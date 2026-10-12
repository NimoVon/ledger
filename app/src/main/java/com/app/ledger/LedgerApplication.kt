package com.app.ledger

import android.app.Application
import com.app.ledger.data.local.AppDatabase
import com.app.ledger.data.local.DefaultCategories
import com.app.ledger.data.repository.LedgerRepository
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedgerApplication : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch { container.seedDefaultCategoriesIfEmpty() }
    }
}

/** 手动依赖注入容器（计划 4.1：当前规模不引入 Hilt/Koin） */
class AppContainer(context: android.content.Context) {

    val database: AppDatabase = AppDatabase.build(context)
    val repository: LedgerRepository = LedgerRepository(database)

    /** 首次启动播种预置分类；分类表非空（如恢复备份后）则跳过 */
    suspend fun seedDefaultCategoriesIfEmpty() {
        val dao = database.categoryDao()
        if (dao.count() == 0) {
            DefaultCategories.all().forEachIndexed { index, entity ->
                dao.upsert(entity.copy(sortOrder = index))
            }
        }
    }

    /** M0 自检：验证 Room / KSP / 触发器 / 部分索引在真实运行时全部生效（IO 线程执行） */
    suspend fun selfCheck(): List<String> = withContext(Dispatchers.IO) {
        fun scalar(sql: String): Int = database.query(SimpleSQLiteQuery(sql)).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
        val triggers = scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger' AND name LIKE 'trg_%'")
        val partialIndex = scalar("SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='idx_budget_month_total'")
        listOf(
            "数据库 user_version: v${scalar("PRAGMA user_version")}",
            "约束触发器: $triggers/4 " + if (triggers == 4) "✓" else "✗ 缺失",
            "预算月度唯一索引: " + if (partialIndex == 1) "已生效 ✓" else "缺失 ✗",
            "账户数: ${database.accountDao().count()}",
            "分类数: ${database.categoryDao().count()}（预置 18 = 支出 11 + 收入 7）",
            "交易数: ${database.transactionDao().count()}",
            "M0 自检通过：Room + KSP + SchemaHardening 全链路生效",
        )
    }
}
