package edu.fudan.elearning.sync.network

/**
 * 元数据请求的节流与重试策略（纯计算，可在 JVM 单测里精确验证）。
 *
 * 与桌面端 `CanvasAPI` 保持同一套语义：
 * - 元数据请求严格低频串行，默认最小间隔 [minIntervalMs]；
 * - 网络异常与 5xx 指数退避，上限 [maxBackoffMs]；
 * - 429 或含 `Rate Limit Exceeded` 的 403 尊重 `Retry-After`，缺省 [defaultRateLimitMs]；
 * - `X-Rate-Limit-Remaining` 低于 [slowDownRemainingThreshold] 时主动减速。
 */
data class RequestPolicy(
    val minIntervalMs: Long = 150,
    val maxAttempts: Int = 4,
    val baseBackoffMs: Long = 500,
    val maxBackoffMs: Long = 30_000,
    val defaultRateLimitMs: Long = 8_000,
    val slowDownRemainingThreshold: Int = 15,
    val slowDownExtraMs: Long = 500,
    val maxPages: Int = 200,
) {
    /** 第 [attempt] 次失败后的退避：500ms、1s、2s…，封顶 [maxBackoffMs]。 */
    fun backoffMs(attempt: Int): Long {
        val shift = (maxOf(attempt, 1) - 1).coerceAtMost(20)
        return (baseBackoffMs shl shift).coerceAtMost(maxBackoffMs)
    }

    /** `Retry-After`（秒）→ 毫秒；缺失或非法时用 [defaultRateLimitMs]，并封顶。 */
    fun retryAfterMs(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull()
        val ms = if (seconds != null && seconds >= 0) seconds * 1000 else defaultRateLimitMs
        return ms.coerceIn(0, maxBackoffMs)
    }

    /** 429，或 403 且正文含 `Rate Limit Exceeded`，都算被限流。 */
    fun isRateLimited(code: Int, body: String): Boolean =
        code == 429 || (code == 403 && body.contains("Rate Limit Exceeded", ignoreCase = true))

    /** 5xx、408 与限流可重试；其余 4xx（含 401/403）不重试。 */
    fun isRetryable(code: Int, rateLimited: Boolean): Boolean =
        rateLimited || code >= 500 || code == 408

    /** `X-Rate-Limit-Remaining` → Int；缺失或非法返回 null。 */
    fun remaining(header: String?): Int? = header?.trim()?.toIntOrNull()

    /** 剩余额度低于阈值时的额外减速，否则 0。 */
    fun slowDownMs(remaining: Int?): Long =
        if (remaining != null && remaining < slowDownRemainingThreshold) slowDownExtraMs else 0L
}
