package edu.fudan.elearning.sync.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import edu.fudan.elearning.sync.BuildConfig
import edu.fudan.elearning.sync.R

@Composable
internal fun SettingsScreen(viewModel: AppViewModel) {
    val settings by viewModel.settings.collectAsState()
    val login by viewModel.loginState.collectAsState()
    val offline by viewModel.needsReauth.collectAsState()
    val profileName by viewModel.profileName.collectAsState()
    val avatarUrl by viewModel.profileAvatarUrl.collectAsState()
    val accountId = (login as? LoginState.LoggedIn)?.username.orEmpty()
    val version by viewModel.dataVersion.collectAsState()
    val lastSummary by loadResource("sync-summary", version) { viewModel.lastSyncSummary() }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showInterval by rememberSaveable { mutableStateOf(false) }
    var showGuide by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        Modifier.fillMaxSize().testTag("settings-list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            ScreenHeading(
                stringResource(R.string.screen_settings),
                stringResource(R.string.settings_privacy),
            )
        }
        item { SectionLabel(stringResource(R.string.settings_account)) }
        item {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            if (avatarUrl.isNotBlank())
                                AsyncImage(
                                    model = avatarUrl,
                                    contentDescription =
                                        stringResource(R.string.settings_profile_photo),
                                    modifier = Modifier.size(52.dp),
                                )
                            else
                                Icon(
                                    Icons.Default.Person,
                                    null,
                                    Modifier.padding(14.dp).size(24.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                profileName.ifBlank {
                                    (login as? LoginState.LoggedIn)?.username.orEmpty()
                                },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                accountId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                stringResource(
                                    if (offline) R.string.settings_offline
                                    else R.string.settings_online
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (offline)
                            TextButton(viewModel::requestReauth) {
                                Text(stringResource(R.string.action_sign_in))
                            }
                        TextButton(onClick = { confirmLogout = true }) {
                            Text(
                                stringResource(R.string.settings_logout),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_sync)) }
        item {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                Column {
                    Row(
                        Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                stringResource(R.string.settings_background),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                stringResource(
                                    if (settings.passwordRemembered)
                                        R.string.settings_background_hint
                                    else R.string.settings_background_no_password
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = settings.backgroundSync && settings.passwordRemembered,
                            onCheckedChange = viewModel::setBackgroundSync,
                            enabled = settings.passwordRemembered,
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                    SettingsRow(
                        stringResource(R.string.settings_interval),
                        stringResource(R.string.settings_minutes, settings.syncInterval),
                    ) {
                        showInterval = true
                    }
                }
            }
        }
        item {
            when (val result = lastSummary) {
                Resource.Loading -> LoadingState(stringResource(R.string.loading_library))
                Resource.Failed ->
                    Text(
                        stringResource(R.string.library_load_error),
                        color = MaterialTheme.colorScheme.error,
                    )
                is Resource.Ready ->
                    Text(
                        result.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
        item { SectionLabel(stringResource(R.string.settings_appearance)) }
        item {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                SettingsRow(
                    stringResource(R.string.settings_theme),
                    stringResource(themeLabel(settings.theme)),
                ) {
                    showTheme = true
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_location)) }
        item {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        viewModel.downloadRoot,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.settings_location_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_help)) }
        item {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                SettingsRow(
                    stringResource(R.string.settings_user_guide),
                    stringResource(R.string.settings_user_guide_hint),
                    modifier = Modifier.testTag("settings-user-guide"),
                ) {
                    showGuide = true
                }
            }
        }
        item { SectionLabel(stringResource(R.string.settings_about)) }
        item {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                SettingsRow(
                    stringResource(R.string.settings_check_update),
                    stringResource(R.string.settings_check_update_hint),
                ) {
                    viewModel.checkForUpdates()
                }
            }
        }
        item {
            Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0f)) {
                Column(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.about_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_features),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_copyright),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.about_license),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (showTheme)
        ChoiceDialog(
            stringResource(R.string.settings_theme),
            ThemeMode.entries.map { stringResource(themeLabel(it)) },
            settings.theme.ordinal,
            {
                viewModel.setTheme(ThemeMode.entries[it])
                showTheme = false
            },
            { showTheme = false },
        )
    val intervals = listOf(15, 30, 60, 120)
    if (showInterval)
        ChoiceDialog(
            stringResource(R.string.settings_interval),
            intervals.map { stringResource(R.string.settings_minutes, it) },
            intervals.indexOf(settings.syncInterval),
            {
                viewModel.setSyncInterval(intervals[it])
                showInterval = false
            },
            { showInterval = false },
        )
    if (showGuide) UserGuideSheet { showGuide = false }
    if (confirmLogout)
        ConfirmActionDialog(
            stringResource(R.string.logout_title),
            stringResource(R.string.logout_hint),
            stringResource(R.string.settings_logout),
            {
                confirmLogout = false
                viewModel.logout()
            },
            { confirmLogout = false },
        )
}

internal fun themeLabel(mode: ThemeMode): Int =
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Default.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserGuideSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.guide_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            GuideSection(R.string.guide_login_title, R.string.guide_login_body)
            GuideSection(R.string.guide_sync_scope_title, R.string.guide_sync_scope_body)
            GuideSection(R.string.guide_sync_title, R.string.guide_sync_body)
            GuideSection(R.string.guide_read_title, R.string.guide_read_body)
            GuideSection(R.string.guide_task_title, R.string.guide_task_body)
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun GuideSection(title: Int, body: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChoiceDialog(
    title: String,
    choices: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                choices.forEachIndexed { index, label ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { onSelect(index) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected == index, onClick = { onSelect(index) })
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
