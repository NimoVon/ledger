package com.nimo.bookkeeping.ui.transactions

import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.data.repository.LedgerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
 * 撤销分两种情况，在同一把锁内判定，不存在竞态：
 * - 还没到点：取消倒计时即可，数据库从头到尾没动过；
 * - 已经落库（Snackbar 还开着，或刚好超时那一刻）：按原 id 整笔恢复
 *   [LedgerRepository.restoreTransaction]，id 不变，因此列表顺序不跳动。
 *
 * 同一时间只允许一笔可撤销：新的删除会先把上一笔立即落定。
 * 进程被杀时最坏情况是"这一笔没被删掉"（数据只会多不会少）。
 */
class PendingTransactionDeletion(
    private val repository: LedgerRepository,
    /** 倒计时与落库跑在这里；生产环境用 Application 级作用域，页面返回不影响它 */
    private val scope: CoroutineScope,
    private val undoWindowMs: Long = DEFAULT_UNDO_WINDOW_MS,
) {

    /** 一次待删除会话：整笔 + 是否已物理删除 + Snackbar 是否还开着 */
    private class Session(val entity: TransactionEntity) {
        var committed: Boolean = false
        var snackbarVisible: Boolean = true
    }

    private val mutex = Mutex()
    private var session: Session? = null
    private var worker: Job? = null

    private val _pending = MutableStateFlow<TransactionEntity?>(null)

    /** 当前可撤销的那一笔（null = 没有待撤销的删除）。Snackbar 据此显示 */
    val pending: StateFlow<TransactionEntity?> = _pending.asStateFlow()

    /** 列表与合计据此把这一笔暂时当作不存在 */
    val pendingId: Flow<Long?> = pending.map { it?.id }

    /**
     * 暂存整笔并开始倒计时。调用后界面立刻看不到它，数据库要等倒计时结束才删。
     *
     * 整个状态迁移是不可取消的：调用方（编辑页的 LaunchedEffect）在返回上一页时会被取消，
     * 半途中断会留下"数据库删了、内存里却还认为没删"的状态，撤销就恢复不回来了。
     */
    suspend fun schedule(entity: TransactionEntity) = withContext(NonCancellable) {
        mutex.withLock {
            worker?.cancel()
            worker = null
            // 上一笔还没撤销就先落定，保证"可撤销的永远只有最后一笔"
            commitLocked()
            session = Session(entity)
            _pending.value = entity
            worker = scope.launch {
                delay(undoWindowMs)
                mutex.withLock { commitLocked() }
            }
        }
    }

    /**
     * 撤销删除。返回 true 表示确实撤销了一笔（false = 没有待撤销的删除）。
     */
    suspend fun undo(): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            val current = session ?: return@withLock false
            worker?.cancel()
            worker = null
            // 已落库才需要恢复；没落库的话数据库里本来就没动过，不必写一次又删一次
            if (current.committed) repository.restoreTransaction(current.entity)
            endLocked()
            true
        }
    }

    /**
     * Snackbar 消失：这一笔不再可撤销。物理删除照旧按倒计时进行，不受影响。
     */
    suspend fun dismiss() = withContext(NonCancellable) {
        mutex.withLock {
            val current = session ?: return@withLock
            current.snackbarVisible = false
            if (current.committed) endLocked()
        }
    }

    /** 等当前这一轮的倒计时 + 落库动作跑完（测试用：倒计时是真实时间） */
    internal suspend fun awaitIdle() {
        val current = mutex.withLock { worker }
        current?.join()
    }

    /** 必须在 [mutex] 内调用 */
    private suspend fun commitLocked() {
        val current = session ?: return
        if (current.committed) return
        repository.deleteTransaction(current.entity)
        current.committed = true
        // Snackbar 已经关掉就不用留着可撤销状态了
        if (!current.snackbarVisible) endLocked()
    }

    /** 必须在 [mutex] 内调用 */
    private fun endLocked() {
        session = null
        _pending.value = null
    }

    companion object {
        /** 撤销窗口：这段时间内数据库里的原记录还在，撤销只是取消倒计时 */
        const val DEFAULT_UNDO_WINDOW_MS: Long = 5_000L
    }
}
