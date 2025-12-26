package com.nextcloud.sync.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.models.repository.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val serverUrl: String = "",
    val serverUrlError: String? = null,
    val isCheckingAccount: Boolean = true,
    val hasExistingAccount: Boolean = false,
    val shouldNavigateToWebLogin: String? = null, // Server URL to navigate to
    val shouldNavigateToMain: Boolean = false
)

sealed class LoginEvent {
    data class ServerUrlChanged(val url: String) : LoginEvent()
    object LoginClicked : LoginEvent()
    object NavigationHandled : LoginEvent()
}

class LoginViewModel(
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        checkExistingAccount()
    }

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.ServerUrlChanged -> {
                _uiState.update { it.copy(serverUrl = event.url, serverUrlError = null) }
            }
            is LoginEvent.LoginClicked -> validateAndProceed()
            is LoginEvent.NavigationHandled -> {
                _uiState.update {
                    it.copy(
                        shouldNavigateToWebLogin = null,
                        shouldNavigateToMain = false
                    )
                }
            }
        }
    }

    private fun checkExistingAccount() {
        viewModelScope.launch {
            try {
                val activeAccount = accountRepository.getActiveAccount()
                if (activeAccount != null) {
                    _uiState.update {
                        it.copy(
                            isCheckingAccount = false,
                            hasExistingAccount = true,
                            shouldNavigateToMain = true
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isCheckingAccount = false,
                            hasExistingAccount = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isCheckingAccount = false, hasExistingAccount = false)
                }
            }
        }
    }

    private fun validateAndProceed() {
        val serverUrl = _uiState.value.serverUrl.trim()

        // Basic validation
        if (serverUrl.isEmpty()) {
            _uiState.update { it.copy(serverUrlError = "Server URL is required") }
            return
        }

        // Add https:// prefix if no protocol is specified
        val normalizedUrl = if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            "https://$serverUrl"
        } else {
            serverUrl
        }

        // Navigate to web login
        _uiState.update {
            it.copy(
                serverUrlError = null,
                shouldNavigateToWebLogin = normalizedUrl.trimEnd('/')
            )
        }
    }
}
