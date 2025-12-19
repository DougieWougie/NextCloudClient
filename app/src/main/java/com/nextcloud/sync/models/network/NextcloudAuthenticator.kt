package com.nextcloud.sync.models.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NextcloudAuthenticator {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
                    val jsonResponse = JSONObject(response.body?.string() ?: "")
                    val appPassword = jsonResponse.getJSONObject("ocs")
                        .getJSONObject("data")
                        .getString("apppassword")

                    TwoFactorResult.Success(appPassword)
                }
                401, 403 -> TwoFactorResult.InvalidCode
                else -> TwoFactorResult.Error("Verification failed: ${response.message}")
            }
        } catch (e: Exception) {
            TwoFactorResult.Error("Verification failed: ${e.message}")
        }
    }
}

sealed class TwoFactorResult {
    data class Success(val appPassword: String) : TwoFactorResult()
    object InvalidCode : TwoFactorResult()
    data class Error(val message: String) : TwoFactorResult()
}
