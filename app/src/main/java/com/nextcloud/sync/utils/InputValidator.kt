package com.nextcloud.sync.utils

import android.util.Patterns
import org.json.JSONException
import org.json.JSONObject

/**
 * Utility for validating server input to prevent injection attacks,
 * malformed data, and excessive resource usage.
 */
object InputValidator {
    // Maximum lengths to prevent resource exhaustion
    private const val MAX_URL_LENGTH = 2048
    private const val MAX_USERNAME_LENGTH = 256
    private const val MAX_PASSWORD_LENGTH = 256
    private const val MAX_TOKEN_LENGTH = 1024
    private const val MAX_FILENAME_LENGTH = 255
    private const val MAX_PATH_LENGTH = 4096
    private const val MAX_JSON_SIZE = 1_048_576 // 1MB

    // Allowed URL schemes
    private val ALLOWED_URL_SCHEMES = setOf("http", "https")

    /**
     * Validate server URL format and scheme
     */
    fun validateServerUrl(url: String?): ValidationResult {
        if (url.isNullOrBlank()) {
            return ValidationResult.Invalid("URL is required")
        }

        if (url.length > MAX_URL_LENGTH) {
            return ValidationResult.Invalid("URL is too long (max $MAX_URL_LENGTH characters)")
        }

        // Check for control characters and null bytes
        if (url.contains(Regex("[\u0000-\u001F\u007F]"))) {
            return ValidationResult.Invalid("URL contains invalid characters")
        }

        // Validate URL format
        if (!Patterns.WEB_URL.matcher(url).matches()) {
            return ValidationResult.Invalid("Invalid URL format")
        }

        // Extract and validate scheme
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme !in ALLOWED_URL_SCHEMES) {
            return ValidationResult.Invalid("Only HTTP and HTTPS URLs are allowed")
        }

        // Check for suspicious patterns
        if (url.contains("@") && !url.contains("://")) {
            // Potential SSRF via user info in URL
            return ValidationResult.Invalid("Invalid URL format")
        }

        // SECURITY: Prevent SSRF by blocking private network addresses
        try {
            val urlObj = java.net.URL(url)
            val host = urlObj.host.lowercase()

            // Block localhost
            if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host.startsWith("127.")) {
                return ValidationResult.Invalid("Localhost addresses are not allowed for security reasons")
            }

            // Block private IP ranges
            val ipAddress = try {
                java.net.InetAddress.getByName(host)
            } catch (e: Exception) {
                null  // If hostname resolution fails, continue validation
            }

            if (ipAddress != null) {
                // Check if it's a private address
                if (ipAddress.isLoopbackAddress || ipAddress.isLinkLocalAddress ||
                    ipAddress.isSiteLocalAddress || ipAddress.isAnyLocalAddress) {
                    return ValidationResult.Invalid("Private network addresses are not allowed")
                }

                // Additional check for AWS metadata endpoint
                if (host == "169.254.169.254") {
                    return ValidationResult.Invalid("Cloud metadata endpoints are not allowed")
                }
            }
        } catch (e: Exception) {
            SafeLogger.w("InputValidator", "Failed to parse URL for SSRF check", e)
            // Continue with validation - don't fail if we can't parse
        }

        return ValidationResult.Valid
    }

    /**
     * Validate username
     */
    fun validateUsername(username: String?): ValidationResult {
        if (username.isNullOrBlank()) {
            return ValidationResult.Invalid("Username is required")
        }

        if (username.length > MAX_USERNAME_LENGTH) {
            return ValidationResult.Invalid("Username is too long")
        }

        // Check for control characters and null bytes
        if (username.contains(Regex("[\u0000-\u001F\u007F]"))) {
            return ValidationResult.Invalid("Username contains invalid characters")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate password
     */
    fun validatePassword(password: String?): ValidationResult {
        if (password.isNullOrBlank()) {
            return ValidationResult.Invalid("Password is required")
        }

        if (password.length > MAX_PASSWORD_LENGTH) {
            return ValidationResult.Invalid("Password is too long")
        }

        // Check for null bytes (but allow other characters for password flexibility)
        if (password.contains('\u0000')) {
            return ValidationResult.Invalid("Password contains invalid characters")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate Nextcloud app password format.
     * Format: xxxxx-xxxxx-xxxxx-xxxxx-xxxxx (5 groups of 5 alphanumeric chars)
     *
     * SECURITY: Strict validation prevents injection attacks and malformed tokens
     */
    fun validateNextcloudAppPassword(appPassword: String?): ValidationResult {
        if (appPassword.isNullOrBlank()) {
            return ValidationResult.Invalid("App password is required")
        }

        // Nextcloud app passwords are exactly 29 characters: 5*5 + 4 hyphens
        if (appPassword.length != 29) {
            return ValidationResult.Invalid("App password has invalid length")
        }

        // Validate format: xxxxx-xxxxx-xxxxx-xxxxx-xxxxx
        val nextcloudAppPasswordPattern = Regex("^[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}-[A-Za-z0-9]{5}$")
        if (!nextcloudAppPasswordPattern.matches(appPassword)) {
            return ValidationResult.Invalid("App password format is invalid")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate generic authentication token
     *
     * SECURITY: More restrictive validation - only alphanumeric and hyphens
     */
    fun validateToken(token: String?): ValidationResult {
        if (token.isNullOrBlank()) {
            return ValidationResult.Invalid("Token is required")
        }

        if (token.length > MAX_TOKEN_LENGTH) {
            return ValidationResult.Invalid("Token is too long")
        }

        // More restrictive: only alphanumeric and hyphens (no dots or underscores)
        if (!token.matches(Regex("^[a-zA-Z0-9-]+$"))) {
            return ValidationResult.Invalid("Token contains invalid characters")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate file name from server
     */
    fun validateFileName(fileName: String?): ValidationResult {
        if (fileName.isNullOrBlank()) {
            return ValidationResult.Invalid("File name is required")
        }

        if (fileName.length > MAX_FILENAME_LENGTH) {
            return ValidationResult.Invalid("File name is too long")
        }

        // Check for path separators
        if (fileName.contains('/') || fileName.contains('\\') || fileName.contains(':')) {
            return ValidationResult.Invalid("File name contains path separators")
        }

        // Check for directory traversal
        if (fileName == ".." || fileName == ".") {
            return ValidationResult.Invalid("Invalid file name")
        }

        // Check for control characters and null bytes
        if (fileName.contains(Regex("[\u0000-\u001F\u007F]"))) {
            return ValidationResult.Invalid("File name contains invalid characters")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate file path from server
     */
    fun validateFilePath(path: String?): ValidationResult {
        if (path.isNullOrBlank()) {
            return ValidationResult.Invalid("Path is required")
        }

        if (path.length > MAX_PATH_LENGTH) {
            return ValidationResult.Invalid("Path is too long")
        }

        // Check for null bytes
        if (path.contains('\u0000')) {
            return ValidationResult.Invalid("Path contains null bytes")
        }

        // Use PathValidator for additional checks
        if (!PathValidator.isSafePath(path)) {
            return ValidationResult.Invalid("Path contains invalid patterns")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate file size is within reasonable bounds
     */
    fun validateFileSize(size: Long): ValidationResult {
        if (size < 0) {
            return ValidationResult.Invalid("File size cannot be negative")
        }

        // 10GB max file size
        if (size > 10_737_418_240L) {
            return ValidationResult.Invalid("File size exceeds maximum (10GB)")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate ETag format
     */
    fun validateETag(etag: String?): ValidationResult {
        if (etag.isNullOrBlank()) {
            return ValidationResult.Valid // ETags can be optional
        }

        if (etag.length > 256) {
            return ValidationResult.Invalid("ETag is too long")
        }

        // ETags are typically quoted strings or hex
        if (!etag.matches(Regex("^[\"a-fA-F0-9-]+$"))) {
            return ValidationResult.Invalid("ETag contains invalid characters")
        }

        return ValidationResult.Valid
    }

    /**
     * Validate timestamp
     */
    fun validateTimestamp(timestamp: Long): ValidationResult {
        // Timestamps should be reasonable (between year 2000 and 2100)
        val year2000 = 946684800000L // 2000-01-01 in millis
        val year2100 = 4102444800000L // 2100-01-01 in millis

        if (timestamp < year2000 || timestamp > year2100) {
            return ValidationResult.Invalid("Timestamp is out of reasonable range")
        }

        return ValidationResult.Valid
    }

    /**
     * Safely parse JSON with size limit
     */
    fun parseJsonSafely(jsonString: String?): JSONObject? {
        if (jsonString.isNullOrBlank()) {
            SafeLogger.w("InputValidator", "JSON string is null or blank")
            return null
        }

        if (jsonString.length > MAX_JSON_SIZE) {
            SafeLogger.w("InputValidator", "JSON exceeds maximum size: ${jsonString.length} > $MAX_JSON_SIZE")
            return null
        }

        return try {
            JSONObject(jsonString)
        } catch (e: JSONException) {
            SafeLogger.w("InputValidator", "Failed to parse JSON", e)
            null
        }
    }

    /**
     * Validate JSON string from server
     */
    fun validateJsonString(
        json: JSONObject,
        key: String,
        required: Boolean = true,
        maxLength: Int = 1024
    ): ValidationResult {
        if (!json.has(key)) {
            return if (required) {
                ValidationResult.Invalid("Missing required field: $key")
            } else {
                ValidationResult.Valid
            }
        }

        return try {
            val value = json.getString(key)

            if (value.length > maxLength) {
                ValidationResult.Invalid("Field '$key' exceeds maximum length")
            } else if (value.contains('\u0000')) {
                ValidationResult.Invalid("Field '$key' contains null bytes")
            } else {
                ValidationResult.Valid
            }
        } catch (e: JSONException) {
            ValidationResult.Invalid("Field '$key' has invalid type")
        }
    }

    /**
     * Sanitize string from server by removing control characters
     */
    fun sanitizeServerString(input: String?, maxLength: Int = 1024): String? {
        if (input == null) return null

        // Remove control characters and null bytes
        var sanitized = input.replace(Regex("[\u0000-\u001F\u007F]"), "")

        // Limit length
        if (sanitized.length > maxLength) {
            sanitized = sanitized.take(maxLength)
        }

        return sanitized
    }

    /**
     * Validate content type header
     */
    fun validateContentType(contentType: String?): ValidationResult {
        if (contentType.isNullOrBlank()) {
            return ValidationResult.Valid // Content-Type can be optional
        }

        if (contentType.length > 256) {
            return ValidationResult.Invalid("Content-Type is too long")
        }

        // Content-Type should follow pattern: type/subtype[; params]
        if (!contentType.matches(Regex("^[a-zA-Z0-9]+/[a-zA-Z0-9.+-]+(;.*)?$"))) {
            return ValidationResult.Invalid("Invalid Content-Type format")
        }

        return ValidationResult.Valid
    }
}

/**
 * Result of input validation
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()

    fun isValid(): Boolean = this is Valid

    fun getErrorOrNull(): String? = when (this) {
        is Invalid -> reason
        is Valid -> null
    }
}
