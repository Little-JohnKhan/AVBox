package com.github.avbox.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import com.github.avbox.osc.player.download.DownloadRepository
import com.github.avbox.osc.player.download.DownloadRow
import com.github.avbox.osc.player.download.LocalLibrary
import com.github.avbox.osc.player.download.LocalVideo
import com.github.avbox.osc.ui.LocalPlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记录页第三段：离线下载（文件夹视图）。
 *
 * 编辑态由记录页顶栏的铅笔/垃圾桶按钮驱动，选中键为 "task:<id>" / "file:<路径>"。
 * 单条删除走长按确认弹窗，行内不再有删除按钮。
 */
@Composable
internal fun DownloadsTab(
    navStart: Dp,
    navBottom: Dp,
    editMode: Boolean = false,
    selected: Set<String> = emptySet(),
    onSelectedChange: (Set<String>) -> Unit = {},
    deleteTick: Int = 0,
    onDeleteDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var files by remember { mutableStateOf<List<LocalVideo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var hasAllFiles by remember { mutableStateOf(true) }
    var handledTick by remember { mutableStateOf(0) }
    var pendingDeleteTask by remember { mutableStateOf<DownloadRow?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<LocalVideo?>(null) }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
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

    // 顶栏「删除选中」确认后触发；同一 tick 只处理一次
    LaunchedEffect(deleteTick) {
        if (deleteTick <= 0 || deleteTick <= handledTick) return@LaunchedEffect
        handledTick = deleteTick
        val keys = selected.toList()
        if (keys.isEmpty()) {
            onDeleteDone()
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            keys.forEach { key ->
                when {
                    key.startsWith("task:") -> DownloadRepository.remove(context, key.removePrefix("task:"))
                    key.startsWith("file:") -> {
                        try {
                            File(key.removePrefix("file:")).delete()
                        } catch (ignored: Throwable) {
                        }
                    }
                }
            }
        }
        files = withContext(Dispatchers.IO) { LocalLibrary.scan() }
        rows = DownloadRepository.list(context).filterNot { it.isDone }
        Toast.makeText(context, "已删除 " + keys.size + " 项", Toast.LENGTH_SHORT).show()
        onDeleteDone()
    }

    fun toggle(key: String) {
        onSelectedChange(if (key in selected) selected - key else selected + key)
    }

    if (loaded && rows.isEmpty() && files.isEmpty() && !editMode) {
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
        if (rows.isNotEmpty()) {
            item(key = "task-header") { SectionTitle("下载中 · " + rows.size + " 个任务") }
            items(rows, key = { "task:" + it.item.id }) { row ->
                val key = "task:" + row.item.id
                DownloadRowCard(
                    row = row,
                    editMode = editMode,
                    checked = key in selected,
                    onClick = {
                        if (editMode) {
                            toggle(key)
                        } else {
                            scope.launch {
                                if (row.isPaused) {
                                    DownloadRepository.resume(context, row.item.id)
                                } else if (row.isDownloading || row.isQueued) {
                                    DownloadRepository.pause(context, row.item.id)
                                }
                            }
                        }
                    },
                    onLongClick = { pendingDeleteTask = row },
                    onPause = { scope.launch { DownloadRepository.pause(context, row.item.id) } },
                    onResume = { scope.launch { DownloadRepository.resume(context, row.item.id) } },
                    onRetry = {
                        scope.launch {
                            DownloadRepository.remove(context, row.item.id)
                            DownloadRepository.enqueue(context, row.item) { _, _ -> }
                        }
                    },
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
            val key = "file:" + file.path
            LocalFileCard(
                file = file,
                editMode = editMode,
                checked = key in selected,
                onClick = {
                    if (editMode) toggle(key) else LocalPlayActivity.start(context, file.path)
                },
                onLongClick = { pendingDeleteFile = file },
            )
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
                    Toast.makeText(context, if (ok) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
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
private fun SelectDot(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(
                if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            )
            .border(
                width = 1.dp,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadRowCard(
    row: DownloadRow,
    editMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editMode) {
                SelectDot(checked)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (editMode) 12.dp else 0.dp),
            ) {
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
            if (!editMode) {
                if (row.isDownloading || row.isQueued) {
                    TextButton(onClick = onPause) { Text("暂停") }
                } else if (row.isPaused) {
                    TextButton(onClick = onResume) { Text("继续") }
                } else if (row.isFailed) {
                    TextButton(onClick = onRetry) { Text("重试") }
                }
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LocalFileCard(
    file: LocalVideo,
    editMode: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editMode) {
            SelectDot(checked)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (editMode) 12.dp else 0.dp),
        ) {
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
