package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorFreeResponseEvaluation
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorSuggestedMove
import com.tingyun.smartmistakebook.core.model.TutorInteractionChoice
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeGuidance
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceOutcome
import com.tingyun.smartmistakebook.core.domain.TutorGuidanceState
import com.tingyun.smartmistakebook.core.domain.TutorProblemScope
import kotlinx.coroutines.runBlocking
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
        val replay = replayTutorGuidanceTransition(
            problem = TutorProblemScope("problem-1", 2),
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = true,
            events = listOf(TutorGuidanceEvent.Question("request-1", masteryRelevant = true)),
        )
        val state = replay.state

        assertEquals(TutorExplanationMode.DIRECT, state.mode)
        assertNull(state.pendingEvidenceRequestId)
        assertEquals("request-1", replay.cancelEvidenceRequestId)
    }

    @Test
    fun transientDirectPreviewExposureIsPresentationOnlyAndCannotAuthorizeMasteryEvidence() {
        val tracker = TutorSolutionExposureTracker(
            viewportBounds = mutableStateOf<Rect?>(null),
            solutionBottomAnchors = mutableStateMapOf(),
            recordedAnswerExposureKeysState = mutableStateOf(emptySet()),
            transientAnswerExposureKeysState = mutableStateOf(emptySet()),
            targets = emptyList(),
        )
        val exposureKey = TutorAnswerExposureKey(
            sessionId = "session-1",
            questionDocumentId = "document-1",
            revisionNumber = 2,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
            modelTaskRequestId = "request-1",
            responseOrdinal = 1,
        )

        assertTrue(tracker.markTransientAnswerExposure(exposureKey))
        assertFalse(tracker.markTransientAnswerExposure(exposureKey))
        assertTrue(tracker.answerExposureKeys.isEmpty())
        assertEquals(setOf(exposureKey), tracker.presentationAnswerExposureKeys)

        val state = replayTutorGuidance(
            problem = TutorProblemScope("problem-1", 2),
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = tracker.answerExposureKeys.isNotEmpty(),
            events = listOf(TutorGuidanceEvent.Question("request-1", masteryRelevant = true)),
        )

        assertEquals(TutorExplanationMode.GUIDED, state.mode)
        assertEquals("request-1", state.pendingEvidenceRequestId)
        assertTrue(state.authorizeEvidence("request-1").mayWriteLearningEvidence)
    }

    @Test
    fun twoExplicitlyIncorrectFreeResponsesTriggerDirectFallbackWhileUnknownWritesNoEvidence() {
        val problem = TutorProblemScope("problem-1", 2)
        val firstQuestion = TutorGuidanceEvent.Question("request-1", masteryRelevant = true)
        val secondQuestion = TutorGuidanceEvent.Question("request-2", masteryRelevant = true)
        val unknown = freeResponseEvidenceEvent(
            requestId = "request-1",
            output = respondOutput(TutorFreeResponseEvaluation.UNKNOWN),
        )
        val firstIncorrect = freeResponseEvidenceEvent(
            requestId = "request-1",
            output = respondOutput(TutorFreeResponseEvaluation.INCORRECT),
        )
        val secondIncorrect = freeResponseEvidenceEvent(
            requestId = "request-2",
            output = respondOutput(TutorFreeResponseEvaluation.INCORRECT),
        )

        assertNull(unknown)
        val replay = replayTutorGuidanceTransition(
            problem = problem,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = listOf(
                firstQuestion,
                requireNotNull(firstIncorrect),
                secondQuestion,
                requireNotNull(secondIncorrect),
            ),
        )
        val state = replay.state

        assertEquals(TutorExplanationMode.DIRECT, state.mode)
        assertEquals(2, state.strugglesObserved)
        assertNull(state.pendingEvidenceRequestId)
        assertNull(replay.cancelEvidenceRequestId)
    }

    @Test
    fun directTransitionsCancelOnlyAnUnconsumedPendingEvidenceIdentity() {
        val problem = TutorProblemScope("problem-1", 2)
        val pending = TutorGuidanceState(
            problem = problem,
            mode = TutorExplanationMode.GUIDED,
            questionsAsked = 1,
            pendingEvidenceRequestId = "request-pending",
        )

        listOf("直接讲", "不要问", "别提问").forEach { message ->
            val transition = tutorResponseModeTransitionFor(pending, message)

            assertEquals(TutorExplanationMode.DIRECT, transition.state.mode)
            assertEquals("request-pending", transition.cancelEvidenceRequestId)
        }
        val ordinary = tutorResponseModeTransitionFor(pending, "“不要问”是什么意思？")
        assertEquals(TutorExplanationMode.GUIDED, ordinary.state.mode)
        assertNull(ordinary.cancelEvidenceRequestId)

        val secondHint = replayTutorGuidanceTransition(
            problem = problem,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = listOf(
                TutorGuidanceEvent.Question("request-pending", masteryRelevant = true),
                TutorGuidanceEvent.Hint("hint-1"),
                TutorGuidanceEvent.Hint("hint-2"),
            ),
        )
        assertEquals(TutorExplanationMode.DIRECT, secondHint.state.mode)
        assertEquals("request-pending", secondHint.cancelEvidenceRequestId)
    }

    @Test
    fun exposureWaitsForCancellationAcknowledgementAndBlocksTheOldInteraction() {
        val replay = replayTutorGuidanceTransition(
            problem = TutorProblemScope("problem-1", 2),
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = listOf(
                TutorGuidanceEvent.Question("request-pending", masteryRelevant = true),
            ),
        )

        val beforeAcknowledgement = resolveTutorGuidanceMode(
            replay = replay,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = true,
            cancellationConfirmed = false,
        )
        assertEquals(TutorExplanationMode.GUIDED, beforeAcknowledgement.state.mode)
        assertEquals(
            "request-pending",
            beforeAcknowledgement.state.pendingEvidenceRequestId,
        )
        assertEquals(
            "request-pending",
            beforeAcknowledgement.cancelEvidenceRequestId,
        )
        assertTrue(beforeAcknowledgement.blockPendingInteraction)

        val acknowledged = resolveTutorGuidanceMode(
            replay = replay,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = true,
            cancellationConfirmed = true,
        )
        assertEquals(TutorExplanationMode.DIRECT, acknowledged.state.mode)
        assertNull(acknowledged.state.pendingEvidenceRequestId)
        assertFalse(acknowledged.blockPendingInteraction)
    }

    @Test
    fun oldCancellationRemovesOnlyItsPendingInteractionAndDoesNotLockGuidedMode() {
        val replay = replayTutorGuidanceTransition(
            problem = TutorProblemScope("problem-1", 2),
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = listOf(
                TutorGuidanceEvent.Question("request-cancelled", masteryRelevant = true),
            ),
        )

        val resolution = resolveTutorGuidanceMode(
            replay = replay,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            cancellationConfirmed = true,
        )

        assertEquals(TutorExplanationMode.GUIDED, resolution.state.mode)
        assertNull(resolution.state.pendingEvidenceRequestId)
        assertEquals(1, resolution.state.questionsAsked)
        assertNull(resolution.cancelEvidenceRequestId)
        assertFalse(resolution.blockPendingInteraction)
    }

    @Test
    fun directIntentCancelsBeforeExternalApprovalAndFailureStopsContinuation() = runBlocking {
        val order = mutableListOf<String>()

        continueTutorResponseAfterEvidenceCancellation(
            cancelEvidenceRequestId = "request-pending",
            cancellationConfirmed = false,
            cancelEvidence = { requestId -> order += "cancel:$requestId" },
            continueResponse = { order += "external-approval" },
        )
        assertEquals(
            listOf("cancel:request-pending", "external-approval"),
            order,
        )

        val failedOrder = mutableListOf<String>()
        val failure = runCatching {
            continueTutorResponseAfterEvidenceCancellation(
                cancelEvidenceRequestId = "request-pending",
                cancellationConfirmed = false,
                cancelEvidence = {
                    failedOrder += "cancel"
                    error("database unavailable")
                },
                continueResponse = { failedOrder += "external-approval" },
            )
        }
        assertTrue(failure.isFailure)
        assertEquals(listOf("cancel"), failedOrder)
    }

    private fun respondOutput(evaluation: TutorFreeResponseEvaluation) = TutorRespondOutput(
        sessionId = "session-1",
        draftRevisionNumber = 2,
        questionDocumentId = "document-1",
        responseOrdinal = 1,
        messageMarkdown = "继续看当前题。",
        freeResponseEvaluation = evaluation,
        modelVersion = "test",
    )

    @Test
    fun emptyMasteryTargetsFailClosedInsteadOfAuthorizingAQuestion() {
        assertFalse(
            masteryTargetsAreRelevant(
                targetedEvidenceLabels = emptyList(),
                teachingConstraints = listOf(
                    TutorKnowledgeGuidance(
                        ref = "current-question-point-1",
                        label = "导数符号",
                        constraint = TutorTeachingConstraint.MAY_GUIDE,
                    ),
                ),
            ),
        )
    }

    @Test
    fun onlyMayGuidePointsCanAuthorizeAQuestion() {
        fun relevant(constraint: TutorTeachingConstraint) = masteryTargetsAreRelevant(
            targetedEvidenceLabels = listOf("导数符号"),
            teachingConstraints = listOf(
                TutorKnowledgeGuidance(
                    ref = "current-question-point-1",
                    label = "导数符号",
                    constraint = constraint,
                ),
            ),
        )

        assertTrue(relevant(TutorTeachingConstraint.MAY_GUIDE))
        assertFalse(relevant(TutorTeachingConstraint.SKIP_BASIC_PROMPT))
        assertFalse(relevant(TutorTeachingConstraint.EXPLAIN_DIRECTLY))
    }

    @Test
    fun directiveAnswersConsumeTheSameThreeQuestionBudgetAndClearPendingIdentity() {
        val problem = TutorProblemScope("problem-1", 2)
        val events = buildList {
            repeat(3) { index ->
                add(TutorGuidanceEvent.Question("directive-$index", masteryRelevant = true))
                add(TutorGuidanceEvent.Evidence("directive-$index", selectionWasCorrect = true))
            }
        }

        val replay = replayTutorGuidanceTransition(
            problem = problem,
            requestedMode = TutorExplanationMode.GUIDED,
            answerWasExposed = false,
            events = events,
        )
        val state = replay.state

        assertEquals(3, state.questionsAsked)
        assertEquals(TutorExplanationMode.DIRECT, state.mode)
        assertNull(state.pendingEvidenceRequestId)
        assertNull(replay.cancelEvidenceRequestId)
    }

    @Test
    fun modeChangeCancelsPendingEvidenceBeforePersistingTheSetting() {
        val order = mutableListOf<String>()

        requestTutorExplanationModeChange(
            mode = TutorExplanationMode.DIRECT,
            cancelPendingEvidence = { order += "cancel" },
            persistMode = { order += "persist:$it" },
        )

        assertEquals(listOf("cancel", "persist:DIRECT"), order)
    }
}
