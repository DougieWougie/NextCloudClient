package com.nextcloud.sync.models.repository

import com.nextcloud.sync.models.database.dao.AccountDao
import com.nextcloud.sync.models.database.entities.AccountEntity

class AccountRepository(private val accountDao: AccountDao) {
    suspend fun getActiveAccount(): AccountEntity? {
        return accountDao.getActiveAccount()
    }

    suspend fun getAllAccounts(): List<AccountEntity> {
        return accountDao.getAllAccounts()
    }

    suspend fun getAccountById(accountId: Long): AccountEntity? {
        return accountDao.getAccountById(accountId)
    }

    suspend fun insertAccount(account: AccountEntity): Long {
        return accountDao.insert(account)
    }

    suspend fun updateAccount(account: AccountEntity) {
        accountDao.update(account)
    }

    suspend fun deleteAccount(account: AccountEntity) {
        accountDao.delete(account)
    }

    suspend fun deactivateAllAccounts() {
        accountDao.deactivateAllAccounts()
    }

    suspend fun updateLastSync(accountId: Long, timestamp: Long) {
        accountDao.updateLastSync(accountId, timestamp)
    }
}
