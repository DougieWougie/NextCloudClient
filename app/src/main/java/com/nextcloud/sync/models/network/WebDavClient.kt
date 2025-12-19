package com.nextcloud.sync.models.network

import android.util.Log
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

    suspend fun testConnection(): ConnectionResult = withContext(Dispatchers.IO) {
        try {
            val testUrl = webdavBaseUrl
            sardine.list(testUrl)
            ConnectionResult.Success
        } catch (e: Exception) {
            when {
                e.message?.contains("401") == true -> {
                    // Check if 2FA is required
                    ConnectionResult.RequiresTwoFactor
                }
                else -> ConnectionResult.Error("Connection failed: ${e.message}")
            }
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
            Log.e("WebDavClient", "Failed to list files", e)
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
            Log.e("WebDavClient", "Failed to list folders", e)
            emptyList()
        }
    }

    suspend fun uploadFile(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$remotePath"
            } else {
                "$webdavBaseUrl/$remotePath"
            }

            sardine.put(fullPath, localFile, null)
            true
        } catch (e: Exception) {
            Log.e("WebDavClient", "Upload failed", e)
            false
        }
    }

    suspend fun downloadFile(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$remotePath"
            } else {
                "$webdavBaseUrl/$remotePath"
            }

            localFile.parentFile?.mkdirs()

            sardine.get(fullPath).use { input ->
                localFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("WebDavClient", "Download failed", e)
            false
        }
    }

    suspend fun deleteFile(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$remotePath"
            } else {
                "$webdavBaseUrl/$remotePath"
            }

            sardine.delete(fullPath)
            true
        } catch (e: Exception) {
            Log.e("WebDavClient", "Delete failed", e)
            false
        }
    }

    suspend fun createDirectory(remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullPath = if (remotePath.startsWith("/")) {
                "$webdavBaseUrl$remotePath"
            } else {
                "$webdavBaseUrl/$remotePath"
            }

            sardine.createDirectory(fullPath)
            true
        } catch (e: Exception) {
            Log.e("WebDavClient", "Create directory failed", e)
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
