package edu.fudan.elearning.sync.preview

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一的「纵向连续滚动」页面列表（下拉式阅读体验）：PDF、Office 共用。
 * - 每页一个 LazyColumn 条目，进入可视区时才在 IO 线程渲染位图；
 * - 位图走 [PageBitmapCache]（按字节数预算的 LRU），当前展示中的页被钉住不会被回收；
 * - 缩放状态上提到列表级（按页号记忆）：双指缩放（1x~[maxZoom]）、双击以**点击点为锚点** 在 1x/2x 间切换，底部控制条还提供 ± 按钮与百分比，缩放不再只靠手势；
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
    onPageChanged: (page: Int, total: Int) -> Unit = { _, _ -> },
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
    var restored by remember { mutableStateOf(false) }
    val reportPage by rememberUpdatedState(onPageChanged)
    val zoomOutLabel = stringResource(R.string.preview_zoom_out)
    val zoomInLabel = stringResource(R.string.preview_zoom_in)

    // 倍率和平移必须作为一个整体写入；拆成两张表会让重组短暂读到
    // 「新倍率 + 旧平移」，松手时就会出现一帧错位。
    val transforms = remember { mutableStateMapOf<Int, PageTransform>() }

    // 当前主显示页（可见度最高的页条目）：± 缩放按钮作用于它
    val currentPage by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            dominantPage(
                info.visibleItemsInfo.mapNotNull { item ->
                    (item.key as? Int)?.let { VisiblePage(it, item.offset, item.size) }
                },
                info.viewportStartOffset,
                info.viewportEndOffset,
            )
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
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.key is Int } }.first { it }
        withFrameNanos {}
        restored = true
    }

    // v1.1.2：主显示页变化时上报（500ms 防抖，快速连续滑动只落一次库）
    LaunchedEffect(currentPage, pageCount, restored) {
        if (restored && pageCount > 0) {
            kotlinx.coroutines.delay(500)
            reportPage(currentPage, pageCount)
        }
    }
    DisposableEffect(pageCount) { onDispose { if (restored) reportPage(currentPage, pageCount) } }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (header != null) {
                item { header() }
            }
            items((0 until pageCount).toList(), key = { it }) { page ->
                PageRow(
                    pageIndex = page,
                    aspect = aspectOf(page),
                    cache = cache,
                    renderPage = renderPage,
                    maxZoom = maxZoom,
                    transforms = transforms,
                )
            }
        }
        // 底部常驻控制条：旧版在列表顶部、一滚动就消失（「不好用」的根因）
        Surface(
            Modifier.fillMaxWidth(),
            shadowElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showJumpDialog = true }) {
                        Text(
                            stringResource(
                                R.string.preview_page_position,
                                currentPage + 1,
                                pageCount,
                            )
                        )
                    }
                    TextButton(
                        onClick = { scope.launch { listState.scrollToItem(headerOffset) } }
                    ) {
                        Text(stringResource(R.string.preview_top))
                    }
                    TextButton(onClick = { transforms.clear() }) {
                        Text(stringResource(R.string.preview_fit))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Accessible, stable touch targets on narrow screens.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            enabled = (transforms[currentPage]?.scale ?: 1f) > 1f,
                            onClick = { zoomStep(currentPage, -0.25f, transforms, maxZoom) },
                            modifier = Modifier.semantics { contentDescription = zoomOutLabel },
                        ) {
                            Text("−", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "${(((transforms[currentPage]?.scale ?: 1f)) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        IconButton(
                            enabled = (transforms[currentPage]?.scale ?: 1f) < maxZoom,
                            onClick = { zoomStep(currentPage, 0.25f, transforms, maxZoom) },
                            modifier = Modifier.semantics { contentDescription = zoomInLabel },
                        ) {
                            Text("+", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showJumpDialog) {
        var pageText by remember { mutableStateOf((currentPage + 1).toString()) }
        // 滑杆与输入框共享状态：输入数字时滑杆跟随，确认时优先取输入框
        var sliderValue by remember(pageCount) { mutableFloatStateOf((currentPage + 1).toFloat()) }
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text(stringResource(R.string.preview_jump_page, pageCount)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pageText,
                        onValueChange = { newText ->
                            pageText = newText.filter { ch -> ch in '0'..'9' }.take(8)
                            pageText.toIntOrNull()?.let {
                                sliderValue = it.coerceIn(1, pageCount).toFloat()
                            }
                        },
                        label = { Text(stringResource(R.string.preview_jump_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = pageText.toIntOrNull() !in 1..pageCount,
                    )
                    Spacer(Modifier.size(8.dp))
                    if (pageCount > 1)
                        Slider(
                            value = sliderValue,
                            onValueChange = {
                                sliderValue = it
                                pageText = it.toInt().toString()
                            },
                            valueRange = 1f..pageCount.toFloat(),
                            steps = 0,
                        )
                    Text(
                        "第 ${sliderValue.toInt()} 页 / 共 $pageCount 页",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pageText.toIntOrNull() in 1..pageCount,
                    onClick = {
                        val page =
                            (pageText.toIntOrNull() ?: sliderValue.toInt()).coerceIn(1, pageCount)
                        // 与续读恢复路径一致：header 存在时目标索引要整体后移 headerOffset，
                        // 否则 Office 预览（带保真度说明 header）会恒定跳到前一格。
                        scope.launch { listState.scrollToItem(headerOffset + page - 1) }
                        showJumpDialog = false
                    },
                ) {
                    Text(stringResource(R.string.preview_jump_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showJumpDialog = false }) {
                    Text(stringResource(R.string.preview_cancel))
                }
            },
        )
    }
}

private data class PageTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/** 缩放按钮的步进：保持锚点在页面中心，回 1x 时清平移。 */
private fun zoomStep(
    page: Int,
    delta: Float,
    transforms: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, PageTransform>,
    maxZoom: Float,
) {
    val current = transforms[page] ?: PageTransform()
    val next = (current.scale + delta).coerceIn(1f, maxZoom)
    transforms[page] =
        if (next <= 1f) PageTransform()
        else
            current.copy(
                scale = next,
                offset =
                    current.offset *
                        ((next - 1f) / (current.scale - 1f).coerceAtLeast(0.001f)).coerceAtMost(1f),
            )
}

@Composable
private fun PageRow(
    pageIndex: Int,
    aspect: Float,
    cache: PageBitmapCache,
    renderPage: suspend (Int) -> Bitmap?,
    maxZoom: Float,
    transforms: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, PageTransform>,
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(cache[pageIndex]) }
    var failed by remember(pageIndex) { mutableStateOf(false) }
    var retry by remember(pageIndex) { mutableStateOf(0) }

    // 进入可视区时渲染；命中缓存则直接展示
    LaunchedEffect(pageIndex, retry) {
        if (bitmap == null && !failed) {
            val rendered = withContext(Dispatchers.IO) { renderPage(pageIndex) }
            coroutineContext.ensureActive()
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

    val transform = transforms[pageIndex] ?: PageTransform()
    val scale = transform.scale
    val offset = transform.offset
    var boxSize by remember(pageIndex) { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier.fillMaxWidth()
            .aspectRatio(aspect.coerceAtLeast(0.1f))
            .background(Color.White)
            .onSizeChanged { boxSize = it }
            .pinchZoom(
                transformProvider = { transforms[pageIndex] ?: PageTransform() },
                sizeProvider = { boxSize },
                maxZoom = maxZoom,
                onTransform = { nextScale, value ->
                    val maxX = boxSize.width * (nextScale - 1f).coerceAtLeast(0f) / 2
                    val maxY = boxSize.height * (nextScale - 1f).coerceAtLeast(0f) / 2
                    transforms[pageIndex] =
                        PageTransform(
                            scale = nextScale,
                            offset =
                                Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY)),
                        )
                },
                onDoubleTap = { tap ->
                    // 以点击点为锚点缩放：放大后手指下仍是原来的内容
                    val center = boxSize.let { Offset(it.width / 2f, it.height / 2f) }
                    if (scale > 1f) {
                        transforms[pageIndex] = PageTransform()
                    } else {
                        // 推导：graphicsLayer(s, T) 把内容点 p 显示在 c + s(p-c) + T；
                        // 要求点击点 t 处的内容仍在 t，得 T = (t - c)(1 - s)
                        val nextOffset = (tap - center) * (1f - 2f)
                        val maxX = boxSize.width / 2f
                        val maxY = boxSize.height / 2f
                        transforms[pageIndex] =
                            PageTransform(
                                scale = 2f,
                                offset =
                                    Offset(
                                        nextOffset.x.coerceIn(-maxX, maxX),
                                        nextOffset.y.coerceIn(-maxY, maxY),
                                    ),
                            )
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "第 ${pageIndex + 1} 页",
                    contentScale = ContentScale.FillWidth,
                    modifier =
                        Modifier.fillMaxWidth()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                )
            failed ->
                TextButton(
                    onClick = {
                        failed = false
                        retry++
                    }
                ) {
                    Text(stringResource(R.string.preview_page_retry), color = Color(0xFF4F46E5))
                }
            else -> CircularProgressIndicator(modifier = Modifier.padding(24.dp))
        }
    }
}

/**
 * 双指缩放 + 单指平移手势，处理与 LazyColumn 纵向滚动的冲突：
 * - 两指：缩放并以双指中心为锚点平移；
 * - 单指且已缩放（>1x）：平移；
 * - 单指且未缩放：不消费事件，交给 LazyColumn 滚动。 双击以**点击点**为锚点在 1x / 2x 间切换（[onDoubleTap] 收到的是组件内坐标）。
 */
@Composable
private fun Modifier.pinchZoom(
    transformProvider: () -> PageTransform,
    sizeProvider: () -> IntSize,
    maxZoom: Float,
    onTransform: (Float, Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
): Modifier {
    val latestTransformProvider = rememberUpdatedState(transformProvider)
    val latestSizeProvider = rememberUpdatedState(sizeProvider)
    val latestOnTransform = rememberUpdatedState(onTransform)
    val latestOnDoubleTap = rememberUpdatedState(onDoubleTap)
    return this.pointerInput(Unit) {
            detectTapGestures(onDoubleTap = { tap -> latestOnDoubleTap.value(tap) })
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var startTransform = PageTransform()
                var started = false
                var startCentroid = Offset.Zero
                var accZoom = 1f
                var pinchSeen = false
                do {
                    val event = awaitPointerEvent()
                    val changes = event.changes
                    val allPressed = changes.all { it.pressed }
                    if (changes.size >= 2 && allPressed) {
                        pinchSeen = true
                        changes.forEach { it.consume() }
                        val centroid = event.calculateCentroid(useCurrent = true)
                        if (!started) {
                            started = true
                            startTransform = latestTransformProvider.value()
                            startCentroid = centroid
                        } else {
                            accZoom *= event.calculateZoom()
                            val newScale = (startTransform.scale * accZoom).coerceIn(1f, maxZoom)
                            if (newScale <= 1f) {
                                latestOnTransform.value(1f, Offset.Zero)
                            } else {
                                // graphicsLayer 以组件中心为原点缩放；让手势开始时双指中心
                                // 对准的内容点始终落在当前双指中心，实现以手指为中心缩放。
                                val center =
                                    latestSizeProvider.value().let {
                                        Offset(it.width / 2f, it.height / 2f)
                                    }
                                val ratio = newScale / startTransform.scale
                                latestOnTransform.value(
                                    newScale,
                                    centroid -
                                        center -
                                        (startCentroid - center - startTransform.offset) * ratio,
                                )
                            }
                        }
                    } else if (changes.size == 1) {
                        val change = changes.first()
                        val current = latestTransformProvider.value()
                        if (!change.pressed || pinchSeen) {
                            // 双指缩放结束后，先抬起的那根手指会让剩余手指产生一次
                            // 很大的 positionChange；把它当平移会表现为松手瞬间跳一下。
                            // 抬手事件本身也只消费、不再写缩放状态。
                            change.consume()
                        } else if (current.scale > 1f) {
                            change.consume()
                            latestOnTransform.value(
                                current.scale,
                                current.offset + change.positionChange(),
                            )
                        }
                        // 未缩放时不消费，LazyColumn 正常滚动
                    } else if (changes.size >= 2) {
                        // 其中一根手指已经抬起：保留上一帧的变换，不再计算 zoom/centroid。
                        changes.forEach { it.consume() }
                    }
                } while (changes.any { it.pressed })
            }
        }
}
