package com.nextcloud.sync.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.DefaultFolderPreference
import com.nextcloud.sync.utils.HiddenFilesPreference
import com.nextcloud.sync.utils.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val defaultFolderName: String = "Nextcloud",
    val serverUrlError: String? = null,
    val usernameError: String? = null,
    val defaultFolderNameError: String? = null,
    val currentTheme: String = ThemePreference.THEME_AUTO,
    val themeDropdownExpanded: Boolean = false,
    val showHiddenFiles: Boolean = false,
    val isLoading: Boolean = true,
    val showLogoutDialog: Boolean = false,
    val successMessage: String? = null,
    val shouldNavigateToLogin: Boolean = false
)

sealed class SettingsEvent {
    data class ServerUrlChanged(val url: String) : SettingsEvent()
    data class UsernameChanged(val username: String) : SettingsEvent()
    data class DefaultFolderNameChanged(val folderName: String) : SettingsEvent()
    object SaveClicked : SettingsEvent()
    data class ThemeSelected(val theme: String) : SettingsEvent()
    data class ThemeDropdownExpandedChanged(val expanded: Boolean) : SettingsEvent()
    data class ShowHiddenFilesToggled(val show: Boolean) : SettingsEvent()
    object LogoutClicked : SettingsEvent()
    object LogoutConfirmed : SettingsEvent()
    object LogoutDialogDismissed : SettingsEvent()
    object MessageDismissed : SettingsEvent()
}

class SettingsViewModel(
    private val accountRepository: AccountRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var accountId: Long = 0

    init {
        loadSettings()
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ServerUrlChanged -> {
                _uiState.update { it.copy(serverUrl = event.url, serverUrlError = null) }
            }
            is SettingsEvent.UsernameChanged -> {
                _uiState.update { it.copy(username = event.username, usernameError = null) }
            }
            is SettingsEvent.DefaultFolderNameChanged -> {
                _uiState.update { it.copy(defaultFolderName = event.folderName, defaultFolderNameError = null) }
            }
            is SettingsEvent.SaveClicked -> saveSettings()
            is SettingsEvent.ThemeSelected -> {
                ThemePreference.setThemeMode(context, event.theme)
                _uiState.update { it.copy(currentTheme = event.theme) }
            }
            is SettingsEvent.ThemeDropdownExpandedChanged -> {
                _uiState.update { it.copy(themeDropdownExpanded = event.expanded) }
            }
            is SettingsEvent.ShowHiddenFilesToggled -> {
                HiddenFilesPreference.setShowHidden(context, event.show)
                _uiState.update { it.copy(showHiddenFiles = event.show) }
            }
            is SettingsEvent.LogoutClicked -> {
                _uiState.update { it.copy(showLogoutDialog = true) }
            }
            is SettingsEvent.LogoutConfirmed -> performLogout()
            is SettingsEvent.LogoutDialogDismissed -> {
                _uiState.update { it.copy(showLogoutDialog = false) }
            }
            is SettingsEvent.MessageDismissed -> {
                _uiState.update { it.copy(successMessage = null) }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val currentTheme = ThemePreference.getThemeMode(context)
            val defaultFolderName = DefaultFolderPreference.getDefaultFolderName(context)
            val showHiddenFiles = HiddenFilesPreference.getShowHidden(context)
            val account = accountRepository.getActiveAccount()

            if (account != null) {
                accountId = account.id
                _uiState.update {
                    it.copy(
                        serverUrl = account.serverUrl,
                        username = account.username,
                        defaultFolderName = defaultFolderName,
                        currentTheme = currentTheme,
                        showHiddenFiles = showHiddenFiles,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        defaultFolderName = defaultFolderName,
                        currentTheme = currentTheme,
                        showHiddenFiles = showHiddenFiles,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun saveSettings() {
        val serverUrl = _uiState.value.serverUrl.trim()
        val username = _uiState.value.username.trim()
        val defaultFolderName = _uiState.value.defaultFolderName.trim()

        // Validation
        var hasError = false
        if (serverUrl.isEmpty()) {
            _uiState.update { it.copy(serverUrlError = "Server URL is required") }
            hasError = true
        }
        if (username.isEmpty()) {
            _uiState.update { it.copy(usernameError = "Username is required") }
            hasError = true
        }
        if (defaultFolderName.isEmpty()) {
            _uiState.update { it.copy(defaultFolderNameError = "Default folder name is required") }
            hasError = true
        } else if (!defaultFolderName.matches(Regex("^[a-zA-Z0-9_\\- ]+$"))) {
            _uiState.update { it.copy(defaultFolderNameError = "Only letters, numbers, spaces, hyphens and underscores allowed") }
            hasError = true
        }

        if (hasError) return

        // Add https:// prefix if no protocol is specified
        val normalizedUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "https://$serverUrl"
        } else {
            serverUrl
        }

        viewModelScope.launch {
            // Save default folder name preference
            DefaultFolderPreference.setDefaultFolderName(context, defaultFolderName)

            // Save account details
            val account = accountRepository.getActiveAccount()
            account?.let {
                val updatedAccount = it.copy(
                    serverUrl = normalizedUrl.trimEnd('/'),
                    username = username
                )
                accountRepository.updateAccount(updatedAccount)
                _uiState.update { state ->
                    state.copy(
                        serverUrl = normalizedUrl.trimEnd('/'),
                        successMessage = "Settings saved successfully"
                    )
                }
            }
        }
    }

    private fun performLogout() {
        viewModelScope.launch {
            val account = accountRepository.getActiveAccount()
            account?.let {
                accountRepository.deleteAccount(it)
            }
            _uiState.update {
                it.copy(
                    showLogoutDialog = false,
                    shouldNavigateToLogin = true
                )
            }
        }
    }
}
