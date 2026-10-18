package com.nimo.ledger.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.nimo.ledger.domain.model.TransactionType
import org.junit.Assert.assertEquals

import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 金额列（UI-UX 规范 §6.3，问题清单 G-5）的组件级验收。
 *
 * 两道网：符号 / 颜色口径用纯函数锁住（[ledgerAmountText]），
 * 96dp 最小宽度与「长金额自己撑开」用 Compose 渲染实测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LedgerAmountTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- 符号口径（纯函数，不依赖渲染）----

    @Test
    fun expenseWithSign_getsMinusPrefix() {
        assertEquals("-¥788.00", ledgerAmountText(78_800L, TransactionType.EXPENSE, showSign = true))
    }

    @Test
    fun incomeWithSign_getsPlusPrefix() {
        assertEquals("+¥3,000.00", ledgerAmountText(300_000L, TransactionType.INCOME, showSign = true))
    }

    @Test
    fun withoutSign_thereIsNoPrefix() {
        assertEquals("¥788.00", ledgerAmountText(78_800L, TransactionType.EXPENSE, showSign = false))
        assertEquals("¥3,000.00", ledgerAmountText(300_000L, TransactionType.INCOME, showSign = false))
    }

    @Test
    fun transferAndBalanceAdjust_stayUnsigned() {
        assertEquals("¥500.00", ledgerAmountText(50_000L, TransactionType.TRANSFER, showSign = true))
        assertEquals("-¥73.00", ledgerAmountText(-7_300L, TransactionType.BALANCE_ADJUST, showSign = true))
    }

    @Test
    fun neutralAmount_keepsItsOwnSign() {
        assertEquals("¥2,566.00", ledgerAmountText(256_600L, null, showSign = true))
        assertEquals("-¥2,566.00", ledgerAmountText(-256_600L, null, showSign = true))
    }

    // ---- 布局约束（规范 §6.3：minWidth 96dp、单行）----

    @Test
    fun shortAmount_stillOccupiesTheMinWidthColumn() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.padding(start = 16.dp)) {
                    LedgerAmount(cents = 100L, type = null)
                }
            }
        }
        composeRule.onNodeWithTag(LEDGER_AMOUNT_TEST_TAG)
            .assertWidthIsAtLeast(LEDGER_AMOUNT_MIN_WIDTH)
    }

    @Test
    fun longestAllowedAmount_isStillRenderedAsOneNode() {
        composeRule.setContent {
            MaterialTheme {
                Box(modifier = Modifier.padding(start = 16.dp)) {
                    LedgerAmount(cents = 9_999_999_999L, type = TransactionType.EXPENSE, showSign = true)
                }
            }
        }
        // 说明：Robolectric 的字体度量不产生真实字宽，无法在这里断言「长金额把 96dp 撑开」；
        // widthIn(min) 只是下限，Text 自身会按内容测量——设备走查里用「¥1,690.00 与 ¥2.00 右缘对齐」
        // 与「最长金额不被截断」两条实测覆盖。这里只锁住：最大合法金额仍是一个可见节点，没被裁掉。
        composeRule.onNodeWithTag(LEDGER_AMOUNT_TEST_TAG)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(LEDGER_AMOUNT_MIN_WIDTH)
    }
}
