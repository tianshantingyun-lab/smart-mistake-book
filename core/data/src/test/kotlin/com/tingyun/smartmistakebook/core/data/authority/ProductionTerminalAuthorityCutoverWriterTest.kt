package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ProductionTerminalAuthorityCutoverWriterTest {
    @Test
    fun crashAfterEachOfTwelveDurableAppendsRecoversWithoutDuplicateReceipt() {
        ThreeAuthorityCutoverStage.legacyJournalPrefix.indices.forEach { crashIndex ->
            val journal = CrashAfterAppendJournal(crashIndex)
            val fixture = WriterFixture(journal)

            assertThrows(InjectedCutoverCrash::class.java) {
                runBlocking {
                    fixture.writer().migrateAndVerifyTerminalJournal()
                }
            }

            val recovered =
                runBlocking {
                    fixture.writer().migrateAndVerifyTerminalJournal()
                }
            assertEquals(
                ThreeAuthorityCutoverStage.legacyJournalPrefix,
                recovered.durableStages,
            )
            assertEquals(
                ThreeAuthorityCutoverStage.legacyJournalPrefix,
                journal.receipts.map(AuthorityStageReceipt::stage),
            )
            assertEquals(
                journal.receipts.size,
                journal.receipts.map(AuthorityStageReceipt::receiptFingerprint).distinct().size,
            )

            val attemptsAfterRecovery = journal.appendAttempts
            runBlocking {
                fixture.writer().migrateAndVerifyTerminalJournal()
            }
            assertEquals(attemptsAfterRecovery, journal.appendAttempts)
        }
    }

    @Test
    fun changedGenerationCannotReplayACompletedTerminalJournal() {
        val journal = MemoryJournal()
        val fixture = WriterFixture(journal, cutoverGeneration = 7L)
        runBlocking {
            fixture.writer().migrateAndVerifyTerminalJournal()
        }

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.writer(cutoverGeneration = 8L)
                    .migrateAndVerifyTerminalJournal()
            }
        }
        assertEquals(12, journal.receipts.size)
    }

    @Test
    fun changedLegacyPrefixDestinationBlocksTailReplay() {
        val journal = MemoryJournal()
        val fixture = WriterFixture(journal)
        runBlocking {
            fixture.writer().migrateAndVerifyTerminalJournal()
        }
        fixture.prefixStates[
            ThreeAuthorityCutoverStage.KNOWLEDGE_RUNTIME_READ_ONLY
        ] =
            fixture.prefixStates.getValue(
                ThreeAuthorityCutoverStage.KNOWLEDGE_RUNTIME_READ_ONLY,
            ).copy(destinationFingerprint = sha("changed-knowledge-prefix"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.writer().migrateAndVerifyTerminalJournal()
            }
        }
        assertEquals(12, journal.receipts.size)
    }

    @Test
    fun sourceChangeBetweenMigrationAndVerificationIsNotJournaled() {
        val journal = MemoryJournal()
        val fixture = WriterFixture(journal)
        fixture.terminalOperations[0].changeOnNextVerify = true

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.writer().migrateAndVerifyTerminalJournal()
            }
        }
        assertEquals(
            ThreeAuthorityCutoverStage.legacyJournalPrefix.take(4),
            journal.receipts.map(AuthorityStageReceipt::stage),
        )
    }

    @Test
    fun destinationChangeAfterCompletionBlocksFreshRerun() {
        val journal = MemoryJournal()
        val fixture = WriterFixture(journal)
        runBlocking {
            fixture.writer().migrateAndVerifyTerminalJournal()
        }
        fixture.terminalOperations[1].snapshot =
            fixture.terminalOperations[1].snapshot.copy(
                destinationFingerprint = sha("changed-student-index"),
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.writer().migrateAndVerifyTerminalJournal()
            }
        }
        assertEquals(12, journal.receipts.size)
    }

    @Test
    fun nonExactAppendReplayFailsClosed() {
        val journal = DifferentReplayJournal()
        val fixture = WriterFixture(journal)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                fixture.writer().migrateAndVerifyTerminalJournal()
            }
        }
        assertEquals(
            ThreeAuthorityCutoverStage.legacyJournalPrefix.take(4),
            journal.receipts.map(AuthorityStageReceipt::stage),
        )
    }

    @Test
    fun malformedJournalOrderDuplicateAndTargetAreRejectedBeforeMigration() {
        val layout = ThreeAuthorityDatabaseLayout()
        val first =
            receipt(
                stage = ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY,
                target = layout.cutoverStorageNameFor(
                    CutoverStorageTarget.LEGACY_MIGRATION_SOURCE,
                ),
                previous = null,
            )
        val duplicate =
            receipt(
                stage = ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY,
                target = layout.cutoverStorageNameFor(
                    CutoverStorageTarget.LEGACY_MIGRATION_SOURCE,
                ),
                previous = first.receiptFingerprint,
            )
        val wrongTarget =
            receipt(
                stage = ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY,
                target = layout.studentMistakes,
                previous = null,
            )

        listOf(
            MemoryJournal(mutableListOf(first, duplicate)),
            MemoryJournal(mutableListOf(wrongTarget)),
        ).forEach { journal ->
            val fixture = WriterFixture(journal)
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    fixture.writer().migrateAndVerifyTerminalJournal()
                }
            }
            assertEquals(0, journal.appendAttempts)
        }
    }

    @Test
    fun writerRejectsMissingOutOfOrderOrDuplicateStageImplementations() {
        val fixture = WriterFixture(MemoryJournal())

        assertThrows(IllegalArgumentException::class.java) {
            fixture.writer(
                terminalOperations = fixture.terminalOperations.dropLast(1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.writer(
                terminalOperations = fixture.terminalOperations.reversed(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture.writer(
                terminalOperations =
                    fixture.terminalOperations.toMutableList().also { operations ->
                        operations[1] = operations[0]
                    },
            )
        }
    }

    @Test
    fun missingRealPostImportCapabilitiesAreReportedWithoutTouchingJournal() {
        val journal = MemoryJournal()
        val operations =
            REQUIRED_POST_STAGES_FOR_TEST
                .dropLast(2)
                .map(::FakeTerminalOperation)

        val result =
            TerminalAuthorityPostImportCapabilityResolver.resolve(operations)

        assertTrue(result is TerminalAuthorityPostImportCapabilityResolution.Blocked)
        result as TerminalAuthorityPostImportCapabilityResolution.Blocked
        assertEquals(REQUIRED_POST_STAGES_FOR_TEST.takeLast(2).toSet(), result.missingStages)
        assertEquals(emptySet<ThreeAuthorityCutoverStage>(), result.duplicateStages)
        assertEquals(emptySet<ThreeAuthorityCutoverStage>(), result.unexpectedStages)
        assertEquals(0, journal.appendAttempts)
    }

    @Test
    fun postImportCapabilityResolverRejectsDuplicateAndForeignStages() {
        val operations =
            REQUIRED_POST_STAGES_FOR_TEST
                .map(::FakeTerminalOperation)
                .toMutableList()
                .also {
                    it += FakeTerminalOperation(
                        ThreeAuthorityCutoverStage.MASTERY_BINDINGS_RECONCILED,
                    )
                    it += FakeTerminalOperation(
                        ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED,
                    )
                }

        val result =
            TerminalAuthorityPostImportCapabilityResolver.resolve(operations)

        assertTrue(result is TerminalAuthorityPostImportCapabilityResolution.Blocked)
        result as TerminalAuthorityPostImportCapabilityResolution.Blocked
        assertEquals(
            setOf(ThreeAuthorityCutoverStage.MASTERY_BINDINGS_RECONCILED),
            result.duplicateStages,
        )
        assertEquals(
            setOf(ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED),
            result.unexpectedStages,
        )
    }

    @Test
    fun studentStagesPersistOnlyExactOwnerProofFields() {
        val journal = MemoryJournal()
        val fixture = WriterFixture(journal)
        val owner = RecordingStudentAttestationOwner()

        runBlocking {
            fixture.ownerBackedWriter(owner).migrateAndVerifyTerminalJournal()
        }

        STUDENT_OWNER_STAGES_FOR_TEST.forEach { stage ->
            val receipt =
                journal.receipts.single { candidate -> candidate.stage == stage }
            val ownerProof = owner.snapshots.getValue(stage)
            assertEquals(ownerProof.migratedRecordCount, receipt.migratedRecordCount)
            assertEquals(ownerProof.sourceCheckpoint, receipt.sourceCheckpoint)
            assertEquals(
                ownerProof.destinationFingerprint,
                receipt.destinationFingerprint,
            )
        }
        assertEquals(
            ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED,
            owner.boundReceipts.single().stage,
        )
    }

    @Test
    fun missingStudentOwnerProofBlocksBeforeItsJournalAppend() {
        STUDENT_OWNER_STAGES_FOR_TEST.forEach { blockedStage ->
            val journal = MemoryJournal()
            val fixture = WriterFixture(journal)
            val owner =
                RecordingStudentAttestationOwner(
                    blockedStage = blockedStage,
                )

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    fixture.ownerBackedWriter(owner)
                        .migrateAndVerifyTerminalJournal()
                }
            }

            assertEquals(
                ThreeAuthorityCutoverStage.legacyJournalPrefix.take(
                    ThreeAuthorityCutoverStage.legacyJournalPrefix
                        .indexOf(blockedStage),
                ),
                journal.receipts.map(AuthorityStageReceipt::stage),
            )
        }
    }

    @Test
    fun crashRecoveryRebindsStageFiveAndFreshlyReattestsStudentStages() {
        (4..7).forEach { crashIndex ->
            val journal = CrashAfterAppendJournal(crashIndex = crashIndex)
            val fixture = WriterFixture(journal)
            val firstOwner = RecordingStudentAttestationOwner()

            assertThrows(InjectedCutoverCrash::class.java) {
                runBlocking {
                    fixture.ownerBackedWriter(firstOwner)
                        .migrateAndVerifyTerminalJournal()
                }
            }
            assertEquals(
                ThreeAuthorityCutoverStage.legacyJournalPrefix
                    .take(crashIndex + 1),
                journal.receipts.map(AuthorityStageReceipt::stage),
            )

            val recoveredOwner = RecordingStudentAttestationOwner()
            runBlocking {
                fixture.ownerBackedWriter(recoveredOwner)
                    .migrateAndVerifyTerminalJournal()
            }

            assertEquals(1, recoveredOwner.boundReceipts.size)
            assertTrue(
                recoveredOwner.calls.getValue(
                    ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED,
                ) >= 1,
            )
            assertEquals(
                ThreeAuthorityCutoverStage.legacyJournalPrefix,
                journal.receipts.map(AuthorityStageReceipt::stage),
            )
            assertEquals(
                journal.receipts.size,
                journal.receipts
                    .map(AuthorityStageReceipt::receiptFingerprint)
                    .distinct()
                    .size,
            )
        }
    }

    private class WriterFixture(
        private val journal: AuthorityCutoverJournal,
        private val cutoverGeneration: Long = 7L,
    ) {
        val prefixStates =
            ThreeAuthorityCutoverStage.legacyJournalPrefix
                .take(4)
                .associateWith { stage -> snapshot(stage) }
                .toMutableMap()
        val terminalOperations =
            ThreeAuthorityCutoverStage.legacyJournalPrefix
                .drop(4)
                .map(::FakeTerminalOperation)
        private val studentAttestationOwner = FakeStudentAttestationOwner()

        fun writer(
            cutoverGeneration: Long = this.cutoverGeneration,
            terminalOperations: List<TerminalAuthorityStageOperation> =
                this.terminalOperations,
        ): ProductionTerminalAuthorityCutoverWriter =
            ProductionTerminalAuthorityCutoverWriter(
                layout = ThreeAuthorityDatabaseLayout(),
                prefixSteps =
                    ThreeAuthorityCutoverStage.legacyJournalPrefix.take(4).map { stage ->
                        FakePrefixStep(stage, prefixStates)
                    },
                terminalOperations = terminalOperations,
                studentAttestationOwner = studentAttestationOwner,
                journal = journal,
                cutoverGeneration = cutoverGeneration,
                clock = { 10_000L },
            )

        fun ownerBackedWriter(
            owner: StudentTerminalAuthorityAttestationOwner,
        ): ProductionTerminalAuthorityCutoverWriter {
            val terminalOperations =
                listOf(
                    FakeTerminalOperation(
                        ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED,
                    ),
                ) +
                    createStudentDestinationAttestationOperations(owner) +
                    ThreeAuthorityCutoverStage.legacyJournalPrefix
                        .drop(8)
                        .map(::FakeTerminalOperation)
            return ProductionTerminalAuthorityCutoverWriter(
                layout = ThreeAuthorityDatabaseLayout(),
                prefixSteps =
                    ThreeAuthorityCutoverStage.legacyJournalPrefix.take(4).map { stage ->
                        FakePrefixStep(stage, prefixStates)
                    },
                terminalOperations = terminalOperations,
                studentAttestationOwner = owner,
                journal = journal,
                cutoverGeneration = cutoverGeneration,
                clock = { 10_000L },
            )
        }
    }

    private class FakePrefixStep(
        override val stage: ThreeAuthorityCutoverStage,
        private val states:
            Map<ThreeAuthorityCutoverStage, TerminalAuthorityStageSnapshot>,
    ) : AuthorityCutoverStep {
        override suspend fun migrate(
            previous: AuthorityStageReceipt?,
        ): AuthorityStageReceipt {
            val state = states.getValue(stage)
            return AuthorityStageReceipt.create(
                stage = stage,
                targetDatabaseName =
                    ThreeAuthorityDatabaseLayout().cutoverStorageNameFor(
                        stage.storageTarget,
                    ),
                migratedRecordCount = state.migratedRecordCount,
                sourceCheckpoint = state.sourceCheckpoint,
                destinationFingerprint = state.destinationFingerprint,
                completedAtEpochMillis = 1_000L + stage.ordinal,
                previousReceiptFingerprint = previous?.receiptFingerprint,
            )
        }

        override suspend fun verify(
            receipt: AuthorityStageReceipt,
        ): AuthorityStageVerification {
            val state = states.getValue(stage)
            check(receipt.migratedRecordCount == state.migratedRecordCount)
            check(receipt.sourceCheckpoint == state.sourceCheckpoint)
            check(receipt.destinationFingerprint == state.destinationFingerprint)
            return AuthorityStageVerification(
                receiptFingerprint = receipt.receiptFingerprint,
                destinationFingerprint = state.destinationFingerprint,
            )
        }
    }

    private class FakeTerminalOperation(
        override val stage: ThreeAuthorityCutoverStage,
    ) : TerminalAuthorityStageOperation {
        var snapshot: TerminalAuthorityStageSnapshot = snapshot(stage)
        var changeOnNextVerify: Boolean = false

        override suspend fun migrate(
            binding: TerminalAuthorityCutoverBinding,
            predecessor: AuthorityStageReceipt,
        ): TerminalAuthorityStageSnapshot = snapshot

        override suspend fun verify(
            binding: TerminalAuthorityCutoverBinding,
        ): TerminalAuthorityStageSnapshot {
            if (changeOnNextVerify) {
                changeOnNextVerify = false
                snapshot =
                    snapshot.copy(
                        sourceCheckpoint = "${snapshot.sourceCheckpoint}-changed",
                    )
            }
            return snapshot
        }
    }

    private class FakeStudentAttestationOwner :
        StudentTerminalAuthorityAttestationOwner {
        override fun bindDocumentImportReceipt(
            binding: TerminalAuthorityCutoverBinding,
            receipt: AuthorityStageReceipt,
        ) = Unit

        override suspend fun attestOutbox(
            binding: TerminalAuthorityCutoverBinding,
        ): TerminalAuthorityStageSnapshot =
            error("Generic writer fixture must not call the student owner")

        override suspend fun attestIndexes(
            binding: TerminalAuthorityCutoverBinding,
        ): TerminalAuthorityStageSnapshot =
            error("Generic writer fixture must not call the student owner")

        override suspend fun attestAuthority(
            binding: TerminalAuthorityCutoverBinding,
        ): TerminalAuthorityStageSnapshot =
            error("Generic writer fixture must not call the student owner")

        override fun close() = Unit
    }

    private class RecordingStudentAttestationOwner(
        private val blockedStage: ThreeAuthorityCutoverStage? = null,
    ) : StudentTerminalAuthorityAttestationOwner {
        val snapshots =
            STUDENT_OWNER_STAGES_FOR_TEST
                .associateWith(::snapshot)
        val boundReceipts = mutableListOf<AuthorityStageReceipt>()
        val calls =
            STUDENT_OWNER_STAGES_FOR_TEST
                .associateWith { 0 }
                .toMutableMap()

        override fun bindDocumentImportReceipt(
            binding: TerminalAuthorityCutoverBinding,
            receipt: AuthorityStageReceipt,
        ) {
            if (boundReceipts.none { it.receiptFingerprint == receipt.receiptFingerprint }) {
                boundReceipts += receipt
            }
        }

        override suspend fun attestOutbox(
            binding: TerminalAuthorityCutoverBinding,
        ): TerminalAuthorityStageSnapshot =
            attest(ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED)

        override suspend fun attestIndexes(
            binding: TerminalAuthorityCutoverBinding,
        ): TerminalAuthorityStageSnapshot =
            attest(ThreeAuthorityCutoverStage.STUDENT_INDEXES_REBUILT)

        override suspend fun attestAuthority(
            binding: TerminalAuthorityCutoverBinding,
        ): TerminalAuthorityStageSnapshot =
            attest(ThreeAuthorityCutoverStage.STUDENT_AUTHORITY_VERIFIED)

        override fun close() = Unit

        private fun attest(
            stage: ThreeAuthorityCutoverStage,
        ): TerminalAuthorityStageSnapshot {
            check(stage != blockedStage) {
                "Student owner proof is missing or expired"
            }
            calls[stage] = calls.getValue(stage) + 1
            return snapshots.getValue(stage)
        }
    }

    private open class MemoryJournal(
        val receipts: MutableList<AuthorityStageReceipt> = mutableListOf(),
    ) : AuthorityCutoverJournal {
        var appendAttempts: Int = 0

        override suspend fun readOrdered(): List<AuthorityStageReceipt> = receipts.toList()

        override suspend fun appendIfAbsent(
            receipt: AuthorityStageReceipt,
        ): AuthorityStageReceipt {
            appendAttempts += 1
            val existing = receipts.firstOrNull { it.stage == receipt.stage }
            if (existing != null) return existing
            receipts += receipt
            return receipt
        }
    }

    private class CrashAfterAppendJournal(
        private val crashIndex: Int,
    ) : MemoryJournal() {
        private var crashed = false

        override suspend fun appendIfAbsent(
            receipt: AuthorityStageReceipt,
        ): AuthorityStageReceipt {
            val persisted = super.appendIfAbsent(receipt)
            if (!crashed && receipts.lastIndex == crashIndex) {
                crashed = true
                throw InjectedCutoverCrash()
            }
            return persisted
        }
    }

    private class DifferentReplayJournal : MemoryJournal() {
        override suspend fun appendIfAbsent(
            receipt: AuthorityStageReceipt,
        ): AuthorityStageReceipt {
            if (receipt.stage != ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED) {
                return super.appendIfAbsent(receipt)
            }
            appendAttempts += 1
            return AuthorityStageReceipt.create(
                stage = receipt.stage,
                targetDatabaseName = receipt.targetDatabaseName,
                migratedRecordCount = receipt.migratedRecordCount + 1L,
                sourceCheckpoint = receipt.sourceCheckpoint,
                destinationFingerprint = receipt.destinationFingerprint,
                completedAtEpochMillis = receipt.completedAtEpochMillis,
                previousReceiptFingerprint = receipt.previousReceiptFingerprint,
            )
        }
    }

    private class InjectedCutoverCrash : RuntimeException()

    private companion object {
        val REQUIRED_POST_STAGES_FOR_TEST =
            listOf(
                ThreeAuthorityCutoverStage.MASTERY_BINDINGS_RECONCILED,
                ThreeAuthorityCutoverStage.MASTERY_PROJECTIONS_REBUILT,
                ThreeAuthorityCutoverStage.MASTERY_AUTHORITY_VERIFIED,
            )
        val STUDENT_OWNER_STAGES_FOR_TEST =
            listOf(
                ThreeAuthorityCutoverStage.STUDENT_OUTBOX_RECONCILED,
                ThreeAuthorityCutoverStage.STUDENT_INDEXES_REBUILT,
                ThreeAuthorityCutoverStage.STUDENT_AUTHORITY_VERIFIED,
            )

        fun snapshot(
            stage: ThreeAuthorityCutoverStage,
        ): TerminalAuthorityStageSnapshot =
            TerminalAuthorityStageSnapshot(
                migratedRecordCount = stage.ordinal.toLong(),
                sourceCheckpoint = "checkpoint-${stage.ordinal}",
                destinationFingerprint = sha("destination-${stage.ordinal}"),
            )

        fun receipt(
            stage: ThreeAuthorityCutoverStage,
            target: String,
            previous: String?,
        ): AuthorityStageReceipt =
            AuthorityStageReceipt.create(
                stage = stage,
                targetDatabaseName = target,
                migratedRecordCount = 0L,
                sourceCheckpoint = "checkpoint",
                destinationFingerprint = sha("receipt-${stage.name}-$previous-$target"),
                completedAtEpochMillis = 1L,
                previousReceiptFingerprint = previous,
            )

        fun sha(value: String): String =
            CanonicalSha256("production-terminal-authority-cutover-writer-test")
                .field("value", value)
                .finish()
    }
}
