package edu.fudan.elearning.sync.preview

import edu.fudan.elearning.sync.search.SearchSnippet
import edu.fudan.elearning.sync.util.DocumentTexts
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ReaderRefactorTest {
    @Test
    fun choosesActuallyVisiblePageRatherThanLargestPage() {
        assertEquals(
            0,
            dominantPage(listOf(VisiblePage(0, -100, 500), VisiblePage(1, 400, 5000)), 0, 600),
        )
        assertEquals(
            1,
            dominantPage(listOf(VisiblePage(0, -450, 500), VisiblePage(1, 50, 5000)), 0, 600),
        )
    }

    @Test
    fun bufferingDoesNotRepeatedlyRestoreOldMediaPosition() {
        val resume = ResumeOnce()
        assertNull(resume.seekPosition(false, 90))
        assertEquals(88_000L, resume.seekPosition(true, 90))
        assertNull(resume.seekPosition(false, 90))
        assertNull(resume.seekPosition(true, 90))
    }

    @Test
    fun noSavedPositionStillMarksRestoreAsComplete() {
        val resume = ResumeOnce()
        assertNull(resume.seekPosition(true, -1))
        assertNull(resume.seekPosition(true, 90))
    }

    @Test
    fun bomEncodingsAndGbkAreReadable() {
        for ((bom, encoding) in
            listOf(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) to "UTF-8",
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) to "UTF-16LE",
                byteArrayOf(0xFE.toByte(), 0xFF.toByte()) to "UTF-16BE",
                byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0) to "UTF-32LE",
                byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()) to "UTF-32BE",
            )) assertEquals(
            encoding,
            "课程资料😀",
            DocumentTexts.decode(bom + "课程资料😀".toByteArray(charset(encoding))),
        )
        assertEquals("课程资料", DocumentTexts.decode("课程资料".toByteArray(charset("GB18030"))))
    }

    @Test
    fun truncatedUtf8DoesNotTurnWholeDocumentIntoGbk() {
        val raw = "课程资料".toByteArray()
        assertEquals("课程资", DocumentTexts.decode(raw.copyOf(raw.size - 1), truncated = true))
    }

    @Test
    fun lazyChunksPreserveEveryCharacterAndEmoji() {
        val text = ("课程 😀\n".repeat(5000))
        val chunks = DocumentTexts.chunks(text)
        assertEquals(text, chunks.joinToString("") { it.text })
        assertTrue(chunks.size > 1)
        chunks.forEach { assertFalse(it.text.last().isHighSurrogate()) }
    }

    @Test
    fun boundedReadReportsTruncationAndHandlesEmptyFile() {
        val file = Files.createTempFile("fxx-text", ".txt").toFile()
        try {
            assertEquals("", DocumentTexts.read(file, 100).text)
            file.writeText("a".repeat(1000))
            val text = DocumentTexts.read(file, 100)
            assertTrue(text.truncated)
            assertEquals(100, text.text.length)
        } finally {
            file.delete()
        }
    }

    @Test
    fun veryShortAndBinaryFilesDoNotCrashTypeDetection() {
        val file = Files.createTempFile("fxx-magic", ".unknown").toFile()
        try {
            for (bytes in
                listOf(
                    byteArrayOf(),
                    byteArrayOf(0xFF.toByte()),
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
                )) {
                file.writeBytes(bytes)
                assertFalse(FileTypes.isMp3(file))
                assertEquals(PreviewKind.UNSUPPORTED, FileTypes.detect(file))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun snippetsKeepOriginalWordsAndRelevantContext() {
        val source = "前文。".repeat(100) + "这里讲述神经网络与反向传播" + "后文。".repeat(100)
        val snippet = SearchSnippet.excerpt(source, "神经网络")
        assertTrue(snippet.contains("神经网络与反向传播"))
        assertTrue(snippet.length <= 184)
        assertFalse(snippet.contains("神 经"))
    }
}
