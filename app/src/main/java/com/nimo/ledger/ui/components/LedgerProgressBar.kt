package com.nimo.ledger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 超支刻度节点（测试用）：fraction > 1 且 overMarker = true 时才存在 */
const val LEDGER_PROGRESS_OVER_MARKER_TEST_TAG: String = "ledger-progress-over-marker"

/** 超支时填充条压到轨道高度的 60%，让「越过刻度线的那一截」一眼可辨 */
private const val OVER_FILL_HEIGHT_FACTOR = 0.6f

/** 100% 处的刻度宽——2dp：再宽就像分隔线，再窄在高密度屏上会消失 */
private val OVER_MARKER_WIDTH = 2.dp

/**
 * 统一细条（设计规范 §6.4）：预算进度条（8dp / 4dp 圆角）与统计页占比条（6dp / 3dp 圆角）共用一份实现。
 *
 * 为什么不用 LinearProgressIndicator：配色与超支分段都要自己说了算，而这套实现只有两层 Box，
 * 没有跨 API 版本的样式差异。
 *
 * 超支（fraction > 1，预算条传 overMarker = true）：填充整条压到 60% 高度并置中，
 * 右端留 2dp 的 surface 色刻度——比「全红到底」更能表达「超了多少」。
 * 轨道与填充的圆角跟随 [shape]，调用方按条目高度各传各的（8dp/4dp 或 6dp/3dp）。
 *
 * 无障碍：整条不声明任何语义（Box 本身不带 contentDescription，也没有可聚焦子节点），
 * 信息由同行文字承载（「已用 ¥x / ¥y」「还剩 ¥x」），避免读屏器念一串百分比。
 */
@Composable
fun LedgerProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color,
    height: Dp = 8.dp,
    shape: Shape = RoundedCornerShape(4.dp),
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    overMarker: Boolean = false,
) {
    // NaN / 负无穷这类脏值一律当 0：fillMaxWidth(NaN) 会直接抛异常，脏数据不该炸页面
    val safeFraction = if (fraction.isFinite()) fraction else 0f
    val clamped = safeFraction.coerceIn(0f, 1f)
    val over = overMarker && safeFraction > 1f
    // 外层承载调用方的 modifier（含 testTag），内层画轨道与填充。
    // 不用 clearAndSetSemantics：它会把子节点的语义一起清掉，超支刻度的 testTag 会连坐。
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(trackColor, shape),
        ) {
            if (over) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .height(height * OVER_FILL_HEIGHT_FACTOR)
                        .background(color, shape),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(OVER_MARKER_WIDTH)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface)
                        .testTag(LEDGER_PROGRESS_OVER_MARKER_TEST_TAG),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(clamped)
                        .fillMaxHeight()
                        .background(color, shape),
                )
            }
        }
    }
}
