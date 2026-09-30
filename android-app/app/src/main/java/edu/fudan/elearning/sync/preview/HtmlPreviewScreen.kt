package edu.fudan.elearning.sync.preview

import android.text.method.LinkMovementMethod
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.office.TextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 与桌面端 HTML 预览上限一致（4 MiB）。 */
private const val MAX_HTML_BYTES = 4L * 1024 * 1024

/**
 * 应用内 HTML 富文本预览（取代此前直接显示源文本）。
 *
 * 渲染策略与产品边界（与桌面端 gui/previewer.py 的 HTML 分支一致）：
 * - 按 HTML 富文本排版渲染基础标签（标题、加粗、列表、表格、链接等）；
 * - 不执行脚本，不加载远程图片（课程资料里的本地化正文已足够）；
 * - 超大文件截断到 [MAX_HTML_BYTES] 并明确提示；
 * - 链接可点击，交给系统浏览器打开（外部导航）。
 */
@Composable
fun HtmlPreviewScreen(file: File) {
    var html by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var truncated by remember { mutableStateOf(false) }
    var fileSize by remember { mutableStateOf(0L) }

    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            try {
                fileSize = file.length()
                truncated = fileSize > MAX_HTML_BYTES
                val limit = minOf(fileSize, MAX_HTML_BYTES).toInt().coerceAtLeast(0)
                val raw = ByteArray(limit)
                file.inputStream().use { stream ->
                    // InputStream.read 不保证一次读满，必须循环读到 EOF 或缓冲满
                    var offset = 0
                    while (offset < raw.size) {
                        val read = stream.read(raw, offset, raw.size - offset)
                        if (read <= 0) break
                        offset += read
                    }
                }
                // 去掉 UTF-8 BOM；UTF-8 出现替换字符时回退 GB18030（校园常见编码）
                val start = if (raw.size >= 3 &&
                    raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
                ) 3 else 0
                html = decodeHtmlText(raw, start)
            } catch (e: Exception) {
                error = "无法读取文件：${e.message ?: "未知错误"}"
            }
        }
    }

    when {
        error != null -> PreviewError(error!!)
        html == null -> Box(Modifier.fillMaxSize()) { /* 等待读取，PreviewScreen 外层已有加载态 */ }
        else -> Column(Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.html_limitation_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            )
            if (truncated) {
                Text(
                    stringResource(
                        R.string.html_truncated,
                        formatBytes(fileSize), formatBytes(MAX_HTML_BYTES)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            val scroll = rememberScrollState()
            Box(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)
            ) {
                // 富文本渲染：TextView 原生支持基础 HTML 标签，比纯源文本可读得多；
                // 不引入 WebView 是为了避免会话/JS/远程资源带来的复杂安全面
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        android.widget.TextView(context).apply {
                            // 让 <a> 可点击：点击后由系统浏览器打开
                            movementMethod = LinkMovementMethod.getInstance()
                            textSize = 14f
                            setLineSpacing(4f, 1.2f)
                        }
                    },
                    update = { textView ->
                        textView.text = HtmlCompat.fromHtml(
                            html.orEmpty(), HtmlCompat.FROM_HTML_MODE_COMPACT
                        )
                    }
                )
            }
        }
    }
}

/**
 * HTML 解码：先按 UTF-8 解；出现替换字符（典型 GBK 文件）时改用 GB18030。
 * 与文本预览同策略，清洗掉控制字符。
 */
private fun decodeHtmlText(raw: ByteArray, start: Int): String {
    val utf8 = String(raw, start, raw.size - start, Charsets.UTF_8)
    val utf8Bad = utf8.count { it == '\uFFFD' }
    if (utf8Bad == 0) return TextSanitizer.clean(utf8)
    val gbk = runCatching {
        String(raw, start, raw.size - start, charset("GB18030"))
    }.getOrNull() ?: return TextSanitizer.clean(utf8)
    return TextSanitizer.clean(if (gbk.count { it == '\uFFFD' } < utf8Bad) gbk else utf8)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
