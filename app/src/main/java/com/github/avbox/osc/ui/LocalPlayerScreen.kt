package com.github.avbox.osc.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.ui.player.videoGestureLayer
import kotlinx.coroutines.delay

/**
 * 本地播放界面。
 *
 * 手势层直接用项目的 [videoGestureLayer] / VideoGestureHandler（左右进度、左侧亮度、
 * 右侧音量、双击播放暂停、长按倍速），UI 只保留需要的几个入口：
 * 关闭、投屏、上一集、播放暂停、下一集、选集。选集是右侧半遮板的网格。
 */
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
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    // 单击要等双击超时后才能确认，这是 videoGestureLayer 的约定
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val tapConfirm = remember {
        Runnable { host.gestureHandler.markSingleTapConfirmed() }
    }
    DisposableEffect(Unit) {
        onDispose { mainHandler.removeCallbacks(tapConfirm) }
    }

    LaunchedEffect(player) {
        while (true) {
            host.sync()
            delay(300L)
        }
    }

    LaunchedEffect(currentIndex) {
        host.onTitleChanged(videos.getOrNull(currentIndex)?.name ?: "本地视频")
    }

    val durationMs = player.duration.coerceAtLeast(0L)
    val positionMs = player.currentPosition.coerceAtLeast(0L)

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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .videoGestureLayer(
                    handler = host.gestureHandler,
                    sessionProvider = { size, downY ->
                        host.buildSession(size.width, size.height, downY)
                    },
                    onTapPending = {
                        mainHandler.removeCallbacks(tapConfirm)
                        mainHandler.postDelayed(tapConfirm, host.gestureHandler.doubleTapTimeoutMs)
                    },
                ),
        )

        if (state.controlsVisible && !showEpisodes) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)),
                    )
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { host.onBackClicked() }) {
                    Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOf(state.videoSize, state.videoQuality)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = Color(0x99FFFFFF),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { host.onCastClicked() }) {
                    Text("投屏", color = Color.White)
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                    )
                    .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
            ) {
                Text(
                    text = fmtTime(if (dragging) (dragValue * durationMs).toLong() else positionMs) +
                        " / " + fmtTime(durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Slider(
                    value = if (durationMs > 0L) {
                        if (dragging) dragValue else (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        if (durationMs > 0L) {
                            player.seekTo((dragValue.toDouble() * durationMs).toLong())
                        }
                        dragging = false
                        host.keepControlsAlive()
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = currentIndex > 0,
                        onClick = { onSelect(currentIndex - 1) },
                    ) {
                        Text("上一集", color = if (currentIndex > 0) Color.White else Color(0x55FFFFFF))
                    }
                    TextButton(onClick = { host.onPlayPauseClicked() }) {
                        Text(
                            if (player.isPlaying) "暂停" else "播放",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    TextButton(
                        enabled = currentIndex + 1 < videos.size,
                        onClick = { onSelect(currentIndex + 1) },
                    ) {
                        Text(
                            "下一集",
                            color = if (currentIndex + 1 < videos.size) Color.White else Color(0x55FFFFFF),
                        )
                    }
                    TextButton(onClick = { showEpisodes = true }) {
                        Text("选集", color = Color.White)
                    }
                }
            }
        }

        if (state.seekHintVisible) {
            HintText(state.seekHintText, Modifier.align(Alignment.Center))
        }
        if (state.slideHintVisible) {
            HintText(state.slideHintText, Modifier.align(Alignment.Center))
        }
        if (state.speedBoostVisible) {
            HintText(fmtSpeed(state.speedBoostValue) + " 倍速", Modifier.align(Alignment.Center))
        }

        if (showEpisodes) {
            EpisodePanel(
                videos = videos,
                currentIndex = currentIndex,
                title = state.title,
                onDismiss = { showEpisodes = false },
                onSelect = { i ->
                    showEpisodes = false
                    onSelect(i)
                },
            )
        }
    }
}

/** 右侧半遮板：和在线播放器的选集面板同一套视觉（深底 + 两列网格 + 亮蓝选中） */
@Composable
private fun EpisodePanel(
    videos: List<LocalVideo>,
    currentIndex: Int,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable { onDismiss() },
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.56f)
                .background(Color(0xF0121A2B))
                .padding(horizontal = 14.dp, vertical = 16.dp),
        ) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "共 " + videos.size + " 个文件",
                color = Color(0x99FFFFFF),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(videos) { i, video ->
                    val selected = i == currentIndex
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0xFF2D7FF9) else Color(0x33FFFFFF))
                            .border(
                                width = 1.dp,
                                color = if (selected) Color(0x00000000) else Color(0x33FFFFFF),
                                shape = RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelect(i) }
                            .padding(horizontal = 10.dp, vertical = 14.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = shortName(video.name),
                            color = if (selected) Color.White else Color(0xFFE0E0E0),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
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

private fun shortName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

private fun fmtSpeed(speed: Float): String {
    return if (speed == speed.toInt().toFloat()) {
        speed.toInt().toString() + "x"
    } else {
        String.format("%.1fx", speed)
    }
}

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) {
        String.format("%d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}
