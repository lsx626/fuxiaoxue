package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
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
import edu.fudan.elearning.sync.data.Assignment
import edu.fudan.elearning.sync.data.SearchResult
import edu.fudan.elearning.sync.util.FileUtils
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal fun dueInstant(value: String): Instant? =
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

internal fun displayDate(value: String): String =
    dueInstant(value)
        ?.atZone(ZoneId.systemDefault())
        ?.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) ?: value.take(16)

@Composable
internal fun ActivityScreen(viewModel: AppViewModel) {
    val assignments by viewModel.upcoming.collectAsState()
    val changes by viewModel.changes.collectAsState()
    var showChanges by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }
    val upcoming =
        remember(assignments, now) {
            assignments
                .filter { (dueInstant(it.dueAt) ?: Instant.MAX) >= now }
                .sortedBy { dueInstant(it.dueAt) ?: Instant.MAX }
        }
    val overdue =
        remember(assignments, now) {
            assignments
                .filter { (dueInstant(it.dueAt) ?: Instant.MAX) < now }
                .sortedByDescending { dueInstant(it.dueAt) }
        }
    LazyColumn(
        Modifier.fillMaxSize().testTag("activity-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            ScreenHeading(
                stringResource(R.string.screen_activity),
                stringResource(R.string.activity_subtitle),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    !showChanges,
                    { showChanges = false },
                    label = { Text(stringResource(R.string.activity_deadlines)) },
                )
                FilterChip(
                    showChanges,
                    { showChanges = true },
                    label = { Text(stringResource(R.string.activity_changes)) },
                )
            }
        }
        if (showChanges) {
            if (changes.isEmpty())
                item {
                    EmptyState(
                        stringResource(R.string.changes_none),
                        stringResource(R.string.changes_none_hint),
                    )
                }
            items(changes, key = { it.id }) { change ->
                Surface(
                    Modifier.fillMaxWidth().clickable(enabled = change.change != "removed") {
                        viewModel.openSearchResult(
                            SearchResult(change.fileId, change.courseId, change.filename)
                        )
                    },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            StatusBadge(
                                stringResource(
                                    when (change.change) {
                                        "new" -> R.string.change_new
                                        "updated" -> R.string.change_updated
                                        else -> R.string.change_removed
                                    }
                                )
                            )
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    change.filename,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    FileUtils.cleanCourseName(change.courseName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                displayDate(change.occurredAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider(
                            Modifier.padding(start = 58.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        } else {
            if (assignments.isEmpty())
                item {
                    EmptyState(
                        stringResource(R.string.deadlines_none),
                        stringResource(R.string.deadlines_none_hint),
                        Icons.Default.DateRange,
                    )
                }
            if (upcoming.isNotEmpty())
                item {
                    SectionLabel(
                        stringResource(R.string.deadlines_upcoming),
                        upcoming.size.toString(),
                    )
                }
            items(upcoming, key = { "next-${it.id}" }) { assignment ->
                DeadlineCard(assignment, false, viewModel)
            }
            if (overdue.isNotEmpty())
                item { SectionLabel(stringResource(R.string.deadlines_overdue)) }
            items(overdue, key = { "past-${it.id}" }) { assignment ->
                DeadlineCard(assignment, true, viewModel)
            }
        }
    }
}

@Composable
private fun DeadlineCard(assignment: Assignment, overdue: Boolean, viewModel: AppViewModel) {
    Surface(
        Modifier.fillMaxWidth().clickable { viewModel.openAssignment(assignment) },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    displayDate(assignment.dueAt),
                    style = MaterialTheme.typography.labelLarge,
                    color =
                        if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        assignment.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        FileUtils.cleanCourseName(assignment.courseName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "›",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(
                Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}
