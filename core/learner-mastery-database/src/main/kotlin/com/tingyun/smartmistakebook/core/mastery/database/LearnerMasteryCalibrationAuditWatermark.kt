package com.tingyun.smartmistakebook.core.mastery.database

import androidx.sqlite.SQLiteConnection
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal const val LEARNER_MASTERY_CALIBRATION_AUDIT_PROTOCOL =
    "learner-mastery-calibration-binding-audit-v2"
internal const val LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY =
    "learner_mastery_calibration_binding_audit_current_v2"

internal enum class LearnerMasteryCalibrationAuditTransition(
    val wireCode: String,
) {
    INCREMENTAL("I"),
    FULL_AUDIT_RESET("R"),
    ;

    companion object {
        fun fromWireCode(wireCode: String): LearnerMasteryCalibrationAuditTransition =
            entries.single { it.wireCode == wireCode }
    }
}

/**
 * Bounded application-level attestation for the calibration audit fast path.
 *
 * The singleton contains only counts, row identifiers, sequences and fingerprints. It never
 * contains an answer, problem text or problem image. The fast path is used only while those
 * bounded values and the canonical schema/guard fingerprint form an allowed transition; a guard
 * change, a schema change or an unsupported ledger-table change requires a full audit.
 *
 * This is not a row-integrity or cryptographic attestation. In particular, unchanged bounded
 * values cannot prove that an arbitrary non-head row was not rewritten by a principal with raw
 * SQLite access. Such a claim would require a full row digest backed by a protected checkpoint.
 */
internal data class LearnerMasteryCalibrationAuditWatermark(
    val auditRevision: Long = 0L,
    val transition: LearnerMasteryCalibrationAuditTransition =
        LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET,
    val protocolVersion: String,
    val guardFingerprint: String,
    val schemaVersion: Int,
    val eventCount: Long,
    val eventMaxRowId: Long,
    val eventMaxSequence: Long,
    val eventHeadFingerprint: String,
    val attributionCount: Long,
    val attributionMaxRowId: Long,
    val attributionHeadFingerprint: String,
    val reviewCaseCount: Long,
    val reviewCaseMaxRowId: Long,
    val reviewCaseHeadFingerprint: String,
    val reviewResolutionCount: Long,
    val reviewResolutionMaxRowId: Long,
    val reviewResolutionHeadFingerprint: String,
    val supersessionCount: Long,
    val supersessionMaxRowId: Long,
    val supersessionHeadFingerprint: String,
    val calibrationSnapshotCount: Long,
    val calibrationSnapshotMaxRowId: Long,
    val calibrationSnapshotHeadFingerprint: String,
    val projectionCount: Long,
    val projectionMaxEventSequence: Long,
    val activeGenerationCount: Long,
    val activeGenerationId: Long,
    val activeGenerationSnapshotFingerprint: String,
    val activeGenerationManifestFingerprint: String,
    val activeGenerationProjectionPolicyFingerprint: String,
    val activeGenerationCalibrationVersionFingerprint: String,
) {
    init {
        require(auditRevision >= 0L)
        require(protocolVersion == LEARNER_MASTERY_CALIBRATION_AUDIT_PROTOCOL)
        requireValidAuditFingerprint(guardFingerprint, allowEmptySentinel = false)
        require(schemaVersion > 0)
        requireAppendOnlyAuditState(eventCount, eventMaxRowId, eventHeadFingerprint)
        require(eventMaxSequence >= 0L)
        require((eventCount == 0L) == (eventMaxSequence == 0L))
        requireAppendOnlyAuditState(
            attributionCount,
            attributionMaxRowId,
            attributionHeadFingerprint,
        )
        requireAppendOnlyAuditState(reviewCaseCount, reviewCaseMaxRowId, reviewCaseHeadFingerprint)
        requireAppendOnlyAuditState(
            reviewResolutionCount,
            reviewResolutionMaxRowId,
            reviewResolutionHeadFingerprint,
        )
        requireAppendOnlyAuditState(
            supersessionCount,
            supersessionMaxRowId,
            supersessionHeadFingerprint,
        )
        requireAppendOnlyAuditState(
            calibrationSnapshotCount,
            calibrationSnapshotMaxRowId,
            calibrationSnapshotHeadFingerprint,
        )
        require(projectionCount >= 0L && projectionMaxEventSequence >= 0L)
        require((projectionCount == 0L) == (projectionMaxEventSequence == 0L))
        require(activeGenerationCount in 0L..1L) {
            "Learner-mastery database has more than one active projection generation"
        }
        val activeFingerprints =
            listOf(
                activeGenerationSnapshotFingerprint,
                activeGenerationManifestFingerprint,
                activeGenerationProjectionPolicyFingerprint,
                activeGenerationCalibrationVersionFingerprint,
            )
        activeFingerprints.forEach { requireValidAuditFingerprint(it, allowEmptySentinel = true) }
        if (activeGenerationCount == 0L) {
            require(activeGenerationId == 0L)
            require(activeFingerprints.all { it == EMPTY_AUDIT_FINGERPRINT })
        } else {
            require(activeGenerationId > 0L)
            require(activeGenerationSnapshotFingerprint != EMPTY_AUDIT_FINGERPRINT)
            require(activeGenerationProjectionPolicyFingerprint != EMPTY_AUDIT_FINGERPRINT)
            require(activeGenerationCalibrationVersionFingerprint != EMPTY_AUDIT_FINGERPRINT)
        }
    }

    val auditedStateFingerprint: String
        get() =
            appendAuditedStateFields(
                CanonicalSha256("learner-mastery-calibration-audit-state-v2"),
            ).finish()

    val canonicalFingerprint: String
        get() =
            appendAuditedStateFields(
                CanonicalSha256("learner-mastery-calibration-audit-watermark-v2")
                    .field("auditRevision", auditRevision)
                    .field("transition", transition.wireCode),
            ).finish()

    fun exactlyMatches(other: LearnerMasteryCalibrationAuditWatermark): Boolean =
        auditedStateFingerprint == other.auditedStateFingerprint

    fun canIncrementallyAdvanceTo(other: LearnerMasteryCalibrationAuditWatermark): Boolean =
        protocolVersion == other.protocolVersion &&
            guardFingerprint == other.guardFingerprint &&
            schemaVersion == other.schemaVersion &&
            activeGenerationCount == other.activeGenerationCount &&
            activeGenerationId == other.activeGenerationId &&
            activeGenerationSnapshotFingerprint == other.activeGenerationSnapshotFingerprint &&
            activeGenerationManifestFingerprint == other.activeGenerationManifestFingerprint &&
            activeGenerationProjectionPolicyFingerprint ==
            other.activeGenerationProjectionPolicyFingerprint &&
            activeGenerationCalibrationVersionFingerprint ==
            other.activeGenerationCalibrationVersionFingerprint &&
            appendOnlyStateCanAdvance(
                priorCount = eventCount,
                priorMaxRowId = eventMaxRowId,
                priorHeadFingerprint = eventHeadFingerprint,
                currentCount = other.eventCount,
                currentMaxRowId = other.eventMaxRowId,
                currentHeadFingerprint = other.eventHeadFingerprint,
            ) &&
            other.eventMaxSequence >= eventMaxSequence &&
            appendOnlyStateCanAdvance(
                priorCount = attributionCount,
                priorMaxRowId = attributionMaxRowId,
                priorHeadFingerprint = attributionHeadFingerprint,
                currentCount = other.attributionCount,
                currentMaxRowId = other.attributionMaxRowId,
                currentHeadFingerprint = other.attributionHeadFingerprint,
            ) &&
            appendOnlyStateCanAdvance(
                priorCount = reviewCaseCount,
                priorMaxRowId = reviewCaseMaxRowId,
                priorHeadFingerprint = reviewCaseHeadFingerprint,
                currentCount = other.reviewCaseCount,
                currentMaxRowId = other.reviewCaseMaxRowId,
                currentHeadFingerprint = other.reviewCaseHeadFingerprint,
            ) &&
            appendOnlyStateCanAdvance(
                priorCount = reviewResolutionCount,
                priorMaxRowId = reviewResolutionMaxRowId,
                priorHeadFingerprint = reviewResolutionHeadFingerprint,
                currentCount = other.reviewResolutionCount,
                currentMaxRowId = other.reviewResolutionMaxRowId,
                currentHeadFingerprint = other.reviewResolutionHeadFingerprint,
            ) &&
            appendOnlyStateCanAdvance(
                priorCount = supersessionCount,
                priorMaxRowId = supersessionMaxRowId,
                priorHeadFingerprint = supersessionHeadFingerprint,
                currentCount = other.supersessionCount,
                currentMaxRowId = other.supersessionMaxRowId,
                currentHeadFingerprint = other.supersessionHeadFingerprint,
            ) &&
            appendOnlyStateCanAdvance(
                priorCount = calibrationSnapshotCount,
                priorMaxRowId = calibrationSnapshotMaxRowId,
                priorHeadFingerprint = calibrationSnapshotHeadFingerprint,
                currentCount = other.calibrationSnapshotCount,
                currentMaxRowId = other.calibrationSnapshotMaxRowId,
                currentHeadFingerprint = other.calibrationSnapshotHeadFingerprint,
            ) &&
            other.projectionCount >= projectionCount &&
            other.projectionMaxEventSequence >= projectionMaxEventSequence

    fun forPersistence(
        previous: LearnerMasteryCalibrationAuditWatermark?,
        nextTransition: LearnerMasteryCalibrationAuditTransition,
        previousAuditRevision: Long = previous?.auditRevision ?: 0L,
    ): LearnerMasteryCalibrationAuditWatermark {
        require(previousAuditRevision >= 0L)
        require(previous == null || previous.auditRevision == previousAuditRevision)
        if (previous == null) {
            require(nextTransition == LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET)
        } else if (nextTransition == LearnerMasteryCalibrationAuditTransition.INCREMENTAL) {
            require(previous.canIncrementallyAdvanceTo(this))
        }
        return copy(
            auditRevision = Math.addExact(previousAuditRevision, 1L),
            transition = nextTransition,
        )
    }

    fun encode(): String {
        require(auditRevision > 0L) { "Only an audited watermark can be persisted" }
        val values = wireValues().toMutableMap()
        values[AuditWireField.SELF_FINGERPRINT] = canonicalFingerprint
        return encodeAuditWireValues(values)
    }

    private fun appendAuditedStateFields(digest: CanonicalSha256): CanonicalSha256 =
        digest
            .field("protocolVersion", protocolVersion)
            .field("guardFingerprint", guardFingerprint)
            .field("schemaVersion", schemaVersion)
            .field("eventCount", eventCount)
            .field("eventMaxRowId", eventMaxRowId)
            .field("eventMaxSequence", eventMaxSequence)
            .field("eventHeadFingerprint", eventHeadFingerprint)
            .field("attributionCount", attributionCount)
            .field("attributionMaxRowId", attributionMaxRowId)
            .field("attributionHeadFingerprint", attributionHeadFingerprint)
            .field("reviewCaseCount", reviewCaseCount)
            .field("reviewCaseMaxRowId", reviewCaseMaxRowId)
            .field("reviewCaseHeadFingerprint", reviewCaseHeadFingerprint)
            .field("reviewResolutionCount", reviewResolutionCount)
            .field("reviewResolutionMaxRowId", reviewResolutionMaxRowId)
            .field("reviewResolutionHeadFingerprint", reviewResolutionHeadFingerprint)
            .field("supersessionCount", supersessionCount)
            .field("supersessionMaxRowId", supersessionMaxRowId)
            .field("supersessionHeadFingerprint", supersessionHeadFingerprint)
            .field("calibrationSnapshotCount", calibrationSnapshotCount)
            .field("calibrationSnapshotMaxRowId", calibrationSnapshotMaxRowId)
            .field("calibrationSnapshotHeadFingerprint", calibrationSnapshotHeadFingerprint)
            .field("projectionCount", projectionCount)
            .field("projectionMaxEventSequence", projectionMaxEventSequence)
            .field("activeGenerationCount", activeGenerationCount)
            .field("activeGenerationId", activeGenerationId)
            .field("activeGenerationSnapshotFingerprint", activeGenerationSnapshotFingerprint)
            .field("activeGenerationManifestFingerprint", activeGenerationManifestFingerprint)
            .field(
                "activeGenerationProjectionPolicyFingerprint",
                activeGenerationProjectionPolicyFingerprint,
            ).field(
                "activeGenerationCalibrationVersionFingerprint",
                activeGenerationCalibrationVersionFingerprint,
            )

    private fun wireValues(): Map<AuditWireField, String> =
        mapOf(
            AuditWireField.FORMAT to AUDIT_WIRE_FORMAT,
            AuditWireField.TRANSITION to transition.wireCode,
            AuditWireField.AUDIT_REVISION to auditRevision.toAuditNumber(),
            AuditWireField.PROTOCOL_FINGERPRINT to CALIBRATION_AUDIT_PROTOCOL_FINGERPRINT,
            AuditWireField.GUARD_FINGERPRINT to guardFingerprint,
            AuditWireField.SCHEMA_VERSION to schemaVersion.toLong().toAuditNumber(),
            AuditWireField.EVENT_COUNT to eventCount.toAuditNumber(),
            AuditWireField.EVENT_MAX_ROW_ID to eventMaxRowId.toAuditNumber(),
            AuditWireField.EVENT_MAX_SEQUENCE to eventMaxSequence.toAuditNumber(),
            AuditWireField.EVENT_HEAD_FINGERPRINT to eventHeadFingerprint,
            AuditWireField.ATTRIBUTION_COUNT to attributionCount.toAuditNumber(),
            AuditWireField.ATTRIBUTION_MAX_ROW_ID to attributionMaxRowId.toAuditNumber(),
            AuditWireField.ATTRIBUTION_HEAD_FINGERPRINT to attributionHeadFingerprint,
            AuditWireField.REVIEW_CASE_COUNT to reviewCaseCount.toAuditNumber(),
            AuditWireField.REVIEW_CASE_MAX_ROW_ID to reviewCaseMaxRowId.toAuditNumber(),
            AuditWireField.REVIEW_CASE_HEAD_FINGERPRINT to reviewCaseHeadFingerprint,
            AuditWireField.REVIEW_RESOLUTION_COUNT to reviewResolutionCount.toAuditNumber(),
            AuditWireField.REVIEW_RESOLUTION_MAX_ROW_ID to
                reviewResolutionMaxRowId.toAuditNumber(),
            AuditWireField.REVIEW_RESOLUTION_HEAD_FINGERPRINT to
                reviewResolutionHeadFingerprint,
            AuditWireField.SUPERSESSION_COUNT to supersessionCount.toAuditNumber(),
            AuditWireField.SUPERSESSION_MAX_ROW_ID to supersessionMaxRowId.toAuditNumber(),
            AuditWireField.SUPERSESSION_HEAD_FINGERPRINT to supersessionHeadFingerprint,
            AuditWireField.CALIBRATION_SNAPSHOT_COUNT to
                calibrationSnapshotCount.toAuditNumber(),
            AuditWireField.CALIBRATION_SNAPSHOT_MAX_ROW_ID to
                calibrationSnapshotMaxRowId.toAuditNumber(),
            AuditWireField.CALIBRATION_SNAPSHOT_HEAD_FINGERPRINT to
                calibrationSnapshotHeadFingerprint,
            AuditWireField.PROJECTION_COUNT to projectionCount.toAuditNumber(),
            AuditWireField.PROJECTION_MAX_EVENT_SEQUENCE to
                projectionMaxEventSequence.toAuditNumber(),
            AuditWireField.ACTIVE_GENERATION_COUNT to activeGenerationCount.toAuditNumber(),
            AuditWireField.ACTIVE_GENERATION_ID to activeGenerationId.toAuditNumber(),
            AuditWireField.ACTIVE_GENERATION_SNAPSHOT_FINGERPRINT to
                activeGenerationSnapshotFingerprint,
            AuditWireField.ACTIVE_GENERATION_MANIFEST_FINGERPRINT to
                activeGenerationManifestFingerprint,
            AuditWireField.ACTIVE_GENERATION_POLICY_FINGERPRINT to
                activeGenerationProjectionPolicyFingerprint,
            AuditWireField.ACTIVE_GENERATION_CALIBRATION_FINGERPRINT to
                activeGenerationCalibrationVersionFingerprint,
        )
}

internal fun readLearnerMasterySchemaVersion(connection: SQLiteConnection): Int =
    connection.prepare("PRAGMA user_version").use { statement ->
        check(statement.step()) { "Learner-mastery schema version is unavailable" }
        statement.getLong(0).toInt()
    }

internal fun requireLearnerMasteryForeignKeys(connection: SQLiteConnection) {
    connection.prepare("PRAGMA foreign_keys").use { statement ->
        check(statement.step() && statement.getLong(0) == 1L) {
            "Learner-mastery database must enforce foreign keys before guard attestation"
        }
    }
}

internal fun readLearnerMasteryCalibrationAuditState(
    connection: SQLiteConnection,
    guardFingerprint: String,
): LearnerMasteryCalibrationAuditWatermark {
    val schemaVersion = readLearnerMasterySchemaVersion(connection)
    return connection.prepare(
        """
        SELECT
            (SELECT COUNT(*) FROM mastery_learning_event),
            (SELECT COALESCE(MAX(rowid), 0) FROM mastery_learning_event),
            (SELECT COALESCE(MAX(event_sequence), 0) FROM mastery_learning_event),
            (SELECT COUNT(*) FROM mastery_learning_event_attribution),
            (SELECT COALESCE(MAX(rowid), 0) FROM mastery_learning_event_attribution),
            (SELECT COUNT(*) FROM mastery_evidence_review_case),
            (SELECT COALESCE(MAX(rowid), 0) FROM mastery_evidence_review_case),
            (SELECT COUNT(*) FROM mastery_evidence_review_resolution),
            (SELECT COALESCE(MAX(rowid), 0) FROM mastery_evidence_review_resolution),
            (SELECT COUNT(*) FROM mastery_learning_evidence_supersession),
            (SELECT COALESCE(MAX(rowid), 0) FROM mastery_learning_evidence_supersession),
            (SELECT COUNT(*) FROM mastery_calibration_snapshot),
            (SELECT COALESCE(MAX(rowid), 0) FROM mastery_calibration_snapshot),
            (SELECT COUNT(*) FROM mastery_knowledge_projection),
            (SELECT COALESCE(MAX(last_event_sequence), 0) FROM mastery_knowledge_projection),
            (SELECT COUNT(*) FROM mastery_projection_generation WHERE state = 'ACTIVE'),
            COALESCE((
                SELECT generation_id FROM mastery_projection_generation
                WHERE state = 'ACTIVE' ORDER BY generation_id DESC LIMIT 1
            ), 0),
            COALESCE((
                SELECT snapshot_fingerprint FROM mastery_projection_generation
                WHERE state = 'ACTIVE' ORDER BY generation_id DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT generation_manifest_fingerprint FROM mastery_projection_generation
                WHERE state = 'ACTIVE' ORDER BY generation_id DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT target_projection_policy_version FROM mastery_projection_generation
                WHERE state = 'ACTIVE' ORDER BY generation_id DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT target_calibration_version FROM mastery_projection_generation
                WHERE state = 'ACTIVE' ORDER BY generation_id DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT canonical_fingerprint FROM mastery_learning_event
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT event_id FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT ordinal FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), -1),
            COALESCE((
                SELECT subject FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT knowledge_node_id FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT taxonomy_version FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT knowledge_pack_version FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT knowledge_node_ref_fingerprint FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT evidence_mass_micros FROM mastery_learning_event_attribution
                ORDER BY rowid DESC LIMIT 1
            ), 0),
            COALESCE((
                SELECT review_case_fingerprint FROM mastery_evidence_review_case
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT resolution_fingerprint FROM mastery_evidence_review_resolution
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT canonical_fingerprint FROM mastery_learning_evidence_supersession
                ORDER BY rowid DESC LIMIT 1
            ), ''),
            COALESCE((
                SELECT snapshot_fingerprint FROM mastery_calibration_snapshot
                ORDER BY rowid DESC LIMIT 1
            ), '')
        """.trimIndent(),
    ).use { statement ->
        check(statement.step()) { "Learner-mastery audit state is unavailable" }
        val activeGenerationCount = statement.getLong(15)
        LearnerMasteryCalibrationAuditWatermark(
            protocolVersion = LEARNER_MASTERY_CALIBRATION_AUDIT_PROTOCOL,
            guardFingerprint = requireStoredAuditFingerprint(guardFingerprint),
            schemaVersion = schemaVersion,
            eventCount = statement.getLong(0),
            eventMaxRowId = statement.getLong(1),
            eventMaxSequence = statement.getLong(2),
            eventHeadFingerprint = optionalStoredAuditFingerprint(statement.getText(21)),
            attributionCount = statement.getLong(3),
            attributionMaxRowId = statement.getLong(4),
            attributionHeadFingerprint =
                attributionHeadFingerprint(
                    count = statement.getLong(3),
                    eventId = statement.getText(22),
                    ordinal = statement.getLong(23),
                    subject = statement.getText(24),
                    knowledgeNodeId = statement.getText(25),
                    taxonomyVersion = statement.getText(26),
                    knowledgePackVersion = statement.getText(27),
                    knowledgeNodeRefFingerprint = statement.getText(28),
                    evidenceMassMicros = statement.getLong(29),
                ),
            reviewCaseCount = statement.getLong(5),
            reviewCaseMaxRowId = statement.getLong(6),
            reviewCaseHeadFingerprint = optionalStoredAuditFingerprint(statement.getText(30)),
            reviewResolutionCount = statement.getLong(7),
            reviewResolutionMaxRowId = statement.getLong(8),
            reviewResolutionHeadFingerprint =
                optionalStoredAuditFingerprint(statement.getText(31)),
            supersessionCount = statement.getLong(9),
            supersessionMaxRowId = statement.getLong(10),
            supersessionHeadFingerprint = optionalStoredAuditFingerprint(statement.getText(32)),
            calibrationSnapshotCount = statement.getLong(11),
            calibrationSnapshotMaxRowId = statement.getLong(12),
            calibrationSnapshotHeadFingerprint =
                optionalStoredAuditFingerprint(statement.getText(33)),
            projectionCount = statement.getLong(13),
            projectionMaxEventSequence = statement.getLong(14),
            activeGenerationCount = activeGenerationCount,
            activeGenerationId = statement.getLong(16),
            activeGenerationSnapshotFingerprint =
                activeAuditFingerprint(activeGenerationCount, statement.getText(17)),
            activeGenerationManifestFingerprint =
                activeOptionalAuditFingerprint(activeGenerationCount, statement.getText(18)),
            activeGenerationProjectionPolicyFingerprint =
                activeReferenceFingerprint(
                    activeGenerationCount,
                    "learner-mastery-active-projection-policy-v1",
                    statement.getText(19),
                ),
            activeGenerationCalibrationVersionFingerprint =
                activeReferenceFingerprint(
                    activeGenerationCount,
                    "learner-mastery-active-calibration-version-v1",
                    statement.getText(20),
                ),
        )
    }
}

internal fun readLatestLearnerMasteryCalibrationAuditWatermark(
    connection: SQLiteConnection,
): LearnerMasteryCalibrationAuditWatermark? =
    readLearnerMasteryCalibrationAuditMetadataValue(connection)?.let { encoded ->
        decodeLearnerMasteryCalibrationAuditWatermark(encoded)
    }

internal fun readLearnerMasteryCalibrationAuditRevision(
    connection: SQLiteConnection,
): Long? =
    readLearnerMasteryCalibrationAuditMetadataValue(connection)?.let { encoded ->
        decodeLearnerMasteryCalibrationAuditEnvelope(encoded)?.watermark?.auditRevision
    }

private fun readLearnerMasteryCalibrationAuditMetadataValue(
    connection: SQLiteConnection,
): String? =
    connection.prepare(
        """
        SELECT metadata_value
        FROM mastery_store_metadata
        WHERE metadata_key = ?
        LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.bindText(1, LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY)
        if (!statement.step()) return@use null
        statement.getText(0)
    }

internal fun persistLearnerMasteryCalibrationAuditWatermark(
    connection: SQLiteConnection,
    watermark: LearnerMasteryCalibrationAuditWatermark,
) {
    val exists =
        connection.prepare(
            "SELECT 1 FROM mastery_store_metadata WHERE metadata_key = ? LIMIT 1",
        ).use { statement ->
            statement.bindText(1, LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY)
            statement.step()
        }
    val sql =
        if (exists) {
            "UPDATE mastery_store_metadata SET metadata_value = ? WHERE metadata_key = ?"
        } else {
            "INSERT INTO mastery_store_metadata(metadata_value, metadata_key) VALUES(?, ?)"
        }
    connection.prepare(sql).use { statement ->
        statement.bindText(1, watermark.encode())
        statement.bindText(2, LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY)
        statement.step()
    }
}

internal fun learnerMasteryCalibrationAuditMetadataUpdateTriggerDefinition():
    LearnerMasteryTriggerDefinition {
    val validOld = auditMetadataValueIsValidSql("OLD.metadata_value")
    val validNew = auditMetadataValueIsValidSql("NEW.metadata_value")
    val oldRevision = auditNumericSql("OLD.metadata_value", AuditWireField.AUDIT_REVISION)
    val newRevision = auditNumericSql("NEW.metadata_value", AuditWireField.AUDIT_REVISION)
    val newTransition = auditSliceSql("NEW.metadata_value", AuditWireField.TRANSITION)
    val transitionIsValid =
        """
        $newRevision = $oldRevision + 1
        AND (
            $newTransition = '${LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET.wireCode}'
            OR (
                $newTransition = '${LearnerMasteryCalibrationAuditTransition.INCREMENTAL.wireCode}'
                AND ${incrementalAuditTransitionIsValidSql()}
            )
        )
        """.trimIndent()
    return LearnerMasteryTriggerDefinition(
        name = "immutable_mastery_store_metadata_update",
        sql =
            """
            CREATE TRIGGER immutable_mastery_store_metadata_update
            BEFORE UPDATE ON mastery_store_metadata
            WHEN OLD.metadata_key != '$LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY'
              OR NEW.metadata_key != OLD.metadata_key
              OR typeof(NEW.metadata_value) != 'text'
              OR NOT ($validNew)
              OR (
                  ($validOld) AND NOT ($transitionIsValid)
              )
              OR (
                  NOT ($validOld) AND NOT (
                      $newTransition =
                          '${LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET.wireCode}'
                      AND $newRevision = 1
                  )
              )
            BEGIN
                SELECT RAISE(ABORT, 'invalid learner-mastery audit watermark transition');
            END
            """.trimIndent(),
    )
}

internal fun learnerMasteryCalibrationAuditMetadataInsertTriggerDefinition():
    LearnerMasteryTriggerDefinition {
    val validNew = auditMetadataValueIsValidSql("NEW.metadata_value")
    val newRevision = auditNumericSql("NEW.metadata_value", AuditWireField.AUDIT_REVISION)
    val newTransition = auditSliceSql("NEW.metadata_value", AuditWireField.TRANSITION)
    return LearnerMasteryTriggerDefinition(
        name = "validate_mastery_store_metadata_calibration_audit_insert",
        sql =
            """
            CREATE TRIGGER validate_mastery_store_metadata_calibration_audit_insert
            BEFORE INSERT ON mastery_store_metadata
            WHEN (
                NEW.metadata_key != '$LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY'
                AND EXISTS (
                    SELECT 1
                    FROM mastery_store_metadata AS existing
                    WHERE existing.metadata_key = NEW.metadata_key
                      AND existing.metadata_value IS NOT NEW.metadata_value
                )
              ) OR (
                NEW.metadata_key = '$LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY'
                AND (
                  EXISTS (
                      SELECT 1 FROM mastery_store_metadata
                      WHERE metadata_key = '$LEARNER_MASTERY_CALIBRATION_AUDIT_METADATA_KEY'
                  )
                  OR typeof(NEW.metadata_value) != 'text'
                  OR NOT ($validNew)
                  OR $newTransition !=
                      '${LearnerMasteryCalibrationAuditTransition.FULL_AUDIT_RESET.wireCode}'
                  OR $newRevision != 1
                )
              )
            BEGIN
                SELECT RAISE(ABORT, 'invalid learner-mastery metadata insert');
            END
            """.trimIndent(),
    )
}

internal fun decodeLearnerMasteryCalibrationAuditWatermark(
    encoded: String,
): LearnerMasteryCalibrationAuditWatermark? =
    decodeLearnerMasteryCalibrationAuditEnvelope(encoded)?.let { envelope ->
        envelope.watermark.takeIf {
            envelope.selfFingerprint == envelope.watermark.canonicalFingerprint
        }
    }

private fun decodeLearnerMasteryCalibrationAuditEnvelope(
    encoded: String,
): DecodedAuditEnvelope? =
    runCatching {
        val parts = decodeAuditWireValues(encoded)
        check(parts.getValue(AuditWireField.FORMAT) == AUDIT_WIRE_FORMAT)
        check(
            parts.getValue(AuditWireField.PROTOCOL_FINGERPRINT) ==
                CALIBRATION_AUDIT_PROTOCOL_FINGERPRINT,
        )
        val selfFingerprint = parts.getValue(AuditWireField.SELF_FINGERPRINT)
        requireValidAuditFingerprint(selfFingerprint, allowEmptySentinel = false)
        val watermark = LearnerMasteryCalibrationAuditWatermark(
            auditRevision = parts.auditLong(AuditWireField.AUDIT_REVISION),
            transition =
                LearnerMasteryCalibrationAuditTransition.fromWireCode(
                    parts.getValue(AuditWireField.TRANSITION),
                ),
            protocolVersion = LEARNER_MASTERY_CALIBRATION_AUDIT_PROTOCOL,
            guardFingerprint = parts.getValue(AuditWireField.GUARD_FINGERPRINT),
            schemaVersion = parts.auditLong(AuditWireField.SCHEMA_VERSION).toInt(),
            eventCount = parts.auditLong(AuditWireField.EVENT_COUNT),
            eventMaxRowId = parts.auditLong(AuditWireField.EVENT_MAX_ROW_ID),
            eventMaxSequence = parts.auditLong(AuditWireField.EVENT_MAX_SEQUENCE),
            eventHeadFingerprint = parts.getValue(AuditWireField.EVENT_HEAD_FINGERPRINT),
            attributionCount = parts.auditLong(AuditWireField.ATTRIBUTION_COUNT),
            attributionMaxRowId = parts.auditLong(AuditWireField.ATTRIBUTION_MAX_ROW_ID),
            attributionHeadFingerprint =
                parts.getValue(AuditWireField.ATTRIBUTION_HEAD_FINGERPRINT),
            reviewCaseCount = parts.auditLong(AuditWireField.REVIEW_CASE_COUNT),
            reviewCaseMaxRowId = parts.auditLong(AuditWireField.REVIEW_CASE_MAX_ROW_ID),
            reviewCaseHeadFingerprint =
                parts.getValue(AuditWireField.REVIEW_CASE_HEAD_FINGERPRINT),
            reviewResolutionCount = parts.auditLong(AuditWireField.REVIEW_RESOLUTION_COUNT),
            reviewResolutionMaxRowId =
                parts.auditLong(AuditWireField.REVIEW_RESOLUTION_MAX_ROW_ID),
            reviewResolutionHeadFingerprint =
                parts.getValue(AuditWireField.REVIEW_RESOLUTION_HEAD_FINGERPRINT),
            supersessionCount = parts.auditLong(AuditWireField.SUPERSESSION_COUNT),
            supersessionMaxRowId = parts.auditLong(AuditWireField.SUPERSESSION_MAX_ROW_ID),
            supersessionHeadFingerprint =
                parts.getValue(AuditWireField.SUPERSESSION_HEAD_FINGERPRINT),
            calibrationSnapshotCount = parts.auditLong(AuditWireField.CALIBRATION_SNAPSHOT_COUNT),
            calibrationSnapshotMaxRowId =
                parts.auditLong(AuditWireField.CALIBRATION_SNAPSHOT_MAX_ROW_ID),
            calibrationSnapshotHeadFingerprint =
                parts.getValue(AuditWireField.CALIBRATION_SNAPSHOT_HEAD_FINGERPRINT),
            projectionCount = parts.auditLong(AuditWireField.PROJECTION_COUNT),
            projectionMaxEventSequence =
                parts.auditLong(AuditWireField.PROJECTION_MAX_EVENT_SEQUENCE),
            activeGenerationCount = parts.auditLong(AuditWireField.ACTIVE_GENERATION_COUNT),
            activeGenerationId = parts.auditLong(AuditWireField.ACTIVE_GENERATION_ID),
            activeGenerationSnapshotFingerprint =
                parts.getValue(AuditWireField.ACTIVE_GENERATION_SNAPSHOT_FINGERPRINT),
            activeGenerationManifestFingerprint =
                parts.getValue(AuditWireField.ACTIVE_GENERATION_MANIFEST_FINGERPRINT),
            activeGenerationProjectionPolicyFingerprint =
                parts.getValue(AuditWireField.ACTIVE_GENERATION_POLICY_FINGERPRINT),
            activeGenerationCalibrationVersionFingerprint =
                parts.getValue(AuditWireField.ACTIVE_GENERATION_CALIBRATION_FINGERPRINT),
        )
        check(watermark.auditRevision > 0L)
        DecodedAuditEnvelope(watermark, selfFingerprint)
    }.getOrNull()

private data class DecodedAuditEnvelope(
    val watermark: LearnerMasteryCalibrationAuditWatermark,
    val selfFingerprint: String,
)

private fun appendOnlyStateCanAdvance(
    priorCount: Long,
    priorMaxRowId: Long,
    priorHeadFingerprint: String,
    currentCount: Long,
    currentMaxRowId: Long,
    currentHeadFingerprint: String,
): Boolean =
    if (currentCount == priorCount) {
        currentMaxRowId == priorMaxRowId && currentHeadFingerprint == priorHeadFingerprint
    } else {
        currentCount > priorCount &&
            currentMaxRowId > priorMaxRowId &&
            currentHeadFingerprint != EMPTY_AUDIT_FINGERPRINT &&
            currentHeadFingerprint != priorHeadFingerprint
    }

private fun appendOnlyStateExactlyMatches(
    priorCount: Long,
    priorMaxRowId: Long,
    priorHeadFingerprint: String,
    currentCount: Long,
    currentMaxRowId: Long,
    currentHeadFingerprint: String,
): Boolean =
    priorCount == currentCount &&
        priorMaxRowId == currentMaxRowId &&
        priorHeadFingerprint == currentHeadFingerprint

private fun requireAppendOnlyAuditState(
    count: Long,
    maxRowId: Long,
    headFingerprint: String,
) {
    require(count >= 0L && maxRowId >= 0L)
    requireValidAuditFingerprint(headFingerprint, allowEmptySentinel = true)
    require(
        if (count == 0L) {
            maxRowId == 0L && headFingerprint == EMPTY_AUDIT_FINGERPRINT
        } else {
            maxRowId > 0L && headFingerprint != EMPTY_AUDIT_FINGERPRINT
        },
    )
}

private fun attributionHeadFingerprint(
    count: Long,
    eventId: String,
    ordinal: Long,
    subject: String,
    knowledgeNodeId: String,
    taxonomyVersion: String,
    knowledgePackVersion: String,
    knowledgeNodeRefFingerprint: String,
    evidenceMassMicros: Long,
): String {
    if (count == 0L) return EMPTY_AUDIT_FINGERPRINT
    return CanonicalSha256("learner-mastery-attribution-audit-head-v1")
        .field("eventId", eventId)
        .field("ordinal", ordinal)
        .field("subject", subject)
        .field("knowledgeNodeId", knowledgeNodeId)
        .field("taxonomyVersion", taxonomyVersion)
        .field("knowledgePackVersion", knowledgePackVersion)
        .field("knowledgeNodeRefFingerprint", knowledgeNodeRefFingerprint)
        .field("evidenceMassMicros", evidenceMassMicros)
        .finish()
}

private fun activeAuditFingerprint(activeCount: Long, value: String): String =
    if (activeCount == 0L) EMPTY_AUDIT_FINGERPRINT else requireStoredAuditFingerprint(value)

private fun activeOptionalAuditFingerprint(activeCount: Long, value: String): String =
    if (activeCount == 0L) EMPTY_AUDIT_FINGERPRINT else optionalStoredAuditFingerprint(value)

private fun activeReferenceFingerprint(
    activeCount: Long,
    domain: String,
    value: String,
): String =
    if (activeCount == 0L) {
        EMPTY_AUDIT_FINGERPRINT
    } else {
        CanonicalSha256(domain).field("value", value).finish()
    }

private fun optionalStoredAuditFingerprint(value: String): String =
    if (value.isEmpty()) EMPTY_AUDIT_FINGERPRINT else requireStoredAuditFingerprint(value)

private fun requireStoredAuditFingerprint(value: String): String =
    value.also { requireValidAuditFingerprint(it, allowEmptySentinel = false) }

private fun requireValidAuditFingerprint(value: String, allowEmptySentinel: Boolean) {
    require(value.length == AUDIT_FINGERPRINT_WIDTH && value.none { it !in '0'..'9' && it !in 'a'..'f' })
    if (!allowEmptySentinel) require(value != EMPTY_AUDIT_FINGERPRINT)
}

private fun Long.toAuditNumber(): String {
    require(this >= 0L)
    return toString().padStart(AUDIT_NUMBER_WIDTH, '0')
}

private fun encodeAuditWireValues(values: Map<AuditWireField, String>): String =
    AuditWireField.entries.joinToString(separator = AUDIT_WIRE_SEPARATOR) { field ->
        val value = values.getValue(field)
        check(value.length == field.width) { "Invalid ${field.name} audit wire width" }
        value
    }

private fun decodeAuditWireValues(encoded: String): Map<AuditWireField, String> {
    check(encoded.length == AUDIT_WIRE_LENGTH)
    val parts = encoded.split(AUDIT_WIRE_SEPARATOR)
    check(parts.size == AuditWireField.entries.size)
    return AuditWireField.entries.associateWith { field ->
        parts[field.ordinal].also { check(it.length == field.width) }
    }
}

private fun Map<AuditWireField, String>.auditLong(field: AuditWireField): Long =
    getValue(field).also { value -> check(value.all(Char::isDigit)) }.toLong()

private fun auditMetadataValueIsValidSql(valueExpression: String): String {
    val conditions =
        mutableListOf(
            "length($valueExpression) = $AUDIT_WIRE_LENGTH",
            "${auditSliceSql(valueExpression, AuditWireField.FORMAT)} = '$AUDIT_WIRE_FORMAT'",
            "${auditSliceSql(valueExpression, AuditWireField.TRANSITION)} IN ('I', 'R')",
            "${auditSliceSql(valueExpression, AuditWireField.PROTOCOL_FINGERPRINT)} = " +
                "'$CALIBRATION_AUDIT_PROTOCOL_FINGERPRINT'",
            "${auditNumericSql(valueExpression, AuditWireField.AUDIT_REVISION)} > 0",
            "${auditNumericSql(valueExpression, AuditWireField.SCHEMA_VERSION)} > 0",
            "${auditNumericSql(valueExpression, AuditWireField.ACTIVE_GENERATION_COUNT)} " +
                "IN (0, 1)",
        )
    AuditWireField.entries.dropLast(1).forEach { field ->
        val delimiterPosition = AUDIT_WIRE_POSITIONS.getValue(field) + field.width
        conditions += "substr($valueExpression, $delimiterPosition, 1) = '$AUDIT_WIRE_SEPARATOR'"
    }
    AUDIT_NUMERIC_FIELDS.forEach { field ->
        conditions += "${auditSliceSql(valueExpression, field)} NOT GLOB '*[^0-9]*'"
    }
    AUDIT_FINGERPRINT_FIELDS.forEach { field ->
        conditions += "${auditSliceSql(valueExpression, field)} NOT GLOB '*[^0-9a-f]*'"
    }
    conditions +=
        auditAppendOnlyStateIsInternallyValidSql(
            valueExpression,
            AuditWireField.EVENT_COUNT,
            AuditWireField.EVENT_MAX_ROW_ID,
            AuditWireField.EVENT_HEAD_FINGERPRINT,
        )
    conditions +=
        "((${auditNumericSql(valueExpression, AuditWireField.EVENT_COUNT)} = 0 " +
        "AND ${auditNumericSql(valueExpression, AuditWireField.EVENT_MAX_SEQUENCE)} = 0) OR " +
        "(${auditNumericSql(valueExpression, AuditWireField.EVENT_COUNT)} > 0 " +
        "AND ${auditNumericSql(valueExpression, AuditWireField.EVENT_MAX_SEQUENCE)} > 0))"
    listOf(
        Triple(
            AuditWireField.ATTRIBUTION_COUNT,
            AuditWireField.ATTRIBUTION_MAX_ROW_ID,
            AuditWireField.ATTRIBUTION_HEAD_FINGERPRINT,
        ),
        Triple(
            AuditWireField.REVIEW_CASE_COUNT,
            AuditWireField.REVIEW_CASE_MAX_ROW_ID,
            AuditWireField.REVIEW_CASE_HEAD_FINGERPRINT,
        ),
        Triple(
            AuditWireField.REVIEW_RESOLUTION_COUNT,
            AuditWireField.REVIEW_RESOLUTION_MAX_ROW_ID,
            AuditWireField.REVIEW_RESOLUTION_HEAD_FINGERPRINT,
        ),
        Triple(
            AuditWireField.SUPERSESSION_COUNT,
            AuditWireField.SUPERSESSION_MAX_ROW_ID,
            AuditWireField.SUPERSESSION_HEAD_FINGERPRINT,
        ),
        Triple(
            AuditWireField.CALIBRATION_SNAPSHOT_COUNT,
            AuditWireField.CALIBRATION_SNAPSHOT_MAX_ROW_ID,
            AuditWireField.CALIBRATION_SNAPSHOT_HEAD_FINGERPRINT,
        ),
    ).forEach { (count, rowId, head) ->
        conditions += auditAppendOnlyStateIsInternallyValidSql(valueExpression, count, rowId, head)
    }
    conditions +=
        "((${auditNumericSql(valueExpression, AuditWireField.PROJECTION_COUNT)} = 0 " +
        "AND ${auditNumericSql(valueExpression, AuditWireField.PROJECTION_MAX_EVENT_SEQUENCE)} = 0) OR " +
        "(${auditNumericSql(valueExpression, AuditWireField.PROJECTION_COUNT)} > 0 " +
        "AND ${auditNumericSql(valueExpression, AuditWireField.PROJECTION_MAX_EVENT_SEQUENCE)} > 0))"
    conditions += activeGenerationStateIsInternallyValidSql(valueExpression)
    conditions +=
        "${auditSliceSql(valueExpression, AuditWireField.GUARD_FINGERPRINT)} != " +
        "'$EMPTY_AUDIT_FINGERPRINT'"
    conditions +=
        "${auditSliceSql(valueExpression, AuditWireField.SELF_FINGERPRINT)} != " +
        "'$EMPTY_AUDIT_FINGERPRINT'"
    return conditions.joinToString(separator = "\nAND ", prefix = "(", postfix = ")")
}

private fun incrementalAuditTransitionIsValidSql(): String {
    val conditions =
        mutableListOf(
            auditFieldsEqualSql(AuditWireField.GUARD_FINGERPRINT),
            auditFieldsEqualSql(AuditWireField.SCHEMA_VERSION),
            auditFieldsEqualSql(AuditWireField.ACTIVE_GENERATION_COUNT),
            auditFieldsEqualSql(AuditWireField.ACTIVE_GENERATION_ID),
            auditFieldsEqualSql(AuditWireField.ACTIVE_GENERATION_SNAPSHOT_FINGERPRINT),
            auditFieldsEqualSql(AuditWireField.ACTIVE_GENERATION_MANIFEST_FINGERPRINT),
            auditFieldsEqualSql(AuditWireField.ACTIVE_GENERATION_POLICY_FINGERPRINT),
            auditFieldsEqualSql(AuditWireField.ACTIVE_GENERATION_CALIBRATION_FINGERPRINT),
            auditNumericDoesNotDecreaseSql(AuditWireField.EVENT_MAX_SEQUENCE),
            auditNumericDoesNotDecreaseSql(AuditWireField.PROJECTION_COUNT),
            auditNumericDoesNotDecreaseSql(AuditWireField.PROJECTION_MAX_EVENT_SEQUENCE),
        )
    val incrementallyAuditedStates =
        listOf(
            Triple(
                AuditWireField.EVENT_COUNT,
                AuditWireField.EVENT_MAX_ROW_ID,
                AuditWireField.EVENT_HEAD_FINGERPRINT,
            ),
            Triple(
                AuditWireField.REVIEW_CASE_COUNT,
                AuditWireField.REVIEW_CASE_MAX_ROW_ID,
                AuditWireField.REVIEW_CASE_HEAD_FINGERPRINT,
            ),
            Triple(
                AuditWireField.REVIEW_RESOLUTION_COUNT,
                AuditWireField.REVIEW_RESOLUTION_MAX_ROW_ID,
                AuditWireField.REVIEW_RESOLUTION_HEAD_FINGERPRINT,
            ),
            Triple(
                AuditWireField.ATTRIBUTION_COUNT,
                AuditWireField.ATTRIBUTION_MAX_ROW_ID,
                AuditWireField.ATTRIBUTION_HEAD_FINGERPRINT,
            ),
            Triple(
                AuditWireField.SUPERSESSION_COUNT,
                AuditWireField.SUPERSESSION_MAX_ROW_ID,
                AuditWireField.SUPERSESSION_HEAD_FINGERPRINT,
            ),
            Triple(
                AuditWireField.CALIBRATION_SNAPSHOT_COUNT,
                AuditWireField.CALIBRATION_SNAPSHOT_MAX_ROW_ID,
                AuditWireField.CALIBRATION_SNAPSHOT_HEAD_FINGERPRINT,
            ),
        )
    incrementallyAuditedStates.forEach { (count, rowId, head) ->
        conditions += appendOnlyAuditTransitionIsValidSql(count, rowId, head)
    }
    return conditions.joinToString(separator = "\nAND ", prefix = "(", postfix = ")")
}

private fun auditAppendOnlyStateIsInternallyValidSql(
    valueExpression: String,
    countField: AuditWireField,
    rowIdField: AuditWireField,
    headField: AuditWireField,
): String {
    val count = auditNumericSql(valueExpression, countField)
    val rowId = auditNumericSql(valueExpression, rowIdField)
    val head = auditSliceSql(valueExpression, headField)
    return "(($count = 0 AND $rowId = 0 AND $head = '$EMPTY_AUDIT_FINGERPRINT') OR " +
        "($count > 0 AND $rowId > 0 AND $head != '$EMPTY_AUDIT_FINGERPRINT'))"
}

private fun activeGenerationStateIsInternallyValidSql(valueExpression: String): String {
    val count = auditNumericSql(valueExpression, AuditWireField.ACTIVE_GENERATION_COUNT)
    val id = auditNumericSql(valueExpression, AuditWireField.ACTIVE_GENERATION_ID)
    val fingerprints =
        listOf(
            AuditWireField.ACTIVE_GENERATION_SNAPSHOT_FINGERPRINT,
            AuditWireField.ACTIVE_GENERATION_MANIFEST_FINGERPRINT,
            AuditWireField.ACTIVE_GENERATION_POLICY_FINGERPRINT,
            AuditWireField.ACTIVE_GENERATION_CALIBRATION_FINGERPRINT,
        ).map { auditSliceSql(valueExpression, it) }
    val allEmpty = fingerprints.joinToString(" AND ") { "$it = '$EMPTY_AUDIT_FINGERPRINT'" }
    val requiredPresent =
        listOf(
            AuditWireField.ACTIVE_GENERATION_SNAPSHOT_FINGERPRINT,
            AuditWireField.ACTIVE_GENERATION_POLICY_FINGERPRINT,
            AuditWireField.ACTIVE_GENERATION_CALIBRATION_FINGERPRINT,
        ).joinToString(" AND ") { field ->
            "${auditSliceSql(valueExpression, field)} != '$EMPTY_AUDIT_FINGERPRINT'"
        }
    return "(($count = 0 AND $id = 0 AND $allEmpty) OR " +
        "($count = 1 AND $id > 0 AND $requiredPresent))"
}

private fun appendOnlyAuditTransitionIsValidSql(
    countField: AuditWireField,
    rowIdField: AuditWireField,
    headField: AuditWireField,
): String {
    val oldCount = auditNumericSql("OLD.metadata_value", countField)
    val newCount = auditNumericSql("NEW.metadata_value", countField)
    val oldRowId = auditNumericSql("OLD.metadata_value", rowIdField)
    val newRowId = auditNumericSql("NEW.metadata_value", rowIdField)
    val oldHead = auditSliceSql("OLD.metadata_value", headField)
    val newHead = auditSliceSql("NEW.metadata_value", headField)
    return "(($newCount = $oldCount AND $newRowId = $oldRowId AND $newHead = $oldHead) OR " +
        "($newCount > $oldCount AND $newRowId > $oldRowId " +
        "AND $newHead != '$EMPTY_AUDIT_FINGERPRINT' AND $newHead != $oldHead))"
}

private fun auditFieldsEqualSql(field: AuditWireField): String =
    "${auditSliceSql("NEW.metadata_value", field)} = ${auditSliceSql("OLD.metadata_value", field)}"

private fun auditNumericDoesNotDecreaseSql(field: AuditWireField): String =
    "${auditNumericSql("NEW.metadata_value", field)} >= " +
        auditNumericSql("OLD.metadata_value", field)

private fun auditNumericSql(valueExpression: String, field: AuditWireField): String =
    "CAST(${auditSliceSql(valueExpression, field)} AS INTEGER)"

private fun auditSliceSql(valueExpression: String, field: AuditWireField): String =
    "substr($valueExpression, ${AUDIT_WIRE_POSITIONS.getValue(field)}, ${field.width})"

private enum class AuditWireField(
    val width: Int,
) {
    FORMAT(5),
    TRANSITION(1),
    AUDIT_REVISION(AUDIT_NUMBER_WIDTH),
    PROTOCOL_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    GUARD_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    SCHEMA_VERSION(AUDIT_NUMBER_WIDTH),
    EVENT_COUNT(AUDIT_NUMBER_WIDTH),
    EVENT_MAX_ROW_ID(AUDIT_NUMBER_WIDTH),
    EVENT_MAX_SEQUENCE(AUDIT_NUMBER_WIDTH),
    EVENT_HEAD_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    ATTRIBUTION_COUNT(AUDIT_NUMBER_WIDTH),
    ATTRIBUTION_MAX_ROW_ID(AUDIT_NUMBER_WIDTH),
    ATTRIBUTION_HEAD_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    REVIEW_CASE_COUNT(AUDIT_NUMBER_WIDTH),
    REVIEW_CASE_MAX_ROW_ID(AUDIT_NUMBER_WIDTH),
    REVIEW_CASE_HEAD_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    REVIEW_RESOLUTION_COUNT(AUDIT_NUMBER_WIDTH),
    REVIEW_RESOLUTION_MAX_ROW_ID(AUDIT_NUMBER_WIDTH),
    REVIEW_RESOLUTION_HEAD_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    SUPERSESSION_COUNT(AUDIT_NUMBER_WIDTH),
    SUPERSESSION_MAX_ROW_ID(AUDIT_NUMBER_WIDTH),
    SUPERSESSION_HEAD_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    CALIBRATION_SNAPSHOT_COUNT(AUDIT_NUMBER_WIDTH),
    CALIBRATION_SNAPSHOT_MAX_ROW_ID(AUDIT_NUMBER_WIDTH),
    CALIBRATION_SNAPSHOT_HEAD_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    PROJECTION_COUNT(AUDIT_NUMBER_WIDTH),
    PROJECTION_MAX_EVENT_SEQUENCE(AUDIT_NUMBER_WIDTH),
    ACTIVE_GENERATION_COUNT(AUDIT_NUMBER_WIDTH),
    ACTIVE_GENERATION_ID(AUDIT_NUMBER_WIDTH),
    ACTIVE_GENERATION_SNAPSHOT_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    ACTIVE_GENERATION_MANIFEST_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    ACTIVE_GENERATION_POLICY_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    ACTIVE_GENERATION_CALIBRATION_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
    SELF_FINGERPRINT(AUDIT_FINGERPRINT_WIDTH),
}

private val AUDIT_NUMERIC_FIELDS =
    setOf(
        AuditWireField.AUDIT_REVISION,
        AuditWireField.SCHEMA_VERSION,
        AuditWireField.EVENT_COUNT,
        AuditWireField.EVENT_MAX_ROW_ID,
        AuditWireField.EVENT_MAX_SEQUENCE,
        AuditWireField.ATTRIBUTION_COUNT,
        AuditWireField.ATTRIBUTION_MAX_ROW_ID,
        AuditWireField.REVIEW_CASE_COUNT,
        AuditWireField.REVIEW_CASE_MAX_ROW_ID,
        AuditWireField.REVIEW_RESOLUTION_COUNT,
        AuditWireField.REVIEW_RESOLUTION_MAX_ROW_ID,
        AuditWireField.SUPERSESSION_COUNT,
        AuditWireField.SUPERSESSION_MAX_ROW_ID,
        AuditWireField.CALIBRATION_SNAPSHOT_COUNT,
        AuditWireField.CALIBRATION_SNAPSHOT_MAX_ROW_ID,
        AuditWireField.PROJECTION_COUNT,
        AuditWireField.PROJECTION_MAX_EVENT_SEQUENCE,
        AuditWireField.ACTIVE_GENERATION_COUNT,
        AuditWireField.ACTIVE_GENERATION_ID,
    )

private val AUDIT_FINGERPRINT_FIELDS =
    setOf(
        AuditWireField.PROTOCOL_FINGERPRINT,
        AuditWireField.GUARD_FINGERPRINT,
        AuditWireField.EVENT_HEAD_FINGERPRINT,
        AuditWireField.ATTRIBUTION_HEAD_FINGERPRINT,
        AuditWireField.REVIEW_CASE_HEAD_FINGERPRINT,
        AuditWireField.REVIEW_RESOLUTION_HEAD_FINGERPRINT,
        AuditWireField.SUPERSESSION_HEAD_FINGERPRINT,
        AuditWireField.CALIBRATION_SNAPSHOT_HEAD_FINGERPRINT,
        AuditWireField.ACTIVE_GENERATION_SNAPSHOT_FINGERPRINT,
        AuditWireField.ACTIVE_GENERATION_MANIFEST_FINGERPRINT,
        AuditWireField.ACTIVE_GENERATION_POLICY_FINGERPRINT,
        AuditWireField.ACTIVE_GENERATION_CALIBRATION_FINGERPRINT,
        AuditWireField.SELF_FINGERPRINT,
    )

private val AUDIT_WIRE_POSITIONS: Map<AuditWireField, Int> =
    buildMap {
        var position = 1
        AuditWireField.entries.forEach { field ->
            put(field, position)
            position += field.width + AUDIT_WIRE_SEPARATOR.length
        }
    }

private val AUDIT_WIRE_LENGTH =
    AuditWireField.entries.sumOf(AuditWireField::width) +
        (AuditWireField.entries.size - 1) * AUDIT_WIRE_SEPARATOR.length

private val CALIBRATION_AUDIT_PROTOCOL_FINGERPRINT =
    CanonicalSha256("learner-mastery-calibration-audit-protocol-v1")
        .field("value", LEARNER_MASTERY_CALIBRATION_AUDIT_PROTOCOL)
        .finish()

private const val AUDIT_WIRE_FORMAT = "LMAW2"
private const val AUDIT_WIRE_SEPARATOR = "|"
private const val AUDIT_NUMBER_WIDTH = 20
private const val AUDIT_FINGERPRINT_WIDTH = 64
private const val EMPTY_AUDIT_FINGERPRINT =
    "0000000000000000000000000000000000000000000000000000000000000000"
