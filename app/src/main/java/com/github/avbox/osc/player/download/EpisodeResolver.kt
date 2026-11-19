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
    private const val TIMEOUT_MS = 20_000L

    sealed interface Result {
        data class Ok(val url: String, val headers: Map<String, String>?) : Result

        /** 该集需要网页嗅探（parse=1 / jx=1），后台无法解析 */
        data object NeedSniff : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun resolve(sourceKey: String?, flag: String?, seriesUrl: String?): Result {
        if (seriesUrl.isNullOrEmpty()) return Result.Failed("没有剧集地址")
        // 先确认这个源在当前配置里真的存在：不存在时 PlayLoader 会直接返回 null，
        // 那种情况只能干等超时，不如提前给出明确原因
        val bean = com.github.avbox.osc.api.ApiConfig.get().getSource(sourceKey)
        if (bean == null) return Result.Failed("源未找到（" + (sourceKey ?: "空") + "）")
        return withContext(Dispatchers.Main) {
            val vm = SourceViewModel()
            var collector: Job? = null
            try {
                val requested = java.util.concurrent.atomic.AtomicBoolean(false)
                val info = withTimeoutOrNull(TIMEOUT_MS) {
                    val signal = CompletableDeferred<JSONObject?>()
                    collector = launch {
                        vm.playResult.flow.collect { value ->
                            if (signal.isCompleted) return@collect
                            // 非空＝真实结果；getPlay 之后来的空值＝明确失败（源侧返回 null）
                            if (value != null) {
                                signal.complete(value)
                            } else if (requested.get()) {
                                signal.complete(null)
                            }
                        }
                    }
                    vm.getPlay(sourceKey, flag, null, seriesUrl, null)
                    requested.set(true)
                    signal.await()
                }
                if (info == null) {
                    Result.Failed("源返回空结果（该集可能已下线或无可用线路）")
                } else {
                    val parsed = parse(info)
                    AppLog.i(
                        TAG + " result=" + parsed.javaClass.simpleName +
                            " parse=" + info.optString("parse", "-") +
                            " jx=" + info.optString("jx", "-") +
                            " url=" + info.optString("url", "").take(80),
                    )
                    parsed
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

        val prefix = info.optString("playUrl", "")
        val rel = info.optString("url", "")
        val full = when {
            rel.isEmpty() -> ""
            rel.startsWith("http", ignoreCase = true) -> rel
            else -> prefix + rel
        }
        val headers = SourceHelper.extractPlayHeaders(info)?.toMap()

        // 关键：只要拼出来是媒体直链就直接用。
        // 播放链路里 parse 的缺省值是 "1"（交给 initParse 嗅探），
        // 但后台没有嗅探器，按它判会把所有直链源全判成「需嗅探」。
        if (full.startsWith("http", ignoreCase = true) && looksLikeMedia(full)) {
            return Result.Ok(full, headers)
        }
        if (info.optString("parse", "1") == "1" || info.optString("jx", "0") == "1") {
            return Result.NeedSniff
        }
        if (full.startsWith("http", ignoreCase = true)) return Result.Ok(full, headers)
        return Result.Failed(if (rel.isEmpty()) "解析结果里没有播放地址" else "播放地址不合法")
    }

    private fun looksLikeMedia(url: String): Boolean {
        val path = url.lowercase().substringBefore('?')
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".ts") ||
            path.endsWith(".mpd") || path.endsWith(".flv") || path.endsWith(".mkv")
    }
}
