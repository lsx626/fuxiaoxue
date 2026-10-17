package edu.fudan.elearning.sync.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * 预览界面用 Canvas 自绘图标（播放控制、音量、循环、错误提示）。
 *
 * 不依赖 material-icons-extended 这类重型扩展包，用最少的矢量绘制保证 图形清晰且可随主题着色；每个图标都带语义化 contentDescription 由调用方传入。
 */
@Composable
fun PlayIcon(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path =
            Path().apply {
                moveTo(w * 0.28f, h * 0.18f)
                lineTo(w * 0.82f, h * 0.50f)
                lineTo(w * 0.28f, h * 0.82f)
                close()
            }
        drawPath(path, tint)
    }
}

@Composable
fun PauseIcon(tint: Color, modifier: Modifier = Modifier.size(28.dp)) {
    Canvas(modifier) {
        val w = size.width
        val barW = w * 0.22f
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.26f, size.height * 0.18f),
            size = Size(barW, size.height * 0.64f),
        )
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.52f, size.height * 0.18f),
            size = Size(barW, size.height * 0.64f),
        )
    }
}

@Composable
fun StopIcon(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        drawRect(
            color = tint,
            topLeft = Offset(size.width * 0.24f, size.height * 0.24f),
            size = Size(size.width * 0.52f, size.height * 0.52f),
        )
    }
}

@Composable
fun SkipBackwardIcon(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tri =
            Path().apply {
                moveTo(w * 0.42f, h * 0.5f)
                lineTo(w * 0.80f, h * 0.20f)
                lineTo(w * 0.80f, h * 0.80f)
                close()
            }
        drawPath(tri, tint)
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.22f),
            size = Size(w * 0.12f, h * 0.56f),
        )
    }
}

@Composable
fun SkipForwardIcon(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val tri =
            Path().apply {
                moveTo(w * 0.58f, h * 0.5f)
                lineTo(w * 0.20f, h * 0.20f)
                lineTo(w * 0.20f, h * 0.80f)
                close()
            }
        drawPath(tri, tint)
        drawRect(
            color = tint,
            topLeft = Offset(w * 0.70f, h * 0.22f),
            size = Size(w * 0.12f, h * 0.56f),
        )
    }
}

@Composable
fun LoopIcon(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.09f)
        // 上方箭头线
        drawLine(
            color = tint,
            start = Offset(w * 0.18f, h * 0.42f),
            end = Offset(w * 0.74f, h * 0.42f),
            strokeWidth = stroke.width,
        )
        drawPath(
            path =
                Path().apply {
                    moveTo(w * 0.62f, h * 0.30f)
                    lineTo(w * 0.80f, h * 0.42f)
                    lineTo(w * 0.62f, h * 0.54f)
                    close()
                },
            color = tint,
        )
        // 下方箭头线
        drawLine(
            color = tint,
            start = Offset(w * 0.82f, h * 0.58f),
            end = Offset(w * 0.26f, h * 0.58f),
            strokeWidth = stroke.width,
        )
        drawPath(
            path =
                Path().apply {
                    moveTo(w * 0.38f, h * 0.70f)
                    lineTo(w * 0.20f, h * 0.58f)
                    lineTo(w * 0.38f, h * 0.46f)
                    close()
                },
            color = tint,
        )
    }
}

@Composable
fun VolumeLowIcon(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val body =
            Path().apply {
                moveTo(w * 0.16f, h * 0.40f)
                lineTo(w * 0.34f, h * 0.40f)
                lineTo(w * 0.54f, h * 0.22f)
                lineTo(w * 0.54f, h * 0.78f)
                lineTo(w * 0.34f, h * 0.60f)
                lineTo(w * 0.16f, h * 0.60f)
                close()
            }
        drawPath(body, tint)
        drawArc(
            color = tint,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.56f, h * 0.32f),
            size = Size(w * 0.28f, h * 0.36f),
            style = Stroke(width = w * 0.08f),
        )
    }
}

@Composable
fun VolumeHighIcon(tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    VolumeLowIcon(tint, modifier)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawArc(
            color = tint,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.72f, h * 0.24f),
            size = Size(w * 0.22f, h * 0.52f),
            style = Stroke(width = w * 0.08f),
        )
    }
}

@Composable
fun AlertIcon(tint: Color, modifier: Modifier = Modifier.size(40.dp)) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.075f)
        val triangle =
            Path().apply {
                moveTo(w * 0.5f, h * 0.08f)
                lineTo(w * 0.94f, h * 0.88f)
                lineTo(w * 0.06f, h * 0.88f)
                close()
            }
        drawPath(triangle, tint, style = stroke)
        // 感叹号竖线
        drawLine(
            color = tint,
            start = Offset(w * 0.5f, h * 0.36f),
            end = Offset(w * 0.5f, h * 0.66f),
            strokeWidth = stroke.width,
        )
        drawCircle(
            color = tint,
            radius = stroke.width * 0.55f,
            center = Offset(w * 0.5f, h * 0.76f),
        )
    }
}
