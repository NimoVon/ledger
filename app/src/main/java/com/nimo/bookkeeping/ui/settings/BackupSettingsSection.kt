package com.nimo.bookkeeping.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nimo.bookkeeping.data.backup.AutoBackupStore
import com.nimo.bookkeeping.data.backup.BackupFileIO
import com.nimo.bookkeeping.data.repository.BackupRepository
import com.nimo.bookkeeping.domain.backup.BackupCodec
import com.nimo.bookkeeping.domain.backup.BackupDecodeResult
import com.nimo.bookkeeping.domain.backup.BackupFile
import com.nimo.bookkeeping.ui.transactions.PendingTransactionDeletion
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的「备份与恢复」（F08c，计划 4.5/4.6）。
 *
 * 三条入口：导出 JSON（完整备份）、导出 CSV（4 个文件，Excel 直开）、从备份恢复（替换模式）。
 * 规则按计划执行：
 * - 全部走 SAF，只要用户给出的 URI，不假定有真实文件路径（4.6 文件权限）；
 * - 导出前先说明文件里有什么（4.5：导出前说明内容）；
 * - 恢复前说明"替换而不是追加"，并要求用户在弹窗里确认（4.6 恢复策略）；
 * - 恢复前先写自动备份，自动备份失败就中止、原数据不动（4.6 恢复前）。
 *
 * 这里只做"选位置 / 读字节 / 报告结果"，编解码与落库都在 [BackupRepository]，
 * 所以危险动作的顺序由可单测的那一层决定，UI 不参与判断。
 */
@Composable
fun BackupSettingsSection(
    backupRepository: BackupRepository,
    autoBackupStore: AutoBackupStore,
    pendingDeletion: PendingTransactionDeletion,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var explainExport by remember { mutableStateOf<ExportKind?>(null) }
    var restoreCandidate by remember { mutableStateOf<BackupFile?>(null) }
    var restoreCandidateName by remember { mutableStateOf("") }

    /** 导出 JSON：让用户选一个新文件（系统负责重名与覆盖确认），再写字节 */
    val jsonExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) {
            status = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            val result = runCatching {
                val file = backupRepository.exportBackupFile()
                writeBackupText(context, uri, BackupCodec.encode(file))
                file
            }
            busy = false
            result
                .onSuccess { file ->
                    status = "已导出 JSON 备份：" + counts(file)
                }
                .onFailure { errorText = "导出失败：" + describe(it) }
        }
    }

    /** 导出 CSV：让用户选一个目录，四个文件都写进去 */
    val csvExport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) {
            status = "已取消导出"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            val result = runCatching {
                val files = backupRepository.exportCsvFiles()
                writeCsvFiles(context, uri, files)
            }
            busy = false
            result
                .onSuccess { names -> status = "已导出 CSV：" + names.joinToString("、") }
                .onFailure { errorText = "导出失败：" + describe(it) }
        }
    }

    /** 从备份恢复：先读文件、再解码校验，校验不过连确认框都不弹 */
    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) {
            status = "已取消恢复"
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            busy = true
            val read = runCatching { readBackupText(context, uri) }
            busy = false
            read
                .onSuccess { text ->
                    when (val decoded = BackupCodec.decode(text)) {
                        is BackupDecodeResult.Success -> {
                            restoreCandidate = decoded.file
                            restoreCandidateName = uri.lastPathSegment?.substringAfterLast('/') ?: "所选文件"
                        }

                        is BackupDecodeResult.Failure -> {
                            errorText = "这个备份文件不能用：\n" + decoded.errors.joinToString("\n")
                        }
                    }
                }
                .onFailure { errorText = "读取失败：" + describe(it) }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BackupRow(
            title = "导出 JSON 完整备份",
            subtitle = "账户、分类、每笔交易、预算；可用来以后恢复",
            enabled = !busy,
        ) { explainExport = ExportKind.JSON }

        BackupRow(
            title = "导出 CSV（4 个文件）",
            subtitle = "accounts / categories / transactions / budgets，金额为元，Excel 可直接打开",
            enabled = !busy,
        ) { explainExport = ExportKind.CSV }

        BackupRow(
            title = "从备份恢复",
            subtitle = "用备份文件替换本机现有的全部数据（不是追加）",
            enabled = !busy,
        ) { restorePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

        Text(
            text = "恢复前会自动把当前数据备份到应用私有目录（" + autoBackupStore.directoryPath +
                "）；该目录随应用卸载一起删除，长期备份请用「导出 JSON」。",
            style = MaterialTheme.typography.bodySmall,
        )

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text(text = "处理中…", style = MaterialTheme.typography.bodyMedium)
            }
        }

        status?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
    }

    // 导出前说明内容（计划 4.5：导出文件可能包含完整财务隐私）
    explainExport?.let { kind ->
        AlertDialog(
            onDismissRequest = { explainExport = null },
            title = { Text(text = "导出前请知悉") },
            text = {
                Text(
                    text = when (kind) {
                        ExportKind.JSON ->
                            "JSON 是完整备份：账户余额、分类、每一笔交易（含金额、备注、日期）和预算全部在内。" +
                                "文件含完整财务隐私，请保存在你信任的位置。"
                        ExportKind.CSV ->
                            "将导出 4 个 CSV 文件：accounts.csv、categories.csv、transactions.csv、budgets.csv。" +
                                "金额写成元（固定两位小数），便于用 Excel 查看；同样包含完整财务隐私。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainExport = null
                    when (kind) {
                        ExportKind.JSON -> jsonExport.launch(defaultBackupFileName())
                        ExportKind.CSV -> csvExport.launch(null)
                    }
                }) { Text(text = "选择保存位置") }
            },
            dismissButton = {
                TextButton(onClick = { explainExport = null }) { Text(text = "取消") }
            },
        )
    }

    // 恢复确认：把"替换掉什么、替换成什么"说清楚，再让用户按下去
    restoreCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text(text = "恢复会替换当前全部数据") },
            text = {
                Text(
                    text = "备份文件：" + restoreCandidateName + "\n" +
                        "将写入：" + counts(candidate) + "\n\n" +
                        "这会「替换」本机现有的全部数据（不是追加，当前数据不会保留）。" +
                        "确定后先把当前数据写成自动备份；自动备份失败会中止恢复，原数据不动。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    restoreCandidate = null
                    scope.launch {
                        busy = true
                        // 待撤销的删除里那几笔 id 可能在新数据里属于另一笔交易：
                        // 恢复前整批取消倒计时，失败则把整笔重新排期（不丢用户那次删除）
                        val cancelled = pendingDeletion.cancelAll()
                        var autoBackupName: String? = null
                        val result = backupRepository.restore(candidate) { snapshot ->
                            autoBackupName = autoBackupStore.write(snapshot).name
                        }
                        if (result.isFailure) {
                            cancelled.forEach { pendingDeletion.schedule(it) }
                        }
                        busy = false
                        result
                            .onSuccess {
                                status = "已恢复：" + counts(candidate) +
                                    (autoBackupName?.let { "；恢复前的数据已自动备份为 " + it } ?: "")
                            }
                            .onFailure { errorText = "恢复失败：" + describe(it) + "\n当前数据未被改动。" }
                    }
                }) { Text(text = "替换并恢复") }
            },
            dismissButton = {
                TextButton(onClick = { restoreCandidate = null }) { Text(text = "取消") }
            },
        )
    }

    errorText?.let { message ->
        AlertDialog(
            onDismissRequest = { errorText = null },
            title = { Text(text = "没有完成") },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = { errorText = null }) { Text(text = "知道了") }
            },
        )
    }
}

/** 导出的两种格式（只影响说明文案与保存方式） */
private enum class ExportKind { JSON, CSV }

@Composable
private fun BackupRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

private fun counts(file: BackupFile): String =
    "账户 " + file.data.accounts.size +
        "、分类 " + file.data.categories.size +
        "、交易 " + file.data.transactions.size +
        "、预算 " + file.data.budgets.size

private fun defaultBackupFileName(): String =
    "ledger-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json"

/** 错误信息只用我们自己的文案 + 异常自带的短消息，绝不把文件内容带进日志/界面（计划 4.5） */
private fun describe(t: Throwable): String =
    t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName

private suspend fun writeBackupText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    val stream = context.contentResolver.openOutputStream(uri, "wt")
        ?: throw IOException("无法写入所选位置")
    stream.use { BackupFileIO.writeText(it, text) }
}

private suspend fun readBackupText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val stream = context.contentResolver.openInputStream(uri)
        ?: throw IOException("无法读取所选文件")
    stream.use { BackupFileIO.readText(it) }
}

/** 逐个创建 CSV：SAF 下每个文件都要拿到 URI 才能写，不能按路径写 */
private suspend fun writeCsvFiles(context: Context, treeUri: Uri, files: Map<String, String>): List<String> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        // 真机踩坑：createDocument 要的是「父文档」URI，直接把 ACTION_OPEN_DOCUMENT_TREE 拿到的
        // tree URI 塞进去，系统会抛 Invalid URI；必须先用 getTreeDocumentId 把它转成文档 URI。
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        files.map { (name, content) ->
            val document = try {
                DocumentsContract.createDocument(resolver, parent, "text/csv", name)
            } catch (t: Throwable) {
                if (t.looksLikeAlreadyExists()) {
                    throw IOException("这个文件夹里已经有 " + name + " 了，请换一个文件夹，或先删掉旧文件再导出")
                }
                throw t
            } ?: throw IOException("无法创建文件：" + name)
            val stream = resolver.openOutputStream(document, "wt")
                ?: throw IOException("无法写入文件：" + name)
            stream.use { BackupFileIO.writeText(it, content) }
            name
        }
    }

/** 同名文件在部分 ROM 上是 FileAlreadyExistsException，部分只是 ErrnoException/IllegalStateException，统一认一下 */
private fun Throwable.looksLikeAlreadyExists(): Boolean =
    this is java.nio.file.FileAlreadyExistsException ||
        javaClass.simpleName.contains("FileAlreadyExists") ||
        message?.contains("already exists", ignoreCase = true) == true
