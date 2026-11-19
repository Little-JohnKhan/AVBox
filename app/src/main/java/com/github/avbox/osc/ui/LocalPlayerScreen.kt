package com.github.avbox.osc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 本地播放界面：PlayerView 只负责出画面，手势和控制条自己画。
 *
 * 手势和在线播放器保持同一套习惯：
 * - 单击：显示/隐藏控制条
 * - 双击：播放/暂停
 * - 长按：3 倍速（松手恢复）
 * - 左右拖动：快进/快退（满屏约 2 分钟）
 */
@Composable
internal fun LocalPlayerScreen(
    player: ExoPlayer,
    title: String,
    onExit: () -> Unit,
) {
    var duration by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf(0L) }
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(player) {
        while (true) {
            if (!dragging) {
                duration = player.duration.coerceAtLeast(0L)
                position = player.currentPosition.coerceAtLeast(0L)
            }
            playing = player.isPlaying
            delay(400L)
        }
    }

    // 播放中且控制条开着，4 秒后自动收起
    LaunchedEffect(controls, playing) {
        if (controls && playing) {
            delay(4000L)
            controls = false
        }
    }

    fun showHint(text: String) {
        hint = text
        scope.launch {
            delay(1000L)
            if (hint == text) hint = null
        }
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

        // 手势层（在画面之上、控制条之下）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = {
                            if (player.isPlaying) {
                                player.pause()
                                showHint("暂停")
                            } else {
                                player.play()
                                showHint("播放")
                            }
                        },
                        onPress = {
                            val boost = scope.launch {
                                delay(600L)
                                player.setPlaybackSpeed(3f)
                                showHint("3x 倍速")
                            }
                            tryAwaitRelease()
                            boost.cancel()
                            if (player.playbackParameters.speed != 1f) {
                                player.setPlaybackSpeed(1f)
                            }
                        },
                    )
                }
                .pointerInput(duration) {
                    if (duration <= 0L) return@pointerInput
                    val span = 120_000.0
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            dragTarget = player.currentPosition.coerceAtLeast(0L)
                        },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            val delta = (amount.toDouble() / size.width * span).toLong()
                            dragTarget = (dragTarget + delta).coerceIn(0L, duration)
                            showHint(fmtTime(dragTarget) + " / " + fmtTime(duration))
                        },
                        onDragEnd = {
                            player.seekTo(dragTarget)
                            position = dragTarget
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        )

        if (controls) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xCC000000), Color.Transparent),
                        ),
                    )
                    .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onExit) { Text("返回", color = Color.White) }
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (controls) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xCC000000)),
                        ),
                    )
                    .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (player.isPlaying) player.pause() else player.play()
                    }) {
                        Text(if (playing) "暂停" else "播放", color = Color.White)
                    }
                    Text(
                        text = fmtTime(if (dragging) dragTarget else position) + " / " + fmtTime(duration),
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Slider(
                    value = sliderValue
                        ?: if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                    onValueChange = { v -> sliderValue = v },
                    onValueChangeFinished = {
                        val v = sliderValue
                        if (v != null && duration > 0L) {
                            val target = (v.toDouble() * duration).toLong()
                            player.seekTo(target)
                            position = target
                            showHint(fmtTime(target))
                        }
                        sliderValue = null
                    },
                )
            }
        }

        hint?.let {
            Text(
                text = it,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(10.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
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
