package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.TutorExplanationModeSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorSettingsRepository
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class DataStoreTutorSettingsRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) : TutorSettingsRepository {
    constructor(context: Context, scope: CoroutineScope) : this(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = scope,
            produceFile = {
                context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE)
            },
        ),
    )

    override val modeSnapshot: Flow<TutorExplanationModeSnapshot> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::snapshotFrom)
        .distinctUntilChanged()

    override suspend fun setMode(mode: TutorExplanationMode) {
        dataStore.edit { values ->
            val current = snapshotFrom(values)
            if (current.mode == mode) return@edit

            check(current.modeVersion < Long.MAX_VALUE) {
                "Tutor explanation mode version overflow"
            }
            values[MODE_KEY] = mode.name
            values[MODE_VERSION_KEY] = current.modeVersion + 1L
        }
    }

    private fun snapshotFrom(values: Preferences): TutorExplanationModeSnapshot {
        val storedMode = values[MODE_KEY]
        val storedVersion = values[MODE_VERSION_KEY]
        if (storedMode == null && storedVersion != null) return DEFAULT_SNAPSHOT
        val mode = storedMode
            ?.let { stored -> runCatching { TutorExplanationMode.valueOf(stored) }.getOrNull() }
            ?: if (storedMode == null) TutorExplanationMode.DIRECT else return DEFAULT_SNAPSHOT
        val version = storedVersion ?: 0L
        if (version < 0L) return DEFAULT_SNAPSHOT
        return TutorExplanationModeSnapshot(
            mode = mode,
            modeVersion = version,
        )
    }

    internal companion object {
        const val DATASTORE_FILE = "tutor_settings.preferences_pb"
        val MODE_KEY = stringPreferencesKey("explanation_mode")
        val MODE_VERSION_KEY = longPreferencesKey("explanation_mode_version")
        val DEFAULT_SNAPSHOT = TutorExplanationModeSnapshot(
            mode = TutorExplanationMode.DIRECT,
            modeVersion = 0L,
        )
    }
}
