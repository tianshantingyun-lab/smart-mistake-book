package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryCutoverControlContractTest {
    @Test
    fun canonicalArtifactsMatchTheCoordinatorAndBindTheMigrationLedger() {
        val studentEvidence = "a".repeat(64)
        val masteryEvidence = "b".repeat(64)
        val fence =
            LearnerMasteryAuthorityCutoverFence.create(
                cutoverGeneration = 9,
                studentImportEvidenceFingerprint = studentEvidence,
                masteryImportEvidenceFingerprint = masteryEvidence,
            )
        val expectedIntent =
            CanonicalSha256("three-authority-cutover-intent-v1")
                .field("cutoverGeneration", 9L)
                .field("studentImportEvidenceFingerprint", studentEvidence)
                .field("masteryImportEvidenceFingerprint", masteryEvidence)
                .finish()
        val expectedFence =
            CanonicalSha256("authority-cutover-fence-v1")
                .field("authority", "LEARNER_MASTERY")
                .field("cutoverGeneration", 9L)
                .field("studentImportEvidenceFingerprint", studentEvidence)
                .field("masteryImportEvidenceFingerprint", masteryEvidence)
                .field("cutoverIntentFingerprint", expectedIntent)
                .finish()
        val ledger =
            LearnerMasteryImmutableMigrationLedgerDigest(
                learnerId = "learner-1",
                sourceGeneration = "legacy-v1",
                migratedObservationCount = 3,
                terminalBatchSequence = 2,
                terminalBatchFingerprint = "c".repeat(64),
                batchReceiptCount = 2,
                destinationCanonicalFingerprint = "d".repeat(64),
            )
        val receipt =
            LearnerMasteryAuthorityCutoverCompletionReceipt.create(fence, ledger)
        val expectedReceipt =
            CanonicalSha256("authority-cutover-completion-receipt-v1")
                .field("authority", "LEARNER_MASTERY")
                .field("cutoverGeneration", 9L)
                .field("cutoverIntentFingerprint", expectedIntent)
                .field("authorityFenceFingerprint", expectedFence)
                .finish()
        val expectedLedgerBinding =
            CanonicalSha256("learner-mastery-cutover-ledger-binding-v1")
                .field("authority", "LEARNER_MASTERY")
                .field("cutoverGeneration", 9L)
                .field("cutoverIntentFingerprint", expectedIntent)
                .field("authorityFenceFingerprint", expectedFence)
                .field("learnerId", "learner-1")
                .field("sourceGeneration", "legacy-v1")
                .field("migrationLedgerCanonicalDigest", "d".repeat(64))
                .field("receiptFingerprint", expectedReceipt)
                .finish()

        assertEquals(expectedIntent, fence.cutoverIntentFingerprint)
        assertEquals(expectedFence, fence.fenceFingerprint)
        assertEquals(expectedReceipt, receipt.receiptFingerprint)
        assertEquals(expectedLedgerBinding, receipt.ledgerBindingFingerprint)
        assertTrue(fence.hasValidFingerprint())
        assertTrue(receipt.hasValidFingerprint())
    }

    @Test
    fun canonicalArtifactsDetectFenceAndLedgerTampering() {
        val fence =
            LearnerMasteryAuthorityCutoverFence.create(
                cutoverGeneration = 1,
                studentImportEvidenceFingerprint = "a".repeat(64),
                masteryImportEvidenceFingerprint = "b".repeat(64),
            )
        val ledger =
            LearnerMasteryImmutableMigrationLedgerDigest(
                learnerId = "learner-1",
                sourceGeneration = "legacy-v1",
                migratedObservationCount = 1,
                terminalBatchSequence = 1,
                terminalBatchFingerprint = "c".repeat(64),
                batchReceiptCount = 1,
                destinationCanonicalFingerprint = "d".repeat(64),
            )
        val receipt =
            LearnerMasteryAuthorityCutoverCompletionReceipt.create(fence, ledger)

        assertFalse(
            fence.copy(fenceFingerprint = "e".repeat(64)).hasValidFingerprint(),
        )
        assertFalse(
            receipt.copy(cutoverGeneration = 2).hasValidFingerprint(),
        )
        assertFalse(
            receipt.copy(
                migrationLedgerCanonicalDigest = "f".repeat(64),
            ).hasValidFingerprint(),
        )
    }

    @Test
    fun ownerSurfaceIsLearnerBoundAndHasNoMutationOrDatabaseEscapeHatches() {
        assertEquals(
            setOf(
                "appendCompletionReceiptIfAbsent",
                "appendCutoverFenceIfAbsent",
                "readCompletionReceipt",
                "readCutoverFence",
                "recomputeCompletedMigrationLedger",
            ),
            LearnerMasteryCutoverControlPort::class.java.methods
                .filter {
                    it.declaringClass == LearnerMasteryCutoverControlPort::class.java &&
                        !it.isSynthetic
                }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val forbiddenFragments = setOf("update", "delete", "reset", "sqlite", "room", "legacy")
        LearnerMasteryCutoverControlPort::class.java.declaredMethods.forEach { method ->
            val signature =
                buildString {
                    append(method.name)
                    append(method.returnType.name)
                    method.parameterTypes.forEach { append(it.name) }
                }
            forbiddenFragments.forEach { fragment ->
                assertFalse(signature.contains(fragment, ignoreCase = true))
            }
        }

        val productionFactory =
            LearnerMasteryCutoverControlPortFactory::class.java.declaredMethods
                .single { method ->
                    method.name == "open" &&
                        method.parameterTypes.lastOrNull() == LearnerMasteryOwnerKey::class.java
                }
        assertEquals(
            LearnerMasteryCutoverControlPort::class.java,
            productionFactory.returnType,
        )
        assertEquals(
            listOf(
                android.content.Context::class.java,
                String::class.java,
                LearnerMasteryOwnerKey::class.java,
            ),
            productionFactory.parameterTypes.toList(),
        )
        assertTrue(
            LearnerMasteryOwnerKey::class.java.declaredConstructors.all {
                Modifier.isPrivate(it.modifiers)
            },
        )
        val bridgeMethod =
            CoreDataLearnerMasteryOwnerBridge::class.java.declaredMethods
                .single { it.name == "openCutoverControl" }
        assertFalse(Modifier.isPublic(bridgeMethod.modifiers))
    }

    @Test
    fun rawSnapshotMigrationSurfaceIsOwnerOnlyAndHasNoDatabaseEscapeHatches() {
        assertEquals(
            setOf("applyPage", "getLearnerId"),
            LearnerMasteryLegacyMigrationPort::class.java.methods
                .filter {
                    it.declaringClass == LearnerMasteryLegacyMigrationPort::class.java &&
                        !it.isSynthetic
                }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val forbiddenFragments =
            setOf("dao", "sqlite", "room", "sql", "update", "delete", "reset", "project")
        LearnerMasteryLegacyMigrationPort::class.java.declaredMethods.forEach { method ->
            val signature =
                buildString {
                    append(method.name)
                    append(method.returnType.name)
                    method.parameterTypes.forEach { append(it.name) }
                }
            forbiddenFragments.forEach { fragment ->
                assertFalse(signature.contains(fragment, ignoreCase = true))
            }
        }
        val bridgeMethod =
            CoreDataLearnerMasteryOwnerBridge::class.java.declaredMethods
                .single { it.name == "openLegacyMigration" }
        assertFalse(Modifier.isPublic(bridgeMethod.modifiers))
    }

    @Test
    fun productionMigrationRegistryCoversEveryHistoricalVersionExactlyOnce() {
        val expected =
            (1 until LEARNER_MASTERY_DATABASE_VERSION).map { startVersion ->
                startVersion to startVersion + 1
            }
        val registered =
            LEARNER_MASTERY_MIGRATIONS.map { migration ->
                migration.startVersion to migration.endVersion
            }

        assertEquals(expected, registered)
    }

    @Test
    fun currentSchemaRetainsImmutableRawLegacyAuditTables() {
        assertEquals(22, LEARNER_MASTERY_DATABASE_VERSION)
        assertEquals(2, RAW_SNAPSHOT_DESTINATION_LEDGER_VERSION)
        assertEquals(2, RAW_SNAPSHOT_DESTINATION_CANONICAL_LAYOUT_VERSION)
        assertTrue(LEARNER_MASTERY_CUTOVER_FENCE_TABLE in LEARNER_MASTERY_TABLE_NAMES)
        assertTrue(
            LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE in
                LEARNER_MASTERY_TABLE_NAMES,
        )
        assertTrue(
            LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE in
                LEARNER_MASTERY_TABLE_NAMES,
        )
        assertTrue(
            LEARNER_MASTERY_MIGRATION_DESTINATION_RECORD_TABLE in
                LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES,
        )
        assertTrue(
            LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE in
                LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES,
        )
        assertTrue(
            LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE in
                LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES,
        )
        assertTrue(
            setOf(
                LEARNER_MASTERY_CUTOVER_FENCE_TABLE,
                LEARNER_MASTERY_CUTOVER_COMPLETION_RECEIPT_TABLE,
                LEARNER_MASTERY_LEGACY_SNAPSHOT_PAGE_TABLE,
                LEARNER_MASTERY_LEGACY_OBSERVATION_SNAPSHOT_TABLE,
                LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
            ).all(LEARNER_MASTERY_IMMUTABLE_TABLE_NAMES::contains),
        )
    }

    @Test
    fun rawLedgerLayoutBindsExplicitIndicesOrderAndStoredFields() {
        val legacyDynamicLayout =
            CanonicalSha256("learner-mastery-raw-snapshot-ledger-v2")
                .field("snapshot[0].sourceFactId", "fact-a")
                .field("snapshot[1].sourceFactId", "fact-b")
                .finish()
        val currentLayout =
            rawLayoutFingerprint(firstFact = "fact-a", secondFact = "fact-b")

        assertNotEquals(legacyDynamicLayout, currentLayout)
        assertNotEquals(
            currentLayout,
            rawLayoutFingerprint(firstFact = "fact-b", secondFact = "fact-a"),
        )
        assertNotEquals(
            currentLayout,
            rawLayoutFingerprint(
                firstFact = "fact-a",
                secondFact = "fact-b",
                storedFingerprint = "e".repeat(64),
            ),
        )
    }
}

private fun rawLayoutFingerprint(
    firstFact: String,
    secondFact: String,
    storedFingerprint: String = "d".repeat(64),
): String =
    CanonicalSha256("learner-mastery-raw-snapshot-ledger-v2")
        .field(
            "destinationCanonicalLayoutVersion",
            RAW_SNAPSHOT_DESTINATION_CANONICAL_LAYOUT_VERSION,
        )
        .field("snapshot.index", 0L)
        .field("snapshot.sourceFactId", firstFact)
        .field("snapshot.snapshotCanonicalFingerprint", storedFingerprint)
        .field("snapshot.index", 1L)
        .field("snapshot.sourceFactId", secondFact)
        .field("snapshot.snapshotCanonicalFingerprint", storedFingerprint)
        .finish()
