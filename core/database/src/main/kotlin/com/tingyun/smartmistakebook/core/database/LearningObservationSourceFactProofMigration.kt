package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds projection-admission capabilities without manufacturing authority for version-36 rows.
 *
 * Version 36 persisted source-fact claims and tutor responses, but it did not persist an immutable
 * binding from the request terminal payload to the exact response content used to derive
 * response_fingerprint, response_summary and fact_kind. A unique response-shaped row is therefore
 * only a candidate, not proof. Every pre-v37 observation stays in the immutable ledger as an inert
 * checkpoint and receives a deterministic review receipt; no proof or admission is backfilled.
 */
internal val LEARNING_OBSERVATION_SOURCE_FACT_PROOF_MIGRATION_36_37 =
    object : Migration(36, 37) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.createTutorEvidenceLookupIndexes()
            connection.createSourceFactProofTable()
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_attributed_learning_observation_event_event_id_canonical_fingerprint`
                ON `attributed_learning_observation_event`
                    (`event_id`, `canonical_fingerprint`)
                """.trimIndent(),
            )
            connection.createEventAdmissionTable()
            connection.quarantineUnverifiableLegacyObservations()
            connection.clearUnadmittedLegacyProjectionState()
        }
    }

private fun SQLiteConnection.createTutorEvidenceLookupIndexes() {
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_tutor_turn_response_evidence_request_id_session_id_cycle_ordinal_turn_ordinal`
        ON `tutor_turn_response`
            (`evidence_request_id`, `session_id`, `cycle_ordinal`, `turn_ordinal`)
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_tutor_evidence_cancellation_learner_id_evidence_request_id_session_id_question_document_id_revision_number`
        ON `tutor_evidence_cancellation`
            (`learner_id`, `evidence_request_id`, `session_id`, `question_document_id`,
             `revision_number`)
        """.trimIndent(),
    )
}

private fun SQLiteConnection.createSourceFactProofTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS `learning_observation_source_fact_proof` (
            `source_fact_id` TEXT NOT NULL,
            `proof_kind` TEXT NOT NULL,
            `target_kind` TEXT NOT NULL,
            `learner_id` TEXT NOT NULL,
            `subject` TEXT NOT NULL,
            `anchor_id` TEXT NOT NULL,
            `source_reference_id` TEXT NOT NULL,
            `source_fingerprint` TEXT NOT NULL,
            `conversation_id` TEXT,
            `conversation_generation` INTEGER,
            `turn_receipt_id` TEXT,
            `evidence_request_id` TEXT,
            `source_locator_kind` TEXT NOT NULL,
            `source_locator_id` TEXT NOT NULL,
            `target_database` TEXT NOT NULL,
            `target_id` TEXT NOT NULL,
            `target_version` TEXT NOT NULL,
            `target_fingerprint` TEXT NOT NULL,
            `target_created_at_epoch_millis` INTEGER NOT NULL,
            `attested_at_epoch_millis` INTEGER NOT NULL,
            `proof_fingerprint` TEXT NOT NULL,
            PRIMARY KEY(`source_fact_id`),
            FOREIGN KEY(`source_fact_id`)
                REFERENCES `learning_observation_source_fact`(`source_fact_id`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_learning_observation_source_fact_proof_learner_id_subject_anchor_id`
        ON `learning_observation_source_fact_proof`
            (`learner_id`, `subject`, `anchor_id`)
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_learning_observation_source_fact_proof_target_database_target_id_target_version`
        ON `learning_observation_source_fact_proof`
            (`target_database`, `target_id`, `target_version`)
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE INDEX IF NOT EXISTS
            `index_learning_observation_source_fact_proof_proof_kind_target_kind`
        ON `learning_observation_source_fact_proof` (`proof_kind`, `target_kind`)
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_learning_observation_source_fact_proof_proof_fingerprint`
        ON `learning_observation_source_fact_proof` (`proof_fingerprint`)
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_learning_observation_source_fact_proof_source_fact_id_proof_fingerprint`
        ON `learning_observation_source_fact_proof`
            (`source_fact_id`, `proof_fingerprint`)
        """.trimIndent(),
    )
}

private fun SQLiteConnection.createEventAdmissionTable() {
    execSQL(
        """
        CREATE TABLE IF NOT EXISTS `learning_observation_event_admission` (
            `event_id` TEXT NOT NULL,
            `source_fact_id` TEXT NOT NULL,
            `raw_event_canonical_fingerprint` TEXT NOT NULL,
            `source_fact_proof_fingerprint` TEXT NOT NULL,
            `policy_version` TEXT NOT NULL,
            `admission_fingerprint` TEXT NOT NULL,
            `admitted_at_epoch_millis` INTEGER NOT NULL,
            PRIMARY KEY(`event_id`),
            FOREIGN KEY(`event_id`, `raw_event_canonical_fingerprint`)
                REFERENCES `attributed_learning_observation_event`
                    (`event_id`, `canonical_fingerprint`)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(`source_fact_id`, `source_fact_proof_fingerprint`)
                REFERENCES `learning_observation_source_fact_proof`
                    (`source_fact_id`, `proof_fingerprint`)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_learning_observation_event_admission_event_id_raw_event_canonical_fingerprint`
        ON `learning_observation_event_admission`
            (`event_id`, `raw_event_canonical_fingerprint`)
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_learning_observation_event_admission_source_fact_id_source_fact_proof_fingerprint`
        ON `learning_observation_event_admission`
            (`source_fact_id`, `source_fact_proof_fingerprint`)
        """.trimIndent(),
    )
    execSQL(
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_learning_observation_event_admission_admission_fingerprint`
        ON `learning_observation_event_admission` (`admission_fingerprint`)
        """.trimIndent(),
    )
}

private fun SQLiteConnection.quarantineUnverifiableLegacyObservations() {
    execSQL(
        """
        INSERT OR IGNORE INTO `learning_evidence_review_case` (
            `review_case_id`,
            `candidate_id`,
            `learner_id`,
            `proposed_event_id`,
            `reason`,
            `detail`,
            `status`,
            `created_at_epoch_millis`,
            `resolved_at_epoch_millis`
        )
        SELECT
            '$LEGACY_QUARANTINE_RECEIPT_PREFIX' || event.event_id,
            event.candidate_id,
            event.learner_id,
            event.event_id,
            'SOURCE_FACT_POLICY_REJECTED',
            '$LEGACY_QUARANTINE_DETAIL',
            'OPEN',
            event.confirmed_at_epoch_millis,
            NULL
        FROM `attributed_learning_observation_event` AS event
        LEFT JOIN `learning_observation_event_admission` AS admission
          ON admission.event_id = event.event_id
        WHERE admission.event_id IS NULL
        """.trimIndent(),
    )
}

/**
 * v36 projected raw observations before admission receipts existed. Clearing only derived state
 * forces the immutable ledger to replay through v37 tombstone semantics. Ledger, source facts,
 * candidates, events, identities, sequences and outbox rows remain untouched for auditability.
 */
private fun SQLiteConnection.clearUnadmittedLegacyProjectionState() {
    val affectedLearners =
        """
        SELECT DISTINCT event.learner_id
        FROM attributed_learning_observation_event AS event
        LEFT JOIN learning_observation_event_admission AS admission
          ON admission.event_id = event.event_id
        WHERE admission.event_id IS NULL
        """.trimIndent()
    listOf(
        "independent_correct_observation",
        "applied_learning_observation_record",
        "applied_tutor_answer_exposure_record",
        "learner_problem_memory_state",
        "learner_knowledge_mastery_state",
        "applied_attempt_record",
        "applied_correction_record",
        "applied_answer_reveal_record",
        "presentation_projection_state",
    ).forEach { tableName ->
        execSQL(
            """
            DELETE FROM `$tableName`
            WHERE `learner_id` IN ($affectedLearners)
            """.trimIndent(),
        )
    }
    execSQL(
        """
        DELETE FROM `projection_consumption`
        WHERE `learner_id` IN ($affectedLearners)
        """.trimIndent(),
    )
    execSQL(
        """
        DELETE FROM `learner_projection_snapshot`
        WHERE `learner_id` IN ($affectedLearners)
        """.trimIndent(),
    )
}

private const val LEGACY_QUARANTINE_RECEIPT_PREFIX =
    "migration-v36-source-proof-quarantine:"
private const val LEGACY_QUARANTINE_DETAIL =
    "Version 36 did not persist an immutable terminal-response content binding; observation kept inert."
