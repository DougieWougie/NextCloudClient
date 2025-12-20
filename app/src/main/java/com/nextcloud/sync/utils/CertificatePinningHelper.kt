package com.nextcloud.sync.utils

import android.content.Context
import android.content.SharedPreferences
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Helper class for implementing certificate pinning to prevent MITM attacks.
 *
 * Certificate pinning ensures that the app only trusts specific SSL/TLS certificates
 * for known domains, providing an additional layer of security beyond standard CA validation.
 */
object CertificatePinningHelper {
    private const val PREFS_NAME = "certificate_pins"
    private const val KEY_PREFIX_PIN = "pin_"

    // Common Nextcloud hosting providers with their certificate pins
    // Format: "sha256/<base64-encoded-sha256-hash>"
    // Users can add their own server pins via settings
    private val DEFAULT_PINS: Map<String, List<String>> = mapOf(
        // Example: Nextcloud official demo server
        // "demo.nextcloud.com" to listOf("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    )

    /**
     * Get stored certificate pins for a domain
     */
    fun getPinsForDomain(context: Context, domain: String): List<String> {
        val prefs = getPrefs(context)
        val pinsJson = prefs.getString(KEY_PREFIX_PIN + domain, null)

        if (pinsJson != null) {
            try {
                val pins = mutableListOf<String>()
                val jsonArray = org.json.JSONArray(pinsJson)
                for (i in 0 until jsonArray.length()) {
                    pins.add(jsonArray.getString(i))
                }
                return pins
            } catch (e: Exception) {
                SafeLogger.e("CertificatePinningHelper", "Failed to parse pins for $domain", e)
            }
        }

        // Return default pins if available
        return DEFAULT_PINS[domain] ?: emptyList()
    }

    /**
     * Store certificate pins for a domain
     */
    fun storePinsForDomain(context: Context, domain: String, pins: List<String>) {
        if (pins.isEmpty()) {
            removePinsForDomain(context, domain)
            return
        }

        val prefs = getPrefs(context)
        val jsonArray = org.json.JSONArray()
        pins.forEach { jsonArray.put(it) }

        prefs.edit()
            .putString(KEY_PREFIX_PIN + domain, jsonArray.toString())
            .apply()

        SafeLogger.d("CertificatePinningHelper", "Stored ${pins.size} pins for $domain")
    }

    /**
     * Remove certificate pins for a domain
     */
    fun removePinsForDomain(context: Context, domain: String) {
        val prefs = getPrefs(context)
        prefs.edit()
            .remove(KEY_PREFIX_PIN + domain)
            .apply()

        SafeLogger.d("CertificatePinningHelper", "Removed pins for $domain")
    }

    /**
     * Get all domains with stored pins
     */
    fun getAllPinnedDomains(context: Context): Set<String> {
        val prefs = getPrefs(context)
        val domains = mutableSetOf<String>()

        prefs.all.keys.forEach { key ->
            if (key.startsWith(KEY_PREFIX_PIN)) {
                domains.add(key.removePrefix(KEY_PREFIX_PIN))
            }
        }

        return domains
    }

    /**
     * Extract domain from server URL
     */
    fun extractDomain(serverUrl: String): String? {
        return try {
            val url = URL(serverUrl)
            url.host
        } catch (e: Exception) {
            SafeLogger.w("CertificatePinningHelper", "Failed to extract domain from $serverUrl", e)
            null
        }
    }

    /**
     * Create an OkHttpClient with certificate pinning enabled
     */
    fun createPinnedClient(
        context: Context,
        serverUrl: String,
        connectTimeout: Long = 30,
        readTimeout: Long = 30,
        writeTimeout: Long = 30
    ): OkHttpClient {
        val domain = extractDomain(serverUrl)
        val pins = if (domain != null) getPinsForDomain(context, domain) else emptyList()

        val builder = OkHttpClient.Builder()
            .connectTimeout(connectTimeout, TimeUnit.SECONDS)
            .readTimeout(readTimeout, TimeUnit.SECONDS)
            .writeTimeout(writeTimeout, TimeUnit.SECONDS)

        // Only add certificate pinner if pins are configured
        if (pins.isNotEmpty() && domain != null) {
            val pinnerBuilder = CertificatePinner.Builder()

            // Add all pins for this domain
            pins.forEach { pin ->
                pinnerBuilder.add(domain, pin)
            }

            builder.certificatePinner(pinnerBuilder.build())
            SafeLogger.d("CertificatePinningHelper", "Certificate pinning enabled for $domain with ${pins.size} pins")
        } else {
            SafeLogger.d("CertificatePinningHelper", "No certificate pins configured for $serverUrl")
        }

        return builder.build()
    }

    /**
     * Validate certificate pin format
     * Expected format: "sha256/base64-encoded-hash"
     */
    fun isValidPinFormat(pin: String): Boolean {
        // Pin should be sha256/[base64] or sha1/[base64] (though sha256 is recommended)
        val pattern = Regex("^(sha256|sha1)/[A-Za-z0-9+/]+=*$")
        return pattern.matches(pin)
    }

    /**
     * Extract certificate pins from a live connection to a server
     * This can be used to help users configure pinning for their server
     *
     * Note: This should be called on a trusted network during initial setup
     */
    suspend fun extractPinsFromServer(serverUrl: String): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val pins = mutableListOf<String>()

        try {
            val domain = extractDomain(serverUrl) ?: return@withContext emptyList()

            // Create a temporary client without pinning to fetch certificates
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = okhttp3.Request.Builder()
                .url(serverUrl)
                .build()

            val response = client.newCall(request).execute()
            response.close()

            // Get the certificate pins from the handshake
            val handshake = response.handshake
            if (handshake != null) {
                handshake.peerCertificates.forEach { cert ->
                    try {
                        val pin = CertificatePinner.pin(cert)
                        pins.add(pin)
                        SafeLogger.d("CertificatePinningHelper", "Extracted pin: $pin")
                    } catch (e: Exception) {
                        SafeLogger.e("CertificatePinningHelper", "Failed to extract pin from certificate", e)
                    }
                }
            }
        } catch (e: Exception) {
            SafeLogger.e("CertificatePinningHelper", "Failed to extract pins from server", e)
        }

        return@withContext pins
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
