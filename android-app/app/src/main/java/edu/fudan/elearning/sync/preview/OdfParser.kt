package edu.fudan.elearning.sync.preview

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

/**
 * ODF（`.odt` / `.ods` / `.odp`）结构化解析器（v1.1.2 新增）。
 *
 * 输入是一个 ODF zip 文件，输出**文档视图块**：标题（分级）、段落、列表项、
 * 表格（文本矩阵）、内嵌图片（字节）。这是「文档视图」而非排版引擎：
 * 分栏、脚注、域代码、复杂数学排版不做高保真还原。
 *
 * 抽到独立对象是为了插桩测试能直接喂构造的 zip 夹具（真机 XML 解析器
 * 在 JVM 单测环境不可用）。
 */
object OdfParser {

    sealed class Block {
        data class Heading(val level: Int, val text: String) : Block()
        data class Paragraph(val text: String) : Block()
        data class ListItem(val text: String) : Block()
        data class Table(val rows: List<List<String>>) : Block()
        data class Image(val data: ByteArray) : Block()
    }

    private const val NS_OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    private const val NS_TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    private const val NS_TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    private const val NS_XLINK = "http://www.w3.org/1999/xlink"

    fun parse(file: File): List<Block> {
        ZipFile(file).use { zip ->
            val contentEntry = zip.getEntry("content.xml")
                ?: throw IllegalStateException("不是有效的 ODF 文档（缺少 content.xml）")
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            // 必须显式开启：factory 默认不保证处理命名空间，否则 namespace 返回
            // 空串、name 带前缀，所有按 (本地名, 命名空间) 的匹配全部落空。
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(zip.getInputStream(contentEntry), "UTF-8")
            val blocks = mutableListOf<Block>()
            var inBody = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        if (!inBody && parser.namespace == NS_OFFICE
                            && (name == "text" || name == "spreadsheet" || name == "presentation")
                        ) {
                            inBody = true
                        } else if (inBody) {
                            when {
                                name == "h" && parser.namespace == NS_TEXT -> {
                                    val level = parser.getAttributeValue(null, "outline-level")
                                        ?.toIntOrNull() ?: 1
                                    val (text, images) = readParagraph(parser, zip)
                                    blocks += Block.Heading(level, text)
                                    blocks += images
                                }
                                name == "p" && parser.namespace == NS_TEXT -> {
                                    val (text, images) = readParagraph(parser, zip)
                                    blocks += Block.Paragraph(text)
                                    blocks += images
                                }
                                name == "list-item" && parser.namespace == NS_TEXT -> {
                                    val (text, images) = readParagraph(parser, zip)
                                    blocks += Block.ListItem(text)
                                    blocks += images
                                }
                                name == "table" && parser.namespace == NS_TABLE -> {
                                    blocks += parseTable(parser)
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        // 正文主元素（office:text 等）结束时正文结束；元素不会自嵌套
                        if (inBody && parser.namespace == NS_OFFICE
                            && (parser.name == "text" || parser.name == "spreadsheet"
                                || parser.name == "presentation")
                        ) {
                            inBody = false
                        }
                    }
                }
                event = parser.next()
            }
            return blocks
        }
    }

    /**
     * 读取元素内全部文本（含嵌套 span），同时把内嵌 draw:image 解码出来。
     * 用标签平衡计数消费整个子树，不依赖各类 XmlPullParser 的 depth 语义差异。
     * 返回时消费完毕该元素的 END_TAG（与 START_TAG 配对）。
     */
    private fun readParagraph(
        parser: XmlPullParser,
        zip: ZipFile
    ): Pair<String, List<Block.Image>> {
        val builder = StringBuilder()
        val images = mutableListOf<Block.Image>()
        var balance = 1  // 调用者刚见到的 START_TAG
        var inImageSubtree = 0
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT && balance > 0) {
            when (event) {
                XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE -> {
                    if (inImageSubtree == 0) builder.append(parser.text)
                }
                XmlPullParser.START_TAG -> {
                    balance++
                    when (parser.name) {
                        "tab" -> builder.append("    ")
                        "line-break" -> builder.append("\n")
                        "image" -> {
                            // href 形如 #Pictures/xxx.png 或 ./Pictures/xxx.png
                            val href = parser.getAttributeValue(NS_XLINK, "href") ?: ""
                            val entryName = href.removePrefix("#").removePrefix("./")
                            val entry = zip.getEntry(entryName)
                            if (entry != null) {
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                if (bytes.isNotEmpty()) images += Block.Image(bytes)
                            }
                            inImageSubtree = balance
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inImageSubtree > 0 && inImageSubtree == balance) inImageSubtree = 0
                    balance--
                }
            }
            event = parser.next()
        }
        return builder.toString().replace(Regex("\\s+"), " ").trim() to images
    }

    /**
     * 表格 → 文本矩阵。
     *
     * 单元格内容在子元素（text:p 等）里，必须递归收集文本；
     * `number-columns-repeated`（合并/重复列）按 ODF 语义展开为重复值。
     */
    private fun parseTable(parser: XmlPullParser): Block.Table {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val cellText = StringBuilder()
        var inCell = false
        var repeatCount = 1
        var balance = 1  // table 自己
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT && balance > 0) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    balance++
                    if (parser.name == "table-cell" && parser.namespace == NS_TABLE) {
                        inCell = true
                        cellText.clear()
                        repeatCount = parser.getAttributeValue(NS_TABLE, "number-columns-repeated")
                            ?.toIntOrNull() ?: 1
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.IGNORABLE_WHITESPACE ->
                    if (inCell) cellText.append(parser.text)
                XmlPullParser.END_TAG -> {
                    if (parser.name == "table-cell" && parser.namespace == NS_TABLE && inCell) {
                        inCell = false
                        val text = cellText.toString().replace(Regex("\\s+"), " ").trim()
                        repeat(repeatCount.coerceAtLeast(1)) { currentRow += text }
                    } else if (parser.name == "table-row" && parser.namespace == NS_TABLE) {
                        if (currentRow.isNotEmpty()) rows += currentRow.toList()
                        currentRow.clear()
                    }
                    balance--
                }
            }
            event = parser.next()
        }
        return Block.Table(rows)
    }
}
