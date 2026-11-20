package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * TS 时间轴修复器：**按段平移**，内容一个字节都不丢。
 *
 * 背景：这类源的清单是把多段独立转码的流拼起来的，段与段之间 PTS 各自从任意值开始
 * （实测 dytt-hot 一集里视频 PTS 中间空了 279 秒，音频却是连续的）。拼成单个 ts 后
 * 播放器按 PTS 走，画面会直接跳过那段、声音继续播，表现就是「画面删了但广告声音还在」。
 *
 * 修法：只改 PES 头里的 PTS/DTS，不动码流。
 *   - 段内：**保留原始帧间隔**（不同段落可能是 24/25/30fps，匀速化会把片头拉慢）；
 *   - 段边界（相邻 DTS 回退、或间隔超过 1.5 帧）：把这一段整体平移到上一段结束处 + 一帧；
 *   - 音频：套用视频算出的「段平移表」，按原 PTS 落段后加同样的偏移，音画始终锁在一起。
 *
 * 与旧版的关键区别：旧版把空洞「压缩」成一帧，会真的丢掉那段内容；
 * 新版只平移时间戳，**所有帧和采样都保留**，总时长 = 各段时长之和。
 *
 * 修完必须通过自检才替换原文件（视频 DTS 严格递增且不留大间隔、音频 PTS 严格递增、PES 数量对得上），
 * 任何一条不满足就删临时文件、原 ts 原封不动。
 */
object TsFixer {

    private const val TAG = "TsFixer"
    private const val PACKET = 188
    private const val SYNC = 0x47

    private fun isVideoSid(sid: Int) = sid in 0xE0..0xEF
    private fun isAudioSid(sid: Int) = sid in 0xC0..0xDF

    data class Stat(
        val videoPes: Int,
        val audioPes: Int,
        val segments: Int,
        val frameMs: Double,
        val gaps: Int,
        val maxGapMs: Long,
        val fixed: Boolean,
        val note: String = "",
    ) {
        override fun toString(): String =
            "video=" + videoPes + " audio=" + audioPes + " segs=" + segments +
                " frameMs=" + String.format(java.util.Locale.US, "%.1f", frameMs) +
                " gaps=" + gaps + " maxGap=" + maxGapMs + "ms fixed=" + fixed +
                (if (note.isEmpty()) "" else " note=" + note)
    }

    private class PesRef(
        val packet: Int,
        val pid: Int,
        val sid: Int,
        val innerOffset: Int,
        val pts: Long,
        val dts: Long,
        val hasDts: Boolean,
    )

    fun fix(file: File): Stat? {
        var tmp: File? = null
        return try {
            val size = file.length()
            if (size < PACKET.toLong() * 100L || size % PACKET != 0L) return null

            val video = ArrayList<PesRef>(1 shl 16)
            val audio = ArrayList<PesRef>(1 shl 14)
            if (!scan(file, video, audio)) {
                AppLog.i(TAG + " 放弃：包结构不对齐")
                return null
            }
            if (video.size < 50) return null

            val frameTicks = medianFrameTicks(video) ?: return null

            // 段边界：相邻 DTS 回退，或间隔超过 1.5 帧
            val gapIndexes = ArrayList<Int>()
            var maxGap = 0L
            for (i in 1 until video.size) {
                val d = video[i].dts - video[i - 1].dts
                if (d <= 0L || d > frameTicks * 3 / 2) {
                    gapIndexes.add(i)
                    if (d > frameTicks * 3 / 2) maxGap = maxOf(maxGap, d / 90L)
                }
            }

            // 时间轴本来连续：不重写，省掉一次整片读写
            if (gapIndexes.isEmpty()) {
                AppLog.i(TAG + " 无需修复：时间轴连续 video=" + video.size + " audio=" + audio.size)
                return Stat(video.size, audio.size, 1, frameTicks / 90.0, 0, 0, false, "时间轴连续")
            }

            // ---- 视频：段内保留原间隔，段间平移
            val vPts = LongArray(video.size)
            val vDts = LongArray(video.size)
            val segStart = ArrayList<Long>()   // 每段在原始时间轴上的起点
            val segOffset = ArrayList<Long>()  // 该段对应的平移量
            var offset = 0L
            vDts[0] = video[0].dts
            vPts[0] = video[0].pts
            segStart.add(video[0].dts)
            segOffset.add(0L)
            var gi = 0
            for (i in 1 until video.size) {
                if (gi < gapIndexes.size && gapIndexes[gi] == i) {
                    offset = vDts[i - 1] + frameTicks - video[i].dts
                    segStart.add(video[i].dts)
                    segOffset.add(offset)
                    gi++
                }
                vDts[i] = video[i].dts + offset
                vPts[i] = video[i].pts + offset
            }

            // ---- 音频：套用视频的段平移表，按原 PTS 落段
            val aPts = LongArray(audio.size)
            var lastA = Long.MIN_VALUE
            for (j in audio.indices) {
                var v = audio[j].pts + offsetAt(segStart, segOffset, audio[j].pts)
                if (v <= lastA) v = lastA + 1
                aPts[j] = v
                lastA = v
            }

            val tmpFile = File(file.parentFile, file.name + ".fixtmp")
            tmp = tmpFile
            rewrite(file, tmpFile, video, vPts, vDts, audio, aPts)

            val problem = verify(tmpFile, frameTicks, video.size, audio.size)
            if (problem != null) {
                AppLog.e(TAG + " 自检不通过，放弃修复：" + problem)
                tmpFile.delete()
                tmp = null
                return Stat(
                    video.size, audio.size, segStart.size, frameTicks / 90.0,
                    gapIndexes.size, maxGap, false, "自检失败:" + problem,
                )
            }

            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
            tmp = null

            val stat = Stat(
                video.size, audio.size, segStart.size, frameTicks / 90.0,
                gapIndexes.size, maxGap, true,
            )
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

    /** 二分找该时刻所属段的平移量 */
    private fun offsetAt(starts: List<Long>, offsets: List<Long>, t: Long): Long {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= t) lo = mid else hi = mid - 1
        }
        return offsets[lo]
    }

    // ---------- 扫描 ----------

    private fun scan(file: File, video: MutableList<PesRef>, audio: MutableList<PesRef>): Boolean {
        val buf = ByteArray(PACKET)
        var packet = 0
        FileInputStream(file).use { raw ->
            val input = raw.buffered(1 shl 20)
            while (readPacket(input, buf)) {
                if ((buf[0].toInt() and 0xFF) != SYNC) return false
                val ref = parsePes(buf, packet)
                if (ref != null) {
                    if (isVideoSid(ref.sid)) video.add(ref) else if (isAudioSid(ref.sid)) audio.add(ref)
                }
                packet++
            }
        }
        return true
    }

    private fun readPacket(input: InputStream, buf: ByteArray): Boolean {
        var read = 0
        while (read < PACKET) {
            val n = input.read(buf, read, PACKET - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    private fun parsePes(buf: ByteArray, packet: Int): PesRef? {
        if ((buf[1].toInt() and 0x40) == 0) return null
        val afc = (buf[3].toInt() shr 4) and 0x3
        var o = 4
        if ((afc and 0x2) != 0) o = 5 + (buf[4].toInt() and 0xFF)
        if ((afc and 0x1) == 0 || o + 19 >= PACKET) return null
        if (buf[o].toInt() != 0 || buf[o + 1].toInt() != 0 || buf[o + 2].toInt() != 1) return null
        val pid = ((buf[1].toInt() and 0x1F) shl 8) or (buf[2].toInt() and 0xFF)
        val sid = buf[o + 3].toInt() and 0xFF
        val kind = (buf[o + 7].toInt() shr 6) and 0x3
        if (kind < 2) return null
        val pts = decode(buf, o + 9)
        val hasDts = kind == 3
        val dts = if (hasDts) decode(buf, o + 14) else pts
        return PesRef(packet, pid, sid, o + 9, pts, dts, hasDts)
    }

    private fun medianFrameTicks(video: List<PesRef>): Long? {
        val diffs = ArrayList<Long>(minOf(video.size - 1, 4000))
        var prev = video[0].dts
        for (i in 1 until minOf(video.size, 4000)) {
            val d = video[i].dts - prev
            prev = video[i].dts
            if (d > 0) diffs.add(d)
        }
        if (diffs.isEmpty()) return null
        diffs.sort()
        val ticks = diffs[diffs.size / 2]
        return if (ticks > 0) ticks else null
    }

    // ---------- 重写 ----------

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
                while (readPacket(input, buf)) {
                    if (vi < video.size && video[vi].packet == packet) {
                        val ref = video[vi]
                        System.arraycopy(encode(vPts[vi]), 0, buf, ref.innerOffset, 5)
                        if (ref.hasDts) System.arraycopy(encode(vDts[vi]), 0, buf, ref.innerOffset + 5, 5)
                        vi++
                    } else if (ai < audio.size && audio[ai].packet == packet) {
                        System.arraycopy(encode(aPts[ai]), 0, buf, audio[ai].innerOffset, 5)
                        ai++
                    }
                    out.write(buf, 0, PACKET)
                    packet++
                }
            }
        }
    }

    // ---------- 自检 ----------

    /** 返回 null 表示通过，否则返回失败原因 */
    private fun verify(file: File, frameTicks: Long, videoCount: Int, audioCount: Int): String? {
        val video = ArrayList<PesRef>(1 shl 16)
        val audio = ArrayList<PesRef>(1 shl 14)
        if (!scan(file, video, audio)) return "包结构错乱"
        if (video.size != videoCount) return "视频 PES 数不符 " + video.size + "≠" + videoCount
        if (audio.size != audioCount) return "音频 PES 数不符 " + audio.size + "≠" + audioCount
        for (i in 1 until video.size) {
            val d = video[i].dts - video[i - 1].dts
            if (d <= 0L || d > frameTicks * 2) return "视频 DTS 间隔异常 " + d + " (基准 " + frameTicks + ")"
        }
        for (j in 1 until audio.size) {
            if (audio[j].pts <= audio[j - 1].pts) {
                return "音频 PTS 非单调 @" + j + " " + audio[j - 1].pts + "→" + audio[j].pts
            }
        }
        return null
    }

    // ---------- 工具 ----------

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
