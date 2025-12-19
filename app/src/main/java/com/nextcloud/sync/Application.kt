package com.nextcloud.sync

import android.app.Application
import android.util.Log
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.services.workers.SyncWorker

class Application : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize database
        AppDatabase.getInstance(this)

        // Schedule periodic sync
        SyncWorker.schedule(this)

        // Setup crash reporting
        setupCrashReporting()
    }

    private fun setupCrashReporting() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("Application", "Uncaught exception in thread ${thread.name}", throwable)
            // Could integrate with Firebase Crashlytics or similar service here
        }
    }
}
