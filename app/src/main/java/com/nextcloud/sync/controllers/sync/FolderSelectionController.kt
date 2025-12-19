package com.nextcloud.sync.controllers.sync

import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.models.network.WebDavClient
import com.nextcloud.sync.models.repository.AccountRepository
import com.nextcloud.sync.models.repository.FolderRepository
import java.io.File

class FolderSelectionController(
    private val folderRepository: FolderRepository,
    private val accountRepository: AccountRepository,
    private val webDavClient: WebDavClient,
    private val appSyncDirectory: String
) {
    interface FolderSelectionCallback {
        fun onFoldersLoaded(folders: List<RemoteFolderInfo>)
        fun onFolderAdded(folderId: Long)
        fun onError(error: String)
    }

    suspend fun loadRemoteFolders(callback: FolderSelectionCallback) {
        try {
            val folders = webDavClient.listFolders("/")
                .map { davResource ->
                    RemoteFolderInfo(
                        path = davResource.path,
                        name = davResource.name,
                        modified = davResource.modified.time
                    )
                }

            callback.onFoldersLoaded(folders)
        } catch (e: Exception) {
            callback.onError("Failed to load folders: ${e.message}")
        }
    }

    suspend fun addSyncFolder(
        remotePath: String,
        localName: String,
        twoWaySync: Boolean,
        wifiOnly: Boolean,
        callback: FolderSelectionCallback
    ) {
        try {
            val account = accountRepository.getActiveAccount()
                ?: throw IllegalStateException("No active account")

            // Create local directory
            val localPath = "$appSyncDirectory/$localName"
            val localDir = File(localPath)
            if (!localDir.exists()) {
                localDir.mkdirs()
            }

            val folder = FolderEntity(
                accountId = account.id,
                localPath = localPath,
                remotePath = remotePath,
                syncEnabled = true,
                twoWaySync = twoWaySync,
                wifiOnly = wifiOnly
            )

            val folderId = folderRepository.insert(folder)
            callback.onFolderAdded(folderId)
        } catch (e: Exception) {
            callback.onError("Failed to add folder: ${e.message}")
        }
    }
}

data class RemoteFolderInfo(
    val path: String,
    val name: String,
    val modified: Long
)
