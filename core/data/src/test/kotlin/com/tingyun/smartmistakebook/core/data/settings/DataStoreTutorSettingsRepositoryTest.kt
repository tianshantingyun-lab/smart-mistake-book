package com.tingyun.smartmistakebook.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.tingyun.smartmistakebook.core.domain.TutorExplanationModeSnapshot
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DataStoreTutorSettingsRepositoryTest {
    @Test
    fun legacyModeWithoutVersionIsReadAtVersionZero() = runBlocking {
        val store = MemoryPreferencesDataStore()
        store.updateData {
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_KEY to TutorExplanationMode.GUIDED.name,
            )
        }

        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.GUIDED, 0L),
            DataStoreTutorSettingsRepository(store).currentModeSnapshot(),
        )
    }

    @Test
    fun realModeChangesAdvanceMonotonicallyAndSameModeIsIdempotent() = runBlocking {
        val store = MemoryPreferencesDataStore()
        val repository = DataStoreTutorSettingsRepository(store)

        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 0L),
            repository.modeSnapshot.first(),
        )
        repository.setMode(TutorExplanationMode.DIRECT)
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 0L),
            repository.currentModeSnapshot(),
        )
        repository.setMode(TutorExplanationMode.GUIDED)
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.GUIDED, 1L),
            repository.currentModeSnapshot(),
        )
        repository.setMode(TutorExplanationMode.GUIDED)
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.GUIDED, 1L),
            repository.currentModeSnapshot(),
        )
        repository.setMode(TutorExplanationMode.DIRECT)
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 2L),
            repository.currentModeSnapshot(),
        )
    }

    @Test
    fun modeAndVersionSurviveRepositoryReconstruction() = runBlocking {
        val store = MemoryPreferencesDataStore()
        DataStoreTutorSettingsRepository(store).apply {
            setMode(TutorExplanationMode.GUIDED)
            setMode(TutorExplanationMode.DIRECT)
        }

        val reopened = DataStoreTutorSettingsRepository(store)
        assertEquals(TutorExplanationMode.DIRECT, reopened.currentMode())
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 2L),
            reopened.currentModeSnapshot(),
        )
    }

    @Test
    fun corruptStoredSettingsFallBackToDirectAtVersionZero() = runBlocking {
        val store = MemoryPreferencesDataStore()
        store.updateData { values ->
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_KEY to "FUTURE_MODE",
                DataStoreTutorSettingsRepository.MODE_VERSION_KEY to 42L,
            )
        }

        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 0L),
            DataStoreTutorSettingsRepository(store).currentModeSnapshot(),
        )

        store.updateData {
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_KEY to TutorExplanationMode.GUIDED.name,
                DataStoreTutorSettingsRepository.MODE_VERSION_KEY to -1L,
            )
        }
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 0L),
            DataStoreTutorSettingsRepository(store).currentModeSnapshot(),
        )
    }

    @Test
    fun versionOverflowFailsWithoutChangingStoredMode() = runBlocking {
        val store = MemoryPreferencesDataStore()
        store.updateData {
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_KEY to TutorExplanationMode.DIRECT.name,
                DataStoreTutorSettingsRepository.MODE_VERSION_KEY to Long.MAX_VALUE,
            )
        }
        val repository = DataStoreTutorSettingsRepository(store)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.setMode(TutorExplanationMode.GUIDED)
            }
        }
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, Long.MAX_VALUE),
            repository.currentModeSnapshot(),
        )
    }

    @Test
    fun concurrentSetModeKeepsEverySerializedTransition() = runBlocking {
        val store = MemoryPreferencesDataStore()
        val repository = DataStoreTutorSettingsRepository(store)

        List(100) { index ->
            async {
                repository.setMode(
                    if (index % 2 == 0) TutorExplanationMode.GUIDED else TutorExplanationMode.DIRECT,
                )
            }
        }.awaitAll()

        val expectedTransitions = store.committedModes
            .fold(TutorExplanationMode.DIRECT to 0L) { (previous, transitions), committed ->
                committed to (transitions + if (committed == previous) 0L else 1L)
            }
            .second
        assertEquals(expectedTransitions, repository.currentModeSnapshot().modeVersion)
    }

    private class MemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val updateMutex = Mutex()
        val committedModes = mutableListOf<TutorExplanationMode>()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = updateMutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated[DataStoreTutorSettingsRepository.MODE_KEY]
                ?.let { stored -> runCatching { TutorExplanationMode.valueOf(stored) }.getOrNull() }
                ?.let(committedModes::add)
            updated
        }
    }
}
