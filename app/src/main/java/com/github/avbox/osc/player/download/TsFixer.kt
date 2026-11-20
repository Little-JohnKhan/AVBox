package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * TS 时间轴修复器。
 *
 * 源站的分片偶尔是残缺的（片内只有 1 帧视频、没有音频），拼成整片后 PTS 会留下
 * 空洞。转成 MP4 时 MP4 用「样本时长」而不是绝对时间戳，空洞就变成「一帧显示 2 秒」
 * —— 画面定格，而音频样本是连续排的，于是音频比视频短，越播越超前。
 *
 * 修法：不动内容字节，只重写 PES 头里的 PTS/DTS。
 *   视频：第 i 个 PES 的新 DTS = base + i × 帧时长，PTS 保留原有的 (PTS − DTS) 偏移，
 *         这样 B 帧的重排关系完全不变；
 *   音频：每个 PES 的新 PTS = 原 PTS − 它之前累计的空洞时长，让音频跟着视频一起跳过空洞。
 *
 * 只在 PES 头上动手，不改码流；PCR 不处理（MP4 里没有 PCR）。
 * 安全校验：包对齐（188 字节）、帧时长稳定（众数占比 ≥ 70%），任一不满足就整体放弃，
 * 保证「宁可不动，也不改坏」。
 */
object TsFixer {

    private const val TAG = "TsFixer"
    private const val PACKET = 188
    private const val SYNC = 0x47

    /** TS 里视频 stream_id 区间 0xE0..0xEF，音频 0xC0..0xDF */
    private fun isVideoSid(sid: Int) = sid in 0xE0..0xEF
    private fun isAudioSid(sid: Int) = sid in 0xC0..0xDF

    data class Stat(
        val videoPes: Int,
        val audioPes: Int,
        val frameMs: Double,
        val gaps: Int,
        val maxGapMs: Long,
        val fixed: Boolean,
        val note: String = "",
    ) {
        override fun toString(): String =
            "video=" + videoPes + " audio=" + audioPes +
                " frameMs=" + String.format(java.util.Locale.US, "%.1f", frameMs) +
                " gaps=" + gaps + " maxGap=" + maxGapMs + "ms fixed=" + fixed +
                (if (note.isEmpty()) "" else " note=" + note)
    }

    /** 一个 PES 起始包：在第 packet 个 TS 包里、PTS 字段起始于包内 innerOffset 处 */
    private class PesRef(val packet: Int, val innerOffset: Int, val pts: Long, val dts: Long, val hasDts: Boolean)

    fun fix(file: File): Stat? {
        var tmp: File? = null
        return try {
            val size = file.length()
            if (size < PACKET.toLong() * 100L || size % PACKET != 0L) return null

            val video = ArrayList<PesRef>(1 shl 16)
            val audio = ArrayList<PesRef>(1 shl 14)
            if (!collect(file, video, audio)) {
                AppLog.i(TAG + " 放弃：包结构不对齐")
                return null
            }
            if (video.size < 50) return null

            // 帧时长取 DTS 差值的中位数（采样前 4000 个，够用且快）
            val diffs = ArrayList<Long>(4000)
            var prev = video[0].dts
            for (i in 1 until minOf(video.size, 4000)) {
                val d = video[i].dts - prev
                prev = video[i].dts
                if (d > 0) diffs.add(d)
            }
            if (diffs.isEmpty()) return null
            diffs.sort()
            val frameTicks = diffs[diffs.size / 2]
            if (frameTicks <= 0) return null

            // 帧时长必须稳定：众数占比不足 70% 说明是 VFR，重排会破坏播放节奏
            val mode = diffs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
            if (mode * 100 / diffs.size < 70) {
                AppLog.i(TAG + " 放弃：帧时长不稳定（众数占比 " + (mode * 100 / diffs.size) + "%）")
                return null
            }

            // 视频空洞：[前帧结束, 下一帧开始)
            val gaps = ArrayList<LongArray>()
            for (i in 1 until video.size) {
                val d = video[i].dts - video[i - 1].dts
                if (d > frameTicks * 3 / 2) {
                    gaps.add(longArrayOf(video[i - 1].dts + frameTicks, video[i].dts))
                }
            }
            var maxGap = 0L
            for (g in gaps) maxGap = maxOf(maxGap, (g[1] - g[0]) / 90L)

            // 视频：按 PES 顺序重排 DTS，PTS 保留 PTS-DTS 偏移（B 帧结构不变）
            val vBase = video[0].dts
            val vPts = LongArray(video.size)
            val vDts = LongArray(video.size)
            for (i in video.indices) {
                val p = video[i]
                val nd = vBase + i.toLong() * frameTicks
                vDts[i] = nd
                vPts[i] = nd + (p.pts - p.dts)
            }
            // 音频：原 PTS 减去它前面累计的空洞
            val aPts = LongArray(audio.size)
            for (j in audio.indices) {
                aPts[j] = audio[j].pts - gapBefore(gaps, audio[j].pts)
            }

            // 顺序读一遍、改完写临时文件再替换：避免十几万次随机 seek
            val tmpFile = File(file.parentFile, file.name + ".fixtmp")
            tmp = tmpFile
            rewrite(file, tmpFile, video, vPts, vDts, audio, aPts)
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
            tmp = null

            val stat = Stat(video.size, audio.size, frameTicks / 90.0, gaps.size, maxGap, true)
            AppLog.i(TAG + " 修复完成 " + stat.toString() + " file=" + file.name)
            stat
        } catch (th: Throwable) {
            AppLog.e(TAG + " 修复失败: " + th.message)
            try {
                tmp?.delete()
            } catch (ignored: Throwable) {
            }
            null
        }
    }

    // ---------- 扫描 ----------

    private fun collect(file: File, video: MutableList<PesRef>, audio: MutableList<PesRef>): Boolean {
        var packet = 0
        val buf = ByteArray(PACKET)
        FileInputStream(file).use { raw ->
            val input = raw.buffered(1 shl 20)
            while (true) {
                var read = 0
                while (read < PACKET) {
                    val n = input.read(buf, read, PACKET - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < PACKET) break
                if ((buf[0].toInt() and 0xFF) != SYNC) return false

                if ((buf[1].toInt() and 0x40) != 0) {
                    val afc = (buf[3].toInt() shr 4) and 0x3
                    var o = 4
                    if ((afc and 0x2) != 0) o = 5 + (buf[4].toInt() and 0xFF)
                    if ((afc and 0x1) != 0 && o + 19 < PACKET &&
                        buf[o].toInt() == 0 && buf[o + 1].toInt() == 0 && buf[o + 2].toInt() == 1
                    ) {
                        val sid = buf[o + 3].toInt() and 0xFF
                        val kind = (buf[o + 7].toInt() shr 6) and 0x3
                        if (kind >= 2) {
                            val pts = decode(buf, o + 9)
                            val hasDts = kind == 3
                            val dts = if (hasDts) decode(buf, o + 14) else pts
                            val ref = PesRef(packet, o + 9, pts, dts, hasDts)
                            when {
                                isVideoSid(sid) -> video.add(ref)
                                isAudioSid(sid) -> audio.add(ref)
                            }
                        }
                    }
                }
                packet++
            }
        }
        return true
    }

    private fun rewrite(
        src: File,
        dst: File,
        video: List<PesRef>,
        vPts: LongArray,
        vDts: LongArray,
        audio: List<PesRef>,
        aPts: LongArray,
    ) {
        val buf = ByteArray(PACKET)
        var packet = 0
        var vi = 0
        var ai = 0
        FileInputStream(src).use { raw ->
            val input = raw.buffered(1 shl 20)
            BufferedOutputStream(FileOutputStream(dst), 1 shl 20).use { out ->
                while (true) {
                    var read = 0
                    while (read < PACKET) {
                        val n = input.read(buf, read, PACKET - read)
                        if (n <= 0) break
                        read += n
                    }
                    if (read < PACKET) break

                    if (vi < video.size && video[vi].packet == packet) {
                        val ref = video[vi]
                        val enc = encode(vPts[vi])
                        System.arraycopy(enc, 0, buf, ref.innerOffset, 5)
                        if (ref.hasDts) {
                            val encDts = encode(vDts[vi])
                            System.arraycopy(encDts, 0, buf, ref.innerOffset + 5, 5)
                        }
                        vi++
                    } else if (ai < audio.size && audio[ai].packet == packet) {
                        val enc = encode(aPts[ai])
                        System.arraycopy(enc, 0, buf, audio[ai].innerOffset, 5)
                        ai++
                    }
                    out.write(buf, 0, PACKET)
                    packet++
                }
            }
        }
    }

    private fun gapBefore(gaps: List<LongArray>, t: Long): Long {
        var s = 0L
        for (g in gaps) {
            val a = g[0]
            val b = g[1]
            if (b <= t) s += b - a else if (a < t && t < b) s += t - a
        }
        return s
    }

    private fun decode(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0x0EL) shl 29) or
            ((b[o + 1].toLong() and 0xFFL) shl 22) or
            ((b[o + 2].toLong() and 0xFEL) shl 14) or
            ((b[o + 3].toLong() and 0xFFL) shl 7) or
            ((b[o + 4].toLong() and 0xFEL) shr 1)

    /** 33 比特、90kHz 的 PTS/DTS 编码（marker 位按标准补齐） */
    private fun encode(v: Long): ByteArray {
        val x = v and 0x1FFFFFFFFL
        return byteArrayOf(
            (0x21 or (((x shr 30) and 0x07L) shl 1).toInt()).toByte(),
            ((x shr 22) and 0xFFL).toByte(),
            ((((x shr 15) and 0x7FL) shl 1) or 1L).toByte(),
            ((x shr 7) and 0xFFL).toByte(),
            (((x and 0x7FL) shl 1) or 1L).toByte(),
        )
    }
}
