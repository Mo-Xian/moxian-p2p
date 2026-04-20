package com.cp12064.moxianp2p

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 进程外单例：托管 moxian-client 子进程 + 日志流
 * Activity 和 Service 共享同一实例
 */
object ClientController {
    data class Config(
        val nodeId: String,
        val server: String,
        val udp: String,
        val token: String,
        val pass: String,
        val forwards: List<String>,
        val mesh: Boolean = true,
        val verbose: Boolean = true, // 默认开 debug 日志 方便排障
    )

    /** 连接状态：IDLE 未启动；CONNECTING 启动中等待握手；CONNECTED 至少一条隧道已建成 */
    enum class State { IDLE, CONNECTING, CONNECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // replay=400 让 Activity 重建时能立即看到历史日志
    private val _logs = MutableSharedFlow<String>(replay = 400, extraBufferCapacity = 512)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var process: Process? = null
    @Volatile private var readerJob: Job? = null

    fun isRunning(): Boolean = process != null

    fun start(context: Context, cfg: Config): Boolean {
        if (process != null) return true
        try {
            val exe = File(context.applicationInfo.nativeLibraryDir, "libmoxianclient.so")
            if (!exe.exists()) {
                appendLog("[app] binary not found at $exe")
                return false
            }
            val args = buildArgs(exe.absolutePath, cfg)
            appendLog("[app] exec: ${args.joinToString(" ")}")
            val p = ProcessBuilder(args)
                .redirectErrorStream(true)
                .directory(context.filesDir)
                .start()
            process = p
            _running.value = true
            _state.value = State.CONNECTING
            readerJob = scope.launch {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { br ->
                        while (true) {
                            val line = br.readLine() ?: break
                            appendLog(line)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    appendLog("[app] process exited")
                    process = null
                    _running.value = false
                    _state.value = State.IDLE
                }
            }
            return true
        } catch (e: Exception) {
            appendLog("[app] start failed: ${e.message}")
            process = null
            _running.value = false
            _state.value = State.IDLE
            return false
        }
    }

    fun stop() {
        val p = process ?: return
        appendLog("[app] stopping...")
        try { p.destroy() } catch (_: Exception) {}
        scope.launch {
            try {
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                }
            } catch (_: Exception) {}
            process = null
            _running.value = false
            _state.value = State.IDLE
            readerJob?.cancel()
        }
    }

    fun appendLog(line: String) {
        // emit 失败时忽略（缓冲满的罕见情况）
        scope.launch { _logs.emit(line) }
        // 根据关键字切换状态
        when {
            // 见到任一 established 即视为连接成功
            "[forward]" in line && "established" in line -> _state.value = State.CONNECTED
            "[responder]" in line && "established" in line -> _state.value = State.CONNECTED
        }
    }

    private fun buildArgs(exe: String, c: Config): List<String> {
        val list = mutableListOf(
            exe,
            "-id", c.nodeId,
            "-server", c.server,
            "-udp", c.udp,
            "-pass", c.pass,
        )
        if (c.token.isNotEmpty()) list += listOf("-token", c.token)
        c.forwards.forEach { list += listOf("-forward", it) }
        if (c.mesh) list += "-mesh"
        if (c.verbose) list += "-v"
        return list
    }
}
