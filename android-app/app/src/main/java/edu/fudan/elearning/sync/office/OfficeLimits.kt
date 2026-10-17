package edu.fudan.elearning.sync.office

/**
 * Office 预览的资源上限与失败文案。
 *
 * 纯 Kotlin、不依赖 Android API，可在 JVM 单元测试里直接验证： 大文件/大页数时的降级策略、POI 记录上限的自适应取值，以及 「内存不足 /
 * 记录超限」与「文件损坏」两类失败的区分。
 */
object OfficeLimits {

    /** 超过该体积的文档降低渲染分辨率。 */
    const val BIG_FILE_BYTES: Long = 16L * 1024 * 1024

    /** 超过该页数的文档降低渲染分辨率。 */
    const val MAX_FULL_RES_PAGES: Int = 60

    /** 大文档的渲染缩放系数。 */
    const val BIG_DOC_SCALE: Float = 0.75f

    /** 内嵌图片超过该体积时，入库前先降采样。 */
    const val MAX_INLINE_IMAGE_BYTES: Int = 2 * 1024 * 1024

    /** 大文件允许的渲染宽度区间。 */
    const val MIN_BIG_FILE_WIDTH: Int = 1080
    const val MAX_BIG_FILE_WIDTH: Int = 1440

    /** POI 单记录字节数组上限区间：100 MiB（POI 默认）到 384 MiB。 */
    private const val MIN_POI_LIMIT: Long = 100L shl 20
    private const val MAX_POI_LIMIT: Long = 384L shl 20

    /** 兜底：解析失败且原因未知时的文案。 */
    private const val CORRUPT_HINT = "文档解析失败，文件可能已损坏"

    /**
     * 内存不足 / 超大内嵌记录的统一说明。
     *
     * POI 5.2.5 的单记录安全上限（默认 100,000,000 字节）在大文档含超大内嵌 媒体或 OLE 时会被触发；此时原始异常串对用户没有意义，必须换成可操作的说明。
     */
    const val MEMORY_HINT = "文件过大或含超大嵌入对象，当前设备内存不足以在应用内解析。可通过右上角分享用其他工具打开。"

    /**
     * POI 单记录字节数组上限：按设备堆大小自适应。
     *
     * 取堆的 1/4，并夹在 [MIN_POI_LIMIT, MAX_POI_LIMIT] 之间——放宽到 100 MiB 以上 才能打开含超大内嵌记录的文档，但不能超过 384
     * MiB，避免直接撞上 Android 的 堆上限而变成无法恢复的 OOM。
     */
    fun memoryAwareLimit(maxHeapBytes: Long = Runtime.getRuntime().maxMemory()): Long =
        (maxHeapBytes / 4).coerceIn(MIN_POI_LIMIT, MAX_POI_LIMIT)

    /** 大文件降到 1080–1440px 渲染宽度，控制单页位图内存。 */
    fun targetWidth(screenWidth: Int, fileBytes: Long): Int =
        if (fileBytes > BIG_FILE_BYTES) {
            screenWidth.coerceIn(MIN_BIG_FILE_WIDTH, MAX_BIG_FILE_WIDTH)
        } else {
            screenWidth
        }

    /** 页数过多的文档整体降分辨率；返回 1.0 表示不降。 */
    fun pageScale(pageCount: Int): Float = if (pageCount > MAX_FULL_RES_PAGES) BIG_DOC_SCALE else 1f

    /** 解析失败文案：区分内存/记录超限与普通损坏，绝不回显超长原始异常串。 */
    fun parseFailureMessage(error: Throwable): String {
        if (isMemoryOrRecordLimit(error)) return MEMORY_HINT
        val message = error.message
        if (message.isNullOrBlank()) return CORRUPT_HINT
        return "文档解析失败：" + message.take(MAX_MESSAGE_CHARS)
    }

    /** 是否为「内存不足 / POI 单记录超限」类失败。 */
    fun isMemoryOrRecordLimit(error: Throwable): Boolean {
        if (error is OutOfMemoryError) return true
        val className = error.javaClass.name
        if (className.contains("RecordFormatException")) return true
        if (className.contains("RecordInputStream")) return true
        val message = error.message ?: return false
        return message.contains("maximum length for the record type", ignoreCase = true) ||
            message.contains("Tried to allocate an array of length", ignoreCase = true) ||
            message.contains("MaxByteArraySize", ignoreCase = true) ||
            message.contains("Java heap space", ignoreCase = true) ||
            message.contains("out of memory", ignoreCase = true)
    }

    /** 原始异常串最多保留的字符数。 */
    private const val MAX_MESSAGE_CHARS = 200
}
