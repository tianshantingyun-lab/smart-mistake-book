package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ThreeAuthorityCutoverTest {
    @Test
    fun productionLayoutUsesExactlyThreeDistinctBusinessAuthorityFiles() {
        val layout = ThreeAuthorityDatabaseLayout()

        assertEquals("student-mistakes.db", layout.studentMistakes)
        assertEquals("learner-mastery.db", layout.learnerMastery)
        assertEquals("high-school-knowledge.db", layout.highSchoolKnowledge)
        assertEquals(
            3,
            setOf(
                layout.studentMistakes,
                layout.learnerMastery,
                layout.highSchoolKnowledge,
            ).size,
        )
        assertFalse(
            LEGACY_MIGRATION_SOURCE_DATABASE_NAME in
                setOf(
                    layout.studentMistakes,
                    layout.learnerMastery,
                    layout.highSchoolKnowledge,
                ),
        )
    }

    @Test
    fun terminalCompatibilityMarkerHasNoDatabaseTargetAndIsNotExecutable() {
        assertFalse(
            ThreeAuthorityCutoverStage.CUTOVER_COMPLETE in
                ThreeAuthorityCutoverStage.legacyJournalPrefix,
        )
        assertThrows(IllegalStateException::class.java) {
            ThreeAuthorityCutoverStage.CUTOVER_COMPLETE.storageTarget
        }
    }

    @Test
    fun durablePrefixIsReverifiedBeforeItCanBeTrustedAgain() = runBlocking {
        val journal = MemoryJournal()
        VerifiedAuthorityCutoverPrefixCoordinator(
            layout = ThreeAuthorityDatabaseLayout(),
            steps = listOf(FakeStep(ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY)),
            journal = journal,
        ).migrateVerifiedPrefix()
        val staleSteps =
            listOf(
                FakeStep(
                    stage = ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY,
                    verification = { receipt ->
                        AuthorityStageVerification(
                            receiptFingerprint = receipt.receiptFingerprint,
                            destinationFingerprint = differentSha("changed-destination"),
                        )
                    },
                ),
            )

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                VerifiedAuthorityCutoverPrefixCoordinator(
                    layout = ThreeAuthorityDatabaseLayout(),
                    steps = staleSteps,
                    journal = journal,
                ).migrateVerifiedPrefix()
            }
        }
        Unit
    }

    @Test
    fun verifiedPrefixStopsBeforeStudentAuthorityWithoutAppendingSuccess() = runBlocking {
        val journal = MemoryJournal()
        val executed = mutableListOf<ThreeAuthorityCutoverStage>()
        val supported =
            ThreeAuthorityCutoverStage.legacyJournalPrefix
                .takeWhile {
                    it != ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED
                }
                .map { stage -> FakeStep(stage, executed = executed) }
        val coordinator =
            VerifiedAuthorityCutoverPrefixCoordinator(
                layout = ThreeAuthorityDatabaseLayout(),
                steps = supported,
                journal = journal,
            )

        val result = coordinator.migrateVerifiedPrefix()

        assertEquals(
            ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED,
            result.blockedAt,
        )
        assertEquals(supported.map(AuthorityCutoverStep::stage), result.durableStages)
        assertEquals(result.durableStages, executed)
        assertFalse(
            journal.receipts.any {
                it.stage == ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED ||
                    it.stage == ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED ||
                    it.stage == ThreeAuthorityCutoverStage.CUTOVER_COMPLETE
            },
        )
    }

    @Test
    fun legacyJournalMayHoldTheFullMigrationPrefixButNeverTerminalCompletion() = runBlocking {
        val journal = MemoryJournal()
        val steps =
            ThreeAuthorityCutoverStage.legacyJournalPrefix.map(::FakeStep)

        val result =
            VerifiedAuthorityCutoverPrefixCoordinator(
                layout = ThreeAuthorityDatabaseLayout(),
                steps = steps,
                journal = journal,
            ).migrateVerifiedPrefix()

        assertEquals(ThreeAuthorityCutoverStage.CUTOVER_COMPLETE, result.blockedAt)
        assertEquals(ThreeAuthorityCutoverStage.legacyJournalPrefix, result.durableStages)
        assertFalse(
            journal.receipts.any {
                it.stage == ThreeAuthorityCutoverStage.CUTOVER_COMPLETE
            },
        )
    }

    @Test
    fun verifiedPrefixRejectsMissingIntermediateStage() {
        val steps =
            listOf(
                FakeStep(ThreeAuthorityCutoverStage.LEGACY_SCHEMA_READY),
                FakeStep(ThreeAuthorityCutoverStage.KNOWLEDGE_PACKAGE_VERIFIED),
            )

        assertThrows(IllegalArgumentException::class.java) {
            VerifiedAuthorityCutoverPrefixCoordinator(
                layout = ThreeAuthorityDatabaseLayout(),
                steps = steps,
                journal = MemoryJournal(),
            )
        }
    }

    @Test
    fun prefixCoordinatorSurfaceHasNoTerminalOrDestructiveOperation() {
        val methodNames =
            VerifiedAuthorityCutoverPrefixCoordinator::class.java.methods
                .mapTo(hashSetOf()) { it.name }

        assertFalse(methodNames.any { it.contains("complete", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("rollback", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("reset", ignoreCase = true) })
        assertFalse(methodNames.any { it.contains("delete", ignoreCase = true) })
    }

    private class MemoryJournal : AuthorityCutoverJournal {
        val receipts = mutableListOf<AuthorityStageReceipt>()

        override suspend fun readOrdered(): List<AuthorityStageReceipt> = receipts.toList()

        override suspend fun appendIfAbsent(
            receipt: AuthorityStageReceipt,
        ): AuthorityStageReceipt {
            val existing = receipts.firstOrNull { it.stage == receipt.stage }
            if (existing != null) return existing
            receipts += receipt
            return receipt
        }
    }

    private class FakeStep(
        override val stage: ThreeAuthorityCutoverStage,
        private val executed: MutableList<ThreeAuthorityCutoverStage> = mutableListOf(),
        private val verification: (AuthorityStageReceipt) -> AuthorityStageVerification = {
            it.verification()
        },
    ) : AuthorityCutoverStep {
        override suspend fun migrate(
            previous: AuthorityStageReceipt?,
        ): AuthorityStageReceipt {
            executed += stage
            return AuthorityStageReceipt.create(
                stage = stage,
                targetDatabaseName =
                    ThreeAuthorityDatabaseLayout().cutoverStorageNameFor(
                        stage.storageTarget,
                    ),
                migratedRecordCount = stage.ordinal.toLong(),
                sourceCheckpoint = "checkpoint-${stage.ordinal}",
                destinationFingerprint = differentSha("destination-${stage.ordinal}"),
                completedAtEpochMillis = 1_000L + stage.ordinal,
                previousReceiptFingerprint = previous?.receiptFingerprint,
            )
        }

        override suspend fun verify(
            receipt: AuthorityStageReceipt,
        ): AuthorityStageVerification = verification(receipt)
    }

    private companion object {
        fun AuthorityStageReceipt.verification() =
            AuthorityStageVerification(
                receiptFingerprint = receiptFingerprint,
                destinationFingerprint = destinationFingerprint,
            )

        fun differentSha(value: String): String =
            CanonicalSha256("three-authority-cutover-test")
                .field("value", value)
                .finish()
    }
}
