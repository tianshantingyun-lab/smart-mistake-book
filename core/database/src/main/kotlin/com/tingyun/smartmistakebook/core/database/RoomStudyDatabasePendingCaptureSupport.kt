package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureIndexRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureWorkspaceColumns
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.dao.toRecord
import com.tingyun.smartmistakebook.core.database.dao.toSnapshot
import com.tingyun.smartmistakebook.core.database.dao.toModel
import com.tingyun.smartmistakebook.core.database.entity.CaptureDraftMergeSessionReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationWorkEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationFactKind
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

internal class RoomStudyDatabasePendingCaptureSupport(
    private val database: StudyDatabase,
) {
    suspend fun loadPendingCaptureBatch(): List<PendingCaptureDraftRecord> =
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

    suspend fun loadPendingCapture(
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
    /**
     * Claims an exact legacy receipt while its commit transaction is still open.
     *
     * A process death after the transaction commits therefore always leaves either no legacy save
     * or a durable PREPARED/FINALIZED handoff that startup replay can discover.
     */
    suspend fun prepareLegacyStudentSaveHandoff(
        command: CommitProblemDraftCommand,
        receipt: ProblemDraftCommitReceipt,
    ) {
        val claim = command.legacyStudentSaveClaim ?: return
        val claimed =
            database.legacyAuthorityMigrationSourceDao().readClaimableCaptureStudentSave(
                learnerId = claim.learnerId,
                intentId = receipt.commandId,
                intentCanonicalFingerprint = receipt.payloadFingerprint,
                draftId = receipt.draftId,
                draftRevisionNumber = receipt.draftRevisionNumber,
                tutorSessionId = claim.tutorSessionId,
                problemId = receipt.problemId,
                problemRevisionId = receipt.problemRevisionId,
                practiceUnitId = receipt.practiceUnitId,
                errorBookEntryId = receipt.errorBookEntryId,
            ) ?: throw CaptureStudentSaveHandoffIntegrityException(
                "Legacy capture commit could not prove its exact current revision and practice unit",
            )

        val targetProblemRef =
            try {
                StudentProblemRef(
                    learnerId = claim.learnerId,
                    subject = SubjectKind.valueOf(claimed.subject),
                    problemId = claimed.problemId,
                    practiceUnitId = claimed.practiceUnitId,
                )
            } catch (failure: IllegalArgumentException) {
                throw CaptureStudentSaveHandoffIntegrityException(
                    "Legacy capture commit contains an invalid target problem identity",
                    failure,
                )
            }
        val targetRevisionRef =
            try {
                StudentProblemRevisionRef(
                    problem = targetProblemRef,
                    revisionId = claimed.revisionId,
                    revisionNumber = claimed.revisionNumber,
                    documentCanonicalFingerprint = claimed.contentFingerprint,
                )
            } catch (failure: IllegalArgumentException) {
                throw CaptureStudentSaveHandoffIntegrityException(
                    "Legacy capture commit contains an invalid target revision identity",
                    failure,
                )
            }
        database.captureStudentSaveHandoffDao().prepare(
            PrepareCaptureStudentSaveHandoffCommand(
                intentId = receipt.commandId,
                intentCanonicalFingerprint = receipt.payloadFingerprint,
                learnerId = claim.learnerId,
                draftId = receipt.draftId,
                draftRevisionNumber = receipt.draftRevisionNumber,
                sessionId = claim.tutorSessionId,
                targetProblemRef = targetProblemRef,
                targetProblemRevisionRef = targetRevisionRef,
                preparedAtEpochMillis = claimed.committedAtEpochMillis,
            ),
        )
    }
}
