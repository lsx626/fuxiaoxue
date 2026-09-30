package edu.fudan.elearning.sync.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * 搜索分词器与查询表达式的 JVM 单测。
 *
 * 与桌面端 tests/test_search_index.py 的 TokenizerTests/MatchQueryTests
 * 是同一组断言的两侧实现，保证双端搜索语义一致。
 */
class SearchIndexTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun cjkRunGetsUnigramsAndBigrams() {
        // 默认分词器不分中文：必须预分词后才能子串检索
        assertEquals("计 算 机 计算 算机", SearchIndex.tokenizeForIndex("计算机"))
    }

    @Test
    fun latinAndDigitsStayWhole() {
        assertEquals("lecture 12 chapters", SearchIndex.tokenizeForIndex("Lecture 12 chapters"))
    }

    @Test
    fun mixedRuns() {
        val tokens = SearchIndex.tokenizeForIndex("数据结构 lecture5.pdf").split(" ")
        assertTrue("数据" in tokens)
        assertTrue("结构" in tokens)
        // 标点把文件名拆成词元：lecture5 与 pdf 分别是独立 token
        assertTrue("lecture5" in tokens)
        assertTrue("pdf" in tokens)
    }

    @Test
    fun punctuationSeparatesOnly() {
        // 标点把汉字串切断成单字：不再产生跨标点的双字
        val tokens = SearchIndex.tokenizeForIndex("计,算！机").split(" ")
        assertTrue("计" in tokens)
        assertTrue("算" in tokens)
        assertTrue("机" in tokens)
        assertFalse("计算" in tokens)
    }

    @Test
    fun singleCjkChar() {
        assertEquals("课", SearchIndex.tokenizeForIndex("课"))
    }

    @Test
    fun emptyAndAsciiOnly() {
        assertEquals("", SearchIndex.tokenizeForIndex(""))
        assertEquals("", SearchIndex.tokenizeForIndex("   "))
        assertEquals("plain text", SearchIndex.tokenizeForIndex("plain text"))
    }

    @Test
    fun emptyQueryIsNone() {
        assertNull(SearchIndex.buildMatchQuery(""))
        assertNull(SearchIndex.buildMatchQuery("   ，。！"))
    }

    @Test
    fun singleCjkCharQuery() {
        assertEquals("\"课\"", SearchIndex.buildMatchQuery("课"))
    }

    @Test
    fun cjkPhraseGetsUnigramsAndBigrams() {
        val match = SearchIndex.buildMatchQuery("计算机")!!
        assertTrue("\"计\"" in match)
        assertTrue("\"计算\"" in match)
        assertTrue("\"算机\"" in match)
    }

    @Test
    fun mixedQueryIsConjoined() {
        val match = SearchIndex.buildMatchQuery("算法 导论")!!
        assertTrue(" " in match) // 空格分隔 = FTS4 AND
    }

    @Test
    fun quotesAreEscaped() {
        // 查询内的引号不能破坏 FTS4 语法
        val match = SearchIndex.buildMatchQuery("a\"b")!!
        assertFalse(match.contains("\"a\"b\""))
    }

    @Test
    fun capTruncates() {
        assertEquals(SearchIndex.MAX_INDEX_CHARS, SearchIndex.cap("甲".repeat(SearchIndex.MAX_INDEX_CHARS + 100)).length)
    }

    @Test
    fun decodeUtf8AndGbkFallback() {
        val utf8 = "计算机导论".toByteArray(Charset.forName("UTF-8"))
        assertEquals("计算机导论", TextExtractor.decodeText(utf8))
        val gbk = "计算机体系结构".toByteArray(Charset.forName("GBK"))
        assertEquals("计算机体系结构", TextExtractor.decodeText(gbk))
    }

    @Test
    fun decodeLatin1FallbackNeverFails() {
        // 0x80 在 UTF-8 / GB18030 / GBK 中都不是合法单字节，三种都严格失败后
        // 走 ISO-8859-1 兜底，绝不能抛异常
        val binary = byteArrayOf(0x00, 0x80.toByte())
        assertEquals(2, TextExtractor.decodeText(binary).length)
    }

    @Test
    fun capApplied() {
        assertTrue(SearchIndex.cap("x".repeat(100)).length == 100)
    }

    @Test
    fun stripHtmlDropsScriptStyle() {
        val html = "<html><body><h1>实验报告</h1><script>var x = '秘密';</script>" +
            "<style>.a{color:red}</style><p>内容甲</p></body></html>"
        val text = TextExtractor.stripHtml(html)
        assertTrue("实验报告" in text)
        assertTrue("内容甲" in text)
        assertFalse("秘密" in text)
        assertFalse("color:red" in text)
        assertFalse("<h1>" in text)
    }

    @Test
    fun stripHtmlUnescapesEntities() {
        val text = TextExtractor.stripHtml("a&nbsp;&amp;b&lt;c&gt;d")
        assertEquals("a &b<c>d", text)
    }
}
