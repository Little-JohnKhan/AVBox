package com.github.avbox.osc.player.download

import android.content.Context
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 「这一集有没有本地成品文件」的查找表。
 *
 * 关键判据是**文件此刻真的存在且非空**，不看任务状态字段。
 * 这样无论下载中断、任务失败、还是用户手动删了文件，都会自然回落到在线播放，
 * 不会像之前那样「索引登记了但文件是空的」导致静默失败。
 *
 * 键是原始播放地址（播放侧传给 setDataSource 的就是它）。
 */
object DownloadLibrary {

    /** 原始地址 → 目标文件绝对路径（无论完成与否都登记，命中与否由文件是否存在决定） */
    private val targets = ConcurrentHashMap<String, String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun localPathOf(originUrl: String?): String? {
        if (originUrl.isNullOrEmpty()) return null
        val path = targets[originUrl] ?: resolve(originUrl) ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0L) path else null
    }

    fun isDownloaded(originUrl: String?): Boolean = localPathOf(originUrl) != null

    /** 自制下载器不用包装地址，保留签名给旧调用点 */
    fun wrappedUrlOf(originUrl: String?): String? = null

    fun markDone(originUrl: String, path: String) {
        if (originUrl.isEmpty() || path.isEmpty()) return
        targets[originUrl] = path
        AppLog.i("echo-download-library-done: n=" + targets.size)
    }

    fun markRemoved(originUrl: String?) {
        if (originUrl.isNullOrEmpty()) return
        targets.remove(originUrl)
        AppLog.i("echo-download-library-remove: n=" + targets.size)
    }

    fun load(context: Context) {
        scope.launch {
            try {
                for (task in TsTaskStore.all()) {
                    if (task.playUrl.isEmpty()) continue
                    targets[task.playUrl] = TsDownloader.publicFileOf(task).absolutePath
                }
                AppLog.i("echo-download-library-load: n=" + targets.size)
            } catch (th: Throwable) {
                AppLog.e("DownloadLibrary", "load failed", th)
            }
        }
    }

    fun refreshAsync(context: Context) = load(context)

    /** 内存表没命中时回查任务表（MMKV 读取是毫秒级，主线程可接受） */
    @Synchronized
    private fun resolve(originUrl: String): String? {
        return try {
            for (task in TsTaskStore.all()) {
                if (task.playUrl != originUrl) continue
                val path = TsDownloader.publicFileOf(task).absolutePath
                targets[originUrl] = path
                return path
            }
            null
        } catch (th: Throwable) {
            AppLog.e("DownloadLibrary", "resolve failed", th)
            null
        }
    }
}
