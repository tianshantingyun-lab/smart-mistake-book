package com.tingyun.smartmistakebook.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.tingyun.smartmistakebook.core.domain.TutorExplanationModeSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorSettingsRepository
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
    fun legacyRepositoryImplementationAdaptsModeToVersionZeroSnapshot() = runBlocking {
        val repository = LegacyTutorSettingsRepository(TutorExplanationMode.GUIDED)

        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.GUIDED, 0L),
            repository.modeSnapshot.first(),
        )
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.GUIDED, 0L),
            repository.currentModeSnapshot(),
        )
    }

    @Test
    fun unknownAndOrphanModesPreserveNonNegativeVersionAndAreRepaired() = runBlocking {
        val store = MemoryPreferencesDataStore()
        store.updateData {
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_KEY to "FUTURE_MODE",
                DataStoreTutorSettingsRepository.MODE_VERSION_KEY to 42L,
            )
        }
        val repository = DataStoreTutorSettingsRepository(store)

        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 42L),
            repository.currentModeSnapshot(),
        )
        repository.setMode(TutorExplanationMode.DIRECT)
        assertEquals(
            TutorExplanationMode.DIRECT.name,
            store.data.first()[DataStoreTutorSettingsRepository.MODE_KEY],
        )
        assertEquals(
            42L,
            store.data.first()[DataStoreTutorSettingsRepository.MODE_VERSION_KEY],
        )

        store.updateData {
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_VERSION_KEY to 73L,
            )
        }
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 73L),
            repository.currentModeSnapshot(),
        )
        repository.setMode(TutorExplanationMode.DIRECT)
        assertEquals(
            TutorExplanationMode.DIRECT.name,
            store.data.first()[DataStoreTutorSettingsRepository.MODE_KEY],
        )
        assertEquals(
            73L,
            store.data.first()[DataStoreTutorSettingsRepository.MODE_VERSION_KEY],
        )
    }

    @Test
    fun negativeVersionFailsClosedAndCannotBeReusedForModeChange() = runBlocking {
        val store = MemoryPreferencesDataStore()
        store.updateData {
            mutablePreferencesOf(
                DataStoreTutorSettingsRepository.MODE_KEY to TutorExplanationMode.GUIDED.name,
                DataStoreTutorSettingsRepository.MODE_VERSION_KEY to -1L,
            )
        }
        val repository = DataStoreTutorSettingsRepository(store)

        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, Long.MAX_VALUE),
            repository.currentModeSnapshot(),
        )
        repository.setMode(TutorExplanationMode.DIRECT)
        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, Long.MAX_VALUE),
            repository.currentModeSnapshot(),
        )
        assertEquals(
            TutorExplanationMode.DIRECT.name,
            store.data.first()[DataStoreTutorSettingsRepository.MODE_KEY],
        )
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
    fun concurrentSameTargetSetModeAdvancesVersionOnlyOnce() = runBlocking {
        val store = MemoryPreferencesDataStore()
        val repository = DataStoreTutorSettingsRepository(store)
        val start = CompletableDeferred<Unit>()

        val requests = List(100) {
            async(Dispatchers.Default) {
                start.await()
                repository.setMode(TutorExplanationMode.GUIDED)
            }
        }
        start.complete(Unit)
        requests.awaitAll()

        assertEquals(
            TutorExplanationModeSnapshot(TutorExplanationMode.GUIDED, 1L),
            repository.currentModeSnapshot(),
        )
    }

    private class MemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val updateMutex = Mutex()

        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = updateMutex.withLock {
            val updated = transform(state.value)
            state.value = updated
            updated
        }
    }

    private class LegacyTutorSettingsRepository(
        initialMode: TutorExplanationMode,
    ) : TutorSettingsRepository {
        private val mutableMode = MutableStateFlow(initialMode)
        override val mode: Flow<TutorExplanationMode> = mutableMode

        override suspend fun currentMode(): TutorExplanationMode = mutableMode.value

        override suspend fun setMode(mode: TutorExplanationMode) {
            mutableMode.value = mode
        }
    }
}
