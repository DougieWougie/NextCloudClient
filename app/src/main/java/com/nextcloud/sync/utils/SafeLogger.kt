package com.nextcloud.sync.utils

import android.util.Log
import com.nextcloud.sync.BuildConfig

/**
 * Safe logging utility that sanitizes sensitive data and is automatically stripped
 * from release builds by ProGuard/R8.
 *
 * This class helps prevent leakage of:
 * - Passwords and authentication tokens
 * - User file paths
 * - Server URLs
 * - Usernames
 * - Personal identifiable information (PII)
 *
 * Usage: Replace all Log.* calls with SafeLogger.* calls
 */
object SafeLogger {
    private const val MAX_LOG_LENGTH = 4000 // Android log buffer limit

    /**
     * Verbose log - automatically stripped in release builds
     */
    @JvmStatic
    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            log(Log.VERBOSE, tag, sanitize(msg), null)
        }
    }

    /**
     * Debug log - automatically stripped in release builds
     */
    @JvmStatic
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            log(Log.DEBUG, tag, sanitize(msg), null)
        }
    }

    /**
     * Info log - automatically stripped in release builds
     */
    @JvmStatic
    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            log(Log.INFO, tag, sanitize(msg), null)
        }
    }

    /**
     * Warning log - sanitized in both debug and release
     */
    @JvmStatic
    fun w(tag: String, msg: String) {
        log(Log.WARN, tag, sanitize(msg), null)
    }

    /**
     * Warning log with throwable - sanitized in both debug and release
     */
    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable?) {
        log(Log.WARN, tag, sanitize(msg), tr)
    }

    /**
     * Error log - sanitized in both debug and release
     */
    @JvmStatic
    fun e(tag: String, msg: String) {
        log(Log.ERROR, tag, sanitize(msg), null)
    }

    /**
     * Error log with throwable - sanitized in both debug and release
     */
    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?) {
        log(Log.ERROR, tag, sanitize(msg), tr)
    }

    /**
     * Sanitize log message to remove sensitive information
     */
    private fun sanitize(message: String): String {
        var sanitized = message

        // Sanitize file paths - replace user-specific parts
        sanitized = sanitizePaths(sanitized)

        // Sanitize URLs - keep only the domain
        sanitized = sanitizeUrls(sanitized)

        // Sanitize potential tokens/passwords (any long alphanumeric strings)
        sanitized = sanitizeTokens(sanitized)

        return sanitized
    }

    /**
     * Sanitize file paths to hide user-specific directories
     */
    private fun sanitizePaths(message: String): String {
        var sanitized = message

        // Pattern: /storage/emulated/0/... or /data/user/0/...
        sanitized = sanitized.replace(
            Regex("/storage/emulated/\\d+/[^\\s]+"),
            "/storage/emulated/*/[REDACTED]"
        )
        sanitized = sanitized.replace(
            Regex("/data/(user|data)/\\d+/[^\\s]+"),
            "/data/*/[REDACTED]"
        )

        // Pattern: content:// URIs
        sanitized = sanitized.replace(
            Regex("content://[^\\s]+"),
            "content://[REDACTED]"
        )

        // Pattern: file:// URIs
        sanitized = sanitized.replace(
            Regex("file://[^\\s]+"),
            "file://[REDACTED]"
        )

        return sanitized
    }

    /**
     * Sanitize URLs to show only the domain
     */
    private fun sanitizeUrls(message: String): String {
        var sanitized = message

        // Pattern: https://example.com/path/to/resource
        sanitized = sanitized.replace(
            Regex("https?://([^/\\s]+)/[^\\s]*"),
            "https://$1/[REDACTED]"
        )

        return sanitized
    }

    /**
     * Sanitize potential authentication tokens and passwords.
     *
     * SECURITY IMPROVEMENTS:
     * - Catches shorter tokens (20+ chars instead of 32+)
     * - Detects base64-encoded strings
     * - Recognizes Nextcloud app passwords
     * - Catches JWT tokens
     * - Handles various encoding formats
     */
    private fun sanitizeTokens(message: String): String {
        var sanitized = message

        // Look for patterns like "password=xxx" or "token=xxx"
        sanitized = sanitized.replace(
            Regex("(password|passwd|pwd|token|auth|key|secret|bearer|authorization)[=:\\s]+[^\\s,}&]+", RegexOption.IGNORE_CASE),
            "$1=[REDACTED]"
        )

        // Sanitize Authorization headers (e.g., "Authorization: Bearer xxx")
        sanitized = sanitized.replace(
            Regex("Authorization:\\s*Bearer\\s+[^\\s]+", RegexOption.IGNORE_CASE),
            "Authorization: Bearer [REDACTED]"
        )
        sanitized = sanitized.replace(
            Regex("Authorization:\\s*Basic\\s+[^\\s]+", RegexOption.IGNORE_CASE),
            "Authorization: Basic [REDACTED]"
        )

        // Sanitize JWT tokens (format: xxx.yyy.zzz)
        sanitized = sanitized.replace(
            Regex("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}"),
            "[JWT-REDACTED]"
        )

        // Sanitize base64-encoded strings (24+ chars with base64 characters)
        // Common in tokens, app passwords, and encrypted data
        sanitized = sanitized.replace(
            Regex("\\b[A-Za-z0-9+/]{24,}={0,2}\\b"),
            "[BASE64-REDACTED]"
        )

        // Sanitize Nextcloud app passwords (format: xxxxx-xxxxx-xxxxx-xxxxx-xxxxx)
        sanitized = sanitized.replace(
            Regex("[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}"),
            "[APP-PASSWORD-REDACTED]"
        )

        // Sanitize UUID-like strings (might be session tokens)
        sanitized = sanitized.replace(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE),
            "[UUID-REDACTED]"
        )

        // Sanitize hexadecimal strings that might be tokens or hashes (40+ chars)
        // Common for SHA-1, SHA-256, API keys
        sanitized = sanitized.replace(
            Regex("\\b[0-9a-fA-F]{40,}\\b"),
            "[HEX-TOKEN-REDACTED]"
        )

        // Sanitize medium-length alphanumeric strings (20-31 chars)
        // Catches shorter tokens that the original pattern missed
        sanitized = sanitized.replace(
            Regex("\\b[a-zA-Z0-9]{20,31}\\b(?![a-zA-Z0-9])"),
            "[TOKEN-REDACTED]"
        )

        // Sanitize long alphanumeric strings (32+ chars)
        // But preserve exception class names and stack traces
        sanitized = sanitized.replace(
            Regex("\\b([a-zA-Z0-9]{32,})\\b(?![a-zA-Z])"),
            "[TOKEN-REDACTED]"
        )

        return sanitized
    }

    /**
     * Internal logging method that handles long messages
     */
    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        // Split long messages to avoid truncation
        if (message.length <= MAX_LOG_LENGTH) {
            logChunk(priority, tag, message, throwable)
        } else {
            // Split into chunks
            var i = 0
            while (i < message.length) {
                val end = minOf(i + MAX_LOG_LENGTH, message.length)
                val chunk = message.substring(i, end)
                logChunk(priority, tag, chunk, if (i == 0) throwable else null)
                i = end
            }
        }
    }

    /**
     * Log a single chunk
     */
    private fun logChunk(priority: Int, tag: String, message: String, throwable: Throwable?) {
        when (priority) {
            Log.VERBOSE -> if (throwable != null) Log.v(tag, message, throwable) else Log.v(tag, message)
            Log.DEBUG -> if (throwable != null) Log.d(tag, message, throwable) else Log.d(tag, message)
            Log.INFO -> if (throwable != null) Log.i(tag, message, throwable) else Log.i(tag, message)
            Log.WARN -> if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
            Log.ERROR -> if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        }
    }

    /**
     * Sanitize a path for display (returns just the filename)
     */
    @JvmStatic
    fun sanitizePathForDisplay(path: String?): String {
        if (path.isNullOrBlank()) return "[empty]"

        // For content URIs, return a safe representation
        if (path.startsWith("content://")) {
            return "content://[DOCUMENT]/${path.substringAfterLast('/')}"
        }

        // For file paths, return just the filename
        if (path.startsWith("/") || path.contains("\\")) {
            return path.substringAfterLast('/').substringAfterLast('\\')
        }

        // For relative paths, return as-is (should be safe)
        return path
    }

    /**
     * Sanitize a URL for display (returns just the domain)
     */
    @JvmStatic
    fun sanitizeUrlForDisplay(url: String?): String {
        if (url.isNullOrBlank()) return "[empty]"

        return try {
            val domain = url.replace(Regex("https?://([^/]+).*"), "$1")
            "https://$domain"
        } catch (e: Exception) {
            "[INVALID-URL]"
        }
    }

    /**
     * Sanitize username for display (shows first and last char only)
     */
    @JvmStatic
    fun sanitizeUsernameForDisplay(username: String?): String {
        if (username.isNullOrBlank()) return "[empty]"
        if (username.length <= 2) return "**"

        return "${username.first()}${"*".repeat(username.length - 2)}${username.last()}"
    }
}
