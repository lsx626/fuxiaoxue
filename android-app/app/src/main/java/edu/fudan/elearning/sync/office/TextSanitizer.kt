package edu.fudan.elearning.sync.office

/**
 * 文档文本清洗（纯函数，可单测）。
 *
 * 真实的 Office 文本里常混有渲染层不可见的控制字符，直接交给 StaticLayout 会 变成方块/乱码：
 * - `\u000B`（VT）在 PPT 里表示**软换行**，要转成 `\n`；
 * - `\u000C`（FF）、`\r` 统一成 `\n`；
 * - OOXML 会把非法控制字符转义成 `_x000B_` 这样的字面标记，必须还原；
 * - `\u0000`–`\u0008`、`\u000E`–`\u001F` 是嵌入对象/域标记，直接丢弃；
 * - 代理对（emoji 等）必须原样保留，不能按 char 拆坏。
 */
object TextSanitizer {

    private const val X_ESCAPE_PREFIX = "_x"
    private const val X_ESCAPE_LENGTH = 7 // _x000B_ 共 7 个字符（下划线 x 四位十六进制 下划线）

    fun clean(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            // OOXML 字面转义：_x000B_ / _x000D_ / _x000A_ …
            if (text.startsWith(X_ESCAPE_PREFIX, i) && i + X_ESCAPE_LENGTH <= text.length) {
                val hex = text.substring(i + 2, i + 6)
                val parsed = hex.toIntOrNull(16)
                if (parsed != null && text[i + 6] == '_') {
                    when (parsed) {
                        0x0B,
                        0x0A,
                        0x0C -> out.append('\n')
                        0x0D -> Unit // 回车标记：换行由段落结构表达
                        0x09 -> out.append('\t')
                        else -> if (parsed > 0x1F && parsed != 0x7F) out.append(parsed.toChar())
                    }
                    i += X_ESCAPE_LENGTH
                    continue
                }
            }
            val c = text[i]
            when {
                c == '\u000B' || c == '\u000C' -> out.append('\n')
                c == '\r' -> if (i + 1 >= text.length || text[i + 1] != '\n') out.append('\n')
                c == '\u0000' -> Unit
                c.code in 0x01..0x08 || c.code in 0x0E..0x1F || c.code == 0x7F -> Unit
                else -> out.append(c)
            }
            i += 1
        }
        return out.toString()
    }
}
