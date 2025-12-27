package com.nextcloud.sync.models.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nextcloud.sync.models.database.dao.AccountDao
import com.nextcloud.sync.models.database.dao.ConflictDao
import com.nextcloud.sync.models.database.dao.FileDao
import com.nextcloud.sync.models.database.dao.FolderDao
import com.nextcloud.sync.models.database.entities.AccountEntity
import com.nextcloud.sync.models.database.entities.ConflictEntity
import com.nextcloud.sync.models.database.entities.FileEntity
import com.nextcloud.sync.models.database.entities.FolderEntity
import com.nextcloud.sync.utils.EncryptionUtil
import com.nextcloud.sync.utils.SafeLogger
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        AccountEntity::class,
        FolderEntity::class,
        FileEntity::class,
        ConflictEntity::class
    ],
    version = 3,
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

        /**
         * Database migration from version 1 to version 2.
         *
         * IMPORTANT: When implementing new migrations:
         * 1. Document all schema changes clearly
         * 2. Test migrations with production data
         * 3. Include rollback strategy in comments
         * 4. Validate data integrity after migration
         *
         * TODO: Implement actual migration logic based on schema changes
         * Current implementation is a placeholder - replace with actual SQL
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                SafeLogger.d("AppDatabase", "Migrating database from version 1 to 2")

                // TODO: Replace with actual migration SQL
                // Example migration steps:
                // 1. Add new columns: database.execSQL("ALTER TABLE accounts ADD COLUMN new_field TEXT")
                // 2. Create new tables: database.execSQL("CREATE TABLE new_table (...)")
                // 3. Migrate data: database.execSQL("INSERT INTO new_table SELECT ... FROM old_table")
                // 4. Drop old tables: database.execSQL("DROP TABLE old_table")

                SafeLogger.d("AppDatabase", "Migration 1->2 completed successfully")
            }
        }

        /**
         * Database migration from version 2 to version 3.
         * Adds index on sync_enabled column for performance optimization.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                SafeLogger.d("AppDatabase", "Migrating database from version 2 to 3")

                // Add index on sync_enabled column to optimize getSyncEnabledFolders() query
                database.execSQL("CREATE INDEX IF NOT EXISTS index_folders_sync_enabled ON folders(sync_enabled)")

                SafeLogger.d("AppDatabase", "Migration 2->3 completed successfully")
            }
        }

        /**
         * Creates or retrieves the singleton database instance.
         *
         * SECURITY IMPROVEMENTS:
         * - Changed from .fallbackToDestructiveMigration() to .fallbackToDestructiveMigrationOnDowngrade()
         * - Destructive migration only occurs when downgrading (version decrease)
         * - Upgrades require explicit migrations to prevent accidental data loss
         * - Missing migrations will cause a crash (intentional - forces proper migration implementation)
         *
         * @param context Application context
         * @return AppDatabase instance
         */
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
                    // SECURITY: Only destroy database on downgrade, not on upgrade
                    // This prevents accidental data loss from schema version mismatches
                    .fallbackToDestructiveMigrationOnDowngrade()
                    // Add migrations here as database schema evolves
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    // Migration callback for logging and validation
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            SafeLogger.d("AppDatabase", "Database created")
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) {
                            super.onOpen(db)
                            SafeLogger.d("AppDatabase", "Database opened, version: ${db.version}")
                        }
                    })
                    .build()

                INSTANCE = instance
                instance
            }
        }

        /**
         * Clears the database instance (for testing only).
         *
         * WARNING: This should NEVER be called in production code.
         * Only use for unit tests to ensure a clean state.
         */
        fun clearInstance() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
