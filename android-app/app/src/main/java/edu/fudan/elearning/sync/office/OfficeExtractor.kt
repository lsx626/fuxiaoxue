package edu.fudan.elearning.sync.office

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.poi.util.IOUtils
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Office 文档统一入口：按扩展名分发到幻灯片/文档/表格提取器，返回可直接绘制
 * 的 [DocPage] 列表。解析内部切到 [Dispatchers.IO]，调用方不必再自己包一层。
 *
 * 结果语义明确区分：
 * - [OfficeParseResult.Success]：成功解析出一页或多页。
 * - [OfficeParseResult.Empty]：文件能打开但没有任何可渲染内容（空文档/空表）。
 * - [OfficeParseResult.Unsupported]：扩展名不在支持范围内。
 * - [OfficeParseResult.Failed]：文件损坏、加密或解析抛异常，附带原因。
 */
object OfficeExtractor {

    /** 设备屏幕宽度（px）作为渲染目标宽度。 */
    fun renderWidth(context: Context): Int {
        val dm = context.resources.displayMetrics
        return (dm.widthPixels * 1.6f).toInt().coerceIn(1080, 2560)
    }

    /** 判断是否为可渲染的 Office 文档。 */
    fun isOffice(name: String): Boolean {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "ppt", "pptx", "doc", "docx", "xls", "xlsx" -> true
            else -> false
        }
    }

    /**
     * 放宽 POI 的单记录安全上限（应用启动时调用一次）。
     *
     * POI 默认把「单个记录」的字节数组上限设为 100,000,000 字节，含超大内嵌媒体
     * 或 OLE 的 PPTX 会直接抛异常，表现为「文档解析失败：Tried to allocate an
     * array of length ...」。[OfficeLimits.memoryAwareLimit] 按设备堆大小自适应，
     * 同时把初始缓冲压到 1 MiB，避免 POI 预分配一大块内存。
     *
     * 这里只放宽上限，不改变解析语义；仍然失败时由 [extract] 转成可读说明。
     */
    fun applyPoiLimits() {
        runCatching {
            IOUtils.setMaxByteArrayInitSize(INITIAL_BYTE_ARRAY_LIMIT)
            IOUtils.setByteArrayMaxOverride(OfficeLimits.memoryAwareLimit().toInt())
        }
    }

    /**
     * 解析 Office 文档为页面列表，返回带明确语义的 [OfficeParseResult]。
     *
     * 在 [Dispatchers.IO] 上执行，可取消：
     * - [onProgress] 逐张幻灯片回调 `(已完成, 总数)`，UI 据此显示「正在解析… n/m」；
     * - 协程被取消时不再写回结果（取消异常原样向上抛，绝不转成「解析失败」）；
     * - 大文件自动降低渲染分辨率，页数过多的文档整体缩放，避免 OOM；
     * - 内存不足或 POI 记录超限转成友好说明，不把原始异常串暴露给用户。
     *
     * 所有解析器内部已对单个形状/单元格做了容错，这里只在整体失败时报告错误；
     * 图表、SmartArt、OLE 嵌入等复杂元素不做高保真还原，由提取器输出占位卡。
     */
    suspend fun extract(
        context: Context,
        file: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): OfficeParseResult {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (!isOffice(file.name)) return OfficeParseResult.Unsupported(ext)
        if (!file.exists() || !file.isFile) return OfficeParseResult.Failed("文件不存在或不是普通文件")

        val screenWidth = renderWidth(context)
        // 大文件与屏幕宽度共同决定目标渲染宽度（大文件降到 1080–1440px）
        val targetW = OfficeLimits.targetWidth(screenWidth, file.length())

        return withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            val report: (Int, Int) -> Unit = { done, total ->
                // 解析过程中被取消时立刻中止，不把半成品结果写回 UI
                job?.ensureActive()
                onProgress(done, total)
            }
            runParse {
                val pages: List<DocPage> = when (ext) {
                    "ppt", "pptx" -> SlideExtractor.extract(
                        file, targetW, report, ::downscaleImage
                    )
                    // 与 PPT 路径同样传入降采样：照片多的 docx 会因页模型常驻
                    // 原始字节而 OOM（v1.0.9 只接了 Slide 路径，Word 漏了）
                    "doc", "docx" -> PageRenderer.paginate(
                        WordExtractor.extract(file, targetW, ::downscaleImage)
                    )
                    else -> SheetExtractor.extract(file, targetW)
                }
                val scale = OfficeLimits.pageScale(pages.size)
                if (scale >= 1f) pages else pages.map { it.scaled(scale) }
            }
        }
    }

    /**
     * 解析外壳：把解析异常转成带明确语义的结果。
     *
     * 独立出来是为了能在单元测试里注入 OOM / POI 记录超限异常，验证失败语义，
     * 而不需要真的构造一份超大文档。取消异常必须原样抛出，不能变成 Failed。
     */
    internal fun runParse(load: () -> List<DocPage>): OfficeParseResult {
        return try {
            val pages = load()
            if (pages.isEmpty()) {
                OfficeParseResult.Empty("文档是空的，没有可显示的页面")
            } else {
                OfficeParseResult.Success(pages)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            OfficeParseResult.Failed(OfficeLimits.parseFailureMessage(error))
        }
    }

    /**
     * 内嵌大图入库前降采样。
     *
     * 解析出的页模型会把图片原始字节常驻内存；一份含多张 5000px 照片的 PPT 可以
     * 轻易吃掉几百 MB。超过 [OfficeLimits.MAX_INLINE_IMAGE_BYTES] 的图片先用
     * `inSampleSize` 解码到长边 [MAX_INLINE_IMAGE_DIMEN]，再编码成 PNG 放回模型。
     * 降采样失败或结果反而更大时保留原始字节，绝不丢图。
     */
    private fun downscaleImage(bytes: ByteArray): ByteArray {
        if (bytes.size <= OfficeLimits.MAX_INLINE_IMAGE_BYTES) return bytes
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@runCatching bytes
            var sample = 1
            while (longest / (sample * 2) >= MAX_INLINE_IMAGE_DIMEN) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@runCatching bytes
            val out = ByteArrayOutputStream()
            decoded.compress(Bitmap.CompressFormat.PNG, 100, out)
            decoded.recycle()
            val shrunk = out.toByteArray()
            if (shrunk.isNotEmpty() && shrunk.size < bytes.size) shrunk else bytes
        }.getOrDefault(bytes)
    }

    /** POI 初始缓冲：1 MiB，避免按上限预分配。 */
    private const val INITIAL_BYTE_ARRAY_LIMIT = 1 shl 20

    /** 内嵌图片降采样后的长边上限（px）。 */
    private const val MAX_INLINE_IMAGE_DIMEN = 2048
}

/** Office 解析结果：成功/空/不支持/失败，调用方据此显示明确界面。 */
sealed class OfficeParseResult {
    data class Success(val pages: List<DocPage>) : OfficeParseResult()
    data class Empty(val reason: String) : OfficeParseResult()
    data class Unsupported(val ext: String) : OfficeParseResult()
    data class Failed(val message: String) : OfficeParseResult()
}
