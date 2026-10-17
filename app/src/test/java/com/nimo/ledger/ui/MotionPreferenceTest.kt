package com.nimo.ledger.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统「减少动画」偏好（设计规范 §13 AA-7 / §11 批 F 的 F-4）。
 *
 * 两条要钉住的规则：① 倍率为 0 时不播动画；② 四个转场在关闭时必须是 `None`——
 * 0 时长的位移仍然会「跳一下」，不如彻底不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class MotionPreferenceTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setScale(value: Float) {
        Settings.Global.putFloat(context.contentResolver, ANIMATOR_DURATION_SCALE, value)
    }

    @Test
    fun aZeroScale_meansNoAnimation() {
        assertFalse("系统「移除动画」时倍率为 0", animationsEnabled(0f))
    }

    @Test
    fun anyPositiveScale_stillAnimates() {
        assertTrue(animationsEnabled(0.5f))
        assertTrue(animationsEnabled(1f))
        assertTrue(animationsEnabled(2f))
    }

    @Test
    fun readAnimatorScale_readsTheSystemSetting() {
        setScale(0f)
        assertEquals(0f, readAnimatorScale(context), 0.0001f)

        setScale(0.5f)
        assertEquals(0.5f, readAnimatorScale(context), 0.0001f)
    }

    @Test
    fun rememberAnimationsEnabled_isFalse_whenSystemSaysRemoveAnimations() {
        setScale(0f)
        composeRule.setContent {
            MaterialTheme {
                Text(if (rememberAnimationsEnabled()) "动画开" else "动画关")
            }
        }

        composeRule.onNodeWithText("动画关").assertIsDisplayed()
    }

    @Test
    fun rememberAnimationsEnabled_isTrue_byDefault() {
        setScale(1f)
        composeRule.setContent {
            MaterialTheme {
                Text(if (rememberAnimationsEnabled()) "动画开" else "动画关")
            }
        }

        composeRule.onNodeWithText("动画开").assertIsDisplayed()
    }

    @Test
    fun turningAnimationsOff_makesAllFourTransitionsNone() {
        val pairs = listOf(
            LedgerRoutes.TRANSACTIONS to LedgerRoutes.REPORTS,
            LedgerRoutes.REPORTS to LedgerRoutes.TRANSACTIONS_DRILL,
            LedgerRoutes.SETTINGS to LedgerRoutes.ACCOUNTS,
            LedgerRoutes.TRANSACTIONS to LedgerRoutes.RECORD,
        )
        pairs.forEach { (from, to) ->
            assertEquals(
                "关闭动画时 enter 必须是不动的（" + from + " -> " + to + "）",
                EnterTransition.None,
                LedgerNavMotion.enter(from, to, animated = false),
            )
            assertEquals(ExitTransition.None, LedgerNavMotion.exit(from, to, animated = false))
            assertEquals(EnterTransition.None, LedgerNavMotion.popEnter(from, to, animated = false))
            assertEquals(ExitTransition.None, LedgerNavMotion.popExit(from, to, animated = false))
        }
    }

    @Test
    fun withAnimationsOn_theTransitionsAreReal() {
        // 反向那一半：默认（动画开）不能也退化成 None，否则上一条测试就成了自我实现
        assertNotEquals(
            EnterTransition.None,
            LedgerNavMotion.enter(LedgerRoutes.TRANSACTIONS, LedgerRoutes.REPORTS),
        )
        assertNotEquals(
            ExitTransition.None,
            LedgerNavMotion.exit(LedgerRoutes.REPORTS, LedgerRoutes.TRANSACTIONS_DRILL),
        )
    }
}
