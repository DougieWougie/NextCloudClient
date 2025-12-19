package com.nextcloud.sync.models.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nextcloud.sync.models.database.entities.FolderEntity

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders WHERE account_id = :accountId")
    suspend fun getFoldersByAccount(accountId: Long): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE sync_enabled = 1")
    suspend fun getSyncEnabledFolders(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :folderId")
    suspend fun getFolderById(folderId: Long): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("UPDATE folders SET last_local_scan = :timestamp WHERE id = :folderId")
    suspend fun updateLastLocalScan(folderId: Long, timestamp: Long)

    @Query("UPDATE folders SET last_remote_scan = :timestamp WHERE id = :folderId")
    suspend fun updateLastRemoteScan(folderId: Long, timestamp: Long)
}
