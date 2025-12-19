package com.nextcloud.sync.models.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "server_url")
    val serverUrl: String,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "password_encrypted")
    val passwordEncrypted: String, // Encrypted with Android Keystore

    @ColumnInfo(name = "two_factor_enabled")
    val twoFactorEnabled: Boolean = false,

    @ColumnInfo(name = "auth_token")
    val authToken: String? = null, // App password or session token

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_sync")
    val lastSync: Long? = null
)
