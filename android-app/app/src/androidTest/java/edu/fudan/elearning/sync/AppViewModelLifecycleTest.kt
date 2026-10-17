package edu.fudan.elearning.sync

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import edu.fudan.elearning.sync.auth.*
import edu.fudan.elearning.sync.data.*
import edu.fudan.elearning.sync.network.ApiClient
import edu.fudan.elearning.sync.sync.*
import edu.fudan.elearning.sync.ui.*
import edu.fudan.elearning.sync.util.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppViewModelLifecycleTest {
    private lateinit var app: Application
    private val store = ViewModelStore()
    private lateinit var file: File

    private fun main(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Before
    fun seed() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("fudan_sync", 0).edit().clear().commit()
        Prefs(app).backgroundSyncEnabled = false
        androidx.work.WorkManager.getInstance(app)
            .cancelAllWork()
            .result
            .get(10, java.util.concurrent.TimeUnit.SECONDS)
        SecurePrefs.clear(app)
        ApiClient.clearSession()
        app.deleteDatabase("fudan_sync.db")
        file =
            File(LocalStorage(app).root, "synthetic-lifecycle/read.txt").apply {
                parentFile!!.mkdirs()
                writeText("offline")
            }
        Repo(app).use { repo ->
            repo.upsertCourses(listOf(Course(1, "离线课程")))
            repo.upsertFile(
                FileItem(10, 1, "本地资料", "read.txt", localPath = file.path, status = "downloaded")
            )
        }
    }

    @After
    fun close() {
        main { store.clear() }
        SecurePrefs.clear(app)
        ApiClient.clearSession()
        file.delete()
    }

    private fun create(
        auth: suspend (String, String) -> LoginResult,
        sync: suspend (Boolean, (String, Int, Int, String, Long) -> Unit) -> SyncResult = { _, _ ->
            SyncResult()
        },
    ): AppViewModel {
        lateinit var vm: AppViewModel
        main {
            vm = AppViewModel(app, true, auth, sync)
            store.put("vm", vm)
        }
        return vm
    }

    @Test
    fun rememberedAccountWithoutPasswordOpensOfflineLibrary() = runBlocking {
        Prefs(app).apply {
            username = "synthetic"
            loggedIn = true
        }
        val vm = create({ _, _ -> error("must not authenticate without password") })
        withTimeout(10_000) {
            vm.courses.first { it.isNotEmpty() }
            vm.loginState.first { it is LoginState.LoggedIn }
        }
        assertTrue(vm.needsReauth.value)
        val record = Repo(app).use { it.getFile(10)!! }
        main { vm.openPreview(record) }
        withTimeout(10_000) { vm.previewTarget.first { it != null } }
        assertEquals(file.path, vm.previewTarget.value!!.file.path)
    }

    @Test
    fun automaticLoginKeepsCanvasCookieName() = runBlocking {
        Prefs(app).apply {
            username = "synthetic"
            loggedIn = true
        }
        SecurePrefs.savePassword(app, "synthetic-only")
        val synced = CompletableDeferred<Unit>()
        val vm =
            create(
                { _, _ ->
                    LoginResult.Success(AuthSession("synthetic-cookie", null, "_canvas_session"))
                },
                { _, _ ->
                    synced.complete(Unit)
                    SyncResult()
                },
            )
        withTimeout(10_000) { synced.await() }
        withTimeout(10_000) {
            while (vm.needsReauth.value || ApiClient.canvasSession.isEmpty()) delay(20)
        }
        assertEquals("_canvas_session", ApiClient.sessionCookieName)
    }

    @Test
    fun logoutCancelsSyncAndKeepsLocalData() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val vm =
            create(
                { _, _ -> LoginResult.Success(AuthSession("synthetic", null)) },
                { _, _ ->
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                },
            )
        withTimeout(10_000) { vm.loginState.first { it is LoginState.LoggedOut } }
        main { vm.login("synthetic", "test-only", false) }
        withTimeout(10_000) { entered.await() }
        main { vm.logout() }
        withTimeout(10_000) {
            stopped.await()
            vm.loginState.first { it is LoginState.LoggedOut }
            SyncGate.busy.first { !it }
        }
        assertFalse(vm.syncing.value)
        assertTrue(file.isFile)
        assertTrue(ApiClient.canvasSession.isEmpty())
        assertNull(SecurePrefs.loadPassword(app))
        Repo(app).use { assertNotNull(it.getFile(10)) }
    }

    @Test
    fun stopSyncPreservesSessionAndDeletionCannotRaceSync() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val vm =
            create(
                { _, _ -> LoginResult.Success(AuthSession("synthetic", null)) },
                { _, _ ->
                    entered.complete(Unit)
                    awaitCancellation()
                },
            )
        withTimeout(10_000) { vm.loginState.first { it is LoginState.LoggedOut } }
        val record = Repo(app).use { it.getFile(10)!! }
        main { vm.login("synthetic", "test-only", false) }
        withTimeout(10_000) { entered.await() }
        main { vm.deleteFile(record) }
        assertTrue(file.isFile)
        main { vm.stopSync() }
        withTimeout(10_000) {
            vm.syncing.first { !it }
            SyncGate.busy.first { !it }
        }
        assertEquals("stopped", vm.syncProgress.value.phase)
        assertTrue(vm.loginState.value is LoginState.LoggedIn)
        assertTrue(ApiClient.canvasSession.isNotEmpty())
    }
}
