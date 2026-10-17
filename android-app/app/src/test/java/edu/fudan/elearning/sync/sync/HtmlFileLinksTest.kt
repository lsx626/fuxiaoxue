package edu.fudan.elearning.sync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 正文 HTML 里文件引用提取的单测（与桌面端 crawler.py 的两条正则等价）。 */
class HtmlFileLinksTest {

    @Test
    fun extractsCanvasFileLinkForms() {
        val html =
            """
            <p><a href="/courses/123/files/456/download?verifier=abc">讲义</a></p>
            <img src="/courses/123/files/789/preview">
            <a href="https://elearning.fudan.edu.cn/api/v1/courses/123/files/1010">API 形式</a>
        """
                .trimIndent()
        assertEquals(setOf(456L, 789L, 1010L), HtmlFileLinks.extractFileIds(html))
    }

    @Test
    fun extractsPreviewShorthand() {
        assertEquals(
            setOf(42L),
            HtmlFileLinks.extractFileIds("<a href=\"/files/42/preview\">x</a>"),
        )
    }

    @Test
    fun deduplicatesAndIgnoresUnrelatedLinks() {
        val html =
            """
            <a href="/courses/1/files/9/download">a</a>
            <a href="/courses/1/files/9/preview">b</a>
            <a href="/courses/1/pages/intro">页面</a>
            <a href="/courses/1/assignments/3">作业</a>
            <a href="https://example.com/x.pdf">外链</a>
        """
                .trimIndent()
        assertEquals(setOf(9L), HtmlFileLinks.extractFileIds(html))
    }

    @Test
    fun handlesEmptyAndNullBody() {
        assertTrue(HtmlFileLinks.extractFileIds(null).isEmpty())
        assertTrue(HtmlFileLinks.extractFileIds("").isEmpty())
        assertTrue(HtmlFileLinks.extractFileIds("   ").isEmpty())
        assertTrue(HtmlFileLinks.extractFileIds("没有链接的正文").isEmpty())
    }

    @Test
    fun ignoresNonPositiveIds() {
        assertTrue(HtmlFileLinks.extractFileIds("/courses/1/files/0/download").isEmpty())
    }
}
