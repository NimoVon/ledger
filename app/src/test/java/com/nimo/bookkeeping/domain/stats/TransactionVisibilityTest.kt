package com.nimo.bookkeeping.domain.stats

import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 撤销窗口内流水的唯一过滤口径（计划 9.2 延迟物理删除）。
 *
 * 流水页、账户页都调用它——这一层要是错了，用户就会看到"我明明删了，数字还在"。
 */
class TransactionVisibilityTest {

    private fun expense(id: Long) = TransactionEntity(
        id = id,
        type = TransactionType.EXPENSE,
        amount = 100L,
        accountId = 1L,
        categoryId = 1L,
        occurredOn = "2026-10-12",
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun noPendingDeletion_returnsTheSameListUntouched() {
        val all = listOf(expense(1L), expense(2L))

        // 没有待删除的：原样返回（同一个实例，省一次无意义的拷贝）
        assertSame(all, TransactionVisibility.visible(all, emptySet()))
    }

    @Test
    fun pendingRows_areLeftOut() {
        val all = listOf(expense(3L), expense(1L), expense(2L))

        assertEquals(listOf(1L, 2L), TransactionVisibility.visible(all, setOf(3L)).map { it.id })
        assertEquals(emptyList<Long>(), TransactionVisibility.visible(all, setOf(1L, 2L, 3L)).map { it.id })
    }

    @Test
    fun idsNotPresentInTheList_doNotDisturb() {
        val all = listOf(expense(1L), expense(2L))

        assertEquals(listOf(1L, 2L), TransactionVisibility.visible(all, setOf(99L)).map { it.id })
        assertEquals(listOf(1L), TransactionVisibility.visible(all, setOf(2L, 99L)).map { it.id })
    }
}
