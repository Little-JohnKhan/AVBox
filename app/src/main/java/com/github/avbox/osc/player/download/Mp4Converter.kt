package com.github.avbox.osc.player.download

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.github.avbox.osc.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * 把下载好的 .ts 封装成 .mp4（media3 Transformer，只换封装不解码）。
 *
 * 为什么不用 MediaMuxer：系统 muxer 写不了 ctts，源里带 B 帧时会出现
 * 声音错位/抽帧；Transformer 内部走 Mp4Muxer，索引和时间戳都能对齐。
 *
 * 串行队列：一次只转一个，避免多任务抢 IO。
 * 输出先写 .mp4.partial，成功后再改名，进程被杀也不会留下"看起来完整"的半成品。
 */
object Mp4Converter {

    private const val TAG = "Mp4Converter"
    private const val PARTIAL_SUFFIX = ".mp4.partial"

    data class State(
        val sourcePath: String,
        val sourceName: String,
        val targetName: String,
        val percent: Int = 0,
        val failed: Boolean = false,
    )

    /** 当前正在转换的文件；Compose 可直接读来做进度显示 */
    var state by mutableStateOf<State?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 封装专用线程。
     *
     * media3 的 Transformer 把内部调度绑在「创建它的那个 Looper」上，而之前我们是在主线程创建的：
     * App 一进后台，ColorOS 会给主线程限速，转换就跟着停住（通知还在、进度条卡死），
     * 只有切回前台才继续。这里给转换单独开一个 HandlerThread，
     * 转换和进度轮询都不再依赖主线程，后台也能跑完。
     */
    private val convertThread by lazy {
        android.os.HandlerThread("avbox-mp4-convert").apply { start() }
    }
    private val convertHandler by lazy { android.os.Handler(convertThread.looper) }
    private val convertDispatcher by lazy { convertHandler.asCoroutineDispatcher() }
    private val pending = ArrayList<String>()
    private var busy = false

    /** 批量下载期间挂起封装：只下不转，整批结束后再统一转 */
    @Volatile
    private var batchMode = false

    @Volatile
    private var appContext: android.content.Context? = null

    fun stateOf(path: String): State? = state?.takeIf { it.sourcePath == path }

    fun isBusy(): Boolean = state != null

    /** 还有没有活要干（正在转 + 排队中）。前台服务靠它决定能不能退出 */
    fun hasWork(): Boolean = busy || state != null || pending.isNotEmpty()

    fun pendingCount(): Int = pending.size

    /** 排队转换一个 .ts；同一路径不会重复入队 */
    fun enqueue(context: Context, tsPath: String) {
        val app = context.applicationContext
        appContext = app
        scope.launch {
            if (pending.contains(tsPath) || state?.sourcePath == tsPath) return@launch
            pending.add(tsPath)
            if (!busy) pump(app)
        }
    }

    /** 把目录里所有 .ts 排进队列，并清掉上次残留的半成品 */
    fun enqueueAll(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val sources = withContext(Dispatchers.IO) {
                val dir = TsDownloader.publicDir()
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(PARTIAL_SUFFIX)) {
                        try {
                            file.delete()
                        } catch (ignored: Throwable) {
                        }
                    }
                }
                (dir.listFiles() ?: emptyArray())
                    .filter { it.isFile && it.length() > 0L && it.extension.equals("ts", true) }
                    .map { it.absolutePath }
            }
            if (sources.isEmpty()) return@launch
            AppLog.i(TAG + " enqueueAll n=" + sources.size)
            sources.forEach { path ->
                if (!pending.contains(path) && state?.sourcePath != path) pending.add(path)
            }
            if (!busy) pump(app)
        }
    }

    /** 批量下载开关：true 时封装队列只排队不执行 */
    fun setBatchMode(on: Boolean) {
        if (batchMode == on) return
        batchMode = on
        AppLog.i(TAG + " batchMode=" + on + " pending=" + pending.size)
        if (!on) {
            val ctx = appContext ?: return
            scope.launch { if (!busy) pump(ctx) }
        }
    }

    private suspend fun pump(context: Context) {
        if (busy) return
        busy = true
        try {
            while (pending.isNotEmpty()) {
                while (batchMode) delay(2000)
                val path = pending.removeAt(0)
                val src = File(path)
                if (!src.isFile || src.length() == 0L) continue

                val dst = File(src.parentFile, src.nameWithoutExtension + ".mp4")
                // 同名旧成品存在 = 用户重下了这一集（改了解析/净化之后内容会不一样）。
                // 必须用新内容替换它，不能走「删掉新 ts 直接跳过」——
                // 那样用户看到的就是「下载完成了，但本地还是旧文件/没有新文件」。
                if (dst.isFile && dst.length() > 0L) {
                    AppLog.i(TAG + " 替换同名旧成品 " + dst.name + " old=" + dst.length() + " new=" + src.length())
                    DownloadLog.write("convert 替换旧成品 " + dst.name + " old=" + dst.length() + " new=" + src.length())
                    dst.delete()
                }

                val temp = File(src.parentFile, src.nameWithoutExtension + PARTIAL_SUFFIX)
                state = State(src.absolutePath, src.name, dst.name, 0)
                // 拉起前台服务：没有它，进程在后台会被系统冻结，转换停在原地不动
                try {
                    DownloadNotifications.refresh(context)
                } catch (ignored: Throwable) {
                }
                val ok = runTransform(context, src, temp)
                val produced = ok && temp.isFile && temp.length() > 0L

                if (produced) {
                    if (dst.exists()) dst.delete()
                    val renamed = temp.renameTo(dst)
                    val done = renamed || run {
                        temp.copyTo(dst, overwrite = true)
                        temp.delete()
                        dst.length() > 0L
                    }
                    if (done && dst.length() >= src.length() / 2) {
                        rebind(src, dst)
                        src.delete()
                        state = State(src.absolutePath, src.name, dst.name, 100)
                        AppLog.i(TAG + " done " + src.name + " -> " + dst.name + " size=" + dst.length())
                    } else {
                        dst.delete()
                        state = State(src.absolutePath, src.name, dst.name, 0, failed = true)
                        AppLog.e(TAG + " verify failed " + src.name)
                        DownloadLog.write("convert 校验失败 " + src.name + " out=" + dst.length() + " src=" + src.length())
                    }
                } else {
                    temp.delete()
                    state = State(src.absolutePath, src.name, dst.name, 0, failed = true)
                    DownloadLog.write("convert 未完成 " + src.name)
                }
                delay(700)
            }
        } catch (th: Throwable) {
            AppLog.e(TAG, "pump failed", th)
        } finally {
            // 失败提示要留得住：状态一秒就清会让用户只看到一闪
            if (state?.failed == true) {
                try {
                    kotlinx.coroutines.delay(6000)
                } catch (ignored: Throwable) {
                }
            }
            state = null
            busy = false
        }
    }

    private suspend fun runTransform(context: Context, src: File, out: File): Boolean {
        return withContext(convertDispatcher) {
            try {
                suspendCancellableCoroutine { cont ->
                    val holder = ProgressHolder()
                    var transformer: Transformer? = null
                    // 停滞检测：后台封装如果被系统掐断，进度会停住但状态还是"正在封装"，
                    // 光看界面分不清「转换本身卡住」和「进程被冻结」。这里超过 90 秒没动就记一笔，
                    // 日志停在某一格不动 = 进程被冻结，日志里出现「停滞」= 转换器自己卡住。
                    var lastPercent = -1
                    var lastChangeAt = System.currentTimeMillis()
                    val poller = scope.launch(convertDispatcher) {
                        while (cont.isActive) {
                            val t = transformer
                            if (t != null &&
                                t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                            ) {
                                val p = holder.progress.coerceIn(0, 100)
                                val now = System.currentTimeMillis()
                                if (p != lastPercent) {
                                    lastPercent = p
                                    lastChangeAt = now
                                } else if (now - lastChangeAt > 90_000L) {
                                    DownloadLog.write("convert 停滞 90s @" + p + "% " + src.name)
                                    lastChangeAt = now
                                }
                                state = state?.copy(percent = p)
                            }
                            delay(500)
                        }
                    }
                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            poller.cancel()
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exception: ExportException,
                        ) {
                            poller.cancel()
                            AppLog.e(TAG + " transform error: " + exception.message)
                            DownloadLog.write(
                                "convert 失败 " + src.name + " [" + exception.javaClass.simpleName + "] " +
                                    exception.message + (exception.cause?.let { " cause=" + it.javaClass.simpleName + ": " + it.message } ?: ""),
                            )
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    val t = Transformer.Builder(context)
                        .addListener(listener)
                        .build()
                    transformer = t
                    cont.invokeOnCancellation {
                        poller.cancel()
                        try {
                            t.cancel()
                        } catch (ignored: Throwable) {
                        }
                    }
                    t.start(MediaItem.fromUri(Uri.fromFile(src)), out.absolutePath)
                }
            } catch (th: Throwable) {
                AppLog.e(TAG, "transform crashed", th)
                false
            }
        }
    }

    /** 转换完成后把「原地址 → 文件」映射指向 mp4，播放侧才不会回落到在线 */
    private fun rebind(src: File, dst: File) {
        try {
            val newPath = dst.absolutePath
            for (task in TsTaskStore.all()) {
                if (TsDownloader.tsFileOf(task).absolutePath == src.absolutePath) {
                    DownloadLibrary.markDone(task.playUrl, newPath)
                }
            }
        } catch (th: Throwable) {
            AppLog.e(TAG, "rebind failed", th)
        }
    }
}
