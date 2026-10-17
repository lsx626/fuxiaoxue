package edu.fudan.elearning.sync.network

/**
 * Canvas 分页用的 `Link` 响应头解析（纯逻辑，便于单测）。
 *
 * 约束（与桌面端一致）：`rel=next` 的 URL 是**不透明值**，必须原样跟随， 绝不能自行拼页码或 bookmark。这里只负责在多个链接里挑出 next。
 */
object LinkHeader {

    /**
     * 从 Link 头里取 `rel="next"` 的 URL；没有则返回 null。
     *
     * 形如：`<https://host/api/v1/courses?page=2>; rel="next", <...>; rel="last"`。
     */
    fun next(headerValue: String?): String? {
        if (headerValue.isNullOrBlank()) return null
        for (part in splitLinks(headerValue)) {
            val segments = part.split(';')
            val rawUrl = segments.firstOrNull()?.trim().orEmpty()
            if (!rawUrl.startsWith("<") || !rawUrl.endsWith(">")) continue
            val url = rawUrl.substring(1, rawUrl.length - 1).trim()
            if (url.isEmpty()) continue
            val isNext =
                segments.drop(1).any { segment ->
                    val param = segment.trim()
                    if (!param.startsWith("rel", ignoreCase = true)) return@any false
                    val value = param.substringAfter('=', "").trim().trim('"')
                    value.split(' ', '\t').any { it.equals("next", ignoreCase = true) }
                }
            if (isNext) return url
        }
        return null
    }

    /**
     * 按顶层逗号切分多个链接。
     *
     * URL 本身可能含逗号（如 query 里的 `fields[]=a,b`），因此必须忽略 `<>` 内部与引号内部的逗号，否则会切出残缺 URL。
     */
    private fun splitLinks(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inAngle = false
        var inQuote = false
        for (ch in value) {
            when {
                ch == '<' && !inQuote -> {
                    inAngle = true
                    current.append(ch)
                }
                ch == '>' && !inQuote -> {
                    inAngle = false
                    current.append(ch)
                }
                ch == '"' -> {
                    inQuote = !inQuote
                    current.append(ch)
                }
                ch == ',' && !inAngle && !inQuote -> {
                    parts.add(current.toString())
                    current.setLength(0)
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotBlank()) parts.add(current.toString())
        return parts
    }
}
