package com.nimo.bookkeeping.ui

import com.nimo.bookkeeping.domain.filter.TransactionFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 跨 Tab 下钻请求（F07）：统计页点一行 → 明细页套条件。
 * 纯 JVM 测试：这里只验"请求会送到、用掉即清"，不涉及 Room 与 Compose。
 */
class DrillDownRequestsTest {

    @Test
    fun pending_isEmptyUntilSomebodyAsksToDrillDown() {
        assertNull(DrillDownRequests().pending.value)
    }

    @Test
    fun request_handsTheFilterToWhoeverIsListening() {
        val requests = DrillDownRequests()
        val filter = TransactionFilter(categoryIds = setOf(6L))

        requests.request(filter)

        assertEquals(filter, requests.pending.value)
    }

    @Test
    fun consume_clearsTheRequestThatWasJustApplied() {
        val requests = DrillDownRequests()
        val filter = TransactionFilter(accountIds = setOf(2L))
        requests.request(filter)

        requests.consume(filter)

        assertNull(requests.pending.value)
    }

    @Test
    fun consume_leavesANewerRequestAlone() {
        val requests = DrillDownRequests()
        val first = TransactionFilter(categoryIds = setOf(6L))
        val second = TransactionFilter(accountIds = setOf(2L))
        requests.request(first)
        // 用户手快：上一个还没应用完就又点了一行
        requests.request(second)

        requests.consume(first)

        assertEquals(second, requests.pending.value)
    }
}
