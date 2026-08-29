package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureHeadRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureIndexRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureWorkspaceColumns
import com.tingyun.smartmistakebook.core.database.dao.toSnapshot
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

private val PENDING_CAPTURE_TABLES = arrayOf(
    "problem_draft",
    "problem_draft_revision",
    "problem_draft_source_asset",
    "canonical_source_asset",
    "problem_draft_edit_snapshot",
    "tutor_session",
    "model_task",
)

/** Aggregated pending-capture draft reads spanning draft, workspace, and task tables. */
internal class RoomPendingCaptureStore(
    private val database: StudyDatabase,
) {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeDrafts(): Flow<List<PendingCaptureDraftRecord>> =
        database.invalidationTracker.createFlow(*PENDING_CAPTURE_TABLES).mapLatest {
            loadPendingCaptureBatch()
        }

    suspend fun readDraft(draftId: String): PendingCaptureDraftRecord? {
        require(draftId.isNotBlank()) { "draftId must not be blank" }
        val index = database.pendingCaptureDao().readPending(draftId) ?: return null
        return loadPendingCapture(index)
    }

    private suspend fun loadPendingCaptureBatch(): List<PendingCaptureDraftRecord> =
        database.withReadTransaction {
            val pending = database.pendingCaptureDao()
            val heads = pending.readPendingHeads()
            if (heads.isEmpty()) return@withReadTransaction emptyList()

            val sourceAssetsByDraft = pending.readPendingSourceAssets().groupBy { it.draftId }
            val assessmentTasksByDraft = pending.readRecentPendingAssessmentTasks()
                .groupBy { it.subjectId }
                .mapValues { (_, tasks) -> tasks.map { it.toSnapshot() } }
            val parseTasksByDraft = pending.readLatestPendingParseTasks()
                .groupBy { it.subjectId }
                .mapValues { (draftId, tasks) ->
                    if (tasks.size != 1) {
                        throw LearningLedgerIntegrityException(
                            "Pending draft $draftId has multiple latest parse tasks",
                        )
                    }
                    tasks.single().toSnapshot()
                }

            heads.map { head ->
                val draft = head.toProblemDraftRecord(
                    sourceAssetsByDraft[head.draftId].orEmpty(),
                )
                validatePendingTutorSession(
                    draft = draft,
                    sessionId = head.tutorSessionId,
                    sessionRevision = head.tutorSessionDraftRevisionNumber,
                )
                val assessments = assessmentTasksByDraft[head.draftId].orEmpty()
                PendingCaptureDraftRecord(
                    draft = draft,
                    editWorkspace = head.toValidatedWorkspaceRecord(draft),
                    latestAssessmentTask = assessments.firstOrNull(),
                    assessmentTasks = assessments,
                    latestParseTask = parseTasksByDraft[head.draftId],
                    tutorSessionId = head.tutorSessionId,
                    tutorSessionDraftRevisionNumber = head.tutorSessionDraftRevisionNumber,
                )
            }
        }

    private suspend fun loadPendingCapture(
        index: PendingCaptureIndexRow,
    ): PendingCaptureDraftRecord? {
        val draft = database.problemDraftTransactionDao().read(index.draftId) ?: return null
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            draft.updatedAtEpochMillis != index.draftUpdatedAtEpochMillis
        ) {
            return null
        }
        val sessionId = index.tutorSessionId
        val sessionRevision = index.tutorSessionDraftRevisionNumber
        validatePendingTutorSession(draft, sessionId, sessionRevision)
        return PendingCaptureDraftRecord(
            draft = draft,
            editWorkspace = index.toValidatedWorkspaceRecord(draft),
            latestAssessmentTask = index.latestAssessmentRequestId?.let {
                database.modelTaskTransactionDao().read(it)
            },
            assessmentTasks = database.pendingCaptureDao()
                .readAssessmentRequestIds(index.draftId)
                .mapNotNull { database.modelTaskTransactionDao().read(it) },
            latestParseTask = index.latestParseRequestId?.let {
                database.modelTaskTransactionDao().read(it)
            },
            tutorSessionId = sessionId,
            tutorSessionDraftRevisionNumber = sessionRevision,
        )
    }

    private fun validatePendingTutorSession(
        draft: ProblemDraftRecord,
        sessionId: String?,
        sessionRevision: Int?,
    ) {
        if ((sessionId == null) != (sessionRevision == null)) {
            throw LearningLedgerIntegrityException("Pending tutor-session columns are incomplete")
        }
        if (
            sessionId != null &&
            (draft.origin != StudyDbValue.CaptureOrigin.TUTOR ||
                sessionRevision != draft.currentRevision.revisionNumber)
        ) {
            throw LearningLedgerIntegrityException("Pending tutor session disagrees with its draft")
        }
    }

    private fun PendingCaptureWorkspaceColumns.toValidatedWorkspaceRecord(
        draft: ProblemDraftRecord,
    ): ProblemDraftEditWorkspaceRecord? {
        val columns = listOf(
            workspaceBasisRevisionNumber,
            workspaceVersion,
            workspaceSnapshotSchemaVersion,
            workspaceSnapshot,
            workspaceFingerprint,
            workspaceCreatedAtEpochMillis,
            workspaceUpdatedAtEpochMillis,
        )
        if (columns.all { it == null }) return null
        if (columns.any { it == null }) {
            throw ProblemDraftEditWorkspaceIntegrityException(
                "Pending workspace ${draft.draftId} has incomplete columns",
            )
        }
        val record = ProblemDraftEditWorkspaceRecord(
            draftId = draft.draftId,
            basisRevisionNumber = checkNotNull(workspaceBasisRevisionNumber),
            workspaceVersion = checkNotNull(workspaceVersion),
            snapshotSchemaVersion = checkNotNull(workspaceSnapshotSchemaVersion),
            workspaceSnapshot = checkNotNull(workspaceSnapshot),
            workspaceFingerprint = checkNotNull(workspaceFingerprint),
            createdAtEpochMillis = checkNotNull(workspaceCreatedAtEpochMillis),
            updatedAtEpochMillis = checkNotNull(workspaceUpdatedAtEpochMillis),
        )
        val workspace = try {
            DatabaseContractValidator.decodeProblemDraftEditWorkspace(
                snapshotSchemaVersion = record.snapshotSchemaVersion,
                workspaceSnapshot = record.workspaceSnapshot,
                workspaceFingerprint = record.workspaceFingerprint,
            )
        } catch (failure: Exception) {
            throw ProblemDraftEditWorkspaceIntegrityException(
                "Pending workspace ${draft.draftId} is corrupted",
                failure,
            )
        }
        if (
            draft.status != StudyDbValue.ProblemDraftStatus.EDITING ||
            record.basisRevisionNumber != draft.currentRevision.revisionNumber ||
            record.workspaceVersion <= 0 ||
            record.createdAtEpochMillis < draft.currentRevision.createdAtEpochMillis ||
            record.updatedAtEpochMillis < record.createdAtEpochMillis ||
            workspace.baseCandidateFingerprint != draft.currentRevision.documentFingerprint ||
            workspace.workingDocument.blockEvidence.any {
                it.sourceAssetId != draft.sourceAsset.sourceAssetId
            }
        ) {
            throw ProblemDraftEditWorkspaceIntegrityException(
                "Pending workspace ${draft.draftId} has a stale or invalid binding",
            )
        }
        return record
    }
}

private fun PendingCaptureHeadRow.toProblemDraftRecord(
    sourceRows: List<PendingCaptureSourceAssetRow>,
): ProblemDraftRecord {
    val persistedRevisionNumber = revisionNumber
        ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no current revision")
    if (persistedRevisionNumber != draftCurrentRevisionNumber) {
        throw LearningLedgerIntegrityException("Pending draft $draftId has a mismatched current revision")
    }
    val sourceAssets = sourceRows.map { row ->
        ProblemDraftSourceAssetRecord(
            pageIndex = row.pageIndex,
            sourceAsset = CanonicalSourceAssetRecord(
                sourceAssetId = row.sourceAssetId,
                contentSha256 = row.contentSha256,
                relativePath = row.relativePath,
                mimeType = row.mimeType,
                byteSize = row.byteSize,
                width = row.width,
                height = row.height,
                sourceType = row.sourceType,
                createdAtEpochMillis = row.createdAtEpochMillis,
            ),
        )
    }
    val primarySource = sourceAssets.firstOrNull()?.sourceAsset
        ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no source asset")
    if (primarySource.sourceAssetId != primarySourceAssetId) {
        throw LearningLedgerIntegrityException("Pending draft $draftId has a mismatched primary source")
    }
    return ProblemDraftRecord(
        draftId = draftId,
        sourceAsset = primarySource,
        sourceAssets = sourceAssets,
        origin = draftOrigin,
        status = draftStatus,
        currentRevision = ProblemDraftRevisionRecord(
            draftId = draftId,
            revisionNumber = persistedRevisionNumber,
            basisRevisionNumber = revisionBasisRevisionNumber,
            subject = revisionSubject,
            title = revisionTitle
                ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no revision title"),
            questionDocument = CapturedQuestionDocumentCodec.decode(
                revisionQuestionDocumentSnapshot ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no question document",
                ),
            ),
            documentFingerprint = revisionDocumentFingerprint
                ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no document fingerprint",
                ),
            author = revisionAuthor
                ?: throw LearningLedgerIntegrityException("Pending draft $draftId has no revision author"),
            createdAtEpochMillis = revisionCreatedAtEpochMillis
                ?: throw LearningLedgerIntegrityException(
                    "Pending draft $draftId has no revision creation time",
                ),
        ),
        createdAtEpochMillis = draftCreatedAtEpochMillis,
        updatedAtEpochMillis = draftUpdatedAtEpochMillis,
        requestFingerprint = draftRequestFingerprint,
    )
}
