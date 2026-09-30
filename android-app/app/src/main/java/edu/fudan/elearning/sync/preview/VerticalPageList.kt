package edu.fudan.elearning.sync.preview

import android.graphics.Bitmap
import android.util.LruCache
import edu.fudan.elearning.sync.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一的「纵向连续滚动」页面列表（下拉式漫画阅读体验）：PDF、Office 共用。
 *
 * - 每页一个 LazyColumn 条目，进入可视区时才在 IO 线程渲染位图；
 * - 位图走 [PageBitmapCache]（按字节数预算的 LRU），当前展示中的页被钉住不会被回收；
 * - 每页支持双指缩放（1x~[maxZoom]）与双击切换 1x/2x；缩放为 1x 时不消费单指手势，
 *   由外层 LazyColumn 处理纵向滚动，从而避免缩放与滚动手势冲突；
 * - 页脚显示「第 N / M 页」。
 */
@Composable
fun VerticalPageList(
    pageCount: Int,
    /** 页面宽高比，用于位图到达前的占位高度，避免布局跳动。 */
    aspectOf: (Int) -> Float,
    /** 在 IO 线程渲染指定页为位图；失败返回 null。 */
    renderPage: suspend (Int) -> Bitmap?,
    modifier: Modifier = Modifier,
    maxZoom: Float = 4f,
    /** 列表顶部额外内容（如保真度说明卡），可为空。 */
    header: (@Composable () -> Unit)? = null
) {
    if (pageCount <= 0) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val listState = rememberLazyListState()
    val cache = remember { PageBitmapCache() }
    val scope = rememberCoroutineScope()
    var showJumpDialog by remember { mutableStateOf(false) }
    // 全局缩放置位：递增后各页恢复 1x 并清除平移
    var zoomResetToken by remember { mutableStateOf(0) }
    // LazyColumn 在页条目之前还有固定条目：可选的 header + 控制按钮行。
    // 跳到第 N 页必须跳过这些固定条目，否则少算 1~2 项。
    val fixedItemCount = (if (header != null) 1 else 0) + 1

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(Color(0xFFEDEEF2)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        if (header != null) {
            item { header() }
        }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showJumpDialog = true }) {
                    Text(stringResource(R.string.preview_jump_button))
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = {
                    scope.launch { listState.scrollToItem(0) }
                }) {
                    Text(stringResource(R.string.preview_back_top))
                }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { zoomResetToken += 1 }) {
                    Text(stringResource(R.string.preview_zoom_reset))
                }
            }
        }
        items((0 until pageCount).toList(), key = { it }) { page ->
            PageRow(
                pageIndex = page,
                totalPages = pageCount,
                aspect = aspectOf(page),
                cache = cache,
                renderPage = renderPage,
                maxZoom = maxZoom,
                zoomResetToken = zoomResetToken,
                onPageIndicatorClick = { showJumpDialog = true }
            )
        }
    }

    if (showJumpDialog) {
        var pageText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text(stringResource(R.string.preview_jump_page, pageCount)) },
            text = {
                OutlinedTextField(
                    value = pageText,
                    onValueChange = { pageText = it.filter { ch -> ch.isDigit() } },
                    label = { Text(stringResource(R.string.preview_jump_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val page = pageText.toIntOrNull()
                    if (page != null && page in 1..pageCount) {
                        // 列表前有固定条目（header + 控制行），页码必须换算成列表索引
                        scope.launch { listState.scrollToItem(fixedItemCount + page - 1) }
                        showJumpDialog = false
                    }
                }) {
                    Text(stringResource(R.string.preview_jump_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text(stringResource(R.string.preview_cancel))
                }
            }
        )
    }
}

@Composable
private fun PageRow(
    pageIndex: Int,
    totalPages: Int,
    aspect: Float,
    cache: PageBitmapCache,
    renderPage: suspend (Int) -> Bitmap?,
    maxZoom: Float,
    zoomResetToken: Int,
    onPageIndicatorClick: () -> Unit
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(cache[pageIndex]) }
    var failed by remember(pageIndex) { mutableStateOf(false) }

    // 进入可视区时渲染；命中缓存则直接展示
    LaunchedEffect(pageIndex) {
        if (bitmap == null && !failed) {
            val rendered = withContext(Dispatchers.IO) { renderPage(pageIndex) }
            if (rendered != null) {
                cache.put(pageIndex, rendered)
                bitmap = rendered
            } else {
                failed = true
            }
        }
    }
    // 展示期间钉住该页，避免被 LRU 回收导致绘制到已回收位图
    DisposableEffect(pageIndex) {
        cache.pin(pageIndex)
        onDispose { cache.unpin(pageIndex) }
    }

    var scale by remember(pageIndex) { mutableStateOf(1f) }
    var offset by remember(pageIndex) { mutableStateOf(Offset.Zero) }
    var boxSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

    // 「重置缩放」：由父组件递增 token 触发，恢复到 1x 并清除平移
    LaunchedEffect(zoomResetToken) {
        if (zoomResetToken > 0) {
            scale = 1f
            offset = Offset.Zero
        }
    }

    Column {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspect.coerceAtLeast(0.1f))
                .clip(RoundedCornerShape(6.dp)).background(Color.White)
                .onSizeChanged { boxSize = it }
                .pinchZoom(
                    scaleProvider = { scale },
                    offsetProvider = { offset },
                    sizeProvider = { boxSize },
                    maxZoom = maxZoom,
                    onScale = { scale = it },
                    onOffset = { offset = it },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 2f; offset = Offset.Zero
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            when {
                bmp != null -> Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "第 ${pageIndex + 1} 页",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().graphicsLayer(
                        scaleX = scale, scaleY = scale,
                        translationX = offset.x, translationY = offset.y
                    )
                )
                failed -> Text(
                    "该页无法渲染",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }
        }
        Text(
            stringResource(R.string.preview_page_indicator, pageIndex + 1, totalPages),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .clickable(onClick = onPageIndicatorClick)
                .padding(4.dp)
        )
    }
}

/**
 * 双指缩放 + 单指平移手势，处理与 LazyColumn 纵向滚动的冲突：
 * - 两指：缩放并以双指中心为锚点平移；
 * - 单指且已缩放（>1x）：平移；
 * - 单指且未缩放：不消费事件，交给 LazyColumn 滚动。
 * 双击在 1x / 2x 间切换。
 */
private fun Modifier.pinchZoom(
    scaleProvider: () -> Float,
    offsetProvider: () -> Offset,
    sizeProvider: () -> IntSize,
    maxZoom: Float,
    onScale: (Float) -> Unit,
    onOffset: (Offset) -> Unit,
    onDoubleTap: () -> Unit
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { onDoubleTap() })
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var startScale = 0f
            var startOffset = Offset.Zero
            var startCentroid = Offset.Zero
            var accZoom = 1f
            do {
                val event = awaitPointerEvent()
                val changes = event.changes
                if (changes.size >= 2) {
                    changes.forEach { it.consume() }
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (startScale == 0f) {
                        startScale = scaleProvider()
                        startOffset = offsetProvider()
                        startCentroid = centroid
                    } else {
                        accZoom *= event.calculateZoom()
                        val newScale = (startScale * accZoom).coerceIn(1f, maxZoom)
                        onScale(newScale)
                        if (newScale <= 1f) {
                            onOffset(Offset.Zero)
                        } else {
                            // graphicsLayer 以组件中心为原点缩放；让手势开始时双指中心
                            // 对准的内容点始终落在当前双指中心，实现以手指为中心缩放。
                            val center = sizeProvider().let { Offset(it.width / 2f, it.height / 2f) }
                            val ratio = newScale / startScale
                            onOffset(centroid - center - (startCentroid - center - startOffset) * ratio)
                        }
                    }
                } else if (changes.size == 1) {
                    val change = changes.first()
                    if (scaleProvider() > 1f) {
                        change.consume()
                        onOffset(offsetProvider() + change.positionChange())
                    }
                    // 未缩放时不消费，LazyColumn 正常滚动
                }
            } while (changes.any { it.pressed })
        }
    }
