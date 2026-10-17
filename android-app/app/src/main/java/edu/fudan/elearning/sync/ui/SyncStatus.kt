package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.util.FileUtils
import kotlinx.coroutines.delay

@Composable
internal fun SyncStatusStrip(
    progress: SyncProgressUi,
    syncing: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (syncing || busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (syncing) stringResource(R.string.sync_running)
                    else if (busy) stringResource(R.string.sync_background) else progress.message,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (syncing && progress.courseTotal > 0)
                    Text(
                        stringResource(
                            R.string.sync_course_progress,
                            progress.courseDone,
                            progress.courseTotal,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                    )
            }
            if (syncing || busy) TextButton(onClick) { Text(stringResource(R.string.sync_detail)) }
            else
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        stringResource(R.string.action_close),
                        Modifier.size(18.dp),
                    )
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncTaskSheet(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val progress by viewModel.syncProgress.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val busy by viewModel.taskBusy.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(syncing) {
        while (syncing) {
            delay(500)
            now = System.currentTimeMillis()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.sync_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                progress.message.ifBlank { stringResource(R.string.sync_idle) },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (syncing) {
                if (progress.courseTotal > 0) {
                    Text(
                        stringResource(
                            R.string.sync_course_progress,
                            progress.courseDone,
                            progress.courseTotal,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (progress.courseDone.toFloat() / progress.courseTotal).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (progress.phase == "file" && progress.filesTotal > 0) {
                    Text(
                        stringResource(
                            R.string.sync_file_progress,
                            progress.filesDone,
                            progress.filesTotal,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LinearProgressIndicator(
                        progress = {
                            (progress.filesDone.toFloat() / progress.filesTotal).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val elapsed = ((now - progress.startedAt) / 1000).coerceAtLeast(0)
                Text(
                    stringResource(
                        R.string.sync_average_rate,
                        FileUtils.formatRate(
                            if (elapsed > 0) progress.bytes.toDouble() / elapsed else 0.0
                        ),
                        FileUtils.formatElapsed(elapsed),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = viewModel::stopSync, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_stop))
                }
            } else {
                Text(
                    stringResource(R.string.sync_idle_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        onDismiss()
                        viewModel.sync()
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_sync))
                }
            }
        }
    }
}
