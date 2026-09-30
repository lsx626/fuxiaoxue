package edu.fudan.elearning.sync.sync

import edu.fudan.elearning.sync.network.CanvasDataSource
import edu.fudan.elearning.sync.network.ApiException
import edu.fudan.elearning.sync.network.CanvasFile
import edu.fudan.elearning.sync.network.CanvasFolder
import edu.fudan.elearning.sync.network.CanvasModule

/** 抓取到的一个远端文件（多来源合并后的权威记录）。 */
data class RemoteFileRef(
    val fileId: Long,
    val displayName: String,
    val filename: String,
    val size: Long,
    val url: String,
    val folderPath: String,
    val updatedAt: String,
    /** 发现来源，例如 `files`、`files+module`、`files+page`。 */
    val sources: String,
    val context: String = "",
    /** 教师已锁定：文件可见但 Canvas 不允许下载（v1.2.2）。 */
    val locked: Boolean = false
)

/**
 * 一次课程抓取的结果。
 *
 * [filesListedOk] 是删除安全闸门：**只有课程文件主列表完整成功**时才为真，
 * 也才允许把本轮未出现的 `file_id` 判定为远端删除。其他来源（模块/页面/作业/
 * 公告/大纲）失败只减少"额外发现"，不影响闸门。
 */
data class CrawlOutcome(
    val files: List<RemoteFileRef>,
    val filesListedOk: Boolean,
    val errors: List<String> = emptyList(),
    /** 作业（带截止日期；供待办视图，v3 起）。 */
    val assignments: List<edu.fudan.elearning.sync.network.CanvasAssignment> = emptyList()
)

/**
 * 课程内容抓取：文件主列表 + 目录树 + 模块 + 页面 + 作业 + 公告 + 大纲。
 *
 * 与桌面端 `Crawler.crawl_course()` 的顺序和语义一致：
 * 1. folders → `folder_id -> 相对路径`（递归解析父目录，带防环）；
 * 2. files → 课程文件主列表（权威路径 + 删除闸门）；
 * 3. modules → File 类型条目；
 * 4. pages / assignments / announcements / syllabus → 正文中的文件链接。
 *
 * 引用型文件（模块/正文里出现的 file_id）通过 `getFile()` 补完整元数据与签名 URL；
 * 同一个 file_id 只保留**第一次发现**的权威路径，其余来源只累加 `sources`。
 */
class CourseCrawler(
    private val api: CanvasDataSource,
    private val onWarning: (String) -> Unit = {},
    /** 已确认未启用的来源（Canvas 对关闭的标签页返回 404），直接跳过不再请求。 */
    private val skipSources: Set<String> = emptySet(),
    /** 首次发现某来源未启用时回调，调用方可持久化以便下次跳过。 */
    private val onSourceUnavailable: (String) -> Unit = {}
) {

    suspend fun crawl(courseId: Long): CrawlOutcome {
        val found = LinkedHashMap<Long, RemoteFileRef>()
        val errors = mutableListOf<String>()

        val folderPaths = try {
            folderPaths(api.getFolders(courseId))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onWarning("课程 $courseId 目录树获取失败：${error.message}")
            errors += "folders: ${error.message}"
            emptyMap()
        }

        // 1) 文件主列表：唯一的删除闸门
        var filesListedOk = false
        try {
            api.getCourseFiles(courseId).forEach { file ->
                addFile(found, file, courseId, folderPaths, SOURCE_FILES, "")
            }
            filesListedOk = true
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onWarning("课程 $courseId 文件列表获取失败：${error.message}（本轮不判定远端删除）")
            errors += "course_files: ${error.message}"
        }

        // 2) 模块
        runSource(errors, "modules") {
            api.getModules(courseId).forEach { module ->
                val items = if (module.items.isNotEmpty()) {
                    module.items
                } else if (module.itemsCount > 0) {
                    api.getModuleItems(courseId, module.id)
                } else {
                    emptyList()
                }
                items.filter { it.type.equals("File", ignoreCase = true) }.forEach { item ->
                    val fileId = item.contentId ?: return@forEach
                    addReferencedFile(
                        found, courseId, fileId, SOURCE_MODULE,
                        "模块: ${module.name} / ${item.title}"
                    )
                }
            }
        }

        // 3) 页面正文
        runSource(errors, "pages") {
            api.getPages(courseId).forEach { page ->
                val body = page.body ?: api.getPageBody(courseId, page.url)
                addReferenced(found, courseId, body, SOURCE_PAGE, "页面: ${page.title}")
            }
        }

        // 4) 作业（描述正文 + 附件 + 截止日期）
        val assignmentsList = mutableListOf<edu.fudan.elearning.sync.network.CanvasAssignment>()
        runSource(errors, "assignments") {
            api.getAssignments(courseId).forEach { assignment ->
                assignmentsList += assignment
                addReferenced(
                    found, courseId, assignment.description, SOURCE_ASSIGNMENT,
                    "作业: ${assignment.name}"
                )
                assignment.attachments.forEach { attachment ->
                    addAttachment(found, courseId, attachment.id, attachment.displayName,
                        attachment.filename, attachment.size, attachment.url,
                        attachment.updatedAt, SOURCE_ASSIGNMENT, "作业附件: ${assignment.name}")
                }
            }
        }

        // 5) 公告（正文 + 附件）
        runSource(errors, "announcements") {
            api.getAnnouncements(courseId).forEach { announcement ->
                addReferenced(
                    found, courseId, announcement.message, SOURCE_ANNOUNCEMENT,
                    "公告: ${announcement.title}"
                )
                announcement.attachments.forEach { attachment ->
                    addAttachment(found, courseId, attachment.id, attachment.displayName,
                        attachment.filename, attachment.size, attachment.url,
                        attachment.updatedAt, SOURCE_ANNOUNCEMENT,
                        "公告附件: ${announcement.title}")
                }
            }
        }

        // 6) 大纲
        runSource(errors, "syllabus") {
            addReferenced(found, courseId, api.getSyllabus(courseId), SOURCE_SYLLABUS, "课程大纲")
        }

        return CrawlOutcome(found.values.toList(), filesListedOk, errors, assignmentsList)
    }

    /**
     * `folder_id -> 相对路径`。
     *
     * 根目录（`parent_folder_id` 为空）映射为空字符串；子目录用 `/` 连接；
     * 用 seen 集合防环（异常数据里可能出现自引用）。
     */
    fun folderPaths(folders: List<CanvasFolder>): Map<Long, String> {
        val names = HashMap<Long, String>()
        val parents = HashMap<Long, Long?>()
        folders.forEach { folder ->
            names[folder.id] = folder.name
            parents[folder.id] = folder.parentFolderId
        }

        fun resolve(id: Long, seen: MutableSet<Long>): String {
            if (!seen.add(id)) return ""
            val name = names[id] ?: return ""
            val parentId = parents[id]
            // 根目录（Canvas 里就是 "course files"）不产生本地子目录
            if (parentId == null) return ""
            // 父目录信息缺失（分页不全/无权限）时保守处理：只用自己的名字
            if (parentId !in names) return name
            val parentPath = resolve(parentId, seen)
            return if (parentPath.isEmpty()) name else "$parentPath/$name"
        }

        return names.keys.associateWith { id -> resolve(id, mutableSetOf()) }
    }

    private suspend fun addReferenced(
        found: MutableMap<Long, RemoteFileRef>,
        courseId: Long,
        html: String?,
        source: String,
        context: String
    ) {
        HtmlFileLinks.extractFileIds(html).forEach { fileId ->
            addReferencedFile(found, courseId, fileId, source, context)
        }
    }

    /** 引用型文件：已在主列表/其它来源出现过就只累加来源，否则补元数据。 */
    private suspend fun addReferencedFile(
        found: MutableMap<Long, RemoteFileRef>,
        courseId: Long,
        fileId: Long,
        source: String,
        context: String
    ) {
        found[fileId]?.let { existing ->
            if (!existing.sources.contains(source)) {
                found[fileId] = existing.copy(
                    sources = "${existing.sources}+$source",
                    context = existing.context.ifEmpty { context }
                )
            }
            return
        }
        val file = try {
            api.getFile(courseId, fileId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onWarning("文件 $fileId 元数据获取失败：${error.message}")
            null
        } ?: return
        addFile(found, file, courseId, emptyMap(), source, context)
    }

    /** 附件对象本身已经带齐信息，不需要再补元数据。 */
    private fun addAttachment(
        found: MutableMap<Long, RemoteFileRef>,
        courseId: Long,
        fileId: Long,
        displayName: String,
        filename: String,
        size: Long,
        url: String,
        updatedAt: String,
        source: String,
        context: String
    ) {
        if (fileId <= 0) return
        val existing = found[fileId]
        if (existing != null) {
            if (!existing.sources.contains(source)) {
                found[fileId] = existing.copy(sources = "${existing.sources}+$source")
            }
            return
        }
        found[fileId] = RemoteFileRef(
            fileId = fileId,
            displayName = displayName.ifEmpty { filename.ifEmpty { "file_$fileId" } },
            filename = filename.ifEmpty { displayName },
            size = size,
            url = url,
            folderPath = "",
            updatedAt = updatedAt,
            sources = source,
            context = context
        )
    }

    private fun addFile(
        found: MutableMap<Long, RemoteFileRef>,
        file: CanvasFile,
        courseId: Long,
        folderPaths: Map<Long, String>,
        source: String,
        context: String
    ) {
        if (file.id <= 0) return
        val path = folderPaths[file.folderId].orEmpty()
        val existing = found[file.id]
        if (existing != null) {
            // 第一次发现保留权威路径；后续来源只累加来源信息
            val mergedSource = if (existing.sources.contains(source)) {
                existing.sources
            } else {
                "${existing.sources}+$source"
            }
            found[file.id] = existing.copy(
                sources = mergedSource,
                context = existing.context.ifEmpty { context },
                // 任一来源显示锁定即锁定
                locked = existing.locked || file.isLocked
            )
            return
        }
        found[file.id] = RemoteFileRef(
            fileId = file.id,
            displayName = file.displayName.ifEmpty { file.filename.ifEmpty { "file_${file.id}" } },
            filename = file.filename.ifEmpty { file.displayName },
            size = file.size,
            url = file.url,
            folderPath = path,
            updatedAt = file.updatedAt,
            sources = source,
            context = context,
            locked = file.isLocked
        )
    }

    /** 单个来源失败只记录，不影响其它来源与删除闸门。 */
    private suspend fun runSource(
        errors: MutableList<String>,
        name: String,
        block: suspend () -> Unit
    ) {
        if (name in skipSources) return
        try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (notFound: ApiException.Server) {
            if (notFound.code == 404) {
                // 课程根本没启用这个功能（如关闭了「页面」/「作业」标签）：
                // 不当作错误提示给用户，只记住下次跳过
                onSourceUnavailable(name)
            } else {
                onWarning("课程来源 $name 获取失败：${notFound.message}")
                errors += "$name: ${notFound.message}"
            }
        } catch (error: Exception) {
            onWarning("课程来源 $name 获取失败：${error.message}")
            errors += "$name: ${error.message}"
        }
    }

    companion object {
        const val SOURCE_FILES = "files"
        const val SOURCE_MODULE = "module"
        const val SOURCE_PAGE = "page"
        const val SOURCE_ASSIGNMENT = "assignment"
        const val SOURCE_ANNOUNCEMENT = "announcement"
        const val SOURCE_SYLLABUS = "syllabus"
    }
}
