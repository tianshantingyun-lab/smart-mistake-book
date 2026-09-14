package com.tingyun.smartmistakebook.core.data.tutor

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.LobbyImageDisclosureStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 消息附图的首次一次性说明：学生第一次发送带图消息时看到说明并确认，
 * 确认后长期记住，之后发送不再逐次询问（与"发起即发送"口径一致）。
 */
class DataStoreLobbyImageDisclosureStore(
    context: Context,
    scope: CoroutineScope,
) : LobbyImageDisclosureStore {
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler {
            emptyPreferences()
        },
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE)
        },
    )

    override val acknowledged: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values -> values[ACKNOWLEDGED] == true }
        .distinctUntilChanged()

    override suspend fun isAcknowledged(): Boolean = acknowledged.first()

    override suspend fun acknowledge() {
        dataStore.edit { values -> values[ACKNOWLEDGED] = true }
    }

    private companion object {
        const val DATASTORE_FILE = "lobby_image_disclosure"
        val ACKNOWLEDGED = booleanPreferencesKey("lobby_image_disclosure_acknowledged")
    }
}
