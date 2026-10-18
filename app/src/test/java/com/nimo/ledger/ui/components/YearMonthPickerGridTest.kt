package com.nimo.ledger.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 年月选择器在 200% 字号下的列数（UI-UX 规范 §10.5 / 无障碍 AA-8）。
 *
 * 真机 2.0 走查发现年份格 4 列会把「2028」截成「202」——数字被截断比挤一点更糟，
 * 所以 ≥1.5 字号降到 3 列（与段头折行同一个 `stackAtLargeFont` 门槛）。
 * 断言用「同一行的节点 top 是否相等」来数行，不依赖 Robolectric 的字宽度量。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class YearMonthPickerGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showPicker(fontScale: Float) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    YearMonthPickerBody(current = YearMonth.of(2026, 10), onSelect = {})
                }
            }
        }
    }

    private fun topOf(text: String): Float =
        composeRule.onNodeWithText(text).getUnclippedBoundsInRoot().top.value

    @Test
    fun at100FontScale_theYearGridStaysFourColumns() {
        showPicker(fontScale = 1f)

        assertEquals(topOf("2020"), topOf("2021"), 0.5f)
        assertEquals(topOf("2021"), topOf("2022"), 0.5f)
        assertEquals(topOf("2022"), topOf("2023"), 0.5f)
    }

    @Test
    fun at200FontScale_theYearGridFallsBackToThreeColumns() {
        showPicker(fontScale = 2f)

        assertEquals(topOf("2020"), topOf("2021"), 0.5f)
        assertEquals(topOf("2021"), topOf("2022"), 0.5f)
        // 第四格换行：2023 与前三个不在同一行
        val third = topOf("2022")
        val fourth = topOf("2023")
        assertTrue("2023 应换到第二行（2022 top=$third，2023 top=$fourth）", fourth > third + 1f)
    }
}
