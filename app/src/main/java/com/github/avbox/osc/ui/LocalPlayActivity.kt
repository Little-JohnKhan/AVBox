package com.github.avbox.osc.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.github.avbox.osc.R
import com.github.avbox.osc.bean.CastVideo
import com.github.avbox.osc.player.cast.LocalCastServer
import com.github.avbox.osc.player.download.LocalLibrary
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.player.state.CastSheetState
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.ui.player.CastSheet
import com.github.avbox.osc.util.AppLog

import java.io.File

/**
 * 本地播放页：给一个本地文件路径就播，不联网、不查详情。
 *
 * 界面与手势复用在线播放器那套组件（顶栏 / 底栏 / VideoGestureHandler），
 * 状态由 [LocalPlaybackHost] 从 ExoPlayer 翻译过去。
 */
class LocalPlayActivity : BaseActivity() {

    private var player: ExoPlayer? = null
    private var host: LocalPlaybackHost? = null
    private var videos: List<LocalVideo> = emptyList()
    private var index by mutableStateOf(0)

    /** 投屏设备选择面板；非空时显示 */
    private var castSheet by mutableStateOf<CastSheetState?>(null)

    override fun init() {
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        if (path.isEmpty()) {
            Toast.makeText(this, "没有指定视频文件", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            Toast.makeText(this, "文件不存在或为空：\n" + path, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 同目录文件列表：上一集/下一集/选集都基于它（自然排序由 LocalLibrary 保证）
        videos = LocalLibrary.scan()
        val found = videos.indexOfFirst { it.path == path }
        index = if (found >= 0) found else 0

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers).build()
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("LocalPlayActivity", "play failed " + error.errorCodeName, error)
                Toast.makeText(this@LocalPlayActivity, "播放失败：" + error.errorCodeName, Toast.LENGTH_LONG).show()
            }
        })

        val playbackHost = LocalPlaybackHost(this, exo, object : LocalPlaybackHost.Callbacks {
            override fun onExit() = finish()

            override fun onPrev() = switchTo(index - 1)

            override fun onNext() = switchTo(index + 1)

            override fun onEpisodes() = Unit

            override fun onCast() = startCast()

            override fun onSwitch(index: Int) = switchTo(index)
        })
        host = playbackHost

        playAt(index)

        val composeHost = findViewById<ComposeView>(R.id.compose_view) ?: return
        composeHost.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LocalPlayerScreen(
                    player = exo,
                    host = playbackHost,
                    videos = videos,
                    currentIndex = index,
                    onSelect = { switchTo(it) },
                )
                val sheet = castSheet
                if (sheet != null) {
                    CastSheet(sheet) {
                        castSheet = null
                        // 关掉面板就撤服务：没必要让电视一直能拉到这个文件
                        LocalCastServer.stop()
                    }
                }
            }
        }
        AppLog.i("echo-local-play: " + path + " size=" + file.length() + " files=" + videos.size)
    }

    /**
     * 本地投屏：先在局域网里把当前文件暴露成 http URL，再交给 DLNA 电视去拉。
     *
     * 电视不认识 file:// 路径，这一步绕不开；服务只对当前文件开白名单，
     * 面板关闭即停服。
     */
    private fun startCast() {
        val video = videos.getOrNull(index) ?: return
        val url = LocalCastServer.start(video.file)
        if (url.isNullOrEmpty()) {
            Toast.makeText(this, "获取局域网地址失败，请确认已连接 Wi-Fi", Toast.LENGTH_LONG).show()
            return
        }
        val position = player?.currentPosition ?: 0L
        castSheet = CastSheetState(CastVideo(url, video.name, null, position)) {
            // 电视接管播放，本机停下，避免两边同时出声
            player?.pause()
        }
    }

    private fun switchTo(target: Int) {
        if (target < 0 || target >= videos.size) return
        // 换集就撤掉投屏：旧 URL 指向的是上一集的文件，留着只会让电视拉到错内容
        if (castSheet != null) {
            castSheet = null
            LocalCastServer.stop()
        }
        index = target
        playAt(target)
    }

    private fun playAt(target: Int) {
        val video = videos.getOrNull(target) ?: return
        val exo = player ?: return
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(video.file)))
        exo.prepare()
        exo.playWhenReady = true
        AppLog.i("echo-local-play-switch: " + target + " " + video.name)
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        LocalCastServer.stop()
        castSheet = null
        player?.release()
        player = null
        host = null
        super.onDestroy()
    }

    companion object {

        private const val EXTRA_PATH = "local_path"

        fun start(context: Context, path: String) {
            context.startActivity(
                Intent(context, LocalPlayActivity::class.java).apply {
                    putExtra(EXTRA_PATH, path)
                },
            )
        }
    }
}
