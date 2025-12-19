package com.nextcloud.sync.controllers.sync

import android.util.Log
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.entities.ConflictEntity
import com.nextcloud.sync.models.database.entities.FileEntity
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.ConflictRepository
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.models.repository.FolderRepository
import com.nextcloud.sync.utils.FileHashUtil
import java.io.File

class SyncController(
    private val fileRepository: FileRepository,
    private val folderRepository: FolderRepository,
    private val conflictRepository: ConflictRepository,
    private val webDavClient: WebDavClient
) {
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
            Log.e("SyncController", "Sync failed", e)
            callback.onSyncError("Sync failed: ${e.message}")
        }
    }

    private suspend fun scanLocalFiles(folder: FolderEntity): List<LocalFileInfo> {
        val localFiles = mutableListOf<LocalFileInfo>()
        val localDir = File(folder.localPath)

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
                    Log.e("SyncController", "Failed to scan file: ${file.name}", e)
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
            Log.e("SyncController", "Failed to scan remote files", e)
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
                val remotePath = "${folder.remotePath}/${localFile.relativePath}"
                val success = webDavClient.uploadFile(File(localFile.path), remotePath)

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
                Log.e("SyncController", "Upload failed for ${localFile.path}", e)
            }
        }

        // Handle downloads
        plan.toDownload.forEach { remoteFile ->
            try {
                val fileName = remoteFile.path.substringAfterLast('/')
                val localPath = "${folder.localPath}/$fileName"
                val success = webDavClient.downloadFile(remoteFile.path, File(localPath))

                if (success) {
                    val localHash = FileHashUtil.calculateHash(File(localPath))

                    // Update database
                    fileRepository.upsertFile(
                        FileEntity(
                            folderId = folder.id,
                            localPath = localPath,
                            remotePath = remoteFile.path,
                            fileName = fileName,
                            fileSize = remoteFile.size,
                            mimeType = getMimeType(localPath),
                            localHash = localHash,
                            remoteHash = remoteFile.hash,
                            localModified = File(localPath).lastModified(),
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
                Log.e("SyncController", "Download failed for ${remoteFile.path}", e)
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

    private fun getMimeType(path: String): String? {
        val extension = path.substringAfterLast('.', "")
        return android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
    }
}
