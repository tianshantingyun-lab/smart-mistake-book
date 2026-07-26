package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorGuidancePolicyTest {
    private val problem = TutorProblemScope("problem-1", revisionNumber = 3)

    @Test
    fun guidedQuestionsStayMasteryRelevantAndStopAfterThree() {
        var state = TutorGuidanceState(problem = problem, mode = TutorExplanationMode.GUIDED)

        repeat(3) { index ->
            val decision = TutorGuidancePolicy.evaluate(
                state,
                TutorGuidanceRequest.question(
                    requestId = "question-$index",
                    problem = problem,
                    masteryRelevant = true,
                ),
            )
            assertEquals(TutorGuidanceOutcome.GUIDED, decision.outcome)
            state = decision.state
        }

        val fourth = TutorGuidancePolicy.evaluate(
            state,
            TutorGuidanceRequest.question("question-4", problem, masteryRelevant = true),
        )
        val unrelated = TutorGuidancePolicy.evaluate(
            state.copy(questionsAsked = 0),
            TutorGuidanceRequest.question("unrelated", problem, masteryRelevant = false),
        )

        assertEquals(TutorGuidanceOutcome.DIRECT_EXPLANATION, fourth.outcome)
        assertEquals(TutorGuidanceOutcome.DIRECT_EXPLANATION, unrelated.outcome)
        assertFalse(fourth.mayWriteLearningEvidence)
    }

    @Test
    fun oneHintAndTwoStrugglesFallBackToDirectExplanation() {
        val initial = TutorGuidanceState(problem = problem, mode = TutorExplanationMode.GUIDED)
        val hint = TutorGuidancePolicy.evaluate(
            initial,
            TutorGuidanceRequest.hint("hint-1", problem),
        )
        val secondHint = TutorGuidancePolicy.evaluate(
            hint.state,
            TutorGuidanceRequest.hint("hint-2", problem),
        )
        val firstStruggle = TutorGuidancePolicy.evaluate(
            initial,
            TutorGuidanceRequest.struggle("struggle-1", problem),
        )
        val secondStruggle = TutorGuidancePolicy.evaluate(
            firstStruggle.state,
            TutorGuidanceRequest.struggle("struggle-2", problem),
        )

        assertEquals(TutorGuidanceOutcome.GUIDED, hint.outcome)
        assertEquals(TutorGuidanceOutcome.DIRECT_EXPLANATION, secondHint.outcome)
        assertEquals(TutorGuidanceOutcome.GUIDED, firstStruggle.outcome)
        assertEquals(TutorGuidanceOutcome.DIRECT_EXPLANATION, secondStruggle.outcome)
    }

    @Test
    fun directOverrideCancelsPendingEvidenceAndRejectsItsLateCompletion() {
        val awaiting = TutorGuidancePolicy.evaluate(
            TutorGuidanceState(problem = problem, mode = TutorExplanationMode.GUIDED),
            TutorGuidanceRequest.question("evidence-1", problem, masteryRelevant = true),
        ).state

        val transition = TutorGuidancePolicy.transitionMode(
            awaiting,
            TutorExplanationMode.DIRECT,
        )
        val staleCompletion = TutorGuidancePolicy.evaluate(
            transition.state,
            TutorGuidanceRequest.evidence("evidence-1", problem),
        )

        assertEquals("evidence-1", transition.cancelEvidenceRequestId)
        assertTrue(transition.requestDirectContinuation)
        assertEquals(TutorGuidanceOutcome.REJECTED_STALE, staleCompletion.outcome)
        assertFalse(staleCompletion.mayWriteLearningEvidence)
        assertNull(staleCompletion.state.pendingEvidenceRequestId)
    }

    @Test
    fun anotherProblemAndVisualBrowsingNeverWriteLearningEvidence() {
        val state = TutorGuidanceState(problem = problem, mode = TutorExplanationMode.GUIDED)
        val stale = TutorGuidancePolicy.evaluate(
            state,
            TutorGuidanceRequest.question(
                "other-problem",
                TutorProblemScope("problem-2", revisionNumber = 1),
                masteryRelevant = true,
            ),
        )
        val browse = TutorGuidancePolicy.evaluate(
            state,
            TutorGuidanceRequest.visualBrowse("visual-1", problem),
        )

        assertEquals(TutorGuidanceOutcome.REJECTED_STALE, stale.outcome)
        assertEquals(TutorGuidanceOutcome.READ_ONLY, browse.outcome)
        assertFalse(stale.mayWriteLearningEvidence)
        assertFalse(browse.mayWriteLearningEvidence)
    }

    @Test
    fun evidenceWritesOnlyForTheCurrentPendingGuidedQuestion() {
        val awaiting = TutorGuidancePolicy.evaluate(
            TutorGuidanceState(problem = problem, mode = TutorExplanationMode.GUIDED),
            TutorGuidanceRequest.question("evidence-1", problem, masteryRelevant = true),
        ).state

        val accepted = TutorGuidancePolicy.evaluate(
            awaiting,
            TutorGuidanceRequest.evidence("evidence-1", problem),
        )
        val duplicate = TutorGuidancePolicy.evaluate(
            accepted.state,
            TutorGuidanceRequest.evidence("evidence-1", problem),
        )

        assertTrue(accepted.mayWriteLearningEvidence)
        assertEquals(TutorGuidanceOutcome.REJECTED_STALE, duplicate.outcome)
        assertFalse(duplicate.mayWriteLearningEvidence)
    }

    @Test
    fun thirdAcceptedAnswerAutomaticallyCompletesTheCurrentSubquestionDirectly() {
        val awaitingThird = TutorGuidanceState(
            problem = problem,
            mode = TutorExplanationMode.GUIDED,
            questionsAsked = TutorGuidancePolicy.MAX_QUESTIONS,
            pendingEvidenceRequestId = "evidence-3",
        )

        val accepted = TutorGuidancePolicy.evaluate(
            awaitingThird,
            TutorGuidanceRequest.evidence("evidence-3", problem),
        )

        assertEquals(TutorGuidanceOutcome.EVIDENCE_ACCEPTED, accepted.outcome)
        assertTrue(accepted.mayWriteLearningEvidence)
        assertEquals(TutorExplanationMode.DIRECT, accepted.state.mode)
        assertNull(accepted.state.pendingEvidenceRequestId)
    }
}
