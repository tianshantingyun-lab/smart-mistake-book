package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

internal val LEARNER_MASTERY_MIGRATION_11_12 =
    object : Migration(11, 12) {
        override suspend fun migrate(connection: SQLiteConnection) {
            openResponseWeakCandidateReceiptSchemaStatements().forEach(connection::execSQL)
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

internal fun openResponseWeakCandidateReceiptSchemaStatements(): List<String> =
    listOf(
        """
        CREATE TABLE IF NOT EXISTS
            `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE` (
                `receipt_fingerprint` TEXT NOT NULL,
                `canonical_fingerprint` TEXT NOT NULL,
                `logical_attempt_fingerprint` TEXT NOT NULL,
                `lineage_parent_fingerprint` TEXT NOT NULL,
                `candidate_id` TEXT NOT NULL,
                `candidate_canonical_fingerprint` TEXT NOT NULL,
                `source_fact_id` TEXT NOT NULL,
                `review_case_id` TEXT NOT NULL,
                `review_case_fingerprint` TEXT NOT NULL,
                `learner_id` TEXT NOT NULL,
                `subject` TEXT NOT NULL,
                `scope_fingerprint` TEXT NOT NULL,
                `conversation_id` TEXT NOT NULL,
                `conversation_generation` INTEGER NOT NULL,
                `conversation_state_version` INTEGER NOT NULL,
                `question_document_id` TEXT NOT NULL,
                `question_revision_number` INTEGER NOT NULL,
                `question_fingerprint` TEXT NOT NULL,
                `answer_fingerprint` TEXT NOT NULL,
                `evidence_request_id` TEXT NOT NULL,
                `presentation_fingerprint` TEXT NOT NULL,
                `problem_fingerprint` TEXT NOT NULL,
                `problem_family_fingerprint` TEXT NOT NULL,
                `turn_reference_id` TEXT NOT NULL,
                `turn_ordinal` INTEGER NOT NULL,
                `turn_generation` INTEGER NOT NULL,
                `mode_version` INTEGER NOT NULL,
                `request_version` INTEGER NOT NULL,
                `attempt_ordinal` INTEGER NOT NULL,
                `hint_count` INTEGER NOT NULL,
                `answer_was_revealed` INTEGER NOT NULL,
                `model_task_request_id` TEXT NOT NULL,
                `model_response_schema_version` INTEGER NOT NULL,
                `evaluator_request_version` INTEGER NOT NULL,
                `candidate_idempotency_key` TEXT NOT NULL,
                `revision_of_candidate_idempotency_key` TEXT,
                `evidence_fingerprint` TEXT NOT NULL,
                `model_version` TEXT NOT NULL,
                `outcome` TEXT NOT NULL,
                `occurred_at_epoch_millis` INTEGER NOT NULL,
                `received_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`receipt_fingerprint`),
                FOREIGN KEY(
                    `candidate_id`, `candidate_canonical_fingerprint`
                ) REFERENCES `mastery_observation_candidate`(
                    `candidate_id`, `canonical_fingerprint`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT,
                FOREIGN KEY(
                    `review_case_id`
                ) REFERENCES `mastery_evidence_review_case`(
                    `review_case_id`
                ) ON UPDATE NO ACTION ON DELETE RESTRICT
            )
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_candidate_id_candidate_canonical_fingerprint`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`candidate_id`, `candidate_canonical_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_review_case_id`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`review_case_id`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_candidate_idempotency_key`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`candidate_idempotency_key`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_logical_attempt_fingerprint`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`logical_attempt_fingerprint`)
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_lineage_parent_fingerprint`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`lineage_parent_fingerprint`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_source_fact_id_received_at_epoch_millis`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`source_fact_id`, `received_at_epoch_millis`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_learner_id_subject_received_at_epoch_millis`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`learner_id`, `subject`, `received_at_epoch_millis`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_conversation_id_conversation_generation`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`conversation_id`, `conversation_generation`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS
            `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_question_document_id_question_revision_number`
        ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
            (`question_document_id`, `question_revision_number`)
        """.trimIndent(),
    )
