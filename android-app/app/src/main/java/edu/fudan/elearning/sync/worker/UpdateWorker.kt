package edu.fudan.elearning.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.fudan.elearning.sync.util.Prefs
import edu.fudan.elearning.sync.util.UpdateChecker
import java.util.concurrent.TimeUnit

class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { UpdateChecker.check() }
            .getOrNull()
            ?.let { update ->
                val prefs = Prefs(applicationContext)
                prefs.pendingUpdateVersion = update.version
                prefs.pendingUpdateUrl = update.url
            }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "fudan_update_check"

        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
