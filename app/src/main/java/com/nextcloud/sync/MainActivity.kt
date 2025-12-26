package com.nextcloud.sync

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.nextcloud.sync.models.database.AppDatabase
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.ui.components.LoadingIndicator
import com.nextcloud.sync.ui.navigation.NavGraph
import com.nextcloud.sync.ui.navigation.Screen
import com.nextcloud.sync.ui.theme.NextcloudSyncTheme
import com.nextcloud.sync.utils.ThemePreference
import kotlinx.coroutines.launch

/**
 * Single Activity for the entire Compose app
 *
 * Determines start destination based on account existence:
 * - If account exists: Navigate to Main screen
 * - If no account: Navigate to Login screen
 */
class MainActivity : ComponentActivity() {
    private var themeMode by mutableStateOf(ThemePreference.THEME_AUTO)
    private val themePrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "theme_mode") {
            themeMode = ThemePreference.getThemeMode(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Load initial theme preference
        themeMode = ThemePreference.getThemeMode(this)

        // Register listener for theme changes
        getSharedPreferences("theme_preferences", MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(themePrefsListener)

        setContent {
            val systemInDarkTheme = isSystemInDarkTheme()

            // Determine dark theme based on user preference
            val darkTheme = when (themeMode) {
                ThemePreference.THEME_DARK -> true
                ThemePreference.THEME_LIGHT -> false
                else -> systemInDarkTheme // THEME_AUTO
            }

            NextcloudSyncTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var startDestination by remember { mutableStateOf<String?>(null) }

                    // Determine start destination
                    if (startDestination == null) {
                        LoadingIndicator()

                        // Check for existing account
                        lifecycleScope.launch {
                            val db = AppDatabase.getInstance(this@MainActivity)
                            val accountRepository = AccountRepository(db.accountDao())
                            val account = accountRepository.getActiveAccount()

                            startDestination = if (account != null) {
                                Screen.Main.route
                            } else {
                                Screen.Login.route
                            }
                        }
                    } else {
                        val navController = rememberNavController()
                        NavGraph(
                            navController = navController,
                            context = this@MainActivity,
                            startDestination = startDestination!!
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister listener to prevent memory leaks
        getSharedPreferences("theme_preferences", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(themePrefsListener)
    }
}
