package com.nimo.ledger.ui.reports

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.filter.TransactionFilter
import com.nimo.ledger.domain.stats.BudgetStatus
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.ui.components.EmptyState
import com.nimo.ledger.ui.components.EmptyTone
import com.nimo.ledger.ui.components.FAB_BOTTOM_RESERVE
import com.nimo.ledger.ui.components.LedgerProgressBar
import com.nimo.ledger.ui.components.LedgerSectionHeader
import com.nimo.ledger.ui.components.MonthSummaryBar
import com.nimo.ledger.ui.components.MonthSwitcher
import com.nimo.ledger.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 统计 / 月报（F07，阶段 3 · 报表）。
 *
 * 一屏回答三件事：本月花了多少（收入/支出/结余）、比上月多还是少（环比）、
 * 钱花在哪（分类支出排行）。所有数字来自 [ReportsViewModel]，页面一个数都不自己算
 * （铁律 3：统计口径唯一实现在 StatsCalculator）。
 *
 * 顶部是**统计范围**（月 / 近 N 个月 / 自然年 / 自选区间）：整页数字全部按它聚合，
 * 切换用 [MonthSwitcher] 的同一套外观（标签可点开 [ReportRangeDialog]），箭头按范围单位走。
 *
 * 点排行或账户分布的一行可以下钻：跳回明细页、带上"这一行 + 当前这个范围"的筛选条件，
 * 让用户自己核对该行背后的每一笔流水（计划 M3 验收：报表金额与流水逐笔对账一致）。
 *
 * @param pendingDeletionIds 正被暂存待删除的那几笔 id：倒计时内它们不进报表
 * @param onDrillDown 点某一行时要看的筛选条件，由 [ReportDrillDown] 翻译，宿主负责跳转
 */
/** 「本月洞察」卡片（S-5）的测试标记：日均 / 环比 / 预算三块被一张卡片包住，行间只有细分隔线 */
const val INSIGHT_CARD_TEST_TAG = "reports-insight-card"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReportsScreen(
    repository: LedgerRepository,
    pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
    onDrillDown: (TransactionFilter) -> Unit = {},
) {
    val viewModel: ReportsViewModel =
        viewModel(factory = ReportsViewModel.factory(repository, pendingDeletionIds))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 点范围标签打开范围弹层：快捷项（本月/近 N 个月/今年/去年）、指定月份、自定义起止日期
    var showRangeDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        MonthSwitcher(
            label = state.rangeLabel,
            onPrevious = viewModel::showPreviousRange,
            onNext = viewModel::showNextRange,
            onLabelClick = { showRangeDialog = true },
            previousLabel = "上一段",
            nextLabel = "下一段",
        )
        MonthSummaryBar(summary = state.summary, loading = state.isLoading)
        HorizontalDivider()

        val contentModifier = Modifier.weight(1f)
        // 分类色：与环形图同一套（CategoryPalette）——颜色跟分类 id 走，不跟排名走
        val categorySlots = resolveCategorySlots(state.breakdown.map { it.categoryId })
        when {
            // 错误优先于加载：读取失败时两条流都会 emit，别把用户永远按在转圈上
            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.errorMessage.orEmpty(),
                modifier = contentModifier,
                tone = EmptyTone.ERROR,
            )

            state.isLoading -> Box(contentModifier, Alignment.Center) { CircularProgressIndicator() }

            // 这段范围没记账、但趋势里还有数据：空状态照旧，下面把趋势图也放出来。
            // 不放的话，用户一切到空范围就看不到刚做好的趋势折线（F10）。
            state.isEmpty && state.trendHasData -> LazyColumn(
                modifier = contentModifier,
                contentPadding = PaddingValues(bottom = FAB_BOTTOM_RESERVE),
            ) {
                item(key = "empty-range") {
                    EmptyState(
                        title = state.emptyTitle,
                        message = state.emptyMessage,
                        modifier = Modifier.heightIn(min = 180.dp),
                    )
                }
                item(key = "trend-header") { LedgerSectionHeader("收支趋势") }
                item(key = "trend") { TrendBlock(state = state, viewModel = viewModel) }
            }

            // 空状态按范围表述：切到没有记录的范围不该看起来像坏了
            state.isEmpty -> EmptyState(
                title = state.emptyTitle,
                message = state.emptyMessage,
                modifier = contentModifier,
            )

            else -> LazyColumn(
                modifier = contentModifier,
                contentPadding = PaddingValues(bottom = FAB_BOTTOM_RESERVE),
            ) {
                // 段头改 stickyHeader（S-2）：五个段头钉在内容区顶沿，滑到「支出排行」不用再猜自己在看哪一段。
                // 段头本身是 surfaceVariant 实条（LedgerSectionHeader），钉住时挡住下面滚动的内容不会被看穿。
                stickyHeader(key = "insight-header") {
                    val hint = buildString {
                        append("日均 / 环比")
                        if (state.hasTotalBudget) append(" / 预算")
                    }
                    LedgerSectionHeader(state.insightTitle, hint = hint)
                }
                item(key = "insight") { InsightCard(state) }
                stickyHeader(key = "trend-header") { LedgerSectionHeader("收支趋势") }
                item(key = "trend") { TrendBlock(state = state, viewModel = viewModel) }
                // 先给个总览：谁占了大头；具体数字看下面的排行（占比条用同一套颜色对号）
                if (state.breakdown.isNotEmpty()) {
                    stickyHeader(key = "donut-header") {
                        LedgerSectionHeader("分类占比", hint = "点一块看明细")
                    }
                    item(key = "donut") {
                        CategoryDonutSection(
                            rows = state.breakdown,
                            // 读屏说的是「2026年10月支出分类占比」，跟着当前范围走，不再是写死的"本月"
                            label = state.rangeLabel,
                            onCategoryClick = { row ->
                                onDrillDown(ReportDrillDown.byCategory(state.range, row.categoryId))
                            },
                        )
                    }
                }
                stickyHeader(key = "breakdown-header") {
                    LedgerSectionHeader("支出排行", hint = "点一行看明细")
                }
                if (state.breakdown.isEmpty()) {
                    item(key = "breakdown-empty") {
                        HintRow(state.noExpenseHint)
                    }
                } else {
                    items(
                        items = state.breakdown,
                        key = { row -> "cat-${row.categoryId}" },
                    ) { row ->
                        BreakdownRow(
                            name = row.name,
                            amountCents = row.amountCents,
                            percent = row.percent,
                            barColor = CATEGORY_SLOT_COLORS[categorySlots.getValue(row.categoryId)],
                            onClick = {
                                onDrillDown(ReportDrillDown.byCategory(state.range, row.categoryId))
                            },
                        )
                    }
                }
                stickyHeader(key = "accounts-header") {
                    LedgerSectionHeader("账户支出分布", hint = "点一行看明细")
                }
                if (state.accountBreakdown.isEmpty()) {
                    item(key = "accounts-empty") {
                        HintRow(state.noAccountExpenseHint)
                    }
                } else {
                    items(items = state.accountBreakdown, key = { "acct-${it.accountId}" }) { row ->
                        BreakdownRow(
                            name = row.name,
                            amountCents = row.amountCents,
                            percent = row.percent,
                            // 账户分布不对应环形图，沿用主题色
                            barColor = MaterialTheme.colorScheme.primary,
                            onClick = {
                                onDrillDown(ReportDrillDown.byAccount(state.range, row.accountId))
                            },
                        )
                    }
                }
            }
        }
    }

    if (showRangeDialog) {
        ReportRangeDialog(
            current = state.range,
            onDismiss = { showRangeDialog = false },
            onSelect = { picked ->
                viewModel.setRange(picked)
                showRangeDialog = false
            },
        )
    }

    // R2：点了某一天的柱子 → 当日流水明细（行文/排序都来自 Repository 与 TransactionTimeline）
    state.dayDetail?.let { detail ->
        DayDetailDialog(state = detail, onDismiss = viewModel::clearSelectedDay)
    }
}

/** 趋势区（F10）：画法随范围走——单月/短区间是逐日柱（柱子可点开当天流水），其余按月折线 */
@Composable
private fun TrendBlock(state: ReportsUiState, viewModel: ReportsViewModel) {
    TrendSection(
        range = state.range,
        points = state.trend,
        daily = state.daily,
        isLoading = state.isTrendLoading,
        onDayClick = viewModel::setSelectedDay,
    )
}

/**
 * 本月洞察卡片（R2 / S-5）：日均支出 + 支出/收入环比 + 预算达成率，包进一张 surfaceContainerLow 卡片，
 * 行间只留「4dp 呼吸 + 1dp 细线」，细线从卡片左内缘 16dp 起画、和行里的文字左缘对齐。
 *
 * 预算达成率只在设了总预算时出现——没设总预算时它没有分母，显示 0% 会误导。
 */
@Composable
private fun InsightCard(state: ReportsUiState) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(INSIGHT_CARD_TEST_TAG),
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            DailyAverageRow(state.dailyAverageExpenseCents)
            InsightDivider()
            ChangeSection(state)
            if (state.hasTotalBudget) {
                InsightDivider()
                BudgetRateRow(state)
            }
        }
    }
}

/**
 * 卡片内的行间分隔：上下各 4dp 呼吸 + 1dp 细线，线只从 16dp 起画——
 * 和行里的文字左缘对齐，不切到卡片 16dp 圆角上（S-5 的「行间 4dp 细分隔线（start = 16dp）」）。
 */
@Composable
private fun InsightDivider() {
    Spacer(modifier = Modifier.height(4.dp))
    HorizontalDivider(
        thickness = 1.dp,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp),
    )
    Spacer(modifier = Modifier.height(4.dp))
}

/** 日均支出：一行。除法在 StatsCalculator，页面只负责显示 */
@Composable
private fun DailyAverageRow(amountCents: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "日均支出",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = Money.formatCurrency(amountCents),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * 预算达成率（计划附.1 R2）：本月支出 ÷ 总预算，比率由 BudgetCalculator 给出。
 * 除了颜色还写了百分比数字，不只靠颜色区分"预警/超支"。
 */
@Composable
private fun BudgetRateRow(state: ReportsUiState) {
    val budget = state.totalBudgetCents ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "预算达成率",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = ReportFormatting.percent(state.budgetAchievementRatio),
                style = MaterialTheme.typography.titleSmall,
                color = budgetRateColor(state.budgetStatus),
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "本月支出 ${Money.formatCurrency(state.summary.expenseCents)} / 总预算 ${Money.formatCurrency(budget)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun budgetRateColor(status: BudgetStatus): Color = when (status) {
    BudgetStatus.OVER -> MaterialTheme.colorScheme.error
    BudgetStatus.WARNING -> MaterialTheme.colorScheme.tertiary
    BudgetStatus.OK, BudgetStatus.NONE -> MaterialTheme.colorScheme.onSurface
}

/** 环比：支出与收入各一格。上月为 0 时显示 "—"（计划 3.4） */
@Composable
private fun ChangeSection(state: ReportsUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        ChangeCell(
            label = "支出环比",
            ratio = state.expenseChangeRatio,
            // 「跟谁比」随范围走：较上月 / 较上一年 / 较前6个月 / 较上一段
            comparedTo = state.range.previousLabel,
            // 支出涨是坏消息、收入涨是好消息，箭头方向一样，颜色不一样
            growthIsBad = true,
            modifier = Modifier.weight(1f),
        )
        ChangeCell(
            label = "收入环比",
            ratio = state.incomeChangeRatio,
            comparedTo = state.range.previousLabel,
            growthIsBad = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ChangeCell(
    label: String,
    ratio: Double?,
    comparedTo: String,
    growthIsBad: Boolean,
    modifier: Modifier = Modifier,
) {
    val color = when {
        ratio == null || ratio == 0.0 -> MaterialTheme.colorScheme.onSurfaceVariant
        growthIsBad == (ratio > 0) -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 箭头用图标而不是 ↑↓ 字符：字符基线随字体漂移，16dp 时尤其明显（§5.1 TrendUp / TrendDown）
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (ReportFormatting.changeDirection(ratio)) {
                ChangeDirection.UP -> {
                    Icon(
                        imageVector = LedgerIcons.ArrowUp,
                        contentDescription = "上升",
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }

                ChangeDirection.DOWN -> {
                    Icon(
                        imageVector = LedgerIcons.ArrowDown,
                        contentDescription = "下降",
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                }

                ChangeDirection.NONE -> Unit
            }
            Text(
                text = ReportFormatting.change(ratio, comparedTo),
                style = MaterialTheme.typography.titleSmall,
                color = color,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * 排行一行（分类排行与账户分布共用）：名字 + 占比 + 金额 + 占比条。
 * 整行可点：[onClick] 把"这一行 + 当前月份"下钻到明细页（F07）。
 *
 * @param barColor 占比条的颜色：分类排行用 [CATEGORY_SLOT_COLORS]（与环形图同色，看图能对上号），
 *   账户分布沿用主题色
 */
@Composable
private fun BreakdownRow(
    name: String,
    amountCents: Long,
    percent: Double?,
    barColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = ReportFormatting.percent(percent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = Money.formatCurrency(amountCents),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (percent != null) {
            Spacer(modifier = Modifier.height(6.dp))
            LedgerProgressBar(
                fraction = percent.toFloat(),
                color = barColor,
                height = 6.dp,
                shape = RoundedCornerShape(3.dp),
            )
        }
    }
}
