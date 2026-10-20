package com.nimo.ledger.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.ui.components.EmptyState
import com.nimo.ledger.ui.components.EmptyTone
import com.nimo.ledger.ui.components.FAB_BOTTOM_RESERVE
import com.nimo.ledger.ui.components.LedgerAmount
import com.nimo.ledger.ui.components.LedgerSectionHeader
import com.nimo.ledger.ui.DrillDownRequests
import com.nimo.ledger.ui.components.MonthSummaryBar
import com.nimo.ledger.ui.components.SkeletonList
import com.nimo.ledger.ui.components.MonthSwitcher
import com.nimo.ledger.ui.components.YearMonthPickerDialog
import com.nimo.ledger.ui.components.stackAtLargeFont
import com.nimo.ledger.util.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 明细（流水时间线，F05）+ 筛选与搜索（F06）。
 *
 * 按日分组倒序展示，每行给出分类图标与名称、账户、备注、金额；
 * 顶部按月切换（月份边界来自 [com.nimo.ledger.util.Dates.monthBounds]），
 * 合计来自 [com.nimo.ledger.domain.stats.StatsCalculator]（转账/余额调整不计入）。
 *
 * 筛选条件由 [TransactionFilterDialog] 收集、[TransactionsViewModel] 执行；
 * 开了日期区间就不再显示月份控件（区间优先），条件摘要贴在「筛选」按钮右侧。
 *
 * 点某一行进入「记一笔」的编辑模式（F03 修改）；删除入口也在那一页，
 * 删除后这一行会立刻消失、合计立刻回退，底部提示条里可撤销（计划 9.2 延迟物理删除）。
 *
 * @param pendingDeletionIds 正被暂存待删除的那几笔 id：倒计时内它们不在列表也不在合计里
 */
@Composable
fun TransactionsScreen(
    repository: LedgerRepository,
    onTransactionClick: (Long) -> Unit = {},
    /** 点转账行：转账走自己那套表单（F04），落到记一笔会变成"这一笔不能在这里改" */
    onTransferClick: (Long) -> Unit = {},
    pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
    drillDown: DrillDownRequests? = null,
) {
    val appContext = LocalContext.current.applicationContext
    val rowTapHintStore = remember(appContext) { RowTapHintStore.from(appContext) }
    val viewModel: TransactionsViewModel =
        viewModel(
            factory = TransactionsViewModel.factory(
                repository, pendingDeletionIds, drillDown, rowTapHintStore,
            ),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // T-3：首启且本机有流水时，列表顶部出现一次性「点行=编辑」提示
    val showRowTapHint by viewModel.showRowTapHint.collectAsStateWithLifecycle()
    var showFilterDialog by remember { mutableStateOf(false) }
    // 从搜索图标进来时让关键词框直接拿焦点（T-5）；点「筛选」按钮则为 false。
    var focusFilterKeyword by remember { mutableStateOf(false) }
    // R1 日期直改：点月份标签打开年月选择器
    var showMonthPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.showsMonthSwitcher) {
            MonthSwitcher(
                label = state.monthLabel,
                onPrevious = viewModel::showPreviousMonth,
                onNext = viewModel::showNextMonth,
                onLabelClick = { showMonthPicker = true },
            )
        }
        MonthSummaryBar(summary = state.summary, loading = state.isLoading)
        FilterBar(
            chips = state.filterChips,
            onOpen = {
                focusFilterKeyword = false
                showFilterDialog = true
            },
            onSearch = {
                focusFilterKeyword = true
                showFilterDialog = true
            },
            onClear = viewModel::clearFilter,
        )
        HorizontalDivider()

        val contentModifier = Modifier.weight(1f)
        when {
            state.isLoading -> SkeletonList(modifier = contentModifier)

            state.errorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.errorMessage.orEmpty(),
                modifier = contentModifier,
                tone = EmptyTone.ERROR,
            )

            // 筛选态下"没有记录"是条件的问题，不是这个月没记账——措辞要分开，否则用户以为数据丢了
            state.isEmpty && state.hasActiveFilter -> EmptyState(
                title = "没有符合条件的记录",
                message = "试试放宽条件，或点上面的「清空」。",
                modifier = contentModifier,
                tone = EmptyTone.FILTERED,
            )

            // 空状态按"本月"表述：切到没有记录的月份不该看起来像坏了
            state.isEmpty -> EmptyState(
                title = "本月还没有记录",
                message = "点右下角「记一笔」，3 秒记下第一笔。",
                modifier = contentModifier,
            )

            else -> LazyColumn(
                modifier = contentModifier,
                contentPadding = PaddingValues(bottom = FAB_BOTTOM_RESERVE),
            ) {
                if (showRowTapHint && state.groups.isNotEmpty()) {
                    item(key = "row-tap-hint") {
                        RowTapHintRow(onDismiss = { viewModel.dismissRowTapHint() })
                    }
                }
                state.groups.forEach { group ->
                    item(key = "day-" + group.date) { DayHeader(group) }
                    items(items = group.rows, key = { it.id }) { row ->
                        TransactionRowItem(
                            row = row,
                            // 转账和收支是两种表单：按类型分流，各进各的编辑页
                            onClick = {
                                if (row.type == TransactionType.TRANSFER) {
                                    onTransferClick(row.id)
                                } else {
                                    onTransactionClick(row.id)
                                }
                            },
                        )
                        HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
                    }
                }
            }
        }
    }

    if (showMonthPicker) {
        YearMonthPickerDialog(
            current = state.month,
            onDismiss = { showMonthPicker = false },
            onSelect = { picked ->
                viewModel.setMonth(picked)
                showMonthPicker = false
            },
        )
    }

    if (showFilterDialog) {
        TransactionFilterDialog(
            initial = state.filter,
            options = state.filterOptions,
            focusKeyword = focusFilterKeyword,
            onDismiss = {
                showFilterDialog = false
                focusFilterKeyword = false
            },
            onApply = { filter ->
                viewModel.applyFilter(filter)
                showFilterDialog = false
                focusFilterKeyword = false
            },
            onReset = {
                viewModel.clearFilter()
                showFilterDialog = false
                focusFilterKeyword = false
            },
        )
    }
}

/** 筛选条测试标签：整条（高度上限）与 chips 横滚区（可滚动） */
internal const val FILTER_BAR_TEST_TAG = "filter_bar"
internal const val FILTER_CHIPS_ROW_TEST_TAG = "filter_chips_row"

/**
 * 单行筛选条的高度上限：Material3 按钮的 48dp 触控目标（视觉 40dp）决定整条高度，
 * 外面不再加竖向内边距；留 4dp 余量给字体缩放。条件再多也只占这一条。
 */
internal const val FILTER_BAR_MAX_HEIGHT_DP = 52

/**
 * 筛选入口 + 当前条件摘要（设计规范 §8 T-1 / T-2，批 E · E-1）。
 *
 * 条件文字由 [TransactionFilterSummary] 生成（页面不自己拼字符串），开条件后才出现「清空」。
 * 布局固定一行：左「筛选」按钮、中间横滚的条件 chips、行尾「清空」——
 * 之前 chips 是竖排文字列，条件越多这一条越高，列表首屏被挤掉；
 * 现在高度只取决于「一行」而不是条件数量（[FILTER_BAR_MAX_HEIGHT_DP] 守住这条线）。
 */
@Composable
internal fun FilterBar(
    chips: List<String>,
    onOpen: () -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    // 字体 ≥1.5：按钮 + 清空占一行、条件 chips 独占下一行。横排时 chips 只剩「按钮和清空之间」那点宽度，
    // 1.5 倍下 chip 会被 LazyRow 边界从字形中间裁断（E-1 真机截图里「支付宝」只剩「支付」+半个字）。
    val stack = stackAtLargeFont(LocalDensity.current.fontScale)
    val barModifier = Modifier
        .fillMaxWidth()
        .testTag(FILTER_BAR_TEST_TAG)
        .padding(horizontal = 16.dp)
    if (stack) {
        Column(modifier = barModifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterSearchButton(onSearch = onSearch)
                FilterOpenButton(onOpen = onOpen)
                if (chips.isNotEmpty()) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
            if (chips.isNotEmpty()) FilterChipsRow(chips = chips, modifier = Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier = barModifier, verticalAlignment = Alignment.CenterVertically) {
            FilterSearchButton(onSearch = onSearch)
            FilterOpenButton(onOpen = onOpen)
            if (chips.isNotEmpty()) {
                Spacer(modifier = Modifier.width(8.dp))
                FilterChipsRow(chips = chips, modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(onClick = onClear) { Text("清空") }
            }
        }
    }
}

@Composable
private fun FilterSearchButton(onSearch: () -> Unit) {
    IconButton(onClick = onSearch) {
        Icon(
            imageVector = LedgerIcons.Search,
            contentDescription = "搜索",
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FilterOpenButton(onOpen: () -> Unit) {
    OutlinedButton(onClick = onOpen) {
        Icon(
            imageVector = LedgerIcons.Filter,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text("筛选")
    }
}

/** 条件 chips：横滚而不是换行——条件再多也只占一行（竖排时这一行独占整宽） */
@Composable
private fun FilterChipsRow(chips: List<String>, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.testTag(FILTER_CHIPS_ROW_TEST_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(items = chips) { chip -> FilterSummaryChip(text = chip) }
    }
}

/**
 * 一条条件：12dp 圆角 + surfaceContainerHigh 底（规范 §4.2 圆角矩阵 / §2 强调容器色阶）。
 *
 * 纯展示，不可点——所以要的不是 44dp 触控区，而是「一行放得下」：文字不换行、不加省略号，
 * 长条件由外层 [LazyRow] 横向滚动来露出。
 */
@Composable
private fun FilterSummaryChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/**
 * 日段头：日期 + 当日合计。
 *
 * 与统计页的段头共用 [LedgerSectionHeader]；当日合计可能要同时给出支出和收入，
 * 一行 [String] 的 hint 放不下，所以走 trailing 槽位（字号颜色仍是段头的小字规格）。
 */
@Composable
internal fun DayHeader(group: TransactionDayGroup) {
    val hasTotals = group.expenseCents > 0L || group.incomeCents > 0L
    // 字体 ≥1.5：日期独占一行，支出/收入竖排在下面。横排时日期拿 weight(1f)，
    // 「支出 ¥972.00」只能挤在剩下的宽度里，2.0 倍下会被折断成两行。
    val stack = stackAtLargeFont(LocalDensity.current.fontScale)
    @Composable
    fun Totals(vertical: Boolean) {
        // AA-5：给读屏的 spoken 与屏幕文本分开备一份，念「支出 972 元」而不是「支出 ¥ 九 七 二 点 零 零」
        val totals = buildList {
            if (group.expenseCents > 0L) add("支出" to group.expenseCents)
            if (group.incomeCents > 0L) add("收入" to group.incomeCents)
        }
        // T-6：日段头补「N 笔」——只有合计没有笔数，看不出这天记了几笔
        val count = "${group.rows.size} 笔"
        val showCount = group.rows.isNotEmpty()
        if (vertical) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                totals.forEach { (label, cents) -> DayTotalText(label, cents) }
                if (showCount) DayCountText(count)
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                totals.forEach { (label, cents) -> DayTotalText(label, cents) }
                if (showCount && totals.isNotEmpty()) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showCount) DayCountText(count)
            }
        }
    }
    val trailing: (@Composable () -> Unit)? = if (hasTotals || group.rows.isNotEmpty()) {
        { Totals(vertical = stack) }
    } else {
        null
    }
    LedgerSectionHeader(title = group.label, stack = stack, trailing = trailing)
}

@Composable
private fun DayCountText(count: String) {
    Text(
        text = count,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DayTotalText(label: String, cents: Long) {
    Text(
        text = "$label " + Money.formatCurrency(cents),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        // AA-5：读屏念「支出 972 元」，不逐字念货币符号与小数点
        modifier = Modifier.semantics { contentDescription = "$label " + Money.spokenAmount(cents) },
    )
}

/** 一行流水：分类图标 + 名称 / 账户 · 备注 / 金额 */
@Composable
private fun TransactionRowItem(row: TransactionRow, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 编辑 / 删除 + 撤销的入口（F03 修改）
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.categoryIcon,
            fontSize = 20.sp,
            modifier = Modifier.width(36.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = row.categoryName, style = MaterialTheme.typography.bodyLarge)
            val secondary = listOfNotNull(
                row.accountLabel,
                row.note?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 金额列统一走 LedgerAmount（规范 §6.3 / G-5）：96dp 起对齐、颜色与符号一处维护
        LedgerAmount(cents = row.amountCents, type = row.type, showSign = true)
    }
}

/** T-3 一次性提示的测试标记 */
const val ROW_TAP_HINT_TEST_TAG = "transactions-row-tap-hint"

@Composable
private fun RowTapHintRow(onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ROW_TAP_HINT_TEST_TAG),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "点任意一行可修改这一笔",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}
