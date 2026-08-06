package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationPage
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationRecord
import com.tingyun.smartmistakebook.core.database.MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryAuthorityCutoverCompletionReceipt
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryAuthorityCutoverFence
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryCutoverControlPort as MasteryTargetCutoverControlPort
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryImmutableMigrationLedgerDigest
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacyMigrationCursor
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacyMigrationPort
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacyObservationSnapshot
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacySnapshotPage
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacySnapshotPageDisposition
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryLegacySnapshotPageResult
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID

/**
 * In-memory protocol adapter between the legacy coordinator and the independent mastery store.
 *
 * It owns neither database and never exposes a DAO. Every proof re-reads both the exact source and
 * the mastery store's immutable raw-snapshot ledger.
 */
internal class LearnerMasteryAuthorityCutoverControlAdapter(
    private val target: MasteryTargetCutoverControlPort,
    private val legacySource: LegacyAuthorityMigrationSourcePort,
    learnerId: String,
    private val legacyPrefixReceiptFingerprint: String,
    private val cutoverGeneration: Long,
    private val pageSize: Int = MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE,
) : LearnerMasteryCutoverControlPort {
    private val learnerId = learnerId.requireLocalLearner()
    private val manifestReader =
        ExactLegacyAuthorityManifestReader(
            source = legacySource,
            learnerId = learnerId,
            pageSize = pageSize,
        )

    init {
        require(cutoverGeneration > 0L) {
            "Mastery cutover generation must be positive"
        }
        require(legacyPrefixReceiptFingerprint.isLowercaseSha256()) {
            "Legacy prefix receipt fingerprint must be lowercase SHA-256"
        }
        require(pageSize in 1..MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE) {
            "Mastery terminal migration page size is outside the bounded source contract"
        }
    }

    override suspend fun readCutoverFence(): AuthorityCutoverFence? =
        target.readCutoverFence()?.toCoreDataFence()

    override suspend fun appendCutoverFenceIfAbsent(
        candidate: AuthorityCutoverFence,
    ): AuthorityCutoverFence =
        target.appendCutoverFenceIfAbsent(candidate.toMasteryFence()).toCoreDataFence()

    override suspend fun readCompletionReceipt(): AuthorityCutoverCompletionReceipt? {
        val persisted = target.readCompletionReceipt() ?: return null
        persisted.requireCurrentDestinationBinding()
        return persisted.toCoreDataReceipt()
    }

    override suspend fun appendCompletionReceiptIfAbsent(
        candidate: AuthorityCutoverCompletionReceipt,
    ): AuthorityCutoverCompletionReceipt {
        check(candidate.authority == FencedAuthority.LEARNER_MASTERY) {
            "Mastery cutover adapter rejected a foreign authority receipt"
        }
        check(candidate.hasValidFingerprint()) {
            "Mastery cutover completion receipt fingerprint is invalid"
        }
        val fence =
            checkNotNull(target.readCutoverFence()) {
                "Mastery cutover completion receipt requires its durable fence"
            }
        check(fence.toCoreDataFence().matches(candidate)) {
            "Mastery cutover completion receipt changed its durable fence"
        }
        val verified =
            checkNotNull(readVerifiedTargetState()) {
                "Mastery cutover completion receipt requires exact destination proof"
            }
        val targetCandidate =
            LearnerMasteryAuthorityCutoverCompletionReceipt.create(
                fence = fence,
                ledger = verified.ledger,
            )
        check(targetCandidate.toCoreDataReceipt() == candidate) {
            "Mastery cutover completion receipt dropped its destination binding"
        }
        val persisted = target.appendCompletionReceiptIfAbsent(targetCandidate)
        persisted.requireCurrentDestinationBinding()
        return persisted.toCoreDataReceipt().also { mapped ->
            check(mapped == candidate) {
                "Mastery target already contains a conflicting completion receipt"
            }
        }
    }

    override suspend fun reverifyImmutableImportEvidence():
        ImmutableAuthorityImportEvidence? {
        val verified = readVerifiedTargetState() ?: return null
        val manifest = verified.expectation.manifest
        return ImmutableAuthorityImportEvidence.create(
            authority = FencedAuthority.LEARNER_MASTERY,
            cutoverGeneration = cutoverGeneration,
            migratedRecordCount = manifest.recordCount,
            sourceCheckpoint = manifest.sourceCheckpoint,
            legacyPrefixReceiptFingerprint = legacyPrefixReceiptFingerprint,
            sourceFingerprint = manifest.sourceCanonicalFingerprint,
            destinationFingerprint = verified.ledger.destinationCanonicalFingerprint,
        ).also { evidence ->
            check(evidence.hasValidFingerprint()) {
                "Mastery immutable import evidence fingerprint is invalid"
            }
        }
    }

    override fun close() {
        target.close()
    }

    private suspend fun LearnerMasteryAuthorityCutoverCompletionReceipt
        .requireCurrentDestinationBinding() {
        check(hasValidFingerprint()) {
            "Persisted mastery cutover completion receipt fingerprint is invalid"
        }
        check(learnerId == this@LearnerMasteryAuthorityCutoverControlAdapter.learnerId) {
            "Persisted mastery completion receipt belongs to another learner"
        }
        val verified =
            checkNotNull(readVerifiedTargetState()) {
                "Persisted mastery completion receipt has no current destination proof"
            }
        check(sourceGeneration == verified.expectation.manifest.sourceCanonicalFingerprint) {
            "Persisted mastery completion receipt names another source generation"
        }
        check(
            migrationLedgerCanonicalDigest ==
                verified.ledger.destinationCanonicalFingerprint,
        ) {
            "Persisted mastery completion receipt changed its destination ledger"
        }
    }

    private suspend fun readVerifiedTargetState(): VerifiedMasteryTargetState? {
        val expectation =
            readStableMasterySourceExpectation(
                source = legacySource,
                manifestReader = manifestReader,
                learnerId = learnerId,
                pageSize = pageSize,
            )
        val ledger =
            target.recomputeCompletedMigrationLedger(
                expectation.manifest.sourceCanonicalFingerprint,
            ) ?: return null
        if (!ledger.matches(expectation)) return null
        return VerifiedMasteryTargetState(expectation, ledger)
    }
}

/** Read-only startup fence probe used before a durable prefix/generation exists. */
internal fun MasteryTargetCutoverControlPort.asMasteryLegacyFenceInspectionPort():
    LearnerMasteryCutoverControlPort =
    object : LearnerMasteryCutoverControlPort {
        override suspend fun readCutoverFence(): AuthorityCutoverFence? =
            this@asMasteryLegacyFenceInspectionPort.readCutoverFence()?.toCoreDataFence()

        override suspend fun readCompletionReceipt(): AuthorityCutoverCompletionReceipt? =
            this@asMasteryLegacyFenceInspectionPort.readCompletionReceipt()?.toCoreDataReceipt()

        override suspend fun appendCutoverFenceIfAbsent(
            candidate: AuthorityCutoverFence,
        ): AuthorityCutoverFence =
            throw SecurityException("Fence inspection cannot append a mastery cutover fence")

        override suspend fun appendCompletionReceiptIfAbsent(
            candidate: AuthorityCutoverCompletionReceipt,
        ): AuthorityCutoverCompletionReceipt =
            throw SecurityException("Fence inspection cannot append a mastery completion receipt")

        override suspend fun reverifyImmutableImportEvidence():
            ImmutableAuthorityImportEvidence? =
            throw SecurityException("Fence inspection cannot issue mastery import evidence")

        override fun close() = this@asMasteryLegacyFenceInspectionPort.close()
    }

/**
 * Performs the terminal, lossless mastery import while the caller holds legacy-writer exclusion.
 *
 * Recovery deliberately replays source pages from the first key. Exact duplicates are accepted;
 * any changed page or changed source generation fails closed.
 */
internal class TerminalLearnerMasteryAuthorityMigrator(
    private val legacySource: LegacyAuthorityMigrationSourcePort,
    private val targetMigration: LearnerMasteryLegacyMigrationPort,
    private val targetCutover: MasteryTargetCutoverControlPort,
    learnerId: String,
    private val cutoverGeneration: Long,
    private val pageSize: Int = MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE,
) {
    private val learnerId = learnerId.requireLocalLearner()
    private val manifestReader =
        ExactLegacyAuthorityManifestReader(
            source = legacySource,
            learnerId = learnerId,
            pageSize = pageSize,
        )

    init {
        require(targetMigration.learnerId == this.learnerId) {
            "Mastery migration target belongs to another learner"
        }
        require(cutoverGeneration > 0L) {
            "Mastery cutover generation must be positive"
        }
        require(pageSize in 1..MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE) {
            "Mastery terminal migration page size is outside the bounded source contract"
        }
    }

    suspend fun migrate(): TerminalLearnerMasteryAuthorityMigrationResult {
        val initialManifest = manifestReader.readStableManifests().masteryFacts
        val sourceGeneration = initialManifest.sourceCanonicalFingerprint
        var cursor: LegacyMasteryFactMigrationCursor? = null
        var batchSequence = 1L
        var recordCount = 0L
        var pageReceiptCount = 0
        var terminalSourcePageFingerprint: String? = null
        var terminalPageReceiptFingerprint: String? = null

        while (true) {
            val sourcePage =
                legacySource.readLegacyMasteryFactMigrationPage(
                    learnerId = learnerId,
                    afterExclusive = cursor,
                    limit = pageSize,
                )
            sourcePage.requireStableShape(pageSize)
            val targetPage =
                sourcePage.toTargetPage(
                    learnerId = learnerId,
                    sourceGeneration = sourceGeneration,
                    batchSequence = batchSequence,
                    afterExclusive = cursor,
                )
            val result = targetMigration.applyPage(targetPage)
            result.requireExactResult(targetPage)

            recordCount = Math.addExact(recordCount, sourcePage.records.size.toLong())
            pageReceiptCount += 1
            terminalSourcePageFingerprint = targetPage.sourcePageCanonicalFingerprint
            terminalPageReceiptFingerprint = targetPage.canonicalFingerprint
            cursor = sourcePage.records.lastOrNull()?.cursor ?: cursor
            if (!sourcePage.hasMore) break
            batchSequence = Math.addExact(batchSequence, 1L)
        }

        check(recordCount == initialManifest.recordCount) {
            "Terminal mastery migration did not cover the exact source record count"
        }
        check(cursor == initialManifest.terminalCursor) {
            "Terminal mastery migration did not reach the exact source boundary"
        }
        val finalManifest = manifestReader.readStableManifests().masteryFacts
        check(finalManifest == initialManifest) {
            "Legacy mastery source changed during terminal migration"
        }
        val expectation =
            MasterySourceLedgerExpectation(
                manifest = finalManifest,
                pageReceiptCount = pageReceiptCount,
                terminalSourcePageFingerprint =
                    checkNotNull(terminalSourcePageFingerprint),
                terminalPageReceiptFingerprint =
                    checkNotNull(terminalPageReceiptFingerprint),
            )
        val ledger =
            checkNotNull(targetCutover.recomputeCompletedMigrationLedger(sourceGeneration)) {
                "Mastery target did not produce a verified terminal migration ledger"
            }
        check(ledger.matches(expectation)) {
            "Mastery target terminal ledger does not match the exact source"
        }
        return TerminalLearnerMasteryAuthorityMigrationResult(
            cutoverGeneration = cutoverGeneration,
            sourceGeneration = sourceGeneration,
            manifest = finalManifest,
            ledgerDigest = ledger,
        )
    }
}

internal data class TerminalLearnerMasteryAuthorityMigrationResult(
    val cutoverGeneration: Long,
    val sourceGeneration: String,
    val manifest: ExactLegacyMasteryFactManifest,
    val ledgerDigest: LearnerMasteryImmutableMigrationLedgerDigest,
) {
    init {
        require(cutoverGeneration > 0L)
        require(sourceGeneration == manifest.sourceCanonicalFingerprint)
        require(ledgerDigest.sourceGeneration == sourceGeneration)
    }
}

private data class MasterySourceLedgerExpectation(
    val manifest: ExactLegacyMasteryFactManifest,
    val pageReceiptCount: Int,
    val terminalSourcePageFingerprint: String,
    val terminalPageReceiptFingerprint: String,
) {
    init {
        require(pageReceiptCount > 0)
        require(terminalSourcePageFingerprint.isLowercaseSha256())
        require(terminalPageReceiptFingerprint.isLowercaseSha256())
    }
}

private data class VerifiedMasteryTargetState(
    val expectation: MasterySourceLedgerExpectation,
    val ledger: LearnerMasteryImmutableMigrationLedgerDigest,
)

private suspend fun readStableMasterySourceExpectation(
    source: LegacyAuthorityMigrationSourcePort,
    manifestReader: ExactLegacyAuthorityManifestReader,
    learnerId: String,
    pageSize: Int,
): MasterySourceLedgerExpectation {
    val before = manifestReader.readStableManifests().masteryFacts
    var cursor: LegacyMasteryFactMigrationCursor? = null
    var batchSequence = 1L
    var recordCount = 0L
    var pageReceiptCount = 0
    var terminalSourcePageFingerprint: String? = null
    var terminalPageReceiptFingerprint: String? = null
    while (true) {
        val sourcePage =
            source.readLegacyMasteryFactMigrationPage(
                learnerId = learnerId,
                afterExclusive = cursor,
                limit = pageSize,
            )
        sourcePage.requireStableShape(pageSize)
        val targetPage =
            sourcePage.toTargetPage(
                learnerId = learnerId,
                sourceGeneration = before.sourceCanonicalFingerprint,
                batchSequence = batchSequence,
                afterExclusive = cursor,
            )
        recordCount = Math.addExact(recordCount, sourcePage.records.size.toLong())
        pageReceiptCount += 1
        terminalSourcePageFingerprint = targetPage.sourcePageCanonicalFingerprint
        terminalPageReceiptFingerprint = targetPage.canonicalFingerprint
        cursor = sourcePage.records.lastOrNull()?.cursor ?: cursor
        if (!sourcePage.hasMore) break
        batchSequence = Math.addExact(batchSequence, 1L)
    }
    check(recordCount == before.recordCount && cursor == before.terminalCursor) {
        "Legacy mastery page ledger does not cover its exact manifest"
    }
    val after = manifestReader.readStableManifests().masteryFacts
    check(after == before) {
        "Legacy mastery source changed while its terminal page ledger was read"
    }
    return MasterySourceLedgerExpectation(
        manifest = after,
        pageReceiptCount = pageReceiptCount,
        terminalSourcePageFingerprint =
            checkNotNull(terminalSourcePageFingerprint),
        terminalPageReceiptFingerprint =
            checkNotNull(terminalPageReceiptFingerprint),
    )
}

private fun LegacyMasteryFactMigrationPage.toTargetPage(
    learnerId: String,
    sourceGeneration: String,
    batchSequence: Long,
    afterExclusive: LegacyMasteryFactMigrationCursor?,
): LearnerMasteryLegacySnapshotPage {
    val snapshots = records.map { record -> record.toRawSnapshot(learnerId) }
    return LearnerMasteryLegacySnapshotPage(
        learnerId = learnerId,
        sourceGeneration = sourceGeneration,
        batchSequence = batchSequence,
        sourcePageCanonicalFingerprint =
            masteryPageFingerprint(
                learnerId = learnerId,
                afterExclusive = afterExclusive,
                records = records,
            ),
        afterExclusive = afterExclusive?.toTargetCursor(),
        snapshots = snapshots,
        finalBatch = !hasMore,
    )
}

private fun LegacyMasteryFactMigrationRecord.toRawSnapshot(
    learnerId: String,
): LearnerMasteryLegacyObservationSnapshot {
    val fact = sourceFact
    check(fact.learnerScopeId == learnerId) {
        "Legacy mastery record belongs to another learner"
    }
    return LearnerMasteryLegacyObservationSnapshot(
        sourceFactId = fact.sourceFactId,
        learnerId = fact.learnerScopeId,
        source = fact.source.name,
        factKind = fact.factKind.name,
        anchorId = fact.anchorId,
        subject = fact.subject.name,
        conversationGeneration = fact.conversationGeneration,
        conversationId = fact.conversationId,
        turnReceiptId = fact.turnReceiptId,
        evidenceRequestId = fact.evidenceRequestId,
        responseFingerprint = fact.responseFingerprint,
        responseSummary = fact.responseSummary,
        occurredAtEpochMillis = fact.occurredAtEpochMillis,
        sourceVersion = fact.sourceVersion,
        sourcePayloadCanonicalFingerprint = sourcePayloadCanonicalFingerprint,
        proofPresent = hasSourceProof,
        sourceProofCanonicalFingerprint = sourceProofCanonicalFingerprint,
        sourceReferenceId = sourceReferenceId,
        targetKind = targetKind,
        targetDatabase = targetDatabase,
        targetId = targetId,
        targetVersion = targetVersion,
        targetCanonicalFingerprint = targetCanonicalFingerprint,
        attestedAtEpochMillis = attestedAtEpochMillis,
        sourceRecordCanonicalFingerprint = masteryRecordFingerprint(this),
    )
}

private fun LegacyMasteryFactMigrationPage.requireStableShape(
    requestedPageSize: Int,
) {
    check(records.size <= requestedPageSize) {
        "Legacy mastery source exceeded the requested page size"
    }
    check(!hasMore || records.size == requestedPageSize) {
        "Legacy mastery source returned an unstable short non-terminal page"
    }
}

private fun LearnerMasteryLegacySnapshotPageResult.requireExactResult(
    page: LearnerMasteryLegacySnapshotPage,
) {
    check(
        disposition == LearnerMasteryLegacySnapshotPageDisposition.IMPORTED ||
            disposition == LearnerMasteryLegacySnapshotPageDisposition.DUPLICATE,
    ) {
        "Mastery target rejected an exact terminal source page: $disposition"
    }
    check(learnerId == page.learnerId)
    check(sourceGeneration == page.sourceGeneration)
    check(batchSequence == page.batchSequence)
    check(sourcePageCanonicalFingerprint == page.sourcePageCanonicalFingerprint)
    check(snapshotCount == page.snapshots.size)
    check(finalBatch == page.finalBatch)
    check(pageReceiptCanonicalFingerprint == page.canonicalFingerprint)
}

private fun LearnerMasteryImmutableMigrationLedgerDigest.matches(
    expectation: MasterySourceLedgerExpectation,
): Boolean {
    val manifest = expectation.manifest
    return learnerId == manifest.learnerId &&
        sourceGeneration == manifest.sourceCanonicalFingerprint &&
        migratedObservationCount == manifest.recordCount &&
        destinationLedgerVersion == COMPLETE_MASTERY_DESTINATION_LEDGER_VERSION &&
        destinationCanonicalLayoutVersion ==
            COMPLETE_MASTERY_DESTINATION_CANONICAL_LAYOUT_VERSION &&
        rawSnapshotCount == manifest.recordCount &&
        terminalCursor == manifest.terminalCursor?.toTargetCursor() &&
        terminalBatchSequence == expectation.pageReceiptCount.toLong() &&
        terminalBatchFingerprint == expectation.terminalPageReceiptFingerprint &&
        terminalSourcePageCanonicalFingerprint ==
            expectation.terminalSourcePageFingerprint &&
        batchReceiptCount == expectation.pageReceiptCount
}

private fun AuthorityCutoverFence.toMasteryFence():
    LearnerMasteryAuthorityCutoverFence {
    check(authority == FencedAuthority.LEARNER_MASTERY) {
        "Mastery cutover adapter rejected a foreign authority fence"
    }
    check(hasValidFingerprint()) {
        "Mastery cutover fence fingerprint is invalid"
    }
    return LearnerMasteryAuthorityCutoverFence(
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

private fun LearnerMasteryAuthorityCutoverFence.toCoreDataFence():
    AuthorityCutoverFence {
    check(hasValidFingerprint()) {
        "Persisted mastery cutover fence fingerprint is invalid"
    }
    return AuthorityCutoverFence(
        authority = FencedAuthority.LEARNER_MASTERY,
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

private fun LearnerMasteryAuthorityCutoverCompletionReceipt.toCoreDataReceipt():
    AuthorityCutoverCompletionReceipt {
    check(hasValidFingerprint()) {
        "Persisted mastery cutover completion receipt fingerprint is invalid"
    }
    return AuthorityCutoverCompletionReceipt(
        authority = FencedAuthority.LEARNER_MASTERY,
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

private fun AuthorityCutoverFence.matches(
    receipt: AuthorityCutoverCompletionReceipt,
): Boolean =
    authority == receipt.authority &&
        cutoverGeneration == receipt.cutoverGeneration &&
        cutoverIntentFingerprint == receipt.cutoverIntentFingerprint &&
        fenceFingerprint == receipt.authorityFenceFingerprint

private fun LegacyMasteryFactMigrationCursor.toTargetCursor():
    LearnerMasteryLegacyMigrationCursor =
    LearnerMasteryLegacyMigrationCursor(
        occurredAtEpochMillis = occurredAtEpochMillis,
        sourceFactId = sourceFactId,
    )

private fun String.requireLocalLearner(): String {
    require(this == LOCAL_LEARNER_ID) {
        "Mastery terminal migration is defined only for the fixed local learner"
    }
    return this
}

private fun String.isLowercaseSha256(): Boolean =
    LOWERCASE_SHA_256.matches(this)

private const val COMPLETE_MASTERY_DESTINATION_LEDGER_VERSION = 2
private const val COMPLETE_MASTERY_DESTINATION_CANONICAL_LAYOUT_VERSION = 2
private val LOWERCASE_SHA_256 = Regex("[0-9a-f]{64}")
