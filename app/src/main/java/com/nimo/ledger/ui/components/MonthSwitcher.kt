package com.nimo.ledger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nimo.ledger.ui.LedgerIcons

/**
 * 月份切换：< 2026年10月 >。
 *
 * 明细页（F05）与统计页（F07）共用一份：两页的月份边界都来自
 * [com.nimo.ledger.util.Dates.monthBounds]，标签格式也必须一致，
 * 不允许各写一套（否则同一个"10月"在两张页面上可能长得不一样）。
 *
 * R1 日期直改：标签本身可点，点开 [YearMonthPickerDialog] 两步选到目标年月；
 * 左右箭头原样保留——上下月仍是最常用的动作，不该被藏进弹窗里。
 *
 * @param onLabelClick 点月份标签的回调；null = 这一页还没接入年月直选（预算页），标签只显示不可点
 */
@Composable
fun MonthSwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLabelClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(imageVector = LedgerIcons.ChevronLeft, contentDescription = "上个月")
        }
        Box(
            modifier = Modifier
                .weight(1f)
                // 标签自己就是入口：不再额外加一个"选择月份"按钮
                .then(
                    if (onLabelClick == null) {
                        Modifier
                    } else {
                        Modifier.clickable(onClick = onLabelClick)
                    },
                )
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        IconButton(onClick = onNext) {
            Icon(imageVector = LedgerIcons.ChevronRight, contentDescription = "下个月")
        }
    }
}
