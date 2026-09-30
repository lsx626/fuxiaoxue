package edu.fudan.elearning.sync.data

/** 课程数据模型 */
data class Course(
    val id: Long,
    val name: String,
    val code: String = "",
    val term: String = "",
    val lastSyncedAt: String? = null
)

/** 文件数据模型 */
data class FileItem(
    val fileId: Long,
    val courseId: Long,
    val name: String,
    val filename: String = "",
    val folderPath: String = "",
    val localPath: String = "",
    val size: Long = 0,
    val status: String = "pending", // pending / downloaded / failed / remote_missing / locked
    val downloadedAt: String? = null,
    val url: String = "",
    /** 远端 `updated_at`；增量同步据此判断「同大小但内容已更新」。 */
    val updatedAt: String = "",
    /** 失败原因（或锁定说明）；界面直接展示给用户排障（v1.2.2）。 */
    val error: String = ""
)

/** 同步运行记录 */
data class SyncRun(
    val id: Long = 0,
    val startedAt: String,
    val finishedAt: String? = null,
    val mode: String = "incremental",
    val filesDownloaded: Int = 0,
    val bytesDownloaded: Long = 0,
    val filesFailed: Int = 0,
    /** 失败原因（成功时为空）。用于历史记录与排障，不含凭据。 */
    val error: String = ""
)

/** 课程统计（列表页展示用） */
data class CourseStats(
    val course: Course,
    val filesTotal: Int,
    val filesDone: Int,
    val bytesDownloaded: Long
)

/** 搜索结果（跨课程的本地全文搜索，见 Repo.searchFiles） */
data class SearchResult(
    val fileId: Long,
    val courseId: Long,
    val name: String,
    val filename: String = "",
    val size: Long = 0,
    val status: String = "",
    val localPath: String = "",
    val courseName: String = "",
    /** FTS4 snippet 命中摘要（无命中时为空）。 */
    val snippet: String = ""
)

/** 作业（带截止日期；同步时从 Canvas assignments 采集） */
data class Assignment(
    val id: Long,
    val courseId: Long,
    val name: String,
    /** Canvas `due_at`（ISO8601 或空字符串）。 */
    val dueAt: String = "",
    val htmlUrl: String = "",
    /** 课程名（LEFT JOIN courses；课程记录缺失时显示兜底文案）。 */
    val courseName: String = ""
)

/** 文件级变更摘要（sync_changes 表的一行） */
data class FileChange(
    val id: Long = 0,
    val runId: Long = 0,
    val fileId: Long,
    val courseId: Long,
    val filename: String = "",
    /** new / updated / removed */
    val change: String = "",
    val occurredAt: String = "",
    /** 课程名（LEFT JOIN courses；课程记录缺失时为空）。 */
    val courseName: String = ""
)
