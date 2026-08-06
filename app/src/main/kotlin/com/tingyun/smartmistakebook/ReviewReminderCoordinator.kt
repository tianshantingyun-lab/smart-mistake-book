package com.tingyun.smartmistakebook

import android.content.Intent
import com.tingyun.smartmistakebook.core.domain.ReviewReminderDelivery
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal class ReviewReminderCoordinator(
    private val repository: ReviewReminderRepository,
    private val platform: ReviewReminderSystem,
    private val pendingReviewCount: suspend () -> Int,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) : ReviewReminderBroadcastHandler {
    fun start() {
        platform.ensureNotificationChannel()
        scope.launch {
            repository.preferences.collectLatest(::reconcile)
        }
    }

    override suspend fun handle(action: String) {
        handleBroadcast(action)
    }

    suspend fun handleBroadcast(action: String?) {
        when (action) {
            ReviewReminderContract.ACTION_DAILY_REMINDER -> handleReminderFired()
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> reconcile(repository.current())
        }
    }

    suspend fun refresh() {
        reconcile(repository.current())
    }

    private suspend fun handleReminderFired() {
        val preferences = repository.current()
        if (!preferences.enabled || !platform.canPostNotifications()) {
            platform.cancel()
            return
        }
        scheduleNext(preferences)
        val pendingCount = pendingReviewCount()
        val now = clock()
        val zone = zoneId()
        val claimed = pendingCount > 0 && repository.claimNotificationDelivery(
            ReviewReminderDelivery(
                localEpochDay = java.time.Instant.ofEpochMilli(now)
                    .atZone(zone)
                    .toLocalDate()
                    .toEpochDay(),
                zoneId = zone.id,
            ),
        )
        if (claimed) {
            platform.postNotification(pendingCount)
        }
    }

    private fun reconcile(preferences: ReviewReminderPreferences) {
        if (!preferences.enabled || !platform.canPostNotifications()) {
            platform.cancel()
            return
        }
        scheduleNext(preferences)
    }

    private fun scheduleNext(preferences: ReviewReminderPreferences) {
        platform.schedule(
            ReviewReminderTimeCalculator.nextTriggerEpochMillis(
                nowEpochMillis = clock(),
                minutesAfterMidnight = preferences.minutesAfterMidnight,
                zoneId = zoneId(),
            ),
        )
    }
}
