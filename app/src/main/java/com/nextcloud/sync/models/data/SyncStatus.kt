package com.nextcloud.sync.models.data

enum class SyncStatus {
    SYNCED,           // In sync
    PENDING_UPLOAD,   // Local changes need upload
    PENDING_DOWNLOAD, // Remote changes need download
    SYNCING,          // Currently syncing
    CONFLICT,         // Conflict detected
    ERROR             // Sync error
}
