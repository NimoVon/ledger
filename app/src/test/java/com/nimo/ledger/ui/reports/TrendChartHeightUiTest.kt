package com.nimo.ledger.ui.reports

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.stats.MonthlyTotal
import com.nimo.ledger.domain.stats.ReportRange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * S-6 的组件级证据：窄屏（320dp）+ 自然年时，折线图画布真的比默认高 20dp。
 *
 * 纯函数测试只能证明"算得对"；这条证明"算出来的值被用上了"，防止有人把 CompositionLocal 接错地方。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w320dp-h640dp-xhdpi")
class TrendChartHeightUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun points(): List<MonthlyTotal> = (1..12).map {
        MonthlyTotal(month = YearMonth.of(2026, it), incomeCents = 0L, expenseCents = 1_000L)
    }

    @Test
    fun calendarYearOnNarrowScreen_drawsTheTallerChart() {
        val range = ReportRange.CalendarYear(2026)
        composeRule.setContent {
            MaterialTheme {
                TrendSection(
                    range = range,
                    points = points(),
                    daily = emptyList(),
                    isLoading = false,
                    onDayClick = {},
                )
            }
        }

        val bounds = composeRule
            .onNodeWithContentDescription(trendDescription(points(), range), substring = true)
            .getBoundsInRoot()
        assertEquals(180.dp, bounds.bottom - bounds.top)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun calendarYearOnWideScreen_keepsTheDefaultChartHeight() {
        val range = ReportRange.CalendarYear(2026)
        composeRule.setContent {
            MaterialTheme {
                TrendSection(
                    range = range,
                    points = points(),
                    daily = emptyList(),
                    isLoading = false,
                    onDayClick = {},
                )
            }
        }

        val bounds = composeRule
            .onNodeWithContentDescription(trendDescription(points(), range), substring = true)
            .getBoundsInRoot()
        assertEquals(160.dp, bounds.bottom - bounds.top)
    }
}
