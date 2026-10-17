package edu.fudan.elearning.sync.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import edu.fudan.elearning.sync.auth.*
import edu.fudan.elearning.sync.data.*
import edu.fudan.elearning.sync.network.*
import edu.fudan.elearning.sync.search.SearchIndexer
import edu.fudan.elearning.sync.sync.*
import edu.fudan.elearning.sync.util.*
import edu.fudan.elearning.sync.worker.Notifier
import edu.fudan.elearning.sync.worker.SyncWorker
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

sealed class LoginState {
    object Loading : LoginState()

    data class LoggedIn(val username: String) : LoginState()

    object LoggedOut : LoginState()

    data class Error(val message: String) : LoginState()
}

data class PreviewTarget(
    val file: File,
    val title: String,
    val fileId: Long = 0,
    val courseId: Long = 0,
    val siblings: List<PreviewSibling> = emptyList(),
    val initialPage: Int = -1,
    val initialMediaSec: Int = -1,
)

data class PreviewSibling(val fileId: Long, val path: String, val title: String)

data class SyncProgressUi(
    val phase: String = "",
    val courseDone: Int = 0,
    val courseTotal: Int = 0,
    val courseName: String = "",
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytes: Long = 0,
    val startedAt: Long = 0,
    val message: String = "",
) {
    val cleanMessage: String
        get() = message.removePrefix("同步课程：")
}

class AppViewModel
@JvmOverloads
constructor(
    app: Application,
    autoStart: Boolean = true,
    private val authenticate: suspend (String, String) -> LoginResult = { user, password ->
        UisAuthenticator().login(user, password)
    },
    private val syncRunner:
        (suspend (Boolean, (String, Int, Int, String, Long) -> Unit) -> SyncResult)? =
        null,
) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val repo = Repo(app)
    private val api = CanvasApi()
    private val storage = LocalStorage(app)
    val downloadRoot: String
        get() = storage.root.absolutePath

    private val _loginState = MutableStateFlow<LoginState>(LoginState.Loading)
    val loginState: StateFlow<LoginState> = _loginState
    private val _courses = MutableStateFlow<List<CourseStats>>(emptyList())
    val courses: StateFlow<List<CourseStats>> = _courses
    private val _upcomingAssignments = MutableStateFlow<List<Assignment>>(emptyList())
    val upcoming: StateFlow<List<Assignment>> = _upcomingAssignments
    private val _recentChanges = MutableStateFlow<List<FileChange>>(emptyList())
    val changes: StateFlow<List<FileChange>> = _recentChanges
    private val _libraryLoading = MutableStateFlow(true)
    val libraryLoading: StateFlow<Boolean> = _libraryLoading
    private val _libraryError = MutableStateFlow<String?>(null)
    val libraryError: StateFlow<String?> = _libraryError
    private val _operationError = MutableStateFlow<String?>(null)
    val operationError: StateFlow<String?> = _operationError
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    val taskBusy: StateFlow<Boolean> = SyncGate.busy
    private val _busyFiles = MutableStateFlow<Set<Long>>(emptySet())
    val busyFiles: StateFlow<Set<Long>> = _busyFiles
    private val _syncProgress = MutableStateFlow(SyncProgressUi())
    val syncProgress: StateFlow<SyncProgressUi> = _syncProgress
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError
    private val _needsReauth = MutableStateFlow(false)
    val needsReauth: StateFlow<Boolean> = _needsReauth
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion
    private val _selectedCourseId = MutableStateFlow<Long?>(null)
    val selectedCourseId: StateFlow<Long?> = _selectedCourseId
    val selectedCourse =
        combine(_courses, _selectedCourseId) { list, id -> list.find { it.course.id == id } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _previewTarget = MutableStateFlow<PreviewTarget?>(null)
    val previewTarget: StateFlow<PreviewTarget?> = _previewTarget
    private val _searchOpen = MutableStateFlow(false)
    val searchOpen: StateFlow<Boolean> = _searchOpen
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private var authJob: Job? = null
    private var syncJob: Job? = null
    private var refreshJob: Job? = null
    private var mutationJob: Job? = null
    private var searchJob: Job? = null
    private var previewJob: Job? = null
    private var settingsJob: Job? = null

    init {
        if (autoStart) autoLogin()
    }

    private suspend fun loadSettings() =
        withContext(Dispatchers.IO) {
            _settings.value =
                AppSettings(
                    ThemeMode.entries.find { it.preference == prefs.themeMode } ?: ThemeMode.SYSTEM,
                    prefs.syncIntervalMinutes,
                    prefs.backgroundSyncEnabled,
                    !SecurePrefs.loadPassword(getApplication()).isNullOrEmpty(),
                )
        }

    private fun autoLogin() {
        authJob =
            viewModelScope.launch {
                loadSettings()
                val saved =
                    withContext(Dispatchers.IO) {
                        Triple(
                            prefs.username,
                            prefs.loggedIn,
                            SecurePrefs.loadPassword(getApplication()),
                        )
                    }
                refreshLibrary()
                if (saved.first.isNotBlank() && saved.second) {
                    _loginState.value = LoginState.LoggedIn(saved.first)
                    _needsReauth.value = true
                }
                if (saved.first.isBlank() || saved.third.isNullOrEmpty()) {
                    SyncWorker.cancel(getApplication())
                    if (_loginState.value !is LoginState.LoggedIn)
                        _loginState.value = LoginState.LoggedOut
                    return@launch
                }
                val result =
                    try {
                        authenticate(saved.first, saved.third!!)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        LoginResult.Failure("暂时无法连接账号，请稍后重试", retryable = true)
                    }
                when (result) {
                    is LoginResult.Success -> {
                        SyncGate.runWaiting { applySession(result.session) }
                        _needsReauth.value = false
                        _loginState.value = LoginState.LoggedIn(saved.first)
                        scheduleBackground()
                        sync()
                    }
                    is LoginResult.Failure -> {
                        if (_loginState.value is LoginState.LoggedIn)
                            _syncError.value = "暂时无法连接账号，已下载资料仍可离线阅读。${result.message}"
                        else _loginState.value = LoginState.Error(result.message)
                    }
                }
            }
    }

    fun login(username: String, password: String, remember: Boolean) {
        if (username.isBlank() || password.isEmpty() || _loginState.value == LoginState.Loading)
            return
        authJob?.cancel()
        _loginState.value = LoginState.Loading
        authJob =
            viewModelScope.launch {
                try {
                    when (val result = authenticate(username.trim(), password)) {
                        is LoginResult.Success -> {
                            SyncGate.runWaiting {
                                withContext(Dispatchers.IO) {
                                    prefs.username = username.trim()
                                    prefs.loggedIn = true
                                    if (remember)
                                        SecurePrefs.savePassword(getApplication(), password)
                                    else SecurePrefs.clear(getApplication())
                                }
                                applySession(result.session)
                            }
                            loadSettings()
                            _loginState.value = LoginState.LoggedIn(username.trim())
                            _needsReauth.value = false
                            _syncError.value = null
                            scheduleBackground()
                            refreshLibrary()
                            sync()
                        }
                        is LoginResult.Failure ->
                            _loginState.value = LoginState.Error(result.message)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _loginState.value = LoginState.Error("无法完成登录或保存账号，请重试")
                }
            }
    }

    private fun applySession(session: AuthSession) =
        ApiClient.setSession(session.canvasSessionCookie, session.csrfToken, session.cookieName)

    fun requestReauth() {
        authJob?.cancel()
        syncJob?.cancel()
        mutationJob?.cancel()
        ApiClient.clearSession()
        _needsReauth.value = true
        SyncWorker.cancel(getApplication())
        _loginState.value = LoginState.LoggedOut
    }

    fun openOfflineLibrary() {
        viewModelScope.launch {
            val user = withContext(Dispatchers.IO) { prefs.username }
            if (_courses.value.isNotEmpty() && user.isNotBlank()) {
                _needsReauth.value = ApiClient.canvasSession.isEmpty()
                _loginState.value = LoginState.LoggedIn(user)
            }
        }
    }

    fun logout() {
        val pendingAuth = authJob
        pendingAuth?.cancel()
        syncJob?.cancel()
        mutationJob?.cancel()
        refreshJob?.cancel()
        closeSearch()
        closePreview()
        _loginState.value = LoginState.Loading
        authJob =
            viewModelScope.launch {
                pendingAuth?.join()
                withContext(Dispatchers.IO) {
                    SecurePrefs.clear(getApplication())
                    prefs.loggedIn = false
                    SyncWorker.cancel(getApplication())
                }
                ApiClient.clearSession()
                _courses.value = emptyList()
                _upcomingAssignments.value = emptyList()
                _recentChanges.value = emptyList()
                _selectedCourseId.value = null
                _needsReauth.value = false
                _syncError.value = null
                _operationError.value = null
                _syncProgress.value = SyncProgressUi()
                _loginState.value = LoginState.LoggedOut
            }
    }

    fun sync(full: Boolean = false) {
        if (_syncing.value) return
        if (_needsReauth.value || ApiClient.canvasSession.isEmpty()) {
            requestReauth()
            return
        }
        if (SyncGate.isBusy) {
            showMessage("后台任务正在处理资料，请稍后再试")
            return
        }
        _syncing.value = true
        _syncError.value = null
        _syncProgress.value =
            SyncProgressUi(
                phase = "start",
                startedAt = System.currentTimeMillis(),
                message = "正在连接课程平台",
            )
        syncJob =
            viewModelScope.launch {
                try {
                    val result =
                        SyncGate.runOrSkip {
                            withContext(Dispatchers.IO) {
                                if (full) prefs.clearDisabledSources()
                                val report: (String, Int, Int, String, Long) -> Unit =
                                    ::updateSyncProgress
                                syncRunner?.invoke(full, report)
                                    ?: SyncEngine(getApplication(), api, repo).sync(full, report)
                            }
                        }
                    if (result == null) {
                        showMessage("已有同步正在运行")
                        return@launch
                    }
                    withContext(Dispatchers.IO) { prefs.lastSyncAt = System.currentTimeMillis() }
                    if (result.needsReauth) _needsReauth.value = true
                    _syncError.value = result.error
                    _syncProgress.update {
                        it.copy(
                            phase = if (result.ok) "done" else "error",
                            message =
                                when {
                                    !result.ok -> result.error.orEmpty()
                                    result.filesFailed > 0 -> "已完成，${result.filesFailed} 个文件需要重试"
                                    else -> "资料已更新 · 下载 ${result.filesDownloaded} 个文件"
                                },
                        )
                    }
                    if (result.filesDownloaded > 0)
                        Notifier.notifySyncComplete(
                            getApplication(),
                            result.filesDownloaded,
                            result.bytesDownloaded,
                        )
                } catch (cancelled: CancellationException) {
                    _syncProgress.update {
                        it.copy(phase = "stopped", message = "同步已停止，已下载资料和断点已保留")
                    }
                    throw cancelled
                } catch (_: Exception) {
                    _syncError.value = "同步未完成，请检查网络或可用存储空间后重试"
                    _syncProgress.update {
                        it.copy(phase = "error", message = _syncError.value.orEmpty())
                    }
                } finally {
                    _syncing.value = false
                    if (_loginState.value is LoginState.LoggedIn) refreshLibrary()
                }
            }
    }

    private fun updateSyncProgress(
        phase: String,
        done: Int,
        total: Int,
        message: String,
        bytes: Long,
    ) {
        _syncProgress.update { previous ->
            val isCoursePhase = phase == "course" || phase == "courses"
            previous.copy(
                phase = phase,
                message = message,
                courseDone = if (isCoursePhase) done else previous.courseDone,
                courseTotal = if (isCoursePhase) total else previous.courseTotal,
                courseName =
                    if (phase == "course" && message.startsWith("同步课程："))
                        message.removePrefix("同步课程：")
                    else previous.courseName,
                filesDone = if (phase == "file") done else previous.filesDone,
                filesTotal = if (phase == "file") total else previous.filesTotal,
                bytes = if (phase == "file" || phase == "done") bytes else previous.bytes,
            )
        }
    }

    fun stopSync() {
        syncJob?.cancel()
    }

    fun clearSyncError() {
        _syncError.value = null
    }

    fun clearOperationError() {
        _operationError.value = null
    }

    fun markSyncDoneSeen() {
        if (!_syncing.value) _syncProgress.update { it.copy(phase = "idle", message = "") }
    }

    fun refreshLibrary() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                _libraryLoading.value = true
                _libraryError.value = null
                try {
                    withContext(Dispatchers.IO) {
                        val courses = repo.courseStats()
                        val assignments = repo.assignments(Int.MAX_VALUE)
                        val changes = repo.recentChanges(100)
                        currentCoroutineContext().ensureActive()
                        _courses.value = courses
                        _upcomingAssignments.value = assignments
                        _recentChanges.value = changes
                    }
                    _dataVersion.update { it + 1 }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _libraryError.value = "本地资料加载失败，请重试；已有文件不会被清除"
                } finally {
                    _libraryLoading.value = false
                }
            }
    }

    fun selectCourse(courseId: Long) {
        _selectedCourseId.value = courseId
    }

    fun clearCourseSelection() {
        _selectedCourseId.value = null
    }

    suspend fun filesOf(courseId: Long): List<FileItem> =
        withContext(Dispatchers.IO) { repo.getVisibleFilesByCourse(courseId) }

    suspend fun storageFilesOf(courseId: Long): List<FileItem> =
        withContext(Dispatchers.IO) { repo.getFilesByCourse(courseId) }

    suspend fun storageSnapshot(): Pair<StorageUsage, List<CourseStats>> =
        withContext(Dispatchers.IO) {
            val courses =
                repo.getAllCourses().map { course ->
                    val files = repo.getFilesByCourse(course.id)
                    val local =
                        files
                            .mapNotNull { it.localPath.takeIf(String::isNotBlank)?.let(::File) }
                            .filter { it.isFile }
                            .distinctBy { it.absolutePath }
                    CourseStats(course, files.size, local.size, local.sumOf { it.length() })
                }
            storage.usage() to courses
        }

    suspend fun filesWithProgress(courseId: Long) =
        withContext(Dispatchers.IO) {
            val progress = repo.progressByCourse(courseId)
            repo.getVisibleFilesByCourse(courseId).map { it to progress[it.fileId] }
        }

    suspend fun latestReadFile(courseId: Long): FileItem? =
        withContext(Dispatchers.IO) { repo.latestReadFileOfCourse(courseId) }

    suspend fun lastSyncSummary(): String =
        withContext(Dispatchers.IO) {
            val run = repo.lastRun() ?: return@withContext "尚无同步记录"
            if (!run.error.isNullOrBlank()) "上次未完成 · ${run.error}"
            else
                "${run.finishedAt.orEmpty()} · 下载 ${run.filesDownloaded} 个 · 失败 ${run.filesFailed} 个"
        }

    fun syncIntervalMinutes() = _settings.value.syncInterval

    fun setSyncInterval(minutes: Int) {
        updateSettings(_settings.value.copy(syncInterval = minutes.coerceAtLeast(15)))
    }

    fun setTheme(mode: ThemeMode) {
        updateSettings(_settings.value.copy(theme = mode))
    }

    fun setBackgroundSync(enabled: Boolean) {
        updateSettings(_settings.value.copy(backgroundSync = enabled))
    }

    private fun updateSettings(value: AppSettings) {
        _settings.value = value
        val previous = settingsJob
        previous?.cancel()
        settingsJob =
            viewModelScope.launch {
                previous?.join()
                withContext(Dispatchers.IO) {
                    prefs.syncIntervalMinutes = value.syncInterval
                    prefs.themeMode = value.theme.preference
                    prefs.backgroundSyncEnabled = value.backgroundSync
                }
                scheduleBackground()
            }
    }

    private fun scheduleBackground() {
        if (_settings.value.backgroundSync && _settings.value.passwordRemembered)
            SyncWorker.schedule(getApplication(), syncIntervalMinutes())
        else SyncWorker.cancel(getApplication())
    }

    private fun runFileMutation(fileIds: Set<Long> = emptySet(), action: suspend () -> Unit) {
        if (SyncGate.isBusy || mutationJob?.isActive == true) {
            showMessage("有资料任务正在进行，请稍后重试")
            return
        }
        _busyFiles.value = fileIds
        mutationJob =
            viewModelScope.launch {
                try {
                    val done =
                        SyncGate.runOrSkip {
                            withContext(Dispatchers.IO) {
                                action()
                                true
                            }
                        }
                    if (done == null) showMessage("有同步正在进行，请稍后重试")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (auth: ApiException.Auth) {
                    _needsReauth.value = true
                    _operationError.value = "登录已失效，请重新登录后重试"
                } catch (error: Exception) {
                    _operationError.value =
                        error.message?.takeIf { !it.contains("http", true) }?.take(150)
                            ?: "操作未完成，记录已保留，请重试"
                } finally {
                    _busyFiles.value = emptySet()
                    if (isActive) refreshLibrary()
                }
            }
    }

    fun deleteFile(file: FileItem) =
        runFileMutation(setOf(file.fileId)) {
            val current = repo.getFile(file.fileId) ?: return@runFileMutation
            storage.deleteLocal(current)
            repo.deleteFile(current.fileId)
            showMessage("已删除本地文件；下次同步可重新下载")
        }

    fun deleteCourseFiles(courseId: Long) = runFileMutation {
        var deleted = 0
        for (file in repo.getFilesByCourse(courseId)) {
            currentCoroutineContext().ensureActive()
            storage.deleteLocal(file)
            repo.deleteFile(file.fileId)
            deleted++
        }
        showMessage("已清理 $deleted 个文件；下次同步可重新下载")
    }

    fun retryFile(file: FileItem) {
        if (_needsReauth.value) {
            requestReauth()
            return
        }
        runFileMutation(setOf(file.fileId)) {
            val current = repo.getFile(file.fileId) ?: return@runFileMutation
            val fresh = api.getFile(current.courseId, current.fileId)
            if (fresh?.isLocked == true) {
                repo.upsertFile(current.copy(status = "locked", error = "教师已锁定此文件，暂不开放下载"))
                showMessage("教师已锁定此文件，暂不开放下载")
                return@runFileMutation
            }
            val url = fresh?.url?.ifBlank { current.url } ?: current.url
            check(url.isNotBlank()) { "缺少下载地址，请先同步课程" }
            val downloader = DownloadManager(getApplication())
            val courseDir = downloader.courseDir(repo.courseName(current.courseId))
            val dir = File(courseDir, DownloadPlan.safeRelativeDir(current.folderPath))
            val taken =
                repo
                    .getFilesByCourse(current.courseId)
                    .filter { it.fileId != current.fileId }
                    .mapNotNull { it.localPath.takeIf(String::isNotBlank)?.let(::File) }
                    .filter { it.parentFile == dir }
                    .map { it.name }
                    .toSet()
            val destination =
                storage.requireManaged(
                    current.localPath.takeIf(String::isNotBlank)?.let(::File)
                        ?: downloader.destinationFor(
                            dir,
                            DownloadManager.sanitize(current.filename.ifBlank { current.name }),
                            taken,
                        )
                )
            if (fresh != null && fresh.updatedAt != current.updatedAt)
                File(destination.path + ".part").delete()
            when (val result = downloader.download(url, destination, fresh?.size ?: current.size)) {
                is DownloadOutcome.Success -> {
                    val updated =
                        current.copy(
                            localPath = result.path.absolutePath,
                            size = result.bytes,
                            status = "downloaded",
                            url = url,
                            updatedAt = fresh?.updatedAt ?: current.updatedAt,
                            error = "",
                            downloadedAt = java.time.Instant.now().toString(),
                        )
                    repo.upsertFile(updated)
                    SearchIndexer.indexFile(getApplication(), repo, updated)
                    showMessage("已下载：${current.name}")
                }
                is DownloadOutcome.Failed -> {
                    repo.markFailed(current.fileId, result.reason)
                    showMessage(result.reason)
                }
            }
        }
    }

    fun openSearch() {
        _searchOpen.value = true
    }

    fun closeSearch() {
        searchJob?.cancel()
        _searchOpen.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _searching.value = false
        _searchError.value = null
    }

    fun updateSearchQuery(query: String) {
        searchJob?.cancel()
        _searchQuery.value = query
        _searchResults.value = emptyList()
        _searchError.value = null
        _searching.value = query.isNotBlank()
        if (query.isBlank()) return
        searchJob =
            viewModelScope.launch {
                try {
                    delay(250)
                    val results = withContext(Dispatchers.IO) { repo.searchFiles(query) }
                    ensureActive()
                    if (_searchQuery.value == query) _searchResults.value = results
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _searchError.value = "本地搜索未完成，请重试"
                } finally {
                    if (_searchQuery.value == query) _searching.value = false
                }
            }
    }

    fun openSearchResult(result: SearchResult) {
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch {
                try {
                    val target =
                        withContext(Dispatchers.IO) {
                            repo.getFile(result.fileId)?.let(::buildPreviewTarget)
                        }
                    ensureActive()
                    if (target == null) showMessage("本地文件不存在，请刷新或重新下载")
                    else _previewTarget.value = target
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showMessage("暂时无法打开文件，请重试")
                }
            }
    }

    fun openPreview(file: FileItem) {
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch {
                try {
                    val target = withContext(Dispatchers.IO) { buildPreviewTarget(file) }
                    ensureActive()
                    if (target == null) showMessage("本地文件不存在，请重新下载")
                    else _previewTarget.value = target
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showMessage("暂时无法打开文件，请重试")
                }
            }
    }

    private fun buildPreviewTarget(file: FileItem): PreviewTarget? {
        val local = File(file.localPath)
        if (file.localPath.isBlank() || !local.isFile) return null
        val siblings =
            repo
                .getVisibleFilesByCourse(file.courseId)
                .filter {
                    it.status == "downloaded" &&
                        it.localPath.isNotBlank() &&
                        File(it.localPath).isFile
                }
                .map { PreviewSibling(it.fileId, it.localPath, it.name) }
        val progress = repo.getReadingProgress(file.fileId)
        return PreviewTarget(
            local,
            file.name.ifBlank { local.name },
            file.fileId,
            file.courseId,
            siblings,
            initialPage = progress?.takeUnless { it.isMedia }?.position ?: -1,
            initialMediaSec = progress?.takeIf { it.isMedia }?.position ?: -1,
        )
    }

    fun navigatePreviewSibling(delta: Int) {
        val current = _previewTarget.value ?: return
        val index = current.siblings.indexOfFirst { it.fileId == current.fileId }
        val sibling = current.siblings.getOrNull(index + delta) ?: return
        previewJob?.cancel()
        previewJob =
            viewModelScope.launch {
                val target =
                    withContext(Dispatchers.IO) {
                        repo.getFile(sibling.fileId)?.let { buildPreviewTarget(it) }
                    }
                ensureActive()
                if (target != null) _previewTarget.value = target
                else showMessage("该文件已不在本地，请刷新后重试")
            }
    }

    fun closePreview() {
        previewJob?.cancel()
        _previewTarget.value = null
    }

    fun reportReadingPage(page: Int, total: Int) {
        val target = _previewTarget.value ?: return
        reportProgress(target.fileId, target.courseId, page, total, false)
    }

    fun reportReadingMediaSec(sec: Int, totalSec: Int) {
        val target = _previewTarget.value ?: return
        reportProgress(target.fileId, target.courseId, sec, totalSec, true)
    }

    fun reportProgress(fileId: Long, courseId: Long, position: Int, total: Int, media: Boolean) {
        if (fileId <= 0 || position < 0) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repo.setReadingProgress(fileId, courseId, position, total, media)
                _dataVersion.update { it + 1 }
            }
        }
    }

    fun shareFile(file: FileItem) {
        FileUtils.shareFile(getApplication(), file)
    }

    private fun showMessage(message: String) {
        messageChannel.trySend(message)
    }

    override fun onCleared() {
        viewModelScope.coroutineContext[Job]?.invokeOnCompletion {
            CoroutineScope(Dispatchers.IO).launch { repo.close() }
        }
        super.onCleared()
    }
}
