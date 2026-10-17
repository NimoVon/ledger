package com.nimo.ledger.ui.reports

/**
 * 统计页的文本格式（占比、环比）。
 *
 * 金额一律交给 com.nimo.ledger.util.Money（分 → 元）；这里只处理"比例"这一层：
 * StatsCalculator 给出的占比/环比是 Double，展示时先四舍五入到"千分之一"再拼字符串，
 * 不依赖 Locale，也不用 String.format（同一个数字在任何设备、任何语言下都长一样）。
 */
/** 环比方向：给 UI 选箭头图标用。NONE 表示没有可比数据或基本持平。 */
enum class ChangeDirection { UP, DOWN, NONE }

object ReportFormatting {

    /** 没有可比数据（合计为 0 / 上月为 0）时统一显示这个，而不是 0% 或 NaN（计划 3.4） */
    const val NO_DATA = "—"

    /** 占比：0.4231 → "42.3%"；null（合计为 0）→ "—" */
    fun percent(ratio: Double?): String {
        if (ratio == null) return NO_DATA
        return formatTenths(Math.round(ratio * 1_000.0))
    }

    /**
     * 环比文字：0.125 → "较上月 12.5%"；-0.5 → "较上月 50.0%"；null（上一段为 0）→ "较上月 —"。
     *
     * 不再拼 ↑ / ↓ 字符：箭头改由 [changeDirection] + LedgerIcons.ArrowUp / ArrowDown 画
     * （字符基线随字体漂移，见设计规范 §5.1 的 TrendUp / TrendDown）。
     *
     * @param comparedTo 「跟谁比」：单月是"较上月"，自然年是"较上一年"，N 个月窗口是"较前N个月"，
     *   自定义区间是"较上一段"——由 [com.nimo.ledger.domain.stats.ReportRange.previousLabel] 给出。
     *   默认值保持单月口径，测试与旧调用零改动。
     */
    fun change(ratio: Double?, comparedTo: String = "较上月"): String {
        if (ratio == null) return comparedTo + " " + NO_DATA
        val tenths = Math.round(Math.abs(ratio) * 1_000.0)
        // 差不到 0.05% 就别拿出来吓人，显示"持平"
        if (tenths == 0L) return comparedTo + " 持平"
        return comparedTo + " " + formatTenths(tenths)
    }

    /** 差不到 0.05% 视为持平，不拿 0.0% 吓人（与 [change] 同一口径） */
    fun changeDirection(ratio: Double?): ChangeDirection {
        if (ratio == null) return ChangeDirection.NONE
        val tenths = Math.round(Math.abs(ratio) * 1_000.0)
        if (tenths == 0L) return ChangeDirection.NONE
        return if (ratio > 0) ChangeDirection.UP else ChangeDirection.DOWN
    }

    /** 千分之一单位 → "12.5%" */
    private fun formatTenths(tenths: Long): String =
        (tenths / 10).toString() + "." + (tenths % 10) + "%"
}
