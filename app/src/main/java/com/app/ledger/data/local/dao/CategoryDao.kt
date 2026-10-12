package com.app.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.app.ledger.data.local.entity.CategoryEntity
import com.app.ledger.domain.model.CategoryKind
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

    /**
     * 归档父分类时级联归档其子分类（计划 9.2：不允许父已归档子仍可选的悬空状态）。
     */
    @Query("UPDATE categories SET isArchived = 1 WHERE id = :id OR parentId = :id")
    suspend fun archiveWithChildren(id: Long)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}
