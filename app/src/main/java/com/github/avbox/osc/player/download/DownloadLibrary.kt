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
 * 「哪些集已经下载完成」的进程内索引。
 *
 * 播放时要在 setDataSource（主线程、同步）里判断该集有没有下载好，
 * 不能现场查 SQLite，所以这里用一份内存表，由后台任务刷新。
 *
 * 键是**原始播放地址**（不是净化代理地址），因为播放侧和下载侧拿到的原始地址是同一个字符串。
 */
object DownloadLibrary {

    private val done = ConcurrentHashMap<String, String>()

    /** 原始地址 → 入队时的净化代理地址。播放时按它构造，才能和下载时完全一致。 */
    private val wrapped = ConcurrentHashMap<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun wrappedUrlOf(originUrl: String?): String? {
        if (originUrl.isNullOrEmpty()) return null
        return wrapped[originUrl]
    }

    fun isDownloaded(originUrl: String?): Boolean {
        if (originUrl.isNullOrEmpty()) return false
        return done.containsKey(originUrl)
    }

    /** 后台刷新，不阻塞调用方 */
    fun refreshAsync(context: Context) {
        scope.launch {
            try {
                refresh(context)
            } catch (th: Throwable) {
                AppLog.e("DownloadLibrary", "refresh failed", th)
            }
        }
    }

    /** 用调用方已经查到的结果直接更新，省一次数据库查询 */
    fun updateFrom(items: List<DownloadItem>, downloads: Map<String, Download>) {
        val fresh = HashMap<String, String>()
        val freshWrapped = HashMap<String, String>()
        for (item in items) {
            val d = downloads[item.id] ?: continue
            if (d.state == Download.STATE_COMPLETED && d.bytesDownloaded > 0) {
                fresh[item.playUrl] = item.id
                item.wrappedUrl?.takeIf { it.isNotEmpty() }?.let { w -> freshWrapped[item.playUrl] = w }
            }
        }
        done.clear()
        done.putAll(fresh)
        wrapped.clear()
        wrapped.putAll(freshWrapped)
    }

    /** 同步刷新，调用方必须已经在后台线程 */
    fun refresh(context: Context) {
        val manager = DownloadStore.get(context)
        val metaById = DownloadMetaStore.all().associateBy { it.id }
        val fresh = HashMap<String, String>()
        val freshWrapped = HashMap<String, String>()
        manager.downloadIndex.getDownloads(Download.STATE_COMPLETED).use { cursor ->
            while (cursor.moveToNext()) {
                val d = cursor.download
                val m = metaById[d.request.id] ?: continue
                if (d.bytesDownloaded <= 0) continue
                fresh[m.playUrl] = m.id
                m.wrappedUrl?.takeIf { it.isNotEmpty() }?.let { w -> freshWrapped[m.playUrl] = w }
            }
        }
        done.clear()
        done.putAll(fresh)
        wrapped.clear()
        wrapped.putAll(freshWrapped)
        AppLog.i("echo-download-library: n=" + done.size)
    }
}
