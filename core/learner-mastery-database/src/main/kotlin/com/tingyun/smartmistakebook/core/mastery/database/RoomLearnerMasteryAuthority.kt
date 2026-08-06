package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RoomLearnerMasteryAuthority(
    private val store: LearnerMasteryStore,
    override val learnerId: String,
    private val nowEpochMillis: () -> Long,
    private val runtimeBindingId: String = "mastery-authority:${UUID.randomUUID()}",
    private val knowledgeReferenceVerifier: KnowledgeReferenceProofVerifier =
        KnowledgeReferenceProofAuthority.create().verifier,
    internal val outboxAuthenticityVerifier: MasteryOutboxAuthenticityVerifier? = null,
) : LearnerMasteryAuthority {
    private val closed = AtomicBoolean(false)
    private val mutationMutex = LearnerMasteryMutationLocks.forLearner(learnerId)
    private val observationIssuerSeal = Any()
    override val displayReader: LearnerMasteryDisplayReader =
        object : LearnerMasteryDisplayReader {
            override fun observeRevision(): Flow<LearnerMasteryDisplayRevision> {
                ensureOpen()
                return store.observeDisplayRevision(
                    BoundLearnerMasteryDisplayQuery(learnerId),
                )
            }

            override suspend fun readOverview(
                expectedRevision: LearnerMasteryDisplayRevision,
            ): LearnerMasteryDisplayOverviewResult {
                ensureOpen()
                return store.readDisplayOverview(
                    BoundLearnerMasteryDisplayQuery(learnerId),
                    expectedRevision,
                )
            }

            override suspend fun readKnowledgePage(
                request: LearnerMasteryDisplayPageRequest,
            ): LearnerMasteryDisplayPageResult {
                ensureOpen()
                return store.readDisplayKnowledgePage(
                    BoundLearnerMasteryDisplayPageQuery(
                        learnerId = learnerId,
                        request = request,
                    ),
                )
            }

            override suspend fun readSubjectTimeline(
                request: LearnerMasteryDisplayTimelineRequest,
            ): LearnerMasteryDisplayTimelineResult {
                ensureOpen()
                return store.readDisplaySubjectTimeline(
                    BoundLearnerMasteryDisplayTimelineQuery(
                        learnerId = learnerId,
                        request = request,
                    ),
                )
            }
        }
    override val localContextReader: LocalMasteryContextReader =
        object : LocalMasteryContextReader {
            override suspend fun queryContext(
                request: LocalMasteryContextRequest,
            ): LocalMasteryContext {
                ensureOpen()
                return store.queryLocalMasteryContext(
                    BoundLocalMasteryContextQuery(
                        learnerId = learnerId,
                        request = request,
                    ),
                )
            }
        }
    override val evidenceReviewCapability: LearnerMasteryEvidenceReviewCapability =
        object : LearnerMasteryEvidenceReviewCapability {
            override suspend fun readPending(
                subject: SubjectKind,
                limit: Int,
            ): List<PendingLearningEvidenceReview> =
                mutationMutex.withLock {
                    ensureOpen()
                    store.readPendingEvidenceReviews(
                        learnerId = learnerId,
                        subject = subject,
                        limit = limit,
                    )
                }

            override suspend fun resolve(
                command: ResolveLearningEvidenceReviewCommand,
            ): LearningEvidenceReviewWriteResult =
                mutationMutex.withLock {
                    ensureOpen()
                    val now = nowEpochMillis()
                    require(now >= 0L)
                    val latestAccepted =
                        if (now > Long.MAX_VALUE - MAX_OBSERVATION_FUTURE_SKEW_MILLIS) {
                            Long.MAX_VALUE
                        } else {
                            now + MAX_OBSERVATION_FUTURE_SKEW_MILLIS
                        }
                    require(command.decidedAtEpochMillis <= latestAccepted) {
                        "Evidence review decision is too far ahead of the trusted local clock"
                    }
                    store.resolveEvidenceReview(
                        learnerId = learnerId,
                        command = command,
                    )
                }
        }
    override val evidenceCorrectionCapability: LearnerMasteryEvidenceCorrectionCapability =
        LearnerMasteryEvidenceCorrectionCapability { correction ->
            mutationMutex.withLock {
                ensureOpen()
                val replacement = issueObservation(correction.replacementObservation)
                replacement.requireIssuedBy(observationIssuerSeal)
                val facts = replacement.facts
                facts.requireRuntimeScope(learnerId, runtimeBindingId)
                val now = nowEpochMillis()
                require(now >= 0L)
                val latestAccepted =
                    if (now > Long.MAX_VALUE - MAX_OBSERVATION_FUTURE_SKEW_MILLIS) {
                        Long.MAX_VALUE
                    } else {
                        now + MAX_OBSERVATION_FUTURE_SKEW_MILLIS
                    }
                require(
                    correction.correctedAtEpochMillis <= latestAccepted &&
                        facts.attestedAtEpochMillis <= latestAccepted,
                ) {
                    "Evidence correction is too far ahead of the trusted local clock"
                }
                val saved = facts.context as? SavedMistakeLearningContext
                if (saved != null) {
                    check(store.isProblemRevisionAuthorized(saved.problemRevision)) {
                        "Corrected saved-mistake revision has not been authorized"
                    }
                    if (saved.knowledgeEvidenceBindings.isNotEmpty()) {
                        check(
                            store.areProblemKnowledgeBindingsAuthorized(
                                problemRevisionCanonicalFingerprint =
                                    saved.problemRevision.canonicalFingerprint,
                                bindingCanonicalFingerprints =
                                    saved.knowledgeEvidenceBindings
                                        .mapTo(mutableSetOf()) { it.canonicalFingerprint },
                            ),
                        ) {
                            "Corrected saved-mistake knowledge evidence has not been authorized"
                        }
                    }
                }
                store.correctLearningEvidence(
                    learnerId = learnerId,
                    command = correction,
                    replacementSourceFact =
                        prepareTrustedSourceFact(
                            learnerId = learnerId,
                            runtimeBindingId = runtimeBindingId,
                            command = facts,
                        ),
                    replacementCandidate =
                        prepareTrustedObservationCandidate(
                            learnerId = learnerId,
                            command = facts,
                        ),
                )
            }
        }
    override val relay: LearnerMasteryRelayCapability =
        object : LearnerMasteryRelayCapability {
            override val learnerId: String = this@RoomLearnerMasteryAuthority.learnerId

            override suspend fun accept(
                message: VerifiedStudentMistakeDelivery,
                receivedAtEpochMillis: Long,
            ): LearnerMasteryInboundDisposition =
                mutationMutex.withLock {
                    ensureOpen()
                    require(receivedAtEpochMillis >= 0L) {
                        "Relay receipt time must not be negative"
                    }
                    when (applyVerifiedStudentDelivery(message, receivedAtEpochMillis)) {
                        MasteryInboundDisposition.APPLIED ->
                            LearnerMasteryInboundDisposition.APPLIED
                        MasteryInboundDisposition.DUPLICATE ->
                            LearnerMasteryInboundDisposition.DUPLICATE
                        MasteryInboundDisposition.CONFLICT ->
                            LearnerMasteryInboundDisposition.CONFLICT
                    }
                }

            override suspend fun readPending(
                nowEpochMillis: Long,
                limit: Int,
            ): List<LearnerMasteryOutboxDelivery> =
                mutationMutex.withLock {
                    ensureOpen()
                    require(nowEpochMillis >= 0L) {
                        "Relay read time must not be negative"
                    }
                    require(limit in 1..LearnerMasteryRelayCapability.MAX_RELAY_BATCH_SIZE) {
                        "Relay batch size is outside the supported range"
                    }
                    store.readPendingMessages(
                        learnerId = learnerId,
                        nowEpochMillis = nowEpochMillis,
                        limit = limit,
                    ).map { issuedMessage ->
                        LearnerMasteryOutboxDelivery.ownerIssued(issuedMessage)
                    }.onEach { delivery ->
                        delivery.requireCompleteLearningAttempt(learnerId)
                    }
                }

            override suspend fun markDelivered(
                message: LearnerMasteryOutboxDelivery,
                deliveredAtEpochMillis: Long,
            ) {
                mutationMutex.withLock {
                    ensureOpen()
                    require(deliveredAtEpochMillis >= 0L) {
                        "Relay delivery time must not be negative"
                    }
                    message.requireCompleteLearningAttempt(learnerId)
                    store.markMessageDelivered(message, deliveredAtEpochMillis)
                }
            }
        }

    init {
        requireMasteryIdentity(learnerId, "Learner id")
        requireMasteryIdentity(runtimeBindingId, "Authority runtime binding id")
    }

    override suspend fun recordObservation(
        command: RecordTrustedLearningObservationCommand,
    ): TrustedLearningObservationResult =
        mutationMutex.withLock {
            recordObservationLocked(
                issued = issueObservation(command),
                deferSavedAttribution = true,
            )
        }

    override suspend fun enqueuePendingOpenResponse(
        facts: PendingOpenResponseFacts,
    ): PendingOpenResponseResult =
        mutationMutex.withLock {
            ensureOpen()
            val trustedNow = nowEpochMillis()
            require(trustedNow >= 0L) {
                "Local mastery clock must not be negative"
            }
            val latestAcceptedAttestation =
                if (trustedNow > Long.MAX_VALUE - MAX_OBSERVATION_FUTURE_SKEW_MILLIS) {
                    Long.MAX_VALUE
                } else {
                    trustedNow + MAX_OBSERVATION_FUTURE_SKEW_MILLIS
                }
            if (
                facts.attestedAtEpochMillis > latestAcceptedAttestation ||
                !facts.isInRuntimeScope(learnerId, runtimeBindingId)
            ) {
                return@withLock PendingOpenResponseResult(
                    sourceFactId = facts.sourceFactId,
                    reviewCaseId = null,
                    disposition = PendingOpenResponseDisposition.REJECTED,
                )
            }
            val result =
                store.enqueuePendingOpenResponse(
                    sourceFact = preparePendingOpenResponseSourceFact(learnerId, facts),
                    candidate = preparePendingOpenResponseCandidate(learnerId, facts),
                )
            PendingOpenResponseResult(
                sourceFactId = result.sourceFactId,
                reviewCaseId = result.reviewCaseId,
                disposition =
                    when (result.status) {
                        PendingOpenResponsePersistenceStatus.QUEUED ->
                            PendingOpenResponseDisposition.QUEUED
                        PendingOpenResponsePersistenceStatus.DUPLICATE ->
                            PendingOpenResponseDisposition.DUPLICATE
                        PendingOpenResponsePersistenceStatus.CONFLICT ->
                            PendingOpenResponseDisposition.CONFLICT
                    },
            )
        }

    private suspend fun recordObservationLocked(
        issued: AuthorityIssuedLearningObservation,
        deferSavedAttribution: Boolean,
        requirePriorSavedProblemAuthorization: Boolean = true,
    ): TrustedLearningObservationResult {
        ensureOpen()
        issued.requireIssuedBy(observationIssuerSeal)
        val command = issued.facts
        command.requireRuntimeScope(learnerId, runtimeBindingId)
        val trustedNow = nowEpochMillis()
        require(trustedNow >= 0L) {
            "Local mastery clock must not be negative"
        }
        val latestAcceptedAttestation =
            if (trustedNow > Long.MAX_VALUE - MAX_OBSERVATION_FUTURE_SKEW_MILLIS) {
                Long.MAX_VALUE
            } else {
                trustedNow + MAX_OBSERVATION_FUTURE_SKEW_MILLIS
            }
        require(command.attestedAtEpochMillis <= latestAcceptedAttestation) {
            "Observation attestation is too far ahead of the trusted local clock"
        }
        val savedContext = command.context as? SavedMistakeLearningContext
        if (savedContext != null && requirePriorSavedProblemAuthorization) {
            check(store.isProblemRevisionAuthorized(savedContext.problemRevision)) {
                "Saved-mistake revision has not been authorized by the student-mistake relay; " +
                    "drain the relay before recording the observation"
            }
        }
        if (savedContext != null && savedContext.knowledgeEvidenceBindings.isNotEmpty()) {
            check(
                store.areProblemKnowledgeBindingsAuthorized(
                    problemRevisionCanonicalFingerprint =
                        savedContext.problemRevision.canonicalFingerprint,
                    bindingCanonicalFingerprints =
                        savedContext.knowledgeEvidenceBindings
                            .mapTo(mutableSetOf()) { it.canonicalFingerprint },
                ),
            ) {
                "Saved-mistake knowledge evidence has not been authorized by a relayed " +
                    "V2 binding snapshot; drain the relay before recording the observation"
            }
        }
        val factResult =
            store.ingestSourceFact(
                prepareTrustedSourceFact(
                    learnerId = learnerId,
                    runtimeBindingId = runtimeBindingId,
                    command = command,
                ),
            )
        if (factResult.status == LearningSourceFactIngestStatus.CONFLICT) {
            return TrustedLearningObservationResult(
                observationId = command.observationId,
                disposition = TrustedLearningObservationDisposition.CONFLICT,
            )
        }
        if (
            deferSavedAttribution &&
            savedContext?.knowledgeEvidenceBindings?.isNotEmpty() == true
        ) {
            return TrustedLearningObservationResult(
                observationId = command.observationId,
                disposition = TrustedLearningObservationDisposition.FACT_STORED,
            )
        }
        val candidate =
            prepareTrustedObservationCandidate(
                learnerId = learnerId,
                command = command,
            )
        val candidateResult = store.ingestObservationCandidate(candidate)
        val terminalReceipt =
            candidateResult.terminalReceipt?.let { receipt ->
                check(receipt.candidateId == candidate.candidateId) {
                    "Mastery admission receipt belongs to another observation candidate"
                }
                check(receipt.candidateCanonicalFingerprint == candidate.canonicalFingerprint) {
                    "Mastery admission receipt does not describe the submitted candidate"
                }
                TrustedLearningObservationTerminalReceipt.create(
                    candidateId = receipt.candidateId,
                    candidateCanonicalFingerprint = receipt.candidateCanonicalFingerprint,
                    disposition =
                        when (receipt.disposition) {
                            LearningObservationDisposition.ADMITTED ->
                                TrustedLearningObservationDisposition.ADMITTED
                            LearningObservationDisposition.INERT ->
                                TrustedLearningObservationDisposition.INERT
                            LearningObservationDisposition.DUPLICATE,
                            LearningObservationDisposition.CONFLICT,
                            -> error("Persisted terminal receipt has a non-terminal disposition")
                        },
                    inertReason = receipt.inertReason,
                    receiptFingerprint = receipt.receiptFingerprint,
                )
            }
        if (
            candidateResult.disposition == LearningObservationDisposition.ADMITTED ||
            candidateResult.disposition == LearningObservationDisposition.DUPLICATE ||
            candidateResult.disposition == LearningObservationDisposition.INERT
        ) {
            checkNotNull(terminalReceipt) {
                "Mastery candidate result is missing its immutable terminal receipt"
            }
        }
        return TrustedLearningObservationResult(
            observationId = command.observationId,
            disposition =
                when (candidateResult.disposition) {
                    LearningObservationDisposition.ADMITTED ->
                        TrustedLearningObservationDisposition.ADMITTED
                    LearningObservationDisposition.DUPLICATE ->
                        TrustedLearningObservationDisposition.DUPLICATE
                    LearningObservationDisposition.INERT ->
                        TrustedLearningObservationDisposition.INERT
                    LearningObservationDisposition.CONFLICT ->
                        TrustedLearningObservationDisposition.CONFLICT
                },
            inertReason = candidateResult.inertReason,
            terminalReceipt = terminalReceipt,
        )
    }

    override fun authorizeEphemeralKnowledge(
        proof: VerifiedKnowledgeReferenceProof,
    ): VerifiedEphemeralKnowledgeEvidence {
        ensureOpen()
        check(knowledgeReferenceVerifier.verifies(proof)) {
            "Knowledge reference proof was not issued by this runtime's knowledge authority"
        }
        return VerifiedEphemeralKnowledgeEvidence.create(
            knowledgeNode = proof.ref,
            manifestFingerprint = proof.manifestFingerprint,
            activationGeneration = proof.activationGeneration,
            runtimeBindingId = runtimeBindingId,
        )
    }

    override fun scopedModelAccess(
        subject: SubjectKind,
        modelVersion: String,
        leaseGate: LearnerMasteryModelAccessLeaseGate,
    ): LearnerMasteryModelAccess {
        ensureOpen()
        val scoped =
            BoundLearnerMasteryModelAccess(
                store = store,
                learnerId = learnerId,
                subject = subject,
                sourceFactId = leaseGate.sourceFactId,
                modelVersion = modelVersion,
                nowEpochMillis = nowEpochMillis,
                ensureRuntimeOpen = ::ensureOpen,
                mutationMutex = mutationMutex,
                leaseGate = leaseGate,
            )
        return LearnerMasteryModelAccess.create(
            candidateSink = scoped,
            digestReader = scoped,
        )
    }

    override suspend fun queryDigest(
        subject: SubjectKind,
        focusLimit: Int,
    ): SubjectMasteryDigest {
        ensureOpen()
        return store.querySubjectDigest(
            SubjectMasteryDigestQuery(
                learnerId = learnerId,
                subject = subject,
                focusLimit = focusLimit,
            ),
        )
    }

    override suspend fun queryTimeline(
        subject: SubjectKind,
        sinceEpochMillis: Long,
        dayLimit: Int,
    ): List<SubjectMasteryTimelineEntry> {
        ensureOpen()
        return store.querySubjectTimeline(
            SubjectMasteryTimelineQuery(
                learnerId = learnerId,
                subject = subject,
                sinceEpochMillis = sinceEpochMillis,
                dayLimit = dayLimit,
            ),
        )
    }

    override suspend fun migrateLegacyFacts(
        batch: LegacyMasteryFactMigrationBatch,
    ): LegacyMasteryFactMigrationResult =
        mutationMutex.withLock {
            migrateLegacyFactsLocked(batch)
        }

    private suspend fun migrateLegacyFactsLocked(
        batch: LegacyMasteryFactMigrationBatch,
    ): LegacyMasteryFactMigrationResult {
        ensureOpen()
        val trustedNow = nowEpochMillis()
        require(trustedNow >= 0L) {
            "Local mastery clock must not be negative"
        }
        val latestAcceptedAttestation =
            if (trustedNow > Long.MAX_VALUE - MAX_OBSERVATION_FUTURE_SKEW_MILLIS) {
                Long.MAX_VALUE
            } else {
                trustedNow + MAX_OBSERVATION_FUTURE_SKEW_MILLIS
            }
        val observations =
            batch.observations.map { observation ->
                val issued = issueObservation(observation)
                issued.requireIssuedBy(observationIssuerSeal)
                val command = issued.facts
                command.requireRuntimeScope(learnerId, runtimeBindingId)
                require(command.attestedAtEpochMillis <= latestAcceptedAttestation) {
                    "Observation attestation is too far ahead of the trusted local clock"
                }
                val savedContext = command.context as? SavedMistakeLearningContext
                if (savedContext != null) {
                    check(store.isProblemRevisionAuthorized(savedContext.problemRevision)) {
                        "Saved-mistake revision has not been authorized by the " +
                            "student-mistake relay; drain the relay before migration"
                    }
                }
                if (savedContext != null && savedContext.knowledgeEvidenceBindings.isNotEmpty()) {
                    check(
                        store.areProblemKnowledgeBindingsAuthorized(
                            problemRevisionCanonicalFingerprint =
                                savedContext.problemRevision.canonicalFingerprint,
                            bindingCanonicalFingerprints =
                                savedContext.knowledgeEvidenceBindings
                                    .mapTo(mutableSetOf()) { it.canonicalFingerprint },
                        ),
                    ) {
                        "Saved-mistake knowledge evidence has not been authorized by a relayed " +
                            "V2 binding snapshot; drain the relay before migration"
                    }
                }
                LegacyMasteryObservationWrite(
                    sourceFact =
                        prepareTrustedSourceFact(
                            learnerId = learnerId,
                            runtimeBindingId = runtimeBindingId,
                            command = command,
                        ),
                    candidate =
                        prepareTrustedObservationCandidate(
                            learnerId = learnerId,
                            command = command,
                        ),
                )
            }
        val checkpoint =
            MasteryLegacyFactMigrationCheckpointEntity(
                learnerId = learnerId,
                sourceGeneration = batch.sourceGeneration,
                batchSequence = batch.batchSequence,
                batchFingerprint = batch.canonicalFingerprint,
                observationCount = batch.observations.size,
                finalBatch = batch.finalBatch,
                sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
                projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                completedAtEpochMillis = trustedNow,
            )
        return when (store.migrateLegacyFactBatch(observations, checkpoint)) {
            LegacyMasteryFactBatchWriteDisposition.IMPORTED ->
                batch.migrationResult(
                    disposition = LegacyMasteryFactMigrationDisposition.IMPORTED,
                    projectionRebuiltFromFacts = batch.finalBatch,
                )
            LegacyMasteryFactBatchWriteDisposition.DUPLICATE ->
                batch.migrationResult(
                    disposition = LegacyMasteryFactMigrationDisposition.DUPLICATE,
                    projectionRebuiltFromFacts = checkpoint.finalBatch,
                )
            LegacyMasteryFactBatchWriteDisposition.OUT_OF_ORDER ->
                batch.migrationResult(
                    disposition = LegacyMasteryFactMigrationDisposition.OUT_OF_ORDER,
                    projectionRebuiltFromFacts = false,
                )
            LegacyMasteryFactBatchWriteDisposition.CONFLICT ->
                batch.migrationResult(
                    disposition = LegacyMasteryFactMigrationDisposition.CONFLICT,
                    projectionRebuiltFromFacts = false,
                )
        }
    }

    override suspend fun eraseAllLearnerData(): LearnerMasteryEraseResult =
        mutationMutex.withLock {
            ensureOpen()
            store.eraseAllLearnerData()
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            store.close()
        }
    }

    private suspend fun applyVerifiedStudentDelivery(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition {
        val envelope = message.envelope()
        require(envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Relay accepts only the bound student-mistake outbox"
        }
        require(envelope.destinationStore == StudyStoreKind.LEARNER_MASTERY) {
            "Student outbox event is not addressed to learner mastery"
        }
        return when (val payload = envelope.payload) {
            is ProblemKnowledgeBindingsSnapshotV2 -> {
                require(payload.problemRevision.problem.learnerId == learnerId) {
                    "Knowledge-binding snapshot is outside the bound learner scope"
                }
                store.acceptProblemKnowledgeBindings(message, receivedAtEpochMillis)
            }
            is ProblemRevisionCommittedV1 -> {
                require(payload.revision.problem.learnerId == learnerId) {
                    "Problem reference is outside the bound learner scope"
                }
                store.acceptStudentProblemReference(message, receivedAtEpochMillis)
            }
            is ProblemLifecycleChangedV1 -> {
                require(payload.problemRevision.problem.learnerId == learnerId) {
                    "Problem lifecycle change is outside the bound learner scope"
                }
                store.acceptProblemLifecycleChange(message, receivedAtEpochMillis)
            }
            is ProblemRevisionSupersededV1 -> {
                require(payload.previousRevision.problem.learnerId == learnerId) {
                    "Problem revision supersession is outside the bound learner scope"
                }
                store.acceptProblemRevisionSupersession(message, receivedAtEpochMillis)
            }
            is ReviewObservationCapturedV2 -> {
                require(payload.problemRevision.problem.learnerId == learnerId) {
                    "Review observation is outside the bound learner scope"
                }
                val bindings =
                    store.readAuthorizedProblemKnowledgeBindings(
                        payload.problemRevision,
                    )
                val receiptDisposition =
                    store.acceptStudentReviewObservation(
                        message = message,
                        receivedAtEpochMillis = receivedAtEpochMillis,
                    )
                if (receiptDisposition == MasteryInboundDisposition.CONFLICT) {
                    return MasteryInboundDisposition.CONFLICT
                }
                val observation =
                    recordObservationLocked(
                        issued =
                            issueObservation(
                                payload.toLearningObservationFacts(
                                    receivedAtEpochMillis,
                                    bindings,
                                ),
                            ),
                        deferSavedAttribution = false,
                        requirePriorSavedProblemAuthorization = false,
                    )
                if (
                    observation.disposition ==
                    TrustedLearningObservationDisposition.CONFLICT
                ) {
                    MasteryInboundDisposition.CONFLICT
                } else {
                    receiptDisposition
                }
            }
            else ->
                error(
                    "Student outbox payload '${envelope.payloadType}' is not supported by " +
                        "learner mastery",
                )
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) {
            "Learner-mastery authority runtime is closed"
        }
    }

    private fun issueObservation(
        facts: LearningObservationFacts,
    ): AuthorityIssuedLearningObservation =
        AuthorityIssuedLearningObservation.issue(
            facts,
            observationIssuerSeal,
        )
}

private fun ReviewObservationCapturedV2.toLearningObservationFacts(
    receivedAtEpochMillis: Long,
    knowledgeEvidenceBindings: List<ProblemKnowledgeBindingRef> = emptyList(),
): LearningObservationFacts {
    val safeHintCount = hintCount.coerceAtMost(MAX_RELAY_HINT_COUNT)
    val safeRetryCount = (attemptOrdinal - 1).coerceAtMost(MAX_RELAY_RETRY_COUNT)
    return LearningObservationFacts(
        observationId = observationId,
        subject = problemRevision.problem.subject,
        source = TrustedLearningObservationSource.SAVED_PROBLEM_REVIEW,
        sourceReferenceId = submissionId,
        presentationFingerprint =
            CanonicalSha256("learner-mastery-review-presentation-v1")
                .field("problemRevision", problemRevision.canonicalFingerprint)
                .field("reviewSessionId", reviewSessionId)
                .field("reviewQueueItemId", reviewQueueItemId)
                .field("presentationId", presentationId)
                .finish(),
        context =
            SavedMistakeLearningContext(
                problemRevision = problemRevision,
                problemFamilyFingerprint = problemRevision.problem.canonicalFingerprint,
                reviewAttempt =
                    TrustedReviewAttemptEvidence(
                        reviewSessionId = reviewSessionId,
                        reviewQueueItemId = reviewQueueItemId,
                        submissionId = submissionId,
                    ),
                knowledgeEvidenceBindings = knowledgeEvidenceBindings,
            ),
        responseForm =
            when (responseForm) {
                ReviewResponseForm.CHOICE ->
                    TrustedLearningResponseForm.MULTIPLE_CHOICE
                ReviewResponseForm.NUMERIC ->
                    TrustedLearningResponseForm.FREE_RESPONSE
                ReviewResponseForm.VISUAL_TARGET ->
                    TrustedLearningResponseForm.VISUAL_TARGET
            },
        answerWasCorrect =
            verificationOutcome == ReviewVerificationOutcome.CORRECT,
        learnerReportedStuck = false,
        answerWasViewed = false,
        independentlyAnswered =
            attemptOrdinal == 1 &&
                safeHintCount == 0 &&
                !answerWasRevealed,
        hintCount = safeHintCount,
        answerRevealed = answerWasRevealed,
        retryCount = safeRetryCount,
        elapsedDurationMillis = elapsedDurationMillis,
        verification = TrustedLearningVerification.DETERMINISTIC_RUBRIC,
        evidenceCanonicalFingerprint = payloadCanonicalFingerprint,
        occurredAtEpochMillis = capturedAtEpochMillis,
        attestedAtEpochMillis = receivedAtEpochMillis,
    )
}

private object LearnerMasteryMutationLocks {
    private val stripes = Array(64) { Mutex() }

    fun forLearner(learnerId: String): Mutex =
        stripes[(learnerId.hashCode() and Int.MAX_VALUE) % stripes.size]
}

private const val MAX_RELAY_HINT_COUNT = 32
private const val MAX_RELAY_RETRY_COUNT = 32
private const val MAX_OBSERVATION_FUTURE_SKEW_MILLIS = 5L * 60L * 1_000L

internal fun prepareTrustedSourceFact(
    learnerId: String,
    runtimeBindingId: String,
    command: RecordTrustedLearningObservationCommand,
): IngestLearningSourceFactCommand {
    command.requireRuntimeScope(learnerId, runtimeBindingId)
    val saved = command.context as? SavedMistakeLearningContext
    val ephemeral = command.context as? EphemeralTutorProblemLearningContext
    val verifiedKnowledge = ephemeral?.verifiedKnowledgeEvidence.orEmpty()
    val authorityIdentity =
        deriveAuthorityEvidenceIdentity(
            learnerId = learnerId,
            command = command,
        )
    val identity =
        CanonicalSha256("learner-mastery-trusted-observation-identity-v1")
            .field("learnerId", learnerId)
            .field("observation", command.canonicalFingerprint)
            .finish()
    return IngestLearningSourceFactCommand(
        sourceFactId = command.observationId,
        learnerId = learnerId,
        subject = command.subject,
        sourceKind = enumValueOf(command.source.name),
        sourceReferenceId = command.sourceReferenceId,
        presentationId = command.presentationFingerprint,
        presentationFingerprint = command.presentationFingerprint,
        problemFamilyFingerprint = command.context.problemFamilyFingerprint,
        evidenceContextKind =
            if (saved != null) {
                MasteryEvidenceContextKind.SAVED_MISTAKE
            } else {
                MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM
            },
        ephemeralProblemFingerprint = ephemeral?.problemFingerprint,
        tutorTurnReferenceId = ephemeral?.tutorTurnReferenceId,
        submissionEvidenceFingerprint = ephemeral?.submissionEvidenceFingerprint,
        attributionModelVersion = ephemeral?.attributionModelVersion,
        authorizedProblemBindingsFingerprint =
            saved
                ?.knowledgeEvidenceBindings
                ?.takeIf { bindings -> bindings.isNotEmpty() }
                ?.let { bindings ->
                    fingerprintAuthorizedProblemBindings(
                        bindings.map { binding ->
                            binding.canonicalFingerprint
                        },
                    )
                },
        authorizedKnowledgeRefsFingerprint =
            ephemeral?.let {
                fingerprintAuthorizedKnowledgeRefs(
                    verifiedKnowledge.map { evidence ->
                        evidence.knowledgeNode.canonicalFingerprint
                    },
                )
            },
        knowledgeManifestFingerprint =
            verifiedKnowledge.firstOrNull()?.manifestFingerprint,
        knowledgeActivationGeneration =
            verifiedKnowledge.firstOrNull()?.activationGeneration,
        authorityAttemptFingerprint = authorityIdentity.attemptFingerprint,
        authoritySubmissionFingerprint = authorityIdentity.submissionFingerprint,
        authorityPresentationFingerprint = authorityIdentity.presentationFingerprint,
        authorityProblemFamilyFingerprint = authorityIdentity.problemFamilyFingerprint,
        authorityIdentityVersion = LEARNER_MASTERY_AUTHORITY_IDENTITY_VERSION,
        problemRevision = saved?.problemRevision,
        reviewAttemptContext =
            saved?.reviewAttempt?.let { evidence ->
                TrustedReviewAttemptContext(
                    reviewSessionId = evidence.reviewSessionId,
                    reviewQueueItemId = evidence.reviewQueueItemId,
                    submissionId = evidence.submissionId,
                )
            },
        responseForm = command.responseForm,
        independentlyAnswered = command.independentlyAnswered,
        hintCount = command.hintCount,
        answerRevealed = command.answerRevealed,
        elapsedDurationMillis = command.elapsedDurationMillis,
        verificationKind = command.verification,
        outcome = command.deriveOutcome(),
        assistance = command.deriveAssistance(),
        retryState = command.deriveRetryState(),
        authority = command.verification.deriveAuthority(),
        sourcePayloadCanonicalFingerprint = command.evidenceCanonicalFingerprint,
        occurredAtEpochMillis = command.occurredAtEpochMillis,
        attestedAtEpochMillis = command.attestedAtEpochMillis,
        idempotencyKey = "trusted-observation:$identity",
    )
}

internal fun preparePendingOpenResponseSourceFact(
    learnerId: String,
    facts: PendingOpenResponseFacts,
): IngestLearningSourceFactCommand {
    val context = facts.context
    val verifiedKnowledge = context.verifiedKnowledgeEvidence
    return IngestLearningSourceFactCommand(
        sourceFactId = facts.sourceFactId,
        learnerId = learnerId,
        subject = facts.subject,
        sourceKind = MasteryEvidenceSourceKind.TUTOR_FREE_RESPONSE,
        sourceReferenceId = facts.submissionId,
        presentationId = facts.presentationFingerprint,
        presentationFingerprint = facts.presentationFingerprint,
        problemFamilyFingerprint = context.problemFamilyFingerprint,
        evidenceContextKind = MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM,
        ephemeralProblemFingerprint = context.problemFingerprint,
        tutorTurnReferenceId = context.tutorTurnReferenceId,
        submissionEvidenceFingerprint = context.submissionEvidenceFingerprint,
        attributionModelVersion = context.attributionModelVersion,
        authorizedProblemBindingsFingerprint = null,
        authorizedKnowledgeRefsFingerprint =
            fingerprintAuthorizedKnowledgeRefs(
                verifiedKnowledge.map { it.knowledgeNode.canonicalFingerprint },
            ),
        knowledgeManifestFingerprint = verifiedKnowledge.firstOrNull()?.manifestFingerprint,
        knowledgeActivationGeneration =
            verifiedKnowledge.firstOrNull()?.activationGeneration,
        problemRevision = null,
        responseForm = TrustedLearningResponseForm.FREE_RESPONSE,
        independentlyAnswered =
            facts.attemptOrdinal == 1 &&
                facts.hintCount == 0 &&
                !facts.answerWasRevealed,
        hintCount = facts.hintCount,
        answerRevealed = facts.answerWasRevealed,
        elapsedDurationMillis = facts.elapsedDurationMillis,
        verificationKind = TrustedLearningVerification.SELF_REPORTED,
        outcome = ObservedLearningOutcome.PENDING_REVIEW,
        assistance =
            deriveAssistance(
                independentlyAnswered =
                    facts.attemptOrdinal == 1 &&
                        facts.hintCount == 0 &&
                        !facts.answerWasRevealed,
                hintCount = facts.hintCount,
                answerRevealed = facts.answerWasRevealed,
            ),
        retryState = deriveRetryState(facts.attemptOrdinal - 1),
        authority = MasteryEvidenceAuthority.SELF_REPORTED,
        sourcePayloadCanonicalFingerprint = facts.canonicalFingerprint,
        occurredAtEpochMillis = facts.occurredAtEpochMillis,
        attestedAtEpochMillis = facts.attestedAtEpochMillis,
        idempotencyKey = "pending-open-response:${facts.sourceFactId}",
    )
}

internal fun preparePendingOpenResponseCandidate(
    learnerId: String,
    facts: PendingOpenResponseFacts,
): IngestLearningObservationCandidateCommand {
    val identity =
        CanonicalSha256("learner-mastery-pending-open-response-candidate-v1")
            .field("learnerId", learnerId)
            .field("sourceFactId", facts.sourceFactId)
            .finish()
    return IngestLearningObservationCandidateCommand(
        candidateId = "pending-open-response:$identity",
        learnerId = learnerId,
        subject = facts.subject,
        sourceFactId = facts.sourceFactId,
        proposedAttributions = emptyList(),
        confidence = MasteryCandidateConfidence.LOW,
        modelVersion = facts.context.attributionModelVersion,
        idempotencyKey = "pending-open-response-candidate:${facts.sourceFactId}",
        proposedAtEpochMillis = facts.attestedAtEpochMillis,
        requestedPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
    )
}

private data class AuthorityEvidenceIdentity(
    val attemptFingerprint: String,
    val submissionFingerprint: String,
    val presentationFingerprint: String,
    val problemFamilyFingerprint: String,
)

/**
 * Derives de-duplication identities only from host-bound episode anchors. Caller-proposed
 * presentation and semantic family fingerprints remain useful metadata, but cannot create a fresh
 * evidence budget for the same real submission.
 */
private fun deriveAuthorityEvidenceIdentity(
    learnerId: String,
    command: RecordTrustedLearningObservationCommand,
): AuthorityEvidenceIdentity {
    val saved = command.context as? SavedMistakeLearningContext
    val ephemeral = command.context as? EphemeralTutorProblemLearningContext
    val problemAnchor =
        saved?.problemRevision?.problem?.canonicalFingerprint
            ?: requireNotNull(ephemeral).problemFingerprint
    val submissionAnchor =
        saved?.reviewAttempt?.canonicalFingerprint
            ?: ephemeral?.let { context ->
                CanonicalSha256("learner-mastery-ephemeral-submission-anchor-v1")
                    .field("tutorTurnReferenceId", context.tutorTurnReferenceId)
                    .field(
                        "submissionEvidenceFingerprint",
                        context.submissionEvidenceFingerprint,
                    )
                    .finish()
            }
            ?: CanonicalSha256("learner-mastery-local-source-submission-anchor-v1")
                .field("source", command.source.name)
                .field("sourceReferenceId", command.sourceReferenceId)
                .field("evidenceFingerprint", command.evidenceCanonicalFingerprint)
                .finish()
    val presentationAnchor =
        saved?.reviewAttempt?.let { review ->
            CanonicalSha256("learner-mastery-review-presentation-anchor-v1")
                .field("reviewSessionId", review.reviewSessionId)
                .field("reviewQueueItemId", review.reviewQueueItemId)
                .field("problem", problemAnchor)
                .finish()
        } ?: ephemeral?.let { context ->
            CanonicalSha256("learner-mastery-tutor-presentation-anchor-v1")
                .field("tutorTurnReferenceId", context.tutorTurnReferenceId)
                .field("problem", problemAnchor)
                .finish()
        } ?: CanonicalSha256("learner-mastery-local-presentation-anchor-v1")
            .field("source", command.source.name)
            .field("sourceReferenceId", command.sourceReferenceId)
            .field("problem", problemAnchor)
            .finish()
    // Knowledge bindings describe attribution, not solution structure. Until a future host-only
    // binding snapshot carries a verified method fingerprint, the immutable problem is the only
    // safe family anchor; this avoids collapsing independent transfer evidence.
    val verifiedFamilyAnchor = problemAnchor
    return AuthorityEvidenceIdentity(
        attemptFingerprint =
            CanonicalSha256("learner-mastery-authority-attempt-v1")
                .field("learnerId", learnerId)
                .field("subject", command.subject.name)
                .field("source", command.source.name)
                .field("problem", problemAnchor)
                .field("submission", submissionAnchor)
                .finish(),
        submissionFingerprint =
            CanonicalSha256("learner-mastery-authority-submission-v1")
                .field("learnerId", learnerId)
                .field("subject", command.subject.name)
                .field("source", command.source.name)
                .field("submission", submissionAnchor)
                .finish(),
        presentationFingerprint =
            CanonicalSha256("learner-mastery-authority-presentation-v1")
                .field("learnerId", learnerId)
                .field("subject", command.subject.name)
                .field("presentation", presentationAnchor)
                .finish(),
        problemFamilyFingerprint =
            CanonicalSha256("learner-mastery-authority-problem-family-v1")
                .field("learnerId", learnerId)
                .field("subject", command.subject.name)
                .field("verifiedFamily", verifiedFamilyAnchor)
                .finish(),
    )
}

internal fun prepareTrustedObservationCandidate(
    learnerId: String,
    command: RecordTrustedLearningObservationCommand,
): IngestLearningObservationCandidateCommand {
    val attributions =
        when (val context = command.context) {
            is SavedMistakeLearningContext ->
                context.knowledgeEvidenceBindings.map { binding ->
                    ProposedKnowledgeAttribution(
                        knowledgeNode = binding.knowledgeNode,
                        problemBinding = binding,
                        role = MasteryAttributionRole.PRIMARY,
                        certainty = MasteryAttributionCertainty.DIRECT,
                    )
                }
            is EphemeralTutorProblemLearningContext ->
                context.verifiedKnowledgeEvidence.map { evidence ->
                    ProposedKnowledgeAttribution(
                        knowledgeNode = evidence.knowledgeNode,
                        problemBinding = null,
                        role = MasteryAttributionRole.PRIMARY,
                        certainty = MasteryAttributionCertainty.DIRECT,
                    )
                }
        }
    val modelVersion =
        (command.context as? EphemeralTutorProblemLearningContext)
            ?.attributionModelVersion
            ?: LOCAL_TRUSTED_ATTRIBUTION_POLICY_VERSION
    val identity =
        CanonicalSha256("learner-mastery-trusted-candidate-identity-v1")
            .field("learnerId", learnerId)
            .field("subject", command.subject.name)
            .field("sourceFactId", command.observationId)
            .field("context", command.context.canonicalFingerprint)
            .field("modelVersion", modelVersion)
            .field("projectionPolicy", LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
            .finish()
    return IngestLearningObservationCandidateCommand(
        candidateId = "mastery-trusted-candidate:${identity.take(48)}",
        learnerId = learnerId,
        subject = command.subject,
        sourceFactId = command.observationId,
        proposedAttributions = attributions,
        confidence = MasteryCandidateConfidence.HIGH,
        modelVersion = modelVersion,
        idempotencyKey = "mastery-trusted-candidate-idempotency:$identity",
        proposedAtEpochMillis = command.attestedAtEpochMillis,
        requestedPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
    )
}

private fun RecordTrustedLearningObservationCommand.requireRuntimeScope(
    learnerId: String,
    runtimeBindingId: String,
) {
    requireMasteryIdentity(learnerId, "Learner id")
    when (val context = context) {
        is SavedMistakeLearningContext -> {
            require(context.problemRevision.problem.learnerId == learnerId) {
                "Observation problem revision is outside the bound learner scope"
            }
            require(context.problemRevision.problem.subject == subject) {
                "Observation problem revision is outside the bound subject scope"
            }
        }
        is EphemeralTutorProblemLearningContext -> {
            require(
                context.verifiedKnowledgeEvidence.all { evidence ->
                    evidence.runtimeBindingId == runtimeBindingId &&
                        evidence.knowledgeNode.subject == subject
                },
            ) {
                "Ephemeral knowledge evidence is not authorized by this runtime scope"
            }
            val manifests =
                context.verifiedKnowledgeEvidence
                    .map { it.manifestFingerprint to it.activationGeneration }
                    .distinct()
            require(manifests.size <= 1) {
                "Ephemeral knowledge evidence must come from one activated catalog generation"
            }
        }
    }
}

private fun PendingOpenResponseFacts.isInRuntimeScope(
    learnerId: String,
    runtimeBindingId: String,
): Boolean =
    learnerId.isNotBlank() &&
        context.verifiedKnowledgeEvidence.all { evidence ->
            evidence.runtimeBindingId == runtimeBindingId &&
                evidence.knowledgeNode.subject == subject
        } &&
        context.verifiedKnowledgeEvidence
            .map { it.manifestFingerprint to it.activationGeneration }
            .distinct()
            .size <= 1

private fun RecordTrustedLearningObservationCommand.deriveOutcome(): ObservedLearningOutcome =
    when {
        answerWasViewed -> ObservedLearningOutcome.VIEWED_ONLY
        learnerReportedStuck -> ObservedLearningOutcome.STUCK
        answerWasCorrect == true -> ObservedLearningOutcome.CORRECT
        else -> ObservedLearningOutcome.INCORRECT
    }

private fun RecordTrustedLearningObservationCommand.deriveAssistance(): ObservedAssistance =
    deriveAssistance(independentlyAnswered, hintCount, answerRevealed)

private fun RecordTrustedLearningObservationCommand.deriveRetryState(): ObservedRetryState =
    deriveRetryState(retryCount)

private fun deriveAssistance(
    independentlyAnswered: Boolean,
    hintCount: Int,
    answerRevealed: Boolean,
): ObservedAssistance =
    when {
        answerRevealed -> ObservedAssistance.ANSWER_REVEALED
        independentlyAnswered -> ObservedAssistance.INDEPENDENT
        hintCount == 1 -> ObservedAssistance.ONE_HINT
        hintCount > 1 -> ObservedAssistance.MULTIPLE_HINTS
        else -> ObservedAssistance.UNKNOWN
    }

private fun deriveRetryState(retryCount: Int): ObservedRetryState =
    when (retryCount) {
        0 -> ObservedRetryState.FIRST_ATTEMPT
        1 -> ObservedRetryState.ONE_RETRY
        else -> ObservedRetryState.MULTIPLE_RETRIES
    }

private fun TrustedLearningVerification.deriveAuthority(): MasteryEvidenceAuthority =
    when (this) {
        TrustedLearningVerification.DEVICE_OBSERVED ->
            MasteryEvidenceAuthority.LOCAL_VERIFIED
        TrustedLearningVerification.DETERMINISTIC_RUBRIC ->
            MasteryEvidenceAuthority.DETERMINISTIC_RUBRIC
        TrustedLearningVerification.MODEL_REVIEWED ->
            MasteryEvidenceAuthority.MODEL_REVIEWED
        TrustedLearningVerification.SELF_REPORTED ->
            MasteryEvidenceAuthority.SELF_REPORTED
    }

private fun LearnerMasteryOutboxDelivery.requireCompleteLearningAttempt(learnerId: String) {
    val envelope = issuedMessage().envelope
    require(envelope.sourceStore == StudyStoreKind.LEARNER_MASTERY) {
        "Outbound learning attempt must originate from learner mastery"
    }
    require(envelope.destinationStore == StudyStoreKind.STUDENT_MISTAKES) {
        "Outbound learning attempt must target the student-mistake store"
    }
    val attempt =
        envelope.payload as? LearningAttemptRecordedV1
            ?: error("Learner-mastery outbox contains an unsupported payload")
    require(
        attempt.evidence.learnerId == learnerId &&
            attempt.problemRevision.problem.learnerId == learnerId,
    ) {
        "Outbound learning attempt is outside the bound learner scope"
    }
}

private fun LegacyMasteryFactMigrationBatch.migrationResult(
    disposition: LegacyMasteryFactMigrationDisposition,
    projectionRebuiltFromFacts: Boolean,
): LegacyMasteryFactMigrationResult =
    LegacyMasteryFactMigrationResult(
        sourceGeneration = sourceGeneration,
        batchSequence = batchSequence,
        disposition = disposition,
        observationCount = observations.size,
        projectionRebuiltFromFacts = projectionRebuiltFromFacts,
    )

private const val LOCAL_TRUSTED_ATTRIBUTION_POLICY_VERSION =
    "local-trusted-attribution-v1"
