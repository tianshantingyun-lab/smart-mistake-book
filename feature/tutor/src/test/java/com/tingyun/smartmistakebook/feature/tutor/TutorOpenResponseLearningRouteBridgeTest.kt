package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TutorOpenResponseLearningRouteBridgeTest {
    @Test
    fun unavailableHostKeepsTheVisibleReplyButSchedulesNoLearningWork() = runTest {
        val bridge = TutorOpenResponseLearningRouteBridge(backgroundScope, hostPort = null)

        val result = bridge.submitVisible(submission())
        advanceUntilIdle()

        assertEquals(TutorOpenResponseLearningDispatch.NotCurrent, result)
    }

    @Test
    fun exactHostIssuedContextDispatchesOnlyAfterTheRouteReturns() = runTest {
        val host = RecordingHostPort()
        val bridge = TutorOpenResponseLearningRouteBridge(backgroundScope, host)

        val result = bridge.submitVisible(submission())

        assertTrue(result is TutorOpenResponseLearningDispatch.Scheduled)
        assertTrue(host.admitted.isEmpty())
        runCurrent()
        assertEquals(1, host.issueCount)
        assertEquals(1, host.admitted.size)
        assertEquals(ANSWER, host.admitted.single().message.rawAnswer)
    }

    @Test
    fun hostCannotReplaceTheVisibleQuestionOrModeWhileIssuingContext() = runTest {
        val host =
            RecordingHostPort(
                issue = { request ->
                    issuedContext(
                        request,
                        questionRevisionNumber = request.questionRevisionNumber + 1,
                    )
                },
            )
        val bridge = TutorOpenResponseLearningRouteBridge(backgroundScope, host)

        val result = bridge.submitVisible(submission())
        advanceUntilIdle()

        assertTrue(result is TutorOpenResponseLearningDispatch.Scheduled)
        assertTrue(host.admitted.isEmpty())
    }

    @Test
    fun clearRevokesTheLeaseHeldByAStartedHostWrite() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val host =
            RecordingHostPort(
                onSubmit = { _, lease ->
                    lease.requireCurrent()
                    started.complete(Unit)
                    release.await()
                    lease.isCurrent()
                },
            )
        val bridge = TutorOpenResponseLearningRouteBridge(backgroundScope, host)
        bridge.submitVisible(submission())
        runCurrent()
        assertTrue(started.isCompleted)

        bridge.clearCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(host.committed)
    }

    @Test
    fun clearCancelsSlowContextIssuanceBeforeItCanBindTheController() = runTest {
        val issueStarted = CompletableDeferred<Unit>()
        val releaseIssue = CompletableDeferred<Unit>()
        val host =
            RecordingHostPort(
                issue = { request ->
                    issueStarted.complete(Unit)
                    releaseIssue.await()
                    issuedContext(request)
                },
            )
        val bridge = TutorOpenResponseLearningRouteBridge(backgroundScope, host)
        bridge.submitVisible(submission())
        runCurrent()
        assertTrue(issueStarted.isCompleted)

        bridge.clearCurrent()
        releaseIssue.complete(Unit)
        advanceUntilIdle()

        assertTrue(host.admitted.isEmpty())
    }

    @Test
    fun admissionRequiresAnExactCurrentFreeResponseAndRejectsNonAnswerActions() {
        val guided =
            resolveTutorOpenResponseRouteAdmission(
                explanationMode = TutorExplanationMode.GUIDED,
                pendingEvidenceRequestId = EVIDENCE_REQUEST_ID,
                visibleFreeResponseRequestId = EVIDENCE_REQUEST_ID,
                directEvidenceRequestId = "turn-request",
                selectedChoiceId = null,
                requestedMove = null,
                isHintRequest = false,
                allowLongTermLearningWrites = true,
            )
        assertEquals(
            TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE,
            guided?.source,
        )
        assertEquals(
            TutorOpenResponseMessageSource.DIRECT_CONVERSATION,
            resolveTutorOpenResponseRouteAdmission(
                explanationMode = TutorExplanationMode.DIRECT,
                pendingEvidenceRequestId = null,
                visibleFreeResponseRequestId = null,
                directEvidenceRequestId = "turn-request",
                selectedChoiceId = null,
                requestedMove = null,
                isHintRequest = false,
                allowLongTermLearningWrites = true,
            )?.source,
        )
        assertNull(
            resolveTutorOpenResponseRouteAdmission(
                explanationMode = TutorExplanationMode.GUIDED,
                pendingEvidenceRequestId = EVIDENCE_REQUEST_ID,
                visibleFreeResponseRequestId = "stale-request",
                directEvidenceRequestId = "turn-request",
                selectedChoiceId = null,
                requestedMove = null,
                isHintRequest = false,
                allowLongTermLearningWrites = true,
            ),
        )
        assertNull(
            resolveTutorOpenResponseRouteAdmission(
                explanationMode = TutorExplanationMode.DIRECT,
                pendingEvidenceRequestId = null,
                visibleFreeResponseRequestId = null,
                directEvidenceRequestId = "turn-request",
                selectedChoiceId = "choice-a",
                requestedMove = null,
                isHintRequest = false,
                allowLongTermLearningWrites = true,
            ),
        )
        assertNull(
            resolveTutorOpenResponseRouteAdmission(
                explanationMode = TutorExplanationMode.DIRECT,
                pendingEvidenceRequestId = null,
                visibleFreeResponseRequestId = null,
                directEvidenceRequestId = "turn-request",
                selectedChoiceId = null,
                requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
                isHintRequest = false,
                allowLongTermLearningWrites = true,
            ),
        )
        assertNull(
            resolveTutorOpenResponseRouteAdmission(
                explanationMode = TutorExplanationMode.DIRECT,
                pendingEvidenceRequestId = null,
                visibleFreeResponseRequestId = null,
                directEvidenceRequestId = "turn-request",
                selectedChoiceId = null,
                requestedMove = null,
                isHintRequest = true,
                allowLongTermLearningWrites = true,
            ),
        )
        assertNull(
            resolveTutorOpenResponseRouteAdmission(
                explanationMode = TutorExplanationMode.DIRECT,
                pendingEvidenceRequestId = null,
                visibleFreeResponseRequestId = null,
                directEvidenceRequestId = "turn-request",
                selectedChoiceId = null,
                requestedMove = null,
                isHintRequest = false,
                allowLongTermLearningWrites = false,
            ),
        )
    }

    private class RecordingHostPort(
        private val issue: suspend (TutorOpenResponseLearningContextRequest) ->
            TutorOpenResponseLearningContext? = { request -> issuedContext(request) },
        private val onSubmit: suspend (
            AdmittedTutorOpenResponseLearningEvidence,
            TutorOpenResponseLearningSubmissionLease,
        ) -> Boolean = { _, lease ->
            lease.requireCurrent()
            true
        },
    ) : TutorOpenResponseLearningHostPort {
        override val learnerId: String = LEARNER_ID
        override val directIntentClassifier =
            DirectOpenResponseLearningIntentClassifier { _, lease ->
                lease.requireCurrent()
                DirectOpenResponseLearningIntent.NotSpecificLearningEvidence
            }
        var issueCount = 0
        var committed = false
        val admitted = mutableListOf<AdmittedTutorOpenResponseLearningEvidence>()

        override suspend fun issueContext(
            request: TutorOpenResponseLearningContextRequest,
        ): TutorOpenResponseLearningContext? {
            issueCount += 1
            return issue(request)
        }

        override suspend fun submitAdmitted(
            evidence: AdmittedTutorOpenResponseLearningEvidence,
            lease: TutorOpenResponseLearningSubmissionLease,
        ): TutorOpenResponseLearningSubmissionReceipt {
            admitted += evidence
            committed = onSubmit(evidence, lease)
            return TutorOpenResponseLearningSubmissionReceipt.PENDING
        }

        override suspend fun retainUnresolvedIntent(
            intent: UnresolvedTutorOpenResponseLearningIntent,
            lease: TutorOpenResponseLearningSubmissionLease,
        ): TutorOpenResponseLearningSubmissionReceipt =
            TutorOpenResponseLearningSubmissionReceipt.RETAINED_FOR_RETRY
    }

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val EVIDENCE_REQUEST_ID = "evidence-request-current"
        const val ANSWER = "我不明白为什么加速度方向向左。"

        val QUESTION =
            QuestionDocument(
                id = "question-current",
                blocks =
                    listOf(
                        ContentBlock.Paragraph(
                            id = "question-block",
                            markdown = "物体向右运动并减速，判断加速度方向。",
                        ),
                    ),
            )

        fun request() =
            TutorOpenResponseLearningContextRequest(
                conversationId = "conversation-current",
                conversationGeneration = 4,
                conversationStateVersion = 8,
                questionDocumentId = QUESTION.id,
                questionRevisionNumber = 3,
                subject = SubjectKind.PHYSICS,
                questionDocument = QUESTION,
                explanationMode = TutorExplanationMode.GUIDED,
                modeVersion = 6,
                turnReferenceId = "turn-current",
                turnOrdinal = 5,
                turnGeneration = 7,
                evidenceRequestId = EVIDENCE_REQUEST_ID,
                attemptOrdinal = 2,
                hintCount = 1,
                answerWasRevealed = false,
                requestVersion = 10,
            )

        fun submission() =
            TutorOpenResponseLearningVisibleSubmission(
                contextRequest = request(),
                submissionId = "submission-current",
                sourceMessageId = "student-message-current",
                source = TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE,
                rawAnswer = ANSWER,
                elapsedDurationMillis = 15_000,
                occurredAtEpochMillis = 1_000,
            )

        fun issuedContext(
            request: TutorOpenResponseLearningContextRequest,
            questionRevisionNumber: Int = request.questionRevisionNumber,
        ) =
            TutorOpenResponseLearningContext(
                learnerId = LEARNER_ID,
                conversationId = request.conversationId,
                conversationGeneration = request.conversationGeneration,
                conversationStateVersion = request.conversationStateVersion,
                questionDocumentId = request.questionDocumentId,
                questionRevisionNumber = questionRevisionNumber,
                subject = request.subject,
                questionDocument = request.questionDocument,
                presentationFingerprint = fingerprint("presentation"),
                problemFingerprint = fingerprint("problem"),
                problemFamilyFingerprint = fingerprint("problem-family"),
                explanationMode = request.explanationMode,
                modeVersion = request.modeVersion,
                turnReferenceId = request.turnReferenceId,
                turnOrdinal = request.turnOrdinal,
                turnGeneration = request.turnGeneration,
                evidenceRequestId = request.evidenceRequestId,
                attemptOrdinal = request.attemptOrdinal,
                hintCount = request.hintCount,
                answerWasRevealed = request.answerWasRevealed,
                requestVersion = request.requestVersion,
            )

        fun fingerprint(seed: String): String =
            CanonicalSha256("feature-open-response-route-test")
                .field("seed", seed)
                .finish()
    }
}
