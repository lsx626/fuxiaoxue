package edu.fudan.elearning.sync.worker

import android.content.Context
import androidx.work.*
import edu.fudan.elearning.sync.auth.*
import edu.fudan.elearning.sync.data.Repo
import edu.fudan.elearning.sync.network.*
import edu.fudan.elearning.sync.sync.*
import edu.fudan.elearning.sync.util.*
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = Prefs(applicationContext)
        if (!prefs.loggedIn || !prefs.backgroundSyncEnabled) return@withContext Result.success()
        try {
            // Authentication also mutates the process-wide session, so it belongs
            // inside the same gate as downloads and storage actions.
            SyncGate.runOrSkip {
                val password = SecurePrefs.loadPassword(applicationContext)
                if (prefs.username.isBlank() || password.isNullOrEmpty()) return@runOrSkip Result.success()
                val auth = UisAuthenticator().login(prefs.username, password)
                if (auth is LoginResult.Failure) {
                    if (auth.retryable && runAttemptCount < 3) return@runOrSkip Result.retry()
                    notifyErrorOnce(prefs, "自动登录失败，请打开应用重新登录")
                    return@runOrSkip Result.failure()
                }
                auth as LoginResult.Success
                ensureActive()
                if (!prefs.loggedIn || !prefs.backgroundSyncEnabled) return@runOrSkip Result.success()
                ApiClient.setSession(auth.session.canvasSessionCookie, auth.session.csrfToken, auth.session.cookieName)
                val repo = Repo(applicationContext)
                try {
                    val result = SyncEngine(applicationContext, CanvasApi(), repo).sync()
                    prefs.lastSyncAt = System.currentTimeMillis()
                    if (result.filesDownloaded > 0) Notifier.notifySyncComplete(
                        applicationContext, result.filesDownloaded, result.bytesDownloaded,
                        repo.recentChanges(12)
                    )
                    when (backgroundDecision(result, runAttemptCount)) {
                        BackgroundDecision.RETRY -> Result.retry()
                        BackgroundDecision.FAIL -> {
                            notifyErrorOnce(prefs, result.error ?: "同步未完成，请打开应用查看")
                            Result.failure()
                        }
                        BackgroundDecision.SUCCESS -> {
                            prefs.backgroundError = ""
                            Result.success()
                        }
                    }
                } finally { repo.close() }
            } ?: Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (runAttemptCount < 3) Result.retry()
            else { notifyErrorOnce(prefs, "后台同步未完成，请检查网络和存储空间"); Result.failure() }
        }
    }

    private fun notifyErrorOnce(prefs: Prefs, message: String) {
        if (prefs.backgroundError != message) Notifier.notifySyncError(applicationContext, message)
        prefs.backgroundError = message
    }

    companion object {
        private const val UNIQUE_WORK = "fudan_sync_periodic_work"
        private const val TAG = "fudan_sync"
        fun schedule(context: Context, intervalMinutes: Int) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG).build())
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }
}

internal enum class BackgroundDecision { SUCCESS, RETRY, FAIL }
internal fun backgroundDecision(result: SyncResult, attempt: Int): BackgroundDecision = when {
    result.needsReauth -> BackgroundDecision.FAIL
    !result.ok && result.retryable && attempt < 3 -> BackgroundDecision.RETRY
    !result.ok -> BackgroundDecision.FAIL
    else -> BackgroundDecision.SUCCESS
}
