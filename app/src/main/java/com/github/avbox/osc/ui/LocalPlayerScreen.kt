package com.github.avbox.osc.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.avbox.osc.player.download.LocalLibrary
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 本地播放界面：视觉和操作对齐在线播放器，但只服务「同目录一堆文件」的场景。
 *
 * - 顶部：返回 / 文件名 / 投屏
 * - 底部：播放暂停 / 时间 / 选集面板 / 进度条 / 上一集 / 下一集
 * - 手势：单击显隐控制条、双击播放暂停、长按倍速（读设置）、左右拖动定位
 *
 * 选集面板直接列 Download/AVBox 里的文件，不涉及任何在线剧集数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalPlayerScreen(
    player: ExoPlayer,
    initialPath: String,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    // 同目录文件列表：上一集/下一集/选集都基于它，顺序由 LocalLibrary 自然排序保证
    val videos = remember { LocalLibrary.scan() }
    var index by remember {
        val found = videos.indexOfFirst { it.path == initialPath }
        mutableStateOf(if (found >= 0) found else 0)
    }
    var duration by remember { mutableStateOf(0L) }
    var position by remember { mutableStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    var hint by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragTarget by remember { mutableStateOf(0L) }
    var sliderValue by remember { mutableStateOf<Float?>(null) }
    var showEpisodes by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun playAt(target: Int) {
        if (target < 0 || target >= videos.size) return
        index = target
        val file = videos[target].file
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player.prepare()
        player.playWhenReady = true
        position = 0L
        dragTarget = 0L
        controls = true
    }

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

    LaunchedEffect(controls, playing) {
        if (controls && playing && !showEpisodes) {
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

    val title = videos.getOrNull(index)?.name ?: "本地视频"

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
                                val speed = try {
                                    KV.get(KVKeys.LONG_PRESS_SPEED, KVKeys.LONG_PRESS_SPEED_DEFAULT).toFloat()
                                } catch (th: Throwable) {
                                    KVKeys.LONG_PRESS_SPEED_DEFAULT.toFloat()
                                }
                                player.setPlaybackSpeed(speed)
                                showHint(fmtSpeed(speed) + " 倍速")
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
                        Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)),
                    )
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 18.dp),
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
                TextButton(onClick = {
                    Toast.makeText(context, "本地文件投屏下个版本接入", Toast.LENGTH_SHORT).show()
                }) {
                    Text("投屏", color = Color.White)
                }
            }
        }

        if (controls) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))),
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
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
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { showEpisodes = true }) {
                        Text("选集", color = Color.White)
                    }
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        enabled = index > 0,
                        onClick = { playAt(index - 1) },
                    ) {
                        Text("上一集", color = if (index > 0) Color.White else Color(0x66FFFFFF))
                    }
                    TextButton(
                        enabled = index + 1 < videos.size,
                        onClick = { playAt(index + 1) },
                    ) {
                        Text(
                            "下一集",
                            color = if (index + 1 < videos.size) Color.White else Color(0x66FFFFFF),
                        )
                    }
                }
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

    if (showEpisodes) {
        ModalBottomSheet(onDismissRequest = { showEpisodes = false }) {
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
                                text = if (i == index) "▶ " + video.name else video.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = { Text(fmtSize(video.size)) },
                        modifier = Modifier.clickable {
                            showEpisodes = false
                            playAt(i)
                        },
                    )
                }
            }
        }
    }
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

private fun fmtSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
