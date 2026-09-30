package edu.fudan.elearning.sync.search

import android.content.Context
import edu.fudan.elearning.sync.office.OfficeExtractor
import edu.fudan.elearning.sync.office.OfficeParseResult
import edu.fudan.elearning.sync.office.PageItem
import java.nio.charset.Charset
import java.io.File

/**
 * 把本地文件抽成纯文本供搜索索引使用。
 *
 * 复用预览链路已有的解析器，不引入新依赖：
 * - 纯文本/代码：读前 [SearchIndex.MAX_INDEX_CHARS] 字符（UTF-8 失败回退
 *   GB18030——与 Android 文本预览 v1.0.12 的回退口径一致）；
 * - HTML：去标签留正文；
 * - Office 六格式：复用 [OfficeExtractor] 得到页模型后收集文本块；
 * - PDF / 图片 / 音视频 / 旧二进制 Office：**不抽取内容**，只索引文件名
 *   （Android 平台 PdfRenderer 只渲染不提供文本层；不在应用里内嵌新
 *   解析库ToDevice，与预览的降级说明口径一致，禁止假装成功）。
 *
 * 所有失败都返回 null，调用方只索引文件名——抽取失败绝不能影响同步。
 */
object TextExtractor {

    private const val MAX_READ_BYTES = 16 * 1024 * 1024

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "log", "ini",
        "cfg", "yml", "yaml", "py", "js", "ts", "java", "c", "h", "cpp",
        "hpp", "cs", "go", "rs", "sql", "sh", "bat", "ps1", "tex", "srt",
        "vtt", "toml", "properties"
    )
    private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml")

    /**
     * 抽取纯文本；返回 null 表示无内容可索引（失败 / 不支持的格式）。
     * [context] 仅为 OfficeExtractor 的渲染宽度探测所需。
     */
    suspend fun extractText(context: Context, file: File): String? = runCatching {
        if (!file.isFile) return null
        val ext = file.name.substringAfterLast('.', "").lowercase()
        when {
            TEXT_EXTENSIONS.contains(ext) -> extractPlain(file)
            HTML_EXTENSIONS.contains(ext) -> extractHtml(file)
            OfficeExtractor.isOffice(file.name) -> extractOffice(context, file)
            else -> null
        }
    }.getOrNull()

    /** 纯文本：UTF-8 优先，失败回退 GB18030/GBK。 */
    private fun extractPlain(file: File): String? {
        val raw = file.readBytesCopy(MAX_READ_BYTES)
        if (raw.isEmpty()) return null
        val text = decodeText(raw)
        return SearchIndex.cap(text).trim().ifEmpty { null }
    }

    /** HTML：去标签、转实体。 */
    private fun extractHtml(file: File): String? {
        val raw = file.readBytesCopy(MAX_READ_BYTES)
        if (raw.isEmpty()) return null
        val text = decodeText(raw)
        val stripped = stripHtml(text)
        return SearchIndex.cap(stripped).trim().ifEmpty { null }
    }

    /** Office：复用预览抽取器，从页模型收集全部文本。 */
    private suspend fun extractOffice(context: Context, file: File): String? {
        val result = OfficeExtractor.extract(context, file)
        return when (result) {
            is OfficeParseResult.Success -> {
                val builder = StringBuilder()
                for (page in result.pages) {
                    for (item in page.items) collectText(item, builder)
                }
                SearchIndex.cap(builder.toString()).trim().ifEmpty { null }
            }
            else -> null
        }
    }

    private fun collectText(item: PageItem, builder: StringBuilder) {
        when (item) {
            is PageItem.TextBlock -> for (para in item.paragraphs) {
                builder.append(para.text).append(' ')
            }
            is PageItem.Table -> for (row in item.rows) {
                for (cell in row.cells) builder.append(cell.text).append(' ')
            }
            is PageItem.Placeholder -> builder.append(item.label).append(' ')
            else -> Unit
        }
    }

    /**
     * 带回退地解码文本：UTF-8 → GB18030 → GBK → ISO-8859-1 兜底。
     *
     * 注意：Java 的 `String(bytes, charset)` 对非法字节默认是**替换**而不抛
     * 异常，GBK 文件会被当成「合法 UTF-8」解码成乱码，回退永远不会触发。
     * 必须用 REPORT 模式的严格解码器，才能真正区分「解码成功」与「乱码」。
     */
    internal fun decodeText(raw: ByteArray): String {
        for (name in listOf("UTF-8", "GB18030", "GBK")) {
            try {
                val decoder = Charset.forName(name).newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                return decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
            } catch (error: java.nio.charset.CharacterCodingException) {
                continue
            }
        }
        return String(raw, charset("ISO-8859-1"))
    }

    internal fun stripHtml(html: String): String {
        // 跳过 script/style 的**标签内正文**（上次实现会把脚本内容 misunderstanding
        // 进索引），去标签，转实体，折叠空白。
        val lowered = html.lowercase()
        val cleaned = StringBuilder()
        var i = 0
        var skipping = false
        while (i < html.length) {
            val tag = lowered.indexOf('<', i)
            val segmentEnd = if (tag < 0) html.length else tag
            // 跳过状态下的段（script/style 的正文）直接丢弃，只留一个空白
            if (!skipping) cleaned.append(html, i, segmentEnd)
            if (tag < 0) break
            val end = lowered.indexOf('>', tag)
            if (end < 0) break
            val tagText = lowered.substring(tag + 1, end).trim()
            if (tagText.startsWith("script") || tagText.startsWith("style")) skipping = true
            if (tagText.startsWith("/script") || tagText.startsWith("/style")) skipping = false
            cleaned.append(' ')
            i = end + 1
        }
        val withEntities = cleaned.toString()
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return withEntities.replace(Regex("\\s+"), " ").trim()
    }

    private fun File.readBytesCopy(maxBytes: Int): ByteArray {
        return inputStream().use { stream ->
            val size = minOf(length(), maxBytes.toLong()).toInt().coerceAtLeast(0)
            val buffer = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = stream.read(buffer, read, size - read)
                if (n < 0) break
                read += n
            }
            if (read == size) buffer else buffer.copyOf(read)
        }
    }
}
