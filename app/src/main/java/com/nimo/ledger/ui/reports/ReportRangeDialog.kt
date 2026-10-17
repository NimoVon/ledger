package com.nimo.ledger.ui.reports

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.ReportRange
import com.nimo.ledger.ui.components.YearMonthPickerDialog
import com.nimo.ledger.util.Dates
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 统计范围弹层（R2 后续）：整页只认一个范围，所以这里选完就整页生效 */
const val RANGE_DIALOG_TEST_TAG: String = "reports-range-dialog"

/** 自定义区间的起止日期输入框（只读 + 点开日期选择器） */
const val RANGE_FROM_TEST_TAG: String = "reports-range-from"
const val RANGE_TO_TEST_TAG: String = "reports-range-to"

/** 自定义区间的「应用」按钮：起止日期都合法才可点 */
const val RANGE_APPLY_TEST_TAG: String = "reports-range-apply"

/** 快捷范围 chip 的前缀：tag = reports-range-quick-<key>，便于 UI 测试逐个点到 */
const val RANGE_QUICK_PREFIX: String = "reports-range-quick-"

/**
 * 快捷范围：把"统计多久"从三档开关换成一份完整清单。
 *
 * 这里只放**口径没有歧义**的快捷项：近 N 个月是滚动窗口，今年/去年是自然年
 * （1 月 1 日到 12 月 31 日，未来的月份画 0，不随今天缩短）。两者同时存在，
 * 是因为用户说「近一年」和「今年」时想的往往是两件事。
 */
internal fun quickRanges(today: LocalDate): List<Pair<String, ReportRange>> {
    val thisMonth = YearMonth.from(today)
    return listOf(
        "本月" to ReportRange.Month(thisMonth),
        "近3个月" to ReportRange.LastMonths(thisMonth, 3),
        "近6个月" to ReportRange.LastMonths(thisMonth, 6),
        "近12个月" to ReportRange.LastMonths(thisMonth, 12),
        "今年" to ReportRange.CalendarYear(today.year),
        "去年" to ReportRange.CalendarYear(today.year - 1),
    )
}

/** 快捷项在 chip 上的 tag */
internal fun quickTag(range: ReportRange): String = RANGE_QUICK_PREFIX + when (range) {
    is ReportRange.Month -> "month"
    is ReportRange.LastMonths -> "last${range.count}"
    is ReportRange.CalendarYear -> "year-${range.year}"
    is ReportRange.Custom -> "custom"
}

/**
 * 范围选择弹层：快捷项点一下立刻生效；「指定月份…」走两步选年月；
 * 自定义区间要填起止日期再点「应用」——区间可能很长，验完再切换。
 *
 * @param current 当前范围：用来高亮已选项，也决定自定义框的初值
 * @param today 今天（测试可注入，避免跨天抖动）
 * @param onSelect 选中新范围（调用方负责 setRange 并关闭弹窗）
 */
@Composable
fun ReportRangeDialog(
    current: ReportRange,
    today: LocalDate = LocalDate.now(),
    onDismiss: () -> Unit,
    onSelect: (ReportRange) -> Unit,
) {
    var showMonthPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(RANGE_DIALOG_TEST_TAG),
        title = { Text("统计范围") },
        text = {
            ReportRangePickerBody(
                current = current,
                today = today,
                onQuickPick = onSelect,
                onPickMonth = { showMonthPicker = true },
                onApplyCustom = { start, end -> onSelect(ReportRange.Custom(start, end)) },
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showMonthPicker) {
        YearMonthPickerDialog(
            current = current.months.last(),
            onDismiss = { showMonthPicker = false },
            onSelect = { picked ->
                showMonthPicker = false
                onSelect(ReportRange.Month(picked))
            },
        )
    }
}

/**
 * 弹层内容（拆出来单独可测：AlertDialog 外壳在 Robolectric 里渲染成本高，
 * 而"快捷项 + 自定义区间校验"这条交互本身必须被回归测试守住）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReportRangePickerBody(
    current: ReportRange,
    today: LocalDate,
    onQuickPick: (ReportRange) -> Unit,
    onPickMonth: () -> Unit,
    onApplyCustom: (LocalDate, LocalDate) -> Unit,
) {
    val custom = current as? ReportRange.Custom
    var fromText by remember { mutableStateOf(custom?.start?.toString().orEmpty()) }
    var toText by remember { mutableStateOf(custom?.endInclusive?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<PickField?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            quickRanges(today).forEach { (label, range) ->
                FilterChip(
                    selected = current == range,
                    onClick = { onQuickPick(range) },
                    label = { Text(label) },
                    modifier = Modifier.testTag(quickTag(range)),
                )
            }
        }

        TextButton(onClick = onPickMonth, modifier = Modifier.testTag("${RANGE_QUICK_PREFIX}pick-month")) {
            Text("指定月份…")
        }

        Text("自定义区间", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            DateField(
                label = "开始",
                value = fromText,
                onPick = { picking = PickField.FROM },
                onClear = { fromText = ""; error = null },
                modifier = Modifier.weight(1f).testTag(RANGE_FROM_TEST_TAG),
            )
            DateField(
                label = "结束",
                value = toText,
                onPick = { picking = PickField.TO },
                onClear = { toText = ""; error = null },
                modifier = Modifier.weight(1f).testTag(RANGE_TO_TEST_TAG),
            )
        }

        val message = error ?: customHint(fromText, toText)
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val from = parseDate(fromText)
        val to = parseDate(toText)
        val ready = from != null && to != null
        TextButton(
            onClick = {
                val (start, end) = validateCustom(fromText, toText)
                if (start != null && end != null) {
                    onApplyCustom(start, end)
                } else {
                    error = customError(fromText, toText)
                }
            },
            enabled = ready,
            modifier = Modifier.testTag(RANGE_APPLY_TEST_TAG),
        ) {
            Text("应用")
        }
    }

    when (picking) {
        null -> Unit
        else -> {
            val field = picking
            val initial = if (field == PickField.FROM) fromText else toText
            val pickerState = rememberDatePickerState(initialSelectedDateMillis = Dates.utcMillisFromIsoDate(initial))
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pickerState.selectedDateMillis?.let { millis ->
                                val iso = Dates.isoDateFromUtcMillis(millis)
                                if (field == PickField.FROM) fromText = iso else toText = iso
                            }
                            error = null
                            picking = null
                        },
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } },
            ) {
                DatePicker(state = pickerState)
            }
        }
    }
}

private enum class PickField { FROM, TO }

/** 自定义区间可选的边界值（与 ReportRange.CUSTOM_MAX_DAYS 同源） */
internal fun parseDate(iso: String): LocalDate? =
    if (iso.isBlank()) null else runCatching { LocalDate.parse(iso) }.getOrNull()

/** 起止日期是否合法（可空：任一为空时靠按钮置灰拦，不算错误） */
internal fun validateCustom(fromText: String, toText: String): Pair<LocalDate?, LocalDate?> {
    val from = parseDate(fromText)
    val to = parseDate(toText)
    if (from == null || to == null) return null to null
    if (to.isBefore(from)) return null to null
    if (ChronoUnit.DAYS.between(from, to) + 1 > ReportRange.CUSTOM_MAX_DAYS) return null to null
    return from to to
}

internal fun customError(fromText: String, toText: String): String? {
    val from = parseDate(fromText)
    val to = parseDate(toText)
    if (from == null || to == null) return null
    if (to.isBefore(from)) return "开始日期不能晚于结束日期"
    if (ChronoUnit.DAYS.between(from, to) + 1 > ReportRange.CUSTOM_MAX_DAYS) {
        return "区间最长 ${ReportRange.CUSTOM_MAX_DAYS} 天"
    }
    return null
}

/** 自定义区间的提示：说明"多长会换画法"，而不是等用户切过去才发现图变了 */
internal fun customHint(fromText: String, toText: String): String? {
    val from = parseDate(fromText)
    val to = parseDate(toText)
    if (from == null || to == null) return "起止日期都填了才能应用；按自然日，含两端。"
    val days = ChronoUnit.DAYS.between(from, to) + 1
    val paint = if (days <= ReportRange.CUSTOM_DAY_LIMIT_DAYS) "按天画柱" else "按月折线"
    return "${days} 天，${paint}；起止日期都含。"
}

/**
 * 日期框：只读展示 + 点开日期选择器。
 *
 * 不用 readOnly 的 TextField——只读输入框会自己吃掉点击手势，外层 clickable 收不到事件；
 * 用 OutlinedCard 当外壳，"点哪都能开选择器"这件事才是真的。
 */
@Composable
private fun DateField(
    label: String,
    value: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(onClick = onPick, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = value.ifEmpty { "选择" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (value.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (value.isNotEmpty()) {
                Text(
                    text = "×",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .clickable(onClick = onClear)
                        .padding(8.dp),
                )
            }
        }
    }
}
