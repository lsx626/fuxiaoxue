package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import edu.fudan.elearning.sync.data.*
import edu.fudan.elearning.sync.util.FileUtils

@Composable
internal fun CourseFilesScreen(viewModel: AppViewModel, stats: CourseStats) {
    val version by viewModel.dataVersion.collectAsState()
    var retry by remember { mutableIntStateOf(0) }
    val resource by
        loadResource(stats.course.id, version, retry) {
            viewModel.filesWithProgress(stats.course.id)
        }
    val latest by
        loadResource(stats.course.id, version) { viewModel.latestReadFile(stats.course.id) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFileFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(FileSort.UNREAD) }
    var sortMenu by remember { mutableStateOf(false) }
    val allFiles = (resource as? Resource.Ready)?.value.orEmpty()
    val filtered =
        remember(allFiles, query, filter, sort) {
            sortLibraryFiles(filterLibraryFiles(allFiles, query, filter), sort)
        }
    val listState = rememberLazyListState()
    LaunchedEffect(query, filter, sort) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().testTag("course-files-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenHeading(
                FileUtils.cleanCourseName(stats.course.name),
                listOf(stats.course.term, stats.course.code)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
            )
        }
        val resume = (latest as? Resource.Ready)?.value
        if (resume != null)
            item {
                Surface(
                    Modifier.fillMaxWidth().clickable { viewModel.openPreview(resume) },
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.action_continue),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                resume.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                    }
                }
            }
        item {
            LibrarySearchField(query, { query = it }, stringResource(R.string.library_file_search))
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryFileFilter.entries) { option ->
                    FilterChip(
                        filter == option,
                        { filter = option },
                        label = { Text(stringResource(fileFilterLabel(option))) },
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.library_file_count, filtered.size, allFiles.size),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    TextButton(onClick = { sortMenu = true }) {
                        Text(stringResource(sortLabel(sort)))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                        FileSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(stringResource(sortLabel(option))) },
                                onClick = {
                                    sort = option
                                    sortMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
        when (resource) {
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
                if (allFiles.isEmpty())
                    item {
                        EmptyState(
                            stringResource(R.string.files_empty_title),
                            stringResource(R.string.files_empty_hint),
                        )
                    }
                else if (filtered.isEmpty())
                    item {
                        EmptyState(
                            stringResource(R.string.library_no_matches),
                            "",
                            action = stringResource(R.string.action_clear),
                            onAction = {
                                query = ""
                                filter = LibraryFileFilter.ALL
                            },
                        )
                    }
                items(filtered, key = { it.first.fileId }) { (file, progress) ->
                    LibraryFileRow(file, progress, viewModel)
                }
            }
        }
    }
}

internal fun fileFilterLabel(filter: LibraryFileFilter): Int =
    when (filter) {
        LibraryFileFilter.ALL -> R.string.library_all_files
        LibraryFileFilter.UNREAD -> R.string.library_unread
        LibraryFileFilter.DOWNLOADED -> R.string.library_downloaded
        LibraryFileFilter.FAILED -> R.string.library_failed
        LibraryFileFilter.PENDING -> R.string.library_pending
        LibraryFileFilter.LOCKED -> R.string.library_locked
    }

internal fun sortLabel(sort: FileSort) =
    when (sort) {
        FileSort.UNREAD -> R.string.sort_unread
        FileSort.NAME -> R.string.sort_name
        FileSort.UPDATED -> R.string.sort_updated
    }

@Composable
internal fun LibraryFileRow(
    file: FileItem,
    progress: Repo.ReadingProgress?,
    viewModel: AppViewModel,
    storageMode: Boolean = false,
) {
    val busy by viewModel.taskBusy.collectAsState()
    val busyFiles by viewModel.busyFiles.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val downloaded = file.status == "downloaded"
    Surface(
        Modifier.fillMaxWidth().testTag("file-${file.fileId}").clickable(enabled = downloaded) {
            viewModel.openPreview(file)
        },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 14.dp, bottom = 14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DocumentBadge(file.filename.ifBlank { file.name })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        file.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${FileUtils.formatBytes(file.size)} · ${FileUtils.statusText(file.status)}",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (file.status == "failed") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (downloaded && !storageMode)
                        Text(
                            progressText(progress),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    if (file.folderPath.isNotBlank())
                        Text(
                            file.folderPath,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                }
                if (file.fileId in busyFiles)
                    CircularProgressIndicator(
                        Modifier.padding(12.dp).size(20.dp),
                        strokeWidth = 2.dp,
                    )
                else
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                stringResource(R.string.action_more_file, file.name),
                            )
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (downloaded) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_preview)) },
                                    onClick = {
                                        menu = false
                                        viewModel.openPreview(file)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_share)) },
                                    onClick = {
                                        menu = false
                                        viewModel.shareFile(file)
                                    },
                                )
                            }
                            if (file.status == "failed")
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_retry)) },
                                    enabled = !busy,
                                    onClick = {
                                        menu = false
                                        viewModel.retryFile(file)
                                    },
                                )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.action_delete_local),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                enabled = !busy,
                                onClick = {
                                    menu = false
                                    confirmDelete = true
                                },
                            )
                        }
                    }
            }
            if (file.error.isNotBlank() && file.status in listOf("failed", "locked"))
                Text(
                    file.error,
                    Modifier.padding(top = 8.dp, end = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (file.status == "failed") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            if (file.status == "failed" && file.fileId !in busyFiles)
                TextButton(
                    onClick = { viewModel.retryFile(file) },
                    enabled = !busy,
                    modifier = Modifier.align(Alignment.End).padding(end = 8.dp),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
        }
    }
    if (confirmDelete)
        ConfirmActionDialog(
            stringResource(R.string.delete_file_title),
            stringResource(R.string.delete_file_hint, file.name),
            stringResource(R.string.action_confirm_delete),
            {
                confirmDelete = false
                viewModel.deleteFile(file)
            },
            { confirmDelete = false },
        )
}
