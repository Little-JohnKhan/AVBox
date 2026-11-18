package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.github.avbox.osc.util.AppLog

import java.io.File

/**
 * 下载专用缓存。
 *
 * 刻意和「边播边缓存」的 [com.github.avbox.osc.player.engine.PlayerCache] 分开：
 * - 播放缓存用 LRU 自动淘汰，随时可能被清；
 * - 下载缓存用 NoOpCacheEvictor，只有用户主动删除才会消失。
 *
 * 两者混用的话，一次「清除缓存」就会把下载好的片子删掉。
 */
object DownloadCache {

    private const val CACHE_DIR_NAME = "avbox-downloads"

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var database: StandaloneDatabaseProvider? = null

    /** 下载文件所在目录（App 私有，无需任何存储权限） */
    fun dir(context: Context): File = File(context.filesDir, CACHE_DIR_NAME)

    fun database(context: Context): StandaloneDatabaseProvider {
        val existing = database
        if (existing != null) return existing
        synchronized(DownloadCache::class.java) {
            database?.let { return it }
            val created = StandaloneDatabaseProvider(context.applicationContext)
            database = created
            return created
        }
    }

    @Synchronized
    fun get(context: Context): SimpleCache {
        cache?.let { return it }
        val appContext = context.applicationContext
        val created = try {
            SimpleCache(dir(appContext), NoOpCacheEvictor(), database(appContext))
        } catch (th: Throwable) {
            // 极端情况：上一次进程没释放锁。删掉再建一次，避免整个下载功能不可用。
            AppLog.e("DownloadCache", "open failed, retry after cleanup", th)
            dir(appContext).deleteRecursively()
            SimpleCache(dir(appContext), NoOpCacheEvictor(), database(appContext))
        }
        cache = created
        return created
    }

    /** 缓存占用的字节数（供 UI 显示） */
    fun sizeBytes(context: Context): Long {
        return try {
            get(context).cacheSpace
        } catch (th: Throwable) {
            AppLog.e("DownloadCache", "size query failed", th)
            0L
        }
    }
}
