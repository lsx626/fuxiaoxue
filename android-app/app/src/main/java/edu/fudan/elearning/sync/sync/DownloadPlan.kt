package edu.fudan.elearning.sync.sync

import java.io.File

/**
 * 可靠下载的决策逻辑（纯函数，可在 JVM 单测里完整验证）。
 *
 * 规则与桌面端一致：
 * - 先写 `目标.part`，成功后**原子改名**为最终文件名；
 * - 已有 `.part` 时用 `Range` 续传；服务端忽略 Range 返回 200 时从头重写；
 * - 已知远端大小时必须校验完整长度，不完整就保留断点；
 * - 同名但不同 `file_id` 的文件必须避让成 `name (n).ext`，绝不静默覆盖；
 * - 下载被 302 到 UIS 登录页时（HTML 嗅探）必须报「会话失效」，而不是把登录页
 *   当成课程文件保存。
 */
object DownloadPlan {

    /** 同名避让的最大尝试次数，超过则退回带哈希后缀的名字，避免死循环。 */
    private const val MAX_AVOID_ATTEMPTS = 200

    /**
     * 把 Canvas 的相对目录路径清洗成安全的本地相对路径。
     *
     * 逐组件清洗：丢弃空段与 `.`/`..`，再用 [DownloadManager.sanitize] 去掉
     * 非法字符——远端目录名是不可信输入，绝不能让 `../../` 逃出课程目录。
     */
    fun safeRelativeDir(relative: String?): String {
        if (relative.isNullOrBlank()) return ""
        return relative.split('/', '\\')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { DownloadManager.sanitize(it) }
            .joinToString("/")
    }

    /**
     * 打开断点文件的写入流。
     *
     * **关键实现细节（曾出过真实缺陷）**：`File.outputStream()` 等价于
     * `FileOutputStream(file)`，打开时**会截断已有内容**；只有追加模式
     * `FileOutputStream(file, true)` 才能在续传时保留已下载的字节。
     * 续传分支（206 响应）必须传 `append = true`，否则会把已下载的头部
     * 截掉、只留下尾部片段，产生长度不符的损坏文件。
     */
    fun partOutputStream(part: File, append: Boolean): java.io.FileOutputStream =
        if (append) java.io.FileOutputStream(part, true) else part.outputStream()

    /** 断点文件路径：`report.pdf` -> `report.pdf.part`。 */
    fun partFile(dest: File): File = File(dest.parentFile, dest.name + PART_SUFFIX)

    fun isPartFile(file: File): Boolean = file.name.endsWith(PART_SUFFIX)

    /** 拆分主名与扩展名（保留点号）。`.gitignore` 这类无主名的名字整体视为主名。 */
    fun splitNameExt(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName to "" else fileName.substring(0, dot) to fileName.substring(dot)
    }

    fun numberedName(base: String, ext: String, index: Int): String = "$base ($index)$ext"

    /**
     * 选一个不与「数据库中其它文件」或「磁盘上已有文件」冲突的目标路径。
     *
     * [taken] 是同一目录下其它文件的文件名（大小写不敏感比较）。返回的路径保证
     * 目录内既没有同名数据库记录，也没有同名落盘文件。
     */
    fun uniqueDestination(dir: File, fileName: String, taken: Set<String>): File {
        val normalized = taken.map { it.lowercase() }.toHashSet()
        val (base, ext) = splitNameExt(fileName)
        var candidate = fileName
        var index = 1
        while (index <= MAX_AVOID_ATTEMPTS) {
            val occupied = candidate.lowercase() in normalized || File(dir, candidate).exists()
            if (!occupied) return File(dir, candidate)
            candidate = numberedName(base, ext, index)
            index += 1
        }
        // 极端情况（目录里几百个同名）：用时间戳保证唯一且稳定可读
        val stamp = System.currentTimeMillis()
        return File(dir, "$base ($stamp)$ext")
    }

    /** 已有断点时的 Range 头；没有断点返回 null（不带 Range）。 */
    fun rangeHeader(partLength: Long): String? =
        if (partLength > 0) "bytes=$partLength-" else null

    /**
     * 本次响应应写入断点文件还是从头覆盖。
     *
     * 206 = 服务端支持并返回了续传片段；200 = 服务端忽略了 Range（或本就没请求
     * 续传），必须从头写，否则会把旧字节留在文件开头造成内容错乱。
     */
    fun appendToPart(responseCode: Int, requestedRange: Boolean): Boolean =
        requestedRange && responseCode == 206

    /** 长度校验：远端大小已知（>0）且实际字节数不一致时为 true。 */
    fun lengthMismatch(expectedSize: Long, actualSize: Long): Boolean =
        expectedSize > 0 && actualSize != expectedSize

    /** 第 [attempt] 次失败后的退避：500ms、1s、2s…，封顶 30s。 */
    fun backoffMs(attempt: Int, baseMs: Long = 500, maxMs: Long = 30_000): Long {
        val shift = (maxOf(attempt, 1) - 1).coerceAtMost(20)
        return (baseMs shl shift).coerceAtMost(maxMs)
    }

    /**
     * 是否是登录页 HTML（会话失效的典型表现）。
     *
     * Canvas 的文件下载链接无会话时会 302 到 UIS 登录页并返回 200 + HTML，
     * 桌面端同样有这条防线；必须识别出来并报错，不能当作课程文件落盘。
     *
     * 判据是「HTML + 登录标记」两个条件同时成立：课程里合法的 `.html` 资料
     * 不会带 `登录/sign in/idp/authn` 这类标记，避免误判成登录页。
     */
    fun looksLikeLoginPage(contentType: String?, head: String): Boolean {
        if (head.isBlank()) return false
        val looksHtml = contentType?.contains("html", ignoreCase = true) == true ||
            head.startsWith("<!doctype html", ignoreCase = true) ||
            head.startsWith("<html", ignoreCase = true)
        if (!looksHtml) return false
        val lower = head.lowercase()
        return LOGIN_MARKERS.any { lower.contains(it) }
    }

    private val LOGIN_MARKERS = listOf(
        "登录", "sign in", "log in", "login", "idp/", "authn", "authcenter", "cas"
    )

    const val PART_SUFFIX = ".part"
}
