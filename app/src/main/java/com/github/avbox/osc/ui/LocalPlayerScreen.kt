package com.github.avbox.osc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.ui.player.PlayerBottomBar
import com.github.avbox.osc.ui.player.PlayerTopBar
import com.github.avbox.osc.ui.player.videoGestureLayer
import kotlinx.coroutines.delay

/**
 * 本地播放界面：顶栏、底栏、手势层全部复用在线播放器那一套实现，
 * 只是把状态源从 PlaybackSession 换成 ExoPlayer（见 [LocalPlaybackHost]）。
 *
 * 选集面板按你的要求直接列 Download/AVBox 里的文件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalPlayerScreen(
    player: ExoPlayer,
    host: LocalPlaybackHost,
    videos: List<LocalVideo>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val state = host.state
    var showEpisodes by remember { mutableStateOf(false) }

    LaunchedEffect(player) {
        while (true) {
            host.sync()
            delay(300L)
        }
    }

    LaunchedEffect(currentIndex) {
        host.onTitleChanged(videos.getOrNull(currentIndex)?.name ?: "本地视频")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // 手势层：方向判定、双击/长按时序都是在线播放器那份 VideoGestureHandler
        Box(
            modifier = Modifier
                .fillMaxSize()
                .videoGestureLayer(
                    handler = host.gestureHandler,
                    sessionProvider = { size, downY ->
                        host.buildSession(size.width, size.height, downY)
                    },
                    onTapPending = { },
                ),
        )

        if (state.controlsVisible) {
            Box(modifier = Modifier.align(Alignment.TopCenter)) {
                PlayerTopBar(state, host)
            }
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                PlayerBottomBar(state, host)
            }
        }

        // 手势提示
        if (state.seekHintVisible) {
            HintText(state.seekHintText, Modifier.align(Alignment.Center))
        }
        if (state.slideHintVisible) {
            HintText(state.slideHintText, Modifier.align(Alignment.Center))
        }
        if (state.speedBoostVisible) {
            HintText(fmtSpeed(state.speedBoostValue) + " 倍速", Modifier.align(Alignment.Center))
        }
    }

    if (showEpisodes) {
        EpisodesSheet(
            videos = videos,
            currentIndex = currentIndex,
            onDismiss = { showEpisodes = false },
            onSelect = { i ->
                showEpisodes = false
                onSelect(i)
            },
        )
    }

    // 选集按钮走 PlayerActions.onEpisodeClicked，这里把请求接回来打开面板
    LaunchedEffect(host) {
        host.onEpisodesRequested = { showEpisodes = true }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodesSheet(
    videos: List<LocalVideo>,
    currentIndex: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "共 " + videos.size + " 个文件",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
        )
        LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
            itemsIndexed(videos) { i, video: LocalVideo ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = if (i == currentIndex) "▶ " + video.name else video.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (i == currentIndex) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    supportingContent = { Text(fmtSize(video.size)) },
                    modifier = Modifier.clickable { onSelect(i) },
                )
            }
        }
    }
}

@Composable
private fun HintText(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private fun fmtSpeed(speed: Float): String {
    return if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString() + "x"
    } else {
        String.format("%.1fx", speed)
    }
}

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
