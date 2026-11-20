package com.github.avbox.osc.player.download

import com.github.avbox.osc.player.usecase.M3u8
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.M3u8Purifier

/**
 * 广告过滤的唯一入口。播放（StreamProxy.purifyM3u8）和下载（TsDownloader）都走这里。
 *
 * 判定顺序：
 *
 *   ⓪ **源侧已过滤**：清单里出现「空断点」（连续两个 #EXT-X-DISCONTINUITY 之间没有分片）。
 *      正常切片不会产生空断点，它是源删掉中间分片、标记没删留下的伤疤。
 *      一旦看到（≥2 处）就整份放行——**不和源过滤叠加**，这是唯一去看"源做过什么"的判据。
 *
 *   ① M3u8Purifier：源方规则 → 块结构异常 → 目录多数派。
 *      擅长「随机 hash 分片」「广告单独转码成一小段」这类源。
 *
 *   ② ①判不出来时交给原版 M3u8.purify：少数派 CDN + EXTINF 精度/帧率分组 + DISCONTINUITY 分块 + CUE/DATERANGE 标记。
 *      擅长「广告段是另一套时长体系」的源（如 dytt-hot：正片 4.171s 非整秒，广告段时长体系不同）。
 *
 * 两层都不命中才算这个源干净。注意：形态判定会误伤片头（龙标 3 片 6 秒、工作室 logo 2 片 4 秒），
 * 用户已确认接受——各家片头各不相同，无法穷举规则，宁可少删。
 */
object PurifyEngine {

    data class Result(
        val text: String,
        val changed: Boolean,
        val dropped: Int,
        val reason: String,
        val report: M3u8Purifier.Report? = null,
    ) {
        override fun toString(): String = "changed=" + changed + " dropped=" + dropped + " reason=" + reason
    }

    /** M3u8.currentAdCount 是静态字段，并发处理多份清单会串，这里串行化 */
    private val legacyLock = Any()

    fun purify(content: String, baseUrl: String): Result {
        val text = M3u8Purifier.stripBom(content)

        // ⓪ 源已过滤 → 不参与，一个片都不删
        val emptyBreaks = countEmptyDiscontinuities(text)
        if (emptyBreaks >= 2) {
            val report = M3u8Purifier.Report()
            report.total = countSegments(text)
            report.reason = "源已过滤（空断点 " + emptyBreaks + " 处）"
            return Result(text, false, 0, report.reason, report)
        }

        // ① M3u8Purifier（源规则 + 块结构异常 + 目录多数派）
        val report = M3u8Purifier.Report()
        val heuristic = try {
            M3u8Purifier.purify(text, baseUrl, report)
        } catch (th: Throwable) {
            AppLog.e("PurifyEngine 启发式失败: " + th.message)
            null
        }
        if (heuristic != null) {
            return Result(heuristic, true, report.dropped, report.reason.ifEmpty { "块结构异常" }, report)
        }

        // ② 原版兜底（少数派 CDN + 精度/帧率分组 + 分块 + CUE 标记）
        synchronized(legacyLock) {
            try {
                val legacy = M3u8.purify(dirOf(baseUrl), text)
                val ads = M3u8.currentAdCount
                if (legacy != null && ads > 0) {
                    report.dropped = ads
                    if (report.total <= 0) report.total = countSegments(text)
                    report.reason = "原版判定"
                    return Result(legacy, true, ads, report.reason, report)
                }
            } catch (th: Throwable) {
                AppLog.e("PurifyEngine 原版失败: " + th.message)
            }
        }

        return Result(text, false, 0, report.reason.ifEmpty { "未检出广告，保持原样" }, report)
    }

    // ---------- 源过滤痕迹 ----------

    /**
     * 统计「空断点」：连续两个 #EXT-X-DISCONTINUITY 之间没有任何分片行。
     * 实测：《罗小黑战记》wsym3u8 源 50 处（源删过广告），dytt-hot 源 0 处（源没删）。
     */
    private fun countEmptyDiscontinuities(text: String): Int {
        var empty = 0
        var prevWasBreak = false
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                if (prevWasBreak) empty++
                prevWasBreak = true
            } else if (!line.startsWith("#")) {
                prevWasBreak = false
            }
        }
        return empty
    }

    private fun countSegments(text: String): Int {
        var n = 0
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isNotEmpty() && !line.startsWith("#")) n++
        }
        return n
    }

    /** 清单所在目录（末尾带斜杠），原版 purify 靠它解析相对路径 */
    private fun dirOf(url: String): String {
        val q = url.indexOf('?')
        val u = if (q >= 0) url.substring(0, q) else url
        val i = u.lastIndexOf('/')
        return if (i > 8) u.substring(0, i + 1) else u
    }
}
