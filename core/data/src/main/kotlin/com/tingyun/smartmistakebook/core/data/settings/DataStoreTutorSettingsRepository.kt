package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.TutorSettingsRepository
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

    override val mode: Flow<TutorExplanationMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values -> values[MODE_KEY].toExplanationMode() }
        .distinctUntilChanged()

    override suspend fun currentMode(): TutorExplanationMode = mode.first()

    override suspend fun setMode(mode: TutorExplanationMode) {
        dataStore.edit { values -> values[MODE_KEY] = mode.name }
    }

    private fun String?.toExplanationMode(): TutorExplanationMode =
        this?.let { stored -> runCatching { TutorExplanationMode.valueOf(stored) }.getOrNull() }
            ?: TutorExplanationMode.DIRECT

    internal companion object {
        const val DATASTORE_FILE = "tutor_settings.preferences_pb"
        val MODE_KEY = stringPreferencesKey("explanation_mode")
    }
}
