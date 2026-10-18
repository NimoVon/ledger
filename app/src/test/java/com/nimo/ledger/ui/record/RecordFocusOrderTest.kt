package com.nimo.ledger.ui.record

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.awaitUntil
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.CategoryKind
import androidx.compose.ui.focus.FocusManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记一笔表单的焦点顺序（UI-UX 规范 AA-6，批 F · F-5 收口）。
 *
 * 规格要求：金额 → 分类 → 账户 → 日期 → 备注 → 保存。
 * 实现用 [androidx.compose.ui.focus.focusProperties] 把每个字段的 `next` / `previous`
 * 显式接到下一个锚点，不再依赖「声明顺序碰巧正确」——今天加一个装饰 Text 或挪一段布局，
 * 默认顺序就可能悄悄改掉，读屏用户第一个遭殃。
 *
 * 这里用 FocusManager.moveFocus(FocusDirection.Next) 逐步推进并断言焦点落在哪个 tag 上；
 * 账号 / 分类都种了两条以上，用来证明「跨过组内其余 chip，直接进下一组首项」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RecordFocusOrderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: LedgerRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        repository = LedgerRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun focusWalksAmountCategoryAccountDateNoteSave_andStopsAtSave() {
        seedTwoAccountsTwoCategories()
        val focusManager = launchRecordScreen()

        awaitText("金额")
        // 新建时进页面就聚焦金额（R-3），焦点链从它开始
        composeRule.onNodeWithTag(RECORD_AMOUNT_TEST_TAG).assertIsFocused()

        moveNext(focusManager)
        composeRule.onNodeWithTag(RECORD_CATEGORY_FIRST_TEST_TAG).assertIsFocused()

        // 分类组里不止一颗 chip，但 next 被显式接到账户首项，不会停在「第二颗分类」上
        moveNext(focusManager)
        composeRule.onNodeWithTag(RECORD_ACCOUNT_FIRST_TEST_TAG).assertIsFocused()

        moveNext(focusManager)
        composeRule.onNodeWithTag(RECORD_DATE_TEST_TAG).assertIsFocused()

        moveNext(focusManager)
        composeRule.onNodeWithTag(RECORD_NOTE_TEST_TAG).assertIsFocused()

        moveNext(focusManager)
        composeRule.onNodeWithTag(RECORD_SAVE_BOTTOM_TEST_TAG).assertIsFocused()

        // 保存是终点：next = FocusRequester.Cancel，再按一次前进不会绕回表单开头
        moveNext(focusManager)
        composeRule.onNodeWithTag(RECORD_SAVE_BOTTOM_TEST_TAG).assertIsFocused()
    }

    @Test
    fun focusWalksBackwardsFromSaveToNote() {
        seedTwoAccountsTwoCategories()
        val focusManager = launchRecordScreen()

        awaitText("金额")
        composeRule.onNodeWithTag(RECORD_SAVE_BOTTOM_TEST_TAG).requestFocus()
        composeRule.onNodeWithTag(RECORD_SAVE_BOTTOM_TEST_TAG).assertIsFocused()

        composeRule.runOnIdle { focusManager.moveFocus(FocusDirection.Previous) }
        composeRule.onNodeWithTag(RECORD_NOTE_TEST_TAG).assertIsFocused()

        composeRule.runOnIdle { focusManager.moveFocus(FocusDirection.Previous) }
        composeRule.onNodeWithTag(RECORD_DATE_TEST_TAG).assertIsFocused()

        composeRule.runOnIdle { focusManager.moveFocus(FocusDirection.Previous) }
        composeRule.onNodeWithTag(RECORD_ACCOUNT_FIRST_TEST_TAG).assertIsFocused()
    }

    private fun launchRecordScreen(): FocusManager {
        lateinit var focusManager: FocusManager
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                RecordScreen(
                    repository = repository,
                    onBack = {},
                    onSaved = {},
                )
            }
        }
        return focusManager
    }

    private fun moveNext(focusManager: FocusManager) {
        composeRule.runOnIdle { focusManager.moveFocus(FocusDirection.Next) }
    }

    private fun seedTwoAccountsTwoCategories() {
        runBlocking {
            repository.createAccount("支付宝", AccountType.ALIPAY, 0L).getOrThrow()
            repository.createAccount("微信", AccountType.WECHAT, 0L).getOrThrow()
            db.categoryDao().upsert(
                CategoryEntity(name = "餐饮", kind = CategoryKind.EXPENSE, icon = "🍜", sortOrder = 0),
            )
            db.categoryDao().upsert(
                CategoryEntity(name = "交通", kind = CategoryKind.EXPENSE, icon = "🚌", sortOrder = 1),
            )
        }
    }

    private fun awaitText(text: String) {
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
