package com.github.avbox.osc.player.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.MainActivity
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载通知 + 前台服务。
 *
 * 下载线程跑在 App 进程里，没有前台服务的话切到后台很容易被系统回收（
 * 之前的 TVBox 版就吃过这个亏）。这里挂一个最低成本的前台通知保住进程优先级。
 */
object DownloadNotifications {

    const val CHANNEL_ID = "avbox_download"
    const val NOTIFICATION_ID = 0x0A11

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "离线下载", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    /** 拉起前台服务（已有任务时重复调用无副作用） */
    fun refresh(context: Context) {
        try {
            val intent = Intent(context, DownloadForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (th: Throwable) {
            AppLog.e("DownloadNotifications", "start service failed", th)
        }
    }

    fun build(context: Context, title: String, text: String, progress: Int, indeterminate: Boolean): Notification {
        ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pending = PendingIntent.getActivity(context, 0, open, flags)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    fun notify(context: Context, title: String, text: String, progress: Int) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.notify(NOTIFICATION_ID, build(context, title, text, progress, false))
        } catch (th: Throwable) {
            AppLog.e("DownloadNotifications", "notify failed", th)
        }
    }

    fun cancel(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.cancel(NOTIFICATION_ID)
        } catch (ignored: Throwable) {
        }
    }
}

class DownloadForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var listener: DownloadManager.Listener? = null

    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.ensureChannel(this)
        try {
            startForeground(
                DownloadNotifications.NOTIFICATION_ID,
                DownloadNotifications.build(this, "离线下载", "正在准备…", 0, true),
            )
        } catch (th: Throwable) {
            AppLog.e("DownloadService", "startForeground failed", th)
        }
        val manager = DownloadStore.get(this)
        val l = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) {
                if (download.state == Download.STATE_COMPLETED) {
                    DownloadLibrary.refreshAsync(this@DownloadForegroundService)
                }
                scope.launch { pushStateNow() }
            }

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                scope.launch { pushStateNow() }
            }
        }
        listener = l
        manager.addListener(l)
        // media3 的 Listener 只在状态变化时回调，分片进度不会通知，
        // 所以这里自己每秒拉一次，跟下载列表保持一致。
        ticker = scope.launch {
            while (true) {
                pushStateNow()
                delay(1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { pushStateNow() }
        return START_NOT_STICKY
    }

    private suspend fun pushStateNow() {
        run {
            val manager = DownloadStore.get(this)
            val rows = withContext(Dispatchers.IO) {
                try {
                    val list = ArrayList<Download>()
                    manager.downloadIndex.getDownloads().use { cursor ->
                        while (cursor.moveToNext()) {
                            list.add(cursor.download)
                        }
                    }
                    list
                } catch (th: Throwable) {
                    AppLog.e("DownloadService", "query downloads failed", th)
                    null
                }
            } ?: return

            val active = rows.filter {
                it.state == Download.STATE_DOWNLOADING ||
                    it.state == Download.STATE_QUEUED ||
                    it.state == Download.STATE_RESTARTING
            }
            if (active.isEmpty()) {
                stopSelfSafely()
                return
            }
            var downloaded = 0L
            var total = 0L
            var percentSum = 0f
            for (d in active) {
                downloaded += d.bytesDownloaded
                total += if (d.contentLength > 0) d.contentLength else 0L
                percentSum += if (d.percentDownloaded > 0f) d.percentDownloaded else 0f
            }
            val percent = if (total > 0) {
                ((downloaded.toDouble() / total.toDouble()) * 100).toInt()
            } else {
                (percentSum / active.size).toInt()
            }
            val text = if (total > 0) {
                formatBytes(downloaded) + " / " + formatBytes(total) + " · 共 " + active.size + " 个任务"
            } else {
                "共 " + active.size + " 个任务 · " + percent + "%"
            }
            DownloadNotifications.notify(this@DownloadForegroundService, "离线下载", text, percent)
        }
    }

    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (ignored: Throwable) {
        }
        DownloadNotifications.cancel(this)
        stopSelf()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    override fun onDestroy() {
        ticker?.cancel()
        ticker = null
        listener?.let { DownloadStore.get(this).removeListener(it) }
        listener = null
        scope.cancel()
        DownloadNotifications.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
