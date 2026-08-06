package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Schedules the one-time presentation-budget identity rebuild.
 *
 * DB14 keyed some already-derived presentation budgets by the source presentation id. The
 * immutable learning event already contains the authoritative presentation fingerprint, so DB15
 * appends only a durable rebuild request. Events, active projections, budgets, and generations are
 * deliberately untouched here: the resumable shadow-generation path performs the later cutover.
 */
internal val LEARNER_MASTERY_MIGRATION_14_15 =
    object : Migration(14, 15) {
        override suspend fun migrate(connection: SQLiteConnection) {
            check(
                connection.prepare(
                    "SELECT COUNT(*) FROM mastery_store_metadata " +
                        "WHERE metadata_key = " +
                        "'$PRESENTATION_FINGERPRINT_BUDGET_REBUILD_COMPLETED_METADATA_KEY' " +
                        "OR metadata_key GLOB " +
                        "'$PRESENTATION_FINGERPRINT_BUDGET_GENERATION_EPOCH_METADATA_PREFIX*'",
                ).use { statement ->
                    statement.step() && statement.getLong(0) == 0L
                },
            ) {
                "DB14 contains reserved DB15 presentation-budget metadata"
            }
            connection.execSQL(
                """
                INSERT OR IGNORE INTO mastery_store_metadata(metadata_key, metadata_value)
                VALUES(
                    '$PRESENTATION_FINGERPRINT_BUDGET_REBUILD_REQUIRED_METADATA_KEY',
                    '$PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH'
                )
                """.trimIndent(),
            )
            check(
                connection.prepare(
                    "SELECT metadata_value FROM mastery_store_metadata " +
                        "WHERE metadata_key = " +
                        "'$PRESENTATION_FINGERPRINT_BUDGET_REBUILD_REQUIRED_METADATA_KEY' " +
                        "LIMIT 1",
                ).use { statement ->
                    statement.step() &&
                        statement.getText(0) ==
                        PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH
                },
            ) {
                "Presentation-fingerprint budget rebuild marker conflicts"
            }
        }
    }

internal const val PRESENTATION_FINGERPRINT_BUDGET_REBUILD_EPOCH =
    "presentation-fingerprint-budget-v1"
internal const val PRESENTATION_FINGERPRINT_BUDGET_REBUILD_REQUIRED_METADATA_KEY =
    "presentation_fingerprint_budget_rebuild_required_v1"
internal const val PRESENTATION_FINGERPRINT_BUDGET_REBUILD_COMPLETED_METADATA_KEY =
    "presentation_fingerprint_budget_rebuild_completed_v1"
internal const val PRESENTATION_FINGERPRINT_BUDGET_GENERATION_EPOCH_METADATA_PREFIX =
    "presentation_fingerprint_budget_generation_epoch_v1:"

internal fun presentationFingerprintBudgetGenerationEpochMetadataKey(
    generationId: Long,
): String {
    require(generationId > 0L) { "Projection generation id must be positive" }
    return PRESENTATION_FINGERPRINT_BUDGET_GENERATION_EPOCH_METADATA_PREFIX + generationId
}
