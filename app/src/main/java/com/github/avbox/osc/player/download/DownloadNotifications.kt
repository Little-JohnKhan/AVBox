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
import com.github.avbox.osc.R
import com.github.avbox.osc.ui.MainActivity
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 下载通知 + 前台服务。
 *
 * 下载线程跑在 App 进程里，没有前台服务的话切到后台很容易被系统回收。
 * 这里挂一个最低成本的前台通知保住进程优先级，进度由服务自己每秒拉一次
 * （[TsDownloader] 的状态在内存里，读取无 IO 开销）。
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
        ticker = scope.launch {
            while (isActive) {
                pushStateNow()
                delay(1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch { pushStateNow() }
        return START_NOT_STICKY
    }

    private fun pushStateNow() {
        val tasks = TsDownloader.snapshot()
        val active = tasks.filter {
            it.state == Download.STATE_DOWNLOADING ||
                it.state == Download.STATE_QUEUED ||
                it.state == Download.STATE_RESTARTING
        }
        val conv = Mp4Converter.state
        if (active.isEmpty()) {
            if (conv == null) {
                stopSelfSafely()
                return
            }
            // 没有下载任务、但在封装：界面不能停，否则进程掉进 cached 会被冻结
            val text = if (conv.failed) {
                "封装失败，保留 ts 直接播 · " + conv.sourceName
            } else {
                "正在封装 " + conv.percent + "% · " + conv.sourceName
            }
            DownloadNotifications.notify(this, "离线下载", text, conv.percent)
            return
        }
        val downloaded = active.sumOf { if (it.bytes > 0L) it.bytes else 0L }
        // 总字节数在下载前未知，进度只能用「已完成分片 / 总片数」算，不能用字节
        val doneSegments = active.sumOf { if (it.doneSegments > 0) it.doneSegments else 0 }
        val totalSegments = active.sumOf { if (it.totalSegments > 0) it.totalSegments else 0 }
        val percent = if (totalSegments > 0) {
            (doneSegments * 100 / totalSegments).coerceIn(0, 100)
        } else {
            0
        }
        val first = active.firstOrNull()
        val parts = ArrayList<String>()
        if (active.size == 1 && first != null) {
            parts.add(first.vodName)
            parts.add(first.episodeName)
            if (first.totalSegments > 0) {
                parts.add(first.doneSegments.toString() + "/" + first.totalSegments + " 片")
            }
            if (first.bytes > 0L) parts.add(formatBytes(first.bytes))
        } else {
            parts.add("共 " + active.size + " 个任务")
            parts.add(percent.toString() + "%")
            if (downloaded > 0L) parts.add(formatBytes(downloaded))
        }
        val text = parts.filter { it.isNotBlank() }.joinToString(" · ")
        DownloadNotifications.notify(this, "离线下载", text, percent)
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
        if (bytes <= 0L) return "0 B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.0f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    override fun onDestroy() {
        ticker?.cancel()
        ticker = null
        scope.cancel()
        DownloadNotifications.cancel(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
