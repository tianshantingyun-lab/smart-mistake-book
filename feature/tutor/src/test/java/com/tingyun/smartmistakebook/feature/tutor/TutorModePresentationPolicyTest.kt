package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorInteractionChoice
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceOutcome
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorModePresentationPolicyTest {
    private val moves = listOf(
        TutorSuggestedMove("deepen", "再看关键一步", TutorMoveType.DEEPEN_REASONING),
        TutorSuggestedMove("visual", "换成图来看", TutorMoveType.CHANGE_REPRESENTATION),
        TutorSuggestedMove("connect", "联系当前定义", TutorMoveType.CONNECT_KNOWLEDGE),
    )

    @Test
    fun directModeSuppressesDiagnosticsAndShowsCompleteCurrentExplanation() {
        val presentation = tutorTurnPresentation(
            mode = TutorExplanationMode.DIRECT,
            suggestedMoves = moves,
        )

        assertFalse(presentation.showDiagnostic)
        assertTrue(presentation.showCompleteExplanation)
        assertEquals(listOf("deepen", "visual"), presentation.followUpMoves.map { it.id })
    }

    @Test
    fun presentationConsumesTheAuthoritativeModeWithoutReapplyingBudgets() {
        val withinBudget = tutorTurnPresentation(
            mode = TutorExplanationMode.GUIDED,
            suggestedMoves = moves,
        )
        val policyStillGuided = tutorTurnPresentation(
            mode = TutorExplanationMode.GUIDED,
            suggestedMoves = moves,
        )
        val policyDirect = tutorTurnPresentation(
            mode = TutorExplanationMode.DIRECT,
            suggestedMoves = moves,
        )

        assertTrue(withinBudget.showDiagnostic)
        assertFalse(withinBudget.showCompleteExplanation)
        assertTrue(policyStillGuided.showDiagnostic)
        assertFalse(policyStillGuided.showCompleteExplanation)
        assertTrue(policyDirect.showCompleteExplanation)
    }

    @Test
    fun interactionDirectivesAreAvailableOnlyInGuidedMode() {
        val directive = TutorInteractionDirective.Choices(
            promptMarkdown = "先判断哪一步？",
            choices = listOf(
                TutorInteractionChoice("first", "第一步"),
                TutorInteractionChoice("second", "第二步"),
            ),
        )

        assertEquals(
            directive,
            visibleTutorInteractionDirective(TutorExplanationMode.GUIDED, directive),
        )
        assertNull(visibleTutorInteractionDirective(TutorExplanationMode.DIRECT, directive))
    }

    @Test
    fun productionGuidanceReplayKeepsOneExactPendingEvidenceIdentity() {
        val problem = TutorProblemScope("problem-1", 2)

        val state = replayTutorGuidance(
            problem = problem,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = listOf(
                TutorGuidanceEvent.Question("request-1", masteryRelevant = true),
                TutorGuidanceEvent.Evidence("request-stale", selectionWasCorrect = true),
            ),
        )
        val exact = state.authorizeEvidence("request-1")
        val stale = state.authorizeEvidence("request-stale")

        assertEquals("request-1", state.pendingEvidenceRequestId)
        assertEquals(TutorGuidanceOutcome.EVIDENCE_ACCEPTED, exact.outcome)
        assertEquals(TutorGuidanceOutcome.REJECTED_STALE, stale.outcome)
    }

    @Test
    fun productionGuidanceReplayForcesDirectAfterDurableExposure() {
        val state = replayTutorGuidance(
            problem = TutorProblemScope("problem-1", 2),
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = true,
            events = listOf(TutorGuidanceEvent.Question("request-1", masteryRelevant = true)),
        )

        assertEquals(TutorExplanationMode.DIRECT, state.mode)
        assertNull(state.pendingEvidenceRequestId)
    }
}
