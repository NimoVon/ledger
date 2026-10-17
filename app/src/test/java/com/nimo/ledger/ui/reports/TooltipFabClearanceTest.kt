package com.nimo.ledger.ui.reports

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nimo.ledger.awaitUntil
import com.nimo.ledger.domain.stats.CategoryBreakdownRow
import com.nimo.ledger.domain.stats.DailyTotal
import com.nimo.ledger.domain.stats.MonthlyTotal
import com.nimo.ledger.domain.stats.ReportRange
import com.nimo.ledger.ui.components.FAB_BOTTOM_RESERVE
import java.time.YearMonth
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 统计页气泡不得被右下角的「记一笔」压住（S-1）：两条修法都要有可断言的证据。
 *
 * 修法 ①：[clearOfFloatingActionButton] 在气泡出现时把「气泡 + 88dp」整块滚进视口——
 * 这里用一个贴底边的普通节点（最小复现）和真实环形图气泡各验一次；
 * 修法 ②：气泡行尾是箭头图标，右下角不再有文字，读屏仍能听到「查看明细」。
 *
 * 假 FAB 按真机尺寸摆（150×56dp、右下 16dp 边距），它的顶边就是"气泡底边不许越过"的那条线。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TooltipFabClearanceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun px(value: Dp): Float = with(composeRule.density) { value.toPx() }

    private fun viewportBottom(): Float =
        composeRule.onRoot().fetchSemanticsNode().boundsInRoot.bottom

    private fun bottomOf(tag: String): Float =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom

    /** FAB 顶边：视口底 − 16dp 外边距 − 56dp 按钮高 */
    private fun fabTop(): Float = viewportBottom() - px(16.dp) - px(56.dp)

    /** 右下角的假 FAB：尺寸与真机的 Extended FAB 同高、近似同宽 */
    @Composable
    private fun PlaceholderFab() {
        Surface(
            modifier = Modifier
                .width(150.dp)
                .height(56.dp)
                .testTag(FAB_TAG),
        ) {}
    }

    /**
     * 最小复现：节点本来有 100dp 露在视口底边外，挂上修饰符后必须整体停在 FAB 保留区之上。
     * 滚动容器给足余量（下面还有 200dp），所以"滚不动"不是理由。
     */
    @Test
    fun aNodeNearTheBottom_scrollsUpSoItsBottomClearsTheFabReserve() {
        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(modifier = Modifier.height(850.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag(TARGET_TAG)
                            .clearOfFloatingActionButton(),
                    )
                    Box(modifier = Modifier.height(200.dp))
                }
            }
        }

        val limit = viewportBottom() - px(FAB_BOTTOM_RESERVE)
        composeRule.awaitUntil { bottomOf(TARGET_TAG) <= limit + 1f }
        val bottom = bottomOf(TARGET_TAG)
        assertTrue(
            "节点底边 $bottom px 必须停在 FAB 保留区上沿 $limit px 之上",
            bottom <= limit + 1f,
        )
    }

    /** 本来就在保留区之上：不许为了"避让"把页面无故往上滚 */
    @Test
    fun aNodeThatAlreadyClearsTheFab_isNotScrolled() {
        val scroll = ScrollState(0)
        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .testTag(TARGET_TAG)
                            .clearOfFloatingActionButton(),
                    )
                    Box(modifier = Modifier.height(800.dp))
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals("气泡一开始就看得见，滚动位置不该变", 0, scroll.value)
        assertEquals(px(100.dp), bottomOf(TARGET_TAG), 1f)
    }

    /**
     * 真实环形图气泡：环放在列表靠下的位置，气泡一出现就会压到右下角的 FAB 上。
     * 断言 ①气泡底边在 FAB 顶边之上；②行尾是箭头图标（带「查看明细」的无障碍说明）。
     */
    @Test
    fun theDonutTooltip_scrollsAboveTheFloatingActionButton_andShowsAChevron() {
        val rows = listOf(
            CategoryBreakdownRow(categoryId = 1L, name = "娱乐", amountCents = 60_000L, percent = 100.0),
        )
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        // 把环形图推到屏底：不避让的话气泡就落在 FAB 后面
                        Box(modifier = Modifier.height(640.dp))
                        CategoryDonutSection(rows = rows, onCategoryClick = {})
                        Box(modifier = Modifier.height(600.dp))
                    }
                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                        PlaceholderFab()
                    }
                }
            }
        }

        tapDonutAt(directionDegrees = 0.0)
        composeRule.onNodeWithTag(DONUT_TOOLTIP_TEST_TAG).assertIsDisplayed()
        composeRule.awaitUntil { bottomOf(DONUT_TOOLTIP_TEST_TAG) <= fabTop() + 1f }

        val bottom = bottomOf(DONUT_TOOLTIP_TEST_TAG)
        assertTrue(
            "气泡底边 $bottom px 必须停在 FAB 顶边 ${fabTop()} px 之上",
            bottom <= fabTop() + 1f,
        )
        // 箭头被整块可点的气泡合并进语义树，只能在未合并树里按 tag 取到
        composeRule.onNodeWithTag(DONUT_TOOLTIP_CHEVRON_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        // 合并后读屏听到的是气泡这一整块，里面仍然带「查看明细」
        composeRule.onNode(hasContentDescription(TOOLTIP_AFFORDANCE_DESCRIPTION)).assertExists()
    }

    /** 逐日柱状图的金额气泡用同一套修饰符与箭头（真实布局下它贴着图表顶部，这里验不变量） */
    @Test
    fun theDayTooltip_alsoClearsTheFab_andShowsAChevron() {
        val month = YearMonth.of(2026, 7)
        val daily = (1..5).map { day ->
            DailyTotal(
                date = month.atDay(day),
                incomeCents = 0L,
                expenseCents = if (day == 5) 60_000L else 0L,
            )
        }
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Box(modifier = Modifier.height(600.dp))
                        TrendSection(
                            range = ReportRange.Month(month),
                            points = listOf(MonthlyTotal(month, incomeCents = 0L, expenseCents = 60_000L)),
                            daily = daily,
                            isLoading = false,
                            onDayClick = {},
                        )
                        Box(modifier = Modifier.height(600.dp))
                    }
                    Box(modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                        PlaceholderFab()
                    }
                }
            }
        }

        composeRule.onNode(hasContentDescription("7月5日", substring = true)).performClick()
        composeRule.onNodeWithTag(DAY_TOOLTIP_TEST_TAG).assertIsDisplayed()
        // 箭头被整块可点的气泡合并进语义树，只能在未合并树里按 tag 取到
        composeRule.onNodeWithTag(DAY_TOOLTIP_CHEVRON_TEST_TAG, useUnmergedTree = true).assertIsDisplayed()
        val bottom = bottomOf(DAY_TOOLTIP_TEST_TAG)
        assertTrue(
            "气泡底边 $bottom px 必须停在 FAB 顶边 ${fabTop()} px 之上",
            bottom <= fabTop() + 1f,
        )
    }

    /** 在环形图上点一个方向：0° = 3 点方向（半径取环路中线，与 CategoryDonutUiTest 同法） */
    private fun tapDonutAt(directionDegrees: Double) {
        val density = composeRule.density.density
        val canvasPx = 180f * density
        val strokePx = 26f * density
        val radius = (canvasPx - strokePx) / 2f * 0.95f
        val radians = Math.toRadians(directionDegrees)
        val x = canvasPx / 2f + (radius * cos(radians)).toFloat()
        val y = canvasPx / 2f + (radius * sin(radians)).toFloat()
        composeRule.onNodeWithTag(DONUT_TEST_TAG).performTouchInput {
            down(Offset(x, y))
            up()
        }
    }

    private companion object {
        const val TARGET_TAG = "fab-clearance-target"
        const val FAB_TAG = "fab-clearance-fab"
    }
}
