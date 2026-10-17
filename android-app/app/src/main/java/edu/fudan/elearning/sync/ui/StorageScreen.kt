package edu.fudan.elearning.sync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.util.FileUtils

@Composable
internal fun StorageScreen(viewModel: AppViewModel) {
    val version by viewModel.dataVersion.collectAsState()
    val busy by viewModel.taskBusy.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var term by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val snapshot by loadResource("storage", version, retry) { viewModel.storageSnapshot() }
    val courses = (snapshot as? Resource.Ready)?.value?.second.orEmpty()
    val usage = (snapshot as? Resource.Ready)?.value?.first
    val selected = courses.find { it.course.id == selectedId }
    val files by
        loadResource(selectedId, version, retry) {
            selectedId?.let { viewModel.storageFilesOf(it) }.orEmpty()
        }
    val terms =
        remember(courses) {
            courses.map { it.course.term }.filter(String::isNotBlank).distinct().sortedDescending()
        }
    val activeTerm = term?.takeIf { it in terms }
    BackHandler(selectedId != null) { selectedId = null }
    LazyColumn(
        Modifier.fillMaxSize().testTag("storage-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeading(
                if (selected != null) FileUtils.cleanCourseName(selected.course.name)
                else stringResource(R.string.storage_heading),
                stringResource(R.string.storage_hint),
            )
        }
        if (selectedId == null) {
            if (usage != null) item { LibraryMetrics(courses.size, usage.files, usage.bytes) }
            item { TermFilters(terms, activeTerm) { term = it } }
            if (snapshot is Resource.Loading)
                item { LoadingState(stringResource(R.string.loading_library)) }
            if (snapshot is Resource.Failed)
                item {
                    Notice(
                        stringResource(R.string.library_load_error),
                        stringResource(R.string.action_retry),
                        { retry++ },
                        error = true,
                    )
                }
            val visible = courses.filter { activeTerm == null || it.course.term == activeTerm }
            if (visible.isEmpty() && snapshot is Resource.Ready)
                item {
                    EmptyState(
                        stringResource(R.string.storage_empty),
                        stringResource(R.string.storage_choose_course),
                    )
                }
            items(visible, key = { it.course.id }) { course ->
                Surface(
                    Modifier.fillMaxWidth().clickable { selectedId = course.course.id },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        Modifier.padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                FileUtils.cleanCourseName(course.course.name),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                course.course.term,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(
                                    R.string.course_file_summary,
                                    course.filesTotal,
                                    course.filesDone,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            FileUtils.formatBytes(course.bytesDownloaded),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { selectedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.search_back))
                    }
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy && selected != null && selected.filesTotal > 0,
                    ) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_delete_all))
                    }
                }
            }
            when (val result = files) {
                Resource.Loading -> item { LoadingState(stringResource(R.string.loading_files)) }
                Resource.Failed ->
                    item {
                        Notice(
                            stringResource(R.string.library_load_error),
                            stringResource(R.string.action_retry),
                            { retry++ },
                            error = true,
                        )
                    }
                is Resource.Ready -> {
                    if (result.value.isEmpty())
                        item {
                            EmptyState(
                                stringResource(R.string.storage_empty),
                                stringResource(R.string.storage_hint),
                            )
                        }
                    items(result.value, key = { it.fileId }) { file ->
                        LibraryFileRow(file, null, viewModel, storageMode = true)
                    }
                }
            }
        }
    }
    if (confirmDelete && selected != null)
        ConfirmActionDialog(
            stringResource(R.string.delete_course_title),
            stringResource(R.string.delete_course_hint, selected.course.name),
            stringResource(R.string.action_confirm_delete),
            onConfirm = {
                confirmDelete = false
                viewModel.deleteCourseFiles(selected.course.id)
            },
            onDismiss = { confirmDelete = false },
        )
}
