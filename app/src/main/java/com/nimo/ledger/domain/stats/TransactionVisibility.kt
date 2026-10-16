package com.nimo.ledger.domain.stats

import com.nimo.ledger.data.local.entity.TransactionEntity

/**
 * 「撤销窗口内的流水暂时不算数」的唯一收口（计划 9.2 延迟物理删除）。
 *
 * 点删除后到真正落库之间的这几秒里，数据库那一行还在；凡是"看得见流水"的计算
 * （流水列表、月度合计、账户余额、净资产）都必须先过这里，页面与 ViewModel 不许各写一份过滤。
 * 以后新增统计/预算/报表页同样调用它——漏掉一处就会出现"我明明删了，统计里还有"。
 *
 * 这里只做一件事：把待删除的 id 剔掉。查询边界、排序、统计口径仍分别由
 * [StatsCalculator] / [BalanceCalculator] / DAO 负责，本类不复制它们的规则。
 */
object TransactionVisibility {

    /**
     * @param all 某个查询范围（例如某个月、或全量流水）读出来的行
     * @param pendingDeletionIds 延迟物理删除里正被暂存的那几笔 id
     */
    fun visible(all: List<TransactionEntity>, pendingDeletionIds: Set<Long>): List<TransactionEntity> =
        if (pendingDeletionIds.isEmpty()) all else all.filterNot { it.id in pendingDeletionIds }
}
