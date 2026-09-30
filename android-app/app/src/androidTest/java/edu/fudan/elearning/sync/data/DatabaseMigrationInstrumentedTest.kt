package edu.fudan.elearning.sync.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 数据库 schema 的插桩测试（真机/模拟器）。
 *
 * 覆盖两个真实踩过的坑：
 * 1. **全新安装**：`onCreate` 建出的表必须包含 v2 新增列，否则首次同步会
 *    直接抛 `no such column: updated_at`（插桩测试实测到过这个崩溃）；
 * 2. **v1 -> v2 升级**：迁移必须是加列式的非破坏性迁移，用户既有数据不能丢。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {

    private lateinit var context: Context
    private lateinit var dbFile: java.io.File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("fudan_sync.db")
        dbFile.parentFile?.mkdirs()
        // 每个用例都从干净的库开始，避免相互影响
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

    private fun columns(db: SQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names.add(cursor.getString(nameIndex))
        }
        return names
    }

    @Test
    fun freshInstall_hasV2Schema() {
        val helper = DatabaseHelper(context)
        val db = helper.writableDatabase

        assertTrue(
            "files 表必须有 updated_at（否则首次同步会崩）",
            columns(db, "files").contains("updated_at")
        )
        val runColumns = columns(db, "sync_runs")
        assertTrue(runColumns.contains("files_failed"))
        assertTrue(runColumns.contains("error"))
        assertEquals(DatabaseHelper.SCHEMA_VERSION, db.version)
        // v4：阅读进度表必须在全新安装时就存在（onCreate 与 onUpgrade 同步维护）
        assertTrue(
            "reading_progress 必须在全新安装时建出",
            tables(db).contains("reading_progress")
        )
        helper.close()
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
    fun upgradeFromV1_addsColumnsAndKeepsUserData() {
        // 1) 手工造一个 v1 库（没有 updated_at / files_failed / error）
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
                url TEXT DEFAULT ''
            )"""
        )
        legacy.execSQL(
            """CREATE TABLE sync_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT NOT NULL, finished_at TEXT,
                mode TEXT DEFAULT 'incremental', files_downloaded INTEGER DEFAULT 0,
                bytes_downloaded INTEGER DEFAULT 0
            )"""
        )
        legacy.execSQL(
            "INSERT INTO courses (id, name, code, term) VALUES (1, '旧课程', 'C1', '2026春')"
        )
        legacy.execSQL(
            """INSERT INTO files (file_id, course_id, name, filename, size, status)
               VALUES (10, 1, '旧讲义.pdf', '旧讲义.pdf', 123, 'downloaded')"""
        )
        legacy.version = 1
        legacy.close()

        // 2) 用当前 helper 打开：触发 v1 -> v2 迁移
        val helper = DatabaseHelper(context)
        val db = helper.writableDatabase

        assertTrue(columns(db, "files").contains("updated_at"))
        val runColumns = columns(db, "sync_runs")
        assertTrue(runColumns.contains("files_failed"))
        assertTrue(runColumns.contains("error"))

        // 3) 用户数据必须原样保留
        db.rawQuery("SELECT name, status, size FROM files WHERE file_id=10", null).use { c ->
            assertTrue("旧文件记录必须保留", c.moveToFirst())
            assertEquals("旧讲义.pdf", c.getString(0))
            assertEquals("downloaded", c.getString(1))
            assertEquals(123L, c.getLong(2))
        }
        db.rawQuery("SELECT name FROM courses WHERE id=1", null).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("旧课程", c.getString(0))
        }

        // 4) 迁移后的库可以直接写入 v2 列（Repo 的真实写入路径）
        val repo = Repo(context)
        repo.upsertFile(
            FileItem(
                fileId = 11, courseId = 1, name = "新讲义.pdf", filename = "新讲义.pdf",
                folderPath = "/tmp", localPath = "/tmp/新讲义.pdf", size = 456,
                status = "downloaded", updatedAt = "2026-09-19T10:00:00Z"
            )
        )
        val saved = repo.getFile(11)
        assertEquals("2026-09-19T10:00:00Z", saved?.updatedAt)
        repo.close()
        helper.close()
    }

    @Test
    fun upgradeFromV3_addsReadingProgressAndKeepsData() {
        // 手工造一个 v3 库：v3 的三张表 + 用户数据
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
        legacy.execSQL("INSERT INTO courses (id, name, code, term) VALUES (1, '机器学习', 'CS229', '2025秋')")
        legacy.execSQL(
            """INSERT INTO files (file_id, course_id, name, filename, status, updated_at)
               VALUES (10, 1, '旧讲义.pdf', '旧讲义.pdf', 'downloaded', 't1')"""
        )
        legacy.version = 3
        legacy.close()

        // 用当前 helper 打开：触发 v3 -> v4 迁移
        val helper = DatabaseHelper(context)
        val db = helper.writableDatabase
        assertEquals(4, db.version)
        assertTrue(tables(db).contains("reading_progress"))

        // 用户数据原样保留
        db.rawQuery("SELECT name, status FROM files WHERE file_id=10", null).use { c ->
            assertTrue("旧文件记录必须保留", c.moveToFirst())
            assertEquals("旧讲义.pdf", c.getString(0))
        }

        // 迁移后可以直接读写进度（Repo 的真实路径）
        val repo = Repo(context)
        repo.setReadingProgress(10, 1, 7, 30, false)
        val prog = repo.getReadingProgress(10)
        assertEquals(7, prog?.position)
        assertEquals(30, prog?.total)
        assertEquals(false, prog?.isMedia)
        repo.close()
        helper.close()
    }

    @Test
    fun syncRunError_isPersistedAndReadable() {
        val helper = DatabaseHelper(context)
        val repo = Repo(context)
        repo.recordRun(
            SyncRun(
                startedAt = "2026-09-19 10:00:00",
                finishedAt = "2026-09-19 10:00:05",
                mode = "incremental",
                filesDownloaded = 2,
                bytesDownloaded = 2048,
                filesFailed = 1,
                error = "网络错误：timeout"
            )
        )

        val last = repo.lastRun()
        assertEquals(1, last?.filesFailed)
        assertEquals("网络错误：timeout", last?.error)
        assertEquals(2, last?.filesDownloaded)
        repo.close()
        helper.close()
    }
}
