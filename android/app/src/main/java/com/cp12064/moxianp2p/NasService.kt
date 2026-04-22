package com.cp12064.moxianp2p

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// NAS 服务条目 存在 SharedPreferences 里 供启动器列表渲染
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
data class ServiceTemplate(
    val name: String,
    val defaultUrl: String,
    val type: ServiceType,
)

object ServiceTemplates {
    val all: List<ServiceTemplate> = listOf(
        ServiceTemplate("Jellyfin 影视",   "http://10.88.0.2:8096",  ServiceType.VIDEO),
        ServiceTemplate("Navidrome 音乐",  "http://10.88.0.2:4533",  ServiceType.MUSIC),
        ServiceTemplate("Immich 相册",     "http://10.88.0.2:2283",  ServiceType.PHOTO),
        ServiceTemplate("qBittorrent",     "http://10.88.0.2:8081",  ServiceType.DOWNLOAD),
        ServiceTemplate("Vaultwarden 密码","http://10.88.0.2:8080",  ServiceType.PASSWORD),
        ServiceTemplate("Syncthing",       "http://10.88.0.2:8384",  ServiceType.FILE),
        ServiceTemplate("AdGuard Home",    "http://10.88.0.2:3000",  ServiceType.DASHBOARD),
        ServiceTemplate("自定义 Web",       "http://",                ServiceType.OTHER),
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

// 按服务类别决定打开方式：优先尝试已知专业 APP 的 deeplink 失败回退浏览器
object ServiceLauncher {
    // 常见专业 APP 的包名（用户已装时优先用它们打开）
    private val knownApps: Map<ServiceType, List<String>> = mapOf(
        ServiceType.VIDEO to listOf(
            "org.jellyfin.mobile",           // Jellyfin 官方
            "com.infuse7.Infuse",            // Infuse（iOS 为主 Android 少）
            "dev.jdtech.jellyfin",           // Findroid
        ),
        ServiceType.MUSIC to listOf(
            "app.symfonik.music.player",     // Symfonium
            "com.simplecity.amp.pro",        // Shuttle+
        ),
        ServiceType.PHOTO to listOf(
            "app.alextran.immich",           // Immich
        ),
    )

    fun open(ctx: Context, svc: NasService) {
        // 先试已知专业 APP
        val pm = ctx.packageManager
        knownApps[svc.type]?.forEach { pkg ->
            if (isInstalled(pm, pkg)) {
                // 让 APP 自己处理 URL（多数专业 APP 支持通过 Intent 接收 URL）
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(svc.url)).apply {
                    setPackage(pkg)
                }
                try {
                    ctx.startActivity(intent)
                    return
                } catch (_: Exception) {
                    // 继续下一个
                }
            }
        }
        // 兜底：浏览器打开 URL
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(svc.url))
        try {
            ctx.startActivity(fallback)
        } catch (e: Exception) {
            // 没浏览器？极端情况 toast 提示
            android.widget.Toast.makeText(
                ctx, "无法打开 ${svc.url}: ${e.message}", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
        pm.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
