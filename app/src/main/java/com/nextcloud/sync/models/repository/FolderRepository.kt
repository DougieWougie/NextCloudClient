package com.nextcloud.sync.models.repository

import com.nextcloud.sync.models.database.dao.FolderDao
import com.nextcloud.sync.models.database.entities.FolderEntity

class FolderRepository(private val folderDao: FolderDao) {
    suspend fun getFoldersByAccount(accountId: Long): List<FolderEntity> {
        return folderDao.getFoldersByAccount(accountId)
    }

    suspend fun getSyncEnabledFolders(): List<FolderEntity> {
        return folderDao.getSyncEnabledFolders()
    }

    suspend fun getFolderById(folderId: Long): FolderEntity? {
        return folderDao.getFolderById(folderId)
    }

    suspend fun insert(folder: FolderEntity): Long {
        return folderDao.insert(folder)
    }

    suspend fun update(folder: FolderEntity) {
        folderDao.update(folder)
    }

    suspend fun delete(folder: FolderEntity) {
        folderDao.delete(folder)
    }

    suspend fun updateLastLocalScan(folderId: Long, timestamp: Long) {
        folderDao.updateLastLocalScan(folderId, timestamp)
    }

    suspend fun updateLastRemoteScan(folderId: Long, timestamp: Long) {
        folderDao.updateLastRemoteScan(folderId, timestamp)
    }

    suspend fun getFolderByRemotePath(accountId: Long, remotePath: String): FolderEntity? {
        return folderDao.getFolderByRemotePath(accountId, remotePath)
    }

    suspend fun getFolderByLocalPath(accountId: Long, localPath: String): FolderEntity? {
        return folderDao.getFolderByLocalPath(accountId, localPath)
    }
}
