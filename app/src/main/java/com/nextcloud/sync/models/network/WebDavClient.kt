package com.nextcloud.sync.models.network

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
import java.util.Date
import java.util.concurrent.TimeUnit

class WebDavClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private val sardine: Sardine by lazy {
        OkHttpSardine().apply {
            setCredentials(username, password)
        }
    }

    private val webdavBaseUrl: String
        get() = "$serverUrl/remote.php/dav/files/$username"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
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

            sardine.list(fullPath)
                .filter { !it.isDirectory }
                .map { resource ->
                    DavResource(
                        path = resource.path,
                        name = resource.name ?: "",
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

            sardine.list(fullPath)
                .filter { it.isDirectory && it.path != fullPath }
                .map { resource ->
                    DavResource(
                        path = resource.path,
                        name = resource.name ?: "",
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

    private fun buildFullPath(remotePath: String): String {
        // If remotePath already contains the webdav base path, use it with serverUrl only
        // This happens when paths come from sardine.list() which returns absolute paths
        return if (remotePath.contains("/remote.php/dav/files/") || remotePath.contains("/remote.php/webdav/")) {
            "$serverUrl$remotePath"
        } else {
            // Otherwise, build the full path with webdavBaseUrl
            if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$remotePath"
            } else {
                "$webdavBaseUrl/$remotePath"
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
