package edu.fudan.elearning.sync.office

import kotlin.math.roundToInt

/**
 * Office 文档页模型（纯 Kotlin，不依赖 Android 平台 API）。
 *
 * 解析层把 doc/docx/ppt/pptx/xls/xlsx 转成一组「页面 + 页内元素」， 再由 [PageRenderer] 用 Android Canvas 绘制成位图。
 * 这样解析逻辑可以在 JVM 单元测试中用真实合成夹具验证。
 */

/** 浮点矩形：left/top/right/bottom。 */
data class Rect4(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top

    /** 整体平移（用于组合形状内子坐标换算）。 */
    fun shift(dx: Float, dy: Float): Rect4 = Rect4(left + dx, top + dy, right + dx, bottom + dy)
}

/** 文本对齐。 */
enum class DocAlign {
    START,
    CENTER,
    END,
    JUSTIFY,
}

/** 文本垂直对齐（PPT 文本框等）。 */
enum class VerticalAlign {
    TOP,
    MIDDLE,
    BOTTOM,
}

/**
 * 自选形状的几何类型。只映射 POI [org.apache.poi.sl.usermodel.ShapeType] 中 最常见的取值，未覆盖者统一退化为
 * [OTHER]（按矩形绘制），不引入复杂路径计算。
 */
enum class ShapeGeometry {
    RECT,
    ROUND_RECT,
    ELLIPSE,
    TRIANGLE,
    RT_TRIANGLE,
    DIAMOND,
    PENTAGON,
    CHEVRON,
    RIGHT_ARROW,
    LEFT_ARROW,
    UP_ARROW,
    DOWN_ARROW,
    STAR_4,
    STAR_5,
    PLUS,
    LEFT_BRACE,
    RIGHT_BRACE,
    CALLOUT,
    FLOWCHART_PROCESS,
    FLOWCHART_DECISION,
    OTHER,
}

/** 连接线端点箭头。 */
enum class ArrowEnd {
    NONE,
    ARROW,
}

/** 一段文本中的一段连续格式片段。 */
data class DocRun(
    val text: String,
    /** 字号（像素）。 */
    val sizePx: Float,
    /** ARGB 颜色；null 表示用默认前景色。 */
    val argb: Long? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    /** 可选字体族（用于中文/等宽场景）。 */
    val family: String? = null,
)

/** 一个段落：多段 Run 拼成一行文本。 */
data class DocParagraph(
    val runs: List<DocRun>,
    val align: DocAlign = DocAlign.START,
    /** 段后间距（像素）。 */
    val spaceAfterPx: Float = 0f,
    val bullet: Boolean = false,
) {
    val text: String
        get() = runs.joinToString("") { it.text }
}

/** 页面元素。 */
sealed class PageItem {
    /** 纯色填充矩形（背景、表格底色等）。 */
    data class Fill(val rect: Rect4, val argb: Long) : PageItem()

    /** 图片：原始字节与 MIME，绘制时才解码。 */
    data class Image(val rect: Rect4, val bytes: ByteArray, val mime: String) : PageItem()

    /** 定位文本块（PPT 文本框、表格单元等）。 [rotationDeg] 与 [valign] 用于还原文本框旋转与垂直对齐。 */
    data class TextBlock(
        val rect: Rect4,
        val paragraphs: List<DocParagraph>,
        val rotationDeg: Float = 0f,
        val valign: VerticalAlign = VerticalAlign.TOP,
    ) : PageItem()

    /** 表格网格：按行列排布的单元文本。 [columnWidths] 为各列的绝对像素宽（已按内容宽度归一化）， null 或列数不匹配时按行内单元数均分。 */
    data class Table(
        val rect: Rect4,
        val rows: List<DocRow>,
        val columnWidths: List<Float>? = null,
    ) : PageItem()

    /** 直线（表格边框、分隔线、连接线等），两端可带箭头。 */
    data class Line(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val argb: Long,
        val widthPx: Float,
        val startArrow: ArrowEnd = ArrowEnd.NONE,
        val endArrow: ArrowEnd = ArrowEnd.NONE,
    ) : PageItem()

    /** 自选形状：几何 + 填充 + 描边，任意一者可为 null（无填充或无描边）。 [rotationDeg] 围绕形状中心旋转。 */
    data class Shape(
        val rect: Rect4,
        val geometry: ShapeGeometry,
        val fill: Long? = null,
        val stroke: Long? = null,
        val strokeWidthPx: Float = 1f,
        val rotationDeg: Float = 0f,
    ) : PageItem()

    /** 占位卡：图表、SmartArt、OLE 嵌入、视频等无法在应用内高保真渲染的元素。 在原位置显示说明，如实告知限制，不假装成功，也不静默跳转第三方应用。 */
    data class Placeholder(val rect: Rect4, val label: String) : PageItem()
}

/** 表格的一行。 */
data class DocRow(
    val cells: List<DocCell>,
    /** 行高（像素）。 */
    val heightPx: Float,
)

/** 表格单元。 */
data class DocCell(
    val text: String,
    val argb: Long? = null,
    val fill: Long? = null,
    val sizePx: Float = 14f,
    val bold: Boolean = false,
)

/** 一页：目标渲染尺寸与元素列表（元素坐标已换算成像素）。 */
data class DocPage(val widthPx: Int, val heightPx: Int, val items: List<PageItem>)

/** 「流式」文档（doc/docx）：尚未分页的段落序列，由 Android 侧分页器按实际 文本测量结果切分成 [DocPage]。 */
data class FlowDocument(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val marginPx: Float,
    val blocks: List<FlowBlock>,
)

/** 流式文档块。 */
sealed class FlowBlock {
    data class Paragraph(val para: DocParagraph) : FlowBlock()

    data class Picture(
        val bytes: ByteArray,
        val mime: String,
        val widthPx: Int,
        val heightPx: Int,
    ) : FlowBlock()

    data class Spacer(val heightPx: Float) : FlowBlock()

    /** 表格：行序列 + 各列相对权重。列宽与行高在分页时按真实文本测量计算， 超过一页的表格会被切成多段分别落到不同页。 */
    data class Table(val rows: List<DocRow>, val columnWeights: List<Float>) : FlowBlock()
}

/**
 * 按比例缩放整页：页尺寸、元素坐标、字号、线宽与旋转都同步缩放。
 *
 * 用途是大文档降分辨率渲染（页数过多或单页超过位图长边上限）：模型先缩小再 交给 Canvas，避免「先分配一张 4096px 以上的位图再缩放」的内存尖峰。 [factor] >= 1
 * 时原样返回，绝不把模型放大。
 */
fun DocPage.scaled(factor: Float): DocPage {
    if (factor >= 1f) return this
    val f = factor.coerceAtLeast(0.05f)

    fun rect(r: Rect4) = Rect4(r.left * f, r.top * f, r.right * f, r.bottom * f)

    val scaledItems =
        items.map { item ->
            when (item) {
                is PageItem.Fill -> PageItem.Fill(rect(item.rect), item.argb)
                is PageItem.Image -> PageItem.Image(rect(item.rect), item.bytes, item.mime)
                is PageItem.TextBlock ->
                    PageItem.TextBlock(
                        rect = rect(item.rect),
                        paragraphs =
                            item.paragraphs.map { para ->
                                para.copy(
                                    runs =
                                        para.runs.map { run -> run.copy(sizePx = run.sizePx * f) },
                                    spaceAfterPx = para.spaceAfterPx * f,
                                )
                            },
                        rotationDeg = item.rotationDeg,
                        valign = item.valign,
                    )
                is PageItem.Table ->
                    PageItem.Table(
                        rect = rect(item.rect),
                        rows =
                            item.rows.map { row ->
                                DocRow(
                                    cells =
                                        row.cells.map { cell ->
                                            cell.copy(sizePx = cell.sizePx * f)
                                        },
                                    heightPx = row.heightPx * f,
                                )
                            },
                        columnWidths = item.columnWidths?.map { it * f },
                    )
                is PageItem.Line ->
                    PageItem.Line(
                        x1 = item.x1 * f,
                        y1 = item.y1 * f,
                        x2 = item.x2 * f,
                        y2 = item.y2 * f,
                        argb = item.argb,
                        widthPx = (item.widthPx * f).coerceAtLeast(1f),
                        startArrow = item.startArrow,
                        endArrow = item.endArrow,
                    )
                is PageItem.Shape ->
                    PageItem.Shape(
                        rect = rect(item.rect),
                        geometry = item.geometry,
                        fill = item.fill,
                        stroke = item.stroke,
                        strokeWidthPx = (item.strokeWidthPx * f).coerceAtLeast(1f),
                        rotationDeg = item.rotationDeg,
                    )
                is PageItem.Placeholder -> PageItem.Placeholder(rect(item.rect), item.label)
            }
        }
    return DocPage(
        widthPx = (widthPx * f).roundToInt().coerceAtLeast(1),
        heightPx = (heightPx * f).roundToInt().coerceAtLeast(1),
        items = scaledItems,
    )
}
