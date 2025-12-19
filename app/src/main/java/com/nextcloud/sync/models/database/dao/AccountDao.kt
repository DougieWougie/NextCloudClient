package com.nextcloud.sync.models.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nextcloud.sync.models.database.entities.AccountEntity

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE is_active = 1")
    suspend fun getActiveAccount(): AccountEntity?

    @Query("SELECT * FROM accounts")
    suspend fun getAllAccounts(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :accountId")
    suspend fun getAccountById(accountId: Long): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("UPDATE accounts SET is_active = 0")
    suspend fun deactivateAllAccounts()

    @Query("UPDATE accounts SET last_sync = :timestamp WHERE id = :accountId")
    suspend fun updateLastSync(accountId: Long, timestamp: Long)
}
