package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AssessmentSnapshotVerification
import com.tingyun.smartmistakebook.core.model.AnswerRevealOutcome
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptCorrection
import com.tingyun.smartmistakebook.core.model.CalibrationSnapshot
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.KnowledgeEvidenceAttribution
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearningEvidence
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.PresentationProjectionState
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.StudyDayContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingLearningCoreReviewTest {
    private val projector = LearningProjector()

    @Test
    fun `cross batch retry with same id and different canonical payload is a conflict`() {
        val first = attempt("a-1", 1)
        val projected = projector.project(LearnerSnapshot.empty("learner"), listOf(first), 1)

        val retry = projector.project(
            projected.snapshot,
            listOf(first.copy(durationSeconds = first.durationSeconds + 1)),
            1,
        )

        assertEquals(setOf("a-1"), retry.conflictedAttemptIds)
        assertTrue(retry.ignoredAttemptIds.isEmpty())
        assertEquals(ProjectionStatus.CONFLICTED, retry.snapshot.projectionStatus)
    }

    @Test
    fun `projector commits continuous valid prefix and stops at a gap`() {
        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(attempt("a-3", 3), attempt("a-1", 1)),
            3,
        )

        assertEquals(setOf("a-1"), result.appliedAttemptIds)
        assertEquals(setOf("a-3"), result.deferredAttemptIds)
        assertEquals(2L, result.missingSequence)
        assertEquals(1L, result.snapshot.checkpoint.lastSequence)
        assertEquals(ProjectionStatus.WAITING_FOR_GAP, result.snapshot.projectionStatus)

        val resumed = projector.project(
            result.snapshot,
            listOf(attempt("a-3", 3), attempt("a-2", 2)),
            3,
        )

        assertEquals(setOf("a-2", "a-3"), resumed.appliedAttemptIds)
        assertEquals(3L, resumed.snapshot.checkpoint.lastSequence)
        assertEquals(ProjectionStatus.CURRENT, resumed.snapshot.projectionStatus)
    }

    @Test
    fun `conflict after a valid prefix does not roll back the prefix`() {
        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(
                attempt("a-1", 1),
                attempt("a-2-left", 2),
                attempt("a-2-right", 2),
                attempt("a-3", 3),
            ),
            3,
        )

        assertEquals(setOf("a-1"), result.appliedAttemptIds)
        assertEquals(setOf("a-2-left", "a-2-right"), result.conflictedAttemptIds)
        assertEquals(setOf("a-3"), result.deferredAttemptIds)
        assertEquals(1L, result.snapshot.checkpoint.lastSequence)
    }

    @Test
    fun `same id payload conflict at a later sequence preserves earlier valid prefix`() {
        val conflicted = attempt("a-2", 2)
        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(
                attempt("a-1", 1),
                conflicted,
                conflicted.copy(durationSeconds = conflicted.durationSeconds + 1),
                attempt("a-3", 3),
            ),
            3,
        )

        assertEquals(setOf("a-1"), result.appliedAttemptIds)
        assertEquals(setOf("a-2"), result.conflictedAttemptIds)
        assertEquals(setOf("a-3"), result.deferredAttemptIds)
        assertEquals(1L, result.snapshot.checkpoint.lastSequence)
    }

    @Test
    fun `waiting for a gap cannot self heal on an empty batch or an old retry`() {
        val waiting = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(attempt("a-1", 1), attempt("a-3", 3)),
            3,
        )

        val emptyBatch = projector.project(waiting.snapshot, emptyList(), 3)
        val oldRetry = projector.project(waiting.snapshot, listOf(attempt("a-1", 1)), 3)

        assertEquals(ProjectionStatus.WAITING_FOR_GAP, emptyBatch.snapshot.projectionStatus)
        assertEquals(ProjectionStatus.WAITING_FOR_GAP, oldRetry.snapshot.projectionStatus)
        assertEquals(1L, emptyBatch.snapshot.checkpoint.lastSequence)
    }

    @Test
    fun `known ledger head prevents a partial catch up batch from publishing current`() {
        val waiting = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(attempt("a-1", 1), attempt("a-3", 3)),
            3,
        )

        val onlyGap = projector.project(waiting.snapshot, listOf(attempt("a-2", 2)), 3)

        assertEquals(2L, onlyGap.snapshot.checkpoint.lastSequence)
        assertEquals(3L, onlyGap.snapshot.knownLedgerHeadSequence)
        assertEquals(ProjectionStatus.CATCHING_UP, onlyGap.snapshot.projectionStatus)

        val caughtUp = projector.project(onlyGap.snapshot, listOf(attempt("a-3", 3)), 3)
        assertEquals(ProjectionStatus.CURRENT, caughtUp.snapshot.projectionStatus)
    }

    @Test
    fun `multi knowledge evidence is distributed by accepted attribution weight`() {
        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(
                attempt(
                    id = "a-1",
                    sequence = 1,
                    attributions = listOf(
                        attribution("binding-a", "kc-a", 0.7),
                        attribution("binding-b", "kc-b", 0.3),
                    ),
                ),
            ),
            1,
        )

        val kcA = result.snapshot.knowledgeMasteryStates.getValue("kc-a")
        val kcB = result.snapshot.knowledgeMasteryStates.getValue("kc-b")
        assertEquals(1.0, kcA.evidenceMass + kcB.evidenceMass, 1e-9)
        assertTrue(kcA.masteryScore > kcB.masteryScore)
    }

    @Test
    fun `ambiguous multi knowledge evidence updates memory but not mastery`() {
        val ambiguous = attribution("binding-a", "kc-a", 0.5).copy(
            certainty = EvidenceAttributionCertainty.AMBIGUOUS,
        )
        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(attempt("a-1", 1, listOf(ambiguous))),
            1,
        )

        assertTrue(result.snapshot.problemMemoryStates.containsKey("unit-1"))
        assertTrue(result.snapshot.knowledgeMasteryStates.isEmpty())
        assertEquals(setOf("a-1"), result.ambiguousAttemptIds)
    }

    @Test
    fun `answer reveal schedules short memory review without mastery evidence`() {
        val revealed = attempt("a-1", 1).copy(
            evidence = LearningEvidence(
                direction = LearningEvidenceDirection.NONE,
                weight = 0.0,
                reason = LearningEvidenceReason.ANSWER_REVEALED,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.ANSWER_REVEALED,
        )

        val result = projector.project(LearnerSnapshot.empty("learner"), listOf(revealed), 1)
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(1, memory.answerRevealCount)
        assertEquals(revealed.occurredAtEpochMillis + 10 * 60_000, memory.nextReviewAtEpochMillis)
        assertTrue(result.snapshot.knowledgeMasteryStates.isEmpty())
    }

    @Test
    fun `terminal answer reveal updates memory when learner leaves without submitting`() {
        val reveal = revealOutcome("reveal-1", 1, "presentation-1")

        val result = projector.project(LearnerSnapshot.empty("learner"), listOf(reveal), 1)
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(setOf("reveal-1"), result.appliedAnswerRevealOutcomeIds)
        assertEquals(1, memory.answerRevealCount)
        assertEquals(reveal.occurredAtEpochMillis + 10 * 60_000, memory.nextReviewAtEpochMillis)
        assertTrue(result.snapshot.knowledgeMasteryStates.isEmpty())

        val retry = projector.project(result.snapshot, listOf(reveal), 1)
        assertEquals(setOf("reveal-1"), retry.ignoredAnswerRevealOutcomeIds)
        assertEquals(result.snapshot, retry.snapshot)
    }

    @Test
    fun `answer reveal does not consume response ordinal or double count a later response`() {
        val presentationId = "presentation-shared"
        val reveal = revealOutcome("reveal-1", 1, presentationId)
        val correctAfterReveal = attempt("a-2", 2).copy(
            presentationId = presentationId,
            responseOrdinal = 1,
            evidence = LearningEvidence(
                LearningEvidenceDirection.NONE,
                0.0,
                LearningEvidenceReason.ANSWER_REVEALED,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.ANSWER_REVEALED,
        )

        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(reveal, correctAfterReveal),
            2,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(setOf("a-2"), result.appliedAttemptIds)
        assertEquals(1, memory.answerRevealCount)
        assertEquals(1, memory.lapseCount)
        assertTrue(result.snapshot.knowledgeMasteryStates.isEmpty())
    }

    @Test
    fun `incorrect response after reveal updates mastery but does not double count memory`() {
        val presentationId = "presentation-shared"
        val reveal = revealOutcome("reveal-1", 1, presentationId)
        val wrongAfterReveal = attempt("a-2", 2).copy(
            presentationId = presentationId,
            responseOrdinal = 1,
            evidence = LearningEvidence(
                LearningEvidenceDirection.NEGATIVE,
                0.6,
                LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )

        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(reveal, wrongAfterReveal),
            2,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(1, memory.answerRevealCount)
        assertEquals(1, memory.lapseCount)
        assertEquals(0.6, result.snapshot.knowledgeMasteryStates.getValue("kc-a").evidenceMass, 1e-9)
    }

    @Test
    fun `response then answer reveal for the same presentation updates memory only once`() {
        val presentationId = "presentation-shared"
        val wrongResponse = attempt("a-1", 1).copy(
            presentationId = presentationId,
            evidence = LearningEvidence(
                LearningEvidenceDirection.NEGATIVE,
                1.0,
                LearningEvidenceReason.INDEPENDENT_INCORRECT,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )
        val reveal = revealOutcome("reveal-2", 2, presentationId)

        val result = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(wrongResponse, reveal),
            2,
        )
        val memory = result.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(setOf("a-1"), result.appliedAttemptIds)
        assertEquals(setOf("reveal-2"), result.appliedAnswerRevealOutcomeIds)
        assertEquals(1, memory.lapseCount)
        assertEquals(0, memory.answerRevealCount)
        assertEquals(wrongResponse.occurredAtEpochMillis, memory.lastReviewedAtEpochMillis)
    }

    @Test
    fun `different reveal ids cannot terminate the same presentation twice`() {
        val first = revealOutcome("reveal-1", 1, "presentation-1")
        val projected = projector.project(LearnerSnapshot.empty("learner"), listOf(first), 1)
        val duplicate = revealOutcome("reveal-2", 2, "presentation-1")

        val result = projector.project(projected.snapshot, listOf(duplicate), 2)

        assertEquals(setOf("reveal-2"), result.conflictedAnswerRevealOutcomeIds)
        assertEquals(1L, result.snapshot.checkpoint.lastSequence)
    }

    @Test
    fun `answer reveal full replay is deterministic under clock rollback`() {
        val first = revealOutcome("reveal-1", 1, "presentation-1", occurredAt = 5_000)
        val rollback = revealOutcome("reveal-2", 2, "presentation-2", occurredAt = 1_000)

        val firstReplay = projector.replay("learner", listOf(first, rollback))
        val secondReplay = projector.replay("learner", listOf(first, rollback))
        val memory = firstReplay.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(firstReplay, secondReplay)
        assertEquals(2, memory.answerRevealCount)
        assertEquals(1, memory.clockAnomalyCount)
        assertEquals(5_000L, memory.lastReviewedAtEpochMillis)
    }

    @Test
    fun `full ledger replay applies correction instead of pretending incremental support`() {
        val wrong = attempt("a-1", 1).copy(
            evidence = LearningEvidence(
                direction = LearningEvidenceDirection.NEGATIVE,
                weight = 1.0,
                reason = LearningEvidenceReason.INDEPENDENT_INCORRECT,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )
        val correction = AttemptCorrection(
            correctionId = "c-1",
            attemptId = "a-1",
            replacementEvidence = positiveEvidence(),
            replacementMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            reasonMarkdown = "录入答案键有误",
            occurredAtEpochMillis = 2_000,
            eventSequence = 2,
        )

        val replay = projector.replay("learner", listOf(wrong, correction))
        val memory = replay.snapshot.problemMemoryStates.getValue("unit-1")

        assertEquals(0, memory.lapseCount)
        assertEquals(1, memory.independentCorrectCount)
        assertEquals(setOf("a-1"), replay.correctedAttemptIds)
        assertEquals(2L, replay.snapshot.checkpoint.lastSequence)

        val originalRetry = projector.project(replay.snapshot, listOf(wrong), 2)
        assertEquals(setOf("a-1"), originalRetry.ignoredAttemptIds)
        assertTrue(originalRetry.conflictedAttemptIds.isEmpty())
    }

    @Test
    fun `review queue identity changes across learner and local day`() {
        val now = 10 * DAY_MILLIS
        val planner = ReviewPlanner()
        val candidate = ReviewCandidate("unit-1", emptySet(), "family-1", null, 0.5, 60)

        val first = planner.plan(
            ReviewPlanningRequest(
                snapshotForReview("learner-a", now), listOf(candidate), 10, "Asia/Shanghai", 60, now,
            ),
        )
        val nextDay = planner.plan(
            ReviewPlanningRequest(
                snapshotForReview("learner-a", now), listOf(candidate), 11, "Asia/Shanghai", 60, now,
            ),
        )
        val otherLearner = planner.plan(
            ReviewPlanningRequest(
                snapshotForReview("learner-b", now), listOf(candidate), 10, "Asia/Shanghai", 60, now,
            ),
        )
        val otherBudget = planner.plan(
            ReviewPlanningRequest(
                snapshotForReview("learner-a", now), listOf(candidate), 10, "Asia/Shanghai", 120, now,
            ),
        )
        val otherTimeZone = planner.plan(
            ReviewPlanningRequest(
                snapshotForReview("learner-a", now), listOf(candidate), 10, "Asia/Urumqi", 60, now,
            ),
        )

        assertNotEquals(first.queueItems.single().queueItemId, nextDay.queueItems.single().queueItemId)
        assertNotEquals(first.queueItems.single().queueItemId, otherLearner.queueItems.single().queueItemId)
        assertNotEquals(first.queueItems.single().queueItemId, otherBudget.queueItems.single().queueItemId)
        assertNotEquals(first.queueItems.single().queueItemId, otherTimeZone.queueItems.single().queueItemId)
    }

    @Test
    fun `learning evidence independence is derived from reason`() {
        val independent = positiveEvidence()
        val assisted = LearningEvidence(
            direction = LearningEvidenceDirection.POSITIVE,
            weight = 0.6,
            reason = LearningEvidenceReason.CORRECT_AFTER_HINT,
        )

        assertTrue(independent.isIndependent)
        assertFalse(assisted.isIndependent)
    }

    @Test
    fun `shared canonical fingerprint is order stable and payload sensitive`() {
        val original = attempt(
            "a-1",
            1,
            listOf(
                attribution("binding-a", "kc-a", 0.6),
                attribution("binding-b", "kc-b", 0.4),
            ),
        )
        val reordered = original.copy(
            assessmentSnapshot = original.assessmentSnapshot.copy(
                attributions = original.assessmentSnapshot.attributions.reversed(),
            ),
        )

        assertEquals(
            LearningLedgerFingerprint.attempt(original),
            LearningLedgerFingerprint.attempt(reordered),
        )
        assertNotEquals(
            LearningLedgerFingerprint.attempt(original),
            LearningLedgerFingerprint.attempt(original.copy(durationSeconds = 61)),
        )
    }

    @Test
    fun `conflicted mastery recovers after two new supported independent families and days`() {
        val highMastery = KnowledgeMasteryState(
            knowledgeNodeId = "kc-a",
            masteryScore = 0.94,
            conservativeMasteryScore = 0.88,
            evidenceMass = 4.0,
            status = MasteryStatus.MASTERED,
            calibrationSupport = CalibrationSupport.SUPPORTED,
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 4,
            lastEvidenceAtEpochMillis = 400,
        )
        val snapshot = LearnerSnapshot(
            learnerId = "learner",
            knowledgeMasteryStates = mapOf("kc-a" to highMastery),
            checkpoint = ProjectionCheckpoint(4, LearningProjector.VERSION, 400),
            generatedAtEpochMillis = 400,
        )
        val error = attempt("a-5", 5).copy(
            evidence = LearningEvidence(
                LearningEvidenceDirection.NEGATIVE,
                1.0,
                LearningEvidenceReason.INDEPENDENT_INCORRECT,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )
        val lowWeightResult = projector.project(
            snapshot,
            listOf(
                error,
                attempt(
                    "a-6-low",
                    6,
                    listOf(
                        attribution("binding-low-6", "kc-a", 0.01).copy(
                            role = EvidenceAttributionRole.PRIMARY,
                        ),
                    ),
                ),
                attempt(
                    "a-7-low",
                    7,
                    listOf(
                        attribution("binding-low-7", "kc-a", 0.01).copy(
                            role = EvidenceAttributionRole.PRIMARY,
                        ),
                    ),
                ),
            ),
            7,
        )

        assertEquals(
            MasteryStatus.CONFLICTED,
            lowWeightResult.snapshot.knowledgeMasteryStates.getValue("kc-a").status,
        )

        val result = projector.project(
            snapshot,
            listOf(error, attempt("a-6", 6), attempt("a-7", 7)),
            7,
        )
        val recovered = result.snapshot.knowledgeMasteryStates.getValue("kc-a")

        assertNotEquals(MasteryStatus.CONFLICTED, recovered.status)
        assertEquals(null, recovered.conflictSinceSequence)
    }

    @Test
    fun `bounded idempotency window is not used as presentation ordinal authority`() {
        val previous = LearnerSnapshot(
            learnerId = "learner",
            checkpoint = ProjectionCheckpoint(4_096, LearningProjector.VERSION, 4_096_000),
            knownLedgerHeadSequence = 4_096,
            generatedAtEpochMillis = 4_096_000,
        )
        val retry = attempt("a-4097", 4_097).copy(
            presentationId = "old-active-presentation",
            responseOrdinal = 2,
            evidence = LearningEvidence(
                LearningEvidenceDirection.POSITIVE,
                0.6,
                LearningEvidenceReason.CORRECT_ON_RETRY,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.ASSISTED_RECALL,
        )

        val result = projector.project(previous, listOf(retry), 4_097)

        assertEquals(setOf("a-4097"), result.appliedAttemptIds)
        assertEquals(ProjectionStatus.CURRENT, result.snapshot.projectionStatus)
    }

    @Test
    fun `authoritative reveal state prevents memory and mastery replay after bounded records are evicted`() {
        val presentationId = "old-revealed-presentation"
        val seeded = projector.project(
            LearnerSnapshot.empty("learner"),
            listOf(revealOutcome("old-reveal", 1, presentationId)),
            1,
        )
        val previous = seeded.snapshot.copy(
            checkpoint = ProjectionCheckpoint(4_096, LearningProjector.VERSION, 4_096_000),
            knownLedgerHeadSequence = 4_096,
            generatedAtEpochMillis = 4_096_000,
            appliedAnswerRevealRecords = emptyMap(),
        )
        val forgedIndependentResponse = attempt("late-response", 4_097).copy(
            presentationId = presentationId,
            responseOrdinal = 1,
        )

        val result = projector.project(
            previous = previous,
            events = listOf(forgedIndependentResponse),
            knownLedgerHeadSequence = 4_097,
            authoritativePresentationStates = mapOf(
                presentationId to PresentationProjectionState(
                    presentationId = presentationId,
                    asOfLedgerSequence = 4_096,
                    memoryProjectionApplied = true,
                    answerRevealSequence = 1,
                ),
            ),
        )

        assertEquals(previous.problemMemoryStates, result.snapshot.problemMemoryStates)
        assertTrue(result.snapshot.knowledgeMasteryStates.isEmpty())
        assertEquals(1L, result.presentationProjectionStates.getValue(presentationId).answerRevealSequence)
    }

    @Test
    fun `authoritative response state prevents reveal memory replay after bounded records are evicted`() {
        val presentationId = "old-answered-presentation"
        val originalResponse = attempt("old-response", 1).copy(
            presentationId = presentationId,
            evidence = LearningEvidence(
                LearningEvidenceDirection.NEGATIVE,
                1.0,
                LearningEvidenceReason.INDEPENDENT_INCORRECT,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )
        val seeded = projector.project(LearnerSnapshot.empty("learner"), listOf(originalResponse), 1)
        val previous = seeded.snapshot.copy(
            checkpoint = ProjectionCheckpoint(4_096, LearningProjector.VERSION, 4_096_000),
            knownLedgerHeadSequence = 4_096,
            generatedAtEpochMillis = 4_096_000,
            appliedAttemptRecords = emptyMap(),
        )
        val lateReveal = revealOutcome("late-reveal", 4_097, presentationId)

        val result = projector.project(
            previous = previous,
            events = listOf(lateReveal),
            knownLedgerHeadSequence = 4_097,
            authoritativePresentationStates = mapOf(
                presentationId to PresentationProjectionState(
                    presentationId = presentationId,
                    asOfLedgerSequence = 4_096,
                    memoryProjectionApplied = true,
                ),
            ),
        )

        assertEquals(previous.problemMemoryStates, result.snapshot.problemMemoryStates)
        assertEquals(4_097L, result.presentationProjectionStates.getValue(presentationId).answerRevealSequence)
    }

    @Test
    fun `correction after answer reveal cannot manufacture independent positive evidence`() {
        val presentationId = "corrected-after-reveal"
        val reveal = revealOutcome("reveal", 1, presentationId)
        val wrong = attempt("wrong", 2).copy(
            presentationId = presentationId,
            assessmentSnapshot = reveal.assessmentSnapshot,
            evidence = LearningEvidence(
                LearningEvidenceDirection.NEGATIVE,
                0.6,
                LearningEvidenceReason.INCORRECT_AFTER_REVEAL,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )
        val correction = AttemptCorrection(
            correctionId = "correction",
            attemptId = wrong.attemptId,
            replacementEvidence = positiveEvidence(),
            replacementMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            reasonMarkdown = "人工复核改为正确，但答案已在修正前揭晓。",
            occurredAtEpochMillis = 3_000,
            eventSequence = 3,
        )

        val result = projector.replay("learner", listOf(reveal, wrong, correction))

        assertTrue(result.snapshot.knowledgeMasteryStates.isEmpty())
        assertEquals(1, result.snapshot.problemMemoryStates.getValue("unit-1").answerRevealCount)
        assertEquals(3_000L, result.snapshot.correctionWatermarkEpochMillis)
    }

    @Test
    fun `later reveal does not retroactively contaminate a corrected earlier response`() {
        val presentationId = "response-before-reveal"
        val wrong = attempt("wrong-before-reveal", 1).copy(
            presentationId = presentationId,
            evidence = LearningEvidence(
                LearningEvidenceDirection.NEGATIVE,
                1.0,
                LearningEvidenceReason.INDEPENDENT_INCORRECT,
            ),
            problemMemoryOutcome = ProblemMemoryOutcome.RETRIEVAL_FAILURE,
        )
        val reveal = revealOutcome("later-reveal", 2, presentationId).copy(
            assessmentSnapshot = wrong.assessmentSnapshot,
        )
        val correction = AttemptCorrection(
            correctionId = "later-correction",
            attemptId = wrong.attemptId,
            replacementEvidence = positiveEvidence(),
            replacementMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
            reasonMarkdown = "原作答发生在揭晓之前，复核确认其正确。",
            occurredAtEpochMillis = 3_000,
            eventSequence = 3,
        )

        val result = projector.replay("learner", listOf(wrong, reveal, correction))
        val mastery = result.snapshot.knowledgeMasteryStates.getValue("kc-a")

        assertEquals(1.0, mastery.evidenceMass, 1e-9)
        assertEquals(1, mastery.independentCorrectObservations.size)
        assertTrue(mastery.independentCorrectObservations.single().isStudyDayTrusted)
    }

    @Test
    fun `clock rollback study day cannot create mastery breadth`() {
        val trustedObservation = com.tingyun.smartmistakebook.core.model.IndependentCorrectObservation(
            itemFamilyId = "family-trusted",
            studyDayEpochDay = 10,
            occurredAtEpochMillis = 5_000,
            eventSequence = 1,
            evidenceWeight = 1.0,
            calibration = CalibrationSnapshot(
                CalibrationSupport.SUPPORTED,
                "calibration-source",
                "calibration-v1",
                0,
                30 * DAY_MILLIS,
            ),
        )
        val priorMastery = KnowledgeMasteryState(
            knowledgeNodeId = "kc-a",
            masteryScore = 0.98,
            conservativeMasteryScore = 0.9,
            evidenceMass = 2.0,
            independentCorrectObservations = listOf(trustedObservation),
            status = MasteryStatus.LEARNING,
            calibrationSupport = CalibrationSupport.SUPPORTED,
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 1,
            lastEvidenceAtEpochMillis = 5_000,
        )
        val previous = LearnerSnapshot(
            learnerId = "learner",
            knowledgeMasteryStates = mapOf("kc-a" to priorMastery),
            checkpoint = ProjectionCheckpoint(1, LearningProjector.VERSION, 5_000),
            generatedAtEpochMillis = 5_000,
        )
        val rollback = attempt("rollback", 2).copy(
            occurredAtEpochMillis = 1_000,
            studyDay = StudyDayContext(11, "Asia/Shanghai", 480),
            assessmentSnapshot = attempt("rollback-seed", 2).assessmentSnapshot.copy(
                itemFamilyId = "family-forged-day",
            ),
        )

        val result = projector.project(previous, listOf(rollback), 2)
        val mastery = result.snapshot.knowledgeMasteryStates.getValue("kc-a")

        assertFalse(mastery.independentCorrectObservations.last().isStudyDayTrusted)
        assertNotEquals(MasteryStatus.MASTERED, mastery.status)
        assertFalse(ClearlyMasteredForSkipPolicy.isSatisfied(mastery, 5_000))
    }

    private fun attempt(
        id: String,
        sequence: Long,
        attributions: List<KnowledgeEvidenceAttribution> = listOf(
            attribution("binding-a", "kc-a", 1.0),
        ),
    ) = Attempt(
        attemptId = id,
        presentationId = "presentation-$id",
        responseOrdinal = 1,
        assessmentSnapshot = AssessmentEvidenceSnapshot(
            snapshotId = "snapshot-$id",
            assessmentItemId = "assessment-1",
            practiceUnitId = "unit-1",
            problemRevisionId = "revision-1",
            answerSpecId = "answer-1",
            itemFamilyId = "family-$id",
            sourceBundleId = "source-1",
            taxonomyVersion = "taxonomy-v1",
            verification = AssessmentSnapshotVerification.VERIFIED,
            calibration = CalibrationSnapshot(
                support = CalibrationSupport.SUPPORTED,
                sourceId = "calibration-source",
                version = "calibration-v1",
                validFromEpochMillis = 0,
                validUntilEpochMillis = 30 * DAY_MILLIS,
            ),
            attributions = attributions,
            capturedAtEpochMillis = 0,
        ),
        evidence = positiveEvidence(),
        problemMemoryOutcome = ProblemMemoryOutcome.INDEPENDENT_RECALL,
        occurredAtEpochMillis = sequence * 1_000,
        durationSeconds = 60,
        studyDay = StudyDayContext(sequence, "Asia/Shanghai", 480),
        eventSequence = sequence,
    )

    private fun revealOutcome(
        id: String,
        sequence: Long,
        presentationId: String,
        occurredAt: Long = sequence * 1_000,
    ): AnswerRevealOutcome {
        val seed = attempt("seed-$id", sequence)
        return AnswerRevealOutcome(
            outcomeId = id,
            presentationId = presentationId,
            assessmentSnapshot = seed.assessmentSnapshot,
            occurredAtEpochMillis = occurredAt,
            studyDay = seed.studyDay,
            eventSequence = sequence,
        )
    }

    private fun positiveEvidence() = LearningEvidence(
        direction = LearningEvidenceDirection.POSITIVE,
        weight = 1.0,
        reason = LearningEvidenceReason.INDEPENDENT_CORRECT,
    )

    private fun attribution(
        bindingId: String,
        knowledgeNodeId: String,
        weight: Double,
    ) = KnowledgeEvidenceAttribution(
        bindingId = bindingId,
        knowledgeNodeId = knowledgeNodeId,
        weight = weight,
        basisRevisionId = "revision-1",
        taxonomyVersion = "taxonomy-v1",
        role = if (weight >= 0.5) EvidenceAttributionRole.PRIMARY else EvidenceAttributionRole.SECONDARY,
        certainty = EvidenceAttributionCertainty.DIRECT,
    )

    private fun snapshotForReview(learnerId: String, now: Long) = LearnerSnapshot.empty(
        learnerId = learnerId,
        projectorVersion = LearningProjector.VERSION,
    ).copy(generatedAtEpochMillis = now)

    private companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}
