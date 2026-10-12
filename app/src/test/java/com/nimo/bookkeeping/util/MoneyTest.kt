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
    fun parse_maxAllowed() {
        assertEquals(Money.MAX_AMOUNT_CENTS, Money.parseCents("99999999.99"))  // ¥99,999,999.99
        assertNull(Money.parseCents("100000000.00"))                            // 超上限
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
        assertNull(Money.parseCents("12,000"))   // 千分位
        assertNull(Money.parseCents("--5"))      // 双负号
        assertNull(Money.parseCents("1 2"))      // 中间空格
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
    fun formatCurrency_withSymbol() {
        assertEquals("¥123.45", Money.formatCurrency(12345))
        assertEquals("-¥1.05", Money.formatCurrency(-105))
        assertEquals("¥0.01", Money.formatCurrency(1))
    }

    @Test
    fun parseFormat_roundtrip() {
        for (text in listOf("0.01", "12.30", "99999999.99", "1.05")) {
            val cents = Money.parseCents(text)!!
            assertEquals(text, Money.format(cents))
        }
    }
}
