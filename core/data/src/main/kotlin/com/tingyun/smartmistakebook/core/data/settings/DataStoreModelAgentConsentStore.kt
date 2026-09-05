package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.ModelAgentConsentStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [ModelAgentConsentStore]. Defaults to ON so a configured model keeps
 * egressing without per-item confirmations (the pre-toggle behavior); the user opts out.
 */
class DataStoreModelAgentConsentStore(
    context: Context,
    scope: CoroutineScope,
) : ModelAgentConsentStore {
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE)
        },
    )

    override val consentEnabled: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values -> values[CONSENT_ENABLED] ?: DEFAULT_CONSENT_ENABLED }
        .distinctUntilChanged()

    override suspend fun current(): Boolean = consentEnabled.first()

    override suspend fun setConsentEnabled(enabled: Boolean) {
        dataStore.edit { values -> values[CONSENT_ENABLED] = enabled }
    }

    private companion object {
        const val DATASTORE_FILE = "model_agent_consent.preferences_pb"
        const val DEFAULT_CONSENT_ENABLED = true
        val CONSENT_ENABLED = booleanPreferencesKey("consent_enabled")
    }
}
