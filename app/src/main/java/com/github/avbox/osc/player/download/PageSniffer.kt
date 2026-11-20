package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 轻量页面嗅探器：不开 WebView，直接用 HTTP 把解析页的地址换出来。
 *
 * 现在主流的一批解析站（hhjx.hhplayer.com 这一类）结构都一样：
 * 页面里塞一个 `window.__HHJX_BOOTSTRAP__ = {"url","t","key","ts_key"}`，
 * 真正的解密发生在服务端 —— 前端只是把这个 JSON 原样 POST 到 `/api/parse`。
 *
 * 所以流程是：
 *   GET 解析页 → 取 bootstrap → POST {origin}/api/parse → 拿真实 m3u8
 *
 * 注意 url 串通常绑 IP + 有时效，必须在设备上现取现用，不能缓存。
 */
object PageSniffer {

    private const val TAG = "PageSniffer"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Mobile Safari/537.36"

    data class Hit(val url: String, val headers: Map<String, String>?)

    private val cookies = object : CookieJar {
        private val store = HashMap<String, List<Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookieList: List<Cookie>) {
            store[url.host] = cookieList
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host].orEmpty()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .cookieJar(cookies)
            .build()
    }

    private val bootstrapRegex = Regex("__HHJX_BOOTSTRAP__\\s*=\\s*(\\{[\\s\\S]*?\\});")

    suspend fun sniff(pageUrl: String): Hit? = withContext(Dispatchers.IO) {
        if (!pageUrl.startsWith("http", ignoreCase = true)) return@withContext null
        val html = try {
            getText(pageUrl, referer = null)
        } catch (th: Throwable) {
            AppLog.i(TAG + " 打开解析页失败: " + th.message)
            return@withContext null
        }
        val match = bootstrapRegex.find(html) ?: run {
            AppLog.i(TAG + " 页面里没有 bootstrap")
            return@withContext null
        }
        val boot = try {
            JSONObject(match.groupValues[1])
        } catch (th: Throwable) {
            AppLog.i(TAG + " bootstrap 不是合法 JSON")
            return@withContext null
        }
        val origin = originOf(pageUrl) ?: return@withContext null
        val payload = JSONObject().apply {
            put("url", boot.optString("url"))
            put("t", boot.optLong("t"))
            put("key", boot.optString("key"))
            put("client_fallback", false)
            if (boot.optInt("act", 0) == 99) put("act", 99)
        }
        val text = try {
            postJson(origin + "/api/parse", payload.toString(), pageUrl)
        } catch (th: Throwable) {
            AppLog.i(TAG + " /api/parse 请求失败: " + th.message)
            return@withContext null
        }
        val json = try {
            JSONObject(text)
        } catch (th: Throwable) {
            AppLog.i(TAG + " /api/parse 返回不是 JSON: " + text.take(80))
            return@withContext null
        }
        val code = json.optInt("code", 0)
        if (code != 200) {
            AppLog.i(TAG + " /api/parse code=" + code + " msg=" + json.optString("msg"))
            return@withContext null
        }
        val url = json.optString("url", "")
        if (!url.startsWith("http", ignoreCase = true)) return@withContext null
        if (json.optString("ext", "") == "youku") {
            AppLog.i(TAG + " 返回优酷浏览器指令，后台无法处理")
            return@withContext null
        }
        AppLog.i(TAG + " 解出真实地址 " + url.take(110))
        Hit(url, mapOf("Referer" to pageUrl, "User-Agent" to UA))
    }

    private fun originOf(pageUrl: String): String? {
        val parsed = pageUrl.toHttpUrlOrNull() ?: return null
        val port = if (parsed.port == parsed.defaultPort) "" else ":" + parsed.port
        return parsed.scheme + "://" + parsed.host + port
    }

    private fun getText(url: String, referer: String?): String {
        val builder = Request.Builder().url(url).get().header("User-Agent", UA)
        if (!referer.isNullOrEmpty()) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw java.io.IOException("HTTP " + resp.code)
            return resp.body?.string() ?: ""
        }
    }

    private fun postJson(url: String, body: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Origin", originOf(referer).orEmpty())
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful && text.isEmpty()) throw java.io.IOException("HTTP " + resp.code)
            return text
        }
    }
}
