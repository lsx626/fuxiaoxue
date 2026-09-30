package edu.fudan.elearning.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.fudan.elearning.sync.preview.PreviewScreen
import edu.fudan.elearning.sync.ui.AppViewModel
import edu.fudan.elearning.sync.ui.FuXiaoXueTheme
import edu.fudan.elearning.sync.ui.HomeScreen
import edu.fudan.elearning.sync.ui.LoginScreen
import edu.fudan.elearning.sync.ui.LoginState
import edu.fudan.elearning.sync.ui.SearchScreen

/** 应用主入口。 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()

        setContent {
            FuXiaoXueTheme {
                val viewModel: AppViewModel = viewModel()
                val loginState by viewModel.loginState.collectAsState()
                val target by viewModel.previewTarget.collectAsState()
                val searchOpen by viewModel.searchOpen.collectAsState()

                // 全局搜索为全屏覆盖层（与预览同一路由模式），返回键先关搜索。
                if (searchOpen) {
                    BackHandler { viewModel.closeSearch() }
                }
                // 应用内预览为全屏覆盖层，独立于登录/主界面路由。
                // 系统返回键优先关闭预览，而不是直接退出应用。
                if (target != null) {
                    BackHandler { viewModel.closePreview() }
                }
                val preview = target
                when {
                    preview != null -> PreviewScreen(
                        file = preview.file,
                        displayName = preview.title,
                        onBack = viewModel::closePreview,
                        onShare = { file ->
                            edu.fudan.elearning.sync.util.FileUtils.shareFile(
                                applicationContext, file
                            )
                        }
                    )
                    searchOpen -> SearchScreen(viewModel)
                    else -> when (val state = loginState) {
                        is LoginState.LoggedIn -> HomeScreen(viewModel)
                        else -> LoginScreen(state) { username, password, remember ->
                            viewModel.login(username, password, remember)
                        }
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
