package com.nimo.ledger.ui.transactions

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.nimo.ledger.MainDispatcherRule
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.SchemaHardening
import com.nimo.ledger.data.repository.LedgerRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T-3：「点行=编辑」的一次性提示。
 *
 * 标记落 DataStore（独立文件），跨进程只出现一次；ViewModel 只在未看过时亮出提示，
 * 点「知道了」后同时收起并把标记写盘。
 */
@RunWith(RobolectricTestRunner::class)
class RowTapHintStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var file: File
    private lateinit var storeScope: CoroutineScope

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        SchemaHardening.apply(db.openHelper.writableDatabase)
        file = File(context.filesDir, "row_tap_hint_test_${System.nanoTime()}.preferences_pb")
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        storeScope.cancel()
        db.close()
        file.delete()
    }

    private fun store(): RowTapHintStore =
        RowTapHintStore(PreferenceDataStoreFactory.create(scope = storeScope) { file })

    @Test
    fun firstLaunch_showsTheHint_andDismissingIt_persistsAcrossInstances() = runTest {
        val first = store()
        assertFalse("首次启动还没看过提示", first.hasShown())

        first.markShown()

        assertTrue("标记必须落盘", first.hasShown())
        // 同一文件只能有一个 DataStore 活着：先停掉第一个，再用新实例读同一个文件
        storeScope.cancel()
        storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 新实例读同一个文件：模拟冷启动后不再出现
        assertTrue("重启后仍是已看过", store().hasShown())
    }

    @Test
    fun viewModel_exposesTheHint_thenClearsItOnDismiss() = runTest {
        val repository = LedgerRepository(db)
        val store = store()
        val viewModel = TransactionsViewModel(
            repository,
            rowTapHintStore = store,
        )

        advanceUntilIdle()
        advanceUntilIdle()
        assertTrue("没看过时应该亮出提示", viewModel.showRowTapHint.value)

        viewModel.dismissRowTapHint()
        advanceUntilIdle()

        assertFalse("点「知道了」后立刻收起", viewModel.showRowTapHint.value)
        // 落盘由上一个用例单独验证：ViewModel 里是异步写，不在这里抢时序
    }
}
