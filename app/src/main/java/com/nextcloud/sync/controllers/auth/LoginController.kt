package com.nextcloud.sync.controllers.auth

import android.content.Context
import com.nextcloud.sync.models.database.entities.AccountEntity
import com.nextcloud.sync.models.network.ConnectionResult
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.AuthRateLimiter
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.InputValidator
import com.nextcloud.sync.utils.RateLimitResult

class LoginController(
    private val context: Context,
    private val accountRepository: AccountRepository,
    private val rateLimiter: AuthRateLimiter
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
        val urlValidation = InputValidator.validateServerUrl(serverUrl)
        if (!urlValidation.isValid()) {
            callback.onValidationError("server_url", urlValidation.getErrorOrNull() ?: "Invalid server URL")
            return
        }

        val usernameValidation = InputValidator.validateUsername(username)
        if (!usernameValidation.isValid()) {
            callback.onValidationError("username", usernameValidation.getErrorOrNull() ?: "Invalid username")
            return
        }

        val passwordValidation = InputValidator.validatePassword(password)
        if (!passwordValidation.isValid()) {
            callback.onValidationError("password", passwordValidation.getErrorOrNull() ?: "Invalid password")
            return
        }

        // Check rate limiting
        val rateLimitIdentifier = "login:$username"
        when (val rateLimitResult = rateLimiter.checkAttempt(rateLimitIdentifier)) {
            is RateLimitResult.Blocked -> {
                callback.onLoginError("Too many failed attempts. Please wait ${rateLimitResult.getWaitTimeFormatted()} before trying again.")
                return
            }
            is RateLimitResult.Allowed -> {
                // Continue with login
            }
        }

        try {
            // Test WebDAV connection
            val normalizedUrl = normalizeServerUrl(serverUrl)
            val webDavClient = WebDavClient(context, normalizedUrl, username, password)
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

                    // Record successful login
                    rateLimiter.recordSuccessfulAttempt(rateLimitIdentifier)

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

                    // Don't record as failed - user needs to complete 2FA
                    // Rate limiting for 2FA will be handled separately

                    callback.onLoginSuccess(requiresTwoFactor = true, accountId = accountId)
                }

                is ConnectionResult.Error -> {
                    // Record failed login attempt
                    rateLimiter.recordFailedAttempt(rateLimitIdentifier)
                    callback.onLoginError(connectionResult.message)
                }
            }
        } catch (e: Exception) {
            // Record failed login attempt
            rateLimiter.recordFailedAttempt(rateLimitIdentifier)
            callback.onLoginError("Connection failed: ${e.message}")
        }
    }

    private fun normalizeServerUrl(url: String): String {
        return url.trimEnd('/')
    }
}
