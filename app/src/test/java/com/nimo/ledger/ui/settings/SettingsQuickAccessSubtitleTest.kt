package com.nimo.ledger.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** §7.3 第 3 条：设置页三个子页入口的副标题文案（纯函数，页面接线由真机截图取证） */
class SettingsQuickAccessSubtitleTest {

    @Test
    fun accountsSubtitle_saysHowManyAccounts() {
        assertEquals("2 个账户", accountsCountSubtitle(2))
        assertEquals("0 个账户", accountsCountSubtitle(0))
    }

    @Test
    fun categoriesSubtitle_saysHowManyCategories() {
        assertEquals("18 个分类", categoriesCountSubtitle(18))
    }

    @Test
    fun budgetSubtitle_tellsWhetherThisMonthHasATotalBudget() {
        assertEquals("本月已设总预算", budgetMonthSubtitle(true))
        assertEquals("本月未设总预算", budgetMonthSubtitle(false))
    }
}