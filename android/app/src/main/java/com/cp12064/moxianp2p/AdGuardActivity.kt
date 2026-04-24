package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
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
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * AdGuard Home 简单客户端
 *
 * 认证：Basic Auth（用户名密码 AdGuard 管理员账号）
 *
 * 功能：
 *   - 状态 + 版本显示
 *   - 今日查询数 + 拦截数 + 拦截率
 *   - 暂停/恢复防护
 *   - Top 10 被拦截域名
 */
class AdGuardActivity : AppCompatActivity() {

    private lateinit var svc: NasService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvBlocked: TextView
    private lateinit var tvBlockedPct: TextView
    private lateinit var btnToggle: Button
    private lateinit var llTopBlocked: LinearLayout
    private var authHeader: String = ""
    private var protectionEnabled = true
    private var pollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adguard)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        svc = NasService.findById(this, svcId) ?: run { finish(); return }
        prefs = AuthStore.prefs(this)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = svc.name
        }

        tvStatus = findViewById(R.id.tv_status)
        tvTotal = findViewById(R.id.tv_total)
        tvBlocked = findViewById(R.id.tv_blocked)
        tvBlockedPct = findViewById(R.id.tv_blocked_pct)
        btnToggle = findViewById(R.id.btn_toggle)
        llTopBlocked = findViewById(R.id.ll_top_blocked)

        findViewById<Button>(R.id.btn_web).setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java).apply { putExtra("svc_id", svc.id) })
        }

        btnToggle.setOnClickListener { togglePausePlay() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val user = prefs.getString("ag_user_${svc.id}", null)
                val pwd = prefs.getString("ag_pass_${svc.id}", null)
                if (user.isNullOrBlank() || pwd.isNullOrBlank()) askCredentials() else {
                    authHeader = basicAuth(user, pwd)
                    startPolling()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun askCredentials() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etUser = EditText(this).apply { hint = "用户名" }
        val etPwd = EditText(this).apply {
            hint = "密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AuthStore.loadLastCredentials(this)?.let { (u, p) -> etUser.setText(u); etPwd.setText(p) }
        layout.addView(etUser); layout.addView(etPwd)
        AlertDialog.Builder(this)
            .setTitle("AdGuard Home 登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val u = etUser.text.toString().trim()
                val p = etPwd.text.toString()
                if (u.isEmpty()) { finish(); return@setPositiveButton }
                prefs.edit().putString("ag_user_${svc.id}", u).putString("ag_pass_${svc.id}", p).apply()
                AuthStore.saveLastCredentials(this, u, p)
                authHeader = basicAuth(u, p)
                startPolling()
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun basicAuth(u: String, p: String): String {
        val raw = "$u:$p".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val status = fetch("/control/status")
                val stats = fetch("/control/stats")
                withContext(Dispatchers.Main) { render(status, stats) }
                delay(5000)
            }
        }
    }

    private fun fetch(path: String): String? = try {
        val conn = URL("${svc.url}$path").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", authHeader)
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    private fun postJson(path: String, body: String): Boolean = try {
        val conn = URL("${svc.url}$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", authHeader)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        conn.responseCode in 200..299
    } catch (e: Exception) { false }

    private fun render(statusJson: String?, statsJson: String?) {
        if (statusJson == null) {
            tvStatus.text = "连接失败 检查密码或 URL"
            return
        }
        val s = try { JSONObject(statusJson) } catch (_: Exception) { null } ?: return
        protectionEnabled = s.optBoolean("protection_enabled", true)
        val version = s.optString("version")
        val running = s.optBoolean("running", true)
        tvStatus.text = if (running) {
            "v$version · 防护${if (protectionEnabled) " 已启用 ✅" else " 已暂停 ⏸"}"
        } else "未运行"
        btnToggle.text = if (protectionEnabled) "暂停" else "恢复"

        // stats
        val st = try { JSONObject(statsJson ?: "") } catch (_: Exception) { null } ?: return
        val total = st.optLong("num_dns_queries", 0)
        val blocked = st.optLong("num_blocked_filtering", 0) +
                      st.optLong("num_replaced_safebrowsing", 0) +
                      st.optLong("num_replaced_parental", 0)
        tvTotal.text = formatNum(total)
        tvBlocked.text = formatNum(blocked)
        val pct = if (total > 0) (blocked * 100.0 / total) else 0.0
        tvBlockedPct.text = "已拦截 %.1f%%".format(pct)

        // top blocked domains
        llTopBlocked.removeAllViews()
        val topArr = st.optJSONArray("top_blocked_domains")
        if (topArr != null) {
            for (i in 0 until minOf(topArr.length(), 10)) {
                val row = topArr.optJSONObject(i) ?: continue
                // 有些版本返回 {domain: count} 有些 [[domain, count]]
                val iter = row.keys()
                if (iter.hasNext()) {
                    val domain = iter.next()
                    val count = row.optInt(domain)
                    llTopBlocked.addView(domainRow(domain, count))
                }
            }
        }
    }

    private fun togglePausePlay() {
        val newState = !protectionEnabled
        lifecycleScope.launch(Dispatchers.IO) {
            // 新版：POST /control/protection body = {enabled, duration: 0}
            val body = """{"enabled":$newState,"duration":0}"""
            val ok = postJson("/control/protection", body)
            withContext(Dispatchers.Main) {
                if (ok) {
                    protectionEnabled = newState
                    btnToggle.text = if (newState) "暂停" else "恢复"
                } else {
                    Toast.makeText(this@AdGuardActivity, "切换失败（可能 API 版本不匹配）", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun domainRow(domain: String, count: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(getColor(R.color.bg_card))
            setPadding(24, 18, 24, 18)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 4
            layoutParams = lp
        }
        row.addView(TextView(this).apply {
            text = domain
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        row.addView(TextView(this).apply {
            text = formatNum(count.toLong())
            setTextColor(getColor(R.color.red))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        return row
    }

    private fun formatNum(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1000 -> "%.1fK".format(n / 1000.0)
        else -> n.toString()
    }
}
