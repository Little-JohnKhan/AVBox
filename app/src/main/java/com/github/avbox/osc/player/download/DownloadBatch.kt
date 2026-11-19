package com.github.avbox.osc.player.download

import android.content.Context
import androidx.media3.exoplayer.offline.Download
import com.github.avbox.osc.bean.VodInfo
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 批量下载调度器。
 *
 * 一次只下一集（任务级闸门由 [TsDownloader.setTaskLimit] 保证），
 * 每一集在「即将开始下载时」才去解析地址 —— 因为播放地址带时效签名，
 * 提前把 20 集全解析出来，后半段必然过期。
 *
 * 解析失败（含 parse=1 需要网页嗅探）不阻塞整批：记进 failed / sniff，游标继续推进。
 */
object DownloadBatch {

    private const val TAG = "DownloadBatch"
    private const val KEY = "download_batches"

    const val STATE_RUNNING = 1
    const val STATE_PAUSED = 2
    const val STATE_DONE = 3

    /** 一集的快照：重启进程后不依赖详情页，仍能继续解析下载 */
    data class Item(val index: Int, val name: String, val url: String)

    data class Batch(
        val id: String,
        val vodId: String,
        val vodName: String,
        val sourceKey: String?,
        val flag: String?,
        val items: List<Item>,
        val done: List<Int> = emptyList(),
        val failed: List<Int> = emptyList(),
        val sniff: List<Int> = emptyList(),
        val cursor: Int = 0,
        val state: Int = STATE_RUNNING,
    ) {
        val total: Int get() = items.size
        val finished: Int get() = done.size + failed.size + sniff.size
    }

    private val gson = Gson()
    private val storeType = object : TypeToken<List<Batch>>() {}.type
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // ---------- 持久化 ----------

    @Synchronized
    fun all(): List<Batch> = try {
        val raw: String = KV.get(KEY, "")
        if (raw.isEmpty()) emptyList() else gson.fromJson(raw, storeType) ?: emptyList()
    } catch (th: Throwable) {
        AppLog.e(TAG + " read failed: " + th.message)
        emptyList()
    }

    @Synchronized
    private fun save(batch: Batch) {
        try {
            val list = all().filterNot { it.id == batch.id }.toMutableList()
            list.add(0, batch)
            KV.put(KEY, gson.toJson(list, storeType))
        } catch (th: Throwable) {
            AppLog.e(TAG + " save failed: " + th.message)
        }
    }

    @Synchronized
    fun get(id: String): Batch? = all().firstOrNull { it.id == id }

    /** 正在进行的批次（同一时间只允许一个） */
    fun current(): Batch? = all().firstOrNull { it.state != STATE_DONE }

    // ---------- 启动 ----------

    fun start(context: Context, vod: VodInfo, indexes: List<Int>) {
        val app = context.applicationContext
        val flag = vod.playFlag
        val series = vod.seriesMap?.get(flag).orEmpty()
        val items = indexes.distinct().sorted()
            .mapNotNull { index ->
                val ep = series.getOrNull(index) ?: return@mapNotNull null
                val url = ep.url ?: return@mapNotNull null
                if (url.isEmpty()) return@mapNotNull null
                Item(index, ep.name ?: ("第 " + (index + 1) + " 集"), url)
            }
        if (items.isEmpty()) {
            AppLog.e(TAG + " nothing to download")
            return
        }
        val batch = Batch(
            id = "batch-" + System.currentTimeMillis(),
            vodId = vod.id ?: "",
            vodName = vod.name ?: "未命名",
            sourceKey = vod.sourceKey,
            flag = flag,
            items = items,
        )
        save(batch)
        AppLog.i(TAG + " start " + batch.vodName + " count=" + items.size + " flag=" + flag)

        TsDownloader.setTaskLimit(1)
        Mp4Converter.setBatchMode(true)
        job?.cancel()
        job = scope.launch { run(app, batch.id) }
    }

    fun resumeRunning(context: Context) {
        val app = context.applicationContext
        val batch = current() ?: return
        if (batch.state != STATE_RUNNING) return
        if (job?.isActive == true) return
        TsDownloader.setTaskLimit(1)
        Mp4Converter.setBatchMode(true)
        job = scope.launch { run(app, batch.id) }
    }

    fun pauseAll(context: Context) {
        TsDownloader.setPausedAll(context, true)
        val batch = current() ?: return
        save(batch.copy(state = STATE_PAUSED))
    }

    fun resumeAll(context: Context) {
        TsDownloader.setPausedAll(context, false)
        val batch = current() ?: return
        if (batch.state == STATE_PAUSED) save(batch.copy(state = STATE_RUNNING))
        resumeRunning(context)
    }

    fun stop(context: Context) {
        job?.cancel()
        job = null
        TsDownloader.setTaskLimit(0)
        Mp4Converter.setBatchMode(false)
    }

    // ---------- 主循环 ----------

    private suspend fun run(context: Context, batchId: String) {
        while (true) {
            val batch = get(batchId) ?: return
            if (batch.state == STATE_DONE) break
            if (TsDownloader.isPausedAll()) {
                delay(1500)
                continue
            }
            val item = batch.items.getOrNull(batch.cursor)
            if (item == null) {
                finish(batch)
                break
            }

            val taskId = DownloadTasks.idOf(batch.vodId, batch.flag, item.index)
            val existing = TsDownloader.get(taskId)
            if (existing != null && existing.state == Download.STATE_COMPLETED) {
                advance(batchId, item.index, landed = true)
                continue
            }

            val resolved = EpisodeResolver.resolve(batch.sourceKey, batch.flag, item.url)
            when (resolved) {
                is EpisodeResolver.Result.Ok -> {
                    val downloadItem = DownloadItem(
                        id = taskId,
                        vodId = batch.vodId,
                        vodName = batch.vodName,
                        flag = batch.flag,
                        episodeIndex = item.index,
                        episodeName = item.name,
                        playUrl = resolved.url,
                        headers = resolved.headers,
                        poster = null,
                        createdAt = System.currentTimeMillis(),
                    )
                    val enqueued = try {
                        TsDownloader.enqueue(context, downloadItem)
                        true
                    } catch (th: Throwable) {
                        AppLog.e(TAG + " enqueue failed " + item.name + " : " + th.message)
                        false
                    }
                    if (!enqueued) {
                        advance(batchId, item.index, landed = false)
                        continue
                    }
                    AppLog.i(TAG + " " + (batch.cursor + 1) + "/" + batch.total + " " + item.name)
                    when (TsDownloader.awaitOutcome(taskId)) {
                        TsDownloader.Outcome.DONE -> advance(batchId, item.index, landed = true)
                        TsDownloader.Outcome.STOPPED -> {
                            // 全部暂停：游标不动，等恢复后重新解析这一集（.part 会续传）
                            if (TsDownloader.isPausedAll()) continue
                            advance(batchId, item.index, landed = false)
                        }
                        TsDownloader.Outcome.FAILED -> advance(batchId, item.index, landed = false)
                        TsDownloader.Outcome.UNKNOWN -> advance(batchId, item.index, landed = false)
                    }
                }

                EpisodeResolver.Result.NeedSniff -> {
                    AppLog.i(TAG + " need sniff " + item.name)
                    advance(batchId, item.index, sniff = true)
                }

                is EpisodeResolver.Result.Failed -> {
                    AppLog.e(TAG + " resolve failed " + item.name + " : " + resolved.reason)
                    advance(batchId, item.index, landed = false)
                }
            }
        }
        TsDownloader.setTaskLimit(0)
        Mp4Converter.setBatchMode(false)
    }

    private fun advance(
        batchId: String,
        index: Int,
        landed: Boolean = false,
        sniff: Boolean = false,
    ) {
        val batch = get(batchId) ?: return
        val done = if (landed) batch.done + index else batch.done
        val failed = if (!landed && !sniff) batch.failed + index else batch.failed
        val sniffed = if (sniff) batch.sniff + index else batch.sniff
        save(batch.copy(done = done, failed = failed, sniff = sniffed, cursor = batch.cursor + 1))
    }

    private fun finish(batch: Batch) {
        save(batch.copy(state = STATE_DONE))
        AppLog.i(
            TAG + " finished " + batch.vodName +
                " done=" + batch.done.size + " failed=" + batch.failed.size + " sniff=" + batch.sniff.size,
        )
    }
}
