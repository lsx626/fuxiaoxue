package edu.fudan.elearning.sync.search

/** Excerpts come from original text, never from the CJK token stream in FTS. */
object SearchSnippet {
    const val CACHE_CHARS = 64 * 1024
    fun excerpt(text: String, query: String): String {
        if (text.isBlank()) return ""
        val terms = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val match = terms.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull() ?: 0
        var start = (match - 40).coerceAtLeast(0)
        if (start > 0 && text[start].isLowSurrogate()) start--
        var end = (start + 180).coerceAtMost(text.length)
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        return (if (start > 0) "…" else "") + text.substring(start, end).replace(Regex("\\s+"), " ").trim() +
            (if (end < text.length) "…" else "")
    }
}
