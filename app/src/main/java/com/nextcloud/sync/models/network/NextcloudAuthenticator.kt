package com.nextcloud.sync.models.network

import android.content.Context
import com.nextcloud.sync.utils.CertificatePinningHelper
import com.nextcloud.sync.utils.InputValidator
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NextcloudAuthenticator(private val context: Context) {
    private fun createClient(serverUrl: String): OkHttpClient {
        return CertificatePinningHelper.createPinnedClient(
            context = context,
            serverUrl = serverUrl,
            connectTimeout = 30,
            readTimeout = 30
        )
    }

    suspend fun verifyTwoFactor(
        serverUrl: String,
        username: String,
        password: String,
        otpCode: String
    ): TwoFactorResult = withContext(Dispatchers.IO) {
        try {
            // Nextcloud 2FA flow:
            // 1. Call OCS API with basic auth + OTP header
            // 2. If successful, create app password

            val client = createClient(serverUrl)
            val appPasswordUrl = "$serverUrl/ocs/v2.php/core/apppassword"

            val request = Request.Builder()
                .url(appPasswordUrl)
                .post("".toRequestBody(null))
                .header("OCS-APIREQUEST", "true")
                .header("Authorization", Credentials.basic(username, password))
                .header("X-NC-2FA-CODE", otpCode)
                .build()

            val response = client.newCall(request).execute()

            when (response.code) {
                200 -> {
                    val responseBody = response.body?.string() ?: ""
                    response.close()

                    SafeLogger.d("NextcloudAuthenticator", "2FA verification successful")

                    // Validate and parse JSON safely
                    val json = InputValidator.parseJsonSafely(responseBody)
                        ?: return@withContext TwoFactorResult.Error("Invalid JSON response from server")

                    // Validate OCS wrapper exists
                    if (!json.has("ocs")) {
                        return@withContext TwoFactorResult.Error("Missing OCS wrapper in server response")
                    }

                    val ocs = json.getJSONObject("ocs")

                    // Validate data object exists
                    if (!ocs.has("data")) {
                        return@withContext TwoFactorResult.Error("Missing data in server response")
                    }

                    val data = ocs.getJSONObject("data")

                    // Validate and extract app password
                    val appPasswordValidation = InputValidator.validateJsonString(data, "apppassword", required = true, maxLength = 512)
                    if (!appPasswordValidation.isValid()) {
                        return@withContext TwoFactorResult.Error("Invalid app password: ${appPasswordValidation.getErrorOrNull()}")
                    }
                    val appPassword = data.getString("apppassword")

                    // Validate it's a proper token
                    val tokenCheck = InputValidator.validateToken(appPassword)
                    if (!tokenCheck.isValid()) {
                        return@withContext TwoFactorResult.Error("Invalid app password format: ${tokenCheck.getErrorOrNull()}")
                    }

                    TwoFactorResult.Success(appPassword)
                }
                401, 403 -> {
                    response.close()
                    SafeLogger.w("NextcloudAuthenticator", "2FA verification failed - invalid code")
                    TwoFactorResult.InvalidCode
                }
                else -> {
                    val responseBody = response.body?.string() ?: ""
                    response.close()
                    SafeLogger.w("NextcloudAuthenticator", "2FA verification failed with HTTP ${response.code}")
                    TwoFactorResult.Error("Verification failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            SafeLogger.e("NextcloudAuthenticator", "2FA verification failed", e)
            TwoFactorResult.Error("Verification failed: ${e.message}")
        }
    }
}

sealed class TwoFactorResult {
    data class Success(val appPassword: String) : TwoFactorResult()
    object InvalidCode : TwoFactorResult()
    data class Error(val message: String) : TwoFactorResult()
}
