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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private const val DEBUG_LOG = "download-debug.log"

    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun debug(line: String) {
        AppLog.i(TAG + " " + line)
        try {
            logExecutor.execute {
                try {
                    val file = File(TsDownloader.publicDir(), DEBUG_LOG)
                    if (file.length() > 1024 * 1024) file.delete()
                    val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                    file.appendText(ts + " " + line + "\n")
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }
    private const val TIMEOUT_MS = 12_000L

    /** 用来在 spider 返回里找「源做过过滤」的线索词 */
    private val FILTER_HINTS = listOf("过滤", "广告", "去除", "屏蔽", "filter", "purify", "removeAds")

    sealed interface Result {
        data class Ok(val url: String, val headers: Map<String, String>?) : Result

        /** 该集需要网页嗅探（parse=1 / jx=1），后台无法解析 */
        data object NeedSniff : Result

        data class Failed(val reason: String) : Result
    }

    suspend fun resolve(
        sourceKey: String?,
        flag: String?,
        seriesUrl: String?,
        vodId: String? = null,
    ): Result {
        if (seriesUrl.isNullOrEmpty()) return Result.Failed("没有剧集地址")
        debug(
            "resolve src=" + (sourceKey ?: "-") + " flag=" + (flag ?: "-") +
                " vod=" + (vodId ?: "-") + " url=" + seriesUrl.take(160),
        )

        // 1) 剧集地址本身就是可下载直链（不少源的 series url 直接就是 m3u8/mp4），
        //    这种情况完全不需要过解析器，直接用最省事也最稳
        val raw = seriesUrl.substringBefore('#')
        if (raw.startsWith("http", ignoreCase = true) && looksLikeMedia(raw)) {
            // 直链也要先问一遍 spider：源侧的广告过滤（会弹 toast 的那种）就发生在
            // spider.playerContent 里面，播放链路正是这么拿到干净地址的。
            // 这里若直接返回原始直链，就会绕过源过滤，下到带广告的脏清单——
            // 结果就是「在线播放没广告、下载下来却被我们自己的规则削掉片尾」。
            val viaSpider = resolveViaSpider(sourceKey, flag, seriesUrl)
            if (viaSpider is Result.Ok) {
                debug("  direct 直链，但 spider 返回了过滤后地址，优先使用")
                return viaSpider
            }
            debug("  direct 直链直接用（spider 无结果，回落原始地址）")
            return Result.Ok(raw, null)
        }

        // 2) 先确认这个源在当前配置里真的存在：不存在时 PlayLoader 会直接返回 null，
        //    那种情况只能干等超时，不如提前给出明确原因
        val bean = com.github.avbox.osc.api.ApiConfig.get().getSource(sourceKey)
        if (bean == null) return Result.Failed("源未找到（" + (sourceKey ?: "空") + "）")

        // 3) 走精灵解析；空结果多半是 spider 内部没有这部片的上下文，
        //    补一次 detailContent（等于详情页做的事）再试一遍
        val first = resolveViaSpider(sourceKey, flag, seriesUrl)
        debug("  spider -> " + describe(first))
        if (first is Result.Ok || first is Result.NeedSniff) return first
        if (vodId.isNullOrEmpty()) return first
        AppLog.i(TAG + " retry after warmUp vod=" + vodId)
        warmUp(sourceKey, vodId)
        val second = resolveViaSpider(sourceKey, flag, seriesUrl)
        debug("  after warmUp -> " + describe(second))
        return second
    }

    /** 直接调一次 spider，把原始返回（或异常）写进日志，用来判断卡在哪 */
    private suspend fun probeRaw(sourceKey: String?, flag: String?, url: String) {
        try {
            withContext(Dispatchers.IO) {
                val bean = com.github.avbox.osc.api.ApiConfig.get().getSource(sourceKey)
                if (bean == null) {
                    debug("  raw -> 源不存在")
                    return@withContext
                }
                val payload = try {
                    val sp = com.github.avbox.osc.api.ApiConfig.get().getCSP(bean)
                    sp.playerContent(flag, url, com.github.avbox.osc.api.ApiConfig.get().vipParseFlags)
                } catch (th: Throwable) {
                    "EX:" + th.javaClass.simpleName + ":" + th.message
                }
                debug("  raw playerContent -> " + (payload ?: "null").take(220))
            }
        } catch (th: Throwable) {
            debug("  raw probe failed " + th.message)
        }
    }

    private fun describe(result: Result): String = when (result) {
        is Result.Ok -> "Ok url=" + result.url.take(120)
        is Result.NeedSniff -> "NeedSniff"
        is Result.Failed -> "Failed " + result.reason
    }

    /** 调一次 getDetail，让 spider 建立这部剧的上下文（部分源的 playerContent 依赖它） */
    private suspend fun warmUp(sourceKey: String?, vodId: String) {
        if (sourceKey.isNullOrEmpty()) return
        withContext(Dispatchers.Main) {
            val vm = SourceViewModel()
            try {
                withTimeoutOrNull(15_000) {
                    val signal = CompletableDeferred<Boolean>()
                    val job = launch {
                        vm.detailResult.flow.collect { value ->
                            if (value != null && !signal.isCompleted) signal.complete(true)
                        }
                    }
                    vm.getDetail(sourceKey, vodId)
                    val ok = signal.await()
                    job.cancel()
                    debug("  warmUp detail -> " + ok)
                }
            } catch (th: Throwable) {
                AppLog.e(TAG + " warmUp failed: " + th.message)
            }
        }
    }

    /**
     * 直接调精灵：不走 SourceViewModel / PlayLoader 那条播放链路。
     *
     * 播放链路里对 url 做 push 包装、还给精灵调用套了很短的超时，
     * 后台批量不需要那些，直接调 playerContent(flag, url, vipFlags) 又快又准。
     */
    private suspend fun resolveViaSpider(sourceKey: String?, flag: String?, seriesUrl: String): Result {
        val bean = com.github.avbox.osc.api.ApiConfig.get().getSource(sourceKey)
            ?: return Result.Failed("源未找到（" + (sourceKey ?: "空") + "）")
        return withContext(Dispatchers.IO) {
            val payload = try {
                val sp = com.github.avbox.osc.api.ApiConfig.get().getCSP(bean)
                sp.playerContent(flag, seriesUrl, com.github.avbox.osc.api.ApiConfig.get().vipParseFlags)
            } catch (th: Throwable) {
                debug("  spider EX: " + th.javaClass.simpleName + ":" + th.message)
                return@withContext Result.Failed("精灵异常：" + (th.message ?: th.javaClass.simpleName))
            }
            if (payload.isNullOrEmpty()) {
                return@withContext Result.Failed("源返回空结果（该集可能已下线或无可用线路）")
            }
            val info = try {
                JSONObject(payload)
            } catch (th: Throwable) {
                return@withContext Result.Failed("源返回内容不是 JSON")
            }
            // 探针：源的过滤痕迹可能藏在返回 JSON 里（msg / ads / filter 之类字段）。
            // 有的话就能当「源已过滤」的信号用，比 toast 可靠（toast 在 jar 里拦不到）。
            try {
                val keys = info.keys().asSequence().toList()
                val payloadHits = FILTER_HINTS.filter { payload.contains(it, ignoreCase = true) }
                debug(
                    "  spider keys=" + keys +
                        (if (payloadHits.isEmpty()) "" else " ★含过滤线索=" + payloadHits) +
                        " len=" + payload.length,
                )
                if (payloadHits.isNotEmpty() || keys.any { it.equals("ads", true) || it.equals("filter", true) }) {
                    debug("  ★ 源返回原文: " + payload.take(400))
                }
            } catch (ignored: Throwable) {
            }
            val parsed = parse(info)
            debug(
                "  spider ok parse=" + info.optString("parse", "-") +
                    " jx=" + info.optString("jx", "-") +
                    " url=" + info.optString("url", "").take(80) +
                    " -> " + describe(parsed),
            )
            parsed
        }
    }

    private suspend fun parse(info: JSONObject): Result {
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
            // 需要嗅探：先交给配置里的 HTTP 解析接口（type=1）试一次，
            // 解析不出来才真的算「只能在播放页下载」
            val sniffUrl = if (full.startsWith("http", ignoreCase = true)) full else ""
            if (sniffUrl.isNotEmpty()) {
                // 先按「解析页自带服务端接口」的方式换地址（hhjx 这类站点）
                val page = PageSniffer.sniff(sniffUrl)
                if (page != null) {
                    debug("  pageSniff 解出真实地址 " + page.url.take(110))
                    return Result.Ok(page.url, page.headers)
                }
                // 再退到配置里的 HTTP 解析接口
                val hit = JxParser.parse(sniffUrl)
                if (hit != null) {
                    debug("  jx 解出真实地址 " + hit.url.take(100))
                    return Result.Ok(hit.url, hit.headers)
                }
                debug("  pageSniff/jx 均未命中，需要播放页嗅探")
            }
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
