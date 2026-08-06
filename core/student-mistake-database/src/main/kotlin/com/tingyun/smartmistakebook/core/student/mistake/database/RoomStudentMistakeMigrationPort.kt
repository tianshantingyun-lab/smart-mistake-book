package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal class RoomStudentMistakeMigrationPort(
    private val database: StudentMistakeRoomDatabase,
) : StudentMistakeMigrationPort {
    private val dao = database.mistakeDao()
    private val store = RoomStudentMistakeStore(database)

    override suspend fun applyPage(
        command: ApplyStudentMistakeMigrationPageCommand,
    ): StudentMistakeMigrationReceipt {
        val previous = dao.readMigrationCheckpoint(command.migrationId)
        val virtualProblems = mutableMapOf<String, StudentProblemDocumentEntity>()
        val virtualCollections = mutableMapOf<String, StudentProblemCollectionEntity>()
        val problemBundles =
            command.records.map { record ->
                val problemId = record.problem.revision.problem.problemId
                val existingProblem =
                    virtualProblems[problemId] ?: dao.readProblem(problemId)
                store.prepareCommitBundle(
                    command = record.problem,
                    existingProblem = existingProblem,
                ).also { bundle ->
                    virtualProblems[problemId] =
                        (existingProblem ?: bundle.problem).copy(
                            currentRevisionId = bundle.revision.revisionId,
                            errorBookEntryId =
                                existingProblem?.errorBookEntryId
                                    ?: bundle.problem.errorBookEntryId,
                            updatedAtEpochMillis = bundle.problem.updatedAtEpochMillis,
                        )
                }
            }
        val collections =
            command.records.map { record ->
                val collection = record.collection
                val practiceUnitId = collection.problem.practiceUnitId
                store.prepareCollectionEntity(
                    command = collection,
                    existing =
                        virtualCollections[practiceUnitId]
                            ?: dao.readCollection(practiceUnitId),
                ).also { virtualCollections[practiceUnitId] = it }
            }
        val initialReviewCandidates =
            command.records.zip(collections).map { (record, collection) ->
                if (collection.mistakeState == StudentMistakeEntryState.ACTIVE.name) {
                    store.prepareInitialReviewCandidate(
                        command = record.problem,
                        collection = collection,
                    )
                } else {
                    null
                }
            }
        val importSnapshots =
            command.records.zip(problemBundles).map { (record, bundle) ->
                createStudentProblemImportSemanticSnapshot(
                    bundle = bundle,
                    semantics = record.importSemanticSnapshot,
                )
            }
        val destinationRecords =
            command.records.indices.map { index ->
                val record = command.records[index]
                val bundle = problemBundles[index]
                val importSnapshot = importSnapshots[index]
                StudentMistakeMigrationDestinationRecordEntity(
                    migrationId = command.migrationId,
                    sourcePageCanonicalFingerprint =
                        command.sourcePageCanonicalFingerprint,
                    pageRecordOrdinal = index,
                    committedAtEpochMillis = record.key.committedAtEpochMillis,
                    problemId = record.key.problemId,
                    revisionNumber = record.key.revisionNumber,
                    revisionId = record.key.revisionId,
                    importSnapshotCanonicalFingerprint =
                        importSnapshot.snapshotCanonicalFingerprint,
                    destinationRecordCanonicalFingerprint =
                        bundle
                            .toImmutableRevisionSnapshot(importSnapshot)
                            .canonicalFingerprint(),
                )
            }
        val previousCount = previous?.importedRecordCount ?: 0L
        val lastKey = command.records.lastOrNull()?.key ?: previous?.toDomain()?.lastKey
        val checkpointFingerprint =
            migrationCheckpointFingerprint(
                migrationId = command.migrationId,
                sourceDatabaseCanonicalFingerprint =
                    command.sourceDatabaseCanonicalFingerprint,
                sourcePageCanonicalFingerprint = command.sourcePageCanonicalFingerprint,
                lastKey = lastKey,
                importedRecordCount = previousCount + command.records.size,
                completed = command.isLastPage,
            )
        val checkpoint =
            StudentMistakeMigrationCheckpointEntity(
                migrationId = command.migrationId,
                sourceDatabaseCanonicalFingerprint =
                    command.sourceDatabaseCanonicalFingerprint,
                lastCommittedAtEpochMillis = lastKey?.committedAtEpochMillis,
                lastProblemId = lastKey?.problemId,
                lastRevisionNumber = lastKey?.revisionNumber,
                lastRevisionId = lastKey?.revisionId,
                importedRecordCount = previousCount + command.records.size,
                completed = command.isLastPage,
                destinationLedgerVersion = STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION,
                checkpointCanonicalFingerprint = checkpointFingerprint,
                updatedAtEpochMillis = command.appliedAtEpochMillis,
            )
        val receiptFingerprint =
            CanonicalSha256(MIGRATION_RECEIPT_DOMAIN)
                .field("migrationId", command.migrationId)
                .field("sourcePageCanonicalFingerprint", command.sourcePageCanonicalFingerprint)
                .field("importedRecordCount", command.records.size)
                .field("checkpointCanonicalFingerprint", checkpointFingerprint)
                .finish()
        val receipt =
            StudentMistakeMigrationReceiptEntity(
                migrationId = command.migrationId,
                sourcePageCanonicalFingerprint = command.sourcePageCanonicalFingerprint,
                importedRecordCount = command.records.size,
                resultLastCommittedAtEpochMillis = lastKey?.committedAtEpochMillis,
                resultLastProblemId = lastKey?.problemId,
                resultLastRevisionNumber = lastKey?.revisionNumber,
                resultLastRevisionId = lastKey?.revisionId,
                resultTotalRecordCount = checkpoint.importedRecordCount,
                resultCompleted = checkpoint.completed,
                checkpointCanonicalFingerprint = checkpointFingerprint,
                receiptCanonicalFingerprint = receiptFingerprint,
                appliedAtEpochMillis = command.appliedAtEpochMillis,
            )
        return dao.applyMigrationPage(
            ApplyStudentMistakeMigrationBundle(
                command = command,
                problemBundles = problemBundles,
                collections = collections,
                initialReviewCandidates = initialReviewCandidates,
                importSnapshots = importSnapshots,
                destinationRecords = destinationRecords,
                checkpoint = checkpoint,
                receipt = receipt,
            ),
        ).toDomain(command.sourceDatabaseCanonicalFingerprint)
    }

    override suspend fun readCheckpoint(
        migrationId: String,
    ): StudentMistakeMigrationCheckpoint? {
        migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
        return dao.readMigrationCheckpoint(migrationId)?.toDomain()
    }

    override fun close() {
        store.close()
    }
}

internal object StudentMistakeMigrationPortFactory {
    fun open(
        context: Context,
        ownerKey: StudentMistakeOwnerKey,
    ): StudentMistakeMigrationPort {
        check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
            "Student-mistake migration requires the core:data owner key"
        }
        return RoomStudentMistakeMigrationPort(
            StudentMistakeOwnedDatabase.openDatabase(context, ownerKey),
        )
    }
}

private fun migrationCheckpointFingerprint(
    migrationId: String,
    sourceDatabaseCanonicalFingerprint: String,
    sourcePageCanonicalFingerprint: String,
    lastKey: StudentMistakeMigrationKey?,
    importedRecordCount: Long,
    completed: Boolean,
): String =
    CanonicalSha256(MIGRATION_CHECKPOINT_DOMAIN)
        .field("migrationId", migrationId)
        .field("sourceDatabaseCanonicalFingerprint", sourceDatabaseCanonicalFingerprint)
        .field("sourcePageCanonicalFingerprint", sourcePageCanonicalFingerprint)
        .nullableField(
            "lastCommittedAtEpochMillis",
            lastKey?.committedAtEpochMillis?.toString(),
        )
        .nullableField("lastProblemId", lastKey?.problemId)
        .nullableField("lastRevisionNumber", lastKey?.revisionNumber?.toString())
        .nullableField("lastRevisionId", lastKey?.revisionId)
        .field("importedRecordCount", importedRecordCount)
        .field("completed", completed)
        .finish()

private fun StudentMistakeMigrationCheckpointEntity.toDomain(): StudentMistakeMigrationCheckpoint {
    val lastKey =
        lastCommittedAtEpochMillis?.let { committedAt ->
            StudentMistakeMigrationKey(
                committedAtEpochMillis = committedAt,
                problemId = checkNotNull(lastProblemId) {
                    "Corrupt migration checkpoint: missing problem id"
                },
                revisionNumber = checkNotNull(lastRevisionNumber) {
                    "Corrupt migration checkpoint: missing revision number"
                },
                revisionId = checkNotNull(lastRevisionId) {
                    "Corrupt migration checkpoint: missing revision id"
                },
            )
        }
    check(
        (lastKey == null) ==
            (
                lastProblemId == null &&
                    lastRevisionNumber == null &&
                    lastRevisionId == null
            ),
    ) {
        "Corrupt migration checkpoint: partial key"
    }
    return StudentMistakeMigrationCheckpoint(
        migrationId = migrationId,
        sourceDatabaseCanonicalFingerprint = sourceDatabaseCanonicalFingerprint,
        lastKey = lastKey,
        importedRecordCount = importedRecordCount,
        completed = completed,
        checkpointCanonicalFingerprint = checkpointCanonicalFingerprint,
    )
}

private fun StudentMistakeMigrationReceiptEntity.toDomain(
    sourceDatabaseCanonicalFingerprint: String,
): StudentMistakeMigrationReceipt {
    check(
        (resultLastCommittedAtEpochMillis == null) ==
            (
                resultLastProblemId == null &&
                    resultLastRevisionNumber == null &&
                    resultLastRevisionId == null
            ),
    ) {
        "Corrupt migration receipt: partial key"
    }
    val lastKey =
        resultLastCommittedAtEpochMillis?.let { committedAt ->
            StudentMistakeMigrationKey(
                committedAtEpochMillis = committedAt,
                problemId = checkNotNull(resultLastProblemId),
                revisionNumber = checkNotNull(resultLastRevisionNumber),
                revisionId = checkNotNull(resultLastRevisionId),
            )
        }
    return StudentMistakeMigrationReceipt(
        migrationId = migrationId,
        sourcePageCanonicalFingerprint = sourcePageCanonicalFingerprint,
        importedRecordCount = importedRecordCount,
        checkpoint =
            StudentMistakeMigrationCheckpoint(
                migrationId = migrationId,
                sourceDatabaseCanonicalFingerprint = sourceDatabaseCanonicalFingerprint,
                lastKey = lastKey,
                importedRecordCount = resultTotalRecordCount,
                completed = resultCompleted,
                checkpointCanonicalFingerprint = checkpointCanonicalFingerprint,
            ),
        receiptCanonicalFingerprint = receiptCanonicalFingerprint,
    )
}

private const val MIGRATION_CHECKPOINT_DOMAIN = "student-mistake-migration-checkpoint-v1"
private const val MIGRATION_RECEIPT_DOMAIN = "student-mistake-migration-receipt-v1"
internal const val STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION = 3
