package edu.fudan.elearning.sync.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import edu.fudan.elearning.sync.preview.PreviewScreen
import edu.fudan.elearning.sync.util.FileUtils
import edu.fudan.elearning.sync.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The same production route tree is rendered by device UI tests. */
@Composable
fun AppContent(viewModel: AppViewModel, requestNotifications: () -> Unit = {}) {
    val settings by viewModel.settings.collectAsState()
    FuXiaoXueTheme(settings.theme) {
        val context = LocalContext.current
        val login by viewModel.loginState.collectAsState()
        val preview by viewModel.previewTarget.collectAsState()
        val task by viewModel.taskAssignment.collectAsState()
        val search by viewModel.searchOpen.collectAsState()
        val courses by viewModel.courses.collectAsState()
        val availableUpdate by viewModel.availableUpdate.collectAsState()
        val saved = rememberSaveableStateHolder()
        val snackbars = remember { SnackbarHostState() }
        LaunchedEffect(viewModel) { viewModel.messages.collect { snackbars.showSnackbar(it) } }
        LaunchedEffect(login is LoginState.LoggedIn) {
            if (login is LoginState.LoggedIn) {
                val ask =
                    withContext(Dispatchers.IO) {
                        val prefs = Prefs(context)
                        if (prefs.notificationPrompted) false
                        else {
                            prefs.notificationPrompted = true
                            true
                        }
                    }
                if (ask) requestNotifications()
            }
        }
        BackHandler(enabled = search && preview == null) { viewModel.closeSearch() }
        BackHandler(enabled = preview != null) { viewModel.closePreview() }
        Box(Modifier.fillMaxSize()) {
            val target = preview
            when {
                target != null ->
                    PreviewScreen(
                        file = target.file,
                        displayName = target.title,
                        onBack = viewModel::closePreview,
                        onShare = { FileUtils.shareFile(context, it) },
                        siblingCount = target.siblings.size,
                        siblingIndex = target.siblings.indexOfFirst { it.fileId == target.fileId },
                        onNavigateSibling = viewModel::navigatePreviewSibling,
                        initialPage = target.initialPage,
                        onPageChanged = { page, total ->
                            viewModel.reportProgress(
                                target.fileId,
                                target.courseId,
                                page,
                                total,
                                false,
                            )
                        },
                        initialMediaSec = target.initialMediaSec,
                        onMediaPositionChanged = { sec, total ->
                            viewModel.reportProgress(
                                target.fileId,
                                target.courseId,
                                sec,
                                total,
                                true,
                            )
                        },
                    )
                task != null -> AssignmentDetailScreen(viewModel)
                search -> saved.SaveableStateProvider("search") { SearchScreen(viewModel) }
                login is LoginState.LoggedIn ->
                    saved.SaveableStateProvider("library") { HomeScreen(viewModel, snackbars) }
                else ->
                    LoginScreen(
                        login,
                        onOffline =
                            if (courses.isNotEmpty()) viewModel::openOfflineLibrary else null,
                        onLogin = viewModel::login,
                    )
            }
            SnackbarHost(
                snackbars,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
            availableUpdate?.let { update ->
                AlertDialog(
                    onDismissRequest = viewModel::dismissUpdate,
                    title = { Text("发现新版本") },
                    text = { Text("复小学 ${update.version} 已发布，是否打开下载页面？") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(update.url))
                                )
                                viewModel.dismissUpdate()
                            }
                        ) {
                            Text("查看更新")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::dismissUpdate) { Text("稍后") }
                    },
                )
            }
        }
    }
}
