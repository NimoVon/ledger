package com.nimo.ledger.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.MoveDirection
import com.nimo.ledger.domain.validate.CategoryReferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 支出 / 收入的中文名。两套分类树各自独立，页面与对话框共用这一处口径。 */
internal fun CategoryKind.displayName(): String = when (this) {
    CategoryKind.EXPENSE -> "支出"
    CategoryKind.INCOME -> "收入"
}

/**
 * 列表行。depth=0 是一级分类，depth=1 是二级（单层父子，计划 F02）。
 *
 * canMoveUp / canMoveDown 是按同级位置算好的：排序是"同 kind、同 parentId 的兄弟之间"的事，
 * 一级与二级各自成组，页面只负责把按钮画出来。
 */
data class CategoryRow(
    val id: Long,
    val name: String,
    val icon: String?,
    val kind: CategoryKind,
    val parentId: Long?,
    val isArchived: Boolean,
    val depth: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

/** 一套分类树（支出或收入） */
data class CategorySection(
    val kind: CategoryKind,
    val title: String,
    val rows: List<CategoryRow>,
)

/** 分类页状态（计划 4.4：至少覆盖加载、内容、空状态和可理解的错误） */
data class CategoriesUiState(
    val isLoading: Boolean = true,
    val sections: List<CategorySection> = emptyList(),
    val errorMessage: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && sections.all { it.rows.isEmpty() }
}

/**
 * 分类管理页（F02）。UI 不直接访问 DAO，只经由 Repository（计划 4.3）。
 *
 * 重名、单层父子、排序这些规则都在 domain + Repository 里，ViewModel 只把树摊平成可渲染的行。
 */
class CategoriesViewModel(private val repository: LedgerRepository) : ViewModel() {

    val uiState: StateFlow<CategoriesUiState> = repository.observeAllCategories()
        .map { all -> CategoriesUiState(isLoading = false, sections = buildSections(all)) }
        .catch { emit(CategoriesUiState(isLoading = false, errorMessage = "读取分类失败，请返回重试")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    suspend fun createCategory(name: String, kind: CategoryKind, parentId: Long?): Result<Long> =
        repository.createCategory(name, kind, parentId)

    suspend fun renameCategory(id: Long, name: String): Result<Unit> =
        repository.renameCategory(id, name)

    /**
     * 停用 / 恢复。两个方向的语义在 Repository：停用级联子分类，恢复只向上恢复祖先链。
     * 页面不重复判断，避免这里和仓库各写一套。
     */
    suspend fun setArchived(row: CategoryRow, archived: Boolean): Result<Unit> = if (archived) {
        repository.archiveCategory(row.id)
    } else {
        repository.restoreCategory(row.id)
    }

    suspend fun moveCategory(id: Long, direction: MoveDirection): Result<Unit> =
        repository.moveCategory(id, direction)

    /**
     * 删除前的引用明细（附.1 R3）：页面用它决定「删除」按钮可不可点。
     * 数数这件事只有仓库会做（UI 不碰 DAO），这里只是一层转交。
     */
    suspend fun categoryReferences(id: Long): CategoryReferences = repository.categoryReferences(id)

    /**
     * 删除分类（附.1 R3）：仓库会再数一遍引用，有引用直接失败并回带原因。
     * 页面拿失败原因引导用户改用「停用」，而不是只显示"删除失败"。
     */
    suspend fun deleteCategory(id: Long): Result<Unit> = repository.deleteCategory(id)

    /**
     * 恢复默认分类（附.4）：按名称补种缺失的预置分类，不覆盖已有。返回实际补种数量。
     */
    suspend fun restoreDefaultCategories(): Result<Int> = repository.restoreDefaultCategories()

    companion object {
        fun factory(repository: LedgerRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CategoriesViewModel(repository) }
        }
    }
}

/**
 * 摊平成"一级紧跟自己的二级"的顺序，支出树在前、收入树在后。
 *
 * 已停用的分类照样列出来（带"已停用"标记），否则用户没有地方恢复它。
 * 排序键与 [com.nimo.ledger.data.local.dao.CategoryDao.observeAll] 一致：
 * sortOrder ASC, id ASC——导入进来的数据可能有重复 sortOrder，用 id 兜底才稳定。
 */
private fun buildSections(all: List<CategoryEntity>): List<CategorySection> =
    CategoryKind.entries.map { kind ->
        val ofKind = all.filter { it.kind == kind }.sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val topLevel = ofKind.filter { it.parentId == null }
        val rows = mutableListOf<CategoryRow>()
        topLevel.forEachIndexed { topIndex, parent ->
            val children = ofKind.filter { it.parentId == parent.id }
            rows += parent.toRow(
                depth = 0,
                canMoveUp = topIndex > 0,
                canMoveDown = topIndex < topLevel.lastIndex,
            )
            children.forEachIndexed { childIndex, child ->
                rows += child.toRow(
                    depth = 1,
                    canMoveUp = childIndex > 0,
                    canMoveDown = childIndex < children.lastIndex,
                )
            }
        }
        // 兜底：父分类行不见了（绕过校验的直插 / 导入覆盖）时，子分类也不能从列表里消失——
        // 页面看不见它，用户就永远没办法把它挂回去或停用。
        val listed = rows.mapTo(mutableSetOf()) { it.id }
        val orphans = ofKind.filterNot { it.id in listed }
        orphans.forEachIndexed { index, orphan ->
            rows += orphan.toRow(
                depth = 1,
                canMoveUp = index > 0,
                canMoveDown = index < orphans.lastIndex,
            )
        }
        CategorySection(kind = kind, title = kind.displayName() + "分类", rows = rows)
    }

private fun CategoryEntity.toRow(depth: Int, canMoveUp: Boolean, canMoveDown: Boolean) = CategoryRow(
    id = id,
    name = name,
    icon = icon,
    kind = kind,
    parentId = parentId,
    isArchived = isArchived,
    depth = depth,
    canMoveUp = canMoveUp,
    canMoveDown = canMoveDown,
)
