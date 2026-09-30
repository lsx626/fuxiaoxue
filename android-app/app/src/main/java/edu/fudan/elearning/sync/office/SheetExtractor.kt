package edu.fudan.elearning.sync.office

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.Font
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

/**
 * 把 Excel（xlsx/xls）解析成 [DocPage] 列表：每个工作表按真实行列宽高绘制网格、
 * 单元格文本与合并区域，尽量保留表格版式。
 *
 * 超大行数的表按固定页高切成多页，既支持纵向翻页，也避免单张巨型位图导致 OOM。
 */
object SheetExtractor {

    /** 渲染基准字号（像素）。 */
    private const val FONT_PX = 13f
    private const val MIN_COL_W = 48f
    private const val ROW_H = 30f

    /** 单页位图高度上限（px），超过则按行分页。 */
    private const val MAX_PAGE_H = 3000f

    fun extract(file: File, targetWidthPx: Int): List<DocPage> {
        val name = file.name.lowercase()
        // 打开阶段抛出的异常（损坏/加密）交给 OfficeExtractor 报告为「解析失败」。
        val wb = if (name.endsWith(".xlsx")) {
            XSSFWorkbook(file.inputStream())
        } else {
            HSSFWorkbook(file.inputStream())
        }
        return wb.use { extractWorkbook(it, targetWidthPx) }
    }

    private fun extractWorkbook(wb: Workbook, targetWidthPx: Int): List<DocPage> {
        val pages = mutableListOf<DocPage>()
        for (i in 0 until wb.numberOfSheets) {
            runCatching { pages.addAll(extractSheet(wb.getSheetAt(i), targetWidthPx)) }
        }
        return pages
    }

    private fun extractSheet(sheet: Sheet, targetWidthPx: Int): List<DocPage> {
        val merged: List<CellRangeAddress> = try {
            @Suppress("UNCHECKED_CAST")
            sheet.mergedRegions as? List<CellRangeAddress> ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val colCount = (0..sheet.lastRowNum).maxOf { rowIdx ->
            runCatching { sheet.getRow(rowIdx)?.lastCellNum?.toInt() ?: 0 }.getOrDefault(0)
        }.coerceAtLeast(1)

        val colWidths = FloatArray(colCount) { c ->
            runCatching {
                val w = sheet.getColumnWidth(c)  // 1/256 char
                (w / 256f * FONT_PX * 1.1f).coerceAtLeast(MIN_COL_W)
            }.getOrDefault(MIN_COL_W)
        }
        val totalW = colWidths.sum()
        // 缩放使整表适配目标宽度（保证一页内横向完整可见）
        val scale = if (totalW > targetWidthPx) targetWidthPx / totalW else 1f

        // 不跳过空行：保证「列表下标 == 工作表行号」，合并区域与页码计算才不会错位；
        // 空行只占网格高度，不绘制文本。
        val rowCount = sheet.lastRowNum + 1
        if (rowCount <= 0) return emptyList()

        val rowH = ROW_H * scale
        val rowsPerPage = (MAX_PAGE_H / rowH).toInt().coerceAtLeast(1)
        val pages = mutableListOf<DocPage>()
        var pageStart = 0
        while (pageStart < rowCount) {
            val pageEnd = (pageStart + rowsPerPage).coerceAtMost(rowCount)
            pages.add(extractPageRows(sheet, pageStart, pageEnd, colWidths, totalW, scale, merged, targetWidthPx))
            pageStart = pageEnd
        }
        return pages
    }

    /** 渲染工作表行 [startRow..endRow) 这一页。 */
    private fun extractPageRows(
        sheet: Sheet,
        startRow: Int,
        endRow: Int,
        colWidths: FloatArray,
        totalW: Float,
        scale: Float,
        merged: List<CellRangeAddress>,
        targetWidthPx: Int
    ): DocPage {
        val rowH = ROW_H * scale
        // 本页实际 n = endRow - startRow 行：网格带高度必须是 n * rowH，
        // 多加一行会让末条横线浮空、页底多出一条无底边的空白带
        val pageH = ((endRow - startRow) * rowH).toInt().coerceAtLeast(1)
        val pageW = (totalW * scale).toInt().coerceAtLeast(1)
        val items = mutableListOf<PageItem>()
        // 单元格底色/字体需经 Workbook 取 Font；取不到时降级为默认格式
        val wb = runCatching { sheet.workbook }.getOrNull()
        // 合并区首格 -> 区域映射，避免每个单元格全扫描，并能按区域总宽排版文本
        val firstCellToRegion: Map<Pair<Int, Int>, CellRangeAddress> = merged.associateBy {
            it.firstRow to it.firstColumn
        }

        items.add(PageItem.Fill(Rect4(0f, 0f, pageW.toFloat(), pageH.toFloat()), 0xFFFFFFFF))

        // 单元格文本与底色
        for (rowIdx in startRow until endRow) {
            val row = runCatching { sheet.getRow(rowIdx) }.getOrNull() ?: continue
            val y = (rowIdx - startRow) * rowH
            for (c in 0 until colWidths.size) {
                val cell = runCatching { row.getCell(c) }.getOrNull() ?: continue
                val text = cellText(cell)
                // 被合并区域覆盖的非首单元格跳过（避免重复绘制）
                val covered = merged.any { mr ->
                    mr.firstRow <= rowIdx && rowIdx <= mr.lastRow &&
                        mr.firstColumn <= c && c <= mr.lastColumn &&
                        !(mr.firstRow == rowIdx && mr.firstColumn == c)
                }
                if (covered) continue
                val x = colStartX(colWidths, c) * scale
                // 合并区首格的文本按**整个区域**的宽度排版，而不是首列宽度
                // （否则本来一行的标题被压窄换行、甚至被截断）
                val region = firstCellToRegion[rowIdx to c]
                val w = if (region != null) {
                    (colEndX(colWidths, region.lastColumn) -
                        colStartX(colWidths, region.firstColumn)) * scale
                } else {
                    colWidths[c] * scale
                }
                val style = runCatching { cell.cellStyle }.getOrNull()
                val font = style?.let { s ->
                    runCatching { wb?.getFontAt(s.fontIndexAsInt) }.getOrNull()
                }
                val fill = style?.let { s ->
                    runCatching { colorArgb(s.fillForegroundColorColor) }.getOrNull()
                }
                if (text.isEmpty() && fill == null) continue
                fill?.let {
                    items.add(PageItem.Fill(Rect4(x, y, x + w, y + rowH), it))
                }
                if (text.isNotEmpty()) {
                    val argb = font?.let { runCatching { fontColorArgb(it) }.getOrNull() }
                    val bold = runCatching { font?.bold }.getOrNull() ?: false
                    items.add(PageItem.TextBlock(
                        Rect4(x + 4f, y, x + w - 4f, y + rowH),
                        listOf(DocParagraph(listOf(
                            DocRun(text, FONT_PX * scale, argb, bold)
                        )))
                    ))
                }
            }
        }

        // 网格线（后画，压在填充之上）
        var acc = 0f
        for (c in 0 until colWidths.size) {
            items.add(PageItem.Line(acc * scale, 0f, acc * scale, pageH.toFloat(), 0xFFD0D0D0, 1f))
            acc += colWidths[c]
        }
        items.add(PageItem.Line(totalW * scale, 0f, totalW * scale, pageH.toFloat(), 0xFFD0D0D0, 1f))
        for (r in startRow..endRow) {
            val y = (r - startRow) * rowH
            items.add(PageItem.Line(0f, y, totalW * scale, y, 0xFFD0D0D0, 1f))
        }

        // 合并区域加粗边框（裁剪到当前页行范围）
        for (mr in merged) {
            if (mr.lastRow < startRow || mr.firstRow >= endRow) continue
            val top = (kotlin.math.max(mr.firstRow, startRow) - startRow) * rowH
            val bottom = (kotlin.math.min(mr.lastRow, endRow - 1) + 1 - startRow) * rowH
            val left = colStartX(colWidths, mr.firstColumn) * scale
            val right = colEndX(colWidths, mr.lastColumn) * scale
            val border = 0xFF9AA0A6
            items.add(PageItem.Line(left, top, right, top, border, 1.5f))
            items.add(PageItem.Line(left, bottom, right, bottom, border, 1.5f))
            items.add(PageItem.Line(left, top, left, bottom, border, 1.5f))
            items.add(PageItem.Line(right, top, right, bottom, border, 1.5f))
        }

        return DocPage(targetWidthPx.coerceAtLeast(pageW), pageH, items)
    }

    private fun colStartX(colWidths: FloatArray, col: Int): Float =
        (0 until col.coerceAtMost(colWidths.size)).sumOf { colWidths[it].toDouble() }.toFloat()

    private fun colEndX(colWidths: FloatArray, col: Int): Float {
        // 合并区域可能超出已知列数（colCount 由各行 lastCellNum 求得）：
        // 超出部分按默认列宽延伸，否则合并区右边框会被画短
        val extra = (col + 1 - colWidths.size).coerceAtLeast(0)
        return colStartX(colWidths, col) + extra * MIN_COL_W
    }

    /**
     * 取单元格显示文本。
     *
     * 关键点：Excel 日期/时间/百分比的存储值是数值 + 格式串，POI 不会自动套格式，
     * 直接取 numericCellValue 会显示成 `45292` 这样的序列号而非 `2024-01-01`。
     * 因此 NUMERIC/FORMULA 统一用 [DataFormatter]（按 cellStyle 套格式，但
     * **不**求值公式——不传 evaluator）。
     */
    private fun cellText(cell: Cell): String {
        val raw = runCatching {
            when (cell.cellType) {
                CellType.NUMERIC, CellType.FORMULA ->
                    FORMATTER.formatCellValue(cell)
                CellType.STRING -> cell.stringCellValue?.trim() ?: ""
                CellType.BOOLEAN -> cell.booleanCellValue.toString()
                else -> ""
            }
        }.getOrDefault("")
        // 单元格里同样可能有控制字符（换行/制表/未转义标记），统一清洗后再绘制
        return TextSanitizer.clean(raw)
    }

    private val FORMATTER = DataFormatter()

    /**
     * POI 的 org.apache.poi.ss.usermodel.Color 是空标记接口，实际实例为
     * XSSFColor（getARGB → byte[]）或 HSSFColor（getTriplet → short[]），
     * 需分别转换为统一 ARGB。
     */
    private fun colorArgb(color: org.apache.poi.ss.usermodel.Color?): Long? {
        if (color == null) return null
        return runCatching {
            when (color) {
                is org.apache.poi.xssf.usermodel.XSSFColor ->
                    color.getARGB()?.let { bytesToArgb(it) }
                is org.apache.poi.hssf.util.HSSFColor ->
                    color.triplet?.let { shortsToRgb(it) }
                else -> null
            }
        }.getOrNull()
    }

    private fun fontColorArgb(font: Font): Long? = runCatching {
        // .xls 的字体色需要 HSSFFont.getHSSFColor(workbook) 传入工作簿才能取到
        // 调色板颜色，这里没有 Workbook 上下文，暂按默认色绘制（保持原行为）
        (font as? org.apache.poi.xssf.usermodel.XSSFFont)?.let {
            colorArgb(it.getXSSFColor())
        }
    }.getOrNull()

    private fun bytesToArgb(a: ByteArray): Long {
        val hasAlpha = a.size >= 4
        val av = if (hasAlpha) a[0].toInt() and 0xFF else 0xFF
        val r = (if (hasAlpha) a[1] else a[0]).toInt() and 0xFF
        val g = (if (hasAlpha) a[2] else a[1]).toInt() and 0xFF
        val b = (if (hasAlpha) a[3] else a[2]).toInt() and 0xFF
        return (av.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private fun shortsToRgb(t: ShortArray): Long {
        return (0xFFL shl 24) or
            ((t[0].toInt() and 0xFF).toLong() shl 16) or
            ((t[1].toInt() and 0xFF).toLong() shl 8) or
            (t[2].toInt() and 0xFF).toLong()
    }
}
