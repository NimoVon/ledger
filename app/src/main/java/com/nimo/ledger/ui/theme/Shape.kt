package com.nimo.ledger.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角规格（R4-L1）：容器类 16–24dp，呼应图标那个圆润的方块腔调。
 *
 * M3 组件默认就取这里：Card 用 medium、Dialog / BottomSheet 用 extraLarge、
 * Chip / 输入框用 small —— 所以改这一处，全站圆角语言一起走，不必逐个组件写死。
 *
 * 零散的 3–4dp 圆角（预算进度条、占比条、柱子顶）是 6–8dp 高的细条，
 * 圆角只用来「两端收口」，不适用 16dp 档，仍留在各自调用点。
 */

/** 容器档位（dp）：medium / large / extraLarge 三档，[LedgerShapesTest] 守住 16–24 区间 */
internal val BRAND_SHAPE_CONTAINER_DP: List<Int> = listOf(16, 20, 24)

internal val LedgerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(BRAND_SHAPE_CONTAINER_DP[0].dp),
    large = RoundedCornerShape(BRAND_SHAPE_CONTAINER_DP[1].dp),
    extraLarge = RoundedCornerShape(BRAND_SHAPE_CONTAINER_DP[2].dp),
)
