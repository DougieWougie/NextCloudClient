package com.nextcloud.sync.controllers.auth

import com.nextcloud.sync.models.database.entities.AccountEntity
import com.nextcloud.sync.models.network.ConnectionResult
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.EncryptionUtil

class LoginController(
    private val accountRepository: AccountRepository
) {
    interface LoginCallback {
        fun onLoginSuccess(requiresTwoFactor: Boolean, accountId: Long)
        fun onLoginError(error: String)
        fun onValidationError(field: String, error: String)
    }

    suspend fun login(
        serverUrl: String,
        username: String,
        password: String,
        callback: LoginCallback
    ) {
        // Validate inputs
        if (!validateServerUrl(serverUrl)) {
            callback.onValidationError("server_url", "Invalid server URL")
            return
        }

        if (username.isBlank()) {
            callback.onValidationError("username", "Username is required")
            return
        }

        if (password.isBlank()) {
            callback.onValidationError("password", "Password is required")
            return
        }

        try {
            // Test WebDAV connection
            val normalizedUrl = normalizeServerUrl(serverUrl)
            val webDavClient = WebDavClient(normalizedUrl, username, password)
            val connectionResult = webDavClient.testConnection()

            when (connectionResult) {
                is ConnectionResult.Success -> {
                    // Deactivate all existing accounts
                    accountRepository.deactivateAllAccounts()

                    // Save account
                    val encryptedPassword = EncryptionUtil.encryptPassword(password)
                    val account = AccountEntity(
                        serverUrl = normalizedUrl,
                        username = username,
                        passwordEncrypted = encryptedPassword,
                        twoFactorEnabled = false
                    )

                    val accountId = accountRepository.insertAccount(account)
                    callback.onLoginSuccess(requiresTwoFactor = false, accountId = accountId)
                }

                is ConnectionResult.RequiresTwoFactor -> {
                    // Deactivate all existing accounts
                    accountRepository.deactivateAllAccounts()

                    // Save temporary credentials and redirect to 2FA
                    val encryptedPassword = EncryptionUtil.encryptPassword(password)
                    val account = AccountEntity(
                        serverUrl = normalizedUrl,
                        username = username,
                        passwordEncrypted = encryptedPassword,
                        twoFactorEnabled = true,
                        isActive = false // Not active until 2FA completed
                    )

                    val accountId = accountRepository.insertAccount(account)
                    callback.onLoginSuccess(requiresTwoFactor = true, accountId = accountId)
                }

                is ConnectionResult.Error -> {
                    callback.onLoginError(connectionResult.message)
                }
            }
        } catch (e: Exception) {
            callback.onLoginError("Connection failed: ${e.message}")
        }
    }

    private fun validateServerUrl(url: String): Boolean {
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun normalizeServerUrl(url: String): String {
        return url.trimEnd('/')
    }
}
