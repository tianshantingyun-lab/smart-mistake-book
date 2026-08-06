package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.sqlite.SQLiteConnection

internal const val LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE =
    "mastery_projection_input_fact"
internal const val LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE =
    "mastery_projection_input_coverage_gap"
internal const val LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE =
    "mastery_calibration_release"
internal const val LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE =
    "mastery_calibration_profile_header"
internal const val LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE =
    "mastery_calibration_validation_metric"
internal const val LEARNER_MASTERY_PROJECTION_INPUT_REPLAY_INDEX =
    "index_mastery_projection_input_fact_replay_keyset"

internal val LEARNER_MASTERY_DB14_IMMUTABLE_TABLE_NAMES =
    setOf(
        LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE,
        LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
        LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE,
        LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE,
        LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE,
    )

/**
 * Immutable, structured behavior facts for a future calibrated projection.
 *
 * This table deliberately contains no evidence weight, coefficient, probability, or mastery
 * score. Eligibility facts can therefore be replayed under a later signed calibration release
 * without rewriting the learning ledger. DB14 does not populate this table because reconstructing
 * behavior order or eligibility for old rows would require guessing missing history.
 */
@Entity(
    tableName = LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE,
    primaryKeys = ["event_id", "attribution_ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = MasteryLearningEventEntity::class,
            parentColumns = ["event_id", "canonical_fingerprint"],
            childColumns = ["event_id", "event_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = MasteryLearningEventAttributionEntity::class,
            parentColumns = ["event_id", "ordinal"],
            childColumns = ["event_id", "attribution_ordinal"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            name = "index_mastery_projection_input_fact_event_fingerprint",
            value = ["event_id", "event_canonical_fingerprint"],
        ),
        Index(
            name = LEARNER_MASTERY_PROJECTION_INPUT_REPLAY_INDEX,
            // Replay is scoped to one knowledge identity, then strictly ordered by the immutable
            // ledger sequence before the behavior sub-order within that event.
            value = [
                "learner_id",
                "subject",
                "knowledge_node_id",
                "taxonomy_version",
                "event_sequence",
                "behavior_order",
                "event_id",
                "attribution_ordinal",
            ],
            unique = true,
        ),
        Index(
            name = "index_mastery_projection_input_fact_canonical_fingerprint",
            value = ["canonical_fingerprint"],
            unique = true,
        ),
    ],
)
internal data class MasteryProjectionInputFactEntity(
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "attribution_ordinal")
    val attributionOrdinal: Int,
    @ColumnInfo(name = "event_canonical_fingerprint")
    val eventCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "knowledge_node_id")
    val knowledgeNodeId: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "behavior_order")
    val behaviorOrder: Long,
    @ColumnInfo(name = "eligibility_basis")
    val eligibilityBasis: String,
    @ColumnInfo(name = "projection_eligible")
    val projectionEligible: Boolean,
    val outcome: String,
    val assistance: String,
    @ColumnInfo(name = "retry_state")
    val retryState: String,
    @ColumnInfo(name = "attempt_ordinal")
    val attemptOrdinal: Int,
    @ColumnInfo(name = "hint_count")
    val hintCount: Int,
    @ColumnInfo(name = "answer_was_revealed")
    val answerWasRevealed: Boolean,
    @ColumnInfo(name = "independently_answered")
    val independentlyAnswered: Boolean,
    @ColumnInfo(name = "source_proof_fingerprint")
    val sourceProofFingerprint: String,
    @ColumnInfo(name = "attribution_fingerprint")
    val attributionFingerprint: String,
    @ColumnInfo(name = "canonical_fingerprint")
    val canonicalFingerprint: String,
    @ColumnInfo(name = "captured_at_epoch_millis")
    val capturedAtEpochMillis: Long,
)

/** Explicitly records why an immutable event cannot yet enter a calibrated replay. */
@Entity(
    tableName = LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = MasteryLearningEventEntity::class,
            parentColumns = ["event_id", "canonical_fingerprint"],
            childColumns = ["event_id", "event_canonical_fingerprint"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            name = "index_mastery_projection_input_coverage_gap_event_fingerprint",
            value = ["event_id", "event_canonical_fingerprint"],
        ),
        Index(
            name = "index_mastery_projection_input_coverage_gap_replay_keyset",
            value = [
                "learner_id",
                "subject",
                "event_sequence",
                "event_id",
                "missing_component",
            ],
            unique = true,
        ),
    ],
)
internal data class MasteryProjectionInputCoverageGapEntity(
    @androidx.room3.PrimaryKey
    @ColumnInfo(name = "gap_fingerprint")
    val gapFingerprint: String,
    @ColumnInfo(name = "event_id")
    val eventId: String,
    @ColumnInfo(name = "event_canonical_fingerprint")
    val eventCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "event_sequence")
    val eventSequence: Long,
    @ColumnInfo(name = "missing_component")
    val missingComponent: String,
    @ColumnInfo(name = "detector_version")
    val detectorVersion: String,
    @ColumnInfo(name = "detected_at_epoch_millis")
    val detectedAtEpochMillis: Long,
)

/**
 * Header for a future offline-calibrated release.
 *
 * DB14 only provides a quarantine ledger. It intentionally has no activation API and the database
 * guard rejects ACTIVE releases, so adding rows cannot change the production v3/v4 projection.
 */
@Entity(
    tableName = LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE,
    indices = [
        Index(
            name = "index_mastery_calibration_release_release_fingerprint",
            value = ["release_fingerprint"],
            unique = true,
        ),
        Index(
            name = "index_mastery_calibration_release_state_created_at",
            value = ["state", "created_at_epoch_millis"],
        ),
    ],
)
internal data class MasteryCalibrationReleaseEntity(
    @androidx.room3.PrimaryKey
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    val state: String,
    @ColumnInfo(name = "release_schema_version")
    val releaseSchemaVersion: String,
    @ColumnInfo(name = "projection_policy_version")
    val projectionPolicyVersion: String,
    @ColumnInfo(name = "calibration_version")
    val calibrationVersion: String,
    @ColumnInfo(name = "source_input_set_fingerprint")
    val sourceInputSetFingerprint: String,
    @ColumnInfo(name = "projection_implementation_fingerprint")
    val projectionImplementationFingerprint: String,
    @ColumnInfo(name = "profile_manifest_fingerprint")
    val profileManifestFingerprint: String,
    @ColumnInfo(name = "validation_manifest_fingerprint")
    val validationManifestFingerprint: String,
    @ColumnInfo(name = "release_fingerprint")
    val releaseFingerprint: String,
    @ColumnInfo(name = "created_at_epoch_millis")
    val createdAtEpochMillis: Long,
)

/** A profile header only; parameter payloads and executable expressions are never stored here. */
@Entity(
    tableName = LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE,
    primaryKeys = ["release_id", "profile_id"],
    foreignKeys = [
        ForeignKey(
            entity = MasteryCalibrationReleaseEntity::class,
            parentColumns = ["release_id"],
            childColumns = ["release_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            name = "index_mastery_calibration_profile_header_release_id",
            value = ["release_id"],
        ),
        Index(
            name = "index_mastery_calibration_profile_header_subject_taxonomy",
            value = ["subject", "taxonomy_version", "release_id"],
        ),
        Index(
            name = "index_mastery_calibration_profile_header_fingerprint",
            value = ["header_fingerprint"],
            unique = true,
        ),
    ],
)
internal data class MasteryCalibrationProfileHeaderEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    val subject: String,
    @ColumnInfo(name = "taxonomy_version")
    val taxonomyVersion: String,
    @ColumnInfo(name = "population_scope")
    val populationScope: String,
    @ColumnInfo(name = "parameter_schema_version")
    val parameterSchemaVersion: String,
    @ColumnInfo(name = "parameter_set_fingerprint")
    val parameterSetFingerprint: String,
    @ColumnInfo(name = "training_source_fingerprint")
    val trainingSourceFingerprint: String,
    @ColumnInfo(name = "evaluation_plan_fingerprint")
    val evaluationPlanFingerprint: String,
    @ColumnInfo(name = "header_fingerprint")
    val headerFingerprint: String,
)

/** Immutable validation evidence; no acceptance threshold or default coefficient is embedded. */
@Entity(
    tableName = LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE,
    primaryKeys = ["release_id", "profile_id", "metric_id", "split_id"],
    foreignKeys = [
        ForeignKey(
            entity = MasteryCalibrationProfileHeaderEntity::class,
            parentColumns = ["release_id", "profile_id"],
            childColumns = ["release_id", "profile_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(
            name = "index_mastery_calibration_validation_metric_profile",
            value = ["release_id", "profile_id"],
        ),
        Index(
            name = "index_mastery_calibration_validation_metric_fingerprint",
            value = ["metric_fingerprint"],
            unique = true,
        ),
    ],
)
internal data class MasteryCalibrationValidationMetricEntity(
    @ColumnInfo(name = "release_id")
    val releaseId: String,
    @ColumnInfo(name = "profile_id")
    val profileId: String,
    @ColumnInfo(name = "metric_id")
    val metricId: String,
    @ColumnInfo(name = "split_id")
    val splitId: String,
    @ColumnInfo(name = "sample_count")
    val sampleCount: Long,
    @ColumnInfo(name = "metric_value_micros")
    val metricValueMicros: Long,
    @ColumnInfo(name = "cohort_fingerprint")
    val cohortFingerprint: String,
    @ColumnInfo(name = "metric_fingerprint")
    val metricFingerprint: String,
    @ColumnInfo(name = "evaluated_at_epoch_millis")
    val evaluatedAtEpochMillis: Long,
)

internal fun installLearnerMasteryNextCalibrationGuards(connection: SQLiteConnection) {
    replaceLearnerMasteryTriggerDefinitionsAtomically(
        connection = connection,
        savepointName = NEXT_CALIBRATION_GUARD_INSTALL_SAVEPOINT,
        definitions = NEXT_CALIBRATION_TRIGGER_DEFINITIONS,
        audit = { auditLearnerMasteryNextCalibrationState(connection) },
    )
}

private fun auditLearnerMasteryNextCalibrationState(connection: SQLiteConnection) {
    check(
        !connection.nextCalibrationHasRow(
            "SELECT 1 FROM $LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE " +
                "AS release WHERE NOT (" +
                VALID_CALIBRATION_RELEASE.replace("NEW.", "release.") +
                ") LIMIT 1",
        ),
    ) { "Learner-mastery calibration release is unsafe or activatable" }
    check(
        !connection.nextCalibrationHasRow(
            "SELECT 1 FROM $LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE AS input " +
                "WHERE NOT (" +
                VALID_AND_PARENT_MATCHED_PROJECTION_INPUT_FACT.replace("NEW.", "input.") +
                ") LIMIT 1",
        ),
    ) { "Learner-mastery projection input fact is unsafe" }
    check(
        !connection.nextCalibrationHasRow(
            "SELECT 1 FROM $LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE AS gap " +
                "WHERE NOT (" +
                VALID_AND_PARENT_MATCHED_PROJECTION_INPUT_COVERAGE_GAP.replace("NEW.", "gap.") +
                ") LIMIT 1",
        ),
    ) { "Learner-mastery projection input coverage gap is unsafe" }
    check(
        !connection.nextCalibrationHasRow(
            "SELECT 1 FROM $LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE AS profile " +
                "WHERE NOT (" +
                VALID_CALIBRATION_PROFILE_HEADER.replace("NEW.", "profile.") +
                ") LIMIT 1",
        ),
    ) { "Learner-mastery calibration profile header is unsafe" }
    check(
        !connection.nextCalibrationHasRow(
            "SELECT 1 FROM $LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE AS metric " +
                "WHERE NOT (" +
                VALID_CALIBRATION_VALIDATION_METRIC.replace("NEW.", "metric.") +
                ") LIMIT 1",
        ),
    ) { "Learner-mastery calibration validation metric is unsafe" }
    check(
        !connection.nextCalibrationHasRow(
            "SELECT 1 FROM mastery_projection_generation AS generation " +
                "WHERE NOT (${VALID_PROJECTION_GENERATION_BINDING.replace("NEW.", "generation.")}) " +
                "LIMIT 1",
        ),
    ) { "Learner-mastery projection generation has an invalid release binding" }
}

private fun SQLiteConnection.nextCalibrationHasRow(sql: String): Boolean =
    prepare(sql).use { statement -> statement.step() }

private fun validationTrigger(
    name: String,
    operation: String,
    table: String,
    validPredicate: String,
): LearnerMasteryTriggerDefinition =
    LearnerMasteryTriggerDefinition(
        name = name,
        sql =
            """
            CREATE TRIGGER $name
            BEFORE $operation ON $table
            WHEN NOT ($validPredicate)
            BEGIN
                SELECT RAISE(ABORT, 'invalid learner-mastery calibration infrastructure row');
            END
            """.trimIndent(),
    )

private val VALID_FINGERPRINT_COLUMNS =
    listOf(
        "NEW.source_input_set_fingerprint",
        "NEW.projection_implementation_fingerprint",
        "NEW.profile_manifest_fingerprint",
        "NEW.validation_manifest_fingerprint",
        "NEW.release_fingerprint",
    ).joinToString(" AND ") { validFingerprint(it) }

private val VALID_CALIBRATION_RELEASE =
    "NEW.state IN ('CANDIDATE', 'REJECTED') AND " +
        validToken("NEW.release_id", 128) + " AND " +
        validToken("NEW.release_schema_version", 128) + " AND " +
        validToken("NEW.projection_policy_version", 128) + " AND " +
        validToken("NEW.calibration_version", 128) + " AND " +
        VALID_FINGERPRINT_COLUMNS + " AND NEW.created_at_epoch_millis >= 0"

private val VALID_PROJECTION_INPUT_FACT =
    "NEW.attribution_ordinal >= 0 AND NEW.event_sequence > 0 AND " +
        "NEW.behavior_order >= 0 AND NEW.attempt_ordinal >= 0 AND NEW.hint_count >= 0 AND " +
        "NEW.projection_eligible IN (0, 1) AND " +
        "NEW.answer_was_revealed IN (0, 1) AND NEW.independently_answered IN (0, 1) AND " +
        validToken("NEW.eligibility_basis", 64) + " AND " +
        validToken("NEW.outcome", 64) + " AND " +
        validToken("NEW.assistance", 64) + " AND " +
        validToken("NEW.retry_state", 64) + " AND " +
        validFingerprint("NEW.event_canonical_fingerprint") + " AND " +
        validFingerprint("NEW.source_proof_fingerprint") + " AND " +
        validFingerprint("NEW.attribution_fingerprint") + " AND " +
        validFingerprint("NEW.canonical_fingerprint") + " AND " +
        "NEW.captured_at_epoch_millis >= 0"

/**
 * Every duplicated ledger identity is checked against its immutable parents before admission.
 * This makes the projection-input row a replay view of ledger facts rather than a second source
 * of truth that can drift independently.
 */
private val PROJECTION_INPUT_FACT_MATCHES_IMMUTABLE_PARENTS =
    """
    EXISTS (
        SELECT 1
        FROM mastery_learning_event AS event
        JOIN mastery_learning_event_attribution AS attribution
          ON attribution.event_id = event.event_id
         AND attribution.ordinal = NEW.attribution_ordinal
        JOIN mastery_candidate_attribution AS candidate_attribution
          ON candidate_attribution.candidate_id = event.candidate_id
         AND candidate_attribution.ordinal = NEW.attribution_ordinal
        JOIN mastery_source_fact AS source
          ON source.source_fact_id = event.source_fact_id
        WHERE event.event_id = NEW.event_id
          AND event.canonical_fingerprint = NEW.event_canonical_fingerprint
          AND event.learner_id = NEW.learner_id
          AND event.subject = NEW.subject
          AND event.event_sequence = NEW.event_sequence
          AND event.source_proof_fingerprint = NEW.source_proof_fingerprint
          AND event.independently_answered = NEW.independently_answered
          AND source.learner_id = NEW.learner_id
          AND source.subject = NEW.subject
          AND source.outcome = NEW.outcome
          AND source.assistance = NEW.assistance
          AND source.retry_state = NEW.retry_state
          AND source.hint_count = NEW.hint_count
          AND source.answer_revealed = NEW.answer_was_revealed
          AND attribution.subject = NEW.subject
          AND attribution.knowledge_node_id = NEW.knowledge_node_id
          AND attribution.taxonomy_version = NEW.taxonomy_version
          AND candidate_attribution.subject = NEW.subject
          AND candidate_attribution.knowledge_node_id = NEW.knowledge_node_id
          AND candidate_attribution.taxonomy_version = NEW.taxonomy_version
          AND candidate_attribution.proposal_fingerprint = NEW.attribution_fingerprint
    )
    """.trimIndent()

private val VALID_AND_PARENT_MATCHED_PROJECTION_INPUT_FACT =
    "($VALID_PROJECTION_INPUT_FACT) AND ($PROJECTION_INPUT_FACT_MATCHES_IMMUTABLE_PARENTS)"

private val VALID_PROJECTION_INPUT_COVERAGE_GAP =
    "NEW.event_sequence > 0 AND " +
        validToken("NEW.missing_component", 64) + " AND " +
        validToken("NEW.detector_version", 128) + " AND " +
        validFingerprint("NEW.gap_fingerprint") + " AND " +
        validFingerprint("NEW.event_canonical_fingerprint") + " AND " +
        "NEW.detected_at_epoch_millis >= 0"

private val PROJECTION_INPUT_COVERAGE_GAP_MATCHES_IMMUTABLE_PARENT =
    """
    EXISTS (
        SELECT 1
        FROM mastery_learning_event AS event
        WHERE event.event_id = NEW.event_id
          AND event.canonical_fingerprint = NEW.event_canonical_fingerprint
          AND event.learner_id = NEW.learner_id
          AND event.subject = NEW.subject
          AND event.event_sequence = NEW.event_sequence
    )
    """.trimIndent()

private val VALID_AND_PARENT_MATCHED_PROJECTION_INPUT_COVERAGE_GAP =
    "($VALID_PROJECTION_INPUT_COVERAGE_GAP) AND " +
        "($PROJECTION_INPUT_COVERAGE_GAP_MATCHES_IMMUTABLE_PARENT)"

private val VALID_CALIBRATION_PROFILE_HEADER =
    validToken("NEW.release_id", 128) + " AND " +
        validToken("NEW.profile_id", 128) + " AND " +
        validToken("NEW.subject", 32) + " AND " +
        validToken("NEW.taxonomy_version", 160) + " AND " +
        validToken("NEW.population_scope", 64) + " AND " +
        validToken("NEW.parameter_schema_version", 128) + " AND " +
        validFingerprint("NEW.parameter_set_fingerprint") + " AND " +
        validFingerprint("NEW.training_source_fingerprint") + " AND " +
        validFingerprint("NEW.evaluation_plan_fingerprint") + " AND " +
        validFingerprint("NEW.header_fingerprint")

private val VALID_CALIBRATION_VALIDATION_METRIC =
    validToken("NEW.release_id", 128) + " AND " +
        validToken("NEW.profile_id", 128) + " AND " +
        validToken("NEW.metric_id", 64) + " AND " +
        validToken("NEW.split_id", 64) + " AND " +
        "NEW.sample_count > 0 AND NEW.metric_value_micros >= 0 AND " +
        validFingerprint("NEW.cohort_fingerprint") + " AND " +
        validFingerprint("NEW.metric_fingerprint") + " AND " +
        "NEW.evaluated_at_epoch_millis >= 0"

private val VALID_PROJECTION_GENERATION_BINDING =
    """
    (
        NEW.calibration_release_id IS NULL
        AND NEW.calibration_release_fingerprint IS NULL
        AND NEW.projection_input_set_fingerprint IS NULL
        AND NEW.projection_implementation_fingerprint IS NULL
        AND NEW.generation_manifest_fingerprint IS NULL
        AND (
            NEW.state != 'ACTIVE'
            OR (
                (
                    NEW.target_projection_policy_version =
                        '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                    AND NEW.target_calibration_version =
                        '$LEARNER_MASTERY_CALIBRATION_VERSION'
                ) OR (
                    NEW.target_projection_policy_version = 'previous-active-projection'
                    AND NEW.target_calibration_version = 'previous-active-calibration'
                )
            )
        )
    ) OR (
        ${validToken("NEW.calibration_release_id", 128)}
        AND ${validFingerprint("NEW.calibration_release_fingerprint")}
        AND ${validFingerprint("NEW.projection_input_set_fingerprint")}
        AND ${validFingerprint("NEW.projection_implementation_fingerprint")}
        AND ${validFingerprint("NEW.generation_manifest_fingerprint")}
        AND EXISTS (
            SELECT 1
            FROM $LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE AS release
            WHERE release.release_id = NEW.calibration_release_id
              AND release.release_fingerprint = NEW.calibration_release_fingerprint
              AND release.source_input_set_fingerprint = NEW.projection_input_set_fingerprint
              AND release.projection_implementation_fingerprint =
                    NEW.projection_implementation_fingerprint
              AND (
                  NEW.state != 'ACTIVE'
                  OR release.state = 'ACTIVE'
              )
        )
    )
    """.trimIndent()

private val NEXT_CALIBRATION_TRIGGER_DEFINITIONS =
    listOf(
        validationTrigger(
            "validate_mastery_projection_input_fact_insert",
            "INSERT",
            LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE,
            VALID_AND_PARENT_MATCHED_PROJECTION_INPUT_FACT,
        ),
        validationTrigger(
            "validate_mastery_projection_input_coverage_gap_insert",
            "INSERT",
            LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
            VALID_AND_PARENT_MATCHED_PROJECTION_INPUT_COVERAGE_GAP,
        ),
        validationTrigger(
            "validate_mastery_calibration_release_insert",
            "INSERT",
            LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE,
            VALID_CALIBRATION_RELEASE,
        ),
        validationTrigger(
            "validate_mastery_calibration_profile_header_insert",
            "INSERT",
            LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE,
            VALID_CALIBRATION_PROFILE_HEADER,
        ),
        validationTrigger(
            "validate_mastery_calibration_validation_metric_insert",
            "INSERT",
            LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE,
            VALID_CALIBRATION_VALIDATION_METRIC,
        ),
        validationTrigger(
            "validate_mastery_projection_generation_release_binding_insert",
            "INSERT",
            "mastery_projection_generation",
            VALID_PROJECTION_GENERATION_BINDING,
        ),
        validationTrigger(
            "validate_mastery_projection_generation_release_binding_update",
            "UPDATE",
            "mastery_projection_generation",
            VALID_PROJECTION_GENERATION_BINDING,
        ),
    )

private fun validToken(column: String, maximumLength: Int): String =
    "length($column) BETWEEN 1 AND $maximumLength " +
        "AND $column NOT GLOB '*[^A-Za-z0-9._-]*'"

private fun validFingerprint(column: String): String =
    "length($column) = 64 AND $column NOT GLOB '*[^0-9a-f]*'"

private const val NEXT_CALIBRATION_GUARD_INSTALL_SAVEPOINT =
    "learner_mastery_next_calibration_guard_install"
