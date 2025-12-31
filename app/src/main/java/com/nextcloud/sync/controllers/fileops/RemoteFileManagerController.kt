package com.nextcloud.sync.controllers.fileops

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.entities.FileEntity
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.DocumentFileHelper
import com.nextcloud.sync.utils.FileHashUtil
import com.nextcloud.sync.utils.HiddenFilesPreference
import com.nextcloud.sync.utils.PathValidator
import com.nextcloud.sync.utils.RemoteFileCache
import com.nextcloud.sync.utils.SafeLogger
import java.io.File
import java.util.Date

/**
 * Controller for remote file manager operations.
 *
 * Handles browsing remote Nextcloud files, downloading files,
 * and managing remote files (delete, rename, move, copy).
 *
 * @property webDavClient WebDAV client for remote operations
 * @property folderRepository Repository for folder operations
 * @property fileRepository Repository for file operations
 * @property context Application context for preferences
 */
class RemoteFileManagerController(
    private val webDavClient: WebDavClient,
    private val folderRepository: FolderRepository,
    private val fileRepository: FileRepository,
    private val context: Context
) {

    /**
     * Data class representing a remote file/folder for UI display.
     */
    data class RemoteFileItem(
        val path: String,
        val name: String,
        val size: Long,
        val lastModified: Date,
        val etag: String,
        val isDirectory: Boolean,
        val mimeType: String
    )

    /**
     * List both files and folders in a remote directory.
     *
     * @param remotePath Remote WebDAV path (e.g., "/folder/subfolder")
     * @param userEmail User's email address for filtering email-based hidden directories (optional)
     * @param forceRefresh If true, bypass cache and fetch from server
     * @return List of remote file items (files and folders)
     */
    suspend fun listFilesAndFolders(
        remotePath: String,
        userEmail: String? = null,
        forceRefresh: Boolean = false
    ): List<RemoteFileItem> {
        return try {
            // Validate path - WebDAV paths should start with / and not contain dangerous characters
            if (remotePath.isEmpty() || (!remotePath.startsWith("/") && remotePath != "/")) {
                SafeLogger.e("RemoteFileManagerController", "Invalid remote path (must start with /): $remotePath")
                return emptyList()
            }

            // Check for path traversal attempts
            if (remotePath.contains("..")) {
                SafeLogger.e("RemoteFileManagerController", "Invalid remote path (contains ..): $remotePath")
                return emptyList()
            }

            // Build cache key including user email and hidden files preference
            val showHidden = HiddenFilesPreference.getShowHidden(context)
            val cacheKey = buildCacheKey(remotePath, userEmail, showHidden)

            // Check cache first (unless force refresh)
            if (!forceRefresh) {
                val cachedItems = RemoteFileCache.getCached(cacheKey)
                if (cachedItems != null) {
                    return cachedItems
                }
            }

            // Cache miss or force refresh - fetch from server
            val folders = webDavClient.listFolders(remotePath)
            val files = webDavClient.listFiles(remotePath)

            val items = mutableListOf<RemoteFileItem>()

            // Add folders first (filter hidden if needed)
            folders.forEach { folder ->
                // Skip the current directory itself (WebDAV returns it as the first item)
                // Normalize paths by removing trailing slashes for comparison
                val normalizedFolderPath = folder.path.trimEnd('/')
                val normalizedRemotePath = remotePath.trimEnd('/')

                if (normalizedFolderPath == normalizedRemotePath) {
                    return@forEach
                }

                // Filter hidden folders if needed
                if (!HiddenFilesPreference.shouldFilter(folder.name, showHidden, userEmail)) {
                    items.add(
                        RemoteFileItem(
                            path = folder.path,
                            name = folder.name,
                            size = 0,
                            lastModified = folder.modified,
                            etag = folder.etag,
                            isDirectory = true,
                            mimeType = "inode/directory"
                        )
                    )
                }
            }

            // Add files (filter hidden if needed)
            files.forEach { file ->
                // Skip the current directory itself (shouldn't happen for files, but be safe)
                if (file.path == remotePath) {
                    return@forEach
                }

                // Filter hidden files if needed
                if (!HiddenFilesPreference.shouldFilter(file.name, showHidden, userEmail)) {
                    items.add(
                        RemoteFileItem(
                            path = file.path,
                            name = file.name,
                            size = file.contentLength,
                            lastModified = file.modified,
                            etag = file.etag,
                            isDirectory = false,
                            mimeType = getMimeTypeFromName(file.name)
                        )
                    )
                }
            }

            // Store in cache
            RemoteFileCache.put(cacheKey, items)

            items
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Failed to list files and folders", e)
            emptyList()
        }
    }

    /**
     * Build cache key that includes path, user email, and hidden files preference.
     */
    private fun buildCacheKey(path: String, userEmail: String?, showHidden: Boolean): String {
        return "${path}|${userEmail ?: "null"}|$showHidden"
    }

    /**
     * Search for files matching a query in a remote directory.
     *
     * @param query Search query (filename contains)
     * @param remotePath Remote directory to search in
     * @return List of matching files
     */
    suspend fun searchFiles(query: String, remotePath: String): List<RemoteFileItem> {
        return try {
            if (query.isEmpty()) {
                return emptyList()
            }

            val allItems = listFilesAndFolders(remotePath)

            // Filter by name (case-insensitive)
            allItems.filter { item ->
                item.name.contains(query, ignoreCase = true)
            }
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Search failed", e)
            emptyList()
        }
    }

    /**
     * Simple MIME type detection based on file extension.
     * For production, consider using a more comprehensive MIME type database.
     */
    private fun getMimeTypeFromName(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            // Text
            "txt" -> "text/plain"
            "md", "markdown" -> "text/markdown"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "json" -> "application/json"
            "xml" -> "application/xml"

            // Documents
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ods" -> "application/vnd.oasis.opendocument.spreadsheet"

            // Images
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"

            // Video
            "mp4" -> "video/mp4"
            "avi" -> "video/x-msvideo"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"

            // Audio
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "m4a" -> "audio/mp4"

            // Archives
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "tar" -> "application/x-tar"
            "gz", "gzip" -> "application/gzip"

            // Code
            "kt" -> "text/x-kotlin"
            "java" -> "text/x-java-source"
            "py" -> "text/x-python"
            "cpp", "cc" -> "text/x-c++src"
            "c" -> "text/x-csrc"
            "h" -> "text/x-chdr"
            "rs" -> "text/x-rust"
            "go" -> "text/x-go"

            else -> "application/octet-stream"
        }
    }

    /**
     * Download a remote file to a local sync folder.
     * Creates a file entry in the database and triggers download.
     *
     * @param remotePath Remote file path
     * @param accountId Account ID
     * @param targetFolderId Target sync folder ID
     * @return True if download initiated successfully
     */
    suspend fun downloadFileToSyncFolder(
        remotePath: String,
        accountId: Long,
        targetFolderId: Long
    ): Boolean {
        return try {
            // Validate path
            if (PathValidator.validateRelativePath(remotePath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid remote path: $remotePath")
                return false
            }

            // Get target folder
            val folder = folderRepository.getFolderById(targetFolderId)
            if (folder == null) {
                SafeLogger.e("RemoteFileManagerController", "Folder not found: $targetFolderId")
                return false
            }

            // Extract filename
            val fileName = remotePath.substringAfterLast('/')

            // Get remote file metadata to get etag and size
            val remoteFiles = webDavClient.listFiles(remotePath.substringBeforeLast('/'))
            val remoteFile = remoteFiles.find { it.path == remotePath }

            // Build local path and download
            val success = if (folder.localPath.startsWith("content://")) {
                // Handle content URI
                downloadToContentUri(folder.localPath, fileName, remotePath, remoteFile)
            } else {
                // Handle file path
                downloadToFilePath(folder.localPath, fileName, remotePath, remoteFile)
            }

            if (!success) {
                SafeLogger.e("RemoteFileManagerController", "Download failed for: $remotePath")
                return false
            }

            // Create FileEntity in database
            val localPath = if (folder.localPath.endsWith("/")) {
                "${folder.localPath}$fileName"
            } else {
                "${folder.localPath}/$fileName"
            }

            // Calculate local hash
            val localHash = if (folder.localPath.startsWith("content://")) {
                // For content URI, we'd need to calculate from stream
                // For now, we'll leave it null and let sync update it
                null
            } else {
                FileHashUtil.calculateHash(File(localPath))
            }

            val fileEntity = FileEntity(
                folderId = targetFolderId,
                localPath = localPath,
                remotePath = remotePath,
                fileName = fileName,
                fileSize = remoteFile?.contentLength ?: 0L,
                mimeType = getMimeTypeFromName(fileName),
                localHash = localHash,
                remoteHash = null,
                localModified = System.currentTimeMillis(),
                remoteModified = remoteFile?.modified?.time,
                syncStatus = SyncStatus.SYNCED,
                lastSync = System.currentTimeMillis(),
                etag = remoteFile?.etag
            )

            fileRepository.insert(fileEntity)
            SafeLogger.d("RemoteFileManagerController", "Downloaded and added to database: $fileName")

            true
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Download failed", e)
            false
        }
    }

    private suspend fun downloadToFilePath(
        folderPath: String,
        fileName: String,
        remotePath: String,
        remoteFile: com.nextcloud.sync.models.network.DavResource?
    ): Boolean {
        return try {
            val localPath = if (folderPath.endsWith("/")) {
                "$folderPath$fileName"
            } else {
                "$folderPath/$fileName"
            }
            val localFile = File(localPath)

            // Create parent directories if needed
            localFile.parentFile?.mkdirs()

            // Download file
            webDavClient.downloadFile(remotePath, localFile)
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "File path download failed", e)
            false
        }
    }

    private suspend fun downloadToContentUri(
        folderUri: String,
        fileName: String,
        remotePath: String,
        remoteFile: com.nextcloud.sync.models.network.DavResource?
    ): Boolean {
        return try {
            val docHelper = DocumentFileHelper(context)
            val rootDoc = docHelper.getDocumentFile(folderUri)
            if (rootDoc == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid folder URI: $folderUri")
                return false
            }

            // Create file in folder
            val mimeType = getMimeTypeFromName(fileName)
            val newFile = docHelper.createFile(rootDoc, fileName, mimeType)
            if (newFile == null) {
                SafeLogger.e("RemoteFileManagerController", "Failed to create file: $fileName")
                return false
            }

            // Download to the file
            val inputStream = webDavClient.getFileStream(remotePath)
            if (inputStream == null) {
                SafeLogger.e("RemoteFileManagerController", "Failed to get file stream: $remotePath")
                return false
            }

            inputStream.use { input ->
                docHelper.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            true
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Content URI download failed", e)
            false
        }
    }

    /**
     * Delete a remote file from the server.
     *
     * @param remotePath Remote file path
     * @return True if successful
     */
    suspend fun deleteRemoteFile(remotePath: String): Boolean {
        return try {
            // Validate path
            if (PathValidator.validateRelativePath(remotePath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid remote path: $remotePath")
                return false
            }

            // Delete file
            val success = webDavClient.deleteFile(remotePath)

            if (success) {
                // Invalidate cache for parent directory
                invalidateCacheForPath(remotePath)
                SafeLogger.d("RemoteFileManagerController", "Deleted remote file: $remotePath")
            }

            success
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Delete failed", e)
            false
        }
    }

    /**
     * Rename a remote file on the server.
     *
     * @param oldPath Current remote path
     * @param newName New filename (not full path)
     * @return True if successful
     */
    suspend fun renameRemoteFile(oldPath: String, newName: String): Boolean {
        return try {
            // Validate paths
            if (PathValidator.validateRelativePath(oldPath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid old path: $oldPath")
                return false
            }

            // Validate new name (no slashes, not empty)
            if (newName.isEmpty() || newName.contains("/") || newName.contains("\\")) {
                SafeLogger.e("RemoteFileManagerController", "Invalid new name: $newName")
                return false
            }

            // Build new path
            val parentPath = oldPath.substringBeforeLast('/')
            val newPath = if (parentPath.isEmpty()) {
                "/$newName"
            } else {
                "$parentPath/$newName"
            }

            // Rename file
            val success = webDavClient.renameFile(oldPath, newPath)

            if (success) {
                // Invalidate cache for parent directory
                invalidateCacheForPath(oldPath)
                SafeLogger.d("RemoteFileManagerController", "Renamed: $oldPath -> $newPath")
            }

            success
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Rename failed", e)
            false
        }
    }

    /**
     * Move a remote file to a different directory.
     *
     * @param sourcePath Source remote path
     * @param destinationPath Destination remote directory path
     * @return True if successful
     */
    suspend fun moveRemoteFile(sourcePath: String, destinationPath: String): Boolean {
        return try {
            // Validate paths
            if (PathValidator.validateRelativePath(sourcePath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid source path: $sourcePath")
                return false
            }
            if (PathValidator.validateRelativePath(destinationPath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid destination path: $destinationPath")
                return false
            }

            // Extract filename
            val fileName = sourcePath.substringAfterLast('/')

            // Build full destination path
            val fullDestPath = if (destinationPath.endsWith("/")) {
                "$destinationPath$fileName"
            } else {
                "$destinationPath/$fileName"
            }

            // Move file
            val success = webDavClient.moveFile(sourcePath, fullDestPath)

            if (success) {
                // Invalidate cache for both source and destination directories
                invalidateCacheForPath(sourcePath)
                invalidateCacheForPath(fullDestPath)
                SafeLogger.d("RemoteFileManagerController", "Moved: $sourcePath -> $fullDestPath")
            }

            success
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Move failed", e)
            false
        }
    }

    /**
     * Copy a remote file to a different location.
     *
     * @param sourcePath Source remote path
     * @param destinationPath Destination remote directory path
     * @return True if successful
     */
    suspend fun copyRemoteFile(sourcePath: String, destinationPath: String): Boolean {
        return try {
            // Validate paths
            if (PathValidator.validateRelativePath(sourcePath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid source path: $sourcePath")
                return false
            }
            if (PathValidator.validateRelativePath(destinationPath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid destination path: $destinationPath")
                return false
            }

            // Extract filename
            val fileName = sourcePath.substringAfterLast('/')

            // Build full destination path
            val fullDestPath = if (destinationPath.endsWith("/")) {
                "$destinationPath$fileName"
            } else {
                "$destinationPath/$fileName"
            }

            // Copy file
            val success = webDavClient.copyFile(sourcePath, fullDestPath)

            if (success) {
                // Invalidate cache for destination directory
                invalidateCacheForPath(fullDestPath)
                SafeLogger.d("RemoteFileManagerController", "Copied: $sourcePath -> $fullDestPath")
            }

            success
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Copy failed", e)
            false
        }
    }

    /**
     * Get available sync folders for download destination picker.
     *
     * @param accountId Account ID
     * @return List of folder entities
     */
    suspend fun getAvailableSyncFolders(accountId: Long): List<FolderEntity> {
        return try {
            folderRepository.getFoldersByAccount(accountId)
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Failed to get folders", e)
            emptyList()
        }
    }

    /**
     * Invalidate cache for a path and its parent directory.
     *
     * @param path Path to invalidate cache for
     */
    private suspend fun invalidateCacheForPath(path: String) {
        try {
            // Invalidate the parent directory cache
            val parentPath = path.substringBeforeLast('/', "/")
            RemoteFileCache.invalidate(parentPath)
            SafeLogger.d("RemoteFileManagerController", "Invalidated cache for: $parentPath")
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Cache invalidation failed", e)
        }
    }
}
