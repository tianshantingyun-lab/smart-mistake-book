package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationCursor
import com.tingyun.smartmistakebook.core.database.MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.student.mistake.database.ApplyStudentMistakeMigrationPageCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeAuthorityCutoverCompletionReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeAuthorityCutoverFence
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeCutoverControlPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeImmutableMigrationLedgerDigest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationCheckpoint
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationKey
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationReceipt

/**
 * Core:data's protocol adapter for the independent student-mistake authority.
 *
 * The adapter never caches import evidence. Every proof reads a stable exact legacy manifest,
 * independently checks its terminal page chain, and asks the target to re-read its immutable
 * destination ledger.
 */
internal class StudentAuthorityCutoverControlAdapter(
    private val target: StudentMistakeCutoverControlPort,
    private val legacySource: LegacyAuthorityMigrationSourcePort,
    learnerId: String,
    private val legacyPrefixReceiptFingerprint: String,
    private val cutoverGeneration: Long,
    private val pageSize: Int = MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE,
) : StudentAuthorityCutoverControlPort {
    private val manifestReader =
        ExactLegacyAuthorityManifestReader(
            source = legacySource,
            learnerId = learnerId,
            pageSize = pageSize,
        )

    init {
        require(cutoverGeneration > 0L) {
            "Student cutover generation must be positive"
        }
        require(legacyPrefixReceiptFingerprint.isLowercaseSha256()) {
            "Legacy prefix receipt fingerprint must be lowercase SHA-256"
        }
        require(pageSize in 1..MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE) {
            "Student terminal migration page size is outside the bounded source contract"
        }
    }

    override suspend fun readCutoverFence(): AuthorityCutoverFence? =
        target.readCutoverFence()?.toCoreDataFence()

    override suspend fun appendCutoverFenceIfAbsent(
        candidate: AuthorityCutoverFence,
    ): AuthorityCutoverFence {
        val mapped = candidate.toStudentFence()
        return target.appendCutoverFenceIfAbsent(mapped).toCoreDataFence()
    }

    override suspend fun readCompletionReceipt(): AuthorityCutoverCompletionReceipt? =
        target.readCompletionReceipt()?.toCoreDataReceipt()

    override suspend fun appendCompletionReceiptIfAbsent(
        candidate: AuthorityCutoverCompletionReceipt,
    ): AuthorityCutoverCompletionReceipt {
        val mapped = candidate.toStudentReceipt()
        return target.appendCompletionReceiptIfAbsent(mapped).toCoreDataReceipt()
    }

    override suspend fun reverifyImmutableImportEvidence():
        ImmutableAuthorityImportEvidence? {
        val sourceExpectation =
            readStableStudentSourceExpectation(
                source = legacySource,
                manifestReader = manifestReader,
                pageSize = pageSize,
            )
        val manifest = sourceExpectation.manifest
        val migrationId = terminalStudentMigrationId(manifest.sourceCanonicalFingerprint)
        if (target.readPendingDestinationReattestations(migrationId).isNotEmpty()) {
            return null
        }
        val ledger =
            target.recomputeCompletedMigrationLedger(migrationId)
                ?: return null
        if (!ledger.matches(sourceExpectation, migrationId)) return null

        return ImmutableAuthorityImportEvidence.create(
            authority = FencedAuthority.STUDENT_MISTAKES,
            cutoverGeneration = cutoverGeneration,
            migratedRecordCount = manifest.recordCount,
            sourceCheckpoint = manifest.sourceCheckpoint,
            legacyPrefixReceiptFingerprint = legacyPrefixReceiptFingerprint,
            sourceFingerprint = manifest.sourceCanonicalFingerprint,
            destinationFingerprint = ledger.destinationCanonicalFingerprint,
        ).also { evidence ->
            check(evidence.hasValidFingerprint()) {
                "Student immutable import evidence fingerprint is invalid"
            }
            check(
                evidence.legacyPrefixReceiptFingerprint ==
                    legacyPrefixReceiptFingerprint,
            ) {
                "Student immutable import evidence changed the legacy prefix receipt"
            }
        }
    }

    override fun close() {
        target.close()
    }
}

/** Read-only startup fence probe used before a durable prefix/generation exists. */
internal fun StudentMistakeCutoverControlPort.asStudentLegacyFenceInspectionPort():
    StudentAuthorityCutoverControlPort =
    object : StudentAuthorityCutoverControlPort {
        override suspend fun readCutoverFence(): AuthorityCutoverFence? =
            this@asStudentLegacyFenceInspectionPort.readCutoverFence()?.toCoreDataFence()

        override suspend fun readCompletionReceipt(): AuthorityCutoverCompletionReceipt? =
            this@asStudentLegacyFenceInspectionPort.readCompletionReceipt()?.toCoreDataReceipt()

        override suspend fun appendCutoverFenceIfAbsent(
            candidate: AuthorityCutoverFence,
        ): AuthorityCutoverFence =
            throw SecurityException("Fence inspection cannot append a student cutover fence")

        override suspend fun appendCompletionReceiptIfAbsent(
            candidate: AuthorityCutoverCompletionReceipt,
        ): AuthorityCutoverCompletionReceipt =
            throw SecurityException("Fence inspection cannot append a student completion receipt")

        override suspend fun reverifyImmutableImportEvidence():
            ImmutableAuthorityImportEvidence? =
            throw SecurityException("Fence inspection cannot issue student import evidence")

        override fun close() = this@asStudentLegacyFenceInspectionPort.close()
    }

/**
 * Performs the one terminal student-document import while the caller holds the process-wide
 * legacy-writer exclusion.
 *
 * No source handle is retained beyond this object, and no cross-database query is used. Recovery
 * starts from the target's durable checkpoint but replays the source keyset from the beginning so
 * the checkpoint must land on an exact page boundary.
 */
internal class TerminalStudentAuthorityMigrator(
    private val legacySource: LegacyAuthorityMigrationSourcePort,
    private val targetMigration: StudentMistakeMigrationPort,
    private val targetCutover: StudentMistakeCutoverControlPort,
    learnerId: String,
    private val documentMapper: LegacyStudentDocumentMapper,
    private val cutoverGeneration: Long,
    private val pageSize: Int = MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val manifestReader =
        ExactLegacyAuthorityManifestReader(
            source = legacySource,
            learnerId = learnerId,
            pageSize = pageSize,
        )

    init {
        require(cutoverGeneration > 0L) {
            "Student cutover generation must be positive"
        }
        require(pageSize in 1..MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE) {
            "Student terminal migration page size is outside the bounded source contract"
        }
    }

    suspend fun migrate(): TerminalStudentAuthorityMigrationResult {
        val initialManifest = manifestReader.readStableManifests().studentDocuments
        val migrationId =
            terminalStudentMigrationId(initialManifest.sourceCanonicalFingerprint)
        var checkpoint = targetMigration.readCheckpoint(migrationId)
        checkpoint?.requireCompatibleWith(
            migrationId = migrationId,
            manifest = initialManifest,
        )

        var sourceCursor: LegacyStudentDocumentMigrationCursor? = null
        var scannedRecordCount = 0L
        var pageReceiptCount = 0
        var terminalSourcePageFingerprint: String? = null

        while (true) {
            val page =
                legacySource.readLegacyStudentDocumentMigrationPage(
                    afterExclusive = sourceCursor,
                    limit = pageSize,
                )
            page.requireStableShape(pageSize)
            val sourcePageFingerprint =
                studentPageFingerprint(
                    afterExclusive = sourceCursor,
                    records = page.records,
                )
            val pageEndCount = scannedRecordCount + page.records.size
            val pageEndKey = page.records.lastOrNull()?.cursor?.toMigrationKey()
            val pageWasApplied =
                checkpoint?.let { durable ->
                    when {
                        durable.completed -> {
                            check(pageEndCount <= durable.importedRecordCount) {
                                "Completed student checkpoint ends inside a source page"
                            }
                            true
                        }

                        page.records.isEmpty() -> false
                        pageEndCount <= durable.importedRecordCount -> true
                        else -> false
                    }
                } ?: false

            if (pageWasApplied) {
                val durable = checkNotNull(checkpoint)
                if (pageEndCount == durable.importedRecordCount) {
                    check(durable.lastKey == pageEndKey) {
                        "Student migration checkpoint does not match its source page boundary"
                    }
                    if (durable.completed) {
                        check(!page.hasMore) {
                            "Completed student checkpoint precedes more source records"
                        }
                    }
                }
            } else {
                val durableCount = checkpoint?.importedRecordCount ?: 0L
                check(scannedRecordCount == durableCount) {
                    "Student migration checkpoint ends inside a source page"
                }
                check(checkpoint?.completed != true) {
                    "Completed student migration cannot accept another page"
                }
                val mapped = page.records.map(documentMapper::map)
                val command =
                    ApplyStudentMistakeMigrationPageCommand(
                        migrationId = migrationId,
                        sourceDatabaseCanonicalFingerprint =
                            initialManifest.sourceCanonicalFingerprint,
                        sourcePageCanonicalFingerprint = sourcePageFingerprint,
                        expectedCheckpointCanonicalFingerprint =
                            checkpoint?.checkpointCanonicalFingerprint,
                        afterExclusive = checkpoint?.lastKey,
                        records = mapped,
                        isLastPage = !page.hasMore,
                        appliedAtEpochMillis = clock().coerceAtLeast(0L),
                    )
                val receipt = targetMigration.applyPage(command)
                receipt.requireExactResult(command)
                val persisted = targetMigration.readCheckpoint(migrationId)
                check(persisted == receipt.checkpoint) {
                    "Student migration target did not persist its returned checkpoint"
                }
                checkpoint = persisted
            }

            pageReceiptCount += 1
            terminalSourcePageFingerprint = sourcePageFingerprint
            scannedRecordCount = pageEndCount
            sourceCursor = page.records.lastOrNull()?.cursor ?: sourceCursor
            if (!page.hasMore) break
        }

        check(scannedRecordCount == initialManifest.recordCount) {
            "Terminal student migration did not cover the exact source record count"
        }
        check(sourceCursor == initialManifest.terminalCursor) {
            "Terminal student migration did not reach the exact source boundary"
        }
        val terminalFingerprint = checkNotNull(terminalSourcePageFingerprint)
        val terminalCheckpoint =
            checkNotNull(checkpoint) {
                "Terminal student migration did not persist a checkpoint"
            }
        terminalCheckpoint.requireTerminalMatch(
            migrationId = migrationId,
            manifest = initialManifest,
            terminalSourcePageFingerprint = terminalFingerprint,
        )

        val finalManifest = manifestReader.readStableManifests().studentDocuments
        check(finalManifest == initialManifest) {
            "Legacy student source changed during terminal migration"
        }
        val ledger =
            checkNotNull(targetCutover.recomputeCompletedMigrationLedger(migrationId)) {
                "Student target did not produce a verified terminal migration ledger"
            }
        val expectation =
            StudentSourceLedgerExpectation(
                manifest = finalManifest,
                pageReceiptCount = pageReceiptCount,
                terminalSourcePageFingerprint = terminalFingerprint,
            )
        check(ledger.matches(expectation, migrationId)) {
            "Student target terminal migration ledger does not match the exact source"
        }
        return TerminalStudentAuthorityMigrationResult(
            cutoverGeneration = cutoverGeneration,
            migrationId = migrationId,
            manifest = finalManifest,
            ledgerDigest = ledger,
        )
    }
}

internal data class TerminalStudentAuthorityMigrationResult(
    val cutoverGeneration: Long,
    val migrationId: String,
    val manifest: ExactLegacyStudentDocumentManifest,
    val ledgerDigest: StudentMistakeImmutableMigrationLedgerDigest,
) {
    init {
        require(cutoverGeneration > 0L)
        require(migrationId == terminalStudentMigrationId(manifest.sourceCanonicalFingerprint))
        require(ledgerDigest.migrationId == migrationId)
    }
}

private data class StudentSourceLedgerExpectation(
    val manifest: ExactLegacyStudentDocumentManifest,
    val pageReceiptCount: Int,
    val terminalSourcePageFingerprint: String,
) {
    init {
        require(pageReceiptCount > 0)
        require(terminalSourcePageFingerprint.isLowercaseSha256())
    }
}

private suspend fun readStableStudentSourceExpectation(
    source: LegacyAuthorityMigrationSourcePort,
    manifestReader: ExactLegacyAuthorityManifestReader,
    pageSize: Int,
): StudentSourceLedgerExpectation {
    val before = manifestReader.readStableManifests().studentDocuments
    var cursor: LegacyStudentDocumentMigrationCursor? = null
    var recordCount = 0L
    var pageReceiptCount = 0
    var terminalSourcePageFingerprint: String? = null
    while (true) {
        val page =
            source.readLegacyStudentDocumentMigrationPage(
                afterExclusive = cursor,
                limit = pageSize,
            )
        page.requireStableShape(pageSize)
        terminalSourcePageFingerprint =
            studentPageFingerprint(
                afterExclusive = cursor,
                records = page.records,
            )
        pageReceiptCount += 1
        recordCount += page.records.size
        cursor = page.records.lastOrNull()?.cursor ?: cursor
        if (!page.hasMore) break
    }
    check(recordCount == before.recordCount && cursor == before.terminalCursor) {
        "Legacy student page ledger does not cover its exact manifest"
    }
    val after = manifestReader.readStableManifests().studentDocuments
    check(after == before) {
        "Legacy student source changed while its terminal page ledger was read"
    }
    return StudentSourceLedgerExpectation(
        manifest = after,
        pageReceiptCount = pageReceiptCount,
        terminalSourcePageFingerprint =
            checkNotNull(terminalSourcePageFingerprint),
    )
}

private fun com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationPage
    .requireStableShape(requestedPageSize: Int) {
    check(records.size <= requestedPageSize) {
        "Legacy student source exceeded the requested page size"
    }
    check(!hasMore || records.size == requestedPageSize) {
        "Legacy student source returned an unstable short non-terminal page"
    }
}

private fun StudentMistakeImmutableMigrationLedgerDigest.matches(
    expectation: StudentSourceLedgerExpectation,
    expectedMigrationId: String,
): Boolean {
    val manifest = expectation.manifest
    val expectedCheckpoint =
        studentTerminalCheckpointFingerprint(
            migrationId = expectedMigrationId,
            sourceDatabaseCanonicalFingerprint = manifest.sourceCanonicalFingerprint,
            sourcePageCanonicalFingerprint =
                expectation.terminalSourcePageFingerprint,
            lastKey = manifest.terminalCursor?.toMigrationKey(),
            importedRecordCount = manifest.recordCount,
        )
    return migrationId == expectedMigrationId &&
        sourceDatabaseCanonicalFingerprint == manifest.sourceCanonicalFingerprint &&
        migratedRecordCount == manifest.recordCount &&
        destinationLedgerVersion == COMPLETE_STUDENT_DESTINATION_LEDGER_VERSION &&
        immutableImportSnapshotCount == manifest.recordCount &&
        legacySemanticSnapshotCount == manifest.recordCount &&
        checkpointCanonicalFingerprint == expectedCheckpoint &&
        terminalSourcePageCanonicalFingerprint ==
            expectation.terminalSourcePageFingerprint &&
        pageReceiptCount == expectation.pageReceiptCount
}

private fun StudentMistakeMigrationCheckpoint.requireCompatibleWith(
    migrationId: String,
    manifest: ExactLegacyStudentDocumentManifest,
) {
    check(this.migrationId == migrationId) {
        "Student checkpoint belongs to another migration"
    }
    check(sourceDatabaseCanonicalFingerprint == manifest.sourceCanonicalFingerprint) {
        "Student checkpoint belongs to another exact source"
    }
    check(importedRecordCount <= manifest.recordCount) {
        "Student checkpoint exceeds the exact source record count"
    }
    if (importedRecordCount == 0L) {
        check(lastKey == null) {
            "Empty student checkpoint contains a migration key"
        }
    } else {
        check(lastKey != null) {
            "Non-empty student checkpoint has no migration key"
        }
    }
}

private fun StudentMistakeMigrationCheckpoint.requireTerminalMatch(
    migrationId: String,
    manifest: ExactLegacyStudentDocumentManifest,
    terminalSourcePageFingerprint: String,
) {
    requireCompatibleWith(migrationId, manifest)
    check(completed) {
        "Student migration checkpoint is not terminal"
    }
    check(importedRecordCount == manifest.recordCount) {
        "Student terminal checkpoint record count is incomplete"
    }
    check(lastKey == manifest.terminalCursor?.toMigrationKey()) {
        "Student terminal checkpoint does not reach the exact source cursor"
    }
    check(
        checkpointCanonicalFingerprint ==
            studentTerminalCheckpointFingerprint(
                migrationId = migrationId,
                sourceDatabaseCanonicalFingerprint =
                    manifest.sourceCanonicalFingerprint,
                sourcePageCanonicalFingerprint = terminalSourcePageFingerprint,
                lastKey = lastKey,
                importedRecordCount = importedRecordCount,
            ),
    ) {
        "Student terminal checkpoint fingerprint is invalid"
    }
}

private fun StudentMistakeMigrationReceipt.requireExactResult(
    command: ApplyStudentMistakeMigrationPageCommand,
) {
    check(migrationId == command.migrationId)
    check(sourcePageCanonicalFingerprint == command.sourcePageCanonicalFingerprint)
    check(importedRecordCount == command.records.size)
    check(checkpoint.sourceDatabaseCanonicalFingerprint ==
        command.sourceDatabaseCanonicalFingerprint)
    check(checkpoint.lastKey ==
        (command.records.lastOrNull()?.key ?: command.afterExclusive))
    check(checkpoint.completed == command.isLastPage)
    check(
        receiptCanonicalFingerprint ==
            CanonicalSha256(STUDENT_MIGRATION_RECEIPT_DOMAIN)
                .field("migrationId", migrationId)
                .field(
                    "sourcePageCanonicalFingerprint",
                    sourcePageCanonicalFingerprint,
                )
                .field("importedRecordCount", importedRecordCount)
                .field(
                    "checkpointCanonicalFingerprint",
                    checkpoint.checkpointCanonicalFingerprint,
                )
                .finish(),
    ) {
        "Student migration receipt fingerprint is invalid"
    }
}

private fun AuthorityCutoverFence.toStudentFence():
    StudentMistakeAuthorityCutoverFence {
    check(authority == FencedAuthority.STUDENT_MISTAKES) {
        "Student cutover adapter rejected a foreign authority fence"
    }
    check(hasValidFingerprint()) {
        "Student cutover fence fingerprint is invalid"
    }
    return StudentMistakeAuthorityCutoverFence(
        cutoverGeneration = cutoverGeneration,
        studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
        masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        fenceFingerprint = fenceFingerprint,
    ).also { mapped ->
        check(mapped.hasValidFingerprint())
        check(mapped.toCoreDataFence() == this)
    }
}

private fun StudentMistakeAuthorityCutoverFence.toCoreDataFence():
    AuthorityCutoverFence {
    check(hasValidFingerprint()) {
        "Persisted student cutover fence fingerprint is invalid"
    }
    return AuthorityCutoverFence(
        authority = FencedAuthority.STUDENT_MISTAKES,
        cutoverGeneration = cutoverGeneration,
        studentImportEvidenceFingerprint = studentImportEvidenceFingerprint,
        masteryImportEvidenceFingerprint = masteryImportEvidenceFingerprint,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        fenceFingerprint = fenceFingerprint,
    ).also { mapped ->
        check(mapped.hasValidFingerprint())
        check(mapped.cutoverGeneration == cutoverGeneration)
        check(mapped.cutoverIntentFingerprint == cutoverIntentFingerprint)
        check(mapped.fenceFingerprint == fenceFingerprint)
    }
}

private fun AuthorityCutoverCompletionReceipt.toStudentReceipt():
    StudentMistakeAuthorityCutoverCompletionReceipt {
    check(authority == FencedAuthority.STUDENT_MISTAKES) {
        "Student cutover adapter rejected a foreign authority receipt"
    }
    check(hasValidFingerprint()) {
        "Student cutover completion receipt fingerprint is invalid"
    }
    return StudentMistakeAuthorityCutoverCompletionReceipt(
        cutoverGeneration = cutoverGeneration,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        authorityFenceFingerprint = authorityFenceFingerprint,
        receiptFingerprint = receiptFingerprint,
    ).also { mapped ->
        check(mapped.hasValidFingerprint())
        check(mapped.toCoreDataReceipt() == this)
    }
}

private fun StudentMistakeAuthorityCutoverCompletionReceipt.toCoreDataReceipt():
    AuthorityCutoverCompletionReceipt {
    check(hasValidFingerprint()) {
        "Persisted student cutover completion receipt fingerprint is invalid"
    }
    return AuthorityCutoverCompletionReceipt(
        authority = FencedAuthority.STUDENT_MISTAKES,
        cutoverGeneration = cutoverGeneration,
        cutoverIntentFingerprint = cutoverIntentFingerprint,
        authorityFenceFingerprint = authorityFenceFingerprint,
        receiptFingerprint = receiptFingerprint,
    ).also { mapped ->
        check(mapped.hasValidFingerprint())
        check(mapped.cutoverGeneration == cutoverGeneration)
        check(mapped.cutoverIntentFingerprint == cutoverIntentFingerprint)
        check(mapped.authorityFenceFingerprint == authorityFenceFingerprint)
        check(mapped.receiptFingerprint == receiptFingerprint)
    }
}

private fun LegacyStudentDocumentMigrationCursor.toMigrationKey():
    StudentMistakeMigrationKey =
    StudentMistakeMigrationKey(
        committedAtEpochMillis = committedAtEpochMillis,
        problemId = problemId,
        revisionNumber = revisionNumber,
        revisionId = revisionId,
    )

internal fun terminalStudentMigrationId(
    sourceCanonicalFingerprint: String,
): String {
    require(sourceCanonicalFingerprint.isLowercaseSha256()) {
        "Student terminal source fingerprint must be lowercase SHA-256"
    }
    return "student-terminal-" +
        CanonicalSha256(STUDENT_TERMINAL_MIGRATION_ID_DOMAIN)
            .field("sourceCanonicalFingerprint", sourceCanonicalFingerprint)
            .finish()
}

private fun studentTerminalCheckpointFingerprint(
    migrationId: String,
    sourceDatabaseCanonicalFingerprint: String,
    sourcePageCanonicalFingerprint: String,
    lastKey: StudentMistakeMigrationKey?,
    importedRecordCount: Long,
): String =
    CanonicalSha256(STUDENT_MIGRATION_CHECKPOINT_DOMAIN)
        .field("migrationId", migrationId)
        .field(
            "sourceDatabaseCanonicalFingerprint",
            sourceDatabaseCanonicalFingerprint,
        )
        .field("sourcePageCanonicalFingerprint", sourcePageCanonicalFingerprint)
        .nullableField(
            "lastCommittedAtEpochMillis",
            lastKey?.committedAtEpochMillis?.toString(),
        )
        .nullableField("lastProblemId", lastKey?.problemId)
        .nullableField("lastRevisionNumber", lastKey?.revisionNumber?.toString())
        .nullableField("lastRevisionId", lastKey?.revisionId)
        .field("importedRecordCount", importedRecordCount)
        .field("completed", true)
        .finish()

private fun String.isLowercaseSha256(): Boolean =
    LOWERCASE_SHA_256.matches(this)

private const val STUDENT_TERMINAL_MIGRATION_ID_DOMAIN =
    "terminal-student-mistake-migration-id-v1"
private const val STUDENT_MIGRATION_CHECKPOINT_DOMAIN =
    "student-mistake-migration-checkpoint-v1"
private const val STUDENT_MIGRATION_RECEIPT_DOMAIN =
    "student-mistake-migration-receipt-v1"
private const val COMPLETE_STUDENT_DESTINATION_LEDGER_VERSION = 3
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
