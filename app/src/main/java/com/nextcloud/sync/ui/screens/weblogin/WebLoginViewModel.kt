package com.nextcloud.sync.ui.screens.weblogin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.models.database.entities.AccountEntity
import com.nextcloud.sync.models.network.LoginFlowInitResult
import com.nextcloud.sync.models.network.LoginFlowPollResult
import com.nextcloud.sync.models.network.NextcloudLoginFlow
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.EncryptionUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebLoginUiState(
    val serverUrl: String = "",
    val loginUrl: String = "",
    val instructionText: String = "Please sign in to your Nextcloud account in the browser below",
    val isLoading: Boolean = false,
    val isPageLoading: Boolean = true,
    val errorMessage: String? = null,
    val loginSuccess: Boolean = false
)

sealed class WebLoginEvent {
    object PageFinished : WebLoginEvent()
    object ErrorDismissed : WebLoginEvent()
}

class WebLoginViewModel(
    private val serverUrl: String,
    private val accountRepository: AccountRepository,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebLoginUiState(serverUrl = serverUrl))
    val uiState: StateFlow<WebLoginUiState> = _uiState.asStateFlow()

    private lateinit var loginFlow: NextcloudLoginFlow
    private var pollToken: String = ""
    private var pollEndpoint: String = ""

    init {
        if (serverUrl.isEmpty()) {
            _uiState.update {
                it.copy(errorMessage = "Invalid server URL")
            }
        } else {
            startLoginFlow()
        }
    }

    fun onEvent(event: WebLoginEvent) {
        when (event) {
            is WebLoginEvent.PageFinished -> {
                _uiState.update { it.copy(isPageLoading = false) }
            }
            is WebLoginEvent.ErrorDismissed -> {
                _uiState.update { it.copy(errorMessage = null) }
            }
        }
    }

    private fun startLoginFlow() {
        _uiState.update { it.copy(isLoading = true) }
        loginFlow = NextcloudLoginFlow(context, serverUrl)

        viewModelScope.launch {
            when (val result = loginFlow.initLoginFlow()) {
                is LoginFlowInitResult.Success -> {
                    pollToken = result.pollToken
                    pollEndpoint = result.pollEndpoint

                    _uiState.update {
                        it.copy(
                            loginUrl = result.loginUrl,
                            isLoading = false,
                            instructionText = "Please sign in to your Nextcloud account in the browser below"
                        )
                    }

                    // Start polling for credentials
                    pollForCredentials()
                }
                is LoginFlowInitResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    private fun pollForCredentials() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    instructionText = "Sign in through the browser below, then we'll automatically connect"
                )
            }

            when (val result = loginFlow.pollForCredentials(pollEndpoint, pollToken)) {
                is LoginFlowPollResult.Success -> {
                    val credentials = result.credentials
                    _uiState.update {
                        it.copy(
                            instructionText = "Login successful! Setting up your account...",
                            isLoading = true
                        )
                    }
                    saveCredentialsAndContinue(credentials)
                }
                is LoginFlowPollResult.Timeout -> {
                    _uiState.update {
                        it.copy(errorMessage = "Login timeout. Please try again.")
                    }
                }
                is LoginFlowPollResult.Error -> {
                    _uiState.update {
                        it.copy(
                            errorMessage = "Login failed: ${result.message}\n\nPlease try again or check your server configuration."
                        )
                    }
                }
            }
        }
    }

    private suspend fun saveCredentialsAndContinue(credentials: NextcloudLoginFlow.LoginCredentials) {
        // Deactivate all existing accounts
        accountRepository.deactivateAllAccounts()

        // Encrypt the app password and auth token
        val encryptedPassword = EncryptionUtil.encryptPassword(credentials.appPassword)
        val encryptedAuthToken = EncryptionUtil.encryptPassword(credentials.appPassword)

        // Create new account
        val account = AccountEntity(
            serverUrl = credentials.serverUrl,
            username = credentials.loginName,
            passwordEncrypted = encryptedPassword,
            twoFactorEnabled = false, // Already handled via web login
            authTokenEncrypted = encryptedAuthToken,
            isActive = true
        )

        accountRepository.insertAccount(account)

        _uiState.update {
            it.copy(
                loginSuccess = true,
                isLoading = false
            )
        }
    }
}
