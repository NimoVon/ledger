package com.nimo.ledger.ui.theme

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 外观偏好（F12）的落盘：真 DataStore 文件（临时目录），
 * 重点是「写一次 → 重启还在」与「盘上是垃圾值 / 文件读坏都退回跟随系统，不许崩」。
 */
@RunWith(RobolectricTestRunner::class)
class ThemePreferenceStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope(): CoroutineScope =
        CoroutineScope(Job() + Dispatchers.IO).also { scopes += it }

    /** 每次都是一块全新的 DataStore；同一文件上不许并存两个实例（DataStore 会主动报错） */
    private fun dataStoreOn(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = newScope()) { file }

    private val storeFile: File get() = File(tempFolder.root, "appearance.preferences_pb")

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun defaultsToFollowingTheSystem_whenNothingWasEverSaved() = runTest {
        val store = ThemePreferenceStore(dataStoreOn(storeFile))
        assertEquals(ThemePreference.SYSTEM, store.read())
        assertEquals(ThemePreference.SYSTEM, store.preference.first())
    }

    @Test
    fun writeThenRead_roundTrips() = runTest {
        val store = ThemePreferenceStore(dataStoreOn(storeFile))
        ThemePreference.entries.forEach { preference ->
            store.write(preference)
            assertEquals(preference, store.read())
        }
    }

    @Test
    fun theObservedFlowReflectsEveryWrite() = runTest {
        val store = ThemePreferenceStore(dataStoreOn(storeFile))
        assertEquals(ThemePreference.SYSTEM, store.preference.first())

        store.write(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, store.preference.first())

        store.write(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, store.preference.first())
    }

    @Test
    fun preferenceSurvivesARestart() = runTest {
        val firstScope = newScope()
        val first = PreferenceDataStoreFactory.create(scope = firstScope) { storeFile }
        ThemePreferenceStore(first).write(ThemePreference.DARK)
        // 同一个文件上不许有两个 DataStore 同时活着：先关掉再当作"重启"
        firstScope.cancel()
        firstScope.coroutineContext[Job]?.join()

        // 重新建一块 DataStore 读同一个文件 = 重启应用：内存里什么都没了，只认盘
        val afterRestart = ThemePreferenceStore(dataStoreOn(storeFile))
        assertEquals(ThemePreference.DARK, afterRestart.read())
    }

    @Test
    fun unknownStoredValue_fallsBackToFollowingTheSystem() = runTest {
        val dataStore = dataStoreOn(storeFile)
        dataStore.edit { prefs -> prefs[stringPreferencesKey(ThemePreferenceStore.KEY)] = "midnight" }
        assertEquals(ThemePreference.SYSTEM, ThemePreferenceStore(dataStore).read())
    }

    @Test
    fun unrelatedKeysInTheSameStore_surviveUntouched() = runTest {
        val dataStore = dataStoreOn(storeFile)
        val futureKey = stringPreferencesKey("future_setting")
        dataStore.edit { prefs -> prefs[futureKey] = "x" }

        val store = ThemePreferenceStore(dataStore)
        assertEquals(ThemePreference.SYSTEM, store.read())
        store.write(ThemePreference.DARK)

        // 只动自己那个键，同一个偏好文件里别人的键原样保留
        assertEquals("x", dataStore.data.first()[futureKey])
        assertEquals(
            "dark",
            dataStore.data.first()[stringPreferencesKey(ThemePreferenceStore.KEY)],
        )
    }
}
