package com.github.avbox.osc.player.download

import android.content.Context
import android.widget.Toast
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
import kotlinx.coroutines.withContext

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

    /** 失败记录：带上原因，下载页要如实展示，不能静默 */
    data class Failure(val index: Int, val name: String, val reason: String)

    data class Batch(
        val id: String,
        val vodId: String,
        val vodName: String,
        val sourceKey: String?,
        val flag: String?,
        val items: List<Item>,
        val done: List<Int> = emptyList(),
        val failures: List<Failure> = emptyList(),
        val sniff: List<Int> = emptyList(),
        val cursor: Int = 0,
        val state: Int = STATE_RUNNING,
    ) {
        val total: Int get() = items.size
        val finished: Int get() = done.size + failures.size + sniff.size
    }

    private val gson = Gson()
    private val storeType = object : TypeToken<List<Batch>>() {}.type
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 批量是否在推进（含「解析下一集」的空窗期）。前台服务靠它决定能不能退出 */
    @Volatile
    private var running = false

    /** 当前协程正在跑哪个批次，用来识别「旧 job 挡路」 */
    @Volatile
    private var runningBatchId: String? = null

    fun isRunning(): Boolean = running

    /** 内存里的最新批次：UI 每秒读它，避免反复反序列化 KV */
    @Volatile
    private var memBatch: Batch? = null

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
        memBatch = batch
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

    /**
     * 下载页展示用的批次：优先返回「还在进行的那一个」，全都结束了才退回最新的。
     *
     * 之前是 memBatch ?: all().firstOrNull()，而 memBatch 只是「最后一次 save 的批次」。
     * 多批次并存时，卡片显示的批次和「全部开始」实际操作的批次（activeBatch）可能不是同一个，
     * 于是出现「下载列表恢复了、批量卡片还停在已暂停，再点一次暂停+开始才恢复」。
     */
    fun current(): Batch? {
        val list = all()
        return list.firstOrNull { it.state != STATE_DONE } ?: list.firstOrNull() ?: memBatch
    }

    /** 仍在运行的批次（App 启动续跑用） */
    fun activeBatch(): Batch? = all().firstOrNull { it.state != STATE_DONE }

    // ---------- 启动 ----------

    fun start(context: Context, vod: VodInfo, indexes: List<Int>, sourceKey: String?) {
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
            Toast.makeText(app, "没有可下载的剧集", Toast.LENGTH_SHORT).show()
            return
        }
        val batch = Batch(
            id = "batch-" + System.currentTimeMillis(),
            vodId = vod.id ?: "",
            vodName = vod.name ?: "未命名",
            sourceKey = sourceKey ?: vod.sourceKey,
            flag = flag,
            items = items,
        )
        save(batch)
        AppLog.i(
            TAG + " start " + batch.vodName + " count=" + items.size +
                " flag=" + flag + " saved=" + (get(batch.id) != null),
        )

        TsDownloader.setTaskLimit(1)
        Mp4Converter.setBatchMode(true)
        running = true
        Toast.makeText(app, "已开始批量下载 " + items.size + " 集", Toast.LENGTH_SHORT).show()
        job?.cancel()
        job = scope.launch { run(app, batch.id) }
    }

    /** 清掉已完成批次卡片 */
    fun clearFinished() {
        try {
            val list = all().filterNot { it.state == STATE_DONE }
            memBatch = null
            KV.put(KEY, gson.toJson(list, storeType))
        } catch (th: Throwable) {
            AppLog.e(TAG + " clear failed: " + th.message)
        }
    }

    fun resumeRunning(context: Context) {
        val app = context.applicationContext
        val batch = activeBatch() ?: return
        if (batch.state != STATE_RUNNING) return
        // 旧协程如果还挂在别的批次上，会挡住新批次推进 —— 先撤掉再重启
        if (job?.isActive == true) {
            val runningId = runningBatchId
            if (runningId != null && runningId != batch.id) {
                AppLog.i(TAG + " resume: cancel stale job of " + runningId)
                job?.cancel()
                job = null
            } else {
                return
            }
        }
        TsDownloader.setTaskLimit(1)
        Mp4Converter.setBatchMode(true)
        running = true
        runningBatchId = batch.id
        job = scope.launch { run(app, batch.id) }
    }

    fun pauseAll(context: Context) {
        TsDownloader.setPausedAll(context, true)
        // 所有未完成批次都标记暂停：之前只改 activeBatch() 那一个，
        // 一旦存在历史遗留批次，卡片上的「已暂停」就可能改不回来
        for (b in all().filter { it.state != STATE_DONE }) save(b.copy(state = STATE_PAUSED))
    }

    fun resumeAll(context: Context) {
        TsDownloader.setPausedAll(context, false)
        // 关键：只清全局标志是不够的。单任务的暂停标记还在 TsDownloader 的 paused 集合里，
        // 状态仍是 STOPPED，于是 run() 里 awaitOutcome 立刻返回 STOPPED，
        // 命中「单集暂停 → 整批暂停」的分支，卡片又被按回「已暂停」，后续集也不再推进。
        // 所以这里要逐个把它们恢复起来。
        for (task in TsDownloader.snapshot()) {
            if (task.state == Download.STATE_STOPPED) {
                scope.launch { TsDownloader.resume(context.applicationContext, task.id) }
            }
        }
        for (b in all().filter { it.state == STATE_PAUSED }) save(b.copy(state = STATE_RUNNING))
        resumeRunning(context)
    }

    fun stop(context: Context) {
        running = false
        job?.cancel()
        job = null
        TsDownloader.setTaskLimit(0)
        Mp4Converter.setBatchMode(false)
    }

    // ---------- 主循环 ----------

    private suspend fun run(context: Context, batchId: String) {
        runningBatchId = batchId
        try {
            runLoop(context, batchId)
        } finally {
            if (runningBatchId == batchId) runningBatchId = null
            running = false
        }
    }

    private suspend fun runLoop(context: Context, batchId: String) {
        var consecutiveResolveFailures = 0
        while (true) {
            val batch = get(batchId) ?: return
            if (batch.state == STATE_DONE) break
            if (TsDownloader.isPausedAll()) {
                delay(1500)
                continue
            }
            val item = batch.items.getOrNull(batch.cursor)
            if (item == null) {
                val latest = get(batchId) ?: batch
                finish(context, latest)
                withContext(Dispatchers.Main) {
                    val head = latest.failures.firstOrNull()?.let { " · " + it.name + "：" + it.reason }
                        ?: latest.sniff.firstOrNull()?.let { " · 需网页嗅探，请在该集播放页下载" }
                        ?: ""
                    Toast.makeText(
                        context,
                        "批量下载结束：成功 " + latest.done.size +
                            " · 失败 " + latest.failures.size +
                            (if (latest.sniff.isNotEmpty()) " · 需手动 " + latest.sniff.size else "") +
                            head,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                break
            }

            val taskId = DownloadTasks.idOf(batch.vodId, batch.flag, item.index)
            val existing = TsDownloader.get(taskId)
            if (existing != null && existing.state == Download.STATE_COMPLETED) {
                markDone(batchId, item.index)
                continue
            }

            val resolved = EpisodeResolver.resolve(
                sourceKey = batch.sourceKey,
                flag = batch.flag,
                seriesUrl = item.url,
                vodId = batch.vodId,
            )
            when (resolved) {
                is EpisodeResolver.Result.Ok -> {
                    consecutiveResolveFailures = 0
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
                        markFailed(batchId, item.index, item.name, "加入下载队列失败")
                        continue
                    }
                    AppLog.i(TAG + " " + (batch.cursor + 1) + "/" + batch.total + " " + item.name)
                    when (TsDownloader.awaitOutcome(taskId)) {
                        TsDownloader.Outcome.DONE -> markDone(batchId, item.index)
                        TsDownloader.Outcome.STOPPED -> {
                            // 全部暂停：游标不动，等恢复后重新解析这一集（.part 会续传）
                            if (TsDownloader.isPausedAll()) continue
                            // 单集被暂停：整批一起暂停，**不记失败**。
                            // 之前记成「被暂停，未下完」，用户恢复下载后这条失败记录永远不会消失，
                            // 卡片就一直是「已完成 · 失败 1 · 第N集：被暂停，未下完」。
                            AppLog.i(TAG + " paused by single task, suspend batch cursor=" + batch.cursor)
                            save(get(batchId)?.copy(state = STATE_PAUSED) ?: batch.copy(state = STATE_PAUSED))
                            return
                        }

                        TsDownloader.Outcome.FAILED, TsDownloader.Outcome.UNKNOWN -> {
                            val why = TsDownloader.get(taskId)?.error ?: "下载中断"
                            markFailed(batchId, item.index, item.name, why)
                        }
                    }
                }

                EpisodeResolver.Result.NeedSniff -> {
                    AppLog.i(TAG + " need sniff " + item.name)
                    markSniff(batchId, item.index)
                }

                is EpisodeResolver.Result.Failed -> {
                    AppLog.e(TAG + " resolve failed " + item.name + " : " + resolved.reason)
                    markFailed(batchId, item.index, item.name, resolved.reason)
                    consecutiveResolveFailures++
                    if (consecutiveResolveFailures >= 2) {
                        val b = get(batchId)
                        if (b != null) finish(context, b)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "连续 2 集解析失败，已停止（" + resolved.reason + "）。该源可能不支持后台解析，可在播放页逐集点下载。",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        break
                    }
                }
            }
        }
        TsDownloader.setTaskLimit(0)
        Mp4Converter.setBatchMode(false)
    }

    private fun markDone(batchId: String, index: Int) {
        val batch = get(batchId) ?: return
        save(batch.copy(done = batch.done + index, cursor = batch.cursor + 1))
    }

    private fun markSniff(batchId: String, index: Int) {
        val batch = get(batchId) ?: return
        save(batch.copy(sniff = batch.sniff + index, cursor = batch.cursor + 1))
    }

    private fun markFailed(batchId: String, index: Int, name: String, reason: String) {
        val batch = get(batchId) ?: return
        save(
            batch.copy(
                failures = batch.failures + Failure(index, name, reason),
                cursor = batch.cursor + 1,
            ),
        )
    }

    private fun finish(context: Context, batch: Batch) {
        running = false
        save(batch.copy(state = STATE_DONE))
        // 整批结束必须把「只下不转」的闸门放开，并把这批下好的 .ts 排进封装队列。
        // 之前只改了批次状态，闸门永远停在 true，于是批量下载的文件永远不会变成 mp4。
        TsDownloader.setTaskLimit(0)
        Mp4Converter.setBatchMode(false)
        try {
            Mp4Converter.enqueueAll(context.applicationContext)
        } catch (th: Throwable) {
            AppLog.e(TAG + " enqueue convert failed: " + th.message)
        }
        AppLog.i(
            TAG + " finished " + batch.vodName +
                " done=" + batch.done.size + " failed=" + batch.failures.size + " sniff=" + batch.sniff.size,
        )
    }
}
