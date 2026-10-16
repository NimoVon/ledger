package com.nimo.ledger.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * 「预置分类已播种」标记（计划附.4）。
 *
 * 没有它，用户把分类删光后下次冷启动会重新复活 18 个默认分类——那就不叫删除。
 * 落 DataStore（同 F12 的外观偏好）：一个布尔值不值得动 Room schema，更不值得一次迁移。
 */
class DefaultCategorySeedStore(private val dataStore: DataStore<Preferences>) {

    /**
     * 是否已经播种过。读坏了（IOException）按"没播种过"处理：宁可多播一次预置分类，
     * 也不要因为偏好文件损坏让新装的应用一个分类都没有。
     */
    suspend fun hasSeeded(): Boolean = dataStore.data
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .first()[KEY_SEEDED] ?: false

    /** 播种成功后打标记：此后分类表为空也不再自动播种（删光就是真删光） */
    suspend fun markSeeded() {
        dataStore.edit { prefs -> prefs[KEY_SEEDED] = true }
    }

    companion object {
        /** 单独一个 DataStore 文件，与账本数据库、外观偏好互不牵连 */
        const val FILE_NAME: String = "ledger-category-seed"

        private val KEY_SEEDED = booleanPreferencesKey("default_categories_seeded")

        /** 进程内单例（preferencesDataStore 按名字缓存），必须传 Application Context */
        fun from(context: Context): DefaultCategorySeedStore =
            DefaultCategorySeedStore(context.applicationContext.categorySeedDataStore)
    }
}

private val Context.categorySeedDataStore: DataStore<Preferences> by
    preferencesDataStore(name = DefaultCategorySeedStore.FILE_NAME)
