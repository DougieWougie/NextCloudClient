package com.nextcloud.sync.models.database

import androidx.room.TypeConverter
import com.nextcloud.sync.models.data.ConflictResolution
import com.nextcloud.sync.models.data.SyncStatus

class Converters {
    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String {
        return value.name
    }

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus {
        return SyncStatus.valueOf(value)
    }

    @TypeConverter
    fun fromConflictResolution(value: ConflictResolution): String {
        return value.name
    }

    @TypeConverter
    fun toConflictResolution(value: String): ConflictResolution {
        return ConflictResolution.valueOf(value)
    }
}
