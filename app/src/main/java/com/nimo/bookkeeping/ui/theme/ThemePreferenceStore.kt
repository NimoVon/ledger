package com.nimo.bookkeeping.ui.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 外观偏好的落盘（F12）。
 *
 * 落点按计划 :236「简单偏好 → DataStore Preferences（主题、首页偏好、默认账户等非核心数据）」，
 * 用应用私有的 DataStore 文件，**不**进 Room：换个主题不值得动 schema、更不值得一次迁移。
 * 也刻意不做「未读到时先渲染一屏」的猜测——[preference] 是冷流，界面拿到真值再画。
 */
class ThemePreferenceStore(private val dataStore: DataStore<Preferences>) {

    /** 供 Compose 观察当前外观偏好；DataStore 的 data 流会先发当前值再发每次写入后的值 */
    val preference: Flow<ThemePreference> = dataStore.data
        // 偏好文件读坏了（IOException）不是账本数据损坏：退回空偏好 = 跟随系统，绝不因此崩
        .catch { cause -> if (cause is IOException) emit(emptyPreferences()) else throw cause }
        .map { prefs -> ThemePreference.fromStorage(prefs[KEY_PREFERENCE]) }

    /** 直接读一次（不经状态缓存）：构造时初始化与测试对账用 */
    suspend fun read(): ThemePreference = preference.first()

    /** 写盘。DataStore 的 edit 是事务性的：写一半断电也不会留下半个值。 */
    suspend fun write(preference: ThemePreference) {
        dataStore.edit { prefs -> prefs[KEY_PREFERENCE] = preference.storageValue }
    }

    companion object {
        /** 外观偏好单独一个 DataStore 文件，与账本数据库、自动备份互不牵连 */
        const val FILE_NAME: String = "ledger-appearance"

        /** 主题模式键 */
        const val KEY: String = "theme_mode"

        private val KEY_PREFERENCE = stringPreferencesKey(KEY)

        /** 进程内单例（preferencesDataStore 按名字缓存），必须传 Application Context */
        fun from(context: Context): ThemePreferenceStore =
            ThemePreferenceStore(context.applicationContext.appearanceDataStore)
    }
}

/** 主题偏好专用 DataStore；名字即文件名（files/datastore/ledger-appearance.preferences_pb） */
private val Context.appearanceDataStore: DataStore<Preferences> by
    preferencesDataStore(name = ThemePreferenceStore.FILE_NAME)
