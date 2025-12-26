package com.nextcloud.sync.ui.screens.twofactor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.controllers.auth.TwoFactorController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TwoFactorUiState(
    val otpCode: String = "",
    val otpError: String? = null,
    val isLoading: Boolean = false,
    val verificationError: String? = null,
    val verificationSuccess: Boolean = false
)

sealed class TwoFactorEvent {
    data class OtpCodeChanged(val code: String) : TwoFactorEvent()
    object VerifyClicked : TwoFactorEvent()
    object ErrorDismissed : TwoFactorEvent()
}

class TwoFactorViewModel(
    private val accountId: Long,
    private val twoFactorController: TwoFactorController
) : ViewModel() {

    private val _uiState = MutableStateFlow(TwoFactorUiState())
    val uiState: StateFlow<TwoFactorUiState> = _uiState.asStateFlow()

    fun onEvent(event: TwoFactorEvent) {
        when (event) {
            is TwoFactorEvent.OtpCodeChanged -> {
                _uiState.update { it.copy(otpCode = event.code, otpError = null) }
            }
            is TwoFactorEvent.VerifyClicked -> verifyOtp()
            is TwoFactorEvent.ErrorDismissed -> {
                _uiState.update { it.copy(verificationError = null) }
            }
        }
    }

    private fun verifyOtp() {
        val otpCode = _uiState.value.otpCode

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            twoFactorController.verifyTwoFactor(
                accountId = accountId,
                otpCode = otpCode,
                callback = object : TwoFactorController.TwoFactorCallback {
                    override fun onTwoFactorSuccess(accountId: Long) {
                        _uiState.update {
                            it.copy(isLoading = false, verificationSuccess = true)
                        }
                    }

                    override fun onTwoFactorError(error: String) {
                        _uiState.update {
                            it.copy(isLoading = false, verificationError = error)
                        }
                    }

                    override fun onInvalidCode() {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                otpError = "Invalid code. Please enter a 6-digit code."
                            )
                        }
                    }
                }
            )
        }
    }
}
