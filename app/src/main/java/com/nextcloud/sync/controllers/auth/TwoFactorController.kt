package com.nextcloud.sync.controllers.auth

import com.nextcloud.sync.models.network.NextcloudAuthenticator
import com.nextcloud.sync.models.network.TwoFactorResult
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.utils.EncryptionUtil

class TwoFactorController(
    private val accountRepository: AccountRepository,
    private val nextcloudAuthenticator: NextcloudAuthenticator
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
                    // Update account with auth token
                    val updatedAccount = account.copy(
                        authToken = result.appPassword,
                        isActive = true
                    )
                    accountRepository.updateAccount(updatedAccount)
                    callback.onTwoFactorSuccess(accountId)
                }

                is TwoFactorResult.InvalidCode -> {
                    callback.onInvalidCode()
                }

                is TwoFactorResult.Error -> {
                    callback.onTwoFactorError(result.message)
                }
            }
        } catch (e: Exception) {
            callback.onTwoFactorError("Verification failed: ${e.message}")
        }
    }
}
