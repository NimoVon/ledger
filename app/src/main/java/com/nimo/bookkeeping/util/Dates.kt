package com.nimo.bookkeeping.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

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

    /**
     * Material3 DatePicker 的 selectedDateMillis 语义是"所选日期 00:00 **UTC**"的毫秒数。
     * 必须按 UTC 还原自然日：若用系统时区还原，东半球会晚一天、西半球会早一天（计划 9.2 日期错一天）。
     */
    fun isoDateFromUtcMillis(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** 反向：ISO 日期 → DatePicker 需要的"当日 00:00 UTC"毫秒。非法日期返回 null。 */
    fun utcMillisFromIsoDate(iso: String): Long? = runCatching {
        LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()
}
