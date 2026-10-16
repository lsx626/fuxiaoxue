package edu.fudan.elearning.sync.preview

import java.io.File
import java.util.zip.ZipFile

data class ArchiveEntry(val name: String, val bytes: Long, val directory: Boolean)
data class ArchiveIndex(val entries: List<ArchiveEntry>, val totalEntries: Int)
internal fun archiveIndex(file: File, limit: Int = 1000): ArchiveIndex = ZipFile(file).use { archive ->
    val entries = archive.entries()
    val rows = mutableListOf<ArchiveEntry>()
    while (entries.hasMoreElements() && rows.size < limit) {
        val item = entries.nextElement()
        rows += ArchiveEntry(item.name.take(2000), item.size, item.isDirectory)
    }
    ArchiveIndex(rows, archive.size())
}
