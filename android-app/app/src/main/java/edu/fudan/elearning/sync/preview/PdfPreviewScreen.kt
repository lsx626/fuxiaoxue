package edu.fudan.elearning.sync.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import java.io.File
import kotlinx.coroutines.*

/** One owned renderer; cancellation always releases it, including during opening. */
@Composable
fun PdfPreviewScreen(
    file: File,
    initialPage: Int = -1,
    onPageChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    val state by
        produceState<PdfLoadState>(PdfLoadState.Loading, file.absolutePath) {
            var acquired: PdfPageSource? = null
            value = PdfLoadState.Loading
            try {
                withContext(Dispatchers.IO) {
                    val descriptor =
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    try {
                        acquired = PdfPageSource(descriptor, PdfRenderer(descriptor))
                    } catch (error: Throwable) {
                        descriptor.close()
                        throw error
                    }
                    acquired!!.prefetchAspects { ensureActive() }
                }
                ensureActive()
                val source = acquired!!
                value =
                    if (source.pageCount == 0) PdfLoadState.Error("该 PDF 没有可显示的页面")
                    else PdfLoadState.Ready(source)
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                value = PdfLoadState.Error("无法打开 PDF，文件可能损坏、已加密或设备内存不足")
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { acquired?.close() }
            }
        }
    when (val result = state) {
        PdfLoadState.Loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        is PdfLoadState.Error -> PreviewError(result.message)
        is PdfLoadState.Ready ->
            VerticalPageList(
                result.source.pageCount,
                result.source::aspectOf,
                { result.source.renderPage(it) },
                initialPage = initialPage,
                onPageChanged = onPageChanged,
            )
    }
}

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState

    data class Error(val message: String) : PdfLoadState

    data class Ready(val source: PdfPageSource) : PdfLoadState
}

private class PdfPageSource(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) {
    val pageCount = renderer.pageCount
    private val aspects = FloatArray(pageCount) { 0.75f }
    private var closed = false

    fun aspectOf(index: Int): Float = aspects.getOrElse(index) { 0.75f }

    fun prefetchAspects(checkActive: () -> Unit) =
        synchronized(renderer) {
            for (index in 0 until pageCount) {
                checkActive()
                renderer.openPage(index).use { page ->
                    if (page.width > 0 && page.height > 0)
                        aspects[index] = page.width.toFloat() / page.height
                }
            }
        }

    fun renderPage(index: Int): Bitmap? =
        synchronized(renderer) {
            if (closed || index !in 0 until pageCount) return@synchronized null
            runCatching {
                    renderer.openPage(index).use { page ->
                        val scale =
                            minOf(
                                2f,
                                4096f / page.width.coerceAtLeast(1),
                                4096f / page.height.coerceAtLeast(1),
                            )
                        val bitmap =
                            Bitmap.createBitmap(
                                (page.width * scale).toInt().coerceAtLeast(1),
                                (page.height * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888,
                            )
                        try {
                            Canvas(bitmap).drawColor(Color.WHITE)
                            page.render(
                                bitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            bitmap
                        } catch (error: Throwable) {
                            bitmap.recycle()
                            throw error
                        }
                    }
                }
                .getOrNull()
        }

    fun close() =
        synchronized(renderer) {
            if (!closed) {
                closed = true
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }
        }
}
