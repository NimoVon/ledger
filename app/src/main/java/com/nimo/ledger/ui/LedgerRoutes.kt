package com.nimo.ledger.ui

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.nimo.ledger.domain.filter.TransactionFilter

/**
 * 全应用路由名的唯一出处。
 *
 * 路由名原来散在 [LedgerApp] 的 private const 里，导航动画和「这个页面算 Tab 还是二级页」
 * 的判断也需要同一份名字，所以收在这里：改路由名只改这一处。
 */
internal object LedgerRoutes {

    /** 明细：底部第一个 Tab */
    const val TRANSACTIONS = "transactions"

    /**
     * 统计页下钻到的明细（F07）：**单独一层目的地**，不是把 Tab 的明细页复用一遍。
     *
     * 为什么单独开一层：下钻是"从统计走到明细"的一次前进，用户按返回应该回到统计页。
     * 早先的做法是先 [androidx.navigation.NavController.navigate] 到明细 Tab 再 popUpTo 起始页，
     * 返回栈只剩起始页一项，系统返回直接结束进程——用户看到的就是"点进明细后一按返回就退出应用"。
     */
    const val TRANSACTIONS_DRILL = "transactions/drill"

    const val REPORTS = "reports"
    const val SETTINGS = "settings"

    /** 账户管理：从设置页进入的二级页面，不在底部导航里 */
    const val ACCOUNTS = "accounts"

    /** 分类管理（F02）：同样从设置页进入 */
    const val CATEGORIES = "categories"

    /** 预算管理（F09）：同样从设置页进入 */
    const val BUDGETS = "budgets"

    /** 记一笔（F03）：全屏表单，由顶层页面的「记一笔」按钮唤起 */
    const val RECORD = "record"

    /** 编辑哪一笔。挂在同一个目的地上作为可选参数：表单、校验、返回逻辑全部复用一套 */
    const val RECORD_TX_ID_ARG = "txId"

    /** 导航到 "record" 是新建，导航到 "record?txId=12" 是编辑第 12 笔 */
    const val RECORD_PATTERN = "$RECORD?$RECORD_TX_ID_ARG={$RECORD_TX_ID_ARG}"

    /** 转账（F04）：编辑转账用；新建入口在记一笔页的「转账」分段 */
    const val TRANSFER = "transfer"

    const val TRANSFER_TX_ID_ARG = "txId"

    /** 导航到 "transfer" 是新建，导航到 "transfer?txId=12" 是编辑第 12 笔 */
    const val TRANSFER_PATTERN = "$TRANSFER?$TRANSFER_TX_ID_ARG={$TRANSFER_TX_ID_ARG}"

    /** 底部三个 Tab */
    val TAB_ROUTES: Set<String> = setOf(TRANSACTIONS, REPORTS, SETTINGS)

    /** 从设置页进出的二级页 */
    val DETAIL_ROUTES: Set<String> = setOf(ACCOUNTS, CATEGORIES, BUDGETS)

    /**
     * 底部导航与「记一笔」悬浮按钮认的"顶层页"：三个 Tab 加下钻页。
     * 下钻页看起来就是一个明细页，底栏不该消失、也不该丢掉「记一笔」。
     */
    val TOP_LEVEL_ROUTES: Set<String> = TAB_ROUTES + TRANSACTIONS_DRILL
}

/**
 * 统计页点某行后的下钻：把条件放进 [DrillDownRequests]，再向前压一层 [LedgerRoutes.TRANSACTIONS_DRILL]。
 *
 * 关键是**不做 popUpTo**：请求先落容器、页面随后入栈，明细页的 ViewModel 起来时就能收到；
 * 返回时系统 pop 回统计页。把这段集中在扩展函数里，是为了让「返回栈里有统计页」这件事
 * 有唯一实现、也有测试能盯着（见 LedgerDrillDownNavigationTest）。
 */
internal fun NavHostController.openDrillDown(
    filter: TransactionFilter,
    requests: DrillDownRequests,
) {
    requests.request(filter)
    navigate(LedgerRoutes.TRANSACTIONS_DRILL) {
        launchSingleTop = true
    }
}

/**
 * 切底部 Tab 的唯一实现，分两条路走：
 *
 * 1. 非起始 Tab：popUpTo 起始页 + saveState/restoreState，让每个 Tab 复用自己的页面实例。
 * 2. 回起始 Tab（明细）：直接 `popBackStack(起始页, inclusive = false, saveState = true)`。
 *    不能走第 1 条：`navigate(起始页) { restoreState = true }` 会把「之前压在起始页之上」的那段
 *    （比如统计页）当成起始页自己的状态整段恢复回来，用户点「明细」看到的还是统计页
 *    （实测 `[transactions, reports]` 上点明细 → 仍是 `[transactions, reports]`，见
 *    LedgerDrillDownNavigationTest 里两条明细 Tab 的用例）。
 *
 * 下钻页是压在统计页之上的一次性结果，不能跟着 Tab 的状态一起存（原因见 [LedgerRoutes.TRANSACTIONS_DRILL]
 * 与这里的 drop 分支）：被 `popUpTo(起始页) { saveState = true }` 和统计页一起弹出时，Navigation 会以
 * 最深的那个页面为键把这一段整存进 `backStackStates`，紧接着 `navigate(统计) { restoreState = true }`
 * 又按同一个键整段恢复——下钻页被一起复活，表现就是「站在下钻页点『统计』没反应」。
 * 所以先把它单独 pop 掉（不保存），再去切 Tab。
 */
internal fun NavHostController.switchTab(route: String) {
    if (currentDestination?.route == LedgerRoutes.TRANSACTIONS_DRILL) {
        popBackStack(LedgerRoutes.TRANSACTIONS_DRILL, inclusive = true)
    }
    if (route == graph.findStartDestination().route) {
        // saveState 保留统计页的滚动位置与状态，下次点回统计仍然接得上
        popBackStack(route, inclusive = false, saveState = true)
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}
