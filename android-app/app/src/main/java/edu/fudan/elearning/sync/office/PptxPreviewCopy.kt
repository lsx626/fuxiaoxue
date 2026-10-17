package edu.fudan.elearning.sync.office

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Builds a short-lived PPTX copy without oversized non-visual payloads.
 *
 * Teachers often embed full lecture recordings in a slide deck. Apache POI materialises those ZIP
 * parts even though the page renderer only shows a video placeholder, so a 300 MiB recording can
 * exhaust the Android heap before the first slide is visible. The original download is never
 * touched; sharing still uses that complete file.
 */
internal object PptxPreviewCopy {
    private const val LARGE_PACKAGE_BYTES = 96L * 1024 * 1024
    private const val LARGE_NON_VISUAL_PART_BYTES = 8L * 1024 * 1024
    private val nonVisualExtensions =
        setOf(
            "mp4",
            "m4v",
            "mov",
            "avi",
            "wmv",
            "webm",
            "mpg",
            "mpeg",
            "mp3",
            "m4a",
            "aac",
            "wav",
            "wma",
            "flac",
            "ogg",
            "tmp",
        )

    data class Prepared(val file: File, val temporary: Boolean) {
        fun close() {
            if (temporary) file.delete()
        }
    }

    suspend fun prepare(
        context: Context,
        source: File,
        packageThreshold: Long = LARGE_PACKAGE_BYTES,
        partThreshold: Long = LARGE_NON_VISUAL_PART_BYTES,
    ): Prepared {
        if (!source.extension.equals("pptx", true) || source.length() <= packageThreshold) {
            return Prepared(source, false)
        }
        val target = File.createTempFile("pptx-preview-", ".pptx", context.cacheDir)
        try {
            ZipFile(source).use { input ->
                ZipOutputStream(target.outputStream().buffered()).use { output ->
                    val entries = input.entries()
                    val buffer = ByteArray(64 * 1024)
                    while (entries.hasMoreElements()) {
                        currentCoroutineContext().ensureActive()
                        val entry = entries.nextElement()
                        val copy = ZipEntry(entry.name).apply { time = entry.time }
                        output.putNextEntry(copy)
                        if (!shouldStrip(entry, partThreshold)) {
                            input.getInputStream(entry).use { stream ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = stream.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                        output.closeEntry()
                    }
                }
            }
            return Prepared(target, true)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private fun shouldStrip(entry: ZipEntry, partThreshold: Long): Boolean {
        if (entry.isDirectory || entry.size in 0..partThreshold) return false
        val name = entry.name.lowercase()
        val ext = name.substringAfterLast('.', "")
        return (name.startsWith("ppt/media/") && ext in nonVisualExtensions) ||
            name.startsWith("ppt/embeddings/")
    }
}
