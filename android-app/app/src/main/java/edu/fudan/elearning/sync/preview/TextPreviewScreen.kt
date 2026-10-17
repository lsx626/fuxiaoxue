package edu.fudan.elearning.sync.preview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.ui.*
import edu.fudan.elearning.sync.util.DocumentTexts
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Bounded decoding and lazy text blocks keep large notes/code responsive. */
@Composable
fun TextPreviewScreen(file: File, showFind: Boolean = false) {
    val document by
        loadResource(file.absolutePath, 0) {
            withContext(Dispatchers.IO) { DocumentTexts.read(file, 2 * 1024 * 1024) }
        }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var query by remember(file.absolutePath) { mutableStateOf("") }
    var selectedMatch by remember(query) { mutableIntStateOf(0) }
    var pendingMatch by remember(query) { mutableIntStateOf(-1) }
    when (val loaded = document) {
        Resource.Loading -> LoadingState(stringResource(R.string.text_loading))
        Resource.Failed -> PreviewError(stringResource(R.string.text_read_error))
        is Resource.Ready -> {
            val content = loaded.value
            val chunks = remember(content.text) { DocumentTexts.chunks(content.text) }
            val layouts = remember(content.text) { mutableStateMapOf<Int, TextLayoutResult>() }
            val matches by
                produceState<List<Int>>(emptyList(), content.text, query) {
                    value =
                        if (query.isBlank()) emptyList()
                        else
                            withContext(Dispatchers.Default) {
                                buildList {
                                    var offset = 0
                                    while (offset < content.text.length && size < 1000) {
                                        val found =
                                            content.text.indexOf(query, offset, ignoreCase = true)
                                        if (found < 0) break
                                        add(found)
                                        offset = found + query.length
                                    }
                                }
                            }
                }
            fun jump(delta: Int) {
                if (matches.isEmpty()) return
                selectedMatch = (selectedMatch + delta + matches.size) % matches.size
                val offset = matches[selectedMatch]
                pendingMatch = offset
                val chunk = chunks.indexOfLast { it.start <= offset }.coerceAtLeast(0)
                scope.launch { list.scrollToItem(chunk) }
            }
            LaunchedEffect(matches) { if (matches.isNotEmpty()) jump(0) }
            val matchChunk =
                chunks.indexOfLast { it.start <= pendingMatch }.takeIf { pendingMatch >= 0 }
            LaunchedEffect(pendingMatch, matchChunk?.let { layouts[chunks[it].start] }) {
                if (pendingMatch < 0 || matchChunk == null) return@LaunchedEffect
                val chunk = chunks[matchChunk]
                val layout = layouts[chunk.start] ?: return@LaunchedEffect
                val localOffset =
                    (pendingMatch - chunk.start).coerceIn(0, chunk.text.lastIndex.coerceAtLeast(0))
                val top = layout.getLineTop(layout.getLineForOffset(localOffset)).toInt()
                list.scrollToItem(matchChunk, top)
                pendingMatch = -1
            }
            Column(Modifier.fillMaxSize()) {
                if (showFind)
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        LibrarySearchField(
                            query,
                            { query = it },
                            stringResource(R.string.text_find),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                if (matches.size >= 1000)
                                    stringResource(R.string.text_matches_limit)
                                else
                                    "${if (matches.isEmpty()) 0 else selectedMatch + 1} / ${matches.size}",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            IconButton({ jump(-1) }, enabled = matches.isNotEmpty()) {
                                Icon(
                                    Icons.Default.KeyboardArrowUp,
                                    stringResource(R.string.text_previous_match),
                                )
                            }
                            IconButton({ jump(1) }, enabled = matches.isNotEmpty()) {
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    stringResource(R.string.text_next_match),
                                )
                            }
                        }
                    }
                if (content.truncated)
                    Text(
                        stringResource(R.string.text_truncated),
                        Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    if (chunks.isEmpty())
                        item {
                            Text(
                                stringResource(R.string.text_empty),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    items(chunks, key = { it.start }) { chunk ->
                        DisposableEffect(chunk.start) { onDispose { layouts.remove(chunk.start) } }
                        val highlight = MaterialTheme.colorScheme.tertiaryContainer
                        val highlightText = MaterialTheme.colorScheme.onTertiaryContainer
                        val text =
                            remember(chunk, query, highlight) {
                                buildAnnotatedString {
                                    append(chunk.text)
                                    if (query.isNotBlank()) {
                                        var offset = 0
                                        while (offset < chunk.text.length) {
                                            val found = chunk.text.indexOf(query, offset, true)
                                            if (found < 0) break
                                            addStyle(
                                                SpanStyle(
                                                    background = highlight,
                                                    color = highlightText,
                                                ),
                                                found,
                                                found + query.length,
                                            )
                                            offset = found + query.length
                                        }
                                    }
                                }
                            }
                        SelectionContainer {
                            Text(
                                text,
                                style =
                                    MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                modifier = Modifier.fillMaxWidth(),
                                onTextLayout = { layouts[chunk.start] = it },
                            )
                        }
                    }
                }
            }
        }
    }
}
