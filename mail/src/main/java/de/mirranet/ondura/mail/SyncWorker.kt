package de.mirranet.ondura.mail

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic sync while the app is in the background. The core reports new
 * mail through its event stream, which [OnduraMailApp] turns into
 * notifications. While the app is open, the core keeps the inbox current on
 * its own (IMAP IDLE / polling).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as OnduraMailApp
        return try {
            app.core.syncAllNow(timeoutSeconds = 8 * 60)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val NAME = "ondura-mail-sync"

        fun schedule(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
