package edu.fudan.elearning.sync.preview

internal data class VisiblePage(val page: Int, val offset: Int, val size: Int)
internal fun dominantPage(pages: List<VisiblePage>, viewportStart: Int, viewportEnd: Int): Int =
    pages.maxByOrNull { (minOf(it.offset + it.size, viewportEnd) - maxOf(it.offset, viewportStart)).coerceAtLeast(0) }?.page ?: 0

internal class ResumeOnce {
    private var applied = false
    fun seekPosition(ready: Boolean, seconds: Int): Long? {
        if (!ready || applied) return null
        applied = true
        return if (seconds > 0) (seconds - 2).coerceAtLeast(0) * 1000L else null
    }
}
