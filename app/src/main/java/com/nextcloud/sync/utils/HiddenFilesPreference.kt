package com.nextcloud.sync.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Utility for managing hidden files visibility preference.
 *
 * Hidden files are defined as:
 * - Files or directories starting with a period (.)
 * - Directories matching the user's email address
 */
object HiddenFilesPreference {
    private const val PREFS_NAME = "file_display_preferences"
    private const val KEY_SHOW_HIDDEN = "show_hidden_files"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the current show hidden files setting
     * @return true if hidden files should be shown, false otherwise (default: false)
     */
    fun getShowHidden(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_SHOW_HIDDEN, false)
    }

    /**
     * Save the show hidden files setting
     */
    fun setShowHidden(context: Context, show: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_SHOW_HIDDEN, show).apply()
    }

    /**
     * Check if a file or directory name is considered hidden.
     *
     * @param name File or directory name
     * @param userEmail User's email address (optional, for directory filtering)
     * @return true if the file/directory is hidden, false otherwise
     */
    fun isHidden(name: String, userEmail: String? = null): Boolean {
        // Files/directories starting with a period are hidden
        if (name.startsWith(".")) {
            return true
        }

        // Directories matching the user's email are hidden
        if (userEmail != null && name.equals(userEmail, ignoreCase = true)) {
            return true
        }

        return false
    }

    /**
     * Check if a path should be filtered out based on hidden files preference.
     *
     * @param name File or directory name
     * @param showHidden Current show hidden files preference
     * @param userEmail User's email address (optional)
     * @return true if the file should be filtered out (not shown), false if it should be shown
     */
    fun shouldFilter(name: String, showHidden: Boolean, userEmail: String? = null): Boolean {
        return !showHidden && isHidden(name, userEmail)
    }
}
