package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R
import kotlinx.coroutines.CancellationException

internal sealed interface Resource<out T> {
    data object Loading : Resource<Nothing>

    data class Ready<T>(val value: T) : Resource<T>

    data object Failed : Resource<Nothing>
}

@Composable
internal fun <T> loadResource(
    key: Any?,
    version: Int,
    retry: Int = 0,
    load: suspend () -> T,
): State<Resource<T>> =
    produceState<Resource<T>>(Resource.Loading, key, version, retry) {
        value = Resource.Loading
        try {
            value = Resource.Ready(load())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            value = Resource.Failed
        } catch (_: OutOfMemoryError) {
            value = Resource.Failed
        }
    }

@Composable
internal fun ScreenHeading(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(4.dp)
                    .height(28.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(11.dp))
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        if (trailing != null)
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
    }
}

@Composable
internal fun EmptyState(
    title: String,
    hint: String,
    icon: ImageVector = Icons.Default.Search,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Icon(
                icon,
                null,
                Modifier.padding(20.dp).size(30.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun LoadingState(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun Notice(
    message: String,
    action: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
    error: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (error) colors.errorContainer else colors.secondaryContainer,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) colors.onErrorContainer else colors.onSecondaryContainer,
                )
                if (onDismiss != null)
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.action_close),
                            Modifier.size(18.dp),
                        )
                    }
            }
            if (action != null)
                TextButton(onClick = onAction, modifier = Modifier.align(Alignment.End)) {
                    Text(action)
                }
        }
    }
}

@Composable
internal fun LibrarySearchField(
    query: String,
    onQuery: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = {
            if (query.isNotEmpty())
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Default.Close, stringResource(R.string.search_clear))
                }
        },
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors =
            OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
    )
}

@Composable
internal fun TermFilters(terms: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text(stringResource(R.string.library_all_terms)) },
            )
        }
        items(terms, key = { it }) { term ->
            FilterChip(
                selected = term == selected,
                onClick = { onSelect(term) },
                label = { Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
internal fun DocumentBadge(filename: String, modifier: Modifier = Modifier) {
    val ext = filename.substringAfterLast('.', "FILE").uppercase().take(4)
    val color =
        when (ext) {
            "PDF" -> Color(0xFFC44E49)
            "DOC",
            "DOCX" -> Color(0xFF4372C4)
            "PPT",
            "PPTX" -> Color(0xFFB96B32)
            "XLS",
            "XLSX" -> Color(0xFF23836B)
            "MP4",
            "MP3",
            "WAV" -> Color(0xFF9269C5)
            else -> MaterialTheme.colorScheme.primary
        }
    Surface(
        modifier.size(width = 48.dp, height = 52.dp),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.13f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                ext,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
internal fun StatusBadge(label: String, error: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (error) colors.errorContainer else colors.surfaceContainerLow,
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (error) colors.onErrorContainer else colors.onSurfaceVariant,
        )
    }
}

@Composable
internal fun ConfirmActionDialog(
    title: String,
    message: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirm,
                    color =
                        if (destructive) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
