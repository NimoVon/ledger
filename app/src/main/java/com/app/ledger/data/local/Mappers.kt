package com.app.ledger.data.local

import com.app.ledger.data.local.entity.AccountEntity
import com.app.ledger.data.local.entity.CategoryEntity
import com.app.ledger.data.local.entity.TransactionEntity
import com.app.ledger.domain.model.AccountBalanceInput
import com.app.ledger.domain.model.CategoryInfo
import com.app.ledger.domain.model.TransactionInput
import com.app.ledger.domain.model.TransactionRecord
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
)

fun AccountEntity.toBalanceInput(): AccountBalanceInput =
    AccountBalanceInput(id = id, initialBalanceCents = initialBalance, includeInTotal = includeInTotal)

fun CategoryEntity.toInfo(): CategoryInfo =
    CategoryInfo(id = id, kind = kind, parentId = parentId, name = name)
