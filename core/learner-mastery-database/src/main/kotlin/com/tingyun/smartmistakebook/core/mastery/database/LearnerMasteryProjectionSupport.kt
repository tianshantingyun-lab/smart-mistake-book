package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind

internal fun MasteryKnowledgeProjectionEntity.calibrationBinding() =
    MasteryCalibrationBindingRow(
        subject = subject,
        projectionPolicyVersion = projectionPolicyVersion,
        calibrationVersion = calibrationVersion,
        calibrationProfileId = calibrationProfileId,
        calibrationSnapshotFingerprint = calibrationSnapshotFingerprint,
    )

internal fun MasteryLearningEventEntity.calibrationBinding() =
    MasteryCalibrationBindingRow(
        subject = subject,
        projectionPolicyVersion = projectionPolicyVersion,
        calibrationVersion = calibrationVersion,
        calibrationProfileId = calibrationProfileId,
        calibrationSnapshotFingerprint = calibrationSnapshotFingerprint,
    )

internal fun encodeProjectionRebuildProgress(
    progress: MasteryProjectionRebuildProgress,
): String =
    encodeLengthPrefixedParts(
        listOf(
            progress.sequence.toString(),
            progress.stage.name,
            progress.cursor.learnerId,
            progress.cursor.subject,
            progress.cursor.knowledgeNodeId,
            progress.cursor.taxonomyVersion,
            progress.cursor.protocolVersion,
            progress.cursor.eventSequence.toString(),
            progress.cursor.eventId,
            progress.cursor.ordinal.toString(),
        ),
    )

internal fun MasteryStoreMetadataEntity.toProjectionRebuildProgress(): MasteryProjectionRebuildProgress {
    val metadataPrefix =
        when {
            metadataKey.startsWith(PROJECTION_REBUILD_PROGRESS_V3_METADATA_PREFIX) ->
                PROJECTION_REBUILD_PROGRESS_V3_METADATA_PREFIX
            metadataKey.startsWith(PROJECTION_REBUILD_PROGRESS_V2_METADATA_PREFIX) ->
                PROJECTION_REBUILD_PROGRESS_V2_METADATA_PREFIX
            else -> error("Corrupt learner-mastery projection rebuild progress key")
        }
    val keySequence =
        checkNotNull(
            metadataKey
                .removePrefix(metadataPrefix)
                .toLongOrNull(),
        ) {
            "Corrupt learner-mastery projection rebuild progress sequence"
        }
    val parts = decodeLengthPrefixedParts(metadataValue, expectedPartCount = 10)
    val valueSequence =
        checkNotNull(parts[0].toLongOrNull()) {
            "Corrupt learner-mastery projection rebuild progress value sequence"
        }
    check(keySequence == valueSequence) {
        "Learner-mastery projection rebuild progress key and value differ"
    }
    val stage =
        runCatching { enumValueOf<MasteryProjectionRebuildStage>(parts[1]) }
            .getOrElse { error("Corrupt learner-mastery projection rebuild stage") }
    val eventSequence =
        checkNotNull(parts[7].toLongOrNull()) {
            "Corrupt learner-mastery projection rebuild event-sequence cursor"
        }
    val ordinal =
        checkNotNull(parts[9].toIntOrNull()) {
            "Corrupt learner-mastery projection rebuild ordinal cursor"
        }
    return MasteryProjectionRebuildProgress(
        sequence = keySequence,
        stage = stage,
        cursor =
            MasteryProjectionRebuildCursor(
                learnerId = parts[2],
                subject = parts[3],
                knowledgeNodeId = parts[4],
                taxonomyVersion = parts[5],
                protocolVersion = parts[6],
                eventSequence = eventSequence,
                eventId = parts[8],
                ordinal = ordinal,
            ),
    )
}

private fun encodeLengthPrefixedParts(parts: List<String>): String =
    buildString {
        parts.forEach { part ->
            append(part.length)
            append(':')
            append(part)
        }
    }

private fun decodeLengthPrefixedParts(
    encoded: String,
    expectedPartCount: Int,
): List<String> {
    val parts = ArrayList<String>(expectedPartCount)
    var offset = 0
    repeat(expectedPartCount) {
        val separator = encoded.indexOf(':', startIndex = offset)
        check(separator > offset) {
            "Corrupt learner-mastery projection rebuild progress encoding"
        }
        val length =
            checkNotNull(encoded.substring(offset, separator).toIntOrNull()) {
                "Corrupt learner-mastery projection rebuild progress length"
            }
        check(length >= 0) {
            "Learner-mastery projection rebuild progress length is negative"
        }
        val valueStart = separator + 1
        val valueEnd = valueStart + length
        check(valueEnd in valueStart..encoded.length) {
            "Learner-mastery projection rebuild progress is truncated"
        }
        parts += encoded.substring(valueStart, valueEnd)
        offset = valueEnd
    }
    check(offset == encoded.length) {
        "Learner-mastery projection rebuild progress has trailing data"
    }
    return parts
}

internal fun LearningObservationIngestResult.toModelSubmissionTerminalReason():
    ModelSubmissionTerminalReason =
    when (disposition) {
        LearningObservationDisposition.ADMITTED ->
            ModelSubmissionTerminalReason.ADMITTED
        LearningObservationDisposition.DUPLICATE ->
            ModelSubmissionTerminalReason.DUPLICATE
        LearningObservationDisposition.INERT ->
            enumValueOf<ModelSubmissionTerminalReason>(
                checkNotNull(inertReason).name,
            )
        LearningObservationDisposition.CONFLICT ->
            ModelSubmissionTerminalReason.CANDIDATE_IDEMPOTENCY_CONFLICT
    }

internal fun modelSubmissionAttemptReceipt(
    attempt: ModelSubmissionAttempt,
    terminalReason: ModelSubmissionTerminalReason,
    candidateId: String?,
    admissionReceiptFingerprint: String?,
    receivedAtEpochMillis: Long,
    primary: Boolean,
): MasteryModelSubmissionAttemptReceiptEntity {
    require(receivedAtEpochMillis >= 0L) {
        "Model submission receipt time must not be negative"
    }
    require((candidateId == null) == (admissionReceiptFingerprint == null)) {
        "Candidate and admission receipt links must be present together"
    }
    val scope = attempt.scope
    val primaryAttemptKey = scope.logicalRequestFingerprint.takeIf { primary }
    val receiptFingerprint =
        CanonicalSha256("learner-mastery-model-submission-attempt-receipt-v1")
            .field("requestGeneration", scope.requestGenerationFingerprint)
            .nullableField("primaryAttemptKey", primaryAttemptKey)
            .field("learnerId", scope.learnerId)
            .field("subject", scope.subject.name)
            .field("sourceFactId", scope.sourceFactId)
            .field("modelVersion", scope.modelVersion)
            .field("requestVersion", scope.requestVersion)
            .field("modeVersion", attempt.modeVersion)
            .field("proposalFingerprint", attempt.proposalFingerprint)
            .field("terminalReason", terminalReason.name)
            .nullableField("candidateId", candidateId)
            .nullableField(
                "admissionReceiptFingerprint",
                admissionReceiptFingerprint,
            )
            .field("receivedAtEpochMillis", receivedAtEpochMillis)
            .finish()
    return MasteryModelSubmissionAttemptReceiptEntity(
        receiptFingerprint = receiptFingerprint,
        requestGenerationFingerprint = scope.requestGenerationFingerprint,
        primaryAttemptKey = primaryAttemptKey,
        learnerId = scope.learnerId,
        subject = scope.subject.name,
        sourceFactId = scope.sourceFactId,
        modelVersion = scope.modelVersion,
        requestVersion = scope.requestVersion,
        modeVersion = attempt.modeVersion,
        proposalFingerprint = attempt.proposalFingerprint,
        terminalReason = terminalReason.name,
        candidateId = candidateId,
        admissionReceiptFingerprint = admissionReceiptFingerprint,
        receivedAtEpochMillis = receivedAtEpochMillis,
    )
}

internal fun inertReceipt(
    candidate: MasteryObservationCandidateEntity,
    sourceProof: MasterySourceProofEntity?,
    reason: LearningObservationInertReason,
    decidedAtEpochMillis: Long,
): MasteryAdmissionReceiptEntity {
    val disposition = LearningObservationDisposition.INERT.name
    return MasteryAdmissionReceiptEntity(
        candidateId = candidate.candidateId,
        candidateCanonicalFingerprint = candidate.canonicalFingerprint,
        learnerId = candidate.learnerId,
        disposition = disposition,
        inertReason = reason.name,
        sourceProofFingerprint = sourceProof?.proofFingerprint,
        policyVersion = candidate.requestedPolicyVersion,
        admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
        calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
        eventId = null,
        receiptFingerprint =
            LearnerMasteryFingerprint.receipt(
                candidate = candidate,
                disposition = disposition,
                inertReason = reason.name,
                sourceProofFingerprint = sourceProof?.proofFingerprint,
                eventId = null,
                decidedAtEpochMillis = decidedAtEpochMillis,
            ),
        decidedAtEpochMillis = decidedAtEpochMillis,
    )
}

internal fun admittedReceipt(
    candidate: MasteryObservationCandidateEntity,
    sourceProof: MasterySourceProofEntity,
    eventId: String,
    decidedAtEpochMillis: Long,
): MasteryAdmissionReceiptEntity {
    val disposition = LearningObservationDisposition.ADMITTED.name
    return MasteryAdmissionReceiptEntity(
        candidateId = candidate.candidateId,
        candidateCanonicalFingerprint = candidate.canonicalFingerprint,
        learnerId = candidate.learnerId,
        disposition = disposition,
        inertReason = null,
        sourceProofFingerprint = sourceProof.proofFingerprint,
        policyVersion = candidate.requestedPolicyVersion,
        admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
        calibrationVersion = LEARNER_MASTERY_CALIBRATION_VERSION,
        eventId = eventId,
        receiptFingerprint =
            LearnerMasteryFingerprint.receipt(
                candidate = candidate,
                disposition = disposition,
                inertReason = null,
                sourceProofFingerprint = sourceProof.proofFingerprint,
                eventId = eventId,
                decidedAtEpochMillis = decidedAtEpochMillis,
            ),
        decidedAtEpochMillis = decidedAtEpochMillis,
    )
}

internal fun MasteryAdmissionReceiptEntity.toTerminalReceipt(): LearningObservationTerminalReceipt {
    val terminalDisposition =
        runCatching { enumValueOf<LearningObservationDisposition>(disposition) }
            .getOrElse { error("Persisted mastery admission receipt has an invalid disposition") }
    val terminalInertReason =
        inertReason?.let { persistedReason ->
            runCatching { enumValueOf<LearningObservationInertReason>(persistedReason) }
                .getOrElse {
                    error("Persisted mastery admission receipt has an invalid inert reason")
                }
        }
    return LearningObservationTerminalReceipt.create(
        candidateId = candidateId,
        candidateCanonicalFingerprint = candidateCanonicalFingerprint,
        disposition = terminalDisposition,
        inertReason = terminalInertReason,
        receiptFingerprint = receiptFingerprint,
    )
}

internal fun evidenceReviewCase(
    candidate: MasteryObservationCandidateEntity,
    sourceFact: MasterySourceFactEntity,
    sourceProof: MasterySourceProofEntity,
    reason: LearningObservationInertReason,
    calibrationSnapshot: MasteryCalibrationSnapshotEntity,
    createdAtEpochMillis: Long,
): MasteryEvidenceReviewCaseEntity {
    val fingerprint =
        LearnerMasteryFingerprint.reviewCase(
            candidate = candidate,
            sourceProof = sourceProof,
            reason = reason,
            calibrationSnapshot = calibrationSnapshot,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    return MasteryEvidenceReviewCaseEntity(
        reviewCaseId = "mrc:${fingerprint.take(48)}",
        candidateId = candidate.candidateId,
        candidateCanonicalFingerprint = candidate.canonicalFingerprint,
        sourceFactId = sourceFact.sourceFactId,
        sourceProofFingerprint = sourceProof.proofFingerprint,
        learnerId = candidate.learnerId,
        subject = candidate.subject,
        reason = reason.name,
        admissionPolicyVersion = LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
        calibrationBindingStatus = MasteryCalibrationBindingStatus.BOUND.name,
        calibrationVersion = calibrationSnapshot.calibrationVersion,
        calibrationProfileId = calibrationSnapshot.profileId,
        calibrationSnapshotFingerprint = calibrationSnapshot.snapshotFingerprint,
        reviewCaseFingerprint = fingerprint,
        createdAtEpochMillis = createdAtEpochMillis,
    )
}

internal fun evidenceReviewResolution(
    reviewCase: MasteryEvidenceReviewCaseEntity,
    command: ResolveLearningEvidenceReviewCommand,
    calibrationSnapshot: MasteryCalibrationSnapshotEntity,
    decidedAtEpochMillis: Long,
): MasteryEvidenceReviewResolutionEntity {
    val fingerprint =
        CanonicalSha256("learner-mastery-evidence-review-resolution-v1")
            .field("reviewCaseFingerprint", reviewCase.reviewCaseFingerprint)
            .field("decision", command.decision.name)
            .field("authority", command.authority.name)
            .field("reviewerVersion", command.reviewerVersion)
            .field("reviewEvidenceFingerprint", command.reviewEvidenceFingerprint)
            .field("calibrationProfileId", calibrationSnapshot.profileId)
            .field(
                "calibrationSnapshotFingerprint",
                calibrationSnapshot.snapshotFingerprint,
            )
            .field("decidedAtEpochMillis", decidedAtEpochMillis)
            .finish()
    return MasteryEvidenceReviewResolutionEntity(
        resolutionId = "mrr:${fingerprint.take(48)}",
        reviewCaseId = reviewCase.reviewCaseId,
        learnerId = reviewCase.learnerId,
        subject = reviewCase.subject,
        decision = command.decision.name,
        authority = command.authority.name,
        reviewerVersion = command.reviewerVersion,
        reviewEvidenceFingerprint = command.reviewEvidenceFingerprint,
        calibrationSnapshotFingerprint = calibrationSnapshot.snapshotFingerprint,
        idempotencyKey = command.idempotencyKey,
        resolutionFingerprint = fingerprint,
        decidedAtEpochMillis = decidedAtEpochMillis,
    )
}

internal fun learningEvidenceSupersession(
    originalFact: MasterySourceFactEntity,
    originalEvent: MasteryLearningEventEntity,
    replacementFact: MasterySourceFactEntity,
    replacementCandidate: MasteryObservationCandidateEntity,
    command: CorrectLearningEvidenceCommand,
): MasteryLearningEvidenceSupersessionEntity {
    val replacementEventId = LearnerMasteryFingerprint.eventId(replacementCandidate)
    val fingerprint =
        CanonicalSha256("learner-mastery-learning-evidence-supersession-v1")
            .field("learnerId", originalFact.learnerId)
            .field("subject", originalFact.subject)
            .field(
                "originalSourceFactFingerprint",
                originalFact.canonicalFingerprint,
            )
            .field("originalEventFingerprint", originalEvent.canonicalFingerprint)
            .field(
                "replacementSourceFactFingerprint",
                replacementFact.canonicalFingerprint,
            )
            .field(
                "replacementCandidateFingerprint",
                replacementCandidate.canonicalFingerprint,
            )
            .field("replacementEventId", replacementEventId)
            .field("authority", command.authority.name)
            .field("authorityVersion", command.authorityVersion)
            .field(
                "correctionEvidenceFingerprint",
                command.correctionEvidenceFingerprint,
            )
            .field("supersededAtEpochMillis", command.correctedAtEpochMillis)
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
        replacementSourceFactCanonicalFingerprint = replacementFact.canonicalFingerprint,
        replacementCandidateId = replacementCandidate.candidateId,
        replacementCandidateCanonicalFingerprint = replacementCandidate.canonicalFingerprint,
        replacementEventId = replacementEventId,
        authority = command.authority.name,
        authorityVersion = command.authorityVersion,
        correctionEvidenceFingerprint = command.correctionEvidenceFingerprint,
        idempotencyKey = command.idempotencyKey,
        canonicalFingerprint = fingerprint,
        supersededAtEpochMillis = command.correctedAtEpochMillis,
    )
}

internal fun projectionFingerprint(projection: MasteryKnowledgeProjectionEntity): String =
    CanonicalSha256("learner-mastery-projection-state-v2")
        .field("learnerId", projection.learnerId)
        .field("stableNodeIdentity", projection.stableNodeIdentityFingerprint)
        .field(
            "latestEvidenceKnowledgePackVersion",
            projection.latestEvidenceKnowledgePackVersion,
        )
        .field("positiveEvidenceMicros", projection.positiveEvidenceMicros)
        .field("negativeEvidenceMicros", projection.negativeEvidenceMicros)
        .field("masteryScoreMicros", projection.masteryScoreMicros)
        .field("masteryState", projection.masteryState)
        .field("trend", projection.trend)
        .field("observationCount", projection.observationCount)
        .field("memoryStabilityMillis", projection.memoryStabilityMillis)
        .field("recallDueAtEpochMillis", projection.recallDueAtEpochMillis)
        .field("evidenceQualityMicros", projection.evidenceQualityMicros)
        .field(
            "independentProblemFamilyCount",
            projection.independentProblemFamilyCount,
        )
        .field("distinctPresentationCount", projection.distinctPresentationCount)
        .nullableField(
            "historicalLogOddsMicros",
            projection.historicalLogOddsMicros?.toString(),
        )
        .nullableField(
            "calibrationSnapshotFingerprint",
            projection.calibrationSnapshotFingerprint,
        )
        .nullableField("calibrationProfileId", projection.calibrationProfileId)
        .nullableField("calibrationVersion", projection.calibrationVersion)
        .nullableField(
            "recallFamiliarizingAtEpochMillis",
            projection.recallFamiliarizingAtEpochMillis?.toString(),
        )
        .nullableField(
            "recallReinforcementAtEpochMillis",
            projection.recallReinforcementAtEpochMillis?.toString(),
        )
        .field("lastEventSequence", projection.lastEventSequence)
        .field("lastOrderedEventId", projection.lastOrderedEventId)
        .field("policyVersion", projection.projectionPolicyVersion)
        .finish()

internal fun MasteryKnowledgeProjectionEntity.withEvidenceDimensions(
    dimensions: MasteryProjectionEvidenceDimensionsRow,
): MasteryKnowledgeProjectionEntity {
    check(dimensions.evidenceEventCount > 0L) {
        "Learner-mastery projection has no immutable evidence events"
    }
    val quality =
        (dimensions.evidenceQualitySumMicros ?: 0L) /
            dimensions.evidenceEventCount
    return copy(
        evidenceQualityMicros =
            quality.coerceIn(0L, LocalMasteryPolicy.FULL_MASS_MICROS),
        independentProblemFamilyCount = dimensions.independentProblemFamilyCount,
        distinctPresentationCount = dimensions.distinctPresentationCount,
    )
}

internal fun MasteryProjectionNodeEvidenceDimensionsRow.toProjectionDimensions():
    MasteryProjectionEvidenceDimensionsRow =
    MasteryProjectionEvidenceDimensionsRow(
        evidenceQualitySumMicros = evidenceQualitySumMicros,
        evidenceEventCount = evidenceEventCount,
        independentProblemFamilyCount = independentProblemFamilyCount,
        distinctPresentationCount = distinctPresentationCount,
    )

internal fun MasteryEventAttributionReplayRow.replayKey(): MasteryProjectionReplayKey =
    MasteryProjectionReplayKey(
        learnerId = learnerId,
        subject = subject,
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
    )

internal fun MasteryProjectionShadowEntity.replayKey(): MasteryProjectionReplayKey =
    MasteryProjectionReplayKey(
        learnerId = learnerId,
        subject = subject,
        knowledgeNodeId = knowledgeNodeId,
        taxonomyVersion = taxonomyVersion,
    )

internal fun MasteryLearningEventEntity.isBeforeProjectionTail(
    projection: MasteryKnowledgeProjectionEntity,
): Boolean =
    occurredAtEpochMillis < projection.lastEvidenceAtEpochMillis ||
        (
            occurredAtEpochMillis == projection.lastEvidenceAtEpochMillis &&
                eventId < projection.lastOrderedEventId
            )

internal fun MasteryLegacyFactMigrationCheckpointEntity.sameMigrationIdentity(
    other: MasteryLegacyFactMigrationCheckpointEntity,
): Boolean =
    learnerId == other.learnerId &&
        sourceGeneration == other.sourceGeneration &&
        batchSequence == other.batchSequence &&
        batchFingerprint == other.batchFingerprint &&
        observationCount == other.observationCount &&
        finalBatch == other.finalBatch &&
        sourcePolicyVersion == other.sourcePolicyVersion &&
        projectionPolicyVersion == other.projectionPolicyVersion

internal fun applicationFingerprint(
    event: MasteryLearningEventEntity,
    projectionFingerprints: List<String>,
): String {
    val digest = CanonicalSha256("learner-mastery-event-application-v1")
        .field("eventFingerprint", event.canonicalFingerprint)
        .field("eventSequence", event.eventSequence)
        .field("policyVersion", event.projectionPolicyVersion)
        .field("projectionCount", projectionFingerprints.size)
    projectionFingerprints.sorted().forEachIndexed { index, fingerprint ->
        digest.field("projection[$index]", fingerprint)
    }
    return digest.finish()
}

internal fun outboxFor(
    event: MasteryLearningEventEntity,
    sourceFact: MasterySourceFactEntity,
    createdAtEpochMillis: Long,
    storeGeneration: String,
): MasteryCrossStoreOutboxEntity? {
    val problemRevision =
        sourceFact.toStudentProblemRevisionRefOrNull()
            ?: return null
    val reviewSessionId = sourceFact.reviewSessionId ?: return null
    val reviewQueueItemId = sourceFact.reviewQueueItemId ?: return null
    val reviewSubmissionId = sourceFact.reviewSubmissionId ?: return null
    val evidence =
        LearningEvidenceRef(
            learnerId = event.learnerId,
            eventKind = "MASTERY_LEARNING_EVENT",
            eventId = event.eventId,
            eventSequence = event.eventSequence,
            eventCanonicalFingerprint = event.canonicalFingerprint,
        )
    val payload =
        LearningAttemptRecordedV1(
            evidence = evidence,
            problemRevision = problemRevision,
            reviewSessionId = reviewSessionId,
            reviewQueueItemId = reviewQueueItemId,
            submissionId = reviewSubmissionId,
            presentationId = sourceFact.presentationId,
            recordedAtEpochMillis = createdAtEpochMillis,
        )
    val idempotencyKey = "learning-attempt:${event.eventId}"
    val envelope =
        CrossStoreEventEnvelope(
            eventId = "outbox:${event.eventId}",
            sourceStore = StudyStoreKind.LEARNER_MASTERY,
            destinationStore = StudyStoreKind.STUDENT_MISTAKES,
            aggregateId = payload.aggregateId,
            aggregateVersion = event.eventSequence,
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
            idempotencyKey = idempotencyKey,
            sourceStoreGeneration = storeGeneration,
            payload = payload,
        )
    return MasteryCrossStoreOutboxEntity(
        eventId = envelope.eventId,
        sourceStore = envelope.sourceStore.name,
        sourceStoreGeneration = envelope.sourceStoreGeneration,
        destinationStore = envelope.destinationStore.name,
        aggregateId = envelope.aggregateId,
        aggregateVersion = envelope.aggregateVersion,
        payloadType = envelope.payloadType,
        payloadVersion = envelope.payloadVersion,
        learningEvidenceRefFingerprint = evidence.canonicalFingerprint,
        problemRevisionRefFingerprint = problemRevision.canonicalFingerprint,
        payloadCanonicalFingerprint = envelope.payloadCanonicalFingerprint,
        payloadWire = LearnerMasteryCrossStoreCodec.encode(payload),
        envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
        idempotencyKey = idempotencyKey,
        createdAtEpochMillis = createdAtEpochMillis,
        occurredAtEpochMillis = envelope.occurredAtEpochMillis,
        publishedAtEpochMillis = null,
        learnerId = event.learnerId,
    )
}

internal fun MasterySourceFactEntity.toStudentProblemRevisionRefOrNull(): StudentProblemRevisionRef? {
    val revisionId = problemRevisionId ?: return null
    val problemId = problemId ?: return null
    val practiceUnitId = practiceUnitId ?: return null
    val revisionNumber = problemRevisionNumber ?: return null
    val documentFingerprint = problemDocumentFingerprint ?: return null
    val revision =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = learnerId,
                    subject = enumValueOrCorrupt(subject, "source fact subject"),
                    problemId = problemId,
                    practiceUnitId = practiceUnitId,
                ),
            revisionId = revisionId,
            revisionNumber = revisionNumber,
            documentCanonicalFingerprint = documentFingerprint,
        )
    check(revision.canonicalFingerprint == problemRevisionRefFingerprint) {
        "Corrupt learner-mastery source fact: problem revision fingerprint mismatch"
    }
    return revision
}

internal fun Long.toBoundedInt(): Int {
    check(this in 0..Int.MAX_VALUE.toLong()) {
        "Learner-mastery count exceeds the supported range"
    }
    return toInt()
}
