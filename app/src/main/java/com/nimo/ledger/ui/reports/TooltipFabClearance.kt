package com.nimo.ledger.ui.reports

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import com.nimo.ledger.ui.components.FAB_BOTTOM_RESERVE

/** 气泡行尾箭头的无障碍文案：整块可点，读屏靠这句知道点进去是明细 */
internal const val TOOLTIP_AFFORDANCE_DESCRIPTION = "查看明细"

/**
 * 气泡出现在屏幕下沿时，把「气泡 + 悬浮按钮保留区」整块滚进视口（S-1 修法 ①）。
 *
 * 为什么要多算一截：统计页的「记一笔」是 Extended FAB，固定贴右下角、悬浮在列表之上，
 * 按钮顶边 = 内容区底边 − 72dp（16dp 外边距 + 56dp 按钮高），所以气泡只"滚到可见"还不够，
 * 右下角那一截仍会被按钮压住——那正是气泡标题行右端、原来「查看明细」所在的位置（测试报告 §10(9)）。
 * 这里用 [FAB_BOTTOM_RESERVE]（88dp）把保留区一起算进要滚进视口的矩形，气泡底边就落在按钮顶边之上。
 *
 * 实现用 [BringIntoViewRequester] 而不是自己算偏移：气泡在 LazyColumn 里的位置随内容高度变化，
 * 交给上层滚动容器去满足矩形，比自己算 scrollBy 稳，也不依赖具体是 LazyColumn 还是 Column+verticalScroll。
 *
 * 注意：这里请求的矩形是「本节点高度 + 88dp」，属于本节点局部坐标；滚动容器会把多出来的一截也算进去。
 */
@Composable
internal fun Modifier.clearOfFloatingActionButton(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var measured by remember { mutableStateOf(IntSize.Zero) }
    val reservePx = with(LocalDensity.current) { FAB_BOTTOM_RESERVE.toPx() }
    LaunchedEffect(measured, reservePx) {
        if (measured.width <= 0 || measured.height <= 0) return@LaunchedEffect
        requester.bringIntoView(
            Rect(
                left = 0f,
                top = 0f,
                right = measured.width.toFloat(),
                bottom = measured.height + reservePx,
            ),
        )
    }
    return this
        .bringIntoViewRequester(requester)
        .onSizeChanged { measured = it }
}
