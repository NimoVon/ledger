package com.nimo.bookkeeping.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nimo.bookkeeping.AppContainer
import com.nimo.bookkeeping.ui.accounts.AccountsScreen
import com.nimo.bookkeeping.ui.record.RecordScreen
import com.nimo.bookkeeping.ui.reports.ReportsScreen
import com.nimo.bookkeeping.ui.settings.SettingsScreen
import com.nimo.bookkeeping.ui.transactions.TransactionsScreen
import com.nimo.bookkeeping.ui.transfer.TransferScreen
import com.nimo.bookkeeping.util.Money

/** 账户管理：从设置页进入的二级页面，不在底部导航里 */
private const val ACCOUNTS_ROUTE = "accounts"

/** 记一笔（F03）：全屏表单，由顶层页面的「记一笔」按钮唤起（计划 F03「从任意页面可唤起」） */
private const val RECORD_ROUTE = "record"

/** 编辑哪一笔。挂在同一个目的地上作为可选参数：表单、校验、返回逻辑全部复用一套 */
private const val RECORD_TX_ID_ARG = "txId"

/**
 * 带可选参数的路由模式：导航到 "record" 是新建，导航到 "record?txId=12" 是编辑第 12 笔。
 * 缺省的查询参数由 Navigation 补成 null。
 */
private const val RECORD_ROUTE_PATTERN = "$RECORD_ROUTE?$RECORD_TX_ID_ARG={$RECORD_TX_ID_ARG}"

/**
 * 转账（F04）：转账页。
 * 新建的入口在记一笔页的「转账」分段（就地渲染同一块转账表单）；
 * 这个路由保留给「编辑转账」——点流水里的转账行进来，转账 ≠ 收支，路由与页面自成一套。
 */
private const val TRANSFER_ROUTE = "transfer"

/** 编辑哪一笔转账。与记一笔同款：新建时没有这个参数，带 txId 时是编辑 */
private const val TRANSFER_TX_ID_ARG = "txId"

/** 导航到 "transfer" 是新建，导航到 "transfer?txId=12" 是编辑第 12 笔 */
private const val TRANSFER_ROUTE_PATTERN = "$TRANSFER_ROUTE?$TRANSFER_TX_ID_ARG={$TRANSFER_TX_ID_ARG}"

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
    // 记账页/转账页都是沉浸式表单：收起底部导航，避免误切页丢掉已填内容
    val isRecordPage = currentDestination?.hierarchy?.any { it.route == RECORD_ROUTE_PATTERN } == true
    val isTransferPage =
        currentDestination?.hierarchy?.any { it.route == TRANSFER_ROUTE_PATTERN } == true
    val isFormPage = isRecordPage || isTransferPage

    // 延迟物理删除（计划 9.2）：删除后立刻从流水消失，「撤销」提示条挂在这里，
    // 倒计时跑在 Application 级作用域上，切页/返回都不会打断它。
    val pendingDeletion = container.pendingTransactionDeletion
    val pendingDeletions by pendingDeletion.pending.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 提示条只挂最新的一笔；其余几笔的倒计时照旧在跑，撤销是一笔一笔来的。
    // 时长由状态机定：撤销窗口就是提示条的寿命——窗口结束时这一笔从 pending 里摘掉，
    // 这里的 key 一变（LaunchedEffect 被取消）提示条随之关闭。页面因此不许再写第二个时长
    // （原先 Long ≈ 4s 与 5s 的窗口只是碰巧接近，谁都不能保证它们一直对齐）。
    val undoableDeletion = pendingDeletions.firstOrNull()
    LaunchedEffect(undoableDeletion?.id) {
        val entity = undoableDeletion ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "已删除 " + Money.formatCurrency(entity.amount),
            actionLabel = "撤销",
            duration = SnackbarDuration.Indefinite,
        )
        // 点了撤销就撤销这一笔；提示条被划掉则只是不再可撤销（物理删除仍按倒计时进行）
        if (result == SnackbarResult.ActionPerformed) {
            pendingDeletion.undo(entity.id)
        } else {
            pendingDeletion.dismiss(entity.id)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            // 记账页/转账页是沉浸式表单：收起底部导航，避免误切页丢掉已填内容
            if (!isFormPage) {
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
            // 「记一笔」在三个顶层页面都可达（计划 F03：从任意页面可唤起）。
            // 转账（F04）不再另开一个悬浮按钮：它的入口是记一笔页里的「转账」分段
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
                    // 点某一行 → 进编辑页（删除入口也在那页）。保存/删除后返回，列表同源自动刷新
                    onTransactionClick = { id ->
                        navController.navigate("$RECORD_ROUTE?$RECORD_TX_ID_ARG=$id") {
                            launchSingleTop = true
                        }
                    },
                    // 点转账行 → 转账页；剩下的才进记一笔
                    onTransferClick = { id ->
                        navController.navigate("$TRANSFER_ROUTE?$TRANSFER_TX_ID_ARG=$id") {
                            launchSingleTop = true
                        }
                    },
                    pendingDeletionIds = pendingDeletion.pendingIds,
                    // 下钻：统计页点了某一行时把条件交给本页（用掉即清，见 DrillDownRequests）
                    drillDown = container.drillDownRequests,
                )
            }
            composable(TopDestination.REPORTS.route) {
                ReportsScreen(
                    repository = container.repository,
                    // 撤销窗口内那几笔数据库里还在：月报要和明细页、账户页一样当它们不存在
                    pendingDeletionIds = pendingDeletion.pendingIds,
                    // 点排行/账户分布的一行 → 记下"看什么"，再切到明细页由它自己套上条件。
                    // 请求先落容器再导航：明细页的 ViewModel 早就在跑，切过去立刻就能收到。
                    onDrillDown = { filter ->
                        container.drillDownRequests.request(filter)
                        navController.navigate(TopDestination.TRANSACTIONS.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
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
                    // 撤销窗口内那几笔数据库里还在：账户余额要和流水页一样当它们不存在（计划 9.2）
                    pendingDeletionIds = pendingDeletion.pendingIds,
                )
            }
            composable(
                route = RECORD_ROUTE_PATTERN,
                arguments = listOf(
                    navArgument(RECORD_TX_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                RecordScreen(
                    repository = container.repository,
                    // 缺省（新建）时是 null；形如 "12" 时是编辑第 12 笔
                    editingId = entry.arguments?.getString(RECORD_TX_ID_ARG)?.toLongOrNull(),
                    onBack = { navController.popBackStack() },
                    // 保存成功即返回；流水页观察的是同一份 Room Flow，会自动看到新记录 / 改动
                    onSaved = { navController.popBackStack() },
                    onDeleted = { entity ->
                        // 先暂存（流水页立刻看不到它），再返回；倒计时内可撤销
                        pendingDeletion.schedule(entity)
                        navController.popBackStack()
                    },
                )
            }
            composable(
                route = TRANSFER_ROUTE_PATTERN,
                arguments = listOf(
                    navArgument(TRANSFER_TX_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                TransferScreen(
                    repository = container.repository,
                    // 缺省（新建）时是 null；形如 "12" 时是编辑第 12 笔
                    editingId = entry.arguments?.getString(TRANSFER_TX_ID_ARG)?.toLongOrNull(),
                    onBack = { navController.popBackStack() },
                    // 保存成功即返回；明细页观察的是同一份 Room Flow，会自动刷新余额与流水
                    onSaved = { navController.popBackStack() },
                    // 撤销窗口内那几笔数据库里还在：余额推导要和流水页一样当它们不存在
                    pendingDeletionIds = pendingDeletion.pendingIds,
                    onDeleted = { entity ->
                        pendingDeletion.schedule(entity)
                        navController.popBackStack()
                    },
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
