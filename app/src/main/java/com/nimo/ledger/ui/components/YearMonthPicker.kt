package com.nimo.ledger.ui.components

import java.time.YearMonth

/**
 * 年月选择器（R1 日期直改）的纯逻辑：年份分页窗口与月份列表。
 *
 * 抽成不依赖 Compose 的对象，是为了让"两步直达"里的两步能被普通单测覆盖：
 * 第一步选年（一屏 [YEARS_PER_PAGE] 年，4 列 × 5 行，手机上不用滚动），
 * 第二步选月（一屏 [MONTHS_PER_PAGE] 个月，4 列 × 3 行）。
 *
 * 打开面板时默认停在包含当前查看年份的那一页，因此"改成今年某个月"永远是最短路径；
 * 跨年跳转由年份页的上一页/下一页箭头完成（一年一屏之外只剩翻页，不需要新的交互概念）。
 */
object YearMonthPicker {

    /** 一屏的年数：4 列 × 5 行 */
    const val YEARS_PER_PAGE = 20

    /** 一屏的月数：4 列 × 3 行 */
    const val MONTHS_PER_PAGE = 12

    /**
     * 含 [year] 的那一页的第一年。
     * 用 floorDiv 而不是 /：年份为负时除法向 0 取整会让相邻年落到同一页的两头。
     */
    fun pageStart(year: Int): Int = Math.floorDiv(year, YEARS_PER_PAGE) * YEARS_PER_PAGE

    /**
     * 相对"含 [year] 的那一页"偏移 [pageOffset] 页的年，升序，共 [YEARS_PER_PAGE] 个。
     * [pageOffset] 为负 = 更早的年代。
     */
    fun years(year: Int, pageOffset: Int = 0): List<Int> {
        val start = pageStart(year) + pageOffset * YEARS_PER_PAGE
        return (0 until YEARS_PER_PAGE).map { start + it }
    }

    /** 12 个月，1..12 */
    fun months(): List<Int> = (1..MONTHS_PER_PAGE).toList()

    /** 年份格：2026 */
    fun yearLabel(year: Int): String = year.toString()

    /** 月份格：10月（与月份标签 "2026年10月" 同一套写法，不补零） */
    fun monthLabel(month: Int): String = "${month}月"

    /** 月份步骤的标题：2026年 */
    fun yearTitle(year: Int): String = "${year}年"

    /** 年份分页的区间标签：2020–2039 */
    fun pageLabel(year: Int, pageOffset: Int = 0): String {
        val start = pageStart(year) + pageOffset * YEARS_PER_PAGE
        return "$start–${start + YEARS_PER_PAGE - 1}"
    }

    /** 该年份格是不是当前查看的年月所在年 */
    fun isSelectedYear(current: YearMonth, year: Int): Boolean = current.year == year

    /** 该月份格是不是当前查看的年月（只在同一个年份下成立，跨年不误亮） */
    fun isSelectedMonth(current: YearMonth, year: Int, month: Int): Boolean =
        current.year == year && current.monthValue == month
}
