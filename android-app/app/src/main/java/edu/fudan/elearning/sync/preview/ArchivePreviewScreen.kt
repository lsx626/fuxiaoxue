package edu.fudan.elearning.sync.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import edu.fudan.elearning.sync.R
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.ui.*
import edu.fudan.elearning.sync.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ArchivePreviewScreen(file: File) {
    val archive by loadResource(file.absolutePath, 0) { withContext(Dispatchers.IO) { archiveIndex(file) } }
    when (val state = archive) {
        Resource.Loading -> LoadingState(stringResource(R.string.archive_loading))
        Resource.Failed -> PreviewError(stringResource(R.string.archive_error))
        is Resource.Ready -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Notice(stringResource(R.string.archive_hint)) }
            item { Text(stringResource(R.string.archive_total, state.value.totalEntries) +
                if (state.value.totalEntries > state.value.entries.size) stringResource(R.string.archive_truncated, state.value.entries.size) else "",
                style = MaterialTheme.typography.labelMedium) }
            itemsIndexed(state.value.entries) { _, entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (!entry.directory && entry.bytes >= 0) Text(FileUtils.formatBytes(entry.bytes), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
