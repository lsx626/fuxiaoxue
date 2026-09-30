package edu.fudan.elearning.sync.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 可靠下载决策逻辑的单测（`.part`、续传、长度校验、同名避让、登录页嗅探）。 */
class DownloadPlanTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun partFile_addsSuffixNextToTarget() {
        val dest = File(temp.root, "报告.pdf")
        assertEquals("报告.pdf.part", DownloadPlan.partFile(dest).name)
        assertEquals(temp.root, DownloadPlan.partFile(dest).parentFile)
        assertTrue(DownloadPlan.isPartFile(DownloadPlan.partFile(dest)))
        assertFalse(DownloadPlan.isPartFile(dest))
    }

    @Test
    fun rangeHeader_onlyWhenPartExists() {
        assertEquals(null, DownloadPlan.rangeHeader(0))
        assertEquals("bytes=1024-", DownloadPlan.rangeHeader(1024))
    }

    @Test
    fun appendToPart_onlyForPartialContent() {
        assertTrue(DownloadPlan.appendToPart(206, requestedRange = true))
        // 服务端忽略 Range 返回 200：必须从头写，否则旧字节会留在文件开头
        assertFalse(DownloadPlan.appendToPart(200, requestedRange = true))
        assertFalse(DownloadPlan.appendToPart(206, requestedRange = false))
    }

    @Test
    fun lengthMismatch_onlyWhenRemoteSizeKnown() {
        assertTrue(DownloadPlan.lengthMismatch(1000, 999))
        assertFalse(DownloadPlan.lengthMismatch(1000, 1000))
        // 远端大小未知时不做长度校验
        assertFalse(DownloadPlan.lengthMismatch(0, 12345))
    }

    @Test
    fun backoff_isExponentialWithCap() {
        assertEquals(500, DownloadPlan.backoffMs(1))
        assertEquals(1_000, DownloadPlan.backoffMs(2))
        assertEquals(2_000, DownloadPlan.backoffMs(3))
        assertEquals(30_000, DownloadPlan.backoffMs(30))
    }

    @Test
    fun uniqueDestination_avoidsDatabaseNamesAndDiskFiles() {
        val dir = temp.newFolder("course")
        File(dir, "讲义.pdf").writeText("on disk but untracked")

        val taken = setOf("作业.docx")
        val dest = DownloadPlan.uniqueDestination(dir, "讲义.pdf", taken)
        assertEquals("讲义 (1).pdf", dest.name)

        val dest2 = DownloadPlan.uniqueDestination(dir, "作业.docx", taken)
        assertEquals("作业 (1).docx", dest2.name)

        // 同名不同 file_id 连续避让
        val dest3 = DownloadPlan.uniqueDestination(dir, "讲义.pdf", taken + "讲义 (1).pdf")
        assertEquals("讲义 (2).pdf", dest3.name)
    }

    @Test
    fun uniqueDestination_keepsNameWhenFree() {
        val dir = temp.newFolder("course2")
        val dest = DownloadPlan.uniqueDestination(dir, "新资料.pdf", emptySet())
        assertEquals("新资料.pdf", dest.name)
    }

    @Test
    fun uniqueDestination_handlesNameWithoutExtension() {
        val dir = temp.newFolder("course3")
        File(dir, "README").writeText("x")
        assertEquals("README (1)", DownloadPlan.uniqueDestination(dir, "README", emptySet()).name)
    }

    @Test
    fun splitNameExt_handlesDotfilesAndMultipleDots() {
        // 只按最后一个点拆分（与系统「另存同名副本」的行为一致）：
        // 期末.tar.gz -> 期末.tar + .gz，编号插入在最后一段扩展名之前
        assertEquals("期末.tar" to ".gz", DownloadPlan.splitNameExt("期末.tar.gz"))
        assertEquals(".gitignore" to "", DownloadPlan.splitNameExt(".gitignore"))
        assertEquals("noext" to "", DownloadPlan.splitNameExt("noext"))
    }

    @Test
    fun numberedName_keepsExtensionAtEnd() {
        assertEquals("期末.tar (1).gz", DownloadPlan.numberedName("期末.tar", ".gz", 1))
        assertEquals("讲义 (2).pdf", DownloadPlan.numberedName("讲义", ".pdf", 2))
        assertEquals("README (1)", DownloadPlan.numberedName("README", "", 1))
    }

    @Test
    fun loginPageSniff_detectsAuthRedirectHtml() {
        val canvasLogin = "<!DOCTYPE html><html><head><title>复旦大学统一身份认证</title>" +
            "<body>请登录</body></html>"
        assertTrue(DownloadPlan.looksLikeLoginPage("text/html; charset=utf-8", canvasLogin))

        val idpRedirect = "<html><head><meta http-equiv=\"refresh\" " +
            "content=\"0;url=https://id.fudan.edu.cn/idp/authn/authExecute\"></head></html>"
        assertTrue(DownloadPlan.looksLikeLoginPage("text/html", idpRedirect))
    }

    @Test
    fun loginPageSniff_doesNotFlagRealCourseFiles() {
        // 合法课程资料：图片/PDF 不应被误判
        assertFalse(DownloadPlan.looksLikeLoginPage("image/png", "\u0089PNG\r\n"))
        assertFalse(DownloadPlan.looksLikeLoginPage("application/pdf", "%PDF-1.7"))
        // 课程里合法的 HTML 资料没有登录标记，不能误伤
        val courseHtml = "<!DOCTYPE html><html><body><h1>第三讲 课件</h1><p>正文</p></body></html>"
        assertFalse(DownloadPlan.looksLikeLoginPage("text/html", courseHtml))
        assertFalse(DownloadPlan.looksLikeLoginPage("text/html", ""))
        // v1.2.2：「cas」是常见英文词干（case/castle），含它的课程 HTML 不能被
        // 误判成登录页（真实登录页必带 idp/authn/登录 等标记）
        val caseHtml = "<!DOCTYPE html><html><body><p>Case study: 见附件</p></body></html>"
        assertFalse(DownloadPlan.looksLikeLoginPage("text/html", caseHtml))
    }

    @Test
    fun safeRelativeDir_stripsTraversalAndIllegalChars() {
        assertEquals("第一周/课件", DownloadPlan.safeRelativeDir("第一周/课件"))
        // 目录穿越必须被消除
        assertEquals("a/b", DownloadPlan.safeRelativeDir("a/../b"))
        assertEquals("", DownloadPlan.safeRelativeDir("../../"))
        assertEquals("x", DownloadPlan.safeRelativeDir("/x/"))
        // Windows/Android 非法字符逐组件清洗
        assertEquals("期中_复习", DownloadPlan.safeRelativeDir("期中:复习"))
        assertEquals("", DownloadPlan.safeRelativeDir(null))
        assertEquals("", DownloadPlan.safeRelativeDir("   "))
    }

    @Test
    fun partOutputStream_appendKeepsExistingBytes() {
        // 断点续传的核心不变量：续传分支写入的新字节必须拼接在已有字节之后，
        // 而不是覆盖掉它们。旧实现直接 part.outputStream()（截断模式），
        // 已下载的头部被丢弃、只留下 206 的尾部片段，文件就此损坏。
        val dest = File(temp.root, "报告.pdf")
        val part = DownloadPlan.partFile(dest)
        part.writeBytes("头部已被".toByteArray(Charsets.UTF_8))

        DownloadPlan.partOutputStream(part, append = true).use { it.write("续传完成".toByteArray(Charsets.UTF_8)) }

        val content = part.readText(Charsets.UTF_8)
        assertEquals("头部已被续传完成", content)
    }

    @Test
    fun partOutputStream_truncateDiscardsExistingBytes() {
        // 全新下载（200 响应）必须从头写，避免旧 .part 残留字节污染内容
        val dest = File(temp.root, "报告2.pdf")
        val part = DownloadPlan.partFile(dest)
        part.writeBytes("过时的旧内容".toByteArray(Charsets.UTF_8))

        DownloadPlan.partOutputStream(part, append = false).use { it.write("全新".toByteArray(Charsets.UTF_8)) }

        assertEquals("全新", part.readText(Charsets.UTF_8))
    }
}
