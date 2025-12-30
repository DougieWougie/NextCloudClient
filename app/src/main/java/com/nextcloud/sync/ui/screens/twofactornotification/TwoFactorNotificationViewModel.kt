package com.nextcloud.sync.ui.screens.twofactornotification

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.controllers.auth.TwoFactorController
import com.nextcloud.sync.models.network.NextcloudAuthenticator
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.AuthRateLimiter
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TwoFactorNotificationViewModel(
    private val twoFactorController: TwoFactorController,
    private val accountId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(TwoFactorNotificationUiState())
    val uiState: StateFlow<TwoFactorNotificationUiState> = _uiState.asStateFlow()

    init {
        startVerification()
    }

    private fun startVerification() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    statusMessage = "Sending notification to your devices...",
                    errorMessage = null
                )
            }

            twoFactorController.verifyNotification(
                accountId = accountId,
                callback = object : TwoFactorController.TwoFactorCallback {
                    override fun onTwoFactorSuccess(accountId: Long) {
                        SafeLogger.d("TwoFactorNotificationViewModel", "Notification approved successfully")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isSuccess = true,
                                statusMessage = "Login approved!"
                            )
                        }
                    }

                    override fun onTwoFactorError(error: String) {
                        SafeLogger.e("TwoFactorNotificationViewModel", "Notification auth error: $error")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = error,
                                statusMessage = "Verification failed"
                            )
                        }
                    }

                    override fun onInvalidCode() {
                        // Not applicable for notification auth
                    }

                    override fun onTimeout() {
                        SafeLogger.w("TwoFactorNotificationViewModel", "Notification auth timed out")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                hasTimedOut = true,
                                statusMessage = "Request timed out",
                                errorMessage = "No response received within 90 seconds. Please try again or use a different method."
                            )
                        }
                    }

                    override fun onDenied() {
                        SafeLogger.w("TwoFactorNotificationViewModel", "Notification auth denied by user")
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = "Login request was denied",
                                statusMessage = "Access denied"
                            )
                        }
                    }
                }
            )

            // Update status message while polling
            _uiState.update {
                it.copy(statusMessage = "Waiting for approval...")
            }
        }
    }

    fun onCancelClicked() {
        _uiState.update {
            it.copy(
                isLoading = false,
                errorMessage = "Verification cancelled",
                statusMessage = "Cancelled"
            )
        }
    }

    fun onRetryClicked() {
        startVerification()
    }

    class Factory(
        private val twoFactorController: TwoFactorController,
        private val accountId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TwoFactorNotificationViewModel(twoFactorController, accountId) as T
        }
    }
}

data class TwoFactorNotificationUiState(
    val isLoading: Boolean = false,
    val isSuccess: Boolean = false,
    val hasTimedOut: Boolean = false,
    val statusMessage: String = "",
    val errorMessage: String? = null
)
