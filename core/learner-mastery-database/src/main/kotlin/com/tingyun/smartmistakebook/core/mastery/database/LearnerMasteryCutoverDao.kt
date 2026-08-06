package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.lang.Math.addExact
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal data class LearnerMasteryLedgerRecomputeDiagnostics(
    var countReadNanos: Long = 0L,
    var pageReadNanos: Long = 0L,
    var snapshotReadNanos: Long = 0L,
    var sourceRecordValidationNanos: Long = 0L,
    var pageReceiptValidationNanos: Long = 0L,
    var destinationDigestNanos: Long = 0L,
)

private val legacySnapshotValidationExecutor: ExecutorService by lazy {
    Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceIn(1, 8),
    ) { runnable ->
        Thread(runnable, "learner-mastery-legacy-validation").apply { isDaemon = true }
    }
}

@Dao
internal abstract class LearnerMasteryCutoverDao {
    private val legacyResponseSummaryCipher: LearnerMasteryLegacyResponseSummaryCipher by lazy {
        AndroidKeystoreLearnerMasteryLegacyResponseSummaryCipher()
    }

    @Query(
        """
        SELECT singleton_key, cutover_generation,
               student_import_evidence_fingerprint,
               mastery_import_evidence_fingerprint,
               cutover_intent_fingerprint, fence_fingerprint
        FROM mastery_cutover_fence
        ORDER BY singleton_key
        """,
    )
    protected abstract suspend fun readCutoverFenceRows():
        List<LearnerMasteryCutoverFenceEntity>

    @Query(
        """
        SELECT singleton_key, cutover_generation, cutover_intent_fingerprint,
               authority_fence_fingerprint, learner_id, source_generation,
               migration_ledger_canonical_digest, ledger_binding_fingerprint,
               receipt_fingerprint
        FROM mastery_cutover_completion_receipt
        ORDER BY singleton_key
        """,
    )
    protected abstract suspend fun readCompletionReceiptRows():
        List<LearnerMasteryCutoverCompletionReceiptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCutoverFence(
        fence: LearnerMasteryCutoverFenceEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCompletionReceipt(
        receipt: LearnerMasteryCutoverCompletionReceiptEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLegacySnapshotPage(
        page: LearnerMasteryLegacySnapshotPageEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLegacyObservationSnapshot(
        snapshot: LearnerMasteryLegacyObservationSnapshotEntity,
    ): Long

    @Query(
        """
        SELECT *
        FROM mastery_legacy_observation_snapshot_page
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
          AND batch_sequence = :batchSequence
        """,
    )
    protected abstract suspend fun readLegacySnapshotPage(
        learnerId: String,
        sourceGeneration: String,
        batchSequence: Long,
    ): LearnerMasteryLegacySnapshotPageEntity?

    @Query(
        """
        SELECT *
        FROM mastery_legacy_observation_snapshot_page
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
          AND source_page_canonical_fingerprint = :sourcePageCanonicalFingerprint
        """,
    )
    protected abstract suspend fun readLegacySnapshotPageBySourceFingerprint(
        learnerId: String,
        sourceGeneration: String,
        sourcePageCanonicalFingerprint: String,
    ): LearnerMasteryLegacySnapshotPageEntity?

    @Query(
        """
        SELECT *
        FROM mastery_legacy_observation_snapshot_page
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
        ORDER BY batch_sequence DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLatestLegacySnapshotPage(
        learnerId: String,
        sourceGeneration: String,
    ): LearnerMasteryLegacySnapshotPageEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1
            FROM mastery_legacy_observation_snapshot_page
            WHERE learner_id = :learnerId
              AND source_generation = :sourceGeneration
              AND final_batch = 1
        )
        """,
    )
    protected abstract suspend fun hasTerminalLegacySnapshotPage(
        learnerId: String,
        sourceGeneration: String,
    ): Boolean

    @Query(
        """
        SELECT *
        FROM mastery_legacy_observation_snapshot
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
          AND batch_sequence = :batchSequence
        ORDER BY snapshot_ordinal ASC
        """,
    )
    protected abstract suspend fun readLegacySnapshotsForPage(
        learnerId: String,
        sourceGeneration: String,
        batchSequence: Long,
    ): List<LearnerMasteryLegacyObservationSnapshotEntity>

    @Query(
        """
        SELECT *
        FROM mastery_legacy_observation_snapshot
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
          AND source_fact_id = :sourceFactId
        """,
    )
    protected abstract suspend fun readLegacySnapshotBySourceFactId(
        learnerId: String,
        sourceGeneration: String,
        sourceFactId: String,
    ): LearnerMasteryLegacyObservationSnapshotEntity?

    @Query(
        """
        SELECT *
        FROM mastery_legacy_observation_snapshot_page
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
          AND batch_sequence > :afterBatchSequence
        ORDER BY batch_sequence ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readLegacySnapshotPageChunk(
        learnerId: String,
        sourceGeneration: String,
        afterBatchSequence: Long,
        limit: Int,
    ): List<LearnerMasteryLegacySnapshotPageEntity>

    @Query(
        """
        SELECT COUNT(*)
        FROM mastery_legacy_observation_snapshot_page
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
        """,
    )
    protected abstract suspend fun countAllLegacySnapshotPages(
        learnerId: String,
        sourceGeneration: String,
    ): Long

    @Query(
        """
        SELECT COUNT(*)
        FROM mastery_legacy_observation_snapshot
        WHERE learner_id = :learnerId
          AND source_generation = :sourceGeneration
        """,
    )
    protected abstract suspend fun countAllLegacySnapshots(
        learnerId: String,
        sourceGeneration: String,
    ): Long

    open suspend fun readCutoverFence(): LearnerMasteryCutoverFenceEntity? =
        requireCutoverFenceSingleton(readCutoverFenceRows())

    @Transaction
    open suspend fun appendCutoverFenceIfAbsent(
        candidate: LearnerMasteryCutoverFenceEntity,
    ): LearnerMasteryCutoverFenceEntity {
        readCutoverFence()?.let { return it }
        check(readCompletionReceiptRows().isEmpty()) {
            "Mastery cutover completion receipt exists without its fence"
        }
        insertCutoverFence(candidate)
        return checkNotNull(readCutoverFence()) {
            "Mastery cutover fence was not durable after append"
        }
    }

    @Transaction
    open suspend fun appendLegacySnapshotPage(
        page: LearnerMasteryLegacySnapshotPage,
    ): LearnerMasteryLegacySnapshotPageResult {
        val expectedPage = page.toEntity()
        val workerCount =
            Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val chunkSize =
            maxOf(1, (page.snapshots.size + workerCount - 1) / workerCount)
        val snapshotFutures = page.snapshots.indices.chunked(chunkSize).map { chunk ->
            legacySnapshotValidationExecutor.submit(
                Callable {
                    chunk.map { ordinal ->
                        ordinal to page.snapshots[ordinal].toEntity(
                            sourceGeneration = page.sourceGeneration,
                            batchSequence = page.batchSequence,
                            snapshotOrdinal = ordinal,
                            cipher = legacyResponseSummaryCipher,
                        )
                    }
                },
            )
        }
        val expectedSnapshots = snapshotFutures.flatMap { future ->
            try {
                future.get()
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
        }.sortedBy { it.first }.map { it.second }
        readLegacySnapshotPage(
            learnerId = page.learnerId,
            sourceGeneration = page.sourceGeneration,
            batchSequence = page.batchSequence,
        )?.let { persisted ->
            val persistedSnapshots =
                readLegacySnapshotsForPage(
                    learnerId = page.learnerId,
                    sourceGeneration = page.sourceGeneration,
                    batchSequence = page.batchSequence,
                )
            return page.result(
                if (
                    persisted == expectedPage &&
                    persistedSnapshots.sameEncryptedSnapshotSemanticsAs(
                        expectedSnapshots,
                        legacyResponseSummaryCipher,
                    )
                ) {
                    LearnerMasteryLegacySnapshotPageDisposition.DUPLICATE
                } else {
                    LearnerMasteryLegacySnapshotPageDisposition.CONFLICT
                },
            )
        }
        if (
            readLegacySnapshotPageBySourceFingerprint(
                learnerId = page.learnerId,
                sourceGeneration = page.sourceGeneration,
                sourcePageCanonicalFingerprint = page.sourcePageCanonicalFingerprint,
            ) != null ||
            expectedSnapshots.any { snapshot ->
                readLegacySnapshotBySourceFactId(
                    learnerId = snapshot.learnerId,
                    sourceGeneration = snapshot.sourceGeneration,
                    sourceFactId = snapshot.sourceFactId,
                ) != null
            }
        ) {
            return page.result(LearnerMasteryLegacySnapshotPageDisposition.CONFLICT)
        }
        if (
            readCutoverFence() != null ||
            hasTerminalLegacySnapshotPage(
                learnerId = page.learnerId,
                sourceGeneration = page.sourceGeneration,
            )
        ) {
            return page.result(LearnerMasteryLegacySnapshotPageDisposition.OUT_OF_ORDER)
        }

        val latest =
            readLatestLegacySnapshotPage(
                learnerId = page.learnerId,
                sourceGeneration = page.sourceGeneration,
            )
        val expectedSequence = latest?.batchSequence?.let { addExact(it, 1L) } ?: 1L
        val expectedCursor = latest?.terminalCursor()
        if (
            page.batchSequence != expectedSequence ||
            page.afterExclusive != expectedCursor
        ) {
            return page.result(LearnerMasteryLegacySnapshotPageDisposition.OUT_OF_ORDER)
        }

        expectedSnapshots.forEach { snapshot ->
            check(insertLegacyObservationSnapshot(snapshot) != -1L) {
                "Raw legacy mastery snapshot was not appended"
            }
        }
        check(insertLegacySnapshotPage(expectedPage) != -1L) {
            "Raw legacy mastery snapshot page was not appended"
        }
        val persistedPage =
            checkNotNull(
                readLegacySnapshotPage(
                    learnerId = page.learnerId,
                    sourceGeneration = page.sourceGeneration,
                    batchSequence = page.batchSequence,
                ),
            ) {
                "Raw legacy mastery snapshot page was not durable"
            }
        val persistedSnapshots =
            readLegacySnapshotsForPage(
                learnerId = page.learnerId,
                sourceGeneration = page.sourceGeneration,
                batchSequence = page.batchSequence,
            )
        check(
            persistedPage == expectedPage &&
                persistedSnapshots.sameEncryptedSnapshotSemanticsAs(
                    expectedSnapshots,
                    legacyResponseSummaryCipher,
                ),
        ) {
            "Raw legacy mastery snapshot page changed during append"
        }
        return page.result(LearnerMasteryLegacySnapshotPageDisposition.IMPORTED)
    }

    @Transaction
    open suspend fun readBoundCompletionReceipt():
        LearnerMasteryCutoverCompletionReceiptEntity? {
        val receipt =
            requireCompletionReceiptSingleton(readCompletionReceiptRows())
                ?: return null
        val fence =
            checkNotNull(readCutoverFence()) {
                "Mastery cutover completion receipt exists without its fence"
            }
        check(receipt.isBoundTo(fence)) {
            "Mastery cutover completion receipt is not bound to its durable fence"
        }
        val ledger =
            recomputeRawMigrationLedger(
                learnerId = receipt.learnerId,
                sourceGeneration = receipt.sourceGeneration,
            )
        checkNotNull(ledger) {
            "Mastery cutover completion receipt names an incomplete migration ledger"
        }
        check(
            receipt.migrationLedgerCanonicalDigest ==
                ledger.destinationCanonicalFingerprint,
        ) {
            "Mastery cutover completion receipt changed its immutable migration ledger"
        }
        return receipt
    }

    @Transaction
    open suspend fun appendCompletionReceiptIfAbsent(
        candidate: LearnerMasteryCutoverCompletionReceiptEntity,
    ): LearnerMasteryCutoverCompletionReceiptEntity {
        readBoundCompletionReceipt()?.let { return it }
        val fence =
            checkNotNull(readCutoverFence()) {
                "Mastery cutover completion receipt requires its durable fence"
            }
        check(candidate.isBoundTo(fence)) {
            "Mastery cutover completion receipt changed its durable fence"
        }
        val ledger =
            recomputeRawMigrationLedger(
                learnerId = candidate.learnerId,
                sourceGeneration = candidate.sourceGeneration,
            )
        checkNotNull(ledger) {
            "Mastery cutover completion receipt requires a completed migration ledger"
        }
        check(
            candidate.migrationLedgerCanonicalDigest ==
                ledger.destinationCanonicalFingerprint,
        ) {
            "Mastery cutover completion receipt does not bind the actual migration ledger"
        }
        insertCompletionReceipt(candidate)
        return checkNotNull(readBoundCompletionReceipt()) {
            "Mastery cutover completion receipt was not durable after append"
        }
    }

    @Transaction
    open suspend fun recomputeCompletedMigrationLedger(
        learnerId: String,
        sourceGeneration: String,
    ): LearnerMasteryImmutableMigrationLedgerDigest? =
        recomputeRawMigrationLedger(learnerId, sourceGeneration)

    @Transaction
    open suspend fun recomputeCompletedMigrationLedgerWithDiagnostics(
        learnerId: String,
        sourceGeneration: String,
    ): Pair<LearnerMasteryImmutableMigrationLedgerDigest?, LearnerMasteryLedgerRecomputeDiagnostics> {
        val diagnostics = LearnerMasteryLedgerRecomputeDiagnostics()
        return recomputeRawMigrationLedger(
            learnerId = learnerId,
            sourceGeneration = sourceGeneration,
            diagnostics = diagnostics,
        ) to diagnostics
    }

    private suspend fun recomputeRawMigrationLedger(
        learnerId: String,
        sourceGeneration: String,
        diagnostics: LearnerMasteryLedgerRecomputeDiagnostics? = null,
    ): LearnerMasteryImmutableMigrationLedgerDigest? {
        val countStartedAt = diagnostics.startedAt()
        val persistedPageCount =
            countAllLegacySnapshotPages(
                learnerId = learnerId,
                sourceGeneration = sourceGeneration,
            )
        val persistedSnapshotCount =
            countAllLegacySnapshots(
                learnerId = learnerId,
                sourceGeneration = sourceGeneration,
            )
        diagnostics?.countReadNanos = countStartedAt.elapsedSince()
        return recomputeCompletedMigrationLedger(
            learnerId = learnerId,
            sourceGeneration = sourceGeneration,
            persistedPageCount = persistedPageCount,
            persistedSnapshotCount = persistedSnapshotCount,
            readPageChunk = { afterBatchSequence ->
                readLegacySnapshotPageChunk(
                    learnerId = learnerId,
                    sourceGeneration = sourceGeneration,
                    afterBatchSequence = afterBatchSequence,
                    limit = MIGRATION_LEDGER_PAGE_READ_BATCH,
                )
            },
            readSnapshotsForPage = { batchSequence ->
                readLegacySnapshotsForPage(
                    learnerId = learnerId,
                    sourceGeneration = sourceGeneration,
                    batchSequence = batchSequence,
                )
            },
            legacyResponseSummaryCipher = legacyResponseSummaryCipher,
            diagnostics = diagnostics,
        )
    }
}

private fun requireCutoverFenceSingleton(
    rows: List<LearnerMasteryCutoverFenceEntity>,
): LearnerMasteryCutoverFenceEntity? {
    check(rows.size <= 1) { "Mastery cutover fence table is not a singleton" }
    return rows.singleOrNull()?.also { row ->
        check(row.singletonKey == LEARNER_MASTERY_CUTOVER_SINGLETON_KEY) {
            "Mastery cutover fence has an invalid singleton key"
        }
    }
}

private fun requireCompletionReceiptSingleton(
    rows: List<LearnerMasteryCutoverCompletionReceiptEntity>,
): LearnerMasteryCutoverCompletionReceiptEntity? {
    check(rows.size <= 1) {
        "Mastery cutover completion receipt table is not a singleton"
    }
    return rows.singleOrNull()?.also { row ->
        check(row.singletonKey == LEARNER_MASTERY_CUTOVER_SINGLETON_KEY) {
            "Mastery cutover completion receipt has an invalid singleton key"
        }
    }
}

private fun LearnerMasteryCutoverCompletionReceiptEntity.isBoundTo(
    fence: LearnerMasteryCutoverFenceEntity,
): Boolean =
    singletonKey == fence.singletonKey &&
        cutoverGeneration == fence.cutoverGeneration &&
        cutoverIntentFingerprint == fence.cutoverIntentFingerprint &&
        authorityFenceFingerprint == fence.fenceFingerprint

private suspend fun recomputeCompletedMigrationLedger(
    learnerId: String,
    sourceGeneration: String,
    persistedPageCount: Long,
    persistedSnapshotCount: Long,
    readPageChunk:
        suspend (afterBatchSequence: Long) ->
            List<LearnerMasteryLegacySnapshotPageEntity>,
    readSnapshotsForPage:
        suspend (batchSequence: Long) ->
            List<LearnerMasteryLegacyObservationSnapshotEntity>,
    legacyResponseSummaryCipher: LearnerMasteryLegacyResponseSummaryCipher,
    diagnostics: LearnerMasteryLedgerRecomputeDiagnostics? = null,
): LearnerMasteryImmutableMigrationLedgerDigest? {
    requireMasteryIdentity(learnerId, "Learner id")
    requireMasteryVersion(sourceGeneration, "Legacy source generation")
    check(persistedPageCount in 0L..Int.MAX_VALUE.toLong()) {
        "Raw legacy mastery page count is outside its bounded contract"
    }
    if (persistedPageCount == 0L) {
        check(persistedSnapshotCount == 0L) {
            "Raw legacy mastery snapshots exist without page receipts"
        }
        return null
    }

    var expectedSequence = 1L
    var previousCursor: LearnerMasteryLegacyMigrationCursor? = null
    var terminalPage: LearnerMasteryLegacySnapshotPageEntity? = null
    var snapshotIndex = 0L
    val destinationDigest =
        CanonicalSha256.repeatingSchema(RAW_SNAPSHOT_MIGRATION_LEDGER_FINGERPRINT_DOMAIN)
            .field("destinationLedgerVersion", RAW_SNAPSHOT_DESTINATION_LEDGER_VERSION)
            .field(
                "destinationCanonicalLayoutVersion",
                RAW_SNAPSHOT_DESTINATION_CANONICAL_LAYOUT_VERSION,
            )
            .field("learnerId", learnerId)
            .field("sourceGeneration", sourceGeneration)
            .field("pageReceiptCount", persistedPageCount)
            .field("rawSnapshotCount", persistedSnapshotCount)

    var pageIndex = 0L
    var pageChunkAfterSequence = 0L
    while (pageIndex < persistedPageCount) {
        val pageReadStartedAt = diagnostics.startedAt()
        val pageChunk = readPageChunk(pageChunkAfterSequence)
        diagnostics?.pageReadNanos += pageReadStartedAt.elapsedSince()
        check(pageChunk.isNotEmpty() && pageChunk.size <= MIGRATION_LEDGER_PAGE_READ_BATCH) {
            "Raw legacy mastery page chunk is incomplete or unbounded"
        }
        pageChunk.forEach { page ->
            val pageAfter = previousCursor
            check(terminalPage == null) {
                "Raw legacy mastery ledger contains a page after its terminal receipt"
            }
            check(
                page.learnerId == learnerId &&
                    page.sourceGeneration == sourceGeneration,
            ) {
                "Raw legacy mastery page belongs to another ledger"
            }
            check(page.batchSequence == expectedSequence) {
                "Raw legacy mastery page sequence is discontinuous"
            }
            check(page.afterCursor() == pageAfter) {
                "Raw legacy mastery page cursor chain is discontinuous"
            }
            check(page.snapshotCount in 0..MAX_LEGACY_SNAPSHOTS_PER_PAGE) {
                "Raw legacy mastery page count is outside its bounded contract"
            }
            check(page.snapshotCount > 0 || (page.batchSequence == 1L && page.finalBatch)) {
                "Only an empty source may have an empty terminal page"
            }
            requireMasteryFingerprint(
                page.sourcePageCanonicalFingerprint,
                "Raw legacy mastery source-page fingerprint",
            )
            requireMasteryFingerprint(
                page.pageReceiptCanonicalFingerprint,
                "Raw legacy mastery page receipt fingerprint",
            )

            val snapshotReadStartedAt = diagnostics.startedAt()
            val pageSnapshots = readSnapshotsForPage(page.batchSequence)
            diagnostics?.snapshotReadNanos += snapshotReadStartedAt.elapsedSince()
            check(pageSnapshots.size == page.snapshotCount) {
                "Raw legacy mastery page count does not match its physical snapshots"
            }

            val sourceValidationStartedAt = diagnostics.startedAt()
            var previousSnapshot: LearnerMasteryLegacyObservationSnapshotEntity? = null
            val responseSummaries = ArrayList<String>(pageSnapshots.size)
            val workerCount =
                Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val chunkSize =
                maxOf(1, (pageSnapshots.size + workerCount - 1) / workerCount)
            val futures = pageSnapshots.indices.chunked(chunkSize).map { chunk ->
                legacySnapshotValidationExecutor.submit(
                    Callable {
                        chunk.map { ordinal ->
                            val snapshot = pageSnapshots[ordinal]
                            val responseSummary = snapshot
                                .requireDecryptedResponseSummary(legacyResponseSummaryCipher)
                            snapshot.validateStoredSourceRecord(
                                learnerId = learnerId,
                                sourceGeneration = sourceGeneration,
                                batchSequence = page.batchSequence,
                                expectedOrdinal = ordinal,
                                responseSummary = responseSummary,
                            )
                            ordinal to responseSummary
                        }
                    },
                )
            }
            val validationResults = futures.flatMap { future ->
                try {
                    future.get()
                } catch (failure: ExecutionException) {
                    throw failure.cause ?: failure
                }
            }.sortedBy { it.first }
            validationResults.forEach { (ordinal, responseSummary) ->
                responseSummaries += responseSummary
                val snapshot = pageSnapshots[ordinal]
                previousSnapshot?.let { previous ->
                    check(snapshot.isStrictlyAfter(previous)) {
                        "Raw legacy mastery page is not strictly keyset ordered"
                    }
                }
                if (ordinal == 0 && pageAfter != null) {
                    check(snapshot.isStrictlyAfter(pageAfter)) {
                        "Raw legacy mastery page did not advance beyond its source cursor"
                    }
                }
                previousSnapshot = snapshot
            }
            diagnostics?.sourceRecordValidationNanos +=
                sourceValidationStartedAt.elapsedSince()

            val expectedTerminalCursor =
                previousSnapshot?.toMigrationCursor() ?: pageAfter
            check(page.hasTerminalCursor(expectedTerminalCursor)) {
                "Raw legacy mastery terminal cursor does not match its snapshots"
            }

            val pageReceiptStartedAt = diagnostics.startedAt()
            val sourcePageDigest =
                CanonicalSha256(LEGACY_SOURCE_PAGE_FINGERPRINT_DOMAIN)
                    .field("manifestVersion", LEGACY_EXACT_MANIFEST_VERSION)
                    .field("learnerId", learnerId)
                    .nullableLongField(
                        "afterOccurredAtEpochMillis",
                        page.afterOccurredAtEpochMillis,
                    )
                    .nullableField("afterSourceFactId", page.afterSourceFactId)
                    .field("recordCount", pageSnapshots.size)
            val pageReceiptDigest =
                CanonicalSha256(LEGACY_SNAPSHOT_PAGE_RECEIPT_DOMAIN)
                    .field("learnerId", learnerId)
                    .field("sourceGeneration", sourceGeneration)
                    .field("batchSequence", page.batchSequence)
                    .field(
                        "sourcePageCanonicalFingerprint",
                        page.sourcePageCanonicalFingerprint,
                    )
                    .nullableLongField(
                        "afterOccurredAtEpochMillis",
                        page.afterOccurredAtEpochMillis,
                    )
                    .nullableField("afterSourceFactId", page.afterSourceFactId)
                    .field("recordCount", pageSnapshots.size)
                    .field("finalBatch", page.finalBatch)
            pageSnapshots.forEachIndexed { ordinal, snapshot ->
                sourcePageDigest.field(
                    "record[$ordinal]",
                    snapshot.sourceRecordCanonicalFingerprint,
                )
                pageReceiptDigest.field(
                    "sourceRecord[$ordinal]",
                    snapshot.sourceRecordCanonicalFingerprint,
                )
            }
            check(sourcePageDigest.finish() == page.sourcePageCanonicalFingerprint) {
                "Raw legacy mastery source page changed after import"
            }
            check(pageReceiptDigest.finish() == page.pageReceiptCanonicalFingerprint) {
                "Raw legacy mastery page receipt changed after import"
            }
            diagnostics?.pageReceiptValidationNanos +=
                pageReceiptStartedAt.elapsedSince()

            val destinationDigestStartedAt = diagnostics.startedAt()
            destinationDigest
                .field("page.index", pageIndex)
                .field("page.learnerId", page.learnerId)
                .field("page.sourceGeneration", page.sourceGeneration)
                .field("page.batchSequence", page.batchSequence)
                .field(
                    "page.sourcePageCanonicalFingerprint",
                    page.sourcePageCanonicalFingerprint,
                )
                .nullableLongField(
                    "page.afterOccurredAtEpochMillis",
                    page.afterOccurredAtEpochMillis,
                )
                .nullableField(
                    "page.afterSourceFactId",
                    page.afterSourceFactId,
                )
                .nullableLongField(
                    "page.terminalOccurredAtEpochMillis",
                    page.terminalOccurredAtEpochMillis,
                )
                .nullableField(
                    "page.terminalSourceFactId",
                    page.terminalSourceFactId,
                )
                .field("page.snapshotCount", page.snapshotCount)
                .field("page.finalBatch", page.finalBatch)
                .field(
                    "page.pageReceiptCanonicalFingerprint",
                    page.pageReceiptCanonicalFingerprint,
                )

            pageSnapshots.forEachIndexed { ordinal, snapshot ->
                destinationDigest.appendSnapshot(
                    index = snapshotIndex,
                    snapshot = snapshot,
                    responseSummary = responseSummaries[ordinal],
                )
                snapshotIndex = addExact(snapshotIndex, 1L)
            }
            diagnostics?.destinationDigestNanos +=
                destinationDigestStartedAt.elapsedSince()
            previousCursor = expectedTerminalCursor
            expectedSequence = addExact(expectedSequence, 1L)
            if (page.finalBatch) terminalPage = page
            pageIndex = addExact(pageIndex, 1L)
            pageChunkAfterSequence = page.batchSequence
        }
    }

    check(pageIndex == persistedPageCount) {
        "Raw legacy mastery page count changed during ledger recomputation"
    }
    check(snapshotIndex == persistedSnapshotCount) {
        "Raw legacy mastery snapshot has no page receipt"
    }
    val terminal = terminalPage ?: return null
    return LearnerMasteryImmutableMigrationLedgerDigest(
        learnerId = learnerId,
        sourceGeneration = sourceGeneration,
        migratedObservationCount = snapshotIndex,
        terminalBatchSequence = terminal.batchSequence,
        terminalBatchFingerprint = terminal.pageReceiptCanonicalFingerprint,
        batchReceiptCount = persistedPageCount.toInt(),
        destinationCanonicalFingerprint =
            destinationDigest
                .field("terminalBatchSequence", terminal.batchSequence)
                .field(
                    "terminalBatchFingerprint",
                    terminal.pageReceiptCanonicalFingerprint,
                )
                .field(
                    "terminalSourcePageCanonicalFingerprint",
                    terminal.sourcePageCanonicalFingerprint,
                )
                .nullableLongField(
                    "terminalOccurredAtEpochMillis",
                    terminal.terminalOccurredAtEpochMillis,
                )
                .nullableField(
                    "terminalSourceFactId",
                    terminal.terminalSourceFactId,
                )
                .field("migratedObservationCount", snapshotIndex)
                .finish(),
        destinationLedgerVersion = RAW_SNAPSHOT_DESTINATION_LEDGER_VERSION,
        destinationCanonicalLayoutVersion =
            RAW_SNAPSHOT_DESTINATION_CANONICAL_LAYOUT_VERSION,
        rawSnapshotCount = snapshotIndex,
        terminalSourcePageCanonicalFingerprint =
            terminal.sourcePageCanonicalFingerprint,
        terminalCursor = terminal.terminalCursor(),
    )
}

private fun CanonicalSha256.appendSnapshot(
    index: Long,
    snapshot: LearnerMasteryLegacyObservationSnapshotEntity,
    responseSummary: String,
): CanonicalSha256 =
    field("snapshot.index", index)
        .field("snapshot.learnerId", snapshot.learnerId)
        .field("snapshot.sourceGeneration", snapshot.sourceGeneration)
        .field("snapshot.batchSequence", snapshot.batchSequence)
        .field("snapshot.snapshotOrdinal", snapshot.snapshotOrdinal)
        .field("snapshot.sourceFactId", snapshot.sourceFactId)
        .field("snapshot.source", snapshot.source)
        .field("snapshot.factKind", snapshot.factKind)
        .field("snapshot.anchorId", snapshot.anchorId)
        .field("snapshot.subject", snapshot.subject)
        .nullableLongField(
            "snapshot.conversationGeneration",
            snapshot.conversationGeneration,
        )
        .nullableField("snapshot.conversationId", snapshot.conversationId)
        .nullableField("snapshot.turnReceiptId", snapshot.turnReceiptId)
        .nullableField("snapshot.evidenceRequestId", snapshot.evidenceRequestId)
        .field("snapshot.responseFingerprint", snapshot.responseFingerprint)
        .field("snapshot.responseSummary", responseSummary)
        .field(
            "snapshot.occurredAtEpochMillis",
            snapshot.occurredAtEpochMillis,
        )
        .field("snapshot.sourceVersion", snapshot.sourceVersion)
        .field(
            "snapshot.sourcePayloadCanonicalFingerprint",
            snapshot.sourcePayloadCanonicalFingerprint,
        )
        .field("snapshot.proofPresent", snapshot.proofPresent)
        .nullableField(
            "snapshot.sourceProofCanonicalFingerprint",
            snapshot.sourceProofCanonicalFingerprint,
        )
        .nullableField(
            "snapshot.sourceReferenceId",
            snapshot.sourceReferenceId,
        )
        .nullableField("snapshot.targetKind", snapshot.targetKind)
        .nullableField("snapshot.targetDatabase", snapshot.targetDatabase)
        .nullableField("snapshot.targetId", snapshot.targetId)
        .nullableField("snapshot.targetVersion", snapshot.targetVersion)
        .nullableField(
            "snapshot.targetCanonicalFingerprint",
            snapshot.targetCanonicalFingerprint,
        )
        .nullableLongField(
            "snapshot.attestedAtEpochMillis",
            snapshot.attestedAtEpochMillis,
        )
        .field(
            "snapshot.sourceRecordCanonicalFingerprint",
            snapshot.sourceRecordCanonicalFingerprint,
        )
        .field(
            "snapshot.snapshotCanonicalFingerprint",
            snapshot.snapshotCanonicalFingerprint,
        )

private fun LearnerMasteryLegacyObservationSnapshotEntity.validateStoredSourceRecord(
    learnerId: String,
    sourceGeneration: String,
    batchSequence: Long,
    expectedOrdinal: Int,
    responseSummary: String,
) {
    check(
        this.learnerId == LOCAL_LEARNER_ID &&
            this.learnerId == learnerId &&
            this.sourceGeneration == sourceGeneration &&
            this.batchSequence == batchSequence,
    ) {
        "Raw legacy mastery snapshot belongs to another page"
    }
    check(snapshotOrdinal == expectedOrdinal) {
        "Raw legacy mastery snapshot ordinals are not contiguous"
    }
    requireMasteryIdentity(sourceFactId, "Legacy source-fact id")
    requireMasteryIdentity(anchorId, "Legacy anchor id")
    check(source in SUPPORTED_LEGACY_OBSERVATION_SOURCES) {
        "Legacy observation source is unsupported"
    }
    check(factKind in SUPPORTED_LEGACY_OBSERVATION_FACT_KINDS) {
        "Legacy observation fact kind is unsupported"
    }
    check(subject in SUPPORTED_LEGACY_SUBJECTS) {
        "Legacy observation requires one high-school subject"
    }
    check(conversationGeneration == null || conversationGeneration > 0L)
    conversationId?.let { requireMasteryIdentity(it, "Legacy conversation reference") }
    turnReceiptId?.let { requireMasteryIdentity(it, "Legacy conversation reference") }
    evidenceRequestId?.let { requireMasteryIdentity(it, "Legacy conversation reference") }
    requireMasteryFingerprint(responseFingerprint, "Legacy response fingerprint")
    check(responseSummary.isNotBlank() && responseSummary == responseSummary.trim()) {
        "Legacy response summary must be retained exactly"
    }
    check(responseSummary.length <= MAX_LEGACY_RESPONSE_SUMMARY_LENGTH) {
        "Legacy response summary exceeds its historical bound"
    }
    check(occurredAtEpochMillis >= 0L)
    requireMasteryVersion(sourceVersion, "Legacy source version")
    requireMasteryFingerprint(
        sourcePayloadCanonicalFingerprint,
        "Legacy source payload fingerprint",
    )
    val proofComplete =
        sourceProofCanonicalFingerprint != null &&
            sourceReferenceId != null &&
            targetKind != null &&
            targetDatabase != null &&
            targetId != null &&
            targetVersion != null &&
            targetCanonicalFingerprint != null &&
            attestedAtEpochMillis != null
    val proofAbsent =
        sourceProofCanonicalFingerprint == null &&
            sourceReferenceId == null &&
            targetKind == null &&
            targetDatabase == null &&
            targetId == null &&
            targetVersion == null &&
            targetCanonicalFingerprint == null &&
            attestedAtEpochMillis == null
    check(if (proofPresent) proofComplete else proofAbsent) {
        "Legacy mastery proof must be preserved as either complete or absent"
    }
    sourceProofCanonicalFingerprint?.let {
        requireMasteryFingerprint(it, "Legacy source proof fingerprint")
    }
    targetCanonicalFingerprint?.let {
        requireMasteryFingerprint(it, "Legacy target fingerprint")
    }
    attestedAtEpochMillis?.let {
        check(it >= occurredAtEpochMillis) {
            "Legacy proof attestation precedes its source fact"
        }
    }
    requireMasteryFingerprint(
        sourceRecordCanonicalFingerprint,
        "Legacy source-record fingerprint",
    )
    check(matchesStoredSourceRecordFingerprint(responseSummary)) {
        "Legacy source-record fingerprint does not cover the complete snapshot"
    }
    check(matchesStoredSnapshotFingerprint(responseSummary)) {
        "Legacy snapshot fingerprint does not cover the encrypted response summary"
    }
}

private fun LearnerMasteryLegacyObservationSnapshotEntity
    .matchesStoredSourceRecordFingerprint(responseSummary: String): Boolean =
    LEGACY_SOURCE_RECORD_CANONICAL_SCHEMA
        .newDigest()
        .field("sourceFactId", sourceFactId)
        .field("learnerScopeId", learnerId)
        .field("source", source)
        .field("factKind", factKind)
        .field("anchorId", anchorId)
        .field("subject", subject)
        .nullableLongField("conversationGeneration", conversationGeneration)
        .nullableField("conversationId", conversationId)
        .nullableField("turnReceiptId", turnReceiptId)
        .nullableField("evidenceRequestId", evidenceRequestId)
        .field("responseFingerprint", responseFingerprint)
        .field("responseSummary", responseSummary)
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .field("sourceVersion", sourceVersion)
        .field(
            "sourcePayloadCanonicalFingerprint",
            sourcePayloadCanonicalFingerprint,
        )
        .nullableField(
            "sourceProofCanonicalFingerprint",
            sourceProofCanonicalFingerprint,
        )
        .nullableField("sourceReferenceId", sourceReferenceId)
        .nullableField("targetKind", targetKind)
        .nullableField("targetDatabase", targetDatabase)
        .nullableField("targetId", targetId)
        .nullableField("targetVersion", targetVersion)
        .nullableField("targetCanonicalFingerprint", targetCanonicalFingerprint)
        .nullableLongField("attestedAtEpochMillis", attestedAtEpochMillis)
        .finishMatchesHex(sourceRecordCanonicalFingerprint)

private fun LearnerMasteryLegacyObservationSnapshotEntity.isStrictlyAfter(
    previous: LearnerMasteryLegacyObservationSnapshotEntity,
): Boolean =
    occurredAtEpochMillis > previous.occurredAtEpochMillis ||
        (
            occurredAtEpochMillis == previous.occurredAtEpochMillis &&
                sourceFactId > previous.sourceFactId
        )

private fun LearnerMasteryLegacyObservationSnapshotEntity.isStrictlyAfter(
    previous: LearnerMasteryLegacyMigrationCursor,
): Boolean =
    occurredAtEpochMillis > previous.occurredAtEpochMillis ||
        (
            occurredAtEpochMillis == previous.occurredAtEpochMillis &&
                sourceFactId > previous.sourceFactId
        )

private fun LearnerMasteryLegacyObservationSnapshotEntity.toMigrationCursor() =
    LearnerMasteryLegacyMigrationCursor(
        occurredAtEpochMillis = occurredAtEpochMillis,
        sourceFactId = sourceFactId,
    )

private fun LearnerMasteryLegacySnapshotPageEntity.hasTerminalCursor(
    expected: LearnerMasteryLegacyMigrationCursor?,
): Boolean =
    terminalOccurredAtEpochMillis == expected?.occurredAtEpochMillis &&
        terminalSourceFactId == expected?.sourceFactId

private fun LearnerMasteryLedgerRecomputeDiagnostics?.startedAt(): Long =
    if (this == null) 0L else System.nanoTime()

private fun Long.elapsedSince(): Long = System.nanoTime() - this

private fun LearnerMasteryLegacySnapshotPage.toEntity():
    LearnerMasteryLegacySnapshotPageEntity =
    LearnerMasteryLegacySnapshotPageEntity(
        learnerId = learnerId,
        sourceGeneration = sourceGeneration,
        batchSequence = batchSequence,
        sourcePageCanonicalFingerprint = sourcePageCanonicalFingerprint,
        afterOccurredAtEpochMillis = afterExclusive?.occurredAtEpochMillis,
        afterSourceFactId = afterExclusive?.sourceFactId,
        terminalOccurredAtEpochMillis = terminalCursor?.occurredAtEpochMillis,
        terminalSourceFactId = terminalCursor?.sourceFactId,
        snapshotCount = snapshots.size,
        finalBatch = finalBatch,
        pageReceiptCanonicalFingerprint = canonicalFingerprint,
    )

private fun LearnerMasteryLegacyObservationSnapshot.toEntity(
    sourceGeneration: String,
    batchSequence: Long,
    snapshotOrdinal: Int,
    cipher: LearnerMasteryLegacyResponseSummaryCipher,
): LearnerMasteryLegacyObservationSnapshotEntity {
    val snapshotCanonicalFingerprint =
        recomputeSnapshotCanonicalFingerprint(
            sourceGeneration = sourceGeneration,
            batchSequence = batchSequence,
            snapshotOrdinal = snapshotOrdinal,
        )
    val encrypted =
        cipher.encrypt(
            binding =
                LearnerMasteryLegacyResponseSummaryBinding(
                    learnerId = learnerId,
                    sourceGeneration = sourceGeneration,
                    batchSequence = batchSequence,
                    snapshotOrdinal = snapshotOrdinal,
                    sourceFactId = sourceFactId,
                    sourceRecordCanonicalFingerprint = sourceRecordCanonicalFingerprint,
                    snapshotCanonicalFingerprint = snapshotCanonicalFingerprint,
                ),
            plaintext = responseSummary,
        )
    return LearnerMasteryLegacyObservationSnapshotEntity(
            learnerId = learnerId,
            sourceGeneration = sourceGeneration,
            batchSequence = batchSequence,
            snapshotOrdinal = snapshotOrdinal,
            sourceFactId = sourceFactId,
            source = source,
            factKind = factKind,
            anchorId = anchorId,
            subject = subject,
            conversationGeneration = conversationGeneration,
            conversationId = conversationId,
            turnReceiptId = turnReceiptId,
            evidenceRequestId = evidenceRequestId,
            responseFingerprint = responseFingerprint,
            keyVersion = encrypted.keyVersion,
            nonce = encrypted.nonce,
            ciphertext = encrypted.ciphertext,
            occurredAtEpochMillis = occurredAtEpochMillis,
            sourceVersion = sourceVersion,
            sourcePayloadCanonicalFingerprint = sourcePayloadCanonicalFingerprint,
            proofPresent = proofPresent,
            sourceProofCanonicalFingerprint = sourceProofCanonicalFingerprint,
            sourceReferenceId = sourceReferenceId,
            targetKind = targetKind,
            targetDatabase = targetDatabase,
            targetId = targetId,
            targetVersion = targetVersion,
            targetCanonicalFingerprint = targetCanonicalFingerprint,
            attestedAtEpochMillis = attestedAtEpochMillis,
            sourceRecordCanonicalFingerprint = sourceRecordCanonicalFingerprint,
            snapshotCanonicalFingerprint = snapshotCanonicalFingerprint,
        )
}

private fun LearnerMasteryLegacyObservationSnapshot
    .recomputeSnapshotCanonicalFingerprint(
        sourceGeneration: String,
        batchSequence: Long,
        snapshotOrdinal: Int,
    ): String =
    RAW_SNAPSHOT_CANONICAL_SCHEMA.newDigest()
        .field("learnerId", learnerId)
        .field("sourceGeneration", sourceGeneration)
        .field("batchSequence", batchSequence)
        .field("snapshotOrdinal", snapshotOrdinal)
        .field("sourceFactId", sourceFactId)
        .field("source", source)
        .field("factKind", factKind)
        .field("anchorId", anchorId)
        .field("subject", subject)
        .nullableField("conversationGeneration", conversationGeneration?.toString())
        .nullableField("conversationId", conversationId)
        .nullableField("turnReceiptId", turnReceiptId)
        .nullableField("evidenceRequestId", evidenceRequestId)
        .field("responseFingerprint", responseFingerprint)
        .field("responseSummary", responseSummary)
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .field("sourceVersion", sourceVersion)
        .field(
            "sourcePayloadCanonicalFingerprint",
            sourcePayloadCanonicalFingerprint,
        )
        .field("proofPresent", proofPresent)
        .nullableField(
            "sourceProofCanonicalFingerprint",
            sourceProofCanonicalFingerprint,
        )
        .nullableField("sourceReferenceId", sourceReferenceId)
        .nullableField("targetKind", targetKind)
        .nullableField("targetDatabase", targetDatabase)
        .nullableField("targetId", targetId)
        .nullableField("targetVersion", targetVersion)
        .nullableField("targetCanonicalFingerprint", targetCanonicalFingerprint)
        .nullableField("attestedAtEpochMillis", attestedAtEpochMillis?.toString())
        .field(
            "sourceRecordCanonicalFingerprint",
            sourceRecordCanonicalFingerprint,
        )
        .finish()

private fun LearnerMasteryLegacyObservationSnapshotEntity
    .matchesStoredSnapshotFingerprint(responseSummary: String): Boolean =
    RAW_SNAPSHOT_CANONICAL_SCHEMA.newDigest()
        .field("learnerId", learnerId)
        .field("sourceGeneration", sourceGeneration)
        .field("batchSequence", batchSequence)
        .field("snapshotOrdinal", snapshotOrdinal)
        .field("sourceFactId", sourceFactId)
        .field("source", source)
        .field("factKind", factKind)
        .field("anchorId", anchorId)
        .field("subject", subject)
        .nullableField("conversationGeneration", conversationGeneration?.toString())
        .nullableField("conversationId", conversationId)
        .nullableField("turnReceiptId", turnReceiptId)
        .nullableField("evidenceRequestId", evidenceRequestId)
        .field("responseFingerprint", responseFingerprint)
        .field("responseSummary", responseSummary)
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .field("sourceVersion", sourceVersion)
        .field(
            "sourcePayloadCanonicalFingerprint",
            sourcePayloadCanonicalFingerprint,
        )
        .field("proofPresent", proofPresent)
        .nullableField(
            "sourceProofCanonicalFingerprint",
            sourceProofCanonicalFingerprint,
        )
        .nullableField("sourceReferenceId", sourceReferenceId)
        .nullableField("targetKind", targetKind)
        .nullableField("targetDatabase", targetDatabase)
        .nullableField("targetId", targetId)
        .nullableField("targetVersion", targetVersion)
        .nullableField("targetCanonicalFingerprint", targetCanonicalFingerprint)
        .nullableField("attestedAtEpochMillis", attestedAtEpochMillis?.toString())
        .field(
            "sourceRecordCanonicalFingerprint",
            sourceRecordCanonicalFingerprint,
        )
        .finishMatchesHex(snapshotCanonicalFingerprint)

private fun LearnerMasteryLegacyObservationSnapshotEntity
    .requireDecryptedResponseSummary(
        cipher: LearnerMasteryLegacyResponseSummaryCipher,
    ): String =
    checkNotNull(
        cipher.decrypt(
            binding = responseSummaryBinding(),
            encrypted =
                LearnerMasteryEncryptedLegacyResponseSummary(
                    keyVersion = keyVersion,
                    nonce = nonce,
                    ciphertext = ciphertext,
                ),
        ),
    ) {
        "Legacy response summary cannot be authenticated"
    }

private fun LearnerMasteryLegacyObservationSnapshotEntity.responseSummaryBinding() =
    LearnerMasteryLegacyResponseSummaryBinding(
        learnerId = learnerId,
        sourceGeneration = sourceGeneration,
        batchSequence = batchSequence,
        snapshotOrdinal = snapshotOrdinal,
        sourceFactId = sourceFactId,
        sourceRecordCanonicalFingerprint = sourceRecordCanonicalFingerprint,
        snapshotCanonicalFingerprint = snapshotCanonicalFingerprint,
        keyVersion = keyVersion,
    )

private fun List<LearnerMasteryLegacyObservationSnapshotEntity>
    .sameEncryptedSnapshotSemanticsAs(
        expected: List<LearnerMasteryLegacyObservationSnapshotEntity>,
        cipher: LearnerMasteryLegacyResponseSummaryCipher,
    ): Boolean {
    if (size != expected.size) return false
    return indices.all { index ->
        val persistedSnapshot = get(index)
        val expectedSnapshot = expected[index]
        val persistedSummary = persistedSnapshot.requireDecryptedResponseSummary(cipher)
        val expectedSummary = expectedSnapshot.requireDecryptedResponseSummary(cipher)
        persistedSnapshot.snapshotCanonicalFingerprint ==
            expectedSnapshot.snapshotCanonicalFingerprint &&
            persistedSummary == expectedSummary &&
            persistedSnapshot.matchesStoredSnapshotFingerprint(persistedSummary) &&
            expectedSnapshot.matchesStoredSnapshotFingerprint(expectedSummary)
    }
}

private fun LearnerMasteryLegacySnapshotPageEntity.afterCursor():
    LearnerMasteryLegacyMigrationCursor? {
    check((afterOccurredAtEpochMillis == null) == (afterSourceFactId == null)) {
        "Raw legacy mastery page has a partial source cursor"
    }
    return afterOccurredAtEpochMillis?.let { occurredAt ->
        LearnerMasteryLegacyMigrationCursor(
            occurredAtEpochMillis = occurredAt,
            sourceFactId = checkNotNull(afterSourceFactId),
        )
    }
}

private fun LearnerMasteryLegacySnapshotPageEntity.terminalCursor():
    LearnerMasteryLegacyMigrationCursor? {
    check(
        (terminalOccurredAtEpochMillis == null) ==
            (terminalSourceFactId == null),
    ) {
        "Raw legacy mastery page has a partial terminal cursor"
    }
    return terminalOccurredAtEpochMillis?.let { occurredAt ->
        LearnerMasteryLegacyMigrationCursor(
            occurredAtEpochMillis = occurredAt,
            sourceFactId = checkNotNull(terminalSourceFactId),
        )
    }
}

private fun LearnerMasteryLegacySnapshotPage.result(
    disposition: LearnerMasteryLegacySnapshotPageDisposition,
): LearnerMasteryLegacySnapshotPageResult =
    LearnerMasteryLegacySnapshotPageResult(
        learnerId = learnerId,
        sourceGeneration = sourceGeneration,
        batchSequence = batchSequence,
        sourcePageCanonicalFingerprint = sourcePageCanonicalFingerprint,
        disposition = disposition,
        snapshotCount = snapshots.size,
        finalBatch = finalBatch,
        pageReceiptCanonicalFingerprint = canonicalFingerprint,
    )

internal fun MasteryLegacyObservationWrite.toMigrationDestinationRecord(
    checkpoint: MasteryLegacyFactMigrationCheckpointEntity,
    observationOrdinal: Int,
): LearnerMasteryMigrationDestinationRecordEntity {
    check(sourceFact.learnerId == checkpoint.learnerId) {
        "Migrated mastery source fact is outside its checkpoint learner scope"
    }
    check(
        sourceProof.sourceFactId == sourceFact.sourceFactId &&
            candidate.sourceFactId == sourceFact.sourceFactId &&
            candidate.learnerId == checkpoint.learnerId,
    ) {
        "Migrated mastery destination record crossed an observation boundary"
    }
    return LearnerMasteryMigrationDestinationRecordEntity(
        learnerId = checkpoint.learnerId,
        sourceGeneration = checkpoint.sourceGeneration,
        batchSequence = checkpoint.batchSequence,
        observationOrdinal = observationOrdinal,
        sourceFactId = sourceFact.sourceFactId,
        sourceFactCanonicalFingerprint = sourceFact.canonicalFingerprint,
        candidateId = candidate.candidateId,
        candidateCanonicalFingerprint = candidate.canonicalFingerprint,
        destinationRecordCanonicalFingerprint =
            immutableMigrationDestinationRecordFingerprint(
                sourceFact = sourceFact,
                sourceProof = sourceProof,
                candidate = candidate,
                attributions = attributions,
            ),
    )
}

private fun immutableMigrationDestinationRecordFingerprint(
    sourceFact: MasterySourceFactEntity,
    sourceProof: MasterySourceProofEntity,
    candidate: MasteryObservationCandidateEntity,
    attributions: List<MasteryCandidateAttributionEntity>,
): String {
    requireMasteryFingerprint(sourceFact.canonicalFingerprint, "Mastery source-fact fingerprint")
    requireMasteryFingerprint(sourceProof.proofFingerprint, "Mastery source-proof fingerprint")
    requireMasteryFingerprint(candidate.canonicalFingerprint, "Mastery candidate fingerprint")
    val digest =
        CanonicalSha256(IMMUTABLE_MIGRATION_DESTINATION_RECORD_FINGERPRINT_DOMAIN)
            .field("sourceFact.sourceFactId", sourceFact.sourceFactId)
            .field("sourceFact.learnerId", sourceFact.learnerId)
            .field("sourceFact.subject", sourceFact.subject)
            .field("sourceFact.sourceKind", sourceFact.sourceKind)
            .field("sourceFact.sourceReferenceId", sourceFact.sourceReferenceId)
            .field("sourceFact.presentationId", sourceFact.presentationId)
            .nullableField(
                "sourceFact.problemRevisionRefFingerprint",
                sourceFact.problemRevisionRefFingerprint,
            )
            .nullableField("sourceFact.problemRevisionId", sourceFact.problemRevisionId)
            .nullableField("sourceFact.problemId", sourceFact.problemId)
            .nullableField("sourceFact.practiceUnitId", sourceFact.practiceUnitId)
            .nullableField(
                "sourceFact.problemRevisionNumber",
                sourceFact.problemRevisionNumber?.toString(),
            )
            .nullableField(
                "sourceFact.problemDocumentFingerprint",
                sourceFact.problemDocumentFingerprint,
            )
            .nullableField("sourceFact.reviewSessionId", sourceFact.reviewSessionId)
            .nullableField("sourceFact.reviewQueueItemId", sourceFact.reviewQueueItemId)
            .nullableField("sourceFact.reviewSubmissionId", sourceFact.reviewSubmissionId)
            .field("sourceFact.outcome", sourceFact.outcome)
            .field("sourceFact.assistance", sourceFact.assistance)
            .field("sourceFact.retryState", sourceFact.retryState)
            .field("sourceFact.authority", sourceFact.authority)
            .field("sourceFact.sourcePayloadFingerprint", sourceFact.sourcePayloadFingerprint)
            .field("sourceFact.occurredAtEpochMillis", sourceFact.occurredAtEpochMillis)
            .field("sourceFact.attestedAtEpochMillis", sourceFact.attestedAtEpochMillis)
            .field("sourceFact.receivedAtEpochMillis", sourceFact.receivedAtEpochMillis)
            .field("sourceFact.sourcePolicyVersion", sourceFact.sourcePolicyVersion)
            .field("sourceFact.idempotencyKey", sourceFact.idempotencyKey)
            .field("sourceFact.canonicalFingerprint", sourceFact.canonicalFingerprint)
            .nullableField(
                "sourceFact.problemFamilyFingerprint",
                sourceFact.problemFamilyFingerprint,
            )
            .field("sourceFact.presentationFingerprint", sourceFact.presentationFingerprint)
            .field("sourceFact.responseForm", sourceFact.responseForm)
            .field("sourceFact.independentlyAnswered", sourceFact.independentlyAnswered)
            .field("sourceFact.hintCount", sourceFact.hintCount)
            .field("sourceFact.answerRevealed", sourceFact.answerRevealed)
            .nullableField(
                "sourceFact.elapsedDurationMillis",
                sourceFact.elapsedDurationMillis?.toString(),
            )
            .field("sourceFact.verificationKind", sourceFact.verificationKind)
            .field("sourceFact.evidenceContextKind", sourceFact.evidenceContextKind)
            .nullableField(
                "sourceFact.ephemeralProblemFingerprint",
                sourceFact.ephemeralProblemFingerprint,
            )
            .nullableField(
                "sourceFact.tutorTurnReferenceId",
                sourceFact.tutorTurnReferenceId,
            )
            .nullableField(
                "sourceFact.submissionEvidenceFingerprint",
                sourceFact.submissionEvidenceFingerprint,
            )
            .nullableField(
                "sourceFact.attributionModelVersion",
                sourceFact.attributionModelVersion,
            )
            .nullableField(
                "sourceFact.authorizedProblemBindingsFingerprint",
                sourceFact.authorizedProblemBindingsFingerprint,
            )
            .nullableField(
                "sourceFact.authorizedKnowledgeRefsFingerprint",
                sourceFact.authorizedKnowledgeRefsFingerprint,
            )
            .nullableField(
                "sourceFact.knowledgeManifestFingerprint",
                sourceFact.knowledgeManifestFingerprint,
            )
            .nullableField(
                "sourceFact.knowledgeActivationGeneration",
                sourceFact.knowledgeActivationGeneration?.toString(),
            )
            .nullableField(
                "sourceFact.authorityAttemptFingerprint",
                sourceFact.authorityAttemptFingerprint,
            )
            .nullableField(
                "sourceFact.authoritySubmissionFingerprint",
                sourceFact.authoritySubmissionFingerprint,
            )
            .nullableField(
                "sourceFact.authorityPresentationFingerprint",
                sourceFact.authorityPresentationFingerprint,
            )
            .nullableField(
                "sourceFact.authorityProblemFamilyFingerprint",
                sourceFact.authorityProblemFamilyFingerprint,
            )
            .nullableField(
                "sourceFact.authorityIdentityVersion",
                sourceFact.authorityIdentityVersion,
            )
            .field("sourceProof.sourceFactId", sourceProof.sourceFactId)
            .field(
                "sourceProof.sourceFactCanonicalFingerprint",
                sourceProof.sourceFactCanonicalFingerprint,
            )
            .field("sourceProof.sourcePolicyVersion", sourceProof.sourcePolicyVersion)
            .field("sourceProof.policySupported", sourceProof.policySupported)
            .field("sourceProof.proofFingerprint", sourceProof.proofFingerprint)
            .field("sourceProof.createdAtEpochMillis", sourceProof.createdAtEpochMillis)
            .field("candidate.candidateId", candidate.candidateId)
            .field("candidate.learnerId", candidate.learnerId)
            .field("candidate.subject", candidate.subject)
            .field("candidate.sourceFactId", candidate.sourceFactId)
            .field("candidate.confidence", candidate.confidence)
            .field("candidate.modelVersion", candidate.modelVersion)
            .field("candidate.requestedPolicyVersion", candidate.requestedPolicyVersion)
            .field("candidate.proposedAtEpochMillis", candidate.proposedAtEpochMillis)
            .field("candidate.receivedAtEpochMillis", candidate.receivedAtEpochMillis)
            .field("candidate.idempotencyKey", candidate.idempotencyKey)
            .field("candidate.canonicalFingerprint", candidate.canonicalFingerprint)
            .field("candidate.candidateOrigin", candidate.candidateOrigin)
            .field("attributionCount", attributions.size)
    attributions.sortedBy(MasteryCandidateAttributionEntity::ordinal)
        .forEachIndexed { index, attribution ->
            check(attribution.candidateId == candidate.candidateId) {
                "Mastery migration attribution belongs to another candidate"
            }
            requireMasteryFingerprint(
                attribution.proposalFingerprint,
                "Mastery migration attribution fingerprint",
            )
            digest
                .field("attribution[$index].candidateId", attribution.candidateId)
                .field("attribution[$index].ordinal", attribution.ordinal)
                .field("attribution[$index].subject", attribution.subject)
                .field("attribution[$index].knowledgeNodeId", attribution.knowledgeNodeId)
                .field("attribution[$index].taxonomyVersion", attribution.taxonomyVersion)
                .field(
                    "attribution[$index].knowledgePackVersion",
                    attribution.knowledgePackVersion,
                )
                .field(
                    "attribution[$index].knowledgeNodeRefFingerprint",
                    attribution.knowledgeNodeRefFingerprint,
                )
                .nullableField(
                    "attribution[$index].problemBindingRefFingerprint",
                    attribution.problemBindingRefFingerprint,
                )
                .nullableField(
                    "attribution[$index].bindingProblemRevisionRefFingerprint",
                    attribution.bindingProblemRevisionRefFingerprint,
                )
                .field("attribution[$index].role", attribution.role)
                .field("attribution[$index].certainty", attribution.certainty)
                .field(
                    "attribution[$index].proposalFingerprint",
                    attribution.proposalFingerprint,
                )
        }
    return digest.finish()
}

private const val RAW_SNAPSHOT_MIGRATION_LEDGER_FINGERPRINT_DOMAIN =
    "learner-mastery-immutable-raw-snapshot-ledger-v2"
private const val RAW_SNAPSHOT_FINGERPRINT_DOMAIN =
    "learner-mastery-legacy-observation-snapshot-v1"
private const val IMMUTABLE_MIGRATION_DESTINATION_RECORD_FINGERPRINT_DOMAIN =
    "learner-mastery-immutable-migration-destination-record-v1"
private val LEGACY_SOURCE_RECORD_CANONICAL_SCHEMA =
    CanonicalSha256.compileSchema(
        LEGACY_SOURCE_RECORD_FINGERPRINT_DOMAIN,
        "sourceFactId",
        "learnerScopeId",
        "source",
        "factKind",
        "anchorId",
        "subject",
        "conversationGeneration",
        "conversationId",
        "turnReceiptId",
        "evidenceRequestId",
        "responseFingerprint",
        "responseSummary",
        "occurredAtEpochMillis",
        "sourceVersion",
        "sourcePayloadCanonicalFingerprint",
        "sourceProofCanonicalFingerprint",
        "sourceReferenceId",
        "targetKind",
        "targetDatabase",
        "targetId",
        "targetVersion",
        "targetCanonicalFingerprint",
        "attestedAtEpochMillis",
    )
private val RAW_SNAPSHOT_CANONICAL_SCHEMA =
    CanonicalSha256.compileSchema(
        RAW_SNAPSHOT_FINGERPRINT_DOMAIN,
        "learnerId",
        "sourceGeneration",
        "batchSequence",
        "snapshotOrdinal",
        "sourceFactId",
        "source",
        "factKind",
        "anchorId",
        "subject",
        "conversationGeneration",
        "conversationId",
        "turnReceiptId",
        "evidenceRequestId",
        "responseFingerprint",
        "responseSummary",
        "occurredAtEpochMillis",
        "sourceVersion",
        "sourcePayloadCanonicalFingerprint",
        "proofPresent",
        "sourceProofCanonicalFingerprint",
        "sourceReferenceId",
        "targetKind",
        "targetDatabase",
        "targetId",
        "targetVersion",
        "targetCanonicalFingerprint",
        "attestedAtEpochMillis",
        "sourceRecordCanonicalFingerprint",
    )
private val SUPPORTED_LEGACY_OBSERVATION_SOURCES =
    enumValues<LearningObservationSource>().mapTo(HashSet()) { it.name }
private val SUPPORTED_LEGACY_OBSERVATION_FACT_KINDS =
    enumValues<LearningObservationFactKind>().mapTo(HashSet()) { it.name }
private val SUPPORTED_LEGACY_SUBJECTS =
    enumValues<SubjectKind>()
        .asSequence()
        .filter { it != SubjectKind.GENERAL }
        .mapTo(HashSet()) { it.name }
private const val MAX_LEGACY_RESPONSE_SUMMARY_LENGTH = 512
private const val MAX_LEGACY_SNAPSHOTS_PER_PAGE = 256
private const val MIGRATION_LEDGER_PAGE_READ_BATCH = 256
