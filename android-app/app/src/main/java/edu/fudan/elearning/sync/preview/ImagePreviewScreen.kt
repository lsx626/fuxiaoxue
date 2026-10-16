package edu.fudan.elearning.sync.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import edu.fudan.elearning.sync.R
import java.io.File

/** Theme-aware image reader, with bounded pan and visible zoom controls. */
@Composable
fun ImagePreviewScreen(file: File) {
    var scale by remember(file.absolutePath) { mutableFloatStateOf(1f) }
    var offset by remember(file.absolutePath) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var loading by remember(file.absolutePath) { mutableStateOf(true) }
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    fun clamp(point: Offset, zoom: Float) = Offset(
        point.x.coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2),
        point.y.coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2))
    fun zoom(value: Float) { scale = value.coerceIn(1f, 5f); offset = clamp(offset, scale) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .onSizeChanged { size = it }
            .pointerInput(file.absolutePath) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                    else {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        scale = 2f; offset = clamp(center - tap, scale)
                    }
                })
            }.pointerInput(file.absolutePath) {
                detectTransformGestures { centroid, pan, factor, _ ->
                    val next = (scale * factor).coerceIn(1f, 5f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    offset = clamp((offset + center - centroid) * (next / scale) - center + centroid + pan, next)
                    scale = next
                }
            }, contentAlignment = Alignment.Center) {
            if (failed) PreviewError(stringResource(R.string.image_error), title = stringResource(R.string.image_error_title))
            else AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(file).crossfade(true).build(),
                contentDescription = file.name, contentScale = ContentScale.Fit,
                onLoading = { loading = true }, onSuccess = { loading = false }, onError = { loading = false; failed = true },
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale,
                    translationX = offset.x, translationY = offset.y))
            if (loading && !failed) CircularProgressIndicator()
        }
        if (!failed) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val out = stringResource(R.string.preview_zoom_out)
                val into = stringResource(R.string.preview_zoom_in)
                IconButton({ zoom(scale - .25f) }, enabled = !loading && scale > 1f,
                    modifier = Modifier.semantics { contentDescription = out }) { Text("−") }
                Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
                IconButton({ zoom(scale + .25f) }, enabled = !loading && scale < 5f,
                    modifier = Modifier.semantics { contentDescription = into }) { Text("+") }
                TextButton({ zoom(1f) }, enabled = !loading, modifier = Modifier.padding(start = 12.dp)) { Text(stringResource(R.string.preview_fit)) }
            }
        }
    }
}
