package com.nimo.ledger.domain.backup

/** 一次 CSV 增量导入实际写了多少行：同 id 覆盖算 [CsvImportOutcome.updated]，新 id 追加算 [CsvImportOutcome.inserted]。 */
data class CsvImportCounts(
    val accounts: Int = 0,
    val categories: Int = 0,
    val transactions: Int = 0,
    val budgets: Int = 0,
) {
    val total: Int get() = accounts + categories + transactions + budgets
}

/**
 * CSV 增量导入的结果（F14）。
 *
 * [problems] 同时含解析层问题与导入层问题（引用不存在、预算键冲突等），每条都带文件名与物理行号，
 * 已按 [CsvImportParser.foldProblems] 折叠；[missingFiles] 是导入目录里没找到的文件（不算错误）。
 */
data class CsvImportOutcome(
    val inserted: CsvImportCounts = CsvImportCounts(),
    val updated: CsvImportCounts = CsvImportCounts(),
    val problems: List<CsvRowProblem> = emptyList(),
    val missingFiles: List<String> = emptyList(),
) {
    val writtenRows: Int get() = inserted.total + updated.total

    /** 被跳过的行数（汇总行的行号是 0，不算行）。 */
    val skippedRows: Int get() = problems.count { it.line > 0 }
}
