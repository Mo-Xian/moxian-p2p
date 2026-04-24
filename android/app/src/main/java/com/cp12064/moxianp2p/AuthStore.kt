package com.cp12064.moxianp2p

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * NAS 服务凭据加密存储
 *
 * 所有 NAS 服务客户端（qBit / Jellyfin / ...）的用户名/密码/token 统一走这里
 * 底层：EncryptedSharedPreferences + Android Keystore（TEE 硬件保护）
 *
 * 安全特性：
 *   - 文件内容 AES-256-GCM 加密
 *   - 主密钥硬件 Keystore 隔离 卸载 APP 丢失
 *   - APK 卸载 / 克隆数据无法解密
 *
 * 使用：
 *   AuthStore.prefs(ctx).edit().putString("qbit_user_xxx", "admin").apply()
 *   AuthStore.prefs(ctx).getString("qbit_user_xxx", null)
 *
 * 附加：saveLastCredentials / loadLastCredentials 给 LoginWizard 和各登录对话框做预填
 */
object AuthStore {

    private const val FILE_NAME = "moxian_auth"
    private const val KEY_LAST_USER = "_last_user"
    private const val KEY_LAST_PASS = "_last_pass"

    @Volatile private var cached: SharedPreferences? = null

    fun prefs(ctx: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val masterKey = MasterKey.Builder(ctx.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val p = EncryptedSharedPreferences.create(
                ctx.applicationContext,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            cached = p
            migrateFromLegacyIfNeeded(ctx, p)
            return p
        }
    }

    /** 记录最后一次登录成功的凭据 供跨服务预填 */
    fun saveLastCredentials(ctx: Context, user: String, pass: String) {
        prefs(ctx).edit()
            .putString(KEY_LAST_USER, user)
            .putString(KEY_LAST_PASS, pass)
            .apply()
    }

    fun loadLastCredentials(ctx: Context): Pair<String, String>? {
        val p = prefs(ctx)
        val u = p.getString(KEY_LAST_USER, null) ?: return null
        val pwd = p.getString(KEY_LAST_PASS, null) ?: return null
        return Pair(u, pwd)
    }

    /**
     * 一次性迁移：把 v1.0.x 明文存在 "moxian" prefs 里的认证项搬到加密 prefs
     * 完成后从老 prefs 删除这些键 避免再读到明文
     */
    private fun migrateFromLegacyIfNeeded(ctx: Context, newPrefs: SharedPreferences) {
        if (newPrefs.getBoolean("_migrated_v1_1", false)) return
        val legacy = ctx.applicationContext.getSharedPreferences("moxian", Context.MODE_PRIVATE)

        val authKeyPatterns = listOf(
            "qbit_user_", "qbit_pass_",
            "sync_apikey_",
            "ag_user_", "ag_pass_",
            "immich_token_",
            "jf_token_", "jf_uid_",
            "nav_user_", "nav_pass_",
            "vw_email_", "vw_device_",
        )
        val exactKeys = listOf("jf_device")

        val toMigrate = legacy.all.filter { (k, v) ->
            v is String && (
                authKeyPatterns.any { k.startsWith(it) } || exactKeys.contains(k)
            )
        }

        if (toMigrate.isNotEmpty()) {
            val edit = newPrefs.edit()
            toMigrate.forEach { (k, v) -> edit.putString(k, v as String) }
            edit.putBoolean("_migrated_v1_1", true).apply()

            val legacyEdit = legacy.edit()
            toMigrate.keys.forEach { legacyEdit.remove(it) }
            legacyEdit.apply()
        } else {
            newPrefs.edit().putBoolean("_migrated_v1_1", true).apply()
        }
    }
}
