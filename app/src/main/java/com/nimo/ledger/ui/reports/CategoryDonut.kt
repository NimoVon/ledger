package com.nimo.ledger.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.CategoryBreakdownRow
import com.nimo.ledger.util.Money

/** 环形图的外径：中间留 [DONUT_HOLE] 放合计，图上不放文字，名字看下面的「支出排行」 */
private val DONUT_SIZE = 180.dp

/** 环的粗细：太细看不出比例，太粗显得笨重 */
private val DONUT_STROKE = 26.dp

/** 环中间的洞（= 外径 − 两侧环宽）：合计文字就摆在这个宽度里 */
private val DONUT_HOLE = DONUT_SIZE - DONUT_STROKE * 2

/** 从 12 点方向开始顺时针画 */
private const val DONUT_START_ANGLE = -90f

internal const val DONUT_TEST_TAG = "report-category-donut"

/** 环心（合计）也是一个可断言的节点：它是"支出合计 + 金额"两行合成的一句话 */
internal const val DONUT_CENTER_TEST_TAG = "report-donut-center"

/** 无障碍描述的头一句（画布上没有可读文字）：读屏听到的是带数字的整句，测试用前缀匹配 */
internal const val DONUT_DESCRIPTION = "本月支出分类占比"

/**
 * 无障碍描述：环形图 + 环心合计读出来，再补金额最大的前三个分类（名字、金额、占比）——
 * 与看「支出排行」拿到的信息一致。占比文案走 [ReportFormatting.percent]，不在这里另写一套。
 */
internal fun donutDescription(rows: List<CategoryBreakdownRow>, totalCents: Long): String {
    if (rows.isEmpty() || totalCents <= 0L) return DONUT_DESCRIPTION
    val top = rows.take(3).joinToString("，") {
        "${it.name} ${Money.formatCurrency(it.amountCents)}（${ReportFormatting.percent(it.percent)}）"
    }
    val rest = if (rows.size > 3) "，其余 ${rows.size - 3} 个分类" else ""
    return "$DONUT_DESCRIPTION，合计 ${Money.formatCurrency(totalCents)}：$top$rest"
}

/**
 * 分类配色：暖色调，与 App 的主色系同族。
 * 分类多于配色数时按序循环——颜色只用来"对号"，具体数字一律看下面的排行。
 */
private val DONUT_COLORS = listOf(
    Color(0xFFB4643C),
    Color(0xFFD9924A),
    Color(0xFFE0B457),
    Color(0xFF8C9E5A),
    Color(0xFF5F8A72),
    Color(0xFF4F7C93),
    Color(0xFF6B6FA8),
    Color(0xFF9A6BA8),
    Color(0xFFC0576E),
    Color(0xFF8A8378),
)

/** 第 [index] 个分类（按金额降序）在环形图里用的颜色；下面的「支出排行」用同一套 */
internal fun donutColorAt(index: Int): Color = DONUT_COLORS[index % DONUT_COLORS.size]

/**
 * 分类占比环形图（F10）：本月支出按一级分类的占比。
 *
 * 图表用 Compose Canvas 自绘（计划 240 行：先自绘，暂不引图表库）。
 * 扇形角度按"分类金额 ÷ 全部分类金额"算——不用 CategoryBreakdownRow 里已四舍五入的
 * percent，否则所有扇形的角度加起来不正好是 360°，环会缺一个口子。
 * 口径本身仍来自 StatsCalculator.categoryBreakdown（铁律 3），这里只负责画。
 *
 * 名字与金额不重复列一遍：正下方的「支出排行」就是它的图例（占比条用了同一套颜色）。
 * [rows] 为空时整段不渲染，由调用方决定。
 */
@Composable
internal fun CategoryDonutSection(rows: List<CategoryBreakdownRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    val totalCents = rows.sumOf { it.amountCents }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CategoryDonutCanvas(rows = rows, totalCents = totalCents)
            DonutCenter(totalCents = totalCents)
        }
    }
}

@Composable
private fun CategoryDonutCanvas(rows: List<CategoryBreakdownRow>, totalCents: Long) {
    Canvas(
        modifier = Modifier
            .size(DONUT_SIZE)
            .testTag(DONUT_TEST_TAG)
            .semantics { contentDescription = donutDescription(rows, totalCents) },
    ) {
        if (totalCents <= 0L) return@Canvas
        val strokePx = DONUT_STROKE.toPx()
        val diameter = size.minDimension - strokePx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        var startAngle = DONUT_START_ANGLE
        rows.forEachIndexed { index, row ->
            val sweep = row.amountCents.toDouble() / totalCents * 360.0
            drawArc(
                color = donutColorAt(index),
                startAngle = startAngle,
                sweepAngle = sweep.toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx),
            )
            startAngle += sweep.toFloat()
        }
    }
}

/** 环中间的合计：这一圈扇形加起来是多少钱 */
@Composable
private fun DonutCenter(totalCents: Long) {
    Column(
        modifier = Modifier
            .width(DONUT_HOLE)
            .testTag(DONUT_CENTER_TEST_TAG)
            // 合成一个节点：读屏把"支出合计 + 金额"当一句话读
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "支出合计",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = Money.formatCurrency(totalCents),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
