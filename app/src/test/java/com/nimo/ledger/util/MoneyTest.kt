package com.nimo.ledger.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyTest {

    // ---- 解析：合法输入 ----

    @Test
    fun parse_validAmounts() {
        assertEquals(1L, Money.parseCents("0.01"))
        assertEquals(1230L, Money.parseCents("12.3"))
        assertEquals(1200L, Money.parseCents("12"))
        assertEquals(100000L, Money.parseCents("1000.00"))
        assertEquals(100005L, Money.parseCents("1000.05"))
        assertEquals(10L, Money.parseCents("00.10"))
        assertEquals(-500L, Money.parseCents("-5"))        // 余额校准用负数
        assertEquals(-125L, Money.parseCents("-1.25"))
        assertEquals(1200L, Money.parseCents("  12  "))     // 容忍首尾空白
    }

    @Test
    fun parse_groupedAmounts() {
        // 界面上展示的金额带千分位，用户复制回输入框必须仍然能解析
        assertEquals(123400L, Money.parseCents("1,234"))
        assertEquals(100000L, Money.parseCents("1,000.00"))
        assertEquals(100050L, Money.parseCents("1,000.5"))
        assertEquals(123456789L, Money.parseCents("1,234,567.89"))
        assertEquals(-123400L, Money.parseCents("-1,234"))
        assertEquals(Money.MAX_AMOUNT_CENTS, Money.parseCents("99,999,999.99"))
    }

    @Test
    fun parse_maxAllowed() {
        assertEquals(Money.MAX_AMOUNT_CENTS, Money.parseCents("99999999.99"))  // ¥99,999,999.99
        assertNull(Money.parseCents("100000000.00"))                            // 超上限
        assertNull(Money.parseCents("100,000,000.00"))                          // 超上限（带千分位）
    }

    @Test
    fun exceedsMaxAmount_separatesOverflowFromMalformedInput() {
        // 计划 line 495：超上限与格式错要能分开——超限 true，格式问题 false
        assertTrue(Money.exceedsMaxAmount("100000000.00"))
        assertTrue(Money.exceedsMaxAmount("100,000,000.00"))
        assertTrue(Money.exceedsMaxAmount("200566831"))       // 真机上那一串（元）
        assertTrue(Money.exceedsMaxAmount("1000000000"))
        assertFalse(Money.exceedsMaxAmount("99999999.99"))    // 恰好等于上限，不算超
        assertFalse(Money.exceedsMaxAmount("99,999,999.99"))
        assertFalse(Money.exceedsMaxAmount("0"))              // 零是"格式/非零"问题，不是超限
        assertFalse(Money.exceedsMaxAmount("0.00"))
        assertFalse(Money.exceedsMaxAmount("abc"))
        assertFalse(Money.exceedsMaxAmount("1,23"))
        assertFalse(Money.exceedsMaxAmount("1.234"))
        // 超限判定与符号无关：余额校准用的负数金额超限也算超限（调用方仍给"超出上限"文案）
        assertTrue(Money.exceedsMaxAmount("-100000000.00"))
        assertTrue(Money.exceedsMaxAmount("-200566831"))
        assertFalse(Money.exceedsMaxAmount("-99999999.99"))
        assertFalse(Money.exceedsMaxAmount(""))
    }

    // ---- 解析：非法输入必须拒绝（计划 9.2 金额边界） ----

    @Test
    fun parse_invalidInputs() {
        assertNull(Money.parseCents(""))
        assertNull(Money.parseCents("   "))
        assertNull(Money.parseCents("abc"))
        assertNull(Money.parseCents("12.345"))   // 三位小数
        assertNull(Money.parseCents("1."))       // 无小数位的点
        assertNull(Money.parseCents(".5"))       // 缺整数部分
        assertNull(Money.parseCents("+5"))       // 不接受 + 号
        assertNull(Money.parseCents("0"))        // 零
        assertNull(Money.parseCents("0.00"))     // 零
        assertNull(Money.parseCents("-0.00"))    // 零（带符号）
        assertNull(Money.parseCents("1000000000")) // ¥10亿 > 上限
        assertNull(Money.parseCents("--5"))      // 双负号
        assertNull(Money.parseCents("1 2"))      // 中间空格
    }

    @Test
    fun parse_malformedGrouping_isRejected() {
        // 千分位只接受整三位分组，半截分组一律当非法输入（不猜用户想输什么）
        assertNull(Money.parseCents("1,23"))
        assertNull(Money.parseCents("12,0000"))
        assertNull(Money.parseCents(",123"))
        assertNull(Money.parseCents("1,234,"))
        assertNull(Money.parseCents("1,234,56"))
        assertNull(Money.parseCents("1,234.567"))
    }

    @Test
    fun parse_semicolonLikeFullWidthSeparator_isRejected() {
        // 口径（用户 2026-10-14 拍板）：小数点只认半角「.」。
        // 手机数字键盘 6 右边那个点打出来的就是半角，用户输入没有障碍，
        // 所以全角「。」「．」和全角数字一律当非法输入：不做"贴心的"自动转换——
        // 猜错了会把 49。99 悄悄记成 4999 分，那才是真丢钱。
        assertNull(Money.parseCents("49。99"))
        assertNull(Money.parseCents("49．99"))
        assertNull(Money.parseCents("100。"))
        assertNull(Money.parseCents("１２３"))
        assertNull(Money.parseCents("12。345"))
        // 对照：同一个数字，半角点照常解析
        assertEquals(63450L, Money.parseCents("634.5"))
    }

    // ---- 格式化 ----

    @Test
    fun format_positiveAndNegative() {
        assertEquals("123.45", Money.format(12345))
        assertEquals("0.01", Money.format(1))
        assertEquals("100.00", Money.format(10000))
        assertEquals("-1.05", Money.format(-105))
        assertEquals("0.00", Money.format(0))
    }

    @Test
    fun format_neverGroups_becauseItFeedsInputRoundTrip() {
        // format 是"机器文本"：给输入框回填用，永不分组（改了会破坏回填→解析的往返）
        assertEquals("1234.00", Money.format(123400))
        assertEquals("99999999.99", Money.format(Money.MAX_AMOUNT_CENTS))
        assertEquals("1000.00", Money.format(100000))
    }

    @Test
    fun formatCurrency_withSymbolAndGrouping() {
        assertEquals("¥123.45", Money.formatCurrency(12345))
        assertEquals("-¥1.05", Money.formatCurrency(-105))
        assertEquals("¥0.01", Money.formatCurrency(1))
        assertEquals("¥999.99", Money.formatCurrency(99999))             // 三位不分组
        assertEquals("¥1,000.00", Money.formatCurrency(100000))          // 计划 9.1 的写法
        assertEquals("¥12,345.67", Money.formatCurrency(1234567))
        assertEquals("¥1,234,567.89", Money.formatCurrency(123456789))
        assertEquals("-¥1,234.00", Money.formatCurrency(-123400))
        assertEquals("¥99,999,999.99", Money.formatCurrency(Money.MAX_AMOUNT_CENTS))
    }

    // ---- 图表纵轴刻度文本 ----

    @Test
    fun formatAxisLabel_dropsTrailingCentsAndKeepsGrouping() {
        assertEquals("¥0", Money.formatAxisLabel(0))
        assertEquals("¥1", Money.formatAxisLabel(100))
        assertEquals("¥50", Money.formatAxisLabel(5000))
        assertEquals("¥600", Money.formatAxisLabel(60000))
        assertEquals("¥1,000", Money.formatAxisLabel(100000))
        assertEquals("¥9,999", Money.formatAxisLabel(999900))
        assertEquals("-¥6", Money.formatAxisLabel(-600))
        // 不是整元就保留两位：刻度不能"看着像整数其实不是"
        assertEquals("¥12.34", Money.formatAxisLabel(1234))
        assertEquals("¥9,999.99", Money.formatAxisLabel(999999))   // 差一分不到 1 万，仍走元
        assertEquals("¥0.05", Money.formatAxisLabel(5))
    }

    @Test
    fun formatAxisLabel_compressesToWanAboveTenThousandYuan() {
        assertEquals("¥1万", Money.formatAxisLabel(1_000_000))
        assertEquals("¥1.1万", Money.formatAxisLabel(1_000_001))     // 向上取到 0.1 万：刻度不低报（1.000001 万 → 1.1 万）
        assertEquals("¥1.1万", Money.formatAxisLabel(1_010_000))     // 不低报实际值
        assertEquals("¥1.2万", Money.formatAxisLabel(1_150_000))
        assertEquals("¥4万", Money.formatAxisLabel(4_000_000))
        assertEquals("¥10万", Money.formatAxisLabel(10_000_000))
        assertEquals("¥100万", Money.formatAxisLabel(100_000_000))
        assertEquals("-¥1.2万", Money.formatAxisLabel(-1_150_000))
        // niceCeiling 的常见档位都是整数万，标签必须干净
        for (step in listOf(1_000_000L, 2_000_000L, 4_000_000L, 5_000_000L, 10_000_000L)) {
            assertTrue(Money.formatAxisLabel(step).endsWith("万"))
            assertFalse(Money.formatAxisLabel(step).contains("."))
        }
    }

    @Test
    fun parseFormat_roundtrip() {
        for (text in listOf("0.01", "12.30", "99999999.99", "1.05")) {
            val cents = Money.parseCents(text)!!
            assertEquals(text, Money.format(cents))
        }
    }

    @Test
    fun parseFormatCurrency_roundtrip() {
        // 展示文本去掉符号后必须能被解析回同一个分值（用户复制粘贴场景）
        for (cents in listOf(1L, 12345L, 100000L, 1234567L, 123456789L, Money.MAX_AMOUNT_CENTS)) {
            val shown = Money.formatCurrency(cents).removePrefix("¥")
            assertEquals(cents, Money.parseCents(shown))
        }
    }
}
