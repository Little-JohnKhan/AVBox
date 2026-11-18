package com.github.avbox.osc.player.download

import android.os.Build
import android.os.Environment
import com.github.avbox.osc.util.AppLog

import java.io.File

/** 下载目录里扫到的一个视频文件（App 下载的、或用户手动放进去的都算） */
data class LocalVideo(
    val file: File,
    val name: String,
    val size: Long,
    val modifiedAt: Long,
) {
    val path: String get() = file.absolutePath
}

/**
 * 直接扫 Download/AVBox 目录，不依赖下载任务表。
 *
 * 下载列表就是把这个目录如实列出来：文件名是什么就显示什么，扫到几个就是几个。
 * 好处是手动拷进去的 mp4 也能看到，任务记录丢了文件还在也不会丢。
 */
object LocalLibrary {

    private val EXTENSIONS = setOf(
        "ts", "mp4", "mkv", "avi", "flv", "mov", "webm", "m4v", "wmv", "rmvb", "mpg", "mpeg",
    )

    /** 没这个权限时 listFiles() 返回 null，扫不到东西 */
    fun hasAllFilesAccess(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                true
            }
        } catch (th: Throwable) {
            false
        }
    }

    fun scan(): List<LocalVideo> {
        return try {
            val files = TsDownloader.publicDir().listFiles() ?: return emptyList()
            files.asSequence()
                .filter { it.isFile }
                .filter { it.length() > 0L }
                .filter { it.extension.lowercase() in EXTENSIONS }
                .map { LocalVideo(it, it.name, it.length(), it.lastModified()) }
                // 自然排序：第2集 排在 第10集 前面
                .sortedWith(Comparator { a, b -> naturalCompare(a.name, b.name) })
                .toList()
        } catch (th: Throwable) {
            AppLog.e("LocalLibrary", "scan failed", th)
            emptyList()
        }
    }

    /** 数字块按数值比，其余按字符比，忽略大小写 */
    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                var nj = j
                while (ni < a.length && a[ni].isDigit()) ni++
                while (nj < b.length && b[nj].isDigit()) nj++
                val na = a.substring(i, ni).trimStart('0')
                val nb = b.substring(j, nj).trimStart('0')
                val cmp = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
                if (cmp != 0) return cmp
                i = ni
                j = nj
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}
