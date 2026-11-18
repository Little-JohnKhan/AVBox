package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import com.github.avbox.osc.player.engine.PurifyProxy
import com.github.avbox.osc.util.AppLog

import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * 把一个播放地址转换成 media3 的下载请求。
 *
 * 关键点：
 * 1. 入队的地址先过 [PurifyProxy.wrap]，所以下载到的是**剔除广告后的清单**；
 * 2. 请求头按「原始地址的 host」登记到 [DownloadHeaders]，分片下载时会按 host 查回来
 *    （media3 1.11 的 DownloadRequest 本身不带 headers）。
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
     * 准备并返回下载请求（异步）。回调在主线程。
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
        // 分片是源站绝对地址，所以 header 要按原始地址的 host 登记
        DownloadHeaders.register(item.playUrl, item.headers)

        val mime = if (playUrl.contains("m3u8", true) || playUrl.contains("m3u", true)) {
            MimeTypes.APPLICATION_M3U8
        } else {
            null
        }
        val builder = MediaItem.Builder().setUri(playUrl)
        if (mime != null) builder.setMimeType(mime)
        val mediaItem = builder.build()

        val helper: DownloadHelper
        try {
            helper = DownloadHelper.forMediaItem(
                appContext,
                mediaItem,
                DefaultRenderersFactory(appContext),
                DownloadDataSourceFactory,
            )
        } catch (th: Throwable) {
            AppLog.e("DownloadTasks", "create helper failed url=$playUrl", th)
            onError(th)
            return
        }

        helper.prepare(object : DownloadHelper.Callback {
            override fun onPrepared(prepared: DownloadHelper, tracksInfoAvailable: Boolean) {
                try {
                    // HLS / DASH 这类非 progressive 源必须显式选轨，
                    // 否则 getDownloadRequest 产出的 streamKeys 是空的，最终下载出 0 字节的空壳。
                    if (tracksInfoAvailable) {
                        val periods = prepared.periodCount
                        for (i in 0 until periods) {
                            try {
                                prepared.addTrackSelection(
                                    i,
                                    TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT,
                                )
                            } catch (ignored: Throwable) {
                                // progressive 源不需要选轨，忽略
                            }
                        }
                    }
                    val request = prepared.getDownloadRequest(item.id, null)
                    AppLog.i(
                        "echo-download-prepared: id=" + item.id +
                            " tracksAvailable=" + tracksInfoAvailable +
                            " periods=" + prepared.periodCount +
                            " streamKeys=" + request.streamKeys.size +
                            " url=" + playUrl,
                    )
                    onReady(request)
                } catch (th: Throwable) {
                    AppLog.e("DownloadTasks", "build request failed", th)
                    onError(th)
                } finally {
                    prepared.release()
                }
            }

            override fun onPrepareError(prepared: DownloadHelper, e: IOException) {
                AppLog.e("DownloadTasks", "prepare failed id=" + item.id, e)
                prepared.release()
                onError(e)
            }
        })
    }

    /** 给外部（如批量下载）复用：构建一个可用的数据源工厂 */
    fun dataSourceFactory(): DataSource.Factory = DownloadDataSourceFactory
}
