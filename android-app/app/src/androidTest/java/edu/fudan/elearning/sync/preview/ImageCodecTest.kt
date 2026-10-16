package edu.fudan.elearning.sync.preview

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageCodecTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    @Test fun applicationImageLoaderDecodesSvg() = runBlocking {
        val file = File(context.cacheDir, "synthetic.svg").apply {
            writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="100" height="80"><rect width="100" height="80" fill="#4f46e5"/></svg>""")
        }
        try {
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(file).build())
            assertTrue("SVG must decode in production loader: $result", result is SuccessResult)
        } finally { file.delete() }
    }
    @Test fun applicationImageLoaderDecodesGif() = runBlocking {
        val file = File(context.cacheDir, "synthetic.gif").apply {
            writeBytes(android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT))
        }
        try {
            val result = context.imageLoader.execute(ImageRequest.Builder(context).data(file).build())
            assertTrue("GIF must decode in production loader: $result", result is SuccessResult)
        } finally { file.delete() }
    }
}
