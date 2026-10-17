package edu.fudan.elearning.sync.ui

import android.net.Uri
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.data.Assignment
import edu.fudan.elearning.sync.network.CanvasAssignment
import edu.fudan.elearning.sync.network.CanvasRubricCriterion
import edu.fudan.elearning.sync.network.CanvasSubmission
import edu.fudan.elearning.sync.util.FileUtils
import java.util.Locale

/**
 * 学习动态里的任务详情。
 *
 * 全部信息来自 Canvas assignment / submission API：说明、附件、评分标准、提交状态、分数、评语， 以及文件/文字/网址三种原生提交。测验、讨论和 LTI
 * 外部工具由 Canvas 自己的引擎承载，接口不返回可原生执行的数据， 页面会明确标注，不再用一个可能白屏的 WebView 占位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssignmentDetailScreen(viewModel: AppViewModel) {
    val assignment by viewModel.taskAssignment.collectAsState()
    val detail by viewModel.taskDetail.collectAsState()
    val loading by viewModel.taskLoading.collectAsState()
    val error by viewModel.taskError.collectAsState()
    val submitting by viewModel.taskSubmitting.collectAsState()
    val submitError by viewModel.taskSubmitError.collectAsState()
    val submitSuccess by viewModel.taskSubmitSuccess.collectAsState()
    val files by viewModel.taskSubmissionFiles.collectAsState()
    val current = assignment ?: return

    BackHandler { viewModel.closeAssignment() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.task_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeAssignment) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.search_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::refreshAssignment,
                        enabled = !loading && !submitting,
                    ) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
    ) { padding ->
        AssignmentBody(
            assignment = current,
            detail = detail,
            loading = loading,
            error = error,
            submitting = submitting,
            submitError = submitError,
            submitSuccess = submitSuccess,
            selectedFiles = files,
            onRefresh = viewModel::refreshAssignment,
            onOpenAttachment = viewModel::openAttachment,
            onAddFile = viewModel::addTaskSubmissionFile,
            onRemoveFile = viewModel::removeTaskSubmissionFile,
            onSubmit = viewModel::submitAssignment,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun AssignmentBody(
    assignment: Assignment,
    detail: CanvasAssignment?,
    loading: Boolean,
    error: String?,
    submitting: Boolean,
    submitError: String?,
    submitSuccess: String?,
    selectedFiles: List<TaskSubmissionFile>,
    onRefresh: () -> Unit,
    onOpenAttachment: (Long) -> Unit,
    onAddFile: (Uri) -> Unit,
    onRemoveFile: (TaskSubmissionFile) -> Unit,
    onSubmit: (TaskSubmissionMode, String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val submission = detail?.submission
    val supportedModes = remember(detail?.submissionTypes) { supportedSubmissionModes(detail) }
    var mode by
        rememberSaveable(detail?.id) {
            mutableStateOf(supportedModes.firstOrNull() ?: TaskSubmissionMode.TEXT)
        }
    var text by rememberSaveable(detail?.id) { mutableStateOf("") }
    var url by rememberSaveable(detail?.id) { mutableStateOf("") }
    LaunchedEffect(detail?.id, supportedModes) {
        if (mode !in supportedModes) supportedModes.firstOrNull()?.let { mode = it }
    }
    LaunchedEffect(detail?.id, submission?.id) {
        if (text.isBlank()) text = submission?.body.orEmpty()
        if (url.isBlank()) url = submission?.url.orEmpty()
    }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach(onAddFile)
        }
    val attemptsUsed = submission?.attempt ?: 0
    val allowedAttempts = detail?.allowedAttempts ?: -1
    val attemptsRemaining =
        when {
            detail == null -> false
            allowedAttempts < 0 -> true
            attemptsUsed < allowedAttempts -> true
            else -> false
        }
    val locked = detail?.lockedForUser == true

    LazyColumn(
        modifier.testTag("assignment-detail"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(assignment.name, style = MaterialTheme.typography.headlineSmall) }
        item {
            Text(
                FileUtils.cleanCourseName(assignment.courseName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(displayDate(assignment.dueAt))
                detail
                    ?.pointsPossible
                    ?.takeIf { it > 0 }
                    ?.let { points ->
                        StatusBadge(
                            stringResource(
                                R.string.task_points,
                                String.format(Locale.getDefault(), "%.0f", points),
                            )
                        )
                    }
            }
        }
        if (loading) item { LoadingState(stringResource(R.string.task_loading)) }
        if (error != null)
            item { Notice(error, stringResource(R.string.action_retry), onRefresh, error = true) }
        if (locked)
            item {
                Notice(
                    detail?.lockExplanation?.takeIf(String::isNotBlank)
                        ?: stringResource(R.string.task_locked),
                    error = true,
                )
            }
        detail?.let { info -> item { TaskMetadataCard(info, attemptsUsed) } }
        item {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.task_description),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val description = detail?.description?.trim().orEmpty()
                    if (description.isBlank())
                        Text(
                            stringResource(
                                if (loading) R.string.task_loading else R.string.task_no_description
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    else {
                        val images = remember(description) { descriptionImageUrls(description) }
                        HtmlText(description.replace(IMAGE_TAG, ""))
                        images.forEach { image ->
                            AsyncImage(
                                model = image,
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        if (!detail?.attachments.isNullOrEmpty())
            item { AttachmentCard(detail.attachments, onOpenAttachment) }
        if (!detail?.rubric.isNullOrEmpty()) item { RubricCard(detail.rubric) }
        item { SubmissionSummary(submission) }
        val previousSubmissions =
            detail
                ?.submissionHistory
                .orEmpty()
                .filter { it.id != submission?.id }
                .sortedByDescending { it.attempt ?: 0 }
        if (previousSubmissions.isNotEmpty()) item { SubmissionHistory(previousSubmissions) }
        if (!submission?.comments.isNullOrEmpty()) item { CommentsCard(submission) }
        if (!supportedModes.isEmpty() && !locked && attemptsRemaining)
            item {
                SubmissionForm(
                    modes = supportedModes,
                    selectedMode = mode,
                    onMode = { mode = it },
                    text = text,
                    onText = { text = it },
                    url = url,
                    onUrl = { url = it },
                    files = selectedFiles,
                    onPickFile = { picker.launch(arrayOf("*/*")) },
                    onRemoveFile = onRemoveFile,
                    submitting = submitting,
                    submitError = submitError,
                    submitSuccess = submitSuccess,
                    onSubmit = { onSubmit(mode, text, url) },
                )
            }
        if (!supportedModes.isEmpty() && (!attemptsRemaining || locked))
            item {
                Notice(
                    stringResource(
                        if (locked) R.string.task_locked else R.string.task_attempts_exhausted
                    ),
                    error = true,
                )
            }
        val unsupported =
            detail?.submissionTypes.orEmpty().filter {
                it in setOf("online_quiz", "discussion_topic", "external_tool", "on_paper")
            }
        if (unsupported.isNotEmpty())
            item {
                Notice(
                    stringResource(
                        R.string.task_native_limits,
                        unsupported.joinToString("、", transform = ::submissionTypeLabel),
                    )
                )
            }
    }
}

@Composable
private fun TaskMetadataCard(detail: CanvasAssignment, attemptsUsed: Int) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val types = detail.submissionTypes.map(::submissionTypeLabel).filter(String::isNotBlank)
            if (types.isNotEmpty())
                MetaRow(stringResource(R.string.task_submission_mode), types.joinToString(" · "))
            MetaRow(
                stringResource(R.string.task_attempts),
                if (detail.allowedAttempts == null || detail.allowedAttempts < 0)
                    stringResource(R.string.task_attempts_unlimited, attemptsUsed)
                else
                    stringResource(
                        R.string.task_attempts_used,
                        attemptsUsed,
                        detail.allowedAttempts,
                    ),
            )
            detail.unlockAt?.takeIf(String::isNotBlank)?.let {
                MetaRow(stringResource(R.string.task_unlock_at), displayDate(it))
            }
            detail.lockAt?.takeIf(String::isNotBlank)?.let {
                MetaRow(stringResource(R.string.task_lock_at), displayDate(it))
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            Modifier.width(96.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AttachmentCard(
    attachments: List<edu.fudan.elearning.sync.network.CanvasAttachment>,
    onOpen: (Long) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.task_attachments),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            attachments.forEach { attachment ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onOpen(attachment.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.task_attachment_short),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            attachment.displayName.ifBlank { attachment.filename },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            FileUtils.formatBytes(attachment.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RubricCard(rubric: List<CanvasRubricCriterion>) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.task_rubric),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            rubric.forEach { criterion ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            criterion.description,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.task_rubric_points, criterion.points),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (criterion.longDescription.isNotBlank()) HtmlText(criterion.longDescription)
                    criterion.ratings.forEach { rating ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "• ${rating.description}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(R.string.task_rubric_points, rating.points),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubmissionSummary(submission: CanvasSubmission?) {
    val submitted =
        submission?.submittedAt?.isNotBlank() == true ||
            submission?.workflowState in setOf("submitted", "graded", "pending_review")
    Surface(
        shape = MaterialTheme.shapes.large,
        color =
            if (submitted) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface,
        border =
            if (submitted) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.task_submission_state),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    stringResource(
                        if (submitted) R.string.task_submitted else R.string.task_not_submitted
                    )
                )
                submission?.score?.let { StatusBadge(stringResource(R.string.task_score, it)) }
                submission?.grade?.takeIf(String::isNotBlank)?.let { StatusBadge(it) }
            }
            submission?.submittedAt?.takeIf(String::isNotBlank)?.let {
                Text(
                    stringResource(R.string.task_submitted_at, displayDate(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            submission?.body?.takeIf(String::isNotBlank)?.let { body ->
                Text(
                    stringResource(R.string.task_submitted_text),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HtmlText(body)
            }
            submission?.url?.takeIf(String::isNotBlank)?.let { link ->
                Text(link, style = MaterialTheme.typography.bodySmall)
            }
            submission?.attachments?.forEach { attachment ->
                Text(
                    "${attachment.displayName.ifBlank { attachment.filename }} · " +
                        FileUtils.formatBytes(attachment.size),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CommentsCard(submission: CanvasSubmission) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.task_comments),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            submission.comments.forEach { comment ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            comment.authorName.orEmpty(),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        comment.createdAt?.takeIf(String::isNotBlank)?.let {
                            Text(
                                displayDate(it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HtmlText(comment.comment.orEmpty())
                }
            }
        }
    }
}

/** 已提交内容的历史记录；评语按每次提交折叠，避免长任务详情被评论淹没。 */
@Composable
private fun SubmissionHistory(submissions: List<CanvasSubmission>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.task_submission_history),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        submissions.forEach { submission ->
            SubmissionHistoryItem(submission)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun SubmissionHistoryItem(submission: CanvasSubmission) {
    var showComments by rememberSaveable(submission.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.task_submission_attempt, submission.attempt ?: 0),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
            submission.submittedAt?.takeIf(String::isNotBlank)?.let {
                Text(
                    displayDate(it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        submission.score?.let {
            Text(
                stringResource(R.string.task_score, it),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        submission.body?.takeIf(String::isNotBlank)?.let { HtmlText(it) }
        submission.attachments.forEach { attachment ->
            Text(
                "${attachment.displayName.ifBlank { attachment.filename }} · " +
                    FileUtils.formatBytes(attachment.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (submission.comments.isNotEmpty()) {
            TextButton(onClick = { showComments = !showComments }) {
                if (showComments) Text(stringResource(R.string.task_hide_comments))
                else Text(stringResource(R.string.task_view_comments, submission.comments.size))
            }
            if (showComments)
                submission.comments.forEach { comment ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            listOfNotNull(
                                    comment.authorName?.takeIf(String::isNotBlank),
                                    comment.createdAt
                                        ?.takeIf(String::isNotBlank)
                                        ?.let(::displayDate),
                                )
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        HtmlText(comment.comment.orEmpty())
                    }
                }
        }
    }
}

@Composable
private fun SubmissionForm(
    modes: List<TaskSubmissionMode>,
    selectedMode: TaskSubmissionMode,
    onMode: (TaskSubmissionMode) -> Unit,
    text: String,
    onText: (String) -> Unit,
    url: String,
    onUrl: (String) -> Unit,
    files: List<TaskSubmissionFile>,
    onPickFile: () -> Unit,
    onRemoveFile: (TaskSubmissionFile) -> Unit,
    submitting: Boolean,
    submitError: String?,
    submitSuccess: String?,
    onSubmit: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.task_submit_heading),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (modes.size > 1)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { option ->
                        FilterChip(
                            selected = selectedMode == option,
                            onClick = { onMode(option) },
                            enabled = !submitting,
                            label = { Text(stringResource(submissionModeLabel(option))) },
                        )
                    }
                }
            when (selectedMode) {
                TaskSubmissionMode.FILE -> {
                    OutlinedButton(
                        onClick = onPickFile,
                        enabled = !submitting && files.size < 10,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.task_choose_files))
                    }
                    if (files.isEmpty())
                        Text(
                            stringResource(R.string.task_no_files_selected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    files.forEach { file ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                file.displayName,
                                Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                FileUtils.formatBytes(file.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { onRemoveFile(file) }, enabled = !submitting) {
                                Text(stringResource(R.string.task_remove_file))
                            }
                        }
                    }
                }
                TaskSubmissionMode.TEXT ->
                    OutlinedTextField(
                        value = text,
                        onValueChange = onText,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        label = { Text(stringResource(R.string.task_text_content)) },
                        minLines = 6,
                    )
                TaskSubmissionMode.URL ->
                    OutlinedTextField(
                        value = url,
                        onValueChange = onUrl,
                        enabled = !submitting,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.task_url_content)) },
                        singleLine = true,
                    )
            }
            if (submitError != null) Notice(submitError, error = true)
            if (submitSuccess != null) Notice(submitSuccess)
            Button(onClick = onSubmit, enabled = !submitting, modifier = Modifier.fillMaxWidth()) {
                if (submitting) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (submitting) R.string.task_submitting else R.string.task_submit
                    )
                )
            }
        }
    }
}

@Composable
private fun HtmlText(html: String) {
    if (html.isBlank()) return
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                movementMethod = LinkMovementMethod.getInstance()
                setLineSpacing(0f, 1.25f)
                textSize = 15f
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        },
        update = { view ->
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
            view.text =
                if (html.contains('<')) Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT) else html
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun supportedSubmissionModes(detail: CanvasAssignment?): List<TaskSubmissionMode> {
    val types = detail?.submissionTypes.orEmpty()
    return buildList {
        if (types.any { it == "online_upload" || it == "media_recording" })
            add(TaskSubmissionMode.FILE)
        if ("online_text_entry" in types) add(TaskSubmissionMode.TEXT)
        if ("online_url" in types) add(TaskSubmissionMode.URL)
    }
}

private fun submissionTypeLabel(type: String): String =
    when (type) {
        "online_upload" -> "文件提交"
        "online_text_entry" -> "文字提交"
        "online_url" -> "网址提交"
        "online_quiz" -> "在线测验"
        "discussion_topic" -> "讨论"
        "media_recording" -> "媒体提交"
        "external_tool" -> "外部工具"
        "on_paper" -> "纸质提交"
        "none" -> "无需提交"
        else -> type
    }

private fun submissionModeLabel(mode: TaskSubmissionMode): Int =
    when (mode) {
        TaskSubmissionMode.FILE -> R.string.task_mode_file
        TaskSubmissionMode.TEXT -> R.string.task_mode_text
        TaskSubmissionMode.URL -> R.string.task_mode_url
    }

private val IMAGE_TAG =
    Regex("""<img\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val IMAGE_SRC =
    Regex(
        """\bsrc\s*=\s*["']([^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

private fun descriptionImageUrls(html: String): List<String> =
    IMAGE_TAG.findAll(html)
        .mapNotNull { IMAGE_SRC.find(it.value)?.groupValues?.getOrNull(1) }
        .map { src ->
            runCatching { java.net.URI("https://elearning.fudan.edu.cn/").resolve(src).toString() }
                .getOrDefault(src)
        }
        .filter { it.startsWith("https://") || it.startsWith("http://") }
        .distinct()
        .take(20)
        .toList()
