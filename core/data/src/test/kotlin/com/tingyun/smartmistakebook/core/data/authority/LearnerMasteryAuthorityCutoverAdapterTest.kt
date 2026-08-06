package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSnapshot
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationPage
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationRecord
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationPage
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryAuthorityCutoverCompletionReceipt
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryAuthorityCutoverFence
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverControlPort as MasteryTargetCutoverControlPort
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryImmutableMigrationLedgerDigest
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacyMigrationPort
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacySnapshotPage
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacySnapshotPageDisposition
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacySnapshotPageResult
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryAuthorityCutoverAdapterTest {
    @Test
    fun emptyAndMultiPageSourcesProduceExactRawV2Ledgers() =
        runBlocking {
            val emptySource = MasteryCutoverFakeLegacySource()
            val emptyTarget = FakeMasteryTarget()
            val emptyResult = migrator(emptySource, emptyTarget, pageSize = 2).migrate()

            assertEquals(0L, emptyResult.manifest.recordCount)
            assertEquals(1, emptyTarget.pages.size)
            assertTrue(emptyTarget.pages.single().snapshots.isEmpty())
            assertTrue(emptyTarget.pages.single().finalBatch)
            assertEquals(2, emptyResult.ledgerDigest.destinationLedgerVersion)

            val records = (1..5).map(::masteryRecord)
            val source = MasteryCutoverFakeLegacySource(records)
            val target = FakeMasteryTarget()
            val result = migrator(source, target, pageSize = 2).migrate()

            assertEquals(listOf(2, 2, 1), target.pages.map { it.snapshots.size })
            assertEquals(listOf(false, false, true), target.pages.map { it.finalBatch })
            assertEquals(
                listOf(
                    null,
                    target.pages[0].terminalCursor,
                    target.pages[1].terminalCursor,
                ),
                target.pages.map { it.afterExclusive },
            )
            assertEquals(5L, result.ledgerDigest.rawSnapshotCount)
            assertEquals(3, result.ledgerDigest.batchReceiptCount)
            assertEquals(
                records.last().cursor.occurredAtEpochMillis,
                result.ledgerDigest.terminalCursor?.occurredAtEpochMillis,
            )

            val evidence =
                adapter(source, target, pageSize = 2)
                    .reverifyImmutableImportEvidence()
            requireNotNull(evidence)
            assertEquals(FencedAuthority.LEARNER_MASTERY, evidence.authority)
            assertEquals(5L, evidence.migratedRecordCount)
            assertEquals(LEGACY_PREFIX_RECEIPT, evidence.legacyPrefixReceiptFingerprint)
            assertTrue(evidence.hasValidFingerprint())
        }

    @Test
    fun retryReplaysDurablePagesWithoutDuplicatingRawSnapshots() {
        val source = MasteryCutoverFakeLegacySource((1..4).map(::masteryRecord))
        val target =
            FakeMasteryTarget().apply {
                failOnceAfterPersistedPageIndex = 0
            }

        assertThrows(IllegalStateException::class.java) {
            runBlocking { migrator(source, target, pageSize = 2).migrate() }
        }
        val result =
            runBlocking { migrator(source, target, pageSize = 2).migrate() }

        assertEquals(2, target.pages.size)
        assertEquals(3, target.applyAttempts)
        assertEquals(4L, result.ledgerDigest.rawSnapshotCount)
        assertEquals(
            4,
            target.pages.flatMap { it.snapshots }.map { it.sourceFactId }.distinct().size,
        )
    }

    @Test
    fun changedSourceOrIncompleteDestinationProofFailsClosed() =
        runBlocking {
            val source = MasteryCutoverFakeLegacySource(listOf(masteryRecord(1)))
            val target = FakeMasteryTarget()
            migrator(source, target, pageSize = 2).migrate()
            val control = adapter(source, target, pageSize = 2)
            assertTrue(control.reverifyImmutableImportEvidence() != null)

            target.ledgerTransform = { it.copy(destinationLedgerVersion = 1) }
            assertNull(control.reverifyImmutableImportEvidence())
            target.ledgerTransform = {
                it.copy(destinationCanonicalLayoutVersion = 3)
            }
            assertNull(control.reverifyImmutableImportEvidence())
            target.ledgerTransform = {
                it.copy(
                    migratedObservationCount = 0,
                    rawSnapshotCount = 0,
                    terminalCursor = null,
                )
            }
            assertNull(control.reverifyImmutableImportEvidence())
            target.ledgerTransform = {
                it.copy(terminalSourcePageCanonicalFingerprint = cutoverSha("wrong-page"))
            }
            assertNull(control.reverifyImmutableImportEvidence())

            target.ledgerTransform = { it }
            source.records = listOf(masteryRecord(1), masteryRecord(2))
            assertNull(control.reverifyImmutableImportEvidence())
        }

    @Test
    fun completionReceiptIsBoundToTheFreshRawLedger() =
        runBlocking {
            val source = MasteryCutoverFakeLegacySource(listOf(masteryRecord(1)))
            val target = FakeMasteryTarget()
            migrator(source, target, pageSize = 2).migrate()
            val control = adapter(source, target, pageSize = 2)
            val fence =
                AuthorityCutoverFence.create(
                    authority = FencedAuthority.LEARNER_MASTERY,
                    cutoverGeneration = CUTOVER_GENERATION,
                    studentImportEvidenceFingerprint = cutoverSha("student-evidence"),
                    masteryImportEvidenceFingerprint = cutoverSha("mastery-evidence"),
                )
            val receipt = AuthorityCutoverCompletionReceipt.create(fence)

            assertEquals(fence, control.appendCutoverFenceIfAbsent(fence))
            assertEquals(receipt, control.appendCompletionReceiptIfAbsent(receipt))
            assertEquals(receipt, control.readCompletionReceipt())

            target.ledgerTransform = {
                it.copy(destinationCanonicalFingerprint = cutoverSha("changed-ledger"))
            }
            assertTrue(runCatching { control.readCompletionReceipt() }.isFailure)
        }

    @Test
    fun terminalMigrationAcceptsOnlyTheFixedLocalLearner() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalLearnerMasteryAuthorityMigrator(
                legacySource = MasteryCutoverFakeLegacySource(),
                targetMigration = FakeMasteryTarget(),
                targetCutover = FakeMasteryTarget(),
                learnerId = "learner:other",
                cutoverGeneration = CUTOVER_GENERATION,
            )
        }
    }

    private fun adapter(
        source: MasteryCutoverFakeLegacySource,
        target: FakeMasteryTarget,
        pageSize: Int,
    ) =
        LearnerMasteryAuthorityCutoverControlAdapter(
            target = target,
            legacySource = source,
            learnerId = LOCAL_LEARNER_ID,
            legacyPrefixReceiptFingerprint = LEGACY_PREFIX_RECEIPT,
            cutoverGeneration = CUTOVER_GENERATION,
            pageSize = pageSize,
        )

    private fun migrator(
        source: MasteryCutoverFakeLegacySource,
        target: FakeMasteryTarget,
        pageSize: Int,
    ) =
        TerminalLearnerMasteryAuthorityMigrator(
            legacySource = source,
            targetMigration = target,
            targetCutover = target,
            learnerId = LOCAL_LEARNER_ID,
            cutoverGeneration = CUTOVER_GENERATION,
            pageSize = pageSize,
        )
}

private class MasteryCutoverFakeLegacySource(
    initialRecords: List<LegacyMasteryFactMigrationRecord> = emptyList(),
) : LegacyAuthorityMigrationSourcePort {
    var records: List<LegacyMasteryFactMigrationRecord> = initialRecords

    override suspend fun readLegacyAuthorityMigrationSnapshot(
        learnerId: String,
    ): LegacyAuthorityMigrationSnapshot {
        require(learnerId == LOCAL_LEARNER_ID)
        return LegacyAuthorityMigrationSnapshot(
            schemaVersion = 40,
            studentDocumentRevisionCount = 0,
            studentSourceAssetLinkCount = 0,
            studentBrokenSourceAssetLinkCount = 0,
            studentSourceAssetByteCount = 0,
            studentProblemWithMultipleEntriesCount = 0,
            masterySourceFactCount = records.size.toLong(),
            masteryProvenSourceFactCount =
                records.count(LegacyMasteryFactMigrationRecord::hasSourceProof).toLong(),
            latestStudentMutationAtEpochMillis = 0,
            latestMasteryFactAtEpochMillis =
                records.maxOfOrNull { it.sourceFact.occurredAtEpochMillis } ?: 0,
        )
    }

    override suspend fun readLegacyStudentDocumentMigrationPage(
        afterExclusive: LegacyStudentDocumentMigrationCursor?,
        limit: Int,
    ): LegacyStudentDocumentMigrationPage =
        LegacyStudentDocumentMigrationPage(records = emptyList(), hasMore = false)

    override suspend fun readLegacyMasteryFactMigrationPage(
        learnerId: String,
        afterExclusive: LegacyMasteryFactMigrationCursor?,
        limit: Int,
    ): LegacyMasteryFactMigrationPage {
        require(learnerId == LOCAL_LEARNER_ID)
        val available =
            records.filter { record ->
                afterExclusive == null || record.cursor > afterExclusive
            }
        val selected = available.take(limit)
        return LegacyMasteryFactMigrationPage(
            records = selected,
            hasMore = available.size > selected.size,
        )
    }
}

private class FakeMasteryTarget :
    LearnerMasteryLegacyMigrationPort,
    MasteryTargetCutoverControlPort {
    override val learnerId: String = LOCAL_LEARNER_ID
    val pages = mutableListOf<LearnerMasteryLegacySnapshotPage>()
    var applyAttempts = 0
    var failOnceAfterPersistedPageIndex: Int? = null
    var ledgerTransform:
        (LearnerMasteryImmutableMigrationLedgerDigest) ->
            LearnerMasteryImmutableMigrationLedgerDigest = { it }
    private var fence: LearnerMasteryAuthorityCutoverFence? = null
    private var completionReceipt: LearnerMasteryAuthorityCutoverCompletionReceipt? = null

    override suspend fun applyPage(
        page: LearnerMasteryLegacySnapshotPage,
    ): LearnerMasteryLegacySnapshotPageResult {
        applyAttempts += 1
        pages.firstOrNull { it.batchSequence == page.batchSequence }?.let { existing ->
            return page.result(
                if (existing.canonicalFingerprint == page.canonicalFingerprint) {
                    LearnerMasteryLegacySnapshotPageDisposition.DUPLICATE
                } else {
                    LearnerMasteryLegacySnapshotPageDisposition.CONFLICT
                },
            )
        }
        check(pages.lastOrNull()?.finalBatch != true)
        check(page.batchSequence == pages.size.toLong() + 1L)
        check(page.afterExclusive == pages.lastOrNull()?.terminalCursor)
        pages += page
        if (failOnceAfterPersistedPageIndex == pages.lastIndex) {
            failOnceAfterPersistedPageIndex = null
            error("simulated crash after durable mastery page")
        }
        return page.result(LearnerMasteryLegacySnapshotPageDisposition.IMPORTED)
    }

    override suspend fun recomputeCompletedMigrationLedger(
        sourceGeneration: String,
    ): LearnerMasteryImmutableMigrationLedgerDigest? {
        val terminal = pages.lastOrNull()?.takeIf { it.finalBatch } ?: return null
        if (pages.any { it.sourceGeneration != sourceGeneration }) return null
        val count = pages.sumOf { it.snapshots.size }.toLong()
        val digest =
            CanonicalSha256("fake-mastery-raw-destination-v2")
                .field("sourceGeneration", sourceGeneration)
                .field("pageCount", pages.size)
                .field("snapshotCount", count)
        pages.forEachIndexed { index, page ->
            digest.field("page[$index]", page.canonicalFingerprint)
        }
        return ledgerTransform(
            LearnerMasteryImmutableMigrationLedgerDigest(
                learnerId = learnerId,
                sourceGeneration = sourceGeneration,
                migratedObservationCount = count,
                terminalBatchSequence = terminal.batchSequence,
                terminalBatchFingerprint = terminal.canonicalFingerprint,
                batchReceiptCount = pages.size,
                destinationCanonicalFingerprint = digest.finish(),
                destinationLedgerVersion = 2,
                rawSnapshotCount = count,
                terminalSourcePageCanonicalFingerprint =
                    terminal.sourcePageCanonicalFingerprint,
                terminalCursor = terminal.terminalCursor,
            ),
        )
    }

    override suspend fun readCutoverFence(): LearnerMasteryAuthorityCutoverFence? =
        fence

    override suspend fun appendCutoverFenceIfAbsent(
        candidate: LearnerMasteryAuthorityCutoverFence,
    ): LearnerMasteryAuthorityCutoverFence {
        fence?.let { return it }
        fence = candidate
        return candidate
    }

    override suspend fun readCompletionReceipt():
        LearnerMasteryAuthorityCutoverCompletionReceipt? =
        completionReceipt

    override suspend fun appendCompletionReceiptIfAbsent(
        candidate: LearnerMasteryAuthorityCutoverCompletionReceipt,
    ): LearnerMasteryAuthorityCutoverCompletionReceipt {
        completionReceipt?.let { return it }
        completionReceipt = candidate
        return candidate
    }

    override fun close() = Unit
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

private fun masteryRecord(index: Int): LegacyMasteryFactMigrationRecord =
    LegacyMasteryFactMigrationRecord(
        sourceFact =
            LearningObservationSourceFact(
                sourceFactId = "source-fact-$index",
                learnerScopeId = LOCAL_LEARNER_ID,
                source = LearningObservationSource.IMPORTED_MISTAKE,
                factKind = LearningObservationFactKind.IMPORTED_VISIBLE_ERROR,
                anchorId = "anchor-$index",
                subject = SubjectKind.MATH,
                conversationGeneration = null,
                conversationId = null,
                turnReceiptId = null,
                evidenceRequestId = null,
                responseFingerprint = cutoverSha("response-$index"),
                responseSummary = "visible error $index",
                occurredAtEpochMillis = index * 2_000L,
                sourceVersion = "source-v1",
            ),
        sourcePayloadCanonicalFingerprint = cutoverSha("payload-$index"),
        sourceProofCanonicalFingerprint = cutoverSha("proof-$index"),
        sourceReferenceId = "reference-$index",
        targetKind = "PROBLEM_REVISION",
        targetDatabase = "student-mistakes.db",
        targetId = "revision-$index",
        targetVersion = "1",
        targetCanonicalFingerprint = cutoverSha("target-$index"),
        attestedAtEpochMillis = index * 2_000L + 1L,
    )

private fun cutoverSha(value: String): String =
    CanonicalSha256("learner-mastery-cutover-adapter-test-v1")
        .field("value", value)
        .finish()

private const val CUTOVER_GENERATION = 7L
private val LEGACY_PREFIX_RECEIPT = cutoverSha("legacy-prefix")
