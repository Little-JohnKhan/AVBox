package com.github.avbox.osc.player.download

import android.content.Context
import android.os.Environment
import androidx.media3.exoplayer.offline.Download
import com.github.avbox.osc.player.engine.PurifyProxy
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.M3u8Purifier
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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

    /** 同时下载的分片数。源站多为单连接限速，并发才能把总带宽吃满 */
    private const val CONCURRENCY = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mem = ConcurrentHashMap<String, TsTask>()
    private val paused = Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var loaded = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
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

    /** 成品：Download/AVBox/<名字>.ts，外部播放器可直接打开 */
    fun publicFileOf(task: TsTask): File = File(publicDir(), safeName(task) + ".ts")

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
        save(app, task)
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
        save(ctx.applicationContext, queued)
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
                publicFileOf(task).delete()
            } catch (ignored: Throwable) {
            }
            DownloadLibrary.markRemoved(task.playUrl)
        }
        AppLog.i(TAG + " removed id=" + id)
    }

    // ---------- 下载主流程 ----------

    private fun start(ctx: Context, task: TsTask) {
        val running = jobs[task.id]
        if (running != null && running.isActive) return
        jobs[task.id] = scope.launch {
            try {
                run(ctx, task)
            } catch (th: Throwable) {
                AppLog.e(TAG, "failed id=" + task.id, th)
                update(ctx, task.id) { t ->
                    t.copy(state = Download.STATE_FAILED, error = th.message ?: "下载失败")
                }
            } finally {
                jobs.remove(task.id)
                DownloadNotifications.refresh(ctx)
            }
        }
    }

    private suspend fun run(ctx: Context, task: TsTask) {
        val id = task.id
        update(ctx, id) { it.copy(state = Download.STATE_DOWNLOADING, error = null) }

        val segments = fetchSegments(task)
        if (segments.isEmpty()) throw IOException("清单里没有分片")
        AppLog.i(TAG + " segments=" + segments.size + " id=" + id)

        val dir = publicDir()
        if (!dir.canWrite()) {
            throw IOException("下载目录不可写，请在系统设置里允许 AVBox 访问「所有文件」")
        }
        val part = partFileOf(task)
        val target = publicFileOf(task)
        val prev = mem[id]
        var startIndex = 0
        if (prev != null && prev.totalSegments == segments.size &&
            prev.doneSegments > 0 && prev.doneSegments < segments.size &&
            part.exists() && part.length() == prev.bytes
        ) {
            startIndex = prev.doneSegments
            AppLog.i(TAG + " resume " + startIndex + "/" + segments.size)
        } else if (part.exists()) {
            part.delete()
        }
        var written = if (part.exists()) part.length() else 0L
        update(ctx, id) { it.copy(totalSegments = segments.size, doneSegments = startIndex, bytes = written) }

        FileOutputStream(part, startIndex > 0).use { os ->
            var index = startIndex
            while (index < segments.size) {
                if (paused.contains(id)) {
                    os.flush()
                    update(ctx, id) { it.copy(state = Download.STATE_STOPPED) }
                    AppLog.i(TAG + " paused at " + index + "/" + segments.size)
                    return
                }
                // 一批并发抓下来，再按顺序落盘：顺序不变，尾部只剩当前批不满的窗口
                val end = minOf(index + CONCURRENCY, segments.size)
                val batch = coroutineScope {
                    (index until end).map { i ->
                        async(Dispatchers.IO) { fetchSegment(segments[i], task.headers) }
                    }
                }
                for ((offset, deferred) in batch.withIndex()) {
                    val bytes = deferred.await()
                    os.write(bytes)
                    os.flush()
                    written += bytes.size
                    val done = index + offset + 1
                    update(ctx, id) { it.copy(doneSegments = done, bytes = written, totalBytes = written) }
                }
                index = end
            }
        }

        // 收尾：.part 改名成 .ts，公开目录里直接就是一个能播的成品文件
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        DownloadLibrary.markDone(task.playUrl, target.absolutePath)
        update(ctx, id) { t ->
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
        if (PurifyProxy.enabled()) {
            val report = M3u8Purifier.Report()
            val purified = M3u8Purifier.purify(text, base, report)
            if (purified != null) {
                text = purified
                AppLog.i(TAG + " purify " + report.toString())
            } else {
                AppLog.i(TAG + " purify skip " + report.toString())
            }
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { M3u8Purifier.resolve(base, it) }
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
            return bytes
        }
    }

    // ---------- 内部工具 ----------

    private fun safeName(task: TsTask): String {
        val raw = (task.vodName + "_" + task.episodeName).ifBlank { task.id }
        return raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").take(80)
    }

    private fun update(ctx: Context, id: String, block: (TsTask) -> TsTask) {
        val cur = mem[id] ?: return
        save(ctx, block(cur))
    }

    private fun save(ctx: Context, task: TsTask) {
        mem[task.id] = task
        TsTaskStore.put(task)
    }
}
