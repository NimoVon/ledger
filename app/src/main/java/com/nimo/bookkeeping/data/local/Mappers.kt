package com.nimo.bookkeeping.data.local

import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity
import com.nimo.bookkeeping.domain.model.AccountBalanceInput
import com.nimo.bookkeeping.domain.model.CategoryInfo
import com.nimo.bookkeeping.domain.model.TransactionInput
import com.nimo.bookkeeping.domain.model.TransactionRecord
import java.time.LocalDate

/** Entity <-> 领域模型映射（data → domain 单向） */

fun TransactionInput.toEntity(now: Long): TransactionEntity = TransactionEntity(
    type = type,
    amount = amountCents,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    occurredOn = occurredOn,
    note = note?.trim()?.ifEmpty { null },
    createdAt = now,
    updatedAt = now,
)

fun TransactionEntity.toRecord(): TransactionRecord = TransactionRecord(
    id = id,
    type = type,
    amountCents = amount,
    accountId = accountId,
    toAccountId = toAccountId,
    categoryId = categoryId,
    occurredOn = LocalDate.parse(occurredOn),
    note = note,
)

/**
 * 容错版 [toRecord]：occurredOn 不是合法 ISO 日期时返回 null，而不是抛异常。
 * 统计、合计、余额这类"必须活着"的流一律用它——裸 [toRecord] 会在 LocalDate.parse 抛异常，
 * 把上层的 combine/stateIn 一起带走（流水页曾因此永远停在加载态）。
 */
fun TransactionEntity.toRecordOrNull(): TransactionRecord? =
    runCatching { toRecord() }.getOrNull()

fun AccountEntity.toBalanceInput(): AccountBalanceInput =
    AccountBalanceInput(id = id, initialBalanceCents = initialBalance, includeInTotal = includeInTotal)

fun CategoryEntity.toInfo(): CategoryInfo =
    CategoryInfo(id = id, kind = kind, parentId = parentId, name = name)
