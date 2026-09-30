package edu.fudan.elearning.sync.office

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.preview.PreviewError
import edu.fudan.elearning.sync.preview.VerticalPageList
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Office 文档预览（doc/docx/ppt/pptx/xls/xlsx）：POI 解析为页模型后逐页位图渲染，
 * 纵向连续滚动（下拉式漫画体验），每页支持双指/双击缩放。
 *
 * 解析失败、空文档或不支持的格式会显示明确说明；复杂元素（图表、SmartArt、OLE
 * 嵌入等）不保证还原，在列表顶部以提示卡如实说明。
 *
 * [displayName] 是文件列表里显示的名字（Canvas `display_name`），顶栏与提示卡
 * 都以它为准，避免显示磁盘上可能被百分号编码的落盘名。
 */
@Composable
fun OfficePreviewScreen(
    file: File,
    displayName: String = file.name,
    initialPage: Int = -1,
    onPageChanged: (page: Int, total: Int) -> Unit = { _, _ -> }
) {
    val title = remember(displayName, file.name) { displayName.ifBlank { file.name } }
    var result by remember(file.absolutePath) { mutableStateOf<OfficeParseResult?>(null) }
    // 解析进度：(已完成, 总数)；解析器运行在 IO 线程，用 StateFlow 传递避免跨线程写状态
    val progress = remember(file.absolutePath) { MutableStateFlow<Pair<Int, Int>?>(null) }
    val progressState by progress.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(file.absolutePath) {
        result = OfficeExtractor.extract(context, file) { done, total ->
            progress.value = done to total
        }
    }

    when (val r = result) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                val p = progressState
                if (p != null && p.second > 0) {
                    Text(
                        stringResource(R.string.parsing_progress, p.first, p.second),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
        is OfficeParseResult.Success -> {
            val pages = r.pages
            VerticalPageList(
                pageCount = pages.size,
                aspectOf = { index ->
                    val p = pages[index]
                    if (p.heightPx > 0) p.widthPx.toFloat() / p.heightPx else 0.75f
                },
                renderPage = { index -> renderOfficePage(pages, index) },
                header = { OfficeLimitationNote(title) },
                initialPage = initialPage,
                onPageChanged = onPageChanged
            )
        }
        is OfficeParseResult.Empty -> PreviewError(r.reason, title = "没有可显示的内容")
        is OfficeParseResult.Unsupported -> PreviewError(
            message = "暂不支持在应用内预览此格式（${r.ext.ifEmpty { "未知类型" }}）。",
            title = "不支持的格式"
        )
        is OfficeParseResult.Failed -> PreviewError(r.message, title = "无法预览")
    }
}

/** 列表顶部的保真度说明：如实告知哪些内容可能不还原。 */
@Composable
private fun OfficeLimitationNote(name: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
            Text(
                stringResource(R.string.office_limitation_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 渲染单页为位图；长边上限 [MAX_DIMEN]。
 *
 * 提取器内部已按屏幕宽度限制页宽；这里对异常文档做长边兜底：超过上限时把
 * **页模型**等比缩小后再渲染，而不是直接放弃该页（旧实现返回 null，界面上
 * 表现为「该页无法渲染」）。先缩模型再画，避免「先分配超大位图」的内存尖峰。
 */
private fun renderOfficePage(pages: List<DocPage>, index: Int): Bitmap? = runCatching {
    if (index < 0 || index >= pages.size) return null
    val page = pages[index]
    val longest = maxOf(page.widthPx, page.heightPx)
    val target = if (longest > MAX_DIMEN) page.scaled(MAX_DIMEN.toFloat() / longest) else page
    PageRenderer.renderPage(target)
}.getOrNull()

private const val MAX_DIMEN = 4096
