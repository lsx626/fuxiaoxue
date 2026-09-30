package edu.fudan.elearning.sync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 展示层工具：课程名清洗（剥离内嵌选课代码）与速率/时长格式化。
 *
 * 课程名来自 Canvas，常内嵌选课代码（如「数据处理与数据库 DATA130012.01」）；
 * 用户明确不希望在课程列表里看到代码，清洗只在展示层做，数据库保持原名。
 */
class FileUtilsDisplayTest {

    @Test
    fun cleanCourseName_stripsTrailingCode() {
        assertEquals(
            "数据处理与数据库",
            FileUtils.cleanCourseName("数据处理与数据库 DATA130012.01")
        )
    }

    @Test
    fun cleanCourseName_stripsLeadingCode() {
        assertEquals(
            "普通化学A（上）",
            FileUtils.cleanCourseName("CHEM10003.03 普通化学A（上）")
        )
    }

    @Test
    fun cleanCourseName_stripsParenthesizedCode() {
        assertEquals(
            "机器学习导论",
            FileUtils.cleanCourseName("机器学习导论（DATA130012.01）")
        )
    }

    @Test
    fun cleanCourseName_keepsNameWithoutCode() {
        assertEquals("软件工程", FileUtils.cleanCourseName("软件工程"))
    }

    @Test
    fun cleanCourseName_keepsEnglishWordsAndNumbers() {
        // 不是选课代码的英文词与数字必须保留
        assertEquals("Linear Algebra 2", FileUtils.cleanCourseName("Linear Algebra 2"))
        assertEquals("C++ 程序设计", FileUtils.cleanCourseName("C++ 程序设计"))
    }

    @Test
    fun cleanCourseName_fallsBackOnCodeOnly() {
        // 全是代码时不能返回空字符串
        assertEquals("DATA130012.01", FileUtils.cleanCourseName("DATA130012.01"))
    }

    @Test
    fun cleanCourseName_handlesBlank() {
        assertEquals("", FileUtils.cleanCourseName(""))
    }

    @Test
    fun cleanCourseName_stripsMultipleCodes() {
        assertEquals(
            "复变函数",
            FileUtils.cleanCourseName("复变函数 MATH120011.01 MATH120011.02")
        )
    }

    @Test
    fun formatRate_formatsUnits() {
        assertEquals("—", FileUtils.formatRate(0.0))
        assertEquals("512 B/s", FileUtils.formatRate(512.0))
        assertEquals("10.0 KB/s", FileUtils.formatRate(10 * 1024.0))
        assertEquals("1.5 MB/s", FileUtils.formatRate(1.5 * 1024 * 1024.0))
    }

    @Test
    fun formatRate_formatsFractionalKb() {
        assertTrue(FileUtils.formatRate(2.5 * 1024.0).contains("KB/s"))
    }

    @Test
    fun formatElapsed_formatsMinutes() {
        assertEquals("0 秒", FileUtils.formatElapsed(0))
        assertEquals("42 秒", FileUtils.formatElapsed(42))
        assertEquals("3 分 5 秒", FileUtils.formatElapsed(185))
    }
}
