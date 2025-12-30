package com.nextcloud.sync.controllers.auth

import com.nextcloud.sync.models.network.NextcloudAuthenticator
import com.nextcloud.sync.models.network.NotificationAuthResult
import com.nextcloud.sync.models.network.PollResult
import com.nextcloud.sync.models.network.TwoFactorResult
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.AuthRateLimiter
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.RateLimitResult
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.delay

class TwoFactorController(
    private val accountRepository: AccountRepository,
    private val nextcloudAuthenticator: NextcloudAuthenticator,
    private val rateLimiter: AuthRateLimiter
) {
    interface TwoFactorCallback {
        fun onTwoFactorSuccess(accountId: Long)
        fun onTwoFactorError(error: String)
        fun onInvalidCode()
        fun onTimeout() // For notification-based auth timeout
        fun onDenied() // For notification-based auth denial
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

    /**
     * Verifies two-factor authentication using notification-based approval.
     *
     * This method initiates a push notification to the user's other logged-in devices
     * and polls for approval. If the user approves, an app password is generated and
     * stored. The method will timeout after 90 seconds.
     *
     * @param accountId The account ID to verify
     * @param callback Callback for handling results
     */
    suspend fun verifyNotification(
        accountId: Long,
        callback: TwoFactorCallback
    ) {
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
                    // Continue with notification auth
                }
            }

            val password = EncryptionUtil.decryptPassword(account.passwordEncrypted)

            // Initiate notification auth
            SafeLogger.d("TwoFactorController", "Initiating notification auth for account $accountId")
            val initiateResult = nextcloudAuthenticator.initiateNotificationAuth(
                serverUrl = account.serverUrl,
                username = account.username,
                password = password
            )

            when (initiateResult) {
                is NotificationAuthResult.Success -> {
                    val token = initiateResult.token
                    SafeLogger.d("TwoFactorController", "Got polling token, starting polling loop")

                    // Start polling loop
                    val startTime = System.currentTimeMillis()
                    val timeoutMillis = 90_000L // 90 seconds timeout
                    val pollIntervalMillis = 2_500L // Poll every 2.5 seconds

                    while (true) {
                        // Check for timeout
                        if (System.currentTimeMillis() - startTime > timeoutMillis) {
                            SafeLogger.w("TwoFactorController", "Notification auth timed out")
                            rateLimiter.recordFailedAttempt(rateLimitIdentifier)
                            callback.onTimeout()
                            return
                        }

                        // Poll for approval
                        val pollResult = nextcloudAuthenticator.pollNotificationAuth(
                            serverUrl = account.serverUrl,
                            username = account.username,
                            password = password,
                            token = token
                        )

                        when (pollResult) {
                            is PollResult.Pending -> {
                                // Still waiting, continue polling
                                SafeLogger.d("TwoFactorController", "Notification still pending, waiting...")
                                delay(pollIntervalMillis)
                            }

                            is PollResult.Approved -> {
                                SafeLogger.d("TwoFactorController", "Notification approved!")

                                // Encrypt and store app password
                                val encryptedAuthToken = EncryptionUtil.encryptPassword(pollResult.appPassword)
                                val updatedAccount = account.copy(
                                    authTokenEncrypted = encryptedAuthToken,
                                    isActive = true
                                )
                                accountRepository.updateAccount(updatedAccount)

                                // Record successful 2FA verification
                                rateLimiter.recordSuccessfulAttempt(rateLimitIdentifier)

                                callback.onTwoFactorSuccess(accountId)
                                return
                            }

                            is PollResult.Denied -> {
                                SafeLogger.w("TwoFactorController", "Notification denied by user")
                                rateLimiter.recordFailedAttempt(rateLimitIdentifier)
                                callback.onDenied()
                                return
                            }

                            is PollResult.Error -> {
                                SafeLogger.e("TwoFactorController", "Polling error: ${pollResult.message}")
                                rateLimiter.recordFailedAttempt(rateLimitIdentifier)
                                callback.onTwoFactorError(pollResult.message)
                                return
                            }
                        }
                    }
                }

                is NotificationAuthResult.Error -> {
                    SafeLogger.e("TwoFactorController", "Failed to initiate notification auth: ${initiateResult.message}")
                    rateLimiter.recordFailedAttempt(rateLimitIdentifier)
                    callback.onTwoFactorError(initiateResult.message)
                }
            }
        } catch (e: Exception) {
            SafeLogger.e("TwoFactorController", "Notification auth failed", e)
            val rateLimitIdentifier = "2fa:$accountId"
            rateLimiter.recordFailedAttempt(rateLimitIdentifier)
            callback.onTwoFactorError("Verification failed: ${e.message}")
        }
    }
}
