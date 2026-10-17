package com.nimo.ledger.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.ledger.data.local.DataStoreLastUsedCategoryStore
import com.nimo.ledger.data.local.entity.CategoryEntity
import com.nimo.ledger.data.local.entity.TransactionEntity
import com.nimo.ledger.data.repository.LedgerRepository
import com.nimo.ledger.domain.model.TransactionType
import com.nimo.ledger.domain.validate.TransactionValidator
import com.nimo.ledger.ui.LedgerIcons
import com.nimo.ledger.ui.components.EmptyState
import com.nimo.ledger.ui.components.EmptyTone
import com.nimo.ledger.ui.transfer.TransferFormSection
import com.nimo.ledger.ui.transfer.TransferUiState
import com.nimo.ledger.ui.transfer.TransferViewModel
import com.nimo.ledger.util.Dates
import com.nimo.ledger.util.Money

/** 预选了「上次用过的分类」时那句提示的 tag：走查/UI 测试按它取文案，不靠截图认字 */
internal const val LAST_USED_CATEGORY_HINT_TEST_TAG: String = "record-last-category-hint"

/** 底部主按钮「保存」的 tag（R-4）：与顶栏「保存」同一个动作、同一份 disabled 逻辑 */
internal const val RECORD_SAVE_BOTTOM_TEST_TAG: String = "record-save-bottom"

/** 顶栏「保存」的 tag：两处按钮文案相同，测试必须按 tag 取，不能按文案 */
internal const val RECORD_SAVE_TOP_TEST_TAG: String = "record-save-top"

/** 金额复核行（R-7）：只有金额能解析成合法分时才出现 */
internal const val AMOUNT_REVIEW_TEST_TAG: String = "record-amount-review"

/** 分类 chip 区（R-2）：限高 + 内部纵向滚 */
internal const val CATEGORY_CHIPS_TEST_TAG: String = "record-category-chips"

/**
 * 分类 chip 区的最大高度（R-2）：约 3 行 chip（每行 40dp + 4dp 间距）。
 * 分类多到 20+ 时，账户/日期/备注不再被挤到屏幕外。
 */
internal const val CATEGORY_CHIPS_MAX_HEIGHT_DP: Int = 168

/**
 * 记一笔 / 改一笔（F03 收支，F04 转账入口）。
 *
 * 从任意顶层页面（明细/统计/设置）的「记一笔」按钮唤起，冷启动到保存 ≤ 3 次点击：
 * 点按钮 → 输入金额 → 点保存（账户默认选中第一个可用账户）。
 * 顶部三个分段：支出 / 收入 / 转账——「转账」不换页，就地换成转账表单（两个账户 + 没有分类），
 * 保存按钮、返回、日期选择共用同一个 Scaffold。转账表单与转账页是同一块内容
 * （[com.nimo.ledger.ui.transfer.TransferFormSection]），不存第二份实现。
 * 点流水行进来则是编辑模式：表单、校验与错误提示完全共用这一套，只是预填了整笔内容。
 * 编辑支出/收入时不出现「转账」分段；转账行的编辑走转账页。余额校准（P1）不走本页。
 *
 * @param editingId 非空 = 编辑这一笔
 * @param onDeleted 用户要求删除这一笔。**本页不删数据**：整笔交给延迟物理删除策略
 *   （[com.nimo.ledger.ui.transactions.PendingTransactionDeletion]），
 *   页面随即返回，撤销交给流水页的 Snackbar（计划 9.2）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(
    repository: LedgerRepository,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
    editingId: Long? = null,
    onDeleted: suspend (TransactionEntity) -> Unit = {},
) {
    val context = LocalContext.current
    // 上次用过的分类存在 DataStore 里（不动 Room schema）；按 Context 缓存，重组不重建
    val lastUsedCategoryStore = remember(context) {
        DataStoreLastUsedCategoryStore.from(context)
    }
    val viewModel: RecordViewModel =
        viewModel(
            factory = RecordViewModel.factory(
                repository = repository,
                editingId = editingId,
                lastUsedCategoryStore = lastUsedCategoryStore,
            ),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }
    // 内联新建分类（附.1 R3）：对话框开着时不让重入，保存成功由 createdCategoryId 信号收掉
    var showCreateCategory by remember { mutableStateOf(false) }

    // 「转账」是记一笔页里的第三个分段（用户要求：转账入口放进记一笔，不再占一个悬浮按钮）：
    // 切过去就地渲染转账表单。转账有自己的 ViewModel 与校验（两个账户、没有分类）。
    // 只在真的切过去时才建它——不切就一分钱开销都不付。
    var transferMode by rememberSaveable { mutableStateOf(false) }
    val transferViewModel: TransferViewModel? = if (transferMode) {
        viewModel(factory = TransferViewModel.factory(repository))
    } else {
        null
    }
    val transferState: TransferUiState? = if (transferViewModel != null) {
        val transferUiState by transferViewModel.uiState.collectAsStateWithLifecycle()
        transferUiState
    } else {
        null
    }

    // 保存成功 → 通知页面返回（先消费信号，避免重组时重复触发）
    LaunchedEffect(state.savedTransactionId) {
        val savedId = state.savedTransactionId ?: return@LaunchedEffect
        viewModel.consumeSaved()
        onSaved(savedId)
    }

    // 删除请求 → 把整笔交给延迟删除策略，然后返回流水页（撤销在那边提示）
    LaunchedEffect(state.deleteRequested) {
        val entity = state.deleteRequested ?: return@LaunchedEffect
        viewModel.consumeDeleteRequest()
        onDeleted(entity)
    }

    // 转账保存成功 → 和收支一样从这里返回（先消费信号）
    LaunchedEffect(transferState?.savedTransactionId) {
        val savedId = transferState?.savedTransactionId ?: return@LaunchedEffect
        transferViewModel?.consumeSaved()
        onSaved(savedId)
    }

    val isSaving = transferState?.isSaving ?: state.isSaving

    // 「保存」只跟着同一个展示决策：表单露出来了才有保存。
    // 顶栏与底部主按钮（R-4）共用这一份判断，加按钮不新增一条分支。
    val showSave = if (transferState != null) {
        transferState.isFormVisible
    } else {
        state.screen == RecordScreenState.Form
    }
    val onSave: () -> Unit = {
        if (transferViewModel != null) {
            transferViewModel.save()
        } else {
            viewModel.save()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            transferState != null -> "转账"
                            state.isEditing -> "编辑"
                            else -> "记一笔"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSaving) {
                        Icon(imageVector = LedgerIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (showSave) {
                        TextButton(
                            onClick = onSave,
                            enabled = !isSaving,
                            modifier = Modifier.testTag(RECORD_SAVE_TOP_TEST_TAG),
                        ) {
                            Text(if (isSaving) "保存中…" else "保存")
                        }
                    }
                },
            )
        },
        // 底部主按钮（R-4）：大屏单手够不到顶栏，表单底部再给一个同动作的保存。
        // 不新增步骤——「3 次点击记账」仍是 点按钮 → 输金额 → 点保存。
        bottomBar = {
            if (showSave) {
                RecordSaveBar(isSaving = isSaving, onSave = onSave)
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        if (transferState != null && transferViewModel != null) {
            // 转账：分段 + 转账表单（TransferFormSection 是唯一的转账表单实现）
            Column(modifier = contentModifier) {
                RecordSegmentRow(
                    selected = RecordSegment.TRANSFER,
                    showTransfer = true,
                    onSelect = { segment ->
                        if (segment == RecordSegment.TRANSFER) {
                            transferMode = true
                        } else {
                            transferMode = false
                            viewModel.setType(segment.type)
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                TransferFormSection(
                    state = transferState,
                    viewModel = transferViewModel,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(modifier = contentModifier) {
                // 分段只在表单露出来时出现：加载中/没有可用账户时，给一个切不过去的分段没有意义
                if (state.screen == RecordScreenState.Form) {
                    RecordSegmentRow(
                        selected = if (state.form.type == TransactionType.INCOME) {
                            RecordSegment.INCOME
                        } else {
                            RecordSegment.EXPENSE
                        },
                        // 编辑一笔支出/收入时不给「转账」分段：那时候切到转账没有意义
                        showTransfer = !state.isEditing,
                        onSelect = { segment ->
                            if (segment == RecordSegment.TRANSFER) {
                                transferMode = true
                            } else {
                                viewModel.setType(segment.type)
                            }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                val bodyModifier = Modifier.weight(1f)

                // 分支顺序只在 RecordUiState.screen 里定义一次；这里 by-when 是穷举的，没有 else 兜底
                when (state.screen) {
                    RecordScreenState.Loading -> Box(bodyModifier, Alignment.Center) {
                        CircularProgressIndicator()
                    }

                    RecordScreenState.LoadError -> EmptyState(
                        title = "加载失败",
                        message = state.loadErrorMessage.orEmpty(),
                        modifier = bodyModifier,
                        tone = EmptyTone.ERROR,
                    )

                    // 转账（F04）与余额调整（P1）的编辑入口不在本页：硬套收支表单会写出半截数据
                    RecordScreenState.UnsupportedType -> EmptyState(
                        title = "这一笔不能在这里改",
                        message = "转账与余额调整不进入收支统计，它们有各自的编辑入口。\n本页只处理支出与收入。",
                        modifier = bodyModifier,
                    )

                    // 没有可用账户就无法记账：明确指路，不给一张填了也存不进去的表单。
                    // 编辑模式不算在内（见 RecordUiState.hasNoAccount）：否则这一笔的恢复入口也会被顶掉。
                    RecordScreenState.NoAccount -> EmptyState(
                        title = "还没有可用账户",
                        message = "先到「设置 → 账户管理」新建一个账户，才能记一笔。\n（已归档的账户不会出现在这里）",
                        modifier = bodyModifier,
                    )

                    RecordScreenState.Form -> RecordFormBody(
                        state = state,
                        viewModel = viewModel,
                        onPickDate = { showDatePicker = true },
                        onCreateCategory = { showCreateCategory = true },
                        modifier = bodyModifier,
                    )
                }
            }
        }
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = Dates.utcMillisFromIsoDate(state.form.occurredOn),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let {
                            viewModel.setDate(Dates.isoDateFromUtcMillis(it))
                        }
                        showDatePicker = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) { DatePicker(state = pickerState) }
    }

    // 内联新建分类（附.1 R3）：kind 锁定当前收支方向，名称 + 可选上级；建好由 ViewModel 自动选中
    if (showCreateCategory) {
        CategoryCreateDialog(
            kindLabel = if (state.form.type == TransactionType.INCOME) "收入" else "支出",
            parentOptions = state.categories.filter { it.parentId == null },
            creating = state.isCreatingCategory,
            errorMessage = state.categoryCreateErrorMessage,
            onDismiss = {
                showCreateCategory = false
                viewModel.consumeCreatedCategory()
            },
            onCreate = viewModel::createCategory,
        )
    }
    // 建好就收掉对话框：选中动作在 ViewModel 里已经做完，页面只负责"关窗口"
    LaunchedEffect(state.createdCategoryId) {
        if (state.createdCategoryId != null) {
            showCreateCategory = false
            viewModel.consumeCreatedCategory()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordFormBody(
    state: RecordUiState,
    viewModel: RecordViewModel,
    onPickDate: () -> Unit,
    onCreateCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountFocus = remember { FocusRequester() }
    // 新建时进页面就聚焦金额："记一笔"的主体就是输金额，省一次点击。
    // 编辑时金额已经填好，弹键盘反而挡住要改的其它字段，不抢焦点。
    LaunchedEffect(Unit) { if (!state.isEditing) amountFocus.requestFocus() }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 提交错误汇总：放在最上方，滚动时也看得见
        val message = state.saveErrorMessage
            ?: state.formErrors.takeIf { it.isNotEmpty() }?.joinToString("；") { it.toMessage() }
        if (message != null) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 原账户/分类已归档或停用：选择器里按 M2 规则不会出现它们，得说清楚为什么没选中
        val archivedAccountName = state.originalAccountName
        if (state.originalAccountUnavailable) {
            if (archivedAccountName != null) {
                // 就地恢复比"把这一笔挪到别的账户"干净：历史归属不动，账户回到选择器
                Text(
                    text = "这笔账记在已归档账户「$archivedAccountName」上，直接保存会被拒绝。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = viewModel::restoreOriginalAccount,
                    enabled = !state.isSaving && !state.isRestoringAccount,
                ) {
                    Text(if (state.isRestoringAccount) "恢复中…" else "恢复账户并编辑")
                }
            } else {
                Text(
                    text = "原账户已归档，不会再出现在选择器里，请重新选择账户。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (state.originalCategoryUnavailable) {
            val archivedCategoryName = state.originalCategoryName
            if (archivedCategoryName != null) {
                // 同账户：就地恢复比「把这一笔挪到别的分类」干净，历史统计口径不动
                Text(
                    text = "这一笔用的分类「$archivedCategoryName」已停用，直接保存会被拒绝。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = viewModel::restoreOriginalCategory,
                    enabled = !state.isSaving && !state.isRestoringCategory,
                ) {
                    Text(if (state.isRestoringCategory) "恢复中…" else "恢复分类并编辑")
                }
            } else {
                Text(
                    text = "原分类已停用，不会再出现在选择器里，请重新选择分类。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 金额 + 复核行（R-7）：复核行紧贴金额框下 12dp，所以两者同组；
        // 放进外层 Column 会吃到表单统一的 16dp 间距，不是设计要的那 12dp。
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            OutlinedTextField(
                value = state.form.amountText,
                onValueChange = viewModel::setAmount,
                label = { Text("金额") },
                prefix = { Text("¥") },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(amountFocus),
            )
            // 能解析成合法金额才显示；空/非法输入不在这里猜，交给下面的错误提示
            AmountReviewLine(cents = Money.parseCents(state.form.amountText)?.takeIf { it > 0 })
        }

        FieldSection(title = "分类", hint = state.lastUsedCategoryHint) {
            if (state.categories.isEmpty()) {
                Text(
                    text = "这个方向下还没有可用分类（停用的分类不会出现在这里）",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            CategoryChips(
                categories = state.categories,
                selectedCategoryId = state.form.categoryId,
                onSelectCategory = viewModel::setCategory,
                onCreateCategory = onCreateCategory,
            )
        }

        FieldSection(title = "账户") {
            if (state.accounts.isEmpty()) {
                // 只会出现在"编辑一笔记在唯一已归档账户上的流水"：上面已经给了恢复入口
                Text(text = "没有其它可用账户", style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.accounts.forEach { account ->
                        FilterChip(
                            selected = state.effectiveAccountId == account.id,
                            onClick = { viewModel.setAccount(account.id) },
                            label = { Text(account.name) },
                        )
                    }
                }
            }
        }

        FieldSection(title = "日期") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 日期图标 20dp：可点的日期字段要一眼看出「这里能点」（§5.1 Calendar）
                TextButton(onClick = onPickDate) {
                    Icon(
                        imageVector = LedgerIcons.Calendar,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(state.form.occurredOn)
                }
                if (state.form.occurredOn != Dates.today()) {
                    TextButton(onClick = { viewModel.setDate(Dates.today()) }) { Text("今天") }
                }
                if (state.form.occurredOn == Dates.today()) {
                    Text(text = "今天", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedTextField(
            value = state.form.note,
            onValueChange = viewModel::setNote,
            label = { Text("备注") },
            singleLine = false,
            maxLines = 3,
            supportingText = {
                Text("${state.form.note.length}/${TransactionValidator.MAX_NOTE_LENGTH}")
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 删除入口（仅编辑模式）。这里不做二次确认：延迟物理删除 + Snackbar 撤销
        // 已经把"点错了"的补救成本降到一次点击，再加弹窗只会拖慢正常流程（计划 9.2）
        if (state.isEditing) {
            HorizontalDivider()
            TextButton(
                onClick = viewModel::requestDelete,
                enabled = !state.isSaving,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("删除这一笔")
            }
            Text(
                text = "删除后它会立刻从流水里消失，底部提示条里可以撤销。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 底部主按钮「保存」（R-4）：全宽 52dp，与顶栏「保存」同一个动作、同一份 disabled 逻辑。
 *
 * 为什么两处都留：大屏单手够不到顶栏右上角，但习惯顶栏的人也不该被改掉肌肉记忆。
 */
@Composable
internal fun RecordSaveBar(isSaving: Boolean, onSave: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = onSave,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .height(52.dp)
                .testTag(RECORD_SAVE_BOTTOM_TEST_TAG),
        ) {
            Text(if (isSaving) "保存中…" else "保存")
        }
    }
}

/**
 * 金额复核行（R-7）：紧贴金额框下 12dp 的一行 bodySmall / onSurfaceVariant。
 *
 * 只有金额能解析成合法分（> 0）时才占位——非法输入由校验错误说，不在这里再写一遍。
 */
@Composable
internal fun AmountReviewLine(cents: Long?) {
    if (cents == null) return
    Text(
        text = "核对：" + Money.formatCurrency(cents),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = 12.dp)
            .testTag(AMOUNT_REVIEW_TEST_TAG),
    )
}

/**
 * 分类 chip 区（R-2）：最多 [CATEGORY_CHIPS_MAX_HEIGHT_DP] 高，超出在框内纵向滚动，
 * 不再把账户 / 日期 / 备注挤出屏幕。
 *
 * 选中项不在可视区时（进页面预选了「上次用过的分类」、或点了框外的分类）自动滚到可见。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryChips(
    categories: List<CategoryEntity>,
    selectedCategoryId: Long?,
    onSelectCategory: (Long) -> Unit,
    onCreateCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chipsScrollState = rememberScrollState()
    val selectedChipRequester = remember { BringIntoViewRequester() }
    // 限高之后选中项可能落在框外：选中变化就把那一颗滚进来
    LaunchedEffect(selectedCategoryId) {
        if (selectedCategoryId != null) selectedChipRequester.bringIntoView()
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = CATEGORY_CHIPS_MAX_HEIGHT_DP.dp)
            .verticalScroll(chipsScrollState)
            .testTag(CATEGORY_CHIPS_TEST_TAG),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            categories.forEach { category ->
                val selected = selectedCategoryId == category.id
                FilterChip(
                    selected = selected,
                    onClick = { onSelectCategory(category.id) },
                    label = {
                        Text(
                            listOfNotNull(category.icon, category.name).joinToString(" "),
                        )
                    },
                    modifier = if (selected) {
                        Modifier.bringIntoViewRequester(selectedChipRequester)
                    } else {
                        Modifier
                    },
                )
            }
            // 内联新建（附.1 R3）：记到一半发现没合适分类，不用退出这一笔；建好自动选中。
            // 一个可用分类都没有时它也在：否则用户会以为"这里没救"
            FilterChip(
                selected = false,
                onClick = onCreateCategory,
                label = { Text(CREATE_CATEGORY_LABEL) },
            )
        }
    }
}

@Composable
private fun FieldSection(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            if (hint != null) {
                // 预选「上次用过的分类」时说清为什么替你选好了、是哪天用过
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(LAST_USED_CATEGORY_HINT_TEST_TAG),
                )
            }
        }
        content()
    }
}

/**
 * 记一笔页顶部的分段（顺序即按钮顺序）：支出 / 收入就地换表单，转账换成转账表单。
 * 转账分段只在新建时出现——编辑一笔支出/收入时切到转账没有意义。
 */
private enum class RecordSegment(val type: TransactionType, val label: String) {
    EXPENSE(TransactionType.EXPENSE, "支出"),
    INCOME(TransactionType.INCOME, "收入"),
    TRANSFER(TransactionType.TRANSFER, "转账"),
}

/** 分段行：三段，或只有收支两段（[showTransfer] = false 时） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordSegmentRow(
    selected: RecordSegment,
    showTransfer: Boolean,
    onSelect: (RecordSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val segments = if (showTransfer) {
        RecordSegment.entries
    } else {
        listOf(RecordSegment.EXPENSE, RecordSegment.INCOME)
    }
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        segments.forEachIndexed { index, segment ->
            SegmentedButton(
                selected = selected == segment,
                onClick = { onSelect(segment) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = segments.size),
            ) { Text(segment.label) }
        }
    }
}
