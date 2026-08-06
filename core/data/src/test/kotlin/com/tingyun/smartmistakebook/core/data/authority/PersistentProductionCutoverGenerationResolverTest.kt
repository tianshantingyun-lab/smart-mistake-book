package com.tingyun.smartmistakebook.core.data.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PersistentProductionCutoverGenerationResolverTest {
    private val layout = ThreeAuthorityDatabaseLayout()

    @Test
    fun allocatesCanonicalFirstGenerationUntilStageFivePersistsIt() {
        assertEquals(
            1L,
            PersistentProductionCutoverGenerationResolver.resolve(layout, emptyList()),
        )
        assertEquals(
            1L,
            PersistentProductionCutoverGenerationResolver.resolve(
                layout,
                journal(generation = 7L, stageCount = 4),
            ),
        )
    }

    @Test
    fun recoversOneGenerationFromPartialAndCompleteTerminalJournals() {
        val partial = journal(generation = 7L, stageCount = 9)
        val complete = journal(generation = 7L, stageCount = 12)

        assertEquals(
            7L,
            PersistentProductionCutoverGenerationResolver.resolve(layout, partial),
        )
        assertEquals(
            7L,
            PersistentProductionCutoverGenerationResolver.resolve(layout, complete),
        )
        PersistentProductionCutoverGenerationResolver.requirePersisted(
            layout,
            complete,
            7L,
        )
    }

    @Test
    fun rejectsMixedOrMalformedDurableGenerationBindings() {
        val mixed = journal(generation = 7L, stageCount = 12).toMutableList()
        mixed[8] =
            replacementReceipt(
                receipts = mixed,
                index = 8,
                sourceCheckpoint = boundCheckpoint(8L, mixed[3].receiptFingerprint, "stage-9"),
            )
        rewriteSuccessors(mixed, 9)
        assertThrows(IllegalStateException::class.java) {
            PersistentProductionCutoverGenerationResolver.resolve(layout, mixed)
        }

        val malformed = journal(generation = 7L, stageCount = 5).toMutableList()
        malformed[4] =
            replacementReceipt(
                receipts = malformed,
                index = 4,
                sourceCheckpoint = "generation:7:prefix:not-a-digest:source:stage-5",
            )
        assertThrows(IllegalStateException::class.java) {
            PersistentProductionCutoverGenerationResolver.resolve(layout, malformed)
        }
    }

    private fun journal(
        generation: Long,
        stageCount: Int,
    ): List<AuthorityStageReceipt> {
        val receipts = mutableListOf<AuthorityStageReceipt>()
        ThreeAuthorityCutoverStage.legacyJournalPrefix
            .take(stageCount)
            .forEachIndexed { index, stage ->
                val prefix = receipts.getOrNull(3)?.receiptFingerprint
                val checkpoint =
                    if (stage in boundStages) {
                        boundCheckpoint(
                            generation,
                            checkNotNull(prefix),
                            "stage-${index + 1}",
                        )
                    } else {
                        "stage-${index + 1}"
                    }
                receipts +=
                    AuthorityStageReceipt.create(
                        stage = stage,
                        targetDatabaseName = layout.cutoverStorageNameFor(stage.storageTarget),
                        migratedRecordCount = index.toLong(),
                        sourceCheckpoint = checkpoint,
                        destinationFingerprint = "a".repeat(64),
                        completedAtEpochMillis = index.toLong(),
                        previousReceiptFingerprint =
                            receipts.lastOrNull()?.receiptFingerprint,
                    )
            }
        return receipts
    }

    private fun replacementReceipt(
        receipts: List<AuthorityStageReceipt>,
        index: Int,
        sourceCheckpoint: String,
    ): AuthorityStageReceipt {
        val existing = receipts[index]
        return AuthorityStageReceipt.create(
            stage = existing.stage,
            targetDatabaseName = existing.targetDatabaseName,
            migratedRecordCount = existing.migratedRecordCount,
            sourceCheckpoint = sourceCheckpoint,
            destinationFingerprint = existing.destinationFingerprint,
            completedAtEpochMillis = existing.completedAtEpochMillis,
            previousReceiptFingerprint = receipts.getOrNull(index - 1)?.receiptFingerprint,
        )
    }

    private fun rewriteSuccessors(
        receipts: MutableList<AuthorityStageReceipt>,
        startIndex: Int,
    ) {
        for (index in startIndex until receipts.size) {
            receipts[index] =
                replacementReceipt(
                    receipts = receipts,
                    index = index,
                    sourceCheckpoint = receipts[index].sourceCheckpoint,
                )
        }
    }

    private fun boundCheckpoint(
        generation: Long,
        prefix: String,
        source: String,
    ): String = "generation:$generation:prefix:$prefix:source:$source"
}

private val boundStages =
    setOf(
        ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED,
        ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED,
        ThreeAuthorityCutoverStage.MASTERY_BINDINGS_RECONCILED,
        ThreeAuthorityCutoverStage.MASTERY_PROJECTIONS_REBUILT,
        ThreeAuthorityCutoverStage.MASTERY_AUTHORITY_VERIFIED,
    )
