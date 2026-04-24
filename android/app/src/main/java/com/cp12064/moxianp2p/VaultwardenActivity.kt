package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * Vaultwarden / Bitwarden 简单客户端（只读）
 *
 * 流程：
 *   1. email + 主密码 → prelogin 取 iterations
 *   2. PBKDF2 派生 masterKey + masterPasswordHash
 *   3. /identity/connect/token 获取 accessToken + 加密的 symKey
 *   4. HKDF 展开 masterKey 为 stretchedEnc + stretchedMac
 *   5. AES-CBC 解密 symKey 得到 userKey（64 字节）拆成 encKey/macKey
 *   6. /api/sync 拿全部 ciphers
 *   7. 解密每条 cipher 的 name / username / password / uris
 *
 * 支持的类型仅 Login（最常见）
 * 会话只在内存 退出 Activity 即清空密钥 下次重新输入主密码解锁
 */
class VaultwardenActivity : AppCompatActivity() {

    private lateinit var svc: NasService
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var rv: RecyclerView
    private lateinit var deviceId: String

    private var encKey: ByteArray? = null  // 用户 key 的 AES 部分
    private var macKey: ByteArray? = null  // HMAC 部分
    private var allItems: List<VwItem> = emptyList()
    private val adapter = CipherAdapter { onItemClick(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vaultwarden)

        val svcId = intent.getStringExtra("svc_id") ?: run { finish(); return }
        svc = NasService.findById(this, svcId) ?: run { finish(); return }
        prefs = getSharedPreferences("moxian", Context.MODE_PRIVATE)

        supportActionBar?.apply { setDisplayHomeAsUpEnabled(true); title = svc.name }

        tvStatus = findViewById(R.id.tv_status)
        etSearch = findViewById(R.id.et_search)
        rv = findViewById(R.id.rv_items)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<Button>(R.id.btn_logout).setOnClickListener { lockAndAskPassword() }
        findViewById<Button>(R.id.btn_web).setOnClickListener {
            startActivity(Intent(this, WebViewActivity::class.java).apply { putExtra("svc_id", svc.id) })
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { applyFilter() }
            override fun afterTextChanged(s: Editable?) {}
        })

        deviceId = prefs.getString("vw_device_${svc.id}", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("vw_device_${svc.id}", it).apply()
        }

        askPassword()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun askPassword() {
        val savedEmail = prefs.getString("vw_email_${svc.id}", "")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etEmail = EditText(this).apply {
            setText(savedEmail)
            hint = "邮箱"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val etPwd = EditText(this).apply {
            hint = "主密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etEmail); layout.addView(etPwd)

        AlertDialog.Builder(this)
            .setTitle("Vaultwarden 解锁")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                val email = etEmail.text.toString().trim().lowercase()
                val pwd = etPwd.text.toString()
                if (email.isEmpty() || pwd.isEmpty()) { finish(); return@setPositiveButton }
                prefs.edit().putString("vw_email_${svc.id}", email).apply()
                startLogin(email, pwd)
            }
            .setNegativeButton("取消") { _, _ -> finish() }
            .setCancelable(false).show()
    }

    private fun lockAndAskPassword() {
        encKey = null; macKey = null
        allItems = emptyList()
        adapter.submit(emptyList())
        tvStatus.text = "已锁定"
        askPassword()
    }

    // ---- 核心登录 + 解密流程 ----
    private fun startLogin(email: String, password: String) {
        tvStatus.text = "登录中..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { loginAndSync(email, password) }
            if (result == null) {
                tvStatus.text = "登录失败 或 Argon2id 不支持"
                askPassword()
            } else {
                encKey = result.encKey; macKey = result.macKey
                allItems = result.items
                applyFilter()
                tvStatus.text = "共 ${allItems.size} 条 搜索过滤"
            }
        }
    }

    private data class AuthResult(val encKey: ByteArray, val macKey: ByteArray, val items: List<VwItem>)

    private fun loginAndSync(email: String, password: String): AuthResult? {
        try {
            // 1. Prelogin 拿 KDF 参数
            val preUrl = "${svc.url}/identity/accounts/prelogin"
            val preBody = JSONObject().put("email", email).toString()
            val preResp = httpPost(preUrl, preBody, "application/json", null) ?: return null
            val preJson = JSONObject(preResp)
            val kdfType = preJson.optInt("Kdf", preJson.optInt("kdf", 0))
            val iters = preJson.optInt("KdfIterations", preJson.optInt("kdfIterations", 600_000))
            if (kdfType != 0) {
                // Argon2id 暂不支持
                return null
            }

            // 2. 派生 masterKey + hash
            val masterKey = BwCrypto.deriveMasterKey(password, email, iters)
            val masterHash = BwCrypto.deriveMasterPasswordHash(masterKey, password)

            // 3. 请求 token
            val tokenUrl = "${svc.url}/identity/connect/token"
            val tokenBody = mapOf(
                "grant_type" to "password",
                "username" to email,
                "password" to masterHash,
                "scope" to "api offline_access",
                "client_id" to "mobile",
                "deviceType" to "0",
                "deviceIdentifier" to deviceId,
                "deviceName" to "moxian-p2p",
                "deviceName" to "moxian-p2p"
            ).entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

            val tokenResp = httpPost(tokenUrl, tokenBody, "application/x-www-form-urlencoded", null) ?: return null
            val tokenJson = JSONObject(tokenResp)
            val accessToken = tokenJson.optString("access_token")
            val protectedKey = tokenJson.optString("Key", tokenJson.optString("key"))
            if (accessToken.isEmpty() || protectedKey.isEmpty()) return null

            // 4. 派生 stretched key
            val (stretchedEnc, stretchedMac) = BwCrypto.stretchMasterKey(masterKey)

            // 5. 解密 protected symmetric key
            val decryptedKey = BwCrypto.decryptEncString(protectedKey, stretchedEnc, stretchedMac) ?: return null
            if (decryptedKey.size < 64) return null
            val userEncKey = decryptedKey.copyOfRange(0, 32)
            val userMacKey = decryptedKey.copyOfRange(32, 64)

            // 6. Sync
            val syncResp = httpGet("${svc.url}/api/sync", accessToken) ?: return null
            val sync = JSONObject(syncResp)
            val ciphers = sync.optJSONArray("Ciphers") ?: sync.optJSONArray("ciphers") ?: JSONArray()

            val items = mutableListOf<VwItem>()
            for (i in 0 until ciphers.length()) {
                val c = ciphers.getJSONObject(i)
                val type = c.optInt("Type", c.optInt("type", 0))
                if (type != 1) continue  // 只处理 Login 类型

                val nameEnc = c.optString("Name", c.optString("name"))
                val name = BwCrypto.decryptEncStringToStr(nameEnc, userEncKey, userMacKey) ?: continue

                val login = c.optJSONObject("Login") ?: c.optJSONObject("login")
                val userEnc = login?.optString("Username", login.optString("username")) ?: ""
                val pwdEnc = login?.optString("Password", login.optString("password")) ?: ""
                val username = if (userEnc.isNotEmpty()) BwCrypto.decryptEncStringToStr(userEnc, userEncKey, userMacKey) ?: "" else ""
                val cipherPwd = if (pwdEnc.isNotEmpty()) BwCrypto.decryptEncStringToStr(pwdEnc, userEncKey, userMacKey) ?: "" else ""

                val urisArr = login?.optJSONArray("Uris") ?: login?.optJSONArray("uris")
                var firstUri = ""
                if (urisArr != null && urisArr.length() > 0) {
                    val u0 = urisArr.getJSONObject(0).optString("Uri", urisArr.getJSONObject(0).optString("uri"))
                    if (u0.isNotEmpty()) firstUri = BwCrypto.decryptEncStringToStr(u0, userEncKey, userMacKey) ?: ""
                }
                items.add(VwItem(name, username, cipherPwd, firstUri))
            }

            return AuthResult(userEncKey, userMacKey, items.sortedBy { it.name.lowercase() })
        } catch (e: Exception) {
            return null
        }
    }

    private fun httpPost(url: String, body: String, contentType: String, bearer: String?): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Content-Type", contentType)
        conn.setRequestProperty("Accept", "application/json")
        if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() }
        else {
            val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            runOnUiThread { tvStatus.text = "HTTP ${conn.responseCode}: ${err?.take(80)}" }
            null
        }
    } catch (e: Exception) { null }

    private fun httpGet(url: String, bearer: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $bearer")
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    // ---- 搜索过滤 ----
    private fun applyFilter() {
        val q = etSearch.text.toString().trim().lowercase()
        val list = if (q.isEmpty()) allItems
                   else allItems.filter {
                       it.name.lowercase().contains(q) ||
                       it.username.lowercase().contains(q) ||
                       it.uri.lowercase().contains(q)
                   }
        adapter.submit(list)
    }

    // ---- 点击展示详情 ----
    private fun onItemClick(it: VwItem) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 24)
        }
        fun addRow(label: String, value: String, copyable: Boolean) {
            layout.addView(TextView(this@VaultwardenActivity).apply {
                text = label; setTextColor(getColor(R.color.text_secondary)); textSize = 11f
            })
            val tv = TextView(this@VaultwardenActivity).apply {
                text = value
                setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                setPadding(0, 4, 0, 20)
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            }
            layout.addView(tv)
            if (copyable) {
                layout.addView(Button(this@VaultwardenActivity).apply {
                    text = "复制"
                    textSize = 11f
                    setOnClickListener {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText(label, value))
                        Toast.makeText(this@VaultwardenActivity, "已复制 $label", Toast.LENGTH_SHORT).show()
                    }
                    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    lp.bottomMargin = 16
                    layoutParams = lp
                })
            }
        }
        addRow("名称", it.name, false)
        if (it.uri.isNotEmpty()) addRow("URL", it.uri, true)
        if (it.username.isNotEmpty()) addRow("用户名", it.username, true)
        if (it.password.isNotEmpty()) addRow("密码", it.password, true)

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("关闭", null)
            .show()
    }
}

data class VwItem(val name: String, val username: String, val password: String, val uri: String)

private class CipherAdapter(val onClick: (VwItem) -> Unit) : RecyclerView.Adapter<CipherAdapter.VH>() {
    private var items: List<VwItem> = emptyList()
    fun submit(list: List<VwItem>) { items = list; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_cipher, parent, false)
    )
    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]
        h.tvName.text = it.name.ifEmpty { "(无名)" }
        h.tvUser.text = if (it.username.isNotEmpty()) it.username else it.uri
        h.itemView.setOnClickListener { onClick(it) }
    }
    override fun getItemCount() = items.size
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tv_name)
        val tvUser: TextView = v.findViewById(R.id.tv_user)
    }
}
