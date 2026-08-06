package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkDatabasePort
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkStatus
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkWriteDisposition
import com.tingyun.smartmistakebook.core.database.MarkCurrentTutorSessionHostWorkActiveCommand
import com.tingyun.smartmistakebook.core.database.RevokeCurrentTutorSessionHostWorkCommand
import com.tingyun.smartmistakebook.core.database.ClaimCurrentTutorFreeResponseActionCommand
import com.tingyun.smartmistakebook.core.database.AcquireCurrentTutorFreeResponseDispatchCommand
import com.tingyun.smartmistakebook.core.database.CompleteCurrentTutorFreeResponseDispatchCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseActionClaimDisposition
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseActionClaimQuery
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchAcquireResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchLease
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchMutationResult
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchState
import com.tingyun.smartmistakebook.core.database.FailCurrentTutorFreeResponseDispatchClosedCommand
import com.tingyun.smartmistakebook.core.database.ReleaseCurrentTutorFreeResponseDispatchCommand
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseContextRequest
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseLearningReceipt
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseSubmissionResult
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorFreeResponseSubmission
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorFreeResponseSubmissionPort
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionActionBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceFeedback
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseRetryAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintShownAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintShownResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicySnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyUpdate
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPresentation
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionQuestionPreparationBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionQuestionPreparationResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionRevocationReason
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionSavedMistakeReference
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPreparedVisualTarget
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetAction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetFeedback
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetPreparation
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionVisualTargetPreparationResult
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorSettingsRepository
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProofRegistry
import com.tingyun.smartmistakebook.core.model.TutorVisualPresentationIdentity
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewResponse
import java.util.function.LongSupplier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Current value from the local owner of long-term learning-write permission. */
internal class CurrentTutorSessionHostCoordinator(
    private val learnerId: String,
    private val questions: CurrentTutorQuestionSource,
    private val modelTasks: CurrentTutorModelTaskSource,
    private val modes: CurrentTutorModeSource,
    private val learningAuthorityStore: CurrentTutorLearningAuthorityStore,
    private val hostWork: CurrentTutorSessionHostWorkDatabasePort,
    private val learningWriteAuthority: CurrentTutorLearningWriteAuthority,
    private val verifiedMaterialAuthority: CurrentTutorVerifiedMaterialAuthority,
    private val activationOwner: CurrentTutorSessionActivationOwner,
    private val interactionWriter: TutorInteractionRepository? = null,
    private val policyController: CurrentTutorHostPolicyController? = null,
    private val savedMistakePreparer: CurrentTutorSavedMistakeQuestionPreparer? = null,
    private val freeResponseSubmission: CurrentTutorFreeResponseSubmissionPort? = null,
    private val freeResponseDispatchGenerationId: String =
        CURRENT_TUTOR_PROCESS_DISPATCH_GENERATION_ID,
    private val nowEpochMillis: LongSupplier = LongSupplier { System.currentTimeMillis() },
) : TutorCurrentSessionHostPort {
    private val interactionAuthorities = ConcurrentHashMap<String, PreparedInteractionAuthority>()
    private val visualTargetGrants = ConcurrentHashMap<String, PreparedVisualTargetGrant>()
    private val freeResponseGrants = ConcurrentHashMap<String, PreparedFreeResponseGrant>()
    private val activeFreeResponseDispatches =
        ConcurrentHashMap<String, ActiveFreeResponseDispatch>()
    private val freeResponseTokensByPresentation = ConcurrentHashMap<String, String>()
    private val sessionPolicyLocks = ConcurrentHashMap<String, Mutex>()
    private val visualTargetSecret = UUID.randomUUID().toString()
    private val freeResponseDispatchOwnerId = "current-tutor-host:${UUID.randomUUID()}"

    init {
        requireHostId(learnerId)
        requireHostId(freeResponseDispatchGenerationId)
    }

    override fun observe(sessionId: String): Flow<TutorCurrentSessionHostSnapshot?> {
        requireHostId(sessionId)
        return hostWork.observeCurrentTutorSessionHostWork(learnerId, sessionId)
            .map { record -> record?.toHostSnapshot() }
    }

    override fun observePresentation(
        sessionId: String,
    ): Flow<TutorCurrentSessionPresentation?> {
        requireHostId(sessionId)
        return hostWork.observeCurrentTutorSessionHostWork(learnerId, sessionId)
            .map { record ->
                withSessionPolicyLock(sessionId) {
                    record
                        ?.takeIf { current ->
                            current.status == CurrentTutorSessionHostWorkStatus.ACTIVE
                        }
                        ?.let { current -> resolveCurrentPresentation(current)?.presentation }
                }
            }
    }

    override suspend fun prepareSavedMistake(
        reference: TutorCurrentSessionSavedMistakeReference,
    ): TutorCurrentSessionQuestionPreparationResult = try {
        savedMistakePreparer?.prepare(reference)
            ?: TutorCurrentSessionQuestionPreparationResult.Unavailable(
                TutorCurrentSessionQuestionPreparationBlocker.UNSUPPORTED,
            )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TutorCurrentSessionQuestionPreparationResult.Unavailable(
            TutorCurrentSessionQuestionPreparationBlocker.NOT_FOUND,
        )
    }

    override suspend fun updatePolicy(
        command: TutorCurrentSessionPolicyUpdate,
    ): TutorCurrentSessionPolicyResult = withSessionPolicyLock(command.sessionId) {
        guardedPolicy {
            val controller = policyController
                ?: return@guardedPolicy TutorCurrentSessionPolicyResult.Unavailable
            val before = hostWork.readCurrentTutorSessionHostWork(learnerId, command.sessionId)
            val policyChangesActiveWork = before != null &&
                before.status != CurrentTutorSessionHostWorkStatus.REVOKED &&
                (
                    before.explanationMode != command.explanationMode ||
                        before.learningWritesAllowed != command.learningWritesAllowed ||
                        before.visualIntent != command.visualIntent
                    )
            if (policyChangesActiveWork) {
                val revoked = revokeLocked(
                    sessionId = command.sessionId,
                    reason = when {
                        !command.learningWritesAllowed ->
                            TutorCurrentSessionRevocationReason.LEARNING_WRITES_DISABLED
                        else -> TutorCurrentSessionRevocationReason.MODE_CHANGED
                    },
                )
                if (revoked !is TutorCurrentSessionHostResult.Revoked) {
                    return@guardedPolicy TutorCurrentSessionPolicyResult.Unavailable
                }
            }
            val updated = controller.update(command)
                ?: return@guardedPolicy TutorCurrentSessionPolicyResult.Unavailable
            TutorCurrentSessionPolicyResult.Current(updated)
        }
    }

    override suspend fun currentPolicy(
        sessionId: String,
    ): TutorCurrentSessionPolicyResult = withSessionPolicyLock(sessionId) {
        guardedPolicy {
            val snapshot = policyController?.currentPolicy(sessionId)
                ?: return@guardedPolicy TutorCurrentSessionPolicyResult.Unavailable
            TutorCurrentSessionPolicyResult.Current(snapshot)
        }
    }

    override suspend fun recordHintShown(
        action: TutorCurrentSessionHintShownAction,
    ): TutorCurrentSessionHintShownResult = withSessionPolicyLock(action.sessionId) {
        try {
            val record = currentActiveRecord(action.sessionId, action.presentationToken)
                ?: return@withSessionPolicyLock TutorCurrentSessionHintShownResult.Rejected(
                    TutorCurrentSessionActionBlocker.NOT_CURRENT,
                )
            val current = resolveCurrentPresentation(record)
                ?: return@withSessionPolicyLock TutorCurrentSessionHintShownResult.Rejected(
                    TutorCurrentSessionActionBlocker.NOT_CURRENT,
                )
            val hint = current.presentation.hint
            if (hint == null || hint.slotToken != action.slotToken) {
                return@withSessionPolicyLock TutorCurrentSessionHintShownResult.Rejected(
                    TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
                )
            }
            val evidenceState = currentEvidenceState(record)
                ?: return@withSessionPolicyLock TutorCurrentSessionHintShownResult.Rejected(
                    TutorCurrentSessionActionBlocker.NOT_CURRENT,
                )
            if (
                evidenceState.answerWasRevealed ||
                evidenceState.hintCount !in 0..1 ||
                (hint.status == TutorCurrentSessionHintStatus.AVAILABLE &&
                    evidenceState.hintCount != 0) ||
                (hint.status == TutorCurrentSessionHintStatus.SHOWN &&
                    evidenceState.hintCount != 1)
            ) {
                return@withSessionPolicyLock TutorCurrentSessionHintShownResult.Rejected(
                    TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
                )
            }
            when (
                activationOwner.recordHintShown(
                    CurrentTutorHintShownCommit(
                        sessionId = record.sessionId,
                        expectedScopeId = evidenceState.scopeId,
                        expectedQuestionDocumentId = record.questionDocumentId,
                        expectedQuestionRevisionNumber = record.questionRevisionNumber,
                        expectedCycleOrdinal = record.cycleOrdinal,
                        expectedTurnOrdinal = record.turnOrdinal,
                        expectedPresentationFingerprint =
                            record.constrainedTutorContentFingerprint,
                        modeVersion = record.modeVersion,
                        slotToken = action.slotToken,
                        occurredAtEpochMillis = trustedNow(),
                    ),
                )
            ) {
                CurrentTutorHintShownCommitResult.RECORDED ->
                    TutorCurrentSessionHintShownResult.Recorded
                CurrentTutorHintShownCommitResult.DUPLICATE ->
                    TutorCurrentSessionHintShownResult.Duplicate
                CurrentTutorHintShownCommitResult.REJECTED ->
                    TutorCurrentSessionHintShownResult.Rejected(
                        TutorCurrentSessionActionBlocker.NOT_CURRENT,
                    )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TutorCurrentSessionHintShownResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
    }

    override suspend fun submitChoice(
        action: TutorCurrentSessionChoiceAction,
    ): TutorCurrentSessionChoiceActionResult = withSessionPolicyLock(action.sessionId) {
        guardedAction {
        val record = hostWork.readCurrentTutorSessionHostWork(learnerId, action.sessionId)
            ?.takeIf { current ->
                current.status == CurrentTutorSessionHostWorkStatus.ACTIVE &&
                    current.presentationToken == action.presentationToken
            }
            ?: return@guardedAction rejectedAction(TutorCurrentSessionActionBlocker.NOT_CURRENT)
        val current = resolveCurrentPresentation(record)
            ?: return@guardedAction rejectedAction(TutorCurrentSessionActionBlocker.NOT_CURRENT)
        val assessment = current.output.plan.diagnosticItem
        val choiceDirective = current.output.plan.interactionDirective
            as? TutorInteractionDirective.Choices
        if ((assessment == null) == (choiceDirective == null)) {
            return@guardedAction rejectedAction(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        val modelFeedback = assessment?.choices
            ?.singleOrNull { choice -> choice.id == action.choiceId }
            ?.feedbackMarkdown
        val directiveChoiceExists = choiceDirective?.choices
            ?.any { choice -> choice.id == action.choiceId } == true
        if (modelFeedback == null && !directiveChoiceExists) {
            return@guardedAction rejectedAction(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        val prepared = interactionAuthorities[record.presentationToken]
        val evidenceRequestId = record.evidenceRequestId
        if (evidenceRequestId == null || record.pendingInteractionKind == null) {
            // Model-authored feedback is presentation-only and can never become learning evidence.
            val feedback = modelFeedback
                ?: return@guardedAction rejectedAction(
                    TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
                )
            return@guardedAction TutorCurrentSessionChoiceActionResult.Answered(
                TutorCurrentSessionChoiceFeedback(feedbackMarkdown = feedback),
            )
        }
        if (!record.learningWritesAllowed) {
            return@guardedAction rejectedAction(
                TutorCurrentSessionActionBlocker.LEARNING_WRITES_DISABLED,
            )
        }
        val exactAuthority = prepared?.takeIf { authority ->
            authority.taskRequestFingerprint == current.task.requestFingerprint &&
                authority.evidenceRequestId == evidenceRequestId &&
                authority.evidenceKind == TutorEvidenceRequestKind.CHOICE
        } ?: return@guardedAction rejectedAction(
            TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
        )
        val material = resolveCurrentTutorLearningMaterial(
            learnerId = learnerId,
            verifiedMaterialAuthority = verifiedMaterialAuthority,
            current = current,
            kind = TutorEvidenceRequestKind.CHOICE,
        )
            ?.takeIf { value ->
                value.currentStepAuthorityFingerprint() == exactAuthority.authorityFingerprint
            }
            ?: return@guardedAction rejectedAction(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val answerInteraction = exactAuthority.answerInteraction
            ?.takeIf { interaction ->
                interaction.responseForm == ReviewResponseForm.CHOICE &&
                    interaction.matchesSource(
                        material.trustedAnswerInteraction,
                        record.presentationToken,
                    )
            }
            ?: return@guardedAction rejectedAction(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val evidenceState = currentEvidenceState(record)
            ?.takeUnless(CurrentTutorSessionEvidenceState::answerWasRevealed)
            ?: return@guardedAction rejectedAction(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val fence = CurrentTutorTrustedAnswerSubmissionFence(
            sessionId = record.sessionId,
            presentationToken = record.presentationToken,
            evidenceRequestId = evidenceRequestId,
            expectedEvidenceState = evidenceState,
            occurredAtEpochMillis = trustedNow(),
        )
        val submission = answerInteraction.submit(
            response = StudentTrustedReviewResponse.Choice(action.choiceId),
            fence = fence,
        )
        val receipt = when (submission) {
            is CurrentTutorTrustedAnswerSubmissionResult.Committed -> submission.receipt
            CurrentTutorTrustedAnswerSubmissionResult.Rejected ->
                return@guardedAction rejectedAction(
                    TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
                )
        }
        val persistedSubmission = currentEvidenceState(record)
        if (
            !receipt.matches(
                fence = fence,
                expectedContentBinding = checkNotNull(answerInteraction.exactContentBinding),
                expectedResponseForm = ReviewResponseForm.CHOICE,
                currentState = persistedSubmission,
            )
        ) {
            return@guardedAction rejectedAction(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        TutorCurrentSessionChoiceActionResult.Answered(
            TutorCurrentSessionChoiceFeedback(
                feedbackMarkdown = TRUSTED_ANSWER_ACCEPTED_FEEDBACK,
            ),
        )
        }
    }

    override suspend fun submitFreeResponse(
        action: TutorCurrentSessionFreeResponseAction,
    ): TutorCurrentSessionFreeResponseActionResult {
        val answer = action.answer.trim()
        val preparation = try {
            withSessionPolicyLock(action.sessionId) {
                prepareFreeResponseDispatch(action, answer)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
        if (preparation is FreeResponseDispatchPreparation.Rejected) {
            return TutorCurrentSessionFreeResponseActionResult.Rejected(preparation.blocker)
        }
        if (preparation is FreeResponseDispatchPreparation.AlreadyCompleted) {
            return TutorCurrentSessionFreeResponseActionResult.Duplicate
        }
        if (preparation is FreeResponseDispatchPreparation.InProgress) {
            return TutorCurrentSessionFreeResponseActionResult.Duplicate
        }
        return dispatchPreparedFreeResponse(preparation as FreeResponseDispatchPreparation.Ready)
    }

    override suspend fun retryFreeResponse(
        action: TutorCurrentSessionFreeResponseRetryAction,
    ): TutorCurrentSessionFreeResponseActionResult {
        val ready = try {
            withSessionPolicyLock(action.sessionId) {
                prepareRecoveredFreeResponseDispatch(action.sessionId, action.actionToken)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return TutorCurrentSessionFreeResponseActionResult.Rejected(
            TutorCurrentSessionActionBlocker.NOT_CURRENT,
        )
        return dispatchPreparedFreeResponse(ready)
    }

    private suspend fun dispatchPreparedFreeResponse(
        ready: FreeResponseDispatchPreparation.Ready,
    ): TutorCurrentSessionFreeResponseActionResult {
        val receipt = try {
            checkNotNull(freeResponseSubmission).submit(ready.submission)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { releaseFreeResponseDispatch(ready) }
            throw cancelled
        } catch (_: Exception) {
            CoreDataTutorOpenResponseSubmissionResult(
                CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY,
                null,
            )
        }
        if (!freeResponseEvidenceStillCurrent(ready)) {
            failFreeResponseDispatchClosed(ready)
            return TutorCurrentSessionFreeResponseActionResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
        return when (receipt.disposition) {
            CoreDataTutorOpenResponseLearningReceipt.PENDING,
            CoreDataTutorOpenResponseLearningReceipt.DUPLICATE,
            -> completeFreeResponseDispatch(ready, receipt)
            CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY -> {
                releaseFreeResponseDispatch(ready)
                TutorCurrentSessionFreeResponseActionResult.Rejected(
                    TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
                )
            }
            CoreDataTutorOpenResponseLearningReceipt.REJECTED,
            CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
            -> {
                failFreeResponseDispatchClosed(ready)
                TutorCurrentSessionFreeResponseActionResult.Rejected(
                    TutorCurrentSessionActionBlocker.NOT_CURRENT,
                )
            }
        }
    }

    private suspend fun completeFreeResponseDispatch(
        ready: FreeResponseDispatchPreparation.Ready,
        receipt: CoreDataTutorOpenResponseSubmissionResult,
    ): TutorCurrentSessionFreeResponseActionResult {
        val commitReceipt = checkNotNull(receipt.candidateCommitReceipt)
        val mutation = try {
            hostWork.completeCurrentTutorFreeResponseDispatch(
                CompleteCurrentTutorFreeResponseDispatchCommand(
                    learnerId = learnerId,
                    sessionId = ready.lease.sessionId,
                    actionToken = ready.lease.actionToken,
                    leaseToken = ready.lease.leaseToken,
                    candidateIdempotencyKey = commitReceipt.candidateIdempotencyKey,
                    candidateReceiptFingerprint = commitReceipt.receiptFingerprint,
                    occurredAtEpochMillis = trustedNow(),
                ),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { releaseFreeResponseDispatch(ready) }
            throw cancelled
        } catch (_: Exception) {
            activeFreeResponseDispatches.remove(ready.lease.actionToken, ready.activeDispatch)
            releaseFreeResponseLease(ready.lease, ready.activeDispatch)
            return TutorCurrentSessionFreeResponseActionResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        } finally {
            activeFreeResponseDispatches.remove(ready.lease.actionToken, ready.activeDispatch)
        }
        return if (
            mutation == CurrentTutorFreeResponseDispatchMutationResult.APPLIED ||
            mutation == CurrentTutorFreeResponseDispatchMutationResult.DUPLICATE
        ) {
            if (
                ready.duplicateClaim ||
                receipt.disposition == CoreDataTutorOpenResponseLearningReceipt.DUPLICATE
            ) {
                TutorCurrentSessionFreeResponseActionResult.Duplicate
            } else {
                TutorCurrentSessionFreeResponseActionResult.Accepted
            }
        } else {
            failFreeResponseDispatchClosed(ready)
            TutorCurrentSessionFreeResponseActionResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
    }

    private suspend fun releaseFreeResponseDispatch(
        ready: FreeResponseDispatchPreparation.Ready,
    ) = releaseFreeResponseLease(ready.lease, ready.activeDispatch)

    private suspend fun failFreeResponseDispatchClosed(
        ready: FreeResponseDispatchPreparation.Ready,
    ) = failFreeResponseLeaseClosed(ready.lease, ready.activeDispatch)

    private suspend fun failFreeResponseLeaseClosed(
        lease: CurrentTutorFreeResponseDispatchLease,
        activeDispatch: ActiveFreeResponseDispatch,
    ) {
        activeFreeResponseDispatches.remove(lease.actionToken, activeDispatch)
        try {
            hostWork.failCurrentTutorFreeResponseDispatchClosed(
                FailCurrentTutorFreeResponseDispatchClosedCommand(
                    learnerId = learnerId,
                    sessionId = lease.sessionId,
                    actionToken = lease.actionToken,
                    leaseToken = lease.leaseToken,
                    occurredAtEpochMillis = trustedNow(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
    }

    private suspend fun releaseFreeResponseLease(
        lease: CurrentTutorFreeResponseDispatchLease,
        activeDispatch: ActiveFreeResponseDispatch,
    ) {
        activeFreeResponseDispatches.remove(lease.actionToken, activeDispatch)
        try {
            hostWork.releaseCurrentTutorFreeResponseDispatch(
                ReleaseCurrentTutorFreeResponseDispatchCommand(
                    learnerId = learnerId,
                    sessionId = lease.sessionId,
                    actionToken = lease.actionToken,
                    leaseToken = lease.leaseToken,
                    occurredAtEpochMillis = trustedNow(),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Unit
        }
    }

    private suspend fun prepareFreeResponseDispatch(
        action: TutorCurrentSessionFreeResponseAction,
        answer: String,
    ): FreeResponseDispatchPreparation {
        if (freeResponseSubmission == null) {
            return FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.OWNER_CLOSED,
            )
        }
        val now = trustedNow()
        val grant = freeResponseGrants[action.actionToken]
            ?.takeIf { current -> current.sessionId == action.sessionId }
            ?: return FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        if (now >= grant.expiresAtEpochMillis) {
            return FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
        val dispatchContext = resolveFreeResponseDispatchContext(
            grant.sessionId,
            grant.presentationToken,
        )?.takeIf { context -> grant.matches(context.record, context.current.task, context.authority) }
            ?: return FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val record = dispatchContext.record
        val prepared = dispatchContext.authority
        val claim = hostWork.claimCurrentTutorFreeResponseAction(
            ClaimCurrentTutorFreeResponseActionCommand(
                learnerId = learnerId,
                sessionId = record.sessionId,
                expectedWorkId = record.workId,
                expectedWorkStateVersion = record.stateVersion,
                expectedWorkStateFingerprint = record.stateFingerprint,
                presentationToken = record.presentationToken,
                evidenceRequestId = prepared.evidenceRequestId,
                actionToken = action.actionToken,
                answer = answer,
                actionExpiresAtEpochMillis = grant.expiresAtEpochMillis,
                occurredAtEpochMillis = now,
            ),
        )
        val duplicate = when (claim.disposition) {
            CurrentTutorFreeResponseActionClaimDisposition.CLAIMED -> false
            CurrentTutorFreeResponseActionClaimDisposition.DUPLICATE -> true
            CurrentTutorFreeResponseActionClaimDisposition.NOT_CURRENT ->
                return FreeResponseDispatchPreparation.Rejected(
                    TutorCurrentSessionActionBlocker.NOT_CURRENT,
                )
            CurrentTutorFreeResponseActionClaimDisposition.REJECTED ->
                return FreeResponseDispatchPreparation.Rejected(
                    TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
                )
        }
        val persistedEvidenceState = currentEvidenceState(record)
        val claimStateIsCurrent = if (duplicate) {
            persistedEvidenceState == dispatchContext.evidenceState
        } else {
            persistedEvidenceState.isStudentSubmissionSuccessorOf(dispatchContext.evidenceState)
        }
        if (!claimStateIsCurrent) {
            return FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        val persistedContext = dispatchContext.copy(
            evidenceState = checkNotNull(persistedEvidenceState),
        )
        val acquired = hostWork.acquireCurrentTutorFreeResponseDispatch(
            AcquireCurrentTutorFreeResponseDispatchCommand(
                learnerId = learnerId,
                sessionId = record.sessionId,
                actionToken = action.actionToken,
                leaseOwnerId = freeResponseDispatchOwnerId,
                leaseGenerationId = freeResponseDispatchGenerationId,
                leaseDurationMillis = FREE_RESPONSE_DISPATCH_LEASE_MILLIS,
                occurredAtEpochMillis = trustedNow(),
            ),
        )
        val lease = when (acquired) {
            is CurrentTutorFreeResponseDispatchAcquireResult.Acquired -> acquired.lease
            CurrentTutorFreeResponseDispatchAcquireResult.Completed ->
                return FreeResponseDispatchPreparation.AlreadyCompleted
            CurrentTutorFreeResponseDispatchAcquireResult.Busy ->
                return FreeResponseDispatchPreparation.InProgress
            CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed,
            CurrentTutorFreeResponseDispatchAcquireResult.NotAvailable,
            -> return FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
        val activeDispatch = ActiveFreeResponseDispatch(
            sessionId = lease.sessionId,
            presentationToken = lease.presentationToken,
            leaseToken = lease.leaseToken,
        )
        activeFreeResponseDispatches[action.actionToken] = activeDispatch
        return try {
            FreeResponseDispatchPreparation.Ready(
                duplicateClaim = duplicate,
                lease = lease,
                activeDispatch = activeDispatch,
                evidenceState = persistedContext.evidenceState,
                submission = buildCurrentTutorFreeResponseSubmission(
                    context = persistedContext,
                    lease = lease,
                    activeDispatch = activeDispatch,
                    isCurrent = {
                        activeFreeResponseDispatches[lease.actionToken] === activeDispatch
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { releaseFreeResponseLease(lease, activeDispatch) }
            throw cancelled
        } catch (_: Exception) {
            failFreeResponseLeaseClosed(lease, activeDispatch)
            FreeResponseDispatchPreparation.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
    }

    private suspend fun resolveFreeResponseDispatchContext(
        sessionId: String,
        presentationToken: String,
    ): CurrentFreeResponseDispatchContext? {
        val record = currentActiveRecord(sessionId, presentationToken) ?: return null
        val current = resolveCurrentPresentation(record) ?: return null
        if (current.output.plan.interactionDirective !is TutorInteractionDirective.FreeResponse) {
            return null
        }
        val authority = interactionAuthorities[record.presentationToken]
            ?.takeIf { prepared ->
                prepared.taskRequestFingerprint == current.task.requestFingerprint &&
                    prepared.evidenceRequestId == record.evidenceRequestId &&
                    prepared.evidenceKind == TutorEvidenceRequestKind.FREE_RESPONSE
            } ?: return null
        val material = resolveCurrentTutorLearningMaterial(
            learnerId = learnerId,
            verifiedMaterialAuthority = verifiedMaterialAuthority,
            current = current,
            kind = TutorEvidenceRequestKind.FREE_RESPONSE,
        )?.takeIf { value ->
            value.trustedAnswerInteraction == null &&
                value.currentStepAuthorityFingerprint() == authority.authorityFingerprint
        } ?: return null
        val evidenceState = currentEvidenceState(record)
            ?.takeUnless(CurrentTutorSessionEvidenceState::answerWasRevealed)
            ?: return null
        return CurrentFreeResponseDispatchContext(
            record = record,
            current = current,
            authority = authority,
            material = material,
            evidenceState = evidenceState,
        )
    }

    private suspend fun recoverPendingFreeResponseDispatch(sessionId: String) {
        if (freeResponseSubmission == null) return
        val ready = try {
            withSessionPolicyLock(sessionId) { prepareRecoveredFreeResponseDispatch(sessionId, null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        dispatchPreparedFreeResponse(ready)
    }

    private suspend fun prepareRecoveredFreeResponseDispatch(
        sessionId: String,
        actionToken: String?,
    ): FreeResponseDispatchPreparation.Ready? {
        val acquired = hostWork.acquireCurrentTutorFreeResponseDispatch(
            AcquireCurrentTutorFreeResponseDispatchCommand(
                learnerId = learnerId,
                sessionId = sessionId,
                actionToken = actionToken,
                leaseOwnerId = freeResponseDispatchOwnerId,
                leaseGenerationId = freeResponseDispatchGenerationId,
                leaseDurationMillis = FREE_RESPONSE_DISPATCH_LEASE_MILLIS,
                occurredAtEpochMillis = trustedNow(),
            ),
        ) as? CurrentTutorFreeResponseDispatchAcquireResult.Acquired ?: return null
        val lease = acquired.lease
        val context = resolveFreeResponseDispatchContext(
            sessionId = lease.sessionId,
            presentationToken = lease.presentationToken,
        )?.takeIf { current -> lease.matches(current.record, learnerId) }
        if (context == null) {
            failFreeResponseLeaseClosed(
                lease,
                ActiveFreeResponseDispatch(
                    sessionId = lease.sessionId,
                    presentationToken = lease.presentationToken,
                    leaseToken = lease.leaseToken,
                ),
            )
            return null
        }
        val activeDispatch = ActiveFreeResponseDispatch(
            sessionId = lease.sessionId,
            presentationToken = lease.presentationToken,
            leaseToken = lease.leaseToken,
        )
        activeFreeResponseDispatches[lease.actionToken] = activeDispatch
        return try {
            FreeResponseDispatchPreparation.Ready(
                duplicateClaim = true,
                lease = lease,
                activeDispatch = activeDispatch,
                evidenceState = context.evidenceState,
                submission = buildCurrentTutorFreeResponseSubmission(
                    context = context,
                    lease = lease,
                    activeDispatch = activeDispatch,
                    isCurrent = {
                        activeFreeResponseDispatches[lease.actionToken] === activeDispatch
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { releaseFreeResponseLease(lease, activeDispatch) }
            throw cancelled
        } catch (_: Exception) {
            failFreeResponseLeaseClosed(lease, activeDispatch)
            null
        }
    }

    override suspend fun prepareVisualTarget(
        action: TutorCurrentSessionVisualTargetPreparation,
    ): TutorCurrentSessionVisualTargetPreparationResult = withSessionPolicyLock(action.sessionId) {
        try {
        val record = currentActiveRecord(action.sessionId, action.presentationToken)
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        val current = resolveCurrentPresentation(record)
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        val directive = current.output.plan.interactionDirective as? TutorInteractionDirective.VisualTarget
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val prepared = interactionAuthorities[record.presentationToken]?.takeIf { authority ->
            authority.taskRequestFingerprint == current.task.requestFingerprint &&
                authority.evidenceRequestId == record.evidenceRequestId &&
                authority.evidenceKind == TutorEvidenceRequestKind.VISUAL_TARGET
        } ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
            TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
        )
        val material = resolveCurrentTutorLearningMaterial(
            learnerId = learnerId,
            verifiedMaterialAuthority = verifiedMaterialAuthority,
            current = current,
            kind = TutorEvidenceRequestKind.VISUAL_TARGET,
        )
            ?.takeIf { value ->
                value.currentStepAuthorityFingerprint() == prepared.authorityFingerprint
            }
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val answerInteraction = prepared.answerInteraction
            ?.takeIf { interaction ->
                interaction.responseForm == ReviewResponseForm.VISUAL_TARGET &&
                    interaction.matchesSource(
                        material.trustedAnswerInteraction,
                        record.presentationToken,
                    )
            }
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val evidenceState = currentEvidenceState(record)
            ?.takeUnless(CurrentTutorSessionEvidenceState::answerWasRevealed)
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val visual = resolveCurrentTutorVisual(modelTasks, current, action.hitProof)
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val step = visual.scene.steps.getOrNull(action.hitProof.stepIndex)
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val elementIds = visual.scene.elements.mapTo(hashSetOf()) { it.elementId }
        val stepTargets = buildSet {
            addAll(step.focusElementIds)
            step.primaryRelationElementId?.let(::add)
        }
        if (
            action.hitProof.selectedTargetId !in elementIds ||
            action.hitProof.selectedTargetId !in stepTargets
        ) {
            return@withSessionPolicyLock TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        val expiresAt = trustedNow() + VISUAL_TARGET_TOKEN_TTL_MILLIS
        val actionToken = CanonicalSha256("current-tutor-visual-action-v1")
            .field("secret", visualTargetSecret)
            .field("presentationToken", record.presentationToken)
            .field("evidenceRequestId", prepared.evidenceRequestId)
            .field("sceneFingerprint", visual.identity.sceneFingerprint)
            .field("stepIndex", action.hitProof.stepIndex)
            .field("targetId", action.hitProof.selectedTargetId)
            .field("authorityFingerprint", prepared.authorityFingerprint)
            .field("expiresAt", expiresAt)
            .finish()
        visualTargetGrants[actionToken] = PreparedVisualTargetGrant(
            sessionId = record.sessionId,
            presentationToken = record.presentationToken,
            taskRequestFingerprint = current.task.requestFingerprint,
            evidenceRequestId = prepared.evidenceRequestId,
            authorityFingerprint = prepared.authorityFingerprint,
            answerCapabilityFingerprint = answerInteraction.canonicalFingerprint,
            evidenceState = evidenceState,
            hitProof = action.hitProof,
            expiresAtEpochMillis = expiresAt,
        )
        TutorCurrentSessionVisualTargetPreparationResult.Ready(
            TutorCurrentSessionPreparedVisualTarget(actionToken),
        )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TutorCurrentSessionVisualTargetPreparationResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
    }

    override suspend fun submitVisualTarget(
        action: TutorCurrentSessionVisualTargetAction,
    ): TutorCurrentSessionVisualTargetActionResult = withSessionPolicyLock(action.sessionId) {
        try {
        val grant = visualTargetGrants[action.actionToken]
            ?.takeIf { value ->
                value.sessionId == action.sessionId &&
                    value.hitProof.selectedTargetId == action.targetId &&
                    value.expiresAtEpochMillis >= trustedNow()
            }
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        val record = currentActiveRecord(grant.sessionId, grant.presentationToken)
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        val current = resolveCurrentPresentation(record)
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        if (currentEvidenceState(record) != grant.evidenceState) {
            visualTargetGrants.remove(action.actionToken, grant)
            return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        val prepared = interactionAuthorities[record.presentationToken]?.takeIf { authority ->
            authority.taskRequestFingerprint == grant.taskRequestFingerprint &&
                authority.evidenceRequestId == grant.evidenceRequestId &&
                authority.evidenceKind == TutorEvidenceRequestKind.VISUAL_TARGET &&
                authority.authorityFingerprint == grant.authorityFingerprint
        } ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
            TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
        )
        val material = resolveCurrentTutorLearningMaterial(
            learnerId = learnerId,
            verifiedMaterialAuthority = verifiedMaterialAuthority,
            current = current,
            kind = TutorEvidenceRequestKind.VISUAL_TARGET,
        )
            ?.takeIf { value ->
                value.currentStepAuthorityFingerprint() == prepared.authorityFingerprint
            }
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        val answerInteraction = prepared.answerInteraction
            ?.takeIf { value ->
                value.responseForm == ReviewResponseForm.VISUAL_TARGET &&
                    value.canonicalFingerprint == grant.answerCapabilityFingerprint &&
                    value.matchesSource(
                        material.trustedAnswerInteraction,
                        record.presentationToken,
                    )
            }
            ?: return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        if (resolveCurrentTutorVisual(modelTasks, current, grant.hitProof) == null) {
            return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        if (!TutorVisualHitProofRegistry.claim(grant.hitProof)) {
            visualTargetGrants.remove(action.actionToken, grant)
            return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
        val fence = CurrentTutorTrustedAnswerSubmissionFence(
            sessionId = record.sessionId,
            presentationToken = record.presentationToken,
            evidenceRequestId = grant.evidenceRequestId,
            expectedEvidenceState = grant.evidenceState,
            occurredAtEpochMillis = trustedNow(),
            hitProof = grant.hitProof,
        )
        val submission = answerInteraction.submit(
            response = StudentTrustedReviewResponse.VisualTarget(action.targetId),
            fence = fence,
        )
        val receipt = when (submission) {
            is CurrentTutorTrustedAnswerSubmissionResult.Committed -> submission.receipt
            CurrentTutorTrustedAnswerSubmissionResult.Rejected -> {
                TutorVisualHitProofRegistry.release(grant.hitProof)
                return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                    TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
                )
            }
        }
        val persistedSubmission = currentEvidenceState(record)
        if (
            !receipt.matches(
                fence = fence,
                expectedContentBinding = checkNotNull(answerInteraction.exactContentBinding),
                expectedResponseForm = ReviewResponseForm.VISUAL_TARGET,
                currentState = persistedSubmission,
            )
        ) {
            TutorVisualHitProofRegistry.finalize(grant.hitProof)
            visualTargetGrants.remove(action.actionToken, grant)
            return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
        if (!TutorVisualHitProofRegistry.finalize(grant.hitProof)) {
            return@withSessionPolicyLock TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.NOT_CURRENT,
            )
        }
        visualTargetGrants.remove(action.actionToken, grant)
        TutorCurrentSessionVisualTargetActionResult.Answered(
            TutorCurrentSessionVisualTargetFeedback(
                feedbackMarkdown = TRUSTED_ANSWER_ACCEPTED_FEEDBACK,
            ),
        )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TutorCurrentSessionVisualTargetActionResult.Rejected(
                TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
            )
        }
    }

    override suspend fun activate(
        sessionId: String,
        modelTaskRequestId: String,
    ): TutorCurrentSessionHostResult {
        requireHostId(sessionId)
        requireHostId(modelTaskRequestId)
        return withSessionPolicyLock(sessionId) { guarded {
            activateVerified(
                sessionId = sessionId,
                modelTaskRequestId = modelTaskRequestId,
                restored = false,
                requiredWork = null,
            )
        } }
    }

    override suspend fun resume(sessionId: String): TutorCurrentSessionHostResult {
        requireHostId(sessionId)
        val result = withSessionPolicyLock(sessionId) { guarded {
            val work = hostWork.readCurrentTutorSessionHostWork(learnerId, sessionId)
                ?: return@guarded unavailable(TutorCurrentSessionHostBlocker.NOT_PREPARED)
            if (work.status == CurrentTutorSessionHostWorkStatus.REVOKED) {
                return@guarded TutorCurrentSessionHostResult.Revoked(work.toHostSnapshot())
            }
            activateVerified(
                sessionId = sessionId,
                modelTaskRequestId = work.modelTaskRequestId,
                restored = true,
                requiredWork = work,
            )
        } }
        if (result is TutorCurrentSessionHostResult.Ready) {
            recoverPendingFreeResponseDispatch(sessionId)
        }
        return result
    }

    override suspend fun revoke(
        sessionId: String,
        reason: TutorCurrentSessionRevocationReason,
    ): TutorCurrentSessionHostResult {
        requireHostId(sessionId)
        return withSessionPolicyLock(sessionId) { guarded {
            revokeLocked(sessionId, reason)
        } }
    }

    private suspend fun revokeLocked(
        sessionId: String,
        reason: TutorCurrentSessionRevocationReason,
    ): TutorCurrentSessionHostResult {
        val current = hostWork.readCurrentTutorSessionHostWork(learnerId, sessionId)
            ?: return unavailable(TutorCurrentSessionHostBlocker.NOT_PREPARED)
        if (current.status == CurrentTutorSessionHostWorkStatus.REVOKED) {
            return TutorCurrentSessionHostResult.Revoked(current.toHostSnapshot())
        }
        val result = hostWork.revokeCurrentTutorSessionHostWork(
            RevokeCurrentTutorSessionHostWorkCommand(
                learnerId = learnerId,
                sessionId = sessionId,
                expectedWorkId = current.workId,
                expectedStateVersion = current.stateVersion,
                expectedStateFingerprint = current.stateFingerprint,
                reason = reason.toDatabaseReason(),
                occurredAtEpochMillis = trustedNow(),
            ),
        )
        val revokedRecord = when (result.disposition) {
            CurrentTutorSessionHostWorkWriteDisposition.APPLIED,
            CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
            -> result.record
                ?.takeIf { record -> record.status == CurrentTutorSessionHostWorkStatus.REVOKED }
                ?: return unavailable(TutorCurrentSessionHostBlocker.SUPERSEDED)

            else -> return unavailable(TutorCurrentSessionHostBlocker.SUPERSEDED)
        }
        val hasPersistedStudentSubmission = currentEvidenceState(current)
            ?.let { evidenceState ->
                evidenceState.attemptOrdinal >= current.attemptOrdinal
            } == true
        if (!hasPersistedStudentSubmission) {
            current.evidenceRequestId?.let { requestId ->
                interactionWriter?.cancelEvidence(
                    CancelTutorEvidenceCommand(
                        sessionId = current.sessionId,
                        questionDocumentId = current.questionDocumentId,
                        revisionNumber = current.questionRevisionNumber,
                        evidenceRequestId = requestId,
                        occurredAtEpochMillis = trustedNow(),
                    ),
                )
            }
        }
        activationOwner.revokeLearningWrites(current.targetScopeId)
        interactionAuthorities.remove(current.presentationToken)
        visualTargetGrants.entries
            .filter { (_, grant) ->
                grant.sessionId == current.sessionId &&
                    grant.presentationToken == current.presentationToken
            }
            .forEach { (token, _) -> visualTargetGrants.remove(token) }
        invalidateFreeResponseState(current.sessionId)
        return TutorCurrentSessionHostResult.Revoked(revokedRecord.toHostSnapshot())
    }

    private suspend fun activateVerified(
        sessionId: String,
        modelTaskRequestId: String,
        restored: Boolean,
        requiredWork: CurrentTutorSessionHostWorkRecord?,
    ): TutorCurrentSessionHostResult {
        val session = questions.read(sessionId)
            ?.takeIf { candidate ->
                candidate.sessionId == sessionId &&
                    candidate.disposition !=
                    com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition.ENDED_WITHOUT_SAVE
            }
            ?: return unavailable(TutorCurrentSessionHostBlocker.QUESTION_NOT_CURRENT)
        val task = modelTasks.read(modelTaskRequestId)
            ?: return unavailable(TutorCurrentSessionHostBlocker.MODEL_RESULT_NOT_CURRENT)
        if (task.request.requestId != modelTaskRequestId) {
            return unavailable(TutorCurrentSessionHostBlocker.MODEL_RESULT_NOT_CURRENT)
        }
        val accepted = task.acceptedTutorPlan(session)
            ?: return unavailable(TutorCurrentSessionHostBlocker.MODEL_RESULT_NOT_CURRENT)
        val (input, initiallyConstrainedOutput, subject) = accepted
        val currentMode = modes.currentMode(sessionId)
        if (currentMode.mode != input.explanationMode || currentMode.modeVersion != input.modeVersion) {
            return unavailable(TutorCurrentSessionHostBlocker.MODEL_RESULT_NOT_CURRENT)
        }
        val writeAuthority = learningWriteAuthority.current(sessionId)
            ?.takeIf { authority ->
                authority.sessionId == sessionId &&
                    authority.allowed == input.allowLongTermLearningWrites &&
                    authority.permissionVersion == input.learningWritePermissionVersion
            }
            ?: return unavailable(TutorCurrentSessionHostBlocker.LEARNING_AUTHORITY_NOT_CURRENT)
        val policy = if (policyController == null) {
            TutorCurrentSessionPolicySnapshot(
                sessionId = sessionId,
                explanationMode = currentMode.mode,
                modeVersion = currentMode.modeVersion,
                learningWritesAllowed = writeAuthority.allowed,
                learningWritePermissionVersion = writeAuthority.permissionVersion,
            )
        } else {
            policyController.currentPolicy(sessionId)
                ?.takeIf { snapshot ->
                    snapshot.sessionId == sessionId &&
                        snapshot.explanationMode == currentMode.mode &&
                        snapshot.modeVersion == currentMode.modeVersion &&
                        snapshot.learningWritesAllowed == writeAuthority.allowed &&
                        snapshot.learningWritePermissionVersion == writeAuthority.permissionVersion
                }
                ?: return unavailable(TutorCurrentSessionHostBlocker.LEARNING_AUTHORITY_NOT_CURRENT)
        }
        val proposal = initiallyConstrainedOutput
            .constrainedForLearningWrites(writeAuthority.allowed)
        val proposedEvidenceKind = proposal.proposedEvidenceKind(input)
        var learningMaterial = if (
            writeAuthority.allowed && proposedEvidenceKind != null &&
            proposedEvidenceKind in SUPPORTED_INTERACTION_EVIDENCE_KINDS &&
            (
                proposedEvidenceKind != TutorEvidenceRequestKind.FREE_RESPONSE ||
                    freeResponseSubmission != null
                )
        ) {
            val query = CurrentTutorVerifiedMaterialQuery(
                learnerId = learnerId,
                session = session,
                subject = subject,
                task = task,
                input = input,
                output = proposal,
                currentStepKnowledgeAuthorityRequired = true,
            )
            verifiedMaterialAuthority.resolve(query)?.takeIf { material ->
                material.matchesSubject(subject) &&
                    material.isUniqueCurrentStepAuthority(query, proposedEvidenceKind)
            }
        } else {
            null
        }
        // A presentation that was activated without answer authority must not gain evidence merely
        // because a credential arrives later. A fresh host activation may create a fresh
        // presentation; process restoration preserves the authority state of the durable one.
        if (requiredWork != null && requiredWork.evidenceRequestId == null) {
            learningMaterial = null
        }
        val output = proposal.constrainedForCurrentHostInteraction(
            authorizedEvidenceKind = proposedEvidenceKind.takeIf { learningMaterial != null },
        )
        if (output.proposedEvidenceKind(input) != proposedEvidenceKind) learningMaterial = null
        val presentationMaterial = verifiedMaterialAuthority.resolve(
            CurrentTutorVerifiedMaterialQuery(
                learnerId = learnerId,
                session = session,
                subject = subject,
                task = task,
                input = input,
                output = output,
                currentStepKnowledgeAuthorityRequired = false,
            ),
        )?.takeIf { value ->
            value.matchesSubject(subject)
        }
            ?: return unavailable(TutorCurrentSessionHostBlocker.VERIFIED_MATERIAL_UNAVAILABLE)
        val preparedLearningAuthority = learningMaterial?.let { candidate ->
            prepareLearningAuthority(
                session = session,
                task = task,
                input = input,
                output = output,
                subject = subject,
                material = candidate,
                interactionKind = checkNotNull(proposedEvidenceKind),
            )
        }
        val material = if (preparedLearningAuthority?.evidence != null) {
            checkNotNull(learningMaterial)
        } else {
            presentationMaterial.withoutLearningEvidence()
        }
        val authority = preparedLearningAuthority ?: prepareCurrentTutorPresentationOnlyAuthority(
            learnerId = learnerId,
            session = session,
            task = task,
            input = input,
            output = output,
            subject = subject,
            material = material,
            nowEpochMillis = trustedNow(),
        )
        val activation = buildCurrentTutorSessionActivation(
            learnerId = learnerId,
            session = session,
            task = task,
            input = input,
            output = output,
            subject = subject,
            writeAuthority = writeAuthority,
            material = material,
            authority = authority,
            nowEpochMillis = trustedNow(),
        )
        if (
            requiredWork != null &&
            (!requiredWork.matchesActivation(activation, authority.directiveFingerprint, policy) ||
                requiredWork.modelTaskRequestFingerprint != task.requestFingerprint)
        ) {
            return unavailable(TutorCurrentSessionHostBlocker.SUPERSEDED)
        }
        val staged = hostWork.stageCurrentTutorSessionHostWork(
            activation.toStageCommand(
                task = task,
                session = session,
                input = input,
                output = output,
                writeAuthority = writeAuthority,
                policy = policy,
                durablePolicyRequired = policyController != null,
                authority = authority,
                existing = requiredWork ?: hostWork.readCurrentTutorSessionHostWork(
                    learnerId,
                    sessionId,
                ),
                occurredAtEpochMillis = trustedNow(),
            ),
        )
        val stagedRecord = when (staged.disposition) {
            CurrentTutorSessionHostWorkWriteDisposition.APPLIED,
            CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
            -> staged.record
            else -> null
        } ?: return unavailable(TutorCurrentSessionHostBlocker.SUPERSEDED)
        if (!stagedRecord.matchesActivation(activation, authority.directiveFingerprint, policy)) {
            return unavailable(TutorCurrentSessionHostBlocker.SUPERSEDED)
        }
        when (activationOwner.activate(activation)) {
            CurrentTutorSessionActivationResult.ACTIVE,
            CurrentTutorSessionActivationResult.DUPLICATE,
            -> Unit
            CurrentTutorSessionActivationResult.OWNER_CLOSED ->
                return unavailable(TutorCurrentSessionHostBlocker.OWNER_CLOSED)
            CurrentTutorSessionActivationResult.STALE ->
                return unavailable(TutorCurrentSessionHostBlocker.SUPERSEDED)
            CurrentTutorSessionActivationResult.AUTHORITY_MISMATCH ->
                return unavailable(TutorCurrentSessionHostBlocker.LEARNING_AUTHORITY_NOT_CURRENT)
        }
        val marked = hostWork.markCurrentTutorSessionHostWorkActive(
            MarkCurrentTutorSessionHostWorkActiveCommand(
                learnerId = learnerId,
                sessionId = sessionId,
                expectedWorkId = stagedRecord.workId,
                expectedStateVersion = stagedRecord.stateVersion,
                expectedStateFingerprint = stagedRecord.stateFingerprint,
                expectedTargetScopeId = activation.scopeId,
                expectedTargetActivationFingerprint = activation.activationFingerprint,
                expectedPolicyStateFingerprint = if (policyController == null) {
                    null
                } else {
                    policy.durableStateFingerprint(learnerId)
                },
                occurredAtEpochMillis = trustedNow(),
            ),
        )
        val active = when (marked.disposition) {
            CurrentTutorSessionHostWorkWriteDisposition.APPLIED,
            CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE,
            -> marked.record
            else -> null
        }?.takeIf { record ->
            record.status == CurrentTutorSessionHostWorkStatus.ACTIVE &&
                record.matchesActivation(activation, authority.directiveFingerprint, policy)
        } ?: return unavailable(TutorCurrentSessionHostBlocker.SUPERSEDED)
        if (authority.evidence != null) {
            interactionAuthorities[active.presentationToken] = PreparedInteractionAuthority(
                taskRequestFingerprint = task.requestFingerprint,
                evidenceRequestId = authority.evidence.evidenceRequestId,
                evidenceKind = authority.evidence.kind,
                authorityFingerprint = material.currentStepAuthorityFingerprint(),
                answerInteraction = material.trustedAnswerInteraction
                    ?.bindToPresentation(active.presentationToken),
            )
        } else {
            interactionAuthorities.remove(active.presentationToken)
        }
        retainOnlyCurrentFreeResponseState(
            record = active,
            task = task,
            authority = interactionAuthorities[active.presentationToken],
        )
        return TutorCurrentSessionHostResult.Ready(
            snapshot = active.toHostSnapshot(),
            restored = restored,
        )
    }

    private suspend fun prepareLearningAuthority(
        session: ConfirmedTutorSession,
        task: ModelTaskSnapshot,
        input: TutorPlanInput,
        output: TutorPlanOutput,
        subject: SubjectKind,
        material: CurrentTutorVerifiedMaterial,
        interactionKind: TutorEvidenceRequestKind,
    ): PreparedTutorLearningAuthority? {
        val conversationId = authorityConversationId(session.sessionId)
        val generation = 1L
        val conversation = when (
            val opened = learningAuthorityStore.openConversation(
                learnerId = learnerId,
                conversationId = conversationId,
                generation = generation,
            )
        ) {
            null -> learningAuthorityStore.createConversation(
                CreateTutorConversationCommand(
                    learnerScopeId = learnerId,
                    conversationId = conversationId,
                    conversationGeneration = generation,
                    clientIdempotencyKey = "current-tutor-conversation:" +
                        CanonicalSha256("current-tutor-conversation-create-key-v1")
                            .field("sessionId", session.sessionId)
                            .finish(),
                    payloadFingerprint = CanonicalSha256("current-tutor-conversation-payload-v1")
                        .field("learnerId", learnerId)
                        .field("sessionId", session.sessionId)
                        .field("generation", generation)
                        .finish(),
                    occurredAtEpochMillis = maxOf(session.createdAtEpochMillis, trustedNow()),
                ),
            )
            else -> opened
        }
        if (
            conversation.status != TutorConversationStatus.ACTIVE ||
            conversation.conversationId != conversationId ||
            conversation.learnerScopeId != learnerId ||
            conversation.generation != generation
        ) return null
        val directiveFingerprint = directiveFingerprint(input, output)
        val requestVersion = tutorRequestVersion(output)
        val turnReceiptId = authorityTurnReceiptId(task.requestFingerprint)
        val turn = when (val opened = learningAuthorityStore.openTurn(learnerId, turnReceiptId)) {
            null -> {
                if (conversation.stateVersion >= Int.MAX_VALUE.toLong()) return null
                learningAuthorityStore.allocateTurn(
                    AllocateTutorTurnCommand(
                        learnerScopeId = learnerId,
                        conversationId = conversationId,
                        conversationGeneration = generation,
                        expectedConversationStateVersion = conversation.stateVersion,
                        expectedTurnOrdinal = conversation.stateVersion.toInt() + 1,
                        turnReceiptId = turnReceiptId,
                        subject = subject,
                        problemAnchorId = material.problemAnchorId,
                        requestVersion = requestVersion,
                        modeVersion = input.modeVersion,
                        mode = input.explanationMode,
                        directiveFingerprint = directiveFingerprint,
                        studentMessageFingerprint =
                            OpenResponseEvaluationTaskFingerprints.question(input.questionDocument),
                        studentMessageSummary = session.title.take(TutorTurnReceipt.MAX_SUMMARY_CHARS),
                        clientIdempotencyKey = "current-tutor-turn:$turnReceiptId",
                        payloadFingerprint = CanonicalSha256("current-tutor-turn-payload-v1")
                            .field("taskRequestFingerprint", task.requestFingerprint)
                            .field("conversationId", conversationId)
                            .field("subject", subject.name)
                            .field("problemAnchorId", material.problemAnchorId)
                            .field("requestVersion", requestVersion)
                            .field("modeVersion", input.modeVersion)
                            .field("directiveFingerprint", directiveFingerprint)
                            .finish(),
                        occurredAtEpochMillis = trustedNow(),
                    ),
                )
            }
            else -> opened
        }
        if (
            !turn.matches(
                conversationId = conversationId,
                generation = generation,
                subject = subject,
                problemAnchorId = material.problemAnchorId,
                requestVersion = requestVersion,
                modeVersion = input.modeVersion,
                directiveFingerprint = directiveFingerprint,
            )
        ) return null
        val evidence = run {
            val evidenceRequestId = authorityEvidenceRequestId(task.requestFingerprint)
            val prepared = learningAuthorityStore.prepareEvidenceRequest(
                PrepareTutorEvidenceCommand(
                    learnerScopeId = learnerId,
                    conversationId = conversationId,
                    conversationGeneration = generation,
                    expectedConversationStateVersion = turn.conversationStateVersion,
                    turnReceiptId = turn.turnReceiptId,
                    turnOrdinal = turn.turnOrdinal,
                    subject = subject,
                    problemAnchorId = material.problemAnchorId,
                    evidenceRequestId = evidenceRequestId,
                    kind = interactionKind,
                    requestVersion = requestVersion,
                    modeVersion = input.modeVersion,
                    mode = input.explanationMode,
                    directiveFingerprint = directiveFingerprint,
                    clientIdempotencyKey = "current-tutor-evidence:$evidenceRequestId",
                    payloadFingerprint = CanonicalSha256("current-tutor-evidence-payload-v1")
                        .field("taskRequestFingerprint", task.requestFingerprint)
                        .field("turnReceiptId", turn.turnReceiptId)
                        .field("kind", interactionKind.name)
                        .field("directiveFingerprint", directiveFingerprint)
                        .finish(),
                    occurredAtEpochMillis = trustedNow(),
                ),
            )
            prepared.takeIf { request ->
                request.status == TutorEvidenceRequestStatus.PENDING &&
                    request.matches(turn, interactionKind, directiveFingerprint)
            } ?: return null
        }
        return PreparedTutorLearningAuthority(
            conversation = conversation.copy(stateVersion = turn.conversationStateVersion),
            turn = turn,
            evidence = evidence,
            directiveFingerprint = directiveFingerprint,
            requestVersion = requestVersion,
        )
    }

    /**
     * Builds an owner-local namespace only. It never calls the learning-memory store and never
     * creates an evidence request. This is also used while the user permits learning writes but
     * the Host has not established a current-step proof subset.
     */
    private suspend fun resolveCurrentPresentation(
        record: CurrentTutorSessionHostWorkRecord,
    ): ResolvedCurrentTutorPresentation? {
        if (record.learnerId != learnerId || record.status != CurrentTutorSessionHostWorkStatus.ACTIVE) {
            return null
        }
        val session = questions.read(record.sessionId)
            ?.takeIf { current ->
                current.sessionId == record.sessionId &&
                    current.draftRevisionNumber == record.questionRevisionNumber &&
                    current.disposition !=
                    com.tingyun.smartmistakebook.core.domain.TutorSessionDisposition.ENDED_WITHOUT_SAVE
            }
            ?: return null
        val task = modelTasks.read(record.modelTaskRequestId)
            ?.takeIf { current ->
                current.request.requestId == record.modelTaskRequestId &&
                    current.requestFingerprint == record.modelTaskRequestFingerprint
            }
            ?: return null
        val accepted = task.acceptedTutorPlan(session) ?: return null
        val input = accepted.input
        val mode = modes.currentMode(record.sessionId)
        val writeAuthority = learningWriteAuthority.current(record.sessionId) ?: return null
        val durablePolicy = policyController?.currentPolicy(record.sessionId)
        if (
            policyController != null &&
            (
                durablePolicy == null ||
                    durablePolicy.sessionId != record.sessionId ||
                    durablePolicy.explanationMode != record.explanationMode ||
                    durablePolicy.modeVersion != record.modeVersion ||
                    durablePolicy.learningWritesAllowed != record.learningWritesAllowed ||
                    durablePolicy.learningWritePermissionVersion !=
                    record.learningWritePermissionVersion ||
                    durablePolicy.visualIntent != record.visualIntent ||
                    durablePolicy.visualIntentVersion != record.visualIntentVersion
                )
        ) return null
        if (
            mode.mode != input.explanationMode ||
            mode.modeVersion != input.modeVersion ||
            writeAuthority.sessionId != record.sessionId ||
            writeAuthority.allowed != input.allowLongTermLearningWrites ||
            writeAuthority.permissionVersion != input.learningWritePermissionVersion ||
            record.explanationMode != mode.mode ||
            record.modeVersion != mode.modeVersion ||
            record.learningWritesAllowed != writeAuthority.allowed ||
            record.learningWritePermissionVersion != writeAuthority.permissionVersion
        ) return null
        val output = accepted.output
            .constrainedForLearningWrites(writeAuthority.allowed)
            .constrainedForCurrentHostInteraction(
                authorizedEvidenceKind = record.pendingInteractionKind,
            )
        val proposedEvidenceKind = output.proposedEvidenceKind(input)
        if (
            record.questionDocumentId != input.questionDocument.id ||
            record.subject != accepted.subject ||
            record.cycleOrdinal != output.cycleOrdinal ||
            record.turnOrdinal != output.turnOrdinal ||
            record.authorityDirectiveFingerprint != directiveFingerprint(input, output) ||
            record.constrainedTutorContentFingerprint !=
            constrainedPresentationFingerprint(task, input, output) ||
            (record.pendingInteractionKind != null &&
                record.pendingInteractionKind != proposedEvidenceKind)
        ) return null
        val token = record.presentationToken.takeIf(String::isNotBlank) ?: return null
        val freeResponseAction = issueCurrentFreeResponseToken(
            record = record,
            task = task,
            output = output,
        )
        val hint = output.toCurrentHintPresentation(
            record = record,
            evidenceState = currentEvidenceState(record),
        )
        return ResolvedCurrentTutorPresentation(
            session = session,
            task = task,
            input = input,
            output = output,
            subject = accepted.subject,
            presentation = output.toUiPresentation(
                sessionId = record.sessionId,
                presentationToken = token,
                explanationMode = record.explanationMode,
                learningWritesAllowed = writeAuthority.allowed,
                visualIntent = record.visualIntent,
                visualIntentVersion = record.visualIntentVersion,
                pendingInteractionKind = record.pendingInteractionKind,
                freeResponseActionToken = freeResponseAction?.actionToken,
                freeResponseSubmissionStatus = freeResponseAction?.submissionStatus,
                hint = hint,
            ),
        )
    }

    private suspend fun issueCurrentFreeResponseToken(
        record: CurrentTutorSessionHostWorkRecord,
        task: ModelTaskSnapshot,
        output: TutorPlanOutput,
    ): IssuedFreeResponseAction? {
        if (
            record.pendingInteractionKind != TutorEvidenceRequestKind.FREE_RESPONSE ||
            output.plan.interactionDirective !is TutorInteractionDirective.FreeResponse ||
            !record.learningWritesAllowed
        ) {
            freeResponseTokensByPresentation.remove(record.presentationToken)?.let { oldToken ->
                freeResponseGrants.remove(oldToken)
            }
            return null
        }
        val authority = interactionAuthorities[record.presentationToken]
            ?.takeIf { prepared ->
                prepared.taskRequestFingerprint == task.requestFingerprint &&
                    prepared.evidenceRequestId == record.evidenceRequestId &&
                    prepared.evidenceKind == TutorEvidenceRequestKind.FREE_RESPONSE
            }
            ?: return null
        freeResponseTokensByPresentation[record.presentationToken]?.let { existingToken ->
            freeResponseGrants[existingToken]
                ?.takeIf { grant ->
                    grant.matches(record, task, authority)
                }
                ?.let { grant ->
                    val state = readFreeResponseDispatchState(record, authority, existingToken)
                    if (
                        grant.expiresAtEpochMillis > trustedNow() ||
                        state != CurrentTutorFreeResponseDispatchState.NOT_CLAIMED
                    ) {
                        return IssuedFreeResponseAction(existingToken, state.toUiStatus())
                    }
                }
            freeResponseGrants.remove(existingToken)
            freeResponseTokensByPresentation.remove(record.presentationToken, existingToken)
        }
        val expiresAt = record.updatedAtEpochMillis + FREE_RESPONSE_TOKEN_TTL_MILLIS
        val actionToken = CanonicalSha256("current-tutor-free-response-action-v1")
            .field("sessionId", record.sessionId)
            .field("presentationToken", record.presentationToken)
            .field("taskRequestFingerprint", task.requestFingerprint)
            .field("evidenceRequestId", authority.evidenceRequestId)
            .field("authorityFingerprint", authority.authorityFingerprint)
            .field("modeVersion", record.modeVersion)
            .field("learningWritePermissionVersion", record.learningWritePermissionVersion)
            .field("questionRevisionNumber", record.questionRevisionNumber)
            .field("cycleOrdinal", record.cycleOrdinal)
            .field("turnOrdinal", record.turnOrdinal)
            .field("requestVersion", record.requestVersion)
            .field("expiresAt", expiresAt)
            .finish()
        val dispatchState = hostWork.readCurrentTutorFreeResponseDispatchState(
            CurrentTutorFreeResponseActionClaimQuery(
                learnerId = learnerId,
                sessionId = record.sessionId,
                expectedWorkId = record.workId,
                expectedWorkStateVersion = record.stateVersion,
                expectedWorkStateFingerprint = record.stateFingerprint,
                presentationToken = record.presentationToken,
                evidenceRequestId = authority.evidenceRequestId,
                actionToken = actionToken,
            ),
        )
        if (
            expiresAt <= trustedNow() &&
            dispatchState == CurrentTutorFreeResponseDispatchState.NOT_CLAIMED
        ) return null
        val grant = PreparedFreeResponseGrant(
            sessionId = record.sessionId,
            presentationToken = record.presentationToken,
            taskRequestFingerprint = task.requestFingerprint,
            evidenceRequestId = authority.evidenceRequestId,
            authorityFingerprint = authority.authorityFingerprint,
            modeVersion = record.modeVersion,
            learningWritePermissionVersion = record.learningWritePermissionVersion,
            questionRevisionNumber = record.questionRevisionNumber,
            expiresAtEpochMillis = expiresAt,
        )
        freeResponseGrants[actionToken] = grant
        freeResponseTokensByPresentation[record.presentationToken] = actionToken
        return IssuedFreeResponseAction(actionToken, dispatchState.toUiStatus())
    }

    private suspend fun readFreeResponseDispatchState(
        record: CurrentTutorSessionHostWorkRecord,
        authority: PreparedInteractionAuthority,
        actionToken: String,
    ): CurrentTutorFreeResponseDispatchState =
        hostWork.readCurrentTutorFreeResponseDispatchState(
            CurrentTutorFreeResponseActionClaimQuery(
                learnerId = learnerId,
                sessionId = record.sessionId,
                expectedWorkId = record.workId,
                expectedWorkStateVersion = record.stateVersion,
                expectedWorkStateFingerprint = record.stateFingerprint,
                presentationToken = record.presentationToken,
                evidenceRequestId = authority.evidenceRequestId,
                actionToken = actionToken,
            ),
        )

    private fun retainOnlyCurrentFreeResponseState(
        record: CurrentTutorSessionHostWorkRecord,
        task: ModelTaskSnapshot,
        authority: PreparedInteractionAuthority?,
    ) {
        freeResponseGrants.entries
            .filter { (_, grant) ->
                grant.sessionId == record.sessionId &&
                    (authority == null || !grant.matches(record, task, authority))
            }
            .forEach { (token, grant) ->
                freeResponseGrants.remove(token, grant)
                freeResponseTokensByPresentation.remove(grant.presentationToken, token)
            }
        activeFreeResponseDispatches.entries
            .filter { (_, dispatch) ->
                dispatch.sessionId == record.sessionId &&
                    (
                        authority == null ||
                            dispatch.presentationToken != record.presentationToken
                    )
            }
            .forEach { (token, dispatch) ->
                activeFreeResponseDispatches.remove(token, dispatch)
                freeResponseTokensByPresentation.remove(dispatch.presentationToken, token)
            }
    }

    private fun invalidateFreeResponseState(sessionId: String) {
        freeResponseGrants.entries
            .filter { (_, grant) -> grant.sessionId == sessionId }
            .forEach { (token, grant) ->
                freeResponseGrants.remove(token, grant)
                freeResponseTokensByPresentation.remove(grant.presentationToken, token)
            }
        activeFreeResponseDispatches.entries
            .filter { (_, dispatch) -> dispatch.sessionId == sessionId }
            .forEach { (token, dispatch) ->
                activeFreeResponseDispatches.remove(token, dispatch)
                freeResponseTokensByPresentation.remove(dispatch.presentationToken, token)
            }
    }

    private suspend fun currentActiveRecord(
        sessionId: String,
        presentationToken: String,
    ): CurrentTutorSessionHostWorkRecord? = runCatching {
        hostWork.readCurrentTutorSessionHostWork(learnerId, sessionId)
    }.getOrNull()?.takeIf { record ->
        record.status == CurrentTutorSessionHostWorkStatus.ACTIVE &&
            record.presentationToken == presentationToken
    }

    private fun trustedNow(): Long = nowEpochMillis.getAsLong().coerceAtLeast(0L)

    private suspend fun <T> withSessionPolicyLock(
        sessionId: String,
        block: suspend () -> T,
    ): T = sessionPolicyLocks.computeIfAbsent(sessionId) { Mutex() }.withLock { block() }

    private suspend inline fun guarded(
        crossinline block: suspend () -> TutorCurrentSessionHostResult,
    ): TutorCurrentSessionHostResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        unavailable(TutorCurrentSessionHostBlocker.LEARNING_AUTHORITY_NOT_CURRENT)
    }

    private suspend inline fun guardedPolicy(
        crossinline block: suspend () -> TutorCurrentSessionPolicyResult,
    ): TutorCurrentSessionPolicyResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        TutorCurrentSessionPolicyResult.Unavailable
    }

    private suspend inline fun guardedAction(
        crossinline block: suspend () -> TutorCurrentSessionChoiceActionResult,
    ): TutorCurrentSessionChoiceActionResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        rejectedAction(TutorCurrentSessionActionBlocker.NOT_CURRENT)
    }

    private suspend fun currentEvidenceState(
        record: CurrentTutorSessionHostWorkRecord,
    ): CurrentTutorSessionEvidenceState? =
        activationOwner.currentEvidenceState(record.sessionId)
            ?.takeIf { state -> state.matches(record) }

    private suspend fun freeResponseEvidenceStillCurrent(
        ready: FreeResponseDispatchPreparation.Ready,
    ): Boolean {
        val record = currentActiveRecord(
            sessionId = ready.lease.sessionId,
            presentationToken = ready.lease.presentationToken,
        ) ?: return false
        val current = currentEvidenceState(record) ?: return false
        return current == ready.evidenceState || current.isSameEvidenceFactsAfterOneClaim(
            ready.evidenceState,
        )
    }
}

internal object CurrentTutorSessionHostCoordinatorFactory {
    fun create(
        learnerId: String,
        captureWorkflow: CaptureWorkflowRepository,
        modelTasks: ModelTaskRepository,
        settings: TutorSettingsRepository,
        learningMemory: TutorLearningMemoryRepository,
        hostWork: CurrentTutorSessionHostWorkDatabasePort,
        learningWriteAuthority: CurrentTutorLearningWriteAuthority,
        verifiedMaterialAuthority: CurrentTutorVerifiedMaterialAuthority,
        owner: CurrentTutorSessionProductionOwner,
        nowEpochMillis: LongSupplier = LongSupplier { System.currentTimeMillis() },
    ): TutorCurrentSessionHostPort = CurrentTutorSessionHostCoordinator(
        learnerId = learnerId,
        questions = CurrentTutorQuestionSource { sessionId ->
            captureWorkflow.readTutorSession(sessionId)
        },
        modelTasks = CurrentTutorModelTaskSource { requestId ->
            modelTasks.observe(requestId).first()
        },
        modes = CurrentTutorModeSource { _ -> settings.currentModeSnapshot() },
        learningAuthorityStore = RepositoryCurrentTutorLearningAuthorityStore(learningMemory),
        hostWork = hostWork,
        learningWriteAuthority = learningWriteAuthority,
        verifiedMaterialAuthority = verifiedMaterialAuthority,
        activationOwner = object : CurrentTutorSessionActivationOwner {
            override suspend fun activate(
                activation: CurrentTutorSessionActivation,
            ): CurrentTutorSessionActivationResult = owner.activate(activation)

            override suspend fun revokeLearningWrites(scopeId: String) {
                owner.revokeLearningWrites(scopeId)
            }

            override suspend fun currentEvidenceState(
                sessionId: String,
            ): CurrentTutorSessionEvidenceState? = owner.currentEvidenceState(sessionId)

            override suspend fun recordHintShown(
                commit: CurrentTutorHintShownCommit,
            ): CurrentTutorHintShownCommitResult = owner.recordHintShown(commit)
        },
        nowEpochMillis = nowEpochMillis,
    )

    fun createProduction(
        learnerId: String,
        questions: CurrentTutorQuestionSource,
        savedMistakePreparer: CurrentTutorSavedMistakeQuestionPreparer? = null,
        modelTasks: ModelTaskRepository,
        learningMemory: TutorLearningMemoryRepository,
        hostWork: CurrentTutorSessionHostWorkDatabasePort,
        policyOwner: ProductionCurrentTutorPolicyOwner,
        verifiedMaterialAuthority: CurrentTutorVerifiedMaterialAuthority,
        owner: CurrentTutorSessionProductionOwner,
        interactionWriter: TutorInteractionRepository,
        freeResponseSubmission: CurrentTutorFreeResponseSubmissionPort,
        nowEpochMillis: LongSupplier = LongSupplier { System.currentTimeMillis() },
    ): TutorCurrentSessionHostPort = CurrentTutorSessionHostCoordinator(
        learnerId = learnerId,
        questions = questions,
        modelTasks = CurrentTutorModelTaskSource { requestId ->
            modelTasks.observe(requestId).first()
        },
        modes = policyOwner,
        learningAuthorityStore = RepositoryCurrentTutorLearningAuthorityStore(learningMemory),
        hostWork = hostWork,
        learningWriteAuthority = policyOwner,
        verifiedMaterialAuthority = verifiedMaterialAuthority,
        activationOwner = object : CurrentTutorSessionActivationOwner {
            override suspend fun activate(
                activation: CurrentTutorSessionActivation,
            ): CurrentTutorSessionActivationResult = owner.activate(activation)

            override suspend fun revokeLearningWrites(scopeId: String) {
                owner.revokeLearningWrites(scopeId)
            }

            override suspend fun currentEvidenceState(
                sessionId: String,
            ): CurrentTutorSessionEvidenceState? = owner.currentEvidenceState(sessionId)

            override suspend fun recordHintShown(
                commit: CurrentTutorHintShownCommit,
            ): CurrentTutorHintShownCommitResult = owner.recordHintShown(commit)
        },
        interactionWriter = interactionWriter,
        freeResponseSubmission = freeResponseSubmission,
        policyController = policyOwner,
        savedMistakePreparer = savedMistakePreparer,
        nowEpochMillis = nowEpochMillis,
    )
}