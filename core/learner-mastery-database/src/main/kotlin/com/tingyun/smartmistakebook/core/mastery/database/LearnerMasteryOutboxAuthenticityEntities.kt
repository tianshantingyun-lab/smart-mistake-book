package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/** Exact archival copy of a v18 outbox row that existed before source authentication. */
@Entity(
    tableName = "mastery_pre_auth_outbox_quarantine",
    indices = [
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["envelope_canonical_fingerprint"], unique = true),
        Index(value = ["learner_id", "quarantined_at_epoch_millis"]),
    ],
)
internal data class MasteryPreAuthOutboxQuarantineEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "source_store") val sourceStore: String,
    @ColumnInfo(name = "source_store_generation") val sourceStoreGeneration: String,
    @ColumnInfo(name = "destination_store") val destinationStore: String,
    @ColumnInfo(name = "aggregate_id") val aggregateId: String,
    @ColumnInfo(name = "aggregate_version") val aggregateVersion: Long,
    @ColumnInfo(name = "payload_type") val payloadType: String,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int,
    @ColumnInfo(name = "learning_evidence_ref_fingerprint")
    val learningEvidenceRefFingerprint: String,
    @ColumnInfo(name = "problem_revision_ref_fingerprint")
    val problemRevisionRefFingerprint: String?,
    @ColumnInfo(name = "payload_canonical_fingerprint")
    val payloadCanonicalFingerprint: String,
    @ColumnInfo(name = "payload_wire") val payloadWire: String,
    @ColumnInfo(name = "envelope_canonical_fingerprint")
    val envelopeCanonicalFingerprint: String,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "created_at_epoch_millis") val createdAtEpochMillis: Long,
    @ColumnInfo(name = "occurred_at_epoch_millis") val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "legacy_published_at_epoch_millis")
    val legacyPublishedAtEpochMillis: Long?,
    @ColumnInfo(name = "learner_id") val learnerId: String,
    @ColumnInfo(name = "quarantined_at_epoch_millis") val quarantinedAtEpochMillis: Long,
    @ColumnInfo(name = "quarantine_reason") val quarantineReason: String,
)
