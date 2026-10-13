package com.nimo.bookkeeping.ui.reports

/**
 * 统计页的文本格式（占比、环比）。
 *
 * 金额一律交给 com.nimo.bookkeeping.util.Money（分 → 元）；这里只处理"比例"这一层：
 * StatsCalculator 给出的占比/环比是 Double，展示时先四舍五入到"千分之一"再拼字符串，
 * 不依赖 Locale，也不用 String.format（同一个数字在任何设备、任何语言下都长一样）。
 */
object ReportFormatting {

    /** 没有可比数据（合计为 0 / 上月为 0）时统一显示这个，而不是 0% 或 NaN（计划 3.4） */
    const val NO_DATA = "—"

    /** 占比：0.4231 → "42.3%"；null（合计为 0）→ "—" */
    fun percent(ratio: Double?): String {
        if (ratio == null) return NO_DATA
        return formatTenths(Math.round(ratio * 1_000.0))
    }

    /** 环比：0.125 → "较上月 ↑12.5%"；-0.5 → "较上月 ↓50.0%"；null（上月为 0）→ "较上月 —" */
    fun change(ratio: Double?): String {
        if (ratio == null) return "较上月 " + NO_DATA
        val tenths = Math.round(Math.abs(ratio) * 1_000.0)
        // 差不到 0.05% 就别拿出来吓人，显示"持平"
        if (tenths == 0L) return "较上月 持平"
        val arrow = if (ratio > 0) "↑" else "↓"
        return "较上月 " + arrow + formatTenths(tenths)
    }

    /** 千分之一单位 → "12.5%" */
    private fun formatTenths(tenths: Long): String =
        (tenths / 10).toString() + "." + (tenths % 10) + "%"
}
