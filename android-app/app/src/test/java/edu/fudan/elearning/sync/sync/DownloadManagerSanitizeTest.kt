package edu.fudan.elearning.sync.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 文件名净化的 JVM 单测：重点是百分号解码的正确性与安全性。
 *
 * Canvas 的 filename 字段对非 ASCII 名字常返回百分号编码，直接落盘会让 本地文件名变成乱码；sanitize 必须安全解码而不能破坏字面百分号。
 */
class DownloadManagerSanitizeTest {

    @Test
    fun plainChineseName_unchanged() {
        assertEquals("期末复习资料.pdf", DownloadManager.sanitize("期末复习资料.pdf"))
    }

    @Test
    fun percentEncodedChinese_decoded() {
        // "期末复习资料" 的 UTF-8 百分号编码
        val encoded = "%E6%9C%9F%E6%9C%AB%E5%A4%8D%E4%B9%A0%E8%B5%84%E6%96%99.pdf"
        assertEquals("期末复习资料.pdf", DownloadManager.sanitize(encoded))
    }

    @Test
    fun literalPercent_preserved() {
        // % 后不是十六进制，必须原样保留，不能吞掉
        assertEquals("100%完成.pdf", DownloadManager.sanitize("100%完成.pdf"))
        assertEquals("折扣50%.txt", DownloadManager.sanitize("折扣50%.txt"))
    }

    @Test
    fun illegalChars_replaced() {
        assertEquals("a_b_c", DownloadManager.sanitize("a/b\\c"))
    }

    @Test
    fun emptyOrAllIllegal_fallbackName() {
        assertEquals("未命名", DownloadManager.sanitize(""))
        assertEquals("未命名", DownloadManager.sanitize("   "))
        // 全是非法字符时逐字符替换为下划线（与 v1.0.5 起的落盘行为一致），
        // 只有结果为空才回退为「未命名」
        assertEquals("___", DownloadManager.sanitize("///"))
    }

    @Test
    fun mixedContent_stableRoundTrip() {
        assertEquals("第1章 100%完成.pdf", DownloadManager.sanitize("第1章 100%完成.pdf"))
    }
}
