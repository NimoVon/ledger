package com.nimo.bookkeeping.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 数据库层硬约束。Room 的 @Entity 不支持 CHECK 约束与部分唯一索引，
 * 因此用触发器 + 部分索引实现（计划 V2.2 评审补遗 #1 #2）。
 *
 * 全部语句幂等（IF NOT EXISTS）：
 * - onCreate 后首次创建；
 * - 未来每个 Migration 结束时必须再次调用 SchemaHardening.apply(db)。
 */
object SchemaHardening {

    fun apply(db: SupportSQLiteDatabase) {
        // 同月只允许一条总预算（UNIQUE(month, category_id) 对 NULL 失效的兜底）
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_budget_month_total ON budgets(month) WHERE categoryId IS NULL",
        )
        db.execSQL(TX_INSERT_TRIGGER)
        db.execSQL(TX_UPDATE_TRIGGER)
        db.execSQL(BUDGET_INSERT_TRIGGER)
        db.execSQL(BUDGET_UPDATE_TRIGGER)
    }

    private const val TX_RULES = """
        SELECT CASE WHEN NEW."type" IN ('EXPENSE','INCOME','TRANSFER') AND NEW."amount" <= 0
            THEN RAISE(ABORT, 'tx: EXPENSE/INCOME/TRANSFER amount must be > 0') END;
        SELECT CASE WHEN NEW."type" = 'BALANCE_ADJUST' AND NEW."amount" = 0
            THEN RAISE(ABORT, 'tx: BALANCE_ADJUST amount must not be 0') END;
        SELECT CASE WHEN NEW."type" = 'TRANSFER' AND
            (NEW."accountId" IS NULL OR NEW."toAccountId" IS NULL OR NEW."accountId" = NEW."toAccountId")
            THEN RAISE(ABORT, 'tx: TRANSFER requires two different accounts') END;
        SELECT CASE WHEN NEW."type" IN ('EXPENSE','INCOME') AND NEW."accountId" IS NULL
            THEN RAISE(ABORT, 'tx: accountId required for EXPENSE/INCOME') END;
        SELECT CASE WHEN NEW."type" NOT IN ('TRANSFER','BALANCE_ADJUST') AND NEW."categoryId" IS NULL
            THEN RAISE(ABORT, 'tx: categoryId required for EXPENSE/INCOME') END;
        SELECT CASE WHEN NEW."type" IN ('TRANSFER','BALANCE_ADJUST') AND NEW."categoryId" IS NOT NULL
            THEN RAISE(ABORT, 'tx: TRANSFER/BALANCE_ADJUST must not have category') END;
    """

    private const val TX_INSERT_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS trg_tx_validate_insert
        BEFORE INSERT ON transactions
        BEGIN
            $TX_RULES
        END
    """

    private const val TX_UPDATE_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS trg_tx_validate_update
        BEFORE UPDATE ON transactions
        BEGIN
            $TX_RULES
        END
    """

    private const val BUDGET_INSERT_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS trg_budget_validate_insert
        BEFORE INSERT ON budgets
        BEGIN
            SELECT CASE WHEN NEW."amount" <= 0
                THEN RAISE(ABORT, 'budget: amount must be > 0') END;
        END
    """

    private const val BUDGET_UPDATE_TRIGGER = """
        CREATE TRIGGER IF NOT EXISTS trg_budget_validate_update
        BEFORE UPDATE ON budgets
        BEGIN
            SELECT CASE WHEN NEW."amount" <= 0
                THEN RAISE(ABORT, 'budget: amount must be > 0') END;
        END
    """
}
