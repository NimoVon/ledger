package com.nimo.ledger.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.DailyTotal
import com.nimo.ledger.domain.stats.MonthlyTotal
import com.nimo.ledger.util.Money
import java.time.LocalDate
import java.time.YearMonth

/**
 * 趋势区的跨度（R2 三段：本月｜半年｜全年）。「本月」按天画柱状（只画已过天数），
 * 半年 = 当前查看月份往前 5 个月，全年 = 往前 11 个月，按月份画折线。
 * 窗口以统计页顶部选中的月份为终点，所以切月时图跟着走。
 */
enum class TrendSpan(val months: Int, val label: String) {
    MONTH(1, "本月"),
    HALF_YEAR(6, "半年"),
    FULL_YEAR(12, "全年"),
}

/** 折线画布的高度：加载态用同一个高度占位，避免图出现时页面跳动 */
private val CHART_HEIGHT = 160.dp

internal const val TREND_CHART_TEST_TAG = "report-trend-chart"

/** 逐日柱状图的 testTag（R2「本月」视图） */
internal const val DAILY_CHART_TEST_TAG = "report-daily-chart"

/** 图表的头一句（切到全年之后必须变成 12 个月）：读屏听到的是带数字的整句，测试用前缀匹配 */
internal fun trendHead(span: TrendSpan): String =
    if (span == TrendSpan.MONTH) "本月收支趋势" else "近${span.months}个月收支趋势"

/**
 * 逐日柱状图的无障碍描述（R2）：窗口天数（= 已过天数）与该窗口的收支合计。
 * 数字全部来自 StatsCalculator.dailyTotals，页面一个数都不自己算。
 */
internal fun dailyDescription(daily: List<DailyTotal>, month: YearMonth): String {
    val head = "${month.year}年${month.monthValue}月逐日收支"
    if (daily.isEmpty()) return "$head：暂无数据"
    val income = daily.sumOf { it.incomeCents }
    val expense = daily.sumOf { it.expenseCents }
    return "$head，共 ${daily.size} 天：收入 ${Money.formatCurrency(income)}，支出 ${Money.formatCurrency(expense)}"
}

/** 单根柱子（一天）的无障碍描述，同时也是它的点击目标说明 */
internal fun dayDescription(total: DailyTotal): String {
    val label = "${total.date.monthValue}月${total.date.dayOfMonth}日"
    return "$label：支出 ${Money.formatCurrency(total.expenseCents)}，收入 ${Money.formatCurrency(total.incomeCents)}"
}

/**
 * 无障碍描述：只说"近半年收支趋势"读屏用户拿不到任何数据，所以把窗口里的钱读出来——
 * 窗口合计与最近一个月（折线上最靠右那个点）。数字全部来自 StatsCalculator 的 [MonthlyTotal]。
 */
internal fun trendDescription(points: List<MonthlyTotal>, span: TrendSpan): String {
    val head = trendHead(span)
    if (points.isEmpty()) return "$head：暂无数据"
    val income = points.sumOf { it.incomeCents }
    val expense = points.sumOf { it.expenseCents }
    val last = points.last()
    return "$head，共 ${points.size} 个月：收入 ${Money.formatCurrency(income)}，" +
        "支出 ${Money.formatCurrency(expense)}；最近一个月 " +
        "${last.month.year}年${last.month.monthValue}月：收入 ${Money.formatCurrency(last.incomeCents)}，" +
        "支出 ${Money.formatCurrency(last.expenseCents)}"
}

/**
 * 收支趋势（F10 / R2）：三段「本月｜半年｜全年」。本月按天画柱状（柱子可点开当日流水），
 * 半年/全年按月份画折线。
 *
 * 图表用 Compose 自绘（计划 240 行：先自绘，暂不引图表库）：横轴是日期/月份、纵轴是金额。
 * 所有数字来自 StatsCalculator.dailyTotals / monthlyTotals，页面一个数都不自己算（铁律 3）。
 *
 * @param span 当前选中的跨度（点了就生效，即使数据还在读）
 * @param month 顶部选中的月份：「本月」视图的柱子属于它
 * @param points 升序的按月合计（切到「本月」时只有当前月一个点）
 * @param daily 升序的逐日合计，只含已过去的天（含今天），未来日期不在里面
 * @param isLoading 数据没到位：显示占位，绝不拿上一段的数字画这一段的图
 * @param onDayClick 点了某一天的柱子：打开当天流水明细
 */
@Composable
internal fun TrendSection(
    span: TrendSpan,
    month: YearMonth,
    points: List<MonthlyTotal>,
    daily: List<DailyTotal>,
    isLoading: Boolean,
    onSpanChange: (TrendSpan) -> Unit,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TrendSpan.entries.forEachIndexed { index, item ->
                SegmentedButton(
                    selected = item == span,
                    onClick = { onSpanChange(item) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = TrendSpan.entries.size,
                    ),
                ) { Text(item.label) }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (span == TrendSpan.MONTH) {
            DailySection(daily = daily, month = month, isLoading = isLoading, onDayClick = onDayClick)
        } else {
            MonthlySection(points = points, span = span, isLoading = isLoading)
        }
    }
}

/** 「本月」视图：逐日柱状，只画已过去的天数（未来日期不画） */
@Composable
private fun DailySection(
    daily: List<DailyTotal>,
    month: YearMonth,
    isLoading: Boolean,
    onDayClick: (LocalDate) -> Unit,
) {
    val maxCents = daily.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) } ?: 0L
    when {
        isLoading -> ChartPlaceholder()
        maxCents <= 0L -> EmptyChartText("本月还没有收支记录。")
        else -> {
            TrendLegend(maxCents)
            Spacer(modifier = Modifier.height(8.dp))
            DailyBars(daily = daily, month = month, maxCents = maxCents, onDayClick = onDayClick)
            Spacer(modifier = Modifier.height(6.dp))
            DayLabels(daily = daily)
        }
    }
}

/** 半年 / 全年视图：按月折线 */
@Composable
private fun MonthlySection(points: List<MonthlyTotal>, span: TrendSpan, isLoading: Boolean) {
    val maxCents = points.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) } ?: 0L
    when {
        isLoading -> ChartPlaceholder()
        // 一段时间里一笔收支都没有：给一句话，而不是一条贴着 0 的直线
        maxCents <= 0L -> EmptyChartText("近${span.months}个月还没有收支记录。")
        else -> {
            TrendLegend(maxCents)
            Spacer(modifier = Modifier.height(8.dp))
            TrendCanvas(points = points, maxCents = maxCents, span = span)
            Spacer(modifier = Modifier.height(6.dp))
            MonthLabels(points = points, span = span)
        }
    }
}

/** 加载占位：与图表同高，避免数据到位时页面跳动 */
@Composable
private fun ChartPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT),
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
}

@Composable
private fun EmptyChartText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/**
 * 逐日柱状图（R2）：一天两根柱子（支出在前、收入在后），高度按当天金额占纵轴上界的比例。
 *
 * 特意用可点击的 Box 而不是 Canvas 画：Canvas 里的每一根柱子对无障碍和测试都是不可区分的，
 * 而「点某一天的柱子看当日流水」要求每一天都是独立的可点目标。金额全程 Long，
 * 比例只在布局时用 Float。
 */
@Composable
private fun DailyBars(
    daily: List<DailyTotal>,
    month: YearMonth,
    maxCents: Long,
    onDayClick: (LocalDate) -> Unit,
) {
    val expenseColor = MaterialTheme.colorScheme.error
    val incomeColor = MaterialTheme.colorScheme.primary
    val top = niceCeiling(maxCents)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .testTag(DAILY_CHART_TEST_TAG)
            .semantics { contentDescription = dailyDescription(daily, month) },
    ) {
        val available = maxHeight
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            daily.forEach { total ->
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onDayClick(total.date) }
                        .semantics(mergeDescendants = true) { contentDescription = dayDescription(total) },
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    DailyBar(
                        cents = total.expenseCents,
                        top = top,
                        available = available,
                        color = expenseColor,
                        modifier = Modifier.weight(1f),
                    )
                    DailyBar(
                        cents = total.incomeCents,
                        top = top,
                        available = available,
                        color = incomeColor,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 一根柱子：高度 = 上界可用高度 × 金额比例；有金额但比例太小时至少留 2dp，否则等于没画 */
@Composable
private fun DailyBar(cents: Long, top: Long, available: Dp, color: Color, modifier: Modifier = Modifier) {
    val fraction = if (top <= 0L) 0f else (cents.toDouble() / top).coerceIn(0.0, 1.0).toFloat()
    val barHeight = if (cents <= 0L) 0.dp else (available * fraction).coerceAtLeast(2.dp)
    Box(
        modifier = modifier
            .height(barHeight)
            .background(color = color, shape = RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)),
    )
}

/** 横轴日期：格子一样宽，放不下时只标 1 号、5 的倍数与最后一天 */
@Composable
private fun DayLabels(daily: List<DailyTotal>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        daily.forEachIndexed { index, total ->
            val day = total.date.dayOfMonth
            val label = if (index == 0 || index == daily.lastIndex || day % 5 == 0) "${day}日" else ""
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 图例 + 这段时间的最高值（纵轴上界是它的"好看整数值"，见 [niceCeiling]） */
@Composable
private fun TrendLegend(maxCents: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(label = "支出", color = MaterialTheme.colorScheme.error)
        Spacer(modifier = Modifier.width(12.dp))
        LegendDot(label = "收入", color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = "最高 ${Money.formatCurrency(maxCents)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}

/** 折线本体：3 条横向网格线 + 支出/收入两条折线，每个月份一个点 */
@Composable
private fun TrendCanvas(points: List<MonthlyTotal>, maxCents: Long, span: TrendSpan) {
    val expenseColor = MaterialTheme.colorScheme.error
    val incomeColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val top = niceCeiling(maxCents)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .testTag(TREND_CHART_TEST_TAG)
            .semantics { contentDescription = trendDescription(points, span) },
    ) {
        // 上下各留一点：折线画在最上/最下时描边不会被裁掉
        val inset = 4f
        val usable = size.height - inset * 2
        for (i in 0..2) {
            val y = inset + usable * i / 2f
            drawLine(color = gridColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
        }
        if (top <= 0L || points.isEmpty()) return@Canvas

        val stepX = if (points.size <= 1) 0f else size.width / (points.size - 1)
        // 金额 → y：0 分贴底线，top 分贴顶线
        fun yAt(cents: Long): Float {
            val ratio = (cents.toDouble() / top).coerceIn(0.0, 1.0).toFloat()
            return inset + usable * (1f - ratio)
        }

        val strokePx = 1.5.dp.toPx()
        val expensePoints = points.mapIndexed { index, p -> Offset(stepX * index, yAt(p.expenseCents)) }
        val incomePoints = points.mapIndexed { index, p -> Offset(stepX * index, yAt(p.incomeCents)) }
        drawPolyline(expenseColor, expensePoints, strokePx)
        drawPolyline(incomeColor, incomePoints, strokePx)
    }
}

private fun DrawScope.drawPolyline(color: Color, points: List<Offset>, strokePx: Float) {
    if (points.isEmpty()) return
    val path = Path()
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        path.lineTo(points[i].x, points[i].y)
    }
    drawPath(path = path, color = color, style = Stroke(width = strokePx, cap = StrokeCap.Round))
    points.forEach { drawCircle(color = color, radius = strokePx * 1.8f, center = it) }
}

/** 横轴月份：每个格子一样宽，12 个格子放不下时隔一个显示一个（位置仍按格子对齐） */
@Composable
private fun MonthLabels(points: List<MonthlyTotal>, span: TrendSpan) {
    Row(modifier = Modifier.fillMaxWidth()) {
        points.forEachIndexed { index, p ->
            val label = if (span == TrendSpan.FULL_YEAR && index % 2 == 1) "" else "${p.month.monthValue}月"
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 纵轴上界：取一个"好看"的整数（1/2/4/5 × 10^n），保证 ≥ [maxCents]。
 * 直接用 max 当上界的话，最高那个月会贴着顶边，看不出高低。
 */
internal fun niceCeiling(maxCents: Long): Long {
    if (maxCents <= 0L) return 0L
    var scale = 1L
    while (scale <= maxCents / 10) scale *= 10
    for (multiplier in longArrayOf(1L, 2L, 4L, 5L)) {
        val candidate = scale * multiplier
        if (candidate >= maxCents) return candidate
    }
    return scale * 10
}
