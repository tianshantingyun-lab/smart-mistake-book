package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentAssistanceEvent
import com.tingyun.smartmistakebook.core.model.PersistedAssessmentAssistance
import com.tingyun.smartmistakebook.core.model.TutorAssistanceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorAssistanceCoordinatorTest {
    @Test
    fun `hint is persisted before it is presented`() {
        val calls = mutableListOf<String>()
        val event = hintEvent()
        val coordinator = TutorAssistanceCoordinator(
            recorder = AssessmentAssistanceRecorder {
                calls += "persist"
                PersistedAssessmentAssistance(it, persistedAtEpochMillis = 11)
            },
            presenter = TutorAssistancePresenter { calls += "present" },
        )

        coordinator.deliver(event)

        assertEquals(listOf("persist", "present"), calls)
    }

    @Test
    fun `presentation is skipped when persistence fails`() {
        var wasPresented = false
        val coordinator = TutorAssistanceCoordinator(
            recorder = AssessmentAssistanceRecorder { error("disk unavailable") },
            presenter = TutorAssistancePresenter { wasPresented = true },
        )

        val result = runCatching { coordinator.deliver(hintEvent()) }

        assertTrue(result.isFailure)
        assertTrue(!wasPresented)
    }

    private fun hintEvent() = AssessmentAssistanceEvent(
        eventId = "hint-1",
        assessmentItemId = "item-1",
        presentationId = "presentation-1",
        kind = TutorAssistanceKind.HINT,
        contentMarkdown = "先判断定义域。",
        occurredAtEpochMillis = 10,
        eventSequence = 1,
    )
}
