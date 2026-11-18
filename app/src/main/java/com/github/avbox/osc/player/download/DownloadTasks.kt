package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.player.core.OkHttpDataSource
import com.github.avbox.osc.player.engine.PurifyProxy
import com.github.avbox.osc.util.AppLog

import java.security.MessageDigest
import java.util.Locale

/**
 * 把一个播放地址转换成 media3 的下载请求。
 *
 * 关键点：入队的地址先过 [PurifyProxy.wrap]，所以下载到的是**剔除广告后的清单**，
 * 分片与在线播放时完全一致。
 */
object DownloadTasks {

    /** 下载 id：同一部剧 + 同一线路 + 同一集，永远算出同一个 id，天然去重 */
    fun idOf(vodId: String, flag: String?, index: Int): String {
        val raw = (vodId + "|" + (flag ?: "") + "|" + index).lowercase(Locale.US)
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
            sb.toString()
        } catch (th: Throwable) {
            Integer.toHexString(raw.hashCode())
        }
    }

    /**
     * 准备并返回下载请求（异步）。调用方在主线程拿到结果。
     *
     * DownloadHelper 需要先解析清单才能算出总大小和分片列表，所以是异步的。
     */
    fun prepareRequest(
        context: Context,
        item: DownloadItem,
        onReady: (DownloadRequest) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val appContext = context.applicationContext
        val playUrl = PurifyProxy.wrap(item.playUrl, item.headers)
        val mime = if (playUrl.contains("m3u8", true) || playUrl.contains("m3u", true)) {
            MimeTypes.APPLICATION_M3U8
        } else {
            null
        }
        val builder = MediaItem.Builder().setUri(playUrl)
        if (mime != null) builder.setMimeType(mime)
        val mediaItem = builder.build()

        var helper: DownloadHelper? = null
        try {
            helper = DownloadHelper.forMediaItem(
                appContext,
                mediaItem,
                dataSourceFactory(appContext, item.headers),
                DefaultRenderersFactory(appContext),
            )
        } catch (th: Throwable) {
            AppLog.e("DownloadTasks", "create helper failed url=$playUrl", th)
            onError(th)
            return
        }

        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(prepared: DownloadHelper) {
                try {
                    val request = prepared.getDownloadRequest(item.id)
                    AppLog.i(
                        "echo-download-prepared: id=" + item.id + " url=" + playUrl +
                            " bytes=" + request.data.length,
                    )
                    onReady(request)
                } catch (th: Throwable) {
                    AppLog.e("DownloadTasks", "build request failed", th)
                    onError(th)
                } finally {
                    prepared.release()
                }
            }

            override fun onPrepareError(prepared: DownloadHelper, error: Throwable) {
                AppLog.e("DownloadTasks", "prepare failed id=" + item.id, error)
                prepared.release()
                onError(error)
            }
        })
    }

    private fun dataSourceFactory(context: Context, headers: Map<String, String>?): DataSource.Factory {
        var userAgent: String? = null
        val props = HashMap<String, String>()
        if (headers != null) {
            for ((k, v) in headers) {
                if (k.equals("User-Agent", true) || k.equals("user-agent", true)) {
                    userAgent = v
                } else {
                    props[k] = v
                }
            }
        }
        val client = HttpClients.itvClient() ?: okhttp3.OkHttpClient()
        val http = OkHttpDataSource.Factory(client)
        http.setUserAgent(userAgent)
        http.setDefaultRequestProperties(props)
        return DefaultDataSource.Factory(context, http)
    }
}
