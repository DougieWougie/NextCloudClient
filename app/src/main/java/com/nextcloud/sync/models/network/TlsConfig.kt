package com.nextcloud.sync.models.network

import com.nextcloud.sync.BuildConfig
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * TLS configuration for secure network connections.
 *
 * SECURITY:
 * - Enforces TLS 1.2 and TLS 1.3 minimum (blocks TLS 1.0/1.1 which have known vulnerabilities)
 * - Uses only secure cipher suites with forward secrecy
 * - Prevents downgrade attacks by explicitly specifying allowed versions
 * - Disables insecure cipher suites (RC4, MD5, DES, 3DES, etc.)
 */
object TlsConfig {
    /**
     * Creates a secure OkHttpClient with enforced TLS 1.2+ and secure cipher suites.
     *
     * @return Configured OkHttpClient instance
     */
    fun createSecureOkHttpClient(): OkHttpClient {
        // SECURITY: Create custom ConnectionSpec that enforces TLS 1.2+
        // and uses only secure cipher suites with forward secrecy
        val secureSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .cipherSuites(
                // TLS 1.3 cipher suites (most secure)
                CipherSuite.TLS_AES_128_GCM_SHA256,
                CipherSuite.TLS_AES_256_GCM_SHA384,
                CipherSuite.TLS_CHACHA20_POLY1305_SHA256,

                // TLS 1.2 cipher suites with forward secrecy (ECDHE)
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
                CipherSuite.TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256,
                CipherSuite.TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
            )
            .build()

        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            // SECURITY: Only use our secure ConnectionSpec (no fallback to weak TLS)
            .connectionSpecs(listOf(secureSpec))
            .build()
    }
}
