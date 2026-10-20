package com.nimo.ledger.ui.components

/**
 * D-3：把「错误文案」映射到具体字段的纯函数（单测友好）。
 *
 * 对话框校验失败时，除了在内容下方显示错误，还要把出错字段本身标红（`isError = true`）。
 * 这里只做「这条错误属于哪个字段」的判断，不改任何文案。
 */
internal fun fieldHasError(message: String?, keyword: String): Boolean =
    message != null && keyword in message