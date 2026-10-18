package com.nimo.ledger.ui.budgets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.ledger.data.local.entity.BudgetEntity
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.toInfo
import com.nimo.ledger.data.local.toRecordOrNull
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.model.TransactionRecord
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.stats.BudgetCalculator
import com.nimo.ledger.domain.stats.BudgetInput
import com.nimo.ledger.domain.stats.BudgetStatus
import com.nimo.ledger.domain.stats.TransactionVisibility
import com.nimo.ledger.ui.transactions.TransactionTimeline
import com.nimo.ledger.util.Dates
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 预算页的一行：总预算行之外的每个支出分类一行 */
data class BudgetCategoryRow(
    /** 支出分类 id；预算指向已不存在的分类时用那个 id */
    val categoryId: Long,
    val budgetId: Long?,
    val name: String,
    /** 0 = 一级、1 = 二级（列表缩进用，和分类管理页一致） */
    val depth: Int,
    val isArchived: Boolean,
    /** 非支出分类上的预算（分类被改过类型之类），UI 需要加个标记 */
    val isNonExpense: Boolean,
    val budgetCents: Long?,
    val spentCents: Long,
    val status: BudgetStatus,
)

data class BudgetsUiState(
    val isLoading: Boolean = true,
    /** 当前查看的年月：月份标签可点直改（R1），年月弹窗要拿它定位年份页与高亮 */
    val month: YearMonth = YearMonth.now(),
    val monthLabel: String = "",
    val totalBudgetCents: Long? = null,
    val totalSpentCents: Long = 0L,
    val totalStatus: BudgetStatus = BudgetStatus.NONE,
    val totalBudgetId: Long? = null,
    val rows: List<BudgetCategoryRow> = emptyList(),
    /** 已超支的分类数（不含总预算） */
    val overCount: Int = 0,
    /** 接近上限的分类数（不含总预算） */
    val warningCount: Int = 0,
    val errorMessage: String? = null,
) {
    /** 一条支出分类都没有：没什么可设预算的 */
    val isEmpty: Boolean
        get() = !isLoading && errorMessage == null && totalBudgetCents == null && rows.isEmpty()
}

/** 当月快照：该月预算行 + 已剔掉撤销窗口的流水（occurredOn 非法的行在转换时丢弃） */
private data class BudgetSnapshot(
    val month: YearMonth,
    val budgets: List<BudgetEntity>,
    val records: List<TransactionRecord>,
)

/**
 * 预算页 ViewModel（F09）。
 *
 * 和统计页同一套骨架：月份是 MutableStateFlow，月份变化时 flatMapLatest 重新订阅该月的预算 + 流水，
 * catch 放在**内层**，否则 IO 一失败页面永远停在加载态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BudgetsViewModel(
    private val repository: LedgerRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.from(today()))
    private val snapshot = MutableStateFlow<BudgetSnapshot?>(null)
    /** null = 还没读到分类（这段时间保持加载态，避免先闪一屏空列表）；空列表 = 确实没有任何分类 */
    private val categories = MutableStateFlow<List<CategoryEntity>?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            month
                .flatMapLatest { ym ->
                    val (from, to) = Dates.monthBounds(ym)
                    combine(
                        repository.observeBudgets(ym.toString()),
                        repository.observeTransactions(from, to),
                        pendingDeletionIds,
                    ) { budgets, transactions, pending ->
                        BudgetSnapshot(
                            month = ym,
                            budgets = budgets,
                            records = TransactionVisibility.visible(transactions, pending)
                                .mapNotNull { it.toRecordOrNull() },
                        )
                    }.catch {
                        loadError.value = "读取预算失败，请稍后重试"
                        emit(BudgetSnapshot(ym, emptyList(), emptyList()))
                    }
                }
                .collect { snapshot.value = it }
        }
        viewModelScope.launch {
            repository.observeAllCategories()
                .catch { loadError.value = "读取分类失败，请稍后重试" }
                .collect { categories.value = it }
        }
    }

    val uiState: StateFlow<BudgetsUiState> = combine(
        month,
        snapshot,
        categories,
        loadError,
    ) { ym, data, cats, error ->
        buildState(ym, data, cats, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetsUiState())

    fun showPreviousMonth() = moveMonth(-1)

    fun showNextMonth() = moveMonth(1)

    /**
     * R1 日期直改：直接跳到指定年月（年月选择器两步选出来的结果）。
     *
     * 与上下月只差"跳多远"：同一个 [month] 流、同一套 [Dates.monthBounds] 区间查询，
     * 所以该月的预算行与已花金额都不需要为它另算一份。
     */
    fun setMonth(month: YearMonth) {
        // 上一个月份的读取错误不该带进新月份
        loadError.value = null
        this.month.value = month
    }

    private fun moveMonth(delta: Long) = setMonth(month.value.plusMonths(delta))

    /** 设置（或更新）当月预算；categoryId 为 null 表示总预算 */
    suspend fun setBudget(categoryId: Long?, amountCents: Long): Result<Unit> =
        repository.setBudget(month.value.toString(), categoryId, amountCents)

    suspend fun removeBudget(id: Long): Result<Unit> = repository.deleteBudget(id)

    private fun buildState(
        ym: YearMonth,
        data: BudgetSnapshot?,
        cats: List<CategoryEntity>?,
        error: String?,
    ): BudgetsUiState {
        val label = TransactionTimeline.monthLabel(ym)
        val current = data?.takeIf { it.month == ym }
        if (error != null) {
            return BudgetsUiState(
                isLoading = false,
                month = ym,
                monthLabel = label,
                errorMessage = error,
            )
        }
        // 分类还没到就继续算加载中：否则会先发一屏"一个分类都没有"的假状态
        if (current == null || cats == null) {
            return BudgetsUiState(isLoading = true, month = ym, monthLabel = label)
        }

        val infoById = cats.associate { it.id to it.toInfo() }
        val summary = BudgetCalculator.summarize(
            records = current.records,
            categories = infoById,
            budgets = current.budgets.map {
                BudgetInput(id = it.id, categoryId = it.categoryId, amountCents = it.amount)
            },
        )
        // 分类预算按 categoryId 建索引；总预算（categoryId == null）不进这个表
        val budgetByCategory: Map<Long, BudgetEntity> = current.budgets
            .mapNotNull { budget -> budget.categoryId?.let { id -> id to budget } }
            .toMap()
        val totalBudgetEntity = current.budgets.firstOrNull { it.categoryId == null }
        val rows = buildRows(cats, budgetByCategory, summary.spentByCategory)

        return BudgetsUiState(
            isLoading = false,
            month = ym,
            monthLabel = label,
            totalBudgetCents = summary.totalBudgetCents,
            totalSpentCents = summary.totalSpentCents,
            totalStatus = summary.totalStatus,
            totalBudgetId = totalBudgetEntity?.id,
            rows = rows,
            overCount = rows.count { it.status == BudgetStatus.OVER },
            warningCount = rows.count { it.status == BudgetStatus.WARNING },
        )
    }

    /**
     * 行的来源有两份数据：
     * - 支出分类树（全部支出分类，含停用），按 sortOrder,id 摊平、父级在前、子级缩进，和分类管理页同序；
     * - 该月的分类预算（含指向不存在的分类、或指向非支出分类的异常数据）。
     * 没设预算的停用分类不显示（列了也没用），设了预算的必须显示（否则钱凭空消失）。
     */
    private fun buildRows(
        cats: List<CategoryEntity>,
        budgetByCategory: Map<Long, BudgetEntity>,
        spentByCategory: Map<Long, Long>,
    ): List<BudgetCategoryRow> {
        val rows = mutableListOf<BudgetCategoryRow>()
        val knownIds = cats.mapTo(HashSet()) { it.id }
        val consuming = budgetByCategory.keys.toMutableSet()
        val expense = cats.filter { it.kind == CategoryKind.EXPENSE }
        val byOrder = compareBy<CategoryEntity>({ it.sortOrder }, { it.id })

        val tops = expense.filter { it.parentId == null }.sortedWith(byOrder)
        for (top in tops) {
            consuming.remove(top.id)
            if (shouldShow(top, budgetByCategory)) {
                rows += top.toRow(depth = 0, budgetByCategory, spentByCategory)
            }
            for (child in expense.filter { it.parentId == top.id }.sortedWith(byOrder)) {
                consuming.remove(child.id)
                if (shouldShow(child, budgetByCategory)) {
                    rows += child.toRow(depth = 1, budgetByCategory, spentByCategory)
                }
            }
        }
        // 孤儿（父分类被删/类型不对）也列出来，不能因为树结构异常就让预算看不见
        for (orphan in expense.filter { it.parentId != null && !knownIds.contains(it.parentId) }
            .sortedWith(byOrder)) {
            consuming.remove(orphan.id)
            if (shouldShow(orphan, budgetByCategory)) {
                rows += orphan.toRow(depth = 1, budgetByCategory, spentByCategory)
            }
        }
        // 兜底 1：预算指向的分类已经不存在
        for (missingId in consuming.filter { !knownIds.contains(it) }.sorted()) {
            val budget = budgetByCategory[missingId]
            val spent = spentByCategory[missingId] ?: 0L
            rows += BudgetCategoryRow(
                categoryId = missingId,
                budgetId = budget?.id,
                name = "分类已不存在",
                depth = 0,
                isArchived = false,
                isNonExpense = false,
                budgetCents = budget?.amount,
                spentCents = spent,
                status = budget?.let { BudgetCalculator.status(spent, it.amount) } ?: BudgetStatus.NONE,
            )
        }
        // 兜底 2：预算落在非支出分类上（分类被改过类型）：总额里不含它，标记出来别让人误以为没生效
        for (otherId in consuming.filter { knownIds.contains(it) }.sorted()) {
            val category = cats.first { it.id == otherId }
            val budget = budgetByCategory[otherId]
            rows += BudgetCategoryRow(
                categoryId = otherId,
                budgetId = budget?.id,
                name = category.name,
                depth = 0,
                isArchived = category.isArchived,
                isNonExpense = true,
                budgetCents = budget?.amount,
                spentCents = 0L,
                status = budget?.let { BudgetCalculator.status(0L, it.amount) } ?: BudgetStatus.NONE,
            )
        }
        return rows
    }

    /** 停用分类列出来没意义；但**设过预算的停用分类必须列**，否则那笔预算在页面上凭空消失 */
    private fun shouldShow(category: CategoryEntity, budgetByCategory: Map<Long, BudgetEntity>): Boolean =
        !category.isArchived || budgetByCategory.containsKey(category.id)

    private fun CategoryEntity.toRow(
        depth: Int,
        budgetByCategory: Map<Long, BudgetEntity>,
        spentByCategory: Map<Long, Long>,
    ): BudgetCategoryRow {
        val budget = budgetByCategory[id]
        val spent = spentByCategory[id] ?: 0L
        return BudgetCategoryRow(
            categoryId = id,
            budgetId = budget?.id,
            name = name,
            depth = depth,
            isArchived = isArchived,
            isNonExpense = false,
            budgetCents = budget?.amount,
            spentCents = spent,
            status = budget?.let { BudgetCalculator.status(spent, it.amount) } ?: BudgetStatus.NONE,
        )
    }

    companion object {
        fun factory(
            repository: LedgerRepository,
            pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { BudgetsViewModel(repository, pendingDeletionIds = pendingDeletionIds) }
        }
    }
}
