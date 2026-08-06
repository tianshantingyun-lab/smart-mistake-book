package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Preserves the v11 receipt fingerprint protocol, adds append-only renewal generations, and adds
 * the exact identity-evidence lookup index.
 *
 * Version 11 remains immutable historical schema. Existing rows become generation one; a renewal
 * is always another row and never updates the expired receipt.
 */
internal val STUDENT_MISTAKE_MIGRATION_11_12 =
    object : Migration(11, 12) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                """
                ALTER TABLE `student_problem_identity_receipt`
                ADD COLUMN `fingerprint_version` INTEGER NOT NULL DEFAULT 1
                """.trimIndent(),
            )
            connection.execSQL(
                """
                ALTER TABLE `student_problem_identity_receipt`
                ADD COLUMN `renewal_generation` INTEGER NOT NULL DEFAULT 1
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS
                    `index_student_problem_identity_receipt_identity_reuse_lookup`
                ON `student_problem_identity_receipt` (
                    `learner_id`,
                    `subject`,
                    `receipt_kind`,
                    `asset_manifest_canonical_fingerprint`,
                    `selected_region_canonical_fingerprint`,
                    `candidate_canonical_fingerprint`,
                    `renewal_generation`,
                    `issued_at_epoch_millis`,
                    `receipt_id`
                )
                """.trimIndent(),
            )
            createStudentProblemIdentityReceiptImmutabilityTriggers(connection)
            verifyStudentProblemIdentityReceiptImmutabilityTriggers(connection)
        }
    }

internal const val STUDENT_PROBLEM_IDENTITY_REUSE_INDEX_NAME =
    "index_student_problem_identity_receipt_identity_reuse_lookup"
