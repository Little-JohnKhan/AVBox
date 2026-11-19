package com.github.avbox.osc.ui.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import com.github.avbox.osc.player.download.Mp4Converter
import com.github.avbox.osc.ui.LocalPlayActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    refreshKey: Int = 0,
    onContentChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var files by remember { mutableStateOf<List<LocalVideo>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var hasAllFiles by remember { mutableStateOf(true) }
    var pendingDeleteTask by remember { mutableStateOf<DownloadRow?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<LocalVideo?>(null) }

    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            val granted = LocalLibrary.hasAllFilesAccess()
            val justGranted = granted && !hasAllFiles
            hasAllFiles = granted
            rows = DownloadRepository.list(context).filterNot { it.isDone }
            if (tick % 3 == 0 || justGranted) {
                files = withContext(Dispatchers.IO) { LocalLibrary.scan() }
            }
            loaded = true
            onContentChange(rows.isNotEmpty() || files.isNotEmpty())
            tick++
            delay(1000)
        }
    }

    // 删除/清空在确认弹窗里直接做完，这里只做一次刷新（幂等，重建时重复执行无害）
    LaunchedEffect(refreshKey) {
        rows = DownloadRepository.list(context).filterNot { it.isDone }
        files = withContext(Dispatchers.IO) { LocalLibrary.scan() }
    }

    fun toggle(key: String) {
        onSelectedChange(if (key in selected) selected - key else selected + key)
    }

    if (loaded && !hasAllFiles && rows.isEmpty() && !editMode) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 24.dp + navStart, end = 24.dp, bottom = navBottom),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AllFilesPermissionCard(context)
        }
        return
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
                    // 重试＝续传：不删已下载的分片，从断点接着下
                    onRetry = {
                        scope.launch { DownloadRepository.restart(context, row.item.id) }
                    },
                )
            }
        }

        item(key = "file-header") {
            SectionTitle("Download/AVBox · " + files.size + " 个文件")
        }
        if (!hasAllFiles) {
            item(key = "file-permission") {
                AllFilesPermissionCard(context)
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
private fun AllFilesPermissionCard(context: Context) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable { openAllFilesSettings(context) },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "需要「所有文件访问权限」",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = "没有这个权限就扫不到 Download/AVBox 里的文件，新下载也会失败。点这里去系统设置开启。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun openAllFilesSettings(context: Context) {
    val pkgUri = Uri.parse("package:" + context.packageName)
    val candidates = listOf(
        Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION", pkgUri),
        Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
    )
    for (candidate in candidates) {
        try {
            context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (ignored: Throwable) {
        }
    }
    Toast.makeText(context, "请到系统设置 → 应用 → AVBox → 权限里开启「所有文件访问权限」", Toast.LENGTH_LONG).show()
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
                SelectCircle(selected = checked)
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
                    color = if (row.isFailed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
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
            SelectCircle(selected = checked)
        }
        val conv = Mp4Converter.stateOf(file.path)
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
                text = when {
                    conv == null -> formatBytes(file.size) + " · " + formatTime(file.modifiedAt)
                    conv.failed -> formatBytes(file.size) + " · MP4 封装失败，保留 ts 直接播"
                    conv.percent >= 100 -> formatBytes(file.size) + " · MP4 已就绪"
                    else -> "MP4 封装中 " + conv.percent + "% · " + formatBytes(file.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (conv?.failed == true) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 3.dp),
            )
            if (conv != null && !conv.failed && conv.percent < 100) {
                LinearProgressIndicator(
                    progress = { conv.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

private fun statusText(row: DownloadRow): String {
    return when {
        row.isDownloading -> "下载中 " + row.percent.toInt() + "%" +
            if (row.downloadedBytes > 0) " · " + formatBytes(row.downloadedBytes) else ""
        row.isQueued -> "等待中"
        row.isPaused -> "已暂停 · " + row.percent.toInt() + "%"
        row.isFailed -> "失败" + (row.error?.let { e -> " · " + e.replace('\n', ' ').take(60) } ?: "")
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
