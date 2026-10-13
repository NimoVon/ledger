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

    /** 备份导出用的一次性全量读取（不走 Flow，导出要的是一次快照） */
    @Query("SELECT * FROM transactions ORDER BY occurredOn DESC, id DESC")
    suspend fun listAll(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Insert
    suspend fun insert(tx: TransactionEntity): Long

    @Update
    suspend fun update(tx: TransactionEntity)

    @Delete
    suspend fun delete(tx: TransactionEntity)

    /** 备份恢复用：带显式 id 整体写入 */
    @Insert
    suspend fun insertAll(items: List<TransactionEntity>)

    /** 批量覆盖已有行（CSV 增量导入用）。用 @Update 而不是 @Upsert：约束冲突必须抛出来，不能静默 0 行。 */
    @Update
    suspend fun updateAll(items: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int
}
