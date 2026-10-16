package com.nimo.ledger.ui.categories

import com.nimo.ledger.domain.validate.CategoryDeletionBlocker
import com.nimo.ledger.domain.validate.CategoryDeletionException
import com.nimo.ledger.domain.validate.CategoryReferences
import com.nimo.ledger.domain.validate.CategoryValidationError
import com.nimo.ledger.domain.validate.CategoryValidationException
import com.nimo.ledger.domain.validate.CategoryValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 的用户可见文案：删除被什么挡住、被挡住时该走哪条路（附.1 R3「有引用只能停用」）。
 *
 * 单独钉这一层是因为"能不能删"由 Repository 决定，但"用户看懂了吗"决定他会不会去点一个
 * 点了没反应的删除按钮——所以每个 blocker 都必须有一句人话。
 */
class CategoryDeletionMessagesTest {

    @Test
    fun whileTheReferencesAreStillBeingCounted_theHintSaysSo() {
        assertEquals("正在检查引用…", categoryReferenceHint(null))
    }

    @Test
    fun aZeroReferenceCategory_saysDeletionIsPossibleAndPermanent() {
        val hint = categoryReferenceHint(CategoryReferences())

        assertTrue(hint.contains("可以删除"))
        assertTrue(hint.contains("不可恢复"))
    }

    @Test
    fun aUsedCategory_listsEveryReferenceAndPointsAtArchive() {
        val hint = categoryReferenceHint(
            CategoryReferences(childCount = 2, transactionCount = 3, budgetCount = 1),
        )

        assertTrue(hint.contains("3 笔流水"))
        assertTrue(hint.contains("1 个预算"))
        assertTrue(hint.contains("2 个子分类"))
        assertTrue(hint.contains("不能删除"))
        assertTrue(hint.contains("停用"))
    }

    @Test
    fun theHint_onlyMentionsTheKindsThatActuallyBlock() {
        val hint = categoryReferenceHint(CategoryReferences(transactionCount = 1))

        assertTrue(hint.contains("1 笔流水"))
        assertFalse(hint.contains("预算"))
        assertFalse(hint.contains("子分类"))
    }

    @Test
    fun everyBlocker_hasItsOwnSentence() {
        assertEquals("还有子分类", CategoryDeletionBlocker.HasChildren.toMessage())
        assertEquals("还有交易记录", CategoryDeletionBlocker.HasTransactions.toMessage())
        assertEquals("还绑着预算", CategoryDeletionBlocker.HasBudgets.toMessage())
    }

    @Test
    fun aDeletionFailure_becomesOneSentenceWithTheWayOut() {
        val message = CategoryDeletionException(
            references = CategoryReferences(transactionCount = 3),
            blockers = listOf(CategoryDeletionBlocker.HasTransactions),
        ).toCategoryMessage()

        assertTrue(message.contains("不能删除"))
        assertTrue(message.contains("还有交易记录"))
        assertTrue(message.contains("停用"))
    }

    @Test
    fun aValidationFailure_joinsEveryReason() {
        val message = CategoryValidationException(
            errors = listOf(CategoryValidationError.NameBlank, CategoryValidationError.NameTooLong),
        ).toCategoryMessage()

        assertEquals(
            "分类名称不能为空；分类名称最多 ${CategoryValidator.MAX_NAME_LENGTH} 个字",
            message,
        )
    }

    @Test
    fun anUnknownFailure_fallsBackToItsOwnMessage() {
        assertEquals("磁盘满了", RuntimeException("磁盘满了").toCategoryMessage())
        assertEquals("保存失败", RuntimeException().toCategoryMessage())
    }
}
