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
import android.widget.Button
import android.widget.TextView
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

    companion object {
        // 后台超过此时间自动锁定（清 masterKey 要求重输主密码）
        private const val AUTO_LOCK_MS = 10 * 60 * 1000L

        @Volatile var lastPauseAt: Long = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v2 登录门禁：未登录跳 LoginActivity 未解锁跳 UnlockActivity
        if (!AuthSession.isLoggedIn()) {
            if (!AuthSession.restoreFromDisk(this)) {
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
                return
            }
        }
        // 后台超时自动锁
        if (lastPauseAt > 0 && System.currentTimeMillis() - lastPauseAt > AUTO_LOCK_MS) {
            AuthSession.lock()
        }
        if (!AuthSession.isUnlocked()) {
            startActivity(Intent(this, UnlockActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvLog.movementMethod = ScrollingMovementMethod()
        restoreConfig()
        // v2：从服务器拉 moxian-p2p 配置自动填入
        fetchServerConfigAsync()
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
        // v2 主页：服务网格 + 日志折叠 + 右上角菜单
        setupServiceGrid()
        setupMenu()
        setupLogToggle()

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
        if (!AuthSession.isLoggedIn()) {
            toast("尚未登录")
            return
        }
        val nodeId = binding.etNodeId.text.toString().trim()
        if (nodeId.isEmpty()) {
            toast("node 名称不能为空")
            return
        }
        saveConfig()
        binding.tvLog.text = ""
        // v2 模式 vip 永远来自 server 分配 先 probe
        probeThenLaunch()
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

    // 拼 v2 yaml 给 Go 侧 mobile.NewClient 用
    // 字段：server (auth base URL) + jwt (Kotlin 已登录的 token) + node + insecure_tls + 行为开关
    // Go 侧用 jwt 直接调 /api/config 拉真实 P2P 配置（pass / server_ws / server_udp / vIP）
    private fun buildYaml(): String {
        val nodeId = binding.etNodeId.text.toString().trim()
        val mesh = binding.cbMesh.isChecked
        val forwards = parseForwards(binding.etForwards.text.toString())

        val sb = StringBuilder()
        sb.appendLine("server: \"${AuthSession.getServerUrl()}\"")
        sb.appendLine("jwt: \"${AuthSession.getJwt()}\"")
        sb.appendLine("node: \"$nodeId\"")
        if (AuthSession.getInsecureTLS()) sb.appendLine("insecure_tls: true")
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
        // node_id 首次启动自动生成并立即持久化 下次打开保持同一 ID
        val savedNodeId = prefs.getString("node_id", null)
        val nodeId = if (savedNodeId.isNullOrBlank()) {
            val auto = autoNodeId()
            prefs.edit().putString("node_id", auto).apply()
            auto
        } else savedNodeId
        binding.etNodeId.setText(nodeId)

        // v2 模式 vip/server/udp/pass/token 全部由 server 下发 用户不感知 留空即可
        binding.etVip.setText("auto")
        binding.etForwards.setText(prefs.getString("forwards", ""))
        binding.cbMesh.isChecked = prefs.getBoolean("mesh", true)

        // 高级选项默认收起
        val showAdvanced = prefs.getBoolean("show_advanced", false)
        binding.cbAdvanced.isChecked = showAdvanced
        binding.advancedSection.visibility = if (showAdvanced) View.VISIBLE else View.GONE
    }

    // autoNodeId 首次使用时自动生成一个唯一 node_id
    // 比如 "phone-小米13-3F8A" 既容易辨识又避免冲突
    private fun autoNodeId(): String {
        val model = Build.MODEL.replace(Regex("[^a-zA-Z0-9\u4e00-\u9fa5]"), "").take(10)
        val suffix = (0..3).map { "0123456789ABCDEF".random() }.joinToString("")
        return "phone-$model-$suffix".ifEmpty { "phone-$suffix" }
    }

    /**
     * v2：从服务器拉 moxian-p2p 配置（node_id / virtual_ip / pass / server_ws / server_udp / allow_peers）
     * 自动填入 UI 用户无需手动配置
     *
     * 流程：
     *   1. 先尝试 GET /api/config?node=<my_node_id> 拿已注册节点配置
     *   2. 若 404（节点未注册）POST /api/nodes {node_id} 注册一个
     *   3. 再 GET 一次拿配置
     */
    private fun fetchServerConfigAsync() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val nodeId = binding.etNodeId.text.toString().trim().ifEmpty { autoNodeId() }
                var resp = AuthSession.httpGet(this@MainActivity, "/api/config?node=$nodeId")
                if (resp == null) {
                    // 尝试注册节点
                    val regBody = org.json.JSONObject().put("node_id", nodeId).toString()
                    AuthSession.httpPostJson(this@MainActivity, "/api/nodes", regBody)
                    resp = AuthSession.httpGet(this@MainActivity, "/api/config?node=$nodeId")
                }
                if (resp == null) {
                    appendLogSafe("[v2] 从服务器拉配置失败 将用本地缓存")
                    return@launch
                }
                val cfg = org.json.JSONObject(resp)
                val srv = cfg.optString("server_ws")
                val udp = cfg.optString("server_udp")
                val pass = cfg.optString("pass")
                val vip = cfg.optString("virtual_ip")
                val mesh = cfg.optBoolean("mesh", true)
                withContext(Dispatchers.Main) {
                    if (srv.isNotEmpty()) binding.etServer.setText(srv)
                    if (udp.isNotEmpty()) binding.etUdp.setText(udp)
                    if (pass.isNotEmpty()) binding.etPass.setText(pass)
                    if (vip.isNotEmpty()) binding.etVip.setText(vip)
                    binding.etNodeId.setText(nodeId)
                    binding.cbMesh.isChecked = mesh
                    updateNodeInfo()
                    appendLogSafe("[v2] 配置已从服务器同步: vip=$vip")
                }
            } catch (e: Exception) {
                appendLogSafe("[v2] 配置同步异常: ${e.message}")
            }
        }
    }

    private fun appendLogSafe(line: String) {
        runOnUiThread { appendLog(line) }
    }

    // ---- v2 主页 UI ----

    private fun setupServiceGrid() {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_services)
        val tvEmpty = findViewById<View>(R.id.tv_empty_services)
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 3)

        val items = NasServiceStore.load(this)
        if (items.isEmpty()) {
            rv.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            rv.visibility = View.VISIBLE
            tvEmpty.visibility = View.GONE
            rv.adapter = MainServiceAdapter(items) { svc ->
                ServiceLauncher.open(this, svc)
            }
        }

        findViewById<Button>(R.id.btn_manage_services).setOnClickListener {
            startActivity(Intent(this, ServiceLauncherActivity::class.java))
        }
    }

    private fun setupMenu() {
        findViewById<android.widget.ImageButton>(R.id.btn_menu).setOnClickListener { anchor ->
            val pop = android.widget.PopupMenu(this, anchor)
            pop.menu.add("👤 账号：${AuthSession.getUsername()}").isEnabled = false
            pop.menu.add("📡 查看 P2P 配置")
            pop.menu.add("🔄 检查更新")
            pop.menu.add("🔒 锁定")
            pop.menu.add("🚪 登出")
            pop.setOnMenuItemClickListener { m ->
                when (m.title?.toString()) {
                    "📡 查看 P2P 配置" -> showP2PConfigDialog()
                    "🔄 检查更新" -> checkForUpdate(silent = false)
                    "🔒 锁定" -> {
                        AuthSession.lock()
                        startActivity(Intent(this, UnlockActivity::class.java))
                        finish()
                    }
                    "🚪 登出" -> {
                        AuthSession.logout(this)
                        startActivity(Intent(this, LoginActivity::class.java))
                        finish()
                    }
                }
                true
            }
            pop.show()
        }
    }

    private fun showP2PConfigDialog() {
        val msg = buildString {
            appendLine("Node ID: ${binding.etNodeId.text}")
            appendLine("虚拟 IP: ${binding.etVip.text}")
            appendLine("Server WS: ${binding.etServer.text}")
            appendLine("Server UDP: ${binding.etUdp.text}")
            append("Passphrase: ${"*".repeat(binding.etPass.text.length.coerceAtLeast(6))}")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("P2P 配置（服务器下发 只读）")
            .setMessage(msg)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun setupLogToggle() {
        val logContainer = findViewById<View>(R.id.log_container)
        val btn = findViewById<Button>(R.id.btn_toggle_log)
        btn.setOnClickListener {
            if (logContainer.visibility == View.VISIBLE) {
                logContainer.visibility = View.GONE
                btn.text = "展开"
            } else {
                logContainer.visibility = View.VISIBLE
                btn.text = "收起"
            }
        }
    }

    // 更新状态卡片里的摘要（连接时显示 node 信息）
    private fun updateNodeInfo() {
        val info = findViewById<TextView>(R.id.tv_node_info)
        val node = binding.etNodeId.text.toString()
        val vip = binding.etVip.text.toString()
        info.text = if (node.isNotEmpty() && vip.isNotEmpty()) "$node · $vip" else ""
    }

    private fun saveConfig() {
        // v2 模式：只持久化 用户可改的字段。pass/server/vip 都是 server 下发 不再本地存。
        prefs.edit()
            .putString("node_id", binding.etNodeId.text.toString().trim())
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
    // 默认 silent=true：发现新版在顶部横幅显示 点击弹详细对话框
    // silent=false（用户手动触发）：无论是否有新版都弹 toast
    private fun checkForUpdate(silent: Boolean = true) {
        lifecycleScope.launch {
            val current = BuildConfig.VERSION_NAME
            val release = AppUpdater.checkLatest(current)
            if (release == null) {
                if (!silent) toast("已是最新版 v$current")
                return@launch
            }
            // 顶部横幅 + 点击打开对话框
            binding.tvUpdate.visibility = View.VISIBLE
            binding.tvUpdate.text = "🔔 发现新版 ${release.tag}（当前 v$current）点此更新"
            binding.tvUpdate.setOnClickListener {
                AppUpdater.promptAndUpdate(this@MainActivity, release, current)
            }
            if (!silent) {
                AppUpdater.promptAndUpdate(this@MainActivity, release, current)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        lastPauseAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // 恢复时若超时则锁定并跳解锁页
        if (lastPauseAt > 0 && System.currentTimeMillis() - lastPauseAt > AUTO_LOCK_MS) {
            AuthSession.lock()
            if (AuthSession.isLoggedIn() && !AuthSession.isUnlocked()) {
                startActivity(Intent(this, UnlockActivity::class.java))
                finish()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 返回主页时刷新服务列表（可能在 ServiceLauncherActivity 里增删过）
        if (::binding.isInitialized) setupServiceGrid()
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
