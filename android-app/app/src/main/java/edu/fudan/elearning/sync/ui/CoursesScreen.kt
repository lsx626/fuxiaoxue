package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.data.CourseStats
import edu.fudan.elearning.sync.util.FileUtils

@Composable
internal fun CoursesScreen(viewModel: AppViewModel) {
    val courses by viewModel.courses.collectAsState()
    val loading by viewModel.libraryLoading.collectAsState()
    val error by viewModel.libraryError.collectAsState()
    val version by viewModel.dataVersion.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    val terms =
        remember(courses) {
            courses.map { it.course.term }.filter(String::isNotBlank).distinct().sortedDescending()
        }
    val activeTerm = selectedTerm?.takeIf { it in terms }
    val fileSearch by
        loadResource("course-file-search:$query", version) {
            viewModel.courseIdsMatchingFileName(query)
        }
    val fileMatchedCourseIds = (fileSearch as? Resource.Ready)?.value.orEmpty()
    val filtered =
        remember(courses, query, activeTerm, fileMatchedCourseIds) {
            filterLibraryCourses(courses, query, activeTerm, fileMatchedCourseIds)
        }
    val gridState = rememberLazyGridState()
    LaunchedEffect(query, activeTerm) { gridState.scrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(290.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize().testTag("courses-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ScreenHeading(
                stringResource(R.string.library_heading),
                stringResource(R.string.library_subtitle),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryMetrics(
                courses.size,
                courses.sumOf { it.filesDone },
                courses.sumOf { it.bytesDownloaded },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibrarySearchField(
                query,
                { query = it },
                stringResource(R.string.library_course_search),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            TermFilters(terms, activeTerm) { selectedTerm = it }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.selection_hint),
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionLabel(
                stringResource(R.string.screen_courses),
                stringResource(R.string.library_course_count, filtered.size, courses.size),
            )
        }
        if (error != null)
            item(span = { GridItemSpan(maxLineSpan) }) {
                Notice(
                    error!!,
                    stringResource(R.string.action_retry),
                    viewModel::refreshLibrary,
                    error = true,
                )
            }
        if (loading && courses.isEmpty())
            item(span = { GridItemSpan(maxLineSpan) }) {
                LoadingState(stringResource(R.string.loading_library))
            }
        else if (courses.isEmpty() && error == null)
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    stringResource(R.string.courses_empty_title),
                    stringResource(R.string.courses_empty_hint),
                    Icons.Default.Refresh,
                    stringResource(R.string.action_sync),
                    { viewModel.sync() },
                )
            }
        else if (filtered.isEmpty())
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    stringResource(R.string.library_no_matches),
                    "",
                    action = stringResource(R.string.action_clear),
                    onAction = {
                        query = ""
                        selectedTerm = null
                    },
                )
            }
        items(filtered, key = { it.course.id }) { stats ->
            CourseTile(stats, onOpen = { viewModel.selectCourse(stats.course.id) })
        }
    }
}

@Composable
internal fun LibraryMetrics(courses: Int, files: Int, bytes: Long) {
    val metrics: List<Triple<String, Int, Int>> =
        listOf(
            Triple(courses.toString(), R.string.metric_courses, R.drawable.ic_nav_library),
            Triple(files.toString(), R.string.metric_files, R.drawable.ic_brand_mark),
            Triple(FileUtils.formatBytes(bytes), R.string.metric_storage, R.drawable.ic_nav_storage),
        )
    Row(Modifier.fillMaxWidth()) {
        metrics.forEachIndexed { index, (value, caption, icon) ->
            if (index > 0)
                VerticalDivider(
                    Modifier.height(56.dp).align(Alignment.CenterVertically),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            Column(
                Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    painterResource(icon),
                    null,
                    Modifier.size(17.dp),
                    tint =
                        if (index == 0) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.secondary,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(caption),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CourseTile(stats: CourseStats, onOpen: () -> Unit) {
    val course = stats.course
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp)) {
        Box(
            Modifier.width(3.dp)
                .fillMaxHeight()
                .defaultMinSize(minHeight = 0.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
        Column(
            Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Text(
                            FileUtils.cleanCourseName(course.name).take(1),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        FileUtils.cleanCourseName(course.name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (course.code.isNotBlank())
                        Text(
                            course.code,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (course.term.isNotBlank()) StatusBadge(course.term)
                StatusBadge(
                    when {
                        course.selected -> stringResource(R.string.course_sync_all_summary)
                        else ->
                            stringResource(R.string.course_sync_files_summary, stats.filesSelected)
                    }
                )
                Text(
                    if (stats.filesTotal > 0)
                        stringResource(
                            R.string.course_file_summary,
                            stats.filesTotal,
                            stats.filesDone,
                        )
                    else stringResource(R.string.course_no_files),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (stats.filesTotal > 0)
                LinearProgressIndicator(
                    progress = { (stats.filesDone.toFloat() / stats.filesTotal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
        }
    }
}
