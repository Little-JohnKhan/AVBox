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
import com.github.avbox.osc.player.download.LocalLibrary
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.ui.base.BaseActivity
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

            override fun onCast() {
                Toast.makeText(this@LocalPlayActivity, "本地文件投屏下个版本接入", Toast.LENGTH_SHORT).show()
            }

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
            }
        }
        AppLog.i("echo-local-play: " + path + " size=" + file.length() + " files=" + videos.size)
    }

    private fun switchTo(target: Int) {
        if (target < 0 || target >= videos.size) return
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
