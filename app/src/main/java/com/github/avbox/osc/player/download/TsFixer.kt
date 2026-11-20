package com.github.avbox.osc.player.download

import com.github.avbox.osc.util.AppLog
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * TS 时间轴修复器（带自检：修不好就绝不动文件）。
 *
 * 源站分片偶尔残缺（片内只有 1 帧视频、没有音频），拼成整片时 PTS 会留空洞。
 * MP4 用「样本时长」而非绝对时间戳，空洞会变成「一帧显示 2 秒」（画面定格），
 * 音频样本又是连续排的、少了同样时长，于是越播越超前。
 *
 * 修法：只改 PES 头里的 PTS/DTS，不动码流。
 *   视频：第 i 个 PES 的新 DTS = base + i × 帧时长，PTS 保留 (PTS − DTS) 偏移，B 帧结构不变；
 *   音频：落在空洞区间内的 PES 整段换成 null 包（丢弃），其余原 PTS 减去累计空洞。
 *
 * 修完必须通过自检才替换原文件：视频 DTS 严格递增且间隔恒定、音频 PTS 严格递增、
 * PES 数量对得上。任何一条不满足就删临时文件、原 ts 原封不动 ——
 * 宁可音画不同步，也不能产出打不开的文件。
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
        val audioDropped: Int,
        val frameMs: Double,
        val gaps: Int,
        val maxGapMs: Long,
        val fixed: Boolean,
        val note: String = "",
    ) {
        override fun toString(): String =
            "video=" + videoPes + " audio=" + audioPes + " audioDropped=" + audioDropped +
                " frameMs=" + String.format(java.util.Locale.US, "%.1f", frameMs) +
                " gaps=" + gaps + " maxGap=" + maxGapMs + "ms fixed=" + fixed +
                (if (note.isEmpty()) "" else " note=" + note)
    }

    private class PesRef(
        val packet: Int,
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
            val diffs = ArrayList<Long>(minOf(video.size - 1, 4000))
            var prev = video[0].dts
            for (i in 1 until minOf(video.size, 4000)) {
                val d = video[i].dts - prev
                prev = video[i].dts
                if (d > 0) diffs.add(d)
            }
            val mode = diffs.groupingBy { it }.eachCount().maxByOrNull { it.value }?.value ?: 0
            if (diffs.isEmpty() || mode * 100 / diffs.size < 70) {
                AppLog.i(TAG + " 放弃：帧时长不稳定（众数占比 " + (if (diffs.isEmpty()) 0 else mode * 100 / diffs.size) + "%）")
                return null
            }

            // 视频空洞：[前帧结束, 下一帧开始)
            val gaps = ArrayList<LongArray>()
            for (i in 1 until video.size) {
                val d = video[i].dts - video[i - 1].dts
                if (d > frameTicks * 3 / 2) gaps.add(longArrayOf(video[i - 1].dts + frameTicks, video[i].dts))
            }
            var maxGap = 0L
            for (g in gaps) maxGap = maxOf(maxGap, (g[1] - g[0]) / 90L)

            // 时间轴本来就连续：不重写，省掉一次整片读写
            if (maxGap < 200L) {
                AppLog.i(TAG + " 无需修复：时间轴连续 video=" + video.size + " audio=" + audio.size)
                return Stat(video.size, audio.size, 0, frameTicks / 90.0, gaps.size, maxGap, false, "时间轴连续")
            }

            val vBase = video[0].dts
            val vPts = LongArray(video.size)
            val vDts = LongArray(video.size)
            for (i in video.indices) {
                val nd = vBase + i.toLong() * frameTicks
                vDts[i] = nd
                vPts[i] = nd + (video[i].pts - video[i].dts)
            }

            val aPts = LongArray(audio.size)
            val aDrop = BooleanArray(audio.size)
            var dropped = 0
            var lastKept = Long.MIN_VALUE
            for (j in audio.indices) {
                val t = audio[j].pts
                if (inGap(gaps, t)) {
                    aDrop[j] = true
                    dropped++
                    continue
                }
                var v = t - gapBefore(gaps, t)
                // 双保险：无论如何保持严格递增，绝不产出非单调时间戳
                if (v <= lastKept) v = lastKept + 1
                aPts[j] = v
                lastKept = v
            }

            val tmpFile = File(file.parentFile, file.name + ".fixtmp")
            tmp = tmpFile
            rewrite(file, tmpFile, video, vPts, vDts, audio, aPts, aDrop)

            val problem = verify(tmpFile, frameTicks, video.size, audio.size - dropped)
            if (problem != null) {
                AppLog.e(TAG + " 自检不通过，放弃修复：" + problem)
                tmpFile.delete()
                tmp = null
                return Stat(video.size, audio.size, dropped, frameTicks / 90.0, gaps.size, maxGap, false, "自检失败:" + problem)
            }

            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
            tmp = null

            val stat = Stat(video.size, audio.size, dropped, frameTicks / 90.0, gaps.size, maxGap, true)
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
        val sid = buf[o + 3].toInt() and 0xFF
        val kind = (buf[o + 7].toInt() shr 6) and 0x3
        if (kind < 2) return null
        val pts = decode(buf, o + 9)
        val hasDts = kind == 3
        val dts = if (hasDts) decode(buf, o + 14) else pts
        return PesRef(packet, sid, o + 9, pts, dts, hasDts)
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
        aDrop: BooleanArray,
    ) {
        val buf = ByteArray(PACKET)
        val nullPacket = ByteArray(PACKET)
        nullPacket[0] = 0x47
        nullPacket[1] = 0x1F
        nullPacket[2] = 0xFF.toByte()
        nullPacket[3] = 0x10
        java.util.Arrays.fill(nullPacket, 4, PACKET, 0xFF.toByte())

        var packet = 0
        var vi = 0
        var ai = 0
        var nullUntil = -1
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
                        if (aDrop[ai]) {
                            nullUntil = if (ai + 1 < audio.size) audio[ai + 1].packet else Int.MAX_VALUE
                        } else {
                            System.arraycopy(encode(aPts[ai]), 0, buf, audio[ai].innerOffset, 5)
                        }
                        ai++
                    }
                    if (packet < nullUntil) out.write(nullPacket, 0, PACKET) else out.write(buf, 0, PACKET)
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
            if (d != frameTicks) return "视频 DTS 间隔异常 " + d + "≠" + frameTicks
        }
        for (j in 1 until audio.size) {
            if (audio[j].pts <= audio[j - 1].pts) {
                return "音频 PTS 非单调 @" + j + " " + audio[j - 1].pts + "→" + audio[j].pts
            }
        }
        return null
    }

    // ---------- 工具 ----------

    private fun inGap(gaps: List<LongArray>, t: Long): Boolean {
        for (g in gaps) if (t >= g[0] && t < g[1]) return true
        return false
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
