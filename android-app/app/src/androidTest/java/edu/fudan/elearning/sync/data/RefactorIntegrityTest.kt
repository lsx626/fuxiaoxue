package edu.fudan.elearning.sync.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.fudan.elearning.sync.network.*
import edu.fudan.elearning.sync.sync.*
import edu.fudan.elearning.sync.util.Prefs
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real SQLite and filesystem, synthetic metadata transport; never contacts Canvas. */
@RunWith(AndroidJUnit4::class)
class RefactorIntegrityTest {
    private lateinit var context: Context
    private lateinit var repo: Repo
    private lateinit var local: File

    @Before
    fun seed() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("fudan_sync.db")
        Prefs(context).clearDisabledSources()
        repo = Repo(context)
        repo.upsertCourses(listOf(Course(1, "合成测试课程", "SYNTH")))
        local =
            File(DownloadManager(context).courseDir("合成测试课程"), "old.txt").apply {
                parentFile!!.mkdirs()
                writeText("old copy")
            }
        repo.upsertFile(
            FileItem(
                10,
                1,
                "原文件",
                "old.txt",
                localPath = local.path,
                status = "downloaded",
                updatedAt = "old",
            )
        )
        repo.upsertAssignments(1, listOf(Assignment(20, 1, "旧作业", "2026-12-01T00:00:00Z")))
    }

    @After
    fun close() {
        repo.close()
        local.delete()
    }

    private fun api(
        files: String = "[]",
        filesCode: Int = 200,
        assignmentsCode: Int = 200,
        foldersCode: Int = 200,
        selfCode: Int = 200,
    ) =
        CanvasApi(
            object : HttpTransport {
                override suspend fun get(url: String): HttpResponse =
                    when {
                        url.endsWith("/api/v1/users/self") ->
                            HttpResponse(selfCode, emptyMap(), "{}")
                        url.contains("/folders?") -> HttpResponse(foldersCode, emptyMap(), "[]")
                        url.contains("/files?") -> HttpResponse(filesCode, emptyMap(), files)
                        url.contains("/assignments?") ->
                            HttpResponse(assignmentsCode, emptyMap(), "[]")
                        url.contains("syllabus_body") -> HttpResponse(200, emptyMap(), "{\"id\":1}")
                        url.contains("/api/v1/courses?") ->
                            HttpResponse(
                                200,
                                emptyMap(),
                                "[{\"id\":1,\"name\":\"合成测试课程\",\"course_code\":\"SYNTH\"}]",
                            )
                        else -> HttpResponse(200, emptyMap(), "[]")
                    }
            },
            baseUrl = "https://synthetic.invalid",
            policy = RequestPolicy(minIntervalMs = 0, maxAttempts = 1),
            sleeper = {},
        )

    @Test
    fun failedListingIsNotDeletionOrSuccess() = runBlocking {
        val result = SyncEngine(context, api(filesCode = 500), repo).sync()
        assertFalse(result.ok)
        assertEquals(1, result.failedCourses)
        assertEquals("downloaded", repo.getFile(10)!!.status)
        assertTrue(local.isFile)
    }

    @Test
    fun malformedListingPreservesExistingFiles() = runBlocking {
        val result = SyncEngine(context, api(files = "[{\"id\":{}}]"), repo).sync()
        assertFalse(result.ok)
        assertEquals("downloaded", repo.getFile(10)!!.status)
    }

    @Test
    fun successfulEmptyListingDeletesLocalCopyRecordAndAssignments() = runBlocking {
        val result = SyncEngine(context, api(), repo).sync()
        assertTrue(result.ok)
        assertNull(repo.getFile(10))
        assertFalse(local.exists())
        assertTrue(repo.assignments().isEmpty())
    }

    @Test
    fun failedAssignmentsPreservePreviousDeadlines() = runBlocking {
        val result = SyncEngine(context, api(assignmentsCode = 500), repo).sync()
        assertFalse(result.ok)
        assertEquals("旧作业", repo.assignments().single().name)
    }

    @Test
    fun lockedFileRetainsLocalCopyAndProgress() = runBlocking {
        repo.setReadingProgress(10, 1, 4, 8, false)
        val result =
            SyncEngine(
                    context,
                    api(
                        files =
                            "[{\"id\":10,\"display_name\":\"原文件\",\"filename\":\"old.txt\",\"locked_for_user\":true}]"
                    ),
                    repo,
                )
                .sync()
        assertTrue(result.ok)
        assertEquals("locked", repo.getFile(10)!!.status)
        assertEquals(local.path, repo.getFile(10)!!.localPath)
        assertTrue(local.isFile)
        assertEquals(4, repo.getReadingProgress(10)!!.position)
        assertNull(repo.latestReadFileOfCourse(1))
    }

    @Test
    fun authenticationFailureInOptionalSourceStopsSync() = runBlocking {
        val result = SyncEngine(context, api(foldersCode = 401, selfCode = 401), repo).sync()
        assertFalse(result.ok)
        assertTrue(result.needsReauth)
        assertEquals("downloaded", repo.getFile(10)!!.status)
    }

    @Test
    fun delayedReaderCallbackCannotResurrectDeletedProgress() {
        repo.setReadingProgress(10, 1, 4, 8, false)
        repo.deleteFile(10)
        repo.setReadingProgress(10, 1, 5, 8, false)
        assertNull(repo.getReadingProgress(10))
    }

    @Test
    fun courseAndFileSelectionsUseSeparateFlags() {
        repo.upsertFile(FileItem(11, 1, "待下载文件", "new.pdf", status = "pending"))
        repo.upsertFile(FileItem(12, 1, "失败文件", "retry.pdf", status = "failed"))
        repo.setFileSelected(11, true)
        assertEquals(setOf(11L), repo.selectedFiles().map { it.fileId }.toSet())
        assertFalse(repo.courseStats().single().course.selected)

        repo.setCourseFilesSelected(1, true)
        assertEquals(setOf(11L, 12L), repo.selectedFiles().map { it.fileId }.toSet())
        repo.setCourseFilesSelected(1, false)
        assertTrue(repo.selectedFiles().isEmpty())

        // 重选「挑选文件」不应消掉用户已挑好的文件。
        repo.setFileSelected(11, true)
        repo.setCourseSelected(1, false)
        assertEquals(setOf(11L), repo.selectedFiles().map { it.fileId }.toSet())

        repo.setCourseSelected(1, true)
        assertTrue(repo.courseStats().single().course.selected)
        assertTrue(repo.selectedFiles().isEmpty())

        repo.setCourseSelected(1, false)
        assertFalse(repo.courseStats().single().course.selected)
        assertTrue(repo.selectedFiles().isEmpty())
    }

    @Test
    fun storageRejectsOutsidePathWithoutDeletingFile() {
        val outside = File(context.cacheDir, "synthetic-outside.txt").apply { writeText("keep") }
        try {
            try {
                LocalStorage(context).deleteLocal(repo.getFile(10)!!.copy(localPath = outside.path))
                fail("must reject")
            } catch (_: IllegalArgumentException) {}
            assertTrue(outside.isFile)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun searchUsesOriginalExcerptAndExcludesLockedFiles() {
        repo.upsertFileIndex(10, "原文件", "这里介绍神经网络与反向传播。")
        assertTrue(repo.searchFiles("神经网络").single().snippet.contains("神经网络与反向传播"))
        repo.upsertFile(repo.getFile(10)!!.copy(status = "locked"))
        assertTrue(repo.searchFiles("神经网络").isEmpty())
    }

    @Test
    fun reindexReplacesOldContentInsteadOfDuplicatingResults() {
        repo.upsertFileIndex(10, "原文件", "旧正文独有词")
        repo.upsertFileIndex(10, "原文件", "新正文独有词")
        assertTrue(repo.searchFiles("旧正文").isEmpty())
        assertEquals(1, repo.searchFiles("新正文").size)
        repo.removeFileIndex(10)
        assertTrue(repo.searchFiles("新正文").isEmpty())
    }

    @Test
    fun unavailableFtsFallsBackToLiteralFilenameSearch() {
        repo.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath("fudan_sync.db").path,
                null,
                0,
            )
            .use { it.execSQL("DROP TABLE files_fts") }
        repo = Repo(context)
        assertEquals(10L, repo.searchFiles("原文件").single().fileId)
        assertTrue(repo.searchFilesByName("%").isEmpty())
        assertTrue(repo.searchFilesByName("_").isEmpty())
    }

    @Test
    fun upgradeFromV5PreservesFileAndReadingAndBackfillsTextCache() {
        repo.setReadingProgress(10, 1, 4, 8, false)
        repo.upsertFileIndex(10, "原文件", "内容")
        repo.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath("fudan_sync.db").path,
                null,
                0,
            )
            .use {
                it.execSQL("DROP TABLE file_text_cache")
                it.version = 5
            }
        repo = Repo(context)
        assertEquals(local.path, repo.getFile(10)!!.localPath)
        assertEquals(4, repo.getReadingProgress(10)!!.position)
        assertTrue(repo.unindexedDownloadedFiles(100).any { it.fileId == 10L })
        repo.upsertFileIndex(10, "原文件", "原文片段")
        assertTrue(repo.searchFiles("原文").single().snippet.contains("原文片段"))
    }
}
