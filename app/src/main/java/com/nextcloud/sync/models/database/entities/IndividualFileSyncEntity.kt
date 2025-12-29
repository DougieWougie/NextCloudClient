package com.nextcloud.sync.models.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an individual file configured for sync.
 *
 * Unlike folder-based sync (FolderEntity), this allows users to select specific
 * files for synchronization regardless of folder hierarchy. Users can add individual
 * files from the remote file manager to sync configuration.
 *
 * Security:
 * - Foreign key cascade delete ensures orphaned records are removed when account is deleted
 * - Paths are validated before insertion using PathValidator
 * - All sync operations use encrypted credentials from AccountEntity
 *
 * @property id Primary key, auto-generated
 * @property accountId Foreign key to AccountEntity (which Nextcloud account owns this file)
 * @property localPath Local file path (content URI or file path)
 * @property remotePath Remote file path on Nextcloud (user-relative WebDAV path)
 * @property fileName File name for display and conflict resolution
 * @property syncEnabled Whether this file is currently enabled for sync
 * @property autoSync Whether to automatically sync this file (vs manual only)
 * @property wifiOnly Restrict sync to WiFi connections only
 * @property lastSync Timestamp of last successful sync (nullable if never synced)
 */
@Entity(
    tableName = "individual_file_sync",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("account_id"),
        Index("remote_path"),
        Index("sync_enabled")
    ]
)
data class IndividualFileSyncEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Long,

    @ColumnInfo(name = "local_path")
    val localPath: String,

    @ColumnInfo(name = "remote_path")
    val remotePath: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "sync_enabled")
    val syncEnabled: Boolean = true,

    @ColumnInfo(name = "auto_sync")
    val autoSync: Boolean = true,

    @ColumnInfo(name = "wifi_only")
    val wifiOnly: Boolean = false,

    @ColumnInfo(name = "last_sync")
    val lastSync: Long? = null
)
