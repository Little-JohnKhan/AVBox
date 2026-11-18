package com.github.avbox.osc.player.engine

import com.github.avbox.osc.server.ControlManager
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV

import java.net.URLEncoder

/**
 * m3u8 净化代理的地址改写器。
 *
 * 播放器原本把源站 m3u8 地址直接交给 media3，本地 HTTP 服务（127.0.0.1:9978/proxy）
 * 只在源自己写 proxy:// 或 WebView 解析时才参与，所以常规播放是拿不到净化机会的。
 * 这里在 setDataSource 之前把 m3u8 换成 localhost 的净化代理：
 *
 * <pre>
 * https://cdn.x.com/a/index.m3u8?auth_key=xx
 *   -> http://127.0.0.1:9978/proxy?go=live&type=m3u8&pure=1&ua=..&referer=..&url=https%3A%2F%2F...
 * </pre>
 *
 * 代理侧（StreamProxy.purifyM3u8）只净化清单、把分片绝对化回源站，
 * 分片仍由播放器直连，所以磁盘缓存（边播边缓存）和分片带宽都不受影响。
 *
 * 开关：KV 的 player_purify_m3u8，默认开。关掉就完全恢复原版行为。
 */
object PurifyProxy {

    const val KEY_ENABLED = "player_purify_m3u8"

    private const val FLAG = "pure=1"

    /** 判断某个播放地址是否已经是我们包装过的净化代理地址 */
    fun isWrapped(url: String?): Boolean = url != null && url.contains(FLAG)

    fun enabled(): Boolean {
        return try {
            KV.get(KEY_ENABLED, true)
        } catch (th: Throwable) {
            true
        }
    }

    /** 设置页开关调用；写失败只记日志，不打断交互 */
    fun setEnabled(value: Boolean) {
        try {
            KV.put(KEY_ENABLED, value)
            AppLog.i("echo-purify-switch: $value")
        } catch (th: Throwable) {
            AppLog.e("PurifyProxy", "setEnabled failed", th)
        }
    }

    /**
     * 需要净化代理就返回包装后的地址，否则原样返回。
     * 任何一步不确定都退回原地址——宁可放广告，也不能把能播的源弄成黑屏。
     */
    fun wrap(url: String, headers: Map<String, String>?): String {
        try {
            if (url.isEmpty()) return url
            if (isWrapped(url)) return url
            if (SourcePolicy.isLocalProxyUrl(url)) return url
            if (!MediaSources.isHlsUri(url)) return url
            if (!enabled()) return url

            val address = ControlManager.get().getAddress(true)
            if (address.isEmpty()) return url

            val query = StringBuilder()
            appendHeader(query, headers, "User-Agent", "ua")
            appendHeader(query, headers, "user-agent", "ua")
            appendHeader(query, headers, "Referer", "referer")
            appendHeader(query, headers, "referer", "referer")
            appendHeader(query, headers, "Origin", "origin")
            appendHeader(query, headers, "origin", "origin")
            appendHeader(query, headers, "Cookie", "cookie")
            appendHeader(query, headers, "cookie", "cookie")

            val wrapped = address + "proxy?go=live&type=m3u8&" + FLAG + query +
                    "&url=" + URLEncoder.encode(url, "UTF-8")
            AppLog.i("echo-purify-wrap: $wrapped")
            return wrapped
        } catch (th: Throwable) {
            AppLog.e("PurifyProxy", "wrap failed, fallback to origin", th)
            return url
        }
    }

    private fun appendHeader(sb: StringBuilder, headers: Map<String, String>?, from: String, to: String) {
        if (headers == null) return
        if (sb.indexOf("&" + to + "=") >= 0) return
        val value = headers[from] ?: return
        if (value.isEmpty()) return
        sb.append("&").append(to).append("=").append(URLEncoder.encode(value, "UTF-8"))
    }
}
