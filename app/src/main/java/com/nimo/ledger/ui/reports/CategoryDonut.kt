package com.nimo.ledger.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.CategoryBreakdownRow
import com.nimo.ledger.ui.theme.TooltipShape
import com.nimo.ledger.util.Money
import kotlin.math.atan2
import kotlin.math.hypot

/** 环形图的外径：中间留 [DONUT_HOLE] 放合计，图上不放文字，名字看下面的「支出排行」 */
private val DONUT_SIZE = 180.dp

/** 环的粗细：太细看不出比例，太粗显得笨重 */
private val DONUT_STROKE = 26.dp

/** 相邻扇区之间露出的底色缝：2dp（半径 77dp 时约 1.49°）。形状边界承载信息，不只靠颜色 */
private val DONUT_SEAM = 2.dp

/** 环中间的洞（= 外径 − 两侧环宽）：合计文字就摆在这个宽度里 */
private val DONUT_HOLE = DONUT_SIZE - DONUT_STROKE * 2

/** 从 12 点方向开始顺时针画 */
internal const val DONUT_START_ANGLE_DEG = -90f

internal const val DONUT_TEST_TAG = "report-category-donut"

/** 环心（合计）也是一个可断言的节点：它是"支出合计 + 金额"两行合成的一句话 */
internal const val DONUT_CENTER_TEST_TAG = "report-donut-center"

/** 点中一块分类后弹出的占比气泡（R3 优化）：先看数，再点它才下钻——与柱状图同一套交互语言 */
internal const val DONUT_TOOLTIP_TEST_TAG = "report-donut-tooltip"

/** 无障碍描述的头一句（画布上没有可读文字）：读屏听到的是带数字的整句，测试用前缀匹配 */
internal const val DONUT_DESCRIPTION = "本月支出分类占比"

/** 描述头里的「这是哪段范围的占比」：统计页传当前范围标签，别处默认单月口径 */
private const val DONUT_DEFAULT_LABEL = "本月"

/** 没被选中的扇形压暗到这个透明度：选中那块一眼能认出来，也不至于脏 */
private const val DONUT_DIM_ALPHA = 0.35f

/** 选中扇形的描边加粗量：颜色之外再加一层形状反馈 */
private val DONUT_SELECTED_STROKE_BONUS = 6.dp

/**
 * 无障碍描述：环形图 + 环心合计读出来，再补金额最大的前三个分类（名字、金额、占比）——
 * 与看「支出排行」拿到的信息一致。占比文案走 [ReportFormatting.percent]，不在这里另写一套。
 */
internal fun donutDescription(
    rows: List<CategoryBreakdownRow>,
    totalCents: Long,
    label: String = DONUT_DEFAULT_LABEL,
): String {
    val head = label + "支出分类占比"
    if (rows.isEmpty() || totalCents <= 0L) return head
    val top = rows.take(3).joinToString("，") {
        "${it.name} ${Money.formatCurrency(it.amountCents)}（${ReportFormatting.percent(it.percent)}）"
    }
    val rest = if (rows.size > 3) "，其余 ${rows.size - 3} 个分类" else ""
    return "$head，合计 ${Money.formatCurrency(totalCents)}：$top$rest"
}

/**
 * 一个扇形的起止角（度）：0 度在 12 点方向，顺时针为正。
 * 单独抽出来是为了让"角度怎么算"能纯函数测，不用靠看截图数格子。
 */
internal data class DonutArc(val startAngleDeg: Float, val sweepAngleDeg: Float)

/** [seamPx] 长的弧在半径 [radiusPx] 的圆上占多少度：色缝宽度换算成角度用 */
internal fun seamAngleDeg(seamPx: Float, radiusPx: Float): Float =
    if (radiusPx <= 0f) 0f else Math.toDegrees((seamPx / radiusPx).toDouble()).toFloat()

/**
 * 按金额占比算每个扇形的角度，并在相邻两块之间留出 [seamAngleDeg] 的缝。
 *
 * 缝是"吃掉"每块末尾的宽度、整块前移半个缝——所以 n 块加起来仍是满满的 360°，
 * 不会因为留缝让环看起来少一截。只有一块时不留缝：单色环上凭空一道缺口像画坏了。
 *
 * @param seamAngleDeg 缝隙张角；调用方按"2dp 在真实半径上的弧长"算好传进来
 */
internal fun donutArcs(
    rows: List<CategoryBreakdownRow>,
    totalCents: Long,
    seamAngleDeg: Float,
): List<DonutArc> {
    if (rows.isEmpty() || totalCents <= 0L) return emptyList()
    val seam = if (rows.size <= 1) 0f else seamAngleDeg.coerceIn(0f, 90f)
    var cursor = DONUT_START_ANGLE_DEG
    return rows.map { row ->
        val sweep = (row.amountCents.toDouble() / totalCents * 360.0).toFloat().coerceAtLeast(0f)
        val arc = DonutArc(
            startAngleDeg = cursor + seam / 2f,
            sweepAngleDeg = (sweep - seam).coerceAtLeast(0f),
        )
        cursor += sweep
        arc
    }
}

/**
 * 点按落在环的哪一块：返回 [rows] 的下标；点在环心的洞里或环外返回 null（视作"取消选中"）。
 *
 * 环从 12 点方向顺时针画（[DONUT_START_ANGLE_DEG]），所以先把 atan2 的角度 +90° 再取模，
 * 折算成"从 12 点起顺时针转过多少度"，然后按各分类的角度宽度累加找落点。
 * 半径上留 [strokePx] / 3 的容错：手指点偏一点也算命中，但点在洞里不算——环心是"支出合计"。
 * 纯函数（不碰 Compose），命中判定单独测：画布上能不能点、点得准不准，不该只靠看截图。
 */
internal fun donutSliceAt(
    rows: List<CategoryBreakdownRow>,
    totalCents: Long,
    tapX: Float,
    tapY: Float,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    strokePx: Float,
): Int? {
    if (rows.isEmpty() || totalCents <= 0L) return null
    val centerX = canvasWidthPx / 2f
    val centerY = canvasHeightPx / 2f
    val dx = tapX - centerX
    val dy = tapY - centerY
    val radius = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    val pathRadius = (minOf(canvasWidthPx, canvasHeightPx) - strokePx) / 2f
    val slack = strokePx / 3f
    val inner = (pathRadius - strokePx / 2f - slack).coerceAtLeast(0f)
    val outer = pathRadius + strokePx / 2f + slack
    if (radius < inner || radius > outer) return null
    val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
    val clockwise = ((degrees + 90.0) % 360.0 + 360.0) % 360.0
    var swept = 0.0
    rows.forEachIndexed { index, row ->
        swept += row.amountCents.toDouble() / totalCents * 360.0
        if (clockwise < swept) return index
    }
    // 浮点误差：贴着最后一个扇区末端的一点点，归最后一块，不当作"点了空白"
    return rows.lastIndex
}

/**
 * 分类占比环形图（F10）：本月支出按一级分类的占比。
 *
 * 图表用 Compose Canvas 自绘（计划 240 行：先自绘，暂不引图表库）。
 * 扇形角度按"分类金额 ÷ 全部分类金额"算——不用 CategoryBreakdownRow 里已四舍五入的
 * percent，否则所有扇形的角度加起来不正好是 360°，环会缺一个口子。
 * 口径本身仍来自 StatsCalculator.categoryBreakdown（铁律 3），这里只负责画。
 *
 * 名字与金额不重复列一遍：正下方的「支出排行」就是它的图例（占比条用了同一套颜色）。
 * 点一块 → 选中并弹出占比气泡 → 点气泡才下钻（[onCategoryClick]）。与「点柱子先出气泡」
 * 是同一套交互语言：图表上先给一眼能看见的数，再进明细。
 * [rows] 为空时整段不渲染，由调用方决定。
 */
@Composable
internal fun CategoryDonutSection(
    rows: List<CategoryBreakdownRow>,
    modifier: Modifier = Modifier,
    onCategoryClick: (CategoryBreakdownRow) -> Unit = {},
    label: String = DONUT_DEFAULT_LABEL,
) {
    if (rows.isEmpty()) return
    val totalCents = rows.sumOf { it.amountCents }
    // 选中的那一块：再点一次同一块 = 收起；换月份（rows 变了）时自动清空
    var selectedIndex by remember(rows) { mutableStateOf<Int?>(null) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            CategoryDonutCanvas(
                rows = rows,
                totalCents = totalCents,
                selectedIndex = selectedIndex,
                label = label,
                onSelect = { picked -> selectedIndex = if (picked == selectedIndex) null else picked },
            )
            DonutCenter(totalCents = totalCents)
        }
        selectedIndex?.let { index ->
            rows.getOrNull(index)?.let { row ->
                DonutTooltip(row = row, onClick = { onCategoryClick(row) })
            }
        }
    }
}

@Composable
private fun CategoryDonutCanvas(
    rows: List<CategoryBreakdownRow>,
    totalCents: Long,
    selectedIndex: Int?,
    label: String,
    onSelect: (Int?) -> Unit,
) {
    // 颜色跟分类 id 走（跨月不跳色）；同一张图里撞槽时按 id 升序避让，与「支出排行」算出来一致
    val slots = resolveCategorySlots(rows.map { it.categoryId })
    Canvas(
        modifier = Modifier
            .size(DONUT_SIZE)
            .testTag(DONUT_TEST_TAG)
            .semantics { contentDescription = donutDescription(rows, totalCents, label) }
            // 命中判定在 donutSliceAt（纯函数、单独测）；点洞里或环外 = 取消选中
            .pointerInput(rows, totalCents) {
                detectTapGestures { offset ->
                    onSelect(
                        donutSliceAt(
                            rows = rows,
                            totalCents = totalCents,
                            tapX = offset.x,
                            tapY = offset.y,
                            canvasWidthPx = size.width.toFloat(),
                            canvasHeightPx = size.height.toFloat(),
                            strokePx = DONUT_STROKE.toPx(),
                        ),
                    )
                }
            },
    ) {
        if (totalCents <= 0L) return@Canvas
        val strokePx = DONUT_STROKE.toPx()
        val diameter = size.minDimension - strokePx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val arcs = donutArcs(
            rows = rows,
            totalCents = totalCents,
            seamAngleDeg = seamAngleDeg(DONUT_SEAM.toPx(), diameter / 2f),
        )
        rows.forEachIndexed { index, row ->
            val selected = index == selectedIndex
            drawArc(
                // 选中的一块保持原色，其余的压暗：颜色 + 描边粗细两层反馈，不只靠颜色
                color = CATEGORY_SLOT_COLORS[slots.getValue(row.categoryId)]
                    .copy(alpha = if (selectedIndex == null || selected) 1f else DONUT_DIM_ALPHA),
                startAngle = arcs[index].startAngleDeg,
                sweepAngle = arcs[index].sweepAngleDeg,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = if (selected) strokePx + DONUT_SELECTED_STROKE_BONUS.toPx() else strokePx,
                ),
            )
        }
    }
}

/**
 * 点中一块分类后的占比气泡：分类名 + 金额 + 占比 + 「查看明细」。
 * 整块可点，点它才把"该分类 + 当前月份"下钻到明细页（F07 的同一条链路，由宿主跳转）。
 */
@Composable
private fun DonutTooltip(row: CategoryBreakdownRow, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .testTag(DONUT_TOOLTIP_TEST_TAG)
            .clickable(onClick = onClick),
        shape = TooltipShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = row.name, style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "查看明细",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = "${Money.formatCurrency(row.amountCents)} · ${ReportFormatting.percent(row.percent)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
