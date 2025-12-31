package com.nextcloud.sync.models.network

import android.content.Context
import com.nextcloud.sync.utils.CertificatePinningHelper
import com.nextcloud.sync.utils.SafeLogger
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * WebDAV client for communicating with Nextcloud servers.
 *
 * SECURITY CONSIDERATIONS - Credential Memory Handling:
 *
 * This class stores the password as a String in memory, which cannot be securely zeroed
 * after use due to String immutability in Kotlin/Java. This is a known limitation imposed
 * by the Sardine WebDAV library architecture.
 *
 * MITIGATIONS IMPLEMENTED:
 * 1. Password is stored as a private property (minimal scope)
 * 2. WebDavClient instances should be short-lived (create, use, discard)
 * 3. Password is encrypted at rest using EncryptionUtil before being passed here
 * 4. ProGuard strips all logging that might contain credentials in release builds
 * 5. SafeLogger sanitizes any accidental credential logging in debug builds
 *
 * LIMITATIONS (UNAVOIDABLE):
 * - Password remains in memory for the lifetime of the WebDavClient instance
 * - String cannot be explicitly zeroed (unlike CharArray)
 * - Sardine library requires String credentials (cannot accept CharArray)
 * - Memory may contain password until garbage collected
 *
 * BEST PRACTICES FOR USAGE:
 * 1. Create WebDavClient instances only when needed for network operations
 * 2. Do not store WebDavClient instances as long-lived fields
 * 3. Allow instances to be garbage collected after use
 * 4. Use dependency injection to ensure instances are scoped appropriately
 *
 * Example of CORRECT usage:
 * ```kotlin
 * suspend fun performSync() {
 *     // Create client only when needed
 *     val client = WebDavClient(context, url, username, decryptedPassword)
 *     try {
 *         client.testConnection()
 *         client.listFiles("/")
 *         // ... perform sync operations
 *     } finally {
 *         // Allow client to be garbage collected
 *         // Password will eventually be cleared by GC
 *     }
 * }
 * ```
 *
 * Example of INCORRECT usage:
 * ```kotlin
 * class MyService {
 *     // DON'T: Store as long-lived field
 *     private val client = WebDavClient(...)
 * }
 * ```
 *
 * ALTERNATIVE APPROACHES CONSIDERED:
 * - Using CharArray: Sardine library doesn't support it
 * - Custom WebDAV implementation: Too complex, high maintenance burden
 * - Contributing to Sardine: Library appears unmaintained
 *
 * @param context Application context for certificate pinning
 * @param serverUrl Nextcloud server URL (e.g., "https://cloud.example.com")
 * @param username User's username
 * @param password User's password (will be held in memory - see security notes above)
 */
class WebDavClient(
    private val context: Context,
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    companion object {
        // SECURITY: Limits to prevent DoS attacks from malicious WebDAV servers
        private const val MAX_WEBDAV_RESPONSE_ITEMS = 10000 // Maximum items per directory listing
        private const val MAX_FILE_NAME_LENGTH = 255 // Maximum file/folder name length
        private const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024 * 1024 // 10 GB max file size
    }

    private val sardineHttpClient: OkHttpClient by lazy {
        CertificatePinningHelper.createPinnedClient(
            context = context,
            serverUrl = serverUrl,
            connectTimeout = 30,
            readTimeout = 120,  // Increased for large file downloads
            writeTimeout = 120  // Increased for large file uploads
        )
    }

    private val sardine: Sardine by lazy {
        OkHttpSardine(sardineHttpClient).apply {
            setCredentials(username, password)
        }
    }

    private val webdavBaseUrl: String
        get() = "$serverUrl/remote.php/dav/files/$username"

    /**
     * Extract the user-relative path from a full WebDAV path
     * Strips the WebDAV base URL to return just the file/folder path relative to the user's root
     */
    private fun extractUserRelativePath(fullPath: String): String {
        // fullPath example: /remote.php/dav/files/username/folder/file.txt
        // Should return: /folder/file.txt

        // Try to remove the new WebDAV path format first
        val newFormatPrefix = "/remote.php/dav/files/$username"
        if (fullPath.startsWith(newFormatPrefix)) {
            val relativePath = fullPath.removePrefix(newFormatPrefix)
            return if (relativePath.isEmpty()) "/" else relativePath
        }

        // Try legacy WebDAV format
        val legacyPrefix = "/remote.php/webdav"
        if (fullPath.startsWith(legacyPrefix)) {
            val relativePath = fullPath.removePrefix(legacyPrefix)
            return if (relativePath.isEmpty()) "/" else relativePath
        }

        // If no WebDAV prefix found, return as-is
        return fullPath
    }

    private val httpClient: OkHttpClient by lazy {
        CertificatePinningHelper.createPinnedClient(
            context = context,
            serverUrl = serverUrl,
            connectTimeout = 30,
            readTimeout = 30
        )
    }

    suspend fun testConnection(): ConnectionResult = withContext(Dispatchers.IO) {
        try {
            // Try the main status endpoint first to validate server
            val statusUrl = "$serverUrl/status.php"
            val statusRequest = Request.Builder()
                .url(statusUrl)
                .get()
                .build()

            val statusResponse = httpClient.newCall(statusRequest).execute()
            if (!statusResponse.isSuccessful) {
                statusResponse.close()
                return@withContext ConnectionResult.Error("Cannot reach Nextcloud server. Check the server URL.")
            }
            statusResponse.close()

            // Now test WebDAV authentication
            // Try new path first: /remote.php/dav/files/username
            var testUrl = webdavBaseUrl
            var request = Request.Builder()
                .url(testUrl)
                .method("PROPFIND", null)
                .header("Authorization", Credentials.basic(username, password))
                .header("Depth", "0")
                .build()

            var response = httpClient.newCall(request).execute()

            // If 404, try legacy WebDAV path
            if (response.code == 404) {
                response.close()
                testUrl = "$serverUrl/remote.php/webdav"
                request = Request.Builder()
                    .url(testUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(username, password))
                    .header("Depth", "0")
                    .build()

                response = httpClient.newCall(request).execute()
            }

            when {
                response.isSuccessful -> {
                    response.close()
                    ConnectionResult.Success
                }
                response.code == 401 -> {
                    // Check for 2FA header
                    val requires2FA = response.headers["X-Nextcloud-2FA-Required"] != null
                    response.close()

                    if (requires2FA) {
                        ConnectionResult.RequiresTwoFactor
                    } else {
                        ConnectionResult.Error("Invalid username or password")
                    }
                }
                response.code == 404 -> {
                    response.close()
                    ConnectionResult.Error("WebDAV endpoint not found. Please check your server URL and ensure WebDAV is enabled.")
                }
                else -> {
                    val errorMsg = "Connection failed: HTTP ${response.code} - ${response.message}"
                    response.close()
                    ConnectionResult.Error(errorMsg)
                }
            }
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Connection test failed", e)
            ConnectionResult.Error("Connection failed: ${e.message}")
        }
    }

    suspend fun listFiles(remotePath: String): List<DavResource> = withContext(Dispatchers.IO) {
        try {
            val fullPath = if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$remotePath"
            } else {
                "$webdavBaseUrl/$remotePath"
            }

            val resources = sardine.list(fullPath)

            // SECURITY: Validate response size to prevent DoS attacks
            if (resources.size > MAX_WEBDAV_RESPONSE_ITEMS) {
                SafeLogger.w("WebDavClient", "WebDAV response too large: ${resources.size} items (max: $MAX_WEBDAV_RESPONSE_ITEMS)")
                throw IllegalStateException("Too many files in directory (max $MAX_WEBDAV_RESPONSE_ITEMS)")
            }

            resources
                .filter { !it.isDirectory }
                .take(MAX_WEBDAV_RESPONSE_ITEMS) // Additional safety limit
                .mapNotNull { resource ->
                    val name = resource.name ?: ""

                    // SECURITY: Validate file name length
                    if (name.length > MAX_FILE_NAME_LENGTH) {
                        SafeLogger.w("WebDavClient", "File name too long (${name.length} chars), skipping: ${name.take(50)}...")
                        return@mapNotNull null
                    }

                    DavResource(
                        path = extractUserRelativePath(resource.path),
                        name = name,
                        contentLength = resource.contentLength ?: 0L,
                        modified = resource.modified ?: Date(),
                        etag = resource.etag ?: "",
                        isDirectory = false
                    )
                }
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Failed to list files", e)
            emptyList()
        }
    }

    suspend fun listFolders(remotePath: String): List<DavResource> = withContext(Dispatchers.IO) {
        try {
            val fullPath = if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$remotePath"
            } else {
                "$webdavBaseUrl/$remotePath"
            }

            val resources = sardine.list(fullPath)

            // SECURITY: Validate response size to prevent DoS attacks
            if (resources.size > MAX_WEBDAV_RESPONSE_ITEMS) {
                SafeLogger.w("WebDavClient", "WebDAV response too large: ${resources.size} items (max: $MAX_WEBDAV_RESPONSE_ITEMS)")
                throw IllegalStateException("Too many folders in directory (max $MAX_WEBDAV_RESPONSE_ITEMS)")
            }

            resources
                .filter { it.isDirectory && it.path != fullPath }
                .take(MAX_WEBDAV_RESPONSE_ITEMS) // Additional safety limit
                .mapNotNull { resource ->
                    val name = resource.name ?: ""

                    // SECURITY: Validate folder name length
                    if (name.length > MAX_FILE_NAME_LENGTH) {
                        SafeLogger.w("WebDavClient", "Folder name too long (${name.length} chars), skipping: ${name.take(50)}...")
                        return@mapNotNull null
                    }

                    DavResource(
                        path = extractUserRelativePath(resource.path),
                        name = name,
                        contentLength = 0L,
                        modified = resource.modified ?: Date(),
                        etag = "",
                        isDirectory = true
                    )
                }
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Failed to list folders", e)
            emptyList()
        }
    }

    /**
     * URL-encode a path by encoding each segment individually.
     * This preserves the forward slashes while encoding special characters like spaces.
     *
     * @param path Path to encode (e.g., "/folder/file name.txt")
     * @return Encoded path (e.g., "/folder/file%20name.txt")
     */
    private fun encodePathSegments(path: String): String {
        if (path.isEmpty()) return path

        // Split by "/" and encode each segment
        val segments = path.split("/")
        return segments.joinToString("/") { segment ->
            if (segment.isEmpty()) {
                segment
            } else {
                // URL encode the segment, then replace + with %20 for proper space encoding
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        }
    }

    private fun buildFullPath(remotePath: String): String {
        // remotePath should be a user-relative path (e.g., /folder/file.txt)
        // We need to prepend the WebDAV base URL to make it a full WebDAV path

        // For backwards compatibility, check if path already contains WebDAV structure
        // (this should not happen with the new cleaned paths from listFiles/listFolders)
        return if (remotePath.contains("/remote.php/dav/files/") || remotePath.contains("/remote.php/webdav/")) {
            SafeLogger.w("WebDavClient", "Path already contains WebDAV structure: $remotePath")
            "$serverUrl$remotePath"
        } else {
            // Build the full path with webdavBaseUrl and URL-encode the path segments
            val encodedPath = encodePathSegments(remotePath)
            if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$encodedPath"
            } else {
                "$webdavBaseUrl/$encodedPath"
            }
        }
    }

    suspend fun uploadFile(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = buildFullPath(remotePath)
            SafeLogger.d("WebDavClient", "Uploading to: $fullPath")

            sardine.put(fullPath, localFile, null)
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Upload failed", e)
            false
        }
    }

    suspend fun uploadFile(inputStream: java.io.InputStream, remotePath: String, contentLength: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = buildFullPath(remotePath)
            SafeLogger.d("WebDavClient", "Uploading stream to: $fullPath")

            // Read stream into byte array (sardine requires byte array for upload)
            val bytes = inputStream.readBytes()
            sardine.put(fullPath, bytes, null)
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Upload from stream failed", e)
            false
        }
    }

    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = buildFullPath(remotePath)
            SafeLogger.d("WebDavClient", "Downloading from: $fullPath")

            localFile.parentFile?.mkdirs()

            sardine.get(fullPath).use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Download failed", e)
            false
        }
    }

    suspend fun getFileStream(remotePath: String): java.io.InputStream? = withContext(Dispatchers.IO) {
        try {
            val fullPath = buildFullPath(remotePath)
            SafeLogger.d("WebDavClient", "Getting file stream from: $fullPath")

            sardine.get(fullPath)
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Failed to get file stream from: $remotePath", e)
            null
        }
    }

    suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = buildFullPath(remotePath)
            SafeLogger.d("WebDavClient", "Deleting: $fullPath")

            sardine.delete(fullPath)
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Delete failed", e)
            false
        }
    }

    suspend fun createDirectory(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = buildFullPath(remotePath)
            SafeLogger.d("WebDavClient", "Creating directory: $fullPath")

            sardine.createDirectory(fullPath)
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Create directory failed", e)
            false
        }
    }

    /**
     * Rename a file or folder.
     * This is implemented as a move operation within the same directory.
     *
     * @param oldPath Current user-relative path (e.g., "/folder/oldname.txt")
     * @param newPath New user-relative path (e.g., "/folder/newname.txt")
     * @return True if successful, false otherwise
     */
    suspend fun renameFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullOldPath = buildFullPath(oldPath)
            val fullNewPath = buildFullPath(newPath)
            SafeLogger.d("WebDavClient", "Renaming from: $fullOldPath to: $fullNewPath")

            sardine.move(fullOldPath, fullNewPath)
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Rename failed", e)
            false
        }
    }

    /**
     * Move a file or folder to a different directory.
     *
     * @param sourcePath Source user-relative path (e.g., "/folder1/file.txt")
     * @param destPath Destination user-relative path (e.g., "/folder2/file.txt")
     * @return True if successful, false otherwise
     */
    suspend fun moveFile(sourcePath: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullSourcePath = buildFullPath(sourcePath)
            val fullDestPath = buildFullPath(destPath)
            SafeLogger.d("WebDavClient", "Moving from: $fullSourcePath to: $fullDestPath")

            sardine.move(fullSourcePath, fullDestPath)
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Move failed", e)
            false
        }
    }

    /**
     * Copy a file or folder to a different location.
     *
     * @param sourcePath Source user-relative path (e.g., "/folder1/file.txt")
     * @param destPath Destination user-relative path (e.g., "/folder2/file.txt")
     * @return True if successful, false otherwise
     */
    suspend fun copyFile(sourcePath: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullSourcePath = buildFullPath(sourcePath)
            val fullDestPath = buildFullPath(destPath)
            SafeLogger.d("WebDavClient", "Copying from: $fullSourcePath to: $fullDestPath")

            sardine.copy(fullSourcePath, fullDestPath)
            true
        } catch (e: Exception) {
            SafeLogger.e("WebDavClient", "Copy failed", e)
            false
        }
    }
}

data class DavResource(
    val path: String,
    val name: String,
    val contentLength: Long,
    val modified: Date,
    val etag: String,
    val isDirectory: Boolean
)

sealed class ConnectionResult {
    object Success : ConnectionResult()
    object RequiresTwoFactor : ConnectionResult()
    data class Error(val message: String) : ConnectionResult()
}
