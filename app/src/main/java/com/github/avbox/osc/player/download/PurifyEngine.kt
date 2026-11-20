package com.github.avbox.osc.player.download

import com.github.avbox.osc.player.usecase.M3u8
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.M3u8Purifier

/**
 * 广告过滤的统一入口。
 *
 * 逻辑与播放侧 `StreamProxy.purifyM3u8` 完全一致（改这里就要同步那边）：
 *
 *   ① M3u8Purifier（我们写的）：源方规则 → 块结构异常 → 目录多数派。
 *      擅长「随机 hash 分片」「广告单独转码成一小段」这类源；
 *   ② ①判定不出来时，交给原版 M3u8.purify（目录多数派 + 广告域名 + CUE/DATERANGE 标记）。
 *      擅长「少数派 CDN」这类源。
 *
 * 两层都不命中才算这个源干净。播放和下载现在都走这一个函数，
 * 所以「在线播放看到什么，下载下来就是什么」，不会再出现一边删多一边删少。
 */
object PurifyEngine {

    data class Result(val text: String, val changed: Boolean, val dropped: Int, val reason: String) {
        override fun toString(): String = "changed=" + changed + " dropped=" + dropped + " reason=" + reason
    }

    /** M3u8.currentAdCount 是静态字段，并发处理多份清单会串，这里串行化 */
    private val legacyLock = Any()

    fun purify(content: String, baseUrl: String): Result {
        val text = M3u8Purifier.stripBom(content)

        val report = M3u8Purifier.Report()
        val heuristic = try {
            M3u8Purifier.purify(text, baseUrl, report)
        } catch (th: Throwable) {
            AppLog.e("PurifyEngine heuristic failed: " + th.message)
            null
        }
        if (heuristic != null) {
            return Result(heuristic, true, report.dropped, report.reason.ifEmpty { "块结构异常" })
        }

        synchronized(legacyLock) {
            try {
                val legacy = M3u8.purify(dirOf(baseUrl), text)
                val ads = M3u8.currentAdCount
                if (legacy != null && ads > 0) {
                    return Result(legacy, true, ads, "原版多数派")
                }
            } catch (th: Throwable) {
                AppLog.e("PurifyEngine legacy failed: " + th.message)
            }
        }

        return Result(text, false, 0, report.reason.ifEmpty { "未检出广告" })
    }

    /** 清单所在目录（末尾带斜杠），原版 purify 靠它解析相对路径 */
    private fun dirOf(url: String): String {
        val q = url.indexOf('?')
        val u = if (q >= 0) url.substring(0, q) else url
        val i = u.lastIndexOf('/')
        return if (i > 8) u.substring(0, i + 1) else u
    }
}
