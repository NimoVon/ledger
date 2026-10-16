package com.nimo.ledger.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.local.toInfo
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.stats.AccountBreakdownRow
import com.nimo.ledger.domain.stats.CategoryBreakdownRow
import com.nimo.ledger.domain.stats.MonthlyTotal
import com.nimo.ledger.domain.stats.PeriodSummary
import com.nimo.ledger.domain.stats.StatsCalculator
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 月报状态（F07，计划 4.4：至少覆盖加载、内容、空状态和可理解的错误）。
 *
 * 页面不写任何统计口径：收入/支出/结余来自 StatsCalculator.summarize，
 * 分类排行来自 StatsCalculator.categoryBreakdown，环比来自 StatsCalculator.monthOverMonth，
 * 趋势折线来自 StatsCalculator.monthlyTotals。
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
    /** 趋势折线（F10）的跨度：用户点的那一段，点了立刻反映在开关上 */
    val trendSpan: TrendSpan = TrendSpan.HALF_YEAR,
    /** 趋势折线的按月合计（升序，最后一个是 [month]）；还没读到数据时为空 */
    val trend: List<MonthlyTotal> = emptyList(),
    /** 趋势数据还没到位（切月/切跨度瞬间）：界面给加载态，绝不拿上一段的数据画图 */
    val isTrendLoading: Boolean = true,
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

    /**
     * 趋势窗口内是否有任何收入/支出。
     * 本月没有记录时用它决定要不要把趋势图放出来——否则用户切到一个没记账的月份，
     * 刚做好的趋势折线就整块"不见了"（F10）。
     */
    val trendHasData: Boolean
        get() = trend.any { it.incomeCents > 0L || it.expenseCents > 0L }
}

/**
 * 月报（F07 + F10 趋势折线）。
 *
 * UI 不直接访问 DAO，读取全部经 LedgerRepository（计划 4.3）；
 * 月度查询走 Dates.monthBounds 得到的 [from, to) 区间（禁止 substr 截取）。
 * 上个月也一起读：环比需要它的合计，但只读不算，口径仍归 StatsCalculator。
 * 趋势窗口是 [month−(跨度−1), month]，同样按月边界取区间，归档交给 StatsCalculator.monthlyTotals。
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

    /** 趋势折线的跨度（F10）：半年 / 全年 */
    private val trendSpan = MutableStateFlow(TrendSpan.HALF_YEAR)

    /**
     * 绑定「月份 + 跨度」的数据快照：同一份快照里的行必须属于同一个月、同一段趋势。
     * [trend] 覆盖 [firstTrendMonth] 到 [month] 的整段（包含 [current]）。
     */
    private data class MonthSnapshot(
        val month: YearMonth,
        val span: TrendSpan,
        val current: List<TransactionEntity>,
        val previous: List<TransactionEntity>,
        val trend: List<TransactionEntity>,
    )

    /** null = 当前请求还没加载完成（用于区分"加载中"与"本月确实没有记录"） */
    private val snapshot = MutableStateFlow<MonthSnapshot?>(null)

    /** 报表要用的"参考数据"：分类（含已停用）与账户名（含已归档）。两样都到齐才出数 */
    private data class References(
        val categories: Map<Long, CategoryEntity>,
        val accountNames: Map<Long, String>,
    )

    private val references = MutableStateFlow<References?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    /** 数据态的打包：请求态（月/跨度）与它分开组合，这样开关不必等查询回来才动 */
    private data class LoadedData(
        val snapshot: MonthSnapshot?,
        val references: References?,
        val loadError: String?,
        val pendingDeletionIds: Set<Long>,
    )

    init {
        viewModelScope.launch {
            // 月份和跨度合成一个"请求键"：趋势的行必须和它所属的跨度一起到达，
            // 否则会出现"开关已经切到全年、线还是半年"的错位。
            combine(month, trendSpan) { ym, span -> ym to span }
                .flatMapLatest { (ym, span) ->
                    val (from, to) = Dates.monthBounds(ym)
                    val (prevFrom, prevTo) = Dates.monthBounds(ym.minusMonths(1))
                    val (trendFrom, _) = Dates.monthBounds(firstTrendMonth(ym, span))
                    combine(
                        repository.observeTransactions(from, to),
                        repository.observeTransactions(prevFrom, prevTo),
                        repository.observeTransactions(trendFrom, to),
                    ) { current, previous, trend -> MonthSnapshot(ym, span, current, previous, trend) }
                        // 读取失败也要 emit 一次：否则这一页永远停在加载态（流水页踩过同一个坑）。
                        // catch 放在内层，失败之后月份/跨度切换照旧能用。
                        .catch {
                            loadError.value = "读取流水失败，请稍后重试"
                            emit(MonthSnapshot(ym, span, emptyList(), emptyList(), emptyList()))
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
        combine(month, trendSpan) { ym, span -> ym to span },
        combine(snapshot, references, loadError, pendingDeletionIds) { snapshot, references, loadError, pendingDeletionIds ->
            LoadedData(snapshot, references, loadError, pendingDeletionIds)
        },
    ) { (requestedMonth, requestedSpan), data ->
        buildState(requestedMonth, requestedSpan, data)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    private fun buildState(
        month: YearMonth,
        span: TrendSpan,
        data: LoadedData,
    ): ReportsUiState {
        // 快照必须属于当前请求：切月/切跨度瞬间不能把上一个月、上一段的数据挂在标题下
        val current = data.snapshot?.takeIf { it.month == month }
        if (current == null || data.references == null) {
            return ReportsUiState(
                isLoading = true,
                isTrendLoading = true,
                errorMessage = data.loadError,
                month = month,
                trendSpan = span,
            )
        }
        // 待删除的几笔在倒计时内先从报表消失；数据库里它们其实还在（计划 9.2）
        val visibleCurrent = TransactionVisibility.visible(current.current, data.pendingDeletionIds)
        val visiblePrevious = TransactionVisibility.visible(current.previous, data.pendingDeletionIds)
        // 非法 occurredOn（绕过校验器的直插，例如备份导入）由 toRecords 丢弃：不能让一行脏数据
        // 把 stateIn 拖死，也不能计入任何合计。区间已由 observeTransactions 限定，这里不再重复判断。
        val records = TransactionTimeline.toRecords(visibleCurrent)
        val summary = StatsCalculator.summarize(records)
        // 趋势是另一段区间：只有跨度也对得上才画（对不上就先给加载态，绝不画错区间）
        val trendReady = current.span == span
        val trend = if (trendReady) {
            StatsCalculator.monthlyTotals(
                records = TransactionTimeline.toRecords(
                    TransactionVisibility.visible(current.trend, data.pendingDeletionIds),
                ),
                months = trendMonths(month, span),
            )
        } else {
            emptyList()
        }
        return ReportsUiState(
            isLoading = false,
            isTrendLoading = !trendReady,
            errorMessage = data.loadError,
            month = month,
            summary = summary,
            previousSummary = StatsCalculator.summarize(TransactionTimeline.toRecords(visiblePrevious)),
            breakdown = StatsCalculator.categoryBreakdown(
                records = records,
                categories = data.references.categories.mapValues { it.value.toInfo() },
                kind = CategoryKind.EXPENSE,
            ),
            accountBreakdown = StatsCalculator.accountBreakdown(records, data.references.accountNames),
            dailyAverageExpenseCents = StatsCalculator.dailyAverage(
                expenseCents = summary.expenseCents,
                days = StatsCalculator.elapsedDays(month, today()),
            ),
            trendSpan = span,
            trend = trend,
            hasRecords = visibleCurrent.isNotEmpty(),
        )
    }

    /** 趋势窗口的第一个月：半年 = 往前 5 个月，全年 = 往前 11 个月 */
    private fun firstTrendMonth(month: YearMonth, span: TrendSpan): YearMonth =
        month.minusMonths((span.months - 1).toLong())

    /** 折线的横轴月份：升序，最后一个是当前查看的月份 */
    private fun trendMonths(month: YearMonth, span: TrendSpan): List<YearMonth> =
        (span.months - 1 downTo 0).map { month.minusMonths(it.toLong()) }

    fun showPreviousMonth() = moveMonth(-1)

    fun showNextMonth() = moveMonth(1)

    /**
     * 切换趋势跨度（半年 / 全年）。点了开关立刻动，数据由查询决定什么时候到；
     * 期间 [ReportsUiState.isTrendLoading] 为 true，界面显示占位而不是画错区间的线。
     */
    fun setTrendSpan(span: TrendSpan) {
        if (trendSpan.value == span) return
        // 上一次的读取错误不该带进新的跨度
        loadError.value = null
        trendSpan.value = span
    }

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
