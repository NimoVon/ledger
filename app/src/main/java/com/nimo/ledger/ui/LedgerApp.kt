package com.nimo.ledger.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nimo.ledger.AppContainer
import com.nimo.ledger.ui.accounts.AccountsScreen
import com.nimo.ledger.ui.categories.CategoriesScreen
import com.nimo.ledger.ui.record.RecordScreen
import com.nimo.ledger.ui.reports.ReportsScreen
import com.nimo.ledger.ui.settings.SettingsScreen
import com.nimo.ledger.ui.budgets.BudgetsScreen
import com.nimo.ledger.ui.transactions.TransactionsScreen
import com.nimo.ledger.ui.transfer.TransferScreen
import com.nimo.ledger.util.Money
import kotlinx.coroutines.flow.Flow

/**
 * 「记一笔」按钮的文字：按钮文字与读屏名共用这一份。
 *
 * 真机实测（F15）：这个悬浮按钮在整个无障碍树上既没有 text 也没有 content-desc，
 * Android 自己给它标了 NAF="true"，TalkBack 读出来只有"按钮"两个字、说不出是干什么的，
 * 所以在这里显式补一条语义。三处悬浮按钮（记一笔 / 新建账户 / 新建分类）同一处理。
 */
private const val RECORD_FAB_LABEL = "记一笔"

/** 顶层导航目的地：底部三个 Tab（明细 / 统计 / 设置） */
private enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    TRANSACTIONS(LedgerRoutes.TRANSACTIONS, "明细", LedgerIcons.List),
    REPORTS(LedgerRoutes.REPORTS, "统计", LedgerIcons.Chart),
    SETTINGS(LedgerRoutes.SETTINGS, "设置", LedgerIcons.Sliders),
}

/**
 * 应用根界面：底部导航 + NavHost（计划 4.1 页面导航选型 Navigation Compose）。
 * 切换到已选中的 Tab 不重建页面（launchSingleTop + restoreState）。
 *
 * 返回栈口径：底部三个 Tab 是平级页（切 Tab 用 popUpTo 起始页 + saveState/restoreState 复用实例）；
 * 二级页与表单是压栈（返回键自然 pop 回上一层）；统计页下钻是**压在统计页之上的一层明细**，
 * 所以按返回会回到统计页而不是退出应用（见 [LedgerRoutes.TRANSACTIONS_DRILL]）。
 */
@Composable
fun LedgerApp(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val isTopLevel = currentDestination?.hierarchy?.any { entry ->
        entry.route != null && entry.route in LedgerRoutes.TOP_LEVEL_ROUTES
    } == true
    // 记账页/转账页都是沉浸式表单：收起底部导航，避免误切页丢掉已填内容
    val isRecordPage = currentDestination?.hierarchy?.any { it.route == LedgerRoutes.RECORD_PATTERN } == true
    val isTransferPage =
        currentDestination?.hierarchy?.any { it.route == LedgerRoutes.TRANSFER_PATTERN } == true
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
                    // 切 Tab 的唯一实现（含「先丢掉下钻页」的修复），见 NavHostController.switchTab
                    onNavigate = navController::switchTab,
                )
            }
        },
        floatingActionButton = {
            // 「记一笔」在三个顶层页面都可达（计划 F03：从任意页面可唤起）。
            // 转账（F04）不再另开一个悬浮按钮：它的入口是记一笔页里的「转账」分段
            if (isTopLevel) {
                ExtendedFloatingActionButton(
                    onClick = { navController.navigate(LedgerRoutes.RECORD) { launchSingleTop = true } },
                    modifier = Modifier.semantics { contentDescription = RECORD_FAB_LABEL },
                    icon = { Icon(imageVector = LedgerIcons.Add, contentDescription = null) },
                    text = { Text(RECORD_FAB_LABEL) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopDestination.TRANSACTIONS.route,
            // 键盘弹起时整栈内容跟着缩（记一笔页表单不再被输入法压住），
            // 并把外层已经吃掉的系统栏 insets 标记为已消费：各页面自带的 Scaffold 不会再加一遍状态栏高度
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            // 转场：Tab 之间淡入淡出，二级页横向推进/退出，表单纵向压上来/落回去（见 LedgerNavMotion）
            enterTransition = {
                LedgerNavMotion.enter(initialState.destination.route, targetState.destination.route)
            },
            exitTransition = {
                LedgerNavMotion.exit(initialState.destination.route, targetState.destination.route)
            },
            popEnterTransition = {
                LedgerNavMotion.popEnter(initialState.destination.route, targetState.destination.route)
            },
            popExitTransition = {
                LedgerNavMotion.popExit(initialState.destination.route, targetState.destination.route)
            },
        ) {
            composable(LedgerRoutes.TRANSACTIONS) {
                TransactionsEntry(
                    container = container,
                    navController = navController,
                    pendingDeletionIds = pendingDeletion.pendingIds,
                    // 普通明细 Tab 不订阅下钻脉冲：同一时刻只允许一个明细页去消费请求，
                    // 否则隐藏的那一页会先把条件吃掉（见 DrillDownRequests 的 KDoc）
                    drillDown = null,
                )
            }
            // 下钻出来的明细：压在统计页之上，返回即回到统计页
            composable(LedgerRoutes.TRANSACTIONS_DRILL) {
                TransactionsEntry(
                    container = container,
                    navController = navController,
                    pendingDeletionIds = pendingDeletion.pendingIds,
                    drillDown = container.drillDownRequests,
                )
            }
            composable(TopDestination.REPORTS.route) {
                ReportsScreen(
                    repository = container.repository,
                    // 撤销窗口内那几笔数据库里还在：月报要和明细页、账户页一样当它们不存在
                    pendingDeletionIds = pendingDeletion.pendingIds,
                    // 点排行/账户分布的一行 → 记下"看什么"，再压一层明细页由它自己套上条件。
                    // 请求先落容器再导航：明细页的 ViewModel 起来时立刻就能收到。
                    onDrillDown = { filter ->
                        navController.openDrillDown(filter, container.drillDownRequests)
                    },
                )
            }
            composable(TopDestination.SETTINGS.route) {
                SettingsScreen(
                    container = container,
                    onOpenAccounts = { navController.navigate(LedgerRoutes.ACCOUNTS) },
                    onOpenCategories = { navController.navigate(LedgerRoutes.CATEGORIES) },
                    onOpenBudgets = { navController.navigate(LedgerRoutes.BUDGETS) },
                )
            }
            composable(LedgerRoutes.CATEGORIES) {
                CategoriesScreen(
                    repository = container.repository,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(LedgerRoutes.BUDGETS) {
                BudgetsScreen(
                    repository = container.repository,
                    // 撤销窗口内那几笔数据库里还在：预算口径要和流水页一样当它们不存在（计划 9.2）
                    pendingDeletionIds = pendingDeletion.pendingIds,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(LedgerRoutes.ACCOUNTS) {
                AccountsScreen(
                    repository = container.repository,
                    onBack = { navController.popBackStack() },
                    // 撤销窗口内那几笔数据库里还在：账户余额要和流水页一样当它们不存在（计划 9.2）
                    pendingDeletionIds = pendingDeletion.pendingIds,
                )
            }
            composable(
                route = LedgerRoutes.RECORD_PATTERN,
                arguments = listOf(
                    navArgument(LedgerRoutes.RECORD_TX_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                RecordScreen(
                    repository = container.repository,
                    // 缺省（新建）时是 null；形如 "12" 时是编辑第 12 笔
                    editingId = entry.arguments?.getString(LedgerRoutes.RECORD_TX_ID_ARG)?.toLongOrNull(),
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
                route = LedgerRoutes.TRANSFER_PATTERN,
                arguments = listOf(
                    navArgument(LedgerRoutes.TRANSFER_TX_ID_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                TransferScreen(
                    repository = container.repository,
                    // 缺省（新建）时是 null；形如 "12" 时是编辑第 12 笔
                    editingId = entry.arguments?.getString(LedgerRoutes.TRANSFER_TX_ID_ARG)?.toLongOrNull(),
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

/**
 * 明细页（普通 Tab 与下钻页共用同一段接线，避免两处改一处漏一处）。
 *
 * [drillDown] 只有下钻页才传：它是"一次性脉冲"的消费者，同一时刻只能有一个明细页订阅。
 */
@Composable
private fun TransactionsEntry(
    container: AppContainer,
    navController: NavHostController,
    pendingDeletionIds: Flow<Set<Long>>,
    drillDown: DrillDownRequests?,
) {
    TransactionsScreen(
        repository = container.repository,
        // 点某一行 → 进编辑页（删除入口也在那页）。保存/删除后返回，列表同源自动刷新
        onTransactionClick = { id ->
            navController.navigate(LedgerRoutes.RECORD + "?" + LedgerRoutes.RECORD_TX_ID_ARG + "=" + id) {
                launchSingleTop = true
            }
        },
        onTransferClick = { id ->
            navController.navigate(LedgerRoutes.TRANSFER + "?" + LedgerRoutes.TRANSFER_TX_ID_ARG + "=" + id) {
                launchSingleTop = true
            }
        },
        pendingDeletionIds = pendingDeletionIds,
        drillDown = drillDown,
    )
}

/** 底部导航栏。抽出来只是为了让 Scaffold 的 bottomBar 保持短小。 */
@Composable
private fun LedgerBottomBar(
    currentDestination: NavDestination?,
    onNavigate: (String) -> Unit,
) {
    val onDrillDownPage = currentDestination
        ?.hierarchy
        ?.any { it.route == LedgerRoutes.TRANSACTIONS_DRILL } == true
    NavigationBar {
        TopDestination.entries.forEach { dest ->
            val selected = currentDestination
                ?.hierarchy
                ?.any { it.route == dest.route } == true ||
                // 下钻页是明细页的另一种入口：底栏仍高亮「明细」
                (dest == TopDestination.TRANSACTIONS && onDrillDownPage)
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(dest.route) },
                icon = { Icon(imageVector = dest.icon, contentDescription = dest.label) },
                label = { Text(dest.label) },
            )
        }
    }
}
