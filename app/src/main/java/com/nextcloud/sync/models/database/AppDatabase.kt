package com.nextcloud.sync.models.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nextcloud.sync.models.database.dao.AccountDao
import com.nextcloud.sync.models.database.dao.ConflictDao
import com.nextcloud.sync.models.database.dao.FileDao
import com.nextcloud.sync.models.database.dao.FolderDao
import com.nextcloud.sync.models.database.entities.AccountEntity
import com.nextcloud.sync.models.database.entities.ConflictEntity
import com.nextcloud.sync.models.database.entities.FileEntity
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.utils.EncryptionUtil
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        FileEntity::class,
        ConflictEntity::class
    ],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun folderDao(): FolderDao
    abstract fun fileDao(): FileDao
    abstract fun conflictDao(): ConflictDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                // Load SQLCipher native library
                System.loadLibrary("sqlcipher")

                val passphrase = EncryptionUtil.getDatabaseKey(context)
                val factory = SupportOpenHelperFactory(passphrase)

                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "nextcloud_sync.db"
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration()
                    .build()

                INSTANCE = instance
                instance
            }
        }
    }
}
