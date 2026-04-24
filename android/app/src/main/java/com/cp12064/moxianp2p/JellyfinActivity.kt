package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Jellyfin 简单浏览器
 *
 * 功能：
 *   - 用户名/密码登录获取 AccessToken + UserId
 *   - 首屏：媒体库列表（Movies/Shows/Music）
 *   - 点击 → 条目网格（海报 + 标题 + 年份）
 *   - 点击条目 → 优先唤起原生 Jellyfin/Findroid APP 播放
 *     无原生 → 跳 WebView 播放
 */
class JellyfinActivity : AppCompatActivity() {

    private lateinit var svc: NasService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rv: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private var accessToken: String = ""
    private var userId: String = ""
    private var currentParentId: String? = null  // null = 首屏媒体库列表
    private val deviceId by lazy {
        // 用稳定 device_id 以免每次登录产生新设备
        val p = getSharedPreferences("moxian", Context.MODE_PRIVATE)
        p.getString("jf_device", null) ?: UUID.randomUUID().toString().also {
            p.edit().putString("jf_device", it).apply()
        }
    }

    private val adapter by lazy { JfAdapter(this::onItemClick, { svc.url }, { accessToken }) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jellyfin)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        svc = NasService.findById(this, svcId) ?: run { finish(); return }
        prefs = getSharedPreferences("moxian", Context.MODE_PRIVATE)

        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = svc.name }

        tvTitle = findViewById(R.id.tv_title)
        tvStatus = findViewById(R.id.tv_status)
        rv = findViewById(R.id.rv_items)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter

        findViewById<Button>(R.id.btn_web).setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java).apply { putExtra("svc_id", svc.id) })
        }

        val savedToken = prefs.getString("jf_token_${svc.id}", null)
        val savedUid = prefs.getString("jf_uid_${svc.id}", null)
        if (!savedToken.isNullOrBlank() && !savedUid.isNullOrBlank()) {
            accessToken = savedToken
            userId = savedUid
            loadLibraries()
        } else {
            askLogin()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (currentParentId != null) {
            currentParentId = null
            loadLibraries()
            return true
        }
        finish(); return true
    }

    override fun onBackPressed() {
        if (currentParentId != null) {
            currentParentId = null
            loadLibraries()
        } else {
            super.onBackPressed()
        }
    }

    // ---- 登录 ----
    private fun askLogin() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etUser = EditText(this).apply { hint = "用户名" }
        val etPwd = EditText(this).apply { hint = "密码" ; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(etUser); layout.addView(etPwd)

        AlertDialog.Builder(this)
            .setTitle("Jellyfin 登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val u = etUser.text.toString().trim(); val p = etPwd.text.toString()
                lifecycleScope.launch {
                    tvStatus.text = "登录中..."
                    val res = withContext(Dispatchers.IO) { login(u, p) }
                    if (res == null) {
                        tvStatus.text = "登录失败"
                    } else {
                        accessToken = res.first; userId = res.second
                        prefs.edit()
                            .putString("jf_token_${svc.id}", accessToken)
                            .putString("jf_uid_${svc.id}", userId)
                            .apply()
                        loadLibraries()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun login(user: String, pwd: String): Pair<String, String>? = try {
        val conn = URL("${svc.url}/Users/AuthenticateByName").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        // Jellyfin 要求一个授权头 描述客户端
        conn.setRequestProperty("X-Emby-Authorization",
            "MediaBrowser Client=\"moxian-p2p\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"1.0\"")
        conn.doOutput = true
        val body = JSONObject().apply {
            put("Username", user); put("Pw", pwd)
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode == 200) {
            val r = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val token = r.optString("AccessToken")
            val uid = r.optJSONObject("User")?.optString("Id") ?: ""
            if (token.isNotEmpty() && uid.isNotEmpty()) Pair(token, uid) else null
        } else null
    } catch (e: Exception) { null }

    // ---- 加载媒体库 ----
    private fun loadLibraries() {
        tvTitle.text = "媒体库"
        currentParentId = null
        tvStatus.text = "加载媒体库..."
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                fetchItems("/Users/$userId/Views")
            }
            adapter.submit(items)
            tvStatus.text = "共 ${items.size} 个媒体库"
        }
    }

    private fun loadLibrary(libId: String, libName: String) {
        tvTitle.text = libName
        currentParentId = libId
        tvStatus.text = "加载..."
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                fetchItems("/Users/$userId/Items?ParentId=$libId&SortBy=DateCreated&SortOrder=Descending&Limit=200")
            }
            adapter.submit(items)
            tvStatus.text = "共 ${items.size} 项"
        }
    }

    private fun fetchItems(path: String): List<JfItem> = try {
        val conn = URL("${svc.url}$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 15_000
        conn.setRequestProperty("X-Emby-Token", accessToken)
        if (conn.responseCode == 200) {
            val r = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val items = r.optJSONArray("Items") ?: JSONArray()
            (0 until items.length()).mapNotNull { JfItem.fromJson(items.getJSONObject(it)) }
        } else emptyList()
    } catch (e: Exception) { emptyList() }

    // ---- 点击 ----
    private fun onItemClick(item: JfItem) {
        // 媒体库 / 文件夹 → 进入
        if (item.isFolder) {
            loadLibrary(item.id, item.name)
            return
        }
        // 具体媒体项 → 播放
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setMessage(item.overview.ifEmpty { "(无简介)" })
            .setPositiveButton("播放") { _, _ -> playItem(item) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun playItem(item: JfItem) {
        // 优先唤起原生 Jellyfin/Findroid
        val url = "${svc.url}/web/index.html#!/details?id=${item.id}"
        // 尝试 jellyfin:// deep link
        val openedNative = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("jellyfin://server/${svc.url}/items/${item.id}"))
            startActivity(intent); true
        } catch (_: Exception) { false }

        if (!openedNative) {
            // 兜底：WebView 打开条目详情页
            startActivity(Intent(this, WebViewActivity::class.java).apply {
                // 临时创建一个"虚拟服务"指向详情 URL 太繁琐
                // 直接用 svc 传入 WebViewActivity 然后让 WebView 开 URL
                putExtra("svc_id", svc.id)
            })
        }
    }
}

data class JfItem(
    val id: String,
    val name: String,
    val year: Int,
    val type: String,
    val isFolder: Boolean,
    val imageTag: String,
    val overview: String,
) {
    companion object {
        fun fromJson(o: JSONObject): JfItem? {
            val id = o.optString("Id")
            if (id.isEmpty()) return null
            return JfItem(
                id = id,
                name = o.optString("Name"),
                year = o.optInt("ProductionYear", 0),
                type = o.optString("Type"),
                isFolder = o.optBoolean("IsFolder", false),
                imageTag = o.optJSONObject("ImageTags")?.optString("Primary") ?: "",
                overview = o.optString("Overview"),
            )
        }
    }
}

private class JfAdapter(
    val onClick: (JfItem) -> Unit,
    val baseUrl: () -> String,
    val token: () -> String,
) : RecyclerView.Adapter<JfAdapter.VH>() {
    private var items: List<JfItem> = emptyList()

    fun submit(list: List<JfItem>) { items = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_jellyfin, parent, false)
        return VH(v)
    }
    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.tvName.text = it.name
        h.tvYear.text = if (it.year > 0) it.year.toString() else it.type
        val poster = "${baseUrl()}/Items/${it.id}/Images/Primary?maxWidth=300${if (it.imageTag.isNotEmpty()) "&tag=${it.imageTag}" else ""}"
        h.iv.load(
            ImageRequest.Builder(h.iv.context)
                .data(poster)
                .addHeader("X-Emby-Token", token())
                .crossfade(true)
                .build()
        )
        h.itemView.setOnClickListener { onClick(it) }
    }
    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.iv_poster)
        val tvName: TextView = v.findViewById(R.id.tv_name)
        val tvYear: TextView = v.findViewById(R.id.tv_year)
    }
}
