package com.nimo.bookkeeping.ui

import com.nimo.bookkeeping.domain.filter.TransactionFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跨 Tab 的「带着条件跳过去」请求（F07 下钻）：统计页点某一行 → 明细页按该条件筛选。
 *
 * 这是一次性脉冲，不是"当前筛选状态"（筛选状态的唯一归属仍是 TransactionsViewModel）：
 * 明细页拿到就 [consume] 掉。否则用户手动「清空」筛选后再切回明细页，会被一个早就过期的
 * 请求重新套上条件——数字对不上，用户会以为清空没生效。
 *
 * 只活在进程内，不落库、不过进程边界（进程重启后没有"待下钻"这回事，也不需要）。
 */
class DrillDownRequests {

    private val current = MutableStateFlow<TransactionFilter?>(null)

    /** 待消费的条件；null = 当前没有下钻请求 */
    val pending: StateFlow<TransactionFilter?> = current.asStateFlow()

    fun request(filter: TransactionFilter) {
        current.value = filter
    }

    /**
     * 明细页应用完 [filter] 之后调用。
     * 用 compareAndSet 而不是直接置 null：应用期间若有更新的请求进来，不能把它一起清掉。
     */
    fun consume(filter: TransactionFilter) {
        current.compareAndSet(filter, null)
    }
}
