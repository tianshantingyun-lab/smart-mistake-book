package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.ColumnInfo
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateStatusCasResult
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateStatusChangeCommand
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateWriteResult
import com.tingyun.smartmistakebook.core.database.LEARNING_OBSERVATION_ADMISSION_POLICY_VERSION
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceFactProofFingerprint
import com.tingyun.smartmistakebook.core.database.LearningObservationMaterializationResult
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceAuthorityException
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceAuthorityRecord
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceAuthorityWriteResult
import com.tingyun.smartmistakebook.core.database.MaterializeLearningObservationCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.entity.AttributedLearningObservationEventEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningEvidenceReviewCaseEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningEventIdentityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationCandidateAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationCandidateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAdmissionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceAuthorityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactProofEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.CapturedTutorProblemIdentity
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewCase
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewReason
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewStatus
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.LearningObservationProjectionAdmission
import com.tingyun.smartmistakebook.core.model.SourceFactEvidencePolicy
import com.tingyun.smartmistakebook.core.model.allowedExternalTransitions
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal fun LearningObservationSourceAuthorityRecord.toEntity() =
    LearningObservationSourceAuthorityEntity(
        learnerId = learnerId,
        source = source.name,
        sourceReferenceId = sourceReferenceId,
        practiceUnitId = practiceUnitId,
        problemRevisionId = problemRevisionId,
        sourcePayloadFingerprint = sourcePayloadFingerprint,
        verifiedAtEpochMillis = verifiedAtEpochMillis,
        sourceFactId = sourceFactId,
    )

internal fun LearningObservationSourceAuthorityEntity.toModel() =
    LearningObservationSourceAuthorityRecord(
        learnerId = learnerId,
        source = LearningObservationSource.valueOf(source),
        sourceReferenceId = sourceReferenceId,
        practiceUnitId = practiceUnitId,
        problemRevisionId = problemRevisionId,
        sourcePayloadFingerprint = sourcePayloadFingerprint,
        verifiedAtEpochMillis = verifiedAtEpochMillis,
        sourceFactId = sourceFactId,
    )

internal fun LearningObservationSourceAuthorityRecord.provenanceKey(): String =
    "$learnerId:${source.name}:$sourceReferenceId"

internal fun LearningObservationSourceFactProofEntity.matches(
    sourceFact: LearningObservationSourceFact,
    expectedSourceFingerprint: String,
): Boolean {
    val expectedProofKind = when (sourceFact.source) {
        LearningObservationSource.TUTOR_CHOICE,
        LearningObservationSource.TUTOR_FREE_RESPONSE,
        LearningObservationSource.TUTOR_VISUAL_TARGET,
        LearningObservationSource.TUTOR_SPECIFIC_STUCK,
        -> PROOF_KIND_TUTOR_INTERACTION

        LearningObservationSource.IMPORTED_MISTAKE -> PROOF_KIND_IMPORTED_VISIBLE_ERROR
        LearningObservationSource.CAPTURED_REVIEW_RESPONSE -> PROOF_KIND_CAPTURED_REVIEW
    }
    return sourceFactId == sourceFact.sourceFactId &&
        proofKind == expectedProofKind &&
        learnerId == sourceFact.learnerScopeId &&
        subject == sourceFact.subject.name &&
        anchorId == sourceFact.anchorId &&
        sourceReferenceId == SourceFactEvidencePolicy.canonicalSourceReferenceId(sourceFact) &&
        sourceFingerprint == expectedSourceFingerprint &&
        conversationId == sourceFact.conversationId &&
        conversationGeneration == sourceFact.conversationGeneration &&
        turnReceiptId == sourceFact.turnReceiptId &&
        evidenceRequestId == sourceFact.evidenceRequestId &&
        sourceLocatorKind.isNotBlank() &&
        sourceLocatorId.isNotBlank() &&
        targetKind in
            setOf(TARGET_KIND_COMMITTED_PRACTICE_UNIT, TARGET_KIND_ANCHORED_UNCOLLECTED) &&
        targetDatabase.isNotBlank() &&
        targetId.isNotBlank() &&
        targetVersion.isNotBlank() &&
        targetFingerprint.isNotBlank() &&
        targetCreatedAtEpochMillis >= 0 &&
        attestedAtEpochMillis >= sourceFact.occurredAtEpochMillis &&
        attestedAtEpochMillis >= targetCreatedAtEpochMillis &&
        LearningObservationSourceFactProofFingerprint.isValid(this)
}

internal fun LearningObservationSourceFactProofEntity.matches(
    sourceFact: LearningObservationSourceFact,
    sourceFingerprint: String,
    authority: LearningObservationSourceAuthorityRecord,
): Boolean = matches(sourceFact, sourceFingerprint) &&
    matches(authority)

internal fun LearningObservationSourceFactProofEntity.matches(
    authority: LearningObservationSourceAuthorityRecord,
): Boolean =
    targetKind == TARGET_KIND_COMMITTED_PRACTICE_UNIT &&
    targetDatabase == TARGET_DATABASE_MISTAKE_COLLECTION &&
    targetId == authority.practiceUnitId &&
    targetVersion == authority.problemRevisionId &&
    attestedAtEpochMillis == authority.verifiedAtEpochMillis

internal fun CapturedTutorSourceFactProofCandidateRow.toProof(
    sourceFact: LearningObservationSourceFact,
    sourceFingerprint: String,
    attestedAtEpochMillis: Long,
): LearningObservationSourceFactProofEntity? {
    val expectedRequestKind = when (sourceFact.source) {
        LearningObservationSource.TUTOR_CHOICE -> REQUEST_KIND_CHOICE
        LearningObservationSource.TUTOR_FREE_RESPONSE -> REQUEST_KIND_FREE_RESPONSE
        LearningObservationSource.TUTOR_VISUAL_TARGET -> REQUEST_KIND_VISUAL_TARGET
        else -> return null
    }
    val expectedLocatorKind = when (sourceFact.source) {
        LearningObservationSource.TUTOR_CHOICE -> SOURCE_LOCATOR_TUTOR_RESPONSE
        LearningObservationSource.TUTOR_VISUAL_TARGET -> SOURCE_LOCATOR_TUTOR_VISUAL_EVIDENCE
        LearningObservationSource.TUTOR_FREE_RESPONSE -> return null
        else -> return null
    }
    if (
        sourceFactId != sourceFact.sourceFactId ||
        learnerId != sourceFact.learnerScopeId ||
        subject != sourceFact.subject.name ||
        anchorId != sourceFact.anchorId ||
        sourceReferenceId != SourceFactEvidencePolicy.canonicalSourceReferenceId(sourceFact) ||
        this.sourceFingerprint != sourceFingerprint ||
        conversationId != sourceFact.conversationId ||
        conversationGeneration != sourceFact.conversationGeneration ||
        turnReceiptId != sourceFact.turnReceiptId ||
        evidenceRequestId != sourceFact.evidenceRequestId ||
        source != sourceFact.source.name ||
        requestKind != expectedRequestKind ||
        sourceLocatorKind != expectedLocatorKind ||
        anchorFingerprintVersion != CapturedTutorProblemIdentity.fingerprintVersion ||
        anchorQuestionFingerprint != CapturedTutorProblemIdentity.questionFingerprint(draftId) ||
        anchorRevisionFingerprint != draftRevisionFingerprint ||
        interactionSubmittedAtEpochMillis != sourceFact.occurredAtEpochMillis ||
        targetCreatedAtEpochMillis < 0 ||
        attestedAtEpochMillis < sourceFact.occurredAtEpochMillis ||
        attestedAtEpochMillis < targetCreatedAtEpochMillis
    ) {
        return null
    }
    val proofWithoutFingerprint = LearningObservationSourceFactProofEntity(
        sourceFactId = sourceFact.sourceFactId,
        proofKind = PROOF_KIND_TUTOR_INTERACTION,
        targetKind = TARGET_KIND_COMMITTED_PRACTICE_UNIT,
        learnerId = sourceFact.learnerScopeId,
        subject = sourceFact.subject.name,
        anchorId = sourceFact.anchorId,
        sourceReferenceId = SourceFactEvidencePolicy.canonicalSourceReferenceId(sourceFact),
        sourceFingerprint = sourceFingerprint,
        conversationId = sourceFact.conversationId,
        conversationGeneration = sourceFact.conversationGeneration,
        turnReceiptId = sourceFact.turnReceiptId,
        evidenceRequestId = sourceFact.evidenceRequestId,
        sourceLocatorKind = sourceLocatorKind,
        sourceLocatorId = sourceLocatorId,
        targetDatabase = TARGET_DATABASE_MISTAKE_COLLECTION,
        targetId = targetId,
        targetVersion = targetVersion,
        targetFingerprint = targetFingerprint,
        targetCreatedAtEpochMillis = targetCreatedAtEpochMillis,
        attestedAtEpochMillis = attestedAtEpochMillis,
        proofFingerprint = "",
    )
    return proofWithoutFingerprint.copy(
        proofFingerprint = LearningObservationSourceFactProofFingerprint.of(
            proofWithoutFingerprint,
        ),
    )
}

private const val PROOF_KIND_TUTOR_INTERACTION = "TUTOR_INTERACTION"
private const val PROOF_KIND_IMPORTED_VISIBLE_ERROR = "IMPORTED_VISIBLE_ERROR"
private const val PROOF_KIND_CAPTURED_REVIEW = "CAPTURED_REVIEW"
private const val TARGET_KIND_COMMITTED_PRACTICE_UNIT = "COMMITTED_PRACTICE_UNIT"
private const val TARGET_KIND_ANCHORED_UNCOLLECTED = "ANCHORED_UNCOLLECTED"
private const val TARGET_DATABASE_MISTAKE_COLLECTION = "MISTAKE_COLLECTION"
private const val REQUEST_KIND_CHOICE = "CHOICE"
private const val REQUEST_KIND_FREE_RESPONSE = "FREE_RESPONSE"
private const val REQUEST_KIND_VISUAL_TARGET = "VISUAL_TARGET"
private const val SOURCE_LOCATOR_TUTOR_RESPONSE = "TUTOR_RESPONSE"
private const val SOURCE_LOCATOR_TUTOR_VISUAL_EVIDENCE = "TUTOR_VISUAL_EVIDENCE"

internal fun LearningObservationCandidate.provenanceKey(): String =
    "$learnerId:${source.name}:$sourceReferenceId"

internal fun LearningObservationCandidate.toEntity(
    fingerprint: String,
) = LearningObservationCandidateEntity(
    candidateId = candidateId,
    learnerId = learnerId,
    source = source.name,
    sourceReferenceId = sourceReferenceId,
    sourceFactId = sourceFactId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    direction = direction.name,
    evidenceLevel = evidenceLevel.name,
    evidenceWeight = evidenceWeight,
    independence = independence.name,
    occurredAtEpochMillis = occurredAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    status = status.name,
    retryCount = retryCount,
    payloadFingerprint = fingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun LearningObservationCandidate.toAttributionEntities() =
    proposedAttributions.sortedBy(LearningObservationKnowledgeAttribution::bindingId)
        .mapIndexed { index, attribution ->
            LearningObservationCandidateAttributionEntity(
                candidateId = candidateId,
                ordinal = index,
                bindingId = attribution.bindingId,
                knowledgeNodeId = attribution.knowledgeNodeId,
                weight = attribution.weight,
                basisRevisionId = attribution.basisRevisionId,
                taxonomyVersion = attribution.taxonomyVersion,
                role = attribution.role.name,
                certainty = attribution.certainty.name,
            )
        }

internal fun LearningObservationCandidateEntity.toModel(
    attributions: List<LearningObservationCandidateAttributionEntity>,
) = LearningObservationCandidate(
    candidateId = candidateId,
    learnerId = learnerId,
    source = LearningObservationSource.valueOf(source),
    sourceReferenceId = sourceReferenceId,
    sourceFactId = sourceFactId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    direction = LearningObservationDirection.valueOf(direction),
    evidenceLevel = LearningObservationEvidenceLevel.valueOf(evidenceLevel),
    evidenceWeight = evidenceWeight,
    independence = LearningObservationIndependence.valueOf(independence),
    proposedAttributions = attributions.map {
        LearningObservationKnowledgeAttribution(
            bindingId = it.bindingId,
            knowledgeNodeId = it.knowledgeNodeId,
            weight = it.weight,
            basisRevisionId = it.basisRevisionId,
            taxonomyVersion = it.taxonomyVersion,
            role = EvidenceAttributionRole.valueOf(it.role),
            certainty = EvidenceAttributionCertainty.valueOf(it.certainty),
        )
    },
    occurredAtEpochMillis = occurredAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    status = LearningObservationCandidateStatus.valueOf(status),
    retryCount = retryCount,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

internal fun AttributedLearningObservationEvent.toEntity(
    subject: String,
    fingerprint: String,
) = AttributedLearningObservationEventEntity(
    eventId = eventId,
    candidateId = candidateId,
    sourceFactId = sourceFactId,
    learnerId = learnerId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    subject = subject,
    direction = direction.name,
    evidenceLevel = evidenceLevel.name,
    evidenceWeight = evidenceWeight,
    independence = independence.name,
    occurredAtEpochMillis = occurredAtEpochMillis,
    confirmedAtEpochMillis = confirmedAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    eventSequence = eventSequence,
    canonicalFingerprint = fingerprint,
)

internal fun AttributedLearningObservationEvent.toAttributionEntities() =
    attributions.sortedBy(LearningObservationKnowledgeAttribution::bindingId)
        .mapIndexed { index, attribution ->
            LearningObservationEventAttributionEntity(
                eventId = eventId,
                practiceUnitId = practiceUnitId,
                ordinal = index,
                bindingId = attribution.bindingId,
                knowledgeNodeId = attribution.knowledgeNodeId,
                weight = attribution.weight,
                basisRevisionId = attribution.basisRevisionId,
                taxonomyVersion = attribution.taxonomyVersion,
                role = attribution.role.name,
                certainty = attribution.certainty.name,
            )
        }

internal fun AttributedLearningObservationEventEntity.toModel(
    attributions: List<LearningObservationEventAttributionEntity>,
) = AttributedLearningObservationEvent(
    eventId = eventId,
    candidateId = candidateId,
    sourceFactId = sourceFactId,
    learnerId = learnerId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    direction = LearningObservationDirection.valueOf(direction),
    evidenceLevel = LearningObservationEvidenceLevel.valueOf(evidenceLevel),
    evidenceWeight = evidenceWeight,
    independence = LearningObservationIndependence.valueOf(independence),
    attributions = attributions.map {
        LearningObservationKnowledgeAttribution(
            bindingId = it.bindingId,
            knowledgeNodeId = it.knowledgeNodeId,
            weight = it.weight,
            basisRevisionId = it.basisRevisionId,
            taxonomyVersion = it.taxonomyVersion,
            role = EvidenceAttributionRole.valueOf(it.role),
            certainty = EvidenceAttributionCertainty.valueOf(it.certainty),
        )
    },
    occurredAtEpochMillis = occurredAtEpochMillis,
    confirmedAtEpochMillis = confirmedAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    eventSequence = eventSequence,
)

internal fun AttributedLearningObservationEventEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_LEARNING_OBSERVATION:$eventId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_LEARNING_OBSERVATION,
    eventId = eventId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = confirmedAtEpochMillis,
)

internal fun LearningEvidenceReviewCase.toEntity() = LearningEvidenceReviewCaseEntity(
    reviewCaseId = reviewCaseId,
    candidateId = candidateId,
    learnerId = learnerId,
    proposedEventId = proposedEventId,
    reason = reason.name,
    detail = detail,
    status = status.name,
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
)

internal fun LearningEvidenceReviewCaseEntity.toModel() = LearningEvidenceReviewCase(
    reviewCaseId = reviewCaseId,
    candidateId = candidateId,
    learnerId = learnerId,
    proposedEventId = proposedEventId,
    reason = LearningEvidenceReviewReason.valueOf(reason),
    detail = detail,
    status = LearningEvidenceReviewStatus.valueOf(status),
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
)

internal fun stableReviewCaseId(
    candidateId: String,
    eventId: String,
    reason: LearningEvidenceReviewReason,
): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "$candidateId\u0000$eventId\u0000${reason.name}".toByteArray(StandardCharsets.UTF_8),
    ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "learning-review:$digest"
}

internal suspend fun <T> List<T>.insertWhenNotEmpty(insert: suspend (List<T>) -> Unit) {
    if (isNotEmpty()) insert(this)
}
