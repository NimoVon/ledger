package com.nimo.ledger.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 统一细条（设计规范 §6.4，批 D · D-3）的组件级验收。
 *
 * 预算条（8dp + 超支刻度）与统计页占比条（6dp、无刻度）只是同一实现的两组参数，
 * 所以这里按参数验证：高度、超支刻度只在 fraction > 1 时出现、脏数据不炸、读屏不念百分比。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LedgerProgressBarUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showBar(
        fraction: Float,
        height: Int = 8,
        overMarker: Boolean = false,
    ) {
        composeRule.setContent {
            MaterialTheme {
                LedgerProgressBar(
                    fraction = fraction,
                    modifier = Modifier.testTag("bar"),
                    color = Color.Red,
                    height = height.dp,
                    overMarker = overMarker,
                )
            }
        }
    }

    @Test
    fun theTrackKeepsTheHeightTheCallerAsksFor() {
        showBar(fraction = 0.5f, height = 6)
        composeRule.onNodeWithTag("bar").assertHeightIsEqualTo(6.dp)
    }

    @Test
    fun overspending_marksTheHundredPercentLine() {
        showBar(fraction = 1.5f, overMarker = true)
        composeRule.onNodeWithTag(LEDGER_PROGRESS_OVER_MARKER_TEST_TAG).assertExists()
    }

    @Test
    fun aBarThatIsNotOver_hasNoMarker_evenWhenTheFlagIsOn() {
        showBar(fraction = 0.8f, overMarker = true)
        composeRule.onNodeWithTag(LEDGER_PROGRESS_OVER_MARKER_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun theRangeBar_neverShowsTheOverspendMarker() {
        // 占比条传的是 0..1 的占比，理论上到不了 >1；就算到了也不该出现预算专属刻度
        showBar(fraction = 1.5f, overMarker = false)
        composeRule.onNodeWithTag(LEDGER_PROGRESS_OVER_MARKER_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun dirtyFractions_doNotCrashTheComposition() {
        // NaN / 负数 / 超大值都可能来自预算差值为 0 之类的边界，绝不能把页面炸掉
        showBar(fraction = Float.NaN)
        composeRule.onNodeWithTag("bar").assertExists()
    }

    @Test
    fun theBarItself_isSilentForScreenReaders() {
        showBar(fraction = 0.5f)
        composeRule.onNodeWithTag("bar")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }
}
