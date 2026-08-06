package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Adds the append-only owner ledger for exact Tutor choice and visual-target answers. */
internal val STUDENT_MISTAKE_MIGRATION_19_20: Migration =
    object : Migration(19, 20) {
        override suspend fun migrate(connection: SQLiteConnection) {
            STUDENT_TUTOR_ANSWER_CERTIFICATE_V20_TABLE_SQL.forEach(connection::execSQL)
            STUDENT_TUTOR_ANSWER_CERTIFICATE_V20_INDEX_SQL.forEach(connection::execSQL)
            createTutorInteractionAnswerCertificateImmutabilityTriggers(connection)
        }
    }

internal fun createTutorInteractionAnswerCertificateImmutabilityTriggers(
    connection: SQLiteConnection,
) {
    createStudentReviewReceiptImmutabilityTriggers(
        connection,
        STUDENT_TUTOR_ANSWER_CERTIFICATE_IMMUTABLE_TABLES,
    )
}

internal val STUDENT_TUTOR_ANSWER_CERTIFICATE_IMMUTABLE_TABLES =
    listOf(
        "student_tutor_interaction_answer_certificate",
        "student_tutor_interaction_answer_certificate_status_event",
        "student_tutor_interaction_answer_certificate_lease_receipt",
        "student_tutor_interaction_answer_evaluation_receipt",
    )

internal val STUDENT_TUTOR_ANSWER_CERTIFICATE_V20_TABLE_SQL =
    listOf(
        """
        CREATE TABLE student_tutor_interaction_answer_certificate (
            certificate_id TEXT NOT NULL,
            admission_idempotency_key TEXT NOT NULL,
            learner_id TEXT NOT NULL,
            subject TEXT NOT NULL,
            problem_id TEXT NOT NULL,
            practice_unit_id TEXT NOT NULL,
            problem_revision_id TEXT NOT NULL,
            problem_revision_number INTEGER NOT NULL,
            question_document_fingerprint TEXT NOT NULL,
            session_id TEXT NOT NULL,
            model_task_request_id TEXT NOT NULL,
            cycle_ordinal INTEGER NOT NULL,
            turn_ordinal INTEGER NOT NULL,
            mode_version INTEGER NOT NULL,
            learning_write_permission_version INTEGER NOT NULL,
            interaction_kind TEXT NOT NULL,
            presentation_canonical_fingerprint TEXT NOT NULL,
            allowed_answer_ids_wire TEXT NOT NULL,
            correct_answer_ids_wire TEXT NOT NULL,
            provenance_kind TEXT NOT NULL,
            provenance_receipt_id TEXT NOT NULL,
            provenance_receipt_canonical_fingerprint TEXT NOT NULL,
            provenance_receipt_schema_version TEXT NOT NULL,
            certificate_schema_version TEXT NOT NULL,
            admission_policy_version TEXT NOT NULL,
            certificate_family_canonical_fingerprint TEXT NOT NULL,
            presentation_answer_free_canonical_fingerprint TEXT NOT NULL,
            owner_canonical_fingerprint TEXT NOT NULL,
            admission_receipt_canonical_fingerprint TEXT NOT NULL,
            issued_at_epoch_millis INTEGER NOT NULL,
            not_after_epoch_millis INTEGER NOT NULL,
            PRIMARY KEY(certificate_id),
            FOREIGN KEY(problem_id) REFERENCES student_problem_document(problem_id)
                ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(problem_revision_id) REFERENCES student_problem_revision(revision_id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        CREATE TABLE student_tutor_interaction_answer_certificate_status_event (
            status_event_id TEXT NOT NULL,
            certificate_id TEXT NOT NULL,
            status_generation INTEGER NOT NULL,
            status TEXT NOT NULL,
            reason_canonical_fingerprint TEXT NOT NULL,
            status_policy_version TEXT NOT NULL,
            occurred_at_epoch_millis INTEGER NOT NULL,
            status_canonical_fingerprint TEXT NOT NULL,
            PRIMARY KEY(status_event_id),
            FOREIGN KEY(certificate_id)
                REFERENCES student_tutor_interaction_answer_certificate(certificate_id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        CREATE TABLE student_tutor_interaction_answer_certificate_lease_receipt (
            lease_receipt_id TEXT NOT NULL,
            certificate_id TEXT NOT NULL,
            learner_id TEXT NOT NULL,
            certificate_status_generation INTEGER NOT NULL,
            presentation_answer_free_canonical_fingerprint TEXT NOT NULL,
            issued_at_epoch_millis INTEGER NOT NULL,
            not_after_epoch_millis INTEGER NOT NULL,
            lease_canonical_fingerprint TEXT NOT NULL,
            PRIMARY KEY(lease_receipt_id),
            FOREIGN KEY(certificate_id)
                REFERENCES student_tutor_interaction_answer_certificate(certificate_id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
        """
        CREATE TABLE student_tutor_interaction_answer_evaluation_receipt (
            evaluation_receipt_id TEXT NOT NULL,
            lease_receipt_id TEXT NOT NULL,
            certificate_id TEXT NOT NULL,
            learner_id TEXT NOT NULL,
            response_canonical_fingerprint TEXT NOT NULL,
            response_was_correct INTEGER NOT NULL,
            evaluated_at_epoch_millis INTEGER NOT NULL,
            evaluation_canonical_fingerprint TEXT NOT NULL,
            PRIMARY KEY(evaluation_receipt_id),
            FOREIGN KEY(lease_receipt_id)
                REFERENCES student_tutor_interaction_answer_certificate_lease_receipt(lease_receipt_id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
    )

internal val STUDENT_TUTOR_ANSWER_CERTIFICATE_V20_INDEX_SQL =
    listOf(
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_certificate_admission_idempotency_key ON student_tutor_interaction_answer_certificate(admission_idempotency_key)",
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_certificate_owner_canonical_fingerprint ON student_tutor_interaction_answer_certificate(owner_canonical_fingerprint)",
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_certificate_provenance_receipt_canonical_fingerprint ON student_tutor_interaction_answer_certificate(provenance_receipt_canonical_fingerprint)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_learner_id_problem_revision_id ON student_tutor_interaction_answer_certificate(learner_id, problem_revision_id)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_session_id_model_task_request_id ON student_tutor_interaction_answer_certificate(session_id, model_task_request_id)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_certificate_family_canonical_fingerprint_issued_at_epoch_millis ON student_tutor_interaction_answer_certificate(certificate_family_canonical_fingerprint, issued_at_epoch_millis)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_presentation_canonical_fingerprint ON student_tutor_interaction_answer_certificate(presentation_canonical_fingerprint)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_problem_id ON student_tutor_interaction_answer_certificate(problem_id)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_problem_revision_id ON student_tutor_interaction_answer_certificate(problem_revision_id)",
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_certificate_status_event_certificate_id_status_generation ON student_tutor_interaction_answer_certificate_status_event(certificate_id, status_generation)",
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_certificate_status_event_status_canonical_fingerprint ON student_tutor_interaction_answer_certificate_status_event(status_canonical_fingerprint)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_status_event_certificate_id_status_status_generation ON student_tutor_interaction_answer_certificate_status_event(certificate_id, status, status_generation)",
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_certificate_lease_receipt_lease_canonical_fingerprint ON student_tutor_interaction_answer_certificate_lease_receipt(lease_canonical_fingerprint)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_lease_receipt_certificate_id_certificate_status_generation ON student_tutor_interaction_answer_certificate_lease_receipt(certificate_id, certificate_status_generation)",
        "CREATE INDEX index_student_tutor_interaction_answer_certificate_lease_receipt_learner_id_issued_at_epoch_millis ON student_tutor_interaction_answer_certificate_lease_receipt(learner_id, issued_at_epoch_millis)",
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_evaluation_receipt_lease_receipt_id ON student_tutor_interaction_answer_evaluation_receipt(lease_receipt_id)",
        "CREATE UNIQUE INDEX index_student_tutor_interaction_answer_evaluation_receipt_evaluation_canonical_fingerprint ON student_tutor_interaction_answer_evaluation_receipt(evaluation_canonical_fingerprint)",
        "CREATE INDEX index_student_tutor_interaction_answer_evaluation_receipt_learner_id_evaluated_at_epoch_millis ON student_tutor_interaction_answer_evaluation_receipt(learner_id, evaluated_at_epoch_millis)",
    )
