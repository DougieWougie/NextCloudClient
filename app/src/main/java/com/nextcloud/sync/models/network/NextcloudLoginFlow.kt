package com.nextcloud.sync.models.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Implements Nextcloud Login Flow v2
 * See: https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html
 */
class NextcloudLoginFlow(private val serverUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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

            val json = JSONObject(responseBody)
            val poll = json.getJSONObject("poll")
            val pollToken = poll.getString("token")
            val pollEndpoint = poll.getString("endpoint")
            val loginUrl = json.getString("login")

            LoginFlowInitResult.Success(
                loginUrl = loginUrl,
                pollToken = pollToken,
                pollEndpoint = pollEndpoint
            )
        } catch (e: Exception) {
            Log.e("NextcloudLoginFlow", "Failed to init login flow", e)
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
                Log.d("NextcloudLoginFlow", "Polling attempt ${attempt + 1}/$maxAttempts")

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val request = Request.Builder()
                    .url(pollEndpoint)
                    .post("""{"token":"$pollToken"}""".toRequestBody(mediaType))
                    .build()

                val response = client.newCall(request).execute()

                Log.d("NextcloudLoginFlow", "Poll response: ${response.code}")

                when (response.code) {
                    200 -> {
                        // Success! Got credentials
                        val responseBody = response.body?.string() ?: ""
                        response.close()

                        Log.d("NextcloudLoginFlow", "Login successful!")

                        val json = JSONObject(responseBody)
                        val serverUrl = json.getString("server")
                        val loginName = json.getString("loginName")
                        val appPassword = json.getString("appPassword")

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
                        Log.d("NextcloudLoginFlow", "Not ready, waiting...")
                        delay(5000) // Wait 5 seconds before next poll
                    }
                    else -> {
                        // For 500 errors, keep retrying (might be transient)
                        val responseBody = response.body?.string() ?: ""
                        response.close()

                        Log.w("NextcloudLoginFlow", "Poll returned ${response.code}: $responseBody")

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
            Log.e("NextcloudLoginFlow", "Polling failed", e)
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
