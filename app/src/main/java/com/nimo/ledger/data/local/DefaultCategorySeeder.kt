package com.nimo.ledger.data.local

import com.nimo.ledger.data.local.dao.CategoryDao

/**
 * 首次启动播种预置分类（计划附.4）。
 *
 * 抽成独立函数是为了能单测：AppContainer 只负责把「已播种标记 + 分类表」这两个真实依赖接上，
 * 判断逻辑（只播一次、之后删光也不复活）在这里，测试可以注入临时 DataStore + 内存库。
 */
object DefaultCategorySeeder {

    suspend fun seedIfNeeded(seedStore: DefaultCategorySeedStore, dao: CategoryDao) {
        // 已经播过种就不再自动播种：用户把分类删光了，那就是真删光（附.4）
        if (seedStore.hasSeeded()) return
        if (dao.count() == 0) {
            DefaultCategories.all().forEachIndexed { index, entity ->
                dao.upsert(entity.copy(sortOrder = index))
            }
        }
        // 库非空（例如刚恢复备份）也打标记：那种情况下"默认分类"该由备份说了算
        seedStore.markSeeded()
    }
}
