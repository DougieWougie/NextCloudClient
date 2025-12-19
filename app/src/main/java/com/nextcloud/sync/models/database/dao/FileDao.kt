package com.nextcloud.sync.models.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.entities.FileEntity

@Dao
interface FileDao {
    @Query("SELECT * FROM files WHERE folder_id = :folderId")
    suspend fun getFilesByFolder(folderId: Long): List<FileEntity>

    @Query("SELECT * FROM files WHERE sync_status = :status")
    suspend fun getFilesByStatus(status: SyncStatus): List<FileEntity>

    @Query("SELECT * FROM files WHERE id = :fileId")
    suspend fun getFileById(fileId: Long): FileEntity?

    @Query("SELECT * FROM files WHERE local_path = :localPath")
    suspend fun getFileByLocalPath(localPath: String): FileEntity?

    @Query("SELECT * FROM files WHERE remote_path = :remotePath")
    suspend fun getFileByRemotePath(remotePath: String): FileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: FileEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<FileEntity>)

    @Update
    suspend fun update(file: FileEntity)

    @Delete
    suspend fun delete(file: FileEntity)

    @Query("UPDATE files SET sync_status = :status WHERE id = :fileId")
    suspend fun updateSyncStatus(fileId: Long, status: SyncStatus)

    @Query("SELECT COUNT(*) FROM files WHERE sync_status = :status")
    suspend fun countFilesByStatus(status: SyncStatus): Int
}
