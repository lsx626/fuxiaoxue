package edu.fudan.elearning.sync.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.office.OfficePreviewScreen
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 走富文本渲染的 HTML 扩展名（与桌面端 previewer 的 HTML 集合一致）。 */
private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

/** ODF 三格式：结构化文档视图（v1.1.2）。 */
private val ODF_EXTS = setOf("odt", "ods", "odp")

/**
 * 统一预览路由：文件存在性/权限检查 -> [FileTypes] 类型识别 -> 分发到具体预览。
 *
 * 这是 FileUtils.openFile() 的应用内目标，取代原来的 ACTION_VIEW 跳转。 保留分享入口（仅授予临时只读 URI 权限）。
 *
 * [displayName] 为列表中显示的文件名（Canvas display_name），顶栏标题以它为准； 空值时兜底用磁盘文件名。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    file: File,
    displayName: String = file.name,
    onBack: () -> Unit,
    onShare: (File) -> Unit,
    // v1.1.2：预览内翻文件与阅读进度（无上下文时传默认值，预览照常工作）
    siblingCount: Int = 0,
    siblingIndex: Int = -1,
    onNavigateSibling: (Int) -> Unit = {},
    initialPage: Int = -1,
    onPageChanged: (page: Int, total: Int) -> Unit = { _, _ -> },
    initialMediaSec: Int = -1,
    onMediaPositionChanged: (sec: Int, totalSec: Int) -> Unit = { _, _ -> },
) {
    val title = remember(displayName, file.name) { displayName.ifBlank { file.name } }
    val probe by
        produceState<Pair<Boolean, PreviewKind>?>(null, file.absolutePath) {
            value = withContext(Dispatchers.IO) { file.isFile to FileTypes.detect(file) }
        }
    val kind = probe?.second
    val exists = probe?.first == true
    val canPreview = exists && kind != PreviewKind.UNSUPPORTED
    var showFind by remember(file.absolutePath) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (siblingIndex >= 0)
                            Text(
                                stringResource(
                                    R.string.preview_file_position,
                                    siblingIndex + 1,
                                    siblingCount,
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (kind == PreviewKind.TEXT && FileTypes.extOf(file.name) !in HTML_EXTENSIONS)
                        IconButton(onClick = { showFind = !showFind }) {
                            Icon(Icons.Default.Search, "查找正文")
                        }
                    // 同课程的上/下一个文件（无上下文时隐藏）
                    if (siblingCount > 1) {
                        IconButton(
                            onClick = { onNavigateSibling(-1) },
                            enabled = siblingIndex > 0,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "上一个文件",
                            )
                        }
                        IconButton(
                            onClick = { onNavigateSibling(1) },
                            enabled = siblingIndex >= 0 && siblingIndex < siblingCount - 1,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "下一个文件",
                            )
                        }
                    }
                    if (exists) {
                        IconButton(onClick = { onShare(file) }) {
                            Icon(Icons.Filled.Share, contentDescription = "分享文件")
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            key(file.absolutePath) {
                when {
                    probe == null ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    !exists -> PreviewError("文件不存在，可能尚未下载或已被删除。")
                    !canPreview ->
                        PreviewError(
                            message =
                                "暂不支持在应用内预览此格式（" +
                                    FileTypes.extOf(file.name).ifEmpty { "未知类型" } +
                                    "）。可通过右上角分享，交给其他应用打开。",
                            title = "不支持的格式",
                            actionLabel = stringResource(R.string.preview_open_other),
                            onAction = { onShare(file) },
                        )
                    else ->
                        when (kind) {
                            PreviewKind.PDF ->
                                PdfPreviewScreen(
                                    file,
                                    initialPage = initialPage,
                                    onPageChanged = onPageChanged,
                                )
                            PreviewKind.IMAGE -> ImagePreviewScreen(file)
                            // html/htm 走富文本渲染（不再显示源文本），其余文本/代码走源码视图
                            PreviewKind.TEXT ->
                                if (FileTypes.extOf(file.name) in HTML_EXTENSIONS)
                                    HtmlPreviewScreen(file)
                                else TextPreviewScreen(file, showFind)
                            PreviewKind.MEDIA ->
                                MediaPreviewScreen(
                                    file,
                                    initialPositionSec = initialMediaSec,
                                    onPositionChanged = onMediaPositionChanged,
                                )
                            PreviewKind.OFFICE ->
                                OfficePreviewScreen(
                                    file,
                                    displayName = title,
                                    initialPage = initialPage,
                                    onPageChanged = onPageChanged,
                                )
                            PreviewKind.STRUCTURED ->
                                // v1.1.2：ODF 三格式走结构化文档视图（标题/列表/表格/图片），
                                // 其余（zip/rtf 等）仍是文本降级
                                if (
                                    FileTypes.extOf(file.name) in setOf("zip", "jar", "apk", "epub")
                                )
                                    ArchivePreviewScreen(file)
                                else if (FileTypes.extOf(file.name) in ODF_EXTS)
                                    OdfDocumentScreen(file)
                                else OfficeFallbackScreen(file, title)
                            PreviewKind.UNSUPPORTED ->
                                PreviewError(
                                    message = "暂不支持在应用内预览此格式。",
                                    actionLabel = stringResource(R.string.preview_open_other),
                                    onAction = { onShare(file) },
                                )
                            null -> Unit
                        }
                }
            }
        }
    }
}
