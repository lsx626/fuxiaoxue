package edu.fudan.elearning.sync.search

/**
 * 本地全文搜索的 CJK 预分词与 FTS4 查询构造。
 *
 * 为什么自己分词：Android 平台 SQLite 的 FTS4 默认分词器（simple）
 * 不做中文分词——一整段汉字会合并成**一个** token。结果是搜「计算机」
 * 永远无法命中「计算机体系结构」。这里把汉字串拆成「单字 + 双字」：
 * 单字保证单字检索可用，双字保证词组的顺序与精度（「算计」不会
 * 被「计算」误命中）。索引与查询使用同一套规则。
 *
 * 与桌面端 [fudan_sync.search_index] 是同一算法的两端实现，
 * 保持搜索语义一致（AGENTS.md 3.1 能力矩阵）。
 */
object SearchIndex {

    private val CJK_RANGES = arrayOf(
        intArrayOf(0x3040, 0x30FF),   // 平假名/片假名
        intArrayOf(0x3400, 0x4DBF),   // CJK 扩展 A
        intArrayOf(0x4E00, 0x9FFF),   // CJK 统一表意文字
        intArrayOf(0xF900, 0xFAFF)    // CJK 兼容表意
    )

    /** 单个文件进入索引的最大字符数（防止巨型文件拖垮同步）。 */
    const val MAX_INDEX_CHARS = 65536

    private fun isCjk(codePoint: Int): Boolean {
        for (range in CJK_RANGES) {
            if (codePoint >= range[0] && codePoint <= range[1]) return true
        }
        return false
    }

    /**
     * 把原始文本转成空格分隔的 token 串（单字 + 双字）。
     * 「计算机」→ 「计 算 机 计算 算机」。
     */
    fun tokenizeForIndex(text: String): String {
        if (text.isEmpty()) return ""
        val lowered = text.lowercase()
        val tokens = mutableListOf<String>()
        var i = 0
        val n = lowered.length
        while (i < n) {
            val codePoint = lowered.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            if (isCjk(codePoint)) {
                // 收集连续汉字串
                val run = StringBuilder()
                var j = i
                while (j < n) {
                    val cp = lowered.codePointAt(j)
                    if (!isCjk(cp)) break
                    run.appendCodePoint(cp)
                    j += Character.charCount(cp)
                }
                val s = run.toString()
                for (c in s) tokens.add(c.toString())                    // 单字
                for (k in 0 until s.length - 1) tokens.add(s.substring(k, k + 2))  // 双字
                i = j
            } else if (Character.isLetterOrDigit(codePoint)) {
                // 连续字母数字作为一个 token
                val run = StringBuilder()
                var j = i
                while (j < n) {
                    val cp = lowered.codePointAt(j)
                    if (isCjk(cp) || !Character.isLetterOrDigit(cp)) break
                    run.appendCodePoint(cp)
                    j += Character.charCount(cp)
                }
                tokens.add(run.toString())
                i = j
            } else {
                i += charCount
            }
        }
        return tokens.joinToString(" ")
    }

    /**
     * 把用户输入转成 FTS4 MATCH 表达式；空查询返回 null。
     *
     * FTS4 与桌面 FTS5 的差异：FTS4 **不支持前缀查询**（`term*`），
     * 因此这里最后一个 ASCII 词不做前缀匹配；其余语义（引号包裹词元、
     * 空格表示 AND）与桌面端一致。
     */
    fun buildMatchQuery(query: String): String? {
        if (query.isEmpty()) return null
        val lowered = query.lowercase()
        val terms = mutableListOf<String>()
        var i = 0
        val n = lowered.length
        while (i < n) {
            val codePoint = lowered.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            if (isCjk(codePoint)) {
                val run = StringBuilder()
                var j = i
                while (j < n) {
                    val cp = lowered.codePointAt(j)
                    if (!isCjk(cp)) break
                    run.appendCodePoint(cp)
                    j += Character.charCount(cp)
                }
                val s = run.toString()
                if (s.length == 1) {
                    terms.add(s)
                } else {
                    for (c in s) terms.add(c.toString())
                    for (k in 0 until s.length - 1) terms.add(s.substring(k, k + 2))
                }
                i = j
            } else if (Character.isLetterOrDigit(codePoint)) {
                val run = StringBuilder()
                var j = i
                while (j < n) {
                    val cp = lowered.codePointAt(j)
                    if (isCjk(cp) || !Character.isLetterOrDigit(cp)) break
                    run.appendCodePoint(cp)
                    j += Character.charCount(cp)
                }
                terms.add(run.toString())
                i = j
            } else {
                i += charCount
            }
        }
        if (terms.isEmpty()) return null
        return terms.take(64).joinToString(" ") { "\"${it.replace("\"", "")}\"" }
    }

    /** 截断到索引上限。 */
    fun cap(text: String): String =
        if (text.length > MAX_INDEX_CHARS) text.substring(0, MAX_INDEX_CHARS) else text
}
