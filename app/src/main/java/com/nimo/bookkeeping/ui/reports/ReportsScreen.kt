package com.nimo.bookkeeping.ui.reports

import androidx.compose.runtime.Composable
import com.nimo.bookkeeping.ui.components.EmptyState

/** 统计（月度报表，F07）。属于阶段 3 · 报表，当前为占位。 */
@Composable
fun ReportsScreen() {
    EmptyState(
        title = "统计报表",
        message = "月报、分类排行与环比将在「阶段 3 · 报表」实现。",
    )
}
