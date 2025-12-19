package com.nextcloud.sync.models.data

enum class ConflictResolution {
    PENDING,       // Awaiting user decision
    KEEP_LOCAL,    // User chose local version
    KEEP_REMOTE,   // User chose remote version
    KEEP_BOTH,     // User chose to keep both (rename local)
    AUTO_RESOLVED  // Automatically resolved (e.g., non-overlapping changes)
}
