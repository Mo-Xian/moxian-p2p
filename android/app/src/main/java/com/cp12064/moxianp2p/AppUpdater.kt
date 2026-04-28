package com.cp12064.moxianp2p

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * APP 自动升级
 *
 * 流程：
 *   1. checkLatest() 异步查 GitHub API 最新 release
 *   2. 若新版本 > 当前 → 弹对话框让用户决定是否下载
 *   3. 用户同意 → DownloadManager 后台下载 APK 到 ExternalFilesDir
 *   4. 下载完成 → 广播回调触发系统安装界面
 *   5. 用户点"安装" → 新 APK 替换旧版（Android 无法跳过此步 安全限制）
 */
object AppUpdater {

    private const val GH_API = "https://api.github.com/repos/Mo-Xian/moxian-p2p/releases/latest"

    data class Release(val tag: String, val apkUrl: String, val notes: String)

    /**
     * 查询最新 release 优先走用户的 moxian-server（国内连 GitHub 不稳）
     * server 失败再 fallback GitHub
     * 返回 null 表示无新版或两边都不可达
     */
    suspend fun checkLatest(current: String): Release? = withContext(Dispatchers.IO) {
        // 1. 走自己的 server（已登录时）
        val srvBase = AuthSession.getServerUrl()
        if (srvBase.isNotBlank()) {
            val r = checkFromServer(srvBase, current)
            if (r != null) return@withContext r
        }
        // 2. fallback GitHub
        return@withContext checkFromGitHub(current)
    }

    private fun checkFromServer(serverBase: String, current: String): Release? {
        return try {
            val url = serverBase.trimEnd('/') + "/api/release/latest"
            val conn = openConn(url)
            conn.connectTimeout = 4_000
            conn.readTimeout = 6_000
            if (conn.responseCode != 200) {
                null
            } else {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(body)
                val tag = obj.optString("tag").removePrefix("v")
                val apkPath = obj.optString("apk_url")
                when {
                    tag.isEmpty() -> null
                    tag == current -> null
                    !isNewer(tag, current) -> null
                    apkPath.isEmpty() -> null
                    else -> {
                        val apkUrl = if (apkPath.startsWith("http")) apkPath
                                     else serverBase.trimEnd('/') + apkPath
                        Release(tag = "v$tag", apkUrl = apkUrl, notes = obj.optString("notes"))
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkFromGitHub(current: String): Release? {
        return try {
            val conn = URL(GH_API).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name").removePrefix("v")
            if (tag.isEmpty() || tag == current || !isNewer(tag, current)) {
                null
            } else {
                val assets = obj.optJSONArray("assets")
                var apkUrl = ""
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url")
                            break
                        }
                    }
                }
                if (apkUrl.isEmpty()) null
                else Release(tag = "v$tag", apkUrl = apkUrl, notes = obj.optString("body"))
            }
        } catch (e: Exception) {
            null
        }
    }

    // 自签证书下也能开 — 复用 AuthSession 的 insecure 标志
    private fun openConn(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        if (AuthSession.getInsecureTLS() && conn is javax.net.ssl.HttpsURLConnection) {
            // 用反射方式调 trustAllFactory（AuthSession 私有），简化做法：直接装 trust-all
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            })
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
            ctx.init(null, trustAll, java.security.SecureRandom())
            conn.sslSocketFactory = ctx.socketFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        return conn
    }

    /** 对话框 + 下载 + 安装 一整套 */
    fun promptAndUpdate(ctx: Context, release: Release, current: String) {
        AlertDialog.Builder(ctx)
            .setTitle("🎉 发现新版 ${release.tag}")
            .setMessage("当前版本 v$current\n\n${release.notes.take(400).ifBlank { "点更新立即下载 + 安装（Android 会弹系统授权一次）" }}")
            .setPositiveButton("立刻更新") { _, _ -> downloadAndInstall(ctx, release) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun downloadAndInstall(ctx: Context, release: Release) {
        val fileName = "moxian-p2p-${release.tag}.apk"
        val target = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (target.exists()) target.delete()

        // 自己 server 自签证书 走手动下载（DownloadManager 不认）
        // GitHub URL 走 DownloadManager（真证书 + 系统通知更友好）
        val srvBase = AuthSession.getServerUrl()
        val isOwnServer = srvBase.isNotBlank() && release.apkUrl.startsWith(srvBase.trimEnd('/'))
        if (isOwnServer && AuthSession.getInsecureTLS()) {
            manualDownloadAndInstall(ctx, release, target)
            return
        }

        val req = DownloadManager.Request(Uri.parse(release.apkUrl))
            .setTitle("moxian-p2p ${release.tag}")
            .setDescription("下载 APK...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setMimeType("application/vnd.android.package-archive")

        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)

        // 监听下载完成
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                try {
                    c.unregisterReceiver(this)
                } catch (_: Exception) {}
                // 弹安装
                launchInstall(ctx, target)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }

        android.widget.Toast.makeText(ctx, "开始下载 完成后会自动弹安装界面", android.widget.Toast.LENGTH_SHORT).show()
    }

    private const val NOTIF_CHANNEL = "moxian_update"
    private const val NOTIF_ID = 9999

    // 用 HttpURLConnection 下载（支持自签证书）+ 通知栏显示进度
    // 完成后 通知栏可点 触发安装（即使 APP 在后台也能看到）
    private fun manualDownloadAndInstall(ctx: Context, release: Release, target: File) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val main = android.os.Handler(ctx.mainLooper)

        fun build(progress: Int, total: Int, text: String, intent: android.app.PendingIntent? = null,
                  ongoing: Boolean = true): android.app.Notification {
            val b = androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("moxian-p2p ${release.tag}")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
            if (total > 0) b.setProgress(total, progress, false)
            if (intent != null) b.setContentIntent(intent)
            return b.build()
        }

        nm.notify(NOTIF_ID, build(0, 0, "准备下载..."))
        android.widget.Toast.makeText(ctx, "开始下载 见通知栏进度", android.widget.Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val conn = openConn(release.apkUrl)
                conn.connectTimeout = 15_000
                conn.readTimeout = 0  // 0 = 不超时（大文件靠 connectTimeout 防卡死即可）
                if (conn.responseCode !in 200..299) {
                    throw RuntimeException("HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong
                var done = 0L
                var lastNotifyAt = 0L
                target.outputStream().use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            // 限频更新通知（每 500ms 一次）
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyAt > 500) {
                                lastNotifyAt = now
                                val pct = if (total > 0) ((done * 100) / total).toInt() else 0
                                val text = if (total > 0) "$pct%  ${done / 1024 / 1024}/${total / 1024 / 1024} MB"
                                           else "${done / 1024 / 1024} MB"
                                nm.notify(NOTIF_ID, build(pct, 100, text))
                            }
                        }
                    }
                }
                // 下载完 通知栏点一下触发安装（APP 在后台也能用）
                val installIntent = installPendingIntent(ctx, target)
                main.post {
                    nm.notify(NOTIF_ID,
                        androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle("moxian-p2p ${release.tag} 已下载")
                            .setContentText("点这里安装 ${done / 1024 / 1024} MB")
                            .setContentIntent(installIntent)
                            .setAutoCancel(true)
                            .setOngoing(false)
                            .build())
                    // 同时直接尝试拉起安装（前台时即时弹出）
                    launchInstall(ctx, target)
                }
            } catch (e: Exception) {
                main.post {
                    nm.cancel(NOTIF_ID)
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("下载失败")
                        .setMessage("${e.javaClass.simpleName}: ${e.message}\n\n" +
                                "URL: ${release.apkUrl}\n" +
                                "本地文件: $target")
                        .setPositiveButton("重试") { _, _ -> downloadAndInstall(ctx, release) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

    private fun ensureChannel(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.getNotificationChannel(NOTIF_CHANNEL) == null) {
                val ch = android.app.NotificationChannel(
                    NOTIF_CHANNEL, "APP 更新",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                ch.description = "下载 APK 进度"
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun installPendingIntent(ctx: Context, apk: File): android.app.PendingIntent {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        else
            android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getActivity(ctx, 0, intent, flags)
    }

    private fun launchInstall(ctx: Context, apk: File) {
        if (!apk.exists()) {
            android.widget.Toast.makeText(ctx, "APK 文件丢失 下载失败？", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(ctx, "无法启动安装器: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** 版本比较 x.y.z 简单按字段 假设数字 */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".", "-").mapNotNull { it.toIntOrNull() }
        val b = current.split(".", "-").mapNotNull { it.toIntOrNull() }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrNull(i) ?: 0
            val y = b.getOrNull(i) ?: 0
            if (x != y) return x > y
        }
        return false
    }
}
