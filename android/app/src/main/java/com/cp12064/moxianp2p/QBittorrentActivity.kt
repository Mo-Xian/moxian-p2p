package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * qBittorrent 简单客户端
 *
 * 支持：
 *   - Web API v2 登录（用户名/密码）
 *   - 种子列表（名称 / 进度 / 状态 / 下载速度）自动每 3 秒刷新
 *   - 单个种子：长按弹菜单 → 恢复 / 暂停 / 删除
 *   - 添加种子：磁力链接或 .torrent URL
 *   - 复杂功能跳 Web UI（Web 按钮）
 */
class QBittorrentActivity : AppCompatActivity() {

    private lateinit var svc: NasService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rv: RecyclerView
    private lateinit var tvStatus: TextView
    private var cookie: String = ""  // SID cookie

    private val adapter = TorrentAdapter(::onTorrentLongPress)
    private val state = MutableStateFlow<List<Torrent>>(emptyList())
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qbittorrent)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        svc = NasService.findById(this, svcId) ?: run { finish(); return }
        prefs = AuthStore.prefs(this)
        // v2: Vault 优先 若已有凭据覆盖本地 prefs
        VaultSync.pullToPrefs(svc.id, prefs, "qbit_user_${svc.id}", "qbit_pass_${svc.id}")

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = svc.name
        }

        rv = findViewById(R.id.rv_torrents)
        tvStatus = findViewById(R.id.tv_status)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<Button>(R.id.btn_add).setOnClickListener { showAddDialog() }
        findViewById<Button>(R.id.btn_web).setOnClickListener {
            startActivity(android.content.Intent(this, WebViewActivity::class.java).apply {
                putExtra("svc_id", svc.id)
            })
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                state.onEach { adapter.submit(it) }.launchIn(this)
                loginAndPoll()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    // ---- 登录 + 轮询 ----
    private suspend fun loginAndPoll() {
        // 保存的登录信息（首次会弹输入）
        var user = prefs.getString("qbit_user_${svc.id}", null)
        var pwd = prefs.getString("qbit_pass_${svc.id}", null)
        if (user.isNullOrBlank()) {
            tvStatus.text = "请登录 qBittorrent"
            askCredentials { u, p ->
                prefs.edit()
                    .putString("qbit_user_${svc.id}", u)
                    .putString("qbit_pass_${svc.id}", p)
                    .apply()
                lifecycleScope.launch { loginAndPoll() }
            }
            return
        }

        val ok = withContext(Dispatchers.IO) { apiLogin(user, pwd ?: "") }
        if (!ok) {
            tvStatus.text = "登录失败 用户名/密码错？"
            askCredentials { u, p ->
                prefs.edit()
                    .putString("qbit_user_${svc.id}", u)
                    .putString("qbit_pass_${svc.id}", p)
                    .apply()
                lifecycleScope.launch { loginAndPoll() }
            }
            return
        }
        // 登录成功 记录到 LastCredentials 供下个服务预填
        AuthStore.saveLastCredentials(this, user, pwd ?: "")
        // v2: 同步到 Vault 加密上传服务器
        VaultSync.pushFromPrefs(this, this, svc.id, user, pwd ?: "")

        // 每 3 秒刷新一次
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val list = apiListTorrents()
                withContext(Dispatchers.Main) {
                    state.value = list
                    tvStatus.text = "共 ${list.size} 个种子 · ${speedSum(list)}"
                }
                delay(3000)
            }
        }
    }

    private fun askCredentials(onOk: (String, String) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etUser = EditText(this).apply { hint = "用户名 默认 admin" }
        val etPwd = EditText(this).apply {
            hint = "密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // 预填上次成功登录的凭据（NAS 各服务常用同一套账号）
        AuthStore.loadLastCredentials(this)?.let { (u, p) ->
            etUser.setText(u); etPwd.setText(p)
        }
        layout.addView(etUser)
        layout.addView(etPwd)
        AlertDialog.Builder(this)
            .setTitle("qBittorrent 登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val u = etUser.text.toString().ifBlank { "admin" }
                onOk(u, etPwd.text.toString())
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    // ---- REST API ----

    private fun apiLogin(user: String, pwd: String): Boolean {
        val body = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pwd, "UTF-8")}"
        return try {
            val conn = openConn("${svc.url}/api/v2/auth/login", "POST", false)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return false
            // Cookie 在 Set-Cookie 头里
            val setCookie = conn.headerFields["Set-Cookie"]?.joinToString("; ")
            if (!setCookie.isNullOrBlank() && "SID=" in setCookie) {
                cookie = setCookie.substringAfter("SID=").substringBefore(";").let { "SID=$it" }
                true
            } else {
                // 有时 200 + Ok. 但无新 cookie（已登录状态）
                val body2 = conn.inputStream.bufferedReader().use { it.readText() }
                body2.trim() == "Ok."
            }
        } catch (e: Exception) { false }
    }

    private fun apiListTorrents(): List<Torrent> = try {
        val conn = openConn("${svc.url}/api/v2/torrents/info", "GET", true)
        if (conn.responseCode != 200) emptyList()
        else {
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            (0 until arr.length()).map { Torrent.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.addedOn }
        }
    } catch (e: Exception) { emptyList() }

    private fun apiAction(action: String, hash: String): Boolean = try {
        val body = "hashes=${URLEncoder.encode(hash, "UTF-8")}"
        val conn = openConn("${svc.url}/api/v2/torrents/$action", "POST", true)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode == 200
    } catch (e: Exception) { false }

    private fun apiDelete(hash: String, deleteFiles: Boolean): Boolean = try {
        val body = "hashes=${URLEncoder.encode(hash, "UTF-8")}&deleteFiles=$deleteFiles"
        val conn = openConn("${svc.url}/api/v2/torrents/delete", "POST", true)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode == 200
    } catch (e: Exception) { false }

    private fun apiAdd(urlOrMagnet: String): Boolean = try {
        val body = "urls=${URLEncoder.encode(urlOrMagnet, "UTF-8")}"
        val conn = openConn("${svc.url}/api/v2/torrents/add", "POST", true)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode == 200
    } catch (e: Exception) { false }

    private fun openConn(url: String, method: String, withCookie: Boolean): HttpURLConnection {
        val u = URL(url)
        val conn = u.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 5000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "moxian-qbit/0.9")
        conn.setRequestProperty("Referer", svc.url)
        if (withCookie && cookie.isNotEmpty()) conn.setRequestProperty("Cookie", cookie)
        return conn
    }

    // ---- UI 交互 ----

    private fun onTorrentLongPress(anchor: View, t: Torrent) {
        PopupMenu(this, anchor).apply {
            menu.add(if (t.isPaused()) "恢复" else "暂停")
            menu.add("删除（保留文件）")
            menu.add("删除（含文件）")
            setOnMenuItemClickListener { item ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = when (item.title) {
                        "恢复" -> apiAction("resume", t.hash)
                        "暂停" -> apiAction("pause", t.hash)
                        "删除（保留文件）" -> apiDelete(t.hash, false)
                        "删除（含文件）" -> apiDelete(t.hash, true)
                        else -> false
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@QBittorrentActivity,
                            if (ok) "操作完成" else "操作失败", Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
        }.show()
    }

    private fun showAddDialog() {
        val et = EditText(this).apply {
            hint = "magnet:?xt=... 或 .torrent URL"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        AlertDialog.Builder(this)
            .setTitle("添加种子")
            .setView(et)
            .setPositiveButton("添加") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty()) return@setPositiveButton
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = apiAdd(v)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@QBittorrentActivity,
                            if (ok) "已添加" else "添加失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

// ---- 数据 ----
data class Torrent(
    val hash: String,
    val name: String,
    val progress: Double,   // 0..1
    val state: String,      // downloading / pausedDL / uploading / ...
    val dlSpeed: Long,      // bytes/s
    val upSpeed: Long,
    val addedOn: Long,
) {
    fun isPaused() = state.startsWith("paused", ignoreCase = true)
    fun statusText(): String = when {
        state == "downloading" -> "下载中"
        state == "uploading"   -> "做种中"
        state == "stalledDL"   -> "等待资源"
        state == "queuedDL"    -> "排队中"
        isPaused()             -> "已暂停"
        state == "error"       -> "错误"
        state == "checkingDL" || state == "checkingUP" -> "校验中"
        state == "missingFiles" -> "文件丢失"
        else -> state
    }

    companion object {
        fun fromJson(o: JSONObject) = Torrent(
            hash = o.optString("hash"),
            name = o.optString("name"),
            progress = o.optDouble("progress", 0.0),
            state = o.optString("state"),
            dlSpeed = o.optLong("dlspeed"),
            upSpeed = o.optLong("upspeed"),
            addedOn = o.optLong("added_on"),
        )
    }
}

private fun speedSum(list: List<Torrent>): String {
    val dl = list.sumOf { it.dlSpeed }
    val up = list.sumOf { it.upSpeed }
    return "↓ ${fmtSpeed(dl)}  ↑ ${fmtSpeed(up)}"
}

private fun fmtSpeed(bps: Long): String = when {
    bps > 1024 * 1024 -> "%.1f MB/s".format(bps / 1024.0 / 1024)
    bps > 1024 -> "%.1f KB/s".format(bps / 1024.0)
    else -> "$bps B/s"
}

private class TorrentAdapter(
    val onLongPress: (View, Torrent) -> Unit,
) : RecyclerView.Adapter<TorrentAdapter.VH>() {
    private var items: List<Torrent> = emptyList()
    fun submit(list: List<Torrent>) { items = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_torrent, parent, false)
    )

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = items[position]
        h.tvName.text = t.name
        val pct = (t.progress * 100).toInt().coerceIn(0, 100)
        h.pbProgress.progress = pct
        h.tvProgressPct.text = "%.1f%%".format(t.progress * 100)
        h.tvStatus.text = t.statusText()
        h.tvSpeed.text = if (t.dlSpeed > 0) "↓ ${fmtSpeed(t.dlSpeed)}" else if (t.upSpeed > 0) "↑ ${fmtSpeed(t.upSpeed)}" else ""
        h.itemView.setOnLongClickListener {
            onLongPress(h.itemView, t); true
        }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tv_name)
        val pbProgress: ProgressBar = v.findViewById(R.id.pb_progress)
        val tvProgressPct: TextView = v.findViewById(R.id.tv_progress_pct)
        val tvStatus: TextView = v.findViewById(R.id.tv_status)
        val tvSpeed: TextView = v.findViewById(R.id.tv_speed)
    }
}
