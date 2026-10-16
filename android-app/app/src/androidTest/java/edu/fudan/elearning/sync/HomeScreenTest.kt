package edu.fudan.elearning.sync

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.fudan.elearning.sync.auth.LoginResult
import edu.fudan.elearning.sync.data.*
import edu.fudan.elearning.sync.ui.*
import edu.fudan.elearning.sync.sync.LocalStorage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Production navigation, real SQLite and synthetic local files. No school requests. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var vm: AppViewModel
    private lateinit var store: ViewModelStore
    private lateinit var intro: File
    private lateinit var archived: File

    @Before fun seed() {
        context = ApplicationProvider.getApplicationContext()
        edu.fudan.elearning.sync.util.Prefs(context).backgroundSyncEnabled = false
        context.deleteDatabase("fudan_sync.db")
        val dir = File(LocalStorage(context).root, "ui-synthetic").apply { mkdirs() }
        intro = File(dir, "绪论.txt").apply { writeText("本节讲授神经网络与反向传播。\n".repeat(30)) }
        archived = File(dir, "已归档.txt").apply { writeText("远端已删除的本地副本") }
        val repo = Repo(context)
        try {
            repo.upsertCourses(listOf(Course(1, "普通化学A（上）", "CHEM10003.03", "2026秋季"),
                Course(2, "Python程序设计", "CS10002.01", "2026秋季"), Course(3, "微积分AⅠ", "MATH10026.06", "2025春季")))
            val first = FileItem(101, 1, "绪论", "绪论.txt", localPath = intro.path, size = intro.length(), status = "downloaded")
            repo.upsertFile(first)
            repo.upsertFile(FileItem(102, 1, "聚集态", "聚集态.pdf", size = 2345678, status = "pending"))
            repo.upsertFile(FileItem(103, 1, "实验步骤", "实验步骤.py", status = "failed", error = "下载失败（HTTP 404），请重试"))
            repo.upsertFile(FileItem(104, 1, "参考答案", "参考答案.docx", status = "locked", error = "教师已锁定，暂不开放下载"))
            repo.upsertFile(FileItem(105, 1, "已归档", "已归档.txt", localPath = archived.path, size = archived.length(), status = "remote_missing"))
            repo.upsertFileIndex(101, first.name, intro.readText())
            repo.setReadingProgress(101, 1, 1, 10, false)
            repo.upsertAssignments(1, listOf(Assignment(1001, 1, "实验报告", Instant.now().plusSeconds(86400).toString()),
                Assignment(1002, 1, "课前练习", Instant.now().minusSeconds(86400).toString())))
            repo.recordFileChange(0, first, "new")
        } finally { repo.close() }
        vm = AppViewModel(context as Application, false, authenticate = { _, _ -> LoginResult.Failure("账号或密码错误") })
        store = ViewModelStore().apply { put("test", vm) }
        loginAs(LoginState.LoggedIn("20260001"))
        vm.setTheme(ThemeMode.LIGHT)
        vm.refreshCourses()
    }

    @After fun close() { compose.runOnIdle { store.clear() } }

    @Suppress("UNCHECKED_CAST")
    private fun loginAs(state: LoginState) {
        val field = vm.javaClass.getDeclaredField("_loginState").apply { isAccessible = true }
        (field.get(vm) as MutableStateFlow<LoginState>).value = state
    }
    private fun launch() {
        compose.setContent { AppContent(vm) }
        compose.waitUntil(10_000) { vm.courses.value.size == 3 }
    }
    private fun tab(name: String) { compose.onNodeWithTag("tab-$name").performClick() }
    private fun course() {
        compose.onNodeWithTag("courses-list").performScrollToNode(hasText("普通化学A（上）"))
        compose.onNodeWithText("普通化学A（上）").performClick()
        waitText("查找文件名或目录")
    }
    private fun waitText(text: String) { compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }

    @Test fun navigationHasFourDestinationsAndRealCourseData() {
        launch()
        for (name in listOf("COURSES", "ACTIVITY", "STORAGE", "SETTINGS")) compose.onNodeWithTag("tab-$name").assertExists()
        compose.onNodeWithTag("courses-list").performScrollToNode(hasText("Python程序设计"))
        compose.onNodeWithText("Python程序设计").assertIsDisplayed()
    }

    @Test fun courseSearchCombinesTermAndCodeAndCanClear() {
        launch()
        compose.onNodeWithText("查找课程名称或代码").performTextInput("CHEM")
        compose.onNodeWithText("普通化学A（上）").assertExists()
        compose.onNodeWithText("Python程序设计").assertDoesNotExist()
        compose.onNodeWithText("2025春季").performClick()
        compose.onNodeWithText("没有符合当前条件的资料").assertExists()
        compose.onNodeWithText("清除筛选").performClick()
        compose.onNodeWithTag("courses-list").performScrollToNode(hasText("Python程序设计"))
        compose.onNodeWithText("Python程序设计").assertIsDisplayed()
    }

    @Test fun fileSearchCombinesStatusAndOpensCorrectFile() {
        launch(); course()
        compose.onNodeWithText("查找文件名或目录").performTextInput("绪论")
        waitText("绪论")
        compose.onNodeWithText("下载失败").performScrollTo().performClick()
        compose.onNodeWithText("没有符合当前条件的资料").assertExists()
        compose.onNodeWithText("清除筛选").performClick()
        compose.onNodeWithText("查找文件名或目录").performTextInput("绪论")
        compose.onNodeWithTag("file-101").performClick()
        compose.waitUntil(10_000) { vm.previewTarget.value?.fileId == 101L }
    }

    @Test fun textPreviewReturnsToSameCourseFilter() {
        launch(); course()
        compose.onNodeWithText("查找文件名或目录").performTextInput("绪论")
        compose.onNodeWithTag("file-101").performClick()
        compose.waitUntil(10_000) { vm.previewTarget.value != null }
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("查找文件名或目录")
        compose.onNode(hasSetTextAction() and hasText("绪论")).assertExists()
    }

    @Test fun searchPreviewReturnsToResultsAndQuery() {
        launch()
        compose.onNodeWithContentDescription("搜索文件").performClick()
        compose.onNodeWithText("搜索文件名或内容…").performTextInput("神经网络")
        waitText("绪论")
        compose.onNodeWithText("绪论").performClick()
        compose.waitUntil(10_000) { vm.previewTarget.value != null }
        compose.onNodeWithContentDescription("返回").performClick()
        waitText("搜索资料")
        compose.onNode(hasSetTextAction() and hasText("神经网络")).assertExists()
        compose.onNodeWithText("绪论").assertExists()
    }

    @Test fun activityShowsDeadlinesAndChanges() {
        launch(); tab("ACTIVITY")
        waitText("实验报告")
        compose.onNodeWithText("实验报告").assertExists()
        compose.onNodeWithText("最近变更").performClick()
        compose.onNodeWithText("绪论").assertExists()
    }

    @Test fun settingsChangeThemeAndInterval() {
        launch(); tab("SETTINGS")
        compose.onNodeWithText("20260001").assertExists()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("显示主题"))
        compose.onNodeWithText("显示主题").performClick()
        compose.onNodeWithText("深色").performClick()
        assertEquals(ThemeMode.DARK, vm.settings.value.theme)
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("后台同步频率"))
        compose.onNodeWithText("后台同步频率").performClick()
        compose.onNodeWithText("30 分钟").performClick()
        assertEquals(30, vm.settings.value.syncInterval)
    }

    @Test fun storageIncludesRemoteMissingAndDeleteCanBeCancelled() {
        launch(); tab("STORAGE")
        compose.onNodeWithTag("storage-list").performScrollToNode(hasText("普通化学A（上）"))
        compose.onNodeWithText("普通化学A（上）").performClick()
        compose.onNodeWithTag("storage-list").performScrollToNode(hasText("已归档"))
        compose.onNodeWithContentDescription("文件操作：已归档").performClick()
        compose.onNodeWithText("删除本地文件").performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(archived.isFile)
        compose.onNodeWithContentDescription("文件操作：已归档").performClick()
        compose.onNodeWithText("删除本地文件").performClick()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(10_000) { !archived.exists() && !vm.taskBusy.value && vm.busyFiles.value.isEmpty() }
        val repo = Repo(context)
        try { assertNull(repo.getFile(105)) } finally { repo.close() }
    }

    @Test fun fullSyncRequiresExplicitConfirmation() {
        launch()
        compose.onNodeWithContentDescription("更多操作").performClick()
        compose.onNodeWithText("全量同步（重新下载全部文件）").performClick()
        compose.onNodeWithText("重新下载全部资料？").assertExists()
        compose.onNodeWithText("取消").performClick()
        assertFalse(vm.syncing.value)
    }

    @Test fun loginFormReportsErrorsAndKeepsUsername() {
        loginAs(LoginState.LoggedOut)
        launch()
        compose.onNodeWithText("账号（学号 / 工号）").performTextInput("synthetic-user")
        compose.onNodeWithText("UIS 密码").performTextInput("synthetic-password")
        compose.onNodeWithText("显示密码").performClick()
        compose.onNodeWithText("隐藏密码").assertExists()
        compose.onNodeWithText("登录并同步").performClick()
        waitText("账号或密码错误")
        compose.onNode(hasSetTextAction() and hasText("synthetic-user")).assertExists()
    }

    @Test fun screenshotModernScreens() {
        launch(); capture("courses")
        tab("ACTIVITY"); capture("activity")
        tab("STORAGE"); capture("storage")
        tab("SETTINGS"); capture("settings")
        tab("COURSES"); course(); waitText("绪论"); capture("files")
        vm.setTheme(ThemeMode.DARK); compose.waitForIdle(); capture("files-dark")
        vm.setTheme(ThemeMode.LIGHT); compose.waitForIdle()
        vm.clearCourseSelection(); compose.waitForIdle()
        compose.onNodeWithContentDescription("搜索文件").performClick(); capture("search")
        vm.closeSearch(); loginAs(LoginState.LoggedOut); compose.waitForIdle(); capture("login")
        compose.onNodeWithText("登录并同步").performScrollTo().assertIsDisplayed()
        capture("login-action")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(context.getExternalFilesDir(null), "screenshots/redesign").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
