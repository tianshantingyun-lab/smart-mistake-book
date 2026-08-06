package com.tingyun.smartmistakebook.core.mastery.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal fun migrateVersion9To10CalibrationLeaseSchema(connection: SQLiteConnection) {
    installVersion10CalibrationRegistry(connection)
    validateVersion9CalibrationBindings(connection)
    migrateVersion9EvidenceReviews(connection)
    migrateVersion9LearningEvents(connection)
    migrateVersion9KnowledgeProjections(connection)
}

internal fun verifyVersion10CalibrationLeaseForeignKeys(connection: SQLiteConnection) {
    connection.prepare("PRAGMA foreign_key_check").use { statement ->
        check(!statement.step()) {
            "Learner-mastery v9 to v10 migration left a foreign-key violation"
        }
    }
}

private fun installVersion10CalibrationRegistry(connection: SQLiteConnection) {
    connection.execSQL(CREATE_LEASE_CALIBRATION_TABLE)
    persistLearnerMasteryCalibrationRegistry(
        connection = connection,
        tableName = LEASE_CALIBRATION_TABLE,
    )
    connection.execSQL("DROP TABLE mastery_calibration_snapshot")
    connection.execSQL(
        "ALTER TABLE $LEASE_CALIBRATION_TABLE RENAME TO mastery_calibration_snapshot",
    )
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_calibration_snapshot_profile_id " +
            "ON mastery_calibration_snapshot(profile_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_calibration_snapshot_snapshot_fingerprint " +
            "ON mastery_calibration_snapshot(snapshot_fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_calibration_snapshot_subject_calibration_version_profile_id_snapshot_fingerprint " +
            "ON mastery_calibration_snapshot(" +
            "subject, calibration_version, profile_id, snapshot_fingerprint)",
    )
}

private fun validateVersion9CalibrationBindings(connection: SQLiteConnection) {
    check(
        connection.calibrationLeaseScalarLong(
            """
            SELECT COUNT(*)
            FROM mastery_learning_event AS event
            LEFT JOIN mastery_calibration_snapshot AS snapshot
              ON snapshot.subject = event.subject
             AND snapshot.calibration_version = event.calibration_version
             AND snapshot.profile_id = event.calibration_profile_id
             AND snapshot.snapshot_fingerprint =
                 event.calibration_snapshot_fingerprint
            WHERE (
                event.projection_policy_version =
                    '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                AND (
                    event.calibration_profile_id IS NULL
                    OR event.calibration_snapshot_fingerprint IS NULL
                    OR snapshot.subject IS NULL
                )
            ) OR (
                event.projection_policy_version !=
                    '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                AND (
                    event.calibration_version !=
                        '$LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION'
                    OR event.calibration_profile_id IS NOT NULL
                    OR event.calibration_snapshot_fingerprint IS NOT NULL
                )
            )
            """.trimIndent(),
        ) == 0L,
    ) {
        "A v9 event has an unresolved or malformed calibration binding"
    }
    check(
        connection.calibrationLeaseScalarLong(
            """
            SELECT COUNT(*)
            FROM mastery_knowledge_projection AS projection
            LEFT JOIN mastery_calibration_snapshot AS snapshot
              ON snapshot.subject = projection.subject
             AND snapshot.profile_id = projection.calibration_profile_id
             AND snapshot.snapshot_fingerprint =
                 projection.calibration_snapshot_fingerprint
            WHERE (
                projection.projection_policy_version =
                    '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                AND (
                    projection.calibration_profile_id IS NULL
                    OR projection.calibration_snapshot_fingerprint IS NULL
                    OR snapshot.subject IS NULL
                )
            ) OR (
                projection.projection_policy_version !=
                    '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                AND (
                    projection.calibration_profile_id IS NOT NULL
                    OR projection.calibration_snapshot_fingerprint IS NOT NULL
                )
            )
            """.trimIndent(),
        ) == 0L,
    ) {
        "A v9 projection has an unresolved or malformed calibration binding"
    }
}

private fun migrateVersion9EvidenceReviews(connection: SQLiteConnection) {
    val caseCount =
        connection.calibrationLeaseScalarLong(
            "SELECT COUNT(*) FROM mastery_evidence_review_case",
        )
    val resolutionCount =
        connection.calibrationLeaseScalarLong(
            "SELECT COUNT(*) FROM mastery_evidence_review_resolution",
        )
    connection.execSQL(CREATE_LEASE_EVIDENCE_REVIEW_CASE_TABLE)
    connection.execSQL(
        """
        INSERT INTO $LEASE_EVIDENCE_REVIEW_CASE_TABLE (
            review_case_id,
            candidate_id,
            candidate_canonical_fingerprint,
            source_fact_id,
            source_proof_fingerprint,
            learner_id,
            subject,
            reason,
            admission_policy_version,
            calibration_binding_status,
            calibration_version,
            calibration_profile_id,
            calibration_snapshot_fingerprint,
            review_case_fingerprint,
            created_at_epoch_millis
        )
        SELECT review_case_id,
               candidate_id,
               candidate_canonical_fingerprint,
               source_fact_id,
               source_proof_fingerprint,
               learner_id,
               subject,
               reason,
               admission_policy_version,
               '${MasteryCalibrationBindingStatus.LEGACY_UNCALIBRATED.name}',
               NULL,
               NULL,
               NULL,
               review_case_fingerprint,
               created_at_epoch_millis
        FROM mastery_evidence_review_case
        """.trimIndent(),
    )
    connection.execSQL(CREATE_LEASE_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE)
    connection.execSQL(
        """
        INSERT INTO $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE (
            resolution_id,
            review_case_id,
            learner_id,
            subject,
            decision,
            authority,
            reviewer_version,
            review_evidence_fingerprint,
            unverified_calibration_snapshot_fingerprint,
            idempotency_key,
            resolution_fingerprint,
            decided_at_epoch_millis
        )
        SELECT resolution_id,
               review_case_id,
               learner_id,
               subject,
               decision,
               authority,
               reviewer_version,
               review_evidence_fingerprint,
               calibration_snapshot_fingerprint,
               idempotency_key,
               resolution_fingerprint,
               decided_at_epoch_millis
        FROM mastery_evidence_review_resolution
        """.trimIndent(),
    )
    check(
        connection.calibrationLeaseScalarLong(
            "SELECT COUNT(*) FROM $LEASE_EVIDENCE_REVIEW_CASE_TABLE",
        ) == caseCount,
    ) {
        "Learner-mastery v9 review cases were not copied exactly"
    }
    check(
        connection.calibrationLeaseScalarLong(
            "SELECT COUNT(*) FROM $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE",
        ) == resolutionCount,
    ) {
        "Learner-mastery v9 review resolution audit is incomplete"
    }
    connection.execSQL("DROP TABLE mastery_evidence_review_resolution")
    connection.execSQL("DROP TABLE mastery_evidence_review_case")
    connection.execSQL(
        "ALTER TABLE $LEASE_EVIDENCE_REVIEW_CASE_TABLE " +
            "RENAME TO mastery_evidence_review_case",
    )
    createLeaseEvidenceReviewCaseIndices(connection)
    createLeaseLegacyReviewResolutionAuditIndices(connection)
    connection.execSQL(CREATE_LEASE_EVIDENCE_REVIEW_RESOLUTION_TABLE)
    createLeaseEvidenceReviewResolutionIndices(connection)
}

private fun migrateVersion9LearningEvents(connection: SQLiteConnection) {
    val eventCount =
        connection.calibrationLeaseScalarLong("SELECT COUNT(*) FROM mastery_learning_event")
    connection.execSQL(CREATE_LEASE_LEARNING_EVENT_TABLE)
    connection.execSQL(
        """
        INSERT INTO $LEASE_LEARNING_EVENT_TABLE (
            event_id,
            candidate_id,
            source_fact_id,
            source_proof_fingerprint,
            learner_id,
            subject,
            direction,
            event_sequence,
            occurred_at_epoch_millis,
            admitted_at_epoch_millis,
            projection_policy_version,
            admission_policy_version,
            calibration_version,
            canonical_fingerprint,
            problem_family_fingerprint,
            presentation_fingerprint,
            evidence_quality_micros,
            independently_answered,
            calibration_snapshot_fingerprint,
            calibration_profile_id,
            review_resolution_fingerprint
        )
        SELECT event_id,
               candidate_id,
               source_fact_id,
               source_proof_fingerprint,
               learner_id,
               subject,
               direction,
               event_sequence,
               occurred_at_epoch_millis,
               admitted_at_epoch_millis,
               projection_policy_version,
               admission_policy_version,
               calibration_version,
               canonical_fingerprint,
               problem_family_fingerprint,
               presentation_fingerprint,
               evidence_quality_micros,
               independently_answered,
               calibration_snapshot_fingerprint,
               calibration_profile_id,
               review_resolution_fingerprint
        FROM mastery_learning_event
        """.trimIndent(),
    )
    check(
        connection.calibrationLeaseScalarLong(
            "SELECT COUNT(*) FROM $LEASE_LEARNING_EVENT_TABLE",
        ) == eventCount,
    ) {
        "Learner-mastery v9 events were not copied exactly"
    }
    backupVersion9LearningEventChildren(connection)
    connection.execSQL("DROP TABLE mastery_learning_evidence_supersession")
    connection.execSQL("DROP TABLE mastery_learning_event_attribution")
    connection.execSQL("DROP TABLE mastery_applied_event")
    connection.execSQL("DROP TABLE mastery_learning_event")
    connection.execSQL(
        "ALTER TABLE $LEASE_LEARNING_EVENT_TABLE RENAME TO mastery_learning_event",
    )
    createLeaseLearningEventIndices(connection)
    restoreVersion9LearningEventChildren(connection)
}

private fun backupVersion9LearningEventChildren(connection: SQLiteConnection) {
    connection.execSQL(
        "CREATE TABLE $LEASE_SUPERSESSION_BACKUP AS " +
            "SELECT * FROM mastery_learning_evidence_supersession",
    )
    connection.execSQL(
        "CREATE TABLE $LEASE_ATTRIBUTION_BACKUP AS " +
            "SELECT * FROM mastery_learning_event_attribution",
    )
    connection.execSQL(
        "CREATE TABLE $LEASE_APPLIED_EVENT_BACKUP AS " +
            "SELECT * FROM mastery_applied_event",
    )
}

private fun restoreVersion9LearningEventChildren(connection: SQLiteConnection) {
    connection.execSQL(CREATE_LEASE_SUPERSESSION_TABLE)
    connection.execSQL(
        "INSERT INTO mastery_learning_evidence_supersession " +
            "SELECT * FROM $LEASE_SUPERSESSION_BACKUP",
    )
    connection.execSQL("DROP TABLE $LEASE_SUPERSESSION_BACKUP")
    createLeaseSupersessionIndices(connection)

    connection.execSQL(CREATE_LEASE_ATTRIBUTION_TABLE)
    connection.execSQL(
        "INSERT INTO mastery_learning_event_attribution " +
            "SELECT * FROM $LEASE_ATTRIBUTION_BACKUP",
    )
    connection.execSQL("DROP TABLE $LEASE_ATTRIBUTION_BACKUP")
    createLeaseAttributionIndices(connection)

    connection.execSQL(CREATE_LEASE_APPLIED_EVENT_TABLE)
    connection.execSQL(
        "INSERT INTO mastery_applied_event SELECT * FROM $LEASE_APPLIED_EVENT_BACKUP",
    )
    connection.execSQL("DROP TABLE $LEASE_APPLIED_EVENT_BACKUP")
    createLeaseAppliedEventIndices(connection)
}

private fun migrateVersion9KnowledgeProjections(connection: SQLiteConnection) {
    val projectionCount =
        connection.calibrationLeaseScalarLong(
            "SELECT COUNT(*) FROM mastery_knowledge_projection",
        )
    connection.execSQL(CREATE_LEASE_KNOWLEDGE_PROJECTION_TABLE)
    connection.execSQL(
        """
        INSERT INTO $LEASE_KNOWLEDGE_PROJECTION_TABLE (
            learner_id,
            subject,
            knowledge_node_id,
            taxonomy_version,
            latest_evidence_knowledge_pack_version,
            stable_node_identity_fingerprint,
            positive_evidence_micros,
            negative_evidence_micros,
            mastery_score_micros,
            mastery_state,
            trend,
            observation_count,
            memory_stability_millis,
            recall_due_at_epoch_millis,
            last_positive_at_epoch_millis,
            last_negative_at_epoch_millis,
            last_evidence_at_epoch_millis,
            last_event_sequence,
            last_ordered_event_id,
            projection_policy_version,
            evidence_quality_micros,
            independent_problem_family_count,
            distinct_presentation_count,
            historical_log_odds_micros,
            calibration_snapshot_fingerprint,
            calibration_profile_id,
            calibration_version,
            recall_familiarizing_at_epoch_millis,
            recall_reinforcement_at_epoch_millis
        )
        SELECT projection.learner_id,
               projection.subject,
               projection.knowledge_node_id,
               projection.taxonomy_version,
               projection.latest_evidence_knowledge_pack_version,
               projection.stable_node_identity_fingerprint,
               projection.positive_evidence_micros,
               projection.negative_evidence_micros,
               projection.mastery_score_micros,
               projection.mastery_state,
               projection.trend,
               projection.observation_count,
               projection.memory_stability_millis,
               projection.recall_due_at_epoch_millis,
               projection.last_positive_at_epoch_millis,
               projection.last_negative_at_epoch_millis,
               projection.last_evidence_at_epoch_millis,
               projection.last_event_sequence,
               projection.last_ordered_event_id,
               projection.projection_policy_version,
               projection.evidence_quality_micros,
               projection.independent_problem_family_count,
               projection.distinct_presentation_count,
               projection.historical_log_odds_micros,
               projection.calibration_snapshot_fingerprint,
               projection.calibration_profile_id,
               CASE
                   WHEN projection.projection_policy_version =
                       '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                   THEN (
                       SELECT snapshot.calibration_version
                       FROM mastery_calibration_snapshot AS snapshot
                       WHERE snapshot.subject = projection.subject
                         AND snapshot.profile_id =
                             projection.calibration_profile_id
                         AND snapshot.snapshot_fingerprint =
                             projection.calibration_snapshot_fingerprint
                       LIMIT 1
                   )
                   ELSE NULL
               END,
               projection.recall_familiarizing_at_epoch_millis,
               projection.recall_reinforcement_at_epoch_millis
        FROM mastery_knowledge_projection AS projection
        """.trimIndent(),
    )
    check(
        connection.calibrationLeaseScalarLong(
            "SELECT COUNT(*) FROM $LEASE_KNOWLEDGE_PROJECTION_TABLE",
        ) == projectionCount,
    ) {
        "Learner-mastery v9 projections were not copied exactly"
    }
    connection.execSQL("DROP TABLE mastery_knowledge_projection")
    connection.execSQL(
        "ALTER TABLE $LEASE_KNOWLEDGE_PROJECTION_TABLE " +
            "RENAME TO mastery_knowledge_projection",
    )
    createLeaseKnowledgeProjectionIndices(connection)
}

private fun createLeaseEvidenceReviewCaseIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_case_candidate_id_candidate_canonical_fingerprint " +
            "ON mastery_evidence_review_case(candidate_id, candidate_canonical_fingerprint)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_case_source_fact_id_source_proof_fingerprint " +
            "ON mastery_evidence_review_case(source_fact_id, source_proof_fingerprint)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_case_learner_id_subject_created_at_epoch_millis " +
            "ON mastery_evidence_review_case(" +
            "learner_id, subject, created_at_epoch_millis)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_case_review_case_fingerprint " +
            "ON mastery_evidence_review_case(review_case_fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_case_review_case_id_calibration_snapshot_fingerprint " +
            "ON mastery_evidence_review_case(" +
            "review_case_id, calibration_snapshot_fingerprint)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_case_subject_calibration_version_calibration_profile_id_calibration_snapshot_fingerprint " +
            "ON mastery_evidence_review_case(" +
            "subject, calibration_version, calibration_profile_id, " +
            "calibration_snapshot_fingerprint)",
    )
}

private fun createLeaseEvidenceReviewResolutionIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_resolution_review_case_id " +
            "ON mastery_evidence_review_resolution(review_case_id)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_resolution_review_case_id_calibration_snapshot_fingerprint " +
            "ON mastery_evidence_review_resolution(" +
            "review_case_id, calibration_snapshot_fingerprint)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_resolution_learner_id_subject_decided_at_epoch_millis " +
            "ON mastery_evidence_review_resolution(" +
            "learner_id, subject, decided_at_epoch_millis)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_resolution_idempotency_key " +
            "ON mastery_evidence_review_resolution(idempotency_key)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_evidence_review_resolution_resolution_fingerprint " +
            "ON mastery_evidence_review_resolution(resolution_fingerprint)",
    )
}

private fun createLeaseLegacyReviewResolutionAuditIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_legacy_evidence_review_resolution_audit_review_case_id " +
            "ON $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE(review_case_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_legacy_evidence_review_resolution_audit_idempotency_key " +
            "ON $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE(idempotency_key)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_legacy_evidence_review_resolution_audit_resolution_fingerprint " +
            "ON $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE(resolution_fingerprint)",
    )
}

private fun createLeaseLearningEventIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_mastery_learning_event_candidate_id " +
            "ON mastery_learning_event(candidate_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_mastery_learning_event_source_fact_id " +
            "ON mastery_learning_event(source_fact_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_source_fact_id_source_proof_fingerprint " +
            "ON mastery_learning_event(source_fact_id, source_proof_fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_learner_id_event_sequence " +
            "ON mastery_learning_event(learner_id, event_sequence)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_learner_id_subject_occurred_at_epoch_millis " +
            "ON mastery_learning_event(learner_id, subject, occurred_at_epoch_millis)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_canonical_fingerprint " +
            "ON mastery_learning_event(canonical_fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_event_id_canonical_fingerprint " +
            "ON mastery_learning_event(event_id, canonical_fingerprint)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_subject_calibration_version_calibration_profile_id_calibration_snapshot_fingerprint " +
            "ON mastery_learning_event(" +
            "subject, calibration_version, calibration_profile_id, " +
            "calibration_snapshot_fingerprint)",
    )
}

private fun createLeaseSupersessionIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_original_source_fact_id " +
            "ON mastery_learning_evidence_supersession(original_source_fact_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_original_source_fact_canonical_fingerprint " +
            "ON mastery_learning_evidence_supersession(" +
            "original_source_fact_canonical_fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_original_event_id " +
            "ON mastery_learning_evidence_supersession(original_event_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_replacement_source_fact_id " +
            "ON mastery_learning_evidence_supersession(replacement_source_fact_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_replacement_event_id " +
            "ON mastery_learning_evidence_supersession(replacement_event_id)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_learner_id_subject_superseded_at_epoch_millis " +
            "ON mastery_learning_evidence_supersession(" +
            "learner_id, subject, superseded_at_epoch_millis)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_idempotency_key " +
            "ON mastery_learning_evidence_supersession(idempotency_key)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_evidence_supersession_canonical_fingerprint " +
            "ON mastery_learning_evidence_supersession(canonical_fingerprint)",
    )
}

private fun createLeaseAttributionIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_attribution_event_id_knowledge_node_id_taxonomy_version_knowledge_pack_version " +
            "ON mastery_learning_event_attribution(" +
            "event_id, knowledge_node_id, taxonomy_version, knowledge_pack_version)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_attribution_subject_knowledge_node_id_taxonomy_version_event_id " +
            "ON mastery_learning_event_attribution(" +
            "subject, knowledge_node_id, taxonomy_version, event_id)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_learning_event_attribution_knowledge_node_ref_fingerprint " +
            "ON mastery_learning_event_attribution(knowledge_node_ref_fingerprint)",
    )
}

private fun createLeaseAppliedEventIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_applied_event_event_id_event_canonical_fingerprint " +
            "ON mastery_applied_event(event_id, event_canonical_fingerprint)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_applied_event_learner_id_event_sequence " +
            "ON mastery_applied_event(learner_id, event_sequence)",
        "CREATE UNIQUE INDEX IF NOT EXISTS " +
            "index_mastery_applied_event_application_fingerprint " +
            "ON mastery_applied_event(application_fingerprint)",
    )
}

private fun createLeaseKnowledgeProjectionIndices(connection: SQLiteConnection) {
    connection.execAll(
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_knowledge_projection_learner_id_subject_mastery_state_last_evidence_at_epoch_millis " +
            "ON mastery_knowledge_projection(" +
            "learner_id, subject, mastery_state, last_evidence_at_epoch_millis)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_knowledge_projection_learner_id_subject_recall_due_at_epoch_millis " +
            "ON mastery_knowledge_projection(" +
            "learner_id, subject, recall_due_at_epoch_millis)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_knowledge_projection_learner_id_recall_familiarizing_at_epoch_millis " +
            "ON mastery_knowledge_projection(" +
            "learner_id, recall_familiarizing_at_epoch_millis)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_knowledge_projection_learner_id_recall_reinforcement_at_epoch_millis " +
            "ON mastery_knowledge_projection(" +
            "learner_id, recall_reinforcement_at_epoch_millis)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_knowledge_projection_learner_id_subject_stable_node_identity_fingerprint " +
            "ON mastery_knowledge_projection(" +
            "learner_id, subject, stable_node_identity_fingerprint)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_knowledge_projection_stable_node_identity_fingerprint " +
            "ON mastery_knowledge_projection(stable_node_identity_fingerprint)",
        "CREATE INDEX IF NOT EXISTS " +
            "index_mastery_knowledge_projection_subject_calibration_version_calibration_profile_id_calibration_snapshot_fingerprint " +
            "ON mastery_knowledge_projection(" +
            "subject, calibration_version, calibration_profile_id, " +
            "calibration_snapshot_fingerprint)",
    )
}

private fun SQLiteConnection.execAll(vararg statements: String) {
    statements.forEach { statement -> execSQL(statement) }
}

private fun SQLiteConnection.calibrationLeaseScalarLong(sql: String): Long =
    prepare(sql).use { statement ->
        check(statement.step()) { "Expected one learner-mastery calibration scalar row" }
        statement.getLong(0)
    }

private const val LEASE_CALIBRATION_TABLE = "mastery_calibration_snapshot_v10_lease"
private const val LEASE_EVIDENCE_REVIEW_CASE_TABLE =
    "mastery_evidence_review_case_v10_lease"
private const val LEASE_LEARNING_EVENT_TABLE = "mastery_learning_event_v10_lease"
private const val LEASE_KNOWLEDGE_PROJECTION_TABLE =
    "mastery_knowledge_projection_v10_lease"
private const val LEASE_SUPERSESSION_BACKUP =
    "mastery_learning_evidence_supersession_v9_backup"
private const val LEASE_ATTRIBUTION_BACKUP =
    "mastery_learning_event_attribution_v9_backup"
private const val LEASE_APPLIED_EVENT_BACKUP = "mastery_applied_event_v9_backup"

private val CREATE_LEASE_EVIDENCE_REVIEW_CASE_TABLE =
    """
    CREATE TABLE IF NOT EXISTS $LEASE_EVIDENCE_REVIEW_CASE_TABLE (
        review_case_id TEXT NOT NULL,
        candidate_id TEXT NOT NULL,
        candidate_canonical_fingerprint TEXT NOT NULL,
        source_fact_id TEXT NOT NULL,
        source_proof_fingerprint TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        reason TEXT NOT NULL,
        admission_policy_version TEXT NOT NULL,
        calibration_binding_status TEXT NOT NULL,
        calibration_version TEXT,
        calibration_profile_id TEXT,
        calibration_snapshot_fingerprint TEXT,
        review_case_fingerprint TEXT NOT NULL,
        created_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(review_case_id),
        FOREIGN KEY(candidate_id, candidate_canonical_fingerprint)
            REFERENCES mastery_observation_candidate(
                candidate_id,
                canonical_fingerprint
            )
            ON UPDATE NO ACTION
            ON DELETE RESTRICT,
        FOREIGN KEY(source_fact_id, source_proof_fingerprint)
            REFERENCES mastery_source_proof(
                source_fact_id,
                proof_fingerprint
            )
            ON UPDATE NO ACTION
            ON DELETE RESTRICT,
        FOREIGN KEY(
            subject,
            calibration_version,
            calibration_profile_id,
            calibration_snapshot_fingerprint
        )
            REFERENCES mastery_calibration_snapshot(
                subject,
                calibration_version,
                profile_id,
                snapshot_fingerprint
            )
            ON UPDATE NO ACTION
            ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_LEASE_EVIDENCE_REVIEW_RESOLUTION_TABLE =
    """
    CREATE TABLE IF NOT EXISTS mastery_evidence_review_resolution (
        resolution_id TEXT NOT NULL,
        review_case_id TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        decision TEXT NOT NULL,
        authority TEXT NOT NULL,
        reviewer_version TEXT NOT NULL,
        review_evidence_fingerprint TEXT NOT NULL,
        calibration_snapshot_fingerprint TEXT NOT NULL,
        idempotency_key TEXT NOT NULL,
        resolution_fingerprint TEXT NOT NULL,
        decided_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(resolution_id),
        FOREIGN KEY(review_case_id, calibration_snapshot_fingerprint)
            REFERENCES mastery_evidence_review_case(
                review_case_id,
                calibration_snapshot_fingerprint
            )
            ON UPDATE NO ACTION
            ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_LEASE_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE =
    """
    CREATE TABLE IF NOT EXISTS $LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE (
        resolution_id TEXT NOT NULL,
        review_case_id TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        decision TEXT NOT NULL,
        authority TEXT NOT NULL,
        reviewer_version TEXT NOT NULL,
        review_evidence_fingerprint TEXT NOT NULL,
        unverified_calibration_snapshot_fingerprint TEXT NOT NULL,
        idempotency_key TEXT NOT NULL,
        resolution_fingerprint TEXT NOT NULL,
        decided_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(resolution_id)
    )
    """.trimIndent()

private val CREATE_LEASE_CALIBRATION_TABLE =
    """
    CREATE TABLE IF NOT EXISTS $LEASE_CALIBRATION_TABLE (
        subject TEXT NOT NULL,
        profile_id TEXT NOT NULL,
        calibration_version TEXT NOT NULL,
        projection_policy_version TEXT NOT NULL,
        prior_log_odds_micros INTEGER NOT NULL,
        positive_log_likelihood_micros INTEGER NOT NULL,
        negative_log_likelihood_micros INTEGER NOT NULL,
        steady_threshold_micros INTEGER NOT NULL,
        reinforcement_threshold_micros INTEGER NOT NULL,
        steady_minimum_observation_count INTEGER NOT NULL,
        material_score_delta_micros INTEGER NOT NULL,
        maximum_absolute_log_odds_micros INTEGER NOT NULL,
        probability_transform_version INTEGER NOT NULL,
        legacy_probability_bridge_version INTEGER NOT NULL,
        initial_stability_millis INTEGER NOT NULL,
        minimum_stability_millis INTEGER NOT NULL,
        maximum_stability_millis INTEGER NOT NULL,
        positive_stability_gain_millis INTEGER NOT NULL,
        negative_stability_scale_micros INTEGER NOT NULL,
        recall_half_life_scale_micros INTEGER NOT NULL,
        presentation_mass_cap_micros INTEGER NOT NULL,
        problem_family_mass_cap_micros INTEGER NOT NULL,
        second_family_observation_scale_micros INTEGER NOT NULL,
        repeated_family_observation_scale_micros INTEGER NOT NULL,
        stable_conflict_floor_millis INTEGER NOT NULL,
        local_verified_mass_micros INTEGER NOT NULL,
        deterministic_rubric_mass_micros INTEGER NOT NULL,
        model_reviewed_mass_micros INTEGER NOT NULL,
        one_hint_scale_micros INTEGER NOT NULL,
        multiple_hints_scale_micros INTEGER NOT NULL,
        unknown_assistance_scale_micros INTEGER NOT NULL,
        one_retry_scale_micros INTEGER NOT NULL,
        multiple_retries_scale_micros INTEGER NOT NULL,
        model_attribution_cap_micros INTEGER NOT NULL,
        open_response_cap_micros INTEGER NOT NULL,
        snapshot_fingerprint TEXT NOT NULL,
        PRIMARY KEY(subject, calibration_version, snapshot_fingerprint)
    )
    """.trimIndent()

private val CREATE_LEASE_LEARNING_EVENT_TABLE =
    """
    CREATE TABLE IF NOT EXISTS $LEASE_LEARNING_EVENT_TABLE (
        event_id TEXT NOT NULL,
        candidate_id TEXT NOT NULL,
        source_fact_id TEXT NOT NULL,
        source_proof_fingerprint TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        direction TEXT NOT NULL,
        event_sequence INTEGER NOT NULL,
        occurred_at_epoch_millis INTEGER NOT NULL,
        admitted_at_epoch_millis INTEGER NOT NULL,
        projection_policy_version TEXT NOT NULL,
        admission_policy_version TEXT NOT NULL
            DEFAULT 'learner-mastery-admission-legacy',
        calibration_version TEXT NOT NULL
            DEFAULT 'learner-mastery-calibration-legacy',
        canonical_fingerprint TEXT NOT NULL,
        problem_family_fingerprint TEXT,
        presentation_fingerprint TEXT NOT NULL DEFAULT '',
        evidence_quality_micros INTEGER NOT NULL DEFAULT 0,
        independently_answered INTEGER NOT NULL DEFAULT 0,
        calibration_snapshot_fingerprint TEXT,
        calibration_profile_id TEXT,
        review_resolution_fingerprint TEXT,
        PRIMARY KEY(event_id),
        FOREIGN KEY(candidate_id)
            REFERENCES mastery_observation_candidate(candidate_id)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT,
        FOREIGN KEY(source_fact_id)
            REFERENCES mastery_source_fact(source_fact_id)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT,
        FOREIGN KEY(source_fact_id, source_proof_fingerprint)
            REFERENCES mastery_source_proof(source_fact_id, proof_fingerprint)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT,
        FOREIGN KEY(
            subject,
            calibration_version,
            calibration_profile_id,
            calibration_snapshot_fingerprint
        )
            REFERENCES mastery_calibration_snapshot(
                subject,
                calibration_version,
                profile_id,
                snapshot_fingerprint
            )
            ON UPDATE NO ACTION
            ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_LEASE_SUPERSESSION_TABLE =
    """
    CREATE TABLE IF NOT EXISTS mastery_learning_evidence_supersession (
        supersession_id TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        original_source_fact_id TEXT NOT NULL,
        original_source_fact_canonical_fingerprint TEXT NOT NULL,
        original_event_id TEXT NOT NULL,
        original_event_canonical_fingerprint TEXT NOT NULL,
        replacement_source_fact_id TEXT NOT NULL,
        replacement_source_fact_canonical_fingerprint TEXT NOT NULL,
        replacement_candidate_id TEXT NOT NULL,
        replacement_candidate_canonical_fingerprint TEXT NOT NULL,
        replacement_event_id TEXT NOT NULL,
        authority TEXT NOT NULL,
        authority_version TEXT NOT NULL,
        correction_evidence_fingerprint TEXT NOT NULL,
        idempotency_key TEXT NOT NULL,
        canonical_fingerprint TEXT NOT NULL,
        superseded_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(supersession_id),
        FOREIGN KEY(original_source_fact_id)
            REFERENCES mastery_source_fact(source_fact_id)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT,
        FOREIGN KEY(original_event_id)
            REFERENCES mastery_learning_event(event_id)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT,
        FOREIGN KEY(replacement_source_fact_id)
            REFERENCES mastery_source_fact(source_fact_id)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_LEASE_ATTRIBUTION_TABLE =
    """
    CREATE TABLE IF NOT EXISTS mastery_learning_event_attribution (
        event_id TEXT NOT NULL,
        ordinal INTEGER NOT NULL,
        subject TEXT NOT NULL,
        knowledge_node_id TEXT NOT NULL,
        taxonomy_version TEXT NOT NULL,
        knowledge_pack_version TEXT NOT NULL,
        knowledge_node_ref_fingerprint TEXT NOT NULL,
        evidence_mass_micros INTEGER NOT NULL,
        PRIMARY KEY(event_id, ordinal),
        FOREIGN KEY(event_id)
            REFERENCES mastery_learning_event(event_id)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_LEASE_APPLIED_EVENT_TABLE =
    """
    CREATE TABLE IF NOT EXISTS mastery_applied_event (
        event_id TEXT NOT NULL,
        event_canonical_fingerprint TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        event_sequence INTEGER NOT NULL,
        projection_policy_version TEXT NOT NULL,
        application_fingerprint TEXT NOT NULL,
        applied_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(event_id),
        FOREIGN KEY(event_id, event_canonical_fingerprint)
            REFERENCES mastery_learning_event(event_id, canonical_fingerprint)
            ON UPDATE NO ACTION
            ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_LEASE_KNOWLEDGE_PROJECTION_TABLE =
    """
    CREATE TABLE IF NOT EXISTS $LEASE_KNOWLEDGE_PROJECTION_TABLE (
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        knowledge_node_id TEXT NOT NULL,
        taxonomy_version TEXT NOT NULL,
        latest_evidence_knowledge_pack_version TEXT NOT NULL,
        stable_node_identity_fingerprint TEXT NOT NULL,
        positive_evidence_micros INTEGER NOT NULL,
        negative_evidence_micros INTEGER NOT NULL,
        mastery_score_micros INTEGER NOT NULL,
        mastery_state TEXT NOT NULL,
        trend TEXT NOT NULL,
        observation_count INTEGER NOT NULL,
        memory_stability_millis INTEGER NOT NULL,
        recall_due_at_epoch_millis INTEGER NOT NULL,
        last_positive_at_epoch_millis INTEGER,
        last_negative_at_epoch_millis INTEGER,
        last_evidence_at_epoch_millis INTEGER NOT NULL,
        last_event_sequence INTEGER NOT NULL,
        last_ordered_event_id TEXT NOT NULL,
        projection_policy_version TEXT NOT NULL,
        evidence_quality_micros INTEGER NOT NULL DEFAULT 0,
        independent_problem_family_count INTEGER NOT NULL DEFAULT 0,
        distinct_presentation_count INTEGER NOT NULL DEFAULT 0,
        historical_log_odds_micros INTEGER,
        calibration_snapshot_fingerprint TEXT,
        calibration_profile_id TEXT,
        calibration_version TEXT,
        recall_familiarizing_at_epoch_millis INTEGER,
        recall_reinforcement_at_epoch_millis INTEGER,
        PRIMARY KEY(
            learner_id,
            subject,
            knowledge_node_id,
            taxonomy_version
        ),
        FOREIGN KEY(
            subject,
            calibration_version,
            calibration_profile_id,
            calibration_snapshot_fingerprint
        )
            REFERENCES mastery_calibration_snapshot(
                subject,
                calibration_version,
                profile_id,
                snapshot_fingerprint
            )
            ON UPDATE NO ACTION
            ON DELETE RESTRICT
    )
    """.trimIndent()
