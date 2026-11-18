package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.exoplayer.offline.DownloadManager
import com.github.avbox.osc.util.AppLog

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * 下载管理器单例。
 *
 * 全进程只能有一个，因为底下的 SimpleCache 同一目录不允许被打开两次。
 */
object DownloadStore {

    /** 同时下载的分片流数量。太高容易触发源站限流，2 条比较稳。 */
    private const val THREADS = 2

    @Volatile
    private var manager: DownloadManager? = null

    private val executor: Executor by lazy {
        Executors.newFixedThreadPool(
            THREADS,
            object : ThreadFactory {
                private val seq = AtomicInteger(0)
                override fun newThread(r: Runnable): Thread =
                    Thread(r, "avbox-download-" + seq.incrementAndGet()).apply { isDaemon = true }
            },
        )
    }

    @Synchronized
    fun get(context: Context): DownloadManager {
        manager?.let { return it }
        val appContext = context.applicationContext
        val created = DownloadManager(
            appContext,
            DownloadCache.database(appContext),
            DownloadCache.get(appContext),
            executor,
        )
        manager = created
        AppLog.i("echo-download-manager-ready")
        return created
    }

    fun setListener(context: Context, listener: DownloadManager.Listener?) {
        get(context).addListener(listener)
    }
}
