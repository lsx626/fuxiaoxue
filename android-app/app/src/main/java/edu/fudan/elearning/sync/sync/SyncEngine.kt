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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val DOWNLOAD_CONCURRENCY = 3

data class SyncResult(
    val coursesCount: Int = 0,
    val filesTotal: Int = 0,
    val filesDownloaded: Int = 0,
    val bytesDownloaded: Long = 0,
    val filesFailed: Int = 0,
    val failedCourses: Int = 0,
    val filesRemoved: Int = 0,
    val error: String? = null,
    val needsReauth: Boolean = false,
    val retryable: Boolean = false,
) {
    val ok: Boolean
        get() = error == null
}

private class CourseStats(
    var filesTotal: Int = 0,
    var filesDownloaded: Int = 0,
    var bytesDownloaded: Long = 0,
    var filesFailed: Int = 0,
    var filesRemoved: Int = 0,
)

class SyncEngine(private val context: Context, private val api: CanvasApi, private val repo: Repo) {
    private val downloader = DownloadManager(context)
    private val prefs = edu.fudan.elearning.sync.util.Prefs(context)

    private data class DownloadTask(
        val remote: RemoteFileRef,
        val destination: File,
        val filename: String,
        val relativeDirectory: String,
        val displayName: String,
        val previousDownloadedAt: String?,
    )

    private suspend fun downloadWithRenewedUrl(
        courseId: Long,
        task: DownloadTask,
    ): DownloadOutcome {
        val initialDownload =
            downloader.download(task.remote.url, task.destination, task.remote.size)
        if (initialDownload !is DownloadOutcome.Failed) return initialDownload
        val urlExpired =
            initialDownload.reason.contains("404") || initialDownload.reason.contains("403")
        if (!urlExpired) return initialDownload
        val freshUrl =
            try {
                api.getFile(courseId, task.remote.fileId)?.url
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: ApiException.Auth) {
                throw auth
            } catch (_: Exception) {
                null
            }
        val renewed =
            freshUrl?.takeIf { it.isNotEmpty() && it != task.remote.url } ?: return initialDownload
        return downloader.download(renewed, task.destination, task.remote.size)
    }

    suspend fun sync(
        full: Boolean = false,
        onProgress: (phase: String, done: Int, total: Int, message: String, bytes: Long) -> Unit =
            { _, _, _, _, _ ->
            },
    ): SyncResult {
        val startedAt = now()
        onProgress("courses", 0, 0, "正在获取课程列表…", 0L)

        val courses =
            try {
                api.getCourses()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: ApiException) {
                return finishSync(startedAt, full, 0, 0, 0L, 0, error.toResult(), onProgress)
            }

        val validCourses = courses.filter { it.name.isNotBlank() }
        repo.upsertCourses(
            validCourses.map {
                Course(id = it.id, name = it.name, code = it.courseCode, term = it.term?.name ?: "")
            }
        )
        onProgress(
            "courses",
            validCourses.size,
            validCourses.size,
            "共 ${validCourses.size} 门课程",
            0L,
        )

        var filesTotal = 0
        var filesDownloaded = 0
        var bytesDownloaded = 0L
        var filesFailed = 0
        var failedCourses = 0
        var filesRemoved = 0
        var fatal: ApiException? = null
        val selectedCourseIds = repo.selectedCourseIds()
        val selectedFilesByCourse =
            repo
                .selectedFiles()
                .filter { it.courseId !in selectedCourseIds }
                .groupBy { it.courseId }
                .mapValues { (_, files) -> files.map { it.fileId }.toSet() }

        for ((index, canvasCourse) in validCourses.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (fatal != null) break
            onProgress("course", index + 1, validCourses.size, "同步课程：${canvasCourse.name}", 0L)

            val outcome =
                try {
                    CourseCrawler(
                            api = api,
                            onWarning = { warning ->
                                onProgress("course", index + 1, validCourses.size, warning, 0L)
                            },
                            skipSources = prefs.disabledSources(canvasCourse.id),
                            onSourceUnavailable = { source ->
                                prefs.markSourceDisabled(canvasCourse.id, source)
                            },
                        )
                        .crawl(canvasCourse.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ApiException) {
                    if (error is ApiException.Auth) {
                        fatal = error
                        break
                    }
                    failedCourses += 1
                    onProgress(
                        "course",
                        index + 1,
                        validCourses.size,
                        "课程「${canvasCourse.name}」文件列表获取失败：${error.message}",
                        0L,
                    )
                    continue
                }

            if (!outcome.filesListedOk) {
                failedCourses += 1
                onProgress(
                    "course",
                    index + 1,
                    validCourses.size,
                    "课程「${canvasCourse.name}」文件列表未取全，本轮不判定远端删除",
                    0L,
                )
                continue
            }
            val sourcesIncomplete = outcome.errors.isNotEmpty()

            val stats = CourseStats()
            try {
                val courseSelected = canvasCourse.id in selectedCourseIds
                val selectedFileIds = selectedFilesByCourse[canvasCourse.id].orEmpty()
                syncCourseBody(
                    canvasCourse,
                    outcome,
                    full,
                    courseSelected,
                    selectedFileIds,
                    stats,
                    startedAt,
                ) { phase, done, total, message, bytes ->
                    onProgress(phase, done, total, message, bytesDownloaded + bytes)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (auth: ApiException) {
                if (auth is ApiException.Auth) {
                    fatal = auth
                    break
                }
                failedCourses += 1
                onProgress(
                    "course",
                    index + 1,
                    validCourses.size,
                    "课程「${canvasCourse.name}」同步失败：${auth.message}",
                    0L,
                )
                continue
            } catch (error: Throwable) {
                failedCourses += 1
                onProgress(
                    "course",
                    index + 1,
                    validCourses.size,
                    "课程「${canvasCourse.name}」同步失败：" +
                        "${error.message ?: error.javaClass.simpleName}",
                    0L,
                )
                continue
            } finally {
                filesTotal += stats.filesTotal
                filesDownloaded += stats.filesDownloaded
                bytesDownloaded += stats.bytesDownloaded
                filesFailed += stats.filesFailed
                filesRemoved += stats.filesRemoved
            }
            if (sourcesIncomplete) failedCourses += 1
        }

        try {
            SearchIndexer.backfill(context, repo) { message ->
                onProgress("index", 0, 0, message, 0L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {}

        val result =
            SyncResult(
                coursesCount = validCourses.size,
                filesTotal = filesTotal,
                filesDownloaded = filesDownloaded,
                bytesDownloaded = bytesDownloaded,
                filesFailed = filesFailed,
                failedCourses = failedCourses,
                filesRemoved = filesRemoved,
                error =
                    fatal?.toResult()?.error
                        ?: if (failedCourses > 0) "$failedCourses 门课程未完整同步，已有本地资料仍可阅读" else null,
                needsReauth = fatal is ApiException.Auth,
                retryable = fatal?.toResult()?.retryable == true || failedCourses > 0,
            )
        return finishSync(
            startedAt,
            full,
            validCourses.size,
            filesTotal,
            bytesDownloaded,
            filesFailed,
            result,
            onProgress,
        )
    }

    private suspend fun syncCourseBody(
        canvasCourse: edu.fudan.elearning.sync.network.CanvasCourse,
        outcome: CrawlOutcome,
        full: Boolean,
        courseSelected: Boolean,
        selectedFileIds: Set<Long>,
        stats: CourseStats,
        startedAt: String,
        onProgress: (phase: String, done: Int, total: Int, message: String, bytes: Long) -> Unit,
    ) {
        val files = outcome.files
        stats.filesTotal = files.size
        val listedFileIds = files.map { it.fileId }.toSet()
        val courseDirectory = downloader.courseDir(canvasCourse.name)
        val existingFiles = repo.getFilesByCourse(canvasCourse.id)
        val reservedNamesByDirectory = mutableMapOf<String, MutableSet<String>>()
        existingFiles.forEach { record ->
            val path = record.localPath.takeIf { it.isNotEmpty() } ?: return@forEach
            val local = File(path)
            val dirKey = local.parentFile?.absolutePath ?: courseDirectory.absolutePath
            reservedNamesByDirectory.getOrPut(dirKey) { mutableSetOf() }.add(local.name)
        }

        val tasks = mutableListOf<DownloadTask>()
        val lockedRefs = mutableListOf<RemoteFileRef>()
        for (remote in files) {
            if (SyncPolicy.shouldSkip(remoteName(remote))) continue

            if (remote.locked) {
                lockedRefs += remote
                continue
            }

            val existing = existingFiles.firstOrNull { it.fileId == remote.fileId }
            if (existing == null) {
                // 所有课程都要完整落库；未勾选课程的新文件只出现在列表，不自动下载。
                repo.upsertFile(
                    FileItem(
                        fileId = remote.fileId,
                        courseId = canvasCourse.id,
                        name = remote.displayName.ifEmpty { remote.filename },
                        filename = remote.filename,
                        folderPath = remote.folderPath,
                        size = remote.size,
                        status = SyncPolicy.STATUS_PENDING,
                        url = remote.url,
                        updatedAt = remote.updatedAt,
                    )
                )
            }
            if (!SyncPolicy.fileInSyncScope(courseSelected, selectedFileIds, remote.fileId))
                continue

            val localPath = existing?.localPath.orEmpty()
            val localFile = localPath.takeIf { it.isNotEmpty() }?.let { File(it) }
            val needs =
                SyncPolicy.shouldDownload(
                    full = full,
                    record = existing,
                    remoteSize = remote.size,
                    remoteUpdatedAt = remote.updatedAt,
                    localExists = localFile?.exists() == true,
                    localLength = localFile?.length() ?: 0L,
                )
            if (!needs) continue

            val filename =
                DownloadManager.sanitize(
                    remote.filename.ifEmpty {
                        remote.displayName.ifEmpty { remote.fileId.toString() }
                    }
                )
            val relativeDirectory = DownloadPlan.safeRelativeDir(remote.folderPath)
            val targetDir =
                if (relativeDirectory.isEmpty()) courseDirectory
                else File(courseDirectory, relativeDirectory)
            val taken = reservedNamesByDirectory.getOrPut(targetDir.absolutePath) { mutableSetOf() }
            val destination = localFile ?: downloader.destinationFor(targetDir, filename, taken)
            taken += destination.name
            if (!isInside(courseDirectory, destination)) {
                onProgress(
                    "file",
                    stats.filesDownloaded,
                    tasks.size,
                    "跳过越界路径：${destination.absolutePath}",
                    stats.bytesDownloaded,
                )
                continue
            }
            if (full || (existing != null && existing.updatedAt != remote.updatedAt)) {
                DownloadPlan.partFile(destination)
                    .takeIf { it.exists() }
                    ?.let { check(it.delete()) { "无法清理旧版本断点" } }
            }

            tasks +=
                DownloadTask(
                    remote = remote,
                    destination = destination,
                    filename = filename,
                    relativeDirectory = relativeDirectory,
                    displayName = remote.displayName.ifEmpty { filename },
                    previousDownloadedAt = existing?.downloadedAt,
                )
        }

        if (tasks.isNotEmpty()) {
            onProgress("file", 0, tasks.size, "开始下载 ${tasks.size} 个文件…", stats.bytesDownloaded)
            var filesCompleted = 0
            processDownloads(
                tasks,
                DOWNLOAD_CONCURRENCY,
                download = { downloadWithRenewedUrl(canvasCourse.id, it) },
            ) { task, download ->
                currentCoroutineContext().ensureActive()
                filesCompleted += 1
                when (download) {
                    is DownloadOutcome.Success -> {
                        stats.filesDownloaded += 1
                        stats.bytesDownloaded += download.bytes
                        val fileItem =
                            FileItem(
                                fileId = task.remote.fileId,
                                courseId = canvasCourse.id,
                                name = task.displayName,
                                filename = task.filename,
                                folderPath = task.relativeDirectory,
                                localPath = download.path.absolutePath,
                                size = download.bytes,
                                status = SyncPolicy.STATUS_DOWNLOADED,
                                downloadedAt = now(),
                                url = task.remote.url,
                                updatedAt = task.remote.updatedAt,
                            )
                        repo.upsertFile(fileItem)
                        SearchIndexer.indexFile(context, repo, fileItem)
                        runCatching {
                            repo.recordFileChange(
                                runId = 0,
                                file = fileItem,
                                change =
                                    if (task.previousDownloadedAt.isNullOrEmpty()) "new"
                                    else "updated",
                            )
                        }
                        onProgress(
                            "file",
                            filesCompleted,
                            tasks.size,
                            "下载：${task.displayName}",
                            stats.bytesDownloaded,
                        )
                    }
                    is DownloadOutcome.Failed -> {
                        stats.filesFailed += 1
                        repo.upsertFile(
                            FileItem(
                                fileId = task.remote.fileId,
                                courseId = canvasCourse.id,
                                name = task.displayName,
                                filename = task.filename,
                                folderPath = task.relativeDirectory,
                                localPath = task.destination.absolutePath,
                                size = task.remote.size,
                                status = SyncPolicy.STATUS_FAILED,
                                downloadedAt = task.previousDownloadedAt,
                                url = task.remote.url,
                                updatedAt = task.remote.updatedAt,
                                error = download.reason,
                                selected =
                                    existingFiles
                                        .firstOrNull { it.fileId == task.remote.fileId }
                                        ?.selected == true,
                            )
                        )
                        onProgress(
                            "file",
                            filesCompleted,
                            tasks.size,
                            "失败：${task.displayName}（${download.reason}）",
                            stats.bytesDownloaded,
                        )
                        if (!download.retryable && download.reason.contains("登录")) {
                            throw ApiException.Auth()
                        }
                    }
                }
            }
        }

        for (remote in lockedRefs) {
            val previous = existingFiles.firstOrNull { it.fileId == remote.fileId }
            repo.upsertFile(
                FileItem(
                    fileId = remote.fileId,
                    courseId = canvasCourse.id,
                    name = remote.displayName,
                    filename = remote.filename,
                    folderPath = remote.folderPath,
                    localPath = previous?.localPath.orEmpty(),
                    size = remote.size,
                    status = SyncPolicy.STATUS_LOCKED,
                    downloadedAt = previous?.downloadedAt,
                    url = remote.url,
                    updatedAt = remote.updatedAt,
                    error = "教师已锁定，暂不开放下载",
                )
            )
        }

        val removedFileIds =
            SyncPolicy.removedIds(
                existingFiles,
                listedFileIds,
                listingSucceeded = outcome.filesListedOk,
            )
        val removableFiles =
            existingFiles
                .filter { it.fileId in removedFileIds }
                .filter { deleteManagedCopy(it, courseDirectory) }
        stats.filesRemoved = repo.deleteFiles(removableFiles.map { it.fileId })
        if (removableFiles.isNotEmpty()) {
            removableFiles.forEach { item ->
                runCatching { repo.recordFileChange(0, item, "removed") }
            }
        }
        runCatching {
            if (outcome.assignmentsListedOk) {
                repo.upsertAssignments(
                    canvasCourse.id,
                    outcome.assignments.map {
                        Assignment(
                            id = it.id,
                            courseId = canvasCourse.id,
                            name = it.name,
                            dueAt = it.dueAt.orEmpty(),
                            htmlUrl = it.htmlUrl.orEmpty(),
                        )
                    },
                )
            }
        }
        repo.updateCourseLastSync(canvasCourse.id, startedAt)
    }

    /**
     * 下载用户在列表里勾选的文件。
     *
     * 下载地址来自最近一次列表刷新；Canvas 的签名地址过期时复用 [downloadWithRenewedUrl] 换取新地址。 失败行保留勾选，成功行清除勾选，便于用户直接重试失败项。
     */
    suspend fun downloadSelected(
        fileIds: Set<Long>,
        onProgress: (phase: String, done: Int, total: Int, message: String, bytes: Long) -> Unit =
            { _, _, _, _, _ ->
            },
    ): SyncResult {
        val startedAt = now()
        val records =
            fileIds
                .mapNotNull { repo.getFile(it) }
                .filter {
                    it.status == SyncPolicy.STATUS_PENDING || it.status == SyncPolicy.STATUS_FAILED
                }
                .distinctBy { it.fileId }
        if (records.isEmpty()) return SyncResult()

        var downloaded = 0
        var failed = 0
        var bytes = 0L
        var completed = 0
        onProgress("file", 0, records.size, "开始下载 ${records.size} 个已选文件…", 0L)

        for ((courseId, courseRecords) in records.groupBy { it.courseId }) {
            currentCoroutineContext().ensureActive()
            val courseDirectory = downloader.courseDir(repo.courseName(courseId))
            val courseFiles = repo.getFilesByCourse(courseId)
            val reservedNamesByDirectory = mutableMapOf<String, MutableSet<String>>()
            courseFiles.forEach { record ->
                val path = record.localPath.takeIf { it.isNotBlank() } ?: return@forEach
                val local = File(path)
                if (!isInside(courseDirectory, local)) return@forEach
                val dirKey = local.parentFile?.absolutePath ?: courseDirectory.absolutePath
                reservedNamesByDirectory.getOrPut(dirKey) { mutableSetOf() }.add(local.name)
            }

            val tasks = mutableListOf<DownloadTask>()
            for (record in courseRecords) {
                if (record.url.isBlank()) {
                    failed += 1
                    completed += 1
                    repo.upsertFile(
                        record.copy(
                            status = SyncPolicy.STATUS_FAILED,
                            selected = true,
                            error = "缺少下载地址，请先刷新文件列表",
                        )
                    )
                    continue
                }
                val filename =
                    DownloadManager.sanitize(
                        record.filename.ifBlank { record.name.ifBlank { record.fileId.toString() } }
                    )
                val relativeDirectory = DownloadPlan.safeRelativeDir(record.folderPath)
                val targetDir =
                    if (relativeDirectory.isEmpty()) courseDirectory
                    else File(courseDirectory, relativeDirectory)
                val taken =
                    reservedNamesByDirectory.getOrPut(targetDir.absolutePath) { mutableSetOf() }
                val previous =
                    record.localPath
                        .takeIf { it.isNotBlank() }
                        ?.let(::File)
                        ?.takeIf { isInside(courseDirectory, it) }
                val destination = previous ?: downloader.destinationFor(targetDir, filename, taken)
                taken += destination.name
                if (!isInside(courseDirectory, destination)) {
                    failed += 1
                    completed += 1
                    repo.upsertFile(
                        record.copy(
                            status = SyncPolicy.STATUS_FAILED,
                            selected = true,
                            error = "文件路径越界，已拒绝写入",
                        )
                    )
                    continue
                }
                tasks +=
                    DownloadTask(
                        remote =
                            RemoteFileRef(
                                fileId = record.fileId,
                                displayName = record.name,
                                filename = record.filename,
                                size = record.size,
                                url = record.url,
                                folderPath = relativeDirectory,
                                updatedAt = record.updatedAt,
                                sources = "selected",
                            ),
                        destination = destination,
                        filename = filename,
                        relativeDirectory = relativeDirectory,
                        displayName = record.name,
                        previousDownloadedAt = record.downloadedAt,
                    )
            }

            processDownloads(
                tasks,
                DOWNLOAD_CONCURRENCY,
                download = { downloadWithRenewedUrl(courseId, it) },
            ) { task, result ->
                currentCoroutineContext().ensureActive()
                completed += 1
                val current = repo.getFile(task.remote.fileId) ?: return@processDownloads
                when (result) {
                    is DownloadOutcome.Success -> {
                        downloaded += 1
                        bytes += result.bytes
                        val updated =
                            current.copy(
                                localPath = result.path.absolutePath,
                                size = result.bytes,
                                status = SyncPolicy.STATUS_DOWNLOADED,
                                downloadedAt = now(),
                                error = "",
                                selected = false,
                                updatedAt = task.remote.updatedAt,
                            )
                        repo.upsertFile(updated)
                        SearchIndexer.indexFile(context, repo, updated)
                        runCatching {
                            repo.recordFileChange(
                                runId = 0,
                                file = updated,
                                change =
                                    if (task.previousDownloadedAt.isNullOrEmpty()) "new"
                                    else "updated",
                            )
                        }
                        onProgress("file", completed, records.size, "下载：${task.displayName}", bytes)
                    }
                    is DownloadOutcome.Failed -> {
                        failed += 1
                        repo.upsertFile(
                            current.copy(
                                localPath = task.destination.absolutePath,
                                status = SyncPolicy.STATUS_FAILED,
                                selected = true,
                                error = result.reason,
                            )
                        )
                        onProgress(
                            "file",
                            completed,
                            records.size,
                            "失败：${task.displayName}（${result.reason}）",
                            bytes,
                        )
                        if (!result.retryable && result.reason.contains("登录")) {
                            throw ApiException.Auth()
                        }
                    }
                }
            }
        }

        try {
            SearchIndexer.backfill(context, repo) { message ->
                onProgress("index", 0, 0, message, bytes)
            }
        } catch (_: Exception) {}

        val result =
            SyncResult(
                filesTotal = records.size,
                filesDownloaded = downloaded,
                bytesDownloaded = bytes,
                filesFailed = failed,
            )
        runCatching {
            repo.recordRun(
                SyncRun(
                    startedAt = startedAt,
                    finishedAt = now(),
                    mode = "selected",
                    filesDownloaded = downloaded,
                    bytesDownloaded = bytes,
                    filesFailed = failed,
                )
            )
        }
        onProgress(
            "done",
            downloaded,
            records.size,
            if (failed > 0) "下载完成，$failed 个文件需要重试" else "已选文件下载完成",
            bytes,
        )
        return result
    }

    private fun finishSync(
        startedAt: String,
        full: Boolean,
        coursesCount: Int,
        filesTotal: Int,
        bytes: Long,
        filesFailed: Int,
        result: SyncResult,
        onProgress: (String, Int, Int, String, Long) -> Unit,
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
                    error = result.error ?: "",
                )
            )
            runCatching { repo.trimChanges(500) }
        }
        val message =
            when {
                !result.ok -> "同步失败：${result.error}"
                result.filesFailed > 0 -> "同步完成，${result.filesFailed} 个文件失败，可再次同步重试"
                else -> "同步完成"
            }
        onProgress("done", result.filesDownloaded, filesTotal, message, bytes)
        return result.copy(coursesCount = coursesCount)
    }

    private fun ApiException.toResult(): SyncResult =
        SyncResult(
            error = message ?: javaClass.simpleName,
            needsReauth = this is ApiException.Auth,
            retryable =
                when (this) {
                    is ApiException.Network,
                    is ApiException.RateLimited -> true
                    is ApiException.Server -> code >= 500 || code == 408
                    else -> false
                },
        )

    private fun remoteName(remote: RemoteFileRef): String =
        remote.filename.ifEmpty { remote.displayName }

    private fun isInside(courseDirectory: File, target: File): Boolean =
        runCatching {
                val root = courseDirectory.canonicalPath + File.separator
                target.canonicalPath.startsWith(root)
            }
            .getOrDefault(false)

    private fun deleteManagedCopy(item: FileItem, courseDirectory: File): Boolean {
        if (item.localPath.isBlank()) return true
        val target = File(item.localPath)
        if (!isInside(courseDirectory, target)) return true
        return listOf(target, DownloadPlan.partFile(target)).all { file ->
            !file.exists() || runCatching { file.delete() }.getOrDefault(false)
        }
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
