package com.nimo.bookkeeping.ui.transactions

import com.nimo.bookkeeping.domain.model.CategoryKind

/** 筛选面板的账户候选项（UI 不回表查询：候选由 ViewModel 从实体投影而来） */
data class FilterAccountOption(
    val id: Long,
    val name: String,
    /** 已归档账户也要能筛：归档只是不能记账，历史流水照常要能捞出来 */
    val archived: Boolean,
)

/** 筛选面板的分类候选项（单层父子） */
data class FilterCategoryOption(
    val id: Long,
    val name: String,
    val kind: CategoryKind,
    val parentId: Long?,
    val archived: Boolean,
    val icon: String?,
)

/** 筛选面板的全部候选项 */
data class FilterOptions(
    val accounts: List<FilterAccountOption> = emptyList(),
    val categories: List<FilterCategoryOption> = emptyList(),
) {
    /** 某一类（支出/收入）下的顶层分类 */
    fun parents(kind: CategoryKind): List<FilterCategoryOption> =
        categories.filter { it.kind == kind && it.parentId == null }

    /** 某个顶层分类下的子分类 */
    fun children(parentId: Long): List<FilterCategoryOption> =
        categories.filter { it.parentId == parentId }
}
