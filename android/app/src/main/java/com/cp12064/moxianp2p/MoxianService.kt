package com.cp12064.moxianp2p

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台 Service：托管 moxian-client 子进程
 * 挂一个持久通知 系统不会把进程/UDP socket 冷冻 (Doze / App Standby / 厂商 ROM 限制)
 */
class MoxianService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val cfg = extractConfig(intent) ?: run {
            ClientController.appendLog("[service] missing config, stopping")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!ClientController.isRunning()) {
            ClientController.start(this, cfg)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ClientController.stop()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "moxian-p2p 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 P2P 隧道持续运行"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            mgr.createNotificationChannel(ch)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("moxian-p2p 运行中")
            .setContentText("P2P 隧道保持在线 点击查看")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须声明 foregroundServiceType
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun extractConfig(intent: Intent?): ClientController.Config? {
        intent ?: return null
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return null
        val server = intent.getStringExtra(EXTRA_SERVER) ?: return null
        val udp = intent.getStringExtra(EXTRA_UDP) ?: return null
        val pass = intent.getStringExtra(EXTRA_PASS) ?: return null
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
        val forwards = intent.getStringArrayListExtra(EXTRA_FORWARDS) ?: arrayListOf()
        val mesh = intent.getBooleanExtra(EXTRA_MESH, false)
        return ClientController.Config(nodeId, server, udp, token, pass, forwards, mesh = mesh)
    }

    companion object {
        const val CHANNEL_ID = "moxian_service"
        const val NOTIF_ID = 1001

        const val EXTRA_NODE_ID = "node_id"
        const val EXTRA_SERVER = "server"
        const val EXTRA_UDP = "udp"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_PASS = "pass"
        const val EXTRA_FORWARDS = "forwards"
        const val EXTRA_MESH = "mesh"

        fun buildIntent(context: Context, cfg: ClientController.Config): Intent =
            Intent(context, MoxianService::class.java).apply {
                putExtra(EXTRA_NODE_ID, cfg.nodeId)
                putExtra(EXTRA_SERVER, cfg.server)
                putExtra(EXTRA_UDP, cfg.udp)
                putExtra(EXTRA_TOKEN, cfg.token)
                putExtra(EXTRA_PASS, cfg.pass)
                putStringArrayListExtra(EXTRA_FORWARDS, ArrayList(cfg.forwards))
                putExtra(EXTRA_MESH, cfg.mesh)
            }
    }
}
