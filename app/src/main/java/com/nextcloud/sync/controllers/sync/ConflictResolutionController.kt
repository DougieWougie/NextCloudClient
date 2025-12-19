package com.nextcloud.sync.controllers.sync

import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.entities.ConflictEntity
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.ConflictRepository
import com.nextcloud.sync.models.repository.FileRepository
import com.nextcloud.sync.utils.FileHashUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConflictResolutionController(
    private val conflictRepository: ConflictRepository,
    private val fileRepository: FileRepository,
    private val webDavClient: WebDavClient
) {
    interface ConflictResolutionCallback {
        fun onResolutionSuccess()
        fun onResolutionError(error: String)
    }

    suspend fun resolveConflict(
        conflictId: Long,
        resolution: ConflictResolution,
        callback: ConflictResolutionCallback
    ) {
        try {
            val conflict = conflictRepository.getConflictById(conflictId)
                ?: throw IllegalArgumentException("Conflict not found")

            val file = fileRepository.getFileById(conflict.fileId)
                ?: throw IllegalArgumentException("File not found")

            when (resolution) {
                ConflictResolution.KEEP_LOCAL -> {
                    // Upload local version
                    val success = webDavClient.uploadFile(
                        File(conflict.localVersionPath),
                        file.remotePath
                    )

                    if (success) {
                        // Update file record
                        fileRepository.update(
                            file.copy(
                                remoteHash = conflict.localHash,
                                remoteModified = conflict.localModified,
                                syncStatus = SyncStatus.SYNCED,
                                lastSync = System.currentTimeMillis()
                            )
                        )
                    } else {
                        throw Exception("Failed to upload local version")
                    }
                }

                ConflictResolution.KEEP_REMOTE -> {
                    // Download remote version
                    val success = webDavClient.downloadFile(
                        file.remotePath,
                        File(file.localPath)
                    )

                    if (success) {
                        // Update file record
                        val newHash = FileHashUtil.calculateHash(File(file.localPath))
                        fileRepository.update(
                            file.copy(
                                localHash = newHash,
                                localModified = File(file.localPath).lastModified(),
                                syncStatus = SyncStatus.SYNCED,
                                lastSync = System.currentTimeMillis()
                            )
                        )
                    } else {
                        throw Exception("Failed to download remote version")
                    }
                }

                ConflictResolution.KEEP_BOTH -> {
                    // Rename local file and upload
                    val localFile = File(file.localPath)
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(Date())
                    val extension = localFile.extension
                    val nameWithoutExt = localFile.nameWithoutExtension
                    val newName = if (extension.isNotEmpty()) {
                        "${nameWithoutExt}_conflict_$timestamp.$extension"
                    } else {
                        "${nameWithoutExt}_conflict_$timestamp"
                    }
                    val newLocalPath = "${localFile.parent}/$newName"

                    localFile.renameTo(File(newLocalPath))

                    // Upload renamed file
                    val newRemotePath = "${file.remotePath.substringBeforeLast('/')}/$newName"
                    val uploadSuccess = webDavClient.uploadFile(File(newLocalPath), newRemotePath)

                    if (uploadSuccess) {
                        // Create new file record
                        fileRepository.insert(
                            file.copy(
                                id = 0,
                                localPath = newLocalPath,
                                remotePath = newRemotePath,
                                fileName = newName,
                                syncStatus = SyncStatus.SYNCED
                            )
                        )

                        // Download original remote version
                        val downloadSuccess = webDavClient.downloadFile(file.remotePath, File(file.localPath))

                        if (downloadSuccess) {
                            // Update original file record
                            val newHash = FileHashUtil.calculateHash(File(file.localPath))
                            fileRepository.update(
                                file.copy(
                                    localHash = newHash,
                                    syncStatus = SyncStatus.SYNCED,
                                    lastSync = System.currentTimeMillis()
                                )
                            )
                        } else {
                            throw Exception("Failed to download remote version")
                        }
                    } else {
                        throw Exception("Failed to upload renamed local version")
                    }
                }

                else -> {
                    throw IllegalArgumentException("Invalid resolution type")
                }
            }

            // Mark conflict as resolved
            conflictRepository.resolveConflict(
                conflictId,
                resolution,
                System.currentTimeMillis()
            )

            callback.onResolutionSuccess()
        } catch (e: Exception) {
            callback.onResolutionError("Resolution failed: ${e.message}")
        }
    }

    suspend fun getPendingConflicts(): List<ConflictEntity> {
        return conflictRepository.getPendingConflicts()
    }
}
