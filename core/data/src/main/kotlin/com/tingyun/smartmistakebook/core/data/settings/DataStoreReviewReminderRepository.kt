package com.tingyun.smartmistakebook.core.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.tingyun.smartmistakebook.core.domain.DEFAULT_REVIEW_REMINDER_MINUTES_AFTER_MIDNIGHT
import com.tingyun.smartmistakebook.core.domain.ReviewReminderDelivery
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class DataStoreReviewReminderRepository(
    context: Context,
    scope: CoroutineScope,
) : ReviewReminderRepository {
    private val dataStore = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = {
            context.applicationContext.preferencesDataStoreFile(DATASTORE_FILE)
        },
    )

    override val preferences: Flow<ReviewReminderPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            val storedMinutes = values[REMINDER_MINUTES]
                ?.takeIf { it in 0 until MINUTES_PER_DAY }
                ?: DEFAULT_REVIEW_REMINDER_MINUTES_AFTER_MIDNIGHT
            ReviewReminderPreferences(
                enabled = values[ENABLED] == true,
                minutesAfterMidnight = storedMinutes,
            )
        }
        .distinctUntilChanged()

    override suspend fun current(): ReviewReminderPreferences = preferences.first()

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { values -> values[ENABLED] = enabled }
    }

    override suspend fun setReminderTime(minutesAfterMidnight: Int) {
        require(minutesAfterMidnight in 0 until MINUTES_PER_DAY) {
            "Reminder time must be within one local day."
        }
        dataStore.edit { values -> values[REMINDER_MINUTES] = minutesAfterMidnight }
    }

    override suspend fun claimNotificationDelivery(delivery: ReviewReminderDelivery): Boolean {
        var claimed = false
        dataStore.edit { values ->
            val storedEntries = values[DELIVERY_HISTORY].orEmpty()
            val deliveryKey = delivery.deliveryKey()
            if (deliveryKey in storedEntries) {
                return@edit
            }

            claimed = true
            values[DELIVERY_HISTORY] = (storedEntries.asSequence()
                .plus(deliveryKey))
                .distinct()
                .sortedByDescending(::deliveryEpochDayOrMinimum)
                .take(MAX_DELIVERY_HISTORY)
                .toSet()
        }
        return claimed
    }

    private fun ReviewReminderDelivery.deliveryKey(): String =
        "$DELIVERY_PREFIX$zoneId|$localEpochDay"

    private fun deliveryEpochDayOrMinimum(value: String): Long {
        if (!value.startsWith(DELIVERY_PREFIX)) return Long.MIN_VALUE
        return value.substringAfterLast('|').toLongOrNull() ?: Long.MIN_VALUE
    }

    private companion object {
        const val DATASTORE_FILE = "review_reminder.preferences_pb"
        const val MINUTES_PER_DAY = 24 * 60
        const val MAX_DELIVERY_HISTORY = 64
        const val DELIVERY_PREFIX = "delivery-v1|"
        val ENABLED = booleanPreferencesKey("enabled")
        val REMINDER_MINUTES = intPreferencesKey("minutes_after_midnight")
        val DELIVERY_HISTORY = stringSetPreferencesKey("notification_delivery_history")
    }
}
