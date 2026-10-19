package com.nimo.ledger.ui.transactions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 明细页筛选条（设计规范 §8 T-1 / T-2，批 E · E-1）的组件级验收。
 *
 * 这一条之前是「按钮 + 竖排文字列」：条件越多，「清空」被顶得越高，列表首屏越少。
 * 现在压成一行——左按钮、中间可横滚的圆角 chips、行尾「清空」——所以断言的是四件事：
 * 高度只跟「一行」有关而不跟条件数量有关、chips 真能横向滚到最后一个、
 * 单个条件只占一行、只在开了条件时才出现「清空」。
 *
 * 注意：Robolectric 的假字体把每个字量成 1px 宽，所以这里不测「文字排了几行宽」，
 * 只测高度与滚动——宽度在真机截图上核对（见 docs/测试报告.md 对应小节）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TransactionsFilterBarUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var openCalls = 0
    private var clearCalls = 0

    /** 一个可改的条件源：Compose 测试规则不允许同一测试里第二次 setContent */
    private val chipsState = mutableStateOf<List<String>?>(null)

    /** 三个长条件：合起来一定宽过 411dp 的一行，用来验证横滚 */
    private val longChips = listOf(
        "账户：支付宝余额账户",
        "分类：娱乐、人情、餐饮",
        "日期：2026-10-01 ~ 2026-10-31",
    )

    private fun showBar(chips: List<String>) {
        val first = chipsState.value == null
        chipsState.value = chips
        if (first) {
            composeRule.setContent {
                MaterialTheme {
                    FilterBar(
                        chips = chipsState.value.orEmpty(),
                        onOpen = { openCalls++ },
                        onSearch = {},
                        onClear = { clearCalls++ },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun sizeOf(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().size

    private fun dpOf(px: Int): Dp = with(composeRule.density) { px.toDp() }

    private fun barHeightDp(): Dp = dpOf(sizeOf(FILTER_BAR_TEST_TAG).height)

    private fun assertWithinOneLine(where: String) {
        val height = barHeightDp()
        assertTrue(
            "$where：筛选条应当是单行（<= $FILTER_BAR_MAX_HEIGHT_DP dp），实测 ${height.value} dp",
            height.value <= FILTER_BAR_MAX_HEIGHT_DP,
        )
    }

    @Test
    fun noFilter_showsOnlyTheFilterButton() {
        showBar(chips = emptyList())

        composeRule.onNodeWithText("筛选").assertIsDisplayed()
        assertTrue(
            "没有条件时不该出现「清空」",
            composeRule.onAllNodesWithText("清空").fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty(),
        )
        assertWithinOneLine("无条件")
    }

    @Test
    fun moreConditions_doNotMakeTheBarTaller() {
        // 旧实现每多一条条件就多一行 labelSmall；这里钉住「高度与条件数量无关」
        showBar(chips = longChips.take(1))
        val oneChip = barHeightDp()
        showBar(chips = longChips + listOf("关键词：电影票", "金额：≥ ¥10.00", "账户：微信"))
        val sixChips = barHeightDp()

        assertEquals("1 条与 6 条条件的高度应当相同", oneChip.value, sixChips.value, 0.5f)
        assertWithinOneLine("6 条条件")
    }

    @Test
    fun threeConditions_areAllRenderedInTheBar() {
        showBar(chips = longChips)

        longChips.forEach { composeRule.onNodeWithText(it).assertExists() }
        assertWithinOneLine("3 条条件")
    }

    @Test
    fun theLastCondition_isReachableByScrollingSideways() {
        showBar(chips = longChips)

        composeRule.onNodeWithTag(FILTER_CHIPS_ROW_TEST_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        composeRule.onNodeWithTag(FILTER_CHIPS_ROW_TEST_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(-1200f, 0f) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(longChips.last()).assertIsDisplayed()
    }

    @Test
    fun aCondition_staysOnOneLine() {
        showBar(chips = listOf(longChips.last()))

        val chipHeight = dpOf(composeRule.onNodeWithText(longChips.last()).fetchSemanticsNode().size.height)
        assertTrue(
            "单条条件只占一行（<= 36 dp），实测 ${chipHeight.value} dp——换行就是把一条条件拆成两行",
            chipHeight.value <= 36f,
        )
    }

    /** 按指定字体倍数列出筛选条（F-2：≥1.5 时 chips 要换到下一行，不能再被 LazyRow 从字形中间裁断） */
    private fun showBarAtFontScale(chips: List<String>, fontScale: Float) {
        composeRule.setContent {
            val base = LocalDensity.current
            MaterialTheme {
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    FilterBar(chips = chips, onOpen = {}, onSearch = {}, onClear = {})
                }
            }
        }
    }

    private fun boundsOf(text: String) =
        composeRule.onNodeWithText(text, substring = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun atNormalFontScale_theChipsShareTheRowWithTheFilterButton() {
        showBarAtFontScale(chips = longChips, fontScale = 1.0f)

        // 同一行 = 垂直区间重叠
        assertTrue(
            "常规字体下 chips 应与筛选按钮同处一行",
            boundsOf(longChips[0]).top < boundsOf("筛选").bottom - 1f,
        )
    }

    @Test
    fun atLargeFontScale_theChipsMoveToTheirOwnFullWidthLine() {
        showBarAtFontScale(chips = longChips, fontScale = 2.0f)

        val button = boundsOf("筛选")
        val chips = boundsOf(longChips[0])
        assertTrue("大字下 chips 必须落在按钮下面（buttonBottom=${button.bottom} chipsTop=${chips.top}）", chips.top >= button.bottom - 1f)
        // 宽度只能量「行」（LazyRow 是 fillMaxWidth），不能量 chip 文字——
        // Robolectric 的假字体把每个字量成 1px 宽，这里 chip 只有 11px（E-1 记录过同一个坑）
        val rowWidth = sizeOf(FILTER_CHIPS_ROW_TEST_TAG).width
        val barWidth = sizeOf(FILTER_BAR_TEST_TAG).width
        val paddingPx = with(composeRule.density) { 16.dp.roundToPx() }
        val expected = barWidth - 2 * paddingPx
        assertTrue(
            "大字下 chips 行应独占整宽（row=" + rowWidth + ", bar=" + barWidth + ", expected=" + expected + "）",
            Math.abs(rowWidth - expected) <= 2,
        )
    }

    @Test
    fun theClearButton_clearsTheFilter() {
        showBar(chips = longChips)

        composeRule.onNodeWithText("清空").performClick()

        assertEquals(1, clearCalls)
    }

    @Test
    fun theFilterButton_opensThePanel() {
        showBar(chips = emptyList())

        composeRule.onNodeWithText("筛选").performClick()

        assertEquals(1, openCalls)
    }
}
