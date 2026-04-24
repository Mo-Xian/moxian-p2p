package com.cp12064.moxianp2p

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * v2 认证会话 单例
 *
 * 持有：
 *   - masterKey（派生出来的 ByteArray，只在进程内存）
 *   - encKey + macKey（用于加解密 vault）
 *   - JWT（服务器签发 会话 token）
 *   - serverUrl（moxian-server 的 base URL，如 https://vps.example.com:7788）
 *   - 当前用户信息（userId, username, isAdmin）
 *   - currentVault（已解密的凭据 Map）
 *
 * 特性：
 *   - APP 启动时尝试从 AuthStore 恢复 JWT（免每次输密码）
 *   - 但 masterKey 不持久化 → 恢复 JWT 后无法解 vault 需再输主密码解锁
 *   - 或者：本地存加密的 masterKey（用 Android Keystore 保护）TODO 后续版本
 *
 * 锁定：
 *   - Activity onPause（超过 N 分钟）自动清 masterKey
 *   - 用户手动点"锁定"
 */
object AuthSession {

    // ---- 持久字段（跨 Activity / 进程）----
    @Volatile private var serverUrl: String = ""
    @Volatile private var jwt: String = ""
    @Volatile private var userId: Long = 0
    @Volatile private var username: String = ""
    @Volatile private var email: String = ""
    @Volatile private var isAdmin: Boolean = false
    @Volatile private var kdfIterations: Int = 600_000
    @Volatile private var vaultVersion: Long = 0

    // ---- 仅内存字段 ----
    @Volatile private var encKey: ByteArray? = null
    @Volatile private var macKey: ByteArray? = null

    // ---- 已解密 vault 数据 ----
    @Volatile private var decryptedVault: VaultData? = null

    // 是否已登录（有 JWT）
    fun isLoggedIn(): Boolean = jwt.isNotEmpty()

    // 是否已解锁（有 masterKey 派生的 key 可解 vault）
    fun isUnlocked(): Boolean = encKey != null && macKey != null

    fun getServerUrl(): String = serverUrl
    fun getJwt(): String = jwt
    fun getUserId(): Long = userId
    fun getUsername(): String = username
    fun getEmail(): String = email
    fun isAdmin(): Boolean = isAdmin
    fun getKdfIterations(): Int = kdfIterations
    fun getVaultVersion(): Long = vaultVersion
    fun getVault(): VaultData? = decryptedVault

    fun getEncKey(): ByteArray? = encKey
    fun getMacKey(): ByteArray? = macKey

    /** 登录成功后 保存会话信息到内存 + 持久化部分到 AuthStore */
    fun onLoginSuccess(
        ctx: Context,
        serverUrl: String,
        email: String,
        jwt: String,
        userId: Long,
        username: String,
        isAdmin: Boolean,
        kdfIter: Int,
        masterKey: ByteArray,
        encryptedVault: String,
        vaultVersion: Long,
    ) {
        this.serverUrl = serverUrl.trimEnd('/')
        this.jwt = jwt
        this.userId = userId
        this.username = username
        this.email = email
        this.isAdmin = isAdmin
        this.kdfIterations = kdfIter
        this.vaultVersion = vaultVersion

        // 派生 enc/mac key
        val (ek, mk) = BwCrypto.stretchMasterKey(masterKey)
        this.encKey = ek
        this.macKey = mk

        // 解密 vault
        this.decryptedVault = if (encryptedVault.isNotEmpty()) {
            val plain = BwCrypto.decryptEncStringToStr(encryptedVault, ek, mk)
            if (plain != null) VaultData.fromJson(plain) else VaultData()
        } else VaultData()

        // 持久化 JWT / server / username 到加密 prefs（但不存 masterKey）
        AuthStore.prefs(ctx).edit()
            .putString("session_server", serverUrl)
            .putString("session_jwt", jwt)
            .putString("session_email", email)
            .putString("session_username", username)
            .putLong("session_uid", userId)
            .putBoolean("session_admin", isAdmin)
            .putInt("session_kdf", kdfIter)
            .putLong("session_vault_ver", vaultVersion)
            .apply()
    }

    /** 进程启动时 尝试恢复上次的 JWT 会话（但 masterKey 没了 需再输主密码解锁）*/
    fun restoreFromDisk(ctx: Context): Boolean {
        val p = AuthStore.prefs(ctx)
        val j = p.getString("session_jwt", null) ?: return false
        if (j.isEmpty()) return false
        serverUrl = p.getString("session_server", "") ?: ""
        jwt = j
        email = p.getString("session_email", "") ?: ""
        username = p.getString("session_username", "") ?: ""
        userId = p.getLong("session_uid", 0)
        isAdmin = p.getBoolean("session_admin", false)
        kdfIterations = p.getInt("session_kdf", 600_000)
        vaultVersion = p.getLong("session_vault_ver", 0)
        return true
    }

    /** 用主密码重新解锁（JWT 仍有效时用 其他服务菜单等用到 vault 时先解锁）*/
    fun unlockWithPassword(ctx: Context, password: String): Boolean {
        if (email.isEmpty() || kdfIterations == 0) return false
        val master = BwCrypto.deriveMasterKey(password, email, kdfIterations)
        val (ek, mk) = BwCrypto.stretchMasterKey(master)

        // 需要拉最新 vault 解密验证
        return try {
            val resp = httpGet(ctx, "/api/vault") ?: return false
            val vaultJson = JSONObject(resp)
            val encryptedVault = vaultJson.optString("encrypted_vault")
            val ver = vaultJson.optLong("version")

            decryptedVault = if (encryptedVault.isNotEmpty()) {
                val plain = BwCrypto.decryptEncStringToStr(encryptedVault, ek, mk)
                if (plain == null) return false   // 密码错
                VaultData.fromJson(plain)
            } else VaultData()

            vaultVersion = ver
            encKey = ek; macKey = mk
            true
        } catch (e: Exception) { false }
    }

    /** 仅清 masterKey（保持 JWT 可继续做 API 请求 但 vault 访问被锁）*/
    fun lock() {
        encKey?.fill(0); macKey?.fill(0)
        encKey = null; macKey = null
        decryptedVault = null
    }

    /** 完全登出 清所有 JWT / 持久化 */
    fun logout(ctx: Context) {
        lock()
        jwt = ""; email = ""; username = ""; userId = 0; isAdmin = false
        vaultVersion = 0
        AuthStore.prefs(ctx).edit()
            .remove("session_jwt")
            .remove("session_server")
            .remove("session_email")
            .remove("session_username")
            .remove("session_uid")
            .remove("session_admin")
            .remove("session_kdf")
            .remove("session_vault_ver")
            .apply()
    }

    /** Vault 变更时 重新加密并上传 */
    fun saveVault(ctx: Context): Boolean {
        val v = decryptedVault ?: return false
        val ek = encKey ?: return false
        val mk = macKey ?: return false
        val plain = v.toJson()
        val encrypted = BwCrypto.encryptToEncString(plain, ek, mk)

        val body = JSONObject()
            .put("encrypted_vault", encrypted)
            .put("expected_version", vaultVersion)
            .toString()
        val resp = httpPostJson(ctx, "/api/vault", body) ?: return false
        val newVer = JSONObject(resp).optLong("version", -1)
        if (newVer > 0) {
            vaultVersion = newVer
            AuthStore.prefs(ctx).edit().putLong("session_vault_ver", newVer).apply()
            return true
        }
        return false
    }

    // ---- HTTP helpers ----
    fun httpGet(ctx: Context, path: String): String? = try {
        val conn = URL(serverUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 8000; conn.readTimeout = 15_000
        if (jwt.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $jwt")
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    fun httpPostJson(ctx: Context, path: String, body: String): String? = try {
        val conn = URL(serverUrl + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000; conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        if (jwt.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $jwt")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
    } catch (e: Exception) { null }

    // 不走 JWT 的 POST（注册 / prelogin）
    // 无论 2xx / 4xx 都返回响应体字符串 调用方用 JSON 的 error 字段判断
    // 网络异常（连接不上 / 超时）返回 null
    fun httpPostJsonNoAuth(serverBase: String, path: String, body: String): String? = try {
        val conn = URL(serverBase.trimEnd('/') + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 8000; conn.readTimeout = 15_000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.use { it.readText() } ?: "{\"error\":\"HTTP ${conn.responseCode} empty body\"}"
    } catch (e: Exception) { null }
}

/** Vault 数据结构（解密后的 JSON 对象）*/
data class VaultData(
    val entries: MutableMap<String, VaultEntry> = mutableMapOf(),
) {
    fun put(serviceId: String, entry: VaultEntry) { entries[serviceId] = entry }
    fun get(serviceId: String): VaultEntry? = entries[serviceId]
    fun remove(serviceId: String) { entries.remove(serviceId) }

    fun toJson(): String {
        val obj = JSONObject()
        val arr = org.json.JSONArray()
        entries.forEach { (k, v) ->
            arr.put(JSONObject()
                .put("service_id", k)
                .put("username", v.username)
                .put("password", v.password)
                .put("extra", v.extra))
        }
        obj.put("version", 1)
        obj.put("entries", arr)
        return obj.toString()
    }

    companion object {
        fun fromJson(json: String): VaultData {
            val out = VaultData()
            try {
                val obj = JSONObject(json)
                val arr = obj.optJSONArray("entries") ?: return out
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONObject(i)
                    out.entries[e.optString("service_id")] = VaultEntry(
                        username = e.optString("username"),
                        password = e.optString("password"),
                        extra = e.optString("extra"),
                    )
                }
            } catch (_: Exception) {}
            return out
        }
    }
}

data class VaultEntry(
    val username: String = "",
    val password: String = "",
    val extra: String = "",  // 存 token / apiKey 等辅助字段（JSON 字符串）
)
