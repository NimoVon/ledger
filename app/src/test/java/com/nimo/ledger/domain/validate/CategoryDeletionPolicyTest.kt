package com.nimo.ledger.domain.validate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类删除策略（附.1 R3）：「仅零引用可删」。
 * 这是页面上「删除」按钮可不可点的唯一判断，也是 Repository 拒绝删除时的依据（两边共用）。
 */
class CategoryDeletionPolicyTest {

    @Test
    fun zeroReferences_canBeDeleted() {
        val references = CategoryReferences()

        assertTrue(references.isZeroReference)
        assertTrue(CategoryDeletionPolicy.canDelete(references))
        assertEquals(emptyList<CategoryDeletionBlocker>(), CategoryDeletionPolicy.blockers(references))
    }

    @Test
    fun aSingleTransactionBlocksDeletion() {
        val references = CategoryReferences(transactionCount = 1)

        assertFalse(references.isZeroReference)
        assertFalse(CategoryDeletionPolicy.canDelete(references))
        assertEquals(listOf(CategoryDeletionBlocker.HasTransactions), CategoryDeletionPolicy.blockers(references))
    }

    @Test
    fun aBudgetBlocksDeletion() {
        val references = CategoryReferences(budgetCount = 2)

        assertFalse(CategoryDeletionPolicy.canDelete(references))
        assertEquals(listOf(CategoryDeletionBlocker.HasBudgets), CategoryDeletionPolicy.blockers(references))
    }

    @Test
    fun aChildBlocksDeletionEvenWithoutOwnTransactions() {
        // 父分类自己没有流水、子分类有：删父会让子变成孤儿，所以父也不能删
        val references = CategoryReferences(childCount = 1)

        assertFalse(CategoryDeletionPolicy.canDelete(references))
        assertEquals(listOf(CategoryDeletionBlocker.HasChildren), CategoryDeletionPolicy.blockers(references))
    }

    @Test
    fun everyKindOfReferenceIsReportedInAFixedOrder() {
        val references = CategoryReferences(childCount = 3, transactionCount = 5, budgetCount = 1)

        assertEquals(
            listOf(
                CategoryDeletionBlocker.HasChildren,
                CategoryDeletionBlocker.HasTransactions,
                CategoryDeletionBlocker.HasBudgets,
            ),
            CategoryDeletionPolicy.blockers(references),
        )
    }
}
