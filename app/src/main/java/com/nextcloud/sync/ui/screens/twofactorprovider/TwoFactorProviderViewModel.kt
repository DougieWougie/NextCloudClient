package com.nextcloud.sync.ui.screens.twofactorprovider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nextcloud.sync.models.data.TwoFactorProvider
import com.nextcloud.sync.models.data.TwoFactorProviderType
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

class TwoFactorProviderViewModel(
    private val accountRepository: AccountRepository,
    private val accountId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(TwoFactorProviderUiState())
    val uiState: StateFlow<TwoFactorProviderUiState> = _uiState.asStateFlow()

    init {
        loadProviders()
    }

    private fun loadProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }

            try {
                val account = accountRepository.getAccountById(accountId)
                if (account == null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = "Account not found"
                        )
                    }
                    return@launch
                }

                // Parse providers from JSON
                val providers = account.twoFactorProviders?.let { jsonString ->
                    parseProviders(jsonString)
                } ?: emptyList()

                if (providers.isEmpty()) {
                    // No providers available, default to TOTP
                    SafeLogger.w("TwoFactorProviderViewModel", "No providers found, defaulting to TOTP")
                    _uiState.update {
                        it.copy(
                            providers = listOf(
                                TwoFactorProvider(
                                    id = "totp",
                                    displayName = "Authenticator app (TOTP)",
                                    type = TwoFactorProviderType.TOTP
                                )
                            ),
                            isLoading = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            providers = providers,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                SafeLogger.e("TwoFactorProviderViewModel", "Failed to load providers", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load 2FA providers: ${e.message}"
                    )
                }
            }
        }
    }

    private fun parseProviders(jsonString: String): List<TwoFactorProvider> {
        return try {
            val jsonArray = JSONArray(jsonString)
            val providers = mutableListOf<TwoFactorProvider>()

            for (i in 0 until jsonArray.length()) {
                val providerJson = jsonArray.getJSONObject(i)
                val id = providerJson.getString("id")
                val displayName = providerJson.getString("displayName")
                val type = TwoFactorProviderType.valueOf(providerJson.getString("type"))

                providers.add(TwoFactorProvider(id, displayName, type))
            }

            providers
        } catch (e: Exception) {
            SafeLogger.e("TwoFactorProviderViewModel", "Failed to parse providers JSON", e)
            emptyList()
        }
    }

    fun onProviderSelected(provider: TwoFactorProvider) {
        _uiState.update { it.copy(selectedProvider = provider) }
    }

    class Factory(
        private val accountRepository: AccountRepository,
        private val accountId: Long
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TwoFactorProviderViewModel(accountRepository, accountId) as T
        }
    }
}

data class TwoFactorProviderUiState(
    val providers: List<TwoFactorProvider> = emptyList(),
    val selectedProvider: TwoFactorProvider? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
