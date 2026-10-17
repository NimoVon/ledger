package com.nimo.ledger.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.DailyTotal
import com.nimo.ledger.domain.stats.MonthlyTotal
import com.nimo.ledger.domain.stats.ReportBucket
import com.nimo.ledger.domain.stats.ReportRange
import com.nimo.ledger.ui.theme.TooltipShape
import com.nimo.ledger.util.Money
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * 画法不再由"三段开关"决定，而是由统计页的范围（ReportRange）派生：
 * 单月与 ≤62 天的自选区间按天画柱（柱子可点开当日明细），
 * 近 N 个月 / 自然年 / 更长的区间按月画折线。用户不再需要"先选月份再选跨度"两道口径。
 */

/** 折线画布的高度：加载态用同一个高度占位，避免图出现时页面跳动 */
private val CHART_HEIGHT = 160.dp

internal const val TREND_CHART_TEST_TAG = "report-trend-chart"

/** 逐日柱状图的 testTag（R2「本月」视图） */
internal const val DAILY_CHART_TEST_TAG = "report-daily-chart"

/** 逐日柱状图的纵轴刻度栏（R2 优化：刻度与网格线对齐） */
internal const val CHART_Y_AXIS_TEST_TAG = "report-chart-y-axis"

/** 点柱子后弹出的金额气泡（R2 优化：先看数，再点进当日明细） */
internal const val DAY_TOOLTIP_TEST_TAG = "report-day-tooltip"

/** 横轴太挤时的提示语：柱子可以左右滑 */
internal const val DAY_LABEL_SCROLL_HINT = "左右滑动看整月"

/** 纵轴刻度栏宽度：放得下「¥1,000」「¥1.2万」这类刻度 */
private val Y_AXIS_WIDTH = 52.dp

/** 绘图区上下留白：刻度文字、网格线、柱子与折线共用同一条基准 */
private val PLOT_INSET = 8.dp

/** 逐日柱的最小格子宽度：比它还窄（月底 31 天）就横向滚动 */
internal val DAY_CELL_MIN_WIDTH = 18.dp

/** 相邻日期标签的最小间距（dp）：[dayLabelInterval] 按它反推"隔几天标一次" */
internal const val DAY_LABEL_MIN_SPACING_DP = 44f

/** 金额气泡宽度 */
private val DAY_TOOLTIP_WIDTH = 172.dp

/** 图表的头一句：直接念当前范围，读屏听到的是带数字的整句，测试用前缀匹配 */
internal fun trendHead(range: ReportRange): String = "${range.label}收支趋势"

/**
 * 逐日柱状图的无障碍描述（R2）：窗口天数（= 已过天数）与该窗口的收支合计。
 * 数字全部来自 StatsCalculator.dailyTotals，页面一个数都不自己算。
 */
internal fun dailyDescription(daily: List<DailyTotal>, rangeLabel: String): String {
    val head = "${rangeLabel}逐日收支"
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

/** 金额气泡的标题：「10月16日」（与柱子的无障碍描述同一套日期写法） */
internal fun tooltipHead(total: DailyTotal): String =
    "${total.date.monthValue}月${total.date.dayOfMonth}日"

/** 金额气泡的金额摘要：「支出 ¥2.00 · 收入 ¥0.00」 */
internal fun tooltipSummary(total: DailyTotal): String =
    "支出 ${Money.formatCurrency(total.expenseCents)} · 收入 ${Money.formatCurrency(total.incomeCents)}"

/**
 * 横轴日期标签的间隔（天）：按格子的实际宽度反推，保证相邻标签至少隔 [DAY_LABEL_MIN_SPACING_DP]。
 * 例：格子 44dp 宽 → 每天标；格子 11dp 宽（月底 31 天）→ 每 4 天标一次。
 */
internal fun dayLabelInterval(cellWidthDp: Float): Int =
    if (cellWidthDp <= 0f) 1 else maxOf(1, ceil(DAY_LABEL_MIN_SPACING_DP / cellWidthDp).toInt())

/**
 * 该标出日期的日子：1 号必标、每隔 interval 天标一次；最后一天只有在离上一个标签至少 2 天时才标
 * （否则 30 日与 31 日会挤在一起）。
 */
internal fun labeledDays(days: List<Int>, interval: Int): Set<Int> {
    if (days.isEmpty()) return emptySet()
    val step = maxOf(1, interval)
    val labels = mutableSetOf(days.first())
    days.forEach { day -> if (day % step == 0) labels += day }
    val last = days.last()
    if (last !in labels && last - labels.max() >= 2) labels += last
    return labels
}

/**
 * 无障碍描述：只说"近半年收支趋势"读屏用户拿不到任何数据，所以把窗口里的钱读出来——
 * 窗口合计与最近一个月（折线上最靠右那个点）。数字全部来自 StatsCalculator 的 [MonthlyTotal]。
 */
internal fun trendDescription(points: List<MonthlyTotal>, range: ReportRange): String {
    val head = trendHead(range)
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
 * 收支趋势（F10）：画法由 [range] 的粒度决定——单月/短区间按天画柱（柱子可点开当日流水），
 * 近 N 个月 / 自然年 / 长区间按月画折线。
 *
 * 图表用 Compose 自绘（计划 240 行：先自绘，暂不引图表库）：横轴是日期/月份、纵轴是金额。
 * 所有数字来自 StatsCalculator.dailyTotals / monthlyTotals，页面一个数都不自己算（铁律 3）。
 *
 * @param range 整页当前的范围：横轴覆盖它、标题念它、空态文案也跟着它说
 * @param points 升序的按月合计（按天画法时只有区间覆盖的那几个月）
 * @param daily 升序的逐日合计，只含已过去的天（含今天），未来日期不在里面
 * @param isLoading 数据没到位：显示占位，绝不拿上一段范围的数字画这一段
 * @param onDayClick 点了某一天的柱子：打开当天流水明细
 */
@Composable
internal fun TrendSection(
    range: ReportRange,
    points: List<MonthlyTotal>,
    daily: List<DailyTotal>,
    isLoading: Boolean,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        if (range.bucket == ReportBucket.DAY) {
            DailySection(daily = daily, range = range, isLoading = isLoading, onDayClick = onDayClick)
        } else {
            MonthlySection(points = points, range = range, isLoading = isLoading)
        }
    }
}

/** 「本月」视图：逐日柱状，只画已过去的天数（未来日期不画） */
@Composable
private fun DailySection(
    daily: List<DailyTotal>,
    range: ReportRange,
    isLoading: Boolean,
    onDayClick: (LocalDate) -> Unit,
) {
    val maxCents = daily.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) } ?: 0L
    // 点某天的柱子先弹金额气泡，再点气泡才进当日明细（R2 优化）
    var selectedDay by remember(daily) { mutableStateOf<LocalDate?>(null) }
    when {
        isLoading -> ChartPlaceholder()
        maxCents <= 0L -> EmptyChartText("${range.label}还没有收支记录。")
        else -> {
            TrendLegend(maxCents)
            Spacer(modifier = Modifier.height(8.dp))
            DailyPlot(
                daily = daily,
                rangeLabel = range.label,
                top = niceCeiling(maxCents),
                selectedDay = selectedDay,
                onSelectDay = { day -> selectedDay = if (selectedDay == day) null else day },
                onOpenDayDetail = onDayClick,
            )
        }
    }
}

/** 近 N 个月 / 自然年 / 长区间：按月折线 */
@Composable
private fun MonthlySection(points: List<MonthlyTotal>, range: ReportRange, isLoading: Boolean) {
    val maxCents = points.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) } ?: 0L
    when {
        isLoading -> ChartPlaceholder()
        // 一段时间里一笔收支都没有：给一句话，而不是一条贴着 0 的直线
        maxCents <= 0L -> EmptyChartText("${range.label}还没有收支记录。")
        else -> {
            TrendLegend(maxCents)
            Spacer(modifier = Modifier.height(8.dp))
            // 折线区与纵轴刻度并排：刻度文字要对上网格线，横轴月份要跟折线同宽（左边空出刻度栏）
            Row(modifier = Modifier.fillMaxWidth()) {
                ChartYAxis(top = niceCeiling(maxCents))
                TrendCanvas(
                    points = points,
                    maxCents = maxCents,
                    range = range,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.width(Y_AXIS_WIDTH))
                MonthLabels(points = points, modifier = Modifier.weight(1f))
            }
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
 * 逐日柱状图 + 纵轴刻度（R2 优化）：柱子 + 与刻度对齐的网格线；格子够宽时平铺，
 * 月底 31 天格子太窄就横向滚动并默认停在"今天"。
 *
 * 特意用可点击的 Box 而不是 Canvas 画柱子：Canvas 里的每一根柱子对无障碍和测试都是不可区分的，
 * 而「点某一天的柱子看当天金额」要求每一天都是独立的可点目标。金额全程 Long，比例只在布局时用 Float。
 *
 * @param top 纵轴上界（[niceCeiling] 的结果），刻度文字与柱子高度共用这一个值
 * @param selectedDay 当前弹着气泡的那一天（再点一下同一天就收起）
 */
@Composable
private fun DailyBars(
    daily: List<DailyTotal>,
    rangeLabel: String,
    top: Long,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expenseColor = MaterialTheme.colorScheme.error
    val incomeColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)

    BoxWithConstraints(
        modifier = modifier
            .height(CHART_HEIGHT)
            .testTag(DAILY_CHART_TEST_TAG)
            .semantics { contentDescription = dailyDescription(daily, rangeLabel) },
    ) {
        // 与纵轴刻度、折线图同一套留白：0 分贴最下面那条网格线，上界贴最上面那条
        val available = (maxHeight - PLOT_INSET * 2).coerceAtLeast(0.dp)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = PLOT_INSET.toPx()
            val usable = size.height - inset * 2
            for (i in 0..2) {
                val y = inset + usable * i / 2f
                drawLine(color = gridColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
            }
        }
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Bottom) {
            daily.forEach { total ->
                val isSelected = total.date == selectedDay
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(if (isSelected) highlight else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(vertical = PLOT_INSET)
                        .clickable { onSelectDay(total.date) }
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

/**
 * 纵轴刻度（R2 优化）：上界 / 一半 / 0 三个刻度贴在绘图区左侧。
 *
 * 用自绘 [Layout] 而不是 Column：刻度文字要压在自己那条网格线的中线上，Column 会按文字高度
 * 往下堆叠而错位。文字按"不限宽"测量后再右对齐，长刻度宁可往左溢出也不被裁成半截。
 */
@Composable
private fun ChartYAxis(top: Long) {
    val values = listOf(top, top / 2, 0L)
    Layout(
        modifier = Modifier
            .width(Y_AXIS_WIDTH)
            .height(CHART_HEIGHT)
            .testTag(CHART_Y_AXIS_TEST_TAG),
        content = {
            values.forEach { value ->
                Text(
                    text = Money.formatAxisLabel(value),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            measurable.measure(
                Constraints(
                    minWidth = 0,
                    maxWidth = Constraints.Infinity,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                ),
            )
        }
        val inset = PLOT_INSET.roundToPx()
        val usable = constraints.maxHeight - inset * 2
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val lineY = inset + usable * index / 2
                placeable.place(
                    x = constraints.maxWidth - placeable.width,
                    y = (lineY - placeable.height / 2f).roundToInt(),
                )
            }
        }
    }
}

/**
 * 「本月」视图的绘图区（R2 优化）：左边是纵轴刻度，右边是柱状图。
 *
 * 格子够宽时柱子平铺；月底 31 天格子太窄（< [DAY_CELL_MIN_WIDTH]）就横向滚动，进页面先滚到"今天"，
 * 并在图下方提示可以左右滑。横轴标签与柱子在同一个滚动容器里，保证标签永远对自己那一天；
 * 金额气泡在滚动容器外，按当前滚动位置算 x，滚到哪都贴着对应的柱子。
 */
@Composable
private fun DailyPlot(
    daily: List<DailyTotal>,
    rangeLabel: String,
    top: Long,
    selectedDay: LocalDate?,
    onSelectDay: (LocalDate) -> Unit,
    onOpenDayDetail: (LocalDate) -> Unit,
) {
    val days = daily.map { it.date.dayOfMonth }
    Row(modifier = Modifier.fillMaxWidth()) {
        ChartYAxis(top = top)
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val cell = maxWidth / daily.size
            val needsScroll = cell < DAY_CELL_MIN_WIDTH
            val cellWidth = if (needsScroll) DAY_CELL_MIN_WIDTH else cell
            // 先算成像素：BoxWithConstraintsScope 不是 Density，再嵌进 Box / let 就取不到 maxWidth 了
            val density = LocalDensity.current
            val cellWidthPx = with(density) { cellWidth.toPx() }
            val viewportPx = with(density) { maxWidth.toPx() }
            val bubblePx = with(density) { DAY_TOOLTIP_WIDTH.toPx() }
            val scrollState = rememberScrollState()
            // 月底进页面先停在"今天"那一格：最近的钱最该先看见
            LaunchedEffect(scrollState.maxValue, needsScroll) {
                if (needsScroll) scrollState.scrollTo(scrollState.maxValue)
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // 只有真挤了才挂横向滚动：平时多一个可滚动节点会让"滚到某处"的测试和读屏都变歧义
                    Box(modifier = if (needsScroll) Modifier.horizontalScroll(scrollState) else Modifier) {
                        Column(modifier = Modifier.width(cellWidth * daily.size)) {
                            DailyBars(
                                daily = daily,
                                rangeLabel = rangeLabel,
                                top = top,
                                selectedDay = selectedDay,
                                onSelectDay = onSelectDay,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            DayLabels(days = days, interval = dayLabelInterval(cellWidth.value))
                        }
                    }
                    selectedDay?.let { day ->
                        val index = daily.indexOfFirst { it.date == day }
                        if (index >= 0) {
                            DayTooltip(
                                total = daily[index],
                                onClick = { onOpenDayDetail(day) },
                                // 滚动偏移在 offset 的 lambda 里读：布局期取值，滚动时只重排不重组
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset {
                                        val centerPx = cellWidthPx * index + cellWidthPx / 2f - scrollState.value
                                        val x = (centerPx - bubblePx / 2f)
                                            .coerceIn(0f, (viewportPx - bubblePx).coerceAtLeast(0f))
                                        IntOffset(x.roundToInt(), 0)
                                    },
                            )
                        }
                    }
                }
                if (needsScroll) {
                    Text(
                        text = DAY_LABEL_SCROLL_HINT,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * 点柱子后弹的金额气泡（R2 优化）：先给一眼能看见的数字，再点一下才进当日明细。
 * 整块可点，并且自己带「查看明细」的提示文字。
 */
@Composable
private fun DayTooltip(total: DailyTotal, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .width(DAY_TOOLTIP_WIDTH)
            .testTag(DAY_TOOLTIP_TEST_TAG)
            .clickable(onClick = onClick),
        shape = TooltipShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = tooltipHead(total), style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "查看明细",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = tooltipSummary(total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * 横轴日期：格子一样宽，按 [interval] 稀疏标注（月底 31 天时每 4~5 天才一个标签，不再挤成一团）。
 * 标签允许溢出自己的窄格子画出来（[TextOverflow.Visible]）——间距已经由 interval 保证，不会被裁成半截。
 */
@Composable
private fun DayLabels(days: List<Int>, interval: Int) {
    val labeled = remember(days, interval) { labeledDays(days, interval) }
    Row(modifier = Modifier.fillMaxWidth()) {
        days.forEach { day ->
            Text(
                text = if (day in labeled) "${day}日" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
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
private fun TrendCanvas(
    points: List<MonthlyTotal>,
    maxCents: Long,
    range: ReportRange,
    modifier: Modifier = Modifier,
) {
    val expenseColor = MaterialTheme.colorScheme.error
    val incomeColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val top = niceCeiling(maxCents)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(CHART_HEIGHT)
            .testTag(TREND_CHART_TEST_TAG)
            .semantics { contentDescription = trendDescription(points, range) },
    ) {
        // 留白与纵轴刻度、柱状图共用同一个值：折线画在最上/最下时描边不会被裁掉，
        // 网格线也正好压在刻度文字的中线上
        val inset = PLOT_INSET.toPx()
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
private fun MonthLabels(points: List<MonthlyTotal>, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        points.forEachIndexed { index, p ->
            // 12 个月（自然年）放不下：单数位留空，位置仍按格子对齐
            val label = if (points.size > 8 && index % 2 == 1) "" else "${p.month.monthValue}月"
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
