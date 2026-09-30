package edu.fudan.elearning.sync.preview

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ODF 解析器插桩测试（v1.1.2 新增）。
 *
 * 为什么必须插桩：`XmlPullParserFactory`/`BitmapFactory` 都是平台组件，
 * JVM 单测里没有。夹具是手工构造的最小 .odt zip（content.xml + 一张
 * 1×1 PNG），覆盖标题分级、段落、列表、表格与内嵌图片五类块。
 */
@RunWith(AndroidJUnit4::class)
class OdfParserInstrumentedTest {

    private fun fixtureDir(): File {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "odf-fixture")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 1×1 透明 PNG 的最小字节。 */
    private val tinyPng = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    /** 构造一个最小 ODF；includeImage 决定是否放内嵌图片。 */
    private fun buildOdt(name: String, includeImage: Boolean): File {
        val out = File(fixtureDir(), name)
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/vnd.oasis.opendocument.text".toByteArray())
            zip.closeEntry()
            val imageXml = if (includeImage) {
                """<draw:frame><draw:image xlink:href="#Pictures/1000.png" """ +
                    """xlink:type="simple"/></draw:frame>"""
            } else ""
            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
                    xmlns:xlink="http://www.w3.org/1999/xlink"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0">
                    <office:body>
                        <office:text>
                            <text:h text:outline-level="1">第一章 概述</text:h>
                            <text:p>这是正文段落。</text:p>
                            <text:h text:outline-level="2">1.1 背景</text:h>
                            <text:p>第二段正文。$imageXml</text:p>
                            <text:list>
                                <text:list-item><text:p>列表项一</text:p></text:list-item>
                                <text:list-item><text:p>列表项二</text:p></text:list-item>
                            </text:list>
                            <table:table>
                                <table:table-row>
                                    <table:table-cell><text:p>A1</text:p></table:table-cell>
                                    <table:table-cell><text:p>B1</text:p></table:table-cell>
                                </table:table-row>
                                <table:table-row>
                                    <table:table-cell><text:p>A2</text:p></table:table-cell>
                                    <table:table-cell><text:p>B2</text:p></table:table-cell>
                                </table:table-row>
                            </table:table>
                        </office:text>
                    </office:body>
                </office:document-content>""".toByteArray()
            )
            zip.closeEntry()
            if (includeImage) {
                zip.putNextEntry(ZipEntry("Pictures/1000.png"))
                zip.write(tinyPng)
                zip.closeEntry()
            }
        }
        return out
    }

    @Test
    fun parsesStructureAndImages() {
        val file = buildOdt("full.odt", includeImage = true)
        val blocks = OdfParser.parse(file)

        val headings = blocks.filterIsInstance<OdfParser.Block.Heading>()
        assertEquals(2, headings.size)
        assertEquals(1, headings[0].level)
        assertEquals("第一章 概述", headings[0].text)
        assertEquals(2, headings[1].level)

        val paragraphs = blocks.filterIsInstance<OdfParser.Block.Paragraph>()
        assertTrue(paragraphs.any { it.text.contains("这是正文段落") })

        val items = blocks.filterIsInstance<OdfParser.Block.ListItem>()
        assertEquals(listOf("列表项一", "列表项二"), items.map { it.text })

        val tables = blocks.filterIsInstance<OdfParser.Block.Table>()
        assertEquals(1, tables.size)
        assertEquals(
            listOf(listOf("A1", "B1"), listOf("A2", "B2")),
            tables[0].rows
        )

        // 内嵌图片按出现位置（第二段之后）收集；tinyPng 不可解码为位图，
        // 但字节本身要原样读出（渲染层会兜底「无法解码」文案）
        val images = blocks.filterIsInstance<OdfParser.Block.Image>()
        assertEquals(1, images.size)
        assertEquals(tinyPng.toList(), images[0].data.toList())

        // 顺序文档语义：标题在段落之前，图片在第二段之后
        val kinds = blocks.map { it::class.simpleName }
        assertTrue(kinds.indexOf("Heading") < kinds.indexOf("Paragraph"))
        val secondParaIndex = blocks.indexOfLast { it is OdfParser.Block.Paragraph }
        val imageIndex = blocks.indexOfFirst { it is OdfParser.Block.Image }
        assertTrue(imageIndex > secondParaIndex)
    }

    @Test
    fun parsesDocumentWithoutImages() {
        val file = buildOdt("noimage.odt", includeImage = false)
        val blocks = OdfParser.parse(file)
        assertTrue(blocks.filterIsInstance<OdfParser.Block.Image>().isEmpty())
        assertTrue(blocks.filterIsInstance<OdfParser.Block.Paragraph>().isNotEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsNonOdfZip() {
        val out = File(fixtureDir(), "empty.odt")
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry("other.xml"))
            zip.write("<x/>".toByteArray())
            zip.closeEntry()
        }
        OdfParser.parse(out)
    }
}
