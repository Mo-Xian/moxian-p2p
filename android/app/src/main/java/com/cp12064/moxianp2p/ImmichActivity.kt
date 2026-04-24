package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.ImageRequest
import coil.decode.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Immich 相册简单客户端
 *
 * 功能：
 *   - email + 密码登录 → accessToken
 *   - 时间轴瀑布流（3 列 缩略图）
 *   - 点击查看大图（ViewPager2 可翻）
 *   - 复杂功能（上传 / 人物 / 地图）跳原生 APP 或 Web
 */
class ImmichActivity : AppCompatActivity() {

    private lateinit var svc: NasService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rv: RecyclerView
    private lateinit var tvStatus: TextView
    private var accessToken: String = ""

    private val allIds = mutableListOf<String>()  // 全部 asset id 按时间倒序
    private val adapter by lazy { PhotoAdapter(svc.url, { accessToken }) { pos ->
        openViewer(pos)
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_immich)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        svc = NasService.findById(this, svcId) ?: run { finish(); return }
        prefs = AuthStore.prefs(this)
        VaultSync.pullTokenToPrefs(svc.id, prefs, "immich_token_${svc.id}")

        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = svc.name }

        tvStatus = findViewById(R.id.tv_status)
        rv = findViewById(R.id.rv_photos)
        rv.layoutManager = GridLayoutManager(this, 3)
        rv.adapter = adapter

        val btnNative = findViewById<Button>(R.id.btn_native)
        if (ServiceLauncher.tryOpenNative(this, svc.copy())) {
            // 装了原生 APP 显示按钮让用户选择
            btnNative.visibility = View.VISIBLE
            btnNative.setOnClickListener { ServiceLauncher.tryOpenNative(this, svc) }
        }
        findViewById<Button>(R.id.btn_web).setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java).apply { putExtra("svc_id", svc.id) })
        }

        val saved = prefs.getString("immich_token_${svc.id}", null)
        if (saved.isNullOrBlank()) askLogin() else {
            accessToken = saved
            loadPhotos()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun askLogin() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etEmail = EditText(this).apply { hint = "邮箱" ; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
        val etPwd = EditText(this).apply { hint = "密码" ; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AuthStore.loadLastCredentials(this)?.let { (u, p) -> etEmail.setText(u); etPwd.setText(p) }
        layout.addView(etEmail); layout.addView(etPwd)

        AlertDialog.Builder(this)
            .setTitle("Immich 登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val email = etEmail.text.toString().trim()
                val pwd = etPwd.text.toString()
                lifecycleScope.launch {
                    tvStatus.text = "登录中..."
                    val token = withContext(Dispatchers.IO) { login(email, pwd) }
                    if (token.isNullOrBlank()) {
                        tvStatus.text = "登录失败"
                    } else {
                        accessToken = token
                        prefs.edit().putString("immich_token_${svc.id}", token).apply()
                        AuthStore.saveLastCredentials(this@ImmichActivity, email, pwd)
                        VaultSync.pushFromPrefs(this@ImmichActivity, this@ImmichActivity, svc.id, email, token, pwd)
                        loadPhotos()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun login(email: String, password: String): String? = try {
        val conn = URL("${svc.url}/api/auth/login").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val body = JSONObject().apply {
            put("email", email); put("password", password)
        }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode == 200 || conn.responseCode == 201) {
            val r = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(r).optString("accessToken")
        } else null
    } catch (e: Exception) { null }

    private fun loadPhotos() {
        tvStatus.text = "加载相册中..."
        lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) { fetchAllAssetIds() }
            allIds.clear(); allIds.addAll(ids)
            adapter.submit(allIds)
            tvStatus.text = "共 ${allIds.size} 张照片 · 点击查看"
        }
    }

    // 通过 /api/search/metadata 拉最近 500 张 IMAGE 的 ID
    private fun fetchAllAssetIds(): List<String> {
        return try {
            val conn = URL("${svc.url}/api/search/metadata").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = """{"order":"desc","size":500}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) return emptyList()
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val r = JSONObject(json)
            // 结构兼容：{assets:{items:[...]}} 或 {items:[...]}
            val items = r.optJSONObject("assets")?.optJSONArray("items")
                ?: r.optJSONArray("items")
                ?: return emptyList()
            (0 until items.length()).mapNotNull {
                val o = items.optJSONObject(it) ?: return@mapNotNull null
                if (o.optString("type").equals("IMAGE", true)) o.optString("id") else null
            }
        } catch (e: Exception) {
            runOnUiThread { tvStatus.text = "加载失败: ${e.message}" }
            emptyList()
        }
    }

    private fun openViewer(startPos: Int) {
        // 把 id 列表传给 viewer activity
        startActivity(Intent(this, PhotoViewerActivity::class.java).apply {
            putExtra("svc_id", svc.id)
            putStringArrayListExtra("ids", ArrayList(allIds))
            putExtra("start", startPos)
            putExtra("token", accessToken)
        })
    }
}

// ---- 缩略图 adapter ----
private class PhotoAdapter(
    val baseUrl: String,
    val tokenProvider: () -> String,
    val onClick: (Int) -> Unit,
) : RecyclerView.Adapter<PhotoAdapter.VH>() {
    private var ids: List<String> = emptyList()
    fun submit(list: List<String>) { ids = list; notifyDataSetChanged() }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
        return VH(v as ImageView)
    }
    override fun onBindViewHolder(h: VH, position: Int) {
        val id = ids[position]
        val url = "$baseUrl/api/asset/$id/thumbnail?size=preview"
        h.iv.load(
            ImageRequest.Builder(h.iv.context)
                .data(url)
                .addHeader("Authorization", "Bearer ${tokenProvider()}")
                .crossfade(true)
                .build()
        )
        h.iv.setOnClickListener { onClick(position) }
    }
    override fun getItemCount() = ids.size

    class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)
}
