package com.github.avbox.osc.player.download

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.M3u8Purifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 把已下载的内容导出成一个可给外部播放器打开的 .ts 文件。
 *
 * media3 的下载结果是 SimpleCache 里的二进制块（按 URL 分 key 存），
 * 不是一个能直接播放的文件，所以这里按清单顺序把分片拼起来。
 *
 * 只支持 TS 分片；遇到 #EXT-X-MAP（fMP4）会拒绝，因为拼起来不是合法文件。
 */
object DownloadExporter {

    suspend fun export(context: Context, item: DownloadItem): Result<File> = withContext(Dispatchers.IO) {
        try {
            val cache = DownloadCache.get(context)
            val manifestKey = item.wrappedUrl?.takeIf { it.isNotEmpty() } ?: item.playUrl

            val manifestBytes = readCached(cache, manifestKey)
                ?: return@withContext Result.failure(IOException("缓存里找不到清单"))

            val manifest = manifestBytes.toString(Charsets.UTF_8)
            if (!manifest.contains("#EXTM3U")) {
                return@withContext Result.failure(IOException("缓存内容不是 m3u8 清单"))
            }
            if (manifest.contains("#EXT-X-MAP")) {
                return@withContext Result.failure(IOException("fMP4 分片暂不支持导出"))
            }

            val segments = manifest
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { M3u8Purifier.resolve(manifestKey, it) }

            if (segments.isEmpty()) {
                return@withContext Result.failure(IOException("清单里没有分片"))
            }

            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AVBox",
            )
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, safeName(item) + ".ts")

            var written = 0
            out.outputStream().use { os ->
                for (seg in segments) {
                    val bytes = readCached(cache, seg) ?: break
                    os.write(bytes)
                    written++
                }
            }

            AppLog.i(
                "echo-download-export: seg=" + written + "/" + segments.size +
                    " size=" + out.length() + " path=" + out.absolutePath,
            )
            if (written == 0) {
                out.delete()
                Result.failure(IOException("没有一个分片能从缓存读出来"))
            } else {
                Result.success(out)
            }
        } catch (th: Throwable) {
            AppLog.e("DownloadExporter", "export failed", th)
            Result.failure(th)
        }
    }

    /** 从缓存读整个 URL 的内容；缓存缺失返回 null（不会回源） */
    private fun readCached(cache: Cache, url: String): ByteArray? {
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OfflineFactory)
        val ds = factory.createDataSource()
        return try {
            val len = ds.open(DataSpec(Uri.parse(url)))
            val out = ByteArrayOutputStream(if (len > 0) len.toInt().coerceAtMost(8 * 1024 * 1024) else 8192)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ds.read(buf, 0, buf.size)
                if (n == -1) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } catch (th: Throwable) {
            null
        } finally {
            try {
                ds.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** 缓存缺失时立刻失败，避免导出过程中偷偷联网 */
    private object OfflineFactory : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            override fun open(dataSpec: DataSpec): Long = throw IOException("cache miss")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
            override fun getUri(): Uri? = null
            override fun close() {}
            override fun addTransferListener(transferListener: TransferListener) {}
        }
    }

    private fun safeName(item: DownloadItem): String {
        val raw = (item.vodName + "_" + item.episodeName).ifBlank { item.id }
        return raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
    }
}
