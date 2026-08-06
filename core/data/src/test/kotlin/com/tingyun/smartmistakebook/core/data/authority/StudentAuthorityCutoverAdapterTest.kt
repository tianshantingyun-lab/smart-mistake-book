package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSnapshot
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationPage
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationPage
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationRecord
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.student.mistake.database.ApplyStudentMistakeMigrationPageCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeAuthorityCutoverCompletionReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeAuthorityCutoverFence
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeCutoverControlPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeDestinationReattestationChallenge
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeImmutableMigrationLedgerDigest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationCheckpoint
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationKey
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeMigrationReceipt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentAuthorityCutoverAdapterTest {
    @Test
    fun emptySourceWritesOneTerminalPageAndCanBeReverified() =
        runBlocking {
            val source = StudentCutoverFakeLegacySource()
            val target = FakeStudentTarget()
            val result =
                migrator(source, target, pageSize = 2).migrate()

            assertEquals(0L, result.manifest.recordCount)
            assertEquals(1, target.commands.size)
            assertTrue(target.commands.single().records.isEmpty())
            assertTrue(target.commands.single().isLastPage)
            assertEquals(1, result.ledgerDigest.pageReceiptCount)

            val evidence =
                adapter(source, target, pageSize = 2)
                    .reverifyImmutableImportEvidence()
            requireNotNull(evidence)
            assertEquals(FencedAuthority.STUDENT_MISTAKES, evidence.authority)
            assertEquals(CUTOVER_GENERATION, evidence.cutoverGeneration)
            assertEquals(0L, evidence.migratedRecordCount)
            assertEquals(LEGACY_PREFIX_RECEIPT, evidence.legacyPrefixReceiptFingerprint)
            assertTrue(evidence.hasValidFingerprint())
        }

    @Test
    fun multiPageSourceUsesExactKeysetPagesAndOnlyTheLastPageIsTerminal() =
        runBlocking {
            val source = StudentCutoverFakeLegacySource((1..5).map(::studentRecord))
            val target = FakeStudentTarget()

            val result = migrator(source, target, pageSize = 2).migrate()

            assertEquals(listOf(2, 2, 1), target.commands.map { it.records.size })
            assertEquals(listOf(false, false, true), target.commands.map { it.isLastPage })
            assertTrue(
                target.commands
                    .flatMap { it.records }
                    .all { it.importSemanticSnapshot != null },
            )
            assertEquals(
                listOf(null, target.commands[0].records.last().key,
                    target.commands[1].records.last().key),
                target.commands.map { it.afterExclusive },
            )
            assertEquals(5L, result.ledgerDigest.migratedRecordCount)
            assertEquals(3, result.ledgerDigest.pageReceiptCount)
            assertEquals(
                studentPageFingerprint(
                    afterExclusive = source.records[3].cursor,
                    records = listOf(source.records[4]),
                ),
                result.ledgerDigest.terminalSourcePageCanonicalFingerprint,
            )

            val evidence =
                adapter(source, target, pageSize = 2)
                    .reverifyImmutableImportEvidence()
            requireNotNull(evidence)
            assertEquals(5L, evidence.migratedRecordCount)
            assertTrue(evidence.hasValidFingerprint())
            target.ledgerTransform = { ledger ->
                ledger.copy(legacySemanticSnapshotCount = 4)
            }
            assertNull(
                adapter(source, target, pageSize = 2)
                    .reverifyImmutableImportEvidence(),
            )
        }

    @Test
    fun retryAfterPersistedPageResumesWithoutDuplicatingThePage() {
        val source = StudentCutoverFakeLegacySource((1..4).map(::studentRecord))
        val target =
            FakeStudentTarget().apply {
                failOnceAfterPersistedCommandIndex = 0
            }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                migrator(source, target, pageSize = 2).migrate()
            }
        }

        val result =
            runBlocking {
                migrator(source, target, pageSize = 2).migrate()
            }
        assertEquals(2, target.commands.size)
        assertEquals(4L, result.ledgerDigest.migratedRecordCount)
        assertEquals(2, target.applyAttempts)
        assertTrue(target.commands.last().isLastPage)
    }

    @Test
    fun currentSourceChangeCannotReusePreviouslyVerifiedEmptyImport() =
        runBlocking {
            val source = StudentCutoverFakeLegacySource()
            val target = FakeStudentTarget()
            migrator(source, target, pageSize = 2).migrate()
            val control = adapter(source, target, pageSize = 2)
            assertTrue(control.reverifyImmutableImportEvidence() != null)

            source.records = listOf(studentRecord(1))

            assertNull(control.reverifyImmutableImportEvidence())
        }

    @Test
    fun mismatchedOrUnprovenDestinationLedgerFailsClosed() =
        runBlocking {
            val source = StudentCutoverFakeLegacySource()
            val target = FakeStudentTarget()
            migrator(source, target, pageSize = 2).migrate()
            val control = adapter(source, target, pageSize = 2)

            target.ledgerTransform = { ledger ->
                ledger.copy(
                    migratedRecordCount = 1L,
                    immutableImportSnapshotCount = 1L,
                    legacySemanticSnapshotCount = 1L,
                )
            }
            assertNull(control.reverifyImmutableImportEvidence())

            target.ledgerTransform = { ledger ->
                ledger.copy(
                    terminalSourcePageCanonicalFingerprint = sha("wrong-terminal"),
                )
            }
            assertNull(control.reverifyImmutableImportEvidence())

            target.ledgerTransform = { ledger ->
                ledger.copy(pageReceiptCount = ledger.pageReceiptCount + 1)
            }
            assertNull(control.reverifyImmutableImportEvidence())

            target.ledgerTransform = { ledger ->
                ledger.copy(destinationLedgerVersion = 1)
            }
            assertNull(control.reverifyImmutableImportEvidence())

            target.ledgerTransform = { it }
            target.unprovenLegacyLedger = true
            assertNull(control.reverifyImmutableImportEvidence())
        }

    @Test
    fun pendingLegacyDestinationReattestationFailsClosed() =
        runBlocking {
            val source = StudentCutoverFakeLegacySource()
            val target = FakeStudentTarget()
            migrator(source, target, pageSize = 2).migrate()
            target.pendingReattestations =
                listOf(
                    StudentMistakeDestinationReattestationChallenge(
                        migrationId = target.commands.single().migrationId,
                        revisionId = "legacy-revision",
                        legacyDestinationRecordCanonicalFingerprint = sha("legacy-proof"),
                        replacementDestinationRecordCanonicalFingerprint =
                            sha("current-proof"),
                        canonicalPolicyVersion = 3,
                    ),
                )

            assertNull(
                adapter(source, target, pageSize = 2)
                    .reverifyImmutableImportEvidence(),
            )
        }

    @Test
    fun fenceAndCompletionReceiptMapExactlyInBothDirections() =
        runBlocking {
            val source = StudentCutoverFakeLegacySource()
            val target = FakeStudentTarget()
            val control = adapter(source, target, pageSize = 2)
            val fence =
                AuthorityCutoverFence.create(
                    authority = FencedAuthority.STUDENT_MISTAKES,
                    cutoverGeneration = CUTOVER_GENERATION,
                    studentImportEvidenceFingerprint = sha("student-evidence"),
                    masteryImportEvidenceFingerprint = sha("mastery-evidence"),
                )

            assertEquals(fence, control.appendCutoverFenceIfAbsent(fence))
            assertEquals(fence, control.readCutoverFence())
            assertEquals(
                StudentMistakeAuthorityCutoverFence(
                    cutoverGeneration = fence.cutoverGeneration,
                    studentImportEvidenceFingerprint =
                        fence.studentImportEvidenceFingerprint,
                    masteryImportEvidenceFingerprint =
                        fence.masteryImportEvidenceFingerprint,
                    cutoverIntentFingerprint = fence.cutoverIntentFingerprint,
                    fenceFingerprint = fence.fenceFingerprint,
                ),
                target.fence,
            )

            val receipt = AuthorityCutoverCompletionReceipt.create(fence)
            assertEquals(
                receipt,
                control.appendCompletionReceiptIfAbsent(receipt),
            )
            assertEquals(receipt, control.readCompletionReceipt())
            assertEquals(
                receipt.receiptFingerprint,
                target.completionReceipt?.receiptFingerprint,
            )
        }

    @Test
    fun fenceAdapterRejectsForeignOrTamperedArtifacts() {
        val source = StudentCutoverFakeLegacySource()
        val target = FakeStudentTarget()
        val control = adapter(source, target, pageSize = 2)
        val valid =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.STUDENT_MISTAKES,
                cutoverGeneration = CUTOVER_GENERATION,
                studentImportEvidenceFingerprint = sha("student-evidence"),
                masteryImportEvidenceFingerprint = sha("mastery-evidence"),
            )
        val foreign =
            AuthorityCutoverFence.create(
                authority = FencedAuthority.LEARNER_MASTERY,
                cutoverGeneration = CUTOVER_GENERATION,
                studentImportEvidenceFingerprint = sha("student-evidence"),
                masteryImportEvidenceFingerprint = sha("mastery-evidence"),
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { control.appendCutoverFenceIfAbsent(foreign) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                control.appendCutoverFenceIfAbsent(
                    valid.copy(fenceFingerprint = sha("tampered")),
                )
            }
        }
        val validReceipt = AuthorityCutoverCompletionReceipt.create(valid)
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                control.appendCompletionReceiptIfAbsent(
                    validReceipt.copy(authority = FencedAuthority.LEARNER_MASTERY),
                )
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                control.appendCompletionReceiptIfAbsent(
                    validReceipt.copy(receiptFingerprint = sha("tampered-receipt")),
                )
            }
        }

        target.fence =
            StudentMistakeAuthorityCutoverFence.create(
                cutoverGeneration = CUTOVER_GENERATION,
                studentImportEvidenceFingerprint = sha("student-evidence"),
                masteryImportEvidenceFingerprint = sha("mastery-evidence"),
            ).copy(fenceFingerprint = sha("persisted-tamper"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking { control.readCutoverFence() }
        }
    }

    @Test
    fun cutoverGenerationMustBeExplicitAndPositive() {
        assertThrows(IllegalArgumentException::class.java) {
            StudentAuthorityCutoverControlAdapter(
                target = FakeStudentTarget(),
                legacySource = StudentCutoverFakeLegacySource(),
                learnerId = LEARNER_ID,
                legacyPrefixReceiptFingerprint = LEGACY_PREFIX_RECEIPT,
                cutoverGeneration = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalStudentAuthorityMigrator(
                legacySource = StudentCutoverFakeLegacySource(),
                targetMigration = FakeStudentTarget(),
                targetCutover = FakeStudentTarget(),
                learnerId = LEARNER_ID,
                documentMapper = mapper(),
                cutoverGeneration = 0,
            )
        }
    }

    private fun adapter(
        source: StudentCutoverFakeLegacySource,
        target: FakeStudentTarget,
        pageSize: Int,
    ) =
        StudentAuthorityCutoverControlAdapter(
            target = target,
            legacySource = source,
            learnerId = LEARNER_ID,
            legacyPrefixReceiptFingerprint = LEGACY_PREFIX_RECEIPT,
            cutoverGeneration = CUTOVER_GENERATION,
            pageSize = pageSize,
        )

    private fun migrator(
        source: StudentCutoverFakeLegacySource,
        target: FakeStudentTarget,
        pageSize: Int,
    ) =
        TerminalStudentAuthorityMigrator(
            legacySource = source,
            targetMigration = target,
            targetCutover = target,
            learnerId = LEARNER_ID,
            documentMapper = mapper(),
            cutoverGeneration = CUTOVER_GENERATION,
            pageSize = pageSize,
            clock = { 10_000L + target.commands.size },
        )
}

private class StudentCutoverFakeLegacySource(
    initialRecords: List<LegacyStudentDocumentMigrationRecord> = emptyList(),
) : LegacyAuthorityMigrationSourcePort {
    var records: List<LegacyStudentDocumentMigrationRecord> = initialRecords

    override suspend fun readLegacyAuthorityMigrationSnapshot(
        learnerId: String,
    ): LegacyAuthorityMigrationSnapshot {
        require(learnerId == LEARNER_ID)
        return LegacyAuthorityMigrationSnapshot(
            schemaVersion = 32,
            studentDocumentRevisionCount = records.size.toLong(),
            studentSourceAssetLinkCount =
                records.sumOf { it.sourceAssets.size }.toLong(),
            studentBrokenSourceAssetLinkCount = 0,
            studentSourceAssetByteCount =
                records.sumOf { record ->
                    record.sourceAssets.sumOf { it.sourceAsset.byteSize }
                },
            studentProblemWithMultipleEntriesCount = 0,
            masterySourceFactCount = 0,
            masteryProvenSourceFactCount = 0,
            latestStudentMutationAtEpochMillis =
                records.maxOfOrNull { it.updatedAtEpochMillis } ?: 0,
            latestMasteryFactAtEpochMillis = 0,
        )
    }

    override suspend fun readLegacyStudentDocumentMigrationPage(
        afterExclusive: LegacyStudentDocumentMigrationCursor?,
        limit: Int,
    ): LegacyStudentDocumentMigrationPage {
        val available =
            records.filter { record ->
                afterExclusive == null || record.cursor > afterExclusive
            }
        val selected = available.take(limit)
        return LegacyStudentDocumentMigrationPage(
            records = selected,
            hasMore = available.size > selected.size,
        )
    }

    override suspend fun readLegacyMasteryFactMigrationPage(
        learnerId: String,
        afterExclusive: LegacyMasteryFactMigrationCursor?,
        limit: Int,
    ): LegacyMasteryFactMigrationPage {
        require(learnerId == LEARNER_ID)
        return LegacyMasteryFactMigrationPage(
            records = emptyList(),
            hasMore = false,
        )
    }
}

private class FakeStudentTarget :
    StudentMistakeMigrationPort,
    StudentMistakeCutoverControlPort {
    val commands = mutableListOf<ApplyStudentMistakeMigrationPageCommand>()
    private val receipts = mutableListOf<StudentMistakeMigrationReceipt>()
    private var checkpoint: StudentMistakeMigrationCheckpoint? = null
    var fence: StudentMistakeAuthorityCutoverFence? = null
    var completionReceipt: StudentMistakeAuthorityCutoverCompletionReceipt? = null
    var applyAttempts = 0
    var failOnceAfterPersistedCommandIndex: Int? = null
    var unprovenLegacyLedger = false
    var pendingReattestations =
        emptyList<StudentMistakeDestinationReattestationChallenge>()
    var ledgerTransform:
        (StudentMistakeImmutableMigrationLedgerDigest) ->
            StudentMistakeImmutableMigrationLedgerDigest = { it }

    override suspend fun applyPage(
        command: ApplyStudentMistakeMigrationPageCommand,
    ): StudentMistakeMigrationReceipt {
        applyAttempts += 1
        commands.indexOfFirst {
            it.sourcePageCanonicalFingerprint == command.sourcePageCanonicalFingerprint
        }.takeIf { it >= 0 }?.let { existingIndex ->
            check(commands[existingIndex] == command)
            return receipts[existingIndex]
        }
        check(checkpoint?.completed != true)
        check(
            command.expectedCheckpointCanonicalFingerprint ==
                checkpoint?.checkpointCanonicalFingerprint,
        )
        check(command.afterExclusive == checkpoint?.lastKey)
        check(
            checkpoint == null ||
                checkpoint?.sourceDatabaseCanonicalFingerprint ==
                command.sourceDatabaseCanonicalFingerprint,
        )

        val lastKey = command.records.lastOrNull()?.key ?: checkpoint?.lastKey
        val total = (checkpoint?.importedRecordCount ?: 0L) + command.records.size
        val nextCheckpoint =
            StudentMistakeMigrationCheckpoint(
                migrationId = command.migrationId,
                sourceDatabaseCanonicalFingerprint =
                    command.sourceDatabaseCanonicalFingerprint,
                lastKey = lastKey,
                importedRecordCount = total,
                completed = command.isLastPage,
                checkpointCanonicalFingerprint =
                    checkpointFingerprint(
                        migrationId = command.migrationId,
                        sourceFingerprint =
                            command.sourceDatabaseCanonicalFingerprint,
                        sourcePageFingerprint =
                            command.sourcePageCanonicalFingerprint,
                        lastKey = lastKey,
                        importedRecordCount = total,
                        completed = command.isLastPage,
                    ),
            )
        val receipt =
            StudentMistakeMigrationReceipt(
                migrationId = command.migrationId,
                sourcePageCanonicalFingerprint =
                    command.sourcePageCanonicalFingerprint,
                importedRecordCount = command.records.size,
                checkpoint = nextCheckpoint,
                receiptCanonicalFingerprint =
                    CanonicalSha256("student-mistake-migration-receipt-v1")
                        .field("migrationId", command.migrationId)
                        .field(
                            "sourcePageCanonicalFingerprint",
                            command.sourcePageCanonicalFingerprint,
                        )
                        .field("importedRecordCount", command.records.size)
                        .field(
                            "checkpointCanonicalFingerprint",
                            nextCheckpoint.checkpointCanonicalFingerprint,
                        )
                        .finish(),
            )
        commands += command
        receipts += receipt
        checkpoint = nextCheckpoint
        if (failOnceAfterPersistedCommandIndex == commands.lastIndex) {
            failOnceAfterPersistedCommandIndex = null
            error("simulated crash after durable page")
        }
        return receipt
    }

    override suspend fun readCheckpoint(
        migrationId: String,
    ): StudentMistakeMigrationCheckpoint? =
        checkpoint?.takeIf { it.migrationId == migrationId }

    override suspend fun recomputeCompletedMigrationLedger(
        migrationId: String,
    ): StudentMistakeImmutableMigrationLedgerDigest? {
        if (unprovenLegacyLedger) return null
        val durable = checkpoint?.takeIf { it.migrationId == migrationId } ?: return null
        if (!durable.completed) return null
        val terminal = commands.lastOrNull() ?: return null
        return ledgerTransform(
            StudentMistakeImmutableMigrationLedgerDigest(
                migrationId = migrationId,
                sourceDatabaseCanonicalFingerprint =
                    durable.sourceDatabaseCanonicalFingerprint,
                migratedRecordCount = durable.importedRecordCount,
                checkpointCanonicalFingerprint =
                    durable.checkpointCanonicalFingerprint,
                terminalSourcePageCanonicalFingerprint =
                    terminal.sourcePageCanonicalFingerprint,
                pageReceiptCount = commands.size,
                destinationCanonicalFingerprint =
                    CanonicalSha256("fake-student-destination-v1")
                        .field("migrationId", migrationId)
                        .field("recordCount", durable.importedRecordCount)
                        .finish(),
                destinationLedgerVersion = 3,
                immutableImportSnapshotCount = durable.importedRecordCount,
                legacySemanticSnapshotCount = durable.importedRecordCount,
            ),
        )
    }

    override suspend fun readPendingDestinationReattestations(
        migrationId: String,
    ): List<StudentMistakeDestinationReattestationChallenge> =
        pendingReattestations.filter { challenge -> challenge.migrationId == migrationId }

    override suspend fun readCutoverFence(): StudentMistakeAuthorityCutoverFence? =
        fence

    override suspend fun appendCutoverFenceIfAbsent(
        candidate: StudentMistakeAuthorityCutoverFence,
    ): StudentMistakeAuthorityCutoverFence {
        fence?.let { return it }
        fence = candidate
        return candidate
    }

    override suspend fun readCompletionReceipt():
        StudentMistakeAuthorityCutoverCompletionReceipt? =
        completionReceipt

    override suspend fun appendCompletionReceiptIfAbsent(
        candidate: StudentMistakeAuthorityCutoverCompletionReceipt,
    ): StudentMistakeAuthorityCutoverCompletionReceipt {
        completionReceipt?.let { return it }
        completionReceipt = candidate
        return candidate
    }

    override fun close() = Unit
}

private fun studentRecord(index: Int): LegacyStudentDocumentMigrationRecord =
    LegacyStudentDocumentMigrationRecord(
        entryId = "entry-$index",
        problemId = "problem-$index",
        problemCanonicalFingerprint = sha("problem-$index"),
        revisionId = "revision-$index",
        revisionNumber = index,
        subject = SubjectKind.MATH.name,
        problemCreatedAtEpochMillis = index * 1_000L,
        problemArchivedAtEpochMillis = null,
        title = "题目 $index",
        problemMarkdown = "计算 $index + $index",
        questionDocumentSnapshot = null,
        answerSpecId = null,
        answerSpecSnapshot = null,
        answerVerificationStatus = "UNVERIFIED",
        revisionSourceType = "LEGACY_IMPORT",
        revisionSourceReference = null,
        contentFingerprint = sha("content-$index"),
        practiceUnitId = "practice-$index",
        practiceUnitKey = "practice-key-$index",
        practiceUnitKind = "WHOLE_PROBLEM",
        practiceUnitTitle = "整题 $index",
        practiceUnitPromptMarkdown = "计算 $index + $index",
        estimatedSeconds = 120,
        practiceUnitRevisionId = "revision-$index",
        practiceUnitCreatedAtEpochMillis = index * 1_000L,
        entryCurrentRevisionId = "revision-$index",
        sourceKey = null,
        status = "ACTIVE",
        acceptedAtEpochMillis = index * 1_000L,
        updatedAtEpochMillis = index * 1_000L + 10,
        revisionCreatedAtEpochMillis = index * 1_000L,
        sourceAssets = emptyList(),
    )

private fun mapper(): LegacyStudentDocumentMapper =
    LegacyStudentDocumentMapper(
        learnerId = LEARNER_ID,
        assetUriResolver = LegacyStudentAssetUriResolver { "file:///unused" },
    )

private fun checkpointFingerprint(
    migrationId: String,
    sourceFingerprint: String,
    sourcePageFingerprint: String,
    lastKey: StudentMistakeMigrationKey?,
    importedRecordCount: Long,
    completed: Boolean,
): String =
    CanonicalSha256("student-mistake-migration-checkpoint-v1")
        .field("migrationId", migrationId)
        .field("sourceDatabaseCanonicalFingerprint", sourceFingerprint)
        .field("sourcePageCanonicalFingerprint", sourcePageFingerprint)
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

private fun sha(value: String): String =
    CanonicalSha256("student-cutover-adapter-test-v1")
        .field("value", value)
        .finish()

private const val LEARNER_ID = "local-learner"
private const val CUTOVER_GENERATION = 7L
private val LEGACY_PREFIX_RECEIPT = sha("legacy-prefix-receipt")
