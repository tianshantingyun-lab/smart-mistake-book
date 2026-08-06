package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

@Entity(tableName = "student_mastery_relay_source_binding")
internal data class StudentMasteryRelaySourceBindingEntity(
    @PrimaryKey @ColumnInfo(name = "learner_id") val learnerId: String,
    @ColumnInfo(name = "source_store") val sourceStore: String,
    @ColumnInfo(name = "source_store_generation") val sourceStoreGeneration: String,
    @ColumnInfo(name = "relay_epoch") val relayEpoch: String,
    @ColumnInfo(name = "issuer_key_id") val issuerKeyId: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "first_verification_receipt_canonical_fingerprint")
    val firstVerificationReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "pinned_at_epoch_millis") val pinnedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_authenticated_mastery_inbox_receipt",
    foreignKeys = [
        ForeignKey(
            entity = StudentStoreInboxEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["learner_id", "received_at_epoch_millis"]),
        Index(value = ["source_store_generation", "relay_epoch"]),
        Index(value = ["proof_canonical_fingerprint"], unique = true),
        Index(value = ["verification_receipt_canonical_fingerprint"], unique = true),
        Index(value = ["problem_id", "problem_revision_id", "event_sequence"]),
    ],
)
internal data class StudentAuthenticatedMasteryInboxReceiptEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "learner_id") val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "problem_id") val problemId: String,
    @ColumnInfo(name = "problem_revision_id") val problemRevisionId: String,
    @ColumnInfo(name = "event_sequence") val eventSequence: Long,
    @ColumnInfo(name = "review_session_id") val reviewSessionId: String,
    @ColumnInfo(name = "review_queue_item_id") val reviewQueueItemId: String,
    @ColumnInfo(name = "submission_id") val submissionId: String,
    @ColumnInfo(name = "presentation_id") val presentationId: String,
    @ColumnInfo(name = "scope_canonical_fingerprint") val scopeCanonicalFingerprint: String,
    @ColumnInfo(name = "source_store") val sourceStore: String,
    @ColumnInfo(name = "source_store_generation") val sourceStoreGeneration: String,
    @ColumnInfo(name = "relay_epoch") val relayEpoch: String,
    @ColumnInfo(name = "issuer_key_id") val issuerKeyId: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "proof_canonical_fingerprint") val proofCanonicalFingerprint: String,
    @ColumnInfo(name = "verification_receipt_canonical_fingerprint")
    val verificationReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "recorded_at_epoch_millis") val recordedAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis") val receivedAtEpochMillis: Long,
)

@Entity(
    tableName = "student_pre_auth_inbox_quarantine",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["envelope_canonical_fingerprint"], unique = true),
        Index(value = ["quarantined_at_epoch_millis"]),
    ],
)
internal data class StudentPreAuthInboxQuarantineEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "source_store") val sourceStore: String,
    @ColumnInfo(name = "destination_store") val destinationStore: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "aggregate_version") val aggregateVersion: Long,
    @ColumnInfo(name = "payload_type") val payloadType: String,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int,
    @ColumnInfo(name = "payload_canonical_fingerprint") val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_wire") val payloadWire: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "source_store_generation") val sourceStoreGeneration: String,
    @ColumnInfo(name = "legacy_apply_state") val legacyApplyState: String,
    @ColumnInfo(name = "received_at_epoch_millis") val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "legacy_applied_at_epoch_millis") val legacyAppliedAtEpochMillis: Long?,
    @ColumnInfo(name = "quarantined_at_epoch_millis") val quarantinedAtEpochMillis: Long,
    @ColumnInfo(name = "quarantine_reason") val quarantineReason: String,
)

@Entity(
    tableName = "student_mastery_relay_reauthorization_case",
    indices = [
        Index(value = ["learner_id", "detected_at_epoch_millis"]),
        Index(value = ["candidate_verification_receipt_canonical_fingerprint"], unique = true),
    ],
)
internal data class StudentMasteryRelayReauthorizationCaseEntity(
    @PrimaryKey @ColumnInfo(name = "case_id") val caseId: String,
    @ColumnInfo(name = "learner_id") val learnerId: String,
    @ColumnInfo(name = "bound_source_store_generation") val boundSourceStoreGeneration: String,
    @ColumnInfo(name = "bound_relay_epoch") val boundRelayEpoch: String,
    @ColumnInfo(name = "bound_issuer_key_id") val boundIssuerKeyId: String,
    @ColumnInfo(name = "candidate_source_store_generation")
    val candidateSourceStoreGeneration: String,
    @ColumnInfo(name = "candidate_relay_epoch") val candidateRelayEpoch: String,
    @ColumnInfo(name = "candidate_issuer_key_id") val candidateIssuerKeyId: String,
    @ColumnInfo(name = "candidate_algorithm_version") val candidateAlgorithmVersion: String,
    @ColumnInfo(name = "candidate_verification_receipt_canonical_fingerprint")
    val candidateVerificationReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "detected_at_epoch_millis") val detectedAtEpochMillis: Long,
    @ColumnInfo(name = "reason") val reason: String,
)

@Entity(
    tableName = "student_mastery_relay_reauthorization_resolution",
    foreignKeys = [
        ForeignKey(
            entity = StudentMasteryRelayReauthorizationCaseEntity::class,
            parentColumns = ["case_id"],
            childColumns = ["case_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["case_id"], unique = true)],
)
internal data class StudentMasteryRelayReauthorizationResolutionEntity(
    @PrimaryKey @ColumnInfo(name = "resolution_id") val resolutionId: String,
    @ColumnInfo(name = "case_id") val caseId: String,
    @ColumnInfo(name = "authorization_canonical_fingerprint")
    val authorizationCanonicalFingerprint: String,
    @ColumnInfo(name = "authorized_at_epoch_millis") val authorizedAtEpochMillis: Long,
)

enum class StudentMasteryRelayReauthenticationState {
    TRUSTED,
    REAUTHENTICATION_REQUIRED,
}

data class StudentMasteryRelayReauthenticationStatus(
    val state: StudentMasteryRelayReauthenticationState,
    val caseId: String?,
)

class StudentMasteryRelayReauthorizationCommand(
    val caseId: String,
    val expectedBoundSourceStoreGeneration: String,
    val expectedBoundRelayEpoch: String,
    val expectedBoundIssuerKeyId: String,
    val candidateSourceStoreGeneration: String,
    val candidateRelayEpoch: String,
    val candidateIssuerKeyId: String,
    val candidateAlgorithmVersion: String,
    val authorizationCanonicalFingerprint: String,
    val authorizedAtEpochMillis: Long,
) {
    init {
        listOf(
            caseId,
            expectedBoundSourceStoreGeneration,
            expectedBoundRelayEpoch,
            expectedBoundIssuerKeyId,
            candidateSourceStoreGeneration,
            candidateRelayEpoch,
            candidateIssuerKeyId,
            candidateAlgorithmVersion,
        ).forEach { require(it.isNotBlank() && it == it.trim() && it.length <= 256) }
        require(Regex("[0-9a-f]{64}").matches(authorizationCanonicalFingerprint))
        require(authorizedAtEpochMillis >= 0L)
    }

    internal val canonicalFingerprint: String =
        CanonicalSha256("student-mastery-relay-reauthorization-command-v1")
            .field("caseId", caseId)
            .field("expectedBoundSourceStoreGeneration", expectedBoundSourceStoreGeneration)
            .field("expectedBoundRelayEpoch", expectedBoundRelayEpoch)
            .field("expectedBoundIssuerKeyId", expectedBoundIssuerKeyId)
            .field("candidateSourceStoreGeneration", candidateSourceStoreGeneration)
            .field("candidateRelayEpoch", candidateRelayEpoch)
            .field("candidateIssuerKeyId", candidateIssuerKeyId)
            .field("candidateAlgorithmVersion", candidateAlgorithmVersion)
            .field("authorizationCanonicalFingerprint", authorizationCanonicalFingerprint)
            .field("authorizedAtEpochMillis", authorizedAtEpochMillis)
            .finish()
}

internal fun masteryAttemptScopeFingerprint(
    learnerId: String,
    subject: String,
    problemId: String,
    problemRevisionId: String,
    eventSequence: Long,
    reviewSessionId: String,
    reviewQueueItemId: String,
    submissionId: String,
    presentationId: String,
): String =
    CanonicalSha256("student-authenticated-mastery-attempt-scope-v1")
        .field("learnerId", learnerId)
        .field("subject", subject)
        .field("problemId", problemId)
        .field("problemRevisionId", problemRevisionId)
        .field("eventSequence", eventSequence)
        .field("reviewSessionId", reviewSessionId)
        .field("reviewQueueItemId", reviewQueueItemId)
        .field("submissionId", submissionId)
        .field("presentationId", presentationId)
        .finish()
