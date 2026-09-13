package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.AppliedAnswerRevealRecord
import com.tingyun.smartmistakebook.core.model.AppliedAttemptRecord
import com.tingyun.smartmistakebook.core.model.AppliedCorrectionRecord
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EventTimeTrust
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerEvent
import com.tingyun.smartmistakebook.core.model.LocalModelJudgedContract
import com.tingyun.smartmistakebook.core.model.LocalReviewSelfReportContract
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudyDatabaseInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() = runBlocking {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        store.seedFixture(baseSeed())
        store.saveAssessmentEvidenceSnapshot(evidenceSnapshot())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun retryDoesNotConsumeSequenceAndSequencesArePerLearner() = runBlocking {
        val firstCommand = attemptCommand(
            learnerId = LEARNER,
            submissionId = "submission-1",
            attemptId = "attempt-1",
            presentationId = "presentation-1",
        )
        val first = store.recordAttempt(firstCommand)
        val replay = store.recordAttempt(firstCommand)
        val second = store.recordAttempt(
            attemptCommand(
                learnerId = LEARNER,
                submissionId = "submission-2",
                attemptId = "attempt-2",
                presentationId = "presentation-2",
            ),
        )
        val otherLearner = store.recordAttempt(
            attemptCommand(
                learnerId = "learner-2",
                submissionId = "submission-other",
                attemptId = "attempt-other",
                presentationId = "presentation-other",
            ),
        )

        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(first.attempt, replay.attempt)
        assertEquals(1L, first.attempt.eventSequence)
        assertEquals(2L, second.attempt.eventSequence)
        assertEquals(1L, otherLearner.attempt.eventSequence)
        assertEquals(listOf(1L, 2L), store.loadLearningLedger(LEARNER).validPrefix.map { it.event.eventSequence })
        assertEquals(listOf(1L), store.loadLearningLedger("learner-2").validPrefix.map { it.event.eventSequence })
        assertEquals(first.attempt, store.readAttemptP0(first.attempt.attemptId)?.attempt)
    }

    @Test
    fun attemptAdvanceProofRequiresCanonicalAttemptAndUsesSnapshotPracticeUnit() = runBlocking {
        assertNull(store.findAttemptAdvanceProof("missing-attempt"))
        val command = attemptCommand(
            submissionId = "advance-proof-submission",
            attemptId = "advance-proof-attempt",
            presentationId = "advance-proof-presentation",
        )
        store.recordAttempt(command)

        assertEquals(
            AttemptAdvanceProofRecord(
                learnerId = LEARNER,
                attemptId = command.attemptId,
                submissionId = command.submissionId,
                presentationId = command.presentationId,
                practiceUnitId = UNIT_ID,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
            ),
            store.findAttemptAdvanceProof(command.attemptId),
        )
        assertNull(store.findAttemptAdvanceProof(command.submissionId))
    }

    @Test
    fun concurrentWritesAllocateOneGapFreeSequencePerLearner() = runBlocking {
        val results = (1..12).map { index ->
            async(Dispatchers.Default) {
                store.recordAttempt(
                    attemptCommand(
                        learnerId = LEARNER,
                        submissionId = "concurrent-submission-$index",
                        attemptId = "concurrent-attempt-$index",
                        presentationId = "concurrent-presentation-$index",
                        evidence = assistedEvidence(),
                        outcome = ProblemMemoryOutcome.ASSISTED_RECALL,
                    ),
                )
            }
        }.awaitAll()

        assertEquals((1L..12L).toList(), results.map { it.attempt.eventSequence }.sorted())
        val ledger = store.loadLearningLedger(LEARNER)
        assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)
        assertEquals((1L..12L).toList(), ledger.validPrefix.map { it.event.eventSequence })
    }

    @Test
    fun revealIsUniqueRetryableAndCanonicalizesLaterResponsesFromDatabaseOrder() = runBlocking {
        val revealCommand = answerRevealCommand(
            assessmentEventId = "reveal-event-1",
            presentationId = "reveal-presentation",
        )
        val reveal = store.recordAnswerReveal(revealCommand)
        val replay = store.recordAnswerReveal(revealCommand)

        val forgedIndependent = store.recordAttempt(
            attemptCommand(
                submissionId = "forged-independent-after-reveal",
                attemptId = "forged-independent-after-reveal",
                presentationId = "reveal-presentation",
            ),
        )
        val forgedReplay = store.recordAttempt(
            attemptCommand(
                submissionId = "forged-independent-after-reveal",
                attemptId = "forged-independent-after-reveal",
                presentationId = "reveal-presentation",
            ),
        )
        val wrongAfterReveal = store.recordAttempt(
            attemptCommand(
                submissionId = "wrong-after-reveal",
                attemptId = "wrong-after-reveal",
                presentationId = "reveal-presentation",
                evidence = negativeEvidence(),
                outcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
            ),
        )
        val secondReveal = runCatching {
            store.recordAnswerReveal(
                answerRevealCommand(
                    assessmentEventId = "reveal-event-2",
                    presentationId = "reveal-presentation",
                ),
            )
        }

        assertTrue(reveal.created)
        assertFalse(replay.created)
        assertEquals(reveal.outcome, replay.outcome)
        assertEquals(1, forgedIndependent.attempt.responseOrdinal)
        assertFalse(forgedReplay.created)
        assertEquals(forgedIndependent.attempt, forgedReplay.attempt)
        assertEquals(LearningEvidenceDirection.NONE, forgedIndependent.attempt.evidence.direction)
        assertEquals(
            LearningEvidenceReason.ANSWER_REVEALED,
            forgedIndependent.attempt.evidence.reason,
        )
        assertEquals(ProblemMemoryOutcome.ANSWER_REVEALED, forgedIndependent.attempt.problemMemoryOutcome)
        assertEquals(2, wrongAfterReveal.attempt.responseOrdinal)
        assertEquals(0.6, wrongAfterReveal.attempt.evidence.weight, 0.0)
        assertEquals(
            LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
            wrongAfterReveal.attempt.evidence.reason,
        )
        assertEquals(3L, wrongAfterReveal.attempt.eventSequence)
        assertTrue(secondReveal.isFailure)
        assertEquals(3, store.loadLearningLedger(LEARNER).validPrefix.size)

        val batch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(3L, batch.ledgerHeadSequence)
        assertEquals(ProjectionBatchStopReason.END_OF_LEDGER, batch.stopReason)
        assertEquals(
            listOf(reveal.outcome.outcomeId, "forged-independent-after-reveal", "wrong-after-reveal"),
            batch.events.map { it.event.ledgerEventId },
        )

        val forgedCorrectionCommand = correctionCommand(
            submissionId = forgedIndependent.submissionId,
            attemptId = forgedIndependent.attempt.attemptId,
            correctionId = "forged-independent-correction-after-reveal",
            replacementEvidence = independentEvidence(),
            replacementOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
        )
        val forgedCorrection = store.appendAttemptCorrection(forgedCorrectionCommand)
        val forgedCorrectionReplay = store.appendAttemptCorrection(forgedCorrectionCommand)
        assertFalse(forgedCorrectionReplay.created)
        assertEquals(forgedCorrection.correction, forgedCorrectionReplay.correction)
        assertEquals(
            LearningEvidenceReason.ANSWER_REVEALED,
            forgedCorrection.correction.replacementEvidence.reason,
        )
        assertEquals(
            ProblemMemoryOutcome.ANSWER_REVEALED,
            forgedCorrection.correction.replacementMemoryOutcome,
        )
    }

    @Test
    fun attemptReplayRequiresExactSubmittedResponseBeforeRevealCanonicalization() = runBlocking {
        val presentationId = "response-replay-presentation"
        store.recordAnswerReveal(
            answerRevealCommand(
                assessmentEventId = "response-replay-reveal",
                presentationId = presentationId,
            ),
        )
        val command = attemptCommand(
            submissionId = "response-replay-submission",
            attemptId = "response-replay-attempt",
            presentationId = presentationId,
        )

        val written = store.recordAttempt(command)

        assertEquals(command.submittedResponse, written.attempt.submittedResponse)
        assertEquals(LearningEvidenceReason.ANSWER_REVEALED, written.attempt.evidence.reason)
        val changedTime = command.occurredAtEpochMillis + 1
        val conflictingCommands = listOf(
            command.copy(
                submittedResponse = command.submittedResponse.copy(choiceId = "choice-b"),
            ),
            command.copy(
                submittedResponse = command.submittedResponse.copy(
                    choiceMarkdown = "原始选项 B",
                ),
            ),
            command.copy(
                occurredAtEpochMillis = changedTime,
                studyDay = studyDay(changedTime),
                submittedResponse = command.submittedResponse.copy(
                    submittedAtEpochMillis = changedTime,
                ),
            ),
        )
        conflictingCommands.forEach { conflicting ->
            val failure = runCatching { store.recordAttempt(conflicting) }
            assertTrue(failure.exceptionOrNull() is AttemptIdempotencyConflictException)
        }
        assertEquals(2, store.loadLearningLedger(LEARNER).validPrefix.size)
    }

    @Test
    fun legacyRevealAssessmentEventIsReconciledExactlyOnceBeforeTheNextResponse() = runBlocking {
        val command = answerRevealCommand(
            assessmentEventId = "legacy-orphan-reveal",
            presentationId = "legacy-orphan-presentation",
        )
        assertTrue(
            store.database.attemptTransactionDao()
                .importLegacyAnswerRevealAssessmentEvent(command),
        )
        assertTrue(store.loadLearningLedger(LEARNER).validPrefix.isEmpty())

        val reconciled = store.reconcileAnswerRevealOutcomes(LEARNER)
        assertEquals(1, reconciled.size)
        assertTrue(reconciled.single().created)
        assertEquals(1L, reconciled.single().outcome.eventSequence)
        assertTrue(store.reconcileAnswerRevealOutcomes(LEARNER).isEmpty())

        val afterReveal = store.recordAttempt(
            attemptCommand(
                submissionId = "legacy-orphan-response",
                attemptId = "legacy-orphan-response",
                presentationId = "legacy-orphan-presentation",
            ),
        )
        assertEquals(1, afterReveal.attempt.responseOrdinal)
        assertEquals(2L, afterReveal.attempt.eventSequence)
        assertEquals(LearningEvidenceReason.ANSWER_REVEALED, afterReveal.attempt.evidence.reason)
    }

    @Test
    fun correctionForcesFullReplayAndRejectsIncompatibleLaterResponseCorrection() = runBlocking {
        val first = store.recordAttempt(
            attemptCommand(
                submissionId = "correction-submission-1",
                attemptId = "correction-attempt-1",
                presentationId = "correction-presentation",
            ),
        )
        val correctionCommand = correctionCommand(
            submissionId = first.submissionId,
            attemptId = first.attempt.attemptId,
            correctionId = "correction-1",
        )
        val correction = store.appendAttemptCorrection(correctionCommand)
        val replay = store.appendAttemptCorrection(correctionCommand)
        val batch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)

        assertTrue(correction.created)
        assertFalse(replay.created)
        assertEquals(correction.correction, replay.correction)
        assertEquals(
            first.attempt.submittedResponse,
            store.readAttemptP0(first.attempt.attemptId)?.attempt?.submittedResponse,
        )
        assertEquals(ProjectionBatchStopReason.FULL_REPLAY_REQUIRED, batch.stopReason)
        assertEquals(listOf(first.attempt.attemptId), batch.events.map { it.event.ledgerEventId })
        assertEquals(correction.correction.eventSequence, batch.blockedAtSequence)

        val second = store.recordAttempt(
            attemptCommand(
                submissionId = "correction-submission-2",
                attemptId = "correction-attempt-2",
                presentationId = "correction-presentation",
                evidence = assistedEvidence(),
                outcome = ProblemMemoryOutcome.ASSISTED_RECALL,
            ),
        )
        assertEquals(2, second.attempt.responseOrdinal)
        val incompatible = runCatching {
            store.appendAttemptCorrection(
                correctionCommand(
                    submissionId = second.submissionId,
                    attemptId = second.attempt.attemptId,
                    correctionId = "illegal-independent-correction",
                    replacementEvidence = independentEvidence(),
                    replacementOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
                ),
            )
        }
        assertTrue(incompatible.isFailure)
        assertEquals(3L, store.loadLearningLedger(LEARNER).validPrefix.last().event.eventSequence)
    }

    @Test
    fun projectionCommitUsesExactPrefixHeadAndStateVersionCas() = runBlocking {
        val first = store.recordAttempt(
            attemptCommand(
                submissionId = "projection-submission-1",
                attemptId = "projection-attempt-1",
                presentationId = "projection-presentation-1",
            ),
        )
        val second = store.recordAttempt(
            attemptCommand(
                submissionId = "projection-submission-2",
                attemptId = "projection-attempt-2",
                presentationId = "projection-presentation-2",
            ),
        )
        val limited = store.loadProjectionBatch(PROJECTION, LEARNER, 1)
        assertEquals(ProjectionBatchStopReason.LIMIT_REACHED, limited.stopReason)
        assertEquals(2L, limited.ledgerHeadSequence)

        val waitingSnapshot = learnerSnapshot(
            checkpoint = 1,
            knownHead = 2,
            status = ProjectionStatus.WAITING_FOR_GAP,
            freshness = LearnerSnapshotFreshness.STALE,
            attempts = listOf(first),
        )
        val firstCommit = store.commitProjection(
            projectionCommit(
                previousCheckpoint = 0,
                previousStateVersion = 0,
                mode = ProjectionCommitMode.INCREMENTAL,
                events = limited.events,
                snapshot = waitingSnapshot,
            ),
        )
        assertEquals(1L, firstCommit.stateVersion)
        assertEquals(2L, firstCommit.knownLedgerHeadSequence)

        val remaining = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(listOf(second.attempt.attemptId), remaining.events.map { it.event.ledgerEventId })
        val currentSnapshot = learnerSnapshot(
            checkpoint = 2,
            knownHead = 2,
            attempts = listOf(first, second),
        )
        val finalCommit = store.commitProjection(
            projectionCommit(
                previousCheckpoint = 1,
                previousStateVersion = 1,
                mode = ProjectionCommitMode.INCREMENTAL,
                events = remaining.events,
                snapshot = currentSnapshot,
            ),
        )
        assertEquals(currentSnapshot, finalCommit.snapshot)
        assertEquals(finalCommit, store.readCurrentLearnerSnapshot(PROJECTION, LEARNER))

        val staleCas = runCatching {
            store.commitProjection(
                projectionCommit(
                    previousCheckpoint = 1,
                    previousStateVersion = 1,
                    mode = ProjectionCommitMode.INCREMENTAL,
                    events = remaining.events,
                    snapshot = currentSnapshot,
                ),
            )
        }
        assertTrue(staleCas.isFailure)

        store.recordAttempt(
            attemptCommand(
                submissionId = "projection-submission-3",
                attemptId = "projection-attempt-3",
                presentationId = "projection-presentation-3",
            ),
        )
        val falseCurrent = runCatching {
            store.commitProjection(
                ProjectionCommit(
                    projectionName = PROJECTION,
                    learnerId = LEARNER,
                    expectedPreviousCheckpoint = 2,
                    expectedPreviousStateVersion = 2,
                    mode = ProjectionCommitMode.INCREMENTAL,
                    knownLedgerHeadSequence = 2,
                    consumedLedgerEvents = emptyList(),
                    presentationProjectionStates = emptyMap(),
                    snapshot = currentSnapshot,
                ),
            )
        }
        assertTrue(falseCurrent.isFailure)
    }

    @Test
    fun fullReplayCommitPersistsCorrectionAndAnswerRevealWindows() = runBlocking {
        val attempt = store.recordAttempt(
            attemptCommand(
                submissionId = "full-replay-submission",
                attemptId = "full-replay-attempt",
                presentationId = "full-replay-presentation",
            ),
        )
        val reveal = store.recordAnswerReveal(
            answerRevealCommand("full-replay-reveal", "full-replay-presentation"),
        )
        val correction = store.appendAttemptCorrection(
            correctionCommand(
                submissionId = attempt.submissionId,
                attemptId = attempt.attempt.attemptId,
                correctionId = "full-replay-correction",
            ),
        )
        val ledger = store.loadLearningLedger(LEARNER)
        assertEquals(LearningLedgerReadStatus.COMPLETE, ledger.status)

        val snapshot = learnerSnapshot(
            checkpoint = 3,
            knownHead = 3,
            correctionWatermarkEpochMillis = correction.correction.occurredAtEpochMillis,
            masteryStates = mapOf(KNOWLEDGE_ID to untrustedMasteryState(checkpoint = 3)),
            attempts = listOf(attempt),
            corrections = listOf(correction),
            reveals = listOf(reveal),
        )
        val persisted = store.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION,
                learnerId = LEARNER,
                expectedPreviousCheckpoint = 0,
                expectedPreviousStateVersion = 0,
                mode = ProjectionCommitMode.FULL_REPLAY,
                knownLedgerHeadSequence = 3,
                consumedLedgerEvents = ledger.validPrefix.map { it.toReceipt() },
                presentationProjectionStates = projectedPresentationStates(
                    ledger.validPrefix.map { it.event },
                ),
                snapshot = snapshot,
            ),
        )
        assertEquals(snapshot, persisted.snapshot)
        assertEquals(
            correction.canonicalFingerprint,
            persisted.snapshot.appliedCorrectionRecords.getValue(correction.correction.correctionId)
                .canonicalFingerprint,
        )
        assertEquals(
            reveal.canonicalFingerprint,
            persisted.snapshot.appliedAnswerRevealRecords.getValue(reveal.outcome.outcomeId)
                .canonicalFingerprint,
        )
        assertFalse(
            persisted.snapshot.knowledgeMasteryStates.getValue(KNOWLEDGE_ID)
                .independentCorrectObservations.single().isStudyDayTrusted,
        )
    }

    @Test
    fun presentationAuthorityOutlivesBoundedAppliedEventAuditWindows() = runBlocking {
        val reveal = store.recordAnswerReveal(
            answerRevealCommand("authority-reveal", "authority-presentation"),
        )
        val revealBatch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        val revealSnapshot = learnerSnapshot(
            checkpoint = 1,
            knownHead = 1,
            reveals = listOf(reveal),
        )
        store.commitProjection(
            projectionCommit(
                previousCheckpoint = 0,
                previousStateVersion = 0,
                mode = ProjectionCommitMode.INCREMENTAL,
                events = revealBatch.events,
                snapshot = revealSnapshot,
            ),
        )

        val auditEvicted = revealSnapshot.copy(appliedAnswerRevealRecords = emptyMap())
        store.commitProjection(
            ProjectionCommit(
                projectionName = PROJECTION,
                learnerId = LEARNER,
                expectedPreviousCheckpoint = 1,
                expectedPreviousStateVersion = 1,
                mode = ProjectionCommitMode.INCREMENTAL,
                knownLedgerHeadSequence = 1,
                consumedLedgerEvents = emptyList(),
                presentationProjectionStates = emptyMap(),
                snapshot = auditEvicted,
            ),
        )

        val lateResponse = store.recordAttempt(
            attemptCommand(
                submissionId = "authority-late-response",
                attemptId = "authority-late-response",
                presentationId = "authority-presentation",
            ),
        )
        val responseBatch = store.loadProjectionBatch(PROJECTION, LEARNER, 10)
        assertEquals(
            PresentationProjectionState(
                presentationId = "authority-presentation",
                asOfLedgerSequence = 1,
                memoryProjectionApplied = true,
                answerRevealSequence = 1,
            ),
            responseBatch.authoritativePresentationStates.getValue("authority-presentation"),
        )

        val responseSnapshot = learnerSnapshot(
            checkpoint = 2,
            knownHead = 2,
            attempts = listOf(lateResponse),
        )
        val responseCommit = projectionCommit(
            previousCheckpoint = 1,
            previousStateVersion = 2,
            mode = ProjectionCommitMode.INCREMENTAL,
            events = responseBatch.events,
            snapshot = responseSnapshot,
        ).copy(
            presentationProjectionStates = projectedPresentationStates(
                events = responseBatch.events.map { it.event },
                initial = responseBatch.authoritativePresentationStates,
            ),
        )
        store.commitProjection(responseCommit)
        assertTrue(
            store.readCurrentLearnerSnapshot(PROJECTION, LEARNER)
                ?.snapshot
                ?.appliedAnswerRevealRecords
                .isNullOrEmpty(),
        )
    }

    @Test
    fun recordReviewAttemptCommitsAttemptAndAdvanceAtomically() = runBlocking {
        val (review, session) = saveStartedReview(
            fingerprint = "record-review-success",
            localDay = 20_010,
            isCurrent = true,
        )
        val command = reviewAttemptCommand(review, session, "success")

        val result = store.recordReviewAttempt(command)

        assertTrue(result.attempt.created)
        assertTrue(result.advance.created)
        assertEquals(result.attempt.attempt.attemptId, result.advance.receipt.attemptId)
        assertEquals(result.attempt.submissionId, result.advance.receipt.submissionId)
        assertEquals(result.attempt.attempt.presentationId, result.advance.receipt.presentationId)
        assertEquals(command.attempt.submittedResponse, result.attempt.attempt.submittedResponse)
        assertEquals(
            result.attempt.attempt.occurredAtEpochMillis,
            result.advance.receipt.occurredAtEpochMillis,
        )
        assertEquals(StudyDbValue.ReviewStatus.COMPLETED, result.advance.session.status)
        assertEquals(1L, result.advance.session.stateVersion)
        val persistence = requireNotNull(store.findAttemptPersistence(result.attempt.submissionId))
        assertEquals(1, persistence.attemptEventCount)
        assertEquals(1, persistence.outboxCount)
    }

    @Test
    fun recordReviewAttemptRollsBackAttemptWhenAdvanceFails() = runBlocking {
        val (review, session) = saveStartedReview(
            fingerprint = "record-review-rollback",
            localDay = 20_011,
        )
        val command = reviewAttemptCommand(review, session, "rollback").copy(
            reviewQueueItemId = "missing-review-queue-item",
        )

        val failure = runCatching { store.recordReviewAttempt(command) }

        assertTrue(failure.exceptionOrNull() is ImmutablePayloadConflictException)
        assertNull(store.findAttemptPersistence(command.attempt.submissionId))
        assertNull(store.findAttemptAdvanceProof(command.attempt.attemptId))
        assertNull(store.readAttemptP0(command.attempt.attemptId))
        assertTrue(store.loadLearningLedger(LEARNER).validPrefix.isEmpty())
        assertEquals(
            session,
            store.observeReviewPlan(review.plan.reviewPlanId).first()?.activeSession,
        )
    }

    @Test
    fun recordReviewAttemptExactReplayIsIdempotent() = runBlocking {
        val (review, session) = saveStartedReview(
            fingerprint = "record-review-replay",
            localDay = 20_012,
        )
        val command = reviewAttemptCommand(review, session, "replay")

        val first = store.recordReviewAttempt(command)
        val replay = store.recordReviewAttempt(command)

        assertTrue(first.attempt.created)
        assertTrue(first.advance.created)
        assertFalse(replay.attempt.created)
        assertFalse(replay.advance.created)
        assertEquals(first.attempt, replay.attempt.copy(created = true))
        assertEquals(first.advance, replay.advance.copy(created = true))
        val persistence = requireNotNull(store.findAttemptPersistence(command.attempt.submissionId))
        assertEquals(1, persistence.attemptEventCount)
        assertEquals(1, persistence.outboxCount)
        assertEquals(1, store.loadLearningLedger(LEARNER).validPrefix.size)
    }

    @Test
    fun recordReviewAttemptRejectsAttemptPreviouslyWrittenOutsideReviewTransaction() = runBlocking {
        val (review, session) = saveStartedReview(
            fingerprint = "record-review-plain-attempt",
            localDay = 20_016,
        )
        val command = reviewAttemptCommand(review, session, "plain-attempt")
        store.recordAttempt(command.attempt)

        val failure = runCatching { store.recordReviewAttempt(command) }

        assertTrue(failure.exceptionOrNull() is ImmutablePayloadConflictException)
        assertEquals(
            session,
            store.observeReviewPlan(review.plan.reviewPlanId).first()?.activeSession,
        )
        assertEquals(1, store.loadLearningLedger(LEARNER).validPrefix.size)
    }

    @Test
    fun directAdvanceRejectsAttemptWrittenOutsideReviewTransaction() = runBlocking {
        val (review, session) = saveStartedReview(
            fingerprint = "direct-review-plain-attempt",
            localDay = 20_017,
        )
        val reviewCommand = reviewAttemptCommand(review, session, "direct-plain-attempt")
        val attempt = store.recordAttempt(reviewCommand.attempt)

        val failure = runCatching {
            store.advanceReviewSession(
                ReviewSessionAdvanceCommand(
                    sessionId = session.reviewSessionId,
                    expectedStateVersion = session.stateVersion,
                    reviewQueueItemId = review.queue.single().reviewQueueItemId,
                    practiceUnitId = review.queue.single().practiceUnitId,
                    attemptId = attempt.attempt.attemptId,
                    submissionId = attempt.submissionId,
                    presentationId = attempt.attempt.presentationId,
                    occurredAtEpochMillis = attempt.attempt.occurredAtEpochMillis,
                ),
            )
        }

        assertTrue(failure.exceptionOrNull() is ImmutablePayloadConflictException)
        assertEquals(
            session,
            store.observeReviewPlan(review.plan.reviewPlanId).first()?.activeSession,
        )
    }

    @Test
    fun concurrentSessionCreationAllowsOnlyOneActiveSessionPerLearner() = runBlocking {
        val reviews = listOf(
            reviewBundle("learner-active-one", localDay = 20_018, isCurrent = false),
            reviewBundle("learner-active-two", localDay = 20_019, isCurrent = false),
        )
        reviews.forEach { store.saveReviewPlan(it) }
        val sessions = reviews.mapIndexed { index, review ->
            ReviewSessionRecord(
                reviewSessionId = "learner-active-session-$index",
                reviewPlanId = review.plan.reviewPlanId,
                status = StudyDbValue.ReviewStatus.IN_PROGRESS,
                startedAtEpochMillis = OCCURRED_AT + index,
                lastActiveAtEpochMillis = OCCURRED_AT + index,
                completedAtEpochMillis = null,
                currentOrdinal = 0,
                timeBudgetSeconds = review.plan.timeBudgetSeconds,
                projectionCheckpoint = review.plan.projectionCheckpoint,
            )
        }

        val outcomes = sessions.map { session ->
            async(Dispatchers.Default) { runCatching { store.saveReviewSession(session) } }
        }.awaitAll()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.exceptionOrNull() is ImmutablePayloadConflictException })
        val active = requireNotNull(store.observeActiveReviewPlan(LEARNER).first()?.activeSession)
        assertTrue(active.reviewSessionId in sessions.map { it.reviewSessionId })
    }

    @Test
    fun observeReviewPlanForSessionReturnsOwningAggregate() = runBlocking {
        val (review, session) = saveStartedReview(
            fingerprint = "session-plan-query",
            localDay = 20_013,
            isCurrent = true,
        )

        assertEquals(
            review.copy(activeSession = session, latestSession = session),
            store.observeReviewPlanForSession(session.reviewSessionId).first(),
        )
        assertNull(store.observeReviewPlanForSession("missing-review-session").first())
    }

    @Test
    fun completedReviewDaysAreDistinctOrderedAndRemainContinuousAcrossZones() = runBlocking {
        listOf(
            20_022L to ZONE,
            20_020L to "UTC",
            20_021L to ZONE,
        ).forEach { (localDay, timeZoneId) ->
            val (review, session) = saveStartedReview(
                fingerprint = "completed-day-$localDay",
                localDay = localDay,
                timeZoneId = timeZoneId,
            )
            store.recordReviewAttempt(
                reviewAttemptCommand(review, session, "completed-day-$localDay"),
            )
        }

        assertEquals(
            listOf(20_022L, 20_021L),
            store.observeCompletedReviewLocalDays(
                learnerId = LEARNER,
                limit = 2,
            ).first(),
        )
    }

    @Test
    fun legacyMultipleActiveSessionsFailClosedWithoutAdvancingEither() = runBlocking {
        val reviews = listOf(
            reviewBundle("legacy-active-older", localDay = 20_014, isCurrent = false),
            reviewBundle("legacy-active-newer", localDay = 20_015, isCurrent = false),
        )
        val sessions = reviews.mapIndexed { index, review ->
            ReviewSessionRecord(
                reviewSessionId = "legacy-active-session-$index",
                reviewPlanId = review.plan.reviewPlanId,
                status = StudyDbValue.ReviewStatus.IN_PROGRESS,
                startedAtEpochMillis = OCCURRED_AT + index,
                lastActiveAtEpochMillis = OCCURRED_AT + index,
                completedAtEpochMillis = null,
                currentOrdinal = 0,
                timeBudgetSeconds = review.plan.timeBudgetSeconds,
                projectionCheckpoint = review.plan.projectionCheckpoint,
            )
        }
        store.seedFixture(
            baseSeed().copy(
                reviewPlans = reviews.map { it.plan },
                reviewQueueItems = reviews.flatMap { it.queue },
                reviewSessions = sessions,
            ),
        )

        val observationFailure = runCatching { store.observeActiveReviewPlan(LEARNER).first() }
        assertTrue(observationFailure.exceptionOrNull() is ImmutablePayloadConflictException)

        val advanceCommand = reviewAttemptCommand(reviews.last(), sessions.last(), "legacy-active")
        val advanceFailure = runCatching { store.recordReviewAttempt(advanceCommand) }
        assertTrue(advanceFailure.exceptionOrNull() is ImmutablePayloadConflictException)
        assertNull(store.findAttemptPersistence(advanceCommand.attempt.submissionId))
        reviews.zip(sessions).forEach { (review, session) ->
            assertEquals(
                session,
                store.observeReviewPlan(review.plan.reviewPlanId).first()?.activeSession,
            )
        }
    }

    @Test
    fun reviewBundleKeepsFrozenQueueHistoryAndCurrentSlotConsistent() = runBlocking {
        val first = reviewBundle("plan-fingerprint-1", localDay = 20_000, isCurrent = true)
        store.saveReviewPlan(first)
        assertEquals(first, store.observeReviewPlan(first.plan.reviewPlanId).first())
        assertEquals(
            first,
            store.observeCurrentReviewPlan(LEARNER, 20_000, ZONE).first(),
        )
        store.saveReviewPlan(first)

        val second = reviewBundle("plan-fingerprint-2", localDay = 20_000, isCurrent = true)
        store.saveReviewPlan(second)
        assertEquals(second, store.observeCurrentReviewPlan(LEARNER, 20_000, ZONE).first())
        assertFalse(requireNotNull(store.observeReviewPlan(first.plan.reviewPlanId).first()).isCurrent)
        assertTrue(requireNotNull(store.observeReviewPlan(second.plan.reviewPlanId).first()).isCurrent)

        val otherDay = reviewBundle("plan-fingerprint-3", localDay = 20_001, isCurrent = true)
        store.saveReviewPlan(otherDay)
        assertEquals(second, store.observeCurrentReviewPlan(LEARNER, 20_000, ZONE).first())
        assertEquals(otherDay, store.observeCurrentReviewPlan(LEARNER, 20_001, ZONE).first())

        val started = ReviewSessionRecord(
            reviewSessionId = "review-session-1",
            reviewPlanId = second.plan.reviewPlanId,
            status = StudyDbValue.ReviewStatus.IN_PROGRESS,
            startedAtEpochMillis = OCCURRED_AT,
            lastActiveAtEpochMillis = OCCURRED_AT,
            completedAtEpochMillis = null,
            currentOrdinal = 0,
            timeBudgetSeconds = second.plan.timeBudgetSeconds,
            projectionCheckpoint = second.plan.projectionCheckpoint,
        )
        store.saveReviewSession(started)
        store.saveReviewSession(started)
        assertEquals(
            started,
            store.observeReviewPlan(second.plan.reviewPlanId).first()?.activeSession,
        )
        assertEquals(
            started,
            store.observeReviewPlan(second.plan.reviewPlanId).first()?.latestSession,
        )
        assertTrue(
            runCatching {
                store.saveReviewSession(
                    started.copy(reviewSessionId = "review-session-concurrent"),
                )
            }.isFailure,
        )

        val reviewCommand = reviewAttemptCommand(second, started, "bundle-history")
        val advance = store.recordReviewAttempt(reviewCommand).advance
        assertTrue(advance.created)
        assertEquals(0, advance.receipt.fromVersion)
        assertEquals(1, advance.receipt.toVersion)
        assertEquals(reviewCommand.attempt.attemptId, advance.receipt.attemptId)
        val completed = started.copy(
            status = StudyDbValue.ReviewStatus.COMPLETED,
            lastActiveAtEpochMillis = reviewCommand.attempt.occurredAtEpochMillis,
            completedAtEpochMillis = reviewCommand.attempt.occurredAtEpochMillis,
            currentOrdinal = 1,
            stateVersion = 1,
        )
        assertEquals(completed, advance.session)
        store.saveReviewSession(started)
        assertEquals(
            null,
            store.observeReviewPlan(second.plan.reviewPlanId).first()?.activeSession,
        )
        assertEquals(
            completed,
            store.observeReviewPlan(second.plan.reviewPlanId).first()?.latestSession,
        )
        val replay = store.recordReviewAttempt(reviewCommand).advance
        assertFalse(replay.created)
        assertEquals(advance, replay.copy(created = true))
        assertTrue(
            runCatching {
                store.recordReviewAttempt(
                    reviewCommand.copy(
                        attempt = reviewCommand.attempt.copy(
                            occurredAtEpochMillis = reviewCommand.attempt.occurredAtEpochMillis + 1,
                        ),
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                store.saveReviewSession(completed)
            }.isFailure,
        )
        val completedPlan = store.observeReviewPlan(second.plan.reviewPlanId).first()
        assertNull(completedPlan?.activeSession)
        assertEquals(completed, completedPlan?.latestSession)

        val conflict = runCatching {
            store.saveReviewPlan(
                first.copy(plan = first.plan.copy(timeBudgetSeconds = 901)),
            )
        }
        assertTrue(conflict.isFailure)
    }

    @Test
    fun reviewAdvanceCasAndReceiptCommitTogetherUnderCompetition() = runBlocking {
        val review = reviewBundle(
            fingerprint = "atomic-review-plan",
            localDay = 20_002,
            isCurrent = false,
        )
        store.saveReviewPlan(review)
        val started = ReviewSessionRecord(
            reviewSessionId = "atomic-review-session",
            reviewPlanId = review.plan.reviewPlanId,
            status = StudyDbValue.ReviewStatus.IN_PROGRESS,
            startedAtEpochMillis = OCCURRED_AT,
            lastActiveAtEpochMillis = OCCURRED_AT,
            completedAtEpochMillis = null,
            currentOrdinal = 0,
            timeBudgetSeconds = review.plan.timeBudgetSeconds,
            projectionCheckpoint = review.plan.projectionCheckpoint,
        )
        store.saveReviewSession(started)
        val commands = (1..2).map { index ->
            reviewAttemptCommand(review, started, "competition-$index")
        }
        val outcomes = commands.map { command ->
            async(Dispatchers.Default) { runCatching { store.recordReviewAttempt(command) } }
        }.awaitAll()
        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })

        val winnerIndex = outcomes.indexOfFirst { it.isSuccess }
        val winning = outcomes[winnerIndex].getOrThrow().advance
        assertTrue(winning.created)
        assertEquals(StudyDbValue.ReviewStatus.COMPLETED, winning.session.status)
        assertEquals(1, winning.session.currentOrdinal)
        assertEquals(1, winning.session.stateVersion)
        assertFalse(store.recordReviewAttempt(commands[winnerIndex]).advance.created)

        val loserIndex = 1 - winnerIndex
        assertNull(store.findAttemptPersistence(commands[loserIndex].attempt.submissionId))
        val secondReview = reviewBundle(
            fingerprint = "atomic-review-rollback-plan",
            localDay = 20_003,
            isCurrent = false,
        )
        store.saveReviewPlan(secondReview)
        val secondStarted = started.copy(
            reviewSessionId = "atomic-review-rollback-session",
            reviewPlanId = secondReview.plan.reviewPlanId,
        )
        store.saveReviewSession(secondStarted)
        val rolledBackAttempt = store.recordReviewAttempt(
            commands[loserIndex].copy(
                sessionId = secondStarted.reviewSessionId,
                reviewQueueItemId = secondReview.queue.single().reviewQueueItemId,
                practiceUnitId = secondReview.queue.single().practiceUnitId,
            ),
        ).advance
        assertTrue(rolledBackAttempt.created)
        assertEquals(commands[loserIndex].attempt.attemptId, rolledBackAttempt.receipt.attemptId)
    }

    @Test
    fun reviewSessionFixtureRetryUsesImmutableRevisionAfterProgress() = runBlocking {
        val review = reviewBundle(
            fingerprint = "fixture-session-plan",
            localDay = 20_002,
            isCurrent = false,
        )
        val started = ReviewSessionRecord(
            reviewSessionId = "fixture-review-session",
            reviewPlanId = review.plan.reviewPlanId,
            status = StudyDbValue.ReviewStatus.IN_PROGRESS,
            startedAtEpochMillis = OCCURRED_AT,
            lastActiveAtEpochMillis = OCCURRED_AT,
            completedAtEpochMillis = null,
            currentOrdinal = 0,
            timeBudgetSeconds = review.plan.timeBudgetSeconds,
            projectionCheckpoint = review.plan.projectionCheckpoint,
        )
        val fixture = baseSeed().copy(
            reviewPlans = listOf(review.plan),
            reviewQueueItems = review.queue,
            reviewSessions = listOf(started),
        )
        assertTrue(
            runCatching {
                store.seedFixture(
                    fixture.copy(
                        reviewSessions = listOf(started.copy(currentOrdinal = 2)),
                    ),
                )
            }.isFailure,
        )
        store.seedFixture(fixture)

        assertTrue(
            runCatching {
                store.seedFixture(
                    fixture.copy(
                        reviewSessions = listOf(
                            started.copy(
                                status = StudyDbValue.ReviewStatus.COMPLETED,
                                completedAtEpochMillis = OCCURRED_AT,
                            ),
                        ),
                    ),
                )
            }.isFailure,
        )
        val advanced = store.recordReviewAttempt(
            reviewAttemptCommand(review, started, "fixture"),
        ).advance.session
        val completed = started.copy(
            status = StudyDbValue.ReviewStatus.COMPLETED,
            lastActiveAtEpochMillis = OCCURRED_AT + 30_000,
            completedAtEpochMillis = OCCURRED_AT + 30_000,
            currentOrdinal = 1,
            stateVersion = 1,
        )
        assertEquals(completed, advanced)
        store.seedFixture(fixture)

        assertNull(store.observeReviewPlan(review.plan.reviewPlanId).first()?.activeSession)
        assertEquals(completed, store.observeReviewPlan(review.plan.reviewPlanId).first()?.latestSession)
        assertTrue(
            runCatching {
                store.seedFixture(
                    fixture.copy(
                        reviewSessions = listOf(started.copy(timeBudgetSeconds = 901)),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun immutableFixturesForeignKeysAndStudyDayBoundaryRejectBadInputs() = runBlocking {
        val replay = store.seedFixture(baseSeed())
        assertEquals(0, replay.insertedProblemCount)
        assertEquals(0, replay.insertedErrorBookEntryCount)

        val conflictingSeed = baseSeed().copy(
            problems = baseSeed().problems.map { it.copy(subject = "PHYSICS") },
        )
        assertTrue(runCatching { store.seedFixture(conflictingSeed) }.isFailure)

        val missingRevision = baseSeed().copy(
            problems = emptyList(),
            revisions = emptyList(),
            practiceUnits = listOf(
                baseSeed().practiceUnits.single().copy(
                    practiceUnitId = "bad-unit",
                    problemRevisionId = "missing-revision",
                ),
            ),
            errorBookEntries = emptyList(),
            knowledgeNodes = emptyList(),
            knowledgeBindings = emptyList(),
        )
        assertTrue(runCatching { store.seedFixture(missingRevision) }.isFailure)

        val unknownZone = StudyDayContext(
            epochDay = studyDay(OCCURRED_AT).epochDay,
            timeZoneId = "Mars/Olympus",
            utcOffsetMinutes = 0,
        )
        assertTrue(
            runCatching {
                store.recordAttempt(
                    attemptCommand(
                        submissionId = "bad-zone",
                        attemptId = "bad-zone",
                        presentationId = "bad-zone",
                    ).copy(studyDay = unknownZone),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                store.recordAttempt(
                    attemptCommand(
                        submissionId = "unknown-snapshot",
                        attemptId = "unknown-snapshot",
                        presentationId = "unknown-snapshot",
                    ).copy(assessmentSnapshotId = "not-trusted"),
                )
            }.isFailure,
        )
        assertEquals(0, store.loadLearningLedger(LEARNER).validPrefix.size)
    }

    @Test
    fun mistakeProjectionRoundTripsDurationAndOnlyExactCurrentKnowledgeBindings() = runBlocking {
        store.confirmProblemOrganization(currentOrganizationWithTaxonomyMismatch())
        store.seedFixture(revisionBoundarySeed())
        store.confirmProblemOrganization(revisionBoundaryOrganization())

        val exact = requireNotNull(store.findMistakeBySourceKey("source-1"))
        assertEquals(120, exact.estimatedSeconds)
        assertEquals(
            setOf("knowledge-exact-a", "knowledge-exact-b"),
            exact.knowledgeNodeIds,
        )

        val observedExact = store.observeMistakes().first().single { it.entryId == "entry-1" }
        assertEquals(exact.estimatedSeconds, observedExact.estimatedSeconds)
        assertEquals(exact.knowledgeNodeIds, observedExact.knowledgeNodeIds)
        assertEquals(listOf("精确知识点 A", "精确知识点 B"), observedExact.knowledgeLabels)
        assertEquals(
            exact.knowledgeNodeIds,
            store.observeConfirmedProblemOrganization("problem-1", "revision-1")
                .first()
                .knowledgeNodeIds,
        )

        val revisionMismatch = requireNotNull(
            store.findMistakeBySourceKey("source-revision-boundary"),
        )
        assertEquals("revision-boundary-current", revisionMismatch.problemRevisionId)
        assertEquals(321, revisionMismatch.estimatedSeconds)
        assertTrue(revisionMismatch.knowledgeNodeIds.isEmpty())
    }

    @Test
    fun onlyTheExactLocalReviewSelfReportContractMayPersistWithoutAttributions() = runBlocking {
        val localSelfReport = localReviewSelfReportSnapshot()

        store.saveAssessmentEvidenceSnapshot(localSelfReport)
        val recorded = store.recordAttempt(
            attemptCommand(
                submissionId = "local-self-report-submission",
                attemptId = "local-self-report-attempt",
                presentationId = "local-self-report-presentation",
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = 0.35,
                    reason = LearningEvidenceReason.SELF_REPORTED_RECALL,
                ),
                outcome = ProblemMemoryOutcome.ASSISTED_RECALL,
            ).copy(assessmentSnapshotId = localSelfReport.snapshotId),
        )

        assertEquals(localSelfReport, recorded.attempt.assessmentSnapshot)
        assertTrue(recorded.attempt.knowledgeNodeIds.isEmpty())
        assertEquals(
            LearningEvidenceReason.SELF_REPORTED_RECALL,
            recorded.attempt.evidence.reason,
        )

        val nearMatch = localSelfReport.copy(
            snapshotId = "local-self-report-near-match",
            itemFamilyId = "not-the-local-review-contract",
        )
        val failure = runCatching {
            store.saveAssessmentEvidenceSnapshot(nearMatch)
        }.exceptionOrNull()

        assertTrue(failure is DatabaseContractViolationException)
        assertEquals(1, store.loadLearningLedger(LEARNER).validPrefix.size)
    }

    @Test
    fun localModelJudgedContractMayPersistWithoutAttributionsAndNearMatchesAreRejected() = runBlocking {
        // 讲题判定结算：快照故意不带知识归属（题目级排期 ≠ 知识掌握证据），
        // 因此它必须有自己的合同身份，而不是蹭自评合同。
        val modelJudged = localModelJudgedSnapshot()

        store.saveAssessmentEvidenceSnapshot(modelJudged)
        val recorded = store.recordAttempt(
            attemptCommand(
                submissionId = "local-model-judged-submission",
                attemptId = "local-model-judged-attempt",
                presentationId = "local-model-judged-presentation",
                evidence = LearningEvidence(
                    direction = LearningEvidenceDirection.POSITIVE,
                    weight = 0.5,
                    reason = LearningEvidenceReason.MODEL_JUDGED_CORRECT,
                ),
                outcome = ProblemMemoryOutcome.ASSISTED_RECALL,
            ).copy(assessmentSnapshotId = modelJudged.snapshotId),
        )

        assertEquals(modelJudged, recorded.attempt.assessmentSnapshot)
        assertTrue(recorded.attempt.knowledgeNodeIds.isEmpty())
        assertEquals(
            LearningEvidenceReason.MODEL_JUDGED_CORRECT,
            recorded.attempt.evidence.reason,
        )

        val nearMatch = modelJudged.copy(
            snapshotId = "local-model-judged-near-match",
            answerSpecId = "not-the-model-judged-contract",
        )
        val failure = runCatching {
            store.saveAssessmentEvidenceSnapshot(nearMatch)
        }.exceptionOrNull()

        assertTrue(failure is DatabaseContractViolationException)
    }

    @Test
    fun persistentDatabaseReopensWithLosslessLedgerSnapshotAndCompletedSession() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "room3-lossless-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            var persistent = StudyDatabaseFactory.open(context, databaseName)
            persistent.seedFixture(baseSeed())
            persistent.saveAssessmentEvidenceSnapshot(evidenceSnapshot())
            val attempt = persistent.recordAttempt(
                attemptCommand(
                    submissionId = "reopen-submission",
                    attemptId = "reopen-attempt",
                    presentationId = "reopen-presentation",
                ),
            )
            val reveal = persistent.recordAnswerReveal(
                answerRevealCommand("reopen-reveal", "reopen-presentation"),
            )
            val snapshot = learnerSnapshot(
                checkpoint = 2,
                knownHead = 2,
                attempts = listOf(attempt),
                reveals = listOf(reveal),
            )
            val batch = persistent.loadProjectionBatch(PROJECTION, LEARNER, 10)
            val committed = persistent.commitProjection(
                projectionCommit(0, 0, ProjectionCommitMode.INCREMENTAL, batch.events, snapshot),
            )
            val review = reviewBundle(
                fingerprint = "reopen-completed-session-plan",
                localDay = 20_003,
                isCurrent = true,
            )
            persistent.saveReviewPlan(review)
            val reviewStarted = ReviewSessionRecord(
                reviewSessionId = "reopen-review-session",
                reviewPlanId = review.plan.reviewPlanId,
                status = StudyDbValue.ReviewStatus.IN_PROGRESS,
                startedAtEpochMillis = OCCURRED_AT,
                lastActiveAtEpochMillis = OCCURRED_AT,
                completedAtEpochMillis = null,
                currentOrdinal = 0,
                timeBudgetSeconds = review.plan.timeBudgetSeconds,
                projectionCheckpoint = review.plan.projectionCheckpoint,
            )
            persistent.saveReviewSession(reviewStarted)
            val reviewWriteCommand = reviewAttemptCommand(review, reviewStarted, "reopen")
            val advance = persistent.recordReviewAttempt(reviewWriteCommand).advance
            val reviewCompleted = advance.session
            assertTrue(advance.created)
            val beforeReopen = persistent.observeReviewPlan(review.plan.reviewPlanId).first()
            assertNull(beforeReopen?.activeSession)
            assertEquals(reviewCompleted, beforeReopen?.latestSession)
            persistent.close()

            persistent = StudyDatabaseFactory.open(context, databaseName)
            assertEquals(attempt.attempt, persistent.readAttemptP0(attempt.attempt.attemptId)?.attempt)
            assertEquals(reveal.outcome, persistent.readAnswerRevealP0(reveal.outcome.outcomeId)?.outcome)
            assertEquals(committed, persistent.readCurrentLearnerSnapshot(PROJECTION, LEARNER))
            assertEquals(LearningLedgerReadStatus.COMPLETE, persistent.loadLearningLedger(LEARNER).status)
            val replay = persistent.recordReviewAttempt(reviewWriteCommand).advance
            assertFalse(replay.created)
            assertEquals(advance.receipt, replay.receipt)
            assertEquals(reviewCompleted, replay.session)
            val afterReopen = persistent.observeReviewPlan(review.plan.reviewPlanId).first()
            assertNull(afterReopen?.activeSession)
            assertEquals(reviewCompleted, afterReopen?.latestSession)
            persistent.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    private fun attemptCommand(
        learnerId: String = LEARNER,
        submissionId: String,
        attemptId: String,
        presentationId: String,
        evidence: LearningEvidence = independentEvidence(),
        outcome: ProblemMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
    ) = AttemptWriteCommand(
        learnerId = learnerId,
        submissionId = submissionId,
        attemptId = attemptId,
        presentationId = presentationId,
        assessmentSnapshotId = SNAPSHOT_ID,
        submittedResponse = AttemptSubmittedResponse.Choice(
            choiceId = "choice-a",
            choiceMarkdown = "原始选项 A",
            submittedAtEpochMillis = OCCURRED_AT,
        ),
        evidence = evidence,
        problemMemoryOutcome = outcome,
        occurredAtEpochMillis = OCCURRED_AT,
        durationSeconds = 45,
        studyDay = studyDay(OCCURRED_AT),
    )

    private fun answerRevealCommand(
        assessmentEventId: String,
        presentationId: String,
    ) = AnswerRevealWriteCommand(
        learnerId = LEARNER,
        assessmentEventId = assessmentEventId,
        presentationId = presentationId,
        assessmentSnapshotId = SNAPSHOT_ID,
        contentMarkdown = "完整答案与推导",
        occurredAtEpochMillis = OCCURRED_AT + 1_000,
        studyDay = studyDay(OCCURRED_AT + 1_000),
    )

    private fun correctionCommand(
        submissionId: String,
        attemptId: String,
        correctionId: String,
        replacementEvidence: LearningEvidence = negativeEvidence(),
        replacementOutcome: ProblemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
    ) = AttemptCorrectionRecord(
        learnerId = LEARNER,
        submissionId = submissionId,
        correctionId = correctionId,
        attemptId = attemptId,
        replacementEvidence = replacementEvidence,
        replacementMemoryOutcome = replacementOutcome,
        reasonMarkdown = "复核后发现首次判分有误",
        occurredAtEpochMillis = OCCURRED_AT + 2_000,
    )

    private fun evidenceSnapshot() = AssessmentEvidenceSnapshot(
        snapshotId = SNAPSHOT_ID,
        assessmentItemId = "assessment-item-1",
        practiceUnitId = UNIT_ID,
        problemRevisionId = REVISION_ID,
        answerSpecId = "answer-spec-1",
        itemFamilyId = "family-1",
        sourceBundleId = "bundle-1",
        taxonomyVersion = "taxonomy-v1",
        verification = AssessmentSnapshotVerification.VERIFIED,
        calibration = CalibrationSnapshot(
            support = CalibrationSupport.SUPPORTED,
            sourceId = "calibration-source",
            version = "calibration-v1",
            validFromEpochMillis = 0,
            validUntilEpochMillis = OCCURRED_AT + 100_000,
        ),
        attributions = listOf(
            KnowledgeEvidenceAttribution(
                bindingId = BINDING_ID,
                knowledgeNodeId = KNOWLEDGE_ID,
                weight = 1.0,
                basisRevisionId = REVISION_ID,
                taxonomyVersion = "taxonomy-v1",
                role = EvidenceAttributionRole.PRIMARY,
                certainty = EvidenceAttributionCertainty.DIRECT,
            ),
        ),
        capturedAtEpochMillis = OCCURRED_AT - 1_000,
    )

    private fun localModelJudgedSnapshot() = AssessmentEvidenceSnapshot(
        snapshotId = "local-model-judged-snapshot",
        assessmentItemId = LocalModelJudgedContract.ASSESSMENT_ITEM_ID_PREFIX + UNIT_ID,
        practiceUnitId = UNIT_ID,
        problemRevisionId = REVISION_ID,
        answerSpecId = LocalModelJudgedContract.ANSWER_SPEC_ID,
        itemFamilyId = LocalModelJudgedContract.ITEM_FAMILY_ID,
        sourceBundleId = null,
        taxonomyVersion = LocalModelJudgedContract.TAXONOMY_VERSION,
        verification = AssessmentSnapshotVerification.VERIFIED,
        calibration = CalibrationSnapshot.unknown(),
        attributions = emptyList(),
        capturedAtEpochMillis = OCCURRED_AT - 1_000,
    )

    private fun localReviewSelfReportSnapshot() = AssessmentEvidenceSnapshot(
        snapshotId = "local-self-report-snapshot",
        assessmentItemId = LocalReviewSelfReportContract.ASSESSMENT_ITEM_ID_PREFIX + UNIT_ID,
        practiceUnitId = UNIT_ID,
        problemRevisionId = REVISION_ID,
        answerSpecId = LocalReviewSelfReportContract.ANSWER_SPEC_ID,
        itemFamilyId = LocalReviewSelfReportContract.ITEM_FAMILY_ID,
        sourceBundleId = null,
        taxonomyVersion = LocalReviewSelfReportContract.TAXONOMY_VERSION,
        verification = AssessmentSnapshotVerification.VERIFIED,
        calibration = CalibrationSnapshot.unknown(),
        attributions = emptyList(),
        capturedAtEpochMillis = OCCURRED_AT - 1_000,
    )

    private fun currentOrganizationWithTaxonomyMismatch() = organizationCommand(
        commandId = "mistake-projection-current-organization",
        fingerprintCharacter = "a",
        problemId = PROBLEM_ID,
        revisionId = REVISION_ID,
        practiceUnitId = UNIT_ID,
        acceptedAtEpochMillis = OCCURRED_AT + 10_000,
        knowledge = listOf(
            OrganizationKnowledgeFixture(
                id = "knowledge-exact-a",
                stableCode = "math.review.exact-a",
                displayName = "精确知识点 A",
                nodeTaxonomy = "math-v1",
                bindingTaxonomy = "organization-v2",
                classificationTaxonomy = "organization-v2",
            ),
            OrganizationKnowledgeFixture(
                id = "knowledge-exact-b",
                stableCode = "math.review.exact-b",
                displayName = "精确知识点 B",
                nodeTaxonomy = "math-v1",
                bindingTaxonomy = "organization-v2",
                classificationTaxonomy = "organization-v2",
            ),
            OrganizationKnowledgeFixture(
                id = "knowledge-taxonomy-mismatch",
                stableCode = "math.review.taxonomy-mismatch",
                displayName = "旧分类不应泄漏",
                bindingTaxonomy = "binding-only-v1",
                classificationTaxonomy = "classification-only-v1",
            ),
        ),
    )

    private fun revisionBoundarySeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = "problem-revision-boundary",
                canonicalFingerprint = "revision-boundary-problem",
                subject = "MATH",
                createdAtEpochMillis = OCCURRED_AT,
            ),
        ),
        revisions = listOf(
            revisionBoundaryRevision(
                revisionId = "revision-boundary-old",
                revisionNumber = 1,
                title = "旧版题目",
                markdown = "旧版题干。",
                fingerprint = "revision-boundary-old-content",
                createdAtEpochMillis = OCCURRED_AT,
            ),
            revisionBoundaryRevision(
                revisionId = "revision-boundary-current",
                revisionNumber = 2,
                title = "当前题目",
                markdown = "当前题干。",
                fingerprint = "revision-boundary-current-content",
                createdAtEpochMillis = OCCURRED_AT + 1,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = "unit-revision-boundary",
                problemId = "problem-revision-boundary",
                problemRevisionId = "revision-boundary-old",
                unitKey = "whole",
                unitKind = "WHOLE",
                title = "旧版复习单元",
                promptMarkdown = "旧版题干。",
                estimatedSeconds = 321,
                createdAtEpochMillis = OCCURRED_AT,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-revision-boundary",
                practiceUnitId = "unit-revision-boundary",
                problemId = "problem-revision-boundary",
                currentRevisionId = "revision-boundary-current",
                sourceKey = "source-revision-boundary",
                acceptedAtEpochMillis = OCCURRED_AT,
                updatedAtEpochMillis = OCCURRED_AT,
            ),
        ),
    )

    private fun revisionBoundaryRevision(
        revisionId: String,
        revisionNumber: Int,
        title: String,
        markdown: String,
        fingerprint: String,
        createdAtEpochMillis: Long,
    ) = ProblemRevisionSeedRecord(
        revisionId = revisionId,
        problemId = "problem-revision-boundary",
        revisionNumber = revisionNumber,
        title = title,
        problemMarkdown = markdown,
        answerSpecId = null,
        answerSpecSnapshot = null,
        answerVerificationStatus = StudyDbValue.VerificationStatus.UNKNOWN,
        sourceType = "TEST",
        sourceReference = null,
        contentFingerprint = fingerprint,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private fun revisionBoundaryOrganization() = organizationCommand(
        commandId = "revision-boundary-old-organization",
        fingerprintCharacter = "b",
        problemId = "problem-revision-boundary",
        revisionId = "revision-boundary-old",
        practiceUnitId = "unit-revision-boundary",
        acceptedAtEpochMillis = OCCURRED_AT + 20_000,
        knowledge = listOf(
            OrganizationKnowledgeFixture(
                id = "knowledge-revision-boundary-old",
                stableCode = "math.review.revision-old",
                displayName = "旧版知识点",
                bindingTaxonomy = "organization-v2",
                classificationTaxonomy = "organization-v2",
            ),
        ),
    )

    private fun organizationCommand(
        commandId: String,
        fingerprintCharacter: String,
        problemId: String,
        revisionId: String,
        practiceUnitId: String,
        acceptedAtEpochMillis: Long,
        knowledge: List<OrganizationKnowledgeFixture>,
    ) = ConfirmProblemOrganizationCommand(
        commandId = commandId,
        payloadFingerprint = fingerprintCharacter.repeat(64),
        problemId = problemId,
        problemRevisionId = revisionId,
        practiceUnitId = practiceUnitId,
        knowledgeNodes = knowledge.map { fixture ->
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = fixture.id,
                stableCode = fixture.stableCode,
                subject = "MATH",
                displayName = fixture.displayName,
                parentKnowledgeNodeId = null,
                taxonomyVersion = fixture.nodeTaxonomy,
                createdAtEpochMillis = acceptedAtEpochMillis,
            )
        },
        knowledgeBindings = knowledge.map { fixture ->
            KnowledgeBindingSeedRecord(
                bindingId = "binding-${fixture.id}",
                practiceUnitId = practiceUnitId,
                knowledgeNodeId = fixture.id,
                basisRevisionId = revisionId,
                strength = 1.0,
                sourceType = "USER_CORRECTED",
                taxonomyVersion = fixture.bindingTaxonomy,
                acceptedAtEpochMillis = acceptedAtEpochMillis,
            )
        },
        classifications = listOf(
            ProblemClassificationBindingRecord(
                bindingId = "classification-$commandId-chapter",
                problemId = problemId,
                basisRevisionId = revisionId,
                dimension = "CHAPTER",
                labelId = "math.chapter.$commandId",
                displayName = "函数",
                taxonomyVersion = "organization-v2",
                acceptanceSource = "USER_CORRECTED",
                acceptedAtEpochMillis = acceptedAtEpochMillis,
            ),
        ) + knowledge.map { fixture ->
            ProblemClassificationBindingRecord(
                bindingId = "classification-${fixture.id}",
                problemId = problemId,
                basisRevisionId = revisionId,
                dimension = "KNOWLEDGE",
                labelId = fixture.stableCode,
                displayName = fixture.displayName,
                taxonomyVersion = fixture.classificationTaxonomy,
                acceptanceSource = "USER_CORRECTED",
                acceptedAtEpochMillis = acceptedAtEpochMillis,
            )
        },
        relations = emptyList(),
        acceptedAtEpochMillis = acceptedAtEpochMillis,
    )

    private data class OrganizationKnowledgeFixture(
        val id: String,
        val stableCode: String,
        val displayName: String,
        val nodeTaxonomy: String = "organization-v2",
        val bindingTaxonomy: String,
        val classificationTaxonomy: String,
    )

    private fun learnerSnapshot(
        checkpoint: Long,
        knownHead: Long,
        status: ProjectionStatus = ProjectionStatus.CURRENT,
        freshness: LearnerSnapshotFreshness = LearnerSnapshotFreshness.CURRENT,
        correctionWatermarkEpochMillis: Long? = null,
        masteryStates: Map<String, KnowledgeMasteryState> = emptyMap(),
        attempts: List<AttemptWriteResult> = emptyList(),
        corrections: List<AttemptCorrectionResult> = emptyList(),
        reveals: List<AnswerRevealWriteResult> = emptyList(),
    ) = LearnerSnapshot(
        learnerId = LEARNER,
        checkpoint = ProjectionCheckpoint(
            lastSequence = checkpoint,
            projectorVersion = PROJECTOR_VERSION,
            projectedAtEpochMillis = OCCURRED_AT + checkpoint,
        ),
        knownLedgerHeadSequence = knownHead,
        generatedAtEpochMillis = OCCURRED_AT + knownHead,
        correctionWatermarkEpochMillis = correctionWatermarkEpochMillis,
        knowledgeMasteryStates = masteryStates,
        freshness = freshness,
        projectionStatus = status,
        appliedAttemptRecords = attempts.associate { result ->
            result.attempt.attemptId to AppliedAttemptRecord(
                attemptId = result.attempt.attemptId,
                canonicalFingerprint = result.canonicalFingerprint,
                eventSequence = result.attempt.eventSequence,
                presentationId = result.attempt.presentationId,
                responseOrdinal = result.attempt.responseOrdinal,
            )
        },
        appliedCorrectionRecords = corrections.associate { result ->
            result.correction.correctionId to AppliedCorrectionRecord(
                correctionId = result.correction.correctionId,
                attemptId = result.correction.attemptId,
                canonicalFingerprint = result.canonicalFingerprint,
                eventSequence = result.correction.eventSequence,
            )
        },
        appliedAnswerRevealRecords = reveals.associate { result ->
            result.outcome.outcomeId to AppliedAnswerRevealRecord(
                outcomeId = result.outcome.outcomeId,
                presentationId = result.outcome.presentationId,
                canonicalFingerprint = result.canonicalFingerprint,
                eventSequence = result.outcome.eventSequence,
            )
        },
    )

    private fun projectionCommit(
        previousCheckpoint: Long,
        previousStateVersion: Long,
        mode: ProjectionCommitMode,
        events: List<PersistedIncrementalLearningEvent>,
        snapshot: LearnerSnapshot,
    ) = ProjectionCommit(
        projectionName = PROJECTION,
        learnerId = LEARNER,
        expectedPreviousCheckpoint = previousCheckpoint,
        expectedPreviousStateVersion = previousStateVersion,
        mode = mode,
        knownLedgerHeadSequence = snapshot.knownLedgerHeadSequence,
        consumedLedgerEvents = events.map { event ->
            ConsumedLedgerEventReceipt(
                eventKind = event.outbox.eventKind,
                eventId = event.event.ledgerEventId,
                eventSequence = event.event.eventSequence,
                canonicalFingerprint = event.canonicalFingerprint,
            )
        },
        presentationProjectionStates = projectedPresentationStates(events.map { it.event }),
        snapshot = snapshot,
    )

    private fun projectedPresentationStates(
        events: List<LearningLedgerEvent>,
        initial: Map<String, PresentationProjectionState> = emptyMap(),
    ): Map<String, PresentationProjectionState> {
        val states = initial.toMutableMap()
        events.sortedBy(LearningLedgerEvent::eventSequence).forEach { event ->
            when (event) {
                is com.tingyun.smartmistakebook.core.model.Attempt -> {
                    val current = states[event.presentationId] ?: PresentationProjectionState(
                        presentationId = event.presentationId,
                        asOfLedgerSequence = event.eventSequence,
                        memoryProjectionApplied = false,
                    )
                    states[event.presentationId] = current.copy(
                        asOfLedgerSequence = maxOf(current.asOfLedgerSequence, event.eventSequence),
                        memoryProjectionApplied = true,
                    )
                }
                is com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome -> {
                    val current = states[event.presentationId] ?: PresentationProjectionState(
                        presentationId = event.presentationId,
                        asOfLedgerSequence = event.eventSequence,
                        memoryProjectionApplied = false,
                    )
                    states[event.presentationId] = current.copy(
                        asOfLedgerSequence = maxOf(current.asOfLedgerSequence, event.eventSequence),
                        memoryProjectionApplied = true,
                        answerRevealSequence = event.eventSequence,
                    )
                }
                is com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome -> Unit
                is com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted -> Unit
                is com.tingyun.smartmistakebook.core.model.AttemptCorrection -> Unit
            }
        }
        val finalAsOf = maxOf(
            initial.values.maxOfOrNull(PresentationProjectionState::asOfLedgerSequence) ?: 0,
            events.maxOfOrNull(LearningLedgerEvent::eventSequence) ?: 0,
        )
        return states.mapValues { (_, state) -> state.copy(asOfLedgerSequence = finalAsOf) }
    }

    private fun PersistedLearningLedgerEvent.toReceipt() = ConsumedLedgerEventReceipt(
        eventKind = when (event) {
            is com.tingyun.smartmistakebook.core.model.Attempt -> "ATTEMPT"
            is com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome -> "ANSWER_REVEAL_OUTCOME"
            is com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome ->
                "TUTOR_ANSWER_EXPOSURE_OUTCOME"
            is com.tingyun.smartmistakebook.core.model.ChatEvidenceSubmitted -> "CHAT_EVIDENCE_SUBMITTED"
            is com.tingyun.smartmistakebook.core.model.AttemptCorrection -> "ATTEMPT_CORRECTION"
        },
        eventId = event.ledgerEventId,
        eventSequence = event.eventSequence,
        canonicalFingerprint = canonicalFingerprint,
    )

    private suspend fun saveStartedReview(
        fingerprint: String,
        localDay: Long,
        isCurrent: Boolean = false,
        startedAtEpochMillis: Long = OCCURRED_AT,
        timeZoneId: String = ZONE,
    ): Pair<ReviewPlanBundle, ReviewSessionRecord> {
        val review = reviewBundle(fingerprint, localDay, isCurrent, timeZoneId)
        store.saveReviewPlan(review)
        val session = ReviewSessionRecord(
            reviewSessionId = "session-$fingerprint",
            reviewPlanId = review.plan.reviewPlanId,
            status = StudyDbValue.ReviewStatus.IN_PROGRESS,
            startedAtEpochMillis = startedAtEpochMillis,
            lastActiveAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = null,
            currentOrdinal = 0,
            timeBudgetSeconds = review.plan.timeBudgetSeconds,
            projectionCheckpoint = review.plan.projectionCheckpoint,
        )
        store.saveReviewSession(session)
        return review to session
    }

    private fun reviewAttemptCommand(
        review: ReviewPlanBundle,
        session: ReviewSessionRecord,
        suffix: String,
    ): ReviewAttemptWriteCommand {
        val occurredAtEpochMillis = OCCURRED_AT + 30_000
        return ReviewAttemptWriteCommand(
            attempt = attemptCommand(
                submissionId = "review-submission-$suffix",
                attemptId = "review-attempt-$suffix",
                presentationId = "review-presentation-$suffix",
            ).copy(
                occurredAtEpochMillis = occurredAtEpochMillis,
                studyDay = studyDay(occurredAtEpochMillis),
                submittedResponse = AttemptSubmittedResponse.Choice(
                    choiceId = "choice-a",
                    choiceMarkdown = "原始选项 A",
                    submittedAtEpochMillis = occurredAtEpochMillis,
                ),
            ),
            sessionId = session.reviewSessionId,
            expectedStateVersion = session.stateVersion,
            reviewQueueItemId = review.queue.single().reviewQueueItemId,
            practiceUnitId = review.queue.single().practiceUnitId,
        )
    }

    private fun reviewBundle(
        fingerprint: String,
        localDay: Long,
        isCurrent: Boolean,
        timeZoneId: String = ZONE,
    ): ReviewPlanBundle {
        val planId = "plan-$fingerprint"
        return ReviewPlanBundle(
            plan = ReviewPlanRecord(
                reviewPlanId = planId,
                learnerId = LEARNER,
                localDate = java.time.LocalDate.ofEpochDay(localDay).toString(),
                localDayEpochDay = localDay,
                timeZoneId = timeZoneId,
                timeBudgetSeconds = 900,
                planningAtEpochMillis = OCCURRED_AT,
                status = StudyDbValue.ReviewStatus.PLANNED,
                plannerVersion = "planner-v2",
                projectionCheckpoint = 0,
                inputFingerprint = "input-$fingerprint",
                planFingerprint = fingerprint,
                planRevision = 1,
                createdAtEpochMillis = OCCURRED_AT,
            ),
            queue = listOf(
                ReviewQueueItemRecord(
                    reviewQueueItemId = "queue-$fingerprint",
                    reviewPlanId = planId,
                    practiceUnitId = UNIT_ID,
                    knowledgeNodeIds = setOf(KNOWLEDGE_ID),
                    itemFamilyId = "family-1",
                    sourceBundleId = "bundle-1",
                    reasons = setOf("DUE_RECALL_RISK"),
                    ordinal = 0,
                    priorityScore = 0.75,
                    difficultyBand = "MEDIUM",
                    dueAtEpochMillis = null,
                    estimatedSeconds = 300,
                    reasonSnapshot = "到期且近期有遗忘风险",
                ),
            ),
            activeSession = null,
            isCurrent = isCurrent,
        )
    }

    private fun untrustedMasteryState(checkpoint: Long) = KnowledgeMasteryState(
        knowledgeNodeId = KNOWLEDGE_ID,
        masteryScore = 0.72,
        conservativeMasteryScore = 0.54,
        evidenceMass = 1.0,
        independentCorrectObservations = listOf(
            IndependentCorrectObservation(
                itemFamilyId = "family-1",
                studyDayEpochDay = studyDay(OCCURRED_AT).epochDay,
                occurredAtEpochMillis = OCCURRED_AT,
                eventSequence = 1,
                bindingId = BINDING_ID,
                evidenceWeight = 1.0,
                calibration = CalibrationSnapshot(
                    support = CalibrationSupport.SUPPORTED,
                    sourceId = "calibration-source",
                    version = "calibration-v1",
                    validFromEpochMillis = 0,
                    validUntilEpochMillis = OCCURRED_AT + 100_000,
                ),
                timeTrust = EventTimeTrust.CLOCK_ROLLBACK_CLAMPED,
            ),
        ),
        status = MasteryStatus.LEARNING,
        calibrationSupport = CalibrationSupport.SUPPORTED,
        projectorVersion = PROJECTOR_VERSION,
        checkpointSequence = checkpoint,
        lastEvidenceAtEpochMillis = OCCURRED_AT,
    )

    private fun baseSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(PROBLEM_ID, "problem-fingerprint", "MATH", OCCURRED_AT - 10_000),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = REVISION_ID,
                problemId = PROBLEM_ID,
                revisionNumber = 1,
                title = "函数单调性",
                problemMarkdown = "求函数的单调区间。",
                answerSpecId = "answer-spec-1",
                answerSpecSnapshot = "增区间与减区间",
                answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                sourceType = "IMPORT",
                sourceReference = null,
                contentFingerprint = "revision-fingerprint",
                createdAtEpochMillis = OCCURRED_AT - 9_000,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                unitKey = "whole",
                unitKind = "WHOLE",
                title = "函数单调性",
                promptMarkdown = "求单调区间。",
                estimatedSeconds = 120,
                createdAtEpochMillis = OCCURRED_AT - 8_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-1",
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                currentRevisionId = REVISION_ID,
                sourceKey = "source-1",
                acceptedAtEpochMillis = OCCURRED_AT - 7_000,
                updatedAtEpochMillis = OCCURRED_AT - 7_000,
            ),
        ),
        knowledgeNodes = listOf(
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = KNOWLEDGE_ID,
                stableCode = "math.function.monotonicity",
                subject = "MATH",
                displayName = "函数单调性",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "taxonomy-v1",
                createdAtEpochMillis = OCCURRED_AT - 6_000,
            ),
        ),
        knowledgeBindings = listOf(
            KnowledgeBindingSeedRecord(
                bindingId = BINDING_ID,
                practiceUnitId = UNIT_ID,
                knowledgeNodeId = KNOWLEDGE_ID,
                basisRevisionId = REVISION_ID,
                strength = 1.0,
                sourceType = "VERIFIED",
                taxonomyVersion = "taxonomy-v1",
                acceptedAtEpochMillis = OCCURRED_AT - 5_000,
            ),
        ),
    )

    companion object {
        private const val LEARNER = "learner-1"
        private const val PROJECTION = "learning-core-v2"
        private const val PROJECTOR_VERSION =
            "learning-core-v2(projector-v2,evidence-v2,curve-v2,skip-v2,attribution-v1)"
        private const val PROBLEM_ID = "problem-1"
        private const val REVISION_ID = "revision-1"
        private const val UNIT_ID = "unit-1"
        private const val KNOWLEDGE_ID = "knowledge-1"
        private const val BINDING_ID = "binding-1"
        private const val SNAPSHOT_ID = "evidence-snapshot-1"
        private const val ZONE = "Asia/Shanghai"
        private const val OCCURRED_AT = 1_728_000_000_000L

        private fun independentEvidence() = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 1.0,
            reason = LearningEvidenceReason.INDEPENDENT_CORRECT,
        )

        private fun assistedEvidence() = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 0.5,
            reason = LearningEvidenceReason.CORRECT_AFTER_HINT,
        )

        private fun negativeEvidence() = LearningEvidence(
            direction = LearningEvidenceDirection.NEGATIVE,
            weight = 1.0,
            reason = LearningEvidenceReason.INDEPENDENT_INCORRECT,
        )

        private fun studyDay(atEpochMillis: Long): StudyDayContext {
            val zoned = Instant.ofEpochMilli(atEpochMillis).atZone(ZoneId.of(ZONE))
            return StudyDayContext(
                epochDay = zoned.toLocalDate().toEpochDay(),
                timeZoneId = ZONE,
                utcOffsetMinutes = zoned.offset.totalSeconds / 60,
            )
        }
    }
}
