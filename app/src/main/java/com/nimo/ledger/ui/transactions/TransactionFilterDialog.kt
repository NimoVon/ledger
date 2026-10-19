package com.nimo.ledger.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.filter.TransactionFilter
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.util.Dates
import com.nimo.ledger.util.Money
import java.time.LocalDate

/** 面板里正在选的那一端日期 */
private enum class DateField { From, To }

/**
 * 筛选面板（F06）：账户 / 分类（父分类命中其子分类）/ 金额区间 / 日期区间 / 备注关键词。
 *
 * 只负责收集条件，不查库（UI 不直接访问 DAO）：点「应用」把条件交给 ViewModel，
 * 匹配口径唯一实现在 [TransactionFilter.matches]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionFilterDialog(
    initial: TransactionFilter,
    options: FilterOptions,
    onDismiss: () -> Unit,
    onApply: (TransactionFilter) -> Unit,
    onReset: () -> Unit,
    focusKeyword: Boolean = false,
) {
    // 每次打开面板都从当前生效的条件出发（remember(initial)：条件变了要重建草稿）
    var accountIds by remember(initial) { mutableStateOf(initial.accountIds) }
    var categoryIds by remember(initial) { mutableStateOf(initial.categoryIds) }
    var minText by remember(initial) {
        mutableStateOf(initial.minAmountCents?.let { Money.format(it) }.orEmpty())
    }
    var maxText by remember(initial) {
        mutableStateOf(initial.maxAmountCents?.let { Money.format(it) }.orEmpty())
    }
    var keyword by remember(initial) { mutableStateOf(initial.noteKeyword) }
    var fromText by remember(initial) { mutableStateOf(initial.from?.toString().orEmpty()) }
    var toText by remember(initial) { mutableStateOf(initial.to?.toString().orEmpty()) }
    var picking by remember { mutableStateOf<DateField?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val keywordFocus = remember { FocusRequester() }
    // 从明细页的搜索图标进来时，关键词框直接拿焦点，省一次点击（T-5）。
    LaunchedEffect(focusKeyword) { if (focusKeyword) keywordFocus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("筛选") },
        text = {
            FilterDialogBody {
                SectionTitle("账户（可多选）")
                if (options.accounts.isEmpty()) {
                    HintText("还没有账户。")
                } else {
                    options.accounts.forEach { account ->
                        CheckRow(
                            label = account.name + if (account.archived) "（已归档）" else "",
                            checked = account.id in accountIds,
                            onToggle = { accountIds = accountIds.toggle(account.id) },
                        )
                    }
                }

                SectionTitle("分类（可多选）")
                HintText("选中父分类会同时命中它的子分类。")
                CategoryKind.entries.forEach { kind ->
                    val parents = options.parents(kind)
                    if (parents.isNotEmpty()) {
                        Text(
                            text = kindLabel(kind),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        parents.forEach { parent ->
                            CheckRow(
                                label = optionLabel(parent.name, parent.archived, parent.icon),
                                checked = parent.id in categoryIds,
                                onToggle = { categoryIds = categoryIds.toggle(parent.id) },
                            )
                            options.children(parent.id).forEach { child ->
                                CheckRow(
                                    label = optionLabel(child.name, child.archived, child.icon),
                                    checked = child.id in categoryIds,
                                    onToggle = { categoryIds = categoryIds.toggle(child.id) },
                                    indented = true,
                                )
                            }
                        }
                    }
                }

                SectionTitle("金额区间（元）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minText,
                        onValueChange = { minText = Money.normalizeAmountInput(it); error = null },
                        label = { Text("最低") },
                        prefix = { Text("¥") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxText,
                        onValueChange = { maxText = Money.normalizeAmountInput(it); error = null },
                        label = { Text("最高") },
                        prefix = { Text("¥") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }

                SectionTitle("日期区间")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateFieldBox(
                        placeholder = "起始",
                        value = fromText,
                        onPick = { picking = DateField.From },
                        onClear = { fromText = ""; error = null },
                        modifier = Modifier.weight(1f),
                    )
                    DateFieldBox(
                        placeholder = "结束",
                        value = toText,
                        onPick = { picking = DateField.To },
                        onClear = { toText = ""; error = null },
                        modifier = Modifier.weight(1f),
                    )
                }
                HintText("留空表示不限；填了日期区间就按区间查，不再看月份。")

                SectionTitle("备注关键词")
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it; error = null },
                    label = { Text("包含文字") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(keywordFocus),
                )

                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val outcome = validate(minText, maxText, fromText, toText)
                when (outcome) {
                    is ValidationError -> error = outcome.message
                    is Validated -> onApply(
                        TransactionFilter(
                            accountIds = accountIds,
                            categoryIds = categoryIds,
                            minAmountCents = outcome.minAmountCents,
                            maxAmountCents = outcome.maxAmountCents,
                            noteKeyword = keyword.trim(),
                            from = outcome.from,
                            to = outcome.to,
                        )
                    )
                }
            }) { Text("确定") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text("清空条件") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )

    // 选日期：与记账页同一套（rememberDatePickerState + Dates 转换）
    val pickingField = picking
    if (pickingField != null) {
        val current = if (pickingField == DateField.From) fromText else toText
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = current
                .takeIf { Dates.isValidIsoDate(it) }
                ?.let { Dates.utcMillisFromIsoDate(it) },
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        val iso = Dates.isoDateFromUtcMillis(millis)
                        if (pickingField == DateField.From) fromText = iso else toText = iso
                        error = null
                    }
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

internal sealed interface ValidationOutcome

internal data class Validated(
    val minAmountCents: Long?,
    val maxAmountCents: Long?,
    val from: LocalDate?,
    val to: LocalDate?,
) : ValidationOutcome

internal data class ValidationError(val message: String) : ValidationOutcome

/**
 * 校验并归一化输入。金额与日期都允许留空（表示不限）；
 * 上下限/起止写反了就互换，让"面板里填的"与"实际用于筛选的"永远一致。
 */
internal fun validate(
    minText: String,
    maxText: String,
    fromText: String,
    toText: String,
): ValidationOutcome {
    val min = if (minText.isBlank()) null else Money.parseCents(minText)
    if (minText.isNotBlank() && min == null) {
        return ValidationError("最低金额填得不对（例：100 或 100.50）")
    }
    val max = if (maxText.isBlank()) null else Money.parseCents(maxText)
    if (maxText.isNotBlank() && max == null) {
        return ValidationError("最高金额填得不对（例：100 或 100.50）")
    }
    val trimmedFrom = fromText.trim()
    if (trimmedFrom.isNotBlank() && !Dates.isValidIsoDate(trimmedFrom)) {
        return ValidationError("起始日期填得不对（例：2026-09-01）")
    }
    val trimmedTo = toText.trim()
    if (trimmedTo.isNotBlank() && !Dates.isValidIsoDate(trimmedTo)) {
        return ValidationError("结束日期填得不对（例：2026-09-01）")
    }
    val fromDate = trimmedFrom.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
    val toDate = trimmedTo.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) }
    val (start, end) = if (fromDate != null && toDate != null && fromDate > toDate) {
        toDate to fromDate
    } else {
        fromDate to toDate
    }
    return Validated(
        minAmountCents = if (min != null && max != null) minOf(min, max) else min,
        maxAmountCents = if (min != null && max != null) maxOf(min, max) else max,
        from = start,
        to = end,
    )
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private fun kindLabel(kind: CategoryKind): String =
    if (kind == CategoryKind.EXPENSE) "支出分类" else "收入分类"

private fun optionLabel(name: String, archived: Boolean, icon: String?): String {
    val prefix = icon?.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
    val suffix = if (archived) "（已停用）" else ""
    return prefix + name + suffix
}

/** 面板内容区的最大高度（真机上约等于半屏多一点） */
internal val FILTER_DIALOG_MAX_HEIGHT = 420.dp

/**
 * 筛选面板的内容容器：**先限高、再挂滚动，顺序不能反**。
 *
 * 反过来（verticalScroll 在外、heightIn 在内）滚动容器会先以无限高度测量内容、再被裁成
 * [FILTER_DIALOG_MAX_HEIGHT]，于是它自身高度就等于内容高度、滚动范围恒为 0：金额、日期、
 * 关键词这半截既看不见也点不到。真机上出过这个缺陷，抽成独立函数就是为了让
 * TransactionFilterDialogUiTest 能用 UI 测试把它钉住。
 */
@Composable
internal fun FilterDialogBody(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .heightIn(max = FILTER_DIALOG_MAX_HEIGHT)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    indented: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(start = if (indented) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DateFieldBox(
    placeholder: String,
    value: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text(value.ifBlank { placeholder })
        }
        if (value.isNotBlank()) {
            TextButton(onClick = onClear) { Text("清除") }
        }
    }
}
