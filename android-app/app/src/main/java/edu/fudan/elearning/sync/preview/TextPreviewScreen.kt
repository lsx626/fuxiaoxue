package edu.fudan.elearning.sync.preview

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.office.TextSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 单次读取的最大字节数，与桌面端 text 预览上限一致。 */
private const val MAX_BYTES = 2L * 1024 * 1024

/**
 * v1.2.3：渐进式渲染参数——一次把 2 MiB 的正文全部丢给一个 Text 布局，
 * 滚动和排版都会明显卡顿。先渲染前 [INITIAL_SHOWN_CHARS] 个字符，
 * 滚到接近底部时按 [MORE_CHARS] 分块扩展。
 */
private const val INITIAL_SHOWN_CHARS = 64 * 1024
private const val MORE_CHARS = 64 * 1024

/**
 * 应用内文本/代码/CSV/Markdown 预览。
 *
 * 硬性约束：最多读取 [MAX_BYTES] 字节，分块流式解码，禁止一次性把超大文件塞入内存。
 * 超出上限时尾部显示截断提示。UTF-8-sig 解码并容错。
 */
@Composable
fun TextPreviewScreen(file: File) {
    // v1.2.1：状态以 file 键化——预览内翻文件时旧正文不会滞留在新标题下。
    val fileKey = file.absolutePath
    var content by remember(fileKey) { mutableStateOf("") }
    var truncated by remember(fileKey) { mutableStateOf(false) }
    var error by remember(fileKey) { mutableStateOf<String?>(null) }
    var fileSize by remember(fileKey) { mutableStateOf(0L) }

    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            try {
                fileSize = file.length()
                val limit = minOf(fileSize, MAX_BYTES)
                val raw = ByteArray(limit.toInt().coerceAtLeast(0))
                file.inputStream().use { stream ->
                    // InputStream.read 不保证一次读满：必须循环读到 EOF 或缓冲满，
                    // 否则大文件会被静默截断（还不会触发 truncated 提示）
                    var offset = 0
                    while (offset < raw.size) {
                        val read = stream.read(raw, offset, raw.size - offset)
                        if (read <= 0) break
                        offset += read
                    }
                }
                // 去掉 UTF-8 BOM
                val start = if (raw.size >= 3 &&
                    raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()
                ) 3 else 0
                content = decodeText(raw, start)
                truncated = fileSize > MAX_BYTES
            } catch (e: Exception) {
                error = "无法读取文件：${e.message ?: "未知错误"}"
            }
        }
    }

    when {
        error != null -> PreviewError(error!!)
        else -> {
            Column(Modifier.fillMaxSize()) {
                if (truncated) {
                    Text(
                        "文件较大（${formatBytes(fileSize)}），仅显示前 ${formatBytes(MAX_BYTES)} 内容",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                val scroll = rememberScrollState()
                // v1.2.3：渐进渲染——只渲染「当前已展开」的部分，滚到底部附近再扩展
                var shownChars by remember(fileKey) { mutableStateOf(INITIAL_SHOWN_CHARS) }
                val displayed = remember(content, shownChars) {
                    if (content.length > shownChars) content.substring(0, shownChars) else content
                }
                LaunchedEffect(scroll.value, scroll.maxValue, displayed.length, content.length) {
                    if (content.length > displayed.length &&
                        scroll.maxValue - scroll.value < 1500
                    ) {
                        shownChars += MORE_CHARS
                    }
                }
                Box(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(12.dp)
                ) {
                    Text(
                        text = displayed,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.4f
                        ),
                        softWrap = true
                    )
                }
            }
        }
    }
}

/**
 * 文本解码：先按 UTF-8 解；出现替换字符（典型 GBK 文件）时改用 GB18030。
 *
 * 校园资料里 GBK/GB18030 编码的 CSV、TXT 很常见，只按 UTF-8 解会整篇花屏。
 */
private fun decodeText(raw: ByteArray, start: Int): String {
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
