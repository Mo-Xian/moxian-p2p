package com.cp12064.moxianp2p

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// gomobile bind 生成的类（javapkg=com.cp12064.moxianp2p）
// import mobile.Client as GoClient
// import mobile.LogSink as GoLogSink
// import mobile.Mobile
import mobile.Client as GoClient
import mobile.LogSink as GoLogSink
import mobile.Mobile

/**
 * 进程内单例：持有 Go 侧 mobile.Client + 日志流 + 状态
 * Activity / Service 共享同一实例
 */
object ClientController {
    enum class State { IDLE, CONNECTING, CONNECTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _logs = MutableSharedFlow<String>(replay = 400, extraBufferCapacity = 512)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var native: GoClient? = null

    // Kotlin 实现的 LogSink 传给 Go 侧接收日志
    private val sink = object : GoLogSink {
        override fun log(line: String) {
            scope.launch { _logs.emit(line) }
            // 状态关键字识别
            when {
                "[forward]" in line && "established" in line -> _state.value = State.CONNECTED
                "[responder]" in line && "established" in line -> _state.value = State.CONNECTED
                "process exited" in line -> _state.value = State.IDLE
            }
        }
    }

    fun isRunning(): Boolean = native?.isRunning ?: false

    /** 由 MoxianVpnService 调用 yaml + tunFd 启动 */
    fun start(@Suppress("UNUSED_PARAMETER") context: Context, yamlConfig: String, tunFd: Int): Boolean {
        if (native != null) return true
        return try {
            appendLog("[app] launching Go client (fd=$tunFd)")
            val c = Mobile.newClient(yamlConfig, sink)
            c.start(tunFd.toLong())
            native = c
            _running.value = true
            _state.value = State.CONNECTING
            true
        } catch (e: Throwable) {
            appendLog("[app] start failed: ${e.message}")
            _running.value = false
            _state.value = State.IDLE
            native = null
            false
        }
    }

    fun stop() {
        val c = native ?: return
        appendLog("[app] stopping...")
        try {
            c.stop()
        } catch (_: Throwable) {
        }
        native = null
        _running.value = false
        _state.value = State.IDLE
    }

    fun appendLog(line: String) {
        scope.launch { _logs.emit(line) }
    }
}
