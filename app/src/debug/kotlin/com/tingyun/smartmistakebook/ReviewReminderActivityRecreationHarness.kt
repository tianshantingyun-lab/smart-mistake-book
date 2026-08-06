package com.tingyun.smartmistakebook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import com.tingyun.smartmistakebook.core.domain.ReviewPacingLevel
import com.tingyun.smartmistakebook.core.domain.ReviewReminderDelivery
import com.tingyun.smartmistakebook.core.domain.ReviewReminderPreferences
import com.tingyun.smartmistakebook.core.domain.ReviewReminderRepository
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.ui.SmartMistakeBookTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

object ReviewReminderRotationFixture {
    val repository = InMemoryReviewReminderRepository()
    var lastBackgroundColor: Color = Color.Unspecified
}

class ReviewReminderActivityRecreationHarness : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SmartMistakeBookTheme {
                ReviewReminderRotationFixture.lastBackgroundColor =
                    MaterialTheme.colorScheme.background
                ReminderScreen(
                    repository = ReviewReminderRotationFixture.repository,
                    onRefreshSchedule = {},
                    onBack = {},
                )
            }
        }
    }
}

class InMemoryReviewReminderRepository : ReviewReminderRepository {
    private val state = MutableStateFlow(ReviewReminderPreferences())

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
    ): Boolean = true
}
