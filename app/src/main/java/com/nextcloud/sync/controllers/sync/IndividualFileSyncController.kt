package com.nextcloud.sync.controllers.sync

import android.content.Context
import com.nextcloud.sync.models.repository.IndividualFileSyncRepository
import com.nextcloud.sync.models.database.entities.IndividualFileSyncEntity
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.utils.SafeLogger
import java.io.File

/**
 * Controller for managing individual file sync operations.
 *
 * Coordinates with SyncController to sync individual files that have been
 * added to sync configuration, independent of folder-based sync.
 *
 * @property individualFileSyncRepository Repository for individual file sync
 * @property context Application context
 */
class IndividualFileSyncController(
    private val individualFileSyncRepository: IndividualFileSyncRepository,
    private val context: Context
) {

    /**
     * Enable sync for a new individual file.
     *
     * @param remotePath Remote WebDAV path
     * @param localPath Local file path (content URI or file path)
     * @param fileName File name
     * @param accountId Account ID
     * @param wifiOnly Whether to restrict to WiFi
     * @return ID of created sync entity, or 0 if failed
     */
    suspend fun enableFileSync(
        remotePath: String,
        localPath: String,
        fileName: String,
        accountId: Long,
        wifiOnly: Boolean = false
    ): Long {
        return try {
            // Check if already being synced
            val existing = individualFileSyncRepository.getByRemotePath(remotePath, accountId)
            if (existing != null) {
                SafeLogger.d("IndividualFileSyncController", "File already in sync: $remotePath")
                return existing.id
            }

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

            individualFileSyncRepository.insert(entity)
        } catch (e: Exception) {
            SafeLogger.e("IndividualFileSyncController", "Failed to enable file sync", e)
            0
        }
    }

    /**
     * Disable sync for an individual file.
     * This removes the file from sync configuration but does not delete the actual file.
     *
     * @param fileId File sync entity ID
     * @return True if successful, false otherwise
     */
    suspend fun disableFileSync(fileId: Long): Boolean {
        return try {
            individualFileSyncRepository.deleteById(fileId)
            true
        } catch (e: Exception) {
            SafeLogger.e("IndividualFileSyncController", "Failed to disable file sync", e)
            false
        }
    }

    /**
     * Get all sync-enabled files for an account.
     *
     * @param accountId Account ID
     * @return List of sync-enabled individual files
     */
    suspend fun getSyncEnabledFiles(accountId: Long): List<IndividualFileSyncEntity> {
        return individualFileSyncRepository.getSyncEnabledFiles(accountId)
    }

    /**
     * Get all files (enabled and disabled) for an account.
     *
     * @param accountId Account ID
     * @return List of all individual file sync entities
     */
    suspend fun getAllFiles(accountId: Long): List<IndividualFileSyncEntity> {
        return individualFileSyncRepository.getAllFiles(accountId)
    }

    /**
     * Sync a single individual file.
     * This downloads the remote file to the configured local path.
     *
     * @param fileId File sync entity ID
     * @param webDavClient WebDAV client for the account
     * @return True if successful, false otherwise
     */
    suspend fun syncFile(fileId: Long, webDavClient: WebDavClient): Boolean {
        return try {
            val fileSyncEntity = individualFileSyncRepository.getById(fileId) ?: run {
                SafeLogger.e("IndividualFileSyncController", "File sync entity not found: $fileId")
                return false
            }

            if (!fileSyncEntity.syncEnabled) {
                SafeLogger.d("IndividualFileSyncController", "File sync disabled, skipping: ${fileSyncEntity.remotePath}")
                return false
            }

            // Download file from remote to local
            val localPath = fileSyncEntity.localPath
            val remotePath = fileSyncEntity.remotePath

            val success = if (localPath.startsWith("content://")) {
                // For content URIs, need to handle differently
                // This is a simplified implementation - production would need more robust handling
                SafeLogger.w("IndividualFileSyncController", "Content URI sync not fully implemented yet")
                false
            } else {
                // Download to file path
                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()

                webDavClient.downloadFile(remotePath, localFile)
            }

            if (success) {
                // Update last sync timestamp
                individualFileSyncRepository.updateLastSync(fileId, System.currentTimeMillis())
                SafeLogger.d("IndividualFileSyncController", "File synced successfully: $remotePath")
            }

            success
        } catch (e: Exception) {
            SafeLogger.e("IndividualFileSyncController", "Failed to sync file", e)
            false
        }
    }

    /**
     * Toggle sync enabled status for a file.
     *
     * @param fileId File sync entity ID
     * @param enabled New enabled status
     * @return True if successful, false otherwise
     */
    suspend fun toggleSyncEnabled(fileId: Long, enabled: Boolean): Boolean {
        return try {
            individualFileSyncRepository.updateSyncEnabled(fileId, enabled)
            true
        } catch (e: Exception) {
            SafeLogger.e("IndividualFileSyncController", "Failed to toggle sync enabled", e)
            false
        }
    }

    /**
     * Get file sync entity by ID.
     *
     * @param fileId File sync entity ID
     * @return File sync entity or null if not found
     */
    suspend fun getFileById(fileId: Long): IndividualFileSyncEntity? {
        return individualFileSyncRepository.getById(fileId)
    }

    /**
     * Update file sync configuration.
     *
     * @param fileId File sync entity ID
     * @param wifiOnly New WiFi-only setting
     * @param autoSync New auto-sync setting
     * @return True if successful, false otherwise
     */
    suspend fun updateFileSyncConfig(fileId: Long, wifiOnly: Boolean, autoSync: Boolean): Boolean {
        return try {
            val entity = individualFileSyncRepository.getById(fileId) ?: return false

            val updated = entity.copy(
                wifiOnly = wifiOnly,
                autoSync = autoSync
            )

            individualFileSyncRepository.update(updated)
            true
        } catch (e: Exception) {
            SafeLogger.e("IndividualFileSyncController", "Failed to update file sync config", e)
            false
        }
    }
}
