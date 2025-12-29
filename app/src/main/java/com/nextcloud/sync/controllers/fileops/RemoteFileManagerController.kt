package com.nextcloud.sync.controllers.fileops

import android.content.Context
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.IndividualFileSyncRepository
import com.nextcloud.sync.models.database.entities.IndividualFileSyncEntity
import com.nextcloud.sync.utils.HiddenFilesPreference
import com.nextcloud.sync.utils.PathValidator
import com.nextcloud.sync.utils.SafeLogger
import java.util.Date

/**
 * Controller for remote file manager operations.
 *
 * Handles browsing remote Nextcloud files, adding files to individual sync,
 * and searching remote files.
 *
 * @property webDavClient WebDAV client for remote operations
 * @property individualFileSyncRepository Repository for individual file sync
 * @property context Application context for preferences
 */
class RemoteFileManagerController(
    private val webDavClient: WebDavClient,
    private val individualFileSyncRepository: IndividualFileSyncRepository,
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
     * @return List of remote file items (files and folders)
     */
    suspend fun listFilesAndFolders(remotePath: String, userEmail: String? = null): List<RemoteFileItem> {
        return try {
            // Validate path (allow root path "/" as special case)
            if (remotePath != "/" && PathValidator.validateRelativePath(remotePath) == null) {
                SafeLogger.e("RemoteFileManagerController", "Invalid remote path: $remotePath")
                return emptyList()
            }

            val showHidden = HiddenFilesPreference.getShowHidden(context)
            val folders = webDavClient.listFolders(remotePath)
            val files = webDavClient.listFiles(remotePath)

            val items = mutableListOf<RemoteFileItem>()

            // Add folders first (filter hidden if needed)
            folders.forEach { folder ->
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

            items
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Failed to list files and folders", e)
            emptyList()
        }
    }

    /**
     * Add selected files to individual sync configuration.
     *
     * @param filePaths List of remote file paths to add
     * @param accountId Account ID
     * @param localBasePath Base local path where files will be synced
     * @param wifiOnly Whether to restrict sync to WiFi
     * @return True if all files added successfully, false otherwise
     */
    suspend fun addFilesToSync(
        filePaths: List<String>,
        accountId: Long,
        localBasePath: String,
        wifiOnly: Boolean
    ): Boolean {
        return try {
            var allSuccess = true

            filePaths.forEach { remotePath ->
                // Validate path
                if (PathValidator.validateRelativePath(remotePath) == null) {
                    SafeLogger.e("RemoteFileManagerController", "Invalid path: $remotePath")
                    allSuccess = false
                    return@forEach
                }

                // Check if already being synced
                val existing = individualFileSyncRepository.getByRemotePath(remotePath, accountId)
                if (existing != null) {
                    SafeLogger.d("RemoteFileManagerController", "File already in sync: $remotePath")
                    return@forEach
                }

                // Extract filename
                val fileName = remotePath.substringAfterLast('/')

                // Build local path
                val localPath = if (localBasePath.endsWith("/")) {
                    "$localBasePath$fileName"
                } else {
                    "$localBasePath/$fileName"
                }

                // Create individual file sync entity
                val entity = IndividualFileSyncEntity(
                    accountId = accountId,
                    localPath = localPath,
                    remotePath = remotePath,
                    fileName = fileName,
                    syncEnabled = true,
                    autoSync = true,
                    wifiOnly = wifiOnly,
                    lastSync = null
                )

                val id = individualFileSyncRepository.insert(entity)
                if (id <= 0) {
                    allSuccess = false
                }
            }

            allSuccess
        } catch (e: Exception) {
            SafeLogger.e("RemoteFileManagerController", "Failed to add files to sync", e)
            false
        }
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
     * Get files already added to individual sync for an account.
     *
     * @param accountId Account ID
     * @return List of individual file sync entities
     */
    suspend fun getSyncedFiles(accountId: Long): List<IndividualFileSyncEntity> {
        return individualFileSyncRepository.getAllFiles(accountId)
    }

    /**
     * Check if a remote path is already being synced.
     *
     * @param remotePath Remote file path
     * @param accountId Account ID
     * @return True if already in sync, false otherwise
     */
    suspend fun isAlreadySynced(remotePath: String, accountId: Long): Boolean {
        return individualFileSyncRepository.isAlreadySynced(remotePath, accountId)
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
}
