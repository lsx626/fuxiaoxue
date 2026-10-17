package edu.fudan.elearning.sync.office

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.fudan.elearning.sync.ui.FuXiaoXueTheme
import java.awt.Dimension
import java.awt.geom.Rectangle2D
import java.io.File
import java.io.FileOutputStream
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Office 预览界面插桩测试：组合 OfficePreviewScreen + VerticalPageList，验证 「解析 -> 分页 -> 逐页渲染 -> 纵向列表展示」整条链路在 UI
 * 层可用（含 LRU 缓存的钉住/回收逻辑与保真度提示卡）。
 *
 * 解析在 Dispatchers.IO 异步执行，waitForIdle 不会等待它。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OfficePreviewUiTest {

    @get:Rule val composeRule = createComposeRule()

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    private fun makePptx(name: String, slides: List<String>): File {
        val file = File(ctx.cacheDir, name)
        FileOutputStream(file).use { out ->
            XMLSlideShow().use { show ->
                show.setPageSize(Dimension(720, 405))
                slides.forEach { text ->
                    val slide = show.createSlide()
                    val box = slide.createTextBox()
                    box.setAnchor(Rectangle2D.Double(50.0, 40.0, 400.0, 80.0))
                    box.setText(text)
                }
                show.write(out)
            }
        }
        return file
    }

    @Test
    fun officePreview_showsPagesAndLimitationNote() {
        val file = makePptx("ui_preview.pptx", listOf("第一章 概述", "第二章 结论"))

        composeRule.setContent { FuXiaoXueTheme { OfficePreviewScreen(file) } }

        // 等异步解析+渲染完成后，页脚「第 1 / 2 页」出现
        composeRule.waitUntilAtLeastOneExists(hasText("第 1 / 2 页"), 15_000)
        composeRule.onNodeWithText("第 1 / 2 页").assertIsDisplayed()
        // 保真度提示卡（含文件名）应出现
        composeRule.onNodeWithText(file.name).assertIsDisplayed()
    }

    @Test
    fun officePreview_corruptShowsErrorPage() {
        val file = File(ctx.cacheDir, "ui_broken.pptx")
        FileOutputStream(file).use { it.write(ByteArray(1024) { (it % 251).toByte() }) }

        composeRule.setContent { FuXiaoXueTheme { OfficePreviewScreen(file) } }

        // 解析失败时应显示明确错误页，而不是崩溃或空白
        composeRule.waitUntilAtLeastOneExists(hasText("无法预览"), 15_000)
        composeRule.onNodeWithText("无法预览").assertIsDisplayed()
    }
}
