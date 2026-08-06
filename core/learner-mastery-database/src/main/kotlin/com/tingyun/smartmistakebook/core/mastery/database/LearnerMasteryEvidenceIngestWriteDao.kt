package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleState
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import java.util.UUID

@Dao
internal abstract class LearnerMasteryEvidenceIngestWriteDao : LearnerMasteryProjectionRebuildWriteDao() {
    @Transaction
    internal open suspend fun acceptProblemKnowledgeBindings(
        message: MasteryCrossStoreInboxEntity,
        sourceBinding: MasteryStudentRelaySourceBindingEntity,
        authenticityReceipt: MasteryAuthenticatedStudentInboxReceiptEntity,
        incomingState: MasteryProblemBindingAuthorityStateEntity,
        authorities: List<MasteryProblemBindingAuthorityEntity>,
    ): MasteryInboundDisposition {
        if (!sourceBinding.matchesAuthenticatedDelivery(message, authenticityReceipt)) {
            return MasteryInboundDisposition.CONFLICT
        }
        if (!acceptStudentRelaySourceBinding(sourceBinding)) {
            return MasteryInboundDisposition.CONFLICT
        }
        val stateDisposition =
            bindingAuthorityStateDisposition(
                current =
                    findProblemBindingAuthorityState(
                        incomingState.problemRevisionRefFingerprint,
                    ),
                incoming = incomingState,
            )
        if (stateDisposition == MasteryInboundDisposition.CONFLICT) {
            return MasteryInboundDisposition.CONFLICT
        }
        if (insertInbox(message) == INSERT_CONFLICT) {
            val existing =
                findInboxByEventId(message.eventId)
                    ?: findInboxByIdempotency(message.idempotencyKey)
                    ?: findInboxByAggregateVersion(
                        sourceStore = message.sourceStore,
                        sourceStoreGeneration = message.sourceStoreGeneration,
                        aggregateId = message.aggregateId,
                        aggregateVersion = message.aggregateVersion,
                        payloadType = message.payloadType,
                        payloadVersion = message.payloadVersion,
                    )
                    ?: return MasteryInboundDisposition.CONFLICT
            return if (sameAuthenticatedStudentDelivery(existing, message, authenticityReceipt)) {
                MasteryInboundDisposition.DUPLICATE
            } else {
                MasteryInboundDisposition.CONFLICT
            }
        }
        insertAuthenticatedStudentInboxReceipt(authenticityReceipt)
        if (stateDisposition == MasteryInboundDisposition.DUPLICATE) {
            return MasteryInboundDisposition.DUPLICATE
        }

        deleteProblemBindingAuthorities(incomingState.problemRevisionRefFingerprint)
        if (authorities.isNotEmpty()) {
            insertProblemBindingAuthorities(authorities)
        }
        upsertProblemBindingAuthorityState(incomingState)
        val accepted =
            findProblemBindingAuthorities(
                incomingState.problemRevisionRefFingerprint,
            ).mapTo(mutableSetOf(), MasteryProblemBindingAuthorityEntity::bindingRefFingerprint)
        check(accepted == authorities.mapTo(mutableSetOf()) { it.bindingRefFingerprint }) {
            "Current problem binding authority does not match its accepted snapshot"
        }
        return MasteryInboundDisposition.APPLIED
    }

    @Transaction
    internal open suspend fun acceptStudentProblemReference(
        message: MasteryCrossStoreInboxEntity,
        sourceBinding: MasteryStudentRelaySourceBindingEntity,
        authenticityReceipt: MasteryAuthenticatedStudentInboxReceiptEntity,
    ): MasteryInboundDisposition {
        if (!sourceBinding.matchesAuthenticatedDelivery(message, authenticityReceipt)) {
            return MasteryInboundDisposition.CONFLICT
        }
        if (!acceptStudentRelaySourceBinding(sourceBinding)) {
            return MasteryInboundDisposition.CONFLICT
        }
        if (insertInbox(message) != INSERT_CONFLICT) {
            insertAuthenticatedStudentInboxReceipt(authenticityReceipt)
            return MasteryInboundDisposition.APPLIED
        }
        val existing =
            findInboxByEventId(message.eventId)
                ?: findInboxByIdempotency(message.idempotencyKey)
                ?: findInboxByAggregateVersion(
                    sourceStore = message.sourceStore,
                    sourceStoreGeneration = message.sourceStoreGeneration,
                    aggregateId = message.aggregateId,
                    aggregateVersion = message.aggregateVersion,
                    payloadType = message.payloadType,
                    payloadVersion = message.payloadVersion,
                )
                ?: return MasteryInboundDisposition.CONFLICT
        return if (sameAuthenticatedStudentDelivery(existing, message, authenticityReceipt)) {
            MasteryInboundDisposition.DUPLICATE
        } else {
            MasteryInboundDisposition.CONFLICT
        }
    }

    @Transaction
    internal open suspend fun acceptProblemLifecycleChange(
        message: MasteryCrossStoreInboxEntity,
        sourceBinding: MasteryStudentRelaySourceBindingEntity,
        authenticityReceipt: MasteryAuthenticatedStudentInboxReceiptEntity,
    ): MasteryInboundDisposition {
        if (!sourceBinding.matchesAuthenticatedDelivery(message, authenticityReceipt)) {
            return MasteryInboundDisposition.CONFLICT
        }
        if (!acceptStudentRelaySourceBinding(sourceBinding)) {
            return MasteryInboundDisposition.CONFLICT
        }
        val applied =
            if (insertInbox(message) != INSERT_CONFLICT) {
                insertAuthenticatedStudentInboxReceipt(authenticityReceipt)
                true
            } else {
                val existing =
                    findInboxByEventId(message.eventId)
                        ?: findInboxByIdempotency(message.idempotencyKey)
                        ?: findInboxByAggregateVersion(
                            sourceStore = message.sourceStore,
                            sourceStoreGeneration = message.sourceStoreGeneration,
                            aggregateId = message.aggregateId,
                            aggregateVersion = message.aggregateVersion,
                            payloadType = message.payloadType,
                            payloadVersion = message.payloadVersion,
                        )
                        ?: return MasteryInboundDisposition.CONFLICT
                if (!sameAuthenticatedStudentDelivery(existing, message, authenticityReceipt)) {
                    return MasteryInboundDisposition.CONFLICT
                }
                false
            }
        if (!applied) {
            return MasteryInboundDisposition.DUPLICATE
        }
        val payload =
            LearnerMasteryCrossStoreCodec.decode(
                payloadType = message.payloadType,
                payloadVersion = message.payloadVersion,
                wire = message.payloadWire,
            ) as? ProblemLifecycleChangedV1
                ?: return MasteryInboundDisposition.CONFLICT
        if (payload.nextState != ProblemLifecycleState.TOMBSTONED) {
            return MasteryInboundDisposition.APPLIED
        }
        retireLearningEventsForRevision(
            learnerId = payload.problemRevision.problem.learnerId,
            problemRevisionRefFingerprint = payload.problemRevision.canonicalFingerprint,
            retiredAtEpochMillis = payload.changedAtEpochMillis,
        )
        return MasteryInboundDisposition.APPLIED
    }

    @Transaction
    internal open suspend fun acceptProblemRevisionSupersession(
        message: MasteryCrossStoreInboxEntity,
        sourceBinding: MasteryStudentRelaySourceBindingEntity,
        authenticityReceipt: MasteryAuthenticatedStudentInboxReceiptEntity,
    ): MasteryInboundDisposition {
        if (!sourceBinding.matchesAuthenticatedDelivery(message, authenticityReceipt)) {
            return MasteryInboundDisposition.CONFLICT
        }
        if (!acceptStudentRelaySourceBinding(sourceBinding)) {
            return MasteryInboundDisposition.CONFLICT
        }
        val applied =
            if (insertInbox(message) != INSERT_CONFLICT) {
                insertAuthenticatedStudentInboxReceipt(authenticityReceipt)
                true
            } else {
                val existing =
                    findInboxByEventId(message.eventId)
                        ?: findInboxByIdempotency(message.idempotencyKey)
                        ?: findInboxByAggregateVersion(
                            sourceStore = message.sourceStore,
                            sourceStoreGeneration = message.sourceStoreGeneration,
                            aggregateId = message.aggregateId,
                            aggregateVersion = message.aggregateVersion,
                            payloadType = message.payloadType,
                            payloadVersion = message.payloadVersion,
                        )
                        ?: return MasteryInboundDisposition.CONFLICT
                if (!sameAuthenticatedStudentDelivery(existing, message, authenticityReceipt)) {
                    return MasteryInboundDisposition.CONFLICT
                }
                false
            }
        if (!applied) {
            return MasteryInboundDisposition.DUPLICATE
        }
        val payload =
            LearnerMasteryCrossStoreCodec.decode(
                payloadType = message.payloadType,
                payloadVersion = message.payloadVersion,
                wire = message.payloadWire,
            ) as? ProblemRevisionSupersededV1
                ?: return MasteryInboundDisposition.CONFLICT
        retireLearningEventsForRevision(
            learnerId = payload.previousRevision.problem.learnerId,
            problemRevisionRefFingerprint =
                payload.previousRevision.canonicalFingerprint,
            retiredAtEpochMillis = payload.changedAtEpochMillis,
        )
        return MasteryInboundDisposition.APPLIED
    }

    private suspend fun acceptStudentRelaySourceBinding(
        incoming: MasteryStudentRelaySourceBindingEntity,
    ): Boolean {
        if (insertStudentRelaySourceBinding(incoming) != INSERT_CONFLICT) {
            return true
        }
        return findStudentRelaySourceBinding(incoming.learnerId)
            ?.samePinnedSource(incoming) == true
    }

    private suspend fun sameAuthenticatedStudentDelivery(
        existingMessage: MasteryCrossStoreInboxEntity,
        incomingMessage: MasteryCrossStoreInboxEntity,
        incomingReceipt: MasteryAuthenticatedStudentInboxReceiptEntity,
    ): Boolean =
        existingMessage.envelopeCanonicalFingerprint ==
            incomingMessage.envelopeCanonicalFingerprint &&
            existingMessage.payloadCanonicalFingerprint ==
            incomingMessage.payloadCanonicalFingerprint &&
            existingMessage.idempotencyKey == incomingMessage.idempotencyKey &&
            findAuthenticatedStudentInboxReceipt(existingMessage.eventId)
                ?.sameVerifiedDelivery(incomingReceipt) == true

    @Transaction
    internal open suspend fun migrateLegacyFactBatch(
        observations: List<MasteryLegacyObservationWrite>,
        checkpoint: MasteryLegacyFactMigrationCheckpointEntity,
    ): LegacyMasteryFactBatchWriteDisposition {
        findMigrationCheckpoint(
            learnerId = checkpoint.learnerId,
            sourceGeneration = checkpoint.sourceGeneration,
            batchSequence = checkpoint.batchSequence,
        )?.let { existing ->
            if (existing.sameMigrationIdentity(checkpoint)) {
                return LegacyMasteryFactBatchWriteDisposition.DUPLICATE
            }
            throw LegacyMasteryBatchConflictException()
        }

        if (
            isMigrationLedgerSealed(
                learnerId = checkpoint.learnerId,
                sourceGeneration = checkpoint.sourceGeneration,
            )
        ) {
            return LegacyMasteryFactBatchWriteDisposition.OUT_OF_ORDER
        }
        val latest =
            findLatestMigrationCheckpoint(
                learnerId = checkpoint.learnerId,
                sourceGeneration = checkpoint.sourceGeneration,
            )
        val expectedSequence = (latest?.batchSequence ?: 0L) + 1L
        if (latest?.finalBatch == true || checkpoint.batchSequence != expectedSequence) {
            return LegacyMasteryFactBatchWriteDisposition.OUT_OF_ORDER
        }

        observations.forEachIndexed { observationOrdinal, observation ->
            if (
                ingestSourceFact(observation.sourceFact, observation.sourceProof) ==
                InternalSourceFactWriteResult.Conflict
            ) {
                throw LegacyMasteryBatchConflictException()
            }
            val candidateResult =
                ingestCandidate(
                    candidate = observation.candidate,
                    attributions = observation.attributions,
                    decisionTimeEpochMillis = observation.decisionTimeEpochMillis,
                    candidateOrigin = observation.candidateOrigin,
                )
            if (candidateResult.disposition == LearningObservationDisposition.CONFLICT) {
                throw LegacyMasteryBatchConflictException()
            }
            val persistedSourceFact =
                checkNotNull(findSourceFact(observation.sourceFact.sourceFactId)) {
                    "Migrated mastery source fact was not durable"
                }
            val persistedSourceProof =
                checkNotNull(findSourceProof(persistedSourceFact.sourceFactId)) {
                    "Migrated mastery source proof was not durable"
                }
            val persistedCandidate =
                checkNotNull(findCandidate(candidateResult.candidateId)) {
                    "Migrated mastery candidate was not durable"
                }
            val persistedObservation =
                MasteryLegacyObservationWrite(
                    sourceFact = persistedSourceFact,
                    sourceProof = persistedSourceProof,
                    candidate = persistedCandidate,
                    attributions = findCandidateAttributions(persistedCandidate.candidateId),
                    decisionTimeEpochMillis = observation.decisionTimeEpochMillis,
                    candidateOrigin = observation.candidateOrigin,
                )
            if (
                insertMigrationDestinationRecord(
                    persistedObservation.toMigrationDestinationRecord(
                        checkpoint = checkpoint,
                        observationOrdinal = observationOrdinal,
                    ),
                ) == INSERT_CONFLICT
            ) {
                throw LegacyMasteryBatchConflictException()
            }
        }

        return when (recordMigrationCheckpoint(checkpoint)) {
            MigrationCheckpointWriteDisposition.STORED ->
                LegacyMasteryFactBatchWriteDisposition.IMPORTED
            MigrationCheckpointWriteDisposition.DUPLICATE ->
                LegacyMasteryFactBatchWriteDisposition.DUPLICATE
            MigrationCheckpointWriteDisposition.CONFLICT ->
                throw LegacyMasteryBatchConflictException()
        }
    }

    @Transaction
    internal open suspend fun recordMigrationCheckpoint(
        checkpoint: MasteryLegacyFactMigrationCheckpointEntity,
    ): MigrationCheckpointWriteDisposition {
        if (insertMigrationCheckpoint(checkpoint) != INSERT_CONFLICT) {
            return MigrationCheckpointWriteDisposition.STORED
        }
        val existing =
            findMigrationCheckpoint(
                learnerId = checkpoint.learnerId,
                sourceGeneration = checkpoint.sourceGeneration,
                batchSequence = checkpoint.batchSequence,
            ) ?: return MigrationCheckpointWriteDisposition.CONFLICT
        return if (
            existing.learnerId == checkpoint.learnerId &&
            existing.sourceGeneration == checkpoint.sourceGeneration &&
            existing.batchSequence == checkpoint.batchSequence &&
            existing.batchFingerprint == checkpoint.batchFingerprint &&
            existing.observationCount == checkpoint.observationCount &&
            existing.finalBatch == checkpoint.finalBatch &&
            existing.sourcePolicyVersion == checkpoint.sourcePolicyVersion &&
            existing.projectionPolicyVersion == checkpoint.projectionPolicyVersion
        ) {
            MigrationCheckpointWriteDisposition.DUPLICATE
        } else {
            MigrationCheckpointWriteDisposition.CONFLICT
        }
    }

    @Transaction
    internal open suspend fun ingestSourceFact(
        fact: MasterySourceFactEntity,
        proof: MasterySourceProofEntity,
    ): InternalSourceFactWriteResult {
        if (insertSourceFact(fact) != INSERT_CONFLICT) {
            insertSourceProof(proof)
            return InternalSourceFactWriteResult.Stored(proof.policySupported)
        }
        val existing =
            findSourceFact(fact.sourceFactId)
                ?: findSourceFactByIdempotency(fact.idempotencyKey)
                ?: fact.authorityAttemptFingerprint?.let { fingerprint ->
                    findSourceFactByAuthorityAttempt(
                        learnerId = fact.learnerId,
                        subject = fact.subject,
                        authorityAttemptFingerprint = fingerprint,
                    )
                }
                ?: fact.authoritySubmissionFingerprint?.let { fingerprint ->
                    findSourceFactByAuthoritySubmission(
                        learnerId = fact.learnerId,
                        subject = fact.subject,
                        authoritySubmissionFingerprint = fingerprint,
                    )
                }
                ?: return InternalSourceFactWriteResult.Conflict
        val existingProof = findSourceProof(existing.sourceFactId)
        return if (
            existing.canonicalFingerprint == fact.canonicalFingerprint &&
            existing.idempotencyKey == fact.idempotencyKey &&
            existingProof?.proofFingerprint == proof.proofFingerprint
        ) {
            InternalSourceFactWriteResult.Duplicate
        } else {
            InternalSourceFactWriteResult.Conflict
        }
    }

    @Transaction
    internal open suspend fun enqueuePendingOpenResponse(
        fact: MasterySourceFactEntity,
        proof: MasterySourceProofEntity,
        candidate: MasteryObservationCandidateEntity,
        decidedAtEpochMillis: Long,
    ): PendingOpenResponsePersistenceResult {
        check(
            fact.outcome == ObservedLearningOutcome.PENDING_REVIEW.name &&
                fact.responseForm == TrustedLearningResponseForm.FREE_RESPONSE.name &&
                candidate.sourceFactId == fact.sourceFactId &&
                candidate.learnerId == fact.learnerId &&
                candidate.subject == fact.subject &&
                proof.sourceFactId == fact.sourceFactId &&
                proof.sourceFactCanonicalFingerprint == fact.canonicalFingerprint,
        ) {
            "Pending open-response persistence received an inconsistent fact bundle"
        }
        if (candidate.requestedPolicyVersion != LEARNER_MASTERY_PROJECTION_POLICY_VERSION) {
            return PendingOpenResponsePersistenceResult(
                sourceFactId = fact.sourceFactId,
                reviewCaseId = null,
                status = PendingOpenResponsePersistenceStatus.CONFLICT,
            )
        }
        val existingFact =
            findSourceFact(fact.sourceFactId)
                ?: findSourceFactByIdempotency(fact.idempotencyKey)
                ?: findSourceFactBySourceReference(
                    learnerId = fact.learnerId,
                    sourceKind = fact.sourceKind,
                    sourceReferenceId = fact.sourceReferenceId,
                )
                ?: findSourceFactByCanonicalFingerprint(fact.canonicalFingerprint)
        if (
            existingFact != null &&
            (
                existingFact.canonicalFingerprint != fact.canonicalFingerprint ||
                    existingFact.idempotencyKey != fact.idempotencyKey ||
                    findSourceProof(existingFact.sourceFactId) != proof
                )
        ) {
            return PendingOpenResponsePersistenceResult(
                sourceFactId = fact.sourceFactId,
                reviewCaseId = null,
                status = PendingOpenResponsePersistenceStatus.CONFLICT,
            )
        }

        val reason = LearningObservationInertReason.MODEL_ONLY_OPEN_RESPONSE
        val expectedReceipt =
            inertReceipt(
                candidate = candidate,
                sourceProof = proof,
                reason = reason,
                decidedAtEpochMillis = decidedAtEpochMillis,
            )
        val expectedReviewCase =
            boundEvidenceReviewCase(
                candidate = candidate,
                sourceFact = fact,
                sourceProof = proof,
                reason = reason,
                createdAtEpochMillis = decidedAtEpochMillis,
            )
        val existingCandidate =
            findCandidate(candidate.candidateId)
                ?: findCandidateByIdempotency(candidate.idempotencyKey)
                ?: findCandidateBySourceFact(candidate.sourceFactId)
                ?: findCandidateByCanonicalFingerprint(candidate.canonicalFingerprint)
        if (existingCandidate != null) {
            val duplicate =
                existingFact != null &&
                    existingCandidate.canonicalFingerprint == candidate.canonicalFingerprint &&
                    existingCandidate.idempotencyKey == candidate.idempotencyKey &&
                    existingCandidate.candidateOrigin == candidate.candidateOrigin &&
                    findAdmissionReceipt(existingCandidate.candidateId) == expectedReceipt &&
                    findEvidenceReviewCase(expectedReviewCase.reviewCaseId) ==
                    expectedReviewCase
            return PendingOpenResponsePersistenceResult(
                sourceFactId = fact.sourceFactId,
                reviewCaseId = expectedReviewCase.reviewCaseId.takeIf { duplicate },
                status =
                    if (duplicate) {
                        PendingOpenResponsePersistenceStatus.DUPLICATE
                    } else {
                        PendingOpenResponsePersistenceStatus.CONFLICT
                    },
            )
        }

        if (existingFact == null) {
            check(insertSourceFact(fact) != INSERT_CONFLICT) {
                "Pending open-response source fact conflicted after transaction preflight"
            }
            insertSourceProof(proof)
        }
        check(insertCandidate(candidate) != INSERT_CONFLICT) {
            "Pending open-response candidate conflicted after transaction preflight"
        }
        insertAdmissionReceipt(expectedReceipt)
        insertEvidenceReviewCase(expectedReviewCase)
        return PendingOpenResponsePersistenceResult(
            sourceFactId = fact.sourceFactId,
            reviewCaseId = expectedReviewCase.reviewCaseId,
            status = PendingOpenResponsePersistenceStatus.QUEUED,
        )
    }

    /**
     * Attaches immutable model provenance, then lets local policy either retain the case or admit
     * bounded weak evidence through the dedicated proof chain in this serialized transaction.
     */
    @Transaction
    internal open suspend fun recordOpenResponseWeakCandidate(
        owner: RoomOpenResponseWeakCandidateOwner,
        command: LearnerMasteryOpenResponseWeakCandidateCommand,
        authorization: LearnerMasteryOpenResponseWeakCandidateAuthorization,
        receivedAtEpochMillis: Long,
    ): LearnerMasteryOpenResponseWeakCandidateResult {
        authorization.authorizeTransactionStart(
            owner,
            command.learnerId,
            command.scopeFingerprint,
        )
        if (receivedAtEpochMillis < command.occurredAtEpochMillis) {
            return rejectedOpenResponseWeakCandidate()
        }

        val fact =
            findSourceFact(command.sourceFactId)
                ?: return rejectedOpenResponseWeakCandidate()
        val candidate =
            findCandidateBySourceFact(command.sourceFactId)
                ?: return rejectedOpenResponseWeakCandidate()
        val reviewCase =
            findEvidenceReviewCase(command.reviewCaseId)
                ?: return rejectedOpenResponseWeakCandidate()
        val admissionReceipt =
            findAdmissionReceipt(candidate.candidateId)
                ?: return rejectedOpenResponseWeakCandidate()

        if (
            fact.learnerId != command.learnerId ||
            fact.subject != command.subject.name ||
            fact.sourceKind != MasteryEvidenceSourceKind.TUTOR_FREE_RESPONSE.name ||
            fact.outcome != ObservedLearningOutcome.PENDING_REVIEW.name ||
            fact.responseForm != TrustedLearningResponseForm.FREE_RESPONSE.name ||
            fact.authority != MasteryEvidenceAuthority.SELF_REPORTED.name ||
            fact.evidenceContextKind !=
            MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM.name ||
            fact.presentationFingerprint.isBlank() ||
            fact.problemFamilyFingerprint == null ||
            fact.ephemeralProblemFingerprint == null ||
            fact.tutorTurnReferenceId != command.turnReferenceId ||
            fact.hintCount != command.hintCount ||
            fact.answerRevealed != command.answerWasRevealed ||
            fact.retryState != expectedOpenResponseRetryState(command.attemptOrdinal).name ||
            fact.assistance !=
            expectedOpenResponseAssistance(
                attemptOrdinal = command.attemptOrdinal,
                hintCount = command.hintCount,
                answerWasRevealed = command.answerWasRevealed,
            ).name ||
            fact.independentlyAnswered !=
            (
                command.attemptOrdinal == 1 &&
                    command.hintCount == 0 &&
                    !command.answerWasRevealed
                ) ||
            fact.occurredAtEpochMillis != command.occurredAtEpochMillis ||
            candidate.learnerId != command.learnerId ||
            candidate.subject != command.subject.name ||
            candidate.sourceFactId != command.sourceFactId ||
            candidate.confidence != MasteryCandidateConfidence.LOW.name ||
            candidate.candidateOrigin != MasteryCandidateOrigin.TRUSTED_LOCAL.name ||
            candidate.requestedPolicyVersion != LEARNER_MASTERY_PROJECTION_POLICY_VERSION ||
            findCandidateAttributions(candidate.candidateId).isNotEmpty() ||
            reviewCase.learnerId != command.learnerId ||
            reviewCase.subject != command.subject.name ||
            reviewCase.sourceFactId != command.sourceFactId ||
            reviewCase.candidateId != candidate.candidateId ||
            reviewCase.candidateCanonicalFingerprint != candidate.canonicalFingerprint ||
            reviewCase.reason != LearningObservationInertReason.MODEL_ONLY_OPEN_RESPONSE.name ||
            findEvidenceReviewResolutionByCase(reviewCase.reviewCaseId) != null ||
            admissionReceipt.candidateCanonicalFingerprint != candidate.canonicalFingerprint ||
            admissionReceipt.inertReason !=
            LearningObservationInertReason.MODEL_ONLY_OPEN_RESPONSE.name ||
            admissionReceipt.eventId != null
        ) {
            return rejectedOpenResponseWeakCandidate()
        }

        findOpenResponseWeakCandidateReceiptByIdempotency(
            command.candidateIdempotencyKey,
        )?.let { existing ->
            val existingRevisionOrdinal =
                existing.revisionOrdinal
                    ?: return rejectedOpenResponseWeakCandidate(
                        LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    )
            val expected =
                command.toReceiptEntity(
                    sourceFact = fact,
                    candidate = candidate,
                    reviewCase = reviewCase,
                    revisionOrdinal = existingRevisionOrdinal,
                    receivedAtEpochMillis = receivedAtEpochMillis,
                )
            val receiptMatches =
                existing.receiptFingerprint == expected.receiptFingerprint &&
                    existing.canonicalFingerprint == expected.canonicalFingerprint &&
                    existing.candidateId == expected.candidateId &&
                    existing.reviewCaseId == expected.reviewCaseId &&
                    existing.revisionOrdinal == expected.revisionOrdinal
            if (!receiptMatches) {
                return rejectedOpenResponseWeakCandidate(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                )
            }
            val expectedAttestation =
                command.toModelAttestationEntity(existing, candidate, reviewCase)
            val persistedAttestation =
                findOpenResponseModelEvaluationAttestation(existing.receiptFingerprint)
            // DB20 receipts deliberately have no attestation and remain inert. New proof chains
            // are accepted as duplicates only when every immutable row matches exactly.
            if (persistedAttestation == null) {
                return if (existing.proofChainVersion == 0) {
                    LearnerMasteryOpenResponseWeakCandidateResult(
                        disposition =
                            LearnerMasteryOpenResponseWeakCandidateDisposition.DUPLICATE,
                        receiptFingerprint = existing.receiptFingerprint,
                    )
                } else {
                    rejectedOpenResponseWeakCandidate(
                        LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    )
                }
            }
            val expectedScope = command.toKnowledgeScopeEntities(expectedAttestation)
            val persistedScope =
                findOpenResponseEvaluationKnowledgeScope(
                    expectedAttestation.attestationFingerprint,
                )
            val persistedDecision =
                findOpenResponseDedicatedDecision(existing.receiptFingerprint)
            return if (
                persistedAttestation == expectedAttestation &&
                existing.proofChainVersion == CURRENT_OPEN_RESPONSE_PROOF_CHAIN_VERSION &&
                persistedScope == expectedScope &&
                persistedDecision != null
            ) {
                LearnerMasteryOpenResponseWeakCandidateResult(
                    disposition =
                        LearnerMasteryOpenResponseWeakCandidateDisposition.DUPLICATE,
                    receiptFingerprint = existing.receiptFingerprint,
                )
            } else {
                rejectedOpenResponseWeakCandidate(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                )
            }
        }

        if (
            countUnresolvedOpenResponseWeakCandidateRevisions(
                command.logicalAttemptFingerprint,
            ) != 0L
        ) {
            return rejectedOpenResponseWeakCandidate(
                LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
            )
        }
        val latest =
            findLatestOpenResponseWeakCandidateReceipt(
                command.logicalAttemptFingerprint,
            )
        val revisionOrdinal =
            when {
                command.revisionOfCandidateIdempotencyKey == null && latest == null -> 0L
                command.revisionOfCandidateIdempotencyKey == null -> {
                    return rejectedOpenResponseWeakCandidate(
                        LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    )
                }
                latest?.candidateIdempotencyKey !=
                    command.revisionOfCandidateIdempotencyKey -> {
                    return rejectedOpenResponseWeakCandidate(
                        LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    )
                }
                latest.revisionOrdinal == null ||
                    latest.revisionOrdinal == Long.MAX_VALUE -> {
                    return rejectedOpenResponseWeakCandidate(
                        LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    )
                }
                else -> latest.revisionOrdinal + 1L
            }
        val expected =
            command.toReceiptEntity(
                sourceFact = fact,
                candidate = candidate,
                reviewCase = reviewCase,
                revisionOrdinal = revisionOrdinal,
                receivedAtEpochMillis = receivedAtEpochMillis,
            )
        val attestation =
            command.toModelAttestationEntity(expected, candidate, reviewCase)
        val knowledgeScope = command.toKnowledgeScopeEntities(attestation)
        val prepared =
            prepareOpenResponseDedicatedDecision(
                sourceFact = fact,
                reviewCase = reviewCase,
                receipt = expected,
                attestation = attestation,
                knowledgeScope = knowledgeScope,
                decidedAtEpochMillis = receivedAtEpochMillis,
            ) ?: return rejectedOpenResponseWeakCandidate()
        authorization.authorizeFinalInsert(
            owner,
            command.learnerId,
            command.scopeFingerprint,
        )
        if (insertOpenResponseWeakCandidateReceipt(expected) == INSERT_CONFLICT) {
            return rejectedOpenResponseWeakCandidate(
                LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
            )
        }
        insertOpenResponseModelEvaluationAttestation(attestation)
        if (knowledgeScope.isNotEmpty()) {
            insertOpenResponseEvaluationKnowledgeScope(knowledgeScope)
        }
        insertOpenResponseDedicatedDecision(prepared.decision)
        return LearnerMasteryOpenResponseWeakCandidateResult(
            disposition =
                LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
            receiptFingerprint = expected.receiptFingerprint,
        )
    }

    private suspend fun prepareOpenResponseDedicatedDecision(
        sourceFact: MasterySourceFactEntity,
        reviewCase: MasteryEvidenceReviewCaseEntity,
        receipt: MasteryOpenResponseWeakCandidateReceiptEntity,
        attestation: MasteryOpenResponseModelEvaluationAttestationEntity,
        knowledgeScope: List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>,
        decidedAtEpochMillis: Long,
    ): PreparedOpenResponseDedicatedDecision? {
        val authorizedFingerprint =
            fingerprintAuthorizedKnowledgeRefs(
                knowledgeScope.map(
                    MasteryOpenResponseEvaluationKnowledgeScopeEntity::knowledgeNodeRefFingerprint,
                ),
            )
        if (
            authorizedFingerprint != sourceFact.authorizedKnowledgeRefsFingerprint ||
            knowledgeScope.any {
                it.attestationFingerprint != attestation.attestationFingerprint ||
                    it.subject != sourceFact.subject
            } ||
            (
                knowledgeScope.isEmpty() &&
                    (
                        sourceFact.knowledgeManifestFingerprint != null ||
                            sourceFact.knowledgeActivationGeneration != null
                        )
                ) ||
            (
                knowledgeScope.isNotEmpty() &&
                    knowledgeScope.any {
                        it.manifestFingerprint != sourceFact.knowledgeManifestFingerprint ||
                            it.activationGeneration !=
                            sourceFact.knowledgeActivationGeneration
                    }
                )
        ) {
            return null
        }
        val calibration = resolveEvidenceReviewCalibration(reviewCase) ?: return null
        val decidedAt =
            maxOf(
                decidedAtEpochMillis,
                reviewCase.createdAtEpochMillis,
                sourceFact.attestedAtEpochMillis,
            )

        fun retained(
            reason: OpenResponseDedicatedDecisionReason,
        ): PreparedOpenResponseDedicatedDecision =
            PreparedOpenResponseDedicatedDecision(
                decision =
                    retainedOpenResponseDedicatedDecisionEntity(
                        attestation = attestation,
                        receipt = receipt,
                        reviewCase = reviewCase,
                        reason = reason,
                        calibrationSnapshot = calibration,
                        decidedAtEpochMillis = decidedAt,
                    ),
            )

        return retained(
            CURRENT_OPEN_RESPONSE_RUNTIME_REASON,
        )
    }

    @Transaction
    internal open suspend fun ingestModelCandidateAttempt(
        candidate: MasteryObservationCandidateEntity,
        attributions: List<MasteryCandidateAttributionEntity>,
        decisionTimeEpochMillis: Long,
        attempt: ModelSubmissionAttempt,
    ): ModelSubmissionAttemptOutcome {
        if (!modelPermissionScopeMatches(candidate, attempt.scope)) {
            return ModelSubmissionAttemptOutcome.Terminal(
                persistModelSubmissionAttemptReceipt(
                    attempt = attempt,
                    terminalReason = ModelSubmissionTerminalReason.PERMISSION_SCOPE_MISMATCH,
                    candidateId = null,
                    admissionReceiptFingerprint = null,
                    receivedAtEpochMillis = decisionTimeEpochMillis,
                    primary = false,
                ),
            )
        }
        attempt.authorizeCommit()
        findModelSubmissionAttemptReceipt(
            requestGenerationFingerprint =
                attempt.scope.requestGenerationFingerprint,
            proposalFingerprint = attempt.proposalFingerprint,
        )?.let { existing ->
            attempt.authorizeCommit()
            return ModelSubmissionAttemptOutcome.Terminal(
                ModelSubmissionAttemptReceipt(existing.receiptFingerprint),
            )
        }
        if (
            attempt.claim ==
            ModelSubmissionCandidateClaim.LOGICAL_ATTEMPT_CONFLICT ||
            findPrimaryModelSubmissionAttemptReceipt(
                attempt.scope.logicalRequestFingerprint,
            ) != null
        ) {
            return ModelSubmissionAttemptOutcome.Terminal(
                persistModelSubmissionAttemptReceipt(
                    attempt = attempt,
                    terminalReason = ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT,
                    candidateId = null,
                    admissionReceiptFingerprint = null,
                    receivedAtEpochMillis = decisionTimeEpochMillis,
                    primary = false,
                ),
            )
        }

        val result =
            ingestCandidate(
                candidate = candidate,
                attributions = attributions,
                decisionTimeEpochMillis = decisionTimeEpochMillis,
                candidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED,
            )
        if (result.retryable) {
            attempt.authorizeCommit()
            return ModelSubmissionAttemptOutcome.RebuildPending
        }
        val admissionReceipt =
            if (result.disposition == LearningObservationDisposition.CONFLICT) {
                null
            } else {
                checkNotNull(findAdmissionReceipt(result.candidateId)) {
                    "A received model candidate has no durable terminal admission receipt"
                }
            }
        val receipt =
            persistModelSubmissionAttemptReceipt(
                attempt = attempt,
                terminalReason = result.toModelSubmissionTerminalReason(),
                candidateId = admissionReceipt?.candidateId,
                admissionReceiptFingerprint = admissionReceipt?.receiptFingerprint,
                receivedAtEpochMillis = decisionTimeEpochMillis,
                primary = true,
            )
        attempt.authorizeCommit()
        return ModelSubmissionAttemptOutcome.Terminal(receipt)
    }

    @Transaction
    internal open suspend fun recordRejectedModelCandidateAttempt(
        attempt: ModelSubmissionAttempt,
        terminalReason: ModelSubmissionTerminalReason,
        receivedAtEpochMillis: Long,
    ): ModelSubmissionAttemptReceipt {
        require(
            terminalReason == ModelSubmissionTerminalReason.MALFORMED_SUBMISSION ||
                terminalReason ==
                ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT ||
                terminalReason ==
                ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED ||
                terminalReason ==
                ModelSubmissionTerminalReason.PERMISSION_SCOPE_MISMATCH,
        ) {
            "Only a locally rejected submission may bypass candidate persistence"
        }
        findModelSubmissionAttemptReceipt(
            requestGenerationFingerprint =
                attempt.scope.requestGenerationFingerprint,
            proposalFingerprint = attempt.proposalFingerprint,
        )?.let { existing ->
            return ModelSubmissionAttemptReceipt(existing.receiptFingerprint)
        }
        val logicalConflict =
            attempt.claim ==
                ModelSubmissionCandidateClaim.LOGICAL_ATTEMPT_CONFLICT ||
                findPrimaryModelSubmissionAttemptReceipt(
                    attempt.scope.logicalRequestFingerprint,
                ) != null
        return persistModelSubmissionAttemptReceipt(
            attempt = attempt,
            terminalReason =
                if (logicalConflict) {
                    ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT
                } else {
                    terminalReason
                },
            candidateId = null,
            admissionReceiptFingerprint = null,
            receivedAtEpochMillis = receivedAtEpochMillis,
            primary =
                !logicalConflict &&
                    terminalReason !=
                    ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED &&
                    terminalReason !=
                    ModelSubmissionTerminalReason.PERMISSION_SCOPE_MISMATCH,
        )
    }

    private suspend fun modelPermissionScopeMatches(
        candidate: MasteryObservationCandidateEntity,
        scope: ModelSubmissionAttemptScope,
    ): Boolean {
        val permission = scope.permission
        if (
            candidate.learnerId != scope.learnerId ||
            candidate.subject != scope.subject.name ||
            candidate.sourceFactId != scope.sourceFactId ||
            candidate.modelVersion != scope.modelVersion ||
            candidate.requestedPolicyVersion != permission.projectionPolicyVersion
        ) {
            return false
        }
        val sourceFact = findSourceFact(scope.sourceFactId) ?: return true
        if (
            sourceFact.learnerId != scope.learnerId ||
            sourceFact.subject != scope.subject.name ||
            sourceFact.sourcePolicyVersion != permission.sourcePolicyVersion
        ) {
            return false
        }
        val savedRevision = sourceFact.problemRevisionRefFingerprint
        return if (savedRevision != null) {
            val bindingState = findProblemBindingAuthorityState(savedRevision)
            permission.problemRevisionFingerprint == savedRevision &&
                permission.problemDocumentFingerprint ==
                sourceFact.problemDocumentFingerprint &&
                permission.problemFingerprint == null &&
                (
                    bindingState == null ||
                        permission.bindingSetVersion == bindingState.bindingSetVersion
                )
        } else {
            permission.problemRevisionFingerprint == null &&
                permission.problemFingerprint == sourceFact.ephemeralProblemFingerprint &&
                permission.knowledgeManifestFingerprint ==
                sourceFact.knowledgeManifestFingerprint &&
                permission.knowledgeActivationGeneration ==
                sourceFact.knowledgeActivationGeneration
        }
    }

    @Transaction
    internal open suspend fun ingestCandidate(
        candidate: MasteryObservationCandidateEntity,
        attributions: List<MasteryCandidateAttributionEntity>,
        decisionTimeEpochMillis: Long,
        candidateOrigin: MasteryCandidateOrigin,
    ): LearningObservationIngestResult {
        check(candidate.candidateOrigin == candidateOrigin.name) {
            "Persisted mastery candidate origin differs from its authority path"
        }
        val pendingExactCandidate =
            findExistingCandidate(candidate)?.let { existing ->
                if (
                    existing.canonicalFingerprint != candidate.canonicalFingerprint ||
                    existing.idempotencyKey != candidate.idempotencyKey ||
                    existing.candidateOrigin != candidate.candidateOrigin
                ) {
                    return existingCandidateResult(candidate, existing)
                }
                if (findAdmissionReceipt(existing.candidateId) != null) {
                    return existingCandidateResult(candidate, existing)
                }
                existing
            }

        val sourceFact = findSourceFact(candidate.sourceFactId)
        val sourceProof = findSourceProof(candidate.sourceFactId)
        val authorizedBindingFingerprints =
            sourceFact
                ?.problemRevisionRefFingerprint
                ?.let { revisionFingerprint ->
                    findProblemBindingAuthorities(revisionFingerprint)
                        .asSequence()
                        .filter { authority ->
                            authority.learnerId == candidate.learnerId &&
                                authority.subject == candidate.subject
                        }.mapTo(
                            mutableSetOf(),
                            MasteryProblemBindingAuthorityEntity::bindingRefFingerprint,
                        )
                }.orEmpty()
        val decidedAt =
            maxOf(
                decisionTimeEpochMillis,
                candidate.proposedAtEpochMillis,
                sourceFact?.attestedAtEpochMillis ?: 0L,
            )
        val decision =
            LocalMasteryPolicy.evaluate(
                candidate = candidate,
                attributions = attributions,
                sourceFact = sourceFact,
                sourceProof = sourceProof,
                authorizedBindingFingerprints = authorizedBindingFingerprints,
            )
        if (
            decision is LocalMasteryAdmissionDecision.Admit &&
            validateReplayCalibrationBindings(candidate, decision) != null
        ) {
            return LearningObservationIngestResult(
                candidateId = candidate.candidateId,
                disposition = LearningObservationDisposition.CONFLICT,
                inertReason =
                    LearningObservationInertReason
                        .CALIBRATION_BINDING_CONFLICT,
            )
        }
        if (pendingExactCandidate == null) {
            if (insertCandidate(candidate) == INSERT_CONFLICT) {
                val existing =
                    findExistingCandidate(candidate)
                        ?: return LearningObservationIngestResult(
                            candidateId = candidate.candidateId,
                            disposition = LearningObservationDisposition.CONFLICT,
                            inertReason = LearningObservationInertReason.IDEMPOTENCY_CONFLICT,
                        )
                return existingCandidateResult(candidate, existing)
            }
            if (attributions.isNotEmpty()) {
                insertCandidateAttributions(attributions)
            }
        }
        check(findCandidateAttributions(candidate.candidateId) == attributions) {
            "Persisted mastery attributions differ from the evaluated semantic candidate"
        }
        return when (decision) {
            is LocalMasteryAdmissionDecision.KeepInert -> {
                val receipt =
                    inertReceipt(
                        candidate = candidate,
                        sourceProof = sourceProof,
                        reason = decision.reason,
                        decidedAtEpochMillis = decidedAt,
                    )
                insertAdmissionReceipt(
                    receipt,
                )
                LearningObservationIngestResult(
                    candidateId = candidate.candidateId,
                    disposition = LearningObservationDisposition.INERT,
                    inertReason = decision.reason,
                    terminalReceipt = receipt.toTerminalReceipt(),
                )
            }
            is LocalMasteryAdmissionDecision.Admit -> {
                checkNotNull(sourceFact)
                checkNotNull(sourceProof)
                val currentProjections =
                    decision.attributedMasses.mapNotNull { attributed ->
                        val attribution = attributed.attribution
                        findProjection(
                            learnerId = candidate.learnerId,
                            subject = attribution.subject,
                            knowledgeNodeId = attribution.knowledgeNodeId,
                            taxonomyVersion = attribution.taxonomyVersion,
                        )
                    }
                if (
                    LocalMasteryPolicy.weakNegativeConflictsWithStableMastery(
                        candidate = candidate,
                        sourceFact = sourceFact,
                        direction = decision.direction,
                        currentProjections = currentProjections,
                    )
                ) {
                    val reason =
                        LearningObservationInertReason.WEAK_CONFLICT_REQUIRES_REVIEW
                    if (
                        candidate.requestedPolicyVersion ==
                        LEARNER_MASTERY_PROJECTION_POLICY_VERSION
                    ) {
                        insertEvidenceReviewCase(
                            boundEvidenceReviewCase(
                                candidate = candidate,
                                sourceFact = sourceFact,
                                sourceProof = sourceProof,
                                reason = reason,
                                createdAtEpochMillis = decidedAt,
                            ),
                        )
                    }
                    val receipt =
                        inertReceipt(
                            candidate = candidate,
                            sourceProof = sourceProof,
                            reason = reason,
                            decidedAtEpochMillis = decidedAt,
                        )
                    insertAdmissionReceipt(receipt)
                    return LearningObservationIngestResult(
                        candidateId = candidate.candidateId,
                        disposition = LearningObservationDisposition.INERT,
                        inertReason = reason,
                        terminalReceipt = receipt.toTerminalReceipt(),
                    )
                }
                if (!directionalBudgetRebuildReady()) {
                    return LearningObservationIngestResult(
                        candidateId = candidate.candidateId,
                        disposition = LearningObservationDisposition.CONFLICT,
                        inertReason =
                            LearningObservationInertReason
                                .DIRECTIONAL_BUDGET_REBUILD_PENDING,
                    )
                }
                val cappedDecision =
                    capToPresentationBudget(
                        candidate = candidate,
                        sourceFact = sourceFact,
                        decision = decision,
                        decidedAtEpochMillis = decidedAt,
                    )
                if (cappedDecision.attributedMasses.isEmpty()) {
                    val reason =
                        LearningObservationInertReason
                            .PRESENTATION_EVIDENCE_BUDGET_EXHAUSTED
                    val receipt =
                        inertReceipt(
                            candidate = candidate,
                            sourceProof = sourceProof,
                            reason = reason,
                            decidedAtEpochMillis = decidedAt,
                        )
                    insertAdmissionReceipt(receipt)
                    LearningObservationIngestResult(
                        candidateId = candidate.candidateId,
                        disposition = LearningObservationDisposition.INERT,
                        inertReason = reason,
                        terminalReceipt = receipt.toTerminalReceipt(),
                    )
                } else {
                    admitAndProject(
                        candidate = candidate,
                        sourceFact = sourceFact,
                        sourceProof = sourceProof,
                        decision = cappedDecision,
                        decidedAtEpochMillis = decidedAt,
                    )
                    LearningObservationIngestResult(
                        candidateId = candidate.candidateId,
                        disposition = LearningObservationDisposition.ADMITTED,
                        terminalReceipt =
                            checkNotNull(findAdmissionReceipt(candidate.candidateId)) {
                                "Admitted mastery candidate is missing its terminal receipt"
                            }.toTerminalReceipt(),
                    )
                }
            }
        }
    }

    @Transaction
    internal open suspend fun resolveEvidenceReview(
        learnerId: String,
        command: ResolveLearningEvidenceReviewCommand,
    ): LearningEvidenceReviewWriteResult {
        val reviewCase =
            findEvidenceReviewCase(command.reviewCaseId)
                ?.takeIf { it.learnerId == learnerId }
                ?: return LearningEvidenceReviewWriteResult(
                    reviewCaseId = command.reviewCaseId,
                    disposition = LearningEvidenceReviewWriteDisposition.NOT_FOUND,
                )
        val snapshot =
            resolveEvidenceReviewCalibration(reviewCase)
                ?: return LearningEvidenceReviewWriteResult(
                    reviewCaseId = reviewCase.reviewCaseId,
                    disposition = LearningEvidenceReviewWriteDisposition.CONFLICT,
                )
        val candidate =
            checkNotNull(findCandidate(reviewCase.candidateId)) {
                "Evidence review candidate is missing"
            }
        val sourceFact =
            checkNotNull(findSourceFact(reviewCase.sourceFactId)) {
                "Evidence review source fact is missing"
            }
        val sourceProof =
            checkNotNull(findSourceProof(reviewCase.sourceFactId)) {
                "Evidence review source proof is missing"
            }
        check(candidate.learnerId == learnerId && sourceFact.learnerId == learnerId)
        check(candidate.subject == reviewCase.subject && sourceFact.subject == reviewCase.subject)
        val terminalReceipt =
            checkNotNull(findAdmissionReceipt(candidate.candidateId)) {
                "Evidence review candidate has no terminal admission receipt"
            }
        val reviewReason =
            runCatching {
                enumValueOf<LearningObservationInertReason>(reviewCase.reason)
            }.getOrNull()
        check(
            terminalReceipt.disposition == LearningObservationDisposition.INERT.name &&
                terminalReceipt.inertReason == reviewCase.reason &&
                reviewReason in
                setOf(
                    LearningObservationInertReason.WEAK_CONFLICT_REQUIRES_REVIEW,
                    LearningObservationInertReason.MODEL_ONLY_OPEN_RESPONSE,
                ),
        ) {
            "Only a supported quarantined evidence case may be reviewed"
        }
        val decidedAt =
            maxOf(
                command.decidedAtEpochMillis,
                reviewCase.createdAtEpochMillis,
                sourceFact.attestedAtEpochMillis,
            )
        val resolution =
            evidenceReviewResolution(
                reviewCase = reviewCase,
                command = command,
                calibrationSnapshot = snapshot,
                decidedAtEpochMillis = decidedAt,
            )
        findEvidenceReviewResolutionByCase(reviewCase.reviewCaseId)?.let { existing ->
            return existingReviewResolutionResult(resolution, existing)
        }
        findEvidenceReviewResolutionByIdempotency(command.idempotencyKey)?.let { existing ->
            return existingReviewResolutionResult(resolution, existing)
        }
        if (
            reviewReason == LearningObservationInertReason.MODEL_ONLY_OPEN_RESPONSE &&
            command.decision == LearningEvidenceReviewDecision.ACCEPT
        ) {
            return LearningEvidenceReviewWriteResult(
                reviewCaseId = reviewCase.reviewCaseId,
                disposition = LearningEvidenceReviewWriteDisposition.CONFLICT,
            )
        }
        if (command.decision == LearningEvidenceReviewDecision.REJECT) {
            if (insertEvidenceReviewResolution(resolution) == INSERT_CONFLICT) {
                val existing =
                    findEvidenceReviewResolutionByCase(reviewCase.reviewCaseId)
                        ?: findEvidenceReviewResolutionByIdempotency(command.idempotencyKey)
                        ?: return LearningEvidenceReviewWriteResult(
                            reviewCaseId = reviewCase.reviewCaseId,
                            disposition = LearningEvidenceReviewWriteDisposition.CONFLICT,
                        )
                return existingReviewResolutionResult(resolution, existing)
            }
            return LearningEvidenceReviewWriteResult(
                reviewCaseId = reviewCase.reviewCaseId,
                disposition = LearningEvidenceReviewWriteDisposition.APPLIED,
            )
        }

        if (!directionalBudgetRebuildReady()) {
            return LearningEvidenceReviewWriteResult(
                reviewCaseId = reviewCase.reviewCaseId,
                disposition = LearningEvidenceReviewWriteDisposition.REBUILD_PENDING,
            )
        }

        val attributions = findCandidateAttributions(candidate.candidateId)
        val authorizedBindingFingerprints =
            sourceFact.problemRevisionRefFingerprint?.let { revisionFingerprint ->
                findProblemBindingAuthorities(revisionFingerprint)
                    .asSequence()
                    .filter { authority ->
                        authority.learnerId == learnerId &&
                            authority.subject == candidate.subject
                    }.mapTo(
                        mutableSetOf(),
                        MasteryProblemBindingAuthorityEntity::bindingRefFingerprint,
                    )
            }.orEmpty()
        val decision =
            LocalMasteryPolicy.evaluate(
                candidate = candidate,
                attributions = attributions,
                sourceFact = sourceFact,
                sourceProof = sourceProof,
                authorizedBindingFingerprints = authorizedBindingFingerprints,
                calibrationSnapshot = snapshot,
            ) as? LocalMasteryAdmissionDecision.Admit
                ?: return LearningEvidenceReviewWriteResult(
                    reviewCaseId = reviewCase.reviewCaseId,
                    disposition = LearningEvidenceReviewWriteDisposition.CONFLICT,
                )
        if (validateReplayCalibrationBindings(candidate, decision) != null) {
            return LearningEvidenceReviewWriteResult(
                reviewCaseId = reviewCase.reviewCaseId,
                disposition = LearningEvidenceReviewWriteDisposition.CONFLICT,
            )
        }
        val dryRun =
            capToPresentationBudget(
                candidate = candidate,
                sourceFact = sourceFact,
                decision = decision,
                decidedAtEpochMillis = decidedAt,
                persistBudgets = false,
                calibrationSnapshot = snapshot,
            )
        if (dryRun.attributedMasses.isEmpty()) {
            return LearningEvidenceReviewWriteResult(
                reviewCaseId = reviewCase.reviewCaseId,
                disposition = LearningEvidenceReviewWriteDisposition.CONFLICT,
            )
        }
        if (insertEvidenceReviewResolution(resolution) == INSERT_CONFLICT) {
            val existing =
                findEvidenceReviewResolutionByCase(reviewCase.reviewCaseId)
                    ?: findEvidenceReviewResolutionByIdempotency(command.idempotencyKey)
                    ?: return LearningEvidenceReviewWriteResult(
                        reviewCaseId = reviewCase.reviewCaseId,
                        disposition = LearningEvidenceReviewWriteDisposition.CONFLICT,
                    )
            return existingReviewResolutionResult(resolution, existing)
        }
        val admitted =
            capToPresentationBudget(
                candidate = candidate,
                sourceFact = sourceFact,
                decision = decision,
                decidedAtEpochMillis = decidedAt,
                persistBudgets = true,
                calibrationSnapshot = snapshot,
            )
        check(admitted.attributedMasses == dryRun.attributedMasses) {
            "Evidence review budget changed inside one authority transaction"
        }
        admitAndProject(
            candidate = candidate,
            sourceFact = sourceFact,
            sourceProof = sourceProof,
            decision = admitted,
            decidedAtEpochMillis = decidedAt,
            reviewResolution = resolution,
            writeAdmissionReceipt = false,
            calibrationSnapshot = snapshot,
        )
        return LearningEvidenceReviewWriteResult(
            reviewCaseId = reviewCase.reviewCaseId,
            disposition = LearningEvidenceReviewWriteDisposition.APPLIED,
        )
    }

    @Transaction
    internal open suspend fun correctLearningEvidence(
        learnerId: String,
        command: CorrectLearningEvidenceCommand,
        replacementFact: MasterySourceFactEntity,
        replacementProof: MasterySourceProofEntity,
        replacementCandidate: MasteryObservationCandidateEntity,
        replacementAttributions: List<MasteryCandidateAttributionEntity>,
        decisionTimeEpochMillis: Long,
    ): LearningEvidenceCorrectionResult {
        findLearningEvidenceSupersessionByIdempotency(command.idempotencyKey)?.let { existing ->
            return existingCorrectionResult(
                learnerId = learnerId,
                command = command,
                replacementFact = replacementFact,
                replacementCandidate = replacementCandidate,
                existing = existing,
            )
        }
        val originalFact =
            findSourceFactByCanonicalFingerprint(
                command.originalSourceFactCanonicalFingerprint,
            ) ?: return rejectedCorrection(command)
        if (
            originalFact.learnerId != learnerId ||
            originalFact.subject != command.subject.name ||
            !isSameLearningObservation(originalFact, replacementFact)
        ) {
            return rejectedCorrection(command)
        }
        val originalEvent =
            findLearningEventBySourceFact(originalFact.sourceFactId)
                ?: return rejectedCorrection(command)
        if (
            originalEvent.learnerId != learnerId ||
            originalEvent.subject != command.subject.name ||
            originalEvent.canonicalFingerprint.isBlank() ||
            runCatching { enumValueOf<MasteryEventDirection>(originalEvent.direction) }.isFailure ||
            findLearningEvidenceSupersessionByOriginalEvent(originalEvent.eventId) != null
        ) {
            return rejectedCorrection(command)
        }
        if (
            replacementFact.learnerId != learnerId ||
            replacementCandidate.learnerId != learnerId ||
            replacementFact.subject != command.subject.name ||
            replacementCandidate.subject != command.subject.name ||
            replacementCandidate.sourceFactId != replacementFact.sourceFactId ||
            replacementAttributions.any {
                it.candidateId != replacementCandidate.candidateId ||
                    it.subject != command.subject.name
            }
        ) {
            return rejectedCorrection(command)
        }
        if (!directionalBudgetRebuildReady()) {
            return LearningEvidenceCorrectionResult(
                replacementObservationId = command.replacementObservation.observationId,
                replacementSourceFactCanonicalFingerprint = null,
                disposition = LearningEvidenceCorrectionDisposition.REBUILD_PENDING,
            )
        }
        val originalAttributions = findLearningEventAttributions(originalEvent.eventId)
        if (
            validateEventCalibrationBinding(originalEvent.calibrationBinding()) != null ||
            validateReplayCalibrationBindings(
                learnerId = learnerId,
                nodes =
                    originalAttributions.map { attribution ->
                        MasteryProjectionNodeKey(
                            subject = attribution.subject,
                            knowledgeNodeId = attribution.knowledgeNodeId,
                            taxonomyVersion = attribution.taxonomyVersion,
                        )
                    },
            ) != null
        ) {
            return rejectedCorrection(command)
        }

        val supersession =
            learningEvidenceSupersession(
                originalFact = originalFact,
                originalEvent = originalEvent,
                replacementFact = replacementFact,
                replacementCandidate = replacementCandidate,
                command = command,
            )
        val factWrite = ingestSourceFact(replacementFact, replacementProof)
        if (
            factWrite !is InternalSourceFactWriteResult.Stored ||
            !factWrite.policySupported ||
            insertLearningEvidenceSupersession(supersession) == INSERT_CONFLICT
        ) {
            throw LearningEvidenceCorrectionRejectedException()
        }

        markProjectionRebuildRequired()
        rebuildSupersededEventDerivedState(
            event = originalEvent,
            attributions = originalAttributions,
        )
        val candidateWrite =
            ingestCandidate(
                candidate = replacementCandidate,
                attributions = replacementAttributions,
                decisionTimeEpochMillis =
                    maxOf(
                        decisionTimeEpochMillis,
                        command.correctedAtEpochMillis,
                    ),
                candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
            )
        if (candidateWrite.disposition != LearningObservationDisposition.ADMITTED) {
            throw LearningEvidenceCorrectionRejectedException()
        }
        val replacementEvent =
            findLearningEventBySourceFact(replacementFact.sourceFactId)
                ?: throw LearningEvidenceCorrectionRejectedException()
        if (
            replacementEvent.eventId != supersession.replacementEventId ||
            replacementEvent.learnerId != learnerId ||
            replacementEvent.subject != command.subject.name
        ) {
            throw LearningEvidenceCorrectionRejectedException()
        }
        return LearningEvidenceCorrectionResult(
            replacementObservationId = command.replacementObservation.observationId,
            replacementSourceFactCanonicalFingerprint = replacementFact.canonicalFingerprint,
            disposition = LearningEvidenceCorrectionDisposition.APPLIED,
        )
    }

    private fun existingCorrectionResult(
        learnerId: String,
        command: CorrectLearningEvidenceCommand,
        replacementFact: MasterySourceFactEntity,
        replacementCandidate: MasteryObservationCandidateEntity,
        existing: MasteryLearningEvidenceSupersessionEntity,
    ): LearningEvidenceCorrectionResult {
        val exactRetry =
            existing.learnerId == learnerId &&
                existing.subject == command.subject.name &&
                existing.originalSourceFactCanonicalFingerprint ==
                command.originalSourceFactCanonicalFingerprint &&
                existing.replacementSourceFactId == replacementFact.sourceFactId &&
                existing.replacementSourceFactCanonicalFingerprint ==
                replacementFact.canonicalFingerprint &&
                existing.replacementCandidateId == replacementCandidate.candidateId &&
                existing.replacementCandidateCanonicalFingerprint ==
                replacementCandidate.canonicalFingerprint &&
                existing.authority == command.authority.name &&
                existing.authorityVersion == command.authorityVersion &&
                existing.correctionEvidenceFingerprint ==
                command.correctionEvidenceFingerprint &&
                existing.supersededAtEpochMillis == command.correctedAtEpochMillis
        return LearningEvidenceCorrectionResult(
            replacementObservationId = command.replacementObservation.observationId,
            replacementSourceFactCanonicalFingerprint =
                if (exactRetry) {
                    existing.replacementSourceFactCanonicalFingerprint
                } else {
                    null
                },
            disposition =
                if (exactRetry) {
                    LearningEvidenceCorrectionDisposition.DUPLICATE
                } else {
                    LearningEvidenceCorrectionDisposition.REJECTED
                },
        )
    }

    private fun rejectedCorrection(
        command: CorrectLearningEvidenceCommand,
    ): LearningEvidenceCorrectionResult =
        LearningEvidenceCorrectionResult(
            replacementObservationId = command.replacementObservation.observationId,
            replacementSourceFactCanonicalFingerprint = null,
            disposition = LearningEvidenceCorrectionDisposition.REJECTED,
        )

    private fun isSameLearningObservation(
        original: MasterySourceFactEntity,
        replacement: MasterySourceFactEntity,
    ): Boolean =
        original.sourceKind == replacement.sourceKind &&
            original.evidenceContextKind == replacement.evidenceContextKind &&
            original.problemRevisionRefFingerprint == replacement.problemRevisionRefFingerprint &&
            original.ephemeralProblemFingerprint == replacement.ephemeralProblemFingerprint &&
            original.tutorTurnReferenceId == replacement.tutorTurnReferenceId &&
            original.occurredAtEpochMillis == replacement.occurredAtEpochMillis &&
            (original.authorityPresentationFingerprint ?: original.presentationFingerprint) ==
            (replacement.authorityPresentationFingerprint ?: replacement.presentationFingerprint) &&
            (original.authorityProblemFamilyFingerprint ?: original.problemFamilyFingerprint) ==
            (
                replacement.authorityProblemFamilyFingerprint
                    ?: replacement.problemFamilyFingerprint
                )

    private suspend fun retireLearningEventsForRevision(
        learnerId: String,
        problemRevisionRefFingerprint: String,
        retiredAtEpochMillis: Long,
    ) {
        val sourceFacts =
            findSourceFactsByProblemRevision(problemRevisionRefFingerprint)
        sourceFacts.forEach { fact ->
            if (fact.learnerId != learnerId) return@forEach
            val event = findLearningEventBySourceFact(fact.sourceFactId) ?: return@forEach
            if (findLearningEvidenceSupersessionByOriginalEvent(event.eventId) != null) {
                return@forEach
            }
            val attributions = findLearningEventAttributions(event.eventId)
            if (attributions.isEmpty()) return@forEach
            val replacement =
                retiredReplacement(
                    originalFact = fact,
                    originalEvent = event,
                    retiredAtEpochMillis = retiredAtEpochMillis,
                )
            check(insertSourceFact(replacement.fact) != INSERT_CONFLICT) {
                "Retired replacement source fact collided"
            }
            insertSourceProof(replacement.proof)
            check(insertCandidate(replacement.candidate) != INSERT_CONFLICT) {
                "Retired replacement candidate collided"
            }
            insertLearningEvent(replacement.event)
            check(insertLearningEvidenceSupersession(replacement.supersession) != INSERT_CONFLICT) {
                "Retired replacement supersession collided"
            }
            rebuildSupersededEventDerivedState(event, attributions)
        }
        markProjectionRebuildRequired()
    }

    private data class RetiredReplacementBundle(
        val fact: MasterySourceFactEntity,
        val proof: MasterySourceProofEntity,
        val candidate: MasteryObservationCandidateEntity,
        val event: MasteryLearningEventEntity,
        val supersession: MasteryLearningEvidenceSupersessionEntity,
    )

    private fun retiredReplacement(
        originalFact: MasterySourceFactEntity,
        originalEvent: MasteryLearningEventEntity,
        retiredAtEpochMillis: Long,
    ): RetiredReplacementBundle {
        val identity =
            CanonicalSha256("learner-mastery-retired-replacement-identity-v1")
                .field("originalEventId", originalEvent.eventId)
                .field("retiredAtEpochMillis", retiredAtEpochMillis)
                .finish()
        val sourceFactId = "mretired:${identity.take(48)}"
        val sourceCanonicalFingerprint =
            CanonicalSha256("learner-mastery-retired-source-fact-v1")
                .field("identity", identity)
                .finish()
        val sourceReferenceId = "mastery-retired-source:$identity"
        val fact =
            originalFact.copy(
                sourceFactId = sourceFactId,
                sourceReferenceId = sourceReferenceId,
                presentationId = "retired:${identity.take(40)}",
                outcome = ObservedLearningOutcome.VIEWED_ONLY.name,
                assistance = ObservedAssistance.UNKNOWN.name,
                authority = MasteryEvidenceAuthority.LOCAL_VERIFIED.name,
                sourcePayloadFingerprint = sourceCanonicalFingerprint,
                occurredAtEpochMillis = retiredAtEpochMillis,
                attestedAtEpochMillis = retiredAtEpochMillis,
                receivedAtEpochMillis = retiredAtEpochMillis,
                sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
                idempotencyKey = "mastery-retired-source:$identity",
                canonicalFingerprint = sourceCanonicalFingerprint,
                presentationFingerprint = originalEvent.presentationFingerprint,
                independentlyAnswered = false,
                hintCount = 0,
                answerRevealed = true,
                elapsedDurationMillis = null,
                verificationKind = originalFact.verificationKind,
                evidenceContextKind = originalFact.evidenceContextKind,
                authorityAttemptFingerprint =
                    CanonicalSha256("learner-mastery-retired-attempt-v1")
                        .field("identity", identity)
                        .finish(),
                authoritySubmissionFingerprint =
                    CanonicalSha256("learner-mastery-retired-submission-v1")
                        .field("identity", identity)
                        .finish(),
                authorityPresentationFingerprint = originalEvent.presentationFingerprint,
                authorityProblemFamilyFingerprint = originalEvent.problemFamilyFingerprint,
                authorityIdentityVersion = LEARNER_MASTERY_AUTHORITY_IDENTITY_VERSION,
            )
        val proof =
            MasterySourceProofEntity(
                sourceFactId = sourceFactId,
                sourceFactCanonicalFingerprint = sourceCanonicalFingerprint,
                sourcePolicyVersion = fact.sourcePolicyVersion,
                policySupported = true,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                createdAtEpochMillis = retiredAtEpochMillis,
            )
        val candidateId = "mretired-candidate:${identity.take(40)}"
        val candidateCanonicalFingerprint =
            CanonicalSha256("learner-mastery-retired-candidate-v1")
                .field("identity", identity)
                .finish()
        val candidate =
            MasteryObservationCandidateEntity(
                candidateId = candidateId,
                learnerId = originalFact.learnerId,
                subject = originalFact.subject,
                sourceFactId = sourceFactId,
                confidence = MasteryCandidateConfidence.HIGH.name,
                modelVersion = LEARNER_MASTERY_RETIREMENT_POLICY_VERSION,
                requestedPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                proposedAtEpochMillis = retiredAtEpochMillis,
                receivedAtEpochMillis = retiredAtEpochMillis,
                idempotencyKey = "mastery-retired-candidate:$identity",
                canonicalFingerprint = candidateCanonicalFingerprint,
                candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL.name,
            )
        val eventId = LearnerMasteryFingerprint.eventId(candidate)
        val eventSequence = originalEvent.eventSequence + 1L
        val eventCanonicalFingerprint =
            LearnerMasteryFingerprint.event(
                eventId = eventId,
                candidate = candidate,
                sourceFact = fact,
                sourceProof = proof,
                direction = MasteryEventDirection.NEGATIVE,
                sequence = eventSequence,
                admittedAtEpochMillis = retiredAtEpochMillis,
                attributedMasses = emptyList(),
                projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                calibrationVersion = originalEvent.calibrationVersion,
                calibrationProfileId = originalEvent.calibrationProfileId,
                calibrationSnapshotFingerprint =
                    originalEvent.calibrationSnapshotFingerprint,
                reviewResolutionFingerprint = originalEvent.reviewResolutionFingerprint,
            )
        val event =
            MasteryLearningEventEntity(
                eventId = eventId,
                candidateId = candidateId,
                sourceFactId = sourceFactId,
                sourceProofFingerprint = proof.proofFingerprint,
                learnerId = originalFact.learnerId,
                subject = originalFact.subject,
                direction = MasteryEventDirection.NEGATIVE.name,
                eventSequence = eventSequence,
                occurredAtEpochMillis = retiredAtEpochMillis,
                admittedAtEpochMillis = retiredAtEpochMillis,
                projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
                calibrationVersion = originalEvent.calibrationVersion,
                canonicalFingerprint = eventCanonicalFingerprint,
                problemFamilyFingerprint = originalEvent.problemFamilyFingerprint,
                presentationFingerprint = originalEvent.presentationFingerprint,
                evidenceQualityMicros = 0L,
                independentlyAnswered = false,
                calibrationSnapshotFingerprint =
                    originalEvent.calibrationSnapshotFingerprint,
                calibrationProfileId = originalEvent.calibrationProfileId,
                reviewResolutionFingerprint = originalEvent.reviewResolutionFingerprint,
            )
        val supersession =
            retiredSupersession(
                originalFact = originalFact,
                originalEvent = originalEvent,
                replacementFact = fact,
                replacementCandidate = candidate,
                replacementEventId = eventId,
                identity = identity,
                retiredAtEpochMillis = retiredAtEpochMillis,
            )
        return RetiredReplacementBundle(
            fact = fact,
            proof = proof,
            candidate = candidate,
            event = event,
            supersession = supersession,
        )
    }

    private fun retiredSupersession(
        originalFact: MasterySourceFactEntity,
        originalEvent: MasteryLearningEventEntity,
        replacementFact: MasterySourceFactEntity,
        replacementCandidate: MasteryObservationCandidateEntity,
        replacementEventId: String,
        identity: String,
        retiredAtEpochMillis: Long,
    ): MasteryLearningEvidenceSupersessionEntity {
        val correctionEvidenceFingerprint =
            CanonicalSha256("learner-mastery-revision-retirement-evidence-v1")
                .field("identity", identity)
                .finish()
        val fingerprint =
            CanonicalSha256("learner-mastery-learning-evidence-supersession-v1")
                .field("learnerId", originalFact.learnerId)
                .field("subject", originalFact.subject)
                .field(
                    "originalSourceFactFingerprint",
                    originalFact.canonicalFingerprint,
                )
                .field(
                    "originalEventFingerprint",
                    originalEvent.canonicalFingerprint,
                )
                .field(
                    "replacementSourceFactFingerprint",
                    replacementFact.canonicalFingerprint,
                )
                .field(
                    "replacementCandidateFingerprint",
                    replacementCandidate.canonicalFingerprint,
                )
                .field("replacementEventId", replacementEventId)
                .field("authority", LearningEvidenceCorrectionAuthority.TRUSTED_LOCAL_RULE.name)
                .field("authorityVersion", LEARNER_MASTERY_RETIREMENT_POLICY_VERSION)
                .field("correctionEvidenceFingerprint", correctionEvidenceFingerprint)
                .field("supersededAtEpochMillis", retiredAtEpochMillis)
                .finish()
        return MasteryLearningEvidenceSupersessionEntity(
            supersessionId = "mles:${fingerprint.take(48)}",
            learnerId = originalFact.learnerId,
            subject = originalFact.subject,
            originalSourceFactId = originalFact.sourceFactId,
            originalSourceFactCanonicalFingerprint = originalFact.canonicalFingerprint,
            originalEventId = originalEvent.eventId,
            originalEventCanonicalFingerprint = originalEvent.canonicalFingerprint,
            replacementSourceFactId = replacementFact.sourceFactId,
            replacementSourceFactCanonicalFingerprint =
                replacementFact.canonicalFingerprint,
            replacementCandidateId = replacementCandidate.candidateId,
            replacementCandidateCanonicalFingerprint =
                replacementCandidate.canonicalFingerprint,
            replacementEventId = replacementEventId,
            authority = LearningEvidenceCorrectionAuthority.TRUSTED_LOCAL_RULE.name,
            authorityVersion = LEARNER_MASTERY_RETIREMENT_POLICY_VERSION,
            correctionEvidenceFingerprint = correctionEvidenceFingerprint,
            idempotencyKey = "mastery-retired-supersession:$identity",
            canonicalFingerprint = fingerprint,
            supersededAtEpochMillis = retiredAtEpochMillis,
        )
    }

    private suspend fun rebuildSupersededEventDerivedState(
        event: MasteryLearningEventEntity,
        attributions: List<MasteryLearningEventAttributionEntity>,
    ) {
        check(attributions.isNotEmpty()) {
            "Admitted learner-mastery event has no attributions"
        }
        attributions.forEach { attribution ->
            deleteProjection(
                learnerId = event.learnerId,
                subject = attribution.subject,
                knowledgeNodeId = attribution.knowledgeNodeId,
                taxonomyVersion = attribution.taxonomyVersion,
            )
            val activeHistory =
                readOrderedNodeHistory(
                    learnerId = event.learnerId,
                    subject = attribution.subject,
                    knowledgeNodeId = attribution.knowledgeNodeId,
                    taxonomyVersion = attribution.taxonomyVersion,
                )
            if (activeHistory.isNotEmpty()) {
                val replayed =
                    activeHistory.fold<
                        MasteryEventAttributionReplayRow,
                        MasteryKnowledgeProjectionEntity?,
                    >(null) { projection, row ->
                        LocalMasteryPolicy.nextProjection(
                            learnerId = event.learnerId,
                            event = row.event(),
                            attribution = row.attribution(),
                            current = projection,
                        )
                    } ?: error("Active learner-mastery replay produced no projection")
                upsertProjection(
                    replayed.withEvidenceDimensions(
                        readProjectionEvidenceDimensions(
                            learnerId = event.learnerId,
                            subject = attribution.subject,
                            knowledgeNodeId = attribution.knowledgeNodeId,
                            taxonomyVersion = attribution.taxonomyVersion,
                        ),
                    ),
                )
            }
            rebuildPresentationBudget(event, attribution)
            rebuildProblemFamilyBudget(event, attribution)
        }
    }

    private suspend fun rebuildPresentationBudget(
        event: MasteryLearningEventEntity,
        attribution: MasteryLearningEventAttributionEntity,
    ) {
        val direction = enumValueOf<MasteryEventDirection>(event.direction).name
        deletePresentationNodeBudget(
            learnerId = event.learnerId,
            presentationId = event.presentationFingerprint,
            subject = attribution.subject,
            knowledgeNodeId = attribution.knowledgeNodeId,
            taxonomyVersion = attribution.taxonomyVersion,
            direction = direction,
        )
        readActivePresentationBudget(
            learnerId = event.learnerId,
            presentationId = event.presentationFingerprint,
            subject = attribution.subject,
            knowledgeNodeId = attribution.knowledgeNodeId,
            taxonomyVersion = attribution.taxonomyVersion,
            direction = direction,
        )?.let { active ->
            val latestEventId =
                checkNotNull(
                    readLatestActivePresentationBudgetEventId(
                        learnerId = active.learnerId,
                        presentationId = active.presentationFingerprint,
                        subject = active.subject,
                        knowledgeNodeId = active.knowledgeNodeId,
                        taxonomyVersion = active.taxonomyVersion,
                        direction = active.direction,
                    ),
                ) { "Presentation budget aggregate has no latest immutable event" }
            upsertPresentationNodeBudget(
                MasteryPresentationNodeBudgetEntity(
                    learnerId = active.learnerId,
                    presentationId = active.presentationFingerprint,
                    subject = active.subject,
                    knowledgeNodeId = active.knowledgeNodeId,
                    taxonomyVersion = active.taxonomyVersion,
                    direction = active.direction,
                    stableNodeIdentityFingerprint =
                        MasteryProjectionIdentity.fingerprint(
                            active.subject,
                            active.knowledgeNodeId,
                            active.taxonomyVersion,
                    ),
                    consumedMassMicros = active.consumedMassMicros,
                    lastEventId = latestEventId,
                    updatedAtEpochMillis = active.updatedAtEpochMillis,
                ),
            )
        }
    }

    private suspend fun rebuildProblemFamilyBudget(
        event: MasteryLearningEventEntity,
        attribution: MasteryLearningEventAttributionEntity,
    ) {
        val problemFamily = event.problemFamilyFingerprint ?: return
        val direction = enumValueOf<MasteryEventDirection>(event.direction).name
        deleteProblemFamilyNodeBudget(
            learnerId = event.learnerId,
            problemFamilyFingerprint = problemFamily,
            subject = attribution.subject,
            knowledgeNodeId = attribution.knowledgeNodeId,
            taxonomyVersion = attribution.taxonomyVersion,
            direction = direction,
        )
        readActiveProblemFamilyBudget(
            learnerId = event.learnerId,
            problemFamilyFingerprint = problemFamily,
            subject = attribution.subject,
            knowledgeNodeId = attribution.knowledgeNodeId,
            taxonomyVersion = attribution.taxonomyVersion,
            direction = direction,
        )?.let { active ->
            val latestEventId =
                checkNotNull(
                    readLatestActiveProblemFamilyBudgetEventId(
                        learnerId = active.learnerId,
                        problemFamilyFingerprint = active.problemFamilyFingerprint,
                        subject = active.subject,
                        knowledgeNodeId = active.knowledgeNodeId,
                        taxonomyVersion = active.taxonomyVersion,
                        direction = active.direction,
                    ),
                ) { "Problem-family budget aggregate has no latest immutable event" }
            upsertProblemFamilyNodeBudget(
                MasteryProblemFamilyNodeBudgetEntity(
                    learnerId = active.learnerId,
                    problemFamilyFingerprint = active.problemFamilyFingerprint,
                    subject = active.subject,
                    knowledgeNodeId = active.knowledgeNodeId,
                    taxonomyVersion = active.taxonomyVersion,
                    direction = active.direction,
                    stableNodeIdentityFingerprint =
                        MasteryProjectionIdentity.fingerprint(
                            active.subject,
                            active.knowledgeNodeId,
                            active.taxonomyVersion,
                        ),
                    observationCount = active.observationCount,
                    consumedMassMicros = active.consumedMassMicros,
                    lastEventId = latestEventId,
                    updatedAtEpochMillis = active.updatedAtEpochMillis,
                ),
            )
        }
    }

    private fun existingReviewResolutionResult(
        incoming: MasteryEvidenceReviewResolutionEntity,
        existing: MasteryEvidenceReviewResolutionEntity,
    ): LearningEvidenceReviewWriteResult =
        LearningEvidenceReviewWriteResult(
            reviewCaseId = incoming.reviewCaseId,
            disposition =
                if (
                    existing.resolutionFingerprint == incoming.resolutionFingerprint &&
                    existing.idempotencyKey == incoming.idempotencyKey
                ) {
                    LearningEvidenceReviewWriteDisposition.DUPLICATE
                } else {
                    LearningEvidenceReviewWriteDisposition.CONFLICT
                },
        )

    private suspend fun findExistingCandidate(
        candidate: MasteryObservationCandidateEntity,
    ): MasteryObservationCandidateEntity? =
        findCandidate(candidate.candidateId)
            ?: findCandidateByIdempotency(candidate.idempotencyKey)
            ?: findCandidateBySourceFact(candidate.sourceFactId)

    private suspend fun existingCandidateResult(
        candidate: MasteryObservationCandidateEntity,
        existing: MasteryObservationCandidateEntity,
    ): LearningObservationIngestResult {
        return if (
            existing.canonicalFingerprint == candidate.canonicalFingerprint &&
            existing.idempotencyKey == candidate.idempotencyKey &&
            existing.candidateOrigin == candidate.candidateOrigin
        ) {
            val receipt =
                findAdmissionReceipt(existing.candidateId)
                    ?: return LearningObservationIngestResult(
                        candidateId = existing.candidateId,
                        disposition = LearningObservationDisposition.CONFLICT,
                        inertReason =
                            LearningObservationInertReason
                                .DIRECTIONAL_BUDGET_REBUILD_PENDING,
                    )
            LearningObservationIngestResult(
                candidateId = existing.candidateId,
                disposition = LearningObservationDisposition.DUPLICATE,
                terminalReceipt = receipt.toTerminalReceipt(),
            )
        } else {
            LearningObservationIngestResult(
                candidateId = candidate.candidateId,
                disposition = LearningObservationDisposition.CONFLICT,
                inertReason = LearningObservationInertReason.IDEMPOTENCY_CONFLICT,
            )
        }
    }

    private suspend fun persistModelSubmissionAttemptReceipt(
        attempt: ModelSubmissionAttempt,
        terminalReason: ModelSubmissionTerminalReason,
        candidateId: String?,
        admissionReceiptFingerprint: String?,
        receivedAtEpochMillis: Long,
        primary: Boolean,
    ): ModelSubmissionAttemptReceipt {
        val entity =
            modelSubmissionAttemptReceipt(
                attempt = attempt,
                terminalReason = terminalReason,
                candidateId = candidateId,
                admissionReceiptFingerprint = admissionReceiptFingerprint,
                receivedAtEpochMillis = receivedAtEpochMillis,
                primary = primary,
            )
        insertModelSubmissionAttemptReceipt(entity)
        return ModelSubmissionAttemptReceipt(entity.receiptFingerprint)
    }

    private suspend fun boundEvidenceReviewCase(
        candidate: MasteryObservationCandidateEntity,
        sourceFact: MasterySourceFactEntity,
        sourceProof: MasterySourceProofEntity,
        reason: LearningObservationInertReason,
        createdAtEpochMillis: Long,
    ): MasteryEvidenceReviewCaseEntity {
        check(candidate.requestedPolicyVersion == LEARNER_MASTERY_PROJECTION_POLICY_VERSION) {
            "Only calibrated mastery policy events may enter evidence review"
        }
        val snapshot =
            LocalMasteryCalibrationRegistry.current(candidate.subject).also { current ->
                ensureCalibrationSnapshot(current)
            }
        return evidenceReviewCase(
            candidate = candidate,
            sourceFact = sourceFact,
            sourceProof = sourceProof,
            reason = reason,
            calibrationSnapshot = snapshot,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    private suspend fun resolveEvidenceReviewCalibration(
        reviewCase: MasteryEvidenceReviewCaseEntity,
    ): MasteryCalibrationSnapshotEntity? {
        if (
            reviewCase.calibrationBindingStatus !=
            MasteryCalibrationBindingStatus.BOUND.name
        ) {
            return null
        }
        val calibrationVersion = reviewCase.calibrationVersion ?: return null
        val profileId = reviewCase.calibrationProfileId ?: return null
        val snapshotFingerprint =
            reviewCase.calibrationSnapshotFingerprint ?: return null
        val snapshot =
            runCatching {
                LocalMasteryCalibrationRegistry.resolve(
                    subject = reviewCase.subject,
                    calibrationVersion = calibrationVersion,
                    profileId = profileId,
                    snapshotFingerprint = snapshotFingerprint,
                )
            }.getOrNull() ?: return null
        if (snapshot.profileId != profileId) {
            return null
        }
        return runCatching {
            ensureCalibrationSnapshot(snapshot)
            snapshot
        }.getOrNull()
    }

    private suspend fun capToPresentationBudget(
        candidate: MasteryObservationCandidateEntity,
        sourceFact: MasterySourceFactEntity,
        decision: LocalMasteryAdmissionDecision.Admit,
        decidedAtEpochMillis: Long,
        persistBudgets: Boolean = true,
        calibrationSnapshot: MasteryCalibrationSnapshotEntity? = null,
    ): LocalMasteryAdmissionDecision.Admit {
        val eventId = LearnerMasteryFingerprint.eventId(candidate)
        val calibrationParameters =
            if (candidate.requestedPolicyVersion == LEARNER_MASTERY_PROJECTION_POLICY_VERSION) {
                val snapshot =
                    calibrationSnapshot
                        ?: LocalMasteryCalibrationRegistry.current(candidate.subject)
                check(snapshot.subject == candidate.subject)
                check(snapshot.projectionPolicyVersion == candidate.requestedPolicyVersion)
                LocalMasteryCalibrationRegistry.verify(snapshot)
                LocalMasteryCalibrationRegistry.parameters(
                    snapshot,
                )
            } else {
                check(calibrationSnapshot == null) {
                    "Legacy mastery policies cannot claim a calibrated snapshot"
                }
                null
            }
        val authoritativePresentation =
            sourceFact.authorityPresentationFingerprint ?: sourceFact.presentationFingerprint
        val authoritativeProblemFamily =
            sourceFact.authorityProblemFamilyFingerprint
                ?: sourceFact.problemFamilyFingerprint
        val direction = decision.direction.name
        val capped =
            decision.attributedMasses.mapNotNull { attributed ->
                val attribution = attributed.attribution
                val presentationBudget =
                    findPresentationNodeBudget(
                        learnerId = candidate.learnerId,
                        presentationId = authoritativePresentation,
                        subject = attribution.subject,
                        knowledgeNodeId = attribution.knowledgeNodeId,
                        taxonomyVersion = attribution.taxonomyVersion,
                        direction = direction,
                    )
                val presentationConsumed = presentationBudget?.consumedMassMicros ?: 0L
                val presentationAdmitted =
                    LocalPresentationEvidenceBudget.admittedMass(
                        alreadyConsumedMicros = presentationConsumed,
                        requestedMassMicros = attributed.evidenceMassMicros,
                        maximumMassMicros =
                            calibrationParameters?.presentationMassCapMicros
                                ?: LocalMasteryPolicy.FULL_MASS_MICROS,
                    )
                val problemFamilyBudget =
                    authoritativeProblemFamily?.let { familyFingerprint ->
                        findProblemFamilyNodeBudget(
                            learnerId = candidate.learnerId,
                            problemFamilyFingerprint = familyFingerprint,
                            subject = attribution.subject,
                            knowledgeNodeId = attribution.knowledgeNodeId,
                            taxonomyVersion = attribution.taxonomyVersion,
                            direction = direction,
                        )
                    }
                val admitted =
                    if (authoritativeProblemFamily == null) {
                        presentationAdmitted
                    } else {
                        LocalProblemFamilyEvidenceBudget.admittedMass(
                            priorObservationCount =
                                problemFamilyBudget?.observationCount ?: 0L,
                            alreadyConsumedMicros =
                                problemFamilyBudget?.consumedMassMicros ?: 0L,
                            requestedMassMicros = presentationAdmitted,
                            maximumMassMicros =
                                calibrationParameters?.problemFamilyMassCapMicros
                                    ?: LocalProblemFamilyEvidenceBudget.MAX_FAMILY_MASS_MICROS,
                            secondObservationScaleMicros =
                                calibrationParameters?.secondFamilyObservationScaleMicros
                                    ?: 500_000L,
                            repeatedObservationScaleMicros =
                                calibrationParameters?.repeatedFamilyObservationScaleMicros
                                    ?: 250_000L,
                        )
                    }
                if (admitted == 0L) {
                    null
                } else {
                    if (persistBudgets) {
                        val latestPresentationEvent =
                            latestBudgetEvent(
                                existingEventId = presentationBudget?.lastEventId,
                                existingUpdatedAtEpochMillis =
                                    presentationBudget?.updatedAtEpochMillis,
                                candidateEventId = eventId,
                                candidateUpdatedAtEpochMillis = decidedAtEpochMillis,
                            )
                        upsertPresentationNodeBudget(
                            MasteryPresentationNodeBudgetEntity(
                                learnerId = candidate.learnerId,
                                presentationId = authoritativePresentation,
                                subject = attribution.subject,
                                knowledgeNodeId = attribution.knowledgeNodeId,
                                taxonomyVersion = attribution.taxonomyVersion,
                                direction = direction,
                                stableNodeIdentityFingerprint =
                                    MasteryProjectionIdentity.fingerprint(
                                        subject = attribution.subject,
                                        knowledgeNodeId = attribution.knowledgeNodeId,
                                        taxonomyVersion = attribution.taxonomyVersion,
                                    ),
                                consumedMassMicros = presentationConsumed + admitted,
                                lastEventId = latestPresentationEvent.eventId,
                                updatedAtEpochMillis =
                                    latestPresentationEvent.updatedAtEpochMillis,
                            ),
                        )
                        authoritativeProblemFamily?.let { familyFingerprint ->
                            val latestProblemFamilyEvent =
                                latestBudgetEvent(
                                    existingEventId = problemFamilyBudget?.lastEventId,
                                    existingUpdatedAtEpochMillis =
                                        problemFamilyBudget?.updatedAtEpochMillis,
                                    candidateEventId = eventId,
                                    candidateUpdatedAtEpochMillis = decidedAtEpochMillis,
                                )
                            upsertProblemFamilyNodeBudget(
                                MasteryProblemFamilyNodeBudgetEntity(
                                    learnerId = candidate.learnerId,
                                    problemFamilyFingerprint = familyFingerprint,
                                    subject = attribution.subject,
                                    knowledgeNodeId = attribution.knowledgeNodeId,
                                    taxonomyVersion = attribution.taxonomyVersion,
                                    direction = direction,
                                    stableNodeIdentityFingerprint =
                                        MasteryProjectionIdentity.fingerprint(
                                            subject = attribution.subject,
                                            knowledgeNodeId = attribution.knowledgeNodeId,
                                            taxonomyVersion = attribution.taxonomyVersion,
                                        ),
                                    observationCount =
                                        (problemFamilyBudget?.observationCount ?: 0L) + 1L,
                                    consumedMassMicros =
                                        (problemFamilyBudget?.consumedMassMicros ?: 0L) + admitted,
                                    lastEventId = latestProblemFamilyEvent.eventId,
                                    updatedAtEpochMillis =
                                        latestProblemFamilyEvent.updatedAtEpochMillis,
                                ),
                            )
                        }
                    }
                    attributed.copy(evidenceMassMicros = admitted)
                }
            }
        return decision.copy(attributedMasses = capped)
    }

    private suspend fun admitAndProject(
        candidate: MasteryObservationCandidateEntity,
        sourceFact: MasterySourceFactEntity,
        sourceProof: MasterySourceProofEntity,
        decision: LocalMasteryAdmissionDecision.Admit,
        decidedAtEpochMillis: Long,
        reviewResolution: MasteryEvidenceReviewResolutionEntity? = null,
        writeAdmissionReceipt: Boolean = true,
        calibrationSnapshot: MasteryCalibrationSnapshotEntity? = null,
        eventIdOverride: String? = null,
        evidenceQualityMicrosOverride: Long? = null,
        independentlyAnsweredOverride: Boolean? = null,
    ) {
        val sequence = allocateSequence(candidate.learnerId)
        val eventId = eventIdOverride ?: LearnerMasteryFingerprint.eventId(candidate)
        val calibration =
            if (candidate.requestedPolicyVersion == LEARNER_MASTERY_PROJECTION_POLICY_VERSION) {
                (
                    calibrationSnapshot
                        ?: LocalMasteryCalibrationRegistry.current(candidate.subject)
                    ).also { snapshot ->
                    check(snapshot.subject == candidate.subject)
                    check(snapshot.projectionPolicyVersion == candidate.requestedPolicyVersion)
                    LocalMasteryCalibrationRegistry.verify(snapshot)
                    ensureCalibrationSnapshot(snapshot)
                }
            } else {
                check(calibrationSnapshot == null) {
                    "Legacy mastery policies cannot claim a calibrated snapshot"
                }
                null
            }
        val eventCalibrationVersion =
            calibration?.calibrationVersion ?: LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION
        val eventFingerprint =
            LearnerMasteryFingerprint.event(
                eventId = eventId,
                candidate = candidate,
                sourceFact = sourceFact,
                sourceProof = sourceProof,
                direction = decision.direction,
                sequence = sequence,
                admittedAtEpochMillis = decidedAtEpochMillis,
                attributedMasses = decision.attributedMasses,
                projectionPolicyVersion = candidate.requestedPolicyVersion,
                calibrationVersion = eventCalibrationVersion,
                calibrationProfileId = calibration?.profileId,
                calibrationSnapshotFingerprint = calibration?.snapshotFingerprint,
                reviewResolutionFingerprint = reviewResolution?.resolutionFingerprint,
            )
        val event =
            MasteryLearningEventEntity(
                eventId = eventId,
                candidateId = candidate.candidateId,
                sourceFactId = sourceFact.sourceFactId,
                sourceProofFingerprint = sourceProof.proofFingerprint,
                learnerId = candidate.learnerId,
                subject = candidate.subject,
                direction = decision.direction.name,
                eventSequence = sequence,
                occurredAtEpochMillis = sourceFact.occurredAtEpochMillis,
                admittedAtEpochMillis = decidedAtEpochMillis,
                projectionPolicyVersion = candidate.requestedPolicyVersion,
                admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
                calibrationVersion = eventCalibrationVersion,
                canonicalFingerprint = eventFingerprint,
                problemFamilyFingerprint =
                    sourceFact.authorityProblemFamilyFingerprint
                        ?: sourceFact.problemFamilyFingerprint,
                presentationFingerprint =
                    sourceFact.authorityPresentationFingerprint
                        ?: sourceFact.presentationFingerprint,
                evidenceQualityMicros =
                    evidenceQualityMicrosOverride
                        ?: LocalMasteryPolicy.evidenceQualityMicros(sourceFact),
                independentlyAnswered =
                    independentlyAnsweredOverride
                        ?: LocalMasteryPolicy.isIndependentMasteryEvidence(sourceFact),
                calibrationSnapshotFingerprint = calibration?.snapshotFingerprint,
                calibrationProfileId = calibration?.profileId,
                reviewResolutionFingerprint = reviewResolution?.resolutionFingerprint,
            )
        val eventAttributions =
            decision.attributedMasses.mapIndexed { index, attributed ->
                MasteryLearningEventAttributionEntity(
                    eventId = eventId,
                    ordinal = index,
                    subject = attributed.attribution.subject,
                    knowledgeNodeId = attributed.attribution.knowledgeNodeId,
                    taxonomyVersion = attributed.attribution.taxonomyVersion,
                    knowledgePackVersion = attributed.attribution.knowledgePackVersion,
                    knowledgeNodeRefFingerprint =
                        attributed.attribution.knowledgeNodeRefFingerprint,
                    evidenceMassMicros = attributed.evidenceMassMicros,
                )
            }
        insertLearningEvent(event)
        insertLearningEventAttributions(eventAttributions)
        if (writeAdmissionReceipt) {
            insertAdmissionReceipt(
                admittedReceipt(
                    candidate = candidate,
                    sourceProof = sourceProof,
                    eventId = eventId,
                    decidedAtEpochMillis = decidedAtEpochMillis,
                ),
            )
        }

        val projectionFingerprints = mutableListOf<String>()
        eventAttributions.forEach { attribution ->
            val current =
                findProjection(
                    learnerId = candidate.learnerId,
                    subject = attribution.subject,
                    knowledgeNodeId = attribution.knowledgeNodeId,
                    taxonomyVersion = attribution.taxonomyVersion,
                )
            val projected =
                if (
                    current != null &&
                    (
                        event.isBeforeProjectionTail(current) ||
                            current.projectionPolicyVersion !=
                            event.projectionPolicyVersion
                        )
                ) {
                    replayProjection(
                        learnerId = candidate.learnerId,
                        subject = attribution.subject,
                        knowledgeNodeId = attribution.knowledgeNodeId,
                        taxonomyVersion = attribution.taxonomyVersion,
                    )
                } else {
                    LocalMasteryPolicy.nextProjection(
                        learnerId = candidate.learnerId,
                        event = event,
                        attribution = attribution,
                        current = current,
                    )
                }
            val updated =
                projected.withEvidenceDimensions(
                    readProjectionEvidenceDimensions(
                        learnerId = candidate.learnerId,
                        subject = attribution.subject,
                        knowledgeNodeId = attribution.knowledgeNodeId,
                        taxonomyVersion = attribution.taxonomyVersion,
                    ),
                )
            upsertProjection(updated)
            projectionFingerprints += projectionFingerprint(updated)
        }
        val applicationFingerprint =
            applicationFingerprint(event, projectionFingerprints)
        insertAppliedEvent(
            MasteryAppliedEventEntity(
                eventId = event.eventId,
                eventCanonicalFingerprint = event.canonicalFingerprint,
                learnerId = event.learnerId,
                eventSequence = event.eventSequence,
                projectionPolicyVersion = event.projectionPolicyVersion,
                applicationFingerprint = applicationFingerprint,
                appliedAtEpochMillis = decidedAtEpochMillis,
            ),
        )
        refreshSubjectDigest(event, decidedAtEpochMillis)
        outboxFor(
            event = event,
            sourceFact = sourceFact,
            createdAtEpochMillis = decidedAtEpochMillis,
            storeGeneration = ensureStoreGeneration(),
        )?.let { insertOutbox(it) }
    }

    private suspend fun ensureCalibrationSnapshot(
        snapshot: MasteryCalibrationSnapshotEntity,
    ) {
        LocalMasteryCalibrationRegistry.verify(snapshot)
        insertCalibrationSnapshot(snapshot)
        val persisted =
            checkNotNull(
                findCalibrationSnapshot(
                    subject = snapshot.subject,
                    calibrationVersion = snapshot.calibrationVersion,
                    profileId = snapshot.profileId,
                    snapshotFingerprint = snapshot.snapshotFingerprint,
                ),
            ) {
                "Learner-mastery calibration snapshot was not persisted"
            }
        check(persisted == snapshot) {
            "Learner-mastery calibration snapshot identity conflicts"
        }
    }

    private suspend fun replayProjection(
        learnerId: String,
        subject: String,
        knowledgeNodeId: String,
        taxonomyVersion: String,
    ): MasteryKnowledgeProjectionEntity {
        val orderedHistory =
            readOrderedNodeHistory(
                learnerId = learnerId,
                subject = subject,
                knowledgeNodeId = knowledgeNodeId,
                taxonomyVersion = taxonomyVersion,
            )
        check(orderedHistory.isNotEmpty()) {
            "Late learner-mastery event has no replayable node history"
        }
        return orderedHistory.fold<MasteryEventAttributionReplayRow, MasteryKnowledgeProjectionEntity?>(
            null,
        ) { projection, row ->
            LocalMasteryPolicy.nextProjection(
                learnerId = learnerId,
                event = row.event(),
                attribution = row.attribution(),
                current = projection,
            )
        } ?: error("Learner-mastery node replay produced no projection")
    }

    private suspend fun refreshSubjectDigest(
        event: MasteryLearningEventEntity,
        decidedAtEpochMillis: Long,
    ) {
        val counts = countMasteryBands(event.learnerId, event.subject)
        upsertSubjectDigest(
            MasterySubjectDigestEntity(
                learnerId = event.learnerId,
                subject = event.subject,
                needsReinforcementCount =
                    (counts.needsReinforcementCount ?: 0L).toBoundedInt(),
                familiarizingCount =
                    (counts.familiarizingCount ?: 0L).toBoundedInt(),
                steadyCount = (counts.steadyCount ?: 0L).toBoundedInt(),
                lastEventSequence = event.eventSequence,
                updatedAtEpochMillis = decidedAtEpochMillis,
                projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            ),
        )
    }

    private suspend fun allocateSequence(learnerId: String): Long {
        initializeSequence(
            MasteryLedgerSequenceEntity(
                learnerId = learnerId,
                lastAllocatedSequence = 0L,
            ),
        )
        check(incrementSequence(learnerId) == 1) {
            "Could not allocate a learner-mastery event sequence"
        }
        return checkNotNull(readSequence(learnerId)) {
            "Learner-mastery sequence disappeared during allocation"
        }
    }

    private suspend fun ensureStoreGeneration(): String {
        readMetadata(STORE_GENERATION_METADATA_KEY)?.let { return it }
        insertMetadata(
            MasteryStoreMetadataEntity(
                metadataKey = STORE_GENERATION_METADATA_KEY,
                metadataValue = UUID.randomUUID().toString(),
            ),
        )
        return checkNotNull(readMetadata(STORE_GENERATION_METADATA_KEY)) {
            "Learner-mastery store generation was not persisted"
        }
    }

    internal open suspend fun markProjectionRebuildRequired() {
        val requiredMarker =
            MasteryStoreMetadataEntity(
                metadataKey = PROJECTION_REBUILD_METADATA_KEY,
                metadataValue = PROJECTION_REBUILD_REQUIRED_V2,
            )
        check(
            insertMetadata(requiredMarker) != INSERT_CONFLICT ||
                readMetadata(PROJECTION_REBUILD_METADATA_KEY) ==
                PROJECTION_REBUILD_REQUIRED_V2,
        ) {
            "Learner-mastery projection rebuild marker conflicts"
        }
    }

    private companion object {
        const val INSERT_CONFLICT = -1L
        const val STORE_GENERATION_METADATA_KEY = "store_generation"
        const val PROJECTION_REBUILD_LEASE_MILLIS = 60_000L
        const val PROJECTION_REBUILD_STREAM_PROTOCOL =
            "event-sequence-subject-history-stream-v2"
        const val PREVIOUS_ACTIVE_PROJECTION_VERSION = "previous-active-projection"
        const val PREVIOUS_ACTIVE_CALIBRATION_VERSION = "previous-active-calibration"
    }
}
