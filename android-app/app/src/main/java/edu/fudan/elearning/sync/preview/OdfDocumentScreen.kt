package edu.fudan.elearning.sync.preview

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.office.TextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

/**
 * ODF（.odt / .ods / .odp）应用内文档视图（v1.1.2 新增）。
 *
 * 旧实现把 ODF 当「结构化降级」丢给纯文本抽取，连标题层级和内嵌图片都没有。
 * 现在按文档结构还原：标题分级、段落、列表、表格（文本矩阵）与内嵌图片
 * （PNG/JPEG 直接解码）。这只是**文档视图**，不是排版引擎：
 * 分栏、脚注、域代码、复杂数学排版等不做高保真还原——会展示为纯文本。
 */
@Composable
fun OdfDocumentScreen(file: File) {
    var state by remember(file.absolutePath) { mutableStateOf<OdfParseState>(OdfParseState.Loading) }

    LaunchedEffect(file.absolutePath) {
        state = withContext(Dispatchers.IO) {
            runCatching { OdfParser.parse(file) }
                .fold(
                    onSuccess = { blocks ->
                        if (blocks.isEmpty()) OdfParseState.Error("该文档没有可显示的正文内容")
                        else OdfParseState.Ready(blocks)
                    },
                    onFailure = { OdfParseState.Error("无法解析 ODF 文档：${it.message ?: it.javaClass.simpleName}") }
                )
        }
    }

    when (val s = state) {
        OdfParseState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is OdfParseState.Error -> PreviewError(s.message, title = "无法显示文档")
        is OdfParseState.Ready -> LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "ODF 文档视图：结构化渲染文本、表格与图片；分栏/脚注等复杂排版不保证还原。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(s.blocks.size) { index ->
                OdfBlockView(s.blocks[index])
            }
        }
    }
}

@Composable
private fun OdfBlockView(block: OdfParser.Block) {
    when (block) {
        is OdfParser.Block.Heading -> Text(
            TextSanitizer.clean(block.text),
            style = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp)
        )
        is OdfParser.Block.Paragraph -> Text(
            TextSanitizer.clean(block.text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth()
        )
        is OdfParser.Block.ListItem -> Row(Modifier.fillMaxWidth()) {
            Text("• ", style = MaterialTheme.typography.bodyMedium)
            Text(
                TextSanitizer.clean(block.text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
        is OdfParser.Block.Table -> Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            block.rows.forEach { row ->
                Text(
                    row.joinToString(" ｜ "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        is OdfParser.Block.Image -> {
            val bitmap = remember(block.data) {
                runCatching { BitmapFactory.decodeByteArray(block.data, 0, block.data.size) }
                    .getOrNull()?.asImageBitmap()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "文档内嵌图片",
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    "（内嵌图片无法解码）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---------- 界面状态（解析逻辑见 OdfParser，独立对象便于插桩测试） ----------

private sealed class OdfParseState {
    object Loading : OdfParseState()
    data class Ready(val blocks: List<OdfParser.Block>) : OdfParseState()
    data class Error(val message: String) : OdfParseState()
}
