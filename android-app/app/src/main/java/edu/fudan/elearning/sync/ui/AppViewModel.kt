package edu.fudan.elearning.sync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.fudan.elearning.sync.auth.LoginResult
import edu.fudan.elearning.sync.auth.UisAuthenticator
import edu.fudan.elearning.sync.data.Assignment
import edu.fudan.elearning.sync.data.CourseStats
import edu.fudan.elearning.sync.data.FileItem
import edu.fudan.elearning.sync.data.Repo
import edu.fudan.elearning.sync.data.SearchResult
import edu.fudan.elearning.sync.network.ApiClient
import edu.fudan.elearning.sync.network.CanvasApi
import edu.fudan.elearning.sync.sync.DownloadManager
import edu.fudan.elearning.sync.sync.SyncEngine
import edu.fudan.elearning.sync.util.FileUtils
import edu.fudan.elearning.sync.util.Prefs
import edu.fudan.elearning.sync.util.SecurePrefs
import edu.fudan.elearning.sync.worker.Notifier
import edu.fudan.elearning.sync.worker.SyncWorker
import edu.fudan.elearning.sync.sync.SyncGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 登录状态。 */
sealed class LoginState {
    object Loading : LoginState()
    data class LoggedIn(val username: String) : LoginState()
    object LoggedOut : LoginState()
    data class Error(val message: String) : LoginState()
}

/**
 * 预览目标：本地文件 + 在列表中显示的名字。
 *
 * 顶栏标题必须与文件列表一致（Canvas 的 display_name），而不是磁盘文件名
 * （Canvas 的 filename 字段可能是百分号编码的乱码）。
 */
data class PreviewTarget(
    val file: java.io.File,
    val title: String,
    // v1.1.2：阅读上下文（翻文件 + 进度恢复）。缺省值表示无上下文。
    val fileId: Long = 0,
    val courseId: Long = 0,
    val siblings: List<PreviewSibling> = emptyList(),
    val initialPage: Int = -1,
    val initialMediaSec: Int = -1
)

/** 预览内翻文件用的同课程文件（按文件列表顺序）。 */
data class PreviewSibling(
    val fileId: Long, val path: String, val title: String
)

/** openPreview 时构建的阅读上下文（IO 线程内生成）。 */
private data class PreviewContext(
    val fileId: Long, val courseId: Long,
    val siblings: List<PreviewSibling>,
    val initialPage: Int,
    val initialMediaSec: Int
)

/**
 * 同步进度（结构化）：供界面展示「课程 2/7」「下载 3/12」「1.8 MB/s」。
 *
 * phase 取值：start（刚启动）→ courses（课程列表）→ course（逐课程抓取）
 * → file（下载）→ index（内容索引回填）→ done。
 * [startedAt] 用于界面估算实时速度（字节 / 已用时间）。
 */
data class SyncProgressUi(
    val phase: String = "",
    val courseDone: Int = 0,
    val courseTotal: Int = 0,
    val courseName: String = "",
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytes: Long = 0,
    val startedAt: Long = 0,
    val message: String = ""
) {
    /**「同步课程：机器学习」式的消息带课程名前缀，剥掉给界面看干净的。 */
    val cleanMessage: String
        get() = when {
            phase == "course" && message.startsWith("同步课程：") ->
                message.substringAfter("同步课程：")
            else -> message
        }
}

/** 主界面 ViewModel：登录、同步、课程/文件数据、应用内预览路由。 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val repo = Repo(app)
    private val api = CanvasApi()
    private val downloader = DownloadManager(app)

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Loading)
    val loginState: StateFlow<LoginState> = _loginState

    private val _courses = MutableStateFlow<List<CourseStats>>(emptyList())
    val courses: StateFlow<List<CourseStats>> = _courses

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    /** 手动同步的协程句柄：退出登录时需要取消它 */
    private var syncJob: Job? = null

    private val _syncProgress = MutableStateFlow(SyncProgressUi())
    val syncProgress: StateFlow<SyncProgressUi> = _syncProgress

    /** 最近一次同步失败的原因（null 表示没有未处理的错误）。 */
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError

    /** 会话是否已失效：界面据此提示重新登录。 */
    private val _needsReauth = MutableStateFlow(false)
    val needsReauth: StateFlow<Boolean> = _needsReauth

    /**
     * 数据版本号：数据库内容变化时自增。
     *
     * 列表用 `remember(key)` 缓存查询结果时必须带上它，否则删除/同步后界面
     * 会继续显示旧数据（历史缺陷）。
     */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion

    private fun bumpDataVersion() {
        _dataVersion.value = _dataVersion.value + 1
    }

    /** 当前选中的课程（文件列表层）。状态放在 ViewModel 而不是 Composable 里，
     *  保证打开预览离开组合后，返回时仍在原来的文件列表。 */
    /** 待办作业（截止日期最早的 5 项；v3 起随课程统计一起刷新）。 */
    private val _upcoming = MutableStateFlow<List<Assignment>>(emptyList())
    val upcoming: StateFlow<List<Assignment>> = _upcoming

    private val _selectedCourseId = MutableStateFlow<Long?>(null)
    val selectedCourseId: StateFlow<Long?> = _selectedCourseId

    /** 选中课程的统计信息；课程数据变化时自动更新。 */
    val selectedCourse: StateFlow<CourseStats?> =
        combine(_courses, _selectedCourseId) { list, id ->
            id?.let { targetId -> list.firstOrNull { it.course.id == targetId } }
        }.let { flow ->
            val state = MutableStateFlow<CourseStats?>(null)
            viewModelScope.launch { flow.collect { state.value = it } }
            state
        }

    /** 应用内预览目标（null 表示不在预览态）。 */
    private val _previewTarget = MutableStateFlow<PreviewTarget?>(null)
    val previewTarget: StateFlow<PreviewTarget?> = _previewTarget

    val downloadRoot: String get() = downloader.rootPath()

    init {
        autoLogin()
    }

    /** 启动时自动登录。 */
    private fun autoLogin() {
        val username = prefs.username
        viewModelScope.launch {
            // Keystore 解密是耗时操作，不得在主线程做（AGENTS 硬性约束）
            val password = withContext(Dispatchers.IO) {
                SecurePrefs.loadPassword(getApplication())
            }
            if (username.isEmpty() || password.isNullOrEmpty()) {
                _loginState.value = LoginState.LoggedOut
                return@launch
            }
            _loginState.value = LoginState.Loading
            when (val result = UisAuthenticator().login(username, password)) {
                is LoginResult.Success -> {
                    ApiClient.setSession(result.session.canvasSessionCookie, result.session.csrfToken)
                    prefs.loggedIn = true
                    _loginState.value = LoginState.LoggedIn(username)
                    refreshCourses()
                }
                is LoginResult.Failure -> {
                    _loginState.value = LoginState.LoggedOut
                }
            }
        }
    }

    /** 账号密码登录。 */
    fun login(username: String, password: String, remember: Boolean) {
        viewModelScope.launch {
            _loginState.value = LoginState.Loading
            when (val result = UisAuthenticator().login(username, password)) {
                is LoginResult.Success -> {
                    ApiClient.setSession(
                        result.session.canvasSessionCookie,
                        result.session.csrfToken,
                        result.session.cookieName
                    )
                    prefs.username = username
                    prefs.loggedIn = true
                    if (remember) {
                        SecurePrefs.savePassword(getApplication(), password)
                    } else {
                        // 用户选择不记住密码时必须清掉旧密文，否则下次仍会静默登录
                        SecurePrefs.clear(getApplication())
                    }
                    _loginState.value = LoginState.LoggedIn(username)
                    // 安排后台定期同步
                    SyncWorker.schedule(getApplication(), prefs.syncIntervalMinutes)
                    _syncError.value = null
                    _needsReauth.value = false
                    refreshCourses()
                    sync()
                }
                is LoginResult.Failure -> {
                    _loginState.value = LoginState.Error(result.message)
                }
            }
        }
    }

    /** 退出登录。 */
    fun logout() {
        // 取消进行中的手动同步：否则 _syncing 滞留为 true，重登后被早退逻辑拒绝，
        // 且旧任务结束后还会把旧课程列表写回界面
        syncJob?.cancel()
        syncJob = null
        _syncing.value = false
        // 先停掉后台同步，避免退出后周期性失败并反复弹通知
        SyncWorker.cancel(getApplication())
        SecurePrefs.clear(getApplication())
        ApiClient.clearSession()
        prefs.loggedIn = false
        _loginState.value = LoginState.LoggedOut
        _courses.value = emptyList()
        _previewTarget.value = null
        _selectedCourseId.value = null
        _syncError.value = null
        _needsReauth.value = false
        _syncProgress.value = SyncProgressUi()
    }

    /**
     * 手动同步。
     *
     * [full] 为 true 时强制重下所有未被排除的文件（与桌面端「全量同步」一致）。
     * 与后台 Worker 通过 [SyncGate] 互斥；已有同步在跑时本次调用直接返回。
     */
    fun sync(full: Boolean = false) {
        if (_syncing.value) return
        syncJob = viewModelScope.launch {
            _syncing.value = true
            _syncError.value = null
            _syncProgress.value = SyncProgressUi(
                phase = "start", startedAt = System.currentTimeMillis(),
                message = if (full) "正在全量同步…" else "正在同步…"
            )
            // 全量同步：清空「来源未启用」的记忆，让用户在 Canvas 上重新开启的
            // 页面/作业/公告等来源有机会重新被抓取（否则会被永久跳过）。
            if (full) {
                prefs.clearDisabledSources()
            }
            val result = try {
                SyncGate.runOrSkip {
                    // 数据库与文件操作必须离开主线程（AGENTS 硬性约束）
                    withContext(Dispatchers.IO) {
                        val engine = SyncEngine(getApplication(), api, repo)
                        engine.sync(full = full) { phase, done, total, message, bytes ->
                            // 把引擎的结构化回调合并成界面可直接渲染的状态；
                            // 警告/失败消息仍随 message 带出（它们属于同一课程）。
                            val prev = _syncProgress.value
                            _syncProgress.value = prev.copy(
                                phase = phase,
                                courseDone = if (phase == "course" || phase == "courses") done else prev.courseDone,
                                courseTotal = if (phase == "course" || phase == "courses") total else prev.courseTotal,
                                courseName = if (phase == "course" && message.startsWith("同步课程："))
                                    message.substringAfter("同步课程：") else prev.courseName,
                                filesDone = if (phase == "file") done else prev.filesDone,
                                filesTotal = if (phase == "file") total else prev.filesTotal,
                                bytes = if (phase == "file" || phase == "done") bytes else prev.bytes,
                                message = message
                            )
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // 兜底：SyncEngine 只处理 ApiException；SQLite/磁盘等异常抛到这里时
                // 必须转成明确的错误状态，而不是让进程崩溃。
                _syncing.value = false
                _syncError.value = "同步出错：${error.message ?: error.javaClass.simpleName}"
                _syncProgress.value = _syncProgress.value.copy(
                    phase = "error", message = "同步失败：${_syncError.value}"
                )
                return@launch
            }
            _syncing.value = false
            if (result == null) {
                _syncProgress.value = _syncProgress.value.copy(
                    phase = "error", message = "已有同步正在进行"
                )
                return@launch
            }
            if (result.filesDownloaded > 0) {
                Notifier.notifySyncComplete(
                    getApplication(), result.filesDownloaded, result.bytesDownloaded
                )
            }
            prefs.lastSyncAt = System.currentTimeMillis()
            if (result.needsReauth) {
                // 会话失效：如实告知并退出到登录页，绝不显示「同步完成」
                _needsReauth.value = true
                _syncError.value = "登录状态已失效，请重新登录"
                _syncProgress.value = SyncProgressUi(phase = "error", message = "")
                logout()
                return@launch
            }
            _syncError.value = result.error
            _syncProgress.value = _syncProgress.value.copy(
                phase = "done",
                message = when {
                    result.error != null -> "同步失败：${result.error}"
                    result.filesFailed > 0 -> "同步完成，${result.filesFailed} 个文件失败，可重试"
                    else -> "同步完成"
                }
            )
            refreshCourses()
        }
    }

    /** 清除错误提示（界面「知道了 / 重试」后调用）。 */
    fun clearSyncError() {
        _syncError.value = null
    }

    /** 同步结果条展示完后标记为已读，避免每次回到首页都重复弹出。 */
    fun markSyncDoneSeen() {
        if (_syncProgress.value.phase == "done") {
            _syncProgress.value = _syncProgress.value.copy(phase = "idle")
        }
    }

    /** 上次同步记录（时间与失败原因），用于设置页展示。 */
    suspend fun lastSyncSummary(): String = withContext(Dispatchers.IO) {
        val run = repo.lastRun() ?: return@withContext "尚无同步记录"
        val finished = run.finishedAt ?: "未完成"
        val mode = if (run.mode == "full") "全量" else "增量"
        val error = run.error
        if (error.isNullOrBlank()) {
            "${mode}同步 · $finished · 下载 ${run.filesDownloaded} 个文件"
        } else {
            "${mode}同步 · $finished · 失败：$error"
        }
    }

    /** 刷新课程统计。 */
    fun refreshCourses() {
        // 统计查询在 IO 线程执行；StateFlow 赋值本身线程安全
        viewModelScope.launch {
            _courses.value = withContext(Dispatchers.IO) { repo.courseStats() }
            // 待办作业（v3 起）：与课程统计同一次刷新，避免单独查询
            _upcoming.value = withContext(Dispatchers.IO) {
                runCatching { repo.assignments(40) }.getOrDefault(emptyList())
            }
            bumpDataVersion()
        }
    }

    /** 选中课程（进入文件列表）。 */
    fun selectCourse(courseId: Long) {
        _selectedCourseId.value = courseId
    }

    /** 返回课程列表。 */
    fun clearCourseSelection() {
        _selectedCourseId.value = null
    }

    /** 获取课程文件（挂起，组合期调用安全：查询在 IO 线程执行）。 */
    suspend fun filesOf(courseId: Long): List<FileItem> =
        // 界面只看可见文件（remote_missing 不再展示）；引擎与重试路径仍读全量
        withContext(Dispatchers.IO) { repo.getVisibleFilesByCourse(courseId) }

    /** v1.1.2：带阅读进度的文件列表（进度列 + 未读优先排序）。 */
    suspend fun filesWithProgress(courseId: Long): List<Pair<FileItem, Repo.ReadingProgress?>> =
        withContext(Dispatchers.IO) {
            val progress = repo.progressByCourse(courseId)
            repo.getVisibleFilesByCourse(courseId).map { it to progress[it.fileId] }
        }

    /** v1.1.2：课程最近阅读的文件（供文件列表顶部「继续阅读」条）。 */
    suspend fun latestReadFile(courseId: Long): FileItem? = withContext(Dispatchers.IO) {
        runCatching { repo.latestReadFileOfCourse(courseId) }.getOrNull()
    }

    /** 更新同步频率。 */
    fun setSyncInterval(minutes: Int) {
        prefs.syncIntervalMinutes = minutes
        SyncWorker.schedule(getApplication(), minutes)
    }

    fun currentInterval(): Int = prefs.syncIntervalMinutes

    /** 删除文件（本地 + 数据库）。 */
    fun deleteFile(file: FileItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                file.localPath.let { path ->
                    if (path.isNotEmpty()) {
                        val target = java.io.File(path)
                        target.delete()
                        // 同时清掉可能存在的断点，避免下次同步把半截文件当续传起点
                        edu.fudan.elearning.sync.sync.DownloadPlan.partFile(target).delete()
                    }
                }
                repo.deleteFile(file.fileId)
            }
            refreshCourses()
        }
    }

    /** 删除课程下所有文件。 */
    fun deleteCourseFiles(courseId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                filesOf(courseId).forEach { file ->
                    if (file.localPath.isNotEmpty()) {
                        val target = java.io.File(file.localPath)
                        target.delete()
                        edu.fudan.elearning.sync.sync.DownloadPlan.partFile(target).delete()
                    }
                }
                repo.deleteFilesByCourse(courseId)
            }
            refreshCourses()
        }
    }

    /**
     * 重试单个失败文件（不触发整轮同步）。
     *
     * 只需要该文件自己的下载 URL 与大小，失败时保留断点，下次可继续续传。
     * 注意：Canvas 的下载 URL 带 verifier 且会过期，数据库里存的是抓取时的
     * 地址；遇 404/403 时必须像 SyncEngine 那样重新换取签名 URL 再试一次，
     * 否则重试永远失败。
     */
    fun retryFile(file: FileItem) {
        if (file.url.isEmpty()) {
            toast("缺少下载地址，请先执行一次同步")
            return
        }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val context = getApplication<android.app.Application>()
                val downloader = edu.fudan.elearning.sync.sync.DownloadManager(context)
                val courseDir = downloader.courseDir(repo.courseName(file.courseId))
                val taken = repo.getFilesByCourse(file.courseId)
                    .filter { it.fileId != file.fileId && it.localPath.isNotEmpty() }
                    .map { java.io.File(it.localPath).name }
                    .toSet()
                val dest = file.localPath.takeIf { it.isNotEmpty() }?.let { java.io.File(it) }
                    ?: downloader.destinationFor(
                        courseDir,
                        file.filename.ifEmpty {
                            edu.fudan.elearning.sync.sync.DownloadManager.sanitize(file.name)
                        },
                        taken
                    )
                var result = downloader.download(file.url, dest, file.size)
                if (result is edu.fudan.elearning.sync.sync.DownloadOutcome.Failed &&
                    (result.reason.contains("404") || result.reason.contains("403"))
                ) {
                    // 签名 URL 已过期：重新向 API 换取，再试一次
                    val freshUrl = runCatching {
                        api.getFile(file.courseId, file.fileId)?.url
                    }.getOrNull()?.takeIf { it.isNotEmpty() && it != file.url }
                    if (freshUrl != null) {
                        result = downloader.download(freshUrl, dest, file.size)
                    }
                }
                result to dest
            }
            when (val result = outcome.first) {
                is edu.fudan.elearning.sync.sync.DownloadOutcome.Success -> {
                    withContext(Dispatchers.IO) {
                        val updated = file.copy(
                            localPath = result.path.absolutePath,
                            size = result.bytes,
                            status = "downloaded",
                            downloadedAt = now()
                        )
                        repo.upsertFile(updated)
                        // 重试成功也要刷新搜索索引（旧索引可能是文件名兜底）
                        edu.fudan.elearning.sync.search.SearchIndexer.indexFile(
                            getApplication(), repo, updated
                        )
                    }
                    toast("已重新下载：${file.name}")
                }
                is edu.fudan.elearning.sync.sync.DownloadOutcome.Failed -> {
                    // v1.2.2：把失败原因写回记录，列表行常驻显示
                    withContext(Dispatchers.IO) {
                        repo.markFailed(file.fileId, result.reason)
                    }
                    toast("重试失败：${result.reason}")
                }
            }
            refreshCourses()
        }
    }

    private fun now(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()
    ).format(java.util.Date())

    // ---------- 搜索（v3 起） ----------
    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private var searchJob: kotlinx.coroutines.Job? = null

    fun openSearch() {
        _searchOpen.value = true
    }

    fun closeSearch() {
        _searchOpen.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        searchJob?.cancel()
    }

    /**
     * 更新搜索关键字（防抖 250ms 后执行查询）。
     *
     * 查询在 IO 线程执行：FTS 搜索不访问网络，只读本地库。
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            _searching.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    runCatching { repo.searchFiles(query) }.getOrDefault(emptyList())
                }
                _searchResults.value = results
            } finally {
                _searching.value = false
            }
        }
    }

    /** 搜索结果点击：直接打开应用内预览，并关闭搜索页。 */
    fun openSearchResult(result: SearchResult) {
        viewModelScope.launch {
            // v1.2.1：repo.getFile 是 SQLite 查询，不得在主线程执行（§8 硬性约束）
            val file = withContext(Dispatchers.IO) {
                runCatching { repo.getFile(result.fileId) }.getOrNull()
            }
            if (file == null) {
                toast("该文件记录已不存在")
                return@launch
            }
            closeSearch()
            openPreview(file)
        }
    }

    /** 打开应用内预览（取代旧的 ACTION_VIEW 跳转）。 */
    fun openPreview(file: FileItem) {
        if (file.localPath.isEmpty()) {
            toast("文件尚未下载，请先同步")
            return
        }
        val local = java.io.File(file.localPath)
        if (!local.exists() || !local.isFile) {
            toast("本地文件不存在，可能尚未下载完成或已被删除")
            return
        }
        viewModelScope.launch {
            // v1.1.2：同课程可预览清单 + 上次阅读位置（IO 线程，不阻塞组合）
            val context = withContext(Dispatchers.IO) {
                runCatching { buildPreviewContext(file) }.getOrNull()
            }
            // 标题用列表显示名（display_name），不用磁盘文件名（可能是百分号编码）
            _previewTarget.value = PreviewTarget(
                local,
                file.name.ifEmpty { local.name },
                fileId = context?.fileId ?: 0,
                courseId = context?.courseId ?: 0,
                siblings = context?.siblings ?: emptyList(),
                initialPage = context?.initialPage ?: -1,
                initialMediaSec = context?.initialMediaSec ?: -1
            )
        }
    }

    private fun buildPreviewContext(file: FileItem): PreviewContext? {
        if (file.fileId <= 0) return null
        val siblings = mutableListOf<PreviewSibling>()
        var initialPage = -1
        var initialMediaSec = -1
        repo.getVisibleFilesByCourse(file.courseId)
            .filter { it.status == "downloaded" && it.localPath.isNotEmpty() }
            .forEach { item ->
                val path = java.io.File(item.localPath)
                if (path.exists() && path.isFile) {
                    siblings += PreviewSibling(
                        fileId = item.fileId,
                        path = item.localPath,
                        title = item.name.ifEmpty { path.name }
                    )
                }
            }
        repo.getReadingProgress(file.fileId)?.let { prog ->
            if (prog.isMedia) initialMediaSec = prog.position
            else initialPage = prog.position
        }
        return PreviewContext(
            fileId = file.fileId, courseId = file.courseId,
            siblings = siblings, initialPage = initialPage,
            initialMediaSec = initialMediaSec
        )
    }

    /** 预览报回当前页码（VerticalPageList 翻页时）。 */
    fun reportReadingPage(page: Int, total: Int) {
        val target = _previewTarget.value ?: return
        if (target.fileId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.setReadingProgress(target.fileId, target.courseId, page, total, false)
            }
            _dataVersion.value += 1  // 列表进度列需要刷新
        }
    }

    /** 预览报回媒体播放位置（秒）。 */
    fun reportReadingMediaSec(sec: Int, totalSec: Int) {
        val target = _previewTarget.value ?: return
        if (target.fileId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.setReadingProgress(target.fileId, target.courseId, sec, totalSec, true)
            }
            _dataVersion.value += 1
        }
    }

    /** 在预览内切到同课程的上一个/下一个文件（delta = -1 / +1）。 */
    private var previewNavJob: kotlinx.coroutines.Job? = null

    fun navigatePreviewSibling(delta: Int) {
        val target = _previewTarget.value ?: return
        if (target.siblings.size <= 1) return
        val index = target.siblings.indexOfFirst { it.path == target.file.absolutePath }
        if (index < 0) return
        val newIndex = index + delta
        if (newIndex !in target.siblings.indices) return
        val sibling = target.siblings[newIndex]
        // v1.2.1：取消未完成的上一次导航——快速连点时 IO 协程乱序完成会让最终落点
        // 与用户最后一次操作不符；这里保证「最后一次点击胜出」。
        previewNavJob?.cancel()
        previewNavJob = viewModelScope.launch {
            val context = withContext(Dispatchers.IO) {
                runCatching {
                    val item = repo.getFile(sibling.fileId)
                    if (item != null) buildPreviewContext(item) else null
                }.getOrNull()
            }
            _previewTarget.value = PreviewTarget(
                java.io.File(sibling.path),
                sibling.title,
                fileId = sibling.fileId,
                courseId = context?.courseId ?: target.courseId,
                // v1.2.1：兄弟清单按新文件重新构建——预览期间新下载的文件也能翻到
                siblings = context?.siblings ?: target.siblings,
                initialPage = context?.initialPage ?: -1,
                initialMediaSec = context?.initialMediaSec ?: -1
            )
        }
    }

    /** 关闭应用内预览。 */
    fun closePreview() {
        _previewTarget.value = null
    }

    /** 分享文件（系统分享面板，仅临时只读 URI 权限）。 */
    fun shareFile(file: FileItem) {
        FileUtils.shareFile(getApplication(), file)
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(
            getApplication(), message, android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    override fun onCleared() {
        repo.close()
        super.onCleared()
    }
}
