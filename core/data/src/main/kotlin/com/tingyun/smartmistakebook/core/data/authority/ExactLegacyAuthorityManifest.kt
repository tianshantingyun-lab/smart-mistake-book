package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSnapshot
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyMasteryFactMigrationRecord
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationCursor
import com.tingyun.smartmistakebook.core.database.LegacyStudentDocumentMigrationRecord
import com.tingyun.smartmistakebook.core.database.MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * Exact, content-addressed manifests for the two business datasets still present in the legacy
 * migration source.
 *
 * Counts and latest timestamps are useful diagnostics, but cannot prove migration coverage. These
 * manifests hash every immutable source field in keyset order. They are assembled in memory from
 * bounded reads and never join authority databases.
 */
internal data class ExactLegacyAuthorityManifests(
    val studentDocuments: ExactLegacyStudentDocumentManifest,
    val masteryFacts: ExactLegacyMasteryFactManifest,
)

internal data class ExactLegacyStudentDocumentManifest(
    val learnerId: String,
    val legacySchemaVersion: Int,
    val recordCount: Long,
    val sourceAssetLinkCount: Long,
    val brokenSourceAssetLinkCount: Long,
    val sourceAssetByteCount: Long,
    val terminalCursor: LegacyStudentDocumentMigrationCursor?,
    val sourceCheckpoint: String,
    val terminalBoundaryCanonicalFingerprint: String,
    val sourceCanonicalFingerprint: String,
) {
    init {
        require(learnerId.isValidManifestText())
        require(legacySchemaVersion > 0)
        require(recordCount >= 0L)
        require(sourceAssetLinkCount >= 0L)
        require(brokenSourceAssetLinkCount in 0L..sourceAssetLinkCount)
        require(sourceAssetByteCount >= 0L)
        require(sourceCheckpoint.isSha256Fingerprint())
        require(terminalBoundaryCanonicalFingerprint.isSha256Fingerprint())
        require(sourceCanonicalFingerprint.isSha256Fingerprint())
        require((recordCount == 0L) == (terminalCursor == null))
    }
}

internal data class ExactLegacyMasteryFactManifest(
    val learnerId: String,
    val legacySchemaVersion: Int,
    val recordCount: Long,
    val provenRecordCount: Long,
    val terminalCursor: LegacyMasteryFactMigrationCursor?,
    val sourceCheckpoint: String,
    val terminalBoundaryCanonicalFingerprint: String,
    val sourceCanonicalFingerprint: String,
) {
    init {
        require(learnerId.isValidManifestText())
        require(legacySchemaVersion > 0)
        require(recordCount >= 0L)
        require(provenRecordCount in 0L..recordCount)
        require(sourceCheckpoint.isSha256Fingerprint())
        require(terminalBoundaryCanonicalFingerprint.isSha256Fingerprint())
        require(sourceCanonicalFingerprint.isSha256Fingerprint())
        require((recordCount == 0L) == (terminalCursor == null))
    }
}

/**
 * Reads each exact manifest twice and accepts it only when both complete scans agree.
 *
 * The terminal coordinator additionally holds the process-wide legacy-writer exclusion. The
 * second scan protects tests, adapters, and future callers from unstable or incorrectly paged
 * source implementations without pretending that count/timestamp snapshots prove content.
 */
internal class ExactLegacyAuthorityManifestReader(
    private val source: LegacyAuthorityMigrationSourcePort,
    private val learnerId: String,
    private val pageSize: Int = MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE,
) {
    init {
        require(learnerId.isValidManifestText()) {
            "Legacy mastery manifest learner id is invalid"
        }
        require(pageSize in 1..MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE) {
            "Legacy manifest page size is outside the bounded source contract"
        }
    }

    suspend fun readStableManifests(): ExactLegacyAuthorityManifests {
        val before = source.readLegacyAuthorityMigrationSnapshot(learnerId)
        requireSnapshotCanRepresentExactImport(before)
        val first = readOnce(before.schemaVersion)
        requireSnapshotCovers(before, first)
        val between = source.readLegacyAuthorityMigrationSnapshot(learnerId)
        check(between == before) {
            "Legacy authority source changed during the first exact manifest scan"
        }
        val second = readOnce(between.schemaVersion)
        requireSnapshotCovers(between, second)
        val after = source.readLegacyAuthorityMigrationSnapshot(learnerId)
        check(after == between) {
            "Legacy authority source changed during the second exact manifest scan"
        }
        check(first == second) {
            "Legacy authority source changed while exact manifests were being read"
        }
        return second
    }

    internal suspend fun readOnce(
        legacySchemaVersion: Int,
    ): ExactLegacyAuthorityManifests {
        require(legacySchemaVersion > 0)
        return ExactLegacyAuthorityManifests(
            studentDocuments = readStudentDocuments(legacySchemaVersion),
            masteryFacts = readMasteryFacts(legacySchemaVersion),
        )
    }

    private suspend fun readStudentDocuments(
        legacySchemaVersion: Int,
    ): ExactLegacyStudentDocumentManifest {
        var cursor: LegacyStudentDocumentMigrationCursor? = null
        var recordCount = 0L
        var sourceAssetLinkCount = 0L
        var sourceAssetByteCount = 0L
        var canonicalByteBudget = 0L
        var terminalRecordFingerprint: String? = null
        val manifest =
            CanonicalSha256(STUDENT_MANIFEST_DOMAIN)
                .field("manifestVersion", EXACT_MANIFEST_VERSION)
                .field("learnerId", learnerId)
                .field("legacySchemaVersion", legacySchemaVersion)

        while (true) {
            val page =
                source.readLegacyStudentDocumentMigrationPage(
                    afterExclusive = cursor,
                    limit = pageSize,
                )
            check(page.records.size <= pageSize) {
                "Legacy student source exceeded the requested page size"
            }
            check(!page.hasMore || page.records.size == pageSize) {
                "Legacy student source returned an unstable short non-terminal page"
            }
            validateStudentPage(cursor, page.records)
            page.records.forEach { record ->
                check(recordCount < MAX_MANIFEST_RECORDS) {
                    "Legacy student manifest exceeded its record budget"
                }
                canonicalByteBudget += record.maximumCanonicalByteSize()
                check(canonicalByteBudget <= MAX_MANIFEST_CANONICAL_BYTES) {
                    "Legacy student manifest exceeded its canonical byte budget"
                }
                val recordFingerprint = studentRecordFingerprint(record)
                manifest.field(
                    "record[$recordCount]",
                    recordFingerprint,
                )
                terminalRecordFingerprint = recordFingerprint
                recordCount += 1L
                sourceAssetLinkCount =
                    Math.addExact(sourceAssetLinkCount, record.sourceAssets.size.toLong())
                record.sourceAssets.forEach { linked ->
                    sourceAssetByteCount =
                        Math.addExact(
                            sourceAssetByteCount,
                            linked.sourceAsset.byteSize,
                        )
                }
            }
            cursor = page.records.lastOrNull()?.cursor ?: cursor
            if (!page.hasMore) {
                break
            }
        }

        val terminalCheckpoint = studentCheckpointFingerprint(cursor)
        val terminalBoundary =
            studentBoundaryFingerprint(
                cursor = cursor,
                terminalRecordFingerprint = terminalRecordFingerprint,
            )
        return ExactLegacyStudentDocumentManifest(
            learnerId = learnerId,
            legacySchemaVersion = legacySchemaVersion,
            recordCount = recordCount,
            sourceAssetLinkCount = sourceAssetLinkCount,
            brokenSourceAssetLinkCount = 0L,
            sourceAssetByteCount = sourceAssetByteCount,
            terminalCursor = cursor,
            sourceCheckpoint = terminalCheckpoint,
            terminalBoundaryCanonicalFingerprint = terminalBoundary,
            sourceCanonicalFingerprint =
                manifest
                    .field("recordCount", recordCount)
                    .field("sourceAssetLinkCount", sourceAssetLinkCount)
                    .field("brokenSourceAssetLinkCount", 0L)
                    .field("sourceAssetByteCount", sourceAssetByteCount)
                    .field("terminalCheckpoint", terminalCheckpoint)
                    .field("terminalBoundary", terminalBoundary)
                    .finish(),
        )
    }

    private suspend fun readMasteryFacts(
        legacySchemaVersion: Int,
    ): ExactLegacyMasteryFactManifest {
        var cursor: LegacyMasteryFactMigrationCursor? = null
        var recordCount = 0L
        var provenRecordCount = 0L
        var canonicalByteBudget = 0L
        var terminalRecordFingerprint: String? = null
        val manifest =
            CanonicalSha256(MASTERY_MANIFEST_DOMAIN)
                .field("manifestVersion", EXACT_MANIFEST_VERSION)
                .field("learnerId", learnerId)
                .field("legacySchemaVersion", legacySchemaVersion)

        while (true) {
            val page =
                source.readLegacyMasteryFactMigrationPage(
                    learnerId = learnerId,
                    afterExclusive = cursor,
                    limit = pageSize,
                )
            check(page.records.size <= pageSize) {
                "Legacy mastery source exceeded the requested page size"
            }
            check(!page.hasMore || page.records.size == pageSize) {
                "Legacy mastery source returned an unstable short non-terminal page"
            }
            validateMasteryPage(cursor, page.records)
            page.records.forEach { record ->
                check(recordCount < MAX_MANIFEST_RECORDS) {
                    "Legacy mastery manifest exceeded its record budget"
                }
                canonicalByteBudget += record.maximumCanonicalByteSize()
                check(canonicalByteBudget <= MAX_MANIFEST_CANONICAL_BYTES) {
                    "Legacy mastery manifest exceeded its canonical byte budget"
                }
                val recordFingerprint = masteryRecordFingerprint(record)
                manifest.field(
                    "record[$recordCount]",
                    recordFingerprint,
                )
                terminalRecordFingerprint = recordFingerprint
                recordCount += 1L
                if (record.hasSourceProof) {
                    provenRecordCount += 1L
                }
            }
            cursor = page.records.lastOrNull()?.cursor ?: cursor
            if (!page.hasMore) {
                break
            }
        }

        val terminalCheckpoint = masteryCheckpointFingerprint(cursor)
        val terminalBoundary =
            masteryBoundaryFingerprint(
                cursor = cursor,
                terminalRecordFingerprint = terminalRecordFingerprint,
            )
        return ExactLegacyMasteryFactManifest(
            learnerId = learnerId,
            legacySchemaVersion = legacySchemaVersion,
            recordCount = recordCount,
            provenRecordCount = provenRecordCount,
            terminalCursor = cursor,
            sourceCheckpoint = terminalCheckpoint,
            terminalBoundaryCanonicalFingerprint = terminalBoundary,
            sourceCanonicalFingerprint =
                manifest
                    .field("recordCount", recordCount)
                    .field("provenRecordCount", provenRecordCount)
                    .field("terminalCheckpoint", terminalCheckpoint)
                    .field("terminalBoundary", terminalBoundary)
                    .finish(),
        )
    }

    private fun requireSnapshotCanRepresentExactImport(
        snapshot: LegacyAuthorityMigrationSnapshot,
    ) {
        check(snapshot.studentProblemWithMultipleEntriesCount == 0L) {
            "Legacy student source has ambiguous duplicate collection entries"
        }
        check(snapshot.studentProblemCurrentRevisionNotLatestCount == 0L) {
            "Legacy student source cannot preserve its current revision exactly"
        }
        check(snapshot.studentBrokenSourceAssetLinkCount == 0L) {
            "Legacy student source contains broken source-asset links"
        }
    }

    private fun requireSnapshotCovers(
        snapshot: LegacyAuthorityMigrationSnapshot,
        manifests: ExactLegacyAuthorityManifests,
    ) {
        check(
            manifests.studentDocuments.recordCount ==
                snapshot.studentDocumentRevisionCount,
        ) {
            "Exact student manifest does not cover every legacy document revision"
        }
        check(
            manifests.studentDocuments.sourceAssetLinkCount ==
                snapshot.studentSourceAssetLinkCount,
        ) {
            "Exact student manifest does not cover every legacy source-asset link"
        }
        check(
            manifests.studentDocuments.brokenSourceAssetLinkCount ==
                snapshot.studentBrokenSourceAssetLinkCount,
        ) {
            "Exact student manifest does not account for broken source-asset links"
        }
        check(
            manifests.studentDocuments.sourceAssetByteCount ==
                snapshot.studentSourceAssetByteCount,
        ) {
            "Exact student manifest source-asset bytes do not match the legacy snapshot"
        }
        check(manifests.masteryFacts.recordCount == snapshot.masterySourceFactCount) {
            "Exact mastery manifest does not cover every legacy source fact"
        }
        check(
            manifests.masteryFacts.provenRecordCount ==
                snapshot.masteryProvenSourceFactCount,
        ) {
            "Exact mastery manifest does not cover every legacy source proof"
        }
    }
}

internal fun studentPageFingerprint(
    afterExclusive: LegacyStudentDocumentMigrationCursor?,
    records: List<LegacyStudentDocumentMigrationRecord>,
): String {
    validateStudentPage(afterExclusive, records)
    val digest =
        CanonicalSha256(STUDENT_PAGE_DOMAIN)
            .field("manifestVersion", EXACT_MANIFEST_VERSION)
            .nullableField(
                "afterCommittedAtEpochMillis",
                afterExclusive?.committedAtEpochMillis?.toString(),
            )
            .nullableField("afterProblemId", afterExclusive?.problemId)
            .nullableField(
                "afterRevisionNumber",
                afterExclusive?.revisionNumber?.toString(),
            )
            .nullableField("afterRevisionId", afterExclusive?.revisionId)
            .field("recordCount", records.size)
    records.forEachIndexed { index, record ->
        digest.field("record[$index]", studentRecordFingerprint(record))
    }
    return digest.finish()
}

internal fun masteryPageFingerprint(
    learnerId: String,
    afterExclusive: LegacyMasteryFactMigrationCursor?,
    records: List<LegacyMasteryFactMigrationRecord>,
): String {
    require(learnerId.isValidManifestText())
    validateMasteryPage(afterExclusive, records)
    val digest =
        CanonicalSha256(MASTERY_PAGE_DOMAIN)
            .field("manifestVersion", EXACT_MANIFEST_VERSION)
            .field("learnerId", learnerId)
            .nullableField(
                "afterOccurredAtEpochMillis",
                afterExclusive?.occurredAtEpochMillis?.toString(),
            )
            .nullableField("afterSourceFactId", afterExclusive?.sourceFactId)
            .field("recordCount", records.size)
    records.forEachIndexed { index, record ->
        digest.field("record[$index]", masteryRecordFingerprint(record))
    }
    return digest.finish()
}

private fun studentRecordFingerprint(
    record: LegacyStudentDocumentMigrationRecord,
): String {
    val digest =
        CanonicalSha256(STUDENT_RECORD_DOMAIN)
            .field("entryId", record.entryId)
            .field("problemId", record.problemId)
            .field("problemCanonicalFingerprint", record.problemCanonicalFingerprint)
            .field("revisionId", record.revisionId)
            .field("revisionNumber", record.revisionNumber)
            .field("subject", record.subject)
            .field(
                "problemCreatedAtEpochMillis",
                record.problemCreatedAtEpochMillis,
            )
            .nullableField(
                "problemArchivedAtEpochMillis",
                record.problemArchivedAtEpochMillis?.toString(),
            )
            .field("title", record.title)
            .field("problemMarkdown", record.problemMarkdown)
            .nullableField("questionDocumentSnapshot", record.questionDocumentSnapshot)
            .nullableField("answerSpecId", record.answerSpecId)
            .nullableField("answerSpecSnapshot", record.answerSpecSnapshot)
            .field("answerVerificationStatus", record.answerVerificationStatus)
            .field("revisionSourceType", record.revisionSourceType)
            .nullableField(
                "revisionSourceReference",
                record.revisionSourceReference,
            )
            .field("contentFingerprint", record.contentFingerprint)
            .field("practiceUnitId", record.practiceUnitId)
            .field("practiceUnitKey", record.practiceUnitKey)
            .field("practiceUnitKind", record.practiceUnitKind)
            .field("practiceUnitTitle", record.practiceUnitTitle)
            .field(
                "practiceUnitPromptMarkdown",
                record.practiceUnitPromptMarkdown,
            )
            .field("estimatedSeconds", record.estimatedSeconds)
            .field("practiceUnitRevisionId", record.practiceUnitRevisionId)
            .field(
                "practiceUnitCreatedAtEpochMillis",
                record.practiceUnitCreatedAtEpochMillis,
            )
            .field("entryCurrentRevisionId", record.entryCurrentRevisionId)
            .nullableField("sourceKey", record.sourceKey)
            .field("status", record.status)
            .field("acceptedAtEpochMillis", record.acceptedAtEpochMillis)
            .field("updatedAtEpochMillis", record.updatedAtEpochMillis)
            .field("revisionCreatedAtEpochMillis", record.revisionCreatedAtEpochMillis)
            .field("sourceAssetCount", record.sourceAssets.size)
    record.sourceAssets.forEachIndexed { index, linked ->
        val asset = linked.sourceAsset
        digest
            .field("sourceAsset[$index].role", linked.role)
            .nullableField(
                "sourceAsset[$index].pageIndex",
                linked.pageIndex?.toString(),
            )
            .field("sourceAsset[$index].sourceAssetId", asset.sourceAssetId)
            .field("sourceAsset[$index].contentSha256", asset.contentSha256)
            .field("sourceAsset[$index].relativePath", asset.relativePath)
            .field("sourceAsset[$index].mimeType", asset.mimeType)
            .field("sourceAsset[$index].byteSize", asset.byteSize)
            .field("sourceAsset[$index].width", asset.width)
            .field("sourceAsset[$index].height", asset.height)
            .field("sourceAsset[$index].sourceType", asset.sourceType)
            .field(
                "sourceAsset[$index].createdAtEpochMillis",
                asset.createdAtEpochMillis,
            )
    }
    return digest.finish()
}

internal fun masteryRecordFingerprint(
    record: LegacyMasteryFactMigrationRecord,
): String {
    val fact = record.sourceFact
    return CanonicalSha256(MASTERY_RECORD_DOMAIN)
        .field("sourceFactId", fact.sourceFactId)
        .field("learnerScopeId", fact.learnerScopeId)
        .field("source", fact.source.name)
        .field("factKind", fact.factKind.name)
        .field("anchorId", fact.anchorId)
        .field("subject", fact.subject.name)
        .nullableField(
            "conversationGeneration",
            fact.conversationGeneration?.toString(),
        )
        .nullableField("conversationId", fact.conversationId)
        .nullableField("turnReceiptId", fact.turnReceiptId)
        .nullableField("evidenceRequestId", fact.evidenceRequestId)
        .field("responseFingerprint", fact.responseFingerprint)
        .field("responseSummary", fact.responseSummary)
        .field("occurredAtEpochMillis", fact.occurredAtEpochMillis)
        .field("sourceVersion", fact.sourceVersion)
        .field(
            "sourcePayloadCanonicalFingerprint",
            record.sourcePayloadCanonicalFingerprint,
        )
        .nullableField(
            "sourceProofCanonicalFingerprint",
            record.sourceProofCanonicalFingerprint,
        )
        .nullableField("sourceReferenceId", record.sourceReferenceId)
        .nullableField("targetKind", record.targetKind)
        .nullableField("targetDatabase", record.targetDatabase)
        .nullableField("targetId", record.targetId)
        .nullableField("targetVersion", record.targetVersion)
        .nullableField(
            "targetCanonicalFingerprint",
            record.targetCanonicalFingerprint,
        )
        .nullableField(
            "attestedAtEpochMillis",
            record.attestedAtEpochMillis?.toString(),
        )
        .finish()
}

private fun validateStudentPage(
    afterExclusive: LegacyStudentDocumentMigrationCursor?,
    records: List<LegacyStudentDocumentMigrationRecord>,
) {
    check(
        records
            .asSequence()
            .map(LegacyStudentDocumentMigrationRecord::cursor)
            .zipWithNext()
            .all { (left, right) -> left < right },
    ) {
        "Legacy student migration page is not strictly ordered"
    }
    records.firstOrNull()?.let { first ->
        check(afterExclusive == null || first.cursor > afterExclusive) {
            "Legacy student migration page did not advance beyond its cursor"
        }
    }
}

private fun validateMasteryPage(
    afterExclusive: LegacyMasteryFactMigrationCursor?,
    records: List<LegacyMasteryFactMigrationRecord>,
) {
    check(
        records
            .asSequence()
            .map(LegacyMasteryFactMigrationRecord::cursor)
            .zipWithNext()
            .all { (left, right) -> left < right },
    ) {
        "Legacy mastery migration page is not strictly ordered"
    }
    records.firstOrNull()?.let { first ->
        check(afterExclusive == null || first.cursor > afterExclusive) {
            "Legacy mastery migration page did not advance beyond its cursor"
        }
    }
}

private fun studentCheckpointFingerprint(
    cursor: LegacyStudentDocumentMigrationCursor?,
): String =
    CanonicalSha256(STUDENT_CHECKPOINT_DOMAIN)
        .nullableField(
            "committedAtEpochMillis",
            cursor?.committedAtEpochMillis?.toString(),
        )
        .nullableField("problemId", cursor?.problemId)
        .nullableField("revisionNumber", cursor?.revisionNumber?.toString())
        .nullableField("revisionId", cursor?.revisionId)
        .finish()

private fun masteryCheckpointFingerprint(
    cursor: LegacyMasteryFactMigrationCursor?,
): String =
    CanonicalSha256(MASTERY_CHECKPOINT_DOMAIN)
        .nullableField(
            "occurredAtEpochMillis",
            cursor?.occurredAtEpochMillis?.toString(),
        )
        .nullableField("sourceFactId", cursor?.sourceFactId)
        .finish()

private fun studentBoundaryFingerprint(
    cursor: LegacyStudentDocumentMigrationCursor?,
    terminalRecordFingerprint: String?,
): String =
    CanonicalSha256(STUDENT_BOUNDARY_DOMAIN)
        .field("checkpoint", studentCheckpointFingerprint(cursor))
        .nullableField("terminalRecordFingerprint", terminalRecordFingerprint)
        .finish()

private fun masteryBoundaryFingerprint(
    cursor: LegacyMasteryFactMigrationCursor?,
    terminalRecordFingerprint: String?,
): String =
    CanonicalSha256(MASTERY_BOUNDARY_DOMAIN)
        .field("checkpoint", masteryCheckpointFingerprint(cursor))
        .nullableField("terminalRecordFingerprint", terminalRecordFingerprint)
        .finish()

private fun LegacyStudentDocumentMigrationRecord.maximumCanonicalByteSize(): Long =
    listOf(
        entryId,
        problemId,
        problemCanonicalFingerprint,
        revisionId,
        subject,
        problemArchivedAtEpochMillis?.toString().orEmpty(),
        title,
        problemMarkdown,
        questionDocumentSnapshot.orEmpty(),
        answerSpecId.orEmpty(),
        answerSpecSnapshot.orEmpty(),
        answerVerificationStatus,
        revisionSourceType,
        revisionSourceReference.orEmpty(),
        contentFingerprint,
        practiceUnitId,
        practiceUnitKey,
        practiceUnitKind,
        practiceUnitTitle,
        practiceUnitPromptMarkdown,
        practiceUnitRevisionId,
        entryCurrentRevisionId,
        sourceKey.orEmpty(),
        status,
    ).sumOf { value -> value.maximumUtf8Bytes() } +
        sourceAssets.sumOf { linked ->
            val asset = linked.sourceAsset
            listOf(
                linked.role,
                asset.sourceAssetId,
                asset.contentSha256,
                asset.relativePath,
                asset.mimeType,
                asset.sourceType,
            ).sumOf { value -> value.maximumUtf8Bytes() } +
                CANONICAL_NUMERIC_FIELD_BYTES
        } +
        CANONICAL_NUMERIC_FIELD_BYTES

private fun LegacyMasteryFactMigrationRecord.maximumCanonicalByteSize(): Long {
    val fact = sourceFact
    return listOf(
        fact.sourceFactId,
        fact.learnerScopeId,
        fact.source.name,
        fact.factKind.name,
        fact.anchorId,
        fact.subject.name,
        fact.conversationId.orEmpty(),
        fact.turnReceiptId.orEmpty(),
        fact.evidenceRequestId.orEmpty(),
        fact.responseFingerprint,
        fact.responseSummary,
        fact.sourceVersion,
        sourcePayloadCanonicalFingerprint,
        sourceProofCanonicalFingerprint.orEmpty(),
        sourceReferenceId.orEmpty(),
        targetKind.orEmpty(),
        targetDatabase.orEmpty(),
        targetId.orEmpty(),
        targetVersion.orEmpty(),
        targetCanonicalFingerprint.orEmpty(),
    ).sumOf { value -> value.maximumUtf8Bytes() } + CANONICAL_NUMERIC_FIELD_BYTES
}

private fun String.maximumUtf8Bytes(): Long =
    length.toLong() * MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT

private fun String.isSha256Fingerprint(): Boolean =
    SHA_256.matches(this)

private fun String.isValidManifestText(): Boolean =
    isNotBlank() &&
        length <= MAX_MANIFEST_TEXT_LENGTH &&
        this == trim() &&
        none(Char::isISOControl)

private const val EXACT_MANIFEST_VERSION = "exact-legacy-authority-manifest-v1"
private const val STUDENT_MANIFEST_DOMAIN = "exact-legacy-student-document-manifest-v1"
private const val MASTERY_MANIFEST_DOMAIN = "exact-legacy-mastery-fact-manifest-v1"
private const val STUDENT_PAGE_DOMAIN = "exact-legacy-student-document-page-v1"
private const val MASTERY_PAGE_DOMAIN = "exact-legacy-mastery-fact-page-v1"
private const val STUDENT_RECORD_DOMAIN = "exact-legacy-student-document-record-v1"
private const val MASTERY_RECORD_DOMAIN = "exact-legacy-mastery-fact-record-v1"
private const val STUDENT_CHECKPOINT_DOMAIN = "exact-legacy-student-checkpoint-v1"
private const val MASTERY_CHECKPOINT_DOMAIN = "exact-legacy-mastery-checkpoint-v1"
private const val STUDENT_BOUNDARY_DOMAIN = "exact-legacy-student-boundary-v1"
private const val MASTERY_BOUNDARY_DOMAIN = "exact-legacy-mastery-boundary-v1"
private const val MAX_MANIFEST_TEXT_LENGTH = 256
private const val MAX_MANIFEST_RECORDS = 2_000_000L
private const val MAX_MANIFEST_CANONICAL_BYTES = 512L * 1024L * 1024L
private const val MAX_UTF8_BYTES_PER_UTF16_CODE_UNIT = 3L
private const val CANONICAL_NUMERIC_FIELD_BYTES = 256L
private val SHA_256 = Regex("[0-9a-f]{64}")
