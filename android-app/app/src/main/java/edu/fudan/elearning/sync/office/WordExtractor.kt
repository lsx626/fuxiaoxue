package edu.fudan.elearning.sync.office

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.hwpf.model.PicturesTable
import org.apache.poi.hwpf.usermodel.CharacterRun
import org.apache.poi.hwpf.usermodel.Picture
import org.apache.poi.hwpf.usermodel.Range
import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFPicture
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.apache.poi.xwpf.usermodel.XWPFTableCell
import java.io.File

/**
 * 把 Word（docx/doc）解析成 [FlowDocument]：保留段落文本与逐段字符格式
 * （字号/颜色/加粗/斜体/下划线）、内嵌图片与表格；分页由 Android 侧按真实
 * 文本测量完成，表格可跨页，版式接近原阅读器——而非只提取纯文字。
 *
 * - .docx（OOXML）：[XWPFDocument]，按正文元素顺序遍历段落与表格，
 *   图片取自 run 的 embeddedPictures，表格取列声明宽度做列宽权重。
 * - .doc（旧二进制 OLE2）：POI scratchpad 的 [HWPFDocument]，逐 [CharacterRun]
 *   取字符格式；图片走 [PicturesTable]；表格走 isInTable/getTable，
 *   并按字符偏移跳过表内段落避免重复输出。
 *
 * 页面按 A4 纵向、以 [targetWidthPx] 等比缩放，避免按 DPI 渲染出超大位图导致 OOM。
 */
object WordExtractor {

    /** A4 纵向，单位磅（72dpi 近似：595x842pt）。 */
    private const val A4_W_PT = 595f
    private const val A4_H_PT = 842f
    private const val MARGIN_PT = 56f
    private const val EMU_PER_PT = 12700f

    /**
     * @param downscale 内嵌图片降采样：页模型会让图片原始字节常驻内存，
     * 含大量照片的讲义会直接撞堆上限（OOX 打不开）。默认不处理，由
     * OfficeExtractor 注入与 PPT 路径相同的降采样实现。
     */
    fun extract(
        file: File,
        targetWidthPx: Int,
        downscale: (ByteArray) -> ByteArray = { it }
    ): FlowDocument {
        val name = file.name.lowercase()
        val scale = targetWidthPx.toFloat() / A4_W_PT
        // 打开阶段抛出的异常（损坏/加密）交给 OfficeExtractor 报告为「解析失败」。
        return if (name.endsWith(".docx")) {
            XWPFDocument(file.inputStream()).use { extractX(it, scale, downscale) }
        } else {
            HWPFDocument(file.inputStream()).use { extractH(it, scale, downscale) }
        }
    }

    // ------------------------------------------------------------------
    // .docx
    // ------------------------------------------------------------------

    private fun extractX(
        doc: XWPFDocument,
        scale: Float,
        downscale: (ByteArray) -> ByteArray
    ): FlowDocument {
        val blocks = mutableListOf<FlowBlock>()
        // 按正文顺序遍历段落与表格（doc.paragraphs 会丢掉表格在正文中的位置）
        for (element in doc.bodyElements) {
            when (element) {
                is XWPFParagraph -> appendXParagraph(element, blocks, scale, downscale)
                is XWPFTable -> appendXTable(element, blocks, scale)
                else -> { /* SDT 等结构化块，本次不渲染 */ }
            }
        }
        return flowDoc(blocks, scale)
    }

    private fun appendXParagraph(
        para: XWPFParagraph,
        blocks: MutableList<FlowBlock>,
        scale: Float,
        downscale: (ByteArray) -> ByteArray
    ) {
        // 内嵌图片：取 run 中的图片，按原始尺寸（EMU->px）输出
        for (pic in runCatching { para.runs.flatMap { it.embeddedPictures } }
            .getOrDefault(emptyList())) {
            addPicture(pic, blocks, scale, downscale)
        }
        val runs = para.runs.mapNotNull { run ->
            runCatching { convertRun(run, scale) }.getOrNull()
        }.filter { it.text.isNotEmpty() }
        if (runs.isEmpty()) {
            blocks.add(FlowBlock.Spacer(8f * scale))
            return
        }
        val align = when (runCatching { para.alignment }.getOrNull()) {
            ParagraphAlignment.CENTER -> DocAlign.CENTER
            ParagraphAlignment.RIGHT, ParagraphAlignment.END -> DocAlign.END
            ParagraphAlignment.BOTH, ParagraphAlignment.DISTRIBUTE -> DocAlign.JUSTIFY
            else -> DocAlign.START
        }
        blocks.add(FlowBlock.Paragraph(
            DocParagraph(runs, align, spaceAfterPx = 4f * scale)
        ))
    }

    private fun appendXTable(table: XWPFTable, blocks: MutableList<FlowBlock>, scale: Float) {
        runCatching {
            val rows = table.rows.map { row ->
                val cells = runCatching { row.tableCells }.getOrDefault(emptyList())
                    .map { cell -> convertCellX(cell, scale) }
                DocRow(cells, 0f)
            }
            if (rows.any { it.cells.isNotEmpty() }) {
                blocks.add(FlowBlock.Table(rows, columnWeightsX(table, rows)))
            }
        }
    }

    private fun convertCellX(cell: XWPFTableCell, scale: Float): DocCell {
        // 单元文本：合并多段，去掉尾部换行；XWPFTableCell.getText() 已含段落分隔
        val text = runCatching { cell.getText() }.getOrDefault("").trim()
        if (text.isEmpty()) return DocCell("")
        val para = runCatching { cell.paragraphs.firstOrNull() }.getOrNull()
        val run = para?.runs?.firstOrNull()
        val sizePt = runCatching { run?.fontSizeAsDouble }.getOrNull() ?: 12.0
        val argb = runCatching { run?.color }.getOrNull()?.let { rgbToArgb(it) }
        val bold = runCatching { run?.isBold }.getOrDefault(false) == true
        val fill = runCatching { cell.getColor() }.getOrNull()?.let { rgbToArgb(it) }
        return DocCell(text, argb, fill, (sizePt * scale).toFloat(), bold)
    }

    /** 取各列声明宽度做权重（单位不限，只取比例）；都缺省时按文本长度估算。 */
    private fun columnWeightsX(table: XWPFTable, rows: List<DocRow>): List<Float> {
        val ncol = rows.maxOf { it.cells.size }.coerceAtLeast(1)
        val w = FloatArray(ncol)
        table.rows.forEach { row ->
            runCatching {
                row.tableCells.forEachIndexed { i, cell ->
                    if (i >= ncol) return@forEachIndexed
                    val cw = runCatching { cell.getWidthDecimal() }.getOrNull() ?: 0.0
                    if (cw > 0) w[i] = maxOf(w[i], cw.toFloat())
                }
            }
        }
        // 缺省列宽时用首行文本长度做粗略权重，避免均分压窄长文本列
        if (w.all { it <= 0f }) {
            rows.firstOrNull()?.cells?.forEachIndexed { i, cell ->
                if (i < ncol) w[i] = cell.text.length.coerceAtLeast(1).toFloat()
            }
        }
        return w.map { if (it <= 0f) 1f else it }
    }

    private fun addPicture(
        pic: XWPFPicture,
        blocks: MutableList<FlowBlock>,
        scale: Float,
        downscale: (ByteArray) -> ByteArray
    ) {
        runCatching {
            // XWPFPicture.getWidth()/getDepth() 返回磅（已从 wp:extent 的 EMU 换算），
            // 再按页面缩放因子转成像素
            val wPt = pic.width
            val hPt = pic.depth
            if (wPt <= 0 || hPt <= 0) return@runCatching
            val wPx = (wPt * scale).toInt().coerceAtLeast(1)
            val hPx = (hPt * scale).toInt().coerceAtLeast(1)
            val data = pic.pictureData ?: return@runCatching
            // 大图先降采样再入模型，避免页模型常驻数百 MB 原始字节
            blocks.add(FlowBlock.Picture(downscale(data.data), "image/*", wPx, hPx))
        }
    }

    private fun convertRun(run: XWPFRun, scale: Float): DocRun? {
        val text = TextSanitizer.clean(run.text())
        if (text.isEmpty()) return null
        // XWPFRun.getFontSize() 返回 int（未设置时为 -1），正数时为磅值
        val sizePt = runCatching { run.fontSizeAsDouble }.getOrNull() ?: 12.0
        val argb = runCatching {
            val rgb = run.color
            if (!rgb.isNullOrBlank() && rgb.length >= 6) {
                (0xFFL shl 24) or rgb.substring(0, 6).toLong(16)
            } else null
        }.getOrNull()
        return DocRun(
            text = text,
            sizePx = (sizePt * scale).toFloat(),
            argb = argb,
            bold = run.isBold,
            italic = run.isItalic,
            underline = run.underline != UnderlinePatterns.NONE
        )
    }

    // ------------------------------------------------------------------
    // .doc（HWPF，旧二进制格式）
    // ------------------------------------------------------------------

    private fun extractH(
        doc: HWPFDocument,
        scale: Float,
        downscale: (ByteArray) -> ByteArray
    ): FlowDocument {
        val blocks = mutableListOf<FlowBlock>()
        val range: Range = doc.range
        // PicturesTable 在 hwpf.model 包；个别损坏文档构造它可能失败，容错为 null
        val pics: PicturesTable? = runCatching { doc.picturesTable }.getOrNull()
        var i = 0
        while (i < range.numParagraphs()) {
            val p = runCatching { range.getParagraph(i) }.getOrNull()
            if (p == null) { i += 1; continue }

            // 表格：段落 isInTable() 时取整张表，并按字符偏移跳过表内后续段落
            if (runCatching { p.isInTable }.getOrDefault(false)) {
                val table = runCatching { range.getTable(p) }.getOrNull()
                if (table != null) {
                    appendHTable(table, blocks, scale)
                    val tableEnd = runCatching { table.endOffset }.getOrDefault(-1)
                    if (tableEnd > 0) {
                        // 跳过起点仍在表内的段落（HWPF 表内段落也在同一 Range 里）
                        while (i < range.numParagraphs()) {
                            val np = runCatching { range.getParagraph(i) }.getOrNull()
                            if (np == null) { i += 1; break }
                            if (runCatching { np.startOffset }.getOrDefault(Int.MAX_VALUE) >= tableEnd) break
                            i += 1
                        }
                    } else {
                        i += 1
                    }
                    continue
                }
                // getTable 失败时该段落不输出内容，避免输出表格行尾控制符
                i += 1
                continue
            }

            // 内嵌图片 + 逐 CharacterRun 的字符格式
            val runs = mutableListOf<DocRun>()
            val n = runCatching { p.numCharacterRuns() }.getOrDefault(0)
            for (r in 0 until n) {
                val cr = runCatching { p.getCharacterRun(r) }.getOrNull() ?: continue
                // 图片运行：hasPicture 判定后抽取，文本部分跳过（占位符字符）
                if (pics != null && runCatching { pics.hasPicture(cr) }.getOrDefault(false)) {
                    val pic = runCatching { pics.extractPicture(cr, false) }.getOrNull()
                    if (pic != null) addPictureH(pic, blocks, scale, downscale)
                    continue
                }
                val text = runCatching { cr.text() }.getOrNull().orEmpty()
                    .replace(CONTROL_CHARS, "")
                if (text.isBlank()) continue
                runs.add(convertRunH(cr, text.trim(), scale))
            }
            if (runs.isEmpty()) {
                blocks.add(FlowBlock.Spacer(8f * scale))
            } else {
                val align = when (runCatching { p.justification }.getOrNull()) {
                    1 -> DocAlign.CENTER
                    2 -> DocAlign.END
                    3, 4, 5 -> DocAlign.JUSTIFY
                    else -> DocAlign.START
                }
                blocks.add(FlowBlock.Paragraph(
                    DocParagraph(runs, align, spaceAfterPx = 4f * scale)
                ))
            }
            i += 1
        }
        return flowDoc(blocks, scale)
    }

    private fun appendHTable(table: org.apache.poi.hwpf.usermodel.Table,
                            blocks: MutableList<FlowBlock>, scale: Float) {
        runCatching {
            val rows = mutableListOf<DocRow>()
            val nRows = table.numRows()
            for (r in 0 until nRows) {
                val row = table.getRow(r)
                val cells = mutableListOf<DocCell>()
                for (c in 0 until row.numCells()) {
                    cells.add(convertCellH(row.getCell(c), scale))
                }
                rows.add(DocRow(cells, 0f))
            }
            if (rows.any { it.cells.isNotEmpty() }) {
                blocks.add(FlowBlock.Table(rows, columnWeightsH(rows)))
            }
        }
    }

    private fun convertCellH(cell: org.apache.poi.hwpf.usermodel.TableCell, scale: Float): DocCell {
        // 单元文本：TableCell 继承 Range，text() 含段落分隔与行尾控制符，需清洗
        val raw = runCatching { cell.text() }.getOrDefault("").orEmpty()
        val text = raw.replace(CONTROL_CHARS, "")
            .replace('\n', ' ').replace('\r', ' ').trim()
        if (text.isEmpty()) return DocCell("")
        // 取单元首个段落首个 CharacterRun 的格式近似
        val nPara = runCatching { cell.numParagraphs() }.getOrDefault(0)
        val cr = if (nPara > 0) {
            runCatching {
                val pp = cell.getParagraph(0)
                if (pp.numCharacterRuns() > 0) pp.getCharacterRun(0) else null
            }.getOrNull()
        } else null
        val sizePt = (runCatching { cr?.fontSize }.getOrNull() ?: 22) / 2f  // HWPF fontSize 为半磅
        val argb = cr?.let {
            runCatching {
                val rgb = it.ico24           // 24 位 RGB，-1 表示无
                if (rgb >= 0) (0xFFL shl 24) or (rgb.toLong() and 0xFFFFFF) else null
            }.getOrNull()
        }
        val bold = runCatching { cr?.isBold }.getOrDefault(false) == true
        // HWPF TableCell 无公开底色 API，旧格式表格单元不取填充色（仅边框与文字）
        return DocCell(text, argb, null, sizePt * scale, bold)
    }

    /** .doc 无可靠列宽声明，按各行单元文本长度估算列权重。 */
    private fun columnWeightsH(rows: List<DocRow>): List<Float> {
        val ncol = rows.maxOf { it.cells.size }.coerceAtLeast(1)
        val w = FloatArray(ncol)
        rows.forEach { row ->
            row.cells.forEachIndexed { i, cell ->
                if (i < ncol) w[i] = maxOf(w[i], cell.text.length.toFloat())
            }
        }
        return w.map { if (it <= 0f) 1f else it }
    }

    private fun addPictureH(
        pic: Picture,
        blocks: MutableList<FlowBlock>,
        scale: Float,
        downscale: (ByteArray) -> ByteArray
    ) {
        runCatching {
            // Picture.getDxaGoal()/getDyaGoal() 返回 EMU
            val wEmu = pic.dxaGoal
            val hEmu = pic.dyaGoal
            val data = pic.content ?: return@runCatching
            if (data.isEmpty()) return@runCatching
            // 大图先降采样再入模型（与 .docx 路径一致）
            val sampled = downscale(data)
            if (wEmu > 0 && hEmu > 0) {
                val wPx = (wEmu / EMU_PER_PT * scale).toInt().coerceAtLeast(1)
                val hPx = (hEmu / EMU_PER_PT * scale).toInt().coerceAtLeast(1)
                blocks.add(FlowBlock.Picture(sampled, "image/*", wPx, hPx))
            } else {
                // 尺寸未知：交给分页器按真实解码尺寸适配
                blocks.add(FlowBlock.Picture(sampled, "image/*", 0, 0))
            }
        }
    }

    private fun convertRunH(cr: CharacterRun, text: String, scale: Float): DocRun {
        // HWPF fontSize 单位为半磅；ico24 为 24 位 RGB，-1 表示未设置
        val sizePt = (runCatching { cr.fontSize }.getOrNull() ?: 22) / 2f
        val argb = runCatching {
            val rgb = cr.ico24
            if (rgb >= 0) (0xFFL shl 24) or (rgb.toLong() and 0xFFFFFF) else null
        }.getOrNull()
        return DocRun(
            text = text,
            sizePx = sizePt * scale,
            argb = argb,
            bold = runCatching { cr.isBold }.getOrDefault(false),
            italic = runCatching { cr.isItalic }.getOrDefault(false),
            underline = runCatching { cr.underlineCode }.getOrDefault(0) != 0,
            family = runCatching { cr.fontName }.getOrNull()
        )
    }

    // ------------------------------------------------------------------
    // 公共工具
    // ------------------------------------------------------------------

    /** ARGB 十六进制（"RRGGBB" 或带 #）-> Long；非法返回 null。 */
    private fun rgbToArgb(rgb: String?): Long? {
        if (rgb.isNullOrBlank()) return null
        val hex = if (rgb.startsWith("#")) rgb.substring(1) else rgb
        if (hex.length < 6) return null
        return runCatching { (0xFFL shl 24) or hex.substring(0, 6).toLong(16) }.getOrNull()
    }

    private fun flowDoc(blocks: List<FlowBlock>, scale: Float): FlowDocument {
        return FlowDocument(
            pageWidthPx = (A4_W_PT * scale).toInt(),
            pageHeightPx = (A4_H_PT * scale).toInt(),
            marginPx = MARGIN_PT * scale,
            blocks = blocks
        )
    }

    /** 旧二进制格式里需要丢弃的控制字符（单元格分隔 \u0007、图片占位 \u0001 等）。 */
    private val CONTROL_CHARS = Regex("[\\u0001\\u0007\\u0008\\u000b\\u000c\\r]")
}
