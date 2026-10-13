package com.nimo.bookkeeping.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
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
    /**
     * 当月收支合计。这里是 [StatsCalculator] 的产物，页面不得自行累加：
     * 转账与余额调整永远不进收支（铁律 3）。
     */
    val summary: PeriodSummary = PeriodSummary(incomeCents = 0, expenseCents = 0),
) {
    val monthLabel: String get() = TransactionTimeline.monthLabel(month)

    val isEmpty: Boolean get() = !isLoading && errorMessage == null && groups.isEmpty()
}

/**
 * 流水时间线（F05）。
 *
 * UI 不直接访问 DAO，读写全部经 [LedgerRepository]（计划 4.3）；
 * 月度查询走 [Dates.monthBounds] 得到的 [from, to) 日期区间，禁止 substr 截取。
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

    /** 当前查看的月份：切换只改这个值，查询区间与标签都由它派生 */
    private val month = MutableStateFlow(YearMonth.from(today()))

    /** 已到位的某个"月份快照"：把数据与它所属的月份绑在一起 */
    private data class MonthSnapshot(val month: YearMonth, val rows: List<TransactionEntity>)

    /**
     * null = 当前月份还没加载完成（用于区分"加载中"与"本月确实没有记录"）。
     * 绑定月份是为了切换月份时不把上一个月的行挂在新的月份标题下。
     */
    private val snapshot = MutableStateFlow<MonthSnapshot?>(null)
    private val accounts = MutableStateFlow<Map<Long, AccountEntity>?>(null)
    private val categories = MutableStateFlow<Map<Long, CategoryEntity>?>(null)
    private val loadError = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            month.flatMapLatest { ym ->
                val (from, to) = Dates.monthBounds(ym)
                repository.observeTransactions(from, to)
                    .catch {
                        loadError.value = "读取流水失败，请稍后重试"
                        emit(emptyList())
                    }
                    .map { MonthSnapshot(ym, it) }
            }.collect { snapshot.value = it }
        }
        viewModelScope.launch {
            // 账户含已归档：归档账户不能再记账，但历史流水要照常显示账户名（M2 验收）
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
     * 这一步只做"凑齐材料"，不涉及展示口径：把当前月份、该月的行、账户/分类字典与读取错误收在一起，
     * 再与待删除 id 合成最终状态（combine 没有六路重载，所以拆成两层）。
     */
    private data class TimelineInput(
        val month: YearMonth,
        /** null = 这个月还没加载完成 */
        val rows: List<TransactionEntity>?,
        val accounts: Map<Long, AccountEntity>?,
        val categories: Map<Long, CategoryEntity>?,
        val errorMessage: String?,
    )

    private val timelineInput: Flow<TimelineInput> = combine(
        month,
        snapshot,
        accounts,
        categories,
        loadError,
    ) { month, snapshot, accounts, categories, loadError ->
        TimelineInput(
            month = month,
            // 绑定月份是为了切换月份时不把上一个月的行挂在新的月份标题下
            rows = snapshot?.takeIf { it.month == month }?.rows,
            accounts = accounts,
            categories = categories,
            errorMessage = loadError,
        )
    }

    val uiState: StateFlow<TransactionsUiState> = combine(
        timelineInput,
        pendingDeletionIds,
    ) { input, pendingDeletionIds ->
        // 待删除的那几笔在倒计时内先从界面消失；数据库里它们其实还在（计划 9.2 延迟物理删除）
        val rows = input.rows?.let { TransactionVisibility.visible(it, pendingDeletionIds) }
        val accounts = input.accounts
        val categories = input.categories
        // 三样都到位才算"这个月加载完成"：
        // 否则会先闪一屏"未分类 / 未知账户"；切月时还可能把上个月的行与合计挂在新月份下。
        val ready = rows != null && accounts != null && categories != null
        val groups: List<TransactionDayGroup>
        val summary: PeriodSummary
        if (rows != null && accounts != null && categories != null) {
            groups = TransactionTimeline.group(rows, categories, accounts, today())
            // 合计一律由 StatsCalculator 给出（口径唯一）；被暂存的那笔因此立刻退出合计。
            // 区间已由 observeTransactions(from, to) 限定在本月，这里不再重复一次边界判断。
            summary = StatsCalculator.summarize(TransactionTimeline.toRecords(rows))
        } else {
            groups = emptyList()
            summary = PeriodSummary(incomeCents = 0, expenseCents = 0)
        }
        TransactionsUiState(
            isLoading = !ready,
            errorMessage = input.errorMessage,
            month = input.month,
            groups = groups,
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

    companion object {
        fun factory(
            repository: LedgerRepository,
            pendingDeletionIds: Flow<Set<Long>> = flowOf(emptySet()),
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { TransactionsViewModel(repository, pendingDeletionIds = pendingDeletionIds) }
        }
    }
}
