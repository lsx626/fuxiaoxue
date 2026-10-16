package edu.fudan.elearning.sync.office

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PptxPreviewCopyInstrumentedTest {
    @Test fun oversizedVideoIsRemovedFromTemporaryCopyWithoutChangingOriginal() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "large-media-synthetic.pptx")
        val document = "<slide>kept</slide>".toByteArray()
        val video = ByteArray(64) { it.toByte() }
        ZipOutputStream(source.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("ppt/slides/slide1.xml")); output.write(document); output.closeEntry()
            output.putNextEntry(ZipEntry("ppt/media/media1.mp4")); output.write(video); output.closeEntry()
        }
        try {
            val prepared = PptxPreviewCopy.prepare(
                context, source, packageThreshold = 1, partThreshold = 8
            )
            assertTrue(prepared.temporary)
            assertTrue(prepared.file.exists())
            ZipFile(prepared.file).use { zip ->
                assertArrayEquals(document, zip.getInputStream(zip.getEntry("ppt/slides/slide1.xml")).readBytes())
                assertEquals(0L, zip.getEntry("ppt/media/media1.mp4").size)
            }
            assertEquals(video.size.toLong(), ZipFile(source).use {
                it.getEntry("ppt/media/media1.mp4").size
            })
            prepared.close()
            assertFalse(prepared.file.exists())
        } finally {
            source.delete()
        }
    }
}
