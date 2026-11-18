package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 列表用的一行：业务信息 + media3 的实时状态 */
data class DownloadRow(
    val item: DownloadItem,
    val state: Int,
    val percent: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val stopReason: Int,
    val failureReason: Int,
) {
    val isDone: Boolean get() = state == Download.STATE_COMPLETED
    val isDownloading: Boolean get() = state == Download.STATE_DOWNLOADING
    val isQueued: Boolean get() = state == Download.STATE_QUEUED
    val isPaused: Boolean get() = state == Download.STATE_STOPPED
    val isFailed: Boolean get() = state == Download.STATE_FAILED
}

/**
 * 下载功能的对外门面：入队、暂停、继续、删除、查询。
 */
object DownloadRepository {

    /** media3 1.11 只留了 STOP_REASON_NONE，暂停用任意非 0 值表达 */
    private const val STOP_REASON_USER = 1


    /** 入队。回调在主线程。 */
    fun enqueue(context: Context, item: DownloadItem, onDone: (Boolean, String?) -> Unit) {
        DownloadMetaStore.put(item)
        DownloadTasks.prepareRequest(
            context = context,
            item = item,
            onReady = { request: DownloadRequest ->
                try {
                    DownloadStore.get(context).addDownload(request)
                    DownloadNotifications.refresh(context)
                    AppLog.i("echo-download-enqueue: id=" + item.id + " name=" + item.vodName + " ep=" + item.episodeName)
                    onDone(true, null)
                } catch (th: Throwable) {
                    AppLog.e("DownloadRepository", "enqueue failed", th)
                    DownloadMetaStore.remove(item.id)
                    onDone(false, th.message)
                }
            },
            onError = { th: Throwable ->
                DownloadMetaStore.remove(item.id)
                onDone(false, th.message)
            },
        )
    }

    suspend fun list(context: Context): List<DownloadRow> = withContext(Dispatchers.IO) {
        val items = DownloadMetaStore.all()
        if (items.isEmpty()) return@withContext emptyList()
        val byId = HashMap<String, Download>()
        try {
            DownloadStore.get(context).downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) {
                    val d = cursor.download
                    byId[d.request.id] = d
                }
            }
        } catch (th: Throwable) {
            AppLog.e("DownloadRepository", "query index failed", th)
        }
        DownloadLibrary.updateFrom(items, byId)
        items.mapNotNull { item ->
            val d = byId[item.id] ?: return@mapNotNull null
            DownloadRow(
                item = item,
                state = d.state,
                percent = d.percentDownloaded,
                downloadedBytes = d.bytesDownloaded,
                totalBytes = d.contentLength,
                stopReason = d.stopReason,
                failureReason = d.failureReason,
            )
        }
    }

    suspend fun pause(context: Context, id: String) {
        withContext(Dispatchers.Main) {
            try {
                DownloadStore.get(context).setStopReason(id, STOP_REASON_USER)
            } catch (th: Throwable) {
                AppLog.e("DownloadRepository", "pause failed", th)
            }
        }
    }

    suspend fun resume(context: Context, id: String) {
        withContext(Dispatchers.Main) {
            try {
                DownloadStore.get(context).setStopReason(id, Download.STOP_REASON_NONE)
                DownloadNotifications.refresh(context)
            } catch (th: Throwable) {
                AppLog.e("DownloadRepository", "resume failed", th)
            }
        }
    }

    suspend fun remove(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            try {
                DownloadStore.get(context).removeDownload(id)
            } catch (th: Throwable) {
                AppLog.e("DownloadRepository", "remove failed", th)
            }
        }
        DownloadMetaStore.remove(id)
    }

    /** 是否已经有这个任务（含已完成的） */
    fun exists(id: String): Boolean = DownloadMetaStore.get(id) != null

    fun cacheSizeBytes(context: Context): Long = DownloadCache.sizeBytes(context)
}
