package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import edu.fudan.elearning.sync.util.FileUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(viewModel: AppViewModel) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val error by viewModel.searchError.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(query) { list.scrollToItem(0) }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.search_heading), style = MaterialTheme.typography.titleLarge) },
        navigationIcon = { IconButton(viewModel::closeSearch) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.search_back)) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)) },
        containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.search_subtitle), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
            LibrarySearchField(query, viewModel::updateSearchQuery, stringResource(R.string.search_hint))
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("search-results"), contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    searching -> item { LoadingState(stringResource(R.string.search_loading)) }
                    error != null -> item { Notice(error!!, stringResource(R.string.action_retry), { viewModel.updateSearchQuery(query) }, error = true) }
                    query.isBlank() -> item { EmptyState(stringResource(R.string.search_empty), stringResource(R.string.search_empty_hint)) }
                    results.isEmpty() -> item { EmptyState(stringResource(R.string.search_no_results), stringResource(R.string.search_no_results_hint)) }
                    else -> item { SectionLabel(stringResource(R.string.search_result_count, results.size)) }
                }
                items(results, key = { it.fileId }) { hit ->
                    Surface(Modifier.fillMaxWidth().clickable { viewModel.openSearchResult(hit) },
                        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                DocumentBadge(hit.filename.ifBlank { hit.name })
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(hit.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(FileUtils.cleanCourseName(hit.courseName), style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (hit.snippet.isNotBlank()) Text(hit.snippet, style = MaterialTheme.typography.bodySmall,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
