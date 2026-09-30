package edu.fudan.elearning.sync.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import edu.fudan.elearning.sync.search.SearchIndex

/** 数据仓库：课程/文件的增删改查与统计。 */
class Repo(context: Context) {
    private val db: DatabaseHelper = DatabaseHelper(context)

    // ---------- 课程 ----------
    fun upsertCourses(courses: List<Course>) {
        val writable = db.writableDatabase
        courses.forEach { course ->
            val values = ContentValues().apply {
                put("id", course.id)
                put("name", course.name)
                put("code", course.code)
                put("term", course.term)
            }
            // 用「行是否存在」判断，而不是 last_synced_at 是否为 NULL：
            // 课程首轮同步失败时该列为 NULL，旧逻辑会走 INSERT + CONFLICT_REPLACE，
            // REPLACE 会先删除旧行——files 表有 ON DELETE CASCADE 外键，
            // 已下载文件的数据库记录会被整课程级联清空。
            val exists = writable.rawQuery(
                "SELECT 1 FROM courses WHERE id=?", arrayOf(course.id.toString())
            ).use { c -> c.moveToFirst() }
            if (exists) {
                // 保留已有的 last_synced_at
                val existing = writable.rawQuery(
                    "SELECT last_synced_at FROM courses WHERE id=?", arrayOf(course.id.toString())
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                if (existing != null) {
                    values.put("last_synced_at", existing)
                }
                writable.update("courses", values, "id=?", arrayOf(course.id.toString()))
            } else {
                // 行不存在时 REPLACE 是安全的（无级联对象）
                writable.insertWithOnConflict("courses", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            }
        }
    }

    fun getAllCourses(): List<Course> {
        val list = mutableListOf<Course>()
        db.readableDatabase.rawQuery("SELECT * FROM courses ORDER BY name", null).use { c ->
            while (c.moveToNext()) list.add(c.toCourse())
        }
        return list
    }

    fun courseStats(): List<CourseStats> {
        val list = mutableListOf<CourseStats>()
        db.readableDatabase.rawQuery(
            """SELECT c.id, c.name, c.code, c.term, c.last_synced_at,
                      COUNT(f.file_id) AS total,
                      SUM(CASE WHEN f.status='downloaded' THEN 1 ELSE 0 END) AS done,
                      SUM(CASE WHEN f.status='downloaded' THEN COALESCE(f.size,0) ELSE 0 END) AS bytes
               FROM courses c LEFT JOIN files f ON f.course_id = c.id
               GROUP BY c.id, c.name, c.code, c.term, c.last_synced_at
               ORDER BY c.name""", null
        ).use { c ->
            while (c.moveToNext()) {
                val course = Course(
                    id = c.getLong(0), name = c.getString(1), code = c.getString(2),
                    term = c.getString(3), lastSyncedAt = c.getString(4)
                )
                list.add(
                    CourseStats(
                        course = course,
                        filesTotal = c.getInt(5),
                        filesDone = c.getInt(6),
                        bytesDownloaded = c.getLong(7)
                    )
                )
            }
        }
        return list
    }

    fun updateCourseLastSync(courseId: Long, time: String) {
        val values = ContentValues().apply { put("last_synced_at", time) }
        db.writableDatabase.update("courses", values, "id=?", arrayOf(courseId.toString()))
    }

    /** 课程名（用于按课程名重建本地目录）。 */
    fun courseName(courseId: Long): String {
        db.readableDatabase.rawQuery(
            "SELECT name FROM courses WHERE id=?", arrayOf(courseId.toString())
        ).use { c ->
            return if (c.moveToFirst()) c.getString(0) ?: "" else ""
        }
    }

    // ---------- 文件 ----------
    fun upsertFile(file: FileItem) {
        val values = ContentValues().apply {
            put("file_id", file.fileId)
            put("course_id", file.courseId)
            put("name", file.name)
            put("filename", file.filename)
            put("folder_path", file.folderPath)
            put("local_path", file.localPath)
            put("size", file.size)
            put("status", file.status)
            put("downloaded_at", file.downloadedAt)
            put("url", file.url)
            put("updated_at", file.updatedAt)
        }
        db.writableDatabase.insertWithOnConflict("files", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getFile(fileId: Long): FileItem? {
        db.readableDatabase.rawQuery("SELECT * FROM files WHERE file_id=?", arrayOf(fileId.toString())).use { c ->
            return if (c.moveToFirst()) c.toFile() else null
        }
    }

    fun getFilesByCourse(courseId: Long): List<FileItem> {
        val list = mutableListOf<FileItem>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM files WHERE course_id=? ORDER BY folder_path, filename",
            arrayOf(courseId.toString())
        ).use { c -> while (c.moveToNext()) list.add(c.toFile()) }
        return list
    }

    fun getAllDownloadedFiles(): List<FileItem> {
        val list = mutableListOf<FileItem>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM files WHERE status='downloaded' ORDER BY downloaded_at DESC", null
        ).use { c -> while (c.moveToNext()) list.add(c.toFile()) }
        return list
    }

    fun deleteFile(fileId: Long) {
        val dbw = db.writableDatabase
        dbw.beginTransaction()
        try {
            dbw.delete("files", "file_id=?", arrayOf(fileId.toString()))
            // 同步清掉搜索索引，避免搜到已经不存在的文件
            dbw.delete("files_fts", "file_id=?", arrayOf(fileId.toString()))
            dbw.setTransactionSuccessful()
        } finally {
            dbw.endTransaction()
        }
    }

    fun deleteFilesByCourse(courseId: Long) {
        val dbw = db.writableDatabase
        dbw.beginTransaction()
        try {
            // 必须先按课程清 FTS 再删 files 行：子查询读的是 files 表，
            // 先删行会让索引清理扑空（FTS 表没有 course_id 列）。
            dbw.execSQL(
                "DELETE FROM files_fts WHERE file_id IN " +
                    "(SELECT file_id FROM files WHERE course_id=?)",
                arrayOf(courseId)
            )
            dbw.delete("files", "course_id=?", arrayOf(courseId.toString()))
            dbw.setTransactionSuccessful()
        } finally {
            dbw.endTransaction()
        }
    }

    /**
     * 把本轮「课程文件列表成功但未出现」的远端文件标记为 remote_missing。
     *
     * 只改状态、**不删除本地文件**：调用方必须先用列表成功这一闸门筛选过
     * （见 `SyncPolicy.shouldMarkRemoteMissing`），网络/权限/解析失败时绝不能调用。
     */
    fun markRemoteMissing(fileIds: List<Long>): Int {
        if (fileIds.isEmpty()) return 0
        val dbw = db.writableDatabase
        var changed = 0
        dbw.beginTransaction()
        try {
            fileIds.forEach { id ->
                val values = ContentValues().apply { put("status", "remote_missing") }
                changed += dbw.update("files", values, "file_id=?", arrayOf(id.toString()))
            }
            dbw.setTransactionSuccessful()
        } finally {
            dbw.endTransaction()
        }
        return changed
    }

    /** 标记单个文件下载失败（保留既有 local_path 与断点，便于下次重试）。 */
    fun markFailed(fileId: Long) {
        val values = ContentValues().apply { put("status", "failed") }
        db.writableDatabase.update("files", values, "file_id=?", arrayOf(fileId.toString()))
    }

    /** 状态计数（用于同步结果与界面提示）。 */
    fun countByStatus(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        db.readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM files GROUP BY status", null
        ).use { c ->
            while (c.moveToNext()) out[c.getString(0) ?: ""] = c.getInt(1)
        }
        return out
    }

    // ---------- 全文搜索（v3 起） ----------
    // 设计与桌面端对齐：索引文本是 CJK 预分词后的串（见 SearchIndex）；
    // 搜索范围限已下载文件，排除 remote_missing。

    /** 写入/更新文件索引（覆盖语义：先删后插）。 */
    fun upsertFileIndex(fileId: Long, filename: String, text: String?) {
        val dbw = db.writableDatabase
        dbw.beginTransaction()
        try {
            dbw.delete("files_fts", "file_id=?", arrayOf(fileId.toString()))
            val values = ContentValues().apply {
                put("filename", SearchIndex.tokenizeForIndex(filename))
                put("content", SearchIndex.tokenizeForIndex(text.orEmpty()))
                put("file_id", fileId)
            }
            dbw.insertWithOnConflict("files_fts", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
            dbw.setTransactionSuccessful()
        } finally {
            dbw.endTransaction()
        }
    }

    fun removeFileIndex(fileId: Long) {
        db.writableDatabase.delete("files_fts", "file_id=?", arrayOf(fileId.toString()))
    }

    /** 已下载但尚未建立内容索引的文件（老库迁移与增量回填用）。 */
    fun unindexedDownloadedFiles(limit: Int = 100): List<FileItem> {
        val list = mutableListOf<FileItem>()
        db.readableDatabase.rawQuery(
            """SELECT f.* FROM files f
               WHERE f.status='downloaded' AND f.local_path != ''
                 AND f.file_id NOT IN (SELECT file_id FROM files_fts)
               ORDER BY f.downloaded_at DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) list.add(c.toFile()) }
        return list
    }

    /**
     * 跨课程搜索本地文件（文件名 + 内容）。
     *
     * FTS4 与桌面 FTS5 的差异：不支持前缀查询、没有 rank() 排序，
     * 这里按文件名排序；命中摘要用 snippet()。
     */
    fun searchFiles(query: String, limit: Int = 200): List<SearchResult> {
        val match = SearchIndex.buildMatchQuery(query) ?: return emptyList()
        val list = mutableListOf<SearchResult>()
        // FTS4 语法错误时不抛崩（调用方传入的是用户关键字，构造器已转义过）
        runCatching {
            db.readableDatabase.rawQuery(
                """SELECT f.file_id, f.course_id, f.name, f.filename, f.size,
                          f.status, f.local_path, c.name,
                          COALESCE(snippet(files_fts, '[', ']', '…', 1, 12), '')
                  FROM files_fts
                  JOIN files f ON f.file_id = files_fts.file_id
                  LEFT JOIN courses c ON c.id = f.course_id
                  WHERE files_fts MATCH ? AND f.status != 'remote_missing'
                  ORDER BY f.filename LIMIT ?""",
                arrayOf(match, limit.toString())
            ).use { c ->
                while (c.moveToNext()) {
                    list.add(
                        SearchResult(
                            fileId = c.getLong(0),
                            courseId = c.getLong(1),
                            name = c.getString(2) ?: "",
                            filename = c.getString(3) ?: "",
                            size = c.getLong(4),
                            status = c.getString(5) ?: "",
                            localPath = c.getString(6) ?: "",
                            courseName = c.getString(7) ?: "",
                            snippet = c.getString(8) ?: ""
                        )
                    )
                }
            }
        }
        return list
    }

    /** 文件名模糊匹配降级（FTS4 不可用的构建 / 语法异常时）。 */
    fun searchFilesByName(query: String, limit: Int = 200): List<SearchResult> {
        val terms = query.split(' ', '　').filter { it.isNotBlank() }
        if (terms.isEmpty()) return emptyList()
        val patterns = terms.joinToString("%") { escapeLike(it) }
        val list = mutableListOf<SearchResult>()
        db.readableDatabase.rawQuery(
            """SELECT f.file_id, f.course_id, f.name, f.filename, f.size,
                      f.status, f.local_path, c.name, ''
               FROM files f LEFT JOIN courses c ON c.id = f.course_id
               WHERE f.status != 'remote_missing'
                 AND (LOWER(f.name) LIKE ? OR LOWER(f.filename) LIKE ?)
               ORDER BY f.filename LIMIT ?""",
            arrayOf("%${patterns.lowercase()}%", "%${patterns.lowercase()}%", limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    SearchResult(
                        fileId = c.getLong(0), courseId = c.getLong(1),
                        name = c.getString(2) ?: "", filename = c.getString(3) ?: "",
                        size = c.getLong(4), status = c.getString(5) ?: "",
                        localPath = c.getString(6) ?: "", courseName = c.getString(7) ?: "",
                        snippet = ""
                    )
                )
            }
        }
        return list
    }

    private fun escapeLike(term: String): String =
        term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    // ---------- 作业（v3 起） ----------

    fun upsertAssignments(courseId: Long, assignments: List<Assignment>) {
        val dbw = db.writableDatabase
        dbw.beginTransaction()
        try {
            dbw.delete("assignments", "course_id=?", arrayOf(courseId.toString()))
            assignments.forEach { assignment ->
                val values = ContentValues().apply {
                    put("id", assignment.id)
                    put("course_id", courseId)
                    put("name", assignment.name)
                    put("due_at", assignment.dueAt)
                    put("html_url", assignment.htmlUrl)
                    put("fetched_at", "")
                }
                dbw.insertWithOnConflict(
                    "assignments", null, values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            dbw.setTransactionSuccessful()
        } finally {
            dbw.endTransaction()
        }
    }

    /** 按截止时间排序的作业（供待办视图；dueAt 非空优先）。 */
    fun assignments(limit: Int = 100): List<Assignment> {
        val list = mutableListOf<Assignment>()
        db.readableDatabase.rawQuery(
            """SELECT a.id, a.course_id, a.name, a.due_at, a.html_url
               FROM assignments a
               WHERE a.due_at != ''
               ORDER BY a.due_at ASC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    Assignment(
                        id = c.getLong(0), courseId = c.getLong(1),
                        name = c.getString(2) ?: "", dueAt = c.getString(3) ?: "",
                        htmlUrl = c.getString(4) ?: ""
                    )
                )
            }
        }
        return list
    }

    // ---------- 变更摘要（v3 起） ----------

    /** 记录一个文件级变更（new / updated / removed）。 */
    fun recordFileChange(runId: Long, file: FileItem, change: String) {
        val values = ContentValues().apply {
            put("run_id", runId)
            put("file_id", file.fileId)
            put("course_id", file.courseId)
            put("filename", file.name)
            put("change", change)
            put("occurred_at", file.downloadedAt ?: "")
        }
        db.writableDatabase.insert("sync_changes", null, values)
    }

    /** 最近的文件变更（通知与「最近变更」视图用；带课程名）。 */
    fun recentChanges(limit: Int = 50): List<FileChange> {
        val list = mutableListOf<FileChange>()
        db.readableDatabase.rawQuery(
            """SELECT s.id, s.run_id, s.file_id, s.course_id, s.filename, s.change,
                      s.occurred_at, c.name
               FROM sync_changes s LEFT JOIN courses c ON c.id = s.course_id
               ORDER BY s.id DESC LIMIT ?""",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                list.add(
                    FileChange(
                        id = c.getLong(0), runId = c.getLong(1), fileId = c.getLong(2),
                        courseId = c.getLong(3), filename = c.getString(4) ?: "",
                        change = c.getString(5) ?: "", occurredAt = c.getString(6) ?: "",
                        courseName = c.getString(7) ?: ""
                    )
                )
            }
        }
        return list
    }

    fun clearChangesBefore(runId: Long) {
        db.writableDatabase.delete("sync_changes", "run_id < ?", arrayOf(runId.toString()))
    }

    /** 变更摘要只保留最近 [keep] 条（无界增长没有意义，老记录会随同步轮次累积）。 */
    fun trimChanges(keep: Int = 500) {
        db.writableDatabase.execSQL(
            "DELETE FROM sync_changes WHERE id NOT IN " +
                "(SELECT id FROM sync_changes ORDER BY id DESC LIMIT ?)",
            arrayOf(keep.toString())
        )
    }

    // ---------- 同步记录 ----------
    fun recordRun(run: SyncRun): Long {
        val values = ContentValues().apply {
            put("started_at", run.startedAt)
            put("finished_at", run.finishedAt)
            put("mode", run.mode)
            put("files_downloaded", run.filesDownloaded)
            put("bytes_downloaded", run.bytesDownloaded)
            put("files_failed", run.filesFailed)
            put("error", run.error)
        }
        return db.writableDatabase.insert("sync_runs", null, values)
    }

    /** 最近一次同步记录（用于界面显示上次结果与失败原因）。 */
    fun lastRun(): SyncRun? {
        db.readableDatabase.rawQuery(
            "SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1", null
        ).use { c ->
            return if (c.moveToFirst()) {
                SyncRun(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    startedAt = c.getString(c.getColumnIndexOrThrow("started_at")),
                    finishedAt = c.getString(c.getColumnIndexOrThrow("finished_at")),
                    mode = c.getString(c.getColumnIndexOrThrow("mode")),
                    filesDownloaded = c.getInt(c.getColumnIndexOrThrow("files_downloaded")),
                    bytesDownloaded = c.getLong(c.getColumnIndexOrThrow("bytes_downloaded")),
                    filesFailed = runCatching {
                        c.getInt(c.getColumnIndexOrThrow("files_failed"))
                    }.getOrDefault(0),
                    error = runCatching {
                        c.getString(c.getColumnIndexOrThrow("error")) ?: ""
                    }.getOrDefault("")
                )
            } else null
        }
    }

    // ---------- 统计 ----------
    fun totalStats(): Triple<Int, Int, Long> {
        db.readableDatabase.rawQuery(
            """SELECT COUNT(*),
                      SUM(CASE WHEN status='downloaded' THEN 1 ELSE 0 END),
                      SUM(CASE WHEN status='downloaded' THEN COALESCE(size,0) ELSE 0 END)
               FROM files""", null
        ).use { c ->
            return if (c.moveToFirst())
                Triple(c.getInt(0), c.getInt(1), c.getLong(2))
            else Triple(0, 0, 0L)
        }
    }

    // ---------- 学期列表 ----------
    fun distinctTerms(): List<String> {
        val list = mutableListOf<String>()
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT term FROM courses WHERE term != '' ORDER BY term", null
        ).use { c -> while (c.moveToNext()) list.add(c.getString(0)) }
        return list
    }

    fun close() {
        db.close()
    }
}

private fun Cursor.toCourse(): Course = Course(
    id = getLong(getColumnIndexOrThrow("id")),
    name = getString(getColumnIndexOrThrow("name")),
    code = getString(getColumnIndexOrThrow("code")),
    term = getString(getColumnIndexOrThrow("term")),
    lastSyncedAt = getString(getColumnIndexOrThrow("last_synced_at"))
)

private fun Cursor.toFile(): FileItem = FileItem(
    fileId = getLong(getColumnIndexOrThrow("file_id")),
    courseId = getLong(getColumnIndexOrThrow("course_id")),
    name = getString(getColumnIndexOrThrow("name")),
    filename = getString(getColumnIndexOrThrow("filename")),
    folderPath = getString(getColumnIndexOrThrow("folder_path")),
    localPath = getString(getColumnIndexOrThrow("local_path")),
    size = getLong(getColumnIndexOrThrow("size")),
    status = getString(getColumnIndexOrThrow("status")),
    downloadedAt = getString(getColumnIndexOrThrow("downloaded_at")),
    url = getString(getColumnIndexOrThrow("url")),
    updatedAt = runCatching { getString(getColumnIndexOrThrow("updated_at")) }.getOrDefault("") ?: ""
)
