package com.tingyun.smartmistakebook.core.data.session

import android.content.Context
import com.tingyun.smartmistakebook.core.data.capture.AndroidCanonicalAssetVault
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftCanonicalAssetBundle
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftMergeReceipt
import com.tingyun.smartmistakebook.core.data.capture.CaptureDraftSessionId
import com.tingyun.smartmistakebook.core.data.capture.CaptureStudentSaveSessionReceipt
import com.tingyun.smartmistakebook.core.data.capture.ImportCaptureDraftSessionCommand
import com.tingyun.smartmistakebook.core.data.capture.LibraryCaptureStudentSaveSourceReceipt
import com.tingyun.smartmistakebook.core.data.capture.MergeAdjacentCaptureDraftsCommand
import com.tingyun.smartmistakebook.core.data.capture.MlKitChineseQuestionTextRecognizer
import com.tingyun.smartmistakebook.core.data.capture.PreparedStudentOwnedCaptureCommit
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureCommitPreparationPort
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureSessionPort
import com.tingyun.smartmistakebook.core.data.capture.ReadCaptureDraftCanonicalAssetsQuery
import com.tingyun.smartmistakebook.core.data.capture.ReadCaptureDraftMergeReceiptQuery
import com.tingyun.smartmistakebook.core.data.capture.TutorCaptureStudentSaveSourceReceipt
import com.tingyun.smartmistakebook.core.data.session.capture.LegacyRoomCaptureAssetBridge
import com.tingyun.smartmistakebook.core.data.session.capture.LegacyRoomCaptureSessionStateStore
import com.tingyun.smartmistakebook.core.database.AcknowledgeStudentOwnedCaptureSessionCommand
import com.tingyun.smartmistakebook.core.database.ExpectedProblemDraftEditWorkspace
import com.tingyun.smartmistakebook.core.database.LegacyCaptureSessionDatabasePort
import com.tingyun.smartmistakebook.core.database.StudentOwnedLibraryCaptureSessionAckSource
import com.tingyun.smartmistakebook.core.database.StudentOwnedTutorCaptureSessionAckSource
import com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.ConfirmCapturedProblemRequest
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.ConsumeCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveRequest
import com.tingyun.smartmistakebook.core.domain.EndTutorSessionWithoutSaveResult
import com.tingyun.smartmistakebook.core.domain.PendingCaptureItem
import com.tingyun.smartmistakebook.core.domain.ReplaceCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.ResumableCaptureDraft
import com.tingyun.smartmistakebook.core.domain.SaveCaptureDraftWorkspaceRequest
import com.tingyun.smartmistakebook.core.domain.SaveTutorSessionRequest
import com.tingyun.smartmistakebook.core.domain.SplitCaptureDraftRequest
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.student.mistake.database.LibraryStudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.TutorStudentCaptureSaveSource
import kotlinx.coroutines.flow.Flow

/**
 * Audited adapter for temporary capture drafts and sessions that have not yet left the legacy
 * store. Its public capability surface has no legacy problem/error-book commit operation.
 */
private class LegacyRoomCaptureSessionAdapter(
    override val learnerId: String,
    private val legacySession: LegacyRoomCaptureSessionStateStore,
) : ProductionCaptureSessionPort, ProductionCaptureCommitPreparationPort {
    init {
        require(learnerId.isNotBlank()) { "Capture session learner id must not be blank" }
    }

    override fun observePendingCaptures(): Flow<List<PendingCaptureItem>> =
        legacySession.observePendingCaptures()

    override suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft? =
        legacySession.readPendingCapture(draftId)

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): CaptureDraftWorkspaceSnapshot? =
        legacySession.readDraftWorkspace(draftId)

    override suspend fun saveDraftWorkspace(
        request: SaveCaptureDraftWorkspaceRequest,
    ): CaptureDraftWorkspaceSnapshot =
        legacySession.saveDraftWorkspace(request)

    override suspend fun consumeDraftWorkspace(
        request: ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean =
        legacySession.consumeDraftWorkspace(request)

    override suspend fun importDraft(
        command: ImportCaptureDraftSessionCommand,
    ): CaptureDraftSessionId =
        CaptureDraftSessionId(legacySession.importDraft(command.request).draftId)

    override suspend fun readDraftSummary(
        draftSessionId: CaptureDraftSessionId,
    ): CaptureDraftSummary? =
        legacySession.readDraftSummary(draftSessionId)

    override suspend fun readCanonicalSourceAssets(
        query: ReadCaptureDraftCanonicalAssetsQuery,
    ): CaptureDraftCanonicalAssetBundle? =
        legacySession.readCanonicalSourceAssets(query)

    override suspend fun mergeAdjacentDrafts(
        command: MergeAdjacentCaptureDraftsCommand,
    ): CaptureDraftMergeReceipt =
        legacySession.mergeAdjacentDrafts(command)

    override suspend fun readMergeReceipt(
        query: ReadCaptureDraftMergeReceiptQuery,
    ): CaptureDraftMergeReceipt? =
        legacySession.readMergeReceipt(query)

    override suspend fun appendDraftPage(
        request: AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary =
        legacySession.appendDraftPage(request)

    override suspend fun replaceDraft(
        request: ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary =
        legacySession.replaceDraft(request)

    override suspend fun splitDraft(
        request: SplitCaptureDraftRequest,
    ): CaptureDraftSplitResult =
        legacySession.splitDraft(request)

    override suspend fun confirmForTutoring(
        request: ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession =
        legacySession.confirmForTutoring(request)

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? =
        legacySession.readTutorSession(sessionId)

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<TutorVisualSourceAssetScope> =
        legacySession.readTutorVisualSourceAssets(sessionId)

    override suspend fun endTutorSessionWithoutSaving(
        request: EndTutorSessionWithoutSaveRequest,
    ): EndTutorSessionWithoutSaveResult =
        legacySession.endTutorSessionWithoutSaving(request)

    override fun studentOwnedLibraryCaptureSource(
        request: ConfirmCapturedProblemRequest,
    ): LibraryStudentCaptureSaveSource =
        legacySession.studentOwnedLibraryCaptureSource(request)

    override suspend fun prepareStudentOwnedLibraryCapture(
        request: ConfirmCapturedProblemRequest,
        source: LibraryStudentCaptureSaveSource,
    ): PreparedStudentOwnedCaptureCommit =
        legacySession.prepareStudentOwnedLibraryCapture(
            request = request,
            source = source,
            learnerId = learnerId,
        )

    override suspend fun studentOwnedTutorCaptureSource(
        request: SaveTutorSessionRequest,
    ): TutorStudentCaptureSaveSource =
        legacySession.studentOwnedTutorCaptureSource(request)

    override suspend fun prepareStudentOwnedTutorCapture(
        request: SaveTutorSessionRequest,
        source: TutorStudentCaptureSaveSource,
    ): PreparedStudentOwnedCaptureCommit =
        legacySession.prepareStudentOwnedTutorCapture(
            request = request,
            source = source,
            learnerId = learnerId,
        )

    override suspend fun acknowledgeStudentOwnedCaptureSession(
        receipt: CaptureStudentSaveSessionReceipt,
    ) {
        require(receipt.learnerId == learnerId) {
            "Capture save acknowledgement belongs to another learner"
        }
        legacySession.acknowledgeStudentOwnedCaptureSession(
            receipt.toLegacySessionAckCommand(),
        )
    }
}

internal data class LegacyCaptureProductionPorts(
    val session: ProductionCaptureSessionPort,
    val commitPreparation: ProductionCaptureCommitPreparationPort,
)

/**
 * Migration/session-only construction of the temporary capture capability.
 *
 * The legacy state store receives only the owner-issued capture-session capability and is never
 * returned to the production capture workflow factory.
 */
internal object LegacyRoomCaptureSessionPortFactory {
    fun create(
        context: Context,
        database: LegacyCaptureSessionDatabasePort,
        learnerId: String,
    ): LegacyCaptureProductionPorts {
        val adapter =
            LegacyRoomCaptureSessionAdapter(
                learnerId = learnerId,
                legacySession =
                    LegacyRoomCaptureSessionStateStore(
                        database = database,
                        assetVault =
                            LegacyRoomCaptureAssetBridge(
                                AndroidCanonicalAssetVault(context.applicationContext),
                            ),
                        localTextRecognizer =
                            MlKitChineseQuestionTextRecognizer(context.applicationContext),
                    ),
            )
        return LegacyCaptureProductionPorts(
            session = adapter,
            commitPreparation = adapter,
        )
    }
}

private fun CaptureStudentSaveSessionReceipt.toLegacySessionAckCommand(): AcknowledgeStudentOwnedCaptureSessionCommand {
    val problem =
        StudentProblemRef(
            learnerId = learnerId,
            subject = SubjectKind.valueOf(targetSubject),
            problemId = targetProblemId,
            practiceUnitId = targetPracticeUnitId,
        )
    val revision =
        StudentProblemRevisionRef(
            problem = problem,
            revisionId = targetRevisionId,
            revisionNumber = targetRevisionNumber,
            documentCanonicalFingerprint = targetDocumentCanonicalFingerprint,
        )
    return AcknowledgeStudentOwnedCaptureSessionCommand(
        learnerId = learnerId,
        source =
            when (val durableSource = source) {
                is LibraryCaptureStudentSaveSourceReceipt ->
                    StudentOwnedLibraryCaptureSessionAckSource(
                        intentId = durableSource.intentId,
                        sourceCanonicalFingerprint =
                            durableSource.sourceCanonicalFingerprint,
                        workspace =
                            ExpectedProblemDraftEditWorkspace(
                                draftId = durableSource.draftId,
                                basisRevisionNumber = durableSource.basisRevisionNumber,
                                workspaceVersion = durableSource.workspaceVersion,
                                workspaceFingerprint =
                                    durableSource.workspaceCanonicalFingerprint,
                                finalRequestId = durableSource.confirmationRequestId,
                                finalOccurredAtEpochMillis =
                                    durableSource.occurredAtEpochMillis,
                            ),
                    )

                is TutorCaptureStudentSaveSourceReceipt ->
                    StudentOwnedTutorCaptureSessionAckSource(
                        intentId = durableSource.intentId,
                        sourceCanonicalFingerprint =
                            durableSource.sourceCanonicalFingerprint,
                        saveRequestId = durableSource.saveRequestId,
                        sessionId = durableSource.sessionId,
                        draftId = durableSource.draftId,
                        draftRevisionNumber = durableSource.draftRevisionNumber,
                        occurredAtEpochMillis = durableSource.occurredAtEpochMillis,
                    )
            },
        targetProblem = problem,
        targetRevision = revision,
        targetSaveReceiptFingerprint = targetCanonicalFingerprint,
        acknowledgedAtEpochMillis = source.occurredAtEpochMillis,
    )
}
