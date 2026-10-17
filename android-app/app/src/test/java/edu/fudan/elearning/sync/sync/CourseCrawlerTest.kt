package edu.fudan.elearning.sync.sync

import edu.fudan.elearning.sync.network.CanvasAnnouncement
import edu.fudan.elearning.sync.network.CanvasAssignment
import edu.fudan.elearning.sync.network.CanvasAttachment
import edu.fudan.elearning.sync.network.CanvasDataSource
import edu.fudan.elearning.sync.network.CanvasFile
import edu.fudan.elearning.sync.network.CanvasFolder
import edu.fudan.elearning.sync.network.CanvasModule
import edu.fudan.elearning.sync.network.CanvasModuleItem
import edu.fudan.elearning.sync.network.CanvasPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 课程抓取的单测：目录路径重建、多来源去重、引用型文件补元数据、 以及「文件主列表失败 → 不允许判定远端删除」的安全闸门。 */
class CourseCrawlerTest {

    /** 可配置的假数据源：只实现被抓取用到的接口。 */
    private class FakeSource(
        private val files: List<CanvasFile> = emptyList(),
        private val folders: List<CanvasFolder> = emptyList(),
        private val modules: List<CanvasModule> = emptyList(),
        private val moduleItems: List<CanvasModuleItem> = emptyList(),
        private val pages: List<CanvasPage> = emptyList(),
        private val pageBodies: Map<String, String> = emptyMap(),
        private val assignments: List<CanvasAssignment> = emptyList(),
        private val announcements: List<CanvasAnnouncement> = emptyList(),
        private val syllabus: String = "",
        private val fileMetadata: Map<Long, CanvasFile> = emptyMap(),
        private val failFiles: Boolean = false,
        private val failPages: Boolean = false,
    ) : CanvasDataSource {
        val requestedFileIds = mutableListOf<Long>()

        override suspend fun getCourseFiles(courseId: Long): List<CanvasFile> {
            if (failFiles) throw IllegalStateException("boom-files")
            return files
        }

        override suspend fun getFolders(courseId: Long) = folders

        override suspend fun getModules(courseId: Long) = modules

        override suspend fun getModuleItems(courseId: Long, moduleId: Long) = moduleItems

        override suspend fun getPages(courseId: Long): List<CanvasPage> {
            if (failPages) throw IllegalStateException("boom-pages")
            return pages
        }

        override suspend fun getPageBody(courseId: Long, pageUrl: String) =
            pageBodies[pageUrl].orEmpty()

        override suspend fun getAssignments(courseId: Long) = assignments

        override suspend fun getAnnouncements(courseId: Long) = announcements

        override suspend fun getSyllabus(courseId: Long) = syllabus

        override suspend fun getFile(courseId: Long, fileId: Long): CanvasFile? {
            requestedFileIds += fileId
            return fileMetadata[fileId]
        }
    }

    private fun file(
        id: Long,
        name: String,
        folderId: Long = 0,
        size: Long = 100,
        updatedAt: String = "2026-09-19T10:00:00Z",
    ) =
        CanvasFile(
            id = id,
            displayName = name,
            filename = name,
            size = size,
            url = "https://canvas/files/$id/download",
            folderId = folderId,
            updatedAt = updatedAt,
        )

    @Test
    fun folderPaths_areRebuiltFromParentChainWithCycleGuard() = runBlocking {
        val folders =
            listOf(
                CanvasFolder(1, "course files", null),
                CanvasFolder(2, "第一周", 1),
                CanvasFolder(3, "课件", 2),
                CanvasFolder(4, "环A", 5),
                CanvasFolder(5, "环B", 4),
            )
        val paths = CourseCrawler(FakeSource()).folderPaths(folders)

        assertEquals("第一周", paths[2])
        assertEquals("第一周/课件", paths[3])
        // 自引用/成环时不能无限递归
        assertTrue(paths[4]!!.isNotEmpty())
        assertTrue(paths[5]!!.isNotEmpty())
    }

    @Test
    fun crawlsFilesWithFolderPathAndDedupesAcrossSources() = runBlocking {
        val source =
            FakeSource(
                files = listOf(file(10, "讲义.pdf", folderId = 2), file(11, "习题.pdf", folderId = 3)),
                folders =
                    listOf(
                        CanvasFolder(1, "course files", null),
                        CanvasFolder(2, "第一周", 1),
                        CanvasFolder(3, "课件", 2),
                    ),
                modules =
                    listOf(
                        CanvasModule(
                            100,
                            "模块一",
                            listOf(
                                CanvasModuleItem(1, "File", 10, "讲义"),
                                CanvasModuleItem(2, "Page", null, "页面"),
                            ),
                        )
                    ),
                pages =
                    listOf(
                        CanvasPage(
                            "intro",
                            "绪论",
                            body = "<a href=\"/courses/1/files/11/download\">x</a>",
                        )
                    ),
                syllabus = "<p><a href=\"/courses/1/files/10/preview\">讲义</a></p>",
            )

        val outcome = CourseCrawler(source).crawl(1)

        assertTrue(outcome.filesListedOk)
        assertEquals(2, outcome.files.size)
        val handout = outcome.files.first { it.fileId == 10L }
        // 第一次发现（文件主列表）保留权威路径：第一周
        assertEquals("第一周", handout.folderPath)
        // 模块与大纲只是累加来源
        assertTrue("sources=${handout.sources}", handout.sources.contains("module"))
        assertTrue("sources=${handout.sources}", handout.sources.contains("syllabus"))
        val exercise = outcome.files.first { it.fileId == 11L }
        assertEquals("第一周/课件", exercise.folderPath)
        assertTrue(exercise.sources.contains("page"))
    }

    @Test
    fun referencedFilesFetchMetadataWhenNotInMainList() = runBlocking {
        val source =
            FakeSource(
                files = emptyList(),
                assignments =
                    listOf(
                        CanvasAssignment(
                            id = 1,
                            name = "作业一",
                            description = "<a href=\"/courses/1/files/77/download\">附件</a>",
                            attachments =
                                listOf(
                                    CanvasAttachment(
                                        id = 88,
                                        displayName = "答案.pdf",
                                        filename = "答案.pdf",
                                        size = 555,
                                        url = "https://canvas/files/88/download",
                                        updatedAt = "2026-09-18T00:00:00Z",
                                    )
                                ),
                        )
                    ),
                announcements =
                    listOf(
                        CanvasAnnouncement(
                            id = 2,
                            title = "开学通知",
                            message = "<p>见 <a href=\"/courses/1/files/77/preview\">讲义</a></p>",
                        )
                    ),
                fileMetadata = mapOf(77L to file(77, "讲义.pdf")),
            )

        val outcome = CourseCrawler(source).crawl(1)

        assertEquals(listOf(77L), source.requestedFileIds)
        assertEquals(setOf(77L, 88L), outcome.files.map { it.fileId }.toSet())
        val referenced = outcome.files.first { it.fileId == 77L }
        assertTrue(referenced.sources.contains("assignment"))
        assertTrue(referenced.sources.contains("announcement"))
        val attachment = outcome.files.first { it.fileId == 88L }
        assertEquals("答案.pdf", attachment.displayName)
        assertTrue(attachment.sources.contains("assignment"))
    }

    @Test
    fun filesListedOkIsFalseWhenMainListingFails_evenIfOtherSourcesSucceed() = runBlocking {
        val source =
            FakeSource(
                failFiles = true,
                pages =
                    listOf(
                        CanvasPage(
                            "intro",
                            "绪论",
                            body = "<a href=\"/courses/1/files/5/download\">x</a>",
                        )
                    ),
                fileMetadata = mapOf(5L to file(5, "a.pdf")),
            )

        val outcome = CourseCrawler(source).crawl(1)

        assertFalse("文件主列表失败时绝不能允许判定远端删除", outcome.filesListedOk)
        assertTrue(outcome.errors.any { it.startsWith("course_files") })
    }

    @Test
    fun otherSourceFailureDoesNotBlockDeletionGate() = runBlocking {
        val source = FakeSource(files = listOf(file(1, "a.pdf")), failPages = true)

        val outcome = CourseCrawler(source).crawl(1)

        assertTrue(outcome.filesListedOk)
        assertEquals(1, outcome.files.size)
        assertTrue(outcome.errors.any { it.startsWith("pages") })
    }

    @Test
    fun missingMetadataForReferencedFileIsSkippedNotFatal() = runBlocking {
        val source =
            FakeSource(
                files = emptyList(),
                syllabus = "<a href=\"/courses/1/files/999/download\">坏引用</a>",
            )
        val outcome = CourseCrawler(source).crawl(1)
        assertTrue(outcome.files.isEmpty())
        assertTrue(outcome.filesListedOk)
    }
}
