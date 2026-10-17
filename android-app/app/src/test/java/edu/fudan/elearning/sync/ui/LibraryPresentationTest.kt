package edu.fudan.elearning.sync.ui

import edu.fudan.elearning.sync.data.Course
import edu.fudan.elearning.sync.data.CourseStats
import edu.fudan.elearning.sync.data.FileItem
import edu.fudan.elearning.sync.data.Repo
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryPresentationTest {
    private val files =
        listOf(
            FileItem(1, 1, "绪论", "lecture.PDF", folderPath = "教材", status = "downloaded") to null,
            FileItem(2, 1, "第二讲", "lec2.pdf", status = "downloaded") to
                Repo.ReadingProgress(2, 9, 10, false, "2026-10-01"),
            FileItem(3, 1, "作业", "hw.py", status = "failed") to null,
            FileItem(4, 1, "答案", "answer.zip", status = "locked") to null,
        )

    @Test
    fun courseSearchCombinesCodeAndTerm() {
        val courses =
            listOf(
                CourseStats(Course(1, "普通化学", "CHEM101", "秋"), 0, 0, 0),
                CourseStats(Course(2, "程序设计", "CS101", "春"), 0, 0, 0),
            )
        assertEquals(listOf(1L), filterLibraryCourses(courses, " chem ", "秋").map { it.course.id })
        assertEquals(emptyList<CourseStats>(), filterLibraryCourses(courses, "chem", "春"))
        assertEquals(
            listOf(2L),
            filterLibraryCourses(courses, "lecture", null, setOf(2L)).map { it.course.id },
        )
    }

    @Test
    fun fileSearchUsesDisplayNameFilenameAndFolder() {
        for (query in listOf("绪论", "PDF", "教材")) {
            val result = filterLibraryFiles(files, query, LibraryFileFilter.ALL)
            assertEquals(1L, result.first().first.fileId)
        }
        assertEquals(4, files.size)
    }

    @Test
    fun unreadExcludesFailedAndLockedFiles() {
        assertEquals(
            listOf(1L),
            filterLibraryFiles(files, "", LibraryFileFilter.UNREAD).map { it.first.fileId },
        )
        assertEquals(
            listOf(3L),
            filterLibraryFiles(files, "HW", LibraryFileFilter.FAILED).map { it.first.fileId },
        )
        assertEquals(
            emptyList<FileItem>(),
            filterLibraryFiles(files, "PDF", LibraryFileFilter.LOCKED).map { it.first },
        )
    }

    @Test
    fun sortPreservesUnreadOrderAndSortsReadByRecency() {
        val moreRecent =
            FileItem(5, 1, "第五讲") to Repo.ReadingProgress(5, 1, 10, false, "2026-10-02")
        assertEquals(
            listOf(1L, 3L, 4L, 5L, 2L),
            sortFilesUnreadFirst(files + moreRecent).map { it.first.fileId },
        )
    }

    @Test
    fun finalPageShowsOneHundredPercentAndMediaUsesSeconds() {
        assertEquals("第 10 / 10 页 · 100%", progressText(Repo.ReadingProgress(1, 9, 10, false, "")))
        assertEquals("第 2 / 10 页 · 20%", progressText(Repo.ReadingProgress(1, 1, 10, false, "")))
        assertEquals("1:00 / 2:00", progressText(Repo.ReadingProgress(1, 60, 120, true, "")))
        assertEquals("未读", progressText(null))
    }
}
