package com.nextcloud.sync.controllers.fileops

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.DocumentFileHelper
import com.nextcloud.sync.utils.HiddenFilesPreference
import java.io.File

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
