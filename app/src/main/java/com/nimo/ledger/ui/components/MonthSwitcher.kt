package com.nimo.ledger.ui.components

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
 */
@Composable
fun MonthSwitcher(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
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
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onNext) {
            Icon(imageVector = LedgerIcons.ChevronRight, contentDescription = "下个月")
        }
    }
}
