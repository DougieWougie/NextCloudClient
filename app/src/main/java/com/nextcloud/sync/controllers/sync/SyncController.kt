package com.nextcloud.sync.controllers.sync

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import com.nextcloud.sync.utils.SafeLogger
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.entities.ConflictEntity
import com.nextcloud.sync.models.database.entities.FileEntity
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.ConflictRepository
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.DocumentFileHelper
import com.nextcloud.sync.utils.FileHashUtil
import com.nextcloud.sync.utils.PathValidator
import java.io.File

class SyncController(
    private val context: Context,
    private val fileRepository: FileRepository,
    private val folderRepository: FolderRepository,
    private val conflictRepository: ConflictRepository,
    private val webDavClient: WebDavClient
) {
    companion object {
        // SECURITY: Maximum file size to prevent storage exhaustion (10 GB)
        private const val MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024 * 1024
    }

    interface SyncCallback {
        fun onSyncStarted(folderId: Long)
        fun onSyncProgress(current: Int, total: Int)
        fun onSyncComplete(stats: SyncStats)
        fun onSyncError(error: String)
        fun onConflictDetected(conflictId: Long)
    }

    suspend fun syncFolder(
        folderId: Long,
        callback: SyncCallback
    ) {
        callback.onSyncStarted(folderId)

        try {
            val folder = folderRepository.getFolderById(folderId)
                ?: throw IllegalArgumentException("Folder not found")

            // Phase 1: Scan local files
            val localFiles = scanLocalFiles(folder)
            folderRepository.updateLastLocalScan(folderId, System.currentTimeMillis())

            // Phase 2: Scan remote files
            val remoteFiles = scanRemoteFiles(folder)
            folderRepository.updateLastRemoteScan(folderId, System.currentTimeMillis())

            // Phase 3: Detect changes and conflicts
            val syncPlan = createSyncPlan(localFiles, remoteFiles, folderId)

            // Phase 4: Execute sync plan
            val stats = executeSyncPlan(syncPlan, folder, callback)

            callback.onSyncComplete(stats)
        } catch (e: Exception) {
            SafeLogger.e("SyncController", "Sync failed", e)
            callback.onSyncError("Sync failed: ${e.message}")
        }
    }

    private suspend fun scanLocalFiles(folder: FolderEntity): List<LocalFileInfo> {
        val localFiles = mutableListOf<LocalFileInfo>()
        val localPath = folder.localPath

        // Check if this is a content URI or a regular file path
        if (localPath.startsWith("content://")) {
            // Use DocumentFile API for content URIs
            val documentHelper = DocumentFileHelper(context)
            val rootDoc = documentHelper.getDocumentFile(localPath)

            if (rootDoc == null) {
                SafeLogger.e("SyncController", "Failed to access DocumentFile at: $localPath")
                return emptyList()
            }

            val documentFiles = documentHelper.listAllFiles(rootDoc)
            documentFiles.forEach { docFile ->
                try {
                    // Calculate hash from content URI
                    val hash = documentHelper.openInputStream(docFile.uri)?.use { inputStream ->
                        FileHashUtil.calculateHash(inputStream)
                    } ?: ""

                    localFiles.add(
                        LocalFileInfo(
                            path = docFile.uri.toString(),
                            relativePath = docFile.relativePath,
                            size = docFile.size,
                            modified = docFile.lastModified,
                            hash = hash
                        )
                    )
                } catch (e: Exception) {
                    SafeLogger.e("SyncController", "Failed to scan DocumentFile: ${docFile.name}", e)
                }
            }
        } else {
            // Use regular File API for filesystem paths
            val localDir = File(localPath)

            if (!localDir.exists()) {
                localDir.mkdirs()
                return emptyList()
            }

            localDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    try {
                        localFiles.add(
                            LocalFileInfo(
                                path = file.absolutePath,
                                relativePath = file.relativeTo(localDir).path,
                                size = file.length(),
                                modified = file.lastModified(),
                                hash = FileHashUtil.calculateHash(file)
                            )
                        )
                    } catch (e: Exception) {
                        SafeLogger.e("SyncController", "Failed to scan file: ${file.name}", e)
                    }
                }
            }
        }

        return localFiles
    }

    private suspend fun scanRemoteFiles(folder: FolderEntity): List<RemoteFileInfo> {
        return try {
            webDavClient.listFiles(folder.remotePath).map { davResource ->
                RemoteFileInfo(
                    path = davResource.path,
                    size = davResource.contentLength,
                    modified = davResource.modified.time,
                    etag = davResource.etag,
                    hash = davResource.etag // Use ETag as hash identifier
                )
            }
        } catch (e: Exception) {
            SafeLogger.e("SyncController", "Failed to scan remote files", e)
            emptyList()
        }
    }

    private suspend fun createSyncPlan(
        localFiles: List<LocalFileInfo>,
        remoteFiles: List<RemoteFileInfo>,
        folderId: Long
    ): SyncPlan {
        val dbFiles = fileRepository.getFilesByFolder(folderId)
        val dbFileMap = dbFiles.associateBy { it.localPath }

        val toUpload = mutableListOf<LocalFileInfo>()
        val toDownload = mutableListOf<RemoteFileInfo>()
        val conflicts = mutableListOf<ConflictInfo>()
        val noChange = mutableListOf<FileEntity>()

        // Check local files
        localFiles.forEach { localFile ->
            val dbFile = dbFileMap[localFile.path]
            val remoteFile = remoteFiles.find {
                it.path.endsWith("/" + localFile.relativePath) ||
                it.path.endsWith(localFile.relativePath)
            }

            when {
                dbFile == null && remoteFile == null -> {
                    // New local file, needs upload
                    toUpload.add(localFile)
                }

                dbFile != null && remoteFile == null -> {
                    // File deleted remotely
                    if (localFile.hash != dbFile.localHash) {
                        // Local file changed, conflict!
                        conflicts.add(ConflictInfo(localFile, null, dbFile))
                    } else {
                        // Safe to delete locally (not implemented - would delete local file)
                        fileRepository.delete(dbFile)
                    }
                }

                dbFile != null && remoteFile != null -> {
                    val localChanged = localFile.hash != dbFile.localHash
                    val remoteChanged = remoteFile.etag != dbFile.etag

                    when {
                        !localChanged && !remoteChanged -> {
                            // No changes
                            noChange.add(dbFile)
                        }
                        localChanged && !remoteChanged -> {
                            // Only local changed, upload
                            toUpload.add(localFile)
                        }
                        !localChanged && remoteChanged -> {
                            // Only remote changed, download
                            toDownload.add(remoteFile)
                        }
                        localChanged && remoteChanged -> {
                            // Both changed, conflict!
                            conflicts.add(ConflictInfo(localFile, remoteFile, dbFile))
                        }
                    }
                }

                dbFile == null && remoteFile != null -> {
                    // New remote file, needs download
                    toDownload.add(remoteFile)
                }
            }
        }

        // Check for remote-only files (not in local scan)
        remoteFiles.forEach { remoteFile ->
            val fileName = remoteFile.path.substringAfterLast('/')
            val hasLocal = localFiles.any { it.relativePath == fileName }
            val hasDb = dbFiles.any { it.remotePath == remoteFile.path }

            if (!hasLocal && !hasDb) {
                toDownload.add(remoteFile)
            }
        }

        return SyncPlan(toUpload, toDownload, conflicts, noChange)
    }

    private suspend fun executeSyncPlan(
        plan: SyncPlan,
        folder: FolderEntity,
        callback: SyncCallback
    ): SyncStats {
        var uploaded = 0
        var downloaded = 0
        var conflictsDetected = 0
        val total = plan.toUpload.size + plan.toDownload.size
        var current = 0

        // Handle uploads
        plan.toUpload.forEach { localFile ->
            try {
                // Validate and sanitize the relative path
                val sanitizedRelativePath = PathValidator.validateRelativePath(localFile.relativePath)
                if (sanitizedRelativePath == null) {
                    SafeLogger.w("SyncController", "Skipping upload - invalid relative path: ${localFile.relativePath}")
                    current++
                    callback.onSyncProgress(current, total)
                    return@forEach
                }

                val remotePath = "${folder.remotePath}/$sanitizedRelativePath"
                val success: Boolean

                // Check if localPath is a content URI or file path
                if (localFile.path.startsWith("content://")) {
                    // Upload from content URI
                    val documentHelper = DocumentFileHelper(context)
                    val inputStream = documentHelper.openInputStream(Uri.parse(localFile.path))

                    success = if (inputStream != null) {
                        inputStream.use { stream ->
                            webDavClient.uploadFile(stream, remotePath, localFile.size)
                        }
                    } else {
                        SafeLogger.e("SyncController", "Failed to open input stream for ${localFile.path}")
                        false
                    }
                } else {
                    // Upload from file path
                    success = webDavClient.uploadFile(File(localFile.path), remotePath)
                }

                if (success) {
                    // Update database
                    fileRepository.upsertFile(
                        FileEntity(
                            folderId = folder.id,
                            localPath = localFile.path,
                            remotePath = remotePath,
                            fileName = localFile.relativePath.substringAfterLast('/'),
                            fileSize = localFile.size,
                            mimeType = getMimeType(localFile.path),
                            localHash = localFile.hash,
                            remoteHash = localFile.hash,
                            localModified = localFile.modified,
                            remoteModified = System.currentTimeMillis(),
                            syncStatus = SyncStatus.SYNCED,
                            lastSync = System.currentTimeMillis(),
                            etag = null // Will be updated on next scan
                        )
                    )

                    uploaded++
                }

                current++
                callback.onSyncProgress(current, total)
            } catch (e: Exception) {
                SafeLogger.e("SyncController", "Upload failed for ${localFile.path}", e)
            }
        }

        // Handle downloads
        plan.toDownload.forEach { remoteFile ->
            try {
                // SECURITY: Validate file size before downloading to prevent storage exhaustion
                if (remoteFile.size > MAX_FILE_SIZE_BYTES) {
                    SafeLogger.w("SyncController", "Skipping download - file too large: ${remoteFile.size} bytes (max: $MAX_FILE_SIZE_BYTES)")
                    current++
                    callback.onSyncProgress(current, total)
                    return@forEach
                }

                // SECURITY: Check available storage space
                val availableSpace = getAvailableStorageSpace(folder.localPath)
                if (remoteFile.size > availableSpace) {
                    SafeLogger.w("SyncController", "Skipping download - insufficient storage: ${remoteFile.size} bytes required, $availableSpace bytes available")
                    current++
                    callback.onSyncProgress(current, total)
                    return@forEach
                }

                // Extract and validate file name from remote path
                val fileName = PathValidator.extractFileName(remoteFile.path)
                if (fileName == null) {
                    SafeLogger.w("SyncController", "Skipping download - invalid file name: ${remoteFile.path}")
                    current++
                    callback.onSyncProgress(current, total)
                    return@forEach
                }

                // Extract and validate relative path
                val relativePath = PathValidator.extractRelativePath(remoteFile.path, folder.remotePath)
                if (relativePath == null) {
                    SafeLogger.w("SyncController", "Skipping download - invalid relative path: ${remoteFile.path}")
                    current++
                    callback.onSyncProgress(current, total)
                    return@forEach
                }

                var success = false
                var finalLocalPath = ""
                var lastModified = 0L

                // Check if folder path is a content URI or file path
                if (folder.localPath.startsWith("content://")) {
                    // Download to content URI
                    // SECURITY: Validate path for traversal attempts
                    if (!isValidContentUriPath(relativePath)) {
                        SafeLogger.w("SyncController", "Skipping download - path traversal detected in content URI path: $relativePath")
                        current++
                        callback.onSyncProgress(current, total)
                        return@forEach
                    }

                    val documentHelper = DocumentFileHelper(context)
                    val rootDoc = documentHelper.getDocumentFile(folder.localPath)

                    if (rootDoc != null) {
                        // Ensure directory structure exists
                        // relativePath is validated against path traversal
                        val pathParts = relativePath.split("/").filter { it.isNotEmpty() }
                        val dirPath = pathParts.dropLast(1).joinToString("/")
                        val targetDir = if (dirPath.isEmpty()) {
                            rootDoc
                        } else {
                            documentHelper.ensureDirectoryPath(rootDoc, dirPath)
                        }

                        if (targetDir != null) {
                            // Create or get the file
                            val existingFile = targetDir.listFiles().find { it.name == fileName }
                            val targetFile = existingFile ?: documentHelper.createFile(
                                targetDir,
                                fileName,
                                "application/octet-stream"
                            )

                            if (targetFile != null) {
                                val outputStream = documentHelper.openOutputStream(targetFile.uri)
                                if (outputStream != null) {
                                    val inputStream = webDavClient.getFileStream(remoteFile.path)
                                    if (inputStream != null) {
                                        outputStream.use { output ->
                                            inputStream.use { input ->
                                                input.copyTo(output)
                                            }
                                        }
                                        success = true
                                        finalLocalPath = targetFile.uri.toString()
                                        lastModified = targetFile.lastModified()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Download to file path
                    // Validate that the final path stays within the sync folder
                    val validatedPath = PathValidator.validatePathWithinRoot(folder.localPath, relativePath)
                    if (validatedPath == null) {
                        SafeLogger.w("SyncController", "Skipping download - path escapes sync folder: $relativePath")
                        current++
                        callback.onSyncProgress(current, total)
                        return@forEach
                    }

                    // Ensure parent directories exist
                    val targetFile = File(validatedPath)
                    targetFile.parentFile?.mkdirs()

                    success = webDavClient.downloadFile(remoteFile.path, targetFile)
                    if (success) {
                        finalLocalPath = validatedPath
                        lastModified = targetFile.lastModified()
                    }
                }

                if (success) {
                    // Calculate hash
                    val localHash = if (finalLocalPath.startsWith("content://")) {
                        val documentHelper = DocumentFileHelper(context)
                        documentHelper.openInputStream(Uri.parse(finalLocalPath))?.use { stream ->
                            FileHashUtil.calculateHash(stream)
                        } ?: ""
                    } else {
                        FileHashUtil.calculateHash(File(finalLocalPath))
                    }

                    // Update database
                    fileRepository.upsertFile(
                        FileEntity(
                            folderId = folder.id,
                            localPath = finalLocalPath,
                            remotePath = remoteFile.path,
                            fileName = fileName,
                            fileSize = remoteFile.size,
                            mimeType = getMimeType(fileName),
                            localHash = localHash,
                            remoteHash = remoteFile.hash,
                            localModified = lastModified,
                            remoteModified = remoteFile.modified,
                            syncStatus = SyncStatus.SYNCED,
                            lastSync = System.currentTimeMillis(),
                            etag = remoteFile.etag
                        )
                    )

                    downloaded++
                }

                current++
                callback.onSyncProgress(current, total)
            } catch (e: Exception) {
                SafeLogger.e("SyncController", "Download failed for ${remoteFile.path}", e)
            }
        }

        // Handle conflicts
        plan.conflicts.forEach { conflict ->
            val conflictEntity = ConflictEntity(
                fileId = conflict.dbFile.id,
                localVersionPath = conflict.localFile?.path ?: conflict.dbFile.localPath,
                localModified = conflict.localFile?.modified ?: conflict.dbFile.localModified ?: 0L,
                localSize = conflict.localFile?.size ?: conflict.dbFile.fileSize,
                localHash = conflict.localFile?.hash ?: conflict.dbFile.localHash ?: "",
                remoteModified = conflict.remoteFile?.modified ?: conflict.dbFile.remoteModified ?: 0L,
                remoteSize = conflict.remoteFile?.size ?: conflict.dbFile.fileSize,
                remoteHash = conflict.remoteFile?.hash ?: conflict.dbFile.remoteHash ?: ""
            )

            val conflictId = conflictRepository.createConflict(conflictEntity)
            callback.onConflictDetected(conflictId)
            conflictsDetected++
        }

        return SyncStats(uploaded, downloaded, conflictsDetected)
    }

    /**
     * Gets available storage space for a given path (file path or content URI).
     *
     * @param path The local path (file path or content URI)
     * @return Available space in bytes
     */
    private fun getAvailableStorageSpace(path: String): Long {
        return try {
            if (path.startsWith("content://")) {
                // For content URIs, get available space from storage stats
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    val uuid = storageManager.getUuidForPath(File(context.filesDir.absolutePath))
                    storageManager.getAllocatableBytes(uuid)
                } else {
                    // Fallback for older versions - use internal storage
                    context.filesDir.usableSpace
                }
            } else {
                // For file paths, use the file system's usable space
                File(path).usableSpace
            }
        } catch (e: Exception) {
            SafeLogger.e("SyncController", "Failed to get available storage space", e)
            // Return a conservative estimate (1 GB) if we can't determine actual space
            1L * 1024 * 1024 * 1024
        }
    }

    /**
     * Validates a relative path for use with Content URIs to prevent path traversal attacks.
     *
     * Content URIs use the DocumentFile API which doesn't support File.canonicalPath,
     * so we need to manually validate the path doesn't contain traversal attempts.
     *
     * @param relativePath The relative path to validate
     * @return true if the path is safe to use, false if it contains traversal attempts
     */
    private fun isValidContentUriPath(relativePath: String): Boolean {
        // Reject paths that try to escape via parent directory references
        if (relativePath.contains("..")) {
            return false
        }

        // Reject absolute paths (should always be relative)
        if (relativePath.startsWith("/")) {
            return false
        }

        // Normalize path and check for traversal in path segments
        val normalizedPath = relativePath.replace("\\", "/")
        val segments = normalizedPath.split("/")

        // Check each segment - none should be ".." or empty (except trailing)
        for (i in 0 until segments.size - 1) {
            val segment = segments[i]
            if (segment == ".." || segment.isEmpty()) {
                return false
            }
        }

        // Last segment (filename) can't be empty either
        if (segments.isNotEmpty() && segments.last().isEmpty()) {
            return false
        }

        return true
    }

    private fun getMimeType(path: String): String? {
        val extension = path.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
    }
}
