package com.nimo.ledger.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 导航转场口径的纯 JVM 验收（UI-UX 规范 §11 批 E 之前的返回体验修复）。
 *
 * 这里盯的是"决定"，不是 Compose 的动画实现：页面属于哪一类、这一次导航该用哪种位移，
 * 全部是不依赖 Android 的纯函数，所以能在 JVM 上逐条钉死。
 */
class LedgerNavMotionTest {

    @Test
    fun theThreeTabs_areTabs() {
        assertEquals(PageKind.TAB, pageKindOf(LedgerRoutes.TRANSACTIONS))
        assertEquals(PageKind.TAB, pageKindOf(LedgerRoutes.REPORTS))
        assertEquals(PageKind.TAB, pageKindOf(LedgerRoutes.SETTINGS))
    }

    @Test
    fun settingsSubPages_areDetails() {
        assertEquals(PageKind.DETAIL, pageKindOf(LedgerRoutes.ACCOUNTS))
        assertEquals(PageKind.DETAIL, pageKindOf(LedgerRoutes.CATEGORIES))
        assertEquals(PageKind.DETAIL, pageKindOf(LedgerRoutes.BUDGETS))
    }

    /**
     * 下钻页虽然是"明细"，但在导航上是压在统计页之上的一层：
     * 按类别算二级页，转场才会有"往里走了一层"的方向感。
     */
    @Test
    fun theDrillDownPage_isADetailLayer_withItsOwnRoute() {
        assertEquals(PageKind.DETAIL, pageKindOf(LedgerRoutes.TRANSACTIONS_DRILL))
        // 必须是独立路由：与普通明细同名就复用了 Tab 的返回栈，返回会退出应用
        org.junit.Assert.assertNotEquals(LedgerRoutes.TRANSACTIONS, LedgerRoutes.TRANSACTIONS_DRILL)
    }

    @Test
    fun recordAndTransfer_areForms_includingTheirParameterisedPatterns() {
        assertEquals(PageKind.FORM, pageKindOf(LedgerRoutes.RECORD_PATTERN))
        assertEquals(PageKind.FORM, pageKindOf(LedgerRoutes.TRANSFER_PATTERN))
    }

    @Test
    fun unknownOrMissingRoutes_fallBackToTab_insteadOfCrashing() {
        assertEquals(PageKind.TAB, pageKindOf(null))
        assertEquals(PageKind.TAB, pageKindOf("no/such/route"))
        assertEquals(NavMotionKind.FADE, navMotionOf("no/such/route", null, isPop = false))
    }

    @Test
    fun switchingBetweenTabs_onlyFades() {
        assertEquals(
            NavMotionKind.FADE,
            navMotionOf(LedgerRoutes.TRANSACTIONS, LedgerRoutes.REPORTS, isPop = false),
        )
        assertEquals(
            NavMotionKind.FADE,
            navMotionOf(LedgerRoutes.REPORTS, LedgerRoutes.SETTINGS, isPop = false),
        )
    }

    @Test
    fun drillingFromReports_slidesHorizontally() {
        assertEquals(
            NavMotionKind.SLIDE_HORIZONTAL,
            navMotionOf(LedgerRoutes.REPORTS, LedgerRoutes.TRANSACTIONS_DRILL, isPop = false),
        )
        assertEquals(
            NavMotionKind.SLIDE_HORIZONTAL,
            navMotionOf(LedgerRoutes.SETTINGS, LedgerRoutes.ACCOUNTS, isPop = false),
        )
    }

    @Test
    fun goingBackFromADetail_slidesHorizontally() {
        assertEquals(
            NavMotionKind.SLIDE_HORIZONTAL,
            navMotionOf(LedgerRoutes.TRANSACTIONS_DRILL, LedgerRoutes.REPORTS, isPop = true),
        )
        assertEquals(
            NavMotionKind.SLIDE_HORIZONTAL,
            navMotionOf(LedgerRoutes.ACCOUNTS, LedgerRoutes.SETTINGS, isPop = true),
        )
    }

    @Test
    fun formsSlideVertically_bothWays() {
        assertEquals(
            NavMotionKind.SLIDE_VERTICAL,
            navMotionOf(LedgerRoutes.TRANSACTIONS, LedgerRoutes.RECORD_PATTERN, isPop = false),
        )
        assertEquals(
            NavMotionKind.SLIDE_VERTICAL,
            navMotionOf(LedgerRoutes.RECORD_PATTERN, LedgerRoutes.TRANSACTIONS, isPop = true),
        )
        // 从下钻页打开编辑页、再返回，仍然是表单的纵向动作
        assertEquals(
            NavMotionKind.SLIDE_VERTICAL,
            navMotionOf(LedgerRoutes.TRANSACTIONS_DRILL, LedgerRoutes.RECORD_PATTERN, isPop = false),
        )
        assertEquals(
            NavMotionKind.SLIDE_VERTICAL,
            navMotionOf(LedgerRoutes.RECORD_PATTERN, LedgerRoutes.TRANSACTIONS_DRILL, isPop = true),
        )
    }

    @Test
    fun poppingAForm_alwaysFallsStraightDown_evenWhenThePageUnderItIsADetail() {
        // 表单从底部压上来，就该原路落回去：落点是不是二级页，不影响纵向退出
        assertEquals(
            NavMotionKind.SLIDE_VERTICAL,
            navMotionOf(LedgerRoutes.RECORD_PATTERN, LedgerRoutes.ACCOUNTS, isPop = true),
        )
        assertEquals(
            NavMotionKind.SLIDE_VERTICAL,
            navMotionOf(LedgerRoutes.TRANSFER_PATTERN, LedgerRoutes.BUDGETS, isPop = true),
        )
    }

    @Test
    fun motionDurations_areShortEnoughToFeelResponsive() {
        assertEquals(220, LedgerNavMotion.ENTER_MILLIS)
        assertEquals(180, LedgerNavMotion.EXIT_MILLIS)
    }
}
