package edu.fudan.elearning.sync.network

import com.google.gson.annotations.SerializedName

/** Canvas 目录（用于重建课程内的本地子目录结构）。 */
data class CanvasFolder(
    val id: Long,
    val name: String = "",
    @SerializedName("parent_folder_id") val parentFolderId: Long? = null
)

/** Canvas 模块条目。 */
data class CanvasModuleItem(
    val id: Long = 0,
    val type: String = "",
    @SerializedName("content_id") val contentId: Long? = null,
    val title: String = ""
)

/** Canvas 模块（`include[]=items` 时 items 一并返回）。 */
data class CanvasModule(
    val id: Long,
    val name: String = "",
    val items: List<CanvasModuleItem> = emptyList(),
    @SerializedName("items_count") val itemsCount: Int = 0
)

/** Canvas 页面（列表接口不含正文，正文按需单独拉取）。 */
data class CanvasPage(
    val url: String = "",
    val title: String = "",
    val body: String? = null
)

/** Canvas 文件附件（作业/公告里的 attachments 元素）。 */
data class CanvasAttachment(
    val id: Long,
    @SerializedName("display_name") val displayName: String = "",
    val filename: String = "",
    val size: Long = 0,
    val url: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    @SerializedName("content-type") val contentType: String = ""
)

/** Canvas 作业。`due_at` 是 Canvas 返回的截止时间（ISO8601，可能为空）。 */
data class CanvasAssignment(
    val id: Long = 0,
    val name: String = "",
    val description: String? = null,
    val attachments: List<CanvasAttachment> = emptyList(),
    @SerializedName("due_at") val dueAt: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null
)

/** Canvas 公告。 */
data class CanvasAnnouncement(
    val id: Long = 0,
    val title: String = "",
    val message: String? = null,
    val attachments: List<CanvasAttachment> = emptyList()
)

/** 课程详情（只取 syllabus_body）。 */
data class CanvasCourseDetail(
    val id: Long = 0,
    @SerializedName("syllabus_body") val syllabusBody: String? = null
)

/**
 * 课程内容抓取所需的数据来源。
 *
 * 抽出接口是为了让 `CourseCrawler` 能在 JVM 单测里用假实现验证「目录路径重建、
 * 多来源去重、列表失败的安全闸门」等逻辑，不需要真实网络。
 */
interface CanvasDataSource {
    suspend fun getCourseFiles(courseId: Long): List<CanvasFile>
    suspend fun getFolders(courseId: Long): List<CanvasFolder>
    suspend fun getModules(courseId: Long): List<CanvasModule>
    suspend fun getModuleItems(courseId: Long, moduleId: Long): List<CanvasModuleItem>
    suspend fun getPages(courseId: Long): List<CanvasPage>
    suspend fun getPageBody(courseId: Long, pageUrl: String): String
    suspend fun getAssignments(courseId: Long): List<CanvasAssignment>
    suspend fun getAnnouncements(courseId: Long): List<CanvasAnnouncement>
    suspend fun getSyllabus(courseId: Long): String
    suspend fun getFile(courseId: Long, fileId: Long): CanvasFile?
}
