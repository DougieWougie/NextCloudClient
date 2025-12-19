package com.nextcloud.sync.controllers.sync

import com.nextcloud.sync.models.database.entities.FileEntity

data class SyncStats(
    val uploaded: Int,
    val downloaded: Int,
    val conflicts: Int
)

data class LocalFileInfo(
    val path: String,
    val relativePath: String,
    val size: Long,
    val modified: Long,
    val hash: String
)

data class RemoteFileInfo(
    val path: String,
    val size: Long,
    val modified: Long,
    val etag: String,
    val hash: String
)

data class ConflictInfo(
    val localFile: LocalFileInfo?,
    val remoteFile: RemoteFileInfo?,
    val dbFile: FileEntity
)

data class SyncPlan(
    val toUpload: List<LocalFileInfo>,
    val toDownload: List<RemoteFileInfo>,
    val conflicts: List<ConflictInfo>,
    val noChange: List<FileEntity>
)
