package edu.fudan.elearning.sync.preview

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import edu.fudan.elearning.sync.ui.FuXiaoXueTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun capture(name: String) {
        val dir =
            File(context.getExternalFilesDir(null), "screenshots/redesign-reader").apply {
                mkdirs()
            }
        File(dir, "$name.png").outputStream().use {
            compose
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun assertReaderControlsDoNotOverlap() {
        val pageControls = compose.onNodeWithText("回到页首").fetchSemanticsNode().boundsInRoot
        val zoomControls =
            compose.onNodeWithContentDescription("放大页面").fetchSemanticsNode().boundsInRoot
        assertTrue("缩放按钮必须位于页码操作下方，不能叠在一起", zoomControls.top >= pageControls.bottom)
    }

    @Test
    fun nativePdfRestoresAndSwitchesFilesThroughProductionRoute() {
        fun pdf(name: String, pages: Int): File =
            File(context.cacheDir, name).also { file ->
                val document = android.graphics.pdf.PdfDocument()
                try {
                    repeat(pages) { index ->
                        val page =
                            document.startPage(
                                android.graphics.pdf.PdfDocument.PageInfo.Builder(240, 360, index)
                                    .create()
                            )
                        page.canvas.drawText(
                            "Synthetic page ${index + 1}",
                            20f,
                            40f,
                            android.graphics.Paint().apply { textSize = 14f },
                        )
                        document.finishPage(page)
                    }
                    file.outputStream().use(document::writeTo)
                } finally {
                    document.close()
                }
            }
        val first = pdf("synthetic-first.pdf", 3)
        val second = pdf("synthetic-second.pdf", 2)
        val target = mutableStateOf<File?>(first)
        val firstProgress = AtomicReference<Pair<Int, Int>?>(null)
        val secondProgress = AtomicReference<Pair<Int, Int>?>(null)
        try {
            compose.setContent {
                FuXiaoXueTheme {
                    val selected = target.value
                    if (selected == null) Text("已关闭合成文档")
                    else
                        PreviewScreen(
                            selected,
                            onBack = { target.value = null },
                            onShare = {},
                            initialPage = if (selected == first) 1 else 0,
                            onPageChanged = { page, total ->
                                (if (selected == first) firstProgress else secondProgress).set(
                                    page to total
                                )
                            },
                        )
                }
            }
            compose.waitUntil(10_000) { firstProgress.get() == (1 to 3) }
            assertReaderControlsDoNotOverlap()
            repeat(3) {
                secondProgress.set(null)
                compose.runOnIdle { target.value = second }
                compose.waitUntil(10_000) { secondProgress.get() == (0 to 2) }
                firstProgress.set(null)
                compose.runOnIdle { target.value = first }
                compose.waitUntil(10_000) { firstProgress.get() == (1 to 3) }
            }
            capture("pdf")
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithText("已关闭合成文档").assertIsDisplayed()
            assertEquals(3, firstProgress.get()!!.second)
            assertEquals(2, secondProgress.get()!!.second)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun restoreAndJumpIncludeHeaderAndZoomCanReset() {
        val reported = AtomicInteger(-1)
        compose.setContent {
            FuXiaoXueTheme {
                VerticalPageList(
                    8,
                    { 0.7f },
                    { Bitmap.createBitmap(140, 200, Bitmap.Config.ARGB_8888) },
                    header = { Text("合成文档说明") },
                    initialPage = 3,
                    onPageChanged = { page, _ -> reported.set(page) },
                )
            }
        }
        compose.waitUntil(10_000) { reported.get() == 3 }
        assertReaderControlsDoNotOverlap()
        capture("pages")
        compose.onNodeWithText("4 / 8 页").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("7")
        compose
            .onNodeWithText(
                context.getString(edu.fudan.elearning.sync.R.string.preview_jump_confirm)
            )
            .performClick()
        compose.waitUntil(10_000) { reported.get() == 6 }
        compose.onNodeWithContentDescription("放大页面").performClick()
        compose.onNodeWithText("125%").assertExists()
        compose.onNodeWithText("适合宽度").performClick()
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithText("回到页首").performClick()
        compose.waitUntil(10_000) { reported.get() == 0 }
    }

    @Test
    fun pinchGestureZoomsWithoutResettingOnRelease() {
        compose.setContent {
            FuXiaoXueTheme {
                VerticalPageList(
                    1,
                    { 0.7f },
                    {
                        Bitmap.createBitmap(280, 400, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(android.graphics.Color.WHITE)
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            pinch(
                start0 = center - androidx.compose.ui.geometry.Offset(80f, 0f),
                end0 = center - androidx.compose.ui.geometry.Offset(260f, 0f),
                start1 = center + androidx.compose.ui.geometry.Offset(80f, 0f),
                end1 = center + androidx.compose.ui.geometry.Offset(260f, 0f),
                durationMillis = 700,
            )
        }
        compose.waitForIdle()
        val zoomTexts =
            compose.onAllNodes(hasText("%", substring = true)).fetchSemanticsNodes().mapNotNull {
                runCatching {
                        it.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                            .joinToString("") { text -> text.text }
                    }
                    .getOrNull()
            }
        assertTrue("双指手势后应保持放大状态，实际 $zoomTexts", zoomTexts.any { it != "100%" })
    }

    @Test
    fun textFindCanNavigateToLaterLazyChunk() {
        val file =
            File(context.cacheDir, "synthetic-find.txt").apply {
                writeText("开头\n" + "普通内容\n".repeat(2200) + "唯一目标词\n")
            }
        try {
            compose.setContent { FuXiaoXueTheme { TextPreviewScreen(file, true) } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("查找正文").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("查找正文").performTextInput("唯一目标词")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("1 / 1").fetchSemanticsNodes().isNotEmpty()
            }
            compose
                .onNode(hasText("唯一目标词", substring = true) and !hasSetTextAction())
                .assertExists()
            compose.onNodeWithContentDescription("下一处匹配").assertIsEnabled()
            capture("text-find")
        } finally {
            file.delete()
        }
    }

    @Test
    fun mediaResumesOnceAndStopReportsBeginning() {
        val file = File(context.cacheDir, "synthetic-silence.wav")
        val samples = 8000 * 30
        val header =
            ByteBuffer.allocate(44)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put("RIFF".toByteArray())
                    putInt(36 + samples * 2)
                    put("WAVEfmt ".toByteArray())
                    putInt(16)
                    putShort(1)
                    putShort(1)
                    putInt(8000)
                    putInt(16000)
                    putShort(2)
                    putShort(16)
                    put("data".toByteArray())
                    putInt(samples * 2)
                }
                .array()
        file.outputStream().use {
            it.write(header)
            it.write(ByteArray(samples * 2))
        }
        val progress = AtomicInteger(-1)
        try {
            compose.setContent {
                FuXiaoXueTheme {
                    MediaPreviewScreen(file, 12) { seconds, _ -> progress.set(seconds) }
                }
            }
            compose.waitUntil(15_000) {
                compose.onAllNodesWithContentDescription("播放").fetchSemanticsNodes().any {
                    it.config
                        .contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                        .not()
                } && progress.get() >= 9
            }
            compose.onNodeWithContentDescription("后退十秒").assertIsEnabled()
            compose.onNodeWithContentDescription("前进十秒").assertIsEnabled()
            compose.onNodeWithContentDescription("音量").assertExists()
            compose.onNodeWithContentDescription("单曲循环").performClick().assertIsOn()
            capture("media")
            compose.onNodeWithContentDescription("停止并回到开头").performClick()
            compose.waitUntil(10_000) { progress.get() == 0 }
        } finally {
            file.delete()
        }
    }

    @Test
    fun imageZoomIsVisibleAndResettable() {
        val file = File(context.cacheDir, "synthetic-image.png")
        Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(android.graphics.Color.rgb(79, 70, 229)) }
            .let { bitmap ->
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        try {
            compose.setContent { FuXiaoXueTheme { ImagePreviewScreen(file) } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("放大页面").fetchSemanticsNodes().any {
                    !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                }
            }
            compose.onNodeWithContentDescription("放大页面").performClick()
            compose.onNodeWithText("125%").assertExists()
            compose.onNodeWithText("适合宽度").performClick()
            compose.onNodeWithText("100%").assertExists()
            capture("image")
        } finally {
            file.delete()
        }
    }
}
