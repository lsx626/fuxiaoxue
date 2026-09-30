package edu.fudan.elearning.sync.office

import org.apache.poi.hslf.usermodel.HSLFPictureShape
import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.hslf.usermodel.HSLFTable
import org.apache.poi.hslf.usermodel.HSLFTextShape
import org.apache.poi.sl.draw.DrawPaint
import org.apache.poi.sl.usermodel.ColorStyle
import org.apache.poi.sl.usermodel.ConnectorShape
import org.apache.poi.sl.usermodel.GroupShape
import org.apache.poi.sl.usermodel.Insets2D
import org.apache.poi.sl.usermodel.LineDecoration
import org.apache.poi.sl.usermodel.ObjectShape
import org.apache.poi.sl.usermodel.PaintStyle
import org.apache.poi.sl.usermodel.PictureData
import org.apache.poi.sl.usermodel.PictureShape
import org.apache.poi.sl.usermodel.PlaceableShape
import org.apache.poi.sl.usermodel.ShapeType
import org.apache.poi.sl.usermodel.SimpleShape
import org.apache.poi.sl.usermodel.Slide
import org.apache.poi.sl.usermodel.TableCell
import org.apache.poi.sl.usermodel.TableShape
import org.apache.poi.sl.usermodel.TextParagraph
import org.apache.poi.sl.usermodel.TextShape
import org.apache.poi.sl.usermodel.VerticalAlignment
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFPictureShape
import org.apache.poi.xslf.usermodel.XSLFGraphicFrame
import org.apache.poi.xslf.usermodel.XSLFTable
import org.apache.poi.xslf.usermodel.XSLFTextShape
import java.awt.Color
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.io.File

/**
 * 把 PPT/PPTX 解析成 [DocPage] 列表：每张幻灯片一页，保留形状坐标、文本格式、
 * 图片与表格，绘制时按比例还原版式（而非只提取文字）。
 *
 * 形状覆盖范围：
 * - 文本/自选形状（含**无文字的装饰形状**）：几何 + 填充 + 描边 + 旋转；
 * - 连接线：线本身与箭头端点；
 * - 组合形状：递归展开，子形状坐标按组合锚点平移（忽略组内缩放，已知限制）；
 * - 图片、表格；
 * - 图表 / SmartArt / OLE 嵌入 / 视频：不假装高保真，输出 [PageItem.Placeholder]
 *   占位卡 + 限制说明，用户可主动分享给其他工具查看。
 *
 * 单位约定（POI 5.2.5）：[SlideShow.getPageSize]、[Shape.getAnchor]、字号与
 * [TextParagraph.getSpaceAfter] 均以磅为单位。页面尺寸直接按磅缩放到目标像素
 * 宽度，形状/字号坐标再乘同一个「磅→像素」因子，比例即保持一致。
 */
object SlideExtractor {

    /** 默认正文字号（磅），PPT 中未显式指定时用此值。 */
    private const val DEFAULT_FONT_PT = 18.0

    /** 占位卡文案：如实说明限制，不假装成功。 */
    private const val CHART_PLACEHOLDER = "图表 · 已折叠，可分享查看"
    private const val DIAGRAM_PLACEHOLDER = "SmartArt 图形 · 已折叠，可分享查看"
    private const val EMBED_PLACEHOLDER = "嵌入对象/媒体 · 已折叠，可分享查看"

    /**
     * 解析幻灯片。
     *
     * [onProgress] 在每张幻灯片完成后回调 `(已完成, 总数)`，调用方可据此显示进度并
     * 在取消时抛出异常终止解析（本函数不吞回调抛出的异常）。
     * [downscaleImage] 用于把超大内嵌图片降采样后再放进页模型，避免整份文档的原始
     * 图片字节常驻内存；默认不做处理，便于 JVM 单测不依赖 Android 解码器。
     */
    fun extract(
        file: File,
        targetWidthPx: Int,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        downscaleImage: (ByteArray) -> ByteArray = { it }
    ): List<DocPage> {
        val name = file.name.lowercase()
        // 打开阶段抛出的异常（损坏/加密）交给 OfficeExtractor 报告为「解析失败」。
        val show = if (name.endsWith(".pptx")) {
            XMLSlideShow(file.inputStream())
        } else {
            HSLFSlideShow(file.inputStream())
        }
        return show.use {
            extractSlides(it.slides, it.pageSize, targetWidthPx, onProgress, downscaleImage)
        }
    }

    private fun extractSlides(
        slides: List<Slide<*, *>>,
        pageSize: Dimension,
        targetWidthPx: Int,
        onProgress: (Int, Int) -> Unit,
        downscaleImage: (ByteArray) -> ByteArray
    ): List<DocPage> {
        if (slides.isEmpty()) return emptyList()
        val total = slides.size
        onProgress(0, total)
        val pageWpt = pageSize.width.toDouble()
        val pageHpt = pageSize.height.toDouble()
        // 缩放因子 = 每磅对应像素
        val scale = targetWidthPx.toDouble() / pageWpt
        // 四舍五入，避免 16:9 等比例高度被系统性截断（如 607.5 -> 608）
        val pageHPx = Math.round(pageHpt * scale).coerceAtLeast(1).toInt()
        val pages = ArrayList<DocPage>(total)
        for ((index, slide) in slides.withIndex()) {
            val items = mutableListOf<PageItem>()
            // 幻灯片背景色：实色填充（ColorStyle 或 SolidPaint）时取色，否则用白色
            val bg = runCatching {
                paintArgb(slide.background?.fillStyle?.paint)
            }.getOrNull()
            items.add(PageItem.Fill(
                Rect4(0f, 0f, targetWidthPx.toFloat(), pageHPx.toFloat()),
                bg ?: 0xFFFFFFFF
            ))
            // 母版/版式上的装饰图形（校徽、色带、装饰线等）画在幻灯片内容之前
            for (shape in backgroundShapes(slide)) {
                runCatching { appendShape(shape, items, scale, 0.0, 0.0, downscaleImage) }
            }
            for (shape in slide.shapes) {
                runCatching { appendShape(shape, items, scale, 0.0, 0.0, downscaleImage) }
            }
            pages.add(DocPage(targetWidthPx, pageHPx, items))
            onProgress(index + 1, total)
        }
        return pages
    }

    /**
     * 母版与版式上的**非占位**形状。
     *
     * 幻灯片通常只放内容，背景装饰（校徽、色带、装饰线、页脚图形）都在版式/母版里；
     * 不渲染它们页面就会「缺组件」。占位符必须跳过：版式占位符里是提示文字，
     * 真正内容在幻灯片上，重复绘制会出现重影。
     *
     * pptx（XSLF）与 ppt（HSLF）都要覆盖：旧实现只处理 XSLFSlide，.ppt 文档的
     * 母版装饰会被整体丢弃。HSLF 的 layout 概念由 HSLFMasterSheet 承载
     * （`getSlideLayout()` 在 HSLF 上返回的同样是 MasterSheet）。
     */
    private fun backgroundShapes(slide: Slide<*, *>): List<Any> = runCatching {
        val out = mutableListOf<Any>()
        val masters: List<org.apache.poi.sl.usermodel.MasterSheet<*, *>> = when (slide) {
            is org.apache.poi.xslf.usermodel.XSLFSlide -> listOfNotNull(
                slide.masterSheet,
                slide.slideLayout
            )
            is org.apache.poi.hslf.usermodel.HSLFSlide -> listOfNotNull(
                slide.masterSheet,
                slide.slideLayout
            )
            else -> return emptyList<Any>()
        }
        masters.forEach { master ->
            runCatching {
                master.shapes?.forEach { shape ->
                    if ((shape as? SimpleShape<*, *>)?.placeholder == null) out += shape
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    /**
     * 分发单个形状。[dx]/[dy] 为组合形状内子坐标的平移量（磅），由 [appendGroup] 传入。
     *
     * 分支顺序有实际意义：POI 中 `XSLFTable` 继承 `XSLFGraphicFrame`、
     * `XSLFObjectShape`（OLE）继承 `XSLFGraphicFrame`、`XSLFAutoShape` 继承
     * `XSLFTextShape`，因此必须由具体到笼统排列。
     */
    private fun appendShape(
        shape: Any,
        items: MutableList<PageItem>,
        scale: Double,
        dx: Double,
        dy: Double,
        downscaleImage: (ByteArray) -> ByteArray
    ) {
        when (shape) {
            is XSLFPictureShape -> appendPicture(shape, items, scale, dx, dy, downscaleImage)
            is HSLFPictureShape -> appendPicture(shape, items, scale, dx, dy, downscaleImage)
            is XSLFTable -> appendTable(shape, items, scale, dx, dy)
            is HSLFTable -> appendTable(shape, items, scale, dx, dy)
            is ObjectShape<*, *> -> appendPlaceholder(
                shape, items, scale, dx, dy, EMBED_PLACEHOLDER
            )
            is XSLFGraphicFrame -> appendGraphicFrame(shape, items, scale, dx, dy)
            is GroupShape<*, *> -> appendGroup(shape, items, scale, dx, dy, downscaleImage)
            is ConnectorShape<*, *> -> appendConnector(shape, items, scale, dx, dy)
            is XSLFTextShape -> appendTextShape(shape, items, scale, dx, dy)
            is HSLFTextShape -> appendTextShape(shape, items, scale, dx, dy)
            is SimpleShape<*, *> -> appendSimpleShape(shape, items, scale, dx, dy)
        }
    }

    private fun appendPicture(shape: PictureShape<*, *>,
                              items: MutableList<PageItem>,
                              scale: Double,
                              dx: Double,
                              dy: Double,
                              downscaleImage: (ByteArray) -> ByteArray) {
        val data: PictureData? = runCatching { shape.pictureData }.getOrNull()
        if (data == null) return
        val rect = scaleRect(shape.anchor, scale, dx, dy) ?: return
        val bytes = runCatching { downscaleImage(data.data) }.getOrDefault(data.data)
        // 位图解码按魔数识别格式，MIME 仅记录用途，不需要精确值
        items.add(PageItem.Image(rect, bytes, "image/*"))
    }

    private fun appendTextShape(shape: TextShape<*, *>,
                                items: MutableList<PageItem>,
                                scale: Double,
                                dx: Double,
                                dy: Double) {
        val rect = scaleRect(shape.anchor, scale, dx, dy) ?: return
        // 自选形状/文本框的几何、底色与描边（含无文字的装饰形状）
        appendGeometry(shape, rect, items, scale)
        val paragraphs = shape.textParagraphs.mapNotNull { para ->
            runCatching { convertParagraph(para, scale) }.getOrNull()
        }
        if (paragraphs.isEmpty()) return
        val insets = runCatching { shape.insets }.getOrNull()
        items.add(PageItem.TextBlock(
            rect = insetRect(rect, insets, scale),
            paragraphs = paragraphs,
            rotationDeg = runCatching { shape.rotation.toFloat() }.getOrDefault(0f),
            valign = verticalAlignOf(shape)
        ))
    }

    /** 只有被连接线/自选形状以外的简单形状（无文字装饰形状）走这里。 */
    private fun appendSimpleShape(shape: SimpleShape<*, *>,
                                  items: MutableList<PageItem>,
                                  scale: Double,
                                  dx: Double,
                                  dy: Double) {
        val rect = scaleRect(shape.anchor, scale, dx, dy) ?: return
        appendGeometry(shape, rect, items, scale)
    }

    /**
     * 画出自选形状的几何：填充 + 描边 + 旋转。
     *
     * 无填充且无描边时什么都不画（普通文本框就是这种情况），
     * 这样既修复了「装饰形状消失」，也不会给纯文本框套上多余的边框。
     */
    private fun appendGeometry(shape: Any,
                               rect: Rect4,
                               items: MutableList<PageItem>,
                               scale: Double) {
        val simple = shape as? SimpleShape<*, *> ?: return
        // 填充优先级：实色 → 渐变（取加权平均色兜底，避免整块形状消失）
        val fill = runCatching { colorArgb(simple.fillColor) }.getOrNull()
            ?: runCatching { paintArgb(simple.fillStyle?.paint) }.getOrNull()
            ?: runCatching { gradientArgb(simple.fillStyle?.paint) }.getOrNull()
        val stroke = runCatching { paintArgb(simple.strokeStyle?.paint) }.getOrNull()
        if (fill == null && stroke == null) return
        val widthPt = runCatching { simple.strokeStyle?.lineWidth }.getOrNull() ?: 1.0
        items.add(PageItem.Shape(
            rect = rect,
            geometry = mapGeometry(runCatching { simple.shapeType }.getOrNull()),
            fill = fill,
            stroke = stroke,
            strokeWidthPx = (widthPt * scale).toFloat().coerceAtLeast(1f),
            rotationDeg = runCatching { (shape as PlaceableShape<*, *>).rotation.toFloat() }
                .getOrDefault(0f)
        ))
    }

    /** 连接线：按翻转标志确定两端点，并保留箭头端点。 */
    private fun appendConnector(shape: ConnectorShape<*, *>,
                                items: MutableList<PageItem>,
                                scale: Double,
                                dx: Double,
                                dy: Double) {
        // 连接线允许某个方向为 0（水平/垂直直线就是这种情况），因此不能用
        // scaleRect 的「宽高必须为正」检查，否则最常见的直线会整条消失。
        val rect = scaleRectAllowingFlat(shape.anchor, scale, dx, dy) ?: return
        val flipH = runCatching { shape.flipHorizontal }.getOrDefault(false)
        val flipV = runCatching { shape.flipVertical }.getOrDefault(false)
        val x1 = if (flipH) rect.right else rect.left
        val x2 = if (flipH) rect.left else rect.right
        val y1 = if (flipV) rect.bottom else rect.top
        val y2 = if (flipV) rect.top else rect.bottom
        val color = runCatching { paintArgb(shape.strokeStyle?.paint) }.getOrNull()
            ?: runCatching { colorArgb(shape.fillColor) }.getOrNull()
            ?: 0xFF3C4043
        val width = ((runCatching { shape.strokeStyle?.lineWidth }.getOrNull() ?: 1.0) * scale)
            .toFloat().coerceAtLeast(1f)
        val decoration = runCatching { shape.lineDecoration }.getOrNull()
        items.add(PageItem.Line(
            x1 = x1, y1 = y1, x2 = x2, y2 = y2,
            argb = color, widthPx = width,
            startArrow = arrowOf(decoration?.headShape),
            endArrow = arrowOf(decoration?.tailShape)
        ))
    }

    /**
     * 组合形状：递归展开子形状，子坐标按「组合锚点 - 内部坐标原点」平移。
     *
     * 组内缩放（group ext 与 chExt 不等）暂不还原，属于已知限制；平移后大多数
     * 由 PowerPoint 直接拖拽生成的组合形状位置正确。
     */
    private fun appendGroup(shape: GroupShape<*, *>,
                            items: MutableList<PageItem>,
                            scale: Double,
                            dx: Double,
                            dy: Double,
                            downscaleImage: (ByteArray) -> ByteArray) {
        val anchor = runCatching { shape.anchor }.getOrNull() ?: return
        val interior = runCatching { shape.interiorAnchor }.getOrNull()
        val ndx = dx + anchor.x - (interior?.x ?: 0.0)
        val ndy = dy + anchor.y - (interior?.y ?: 0.0)
        val children = runCatching { shape.shapes }.getOrNull() ?: return
        for (child in children) {
            runCatching { appendShape(child, items, scale, ndx, ndy, downscaleImage) }
        }
    }

    /** 图表 / SmartArt；其余图形框（视频、嵌入对象）统一给占位卡。 */
    private fun appendGraphicFrame(frame: XSLFGraphicFrame,
                                   items: MutableList<PageItem>,
                                   scale: Double,
                                   dx: Double,
                                   dy: Double) {
        if (runCatching { frame.hasChart() }.getOrDefault(false)) {
            val title = runCatching { frame.chart?.titleShape?.text }.getOrNull()?.trim()
            val label = if (title.isNullOrEmpty()) CHART_PLACEHOLDER else "图表：$title · 已折叠，可分享查看"
            appendPlaceholder(frame, items, scale, dx, dy, label)
            return
        }
        if (runCatching { frame.hasDiagram() }.getOrDefault(false)) {
            appendPlaceholder(frame, items, scale, dx, dy, DIAGRAM_PLACEHOLDER)
            return
        }
        appendPlaceholder(frame, items, scale, dx, dy, EMBED_PLACEHOLDER)
    }

    /** 在原位置放一张占位卡：浅灰圆角 + 说明文字，由 [PageRenderer] 绘制。 */
    private fun appendPlaceholder(shape: Any,
                                  items: MutableList<PageItem>,
                                  scale: Double,
                                  dx: Double,
                                  dy: Double,
                                  label: String) {
        val anchor = runCatching { (shape as PlaceableShape<*, *>).anchor }.getOrNull() ?: return
        val rect = scaleRect(anchor, scale, dx, dy) ?: return
        items.add(PageItem.Placeholder(rect, label))
    }

    private fun appendTable(shape: TableShape<*, *>, items: MutableList<PageItem>,
                            scale: Double, dx: Double, dy: Double) {
        val rect = scaleRect(shape.anchor, scale, dx, dy) ?: return
        val rows = mutableListOf<DocRow>()
        val numRows = runCatching { shape.numberOfRows }.getOrDefault(0)
        val numCols = runCatching { shape.numberOfColumns }.getOrDefault(0)
        for (r in 0 until numRows) {
            val cells = mutableListOf<DocCell>()
            var rowH = 0.0
            for (c in 0 until numCols) {
                runCatching {
                    val cell: TableCell<*, *> = shape.getCell(r, c)
                    val text = cell.text ?: ""
                    val sizePt = runCatching {
                        cell.textParagraphs.firstOrNull()?.textRuns?.firstOrNull()?.fontSize
                    }.getOrNull() ?: 12.0
                    val color = runCatching {
                        cell.textParagraphs.firstOrNull()?.textRuns?.firstOrNull()
                            ?.fontColor?.let { paintArgb(it) }
                    }.getOrNull()
                    val fill = runCatching { colorArgb(cell.fillColor) }.getOrNull()
                    cells.add(DocCell(text, color, fill, (sizePt * scale).toFloat()))
                    // 行高按单元高度（磅）换算
                    val ch = runCatching { cell.anchor.height * scale }.getOrDefault(0.0)
                    if (ch > rowH) rowH = ch
                }
            }
            rows.add(DocRow(cells, rowH.toFloat()))
        }
        if (rows.isNotEmpty()) items.add(PageItem.Table(rect, rows))
    }

    private fun convertParagraph(para: TextParagraph<*, *, *>, scale: Double): DocParagraph {
        val runs = para.textRuns.mapNotNull { run ->
            runCatching {
                val sizePt = run.fontSize ?: DEFAULT_FONT_PT
                DocRun(
                    text = TextSanitizer.clean(run.rawText),
                    sizePx = (sizePt * scale).toFloat(),
                    argb = paintArgb(run.fontColor),
                    bold = run.isBold,
                    italic = run.isItalic,
                    underline = run.isUnderlined,
                    family = run.fontFamily
                )
            }.getOrNull()
        }.filter { it.text.isNotEmpty() }
        if (runs.isEmpty()) return DocParagraph(emptyList())
        val align = when (runCatching { para.textAlign }.getOrNull()) {
            TextParagraph.TextAlign.CENTER -> DocAlign.CENTER
            TextParagraph.TextAlign.RIGHT -> DocAlign.END
            TextParagraph.TextAlign.JUSTIFY, TextParagraph.TextAlign.DIST -> DocAlign.JUSTIFY
            else -> DocAlign.START
        }
        val spaceAfter = runCatching { (para.spaceAfter ?: 0.0) * scale }.getOrDefault(0.0)
        return DocParagraph(runs, align, spaceAfterPx = spaceAfter.toFloat())
    }

    /** AWT Rectangle2D（磅）→ 像素 Rect4，并应用组合形状的平移量。 */
    private fun scaleRect(anchor: Rectangle2D, scale: Double, dx: Double = 0.0, dy: Double = 0.0): Rect4? {
        val w = anchor.width
        val h = anchor.height
        if (w <= 0 || h <= 0) return null
        return scaleRectAllowingFlat(anchor, scale, dx, dy)
    }

    /** 同上，但允许宽或高为 0（连接线）；两者同时为 0 时视为无长度，返回 null。 */
    private fun scaleRectAllowingFlat(anchor: Rectangle2D,
                                      scale: Double,
                                      dx: Double,
                                      dy: Double): Rect4? {
        val w = anchor.width
        val h = anchor.height
        if (w < 0 || h < 0) return null
        if (w == 0.0 && h == 0.0) return null
        return Rect4(
            ((anchor.x + dx) * scale).toFloat(),
            ((anchor.y + dy) * scale).toFloat(),
            ((anchor.x + dx + w) * scale).toFloat(),
            ((anchor.y + dy + h) * scale).toFloat()
        )
    }

    /** 文本框内边距（磅 -> 像素），单边最多占 25%，避免异常值时把文本挤没。 */
    private fun insetRect(rect: Rect4, insets: Insets2D?, scale: Double): Rect4 {
        if (insets == null) return rect
        val left = (insets.left * scale).toFloat().coerceIn(0f, rect.width * 0.25f)
        val top = (insets.top * scale).toFloat().coerceIn(0f, rect.height * 0.25f)
        val right = (insets.right * scale).toFloat().coerceIn(0f, rect.width * 0.25f)
        val bottom = (insets.bottom * scale).toFloat().coerceIn(0f, rect.height * 0.25f)
        if (left + top + right + bottom == 0f) return rect
        return Rect4(rect.left + left, rect.top + top, rect.right - right, rect.bottom - bottom)
    }

    private fun verticalAlignOf(shape: TextShape<*, *>): VerticalAlign =
        when (runCatching { shape.verticalAlignment }.getOrNull()) {
            VerticalAlignment.MIDDLE -> VerticalAlign.MIDDLE
            VerticalAlignment.BOTTOM -> VerticalAlign.BOTTOM
            else -> VerticalAlign.TOP
        }

    /** 端点箭头：只有真正的箭头类装饰才画，其余（圆点/菱形）按无箭头处理。 */
    private fun arrowOf(decoration: LineDecoration.DecorationShape?): ArrowEnd =
        when (decoration) {
            LineDecoration.DecorationShape.ARROW,
            LineDecoration.DecorationShape.TRIANGLE,
            LineDecoration.DecorationShape.STEALTH -> ArrowEnd.ARROW
            else -> ArrowEnd.NONE
        }

    /** POI [ShapeType] -> 我们可绘制的几何；未覆盖的统一退化为矩形。 */
    private fun mapGeometry(type: ShapeType?): ShapeGeometry = when (type) {
        ShapeType.RECT,
        ShapeType.TEXT_BOX,
        ShapeType.FLOW_CHART_PROCESS,
        ShapeType.FLOW_CHART_PREDEFINED_PROCESS -> ShapeGeometry.RECT
        ShapeType.ROUND_RECT,
        ShapeType.ROUND_1_RECT,
        ShapeType.ROUND_2_SAME_RECT,
        ShapeType.ROUND_2_DIAG_RECT,
        ShapeType.SNIP_ROUND_RECT -> ShapeGeometry.ROUND_RECT
        ShapeType.ELLIPSE,
        ShapeType.FLOW_CHART_TERMINATOR,
        ShapeType.FLOW_CHART_CONNECTOR -> ShapeGeometry.ELLIPSE
        ShapeType.TRIANGLE,
        ShapeType.FLOW_CHART_EXTRACT,
        ShapeType.FLOW_CHART_MERGE -> ShapeGeometry.TRIANGLE
        ShapeType.RT_TRIANGLE -> ShapeGeometry.RT_TRIANGLE
        ShapeType.DIAMOND,
        ShapeType.FLOW_CHART_DECISION -> ShapeGeometry.DIAMOND
        ShapeType.PENTAGON,
        ShapeType.HOME_PLATE,
        ShapeType.FLOW_CHART_PREPARATION -> ShapeGeometry.PENTAGON
        ShapeType.CHEVRON -> ShapeGeometry.CHEVRON
        ShapeType.RIGHT_ARROW, ShapeType.STRIPED_RIGHT_ARROW,
        ShapeType.NOTCHED_RIGHT_ARROW, ShapeType.CURVED_RIGHT_ARROW -> ShapeGeometry.RIGHT_ARROW
        ShapeType.LEFT_ARROW, ShapeType.CURVED_LEFT_ARROW -> ShapeGeometry.LEFT_ARROW
        ShapeType.UP_ARROW, ShapeType.CURVED_UP_ARROW -> ShapeGeometry.UP_ARROW
        ShapeType.DOWN_ARROW, ShapeType.CURVED_DOWN_ARROW -> ShapeGeometry.DOWN_ARROW
        ShapeType.STAR_4,
        ShapeType.CHART_STAR -> ShapeGeometry.STAR_4
        ShapeType.STAR_5 -> ShapeGeometry.STAR_5
        ShapeType.PLUS, ShapeType.MATH_PLUS -> ShapeGeometry.PLUS
        ShapeType.LEFT_BRACE, ShapeType.BRACE_PAIR -> ShapeGeometry.LEFT_BRACE
        ShapeType.RIGHT_BRACE -> ShapeGeometry.RIGHT_BRACE
        ShapeType.WEDGE_RECT_CALLOUT,
        ShapeType.WEDGE_ROUND_RECT_CALLOUT,
        ShapeType.WEDGE_ELLIPSE_CALLOUT,
        ShapeType.CALLOUT_1,
        ShapeType.CALLOUT_2,
        ShapeType.CALLOUT_3 -> ShapeGeometry.CALLOUT
        else -> ShapeGeometry.OTHER
    }

    /**
     * PaintStyle → ARGB；只处理实色，渐变/图案/图片填充返回 null（如实降级为不使用该颜色）。
     *
     * POI 的实色有**两种**表现形式：`ColorStyle`（主题色/配色变换）与
     * `PaintStyle.SolidPaint`（直接 RGB，`DrawPaint.SimpleSolidPaint`）。
     * 只判断 ColorStyle 会让 XSLF 的描边色与逐段文字颜色全部丢失，
     * 因此两者都必须支持。
     */
    private fun paintArgb(paint: PaintStyle?): Long? = when (paint) {
        null -> null
        is ColorStyle -> themeArgb(paint)
        is PaintStyle.SolidPaint -> paint.solidColor?.let { themeArgb(it) }
        else -> null
    }

    /**
     * 主题色 → 最终 ARGB。
     *
     * `ColorStyle.getColor()` 只给出**原始**颜色，主题里的 tint/shade/lumMod/lumOff
     * 都要靠 `DrawPaint.applyColorTransform` 应用；不做这一步，深色主题的配色会明显偏。
     */
    private fun themeArgb(style: ColorStyle): Long? =
        runCatching { colorArgb(DrawPaint.applyColorTransform(style)) }.getOrNull()
            ?: runCatching { colorArgb(style.color) }.getOrNull()

    /**
     * 渐变填充 → 加权平均色。
     *
     * 应用内不做真正的渐变渲染（成本高、收益低），但**绝不能让形状消失**：
     * 取各色标的平均色作为纯色兜底，视觉上接近原设计，也比空白好得多。
     */
    private fun gradientArgb(paint: PaintStyle?): Long? {
        val gradient = paint as? PaintStyle.GradientPaint ?: return null
        val colors = runCatching { gradient.gradientColors }.getOrNull() ?: return null
        if (colors.isEmpty()) return null
        val fractions = runCatching { gradient.gradientFractions }.getOrNull()
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var weight = 0.0
        colors.forEachIndexed { index, style ->
            val argb = themeArgb(style) ?: return@forEachIndexed
            val w = if (fractions != null && index < fractions.size) {
                fractions[index].toDouble().coerceAtLeast(0.05)
            } else {
                1.0
            }
            sumR += ((argb shr 16) and 0xFF) * w
            sumG += ((argb shr 8) and 0xFF) * w
            sumB += (argb and 0xFF) * w
            weight += w
        }
        if (weight <= 0.0) return null
        val r = (sumR / weight).toLong().coerceIn(0, 255)
        val g = (sumG / weight).toLong().coerceIn(0, 255)
        val b = (sumB / weight).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** java.awt.Color → ARGB Long。 */
    private fun colorArgb(color: Color?): Long? {
        if (color == null) return null
        return (0xFFL shl 24) or (color.rgb.toLong() and 0xFFFFFF)
    }
}
