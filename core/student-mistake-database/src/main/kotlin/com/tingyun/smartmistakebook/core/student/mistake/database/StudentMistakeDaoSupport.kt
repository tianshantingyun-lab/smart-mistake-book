package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef

internal fun StudentProblemDocumentEntity.sameProblemIdentity(
    other: StudentProblemDocumentEntity,
): Boolean =
    problemId == other.problemId &&
        learnerId == other.learnerId &&
        subject == other.subject &&
        primaryPracticeUnitId == other.primaryPracticeUnitId &&
        createdAtEpochMillis == other.createdAtEpochMillis

internal fun StudentStoreOutboxEntity.sameImmutableOutboxContent(
    other: StudentStoreOutboxEntity,
): Boolean =
    eventId == other.eventId &&
        sourceStore == other.sourceStore &&
        destinationStore == other.destinationStore &&
        aggregateId == other.aggregateId &&
        aggregateVersion == other.aggregateVersion &&
        payloadType == other.payloadType &&
        payloadVersion == other.payloadVersion &&
        payloadCanonicalFingerprint == other.payloadCanonicalFingerprint &&
        payloadWire == other.payloadWire &&
        envelopeCanonicalFingerprint == other.envelopeCanonicalFingerprint &&
        occurredAtEpochMillis == other.occurredAtEpochMillis &&
        idempotencyKey == other.idempotencyKey &&
        sourceStoreGeneration == other.sourceStoreGeneration

internal fun StudentCaptureSaveHandoffEntity.sameImmutableCaptureHandoff(
    other: StudentCaptureSaveHandoffEntity,
): Boolean =
    copy(acknowledgedAtEpochMillis = null) ==
        other.copy(acknowledgedAtEpochMillis = null)

internal fun StudentCaptureSaveHandoffEntity.sameImmutableCaptureSource(
    other: StudentCaptureSaveHandoffEntity,
): Boolean =
    intentId == other.intentId &&
        sourceKind == other.sourceKind &&
        sourceCanonicalFingerprint == other.sourceCanonicalFingerprint &&
        learnerId == other.learnerId &&
        draftId == other.draftId &&
        draftRevisionNumber == other.draftRevisionNumber &&
        sessionId == other.sessionId &&
        basisRevisionNumber == other.basisRevisionNumber &&
        workspaceVersion == other.workspaceVersion &&
        workspaceCanonicalFingerprint == other.workspaceCanonicalFingerprint &&
        confirmationRequestId == other.confirmationRequestId &&
        saveRequestId == other.saveRequestId &&
        occurredAtEpochMillis == other.occurredAtEpochMillis &&
        schemaVersion == other.schemaVersion

internal fun StudentProblemClassificationResultEntity.sameSubmittedClassification(
    submitted: StudentProblemClassificationResultEntity,
): Boolean =
    this == submitted ||
        (
            status == StudentProblemClassificationStatus.SUPERSEDED.name &&
                submitted.status == StudentProblemClassificationStatus.ACCEPTED.name &&
                copy(status = StudentProblemClassificationStatus.ACCEPTED.name) == submitted
        )

internal fun StudentProblemClassificationResultEntity.sameClassificationReference(
    other: StudentProblemClassificationResultEntity,
): Boolean =
    labelId == other.labelId &&
        knowledgeSubject == other.knowledgeSubject &&
        knowledgeNodeId == other.knowledgeNodeId &&
        knowledgeTaxonomyVersion == other.knowledgeTaxonomyVersion &&
        knowledgePackVersion == other.knowledgePackVersion

internal fun StudentMistakeMigrationCheckpointEntity.toMigrationKey(): StudentMistakeMigrationKey? {
    val committedAt = lastCommittedAtEpochMillis ?: return null
    return StudentMistakeMigrationKey(
        committedAtEpochMillis = committedAt,
        problemId = checkNotNull(lastProblemId) {
            "Corrupt migration checkpoint: missing problem id"
        },
        revisionNumber = checkNotNull(lastRevisionNumber) {
            "Corrupt migration checkpoint: missing revision number"
        },
        revisionId = checkNotNull(lastRevisionId) {
            "Corrupt migration checkpoint: missing revision id"
        },
    )
}

internal suspend inline fun <T> List<T>.insertWhenNotEmpty(
    insert: suspend (List<T>) -> Unit,
) {
    if (isNotEmpty()) insert(this)
}


internal const val MAX_STORED_IMAGES = 64
internal const val MAX_STORED_SOLUTION_STEPS = 128
internal const val MAX_STORED_ERROR_ATTRIBUTIONS = 64
internal const val MAX_STORED_ERROR_EVIDENCE = 512
internal const val MAX_STORED_CLASSIFICATIONS = 512
internal const val MAX_STORED_REVIEW_ITEMS = 512
internal const val MAX_SQLITE_BIND_BATCH = 400
internal const val MAX_STORED_REVIEW_REASONS = 16
internal const val LEARNING_ATTEMPT_REASON = "learning-attempt"
internal const val SELF_REPORT_DONE_REASON = "self-report-done"
internal const val SELF_REPORT_STUCK_REASON = "self-report-stuck"

