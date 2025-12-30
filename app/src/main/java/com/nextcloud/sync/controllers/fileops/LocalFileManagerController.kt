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
            SafeLogger.d("LocalFileManagerController", "Attempting to rename: $filePath to $newName")

            val renamed = if (filePath.startsWith("content://")) {
                // For content URIs, use copy+delete approach since SingleDocumentFile.renameTo() is not supported
                renameContentUri(filePath, newName)
            } else {
                val file = File(filePath)
                if (!file.exists()) {
                    SafeLogger.e("LocalFileManagerController", "File does not exist: $filePath")
                    return false
                }
                val parent = file.parentFile
                if (parent == null) {
                    SafeLogger.e("LocalFileManagerController", "Parent directory is null for: $filePath")
                    return false
                }
                val newFile = File(parent, newName)
                if (newFile.exists()) {
                    SafeLogger.e("LocalFileManagerController", "Target file already exists: ${newFile.absolutePath}")
                    return false
                }
                val result = file.renameTo(newFile)
                SafeLogger.d("LocalFileManagerController", "File rename result: $result, from ${file.absolutePath} to ${newFile.absolutePath}")

                if (result) {
                    // Update database with new path, filename, remote path, and mark for sync
                    fileRepository.getFileByLocalPath(filePath)?.let { dbFile: FileEntity ->
                        val newPath = newFile.absolutePath
                        val newRemotePath = dbFile.remotePath.substringBeforeLast("/") + "/" + newName
                        SafeLogger.d("LocalFileManagerController", "Updating database: localPath=$newPath, remotePath=$newRemotePath, fileName=$newName")
                        fileRepository.update(dbFile.copy(
                            localPath = newPath,
                            remotePath = newRemotePath,
                            fileName = newName,
                            syncStatus = SyncStatus.PENDING_UPLOAD
                        ))
                    }
                }

                result
            }

            if (renamed) {
                SafeLogger.d("LocalFileManagerController", "Successfully renamed file: $filePath to $newName")
            } else {
                SafeLogger.w("LocalFileManagerController", "Rename returned false for: $filePath to $newName")
            }

            renamed
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to rename file: $filePath to $newName", e)
            false
        }
    }

    /**
     * Rename a content URI file using copy+delete approach.
     * SingleDocumentFile.renameTo() is not supported, so we need to:
     * 1. Get the parent folder's tree URI
     * 2. Create a new file with the new name
     * 3. Copy the content
     * 4. Delete the old file
     * 5. Update the database
     */
    private suspend fun renameContentUri(filePath: String, newName: String): Boolean {
        return try {
            val sourceUri = Uri.parse(filePath)
            val sourceFile = DocumentFile.fromSingleUri(context, sourceUri)

            if (sourceFile == null || !sourceFile.exists()) {
                SafeLogger.e("LocalFileManagerController", "Source file does not exist: $filePath")
                return false
            }

            // Get the folder that contains this file to find the tree URI
            val dbFile = fileRepository.getFileByLocalPath(filePath)
            if (dbFile == null) {
                SafeLogger.e("LocalFileManagerController", "File not found in database: $filePath")
                return false
            }

            val folder = folderRepository.getFolderById(dbFile.folderId)
            if (folder == null) {
                SafeLogger.e("LocalFileManagerController", "Folder not found for file: $filePath")
                return false
            }

            val treeUri = folder.localPath
            if (!treeUri.startsWith("content://")) {
                SafeLogger.e("LocalFileManagerController", "Expected content URI for folder, got: $treeUri")
                return false
            }

            // Get the parent directory DocumentFile
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            if (rootDoc == null) {
                SafeLogger.e("LocalFileManagerController", "Could not get root DocumentFile from tree URI: $treeUri")
                return false
            }

            // Find the parent directory by traversing the path
            val relativePath = getRelativePath(sourceFile.uri, rootDoc.uri)
            val parentDoc = if (relativePath.contains("/")) {
                // Navigate to parent directory
                navigateToParentDirectory(rootDoc, relativePath)
            } else {
                // File is in root directory
                rootDoc
            }

            if (parentDoc == null) {
                SafeLogger.e("LocalFileManagerController", "Could not find parent directory for: $filePath")
                return false
            }

            // Check if file with new name already exists
            val existingFile = parentDoc.findFile(newName)
            if (existingFile != null && existingFile.exists()) {
                SafeLogger.e("LocalFileManagerController", "File with new name already exists: $newName")
                return false
            }

            // Create new file with new name
            val mimeType = sourceFile.type ?: "application/octet-stream"
            val newFile = parentDoc.createFile(mimeType, newName)
            if (newFile == null) {
                SafeLogger.e("LocalFileManagerController", "Failed to create new file: $newName")
                return false
            }

            // Copy content from old file to new file
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }

            // Delete old file
            val deleted = sourceFile.delete()
            if (!deleted) {
                SafeLogger.w("LocalFileManagerController", "Failed to delete old file after rename: $filePath")
                // Attempt to delete the new file to rollback
                newFile.delete()
                return false
            }

            // Update database with new URI, filename, remote path, and mark for sync
            val newRemotePath = dbFile.remotePath.substringBeforeLast("/") + "/" + newName
            fileRepository.update(dbFile.copy(
                localPath = newFile.uri.toString(),
                remotePath = newRemotePath,
                fileName = newName,
                syncStatus = SyncStatus.PENDING_UPLOAD
            ))

            SafeLogger.d("LocalFileManagerController", "Successfully renamed content URI file from $filePath to ${newFile.uri}, remotePath=$newRemotePath, and marked for upload")
            true
        } catch (e: Exception) {
            SafeLogger.e("LocalFileManagerController", "Failed to rename content URI: $filePath", e)
            false
        }
    }

    /**
     * Get relative path from a file URI to a root URI.
     */
    private fun getRelativePath(fileUri: Uri, rootUri: Uri): String {
        val filePathSegments = fileUri.toString().split("/")
        val rootPathSegments = rootUri.toString().split("/")

        // Find the common prefix and extract the relative part
        val fileName = filePathSegments.lastOrNull() ?: ""

        // Extract the path after the document ID
        val fileUriStr = fileUri.toString()
        val docIdStart = fileUriStr.indexOf("/document/")
        if (docIdStart != -1) {
            val docId = fileUriStr.substring(docIdStart + "/document/".length)
            // DocId format is typically "primary:path/to/file"
            val colonIndex = docId.indexOf(":")
            if (colonIndex != -1) {
                return docId.substring(colonIndex + 1)
            }
        }

        return fileName
    }

    /**
     * Navigate to parent directory from root DocumentFile.
     */
    private fun navigateToParentDirectory(rootDoc: DocumentFile, relativePath: String): DocumentFile? {
        val pathParts = relativePath.split("/")
        if (pathParts.isEmpty()) return rootDoc

        // Remove the filename, leaving only directory parts
        val directoryParts = pathParts.dropLast(1)

        var currentDoc = rootDoc
        for (dirName in directoryParts) {
            if (dirName.isEmpty()) continue

            val nextDoc = currentDoc.findFile(dirName)
            if (nextDoc == null || !nextDoc.isDirectory) {
                SafeLogger.e("LocalFileManagerController", "Could not navigate to directory: $dirName")
                return null
            }
            currentDoc = nextDoc
        }

        return currentDoc
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
