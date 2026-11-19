package com.github.avbox.osc.player.download

import com.github.avbox.osc.sourcedata.SourceHelper
import com.github.avbox.osc.sourcedata.SourceViewModel
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 单集地址解析器：给批量下载用。
 *
 * 单集下载之所以能工作，是因为用户先点了播放，播放器把真实 m3u8 写进了
 * [CurrentPlayback.url]。批量下载没人去逐集点播放，所以这里模拟一次解析：
 * 新建一个临时的 [SourceViewModel]（每次都是全新实例，playResult 里不会残留
 * 上一次的结果），调 getPlay 后等第一条非空结果。
 *
 * 结果里的字段和播放链路一致：`playUrl` 是前缀，`url` 是（可能相对的）地址，
 * `header`/`headers` 是请求头，`parse=1` 表示还需要网页嗅探，后台做不到。
 */
object EpisodeResolver {

    private const val TAG = "EpisodeResolver"
    private const val TIMEOUT_MS = 30_000L

    sealed interface Result {
        data class Ok(val url: String, val headers: Map<String, String>?) : Result

        /** 该集需要网页嗅探（parse=1 / jx=1），后台无法解析 */
        data object NeedSniff : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun resolve(sourceKey: String?, flag: String?, seriesUrl: String?): Result {
        if (seriesUrl.isNullOrEmpty()) return Result.Failed("没有剧集地址")
        return withContext(Dispatchers.Main) {
            val vm = SourceViewModel()
            var collector: Job? = null
            try {
                val info = withTimeoutOrNull(TIMEOUT_MS) {
                    val signal = CompletableDeferred<JSONObject?>()
                    collector = launch {
                        vm.playResult.flow.collect { value ->
                            if (value != null && !signal.isCompleted) signal.complete(value)
                        }
                    }
                    vm.getPlay(sourceKey, flag, null, seriesUrl, null)
                    signal.await()
                }
                if (info == null) {
                    Result.Failed("解析超时")
                } else {
                    parse(info)
                }
            } catch (th: Throwable) {
                AppLog.e(TAG + " resolve failed: " + th.message)
                Result.Failed(th.message ?: "解析失败")
            } finally {
                collector?.cancel()
            }
        }
    }

    private fun parse(info: JSONObject): Result {
        val msg = info.optString("msg", "")
        if (msg.isNotEmpty()) return Result.Failed(msg)
        if (info.optString("parse", "1") == "1" || info.optString("jx", "0") == "1") {
            return Result.NeedSniff
        }
        val prefix = info.optString("playUrl", "")
        val rel = info.optString("url", "")
        if (rel.isEmpty()) return Result.Failed("解析结果里没有播放地址")
        val full = if (rel.startsWith("http", ignoreCase = true)) rel else prefix + rel
        if (!full.startsWith("http", ignoreCase = true)) return Result.Failed("播放地址不合法")
        return Result.Ok(full, SourceHelper.extractPlayHeaders(info)?.toMap())
    }
}
