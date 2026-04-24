package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Syncthing 状态查看器（只读）
 *
 * API 见 https://docs.syncthing.net/dev/rest.html
 * 认证：需要 API Key（用户设置 → API 密钥 里拿）
 *
 * 显示：
 *   - 整体状态（启动时间 / 版本）
 *   - 共享文件夹列表 + 同步进度 + 状态
 *   - 对端设备列表 + 在线/离线 + 最后连接时间
 *   - 复杂配置仍跳 Web UI
 */
class SyncthingActivity : AppCompatActivity() {

    private lateinit var svc: NasService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tvSummary: TextView
    private lateinit var llFolders: LinearLayout
    private lateinit var llDevices: LinearLayout
    private var apiKey: String = ""
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_syncthing)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        svc = NasService.findById(this, svcId) ?: run { finish(); return }
        prefs = AuthStore.prefs(this)
        // v2: Vault 优先（把 api key 当 token 存到 vault 的 password 字段）
        VaultSync.pullTokenToPrefs(svc.id, prefs, "sync_apikey_${svc.id}")

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = svc.name
        }

        tvSummary = findViewById(R.id.tv_summary)
        llFolders = findViewById(R.id.ll_folders)
        llDevices = findViewById(R.id.ll_devices)

        findViewById<Button>(R.id.btn_web).setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java).apply {
                putExtra("svc_id", svc.id)
            })
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val saved = prefs.getString("sync_apikey_${svc.id}", null)
                if (saved.isNullOrBlank()) askApiKey() else {
                    apiKey = saved
                    startPolling()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun askApiKey() {
        val et = EditText(this).apply {
            hint = "Syncthing API Key（Web UI → 设置 → 通用 → API 密钥）"
        }
        tvSummary.text = "需要 API Key 点 Web 按钮去 Syncthing 设置里拷贝"
        AlertDialog.Builder(this)
            .setTitle("Syncthing API Key")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val k = et.text.toString().trim()
                if (k.isEmpty()) { finish(); return@setPositiveButton }
                apiKey = k
                prefs.edit().putString("sync_apikey_${svc.id}", k).apply()
                // v2: 同步 API key 到 Vault
                VaultSync.pushFromPrefs(this@SyncthingActivity, this@SyncthingActivity, svc.id, "", k)
                startPolling()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val status = fetch("/rest/system/status")
                val config = fetch("/rest/config")
                val conns = fetch("/rest/system/connections")
                withContext(Dispatchers.Main) {
                    render(status, config, conns)
                }
                delay(5000)
            }
        }
    }

    private fun fetch(path: String): String? = try {
        val conn = URL("${svc.url}$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 10_000
        conn.setRequestProperty("X-API-Key", apiKey)
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    private fun render(status: String?, config: String?, conns: String?) {
        if (status == null) {
            tvSummary.text = "连接失败 检查 API Key 或 URL"
            return
        }
        val statusObj = try { JSONObject(status) } catch (_: Exception) { null }
        val uptime = statusObj?.optLong("uptime") ?: 0
        val myId = statusObj?.optString("myID") ?: ""
        tvSummary.text = "运行 ${formatUptime(uptime)} · ID ${myId.take(7)}..."

        // folders
        llFolders.removeAllViews()
        val cfgObj = try { JSONObject(config ?: "") } catch (_: Exception) { null }
        val folders = cfgObj?.optJSONArray("folders") ?: JSONArray()
        for (i in 0 until folders.length()) {
            val f = folders.getJSONObject(i)
            llFolders.addView(folderRow(f.optString("label").ifEmpty { f.optString("id") },
                f.optString("path"), f.optString("id")))
        }
        if (folders.length() == 0) {
            llFolders.addView(hintRow("无共享文件夹"))
        }

        // devices
        llDevices.removeAllViews()
        val devices = cfgObj?.optJSONArray("devices") ?: JSONArray()
        val connsObj = try { JSONObject(conns ?: "{}").optJSONObject("connections") } catch (_: Exception) { null }
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val devId = d.optString("deviceID")
            if (devId == myId) continue  // 跳过自己
            val connected = connsObj?.optJSONObject(devId)?.optBoolean("connected", false) ?: false
            llDevices.addView(deviceRow(d.optString("name"), devId.take(7), connected))
        }
        if (llDevices.childCount == 0) {
            llDevices.addView(hintRow("无对端设备"))
        }
    }

    private fun folderRow(label: String, path: String, folderId: String): View {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_card))
            setPadding(30, 20, 30, 20)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 8
            layoutParams = lp
        }
        ll.addView(TextView(this).apply {
            text = label
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        ll.addView(TextView(this).apply {
            text = path
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
        })
        ll.addView(TextView(this).apply {
            text = "ID: $folderId"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })
        return ll
    }

    private fun deviceRow(name: String, shortId: String, connected: Boolean): View {
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bg_card))
            setPadding(30, 20, 30, 20)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 8
            layoutParams = lp
        }
        ll.addView(TextView(this).apply {
            text = "●"
            setTextColor(getColor(if (connected) R.color.green else R.color.text_secondary))
            textSize = 14f
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = 16
            layoutParams = lp
        })
        ll.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@SyncthingActivity).apply {
                text = name.ifEmpty { "(未命名)" }
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@SyncthingActivity).apply {
                text = "$shortId${if (connected) " · 已连接" else " · 离线"}"
                setTextColor(getColor(R.color.text_secondary))
                textSize = 11f
            })
        })
        return ll
    }

    private fun hintRow(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        setPadding(30, 20, 30, 20)
    }

    private fun formatUptime(sec: Long): String = when {
        sec > 86400 -> "${sec / 86400}天 ${(sec % 86400) / 3600}时"
        sec > 3600 -> "${sec / 3600}时 ${(sec % 3600) / 60}分"
        sec > 60 -> "${sec / 60}分"
        else -> "${sec}秒"
    }
}
