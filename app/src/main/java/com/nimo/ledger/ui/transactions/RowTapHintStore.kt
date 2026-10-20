package com.nimo.ledger.ui.transactions

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * T-3：「点行=编辑」的一次性提示标记。
 *
 * 一个布尔值不值得动 Room schema，更不值得一次迁移——落 DataStore（同 F12 外观偏好、
 * 默认分类播种标记的做法）。跨进程只出现一次，点「知道了」后永久收起。
 */
class RowTapHintStore(private val dataStore: DataStore<Preferences>) {

    suspend fun hasShown(): Boolean = dataStore.data
        .catch { emit(emptyPreferences()) }
        .first()[KEY_SHOWN] ?: false

    suspend fun markShown() {
        dataStore.edit { prefs -> prefs[KEY_SHOWN] = true }
    }

    companion object {
        /** 单独一个 DataStore 文件，与账本数据库、外观偏好互不牵连 */
        private val KEY_SHOWN = booleanPreferencesKey("row_tap_hint_shown")

        fun from(context: Context): RowTapHintStore =
            RowTapHintStore(context.applicationContext.rowTapHintDataStore)
    }
}

private val Context.rowTapHintDataStore by preferencesDataStore(name = "row_tap_hint")
