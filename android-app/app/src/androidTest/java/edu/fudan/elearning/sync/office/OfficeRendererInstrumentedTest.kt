package edu.fudan.elearning.sync.office

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.sl.usermodel.ShapeType
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.openxmlformats.schemas.drawingml.x2006.main.CTShapeProperties
import org.openxmlformats.schemas.drawingml.x2006.main.STLineEndType
import org.openxmlformats.schemas.presentationml.x2006.main.CTConnector
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape

/**
 * Office 渲染插桩测试：在真实 Android 运行时（ART）上验证 POI 解析 + Canvas 渲染。
 *
 * 这是「POI 能否在 Android 上运行」的端到端闸门：本机 JVM 无法加载 java.awt 桩 （禁止定义 java.* 包），只有设备上的 PathClassLoader
 * 可以。本测试同时覆盖：
 * - xmlbeans 的 OOXML 解析路径（依赖 javax.xml.stream 桩）；
 * - POIFS 的旧格式路径（.xls）；
 * - java.awt.* 桩类在解析坐标/颜色时被正确解析；
 * - [PageRenderer] 用 Android Canvas 真正产出位图。
 *
 * 夹具全部由 POI 写 API 在测试内现造，不引入二进制文件，也不使用真实课程资料。 .doc/.ppt 由本机 Office COM 另存为旧格式后 adb push 到外部专属目录，存在时
 * 才执行（assume），缺失时跳过该用例。
 */
@RunWith(AndroidJUnit4::class)
class OfficeRendererInstrumentedTest {

    private lateinit var ctx: Context
    private lateinit var fixtures: File

    @Before
    fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        fixtures = File(ctx.cacheDir, "office_fixtures").apply { mkdirs() }
    }

    private fun write(name: String, block: (FileOutputStream) -> Unit): File {
        val file = File(fixtures, name)
        FileOutputStream(file).use(block)
        return file
    }

    /** 解析入口是 suspend（内部切到 IO 线程），插桩测试里同步等待结果。 */
    private fun extract(file: File, onProgress: (Int, Int) -> Unit = { _, _ -> }) = runBlocking {
        OfficeExtractor.extract(ctx, file, onProgress)
    }

    /** 断言可解析且第一页能渲染成合法位图。 */
    private fun assertRenderable(file: File, minPages: Int) {
        val result = extract(file)
        assertTrue("解析 ${file.name} 应成功，实际: $result", result is OfficeParseResult.Success)
        val pages = (result as OfficeParseResult.Success).pages
        assertTrue("${file.name} 页数应 >= $minPages，实际 ${pages.size}", pages.size >= minPages)
        // 关键：在 Android 上真正把第一页画成位图（触发全部 java.awt 桩与 Canvas 路径）
        val bmp = PageRenderer.renderPage(pages.first())
        assertNotNull("渲染 ${file.name} 第一页不应返回 null", bmp)
        assertTrue("${file.name} 位图宽度应 > 0", bmp!!.width > 0)
        assertTrue("${file.name} 位图高度应 > 0", bmp.height > 0)
        assertTrue("${file.name} 位图应可绘制", !bmp.isRecycled)
        bmp.recycle()
    }

    @Test
    fun pptx_parsesAndRendersOnDevice() {
        val file =
            write("slides.pptx") { out ->
                XMLSlideShow().use { show ->
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
        assertRenderable(file, minPages = 2)
        // 坐标换算：50pt * (目标宽/720pt) 应与目标宽成正比；
        // 文本块再按文本框内边距内缩（POI 默认左右 0.1in = 7.2pt）
        val pages = (extract(file) as OfficeParseResult.Success).pages
        assertEquals(2, pages.size)
        val box = pages[0].items.filterIsInstance<PageItem.TextBlock>().first()
        val targetW = OfficeExtractor.renderWidth(ctx)
        assertEquals((50f + 7.2f) * targetW / 720f, box.rect.left, 2f)
    }

    @Test
    fun docx_parsesPaginatesAndRendersOnDevice() {
        val file =
            write("doc.docx") { out ->
                XWPFDocument().use { doc ->
                    val p1 = doc.createParagraph()
                    p1.createRun().setText("这是第一段正文内容，用于验证 Word 分页与渲染。")
                    p1.runs.first().setBold(true)
                    repeat(60) { i ->
                        val p = doc.createParagraph()
                        p.createRun().setText("填充段落 $i：用于产生多页文档，验证分页器在真机上的行为。")
                    }
                    doc.write(out)
                }
            }
        // Word 走分页路径（StaticLayout），多段至少一页
        assertRenderable(file, minPages = 1)
    }

    @Test
    fun xlsx_parsesAndRendersOnDevice() {
        val file =
            write("sheet.xlsx") { out ->
                XSSFWorkbook().use { wb ->
                    val sheet = wb.createSheet("数据")
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
        assertRenderable(file, minPages = 1)
    }

    @Test
    fun xls_legacy_parsesAndRendersOnDevice() {
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
        assertRenderable(file, minPages = 1)
    }

    /** 旧格式 .doc：由本机 Office COM 另存后 adb push，缺失时跳过。 */
    @Test
    fun doc_legacy_parsesAndRendersOnDevice() {
        val file = File(ctx.getExternalFilesDir("fixtures"), "legacy.doc")
        assumeTrue("跳过：未在 ${file.parent} 发现 legacy.doc", file.exists())
        assertRenderable(file, minPages = 1)
    }

    /**
     * 旧格式 .ppt：POI 的 HSLFSlideShow 提供无参构造，可直接在设备上造出旧格式 幻灯片（走 scratchpad 的 POIFS 路径），无需宿主机 Office
     * COM。
     */
    @Test
    fun ppt_legacy_parsesAndRendersOnDevice() {
        val file =
            write("legacy.ppt") { out ->
                HSLFSlideShow().use { show ->
                    show.setPageSize(Dimension(720, 540))
                    val slide1 = show.createSlide()
                    val box1 = slide1.createTextBox()
                    box1.setAnchor(Rectangle2D.Double(50.0, 40.0, 400.0, 80.0))
                    box1.setText("Legacy slide one")
                    val slide2 = show.createSlide()
                    val box2 = slide2.createTextBox()
                    box2.setAnchor(Rectangle2D.Double(50.0, 40.0, 400.0, 80.0))
                    box2.setText("Legacy slide two")
                    show.write(out)
                }
            }
        assertRenderable(file, minPages = 2)
    }

    @Test
    fun corruptFile_reportsFailureNotCrash() {
        val file =
            write("broken.pptx") { out -> out.write(ByteArray(1024) { (it % 251).toByte() }) }
        val result = extract(file)
        assertTrue("损坏文件应返回 Failed，实际: $result", result is OfficeParseResult.Failed)
    }

    @Test
    fun unsupportedExtension_reportsUnsupported() {
        val file = write("notes.odt") { out -> out.write("dummy".toByteArray()) }
        val result = extract(file)
        assertTrue("不支持的扩展名应返回 Unsupported，实际: $result", result is OfficeParseResult.Unsupported)
    }

    /**
     * 真实 Office 文档（本机 Word/PowerPoint COM 生成，含表格/图片/富文本） 验证 doc/docx/ppt 的「完整页面」渲染：表格块、图片块都被解析并画出来。
     * 夹具由 adb push 放到 fixtures 目录，缺失时跳过。
     */
    @Test
    fun officeFixtures_renderTablesAndPictures() {
        val fixturesDir = ctx.getExternalFilesDir("fixtures")

        // ---- .doc（旧二进制）：表格 + 逐段字符格式（加粗/红色）----
        val doc = File(fixturesDir, "legacy.doc")
        assumeTrue("跳过：未在 ${doc.parent} 发现 legacy.doc", doc.exists())
        val docRes = extract(doc)
        assertTrue("解析 legacy.doc 应成功，实际: $docRes", docRes is OfficeParseResult.Success)
        val docPages = (docRes as OfficeParseResult.Success).pages
        val docTables = docPages.flatMap { it.items }.filterIsInstance<PageItem.Table>()
        assertTrue(
            ".doc 应至少渲染出一个表格，首页元素：${docPages.first().items.map { it::class.simpleName }}",
            docTables.isNotEmpty(),
        )
        val docCellText =
            docTables.joinToString { tbl ->
                tbl.rows.joinToString { it.cells.joinToString { c -> c.text } }
            }
        assertTrue(
            ".doc 表格单元应含 Alice 与 Score：$docCellText",
            docCellText.contains("Alice") && docCellText.contains("Score"),
        )
        val docBmp = PageRenderer.renderPage(docPages.first())
        assertTrue(".doc 首页位图应合法", docBmp.width > 0 && docBmp.height > 0)
        docBmp.recycle()

        // ---- .docx：内嵌图片 + 表格 ----
        val docx = File(fixturesDir, "modern.docx")
        assumeTrue("跳过：未在 ${docx.parent} 发现 modern.docx", docx.exists())
        val docxRes = extract(docx)
        assertTrue("解析 modern.docx 应成功，实际: $docxRes", docxRes is OfficeParseResult.Success)
        val docxItems = (docxRes as OfficeParseResult.Success).pages.flatMap { it.items }
        assertTrue(".docx 应含图片块", docxItems.any { it is PageItem.Image })
        assertTrue(".docx 应含表格块", docxItems.any { it is PageItem.Table })

        // ---- .ppt（真实旧格式）：形状 + 表格 + 图片 ----
        val ppt = File(fixturesDir, "legacy.ppt")
        assumeTrue("跳过：未在 ${ppt.parent} 发现 legacy.ppt", ppt.exists())
        val pptRes = extract(ppt)
        assertTrue("解析 legacy.ppt 应成功，实际: $pptRes", pptRes is OfficeParseResult.Success)
        val pptPages = (pptRes as OfficeParseResult.Success).pages
        assertEquals("legacy.ppt 应为 2 页，实际 ${pptPages.size}", 2, pptPages.size)
        val pptItems = pptPages.flatMap { it.items }
        assertTrue(".ppt 应含图片块", pptItems.any { it is PageItem.Image })
        assertTrue(".ppt 应含表格块", pptItems.any { it is PageItem.Table })
        val pptBmp = PageRenderer.renderPage(pptPages.first())
        assertTrue(".ppt 首页位图应合法", pptBmp.width > 0 && pptBmp.height > 0)
        pptBmp.recycle()
    }

    /**
     * v1.0.9：含装饰性自选形状、连接线箭头、组合形状与图表的 pptx，在真机上 既要提取出形状/线/占位卡，也要能真正画成位图（锁定「装饰形状消失」回归）。
     *
     * 夹具刻意**直接写 OOXML**而不是走 POI 的 `setFillColor(Color)` 等写 API： Android 平台没有真正的 `java.awt`（用的是
     * awtstub 最小桩），POI 的写路径 会调用更多 AWT 方法；而真实的课程文档是"读 XML"，与夹具的这种构造方式一致。
     * 这样既避免依赖桩的完整性，又顺带验证「真实文档里的实色填充/描边在 ART 上 能被正确读出」。
     */
    @Test
    fun pptx_shapesRenderAndChartBecomesPlaceholder() {
        val file =
            write("shapes.pptx") { out ->
                XMLSlideShow().use { show ->
                    show.setPageSize(Dimension(720, 405))
                    val slide = show.createSlide()

                    val decor = slide.createAutoShape()
                    decor.shapeType = ShapeType.ROUND_RECT
                    decor.setAnchor(Rectangle2D.Double(30.0, 30.0, 200.0, 100.0))
                    decor.rotation = 30.0
                    applyShapeStyle(
                        (decor.xmlObject as CTShape).spPr,
                        fillRgb = intArrayOf(0x4F, 0x46, 0xE5),
                        strokeRgb = intArrayOf(0x31, 0x2E, 0x81),
                        strokeWidthEmu = 38100,
                        arrow = false,
                    )

                    // 水平连接线：高度为 0，容易被「宽高必须为正」的检查整条丢掉
                    val connector = slide.createConnector()
                    connector.setAnchor(Rectangle2D.Double(300.0, 60.0, 120.0, 0.0))
                    applyShapeStyle(
                        (connector.xmlObject as CTConnector).spPr,
                        fillRgb = null,
                        strokeRgb = intArrayOf(0, 0, 0),
                        strokeWidthEmu = 25400,
                        arrow = true,
                    )

                    val group = slide.createGroup()
                    group.setAnchor(Rectangle2D.Double(100.0, 200.0, 400.0, 150.0))
                    group.setInteriorAnchor(Rectangle2D.Double(0.0, 0.0, 400.0, 150.0))
                    val child = group.createAutoShape()
                    child.shapeType = ShapeType.ELLIPSE
                    child.setAnchor(Rectangle2D.Double(50.0, 40.0, 100.0, 60.0))
                    applyShapeStyle(
                        (child.xmlObject as CTShape).spPr,
                        fillRgb = intArrayOf(0xFF, 0x00, 0x00),
                        strokeRgb = null,
                        strokeWidthEmu = 0,
                        arrow = false,
                    )

                    // POI 的图形框只在 write() 时挂到幻灯片上，写盘后再解析即可
                    slide.addChart(
                        show.createChart(slide),
                        Rectangle2D.Double(380.0, 240.0, 250.0, 120.0),
                    )
                    show.write(out)
                }
            }

        val result = extract(file)
        assertTrue("解析 shapes.pptx 应成功，实际: $result", result is OfficeParseResult.Success)
        val pages = (result as OfficeParseResult.Success).pages
        val items = pages.first().items
        // 失败时把「提取到的元素」和「POI 原始读取结果」一并打出来，
        // 便于区分是提取逻辑问题还是 ART 上 AWT 桩的缺口
        val itemSummary = items.joinToString(", ") { it::class.simpleName ?: "?" }
        val probe = probeShapeRead(file)
        assertTrue("应提取出形状；元素=[$itemSummary]；原始读取=[$probe]", items.any { it is PageItem.Shape })
        assertTrue("应提取出连接线；元素=[$itemSummary]", items.any { it is PageItem.Line })
        assertTrue("图表应转为占位卡；元素=[$itemSummary]", items.any { it is PageItem.Placeholder })

        // 关键：在真机上把含形状的页面真正画出来（走 DashPathEffect / Path / 旋转）
        val bmp = PageRenderer.renderPage(pages.first())
        assertTrue("含形状的首页应能渲染成合法位图", bmp.width > 0 && bmp.height > 0)
        bmp.recycle()
    }

    /**
     * 诊断用：直接读回幻灯片里第一个形状的填充/描边，并把异常原文带出来。
     *
     * `SlideExtractor` 对每个形状都做 `runCatching` 容错，单个字段读取失败会被 静默降级；这里不加保护地读一次，才能看到 ART 上到底缺哪个 AWT 方法。
     */
    private fun probeShapeRead(file: File): String {
        val sb = StringBuilder()
        runCatching {
                XMLSlideShow(file.inputStream()).use { show ->
                    val shapes = show.slides.first().shapes
                    sb.append("count=").append(shapes.size).append(' ')
                    shapes.forEach { shape ->
                        sb.append(shape.javaClass.simpleName).append('{')
                        val simple = shape as? org.apache.poi.sl.usermodel.SimpleShape<*, *>
                        sb.append("fill=")
                        sb.append(
                            runCatching { simple?.fillColor?.toString() }.getOrElse { "ERR:" + it }
                        )
                        sb.append(",stroke=")
                        sb.append(
                            runCatching { simple?.strokeStyle?.paint?.toString() }
                                .getOrElse { "ERR:" + it }
                        )
                        sb.append("} ")
                    }
                }
            }
            .onFailure { sb.append("OPEN_ERR:").append(it) }
        return sb.toString()
    }

    /**
     * 直接写 OOXML 的实色填充/描边（等价于真实文档里的 `a:solidFill/a:srgbClr`）。
     *
     * [fillRgb]/[strokeRgb] 为 null 表示不设置该项；[strokeWidthEmu] 用 EMU （12700 EMU = 1pt）；[arrow] 为 true
     * 时给线段加尾端三角箭头。
     */
    private fun applyShapeStyle(
        spPr: CTShapeProperties,
        fillRgb: IntArray?,
        strokeRgb: IntArray?,
        strokeWidthEmu: Int,
        arrow: Boolean,
    ) {
        fillRgb?.let { rgb ->
            spPr
                .addNewSolidFill()
                .addNewSrgbClr()
                .setVal(byteArrayOf(rgb[0].toByte(), rgb[1].toByte(), rgb[2].toByte()))
        }
        if (strokeRgb == null && !arrow) return
        val ln = spPr.addNewLn()
        if (strokeWidthEmu > 0) ln.setW(strokeWidthEmu)
        strokeRgb?.let { rgb ->
            ln.addNewSolidFill()
                .addNewSrgbClr()
                .setVal(byteArrayOf(rgb[0].toByte(), rgb[1].toByte(), rgb[2].toByte()))
        }
        if (arrow) ln.addNewTailEnd().setType(STLineEndType.TRIANGLE)
    }
}
