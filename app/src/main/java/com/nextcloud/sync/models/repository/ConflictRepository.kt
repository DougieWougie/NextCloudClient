package com.nextcloud.sync.models.repository

import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.database.dao.ConflictDao
import com.nextcloud.sync.models.database.entities.ConflictEntity

class ConflictRepository(private val conflictDao: ConflictDao) {
    suspend fun getPendingConflicts(): List<ConflictEntity> {
        return conflictDao.getPendingConflicts()
    }

    suspend fun getPendingConflictForFile(fileId: Long): ConflictEntity? {
        return conflictDao.getPendingConflictForFile(fileId)
    }

    suspend fun getConflictById(conflictId: Long): ConflictEntity? {
        return conflictDao.getConflictById(conflictId)
    }

    suspend fun createConflict(conflict: ConflictEntity): Long {
        return conflictDao.insert(conflict)
    }

    suspend fun update(conflict: ConflictEntity) {
        conflictDao.update(conflict)
    }

    suspend fun resolveConflict(conflictId: Long, resolution: ConflictResolution, timestamp: Long) {
        conflictDao.resolveConflict(conflictId, resolution, timestamp)
    }

    suspend fun deleteOldResolvedConflicts(cutoffTime: Long) {
        conflictDao.deleteOldResolvedConflicts(cutoffTime)
    }
}
