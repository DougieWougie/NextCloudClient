package com.nextcloud.sync.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nextcloud.sync.R
import com.nextcloud.sync.controllers.sync.SyncStats

class NotificationUtil(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val syncChannel = NotificationChannel(
                Constants.CHANNEL_ID_SYNC,
                "Sync Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows sync progress and status"
            }

            val conflictChannel = NotificationChannel(
                Constants.CHANNEL_ID_CONFLICT,
                "Sync Conflicts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when file conflicts are detected"
            }

            notificationManager.createNotificationChannel(syncChannel)
            notificationManager.createNotificationChannel(conflictChannel)
        }
    }

    fun showSyncProgressNotification(current: Int, total: Int) {
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ID_SYNC)
            .setContentTitle("Syncing files")
            .setContentText("$current of $total files")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setProgress(total, current, false)
            .setOngoing(true)
            .build()

        notificationManager.notify(Constants.NOTIFICATION_ID_SYNC, notification)
    }

    fun showSyncCompleteNotification(stats: SyncStats) {
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ID_SYNC)
            .setContentTitle("Sync complete")
            .setContentText("Uploaded: ${stats.uploaded}, Downloaded: ${stats.downloaded}")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(Constants.NOTIFICATION_ID_SYNC, notification)
    }

    fun showConflictNotification(conflictCount: Int) {
        // Create explicit intent to main activity with conflict resolution action
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            action = "com.nextcloud.sync.ACTION_VIEW_CONFLICTS"
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        } ?: Intent()

        // SECURITY: Use FLAG_IMMUTABLE to prevent intent modification (required for API 31+)
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.getActivity(
                context,
                Constants.NOTIFICATION_ID_CONFLICT,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getActivity(
                context,
                Constants.NOTIFICATION_ID_CONFLICT,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ID_CONFLICT)
            .setContentTitle("Sync conflicts detected")
            .setContentText("$conflictCount file(s) require your attention")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(Constants.NOTIFICATION_ID_CONFLICT, notification)
    }

    fun showSyncErrorNotification(errorMessage: String) {
        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_ID_SYNC)
            .setContentTitle("Sync failed")
            .setContentText(errorMessage)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(Constants.NOTIFICATION_ID_SYNC, notification)
    }

    fun cancelSyncNotification() {
        notificationManager.cancel(Constants.NOTIFICATION_ID_SYNC)
    }
}
