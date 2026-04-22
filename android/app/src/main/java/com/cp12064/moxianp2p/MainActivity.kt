package com.cp12064.moxianp2p

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import java.net.URLEncoder

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("moxian", Context.MODE_PRIVATE) }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 拒绝也不影响 Service 只是通知不可见 */ }

    // 扫码回调
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content.isNullOrBlank()) {
            toast("扫码取消")
        } else {
            importFromMoxianUri(content)
        }
    }

    // 相机权限回调（同意后启动扫描）
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchQrScan()
        else toast("需要相机权限才能扫码")
    }

    // auto 模式下 probe 到的 vip 等 VPN 授权通过后用
    @Volatile private var pendingVip: String? = null

    // VPN 授权回调
    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        val vip = pendingVip
        pendingVip = null
        if (result.resultCode == RESULT_OK && vip != null) {
            launchVpnService(vip)
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

        // 首次进入时 打印上次崩溃日志
        MoxianApplication.consumeCrashLog(application)?.let { crash ->
            appendLog("══════ 上次运行崩溃日志 ══════")
            crash.lines().take(80).forEach { appendLog(it) }
            appendLog("══════════════════════════════")
        }

        // 异步检查新版本
        checkForUpdate()

        binding.btnStartStop.setOnClickListener {
            if (ClientController.isRunning()) stopVpn() else startVpn()
        }
        binding.btnClear.setOnClickListener { binding.tvLog.text = "" }
        binding.btnCopy.setOnClickListener { copyLog() }
        binding.btnTest.setOnClickListener { runTest() }
        // 长按复制按钮做 AAR 自检 验证 gomobile 加载正常
        binding.btnCopy.setOnLongClickListener {
            selfTestAar()
            true
        }
        binding.btnQrScan.setOnClickListener { startScanQr() }
        binding.btnQrShow.setOnClickListener { showConfigQr() }
        binding.btnServices.setOnClickListener {
            startActivity(Intent(this, ServiceLauncherActivity::class.java))
        }

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
            ClientController.State.READY -> {
                binding.tvStateDot.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.tvStatus.text = getString(R.string.status_ready)
                binding.pbConnecting.visibility = View.GONE
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
        val vipField = binding.etVip.text.toString().trim()
        if (nodeId.isEmpty() || server.isEmpty() || udp.isEmpty() || pass.isEmpty()) {
            toast("node_id / server / udp / pass 必填")
            return
        }
        if (vipField.isEmpty() ||
            (vipField != "auto" && !vipField.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))))  {
            toast("virtual_ip 必须是 auto 或 x.x.x.x")
            return
        }
        saveConfig()
        binding.tvLog.text = ""

        if (vipField == "auto") {
            // 先 probe 拿 server 分配的 IP
            probeThenLaunch()
        } else {
            requestVpnThenLaunch(vipField)
        }
    }

    private fun probeThenLaunch() {
        binding.btnStartStop.isEnabled = false
        ClientController.appendLog("[app] 正在向 server 请求分配虚拟 IP...")
        lifecycleScope.launch {
            val vip = withContext(Dispatchers.IO) {
                try {
                    com.cp12064.moxianp2p.mobile.Mobile.prepareVip(buildYaml())
                } catch (e: Throwable) {
                    ClientController.appendLog("[app] prepareVip failed: ${e.message}")
                    null
                }
            }
            binding.btnStartStop.isEnabled = true
            if (vip.isNullOrBlank()) {
                toast("获取 vip 失败（确认 server 已配置 virtual_subnet）")
                return@launch
            }
            ClientController.appendLog("[app] 分配到 vip = $vip")
            requestVpnThenLaunch(vip)
        }
    }

    private fun requestVpnThenLaunch(vip: String) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingVip = vip
            ClientController.appendLog("[app] 请求 VPN 授权...")
            vpnPermLauncher.launch(prepareIntent)
        } else {
            launchVpnService(vip)
        }
    }

    private fun launchVpnService(vip: String) {
        val yaml = buildYaml()
        val intent = MoxianVpnService.buildStartIntent(this, yaml, vip)
        // VpnService 不用 startForegroundService（Android 会自动把它当前台服务）
        startService(intent)
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
    // 优先: forward 规则第一条的 local 地址
    // 否则: 从日志里最近看到的对端 vIP 猜一个 (默认 :80 可手动改)
    // 最后兜底: 10.88.0.2:80
    private fun runTest() {
        val rules = parseForwards(binding.etForwards.text.toString())
        val defaultTarget: String = when {
            rules.isNotEmpty() -> {
                val parts = rules.first().split("=")
                if (parts.size == 3) "http://${parts[0]}/" else "http://127.0.0.1:18080/"
            }
            else -> {
                // TUN 模式 从日志里找对端 vIP
                val peerVip = lastSeenPeerVip() ?: "10.88.0.2"
                "http://$peerVip:80/"
            }
        }
        promptTestUrl(defaultTarget) { url ->
            appendLog("[test] GET $url")
            doTest(url)
        }
    }

    private fun lastSeenPeerVip(): String? {
        // 从日志文本里找 "vip=10.88.0.X" (非自己的 vIP)
        val self = binding.etVip.text.toString().trim()
        val pattern = Regex("""vip=(10\.88\.0\.\d+)""")
        val hits = pattern.findAll(binding.tvLog.text.toString()).map { it.groupValues[1] }.toSet()
        return hits.firstOrNull { it != self && it != "auto" }
    }

    private fun promptTestUrl(default: String, onConfirm: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(default)
            setSelection(default.length)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("测试 URL")
            .setView(input)
            .setPositiveButton("GET") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) onConfirm(url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doTest(url: String) {
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

    // 长按复制按钮触发 AAR 自检：单独调 Mobile.version() 看 gomobile native lib 能不能加载
    private fun selfTestAar() {
        appendLog("[selftest] 检查 gomobile AAR 加载...")
        val version = try {
            com.cp12064.moxianp2p.mobile.Mobile.version()
        } catch (e: Throwable) {
            appendLog("[selftest] ❌ 加载失败: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        appendLog("[selftest] ✅ AAR OK version=$version")
    }

    // ---- 配置持久化 ----
    private fun restoreConfig() {
        binding.etNodeId.setText(prefs.getString("node_id", "phone"))
        binding.etServer.setText(prefs.getString("server", "ws://139.224.1.83:7788/ws"))
        binding.etUdp.setText(prefs.getString("udp", "139.224.1.83:7789"))
        binding.etToken.setText(prefs.getString("token", ""))
        binding.etPass.setText(prefs.getString("pass", ""))
        binding.etVip.setText(prefs.getString("vip", "auto"))
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

    // ---- 二维码扫描 / 生成 ----
    // URL scheme: moxian://import?n=nodeId&s=server&u=udp&t=token&p=pass&v=vip&m=mesh
    private fun startScanQr() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchQrScan()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchQrScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("扫描 moxian-p2p 配置二维码")
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(options)
    }

    private fun importFromMoxianUri(raw: String) {
        val uri = try { Uri.parse(raw) } catch (e: Exception) { null }
        if (uri == null || uri.scheme != "moxian" || uri.host != "import") {
            toast("不是 moxian 配置二维码：$raw")
            return
        }
        uri.getQueryParameter("n")?.let { binding.etNodeId.setText(it) }
        uri.getQueryParameter("s")?.let { binding.etServer.setText(it) }
        uri.getQueryParameter("u")?.let { binding.etUdp.setText(it) }
        uri.getQueryParameter("t")?.let { binding.etToken.setText(it) }
        uri.getQueryParameter("p")?.let { binding.etPass.setText(it) }
        uri.getQueryParameter("v")?.let { binding.etVip.setText(it) }
        uri.getQueryParameter("m")?.toBooleanStrictOrNull()?.let { binding.cbMesh.isChecked = it }
        saveConfig()
        toast("配置已导入")
    }

    private fun buildMoxianUri(): String {
        fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        val n = enc(binding.etNodeId.text.toString().trim())
        val s = enc(binding.etServer.text.toString().trim())
        val u = enc(binding.etUdp.text.toString().trim())
        val t = enc(binding.etToken.text.toString().trim())
        val p = enc(binding.etPass.text.toString().trim())
        val v = enc(binding.etVip.text.toString().trim())
        val m = binding.cbMesh.isChecked
        return "moxian://import?n=$n&s=$s&u=$u&t=$t&p=$p&v=$v&m=$m"
    }

    private fun showConfigQr() {
        val uri = buildMoxianUri()
        val size = 720
        val matrix = try {
            MultiFormatWriter().encode(uri, BarcodeFormat.QR_CODE, size, size)
        } catch (e: Exception) {
            toast("生成 QR 失败: ${e.message}")
            return
        }
        val bitmap = BarcodeEncoder().createBitmap(matrix)
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setPadding(24, 24, 24, 24)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("分享此配置给其他节点")
            .setMessage("让其他设备 APP 点\"扫码\"扫这个二维码 自动填入配置\n⚠️ 内含 token/pass 注意不要外泄")
            .setView(imageView)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ---- 自动升级检查 ----
    private fun checkForUpdate() {
        lifecycleScope.launch(Dispatchers.IO) {
            val tag = try {
                val url = URL("https://api.github.com/repos/Mo-Xian/moxian-p2p/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                Regex("""\"tag_name\"\s*:\s*\"([^\"]+)\"""").find(json)?.groupValues?.get(1)
            } catch (_: Exception) { null } ?: return@launch

            val latestClean = tag.removePrefix("v")
            val current = BuildConfig.VERSION_NAME
            if (latestClean != current) {
                withContext(Dispatchers.Main) {
                    binding.tvUpdate.visibility = View.VISIBLE
                    binding.tvUpdate.text = "🔔 发现新版 $tag（当前 v$current）点此下载"
                    binding.tvUpdate.setOnClickListener {
                        val uri = Uri.parse("https://github.com/Mo-Xian/moxian-p2p/releases/tag/$tag")
                        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

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
