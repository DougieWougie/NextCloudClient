package com.nextcloud.sync.controllers.auth

import com.nextcloud.sync.models.network.NextcloudAuthenticator
import com.nextcloud.sync.models.network.TwoFactorResult
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.AuthRateLimiter
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.RateLimitResult

class TwoFactorController(
    private val accountRepository: AccountRepository,
    private val nextcloudAuthenticator: NextcloudAuthenticator,
    private val rateLimiter: AuthRateLimiter
) {
    interface TwoFactorCallback {
        fun onTwoFactorSuccess(accountId: Long)
        fun onTwoFactorError(error: String)
        fun onInvalidCode()
    }

    suspend fun verifyTwoFactor(
        accountId: Long,
        otpCode: String,
        callback: TwoFactorCallback
    ) {
        if (otpCode.length != 6 || !otpCode.all { it.isDigit() }) {
            callback.onInvalidCode()
            return
        }

        try {
            val account = accountRepository.getAccountById(accountId)
            if (account == null) {
                callback.onTwoFactorError("Account not found")
                return
            }

            // Check rate limiting for 2FA attempts
            val rateLimitIdentifier = "2fa:$accountId"
            when (val rateLimitResult = rateLimiter.checkAttempt(rateLimitIdentifier)) {
                is RateLimitResult.Blocked -> {
                    callback.onTwoFactorError("Too many failed attempts. Please wait ${rateLimitResult.getWaitTimeFormatted()} before trying again.")
                    return
                }
                is RateLimitResult.Allowed -> {
                    // Continue with 2FA verification
                }
            }

            val password = EncryptionUtil.decryptPassword(account.passwordEncrypted)

            // Verify OTP and get app password/token
            val result = nextcloudAuthenticator.verifyTwoFactor(
                serverUrl = account.serverUrl,
                username = account.username,
                password = password,
                otpCode = otpCode
            )

            when (result) {
                is TwoFactorResult.Success -> {
                    // Encrypt and update account with auth token
                    val encryptedAuthToken = EncryptionUtil.encryptPassword(result.appPassword)
                    val updatedAccount = account.copy(
                        authTokenEncrypted = encryptedAuthToken,
                        isActive = true
                    )
                    accountRepository.updateAccount(updatedAccount)

                    // Record successful 2FA verification
                    rateLimiter.recordSuccessfulAttempt(rateLimitIdentifier)

                    callback.onTwoFactorSuccess(accountId)
                }

                is TwoFactorResult.InvalidCode -> {
                    // Record failed 2FA attempt
                    rateLimiter.recordFailedAttempt(rateLimitIdentifier)
                    callback.onInvalidCode()
                }

                is TwoFactorResult.Error -> {
                    // Record failed 2FA attempt
                    rateLimiter.recordFailedAttempt(rateLimitIdentifier)
                    callback.onTwoFactorError(result.message)
                }
            }
        } catch (e: Exception) {
            // Record failed attempt on exception
            val rateLimitIdentifier = "2fa:$accountId"
            rateLimiter.recordFailedAttempt(rateLimitIdentifier)
            callback.onTwoFactorError("Verification failed: ${e.message}")
        }
    }
}
