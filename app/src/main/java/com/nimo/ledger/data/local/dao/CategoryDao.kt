package com.nimo.ledger.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.model.CategoryKind
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

    /**
     * 同级兄弟（同 kind、同 parentId），顺序与页面一致：sortOrder ASC, id ASC。
     * 上移 / 下移要拿整组一起看，才知道有没有越界。
     */
    @Query(
        "SELECT * FROM categories WHERE kind = :kind AND parentId IS :parentId " +
            "ORDER BY sortOrder ASC, id ASC",
    )
    suspend fun listSiblings(kind: CategoryKind, parentId: Long?): List<CategoryEntity>

    /** 同级最大 sortOrder（空组为 -1）：新建排到末尾就是 max + 1 */
    @Query(
        "SELECT COALESCE(MAX(sortOrder), -1) FROM categories " +
            "WHERE kind = :kind AND parentId IS :parentId",
    )
    suspend fun maxSortOrder(kind: CategoryKind, parentId: Long?): Int

    /**
     * 新建单条。用 @Insert 而不是 @Upsert：新建就是插入，约束冲突必须抛出来，
     * 不能像 @Upsert 那样被 Room 转成"按主键 UPDATE 0 行"的静默无操作。
     */
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    /** 改名。不动 kind / parentId：换树会让既有流水落到另一棵树里（F02 不做跨树移动）。 */
    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE categories SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    /** 子分类数（附.1 R3：有子分类就不能删——删父会让子分类变成孤儿） */
    @Query("SELECT COUNT(*) FROM categories WHERE parentId = :id")
    suspend fun countChildren(id: Long): Int

    /** 挂在这个分类上的流水数（本分类自己的；子分类由 [countChildren] 单独把关） */
    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :id")
    suspend fun countTransactions(id: Long): Int

    /** 挂在这个分类上的预算数（附.1 R3：无预算绑定才可删） */
    @Query("SELECT COUNT(*) FROM budgets WHERE categoryId = :id")
    suspend fun countBudgets(id: Long): Int

    /**
     * 物理删除（附.1 R3 / 附.2：删除不可恢复，只用来清理从未被引用过的错误创建）。
     * 调用方（[com.nimo.ledger.data.repository.LedgerRepository.deleteCategory]）必须先确认零引用：
     * 库里没有"禁止删除被引用分类"的触发器，这里不做兜底。
     */
    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: Long)
}
