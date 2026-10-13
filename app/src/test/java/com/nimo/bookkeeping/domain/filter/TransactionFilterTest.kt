package com.nimo.bookkeeping.domain.filter

import com.nimo.bookkeeping.domain.model.TransactionRecord
import com.nimo.bookkeeping.domain.model.TransactionType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 筛选口径（F06）：账户、分类（父命中子）、金额区间、日期区间、备注关键词，各维 AND、同维 OR。
 * 纯 JVM 用例，不依赖 Room / Android。
 */
class TransactionFilterTest {

    private fun expense(
        id: Long,
        amountCents: Long,
        date: String,
        accountId: Long? = 1L,
        categoryId: Long? = 10L,
        note: String? = null,
    ) = TransactionRecord(
        id = id,
        type = TransactionType.EXPENSE,
        amountCents = amountCents,
        accountId = accountId,
        occurredOn = LocalDate.parse(date),
        categoryId = categoryId,
        note = note,
    )

    @Test
    fun inactiveFilter_returnsTheSameListUntouched() {
        val records = listOf(expense(1, 100L, "2026-10-12"))
        val filter = TransactionFilter()

        assertFalse(filter.isActive)
        assertSame(records, filter.apply(records))
    }

    @Test
    fun account_matchesEitherLegOfATransfer() {
        val transfer = TransactionRecord(
            id = 1,
            type = TransactionType.TRANSFER,
            amountCents = 5_000L,
            accountId = 1L,
            toAccountId = 2L,
            occurredOn = LocalDate.parse("2026-10-12"),
        )
        val other = expense(2, 100L, "2026-10-12", accountId = 3L)

        assertTrue(TransactionFilter(accountIds = setOf(1L)).matches(transfer))
        assertTrue(TransactionFilter(accountIds = setOf(2L)).matches(transfer))
        assertFalse(TransactionFilter(accountIds = setOf(3L)).matches(transfer))
        assertFalse(TransactionFilter(accountIds = setOf(1L)).matches(other))
    }

    @Test
    fun category_selectingAParentAlsoMatchesItsChildren() {
        val child = expense(1, 100L, "2026-10-12", categoryId = 11L)
        val parentIds = mapOf(10L to null as Long?, 11L to 10L)

        assertTrue(TransactionFilter(categoryIds = setOf(10L)).matches(child, parentIds))
        assertTrue(TransactionFilter(categoryIds = setOf(11L)).matches(child, parentIds))
        assertFalse(TransactionFilter(categoryIds = setOf(12L)).matches(child, parentIds))
    }

    @Test
    fun category_selectingACategorySkipsRowsWithoutOne() {
        val transferWithoutCategory = TransactionRecord(
            id = 1,
            type = TransactionType.TRANSFER,
            amountCents = 100L,
            accountId = 1L,
            toAccountId = 2L,
            occurredOn = LocalDate.parse("2026-10-12"),
        )

        assertFalse(TransactionFilter(categoryIds = setOf(10L)).matches(transferWithoutCategory))
    }

    @Test
    fun amountRange_comparesTheAbsoluteAmount() {
        val minus = TransactionRecord(
            id = 1,
            type = TransactionType.BALANCE_ADJUST,
            amountCents = -2_000L,
            accountId = 1L,
            occurredOn = LocalDate.parse("2026-10-12"),
        )
        val filter = TransactionFilter(minAmountCents = 1_000L, maxAmountCents = 3_000L)

        assertTrue(filter.matches(minus))
        assertFalse(TransactionFilter(minAmountCents = 2_001L).matches(minus))
        assertFalse(TransactionFilter(maxAmountCents = 1_999L).matches(minus))
        // 边界是含的
        assertTrue(TransactionFilter(minAmountCents = 2_000L).matches(minus))
    }

    @Test
    fun keyword_ignoresCaseAndSurroundingSpacesAndSkipsNullNotes() {
        val withNote = expense(1, 100L, "2026-10-12", note = "Dinner with Sam")
        val withoutNote = expense(2, 100L, "2026-10-12")

        assertTrue(TransactionFilter(noteKeyword = " dinner ").matches(withNote))
        assertTrue(TransactionFilter(noteKeyword = "SAM").matches(withNote))
        assertFalse(TransactionFilter(noteKeyword = "lunch").matches(withNote))
        assertFalse(TransactionFilter(noteKeyword = "dinner").matches(withoutNote))
    }

    @Test
    fun dateRange_isInclusiveOnBothEnds() {
        val first = expense(1, 100L, "2026-09-01")
        val middle = expense(2, 100L, "2026-09-15")
        val last = expense(3, 100L, "2026-09-30")
        val filter = TransactionFilter(
            from = LocalDate.parse("2026-09-01"),
            to = LocalDate.parse("2026-09-30"),
        )

        assertTrue(filter.matches(first))
        assertTrue(filter.matches(middle))
        assertTrue(filter.matches(last))
        assertFalse(filter.matches(expense(4, 100L, "2026-10-01")))
        assertFalse(TransactionFilter(from = LocalDate.parse("2026-09-16")).matches(middle))
        assertFalse(TransactionFilter(to = LocalDate.parse("2026-09-14")).matches(middle))
    }

    @Test
    fun dateRange_writtenBackwardsIsNormalizedInsteadOfMatchingNothing() {
        val filter = TransactionFilter(
            from = LocalDate.parse("2026-10-31"),
            to = LocalDate.parse("2026-10-01"),
        )

        assertEquals(
            LocalDate.parse("2026-10-01") to LocalDate.parse("2026-10-31"),
            filter.normalizedRange(),
        )
        assertTrue(filter.matches(expense(1, 100L, "2026-10-12")))
    }

    @Test
    fun queryBounds_areHalfOpenAndNullWithoutDateConditions() {
        assertNull(TransactionFilter(noteKeyword = "午餐").queryBounds())

        assertEquals(
            "2026-09-01" to "2026-10-01",
            TransactionFilter(
                from = LocalDate.parse("2026-09-01"),
                to = LocalDate.parse("2026-09-30"),
            ).queryBounds(),
        )
        // 只填一端：另一端用自然日量的两端兜住
        assertEquals(
            "2026-09-01" to TransactionFilter.MAX_DATE.plusDays(1).toString(),
            TransactionFilter(from = LocalDate.parse("2026-09-01")).queryBounds(),
        )
        assertEquals(
            TransactionFilter.MIN_DATE.toString() to "2026-10-01",
            TransactionFilter(to = LocalDate.parse("2026-09-30")).queryBounds(),
        )
    }

    @Test
    fun dimensionsCombineWithAnd() {
        val hit = expense(1, 1_500L, "2026-10-12", categoryId = 10L, note = "午餐")
        val filter = TransactionFilter(
            accountIds = setOf(1L),
            categoryIds = setOf(10L),
            minAmountCents = 1_000L,
            noteKeyword = "午",
            from = LocalDate.parse("2026-10-01"),
            to = LocalDate.parse("2026-10-31"),
        )

        assertTrue(filter.matches(hit, mapOf(10L to null as Long?)))
        assertFalse(filter.copy(noteKeyword = "晚饭").matches(hit, mapOf(10L to null as Long?)))
        assertFalse(filter.copy(accountIds = setOf(9L)).matches(hit, mapOf(10L to null as Long?)))
    }
}
