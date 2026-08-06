package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal fun projectionGenerationFingerprint(
    generationId: Long,
    targetProjectionPolicyVersion: String,
    targetCalibrationVersion: String,
    sourceEventCount: Long,
    sourceSupersessionCount: Long,
    projectionRowCount: Long,
    subjectDigestRowCount: Long,
    presentationBudgetRowCount: Long = 0L,
    problemFamilyBudgetRowCount: Long = 0L,
    budgetInputRowCount: Long = 0L,
    budgetInputSnapshotFingerprint: String = "",
    budgetOutputFingerprint: String = "",
): String =
    CanonicalSha256("learner-mastery-projection-generation-v2")
        .field("generationId", generationId)
        .field("targetProjectionPolicyVersion", targetProjectionPolicyVersion)
        .field("targetCalibrationVersion", targetCalibrationVersion)
        .field("sourceEventCount", sourceEventCount)
        .field("sourceSupersessionCount", sourceSupersessionCount)
        .field("projectionRowCount", projectionRowCount)
        .field("subjectDigestRowCount", subjectDigestRowCount)
        .field("presentationBudgetRowCount", presentationBudgetRowCount)
        .field("problemFamilyBudgetRowCount", problemFamilyBudgetRowCount)
        .field("budgetPolicyVersion", LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION)
        .field(
            "budgetAlgorithmVersion",
            LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION,
        )
        .field("budgetInputRowCount", budgetInputRowCount)
        .field("budgetInputSnapshotFingerprint", budgetInputSnapshotFingerprint)
        .field("budgetOutputFingerprint", budgetOutputFingerprint)
        .finish()

internal fun directionalBudgetInputSeedFingerprint(): String =
    CanonicalSha256("learner-mastery-directional-budget-input-seed-v1")
        .field("budgetPolicyVersion", LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION)
        .field(
            "algorithmVersion",
            LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION,
        ).finish()

internal fun extendDirectionalBudgetInputFingerprint(
    previousFingerprint: String,
    rows: List<MasteryEventAttributionReplayRow>,
): String {
    require(rows.isNotEmpty()) { "Directional budget input page must not be empty" }
    return rows.chunked(DIRECTIONAL_BUDGET_FINGERPRINT_CHUNK_SIZE)
        .fold(previousFingerprint) { fingerprint, chunk ->
            extendDirectionalBudgetInputFingerprintChunk(fingerprint, chunk)
        }
}

private fun extendDirectionalBudgetInputFingerprintChunk(
    previousFingerprint: String,
    rows: List<MasteryEventAttributionReplayRow>,
): String {
    require(rows.isNotEmpty()) { "Directional budget input chunk must not be empty" }
    val digest =
        CanonicalSha256.repeatingSchema(
            "learner-mastery-directional-budget-input-chunk-v1",
        ).field("previousFingerprint", previousFingerprint)
            .field("rowCount", rows.size)
    rows.forEach { row ->
        digest.field("learnerId", row.learnerId)
            .field("occurredAtEpochMillis", row.occurredAtEpochMillis)
            .field("eventId", row.eventId)
            .field("attributionOrdinal", row.ordinal)
            .field("direction", row.direction)
            .field("eventCanonicalFingerprint", row.eventCanonicalFingerprint)
            .field("subject", row.subject)
            .field("knowledgeNodeId", row.knowledgeNodeId)
            .field("taxonomyVersion", row.taxonomyVersion)
            .field("knowledgePackVersion", row.knowledgePackVersion)
            .field("knowledgeNodeRefFingerprint", row.knowledgeNodeRefFingerprint)
            .field("evidenceMassMicros", row.evidenceMassMicros)
            .field("presentationFingerprint", row.presentationFingerprint)
            .nullableField("problemFamilyFingerprint", row.problemFamilyFingerprint)
    }
    return digest.finish()
}

internal fun directionalBudgetOutputSeedFingerprint(): String =
    CanonicalSha256("learner-mastery-directional-budget-output-seed-v1")
        .field("budgetPolicyVersion", LEARNER_MASTERY_DIRECTIONAL_BUDGET_POLICY_VERSION)
        .field(
            "algorithmVersion",
            LEARNER_MASTERY_DIRECTIONAL_BUDGET_REBUILD_ALGORITHM_VERSION,
        ).finish()

internal fun extendDirectionalPresentationBudgetOutputFingerprint(
    previousFingerprint: String,
    rows: List<MasteryPresentationNodeBudgetShadowEntity>,
): String {
    require(rows.isNotEmpty()) { "Directional presentation output page must not be empty" }
    return rows.chunked(DIRECTIONAL_BUDGET_FINGERPRINT_CHUNK_SIZE)
        .fold(previousFingerprint) { fingerprint, chunk ->
            extendDirectionalPresentationBudgetOutputFingerprintChunk(fingerprint, chunk)
        }
}

private fun extendDirectionalPresentationBudgetOutputFingerprintChunk(
    previousFingerprint: String,
    rows: List<MasteryPresentationNodeBudgetShadowEntity>,
): String {
    require(rows.isNotEmpty()) { "Directional presentation output chunk must not be empty" }
    val digest =
        CanonicalSha256.repeatingSchema(
            "learner-mastery-directional-presentation-output-chunk-v1",
        ).field("previousFingerprint", previousFingerprint)
            .field("rowCount", rows.size)
    rows.forEach { row ->
        digest.field("learnerId", row.learnerId)
            .field("presentationId", row.presentationId)
            .field("subject", row.subject)
            .field("knowledgeNodeId", row.knowledgeNodeId)
            .field("taxonomyVersion", row.taxonomyVersion)
            .field("direction", row.direction)
            .field("stableNodeIdentityFingerprint", row.stableNodeIdentityFingerprint)
            .field("consumedMassMicros", row.consumedMassMicros)
            .field("lastEventId", row.lastEventId)
            .field("updatedAtEpochMillis", row.updatedAtEpochMillis)
    }
    return digest.finish()
}

internal fun extendDirectionalProblemFamilyBudgetOutputFingerprint(
    previousFingerprint: String,
    rows: List<MasteryProblemFamilyNodeBudgetShadowEntity>,
): String {
    require(rows.isNotEmpty()) { "Directional family output page must not be empty" }
    return rows.chunked(DIRECTIONAL_BUDGET_FINGERPRINT_CHUNK_SIZE)
        .fold(previousFingerprint) { fingerprint, chunk ->
            extendDirectionalProblemFamilyBudgetOutputFingerprintChunk(fingerprint, chunk)
        }
}

private fun extendDirectionalProblemFamilyBudgetOutputFingerprintChunk(
    previousFingerprint: String,
    rows: List<MasteryProblemFamilyNodeBudgetShadowEntity>,
): String {
    require(rows.isNotEmpty()) { "Directional family output chunk must not be empty" }
    val digest =
        CanonicalSha256.repeatingSchema(
            "learner-mastery-directional-family-output-chunk-v1",
        ).field("previousFingerprint", previousFingerprint)
            .field("rowCount", rows.size)
    rows.forEach { row ->
        digest.field("learnerId", row.learnerId)
            .field("problemFamilyFingerprint", row.problemFamilyFingerprint)
            .field("subject", row.subject)
            .field("knowledgeNodeId", row.knowledgeNodeId)
            .field("taxonomyVersion", row.taxonomyVersion)
            .field("direction", row.direction)
            .field("stableNodeIdentityFingerprint", row.stableNodeIdentityFingerprint)
            .field("observationCount", row.observationCount)
            .field("consumedMassMicros", row.consumedMassMicros)
            .field("lastEventId", row.lastEventId)
            .field("updatedAtEpochMillis", row.updatedAtEpochMillis)
    }
    return digest.finish()
}

internal fun MasteryPresentationNodeBudgetShadowEntity.directionalStableKey(): String =
    directionalStableKey(
        learnerId,
        presentationId,
        subject,
        knowledgeNodeId,
        taxonomyVersion,
        direction,
    )

internal fun MasteryProblemFamilyNodeBudgetShadowEntity.directionalStableKey(): String =
    directionalStableKey(
        learnerId,
        problemFamilyFingerprint,
        subject,
        knowledgeNodeId,
        taxonomyVersion,
        direction,
    )

internal data class MasteryDirectionalBudgetFingerprintCursor(
    val learnerId: String,
    val secondaryId: String,
    val subject: String,
    val knowledgeNodeId: String,
    val taxonomyVersion: String,
    val direction: String,
)

internal fun directionalStableKey(vararg parts: String): String =
    parts.joinToString(":", transform = String::uppercaseUtf8Hex)

internal fun String.decodeDirectionalBudgetCursor(): MasteryDirectionalBudgetFingerprintCursor {
    val parts = split(':', limit = 7)
    check(parts.size == 6) { "Directional budget cursor has an invalid component count" }
    val decoded = parts.map(String::decodeCanonicalUtf8Hex)
    return MasteryDirectionalBudgetFingerprintCursor(
        learnerId = decoded[0],
        secondaryId = decoded[1],
        subject = decoded[2],
        knowledgeNodeId = decoded[3],
        taxonomyVersion = decoded[4],
        direction = decoded[5],
    )
}

internal fun String.uppercaseUtf8Hex(): String {
    val bytes = encodeToByteArray()
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(UPPERCASE_HEX[value ushr 4])
            append(UPPERCASE_HEX[value and 0x0f])
        }
    }
}

internal fun String.decodeCanonicalUtf8Hex(): String {
    check(length % 2 == 0 && all { it in '0'..'9' || it in 'A'..'F' }) {
        "Directional budget cursor is not canonical uppercase hexadecimal UTF-8"
    }
    val bytes = ByteArray(length / 2) { index ->
        val high = UPPERCASE_HEX.indexOf(this[index * 2])
        val low = UPPERCASE_HEX.indexOf(this[index * 2 + 1])
        ((high shl 4) or low).toByte()
    }
    val decoded = bytes.toString(Charsets.UTF_8)
    check(decoded.uppercaseUtf8Hex() == this) {
        "Directional budget cursor contains invalid UTF-8"
    }
    return decoded
}

internal const val UPPERCASE_HEX = "0123456789ABCDEF"
