package com.github.avbox.osc.player.download

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.github.avbox.osc.net.HttpClients
import com.github.avbox.osc.player.core.OkHttpDataSource
import com.github.avbox.osc.util.AppLog

import java.util.concurrent.ConcurrentHashMap

/**
 * 下载用的请求头登记表。
 *
 * media3 1.11 的 DownloadRequest 不带 headers 字段，DownloadManager 只有一个全局
 * upstreamFactory，所以每个源的 UA / Referer 只能这样补：
 * 入队时按 host 登记，DataSource 打开时按分片地址的 host 查回来。
 */
object DownloadHeaders {

    private val byHost = ConcurrentHashMap<String, Map<String, String>>()

    fun register(url: String, headers: Map<String, String>?) {
        if (headers.isNullOrEmpty()) return
        val host = hostOf(url) ?: return
        byHost[host] = headers
        AppLog.i("echo-download-headers: host=" + host + " n=" + headers.size)
    }

    fun forUri(uri: String): Map<String, String>? = hostOf(uri)?.let { byHost[it] }

    private fun hostOf(url: String): String? {
        return try {
            Uri.parse(url).host
        } catch (th: Throwable) {
            null
        }
    }
}

/**
 * 带请求头的下载数据源：按分片地址的 host 查登记表，逐条塞进请求头。
 * 下载内容都是 http/https，所以直接用 OkHttpDataSource，不套 DefaultDataSource。
 */
object DownloadDataSourceFactory : DataSource.Factory {

    private val base: OkHttpDataSource.Factory by lazy {
        OkHttpDataSource.Factory(HttpClients.itvClient() ?: okhttp3.OkHttpClient())
    }

    override fun createDataSource(): DataSource = HeaderDataSource(base.createDataSource())

    private class HeaderDataSource(private val delegate: HttpDataSource) : DataSource {

        override fun open(dataSpec: DataSpec): Long {
            val headers = DownloadHeaders.forUri(dataSpec.uri.toString())
            if (headers != null) {
                for ((k, v) in headers) {
                    delegate.setRequestProperty(k, v)
                }
            }
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length)

        override fun getUri(): Uri? = delegate.uri

        override fun close() = delegate.close()

        override fun addTransferListener(transferListener: TransferListener) {
            delegate.addTransferListener(transferListener)
        }
    }
}
