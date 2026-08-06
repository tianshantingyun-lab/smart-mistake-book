package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Repairs the durable open-response receipt ledger for any v15 install produced before the
 * receipt owner was published. CREATE IF NOT EXISTS keeps already-correct v15 databases intact.
 */
internal val LEARNER_MASTERY_MIGRATION_15_16 =
    object : Migration(15, 16) {
        override suspend fun migrate(connection: SQLiteConnection) {
            openResponseWeakCandidateReceiptSchemaStatements().forEach(connection::execSQL)
            if (!connection.hasOpenResponseRevisionOrdinal()) {
                connection.execSQL(
                    """
                    ALTER TABLE `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
                    ADD COLUMN `revision_ordinal` INTEGER
                    """.trimIndent(),
                )
            }
            connection.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS
                    `index_${LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE}_logical_attempt_fingerprint_revision_ordinal`
                ON `$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`
                    (`logical_attempt_fingerprint`, `revision_ordinal`)
                """.trimIndent(),
            )
            connection.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_mastery_event_attribution_knowledge_ref_cover`
                ON `mastery_learning_event_attribution` (
                    `knowledge_node_ref_fingerprint`, `subject`, `knowledge_node_id`,
                    `taxonomy_version`, `knowledge_pack_version`
                )
                """.trimIndent(),
            )
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

private fun SQLiteConnection.hasOpenResponseRevisionOrdinal(): Boolean =
    prepare(
        "PRAGMA table_info(`$LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE`)",
    ).use { statement ->
        while (statement.step()) {
            if (statement.getText(1) == "revision_ordinal") return true
        }
        false
    }
