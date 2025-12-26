package com.nextcloud.sync.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages the default local folder name preference.
 *
 * This preference determines the name of the default local folder that will be
 * automatically created when adding a new sync folder. The default folder will
 * be created in the app's external files directory.
 */
object DefaultFolderPreference {
    private const val PREFS_NAME = "folder_preferences"
    private const val KEY_DEFAULT_FOLDER_NAME = "default_folder_name"
    private const val DEFAULT_VALUE = "Nextcloud"

    /**
     * Get the default folder name preference.
     *
     * @param context Application context
     * @return The configured default folder name (defaults to "Nextcloud")
     */
    fun getDefaultFolderName(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_DEFAULT_FOLDER_NAME, DEFAULT_VALUE) ?: DEFAULT_VALUE
    }

    /**
     * Set the default folder name preference.
     *
     * @param context Application context
     * @param folderName The new default folder name
     */
    fun setDefaultFolderName(context: Context, folderName: String) {
        val prefs = getPrefs(context)
        prefs.edit()
            .putString(KEY_DEFAULT_FOLDER_NAME, folderName)
            .apply()
    }

    /**
     * Get the full path for a subfolder within the default folder.
     *
     * This creates a path like: /storage/emulated/0/Android/data/com.nextcloud.sync/files/Nextcloud/Photos
     * where "Nextcloud" is the default folder name and "Photos" is the subfolder.
     *
     * @param context Application context
     * @param subfolder Optional subfolder name (e.g., "Photos", "Documents")
     * @return Full path to the folder
     */
    fun getDefaultFolderPath(context: Context, subfolder: String? = null): String {
        val baseDir = context.getExternalFilesDir(null)
        val defaultFolderName = getDefaultFolderName(context)

        return if (subfolder != null && subfolder.isNotBlank()) {
            "${baseDir?.absolutePath}/$defaultFolderName/$subfolder"
        } else {
            "${baseDir?.absolutePath}/$defaultFolderName"
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
