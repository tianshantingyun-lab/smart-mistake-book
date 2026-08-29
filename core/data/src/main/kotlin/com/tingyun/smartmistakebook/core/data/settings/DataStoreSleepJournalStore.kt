package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.SleepJournalStore
import com.tingyun.smartmistakebook.core.domain.SleepWindowEntry
import com.tingyun.smartmistakebook.core.domain.SleepWindowInference
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * DataStore-backed silent activity journal (spec mastery-scheduling §2.14):
 * records when the app became visible and re-infers the sleep windows from
 * the usage gaps. Collection is entirely passive; nothing is surfaced in the
 * UI.
 */
class DataStoreSleepJournalStore(
    context: Context,
    scope: CoroutineScope,
) : SleepJournalStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE)
        },
    )

    override suspend fun recordActivity(atEpochMillis: Long) {
        require(atEpochMillis >= 0) { "Activity time must not be negative" }
        dataStore.edit { values ->
            val stamps = decodeStamps(values[ACTIVITY_STAMPS]).toLongArray().toMutableList()
            stamps += atEpochMillis
            val horizonStart = atEpochMillis - HORIZON_MILLIS
            val retained = stamps.filter { it >= horizonStart }.distinct().sorted()
            values[ACTIVITY_STAMPS] = encodeStamps(retained)
            values[SLEEP_WINDOWS] = encodeWindows(SleepWindowInference.infer(retained))
        }
    }

    override val recentWindows: Flow<List<SleepWindowEntry>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values -> decodeWindows(values[SLEEP_WINDOWS]) }
        .distinctUntilChanged()

    private fun decodeStamps(raw: String?): List<Long> = raw
        ?.split(STAMP_SEPARATOR)
        ?.mapNotNull { token -> token.toLongOrNull() }
        .orEmpty()

    private fun encodeStamps(stamps: List<Long>): String =
        stamps.joinToString(STAMP_SEPARATOR)

    private fun decodeWindows(raw: String?): List<SleepWindowEntry> = raw
        ?.let { value ->
            runCatching {
                json.decodeFromString(ListSerializer(SleepWindowEntry.serializer()), value)
            }.getOrDefault(emptyList())
        }
        .orEmpty()

    private fun encodeWindows(windows: List<SleepWindowEntry>): String =
        json.encodeToString(ListSerializer(SleepWindowEntry.serializer()), windows)

    private companion object {
        const val DATASTORE_FILE = "sleep_journal"
        const val STAMP_SEPARATOR = ","
        const val HORIZON_MILLIS = 30L * 24 * 60 * 60 * 1000
        val ACTIVITY_STAMPS = stringPreferencesKey("activity_stamps")
        val SLEEP_WINDOWS = stringPreferencesKey("sleep_windows")
    }
}
