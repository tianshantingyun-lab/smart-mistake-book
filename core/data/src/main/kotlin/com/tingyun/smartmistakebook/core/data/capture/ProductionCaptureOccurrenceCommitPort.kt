package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.student.mistake.database.AcknowledgeStudentCaptureSaveHandoffCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.AppendStudentProblemErrorOccurrenceCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.ReadPendingStudentCaptureSaveHandoffsQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.SaveStudentCaptureOccurrenceCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureOccurrenceReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveHandoffRecord
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentOwnedCaptureSaveReceipt
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemCanonicalIdentityKey
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemErrorOccurrence
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemImageReference

internal data class PreparedStudentOwnedCaptureCommit(
    val command:
        com.tingyun.smartmistakebook.core.student.mistake.database.SaveStudentOwnedCaptureCommand,
    val summary: CapturedProblemCommitSummary,
)

/**
 * Capture's only production write boundary for a newly observed saved mistake.
 *
 * The implementation delegates one indivisible command to the student-mistake authority. It has
 * no mastery or public-knowledge capability and cannot emulate atomicity by calling separate save
 * and occurrence writers.
 */
internal interface ProductionCaptureOccurrenceCommitPort {
    val learnerId: String

    suspend fun save(
        prepared: PreparedStudentOwnedCaptureCommit,
        identityEvidenceRequest: ProductionStudentProblemIdentityEvidenceRequest =
            ProductionStudentProblemIdentityEvidenceRequest.ExactAssetSelectionOrUnresolved,
    ): ProductionCaptureOccurrenceCommitReceipt

    suspend fun readPending(
        query: ReadPendingStudentCaptureSaveHandoffsQuery =
            ReadPendingStudentCaptureSaveHandoffsQuery(),
    ): List<StudentCaptureSaveHandoffRecord>

    suspend fun readByDraftIds(
        draftIds: Set<String>,
    ): List<StudentCaptureSaveHandoffRecord>

    suspend fun readBySessionId(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord?

    suspend fun acknowledge(
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffRecord
}

/**
 * One owner-composed capability. Its implementation owns both identity resolution and the
 * learner-bound atomic occurrence port; callers cannot mix a resolver from one owner with another
 * owner's writer.
 */
internal interface ProductionStudentCaptureOccurrenceOwner {
    val learnerId: String

    suspend fun save(
        command: SaveStudentCaptureOccurrenceCommand,
        identityEvidenceRequest: ProductionStudentProblemIdentityEvidenceRequest,
    ): StudentCaptureOccurrenceReceipt

    suspend fun readPending(
        query: ReadPendingStudentCaptureSaveHandoffsQuery,
    ): List<StudentCaptureSaveHandoffRecord>

    suspend fun readByDraftIds(
        draftIds: Set<String>,
    ): List<StudentCaptureSaveHandoffRecord>

    suspend fun readBySessionId(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord?

    suspend fun acknowledge(
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffRecord
}

/**
 * Resolved student-authority receipt. Consumers must use this receipt, rather than the prepared
 * target, once canonical question identity resolution is added inside the student authority.
 */
internal data class ProductionCaptureOccurrenceCommitReceipt(
    val transactionId: String,
    val transactionCanonicalFingerprint: String,
    val assetManifestCanonicalFingerprint: String,
    val selectedRegionCanonicalFingerprint: String,
    val resolvedIdentity: StudentProblemCanonicalIdentityKey,
    val save: StudentOwnedCaptureSaveReceipt,
    val occurrence: StudentProblemErrorOccurrence,
) {
    val handoff: StudentCaptureSaveHandoffRecord
        get() = save.handoff
}

internal object ProductionCaptureOccurrenceCommitPortFactory {
    fun create(
        studentOwner: ProductionStudentCaptureOccurrenceOwner,
    ): ProductionCaptureOccurrenceCommitPort =
        StudentAuthorityProductionCaptureOccurrenceCommitPort(
            studentOwner = studentOwner,
        )
}

private class StudentAuthorityProductionCaptureOccurrenceCommitPort(
    private val studentOwner: ProductionStudentCaptureOccurrenceOwner,
) : ProductionCaptureOccurrenceCommitPort {
    override val learnerId: String = studentOwner.learnerId

    override suspend fun save(
        prepared: PreparedStudentOwnedCaptureCommit,
        identityEvidenceRequest: ProductionStudentProblemIdentityEvidenceRequest,
    ): ProductionCaptureOccurrenceCommitReceipt {
        val capture = prepared.command
        val source = capture.source
        val revision = capture.target.problem.revision
        require(revision.problem.learnerId == learnerId) {
            "Capture occurrence belongs to another learner"
        }
        require(prepared.summary.draftId == source.draftId) {
            "Capture occurrence summary belongs to another draft"
        }
        require(prepared.summary.problemRevisionId == revision.revisionId) {
            "Capture occurrence summary belongs to another prepared revision"
        }

        val assetManifest =
            capture.target.problem.originalImages.captureAssetManifestFingerprint()
        val occurrence =
            AppendStudentProblemErrorOccurrenceCommand(
                occurrenceId = captureOccurrenceId(learnerId, source.intentId),
                idempotencyKey = source.intentId,
                problemRevision = revision,
                batchCanonicalFingerprint =
                    captureOccurrenceBatchFingerprint(
                        learnerId = learnerId,
                        sourceKind = source.kind.name,
                        sourceIntentId = source.intentId,
                        sourceCanonicalFingerprint =
                            source.sourceCanonicalFingerprint,
                    ),
                importSourceCanonicalFingerprint =
                    captureImportSourceFingerprint(
                        sourceCanonicalFingerprint =
                            source.sourceCanonicalFingerprint,
                        assetManifestCanonicalFingerprint = assetManifest,
                    ),
                occurredAtEpochMillis = source.occurredAtEpochMillis,
                importedAtEpochMillis = source.occurredAtEpochMillis,
                attributionStatus =
                    ProblemErrorAttributionResolutionStatus.UNRESOLVED,
                evidenceRefs = emptyList(),
            )
        val receipt =
            studentOwner.save(
                SaveStudentCaptureOccurrenceCommand(
                    capture = capture,
                    occurrence = occurrence,
                ),
                identityEvidenceRequest,
            )
        return receipt.toProductionReceipt()
    }

    override suspend fun readPending(
        query: ReadPendingStudentCaptureSaveHandoffsQuery,
    ): List<StudentCaptureSaveHandoffRecord> =
        studentOwner.readPending(query)

    override suspend fun readByDraftIds(
        draftIds: Set<String>,
    ): List<StudentCaptureSaveHandoffRecord> =
        studentOwner.readByDraftIds(draftIds)

    override suspend fun readBySessionId(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord? =
        studentOwner.readBySessionId(sessionId)

    override suspend fun acknowledge(
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffRecord =
        studentOwner.acknowledge(command)
}

private fun StudentCaptureOccurrenceReceipt.toProductionReceipt() =
    ProductionCaptureOccurrenceCommitReceipt(
        transactionId = transactionId,
        transactionCanonicalFingerprint = transactionCanonicalFingerprint,
        assetManifestCanonicalFingerprint = assetManifestCanonicalFingerprint,
        selectedRegionCanonicalFingerprint = selectedRegionCanonicalFingerprint,
        resolvedIdentity = resolvedIdentity,
        save = save,
        occurrence = occurrence,
    )

/**
 * Stable capture evidence, not a student problem identity.
 *
 * Revision-scoped image ids and local URIs are intentionally excluded: importing identical
 * canonical pixels through another capture intent must yield the same evidence manifest. The
 * student authority alone decides whether that evidence is sufficient to reuse a problem.
 */
private fun List<StudentProblemImageReference>.captureAssetManifestFingerprint(): String {
    require(map(StudentProblemImageReference::ordinal) == indices.toList()) {
        "Capture asset manifest must preserve contiguous source order"
    }
    val canonical =
        CanonicalSha256(CAPTURE_ASSET_MANIFEST_FINGERPRINT_DOMAIN)
            .field("assetCount", size)
    forEachIndexed { assetIndex, asset ->
        val prefix = "asset[$assetIndex]"
        canonical
            .field("$prefix.ordinal", asset.ordinal)
            .field(
                "$prefix.contentCanonicalFingerprint",
                asset.contentCanonicalFingerprint,
            )
            .field("$prefix.mediaType", asset.mediaType)
            .nullableLongField(
                "$prefix.widthPixels",
                asset.widthPixels?.toLong(),
            )
            .nullableLongField(
                "$prefix.heightPixels",
                asset.heightPixels?.toLong(),
            )
            .nullableLongField("$prefix.byteSize", asset.byteSize)
            .field(
                "$prefix.regionScope",
                if (asset.selectedRegions.isEmpty()) {
                    CAPTURE_ASSET_REGION_SCOPE_FULL_FRAME
                } else {
                    CAPTURE_ASSET_REGION_SCOPE_SELECTED
                },
            )
            .field("$prefix.selectedRegionCount", asset.selectedRegions.size)
        asset.selectedRegions.forEachIndexed { regionIndex, region ->
            val regionPrefix = "$prefix.selectedRegion[$regionIndex]"
            canonical
                .field("$regionPrefix.leftBits", region.left.toBits())
                .field("$regionPrefix.topBits", region.top.toBits())
                .field("$regionPrefix.rightBits", region.right.toBits())
                .field("$regionPrefix.bottomBits", region.bottom.toBits())
        }
    }
    return canonical.finish()
}

private fun captureOccurrenceId(
    learnerId: String,
    sourceIntentId: String,
): String =
    "capture-occurrence-" +
        CanonicalSha256(CAPTURE_OCCURRENCE_ID_DOMAIN)
            .field("learnerId", learnerId)
            .field("sourceIntentId", sourceIntentId)
            .finish()
            .take(CAPTURE_OCCURRENCE_ID_HASH_CHARS)

private fun captureOccurrenceBatchFingerprint(
    learnerId: String,
    sourceKind: String,
    sourceIntentId: String,
    sourceCanonicalFingerprint: String,
): String =
    CanonicalSha256(CAPTURE_OCCURRENCE_BATCH_FINGERPRINT_DOMAIN)
        .field("learnerId", learnerId)
        .field("sourceKind", sourceKind)
        .field("sourceIntentId", sourceIntentId)
        .field("sourceCanonicalFingerprint", sourceCanonicalFingerprint)
        .finish()

private fun captureImportSourceFingerprint(
    sourceCanonicalFingerprint: String,
    assetManifestCanonicalFingerprint: String,
): String =
    CanonicalSha256(CAPTURE_IMPORT_SOURCE_FINGERPRINT_DOMAIN)
        .field("sourceCanonicalFingerprint", sourceCanonicalFingerprint)
        .field(
            "assetManifestCanonicalFingerprint",
            assetManifestCanonicalFingerprint,
        )
        .finish()

private const val CAPTURE_OCCURRENCE_ID_HASH_CHARS = 48
private const val CAPTURE_OCCURRENCE_ID_DOMAIN =
    "production-capture-occurrence-id-v1"
private const val CAPTURE_OCCURRENCE_BATCH_FINGERPRINT_DOMAIN =
    "production-capture-occurrence-batch-v1"
private const val CAPTURE_IMPORT_SOURCE_FINGERPRINT_DOMAIN =
    "production-capture-import-source-v1"
private const val CAPTURE_ASSET_MANIFEST_FINGERPRINT_DOMAIN =
    "production-capture-asset-manifest-v1"
private const val CAPTURE_ASSET_REGION_SCOPE_FULL_FRAME = "FULL_FRAME"
private const val CAPTURE_ASSET_REGION_SCOPE_SELECTED = "SELECTED_REGIONS"
