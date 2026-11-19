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
    private val pending = ArrayList<String>()
    private var busy = false

    fun stateOf(path: String): State? = state?.takeIf { it.sourcePath == path }

    fun isBusy(): Boolean = state != null

    /** 排队转换一个 .ts；同一路径不会重复入队 */
    fun enqueue(context: Context, tsPath: String) {
        val app = context.applicationContext
        scope.launch {
            if (pending.contains(tsPath) || state?.sourcePath == tsPath) return@launch
            pending.add(tsPath)
            if (!busy) pump(app)
        }
    }

    /** 把目录里所有 .ts 排进队列，并清掉上次残留的半成品 */
    fun enqueueAll(context: Context) {
        val app = context.applicationContext
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

    private suspend fun pump(context: Context) {
        if (busy) return
        busy = true
        try {
            while (pending.isNotEmpty()) {
                val path = pending.removeAt(0)
                val src = File(path)
                if (!src.isFile || src.length() == 0L) continue

                val dst = File(src.parentFile, src.nameWithoutExtension + ".mp4")
                // 已经有成品：直接清掉源文件
                if (dst.isFile && dst.length() > 0L) {
                    src.delete()
                    continue
                }

                val temp = File(src.parentFile, src.nameWithoutExtension + PARTIAL_SUFFIX)
                state = State(src.absolutePath, src.name, dst.name, 0)
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
                    }
                } else {
                    temp.delete()
                    state = State(src.absolutePath, src.name, dst.name, 0, failed = true)
                }
                delay(700)
            }
        } catch (th: Throwable) {
            AppLog.e(TAG, "pump failed", th)
        } finally {
            state = null
            busy = false
        }
    }

    private suspend fun runTransform(context: Context, src: File, out: File): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                suspendCancellableCoroutine { cont ->
                    val holder = ProgressHolder()
                    var transformer: Transformer? = null
                    val poller = scope.launch {
                        while (cont.isActive) {
                            val t = transformer
                            if (t != null &&
                                t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE
                            ) {
                                val p = holder.progress.coerceIn(0, 100)
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
