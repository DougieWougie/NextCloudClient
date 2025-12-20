package com.nextcloud.sync.models.network

import android.content.Context
import com.nextcloud.sync.utils.CertificatePinningHelper
import com.nextcloud.sync.utils.InputValidator
import com.nextcloud.sync.utils.SafeLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Implements Nextcloud Login Flow v2
 * See: https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
 */
class NextcloudLoginFlow(
    private val context: Context,
    private val serverUrl: String
) {

    private val client = CertificatePinningHelper.createPinnedClient(
        context = context,
        serverUrl = serverUrl,
        connectTimeout = 30,
        readTimeout = 30
    )

    data class LoginCredentials(
        val serverUrl: String,
        val loginName: String,
        val appPassword: String
    )

    /**
     * Step 1: Initialize login flow and get the login URL
     */
    suspend fun initLoginFlow(): LoginFlowInitResult = withContext(Dispatchers.IO) {
        try {
            val initUrl = "$serverUrl/index.php/login/v2"

            val request = Request.Builder()
                .url(initUrl)
                .post("".toRequestBody(null))
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                response.close()
                return@withContext LoginFlowInitResult.Error("Server does not support Login Flow v2. HTTP ${response.code}")
            }

            val responseBody = response.body?.string() ?: ""
            response.close()

            // Validate and parse JSON safely
            val json = InputValidator.parseJsonSafely(responseBody)
                ?: return@withContext LoginFlowInitResult.Error("Invalid JSON response from server")

            // Validate required fields exist
            if (!json.has("poll") || !json.has("login")) {
                return@withContext LoginFlowInitResult.Error("Missing required fields in server response")
            }

            val poll = json.getJSONObject("poll")

            // Extract and validate poll token
            val pollTokenValidation = InputValidator.validateJsonString(poll, "token", required = true, maxLength = 512)
            if (!pollTokenValidation.isValid()) {
                return@withContext LoginFlowInitResult.Error("Invalid poll token: ${pollTokenValidation.getErrorOrNull()}")
            }
            val pollToken = poll.getString("token")

            // Extract and validate poll endpoint
            val pollEndpointValidation = InputValidator.validateJsonString(poll, "endpoint", required = true, maxLength = 2048)
            if (!pollEndpointValidation.isValid()) {
                return@withContext LoginFlowInitResult.Error("Invalid poll endpoint: ${pollEndpointValidation.getErrorOrNull()}")
            }
            val pollEndpoint = poll.getString("endpoint")

            // Validate it's a proper URL
            val urlValidation = InputValidator.validateServerUrl(pollEndpoint)
            if (!urlValidation.isValid()) {
                return@withContext LoginFlowInitResult.Error("Invalid poll endpoint URL: ${urlValidation.getErrorOrNull()}")
            }

            // Extract and validate login URL
            val loginUrlValidation = InputValidator.validateJsonString(json, "login", required = true, maxLength = 2048)
            if (!loginUrlValidation.isValid()) {
                return@withContext LoginFlowInitResult.Error("Invalid login URL: ${loginUrlValidation.getErrorOrNull()}")
            }
            val loginUrl = json.getString("login")

            // Validate it's a proper URL
            val loginUrlCheck = InputValidator.validateServerUrl(loginUrl)
            if (!loginUrlCheck.isValid()) {
                return@withContext LoginFlowInitResult.Error("Invalid login URL format: ${loginUrlCheck.getErrorOrNull()}")
            }

            LoginFlowInitResult.Success(
                loginUrl = loginUrl,
                pollToken = pollToken,
                pollEndpoint = pollEndpoint
            )
        } catch (e: Exception) {
            SafeLogger.e("NextcloudLoginFlow", "Failed to init login flow", e)
            LoginFlowInitResult.Error("Failed to initialize login: ${e.message}")
        }
    }

    /**
     * Step 2: Poll for credentials after user completes web login
     */
    suspend fun pollForCredentials(
        pollEndpoint: String,
        pollToken: String,
        maxAttempts: Int = 120 // Poll for up to 10 minutes (5 seconds * 120)
    ): LoginFlowPollResult = withContext(Dispatchers.IO) {
        try {
            // Wait a bit before starting to poll (let user see the page first)
            delay(3000)

            repeat(maxAttempts) { attempt ->
                SafeLogger.d("NextcloudLoginFlow", "Polling attempt ${attempt + 1}/$maxAttempts")

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(pollEndpoint)
                    .post("""{"token":"$pollToken"}""".toRequestBody(mediaType))
                    .build()

                val response = client.newCall(request).execute()

                SafeLogger.d("NextcloudLoginFlow", "Poll response: ${response.code}")

                when (response.code) {
                    200 -> {
                        // Success! Got credentials
                        val responseBody = response.body?.string() ?: ""
                        response.close()

                        SafeLogger.d("NextcloudLoginFlow", "Login successful!")

                        // Validate and parse JSON safely
                        val json = InputValidator.parseJsonSafely(responseBody)
                            ?: return@withContext LoginFlowPollResult.Error("Invalid JSON response from server")

                        // Validate server URL
                        val serverUrlValidation = InputValidator.validateJsonString(json, "server", required = true, maxLength = 2048)
                        if (!serverUrlValidation.isValid()) {
                            return@withContext LoginFlowPollResult.Error("Invalid server URL: ${serverUrlValidation.getErrorOrNull()}")
                        }
                        val serverUrl = json.getString("server")

                        val serverCheck = InputValidator.validateServerUrl(serverUrl)
                        if (!serverCheck.isValid()) {
                            return@withContext LoginFlowPollResult.Error("Invalid server URL format: ${serverCheck.getErrorOrNull()}")
                        }

                        // Validate login name
                        val loginNameValidation = InputValidator.validateJsonString(json, "loginName", required = true, maxLength = 256)
                        if (!loginNameValidation.isValid()) {
                            return@withContext LoginFlowPollResult.Error("Invalid login name: ${loginNameValidation.getErrorOrNull()}")
                        }
                        val loginName = json.getString("loginName")

                        val usernameCheck = InputValidator.validateUsername(loginName)
                        if (!usernameCheck.isValid()) {
                            return@withContext LoginFlowPollResult.Error("Invalid username: ${usernameCheck.getErrorOrNull()}")
                        }

                        // Validate app password
                        val appPasswordValidation = InputValidator.validateJsonString(json, "appPassword", required = true, maxLength = 512)
                        if (!appPasswordValidation.isValid()) {
                            return@withContext LoginFlowPollResult.Error("Invalid app password: ${appPasswordValidation.getErrorOrNull()}")
                        }
                        val appPassword = json.getString("appPassword")

                        val tokenCheck = InputValidator.validateToken(appPassword)
                        if (!tokenCheck.isValid()) {
                            return@withContext LoginFlowPollResult.Error("Invalid app password format: ${tokenCheck.getErrorOrNull()}")
                        }

                        return@withContext LoginFlowPollResult.Success(
                            LoginCredentials(
                                serverUrl = serverUrl,
                                loginName = loginName,
                                appPassword = appPassword
                            )
                        )
                    }
                    404 -> {
                        // Not ready yet, continue polling
                        response.close()
                        SafeLogger.d("NextcloudLoginFlow", "Not ready, waiting...")
                        delay(5000) // Wait 5 seconds before next poll
                    }
                    else -> {
                        // For 500 errors, keep retrying (might be transient)
                        val responseBody = response.body?.string() ?: ""
                        response.close()

                        SafeLogger.w("NextcloudLoginFlow", "Poll returned ${response.code}: $responseBody")

                        if (response.code >= 500) {
                            // Server error, retry
                            delay(5000)
                        } else {
                            // Client error, fail
                            return@withContext LoginFlowPollResult.Error("Polling failed: HTTP ${response.code}")
                        }
                    }
                }
            }

            // Timeout
            LoginFlowPollResult.Timeout
        } catch (e: Exception) {
            SafeLogger.e("NextcloudLoginFlow", "Polling failed", e)
            LoginFlowPollResult.Error("Polling failed: ${e.message}")
        }
    }
}

sealed class LoginFlowInitResult {
    data class Success(
        val loginUrl: String,
        val pollToken: String,
        val pollEndpoint: String
    ) : LoginFlowInitResult()

    data class Error(val message: String) : LoginFlowInitResult()
}

sealed class LoginFlowPollResult {
    data class Success(val credentials: NextcloudLoginFlow.LoginCredentials) : LoginFlowPollResult()
    object Timeout : LoginFlowPollResult()
    data class Error(val message: String) : LoginFlowPollResult()
}
