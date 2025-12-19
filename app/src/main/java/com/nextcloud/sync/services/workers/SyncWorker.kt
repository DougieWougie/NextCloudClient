package com.nextcloud.sync.services.workers

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import com.nextcloud.sync.controllers.sync.SyncController
import com.nextcloud.sync.controllers.sync.SyncStats
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.ConflictRepository
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.Constants
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.NetworkUtil
import com.nextcloud.sync.utils.NotificationUtil
import java.util.concurrent.TimeUnit

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val db = AppDatabase.getInstance(context)
    private val folderRepository = FolderRepository(db.folderDao())
    private val fileRepository = FileRepository(db.fileDao())
    private val conflictRepository = ConflictRepository(db.conflictDao())
    private val accountRepository = AccountRepository(db.accountDao())
    private val notificationUtil = NotificationUtil(context)

    override suspend fun doWork(): Result {
        try {
            // Check if WiFi-only constraint needs to be enforced
            val isWifiRequired = inputData.getBoolean("wifi_only", false)
            if (isWifiRequired && !NetworkUtil.isWifiConnected(applicationContext)) {
                return Result.retry()
            }

            // Get active account
            val account = accountRepository.getActiveAccount()
                ?: return Result.failure()

            // Create WebDAV client
            val password = EncryptionUtil.decryptPassword(account.passwordEncrypted)
            val authToken = account.authToken ?: password
            val webDavClient = WebDavClient(account.serverUrl, account.username, authToken)

            // Get sync-enabled folders
            val folders = folderRepository.getSyncEnabledFolders()

            if (folders.isEmpty()) {
                return Result.success()
            }

            // Sync each folder
            val syncController = SyncController(
                fileRepository,
                folderRepository,
                conflictRepository,
                webDavClient
            )

            var totalUploaded = 0
            var totalDownloaded = 0
            var totalConflicts = 0

            folders.forEach { folder ->
                val stats = syncFolder(syncController, folder.id)
                totalUploaded += stats.uploaded
                totalDownloaded += stats.downloaded
                totalConflicts += stats.conflicts
            }

            // Update last sync time
            accountRepository.updateLastSync(account.id, System.currentTimeMillis())

            // Show completion notification
            notificationUtil.showSyncCompleteNotification(
                SyncStats(totalUploaded, totalDownloaded, totalConflicts)
            )

            // Show conflict notification if needed
            if (totalConflicts > 0) {
                notificationUtil.showConflictNotification(totalConflicts)
            }

            return Result.success()
        } catch (e: Exception) {
            Log.e("SyncWorker", "Sync failed", e)
            return Result.retry()
        }
    }

    private suspend fun syncFolder(
        syncController: SyncController,
        folderId: Long
    ): SyncStats {
        var stats = SyncStats(0, 0, 0)

        syncController.syncFolder(folderId, object : SyncController.SyncCallback {
            override fun onSyncStarted(folderId: Long) {
                // Update notification
            }

            override fun onSyncProgress(current: Int, total: Int) {
                notificationUtil.showSyncProgressNotification(current, total)
            }

            override fun onSyncComplete(syncStats: SyncStats) {
                stats = syncStats
            }

            override fun onSyncError(error: String) {
                Log.e("SyncWorker", "Sync error: $error")
            }

            override fun onConflictDetected(conflictId: Long) {
                // Will be notified after all folders synced
            }
        })

        return stats
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                Constants.SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                Constants.WORK_NAME_PERIODIC_SYNC,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
        }

        fun scheduleImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(Constants.WORK_NAME_PERIODIC_SYNC)
        }
    }
}
