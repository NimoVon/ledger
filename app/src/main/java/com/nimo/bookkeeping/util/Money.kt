package com.nimo.bookkeeping.util

import kotlin.math.abs

/**
 * 金额唯一入口：所有金额一律 Long 存"分"，绝不用 Float/Double（计划铁律 1）。
 */
object Money {

    /** 产品允许上限：¥99,999,999.99 */
    const val MAX_AMOUNT_CENTS: Long = 9_999_999_999L

    private val AMOUNT_RE = Regex("""\d{1,11}(\.\d{1,2})?""")

    /**
     * 解析用户输入金额文本为分。接受可选前导 '-'（余额校准用负数）。
     * 规则：非空、纯数字、最多两位小数、非零、绝对值不超过上限。
     * 返回 null 表示非法，由调用方给出提示。
     */
    fun parseCents(input: String): Long? {
        val text = input.trim()
        if (text.isEmpty()) return null
        val negative = text.startsWith("-")
        val body = if (negative) text.substring(1) else text
        if (body.isEmpty() || !AMOUNT_RE.matches(body)) return null
        val parts = body.split('.')
        val centsDigits = parts[0] + (if (parts.size == 2) parts[1].padEnd(2, '0') else "00")
        val magnitude = centsDigits.toLongOrNull() ?: return null
        if (magnitude == 0L || magnitude > MAX_AMOUNT_CENTS) return null
        return if (negative) -magnitude else magnitude
    }

    /** 分 → 元文本，两位小数，如 12345 -> "123.45"、-105 -> "-1.05" */
    fun format(cents: Long): String {
        val a = abs(cents)
        return "${if (cents < 0) "-" else ""}${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    /** 分 → 带货币符号文本，如 12345 -> "¥123.45"、-105 -> "-¥1.05" */
    fun formatCurrency(cents: Long): String =
        if (cents < 0) "-¥${format(-cents)}" else "¥${format(cents)}"
}
