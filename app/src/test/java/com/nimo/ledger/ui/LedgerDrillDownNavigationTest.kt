package com.nimo.ledger.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nimo.ledger.domain.filter.TransactionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 下钻返回栈的回归测试（用户反馈：从统计点明细后，一按返回就退出应用）。
 *
 * 复现的是真实图结构：起始页是明细 Tab，统计页是 Tab 切过去的，下钻是压在统计页之上的一层。
 * 早先的实现用 popUpTo(起始页) 落到明细 Tab，返回栈只剩起始页一项，系统返回直接结束进程；
 * 现在 [LedgerRoutes.TRANSACTIONS_DRILL] 是独立目的地、不 popUpTo，所以统计页仍在栈里。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class LedgerDrillDownNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setGraph() {
        composeRule.setContent {
            navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = LedgerRoutes.TRANSACTIONS,
            ) {
                composable(LedgerRoutes.TRANSACTIONS) { Text("明细页") }
                composable(LedgerRoutes.REPORTS) { Text("统计页") }
                composable(LedgerRoutes.TRANSACTIONS_DRILL) { Text("下钻明细页") }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun drillingFromReports_leavesReportsOnTheBackStack() {
        val requests = DrillDownRequests()
        val filter = TransactionFilter(categoryIds = setOf(7L))
        setGraph()

        composeRule.runOnIdle { navController.navigate(LedgerRoutes.REPORTS) }
        composeRule.runOnIdle { navController.openDrillDown(filter, requests) }
        composeRule.waitForIdle()

        assertEquals(LedgerRoutes.TRANSACTIONS_DRILL, navController.currentDestination?.route)
        // 关键断言：统计页还在下面。旧实现这里会是「明细」甚至只有起始页，返回即退出应用
        assertEquals(LedgerRoutes.REPORTS, navController.previousBackStackEntry?.destination?.route)
        // 请求先落容器，明细页的 ViewModel 起来时就能收到
        assertEquals(filter, requests.pending.value)
    }

    @Test
    fun pressingBackFromTheDrillDownPage_returnsToReports() {
        val requests = DrillDownRequests()
        setGraph()

        composeRule.runOnIdle { navController.navigate(LedgerRoutes.REPORTS) }
        composeRule.runOnIdle { navController.openDrillDown(TransactionFilter(noteKeyword = "地铁"), requests) }
        composeRule.waitForIdle()

        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()

        assertEquals(LedgerRoutes.REPORTS, navController.currentDestination?.route)
        // 再返回一次才回起始页；也就是说第一下返回不会被系统当成"退出应用"
        assertNotNull(navController.previousBackStackEntry)
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        assertEquals(LedgerRoutes.TRANSACTIONS, navController.currentDestination?.route)
    }

    @Test
    fun drillingTwice_doesNotStackTwoDrillPages() {
        val requests = DrillDownRequests()
        setGraph()

        composeRule.runOnIdle { navController.navigate(LedgerRoutes.REPORTS) }
        composeRule.runOnIdle { navController.openDrillDown(TransactionFilter(categoryIds = setOf(1L)), requests) }
        composeRule.waitForIdle()
        composeRule.runOnIdle { navController.openDrillDown(TransactionFilter(categoryIds = setOf(2L)), requests) }
        composeRule.waitForIdle()

        assertEquals(LedgerRoutes.TRANSACTIONS_DRILL, navController.currentDestination?.route)
        composeRule.runOnIdle { navController.popBackStack() }
        composeRule.waitForIdle()
        // 一次返回就回到统计页：没有叠第二层下钻页
        assertEquals(LedgerRoutes.REPORTS, navController.currentDestination?.route)
        assertEquals(setOf(2L), requests.pending.value?.categoryIds)
    }

    @Test
    fun theDrillDownRoute_isRecognisedAsATopLevelPage_soTheBottomBarStays() {
        // 顶栏/底栏/悬浮按钮认的是这份集合（见 LedgerApp.isTopLevel）
        org.junit.Assert.assertTrue(LedgerRoutes.TRANSACTIONS_DRILL in LedgerRoutes.TOP_LEVEL_ROUTES)
        org.junit.Assert.assertTrue(LedgerRoutes.TRANSACTIONS in LedgerRoutes.TOP_LEVEL_ROUTES)
        org.junit.Assert.assertTrue(LedgerRoutes.REPORTS in LedgerRoutes.TOP_LEVEL_ROUTES)
        // 二级页与表单不在里面（进去要收底栏）
        org.junit.Assert.assertFalse(LedgerRoutes.RECORD_PATTERN in LedgerRoutes.TOP_LEVEL_ROUTES)
        org.junit.Assert.assertFalse(LedgerRoutes.ACCOUNTS in LedgerRoutes.TOP_LEVEL_ROUTES)
    }
}
