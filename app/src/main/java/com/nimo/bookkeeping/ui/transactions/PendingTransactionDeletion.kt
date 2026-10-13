package com.nimo.bookkeeping.ui.transactions

import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 「删除 + 撤销」的 UI 层状态机：**延迟物理删除**（计划 9.2 / V2.2 补遗 7）。
 *
 * 点删除后：整笔暂存在内存里，界面（列表与合计）立刻当它不存在，
 * 数据库里这一笔其实还在；倒计时 [undoWindowMs] 走完才真正调
 * [LedgerRepository.deleteTransaction]。不引入软删除字段，统计查询因此不必处处过滤 is_deleted。
 *
 * **撤销窗口就是提示条的生命周期**：窗口走完 → 这一笔落库、同时从 [pending] 里摘掉，
 * 页面按 [pending] 驱动的提示条随之关闭。两个时长是同一个数（[DEFAULT_UNDO_WINDOW_MS]），
 * 不存在"提示条已经没了、状态却还捂着"的错位。窗口外也**不再**提供撤销：那一笔已经物理删除，
 * 撤销只在这几秒内有效（计划 9.2：Snackbar 倒计时内可撤销）。
 *
 * 多笔可以同时待撤销，各跑各的倒计时；[pending] 按发起时间倒序，页面取第一项显示提示条，
 * 撤销按 id 精确到具体某一笔。[pendingIds] 是列表/合计/余额统一的过滤源
 * （口径实现见 com.nimo.bookkeeping.domain.stats.TransactionVisibility）。
 *
 * 进程被杀时最坏情况是"这一笔没被删掉"（数据只会多不会少）。
 */
class PendingTransactionDeletion(
    private val repository: LedgerRepository,
    /** 倒计时与落库跑在这里；生产环境用 Application 级作用域，页面返回不影响它 */
    private val scope: CoroutineScope,
    private val undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS,
) {

    /** 一次待删除会话：整笔 + 自己的倒计时 + 提示条是否还开着 */
    private class Session(val entity: TransactionEntity) {
        var snackbarVisible: Boolean = true
        var worker: Job? = null
    }

    private val mutex = Mutex()

    /** key = 流水 id。LinkedHashMap 保持发起顺序，发布时倒序即"最新的一笔在前" */
    private val sessions = LinkedHashMap<Long, Session>()

    private val _pending = MutableStateFlow<List<TransactionEntity>>(emptyList())

    /**
     * 还开着提示条的待删除会话，**最新的在前**。页面取 [firstOrNull] 显示「已删除 …／撤销」；
     * 这一项消失（窗口结束或已撤销/划掉）时，提示条就该关掉——页面不要自己再定一个时长。
     */
    val pending: StateFlow<List<TransactionEntity>> = _pending.asStateFlow()

    private val _pendingIds = MutableStateFlow<Set<Long>>(emptySet())

    /** 列表、合计、余额据此把这几笔当作不存在：所有算钱入口的唯一过滤源 */
    val pendingIds: StateFlow<Set<Long>> = _pendingIds.asStateFlow()

    /**
     * 暂存整笔并开始倒计时。调用后界面立刻看不到它，数据库要等倒计时结束才删。
     *
     * 整个状态迁移是不可取消的：调用方（编辑页的 LaunchedEffect）在返回上一页时会被取消，
     * 半途中断会留下"数据库删了、内存里却还认为没删"的状态，撤销就恢复不回来了。
     */
    suspend fun schedule(entity: TransactionEntity) = withContext(NonCancellable) {
        mutex.withLock {
            // 同一个 id 又进来一次（理论上不会）：按新的一次算，别留下两个倒计时
            sessions.remove(entity.id)?.worker?.cancel()
            val session = Session(entity)
            sessions[entity.id] = session
            publishLocked()
            session.worker = scope.launch {
                delay(undoWindowMs)
                mutex.withLock { commitLocked(entity.id) }
            }
        }
    }

    /**
     * 撤销某一笔的删除。返回 true 表示确实撤销了；false = 这一笔已经落库，或本来就不在待撤销集合里。
     */
    suspend fun undo(id: Long): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            val current = sessions[id] ?: return@withLock false
            // 还没到点：取消倒计时即可，数据库从头到尾没动过
            current.worker?.cancel()
            current.worker = null
            sessions.remove(id)
            publishLocked()
            true
        }
    }

    /**
     * 提示条消失（用户划掉它，或页面换了另一笔）：这一笔不再可撤销。
     * 物理删除照旧按自己的倒计时进行，不受影响——所以它只是不再出现在 [pending] 里，
     * [pendingIds] 要留到真正落库才摘掉。
     */
    suspend fun dismiss(id: Long) = withContext(NonCancellable) {
        mutex.withLock {
            val current = sessions[id] ?: return@withLock
            current.snackbarVisible = false
            publishLocked()
        }
    }

    /**
     * 整批取消待撤销会话，并把暂存的整笔交还给调用方（F08c：恢复备份前必须做这件事）。
     *
     * 恢复备份是**替换**全部数据：倒计时里那几笔的 id 在新数据里可能已经属于另一笔交易，
     * 让它们继续跑就会删掉刚恢复进来的行。失败路径由调用方拿返回的整笔重新 [schedule]，
     * 所以"取消 → 恢复失败"不会连带丢掉用户刚点的那次删除。
     */
    suspend fun cancelAll(): List<TransactionEntity> = withContext(NonCancellable) {
        mutex.withLock {
            val cancelled = sessions.values.map { it.entity }
            sessions.values.forEach { it.worker?.cancel() }
            sessions.clear()
            publishLocked()
            cancelled
        }
    }

    /** 等所有倒计时 + 落库动作跑完（测试用：倒计时是真实时间） */
    internal suspend fun awaitIdle() {
        val jobs = mutex.withLock { sessions.values.mapNotNull { it.worker } }
        jobs.forEach { it.join() }
    }

    /** 必须在 [mutex] 内调用：落库并摘掉这一笔（提示条随之关闭，撤销窗口同时结束） */
    private suspend fun commitLocked(id: Long) {
        val current = sessions[id] ?: return
        repository.deleteTransaction(current.entity)
        sessions.remove(id)
        publishLocked()
    }

    /** 必须在 [mutex] 内调用 */
    private fun publishLocked() {
        _pendingIds.value = sessions.keys.toSet()
        _pending.value = sessions.values
            .filter { it.snackbarVisible }
            .map { it.entity }
            .asReversed()
    }

    companion object {
        /** 撤销窗口：窗口内撤销只是取消倒计时，窗口结束时落库。它也是「已删除 …／撤销」提示条的存活时间 */
        const val DEFAULT_UNDO_WINDOW_MS: Long = 5_000L
    }
}
