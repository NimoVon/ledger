package com.nimo.bookkeeping.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.local.toRecordOrNull
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.filter.TransactionFilter
import com.nimo.bookkeeping.domain.stats.PeriodSummary
import com.nimo.bookkeeping.domain.stats.StatsCalculator
import com.nimo.bookkeeping.domain.stats.TransactionVisibility
import com.nimo.bookkeeping.util.Dates
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * 流水页状态（计划 4.4：至少覆盖加载、内容、空状态和可理解的错误）。
 */
data class TransactionsUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val month: YearMonth = YearMonth.now(),
    val groups: List<TransactionDayGroup> = emptyList(),
    /** 当前生效的筛选条件（F06）；空条件 = 按月浏览 */
    val filter: TransactionFilter = TransactionFilter(),
    /** 已开条件的文字摘要，页面直接渲染（[TransactionFilterSummary] 唯一实现） */
    val filterChips: List<String> = emptyList(),
    /** 筛选面板的候选项（账户含已归档、分类含已停用） */
    val filterOptions: FilterOptions = FilterOptions(),
    /**
     * 区间内收支合计。这里是 [StatsCalculator] 的产物，页面不得自行累加：
     * 转账与余额调整永远不进收支（铁律 3）。
     */
    val summary: PeriodSummary = PeriodSummary(incomeCents = 0, expenseCents = 0),
) {
    val monthLabel: String get() = TransactionTimeline.monthLabel(month)

    /** 有没有开筛选：决定空状态说"本月没有记录"还是"没有符合条件的记录" */
    val hasActiveFilter: Boolean get() = filter.isActive

    /** 指定了日期区间后月份切换就没有意义了（区间优先），这时不再显示月份控件 */
    val showsMonthSwitcher: Boolean get() = !filter.hasDateRange

    val isEmpty: Boolean get() = !isLoading && errorMessage == null && groups.isEmpty()
}

/**
 * 流水时间线（F05）+ 筛选与搜索（F06）。
 *
 * UI 不直接访问 DAO，读写全部经 [LedgerRepository]（计划 4.3）；
 * 日期区间一律走 [Dates.monthBounds] 或 [TransactionFilter.queryBounds] 得到的 [from, to) 范围比较，
 * 命中 occurredOn 索引，禁止 substr 截取。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionsViewModel(
    private val repository: LedgerRepository,
    private val today: () -> LocalDate = { LocalDate.now() },
    /**
     * UI 层延迟物理删除（计划 9.2）里正被暂存的那几笔 id：倒计时内它们既不在列表也不在合计里。
     * 过滤一律走 [TransactionVisibility]（本页不再自己写一份），以后新页面也不会漏。
     * 默认值表示"没有待删除的暂存"，测试与旧调用点因此不必都接上。
     */
    private val pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
) : ViewModel() {

    /** 当前查看的月份：切换只改这个值 */
    private val month = MutableStateFlow(YearMonth.from(today()))

    /** 当前生效的筛选条件 */
    private val filter = MutableStateFlow(TransactionFilter())

    /**
     * 视图键 = 月份 + 筛选条件。两者任一变化都要重新查询（查询区间由它们派生），
     * 数据必须与它所属的视图键绑在一起，否则换了条件会先把上一批行挂在新条件下闪一帧。
     */
    private data class ViewKey(val month: YearMonth, val filter: TransactionFilter)

    private val viewKey: Flow<ViewKey> =
        combine(month, filter) { month, filter -> ViewKey(month, filter) }

    /** 已到位的某一份"视图快照"：数据与它所属的视图键绑在一起 */
    private data class Snapshot(val key: ViewKey, val rows: List<TransactionEntity>)

    /**
     * null = 当前视图还没加载完成（用于区分"加载中"与"确实没有记录"）。
     */
    private val snapshot = MutableStateFlow<Snapshot?>(null)
    private val accounts = MutableStateFlow<Map<Long, AccountEntity>?>(null)
    private val categories = MutableStateFlow<Map<Long, CategoryEntity>?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            viewKey.flatMapLatest { key ->
                // 有日期区间就按区间查（一次范围比较），否则按月浏览
                val (from, to) = key.filter.queryBounds() ?: Dates.monthBounds(key.month)
                repository.observeTransactions(from, to)
                    .catch {
                        loadError.value = "读取流水失败，请稍后重试"
                        emit(emptyList())
                    }
                    .map { Snapshot(key, it) }
            }.collect { snapshot.value = it }
        }
        viewModelScope.launch {
            // 账户含已归档：归档账户不能再记账，但历史流水要照常显示账户名、也要能按它筛（M2 验收）
            repository.observeAccounts()
                .catch {
                    loadError.value = "读取账户失败，请稍后重试"
                    emit(emptyList())
                }
                .collect { list -> accounts.value = list.associateBy { it.id } }
        }
        viewModelScope.launch {
            // 分类含已停用：理由同上，历史流水的分类名与图标不能丢
            repository.observeAllCategories()
                .catch {
                    loadError.value = "读取分类失败，请稍后重试"
                    emit(emptyList())
                }
                .collect { list -> categories.value = list.associateBy { it.id } }
        }
    }

    /**
     * 这一步只做"凑齐材料"，不涉及展示口径：把视图键、该视图的行、账户/分类字典与读取错误收在一起，
     * 再与待删除 id 合成最终状态（combine 没有六路重载，所以拆成两层）。
     */
    private data class TimelineInput(
        val key: ViewKey,
        /** null = 这一份视图（月份 + 筛选条件）还没加载完成 */
        val rows: List<TransactionEntity>?,
        val accounts: Map<Long, AccountEntity>?,
        val categories: Map<Long, CategoryEntity>?,
        val errorMessage: String?,
    )

    private val timelineInput: Flow<TimelineInput> = combine(
        viewKey,
        snapshot,
        accounts,
        categories,
        loadError,
    ) { key, snapshot, accounts, categories, loadError ->
        TimelineInput(
            key = key,
            rows = snapshot?.takeIf { it.key == key }?.rows,
            accounts = accounts,
            categories = categories,
            errorMessage = loadError,
        )
    }

    val uiState: StateFlow<TransactionsUiState> = combine(
        timelineInput,
        pendingDeletionIds,
    ) { input, pendingDeletionIds ->
        val accountMap = input.accounts
        val categoryMap = input.categories
        // 待删除的那几笔在倒计时内先从界面消失；数据库里它们其实还在（计划 9.2 延迟物理删除）
        val visible = input.rows?.let { TransactionVisibility.visible(it, pendingDeletionIds) }
        // 三样都到位才算"这一份视图加载完成"：否则会先闪一屏"未分类 / 未知账户"
        val ready = visible != null && accountMap != null && categoryMap != null

        var groups: List<TransactionDayGroup> = emptyList()
        var summary = PeriodSummary(incomeCents = 0, expenseCents = 0)
        var chips: List<String> = emptyList()
        var options = FilterOptions()
        if (ready && accountMap != null && categoryMap != null && visible != null) {
            val shown = matchFilter(visible, input.key.filter, categoryMap)
            groups = TransactionTimeline.group(shown, categoryMap, accountMap, today())
            // 合计一律由 StatsCalculator 给出（口径唯一）：筛选态下的合计就是"当前所见那些行"的合计
            summary = StatsCalculator.summarize(TransactionTimeline.toRecords(shown))
            chips = TransactionFilterSummary.chips(
                filter = input.key.filter,
                accountNames = accountMap.mapValues { (_, account) -> account.name },
                categoryNames = categoryMap.mapValues { (_, category) -> category.name },
            )
            options = buildFilterOptions(accountMap, categoryMap)
        }
        TransactionsUiState(
            isLoading = !ready,
            errorMessage = input.errorMessage,
            month = input.key.month,
            groups = groups,
            filter = input.key.filter,
            filterChips = chips,
            filterOptions = options,
            summary = summary,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    fun showPreviousMonth() = moveMonth(-1)

    fun showNextMonth() = moveMonth(1)

    private fun moveMonth(delta: Long) {
        // 上个月的读取错误不该带进新月份
        loadError.value = null
        month.update { it.plusMonths(delta) }
    }

    /** 应用筛选条件（F06）；空条件等价于清空筛选、回到按月浏览 */
    fun applyFilter(filter: TransactionFilter) {
        // 上一次读取的错误不该带进新条件
        loadError.value = null
        this.filter.value = filter
    }

    fun clearFilter() = applyFilter(TransactionFilter())

    /**
     * 筛选（F06）：账户、分类（父分类命中其子分类）、金额区间、日期区间、备注关键词。
     * 匹配口径唯一实现在 [TransactionFilter.matches]，这里只负责把实体转成领域模型。
     *
     * 非法 occurredOn 的行（只可能来自绕过校验器的直插）转不成领域模型、因此不参与匹配：
     * 它们在筛选态下不出现，而且本来也不进任何合计。
     */
    private fun matchFilter(
        rows: List<TransactionEntity>,
        filter: TransactionFilter,
        categories: Map<Long, CategoryEntity>,
    ): List<TransactionEntity> {
        if (!filter.isActive) return rows
        val parentIds = categories.mapValues { (_, category) -> category.parentId }
        return rows.filter { row ->
            row.toRecordOrNull()?.let { filter.matches(it, parentIds) } == true
        }
    }

    /** 筛选候选项：账户含已归档、分类含已停用，顺序沿用实体的 sortOrder（与选择器一致） */
    private fun buildFilterOptions(
        accounts: Map<Long, AccountEntity>,
        categories: Map<Long, CategoryEntity>,
    ): FilterOptions = FilterOptions(
        accounts = accounts.values
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map { FilterAccountOption(id = it.id, name = it.name, archived = it.isArchived) },
        categories = categories.values
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .map {
                FilterCategoryOption(
                    id = it.id,
                    name = it.name,
                    kind = it.kind,
                    parentId = it.parentId,
                    archived = it.isArchived,
                    icon = it.icon,
                )
            },
    )

    companion object {
        fun factory(
            repository: LedgerRepository,
            pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TransactionsViewModel(repository, pendingDeletionIds = pendingDeletionIds) }
        }
    }
}
