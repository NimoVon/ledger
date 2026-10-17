package com.nimo.ledger.ui.record

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.domain.model.CategoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记一笔表单的三处结构改动（设计规范 §9 批 E · E-2 / E-3）的组件级验收。
 *
 * E-2：金额框下 12dp 处多一行复核金额（只在能解析成合法金额时出现）；
 *      表单底部多一个全宽主按钮「保存」（与顶栏同一个动作、同一份 disabled 逻辑）。
 * E-3：分类 chip 区最高 168dp、超出在框内纵向滚；选中项在框外时自动滚到可见。
 *
 * 为什么是组件级而不是整页：整页要 Room + ViewModel + DataStore，为本轮三处布局改动
 * 引入一整套假数据只会把「点击保存真的落库」这类契约（已有 RecordScreenTransferTest）搅浑。
 *
 * 注意（沿用 TransactionsFilterBarUiTest 的教训）：Robolectric 的假字体把每个字量成 1px 宽，
 * 所以这里只断言**高度与滚动**，不断言宽度；真实排版在真机截图上核对（见 docs/测试报告.md）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RecordFormActionUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 分类区当前高度（px）。
     *
     * 不用 assertHeightIsAtMost：本工程的 compose-ui-test 版本没有这个断言扩展，
     * 自己按 density 折算成 dp 上限比较，失败时把两个像素值都打进消息里。
     */
    private fun chipAreaHeightPx(): Int =
        composeRule.onNodeWithTag(CATEGORY_CHIPS_TEST_TAG).fetchSemanticsNode().size.height

    private fun maxChipAreaHeightPx(maxDp: Int): Int =
        with(composeRule.density) { maxDp.dp.roundToPx() }

    /** 200 颗分类：Robolectric 里每个 chip 只有几十像素宽，要足够多才会把 168dp 撑满 */
    private fun categories(count: Int): List<CategoryEntity> = List(count) { index ->
        CategoryEntity(
            id = index + 1L,
            name = "分类$index",
            kind = CategoryKind.EXPENSE,
            sortOrder = index,
        )
    }

    // ---- E-2 / R-7：金额复核行 ----

    @Test
    fun amountReview_isAbsent_whenThereIsNothingToReview() {
        composeRule.setContent {
            MaterialTheme { AmountReviewLine(cents = null) }
        }
        assertTrue(
            "金额为空/非法时不该出现复核行",
            composeRule.onAllNodesWithTag(AMOUNT_REVIEW_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty(),
        )
    }

    @Test
    fun amountReview_showsTheParsedAmountAsCurrency() {
        composeRule.setContent {
            MaterialTheme { AmountReviewLine(cents = 123_456L) }
        }
        composeRule.onNodeWithTag(AMOUNT_REVIEW_TEST_TAG).assertTextEquals("核对：¥1,234.56")
    }

    // ---- E-2 / R-4：底部主按钮 ----

    @Test
    fun saveBar_saysSave_callsBack_andIsEnabled() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme { RecordSaveBar(isSaving = false, onSave = { clicks++ }) }
        }
        val button = composeRule.onNodeWithTag(RECORD_SAVE_BOTTOM_TEST_TAG)
        button.assertIsEnabled().assertTextEquals("保存")
        button.performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun saveBar_saysSaving_andBlocksClicks() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme { RecordSaveBar(isSaving = true, onSave = { clicks++ }) }
        }
        val button = composeRule.onNodeWithTag(RECORD_SAVE_BOTTOM_TEST_TAG)
        button.assertIsNotEnabled().assertTextEquals("保存中…")
        // disabled 的 Button 不再挂 OnClick 语义动作，这里也没有可点的入口可走
        assertEquals("保存中不该再触发一次保存", 0, clicks)
    }

    // ---- E-3 / R-2：分类 chip 区限高 + 内部滚动 ----

    @Test
    fun categoryChips_stayWithinTheCap_whenThereAreMany() {
        composeRule.setContent {
            MaterialTheme {
                CategoryChips(
                    categories = categories(200),
                    selectedCategoryId = null,
                    onSelectCategory = {},
                    onCreateCategory = {},
                )
            }
        }
        val capPx = maxChipAreaHeightPx(CATEGORY_CHIPS_MAX_HEIGHT_DP)
        val heightPx = chipAreaHeightPx()
        assertTrue("分类区高度 $heightPx px 应正好被限在 $capPx px（168dp）", heightPx == capPx)
    }

    @Test
    fun categoryChips_canBeScrolledInsideTheCap() {
        composeRule.setContent {
            MaterialTheme {
                CategoryChips(
                    categories = categories(200),
                    selectedCategoryId = null,
                    onSelectCategory = {},
                    onCreateCategory = {},
                )
            }
        }
        composeRule.onNodeWithTag(CATEGORY_CHIPS_TEST_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
    }

    @Test
    fun categoryChips_takeOnlyTheirOwnHeight_whenThereAreAFew() {
        composeRule.setContent {
            MaterialTheme {
                CategoryChips(
                    categories = categories(3),
                    selectedCategoryId = null,
                    onSelectCategory = {},
                    onCreateCategory = {},
                )
            }
        }
        // 3 颗 chip 只占一行：远不到 168dp 的上限，说明限高不会给少量分类凭空加高度
        val heightPx = chipAreaHeightPx()
        assertTrue("3 颗分类时高度 $heightPx px 应在一行（<= 72dp）以内且大于 0", heightPx in 1..maxChipAreaHeightPx(72))
    }

    @Test
    fun categoryChips_scrollTheSelectedChipIntoView() {
        val selected = mutableStateOf<Long?>(null)
        composeRule.setContent {
            MaterialTheme {
                CategoryChips(
                    categories = categories(200),
                    selectedCategoryId = selected.value,
                    onSelectCategory = {},
                    onCreateCategory = {},
                )
            }
        }
        composeRule.waitForIdle()
        // 最后那颗一开始在框外（框只有 168dp 高）
        composeRule.onNodeWithText("分类199").assertIsNotDisplayed()

        selected.value = 200L
        composeRule.waitForIdle()

        composeRule.onNodeWithText("分类199").assertIsDisplayed()
    }
}
