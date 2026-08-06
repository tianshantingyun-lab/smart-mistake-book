package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.SubjectKind

const val MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE = 256
const val MAX_LEGACY_STUDENT_SOURCE_ASSETS_PER_RECORD = 64
const val MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_RECORD = 512L * 1024L * 1024L
const val MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_PAGE = 512L * 1024L * 1024L

data class LegacyStudentDocumentMigrationCursor(
    val committedAtEpochMillis: Long,
    val problemId: String,
    val revisionNumber: Int,
    val revisionId: String,
) : Comparable<LegacyStudentDocumentMigrationCursor> {
    init {
        require(committedAtEpochMillis >= 0L)
        require(problemId.isNotBlank())
        require(revisionNumber > 0)
        require(revisionId.isNotBlank())
    }

    override fun compareTo(other: LegacyStudentDocumentMigrationCursor): Int =
        compareValuesBy(
            this,
            other,
            LegacyStudentDocumentMigrationCursor::committedAtEpochMillis,
            LegacyStudentDocumentMigrationCursor::problemId,
            LegacyStudentDocumentMigrationCursor::revisionNumber,
            LegacyStudentDocumentMigrationCursor::revisionId,
        )
}

/**
 * One immutable legacy revision plus its current collection state.
 *
 * The record intentionally contains no learner-mastery or curriculum rows. It is the bounded
 * source shape for the independent student-mistake database only.
 */
data class LegacyStudentDocumentMigrationRecord(
    val entryId: String,
    val problemId: String,
    val problemCanonicalFingerprint: String,
    val revisionId: String,
    val revisionNumber: Int,
    val subject: String,
    val problemCreatedAtEpochMillis: Long,
    val problemArchivedAtEpochMillis: Long?,
    val title: String,
    val problemMarkdown: String,
    val questionDocumentSnapshot: String?,
    val answerSpecId: String?,
    val answerSpecSnapshot: String?,
    val answerVerificationStatus: String,
    val revisionSourceType: String,
    val revisionSourceReference: String?,
    val contentFingerprint: String,
    val practiceUnitId: String,
    val practiceUnitKey: String,
    val practiceUnitKind: String,
    val practiceUnitTitle: String,
    val practiceUnitPromptMarkdown: String,
    val estimatedSeconds: Int,
    val practiceUnitRevisionId: String,
    val practiceUnitCreatedAtEpochMillis: Long,
    val entryCurrentRevisionId: String,
    val sourceKey: String?,
    val status: String,
    val acceptedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val revisionCreatedAtEpochMillis: Long,
    val sourceAssets: List<MistakeDetailSourceAssetRecord>,
) {
    val committedAtEpochMillis: Long =
        maxOf(revisionCreatedAtEpochMillis, acceptedAtEpochMillis)

    val cursor: LegacyStudentDocumentMigrationCursor =
        LegacyStudentDocumentMigrationCursor(
            committedAtEpochMillis = committedAtEpochMillis,
            problemId = problemId,
            revisionNumber = revisionNumber,
            revisionId = revisionId,
        )

    init {
        require(entryId.isNotBlank())
        require(problemId.isNotBlank())
        require(problemCanonicalFingerprint.matches(SHA_256))
        require(revisionId.isNotBlank())
        require(revisionNumber > 0)
        require(subject.isNotBlank())
        require(problemCreatedAtEpochMillis >= 0L)
        require(problemArchivedAtEpochMillis == null ||
            problemArchivedAtEpochMillis >= problemCreatedAtEpochMillis)
        require(title.isNotBlank())
        require(problemMarkdown.isNotBlank())
        require(answerSpecId == null || answerSpecId.isNotBlank())
        require(answerSpecSnapshot == null || answerSpecSnapshot.isNotBlank())
        require(answerVerificationStatus.isNotBlank())
        require(revisionSourceType.isNotBlank())
        require(revisionSourceReference == null || revisionSourceReference.isNotBlank())
        require(contentFingerprint.matches(SHA_256))
        require(practiceUnitId.isNotBlank())
        require(practiceUnitKey.isNotBlank())
        require(practiceUnitKind.isNotBlank())
        require(practiceUnitTitle.isNotBlank())
        require(practiceUnitPromptMarkdown.isNotBlank())
        require(estimatedSeconds > 0)
        require(practiceUnitRevisionId.isNotBlank())
        require(practiceUnitCreatedAtEpochMillis >= 0L)
        require(entryCurrentRevisionId.isNotBlank())
        require(entryCurrentRevisionId == revisionId) {
            "Legacy error-book head must match the migrated document revision"
        }
        require(sourceKey == null || sourceKey.isNotBlank())
        require(status.isNotBlank())
        require(acceptedAtEpochMillis >= 0L)
        require(updatedAtEpochMillis >= 0L)
        require(revisionCreatedAtEpochMillis >= 0L)
        require(updatedAtEpochMillis >= acceptedAtEpochMillis)
        require(sourceAssets.size <= MAX_LEGACY_STUDENT_SOURCE_ASSETS_PER_RECORD) {
            "Legacy student document exceeds the source-asset count budget"
        }
        require(
            sourceAssets.all { linked ->
                linked.sourceAsset.byteSize in
                    1L..MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_RECORD
            },
        ) {
            "Legacy student source asset exceeds the per-document byte budget"
        }
        require(
            sourceAssets.sumOf { linked -> linked.sourceAsset.byteSize } <=
                MAX_LEGACY_STUDENT_SOURCE_ASSET_BYTES_PER_RECORD,
        ) {
            "Legacy student document exceeds the source-asset byte budget"
        }
        require(sourceAssets.map { it.sourceAsset.sourceAssetId to it.role }.distinct().size ==
            sourceAssets.size)
        require(sourceAssets.all { it.pageIndex == null || it.pageIndex >= 0 }) {
            "Legacy source-asset page order must not be negative"
        }
    }
}

/**
 * The two indexed ways to reconstruct one capture-owned student document.
 *
 * Receipt replay and prepared-handoff recovery deliberately have different proof shapes. Keeping
 * them as separate types prevents a caller from constructing a partially proven, nullable union.
 */
sealed interface ExactLegacyCaptureStudentDocumentQuery {
    val learnerId: String
    val intentId: String
    val intentCanonicalFingerprint: String
    val draftId: String
    val draftRevisionNumber: Int
    val tutorSessionId: String?
    val problemId: String
    val problemRevisionId: String
    val practiceUnitId: String
}

/**
 * Point read for an exact commit receipt already held by the caller.
 *
 * A null tutorSessionId is an exact claim that neither the receipt draft nor its handoff belongs to
 * a tutor session; it is not an unscoped session lookup.
 */
data class ExactLegacyCaptureReceiptReplayQuery(
    override val learnerId: String,
    override val intentId: String,
    override val intentCanonicalFingerprint: String,
    override val draftId: String,
    override val draftRevisionNumber: Int,
    override val tutorSessionId: String?,
    val errorBookEntryId: String,
    override val problemId: String,
    override val problemRevisionId: String,
    override val practiceUnitId: String,
) : ExactLegacyCaptureStudentDocumentQuery {
    init {
        validateExactLegacyCaptureIdentity()
        requireExactLegacyIdentity(errorBookEntryId, "errorBookEntryId")
    }
}

/**
 * Point read for a durable PREPARED or FINALIZED handoff.
 *
 * The legacy receipt is unique by draft id, so Room can derive its entry id without scanning while
 * the caller proves the complete immutable student target retained by the handoff.
 */
data class ExactLegacyPreparedHandoffReplayQuery(
    override val learnerId: String,
    override val intentId: String,
    override val intentCanonicalFingerprint: String,
    override val draftId: String,
    override val draftRevisionNumber: Int,
    override val tutorSessionId: String?,
    val subject: String,
    override val problemId: String,
    override val problemRevisionId: String,
    val problemRevisionNumber: Int,
    override val practiceUnitId: String,
    val documentCanonicalFingerprint: String,
) : ExactLegacyCaptureStudentDocumentQuery {
    init {
        validateExactLegacyCaptureIdentity()
        requireExactLegacyIdentity(subject, "subject")
        require(enumValues<SubjectKind>().any { candidate -> candidate.name == subject }) {
            "subject must be a supported subject identity"
        }
        require(problemRevisionNumber > 0) {
            "problemRevisionNumber must be positive"
        }
        require(documentCanonicalFingerprint.matches(SHA_256)) {
            "documentCanonicalFingerprint must be a lowercase SHA-256 fingerprint"
        }
    }
}

/**
 * Read-only capability for reconstructing exactly one capture-owned student document.
 *
 * It exposes neither Room, SQL, nor an unscoped migration page.
 * A non-null record preserves the legacy evidence; it does not manufacture cross-draft page order
 * merely to satisfy a stricter destination shape.
 */
interface ExactLegacyCaptureStudentDocumentSourcePort {
    suspend fun readExactLegacyCaptureStudentDocument(
        query: ExactLegacyCaptureStudentDocumentQuery,
    ): LegacyStudentDocumentMigrationRecord?
}

data class LegacyStudentDocumentMigrationPage(
    val records: List<LegacyStudentDocumentMigrationRecord>,
    val hasMore: Boolean,
) {
    init {
        require(records.size <= MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE)
        require(records.map(LegacyStudentDocumentMigrationRecord::cursor)
            .zipWithNext()
            .all { (left, right) -> left < right })
        require(!hasMore || records.isNotEmpty())
    }
}

data class LegacyMasteryFactMigrationCursor(
    val occurredAtEpochMillis: Long,
    val sourceFactId: String,
) : Comparable<LegacyMasteryFactMigrationCursor> {
    init {
        require(occurredAtEpochMillis >= 0L)
        require(sourceFactId.isNotBlank())
    }

    override fun compareTo(other: LegacyMasteryFactMigrationCursor): Int =
        compareValuesBy(
            this,
            other,
            LegacyMasteryFactMigrationCursor::occurredAtEpochMillis,
            LegacyMasteryFactMigrationCursor::sourceFactId,
        )
}

/**
 * A legacy fact and the immutable local proof that admitted it.
 *
 * This shape deliberately does not manufacture hint, retry, answer-exposure, or response-form
 * metadata that the old source fact did not store. A destination adapter must reject facts for
 * which the stricter learner-mastery command cannot be reconstructed exactly.
 */
data class LegacyMasteryFactMigrationRecord(
    val sourceFact: LearningObservationSourceFact,
    val sourcePayloadCanonicalFingerprint: String,
    val sourceProofCanonicalFingerprint: String?,
    val sourceReferenceId: String?,
    val targetKind: String?,
    val targetDatabase: String?,
    val targetId: String?,
    val targetVersion: String?,
    val targetCanonicalFingerprint: String?,
    val attestedAtEpochMillis: Long?,
) {
    val hasSourceProof: Boolean =
        sourceProofCanonicalFingerprint != null

    val cursor =
        LegacyMasteryFactMigrationCursor(
            occurredAtEpochMillis = sourceFact.occurredAtEpochMillis,
            sourceFactId = sourceFact.sourceFactId,
        )

    init {
        require(sourcePayloadCanonicalFingerprint.matches(SHA_256))
        sourceProofCanonicalFingerprint?.let { require(it.matches(SHA_256)) }
        targetCanonicalFingerprint?.let { require(it.matches(SHA_256)) }
        require(attestedAtEpochMillis == null ||
            attestedAtEpochMillis >= sourceFact.occurredAtEpochMillis)
        val proofFields =
            listOf(
                sourceProofCanonicalFingerprint,
                sourceReferenceId,
                targetKind,
                targetDatabase,
                targetId,
                targetVersion,
                targetCanonicalFingerprint,
                attestedAtEpochMillis?.toString(),
            )
        require(proofFields.all { value -> value == null } ||
            proofFields.all { value -> value != null }) {
            "Legacy mastery proof must be either complete or absent"
        }
    }
}

data class LegacyMasteryFactMigrationPage(
    val records: List<LegacyMasteryFactMigrationRecord>,
    val hasMore: Boolean,
) {
    init {
        require(records.size <= MAX_LEGACY_AUTHORITY_MIGRATION_PAGE_SIZE)
        require(records.map(LegacyMasteryFactMigrationRecord::cursor)
            .zipWithNext()
            .all { (left, right) -> left < right })
        require(!hasMore || records.isNotEmpty())
    }
}

data class LegacyAuthorityMigrationSnapshot(
    val schemaVersion: Int,
    val studentDocumentRevisionCount: Long,
    val studentSourceAssetLinkCount: Long,
    val studentBrokenSourceAssetLinkCount: Long,
    val studentSourceAssetByteCount: Long,
    val studentProblemWithMultipleEntriesCount: Long,
    val studentProblemCurrentRevisionNotLatestCount: Long = 0L,
    val masterySourceFactCount: Long,
    val masteryProvenSourceFactCount: Long,
    val latestStudentMutationAtEpochMillis: Long,
    val latestMasteryFactAtEpochMillis: Long,
) {
    init {
        require(schemaVersion > 0)
        require(studentDocumentRevisionCount >= 0L)
        require(studentSourceAssetLinkCount >= 0L)
        require(studentBrokenSourceAssetLinkCount in 0L..studentSourceAssetLinkCount)
        require(studentSourceAssetByteCount >= 0L)
        require(studentProblemWithMultipleEntriesCount >= 0L)
        require(studentProblemCurrentRevisionNotLatestCount >= 0L)
        require(masterySourceFactCount >= 0L)
        require(masteryProvenSourceFactCount in 0L..masterySourceFactCount)
        require(latestStudentMutationAtEpochMillis >= 0L)
        require(latestMasteryFactAtEpochMillis >= 0L)
    }

    val legacySchemaCanonicalFingerprint: String =
        CanonicalSha256("legacy-authority-schema-snapshot-v1")
            .field("schemaVersion", schemaVersion)
            .finish()

    val studentDocumentsCanonicalFingerprint: String =
        CanonicalSha256("legacy-student-documents-snapshot-v3")
            .field("schemaVersion", schemaVersion)
            .field("revisionCount", studentDocumentRevisionCount)
            .field("sourceAssetLinkCount", studentSourceAssetLinkCount)
            .field("brokenSourceAssetLinkCount", studentBrokenSourceAssetLinkCount)
            .field("sourceAssetByteCount", studentSourceAssetByteCount)
            .field(
                "problemWithMultipleEntriesCount",
                studentProblemWithMultipleEntriesCount,
            )
            .field(
                "problemCurrentRevisionNotLatestCount",
                studentProblemCurrentRevisionNotLatestCount,
            )
            .field(
                "latestMutationAtEpochMillis",
                latestStudentMutationAtEpochMillis,
            )
            .finish()

    val masteryFactsCanonicalFingerprint: String =
        CanonicalSha256("legacy-mastery-facts-snapshot-v1")
            .field("schemaVersion", schemaVersion)
            .field("sourceFactCount", masterySourceFactCount)
            .field("provenSourceFactCount", masteryProvenSourceFactCount)
            .field("latestFactAtEpochMillis", latestMasteryFactAtEpochMillis)
            .finish()

    val canonicalFingerprint: String =
        CanonicalSha256("legacy-authority-migration-snapshot-v3")
            .field("legacySchema", legacySchemaCanonicalFingerprint)
            .field("studentDocuments", studentDocumentsCanonicalFingerprint)
            .field("masteryFacts", masteryFactsCanonicalFingerprint)
            .finish()
}

/**
 * Read-only, bounded migration capability over the legacy coordination database.
 *
 * It exposes neither SQL nor mutation operations and must never be passed to a model.
 */
interface LegacyAuthorityMigrationSourcePort {
    suspend fun readLegacyAuthorityMigrationSnapshot(
        learnerId: String,
    ): LegacyAuthorityMigrationSnapshot

    /**
     * Bounded bulk-migration scan. Runtime capture replay must use
     * [ExactLegacyCaptureStudentDocumentSourcePort] instead.
     */
    suspend fun readLegacyStudentDocumentMigrationPage(
        afterExclusive: LegacyStudentDocumentMigrationCursor?,
        limit: Int,
    ): LegacyStudentDocumentMigrationPage

    suspend fun readLegacyMasteryFactMigrationPage(
        learnerId: String,
        afterExclusive: LegacyMasteryFactMigrationCursor?,
        limit: Int,
    ): LegacyMasteryFactMigrationPage
}

private fun requireExactLegacyIdentity(value: String, label: String) {
    require(value.isNotBlank() && value == value.trim() && value.none(Char::isISOControl)) {
        "$label must be a trimmed opaque identity"
    }
}

private fun ExactLegacyCaptureStudentDocumentQuery.validateExactLegacyCaptureIdentity() {
    require(learnerId == LOCAL_LEARNER_ID) {
        "Legacy capture documents belong only to the local learner"
    }
    listOf(
        "intentId" to intentId,
        "draftId" to draftId,
        "problemId" to problemId,
        "problemRevisionId" to problemRevisionId,
        "practiceUnitId" to practiceUnitId,
    ).forEach { (label, value) -> requireExactLegacyIdentity(value, label) }
    tutorSessionId?.let { requireExactLegacyIdentity(it, "tutorSessionId") }
    require(intentCanonicalFingerprint.matches(SHA_256)) {
        "intentCanonicalFingerprint must be a lowercase SHA-256 fingerprint"
    }
    require(draftRevisionNumber > 0) {
        "draftRevisionNumber must be positive"
    }
}

private val SHA_256 = Regex("[0-9a-f]{64}")
