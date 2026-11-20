package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.AppLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 下载链路的可读日志：写到 Download/AVBox/download-debug.log。
 *
 * AppLog 只进 logcat，用户在手机上翻不到；下载出问题时（片段被删、卡片停在 3/4、
 * 音画不同步）需要能自己看一眼真实数据，所以净化统计、分片校验、时间轴体检都落到这里。
 */
object DownloadLog {

    const val FILE = "download-debug.log"
    private const val TAG = "DownloadLog"

    private val executor = Executors.newSingleThreadExecutor()

    fun write(line: String) {
        AppLog.i(TAG + " " + line)
        try {
            executor.execute {
                try {
                    val file = File(TsDownloader.publicDir(), FILE)
                    if (file.length() > 1024 * 1024) file.delete()
                    val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                    file.appendText(ts + " " + line + "\n")
                } catch (ignored: Throwable) {
                }
            }
        } catch (ignored: Throwable) {
        }
    }
}
