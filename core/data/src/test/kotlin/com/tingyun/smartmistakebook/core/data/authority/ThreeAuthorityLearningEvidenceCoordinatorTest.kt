package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.mastery.database.LearningObservationFacts
import com.tingyun.smartmistakebook.core.mastery.database.PendingOpenResponseDisposition
import com.tingyun.smartmistakebook.core.mastery.database.PendingOpenResponseFacts
import com.tingyun.smartmistakebook.core.mastery.database.PendingOpenResponseResult
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationDisposition
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationResult
import com.tingyun.smartmistakebook.core.mastery.database.VerifiedEphemeralKnowledgeEvidence
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionSnapshot
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewSessionStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewTransitionResult
import com.tingyun.smartmistakebook.core.student.mistake.database.SubmitStudentReviewResponseCommand
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeAuthorityLearningEvidenceCoordinatorTest {
    @Test
    fun savedReviewCommitsOnlyThroughStudentAuthorityThenDrainsRelay() {
        val authority = FakeLearningEvidenceAuthority()
        val result = runSuspend { coordinator(authority).submitSavedReview(savedReview()) }

        assertEquals(
            SavedReviewEvidenceWriteResult.Saved(
                transition = SavedReviewTransitionDisposition.APPLIED,
                sessionVersion = 2L,
                relayDelivery = AuthorityRelayDelivery.DELIVERED,
            ),
            result,
        )
        assertEquals(1, authority.savedReviewCommands.size)
        assertEquals(1, authority.relayDrainCount)
        assertTrue(authority.observations.isEmpty())
        assertTrue(authority.pendingOpenResponses.isEmpty())
    }

    @Test
    fun savedReviewRelayFailureReportsPendingRetryWithoutReversingTheSave() {
        val authority =
            FakeLearningEvidenceAuthority(
                relayFailure = IllegalStateException("relay unavailable"),
            )

        val result = runSuspend { coordinator(authority).submitSavedReview(savedReview()) }

        assertEquals(
            SavedReviewEvidenceWriteResult.Saved(
                transition = SavedReviewTransitionDisposition.APPLIED,
                sessionVersion = 2L,
                relayDelivery = AuthorityRelayDelivery.PENDING_RETRY,
            ),
            result,
        )
        assertEquals(1, authority.savedReviewCommands.size)
        assertEquals(1, authority.relayDrainCount)
    }

    @Test
    fun savedReviewReloadDoesNotDrainRelay() {
        val authority =
            FakeLearningEvidenceAuthority(
                savedReviewResult = StudentReviewTransitionResult.ReloadRequired,
            )

        val result = runSuspend { coordinator(authority).submitSavedReview(savedReview()) }

        assertEquals(SavedReviewEvidenceWriteResult.ReloadRequired, result)
        assertEquals(0, authority.relayDrainCount)
    }

    @Test
    fun savedReviewSeparatesRejectedInputFromStorageFailure() {
        val rejected =
            runSuspend {
                coordinator(FakeLearningEvidenceAuthority())
                    .submitSavedReview(savedReview(responseFingerprint = "not-a-fingerprint"))
            }
        val unavailable =
            runSuspend {
                coordinator(
                    FakeLearningEvidenceAuthority(
                        savedReviewFailure = IllegalStateException("disk unavailable"),
                    ),
                ).submitSavedReview(savedReview())
            }

        assertEquals(SavedReviewEvidenceWriteResult.Rejected, rejected)
        assertEquals(SavedReviewEvidenceWriteResult.StorageUnavailable, unavailable)
    }

    @Test
    fun locallyVerifiedUnsavedResponseWritesOnlyOneMasteryObservation() {
        val authority = FakeLearningEvidenceAuthority()

        val result =
            runSuspend {
                coordinator(authority).recordUnsavedStudy(
                    unsavedStudy(
                        response =
                            OrdinaryStudyResponse.Choice(
                                responseCanonicalFingerprint = "7".repeat(64),
                                verificationPolicyVersion = "choice-rubric-v1",
                                isCorrect = true,
                            ),
                    ),
                )
            }

        assertTrue(result is UnsavedStudyEvidenceWriteResult.Recorded)
        assertEquals(1, authority.observations.size)
        assertTrue(authority.savedReviewCommands.isEmpty())
        assertTrue(authority.pendingOpenResponses.isEmpty())
        assertEquals(true, authority.observations.single().answerWasCorrect)
    }

    @Test
    fun masteryConflictIsNotReportedAsStorageOrRejection() {
        val authority =
            FakeLearningEvidenceAuthority(
                observationDisposition = TrustedLearningObservationDisposition.CONFLICT,
            )

        val result =
            runSuspend {
                coordinator(authority).recordUnsavedStudy(
                    unsavedStudy(
                        response =
                            OrdinaryStudyResponse.Numeric(
                                responseCanonicalFingerprint = "7".repeat(64),
                                verificationPolicyVersion = "numeric-rubric-v1",
                                isCorrect = false,
                            ),
                    ),
                )
            }

        assertTrue(result is UnsavedStudyEvidenceWriteResult.Conflict)
    }

    @Test
    fun unverifiedFreeResponseQueuesDirectionUnknownMasteryFactOnly() {
        val authority = FakeLearningEvidenceAuthority()

        val result =
            runSuspend {
                coordinator(authority).recordUnsavedStudy(
                    unsavedStudy(
                        response =
                            OrdinaryStudyResponse.UnverifiedFreeResponse(
                                responseCanonicalFingerprint = "8".repeat(64),
                                verificationPolicyVersion = "open-response-v1",
                            ),
                    ),
                )
            }

        assertTrue(result is UnsavedStudyEvidenceWriteResult.PendingAttributionQueued)
        assertEquals(1, authority.pendingOpenResponses.size)
        assertTrue(authority.observations.isEmpty())
        assertTrue(authority.savedReviewCommands.isEmpty())
        val pending = authority.pendingOpenResponses.single()
        assertEquals("8".repeat(64), pending.responseCanonicalFingerprint)
        assertEquals(SubjectKind.MATH, pending.subject)
    }

    @Test
    fun pendingOpenResponseDistinguishesDuplicateConflictRejectedAndStorageFailure() {
        val command =
            unsavedStudy(
                response =
                    OrdinaryStudyResponse.UnverifiedFreeResponse(
                        responseCanonicalFingerprint = "8".repeat(64),
                        verificationPolicyVersion = "open-response-v1",
                    ),
            )
        fun resultFor(
            disposition: PendingOpenResponseDisposition,
        ): UnsavedStudyEvidenceWriteResult =
            runSuspend {
                coordinator(
                    FakeLearningEvidenceAuthority(pendingDisposition = disposition),
                ).recordUnsavedStudy(command)
            }

        assertTrue(
            resultFor(PendingOpenResponseDisposition.DUPLICATE) is
                UnsavedStudyEvidenceWriteResult.PendingAttributionQueued,
        )
        assertTrue(
            resultFor(PendingOpenResponseDisposition.CONFLICT) is
                UnsavedStudyEvidenceWriteResult.Conflict,
        )
        assertTrue(
            resultFor(PendingOpenResponseDisposition.REJECTED) is
                UnsavedStudyEvidenceWriteResult.Rejected,
        )
        assertTrue(
            runSuspend {
                coordinator(
                    FakeLearningEvidenceAuthority(
                        pendingFailure = IllegalStateException("disk unavailable"),
                    ),
                ).recordUnsavedStudy(command)
            } is UnsavedStudyEvidenceWriteResult.StorageUnavailable,
        )
    }

    @Test
    fun knowledgeProofListIsCopiedBoundedAndOneCatalogGeneration() {
        val proofAuthority = KnowledgeReferenceProofAuthority.create()
        val mutableProofs =
            mutableListOf(
                proofAuthority.issuer.issue(
                    knowledgeNode(0),
                    "a".repeat(64),
                    1L,
                ),
            )
        val command = unsavedStudy(verifiedKnowledgeProofs = mutableProofs)
        mutableProofs.clear()

        assertEquals(1, command.verifiedKnowledgeProofs.size)
        assertTrue(
            runCatching {
                unsavedStudy(
                    verifiedKnowledgeProofs =
                        listOf(
                            proofAuthority.issuer.issue(
                                knowledgeNode(0),
                                "a".repeat(64),
                                1L,
                            ),
                            proofAuthority.issuer.issue(
                                knowledgeNode(1),
                                "b".repeat(64),
                                2L,
                            ),
                        ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                unsavedStudy(
                    verifiedKnowledgeProofs =
                        (0..32).map { index ->
                            proofAuthority.issuer.issue(
                                knowledgeNode(index),
                                "a".repeat(64),
                                1L,
                            )
                        },
                )
            }.isFailure,
        )
    }

    @Test
    fun changedPendingPayloadKeepsIdentityButChangesTheMasteryCanonicalPayload() {
        val firstAuthority = FakeLearningEvidenceAuthority()
        val changedAuthority = FakeLearningEvidenceAuthority()
        val first =
            unsavedStudy(
                response =
                    OrdinaryStudyResponse.UnverifiedFreeResponse(
                        responseCanonicalFingerprint = "8".repeat(64),
                        verificationPolicyVersion = "open-response-v1",
                    ),
            )
        val changed =
            unsavedStudy(
                response =
                    OrdinaryStudyResponse.UnverifiedFreeResponse(
                        responseCanonicalFingerprint = "9".repeat(64),
                        verificationPolicyVersion = "open-response-v1",
                    ),
            )

        runSuspend { coordinator(firstAuthority).recordUnsavedStudy(first) }
        runSuspend { coordinator(changedAuthority).recordUnsavedStudy(changed) }

        val firstFacts = firstAuthority.pendingOpenResponses.single()
        val changedFacts = changedAuthority.pendingOpenResponses.single()
        assertEquals(firstFacts.sourceFactId, changedFacts.sourceFactId)
        assertNotEquals(firstFacts.responseCanonicalFingerprint, changedFacts.responseCanonicalFingerprint)
    }

    private fun coordinator(
        authority: FakeLearningEvidenceAuthority,
    ): ThreeAuthorityLearningEvidenceCoordinator =
        ThreeAuthorityLearningEvidenceCoordinator(authority)

    private fun savedReview(
        responseFingerprint: String = "1".repeat(64),
    ): SavedReviewEvidenceCommand =
        SavedReviewEvidenceCommand(
            transitionId = "transition-1",
            sessionId = "session-1",
            queueItemId = "queue-1",
            expectedSessionVersion = 1L,
            presentationId = "presentation-1",
            observationId = "observation-1",
            submissionId = "submission-1",
            responseForm = StudyEvidenceResponseForm.CHOICE,
            responseCanonicalFingerprint = responseFingerprint,
            verificationOutcome = StudyEvidenceVerificationOutcome.CORRECT,
            attemptOrdinal = 1,
            hintCount = 0,
            answerWasRevealed = false,
            verificationPolicyVersion = "choice-rubric-v1",
            elapsedDurationMillis = 1_000L,
            schedule =
                SavedReviewSchedule(
                    nextAvailableAtEpochMillis = 2_000L,
                    nextDueAtEpochMillis = 3_000L,
                    schedulingPolicyVersion = "schedule-v1",
                ),
            capturedAtEpochMillis = 1_000L,
        )

    private fun unsavedStudy(
        verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof> = emptyList(),
        response: OrdinaryStudyResponse =
            OrdinaryStudyResponse.Choice(
                responseCanonicalFingerprint = "7".repeat(64),
                verificationPolicyVersion = "choice-rubric-v1",
                isCorrect = true,
            ),
    ): UnsavedStudyEvidenceCommand =
        UnsavedStudyEvidenceCommand(
            submissionId = "ordinary-submission-1",
            subject = SubjectKind.MATH,
            presentationFingerprint = "1".repeat(64),
            problemFingerprint = "2".repeat(64),
            problemFamilyFingerprint = "3".repeat(64),
            interactionReferenceId = "turn-1",
            attributionPolicyVersion = "attribution-v1",
            verifiedKnowledgeProofs = verifiedKnowledgeProofs,
            response = response,
            attemptOrdinal = 1,
            hintCount = 0,
            answerWasRevealed = false,
            elapsedDurationMillis = 1_000L,
            occurredAtEpochMillis = 1_000L,
        )

    private fun knowledgeNode(index: Int): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "math.node.$index",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )
}

private class FakeLearningEvidenceAuthority(
    private val savedReviewResult: StudentReviewTransitionResult =
        StudentReviewTransitionResult.Applied(reviewSession()),
    private val savedReviewFailure: Exception? = null,
    private val relayFailure: Exception? = null,
    private val observationDisposition: TrustedLearningObservationDisposition =
        TrustedLearningObservationDisposition.ADMITTED,
    private val pendingDisposition: PendingOpenResponseDisposition =
        PendingOpenResponseDisposition.QUEUED,
    private val pendingFailure: Exception? = null,
) : LearnerBoundLearningEvidenceAuthority {
    override val learnerId: String = "learner-1"
    val savedReviewCommands = mutableListOf<SubmitStudentReviewResponseCommand>()
    val observations = mutableListOf<LearningObservationFacts>()
    val pendingOpenResponses = mutableListOf<PendingOpenResponseFacts>()
    var relayDrainCount: Int = 0

    override suspend fun submitStudentReview(
        command: SubmitStudentReviewResponseCommand,
    ): StudentReviewTransitionResult {
        savedReviewCommands += command
        savedReviewFailure?.let { throw it }
        return savedReviewResult
    }

    override fun authorizeKnowledge(
        proofs: List<VerifiedKnowledgeReferenceProof>,
    ): List<VerifiedEphemeralKnowledgeEvidence> {
        assertTrue(proofs.isEmpty())
        return emptyList()
    }

    override suspend fun recordObservation(
        facts: LearningObservationFacts,
    ): TrustedLearningObservationResult {
        observations += facts
        return TrustedLearningObservationResult(
            observationId = facts.observationId,
            disposition = observationDisposition,
        )
    }

    override suspend fun enqueuePendingOpenResponse(
        facts: PendingOpenResponseFacts,
    ): PendingOpenResponseResult {
        pendingOpenResponses += facts
        pendingFailure?.let { throw it }
        return PendingOpenResponseResult(
            sourceFactId = facts.sourceFactId,
            reviewCaseId =
                "review-case-1".takeIf {
                    pendingDisposition == PendingOpenResponseDisposition.QUEUED ||
                        pendingDisposition == PendingOpenResponseDisposition.DUPLICATE
                },
            disposition = pendingDisposition,
        )
    }

    override suspend fun drainAuthorityRelay() {
        relayDrainCount += 1
        relayFailure?.let { throw it }
    }
}

private fun reviewSession(): StudentReviewSessionSnapshot =
    StudentReviewSessionSnapshot(
        sessionId = "session-1",
        planId = "plan-1",
        status = StudentReviewSessionStatus.COMPLETED,
        sessionVersion = 2L,
        currentItem = null,
        currentPresentationId = null,
        startedAtEpochMillis = 0L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = 1_000L,
    )

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(value: Result<T>) {
                result = value
            }
        },
    )
    return checkNotNull(result).getOrThrow()
}
