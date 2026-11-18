package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import java.util.concurrent.ConcurrentHashMap

/**
 * 「哪些集已经有本地成品文件」的进程内索引。
 *
 * 播放要在 setDataSource（主线程、同步）里判断，不能现场读 KV，所以用内存表，
 * 由下载器完成时写入、启动时批量重建。
 *
 * 键是原始播放地址（不是净化代理地址），因为播放侧传给 setDataSource 的就是它。
 */
object DownloadLibrary {

    /** 原始地址 → 本地 .ts 绝对路径 */
    private val localPaths = ConcurrentHashMap<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun localPathOf(originUrl: String?): String? {
        if (originUrl.isNullOrEmpty()) return null
        return localPaths[originUrl]
    }

    fun isDownloaded(originUrl: String?): Boolean = localPathOf(originUrl) != null

    /** 保留旧签名：自制下载器不再需要包装地址，播放直接读本地文件 */
    fun wrappedUrlOf(originUrl: String?): String? = null

    fun markDone(originUrl: String, path: String) {
        if (originUrl.isEmpty() || path.isEmpty()) return
        localPaths[originUrl] = path
        AppLog.i("echo-download-library-done: n=" + localPaths.size + " path=" + path)
    }

    fun markRemoved(originUrl: String?) {
        if (originUrl.isNullOrEmpty()) return
        localPaths.remove(originUrl)
        AppLog.i("echo-download-library-remove: n=" + localPaths.size)
    }

    /** 从持久化任务表重建索引（App 启动后调用一次即可） */
    fun load(context: Context) {
        scope.launch {
            try {
                val fresh = HashMap<String, String>()
                for (task in TsTaskStore.all()) {
                    if (task.state != Download.STATE_COMPLETED) continue
                    val file = TsDownloader.fileOf(context.applicationContext, task.id)
                    if (!file.exists() || file.length() <= 0L) continue
                    fresh[task.playUrl] = file.absolutePath
                }
                localPaths.clear()
                localPaths.putAll(fresh)
                AppLog.i("echo-download-library-load: n=" + localPaths.size)
            } catch (th: Throwable) {
                AppLog.e("DownloadLibrary", "load failed", th)
            }
        }
    }

    fun refreshAsync(context: Context) = load(context)
}
