package com.nextcloud.sync

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import com.nextcloud.sync.utils.SafeLogger
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

        // Validate deep link intent before processing
        if (!validateDeepLinkIntent(intent)) {
            // Invalid deep link, finish activity
            finish()
            return
        }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!validateDeepLinkIntent(intent)) {
            SafeLogger.w("MainActivity", "Received invalid deep link intent in onNewIntent")
            // Don't process invalid intent
            return
        }
        setIntent(intent)
    }

    /**
     * Validates deep link intents to prevent malicious deep link attacks
     *
     * @param intent The intent to validate
     * @return true if the intent is valid or not a deep link, false if it's an invalid deep link
     */
    private fun validateDeepLinkIntent(intent: Intent?): Boolean {
        if (intent == null) {
            return true
        }

        val data: Uri? = intent.data
        if (data == null) {
            // Not a deep link, allow normal processing
            return true
        }

        // Validate scheme
        if (data.scheme != "nextcloudsync") {
            SafeLogger.w("MainActivity", "Invalid deep link scheme: ${data.scheme}")
            Toast.makeText(this, "Invalid deep link", Toast.LENGTH_SHORT).show()
            return false
        }

        // Validate host
        when (data.host) {
            "conflicts" -> {
                // Validate conflict ID parameter if present
                val conflictId = data.getQueryParameter("id")
                if (conflictId != null) {
                    val id = conflictId.toLongOrNull()
                    if (id == null || id <= 0 || id > Long.MAX_VALUE / 2) {
                        SafeLogger.w("MainActivity", "Invalid conflict ID in deep link: $conflictId")
                        Toast.makeText(this, "Invalid conflict ID", Toast.LENGTH_SHORT).show()
                        return false
                    }
                }
                return true
            }
            else -> {
                SafeLogger.w("MainActivity", "Unknown deep link host: ${data.host}")
                Toast.makeText(this, "Unknown deep link", Toast.LENGTH_SHORT).show()
                return false
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
