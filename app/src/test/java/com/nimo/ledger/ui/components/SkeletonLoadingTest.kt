package com.nimo.ledger.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.nimo.ledger.domain.stats.PeriodSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G-9：加载态先给骨架占位，别转圈。
 *
 * 断言只锁「有几块占位」「加载完成不再有占位」——灰块的颜色/圆角属于视觉，不在这里冒充像素比对。
 * 另外这也是一道护栏：谁给骨架加上无限动画，Robolectric 会直接不 idle（§50.3 的坑）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SkeletonLoadingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val empty = PeriodSummary(incomeCents = 0L, expenseCents = 0L)

    @Test
    fun summaryBarWhileLoading_showsOneSkeletonPerCell() {
        composeRule.setContent {
            MaterialTheme {
                MonthSummaryBar(summary = empty, loading = true)
            }
        }

        composeRule.onAllNodesWithTag(SKELETON_BLOCK_TAG, useUnmergedTree = true).assertCountEquals(3)
        composeRule.onNodeWithText("支出").assertExists()
    }

    @Test
    fun summaryBarWhenLoaded_dropsTheSkeletonsAndShowsMoney() {
        composeRule.setContent {
            MaterialTheme {
                MonthSummaryBar(
                    summary = PeriodSummary(incomeCents = 0L, expenseCents = 256_600L),
                    loading = false,
                )
            }
        }

        composeRule.onAllNodesWithTag(SKELETON_BLOCK_TAG, useUnmergedTree = true).assertCountEquals(0)
        composeRule.onNodeWithText("¥2,566.00").assertExists()
    }

    @Test
    fun listSkeleton_rendersRequestedNumberOfRowsWithoutSpinner() {
        composeRule.setContent {
            MaterialTheme {
                SkeletonList(rows = 6)
            }
        }

        composeRule.onAllNodesWithTag(SKELETON_ROW_TAG).assertCountEquals(6)
        composeRule.onNodeWithTag(SKELETON_LIST_TAG).assertExists()
    }
}
