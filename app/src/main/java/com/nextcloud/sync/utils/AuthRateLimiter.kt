package com.nextcloud.sync.utils

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Rate limiter for authentication attempts to prevent brute force attacks.
 * Uses exponential backoff to progressively increase delays after failed attempts.
 */
class AuthRateLimiter(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "auth_rate_limiter",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_PREFIX_ATTEMPTS = "attempts_"
        private const val KEY_PREFIX_FIRST_ATTEMPT = "first_attempt_"
        private const val KEY_PREFIX_LAST_ATTEMPT = "last_attempt_"
        private const val KEY_PREFIX_LOCKED_UNTIL = "locked_until_"

        // Rate limiting configuration
        private const val MAX_ATTEMPTS_WINDOW = 5 // Max attempts per window
        private const val WINDOW_DURATION_MS = 15 * 60 * 1000L // 15 minutes in milliseconds
        private const val INITIAL_BACKOFF_MS = 5 * 1000L // 5 seconds in milliseconds
        private const val MAX_BACKOFF_MS = 60 * 60 * 1000L // 1 hour in milliseconds
        private const val BACKOFF_MULTIPLIER = 2.0 // Exponential backoff

        @Volatile
        private var instance: AuthRateLimiter? = null

        fun getInstance(context: Context): AuthRateLimiter {
            return instance ?: synchronized(this) {
                instance ?: AuthRateLimiter(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Check if authentication is allowed for the given identifier
     * @param identifier Unique identifier (e.g., "login:username" or "2fa:accountId")
     * @return RateLimitResult indicating if auth is allowed and how long to wait if not
     */
    fun checkAttempt(identifier: String): RateLimitResult {
        val sanitizedId = sanitizeIdentifier(identifier)
        val now = System.currentTimeMillis()

        // Check if currently locked out
        val lockedUntil = prefs.getLong(KEY_PREFIX_LOCKED_UNTIL + sanitizedId, 0)
        if (lockedUntil > now) {
            val waitTimeMs = lockedUntil - now
            SafeLogger.w("AuthRateLimiter", "Authentication blocked for $sanitizedId, wait time: ${waitTimeMs}ms")
            return RateLimitResult.Blocked(waitTimeMs)
        }

        // Get attempt history
        val attempts = prefs.getInt(KEY_PREFIX_ATTEMPTS + sanitizedId, 0)
        val firstAttempt = prefs.getLong(KEY_PREFIX_FIRST_ATTEMPT + sanitizedId, now)
        val lastAttempt = prefs.getLong(KEY_PREFIX_LAST_ATTEMPT + sanitizedId, 0)

        // Reset if window has expired
        if (now - firstAttempt > WINDOW_DURATION_MS) {
            SafeLogger.d("AuthRateLimiter", "Rate limit window expired for $sanitizedId, resetting")
            resetAttempts(sanitizedId)
            return RateLimitResult.Allowed
        }

        // Check if too many attempts in window
        if (attempts >= MAX_ATTEMPTS_WINDOW) {
            // Calculate backoff time (exponential)
            val backoffTime = calculateBackoff(attempts)
            val timeSinceLastAttempt = now - lastAttempt

            if (timeSinceLastAttempt < backoffTime) {
                // Still in backoff period
                val waitTimeMs = backoffTime - timeSinceLastAttempt
                SafeLogger.w("AuthRateLimiter", "Too many attempts for $sanitizedId (attempt $attempts), backoff: ${waitTimeMs}ms")

                // Lock until backoff expires
                prefs.edit()
                    .putLong(KEY_PREFIX_LOCKED_UNTIL + sanitizedId, now + waitTimeMs)
                    .apply()

                return RateLimitResult.Blocked(waitTimeMs)
            } else {
                // Backoff expired, allow but keep count
                SafeLogger.d("AuthRateLimiter", "Backoff expired for $sanitizedId, allowing attempt")
                return RateLimitResult.Allowed
            }
        }

        SafeLogger.d("AuthRateLimiter", "Allowing attempt for $sanitizedId (attempt ${attempts + 1}/$MAX_ATTEMPTS_WINDOW)")
        return RateLimitResult.Allowed
    }

    /**
     * Record a failed authentication attempt
     */
    fun recordFailedAttempt(identifier: String) {
        val sanitizedId = sanitizeIdentifier(identifier)
        val now = System.currentTimeMillis()

        val attempts = prefs.getInt(KEY_PREFIX_ATTEMPTS + sanitizedId, 0)
        val firstAttempt = prefs.getLong(KEY_PREFIX_FIRST_ATTEMPT + sanitizedId, now)

        // Reset if window expired
        if (now - firstAttempt > WINDOW_DURATION_MS) {
            SafeLogger.d("AuthRateLimiter", "Recording first failed attempt for $sanitizedId")
            prefs.edit()
                .putInt(KEY_PREFIX_ATTEMPTS + sanitizedId, 1)
                .putLong(KEY_PREFIX_FIRST_ATTEMPT + sanitizedId, now)
                .putLong(KEY_PREFIX_LAST_ATTEMPT + sanitizedId, now)
                .apply()
        } else {
            val newAttempts = attempts + 1
            SafeLogger.d("AuthRateLimiter", "Recording failed attempt $newAttempts for $sanitizedId")
            prefs.edit()
                .putInt(KEY_PREFIX_ATTEMPTS + sanitizedId, newAttempts)
                .putLong(KEY_PREFIX_LAST_ATTEMPT + sanitizedId, now)
                .apply()

            // If reached max attempts, calculate and set lockout
            if (newAttempts >= MAX_ATTEMPTS_WINDOW) {
                val backoffTime = calculateBackoff(newAttempts)
                SafeLogger.w("AuthRateLimiter", "Max attempts reached for $sanitizedId, locking for ${backoffTime}ms")
                prefs.edit()
                    .putLong(KEY_PREFIX_LOCKED_UNTIL + sanitizedId, now + backoffTime)
                    .apply()
            }
        }
    }

    /**
     * Record a successful authentication (resets the counter)
     */
    fun recordSuccessfulAttempt(identifier: String) {
        val sanitizedId = sanitizeIdentifier(identifier)
        SafeLogger.d("AuthRateLimiter", "Recording successful attempt for $sanitizedId, resetting counter")
        resetAttempts(sanitizedId)
    }

    /**
     * Reset all attempts for an identifier
     */
    fun resetAttempts(identifier: String) {
        val sanitizedId = sanitizeIdentifier(identifier)
        prefs.edit()
            .remove(KEY_PREFIX_ATTEMPTS + sanitizedId)
            .remove(KEY_PREFIX_FIRST_ATTEMPT + sanitizedId)
            .remove(KEY_PREFIX_LAST_ATTEMPT + sanitizedId)
            .remove(KEY_PREFIX_LOCKED_UNTIL + sanitizedId)
            .apply()
    }

    /**
     * Get remaining attempts before lockout
     */
    fun getRemainingAttempts(identifier: String): Int {
        val sanitizedId = sanitizeIdentifier(identifier)
        val attempts = prefs.getInt(KEY_PREFIX_ATTEMPTS + sanitizedId, 0)
        return maxOf(0, MAX_ATTEMPTS_WINDOW - attempts)
    }

    /**
     * Calculate exponential backoff time
     */
    private fun calculateBackoff(attempts: Int): Long {
        val backoff = (INITIAL_BACKOFF_MS * Math.pow(BACKOFF_MULTIPLIER, (attempts - MAX_ATTEMPTS_WINDOW).toDouble())).toLong()
        return minOf(backoff, MAX_BACKOFF_MS)
    }

    /**
     * Sanitize identifier to prevent injection and collision attacks.
     *
     * If the identifier contains invalid characters, it is hashed using SHA-256
     * to prevent collision attacks where different identifiers could sanitize
     * to the same value (e.g., "admin@@@" and "admin" both become "admin").
     *
     * SECURITY: This prevents attackers from:
     * - Locking out legitimate users by using crafted identifiers
     * - Bypassing rate limits through identifier collision
     *
     * @param identifier The raw identifier to sanitize
     * @return Sanitized identifier (either cleaned or hashed)
     */
    private fun sanitizeIdentifier(identifier: String): String {
        // Check if identifier contains only allowed characters
        val allowedPattern = Regex("^[a-zA-Z0-9_:-]{1,100}$")

        if (allowedPattern.matches(identifier)) {
            // Identifier is clean, use as-is
            return identifier
        }

        // Identifier contains invalid characters or is too long
        // Use SHA-256 hash to prevent collision attacks
        val hash = try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(identifier.toByteArray(Charsets.UTF_8))
            // Convert to hex string (first 32 characters for storage efficiency)
            hashBytes.joinToString("") { "%02x".format(it) }.take(32)
        } catch (e: Exception) {
            SafeLogger.e("AuthRateLimiter", "Failed to hash identifier", e)
            // Fallback: sanitize but prefix with hash indicator
            identifier.replace(Regex("[^a-zA-Z0-9_:-]"), "_").take(90)
        }

        return "hash_$hash"
    }

    /**
     * Clear all rate limit data (for testing or admin reset)
     */
    fun clearAll() {
        SafeLogger.w("AuthRateLimiter", "Clearing all rate limit data")
        prefs.edit().clear().apply()
    }

    /**
     * Get current attempt count for debugging
     */
    fun getAttemptCount(identifier: String): Int {
        val sanitizedId = sanitizeIdentifier(identifier)
        return prefs.getInt(KEY_PREFIX_ATTEMPTS + sanitizedId, 0)
    }
}

/**
 * Result of rate limit check
 */
sealed class RateLimitResult {
    /**
     * Authentication attempt is allowed
     */
    object Allowed : RateLimitResult()

    /**
     * Authentication is blocked due to too many failed attempts
     * @param waitTimeMs How long to wait before trying again (in milliseconds)
     */
    data class Blocked(val waitTimeMs: Long) : RateLimitResult() {
        /**
         * Get wait time in seconds for display
         */
        fun getWaitTimeSeconds(): Long = TimeUnit.MILLISECONDS.toSeconds(waitTimeMs)

        /**
         * Get human-readable wait time
         */
        fun getWaitTimeFormatted(): String {
            val seconds = getWaitTimeSeconds()
            return when {
                seconds < 60 -> "$seconds seconds"
                seconds < 3600 -> "${seconds / 60} minutes"
                else -> "${seconds / 3600} hours"
            }
        }
    }
}
