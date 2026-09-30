package edu.fudan.elearning.sync.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

/**
 * 应用内图片预览：Coil 解码，覆盖 PNG/JPEG/GIF 动图/WebP/HEIC 等格式。
 *
 * 支持双指缩放（1x~5x）与拖动，双击在 1x/2x 间切换并复位位置；超大图由 Coil
 * 自动降采样，避免 OOM。解码失败时显示结构化错误，不跳转第三方应用。
 */
@Composable
fun ImagePreviewScreen(file: File) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier.fillMaxSize().background(Color(0xFF2A2A2E))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2f
                        offset = Offset.Zero
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    offset = if (newScale > 1f) {
                        Offset(offset.x + pan.x, offset.y + pan.y)
                    } else Offset.Zero
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // v1.2.1：解码失败不能只显示纯黑背景——给 error 占位与明确文案
        var loadFailed by remember(file.absolutePath) { mutableStateOf(false) }
        if (loadFailed) {
            PreviewError(
                "图片解码失败：文件可能已损坏，或当前设备不支持该格式" +
                    "（如部分 HEIF/AVIF 变体），可通过分享交给其他应用查看。",
                title = "无法显示图片"
            )
        } else {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(file)
                    .crossfade(true)
                    .build(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                onError = { loadFailed = true },
                error = null,
                modifier = Modifier.fillMaxSize().graphicsLayer(
                    scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y
                )
            )
        }
    }
}