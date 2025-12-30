package com.nextcloud.sync.models.network

import android.content.Context
import com.nextcloud.sync.models.data.TwoFactorProvider
import com.nextcloud.sync.models.data.TwoFactorProviderType
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

                    // SECURITY: Validate Nextcloud app password format (xxxxx-xxxxx-xxxxx-xxxxx-xxxxx)
                    val tokenCheck = InputValidator.validateNextcloudAppPassword(appPassword)
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

    /**
     * Fetches available 2FA providers from the server.
     *
     * This method attempts to call the login challenge endpoint to retrieve
     * the list of available two-factor authentication providers.
     *
     * @param serverUrl The Nextcloud server URL
     * @param username The username
     * @param password The password
     * @return List of available 2FA providers
     */
    suspend fun fetch2FAProviders(
        serverUrl: String,
        username: String,
        password: String
    ): List<TwoFactorProvider> = withContext(Dispatchers.IO) {
        try {
            val client = createClient(serverUrl)

            // Call the login endpoint to initiate 2FA and get provider list
            // This endpoint returns X-Nextcloud-2FA-Required header and provider information
            val loginUrl = "$serverUrl/index.php/login/v2"

            val request = Request.Builder()
                .url(loginUrl)
                .post("".toRequestBody(null))
                .header("Authorization", Credentials.basic(username, password))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val requires2FA = response.headers["X-Nextcloud-2FA-Required"] != null

            response.close()

            if (requires2FA) {
                // Parse provider list from response
                val json = InputValidator.parseJsonSafely(responseBody)

                if (json != null && json.has("2fa")) {
                    val twoFactorInfo = json.getJSONObject("2fa")
                    val providers = mutableListOf<TwoFactorProvider>()

                    if (twoFactorInfo.has("providers")) {
                        val providersArray = twoFactorInfo.getJSONArray("providers")
                        for (i in 0 until providersArray.length()) {
                            val providerObj = providersArray.getJSONObject(i)
                            val id = providerObj.optString("id", "")
                            val displayName = providerObj.optString("displayName", id)

                            if (id.isNotEmpty()) {
                                val type = TwoFactorProviderType.fromProviderId(id)
                                providers.add(TwoFactorProvider(id, displayName, type))
                            }
                        }
                    }

                    SafeLogger.d("NextcloudAuthenticator", "Found ${providers.size} 2FA providers")
                    return@withContext providers.filter { it.isSupported }
                }
            }

            // If no 2FA required or can't parse, return empty list
            SafeLogger.d("NextcloudAuthenticator", "No 2FA providers found or 2FA not required")
            emptyList()
        } catch (e: Exception) {
            SafeLogger.e("NextcloudAuthenticator", "Failed to fetch 2FA providers", e)
            emptyList()
        }
    }

    /**
     * Initiates notification-based 2FA authentication.
     *
     * This triggers a push notification to the user's already-logged-in devices
     * and returns a token that can be used to poll for approval.
     *
     * @param serverUrl The Nextcloud server URL
     * @param username The username
     * @param password The password
     * @return NotificationAuthResult with token on success
     */
    suspend fun initiateNotificationAuth(
        serverUrl: String,
        username: String,
        password: String
    ): NotificationAuthResult = withContext(Dispatchers.IO) {
        try {
            val client = createClient(serverUrl)

            // Call the app password endpoint with notification provider
            // This triggers the notification and returns a polling token
            val appPasswordUrl = "$serverUrl/ocs/v2.php/core/apppassword"

            val request = Request.Builder()
                .url(appPasswordUrl)
                .post("".toRequestBody(null))
                .header("OCS-APIREQUEST", "true")
                .header("Authorization", Credentials.basic(username, password))
                .header("X-NC-2FA-PROVIDER", "twofactor_nextcloud_notification")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200, 202 -> {
                    response.close()

                    // Parse response for polling token
                    val json = InputValidator.parseJsonSafely(responseBody)
                        ?: return@withContext NotificationAuthResult.Error("Invalid JSON response from server")

                    if (!json.has("ocs")) {
                        return@withContext NotificationAuthResult.Error("Missing OCS wrapper in server response")
                    }

                    val ocs = json.getJSONObject("ocs")
                    if (!ocs.has("data")) {
                        return@withContext NotificationAuthResult.Error("Missing data in server response")
                    }

                    val data = ocs.getJSONObject("data")

                    // Extract polling token
                    val tokenValidation = InputValidator.validateJsonString(data, "token", required = true, maxLength = 512)
                    if (!tokenValidation.isValid()) {
                        return@withContext NotificationAuthResult.Error("Invalid token: ${tokenValidation.getErrorOrNull()}")
                    }

                    val token = data.getString("token")
                    SafeLogger.d("NextcloudAuthenticator", "Notification auth initiated, got polling token")
                    NotificationAuthResult.Success(token)
                }
                401, 403 -> {
                    response.close()
                    SafeLogger.w("NextcloudAuthenticator", "Notification auth initiation failed - authentication error")
                    NotificationAuthResult.Error("Authentication failed")
                }
                else -> {
                    response.close()
                    SafeLogger.w("NextcloudAuthenticator", "Notification auth initiation failed with HTTP ${response.code}")
                    NotificationAuthResult.Error("Initiation failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            SafeLogger.e("NextcloudAuthenticator", "Failed to initiate notification auth", e)
            NotificationAuthResult.Error("Initiation failed: ${e.message}")
        }
    }

    /**
     * Polls for notification approval status.
     *
     * @param serverUrl The Nextcloud server URL
     * @param username The username for authentication
     * @param password The password for authentication
     * @param token The polling token from initiateNotificationAuth
     * @return PollResult indicating current status
     */
    suspend fun pollNotificationAuth(
        serverUrl: String,
        username: String,
        password: String,
        token: String
    ): PollResult = withContext(Dispatchers.IO) {
        try {
            val client = createClient(serverUrl)

            // Poll the notification endpoint
            val pollUrl = "$serverUrl/ocs/v2.php/apps/twofactor_nextcloud_notification/api/v1/poll/$token"

            val request = Request.Builder()
                .url(pollUrl)
                .get()
                .header("OCS-APIREQUEST", "true")
                .header("Authorization", Credentials.basic(username, password))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            when (response.code) {
                200 -> {
                    response.close()

                    val json = InputValidator.parseJsonSafely(responseBody)
                        ?: return@withContext PollResult.Error("Invalid JSON response")

                    if (!json.has("ocs")) {
                        return@withContext PollResult.Error("Missing OCS wrapper")
                    }

                    val ocs = json.getJSONObject("ocs")
                    if (!ocs.has("data")) {
                        return@withContext PollResult.Error("Missing data in response")
                    }

                    val data = ocs.getJSONObject("data")
                    val state = data.optString("state", "")

                    when (state) {
                        "pending" -> {
                            SafeLogger.d("NextcloudAuthenticator", "Notification still pending")
                            PollResult.Pending
                        }
                        "approved" -> {
                            // Extract app password
                            val appPasswordValidation = InputValidator.validateJsonString(data, "appPassword", required = true, maxLength = 512)
                            if (!appPasswordValidation.isValid()) {
                                return@withContext PollResult.Error("Invalid app password: ${appPasswordValidation.getErrorOrNull()}")
                            }
                            val appPassword = data.getString("appPassword")

                            // SECURITY: Validate Nextcloud app password format
                            val formatCheck = InputValidator.validateNextcloudAppPassword(appPassword)
                            if (!formatCheck.isValid()) {
                                return@withContext PollResult.Error("Invalid app password format: ${formatCheck.getErrorOrNull()}")
                            }

                            SafeLogger.d("NextcloudAuthenticator", "Notification approved")
                            PollResult.Approved(appPassword)
                        }
                        "denied" -> {
                            SafeLogger.d("NextcloudAuthenticator", "Notification denied")
                            PollResult.Denied
                        }
                        else -> {
                            SafeLogger.w("NextcloudAuthenticator", "Unknown notification state: $state")
                            PollResult.Error("Unknown state: $state")
                        }
                    }
                }
                404 -> {
                    response.close()
                    SafeLogger.w("NextcloudAuthenticator", "Polling token not found or expired")
                    PollResult.Error("Token expired")
                }
                else -> {
                    response.close()
                    SafeLogger.w("NextcloudAuthenticator", "Polling failed with HTTP ${response.code}")
                    PollResult.Error("Polling failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            SafeLogger.e("NextcloudAuthenticator", "Failed to poll notification auth", e)
            PollResult.Error("Polling failed: ${e.message}")
        }
    }
}

sealed class TwoFactorResult {
    data class Success(val appPassword: String) : TwoFactorResult()
    object InvalidCode : TwoFactorResult()
    data class Error(val message: String) : TwoFactorResult()
}

/**
 * Result of initiating notification-based 2FA.
 */
sealed class NotificationAuthResult {
    /**
     * Notification sent successfully, contains token for polling.
     */
    data class Success(val token: String) : NotificationAuthResult()

    /**
     * Failed to initiate notification auth.
     */
    data class Error(val message: String) : NotificationAuthResult()
}

/**
 * Result of polling for notification approval.
 */
sealed class PollResult {
    /**
     * Notification is still pending approval.
     */
    object Pending : PollResult()

    /**
     * User approved the login, contains app password.
     */
    data class Approved(val appPassword: String) : PollResult()

    /**
     * User denied the login request.
     */
    object Denied : PollResult()

    /**
     * Polling timed out or encountered an error.
     */
    data class Error(val message: String) : PollResult()
}
