package com.nextcloud.sync

import android.app.Application
import com.nextcloud.sync.utils.SafeLogger
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.services.workers.SyncWorker
import com.nextcloud.sync.utils.ThemePreference

class Application : Application() {
    override fun onCreate() {
        super.onCreate()

        // Apply saved theme
        ThemePreference.applySavedTheme(this)

        // Initialize database
        AppDatabase.getInstance(this)

        // Schedule periodic sync
        SyncWorker.schedule(this)

        // Setup crash reporting
        setupCrashReporting()
    }

    private fun setupCrashReporting() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            SafeLogger.e("Application", "Uncaught exception in thread ${thread.name}", throwable)
            // Could integrate with Firebase Crashlytics or similar service here
        }
    }
}
