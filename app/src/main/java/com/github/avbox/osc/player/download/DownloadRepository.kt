package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 列表用的一行：业务信息 + 下载状态（状态值沿用 media3 的常量，UI 判断逻辑不变） */
data class DownloadRow(
    val item: DownloadItem,
    val state: Int,
    val percent: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val stopReason: Int,
    val failureReason: Int,
    val error: String? = null,
) {
    val isDone: Boolean get() = state == Download.STATE_COMPLETED
    val isDownloading: Boolean get() = state == Download.STATE_DOWNLOADING
    val isQueued: Boolean get() = state == Download.STATE_QUEUED
    val isPaused: Boolean get() = state == Download.STATE_STOPPED
    val isFailed: Boolean get() = state == Download.STATE_FAILED
}

/**
 * 下载功能对外的门面。
 *
 * 实际执行者是 [TsDownloader]（自建分片下载，产出可播放的 .ts 成品文件），
 * 这里只做状态映射，保证 UI 侧完全不用改。
 */
object DownloadRepository {

    fun enqueue(context: Context, item: DownloadItem, onDone: (Boolean, String?) -> Unit) {
        try {
            TsDownloader.enqueue(context, item)
            onDone(true, null)
        } catch (th: Throwable) {
            onDone(false, th.message ?: "入队失败")
        }
    }

    suspend fun list(context: Context): List<DownloadRow> = withContext(Dispatchers.IO) {
        TsDownloader.snapshot().map { task ->
            DownloadRow(
                item = task.toItem(),
                state = task.state,
                percent = task.percent,
                downloadedBytes = task.bytes,
                totalBytes = if (task.totalBytes > 0L) task.totalBytes else task.bytes,
                stopReason = 0,
                failureReason = 0,
                error = task.error,
            )
        }
    }

    suspend fun pause(context: Context, id: String) {
        withContext(Dispatchers.IO) { TsDownloader.pause(context, id) }
    }

    suspend fun resume(context: Context, id: String) {
        withContext(Dispatchers.IO) { TsDownloader.resume(context, id) }
    }

    /** 失败重试：保留已下载的 .part，从断点继续 */
    suspend fun restart(context: Context, id: String) {
        withContext(Dispatchers.IO) { TsDownloader.restart(context, id) }
    }

    suspend fun remove(context: Context, id: String) {
        withContext(Dispatchers.IO) { TsDownloader.remove(context, id) }
    }

    fun exists(id: String): Boolean = TsDownloader.get(id) != null

    fun cacheSizeBytes(context: Context): Long =
        TsDownloader.snapshot().sumOf { if (it.bytes > 0L) it.bytes else 0L }

    /** App 内播放用：私有目录里的成品文件 */
    fun localFileOf(context: Context, id: String): String? {
        val file = TsDownloader.fileOf(context, id)
        return if (file.exists() && file.length() > 0L) file.absolutePath else null
    }

    /** 对外导出用：Download/AVBox 下的同名文件 */
    fun publicFileOf(context: Context, id: String): String? {
        val file = TsDownloader.publicFileOf(context, id) ?: return null
        return if (file.exists() && file.length() > 0L) file.absolutePath else null
    }
}
