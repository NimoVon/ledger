package com.nimo.bookkeeping.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nimo.bookkeeping.AppContainer
import com.nimo.bookkeeping.ui.accounts.AccountsScreen
import com.nimo.bookkeeping.ui.record.RecordScreen
import com.nimo.bookkeeping.ui.reports.ReportsScreen
import com.nimo.bookkeeping.ui.settings.SettingsScreen
import com.nimo.bookkeeping.ui.transactions.TransactionsScreen

/** 账户管理：从设置页进入的二级页面，不在底部导航里 */
private const val ACCOUNTS_ROUTE = "accounts"

/** 记一笔（F03）：全屏表单，由顶层页面的「记一笔」按钮唤起（计划 F03「从任意页面可唤起」） */
private const val RECORD_ROUTE = "record"

/** 顶层导航目的地：底部三个 Tab（明细 / 统计 / 设置） */
private enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    TRANSACTIONS("transactions", "明细", LedgerIcons.List),
    REPORTS("reports", "统计", LedgerIcons.Chart),
    SETTINGS("settings", "设置", LedgerIcons.Sliders),
}

/**
 * 应用根界面：底部导航 + NavHost（计划 4.1 页面导航选型 Navigation Compose）。
 * 切换到已选中的 Tab 不重建页面（launchSingleTop + restoreState）。
 */
@Composable
fun LedgerApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val isTopLevel = TopDestination.entries.any { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }
    // 记账页是沉浸式表单：收起底部导航，避免误切页丢掉已填内容
    val isRecordPage = currentDestination?.hierarchy?.any { it.route == RECORD_ROUTE } == true

    Scaffold(
        bottomBar = {
            // 记账页是沉浸式表单：收起底部导航，避免误切页丢掉已填内容
            if (!isRecordPage) {
                LedgerBottomBar(
                    currentDestination = currentDestination,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            // 「记一笔」在三个顶层页面都可达（计划 F03：从任意页面可唤起）
            if (isTopLevel) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(RECORD_ROUTE) { launchSingleTop = true } },
                    icon = { Icon(imageVector = LedgerIcons.Add, contentDescription = null) },
                    text = { Text("记一笔") },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.TRANSACTIONS.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopDestination.TRANSACTIONS.route) {
                TransactionsScreen(
                    repository = container.repository,
                    // F05 后续：单击流水进入编辑 / 删除 + 撤销。本轮只留入口，不实现。
                    onTransactionClick = { },
                )
            }
            composable(TopDestination.REPORTS.route) { ReportsScreen() }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen(
                    container = container,
                    onOpenAccounts = { navController.navigate(ACCOUNTS_ROUTE) },
                )
            }
            composable(ACCOUNTS_ROUTE) {
                AccountsScreen(
                    repository = container.repository,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(RECORD_ROUTE) {
                RecordScreen(
                    repository = container.repository,
                    onBack = { navController.popBackStack() },
                    // 保存成功即返回；流水页观察的是同一份 Room Flow，会自动看到新记录
                    onSaved = { navController.popBackStack() },
                )
            }
        }
    }
}

/** 底部导航栏。抽出来只是为了让 Scaffold 的 bottomBar 保持短小。 */
@Composable
private fun LedgerBottomBar(
    currentDestination: NavDestination?,
    onNavigate: (String) -> Unit,
) {
    NavigationBar {
        TopDestination.entries.forEach { dest ->
            val selected = currentDestination
                ?.hierarchy
                ?.any { it.route == dest.route } == true
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(dest.route) },
                icon = { Icon(imageVector = dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}
