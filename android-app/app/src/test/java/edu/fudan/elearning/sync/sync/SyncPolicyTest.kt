package edu.fudan.elearning.sync.sync

import edu.fudan.elearning.sync.data.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 增量判定与删除安全闸门的单测。 */
class SyncPolicyTest {

    private fun record(
        status: String = "downloaded",
        size: Long = 100,
        updatedAt: String = "2026-09-01T10:00:00Z"
    ) = FileItem(
        fileId = 1L, courseId = 2L, name = "讲义.pdf", filename = "讲义.pdf",
        localPath = "/tmp/讲义.pdf", size = size, status = status, updatedAt = updatedAt
    )

    @Test
    fun fullSyncAlwaysDownloads() {
        assertTrue(
            SyncPolicy.shouldDownload(
                full = true, record = record(), remoteSize = 100,
                remoteUpdatedAt = "2026-09-01T10:00:00Z",
                localExists = true, localLength = 100
            )
        )
    }

    @Test
    fun firstTimeAndFailedRecordsDownload() {
        assertTrue(SyncPolicy.shouldDownload(false, null, 100, "", true, 100))
        assertTrue(SyncPolicy.shouldDownload(false, record(status = "failed"), 100, "", true, 100))
        assertTrue(SyncPolicy.shouldDownload(false, record(status = "pending"), 100, "", true, 100))
    }

    @Test
    fun unchangedDownloadedFileIsSkipped() {
        assertFalse(
            SyncPolicy.shouldDownload(
                full = false, record = record(), remoteSize = 100,
                remoteUpdatedAt = "2026-09-01T10:00:00Z",
                localExists = true, localLength = 100
            )
        )
    }

    @Test
    fun sizeOrTimestampOrLocalStateChangesTriggerRedownload() {
        val base = { size: Long, updated: String, exists: Boolean, length: Long ->
            SyncPolicy.shouldDownload(false, record(), size, updated, exists, length)
        }
        assertTrue("远端大小变化", base(101, "2026-09-01T10:00:00Z", true, 100))
        assertTrue("远端更新时间变化", base(100, "2026-09-02T10:00:00Z", true, 100))
        assertTrue("本地文件被删", base(100, "2026-09-01T10:00:00Z", false, 0))
        assertTrue("本地长度不符（上次不完整）", base(100, "2026-09-01T10:00:00Z", true, 42))
    }

    @Test
    fun remoteUpdatedAtMissingOnRecordDoesNotForceRedownload() {
        // 旧数据库记录没有 updated_at（迁移前），远端有：应视为变化并重下
        val legacy = record(updatedAt = "")
        assertTrue(
            SyncPolicy.shouldDownload(false, legacy, 100, "2026-09-01T10:00:00Z", true, 100)
        )
        // 远端没有该字段时不能用空值逼迫全量重下
        assertFalse(SyncPolicy.shouldDownload(false, record(), 100, "", true, 100))
    }

    @Test
    fun remoteMissingRequiresSuccessfulListing() {
        val existing = listOf(record().copy(fileId = 1), record().copy(fileId = 2))

        assertTrue(SyncPolicy.remoteMissingIds(existing, setOf(1L), listingSucceeded = false).isEmpty())
        assertEquals(
            listOf(2L),
            SyncPolicy.remoteMissingIds(existing, setOf(1L), listingSucceeded = true)
        )
    }

    @Test
    fun alreadyMissingRecordsAreNotRepeated() {
        val existing = listOf(record().copy(fileId = 2, status = SyncPolicy.STATUS_REMOTE_MISSING))
        assertTrue(SyncPolicy.remoteMissingIds(existing, emptySet(), listingSucceeded = true).isEmpty())
    }

    @Test
    fun skipRulesOnlyCoverCanvasSystemImages() {
        // v1.2.2：安装包/压缩包扩展名不再被跳过——课程资料归用户所有，
        // 旧的扩展名审查让这些文件永远不出现，用户只能看到「少文件」。
        assertFalse(SyncPolicy.shouldSkip("setup.exe"))
        assertFalse(SyncPolicy.shouldSkip("工具.APK"))
        assertFalse(SyncPolicy.shouldSkip("实验材料.7z"))
        assertFalse(SyncPolicy.shouldSkip("数据包.rar"))
        assertFalse(SyncPolicy.shouldSkip("工具.jar"))
        assertFalse(SyncPolicy.shouldSkip("讲义.pdf"))
        assertFalse(SyncPolicy.shouldSkip("数据.xlsx"))
        // Canvas 自己的课程封面图仍然跳过（非用户内容）
        assertTrue(SyncPolicy.shouldSkip("course_image.png"))
    }
}
