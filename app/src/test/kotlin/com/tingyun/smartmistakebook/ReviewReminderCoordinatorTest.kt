package com.tingyun.smartmistakebook

import android.content.Intent
import com.tingyun.smartmistakebook.core.domain.ReviewReminderDelivery
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import com.tingyun.smartmistakebook.core.domain.ReviewPacingLevel
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewReminderCoordinatorTest {
    private val now = Instant.parse("2026-07-21T19:00:00Z").toEpochMilli()

    @Test
    fun refreshSchedulesEnabledReminderFromTrustedPreferences() = runBlocking {
        val repository = FakeRepository(ReviewReminderPreferences(enabled = true))
        val system = FakeSystem()
        val coordinator = coordinator(repository, system)

        coordinator.refresh()

        assertEquals(
            Instant.parse("2026-07-21T20:30:00Z").toEpochMilli(),
            system.scheduledAt,
        )
        assertFalse(system.cancelled)
    }

    @Test
    fun reminderBroadcastPostsOnlyThePendingCountAndSchedulesTomorrow() = runBlocking {
        val repository = FakeRepository(ReviewReminderPreferences(enabled = true))
        val system = FakeSystem()
        val coordinator = coordinator(repository, system)

        coordinator.handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)

        assertTrue(system.notificationPosted)
        assertEquals(3, system.notificationPendingCount)
        assertEquals(
            Instant.parse("2026-07-21T20:30:00Z").toEpochMilli(),
            system.scheduledAt,
        )
    }

    @Test
    fun duplicateAlarmAndClockRollbackDoNotDistractTheStudentAgain() = runBlocking {
        val repository = FakeRepository(ReviewReminderPreferences(enabled = true))
        val system = FakeSystem()
        val coordinator = coordinator(repository, system)

        coordinator.handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)
        coordinator.handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)

        assertEquals(1, system.notificationPostCount)

        coordinator(
            repository = repository,
            system = system,
            nowEpochMillis = Instant.parse("2026-07-22T19:00:00Z").toEpochMilli(),
        ).handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)
        assertEquals(2, system.notificationPostCount)

        val rolledBackCoordinator = coordinator(
            repository = repository,
            system = system,
            nowEpochMillis = Instant.parse("2026-07-21T19:00:00Z").toEpochMilli(),
        )
        rolledBackCoordinator.handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)

        assertEquals(2, system.notificationPostCount)
    }

    @Test
    fun aDifferentLocalDayInAnotherTimeZoneCanNotifyOnce() = runBlocking {
        val repository = FakeRepository(ReviewReminderPreferences(enabled = true))
        val system = FakeSystem()
        coordinator(repository, system, zone = ZoneId.of("Pacific/Kiritimati"))
            .handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)
        coordinator(repository, system, zone = ZoneId.of("America/Adak"))
            .handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)
        coordinator(repository, system, zone = ZoneId.of("America/Adak"))
            .handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)

        assertEquals(2, system.notificationPostCount)
    }

    @Test
    fun aFinishedOrEmptyDaySchedulesTomorrowWithoutDistractingTheStudent() = runBlocking {
        val repository = FakeRepository(ReviewReminderPreferences(enabled = true))
        val system = FakeSystem()
        val coordinator = coordinator(repository, system, pendingCount = 0)

        coordinator.handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)

        assertFalse(system.notificationPosted)
        assertEquals(
            Instant.parse("2026-07-21T20:30:00Z").toEpochMilli(),
            system.scheduledAt,
        )
    }

    @Test
    fun disabledOrPermissionRevokedCancelsWithoutPosting() = runBlocking {
        val disabledRepository = FakeRepository(ReviewReminderPreferences(enabled = false))
        val disabledSystem = FakeSystem()
        coordinator(disabledRepository, disabledSystem)
            .handleBroadcast(ReviewReminderContract.ACTION_DAILY_REMINDER)
        assertTrue(disabledSystem.cancelled)
        assertFalse(disabledSystem.notificationPosted)

        val blockedRepository = FakeRepository(ReviewReminderPreferences(enabled = true))
        val blockedSystem = FakeSystem(canPost = false)
        coordinator(blockedRepository, blockedSystem)
            .handleBroadcast(Intent.ACTION_BOOT_COMPLETED)
        assertTrue(blockedSystem.cancelled)
        assertFalse(blockedSystem.notificationPosted)
    }

    private fun coordinator(
        repository: ReviewReminderRepository,
        system: ReviewReminderSystem,
        pendingCount: Int = 3,
        nowEpochMillis: Long = now,
        zone: ZoneId = ZoneId.of("UTC"),
    ) = ReviewReminderCoordinator(
        repository = repository,
        platform = system,
        pendingReviewCount = { pendingCount },
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { nowEpochMillis },
        zoneId = { zone },
    )

    private class FakeRepository(
        initial: ReviewReminderPreferences,
    ) : ReviewReminderRepository {
        private val state = MutableStateFlow(initial)
        private val delivered = mutableSetOf<ReviewReminderDelivery>()
        override val preferences: Flow<ReviewReminderPreferences> = state

        override suspend fun current(): ReviewReminderPreferences = state.value

        override suspend fun setEnabled(enabled: Boolean) {
            state.value = state.value.copy(enabled = enabled)
        }

        override suspend fun setReminderTime(minutesAfterMidnight: Int) {
            state.value = state.value.copy(minutesAfterMidnight = minutesAfterMidnight)
        }

        override suspend fun setPacingLevel(pacingLevel: ReviewPacingLevel) {
            state.value = state.value.copy(pacingLevel = pacingLevel)
        }

        override suspend fun setExamTarget(
            subject: SubjectKind?,
            examEpochDay: Long?,
        ) {
            state.value = state.value.copy(
                examSubject = subject,
                examEpochDay = examEpochDay,
            )
        }

        override suspend fun claimNotificationDelivery(
            delivery: ReviewReminderDelivery,
        ): Boolean = delivered.add(delivery)
    }

    private class FakeSystem(
        private val canPost: Boolean = true,
    ) : ReviewReminderSystem {
        var scheduledAt: Long? = null
        var cancelled = false
        var notificationPosted = false
        var notificationPostCount = 0
        var notificationPendingCount: Int? = null

        override fun canPostNotifications(): Boolean = canPost
        override fun ensureNotificationChannel() = Unit
        override fun schedule(triggerAtEpochMillis: Long) {
            scheduledAt = triggerAtEpochMillis
        }
        override fun cancel() {
            cancelled = true
        }
        override fun postNotification(pendingCount: Int) {
            notificationPosted = true
            notificationPostCount += 1
            notificationPendingCount = pendingCount
        }
    }
}
