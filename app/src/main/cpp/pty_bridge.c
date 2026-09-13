// pty_bridge.c — 沙箱终端使用的原生 PTY 桥接层。
//
// Android 的 Java API 只能通过 ProcessBuilder 拿到管道式 stdin/stdout，
// 这种“非 TTY”的子进程既不会打印提示符，也不支持 readline 行编辑、方向键、
// Ctrl+C（SIGINT）、SIGWINCH 以及 vi/top 等全屏程序。
//
// 这里用 bionic 自带的 forkpty() 把 PRoot + /bin/sh 挂到一个真正的伪终端上，
// 再暴露 master fd 的读/写/ioctl/waitpid 给 Kotlin 层，行为与桌面终端一致。
//
// 依赖：bionic libc 自 API 21 起提供 forkpty()。

#include <jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TAG "NekoPty"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/** 把 Java 字符串数组深拷贝成以 NULL 结尾的 C 字符串数组。 */
static char **copy_string_array(JNIEnv *env, jobjectArray array, jsize *out_count)
{
    jsize count = array ? (*env)->GetArrayLength(env, array) : 0;
    char **result = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL) {
        *out_count = 0;
        return NULL;
    }
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        const char *chars = item ? (*env)->GetStringUTFChars(env, item, NULL) : NULL;
        result[i] = strdup(chars != NULL ? chars : "");
        if (item) {
            if (chars) (*env)->ReleaseStringUTFChars(env, item, chars);
            (*env)->DeleteLocalRef(env, item);
        }
    }
    result[count] = NULL;
    *out_count = count;
    return result;
}

static void free_string_array(char **array, jsize count)
{
    if (array == NULL) return;
    for (jsize i = 0; i < count; i++) free(array[i]);
    free(array);
}

/**
 * 在新 PTY 上 fork 并 execve(command)。
 *
 * @return 成功时返回 PTY master fd，失败返回 -errno；子进程 pid 写入 outPid[0]。
 */
JNIEXPORT jint JNICALL
Java_com_nekobot_app_data_local_ai_terminal_PtyBridge_forkExec(
    JNIEnv *env, jclass clazz,
    jstring jCommand, jobjectArray jArgv, jobjectArray jEnvp,
    jstring jCwd, jint cols, jint rows, jintArray outPid)
{
    if (jCommand == NULL || outPid == NULL) return -EINVAL;
    const char *command = (*env)->GetStringUTFChars(env, jCommand, NULL);
    if (command == NULL) return -EINVAL;
    const char *cwd = jCwd ? (*env)->GetStringUTFChars(env, jCwd, NULL) : NULL;

    jsize argc = 0;
    jsize envc = 0;
    char **argv = copy_string_array(env, jArgv, &argc);
    char **envp = copy_string_array(env, jEnvp, &envc);

    int masterFd = -1;
    pid_t pid = -1;

    if (argv == NULL || envp == NULL) {
        errno = ENOMEM;
        goto done;
    }

    struct winsize window;
    memset(&window, 0, sizeof(window));
    window.ws_col = (cols > 0) ? (unsigned short) cols : 80;
    window.ws_row = (rows > 0) ? (unsigned short) rows : 24;

    // 经典终端 termios：内核负责回显与行编辑，ISIG 让 Ctrl+C 直接产生 SIGINT。
    // shell 自己（readline/ash 行编辑）会按需再调用 tcsetattr 覆盖这些值。
    struct termios term;
    memset(&term, 0, sizeof(term));
    term.c_iflag = ICRNL | IXON | IUTF8;
    term.c_oflag = OPOST | ONLCR;
    term.c_cflag = CREAD | CS8 | HUPCL;
    term.c_lflag = ISIG | ICANON | ECHO | ECHOE | ECHOK | IEXTEN;
    cfsetispeed(&term, B38400);
    cfsetospeed(&term, B38400);
    term.c_cc[VINTR] = 0x03;   // Ctrl-C
    term.c_cc[VQUIT] = 0x1c;   // Ctrl-4（SIGQUIT）
    term.c_cc[VERASE] = 0x7f;  // Backspace
    term.c_cc[VKILL] = 0x15;   // Ctrl-U
    term.c_cc[VEOF] = 0x04;    // Ctrl-D
    term.c_cc[VSTART] = 0x11;
    term.c_cc[VSTOP] = 0x13;
    term.c_cc[VSUSP] = 0x1a;   // Ctrl-Z
    term.c_cc[VMIN] = 1;
    term.c_cc[VTIME] = 0;

    pid = forkpty(&masterFd, NULL, &term, &window);
    if (pid < 0) goto done;

    if (pid == 0) {
        // 子进程：只做异步信号安全的操作，随后立刻 execve。
        if (cwd != NULL && *cwd != '\0') {
            if (chdir(cwd) != 0) { /* 目录不可用时留在当前 cwd */ }
        }
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);
        for (int sig = 1; sig < NSIG; sig++) signal(sig, SIG_DFL);
        execve(command, argv, envp);
        _exit(127);
    }

    jint pidValue = (jint) pid;
    (*env)->SetIntArrayRegion(env, outPid, 0, 1, &pidValue);
    LOGI("forkpty ok: pid=%d masterFd=%d cols=%d rows=%d", (int) pid, masterFd, cols, rows);

done:
    {
        int savedErrno = (masterFd < 0 || pid < 0) ? errno : 0;
        free_string_array(argv, argc);
        free_string_array(envp, envc);
        (*env)->ReleaseStringUTFChars(env, jCommand, command);
        if (jCwd && cwd) (*env)->ReleaseStringUTFChars(env, jCwd, cwd);
        if (pid < 0) {
            LOGE("forkpty failed: %s", strerror(savedErrno));
            return -savedErrno;
        }
    }
    return masterFd;
}

/** 从 PTY master 读取数据，返回读到的字节数、0 表示 EOF、负值为 -errno。 */
JNIEXPORT jint JNICALL
Java_com_nekobot_app_data_local_ai_terminal_PtyBridge_readBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length)
{
    if (fd < 0 || buffer == NULL || length <= 0) return -EINVAL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -ENOMEM;

    ssize_t readCount;
    do {
        readCount = read(fd, bytes + offset, (size_t) length);
    } while (readCount < 0 && errno == EINTR);

    int error = (readCount < 0) ? errno : 0;
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, 0);
    return (readCount < 0) ? -error : (jint) readCount;
}

/** 向 PTY master 写入数据，写满 length 字节后返回写入量，负值为 -errno。 */
JNIEXPORT jint JNICALL
Java_com_nekobot_app_data_local_ai_terminal_PtyBridge_writeBytes(
    JNIEnv *env, jclass clazz, jint fd, jbyteArray buffer, jint offset, jint length)
{
    if (fd < 0 || buffer == NULL || length <= 0) return -EINVAL;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -ENOMEM;

    jint written = 0;
    int error = 0;
    while (written < length) {
        ssize_t step = write(fd, bytes + offset + written, (size_t) (length - written));
        if (step < 0) {
            if (errno == EINTR) continue;
            error = errno;
            break;
        }
        if (step == 0) break;
        written += (jint) step;
    }
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    if (error != 0) return -error;
    return written;
}

/** ioctl(TIOCSWINSZ)：改变窗口尺寸，内核会向 shell 发 SIGWINCH。 */
JNIEXPORT jint JNICALL
Java_com_nekobot_app_data_local_ai_terminal_PtyBridge_setWindowSize(
    JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows)
{
    if (fd < 0 || cols <= 0 || rows <= 0) return -EINVAL;
    struct winsize window;
    memset(&window, 0, sizeof(window));
    window.ws_col = (unsigned short) cols;
    window.ws_row = (unsigned short) rows;
    if (ioctl(fd, TIOCSWINSZ, &window) < 0) return -errno;
    return 0;
}

/** 关闭 master fd；重复关闭安全。 */
JNIEXPORT jint JNICALL
Java_com_nekobot_app_data_local_ai_terminal_PtyBridge_closeFd(
    JNIEnv *env, jclass clazz, jint fd)
{
    if (fd < 0) return 0;
    if (close(fd) < 0) return -errno;
    return 0;
}

/** kill(pid, sig)：用于终止 shell 或发送 SIGINT/SIGTERM。 */
JNIEXPORT jint JNICALL
Java_com_nekobot_app_data_local_ai_terminal_PtyBridge_sendSignal(
    JNIEnv *env, jclass clazz, jint pid, jint signalNumber)
{
    if (pid <= 0) return -EINVAL;
    if (kill((pid_t) pid, signalNumber) < 0) return -errno;
    return 0;
}

/**
 * 阻塞等待子进程结束。
 *
 * 正常退出返回退出码；被信号杀死返回 -(128+signal)；waitpid 失败返回 -errno。
 */
JNIEXPORT jint JNICALL
Java_com_nekobot_app_data_local_ai_terminal_PtyBridge_waitFor(
    JNIEnv *env, jclass clazz, jint pid)
{
    if (pid <= 0) return -EINVAL;
    int status = 0;
    pid_t result;
    do {
        result = waitpid((pid_t) pid, &status, 0);
    } while (result < 0 && errno == EINTR);
    if (result < 0) return -errno;

    if (WIFEXITED(status)) return (jint) WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return (jint) -(128 + WTERMSIG(status));
    return -1;
}
