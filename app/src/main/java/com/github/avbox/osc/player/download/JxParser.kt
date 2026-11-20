package com.github.avbox.osc.player.download

import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.ParseBean
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 二次解析：把「需要嗅探」的播放页交给配置里的 HTTP 解析接口（ParseBean.type == 1）换成真实地址。
 *
 * 播放器走的是 PlayUrlResolver：type=1 用 HTTP 接口，type=0 才开 WebView 嗅探。
 * 后台批量没有 WebView，所以这里只走 type=1 那条路 ——
 * 很多源的 hhplayer / jx 页面正好能被解析接口直接换成 m3u8。
 *
 * 返回链路和 jsonParse 一致：不带 data 包装的 url、`//` 补协议、headers 透传。
 */
object JxParser {

    private const val TAG = "JxParser"

    data class Hit(val url: String, val headers: Map<String, String>?)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    suspend fun parse(webUrl: String): Hit? = withContext(Dispatchers.IO) {
        val beans = try {
            ApiConfig.get().parseBeanList.filter { it.type == 1 }
        } catch (th: Throwable) {
            AppLog.e(TAG + " 读取解析接口失败: " + th.message)
            emptyList<ParseBean>()
        }
        if (beans.isEmpty()) {
            AppLog.i(TAG + " 配置里没有 type=1 的解析接口")
            return@withContext null
        }
        for (pb in beans) {
            val base = pb.url
            if (base.isNullOrEmpty()) continue
            val extHeaders = extHeaders(pb.ext)
            // 两种拼法都试：编码 / 原样（不同接口对参数的期望不一样）
            val requests = listOf(
                base + URLEncoder.encode(webUrl, "UTF-8"),
                base + webUrl,
            )
            for (request in requests.distinct()) {
                val body = try {
                    httpGet(request, extHeaders)
                } catch (th: Throwable) {
                    AppLog.i(TAG + " " + (pb.name ?: "?") + " 请求失败: " + th.message)
                    continue
                }
                val hit = jsonParse(webUrl, body, extHeaders)
                if (hit != null) {
                    AppLog.i(TAG + " 命中 " + (pb.name ?: "?") + " -> " + hit.url.take(100))
                    return@withContext hit
                }
            }
        }
        null
    }

    private fun httpGet(url: String, headers: Map<String, String>?): String {
        val builder = Request.Builder().url(url).get()
        headers?.forEach { (key, value) ->
            try {
                builder.header(key, value)
            } catch (ignored: Throwable) {
            }
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
            return resp.body?.string() ?: ""
        }
    }

    /** pb.ext 里的 header / headers 字段 */
    private fun extHeaders(ext: String?): Map<String, String>? {
        if (ext.isNullOrEmpty()) return null
        return try {
            val json = JSONObject(ext)
            val map = HashMap<String, String>()
            collectHeaders(map, json.opt("header"))
            collectHeaders(map, json.opt("headers"))
            if (map.isEmpty()) null else map
        } catch (th: Throwable) {
            null
        }
    }

    private fun collectHeaders(target: MutableMap<String, String>, raw: Any?) {
        if (raw == null || raw === JSONObject.NULL) return
        try {
            val json = when (raw) {
                is JSONObject -> raw
                is String -> if (raw.trim().isEmpty()) null else JSONObject(raw)
                else -> null
            } ?: return
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (key.isNotEmpty() && value.isNotEmpty()) target[key] = value
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun jsonParse(webUrl: String, body: String, fallbackHeaders: Map<String, String>?): Hit? {
        return try {
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            var url = data.optString("url", root.optString("url", ""))
            if (url.startsWith("//")) url = "http:$url"
            var needSniff = false
            if (url.startsWith("video://")) {
                url = url.substring(8)
                needSniff = true
            }
            if (!url.startsWith("http") && !url.startsWith("data:application")) return null
            if (needSniff || data.optInt("parse", root.optInt("parse", 0)) == 1) {
                // 这个接口自己也说要嗅探，换下一个
                return null
            }
            val headers = HashMap<String, String>()
            fallbackHeaders?.let { headers.putAll(it) }
            collectHeaders(headers, root.opt("header"))
            collectHeaders(headers, root.opt("headers"))
            collectHeaders(headers, data.opt("header"))
            collectHeaders(headers, data.opt("headers"))
            val ua = data.optString("user-agent", root.optString("user-agent", ""))
            if (ua.trim().isNotEmpty()) headers["User-Agent"] = ua.trim()
            val referer = data.optString("referer", root.optString("referer", ""))
            if (referer.trim().isNotEmpty()) headers["Referer"] = referer.trim()
            if (headers.isEmpty()) headers["Referer"] = webUrl
            AppLog.i(TAG + " jsonParse ok url=" + url.take(100))
            Hit(url, headers)
        } catch (th: Throwable) {
            null
        }
    }
}
