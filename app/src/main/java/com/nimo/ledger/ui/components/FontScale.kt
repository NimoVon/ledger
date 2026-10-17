package com.nimo.ledger.ui.components

/**
 * 字体倍数达到这个门槛就该从「一行横排」改成「竖排」的行内门槛（设计规范 §3.3 / G-6 / 批 F 的 F-2）。
 *
 * Android 的字体档位是 1.0 / 1.15 / 1.3 / 1.5 / 2.0；1.3 实测多数横向排布还挤得下，
 * 1.5 起金额开始被折断（F15 真机取证：2.0 下「结余 -¥600.00」被断成两行），门槛取 1.5。
 * 门槛只在这里定一次：MonthSummaryBar、明细筛选条、日段头、预算进度条下行共用它，不许各写一份。
 */
internal const val STACK_FONT_SCALE = 1.5f

/** 纯函数：这个字体倍数要不要改竖排。抽出来是为了能单测（Compose 里读 [androidx.compose.ui.platform.LocalDensity] 再调它）。 */
internal fun stackAtLargeFont(fontScale: Float): Boolean = fontScale >= STACK_FONT_SCALE
