package edu.fudan.elearning.sync.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.R

/** Adaptive navigation shell; feature screens own their lists, not the app's jobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: AppViewModel, sharedSnackbars: SnackbarHostState? = null) {
    val course by viewModel.selectedCourse.collectAsState()
    val syncing by viewModel.syncing.collectAsState()
    val busy by viewModel.taskBusy.collectAsState()
    val progress by viewModel.syncProgress.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    val operationError by viewModel.operationError.collectAsState()
    val needsReauth by viewModel.needsReauth.collectAsState()
    var tab by rememberSaveable { mutableStateOf(LibraryTab.COURSES) }
    var menu by remember { mutableStateOf(false) }
    var showSync by rememberSaveable { mutableStateOf(false) }
    val localSnackbars = remember { SnackbarHostState() }
    val snackbars = sharedSnackbars ?: localSnackbars
    if (sharedSnackbars == null)
        LaunchedEffect(viewModel) { viewModel.messages.collect { snackbars.showSnackbar(it) } }
    BackHandler(course != null) { viewModel.clearCourseSelection() }

    fun selectTab(next: LibraryTab) {
        viewModel.clearCourseSelection()
        tab = next
    }
    val labels =
        listOf(
            R.string.nav_courses,
            R.string.nav_activity,
            R.string.nav_storage,
            R.string.nav_settings,
        )
    val titles =
        listOf(
            R.string.screen_courses,
            R.string.screen_activity,
            R.string.screen_storage,
            R.string.screen_settings,
        )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (course == null) stringResource(titles[tab.ordinal])
                            else stringResource(R.string.screen_courses),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    navigationIcon = {
                        if (course != null)
                            IconButton(onClick = viewModel::clearCourseSelection) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    stringResource(R.string.search_back),
                                )
                            }
                        else
                            Box(Modifier.padding(start = 20.dp, end = 10.dp)) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_brand_mark),
                                        null,
                                        Modifier.padding(8.dp).size(22.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                    },
                    actions = {
                        if (tab == LibraryTab.COURSES || course != null)
                            IconButton(
                                onClick = {
                                    if (syncing || busy) showSync = true else viewModel.sync()
                                },
                                enabled = !busy,
                                modifier = Modifier.testTag("sync-icon"),
                            ) {
                                if (syncing)
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                else
                                    Icon(
                                        Icons.Default.Refresh,
                                        stringResource(R.string.action_sync_selected),
                                    )
                            }
                        IconButton(onClick = viewModel::openSearch) {
                            Icon(Icons.Default.Search, stringResource(R.string.search_open))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.menu_more))
                            }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_refresh)) },
                                    onClick = {
                                        menu = false
                                        viewModel.refreshLibrary()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.sync_title)) },
                                    onClick = {
                                        menu = false
                                        showSync = true
                                    },
                                )
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                )
            },
            bottomBar = {
                if (course == null && !wide)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp,
                    ) {
                        LibraryTab.entries.forEach { item ->
                            NavigationBarItem(
                                selected = tab == item,
                                onClick = { selectTab(item) },
                                modifier = Modifier.testTag("tab-${item.name}"),
                                icon = { TabIcon(item) },
                                label = { Text(stringResource(labels[item.ordinal])) },
                            )
                        }
                    }
            },
            snackbarHost = { if (sharedSnackbars == null) SnackbarHost(snackbars) },
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide)
                    NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                        Spacer(Modifier.height(12.dp))
                        LibraryTab.entries.forEach { item ->
                            NavigationRailItem(
                                selected = tab == item,
                                onClick = { selectTab(item) },
                                modifier = Modifier.testTag("tab-${item.name}"),
                                icon = { TabIcon(item) },
                                label = { Text(stringResource(labels[item.ordinal])) },
                            )
                        }
                    }
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 1120.dp).fillMaxSize()) {
                        if (syncing || busy || progress.phase in listOf("done", "stopped")) {
                            SyncStatusStrip(
                                progress,
                                syncing,
                                busy,
                                { showSync = true },
                                viewModel::markSyncDoneSeen,
                            )
                        }
                        if (syncError != null || needsReauth)
                            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                                Notice(
                                    syncError ?: stringResource(R.string.offline_notice),
                                    action =
                                        stringResource(
                                            if (needsReauth) R.string.action_sign_in
                                            else R.string.action_retry
                                        ),
                                    onAction = {
                                        if (needsReauth) viewModel.requestReauth()
                                        else viewModel.sync()
                                    },
                                    onDismiss =
                                        if (!needsReauth) viewModel::clearSyncError else null,
                                    error = syncError != null,
                                )
                            }
                        if (operationError != null)
                            Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                                Notice(
                                    operationError!!,
                                    onDismiss = viewModel::clearOperationError,
                                    error = true,
                                )
                            }
                        when {
                            course != null ->
                                key(course!!.course.id) { CourseFilesScreen(viewModel, course!!) }
                            tab == LibraryTab.COURSES -> CoursesScreen(viewModel)
                            tab == LibraryTab.ACTIVITY -> ActivityScreen(viewModel)
                            tab == LibraryTab.STORAGE -> StorageScreen(viewModel)
                            else -> SettingsScreen(viewModel)
                        }
                    }
                }
            }
        }
    }
    if (showSync) SyncTaskSheet(viewModel, onDismiss = { showSync = false })
}

@Composable
private fun TabIcon(tab: LibraryTab) {
    when (tab) {
        LibraryTab.COURSES -> Icon(painterResource(R.drawable.ic_nav_library), null)
        LibraryTab.ACTIVITY -> Icon(painterResource(R.drawable.ic_nav_activity), null)
        LibraryTab.STORAGE -> Icon(painterResource(R.drawable.ic_nav_storage), null)
        LibraryTab.SETTINGS -> Icon(painterResource(R.drawable.ic_nav_settings), null)
    }
}
