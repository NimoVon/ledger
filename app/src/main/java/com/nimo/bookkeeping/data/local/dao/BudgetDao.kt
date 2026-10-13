package com.nimo.bookkeeping.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.nimo.bookkeeping.data.local.entity.BudgetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {

    @Query("SELECT * FROM budgets WHERE month = :month ORDER BY categoryId IS NULL DESC, categoryId ASC")
    fun observeByMonth(month: String): Flow<List<BudgetEntity>>

    /** SQLite 的 IS 可正确比较 NULL（categoryId 为 NULL 表示总预算） */
    @Query("SELECT * FROM budgets WHERE month = :month AND categoryId IS :categoryId")
    suspend fun find(month: String, categoryId: Long?): BudgetEntity?

    @Upsert
    suspend fun upsert(budget: BudgetEntity): Long

    /** 备份导出用的一次性全量读取 */
    @Query("SELECT * FROM budgets ORDER BY id ASC")
    suspend fun listAll(): List<BudgetEntity>

    /** 备份恢复用：带显式 id 整体写入 */
    @Insert
    suspend fun insertAll(items: List<BudgetEntity>)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
