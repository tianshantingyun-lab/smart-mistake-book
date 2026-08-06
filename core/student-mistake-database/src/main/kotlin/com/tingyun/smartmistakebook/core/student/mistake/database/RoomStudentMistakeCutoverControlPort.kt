package com.tingyun.smartmistakebook.core.student.mistake.database

import android.content.Context
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

internal class RoomStudentMistakeCutoverControlPort(
    private val database: StudentMistakeRoomDatabase,
) : StudentMistakeCutoverControlPort {
    private val dao = database.cutoverDao()

    override suspend fun readCutoverFence(): StudentMistakeAuthorityCutoverFence? =
        dao.readCutoverFence()?.toDomain()

    override suspend fun appendCutoverFenceIfAbsent(
        candidate: StudentMistakeAuthorityCutoverFence,
    ): StudentMistakeAuthorityCutoverFence {
        readCutoverFence()?.let { return it }
        check(candidate.hasValidFingerprint()) {
            "Student cutover fence fingerprint is invalid"
        }
        return dao.appendCutoverFenceIfAbsent(candidate.toEntity()).toDomain()
    }

    override suspend fun readCompletionReceipt():
        StudentMistakeAuthorityCutoverCompletionReceipt? =
        dao.readBoundCompletionReceipt()?.toDomain()

    override suspend fun appendCompletionReceiptIfAbsent(
        candidate: StudentMistakeAuthorityCutoverCompletionReceipt,
    ): StudentMistakeAuthorityCutoverCompletionReceipt {
        readCompletionReceipt()?.let { return it }
        check(candidate.hasValidFingerprint()) {
            "Student cutover completion receipt fingerprint is invalid"
        }
        return dao.appendCompletionReceiptIfAbsent(candidate.toEntity()).toDomain()
    }

    override suspend fun readPendingDestinationReattestations(
        migrationId: String,
    ): List<StudentMistakeDestinationReattestationChallenge> {
        migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
        val rows = dao.readMigrationLedgerRows(migrationId) ?: return emptyList()
        if (!rows.checkpoint.completed) return emptyList()
        if (rows.checkpoint.destinationLedgerVersion != LEGACY_DESTINATION_LEDGER_VERSION) {
            check(rows.destinationAttestationInvalidations.isEmpty()) {
                "Current destination ledger unexpectedly has legacy invalidations"
            }
            check(rows.destinationReattestationReceipts.isEmpty()) {
                "Current destination ledger unexpectedly has legacy reattestations"
            }
            return emptyList()
        }
        val challenges = rows.legacyReattestationChallenges()
        val receipts = rows.validatedReattestationReceipts(challenges)
        return challenges.filter { challenge ->
            (challenge.migrationId to challenge.revisionId) !in receipts
        }
    }

    override suspend fun appendDestinationReattestationReceipt(
        candidate: StudentMistakeDestinationReattestationReceipt,
    ): StudentMistakeDestinationReattestationReceipt {
        check(candidate.hasValidFingerprint()) {
            "Destination reattestation receipt fingerprint is invalid"
        }
        val rows =
            checkNotNull(dao.readMigrationLedgerRows(candidate.migrationId)) {
                "Destination reattestation requires its migration ledger"
            }
        check(rows.checkpoint.destinationLedgerVersion == LEGACY_DESTINATION_LEDGER_VERSION) {
            "Only a legacy destination ledger may be reattested"
        }
        check(rows.checkpoint.completed) {
            "Only a completed destination ledger may be reattested"
        }
        val challenge =
            checkNotNull(
                rows.legacyReattestationChallenges().singleOrNull { challenge ->
                    challenge.revisionId == candidate.revisionId
                },
            ) {
                "Destination reattestation has no matching invalidation challenge"
            }
        check(candidate.matches(challenge)) {
            "Destination reattestation changed its exact challenge"
        }
        return dao
            .appendDestinationReattestationReceiptIfAbsent(candidate.toEntity())
            .toDomain()
    }

    override suspend fun recomputeCompletedMigrationLedger(
        migrationId: String,
    ): StudentMistakeImmutableMigrationLedgerDigest? {
        migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
        val rows = dao.readMigrationLedgerRows(migrationId) ?: return null
        if (!rows.checkpoint.completed) {
            check(rows.receipts.none(StudentMistakeMigrationReceiptEntity::resultCompleted)) {
                "Incomplete student migration has a terminal page receipt"
            }
            return null
        }
        when (rows.checkpoint.destinationLedgerVersion) {
            0 -> {
                check(rows.destinationRecords.isEmpty()) {
                    "Unverified legacy migration unexpectedly has a destination ledger"
                }
                return null
            }
            1 -> return null
            LEGACY_DESTINATION_LEDGER_VERSION,
            STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION -> Unit
            else -> error("Student migration destination-ledger version is unsupported")
        }
        return rows.recomputeDigest()
    }

    override fun close() {
        database.close()
    }
}

internal object StudentMistakeCutoverControlPortFactory {
    fun open(
        context: Context,
        ownerKey: StudentMistakeOwnerKey,
    ): StudentMistakeCutoverControlPort {
        check(ownerKey === StudentMistakeOwnerKey.INSTANCE) {
            "Student-mistake cutover control requires the core:data owner key"
        }
        return RoomStudentMistakeCutoverControlPort(
            StudentMistakeOwnedDatabase.openDatabase(context, ownerKey),
        )
    }
}

private fun StudentMistakeAuthorityCutoverFence.toEntity():
    StudentMistakeCutoverFenceEntity =
    StudentMistakeCutoverFenceEntity(
        singletonKey = STUDENT_CUTOVER_SINGLETON_KEY,
        cutoverGeneration = cutoverGeneration,
        studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
        masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        fenceFingerprint = fenceFingerprint,
    )

private fun StudentMistakeCutoverFenceEntity.toDomain():
    StudentMistakeAuthorityCutoverFence {
    check(singletonKey == STUDENT_CUTOVER_SINGLETON_KEY) {
        "Student cutover fence has an invalid singleton key"
    }
    return StudentMistakeAuthorityCutoverFence(
        cutoverGeneration = cutoverGeneration,
        studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
        masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        fenceFingerprint = fenceFingerprint,
    ).also { fence ->
        check(fence.hasValidFingerprint()) {
            "Persisted student cutover fence fingerprint is invalid"
        }
    }
}

private fun StudentMistakeAuthorityCutoverCompletionReceipt.toEntity():
    StudentMistakeCutoverCompletionReceiptEntity =
    StudentMistakeCutoverCompletionReceiptEntity(
        singletonKey = STUDENT_CUTOVER_SINGLETON_KEY,
        cutoverGeneration = cutoverGeneration,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        authorityFenceFingerprint = authorityFenceFingerprint,
        receiptFingerprint = receiptFingerprint,
    )

private fun StudentMistakeCutoverCompletionReceiptEntity.toDomain():
    StudentMistakeAuthorityCutoverCompletionReceipt {
    check(singletonKey == STUDENT_CUTOVER_SINGLETON_KEY) {
        "Student cutover completion receipt has an invalid singleton key"
    }
    return StudentMistakeAuthorityCutoverCompletionReceipt(
        cutoverGeneration = cutoverGeneration,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        authorityFenceFingerprint = authorityFenceFingerprint,
        receiptFingerprint = receiptFingerprint,
    ).also { receipt ->
        check(receipt.hasValidFingerprint()) {
            "Persisted student cutover completion receipt fingerprint is invalid"
        }
    }
}

private fun StudentMistakeDestinationReattestationReceipt.toEntity():
    StudentMistakeDestinationReattestationReceiptEntity =
    StudentMistakeDestinationReattestationReceiptEntity(
        migrationId = migrationId,
        revisionId = revisionId,
        legacyDestinationRecordCanonicalFingerprint =
            legacyDestinationRecordCanonicalFingerprint,
        replacementDestinationRecordCanonicalFingerprint =
            replacementDestinationRecordCanonicalFingerprint,
        canonicalPolicyVersion = canonicalPolicyVersion,
        issuerKeyId = issuerKeyId,
        issuerVersion = issuerVersion,
        issuedAtEpochMillis = issuedAtEpochMillis,
        receiptCanonicalFingerprint = receiptCanonicalFingerprint,
    )

private fun StudentMistakeDestinationReattestationReceiptEntity.toDomain():
    StudentMistakeDestinationReattestationReceipt =
    StudentMistakeDestinationReattestationReceipt(
        migrationId = migrationId,
        revisionId = revisionId,
        legacyDestinationRecordCanonicalFingerprint =
            legacyDestinationRecordCanonicalFingerprint,
        replacementDestinationRecordCanonicalFingerprint =
            replacementDestinationRecordCanonicalFingerprint,
        canonicalPolicyVersion = canonicalPolicyVersion,
        issuerKeyId = issuerKeyId,
        issuerVersion = issuerVersion,
        issuedAtEpochMillis = issuedAtEpochMillis,
        receiptCanonicalFingerprint = receiptCanonicalFingerprint,
    ).also { receipt ->
        check(receipt.hasValidFingerprint()) {
            "Persisted destination reattestation receipt fingerprint is invalid"
        }
    }

private fun StudentMistakeMigrationLedgerRows.legacyReattestationChallenges():
    List<StudentMistakeDestinationReattestationChallenge> {
    check(checkpoint.destinationLedgerVersion == LEGACY_DESTINATION_LEDGER_VERSION) {
        "Only a v2 destination ledger can require v3 reattestation"
    }
    val snapshots = immutableSnapshotsByRevision()
    val invalidationsByRevision =
        destinationAttestationInvalidations.associateBy(
            StudentMistakeDestinationAttestationInvalidationEntity::revisionId,
        )
    check(invalidationsByRevision.size == destinationAttestationInvalidations.size) {
        "Destination attestation invalidation ledger repeats a revision"
    }
    val expectedInvalidatedRevisions =
        snapshots
            .filterValues { snapshot -> snapshot.errorAttributions.isNotEmpty() }
            .keys
    check(invalidationsByRevision.keys == expectedInvalidatedRevisions) {
        "Legacy destination invalidations do not match the confidence-bearing proof set"
    }
    return destinationRecords.mapNotNull { record ->
        val invalidation = invalidationsByRevision[record.revisionId] ?: return@mapNotNull null
        check(
            invalidation.migrationId == checkpoint.migrationId &&
                invalidation.revisionId == record.revisionId &&
                invalidation.legacyDestinationRecordCanonicalFingerprint ==
                record.destinationRecordCanonicalFingerprint &&
                invalidation.invalidationReason ==
                STUDENT_DESTINATION_REATTESTATION_REASON_CONFIDENCE_REMOVED &&
                invalidation.replacementPolicyVersion ==
                STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION &&
                invalidation.invalidatedAtSchemaVersion == STUDENT_MISTAKE_DATABASE_VERSION,
        ) {
            "Legacy destination invalidation changed its exact proof binding"
        }
        StudentMistakeDestinationReattestationChallenge(
            migrationId = record.migrationId,
            revisionId = record.revisionId,
            legacyDestinationRecordCanonicalFingerprint =
                record.destinationRecordCanonicalFingerprint,
            replacementDestinationRecordCanonicalFingerprint =
                checkNotNull(snapshots[record.revisionId]).canonicalFingerprint(),
            canonicalPolicyVersion = STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION,
        )
    }
}

private fun StudentMistakeMigrationLedgerRows.validatedReattestationReceipts(
    challenges: List<StudentMistakeDestinationReattestationChallenge>,
): Map<Pair<String, String>, StudentMistakeDestinationReattestationReceipt> {
    val challengesByKey = challenges.associateBy { it.migrationId to it.revisionId }
    check(challengesByKey.size == challenges.size) {
        "Destination reattestation challenge ledger repeats a revision"
    }
    val receipts =
        destinationReattestationReceipts.associate { entity ->
            val receipt = entity.toDomain()
            val key = receipt.migrationId to receipt.revisionId
            val challenge =
                checkNotNull(challengesByKey[key]) {
                    "Destination reattestation receipt has no invalidation"
                }
            check(receipt.matches(challenge)) {
                "Destination reattestation receipt changed its exact challenge"
            }
            key to receipt
        }
    check(receipts.size == destinationReattestationReceipts.size) {
        "Destination reattestation ledger repeats a revision"
    }
    return receipts
}

private fun StudentMistakeMigrationLedgerRows.immutableSnapshotsByRevision():
    Map<String, StudentMistakeImmutableRevisionSnapshot> {
    check(
        destinationRecords.map { it.migrationId to it.revisionId }.toSet().size ==
            destinationRecords.size,
    ) {
        "Student migration destination ledger repeats an imported revision"
    }
    val problemsById = problems.associateBy(StudentProblemDocumentEntity::problemId)
    check(problemsById.size == problems.size) {
        "Student migration destination contains duplicate problem rows"
    }
    val revisionsById = revisions.associateBy(StudentProblemRevisionEntity::revisionId)
    check(revisionsById.size == revisions.size) {
        "Student migration destination contains duplicate revision rows"
    }
    val importSnapshotsByRevision =
        importSnapshots.associateBy(StudentProblemImportSemanticSnapshotEntity::revisionId)
    check(
        importSnapshotsByRevision.size == importSnapshots.size &&
            importSnapshots.size == destinationRecords.size,
    ) {
        "Student migration destination does not have one immutable import snapshot per revision"
    }
    val imagesByRevision = images.groupBy(StudentProblemImageReferenceEntity::revisionId)
    val analysesByRevision =
        solutionAnalyses.groupBy(StudentProblemSolutionAnalysisEntity::basisRevisionId)
    check(analysesByRevision.values.all { it.size == 1 }) {
        "Student migration destination contains duplicate solution analyses"
    }
    val stepsByRevision =
        solutionSteps.groupBy(StudentProblemSolutionStepEntity::basisRevisionId)
    val attributionsByRevision =
        errorAttributions.groupBy(StudentProblemErrorAttributionEntity::basisRevisionId)
    val evidenceByRevision =
        errorEvidence.groupBy(StudentProblemErrorEvidenceEntity::basisRevisionId)
    return destinationRecords.associate { record ->
        val revision =
            checkNotNull(revisionsById[record.revisionId]) {
                "Imported student revision ${record.revisionId} is missing"
            }
        record.revisionId to
            StudentMistakeImmutableRevisionSnapshot(
                problem =
                    checkNotNull(problemsById[record.problemId]) {
                        "Imported student problem ${record.problemId} is missing"
                    },
                revision = revision,
                importSnapshot =
                    checkNotNull(importSnapshotsByRevision[record.revisionId]) {
                        "Imported student revision ${record.revisionId} has no import snapshot"
                    }.also { importSnapshot ->
                        check(
                            record.importSnapshotCanonicalFingerprint ==
                                importSnapshot.snapshotCanonicalFingerprint,
                        ) {
                            "Imported student revision changed its import snapshot"
                        }
                    },
                images = imagesByRevision[record.revisionId].orEmpty(),
                solutionAnalysis = analysesByRevision[record.revisionId]?.singleOrNull(),
                solutionSteps = stepsByRevision[record.revisionId].orEmpty(),
                errorAttributions = attributionsByRevision[record.revisionId].orEmpty(),
                errorEvidence = evidenceByRevision[record.revisionId].orEmpty(),
            )
    }
}

private fun StudentMistakeMigrationLedgerRows.recomputeDigest():
    StudentMistakeImmutableMigrationLedgerDigest? {
    val migrationId = checkpoint.migrationId
    migrationId.requireStoreText("Migration id", MAX_ID_CHARS)
    requireSha256(
        checkpoint.sourceDatabaseCanonicalFingerprint,
        "Migration source database fingerprint",
    )
    check(receipts.isNotEmpty()) {
        "Completed student migration has no page receipts"
    }

    val receiptFingerprints =
        receipts.mapTo(mutableSetOf()) { it.sourcePageCanonicalFingerprint }
    check(
        destinationRecords.all {
            it.migrationId == migrationId &&
                it.sourcePageCanonicalFingerprint in receiptFingerprints
        },
    ) {
        "Student migration destination record is not bound to a page receipt"
    }
    check(
        destinationRecords.map { it.migrationId to it.revisionId }.toSet().size ==
            destinationRecords.size,
    ) {
        "Student migration destination ledger repeats an imported revision"
    }
    val destinationRecordsByPage =
        destinationRecords.groupBy(
            StudentMistakeMigrationDestinationRecordEntity::
                sourcePageCanonicalFingerprint,
        )
    val snapshotsByRevision = immutableSnapshotsByRevision()
    val reattestationChallenges =
        when (checkpoint.destinationLedgerVersion) {
            LEGACY_DESTINATION_LEDGER_VERSION -> legacyReattestationChallenges()
            STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION -> {
                check(destinationAttestationInvalidations.isEmpty()) {
                    "Current destination ledger unexpectedly has legacy invalidations"
                }
                check(destinationReattestationReceipts.isEmpty()) {
                    "Current destination ledger unexpectedly has legacy reattestations"
                }
                emptyList()
            }
            else -> error("Unsupported destination-ledger version during recomputation")
        }
    val reattestationReceipts =
        if (checkpoint.destinationLedgerVersion == LEGACY_DESTINATION_LEDGER_VERSION) {
            validatedReattestationReceipts(reattestationChallenges)
        } else {
            emptyMap()
        }
    val hasPendingReattestation =
        reattestationChallenges.any { challenge ->
            (challenge.migrationId to challenge.revisionId) !in reattestationReceipts
        }

    val destinationDigest =
        CanonicalSha256(IMMUTABLE_MIGRATION_DESTINATION_FINGERPRINT_V3_DOMAIN)
            .field("migrationId", migrationId)
            .field(
                "sourceDatabaseCanonicalFingerprint",
                checkpoint.sourceDatabaseCanonicalFingerprint,
            )
            .field("destinationRecordCount", destinationRecords.size)
    var previousTotal = 0L
    var previousKey: StudentMistakeMigrationKey? = null
    var terminalReceipt: StudentMistakeMigrationReceiptEntity? = null
    var destinationRecordIndex = 0

    receipts.forEach { receipt ->
        check(terminalReceipt == null) {
            "Student migration ledger contains a page after its terminal receipt"
        }
        receipt.requireCanonicalShape(migrationId)
        val resultKey = receipt.resultKey()
        check(
            receipt.resultTotalRecordCount ==
                previousTotal + receipt.importedRecordCount,
        ) {
            "Student migration receipt count chain is discontinuous"
        }
        val expectedCheckpointFingerprint =
            migrationCheckpointFingerprint(
                migrationId = migrationId,
                sourceDatabaseCanonicalFingerprint =
                    checkpoint.sourceDatabaseCanonicalFingerprint,
                sourcePageCanonicalFingerprint =
                    receipt.sourcePageCanonicalFingerprint,
                lastKey = resultKey,
                importedRecordCount = receipt.resultTotalRecordCount,
                completed = receipt.resultCompleted,
            )
        check(
            receipt.checkpointCanonicalFingerprint ==
                expectedCheckpointFingerprint,
        ) {
            "Student migration receipt checkpoint fingerprint is invalid"
        }
        val expectedReceiptFingerprint =
            CanonicalSha256(MIGRATION_RECEIPT_FINGERPRINT_DOMAIN)
                .field("migrationId", migrationId)
                .field(
                    "sourcePageCanonicalFingerprint",
                    receipt.sourcePageCanonicalFingerprint,
                )
                .field("importedRecordCount", receipt.importedRecordCount)
                .field(
                    "checkpointCanonicalFingerprint",
                    receipt.checkpointCanonicalFingerprint,
                )
                .finish()
        check(receipt.receiptCanonicalFingerprint == expectedReceiptFingerprint) {
            "Student migration page receipt fingerprint is invalid"
        }

        val pageRecords =
            destinationRecordsByPage[receipt.sourcePageCanonicalFingerprint]
                .orEmpty()
                .sortedBy(StudentMistakeMigrationDestinationRecordEntity::pageRecordOrdinal)
        check(pageRecords.size == receipt.importedRecordCount) {
            "Student migration page receipt count does not match destination records"
        }
        check(pageRecords.map { it.pageRecordOrdinal } == pageRecords.indices.toList()) {
            "Student migration destination page ordinals are not contiguous"
        }
        pageRecords.forEach { record ->
            requireSha256(
                record.destinationRecordCanonicalFingerprint,
                "Migration destination-record fingerprint",
            )
            val recordKey = record.migrationKey()
            val precedingKey = previousKey
            check(precedingKey == null || recordKey > precedingKey) {
                "Student migration destination keys are not strictly increasing"
            }
            val snapshot =
                checkNotNull(snapshotsByRevision[record.revisionId]) {
                    "Imported student revision ${record.revisionId} is missing"
                }
            val revision = snapshot.revision
            check(
                revision.createdAtEpochMillis == record.committedAtEpochMillis &&
                    revision.problemId == record.problemId &&
                    revision.revisionNumber == record.revisionNumber,
            ) {
                "Imported student revision key changed after migration"
            }
            val actualFingerprint = snapshot.canonicalFingerprint()
            when (checkpoint.destinationLedgerVersion) {
                LEGACY_DESTINATION_LEDGER_VERSION -> {
                    if (snapshot.errorAttributions.isEmpty()) {
                        check(
                            snapshot.canonicalFingerprintV2WithoutAttributions() ==
                                record.destinationRecordCanonicalFingerprint,
                        ) {
                            "Unchanged legacy student destination no longer matches its proof"
                        }
                    } else {
                        val challengeKey = record.migrationId to record.revisionId
                        check(
                            reattestationChallenges.any { challenge ->
                                (challenge.migrationId to challenge.revisionId) == challengeKey
                            },
                        ) {
                            "Confidence-bearing legacy destination has no invalidation"
                        }
                    }
                }
                STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION ->
                    check(
                        actualFingerprint == record.destinationRecordCanonicalFingerprint,
                    ) {
                        "Imported student revision content changed after migration"
                    }
            }
            destinationDigest
                .field(
                    "record[$destinationRecordIndex].sourcePageCanonicalFingerprint",
                    record.sourcePageCanonicalFingerprint,
                )
                .field(
                    "record[$destinationRecordIndex].pageRecordOrdinal",
                    record.pageRecordOrdinal,
                )
                .field(
                    "record[$destinationRecordIndex].committedAtEpochMillis",
                    record.committedAtEpochMillis,
                )
                .field(
                    "record[$destinationRecordIndex].problemId",
                    record.problemId,
                )
                .field(
                    "record[$destinationRecordIndex].revisionNumber",
                    record.revisionNumber,
                )
                .field(
                    "record[$destinationRecordIndex].revisionId",
                    record.revisionId,
                )
                .field(
                    "record[$destinationRecordIndex].actualCanonicalFingerprint",
                    actualFingerprint,
                )
            destinationRecordIndex += 1
            previousKey = recordKey
        }
        check(resultKey == previousKey) {
            "Student migration page destination does not reach its receipt checkpoint"
        }
        if (receipt.importedRecordCount == 0) {
            check(receipt.resultCompleted) {
                "Only a terminal student migration page may be empty"
            }
        }

        previousTotal = receipt.resultTotalRecordCount
        if (receipt.resultCompleted) terminalReceipt = receipt
    }

    val terminal =
        checkNotNull(terminalReceipt) {
            "Completed student migration has no terminal page receipt"
        }
    val checkpointKey = checkpoint.resultKey()
    check(
        checkpoint.importedRecordCount == previousTotal &&
            destinationRecordIndex.toLong() == previousTotal &&
            checkpointKey == previousKey &&
            checkpoint.checkpointCanonicalFingerprint ==
            terminal.checkpointCanonicalFingerprint &&
            checkpoint.updatedAtEpochMillis == terminal.appliedAtEpochMillis,
    ) {
        "Student migration terminal checkpoint does not match its receipt chain"
    }
    requireSha256(
        checkpoint.checkpointCanonicalFingerprint,
        "Migration checkpoint fingerprint",
    )
    if (hasPendingReattestation) return null

    return StudentMistakeImmutableMigrationLedgerDigest(
        migrationId = migrationId,
        sourceDatabaseCanonicalFingerprint =
            checkpoint.sourceDatabaseCanonicalFingerprint,
        migratedRecordCount = previousTotal,
        checkpointCanonicalFingerprint =
            checkpoint.checkpointCanonicalFingerprint,
        terminalSourcePageCanonicalFingerprint =
            terminal.sourcePageCanonicalFingerprint,
        pageReceiptCount = receipts.size,
        destinationCanonicalFingerprint =
            destinationDigest
                .finish(),
        destinationLedgerVersion = STUDENT_MIGRATION_DESTINATION_LEDGER_VERSION,
        immutableImportSnapshotCount = importSnapshots.size.toLong(),
        legacySemanticSnapshotCount =
            importSnapshots.count(
                StudentProblemImportSemanticSnapshotEntity::legacySemanticsPresent,
            ).toLong(),
    )
}

private fun StudentMistakeMigrationReceiptEntity.requireCanonicalShape(
    expectedMigrationId: String,
) {
    check(migrationId == expectedMigrationId) {
        "Student migration receipt belongs to another migration"
    }
    requireSha256(sourcePageCanonicalFingerprint, "Migration source-page fingerprint")
    check(importedRecordCount >= 0) {
        "Student migration receipt count must not be negative"
    }
    check(resultTotalRecordCount >= 0L) {
        "Student migration total count must not be negative"
    }
    requireSha256(checkpointCanonicalFingerprint, "Migration checkpoint fingerprint")
    requireSha256(receiptCanonicalFingerprint, "Migration receipt fingerprint")
    check(appliedAtEpochMillis >= 0L) {
        "Student migration receipt time must not be negative"
    }
}

private fun StudentMistakeMigrationReceiptEntity.resultKey():
    StudentMistakeMigrationKey? =
    migrationResultKey(
        committedAtEpochMillis = resultLastCommittedAtEpochMillis,
        problemId = resultLastProblemId,
        revisionNumber = resultLastRevisionNumber,
        revisionId = resultLastRevisionId,
    )

private fun StudentMistakeMigrationCheckpointEntity.resultKey():
    StudentMistakeMigrationKey? =
    migrationResultKey(
        committedAtEpochMillis = lastCommittedAtEpochMillis,
        problemId = lastProblemId,
        revisionNumber = lastRevisionNumber,
        revisionId = lastRevisionId,
    )

private fun migrationResultKey(
    committedAtEpochMillis: Long?,
    problemId: String?,
    revisionNumber: Int?,
    revisionId: String?,
): StudentMistakeMigrationKey? {
    val allMissing =
        committedAtEpochMillis == null &&
            problemId == null &&
            revisionNumber == null &&
            revisionId == null
    if (allMissing) return null
    check(
        committedAtEpochMillis != null &&
            problemId != null &&
            revisionNumber != null &&
            revisionId != null,
    ) {
        "Student migration ledger contains a partial key"
    }
    return StudentMistakeMigrationKey(
        committedAtEpochMillis = committedAtEpochMillis,
        problemId = problemId,
        revisionNumber = revisionNumber,
        revisionId = revisionId,
    )
}

private fun migrationCheckpointFingerprint(
    migrationId: String,
    sourceDatabaseCanonicalFingerprint: String,
    sourcePageCanonicalFingerprint: String,
    lastKey: StudentMistakeMigrationKey?,
    importedRecordCount: Long,
    completed: Boolean,
): String =
    CanonicalSha256(MIGRATION_CHECKPOINT_FINGERPRINT_DOMAIN)
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

private fun StudentMistakeMigrationDestinationRecordEntity.migrationKey():
    StudentMistakeMigrationKey =
    StudentMistakeMigrationKey(
        committedAtEpochMillis = committedAtEpochMillis,
        problemId = problemId,
        revisionNumber = revisionNumber,
        revisionId = revisionId,
    )

private const val LEGACY_DESTINATION_LEDGER_VERSION = 2
private const val IMMUTABLE_MIGRATION_DESTINATION_FINGERPRINT_V3_DOMAIN =
    "student-mistake-immutable-migration-destination-v3"
private const val MIGRATION_CHECKPOINT_FINGERPRINT_DOMAIN =
    "student-mistake-migration-checkpoint-v1"
private const val MIGRATION_RECEIPT_FINGERPRINT_DOMAIN =
    "student-mistake-migration-receipt-v1"
