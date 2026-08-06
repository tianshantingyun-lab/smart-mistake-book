package com.tingyun.smartmistakebook.core.data.authority

/**
 * Recovers the terminal generation only from generation-bound durable journal receipts.
 *
 * Before stage five exists, generation one is the sole canonical allocation. Its first durable
 * write atomically persists that value in the stage-five checkpoint. No caller supplies or
 * increments a generation number.
 */
internal object PersistentProductionCutoverGenerationResolver {
    fun resolve(
        layout: ThreeAuthorityDatabaseLayout,
        receipts: List<AuthorityStageReceipt>,
    ): Long {
        requireValidAuthorityCutoverJournalPrefix(layout, receipts)
        val boundReceipts =
            receipts.filter { receipt -> receipt.stage in GENERATION_BOUND_STAGES }
        if (boundReceipts.isEmpty()) {
            check(receipts.size <= VERIFIED_PREFIX_STAGE_COUNT) {
                "Terminal journal crossed stage four without a generation binding"
            }
            return INITIAL_CUTOVER_GENERATION
        }

        val prefixReceipt =
            checkNotNull(receipts.getOrNull(VERIFIED_PREFIX_STAGE_COUNT - 1)) {
                "Generation-bound terminal receipt has no verified prefix"
            }
        val bindings = boundReceipts.map(::parseBinding)
        val expected = bindings.first()
        check(bindings.all { binding -> binding == expected }) {
            "Terminal journal mixes cutover generations or verified prefixes"
        }
        check(expected.legacyPrefixFingerprint == prefixReceipt.receiptFingerprint) {
            "Terminal generation binding does not identify the durable verified prefix"
        }
        return expected.generation
    }

    fun requirePersisted(
        layout: ThreeAuthorityDatabaseLayout,
        receipts: List<AuthorityStageReceipt>,
        expectedGeneration: Long,
    ) {
        check(expectedGeneration > 0L)
        check(receipts.any { receipt -> receipt.stage in GENERATION_BOUND_STAGES }) {
            "Terminal migration did not persist its cutover generation"
        }
        check(resolve(layout, receipts) == expectedGeneration) {
            "Terminal migration changed its durable cutover generation"
        }
    }

    private fun parseBinding(receipt: AuthorityStageReceipt): DurableGenerationBinding {
        val match =
            checkNotNull(BOUND_CHECKPOINT.matchEntire(receipt.sourceCheckpoint)) {
                "${receipt.stage.name} has a malformed durable generation binding"
            }
        val generation =
            match.groupValues[1].toLongOrNull()
                ?.takeIf { value -> value > 0L }
                ?: error("${receipt.stage.name} has an invalid cutover generation")
        val source = match.groupValues[3]
        check(source.isNotBlank() && source.none(Char::isISOControl)) {
            "${receipt.stage.name} has an invalid raw source checkpoint"
        }
        return DurableGenerationBinding(
            generation = generation,
            legacyPrefixFingerprint = match.groupValues[2],
        )
    }
}

private data class DurableGenerationBinding(
    val generation: Long,
    val legacyPrefixFingerprint: String,
)

private const val VERIFIED_PREFIX_STAGE_COUNT = 4
private const val INITIAL_CUTOVER_GENERATION = 1L
private val GENERATION_BOUND_STAGES =
    setOf(
        ThreeAuthorityCutoverStage.STUDENT_DOCUMENTS_IMPORTED,
        ThreeAuthorityCutoverStage.MASTERY_FACTS_IMPORTED,
        ThreeAuthorityCutoverStage.MASTERY_BINDINGS_RECONCILED,
        ThreeAuthorityCutoverStage.MASTERY_PROJECTIONS_REBUILT,
        ThreeAuthorityCutoverStage.MASTERY_AUTHORITY_VERIFIED,
    )
private val BOUND_CHECKPOINT =
    Regex("generation:([1-9][0-9]*):prefix:([0-9a-f]{64}):source:(.{1,384})")
