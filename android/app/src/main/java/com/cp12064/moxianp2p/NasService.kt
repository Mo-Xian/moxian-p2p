package com.cp12064.moxianp2p

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// NAS 服务条目 存在 SharedPreferences 里 供启动器列表渲染
// 认证 token 各服务客户端自己管 不存在这里（避免泄漏 也便于 QR 分享时不带密码）
data class NasService(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val type: ServiceType = ServiceType.OTHER,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("type", type.name)
    }

    companion object {
        fun fromJson(o: JSONObject): NasService = NasService(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            url = o.optString("url"),
            type = runCatching { ServiceType.valueOf(o.optString("type", "OTHER")) }
                .getOrDefault(ServiceType.OTHER),
        )

        // 从 id 加载（QBittorrentActivity 等通过 svc_id 取完整对象）
        fun findById(ctx: Context, id: String): NasService? =
            NasServiceStore.load(ctx).firstOrNull { it.id == id }
    }
}

// 服务类别 决定图标和默认 deeplink 匹配规则
enum class ServiceType(val label: String, val emoji: String) {
    VIDEO("影视", "📺"),
    MUSIC("音乐", "🎵"),
    PHOTO("相册", "📷"),
    FILE("文件", "📁"),
    PASSWORD("密码", "🔐"),
    DOWNLOAD("下载", "⬇️"),
    DASHBOARD("面板", "📊"),
    OTHER("其他", "🔗");
}

// 预设模板 用户新建时可一键填充 剩下只需改 IP:PORT
// URL 中的 {peer} 会在导入时按当前发现的对端 vIP 替换 否则保留占位符提示用户编辑
// server 按 uid%256 给每个用户分配独立 /24 vIP 不能写死 10.88.0.2
data class ServiceTemplate(
    val name: String,
    val defaultUrl: String,
    val type: ServiceType,
)

object ServiceTemplates {
    const val PEER_PLACEHOLDER = "{peer}"

    val all: List<ServiceTemplate> = listOf(
        ServiceTemplate("Jellyfin 影视",   "http://{peer}:8096",  ServiceType.VIDEO),
        ServiceTemplate("Navidrome 音乐",  "http://{peer}:4533",  ServiceType.MUSIC),
        ServiceTemplate("Immich 相册",     "http://{peer}:2283",  ServiceType.PHOTO),
        ServiceTemplate("qBittorrent",     "http://{peer}:8081",  ServiceType.DOWNLOAD),
        ServiceTemplate("Vaultwarden 密码","http://{peer}:8080",  ServiceType.PASSWORD),
        ServiceTemplate("Syncthing",       "http://{peer}:8384",  ServiceType.FILE),
        ServiceTemplate("AdGuard Home",    "http://{peer}:3000",  ServiceType.DASHBOARD),
        ServiceTemplate("自定义 Web",       "http://",             ServiceType.OTHER),
    )
}

// SharedPreferences 持久化 用 JSON 数组存 所有服务
object NasServiceStore {
    private const val PREFS = "moxian"
    private const val KEY = "nas_services"

    fun load(ctx: Context): List<NasService> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { NasService.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(ctx: Context, services: List<NasService>) {
        val arr = JSONArray()
        services.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, s: NasService) {
        save(ctx, load(ctx) + s)
    }

    fun update(ctx: Context, s: NasService) {
        save(ctx, load(ctx).map { if (it.id == s.id) s else it })
    }

    fun delete(ctx: Context, id: String) {
        save(ctx, load(ctx).filterNot { it.id == id })
    }
}

// 按服务类型路由到最合适的打开方式
// 优先级：内置客户端 Activity > 已装原生 APP（deeplink）> APP 内 WebView
object ServiceLauncher {
    // 常见专业 APP 的包名（用户已装时可提供"打开原生 APP"选项）
    private val knownApps: Map<ServiceType, List<String>> = mapOf(
        ServiceType.VIDEO to listOf(
            "org.jellyfin.mobile",
            "dev.jdtech.jellyfin",
        ),
        ServiceType.MUSIC to listOf(
            "app.symfonik.music.player",
        ),
        ServiceType.PHOTO to listOf(
            "app.alextran.immich",
        ),
    )

    // 内置轻量客户端映射（v1.0.0 全覆盖常见服务）
    // 按服务名关键字匹配 一方面看 ServiceType 一方面容错用户自定义的名字
    private fun builtInActivity(svc: NasService): Class<*>? {
        val name = svc.name.lowercase()
        val url = svc.url.lowercase()
        return when {
            // 下载
            "qbittorrent" in name || "qbit" in name ->
                QBittorrentActivity::class.java
            // 同步
            "syncthing" in name || ":8384" in url ->
                SyncthingActivity::class.java
            // DNS / 拦截
            "adguard" in name || ":3000" in url ->
                AdGuardActivity::class.java
            // 相册
            "immich" in name || svc.type == ServiceType.PHOTO ->
                ImmichActivity::class.java
            // 影视
            "jellyfin" in name || "emby" in name || svc.type == ServiceType.VIDEO ->
                JellyfinActivity::class.java
            // 音乐
            "navidrome" in name || "subsonic" in name || svc.type == ServiceType.MUSIC ->
                NavidromeActivity::class.java
            // 密码
            "vaultwarden" in name || "bitwarden" in name || svc.type == ServiceType.PASSWORD ->
                VaultwardenActivity::class.java
            else -> null
        }
    }

    fun open(ctx: Context, svc: NasService) {
        // 1. 有内置客户端走内置
        builtInActivity(svc)?.let { cls ->
            // 内置 NAS 客户端从 vault 读凭据 必须先解锁
            // 未解锁就先跳 UnlockActivity 解锁完再启动目标 Activity
            if (!AuthSession.isUnlocked()) {
                val intent = Intent(ctx, UnlockActivity::class.java).apply {
                    putExtra("post_unlock_class", cls.name)
                    putExtra("post_unlock_svc_id", svc.id)
                }
                try { ctx.startActivity(intent); return } catch (_: Exception) {}
            }
            val intent = Intent(ctx, cls).apply {
                putExtra("svc_id", svc.id)
            }
            try { ctx.startActivity(intent); return } catch (_: Exception) {}
        }

        // 2. WebView 兜底（不读 vault 不需解锁）
        val intent = Intent(ctx, WebViewActivity::class.java).apply {
            putExtra("svc_id", svc.id)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "打开失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 辅助：让用户选择跳转到原生 APP（如已装）从内置客户端 Activity 里调用
    fun tryOpenNative(ctx: Context, svc: NasService): Boolean {
        val pm = ctx.packageManager
        knownApps[svc.type]?.forEach { pkg ->
            if (isInstalled(pm, pkg)) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(svc.url)).apply {
                    setPackage(pkg)
                }
                try { ctx.startActivity(intent); return true } catch (_: Exception) {}
            }
        }
        return false
    }

    // 辅助：打开系统浏览器（WebViewActivity 里"外部浏览器打开"按钮用）
    fun openInExternalBrowser(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "打开失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }
}
