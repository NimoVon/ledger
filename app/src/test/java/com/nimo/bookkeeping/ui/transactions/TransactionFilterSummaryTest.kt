package com.nimo.bookkeeping.ui.transactions

import com.nimo.bookkeeping.domain.filter.TransactionFilter
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件摘要文案（F06）：屏幕上的条件必须与实际筛选条件一致，所以顺序与措辞都有断言。
 * 纯 JVM 用例。
 */
class TransactionFilterSummaryTest {

    @Test
    fun noCondition_meansNoChipsAndNoTexts() {
        val filter = TransactionFilter()

        assertTrue(TransactionFilterSummary.chips(filter).isEmpty())
        assertNull(TransactionFilterSummary.amountText(filter))
        assertNull(TransactionFilterSummary.dateRangeText(filter))
    }

    @Test
    fun chips_areOrderedAccountCategoryAmountKeywordDate() {
        val filter = TransactionFilter(
            accountIds = setOf(2L, 1L),
            categoryIds = setOf(10L),
            minAmountCents = 10_000L,
            maxAmountCents = 50_000L,
            noteKeyword = " 午餐 ",
            from = LocalDate.parse("2026-09-01"),
            to = LocalDate.parse("2026-09-30"),
        )

        val chips = TransactionFilterSummary.chips(
            filter = filter,
            accountNames = mapOf(1L to "现金", 2L to "招行卡"),
            categoryNames = mapOf(10L to "餐饮"),
        )

        assertEquals(
            listOf(
                "账户：招行卡、现金",
                "分类：餐饮",
                "金额：¥100.00 ~ ¥500.00",
                "关键词：午餐",
                "日期：2026-09-01 ~ 2026-09-30",
            ),
            chips,
        )
    }

    @Test
    fun chips_fallBackToIdsWhenANameIsMissing() {
        val chips = TransactionFilterSummary.chips(
            filter = TransactionFilter(accountIds = setOf(7L)),
        )

        assertEquals(listOf("账户：#7"), chips)
    }

    @Test
    fun amountText_coversBothBoundsLowerOnlyUpperOnly() {
        assertEquals(
            "金额：¥100.00 ~ ¥500.00",
            TransactionFilterSummary.amountText(
                TransactionFilter(minAmountCents = 10_000L, maxAmountCents = 50_000L),
            ),
        )
        assertEquals(
            "金额：≥ ¥100.00",
            TransactionFilterSummary.amountText(TransactionFilter(minAmountCents = 10_000L)),
        )
        assertEquals(
            "金额：≤ ¥500.00",
            TransactionFilterSummary.amountText(TransactionFilter(maxAmountCents = 50_000L)),
        )
    }

    @Test
    fun dateRangeText_coversBothBoundsStartOnlyEndOnly() {
        assertEquals(
            "2026-09-01 ~ 2026-09-30",
            TransactionFilterSummary.dateRangeText(
                TransactionFilter(
                    from = LocalDate.parse("2026-09-01"),
                    to = LocalDate.parse("2026-09-30"),
                ),
            ),
        )
        assertEquals(
            "2026-09-01 起",
            TransactionFilterSummary.dateRangeText(
                TransactionFilter(from = LocalDate.parse("2026-09-01")),
            ),
        )
        assertEquals(
            "截至 2026-09-30",
            TransactionFilterSummary.dateRangeText(
                TransactionFilter(to = LocalDate.parse("2026-09-30")),
            ),
        )
    }

    @Test
    fun dateRangeText_showsTheNormalizedOrderWhenWrittenBackwards() {
        assertEquals(
            "2026-10-01 ~ 2026-10-31",
            TransactionFilterSummary.dateRangeText(
                TransactionFilter(
                    from = LocalDate.parse("2026-10-31"),
                    to = LocalDate.parse("2026-10-01"),
                ),
            ),
        )
    }
}
