package com.nimo.ledger.domain.validate

/**
 * 一个分类当前的引用情况（计划附.1 R3）。删除只允许"零引用"的分类。
 *
 * 口径（附.1 / 附.2）：本分类自己的流水、挂在它上面的预算、以及它的子分类——三者只要有一个不为 0，
 * 就只能停用不能删除。子分类单独算一项：父分类自己没有流水、子分类有流水时同样不能删父，
 * 删掉父会让子分类变成孤儿，统计与筛选口径一起塌。
 */
data class CategoryReferences(
    val childCount: Int = 0,
    val transactionCount: Int = 0,
    val budgetCount: Int = 0,
) {
    val isZeroReference: Boolean get() = childCount == 0 && transactionCount == 0 && budgetCount == 0
}

/** 删除被挡下的原因（页面翻成中文提示，见 ui/categories/CategoryEditDialog.kt） */
enum class CategoryDeletionBlocker { HasChildren, HasTransactions, HasBudgets }

/**
 * 分类删除策略（纯函数，计划附.1 R3「仅零引用可删」）。
 *
 * Repository 在删除前再数一遍引用（写路径的第二道防线），页面拿同一份 [CategoryReferences]
 * 决定「删除」按钮可不可点——两边共用这一个判断，不各写一套。
 */
object CategoryDeletionPolicy {

    fun blockers(references: CategoryReferences): List<CategoryDeletionBlocker> = buildList {
        if (references.childCount > 0) add(CategoryDeletionBlocker.HasChildren)
        if (references.transactionCount > 0) add(CategoryDeletionBlocker.HasTransactions)
        if (references.budgetCount > 0) add(CategoryDeletionBlocker.HasBudgets)
    }

    /** 零引用才可删；有引用时引导改用「停用」（附.2：停用保留历史与统计） */
    fun canDelete(references: CategoryReferences): Boolean = blockers(references).isEmpty()
}

/**
 * 删除被引用的分类时抛出。带引用明细，页面能说清"被什么挡住了"，
 * 而不是只丢一句"删除失败"。
 */
class CategoryDeletionException(
    val references: CategoryReferences,
    val blockers: List<CategoryDeletionBlocker>,
) : RuntimeException("分类已被引用，不能删除：" + blockers.joinToString(","))
