package com.nimo.ledger.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate

/**
 * 真机 1 万笔验收用的种子库生成器（计划 6.1「真机 1 万笔」的取数工具）。
 *
 * 产物落在 build/device-seed/ledger10k.db（build 目录不入 Git），设备端流程：
 *   ./gradlew.bat testDebugUnitTest --tests "*DeviceSeedGenerator*"
 *   adb push app/build/device-seed/ledger10k.db /data/local/tmp/ledger10k.db
 *   adb shell run-as com.nimo.ledger cp /data/local/tmp/ledger10k.db databases/ledger.db
 *
 * 数据构成：2 账户 / 6 分类（含父子）/ 2 预算 / 10000 笔交易，
 * 日期分布在 2026-06-01 ~ 2026-10-16，当月约 1160 笔——正好压「1 万笔下的月报」。
 */
@RunWith(RobolectricTestRunner::class)
class DeviceSeedGenerator {

    @Test
    fun writeTenThousandRowDatabaseForDeviceRun() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .allowMainThreadQueries()
            .build()
        val raw = db.openHelper.writableDatabase
        SchemaHardening.apply(raw)

        raw.beginTransaction()
        try {
            raw.execSQL(
                "INSERT INTO accounts (name, type, initialBalance, includeInTotal, isArchived, sortOrder, createdAt, updatedAt) " +
                    "VALUES ('银行', 'DEBIT', 5000000, 1, 0, 0, 0, 0)",
            )
            raw.execSQL(
                "INSERT INTO accounts (name, type, initialBalance, includeInTotal, isArchived, sortOrder, createdAt, updatedAt) " +
                    "VALUES ('现金', 'CASH', 50000, 1, 0, 0, 0, 0)",
            )
            raw.execSQL("INSERT INTO categories (name, kind, parentId, isArchived, sortOrder) VALUES ('餐饮', 'EXPENSE', NULL, 0, 0)")
            raw.execSQL("INSERT INTO categories (name, kind, parentId, isArchived, sortOrder) VALUES ('工资', 'INCOME', NULL, 0, 0)")
            raw.execSQL("INSERT INTO categories (name, kind, parentId, isArchived, sortOrder) VALUES ('早餐', 'EXPENSE', 1, 0, 0)")
            raw.execSQL("INSERT INTO categories (name, kind, parentId, isArchived, sortOrder) VALUES ('交通', 'EXPENSE', NULL, 0, 0)")
            raw.execSQL("INSERT INTO categories (name, kind, parentId, isArchived, sortOrder) VALUES ('购物', 'EXPENSE', NULL, 0, 0)")
            raw.execSQL("INSERT INTO categories (name, kind, parentId, isArchived, sortOrder) VALUES ('奖金', 'INCOME', NULL, 0, 0)")

            val base = LocalDate.of(2026, 6, 1)
            for (i in 1..10_000) {
                val date = base.plusDays((i % 138).toLong())
                val type = when (i % 10) {
                    0 -> "INCOME"
                    1, 2 -> "TRANSFER"
                    else -> "EXPENSE"
                }
                raw.execSQL(
                    "INSERT INTO transactions (type, amount, accountId, toAccountId, categoryId, occurredOn, note, createdAt, updatedAt) " +
                        "VALUES (?, ?, 1, ?, ?, ?, ?, 0, 0)",
                    arrayOf<Any?>( 
                        type,
                        ((i % 500) + 1).toLong() * 100,
                        if (type == "TRANSFER") 2L else null,
                        when (type) {
                            "INCOME" -> if (i % 20 == 0) 6L else 2L
                            "EXPENSE" -> if (i % 3 == 0) 3L else if (i % 3 == 1) 1L else 4L
                            else -> null
                        },
                        date.toString(),
                        if (i % 7 == 0) "coffee" else null,
                    ),
                )
            }
            raw.execSQL("INSERT INTO budgets (month, categoryId, amount) VALUES ('2026-10', NULL, 500000)")
            raw.execSQL("INSERT INTO budgets (month, categoryId, amount) VALUES ('2026-10', 1, 80000)")
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }

        assertEquals(10_000, db.transactionDao().count())
        db.close()

        val source = context.getDatabasePath(AppDatabase.NAME)
        assertTrue("种子库未生成: " + source, source.exists())
        val outDir = File("build/device-seed").apply { mkdirs() }
        val target = File(outDir, "ledger10k.db")
        source.copyTo(target, overwrite = true)
        // WAL 若未在关闭时合并，一起复制，保证设备端拿到的是一致的一份库
        for (suffix in listOf("-wal", "-shm")) {
            val extra = File(source.path + suffix)
            if (extra.exists()) extra.copyTo(File(target.path + suffix), overwrite = true)
        }

        // 自检：复制出来的这份必须能被独立打开且行数正确（避免推上设备才发现是半截库）
        val check = SQLiteDatabase.openDatabase(target.path, null, SQLiteDatabase.OPEN_READONLY)
        val rows = check.rawQuery("SELECT COUNT(*) FROM transactions", null).use { it.moveToFirst(); it.getLong(0) }
        val inOctober = check
            .rawQuery("SELECT COUNT(*) FROM transactions WHERE occurredOn >= '2026-10-01' AND occurredOn < '2026-11-01'", null)
            .use { it.moveToFirst(); it.getLong(0) }
        check.close()
        assertEquals(10_000L, rows)
        assertTrue("当月样本太少，压不出月报负载: " + inOctober, inOctober >= 900)
        println("DEVICE-SEED: file=" + target.absolutePath + " bytes=" + target.length() + " rows=" + rows + " octoberRows=" + inOctober)
    }
}
