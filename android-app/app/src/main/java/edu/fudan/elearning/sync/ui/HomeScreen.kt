package edu.fudan.elearning.sync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.fudan.elearning.sync.BuildConfig
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.data.CourseStats
import edu.fudan.elearning.sync.data.FileItem
import edu.fudan.elearning.sync.util.FileUtils

private enum class Tab { COURSES, STORAGE, SETTINGS }

/**
 * 主界面（重绘版）：靛蓝渐变顶栏 + Canvas 自绘进度环 + 现代卡片，
 * 保持 Material 3 与品牌色，覆盖空数据/加载/同步中状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: AppViewModel) {
    val courses by viewModel.courses.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()
    val loginState by viewModel.loginState.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(Tab.COURSES) }
    val selectedCourse by viewModel.selectedCourse.collectAsState()

    // 系统返回键：在文件列表层返回课程列表，而不是直接退出应用
    BackHandler(enabled = selectedCourse != null) {
        viewModel.clearCourseSelection()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            selectedCourse?.course?.name ?: "复小学",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!syncing && selectedCourse == null) {
                            Text(
                                "复旦大学 eLearning 课程资料",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (selectedCourse != null) {
                        IconButton(onClick = { viewModel.clearCourseSelection() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回课程列表"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openSearch() }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "搜索文件",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    SyncButton(syncing = syncing, onClick = { viewModel.sync() })
                    HomeOverflowMenu(viewModel)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            if (selectedCourse == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == Tab.COURSES,
                        onClick = { selectedTab = Tab.COURSES },
                        icon = { navIcon(R.drawable.ic_school, selectedTab == Tab.COURSES) },
                        label = { Text("课程") },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == Tab.STORAGE,
                        onClick = { selectedTab = Tab.STORAGE },
                        icon = { navIcon(R.drawable.ic_folder, selectedTab == Tab.STORAGE) },
                        label = { Text("存储") },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == Tab.SETTINGS,
                        onClick = { selectedTab = Tab.SETTINGS },
                        icon = {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = null,
                                tint = if (selectedTab == Tab.SETTINGS)
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        label = { Text("设置") },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Indigo,
                        0.14f to IndigoDark,
                        0.16f to MaterialTheme.colorScheme.background,
                        1f to MaterialTheme.colorScheme.background
                    )
                )
            ).padding(padding)
        ) {
            AnimatedVisibility(visible = syncing, enter = fadeIn(), exit = fadeOut()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                    )
                    Text(
                        syncProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            SyncErrorBanner(viewModel)
            when {
                selectedCourse != null -> FileListView(viewModel, selectedCourse!!)
                selectedTab == Tab.COURSES ->
                    Column {
                        UpcomingDeadlines(viewModel)
                        CourseListView(viewModel, courses) { viewModel.selectCourse(it.course.id) }
                    }
                selectedTab == Tab.STORAGE -> StorageView(viewModel, courses)
                else -> SettingsView(viewModel, loginState)
            }
        }
    }
}

/**
 * 最近截止的作业（v3 起）：同步时从 Canvas assignments 采集 `due_at`。
 * 没有截止时间时不占位；有截至作业时按时间升序展示最多 3 项。
 */
@Composable
private fun UpcomingDeadlines(viewModel: AppViewModel) {
    val upcoming by viewModel.upcoming.collectAsState()
    if (upcoming.isEmpty()) {
        Text(
            stringResource(R.string.upcoming_deadlines_empty),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(R.string.upcoming_deadlines_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(6.dp))
            upcoming.take(3).forEach { assignment ->
                Text(
                    "【${formatDueAt(assignment.dueAt)}】${assignment.name}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

/** Canvas due_at（ISO8601 带偏移）转成本地可读的「MM-dd HH:mm」。 */
private fun formatDueAt(dueAt: String): String {
    if (dueAt.isEmpty()) return "无截止"
    return runCatching {
        val parsed = java.time.OffsetDateTime.parse(dueAt)
        val local = parsed.atZoneSameInstant(java.time.ZoneId.systemDefault())
        local.format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrElse { dueAt.take(16) }
}

/** 顶栏溢出菜单：全量同步等次要但常用的操作。 */
@Composable
private fun HomeOverflowMenu(viewModel: AppViewModel) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_sync_now)) },
            onClick = { expanded = false; viewModel.sync() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_sync_full)) },
            onClick = { expanded = false; viewModel.sync(full = true) }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_refresh)) },
            onClick = { expanded = false; viewModel.refreshCourses() }
        )
    }
}

/**
 * 同步失败横幅：如实说明失败原因，并提供重试/关闭。
 *
 * 历史缺陷是把「登录失效」「被限流」「网络错误」都显示成「同步完成」，
 * 用户既不知道出了什么事，也不知道能做什么。
 */
@Composable
private fun SyncErrorBanner(viewModel: AppViewModel) {
    val error by viewModel.syncError.collectAsState()
    val message = error ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                stringResource(R.string.sync_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.clearSyncError() }) {
                    Text(stringResource(R.string.action_close))
                }
                TextButton(onClick = {
                    viewModel.clearSyncError()
                    viewModel.sync()
                }) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}
/** 顶栏同步按钮：同步中显示脉冲圆点动画。 */
@Composable
private fun SyncButton(syncing: Boolean, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "sync_pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    TextButton(onClick = onClick, enabled = !syncing) {
        if (syncing) {
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = alpha))
            )
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(if (syncing) "同步中" else "立即同步")
    }
}

/** 底部导航自绘图标（按选中态着色）。 */
@Composable
private fun navIcon(drawableRes: Int, selected: Boolean) {
    Image(
        painter = painterResource(drawableRes),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        colorFilter = ColorFilter.tint(
            if (selected) MaterialTheme.colorScheme.onSecondaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/** Canvas 自绘圆环进度（课程已下载占比）。 */
@Composable
private fun ProgressRing(percent: Float, modifier: Modifier = Modifier.size(46.dp)) {
    val track = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val progress = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = size.minDimension * 0.11f
        val inset = stroke / 2 + size.minDimension * 0.04f
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(
            color = track,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawArc(
            color = progress,
            startAngle = -90f,
            sweepAngle = 360f * percent.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** 课程列表。 */
@Composable
private fun CourseListView(
    viewModel: AppViewModel,
    courses: List<CourseStats>,
    onOpen: (CourseStats) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (courses.isEmpty()) {
            item { EmptyCourseState() }
        }
        items(courses, key = { it.course.id }) { stats ->
            CourseCard(stats) { onOpen(stats) }
        }
    }
}

/** 空课程态：Canvas 自绘文档插画 + 引导。 */
@Composable
private fun EmptyCourseState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(Modifier.size(96.dp)) {
            val w = size.width
            val h = size.height
            drawCircle(color = Indigo.copy(alpha = 0.08f), radius = w / 2, center = center)
            val docColor = Indigo.copy(alpha = 0.85f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.28f, h * 0.18f)
                lineTo(w * 0.62f, h * 0.18f)
                lineTo(w * 0.74f, h * 0.30f)
                lineTo(w * 0.74f, h * 0.82f)
                lineTo(w * 0.28f, h * 0.82f)
                close()
            }
            drawPath(path, docColor)
            val fold = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.62f, h * 0.18f)
                lineTo(w * 0.62f, h * 0.30f)
                lineTo(w * 0.74f, h * 0.30f)
                close()
            }
            drawPath(fold, IndigoDark)
            for (i in 0..2) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(w * 0.36f, h * (0.40f + i * 0.11f)),
                    size = Size(w * (if (i == 2) 0.24f else 0.32f), h * 0.045f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.02f, h * 0.02f)
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "暂无课程",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "点击右上角「立即同步」拉取你的课程资料",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CourseCard(stats: CourseStats, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val percent = if (stats.filesTotal > 0) stats.filesDone.toFloat() / stats.filesTotal else 0f
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(percent)
                Text(
                    "${(percent * 100).toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stats.course.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (stats.course.term.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stats.course.term,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${stats.filesDone}/${stats.filesTotal} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        " · ${FileUtils.formatBytes(stats.bytesDownloaded)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
/** 文件类型徽标颜色。 */
private fun typeBadgeColor(ext: String): Color = when (ext) {
    "pdf" -> Color(0xFFD64545)
    "ppt", "pptx" -> Color(0xFFE07B39)
    "doc", "docx" -> Color(0xFF3B82F6)
    "xls", "xlsx" -> Color(0xFF22A06B)
    "mp4", "mkv", "mov", "avi", "webm" -> Color(0xFF8B5CF6)
    "mp3", "m4a", "flac", "wav" -> Color(0xFFEC4899)
    "png", "jpg", "jpeg", "gif", "webp" -> Color(0xFF0EA5E9)
    else -> Color(0xFF8A8F98)
}

/** 文件列表（某课程）。 */
@Composable
private fun FileListView(viewModel: AppViewModel, stats: CourseStats) {
    // 数据版本号作为 key：删除/同步后必须立刻反映数据库变化，
    // 不能只按课程 id 缓存（历史缺陷：删完文件界面还在显示旧列表）。
    val dataVersion by viewModel.dataVersion.collectAsState()
    // filesOf 是挂起函数（查询在 IO 线程）：用 produceState 异步加载，
    // 不在组合期阻塞主线程
    val files by produceState(emptyList<FileItem>(), stats.course.id, dataVersion) {
        value = viewModel.filesOf(stats.course.id)
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (files.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "该课程暂无文件",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "同步后文件会自动出现在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        items(files, key = { it.fileId }) { file ->
            FileRow(file, viewModel)
        }
    }
}

@Composable
private fun FileRow(file: FileItem, viewModel: AppViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val ext = file.filename.substringAfterLast('.', "").lowercase()
            if (ext.isNotEmpty()) {
                Box(
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                        .background(typeBadgeColor(ext).copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        ext.take(3).uppercase(),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = typeBadgeColor(ext)
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        FileUtils.formatBytes(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        " · ${FileUtils.statusText(file.status)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = when (file.status) {
                            "downloaded" -> MaterialTheme.colorScheme.primary
                            "failed" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                if (file.status == "failed") {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.file_retry_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                file.status == "downloaded" -> TextButton(onClick = { viewModel.openPreview(file) }) {
                    Text("预览")
                }
                file.status == "failed" ->
                    TextButton(onClick = { viewModel.retryFile(file) }) {
                        Text("重试")
                    }
                // 远端已删除：重试只会 404，还会把准确的 remote_missing 状态改写成
                // failed；这里显示不可操作的状态说明
                file.status == "remote_missing" -> Text(
                    "远端已删除",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                else -> Text(
                    stringResource(R.string.file_pending),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            IconButton(onClick = { viewModel.shareFile(file) }) {
                Icon(
                    Icons.Filled.Share,
                    contentDescription = "分享 ${file.name}",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
/** 存储管理：学期筛选 + 批量删除（删除前需二次确认）。 */
@Composable
private fun StorageView(viewModel: AppViewModel, courses: List<CourseStats>) {
    // terms 必须以 courses 为 key：同步出全新学期后不刷新会成为永久旧数据
    val terms = remember(courses) { courses.map { it.course.term }.filter { it.isNotEmpty() }.distinct() }
    var selectedTerm by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedCourse by remember { mutableStateOf<CourseStats?>(null) }
    var pendingDeleteCourse by remember { mutableStateOf<CourseStats?>(null) }
    var pendingDeleteFile by remember { mutableStateOf<FileItem?>(null) }

    val dataVersion by viewModel.dataVersion.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("按学期筛选", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    selected = selectedTerm == null,
                    onClick = { selectedTerm = null },
                    label = { Text("全部") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
            items(terms) { term ->
                FilterChip(
                    selected = selectedTerm == term,
                    onClick = { selectedTerm = term },
                    label = { Text(term) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        val filtered = courses.filter { selectedTerm == null || it.course.term == selectedTerm }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { it.course.id }) { stats ->
                StorageCourseCard(
                    stats,
                    expanded = expandedCourse?.course?.id == stats.course.id,
                    onToggle = {
                        expandedCourse =
                            if (expandedCourse?.course?.id == stats.course.id) null else stats
                    },
                    onDeleteAll = { pendingDeleteCourse = stats },
                    onDeleteFile = { pendingDeleteFile = it },
                    filesProvider = { viewModel.filesOf(stats.course.id) },
                    dataVersion = dataVersion
                )
            }
        }
    }

    pendingDeleteCourse?.let { stats ->
        ConfirmDialog(
            title = "删除课程文件",
            message = "将删除「${stats.course.name}」下的全部已下载文件，此操作不可撤销。",
            onConfirm = {
                viewModel.deleteCourseFiles(stats.course.id)
                pendingDeleteCourse = null
            },
            onDismiss = { pendingDeleteCourse = null }
        )
    }
    pendingDeleteFile?.let { file ->
        ConfirmDialog(
            title = "删除文件",
            message = "将删除「${file.name}」，此操作不可撤销。",
            onConfirm = {
                viewModel.deleteFile(file)
                pendingDeleteFile = null
            },
            onDismiss = { pendingDeleteFile = null }
        )
    }
}

@Composable
private fun StorageCourseCard(
    stats: CourseStats,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDeleteAll: () -> Unit,
    onDeleteFile: (FileItem) -> Unit,
    /** 挂起的文件查询（内部切 IO 线程，不阻塞组合） */
    filesProvider: suspend () -> List<FileItem>,
    /** 数据版本号：删除/同步后必须重新查询，不能缓存旧列表 */
    dataVersion: Int
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stats.course.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${stats.course.term} · ${FileUtils.formatBytes(stats.bytesDownloaded)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onToggle) { Text(if (expanded) "收起" else "查看文件") }
                TextButton(onClick = onDeleteAll) {
                    Text("删除全部", color = MaterialTheme.colorScheme.error)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                val files by produceState(emptyList<FileItem>(), stats.course.id, dataVersion) {
                    value = filesProvider()
                }
                if (files.isEmpty()) {
                    Text(
                        "暂无文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                files.forEach { file ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            file.name,
                            Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp
                        )
                        TextButton(onClick = { onDeleteFile(file) }) {
                            Text("删除", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
/** 设置：账号、同步频率、本地存储。 */
@Composable
private fun SettingsView(viewModel: AppViewModel, loginState: LoginState) {
    var interval by remember { mutableStateOf(viewModel.currentInterval()) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SectionTitle("账号")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(42.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            ((loginState as? LoginState.LoggedIn)?.username ?: "?")
                                .firstOrNull()?.uppercase() ?: "?",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("已登录", style = MaterialTheme.typography.titleSmall)
                        Text(
                            (loginState as? LoginState.LoggedIn)?.username ?: "",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { viewModel.logout() }) {
                        Text("退出登录", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            SectionTitle("后台同步频率")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(15, 30, 60, 120)) { m ->
                    FilterChip(
                        selected = interval == m,
                        onClick = {
                            interval = m
                            viewModel.setSyncInterval(m)
                        },
                        label = { Text("$m 分钟") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // 上次同步结果（含失败原因）：让用户能判断资料是否真的同步成功
            val dataVersion by viewModel.dataVersion.collectAsState()
            var lastSummary by remember { mutableStateOf("加载中…") }
            LaunchedEffect(dataVersion) {
                lastSummary = viewModel.lastSyncSummary()
            }
            Text(
                "上次同步：$lastSummary",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            SectionTitle("本地存储")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("下载目录", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        viewModel.downloadRoot,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "已下载文件可直接在应用内预览（PDF、图片、文本、音视频，Office 六格式逐页渲染），" +
                            "图表、SmartArt、嵌入对象等暂不支持高保真的元素会显示说明，"
                            + "也可通过系统分享面板交给其他工具处理。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            SectionTitle("关于")
            Card(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("复小学", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "用于同步并管理你有权访问的复旦大学 eLearning/Canvas 课程资料。"
                            + "支持 UIS 账号登录、定时增量同步与应用内预览。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "应用内预览：PDF、图片（含 GIF/HEIF）、文本/CSV（2MiB 上限）、"
                            + "音视频（Media3），以及 doc/docx/ppt/pptx/xls/xlsx 的逐页渲染。"
                            + "图表、SmartArt、OLE 嵌入与动画等复杂元素只做限制说明，不保证高保真还原。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "项目地址：github.com/lsx626/fuxiaoxue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}
