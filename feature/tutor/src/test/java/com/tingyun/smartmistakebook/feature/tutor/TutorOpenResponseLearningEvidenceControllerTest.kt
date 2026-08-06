package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TutorOpenResponseLearningEvidenceControllerTest {
    @Test
    fun guidedAnswerSchedulesAfterTheVisibleMessageAndCarriesExactHostFacts() = runTest {
        val submissions = RecordingSubmissionPort()
        val classifier = RecordingClassifier(DirectOpenResponseLearningIntent.NotSpecificLearningEvidence)
        val controller =
            TutorOpenResponseLearningEvidenceController(
                parentScope = backgroundScope,
                submissions = submissions,
                directIntentClassifier = classifier,
            )
        val context = context(TutorExplanationMode.GUIDED)
        val message = message(context, TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE)
        controller.bindCurrent(context)

        val dispatch = controller.submitVisibleMessage(message)

        assertTrue(dispatch is TutorOpenResponseLearningDispatch.Scheduled)
        assertTrue("Evidence work must not block message presentation", submissions.admitted.isEmpty())
        runCurrent()

        val admitted = submissions.admitted.single()
        assertEquals(message.rawAnswer, admitted.message.rawAnswer)
        assertEquals(context.conversationGeneration, admitted.message.context.conversationGeneration)
        assertEquals(context.questionRevisionNumber, admitted.message.context.questionRevisionNumber)
        assertEquals(context.modeVersion, admitted.message.context.modeVersion)
        assertEquals(context.turnGeneration, admitted.message.context.turnGeneration)
        assertEquals(context.attemptOrdinal, admitted.message.context.attemptOrdinal)
        assertEquals(context.hintCount, admitted.message.context.hintCount)
        assertEquals(context.answerWasRevealed, admitted.message.context.answerWasRevealed)
        assertEquals(
            context.evidenceRequestId,
            (admitted.admission as TutorOpenResponseEvidenceAdmission.GuidedFreeResponse)
                .evidenceRequestId,
        )
        assertEquals(0, classifier.callCount)
    }

    @Test
    fun directMessageIsSubmittedOnlyAfterModelClassifiesASpecificCurrentQuestionGap() = runTest {
        val submissions = RecordingSubmissionPort()
        val intentFingerprint = fingerprint("specific-gap")
        val classifier =
            RecordingClassifier(
                DirectOpenResponseLearningIntent.SpecificCurrentQuestionGap(intentFingerprint),
            )
        val controller =
            TutorOpenResponseLearningEvidenceController(
                backgroundScope,
                submissions,
                classifier,
            )
        val context = context(TutorExplanationMode.DIRECT)
        controller.bindCurrent(context)

        controller.submitVisibleMessage(
            message(context, TutorOpenResponseMessageSource.DIRECT_CONVERSATION),
        )
        runCurrent()

        assertEquals(1, classifier.callCount)
        assertEquals(1, submissions.admitted.size)
        assertEquals(
            intentFingerprint,
            (
                submissions.admitted.single().admission as
                    TutorOpenResponseEvidenceAdmission.DirectSpecificCurrentQuestionGap
                ).intentFingerprint,
        )
        assertTrue(submissions.unresolved.isEmpty())
    }

    @Test
    fun vagueOrUnrelatedDirectMessageDoesNotBecomeLearningEvidence() = runTest {
        val submissions = RecordingSubmissionPort()
        val controller =
            TutorOpenResponseLearningEvidenceController(
                backgroundScope,
                submissions,
                RecordingClassifier(
                    DirectOpenResponseLearningIntent.NotSpecificLearningEvidence,
                ),
            )
        val context = context(TutorExplanationMode.DIRECT)
        controller.bindCurrent(context)

        controller.submitVisibleMessage(
            message(context, TutorOpenResponseMessageSource.DIRECT_CONVERSATION),
        )
        advanceUntilIdle()

        assertTrue(submissions.admitted.isEmpty())
        assertTrue(submissions.unresolved.isEmpty())
    }

    @Test
    fun unknownDirectIntentIsRetainedForRetryButNeverSubmittedAsMasteryEvidence() = runTest {
        val submissions = RecordingSubmissionPort()
        val classificationRequestId = "intent-classification-request"
        val classifier =
            RecordingClassifier(
                DirectOpenResponseLearningIntent.Unknown(classificationRequestId),
            )
        val controller =
            TutorOpenResponseLearningEvidenceController(
                backgroundScope,
                submissions,
                classifier,
            )
        val context = context(TutorExplanationMode.DIRECT)
        controller.bindCurrent(context)

        controller.submitVisibleMessage(
            message(context, TutorOpenResponseMessageSource.DIRECT_CONVERSATION),
        )
        runCurrent()

        assertEquals(1, classifier.callCount)
        assertTrue(submissions.admitted.isEmpty())
        assertEquals(1, submissions.unresolved.size)
        assertEquals(
            classificationRequestId,
            submissions.unresolved.single().classificationRequestId,
        )
    }

    @Test
    fun intentClassifierFailureAlsoRetainsTheRawSourceForRetry() = runTest {
        val submissions = RecordingSubmissionPort()
        val controller =
            TutorOpenResponseLearningEvidenceController(
                backgroundScope,
                submissions,
                DirectOpenResponseLearningIntentClassifier { _, _ ->
                    error("model timeout")
                },
            )
        val context = context(TutorExplanationMode.DIRECT)
        controller.bindCurrent(context)

        controller.submitVisibleMessage(
            message(context, TutorOpenResponseMessageSource.DIRECT_CONVERSATION),
        )
        runCurrent()

        assertTrue(submissions.admitted.isEmpty())
        assertEquals(1, submissions.unresolved.size)
        assertTrue(
            submissions.unresolved.single().classificationRequestId
                .startsWith("direct-intent:"),
        )
    }

    @Test
    fun changingQuestionCancelsLateIntentAndPreventsAnyEvidenceHandoff() = runTest {
        val submissions = RecordingSubmissionPort()
        val classifierStarted = CompletableDeferred<Unit>()
        val releaseClassifier = CompletableDeferred<DirectOpenResponseLearningIntent>()
        val classifier =
            DirectOpenResponseLearningIntentClassifier { _, lease ->
                lease.requireCurrent()
                classifierStarted.complete(Unit)
                releaseClassifier.await()
            }
        val controller =
            TutorOpenResponseLearningEvidenceController(
                backgroundScope,
                submissions,
                classifier,
            )
        val first = context(TutorExplanationMode.DIRECT)
        controller.bindCurrent(first)
        controller.submitVisibleMessage(
            message(first, TutorOpenResponseMessageSource.DIRECT_CONVERSATION),
        )
        runCurrent()
        assertTrue(classifierStarted.isCompleted)

        controller.bindCurrent(
            context(
                mode = TutorExplanationMode.DIRECT,
                questionRevisionNumber = first.questionRevisionNumber + 1,
            ),
        )
        releaseClassifier.complete(
            DirectOpenResponseLearningIntent.SpecificCurrentQuestionGap(
                fingerprint("late-specific-gap"),
            ),
        )
        advanceUntilIdle()

        assertTrue(submissions.admitted.isEmpty())
        assertTrue(submissions.unresolved.isEmpty())
    }

    @Test
    fun clearingCurrentRevokesLeaseAlreadyHeldByTheSubmissionPort() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val submissions =
            object : OpenResponseLearningEvidenceSubmissionPort {
                override val learnerId: String = LEARNER_ID
                var committed = false

                override suspend fun submitAdmitted(
                    evidence: AdmittedTutorOpenResponseLearningEvidence,
                    lease: TutorOpenResponseLearningSubmissionLease,
                ): TutorOpenResponseLearningSubmissionReceipt {
                    lease.requireCurrent()
                    started.complete(Unit)
                    release.await()
                    if (lease.isCurrent()) committed = true
                    return TutorOpenResponseLearningSubmissionReceipt.PENDING
                }

                override suspend fun retainUnresolvedIntent(
                    intent: UnresolvedTutorOpenResponseLearningIntent,
                    lease: TutorOpenResponseLearningSubmissionLease,
                ): TutorOpenResponseLearningSubmissionReceipt =
                    TutorOpenResponseLearningSubmissionReceipt.RETAINED_FOR_RETRY
            }
        val controller =
            TutorOpenResponseLearningEvidenceController(
                backgroundScope,
                submissions,
                RecordingClassifier(
                    DirectOpenResponseLearningIntent.NotSpecificLearningEvidence,
                ),
            )
        val context = context(TutorExplanationMode.GUIDED)
        controller.bindCurrent(context)
        controller.submitVisibleMessage(
            message(context, TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE),
        )
        runCurrent()
        assertTrue(started.isCompleted)

        controller.clearCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertFalse(submissions.committed)
    }

    @Test
    fun staleContextAndCrossModeSourceNeverDispatch() = runTest {
        val submissions = RecordingSubmissionPort()
        val controller =
            TutorOpenResponseLearningEvidenceController(
                backgroundScope,
                submissions,
                RecordingClassifier(
                    DirectOpenResponseLearningIntent.NotSpecificLearningEvidence,
                ),
            )
        val current = context(TutorExplanationMode.GUIDED)
        controller.bindCurrent(current)
        val stale =
            context(
                mode = TutorExplanationMode.GUIDED,
                questionRevisionNumber = current.questionRevisionNumber + 1,
            )

        assertEquals(
            TutorOpenResponseLearningDispatch.NotCurrent,
            controller.submitVisibleMessage(
                message(stale, TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE),
            ),
        )
        val invalid =
            runCatching {
                message(current, TutorOpenResponseMessageSource.DIRECT_CONVERSATION)
            }.exceptionOrNull()
        assertTrue(invalid is IllegalArgumentException)
        advanceUntilIdle()
        assertTrue(submissions.admitted.isEmpty())
    }

    private class RecordingSubmissionPort :
        OpenResponseLearningEvidenceSubmissionPort {
        override val learnerId: String = LEARNER_ID
        val admitted = mutableListOf<AdmittedTutorOpenResponseLearningEvidence>()
        val unresolved = mutableListOf<UnresolvedTutorOpenResponseLearningIntent>()

        override suspend fun submitAdmitted(
            evidence: AdmittedTutorOpenResponseLearningEvidence,
            lease: TutorOpenResponseLearningSubmissionLease,
        ): TutorOpenResponseLearningSubmissionReceipt {
            lease.requireCurrent()
            admitted += evidence
            return TutorOpenResponseLearningSubmissionReceipt.PENDING
        }

        override suspend fun retainUnresolvedIntent(
            intent: UnresolvedTutorOpenResponseLearningIntent,
            lease: TutorOpenResponseLearningSubmissionLease,
        ): TutorOpenResponseLearningSubmissionReceipt {
            lease.requireCurrent()
            unresolved += intent
            return TutorOpenResponseLearningSubmissionReceipt.RETAINED_FOR_RETRY
        }
    }

    private class RecordingClassifier(
        private val result: DirectOpenResponseLearningIntent,
    ) : DirectOpenResponseLearningIntentClassifier {
        var callCount = 0

        override suspend fun classify(
            message: TutorOpenResponseLearningMessage,
            lease: TutorOpenResponseLearningSubmissionLease,
        ): DirectOpenResponseLearningIntent {
            lease.requireCurrent()
            callCount += 1
            return result
        }
    }

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val ANSWER = "我不明白为什么这里的加速度方向向左。"

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

        fun context(
            mode: TutorExplanationMode,
            questionRevisionNumber: Int = 3,
        ): TutorOpenResponseLearningContext =
            TutorOpenResponseLearningContext(
                learnerId = LEARNER_ID,
                conversationId = "conversation-current",
                conversationGeneration = 4,
                conversationStateVersion = 8,
                questionDocumentId = QUESTION.id,
                questionRevisionNumber = questionRevisionNumber,
                subject = SubjectKind.PHYSICS,
                questionDocument = QUESTION,
                presentationFingerprint = fingerprint("presentation"),
                problemFingerprint = fingerprint("problem"),
                problemFamilyFingerprint = fingerprint("problem-family"),
                explanationMode = mode,
                modeVersion = 6,
                turnReferenceId = "turn-current",
                turnOrdinal = 5,
                turnGeneration = 7,
                evidenceRequestId = "evidence-request-current",
                attemptOrdinal = 2,
                hintCount = 1,
                answerWasRevealed = false,
                requestVersion = 10,
            )

        fun message(
            context: TutorOpenResponseLearningContext,
            source: TutorOpenResponseMessageSource,
        ): TutorOpenResponseLearningMessage =
            TutorOpenResponseLearningMessage(
                context = context,
                submissionId = "submission-current",
                sourceMessageId = "student-message-current",
                source = source,
                rawAnswer = ANSWER,
                elapsedDurationMillis = 15_000,
                occurredAtEpochMillis = 1_000,
            )

        fun fingerprint(seed: String): String =
            CanonicalSha256("feature-open-response-learning-test")
                .field("seed", seed)
                .finish()
    }
}
