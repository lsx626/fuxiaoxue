package edu.fudan.elearning.sync

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import edu.fudan.elearning.sync.data.Course
import edu.fudan.elearning.sync.data.FileItem
import edu.fudan.elearning.sync.data.Repo
import edu.fudan.elearning.sync.ui.AppViewModel
import edu.fudan.elearning.sync.ui.FuXiaoXueTheme
import edu.fudan.elearning.sync.ui.HomeScreen
import edu.fudan.elearning.sync.ui.LoginState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 主界面插桩测试：用假数据直接渲染 HomeScreen，验证 GUI 改动：
 * 导航图标、课程头像、中文文件状态、学期筛选条、设置页。
 * 导航切换使用 uiautomator 真实输入事件，避免 Compose performClick 在
 * NavigationBarItem 上的语义派发差异。
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var vm: AppViewModel
    private lateinit var device: UiDevice

    @Before
    fun seed() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        // 与 AppViewModel 共用同一 SQLite 库文件，直接写入演示数据
        val repo = Repo(ctx)
        repo.upsertCourses(
            listOf(
                Course(1, "普通化学A（上）", "CHEM10003.03", "2026秋季"),
                Course(2, "Python程序设计", "CS10002.01", "2026秋季"),
                Course(3, "微积分AⅠ", "MATH10026.06", "2025春季")
            )
        )
        repo.upsertFile(
            FileItem(101, 1, "绪论", "绪论-2026.pdf", "", "", 1234567, "downloaded", "2026-09-01", "u")
        )
        repo.upsertFile(
            FileItem(102, 1, "聚集态", "一物质的聚集态-2026.pdf", "", "", 2345678, "pending", null, "u")
        )
        repo.upsertFile(
            FileItem(103, 2, "程序设计", "1-程序设计.pptx", "", "", 3456789, "skipped", null, "u")
        )
        repo.close()

        vm = AppViewModel(ctx as Application)
        // 登录态没有公开 setter（真实路径是走 UIS 登录），这里只能反射注入；
        // 公开字段与私有 backing flow 都要替换，界面读公开字段、内部派生读私有流。
        //
        // 两个字段必须注入**不同**对象：init 里启动的 autoLogin() 协程会异步写
        // `_loginState`（最终是 LoggedOut）。若注入同一个对象，LoggedOut 会
        // 覆盖 LoggedIn，设置页的用户名变成空串。让内部写入落在一个一次性
        // flow 上，组合读的 LoggedIn flow 没人再写，竞态即消除。
        val login = MutableStateFlow<LoginState>(LoginState.LoggedIn("20260001"))
        setField(vm, "_loginState", MutableStateFlow(LoginState.LoggedOut))
        setField(vm, "loginState", login)
        // 课程数据走**真实路径**（refreshCourses 从 SQLite 读演示数据），
        // 不再反射替换：否则 ViewModel 内部在 init 时派生的 selectedCourse
        // 仍绑定在原来的 _courses 上，点进课程永远打不开文件列表。
        vm.refreshCourses()
    }

    private fun setField(obj: Any, name: String, value: Any) {
        val field = obj.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun launch() {
        composeRule.setContent {
            FuXiaoXueTheme {
                HomeScreen(vm)
            }
        }
        composeRule.waitForIdle()
    }

    /** 真实点击某个文本节点所在位置。 */
    private fun tapText(text: String) {
        val bounds = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInWindow
        device.click(bounds.center.x.toInt(), bounds.center.y.toInt())
        composeRule.waitForIdle()
    }

    /**
     * 最小复现：Scaffold + NavigationBar 的 tab 切换是否可用（与 HomeScreen 无关的对照实验）。
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun minimal_navBar_repro() {
        composeRule.setContent {
            MaterialTheme {
                val tab = remember { mutableStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            repeat(3) { i ->
                                NavigationBarItem(
                                    selected = tab.value == i,
                                    onClick = { tab.value = i },
                                    icon = { Icon(Icons.Filled.Settings, null) },
                                    label = { Text("Tab" + i) }
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.padding(padding)) {
                        Text("Content for tab " + tab.value)
                    }
                }
            }
        }
        composeRule.onNodeWithText("Tab1").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Content for tab 1").assertExists()
    }

    @Test
    fun debug_navBounds() {
        launch()
        for (t in listOf("课程", "存储", "设置")) {
            val b = composeRule.onNodeWithText(t).fetchSemanticsNode().boundsInWindow
            println("DBG label '$t' bounds=$b center=${b.center}")
        }
    }

    @Test
    fun debug_treeAfterNavClick() {
        launch()
        composeRule.onNodeWithText("存储").performClick()
        composeRule.waitForIdle()
        composeRule.onRoot().printToLog("NAVTREE")
    }

    @Test
    fun courseList_showsCoursesAndNav() {
        launch()
        composeRule.onNodeWithText("普通化学A（上）").assertExists()
        composeRule.onNodeWithText("Python程序设计").assertExists()
        composeRule.onNodeWithText("微积分AⅠ").assertExists()
        composeRule.onNodeWithText("课程").assertExists()
        composeRule.onNodeWithText("存储").assertExists()
        composeRule.onNodeWithText("设置").assertExists()
    }

    @Test
    fun storageTab_showsTermChips() {
        launch()
        tapText("存储")
        composeRule.onNodeWithText("按学期筛选").assertExists()
        composeRule.onNodeWithText("全部").assertExists()
        composeRule.onNodeWithText("2026秋季").assertExists()
        composeRule.onNodeWithText("2025春季").assertExists()
    }

    @Test
    fun settingsTab_showsAccountAndInterval() {
        launch()
        tapText("设置")
        composeRule.onNodeWithText("账号").assertExists()
        composeRule.onNodeWithText("已登录").assertExists()
        composeRule.onNodeWithText("20260001").assertExists()
        composeRule.onNodeWithText("后台同步频率").assertExists()
        composeRule.onNodeWithText("15 分钟").assertExists()
        composeRule.onNodeWithText("120 分钟").assertExists()
    }

    @Test
    fun courseDetail_showsChineseFileStatus() {
        launch()
        tapText("普通化学A（上）")
        // 文件行把「大小」和「状态」渲染成两个独立 Text 节点，
        // 因此必须分别断言，不能拼成 "1.2 MB · 已下载" 去匹配单个节点。
        composeRule.onNodeWithText("绪论").assertExists()
        composeRule.onNodeWithText("1.2 MB").assertExists()
        composeRule.onNodeWithText("已下载", substring = true).assertExists()
        composeRule.onNodeWithText("2.2 MB").assertExists()
        composeRule.onNodeWithText("待下载", substring = true).assertExists()
    }

    @Test
    fun screenshot_homeScreens() {
        launch()
        capture("home_courses.png")
        tapText("存储")
        capture("home_storage.png")
        tapText("设置")
        capture("home_settings.png")
        tapText("课程")
        tapText("普通化学A（上）")
        capture("home_files.png")
    }

    private fun capture(name: String) {
        val bmp = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(ctx.getExternalFilesDir(null), "screenshots")
        if (!dir.exists()) dir.mkdirs()
        bmp.compress(
            android.graphics.Bitmap.CompressFormat.PNG,
            100,
            java.io.FileOutputStream(File(dir, name))
        )
    }
}
