package com.nimo.bookkeeping.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.local.toInfo
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.CategoryKind
import com.nimo.bookkeeping.domain.stats.AccountBreakdownRow
import com.nimo.bookkeeping.domain.stats.CategoryBreakdownRow
import com.nimo.bookkeeping.domain.stats.PeriodSummary
import com.nimo.bookkeeping.domain.stats.StatsCalculator
import com.nimo.bookkeeping.domain.stats.TransactionVisibility
import com.nimo.bookkeeping.ui.transactions.TransactionTimeline
import com.nimo.bookkeeping.util.Dates
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 月报状态（F07，计划 4.4：至少覆盖加载、内容、空状态和可理解的错误）。
 *
 * 页面不写任何统计口径：收入/支出/结余来自 StatsCalculator.summarize，
 * 分类排行来自 StatsCalculator.categoryBreakdown，环比来自 StatsCalculator.monthOverMonth。
 */
data class ReportsUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val month: YearMonth = YearMonth.now(),
    /** 本月收支合计 */
    val summary: PeriodSummary = PeriodSummary(incomeCents = 0, expenseCents = 0),
    /** 上月收支合计：只用于算环比，不直接展示 */
    val previousSummary: PeriodSummary = PeriodSummary(incomeCents = 0, expenseCents = 0),
    /** 本月支出分类排行（一级分类粒度，子分类金额已并入父分类） */
    val breakdown: List<CategoryBreakdownRow> = emptyList(),
    /** 本月支出账户分布 */
    val accountBreakdown: List<AccountBreakdownRow> = emptyList(),
    /** 本月日均支出（分母 = 已过去的天数，见 StatsCalculator.elapsedDays） */
    val dailyAverageExpenseCents: Long = 0L,
    /**
     * 本月是否有任何流水（含转账/余额调整）。
     * 决定"空月"还是"有记录但本月没花钱"——后者不该显示整页空态。
     */
    val hasRecords: Boolean = false,
) {
    val monthLabel: String get() = TransactionTimeline.monthLabel(month)

    /** 支出环比：(本月 − 上月) ÷ 上月；上月为 0 时为 null，界面显示 "—"（计划 3.4） */
    val expenseChangeRatio: Double?
        get() = StatsCalculator.monthOverMonth(summary.expenseCents, previousSummary.expenseCents)

    /** 收入环比，规则同上 */
    val incomeChangeRatio: Double?
        get() = StatsCalculator.monthOverMonth(summary.incomeCents, previousSummary.incomeCents)

    val isEmpty: Boolean get() = !isLoading && errorMessage == null && !hasRecords
}

/**
 * 月报（F07，阶段 3 · 报表的第一个切片）。
 *
 * UI 不直接访问 DAO，读取全部经 LedgerRepository（计划 4.3）；
 * 月度查询走 Dates.monthBounds 得到的 [from, to) 区间（禁止 substr 截取）。
 * 上个月也一起读：环比需要它的合计，但只读不算，口径仍归 StatsCalculator。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(
    private val repository: LedgerRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
    /**
     * 撤销窗口内那几笔（延迟物理删除，计划 9.2）的 id：数据库里它们还在，
     * 但月报必须当它们不存在，否则统计页会显示一个用户已经删掉的数字。
     * 过滤统一走 TransactionVisibility，与明细页/账户页同一口径。
     */
    private val pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {

    /** 当前查看的月份：切换只改这个值，查询区间与标签都由它派生 */
    private val month = MutableStateFlow(YearMonth.from(today()))

    /** 绑定月份的"数据快照"：本月与上月的行必须属于同一个月份 */
    private data class MonthSnapshot(
        val month: YearMonth,
        val current: List<TransactionEntity>,
        val previous: List<TransactionEntity>,
    )

    /** null = 当前月份还没加载完成（用于区分"加载中"与"本月确实没有记录"） */
    private val snapshot = MutableStateFlow<MonthSnapshot?>(null)

    /** 报表要用的"参考数据"：分类（含已停用）与账户名（含已归档）。两样都到齐才出数 */
    private data class References(
        val categories: Map<Long, CategoryEntity>,
        val accountNames: Map<Long, String>,
    )

    private val references = MutableStateFlow<References?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            month.flatMapLatest { ym ->
                val (from, to) = Dates.monthBounds(ym)
                val (prevFrom, prevTo) = Dates.monthBounds(ym.minusMonths(1))
                combine(
                    repository.observeTransactions(from, to),
                    repository.observeTransactions(prevFrom, prevTo),
                ) { current, previous -> MonthSnapshot(ym, current, previous) }
                    // 读取失败也要 emit 一次：否则这一页永远停在加载态（流水页踩过同一个坑）。
                    // catch 放在内层，失败之后月份切换照旧能用。
                    .catch {
                        loadError.value = "读取流水失败，请稍后重试"
                        emit(MonthSnapshot(ym, emptyList(), emptyList()))
                    }
            }.collect { snapshot.value = it }
        }
        viewModelScope.launch {
            // 分类含已停用、账户含已归档：历史流水的名字不能丢（各自失败只丢自己那一份）
            val categoryFlow = repository.observeAllCategories()
                .catch {
                    loadError.value = "读取分类失败，请稍后重试"
                    emit(emptyList())
                }
            val accountFlow = repository.observeAccounts()
                .catch {
                    loadError.value = "读取账户失败，请稍后重试"
                    emit(emptyList())
                }
            combine(categoryFlow, accountFlow) { categories, accounts ->
                References(
                    categories = categories.associateBy { it.id },
                    accountNames = accounts.associate { it.id to it.name },
                )
            }.collect { references.value = it }
        }
    }

    val uiState: StateFlow<ReportsUiState> = combine(
        month,
        snapshot,
        references,
        loadError,
        pendingDeletionIds,
    ) { month, snapshot, references, loadError, pendingDeletionIds ->
        // 快照必须属于当前月份：切月瞬间不能把上个月的数字挂在标题下
        val current = snapshot?.takeIf { it.month == month }
        val ready = current != null && references != null
        if (!ready) {
            ReportsUiState(isLoading = true, errorMessage = loadError, month = month)
        } else {
            // 待删除的几笔在倒计时内先从报表消失；数据库里它们其实还在（计划 9.2）
            val visibleCurrent = TransactionVisibility.visible(current.current, pendingDeletionIds)
            val visiblePrevious = TransactionVisibility.visible(current.previous, pendingDeletionIds)
            // 非法 occurredOn（绕过校验器的直插，例如备份导入）由 toRecords 丢弃：不能让一行脏数据
            // 把 stateIn 拖死，也不能计入任何合计。区间已由 observeTransactions 限定，这里不再重复判断。
            val records = TransactionTimeline.toRecords(visibleCurrent)
            val summary = StatsCalculator.summarize(records)
            ReportsUiState(
                isLoading = false,
                errorMessage = loadError,
                month = month,
                summary = summary,
                previousSummary = StatsCalculator.summarize(TransactionTimeline.toRecords(visiblePrevious)),
                breakdown = StatsCalculator.categoryBreakdown(
                    records = records,
                    categories = references.categories.mapValues { it.value.toInfo() },
                    kind = CategoryKind.EXPENSE,
                ),
                accountBreakdown = StatsCalculator.accountBreakdown(records, references.accountNames),
                dailyAverageExpenseCents = StatsCalculator.dailyAverage(
                    expenseCents = summary.expenseCents,
                    days = StatsCalculator.elapsedDays(month, today()),
                ),
                hasRecords = visibleCurrent.isNotEmpty(),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    fun showPreviousMonth() = moveMonth(-1)

    fun showNextMonth() = moveMonth(1)

    private fun moveMonth(delta: Long) {
        // 上个月的读取错误不该带进新月份
        loadError.value = null
        month.update { it.plusMonths(delta) }
    }

    companion object {
        fun factory(
            repository: LedgerRepository,
            pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportsViewModel(repository, pendingDeletionIds = pendingDeletionIds) }
        }
    }
}
