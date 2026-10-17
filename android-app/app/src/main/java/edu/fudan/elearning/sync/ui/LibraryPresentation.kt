package edu.fudan.elearning.sync.ui

import edu.fudan.elearning.sync.data.CourseStats
import edu.fudan.elearning.sync.data.FileItem
import edu.fudan.elearning.sync.data.Repo

internal enum class LibraryFileFilter {
    ALL,
    UNREAD,
    DOWNLOADED,
    FAILED,
    PENDING,
    LOCKED,
}

internal fun filterLibraryCourses(
    courses: List<CourseStats>,
    query: String,
    term: String?,
): List<CourseStats> {
    val text = query.trim()
    return courses.filter {
        (term == null || it.course.term == term) &&
            (text.isEmpty() ||
                it.course.name.contains(text, ignoreCase = true) ||
                it.course.code.contains(text, ignoreCase = true))
    }
}

/** Display-only filtering; never mutate sync records or the preview sibling list. */
internal fun filterLibraryFiles(
    files: List<Pair<FileItem, Repo.ReadingProgress?>>,
    query: String,
    filter: LibraryFileFilter,
): List<Pair<FileItem, Repo.ReadingProgress?>> {
    val text = query.trim()
    return files.filter { (file, progress) ->
        val matchesText =
            text.isEmpty() ||
                listOf(file.name, file.filename, file.folderPath).any {
                    it.contains(text, ignoreCase = true)
                }
        matchesText &&
            when (filter) {
                LibraryFileFilter.ALL -> true
                LibraryFileFilter.UNREAD ->
                    file.status == "downloaded" && (progress?.position ?: 0) <= 0
                LibraryFileFilter.DOWNLOADED -> file.status == "downloaded"
                LibraryFileFilter.FAILED -> file.status == "failed"
                LibraryFileFilter.PENDING -> file.status == "pending"
                LibraryFileFilter.LOCKED -> file.status == "locked"
            }
    }
}

internal fun sortFilesUnreadFirst(
    files: List<Pair<FileItem, Repo.ReadingProgress?>>
): List<Pair<FileItem, Repo.ReadingProgress?>> {
    val (read, unread) = files.partition { (it.second?.position ?: 0) > 0 }
    return unread + read.sortedByDescending { it.second?.updatedAt ?: "" }
}

internal fun sortLibraryFiles(files: List<Pair<FileItem, Repo.ReadingProgress?>>, sort: FileSort) =
    when (sort) {
        FileSort.UNREAD -> sortFilesUnreadFirst(files)
        FileSort.NAME -> files.sortedBy { it.first.name.lowercase(java.util.Locale.ROOT) }
        FileSort.UPDATED -> files.sortedByDescending { it.first.updatedAt }
    }

internal fun progressText(progress: Repo.ReadingProgress?): String {
    if (progress == null || progress.position <= 0) return "未读"
    if (progress.isMedia) {
        fun fmt(sec: Int) = "%d:%02d".format(sec / 60, sec % 60)
        return if (progress.total > 0) "${fmt(progress.position)} / ${fmt(progress.total)}"
        else fmt(progress.position)
    }
    val page = progress.position + 1
    return if (progress.total > 0) {
        val percent = (page.toLong() * 100 / progress.total).coerceIn(0, 100)
        "第 $page / ${progress.total} 页 · $percent%"
    } else "第 $page 页"
}
