package com.tingyun.smartmistakebook.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v43: learner_chat_evidence gains `rejected_reason` / `rejected_at_epoch_millis`
 * (both NULL by default). A non-NULL rejected_reason marks a model-issued
 * evidence write that the local gate rejected (research
 * tutor-evidence-gate §3.3: rejected ≠ deleted — the row is kept as an
 * auditable observation and never enters the projection/ledger).
 */
internal val CHAT_EVIDENCE_GATE_REJECTION_MIGRATION_42_43 = object : Migration(42, 43) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `learner_chat_evidence` " +
                "ADD COLUMN `rejected_reason` TEXT DEFAULT NULL",
        )
        connection.execSQL(
            "ALTER TABLE `learner_chat_evidence` " +
                "ADD COLUMN `rejected_at_epoch_millis` INTEGER DEFAULT NULL",
        )
    }
}
