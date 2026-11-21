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

            // 跨度合理性检查：PTS 跨度必须约等于「帧数 × 帧时长」。
            // 实测有源在清单中途让 PTS 回退（分片被拼了两遍/两段独立编码），
            // 跨度是内容量的两倍（2731s vs 1388s）。这种流「按段平移」会把重复内容接到后面，
            // 出来就是时长翻倍、播放错乱的坏文件，所以宁可不动。
            val rawSpan = video.last().dts - video.first().dts
            val expectSpan = video.size.toLong() * frameTicks
            if (rawSpan > expectSpan * 13 / 10) {
                AppLog.e(
                    TAG + " 放弃：时间轴跨度异常 raw=" + (rawSpan / 90000) + "s expect=" +
                        (expectSpan / 90000) + "s frames=" + video.size,
                )
                return Stat(
                    video.size, audio.size, gapIndexes.size + 1, frameTicks / 90.0,
                    gapIndexes.size, maxGap, false,
                    "源时间轴跨度异常（" + (rawSpan / 90000) + "s vs 内容 " + (expectSpan / 90000) + "s），跳过修复",
                )
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

    /**
     * 按清单的 EXTINF 重建时间轴（解决「内容 23 分钟、PTS 跨度 45 分钟」这类拼接流）。
     *
     * 原理与 IDM 合并 mp4 一致：**不信任原始 PTS，只按分片顺序和每片时长重新排**。
     *   - 分片 i 的起始时间 = 前面所有 EXTINF 之和
     *   - 分片内：newPts = 分片起始 + (原Pts − 该片首帧原Pts)，段内节奏完全保留
     *   - 音频同理，用音频自己在同一分片的首个 PES 作为基准
     *
     * @param durations       每个分片的 EXTINF（秒），长度必须等于分片数
     * @param segmentPackets  每个分片包含的 TS 包数，用来把 PES 归到分片
     *
     * 修完必过自检：帧数不变、DTS 递增、跨度 ≈ EXTINF 总和、音频 PTS 单调。不过就保留原文件。
     */
    fun rebuildByExtinf(file: File, durations: List<Double>, segmentPackets: List<Int>): Stat? {
        var tmp: File? = null
        return try {
            if (durations.isEmpty() || durations.size != segmentPackets.size) return null
            val size = file.length()
            if (size < PACKET.toLong() * 100L || size % PACKET != 0L) return null

            val video = ArrayList<PesRef>(1 shl 16)
            val audio = ArrayList<PesRef>(1 shl 14)
            if (!scan(file, video, audio)) return null
            if (video.size < 50) return null
            val frameTicks = medianFrameTicks(video) ?: return null

            // 分片边界（包序号）
            val starts = LongArray(segmentPackets.size + 1)
            for (i in segmentPackets.indices) starts[i + 1] = starts[i] + segmentPackets[i]
            val totalPackets = starts[segmentPackets.size]
            if (totalPackets <= 0L) return null

            // 每片起始时间（ticks）
            val segStart = LongArray(segmentPackets.size)
            var acc = 0.0
            for (i in durations.indices) {
                segStart[i] = Math.round(acc * 90000.0)
                acc += durations[i]
            }
            val expectSpan = Math.round(acc * 90000.0)

            // 每片首帧基准
            val firstV = HashMap<Int, LongArray>()   // seg -> (pts, dts)
            for (ref in video) {
                val seg = segOf(starts, ref.packet.toLong())
                if (seg < 0 || seg >= segmentPackets.size) continue
                if (!firstV.containsKey(seg)) firstV[seg] = longArrayOf(ref.pts, ref.dts)
            }
            val firstA = HashMap<Int, Long>()
            for (ref in audio) {
                val seg = segOf(starts, ref.packet.toLong())
                if (seg < 0 || seg >= segmentPackets.size) continue
                if (!firstA.containsKey(seg)) firstA[seg] = ref.pts
            }

            val vPts = LongArray(video.size)
            val vDts = LongArray(video.size)
            var vi = 0
            for (ref in video) {
                val seg = segOf(starts, ref.packet.toLong())
                val base = if (seg in segStart.indices) segStart[seg] else 0L
                val f = firstV[seg]
                if (f == null) {
                    vPts[vi] = ref.pts
                    vDts[vi] = ref.dts
                } else {
                    vPts[vi] = base + (ref.pts - f[0])
                    vDts[vi] = base + (ref.dts - f[1])
                }
                vi++
            }

            // 音频：没有基准的分片沿用最近一个有效基准
            val aPts = LongArray(audio.size)
            var ai = 0
            var lastSegWithBase = -1
            for (ref in audio) {
                val seg = segOf(starts, ref.packet.toLong())
                if (firstA.containsKey(seg)) lastSegWithBase = seg
                val baseSeg = if (firstA.containsKey(seg)) seg else lastSegWithBase
                if (baseSeg >= 0 && baseSeg < segStart.size) {
                    val f = firstA[baseSeg] ?: ref.pts
                    aPts[ai] = segStart[baseSeg] + (ref.pts - f)
                } else {
                    aPts[ai] = ref.pts
                }
                ai++
            }
            // 音频强制单调，避免基准切换造成的微小回退
            for (j in 1 until aPts.size) if (aPts[j] <= aPts[j - 1]) aPts[j] = aPts[j - 1] + 1

            val tmpFile = File(file.parentFile, file.name + ".rb")
            tmp = tmpFile
            rewrite(file, tmpFile, video, vPts, vDts, audio, aPts)

            val problem = verifySpan(tmpFile, frameTicks, video.size, audio.size, expectSpan)
            if (problem != null) {
                AppLog.e(TAG + " 重建自检不通过，放弃：" + problem)
                tmpFile.delete()
                tmp = null
                return Stat(
                    video.size, audio.size, segmentPackets.size, frameTicks / 90.0,
                    0, 0, false, "重建自检失败:" + problem,
                )
            }
            if (!tmpFile.renameTo(file)) {
                tmpFile.copyTo(file, overwrite = true)
                tmpFile.delete()
            }
            tmp = null
            val stat = Stat(
                video.size, audio.size, segmentPackets.size, frameTicks / 90.0,
                0, 0, true, "按EXTINF重建",
            )
            AppLog.i(TAG + " 重建完成 " + stat.toString() + " file=" + file.name)
            stat
        } catch (th: Throwable) {
            AppLog.e(TAG + " 重建失败: " + th.message)
            try {
                tmp?.delete()
            } catch (ignored: Throwable) {
            }
            null
        }
    }

    /** PES 所在的包序号属于哪个分片，找不到返回 -1 */
    private fun segOf(starts: LongArray, packet: Long): Int {
        var lo = 0
        var hi = starts.size - 2
        if (hi < 0) return -1
        if (packet < starts[0] || packet >= starts[hi + 1]) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= packet) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** 重建后的自检：帧数、DTS 递增、跨度必须对上 EXTINF 总和、音频单调 */
    private fun verifySpan(
        file: File,
        frameTicks: Long,
        videoCount: Int,
        audioCount: Int,
        expectSpan: Long,
    ): String? {
        val video = ArrayList<PesRef>(1 shl 16)
        val audio = ArrayList<PesRef>(1 shl 14)
        if (!scan(file, video, audio)) return "包结构错乱"
        if (video.size != videoCount) return "视频 PES 数不符 " + video.size + "≠" + videoCount
        if (audio.size != audioCount) return "音频 PES 数不符 " + audio.size + "≠" + audioCount
        for (i in 1 until video.size) {
            val d = video[i].dts - video[i - 1].dts
            if (d <= 0L || d > frameTicks * 2) return "视频 DTS 间隔异常 " + d
        }
        for (j in 1 until audio.size) {
            if (audio[j].pts < audio[j - 1].pts) return "音频 PTS 非单调 @" + j
        }
        val span = video.last().dts - video.first().dts + frameTicks
        val diff = Math.abs(span - expectSpan)
        if (diff > 90000L * 2) {
            return "跨度不符 span=" + (span / 90000) + "s expect=" + (expectSpan / 90000) + "s"
        }
        return null
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
        // 时间跨度也要对得上：视频跨度不能明显超过「帧数 × 帧时长」，
        // 否则说明段平移把重复内容接成了双倍时长（会产出播放错乱的坏文件）
        val span = video.last().dts - video.first().dts
        val expect = video.size.toLong() * frameTicks
        if (span > expect * 13 / 10) {
            return "时间跨度异常 span=" + (span / 90000) + "s expect=" + (expect / 90000) + "s"
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
