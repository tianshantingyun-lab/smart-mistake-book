package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Update

/**
 * Student-mistake migration checkpoint, receipt and destination record primitives.
 */
@Dao
internal abstract class StudentMistakeMigrationDao : StudentMistakeSearchIndexDao() {
    @Query(
        """
        SELECT migration_id, source_database_canonical_fingerprint,
               last_committed_at_epoch_millis, last_problem_id,
               last_revision_number, last_revision_id, imported_record_count,
               completed, destination_ledger_version,
               checkpoint_canonical_fingerprint,
               updated_at_epoch_millis
        FROM student_mistake_migration_checkpoint
        WHERE migration_id = :migrationId
        LIMIT 1
        """,
    )
    abstract suspend fun readMigrationCheckpoint(
        migrationId: String,
    ): StudentMistakeMigrationCheckpointEntity?

    @Query(
        """
        SELECT migration_id, source_page_canonical_fingerprint,
               imported_record_count, result_last_committed_at_epoch_millis,
               result_last_problem_id, result_last_revision_number,
               result_last_revision_id, result_total_record_count,
               result_completed, checkpoint_canonical_fingerprint,
               receipt_canonical_fingerprint, applied_at_epoch_millis
        FROM student_mistake_migration_receipt
        WHERE migration_id = :migrationId
          AND source_page_canonical_fingerprint = :sourcePageCanonicalFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun readMigrationReceipt(
        migrationId: String,
        sourcePageCanonicalFingerprint: String,
    ): StudentMistakeMigrationReceiptEntity?

    @Query(
        """
        SELECT migration_id, source_page_canonical_fingerprint,
               page_record_ordinal, committed_at_epoch_millis,
               problem_id, revision_number, revision_id,
               import_snapshot_canonical_fingerprint,
               destination_record_canonical_fingerprint
        FROM student_mistake_migration_destination_record
        WHERE migration_id = :migrationId
          AND source_page_canonical_fingerprint = :sourcePageCanonicalFingerprint
        ORDER BY page_record_ordinal ASC
        """,
    )
    protected abstract suspend fun readMigrationDestinationRecords(
        migrationId: String,
        sourcePageCanonicalFingerprint: String,
    ): List<StudentMistakeMigrationDestinationRecordEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMigrationCheckpoint(
        checkpoint: StudentMistakeMigrationCheckpointEntity,
    )

    @Update
    protected abstract suspend fun updateMigrationCheckpoint(
        checkpoint: StudentMistakeMigrationCheckpointEntity,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMigrationReceipt(
        receipt: StudentMistakeMigrationReceiptEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMigrationDestinationRecords(
        records: List<StudentMistakeMigrationDestinationRecordEntity>,
    )
}
