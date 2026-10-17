package com.nimo.ledger.domain.stats

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 趋势图的画法粒度：区间够短按天画柱（柱子可点开当日明细），够长按月画折线。
 *
 * 这是**画法**，不是范围本身——范围由 [ReportRange] 决定，画法只由它派生。
 */
enum class ReportBucket {
    /** 逐日柱状：单月、或跨度不超过 [ReportRange.CUSTOM_DAY_LIMIT_DAYS] 天的自定义区间 */
    DAY,

    /** 按月折线：近 N 个月、自然年、跨度较长的自定义区间 */
    MONTH,
}

/**
 * 统计页的「范围」——整页唯一的时间口径（计划 F07/F10 + 本轮增补：自然年、自选区间）。
 *
 * 与旧的 `TrendSpan` 的本质区别：旧「全年」是**以当前月为终点的近 12 个月滚动窗口**，
 * 用户要的是**自然年 1 月 1 日 – 12 月 31 日**；而且范围必须能落到"任意起止日期"。
 * 这里把四类范围收进一个密封接口，统计页的合计、分类占比、账户分布、排行、趋势、日均
 * 全部只认这一个值，谁都不许另算一套区间（铁律 3 的区间版本）。
 *
 * 区间一律用**半开区间** `[from, to)` 表达：月度/区间查询禁止 substr 截取，
 * 一律走日期范围比较（AGENTS.md 第 4 条）。
 */
sealed interface ReportRange {

    /** 区间起点（含） */
    val from: LocalDate

    /** 区间终点（**不含**）：与 DAO 的 `occurredOn >= from AND occurredOn < to` 同一口径 */
    val to: LocalDate

    /** 顶部范围控件上显示的文字，例如「2026年10月」「近6个月」「2026年」「3月1日–4月15日」 */
    val label: String

    /** 环比那一行用来交代"跟谁比"：「较上月」「较上一年」「较前6个月」「较上一段」 */
    val previousLabel: String

    /** 画法：按天还是按月 */
    val bucket: ReportBucket

    /** 区间覆盖的月份（升序）：按月折线的横轴，也用于按月聚合 */
    val months: List<YearMonth>

    /** 是否是"某个月"——只有它为 true 时预算达成率才有意义（月预算按月设） */
    val isSingleMonth: Boolean

    /**
     * 区间里**已经过去**的天数（分母）。
     * 未来的日期不算：拿整段天数当分母会把月中看到的日均压得偏低（与 [StatsCalculator.elapsedDays] 同一口径）。
     */
    fun elapsedDays(today: LocalDate): Int

    /** 按天画法时横轴上的每一天（只含已过去的天，含今天）；按月画法返回空列表 */
    fun days(today: LocalDate): List<LocalDate>

    /** 上一个等长区间（环比用）：月→上月，年→上一年，近 N 个月→再往前 N 个月，自定义→同等天数往前挪 */
    fun previous(): ReportRange

    /**
     * 左右箭头「上一段 / 下一段」：**一次挪一整段**，与 [previous] 同一口径。
     * 月→±1 个月，近 N 个月→±N 个月，年→±1 年，自定义→同等天数前后挪。
     * 不做"窗口滑一个月"式的错位移动：箭头换到的是**和这一段等长、且不重叠**的相邻段，
     * 这样用户往回翻看到的就是洞察区里用来比的那一段，标签说法与数字口径不会打架。
     */
    fun shift(steps: Long): ReportRange

    /** 某个月 */
    data class Month(val month: YearMonth) : ReportRange {
        override val from: LocalDate = month.atDay(1)
        override val to: LocalDate = month.plusMonths(1).atDay(1)
        override val label: String = "${month.year}年${month.monthValue}月"
        override val previousLabel: String = "较上月"
        override val bucket: ReportBucket = ReportBucket.DAY
        override val months: List<YearMonth> = listOf(month)
        override val isSingleMonth: Boolean = true

        override fun elapsedDays(today: LocalDate): Int =
            ChronoUnit.DAYS.between(from, minOf(to, today.plusDays(1))).toInt().coerceAtLeast(0)

        override fun days(today: LocalDate): List<LocalDate> {
            val end = minOf(to, today.plusDays(1))
            if (!end.isAfter(from)) return emptyList()
            return generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(end) }.toList()
        }

        override fun previous(): ReportRange = shift(-1)

        override fun shift(steps: Long): ReportRange = Month(month.plusMonths(steps))
    }

    /** 以 [endMonth] 为终点的近 [count] 个月（count ≥ 2，含端点月） */
    data class LastMonths(val endMonth: YearMonth, val count: Int) : ReportRange {
        init {
            require(count >= 2) { "近 N 个月至少要 2 个月，单月请用 Month" }
        }

        override val from: LocalDate = endMonth.minusMonths((count - 1).toLong()).atDay(1)
        override val to: LocalDate = endMonth.plusMonths(1).atDay(1)
        override val label: String = "近${count}个月"
        override val previousLabel: String = "较前${count}个月"
        override val bucket: ReportBucket = ReportBucket.MONTH
        override val months: List<YearMonth> = (count - 1 downTo 0).map { endMonth.minusMonths(it.toLong()) }
        override val isSingleMonth: Boolean = false

        override fun elapsedDays(today: LocalDate): Int =
            ChronoUnit.DAYS.between(from, minOf(to, today.plusDays(1))).toInt().coerceAtLeast(0)

        override fun days(today: LocalDate): List<LocalDate> = emptyList()

        override fun previous(): ReportRange = shift(-1)

        override fun shift(steps: Long): ReportRange =
            LastMonths(endMonth.plusMonths(steps * count.toLong()), count)
    }

    /** 自然年：1 月 1 日到 12 月 31 日，横轴固定 1–12 月（未来月份补 0） */
    data class CalendarYear(val year: Int) : ReportRange {
        override val from: LocalDate = LocalDate.of(year, 1, 1)
        override val to: LocalDate = LocalDate.of(year + 1, 1, 1)
        override val label: String = "${year}年"
        override val previousLabel: String = "较上一年"
        override val bucket: ReportBucket = ReportBucket.MONTH
        override val months: List<YearMonth> = (1..12).map { YearMonth.of(year, it) }
        override val isSingleMonth: Boolean = false

        override fun elapsedDays(today: LocalDate): Int =
            ChronoUnit.DAYS.between(from, minOf(to, today.plusDays(1))).toInt().coerceAtLeast(0)

        override fun days(today: LocalDate): List<LocalDate> = emptyList()

        override fun previous(): ReportRange = shift(-1)

        override fun shift(steps: Long): ReportRange = CalendarYear(year + steps.toInt())
    }

    /** 自选区间：起止都到日（闭区间），内部折算成半开区间 */
    data class Custom(val start: LocalDate, val endInclusive: LocalDate) : ReportRange {
        init {
            require(!endInclusive.isBefore(start)) { "区间终点不能早于起点" }
        }

        override val from: LocalDate = start
        override val to: LocalDate = endInclusive.plusDays(1)
        override val label: String = when {
            start.year == endInclusive.year ->
                "${start.monthValue}月${start.dayOfMonth}日–${endInclusive.monthValue}月${endInclusive.dayOfMonth}日"
            else ->
                "${start.year}.${start.monthValue}.${start.dayOfMonth}–${endInclusive.year}.${endInclusive.monthValue}.${endInclusive.dayOfMonth}"
        }
        override val previousLabel: String = "较上一段"

        /** 区间天数（含首尾）：Kotlin 顶层/成员属性按声明顺序初始化，必须写在 [bucket] 前面 */
        val daysInclusive: Int = (ChronoUnit.DAYS.between(start, endInclusive) + 1).toInt()

        override val bucket: ReportBucket =
            if (daysInclusive <= CUSTOM_DAY_LIMIT_DAYS) ReportBucket.DAY else ReportBucket.MONTH
        override val months: List<YearMonth> = buildList {
            var ym = YearMonth.from(start)
            val last = YearMonth.from(endInclusive)
            while (!ym.isAfter(last)) {
                add(ym)
                ym = ym.plusMonths(1)
            }
        }
        override val isSingleMonth: Boolean = false

        override fun elapsedDays(today: LocalDate): Int =
            ChronoUnit.DAYS.between(from, minOf(to, today.plusDays(1))).toInt().coerceAtLeast(0)

        override fun days(today: LocalDate): List<LocalDate> {
            if (bucket != ReportBucket.DAY) return emptyList()
            val end = minOf(to, today.plusDays(1))
            if (!end.isAfter(from)) return emptyList()
            return generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(end) }.toList()
        }

        override fun previous(): ReportRange = shift(-1)

        override fun shift(steps: Long): ReportRange {
            val delta = daysInclusive.toLong() * steps
            return Custom(start.plusDays(delta), endInclusive.plusDays(delta))
        }
    }

    companion object {
        /** 自选区间按天画柱的上限（超过就按月折线）：两个月左右是柱子还看得清的极限 */
        const val CUSTOM_DAY_LIMIT_DAYS: Int = 62

        /** 自选区间最多允许多久：再长就不是"看账"而是"翻档案"，且折线横轴会挤成一团 */
        const val CUSTOM_MAX_DAYS: Int = 3660

        fun month(month: YearMonth): ReportRange = Month(month)

        fun lastMonths(endMonth: YearMonth, count: Int): ReportRange = LastMonths(endMonth, count)

        fun year(year: Int): ReportRange = CalendarYear(year)

        fun custom(start: LocalDate, endInclusive: LocalDate): ReportRange =
            Custom(start, endInclusive)
    }
}
