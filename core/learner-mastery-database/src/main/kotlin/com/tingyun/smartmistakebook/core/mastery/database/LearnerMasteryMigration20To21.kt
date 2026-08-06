package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Adds the content-free open-response evaluation proof chain.
 *
 * DB20 receipts intentionally remain unattested and therefore remain inert. The migration never
 * guesses a knowledge node, qualitative role, direction, weight, or learning event for them.
 */
internal val LEARNER_MASTERY_MIGRATION_20_21: Migration =
    object : Migration(20, 21) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val legacyReceiptCount =
                connection.countOpenResponseRows(
                    LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                )
            connection.execSQL(
                "ALTER TABLE $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE " +
                    "ADD COLUMN proof_chain_version INTEGER NOT NULL DEFAULT 0",
            )
            connection.execSQL(CREATE_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE)
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_model_evaluation_attestation_receipt_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE(receipt_fingerprint)",
            )
            connection.execSQL(
                "CREATE INDEX " +
                    "index_mastery_open_response_model_evaluation_attestation_review_case_id " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE(review_case_id)",
            )
            connection.execSQL(
                "CREATE INDEX " +
                    "index_mastery_open_response_model_evaluation_attestation_candidate_id " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE(candidate_id)",
            )
            connection.execSQL(
                "CREATE INDEX " +
                    "index_mastery_open_response_model_evaluation_attestation_learner_id_subject_received_at_epoch_millis " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE(" +
                    "learner_id, subject, received_at_epoch_millis)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_model_evaluation_attestation_model_output_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE(model_output_fingerprint)",
            )

            connection.execSQL(CREATE_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE)
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_evaluation_knowledge_scope_attestation_fingerprint_ref_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE(" +
                    "attestation_fingerprint, ref_fingerprint)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_evaluation_knowledge_scope_attestation_fingerprint_knowledge_node_ref_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE(" +
                    "attestation_fingerprint, knowledge_node_ref_fingerprint)",
            )
            connection.execSQL(
                "CREATE INDEX " +
                    "index_mastery_open_response_evaluation_knowledge_scope_knowledge_node_ref_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE(" +
                    "knowledge_node_ref_fingerprint)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_evaluation_knowledge_scope_scope_entry_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE(scope_entry_fingerprint)",
            )

            connection.execSQL(CREATE_OPEN_RESPONSE_DEDICATED_DECISION_TABLE)
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_dedicated_decision_attestation_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE(attestation_fingerprint)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_dedicated_decision_receipt_fingerprint " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE(receipt_fingerprint)",
            )
            connection.execSQL(
                "CREATE INDEX index_mastery_open_response_dedicated_decision_review_case_id " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE(review_case_id)",
            )
            connection.execSQL(
                "CREATE INDEX index_mastery_open_response_dedicated_decision_candidate_id " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE(candidate_id)",
            )
            connection.execSQL(
                "CREATE UNIQUE INDEX " +
                    "index_mastery_open_response_dedicated_decision_accepted_event_id " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE(accepted_event_id)",
            )
            connection.execSQL(
                "CREATE INDEX " +
                    "index_mastery_open_response_dedicated_decision_learner_id_subject_decided_at_epoch_millis " +
                    "ON $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE(" +
                    "learner_id, subject, decided_at_epoch_millis)",
            )

            check(
                connection.countOpenResponseRows(
                    LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                ) == legacyReceiptCount,
            ) { "DB20→DB21 changed an immutable open-response receipt" }
            check(
                connection.countOpenResponseRows(
                    LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
                ) == 0L &&
                    connection.countOpenResponseRows(
                        LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
                    ) == 0L &&
                    connection.countOpenResponseRows(
                        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                    ) == 0L,
            ) { "DB20→DB21 fabricated open-response attribution" }
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

private fun SQLiteConnection.countOpenResponseRows(tableName: String): Long =
    prepare("SELECT COUNT(*) FROM `$tableName`").use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private val CREATE_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE =
    """
    CREATE TABLE $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE (
        attestation_fingerprint TEXT NOT NULL,
        receipt_fingerprint TEXT NOT NULL,
        candidate_id TEXT NOT NULL,
        candidate_canonical_fingerprint TEXT NOT NULL,
        source_fact_id TEXT NOT NULL,
        review_case_id TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        scope_fingerprint TEXT NOT NULL,
        conversation_id TEXT NOT NULL,
        conversation_generation INTEGER NOT NULL,
        conversation_state_version INTEGER NOT NULL,
        question_document_id TEXT NOT NULL,
        question_revision_number INTEGER NOT NULL,
        question_fingerprint TEXT NOT NULL,
        evidence_request_id TEXT NOT NULL,
        turn_reference_id TEXT NOT NULL,
        turn_ordinal INTEGER NOT NULL,
        turn_generation INTEGER NOT NULL,
        mode_version INTEGER NOT NULL,
        request_version INTEGER NOT NULL,
        model_task_request_id TEXT NOT NULL,
        model_response_schema_version INTEGER NOT NULL,
        evaluator_request_version INTEGER NOT NULL,
        candidate_idempotency_key TEXT NOT NULL,
        evidence_fingerprint TEXT NOT NULL,
        model_output_fingerprint TEXT NOT NULL,
        model_version TEXT NOT NULL,
        outcome TEXT NOT NULL,
        occurred_at_epoch_millis INTEGER NOT NULL,
        received_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(attestation_fingerprint),
        FOREIGN KEY(receipt_fingerprint)
            REFERENCES $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE(receipt_fingerprint)
            ON UPDATE NO ACTION ON DELETE RESTRICT,
        FOREIGN KEY(review_case_id)
            REFERENCES mastery_evidence_review_case(review_case_id)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE =
    """
    CREATE TABLE $LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE (
        attestation_fingerprint TEXT NOT NULL,
        ordinal INTEGER NOT NULL,
        ref_fingerprint TEXT NOT NULL,
        subject TEXT NOT NULL,
        knowledge_node_id TEXT NOT NULL,
        taxonomy_version TEXT NOT NULL,
        knowledge_pack_version TEXT NOT NULL,
        knowledge_node_ref_fingerprint TEXT NOT NULL,
        manifest_fingerprint TEXT NOT NULL,
        activation_generation INTEGER NOT NULL,
        evaluation_role TEXT,
        scope_entry_fingerprint TEXT NOT NULL,
        PRIMARY KEY(attestation_fingerprint, ordinal),
        FOREIGN KEY(attestation_fingerprint)
            REFERENCES $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE(attestation_fingerprint)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent()

private val CREATE_OPEN_RESPONSE_DEDICATED_DECISION_TABLE =
    """
    CREATE TABLE $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE (
        decision_fingerprint TEXT NOT NULL,
        attestation_fingerprint TEXT NOT NULL,
        receipt_fingerprint TEXT NOT NULL,
        review_case_id TEXT NOT NULL,
        candidate_id TEXT NOT NULL,
        source_fact_id TEXT NOT NULL,
        learner_id TEXT NOT NULL,
        subject TEXT NOT NULL,
        disposition TEXT NOT NULL,
        direction TEXT,
        local_reason TEXT,
        selected_scope_fingerprint TEXT,
        selected_knowledge_count INTEGER NOT NULL,
        local_policy_version TEXT NOT NULL,
        calibration_snapshot_fingerprint TEXT NOT NULL,
        accepted_event_id TEXT,
        independently_completed INTEGER NOT NULL,
        decided_at_epoch_millis INTEGER NOT NULL,
        PRIMARY KEY(decision_fingerprint),
        FOREIGN KEY(attestation_fingerprint)
            REFERENCES $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE(attestation_fingerprint)
            ON UPDATE NO ACTION ON DELETE RESTRICT,
        FOREIGN KEY(receipt_fingerprint)
            REFERENCES $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE(receipt_fingerprint)
            ON UPDATE NO ACTION ON DELETE RESTRICT
    )
    """.trimIndent()
