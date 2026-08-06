package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.flow.Flow

const val DEFAULT_REVIEW_REMINDER_MINUTES_AFTER_MIDNIGHT = 20 * 60 + 30

data class ReviewReminderPreferences(
    val enabled: Boolean = false,
    val minutesAfterMidnight: Int = DEFAULT_REVIEW_REMINDER_MINUTES_AFTER_MIDNIGHT,
    val pacingLevel: ReviewPacingLevel = ReviewPacingLevel.STANDARD,
    val examSubject: SubjectKind? = null,
    val examEpochDay: Long? = null,
) {
    init {
        require(minutesAfterMidnight in 0 until MINUTES_PER_DAY) {
            "Reminder time must be within one local day."
        }
        require((examSubject == null) == (examEpochDay == null)) {
            "Exam target subject and day must appear together"
        }
        require(examEpochDay == null || examEpochDay >= 0L) {
            "Exam target day must not be negative"
        }
        require(examSubject == null || examSubject != SubjectKind.GENERAL) {
            "Exam target requires a high-school subject"
        }
    }
}

data class ReviewReminderDelivery(
    val localEpochDay: Long,
    val zoneId: String,
) {
    init {
        require(zoneId.isNotBlank()) { "Reminder zone must not be blank." }
    }
}

interface ReviewReminderRepository {
    val preferences: Flow<ReviewReminderPreferences>

    suspend fun current(): ReviewReminderPreferences

    suspend fun setEnabled(enabled: Boolean)

    suspend fun setReminderTime(minutesAfterMidnight: Int)

    suspend fun setPacingLevel(pacingLevel: ReviewPacingLevel)

    suspend fun setExamTarget(
        subject: SubjectKind?,
        examEpochDay: Long?,
    )

    /**
     * Atomically claims one notification delivery.
     *
     * Returns false when the same local day in the same time zone was already
     * delivered, including after a system-clock rollback.
     */
    suspend fun claimNotificationDelivery(delivery: ReviewReminderDelivery): Boolean
}

private const val MINUTES_PER_DAY = 24 * 60
