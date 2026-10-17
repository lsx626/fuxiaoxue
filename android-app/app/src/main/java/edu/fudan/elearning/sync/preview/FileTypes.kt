package edu.fudan.elearning.sync.preview

import java.io.File
import java.io.RandomAccessFile

/**
 * 统一预览类型识别：扩展名 + 魔数双重判定。
 *
 * 与桌面端 gui/previewer.py::_detect_type() 对齐：
 * - .ts 有双重含义（MPEG-TS 与 TypeScript），必须按 188 字节同步字节特征判断媒体。
 * - 无法高保真预览的格式（Office/ODF/旧二进制 Office）统一走 [PreviewKind.Structured] 降级， 绝不偷偷跳转外部应用。
 */
enum class PreviewKind {
    PDF,
    IMAGE,
    TEXT,
    MEDIA,
    OFFICE,
    STRUCTURED,
    UNSUPPORTED,
}

object FileTypes {

    /** 受支持的文本/代码扩展名（与桌面端 text 集合保持一致）。 */
    private val TEXT_EXTS =
        setOf(
            "txt",
            "md",
            "markdown",
            "rst",
            "log",
            "ini",
            "cfg",
            "conf",
            "properties",
            "json",
            "xml",
            "yaml",
            "yml",
            "toml",
            "csv",
            "tsv",
            "html",
            "htm",
            "xhtml",
            "css",
            "js",
            "jsx",
            "ts",
            "tsx",
            "py",
            "java",
            "kt",
            "kts",
            "c",
            "h",
            "cpp",
            "hpp",
            "cs",
            "go",
            "rs",
            "rb",
            "php",
            "sh",
            "bat",
            "ps1",
            "sql",
            "gitignore",
            "gradle",
            "groovy",
            "scala",
            "swift",
            "dart",
            "vue",
            "srt",
            "vtt",
        )

    private val IMAGE_EXTS =
        setOf(
            "png",
            "jpg",
            "jpeg",
            "gif",
            "bmp",
            "webp",
            "ico",
            "svg",
            "tif",
            "tiff",
            "avif",
            "heic",
            "heif",
        )

    private val MEDIA_EXTS =
        setOf(
            "mp4",
            "m4v",
            "mkv",
            "mov",
            "avi",
            "webm",
            "3gp",
            "mp3",
            "m4a",
            "aac",
            "flac",
            "ogg",
            "wav",
            "opus",
            "m3u8",
        )

    /** Office 文档：可逐页渲染（POI 解析 + Canvas 绘制）。 */
    private val OFFICE_EXTS = setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx")

    /** 结构化降级：ODF/压缩包等无法在应用内高保真渲染的格式。 */
    private val STRUCTURED_EXTS =
        setOf(
            "odt",
            "ods",
            "odp",
            "rtf",
            "epub",
            "pages",
            "numbers",
            "key",
            "zip",
            "jar",
            "rar",
            "7z",
            "gz",
            "tar",
            "bz2",
            "xz",
            "iso",
            "dmg",
            "apk",
            "exe",
            "msi",
        )

    private val PDF_EXTS = setOf("pdf")

    fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase().trim()

    /** 主入口：先按扩展名分类，再对歧义格式用魔数校正。 */
    fun detect(file: File): PreviewKind {
        if (!file.exists() || !file.isFile) return PreviewKind.UNSUPPORTED
        val ext = extOf(file.name)
        val kind =
            when {
                PDF_EXTS.contains(ext) -> PreviewKind.PDF
                IMAGE_EXTS.contains(ext) -> PreviewKind.IMAGE
                MEDIA_EXTS.contains(ext) -> PreviewKind.MEDIA
                OFFICE_EXTS.contains(ext) -> PreviewKind.OFFICE
                TEXT_EXTS.contains(ext) -> PreviewKind.TEXT
                STRUCTURED_EXTS.contains(ext) -> PreviewKind.STRUCTURED
                else -> null
            } ?: return sniff(file) ?: PreviewKind.UNSUPPORTED

        // .ts 双重含义：必须是 MPEG-TS 才按媒体处理，否则按文本。
        return if (ext == "ts") {
            if (isMpegTs(file)) PreviewKind.MEDIA else PreviewKind.TEXT
        } else {
            kind
        }
    }

    /** 无扩展名或未知扩展名时按文件头嗅探。 */
    private fun sniff(file: File): PreviewKind? {
        return when {
            isPdf(file) -> PreviewKind.PDF
            isImageMagic(file) -> PreviewKind.IMAGE
            isMpegTs(file) || isMp4(file) || isMp3(file) -> PreviewKind.MEDIA
            looksLikeText(file) -> PreviewKind.TEXT
            else -> null
        }
    }

    fun isPdf(file: File): Boolean =
        matchesPrefix(file, byteArrayOf(0x25, 0x50, 0x44, 0x46)) // %PDF

    fun isMp4(file: File): Boolean {
        val head = readHead(file, 12) ?: return false
        // ftyp box 在 4..8 偏移
        return head.size >= 12 &&
            head[4] == 0x66.toByte() &&
            head[5] == 0x74.toByte() &&
            head[6] == 0x79.toByte() &&
            head[7] == 0x70.toByte()
    }

    fun isMp3(file: File): Boolean {
        val head = readHead(file, 3) ?: return false
        if (head.size < 3) return false
        // ID3 tag 或帧同步 0xFFEx/0xFFFx
        return (head[0] == 0x49.toByte() && head[1] == 0x44.toByte() && head[2] == 0x33.toByte()) ||
            (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
    }

    /** PNG/JPEG/GIF/WebP/BMP/AVIF/HEIC 等常见文件头。 */
    fun isImageMagic(file: File): Boolean {
        val head = readHead(file, 16) ?: return false
        if (head.size < 4) return false
        return when {
            head[0] == 0x89.toByte() &&
                head[1] == 0x50.toByte() &&
                head[2] == 0x4E.toByte() &&
                head[3] == 0x47.toByte() -> true // PNG
            head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> true // JPEG
            head[0] == 0x47.toByte() && head[1] == 0x49.toByte() && head[2] == 0x46.toByte() ->
                true // GIF
            head[0] == 0x42.toByte() && head[1] == 0x4D.toByte() -> true // BMP
            head.size >= 12 &&
                head[0] == 0x52.toByte() &&
                head[1] == 0x49.toByte() &&
                head[2] == 0x46.toByte() &&
                head[3] == 0x46.toByte() &&
                head[8] == 0x57.toByte() &&
                head[9] == 0x45.toByte() &&
                head[10] == 0x42.toByte() &&
                head[11] == 0x50.toByte() -> true // WebP
            head.size >= 12 &&
                head[4] == 0x66.toByte() &&
                head[5] == 0x74.toByte() &&
                head[6] == 0x79.toByte() &&
                head[7] == 0x70.toByte() &&
                String(head, 8, 4, Charsets.US_ASCII) in
                    setOf("avif", "heic", "heix", "mif1", "msf1") -> true // AVIF/HEIC
            else -> false
        }
    }

    /** MPEG-TS 判定：188 字节定长包，每包首字节为 0x47 同步字节。 与桌面端保持一致，避免把 TypeScript 的 .ts 误判为媒体。 */
    fun isMpegTs(file: File): Boolean {
        if (file.length() < 188L) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val first = ByteArray(188)
                raf.seek(0)
                if (raf.read(first) != 188) return false
                if (first[0] != 0x47.toByte()) return false
                // 校验第 2、3 个包的同步字节，排除偶然命中
                val buf = ByteArray(188)
                repeat(2) {
                    raf.seek((188L * (it + 1)))
                    if (raf.read(buf) != 188 || buf[0] != 0x47.toByte()) return false
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 文本嗅探：前 8KiB 不含 NUL 且可 UTF-8 解码。 */
    fun looksLikeText(file: File): Boolean {
        if (file.length() > 8L * 1024 * 1024) return false
        val head = readHead(file, 8 * 1024) ?: return false
        if (head.isEmpty()) return false
        for (b in head) if (b == 0x00.toByte()) return false
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(head))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun matchesPrefix(file: File, prefix: ByteArray): Boolean {
        val head = readHead(file, prefix.size) ?: return false
        if (head.size < prefix.size) return false
        return prefix.indices.all { head[it] == prefix[it] }
    }

    private fun readHead(file: File, n: Int): ByteArray? {
        return try {
            file.inputStream().use { stream ->
                val buf = ByteArray(n)
                val read = stream.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        } catch (e: Exception) {
            null
        }
    }
}
