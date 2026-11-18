package com.github.avbox.osc.util

/**
 * 净化结果的累计统计。
 *
 * 只做两件事：给设置页显示一行「最近过滤情况」，并让用户用一段时间后能看出真实过滤率。
 * 数据存在 KV 里，跨会话累计；M3u8Purifier.Report 本身不进内存，避免泄漏。
 */
object PurifyStats {

    private const val KEY_RUNS = "purify_stat_runs"
    private const val KEY_HITS = "purify_stat_hits"
    private const val KEY_DROPPED = "purify_stat_dropped"
    private const val KEY_LAST = "purify_stat_last"

    /**
     * 记一次净化。同一集因 seek / 重试可能重复上报，所以数字是趋势而不是精确口径，
     * 这里刻意不做去重——去重需要记清单指纹，成本收益不划算。
     */
    @Synchronized
    fun record(report: M3u8Purifier.Report?) {
        if (report == null) return
        try {
            val runs: Int = KV.get(KEY_RUNS, 0) + 1
            val dropped: Int = report.dropped
            val hits: Int = KV.get(KEY_HITS, 0) + if (dropped > 0) 1 else 0
            val totalDropped: Int = KV.get(KEY_DROPPED, 0) + dropped
            KV.put(KEY_RUNS, runs)
            KV.put(KEY_HITS, hits)
            KV.put(KEY_DROPPED, totalDropped)
            KV.put(KEY_LAST, describe(report))
        } catch (th: Throwable) {
            AppLog.e("PurifyStats", "record failed", th)
        }
    }

    /** 设置页副标题用的一行摘要 */
    fun summary(): String {
        return try {
            val runs: Int = KV.get(KEY_RUNS, 0)
            val hits: Int = KV.get(KEY_HITS, 0)
            val dropped: Int = KV.get(KEY_DROPPED, 0)
            val last: String = KV.get(KEY_LAST, "")
            if (runs <= 0) {
                "还没有播放记录"
            } else {
                val head = "命中 $hits/$runs 次，累计剔除 $dropped 片"
                if (last.isEmpty()) head else "$head · 上次 $last"
            }
        } catch (th: Throwable) {
            "统计不可用"
        }
    }

    @Synchronized
    fun reset() {
        try {
            KV.delete(KEY_RUNS)
            KV.delete(KEY_HITS)
            KV.delete(KEY_DROPPED)
            KV.delete(KEY_LAST)
        } catch (th: Throwable) {
            AppLog.e("PurifyStats", "reset failed", th)
        }
    }

    private fun describe(report: M3u8Purifier.Report): String {
        if (report.dropped <= 0) return "放行 " + report.total + " 片"
        val reason = report.reason.ifEmpty { "启发式" }
        return "剔 " + report.dropped + "/" + report.total + " 片（" + reason + "）"
    }
}
