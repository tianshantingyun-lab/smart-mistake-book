package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.ExamCalendarEntry
import com.tingyun.smartmistakebook.core.domain.SchedulingOptions
import com.tingyun.smartmistakebook.core.domain.SchedulingSettingsStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * DataStore-backed scheduling settings (spec mastery-scheduling 2.4/2.17/2.20):
 * desired retention, the FSRS kill switch, the exam calendar, and locally
 * optimized FSRS parameters awaiting the next launch.
 */
class DataStoreSchedulingSettingsStore(
    context: Context,
    scope: CoroutineScope,
) : SchedulingSettingsStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE)
        },
    )

    override val options: Flow<SchedulingOptions> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            SchedulingOptions(
                desiredRetention = values[DESIRED_RETENTION]
                    ?: SchedulingOptions().desiredRetention,
                useFsrsScheduling = values[FSRS_ENABLED]
                    ?: SchedulingOptions().useFsrsScheduling,
            )
        }
        .distinctUntilChanged()

    override suspend fun setOptions(options: SchedulingOptions) {
        dataStore.edit { values ->
            values[DESIRED_RETENTION] = options.desiredRetention
            values[FSRS_ENABLED] = options.useFsrsScheduling
        }
    }

    override val exams: Flow<List<ExamCalendarEntry>> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            values[EXAMS]?.mapNotNull(::decodeExam).orEmpty()
        }
        .distinctUntilChanged()

    override suspend fun addExam(entry: ExamCalendarEntry) {
        dataStore.edit { values ->
            val current = values[EXAMS].orEmpty().mapNotNull(::decodeExam)
            val updated = current.filterNot { it.entryId == entry.entryId } + entry
            values[EXAMS] = updated.map(::encodeExam).toSet()
        }
    }

    override suspend fun removeExam(entryId: String) {
        dataStore.edit { values ->
            val current = values[EXAMS].orEmpty().mapNotNull(::decodeExam)
            values[EXAMS] = current.filterNot { it.entryId == entryId }.map(::encodeExam).toSet()
        }
    }

    override val optimizedParameters: Flow<DoubleArray?> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            values[OPTIMIZED_PARAMETERS]?.split(PARAMETER_SEPARATOR)
                ?.mapNotNull { token -> token.toDoubleOrNull() }
                ?.takeIf { it.size == FSRS_PARAMETER_COUNT }
                ?.toDoubleArray()
        }
        .distinctUntilChanged()

    override suspend fun setOptimizedParameters(parameters: DoubleArray?) {
        dataStore.edit { values ->
            if (parameters == null) {
                values.remove(OPTIMIZED_PARAMETERS)
            } else {
                values[OPTIMIZED_PARAMETERS] = parameters.joinToString(PARAMETER_SEPARATOR)
            }
        }
    }

    private fun decodeExam(raw: String): ExamCalendarEntry? = try {
        json.decodeFromString(ExamCalendarEntry.serializer(), raw)
    } catch (_: Exception) {
        null
    }

    private fun encodeExam(entry: ExamCalendarEntry): String =
        json.encodeToString(ExamCalendarEntry.serializer(), entry)

    private companion object {
        const val DATASTORE_FILE = "scheduling_settings"
        const val FSRS_PARAMETER_COUNT = 21
        const val PARAMETER_SEPARATOR = ","
        val DESIRED_RETENTION = doublePreferencesKey("desired_retention")
        val FSRS_ENABLED = booleanPreferencesKey("fsrs_enabled")
        val EXAMS = stringSetPreferencesKey("exam_calendar")
        val OPTIMIZED_PARAMETERS = stringPreferencesKey("optimized_fsrs_parameters")
    }
}
