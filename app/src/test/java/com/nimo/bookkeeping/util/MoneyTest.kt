package com.nimo.bookkeeping.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
