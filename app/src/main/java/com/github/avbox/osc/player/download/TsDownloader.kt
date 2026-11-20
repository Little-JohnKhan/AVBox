package com.github.avbox.osc.player.download

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import androidx.media3.exoplayer.offline.Download
import com.github.avbox.osc.player.engine.PurifyProxy
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.M3u8Purifier
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条自制下载任务的完整状态。
 *
 * 和 media3 那套 {缓存 key → 分片块} 不同，这里的分片按顺序直接追加进一个 .ts 文件，
 * 下完就是能播放的成品。所以 state 只借 media3 的常量值让 UI 判断逻辑保持兼容。
 */
data class TsTask(
    val id: String,
    val vodId: String,
    val vodName: String,
    val flag: String?,
    val episodeIndex: Int,
    val episodeName: String,
    val playUrl: String,
    val headers: Map<String, String>? = null,
    val poster: String? = null,
    val state: Int = Download.STATE_QUEUED,
    val doneSegments: Int = 0,
    val totalSegments: Int = 0,
    val bytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val createdAt: Long = 0L,
) {
    val percent: Float
        get() = when {
            state == Download.STATE_COMPLETED -> 100f
            totalBytes > 0L && bytes > 0L -> (bytes.toFloat() * 100f / totalBytes.toFloat()).coerceIn(0f, 100f)
            totalSegments > 0 -> doneSegments.toFloat() * 100f / totalSegments.toFloat()
            else -> 0f
        }

    fun toItem(): DownloadItem = DownloadItem(
        id = id,
        vodId = vodId,
        vodName = vodName,
        flag = flag,
        episodeIndex = episodeIndex,
        episodeName = episodeName,
        playUrl = playUrl,
        headers = headers,
        poster = poster,
        createdAt = createdAt,
    )
}

/** 任务持久化（KV + JSON，不碰数据库） */
object TsTaskStore {

    private const val KEY = "ts_download_tasks"

    private val gson = Gson()
    private val type = object : TypeToken<List<TsTask>>() {}.type

    @Synchronized
    fun all(): List<TsTask> {
        return try {
            val raw: String = KV.get(KEY, "")
            if (raw.isEmpty()) emptyList() else gson.fromJson(raw, type) ?: emptyList()
        } catch (th: Throwable) {
            AppLog.e("TsTaskStore", "read failed", th)
            emptyList()
        }
    }

    @Synchronized
    fun put(task: TsTask) {
        try {
            val list = all().filterNot { it.id == task.id }.toMutableList()
            list.add(0, task)
            KV.put(KEY, gson.toJson(list, type))
        } catch (th: Throwable) {
            AppLog.e("TsTaskStore", "put failed", th)
        }
    }

    @Synchronized
    fun get(id: String): TsTask? = all().firstOrNull { it.id == id }

    @Synchronized
    fun remove(id: String) {
        try {
            val list = all().filterNot { it.id == id }
            KV.put(KEY, gson.toJson(list, type))
        } catch (th: Throwable) {
            AppLog.e("TsTaskStore", "remove failed", th)
        }
    }
}

/**
 * 自制 HLS 下载器：拉清单 → 逐片 GET → 顺序 append 到一个 .ts 文件。
 *
 * 为什么不继续用 media3 DownloadManager：它把内容按 URL 分 key 塞进 SimpleCache，
 * 而我们的清单来自本地净化代理、会被改写，下载侧和播放侧算出的 key 对不上，
 * 表现为「下载了 134MB 但播放转圈、导出只有清单」。这里根本不碰 cache，
 * 结束后产物是一个普通视频文件，App 内直接播本地、外部播放器也能直接播。
 */
object TsDownloader {

    private const val TAG = "TsDownloader"

    /**
     * 同时下载的分片数。源站多为单连接限速，并发才能把总带宽吃满。
     * 8 是实测比较稳的值：再往上单连接收益递减，也更容易被源站限流。
     */
    private const val CONCURRENCY = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    /** 正在跑的下载协程登记表：保证同一集在任何时刻只有一个协程在写文件 */
    private val running: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val mem = ConcurrentHashMap<String, TsTask>()
    private val paused = Collections.synchronizedSet(HashSet<String>())
    private val persistAt = ConcurrentHashMap<String, Long>()

    /** 因「全部暂停」而停下的任务，恢复时只拉起这些，不动用户手动暂停的 */
    private val stoppedByGlobal = Collections.synchronizedSet(HashSet<String>())

    /** 任务结束信号：批量下载等这一集的最终结果 */
    private val doneSignals = ConcurrentHashMap<String, CompletableDeferred<Outcome>>()

    @Volatile
    private var globalPaused = false

    @Volatile
    private var taskLimit = Int.MAX_VALUE

    private val activeTasks = AtomicInteger(0)

    @Volatile
    private var loaded = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            // 默认 maxRequestsPerHost = 5，会把 8 路并发卡回 5 路
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                },
            )
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .build()
    }

    // ---------- 存储与查询 ----------

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        for (raw in TsTaskStore.all()) {
            // 进程重启后不会有协程还在跑，把「进行中」统一降级成已暂停，让用户自己继续
            val state = if (raw.state == Download.STATE_DOWNLOADING ||
                raw.state == Download.STATE_QUEUED ||
                raw.state == Download.STATE_RESTARTING
            ) {
                Download.STATE_STOPPED
            } else {
                raw.state
            }
            val task = if (state != raw.state) raw.copy(state = state) else raw
            mem[task.id] = task
            if (state != raw.state) TsTaskStore.put(task)
        }
        loaded = true
        AppLog.i(TAG + " loaded tasks=" + mem.size)
    }

    fun snapshot(): List<TsTask> {
        ensureLoaded()
        return mem.values.sortedByDescending { it.createdAt }
    }

    fun get(id: String): TsTask? {
        ensureLoaded()
        return mem[id]
    }

    /** 下载中的临时文件：Download/AVBox/<名字>.ts.part */
    fun partFileOf(task: TsTask): File = File(publicDir(), safeName(task) + ".ts.part")

    /** 单文件直链（mp4/mkv/flv…）的扩展名，非直链返回空串 */
    private fun sourceExt(url: String): String {
        val path = url.lowercase().substringBefore('?')
        return when {
            path.endsWith(".mp4") -> ".mp4"
            path.endsWith(".mkv") -> ".mkv"
            path.endsWith(".flv") -> ".flv"
            path.endsWith(".m4v") -> ".m4v"
            path.endsWith(".mov") -> ".mov"
            path.endsWith(".webm") -> ".webm"
            else -> ""
        }
    }

    /** 成品（转换前）：Download/AVBox/<名字>.ts */
    fun tsFileOf(task: TsTask): File = File(publicDir(), safeName(task) + ".ts")

    /** 成品：优先返回转换后的 mp4，没有则回落到 .ts，外部播放器可直接打开 */
    fun publicFileOf(task: TsTask): File {
        val ts = tsFileOf(task)
        if (ts.exists()) return ts
        val mp4 = File(publicDir(), safeName(task) + ".mp4")
        if (mp4.exists()) return mp4
        return ts
    }

    fun publicDir(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AVBox",
        )
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 兼容旧调用点：直接指向成品文件 */
    fun fileOf(ctx: Context, id: String): File {
        val task = get(id)
        return if (task != null) publicFileOf(task) else File(publicDir(), id + ".ts")
    }

    fun publicFileOf(ctx: Context, id: String): File? {
        val task = get(id) ?: return null
        return publicFileOf(task)
    }

    // ---------- 对外操作 ----------

    fun enqueue(ctx: Context, item: DownloadItem) {
        val app = ctx.applicationContext
        ensureLoaded()
        // 同一集已经在下载中：忽略重复入队，免得把进度重置回 0
        if (running.contains(item.id)) {
            AppLog.i(TAG + " enqueue ignored (already running) id=" + item.id)
            return
        }
        val old = mem[item.id]
        val task = TsTask(
            id = item.id,
            vodId = item.vodId,
            vodName = item.vodName,
            flag = item.flag,
            episodeIndex = item.episodeIndex,
            episodeName = item.episodeName,
            playUrl = item.playUrl,
            headers = item.headers,
            poster = item.poster,
            state = Download.STATE_QUEUED,
            createdAt = if (old != null) old.createdAt else System.currentTimeMillis(),
        )
        paused.remove(item.id)
        save(app, task, force = true)
        DownloadNotifications.refresh(app)
        start(app, task)
        AppLog.i(TAG + " enqueue id=" + task.id + " name=" + task.vodName + " ep=" + task.episodeName)
    }

    fun pause(ctx: Context, id: String) {
        paused.add(id)
        update(ctx, id) { t ->
            if (t.state == Download.STATE_QUEUED || t.state == Download.STATE_DOWNLOADING) {
                t.copy(state = Download.STATE_STOPPED)
            } else {
                t
            }
        }
    }

    fun resume(ctx: Context, id: String) {
        ensureLoaded()
        val task = mem[id] ?: return
        if (task.state == Download.STATE_COMPLETED) return
        paused.remove(id)
        val queued = task.copy(state = Download.STATE_QUEUED, error = null)
        save(ctx.applicationContext, queued, force = true)
        DownloadNotifications.refresh(ctx.applicationContext)
        start(ctx.applicationContext, queued)
    }

    fun remove(ctx: Context, id: String) {
        val task = mem[id]
        paused.add(id)
        jobs.remove(id)?.cancel()
        mem.remove(id)
        TsTaskStore.remove(id)
        if (task != null) {
            try {
                partFileOf(task).delete()
            } catch (ignored: Throwable) {
            }
            try {
                tsFileOf(task).delete()
            } catch (ignored: Throwable) {
            }
            try {
                File(publicDir(), safeName(task) + ".mp4").delete()
            } catch (ignored: Throwable) {
            }
            try {
                File(publicDir(), safeName(task) + ".ts.fixtmp").delete()
            } catch (ignored: Throwable) {
            }
            DownloadLibrary.markRemoved(task.playUrl)
        }
        AppLog.i(TAG + " removed id=" + id)
    }

    // ---------- 下载主流程 ----------

    private fun start(ctx: Context, task: TsTask) {
        // 原子占位：抢到就开始；抢不到说明这一集已有协程在写文件，
        // 稍等它退出（比如刚被取消）再补一次，避免任务卡在「等待中」
        if (running.add(task.id)) {
            launchWithSlot(ctx, task)
            return
        }
        scope.launch {
            repeat(50) {
                delay(100)
                if (running.add(task.id)) {
                    launchWithSlot(ctx, task)
                    return@launch
                }
            }
            AppLog.e(TAG + " start timeout id=" + task.id)
        }
    }

    private fun launchWithSlot(ctx: Context, task: TsTask) {
        if (mem[task.id] == null) {
            running.remove(task.id)
            return
        }
        val job = scope.launch {
            var acquired = false
            try {
                // 任务级闸门：批量下载时限制同时下载的集数（分片并发不受影响）
                while (activeTasks.get() >= taskLimit) delay(300)
                activeTasks.incrementAndGet()
                acquired = true
                run(ctx, task)
                signalDone(task.id, outcomeOf(task.id))
            } catch (th: CancellationException) {
                signalDone(task.id, Outcome.UNKNOWN)
                throw th
            } catch (th: Throwable) {
                AppLog.e(TAG, "failed id=" + task.id, th)
                update(ctx, task.id, force = true) { t ->
                    t.copy(state = Download.STATE_FAILED, error = th.message ?: "下载失败")
                }
                signalDone(task.id, Outcome.FAILED)
            } finally {
                if (acquired) activeTasks.decrementAndGet()
                running.remove(task.id)
                DownloadNotifications.refresh(ctx)
            }
        }
        jobs[task.id] = job
        // 只删自己那条登记，避免把后来者（新协程）的引用误删
        job.invokeOnCompletion {
            jobs.remove(task.id, job)
        }
    }

    /** 一集下载的最终结局 */
    enum class Outcome { DONE, FAILED, STOPPED, UNKNOWN }

    private fun signalDone(id: String, outcome: Outcome) {
        doneSignals[id]?.complete(outcome)
    }

    private fun outcomeOf(id: String): Outcome = when (mem[id]?.state) {
        Download.STATE_COMPLETED -> Outcome.DONE
        Download.STATE_FAILED -> Outcome.FAILED
        Download.STATE_STOPPED -> Outcome.STOPPED
        else -> Outcome.UNKNOWN
    }

    /** 批量下载用：等这一集结束，并区分「下完了 / 失败 / 被暂停」 */
    suspend fun awaitOutcome(id: String, timeoutMs: Long = 6 * 3600_000L): Outcome {
        when (mem[id]?.state) {
            Download.STATE_COMPLETED -> return Outcome.DONE
            Download.STATE_FAILED -> return Outcome.FAILED
            Download.STATE_STOPPED -> return Outcome.STOPPED
            null -> return Outcome.UNKNOWN
        }
        val signal = doneSignals.getOrPut(id) { CompletableDeferred() }
        return withTimeoutOrNull(timeoutMs) { signal.await() } ?: Outcome.UNKNOWN
    }

    /**
     * 全部暂停 / 全部开始。
     *
     * 全部暂停：所有正在跑的协程在下一次分片检查时退出（保留 .part），
     * 批次调度器也会停在当前集不再推进。
     * 全部开始：只重新拉起「因全部暂停而停下」的任务，用户手动暂停的保持不变。
     */
    fun setPausedAll(ctx: Context, pausedAll: Boolean) {
        globalPaused = pausedAll
        val app = ctx.applicationContext
        if (pausedAll) {
            AppLog.i(TAG + " pause all")
            return
        }
        AppLog.i(TAG + " resume all")
        scope.launch {
            val ids = stoppedByGlobal.toList()
            stoppedByGlobal.clear()
            for (taskId in ids) {
                resume(app, taskId)
            }
        }
    }

    fun isPausedAll(): Boolean = globalPaused

    /** 同时下载的任务数上限：批量下载时设为 1，避免多集并行把带宽和连接数打满 */
    fun setTaskLimit(limit: Int) {
        taskLimit = if (limit <= 0) Int.MAX_VALUE else limit
    }

    /**
     * 失败/手动重试：从断点续传。
     *
     * 先 cancel 并 join 旧协程，确认它已经松手再重启，避免两个协程同时写同一个 .part；
     * 不删 part 文件，[run] 会按 doneSegments 接着往下写。
     */
    suspend fun restart(ctx: Context, id: String) {
        ensureLoaded()
        paused.remove(id)
        val old = jobs.remove(id)
        if (old != null) {
            old.cancel()
            old.join()
        }
        val cur = mem[id] ?: return
        if (cur.state == Download.STATE_COMPLETED) return
        val queued = cur.copy(state = Download.STATE_QUEUED, error = null)
        save(ctx.applicationContext, queued, force = true)
        DownloadNotifications.refresh(ctx.applicationContext)
        start(ctx.applicationContext, queued)
    }

    private suspend fun run(ctx: Context, task: TsTask) {
        val id = task.id
        update(ctx, id, force = true) { it.copy(state = Download.STATE_DOWNLOADING, error = null) }

        // 直链文件（mp4/mkv…）没有清单，整段下载即可
        val ext = sourceExt(task.playUrl)
        if (ext.isNotEmpty()) {
            runSingleFile(ctx, task, ext, id)
            return
        }

        val segments = fetchSegments(task)
        if (segments.isEmpty()) throw IOException("清单里没有分片")
        AppLog.i(TAG + " segments=" + segments.size + " id=" + id)

        val dir = publicDir()
        if (!dir.canWrite()) {
            throw IOException("下载目录不可写，请在系统设置里允许 AVBox 访问「所有文件」")
        }
        val part = partFileOf(task)
        // 注意：这里必须用 tsFileOf，不能用 publicFileOf。
        // publicFileOf 的语义是「优先 .ts、没有则回落到 .mp4」，给播放/打开用；
        // 一旦拿它当写盘目标，重复下载同一集时（ts 已被封装删掉）会把 target 解析成
        // 已封装好的 .mp4，收尾的 delete + rename 就把成品删了、再把 ts 内容改名成 .mp4。
        val target = tsFileOf(task)
        val prev = mem[id]
        var startIndex = 0
        if (prev != null && prev.totalSegments == segments.size &&
            prev.doneSegments > 0 && prev.doneSegments < segments.size &&
            prev.bytes > 0L && part.exists() && part.length() >= prev.bytes
        ) {
            // 落盘节流后，文件可能比记录多写了几片：截回记录位置再续，保证分片边界对齐
            if (part.length() > prev.bytes && !truncateTo(part, prev.bytes)) {
                part.delete()
                AppLog.e(TAG + " truncate failed, restart from 0")
            } else {
                startIndex = prev.doneSegments
                AppLog.i(TAG + " resume " + startIndex + "/" + segments.size + " bytes=" + prev.bytes)
            }
        } else if (part.exists()) {
            part.delete()
        }
        var written = if (part.exists()) part.length() else 0L
        update(ctx, id, force = true) {
            it.copy(totalSegments = segments.size, doneSegments = startIndex, bytes = written)
        }

        var done = startIndex
        var pausedNow = false
        FileOutputStream(part, startIndex > 0).use { os ->
            coroutineScope {
                // 滑动窗口：始终让 CONCURRENCY 个请求在飞，同时预取一批排队，
                // 某一片慢了只堵住自己，不再拖整批
                val permits = Semaphore(CONCURRENCY)
                val inFlight = ArrayList<Deferred<ByteArray>>()
                var next = startIndex

                fun launchOne() {
                    if (next >= segments.size) return
                    val i = next++
                    inFlight.add(
                        async(Dispatchers.IO) {
                            permits.withPermit { fetchSegment(segments[i], task.headers) }
                        },
                    )
                }

                // 预取窗口比并发数略大：一片写完马上能补位，避免收尾时并发掉到 1
                repeat(minOf(CONCURRENCY + 4, segments.size - startIndex)) { launchOne() }

                while (inFlight.isNotEmpty()) {
                    if (paused.contains(id) || globalPaused) {
                        if (globalPaused && !paused.contains(id)) stoppedByGlobal.add(id)
                        pausedNow = true
                        break
                    }
                    val bytes = inFlight.removeAt(0).await()
                    os.write(bytes)
                    os.flush()
                    written += bytes.size
                    done++
                    update(ctx, id) { it.copy(doneSegments = done, bytes = written) }
                    launchOne()
                }
            }
            if (pausedNow) {
                os.flush()
                update(ctx, id, force = true) { it.copy(state = Download.STATE_STOPPED) }
                AppLog.i(TAG + " paused at " + done + "/" + segments.size)
                return
            }
        }

        // 收尾：.part 改名成 .ts，公开目录里直接就是一个能播的成品文件
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        DownloadLibrary.markDone(task.playUrl, target.absolutePath)
        update(ctx, id, force = true) { t ->
            t.copy(
                state = Download.STATE_COMPLETED,
                bytes = target.length(),
                totalBytes = target.length(),
                doneSegments = segments.size,
                totalSegments = segments.size,
                error = null,
            )
        }
        AppLog.i(
            TAG + " done id=" + id + " size=" + target.length() +
                " segments=" + segments.size + " file=" + target.absolutePath,
        )
        // 时间轴处理：源站残缺分片留下 PTS 空洞，转 MP4 时会被写成「一帧显示 2 秒」
        // （画面定格）并且音频比视频短（越播越超前）。先重写成连续时间轴再体检。
        try {
            val stat = TsFixer.fix(target)
            if (stat != null) DownloadLog.write("fixer " + stat.toString() + " file=" + target.name)
        } catch (th: Throwable) {
            AppLog.e(TAG + " fixer failed: " + th.message)
        }
        try {
            val probe = TsProbe.scan(target)
            if (probe != null) DownloadLog.write("pts " + probe.toString() + " file=" + target.name)
        } catch (th: Throwable) {
            AppLog.e(TAG + " pts probe failed: " + th.message)
        }

        // 顺手排一次 MP4 封装（后台串行；失败保留 .ts 不影响播放）
        try {
            Mp4Converter.enqueue(ctx, target.absolutePath)
        } catch (th: Throwable) {
            AppLog.e(TAG + " enqueue convert failed: " + th.message)
        }
    }

    // ---------- 直链文件 ----------

    private suspend fun runSingleFile(ctx: Context, task: TsTask, ext: String, id: String) {
        val dir = publicDir()
        if (!dir.canWrite()) {
            throw IOException("下载目录不可写，请在系统设置里允许 AVBox 访问「所有文件」")
        }
        val part = File(dir, safeName(task) + ext + ".part")
        val target = File(dir, safeName(task) + ext)
        val start = if (part.exists()) part.length() else 0L
        AppLog.i(TAG + " single-file id=" + id + " ext=" + ext + " resume=" + start)

        val builder = Request.Builder().url(task.playUrl).get()
        task.headers?.forEach { (key, value) ->
            try {
                builder.header(key, value)
            } catch (ignored: Throwable) {
            }
        }
        if (start > 0L) builder.header("Range", "bytes=" + start + "-")
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("文件 HTTP " + resp.code)
            val body = resp.body ?: throw IOException("空响应")
            val remain = body.contentLength()
            val total = if (remain > 0L) start + remain else 0L
            update(ctx, id, force = true) { it.copy(bytes = start, totalBytes = total) }
            body.byteStream().use { input ->
                FileOutputStream(part, start > 0L).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var written = start
                    var lastReport = written
                    while (true) {
                        if (paused.contains(id) || globalPaused) {
                            if (globalPaused && !paused.contains(id)) stoppedByGlobal.add(id)
                            out.flush()
                            update(ctx, id, force = true) { it.copy(state = Download.STATE_STOPPED, bytes = written) }
                            AppLog.i(TAG + " single-file paused at " + written)
                            return
                        }
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - lastReport >= 1_000_000L) {
                            lastReport = written
                            out.flush()
                            update(ctx, id) { it.copy(bytes = written) }
                        }
                    }
                    out.flush()
                    update(ctx, id) { it.copy(bytes = written) }
                }
            }
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        DownloadLibrary.markDone(task.playUrl, target.absolutePath)
        update(ctx, id, force = true) {
            it.copy(
                state = Download.STATE_COMPLETED,
                bytes = target.length(),
                totalBytes = target.length(),
                error = null,
            )
        }
        AppLog.i(TAG + " single-file done id=" + id + " size=" + target.length() + " file=" + target.absolutePath)
    }

    // ---------- 清单 ----------

    private fun fetchSegments(task: TsTask): List<String> {
        var base = task.playUrl
        var content = httpText(base, task.headers)
        var depth = 0
        while (isMaster(content) && depth < 3) {
            val next = pickVariant(content, base) ?: throw IOException("主清单里没有可用线路")
            base = next
            content = httpText(base, task.headers)
            depth++
        }
        if (isMaster(content)) throw IOException("清单层级过多")
        if (content.contains("#EXT-X-KEY") && !content.contains("METHOD=NONE")) {
            throw IOException("清单加密（EXT-X-KEY），暂不支持")
        }
        if (content.contains("#EXT-X-MAP")) {
            throw IOException("fMP4 分片（EXT-X-MAP），暂不支持")
        }

        var text = M3u8Purifier.stripBom(content)
        // 净化统一走 PurifyEngine：两层判断与播放侧 StreamProxy.purifyM3u8 完全一致，
        // 在线播放看到什么，下载下来就是什么。
        // 例外：地址本身已经是本地净化代理（pure=1 / 127.0.0.1）时说明清单已经洗过一遍，
        // 再洗一次会二次误删，所以跳过。
        if (PurifyProxy.enabled()) {
            if (isLocalPurifyUrl(task.playUrl)) {
                DownloadLog.write("purify 跳过：清单来自本地净化代理")
            } else {
                val before = countSegments(text)
                val result = PurifyEngine.purify(text, base)
                text = result.text
                val after = countSegments(text)
                DownloadLog.write("purify dropped=" + (before - after) + "/" + before + " " + result.toString())
            }
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { M3u8Purifier.resolve(base, it) }
    }

    /** 本地净化代理地址（清单已经被洗过一遍） */
    private fun isLocalPurifyUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("pure=1") || u.contains("127.0.0.1") || u.contains("localhost")
    }

    private fun countSegments(text: String): Int {
        var n = 0
        for (line in text.split("\n")) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#")) n++
        }
        return n
    }

    private fun isMaster(content: String): Boolean = content.contains("#EXT-X-STREAM-INF")

    private fun pickVariant(content: String, base: String): String? {
        var best: String? = null
        var bestBandwidth = -1L
        var pending = -1L
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pending = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } else if (line.isNotEmpty() && !line.startsWith("#") && pending >= 0) {
                if (pending > bestBandwidth) {
                    bestBandwidth = pending
                    best = M3u8Purifier.resolve(base, line)
                }
                pending = -1L
            }
        }
        return best
    }

    // ---------- HTTP ----------

    private fun buildRequest(url: String, headers: Map<String, String>?): Request {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IOException("地址不合法: " + url.take(90))
        val builder = Request.Builder().url(httpUrl)
        if (headers != null) {
            for ((k, v) in headers) {
                try {
                    builder.header(k, v)
                } catch (ignored: Throwable) {
                }
            }
        }
        return builder.build()
    }

    private fun httpText(url: String, headers: Map<String, String>?): String {
        client.newCall(buildRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("清单 HTTP " + resp.code)
            val body = resp.body ?: throw IOException("清单空响应")
            return body.bytes().toString(Charsets.UTF_8)
        }
    }

    private suspend fun fetchSegment(url: String, headers: Map<String, String>?): ByteArray {
        var last: Throwable? = null
        for (attempt in 1..3) {
            try {
                return httpBytes(url, headers)
            } catch (th: Throwable) {
                last = th
                AppLog.e(TAG, "segment attempt=" + attempt + " " + url.take(120), th)
                if (attempt < 3) delay(700L * attempt)
            }
        }
        throw IOException("分片下载失败: " + (last?.message ?: "未知"), last)
    }

    /**
     * 整片读进内存再交给调用方写盘，避免重试时把半片数据重复写进文件。
     *
     * 首字节必须是 0x47（TS 包同步字节），否则直接报错——源站返回占位图/HTML
     * 时会在这里暴露，而不是等用户播放时才发现文件是坏的。
     */
    private fun httpBytes(url: String, headers: Map<String, String>?): ByteArray {
        client.newCall(buildRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("分片 HTTP " + resp.code)
            val body = resp.body ?: throw IOException("分片空响应")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw IOException("分片为空")
            val first = bytes[0].toInt() and 0xFF
            if (first != 0x47) {
                throw IOException("不是 TS 分片（首字节 0x" + Integer.toHexString(first) + "）")
            }
            // 源站偶尔给出残缺分片（末尾多／少几个字节）。TS 是固定 188 字节一个包，
            // 一旦不对齐，拼进整片后后面所有包都会错位，播放器只能边找同步字边丢帧
            // —— 表现就是「画面一卡一卡」。这里把尾巴的残字节切掉。
            val rem = bytes.size % 188
            if (rem != 0) {
                DownloadLog.write("segment 残缺 " + rem + " 字节已截断 " + url.substringAfterLast('/').take(40))
                return bytes.copyOf(bytes.size - rem)
            }
            return bytes
        }
    }

    // ---------- 内部工具 ----------

    /** 把文件截到指定长度，用于续传前校正「记录位置」与「实际长度」的偏差 */
    private fun truncateTo(file: File, length: Long): Boolean {
        return try {
            RandomAccessFile(file, "rw").use { it.setLength(length) }
            true
        } catch (th: Throwable) {
            AppLog.e(TAG + " truncate failed: " + th.message)
            false
        }
    }

    private fun safeName(task: TsTask): String {
        val raw = (task.vodName + "_" + task.episodeName).ifBlank { task.id }
        return raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
    }

    /**
     * 内存里的状态每片都更新（UI 读的是它，进度才平滑）；
     * 落盘（KV 里整表 JSON）按 1 秒节流，否则每片一次序列化会明显拖慢下载。
     */
    private fun update(ctx: Context, id: String, force: Boolean = false, block: (TsTask) -> TsTask) {
        val cur = mem[id] ?: return
        save(ctx, block(cur), force)
    }

    private fun save(ctx: Context, task: TsTask, force: Boolean = false) {
        mem[task.id] = task
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            val last = persistAt[task.id] ?: 0L
            if (now - last < 1000L) return
        }
        persistAt[task.id] = now
        TsTaskStore.put(task)
    }
}
