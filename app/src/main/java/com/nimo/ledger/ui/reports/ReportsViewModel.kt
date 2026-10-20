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
import com.nimo.ledger.domain.stats.ReportBucket
import com.nimo.ledger.domain.stats.ReportRange
import com.nimo.ledger.domain.stats.StatsCalculator
import com.nimo.ledger.domain.stats.TransactionVisibility
import com.nimo.ledger.ui.transactions.TransactionRow
import com.nimo.ledger.ui.transactions.TransactionTimeline
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
 * 统计报表状态（F07 + F10）。
 *
 * 与旧版的差别：时间口径从「一个月 + 趋势三段」变成**一个范围**（[ReportRange]：
 * 某个月 / 近 N 个月 / 自然年 / 自定义起止日期）。整页的合计、分类占比、账户分布、排行、
 * 趋势、日均全部按这一个范围聚合——不许出现"标题写着 10 月、趋势却画近 12 个月"的口径错位。
 *
 * 页面不写任何统计口径：收入/支出/结余来自 StatsCalculator.summarize，
 * 分类排行来自 categoryBreakdown，环比来自 monthOverMonth，
 * 按天/按月聚合来自 dailyTotals / monthlyTotals。
 */
data class ReportsUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    /** 整页的时间范围（唯一口径） */
    val range: ReportRange = ReportRange.Month(YearMonth.now()),
    /** 区间内收支合计 */
    val summary: PeriodSummary = PeriodSummary(incomeCents = 0, expenseCents = 0),
    /** 上一个等长区间的收支合计：只用于算环比，不直接展示 */
    val previousSummary: PeriodSummary = PeriodSummary(incomeCents = 0, expenseCents = 0),
    /** 区间内支出分类排行（一级分类粒度，子分类金额已并入父分类） */
    val breakdown: List<CategoryBreakdownRow> = emptyList(),
    /** 区间内支出账户分布 */
    val accountBreakdown: List<AccountBreakdownRow> = emptyList(),
    /** 区间日均支出（分母 = 已过去的天数，见 StatsCalculator.elapsedDays） */
    val dailyAverageExpenseCents: Long = 0L,
    /** 按月画法的横轴合计（升序、无记录的月份补 0）；按天画法时只有区间覆盖的那几个月 */
    val trend: List<MonthlyTotal> = emptyList(),
    /** 趋势数据还没到位（切范围瞬间）：界面给加载态，绝不拿上一段的数据画图 */
    val isTrendLoading: Boolean = true,
    /**
     * 区间内是否有任何流水（含转账/余额调整）。
     * 决定"空区间"还是"有记录但这段没花钱"——后者不该显示整页空态。
     */
    val hasRecords: Boolean = false,
    /** 按天画法的逐日合计：只含已过去的天（含今天），未来日期不在里面 */
    val daily: List<DailyTotal> = emptyList(),
    /** 区间里已经过去的天数（= [daily] 的天数；未来区间为 0） */
    val elapsedDays: Int = 0,
    /** 区间的总预算（分）；只有"单月"范围才有，其余范围/没设时为 null */
    val totalBudgetCents: Long? = null,
    /** 预算达成率 = 区间支出 ÷ 总预算；只有单月范围且有总预算时才非 null */
    val budgetAchievementRatio: Double? = null,
    /** 总预算的状态（计划 3.4：80% 预警 / 超支） */
    val budgetStatus: BudgetStatus = BudgetStatus.NONE,
    /** 点开的当日流水明细；null = 弹层关闭 */
    val dayDetail: DayDetailUiState? = null,
    /**
     * 区间里有没有任何收入/支出（不含转账/余额调整）。
     * 按月画法的空区间不该画一条贴着 0 的直线，而该给一句话。
     */
    val trendHasData: Boolean = false,
) {
    /** 顶部范围控件上的文字 */
    val rangeLabel: String get() = range.label

    /** 支出环比：(本段 − 上一段) ÷ 上一段；上一段为 0 时为 null，界面显示 "—"（计划 3.4） */
    val expenseChangeRatio: Double?
        get() = StatsCalculator.monthOverMonth(summary.expenseCents, previousSummary.expenseCents)

    /** 收入环比，规则同上 */
    val incomeChangeRatio: Double?
        get() = StatsCalculator.monthOverMonth(summary.incomeCents, previousSummary.incomeCents)

    val isEmpty: Boolean get() = !isLoading && errorMessage == null && !hasRecords

    /** 设了总预算才显示「预算达成率」（计划附.1 R2）：没设就是"没这个概念"，不是 0% */
    val hasTotalBudget: Boolean get() = totalBudgetCents != null

    /** 空态标题：单月说「本月」，其它范围说「这段区间」 */
    val emptyTitle: String get() = if (range.isSingleMonth) "本月还没有记录" else "这段区间还没有记录"

    /** 空态说明 */
    val emptyMessage: String
        get() = if (range.isSingleMonth) {
            "点右下角「记一笔」，这里会给出收入、支出、结余与环比。"
        } else {
            "换个范围看看，或者先记一笔。"
        }

    /** 洞察区标题：日均/环比在任意范围都成立，预算达成率只在单月范围出现 */
    val insightTitle: String get() = if (range.isSingleMonth) "本月洞察" else "区间洞察"

    /** 支出排行/账户分布的空行文案 */
    val noExpenseHint: String
        get() = if (range.isSingleMonth) {
            "本月没有支出。转账与余额调整不算收支，所以不会出现在这里。"
        } else {
            "这段区间没有支出。转账与余额调整不算收支，所以不会出现在这里。"
        }

    val noAccountExpenseHint: String
        get() = if (range.isSingleMonth) "本月没有支出，所以没有账户分布。" else "这段区间没有支出，所以没有账户分布。"
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
 * 统计报表（F07 月报 + F10 趋势图 + 本轮增补：自然年 / 自选区间）。
 *
 * UI 不直接访问 DAO，读取全部经 LedgerRepository（计划 4.3）；
 * 区间一律用 [ReportRange] 给出的半开区间 `[from, to)`（禁止 substr 截取）。
 * 上一个等长区间也一起读：环比需要它的合计，但只读不算，口径仍归 StatsCalculator。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModel(
    private val repository: LedgerRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
    /**
     * 撤销窗口内那几笔（延迟物理删除，计划 9.2）的 id：数据库里它们还在，
     * 但报表必须当它们不存在，否则统计页会显示一个用户已经删掉的数字。
     * 过滤统一走 TransactionVisibility，与明细页/账户页同一口径。
     */
    private val pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {

    /** 当前查看的范围：切换只改这个值，查询区间与标签都由它派生 */
    private val range = MutableStateFlow<ReportRange>(ReportRange.Month(YearMonth.from(today())))

    /**
     * 绑定「范围」的数据快照：同一份快照里的行必须属于同一个范围。
     * 预算只对单月范围有意义（月预算按月设），其余范围不读。
     */
    private data class RangeSnapshot(
        val range: ReportRange,
        val current: List<TransactionEntity>,
        val previous: List<TransactionEntity>,
        /** 只有单月范围才有的当月预算：总预算 + 各分类预算（预算达成率用） */
        val budgets: List<BudgetEntity>,
    )

    /** null = 当前请求还没加载完成（用于区分"加载中"与"这段确实没有记录"） */
    private val snapshot = MutableStateFlow<RangeSnapshot?>(null)

    /** 报表要用的"参考数据"：分类（含已停用）与账户名（含已归档）。两样都到齐才出数 */
    private data class References(
        val categories: Map<Long, CategoryEntity>,
        /** 账户实体（含已归档）：详情弹层要用它拼「转出 → 转入」 */
        val accounts: Map<Long, AccountEntity>,
        val accountNames: Map<Long, String>,
    )

    private val references = MutableStateFlow<References?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    /** 数据态的打包：请求态（范围）与它分开组合，这样范围控件不必等查询回来才动 */
    private data class LoadedData(
        val snapshot: RangeSnapshot?,
        val references: References?,
        val loadError: String?,
        val pendingDeletionIds: Set<Long>,
    )

    init {
        viewModelScope.launch {
            // 只按范围重查：整页所有数字都来自这一次区间查询，不存在"标题一个范围、图里另一个范围"
            range.flatMapLatest { requested ->
                val previousRange = requested.previous()
                val budgetsFlow: Flow<List<BudgetEntity>> =
                    if (requested.isSingleMonth) {
                        // 预算只读当月：它读失败只丢预算这一份，不该把整页拖成错误态
                        repository.observeBudgets(YearMonth.from(requested.from).toString()).catch {
                            loadError.value = "读取预算失败，请稍后重试"
                            emit(emptyList())
                        }
                    } else {
                        flowOf(emptyList())
                    }
                combine(
                    repository.observeTransactions(requested.from.toString(), requested.to.toString()),
                    repository.observeTransactions(
                        previousRange.from.toString(),
                        previousRange.to.toString(),
                    ),
                    budgetsFlow,
                ) { current, previous, budgets ->
                    RangeSnapshot(requested, current, previous, budgets)
                }
                    // 读取失败也要 emit 一次：否则这一页永远停在加载态（流水页踩过同一个坑）。
                    // catch 放在内层，失败之后范围切换照旧能用。
                    .catch {
                        loadError.value = "读取流水失败，请稍后重试"
                        emit(RangeSnapshot(requested, emptyList(), emptyList(), emptyList()))
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
        range,
        combine(snapshot, references, loadError, pendingDeletionIds) { snapshot, references, loadError, pendingDeletionIds ->
            LoadedData(snapshot, references, loadError, pendingDeletionIds)
        },
        dayDetail,
    ) { requestedRange, data, detail ->
        buildState(requestedRange, data, detail)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReportsUiState())

    private fun buildState(
        requestedRange: ReportRange,
        data: LoadedData,
        dayDetail: DayDetailUiState?,
    ): ReportsUiState {
        // 快照必须属于当前请求：切范围瞬间不能把上一段的数据挂在标题下
        val current = data.snapshot?.takeIf { it.range == requestedRange }
        if (current == null || data.references == null) {
            return ReportsUiState(
                isLoading = true,
                isTrendLoading = true,
                errorMessage = data.loadError,
                range = requestedRange,
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
        val today = today()
        val trend = StatsCalculator.monthlyTotals(records = records, months = requestedRange.months)
        val daily = if (requestedRange.bucket == ReportBucket.DAY) {
            StatsCalculator.dailyTotals(
                records = records,
                from = requestedRange.from,
                to = requestedRange.to,
                today = today,
            )
        } else {
            emptyList()
        }
        val elapsed = StatsCalculator.elapsedDays(requestedRange.from, requestedRange.to, today)
        val categories = data.references.categories.mapValues { it.value.toInfo() }
        // 预算达成率的口径复用 BudgetCalculator（唯一实现），总预算没设时 totalBudgetCents 为 null。
        // 非单月范围不读预算：月预算按月设，拿它去除一个跨月区间没有可比性。
        val budget = if (requestedRange.isSingleMonth) {
            BudgetCalculator.summarize(
                records = records,
                categories = categories,
                budgets = current.budgets.map { BudgetInput(it.id, it.categoryId, it.amount) },
            )
        } else {
            null
        }
        return ReportsUiState(
            isLoading = false,
            isTrendLoading = false,
            errorMessage = data.loadError,
            range = requestedRange,
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
            trend = trend,
            trendHasData = trend.any { it.incomeCents > 0L || it.expenseCents > 0L } ||
                daily.any { it.incomeCents > 0L || it.expenseCents > 0L },
            hasRecords = visibleCurrent.isNotEmpty(),
            daily = daily,
            elapsedDays = elapsed,
            totalBudgetCents = budget?.totalBudgetCents,
            budgetAchievementRatio = budget?.totalBudgetCents?.let {
                BudgetCalculator.percent(budget.totalSpentCents, it)
            },
            budgetStatus = budget?.totalStatus ?: BudgetStatus.NONE,
            dayDetail = dayDetail,
        )
    }

    /** 上一个范围（按范围自己的一格挪） */
    fun showPreviousRange() = setRange(range.value.previous())

    /** 下一个范围 */
    fun showNextRange() = setRange(range.value.shift(1))

    /**
     * 换范围：范围控件点选、快捷项、自定义区间、直接跳到某个月都走这里。
     *
     * 只改这一个值：查询区间、上一个等长区间、横轴月份、空态文案全部由它派生，
     * 不需要为每种范围另写一份（旧版为「趋势三段」另存一份数据，才有了口径漂移的机会）。
     */
    fun setRange(newRange: ReportRange) {
        if (range.value == newRange) return
        // 上一个范围的读取错误不该带进新范围
        loadError.value = null
        range.value = newRange
    }

    /**
     * R1 日期直改的延续：直接跳到指定年月（范围弹层里的「指定月份」）。
     *
     * 与左右箭头只差"跳多远"：同一个 [range] 流、同一套半开区间查询。
     */
    fun setMonth(month: YearMonth) = setRange(ReportRange.Month(month))

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
