package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Navidrome / Subsonic 简单客户端
 *
 * API: Subsonic OpenSubsonic - https://www.subsonic.org/pages/api.jsp
 * 认证: token 模式 md5(password + salt) 避免明文密码
 *
 * 功能：
 *   - 用户名 + 密码 登录（保存后下次免输入）
 *   - 专辑列表（最近添加 / 最多播放）
 *   - 进入专辑 → 曲目列表 → 点击单曲播放
 *   - 后台播放通过 NavPlayerService（前台通知 + MediaSession）
 */
class NavidromeActivity : AppCompatActivity() {

    private lateinit var svc: NasService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var rv: RecyclerView
    private lateinit var playerBar: View
    private lateinit var tvPlayingTitle: TextView
    private lateinit var tvPlayingArtist: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton

    private var username: String = ""
    private var password: String = ""  // 永远不会存明文 仅内存缓存

    private val adapter = NavAdapter(svcUrlProvider = { svc.url }, authProvider = { buildAuthQuery() }) {
        onRowClick(it)
    }
    private enum class Level { ALBUMS, SONGS }
    private var level = Level.ALBUMS
    private var currentAlbumId: String? = null
    private var currentSongs: List<NavRow> = emptyList()
    private var playIndex: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navidrome)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        svc = NasService.findById(this, svcId) ?: run { finish(); return }
        prefs = getSharedPreferences("moxian", android.content.Context.MODE_PRIVATE)

        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = svc.name }

        tvTitle = findViewById(R.id.tv_title)
        tvStatus = findViewById(R.id.tv_status)
        rv = findViewById(R.id.rv_items)
        playerBar = findViewById(R.id.player_bar)
        tvPlayingTitle = findViewById(R.id.tv_playing_title)
        tvPlayingArtist = findViewById(R.id.tv_playing_artist)
        btnPlay = findViewById(R.id.btn_play)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<Button>(R.id.btn_web).setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java).apply { putExtra("svc_id", svc.id) })
        }

        btnPlay.setOnClickListener { NavPlayerService.togglePause(this) }
        btnPrev.setOnClickListener { if (playIndex > 0) playAt(playIndex - 1) }
        btnNext.setOnClickListener { if (playIndex < currentSongs.size - 1) playAt(playIndex + 1) }

        val savedU = prefs.getString("nav_user_${svc.id}", null)
        val savedP = prefs.getString("nav_pass_${svc.id}", null)
        if (savedU.isNullOrBlank() || savedP.isNullOrBlank()) askLogin() else {
            username = savedU; password = savedP
            loadAlbums()
        }

        // 订阅播放状态（简单轮询）
        lifecycleScope.launch {
            while (true) {
                val s = NavPlayerService.currentState
                if (s != null) {
                    playerBar.visibility = View.VISIBLE
                    tvPlayingTitle.text = s.title
                    tvPlayingArtist.text = s.artist
                    btnPlay.setImageResource(
                        if (s.playing) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                    )
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (level == Level.SONGS) { loadAlbums(); return true }
        finish(); return true
    }

    override fun onBackPressed() {
        if (level == Level.SONGS) loadAlbums() else super.onBackPressed()
    }

    private fun askLogin() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24) }
        val etU = EditText(this).apply { hint = "用户名" }
        val etP = EditText(this).apply { hint = "密码" ; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        layout.addView(etU); layout.addView(etP)
        AlertDialog.Builder(this)
            .setTitle("Navidrome 登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                username = etU.text.toString().trim()
                password = etP.text.toString()
                prefs.edit()
                    .putString("nav_user_${svc.id}", username)
                    .putString("nav_pass_${svc.id}", password)
                    .apply()
                loadAlbums()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    // ---- Subsonic auth 构造 token 模式 ----
    private fun buildAuthQuery(): String {
        val salt = (1..12).map { "0123456789abcdef".random() }.joinToString("")
        val token = md5Hex(password + salt)
        return "u=${URLEncoder.encode(username, "UTF-8")}&t=$token&s=$salt&v=1.16.0&c=moxian&f=json"
    }

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ---- 加载 ----
    private fun loadAlbums() {
        level = Level.ALBUMS
        currentAlbumId = null
        tvTitle.text = "专辑"
        tvStatus.text = "加载中..."
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                fetchAlbums()
            }
            adapter.submit(list)
            tvStatus.text = if (list.isNotEmpty()) "共 ${list.size} 张专辑" else "加载失败 检查账号"
        }
    }

    private fun fetchAlbums(): List<NavRow> {
        return try {
            val url = "${svc.url}/rest/getAlbumList2.view?${buildAuthQuery()}&type=newest&size=100"
            val json = URL(url).readText()
            val response = JSONObject(json).optJSONObject("subsonic-response") ?: return emptyList()
            val albums = response.optJSONObject("albumList2")?.optJSONArray("album") ?: return emptyList()
            (0 until albums.length()).map {
                val o = albums.getJSONObject(it)
                NavRow(
                    id = o.optString("id"),
                    title = o.optString("name"),
                    sub = "${o.optString("artist")} · ${o.optInt("year", 0).let { y -> if (y > 0) y.toString() else "" }}",
                    coverArt = o.optString("coverArt"),
                    isAlbum = true,
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun loadSongs(albumId: String, albumName: String) {
        level = Level.SONGS
        currentAlbumId = albumId
        tvTitle.text = albumName
        tvStatus.text = "加载曲目..."
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                fetchAlbumSongs(albumId)
            }
            currentSongs = list
            adapter.submit(list)
            tvStatus.text = if (list.isNotEmpty()) "${list.size} 首" else "空专辑"
        }
    }

    private fun fetchAlbumSongs(albumId: String): List<NavRow> = try {
        val url = "${svc.url}/rest/getAlbum.view?${buildAuthQuery()}&id=$albumId"
        val json = URL(url).readText()
        val r = JSONObject(json).optJSONObject("subsonic-response") ?: JSONObject()
        val songs = r.optJSONObject("album")?.optJSONArray("song") ?: JSONArray()
        (0 until songs.length()).map {
            val o = songs.getJSONObject(it)
            NavRow(
                id = o.optString("id"),
                title = o.optString("title"),
                sub = "${o.optInt("track", 0)} · ${fmtDuration(o.optInt("duration"))}",
                coverArt = o.optString("coverArt"),
                isAlbum = false,
                artist = o.optString("artist"),
            )
        }
    } catch (e: Exception) { emptyList() }

    private fun fmtDuration(sec: Int): String {
        val m = sec / 60; val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    // ---- 点击 ----
    private fun onRowClick(row: NavRow) {
        if (row.isAlbum) {
            loadSongs(row.id, row.title)
        } else {
            val idx = currentSongs.indexOfFirst { it.id == row.id }
            if (idx >= 0) playAt(idx)
        }
    }

    private fun playAt(idx: Int) {
        playIndex = idx
        val song = currentSongs.getOrNull(idx) ?: return
        val streamUrl = "${svc.url}/rest/stream.view?${buildAuthQuery()}&id=${song.id}&format=raw"
        val coverUrl = if (song.coverArt.isNotEmpty())
            "${svc.url}/rest/getCoverArt.view?${buildAuthQuery()}&id=${song.coverArt}&size=300"
        else ""
        NavPlayerService.play(
            this,
            NavTrack(song.title, song.artist, coverUrl, streamUrl),
        )
    }
}

// ---- 数据 ----
data class NavRow(
    val id: String,
    val title: String,
    val sub: String,
    val coverArt: String,
    val isAlbum: Boolean,
    val artist: String = "",
)

private class NavAdapter(
    val svcUrlProvider: () -> String,
    val authProvider: () -> String,
    val onClick: (NavRow) -> Unit,
) : RecyclerView.Adapter<NavAdapter.VH>() {
    private var items: List<NavRow> = emptyList()
    fun submit(list: List<NavRow>) { items = list; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_nav_row, parent, false)
    )
    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        h.tvTitle.text = r.title
        h.tvSub.text = r.sub
        if (r.coverArt.isNotEmpty()) {
            val url = "${svcUrlProvider()}/rest/getCoverArt.view?${authProvider()}&id=${r.coverArt}&size=120"
            h.iv.load(url)
        }
        h.itemView.setOnClickListener { onClick(r) }
    }
    override fun getItemCount() = items.size
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.iv_cover)
        val tvTitle: TextView = v.findViewById(R.id.tv_title)
        val tvSub: TextView = v.findViewById(R.id.tv_sub)
    }
}
