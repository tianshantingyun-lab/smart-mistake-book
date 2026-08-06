package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.data.authority.LocalLearningAuthorityRuntime
import com.tingyun.smartmistakebook.core.domain.AppendCaptureDraftPageRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSplitResult
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.CaptureDraftWorkspaceSnapshot
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
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
import com.tingyun.smartmistakebook.core.student.mistake.database.LibraryStudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveHandoffRecord
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.TutorStudentCaptureSaveSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Temporary capture/session capability retained in the legacy session store.
 *
 * Implementations may persist drafts, model/session coordination, and save acknowledgements only.
 * They must not create or update legacy problem, revision, practice-unit, error-book, mastery, or
 * knowledge rows. Confirmed-problem persistence stays outside this capability.
 */
internal interface ProductionCaptureSessionPort : CaptureDraftSessionPort {
    val learnerId: String

    fun observePendingCaptures(): Flow<List<PendingCaptureItem>>

    suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft?

    suspend fun readDraftWorkspace(draftId: String): CaptureDraftWorkspaceSnapshot?

    suspend fun saveDraftWorkspace(
        request: SaveCaptureDraftWorkspaceRequest,
    ): CaptureDraftWorkspaceSnapshot

    suspend fun consumeDraftWorkspace(request: ConsumeCaptureDraftWorkspaceRequest): Boolean

    suspend fun appendDraftPage(request: AppendCaptureDraftPageRequest): CaptureDraftSummary

    suspend fun replaceDraft(request: ReplaceCaptureDraftRequest): CaptureDraftSummary

    suspend fun splitDraft(request: SplitCaptureDraftRequest): CaptureDraftSplitResult

    suspend fun confirmForTutoring(
        request: ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession

    suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession?

    suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<TutorVisualSourceAssetScope>

    suspend fun endTutorSessionWithoutSaving(
        request: EndTutorSessionWithoutSaveRequest,
    ): EndTutorSessionWithoutSaveResult

    suspend fun acknowledgeStudentOwnedCaptureSession(
        receipt: CaptureStudentSaveSessionReceipt,
    )
}

/**
 * Capture-owned command preparation capability. It is deliberately separate from the temporary
 * session port so session consumers cannot reach student-mistake business commands.
 */
internal interface ProductionCaptureCommitPreparationPort {
    fun studentOwnedLibraryCaptureSource(
        request: ConfirmCapturedProblemRequest,
    ): LibraryStudentCaptureSaveSource

    /**
     * Prepares exactly one independent capture occurrence. Images from another draft or capture
     * intent must never be appended here by guessing cross-capture page order.
     */
    suspend fun prepareStudentOwnedLibraryCapture(
        request: ConfirmCapturedProblemRequest,
        source: LibraryStudentCaptureSaveSource,
    ): PreparedStudentOwnedCaptureCommit

    suspend fun studentOwnedTutorCaptureSource(
        request: SaveTutorSessionRequest,
    ): TutorStudentCaptureSaveSource

    /** Prepares one tutor-save occurrence without merging images from another capture intent. */
    suspend fun prepareStudentOwnedTutorCapture(
        request: SaveTutorSessionRequest,
        source: TutorStudentCaptureSaveSource,
    ): PreparedStudentOwnedCaptureCommit
}

internal sealed interface CaptureStudentSaveSourceReceipt {
    val intentId: String
    val sourceCanonicalFingerprint: String
    val draftId: String
    val occurredAtEpochMillis: Long
}

internal data class LibraryCaptureStudentSaveSourceReceipt(
    override val intentId: String,
    override val sourceCanonicalFingerprint: String,
    override val draftId: String,
    val basisRevisionNumber: Int,
    val workspaceVersion: Long,
    val workspaceCanonicalFingerprint: String,
    val confirmationRequestId: String,
    override val occurredAtEpochMillis: Long,
) : CaptureStudentSaveSourceReceipt

internal data class TutorCaptureStudentSaveSourceReceipt(
    override val intentId: String,
    override val sourceCanonicalFingerprint: String,
    override val draftId: String,
    val saveRequestId: String,
    val sessionId: String,
    val draftRevisionNumber: Int,
    override val occurredAtEpochMillis: Long,
) : CaptureStudentSaveSourceReceipt

/**
 * Narrow receipt used only to close temporary session state after the student authority commits.
 * It contains stable scalar identities, not a student-store command or database capability.
 */
internal data class CaptureStudentSaveSessionReceipt(
    val learnerId: String,
    val source: CaptureStudentSaveSourceReceipt,
    val targetSubject: String,
    val targetProblemId: String,
    val targetPracticeUnitId: String,
    val targetRevisionId: String,
    val targetRevisionNumber: Int,
    val targetDocumentCanonicalFingerprint: String,
    val errorBookEntryId: String,
    val targetCanonicalFingerprint: String,
)

/**
 * Production capture boundary.
 *
 * Temporary draft/session state stays behind [ProductionCaptureSessionPort]. Confirmed problem
 * content, every original image and the first error occurrence are written only by
 * [StudentAuthorityCaptureCommitCoordinator] to the learner-bound student mistake authority.
 * Historical mirror recovery is a separate migration route and is never part of a new save.
 */
internal class ProductionCaptureWorkflowRepository(
    private val session: ProductionCaptureSessionPort,
    private val commitPreparation: ProductionCaptureCommitPreparationPort,
    commitPortProvider: suspend () -> ProductionCaptureOccurrenceCommitPort,
) : CaptureWorkflowRepository {
    private val commits =
        StudentAuthorityCaptureCommitCoordinator(
            learnerId = session.learnerId,
            commitPortProvider = commitPortProvider,
            acknowledgeLegacySession = { handoff ->
                session.acknowledgeStudentOwnedCaptureSession(handoff.toSessionReceipt())
            },
        )

    override fun observePendingCaptures(): Flow<List<PendingCaptureItem>> =
        session.observePendingCaptures().map { pending ->
            commits.replayPendingSessionAcknowledgements()
            val committed =
                commits.committedDraftIds(
                    pending.mapTo(linkedSetOf(), PendingCaptureItem::draftId),
                )
            pending.filterNot { it.draftId in committed }
        }

    override suspend fun readPendingCapture(draftId: String): ResumableCaptureDraft? {
        commits.replayPendingSessionAcknowledgements()
        if (isCommitted(draftId)) return null
        return session.readPendingCapture(draftId)
    }

    override suspend fun readDraftWorkspace(
        draftId: String,
    ): CaptureDraftWorkspaceSnapshot? {
        if (isCommitted(draftId)) return null
        return session.readDraftWorkspace(draftId)
    }

    override suspend fun saveDraftWorkspace(
        request: SaveCaptureDraftWorkspaceRequest,
    ): CaptureDraftWorkspaceSnapshot = withDraftMutationLock(request.draftId) {
        requireUncommitted(request.draftId)
        session.saveDraftWorkspace(request)
    }

    override suspend fun consumeDraftWorkspace(
        request: ConsumeCaptureDraftWorkspaceRequest,
    ): Boolean = withDraftMutationLock(request.identity.draftId) {
        if (isCommitted(request.identity.draftId)) true
        else session.consumeDraftWorkspace(request)
    }

    override suspend fun importDraft(
        request: CaptureDraftImportRequest,
    ): CaptureDraftSummary {
        val draftSessionId =
            session.importDraft(
                ImportCaptureDraftSessionCommand(request),
            )
        return checkNotNull(session.readDraftSummary(draftSessionId)) {
            "Imported capture draft session is unavailable"
        }
    }

    override suspend fun appendDraftPage(
        request: AppendCaptureDraftPageRequest,
    ): CaptureDraftSummary = withDraftMutationLock(request.draftId) {
        requireUncommitted(request.draftId)
        session.appendDraftPage(request)
    }

    override suspend fun replaceDraft(
        request: ReplaceCaptureDraftRequest,
    ): CaptureDraftSummary = withDraftMutationLock(request.replacedDraftId) {
        requireUncommitted(request.replacedDraftId)
        session.replaceDraft(request)
    }

    override suspend fun splitDraft(
        request: SplitCaptureDraftRequest,
    ): CaptureDraftSplitResult = withDraftMutationLock(request.draftId) {
        requireUncommitted(request.draftId)
        session.splitDraft(request)
    }

    override suspend fun confirmForTutoring(
        request: ConfirmCapturedProblemRequest,
    ): ConfirmedTutorSession = withDraftMutationLock(request.draftId) {
        requireUncommitted(request.draftId)
        session.confirmForTutoring(request)
    }

    override suspend fun readTutorSession(sessionId: String): ConfirmedTutorSession? {
        commits.replayPendingSessionAcknowledgements()
        val tutorSession = session.readTutorSession(sessionId) ?: return null
        val studentSave = commits.committedTutorSession(sessionId) ?: return tutorSession
        return tutorSession.copy(
            isSaved = true,
            isEndedWithoutSave = false,
            errorBookEntryId = studentSave.errorBookEntryId,
        )
    }

    override suspend fun readTutorVisualSourceAssets(
        sessionId: String,
    ): List<TutorVisualSourceAssetScope> =
        session.readTutorVisualSourceAssets(sessionId)

    override suspend fun confirmAndCommit(
        request: ConfirmCapturedProblemRequest,
    ): CapturedProblemCommitSummary = withDraftMutationLock(request.draftId) {
        val source = commitPreparation.studentOwnedLibraryCaptureSource(request)
        commits.replayCommitted(source)
            ?: commits.commit(
                commitPreparation.prepareStudentOwnedLibraryCapture(
                    request = request,
                    source = source,
                ).requireSource(source)
                    .requireLearner(session.learnerId),
            )
    }

    override suspend fun saveTutorSession(
        request: SaveTutorSessionRequest,
    ): CapturedProblemCommitSummary = tutorLifecycleMutex.withLock {
        val source = commitPreparation.studentOwnedTutorCaptureSource(request)
        withDraftMutationLock(source.draftId) {
            commits.replayCommitted(source)
                ?: commits.commit(
                    commitPreparation.prepareStudentOwnedTutorCapture(
                        request = request,
                        source = source,
                    ).requireSource(source)
                        .requireLearner(session.learnerId),
                )
        }
    }

    override suspend fun endTutorSessionWithoutSaving(
        request: EndTutorSessionWithoutSaveRequest,
    ): EndTutorSessionWithoutSaveResult = tutorLifecycleMutex.withLock {
        require(commits.committedTutorSession(request.sessionId) == null) {
            "A student-owned saved tutor session cannot be ended without saving"
        }
        session.endTutorSessionWithoutSaving(request)
    }

    private suspend fun isCommitted(draftId: String): Boolean =
        draftId in commits.committedDraftIds(setOf(draftId))

    private suspend fun requireUncommitted(draftId: String) {
        check(!isCommitted(draftId)) {
            "A student-owned saved capture cannot be changed by a late session result"
        }
    }

    private suspend fun <T> withDraftMutationLock(
        draftId: String,
        operation: suspend () -> T,
    ): T {
        require(draftId.isNotBlank()) { "Capture draft id must not be blank" }
        return draftMutationLocks.computeIfAbsent(draftId) { Mutex() }.withLock {
            operation()
        }
    }

    private fun PreparedStudentOwnedCaptureCommit.requireLearner(
        expectedLearnerId: String,
    ): PreparedStudentOwnedCaptureCommit = also { prepared ->
        check(
            prepared.command.target.problem.revision.problem.learnerId == expectedLearnerId,
        ) {
            "Capture save preparation belongs to another learner"
        }
    }

    private fun PreparedStudentOwnedCaptureCommit.requireSource(
        expectedSource: StudentCaptureSaveSource,
    ): PreparedStudentOwnedCaptureCommit = also { prepared ->
        check(prepared.command.source == expectedSource) {
            "Capture save preparation belongs to another immutable source"
        }
        check(prepared.summary.draftId == expectedSource.draftId) {
            "Capture save summary belongs to another draft"
        }
        check(
            prepared.summary.problemRevisionId ==
                prepared.command.target.problem.revision.revisionId,
        ) {
            "Capture save summary belongs to another problem revision"
        }
    }

    private companion object {
        // Serialize terminal tutor decisions across every repository instance in this process.
        val tutorLifecycleMutex = Mutex()
        val draftMutationLocks = ConcurrentHashMap<String, Mutex>()
    }
}

/**
 * The production factory accepts only reviewed temporary-session capabilities and the
 * owner-composed student occurrence port. It cannot receive a legacy database, mirror transition,
 * separate identity resolver, or broad legacy repository.
 */
internal object ProductionCaptureWorkflowRepositoryFactory {
    fun create(
        session: ProductionCaptureSessionPort,
        commitPreparation: ProductionCaptureCommitPreparationPort,
        commitPortProvider: suspend () -> ProductionCaptureOccurrenceCommitPort,
    ): CaptureWorkflowRepository =
        ProductionCaptureWorkflowRepository(
            session = session,
            commitPreparation = commitPreparation,
            commitPortProvider = commitPortProvider,
        )

    fun createFromAuthorityRuntime(
        session: ProductionCaptureSessionPort,
        commitPreparation: ProductionCaptureCommitPreparationPort,
        runtimeProvider: suspend () -> LocalLearningAuthorityRuntime,
    ): CaptureWorkflowRepository =
        create(
            session = session,
            commitPreparation = commitPreparation,
            commitPortProvider = {
                runtimeProvider().productionCaptureOccurrenceCommitPort()
            },
        )
}

private fun StudentCaptureSaveHandoffRecord.toSessionReceipt():
    CaptureStudentSaveSessionReceipt =
    CaptureStudentSaveSessionReceipt(
        learnerId = learnerId,
        source =
            when (val durableSource = source) {
                is LibraryStudentCaptureSaveSource ->
                    LibraryCaptureStudentSaveSourceReceipt(
                        intentId = durableSource.intentId,
                        sourceCanonicalFingerprint =
                            durableSource.sourceCanonicalFingerprint,
                        draftId = durableSource.draftId,
                        basisRevisionNumber = durableSource.basisRevisionNumber,
                        workspaceVersion = durableSource.workspaceVersion,
                        workspaceCanonicalFingerprint =
                            durableSource.workspaceCanonicalFingerprint,
                        confirmationRequestId = durableSource.confirmationRequestId,
                        occurredAtEpochMillis = durableSource.occurredAtEpochMillis,
                    )

                is TutorStudentCaptureSaveSource ->
                    TutorCaptureStudentSaveSourceReceipt(
                        intentId = durableSource.intentId,
                        sourceCanonicalFingerprint =
                            durableSource.sourceCanonicalFingerprint,
                        draftId = durableSource.draftId,
                        saveRequestId = durableSource.saveRequestId,
                        sessionId = durableSource.sessionId,
                        draftRevisionNumber = durableSource.draftRevisionNumber,
                        occurredAtEpochMillis = durableSource.occurredAtEpochMillis,
                    )
            },
        targetSubject = targetProblem.subject.name,
        targetProblemId = targetProblem.problemId,
        targetPracticeUnitId = targetProblem.practiceUnitId,
        targetRevisionId = targetRevision.revisionId,
        targetRevisionNumber = targetRevision.revisionNumber,
        targetDocumentCanonicalFingerprint =
            targetRevision.documentCanonicalFingerprint,
        errorBookEntryId = errorBookEntryId,
        targetCanonicalFingerprint = targetCanonicalFingerprint,
    )
