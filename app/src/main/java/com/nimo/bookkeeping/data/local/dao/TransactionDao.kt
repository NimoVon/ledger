package com.nimo.bookkeeping.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    // 计划铁律：统一 occurredOn DESC, id DESC，同日多笔按录入顺序倒序稳定排列

    @Query("SELECT * FROM transactions ORDER BY occurredOn DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /** 月度/区间查询：日期范围比较命中 idx(type, occurredOn)，禁止 substr 截取（计划 3.2） */
    @Query(
        "SELECT * FROM transactions WHERE occurredOn >= :from AND occurredOn < :to " +
            "ORDER BY occurredOn DESC, id DESC",
    )
    fun observeBetween(from: String, to: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Delete
    suspend fun delete(tx: TransactionEntity)

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int
}
