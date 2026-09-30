package edu.fudan.elearning.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.fudan.elearning.sync.auth.LoginResult
import edu.fudan.elearning.sync.auth.UisAuthenticator
import edu.fudan.elearning.sync.data.Repo
import edu.fudan.elearning.sync.network.ApiClient
import edu.fudan.elearning.sync.network.CanvasApi
import edu.fudan.elearning.sync.sync.SyncEngine
import edu.fudan.elearning.sync.sync.SyncGate
import edu.fudan.elearning.sync.util.Prefs
import edu.fudan.elearning.sync.util.SecurePrefs
import java.util.concurrent.TimeUnit

/** 后台定期同步 Worker。默认每 15 分钟，用户可配置。 */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val username = prefs.username
        val password = SecurePrefs.loadPassword(applicationContext)

        if (username.isEmpty() || password.isNullOrEmpty()) {
            return Result.success()
        }

        // 静默登录
        val authResult = UisAuthenticator().login(username, password)
        if (authResult !is LoginResult.Success) {
            // 密码可能已失效：提示用户重新登录，而不是静默当作成功
            Notifier.notifySyncError(applicationContext, "自动登录失败，请打开应用重新登录")
            return Result.failure()
        }
        ApiClient.setSession(
            authResult.session.canvasSessionCookie,
            authResult.session.csrfToken,
            authResult.session.cookieName
        )

        // 同步：与手动同步互斥；已有同步在跑时直接跳过本次后台任务
        var recentChanges: List<edu.fudan.elearning.sync.data.FileChange> = emptyList()
        val result = SyncGate.runOrSkip {
            // 必须关闭：Worker 每轮新建连接，泄漏的 SQLiteDatabase 句柄会
            // 累积并加剧与 UI 连接的锁竞争
            val repo = Repo(applicationContext)
            try {
                val api = CanvasApi()
                val engine = SyncEngine(applicationContext, api, repo)
                val runResult = engine.sync(full = false)
                // 通知摘要：在连接关闭前取回本轮文件级变更（v1.1.0 变更摘要）
                recentChanges = runCatching {
                    repo.recentChanges(minOf(maxOf(runResult.filesDownloaded, 1), 12))
                }.getOrDefault(emptyList())
                runResult
            } finally {
                repo.close()
            }
        } ?: return Result.success()

        // 下载新文件后发通知（按课程列出文件名，不再只是计数）
        if (result.filesDownloaded > 0) {
            Notifier.notifySyncComplete(
                applicationContext, result.filesDownloaded, result.bytesDownloaded, recentChanges
            )
        }
        if (result.filesFailed > 0 && result.ok) {
            Notifier.notifySyncError(
                applicationContext,
                "有 ${result.filesFailed} 个文件未下载成功，下次同步会自动重试"
            )
        }
        prefs.lastSyncAt = System.currentTimeMillis()

        return when {
            result.needsReauth -> {
                Notifier.notifySyncError(applicationContext, "登录状态已失效，请打开应用重新登录")
                Result.failure()
            }
            !result.ok -> {
                if (result.retryable) Result.retry()
                else {
                    Notifier.notifySyncError(applicationContext, result.error ?: "同步失败")
                    Result.failure()
                }
            }
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "fudan_sync_periodic_work"
        private const val TAG = "fudan_sync"

        /** 按用户配置的间隔安排定期同步。 */
        fun schedule(context: Context, intervalMinutes: Int) {
            val minutes = intervalMinutes.coerceAtLeast(15)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** 立即触发一次同步。 */
        fun syncNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        /**
         * 取消全部同步任务（退出登录/关闭后台同步时调用）。
         *
         * 退出登录后继续跑周期任务只会在后台反复失败并弹「重新登录」通知，
         * 因此必须显式取消，而不是留给系统。
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }
    }
}
