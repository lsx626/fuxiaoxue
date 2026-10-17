package edu.fudan.elearning.sync.office

import java.awt.Color
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.CancellationException
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.sl.usermodel.LineDecoration
import org.apache.poi.sl.usermodel.ShapeType
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Office 解析层单元测试：用 POI 写 API 在测试内现造合成夹具（docx/pptx/xlsx/xls）， 喂给我们的提取器，验证页数、文本与坐标换算。不使用真实课程资料。
 *
 * 说明：PageRenderer.paginate 依赖 Android 的 StaticLayout，无法在 JVM 单测中执行， 因此 Word 只验证到
 * [FlowDocument]；分页与位图渲染由模拟器插桩测试覆盖。
 */
class OfficeExtractorTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private fun write(name: String, block: (FileOutputStream) -> Unit): File {
        val file = tempFolder.newFile(name)
        FileOutputStream(file).use(block)
        return file
    }

    private fun pageTexts(pages: List<DocPage>): String = buildString {
        for (page in pages) {
            for (item in page.items) {
                when (item) {
                    is PageItem.TextBlock -> append(item.paragraphs.joinToString { p -> p.text })
                    is PageItem.Table ->
                        append(
                            item.rows.joinToString { row -> row.cells.joinToString { c -> c.text } }
                        )
                    else -> {}
                }
            }
        }
    }

    @Test
    fun pptx_slides_textAndScaling() {
        val file =
            write("slides.pptx") { out ->
                XMLSlideShow().use { show ->
                    // 10in x 5.625in（16:9）= 720pt x 405pt
                    show.setPageSize(Dimension(720, 405))
                    val slide1 = show.createSlide()
                    val box1 = slide1.createTextBox()
                    box1.setAnchor(Rectangle2D.Double(50.0, 40.0, 400.0, 80.0))
                    box1.setText("第一章 概述")
                    val slide2 = show.createSlide()
                    val box2 = slide2.createTextBox()
                    box2.setAnchor(Rectangle2D.Double(50.0, 40.0, 400.0, 80.0))
                    box2.setText("第二章 结论")
                    show.write(out)
                }
            }

        val pages = SlideExtractor.extract(file, 1080)

        assertEquals(2, pages.size)
        // 页面宽度等于目标宽度，高度按 16:9 等比
        assertEquals(1080, pages[0].widthPx)
        assertEquals(608, pages[0].heightPx)
        val texts = pageTexts(pages)
        assertTrue(texts.contains("第一章 概述"))
        assertTrue(texts.contains("第二章 结论"))
        // 形状锚点按 磅→像素 换算：50pt * (1080/720pt) = 75px；
        // 文本还要按文本框内边距内缩（POI 默认左右 0.1in = 7.2pt => 10.8px，
        // 上下 0.05in = 3.6pt => 5.4px），因此文本块边界略大于锚点边界
        val box = pages[0].items.filterIsInstance<PageItem.TextBlock>().first()
        assertEquals(75f + 10.8f, box.rect.left, 1f)
        assertEquals(60f + 5.4f, box.rect.top, 1f)
    }

    @Test
    fun pptx_corrupt_throws() {
        val file =
            write("broken.pptx") { out -> out.write(ByteArray(1024) { (it % 251).toByte() }) }
        // 损坏文件由解析器抛出，交给 OfficeExtractor 报告「解析失败」
        var threw = false
        try {
            SlideExtractor.extract(file, 1080)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    /**
     * v1.0.9 形状保真：装饰性自选形状、连接线箭头、组合形状递归平移、图表占位卡。
     *
     * 旧实现的 appendShape 只处理 Picture/TextShape/Table，无文字的装饰形状、 连接线与图形框会被静默丢弃；本测试锁定修复后的行为。
     */
    @Test
    fun pptx_shapesConnectorsGroupAndChart() {
        val file =
            write("shapes.pptx") { out ->
                XMLSlideShow().use { show ->
                    show.setPageSize(Dimension(720, 405))
                    val slide = show.createSlide()

                    // 无文字的装饰性自选形状：必须画几何，而不是被当成空文本丢掉
                    val decor = slide.createAutoShape()
                    decor.shapeType = ShapeType.ROUND_RECT
                    decor.setAnchor(Rectangle2D.Double(30.0, 30.0, 200.0, 100.0))
                    decor.fillColor = Color(0x4F, 0x46, 0xE5)
                    decor.setLineWidth(3.0)
                    decor.lineColor = Color(0x31, 0x2E, 0x81)
                    decor.rotation = 30.0

                    // 连接线：水平直线的高为 0，仍必须保留为一条带箭头的线
                    val connector = slide.createConnector()
                    connector.setAnchor(Rectangle2D.Double(300.0, 60.0, 120.0, 0.0))
                    connector.setLineWidth(2.0)
                    connector.lineColor = Color.BLACK
                    connector.setLineTailDecoration(LineDecoration.DecorationShape.TRIANGLE)

                    // 组合形状：子坐标基于内部坐标系，需按组合锚点平移
                    val group = slide.createGroup()
                    group.setAnchor(Rectangle2D.Double(100.0, 200.0, 400.0, 150.0))
                    group.setInteriorAnchor(Rectangle2D.Double(0.0, 0.0, 400.0, 150.0))
                    val child = group.createAutoShape()
                    child.shapeType = ShapeType.ELLIPSE
                    child.setAnchor(Rectangle2D.Double(50.0, 40.0, 100.0, 60.0))
                    child.fillColor = Color.RED

                    // 图表：不假装高保真，转成占位卡 + 限制说明
                    // 注意：POI 的图形框只在 write() 时挂到幻灯片上，这里写盘后再解析即可
                    slide.addChart(
                        show.createChart(slide),
                        Rectangle2D.Double(380.0, 240.0, 250.0, 120.0),
                    )

                    show.write(out)
                }
            }

        // 720pt 页宽 + 目标宽 720px => 缩放系数 1.0，断言用原始坐标
        val pages = SlideExtractor.extract(file, 720)

        assertEquals(1, pages.size)
        val items = pages[0].items

        val shapes = items.filterIsInstance<PageItem.Shape>()
        assertEquals("装饰形状 + 组合内子形状", 2, shapes.size)

        val decor = shapes.first { it.rect.left < 100f }
        assertEquals(ShapeGeometry.ROUND_RECT, decor.geometry)
        assertEquals(0xFF4F46E5L, decor.fill)
        assertEquals(0xFF312E81L, decor.stroke)
        assertEquals(30f, decor.rotationDeg, 0.01f)
        assertEquals(30f, decor.rect.left, 1f)

        // 组合内子形状：50+100=150、40+200=240（忽略组内缩放，只平移）
        val grouped = shapes.first { it.rect.left > 100f }
        assertEquals(ShapeGeometry.ELLIPSE, grouped.geometry)
        assertEquals(150f, grouped.rect.left, 1f)
        assertEquals(240f, grouped.rect.top, 1f)
        assertEquals(0xFFFF0000L, grouped.fill)

        // 连接线：两端点与箭头方向
        val line = items.filterIsInstance<PageItem.Line>().first()
        assertEquals(300f, line.x1, 1f)
        assertEquals(60f, line.y1, 1f)
        assertEquals(420f, line.x2, 1f)
        assertEquals(60f, line.y2, 1f)
        assertEquals(ArrowEnd.NONE, line.startArrow)
        assertEquals(ArrowEnd.ARROW, line.endArrow)

        // 图表：占位卡 + 说明文字
        val placeholders = items.filterIsInstance<PageItem.Placeholder>()
        assertTrue("图表应输出占位卡", placeholders.isNotEmpty())
        assertTrue(
            "占位卡应说明元素类型：${placeholders.first().label}",
            placeholders.first().label.contains("图表"),
        )
    }

    /** 大文件降分辨率、大页数整体缩放、POI 上限自适应。 */
    @Test
    fun limits_memoryAwareAndBigDocumentDowngrade() {
        val mib = 1L shl 20
        for (heap in listOf(128L * mib, 512L * mib, 8L shl 30)) {
            val limit = OfficeLimits.memoryAwareLimit(heap)
            assertTrue(
                "heap=$heap limit=$limit 应落在 [100MiB, 384MiB]",
                limit in (100L * mib)..(384L * mib),
            )
        }
        // 小堆不低于 POI 默认值，大堆不超过 384 MiB
        assertEquals(100L * mib, OfficeLimits.memoryAwareLimit(64L * mib))
        assertEquals(384L * mib, OfficeLimits.memoryAwareLimit(8L shl 30))

        // 普通文件保持屏幕宽度；大文件降到 1080–1440px
        assertEquals(2560, OfficeLimits.targetWidth(2560, 1024L))
        assertEquals(1440, OfficeLimits.targetWidth(2560, OfficeLimits.BIG_FILE_BYTES + 1))
        assertEquals(1080, OfficeLimits.targetWidth(1000, OfficeLimits.BIG_FILE_BYTES + 1))

        assertEquals(1f, OfficeLimits.pageScale(60), 0f)
        assertEquals(OfficeLimits.BIG_DOC_SCALE, OfficeLimits.pageScale(61), 0f)
    }

    /** 失败语义：内存/记录超限给友好说明，普通损坏给原因，取消必须原样抛出。 */
    @Test
    fun parseFailure_memoryRecordLimitAndCancellation() {
        val oom =
            OfficeExtractor.runParse {
                throw OutOfMemoryError("Failed to allocate a 185199965 byte allocation")
            }
        assertEquals(OfficeLimits.MEMORY_HINT, (oom as OfficeParseResult.Failed).message)

        // POI 5.2.5 单记录上限被触发时的原始文案
        val recordLimit =
            OfficeExtractor.runParse {
                throw IllegalStateException(
                    "Tried to allocate an array of length 185199965, " +
                        "but the maximum length for the record type is 100000000"
                )
            }
        assertEquals(OfficeLimits.MEMORY_HINT, (recordLimit as OfficeParseResult.Failed).message)

        // 普通损坏保留原因，但不回显超长异常串
        val corrupt =
            OfficeExtractor.runParse { throw IllegalStateException("not a valid OOXML file") }
        assertTrue((corrupt as OfficeParseResult.Failed).message.contains("not a valid OOXML file"))
        val longMessage = OfficeExtractor.runParse { throw IllegalStateException("x".repeat(5000)) }
        assertTrue("异常串必须截断", (longMessage as OfficeParseResult.Failed).message.length < 300)

        val blank = OfficeExtractor.runParse { throw IllegalStateException() }
        assertTrue((blank as OfficeParseResult.Failed).message.contains("可能已损坏"))

        // 空文档与成功语义
        assertTrue(OfficeExtractor.runParse { emptyList() } is OfficeParseResult.Empty)
        val page = DocPage(10, 10, emptyList())
        assertTrue(OfficeExtractor.runParse { listOf(page) } is OfficeParseResult.Success)

        // 取消（用户离开预览）绝不能被当成「解析失败」
        var cancelled = false
        try {
            OfficeExtractor.runParse { throw CancellationException("cancelled") }
        } catch (expected: CancellationException) {
            cancelled = true
        }
        assertTrue("取消异常必须原样抛出", cancelled)
    }

    /** 大文档降分辨率：页模型整体缩放，坐标/字号/线宽/旋转同步缩小，且不放大。 */
    @Test
    fun scaledPage_shrinksGeometryAndKeepsFlags() {
        val page =
            DocPage(
                1000,
                500,
                listOf(
                    PageItem.TextBlock(
                        Rect4(10f, 20f, 210f, 120f),
                        listOf(DocParagraph(listOf(DocRun("标题", 40f)))),
                    ),
                    PageItem.Shape(
                        rect = Rect4(0f, 0f, 100f, 50f),
                        geometry = ShapeGeometry.ELLIPSE,
                        fill = 0xFF4F46E5,
                        stroke = 0xFF000000,
                        strokeWidthPx = 4f,
                        rotationDeg = 15f,
                    ),
                    PageItem.Line(
                        0f,
                        0f,
                        100f,
                        100f,
                        0xFF000000,
                        2f,
                        ArrowEnd.ARROW,
                        ArrowEnd.NONE,
                    ),
                    PageItem.Placeholder(Rect4(5f, 5f, 105f, 55f), "图表 · 已折叠，可分享查看"),
                ),
            )

        val half = page.scaled(0.5f)

        assertEquals(500, half.widthPx)
        assertEquals(250, half.heightPx)
        val text = half.items.filterIsInstance<PageItem.TextBlock>().first()
        assertEquals(5f, text.rect.left, 0.01f)
        assertEquals(20f, text.paragraphs.first().runs.first().sizePx, 0.01f)
        val shape = half.items.filterIsInstance<PageItem.Shape>().first()
        assertEquals(50f, shape.rect.right, 0.01f)
        assertEquals(2f, shape.strokeWidthPx, 0.01f)
        assertEquals(15f, shape.rotationDeg, 0.01f)
        val line = half.items.filterIsInstance<PageItem.Line>().first()
        assertEquals(50f, line.x2, 0.01f)
        assertEquals(ArrowEnd.ARROW, line.startArrow)
        assertTrue(half.items.any { it is PageItem.Placeholder })

        // factor >= 1 原样返回，绝不把模型放大
        assertSame(page, page.scaled(2f))
    }

    @Test
    fun docx_paragraphsAndFormatting() {
        val file =
            write("doc.docx") { out ->
                XWPFDocument().use { doc ->
                    val p1 = doc.createParagraph()
                    val r1 = p1.createRun()
                    r1.setText("这是第一段正文内容，用于验证提取。")
                    r1.setBold(true)
                    val p2 = doc.createParagraph()
                    val r2 = p2.createRun()
                    r2.setText("第二段正文。")
                    doc.write(out)
                }
            }

        val flow = WordExtractor.extract(file, 1080)

        assertEquals(1080, flow.pageWidthPx)
        assertFalse(flow.blocks.isEmpty())
        val texts =
            flow.blocks.filterIsInstance<FlowBlock.Paragraph>().joinToString { it.para.text }
        assertTrue(texts.contains("第一段正文内容"))
        assertTrue(texts.contains("第二段正文。"))
        // 加粗格式被保留
        val boldRun =
            flow.blocks
                .filterIsInstance<FlowBlock.Paragraph>()
                .flatMap { it.para.runs }
                .first { it.text.contains("第一段") }
        assertTrue(boldRun.bold)
    }

    @Test
    fun xlsx_cellsAndMergedRegion() {
        val file =
            write("sheet.xlsx") { out ->
                XSSFWorkbook().use { wb ->
                    val sheet = wb.createSheet("数据")
                    // 注意：同一行只能 createRow 一次并复用引用。重复 createRow(n) 会让
                    // 前一次拿到的行对象（及其已加单元格）在落盘时丢失。
                    val header = sheet.createRow(0)
                    header.createCell(0).setCellValue("姓名")
                    header.createCell(1).setCellValue("分数")
                    val first = sheet.createRow(1)
                    first.createCell(0).setCellValue("张三")
                    first.createCell(1).setCellValue(95.0)
                    sheet.addMergedRegion(CellRangeAddress(0, 0, 0, 1))
                    wb.write(out)
                }
            }

        val pages = SheetExtractor.extract(file, 1080)
        assertTrue(pages.isNotEmpty())
        val texts = pageTexts(pages)
        assertTrue(texts.contains("姓名"))
        assertTrue(texts.contains("张三"))
        // 数值单元被格式化为可读文本
        assertTrue(texts.contains("95"))
    }

    @Test
    fun xls_legacy_workbook() {
        val file =
            write("legacy.xls") { out ->
                HSSFWorkbook().use { wb ->
                    val sheet = wb.createSheet("旧格式")
                    val r0 = sheet.createRow(0)
                    r0.createCell(0).setCellValue("旧版单元格")
                    r0.createCell(1).setCellValue("表头")
                    val r1 = sheet.createRow(1)
                    r1.createCell(0).setCellValue("数据行")
                    r1.createCell(1).setCellValue(42.0)
                    wb.write(out)
                }
            }

        val pages = SheetExtractor.extract(file, 1080)

        assertTrue(pages.isNotEmpty())
        val texts = pageTexts(pages)
        assertTrue(texts.contains("旧版单元格"))
        assertTrue(texts.contains("42"))
    }

    @Test
    fun docx_table_extractedAsTableBlock() {
        val file =
            write("tbl.docx") { out ->
                XWPFDocument().use { doc ->
                    doc.createParagraph().createRun().setText("表前段落")
                    val table = doc.createTable()
                    val r0 = table.getRow(0)
                    r0.getCell(0).setText("姓名")
                    r0.addNewTableCell().setText("分数")
                    val r1 = table.createRow()
                    r1.getCell(0).setText("张三")
                    r1.getCell(1).setText("95")
                    doc.createParagraph().createRun().setText("表后段落")
                    doc.write(out)
                }
            }

        val flow = WordExtractor.extract(file, 1080)

        // 段落 -> 表格 -> 段落 的正文顺序被保留
        assertTrue(flow.blocks.filterIsInstance<FlowBlock.Table>().isNotEmpty())
        assertTrue(
            flow.blocks
                .filterIsInstance<FlowBlock.Paragraph>()
                .joinToString { it.para.text }
                .contains("表前段落")
        )
        assertTrue(
            flow.blocks
                .filterIsInstance<FlowBlock.Paragraph>()
                .joinToString { it.para.text }
                .contains("表后段落")
        )
        val tbl = flow.blocks.filterIsInstance<FlowBlock.Table>().first()
        val texts = tbl.rows.joinToString { row -> row.cells.joinToString { it.text } }
        assertTrue(texts.contains("姓名"))
        assertTrue(texts.contains("分数"))
        assertTrue(texts.contains("张三"))
        assertTrue(tbl.columnWeights.isNotEmpty())
    }

    @Test
    fun docx_inlineImage_extractedAsPicture() {
        // 合成 100x50 RGB PNG（单测不依赖 AWT：Android 编译期无 java.desktop）
        val png = makePng(100, 50)
        val file =
            write("img.docx") { out ->
                XWPFDocument().use { doc ->
                    val p = doc.createParagraph()
                    val r = p.createRun()
                    r.setText("图片说明")
                    r.addPicture(
                        ByteArrayInputStream(png),
                        Document.PICTURE_TYPE_PNG,
                        "dot.png",
                        1270000,
                        635000,
                    ) // EMU：100pt x 50pt
                    doc.write(out)
                }
            }

        val flow = WordExtractor.extract(file, 1080)

        val pics = flow.blocks.filterIsInstance<FlowBlock.Picture>()
        assertTrue(pics.isNotEmpty())
        val pic = pics.first()
        assertTrue(pic.bytes.size > 100)
        // EMU->px 换算必须保持原始宽高比（100:50 = 2:1）
        assertTrue(
            "widthPx=" + pic.widthPx + " heightPx=" + pic.heightPx,
            pic.widthPx > 0 && pic.heightPx > 0,
        )
        val ratio = pic.widthPx.toFloat() / pic.heightPx
        assertTrue("ratio=" + ratio, ratio in 1.9f..2.1f)
    }

    /** 手写最小合法 PNG（IHDR/IDAT/IEND），避免单测依赖 AWT 解码器。 */
    private fun makePng(w: Int, h: Int): ByteArray {
        val ihdr =
            ByteArrayOutputStream(13).let { bos ->
                DataOutputStream(bos).use {
                    it.writeInt(w)
                    it.writeInt(h)
                    it.writeByte(8) // 位深
                    it.writeByte(2) // 色彩类型：真彩 RGB
                    it.writeByte(0)
                    it.writeByte(0)
                    it.writeByte(0)
                }
                bos.toByteArray()
            }
        // 原始扫描线：每行 = 1 字节过滤位 + w*3 字节 RGB
        val raw = ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) {
                raw.write(0x4F)
                raw.write(0x46)
                raw.write(0xE5)
            }
        }
        // android.jar 的 Deflater 未必是 Closeable，不用 .use；测试内创建后即丢弃
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val idatBos = ByteArrayOutputStream()
        val idatBuf = ByteArray(4096)
        while (!deflater.finished()) idatBos.write(idatBuf, 0, deflater.deflate(idatBuf))
        val idat = idatBos.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                0x89.toByte(),
                0x50.toByte(),
                0x4E.toByte(),
                0x47.toByte(),
                0x0D.toByte(),
                0x0A.toByte(),
                0x1A.toByte(),
                0x0A.toByte(),
            )
        ) // PNG 签名
        out.write(pngChunk("IHDR", ihdr))
        out.write(pngChunk("IDAT", idat))
        out.write(pngChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(type.toByteArray(Charsets.US_ASCII))
        crc.update(data)
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeInt(data.size)
            it.write(type.toByteArray(Charsets.US_ASCII))
            it.write(data)
            it.writeInt(crc.value.toInt())
        }
        return bos.toByteArray()
    }
}
