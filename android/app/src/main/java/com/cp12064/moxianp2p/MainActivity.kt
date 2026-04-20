package com.cp12064.moxianp2p

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cp12064.moxianp2p.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("moxian", Context.MODE_PRIVATE) }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响 Service 只是通知不可见 */ }

    // VPN 授权回调
    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            launchVpnService()
        } else {
            toast("VPN 授权被拒绝")
            ClientController.appendLog("[app] 用户拒绝了 VPN 授权")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()
        restoreConfig()
        requestNotificationPermissionIfNeeded()

        binding.btnStartStop.setOnClickListener {
            if (ClientController.isRunning()) stopVpn() else startVpn()
        }
        binding.btnClear.setOnClickListener { binding.tvLog.text = "" }
        binding.btnCopy.setOnClickListener { copyLog() }
        binding.btnTest.setOnClickListener { runTest() }

        observeControllerState()
    }

    // ---- 订阅 ClientController flow ----
    private fun observeControllerState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ClientController.running
                    .onEach { running -> setRunningUi(running) }
                    .launchIn(this)
                ClientController.state
                    .onEach { st -> applyState(st) }
                    .launchIn(this)
                ClientController.logs
                    .onEach { line -> appendLog(line) }
                    .launchIn(this)
            }
        }
    }

    private fun applyState(st: ClientController.State) {
        when (st) {
            ClientController.State.IDLE -> {
                binding.tvStateDot.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvStatus.text = getString(R.string.status_idle)
                binding.pbConnecting.visibility = View.GONE
            }
            ClientController.State.CONNECTING -> {
                binding.tvStateDot.setTextColor(ContextCompat.getColor(this, R.color.red))
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.pbConnecting.visibility = View.VISIBLE
            }
            ClientController.State.CONNECTED -> {
                binding.tvStateDot.setTextColor(ContextCompat.getColor(this, R.color.green))
                binding.tvStatus.text = getString(R.string.status_connected)
                binding.pbConnecting.visibility = View.GONE
            }
        }
    }

    // ---- 启停 VPN ----
    private fun startVpn() {
        val nodeId = binding.etNodeId.text.toString().trim()
        val server = binding.etServer.text.toString().trim()
        val udp = binding.etUdp.text.toString().trim()
        val pass = binding.etPass.text.toString().trim()
        val vip = binding.etVip.text.toString().trim()
        if (nodeId.isEmpty() || server.isEmpty() || udp.isEmpty() || pass.isEmpty()) {
            toast("node_id / server / udp / pass 必填")
            return
        }
        if (vip.isEmpty() || !vip.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
            toast("virtual_ip 必填 例 10.88.0.10")
            return
        }
        saveConfig()
        binding.tvLog.text = ""

        // 请求 VPN 授权
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            ClientController.appendLog("[app] 请求 VPN 授权...")
            vpnPermLauncher.launch(prepareIntent)
        } else {
            launchVpnService()
        }
    }

    private fun launchVpnService() {
        val yaml = buildYaml()
        val vip = binding.etVip.text.toString().trim()
        val intent = MoxianVpnService.buildStartIntent(this, yaml, vip)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpn() {
        stopService(Intent(this, MoxianVpnService::class.java))
    }

    // 把 UI 字段拼成 Go 侧可解析的 yaml 字符串
    private fun buildYaml(): String {
        val nodeId = binding.etNodeId.text.toString().trim()
        val server = binding.etServer.text.toString().trim()
        val udp = binding.etUdp.text.toString().trim()
        val token = binding.etToken.text.toString().trim()
        val pass = binding.etPass.text.toString().trim()
        val vip = binding.etVip.text.toString().trim()
        val mesh = binding.cbMesh.isChecked
        val forwards = parseForwards(binding.etForwards.text.toString())

        val sb = StringBuilder()
        sb.appendLine("node_id: \"$nodeId\"")
        sb.appendLine("server: \"$server\"")
        sb.appendLine("server_udp: \"$udp\"")
        if (token.isNotEmpty()) sb.appendLine("token: \"$token\"")
        sb.appendLine("pass: \"$pass\"")
        sb.appendLine("virtual_ip: \"$vip\"")
        sb.appendLine("mesh: $mesh")
        sb.appendLine("verbose: true")
        if (forwards.isNotEmpty()) {
            sb.appendLine("forwards:")
            for (f in forwards) {
                val parts = f.split("=")
                if (parts.size == 3) {
                    sb.appendLine("  - local: \"${parts[0]}\"")
                    sb.appendLine("    peer: \"${parts[1]}\"")
                    sb.appendLine("    target: \"${parts[2]}\"")
                }
            }
        }
        return sb.toString()
    }

    // ---- 测试按钮 ----
    private fun runTest() {
        val rules = parseForwards(binding.etForwards.text.toString())
        if (rules.isEmpty()) { appendLog("[test] no forward rule"); return }
        val parts = rules.first().split("=")
        if (parts.size != 3) { appendLog("[test] invalid rule: ${rules.first()}"); return }
        val url = "http://${parts[0]}/"
        appendLog("[test] GET $url")
        lifecycleScope.launch(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "moxian-test")
                }
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val elapsed = System.currentTimeMillis() - start
                val preview = body.take(200).replace("\n", " ").replace("\r", "")
                withContext(Dispatchers.Main) {
                    appendLog("[test] ← HTTP $code  ${body.length} bytes  ${elapsed}ms")
                    if (preview.isNotEmpty()) appendLog("[test]   body: $preview")
                }
            } catch (e: Exception) {
                val elapsed = System.currentTimeMillis() - start
                withContext(Dispatchers.Main) {
                    appendLog("[test] ← FAIL after ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("moxian-p2p log", binding.tvLog.text))
        toast(getString(R.string.copied))
    }

    // ---- 配置持久化 ----
    private fun restoreConfig() {
        binding.etNodeId.setText(prefs.getString("node_id", "phone"))
        binding.etServer.setText(prefs.getString("server", "ws://139.224.1.83:7788/ws"))
        binding.etUdp.setText(prefs.getString("udp", "139.224.1.83:7789"))
        binding.etToken.setText(prefs.getString("token", ""))
        binding.etPass.setText(prefs.getString("pass", ""))
        binding.etVip.setText(prefs.getString("vip", "10.88.0.10"))
        binding.etForwards.setText(prefs.getString("forwards", ""))
        binding.cbMesh.isChecked = prefs.getBoolean("mesh", true)
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("node_id", binding.etNodeId.text.toString().trim())
            .putString("server", binding.etServer.text.toString().trim())
            .putString("udp", binding.etUdp.text.toString().trim())
            .putString("token", binding.etToken.text.toString().trim())
            .putString("pass", binding.etPass.text.toString().trim())
            .putString("vip", binding.etVip.text.toString().trim())
            .putString("forwards", binding.etForwards.text.toString())
            .putBoolean("mesh", binding.cbMesh.isChecked)
            .apply()
    }

    private fun parseForwards(raw: String): List<String> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    // ---- UI 状态 ----
    private fun setRunningUi(running: Boolean) {
        binding.btnStartStop.text = getString(if (running) R.string.btn_stop else R.string.btn_start)
        binding.btnTest.visibility = if (running) View.VISIBLE else View.GONE
        val inputs = arrayOf<View>(
            binding.etNodeId, binding.etServer, binding.etUdp,
            binding.etToken, binding.etPass, binding.etVip,
            binding.etForwards, binding.cbMesh,
        )
        inputs.forEach { it.isEnabled = !running }
    }

    private fun appendLog(line: String) {
        val tv = binding.tvLog
        tv.append(line)
        tv.append("\n")
        val count = tv.text.count { it == '\n' }
        if (count > 500) {
            val keep = tv.text.toString().lines().takeLast(400).joinToString("\n")
            tv.text = keep
        }
        tv.post {
            val scrollAmount = tv.layout?.getLineTop(tv.lineCount) ?: 0
            val delta = scrollAmount - tv.height
            if (delta > 0) tv.scrollTo(0, delta)
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
