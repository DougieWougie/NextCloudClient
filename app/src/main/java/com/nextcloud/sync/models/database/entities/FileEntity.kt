package com.nextcloud.sync.models.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nextcloud.sync.models.data.SyncStatus

@Entity(
    tableName = "files",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("folder_id"),
        Index("local_path"),
        Index("remote_path"),
        Index("sync_status")
    ]
)
data class FileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "folder_id")
    val folderId: Long,

    @ColumnInfo(name = "local_path")
    val localPath: String,

    @ColumnInfo(name = "remote_path")
    val remotePath: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "file_size")
    val fileSize: Long,

    @ColumnInfo(name = "mime_type")
    val mimeType: String?,

    @ColumnInfo(name = "local_hash")
    val localHash: String?, // SHA-256 hash

    @ColumnInfo(name = "remote_hash")
    val remoteHash: String?, // From Nextcloud ETag or content hash

    @ColumnInfo(name = "local_modified")
    val localModified: Long?,

    @ColumnInfo(name = "remote_modified")
    val remoteModified: Long?,

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus,

    @ColumnInfo(name = "last_sync")
    val lastSync: Long?,

    @ColumnInfo(name = "etag")
    val etag: String? // Nextcloud ETag for conflict detection
)
