package com.nextcloud.sync.models.repository

import com.nextcloud.sync.models.data.SyncStatus
import com.nextcloud.sync.models.database.dao.FileDao
import com.nextcloud.sync.models.database.entities.FileEntity

class FileRepository(private val fileDao: FileDao) {
    suspend fun getFilesByFolder(folderId: Long): List<FileEntity> {
        return fileDao.getFilesByFolder(folderId)
    }

    suspend fun getFilesByStatus(status: SyncStatus): List<FileEntity> {
        return fileDao.getFilesByStatus(status)
    }

    suspend fun getFileById(fileId: Long): FileEntity? {
        return fileDao.getFileById(fileId)
    }

    suspend fun getFileByLocalPath(localPath: String): FileEntity? {
        return fileDao.getFileByLocalPath(localPath)
    }

    suspend fun getFileByRemotePath(remotePath: String): FileEntity? {
        return fileDao.getFileByRemotePath(remotePath)
    }

    suspend fun insert(file: FileEntity): Long {
        return fileDao.insert(file)
    }

    suspend fun insertAll(files: List<FileEntity>) {
        fileDao.insertAll(files)
    }

    suspend fun update(file: FileEntity) {
        fileDao.update(file)
    }

    suspend fun delete(file: FileEntity) {
        fileDao.delete(file)
    }

    suspend fun updateSyncStatus(fileId: Long, status: SyncStatus) {
        fileDao.updateSyncStatus(fileId, status)
    }

    suspend fun countFilesByStatus(status: SyncStatus): Int {
        return fileDao.countFilesByStatus(status)
    }

    suspend fun upsertFile(file: FileEntity) {
        val existing = getFileByLocalPath(file.localPath)
        if (existing != null) {
            update(file.copy(id = existing.id))
        } else {
            insert(file)
        }
    }
}
