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

    private const val REPO_API = "https://api.github.com/repos/Mo-Xian/moxian-p2p/releases/latest"

    data class Release(val tag: String, val apkUrl: String, val notes: String)

    /** 查询最新 release 返回 null 表示网络错误或无新版 */
    suspend fun checkLatest(current: String): Release? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(REPO_API).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name").removePrefix("v")
            if (tag.isEmpty() || tag == current) return@withContext null
            if (!isNewer(tag, current)) return@withContext null
            val assets = obj.optJSONArray("assets") ?: return@withContext null
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isEmpty()) return@withContext null
            Release(tag = "v$tag", apkUrl = apkUrl, notes = obj.optString("body"))
        } catch (e: Exception) { null }
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
