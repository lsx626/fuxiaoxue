package edu.fudan.elearning.sync.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用内 PDF 预览：PdfRenderer 逐页位图，**纵向连续滚动**（下拉式翻页），
 * 每页可双指缩放与双击缩放；渲染失败显示结构化错误，不跳转第三方应用。
 *
 * 全程复用一份 [PdfRenderer] 实例渲染所有页，位图由 [PageBitmapCache] 按需缓存。
 */
@Composable
fun PdfPreviewScreen(file: File) {
    var loadState by remember(file.absolutePath) { mutableStateOf<PdfLoadState>(PdfLoadState.Loading) }
    var source by remember(file.absolutePath) { mutableStateOf<PdfPageSource?>(null) }

    LaunchedEffect(file.absolutePath) {
        withContext(Dispatchers.IO) {
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                if (renderer.pageCount <= 0) {
                    runCatching { renderer.close() }
                    runCatching { pfd.close() }
                    loadState = PdfLoadState.Error("该 PDF 没有可显示的页面")
                    return@withContext
                }
                source = PdfPageSource(pfd, renderer)
                loadState = PdfLoadState.Ready(renderer.pageCount)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // 离开预览的取消必须原样抛出，绝不能当成「解析失败」
                throw cancelled
            } catch (error: Throwable) {
                // PdfRenderer 构造失败时 pfd 必须关闭，否则文件描述符泄漏
                runCatching { pfd?.close() }
                loadState = PdfLoadState.Error("无法打开 PDF：${error.message ?: "文件可能已损坏"}")
            }
        }
    }

    DisposableEffect(file.absolutePath) {
        onDispose {
            source?.close()
            source = null
        }
    }

    when (val s = loadState) {
        PdfLoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is PdfLoadState.Error -> PreviewError(s.message)
        is PdfLoadState.Ready -> {
            val src = source
            if (src == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                VerticalPageList(
                    pageCount = s.pageCount,
                    aspectOf = { src.aspectOf(it) },
                    renderPage = { src.renderPage(it) }
                )
            }
        }
    }
}

private sealed class PdfLoadState {
    object Loading : PdfLoadState()
    data class Ready(val pageCount: Int) : PdfLoadState()
    data class Error(val message: String) : PdfLoadState()
}

/** 持有一份 PdfRenderer，按需渲染单页；页面宽高比缓存后复用，避免重复 openPage。 */
private class PdfPageSource(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    private val aspects = HashMap<Int, Float>()

    fun aspectOf(index: Int): Float {
        aspects[index]?.let { return it }
        // aspectOf 在主线程调用、renderPage 在 IO 线程调用；PdfRenderer 的
        // openPage 并非线程安全，用同一把锁串行化，避免原生层竞态崩溃
        val a = synchronized(renderer) {
            runCatching {
                renderer.openPage(index).use { p ->
                    if (p.width <= 0 || p.height <= 0) DEFAULT_ASPECT
                    else p.width.toFloat() / p.height.toFloat()
                }
            }.getOrDefault(DEFAULT_ASPECT)
        }
        aspects[index] = a
        return a
    }

    /** 以 2 倍清晰度渲染单页；长边上限 [MAX_PAGE_DIMEN]，防止位图过大 OOM。 */
    fun renderPage(index: Int): Bitmap? = runCatching {
        if (index < 0 || index >= renderer.pageCount) return null
        synchronized(renderer) {
            renderer.openPage(index).use { page ->
                val width = (page.width * RENDER_SCALE).coerceAtMost(MAX_PAGE_DIMEN).coerceAtLeast(1)
                val height = (page.height * RENDER_SCALE).coerceAtMost(MAX_PAGE_DIMEN).coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }.getOrNull()

    fun close() {
        runCatching { renderer.close() }
        runCatching { pfd.close() }
    }

    companion object {
        private const val RENDER_SCALE = 2
        private const val MAX_PAGE_DIMEN = 4096
        private const val DEFAULT_ASPECT = 0.75f
    }
}