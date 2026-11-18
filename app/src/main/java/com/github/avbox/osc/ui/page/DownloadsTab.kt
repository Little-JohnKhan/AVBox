package com.github.avbox.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.github.avbox.osc.player.download.LocalLibrary
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.ui.LocalPlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录页第三段：离线下载。
 *
 * 这一页把 Download/AVBox 当文件夹用：
 * - 上半部分只放「还没下完」的任务（排队/下载中/暂停/失败），带进度和操作；
 * - 下半部分直接列目录里扫到的视频文件，文件名如实显示，点了就本地播放。
 *
 * 所以下载完的任务会自然「变成」文件条目，不再需要做任何 URL / 名称匹配。
 */
@Composable
internal fun DownloadsTab(navStart: Dp, navBottom: Dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var files by remember { mutableStateOf<List<LocalVideo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var manualUrl by remember { mutableStateOf("") }
    var hasAllFiles by remember { mutableStateOf(true) }
    var pendingDeleteTask by remember { mutableStateOf<DownloadRow?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<LocalVideo?>(null) }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            // 已完成的下载不再当任务展示，它的产物会出现在下面的文件列表里
            rows = DownloadRepository.list(context).filterNot { it.isDone }
            if (tick % 3 == 0) {
                files = withContext(Dispatchers.IO) { LocalLibrary.scan() }
                hasAllFiles = LocalLibrary.hasAllFilesAccess()
            }
            loaded = true
            tick++
            delay(1000)
        }
    }

    if (loaded && rows.isEmpty() && files.isEmpty()) {
        EmptyState(
            navStart = navStart,
            navBottom = navBottom,
            manualUrl = manualUrl,
            onManualUrlChange = { manualUrl = it },
            onAddManual = {
                val url = manualUrl.trim()
                if (url.isNotEmpty()) {
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
                        if (ok) {
                            manualUrl = ""
                        } else {
                            Toast.makeText(context, err ?: "入队失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
        )
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
        if (rows.isNotEmpty()) {
            item(key = "task-header") { SectionTitle("下载中 · " + rows.size + " 个任务") }
            items(rows, key = { "task:" + it.item.id }) { row ->
                DownloadRowCard(
                    row = row,
                    onPause = { scope.launch { DownloadRepository.pause(context, row.item.id) } },
                    onResume = { scope.launch { DownloadRepository.resume(context, row.item.id) } },
                    onRetry = {
                        scope.launch {
                            DownloadRepository.remove(context, row.item.id)
                            DownloadRepository.enqueue(context, row.item) { _, _ -> }
                        }
                    },
                    onDelete = { pendingDeleteTask = row },
                )
            }
        }

        item(key = "file-header") {
            SectionTitle("Download/AVBox · " + files.size + " 个文件")
        }
        if (!hasAllFiles) {
            item(key = "file-permission") {
                Text(
                    text = "没有「所有文件访问权限」，扫不到目录里的文件。请到系统设置 → 应用 → AVBox → 权限里允许访问所有文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        items(files, key = { "file:" + it.path }) { file ->
            LocalFileCard(
                file = file,
                onPlay = { LocalPlayActivity.start(context, file.path) },
                onDelete = { pendingDeleteFile = file },
            )
        }

        item(key = "manual-entry") {
            OutlinedTextField(
                value = manualUrl,
                onValueChange = { manualUrl = it },
                label = { Text("粘贴 m3u8 地址直接下载") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }
        item(key = "manual-button") {
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
                        if (ok) {
                            manualUrl = ""
                        } else {
                            Toast.makeText(context, err ?: "入队失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            ) {
                Text("开始下载")
            }
        }
    }

    val taskTarget = pendingDeleteTask
    if (taskTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteTask = null },
            title = { Text("删除下载") },
            text = {
                Text(
                    "《" + taskTarget.item.vodName + "》" + taskTarget.item.episodeName +
                        "\n未下完的进度会一并清除。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { DownloadRepository.remove(context, taskTarget.item.id) }
                    pendingDeleteTask = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteTask = null }) { Text("取消") } },
        )
    }

    val fileTarget = pendingDeleteFile
    if (fileTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteFile = null },
            title = { Text("删除文件") },
            text = { Text(fileTarget.name + "\n" + formatBytes(fileTarget.size) + "，删除后不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = try {
                        fileTarget.file.delete()
                    } catch (th: Throwable) {
                        false
                    }
                    Toast.makeText(
                        context,
                        if (ok) "已删除" else "删除失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                    scope.launch {
                        files = withContext(Dispatchers.IO) { LocalLibrary.scan() }
                    }
                    pendingDeleteFile = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteFile = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun EmptyState(
    navStart: Dp,
    navBottom: Dp,
    manualUrl: String,
    onManualUrlChange: (String) -> Unit,
    onAddManual: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp + navStart, end = 24.dp, bottom = navBottom),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有下载任务\n把视频放进 Download/AVBox/ 也会出现在这里",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = manualUrl,
            onValueChange = onManualUrlChange,
            label = { Text("粘贴 m3u8 地址直接下载") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
        )
        TextButton(
            enabled = manualUrl.trim().startsWith("http"),
            onClick = onAddManual,
        ) {
            Text("开始下载")
        }
    }
}

@Composable
private fun DownloadRowCard(
    row: DownloadRow,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
            } else if (row.isFailed) {
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

@Composable
private fun LocalFileCard(
    file: LocalVideo,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onPlay() }
            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = formatBytes(file.size) + " · " + formatTime(file.modifiedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

private fun statusText(row: DownloadRow): String {
    return when {
        row.isDownloading -> "下载中 " + row.percent.toInt() + "%" +
            if (row.downloadedBytes > 0) " · " + formatBytes(row.downloadedBytes) else ""
        row.isQueued -> "等待中"
        row.isPaused -> "已暂停 · " + row.percent.toInt() + "%"
        row.isFailed -> "失败"
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

private fun formatTime(ms: Long): String {
    return try {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
    } catch (th: Throwable) {
        ""
    }
}
