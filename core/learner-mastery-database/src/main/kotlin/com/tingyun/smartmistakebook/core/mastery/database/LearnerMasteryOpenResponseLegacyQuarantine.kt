package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal const val LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE =
    "mastery_open_response_legacy_quarantine"
internal const val LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_POLICY_VERSION =
    "learner-mastery-open-response-legacy-quarantine-v1"
internal const val LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_REASON =
    "MISSING_INDEPENDENT_NON_MODEL_CONFIRMATION"

/**
 * Immutable negative authorization for an already persisted model-semantic learning event.
 *
 * The original decision, event, attribution and application remain immutable facts. This record
 * only states that the event is not authorized as a projection input. No model or provider field
 * can create an exemption from this local database-owned boundary.
 */
@Entity(
    tableName = LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MasteryOpenResponseDedicatedDecisionEntity::class,
            parentColumns = ["decision_fingerprint"],
            childColumns = ["decision_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryLearningEventEntity::class,
            parentColumns = ["event_id", "canonical_fingerprint"],
            childColumns = ["accepted_event_id", "event_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["decision_fingerprint"], unique = true),
        Index(
            value = ["accepted_event_id", "event_canonical_fingerprint"],
            unique = true,
        ),
        Index(value = ["quarantine_fingerprint"], unique = true),
    ],
)
internal data class MasteryOpenResponseLegacyQuarantineEntity(
    @PrimaryKey
    @ColumnInfo(name = "accepted_event_id")
    val acceptedEventId: String,
    @ColumnInfo(name = "event_canonical_fingerprint")
    val eventCanonicalFingerprint: String,
    @ColumnInfo(name = "decision_fingerprint")
    val decisionFingerprint: String,
    @ColumnInfo(name = "quarantine_reason")
    val quarantineReason: String,
    @ColumnInfo(name = "policy_version")
    val policyVersion: String,
    @ColumnInfo(name = "quarantined_at_epoch_millis")
    val quarantinedAtEpochMillis: Long,
    @ColumnInfo(name = "quarantine_fingerprint")
    val quarantineFingerprint: String,
)

internal fun openResponseLegacyQuarantineFingerprint(
    acceptedEventId: String,
    eventCanonicalFingerprint: String,
    decisionFingerprint: String,
    quarantinedAtEpochMillis: Long,
): String =
    CanonicalSha256("learner-mastery-open-response-legacy-quarantine-v1")
        .field("acceptedEventId", acceptedEventId)
        .field("eventCanonicalFingerprint", eventCanonicalFingerprint)
        .field("decisionFingerprint", decisionFingerprint)
        .field("quarantineReason", LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_REASON)
        .field("policyVersion", LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_POLICY_VERSION)
        .field("quarantinedAtEpochMillis", quarantinedAtEpochMillis)
        .finish()
