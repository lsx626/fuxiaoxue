package edu.fudan.elearning.sync.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 本地 SQLite 数据库：课程与文件状态。
 *
 * 迁移规则（硬性）：**发布后任何 schema 变更都必须是非破坏性迁移**。
 * `onUpgrade` 逐版本执行增量步骤，绝不 DROP 用户数据；只有从 v0
 * （异常/损坏状态）升级时才回退到重建。
 */
class DatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "fudan_sync.db", null, SCHEMA_VERSION) {

    init {
        // 开启 WAL：应用内有长生命周期的 UI 连接（AppViewModel）与每轮 Worker
        // 新建的连接并发读写；默认回滚日志下写会阻塞读，长事务可能抛
        // "database is locked"。与桌面端 StateStore（WAL）保持同一选择。
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE courses (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                code TEXT DEFAULT '',
                term TEXT DEFAULT '',
                last_synced_at TEXT
            )"""
        )
        db.execSQL(
            """CREATE TABLE files (
                file_id INTEGER PRIMARY KEY,
                course_id INTEGER NOT NULL,
                name TEXT NOT NULL,
                filename TEXT DEFAULT '',
                folder_path TEXT DEFAULT '',
                local_path TEXT DEFAULT '',
                size INTEGER DEFAULT 0,
                status TEXT DEFAULT 'pending',
                downloaded_at TEXT,
                url TEXT DEFAULT '',
                updated_at TEXT DEFAULT '',
                FOREIGN KEY(course_id) REFERENCES courses(id) ON DELETE CASCADE
            )"""
        )
        db.execSQL(
            """CREATE TABLE sync_runs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                started_at TEXT NOT NULL,
                finished_at TEXT,
                mode TEXT DEFAULT 'incremental',
                files_downloaded INTEGER DEFAULT 0,
                bytes_downloaded INTEGER DEFAULT 0,
                files_failed INTEGER DEFAULT 0,
                error TEXT DEFAULT ''
            )"""
        )
        // v3：全文索引、作业截止日期、文件级变更摘要。
        // onCreate 与 onUpgrade 必须同步维护（v1.0.10 的「全新安装首崩」教训）。
        createSearchTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            // v0 不是任何已发布形态，按全新库处理
            onCreate(db)
            return
        }
        if (oldVersion < 2) migrateV1ToV2(db)
        if (oldVersion < 3) migrateV2ToV3(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 不删除数据：降级安装（例如回滚 APK）时保留用户资料，缺失列由读取侧
        // 的默认值兜底，避免「装回旧版本就清空课程」。
    }

    /** v1 -> v2：补远端更新时间与同步失败信息（只加列，不动数据）。 */
    private fun migrateV1ToV2(db: SQLiteDatabase) {
        addColumnIfMissing(db, "files", "updated_at", "TEXT DEFAULT ''")
        addColumnIfMissing(db, "sync_runs", "files_failed", "INTEGER DEFAULT 0")
        addColumnIfMissing(db, "sync_runs", "error", "TEXT DEFAULT ''")
    }

    /**
     * v2 -> v3：新增搜索索引、作业截止日期与变更摘要表（全部是**新表**，
     * 不动任何旧列与旧数据）。老库的已下载文件需要由同步引擎回填索引
     * （见 SyncEngine.backfillSearchIndex）。
     */
    private fun migrateV2ToV3(db: SQLiteDatabase) {
        createSearchTables(db)
    }

    private fun createSearchTables(db: SQLiteDatabase) {
        // FTS4（而非 FTS5）：Android 各版本平台 SQLite 对 FTS5 支持不一致，
        // FTS4 自 API 11 起普遍可用。列存 CJK 预分词文本（见 SearchIndex）。
        runCatching {
            db.execSQL(
                """CREATE VIRTUAL TABLE IF NOT EXISTS files_fts USING fts4(
                    filename, content, file_id UNINDEXED
                )"""
            )
        }
        // 作业截止日期（v3）：不从 files 表分离，因为作业未必有附件文件
        runCatching {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS assignments (
                    id INTEGER PRIMARY KEY,
                    course_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
                    due_at TEXT DEFAULT '',
                    html_url TEXT DEFAULT '',
                    fetched_at TEXT DEFAULT ''
                )"""
            )
        }
        // 同步变更摘要（v3）：文件级 diff，通知与「最近变更」视图的来源
        runCatching {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS sync_changes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id INTEGER DEFAULT 0,
                    file_id INTEGER NOT NULL,
                    course_id INTEGER NOT NULL,
                    filename TEXT DEFAULT '',
                    change TEXT DEFAULT '',
                    occurred_at TEXT DEFAULT ''
                )"""
            )
        }
    }

    private fun addColumnIfMissing(db: SQLiteDatabase, table: String, column: String, spec: String) {
        if (hasColumn(db, table, column)) return
        runCatching { db.execSQL("ALTER TABLE $table ADD COLUMN $column $spec") }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        return runCatching {
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return true
                }
                false
            }
        }.getOrDefault(false)
    }

    companion object {
        /**
         * 当前 schema 版本。
         *
         * v2 新增 files.updated_at 与 sync_runs 的失败信息；
         * v3 新增 files_fts（搜索）、assignments（截止日期）、sync_changes（变更摘要），
         * 全部为**新增表**，老数据不动。
         */
        const val SCHEMA_VERSION = 3
    }
}
