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

// gomobile bind 生成的类（javapkg=com.cp12064.moxianp2p + go package mobile）
// 生成的 Java 包名 = <javapkg>.<go_package> = com.cp12064.moxianp2p.mobile
import com.cp12064.moxianp2p.mobile.Client as GoClient
import com.cp12064.moxianp2p.mobile.LogSink as GoLogSink
import com.cp12064.moxianp2p.mobile.Mobile

/**
 * 进程内单例：持有 Go 侧 mobile.Client + 日志流 + 状态
 * Activity / Service 共享同一实例
 */
object ClientController {
    // IDLE     - 未启动
    // CONNECTING - 启动中 VPN 拉起 信令 / TUN 未就绪
    // READY    - 本端 VPN 通道已就绪（已注册信令 且 TUN 设备 up）无对端连接
    // CONNECTED - 至少一个对端已建立隧道
    enum class State { IDLE, CONNECTING, READY, CONNECTED }

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
            // 状态关键字识别：
            //   CONNECTED: 至少一路对端隧道 established / 已识别到对端
            //   READY    : 本端已注册信令 或 TUN 设备 up（还没对端 但 VPN 已可用）
            //   IDLE     : 进程退出
            when {
                "[forward]" in line && "established" in line -> _state.value = State.CONNECTED
                "[responder]" in line && "established" in line -> _state.value = State.CONNECTED
                "[mesh] connected to" in line -> _state.value = State.CONNECTED
                "[peerpool] dialed" in line -> _state.value = State.CONNECTED
                "[peerpool] registered inbound" in line -> _state.value = State.CONNECTED
                "[tun] device=" in line -> promoteToReady()
                "[client] registered " in line -> promoteToReady()
                "process exited" in line -> _state.value = State.IDLE
            }
        }
    }

    fun isRunning(): Boolean = native?.isRunning ?: false

    // 已 CONNECTED 不回退到 READY 只允许从 CONNECTING 升到 READY
    private fun promoteToReady() {
        if (_state.value == State.CONNECTING) {
            _state.value = State.READY
        }
    }

    /** 由 MoxianVpnService 调用 yaml + tunFd 启动 */
    fun start(@Suppress("UNUSED_PARAMETER") context: Context, yamlConfig: String, tunFd: Int): Boolean {
        if (native != null) return true
        return try {
            appendLog("[app] launching Go client (fd=$tunFd)")
            val c = Mobile.newClient(yamlConfig, sink)
            c.start(tunFd)
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
