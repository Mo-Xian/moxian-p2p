package com.cp12064.moxianp2p

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * v2 启动页：登录 / 注册
 *
 * 核心流程：
 *   1. 用户输入 server URL + email + password
 *   2. POST /api/auth/prelogin 拿 kdf_iterations
 *   3. 本地派生 masterKey = PBKDF2(password, email, iterations)
 *   4. POST /api/auth/login 传 email + PBKDF2(masterKey, password, 1) 作为 pwdHash
 *   5. 服务器返回 jwt + encrypted_vault + vault_version
 *   6. 客户端用 masterKey 解 encrypted_vault → 进 MainActivity
 *
 * 注册类似：先 prelogin 拿 iterations（如果是新邮箱会返回默认 600k）
 *   派生 masterKey 和 pwdHash
 *   POST /api/auth/register（带 invite_code 首位管理员免填）
 *   成功后自动跑登录流程
 */
class LoginActivity : AppCompatActivity() {

    private enum class Mode { LOGIN, REGISTER }
    private var mode = Mode.LOGIN

    private lateinit var etServer: EditText
    private lateinit var etEmail: EditText
    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etInvite: EditText
    private lateinit var btnSubmit: Button
    private lateinit var btnTabLogin: Button
    private lateinit var btnTabRegister: Button
    private lateinit var lblUsername: TextView
    private lateinit var lblInvite: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已登录 → 直接跳主界面
        if (AuthSession.isLoggedIn()) {
            goMain()
            return
        }

        // 尝试从 disk 恢复会话（JWT 持久保存 主密码需要再输）
        if (AuthSession.restoreFromDisk(this)) {
            // 有 JWT 但 masterKey 没了 需要解锁
            startActivity(Intent(this, UnlockActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)

        etServer = findViewById(R.id.et_server)
        etEmail = findViewById(R.id.et_email)
        etUsername = findViewById(R.id.et_username)
        etPassword = findViewById(R.id.et_password)
        etInvite = findViewById(R.id.et_invite)
        btnSubmit = findViewById(R.id.btn_submit)
        btnTabLogin = findViewById(R.id.btn_tab_login)
        btnTabRegister = findViewById(R.id.btn_tab_register)
        lblUsername = findViewById(R.id.lbl_username)
        lblInvite = findViewById(R.id.lbl_invite)
        pbLoading = findViewById(R.id.pb_loading)
        tvError = findViewById(R.id.tv_error)

        // 预填服务器（上次登录用过的）
        val prefs = AuthStore.prefs(this)
        val lastServer = prefs.getString("last_server", "") ?: ""
        if (lastServer.isNotEmpty()) etServer.setText(lastServer)
        else etServer.setText("https://")

        btnTabLogin.setOnClickListener { switchMode(Mode.LOGIN) }
        btnTabRegister.setOnClickListener { switchMode(Mode.REGISTER) }
        btnSubmit.setOnClickListener { onSubmit() }
    }

    private fun switchMode(m: Mode) {
        mode = m
        val login = m == Mode.LOGIN
        btnTabLogin.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            getColor(if (login) R.color.accent else R.color.bg_card)))
        btnTabLogin.setTextColor(getColor(if (login) R.color.bg else R.color.accent))
        btnTabRegister.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            getColor(if (!login) R.color.accent else R.color.bg_card)))
        btnTabRegister.setTextColor(getColor(if (!login) R.color.bg else R.color.accent))
        lblUsername.visibility = if (!login) View.VISIBLE else View.GONE
        etUsername.visibility = if (!login) View.VISIBLE else View.GONE
        lblInvite.visibility = if (!login) View.VISIBLE else View.GONE
        etInvite.visibility = if (!login) View.VISIBLE else View.GONE
        btnSubmit.text = if (login) "登录" else "注册"
        tvError.text = ""
    }

    private fun onSubmit() {
        val server = etServer.text.toString().trim().trimEnd('/')
        val email = etEmail.text.toString().trim().lowercase()
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        val invite = etInvite.text.toString().trim().uppercase()

        if (server.isEmpty() || !(server.startsWith("http://") || server.startsWith("https://"))) {
            err("服务器 URL 必须以 http:// 或 https:// 开头"); return
        }
        if (email.isEmpty() || !email.contains("@")) { err("邮箱格式不对"); return }
        if (password.length < 6) { err("密码最少 6 位"); return }
        if (mode == Mode.REGISTER && username.isEmpty()) { err("用户名必填"); return }

        setLoading(true)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (mode == Mode.REGISTER) doRegister(server, email, username, password, invite)
                    doLogin(server, email, password)
                } catch (e: Exception) {
                    errOnMain("网络错误: ${e.message}")
                    false
                }
            }
            setLoading(false)
            if (ok) {
                AuthStore.prefs(this@LoginActivity).edit()
                    .putString("last_server", server).apply()
                goMain()
            }
        }
    }

    /** 注册流程 */
    private suspend fun doRegister(server: String, email: String, username: String, password: String, invite: String): Boolean {
        // 先 prelogin 取 iterations（新邮箱返回默认 600k）
        val pre = AuthSession.httpPostJsonNoAuth(server, "/api/auth/prelogin",
            JSONObject().put("email", email).toString()) ?: run {
            errOnMain("连接服务器失败"); return false
        }
        if (pre.contains("_error")) {
            errOnMain("prelogin 失败: ${JSONObject(pre).optString("_error")}"); return false
        }
        val iterations = JSONObject(pre).optInt("kdf_iterations", 600_000)

        // 派生 pwdHash
        val masterKey = BwCrypto.deriveMasterKey(password, email, iterations)
        val pwdHash = BwCrypto.deriveMasterPasswordHash(masterKey, password)

        val regBody = JSONObject()
            .put("email", email)
            .put("username", username)
            .put("password_hash", pwdHash)
            .put("kdf_iterations", iterations)
            .put("invite_code", invite)
            .toString()

        val resp = AuthSession.httpPostJsonNoAuth(server, "/api/auth/register", regBody)
        if (resp == null) { errOnMain("注册请求失败"); return false }
        val obj = JSONObject(resp)
        if (obj.has("_error") || obj.has("error")) {
            val e = obj.optString("_error").ifEmpty { obj.optString("error") }
            errOnMain("注册失败: $e"); return false
        }
        // 注册成功 继续登录
        return true
    }

    /** 登录流程 */
    private suspend fun doLogin(server: String, email: String, password: String): Boolean {
        val pre = AuthSession.httpPostJsonNoAuth(server, "/api/auth/prelogin",
            JSONObject().put("email", email).toString()) ?: run {
            errOnMain("连接服务器失败"); return false
        }
        if (pre.contains("_error")) {
            errOnMain("prelogin 失败: ${JSONObject(pre).optString("_error")}"); return false
        }
        val iterations = JSONObject(pre).optInt("kdf_iterations", 600_000)

        val masterKey = BwCrypto.deriveMasterKey(password, email, iterations)
        val pwdHash = BwCrypto.deriveMasterPasswordHash(masterKey, password)

        val loginBody = JSONObject()
            .put("email", email)
            .put("password_hash", pwdHash)
            .toString()
        val resp = AuthSession.httpPostJsonNoAuth(server, "/api/auth/login", loginBody)
        if (resp == null) { errOnMain("登录请求失败"); return false }
        val obj = JSONObject(resp)
        if (obj.has("_error") || obj.has("error")) {
            val e = obj.optString("_error").ifEmpty { obj.optString("error") }
            errOnMain("登录失败: $e"); return false
        }

        val jwt = obj.optString("jwt")
        val userId = obj.optLong("user_id")
        val username = obj.optString("username")
        val isAdmin = obj.optBoolean("is_admin")
        val kdfIter = obj.optInt("kdf_iterations", iterations)
        val encVault = obj.optString("encrypted_vault")
        val vaultVer = obj.optLong("vault_version")

        if (jwt.isEmpty()) { errOnMain("无 jwt"); return false }

        // 存会话
        withContext(Dispatchers.Main) {
            AuthSession.onLoginSuccess(
                this@LoginActivity, server, email, jwt, userId,
                username, isAdmin, kdfIter, masterKey, encVault, vaultVer
            )
        }
        return true
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        pbLoading.visibility = if (loading) View.VISIBLE else View.GONE
        btnSubmit.isEnabled = !loading
    }

    private fun err(msg: String) { tvError.text = msg }
    private suspend fun errOnMain(msg: String) {
        withContext(Dispatchers.Main) { tvError.text = msg }
    }
}
