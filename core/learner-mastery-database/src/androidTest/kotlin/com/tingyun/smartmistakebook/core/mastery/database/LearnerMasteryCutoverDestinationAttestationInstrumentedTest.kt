package com.tingyun.smartmistakebook.core.mastery.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import java.util.function.LongSupplier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryCutoverDestinationAttestationInstrumentedTest {
    @Test
    fun physicalEmptyImportAttestsAllTerminalStagesWithoutCallerSummaries() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName =
                "mastery-attestation-complete-${System.nanoTime()}.mastery-test.db"
            val sourceGeneration = fingerprint("complete-source")
            context.deleteDatabase(databaseName)
            val database =
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                )
            val sourcePage =
                LearnerMasteryLegacySnapshotPage(
                    learnerId = LOCAL_LEARNER_ID,
                    sourceGeneration = sourceGeneration,
                    batchSequence = 1L,
                    sourcePageCanonicalFingerprint =
                        recomputeSourcePageFingerprint(
                            learnerId = LOCAL_LEARNER_ID,
                            afterExclusive = null,
                            snapshots = emptyList(),
                        ),
                    afterExclusive = null,
                    snapshots = emptyList(),
                    finalBatch = true,
                )
            assertEquals(
                LearnerMasteryLegacySnapshotPageDisposition.IMPORTED,
                RoomLearnerMasteryLegacyMigrationPort(
                    database = database,
                    learnerId = LOCAL_LEARNER_ID,
                ).applyPage(sourcePage).disposition,
            )
            val ledger =
                checkNotNull(
                    database.cutoverDao().recomputeCompletedMigrationLedger(
                        learnerId = LOCAL_LEARNER_ID,
                        sourceGeneration = sourceGeneration,
                    ),
                )
            val rebuild = database.masteryDao().prepareProjectionRebuild(2_000L)
            assertTrue(rebuild.completed)
            assertTrue(rebuild.activeGenerationAvailable)

            val receipt =
                LearnerMasteryFactsImportReceiptReferenceFactory.bind(
                    learnerId = LOCAL_LEARNER_ID,
                    cutoverGeneration = 9L,
                    legacyPrefixFingerprint = fingerprint("complete-prefix"),
                    migratedRecordCount = ledger.migratedObservationCount,
                    sourceGeneration = sourceGeneration,
                    sourceCheckpoint = "raw-page:1",
                    destinationFingerprint = ledger.destinationCanonicalFingerprint,
                    receiptFingerprint = fingerprint("complete-stage-nine-receipt"),
                    ownerKey = LearnerMasteryOwnerKey.INSTANCE,
                )
            val engine =
                LearnerMasteryCutoverDestinationAttestationEngine(
                    source = RoomLearnerMasteryCutoverDestinationReadSource(database),
                    nowEpochMillis = LongSupplier { 3_000L },
                    ownerKey = LearnerMasteryOwnerKey.INSTANCE,
                )
            try {
                val binding = engine.binding.bind(receipt)
                val bindings = drainBindings(engine, binding)
                val projections = drainProjections(engine, binding)
                val authority =
                    engine.authorityVerified.attest(
                        binding = binding,
                        factsImportReceipt = receipt,
                        bindingsReconciled = bindings,
                        projectionsRebuilt = projections,
                    )

                assertEquals(
                    LearnerMasteryCutoverDestinationStage.MASTERY_BINDINGS_RECONCILED,
                    bindings.stage,
                )
                assertEquals(
                    LearnerMasteryCutoverDestinationStage.MASTERY_PROJECTIONS_REBUILT,
                    projections.stage,
                )
                assertEquals(
                    LearnerMasteryCutoverDestinationStage.MASTERY_AUTHORITY_VERIFIED,
                    authority.stage,
                )
                assertTrue(bindings.hasValidFingerprint())
                assertTrue(projections.hasValidFingerprint())
                assertTrue(authority.hasValidFingerprint())
            } finally {
                engine.close()
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun freshPhysicalStoreFailsClosedWithoutStageNineLedgerOrActiveGeneration() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName =
                "mastery-attestation-empty-${System.nanoTime()}.mastery-test.db"
            context.deleteDatabase(databaseName)
            val source =
                RoomLearnerMasteryCutoverDestinationReadSource(
                    LearnerMasteryStoreFactory.openDatabaseForTest(
                        context = context,
                        databaseName = databaseName,
                    ),
                )
            val engine =
                LearnerMasteryCutoverDestinationAttestationEngine(
                    source = source,
                    nowEpochMillis = LongSupplier { 10_000L },
                    ownerKey = LearnerMasteryOwnerKey.INSTANCE,
                )
            try {
                val snapshot =
                    source.readSnapshot(
                        learnerId = LOCAL_LEARNER_ID,
                        sourceGeneration = fingerprint("missing-source"),
                    )
                assertFalse(snapshot.eventBindingsConsistent)
                assertFalse(snapshot.projectionsConsistent)
                assertTrue(
                    runCatching {
                        source.readSnapshot(
                            learnerId = "learner:other",
                            sourceGeneration = fingerprint("missing-source"),
                        )
                    }.isFailure,
                )

                val receipt =
                    LearnerMasteryFactsImportReceiptReference.issue(
                        ownerKey = LearnerMasteryOwnerKey.INSTANCE,
                        learnerId = LOCAL_LEARNER_ID,
                        cutoverGeneration = 3L,
                        legacyPrefixFingerprint = fingerprint("prefix"),
                        migratedRecordCount = 0L,
                        sourceGeneration = fingerprint("missing-source"),
                        sourceCheckpoint = "empty-stage-nine",
                        destinationFingerprint = fingerprint("invented-destination"),
                        receiptFingerprint = fingerprint("stage-nine-receipt"),
                    )
                assertTrue(runCatching { engine.binding.bind(receipt) }.isFailure)
            } finally {
                engine.close()
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun physicalSchemaScanIsBoundedAndContainsOwnerGuards() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName =
            "mastery-attestation-schema-${System.nanoTime()}.mastery-test.db"
        context.deleteDatabase(databaseName)
        val source =
            RoomLearnerMasteryCutoverDestinationReadSource(
                LearnerMasteryStoreFactory.openDatabaseForTest(
                    context = context,
                    databaseName = databaseName,
                ),
        )
        try {
            var afterExclusive: String? = null
            val stableKeys = mutableListOf<String>()
            for (pageIndex in 0 until 100) {
                val page =
                    source.readPage(
                        phase =
                            LearnerMasteryCutoverVerificationPhase.SCHEMA_OBJECTS,
                        learnerId = LOCAL_LEARNER_ID,
                        sourceGeneration = fingerprint("source"),
                        projectionGenerationId = 1L,
                        afterExclusive = afterExclusive,
                        limit = 7,
                    )
                assertTrue(page.size <= 7)
                assertTrue(
                    page.zipWithNext().all { (left, right) ->
                        left.stableKey < right.stableKey
                    },
                )
                page.forEach { row ->
                    assertTrue(
                        row.canonicalFingerprint.matches(Regex("[0-9a-f]{64}")),
                    )
                }
                stableKeys += page.map(LearnerMasteryCutoverVerificationRecord::stableKey)
                if (page.size < 7) break
                afterExclusive = page.last().stableKey
            }
            assertTrue(stableKeys.isNotEmpty())
            assertTrue(
                stableKeys.any {
                    it == "trigger:immutable_mastery_learning_event_update"
                },
            )
        } finally {
            source.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun fingerprint(value: String): String =
        CanonicalSha256("learner-mastery-cutover-attestation-instrumented-test")
            .field("value", value)
            .finish()

    private suspend fun drainBindings(
        engine: LearnerMasteryCutoverDestinationAttestationEngine,
        binding: LearnerMasteryCutoverDestinationBinding,
    ): LearnerMasteryBindingsReconciledAttestation {
        var cursor: LearnerMasteryCutoverVerificationCursor? = null
        repeat(MAX_TERMINAL_SCAN_PAGES) {
            when (
                val result =
                    engine.bindingsReconciled.verifyBindingsNext(
                        binding = binding,
                        cursor = cursor,
                        limit = TERMINAL_SCAN_PAGE_SIZE,
                    )
            ) {
                is LearnerMasteryCutoverAttestationProgress.Continue ->
                    cursor = result.cursor
                is LearnerMasteryCutoverAttestationProgress.Verified ->
                    return result.attestation
            }
        }
        error("Physical mastery binding attestation did not terminate")
    }

    private suspend fun drainProjections(
        engine: LearnerMasteryCutoverDestinationAttestationEngine,
        binding: LearnerMasteryCutoverDestinationBinding,
    ): LearnerMasteryProjectionsRebuiltAttestation {
        var cursor: LearnerMasteryCutoverVerificationCursor? = null
        repeat(MAX_TERMINAL_SCAN_PAGES) {
            when (
                val result =
                    engine.projectionsRebuilt.verifyProjectionsNext(
                        binding = binding,
                        cursor = cursor,
                        limit = TERMINAL_SCAN_PAGE_SIZE,
                    )
            ) {
                is LearnerMasteryCutoverAttestationProgress.Continue ->
                    cursor = result.cursor
                is LearnerMasteryCutoverAttestationProgress.Verified ->
                    return result.attestation
            }
        }
        error("Physical mastery projection attestation did not terminate")
    }

    private companion object {
        const val TERMINAL_SCAN_PAGE_SIZE = 16
        const val MAX_TERMINAL_SCAN_PAGES = 128
    }
}
