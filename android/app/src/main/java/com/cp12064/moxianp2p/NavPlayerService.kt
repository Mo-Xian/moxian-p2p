package com.cp12064.moxianp2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.URL

/**
 * Navidrome 后台音乐播放服务
 *
 * 最简版：MediaPlayer 流式播放 + 前台通知（保活）
 * 不包含 MediaSession / 锁屏控制 / 蓝牙媒体键（下一版再补）
 */
class NavPlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "nav_player"
        const val NOTIF_ID = 2001
        const val ACTION_PLAY = "play"
        const val ACTION_TOGGLE = "toggle"
        const val ACTION_STOP = "stop"

        data class Track(val title: String, val artist: String, val coverUrl: String, val streamUrl: String)
        data class State(val title: String, val artist: String, val playing: Boolean)

        @Volatile var currentState: State? = null
            private set

        fun play(ctx: Context, t: Track) {
            val i = Intent(ctx, NavPlayerService::class.java).apply {
                action = ACTION_PLAY
                putExtra("title", t.title)
                putExtra("artist", t.artist)
                putExtra("cover", t.coverUrl)
                putExtra("stream", t.streamUrl)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun togglePause(ctx: Context) {
            ctx.startService(Intent(ctx, NavPlayerService::class.java).apply { action = ACTION_TOGGLE })
        }

        fun stopPlayback(ctx: Context) {
            ctx.startService(Intent(ctx, NavPlayerService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var player: MediaPlayer? = null
    private var currentTitle = ""
    private var currentArtist = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                currentTitle = intent.getStringExtra("title") ?: ""
                currentArtist = intent.getStringExtra("artist") ?: ""
                val stream = intent.getStringExtra("stream") ?: return START_NOT_STICKY
                startPlayback(stream)
            }
            ACTION_TOGGLE -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.start()
                    updateState(it.isPlaying)
                    updateNotif()
                }
            }
            ACTION_STOP -> {
                player?.release(); player = null
                currentState = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(streamUrl: String) {
        player?.release()
        val p = MediaPlayer()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        p.setDataSource(this, Uri.parse(streamUrl))
        p.setOnPreparedListener {
            it.start()
            updateState(true)
            updateNotif()
        }
        p.setOnCompletionListener {
            updateState(false)
            updateNotif()
        }
        p.setOnErrorListener { _, what, extra ->
            updateState(false)
            true
        }
        p.prepareAsync()
        player = p
        updateState(false)  // preparing
        startForegroundSafe()
    }

    private fun updateState(playing: Boolean) {
        currentState = State(currentTitle, currentArtist, playing)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Navidrome 播放", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotif(): Notification {
        val playing = currentState?.playing ?: false
        val pendingOpen = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePI = PendingIntent.getService(
            this, 1,
            Intent(this, NavPlayerService::class.java).apply { action = ACTION_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPI = PendingIntent.getService(
            this, 2,
            Intent(this, NavPlayerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(currentTitle.ifEmpty { "音乐" })
            .setContentText(currentArtist)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingOpen)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放",
                togglePI
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "关闭", stopPI)
            .build()
    }

    private fun startForegroundSafe() {
        val n = buildNotif()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif())
    }

    override fun onDestroy() {
        player?.release(); player = null
        currentState = null
        super.onDestroy()
    }
}
