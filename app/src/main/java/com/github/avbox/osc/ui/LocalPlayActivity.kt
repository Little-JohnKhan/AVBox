package com.github.avbox.osc.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.avbox.osc.ui.base.BaseActivity
import com.github.avbox.osc.util.AppLog

import java.io.File

/**
 * 极简本地播放页：给一个本地文件路径就播，不联网、不查详情、不依赖任何播放会话。
 *
 * 存在的理由：详情页那套播放器要先拉剧集详情才能起播，断网时连详情页都进不去，
 * 下载好的文件根本没法播。这里绕开整条网络链路，只保留「文件 → 播放器」。
 *
 * 初始化写在 [init]（BaseActivity.onCreate 里回调）之后，不覆盖 onCreate。
 */
class LocalPlayActivity : BaseActivity() {

    private var player: ExoPlayer? = null

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

        val view = PlayerView(this)
        view.setShowNextButton(false)
        view.setShowPreviousButton(false)
        view.setShowFastForwardButton(false)
        view.setShowRewindButton(false)
        // BaseActivity 已经把 compose_view 设为内容视图，这里直接换成播放器视图
        setContentView(view)

        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        val exo = ExoPlayer.Builder(this, renderers).build()
        player = exo
        view.player = exo
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                AppLog.e("LocalPlayActivity", "play failed " + error.errorCodeName, error)
                Toast.makeText(this@LocalPlayActivity, "播放失败：" + error.errorCodeName, Toast.LENGTH_LONG).show()
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        AppLog.i("echo-local-play: " + path + " size=" + file.length())
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
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
