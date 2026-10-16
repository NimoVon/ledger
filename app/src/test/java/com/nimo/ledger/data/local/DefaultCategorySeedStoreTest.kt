package com.nimo.ledger.data.local

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 「预置分类已播种」标记（附.4）的落盘：默认「没播过」、播完能记住、重启还在。
 * 读坏文件那一支由 [DefaultCategorySeederTest] 的删光场景从行为上兜住（见 store 的注释）。
 */
@RunWith(RobolectricTestRunner::class)
class DefaultCategorySeedStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    private fun newScope(): CoroutineScope =
        CoroutineScope(Job() + Dispatchers.IO).also { scopes += it }

    /** 每次一块全新的 DataStore；同一文件上不许并存两个实例（DataStore 会主动报错） */
    private fun dataStoreOn(file: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = newScope()) { file }

    private val storeFile: File get() = File(tempFolder.root, "ledger-category-seed.preferences_pb")

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    @Test
    fun aFreshInstallHasNeverSeeded() = runTest {
        val store = DefaultCategorySeedStore(dataStoreOn(storeFile))

        assertFalse(store.hasSeeded())
    }

    @Test
    fun markSeeded_isRemembered() = runTest {
        val store = DefaultCategorySeedStore(dataStoreOn(storeFile))

        store.markSeeded()

        assertTrue(store.hasSeeded())
    }

    @Test
    fun theSeedFlagSurvivesARestart() = runTest {
        val firstScope = newScope()
        DefaultCategorySeedStore(PreferenceDataStoreFactory.create(scope = firstScope) { storeFile })
            .markSeeded()
        // 同一个文件上不许有两个 DataStore 同时活着：先关掉再当作"重启"
        firstScope.cancel()
        firstScope.coroutineContext[Job]?.join()

        assertTrue(DefaultCategorySeedStore(dataStoreOn(storeFile)).hasSeeded())
    }

    @Test
    fun markingSeededTwiceIsHarmless() = runTest {
        val store = DefaultCategorySeedStore(dataStoreOn(storeFile))

        store.markSeeded()
        store.markSeeded()

        assertTrue(store.hasSeeded())
    }

    @Test
    fun unrelatedKeysInTheSameStore_stayUntouched() = runTest {
        val dataStore = dataStoreOn(storeFile)
        val futureKey = stringPreferencesKey("future_setting")
        dataStore.edit { prefs -> prefs[futureKey] = "x" }

        DefaultCategorySeedStore(dataStore).markSeeded()

        // 只写自己那个键，同一个偏好文件里别人的键原样保留
        assertEquals("x", dataStore.data.first()[futureKey])
        assertTrue(DefaultCategorySeedStore(dataStore).hasSeeded())
    }
}
