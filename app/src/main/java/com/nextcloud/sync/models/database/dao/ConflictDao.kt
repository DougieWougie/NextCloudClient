package com.nextcloud.sync.models.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.database.entities.ConflictEntity

@Dao
interface ConflictDao {
    @Query("SELECT * FROM conflicts WHERE resolution_status = 'PENDING'")
    suspend fun getPendingConflicts(): List<ConflictEntity>

    @Query("SELECT * FROM conflicts WHERE file_id = :fileId AND resolution_status = 'PENDING'")
    suspend fun getPendingConflictForFile(fileId: Long): ConflictEntity?

    @Query("SELECT * FROM conflicts WHERE id = :conflictId")
    suspend fun getConflictById(conflictId: Long): ConflictEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conflict: ConflictEntity): Long

    @Update
    suspend fun update(conflict: ConflictEntity)

    @Query("UPDATE conflicts SET resolution_status = :status, resolved_at = :timestamp WHERE id = :conflictId")
    suspend fun resolveConflict(conflictId: Long, status: ConflictResolution, timestamp: Long)

    @Query("DELETE FROM conflicts WHERE resolution_status != 'PENDING' AND resolved_at < :cutoffTime")
    suspend fun deleteOldResolvedConflicts(cutoffTime: Long)
}
