package com.nextcloud.sync.utils

import com.nextcloud.sync.utils.SafeLogger
import java.io.File

/**
 * Utility class for sanitizing and validating file paths to prevent directory traversal attacks
 * and ensure paths stay within designated sync directories.
 */
object PathValidator {
    private const val TAG = "PathValidator"

    // Characters that are unsafe in file names
    private val UNSAFE_FILENAME_CHARS = Regex("[\\x00-\\x1F\\x7F<>:\"|?*\\\\]")

    // Path traversal patterns
    private val PATH_TRAVERSAL_PATTERNS = listOf(
        "..",
        "/../",
        "\\..\\",
        "/./",
        "\\.\\",
        "//",
        "\\\\"
    )

    /**
     * Sanitize a file name by removing or replacing unsafe characters.
     *
     * @param fileName The file name to sanitize
     * @return Sanitized file name, or null if the name is invalid
     */
    fun sanitizeFileName(fileName: String?): String? {
        if (fileName.isNullOrBlank()) {
            SafeLogger.w(TAG, "File name is null or blank")
            return null
        }

        var sanitized = fileName.trim()

        // Remove null bytes
        sanitized = sanitized.replace("\u0000", "")

        // Check for absolute paths (should be file names only)
        if (sanitized.startsWith("/") || sanitized.contains("\\") || sanitized.contains(":")) {
            SafeLogger.w(TAG, "File name contains path separators: $fileName")
            return null
        }

        // Check for parent directory references
        if (sanitized == ".." || sanitized == ".") {
            SafeLogger.w(TAG, "File name is directory reference: $fileName")
            return null
        }

        // Replace unsafe characters with underscore
        sanitized = sanitized.replace(UNSAFE_FILENAME_CHARS, "_")

        // Ensure name is not empty after sanitization
        if (sanitized.isBlank()) {
            SafeLogger.w(TAG, "File name is blank after sanitization: $fileName")
            return null
        }

        // Limit file name length (255 bytes is common filesystem limit)
        if (sanitized.toByteArray().size > 255) {
            val extension = sanitized.substringAfterLast('.', "")
            val nameWithoutExt = sanitized.substringBeforeLast('.')
            val maxNameLength = 255 - extension.length - 1 // -1 for the dot
            sanitized = nameWithoutExt.take(maxNameLength) + if (extension.isNotEmpty()) ".$extension" else ""
            SafeLogger.w(TAG, "File name truncated: $fileName -> $sanitized")
        }

        return sanitized
    }

    /**
     * Validate and sanitize a relative path.
     * Ensures the path doesn't contain directory traversal sequences and is safe to use.
     *
     * @param relativePath The relative path to validate
     * @return Sanitized relative path, or null if the path is invalid
     */
    fun validateRelativePath(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) {
            return null
        }

        var path = relativePath.trim()

        // Remove null bytes
        path = path.replace("\u0000", "")

        // Check for absolute paths
        if (path.startsWith("/") || path.startsWith("\\") || path.contains(":")) {
            SafeLogger.w(TAG, "Relative path is absolute: $relativePath")
            return null
        }

        // Check for path traversal patterns
        PATH_TRAVERSAL_PATTERNS.forEach { pattern ->
            if (path.contains(pattern)) {
                SafeLogger.w(TAG, "Path contains traversal pattern '$pattern': $relativePath")
                return null
            }
        }

        // Normalize path separators to forward slash
        path = path.replace("\\", "/")

        // Remove redundant slashes
        while (path.contains("//")) {
            path = path.replace("//", "/")
        }

        // Remove leading/trailing slashes
        path = path.trim('/')

        // Split into parts and validate each component
        val parts = path.split("/")
        val sanitizedParts = parts.mapNotNull { part ->
            if (part == ".." || part == ".") {
                SafeLogger.w(TAG, "Path contains directory reference: $relativePath")
                return null
            }
            sanitizeFileName(part)
        }

        if (sanitizedParts.size != parts.size) {
            SafeLogger.w(TAG, "Some path components failed sanitization: $relativePath")
            return null
        }

        return sanitizedParts.joinToString("/")
    }

    /**
     * Ensure a path stays within a root directory.
     * This prevents directory traversal attacks by resolving the path and checking
     * if it's still within the allowed root.
     *
     * @param rootPath The root directory that must contain the final path
     * @param relativePath The relative path to validate
     * @return The absolute path if valid and within root, null otherwise
     */
    fun validatePathWithinRoot(rootPath: String, relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) {
            return null
        }

        // First sanitize the relative path
        val sanitizedRelative = validateRelativePath(relativePath) ?: return null

        try {
            // Resolve paths
            val root = File(rootPath).canonicalFile
            val target = File(root, sanitizedRelative).canonicalFile

            // Check if target is within root
            if (!target.canonicalPath.startsWith(root.canonicalPath)) {
                SafeLogger.w(TAG, "Path escapes root directory: $relativePath")
                SafeLogger.w(TAG, "  Root: ${root.canonicalPath}")
                SafeLogger.w(TAG, "  Target: ${target.canonicalPath}")
                return null
            }

            return target.absolutePath
        } catch (e: Exception) {
            SafeLogger.e(TAG, "Error validating path: $relativePath", e)
            return null
        }
    }

    /**
     * Extract just the file name from a path (remote or local).
     * This is safer than using substringAfterLast('/') directly.
     *
     * @param path The full path
     * @return The file name, or null if invalid
     */
    fun extractFileName(path: String?): String? {
        if (path.isNullOrBlank()) {
            return null
        }

        // Remove trailing slashes
        var cleanPath = path.trimEnd('/', '\\')

        // Get the last component
        val fileName = cleanPath.substringAfterLast('/')
            .substringAfterLast('\\')

        return sanitizeFileName(fileName)
    }

    /**
     * Extract the relative path from a full remote path by removing the base path.
     *
     * @param fullPath The full remote path
     * @param basePath The base path to remove
     * @return The relative path, or null if invalid
     */
    fun extractRelativePath(fullPath: String, basePath: String): String? {
        if (fullPath.isBlank() || basePath.isBlank()) {
            return null
        }

        // Normalize paths
        val normalizedFull = fullPath.replace("\\", "/").trim('/')
        val normalizedBase = basePath.replace("\\", "/").trim('/')

        // Remove base path
        val relativePath = if (normalizedFull.startsWith(normalizedBase)) {
            normalizedFull.removePrefix(normalizedBase).trim('/')
        } else {
            normalizedFull
        }

        if (relativePath.isBlank()) {
            return null
        }

        return validateRelativePath(relativePath)
    }

    /**
     * Check if a path is safe to use (basic validation).
     *
     * @param path The path to check
     * @return True if the path appears safe, false otherwise
     */
    fun isSafePath(path: String?): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }

        // Check for null bytes
        if (path.contains("\u0000")) {
            return false
        }

        // Check for path traversal patterns
        return PATH_TRAVERSAL_PATTERNS.none { path.contains(it) }
    }
}
