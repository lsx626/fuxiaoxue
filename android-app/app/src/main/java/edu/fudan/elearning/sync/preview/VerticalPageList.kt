package edu.fudan.elearning.sync.preview

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一的「纵向连续滚动」页面列表（下拉式阅读体验）：PDF、Office 共用。
 *
 * - 每页一个 LazyColumn 条目，进入可视区时才在 IO 线程渲染位图；
 * - 位图走 [PageBitmapCache]（按字节数预算的 LRU），当前展示中的页被钉住不会被回收；
 * - 缩放状态上提到列表级（按页号记忆）：双指缩放（1x~[maxZoom]）、双击以**点击点为锚点**
 *   在 1x/2x 间切换，底部控制条还提供 ± 按钮与百分比，缩放不再只靠手势；
 * - 底部常驻控制条：跳页 / 回页首 / 缩放 / 重置——滚动时也不会消失；
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
    header: (@Composable () -> Unit)? = null,
    /** v1.1.2：打开时恢复到的页码（-1 不恢复）。 */
    initialPage: Int = -1,
    /** v1.1.2：主显示页变化时回调（ViewModel 落库阅读进度）。 */
    onPageChanged: (page: Int, total: Int) -> Unit = { _, _ -> }
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

    // 列表级缩放状态：页号 -> 倍率 / 平移。手势与按钮都写这两张表，
    // 列表保留各自的缩放（历史缺陷：滚到下一页缩放丢失，只能重新捏）。
    val zoomScales = remember { mutableStateMapOf<Int, Float>() }
    val zoomOffsets = remember { mutableStateMapOf<Int, Offset>() }

    // 当前主显示页（可见度最高的页条目）：± 缩放按钮作用于它
    val currentPage by remember {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .filter { it.key is Int }
                .maxByOrNull { it.size - kotlin.math.abs(it.offset) }
                ?.key as? Int ?: 0
        }
    }

    // LazyColumn 在页条目之前还有固定条目：可选的 header（跳页/恢复都要算上偏移）
    val headerOffset = if (header != null) 1 else 0

    // v1.1.2：进入时恢复到上次阅读的页（header 偏移量一并计算）
    LaunchedEffect(pageCount, initialPage) {
        if (initialPage in 0 until pageCount) {
            // 等首帧布局完成，否则 scrollToItem 算不出位置
            listState.scrollToItem(headerOffset + initialPage)
        }
    }

    // v1.1.2：主显示页变化时上报（500ms 防抖，快速连续滑动只落一次库）
    LaunchedEffect(currentPage, pageCount) {
        if (pageCount > 0) {
            kotlinx.coroutines.delay(500)
            onPageChanged(currentPage, pageCount)
        }
    }

    Column(modifier.fillMaxSize().background(Color(0xFFEDEEF2))) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            if (header != null) {
                item { header() }
            }
            items((0 until pageCount).toList(), key = { it }) { page ->
                PageRow(
                    pageIndex = page,
                    totalPages = pageCount,
                    aspect = aspectOf(page),
                    cache = cache,
                    renderPage = renderPage,
                    maxZoom = maxZoom,
                    zoomScales = zoomScales,
                    zoomOffsets = zoomOffsets,
                    onPageIndicatorClick = { showJumpDialog = true }
                )
            }
        }
        // 底部常驻控制条：旧版在列表顶部、一滚动就消失（「不好用」的根因）
        Surface(
            Modifier.fillMaxWidth(),
            shadowElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { showJumpDialog = true }) {
                    Text(stringResource(R.string.preview_jump_button))
                }
                TextButton(onClick = {
                    scope.launch { listState.scrollToItem(0) }
                }) {
                    Text(stringResource(R.string.preview_back_top))
                }
                // 缩放按钮组：对当前主显示页 +-，倍率即时可读
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = (zoomScales[currentPage] ?: 1f) > 1f,
                        onClick = { zoomStep(currentPage, -0.25f, zoomScales, zoomOffsets, maxZoom) }
                    ) { Text("−", fontWeight = FontWeight.Bold) }
                    Text(
                        "${((zoomScales[currentPage] ?: 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        enabled = (zoomScales[currentPage] ?: 1f) < maxZoom,
                        onClick = { zoomStep(currentPage, 0.25f, zoomScales, zoomOffsets, maxZoom) }
                    ) { Text("+", fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = {
                    zoomScales.clear()
                    zoomOffsets.clear()
                }) {
                    Text(stringResource(R.string.preview_zoom_reset))
                }
            }
        }
    }

    if (showJumpDialog) {
        var pageText by remember { mutableStateOf("") }
        // 滑杆与输入框共享状态：输入数字时滑杆跟随，确认时优先取输入框
        var sliderValue by remember(pageCount) {
            mutableFloatStateOf(1f)
        }
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text(stringResource(R.string.preview_jump_page, pageCount)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { newText ->
                            pageText = newText.filter { ch -> ch.isDigit() }
                            pageText.toIntOrNull()?.let {
                                sliderValue = it.coerceIn(1, pageCount).toFloat()
                            }
                        },
                        label = { Text(stringResource(R.string.preview_jump_hint)) },
                        singleLine = true
                    )
                    Spacer(Modifier.size(8.dp))
                    Slider(
                        value = sliderValue,
                        onValueChange = { sliderValue = it },
                        valueRange = 1f..pageCount.toFloat(),
                        steps = 0
                    )
                    Text(
                        "第 ${sliderValue.toInt()} 页 / 共 $pageCount 页",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val page = (pageText.toIntOrNull() ?: sliderValue.toInt())
                        .coerceIn(1, pageCount)
                    scope.launch { listState.scrollToItem(0 + page - 1) }
                    showJumpDialog = false
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

/** 缩放按钮的步进：保持锚点在页面中心，回 1x 时清平移。 */
private fun zoomStep(
    page: Int,
    delta: Float,
    scales: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Float>,
    offsets: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Offset>,
    maxZoom: Float
) {
    val current = scales[page] ?: 1f
    val next = (current + delta).coerceIn(1f, maxZoom)
    scales[page] = next
    if (next <= 1f) offsets.remove(page) else offsets[page] = offsets[page] ?: Offset.Zero
}

@Composable
private fun PageRow(
    pageIndex: Int,
    totalPages: Int,
    aspect: Float,
    cache: PageBitmapCache,
    renderPage: suspend (Int) -> Bitmap?,
    maxZoom: Float,
    zoomScales: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Float>,
    zoomOffsets: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, Offset>,
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
    // pin/unpin 只在进入/离开组合时执行一次（DisposableEffect），不随重绘反复触发
    DisposableEffect(pageIndex) {
        cache.pin(pageIndex)
        onDispose { cache.unpin(pageIndex) }
    }

    val scale = zoomScales[pageIndex] ?: 1f
    val offset = zoomOffsets[pageIndex] ?: Offset.Zero
    var boxSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

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
                    onScale = { zoomScales[pageIndex] = it },
                    onOffset = { zoomOffsets[pageIndex] = it },
                    onDoubleTap = { tap ->
                        // 以点击点为锚点缩放：放大后手指下仍是原来的内容
                        val center = boxSize.let { Offset(it.width / 2f, it.height / 2f) }
                        if (scale > 1f) {
                            zoomScales[pageIndex] = 1f
                            zoomOffsets.remove(pageIndex)
                        } else {
                            zoomScales[pageIndex] = 2f
                            // 推导：graphicsLayer(s, T) 把内容点 p 显示在 c + s(p-c) + T；
                            // 要求点击点 t 处的内容仍在 t，得 T = (t - c)(1 - s)
                            zoomOffsets[pageIndex] = (tap - center) * (1f - 2f)
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
 * 双击以**点击点**为锚点在 1x / 2x 间切换（[onDoubleTap] 收到的是组件内坐标）。
 */
private fun Modifier.pinchZoom(
    scaleProvider: () -> Float,
    offsetProvider: () -> Offset,
    sizeProvider: () -> IntSize,
    maxZoom: Float,
    onScale: (Float) -> Unit,
    onOffset: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { tap -> onDoubleTap(tap) })
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
                            val center = sizeProvider().let {
                                Offset(it.width / 2f, it.height / 2f)
                            }
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
