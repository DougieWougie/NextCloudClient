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
import kotlinx.coroutines.delay

class LoginController(
    private val context: Context,
    private val accountRepository: AccountRepository,
    private val rateLimiter: AuthRateLimiter
) {
    companion object {
        // SECURITY: Anti-enumeration delay to prevent timing attacks
        // This makes all failed login attempts take approximately the same time
        private const val ANTI_ENUMERATION_DELAY_MS = 500L

        // Generic error message to prevent account enumeration
        private const val GENERIC_AUTH_ERROR = "Authentication failed. Please check your credentials and try again."
    }

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
        val startTime = System.currentTimeMillis()

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

        // SECURITY: Check rate limiting BEFORE any authentication logic
        // This prevents timing-based enumeration attacks
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
                    // SECURITY: Use constant-time response and generic error message
                    // to prevent account enumeration
                    ensureMinimumResponseTime(startTime)

                    // Record failed login attempt
                    rateLimiter.recordFailedAttempt(rateLimitIdentifier)

                    // Use generic error message (no details about what failed)
                    callback.onLoginError(GENERIC_AUTH_ERROR)
                }
            }
        } catch (e: Exception) {
            // SECURITY: Use constant-time response and generic error message
            // to prevent account enumeration
            ensureMinimumResponseTime(startTime)

            // Record failed login attempt
            rateLimiter.recordFailedAttempt(rateLimitIdentifier)

            // Use generic error message (no exception details exposed)
            callback.onLoginError(GENERIC_AUTH_ERROR)
        }
    }

    /**
     * Ensures a minimum response time for failed authentication attempts.
     *
     * SECURITY: This prevents timing-based account enumeration attacks where
     * attackers could distinguish between:
     * - Valid username, wrong password (slower - server validates password)
     * - Invalid username (faster - server rejects immediately)
     *
     * @param startTime The timestamp when the authentication attempt started
     */
    private suspend fun ensureMinimumResponseTime(startTime: Long) {
        val elapsed = System.currentTimeMillis() - startTime
        val remainingDelay = ANTI_ENUMERATION_DELAY_MS - elapsed

        if (remainingDelay > 0) {
            delay(remainingDelay)
        }
    }

    private fun normalizeServerUrl(url: String): String {
        return url.trimEnd('/')
    }
}
