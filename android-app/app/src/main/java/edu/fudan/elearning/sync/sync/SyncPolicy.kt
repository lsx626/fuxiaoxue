package edu.fudan.elearning.sync.sync

import edu.fudan.elearning.sync.data.FileItem
import java.util.Locale

/**
 * 增量判定与删除安全闸门（纯逻辑，便于单测）。
 *
 * 两条硬性不变量：
 * 1. **下载判定**：只有文件缺失、状态不是 downloaded、大小/更新时间变化、
 *    或本地文件不存在/长度不符时才重下；全量同步则无条件重下。
 * 2. **删除判定**：只有「课程文件列表完整成功」时，才允许把本轮未出现的
 *    `file_id` 视为远端删除；任何 API 失败都不能导致标记或删除。
 */
object SyncPolicy {

    /** 是否需要下载该文件。 */
    fun shouldDownload(
        full: Boolean,
        record: FileItem?,
        remoteSize: Long,
        remoteUpdatedAt: String,
        localExists: Boolean,
        localLength: Long
    ): Boolean {
        if (full) return true
        if (record == null) return true
        if (record.status != STATUS_DOWNLOADED) return true
        if (record.size != remoteSize) return true
        if (remoteUpdatedAt.isNotEmpty() && record.updatedAt != remoteUpdatedAt) return true
        if (!localExists) return true
        // 已知远端大小且本地长度不符：说明上次落盘不完整
        if (remoteSize > 0 && localLength != remoteSize) return true
        return false
    }

    /** 只有列表完整成功时才允许标记远端删除。 */
    fun shouldMarkRemoteMissing(listingSucceeded: Boolean): Boolean = listingSucceeded

    /** 本轮列表里已出现的 file_id 中，哪些既有的本地记录需要标记为远端删除。 */
    fun remoteMissingIds(existing: List<FileItem>, listedIds: Set<Long>, listingSucceeded: Boolean): List<Long> {
        if (!shouldMarkRemoteMissing(listingSucceeded)) return emptyList()
        return existing.filter { it.fileId !in listedIds && it.status != STATUS_REMOTE_MISSING }
            .map { it.fileId }
    }

    /**
     * v1.2.2 起**不再**按扩展名静默跳过「安装包/压缩包」类文件。
     *
     * 课程资料归用户所有：老师的 `实验材料.7z`、`数据包.rar`、`工具.jar` 与
     * 普通文档没有本质区别，旧实现按扩展名审查（7z/rar/tar/jar/img/bin/iso…）
     * 让这些文件**永远不出现**在文件列表里，用户只能看到「同步完了但少文件」。
     * 现在忠实下载一切用户有权访问的文件；不需要的文件用户自行删除即可。
     * 保留的 `course_image` 目录跳过针对 Canvas 自己的课程封面图，不是用户内容。
     */
    /** 是否跳过 Canvas 系统封面图等非用户内容。 */
    fun shouldSkip(remoteName: String): Boolean {
        val lower = remoteName.lowercase(Locale.ROOT)
        if (lower.contains("course_image")) return true
        return false
    }

    const val STATUS_DOWNLOADED = "downloaded"
    const val STATUS_FAILED = "failed"
    const val STATUS_REMOTE_MISSING = "remote_missing"
    const val STATUS_PENDING = "pending"
    /** 教师已锁定：文件可见（在列表里标注），但 Canvas 不允许下载。 */
    const val STATUS_LOCKED = "locked"
}
