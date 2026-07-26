package com.tingyun.smartmistakebook.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DataStoreTutorSettingsRepositoryTest {
    @Test
    fun modeDefaultsToDirectAndPersistsGuidedAcrossRepositoryInstances() = runBlocking {
        val store = MemoryPreferencesDataStore()
        val first = DataStoreTutorSettingsRepository(store)

        assertEquals(TutorExplanationMode.DIRECT, first.mode.first())
        first.setMode(TutorExplanationMode.GUIDED)

        val reopened = DataStoreTutorSettingsRepository(store)
        assertEquals(TutorExplanationMode.GUIDED, reopened.currentMode())
    }

    @Test
    fun unknownStoredModeFallsBackToDirect() = runBlocking {
        val store = MemoryPreferencesDataStore()
        store.updateData { values ->
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_KEY to "FUTURE_MODE",
            )
        }

        assertEquals(
            TutorExplanationMode.DIRECT,
            DataStoreTutorSettingsRepository(store).currentMode(),
        )
    }

    private class MemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }
}
