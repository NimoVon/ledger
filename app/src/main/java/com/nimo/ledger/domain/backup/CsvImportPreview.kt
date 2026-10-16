package com.nimo.ledger.domain.backup

/**
 * 导入前的预览（计划 line 312「导入前提供预览/确认」）。
 *
 * 只描述"如果此刻按下去会发生什么"，**一个字节都不写库**。字段与 [CsvImportOutcome] 一一对应：
 * 用户确认后真正导入的结果应当与它一致；中途本机数据被改过时以真正的导入结果为准（预览不是写入凭据）。
 */
data class CsvImportPreview(
    val inserted: CsvImportCounts = CsvImportCounts(),
    val updated: CsvImportCounts = CsvImportCounts(),
    val problems: List<CsvRowProblem> = emptyList(),
    val missingFiles: List<String> = emptyList(),
) {
    /** 确认后会写入的行数（新增 + 覆盖）。 */
    val rowsToWrite: Int get() = inserted.total + updated.total

    /** 会被跳过的行数（汇总行的行号是 0，不算行）。 */
    val skippedRows: Int get() = problems.count { it.line > 0 }

    val hasAnythingToWrite: Boolean get() = rowsToWrite > 0
}
