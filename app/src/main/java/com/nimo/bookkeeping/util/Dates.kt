package com.nimo.bookkeeping.util

import java.time.LocalDate
import java.time.YearMonth

/** 日期工具：occurredOn 统一 YYYY-MM-DD，本地自然日语义（计划 9.2） */
object Dates {

    fun today(): String = LocalDate.now().toString()

    fun isValidIsoDate(text: String): Boolean =
        Regex("""\d{4}-\d{2}-\d{2}""").matches(text) && runCatching { LocalDate.parse(text) }.isSuccess

    /** 某个月的查询边界 [from, to)，to 为下月一号 —— 保证命中索引（计划 3.2） */
    fun monthBounds(year: Int, month: Int): Pair<String, String> {
        val first = LocalDate.of(year, month, 1)
        return first.toString() to first.plusMonths(1).toString()
    }

    fun monthBounds(ym: YearMonth): Pair<String, String> =
        ym.atDay(1).toString() to ym.plusMonths(1).atDay(1).toString()
}
