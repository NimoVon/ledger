package com.nimo.ledger.ui.record

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.awaitUntil
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.AccountType
import com.nimo.ledger.domain.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 余额调整的删除入口（此前它进「这一笔不能在这里改」后无路可走）。
 *
 * 不删数据：点「删除这条余额调整」只发 requestDelete，整笔交给延迟删除策略，
 * 撤销窗口由流水页的 Snackbar 负责——和收支、转账的删除走同一条链。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class RecordAdjustmentDeleteTest {
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
    fun anAdjustmentCanBeDeletedFromTheReadOnlyPage() {
        var accountId = 0L
        var adjustId = 0L
        runBlocking {
            accountId = repository.createAccount("支付宝", AccountType.ALIPAY, 100_000L).getOrThrow()
            // 账面 1000 元 → 实际 1500 元，追加一笔 +500 元的余额调整
            adjustId = repository.adjustBalanceTo(accountId, 150_000L).getOrThrow()
        }

        var deleted: TransactionEntity? = null
        composeRule.setContent {
            MaterialTheme {
                RecordScreen(
                    repository = repository,
                    onBack = {},
                    onSaved = {},
                    editingId = adjustId,
                    onDeleted = { deleted = it },
                )
            }
        }

        awaitText("这一笔不能在这里改")
        awaitText("删除这条余额调整")
        composeRule.onNodeWithText("删除这条余额调整").performClick()

        composeRule.awaitUntil { deleted != null }
        assertEquals(TransactionType.BALANCE_ADJUST, deleted!!.type)
        assertEquals(adjustId, deleted!!.id)
    }

    private fun awaitText(text: String) {
        composeRule.awaitUntil {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
