package com.nimo.bookkeeping.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.domain.model.CategoryKind
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Query("SELECT * FROM categories ORDER BY isArchived ASC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isArchived = 0 AND kind = :kind ORDER BY sortOrder ASC, id ASC")
    fun observeActiveByKind(kind: CategoryKind): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, id ASC")
    suspend fun listAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Upsert
    suspend fun upsert(category: CategoryEntity): Long

    /** 备份恢复用：带显式 id 整体写入 */
    @Insert
    suspend fun insertAll(items: List<CategoryEntity>)

    /** 批量覆盖已有行（CSV 增量导入用）。用 @Update 而不是 @Upsert：约束冲突必须抛出来，不能静默 0 行。 */
    @Update
    suspend fun updateAll(items: List<CategoryEntity>)

    @Query("DELETE FROM categories")
    suspend fun deleteAll()

    /**
     * 归档父分类时级联归档其子分类（计划 9.2：不允许父已归档子仍可选的悬空状态）。
     */
    @Query("UPDATE categories SET isArchived = 1 WHERE id = :id OR parentId = :id")
    suspend fun archiveWithChildren(id: Long)

    /**
     * 只恢复这一个分类，不动子分类。向上恢复父链由 [LedgerRepository.restoreCategory] 逐级完成：
     * 向下级联会替用户打开他单独停用过的子分类，而向上不恢复又会留下「父已停用、子仍可选」的悬空状态。
     */
    @Query("UPDATE categories SET isArchived = 0 WHERE id = :id")
    suspend fun unarchive(id: Long)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}
