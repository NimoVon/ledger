package com.nimo.bookkeeping.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nimo.bookkeeping.AppContainer
import com.nimo.bookkeeping.ui.accounts.AccountsScreen
import com.nimo.bookkeeping.ui.reports.ReportsScreen
import com.nimo.bookkeeping.ui.settings.SettingsScreen
import com.nimo.bookkeeping.ui.transactions.TransactionsScreen

/** 账户管理：从设置页进入的二级页面，不在底部导航里 */
private const val ACCOUNTS_ROUTE = "accounts"

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

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopDestination.entries.forEach { dest ->
                    val selected = currentDestination
                        ?.hierarchy
                        ?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(imageVector = dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.TRANSACTIONS.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopDestination.TRANSACTIONS.route) { TransactionsScreen() }
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
        }
    }
}
