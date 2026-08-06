package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Transaction

/**
 * Exact legacy migration page transaction shared by the main DAO and migration ports.
 */
@Dao
internal abstract class StudentMigrationWriteDao : StudentSaveWriteDao() {
    @Transaction
    open suspend fun applyMigrationPage(
        bundle: ApplyStudentMistakeMigrationBundle,
    ): StudentMistakeMigrationReceiptEntity {
        val command = bundle.command
        val existingCheckpoint = readMigrationCheckpoint(command.migrationId)
        check(
            existingCheckpoint == null ||
                existingCheckpoint.sourceDatabaseCanonicalFingerprint ==
                command.sourceDatabaseCanonicalFingerprint,
        ) {
            "Migration id is already bound to another source database"
        }
        check(
            existingCheckpoint == null ||
                existingCheckpoint.destinationLedgerVersion ==
                STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION,
        ) {
            "Legacy migration checkpoint is unverified; start a new exact migration id"
        }
        readMigrationReceipt(
            migrationId = command.migrationId,
            sourcePageCanonicalFingerprint = command.sourcePageCanonicalFingerprint,
        )?.let { existing ->
            check(
                readMigrationDestinationRecords(
                    migrationId = command.migrationId,
                    sourcePageCanonicalFingerprint =
                        command.sourcePageCanonicalFingerprint,
                ) == bundle.destinationRecords,
            ) {
                "Migration page receipt is not bound to its immutable destination records"
            }
            val persistedSnapshots =
                if (bundle.importSnapshots.isEmpty()) {
                    emptyList()
                } else {
                    readImportSnapshots(
                        bundle.importSnapshots.map(
                            StudentProblemImportSemanticSnapshotEntity::revisionId,
                        ),
                    )
                }.associateBy(StudentProblemImportSemanticSnapshotEntity::revisionId)
            check(
                bundle.importSnapshots.all { expected ->
                    persistedSnapshots[expected.revisionId] == expected
                } &&
                    persistedSnapshots.size == bundle.importSnapshots.size,
            ) {
                "Migration page receipt is not bound to its immutable import snapshots"
            }
            return existing
        }

        if (existingCheckpoint == null) {
            check(
                command.expectedCheckpointCanonicalFingerprint == null &&
                    command.afterExclusive == null,
            ) {
                "First migration page must start without a checkpoint"
            }
        } else {
            check(!existingCheckpoint.completed) {
                "Completed migration cannot accept another page"
            }
            check(
                command.expectedCheckpointCanonicalFingerprint ==
                    existingCheckpoint.checkpointCanonicalFingerprint,
            ) {
                "Migration checkpoint changed before this page was applied"
            }
            check(command.afterExclusive == existingCheckpoint.toMigrationKey()) {
                "Migration page key does not continue the persisted checkpoint"
            }
        }
        check(bundle.problemBundles.size == command.records.size) {
            "Migration page bundle count does not match its records"
        }
        check(bundle.collections.size == command.records.size) {
            "Migration collection bundle count does not match its records"
        }
        check(bundle.initialReviewCandidates.size == command.records.size) {
            "Migration review-candidate bundle count does not match its records"
        }
        check(bundle.importSnapshots.size == command.records.size) {
            "Migration import-snapshot bundle count does not match its records"
        }
        check(bundle.destinationRecords.size == command.records.size) {
            "Migration destination-record count does not match its records"
        }
        bundle.problemBundles.indices.forEach { index ->
            commitProblem(bundle.problemBundles[index], recordChange = false)
            setCollectionState(bundle.collections[index], recordChange = false)
            bundle.initialReviewCandidates[index]?.let { candidate ->
                applyReviewCandidate(candidate, recordChange = false)
            }
        }
        bundle.importSnapshots.forEach { snapshot ->
            insertImportSnapshot(snapshot)
            check(readImportSnapshot(snapshot.revisionId) == snapshot) {
                "Imported revision was replayed with different semantic snapshot content"
            }
        }
        if (existingCheckpoint == null) {
            // Receipt FKs need the parent first; terminal completion is promoted only after the
            // destination ledger and terminal receipt exist in this same transaction.
            insertMigrationCheckpoint(
                if (bundle.checkpoint.completed) {
                    bundle.checkpoint.copy(completed = false)
                } else {
                    bundle.checkpoint
                },
            )
        }
        bundle.destinationRecords.insertWhenNotEmpty(::insertMigrationDestinationRecords)
        insertMigrationReceipt(bundle.receipt)
        if (existingCheckpoint != null || bundle.checkpoint.completed) {
            check(updateMigrationCheckpoint(bundle.checkpoint) == 1) {
                "Migration checkpoint update did not affect exactly one row"
            }
        }
        bundle.problemBundles
            .mapTo(sortedSetOf()) { it.problem.learnerId }
            .forEach { learnerId -> bumpChangeVersion(learnerId) }
        return bundle.receipt
    }
}
