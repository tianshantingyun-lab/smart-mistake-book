package com.tingyun.smartmistakebook.core.mastery.database

import android.annotation.SuppressLint
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.NoopProjectionWorkloadGate
import com.tingyun.smartmistakebook.core.model.ProjectionWorkloadGate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsAcceptedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

internal class RoomLearnerMasteryStore(
    private var database: LearnerMasteryRoomDatabase,
    private val nowEpochMillis: () -> Long,
    private val delayDisplayRefresh: suspend (Long) -> Unit = { delay(it) },
    private var outboxAuthenticatorSession: MasteryOutboxAuthenticatorSession? = null,
    private val reopenDatabase: () -> LearnerMasteryRoomDatabase = {
        error("Learner-mastery erase requires a database reopen factory")
    },
    private val projectionWorkloadGate: ProjectionWorkloadGate =
        NoopProjectionWorkloadGate,
) : LearnerMasteryStore {
    private var dao = database.masteryDao()
    private var displayDao = database.displayDao()
    private val projectionRebuildScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val projectionRebuildRunning = AtomicBoolean(false)
    private val projectionRebuildOwnerId = "projection-rebuild:${UUID.randomUUID()}"

    private suspend fun prepareDerivedState():
        MasteryProjectionRebuildChunkResult {
        val result = dao.prepareProjectionRebuild(nowEpochMillis())
        if (!result.completed && result.blockedReason == null) {
            scheduleProjectionRebuild()
        }
        return result
    }

    private fun scheduleProjectionRebuild() {
        if (!projectionRebuildRunning.compareAndSet(false, true)) {
            return
        }
        projectionRebuildScope.launch {
            projectionWorkloadGate.withPermit {
                try {
                    while (true) {
                        val result =
                            dao.rebuildDerivedStateChunk(
                                ownerId = projectionRebuildOwnerId,
                                nowEpochMillis = nowEpochMillis(),
                            )
                        if (
                            result.completed ||
                            result.blockedReason != null ||
                            result.leaseBusy
                        ) {
                            break
                        }
                        yield()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The durable cursor and lease make the next foreground touch resume safely.
                } finally {
                    projectionRebuildRunning.set(false)
                }
            }
        }
    }

    override suspend fun ingestSourceFact(
        command: IngestLearningSourceFactCommand,
    ): LearningSourceFactIngestResult {
        val fact = command.toEntity(receivedAtEpochMillis = nowEpochMillis())
        val proof = fact.toSourceProof()
        val status =
            when (val result = dao.ingestSourceFact(fact, proof)) {
                is InternalSourceFactWriteResult.Stored ->
                    if (result.policySupported) {
                        LearningSourceFactIngestStatus.STORED
                    } else {
                        LearningSourceFactIngestStatus.STORED_INERT
                    }
                InternalSourceFactWriteResult.Duplicate ->
                    LearningSourceFactIngestStatus.DUPLICATE
                InternalSourceFactWriteResult.Conflict ->
                    LearningSourceFactIngestStatus.CONFLICT
            }
        return LearningSourceFactIngestResult(
            sourceFactId = command.sourceFactId,
            status = status,
        )
    }

    override suspend fun enqueuePendingOpenResponse(
        sourceFact: IngestLearningSourceFactCommand,
        candidate: IngestLearningObservationCandidateCommand,
    ): PendingOpenResponsePersistenceResult {
        require(
            sourceFact.learnerId == candidate.learnerId &&
                sourceFact.subject == candidate.subject &&
                sourceFact.sourceFactId == candidate.sourceFactId,
        ) {
            "Pending open-response fact and candidate must share one learner-bound identity"
        }
        require(
            sourceFact.outcome == ObservedLearningOutcome.PENDING_REVIEW &&
                sourceFact.responseForm == TrustedLearningResponseForm.FREE_RESPONSE &&
                sourceFact.authority == MasteryEvidenceAuthority.SELF_REPORTED,
        ) {
            "Pending open-response persistence accepts only direction-free free-response facts"
        }
        require(candidate.proposedAttributions.isEmpty()) {
            "Pending open-response placeholder cannot carry semantic attribution"
        }
        val receivedAtEpochMillis = nowEpochMillis()
        val fact = sourceFact.toEntity(receivedAtEpochMillis)
        val candidateEntity = candidate.toEntity(receivedAtEpochMillis)
        return dao.enqueuePendingOpenResponse(
            fact = fact,
            proof = fact.toSourceProof(),
            candidate = candidateEntity,
            decidedAtEpochMillis =
                maxOf(
                    sourceFact.attestedAtEpochMillis,
                    candidate.proposedAtEpochMillis,
                ),
        )
    }

    override suspend fun acceptProblemKnowledgeBindings(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition {
        val envelope = message.envelope()
        require(envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Problem binding authority must originate from the student-mistake store"
        }
        require(envelope.destinationStore == StudyStoreKind.LEARNER_MASTERY) {
            "Problem binding authority must target the learner-mastery store"
        }
        require(receivedAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Cross-store receipt time must not precede the source event"
        }
        val snapshot = message.toBindingAuthoritySnapshot()
        val inbox = message.toInboxEntity(receivedAtEpochMillis)
        val state =
            MasteryProblemBindingAuthorityStateEntity(
                problemRevisionRefFingerprint =
                    snapshot.problemRevision.canonicalFingerprint,
                inboxEventId = envelope.eventId,
                sourceStoreGeneration = envelope.sourceStoreGeneration,
                envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                payloadCanonicalFingerprint = envelope.payloadCanonicalFingerprint,
                bindingProtocolVersion = snapshot.protocolVersion,
                bindingSetVersion = snapshot.bindingSetVersion,
                learnerId = snapshot.problemRevision.problem.learnerId,
                subject = snapshot.problemRevision.problem.subject.name,
                changedAtEpochMillis = snapshot.changedAtEpochMillis,
            )
        val authorities =
            snapshot.bindings.map { binding ->
                binding.toAuthorityEntity(
                    message = message,
                    protocolVersion = snapshot.protocolVersion,
                    bindingSetVersion = snapshot.bindingSetVersion,
                    changedAtEpochMillis = snapshot.changedAtEpochMillis,
                )
            }
        return dao.acceptProblemKnowledgeBindings(
            message = inbox,
            sourceBinding = message.toSourceBindingEntity(receivedAtEpochMillis),
            authenticityReceipt =
                message.toAuthenticatedInboxReceiptEntity(receivedAtEpochMillis),
            incomingState = state,
            authorities = authorities,
        )
    }

    override suspend fun acceptStudentProblemReference(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition {
        val envelope = message.envelope()
        require(envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Problem reference must originate from the student-mistake store"
        }
        require(envelope.destinationStore == StudyStoreKind.LEARNER_MASTERY) {
            "Problem reference must target the learner-mastery store"
        }
        require(envelope.payload is ProblemRevisionCommittedV1) {
            "Only a committed immutable problem reference may be acknowledged"
        }
        require(receivedAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Cross-store receipt time must not precede the source event"
        }
        return dao.acceptStudentProblemReference(
            message = message.toInboxEntity(receivedAtEpochMillis),
            sourceBinding = message.toSourceBindingEntity(receivedAtEpochMillis),
            authenticityReceipt =
                message.toAuthenticatedInboxReceiptEntity(receivedAtEpochMillis),
        )
    }

    override suspend fun acceptProblemLifecycleChange(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition {
        val envelope = message.envelope()
        require(envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Problem lifecycle change must originate from the student-mistake store"
        }
        require(envelope.destinationStore == StudyStoreKind.LEARNER_MASTERY) {
            "Problem lifecycle change must target the learner-mastery store"
        }
        require(envelope.payload is ProblemLifecycleChangedV1) {
            "Only a lifecycle change event may enter this path"
        }
        require(receivedAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Cross-store receipt time must not precede the lifecycle change"
        }
        return dao.acceptProblemLifecycleChange(
            message = message.toInboxEntity(receivedAtEpochMillis),
            sourceBinding = message.toSourceBindingEntity(receivedAtEpochMillis),
            authenticityReceipt =
                message.toAuthenticatedInboxReceiptEntity(receivedAtEpochMillis),
        )
    }

    override suspend fun acceptProblemRevisionSupersession(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition {
        val envelope = message.envelope()
        require(envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Problem revision supersession must originate from the student-mistake store"
        }
        require(envelope.destinationStore == StudyStoreKind.LEARNER_MASTERY) {
            "Problem revision supersession must target the learner-mastery store"
        }
        require(envelope.payload is ProblemRevisionSupersededV1) {
            "Only a revision supersession event may enter this path"
        }
        require(receivedAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Cross-store receipt time must not precede the revision supersession"
        }
        return dao.acceptProblemRevisionSupersession(
            message = message.toInboxEntity(receivedAtEpochMillis),
            sourceBinding = message.toSourceBindingEntity(receivedAtEpochMillis),
            authenticityReceipt =
                message.toAuthenticatedInboxReceiptEntity(receivedAtEpochMillis),
        )
    }

    override suspend fun acceptStudentReviewObservation(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition {
        val envelope = message.envelope()
        require(envelope.sourceStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Review observation must originate from the student-mistake store"
        }
        require(envelope.destinationStore == StudyStoreKind.LEARNER_MASTERY) {
            "Review observation must target the learner-mastery store"
        }
        require(envelope.payload is ReviewObservationCapturedV2) {
            "Only an owner-bound V2 student review observation may enter this path"
        }
        require(receivedAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Cross-store receipt time must not precede the review observation"
        }
        return dao.acceptStudentProblemReference(
            message = message.toInboxEntity(receivedAtEpochMillis),
            sourceBinding = message.toSourceBindingEntity(receivedAtEpochMillis),
            authenticityReceipt =
                message.toAuthenticatedInboxReceiptEntity(receivedAtEpochMillis),
        )
    }

    override suspend fun isProblemRevisionAuthorized(
        problemRevision: StudentProblemRevisionRef,
    ): Boolean {
        if (
            dao.hasProblemBindingAuthorityState(
                problemRevision.canonicalFingerprint,
            )
        ) {
            return true
        }
        return dao.readProblemReferenceReceipts(
            payloadType = ProblemRevisionCommittedV1.PAYLOAD_TYPE,
            payloadVersion = ProblemRevisionCommittedV1.PAYLOAD_VERSION,
            revisionId = problemRevision.revisionId,
            limit = MAX_PROBLEM_REFERENCE_RECEIPTS,
        ).any { row ->
            val payload =
                LearnerMasteryCrossStoreCodec.decode(
                    payloadType = row.payloadType,
                    payloadVersion = row.payloadVersion,
                    wire = row.payloadWire,
                )
            check(payload.payloadCanonicalFingerprint == row.payloadCanonicalFingerprint) {
                "Corrupt learner-mastery problem-reference receipt"
            }
            (payload as ProblemRevisionCommittedV1).revision == problemRevision
        }
    }

    override suspend fun areProblemKnowledgeBindingsAuthorized(
        problemRevisionCanonicalFingerprint: String,
        bindingCanonicalFingerprints: Set<String>,
    ): Boolean =
        dao.areProblemKnowledgeBindingsAuthorized(
            problemRevisionRefFingerprint = problemRevisionCanonicalFingerprint,
            bindingRefFingerprints = bindingCanonicalFingerprints,
        )

    override suspend fun readAuthorizedProblemKnowledgeBindings(
        problemRevision: StudentProblemRevisionRef,
    ): List<ProblemKnowledgeBindingRef> {
        val inbox =
            dao.findCurrentBindingSnapshotInbox(
                problemRevision.canonicalFingerprint,
            ) ?: return emptyList()
        val payload =
            LearnerMasteryCrossStoreCodec.decode(
                payloadType = inbox.payloadType,
                payloadVersion = inbox.payloadVersion,
                wire = inbox.payloadWire,
            )
        check(payload is ProblemKnowledgeBindingsSnapshotV2) {
            "Current mastery binding-authority snapshot is not a V2 binding payload"
        }
        check(payload.problemRevision == problemRevision) {
            "Current mastery binding-authority snapshot does not match the requested revision"
        }
        return payload.bindings.sortedBy(ProblemKnowledgeBindingRef::bindingId)
    }

    override suspend fun readPendingMessages(
        learnerId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<LearnerMasteryRelayMessage> {
        requireMasteryIdentity(learnerId, "Learner id")
        require(nowEpochMillis >= 0L) { "Outbox read time must not be negative" }
        require(limit in 1..MAX_OUTBOX_READ_LIMIT) { "Outbox read limit is out of range" }
        val authenticator =
            (outboxAuthenticatorSession
                ?: MasteryOutboxAuthenticatorSession(
                    learnerId,
                    AndroidKeystoreMasteryOutboxHmacKeyStore.INSTANCE,
                ).also { outboxAuthenticatorSession = it }).bind(
                checkNotNull(dao.readActiveMasteryOutboxAuthenticityKeyState()) {
                    "Learner-mastery outbox authenticity key-state is unavailable"
                }.toActiveKey(),
            )
        return dao.readPendingOutbox(learnerId, nowEpochMillis, limit).map { row ->
            val payload =
                LearnerMasteryCrossStoreCodec.decode(
                    payloadType = row.payloadType,
                    payloadVersion = row.payloadVersion,
                    wire = row.payloadWire,
                )
            check(payload is LearningAttemptRecordedV1) {
                "Corrupt learner-mastery outbox: unsupported payload type"
            }
            val envelope = CrossStoreEventEnvelope(
                eventId = row.eventId,
                sourceStore = enumValueOrCorrupt(row.sourceStore, "outbox source store"),
                destinationStore =
                    enumValueOrCorrupt(row.destinationStore, "outbox destination store"),
                aggregateId = row.aggregateId,
                aggregateVersion = row.aggregateVersion,
                occurredAtEpochMillis = row.occurredAtEpochMillis,
                idempotencyKey = row.idempotencyKey,
                sourceStoreGeneration = row.sourceStoreGeneration,
                payload = payload,
                payloadType = row.payloadType,
                payloadVersion = row.payloadVersion,
                payloadCanonicalFingerprint = row.payloadCanonicalFingerprint,
            ).also { envelope ->
                check(envelope.canonicalFingerprint == row.envelopeCanonicalFingerprint) {
                    "Corrupt learner-mastery outbox: envelope fingerprint mismatch"
                }
            }
            authenticator.attest(envelope)
        }
    }

    override suspend fun markMessageDelivered(
        message: LearnerMasteryOutboxDelivery,
        deliveredAtEpochMillis: Long,
    ) {
        val issuedMessage = message.issuedMessage()
        checkNotNull(outboxAuthenticatorSession) {
            "Learner-mastery outbox verification is unavailable"
        }.verifier.requireAuthentic(issuedMessage)
        val envelope = issuedMessage.envelope
        require(envelope.sourceStore == StudyStoreKind.LEARNER_MASTERY) {
            "Only learner-mastery outbox messages may be marked delivered"
        }
        require(envelope.destinationStore == StudyStoreKind.STUDENT_MISTAKES) {
            "Learner-mastery outbox message has an invalid destination"
        }
        require(deliveredAtEpochMillis >= envelope.occurredAtEpochMillis) {
            "Delivery time must not precede the source event"
        }
        val changed =
            dao.markOutboxDelivered(
                eventId = envelope.eventId,
                envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                deliveredAtEpochMillis = deliveredAtEpochMillis,
            )
        check(changed in 0..1) { "Outbox delivery updated an invalid number of records" }
    }

    override suspend fun ingestObservationCandidate(
        command: IngestLearningObservationCandidateCommand,
    ): LearningObservationIngestResult {
        prepareDerivedState()
        val candidate = command.toEntity(receivedAtEpochMillis = nowEpochMillis())
        val attributions =
            command.canonicalAttributions.mapIndexed { index, attribution ->
                attribution.toEntity(command.candidateId, index)
            }
        return dao.ingestCandidate(
            candidate = candidate,
            attributions = attributions,
            decisionTimeEpochMillis = nowEpochMillis(),
            candidateOrigin = command.candidateOrigin,
        )
    }

    override suspend fun ingestModelObservationCandidate(
        command: IngestLearningObservationCandidateCommand,
        attempt: ModelSubmissionAttempt,
    ): ModelSubmissionAttemptOutcome {
        prepareDerivedState()
        val receivedAtEpochMillis = nowEpochMillis()
        val candidate = command.toEntity(receivedAtEpochMillis)
        val attributions =
            command.canonicalAttributions.mapIndexed { index, attribution ->
                attribution.toEntity(command.candidateId, index)
            }
        return dao.ingestModelCandidateAttempt(
            candidate = candidate,
            attributions = attributions,
            decisionTimeEpochMillis = receivedAtEpochMillis,
            attempt = attempt,
        )
    }

    override suspend fun recordRejectedModelSubmissionAttempt(
        attempt: ModelSubmissionAttempt,
        terminalReason: ModelSubmissionTerminalReason,
    ): ModelSubmissionAttemptReceipt {
        prepareDerivedState()
        return dao.recordRejectedModelCandidateAttempt(
            attempt = attempt,
            terminalReason = terminalReason,
            receivedAtEpochMillis = nowEpochMillis(),
        )
    }

    override suspend fun readPendingEvidenceReviews(
        learnerId: String,
        subject: SubjectKind,
        limit: Int,
    ): List<PendingLearningEvidenceReview> {
        requireMasteryIdentity(learnerId, "Learner id")
        requireSpecificSubject(subject)
        require(limit in 1..LearnerMasteryEvidenceReviewCapability.MAX_LIMIT)
        return dao.readPendingEvidenceReviewCases(
            learnerId = learnerId,
            subject = subject.name,
            limit = limit,
        ).map { reviewCase ->
            PendingLearningEvidenceReview(
                reviewCaseId = reviewCase.reviewCaseId,
                subject = subject,
                sourceFactId = reviewCase.sourceFactId,
                candidateId = reviewCase.candidateId,
                createdAtEpochMillis = reviewCase.createdAtEpochMillis,
            )
        }
    }

    override suspend fun resolveEvidenceReview(
        learnerId: String,
        command: ResolveLearningEvidenceReviewCommand,
    ): LearningEvidenceReviewWriteResult {
        requireMasteryIdentity(learnerId, "Learner id")
        prepareDerivedState()
        return dao.resolveEvidenceReview(learnerId, command)
    }

    override suspend fun correctLearningEvidence(
        learnerId: String,
        command: CorrectLearningEvidenceCommand,
        replacementSourceFact: IngestLearningSourceFactCommand,
        replacementCandidate: IngestLearningObservationCandidateCommand,
    ): LearningEvidenceCorrectionResult {
        requireMasteryIdentity(learnerId, "Learner id")
        require(
            replacementSourceFact.learnerId == learnerId &&
                replacementCandidate.learnerId == learnerId &&
                replacementSourceFact.subject == command.subject &&
                replacementCandidate.subject == command.subject &&
                replacementCandidate.sourceFactId == replacementSourceFact.sourceFactId,
        ) {
            "Evidence correction replacement is outside its learner or subject scope"
        }
        prepareDerivedState()
        val receivedAt = nowEpochMillis()
        val replacementFact =
            replacementSourceFact
                .toEntity(receivedAtEpochMillis = receivedAt)
                .asCorrectionRevision(command)
        val replacementProof =
            MasterySourceProofEntity(
                sourceFactId = replacementFact.sourceFactId,
                sourceFactCanonicalFingerprint = replacementFact.canonicalFingerprint,
                sourcePolicyVersion = replacementFact.sourcePolicyVersion,
                policySupported =
                    replacementFact.sourcePolicyVersion ==
                        LEARNER_MASTERY_SOURCE_POLICY_VERSION,
                proofFingerprint = LearnerMasteryFingerprint.sourceProof(replacementFact),
                createdAtEpochMillis = replacementFact.attestedAtEpochMillis,
            )
        val candidate = replacementCandidate.toEntity(receivedAtEpochMillis = receivedAt)
        val attributions =
            replacementCandidate.canonicalAttributions.mapIndexed { index, attribution ->
                attribution.toEntity(replacementCandidate.candidateId, index)
            }
        return try {
            dao.correctLearningEvidence(
                learnerId = learnerId,
                command = command,
                replacementFact = replacementFact,
                replacementProof = replacementProof,
                replacementCandidate = candidate,
                replacementAttributions = attributions,
                decisionTimeEpochMillis = receivedAt,
            )
        } catch (_: LearningEvidenceCorrectionRejectedException) {
            LearningEvidenceCorrectionResult(
                replacementObservationId = command.replacementObservation.observationId,
                replacementSourceFactCanonicalFingerprint = null,
                disposition = LearningEvidenceCorrectionDisposition.REJECTED,
            )
        }
    }

    override suspend fun querySubjectDigest(
        query: SubjectMasteryDigestQuery,
    ): SubjectMasteryDigest {
        val readiness = prepareDerivedState()
        if (!readiness.activeGenerationAvailable) {
            return SubjectMasteryDigest(
                learnerId = query.learnerId,
                subject = query.subject,
                stateCounts =
                    SubjectMasteryStateCounts(
                        needsReinforcement = 0,
                        familiarizing = 0,
                        steady = 0,
                    ),
                focus = emptyList(),
                lastUpdatedAtEpochMillis = null,
                historyAvailability =
                    LearnerMasteryHistoryAvailability.INSUFFICIENT_HISTORY,
            )
        }
        val subjectName = query.subject.name
        val now = nowEpochMillis()
        val snapshot =
            dao.readSubjectDigestSnapshot(
                learnerId = query.learnerId,
                subject = subjectName,
                limit = query.focusLimit,
                nowEpochMillis = now,
            )
        val digest = snapshot.digest
        val focus =
            snapshot.focus.map { projection ->
                KnowledgeMasteryDigestItem(
                    knowledgeNode = projection.toKnowledgeNodeRef(),
                    historicalState =
                        enumValueOrCorrupt(projection.masteryState, "mastery state"),
                    currentRecallState =
                        LocalMasteryPolicy.currentRecallState(projection, now),
                    trend = enumValueOrCorrupt(projection.trend, "mastery trend"),
                    evidenceQuality =
                        projection.evidenceQualityMicros.toEvidenceQuality(),
                    independentProblemFamilyCount =
                        projection.independentProblemFamilyCount,
                    distinctPresentationCount = projection.distinctPresentationCount,
                    memoryStabilityMillis = projection.memoryStabilityMillis,
                    recallDueAtEpochMillis = projection.recallDueAtEpochMillis,
                    lastPositiveAtEpochMillis = projection.lastPositiveAtEpochMillis,
                    lastNegativeAtEpochMillis = projection.lastNegativeAtEpochMillis,
                    lastEvidenceAtEpochMillis = projection.lastEvidenceAtEpochMillis,
                )
            }
        return SubjectMasteryDigest(
            learnerId = query.learnerId,
            subject = query.subject,
            stateCounts =
                SubjectMasteryStateCounts(
                    needsReinforcement = digest?.needsReinforcementCount ?: 0,
                    familiarizing = digest?.familiarizingCount ?: 0,
                    steady = digest?.steadyCount ?: 0,
                ),
            focus = focus,
            lastUpdatedAtEpochMillis = digest?.updatedAtEpochMillis,
        )
    }

    override fun observeDisplayRevision(
        query: BoundLearnerMasteryDisplayQuery,
    ): Flow<LearnerMasteryDisplayRevision> =
        channelFlow {
            prepareDerivedState()
            displayDao.observeDisplayRevision(query.learnerId).collectLatest { ledgerSequence ->
                var lastRevision: LearnerMasteryDisplayRevision? = null
                while (true) {
                    val now = nowEpochMillis()
                    require(now >= 0L) {
                        "Mastery display clock must not be negative"
                    }
                    val bounds =
                        displayDao.readDisplayTemporalBounds(
                            learnerId = query.learnerId,
                            nowEpochMillis = now,
                        )
                    val asOf = bounds.latestTransitionAtEpochMillis ?: 0L
                    check(asOf <= now) {
                        "Mastery display temporal snapshot is in the future"
                    }
                    val revision =
                        LearnerMasteryDisplayRevision(
                            ledgerSequence = ledgerSequence,
                            asOfEpochMillis = asOf,
                        )
                    if (revision != lastRevision) {
                        send(revision)
                        lastRevision = revision
                    }
                    val nextRefreshAt =
                        bounds.nextTransitionAtEpochMillis ?: awaitCancellation()
                    check(nextRefreshAt > now) {
                        "Mastery display next refresh must be in the future"
                    }
                    delayDisplayRefresh(maxOf(1L, nextRefreshAt - now))
                }
            }
        }

    override suspend fun readDisplayOverview(
        query: BoundLearnerMasteryDisplayQuery,
        expectedRevision: LearnerMasteryDisplayRevision,
    ): LearnerMasteryDisplayOverviewResult {
        prepareDerivedState()
        val snapshot =
            displayDao.readDisplayOverviewSnapshot(
                learnerId = query.learnerId,
                expectedRevision = expectedRevision.ledgerSequence,
            )
        if (snapshot.revisionChanged) {
            val now = nowEpochMillis()
            require(now >= 0L) {
                "Mastery display clock must not be negative"
            }
            val bounds =
                displayDao.readDisplayTemporalBounds(
                    learnerId = query.learnerId,
                    nowEpochMillis = now,
                )
            return LearnerMasteryDisplayOverviewResult.RevisionChanged(
                LearnerMasteryDisplayRevision(
                    ledgerSequence = snapshot.revision,
                    asOfEpochMillis = bounds.latestTransitionAtEpochMillis ?: 0L,
                ),
            )
        }
        snapshot.projections.forEach { projection ->
            check(projection.learnerId == query.learnerId) {
                "Mastery display overview escaped its learner scope"
            }
        }
        val projectionsBySubject =
            snapshot.projections.groupBy { projection ->
                enumValueOrCorrupt<SubjectKind>(projection.subject, "display subject")
            }
        check(projectionsBySubject.keys.none { it == SubjectKind.GENERAL }) {
            "Mastery display query returned the general subject"
        }
        return LearnerMasteryDisplayOverviewResult.Current(
            LearnerMasteryDisplayOverviewSnapshot(
                revision = expectedRevision,
                subjects =
                    LEARNER_MASTERY_DISPLAY_SUBJECTS.map { subject ->
                        projectionsBySubject[subject]?.toDisplaySubjectOverview(
                            subject = subject,
                            asOfEpochMillis = expectedRevision.asOfEpochMillis,
                        )
                            ?: LearnerMasteryDisplaySubjectOverview(
                                subject = subject,
                                currentState = null,
                                trend = null,
                            )
                    },
                taxonomyVersions = snapshot.taxonomyVersions,
            ),
        )
    }

    override suspend fun readDisplayKnowledgePage(
        query: BoundLearnerMasteryDisplayPageQuery,
    ): LearnerMasteryDisplayPageResult {
        prepareDerivedState()
        val request = query.request
        val requestedFingerprints =
            request.orderedKnowledgeNodes.map { node ->
                MasteryProjectionIdentity.fingerprint(
                    subject = node.subject.name,
                    knowledgeNodeId = node.knowledgeNodeId,
                    taxonomyVersion = node.taxonomyVersion,
                )
            }
        val snapshot =
            displayDao.readDisplayPageSnapshot(
                learnerId = query.learnerId,
                subject = request.subject.name,
                expectedRevision = request.expectedRevision.ledgerSequence,
                stableNodeIdentityFingerprints = requestedFingerprints,
            )
        val revision =
            LearnerMasteryDisplayRevision(
                ledgerSequence = snapshot.revision,
                asOfEpochMillis = request.expectedRevision.asOfEpochMillis,
            )
        if (snapshot.revisionChanged) {
            val now = nowEpochMillis()
            require(now >= 0L) {
                "Mastery display clock must not be negative"
            }
            val bounds =
                displayDao.readDisplayTemporalBounds(
                    learnerId = query.learnerId,
                    nowEpochMillis = now,
                )
            return LearnerMasteryDisplayPageResult.RevisionChanged(
                LearnerMasteryDisplayRevision(
                    ledgerSequence = snapshot.revision,
                    asOfEpochMillis = bounds.latestTransitionAtEpochMillis ?: 0L,
                ),
            )
        }
        snapshot.projections.forEach { projection ->
            check(
                projection.learnerId == query.learnerId &&
                    projection.subject == request.subject.name,
            ) {
                "Mastery display page escaped its learner or subject scope"
            }
            check(
                projection.stableNodeIdentityFingerprint ==
                    MasteryProjectionIdentity.fingerprint(
                        subject = projection.subject,
                        knowledgeNodeId = projection.knowledgeNodeId,
                        taxonomyVersion = projection.taxonomyVersion,
                    ),
            ) {
                "Mastery display page contains a corrupt knowledge identity"
            }
        }
        val projectionByFingerprint =
            snapshot.projections.associateBy(MasteryKnowledgeProjectionEntity::stableNodeIdentityFingerprint)
        check(projectionByFingerprint.size == snapshot.projections.size) {
            "Mastery display lookup returned duplicate projections"
        }
        return LearnerMasteryDisplayPageResult.Current(
            revision = revision,
            items =
                requestedFingerprints.mapNotNull { fingerprint ->
                    projectionByFingerprint[fingerprint]?.let { projection ->
                        LearnerMasteryDisplayKnowledgeItem(
                            stableNodeIdentityFingerprint =
                                projection.stableNodeIdentityFingerprint,
                            knowledgeNode = projection.toKnowledgeNodeRef(),
                            currentRecallState =
                                LocalMasteryPolicy.currentRecallState(
                                    projection,
                                    request.expectedRevision.asOfEpochMillis,
                                ),
                            trend = enumValueOrCorrupt(projection.trend, "mastery trend"),
                        )
                    }
                },
            taxonomyVersions = snapshot.taxonomyVersions,
        )
    }

    override suspend fun readDisplaySubjectTimeline(
        query: BoundLearnerMasteryDisplayTimelineQuery,
    ): LearnerMasteryDisplayTimelineResult {
        prepareDerivedState()
        val request = query.request
        val currentLedgerSequence =
            displayDao.observeDisplayRevision(query.learnerId).first()
        if (currentLedgerSequence != request.expectedRevision.ledgerSequence) {
            val now = nowEpochMillis()
            require(now >= 0L) {
                "Mastery display clock must not be negative"
            }
            val bounds =
                displayDao.readDisplayTemporalBounds(
                    learnerId = query.learnerId,
                    nowEpochMillis = now,
                )
            return LearnerMasteryDisplayTimelineResult.RevisionChanged(
                LearnerMasteryDisplayRevision(
                    ledgerSequence = currentLedgerSequence,
                    asOfEpochMillis = bounds.latestTransitionAtEpochMillis ?: 0L,
                ),
            )
        }
        val entries =
            dao.readSubjectTimeline(
                learnerId = query.learnerId,
                subject = request.subject.name,
                sinceEpochMillis = request.sinceEpochMillis,
                dayLimit = request.dayLimit,
            ).map(MasteryTimelineRow::toTimelineEntry)
                .sortedBy(SubjectMasteryTimelineEntry::utcEpochDay)
        return LearnerMasteryDisplayTimelineResult.Current(
            revision = request.expectedRevision,
            entries = entries,
        )
    }

    override suspend fun queryLocalMasteryContext(
        query: BoundLocalMasteryContextQuery,
    ): LocalMasteryContext {
        val readiness = prepareDerivedState()
        val request = query.request
        if (!readiness.activeGenerationAvailable) {
            return LocalMasteryContext(
                subject = request.subject,
                items = emptyList(),
                historyAvailability =
                    LearnerMasteryHistoryAvailability.INSUFFICIENT_HISTORY,
            )
        }
        val exactFingerprints = request.exactStableNodeFingerprints.sorted()
        val effectiveFallbackLimit =
            minOf(
                request.fallbackLimit,
                LocalMasteryContextRequest.MAX_RESULT_COUNT - exactFingerprints.size,
            )
        val now = nowEpochMillis()
        val exact =
            if (exactFingerprints.isEmpty()) {
                emptyList()
            } else {
                displayDao.readExactKnowledgeProjections(
                    learnerId = query.learnerId,
                    subject = request.subject.name,
                    stableNodeFingerprints = exactFingerprints,
                )
            }
        val focus =
            if (effectiveFallbackLimit == 0) {
                emptyList()
            } else {
                dao.readSubjectDigestSnapshot(
                    learnerId = query.learnerId,
                    subject = request.subject.name,
                    limit = exactFingerprints.size + effectiveFallbackLimit,
                    nowEpochMillis = now,
                ).focus
            }
        return buildLocalMasteryContext(
            query = query,
            exactProjections = exact,
            focusProjections = focus,
            nowEpochMillis = now,
        )
    }

    override suspend fun querySubjectTimeline(
        query: SubjectMasteryTimelineQuery,
    ): List<SubjectMasteryTimelineEntry> =
        dao.readSubjectTimeline(
            learnerId = query.learnerId,
            subject = query.subject.name,
            sinceEpochMillis = query.sinceEpochMillis,
            dayLimit = query.dayLimit,
        ).map(MasteryTimelineRow::toTimelineEntry)
            .sortedBy(SubjectMasteryTimelineEntry::utcEpochDay)

    override suspend fun migrateLegacyFactBatch(
        observations: List<LegacyMasteryObservationWrite>,
        checkpoint: MasteryLegacyFactMigrationCheckpointEntity,
    ): LegacyMasteryFactBatchWriteDisposition {
        require(checkpoint.observationCount == observations.size) {
            "Legacy migration checkpoint does not match its observation batch"
        }
        require(
            observations.all { observation ->
                observation.sourceFact.learnerId == checkpoint.learnerId &&
                    observation.candidate.learnerId == checkpoint.learnerId &&
                    observation.candidate.sourceFactId == observation.sourceFact.sourceFactId
            },
        ) {
            "Legacy migration observations are outside the checkpoint learner scope"
        }
        prepareDerivedState()
        val receivedAtEpochMillis = nowEpochMillis()
        val writes =
            observations.map { observation ->
                val fact = observation.sourceFact.toEntity(receivedAtEpochMillis)
                MasteryLegacyObservationWrite(
                    sourceFact = fact,
                    sourceProof =
                        MasterySourceProofEntity(
                            sourceFactId = fact.sourceFactId,
                            sourceFactCanonicalFingerprint = fact.canonicalFingerprint,
                            sourcePolicyVersion = fact.sourcePolicyVersion,
                            policySupported =
                                fact.sourcePolicyVersion ==
                                    LEARNER_MASTERY_SOURCE_POLICY_VERSION,
                            proofFingerprint = LearnerMasteryFingerprint.sourceProof(fact),
                            createdAtEpochMillis = fact.attestedAtEpochMillis,
                        ),
                    candidate = observation.candidate.toEntity(receivedAtEpochMillis),
                    attributions =
                        observation.candidate.canonicalAttributions.mapIndexed {
                                index,
                                attribution,
                            ->
                            attribution.toEntity(observation.candidate.candidateId, index)
                        },
                    decisionTimeEpochMillis = receivedAtEpochMillis,
                    candidateOrigin = observation.candidate.candidateOrigin,
                )
            }
        return try {
            dao.migrateLegacyFactBatch(writes, checkpoint)
        } catch (_: LegacyMasteryBatchConflictException) {
            LegacyMasteryFactBatchWriteDisposition.CONFLICT
        }
    }

    @SuppressLint("RestrictedApi")
    override suspend fun eraseAllLearnerData(): LearnerMasteryEraseResult {
        projectionRebuildScope.coroutineContext[Job]
            ?.children
            ?.forEach { it.cancel() }
        projectionRebuildRunning.set(false)
        database.useConnection(isReadOnly = false) { transactor ->
            eraseAllLearnerMasteryRows(transactor)
        }
        database.close()
        database = reopenDatabase()
        dao = database.masteryDao()
        displayDao = database.displayDao()
        return LearnerMasteryEraseResult(nowEpochMillis())
    }

    override fun close() {
        projectionRebuildScope.cancel()
        database.close()
    }
}

private fun List<MasteryKnowledgeProjectionEntity>.toDisplaySubjectOverview(
    subject: SubjectKind,
    asOfEpochMillis: Long,
):
    LearnerMasteryDisplaySubjectOverview {
    check(isNotEmpty()) {
        "An empty mastery subject must not produce an aggregate"
    }
    check(all { projection -> projection.subject == subject.name }) {
        "Mastery display subject aggregate crossed a subject boundary"
    }
    val stateCounts =
        groupingBy { projection ->
            LocalMasteryPolicy.recallProjectionState(
                projection,
                asOfEpochMillis,
            ).currentState
        }.eachCount()
    val trendCounts =
        groupingBy { projection ->
            enumValueOrCorrupt<KnowledgeMasteryTrend>(projection.trend, "mastery trend")
        }.eachCount()
    val state =
        when {
            stateCounts.getOrDefault(KnowledgeMasteryState.NEEDS_REINFORCEMENT, 0) >=
                stateCounts.getOrDefault(KnowledgeMasteryState.FAMILIARIZING, 0) &&
                stateCounts.getOrDefault(KnowledgeMasteryState.NEEDS_REINFORCEMENT, 0) >=
                stateCounts.getOrDefault(KnowledgeMasteryState.STEADY, 0) ->
                KnowledgeMasteryState.NEEDS_REINFORCEMENT

            stateCounts.getOrDefault(KnowledgeMasteryState.FAMILIARIZING, 0) >=
                stateCounts.getOrDefault(KnowledgeMasteryState.STEADY, 0) ->
                KnowledgeMasteryState.FAMILIARIZING

            else -> KnowledgeMasteryState.STEADY
        }
    val aggregateTrend =
        when {
            trendCounts.getOrDefault(KnowledgeMasteryTrend.WAVERING, 0) >=
                trendCounts.getOrDefault(KnowledgeMasteryTrend.IMPROVING, 0) &&
                trendCounts.getOrDefault(KnowledgeMasteryTrend.WAVERING, 0) >=
                trendCounts.getOrDefault(KnowledgeMasteryTrend.STABLE, 0) ->
                KnowledgeMasteryTrend.WAVERING

            trendCounts.getOrDefault(KnowledgeMasteryTrend.IMPROVING, 0) >
                trendCounts.getOrDefault(KnowledgeMasteryTrend.STABLE, 0) ->
                KnowledgeMasteryTrend.IMPROVING

            else -> KnowledgeMasteryTrend.STABLE
        }
    return LearnerMasteryDisplaySubjectOverview(
        subject = subject,
        currentState = state,
        trend = aggregateTrend,
    )
}

internal fun buildLocalMasteryContext(
    query: BoundLocalMasteryContextQuery,
    exactProjections: List<MasteryKnowledgeProjectionEntity>,
    focusProjections: List<MasteryKnowledgeProjectionEntity>,
    nowEpochMillis: Long,
): LocalMasteryContext {
    require(nowEpochMillis >= 0L) {
        "Local mastery context clock must not be negative"
    }
    val request = query.request
    val subject = request.subject.name
    val allProjections = exactProjections + focusProjections
    check(
        allProjections.all {
            it.learnerId == query.learnerId && it.subject == subject
        },
    ) {
        "Local mastery context projection escaped its learner or subject scope"
    }
    val requestedFingerprints = request.exactStableNodeFingerprints.toSet()
    check(
        exactProjections.all {
            it.stableNodeIdentityFingerprint in requestedFingerprints
        },
    ) {
        "Exact local mastery context query returned an unrequested knowledge node"
    }

    val exactByFingerprint =
        exactProjections.associateBy(MasteryKnowledgeProjectionEntity::stableNodeIdentityFingerprint)
    val exact =
        request.exactStableNodeFingerprints
            .sorted()
            .mapNotNull(exactByFingerprint::get)
    val selectedFingerprints =
        exact.mapTo(mutableSetOf()) {
            it.stableNodeIdentityFingerprint
        }
    val fallbackComparator =
        compareBy<MasteryKnowledgeProjectionEntity> {
            when (enumValueOrCorrupt<KnowledgeMasteryState>(it.masteryState, "mastery state")) {
                KnowledgeMasteryState.NEEDS_REINFORCEMENT -> 0
                KnowledgeMasteryState.FAMILIARIZING -> 1
                KnowledgeMasteryState.STEADY -> 2
            }
        }.thenByDescending(MasteryKnowledgeProjectionEntity::lastEvidenceAtEpochMillis)
            .thenBy(MasteryKnowledgeProjectionEntity::stableNodeIdentityFingerprint)
    val fallback =
        focusProjections
            .sortedWith(fallbackComparator)
            .filter { selectedFingerprints.add(it.stableNodeIdentityFingerprint) }
            .take(
                minOf(
                    request.fallbackLimit,
                    LocalMasteryContextRequest.MAX_RESULT_COUNT - exact.size,
                ),
            )

    return LocalMasteryContext(
        subject = request.subject,
        items =
            exact.map {
                it.toLocalMasteryContextItem(
                    selection = LocalMasteryContextSelection.EXACT,
                    nowEpochMillis = nowEpochMillis,
                )
            } +
                fallback.map {
                    it.toLocalMasteryContextItem(
                        selection = LocalMasteryContextSelection.SUBJECT_FOCUS,
                        nowEpochMillis = nowEpochMillis,
                    )
                },
    )
}

internal class BoundLearnerMasteryModelAccess(
    private val store: LearnerMasteryStore,
    private val learnerId: String,
    private val subject: SubjectKind,
    private val sourceFactId: String,
    private val modelVersion: String,
    private val nowEpochMillis: () -> Long,
    private val ensureRuntimeOpen: () -> Unit,
    private val mutationMutex: Mutex,
    private val leaseGate: LearnerMasteryModelAccessLeaseGate,
) : LearnerMasteryCandidateSink,
    SubjectMasteryDigestReader {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
        requireSpecificSubject(subject)
        requireMasteryIdentity(sourceFactId, "Source fact id")
        requireMasteryVersion(modelVersion, "Attribution model version")
    }

    override suspend fun submitCandidate(
        command: SubmitLearningObservationCandidateCommand,
    ): ModelLearningObservationResult =
        mutationMutex.withLock {
            val attempt =
                leaseGate.acquireCandidateSubmission(command.canonicalFingerprint)
            try {
                ensureRuntimeOpen()
                if (
                    attempt.claim ==
                    ModelSubmissionCandidateClaim.LOGICAL_ATTEMPT_CONFLICT
                ) {
                    val receipt =
                        store.recordRejectedModelSubmissionAttempt(
                            attempt = attempt,
                            terminalReason =
                                ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT,
                        )
                    leaseGate.confirmCurrent()
                    return@withLock receipt.toModelResult()
                }
                val proposedAtEpochMillis = nowEpochMillis()
                val candidate =
                    try {
                        prepareBoundModelCandidate(
                            learnerId = learnerId,
                            subject = subject,
                            sourceFactId = sourceFactId,
                            modelVersion = modelVersion,
                            command = command,
                            proposedAtEpochMillis = proposedAtEpochMillis,
                        )
                    } catch (_: IllegalArgumentException) {
                        val receipt =
                            store.recordRejectedModelSubmissionAttempt(
                                attempt = attempt,
                                terminalReason =
                                    ModelSubmissionTerminalReason.MALFORMED_SUBMISSION,
                            )
                        leaseGate.confirmCurrent()
                        return@withLock receipt.toModelResult()
                    }
                val outcome =
                    try {
                        store.ingestModelObservationCandidate(
                            command = candidate,
                            attempt = attempt,
                        )
                    } catch (revoked: ModelSubmissionPermissionEpochRevokedException) {
                        attempt.rollbackPermissionEpochClaim()
                        store.recordRejectedModelSubmissionAttempt(
                            attempt = attempt,
                            terminalReason =
                                ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED,
                        )
                        throw revoked
                    }
                when (outcome) {
                    is ModelSubmissionAttemptOutcome.Terminal -> {
                        leaseGate.confirmCurrent()
                        outcome.receipt.toModelResult()
                    }

                    ModelSubmissionAttemptOutcome.RebuildPending -> {
                        attempt.rollbackPermissionEpochClaim()
                        leaseGate.confirmCurrent()
                        ModelLearningObservationResult(
                            disposition = ModelLearningObservationDisposition.RETRYABLE,
                            receiptFingerprint = null,
                        )
                    }
                }
            } finally {
                attempt.finish()
            }
        }

    override suspend fun queryDigest(focusLimit: Int): ModelSubjectMasteryDigest {
        leaseGate.acquireDigestRead()
        ensureRuntimeOpen()
        val digest =
            store.querySubjectDigest(
                SubjectMasteryDigestQuery(
                    learnerId = learnerId,
                    subject = subject,
                    focusLimit = focusLimit,
                ),
            )
        leaseGate.confirmCurrent()
        return digest.toModelDigest()
    }
}

private fun ModelSubmissionAttemptReceipt.toModelResult(): ModelLearningObservationResult =
    ModelLearningObservationResult(
        disposition = ModelLearningObservationDisposition.RECEIVED,
        receiptFingerprint = receiptFingerprint,
    )

private fun SubjectMasteryDigest.toModelDigest(): ModelSubjectMasteryDigest =
    ModelSubjectMasteryDigest(
        subject = subject,
        items =
            focus.map { item ->
                ModelKnowledgeMasteryDigestItem(
                    knowledgeNode = item.knowledgeNode,
                    state = item.currentRecallState,
                    trend = item.trend,
                )
            },
    )

internal fun prepareBoundModelCandidate(
    learnerId: String,
    subject: SubjectKind,
    sourceFactId: String,
    modelVersion: String,
    command: SubmitLearningObservationCandidateCommand,
    proposedAtEpochMillis: Long,
): IngestLearningObservationCandidateCommand {
    requireMasteryIdentity(learnerId, "Learner id")
    requireSpecificSubject(subject)
    requireMasteryIdentity(sourceFactId, "Source fact id")
    requireMasteryVersion(modelVersion, "Attribution model version")
    require(proposedAtEpochMillis >= 0L) {
        "Locally generated candidate proposal time must not be negative"
    }
    val identity =
        CanonicalSha256("learner-mastery-local-candidate-identity-v2")
            .field("learnerId", learnerId)
            .field("subject", subject.name)
            .field("sourceFactId", sourceFactId)
            .field("modelVersion", modelVersion)
            .field("semanticCandidate", command.canonicalFingerprint)
            .field("projectionPolicy", LEARNER_MASTERY_PROJECTION_POLICY_VERSION)
            .finish()
    return IngestLearningObservationCandidateCommand(
        candidateId = "mastery-candidate:${identity.take(48)}",
        learnerId = learnerId,
        subject = subject,
        sourceFactId = sourceFactId,
        proposedAttributions = command.proposedAttributions,
        confidence = command.confidence,
        modelVersion = modelVersion,
        idempotencyKey = "mastery-candidate-idempotency:$identity",
        proposedAtEpochMillis = proposedAtEpochMillis,
        requestedPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
    )
}

private data class BindingAuthoritySnapshot(
    val problemRevision: StudentProblemRevisionRef,
    val bindings: List<ProblemKnowledgeBindingRef>,
    val protocolVersion: Int,
    val bindingSetVersion: Long,
    val changedAtEpochMillis: Long,
)

private fun VerifiedStudentMistakeDelivery.toInboxEntity(
    receivedAtEpochMillis: Long,
): MasteryCrossStoreInboxEntity {
    val source = envelope()
    return MasteryCrossStoreInboxEntity(
        eventId = source.eventId,
        sourceStore = source.sourceStore.name,
        destinationStore = source.destinationStore.name,
        aggregateId = source.aggregateId,
        aggregateVersion = source.aggregateVersion,
        payloadType = source.payloadType,
        payloadVersion = source.payloadVersion,
        payloadCanonicalFingerprint = source.payloadCanonicalFingerprint,
        payloadWire = LearnerMasteryCrossStoreCodec.encode(source.payload),
        envelopeCanonicalFingerprint = source.canonicalFingerprint,
        idempotencyKey = source.idempotencyKey,
        sourceStoreGeneration = source.sourceStoreGeneration,
        occurredAtEpochMillis = source.occurredAtEpochMillis,
        receivedAtEpochMillis = receivedAtEpochMillis,
        processedAtEpochMillis = receivedAtEpochMillis,
    )
}

private fun VerifiedStudentMistakeDelivery.toBindingAuthoritySnapshot():
    BindingAuthoritySnapshot {
    val source = envelope()
    return when (val payload = source.payload) {
        is ProblemKnowledgeBindingsAcceptedV1 ->
            BindingAuthoritySnapshot(
                problemRevision = payload.problemRevision,
                bindings = payload.bindings,
                protocolVersion = LEGACY_BINDING_PROTOCOL_VERSION,
                bindingSetVersion = LEGACY_BINDING_SET_VERSION,
                changedAtEpochMillis = payload.acceptedAtEpochMillis,
            )

        is ProblemKnowledgeBindingsSnapshotV2 -> {
            require(source.aggregateVersion == payload.bindingSetVersion) {
                "Binding snapshot aggregate version must equal its binding-set version"
            }
            BindingAuthoritySnapshot(
                problemRevision = payload.problemRevision,
                bindings = payload.bindings,
                protocolVersion = CURRENT_BINDING_PROTOCOL_VERSION,
                bindingSetVersion = payload.bindingSetVersion,
                changedAtEpochMillis = payload.changedAtEpochMillis,
            )
        }

        else -> error("Cross-store payload does not grant problem binding authority")
    }
}

private fun ProblemKnowledgeBindingRef.toAuthorityEntity(
    message: VerifiedStudentMistakeDelivery,
    protocolVersion: Int,
    bindingSetVersion: Long,
    changedAtEpochMillis: Long,
): MasteryProblemBindingAuthorityEntity {
    val source = message.envelope()
    return MasteryProblemBindingAuthorityEntity(
        bindingRefFingerprint = canonicalFingerprint,
        inboxEventId = source.eventId,
        sourceStoreGeneration = source.sourceStoreGeneration,
        envelopeCanonicalFingerprint = source.canonicalFingerprint,
        payloadCanonicalFingerprint = source.payloadCanonicalFingerprint,
        bindingProtocolVersion = protocolVersion,
        bindingSetVersion = bindingSetVersion,
        problemRevisionRefFingerprint = problemRevision.canonicalFingerprint,
        learnerId = problemRevision.problem.learnerId,
        subject = problemRevision.problem.subject.name,
        knowledgeNodeId = knowledgeNode.knowledgeNodeId,
        taxonomyVersion = knowledgeNode.taxonomyVersion,
        knowledgePackVersion = knowledgeNode.knowledgePackVersion,
        knowledgeNodeRefFingerprint = knowledgeNode.canonicalFingerprint,
        acceptedAtEpochMillis = changedAtEpochMillis,
    )
}

private fun IngestLearningSourceFactCommand.toEntity(
    receivedAtEpochMillis: Long,
): MasterySourceFactEntity =
    MasterySourceFactEntity(
        sourceFactId = sourceFactId,
        learnerId = learnerId,
        subject = subject.name,
        sourceKind = sourceKind.name,
        sourceReferenceId = sourceReferenceId,
        presentationId = presentationId,
        problemRevisionRefFingerprint = problemRevision?.canonicalFingerprint,
        problemRevisionId = problemRevision?.revisionId,
        problemId = problemRevision?.problem?.problemId,
        practiceUnitId = problemRevision?.problem?.practiceUnitId,
        problemRevisionNumber = problemRevision?.revisionNumber,
        problemDocumentFingerprint = problemRevision?.documentCanonicalFingerprint,
        reviewSessionId = reviewAttemptContext?.reviewSessionId,
        reviewQueueItemId = reviewAttemptContext?.reviewQueueItemId,
        reviewSubmissionId = reviewAttemptContext?.submissionId,
        outcome = outcome.name,
        assistance = assistance.name,
        retryState = retryState.name,
        authority = authority.name,
        sourcePayloadFingerprint = sourcePayloadCanonicalFingerprint,
        occurredAtEpochMillis = occurredAtEpochMillis,
        attestedAtEpochMillis = attestedAtEpochMillis,
        receivedAtEpochMillis = receivedAtEpochMillis,
        sourcePolicyVersion = sourcePolicyVersion,
        idempotencyKey = idempotencyKey,
        canonicalFingerprint = canonicalFingerprint,
        problemFamilyFingerprint = problemFamilyFingerprint,
        presentationFingerprint = presentationFingerprint,
        responseForm = responseForm.name,
        independentlyAnswered = independentlyAnswered,
        hintCount = hintCount,
        answerRevealed = answerRevealed,
        elapsedDurationMillis = elapsedDurationMillis,
        verificationKind = verificationKind.name,
        evidenceContextKind = evidenceContextKind.name,
        ephemeralProblemFingerprint = ephemeralProblemFingerprint,
        tutorTurnReferenceId = tutorTurnReferenceId,
        submissionEvidenceFingerprint = submissionEvidenceFingerprint,
        attributionModelVersion = attributionModelVersion,
        authorizedProblemBindingsFingerprint = authorizedProblemBindingsFingerprint,
        authorizedKnowledgeRefsFingerprint = authorizedKnowledgeRefsFingerprint,
        knowledgeManifestFingerprint = knowledgeManifestFingerprint,
        knowledgeActivationGeneration = knowledgeActivationGeneration,
        authorityAttemptFingerprint = authorityAttemptFingerprint,
        authoritySubmissionFingerprint = authoritySubmissionFingerprint,
        authorityPresentationFingerprint = authorityPresentationFingerprint,
        authorityProblemFamilyFingerprint = authorityProblemFamilyFingerprint,
        authorityIdentityVersion = authorityIdentityVersion,
    )

private fun MasterySourceFactEntity.toSourceProof(): MasterySourceProofEntity =
    MasterySourceProofEntity(
        sourceFactId = sourceFactId,
        sourceFactCanonicalFingerprint = canonicalFingerprint,
        sourcePolicyVersion = sourcePolicyVersion,
        policySupported = sourcePolicyVersion == LEARNER_MASTERY_SOURCE_POLICY_VERSION,
        proofFingerprint = LearnerMasteryFingerprint.sourceProof(this),
        createdAtEpochMillis = attestedAtEpochMillis,
    )

private fun MasterySourceFactEntity.asCorrectionRevision(
    command: CorrectLearningEvidenceCommand,
): MasterySourceFactEntity {
    val revisionIdentity =
        CanonicalSha256("learner-mastery-corrected-source-fact-identity-v1")
            .field(
                "originalSourceFactFingerprint",
                command.originalSourceFactCanonicalFingerprint,
            )
            .field("replacementSourceFactFingerprint", canonicalFingerprint)
            .field("correctionCommandFingerprint", command.canonicalFingerprint)
            .finish()
    val correctedCanonicalFingerprint =
        CanonicalSha256("learner-mastery-corrected-source-fact-v1")
            .field("revisionIdentity", revisionIdentity)
            .field("replacementSourceFactFingerprint", canonicalFingerprint)
            .field("correctionCommandFingerprint", command.canonicalFingerprint)
            .finish()
    return copy(
        sourceReferenceId = "mastery-correction-source:${revisionIdentity.take(48)}",
        authorityAttemptFingerprint =
            CanonicalSha256("learner-mastery-corrected-attempt-v1")
                .field("revisionIdentity", revisionIdentity)
                .finish(),
        authoritySubmissionFingerprint =
            CanonicalSha256("learner-mastery-corrected-submission-v1")
                .field("revisionIdentity", revisionIdentity)
                .finish(),
        authorityPresentationFingerprint =
            authorityPresentationFingerprint ?: presentationFingerprint,
        authorityProblemFamilyFingerprint =
            authorityProblemFamilyFingerprint ?: problemFamilyFingerprint,
        authorityIdentityVersion = LEARNER_MASTERY_AUTHORITY_IDENTITY_VERSION,
        idempotencyKey = "mastery-correction-source:$revisionIdentity",
        canonicalFingerprint = correctedCanonicalFingerprint,
    )
}

private fun IngestLearningObservationCandidateCommand.toEntity(
    receivedAtEpochMillis: Long,
): MasteryObservationCandidateEntity =
    MasteryObservationCandidateEntity(
        candidateId = candidateId,
        learnerId = learnerId,
        subject = subject.name,
        sourceFactId = sourceFactId,
        confidence = confidence.name,
        modelVersion = modelVersion,
        requestedPolicyVersion = requestedPolicyVersion,
        proposedAtEpochMillis = proposedAtEpochMillis,
        receivedAtEpochMillis = receivedAtEpochMillis,
        idempotencyKey = idempotencyKey,
        canonicalFingerprint = canonicalFingerprint,
        candidateOrigin = candidateOrigin.name,
    )

private fun ProposedKnowledgeAttribution.toEntity(
    candidateId: String,
    ordinal: Int,
): MasteryCandidateAttributionEntity =
    MasteryCandidateAttributionEntity(
        candidateId = candidateId,
        ordinal = ordinal,
        subject = knowledgeNode.subject.name,
        knowledgeNodeId = knowledgeNode.knowledgeNodeId,
        taxonomyVersion = knowledgeNode.taxonomyVersion,
        knowledgePackVersion = knowledgeNode.knowledgePackVersion,
        knowledgeNodeRefFingerprint = knowledgeNode.canonicalFingerprint,
        problemBindingRefFingerprint = problemBinding?.canonicalFingerprint,
        bindingProblemRevisionRefFingerprint =
            problemBinding?.problemRevision?.canonicalFingerprint,
        role = role.name,
        certainty = certainty.name,
        proposalFingerprint = canonicalFingerprint,
    )

private fun MasteryKnowledgeProjectionEntity.toKnowledgeNodeRef(): KnowledgeNodeRef =
    KnowledgeNodeRef(
        subject = enumValueOrCorrupt(subject, "projection subject"),
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
        knowledgePackVersion = latestEvidenceKnowledgePackVersion,
    )

private fun MasteryKnowledgeProjectionEntity.toLocalMasteryContextItem(
    selection: LocalMasteryContextSelection,
    nowEpochMillis: Long,
): LocalMasteryContextItem =
    LocalMasteryContextItem(
        selection = selection,
        stableNodeIdentityFingerprint = stableNodeIdentityFingerprint,
        knowledgeNode = toKnowledgeNodeRef(),
        historicalState = enumValueOrCorrupt(masteryState, "mastery state"),
        currentRecallState = LocalMasteryPolicy.currentRecallState(this, nowEpochMillis),
        trend = enumValueOrCorrupt(trend, "mastery trend"),
        evidenceQuality = evidenceQualityMicros.toEvidenceQuality(),
    )

private fun MasteryTimelineRow.toTimelineEntry(): SubjectMasteryTimelineEntry {
    val totalMass = positiveMassMicros + negativeMassMicros
    val difference = positiveMassMicros - negativeMassMicros
    val signal =
        when {
            totalMass == 0L -> SubjectMasteryTimelineSignal.MIXED
            difference > totalMass / 5L -> SubjectMasteryTimelineSignal.PROGRESS
            -difference > totalMass / 5L -> SubjectMasteryTimelineSignal.NEEDS_ATTENTION
            else -> SubjectMasteryTimelineSignal.MIXED
        }
    val activity =
        when {
            observationCount <= 2L -> SubjectMasteryTimelineActivity.LIGHT
            observationCount <= 5L -> SubjectMasteryTimelineActivity.REGULAR
            else -> SubjectMasteryTimelineActivity.INTENSIVE
        }
    return SubjectMasteryTimelineEntry(
        utcEpochDay = utcEpochDay,
        signal = signal,
        activity = activity,
        observationCount = observationCount.toBoundedTimelineInt(),
        affectedKnowledgeCount = affectedKnowledgeCount.toBoundedTimelineInt(),
    )
}

private fun Long.toEvidenceQuality(): MasteryEvidenceQuality =
    when {
        this >= 800_000L -> MasteryEvidenceQuality.HIGH
        this >= 500_000L -> MasteryEvidenceQuality.MEDIUM
        this > 0L -> MasteryEvidenceQuality.LOW
        else -> MasteryEvidenceQuality.UNVERIFIABLE
    }

internal inline fun <reified T : Enum<T>> enumValueOrCorrupt(
    value: String,
    label: String,
): T =
    enumValues<T>().firstOrNull { it.name == value }
        ?: error("Corrupt learner-mastery database: unknown $label '$value'")

private fun Long.toBoundedTimelineInt(): Int {
    check(this in 0..Int.MAX_VALUE.toLong()) {
        "Learner-mastery timeline count exceeds the supported range"
    }
    return toInt()
}

internal const val LEGACY_BINDING_PROTOCOL_VERSION = 1
internal const val CURRENT_BINDING_PROTOCOL_VERSION = 2
private const val LEGACY_BINDING_SET_VERSION = 1L
private const val MAX_OUTBOX_READ_LIMIT = 256
private const val MAX_PROBLEM_REFERENCE_RECEIPTS = 64
