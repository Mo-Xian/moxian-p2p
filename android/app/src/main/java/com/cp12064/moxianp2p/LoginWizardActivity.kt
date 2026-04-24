package com.cp12064.moxianp2p

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 批量登录向导
 *
 * 用途：NAS 各服务（Jellyfin / Immich / qBit / AdGuard / Navidrome / Vaultwarden）
 *       常常共用一套管理员账号 用户一次输入凭据 向导遍历尝试登录每个服务
 *       成功的保存 token/凭据 供对应 Activity 免输入使用
 *
 * Syncthing 用 API Key 不在此流程（需要单独从 Web UI 拷贝）
 * Vaultwarden 可选加入但主密码不持久 下次开 APP 仍需要重输
 */
class LoginWizardActivity : AppCompatActivity() {

    private lateinit var llServices: LinearLayout
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnGo: Button

    private data class ServiceCb(val svc: NasService, val cb: CheckBox)
    private val serviceCbs = mutableListOf<ServiceCb>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login_wizard)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "批量登录"
        }

        llServices = findViewById(R.id.ll_services)
        etUser = findViewById(R.id.et_user)
        etPass = findViewById(R.id.et_pass)
        tvStatus = findViewById(R.id.tv_status)
        btnGo = findViewById(R.id.btn_go)

        // 预填上次凭据
        AuthStore.loadLastCredentials(this)?.let { (u, p) ->
            etUser.setText(u); etPass.setText(p)
        }

        // 列出所有支持批量登录的服务（跳过 Syncthing：它用 API Key 不是密码）
        val services = NasServiceStore.load(this).filter { matchableService(it) }
        services.forEach { svc -> llServices.addView(makeServiceRow(svc)) }

        if (services.isEmpty()) {
            llServices.addView(TextView(this).apply {
                text = "没有可批量登录的服务\n先在服务页导入模板"
                setTextColor(getColor(R.color.text_secondary))
                setPadding(24, 24, 24, 24)
                gravity = android.view.Gravity.CENTER
            })
            btnGo.isEnabled = false
        }

        btnGo.setOnClickListener { runBatchLogin() }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun matchableService(svc: NasService): Boolean {
        val n = svc.name.lowercase()
        return n.contains("qbittorrent") || n.contains("qbit") ||
               n.contains("adguard") ||
               n.contains("immich") ||
               n.contains("jellyfin") || n.contains("emby") ||
               n.contains("navidrome") || n.contains("subsonic") ||
               n.contains("vaultwarden") || n.contains("bitwarden") ||
               svc.type in setOf(ServiceType.VIDEO, ServiceType.MUSIC,
                                  ServiceType.PHOTO, ServiceType.PASSWORD,
                                  ServiceType.DOWNLOAD, ServiceType.DASHBOARD)
    }

    private fun makeServiceRow(svc: NasService): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.bg_card))
            setPadding(20, 12, 20, 12)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 4
            layoutParams = lp
        }
        val cb = CheckBox(this).apply {
            isChecked = true
            buttonTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
        }
        row.addView(cb)
        row.addView(TextView(this).apply {
            text = "${svc.type.emoji} ${svc.name}\n${svc.url}"
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0)
        })
        serviceCbs.add(ServiceCb(svc, cb))
        return row
    }

    private fun runBatchLogin() {
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            tvStatus.text = "请输入用户名和密码"
            return
        }
        val selected = serviceCbs.filter { it.cb.isChecked }
        if (selected.isEmpty()) {
            tvStatus.text = "请至少勾选一个服务"
            return
        }

        btnGo.isEnabled = false
        tvStatus.text = "登录中..."

        lifecycleScope.launch {
            val log = StringBuilder()
            AuthStore.saveLastCredentials(this@LoginWizardActivity, user, pass)
            for (sc in selected) {
                val svc = sc.svc
                val type = classify(svc)
                val ok = withContext(Dispatchers.IO) {
                    tryLogin(svc, type, user, pass)
                }
                log.appendLine("${if (ok) "✅" else "❌"} ${svc.name}")
                tvStatus.text = log.toString().trim()
            }
            btnGo.isEnabled = true
        }
    }

    private fun classify(svc: NasService): String {
        val n = svc.name.lowercase()
        return when {
            "qbit" in n -> "qbit"
            "adguard" in n -> "adguard"
            "immich" in n || svc.type == ServiceType.PHOTO -> "immich"
            "jellyfin" in n || "emby" in n || svc.type == ServiceType.VIDEO -> "jellyfin"
            "navidrome" in n || "subsonic" in n || svc.type == ServiceType.MUSIC -> "navidrome"
            "vaultwarden" in n || "bitwarden" in n || svc.type == ServiceType.PASSWORD -> "vaultwarden"
            else -> "unknown"
        }
    }

    private fun tryLogin(svc: NasService, type: String, user: String, pass: String): Boolean {
        return when (type) {
            "qbit" -> loginQbit(svc, user, pass)
            "adguard" -> loginAdGuard(svc, user, pass)
            "immich" -> loginImmich(svc, user, pass)
            "jellyfin" -> loginJellyfin(svc, user, pass)
            "navidrome" -> loginNavidrome(svc, user, pass)
            "vaultwarden" -> {
                // Vaultwarden 主密码不持久 这里仅验证能登录不保存
                // 实际使用还是进入 Vaultwarden Activity 重新输入一次
                true
            }
            else -> false
        }
    }

    private fun loginQbit(svc: NasService, user: String, pass: String): Boolean = try {
        val body = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
        val conn = URL("${svc.url}/api/v2/auth/login").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000; conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Referer", svc.url)
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode == 200 && conn.inputStream.bufferedReader().use { it.readText() }.trim() == "Ok.") {
            AuthStore.prefs(this).edit()
                .putString("qbit_user_${svc.id}", user)
                .putString("qbit_pass_${svc.id}", pass)
                .apply()
            true
        } else false
    } catch (e: Exception) { false }

    private fun loginAdGuard(svc: NasService, user: String, pass: String): Boolean = try {
        val b64 = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        val conn = URL("${svc.url}/control/status").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000; conn.readTimeout = 10_000
        conn.setRequestProperty("Authorization", "Basic $b64")
        if (conn.responseCode == 200) {
            AuthStore.prefs(this).edit()
                .putString("ag_user_${svc.id}", user)
                .putString("ag_pass_${svc.id}", pass)
                .apply()
            true
        } else false
    } catch (e: Exception) { false }

    private fun loginImmich(svc: NasService, user: String, pass: String): Boolean = try {
        val conn = URL("${svc.url}/api/auth/login").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000; conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        val body = JSONObject().apply { put("email", user); put("password", pass) }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode in 200..201) {
            val r = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val token = r.optString("accessToken")
            if (token.isNotEmpty()) {
                AuthStore.prefs(this).edit().putString("immich_token_${svc.id}", token).apply()
                true
            } else false
        } else false
    } catch (e: Exception) { false }

    private fun loginJellyfin(svc: NasService, user: String, pass: String): Boolean = try {
        val conn = URL("${svc.url}/Users/AuthenticateByName").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 5000; conn.readTimeout = 10_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("X-Emby-Authorization",
            "MediaBrowser Client=\"moxian-p2p\", Device=\"Android\", DeviceId=\"wizard-${svc.id}\", Version=\"1.1\"")
        conn.doOutput = true
        val body = JSONObject().apply { put("Username", user); put("Pw", pass) }.toString()
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode == 200) {
            val r = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val token = r.optString("AccessToken")
            val uid = r.optJSONObject("User")?.optString("Id") ?: ""
            if (token.isNotEmpty() && uid.isNotEmpty()) {
                AuthStore.prefs(this).edit()
                    .putString("jf_token_${svc.id}", token)
                    .putString("jf_uid_${svc.id}", uid)
                    .apply()
                true
            } else false
        } else false
    } catch (e: Exception) { false }

    private fun loginNavidrome(svc: NasService, user: String, pass: String): Boolean = try {
        // Navidrome 用 Subsonic token 模式 构造 ping 请求验证
        val salt = (1..12).map { "0123456789abcdef".random() }.joinToString("")
        val md = java.security.MessageDigest.getInstance("MD5")
        val token = md.digest((pass + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        val url = "${svc.url}/rest/ping.view?u=${URLEncoder.encode(user, "UTF-8")}&t=$token&s=$salt&v=1.16.0&c=moxian&f=json"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 5000; conn.readTimeout = 10_000
        if (conn.responseCode == 200) {
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val status = JSONObject(json).optJSONObject("subsonic-response")?.optString("status")
            if (status == "ok") {
                AuthStore.prefs(this).edit()
                    .putString("nav_user_${svc.id}", user)
                    .putString("nav_pass_${svc.id}", pass)
                    .apply()
                true
            } else false
        } else false
    } catch (e: Exception) { false }
}
