package com.nextcloud.sync.models.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nextcloud.sync.models.data.ConflictResolution

@Entity(
    tableName = "conflicts",
    foreignKeys = [
        ForeignKey(
            entity = FileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("file_id"), Index("resolution_status")]
)
data class ConflictEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "file_id")
    val fileId: Long,

    @ColumnInfo(name = "local_version_path")
    val localVersionPath: String,

    @ColumnInfo(name = "local_modified")
    val localModified: Long,

    @ColumnInfo(name = "local_size")
    val localSize: Long,

    @ColumnInfo(name = "local_hash")
    val localHash: String,

    @ColumnInfo(name = "remote_modified")
    val remoteModified: Long,

    @ColumnInfo(name = "remote_size")
    val remoteSize: Long,

    @ColumnInfo(name = "remote_hash")
    val remoteHash: String,

    @ColumnInfo(name = "detected_at")
    val detectedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "resolution_status")
    val resolutionStatus: ConflictResolution = ConflictResolution.PENDING,

    @ColumnInfo(name = "resolved_at")
    val resolvedAt: Long? = null
)
