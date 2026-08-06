package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactProofEntity
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LEARNING_OBSERVATION_PROJECTION_ADMISSION_POLICY_VERSION

internal const val LEARNING_OBSERVATION_ADMISSION_POLICY_VERSION =
    LEARNING_OBSERVATION_PROJECTION_ADMISSION_POLICY_VERSION

/**
 * Canonical commitment to the database-owned proof schema.
 *
 * The model layer deliberately treats this value as an opaque SHA-256 commitment. Length-prefixing
 * and explicit null tags prevent delimiter and absent-value collisions when the stores are split.
 */
internal object LearningObservationSourceFactProofFingerprint {
    private const val SCHEMA_VERSION = "learning-observation-source-fact-proof-v1"

    fun of(proof: LearningObservationSourceFactProofEntity): String =
        CanonicalSha256(SCHEMA_VERSION)
            .field("sourceFactId", proof.sourceFactId)
            .field("proofKind", proof.proofKind)
            .field("targetKind", proof.targetKind)
            .field("learnerId", proof.learnerId)
            .field("subject", proof.subject)
            .field("anchorId", proof.anchorId)
            .field("sourceReferenceId", proof.sourceReferenceId)
            .field("sourceFingerprint", proof.sourceFingerprint)
            .nullableField("conversationId", proof.conversationId)
            .nullableField(
                "conversationGeneration",
                proof.conversationGeneration?.toString(),
            )
            .nullableField("turnReceiptId", proof.turnReceiptId)
            .nullableField("evidenceRequestId", proof.evidenceRequestId)
            .field("sourceLocatorKind", proof.sourceLocatorKind)
            .field("sourceLocatorId", proof.sourceLocatorId)
            .field("targetDatabase", proof.targetDatabase)
            .field("targetId", proof.targetId)
            .field("targetVersion", proof.targetVersion)
            .field("targetFingerprint", proof.targetFingerprint)
            .field("targetCreatedAtEpochMillis", proof.targetCreatedAtEpochMillis)
            .field("attestedAtEpochMillis", proof.attestedAtEpochMillis)
            .finish()

    fun isValid(proof: LearningObservationSourceFactProofEntity): Boolean =
        SHA_256_HEX.matches(proof.proofFingerprint) && proof.proofFingerprint == of(proof)

    private val SHA_256_HEX = Regex("^[0-9a-f]{64}$")
}
