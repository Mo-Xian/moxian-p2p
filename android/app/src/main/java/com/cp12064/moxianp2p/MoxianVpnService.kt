package com.cp12064.moxianp2p

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * 基于 Android VpnService 的 TUN 实现
 * 工作流程：
 *   Builder.addAddress(vip, 24) + addRoute(10.88.0.0, 24) + establish() → tun fd
 *   把 fd 传给 Go 侧（ClientController.start），Go 代码通过 wireguard/tun.CreateFromFD 复用
 *   所有路由到 10.88.0.0/24 的流量经 TUN → Go → P2P 隧道 → 对端
 */
class MoxianVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val yamlCfg = intent?.getStringExtra(EXTRA_YAML)
        val vip = intent?.getStringExtra(EXTRA_VIP)
        if (yamlCfg.isNullOrBlank() || vip.isNullOrBlank()) {
            ClientController.appendLog("[service] missing yaml or vip, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // 建立 TUN 网卡
        val fd = try {
            buildVpn(vip)
        } catch (e: Exception) {
            ClientController.appendLog("[service] VpnService.Builder failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        } ?: run {
            ClientController.appendLog("[service] establish() returned null (没有 VPN 授权?)")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotif()

        if (!ClientController.start(this, yamlCfg, fd)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun buildVpn(vip: String): Int? {
        val builder = Builder()
            .setSession("moxian-p2p")
            .setMtu(1400)
            .addAddress(vip, 24)
            .addRoute("10.88.0.0", 24)

        // 防止本 APP 自己的流量被路由回 TUN 形成死循环
        // 如果不加，moxian-client 的信令/STUN UDP 包会被自己的 VPN 吞了
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }

        val pfd = builder.establish() ?: return null
        return pfd.detachFd()
    }

    private fun startForegroundNotif() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "moxian-p2p VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 VPN + P2P 隧道在线"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            mgr.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("moxian-p2p 运行中")
            .setContentText("虚拟局域网已连接")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        ClientController.stop()
        super.onDestroy()
    }

    override fun onRevoke() {
        // 用户手动撤销 VPN 授权
        ClientController.stop()
        stopSelf()
        super.onRevoke()
    }

    companion object {
        const val CHANNEL_ID = "moxian_vpn"
        const val NOTIF_ID = 1001
        const val EXTRA_YAML = "yaml"
        const val EXTRA_VIP = "vip"

        fun buildStartIntent(ctx: Context, yaml: String, vip: String): Intent =
            Intent(ctx, MoxianVpnService::class.java).apply {
                putExtra(EXTRA_YAML, yaml)
                putExtra(EXTRA_VIP, vip)
            }
    }
}
