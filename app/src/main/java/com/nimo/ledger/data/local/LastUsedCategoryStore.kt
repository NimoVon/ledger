package com.nimo.ledger.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 上次记账用过的分类 + 那天（epoch day）：进「记一笔」时按当前方向预选，
 * 并在分类区给一句「2 天前用过「餐饮」」（用户批准的产品决定，附.1 R4-P3）。
 *
 * 存"哪天"而不是"多久以前"，是因为相对时间一落盘就过期了——今天存的"1 天前"，
 * 明天看就错了。读出来再和今天相减，才算得准。
 */
data class LastUsedCategory(val categoryId: Long, val usedOnEpochDay: Long)

/**
 * 抽一层接口：单测注入假实现，不需要真的碰 DataStore；
 * 生产实现落 Preferences（同 F12 外观偏好、R3 播种标记），不为一个偏好动 Room schema。
 */
interface LastUsedCategoryStore {
    /** 没记过（或读坏了）返回 null：调用方据此不预选、也不提示 */
    suspend fun load(): LastUsedCategory?

    suspend fun save(lastUsed: LastUsedCategory)
}

/** DataStore 实现：文件损坏按"没记过"处理，绝不能因为偏好坏了让记一笔开不了 */
class DataStoreLastUsedCategoryStore(
    private val dataStore: DataStore<Preferences>,
) : LastUsedCategoryStore {

    override suspend fun load(): LastUsedCategory? {
        val prefs = dataStore.data
            .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
            .first()
        val categoryId = prefs[KEY_CATEGORY_ID] ?: return null
        val usedOnEpochDay = prefs[KEY_USED_ON_EPOCH_DAY] ?: return null
        return LastUsedCategory(categoryId = categoryId, usedOnEpochDay = usedOnEpochDay)
    }

    override suspend fun save(lastUsed: LastUsedCategory) {
        dataStore.edit { prefs ->
            prefs[KEY_CATEGORY_ID] = lastUsed.categoryId
            prefs[KEY_USED_ON_EPOCH_DAY] = lastUsed.usedOnEpochDay
        }
    }

    companion object {
        /** 单独一个文件，与账本数据库、外观偏好、播种标记互不牵连 */
        const val FILE_NAME: String = "ledger-last-category"

        private val KEY_CATEGORY_ID = longPreferencesKey("last_category_id")
        private val KEY_USED_ON_EPOCH_DAY = longPreferencesKey("last_category_used_epoch_day")

        /** 进程内单例（preferencesDataStore 按名字缓存），必须传 Application Context */
        fun from(context: Context): DataStoreLastUsedCategoryStore =
            DataStoreLastUsedCategoryStore(context.applicationContext.lastCategoryDataStore)
    }
}

private val Context.lastCategoryDataStore: DataStore<Preferences> by
    preferencesDataStore(name = DataStoreLastUsedCategoryStore.FILE_NAME)
