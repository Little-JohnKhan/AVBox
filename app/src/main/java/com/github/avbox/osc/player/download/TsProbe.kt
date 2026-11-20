package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.AppLog
import java.io.File

/**
 * TS 时间轴探针（只读，绝不改文件）。
 *
 * 背景：净化器把广告分片从清单里剔掉之后，剩下分片的 PTS 会留下断点。
 * 拼接出来的 .ts 在断点处会卡一下、之后音画越差越远。
 *
 * 这里只做统计：扫一遍 188 字节的 TS 包，按 PID 取每个 PES 头的 PTS，
 * 数出「相邻 PTS 间隔超过 2 秒」的次数和最大间隔，写进 download-debug.log。
 * 有这组数据就能确认根因、也能给后续的时间戳修复器定参数。
 *
 * PTS 是 33 比特、90kHz 时钟，约 26.5 小时才回绕，正常影片不会溢出。
 */
object TsProbe {

    private const val TAG = "TsProbe"
    private const val PACKET = 188
    private const val SYNC = 0x47
    private const val JUMP_MS = 2000L

    data class Report(
        val packets: Int,
        val streams: Int,
        val jumps: Int,
        val maxGapMs: Long,
        val spanMs: Long,
    ) {
        override fun toString(): String =
            "packets=" + packets + " streams=" + streams + " jumps=" + jumps +
                " maxGap=" + maxGapMs + "ms span=" + spanMs + "ms"
    }

    fun scan(file: File): Report? {
        return try {
            if (!file.exists() || file.length() < PACKET.toLong() * 4L) return null
            var packets = 0
            var jumps = 0
            var maxGap = 0L
            val first = HashMap<Int, Long>()
            val last = HashMap<Int, Long>()
            val buffer = ByteArray(PACKET)
            file.inputStream().buffered(1 shl 20).use { input ->
                while (true) {
                    var read = 0
                    while (read < PACKET) {
                        val n = input.read(buffer, read, PACKET - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < PACKET) break
                    if ((buffer[0].toInt() and 0xFF) != SYNC) break
                    packets++

                    val pusi = (buffer[1].toInt() and 0x40) != 0
                    if (!pusi) continue
                    val pid = ((buffer[1].toInt() and 0x1F) shl 8) or (buffer[2].toInt() and 0xFF)

                    val afc = (buffer[3].toInt() shr 4) and 0x3
                    var offset = 4
                    if ((afc and 0x2) != 0) offset = 5 + (buffer[4].toInt() and 0xFF)
                    if ((afc and 0x1) == 0) continue
                    if (offset + 14 >= PACKET) continue

                    if (buffer[offset].toInt() != 0 ||
                        buffer[offset + 1].toInt() != 0 ||
                        buffer[offset + 2].toInt() != 1
                    ) {
                        continue
                    }
                    val flags = buffer[offset + 7].toInt() and 0xFF
                    if ((flags and 0x80) == 0) continue

                    val base = offset + 9
                    val pts = ((buffer[base].toLong() and 0x0EL) shl 29) or
                        ((buffer[base + 1].toLong() and 0xFFL) shl 22) or
                        ((buffer[base + 2].toLong() and 0xFEL) shl 14) or
                        ((buffer[base + 3].toLong() and 0xFFL) shl 7) or
                        ((buffer[base + 4].toLong() and 0xFEL) shr 1)
                    val ms = pts / 90L

                    val prev = last[pid]
                    if (prev == null) {
                        first[pid] = ms
                    } else {
                        val gap = ms - prev
                        if (gap > JUMP_MS) {
                            jumps++
                            if (gap > maxGap) maxGap = gap
                        }
                    }
                    last[pid] = ms
                }
            }
            if (packets == 0) return null
            val begin = first.values.minOrNull() ?: 0L
            val end = last.values.maxOrNull() ?: 0L
            Report(packets, last.size, jumps, maxGap, (end - begin).coerceAtLeast(0L))
        } catch (th: Throwable) {
            AppLog.e(TAG + " scan failed: " + th.message)
            null
        }
    }
}
