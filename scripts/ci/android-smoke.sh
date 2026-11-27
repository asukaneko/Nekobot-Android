#!/usr/bin/env bash
set -euo pipefail

test "${CI:-}" = "true"

gradle() {
  local heap_limit="$1"
  shift
  ./gradlew --no-daemon --no-parallel --console=plain --max-workers=2 \
    "-Dorg.gradle.jvmargs=-Xmx${heap_limit} -Xss8m -Dfile.encoding=UTF-8" \
    -Pkotlin.incremental=false -Pnekobot.testAbi=x86_64 "$@"
}

# 在启动模拟器前完成两个模块的编译、DEX、R8 和签名。
if [[ "${1:-}" == "--prepare" ]]; then
  gradle 6g :app:assembleDebug :app:assembleDebugAndroidTest :lianxu:assembleDebugAndroidTest
  mkdir .signing
  trap 'rm -f .signing/legacy-debug.keystore; rmdir .signing' EXIT
  keytool -genkeypair -noprompt -keystore .signing/legacy-debug.keystore \
    -storepass android -keypass android -alias androiddebugkey \
    -dname 'CN=Android CI' -keyalg RSA -keysize 2048 -validity 2
  gradle 6g :app:assembleRelease
  exit 0
fi

# 安装、清理和测试只在 CI 模拟器上执行。
test "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = "1"
mkdir -p app/build/reports/smoke
adb logcat -c
test_status=0
gradle 4g :lianxu:connectedDebugAndroidTest || test_status=1
gradle 4g :app:connectedDebugAndroidTest || test_status=1
adb logcat -d -s NekoPerformance:I > app/build/reports/smoke/session-query.txt

# 设备测试结束时 Gradle 已卸载过包，这里只保证安装环境干净：不存在的包不算失败。
adb uninstall com.nekobot.app > /dev/null 2>&1 || true
# -g：安装时直接授予运行时权限。否则首启会弹系统权限页并顶在最前面，
# 后续 am start 被投递给权限页，应用进程根本起不来。
adb install -g app/build/outputs/apk/release/app-release.apk
adb logcat -c
for attempt in 1 2 3 4 5; do
  adb shell am force-stop com.nekobot.app
  adb shell am start -W -n com.nekobot.app/.MainActivity \
    | tee "app/build/reports/smoke/startup-${attempt}.txt"
  grep -q 'Status: ok' "app/build/reports/smoke/startup-${attempt}.txt"
  # 必须真的启动到本应用自己的 Activity：落到权限页等其它进程不算启动成功。
  grep -q 'Activity: com\.nekobot\.app/' "app/build/reports/smoke/startup-${attempt}.txt"
  sleep 2
  # 启动成功不代表进程还活着：崩溃时先保存日志再失败。
  if ! adb shell pidof com.nekobot.app; then
    adb logcat -d -s AndroidRuntime:E > app/build/reports/smoke/runtime.txt
    cat app/build/reports/smoke/runtime.txt
    exit 1
  fi
done
adb logcat -d -s AndroidRuntime:E > app/build/reports/smoke/runtime.txt
# 只把本应用的致命异常算作失败：系统组件（权限弹窗等）的崩溃与本次冒烟无关。
if grep -A4 'FATAL EXCEPTION' app/build/reports/smoke/runtime.txt | grep -q 'com\.nekobot\.app'; then
  cat app/build/reports/smoke/runtime.txt
  exit 1
fi
exit "$test_status"
