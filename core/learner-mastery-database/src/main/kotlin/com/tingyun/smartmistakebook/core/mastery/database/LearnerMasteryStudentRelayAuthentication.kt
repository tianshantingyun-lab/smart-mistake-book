package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** First verified student-store identity accepted by this learner-bound mastery store. */
@Entity(tableName = "mastery_student_relay_source_binding")
internal data class MasteryStudentRelaySourceBindingEntity(
    @PrimaryKey
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "source_store")
    val sourceStore: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "relay_epoch")
    val relayEpoch: String,
    @ColumnInfo(name = "algorithm_version")
    val algorithmVersion: String,
    @ColumnInfo(name = "first_issuer_key_id")
    val firstIssuerKeyId: String,
    @ColumnInfo(name = "first_verification_receipt_canonical_fingerprint")
    val firstVerificationReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "pinned_at_epoch_millis")
    val pinnedAtEpochMillis: Long,
)

/** Durable destination receipt for the exact owner-verified student delivery. */
@Entity(
    tableName = "mastery_authenticated_student_inbox_receipt",
    foreignKeys = [
        ForeignKey(
            entity = MasteryCrossStoreInboxEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["learner_id", "received_at_epoch_millis"]),
        Index(value = ["source_store_generation", "relay_epoch"]),
        Index(value = ["proof_canonical_fingerprint"], unique = true),
        Index(value = ["verification_receipt_canonical_fingerprint"], unique = true),
    ],
)
internal data class MasteryAuthenticatedStudentInboxReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "source_store")
    val sourceStore: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "relay_epoch")
    val relayEpoch: String,
    @ColumnInfo(name = "issuer_key_id")
    val issuerKeyId: String,
    @ColumnInfo(name = "algorithm_version")
    val algorithmVersion: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "proof_canonical_fingerprint")
    val proofCanonicalFingerprint: String,
    @ColumnInfo(name = "verification_receipt_canonical_fingerprint")
    val verificationReceiptCanonicalFingerprint: String,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
)

/** Exact archive of inbox rows that predate cryptographic source verification. */
@Entity(
    tableName = "mastery_pre_auth_student_inbox_quarantine",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["envelope_canonical_fingerprint"], unique = true),
        Index(value = ["quarantined_at_epoch_millis"]),
    ],
)
internal data class MasteryPreAuthStudentInboxQuarantineEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "source_store")
    val sourceStore: String,
    @ColumnInfo(name = "destination_store")
    val destinationStore: String,
    @ColumnInfo(name = "aggregate_id")
    val aggregateId: String,
    @ColumnInfo(name = "aggregate_version")
    val aggregateVersion: Long,
    @ColumnInfo(name = "payload_type")
    val payloadType: String,
    @ColumnInfo(name = "payload_version")
    val payloadVersion: Int,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_wire")
    val payloadWire: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "source_store_generation")
    val sourceStoreGeneration: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "received_at_epoch_millis")
    val receivedAtEpochMillis: Long,
    @ColumnInfo(name = "processed_at_epoch_millis")
    val processedAtEpochMillis: Long?,
    @ColumnInfo(name = "quarantined_at_epoch_millis")
    val quarantinedAtEpochMillis: Long,
    @ColumnInfo(name = "quarantine_reason")
    val quarantineReason: String,
)

internal fun VerifiedStudentMistakeDelivery.toSourceBindingEntity(
    pinnedAtEpochMillis: Long,
): MasteryStudentRelaySourceBindingEntity =
    MasteryStudentRelaySourceBindingEntity(
        learnerId = learnerId(),
        sourceStore = envelope().sourceStore.name,
        sourceStoreGeneration = sourceStoreGeneration(),
        relayEpoch = relayEpoch(),
        algorithmVersion = algorithmVersion(),
        firstIssuerKeyId = issuerKeyId(),
        firstVerificationReceiptCanonicalFingerprint =
            verificationReceiptCanonicalFingerprint(),
        pinnedAtEpochMillis = pinnedAtEpochMillis,
    )

internal fun VerifiedStudentMistakeDelivery.toAuthenticatedInboxReceiptEntity(
    receivedAtEpochMillis: Long,
): MasteryAuthenticatedStudentInboxReceiptEntity =
    MasteryAuthenticatedStudentInboxReceiptEntity(
        eventId = envelope().eventId,
        learnerId = learnerId(),
        sourceStore = envelope().sourceStore.name,
        sourceStoreGeneration = sourceStoreGeneration(),
        relayEpoch = relayEpoch(),
        issuerKeyId = issuerKeyId(),
        algorithmVersion = algorithmVersion(),
        envelopeCanonicalFingerprint = envelope().canonicalFingerprint,
        proofCanonicalFingerprint = proofCanonicalFingerprint(),
        verificationReceiptCanonicalFingerprint =
            verificationReceiptCanonicalFingerprint(),
        receivedAtEpochMillis = receivedAtEpochMillis,
    )

internal fun MasteryStudentRelaySourceBindingEntity.samePinnedSource(
    incoming: MasteryStudentRelaySourceBindingEntity,
): Boolean =
    learnerId == incoming.learnerId &&
        sourceStore == incoming.sourceStore &&
        sourceStoreGeneration == incoming.sourceStoreGeneration &&
        relayEpoch == incoming.relayEpoch &&
        algorithmVersion == incoming.algorithmVersion

internal fun MasteryStudentRelaySourceBindingEntity.matchesAuthenticatedDelivery(
    message: MasteryCrossStoreInboxEntity,
    receipt: MasteryAuthenticatedStudentInboxReceiptEntity,
): Boolean =
    learnerId == receipt.learnerId &&
        sourceStore == message.sourceStore &&
        sourceStore == receipt.sourceStore &&
        sourceStoreGeneration == message.sourceStoreGeneration &&
        sourceStoreGeneration == receipt.sourceStoreGeneration &&
        relayEpoch == receipt.relayEpoch &&
        algorithmVersion == receipt.algorithmVersion &&
        message.eventId == receipt.eventId &&
        message.envelopeCanonicalFingerprint == receipt.envelopeCanonicalFingerprint

internal fun MasteryAuthenticatedStudentInboxReceiptEntity.sameVerifiedDelivery(
    incoming: MasteryAuthenticatedStudentInboxReceiptEntity,
): Boolean =
    eventId == incoming.eventId &&
        learnerId == incoming.learnerId &&
        sourceStore == incoming.sourceStore &&
        sourceStoreGeneration == incoming.sourceStoreGeneration &&
        relayEpoch == incoming.relayEpoch &&
        issuerKeyId == incoming.issuerKeyId &&
        algorithmVersion == incoming.algorithmVersion &&
        envelopeCanonicalFingerprint == incoming.envelopeCanonicalFingerprint &&
        proofCanonicalFingerprint == incoming.proofCanonicalFingerprint &&
        verificationReceiptCanonicalFingerprint ==
        incoming.verificationReceiptCanonicalFingerprint

internal const val MASTERY_PRE_AUTH_STUDENT_INBOX_QUARANTINE_REASON =
    "PRE_AUTHENTICITY_SCHEMA_V17"
