package com.github.avbox.osc.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.avbox.osc.player.core.PlayState
import com.github.avbox.osc.player.gesture.VideoGestureActions
import com.github.avbox.osc.player.gesture.VideoGestureHandler
import com.github.avbox.osc.player.gesture.VideoGestureSession
import com.github.avbox.osc.player.state.PlayerActions
import com.github.avbox.osc.player.state.PlayerUiState
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把 ExoPlayer 的状态翻译成项目里那套 [PlayerUiState]，动作翻译成 [PlayerActions]。
 *
 * 这样 [PlayerTopBar]、[PlayerBottomBar]、[VideoGestureHandler] 可以原样复用：
 * 顶栏底栏的样式、手势的方向判定（左右进度 / 左侧亮度 / 右侧音量）、长按倍速
 * 全部是在线播放器那一套，不是另画一份。
 */
class LocalPlaybackHost(
    private val activity: Activity,
    private val player: ExoPlayer,
    private val callbacks: Callbacks,
) : PlayerActions, VideoGestureActions {

    interface Callbacks {
        fun onExit()
        fun onPrev()
        fun onNext()
        fun onEpisodes()
        fun onCast()
        fun onSwitch(index: Int)
    }

    val state = PlayerUiState()

    /** 选集面板的打开请求（界面层注册） */
    var onEpisodesRequested: (() -> Unit)? = null

    /** 手势方向判定、双击长按的时序全在这个 handler 里，和在线播放器同一份实现 */
    val gestureHandler = VideoGestureHandler(this)

    private val audioManager: AudioManager? =
        activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var systemBrightness = -1f
    /** 手势开始那一刻的亮度/音量；滑动给的是累计位移，必须相对起点算，不能逐帧叠加 */
    private var startBrightness = Float.NaN
    private var startVolume = Int.MIN_VALUE
    private var seekBase = 0L
    private var seekTarget = 0L
    private var hintAt = 0L
    private var controlsTouchedAt = 0L
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var lastSysSecond = -1L

    init {
        state.updatePreviewMode(false)
        state.updateShowParseRow(false)
        state.updateGestureSwitch(true)
        state.updateEnableInNormal(true)
        state.updateCanChangePosition(true)
        state.updateLocked(false)
        state.updateTopLeftVisible(true)
        state.updateTopRightVisible(true)
        state.updateNetSpeedTopRightVisible(false)
        state.updateControlsVisible(true)
    }

    // ---------- 状态同步 ----------

    fun sync() {
        val durationMs = player.duration.coerceAtLeast(0L)
        // PlayerUiState 的 duration/position 单位是毫秒（PlayerUtils.stringForTime 内部再除 1000）
        state.updateDuration(durationMs.toInt())
        if (!state.dragging) {
            state.updatePosition(player.currentPosition.coerceAtLeast(0L).toInt())
        }
        state.updateBufferedPercent(
            if (durationMs > 0L) {
                ((player.bufferedPosition.toFloat() / durationMs.toFloat()) * 100f).toInt().coerceIn(0, 100)
            } else {
                0
            },
        )
        state.updatePlayState(
            when {
                player.playbackState == Player.STATE_BUFFERING -> PlayState.BUFFERING
                player.playbackState == Player.STATE_ENDED -> PlayState.COMPLETED
                player.isPlaying -> PlayState.PLAYING
                player.playbackState == Player.STATE_READY -> PlayState.PAUSED
                player.playbackState == Player.STATE_IDLE -> PlayState.IDLE
                else -> PlayState.PREPARING
            },
        )
        val videoFormat = try {
            player.videoFormat
        } catch (th: Throwable) {
            null
        }
        if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
            state.updateVideoSize(videoFormat.width.toString() + " X " + videoFormat.height.toString())
            val shortSide = minOf(videoFormat.width, videoFormat.height)
            val quality = when {
                shortSide >= 2160 -> "4K"
                shortSide >= 1440 -> "2K"
                shortSide >= 1080 -> "1080P"
                shortSide >= 720 -> "720P"
                shortSide >= 480 -> "480P"
                else -> shortSide.toString() + "P"
            }
            state.updateVideoQuality(quality)
        }
        refreshSystemInfo()

        // 手势提示 1.2 秒后自动消失
        if ((state.seekHintVisible || state.slideHintVisible) && System.currentTimeMillis() - hintAt > 1200L) {
            state.updateSeekHintVisible(false)
            state.updateSlideHintVisible(false)
        }
        // 播放中且没人操作，4 秒后收起控制条
        if (state.controlsVisible && player.isPlaying &&
            System.currentTimeMillis() - controlsTouchedAt > 4000L
        ) {
            state.updateControlsVisible(false)
        }
    }

    override fun refreshSystemInfo() {
        val now = System.currentTimeMillis()
        if (now - lastSysSecond < 1000L) return
        lastSysSecond = now
        state.updateSysTime(timeFormat.format(Date(now)))
        try {
            val bm = activity.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                state.updateBatteryPercent(percent)
                state.updateBatteryCharging(bm.isCharging)
            }
        } catch (ignored: Throwable) {
        }
    }

    fun onTitleChanged(title: String) {
        state.updateTitle(title)
    }

    /** 手势开始时的环境快照：方向判定需要这些字段 */
    fun buildSession(width: Int, height: Int, downY: Float): VideoGestureSession {
        // 新一轮手势：重置亮度/音量基准
        startBrightness = Float.NaN
        startVolume = Int.MIN_VALUE
        val screenWidth = activity.resources.displayMetrics.widthPixels
        return VideoGestureSession(
            inPlayback = true,
            canChangePosition = true,
            enableInNormal = true,
            fullScreen = true,
            locked = false,
            previewMode = false,
            paused = !player.isPlaying,
            gestureEnabled = true,
            verticalSlidingDisabled = false,
            width = width,
            height = height,
            screenWidth = screenWidth,
            edge = downY < 0f,
        )
    }

    private fun touchControls(visible: Boolean = true) {
        controlsTouchedAt = System.currentTimeMillis()
        if (visible) state.updateControlsVisible(true)
    }

    // ---------- PlayerActions ----------

    override fun toggleControls() {
        val next = !state.controlsVisible
        controlsTouchedAt = System.currentTimeMillis()
        state.updateControlsVisible(next)
        hintAt = System.currentTimeMillis()
        state.updateSeekHintVisible(false)
        state.updateSlideHintVisible(false)
    }

    override fun keepControlsAlive() {
        controlsTouchedAt = System.currentTimeMillis()
    }

    override fun onNextClicked() = callbacks.onNext()

    override fun onPreClicked() = callbacks.onPrev()

    override fun onPlayPauseClicked() {
        touchControls()
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun onScaleClicked() = Unit

    override fun onScaleLongClicked() = Unit

    override fun onSpeedClicked() = Unit

    override fun onSpeedLongClicked() = Unit

    override fun onPlayerClicked() = toggleControls()

    override fun onPlayerLongClicked() = Unit

    override fun onTimeStartClicked() = Unit

    override fun onTimeStartLongClicked() = Unit

    override fun onTimeEndClicked() = Unit

    override fun onTimeEndLongClicked() = Unit

    override fun onTimeResetClicked() = Unit

    /** 仓库版 PlayerActions 带下载入口；本地播放页不需要，空实现 */
    override fun onDownloadClicked() = Unit

    override fun onEpisodeClicked() {
        onEpisodesRequested?.invoke()
    }

    override fun onCastClicked() = callbacks.onCast()

    override fun onSubtitleClicked() = Unit

    override fun onSubtitleLongClicked() = Unit

    override fun onAudioTrackClicked() = Unit

    override fun onVideoTrackClicked() = Unit

    override fun onDanmuSettingClicked() = Unit

    override fun onDanmuSettingLongClicked() = Unit

    override fun onDanmuSearchClicked() = Unit

    override fun onDanmuSearchLongClicked() = Unit

    override fun onRotateClicked() {
        if (activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onParamsClicked() = Unit

    override fun onInfoOsdClicked() = Unit

    override fun onBackClicked() = callbacks.onExit()

    override fun onLockClicked() = Unit

    override fun onParseSelected(position: Int) = Unit

    override fun onSeekStarted() {
        state.updateDragging(true)
        seekBase = player.currentPosition.coerceAtLeast(0L)
        seekTarget = seekBase
        state.updateSeekPreviewPositionMs(seekBase)
    }

    override fun onSeekPreview(progress: Int) {
        val durationMs = player.duration.coerceAtLeast(0L)
        if (durationMs <= 0L) return
        // 底部进度条的 progress 是 0..1000（PlayerBottomBar 的 SEEK_MAX）
        val target = (durationMs * progress.coerceIn(0, 1000) / 1000L)
        seekTarget = target
        state.updateSeekPreviewPositionMs(target)
        touchControls()
    }

    override fun onSeekFinished(progress: Int) {
        val durationMs = player.duration.coerceAtLeast(0L)
        val target = if (durationMs > 0L) durationMs * progress.coerceIn(0, 1000) / 1000L else seekTarget
        player.seekTo(target)
        state.updateDragging(false)
        state.updatePosition(target.toInt())
    }

    override fun onSeekCancelled() {
        state.updateDragging(false)
    }

    override fun onSeekStep(dir: Int) {
        val durationMs = player.duration.coerceAtLeast(0L)
        val target = (player.currentPosition + dir * 10_000L).coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
        player.seekTo(target)
        state.updatePosition(target.toInt())
        touchControls()
    }

    override fun onSeekRelative(deltaMs: Long) {
        val durationMs = player.duration.coerceAtLeast(0L)
        val target = (player.currentPosition + deltaMs).coerceIn(0L, if (durationMs > 0L) durationMs else Long.MAX_VALUE)
        player.seekTo(target)
        state.updatePosition(target.toInt())
        touchControls()
    }

    override fun hideSeekHint() {
        state.updateSeekHintVisible(false)
    }

    override fun hideSlideHint() {
        state.updateSlideHintVisible(false)
    }

    // ---------- VideoGestureActions ----------

    override fun inPlayback(): Boolean = true

    override fun onSingleTap() = toggleControls()

    override fun onDoubleTapTogglePlay() {
        touchControls()
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun onLongPressStart() {
        val speed = try {
            KV.get(KVKeys.LONG_PRESS_SPEED, KVKeys.LONG_PRESS_SPEED_DEFAULT).toFloat()
        } catch (th: Throwable) {
            KVKeys.LONG_PRESS_SPEED_DEFAULT.toFloat()
        }
        player.setPlaybackSpeed(speed)
        state.updateSpeedBoostValue(speed)
        state.updateSpeedBoostVisible(true)
        touchControls()
    }

    override fun onLongPressEnd() {
        player.setPlaybackSpeed(1f)
        state.updateSpeedBoostVisible(false)
    }

    override fun onSeekPreview(totalDeltaX: Float) {
        val durationMs = player.duration.coerceAtLeast(0L)
        if (durationMs <= 0L) return
        val width = maxOf(activity.resources.displayMetrics.widthPixels, 1)
        val span = 120_000.0
        val target = (seekBase + (totalDeltaX.toDouble() / width * span)).toLong().coerceIn(0L, durationMs)
        seekTarget = target
        state.updateDragging(true)
        state.updateSeekPreviewPositionMs(target)
        state.updateSeekHintForward(target >= seekBase)
        state.updateSeekHintText(fmtTime(target) + " / " + fmtTime(durationMs))
        state.updateSeekHintVisible(true)
        hintAt = System.currentTimeMillis()
        touchControls()
    }

    override fun onSeekCommit() {
        player.seekTo(seekTarget)
        state.updateDragging(false)
        state.updatePosition(seekTarget.toInt())
        state.updateSeekHintVisible(false)
    }

    override fun onSeekCancel() {
        state.updateDragging(false)
        state.updateSeekHintVisible(false)
    }

    override fun onBrightnessSlide(totalDeltaY: Float) {
        val height = maxOf(activity.resources.displayMetrics.heightPixels, 1)
        if (startBrightness.isNaN()) {
            if (systemBrightness < 0f) {
                systemBrightness = try {
                    Settings.System.getInt(
                        activity.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS,
                    ) / 255f
                } catch (th: Throwable) {
                    0.5f
                }
            }
            startBrightness = systemBrightness.coerceIn(0.01f, 1f)
        }
        // 滑满屏高 ≈ 变化 80%，不会一滑到底
        val target = (startBrightness - (totalDeltaY / height) * 0.8f).coerceIn(0.01f, 1f)
        try {
            val lp: WindowManager.LayoutParams = activity.window.attributes
            lp.screenBrightness = target
            activity.window.attributes = lp
        } catch (ignored: Throwable) {
        }
        state.updateSlideHintBrightness(true)
        state.updateSlideHintText((target * 100f).toInt().toString() + "%")
        state.updateSlideHintVisible(true)
        hintAt = System.currentTimeMillis()
        touchControls()
    }

    override fun onVolumeSlide(totalDeltaY: Float) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (startVolume == Int.MIN_VALUE) {
            startVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        val height = maxOf(activity.resources.displayMetrics.heightPixels, 1)
        // 滑满屏高 ≈ 音量走 80% 量程
        val target = (startVolume - (totalDeltaY / height) * max * 0.8f).toInt().coerceIn(0, max)
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (ignored: Throwable) {
        }
        state.updateSlideHintBrightness(false)
        state.updateSlideHintText((target * 100 / max).toString() + "%")
        state.updateSlideHintVisible(true)
        hintAt = System.currentTimeMillis()
        touchControls()
    }

    private fun fmtTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }
}
