package com.nimo.bookkeeping.data.local.dao

import androidx.room.Dao
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

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteById(id: Long)
}
