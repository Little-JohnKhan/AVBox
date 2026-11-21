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

            // ---- 音画起点对齐（头部多余音频）
            //
            // 实测现象：源把开头的广告**画面**删了（清单里视频分片没了），音频分片却保留。
            // 拼成 ts 后视频首帧 PTS ≈ 15s、音频首帧 PTS ≈ 0s；转 mp4 时两条轨各自被
            // 归一化到 0，于是整片音画差 15 秒，开头那段还只有广告声。
            // 解法：把「视频首帧之前」的音频整 PES 从文件里剔除（不是填空包），
            // 让两条轨从同一时刻开始。
            val vFirstPts = video[0].pts
            var aSkipTicks = Long.MIN_VALUE
            if (audio.isNotEmpty() && audio[0].pts + 90000L * 2 < vFirstPts) {
                aSkipTicks = vFirstPts
            }
            var aSkipped = 0
            if (aSkipTicks != Long.MIN_VALUE) {
                // 不能用 break 提前收尾：音频 PTS 在同段内可能有毫秒级抖动，
                // 逐个计数才能和 rewrite 里的剔除条件严格对应
                for (j in audio.indices) {
                    if (audio[j].pts < aSkipTicks) aSkipped++
                }
                // 极端情况：整条音频都在视频首帧之前（没有可用音频），放弃剔除
                if (aSkipped >= audio.size) {
                    aSkipTicks = Long.MIN_VALUE
                    aSkipped = 0
                }
            }
            if (aSkipped > 0) {
                AppLog.i(
                    TAG + " 头部音频剔除 " + aSkipped + "/" + audio.size +
                        " 视频首帧=" + (vFirstPts / 90000) + "s 音频首帧=" + (audio[0].pts / 90000) + "s",
                )
            }

            // ---- 音频：按音频自己的回退点切段，段号与视频段号一一对应
            //
            // 只能用段号对应，不能"按时间戳去视频段表里落段"：音频的重置点通常比视频
            // 早几十毫秒，一落到上一段就拿到 offset=0，再被单调保护压成"每帧 +1 tick"，
            // 整段音频被压扁（实测表现为正片中途开始音画不同步、声音挤成一片）。
            //
            // 刻意不做"裁剪超出视频终点的音频"：把音频 PES 换成 null 包会在音频轨上
            // 打出一串空洞，mp4 转换后 edit list / duration 错乱，播放器 seek 直接
            // 跳到片尾、画面定格只剩广告声（实测比拖音更糟）。源过滤对片尾广告是
            // 主动不处理（不影响观感），保持原样即可。
            val aGaps = ArrayList<Int>()
            for (j in 1 until audio.size) {
                val d = audio[j].dts - audio[j - 1].dts
                if (d <= 0L || d > frameTicks * 3 / 2) aGaps.add(j)
            }
            val bySegment = aGaps.size == gapIndexes.size
            if (!bySegment) {
                AppLog.i(TAG + " 音频段数(" + aGaps.size + ")与视频段数(" + gapIndexes.size + ")不一致，回落按时间戳落段")
            }
            val aPts = LongArray(audio.size)
            var lastA = Long.MIN_VALUE
            var aseg = 0
            for (j in audio.indices) {
                if (aseg < aGaps.size && aGaps[aseg] == j) {
                    aseg++
                    lastA = Long.MIN_VALUE   // 换段：单调保护必须重置，否则整段被压到上一段末尾
                }
                val off =
                    if (bySegment) segOffset[minOf(aseg, segOffset.size - 1)]
                    else offsetAt(segStart, segOffset, audio[j].pts)
                var v = audio[j].pts + off
                if (v <= lastA) v = lastA + 1
                aPts[j] = v
                lastA = v
            }
            if (bySegment && aGaps.isNotEmpty()) {
                AppLog.i(TAG + " 音频分段对齐 segs=" + (aGaps.size + 1))
            }

            val tmpFile = File(file.parentFile, file.name + ".fixtmp")
            tmp = tmpFile
            rewrite(file, tmpFile, video, vPts, vDts, audio, aPts, aSkipTicks)

            val problem = verify(tmpFile, frameTicks, video.size, audio.size - aSkipped)
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

            // 视频映射：分片内保留相对偏移，分片间接续。
            //
            // 关键：每片强制「新 DTS ≥ 上一帧 DTS + 一帧」。
            // 清单的 EXTINF 是四舍五入的，23.976fps 下 2.0s 的片实际有 48 帧
            // （2.002s），347 个片累积下来每个边界都会和上一片重叠 2ms；
            // 不夹紧的话自检的「DTS 必须递增」会直接判失败，重建等于白做。
            val vPts = LongArray(video.size)
            val vDts = LongArray(video.size)
            var vi = 0
            var lastVDts = Long.MIN_VALUE
            for (ref in video) {
                val seg = segOf(starts, ref.packet.toLong())
                val base = if (seg in segStart.indices) segStart[seg] else 0L
                val f = if (seg in segStart.indices) firstV[seg] else null
                var nd = if (f == null) ref.dts else base + (ref.dts - f[1])
                if (lastVDts != Long.MIN_VALUE && nd < lastVDts + frameTicks) nd = lastVDts + frameTicks
                vDts[vi] = nd
                // CTS 偏移（pts-dts）原样保留：B 帧的重排关系不能动，否则解码顺序会乱
                vPts[vi] = nd + (ref.pts - ref.dts)
                lastVDts = nd
                vi++
            }

            // 缺帧统计：源删内容只会整片删，缺口必然落在分片边界上。
            // 这里把「实际帧数明显少于清单时长应有帧数」的片列出来，
            // 日志里的秒数可以直接拿去跳着看。
            run {
                val expFrames = DoubleArray(segmentPackets.size) {
                    durations[it] * 90000.0 / frameTicks
                }
                val gotFrames = IntArray(segmentPackets.size)
                for (ref in video) {
                    val seg = segOf(starts, ref.packet.toLong())
                    if (seg in gotFrames.indices) gotFrames[seg]++
                }
                var bad = 0
                val detail = StringBuilder()
                for (i in gotFrames.indices) {
                    if (expFrames[i] - gotFrames[i] > 2.0) {
                        bad++
                        if (bad <= 12) {
                            val sec = segStart[i] / 90000
                            detail.append(' ').append(sec / 60).append(':')
                                .append(String.format(java.util.Locale.US, "%02d", sec % 60))
                                .append('(').append(gotFrames[i]).append('/')
                                .append(Math.round(expFrames[i])).append(')')
                        }
                    }
                }
                DownloadLog.write("rebuild 缺帧分片 n=" + bad + detail)
            }

            // 音频映射：分片内有音频起点就用它做基准；没有的话按上一条音频的
            // 实际增量推进（绝不套用别的分片的基准，否则整片会被压成 1 tick 一条）
            val aDiffs = ArrayList<Long>()
            for (i in 1 until audio.size) {
                val d = audio[i].pts - audio[i - 1].pts
                if (d > 0) aDiffs.add(d)
            }
            aDiffs.sort()
            val aMed = if (aDiffs.isEmpty()) 3000L else aDiffs[aDiffs.size / 2]
            val aPts = LongArray(audio.size)
            var ai = 0
            var lastA = Long.MIN_VALUE
            for (ref in audio) {
                val seg = segOf(starts, ref.packet.toLong())
                val f = if (seg in segStart.indices) firstA[seg] else null
                var v =
                    if (f != null) segStart[seg] + (ref.pts - f)
                    else (if (lastA == Long.MIN_VALUE) ref.pts else lastA + aMed)
                if (lastA != Long.MIN_VALUE && v <= lastA) v = lastA + 1
                aPts[ai] = v
                lastA = v
                ai++
            }

            val tmpFile = File(file.parentFile, file.name + ".rb")
            tmp = tmpFile
            rewrite(file, tmpFile, video, vPts, vDts, audio, aPts, Long.MIN_VALUE)

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
            // 只要求递增。分片边界出现大间隔是「源删过画面」的真实反映，
            // 时间轴仍以 EXTINF 为准，硬判失败等于把能用的重建结果丢掉。
            if (d <= 0L) return "视频 DTS 非递增 " + d
        }
        for (j in 1 until audio.size) {
            if (audio[j].pts < audio[j - 1].pts) return "音频 PTS 非单调 @" + j
        }
        val span = video.last().dts - video.first().dts + frameTicks
        // 容差放宽到 10% + 3s：内容帧数 × 帧间隔与 EXTINF 四舍五入后的总和
        // 本来就差几秒（23.976fps 尤其明显），卡 2 秒会误杀正常结果
        val tol = maxOf(90000L * 3, expectSpan / 10)
        if (Math.abs(span - expectSpan) > tol) {
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
        aSkipTicks: Long,
    ) {
        val buf = ByteArray(PACKET)
        val audioPid = audio.firstOrNull()?.pid ?: -1
        var skipUntil = -1
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
                        if (aSkipTicks != Long.MIN_VALUE && audio[ai].pts < aSkipTicks) {
                            // 视频首帧之前的声音（源删了画面没删声音）：整段音频包不写入文件
                            skipUntil = if (ai + 1 < audio.size) audio[ai + 1].packet else Int.MAX_VALUE
                        } else {
                            System.arraycopy(encode(aPts[ai]), 0, buf, audio[ai].innerOffset, 5)
                        }
                        ai++
                    }
                    if (packet < skipUntil) {
                        val pid = ((buf[1].toInt() and 0x1F) shl 8) or (buf[2].toInt() and 0xFF)
                        // 只剔除音频 PID 的包；视频包和数据交错在一起，不能一起抹
                        if (pid != audioPid) out.write(buf, 0, PACKET)
                    } else {
                        out.write(buf, 0, PACKET)
                    }
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
