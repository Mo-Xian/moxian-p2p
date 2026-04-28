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
    private const val MAX_RETRIES = 5

    // 用 HttpURLConnection 下载（支持自签证书 + Range 断点续传 + 自动重试）
    private fun manualDownloadAndInstall(ctx: Context, release: Release, target: File) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val main = android.os.Handler(ctx.mainLooper)

        fun build(progress: Int, total: Int, text: String, ongoing: Boolean = true): android.app.Notification {
            val b = androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("moxian-p2p ${release.tag}")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
            if (total > 0) b.setProgress(total, progress, false)
            return b.build()
        }

        nm.notify(NOTIF_ID, build(0, 0, "准备下载..."))
        android.widget.Toast.makeText(ctx, "开始下载 见通知栏 + 主页日志", android.widget.Toast.LENGTH_SHORT).show()
        ClientController.appendLog("[update] 开始下载 ${release.tag} ${release.apkUrl}")

        Thread {
            // 删旧文件（中断后可能有残片）只用 .part 临时文件
            val tmp = java.io.File(target.parentFile, target.name + ".part")
            var lastError: Exception? = null

            for (attempt in 1..MAX_RETRIES) {
                try {
                    val resumeFrom = if (tmp.exists()) tmp.length() else 0L
                    if (attempt > 1) {
                        ClientController.appendLog("[update] 重试 #$attempt 从 ${resumeFrom / 1024 / 1024}MB 续传")
                    }
                    val conn = openConn(release.apkUrl)
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000  // 单次读 30s 卡住就重试
                    if (resumeFrom > 0) {
                        conn.setRequestProperty("Range", "bytes=$resumeFrom-")
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) throw RuntimeException("HTTP $code")

                    // 总长度：206 时从 Content-Range 解 否则用 Content-Length
                    val totalSize: Long = if (code == 206) {
                        conn.getHeaderField("Content-Range")
                            ?.substringAfter('/')?.toLongOrNull()
                            ?: (resumeFrom + conn.contentLengthLong)
                    } else {
                        // 200 OK 不支持 range 从头来 截断 .part
                        if (resumeFrom > 0) tmp.delete()
                        conn.contentLengthLong
                    }

                    var done = if (code == 206) resumeFrom else 0L
                    var lastNotifyAt = 0L
                    var lastLogPct = -1
                    java.io.FileOutputStream(tmp, code == 206).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastNotifyAt > 500) {
                                    lastNotifyAt = now
                                    val pct = if (totalSize > 0) ((done * 100) / totalSize).toInt() else 0
                                    val text = if (totalSize > 0)
                                        "$pct%  ${done / 1024 / 1024}/${totalSize / 1024 / 1024} MB" +
                                            (if (attempt > 1) "  (重试 #$attempt)" else "")
                                    else "${done / 1024 / 1024} MB"
                                    nm.notify(NOTIF_ID, build(pct, 100, text))
                                    // 每 10% 写一次主页日志（防刷屏）
                                    if (totalSize > 0 && pct >= lastLogPct + 10) {
                                        lastLogPct = pct
                                        ClientController.appendLog("[update] $text")
                                    }
                                }
                            }
                        }
                    }
                    // 完整下完 把 .part 改名为正式 APK
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        throw RuntimeException("rename .part → APK 失败")
                    }
                    ClientController.appendLog("[update] ✅ 下载完成 ${done / 1024 / 1024}MB → $target")
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
                        launchInstall(ctx, target)
                    }
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                    ClientController.appendLog("[update] ⚠️ 失败 #$attempt: ${e.javaClass.simpleName}: ${e.message}")
                    main.post {
                        nm.notify(NOTIF_ID, build(0, 0,
                            "网络异常 重试 #${attempt}/${MAX_RETRIES}（${e.javaClass.simpleName}）"))
                    }
                    try { Thread.sleep(2000L * attempt) } catch (_: InterruptedException) {}
                }
            }

            // 重试用尽 弹失败 dialog
            ClientController.appendLog("[update] ❌ 重试 $MAX_RETRIES 次仍失败 放弃")
            main.post {
                nm.cancel(NOTIF_ID)
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("下载失败（已重试 $MAX_RETRIES 次）")
                    .setMessage("${lastError?.javaClass?.simpleName}: ${lastError?.message}\n\n" +
                            "URL: ${release.apkUrl}\n" +
                            "本地缓存: $tmp（保留 下次自动续传）")
                    .setPositiveButton("再试一次") { _, _ -> downloadAndInstall(ctx, release) }
                    .setNegativeButton("取消", null)
                    .show()
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

    /**
     * APP 启动时调一次：清理已成功安装的旧 APK 文件 + 7 天前的 .part 残片
     * 判定"已安装": 当前 BuildConfig.VERSION_NAME >= 文件名里的 tag
     */
    fun cleanupInstalled(ctx: Context, currentVersion: String) {
        try {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
            val files = dir.listFiles() ?: return
            val apkRe = Regex("""^moxian-p2p-v?([\d.]+)\.apk$""")
            val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            var deleted = 0
            for (f in files) {
                if (f.name.endsWith(".part")) {
                    if (now - f.lastModified() > sevenDaysMs) {
                        if (f.delete()) deleted++
                    }
                    continue
                }
                val m = apkRe.matchEntire(f.name) ?: continue
                val fileVer = m.groupValues[1]
                // 当前版本 >= 文件版本 = 已安装可清
                if (!isNewer(fileVer, currentVersion)) {
                    if (f.delete()) deleted++
                }
            }
            if (deleted > 0) {
                ClientController.appendLog("[update] 清理了 $deleted 个已安装/过期的 APK 文件")
            }
        } catch (_: Exception) {
            // 清理失败不影响主流程
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
