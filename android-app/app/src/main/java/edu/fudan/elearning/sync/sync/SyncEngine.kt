package edu.fudan.elearning.sync.sync

import android.content.Context
import edu.fudan.elearning.sync.data.Assignment
import edu.fudan.elearning.sync.data.Course
import edu.fudan.elearning.sync.data.FileItem
import edu.fudan.elearning.sync.data.Repo
import edu.fudan.elearning.sync.data.SyncRun
import edu.fudan.elearning.sync.network.ApiException
import edu.fudan.elearning.sync.network.CanvasApi
import edu.fudan.elearning.sync.search.SearchIndexer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 文件内容下载的并发上限（元数据请求仍然严格串行）。 */
private const val DOWNLOAD_CONCURRENCY = 3

/**
 * 同步结果。
 *
 * [error] 非空表示本轮没有正常完成：界面必须如实展示，绝不能显示成
 * 「同步完成，0 个文件」。[needsReauth] 表示会话失效，需要重新登录。
 */
data class SyncResult(
    val coursesCount: Int = 0,
    val filesTotal: Int = 0,
    val filesDownloaded: Int = 0,
    val bytesDownloaded: Long = 0,
    val filesFailed: Int = 0,
    val failedCourses: Int = 0,
    val remoteMissing: Int = 0,
    val error: String? = null,
    val needsReauth: Boolean = false,
    /** 失败是否值得重试（网络/限流/5xx）；鉴权与格式错误不应盲目重试。 */
    val retryable: Boolean = false
) {
    val ok: Boolean get() = error == null
}

/** 单课程同步体回写的计数（供主循环累加到总结果）。 */
private class CourseStats(
    var filesTotal: Int = 0,
    var filesDownloaded: Int = 0,
    var bytesDownloaded: Long = 0,
    var filesFailed: Int = 0,
    var remoteMissing: Int = 0
)

/**
 * 同步引擎：课程发现 → 文件增量比对 → 可靠下载 → 落库。
 *
 * 关键安全语义（与桌面端对齐）：
 * - 课程列表/文件列表请求失败会**显式报错**，不会被当成「远端没有文件」；
 * - 单个课程的文件列表失败只影响该课程（计入 `failedCourses`），其余课程继续，
 *   且该课程**不会**触发远端删除判定；
 * - 只有列表完整成功的课程才会把本轮未出现的 `file_id` 标记为 `remote_missing`，
 *   并且只改状态、不删除本地文件；
 * - 下载失败保留断点并写入 `failed` 状态，下次同步自动重试。
 */
class SyncEngine(
    private val context: Context,
    private val api: CanvasApi,
    private val repo: Repo
) {
    private val downloader = DownloadManager(context)
    private val prefs = edu.fudan.elearning.sync.util.Prefs(context)

    /** 一个待下载任务（先算好路径，再并发下载）。 */
    private data class DownloadTask(
        val ref: RemoteFileRef,
        val dest: File,
        val filename: String,
        val relativeDir: String,
        val displayName: String,
        val previousDownloadedAt: String?
    )

    /**
     * 下载单个文件；若因 **签名 URL 过期** 返回 404/403，则重新拉一次文件元数据
     * 换取新的签名 URL 再试一次。
     *
     * Canvas 的 `url` 字段带 verifier 且会过期；大课程同步到后半程时，
     * 最早抓到的 URL 已经失效——这正是「部分课程同步出现 404」的根因。
     */
    private suspend fun downloadWithFreshUrlIfNeeded(
        courseId: Long,
        task: DownloadTask
    ): DownloadOutcome {
        val first = downloader.download(task.ref.url, task.dest, task.ref.size)
        if (first !is DownloadOutcome.Failed) return first
        val urlExpired = first.reason.contains("404") || first.reason.contains("403")
        if (!urlExpired) return first
        val freshUrl = runCatching { api.getFile(courseId, task.ref.fileId)?.url }
            .getOrNull()?.takeIf { it.isNotEmpty() && it != task.ref.url }
            ?: return first
        return downloader.download(freshUrl, task.dest, task.ref.size)
    }

    /** 执行一次同步。返回结果统计；失败语义见 [SyncResult]。 */
    suspend fun sync(
        full: Boolean = false,
        onProgress: (phase: String, done: Int, total: Int, message: String, bytes: Long) -> Unit =
            { _, _, _, _, _ -> }
    ): SyncResult {
        val startedAt = now()
        onProgress("courses", 0, 0, "正在获取课程列表…", 0L)

        val courses = try {
            api.getCourses()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiException) {
            return record(startedAt, full, 0, 0, 0L, 0, error.toResult(), onProgress)
        }

        val validCourses = courses.filter { it.name.isNotBlank() }
        repo.upsertCourses(validCourses.map {
            Course(id = it.id, name = it.name, code = it.courseCode, term = it.term?.name ?: "")
        })
        onProgress(
            "courses", validCourses.size, validCourses.size,
            "共 ${validCourses.size} 门课程", 0L
        )

        var filesTotal = 0
        var filesDownloaded = 0
        var bytesDownloaded = 0L
        var filesFailed = 0
        var failedCourses = 0
        var remoteMissing = 0
        var fatal: ApiException? = null

        for ((index, canvasCourse) in validCourses.withIndex()) {
            if (fatal != null) break
            onProgress(
                "course", index + 1, validCourses.size,
                "同步课程：${canvasCourse.name}", 0L
            )

            // 抓取：文件主列表 + 目录树 + 模块 + 页面 + 作业 + 公告 + 大纲
            val outcome = try {
                CourseCrawler(
                    api = api,
                    onWarning = { warning ->
                        onProgress("course", index + 1, validCourses.size, warning, 0L)
                    },
                    // 已确认未启用的来源不再重复请求（404 既慢又会刷错误提示）
                    skipSources = prefs.disabledSources(canvasCourse.id),
                    onSourceUnavailable = { source -> prefs.markSourceDisabled(canvasCourse.id, source) }
                ).crawl(canvasCourse.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                // 鉴权失效是全局问题，立即停止；其余错误只影响本课程
                if (error is ApiException.Auth) {
                    fatal = error
                    break
                }
                failedCourses += 1
                onProgress(
                    "course", index + 1, validCourses.size,
                    "课程「${canvasCourse.name}」文件列表获取失败：${error.message}", 0L
                )
                continue
            }

            // 删除闸门：文件主列表没成功就只跳过删除判定，同时如实计入失败课程
            if (!outcome.filesListedOk) {
                failedCourses += 1
                onProgress(
                    "course", index + 1, validCourses.size,
                    "课程「${canvasCourse.name}」文件列表未取全，本轮不判定远端删除", 0L
                )
                continue
            }

            // 单课程的后续处理（路径分配、下载、落库）出现任何意外异常（磁盘满、
            // SQLite 锁等）只影响本课程，不得让整轮中止 —— 与列表失败同等处理。
            val stats = CourseStats()
            try {
                syncCourseBody(canvasCourse, outcome, full, stats, startedAt, onProgress)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: ApiException) {
                // 与抓取阶段同语义：鉴权失效是全局问题，立即停止整轮
                if (auth is ApiException.Auth) {
                    fatal = auth
                    break
                }
                failedCourses += 1
                onProgress(
                    "course", index + 1, validCourses.size,
                    "课程「${canvasCourse.name}」同步失败：${auth.message}", 0L
                )
                continue
            } catch (error: Throwable) {
                failedCourses += 1
                onProgress(
                    "course", index + 1, validCourses.size,
                    "课程「${canvasCourse.name}」同步失败：" +
                        "${error.message ?: error.javaClass.simpleName}", 0L
                )
                continue
            }

            filesTotal += stats.filesTotal
            filesDownloaded += stats.filesDownloaded
            bytesDownloaded += stats.bytesDownloaded
            filesFailed += stats.filesFailed
            remoteMissing += stats.remoteMissing
        }

        // 同步收尾：给本次没轮到下载的旧文件补内容索引（老库升级后分批完成）。
        // 失败与取消都不影响本轮同步结果。
        runCatching {
            SearchIndexer.backfill(context, repo) { message ->
                onProgress("index", 0, 0, message, 0L)
            }
        }

        val result = SyncResult(
            coursesCount = validCourses.size,
            filesTotal = filesTotal,
            filesDownloaded = filesDownloaded,
            bytesDownloaded = bytesDownloaded,
            filesFailed = filesFailed,
            failedCourses = failedCourses,
            remoteMissing = remoteMissing,
            error = fatal?.toResult()?.error,
            needsReauth = fatal is ApiException.Auth,
            retryable = fatal?.toResult()?.retryable == true
        )
        return record(
            startedAt, full, validCourses.size, filesTotal, bytesDownloaded, filesFailed,
            result, onProgress
        )
    }

    /**
     * 单课程的路径分配、并发下载与落库。
     *
     * 计数写入 [stats]（由调用方累加进总结果），保证异常路径下课程体一旦
     * 中途失败，已完成的增量不会重复计入。
     */
    private suspend fun syncCourseBody(
        canvasCourse: edu.fudan.elearning.sync.network.CanvasCourse,
        outcome: CrawlOutcome,
        full: Boolean,
        stats: CourseStats,
        startedAt: String,
        onProgress: (phase: String, done: Int, total: Int, message: String, bytes: Long) -> Unit
    ) {
        val files = outcome.files
        stats.filesTotal = files.size
        val listedIds = files.map { it.fileId }.toSet()
        val courseDir = downloader.courseDir(canvasCourse.name)
        val existingRecords = repo.getFilesByCourse(canvasCourse.id)
        // 同名只在**同一目录**内冲突，因此按目录维护已占用文件名
        val takenByDir = mutableMapOf<String, MutableSet<String>>()
        existingRecords.forEach { record ->
            val path = record.localPath.takeIf { it.isNotEmpty() } ?: return@forEach
            val local = File(path)
            val dirKey = local.parentFile?.absolutePath ?: courseDir.absolutePath
            takenByDir.getOrPut(dirKey) { mutableSetOf() }.add(local.name)
        }

        val tasks = mutableListOf<DownloadTask>()
        for (ref in files) {
            if (SyncPolicy.shouldSkip(remoteName(ref))) continue

            val existing = existingRecords.firstOrNull { it.fileId == ref.fileId }
            val localPath = existing?.localPath.orEmpty()
            val localFile = localPath.takeIf { it.isNotEmpty() }?.let { File(it) }
            val needs = SyncPolicy.shouldDownload(
                full = full,
                record = existing,
                remoteSize = ref.size,
                remoteUpdatedAt = ref.updatedAt,
                localExists = localFile?.exists() == true,
                localLength = localFile?.length() ?: 0L
            )
            if (!needs) continue

            val filename = DownloadManager.sanitize(
                ref.filename.ifEmpty {
                    ref.displayName.ifEmpty { ref.fileId.toString() }
                }
            )
            val relativeDir = DownloadPlan.safeRelativeDir(ref.folderPath)
            val targetDir = if (relativeDir.isEmpty()) courseDir else File(courseDir, relativeDir)
            // 已下载过的文件沿用原路径（覆盖自己的旧版本）；否则避让同名文件
            val taken = takenByDir.getOrPut(targetDir.absolutePath) { mutableSetOf() }
            val dest = localFile ?: downloader.destinationFor(targetDir, filename, taken)
            taken += dest.name
            // 纵深防御：远端目录名不可信，落盘路径必须仍在课程目录内
            if (!isInside(courseDir, dest)) {
                onProgress(
                    "file", stats.filesDownloaded, tasks.size,
                    "跳过越界路径：${dest.absolutePath}", stats.bytesDownloaded
                )
                continue
            }

            tasks += DownloadTask(
                ref = ref,
                dest = dest,
                filename = filename,
                relativeDir = relativeDir,
                displayName = ref.displayName.ifEmpty { filename },
                previousDownloadedAt = existing?.downloadedAt
            )
        }

        // 并发下载（含失败后刷新签名 URL 重试），DB 写入串行，避免多线程写 SQLite
        if (tasks.isNotEmpty()) {
            // 下载开始前先报一次总数，让界面立刻从「抓取列表」切到「下载 N 个文件」
            onProgress("file", 0, tasks.size, "开始下载 ${tasks.size} 个文件…", stats.bytesDownloaded)
            val gate = Semaphore(DOWNLOAD_CONCURRENCY)
            val outcomes = coroutineScope {
                tasks.map { task ->
                    async {
                        gate.withPermit {
                            task to downloadWithFreshUrlIfNeeded(canvasCourse.id, task)
                        }
                    }
                }.awaitAll()
            }
            for ((task, download) in outcomes) {
                when (download) {
                    is DownloadOutcome.Success -> {
                        stats.filesDownloaded += 1
                        stats.bytesDownloaded += download.bytes
                        val fileItem = FileItem(
                            fileId = task.ref.fileId,
                            courseId = canvasCourse.id,
                            name = task.displayName,
                            filename = task.filename,
                            folderPath = task.relativeDir,
                            localPath = download.path.absolutePath,
                            size = download.bytes,
                            status = SyncPolicy.STATUS_DOWNLOADED,
                            downloadedAt = now(),
                            url = task.ref.url,
                            updatedAt = task.ref.updatedAt
                        )
                        repo.upsertFile(fileItem)
                        // 即时建立搜索索引 + 记录变更摘要（失败只影响搜索，不影响同步）
                        SearchIndexer.indexFile(context, repo, fileItem)
                        runCatching {
                            repo.recordFileChange(
                                runId = 0, file = fileItem,
                                change = if (task.previousDownloadedAt.isNullOrEmpty()) "new" else "updated"
                            )
                        }
                        onProgress(
                            "file", stats.filesDownloaded, tasks.size,
                            "下载：${task.displayName}", stats.bytesDownloaded
                        )
                    }
                    is DownloadOutcome.Failed -> {
                        stats.filesFailed += 1
                        // 记录失败行，让列表能显示、下次同步能重试
                        repo.upsertFile(
                            FileItem(
                                fileId = task.ref.fileId,
                                courseId = canvasCourse.id,
                                name = task.displayName,
                                filename = task.filename,
                                folderPath = task.relativeDir,
                                localPath = task.dest.absolutePath,
                                size = task.ref.size,
                                status = SyncPolicy.STATUS_FAILED,
                                downloadedAt = task.previousDownloadedAt,
                                url = task.ref.url,
                                updatedAt = task.ref.updatedAt
                            )
                        )
                        onProgress(
                            "file", stats.filesDownloaded, tasks.size,
                            "失败：${task.displayName}（${download.reason}）",
                            stats.bytesDownloaded
                        )
                        if (!download.retryable && download.reason.contains("登录")) {
                            throw ApiException.Auth()
                        }
                    }
                }
            }
        }

        // 删除安全闸门：列表完整成功才允许判定远端删除，且只改状态不删本地文件
        val removed = SyncPolicy.remoteMissingIds(
            existingRecords, listedIds, listingSucceeded = outcome.filesListedOk
        )
        stats.remoteMissing = repo.markRemoteMissing(removed)
        // 变更摘要：被判定远端删除的文件记一笔（与新增/更新同一张表）
        if (removed.isNotEmpty()) {
            existingRecords.filter { it.fileId in removed }.forEach { item ->
                runCatching { repo.recordFileChange(0, item, "removed") }
            }
        }
        // 作业截止日期落库（采集失败不影响同步；正文归档仍仅桌面端做）
        runCatching {
            if (outcome.assignments.isNotEmpty()) {
                repo.upsertAssignments(
                    canvasCourse.id,
                    outcome.assignments.map {
                        Assignment(
                            id = it.id, courseId = canvasCourse.id, name = it.name,
                            dueAt = it.dueAt.orEmpty(), htmlUrl = it.htmlUrl.orEmpty()
                        )
                    }
                )
            }
        }
        // 放在课程体最后，与计数合并由调用方感知；失败时整个课程体回退为
        // failedCourses，不会出现「计数已累加但课程未标同步时间」的错配
        repo.updateCourseLastSync(canvasCourse.id, startedAt)
    }

    /** 写同步记录并给出最终进度文案；失败时如实说明，不显示「同步完成」。 */
    private fun record(
        startedAt: String,
        full: Boolean,
        coursesCount: Int,
        filesTotal: Int,
        bytes: Long,
        filesFailed: Int,
        result: SyncResult,
        onProgress: (String, Int, Int, String, Long) -> Unit
    ): SyncResult {
        runCatching {
            repo.recordRun(
                SyncRun(
                    startedAt = startedAt,
                    finishedAt = now(),
                    mode = if (full) "full" else "incremental",
                    filesDownloaded = result.filesDownloaded,
                    bytesDownloaded = result.bytesDownloaded,
                    filesFailed = filesFailed,
                    error = result.error ?: ""
                )
            )
            // 变更摘要表只保留最近 500 条，无界增长没有意义
            runCatching { repo.trimChanges(500) }
        }
        val message = when {
            !result.ok -> "同步失败：${result.error}"
            result.filesFailed > 0 -> "同步完成，${result.filesFailed} 个文件失败，可再次同步重试"
            else -> "同步完成"
        }
        onProgress("done", result.filesDownloaded, filesTotal, message, bytes)
        return result.copy(coursesCount = coursesCount)
    }

    private fun ApiException.toResult(): SyncResult = SyncResult(
        error = message ?: javaClass.simpleName,
        needsReauth = this is ApiException.Auth,
        retryable = when (this) {
            is ApiException.Network, is ApiException.RateLimited -> true
            is ApiException.Server -> code >= 500 || code == 408
            else -> false
        }
    )

    private fun remoteName(ref: RemoteFileRef): String =
        ref.filename.ifEmpty { ref.displayName }

    /** 目标路径必须仍在课程目录内（防止远端目录名造成目录穿越）。 */
    private fun isInside(courseDir: File, target: File): Boolean = runCatching {
        val root = courseDir.canonicalPath + File.separator
        target.canonicalPath.startsWith(root)
    }.getOrDefault(false)

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
