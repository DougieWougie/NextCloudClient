package com.nextcloud.sync.controllers.fileops

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.entities.FileEntity
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.DocumentFileHelper
import com.nextcloud.sync.utils.HiddenFilesPreference
import com.nextcloud.sync.utils.SafeLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Controller for local file manager operations.
 *
 * Handles browsing local files within sync folders and retrieving file metadata.
 * Scope is limited to sync-enabled folders only (not a full device file browser).
 *
 * @property fileRepository Repository for file metadata
 * @property folderRepository Repository for folder configuration
 * @property context Application context
 */
class LocalFileManagerController(
    private val fileRepository: FileRepository,
    private val folderRepository: FolderRepository,
    private val context: Context
) {

    private val documentFileHelper = DocumentFileHelper(context)

    /**
     * Data class representing a local file for UI display.
     */
    data class LocalFileItem(
        val id: Long,
        val name: String,
        val path: String,
        val size: Long,
        val lastModified: Long,
        val mimeType: String,
        val syncStatus: SyncStatus,
        val isDirectory: Boolean
    )

    /**
     * Get all sync-enabled folders for the given account.
     *
     * @param accountId Account ID
     * @return List of folder entities
     */
    suspend fun getSyncFolders(accountId: Long): List<FolderEntity> {
        return folderRepository.getFoldersByAccount(accountId)
    }

    /**
     * List files in a specific sync folder.
     *
     * @param folderId Folder ID
     * @param userEmail User's email address for filtering email-based hidden directories (optional)
     * @return List of local file items
     */
    suspend fun listFilesInFolder(folderId: Long, userEmail: String? = null): List<LocalFileItem> {
        val folder = folderRepository.getFolderById(folderId) ?: return emptyList()
        val localPath = folder.localPath
        val showHidden = HiddenFilesPreference.getShowHidden(context)

        return if (localPath.startsWith("content://")) {
            listFilesFromContentUri(localPath, folderId, showHidden, userEmail)
        } else {
            listFilesFromFilePath(localPath, folderId, showHidden, userEmail)
        }
    }

    /**
     * List files from a content URI (Android 10+).
     */
    private suspend fun listFilesFromContentUri(
        treeUri: String,
        folderId: Long,
        showHidden: Boolean,
        userEmail: String?
    ): List<LocalFileItem> {
        val documentFile = documentFileHelper.getDocumentFile(treeUri) ?: return emptyList()
        val files = documentFileHelper.listAllFiles(documentFile)

        // Get file metadata from database for sync status
        val dbFiles = fileRepository.getFilesByFolder(folderId)
        val fileMap = dbFiles.associateBy { it.localPath }

        return files.mapNotNull { file ->
            // Filter hidden files if needed
            if (HiddenFilesPreference.shouldFilter(file.name, showHidden, userEmail)) {
                return@mapNotNull null
            }

            val dbFile = fileMap[file.uri.toString()]
            LocalFileItem(
                id = dbFile?.id ?: 0,
                name = file.name,
                path = file.uri.toString(),
                size = file.size,
                lastModified = file.lastModified,
                mimeType = file.mimeType,
                syncStatus = dbFile?.syncStatus ?: SyncStatus.PENDING_UPLOAD,
                isDirectory = false
            )
        }
    }

    /**
     * List files from a file path (legacy, Android 9 and below).
     */
    private suspend fun listFilesFromFilePath(
        dirPath: String,
        folderId: Long,
        showHidden: Boolean,
        userEmail: String?
    ): List<LocalFileItem> {
        val directory = File(dirPath)
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }

        val files = mutableListOf<LocalFileItem>()
        listFilesRecursive(directory, "", folderId, files, showHidden, userEmail)

        return files
    }

    /**
     * Recursively list files from a directory.
     */
    private suspend fun listFilesRecursive(
        directory: File,
        currentPath: String,
        folderId: Long,
        files: MutableList<LocalFileItem>,
        showHidden: Boolean,
        userEmail: String?
    ) {
        directory.listFiles()?.forEach { file ->
            // Filter hidden files if needed
            if (HiddenFilesPreference.shouldFilter(file.name, showHidden, userEmail)) {
                return@forEach
            }

            val filePath = if (currentPath.isEmpty()) {
                file.name
            } else {
                "$currentPath/${file.name}"
            }

            // Get file metadata from database for sync status
            val dbFile = fileRepository.getFileByLocalPath(file.absolutePath)

            if (file.isFile) {
                files.add(
                    LocalFileItem(
                        id = dbFile?.id ?: 0,
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        mimeType = getMimeType(file.name),
                        syncStatus = dbFile?.syncStatus ?: SyncStatus.PENDING_UPLOAD,
                        isDirectory = false
                    )
                )
            } else if (file.isDirectory) {
                files.add(
                    LocalFileItem(
                        id = 0,
                        name = file.name,
                        path = file.absolutePath,
                        size = 0,
                        lastModified = file.lastModified(),
                        mimeType = "inode/directory",
                        syncStatus = SyncStatus.SYNCED,
                        isDirectory = true
                    )
                )
                listFilesRecursive(file, filePath, folderId, files, showHidden, userEmail)
            }
        }
    }

    /**
     * Get file metadata for a specific file.
     *
     * @param filePath File path (content URI or file path)
     * @return File metadata or null if not found
     */
    suspend fun getFileMetadata(filePath: String): FileMetadata? {
        return if (filePath.startsWith("content://")) {
            val uri = Uri.parse(filePath)
            val documentFile = DocumentFile.fromSingleUri(context, uri) ?: return null

            FileMetadata(
                name = documentFile.name ?: "Unknown",
                path = filePath,
                size = documentFile.length(),
                lastModified = documentFile.lastModified(),
                mimeType = documentFile.type ?: "application/octet-stream",
                isDirectory = documentFile.isDirectory
            )
        } else {
            val file = File(filePath)
            if (!file.exists()) return null

            FileMetadata(
                name = file.name,
                path = filePath,
                size = file.length(),
                lastModified = file.lastModified(),
                mimeType = getMimeType(file.name),
                isDirectory = file.isDirectory
            )
        }
    }

    /**
     * Simple MIME type detection based on file extension.
     * For production, consider using MimeTypeMap.
     */
    private fun getMimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "zip" -> "application/zip"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            else -> "application/octet-stream"
        }
    }

    /**
     * Delete a file from local storage and update database.
     *
     * @param filePath File path (content URI or file path)
     * @return True if successful, false otherwise
     */
    suspend fun deleteFile(filePath: String): Boolean {
        return try {
            val deleted = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                val documentFile = DocumentFile.fromSingleUri(context, uri)
                documentFile?.delete() ?: false
            } else {
                val file = File(filePath)
                file.delete()
            }

            if (deleted) {
                // Remove from database
                fileRepository.getFileByLocalPath(filePath)?.let { dbFile: FileEntity ->
                    fileRepository.delete(dbFile)
                }
                SafeLogger.d("LocalFileManagerController", "Deleted file: $filePath")
            }

            deleted
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to delete file", e)
            false
        }
    }

    /**
     * Rename a file in local storage and update database.
     *
     * @param filePath File path (content URI or file path)
     * @param newName New file name
     * @return True if successful, false otherwise
     */
    suspend fun renameFile(filePath: String, newName: String): Boolean {
        return try {
            val renamed = if (filePath.startsWith("content://")) {
                val uri = Uri.parse(filePath)
                val documentFile = DocumentFile.fromSingleUri(context, uri)
                documentFile?.renameTo(newName) ?: false
            } else {
                val file = File(filePath)
                val newFile = File(file.parent, newName)
                file.renameTo(newFile)
            }

            if (renamed) {
                // Update database
                fileRepository.getFileByLocalPath(filePath)?.let { dbFile: FileEntity ->
                    val newPath = if (filePath.startsWith("content://")) {
                        // For content URIs, we need to rebuild the path
                        filePath.substringBeforeLast("/") + "/" + newName
                    } else {
                        File(File(filePath).parent, newName).absolutePath
                    }
                    fileRepository.update(dbFile.copy(localPath = newPath))
                }
                SafeLogger.d("LocalFileManagerController", "Renamed file: $filePath to $newName")
            }

            renamed
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to rename file", e)
            false
        }
    }

    /**
     * Copy a file to another location.
     *
     * @param sourcePath Source file path
     * @param destinationPath Destination directory path
     * @return True if successful, false otherwise
     */
    suspend fun copyFile(sourcePath: String, destinationPath: String): Boolean {
        return try {
            if (sourcePath.startsWith("content://") || destinationPath.startsWith("content://")) {
                // Content URI handling
                copyFileContentUri(sourcePath, destinationPath)
            } else {
                // Regular file handling
                val sourceFile = File(sourcePath)
                val destDir = File(destinationPath)
                val destFile = File(destDir, sourceFile.name)

                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                SafeLogger.d("LocalFileManagerController", "Copied file: $sourcePath to $destinationPath")
                true
            }
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to copy file", e)
            false
        }
    }

    /**
     * Copy file using content URIs.
     */
    private fun copyFileContentUri(sourcePath: String, destinationPath: String): Boolean {
        return try {
            val sourceUri = Uri.parse(sourcePath)
            val destUri = Uri.parse(destinationPath)

            val sourceFile = DocumentFile.fromSingleUri(context, sourceUri) ?: return false
            val destDir = DocumentFile.fromTreeUri(context, destUri) ?: return false

            val newFile = destDir.createFile(
                sourceFile.type ?: "application/octet-stream",
                sourceFile.name ?: "copy"
            ) ?: return false

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            true
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to copy content URI file", e)
            false
        }
    }

    /**
     * Move a file to another location.
     *
     * @param sourcePath Source file path
     * @param destinationPath Destination directory path
     * @return True if successful, false otherwise
     */
    suspend fun moveFile(sourcePath: String, destinationPath: String): Boolean {
        return try {
            val copied = copyFile(sourcePath, destinationPath)
            if (copied) {
                deleteFile(sourcePath)
            } else {
                false
            }
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to move file", e)
            false
        }
    }

    /**
     * Create an Intent to open a file with the system's default handler.
     *
     * @param filePath File path (content URI or file path)
     * @param mimeType MIME type of the file
     * @return Intent to open the file, or null if failed
     */
    fun createOpenFileIntent(filePath: String, mimeType: String): Intent? {
        return try {
            val uri = if (filePath.startsWith("content://")) {
                Uri.parse(filePath)
            } else {
                // Use FileProvider for API 24+
                val file = File(filePath)
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }

            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to create open file intent", e)
            null
        }
    }

    /**
     * Trigger download/sync for a file.
     *
     * @param filePath File path
     * @param folderId Folder ID
     * @return True if sync was triggered successfully
     */
    suspend fun downloadFile(filePath: String, folderId: Long): Boolean {
        return try {
            val dbFile = fileRepository.getFileByLocalPath(filePath)
            if (dbFile != null) {
                // Update status to trigger sync
                fileRepository.update(dbFile.copy(syncStatus = SyncStatus.PENDING_DOWNLOAD))
                SafeLogger.d("LocalFileManagerController", "Marked file for download: $filePath")
                true
            } else {
                SafeLogger.w("LocalFileManagerController", "File not found in database: $filePath")
                false
            }
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to trigger download", e)
            false
        }
    }

    /**
     * Data class for file metadata.
     */
    data class FileMetadata(
        val name: String,
        val path: String,
        val size: Long,
        val lastModified: Long,
        val mimeType: String,
        val isDirectory: Boolean
    )
}
