package com.nextcloud.sync.models.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("account_id"), Index("remote_path")]
)
data class FolderEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "account_id")
    val accountId: Long,

    @ColumnInfo(name = "local_path")
    val localPath: String, // App-specific storage path

    @ColumnInfo(name = "remote_path")
    val remotePath: String, // Nextcloud WebDAV path

    @ColumnInfo(name = "sync_enabled")
    val syncEnabled: Boolean = true,

    @ColumnInfo(name = "two_way_sync")
    val twoWaySync: Boolean = true, // false = download only

    @ColumnInfo(name = "wifi_only")
    val wifiOnly: Boolean = false,

    @ColumnInfo(name = "last_local_scan")
    val lastLocalScan: Long? = null,

    @ColumnInfo(name = "last_remote_scan")
    val lastRemoteScan: Long? = null
)
