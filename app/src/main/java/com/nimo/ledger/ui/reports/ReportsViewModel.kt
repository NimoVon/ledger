package com.nimo.ledger.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.ledger.data.local.entity.AccountEntity
import com.nimo.ledger.data.local.entity.BudgetEntity
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.local.toInfo
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.CategoryKind
import com.nimo.ledger.domain.stats.AccountBreakdownRow
import com.nimo.ledger.domain.stats.BudgetCalculator
import com.nimo.ledger.domain.stats.BudgetInput
import com.nimo.ledger.domain.stats.BudgetStatus
import com.nimo.ledger.domain.stats.CategoryBreakdownRow
import com.nimo.ledger.domain.stats.DailyTotal
import com.nimo.ledger.domain.stats.MonthlyTotal
import com.nimo.ledger.domain.stats.PeriodSummary
import com.nimo.ledger.domain.stats.StatsCalculator
import com.nimo.ledger.domain.stats.TransactionVisibility
import com.nimo.ledger.ui.transactions.TransactionRow
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
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
    /** 趋势区（R2 三段：本月｜半年｜全年）的跨度：用户点的那一段，点了立刻反映在开关上 */
    val trendSpan: TrendSpan = TrendSpan.MONTH,
    /** 趋势折线的按月合计（升序，最后一个是 [month]）；还没读到数据时为空 */
    val trend: List<MonthlyTotal> = emptyList(),
    /** 趋势数据还没到位（切月/切跨度瞬间）：界面给加载态，绝不拿上一段的数据画图 */
    val isTrendLoading: Boolean = true,
    /**
     * 本月是否有任何流水（含转账/余额调整）。
     * 决定"空月"还是"有记录但本月没花钱"——后者不该显示整页空态。
     */
    val hasRecords: Boolean = false,
    /** R2「本月」视图的逐日合计：只含已过去的天（含今天），未来日期不在里面 */
    val daily: List<DailyTotal> = emptyList(),
    /** 本月已经过去的天数（= [daily] 的天数；未来的月份为 0） */
    val elapsedDays: Int = 0,
    /** 本月总预算（分）；没设总预算时为 null，界面据此决定是否显示「预算达成率」 */
    val totalBudgetCents: Long? = null,
    /** 预算达成率 = 本月支出 ÷ 总预算；没设总预算时为 null（界面不显示这一格） */
    val budgetAchievementRatio: Double? = null,
    /** 总预算的状态（计划 3.4：80% 预警 / 超支） */
    val budgetStatus: BudgetStatus = BudgetStatus.NONE,
    /** 点开的当日流水明细；null = 弹层关闭 */
    val dayDetail: DayDetailUiState? = null,
    /**
     * 趋势窗口（近 12 个月）里有没有任何收入/支出——与当前跨度无关。
     *
     * 不能用 [trend] 判断：它是当前跨度的切片，「本月」跨度下只有本月一个点，
     * 空月份的切片恒为 0，趋势区就会整块不显示——正是 F10 要避免的（切到没记账的
     * 月份，刚做好的趋势折线"不见了"）。
     */
    val trendHasData: Boolean = false,
) {
    val monthLabel: String get() = TransactionTimeline.monthLabel(month)

    /** 支出环比：(本月 − 上月) ÷ 上月；上月为 0 时为 null，界面显示 "—"（计划 3.4） */
    val expenseChangeRatio: Double?
        get() = StatsCalculator.monthOverMonth(summary.expenseCents, previousSummary.expenseCents)

    /** 收入环比，规则同上 */
    val incomeChangeRatio: Double?
        get() = StatsCalculator.monthOverMonth(summary.incomeCents, previousSummary.incomeCents)

    val isEmpty: Boolean get() = !isLoading && errorMessage == null && !hasRecords

    /** 设了总预算才显示「预算达成率」（计划附.1 R2）：没设就是"没这个概念"，不是 0% */
    val hasTotalBudget: Boolean get() = totalBudgetCents != null
}

/**
 * 当日流水明细（R2：点某一天的柱子打开）。
 *
 * 行文（分类名/「转账」/「余额调整」/账户标签/金额符号）由 TransactionTimeline 统一给出，
 * 与明细页逐字一致；合计仍来自 StatsCalculator（TransactionDayGroup 内部就是它算的）。
 */
data class DayDetailUiState(
    val date: LocalDate,
    /** 弹层标题：今天 / 昨天 / 10月12日 周一（与明细页的日段头同一套文案） */
    val label: String = TransactionTimeline.dayLabel(date, LocalDate.now()),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /** DAO 的区间查询已按 ORDER BY occurredOn DESC, id DESC 排好，这里不再重排 */
    val rows: List<TransactionRow> = emptyList(),
    /** 当日合计（转账/余额调整永不进入） */
    val summary: PeriodSummary = PeriodSummary(incomeCents = 0, expenseCents = 0),
    /** 当日是否有任何流水（含转账/余额调整）：用来区分"空"与"只有转账" */
    val hasRecords: Boolean = false,
) {
    val isEmpty: Boolean get() = !isLoading && errorMessage == null && !hasRecords
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

    /** 趋势区的跨度（R2 三段：本月 / 半年 / 全年），默认「本月」 */
    private val trendSpan = MutableStateFlow(TrendSpan.MONTH)

    /**
     * 绑定「月份」的数据快照：同一份快照里的行必须属于同一个月。
     * [trend] 覆盖 [firstTrendMonth] 到 [month] 的整段（近 12 个月，含 [current]）：
     * 三种跨度共用它，切跨度只是换画法（本月=按天，半年/全年=按月切片），不需要重查。
     */
    private data class MonthSnapshot(
        val month: YearMonth,
        val current: List<TransactionEntity>,
        val previous: List<TransactionEntity>,
        val trend: List<TransactionEntity>,
        /** 当月预算：总预算 + 各分类预算（预算达成率用） */
        val budgets: List<BudgetEntity>,
    )

    /** null = 当前请求还没加载完成（用于区分"加载中"与"本月确实没有记录"） */
    private val snapshot = MutableStateFlow<MonthSnapshot?>(null)

    /** 报表要用的"参考数据"：分类（含已停用）与账户名（含已归档）。两样都到齐才出数 */
    private data class References(
        val categories: Map<Long, CategoryEntity>,
        /** 账户实体（含已归档）：详情弹层要用它拼「转出 → 转入」 */
        val accounts: Map<Long, AccountEntity>,
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
            // 只按月份重查：趋势一次性取"近 12 个月"，切跨度只是在这段数据上换一种画法
            // （本月=按天，半年/全年=按月切片），因此不存在"开关已切到全年、线还是半年"的错位。
            month.flatMapLatest { ym ->
                val (from, to) = Dates.monthBounds(ym)
                val (prevFrom, prevTo) = Dates.monthBounds(ym.minusMonths(1))
                val (trendFrom, _) = Dates.monthBounds(firstTrendMonth(ym, TrendSpan.FULL_YEAR))
                combine(
                    repository.observeTransactions(from, to),
                    repository.observeTransactions(prevFrom, prevTo),
                    repository.observeTransactions(trendFrom, to),
                    // 预算只读当月：它读失败只丢预算这一份，不该把整页拖成错误态
                    repository.observeBudgets(ym.toString()).catch {
                        loadError.value = "读取预算失败，请稍后重试"
                        emit(emptyList())
                    },
                ) { current, previous, trend, budgets ->
                    MonthSnapshot(ym, current, previous, trend, budgets)
                }
                    // 读取失败也要 emit 一次：否则这一页永远停在加载态（流水页踩过同一个坑）。
                    // catch 放在内层，失败之后月份切换照旧能用。
                    .catch {
                        loadError.value = "读取流水失败，请稍后重试"
                        emit(MonthSnapshot(ym, emptyList(), emptyList(), emptyList(), emptyList()))
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
                    accounts = accounts.associateBy { it.id },
                    accountNames = accounts.associate { it.id to it.name },
                )
            }.collect { references.value = it }
        }
    }

    /** 用户在趋势区点开的那一天（R2）：null = 弹层关闭 */
    private val selectedDay = MutableStateFlow<LocalDate?>(null)

    /**
     * 当日流水明细（R2）：区间用 [day, day+1)（禁止 substr 截取），
     * 排序由 DAO 的 ORDER BY occurredOn DESC, id DESC 保证；
     * 行文与当日合计都走 TransactionTimeline / StatsCalculator，弹层不自己算。
     */
    private val dayDetail: Flow<DayDetailUiState?> =
        combine(selectedDay, references) { day, refs -> day to refs }
            .flatMapLatest { (day, refs) ->
                when {
                    day == null -> flowOf(null)
                    // 参考数据还没到：先给加载态，别急着把分类名显示成"未分类"
                    refs == null -> flowOf(DayDetailUiState(date = day))
                    else -> repository.observeTransactions(day.toString(), day.plusDays(1).toString())
                        .combine(pendingDeletionIds) { rows, pending -> rows to pending }
                        .map { (rows, pending) ->
                            val visible = TransactionVisibility.visible(rows, pending)
                            // 一天的流水恰好一组；合计由它内部的 StatsCalculator 给出
                            val group = TransactionTimeline
                                .group(visible, refs.categories, refs.accounts, today())
                                .firstOrNull()
                            DayDetailUiState(
                                date = day,
                                isLoading = false,
                                rows = group?.rows.orEmpty(),
                                summary = PeriodSummary(
                                    incomeCents = group?.incomeCents ?: 0L,
                                    expenseCents = group?.expenseCents ?: 0L,
                                ),
                                hasRecords = visible.isNotEmpty(),
                            )
                        }
                        .catch {
                            emit(
                                DayDetailUiState(
                                    date = day,
                                    isLoading = false,
                                    errorMessage = "读取当日流水失败，请稍后重试",
                                ),
                            )
                        }
                        .onStart { emit(DayDetailUiState(date = day)) }
                }
            }

    val uiState: StateFlow<ReportsUiState> = combine(
        combine(month, trendSpan) { ym, span -> ym to span },
        combine(snapshot, references, loadError, pendingDeletionIds) { snapshot, references, loadError, pendingDeletionIds ->
            LoadedData(snapshot, references, loadError, pendingDeletionIds)
        },
        dayDetail,
    ) { (requestedMonth, requestedSpan), data, detail ->
        buildState(requestedMonth, requestedSpan, data, detail)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    private fun buildState(
        month: YearMonth,
        span: TrendSpan,
        data: LoadedData,
        dayDetail: DayDetailUiState?,
    ): ReportsUiState {
        // 快照必须属于当前请求：切月瞬间不能把上一个月的数据挂在标题下
        val current = data.snapshot?.takeIf { it.month == month }
        if (current == null || data.references == null) {
            return ReportsUiState(
                isLoading = true,
                isTrendLoading = true,
                errorMessage = data.loadError,
                month = month,
                trendSpan = span,
                dayDetail = dayDetail,
            )
        }
        // 待删除的几笔在倒计时内先从报表消失；数据库里它们其实还在（计划 9.2）
        val visibleCurrent = TransactionVisibility.visible(current.current, data.pendingDeletionIds)
        val visiblePrevious = TransactionVisibility.visible(current.previous, data.pendingDeletionIds)
        // 非法 occurredOn（绕过校验器的直插，例如备份导入）由 toRecords 丢弃：不能让一行脏数据
        // 把 stateIn 拖死，也不能计入任何合计。区间已由 observeTransactions 限定，这里不再重复判断。
        val records = TransactionTimeline.toRecords(visibleCurrent)
        val summary = StatsCalculator.summarize(records)
        // 趋势数据覆盖近 12 个月：先按整段算一次，三种跨度都是它的切片（「本月」只有当前月一个点）
        // 「有没有数据」也按整段判断：否则空月份的「本月」切片恒为 0，趋势区整块不显示
        val trendFull = StatsCalculator.monthlyTotals(
            records = TransactionTimeline.toRecords(
                TransactionVisibility.visible(current.trend, data.pendingDeletionIds),
            ),
            months = trendMonths(month, TrendSpan.FULL_YEAR),
        )
        val categories = data.references.categories.mapValues { it.value.toInfo() }
        // 预算达成率的口径复用 BudgetCalculator（唯一实现），总预算没设时 totalBudgetCents 为 null
        val budget = BudgetCalculator.summarize(
            records = records,
            categories = categories,
            budgets = current.budgets.map { BudgetInput(it.id, it.categoryId, it.amount) },
        )
        val elapsed = StatsCalculator.elapsedDays(month, today())
        return ReportsUiState(
            isLoading = false,
            isTrendLoading = false,
            errorMessage = data.loadError,
            month = month,
            summary = summary,
            previousSummary = StatsCalculator.summarize(TransactionTimeline.toRecords(visiblePrevious)),
            breakdown = StatsCalculator.categoryBreakdown(
                records = records,
                categories = categories,
                kind = CategoryKind.EXPENSE,
            ),
            accountBreakdown = StatsCalculator.accountBreakdown(records, data.references.accountNames),
            dailyAverageExpenseCents = StatsCalculator.dailyAverage(
                expenseCents = summary.expenseCents,
                days = elapsed,
            ),
            trendSpan = span,
            // 切片口径与 trendMonths(month, span) 等价：整段的后 span.months 个月
            trend = trendFull.takeLast(span.months),
            trendHasData = trendFull.any { it.incomeCents > 0L || it.expenseCents > 0L },
            hasRecords = visibleCurrent.isNotEmpty(),
            daily = StatsCalculator.dailyTotals(records = records, month = month, today = today()),
            elapsedDays = elapsed,
            totalBudgetCents = budget.totalBudgetCents,
            budgetAchievementRatio = budget.totalBudgetCents?.let {
                BudgetCalculator.percent(budget.totalSpentCents, it)
            },
            budgetStatus = budget.totalStatus,
            dayDetail = dayDetail,
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
     * 切换趋势跨度（本月 / 半年 / 全年）。三段共用同一份「近 12 个月」数据，
     * 切换只是换一种画法：立刻生效，不需要重查，也不会出现画错区间的中间态。
     */
    fun setTrendSpan(span: TrendSpan) {
        if (trendSpan.value == span) return
        // 上一次的读取错误不该带进新的跨度
        loadError.value = null
        trendSpan.value = span
    }

    /**
     * R1 日期直改：直接跳到指定年月（年月选择器两步选出来的结果）。
     *
     * 与上下月只差"跳多远"：同一个 [month] 流、同一套 [Dates.monthBounds] 区间查询，
     * 本月合计、上月环比、趋势窗口因此都跟着这个值一起走，不需要为直选另写一份。
     */
    fun setMonth(month: YearMonth) {
        // 上一个月份的读取错误不该带进新月份
        loadError.value = null
        this.month.value = month
    }

    private fun moveMonth(delta: Long) = setMonth(month.value.plusMonths(delta))

    /**
     * R2：点趋势区某一天的柱子 —— 打开当日流水明细弹层。
     * 同一天重复点是幂等的（值没变不重新订阅），关掉之后还能再点开。
     */
    fun setSelectedDay(date: LocalDate) {
        selectedDay.value = date
    }

    /** 关闭当日流水明细弹层 */
    fun clearSelectedDay() {
        selectedDay.value = null
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
