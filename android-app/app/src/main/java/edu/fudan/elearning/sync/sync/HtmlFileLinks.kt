package edu.fudan.elearning.sync.sync

/**
 * 从 Canvas 正文 HTML 里提取**文件引用**（页面/作业/公告/大纲共用）。
 *
 * 与桌面端 `crawler.py` 的两条正则等价：
 * - `/courses/123/files/456[/download|/preview|?verifier=…]`（含 `/api/v1/` 前缀写法）；
 * - `/files/456/preview` 这类简写。
 *
 * 纯字符串处理，可在 JVM 单测里完整验证。
 */
object HtmlFileLinks {

    private val FILE_LINK_RE = Regex("""/(?:api/v1/)?courses/\d+/files/(\d+)(?:[/?"'\s>]|$)""")

    private val FILE_PREVIEW_RE = Regex("""/files/(\d+)/preview""")

    /** 正文里出现过的所有 file_id（去重、只保留正数）。 */
    fun extractFileIds(html: String?): Set<Long> {
        if (html.isNullOrBlank()) return emptySet()
        val ids = mutableSetOf<Long>()
        FILE_LINK_RE.findAll(html).forEach { match ->
            match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { ids.add(it) }
        }
        FILE_PREVIEW_RE.findAll(html).forEach { match ->
            match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }?.let { ids.add(it) }
        }
        return ids
    }
}
