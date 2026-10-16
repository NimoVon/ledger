package com.nimo.ledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nimo.ledger.ui.LedgerIcons
import java.time.YearMonth

/**
 * 年月选择器弹窗（R1 日期直改）：明细页与统计页点月份标签后打开。
 *
 * 不用 Material3 的 DatePicker year-mode：这里的终点语义是"年月"而不是"日期"，
 * 用户还要为它多担一次"选日"的歧义（计划 附.1 R1 已定口径）。自绘只有两屏：
 * 年份格 → 月份格，选到月份立刻回调并关闭。
 *
 * 未来年月不加限制：与"记一笔"允许记未来日期保持一致（附.1 R1）。
 *
 * @param current 当前查看的年月：用来高亮，也决定年份格默认停在哪一页
 * @param onDismiss 取消 / 点外部关闭
 * @param onSelect 选中某年某月（调用方负责 setMonth 并关闭弹窗）
 */
@Composable
fun YearMonthPickerDialog(
    current: YearMonth,
    onDismiss: () -> Unit,
    onSelect: (YearMonth) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择年月") },
        text = { YearMonthPickerBody(current = current, onSelect = onSelect) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 弹窗内容（拆出来单独可测：AlertDialog 外壳在 Robolectric 里渲染成本高，
 * 而"两步选年月"这条交互本身必须被回归测试守住——与 FilterDialogBody 同一处理方式）。
 */
@Composable
internal fun YearMonthPickerBody(current: YearMonth, onSelect: (YearMonth) -> Unit) {
    var pickingMonths by remember { mutableStateOf(false) }
    var year by remember { mutableStateOf(current.year) }
    var pageOffset by remember { mutableStateOf(0) }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pickingMonths) {
                // 第二步：回到第一步换年（选错了不用关掉重开）
                IconButton(onClick = { pickingMonths = false }) { Icon(imageVector = LedgerIcons.Back, contentDescription = "返回选择年份") }
                Text(
                    text = YearMonthPicker.yearTitle(year),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            } else {
                IconButton(onClick = { pageOffset -= 1 }) { Icon(imageVector = LedgerIcons.ChevronLeft, contentDescription = "前二十年") }
                Text(
                    text = YearMonthPicker.pageLabel(year, pageOffset),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { pageOffset += 1 }) { Icon(imageVector = LedgerIcons.ChevronRight, contentDescription = "后二十年") }
            }
        }

        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (pickingMonths) {
                YearMonthPicker.months().chunked(GridColumns).forEach { row ->
                    PickerRow {
                        row.forEach { month ->
                            PickerCell(
                                label = YearMonthPicker.monthLabel(month),
                                selected = YearMonthPicker.isSelectedMonth(current, year, month),
                                onClick = { onSelect(YearMonth.of(year, month)) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            } else {
                YearMonthPicker.years(year, pageOffset).chunked(GridColumns).forEach { row ->
                    PickerRow {
                        row.forEach { candidate ->
                            PickerCell(
                                label = YearMonthPicker.yearLabel(candidate),
                                selected = YearMonthPicker.isSelectedYear(current, candidate),
                                // 第一步选完年立刻进第二步，两步直达
                                onClick = {
                                    year = candidate
                                    pickingMonths = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一屏两行/多行的列数：年份 20 格 4 列 5 行、月份 12 格 4 列 3 行 */
private const val GridColumns = 4

@Composable
private fun PickerRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** 一格：选中的用主色容器底，其余用中性色底 */
@Composable
private fun PickerCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .height(40.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                fontWeight = if (selected) FontWeight.Medium else null,
                maxLines = 1,
            )
        }
    }
}
