package edu.fudan.elearning.sync.util

import edu.fudan.elearning.sync.office.TextSanitizer
import java.io.ByteArrayOutputStream
import java.io.File

data class DocumentText(val text: String, val bytes: Long, val truncated: Boolean)

data class TextChunk(val start: Int, val text: String)

object DocumentTexts {
    fun read(file: File, maxBytes: Int): DocumentText {
        val size = file.length()
        val raw =
            file.inputStream().use { stream ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
                val buffer = ByteArray(8192)
                while (output.size() < maxBytes) {
                    val n = stream.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                    if (n < 0) break
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        return DocumentText(decode(raw, size > maxBytes), size, size > maxBytes)
    }

    fun decode(raw: ByteArray, truncated: Boolean = false, sanitize: Boolean = true): String {
        fun finish(text: String) = if (sanitize) TextSanitizer.clean(text) else text
        fun prefix(vararg bytes: Int) =
            raw.size >= bytes.size && bytes.indices.all { raw[it] == bytes[it].toByte() }
        val (encoding, offset) =
            when {
                prefix(0xFF, 0xFE, 0, 0) -> "UTF-32LE" to 4
                prefix(0, 0, 0xFE, 0xFF) -> "UTF-32BE" to 4
                prefix(0xFF, 0xFE) -> "UTF-16LE" to 2
                prefix(0xFE, 0xFF) -> "UTF-16BE" to 2
                prefix(0xEF, 0xBB, 0xBF) -> "UTF-8" to 3
                else -> "" to 0
            }
        if (encoding.isNotEmpty()) {
            val text = finish(String(raw, offset, raw.size - offset, charset(encoding)))
            return if (truncated) text.removeSuffix("\uFFFD") else text
        }
        val utf8 = String(raw, Charsets.UTF_8)
        if ('\uFFFD' !in utf8) return finish(utf8)
        if (truncated)
            for (tail in 1..minOf(3, raw.size)) {
                val trimmed = String(raw, 0, raw.size - tail, Charsets.UTF_8)
                if ('\uFFFD' !in trimmed) return finish(trimmed)
            }
        val gb = String(raw, charset("GB18030"))
        if ('\uFFFD' !in gb) return finish(gb)
        return finish(String(raw, Charsets.ISO_8859_1))
    }

    fun chunks(text: String, limit: Int = 8000): List<TextChunk> {
        require(limit >= 2) { "Text chunk size must accommodate a surrogate pair" }
        val result = mutableListOf<TextChunk>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + limit, text.length)
            if (end < text.length) {
                val newline = text.lastIndexOf('\n', end - 1)
                if (newline >= start + limit / 2) end = newline + 1
                else if (text[end - 1].isHighSurrogate()) end--
            }
            result += TextChunk(start, text.substring(start, end))
            start = end
        }
        return result
    }
}
