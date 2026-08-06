package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class LearnerMasteryAuthorityTest {
    private val knowledgeProofAuthority = KnowledgeReferenceProofAuthority.create()

    @Test
    fun ephemeralKnowledgeAuthorizationRejectsAnotherProofAuthority() {
        val authority = authority(FakeLearnerMasteryStore())
        val other = KnowledgeReferenceProofAuthority.create()
        val foreignProof =
            other.issuer.issue(
                node(),
                MANIFEST_FINGERPRINT,
                3L,
            )

        assertTrue(
            runCatching {
                authority.authorizeEphemeralKnowledge(foreignProof)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                authority.authorizeEphemeralKnowledge(
                    knowledgeProofAuthority.issuer.issue(
                        node(),
                        MANIFEST_FINGERPRINT,
                        3L,
                    ),
                )
            }.isSuccess,
        )
    }

    @Test
    fun pendingOpenResponseQueuesDirectionUnknownFactAndBindsFullPayloadToIdentity() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val evidence =
            authority.authorizeEphemeralKnowledge(
                knowledgeProofAuthority.issuer.issue(
                    node(),
                    MANIFEST_FINGERPRINT,
                    3L,
                ),
            )
        val first = pendingOpenResponseFacts(evidence)

        val queued = runSuspend { authority.enqueuePendingOpenResponse(first) }
        val duplicate = runSuspend { authority.enqueuePendingOpenResponse(first) }
        val conflict =
            runSuspend {
                authority.enqueuePendingOpenResponse(
                    pendingOpenResponseFacts(
                        evidence = evidence,
                        responseFingerprint = "f".repeat(64),
                    ),
                )
            }

        assertEquals(PendingOpenResponseDisposition.QUEUED, queued.disposition)
        assertEquals(PendingOpenResponseDisposition.DUPLICATE, duplicate.disposition)
        assertEquals(PendingOpenResponseDisposition.CONFLICT, conflict.disposition)
        assertEquals(queued.reviewCaseId, duplicate.reviewCaseId)
        assertNull(conflict.reviewCaseId)
        assertEquals(
            ObservedLearningOutcome.PENDING_REVIEW,
            store.lastPendingSourceFact?.outcome,
        )
        assertEquals(
            TrustedLearningResponseForm.FREE_RESPONSE,
            store.lastPendingSourceFact?.responseForm,
        )
        assertTrue(store.lastCandidate?.proposedAttributions.orEmpty().isEmpty())
    }

    @Test
    fun pendingOpenResponseRejectsKnowledgeFromAnotherRuntimeBinding() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val foreignEvidence =
            VerifiedEphemeralKnowledgeEvidence.create(
                knowledgeNode = node(),
                manifestFingerprint = MANIFEST_FINGERPRINT,
                activationGeneration = 3L,
                runtimeBindingId = "another-runtime-binding",
            )

        val result =
            runSuspend {
                authority.enqueuePendingOpenResponse(
                    pendingOpenResponseFacts(foreignEvidence),
                )
            }

        assertEquals(PendingOpenResponseDisposition.REJECTED, result.disposition)
        assertTrue(store.pendingOpenResponses.isEmpty())
    }

    @Test
    fun ephemeralContextRejectsMixedCatalogGenerationsAndCopiesItsEvidenceList() {
        val mutableEvidence =
            mutableListOf(
                VerifiedEphemeralKnowledgeEvidence.create(
                    knowledgeNode = node(),
                    manifestFingerprint = MANIFEST_FINGERPRINT,
                    activationGeneration = 3L,
                    runtimeBindingId = RUNTIME_BINDING_ID,
                ),
            )
        val context =
            EphemeralTutorProblemLearningContext(
                problemFingerprint = "1".repeat(64),
                problemFamilyFingerprint = "2".repeat(64),
                tutorTurnReferenceId = "turn-copy",
                submissionEvidenceFingerprint = "3".repeat(64),
                attributionModelVersion = "attribution-v1",
                verifiedKnowledgeEvidence = mutableEvidence,
            )
        mutableEvidence.clear()

        assertEquals(1, context.verifiedKnowledgeEvidence.size)
        assertTrue(
            runCatching {
                EphemeralTutorProblemLearningContext(
                    problemFingerprint = "1".repeat(64),
                    problemFamilyFingerprint = "2".repeat(64),
                    tutorTurnReferenceId = "turn-mixed",
                    submissionEvidenceFingerprint = "3".repeat(64),
                    attributionModelVersion = "attribution-v1",
                    verifiedKnowledgeEvidence =
                        listOf(
                            context.verifiedKnowledgeEvidence.single(),
                            VerifiedEphemeralKnowledgeEvidence.create(
                                knowledgeNode =
                                    KnowledgeNodeRef(
                                        subject = SubjectKind.MATH,
                                        knowledgeNodeId = "math.function.linear",
                                        taxonomyVersion = "taxonomy-v1",
                                        knowledgePackVersion = "pack-v1",
                                    ),
                                manifestFingerprint = "e".repeat(64),
                                activationGeneration = 4L,
                                runtimeBindingId = RUNTIME_BINDING_ID,
                            ),
                        ),
                )
            }.isFailure,
        )
    }

    @Test
    fun ephemeralTutorEvidenceUpdatesMasteryWithoutCallingStudentStore() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val evidence =
            VerifiedEphemeralKnowledgeEvidence.create(
                knowledgeNode = node(),
                manifestFingerprint = MANIFEST_FINGERPRINT,
                activationGeneration = 3L,
                runtimeBindingId = RUNTIME_BINDING_ID,
            )
        val result =
            runSuspend {
                authority.recordObservation(
                    tutorCommand(
                        context =
                            EphemeralTutorProblemLearningContext(
                                problemFingerprint = "1".repeat(64),
                                problemFamilyFingerprint = "2".repeat(64),
                                tutorTurnReferenceId = "turn-7",
                                submissionEvidenceFingerprint = "3".repeat(64),
                                attributionModelVersion = "tutor-attribution-v3",
                                verifiedKnowledgeEvidence = listOf(evidence),
                            ),
                    ),
                )
            }

        assertEquals(TrustedLearningObservationDisposition.ADMITTED, result.disposition)
        assertEquals(
            TrustedLearningObservationDisposition.ADMITTED,
            checkNotNull(result.terminalReceipt).disposition,
        )
        assertEquals(
            MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM,
            store.lastSourceFact?.evidenceContextKind,
        )
        assertNull(store.lastSourceFact?.problemRevision)
        assertEquals("1".repeat(64), store.lastSourceFact?.ephemeralProblemFingerprint)
        assertEquals(MANIFEST_FINGERPRINT, store.lastSourceFact?.knowledgeManifestFingerprint)
        assertTrue(
            store.lastCandidate
                ?.proposedAttributions
                .orEmpty()
                .all { it.problemBinding == null },
        )
        assertEquals(MasteryCandidateOrigin.TRUSTED_LOCAL, store.lastCandidate?.candidateOrigin)
    }

    @Test
    fun unattributedTutorConversationIsInertAndNeverTouchesStudentStore() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val result =
            runSuspend {
                authority.recordObservation(
                    tutorCommand(
                        context =
                            EphemeralTutorProblemLearningContext(
                                problemFingerprint = "4".repeat(64),
                                problemFamilyFingerprint = "5".repeat(64),
                                tutorTurnReferenceId = "turn-chat",
                                submissionEvidenceFingerprint = "6".repeat(64),
                                attributionModelVersion = "tutor-attribution-v3",
                                verifiedKnowledgeEvidence = emptyList(),
                            ),
                    ),
                )
            }

        assertEquals(TrustedLearningObservationDisposition.INERT, result.disposition)
        assertEquals(LearningObservationInertReason.NO_ATTRIBUTION, result.inertReason)
        assertEquals(
            LearningObservationInertReason.NO_ATTRIBUTION,
            checkNotNull(result.terminalReceipt).inertReason,
        )
    }

    @Test
    fun ephemeralTurnReferenceRejectsRetainedChatText() {
        val result =
            runCatching {
                EphemeralTutorProblemLearningContext(
                    problemFingerprint = "4".repeat(64),
                    problemFamilyFingerprint = "5".repeat(64),
                    tutorTurnReferenceId = "the learner asked for the full solution",
                    submissionEvidenceFingerprint = "6".repeat(64),
                    attributionModelVersion = "tutor-attribution-v3",
                    verifiedKnowledgeEvidence = emptyList(),
                )
            }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun futureDatedObservationCannotFreezeRecallOrOutboxDelivery() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val context =
            EphemeralTutorProblemLearningContext(
                problemFingerprint = "a".repeat(64),
                problemFamilyFingerprint = "b".repeat(64),
                tutorTurnReferenceId = "turn-future",
                submissionEvidenceFingerprint = "c".repeat(64),
                attributionModelVersion = "tutor-attribution-v3",
                verifiedKnowledgeEvidence = emptyList(),
            )

        val result =
            runCatching {
                runSuspend {
                    authority.recordObservation(
                        tutorCommand(
                            context = context,
                            occurredAtEpochMillis = Long.MAX_VALUE,
                            attestedAtEpochMillis = Long.MAX_VALUE,
                        ),
                    )
                }
            }

        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(store.sourceFacts.isEmpty())
    }

    @Test
    fun savedObservationWithoutRelayedV2BindingWritesNothing() {
        val store = FakeLearnerMasteryStore(problemBindingsAuthorized = false)
        val authority = authority(store)

        val result =
            runCatching {
                runSuspend {
                    authority.recordObservation(savedMistakeCommand("unrelayed-observation"))
                }
            }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(store.sourceFacts.isEmpty())
        assertTrue(store.candidates.isEmpty())
    }

    @Test
    fun fabricatedSavedRevisionWithoutRelayReceiptWritesNothing() {
        val store = FakeLearnerMasteryStore(problemRevisionAuthorized = false)
        val authority = authority(store)

        val result =
            runCatching {
                runSuspend {
                    authority.recordObservation(
                        savedMistakeCommand(
                            observationId = "unverified-saved-revision",
                            includeKnowledgeBinding = false,
                        ),
                    )
                }
            }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(store.sourceFacts.isEmpty())
        assertTrue(store.candidates.isEmpty())
    }

    @Test
    fun savedFactDefersOneSemanticCandidateToScopedModelAccess() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val command = savedMistakeCommand("model-attribution-source")
        val context = command.context as SavedMistakeLearningContext

        val recorded = runSuspend { authority.recordObservation(command) }
        val submitted =
            runSuspend {
                modelAccess(
                    authority = authority,
                    subject = SubjectKind.MATH,
                    modelVersion = "model-attribution-v1",
                    sourceFactId = command.observationId,
                ).candidateSink.submitCandidate(
                    SubmitLearningObservationCandidateCommand(
                        proposedAttributions =
                            context.knowledgeEvidenceBindings.map { binding ->
                                ProposedKnowledgeAttribution(
                                    knowledgeNode = binding.knowledgeNode,
                                    problemBinding = binding,
                                    role = MasteryAttributionRole.PRIMARY,
                                    certainty = MasteryAttributionCertainty.DIRECT,
                                )
                            },
                        confidence = MasteryCandidateConfidence.HIGH,
                    ),
                )
            }

        assertEquals(TrustedLearningObservationDisposition.FACT_STORED, recorded.disposition)
        assertEquals(ModelLearningObservationDisposition.RECEIVED, submitted.disposition)
        assertEquals(1, store.candidates.size)
        assertEquals(MasteryCandidateOrigin.MODEL_SCOPED, store.lastCandidate?.candidateOrigin)
    }

    @Test
    fun inboundMessageReplayIsIdempotentInsideMasteryStore() {
        val envelope = bindingSnapshotEnvelope()
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)

        val first =
            runSuspend {
                authority.relay.accept(
                    message = studentRelayMessage(envelope),
                    receivedAtEpochMillis = NOW,
                )
            }
        assertEquals(1, store.appliedInboundCount)
        val replayed =
            runSuspend {
                authority.relay.accept(
                    message = studentRelayMessage(envelope),
                    receivedAtEpochMillis = NOW,
                )
            }
        assertEquals(LearnerMasteryInboundDisposition.APPLIED, first)
        assertEquals(LearnerMasteryInboundDisposition.DUPLICATE, replayed)
        assertEquals(1, store.appliedInboundCount)
    }

    @Test
    fun reviewRelayStoresPendingAttributionAndDerivesEvidenceLocally() {
        val store =
            FakeLearnerMasteryStore(
                problemRevisionAuthorized = false,
                problemBindingsAuthorized = false,
            )
        val authority = authority(store)
        val envelope =
            reviewObservationEnvelope(
                hintCount = 2,
                answerWasRevealed = true,
                attemptOrdinal = 3,
                verificationOutcome = ReviewVerificationOutcome.INCORRECT,
            )

        val first =
            runSuspend {
                authority.relay.accept(
                    message = studentRelayMessage(envelope),
                    receivedAtEpochMillis = NOW,
                )
            }
        val replay =
            runSuspend {
                authority.relay.accept(
                    message = studentRelayMessage(envelope),
                    receivedAtEpochMillis = NOW,
                )
            }

        assertEquals(LearnerMasteryInboundDisposition.APPLIED, first)
        assertEquals(LearnerMasteryInboundDisposition.DUPLICATE, replay)
        assertEquals(1, store.appliedInboundCount)
        val fact = requireNotNull(store.lastSourceFact)
        assertEquals(ObservedLearningOutcome.INCORRECT, fact.outcome)
        assertEquals(ObservedAssistance.ANSWER_REVEALED, fact.assistance)
        assertEquals(ObservedRetryState.MULTIPLE_RETRIES, fact.retryState)
        assertEquals(2, fact.hintCount)
        assertTrue(fact.answerRevealed)
        assertTrue(!fact.independentlyAnswered)
        assertEquals(
            TrustedLearningVerification.DETERMINISTIC_RUBRIC,
            fact.verificationKind,
        )
        assertNull(fact.authorizedProblemBindingsFingerprint)
        assertTrue(requireNotNull(store.lastCandidate).proposedAttributions.isEmpty())
    }

    @Test
    fun duplicateReviewReceiptStillRepairsAMissingLocalFact() {
        val store =
            FakeLearnerMasteryStore(
                problemRevisionAuthorized = false,
                problemBindingsAuthorized = false,
            )
        val authority = authority(store)
        val envelope =
            reviewObservationEnvelope(
                hintCount = 0,
                answerWasRevealed = false,
                attemptOrdinal = 1,
                verificationOutcome = ReviewVerificationOutcome.CORRECT,
            )
        assertEquals(
            MasteryInboundDisposition.APPLIED,
            runSuspend {
                store.acceptStudentReviewObservation(
                    message = studentRelayMessage(envelope),
                    receivedAtEpochMillis = NOW,
                )
            },
        )
        assertTrue(store.sourceFacts.isEmpty())

        assertEquals(
            LearnerMasteryInboundDisposition.DUPLICATE,
            runSuspend {
                authority.relay.accept(
                    message = studentRelayMessage(envelope),
                    receivedAtEpochMillis = NOW,
                )
            },
        )
        assertEquals(1, store.sourceFacts.size)
        assertEquals(1, store.candidates.size)
    }

    @Test
    fun outboundMessageIsReadAndAcknowledgedThroughBoundedPump() {
        val outbound = learningAttemptEnvelope()
        val store =
            FakeLearnerMasteryStore().apply {
                pendingOutbound += outbound
            }
        val authority = authority(store)

        val pending =
            runSuspend {
                authority.relay.readPending(
                    nowEpochMillis = NOW,
                    limit = 8,
                )
            }
        assertEquals(listOf(outbound), pending.map { message -> message.issuedMessage().envelope })

        runSuspend {
            authority.relay.markDelivered(
                message = pending.single(),
                deliveredAtEpochMillis = NOW,
            )
        }
        assertTrue(outbound.eventId in store.deliveredOutbound)
    }

    @Test
    fun digestAndTimelineAlwaysUseTheRuntimeLearnerScope() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)

        runSuspend {
            authority.queryDigest(SubjectKind.MATH, focusLimit = 3)
            authority.queryTimeline(SubjectKind.PHYSICS, dayLimit = 7)
            authority.localContextReader.queryContext(
                LocalMasteryContextRequest(
                    subject = SubjectKind.BIOLOGY,
                    exactStableNodeFingerprints = listOf("a".repeat(64)),
                    fallbackLimit = 1,
                ),
            )
            modelAccess(
                authority = authority,
                subject = SubjectKind.CHEMISTRY,
                modelVersion = "model-v1",
            ).digestReader.queryDigest(2)
        }

        assertEquals(
            listOf(LEARNER_ID, LEARNER_ID),
            store.digestQueries.map(SubjectMasteryDigestQuery::learnerId),
        )
        assertEquals(
            listOf(SubjectKind.MATH, SubjectKind.CHEMISTRY),
            store.digestQueries.map(SubjectMasteryDigestQuery::subject),
        )
        assertEquals(
            listOf(LEARNER_ID),
            store.timelineQueries.map(SubjectMasteryTimelineQuery::learnerId),
        )
        assertEquals(
            listOf(LEARNER_ID),
            store.localContextQueries.map(BoundLocalMasteryContextQuery::learnerId),
        )
        assertEquals(
            listOf(SubjectKind.BIOLOGY),
            store.localContextQueries.map { it.request.subject },
        )
    }

    @Test
    fun modelDigestContainsOnlyTypedKnowledgeAndQualitativeState() {
        val knowledgeNode =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "math-function-monotonicity",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )
        val store =
            FakeLearnerMasteryStore().apply {
                digestFocus +=
                    KnowledgeMasteryDigestItem(
                        knowledgeNode = knowledgeNode,
                        historicalState = KnowledgeMasteryState.STEADY,
                        currentRecallState = KnowledgeMasteryState.FAMILIARIZING,
                        trend = KnowledgeMasteryTrend.WAVERING,
                        evidenceQuality = MasteryEvidenceQuality.HIGH,
                        independentProblemFamilyCount = 17L,
                        distinctPresentationCount = 23L,
                        memoryStabilityMillis = 987_654_321L,
                        recallDueAtEpochMillis = 123_456L,
                        lastPositiveAtEpochMillis = 120_000L,
                        lastNegativeAtEpochMillis = 121_000L,
                        lastEvidenceAtEpochMillis = 122_000L,
                    )
            }

        val digest =
            runSuspend {
                modelAccess(
                    authority = authority(store),
                    subject = SubjectKind.MATH,
                    modelVersion = "model-v1",
                ).digestReader.queryDigest(64)
            }

        assertEquals(SubjectKind.MATH, digest.subject)
        val item = digest.items.single()
        assertEquals(knowledgeNode, item.knowledgeNode)
        assertEquals(KnowledgeMasteryState.FAMILIARIZING, item.state)
        assertEquals(KnowledgeMasteryTrend.WAVERING, item.trend)
    }

    @Test
    fun modelRejectionsAreIndistinguishableAndPromptInjectionRemainsOpaqueData() {
        val internalReasons =
            mapOf(
                "missing' OR 1=1 --" to LearningObservationInertReason.MISSING_SOURCE_PROOF,
                "other-learner-fact" to LearningObservationInertReason.LEARNER_MISMATCH,
                "other-subject-fact" to LearningObservationInertReason.SUBJECT_MISMATCH,
            )
        val store =
            FakeLearnerMasteryStore(
                candidateResultOverride = { command ->
                    LearningObservationIngestResult(
                        candidateId = command.candidateId,
                        disposition = LearningObservationDisposition.INERT,
                        inertReason = checkNotNull(internalReasons[command.sourceFactId]),
                    )
                },
            )
        val results =
            internalReasons.keys.map { sourceFactId ->
                runSuspend {
                    modelAccess(
                        authority = authority(store),
                        subject = SubjectKind.MATH,
                        modelVersion = "model-v1",
                        sourceFactId = sourceFactId,
                    ).candidateSink.submitCandidate(
                        SubmitLearningObservationCandidateCommand(
                            proposedAttributions = emptyList(),
                            confidence = MasteryCandidateConfidence.HIGH,
                        ),
                    )
                }
            }

        assertTrue(
            results.all {
                it.disposition == ModelLearningObservationDisposition.RECEIVED
            },
        )
        assertTrue(store.candidates.isEmpty())
    }

    @Test
    fun modelLeaseReturnsTheSameDurableReceiptForAnIdempotentRetry() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val provider = assembleLearnerMasteryRuntimeCapabilities(authority).modelHostHandoff()
        val lease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "fact-for-request-42",
                modelVersion = "model-v1",
                requestVersion = "request-42",
                modeVersion = "guided-3",
            )
        val command = modelCandidateCommand()

        val first = runSuspend { lease.access.candidateSink.submitCandidate(command) }
        val second = runSuspend { lease.access.candidateSink.submitCandidate(command) }
        runSuspend { lease.access.digestReader.queryDigest(1) }
        assertEquals(ModelLearningObservationDisposition.RECEIVED, first.disposition)
        assertEquals(first.receiptFingerprint, second.receiptFingerprint)
        assertEquals(1, store.candidates.size)
        assertEquals(1, store.digestQueries.size)

        lease.close()
        lease.close()
        val late =
            runCatching {
                runSuspend {
                    lease.access.candidateSink.submitCandidate(
                        modelCandidateCommand(),
                    )
                }
            }
        val lateRead =
            runCatching {
                runSuspend {
                    lease.access.digestReader.queryDigest(1)
                }
            }
        assertTrue(late.exceptionOrNull() is IllegalStateException)
        assertTrue(lateRead.exceptionOrNull() is IllegalStateException)
        assertEquals(1, store.candidates.size)
        assertEquals(1, store.digestQueries.size)
    }

    @Test
    fun rebuildPendingModelSubmissionReleasesTheLogicalAttemptForRetry() {
        var candidateAttempts = 0
        val store =
            FakeLearnerMasteryStore(
                candidateResultOverride = { command ->
                    candidateAttempts += 1
                    if (candidateAttempts == 1) {
                        LearningObservationIngestResult(
                            candidateId = command.candidateId,
                            disposition = LearningObservationDisposition.CONFLICT,
                            inertReason =
                                LearningObservationInertReason
                                    .DIRECTIONAL_BUDGET_REBUILD_PENDING,
                        )
                    } else {
                        LearningObservationIngestResult(
                            candidateId = command.candidateId,
                            disposition = LearningObservationDisposition.ADMITTED,
                        )
                    }
                },
            )
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val lease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "rebuild-pending-fact",
                modelVersion = "model-v1",
                requestVersion = "rebuild-pending-request",
                modeVersion = "guided-1",
            )
        val command = modelCandidateCommand()

        val pending = runSuspend { lease.access.candidateSink.submitCandidate(command) }
        val admitted = runSuspend { lease.access.candidateSink.submitCandidate(command) }
        val duplicate = runSuspend { lease.access.candidateSink.submitCandidate(command) }

        assertEquals(ModelLearningObservationDisposition.RETRYABLE, pending.disposition)
        assertNull(pending.receiptFingerprint)
        assertEquals(ModelLearningObservationDisposition.RECEIVED, admitted.disposition)
        assertTrue(admitted.receiptFingerprint != null)
        assertEquals(admitted.receiptFingerprint, duplicate.receiptFingerprint)
        assertEquals(2, candidateAttempts)
        assertEquals(1, store.modelAttemptTerminalReasons.size)
        assertEquals(
            ModelSubmissionTerminalReason.ADMITTED,
            store.modelAttemptTerminalReasons[checkNotNull(admitted.receiptFingerprint)],
        )
    }

    @Test
    fun providerInvalidationIsIdempotentAndRejectsLateModelWork() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val lease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "invalidated-generation-fact",
                modelVersion = "model-v1",
                requestVersion = "request-invalidated",
                modeVersion = "guided-1",
            )

        provider.invalidateCurrentLease()
        provider.invalidateCurrentLease()
        val late =
            runCatching {
                runSuspend {
                    lease.access.candidateSink.submitCandidate(modelCandidateCommand())
                }
            }

        assertTrue(late.exceptionOrNull() is IllegalStateException)
        assertTrue(store.candidates.isEmpty())
        assertTrue(store.digestQueries.isEmpty())
    }

    @Test
    fun reopeningTheSameRequestCannotMintASecondCandidateBudget() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val firstLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "same-request-fact",
                modelVersion = "model-v1",
                requestVersion = "same-request",
                modeVersion = "direct-1",
            )
        val first =
            runSuspend {
                firstLease.access.candidateSink.submitCandidate(modelCandidateCommand())
            }
        firstLease.close()
        val reopened =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "same-request-fact",
                modelVersion = "model-v1",
                requestVersion = "same-request",
                modeVersion = "direct-1",
            )

        val retry =
            runSuspend {
                reopened.access.candidateSink.submitCandidate(modelCandidateCommand())
            }
        val conflictingPayload =
            runSuspend {
                reopened.access.candidateSink.submitCandidate(
                    modelCandidateCommand(MasteryCandidateConfidence.MEDIUM),
                )
            }
        val repeatedConflict =
            runSuspend {
                reopened.access.candidateSink.submitCandidate(
                    modelCandidateCommand(MasteryCandidateConfidence.MEDIUM),
                )
            }

        assertEquals(first.receiptFingerprint, retry.receiptFingerprint)
        assertNotEquals(first.receiptFingerprint, conflictingPayload.receiptFingerprint)
        assertEquals(
            conflictingPayload.receiptFingerprint,
            repeatedConflict.receiptFingerprint,
        )
        assertEquals(
            ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT,
            store.modelAttemptTerminalReasons[
                checkNotNull(conflictingPayload.receiptFingerprint)
            ],
        )
        assertEquals(1, store.candidates.size)
    }

    @Test
    fun modeRotationRevokesTheOldLeaseWithoutResettingRequestBudgets() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val oldLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "mode-budget-fact",
                modelVersion = "model-v1",
                requestVersion = "mode-budget-request",
                modeVersion = "guided-1",
            )
        val first =
            runSuspend {
                oldLease.access.candidateSink.submitCandidate(modelCandidateCommand())
            }
        repeat(LearnerMasteryModelAccessLeaseGate.MAX_DIGEST_READS - 1) {
            runSuspend { oldLease.access.digestReader.queryDigest(1) }
        }
        val currentLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "mode-budget-fact",
                modelVersion = "model-v1",
                requestVersion = "mode-budget-request",
                modeVersion = "direct-2",
            )

        val oldRead =
            runCatching {
                runSuspend { oldLease.access.digestReader.queryDigest(1) }
            }
        runSuspend { currentLease.access.digestReader.queryDigest(1) }
        val overRead =
            runCatching {
                runSuspend { currentLease.access.digestReader.queryDigest(1) }
            }
        val modeChangedSubmission =
            runSuspend {
                currentLease.access.candidateSink.submitCandidate(modelCandidateCommand())
            }

        assertTrue(oldRead.exceptionOrNull() is IllegalStateException)
        assertTrue(overRead.exceptionOrNull() is IllegalStateException)
        assertNotEquals(first.receiptFingerprint, modeChangedSubmission.receiptFingerprint)
        assertEquals(
            ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT,
            store.modelAttemptTerminalReasons[
                checkNotNull(modeChangedSubmission.receiptFingerprint)
            ],
        )
        assertEquals(1, store.candidates.size)
    }

    @Test
    fun permissionRotationRevokesTheOldLeaseAndKeepsTheLogicalAttemptBudget() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val initialScope =
            modelRequestScope(
                subject = SubjectKind.MATH,
                sourceFactId = "permission-rotation-fact",
                modelVersion = "model-v1",
                requestVersion = "permission-rotation-request",
                modeVersion = "guided-1",
            )
        val initialLease = provider.openLease(initialScope)
        val first =
            runSuspend {
                initialLease.access.candidateSink.submitCandidate(modelCandidateCommand())
            }
        val rotatedScope =
            modelRequestScope(
                subject = SubjectKind.MATH,
                sourceFactId = "permission-rotation-fact",
                modelVersion = "model-v1",
                requestVersion = "permission-rotation-request",
                modeVersion = "guided-1",
                conversationStateVersion = 2L,
                learningWritePermissionVersion = "learning-write-v2",
            )
        val currentLease = provider.openLease(rotatedScope)

        val late =
            runCatching {
                runSuspend {
                    initialLease.access.candidateSink.submitCandidate(modelCandidateCommand())
                }
            }
        val current =
            runSuspend {
                currentLease.access.candidateSink.submitCandidate(modelCandidateCommand())
            }
        val initialAttemptScope =
            ModelSubmissionAttemptScope(learnerId = LEARNER_ID, permission = initialScope)
        val rotatedAttemptScope =
            ModelSubmissionAttemptScope(learnerId = LEARNER_ID, permission = rotatedScope)

        assertTrue(late.exceptionOrNull() is IllegalStateException)
        assertNotEquals(
            initialAttemptScope.requestGenerationFingerprint,
            rotatedAttemptScope.requestGenerationFingerprint,
        )
        assertEquals(
            initialAttemptScope.logicalRequestFingerprint,
            rotatedAttemptScope.logicalRequestFingerprint,
        )
        assertNotEquals(first.receiptFingerprint, current.receiptFingerprint)
        assertEquals(
            ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT,
            store.modelAttemptTerminalReasons[checkNotNull(current.receiptFingerprint)],
        )
        assertEquals(1, store.candidates.size)
    }

    @Test
    fun runtimeCloseRevokesTheHostHandoffBeforeAnInFlightResultCanEscape() {
        val enteredStore = CountDownLatch(1)
        val releaseStore = CountDownLatch(1)
        val store =
            FakeLearnerMasteryStore(
                beforeModelAttempt = {
                    enteredStore.countDown()
                    check(releaseStore.await(2, TimeUnit.SECONDS))
                },
            )
        val runtime = assembleLearnerMasteryRuntimeCapabilities(authority(store))
        val provider = runtime.modelHostHandoff()
        val lease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "close-race-fact",
                modelVersion = "model-v1",
                requestVersion = "close-race-request",
                modeVersion = "direct-1",
            )

        val result =
            runBlocking {
                val pending =
                    async(Dispatchers.Default) {
                        runCatching {
                            lease.access.candidateSink.submitCandidate(
                                modelCandidateCommand(),
                            )
                        }
                    }
                assertTrue(enteredStore.await(2, TimeUnit.SECONDS))
                var closeFailure: Throwable? = null
                val closing =
                    Thread {
                        try {
                            runtime.close()
                        } catch (error: Throwable) {
                            closeFailure = error
                        }
                    }
                closing.start()
                awaitSubmissionDrainWait(closing)
                releaseStore.countDown()
                val submission = pending.await()
                closing.join(TimeUnit.SECONDS.toMillis(2))
                check(!closing.isAlive) { "Runtime close did not finish after submission drain" }
                closeFailure?.let { throw it }
                submission
            }
        val reopen =
            runCatching {
                provider.openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = "close-race-fact",
                    modelVersion = "model-v1",
                    requestVersion = "close-race-next",
                    modeVersion = "direct-1",
                )
            }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(reopen.exceptionOrNull() is IllegalStateException)
        assertTrue(store.candidates.isEmpty())
        assertEquals(1, store.modelAttemptTerminalReasons.size)
        assertEquals(
            ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED,
            store.modelAttemptTerminalReasons.values.single(),
        )
    }

    @Test
    fun revokedInFlightFirstClaimIsReleasedForTheNextPermissionEpoch() {
        val enteredStore = CountDownLatch(1)
        val releaseStore = CountDownLatch(1)
        val store =
            FakeLearnerMasteryStore(
                beforeModelAttempt = {
                    enteredStore.countDown()
                    check(releaseStore.await(2, TimeUnit.SECONDS))
                },
            )
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val initial =
            provider.openLease(
                modelRequestScope(
                    subject = SubjectKind.MATH,
                    sourceFactId = "claim-rollback-fact",
                    modelVersion = "model-v1",
                    requestVersion = "claim-rollback-request",
                    modeVersion = "guided-1",
                ),
            )

        val stale =
            runBlocking {
                val pending =
                    async(Dispatchers.Default) {
                        runCatching {
                            initial.access.candidateSink.submitCandidate(
                                modelCandidateCommand(),
                            )
                        }
                    }
                assertTrue(enteredStore.await(2, TimeUnit.SECONDS))
                val invalidating =
                    Thread {
                        provider.invalidateCurrentLease()
                    }
                invalidating.start()
                awaitSubmissionDrainWait(invalidating)
                releaseStore.countDown()
                val submission = pending.await()
                invalidating.join(TimeUnit.SECONDS.toMillis(2))
                check(!invalidating.isAlive) {
                    "Lease invalidation did not finish after submission drain"
                }
                submission
            }
        val current =
            provider.openLease(
                modelRequestScope(
                    subject = SubjectKind.MATH,
                    sourceFactId = "claim-rollback-fact",
                    modelVersion = "model-v1",
                    requestVersion = "claim-rollback-request",
                    modeVersion = "guided-1",
                    conversationStateVersion = 2L,
                    learningWritePermissionVersion = "learning-write-v2",
                ),
            )
        val admitted =
            runSuspend {
                current.access.candidateSink.submitCandidate(modelCandidateCommand())
            }

        assertTrue(stale.exceptionOrNull() is IllegalStateException)
        assertEquals(ModelLearningObservationDisposition.RECEIVED, admitted.disposition)
        assertEquals(1, store.candidates.size)
        assertTrue(
            store.modelAttemptTerminalReasons.values.contains(
                ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED,
            ),
        )
        assertTrue(
            store.modelAttemptTerminalReasons.values.contains(
                ModelSubmissionTerminalReason.ADMITTED,
            ),
        )
    }

    @Test
    fun hostBoundSourceFactSeparatesIdentityWithoutChangingTheModelCommand() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val command = modelCandidateCommand()
        val firstLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "host-fact-a",
                modelVersion = "model-v1",
                requestVersion = "request-a",
                modeVersion = "guided-1",
            )
        val first = runSuspend { firstLease.access.candidateSink.submitCandidate(command) }
        val secondLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "host-fact-b",
                modelVersion = "model-v1",
                requestVersion = "request-b",
                modeVersion = "guided-1",
            )

        val second = runSuspend { secondLease.access.candidateSink.submitCandidate(command) }

        assertEquals(ModelLearningObservationDisposition.RECEIVED, first.disposition)
        assertEquals(ModelLearningObservationDisposition.RECEIVED, second.disposition)
        assertEquals(setOf("host-fact-a", "host-fact-b"), store.candidates.values.mapTo(mutableSetOf()) { it.sourceFactId })
        assertEquals(2, store.candidates.size)
        assertNotEquals(
            store.candidates.values.first().candidateId,
            store.candidates.values.last().candidateId,
        )
    }

    @Test
    fun sameHostSubmissionCannotMintNewEvidenceIdentityFromCallerMetadata() {
        val first =
            prepareTrustedSourceFact(
                learnerId = LEARNER_ID,
                runtimeBindingId = RUNTIME_BINDING_ID,
                command =
                    savedMistakeCommand(
                        observationId = "same-submission-a",
                        claimedPresentationFingerprint = "1".repeat(64),
                        claimedProblemFamilyFingerprint = "2".repeat(64),
                    ),
            )
        val replayWithChangedCallerMetadata =
            prepareTrustedSourceFact(
                learnerId = LEARNER_ID,
                runtimeBindingId = RUNTIME_BINDING_ID,
                command =
                    savedMistakeCommand(
                        observationId = "same-submission-b",
                        claimedPresentationFingerprint = "3".repeat(64),
                        claimedProblemFamilyFingerprint = "4".repeat(64),
                    ),
            )

        assertNotEquals(first.sourceFactId, replayWithChangedCallerMetadata.sourceFactId)
        assertNotEquals(
            first.presentationFingerprint,
            replayWithChangedCallerMetadata.presentationFingerprint,
        )
        assertNotEquals(
            first.problemFamilyFingerprint,
            replayWithChangedCallerMetadata.problemFamilyFingerprint,
        )
        assertEquals(
            first.authorityAttemptFingerprint,
            replayWithChangedCallerMetadata.authorityAttemptFingerprint,
        )
        assertEquals(
            first.authoritySubmissionFingerprint,
            replayWithChangedCallerMetadata.authoritySubmissionFingerprint,
        )
        assertEquals(
            first.authorityPresentationFingerprint,
            replayWithChangedCallerMetadata.authorityPresentationFingerprint,
        )
        assertEquals(
            first.authorityProblemFamilyFingerprint,
            replayWithChangedCallerMetadata.authorityProblemFamilyFingerprint,
        )
    }

    @Test
    fun activatingANewModeRejectsTheOldLeaseWithoutRelyingOnClose() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val oldLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "mode-switch-fact",
                modelVersion = "model-v1",
                requestVersion = "request-mode-switch",
                modeVersion = "guided-1",
            )
        val newLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "mode-switch-fact",
                modelVersion = "model-v1",
                requestVersion = "request-mode-switch",
                modeVersion = "direct-2",
            )

        val late =
            runCatching {
                runSuspend {
                    oldLease.access.candidateSink.submitCandidate(modelCandidateCommand())
                }
            }
        val current =
            runSuspend {
                newLease.access.candidateSink.submitCandidate(modelCandidateCommand())
            }

        assertTrue(late.exceptionOrNull() is IllegalStateException)
        assertEquals(ModelLearningObservationDisposition.RECEIVED, current.disposition)
        assertEquals(1, store.candidates.size)
        assertEquals("mode-switch-fact", store.candidates.values.single().sourceFactId)
    }

    @Test
    fun activatingANewRequestRejectsLateReadsFromTheOldRequest() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val oldLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "request-switch-fact",
                modelVersion = "model-v1",
                requestVersion = "request-old",
                modeVersion = "direct-1",
            )
        val newLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "request-switch-fact",
                modelVersion = "model-v1",
                requestVersion = "request-new",
                modeVersion = "direct-1",
            )

        val lateRead =
            runCatching {
                runSuspend { oldLease.access.digestReader.queryDigest(1) }
            }
        val currentRead =
            runSuspend { newLease.access.digestReader.queryDigest(1) }

        assertTrue(lateRead.exceptionOrNull() is IllegalStateException)
        assertEquals(SubjectKind.MATH, currentRead.subject)
        assertEquals(1, store.digestQueries.size)
    }

    @Test
    fun aSupersededRequestCannotReopenAndResetItsConsumedBudget() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()
        val oldLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "superseded-fact",
                modelVersion = "model-v1",
                requestVersion = "request-superseded",
                modeVersion = "direct-1",
            )
        runSuspend {
            oldLease.access.candidateSink.submitCandidate(modelCandidateCommand())
        }
        provider.openLease(
            subject = SubjectKind.MATH,
            sourceFactId = "current-fact",
            modelVersion = "model-v1",
            requestVersion = "request-current",
            modeVersion = "direct-1",
        )

        val reopened =
            runCatching {
                provider.openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = "superseded-fact",
                    modelVersion = "model-v1",
                    requestVersion = "request-superseded",
                    modeVersion = "direct-2",
                )
            }

        assertTrue(reopened.exceptionOrNull() is IllegalStateException)
        assertEquals(1, store.candidates.size)
    }

    @Test
    fun expiredLeaseRejectsReadsAndWritesBeforeStoreAccess() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        var nowNanos = 10L
        val leaseGate =
            LearnerMasteryModelAccessLeaseGate(
                requestGeneration =
                    requestGeneration(
                        requestVersion = "request-expired",
                        modeVersion = "guided-1",
                        sourceFactId = "expired-fact",
                    ),
                nowNanos = { nowNanos },
            )
        val access =
            authority.scopedModelAccess(
                subject = SubjectKind.MATH,
                modelVersion = "model-v1",
                leaseGate = leaseGate,
            )
        nowNanos += LearnerMasteryModelAccessLeaseGate.MAX_LEASE_DURATION_NANOS + 1L

        val expiredWrite =
            runCatching {
                runSuspend { access.candidateSink.submitCandidate(modelCandidateCommand()) }
            }
        val expiredRead =
            runCatching {
                runSuspend { access.digestReader.queryDigest(1) }
            }

        assertTrue(expiredWrite.exceptionOrNull() is IllegalStateException)
        assertTrue(expiredRead.exceptionOrNull() is IllegalStateException)
        assertTrue(store.candidates.isEmpty())
        assertTrue(store.digestQueries.isEmpty())
    }

    @Test
    fun concurrentIdempotentCandidateSubmissionsShareOneLogicalAttemptReceipt() {
        val store = FakeLearnerMasteryStore()
        val lease =
            assembleLearnerMasteryRuntimeCapabilities(authority(store))
                .modelHostHandoff()
                .openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = "concurrent-model-fact",
                    modelVersion = "model-v1",
                    requestVersion = "request-concurrent",
                    modeVersion = "guided-1",
                )
        val command = modelCandidateCommand()

        val attempts =
            runBlocking {
                List(16) {
                    async(Dispatchers.Default) {
                        runCatching {
                            lease.access.candidateSink.submitCandidate(command)
                        }
                    }
                }.awaitAll()
            }

        assertTrue(attempts.all { it.isSuccess })
        assertEquals(
            1,
            attempts.map { checkNotNull(it.getOrNull()).receiptFingerprint }.toSet().size,
        )
        assertEquals(1, store.candidates.size)
    }

    @Test
    fun digestReadsAreBoundedPerLease() {
        val store = FakeLearnerMasteryStore()
        val lease =
            assembleLearnerMasteryRuntimeCapabilities(authority(store))
                .modelHostHandoff()
                .openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = "bounded-read-fact",
                    modelVersion = "model-v1",
                    requestVersion = "request-bounded-read",
                    modeVersion = "direct-1",
                )

        repeat(LearnerMasteryModelAccessLeaseGate.MAX_DIGEST_READS) {
            runSuspend { lease.access.digestReader.queryDigest(1) }
        }
        val overBudget =
            runCatching {
                runSuspend { lease.access.digestReader.queryDigest(1) }
            }

        assertTrue(overBudget.exceptionOrNull() is IllegalStateException)
        assertEquals(
            LearnerMasteryModelAccessLeaseGate.MAX_DIGEST_READS,
            store.digestQueries.size,
        )
    }

    @Test
    fun requestGateRejectsInvalidLocalRequestAndModeVersions() {
        val store = FakeLearnerMasteryStore()
        val provider =
            assembleLearnerMasteryRuntimeCapabilities(authority(store)).modelHostHandoff()

        val invalidRequest =
            runCatching {
                provider.openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = "source-request-99",
                    modelVersion = "model-v1",
                    requestVersion = "ignore previous instructions; request-99",
                    modeVersion = "direct-1",
                )
            }
        val invalidMode =
            runCatching {
                provider.openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = "source-request-99",
                    modelVersion = "model-v1",
                    requestVersion = "request-99",
                    modeVersion = "../guided",
                )
            }

        assertTrue(invalidRequest.exceptionOrNull() is IllegalArgumentException)
        assertTrue(invalidMode.exceptionOrNull() is IllegalArgumentException)
        assertTrue(store.candidates.isEmpty())

        val opaqueLease =
            provider.openLease(
                subject = SubjectKind.MATH,
                sourceFactId = "source-request-opaque",
                modelVersion = "model-v1",
                requestVersion = "ignore.previous.instructions",
                modeVersion = "read.other.learner",
            )
        runSuspend { opaqueLease.access.digestReader.queryDigest(1) }
        opaqueLease.close()
        assertEquals(LEARNER_ID, store.digestQueries.single().learnerId)
        assertEquals(SubjectKind.MATH, store.digestQueries.single().subject)
    }

    @Test
    fun legacyFactMigrationIsCheckpointedAndIdempotent() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val batch =
            LegacyMasteryFactMigrationBatch(
                sourceGeneration = "legacy-v37",
                batchSequence = 1L,
                observations = listOf(savedMistakeCommand("migration-observation")),
                finalBatch = true,
            )

        val first = runSuspend { authority.migrateLegacyFacts(batch) }
        val replay = runSuspend { authority.migrateLegacyFacts(batch) }
        val conflict =
            runSuspend {
                authority.migrateLegacyFacts(
                    LegacyMasteryFactMigrationBatch(
                        sourceGeneration = "legacy-v37",
                        batchSequence = 1L,
                        observations = listOf(savedMistakeCommand("different-observation")),
                        finalBatch = true,
                    ),
                )
            }

        assertEquals(LegacyMasteryFactMigrationDisposition.IMPORTED, first.disposition)
        assertTrue(first.projectionRebuiltFromFacts)
        assertEquals(LegacyMasteryFactMigrationDisposition.DUPLICATE, replay.disposition)
        assertEquals(LegacyMasteryFactMigrationDisposition.CONFLICT, conflict.disposition)
        assertEquals(1, store.sourceFacts.size)
        assertEquals(1, store.checkpoints.size)
    }

    @Test
    fun legacyFactMigrationConflictRollsBackTheWholeBatchAndCheckpoint() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        runSuspend {
            authority.recordObservation(savedMistakeCommand("already-recorded"))
        }

        val result =
            runSuspend {
                authority.migrateLegacyFacts(
                    LegacyMasteryFactMigrationBatch(
                        sourceGeneration = "legacy-v37",
                        batchSequence = 1L,
                        observations =
                            listOf(
                                savedMistakeCommand("fresh-before-conflict"),
                                savedMistakeCommand(
                                    observationId = "already-recorded",
                                    answerWasCorrect = true,
                                ),
                            ),
                        finalBatch = true,
                    ),
                )
            }

        assertEquals(LegacyMasteryFactMigrationDisposition.CONFLICT, result.disposition)
        assertEquals(setOf("already-recorded"), store.sourceFacts.keys)
        assertTrue(store.candidates.isEmpty())
        assertTrue(store.checkpoints.isEmpty())
    }

    @Test
    fun correctionIsLearnerBoundAndDerivesReplacementDirectionLocally() {
        val store = FakeLearnerMasteryStore()
        val authority = authority(store)
        val replacement =
            savedMistakeCommand(
                observationId = "corrected-observation",
                answerWasCorrect = true,
            )
        val command =
            CorrectLearningEvidenceCommand(
                subject = SubjectKind.MATH,
                originalSourceFactCanonicalFingerprint = "1".repeat(64),
                replacementObservation = replacement,
                authority = LearningEvidenceCorrectionAuthority.INDEPENDENT_MODEL_REVIEW,
                authorityVersion = "correction-review-v1",
                correctionEvidenceFingerprint = "2".repeat(64),
                idempotencyKey = "correction:authority-test",
                correctedAtEpochMillis = NOW,
            )

        val result =
            runSuspend {
                authority.evidenceCorrectionCapability.correct(command)
            }

        assertEquals(LearningEvidenceCorrectionDisposition.APPLIED, result.disposition)
        assertEquals(LEARNER_ID, store.lastCorrectionLearnerId)
        assertEquals(SubjectKind.MATH, store.lastSourceFact?.subject)
        assertEquals(ObservedLearningOutcome.CORRECT, store.lastSourceFact?.outcome)
        assertEquals(MasteryCandidateOrigin.TRUSTED_LOCAL, store.lastCandidate?.candidateOrigin)
        assertTrue(
            CorrectLearningEvidenceCommand::class.java.declaredFields.none {
                it.name.contains("direction", ignoreCase = true) ||
                    it.name.contains("mass", ignoreCase = true) ||
                    it.name.contains("mastery", ignoreCase = true)
            },
        )
    }

    @Test
    fun correctionRejectsAReplacementFromAnotherSubjectBeforeAuthorityUse() {
        val replacement =
            savedMistakeCommand(
                observationId = "wrong-subject-correction",
                answerWasCorrect = true,
            )

        assertTrue(
            runCatching {
                CorrectLearningEvidenceCommand(
                    subject = SubjectKind.PHYSICS,
                    originalSourceFactCanonicalFingerprint = "1".repeat(64),
                    replacementObservation = replacement,
                    authority = LearningEvidenceCorrectionAuthority.INDEPENDENT_MODEL_REVIEW,
                    authorityVersion = "correction-review-v1",
                    correctionEvidenceFingerprint = "2".repeat(64),
                    idempotencyKey = "correction:wrong-subject",
                    correctedAtEpochMillis = NOW,
                )
            }.isFailure,
        )
    }

    private fun authority(
        store: FakeLearnerMasteryStore,
    ): RoomLearnerMasteryAuthority =
        RoomLearnerMasteryAuthority(
            store = store,
            learnerId = LEARNER_ID,
            nowEpochMillis = { NOW },
            runtimeBindingId = RUNTIME_BINDING_ID,
            knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
        )

    private fun modelAccess(
        authority: RoomLearnerMasteryAuthority,
        subject: SubjectKind,
        modelVersion: String,
        sourceFactId: String = "model-access-source",
    ): LearnerMasteryModelAccess =
        authority.scopedModelAccess(
            subject = subject,
            modelVersion = modelVersion,
            leaseGate =
                LearnerMasteryModelAccessLeaseGate(
                    requestGeneration =
                        requestGeneration(
                            requestVersion = "request-v1",
                            modeVersion = "direct-v1",
                            sourceFactId = sourceFactId,
                            subject = subject,
                            modelVersion = modelVersion,
                        ),
                ),
        )

    private fun LearnerMasteryModelAccessProvider.openLease(
        subject: SubjectKind,
        sourceFactId: String,
        modelVersion: String,
        requestVersion: String,
        modeVersion: String,
    ): LearnerMasteryModelAccessLease =
        openLease(
            modelRequestScope(
                subject = subject,
                sourceFactId = sourceFactId,
                modelVersion = modelVersion,
                requestVersion = requestVersion,
                modeVersion = modeVersion,
            ),
        )

    private fun modelRequestScope(
        subject: SubjectKind,
        sourceFactId: String,
        modelVersion: String,
        requestVersion: String,
        modeVersion: String,
        conversationStateVersion: Long = 1L,
        learningWritePermissionVersion: String = "learning-write-v1",
    ): LearnerMasteryModelRequestScope =
        LearnerMasteryModelRequestScope(
            subject = subject,
            sourceFactId = sourceFactId,
            conversationId = "conversation:$requestVersion",
            conversationGeneration = 1L,
            conversationState = "ACTIVE",
            conversationStateVersion = conversationStateVersion,
            turnReferenceId = "turn:$requestVersion",
            turnOrdinal = 1,
            problemRevisionFingerprint =
                CanonicalSha256("authority-test-model-problem-revision-v1")
                    .field("sourceFactId", sourceFactId)
                    .finish(),
            problemDocumentFingerprint = "a".repeat(64),
            problemFingerprint = null,
            problemFingerprintVersion = null,
            bindingSetVersion = 1L,
            knowledgeManifestFingerprint = null,
            knowledgeActivationGeneration = null,
            sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
            projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            modelVersion = modelVersion,
            requestVersion = requestVersion,
            modeVersion = modeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion,
        )

    private fun requestGeneration(
        requestVersion: String,
        modeVersion: String,
        sourceFactId: String = "model-access-source",
        subject: SubjectKind = SubjectKind.MATH,
        modelVersion: String = "model-v1",
    ): LearnerMasteryModelRequestGate.Generation =
        LearnerMasteryModelRequestGate.create().activate(
            scope =
                ModelSubmissionAttemptScope(
                    learnerId = LEARNER_ID,
                    permission =
                        modelRequestScope(
                            subject = subject,
                            sourceFactId = sourceFactId,
                            modelVersion = modelVersion,
                            requestVersion = requestVersion,
                            modeVersion = modeVersion,
                        ),
                ),
            modeVersion = modeVersion,
        )

    private fun modelCandidateCommand(
        confidence: MasteryCandidateConfidence = MasteryCandidateConfidence.HIGH,
    ): SubmitLearningObservationCandidateCommand {
        val revision = revision()
        val binding = binding(revision)
        return SubmitLearningObservationCandidateCommand(
            proposedAttributions =
                listOf(
                    ProposedKnowledgeAttribution(
                        knowledgeNode = binding.knowledgeNode,
                        problemBinding = binding,
                        role = MasteryAttributionRole.PRIMARY,
                        certainty = MasteryAttributionCertainty.DIRECT,
                    ),
                ),
            confidence = confidence,
        )
    }

    private fun tutorCommand(
        context: EphemeralTutorProblemLearningContext,
        occurredAtEpochMillis: Long = 100L,
        attestedAtEpochMillis: Long = 110L,
    ): RecordTrustedLearningObservationCommand =
        RecordTrustedLearningObservationCommand(
            observationId = "tutor-observation:${context.problemFingerprint.take(16)}",
            subject = SubjectKind.MATH,
            source = TrustedLearningObservationSource.TUTOR_FREE_RESPONSE,
            sourceReferenceId = "tutor-submission-1",
            presentationFingerprint = "7".repeat(64),
            context = context,
            responseForm = TrustedLearningResponseForm.FREE_RESPONSE,
            answerWasCorrect = true,
            learnerReportedStuck = false,
            answerWasViewed = false,
            independentlyAnswered = true,
            hintCount = 0,
            answerRevealed = false,
            retryCount = 0,
            elapsedDurationMillis = 42_000L,
            verification = TrustedLearningVerification.DETERMINISTIC_RUBRIC,
            evidenceCanonicalFingerprint = "8".repeat(64),
            occurredAtEpochMillis = occurredAtEpochMillis,
            attestedAtEpochMillis = attestedAtEpochMillis,
        )

    private fun savedMistakeCommand(
        observationId: String,
        includeKnowledgeBinding: Boolean = true,
        answerWasCorrect: Boolean = false,
        claimedPresentationFingerprint: String = "9".repeat(64),
        claimedProblemFamilyFingerprint: String = "a".repeat(64),
    ): RecordTrustedLearningObservationCommand {
        val revision = revision()
        val binding = binding(revision)
        return RecordTrustedLearningObservationCommand(
            observationId = observationId,
            subject = SubjectKind.MATH,
            source = TrustedLearningObservationSource.SAVED_PROBLEM_REVIEW,
            sourceReferenceId = "review-submission:$observationId",
            presentationFingerprint = claimedPresentationFingerprint,
            context =
                SavedMistakeLearningContext(
                    problemRevision = revision,
                    problemFamilyFingerprint = claimedProblemFamilyFingerprint,
                    reviewAttempt =
                        TrustedReviewAttemptEvidence(
                            reviewSessionId = "review-session-1",
                            reviewQueueItemId = "review-item-1",
                            submissionId = "review-submission-1",
                        ),
                    knowledgeEvidenceBindings =
                        if (includeKnowledgeBinding) listOf(binding) else emptyList(),
                ),
            responseForm = TrustedLearningResponseForm.FREE_RESPONSE,
            answerWasCorrect = answerWasCorrect,
            learnerReportedStuck = false,
            answerWasViewed = false,
            independentlyAnswered = true,
            hintCount = 0,
            answerRevealed = false,
            retryCount = 0,
            elapsedDurationMillis = 30_000L,
            verification = TrustedLearningVerification.DEVICE_OBSERVED,
            evidenceCanonicalFingerprint =
                CanonicalSha256("migration-evidence-v1")
                    .field("observationId", observationId)
                    .finish(),
            occurredAtEpochMillis = 200L,
            attestedAtEpochMillis = 210L,
        )
    }

    private fun bindingSnapshotEnvelope(): CrossStoreEventEnvelope {
        val revision = revision()
        val payload =
            ProblemKnowledgeBindingsSnapshotV2(
                problemRevision = revision,
                bindings = listOf(binding(revision)),
                bindingSetVersion = 1L,
                changedAtEpochMillis = 300L,
            )
        return CrossStoreEventEnvelope(
            eventId = "student-binding-snapshot-1",
            sourceStore = StudyStoreKind.STUDENT_MISTAKES,
            destinationStore = StudyStoreKind.LEARNER_MASTERY,
            aggregateId = payload.aggregateId,
            aggregateVersion = payload.bindingSetVersion,
            occurredAtEpochMillis = payload.changedAtEpochMillis,
            idempotencyKey = "student-binding-snapshot-idempotency-1",
            sourceStoreGeneration = "student-v1",
            payload = payload,
        )
    }

    private fun reviewObservationEnvelope(
        hintCount: Int,
        answerWasRevealed: Boolean,
        attemptOrdinal: Int,
        verificationOutcome: ReviewVerificationOutcome,
    ): CrossStoreEventEnvelope {
        val payload =
            ReviewObservationCapturedV2(
                problemRevision = revision(),
                reviewSessionId = "review-session-observation",
                reviewQueueItemId = "review-item-observation",
                observationId = "review-observation-1",
                submissionId = "review-submission-observation",
                presentationId = "review-presentation-observation",
                responseForm = ReviewResponseForm.NUMERIC,
                responseOpaqueBinding = "5".repeat(64),
                responseBindingAlgorithmVersion = "test-hmac-sha256-v1",
                verificationOutcome = verificationOutcome,
                attemptOrdinal = attemptOrdinal,
                hintCount = hintCount,
                answerWasRevealed = answerWasRevealed,
                verificationPolicyVersion = "review-verification-v1",
                elapsedDurationMillis = 20_000L,
                capturedAtEpochMillis = 500L,
            )
        return CrossStoreEventEnvelope(
            eventId = "review-observation-event-1",
            sourceStore = StudyStoreKind.STUDENT_MISTAKES,
            destinationStore = StudyStoreKind.LEARNER_MASTERY,
            aggregateId = payload.aggregateId,
            aggregateVersion = ReviewObservationCapturedV2.PAYLOAD_VERSION.toLong(),
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
            idempotencyKey = "review-observation-idempotency-1",
            sourceStoreGeneration = "student-v1",
            payload = payload,
        )
    }

    private fun learningAttemptEnvelope(): CrossStoreEventEnvelope {
        val payload =
            LearningAttemptRecordedV1(
                evidence =
                    LearningEvidenceRef(
                        learnerId = LEARNER_ID,
                        eventKind = "MASTERY_LEARNING_EVENT",
                        eventId = "mastery-event-1",
                        eventSequence = 1L,
                        eventCanonicalFingerprint = "e".repeat(64),
                    ),
                problemRevision = revision(),
                reviewSessionId = "review-session-1",
                reviewQueueItemId = "review-item-1",
                submissionId = "review-submission-1",
                presentationId = "presentation-1",
                recordedAtEpochMillis = 500L,
            )
        return CrossStoreEventEnvelope(
            eventId = "mastery-learning-attempt-1",
            sourceStore = StudyStoreKind.LEARNER_MASTERY,
            destinationStore = StudyStoreKind.STUDENT_MISTAKES,
            aggregateId = payload.aggregateId,
            aggregateVersion = 1L,
            occurredAtEpochMillis = payload.recordedAtEpochMillis,
            idempotencyKey = "mastery-learning-attempt-idempotency-1",
            sourceStoreGeneration = "mastery-v1",
            payload = payload,
        )
    }

    private fun studentRelayMessage(
        envelope: CrossStoreEventEnvelope,
    ): VerifiedStudentMistakeDelivery {
        val proof =
            StudentOutboxAuthenticityProof(
                protocolVersion = StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
                algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                issuerKeyId = "student-test-key",
                learnerId = LEARNER_ID,
                envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                tagHex = "0".repeat(64),
            )
        return VerifiedStudentMistakeDelivery(
            envelope,
            LEARNER_ID,
            envelope.sourceStoreGeneration,
            "student-test-relay-epoch",
            "student-test-key",
            StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
            proof.canonicalFingerprint,
            envelope.canonicalFingerprint,
            CanonicalSha256("student-outbox-verification-receipt-v1")
                .field("learnerId", LEARNER_ID)
                .field("sourceStoreGeneration", envelope.sourceStoreGeneration)
                .field("relayEpoch", "student-test-relay-epoch")
                .field("issuerKeyId", "student-test-key")
                .field("algorithmVersion", StudentOutboxAuthenticityProof.ALGORITHM_VERSION)
                .field("envelopeCanonicalFingerprint", envelope.canonicalFingerprint)
                .field("proofCanonicalFingerprint", proof.canonicalFingerprint)
                .finish(),
        )
    }

    private fun revision(): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.MATH,
                    problemId = "problem-1",
                    practiceUnitId = "practice-unit-1",
                ),
            revisionId = "revision-1",
            revisionNumber = 1,
            documentCanonicalFingerprint = "b".repeat(64),
        )

    private fun node(): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "math.function.quadratic",
            taxonomyVersion = "taxonomy-v1",
            knowledgePackVersion = "pack-v1",
        )

    private fun pendingOpenResponseFacts(
        evidence: VerifiedEphemeralKnowledgeEvidence,
        responseFingerprint: String = "4".repeat(64),
    ): PendingOpenResponseFacts =
        PendingOpenResponseFacts(
            sourceFactId = "pending-open-response-source",
            submissionId = "pending-open-response-submission",
            subject = SubjectKind.MATH,
            presentationFingerprint = "5".repeat(64),
            context =
                EphemeralTutorProblemLearningContext(
                    problemFingerprint = "6".repeat(64),
                    problemFamilyFingerprint = "7".repeat(64),
                    tutorTurnReferenceId = "pending-open-response-turn",
                    submissionEvidenceFingerprint = "8".repeat(64),
                    attributionModelVersion = "open-response-review-v1",
                    verifiedKnowledgeEvidence = listOf(evidence),
                ),
            responseCanonicalFingerprint = responseFingerprint,
            responsePolicyVersion = "open-response-v1",
            attemptOrdinal = 1,
            hintCount = 0,
            answerWasRevealed = false,
            elapsedDurationMillis = 1_000L,
            occurredAtEpochMillis = 500L,
            attestedAtEpochMillis = 500L,
        )

    private fun binding(
        revision: StudentProblemRevisionRef,
    ): ProblemKnowledgeBindingRef =
        ProblemKnowledgeBindingRef(
            bindingId = "binding-1",
            problemRevision = revision,
            knowledgeNode = node(),
            bindingCanonicalFingerprint = "c".repeat(64),
        )

    private companion object {
        const val LEARNER_ID = "learner-authority-test"
        const val RUNTIME_BINDING_ID = "runtime-binding-test"
        const val NOW = 1_000L
        val MANIFEST_FINGERPRINT = "d".repeat(64)
    }
}

private class FakeLearnerMasteryStore(
    private val problemBindingsAuthorized: Boolean = true,
    private val problemRevisionAuthorized: Boolean = true,
    private val candidateResultOverride:
        ((IngestLearningObservationCandidateCommand) -> LearningObservationIngestResult)? = null,
    private val beforeModelAttempt: (() -> Unit)? = null,
) : LearnerMasteryStore {
    val sourceFacts = linkedMapOf<String, IngestLearningSourceFactCommand>()
    val candidates = linkedMapOf<String, IngestLearningObservationCandidateCommand>()
    private val candidateTerminalReceipts =
        linkedMapOf<String, LearningObservationTerminalReceipt>()
    val checkpoints =
        linkedMapOf<Triple<String, String, Long>, MasteryLegacyFactMigrationCheckpointEntity>()
    val digestQueries = mutableListOf<SubjectMasteryDigestQuery>()
    val digestFocus = mutableListOf<KnowledgeMasteryDigestItem>()
    val localContextQueries = mutableListOf<BoundLocalMasteryContextQuery>()
    val timelineQueries = mutableListOf<SubjectMasteryTimelineQuery>()
    val pendingOutbound = mutableListOf<CrossStoreEventEnvelope>()
    val deliveredOutbound = mutableSetOf<String>()
    val pendingOpenResponses =
        linkedMapOf<String, Pair<IngestLearningSourceFactCommand, IngestLearningObservationCandidateCommand>>()
    val modelAttemptTerminalReasons =
        linkedMapOf<String, ModelSubmissionTerminalReason>()
    private val modelAttemptReceipts =
        linkedMapOf<Pair<String, String>, ModelSubmissionAttemptReceipt>()
    private val primaryModelAttemptProposal = linkedMapOf<String, String>()
    private val inbound = linkedMapOf<String, String>()

    var lastSourceFact: IngestLearningSourceFactCommand? = null
    var lastCandidate: IngestLearningObservationCandidateCommand? = null
    var lastPendingSourceFact: IngestLearningSourceFactCommand? = null
    var lastCorrection: CorrectLearningEvidenceCommand? = null
    var lastCorrectionLearnerId: String? = null
    var appliedInboundCount: Int = 0

    override suspend fun ingestSourceFact(
        command: IngestLearningSourceFactCommand,
    ): LearningSourceFactIngestResult {
        lastSourceFact = command
        val existing = sourceFacts[command.sourceFactId]
        val status =
            when {
                existing == null -> {
                    sourceFacts[command.sourceFactId] = command
                    LearningSourceFactIngestStatus.STORED
                }
                existing.canonicalFingerprint == command.canonicalFingerprint ->
                    LearningSourceFactIngestStatus.DUPLICATE
                else -> LearningSourceFactIngestStatus.CONFLICT
            }
        return LearningSourceFactIngestResult(command.sourceFactId, status)
    }

    override suspend fun ingestObservationCandidate(
        command: IngestLearningObservationCandidateCommand,
    ): LearningObservationIngestResult {
        lastCandidate = command
        candidateResultOverride?.let { override ->
            val result = override(command)
            if (result.terminalReceipt != null || result.disposition == LearningObservationDisposition.CONFLICT) {
                return result
            }
            val terminalDisposition =
                when (result.disposition) {
                    LearningObservationDisposition.ADMITTED -> LearningObservationDisposition.ADMITTED
                    LearningObservationDisposition.INERT -> LearningObservationDisposition.INERT
                    LearningObservationDisposition.DUPLICATE ->
                        error("A fake duplicate requires an existing terminal receipt")
                    LearningObservationDisposition.CONFLICT -> error("Handled above")
                }
            val receipt = fakeTerminalReceipt(command, terminalDisposition, result.inertReason)
            candidateTerminalReceipts[command.candidateId] = receipt
            return result.copy(terminalReceipt = receipt)
        }
        val existing = candidates[command.candidateId]
        if (existing != null) {
            return if (existing.canonicalFingerprint == command.canonicalFingerprint) {
                LearningObservationIngestResult(
                    command.candidateId,
                    LearningObservationDisposition.DUPLICATE,
                    terminalReceipt = checkNotNull(candidateTerminalReceipts[command.candidateId]),
                )
            } else {
                LearningObservationIngestResult(
                    command.candidateId,
                    LearningObservationDisposition.CONFLICT,
                    LearningObservationInertReason.IDEMPOTENCY_CONFLICT,
                )
            }
        }
        candidates[command.candidateId] = command
        return if (command.proposedAttributions.isEmpty()) {
            val reason = LearningObservationInertReason.NO_ATTRIBUTION
            val receipt =
                fakeTerminalReceipt(
                    command,
                    LearningObservationDisposition.INERT,
                    reason,
                )
            candidateTerminalReceipts[command.candidateId] = receipt
            LearningObservationIngestResult(
                command.candidateId,
                LearningObservationDisposition.INERT,
                reason,
                terminalReceipt = receipt,
            )
        } else {
            val receipt =
                fakeTerminalReceipt(
                    command,
                    LearningObservationDisposition.ADMITTED,
                    null,
                )
            candidateTerminalReceipts[command.candidateId] = receipt
            LearningObservationIngestResult(
                command.candidateId,
                LearningObservationDisposition.ADMITTED,
                terminalReceipt = receipt,
            )
        }
    }

    private fun fakeTerminalReceipt(
        command: IngestLearningObservationCandidateCommand,
        disposition: LearningObservationDisposition,
        inertReason: LearningObservationInertReason?,
    ): LearningObservationTerminalReceipt =
        LearningObservationTerminalReceipt.create(
            candidateId = command.candidateId,
            candidateCanonicalFingerprint = command.canonicalFingerprint,
            disposition = disposition,
            inertReason = inertReason,
            receiptFingerprint =
                CanonicalSha256("fake-mastery-terminal-receipt-v1")
                    .field("candidate", command.canonicalFingerprint)
                    .field("disposition", disposition.name)
                    .nullableField("inertReason", inertReason?.name)
                    .finish(),
        )

    override suspend fun ingestModelObservationCandidate(
        command: IngestLearningObservationCandidateCommand,
        attempt: ModelSubmissionAttempt,
    ): ModelSubmissionAttemptOutcome {
        beforeModelAttempt?.invoke()
        attempt.authorizeCommit()
        existingModelAttempt(attempt)?.let { receipt ->
            return ModelSubmissionAttemptOutcome.Terminal(receipt)
        }
        if (
            attempt.claim ==
            ModelSubmissionCandidateClaim.LOGICAL_ATTEMPT_CONFLICT ||
            primaryModelAttemptProposal.containsKey(
                attempt.scope.logicalRequestFingerprint,
            )
        ) {
            return ModelSubmissionAttemptOutcome.Terminal(
                recordModelAttempt(
                    attempt = attempt,
                    terminalReason =
                        ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT,
                    primary = false,
                ),
            )
        }
        val result = ingestObservationCandidate(command)
        if (result.retryable) {
            attempt.authorizeCommit()
            return ModelSubmissionAttemptOutcome.RebuildPending
        }
        val terminalReason =
            when (result.disposition) {
                LearningObservationDisposition.ADMITTED ->
                    ModelSubmissionTerminalReason.ADMITTED
                LearningObservationDisposition.DUPLICATE ->
                    ModelSubmissionTerminalReason.DUPLICATE
                LearningObservationDisposition.INERT ->
                    enumValueOf<ModelSubmissionTerminalReason>(
                        checkNotNull(result.inertReason).name,
                    )
                LearningObservationDisposition.CONFLICT ->
                    ModelSubmissionTerminalReason.CANDIDATE_IDEMPOTENCY_CONFLICT
            }
        return ModelSubmissionAttemptOutcome.Terminal(
            recordModelAttempt(
                attempt = attempt,
                terminalReason = terminalReason,
                primary = true,
            ),
        )
    }

    override suspend fun recordRejectedModelSubmissionAttempt(
        attempt: ModelSubmissionAttempt,
        terminalReason: ModelSubmissionTerminalReason,
    ): ModelSubmissionAttemptReceipt {
        existingModelAttempt(attempt)?.let { return it }
        val logicalConflict =
            attempt.claim ==
                ModelSubmissionCandidateClaim.LOGICAL_ATTEMPT_CONFLICT ||
                primaryModelAttemptProposal.containsKey(
                    attempt.scope.logicalRequestFingerprint,
                )
        return recordModelAttempt(
            attempt = attempt,
            terminalReason =
                if (logicalConflict) {
                    ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT
                } else {
                    terminalReason
                },
            primary =
                !logicalConflict &&
                    terminalReason !=
                    ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED &&
                    terminalReason !=
                    ModelSubmissionTerminalReason.PERMISSION_SCOPE_MISMATCH,
        )
    }

    private fun existingModelAttempt(
        attempt: ModelSubmissionAttempt,
    ): ModelSubmissionAttemptReceipt? =
        modelAttemptReceipts[
            attempt.scope.requestGenerationFingerprint to attempt.proposalFingerprint
        ]

    private fun recordModelAttempt(
        attempt: ModelSubmissionAttempt,
        terminalReason: ModelSubmissionTerminalReason,
        primary: Boolean,
    ): ModelSubmissionAttemptReceipt {
        val fingerprint =
            CanonicalSha256("fake-model-submission-attempt-receipt-v1")
                .field(
                    "requestGeneration",
                    attempt.scope.requestGenerationFingerprint,
                )
                .field("proposalFingerprint", attempt.proposalFingerprint)
                .field("terminalReason", terminalReason.name)
                .field("primary", primary)
                .finish()
        val receipt = ModelSubmissionAttemptReceipt(fingerprint)
        modelAttemptReceipts[
            attempt.scope.requestGenerationFingerprint to attempt.proposalFingerprint
        ] = receipt
        if (primary) {
            primaryModelAttemptProposal[
                attempt.scope.logicalRequestFingerprint
            ] = attempt.proposalFingerprint
        }
        modelAttemptTerminalReasons[fingerprint] = terminalReason
        return receipt
    }

    override suspend fun enqueuePendingOpenResponse(
        sourceFact: IngestLearningSourceFactCommand,
        candidate: IngestLearningObservationCandidateCommand,
    ): PendingOpenResponsePersistenceResult {
        lastPendingSourceFact = sourceFact
        lastCandidate = candidate
        val existing = pendingOpenResponses[sourceFact.sourceFactId]
        val status =
            when {
                existing == null -> {
                    pendingOpenResponses[sourceFact.sourceFactId] = sourceFact to candidate
                    PendingOpenResponsePersistenceStatus.QUEUED
                }
                existing.first.canonicalFingerprint == sourceFact.canonicalFingerprint &&
                    existing.second.canonicalFingerprint == candidate.canonicalFingerprint ->
                    PendingOpenResponsePersistenceStatus.DUPLICATE
                else -> PendingOpenResponsePersistenceStatus.CONFLICT
            }
        return PendingOpenResponsePersistenceResult(
            sourceFactId = sourceFact.sourceFactId,
            reviewCaseId =
                "review:${sourceFact.sourceFactId}".takeIf {
                    status != PendingOpenResponsePersistenceStatus.CONFLICT
                },
            status = status,
        )
    }

    override suspend fun readPendingEvidenceReviews(
        learnerId: String,
        subject: SubjectKind,
        limit: Int,
    ): List<PendingLearningEvidenceReview> = emptyList()

    override suspend fun resolveEvidenceReview(
        learnerId: String,
        command: ResolveLearningEvidenceReviewCommand,
    ): LearningEvidenceReviewWriteResult =
        LearningEvidenceReviewWriteResult(
            reviewCaseId = command.reviewCaseId,
            disposition = LearningEvidenceReviewWriteDisposition.NOT_FOUND,
        )

    override suspend fun correctLearningEvidence(
        learnerId: String,
        command: CorrectLearningEvidenceCommand,
        replacementSourceFact: IngestLearningSourceFactCommand,
        replacementCandidate: IngestLearningObservationCandidateCommand,
    ): LearningEvidenceCorrectionResult {
        lastCorrectionLearnerId = learnerId
        lastCorrection = command
        lastSourceFact = replacementSourceFact
        lastCandidate = replacementCandidate
        return LearningEvidenceCorrectionResult(
            replacementObservationId = command.replacementObservation.observationId,
            replacementSourceFactCanonicalFingerprint =
                replacementSourceFact.canonicalFingerprint,
            disposition = LearningEvidenceCorrectionDisposition.APPLIED,
        )
    }

    override suspend fun acceptProblemKnowledgeBindings(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition = acceptInbound(message.envelope())

    override suspend fun acceptStudentProblemReference(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition = acceptInbound(message.envelope())

    override suspend fun acceptProblemLifecycleChange(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition = acceptInbound(message.envelope())

    override suspend fun acceptProblemRevisionSupersession(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition = acceptInbound(message.envelope())

    override suspend fun acceptStudentReviewObservation(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition = acceptInbound(message.envelope())

    override suspend fun isProblemRevisionAuthorized(
        problemRevision: StudentProblemRevisionRef,
    ): Boolean = problemRevisionAuthorized

    override suspend fun areProblemKnowledgeBindingsAuthorized(
        problemRevisionCanonicalFingerprint: String,
        bindingCanonicalFingerprints: Set<String>,
    ): Boolean = problemBindingsAuthorized

    override suspend fun readAuthorizedProblemKnowledgeBindings(
        problemRevision: StudentProblemRevisionRef,
    ): List<ProblemKnowledgeBindingRef> = emptyList()

    private fun acceptInbound(envelope: CrossStoreEventEnvelope): MasteryInboundDisposition {
        val existing = inbound[envelope.eventId]
        return when {
            existing == null -> {
                inbound[envelope.eventId] = envelope.canonicalFingerprint
                appliedInboundCount += 1
                MasteryInboundDisposition.APPLIED
            }
            existing == envelope.canonicalFingerprint -> MasteryInboundDisposition.DUPLICATE
            else -> MasteryInboundDisposition.CONFLICT
        }
    }

    override suspend fun readPendingMessages(
        learnerId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage> =
        pendingOutbound
            .filterNot { it.eventId in deliveredOutbound }
            .filter { envelope ->
                val learner =
                    (envelope.payload as? LearningAttemptRecordedV1)
                        ?.evidence
                        ?.learnerId
                learner == learnerId
            }.take(limit)
            .map { envelope ->
                com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage
                    .fromUnverifiedEnvelopeAndProof(
                        envelope,
                        com.tingyun.smartmistakebook.core.model.storage
                            .MasteryOutboxAuthenticityProof(
                                protocolVersion = 1,
                                algorithmVersion = "android-keystore-hmac-sha256-v1",
                                issuerKeyId = "test-mastery-outbox-key",
                                learnerId = learnerId,
                                envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                                tagHex = "a".repeat(64),
                            ),
                    )
            }

    override suspend fun markMessageDelivered(
        message: LearnerMasteryOutboxDelivery,
        deliveredAtEpochMillis: Long,
    ) {
        deliveredOutbound += message.eventId
    }

    override suspend fun querySubjectDigest(
        query: SubjectMasteryDigestQuery,
    ): SubjectMasteryDigest {
        digestQueries += query
        return SubjectMasteryDigest(
            learnerId = query.learnerId,
            subject = query.subject,
            stateCounts = SubjectMasteryStateCounts(0, 0, 0),
            focus = digestFocus.toList(),
            lastUpdatedAtEpochMillis = null,
        )
    }

    override suspend fun queryLocalMasteryContext(
        query: BoundLocalMasteryContextQuery,
    ): LocalMasteryContext {
        localContextQueries += query
        return LocalMasteryContext(
            subject = query.request.subject,
            items = emptyList(),
        )
    }

    override suspend fun querySubjectTimeline(
        query: SubjectMasteryTimelineQuery,
    ): List<SubjectMasteryTimelineEntry> {
        timelineQueries += query
        return emptyList()
    }

    override fun observeDisplayRevision(
        query: BoundLearnerMasteryDisplayQuery,
    ): Flow<LearnerMasteryDisplayRevision> =
        flowOf(LearnerMasteryDisplayRevision(ledgerSequence = 0L, asOfEpochMillis = 0L))

    override suspend fun readDisplayOverview(
        query: BoundLearnerMasteryDisplayQuery,
        expectedRevision: LearnerMasteryDisplayRevision,
    ): LearnerMasteryDisplayOverviewResult =
        LearnerMasteryDisplayOverviewResult.Current(
            LearnerMasteryDisplayOverviewSnapshot(
                revision = expectedRevision,
                subjects =
                    LEARNER_MASTERY_DISPLAY_SUBJECTS.map { subject ->
                        LearnerMasteryDisplaySubjectOverview(
                            subject = subject,
                            currentState = null,
                            trend = null,
                        )
                    },
                taxonomyVersions = emptySet(),
            ),
        )

    override suspend fun readDisplayKnowledgePage(
        query: BoundLearnerMasteryDisplayPageQuery,
    ): LearnerMasteryDisplayPageResult =
        LearnerMasteryDisplayPageResult.Current(
            revision = query.request.expectedRevision,
            items = emptyList(),
            taxonomyVersions = emptySet(),
        )

    override suspend fun readDisplaySubjectTimeline(
        query: BoundLearnerMasteryDisplayTimelineQuery,
    ): LearnerMasteryDisplayTimelineResult =
        LearnerMasteryDisplayTimelineResult.Current(
            revision = query.request.expectedRevision,
            entries = emptyList(),
        )

    override suspend fun migrateLegacyFactBatch(
        observations: List<LegacyMasteryObservationWrite>,
        checkpoint: MasteryLegacyFactMigrationCheckpointEntity,
    ): LegacyMasteryFactBatchWriteDisposition {
        val key =
            Triple(
                checkpoint.learnerId,
                checkpoint.sourceGeneration,
                checkpoint.batchSequence,
            )
        val existing = checkpoints[key]
        if (existing != null) {
            return if (existing.batchFingerprint == checkpoint.batchFingerprint) {
                LegacyMasteryFactBatchWriteDisposition.DUPLICATE
            } else {
                LegacyMasteryFactBatchWriteDisposition.CONFLICT
            }
        }
        val latest =
            checkpoints.values
                .filter {
                    it.learnerId == checkpoint.learnerId &&
                        it.sourceGeneration == checkpoint.sourceGeneration
                }.maxByOrNull(MasteryLegacyFactMigrationCheckpointEntity::batchSequence)
        if (
            latest?.finalBatch == true ||
            checkpoint.batchSequence != (latest?.batchSequence ?: 0L) + 1L
        ) {
            return LegacyMasteryFactBatchWriteDisposition.OUT_OF_ORDER
        }

        val sourceSnapshot = LinkedHashMap(sourceFacts)
        val candidateSnapshot = LinkedHashMap(candidates)
        observations.forEach { observation ->
            val fact = ingestSourceFact(observation.sourceFact)
            val candidate =
                if (fact.status == LearningSourceFactIngestStatus.CONFLICT) {
                    null
                } else {
                    ingestObservationCandidate(observation.candidate)
                }
            if (
                fact.status == LearningSourceFactIngestStatus.CONFLICT ||
                candidate?.disposition == LearningObservationDisposition.CONFLICT
            ) {
                sourceFacts.clear()
                sourceFacts.putAll(sourceSnapshot)
                candidates.clear()
                candidates.putAll(candidateSnapshot)
                return LegacyMasteryFactBatchWriteDisposition.CONFLICT
            }
        }
        checkpoints[key] = checkpoint
        return LegacyMasteryFactBatchWriteDisposition.IMPORTED
    }

    override suspend fun eraseAllLearnerData(): LearnerMasteryEraseResult {
        sourceFacts.clear()
        candidates.clear()
        checkpoints.clear()
        return LearnerMasteryEraseResult(0L)
    }

    override fun close() = Unit
}

private fun awaitSubmissionDrainWait(thread: Thread) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
    while (
        thread.isAlive &&
            thread.state != Thread.State.WAITING &&
            thread.state != Thread.State.TIMED_WAITING
    ) {
        check(System.nanoTime() < deadline) {
            "Revocation did not reach the in-flight submission drain"
        }
        Thread.yield()
    }
    check(thread.isAlive) {
        "Revocation finished before observing an in-flight submission"
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var completed: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<T>) {
                completed = result
            }
        },
    )
    return checkNotNull(completed) {
        "Test coroutine unexpectedly suspended"
    }.getOrThrow()
}
