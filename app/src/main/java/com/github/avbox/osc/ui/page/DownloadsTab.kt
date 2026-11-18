package com.github.avbox.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.avbox.osc.player.download.DownloadRepository
import com.github.avbox.osc.player.download.DownloadRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 记录页第三段：离线下载。
 *
 * 下载由 TsDownloader 在后台把分片直接拼成一个 .ts 文件，这里只做展示和操作，
 * 每秒轮询一次状态（任务数量不多，轮询比监听更省事也更稳）。
 */
@Composable
internal fun DownloadsTab(navStart: Dp, navBottom: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var manualUrl by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<DownloadRow?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            rows = DownloadRepository.list(context)
            loaded = true
            delay(1000)
        }
    }

    if (loaded && rows.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp + navStart, end = 24.dp, bottom = navBottom),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "还没有下载任务\n播放页的下载入口在下一版接入",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text("粘贴 m3u8 地址直接下载") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            )
            TextButton(
                enabled = manualUrl.trim().startsWith("http"),
                onClick = {
                    val url = manualUrl.trim()
                    if (url.isEmpty()) return@TextButton
                    val item = com.github.avbox.osc.player.download.DownloadItem(
                        id = com.github.avbox.osc.player.download.DownloadTasks.idOf(url, "manual", 0),
                        vodId = url,
                        vodName = "手动添加",
                        flag = "manual",
                        episodeIndex = 0,
                        episodeName = url.takeLast(24),
                        playUrl = url,
                        headers = null,
                        poster = null,
                        createdAt = System.currentTimeMillis(),
                    )
                    DownloadRepository.enqueue(context, item) { ok, err ->
                        if (ok) manualUrl = "" else android.widget.Toast.makeText(
                            context,
                            err ?: "入队失败",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Text("开始下载")
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp + navStart,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp + navBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { it.item.id }) { row ->
            DownloadRowCard(
                row = row,
                onPlay = {
                    // 本地文件在就直接起本地播放页（断网也能播），否则回退到详情页
                    val local = DownloadRepository.publicFileOf(context, row.item.id)
                    if (!local.isNullOrEmpty()) {
                        com.github.avbox.osc.ui.LocalPlayActivity.start(context, local)
                    } else {
                        context.jumpToDetail(
                            id = row.item.vodId,
                            sourceKey = row.item.flag,
                            title = row.item.vodName,
                            picture = row.item.poster,
                        )
                    }
                },
                onPause = { scope.launch { DownloadRepository.pause(context, row.item.id) } },
                onResume = { scope.launch { DownloadRepository.resume(context, row.item.id) } },
                onRetry = {
                    scope.launch {
                        DownloadRepository.remove(context, row.item.id)
                        DownloadRepository.enqueue(context, row.item) { _, _ -> }
                    }
                },
                onExport = {
                    val id = row.item.id
                    val path = DownloadRepository.publicFileOf(context, id)
                        ?: DownloadRepository.localFileOf(context, id)
                    val msg = if (path.isNullOrEmpty()) {
                        "文件不存在，请删除后重下"
                    } else {
                        "已保存到：\n" + path
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                },
                onDelete = { pendingDelete = row },
            )
        }
    }

    val target = pendingDelete
    if (target != null) {
        ConfirmDeleteDialog(
            row = target,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                scope.launch { DownloadRepository.remove(context, target.item.id) }
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(
    row: DownloadRow,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除下载") },
        text = {
            Text(
                "《" + row.item.vodName + "》" + row.item.episodeName +
                    "\n本地文件会一并清除，删除后需要重新下载。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun DownloadRowCard(
    row: DownloadRow,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onPlay() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.item.vodName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = row.item.episodeName + " · " + statusText(row),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            if (row.isDownloading || row.isQueued) {
                TextButton(onClick = onPause) { Text("暂停") }
            } else if (row.isPaused) {
                TextButton(onClick = onResume) { Text("继续") }
            } else if (row.isDone && row.downloadedBytes > 0) {
                TextButton(onClick = onExport) { Text("文件") }
            } else if (row.isFailed || row.isDone) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
        if (row.isDownloading || row.isQueued || row.isPaused) {
            LinearProgressIndicator(
                progress = { row.percent.coerceIn(0f, 100f) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }
    }
}

private fun statusText(row: DownloadRow): String {
    return when {
        row.isDone && row.downloadedBytes <= 0 -> "已完成但内容为空，请删除后重下"
        row.isDone -> "已完成 · " + formatBytes(if (row.totalBytes > 0) row.totalBytes else row.downloadedBytes)
        row.isDownloading -> "下载中 " + row.percent.toInt() + "%" +
            if (row.totalBytes > 0) {
                " · " + formatBytes(row.downloadedBytes) + " / " + formatBytes(row.totalBytes)
            } else if (row.downloadedBytes > 0) {
                " · 已下载 " + formatBytes(row.downloadedBytes)
            } else ""
        row.isQueued -> "等待中"
        row.isPaused -> "已暂停 · " + row.percent.toInt() + "%"
        row.isFailed -> "失败，可删除后重试"
        else -> "已停止"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
