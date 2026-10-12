package com.nimo.bookkeeping.ui.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nimo.bookkeeping.data.repository.LedgerRepository
import com.nimo.bookkeeping.domain.model.TransactionType
import com.nimo.bookkeeping.domain.validate.TransactionValidator
import com.nimo.bookkeeping.ui.LedgerIcons
import com.nimo.bookkeeping.ui.components.EmptyState
import com.nimo.bookkeeping.util.Dates

/**
 * 记一笔（F03，仅支出/收入）。
 *
 * 从任意顶层页面（明细/统计/设置）的「记一笔」按钮唤起，冷启动到保存 ≤ 3 次点击：
 * 点按钮 → 输入金额 → 点保存（账户默认选中第一个可用账户）。
 * 转账（F04）与余额校准（P1）不走本页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecordScreen(
    repository: LedgerRepository,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val viewModel: RecordViewModel = viewModel(factory = RecordViewModel.factory(repository))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showDatePicker by remember { mutableStateOf(false) }

    // 保存成功 → 通知页面返回（先消费信号，避免重组时重复触发）
    LaunchedEffect(state.savedTransactionId) {
        val savedId = state.savedTransactionId ?: return@LaunchedEffect
        viewModel.consumeSaved()
        onSaved(savedId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记一笔") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isSaving) {
                        Icon(imageVector = LedgerIcons.Back, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!state.isLoading && !state.hasNoAccount && state.loadErrorMessage == null) {
                        TextButton(onClick = { viewModel.save() }, enabled = !state.isSaving) {
                            Text(if (state.isSaving) "保存中…" else "保存")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when {
            state.isLoading -> Box(contentModifier, Alignment.Center) { CircularProgressIndicator() }

            state.loadErrorMessage != null -> EmptyState(
                title = "加载失败",
                message = state.loadErrorMessage.orEmpty(),
                modifier = contentModifier,
            )

            // 没有可用账户就无法记账：明确指路，不给一张填了也存不进去的表单
            state.hasNoAccount -> EmptyState(
                title = "还没有可用账户",
                message = "先到「设置 → 账户管理」新建一个账户，才能记一笔。\n（已归档的账户不会出现在这里）",
                modifier = contentModifier,
            )

            else -> RecordFormBody(
                state = state,
                viewModel = viewModel,
                onPickDate = { showDatePicker = true },
                modifier = contentModifier,
            )
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecordFormBody(
    state: RecordUiState,
    viewModel: RecordViewModel,
    onPickDate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val amountFocus = remember { FocusRequester() }
    // 进页面就聚焦金额："记一笔"的主体就是输金额，省一次点击
    LaunchedEffect(Unit) { amountFocus.requestFocus() }

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

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentType.entries.forEachIndexed { index, segment ->
                SegmentedButton(
                    selected = state.form.type == segment.type,
                    onClick = { viewModel.setType(segment.type) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SegmentType.entries.size,
                    ),
                ) { Text(segment.label) }
            }
        }

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

        FieldSection(title = "分类") {
            if (state.categories.isEmpty()) {
                Text(
                    text = "这个方向下还没有可用分类（停用的分类不会出现在这里）",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.categories.forEach { category ->
                        FilterChip(
                            selected = state.form.categoryId == category.id,
                            onClick = { viewModel.setCategory(category.id) },
                            label = {
                                Text(
                                    listOfNotNull(category.icon, category.name).joinToString(" "),
                                )
                            },
                        )
                    }
                }
            }
        }

        FieldSection(title = "账户") {
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

        FieldSection(title = "日期") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onPickDate) {
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
    }
}

@Composable
private fun FieldSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        content()
    }
}

/** 收支切换的两个分段（顺序即按钮顺序） */
private enum class SegmentType(val type: TransactionType, val label: String) {
    EXPENSE(TransactionType.EXPENSE, "支出"),
    INCOME(TransactionType.INCOME, "收入"),
}
