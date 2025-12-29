package com.nextcloud.sync.models.repository

import com.nextcloud.sync.models.database.dao.IndividualFileSyncDao
import com.nextcloud.sync.models.database.entities.IndividualFileSyncEntity

/**
 * Repository for individual file sync operations.
 *
 * Provides a clean API for managing individually synced files, abstracting
 * direct database access. Follows the repository pattern consistent with
 * other repositories in the app (AccountRepository, FolderRepository, etc.).
 *
 * All methods are suspend functions for coroutine support.
 *
 * @property dao Data access object for database operations
 */
class IndividualFileSyncRepository(private val dao: IndividualFileSyncDao) {

    /**
     * Get all sync-enabled files for an account.
     * Used by background sync worker to determine which files to sync.
     *
     * @param accountId Account ID to query
     * @return List of sync-enabled files
     */
    suspend fun getSyncEnabledFiles(accountId: Long): List<IndividualFileSyncEntity> {
        return dao.getSyncEnabledFiles(accountId)
    }

    /**
     * Get all files for an account (enabled and disabled).
     * Used by UI to display all individually synced files.
     *
     * @param accountId Account ID to query
     * @return List of all files for account
     */
    suspend fun getAllFiles(accountId: Long): List<IndividualFileSyncEntity> {
        return dao.getAllFiles(accountId)
    }

    /**
     * Get file by ID.
     *
     * @param id File ID
     * @return File entity or null if not found
     */
    suspend fun getById(id: Long): IndividualFileSyncEntity? {
        return dao.getById(id)
    }

    /**
     * Get file by remote path.
     * Used to check if a file is already in sync configuration before adding.
     *
     * @param remotePath Remote WebDAV path
     * @param accountId Account ID
     * @return File entity or null if not found
     */
    suspend fun getByRemotePath(remotePath: String, accountId: Long): IndividualFileSyncEntity? {
        return dao.getByRemotePath(remotePath, accountId)
    }

    /**
     * Insert new file sync configuration.
     *
     * @param file File entity to insert
     * @return ID of inserted file
     */
    suspend fun insert(file: IndividualFileSyncEntity): Long {
        return dao.insert(file)
    }

    /**
     * Update existing file sync configuration.
     *
     * @param file File entity to update
     */
    suspend fun update(file: IndividualFileSyncEntity) {
        dao.update(file)
    }

    /**
     * Delete file sync configuration.
     * This removes the file from sync but does not delete the actual file.
     *
     * @param file File entity to delete
     */
    suspend fun delete(file: IndividualFileSyncEntity) {
        dao.delete(file)
    }

    /**
     * Delete file by ID.
     *
     * @param id File ID to delete
     */
    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }

    /**
     * Update last sync timestamp for a file.
     *
     * @param id File ID
     * @param timestamp Sync timestamp in milliseconds
     */
    suspend fun updateLastSync(id: Long, timestamp: Long) {
        dao.updateLastSync(id, timestamp)
    }

    /**
     * Enable or disable sync for a file.
     *
     * @param id File ID
     * @param enabled Whether sync is enabled
     */
    suspend fun updateSyncEnabled(id: Long, enabled: Boolean) {
        dao.updateSyncEnabled(id, enabled)
    }

    /**
     * Count sync-enabled files for an account.
     * Used for statistics and UI display.
     *
     * @param accountId Account ID
     * @return Number of sync-enabled files
     */
    suspend fun countSyncEnabledFiles(accountId: Long): Int {
        return dao.countSyncEnabledFiles(accountId)
    }

    /**
     * Delete all files for an account.
     * Note: Cascade delete from account should handle this automatically,
     * but this method provides explicit control if needed.
     *
     * @param accountId Account ID
     */
    suspend fun deleteAllForAccount(accountId: Long) {
        dao.deleteAllForAccount(accountId)
    }

    /**
     * Check if a remote path is already being synced.
     * Convenient method for controllers to check before adding.
     *
     * @param remotePath Remote WebDAV path
     * @param accountId Account ID
     * @return True if file is already being synced
     */
    suspend fun isAlreadySynced(remotePath: String, accountId: Long): Boolean {
        return dao.getByRemotePath(remotePath, accountId) != null
    }
}
