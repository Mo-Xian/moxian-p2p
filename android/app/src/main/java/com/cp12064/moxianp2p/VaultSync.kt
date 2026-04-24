package com.cp12064.moxianp2p

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Vault ↔ AuthStore 桥接器
 *
 * 目的：现有 NAS Activity 继续用 AuthStore（SharedPreferences）读写凭据
 *       但 Vault 是真·源头 服务器加密同步
 *       启动时 pull（vault → prefs）login 后 push（prefs → vault）
 *
 * 保留现有代码几乎不动 只在 onCreate / 登录成功处加两行
 *
 * serviceId 用 NasService.id 作为 vault key
 * prefs key 格式按各服务自己的约定（qbit_user_xxx / jf_token_xxx 等）
 */
object VaultSync {

    /**
     * 从 Vault 同步到 prefs（启动时调用 如 vault 有则覆盖本地 prefs）
     *
     * @param prefsKeys 需要同步的 prefs key 列表
     *   每个 key 在 vault 里对应存在 VaultEntry.extra 的一个 JSON 字段
     *   约定：VaultEntry.username = prefsKeys 第一项的值
     *         VaultEntry.password = prefsKeys 第二项的值
     *         VaultEntry.extra   = JSON map 里其他 keys
     *
     *   简化版：调用方自己在 push 时把所有需要同步的键组装好
     */
    fun pullToPrefs(serviceId: String, prefs: SharedPreferences, userKey: String, passKey: String) {
        val entry = AuthSession.getVault()?.get(serviceId) ?: return
        val edit = prefs.edit()
        if (entry.username.isNotEmpty()) edit.putString(userKey, entry.username)
        if (entry.password.isNotEmpty()) edit.putString(passKey, entry.password)
        edit.apply()
    }

    /**
     * 单一 token/key 类的服务（Syncthing API Key / Immich access token / Jellyfin token）
     * 把 vault entry.password 当作 token 同步到指定 prefs key
     */
    fun pullTokenToPrefs(serviceId: String, prefs: SharedPreferences, tokenKey: String, extraPrefsKey: String? = null) {
        val entry = AuthSession.getVault()?.get(serviceId) ?: return
        val edit = prefs.edit()
        if (entry.password.isNotEmpty()) edit.putString(tokenKey, entry.password)
        if (extraPrefsKey != null && entry.extra.isNotEmpty()) edit.putString(extraPrefsKey, entry.extra)
        edit.apply()
    }

    /**
     * 从 prefs 同步到 Vault（登录成功后 异步上传）
     */
    fun pushFromPrefs(
        ctx: Context,
        owner: LifecycleOwner,
        serviceId: String,
        username: String,
        password: String,
        extra: String = "",
    ) {
        val v = AuthSession.getVault() ?: return
        val cur = v.get(serviceId)
        // 没变化就不折腾
        if (cur != null && cur.username == username && cur.password == password && cur.extra == extra) return

        v.put(serviceId, VaultEntry(username = username, password = password, extra = extra))
        owner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { AuthSession.saveVault(ctx) }
            if (!ok) android.util.Log.w("VaultSync", "upload vault failed for $serviceId")
        }
    }

    /** 用户登出特定服务时 同步清除 Vault 对应条目 */
    fun clearForService(ctx: Context, owner: LifecycleOwner, serviceId: String) {
        val v = AuthSession.getVault() ?: return
        v.remove(serviceId)
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { AuthSession.saveVault(ctx) }
        }
    }
}
