package com.nextcloud.sync.models.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nextcloud.sync.models.database.entities.IndividualFileSyncEntity

/**
 * Data Access Object for individual file sync operations.
 *
 * Provides CRUD operations for managing individually synced files.
 * All operations are suspend functions for coroutine support.
 *
 * Query Performance:
 * - Indexed on account_id, remote_path, sync_enabled for fast queries
 * - getSyncEnabledFiles() uses sync_enabled index for background sync worker
 * - getByRemotePath() uses remote_path index for conflict detection
 */
@Dao
interface IndividualFileSyncDao {

    /**
     * Get all sync-enabled files for an account.
     * Used by background sync worker to determine which files to sync.
     *
     * @param accountId Account ID to query
     * @return List of sync-enabled files
     */
    @Query("SELECT * FROM individual_file_sync WHERE account_id = :accountId AND sync_enabled = 1")
    suspend fun getSyncEnabledFiles(accountId: Long): List<IndividualFileSyncEntity>

    /**
     * Get all files for an account (enabled and disabled).
     * Used by UI to display all individually synced files.
     *
     * @param accountId Account ID to query
     * @return List of all files for account
     */
    @Query("SELECT * FROM individual_file_sync WHERE account_id = :accountId")
    suspend fun getAllFiles(accountId: Long): List<IndividualFileSyncEntity>

    /**
     * Get file by ID.
     *
     * @param id File ID
     * @return File entity or null if not found
     */
    @Query("SELECT * FROM individual_file_sync WHERE id = :id")
    suspend fun getById(id: Long): IndividualFileSyncEntity?

    /**
     * Get file by remote path.
     * Used to check if a file is already in sync configuration before adding.
     *
     * @param remotePath Remote WebDAV path
     * @param accountId Account ID
     * @return File entity or null if not found
     */
    @Query("SELECT * FROM individual_file_sync WHERE remote_path = :remotePath AND account_id = :accountId")
    suspend fun getByRemotePath(remotePath: String, accountId: Long): IndividualFileSyncEntity?

    /**
     * Insert new file sync configuration.
     *
     * @param file File entity to insert
     * @return ID of inserted file
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: IndividualFileSyncEntity): Long

    /**
     * Update existing file sync configuration.
     *
     * @param file File entity to update
     */
    @Update
    suspend fun update(file: IndividualFileSyncEntity)

    /**
     * Delete file sync configuration.
     * This removes the file from sync but does not delete the actual file.
     *
     * @param file File entity to delete
     */
    @Delete
    suspend fun delete(file: IndividualFileSyncEntity)

    /**
     * Delete file by ID.
     *
     * @param id File ID to delete
     */
    @Query("DELETE FROM individual_file_sync WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Update last sync timestamp for a file.
     *
     * @param id File ID
     * @param timestamp Sync timestamp in milliseconds
     */
    @Query("UPDATE individual_file_sync SET last_sync = :timestamp WHERE id = :id")
    suspend fun updateLastSync(id: Long, timestamp: Long)

    /**
     * Enable or disable sync for a file.
     *
     * @param id File ID
     * @param enabled Whether sync is enabled
     */
    @Query("UPDATE individual_file_sync SET sync_enabled = :enabled WHERE id = :id")
    suspend fun updateSyncEnabled(id: Long, enabled: Boolean)

    /**
     * Count sync-enabled files for an account.
     * Used for statistics and UI display.
     *
     * @param accountId Account ID
     * @return Number of sync-enabled files
     */
    @Query("SELECT COUNT(*) FROM individual_file_sync WHERE account_id = :accountId AND sync_enabled = 1")
    suspend fun countSyncEnabledFiles(accountId: Long): Int

    /**
     * Delete all files for an account.
     * Note: Cascade delete from account should handle this automatically,
     * but this method provides explicit control if needed.
     *
     * @param accountId Account ID
     */
    @Query("DELETE FROM individual_file_sync WHERE account_id = :accountId")
    suspend fun deleteAllForAccount(accountId: Long)
}
