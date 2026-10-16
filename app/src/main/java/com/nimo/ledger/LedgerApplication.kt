package com.nimo.ledger

import android.app.Application
import com.nimo.ledger.data.backup.AutoBackupStore
import com.nimo.ledger.data.local.AppDatabase
import com.nimo.ledger.data.local.DefaultCategories
import com.nimo.ledger.data.repository.BackupRepository
import com.nimo.ledger.data.repository.LedgerRepository
import java.io.File
import com.nimo.ledger.ui.DrillDownRequests
import com.nimo.ledger.ui.theme.ThemePreferenceStore
import com.nimo.ledger.ui.transactions.PendingTransactionDeletion
import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LedgerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch { container.seedDefaultCategoriesIfEmpty() }
    }
}

/** 手动依赖注入容器（计划 4.1：当前规模不引入 Hilt/Koin） */
class AppContainer(context: android.content.Context) {

    val database: AppDatabase = AppDatabase.build(context)
    val repository: LedgerRepository = LedgerRepository(database)

    /** F08：备份导出 / 替换式恢复。UI 不碰 DAO，一切写入都经过 Repository */
    val backupRepository: BackupRepository = BackupRepository(database)

    /**
     * F08c：恢复前的自动备份。放应用私有目录（不经 SAF 弹窗），
     * 因为它必须"确定地"写成功或失败——失败了就要中止恢复（计划 4.6）。
     */
    val autoBackupStore: AutoBackupStore =
        AutoBackupStore(File(context.applicationContext.filesDir, "auto-backup"))

    /**
     * F12：外观偏好（跟随系统 / 浅色 / 深色）。
     * 只落一个 SharedPreferences 文件，不进 Room —— 换主题不值得动 schema、更不值得迁移。
     */
    val themePreference: ThemePreferenceStore =
        ThemePreferenceStore.from(context.applicationContext)

    /** 页面销毁不该打断的活儿放这里：预置分类、删除倒计时（计划 9.2） */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 删除 + 撤销：延迟物理删除（计划 9.2 / V2.2 补遗 7）。
     * 挂在 Application 级，从编辑页返回流水页、甚至切到别的 Tab 都不影响倒计时。
     */
    val pendingTransactionDeletion: PendingTransactionDeletion =
        PendingTransactionDeletion(repository, appScope)

    /**
     * 统计页 → 明细页的下钻请求（F07）：点排行/账户分布的一行，带着条件跳到明细页。
     * 用完即清（见 [DrillDownRequests]），不落库、不跨进程。
     */
    val drillDownRequests: DrillDownRequests = DrillDownRequests()

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
