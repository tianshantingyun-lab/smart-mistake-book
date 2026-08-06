package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * Replaces directionless derived budgets with empty directional tables.
 *
 * The immutable ledger is deliberately not replayed inside the schema transaction. The owner
 * worker resumes from a bounded keyset cursor after open and atomically activates a new generation.
 */
internal val LEARNER_MASTERY_MIGRATION_19_20: Migration =
    object : Migration(19, 20) {
        override suspend fun migrate(connection: SQLiteConnection) {
            val before = DirectionalBudgetMigrationWatermark.read(connection)
            val recordedAtEpochMillis = System.currentTimeMillis()
            check(
                connection.countRows(
                    tableName = "mastery_store_metadata",
                    predicate =
                        "metadata_key IN (" +
                            "'$DIRECTIONAL_BUDGET_REBUILD_REQUIRED_METADATA_KEY', " +
                            "'$DIRECTIONAL_BUDGET_REBUILD_COMPLETED_METADATA_KEY') " +
                            "OR metadata_key GLOB " +
                            "'presentation_fingerprint_budget_generation_epoch_v2:*'",
                ) == 0L,
            ) {
                "DB19 contains reserved DB20 directional-budget metadata"
            }

            connection.execSQL(
                "DELETE FROM mastery_projection_shadow WHERE generation_id IN " +
                    "(SELECT generation_id FROM mastery_projection_generation " +
                    "WHERE state = 'BUILDING')",
            )
            connection.execSQL(
                "DELETE FROM mastery_subject_digest_shadow WHERE generation_id IN " +
                    "(SELECT generation_id FROM mastery_projection_generation " +
                    "WHERE state = 'BUILDING')",
            )
            connection.execSQL(
                "UPDATE mastery_projection_generation SET state = 'RETIRED', " +
                    "lease_owner_id = NULL, lease_expires_at_epoch_millis = NULL " +
                    "WHERE state = 'BUILDING'",
            )

            // These four tables are caches. Their v19 rows have no recoverable direction identity.
            connection.execSQL("DROP TABLE mastery_presentation_node_budget")
            connection.execSQL("DROP TABLE mastery_problem_family_node_budget")
            connection.execSQL("DROP TABLE mastery_presentation_node_budget_shadow")
            connection.execSQL("DROP TABLE mastery_problem_family_node_budget_shadow")
            createDirectionalBudgetTables(connection)
            connection.execSQL(
                "CREATE UNIQUE INDEX index_mastery_learning_event_directional_budget_replay " +
                    "ON mastery_learning_event(learner_id, occurred_at_epoch_millis, " +
                    "event_id, direction)",
            )

            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "cursor_occurred_at_epoch_millis INTEGER NOT NULL DEFAULT -1",
            )
            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "cursor_event_id TEXT NOT NULL DEFAULT ''",
            )
            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "cursor_direction TEXT NOT NULL DEFAULT ''",
            )
            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "presentation_budget_row_count INTEGER",
            )
            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "problem_family_budget_row_count INTEGER",
            )
            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "budget_input_snapshot_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "budget_output_fingerprint TEXT",
            )
            connection.execSQL(
                "ALTER TABLE mastery_projection_generation ADD COLUMN " +
                    "budget_input_row_count INTEGER NOT NULL DEFAULT 0",
            )
            createDirectionalBudgetReceiptTables(connection)

            connection.execSQL(
                "INSERT OR IGNORE INTO mastery_store_metadata(metadata_key, metadata_value) " +
                    "VALUES('$DIRECTIONAL_BUDGET_REBUILD_REQUIRED_METADATA_KEY', " +
                    "'$DIRECTIONAL_BUDGET_REBUILD_EPOCH')",
            )
            check(
                connection.prepare(
                    "SELECT metadata_value FROM mastery_store_metadata WHERE metadata_key = " +
                        "'$DIRECTIONAL_BUDGET_REBUILD_REQUIRED_METADATA_KEY' LIMIT 1",
                ).use { statement ->
                    statement.step() &&
                        statement.getText(0) == DIRECTIONAL_BUDGET_REBUILD_EPOCH
                },
            ) { "Directional budget rebuild marker conflicts" }
            val migrationFingerprint =
                before.fingerprint(recordedAtEpochMillis = recordedAtEpochMillis)
            connection.execSQL(
                """
                INSERT INTO $LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE(
                    migration_id, from_schema_version, to_schema_version,
                    immutable_event_count, immutable_attribution_count, supersession_count,
                    discarded_active_presentation_count,
                    discarded_active_problem_family_count,
                    discarded_shadow_presentation_count,
                    discarded_shadow_problem_family_count,
                    retired_building_generation_count, required_rebuild_epoch,
                    canonical_fingerprint, recorded_at_epoch_millis
                ) VALUES(
                    '$DIRECTIONAL_BUDGET_MIGRATION_ID', 19, 20,
                    ${before.eventCount}, ${before.attributionCount}, ${before.supersessionCount},
                    ${before.activePresentationCount}, ${before.activeProblemFamilyCount},
                    ${before.shadowPresentationCount}, ${before.shadowProblemFamilyCount},
                    ${before.buildingGenerationCount},
                    '$DIRECTIONAL_BUDGET_REBUILD_EPOCH',
                    '$migrationFingerprint', $recordedAtEpochMillis
                )
                """.trimIndent(),
            )

            val after = DirectionalBudgetMigrationWatermark.read(connection)
            check(after.eventCount == before.eventCount) {
                "Directional budget migration changed immutable learning events"
            }
            check(after.attributionCount == before.attributionCount) {
                "Directional budget migration changed immutable attributions"
            }
            check(after.supersessionCount == before.supersessionCount) {
                "Directional budget migration changed immutable supersessions"
            }
            check(
                after.activePresentationCount == 0L &&
                    after.activeProblemFamilyCount == 0L &&
                    after.shadowPresentationCount == 0L &&
                    after.shadowProblemFamilyCount == 0L,
            ) { "Directionless derived budgets survived migration" }
            installLearnerMasteryImmutableLedgerGuards(connection)
        }
    }

private data class DirectionalBudgetMigrationWatermark(
    val eventCount: Long,
    val attributionCount: Long,
    val supersessionCount: Long,
    val activePresentationCount: Long,
    val activeProblemFamilyCount: Long,
    val shadowPresentationCount: Long,
    val shadowProblemFamilyCount: Long,
    val buildingGenerationCount: Long,
) {
    fun fingerprint(recordedAtEpochMillis: Long): String =
        CanonicalSha256("learner-mastery-directional-budget-migration-receipt-v1")
            .field("migrationId", DIRECTIONAL_BUDGET_MIGRATION_ID)
            .field("fromSchemaVersion", 19)
            .field("toSchemaVersion", 20)
            .field("eventCount", eventCount)
            .field("attributionCount", attributionCount)
            .field("supersessionCount", supersessionCount)
            .field("activePresentationCount", activePresentationCount)
            .field("activeProblemFamilyCount", activeProblemFamilyCount)
            .field("shadowPresentationCount", shadowPresentationCount)
            .field("shadowProblemFamilyCount", shadowProblemFamilyCount)
            .field("buildingGenerationCount", buildingGenerationCount)
            .field("requiredEpoch", DIRECTIONAL_BUDGET_REBUILD_EPOCH)
            .field("recordedAtEpochMillis", recordedAtEpochMillis)
            .finish()

    companion object {
        fun read(connection: SQLiteConnection): DirectionalBudgetMigrationWatermark =
            DirectionalBudgetMigrationWatermark(
                eventCount = connection.countRows("mastery_learning_event"),
                attributionCount = connection.countRows("mastery_learning_event_attribution"),
                supersessionCount =
                    connection.countRows("mastery_learning_evidence_supersession"),
                activePresentationCount =
                    connection.countRows("mastery_presentation_node_budget"),
                activeProblemFamilyCount =
                    connection.countRows("mastery_problem_family_node_budget"),
                shadowPresentationCount =
                    connection.countRows("mastery_presentation_node_budget_shadow"),
                shadowProblemFamilyCount =
                    connection.countRows("mastery_problem_family_node_budget_shadow"),
                buildingGenerationCount =
                    connection.countRows(
                        tableName = "mastery_projection_generation",
                        predicate = "state = 'BUILDING'",
                    ),
            )
    }
}

private fun SQLiteConnection.countRows(
    tableName: String,
    predicate: String = "1 = 1",
): Long =
    prepare("SELECT COUNT(*) FROM `$tableName` WHERE $predicate").use { statement ->
        check(statement.step())
        statement.getLong(0)
    }

private fun createDirectionalBudgetTables(connection: SQLiteConnection) {
    DIRECTIONAL_BUDGET_TABLE_SQL.forEach(connection::execSQL)
}

private fun createDirectionalBudgetReceiptTables(connection: SQLiteConnection) {
    DIRECTIONAL_BUDGET_RECEIPT_TABLE_SQL.forEach(connection::execSQL)
}

private const val DIRECTIONAL_BUDGET_MIGRATION_ID = "learner-mastery-db19-db20"

private val DIRECTIONAL_BUDGET_TABLE_SQL =
    listOf(
        """
        CREATE TABLE mastery_presentation_node_budget (
            learner_id TEXT NOT NULL, presentation_id TEXT NOT NULL,
            subject TEXT NOT NULL, knowledge_node_id TEXT NOT NULL,
            taxonomy_version TEXT NOT NULL, direction TEXT NOT NULL,
            stable_node_identity_fingerprint TEXT NOT NULL,
            consumed_mass_micros INTEGER NOT NULL, last_event_id TEXT NOT NULL,
            updated_at_epoch_millis INTEGER NOT NULL,
            PRIMARY KEY(learner_id, presentation_id, subject, knowledge_node_id,
                taxonomy_version, direction)
        )
        """.trimIndent(),
        "CREATE INDEX index_mastery_presentation_node_budget_learner_id_subject_direction_updated_at_epoch_millis ON mastery_presentation_node_budget(learner_id, subject, direction, updated_at_epoch_millis)",
        "CREATE INDEX index_mastery_presentation_node_budget_stable_node_identity_fingerprint_direction ON mastery_presentation_node_budget(stable_node_identity_fingerprint, direction)",
        """
        CREATE TABLE mastery_problem_family_node_budget (
            learner_id TEXT NOT NULL, problem_family_fingerprint TEXT NOT NULL,
            subject TEXT NOT NULL, knowledge_node_id TEXT NOT NULL,
            taxonomy_version TEXT NOT NULL, direction TEXT NOT NULL,
            stable_node_identity_fingerprint TEXT NOT NULL,
            observation_count INTEGER NOT NULL, consumed_mass_micros INTEGER NOT NULL,
            last_event_id TEXT NOT NULL, updated_at_epoch_millis INTEGER NOT NULL,
            PRIMARY KEY(learner_id, problem_family_fingerprint, subject, knowledge_node_id,
                taxonomy_version, direction)
        )
        """.trimIndent(),
        "CREATE INDEX index_mastery_problem_family_node_budget_learner_id_subject_direction_updated_at_epoch_millis ON mastery_problem_family_node_budget(learner_id, subject, direction, updated_at_epoch_millis)",
        "CREATE INDEX index_mastery_problem_family_node_budget_stable_node_identity_fingerprint_direction ON mastery_problem_family_node_budget(stable_node_identity_fingerprint, direction)",
        """
        CREATE TABLE mastery_presentation_node_budget_shadow (
            generation_id INTEGER NOT NULL, learner_id TEXT NOT NULL,
            presentation_id TEXT NOT NULL, subject TEXT NOT NULL,
            knowledge_node_id TEXT NOT NULL, taxonomy_version TEXT NOT NULL,
            direction TEXT NOT NULL, stable_node_identity_fingerprint TEXT NOT NULL,
            consumed_mass_micros INTEGER NOT NULL, last_event_id TEXT NOT NULL,
            updated_at_epoch_millis INTEGER NOT NULL,
            PRIMARY KEY(generation_id, learner_id, presentation_id, subject,
                knowledge_node_id, taxonomy_version, direction)
        )
        """.trimIndent(),
        "CREATE INDEX index_mastery_presentation_node_budget_shadow_generation_id_learner_id_subject_direction ON mastery_presentation_node_budget_shadow(generation_id, learner_id, subject, direction)",
        """
        CREATE TABLE mastery_problem_family_node_budget_shadow (
            generation_id INTEGER NOT NULL, learner_id TEXT NOT NULL,
            problem_family_fingerprint TEXT NOT NULL, subject TEXT NOT NULL,
            knowledge_node_id TEXT NOT NULL, taxonomy_version TEXT NOT NULL,
            direction TEXT NOT NULL, stable_node_identity_fingerprint TEXT NOT NULL,
            observation_count INTEGER NOT NULL, consumed_mass_micros INTEGER NOT NULL,
            last_event_id TEXT NOT NULL, updated_at_epoch_millis INTEGER NOT NULL,
            PRIMARY KEY(generation_id, learner_id, problem_family_fingerprint, subject,
                knowledge_node_id, taxonomy_version, direction)
        )
        """.trimIndent(),
        "CREATE INDEX index_mastery_problem_family_node_budget_shadow_generation_id_learner_id_subject_direction ON mastery_problem_family_node_budget_shadow(generation_id, learner_id, subject, direction)",
    )

private val DIRECTIONAL_BUDGET_RECEIPT_TABLE_SQL =
    listOf(
        """
        CREATE TABLE $LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE (
            generation_id INTEGER NOT NULL, budget_policy_version TEXT NOT NULL,
            algorithm_version TEXT NOT NULL, source_event_count INTEGER NOT NULL,
            source_supersession_count INTEGER NOT NULL, input_row_count INTEGER NOT NULL,
            input_snapshot_fingerprint TEXT NOT NULL,
            presentation_budget_row_count INTEGER NOT NULL,
            problem_family_budget_row_count INTEGER NOT NULL,
            last_occurred_at_epoch_millis INTEGER, last_event_id TEXT,
            last_attribution_ordinal INTEGER, last_direction TEXT,
            output_fingerprint TEXT NOT NULL, completed_at_epoch_millis INTEGER NOT NULL,
            PRIMARY KEY(generation_id),
            FOREIGN KEY(generation_id) REFERENCES mastery_projection_generation(generation_id)
                ON UPDATE NO ACTION ON DELETE RESTRICT
        )
        """.trimIndent(),
        "CREATE INDEX index_mastery_projection_budget_rebuild_receipt_input_snapshot_fingerprint ON $LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE(input_snapshot_fingerprint)",
        "CREATE INDEX index_mastery_projection_budget_rebuild_receipt_output_fingerprint ON $LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE(output_fingerprint)",
        "CREATE INDEX index_mastery_projection_budget_rebuild_receipt_completed_at_epoch_millis ON $LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE(completed_at_epoch_millis)",
        """
        CREATE TABLE $LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE (
            migration_id TEXT NOT NULL, from_schema_version INTEGER NOT NULL,
            to_schema_version INTEGER NOT NULL, immutable_event_count INTEGER NOT NULL,
            immutable_attribution_count INTEGER NOT NULL, supersession_count INTEGER NOT NULL,
            discarded_active_presentation_count INTEGER NOT NULL,
            discarded_active_problem_family_count INTEGER NOT NULL,
            discarded_shadow_presentation_count INTEGER NOT NULL,
            discarded_shadow_problem_family_count INTEGER NOT NULL,
            retired_building_generation_count INTEGER NOT NULL,
            required_rebuild_epoch TEXT NOT NULL, canonical_fingerprint TEXT NOT NULL,
            recorded_at_epoch_millis INTEGER NOT NULL, PRIMARY KEY(migration_id)
        )
        """.trimIndent(),
        "CREATE UNIQUE INDEX index_mastery_directional_budget_migration_receipt_canonical_fingerprint ON $LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE(canonical_fingerprint)",
        "CREATE INDEX index_mastery_directional_budget_migration_receipt_recorded_at_epoch_millis ON $LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE(recorded_at_epoch_millis)",
    )
