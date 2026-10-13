package com.nimo.bookkeeping.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Query("SELECT * FROM accounts ORDER BY isArchived ASC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY sortOrder ASC, id ASC")
    fun observeActive(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY sortOrder ASC, id ASC")
    suspend fun listAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Upsert
    suspend fun upsert(account: AccountEntity): Long

    /** 备份恢复用：带显式 id 整体写入（不走 autoGenerate，也不做 upsert 掩盖冲突） */
    @Insert
    suspend fun insertAll(items: List<AccountEntity>)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("UPDATE accounts SET isArchived = :archived, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    /** 新建账户的排序位：当前最大 sortOrder + 1（空表为 0） */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM accounts")
    suspend fun nextSortOrder(): Int
}
