package com.nimo.bookkeeping.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nimo.bookkeeping.data.local.dao.AccountDao
import com.nimo.bookkeeping.data.local.dao.BudgetDao
import com.nimo.bookkeeping.data.local.dao.CategoryDao
import com.nimo.bookkeeping.data.local.dao.TransactionDao
import com.nimo.bookkeeping.data.local.entity.AccountEntity
import com.nimo.bookkeeping.data.local.entity.BudgetEntity
import com.nimo.bookkeeping.data.local.entity.CategoryEntity
import com.nimo.bookkeeping.data.local.entity.TransactionEntity

/**
 * Room 会自动将枚举字段按 name 存为 TEXT，无需 TypeConverter；
 * 触发器中的 'EXPENSE' 等字面量与枚举名一致。
 *
 * exportSchema = true：schema JSON 导出到 app/schemas 并入 Git（计划 4.2 规则 6）。
 */
@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        TransactionEntity::class,
        BudgetEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao

    companion object {
        const val NAME = "ledger.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addCallback(HardeningCallback)
                .build()

        private val HardeningCallback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                SchemaHardening.apply(db)
            }
        }
    }
}
