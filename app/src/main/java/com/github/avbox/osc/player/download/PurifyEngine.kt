package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.M3u8Purifier

/**
 * 广告过滤入口（保守版）。
 *
 * 原则：**信任源侧过滤，我们的规则只做兜底，且只认强特征**。
 *
 *   ① 源方规则：源作者在配置里写明的广告域名 / 正则，命中就删；
 *   ② 少数派 CDN：分片分散在多个域名时，占比明显偏低的那几个域名整体剔除
 *      （广告基本都挂在另一个 CDN 上，正片在自己的 CDN 上）；
 *   ③ 都不命中就原样放行，一个分片都不删。
 *
 * 明确不做的事（实测会误伤的形态猜测）：
 *   - 按「块片数 / 时长偏小」判广告：电影龙标（3 片 6 秒）、动画工作室片头（2 片 4 秒）
 *     都符合短块特征，会被当成广告删掉；
 *   - 按「EXTINF 小数精度 / 帧率」分组：片头常单独转码，帧率与正片不同，同样会被删；
 *   - 按「目录多数派」投票：同域同目录的插播区分不出来，反而容易把片头孤立掉。
 *
 * 各家片头、赞助商 logo 各不相同，靠规则穷举不现实，所以这些一律保留。
 * 代价是漏掉一部分插播广告，用户已确认接受。
 */
object PurifyEngine {

    data class Result(val text: String, val changed: Boolean, val dropped: Int, val reason: String) {
        override fun toString(): String = "changed=" + changed + " dropped=" + dropped + " reason=" + reason
    }

    /** 少数派域名占比低于这个值时判为广告 CDN */
    private const val MINORITY_RATIO = 0.15
    /** 少数派域名至少要这么多片才敢删，避免把单片的特殊地址当广告 */
    private const val MINORITY_MIN_SEGMENTS = 2
    /** 删除量上限（按分片数），超过就整份放行 */
    private const val MAX_DROP_RATIO = 0.20

    fun purify(content: String, baseUrl: String): Result {
        val text = M3u8Purifier.stripBom(content)
        val nl = if (text.contains("\r\n")) "\r\n" else "\n"

        // ① 源方规则（M3u8Purifier 现在只剩这一档，形态判定已下线）
        val report = M3u8Purifier.Report()
        val byRule = try {
            M3u8Purifier.purify(text, baseUrl, report)
        } catch (th: Throwable) {
            AppLog.e("PurifyEngine 源规则失败: " + th.message)
            null
        }
        if (byRule != null) {
            return Result(byRule, true, report.dropped, report.reason.ifEmpty { "源方规则" })
        }

        // ② 少数派 CDN（强特征：不同域名的分片基本只能是广告）
        val byHost = removeMinorityHost(text, baseUrl, nl)
        if (byHost != null) return byHost

        // ③ 原样放行
        return Result(text, false, 0, report.reason.ifEmpty { "未命中强特征，保持原样" })
    }

    // ---------- 少数派 CDN ----------

    private fun removeMinorityHost(text: String, baseUrl: String, nl: String): Result? {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val segHosts = HashMap<Int, String>()
        val counter = LinkedHashMap<String, Int>()
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val host = hostOf(resolve(baseUrl, line)) ?: continue
            segHosts[i] = host
            counter[host] = (counter[host] ?: 0) + 1
        }
        if (counter.size <= 1) return null
        val total = segHosts.size
        if (total < 10) return null

        val minority = HashSet<String>()
        for ((host, count) in counter) {
            if (count.toDouble() / total < MINORITY_RATIO && count >= MINORITY_MIN_SEGMENTS) {
                minority.add(host)
            }
        }
        if (minority.isEmpty()) return null

        var dropCount = 0
        for (host in minority) dropCount += counter[host] ?: 0
        if (dropCount.toDouble() / total > MAX_DROP_RATIO) {
            AppLog.i("PurifyEngine 少数派删除量过大（" + dropCount + "/" + total + "），放行")
            return null
        }

        val out = StringBuilder()
        for (i in lines.indices) {
            val host = segHosts[i]
            if (host != null && minority.contains(host)) {
                // 连同它上面紧邻的 #EXTINF 一起丢掉
                val sb = out.length
                if (sb > 0) {
                    val lastNl = out.lastIndexOf("\n")
                    if (lastNl >= 0) {
                        val lastLine = out.substring(lastNl + 1).trim()
                        if (lastLine.startsWith("#EXTINF")) {
                            out.setLength(lastNl + 1)
                        }
                    }
                }
                continue
            }
            out.append(lines[i]).append(nl)
        }
        return Result(out.toString(), true, dropCount, "少数派 CDN:" + minority.joinToString(","))
    }

    private fun resolve(base: String, uri: String): String {
        if (uri.startsWith("http://") || uri.startsWith("https://")) return uri
        val dir = base.substringBefore('?').substringBeforeLast('/')
        return if (uri.startsWith("/")) {
            val host = base.substringBefore("://").let { if (it.isEmpty()) "https" else it } +
                "://" + base.substringAfter("://").substringBefore('/')
            host + uri
        } else {
            dir + "/" + uri
        }
    }

    private fun hostOf(url: String): String? {
        val idx = url.indexOf("://")
        if (idx < 0) return null
        val rest = url.substring(idx + 3)
        val end = rest.indexOf('/')
        val host = if (end > 0) rest.substring(0, end) else rest
        return host.ifEmpty { null }
    }
}
