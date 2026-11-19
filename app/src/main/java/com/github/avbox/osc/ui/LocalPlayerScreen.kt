package com.github.avbox.osc.ui

import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.github.avbox.osc.R
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.player.state.PlayerUiState
import com.github.avbox.osc.ui.player.PlayerBottomBar
import com.github.avbox.osc.ui.player.videoGestureLayer
import kotlinx.coroutines.delay

/**
 * 本地播放界面。
 *
 * 手势层复用项目的 [videoGestureLayer] / VideoGestureHandler；
 * 顶栏底栏按原版播放器的观感组织：同样的渐变高度、同样的图标资源（player_ic_*）、
 * 同样的电池图标与系统时间，只保留需要的入口。
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
    var panelMounted by remember { mutableStateOf(false) }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val tapConfirm = remember { Runnable { host.gestureHandler.markSingleTapConfirmed() } }
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

    // 右侧面板滑入/滑出动画
    val panelAnim by animateFloatAsState(
        targetValue = if (showEpisodes) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "episodePanel",
    )
    LaunchedEffect(showEpisodes) {
        if (showEpisodes) {
            panelMounted = true
        } else {
            delay(230L)
            panelMounted = false
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
            // ---------- 顶栏：与原版同一套渐变 + 图标 + 电池时间 ----------
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.65f),
                            0.45f to Color.Black.copy(alpha = 0.28f),
                            1f to Color.Transparent,
                        ),
                    )
                    .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(R.drawable.player_ic_close) { host.onBackClicked() }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 2.dp),
                ) {
                    Text(
                        text = state.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sizeLine = listOf(state.videoSize, state.videoQuality)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (sizeLine.isNotBlank()) {
                        Text(
                            text = sizeLine,
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (state.batteryPercent in 0..100) {
                    Text(
                        text = state.batteryPercent.toString() + "%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Image(
                        painter = painterResource(batteryIcon(state)),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp, end = 8.dp)
                            .size(16.dp),
                    )
                }
                Text(
                    text = state.sysTime,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                IconAction(R.drawable.ic_detail_cast) { host.onCastClicked() }
            }

            // ---------- 底栏：直接用项目组件（compact 模式） ----------
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                PlayerBottomBar(state = state, actions = host, compact = true)
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

        // ---------- 右侧半遮板：圆角 + 滑入动画 ----------
        if (panelMounted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f * panelAnim))
                    .clickable { showEpisodes = false },
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.56f)
                    .graphicsLayer { translationX = (1f - panelAnim) * size.width }
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(Color(0xF0121A2B))
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
            ) {
                Text(
                    text = state.title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "共 " + videos.size + " 个文件",
                    color = Color(0x99FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
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
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 与原版 PlayerActionIconButton 同尺寸的图标按钮 */
@Composable
private fun IconAction(
    @DrawableRes res: Int,
    boxSize: Dp = 40.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            alpha = if (enabled) 1f else 0.35f,
        )
    }
}

@Composable
private fun HintText(text: String, modifier: Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .background(Color(0x99000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

private fun batteryIcon(state: PlayerUiState): Int = when {
    state.batteryCharging -> R.drawable.ic_battery_charging
    state.batteryPercent <= 16 -> R.drawable.ic_battery_1
    state.batteryPercent <= 33 -> R.drawable.ic_battery_2
    state.batteryPercent <= 50 -> R.drawable.ic_battery_3
    state.batteryPercent <= 66 -> R.drawable.ic_battery_4
    state.batteryPercent <= 83 -> R.drawable.ic_battery_5
    else -> R.drawable.ic_battery_6
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
