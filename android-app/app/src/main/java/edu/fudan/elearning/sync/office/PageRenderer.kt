package edu.fudan.elearning.sync.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 把 [DocPage] 绘制成位图，并把 Word 的 [FlowDocument] 按真实文本测量分页。
 *
 * 绘制策略：
 * - 文本用 StaticLayout（支持换行与富文本 Span），位置由模型决定；
 * - 图片解码后按目标矩形等比缩放；
 * - 表格逐行绘制单元文本、底色与网格线，列宽按权重归一化；
 * - Word 表格可跨页：按行高把表格切成多段，分别落到不同页。
 */
object PageRenderer {

    /** 表格单元内边距与最小行高（像素）。 */
    private const val CELL_PADDING = 6f
    private const val CELL_EXTRA_H = 8f
    private const val MIN_ROW_H = 18f

    /** 占位卡配色（浅灰底 + 虚线框 + 中灰文字，明暗主题下都清晰可读）。 */
    private const val PLACEHOLDER_FILL = 0xFFF1F2F6
    private const val PLACEHOLDER_STROKE = 0xFFC9CDD6
    private const val PLACEHOLDER_TEXT = 0xFF5A6070

    /** ARGB Long -> Int 颜色。 */
    private fun Long.toIntColor(): Int = this.toInt()

    /**
     * Word 流式文档分页：按段落/表格真实高度切页，尽量贴近原阅读器版式。
     */
    fun paginate(doc: FlowDocument): List<DocPage> {
        if (doc.blocks.isEmpty()) {
            return listOf(DocPage(doc.pageWidthPx, doc.pageHeightPx,
                listOf(PageItem.Fill(Rect4(0f, 0f, doc.pageWidthPx.toFloat(), doc.pageHeightPx.toFloat()), 0xFFFFFFFF))))
        }
        val pages = mutableListOf<DocPage>()
        val contentW = (doc.pageWidthPx - 2 * doc.marginPx).toFloat()
        // 一页可用的内容高度（上下留边）
        val contentH = (doc.pageHeightPx - 2 * doc.marginPx).toFloat()
        var items = mutableListOf<PageItem>()
        var y = doc.marginPx

        fun flushPage() {
            pages.add(DocPage(doc.pageWidthPx, doc.pageHeightPx, items))
            items = mutableListOf()
            y = doc.marginPx
        }

        for (block in doc.blocks) {
            when (block) {
                is FlowBlock.Paragraph -> {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                    val firstRun = block.para.runs.firstOrNull()
                    paint.textSize = firstRun?.sizePx ?: 13f
                    paint.color = (firstRun?.argb ?: 0xFF1A1A1A).toIntColor()
                    val sb = buildSpannable(block.para)
                    val layout = StaticLayout.Builder.obtain(
                        sb, 0, sb.length, paint, contentW.roundToInt()
                    ).setAlignment(alignOf(block.para.align))
                        .setLineSpacing(0f, 1.25f)
                        .build()
                    val h = layout.height + block.para.spaceAfterPx
                    if (y + h > doc.pageHeightPx - doc.marginPx && items.isNotEmpty()) {
                        flushPage()
                    }
                    val rect = Rect4(doc.marginPx, y, doc.pageWidthPx - doc.marginPx, y + h)
                    items.add(PageItem.TextBlock(rect, listOf(block.para)))
                    y += h
                }
                is FlowBlock.Picture -> {
                    var w = block.widthPx.toFloat()
                    var h = block.heightPx.toFloat()
                    if (w <= 0 || h <= 0) {
                        // 声明尺寸缺失（旧格式图片），用真实解码尺寸按内容宽度适配
                        val ratio = decodeRatio(block.bytes)
                        if (ratio > 0) {
                            w = contentW
                            h = contentW / ratio
                        } else {
                            w = contentW * 0.6f
                            h = contentW * 0.4f
                        }
                    } else if (w > contentW) {
                        val r = contentW / w
                        w *= r; h *= r
                    }
                    // 超高图片最多占一页可用高度的 85%，避免单图撑爆内存
                    val maxH = contentH * 0.85f
                    if (h > maxH) {
                        val r = maxH / h
                        w *= r; h *= r
                    }
                    if (y + h > doc.pageHeightPx - doc.marginPx && items.isNotEmpty()) {
                        flushPage()
                    }
                    items.add(PageItem.Image(
                        Rect4(doc.marginPx, y, doc.marginPx + w, y + h),
                        block.bytes, block.mime
                    ))
                    y += h + 8f
                }
                is FlowBlock.Spacer -> {
                    y += block.heightPx
                }
                is FlowBlock.Table -> {
                    val rows = block.rows
                    if (rows.isEmpty()) continue
                    val ncol = rows.maxOf { it.cells.size }.coerceAtLeast(1)
                    val colWs = columnWidths(ncol, block.columnWeights, contentW)
                    // 按可用页高把表格切成多段；单行超高时独占一页
                    val chunks = mutableListOf<Pair<List<DocRow>, Float>>()
                    var curRows = mutableListOf<DocRow>()
                    var curH = 0f
                    for (row in rows) {
                        val rowH = measureRowHeight(row, colWs)
                        if (curH + rowH > contentH && curRows.isNotEmpty()) {
                            chunks.add(curRows.toList() to curH)
                            curRows = mutableListOf()
                            curH = 0f
                        }
                        curRows.add(row.copy(heightPx = rowH))
                        curH += rowH
                    }
                    if (curRows.isNotEmpty()) chunks.add(curRows.toList() to curH)
                    // 逐段放入当前页，放不下则翻页
                    for ((chunkRows, chunkH) in chunks) {
                        if (y + chunkH > doc.pageHeightPx - doc.marginPx && items.isNotEmpty()) {
                            flushPage()
                        }
                        items.add(PageItem.Table(
                            Rect4(doc.marginPx, y, doc.pageWidthPx - doc.marginPx, y + chunkH),
                            chunkRows, colWs
                        ))
                        y += chunkH
                    }
                }
            }
            if (y > doc.pageHeightPx - doc.marginPx && items.isNotEmpty()) {
                flushPage()
            }
        }
        if (items.isNotEmpty()) flushPage()
        return pages
    }

    /**
     * 列权重 -> 绝对像素宽（按内容宽度归一化）；权重缺失或列数不符则均分。
     *
     * 24px 下限施加后再做一次总宽归一化：列数很多时（如 40 列 × 880px 内容宽）
     * 直接 clamp 会让总宽 960 > 880，右边的列被画到页边距外裁掉。归一化后
     * 各列允许窄于 24px，但总宽恒等于内容宽度。
     */
    private fun columnWidths(ncol: Int, weights: List<Float>, contentW: Float): List<Float> {
        if (ncol <= 0) return emptyList()
        if (weights.size == ncol && weights.sum() > 0f) {
            val sum = weights.sum()
            val clamped = weights.map { (it / sum * contentW).coerceAtLeast(24f) }
            val total = clamped.sum()
            return if (total <= contentW) clamped
            else clamped.map { it / total * contentW }
        }
        return List(ncol) { contentW / ncol }
    }

    /** 实测一行高度：取行内各单元文本换行后的最大高度。列数不匹配时按均分重算。 */
    private fun measureRowHeight(row: DocRow, colWs: List<Float>): Float {
        if (row.cells.isEmpty()) return MIN_ROW_H
        val widths = if (row.cells.size == colWs.size) colWs
            else List(row.cells.size) { colWs.sum() / row.cells.size }
        var maxH = 0f
        row.cells.forEachIndexed { i, cell ->
            if (cell.text.isEmpty()) return@forEachIndexed
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            paint.textSize = cell.sizePx
            paint.isFakeBoldText = cell.bold
            val w = (widths[i] - 2 * CELL_PADDING).roundToInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(
                SpannableStringBuilder(cell.text), 0, cell.text.length, paint, w
            ).setLineSpacing(0f, 1.2f).build()
            val lh = layout.height.toFloat()
            if (lh > maxH) maxH = lh
        }
        return (maxH + CELL_EXTRA_H).coerceAtLeast(MIN_ROW_H)
    }

    /** 解码图片真实宽高比；失败返回 -1。 */
    private fun decodeRatio(bytes: ByteArray): Float {
        return runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) {
                opts.outWidth.toFloat() / opts.outHeight.toFloat()
            } else -1f
        }.getOrDefault(-1f)
    }

    private fun alignOf(align: DocAlign): Layout.Alignment = when (align) {
        DocAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
        DocAlign.END -> Layout.Alignment.ALIGN_OPPOSITE
        DocAlign.JUSTIFY -> Layout.Alignment.ALIGN_NORMAL
        DocAlign.START -> Layout.Alignment.ALIGN_NORMAL
    }

    /** 拼接富文本：字号/颜色/加粗斜体/下划线。 */
    private fun buildSpannable(para: DocParagraph): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (run in para.runs) {
            val start = sb.length
            sb.append(run.text)
            val end = sb.length
            sb.setSpan(AbsoluteSizeSpan(run.sizePx.roundToInt()), start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            run.argb?.let {
                sb.setSpan(ForegroundColorSpan(it.toIntColor()), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.bold && run.italic) {
                sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (run.bold) {
                sb.setSpan(StyleSpan(Typeface.BOLD), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (run.italic) {
                sb.setSpan(StyleSpan(Typeface.ITALIC), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.underline) {
                sb.setSpan(UnderlineSpan(), start, end,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** 把一页绘制成位图（在 IO 线程调用）。 */
    fun renderPage(page: DocPage): Bitmap {
        val bmp = Bitmap.createBitmap(page.widthPx, page.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        for (item in page.items) {
            when (item) {
                is PageItem.Fill -> {
                    val p = Paint().apply { color = item.argb.toIntColor() }
                    canvas.drawRect(item.rect.toRectF(), p)
                }
                is PageItem.Image -> drawImage(canvas, item)
                is PageItem.TextBlock -> drawText(canvas, item)
                is PageItem.Table -> drawTable(canvas, item)
                is PageItem.Line -> drawLine(canvas, item)
                is PageItem.Shape -> drawShape(canvas, item)
                is PageItem.Placeholder -> drawPlaceholder(canvas, item)
            }
        }
        return bmp
    }

    /** 直线 + 两端箭头（连接线、装饰箭头等）。 */
    private fun drawLine(canvas: Canvas, item: PageItem.Line) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = item.argb.toIntColor()
            strokeWidth = item.widthPx
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(item.x1, item.y1, item.x2, item.y2, paint)
        val dx = item.x2 - item.x1
        val dy = item.y2 - item.y1
        val length = hypot(dx, dy)
        if (length < 0.5f) return
        val ux = dx / length
        val uy = dy / length
        val size = (item.widthPx * 4f).coerceIn(8f, 40f)
        if (item.endArrow == ArrowEnd.ARROW) {
            drawArrowHead(canvas, item.x2, item.y2, ux, uy, size, paint)
        }
        if (item.startArrow == ArrowEnd.ARROW) {
            drawArrowHead(canvas, item.x1, item.y1, -ux, -uy, size, paint)
        }
    }

    /** 在 (x, y) 处画一个指向 (ux, uy) 方向的实心箭头。 */
    private fun drawArrowHead(canvas: Canvas, x: Float, y: Float,
                              ux: Float, uy: Float, size: Float, paint: Paint) {
        val px = -uy
        val py = ux
        val half = size * 0.5f
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x - ux * size + px * half, y - uy * size + py * half)
            lineTo(x - ux * size - px * half, y - uy * size - py * half)
            close()
        }
        canvas.drawPath(path, Paint(paint).apply { style = Paint.Style.FILL })
    }

    /**
     * 自选形状：按几何生成 Path，先填充后描边，整体围绕形状中心旋转。
     *
     * 未在 [ShapeGeometry] 中建模的形状退化为矩形（[ShapeGeometry.OTHER]），
     * 保持「位置与配色正确、细节不保证」的诚实降级。
     */
    private fun drawShape(canvas: Canvas, item: PageItem.Shape) {
        if (item.fill == null && item.stroke == null) return
        val rect = item.rect.toRectF()
        if (rect.width() <= 0f || rect.height() <= 0f) return
        canvas.save()
        if (item.rotationDeg != 0f) {
            canvas.rotate(item.rotationDeg, rect.centerX(), rect.centerY())
        }
        val path = pathFor(item.geometry, rect)
        item.fill?.let { argb ->
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb.toIntColor()
                style = Paint.Style.FILL
            })
        }
        item.stroke?.let { argb ->
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = argb.toIntColor()
                style = Paint.Style.STROKE
                strokeWidth = item.strokeWidthPx
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            })
        }
        canvas.restore()
    }

    /**
     * 占位卡：图表 / SmartArt / OLE 嵌入 / 视频等无法高保真还原的元素。
     * 浅灰圆角 + 虚线边框 + 说明文字，明确告知限制，不假装成功。
     */
    private fun drawPlaceholder(canvas: Canvas, item: PageItem.Placeholder) {
        val rect = item.rect.toRectF()
        if (rect.width() <= 1f || rect.height() <= 1f) return
        val radius = 12f
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_FILL.toIntColor()
        })
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_STROKE.toIntColor()
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        })
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (rect.height() * 0.16f).coerceIn(12f, 26f)
            color = PLACEHOLDER_TEXT.toIntColor()
        }
        val textWidth = (rect.width() - 24f).roundToInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder.obtain(
            SpannableStringBuilder(item.label), 0, item.label.length, paint, textWidth
        ).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .build()
        canvas.save()
        canvas.translate(rect.left + 12f, rect.centerY() - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    /** 几何 -> Path。所有坐标都基于传入的矩形，便于统一旋转与缩放。 */
    private fun pathFor(geometry: ShapeGeometry, rect: RectF): Path {
        val path = Path()
        val l = rect.left
        val t = rect.top
        val r = rect.right
        val b = rect.bottom
        val w = rect.width()
        val h = rect.height()
        when (geometry) {
            ShapeGeometry.RECT, ShapeGeometry.OTHER, ShapeGeometry.CALLOUT ->
                path.addRect(rect, Path.Direction.CW)
            ShapeGeometry.ROUND_RECT, ShapeGeometry.FLOWCHART_PROCESS -> {
                val radius = minOf(w, h) * 0.18f
                path.addRoundRect(rect, radius, radius, Path.Direction.CW)
            }
            ShapeGeometry.ELLIPSE -> path.addOval(rect, Path.Direction.CW)
            ShapeGeometry.TRIANGLE -> {
                path.moveTo(rect.centerX(), t); path.lineTo(r, b); path.lineTo(l, b); path.close()
            }
            ShapeGeometry.RT_TRIANGLE -> {
                path.moveTo(l, t); path.lineTo(l, b); path.lineTo(r, b); path.close()
            }
            ShapeGeometry.DIAMOND, ShapeGeometry.FLOWCHART_DECISION -> {
                path.moveTo(rect.centerX(), t); path.lineTo(r, rect.centerY())
                path.lineTo(rect.centerX(), b); path.lineTo(l, rect.centerY()); path.close()
            }
            ShapeGeometry.PENTAGON -> {
                path.moveTo(rect.centerX(), t)
                path.lineTo(r, t + h * 0.38f)
                path.lineTo(r - w * 0.18f, b)
                path.lineTo(l + w * 0.18f, b)
                path.lineTo(l, t + h * 0.38f)
                path.close()
            }
            ShapeGeometry.CHEVRON -> {
                val notch = w * 0.25f
                path.moveTo(l, t)
                path.lineTo(r - notch, t)
                path.lineTo(r, rect.centerY())
                path.lineTo(r - notch, b)
                path.lineTo(l, b)
                path.lineTo(l + notch, rect.centerY())
                path.close()
            }
            ShapeGeometry.RIGHT_ARROW, ShapeGeometry.LEFT_ARROW -> {
                val right = geometry == ShapeGeometry.RIGHT_ARROW
                val shaft = h * 0.5f
                val head = w * 0.45f
                if (right) {
                    path.moveTo(l, rect.centerY() - shaft / 2)
                    path.lineTo(r - head, rect.centerY() - shaft / 2)
                    path.lineTo(r - head, t)
                    path.lineTo(r, rect.centerY())
                    path.lineTo(r - head, b)
                    path.lineTo(r - head, rect.centerY() + shaft / 2)
                    path.lineTo(l, rect.centerY() + shaft / 2)
                } else {
                    path.moveTo(r, rect.centerY() - shaft / 2)
                    path.lineTo(l + head, rect.centerY() - shaft / 2)
                    path.lineTo(l + head, t)
                    path.lineTo(l, rect.centerY())
                    path.lineTo(l + head, b)
                    path.lineTo(l + head, rect.centerY() + shaft / 2)
                    path.lineTo(r, rect.centerY() + shaft / 2)
                }
                path.close()
            }
            ShapeGeometry.UP_ARROW, ShapeGeometry.DOWN_ARROW -> {
                val up = geometry == ShapeGeometry.UP_ARROW
                val shaft = w * 0.5f
                val head = h * 0.45f
                if (up) {
                    path.moveTo(rect.centerX() - shaft / 2, b)
                    path.lineTo(rect.centerX() - shaft / 2, t + head)
                    path.lineTo(l, t + head)
                    path.lineTo(rect.centerX(), t)
                    path.lineTo(r, t + head)
                    path.lineTo(rect.centerX() + shaft / 2, t + head)
                    path.lineTo(rect.centerX() + shaft / 2, b)
                } else {
                    path.moveTo(rect.centerX() - shaft / 2, t)
                    path.lineTo(rect.centerX() - shaft / 2, b - head)
                    path.lineTo(l, b - head)
                    path.lineTo(rect.centerX(), b)
                    path.lineTo(r, b - head)
                    path.lineTo(rect.centerX() + shaft / 2, b - head)
                    path.lineTo(rect.centerX() + shaft / 2, t)
                }
                path.close()
            }
            ShapeGeometry.STAR_4 -> addStar(path, rect, 4, 0.38f)
            ShapeGeometry.STAR_5 -> addStar(path, rect, 5, 0.4f)
            ShapeGeometry.PLUS -> {
                val armX = w * 0.28f
                val armY = h * 0.28f
                path.moveTo(rect.centerX() - armX, t)
                path.lineTo(rect.centerX() + armX, t)
                path.lineTo(rect.centerX() + armX, rect.centerY() - armY)
                path.lineTo(r, rect.centerY() - armY)
                path.lineTo(r, rect.centerY() + armY)
                path.lineTo(rect.centerX() + armX, rect.centerY() + armY)
                path.lineTo(rect.centerX() + armX, b)
                path.lineTo(rect.centerX() - armX, b)
                path.lineTo(rect.centerX() - armX, rect.centerY() + armY)
                path.lineTo(l, rect.centerY() + armY)
                path.lineTo(l, rect.centerY() - armY)
                path.lineTo(rect.centerX() - armX, rect.centerY() - armY)
                path.close()
            }
            ShapeGeometry.LEFT_BRACE, ShapeGeometry.RIGHT_BRACE -> {
                // 花括号只画折线轮廓，不填充（近似还原，细节不保证）
                val left = geometry == ShapeGeometry.LEFT_BRACE
                val outerX = if (left) r else l
                val innerX = if (left) l else r
                path.moveTo(outerX, t)
                path.lineTo(rect.centerX(), t + h * 0.2f)
                path.lineTo(innerX, rect.centerY())
                path.lineTo(rect.centerX(), b - h * 0.2f)
                path.lineTo(outerX, b)
            }
        }
        return path
    }

    /** 星形路径：外顶点 [points] 个，内顶点按 [innerRatio] 收缩。 */
    private fun addStar(path: Path, rect: RectF, points: Int, innerRatio: Float) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val rx = rect.width() / 2f
        val ry = rect.height() / 2f
        val step = Math.PI / points
        var angle = -Math.PI / 2
        for (i in 0 until points * 2) {
            val ratio = if (i % 2 == 0) 1f else innerRatio
            val x = cx + (rx * ratio * Math.cos(angle)).toFloat()
            val y = cy + (ry * ratio * Math.sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            angle += step
        }
        path.close()
    }

    private fun drawImage(canvas: Canvas, item: PageItem.Image) {
        runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size, opts)
            val sample = (opts.outWidth / item.rect.width).toInt().coerceAtLeast(1)
            val opts2 = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
            }
            val src = android.graphics.BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size, opts2)
                ?: return@runCatching
            val srcW = src.width.toFloat()
            val srcH = src.height.toFloat()
            val target = item.rect.toRectF()
            // 保持比例，适配目标矩形
            val scale = minOf(target.width() / srcW, target.height() / srcH)
            val dw = srcW * scale
            val dh = srcH * scale
            val left = target.left + (target.width() - dw) / 2f
            val top = target.top + (target.height() - dh) / 2f
            val dst = RectF(left, top, left + dw, top + dh)
            canvas.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun drawText(canvas: Canvas, item: PageItem.TextBlock) {
        val rect = item.rect.toRectF()
        if (rect.width() <= 1f || rect.height() <= 1f) return
        val layouts = mutableListOf<Pair<StaticLayout?, Float>>()
        var totalH = 0f
        for (para in item.paragraphs) {
            if (para.runs.isEmpty()) {
                layouts.add(null to para.spaceAfterPx)
                totalH += para.spaceAfterPx
                continue
            }
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
            val first = para.runs.first()
            paint.textSize = first.sizePx
            paint.color = (first.argb ?: 0xFF1A1A1A).toIntColor()
            val sb = buildSpannable(para)
            val w = rect.width().roundToInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, w)
                .setAlignment(alignOf(para.align))
                // 必须与分页测量（paginate）使用同一行距倍数，否则分页算出的
                // 高度比实际画出来的大，段尾积累空白、换页偏早
                .setLineSpacing(0f, 1.25f)
                .build()
            layouts.add(layout to para.spaceAfterPx)
            totalH += layout.height + para.spaceAfterPx
        }
        if (layouts.isEmpty()) return
        // 垂直对齐：文本框的 TOP / MIDDLE / BOTTOM
        var y = when (item.valign) {
            VerticalAlign.MIDDLE -> rect.top + (rect.height() - totalH) / 2f
            VerticalAlign.BOTTOM -> rect.bottom - totalH
            VerticalAlign.TOP -> rect.top
        }.coerceAtLeast(rect.top)
        canvas.save()
        if (item.rotationDeg != 0f) {
            canvas.rotate(item.rotationDeg, rect.centerX(), rect.centerY())
        }
        for ((layout, spaceAfter) in layouts) {
            if (layout != null) {
                canvas.save()
                canvas.translate(rect.left, y)
                layout.draw(canvas)
                canvas.restore()
                y += layout.height + spaceAfter
            } else {
                y += spaceAfter
            }
            if (y > rect.bottom) break
        }
        canvas.restore()
    }

    private fun drawTable(canvas: Canvas, table: PageItem.Table) {
        var y = table.rect.top
        for (row in table.rows) {
            val ncell = row.cells.size.coerceAtLeast(1)
            // 有显式列宽且列数匹配时用列宽，否则按行内单元数均分
            val widths = table.columnWidths?.takeIf { it.size == ncell }
                ?: List(ncell) { table.rect.width / ncell }
            var x = table.rect.left
            row.cells.forEachIndexed { i, cell ->
                val cw = widths[i]
                val rectF = RectF(x, y, x + cw, y + row.heightPx)
                cell.fill?.let {
                    canvas.drawRect(rectF, Paint().apply { color = it.toIntColor() })
                }
                // 边框
                canvas.drawRect(rectF, Paint().apply {
                    color = 0xFFC8CDD2.toIntColor()
                    style = Paint.Style.STROKE
                    strokeWidth = 1f
                })
                if (cell.text.isNotEmpty()) {
                    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
                    paint.textSize = cell.sizePx
                    paint.color = (cell.argb ?: 0xFF1A1A1A).toIntColor()
                    paint.isFakeBoldText = cell.bold
                    val sb = SpannableStringBuilder(cell.text)
                    val layout = StaticLayout.Builder.obtain(
                        sb, 0, sb.length, paint, (cw - 2 * CELL_PADDING).roundToInt().coerceAtLeast(1)
                    ).setLineSpacing(0f, 1.2f).build()
                    canvas.save()
                    canvas.translate(x + CELL_PADDING, y + (row.heightPx - layout.height) / 2f)
                    layout.draw(canvas)
                    canvas.restore()
                }
                x += cw
            }
            y += row.heightPx
            if (y > table.rect.bottom) break
        }
    }

    private fun Rect4.toRectF(): RectF = RectF(left, top, right, bottom)
}
