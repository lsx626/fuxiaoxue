package edu.fudan.elearning.sync.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.fudan.elearning.sync.search.SearchIndexer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 全文搜索 / 作业截止日期 / 变更摘要的插桩测试（真机/模拟器）。
 *
 * 需要真机 SQLite：FTS4 虚拟表、snippet 函数与迁移都只在设备上能验证
 * （JVM 单测没有 Android 的 SQLite 实现）。
 */
@RunWith(AndroidJUnit4::class)
class SearchInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dbFile: java.io.File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("fudan_sync.db")
        dbFile.parentFile?.mkdirs()
        listOf("", "-wal", "-shm").forEach { suffix ->
            java.io.File(dbFile.path + suffix).delete()
        }
    }

    @After
    fun tearDown() {
        listOf("", "-wal", "-shm").forEach { suffix ->
            java.io.File(dbFile.path + suffix).delete()
        }
    }

    private fun tables(db: SQLiteDatabase): Set<String> {
        val names = mutableSetOf<String>()
        db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view')", null
        ).use { cursor ->
            while (cursor.moveToNext()) names.add(cursor.getString(0))
        }
        return names
    }

    @Test
    fun freshInstall_hasV3Tables() {
        val helper = DatabaseHelper(context)
        val db = helper.writableDatabase
        val all = tables(db)
        assertTrue("files_fts 必须在全新安装时建出", all.contains("files_fts"))
        assertTrue("assignments 必须在全新安装时建出", all.contains("assignments"))
        assertTrue("sync_changes 必须在全新安装时建出", all.contains("sync_changes"))
        assertEquals(DatabaseHelper.SCHEMA_VERSION, db.version)
        helper.close()
    }

    @Test
    fun upgradeFromV2_addsSearchTablesAndKeepsData() {
        // 手工造一个 v2 库（含 v2 新列），塞入用户数据
        val legacy = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        legacy.execSQL(
            """CREATE TABLE courses (
                id INTEGER PRIMARY KEY, name TEXT NOT NULL, code TEXT DEFAULT '',
                term TEXT DEFAULT '', last_synced_at TEXT
            )"""
        )
        legacy.execSQL(
            """CREATE TABLE files (
                file_id INTEGER PRIMARY KEY, course_id INTEGER NOT NULL, name TEXT NOT NULL,
                filename TEXT DEFAULT '', folder_path TEXT DEFAULT '', local_path TEXT DEFAULT '',
                size INTEGER DEFAULT 0, status TEXT DEFAULT 'pending', downloaded_at TEXT,
                url TEXT DEFAULT '', updated_at TEXT DEFAULT ''
            )"""
        )
        legacy.execSQL(
            "INSERT INTO courses (id, name, code, term) VALUES (1, '机器学习', 'CS229', '2025秋')"
        )
        legacy.execSQL(
            """INSERT INTO files (file_id, course_id, name, filename, size, status, updated_at)
               VALUES (10, 1, '旧讲义.pdf', '旧讲义.pdf', 123, 'downloaded', 't1')"""
        )
        legacy.version = 2
        legacy.close()

        // 用当前 helper 打开：触发 v2 -> v3 迁移
        val helper = DatabaseHelper(context)
        val db = helper.writableDatabase
        val all = tables(db)
        assertTrue(all.contains("files_fts"))
        assertTrue(all.contains("assignments"))
        assertTrue(all.contains("sync_changes"))

        // 用户数据原样保留
        db.rawQuery("SELECT name, status FROM files WHERE file_id=10", null).use { c ->
            assertTrue("旧文件记录必须保留", c.moveToFirst())
            assertEquals("旧讲义.pdf", c.getString(0))
            assertEquals("downloaded", c.getString(1))
        }

        // 迁移后可以直接走 Repo 的索引写入路径
        val repo = Repo(context)
        repo.upsertFileIndex(10, "旧讲义.pdf", "计算机体系结构")
        val hits = repo.searchFiles("计算机")
        assertEquals(1, hits.size)
        repo.close()
        helper.close()
    }

    @Test
    fun indexAndSearchCjkSubstring() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.upsertCourses(listOf(Course(id = 1, name = "机器学习导论", code = "CS229", term = "2025秋")))
        repo.upsertFile(
            FileItem(
                fileId = 100, courseId = 1, name = "lec1.txt", filename = "lec1.txt",
                localPath = "/tmp/lec1.txt", size = 30, status = "downloaded"
            )
        )
        repo.upsertFileIndex(100, "lec1.txt", "本节讲授支持向量机与核技巧")
        val hits = repo.searchFiles("支持向量机")
        assertEquals(1, hits.size)
        assertEquals(100L, hits[0].fileId)
        assertEquals("机器学习导论", hits[0].courseName)
        repo.close()
        helper.close()
    }

    @Test
    fun snippetMarksHits() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.upsertFile(
            FileItem(
                fileId = 101, courseId = 0, name = "hidden.txt", filename = "hidden.txt",
                localPath = "/tmp/hidden.txt", size = 30, status = "downloaded"
            )
        )
        repo.upsertFileIndex(101, "hidden.txt", "这里有一段关于神经网络的讨论")
        val hits = repo.searchFiles("神经网络")
        assertEquals(1, hits.size)
        assertTrue("摘要应标记命中片段", hits[0].snippet.contains("["))
        repo.close()
        helper.close()
    }

    @Test
    fun deleteCleansIndex() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.upsertFile(
            FileItem(
                fileId = 102, courseId = 0, name = "b.txt", filename = "b.txt",
                localPath = "/tmp/b.txt", size = 30, status = "downloaded"
            )
        )
        repo.upsertFileIndex(102, "b.txt", "离散数学图论")
        assertEquals(1, repo.searchFiles("图论").size)
        repo.deleteFile(102)
        assertEquals(0, repo.searchFiles("图论").size)
        repo.close()
        helper.close()
    }

    @Test
    fun remoteMissingExcluded() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.upsertFile(
            FileItem(
                fileId = 103, courseId = 0, name = "d.txt", filename = "d.txt",
                localPath = "/tmp/d.txt", size = 30, status = "remote_missing"
            )
        )
        repo.upsertFileIndex(103, "d.txt", "离散数学图论")
        assertFalse("远端已删除的文件不应出现在搜索结果", repo.searchFiles("图论").isNotEmpty())
        // 文件名降级搜索同样排除
        assertFalse(repo.searchFilesByName("d.txt").isNotEmpty())
        repo.close()
        helper.close()
    }

    @Test
    fun unlistedDownloadedAndBackfillIndexes() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        val textFile = java.io.File(context.cacheDir, "search_backfill_test.txt")
        textFile.writeText("密码学基础与公钥加密")

        repo.upsertFile(
            FileItem(
                fileId = 104, courseId = 0, name = textFile.name, filename = textFile.name,
                localPath = textFile.absolutePath, size = textFile.length(),
                status = "downloaded"
            )
        )
        // 升级前老库：文件已下载但尚未索引
        assertEquals(1, repo.unindexedDownloadedFiles(10).size)
        runBlocking { SearchIndexer.backfill(context, repo) }
        // 回填后内容可搜
        val hits = repo.searchFiles("公钥加密")
        assertEquals(1, hits.size)
        assertEquals(0, repo.unindexedDownloadedFiles(10).size)
        textFile.delete()
        repo.close()
        helper.close()
    }

    @Test
    fun assignmentsAreSortedByDueAt() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.upsertAssignments(
            1, listOf(
                Assignment(id = 1, courseId = 1, name = "作业一", dueAt = "2026-10-05T23:59:00Z"),
                Assignment(id = 2, courseId = 1, name = "作业二", dueAt = "2026-10-01T23:59:00Z")
            )
        )
        val list = repo.assignments()
        assertEquals(2, list.size)
        assertEquals("作业二", list[0].name) // 截止更早的排前面
        repo.close()
        helper.close()
    }

    @Test
    fun assignmentsCarryCourseName() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.upsertCourses(
            listOf(Course(id = 1, name = "机器学习导论 DATA130012.01", code = "DATA130012.01", term = "2026秋"))
        )
        repo.upsertAssignments(
            1, listOf(Assignment(id = 1, courseId = 1, name = "作业一", dueAt = "2026-10-05T23:59:00Z"))
        )
        val list = repo.assignments()
        assertEquals(1, list.size)
        // LEFT JOIN courses：界面「最近截止」栏要标注每项属于哪门课
        assertEquals("机器学习导论 DATA130012.01", list[0].courseName)
        repo.close()
        helper.close()
    }

    @Test
    fun visibleFilesAndStats_hideRemoteMissing() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.upsertCourses(listOf(Course(id = 1, name = "机器学习导论", code = "CS229", term = "2025秋")))
        repo.upsertFile(
            FileItem(fileId = 100, courseId = 1, name = "lec1.txt", filename = "lec1.txt",
                localPath = "/tmp/lec1.txt", size = 30, status = "downloaded")
        )
        repo.upsertFile(
            FileItem(fileId = 101, courseId = 1, name = "lec2.txt", filename = "lec2.txt",
                localPath = "/tmp/lec2.txt", size = 30, status = "remote_missing")
        )
        repo.upsertFile(
            FileItem(fileId = 102, courseId = 1, name = "lec3.txt", filename = "lec3.txt",
                localPath = "/tmp/lec3.txt", size = 30, status = "failed")
        )

        // 界面列表：远端已删除的不返回
        val visible = repo.getVisibleFilesByCourse(1)
        assertEquals(2, visible.size)
        assertFalse(visible.any { it.status == "remote_missing" })

        // 引擎增量判定仍需要 remote_missing 行，不能被隐藏掉
        assertEquals(3, repo.getFilesByCourse(1).size)

        // 课程统计与可见列表口径一致（ otherwise 「N 个文件」与列表行数对不上）
        val stats = repo.courseStats()
        assertEquals(1, stats.size)
        assertEquals(2, stats[0].filesTotal)
        assertEquals(1, stats[0].filesDone)
        repo.close()
        helper.close()
    }

    @Test
    fun changesRecordedAndTrimmed() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        val base = FileItem(fileId = 1, courseId = 1, name = "f.pdf", filename = "f.pdf")
        repo.recordFileChange(0, base.copy(downloadedAt = "t1"), "new")
        repo.recordFileChange(0, base.copy(downloadedAt = "t2"), "updated")
        repo.recordFileChange(0, base.copy(downloadedAt = "t3"), "removed")
        val recent = repo.recentChanges(10)
        assertEquals(3, recent.size)
        assertEquals("removed", recent.first().change) // 最新的在前
        repo.trimChanges(2)
        assertEquals(2, repo.recentChanges(10).size)
        repo.close()
        helper.close()
    }
}
