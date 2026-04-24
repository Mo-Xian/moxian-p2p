package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一的"从 Vault 取凭据"辅助
 *
 * 各 NAS 服务 Activity 不再自己存密码 改调本辅助：
 *   1. 先查 AuthSession.getVault() 有没有该 serviceId 的条目
 *   2. 有 → 回调 user/pass
 *   3. 没有 → 弹出登录对话框收集 user/pass + 保存到 Vault 并上传服务器
 *
 * Vault 未解锁时（masterKey 为空）直接弹对话框提示用户去解锁
 */
object VaultAuthHelper {

    /**
     * 获取服务凭据 没有时弹对话框收集
     * @param title 弹窗标题 如 "Jellyfin 登录"
     * @param hintUser 用户名字段提示 如 "用户名" 或 "邮箱"
     * @param onCreds 拿到 (user, pass) 后回调 若 Vault 没有会先弹窗收集
     */
    fun getCreds(
        ctx: Context,
        owner: LifecycleOwner,
        serviceId: String,
        title: String,
        hintUser: String = "用户名",
        onCreds: (user: String, pass: String) -> Unit,
    ) {
        if (!AuthSession.isUnlocked()) {
            Toast.makeText(ctx, "请先解锁 Vault（回主界面 → 解锁）", Toast.LENGTH_LONG).show()
            return
        }
        val vault = AuthSession.getVault()
        val existing = vault?.get(serviceId)
        if (existing != null && existing.username.isNotEmpty()) {
            onCreds(existing.username, existing.password)
            return
        }
        // 弹窗收集
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val etU = EditText(ctx).apply { hint = hintUser }
        val etP = EditText(ctx).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // 预填 email 作为常用 user（避免输两次）
        if (AuthSession.getEmail().isNotEmpty() && hintUser.contains("邮箱")) {
            etU.setText(AuthSession.getEmail())
        }
        layout.addView(etU); layout.addView(etP)

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage("凭据会加密存到 Vault 服务器只看到密文 下次自动使用")
            .setView(layout)
            .setPositiveButton("保存并登录") { _, _ ->
                val u = etU.text.toString().trim()
                val p = etP.text.toString()
                if (u.isEmpty() || p.isEmpty()) {
                    Toast.makeText(ctx, "用户名和密码必填", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                vault?.put(serviceId, VaultEntry(username = u, password = p))
                // 异步上传
                owner.lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) { AuthSession.saveVault(ctx) }
                    if (!saved) {
                        Toast.makeText(ctx, "Vault 同步失败 凭据仅在本地内存", Toast.LENGTH_SHORT).show()
                    }
                }
                onCreds(u, p)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 保存/更新 extra 字段（如 token / api key）*/
    fun updateExtra(ctx: Context, owner: LifecycleOwner, serviceId: String, extra: String) {
        val v = AuthSession.getVault() ?: return
        val cur = v.get(serviceId) ?: VaultEntry()
        v.put(serviceId, cur.copy(extra = extra))
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { AuthSession.saveVault(ctx) }
        }
    }

    /** 清除某服务的凭据（登出时用）*/
    fun clearCreds(ctx: Context, owner: LifecycleOwner, serviceId: String) {
        val v = AuthSession.getVault() ?: return
        v.remove(serviceId)
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) { AuthSession.saveVault(ctx) }
        }
    }
}
