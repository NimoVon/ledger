package com.nimo.bookkeeping.ui.transactions

import androidx.compose.runtime.Composable
import com.nimo.bookkeeping.ui.components.EmptyState

/** 明细（流水时间线，F05）。属于阶段 2 · 记账闭环，当前为占位空状态。 */
@Composable
fun TransactionsScreen() {
    EmptyState(
        title = "还没有交易记录",
        message = "流水时间线将在「阶段 2 · 记账闭环」实现。",
    )
}
