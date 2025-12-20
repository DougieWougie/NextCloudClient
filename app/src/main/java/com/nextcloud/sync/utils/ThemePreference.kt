package com.nextcloud.sync.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

object ThemePreference {
    private const val PREFS_NAME = "theme_preferences"
    private const val KEY_THEME = "theme_mode"

    const val THEME_AUTO = "auto"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the current theme mode setting
     */
    fun getThemeMode(context: Context): String {
        return getPreferences(context).getString(KEY_THEME, THEME_AUTO) ?: THEME_AUTO
    }

    /**
     * Save the theme mode setting
     */
    fun setThemeMode(context: Context, mode: String) {
        getPreferences(context).edit().putString(KEY_THEME, mode).apply()
        applyTheme(mode)
    }

    /**
     * Apply the theme based on the mode
     */
    fun applyTheme(mode: String) {
        when (mode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_AUTO -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    /**
     * Apply the saved theme on app startup
     */
    fun applySavedTheme(context: Context) {
        val savedTheme = getThemeMode(context)
        applyTheme(savedTheme)
    }

    /**
     * Get display name for theme mode
     */
    fun getThemeDisplayName(mode: String): String {
        return when (mode) {
            THEME_LIGHT -> "Light"
            THEME_DARK -> "Dark"
            THEME_AUTO -> "Auto (System)"
            else -> "Auto (System)"
        }
    }
}
