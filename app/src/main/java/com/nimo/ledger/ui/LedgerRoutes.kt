package com.nimo.ledger.ui

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
