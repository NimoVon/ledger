package com.nimo.bookkeeping.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.dp
import com.nimo.bookkeeping.domain.stats.MonthlyTotal
import com.nimo.bookkeeping.util.Money

/**
 * 趋势折线的跨度（F10）：半年 = 当前查看月份往前 5 个月，全年 = 往前 11 个月。
 * 窗口以统计页顶部选中的月份为终点，所以切月时折线跟着走。
 */
enum class TrendSpan(val months: Int, val label: String) {
    HALF_YEAR(6, "半年"),
    FULL_YEAR(12, "全年"),
}

/** 折线画布的高度：加载态用同一个高度占位，避免图出现时页面跳动 */
private val CHART_HEIGHT = 160.dp

internal const val TREND_CHART_TEST_TAG = "report-trend-chart"

/** 无障碍与测试共用同一句描述：切到全年之后它必须变成 12 个月 */
internal fun trendDescription(span: TrendSpan): String = "近${span.months}个月收支趋势"

/**
 * 收支趋势（F10）：近半年 / 近全年的按月收入、支出折线。
 *
 * 图表用 Compose Canvas 自绘（计划 240 行：先自绘，暂不引图表库）：
 * 横轴是月份、纵轴是金额，两条折线——支出与收入。所有数字来自 StatsCalculator.monthlyTotals，
 * 页面一个数都不自己算（铁律 3）。
 *
 * @param span 当前选中的跨度（点了就生效，即使数据还在读）
 * @param points 升序的按月合计；[isLoading] 为 true 时这里是空列表
 * @param isLoading 数据没到位：显示占位，绝不拿上一段的数字画这一段的图
 */
@Composable
internal fun TrendSection(
    span: TrendSpan,
    points: List<MonthlyTotal>,
    isLoading: Boolean,
    onSpanChange: (TrendSpan) -> Unit,
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

        val maxCents = points.maxOfOrNull { maxOf(it.incomeCents, it.expenseCents) } ?: 0L
        when {
            isLoading -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(CHART_HEIGHT),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }

            // 一段时间里一笔收支都没有：给一句话，而不是一条贴着 0 的直线
            maxCents <= 0L -> Text(
                text = "近${span.months}个月还没有收支记录。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            else -> {
                TrendLegend(maxCents)
                Spacer(modifier = Modifier.height(8.dp))
                TrendCanvas(points = points, maxCents = maxCents, span = span)
                Spacer(modifier = Modifier.height(6.dp))
                MonthLabels(points = points, span = span)
            }
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
            .semantics { contentDescription = trendDescription(span) },
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
