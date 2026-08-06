package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BatchBoundaryClaimTimeMigrationInstrumentedTest {
    @Test
    fun versionFortyUpgradesThroughTheRegisteredMigrationChain() = runBlocking {
        migrateAndAssert(startVersion = 40)
    }

    @Test
    fun versionFortyOneBackfillsOnlyAnActiveBoundaryClaim() = runBlocking {
        migrateAndAssert(startVersion = 41)
    }

    private suspend fun migrateAndAssert(startVersion: Int) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName =
            "batch-boundary-claim-v$startVersion-to-v$STUDY_DATABASE_VERSION-" +
            "${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 40)
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { database ->
                if (startVersion == 41) database.advanceToVersionFortyOneShape()
                database.insertBatchBoundaryFixtures()
            }

            StudyDatabaseFactory.open(context, databaseName).use { store ->
                val migrated = checkNotNull(store.readBatchImportJob(JOB_ID))
                assertEquals(CLAIMED_AT, migrated.pages[0].boundaryClaimedAtEpochMillis)
                assertNull(migrated.pages[1].boundaryClaimedAtEpochMillis)
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    """
                    SELECT COUNT(*)
                    FROM sqlite_master
                    WHERE type = 'table'
                      AND name = 'batch_import_boundary_resolution_receipt'
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(1, cursor.getInt(0))
                }
                database.rawQuery(
                    """
                    SELECT boundary_claimed_at_epoch_millis
                    FROM batch_import_page
                    WHERE job_id = ? AND page_index = 0
                    """.trimIndent(),
                    arrayOf(JOB_ID),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals(CLAIMED_AT, cursor.getLong(0))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun SQLiteDatabase.insertBatchBoundaryFixtures() {
        execSQL(
            """
            INSERT INTO batch_import_job (
                job_id, request_id, request_fingerprint, status,
                created_at_epoch_millis, updated_at_epoch_millis
            ) VALUES (
                '$JOB_ID', 'migration-request', '${"a".repeat(64)}', 'COMPLETED',
                1000, $CLAIMED_AT
            )
            """.trimIndent(),
        )
        execSQL(
            """
            INSERT INTO batch_import_page (
                job_id, page_index, source_uri, status, result_draft_id,
                failure_code, attempt_count, created_at_epoch_millis,
                updated_at_epoch_millis, boundary_after_status
            ) VALUES
                (
                    '$JOB_ID', 0, 'content://migration/0', 'READY', NULL,
                    NULL, 1, 1000, $CLAIMED_AT, 'CHECKING'
                ),
                (
                    '$JOB_ID', 1, 'content://migration/1', 'READY', NULL,
                    NULL, 1, 1000, 1300, 'PENDING'
                )
            """.trimIndent(),
        )
    }

    private fun SQLiteDatabase.advanceToVersionFortyOneShape() {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS `capture_draft_merge_session_receipt` (
                `receipt_reference` TEXT NOT NULL,
                `batch_job_id` TEXT NOT NULL,
                `batch_page_index` INTEGER NOT NULL,
                `primary_draft_id` TEXT NOT NULL,
                `following_draft_id` TEXT NOT NULL,
                `merged_draft_id` TEXT NOT NULL,
                `asset_order_fingerprint` TEXT NOT NULL,
                `session_version` INTEGER NOT NULL,
                `source_asset_count` INTEGER NOT NULL,
                `request_canonical_fingerprint` TEXT NOT NULL,
                `merged_at_epoch_millis` INTEGER NOT NULL,
                PRIMARY KEY(`batch_job_id`, `batch_page_index`)
            )
            """.trimIndent(),
        )
        execSQL(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS
            `index_capture_draft_merge_session_receipt_receipt_reference`
            ON `capture_draft_merge_session_receipt` (`receipt_reference`)
            """.trimIndent(),
        )
        version = 41
    }

    private companion object {
        const val JOB_ID = "boundary-migration-job"
        const val CLAIMED_AT = 1_250L
    }
}
