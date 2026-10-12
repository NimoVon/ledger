package com.nimo.bookkeeping.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 底部导航用的三个极简矢量图标（明细 / 统计 / 设置）。
 *
 * 只用 compose-ui 自带的 ImageVector 手绘，不引入 material-icons-core：
 * 计划 4.2 规则 2 要求「能用标准库实现就不新增依赖」。
 * 实际显示颜色由 Icon 的 tint（LocalContentColor）覆盖，这里的填充色不生效。
 */
object LedgerIcons {

    /** 明细：三条横线 */
    val List: ImageVector = ledgerIcon("LedgerList") {
        path(fill = SolidColor(Color.Black)) {
            hBar(4f, 20f, 7f, 2f)
            hBar(4f, 20f, 12f, 2f)
            hBar(4f, 20f, 17f, 2f)
        }
    }

    /** 统计：三根高低不同的柱子 */
    val Chart: ImageVector = ledgerIcon("LedgerChart") {
        path(fill = SolidColor(Color.Black)) {
            vBar(7f, 13f, 19f, 3.5f)
            vBar(12f, 8f, 19f, 3.5f)
            vBar(17f, 4f, 19f, 3.5f)
        }
    }

    /** 设置：三条带滑钮的滑杆 */
    val Sliders: ImageVector = ledgerIcon("LedgerSliders") {
        path(fill = SolidColor(Color.Black)) {
            slider(7f, 10f)
            slider(12f, 15f)
            slider(17f, 8f)
        }
    }
}

private fun ledgerIcon(name: String, content: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(content).build()

private fun PathBuilder.hBar(x0: Float, x1: Float, y: Float, thickness: Float) {
    moveTo(x0, y - thickness / 2f)
    lineTo(x1, y - thickness / 2f)
    lineTo(x1, y + thickness / 2f)
    lineTo(x0, y + thickness / 2f)
    close()
}

private fun PathBuilder.vBar(x: Float, y0: Float, y1: Float, thickness: Float) {
    moveTo(x - thickness / 2f, y0)
    lineTo(x + thickness / 2f, y0)
    lineTo(x + thickness / 2f, y1)
    lineTo(x - thickness / 2f, y1)
    close()
}

/** 一条滑杆：左右两段轨道 + 中间一个方形滑钮 */
private fun PathBuilder.slider(y: Float, knobX: Float) {
    hBar(4f, knobX - 2.5f, y, 1.6f)
    hBar(knobX + 2.5f, 20f, y, 1.6f)
    moveTo(knobX - 2.5f, y - 3f)
    lineTo(knobX + 2.5f, y - 3f)
    lineTo(knobX + 2.5f, y + 3f)
    lineTo(knobX - 2.5f, y + 3f)
    close()
}
