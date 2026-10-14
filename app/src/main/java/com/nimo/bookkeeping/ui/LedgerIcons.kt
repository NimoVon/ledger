package com.nimo.bookkeeping.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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

    /** 返回：左尖括号（描边） */
    val Back: ImageVector = ledgerIcon("LedgerBack") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(14.5f, 5f)
            lineTo(8f, 12f)
            lineTo(14.5f, 19f)
        }
    }

    /** 上个月：左尖括号（描边） */
    val ChevronLeft: ImageVector = ledgerIcon("LedgerChevronLeft") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(14.5f, 6f)
            lineTo(9f, 12f)
            lineTo(14.5f, 18f)
        }
    }

    /** 下个月：右尖括号（描边） */
    val ChevronRight: ImageVector = ledgerIcon("LedgerChevronRight") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(9.5f, 6f)
            lineTo(15f, 12f)
            lineTo(9.5f, 18f)
        }
    }

    /** 筛选：漏斗 */
    val Filter: ImageVector = ledgerIcon("LedgerFilter") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 5f)
            lineTo(20f, 5f)
            lineTo(13.5f, 12.5f)
            lineTo(13.5f, 19.5f)
            lineTo(10.5f, 17f)
            lineTo(10.5f, 12.5f)
            close()
        }
    }

    /** 新增：加号 */
    val Add: ImageVector = ledgerIcon("LedgerAdd") {
        path(fill = SolidColor(Color.Black)) {
            hBar(5f, 19f, 12f, 2.2f)
            vBar(12f, 5f, 19f, 2.2f)
        }
    }

    /** 转账：两条反向的箭头（上排向右、下排向左） */
    val Transfer: ImageVector = ledgerIcon("LedgerTransfer") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 上排：向右的箭头
            moveTo(5f, 9f)
            lineTo(19f, 9f)
            moveTo(16f, 6f)
            lineTo(19f, 9f)
            lineTo(16f, 12f)
            // 下排：向左的箭头
            moveTo(19f, 15f)
            lineTo(5f, 15f)
            moveTo(8f, 12f)
            lineTo(5f, 15f)
            lineTo(8f, 18f)
        }
    }

    /** 上移（F02 分类排序）：向上的箭头 */
    val ArrowUp: ImageVector = ledgerIcon("LedgerArrowUp") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 19f)
            lineTo(12f, 5.5f)
            moveTo(6.5f, 11f)
            lineTo(12f, 5.5f)
            lineTo(17.5f, 11f)
        }
    }

    /** 下移（F02 分类排序）：向下的箭头 */
    val ArrowDown: ImageVector = ledgerIcon("LedgerArrowDown") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 5f)
            lineTo(12f, 18.5f)
            moveTo(6.5f, 13f)
            lineTo(12f, 18.5f)
            lineTo(17.5f, 13f)
        }
    }

    /** 校准（F11 余额校准）：天平——横梁 + 立柱 + 底座 + 两个托盘 */
    val Scale: ImageVector = ledgerIcon("LedgerScale") {
        path(fill = SolidColor(Color.Black)) {
            hBar(3.5f, 20.5f, 6.5f, 1.8f)
            vBar(12f, 6.5f, 18.5f, 1.8f)
            hBar(7.5f, 16.5f, 19f, 1.8f)
            // 左托盘
            moveTo(3.5f, 9.5f)
            lineTo(8.5f, 9.5f)
            lineTo(6f, 13.5f)
            close()
            // 右托盘
            moveTo(15.5f, 9.5f)
            lineTo(20.5f, 9.5f)
            lineTo(18f, 13.5f)
            close()
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
