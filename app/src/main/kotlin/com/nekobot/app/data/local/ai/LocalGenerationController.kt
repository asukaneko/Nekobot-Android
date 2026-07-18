package com.nekobot.app.data.local.ai

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 单次本地生成的取消控制器。
 *
 * 协程取消无法立即打断同步工具调用，因此这里同时维护停止标志和当前底层任务，
 * 让停止按钮可以主动取消 HTTP 请求与 shell 进程。
 */
internal class LocalGenerationController {
    private val stopped = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()

    val isStopped: Boolean
        get() = stopped.get()

    fun requestStop() {
        if (!stopped.compareAndSet(false, true)) return
        activeCalls.forEach { call -> runCatching { call.cancel() } }
        activeProcesses.forEach(::stopProcess)
    }

    fun track(call: Call): Call {
        activeCalls.add(call)
        if (isStopped) call.cancel()
        return call
    }

    fun release(call: Call) {
        activeCalls.remove(call)
    }

    fun track(process: Process): Process {
        activeProcesses.add(process)
        if (isStopped) stopProcess(process)
        return process
    }

    fun release(process: Process) {
        activeProcesses.remove(process)
    }

    private fun stopProcess(process: Process) {
        runCatching { process.destroy() }
        if (runCatching { process.isAlive }.getOrDefault(false)) {
            runCatching { process.destroyForcibly() }
        }
    }
}
