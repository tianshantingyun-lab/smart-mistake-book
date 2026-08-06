package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkRecord
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkRevocationReason
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkStatus
import com.tingyun.smartmistakebook.core.database.StageCurrentTutorSessionHostWorkCommand
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchLease
import com.tingyun.smartmistakebook.core.database.CurrentTutorFreeResponseDispatchState
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorFreeResponseSubmission
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseContextRequest
import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.OpenTutorConversationResult
import com.tingyun.smartmistakebook.core.domain.OpenTutorTurnResult
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionActionBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoice
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionChoiceActionResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionFreeResponseStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostBlocker
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPhase
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostResult
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHint
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHintStatus
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionInteraction
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicySnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPresentation
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionRevocationReason
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionText
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionTextKind
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorConversationStatus
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualPresentationIdentity
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.locallyConstrainedFor
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal class RepositoryCurrentTutorLearningAuthorityStore(
    private val repository: TutorLearningMemoryRepository,
) : CurrentTutorLearningAuthorityStore {
    override suspend fun openConversation(
        learnerId: String,
        conversationId: String,
        generation: Long,
    ): TutorConversation? = when (
        val opened = repository.openConversation(
            OpenTutorConversationCommand(learnerId, conversationId, generation),
        )
    ) {
        is OpenTutorConversationResult.Opened -> opened.conversation
        OpenTutorConversationResult.NotFound -> null
    }

    override suspend fun createConversation(
        command: CreateTutorConversationCommand,
    ): TutorConversation = repository.createConversation(command).conversation

    override suspend fun openTurn(
        learnerId: String,
        turnReceiptId: String,
    ): TutorTurnReceipt? = when (val opened = repository.openTurn(learnerId, turnReceiptId)) {
        is OpenTutorTurnResult.Found -> opened.receipt
        OpenTutorTurnResult.NotFound -> null
    }

    override suspend fun allocateTurn(command: AllocateTutorTurnCommand): TutorTurnReceipt =
        repository.allocateTurn(command).receipt

    override suspend fun prepareEvidenceRequest(
        command: PrepareTutorEvidenceCommand,
    ): TutorEvidenceRequest = repository.prepareEvidenceRequest(command).request
}

internal data class AcceptedTutorPlan(
    val input: TutorPlanInput,
    val output: TutorPlanOutput,
    val subject: SubjectKind,
)

internal data class ResolvedCurrentTutorPresentation(
    val session: ConfirmedTutorSession,
    val task: ModelTaskSnapshot,
    val input: TutorPlanInput,
    val output: TutorPlanOutput,
    val subject: SubjectKind,
    val presentation: TutorCurrentSessionPresentation,
)

internal sealed interface FreeResponseDispatchPreparation {
    data class Ready(
        val duplicateClaim: Boolean,
        val lease: CurrentTutorFreeResponseDispatchLease,
        val activeDispatch: ActiveFreeResponseDispatch,
        val evidenceState: CurrentTutorSessionEvidenceState,
        val submission: CurrentTutorFreeResponseSubmission,
    ) : FreeResponseDispatchPreparation

    data object AlreadyCompleted : FreeResponseDispatchPreparation

    data object InProgress : FreeResponseDispatchPreparation

    data class Rejected(
        val blocker: TutorCurrentSessionActionBlocker,
    ) : FreeResponseDispatchPreparation
}

internal data class CurrentFreeResponseDispatchContext(
    val record: CurrentTutorSessionHostWorkRecord,
    val current: ResolvedCurrentTutorPresentation,
    val authority: PreparedInteractionAuthority,
    val material: CurrentTutorVerifiedMaterial,
    val evidenceState: CurrentTutorSessionEvidenceState,
)

internal data class ActiveFreeResponseDispatch(
    val sessionId: String,
    val presentationToken: String,
    val leaseToken: String,
)

internal fun CurrentTutorFreeResponseDispatchLease.matches(
    record: CurrentTutorSessionHostWorkRecord,
    expectedLearnerId: String,
): Boolean =
    learnerId == expectedLearnerId &&
        sessionId == record.sessionId &&
        workId == record.workId &&
        workStateVersion == record.stateVersion &&
        workStateFingerprint == record.stateFingerprint &&
        presentationToken == record.presentationToken &&
        evidenceRequestId == record.evidenceRequestId

internal data class PreparedInteractionAuthority(
    val taskRequestFingerprint: String,
    val evidenceRequestId: String,
    val evidenceKind: TutorEvidenceRequestKind,
    val authorityFingerprint: String,
    val answerInteraction: CurrentTutorPreparedAnswerInteraction?,
)

internal data class PreparedVisualTargetGrant(
    val sessionId: String,
    val presentationToken: String,
    val taskRequestFingerprint: String,
    val evidenceRequestId: String,
    val authorityFingerprint: String,
    val answerCapabilityFingerprint: String,
    val evidenceState: CurrentTutorSessionEvidenceState,
    val hitProof: TutorVisualHitProof,
    val expiresAtEpochMillis: Long,
)

internal data class PreparedFreeResponseGrant(
    val sessionId: String,
    val presentationToken: String,
    val taskRequestFingerprint: String,
    val evidenceRequestId: String,
    val authorityFingerprint: String,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val questionRevisionNumber: Int,
    val expiresAtEpochMillis: Long,
) {
    fun matches(
        record: CurrentTutorSessionHostWorkRecord,
        task: ModelTaskSnapshot,
        authority: PreparedInteractionAuthority,
    ): Boolean =
        sessionId == record.sessionId &&
            presentationToken == record.presentationToken &&
            taskRequestFingerprint == task.requestFingerprint &&
            evidenceRequestId == record.evidenceRequestId &&
            authorityFingerprint == authority.authorityFingerprint &&
            modeVersion == record.modeVersion &&
            learningWritePermissionVersion == record.learningWritePermissionVersion &&
            questionRevisionNumber == record.questionRevisionNumber
}

internal data class IssuedFreeResponseAction(
    val actionToken: String,
    val submissionStatus: TutorCurrentSessionFreeResponseStatus,
)

internal fun CurrentTutorFreeResponseDispatchState.toUiStatus():
    TutorCurrentSessionFreeResponseStatus = when (this) {
    CurrentTutorFreeResponseDispatchState.NOT_CLAIMED ->
        TutorCurrentSessionFreeResponseStatus.READY
    CurrentTutorFreeResponseDispatchState.NEEDS_DISPATCH ->
        TutorCurrentSessionFreeResponseStatus.RETRY_AVAILABLE
    CurrentTutorFreeResponseDispatchState.IN_FLIGHT ->
        TutorCurrentSessionFreeResponseStatus.SENDING
    CurrentTutorFreeResponseDispatchState.COMPLETED ->
        TutorCurrentSessionFreeResponseStatus.COMPLETED
    CurrentTutorFreeResponseDispatchState.FAILED_CLOSED ->
        TutorCurrentSessionFreeResponseStatus.UNAVAILABLE
}

internal data class VerifiedCurrentTutorVisual(
    val scene: TutorVisualDocumentScene,
    val identity: TutorVisualPresentationIdentity,
    val anchor: TutorVisualTurnAnchor,
)

internal data class PreparedTutorLearningAuthority(
    val conversation: TutorConversation,
    val turn: TutorTurnReceipt,
    val evidence: TutorEvidenceRequest?,
    val directiveFingerprint: String,
    val requestVersion: Long,
)

internal fun ModelTaskSnapshot.acceptedTutorPlan(
    session: ConfirmedTutorSession,
): AcceptedTutorPlan? {
    if (status != ModelTaskStatus.SUCCEEDED || attemptCount !in 1..17) return null
    val input = request.input as? TutorPlanInput ?: return null
    val rawOutput = output as? TutorPlanOutput ?: return null
    val subject = SubjectKind.entries.singleOrNull { candidate ->
        candidate != SubjectKind.GENERAL &&
            candidate.name.equals(session.subject, ignoreCase = true)
    } ?: return null
    if (
        request.requestId.isBlank() ||
        input.sessionId != session.sessionId ||
        input.draftRevisionNumber != session.draftRevisionNumber ||
        !input.subject.equals(session.subject, ignoreCase = true) ||
        input.questionDocument != session.questionDocument.document ||
        rawOutput.sessionId != session.sessionId ||
        rawOutput.draftRevisionNumber != session.draftRevisionNumber ||
        rawOutput.questionDocumentId != input.questionDocument.id ||
        rawOutput.cycleOrdinal != input.cycleOrdinal ||
        rawOutput.turnOrdinal != input.turnOrdinal
    ) return null
    return AcceptedTutorPlan(input, rawOutput.locallyConstrainedFor(input), subject)
}

internal fun TutorPlanOutput.constrainedForLearningWrites(
    learningWritesAllowed: Boolean,
): TutorPlanOutput = if (learningWritesAllowed) {
    this
} else {
    copy(
        plan = plan.copy(
            showOpening = false,
            responseIntent = com.tingyun.smartmistakebook.core.model.TutorResponseIntent.EXPLAIN,
            solutionRevealed = true,
            diagnosticItem = null,
            interactionDirective = null,
            guidedInteractionProposal = null,
            hintMarkdown = null,
        ),
    )
}

/** Unsupported interactive directives must never leave a guided turn with no usable next action. */
internal fun TutorPlanOutput.constrainedForCurrentHostInteraction(
    authorizedEvidenceKind: TutorEvidenceRequestKind?,
): TutorPlanOutput {
    plan.guidedInteractionProposal?.let { proposal ->
        val proposedKind = when (proposal.directive) {
            is TutorInteractionDirective.Choices -> TutorEvidenceRequestKind.CHOICE
            is TutorInteractionDirective.VisualTarget -> TutorEvidenceRequestKind.VISUAL_TARGET
            TutorInteractionDirective.Continue,
            is TutorInteractionDirective.FreeResponse,
            -> return this
        }
        if (authorizedEvidenceKind != proposedKind) return this
        return copy(
            plan = plan.copy(
                interactionDirective = proposal.directive,
                visualScene = proposal.visualScene,
                visualRequest = null,
            ),
        )
    }
    if (plan.diagnosticItem != null) return this
    val unsupported = when (plan.interactionDirective) {
        is TutorInteractionDirective.Choices ->
            authorizedEvidenceKind != TutorEvidenceRequestKind.CHOICE
        is TutorInteractionDirective.FreeResponse ->
            authorizedEvidenceKind != TutorEvidenceRequestKind.FREE_RESPONSE
        is TutorInteractionDirective.VisualTarget ->
            authorizedEvidenceKind != TutorEvidenceRequestKind.VISUAL_TARGET
        TutorInteractionDirective.Continue -> true
        null -> false
    }
    if (!unsupported) return this
    return copy(
        plan = plan.copy(
            responseIntent = com.tingyun.smartmistakebook.core.model.TutorResponseIntent.EXPLAIN,
            solutionRevealed = true,
            interactionDirective = null,
            hintMarkdown = null,
        ),
    )
}

internal fun constrainedPresentationFingerprint(
    task: ModelTaskSnapshot,
    input: TutorPlanInput,
    output: TutorPlanOutput,
): String = CanonicalSha256("current-tutor-presentation-v1")
    .field("taskRequestFingerprint", task.requestFingerprint)
    .field("constrainedOutput", ModelTaskCodec.encodeOutput(output))
    .field("modeVersion", input.modeVersion)
    .field("learningWritesAllowed", input.allowLongTermLearningWrites)
    .field("learningWritePermissionVersion", input.learningWritePermissionVersion)
    .finish()

internal fun TutorPlanOutput.toUiPresentation(
    sessionId: String,
    presentationToken: String,
    explanationMode: com.tingyun.smartmistakebook.core.model.TutorExplanationMode,
    learningWritesAllowed: Boolean,
    visualIntent: TutorCurrentSessionVisualIntent,
    visualIntentVersion: Long,
    pendingInteractionKind: TutorEvidenceRequestKind?,
    freeResponseActionToken: String?,
    freeResponseSubmissionStatus: TutorCurrentSessionFreeResponseStatus?,
    hint: TutorCurrentSessionHint?,
): TutorCurrentSessionPresentation {
    val visibleText = buildList {
        if (plan.showOpening) {
            add(TutorCurrentSessionText(TutorCurrentSessionTextKind.OPENING, plan.openingMarkdown))
        }
        if (
            plan.solutionRevealed ||
            plan.responseIntent == com.tingyun.smartmistakebook.core.model.TutorResponseIntent.EXPLAIN
        ) {
            add(TutorCurrentSessionText(TutorCurrentSessionTextKind.SOLUTION, plan.solutionMarkdown))
        }
        if (isEmpty()) {
            add(TutorCurrentSessionText(TutorCurrentSessionTextKind.OPENING, plan.openingMarkdown))
        }
    }
    val interaction = if (!learningWritesAllowed) {
        null
    } else {
        plan.diagnosticItem
            ?.takeIf { item -> item.choices.size in 2..4 }
            ?.let { item ->
                TutorCurrentSessionInteraction.Choices(
                    promptMarkdown = item.promptMarkdown ?: item.stemMarkdown,
                    choices = item.choices.map { choice ->
                        TutorCurrentSessionChoice(
                            choiceId = choice.id,
                            labelMarkdown = choice.markdown,
                        )
                    },
                )
            }
            ?: when (val directive = plan.interactionDirective) {
                is TutorInteractionDirective.FreeResponse ->
                    freeResponseActionToken
                        ?.takeIf { pendingInteractionKind == TutorEvidenceRequestKind.FREE_RESPONSE }
                        ?.let { token ->
                            TutorCurrentSessionInteraction.FreeResponse(
                                promptMarkdown = directive.promptMarkdown,
                                actionToken = token,
                                submissionStatus = freeResponseSubmissionStatus
                                    ?: TutorCurrentSessionFreeResponseStatus.READY,
                            )
                        }
                is TutorInteractionDirective.Choices -> directive
                    .takeIf { pendingInteractionKind == TutorEvidenceRequestKind.CHOICE }
                    ?.let { current ->
                        TutorCurrentSessionInteraction.Choices(
                            promptMarkdown = current.promptMarkdown,
                            choices = current.choices.map { choice ->
                                TutorCurrentSessionChoice(
                                    choiceId = choice.id,
                                    labelMarkdown = choice.labelMarkdown,
                                )
                            },
                        )
                    }
                is TutorInteractionDirective.VisualTarget -> directive
                    .takeIf { pendingInteractionKind == TutorEvidenceRequestKind.VISUAL_TARGET }
                    ?.let { TutorCurrentSessionInteraction.VisualTarget(it.promptMarkdown) }
                else -> null
            }
    }
    return TutorCurrentSessionPresentation(
        sessionId = sessionId,
        presentationToken = presentationToken,
        explanationMode = explanationMode,
        learningWritesAllowed = learningWritesAllowed,
        visualIntent = visualIntent,
        visualIntentVersion = visualIntentVersion,
        text = visibleText,
        interaction = interaction,
        visualScene = plan.visualScene,
        hint = hint,
    )
}

internal fun TutorPlanOutput.toCurrentHintPresentation(
    record: CurrentTutorSessionHostWorkRecord,
    evidenceState: CurrentTutorSessionEvidenceState?,
): TutorCurrentSessionHint? {
    val state = evidenceState ?: return null
    val markdown = plan.hintMarkdown?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (
        record.explanationMode != com.tingyun.smartmistakebook.core.model.TutorExplanationMode.GUIDED ||
        plan.responseIntent != com.tingyun.smartmistakebook.core.model.TutorResponseIntent.ASK ||
        plan.solutionRevealed ||
        state.answerWasRevealed ||
        state.hintCount !in 0..1
    ) return null
    val slotToken = CanonicalSha256("current-tutor-hint-slot-v1")
        .field("sessionId", record.sessionId)
        .field("scopeId", state.scopeId)
        .field("questionDocumentId", record.questionDocumentId)
        .field("questionRevisionNumber", record.questionRevisionNumber)
        .field("cycleOrdinal", record.cycleOrdinal)
        .field("turnOrdinal", record.turnOrdinal)
        .field("presentationToken", record.presentationToken)
        .field("presentationFingerprint", record.constrainedTutorContentFingerprint)
        .field("mode", record.explanationMode.name)
        .field("modeVersion", record.modeVersion)
        .field("hintMarkdown", markdown)
        .finish()
    return TutorCurrentSessionHint(
        markdown = markdown,
        slotToken = slotToken,
        status = if (state.hintCount == 0) {
            TutorCurrentSessionHintStatus.AVAILABLE
        } else {
            TutorCurrentSessionHintStatus.SHOWN
        },
    )
}

internal fun CurrentTutorVerifiedMaterial.matchesSubject(subject: SubjectKind): Boolean =
    verifiedKnowledgeProofs.all { proof -> proof.ref.subject == subject } &&
        teachingReferences.all { reference -> reference.proof.ref.subject == subject }

internal fun CurrentTutorVerifiedMaterial.withoutLearningEvidence(): CurrentTutorVerifiedMaterial =
    copy(
        verifiedKnowledgeProofs = emptyList(),
        teachingReferences = emptyList(),
        prohibitedEvaluatorExecutionFingerprint = null,
        trustedAnswerInteraction = null,
        learningEvidenceEligible = false,
    )

internal fun TutorPlanOutput.proposedEvidenceKind(input: TutorPlanInput): TutorEvidenceRequestKind? {
    if (
        !input.allowLongTermLearningWrites ||
        input.explanationMode != com.tingyun.smartmistakebook.core.model.TutorExplanationMode.GUIDED ||
        plan.solutionRevealed
    ) return null
    plan.guidedInteractionProposal?.let { proposal ->
        return when (proposal.directive) {
            is TutorInteractionDirective.Choices -> TutorEvidenceRequestKind.CHOICE
            is TutorInteractionDirective.VisualTarget -> TutorEvidenceRequestKind.VISUAL_TARGET
            TutorInteractionDirective.Continue,
            is TutorInteractionDirective.FreeResponse,
            -> null
        }
    }
    val candidates = buildList {
        plan.diagnosticItem?.takeIf { item -> item.choices.size in 2..4 }
            ?.let { add(TutorEvidenceRequestKind.CHOICE) }
        (plan.interactionDirective as? TutorInteractionDirective.Choices)
            ?.let { add(TutorEvidenceRequestKind.CHOICE) }
        (plan.interactionDirective as? TutorInteractionDirective.VisualTarget)
            ?.let { add(TutorEvidenceRequestKind.VISUAL_TARGET) }
        (plan.interactionDirective as? TutorInteractionDirective.FreeResponse)
            ?.let { add(TutorEvidenceRequestKind.FREE_RESPONSE) }
    }
    return candidates.singleOrNull()
}

internal fun CurrentTutorVerifiedMaterial.isUniqueCurrentStepAuthority(
    query: CurrentTutorVerifiedMaterialQuery,
    kind: TutorEvidenceRequestKind,
): Boolean {
    val input = query.input
    val output = query.output
    if (!learningEvidenceEligible || output.proposedEvidenceKind(input) != kind) return false
    val targetLabel = output.plan.targetedEvidenceLabels.singleOrNull() ?: return false
    val guidance = input.teachingConstraints.singleOrNull { value ->
        value.label == targetLabel && value.constraint == TutorTeachingConstraint.MAY_GUIDE
    } ?: return false
    if (input.teachingConstraints.count { it.constraint == TutorTeachingConstraint.MAY_GUIDE } != 1) {
        return false
    }
    val reference = teachingReferences.singleOrNull() ?: return false
    if (
        verifiedKnowledgeProofs.size != 1 ||
        reference.label != guidance.label ||
        reference.constraint != TutorTeachingConstraint.MAY_GUIDE
    ) return false
    val answerInteraction = trustedAnswerInteraction
    return when (answerInteraction?.responseForm) {
        ReviewResponseForm.CHOICE ->
            kind == TutorEvidenceRequestKind.CHOICE &&
                answerInteraction.exactContentBinding ==
                CurrentTutorTrustedAnswerContentBinding.forQuery(
                    query,
                    ReviewResponseForm.CHOICE,
                ) &&
                (
                    output.plan.diagnosticItem?.choices?.size?.let { it in 2..4 } == true ||
                        (output.plan.guidedInteractionProposal?.directive as?
                            TutorInteractionDirective.Choices)
                            ?.choices?.size?.let { it in 2..4 } == true ||
                        (output.plan.interactionDirective as? TutorInteractionDirective.Choices)
                            ?.choices?.size?.let { it in 2..4 } == true
                )
        ReviewResponseForm.VISUAL_TARGET ->
            kind == TutorEvidenceRequestKind.VISUAL_TARGET &&
                answerInteraction.exactContentBinding ==
                CurrentTutorTrustedAnswerContentBinding.forQuery(
                    query,
                    ReviewResponseForm.VISUAL_TARGET,
                ) &&
                (
                    output.plan.guidedInteractionProposal?.directive is
                        TutorInteractionDirective.VisualTarget ||
                        output.plan.interactionDirective is TutorInteractionDirective.VisualTarget
                    )
        ReviewResponseForm.NUMERIC -> false
        null -> kind == TutorEvidenceRequestKind.FREE_RESPONSE &&
            prohibitedEvaluatorExecutionFingerprint != null &&
            output.plan.diagnosticItem == null &&
            output.plan.interactionDirective is TutorInteractionDirective.FreeResponse
    }
}

internal fun CurrentTutorVerifiedMaterial.currentStepAuthorityFingerprint(): String =
    CanonicalSha256("current-tutor-step-authority-v1")
        .field("problemAnchorId", problemAnchorId)
        .field("problemFingerprint", problemFingerprint)
        .field("problemFamilyFingerprint", problemFamilyFingerprint)
        .field("attributionPolicyVersion", attributionPolicyVersion)
        .field("responsePolicyVersion", responsePolicyVersion)
        .field("rubricCanonicalFingerprint", rubricCanonicalFingerprint)
        .field(
            "proofFingerprints",
            verifiedKnowledgeProofs.map { it.ref.canonicalFingerprint }.sorted().joinToString("|"),
        )
        .field(
            "teachingReferences",
            teachingReferences.sortedBy { it.proof.ref.canonicalFingerprint }.joinToString("|") {
                "${it.proof.ref.canonicalFingerprint}:${it.label}:${it.constraint.name}"
            },
        )
        .field(
            "answerCapability",
            trustedAnswerInteraction?.canonicalFingerprint.orEmpty(),
        )
        .nullableField(
            "prohibitedEvaluatorExecutionFingerprint",
            prohibitedEvaluatorExecutionFingerprint,
        )
        .finish()

internal fun CurrentTutorSessionEvidenceState.matches(
    record: CurrentTutorSessionHostWorkRecord,
): Boolean =
    scopeId == record.activeScopeId &&
        activationFingerprint == record.targetActivationFingerprint &&
        presentationFingerprint == record.constrainedTutorContentFingerprint &&
        // Attempt and hint state advance in the interaction transaction. Host-work identity is
        // fixed by the durable scope and presentation fingerprints above.
        record.attemptOrdinal == FIRST_STUDENT_ATTEMPT_ORDINAL

internal fun CurrentTutorSessionEvidenceState?.isStudentSubmissionSuccessorOf(
    before: CurrentTutorSessionEvidenceState,
): Boolean =
    this != null &&
        scopeId == before.scopeId &&
        activationFingerprint == before.activationFingerprint &&
        presentationFingerprint == before.presentationFingerprint &&
        stateVersion == before.stateVersion + 1L &&
        stateFingerprint != before.stateFingerprint &&
        attemptOrdinal == before.attemptOrdinal + 1 &&
        hintCount == before.hintCount &&
        answerWasRevealed == before.answerWasRevealed

internal fun CurrentTutorSessionEvidenceState.isSameEvidenceFactsAfterOneClaim(
    before: CurrentTutorSessionEvidenceState,
): Boolean =
    scopeId == before.scopeId &&
        activationFingerprint == before.activationFingerprint &&
        presentationFingerprint == before.presentationFingerprint &&
        stateVersion == before.stateVersion + 1L &&
        stateFingerprint != before.stateFingerprint &&
        attemptOrdinal == before.attemptOrdinal &&
        hintCount == before.hintCount &&
        answerWasRevealed == before.answerWasRevealed

internal suspend fun TutorInteractionRepository.cancelCurrentEvidence(
    record: CurrentTutorSessionHostWorkRecord,
    evidenceRequestId: String,
    occurredAtEpochMillis: Long,
) {
    cancelEvidence(
        CancelTutorEvidenceCommand(
            sessionId = record.sessionId,
            questionDocumentId = record.questionDocumentId,
            revisionNumber = record.questionRevisionNumber,
            occurredAtEpochMillis = occurredAtEpochMillis,
            evidenceRequestId = evidenceRequestId,
        ),
    )
}

internal fun TutorVisualGenerateInput.matchesCurrentVisual(
    current: ResolvedCurrentTutorPresentation,
    expectedAnchor: TutorVisualTurnAnchor,
): Boolean =
    sessionId == current.session.sessionId &&
        draftRevisionNumber == current.session.draftRevisionNumber &&
        subject.equals(current.input.subject, ignoreCase = true) &&
        questionDocument == current.input.questionDocument &&
        anchor == expectedAnchor

internal fun TutorVisualReviewInput.matchesCurrentVisual(
    current: ResolvedCurrentTutorPresentation,
    expectedAnchor: TutorVisualTurnAnchor,
): Boolean =
    sessionId == current.session.sessionId &&
        draftRevisionNumber == current.session.draftRevisionNumber &&
        subject.equals(current.input.subject, ignoreCase = true) &&
        questionDocument == current.input.questionDocument &&
        anchor == expectedAnchor

internal fun directiveFingerprint(input: TutorPlanInput, output: TutorPlanOutput): String =
    CanonicalSha256("current-tutor-directive-v1")
        .field("mode", input.explanationMode.name)
        .field("modeVersion", input.modeVersion)
        .field("sessionId", input.sessionId)
        .field("cycleOrdinal", output.cycleOrdinal)
        .field("turnOrdinal", output.turnOrdinal)
        .field("output", ModelTaskCodec.encodeOutput(output))
        .finish()

internal fun tutorRequestVersion(output: TutorPlanOutput): Long =
    (output.cycleOrdinal.toLong() - 1L) * TutorPlanInput.MAX_TURNS + output.turnOrdinal.toLong()

internal fun authorityConversationId(sessionId: String): String =
    "current-tutor-conversation:" + CanonicalSha256("current-tutor-conversation-id-v1")
        .field("sessionId", sessionId)
        .finish()

internal fun authorityTurnReceiptId(requestFingerprint: String): String =
    "current-tutor-turn:" + CanonicalSha256("current-tutor-turn-id-v1")
        .field("requestFingerprint", requestFingerprint)
        .finish()

internal fun authorityEvidenceRequestId(requestFingerprint: String): String =
    "current-tutor-evidence:" + CanonicalSha256("current-tutor-evidence-id-v1")
        .field("requestFingerprint", requestFingerprint)
        .finish()

internal fun TutorTurnReceipt.matches(
    conversationId: String,
    generation: Long,
    subject: SubjectKind,
    problemAnchorId: String,
    requestVersion: Long,
    modeVersion: Long,
    directiveFingerprint: String,
): Boolean =
    this.conversationId == conversationId &&
        conversationGeneration == generation &&
        conversationStateVersion >= 1L &&
        turnOrdinal > 0 &&
        this.subject == subject &&
        this.problemAnchorId == problemAnchorId &&
        this.requestVersion == requestVersion &&
        this.modeVersion == modeVersion &&
        this.directiveFingerprint == directiveFingerprint

internal fun TutorEvidenceRequest.matches(
    turn: TutorTurnReceipt,
    kind: TutorEvidenceRequestKind,
    directiveFingerprint: String,
): Boolean =
    conversationId == turn.conversationId &&
        conversationGeneration == turn.conversationGeneration &&
        conversationStateVersion == turn.conversationStateVersion &&
        turnReceiptId == turn.turnReceiptId &&
        turnOrdinal == turn.turnOrdinal &&
        subject == turn.subject &&
        problemAnchorId == turn.problemAnchorId &&
        this.kind == kind &&
        requestVersion == turn.requestVersion &&
        modeVersion == turn.modeVersion &&
        explanationMode == turn.explanationMode &&
        this.directiveFingerprint == directiveFingerprint

internal fun CurrentTutorSessionActivation.toStageCommand(
    task: ModelTaskSnapshot,
    session: ConfirmedTutorSession,
    input: TutorPlanInput,
    output: TutorPlanOutput,
    writeAuthority: CurrentTutorLearningWriteAuthoritySnapshot,
    policy: TutorCurrentSessionPolicySnapshot,
    durablePolicyRequired: Boolean,
    authority: PreparedTutorLearningAuthority,
    existing: CurrentTutorSessionHostWorkRecord?,
    occurredAtEpochMillis: Long,
): StageCurrentTutorSessionHostWorkCommand {
    val payload = CanonicalSha256("current-tutor-host-work-payload-v1")
        .field("activationFingerprint", activationFingerprint)
        .field("taskRequestFingerprint", task.requestFingerprint)
        .field("authorityTurnReceiptId", authority.turn.turnReceiptId)
        .field("authorityDirectiveFingerprint", authority.directiveFingerprint)
        .field("constrainedTutorContentFingerprint", presentationFingerprint)
        .field("learningWritePermissionVersion", writeAuthority.permissionVersion)
        .field("visualIntent", policy.visualIntent.name)
        .field("visualIntentVersion", policy.visualIntentVersion)
        .finish()
    return StageCurrentTutorSessionHostWorkCommand(
        workId = "current-tutor-work:" + CanonicalSha256("current-tutor-work-id-v1")
            .field("sessionId", session.sessionId)
            .field("taskRequestFingerprint", task.requestFingerprint)
            .finish(),
        learnerId = learnerId,
        sessionId = session.sessionId,
        questionDocumentId = input.questionDocument.id,
        questionRevisionNumber = session.draftRevisionNumber,
        subject = subject,
        authorityConversationId = authority.conversation.conversationId,
        authorityConversationGeneration = authority.conversation.generation,
        authorityConversationStateVersion = authority.turn.conversationStateVersion,
        authorityTurnReceiptId = authority.turn.turnReceiptId,
        authorityTurnOrdinal = authority.turn.turnOrdinal,
        authorityRequestVersion = authority.requestVersion,
        authorityDirectiveFingerprint = authority.directiveFingerprint,
        modelTaskRequestId = task.request.requestId,
        modelTaskRequestFingerprint = task.requestFingerprint,
        problemAnchorId = problemAnchorId,
        explanationMode = input.explanationMode,
        modeVersion = input.modeVersion,
        learningWritesAllowed = writeAuthority.allowed,
        learningWritePermissionVersion = writeAuthority.permissionVersion,
        visualIntent = policy.visualIntent,
        visualIntentVersion = policy.visualIntentVersion,
        cycleOrdinal = output.cycleOrdinal,
        turnOrdinal = output.turnOrdinal,
        // Model execution retries are not student attempts. Host work exposes the first valid
        // ordinal while the durable interaction scope starts at zero.
        attemptOrdinal = FIRST_STUDENT_ATTEMPT_ORDINAL,
        requestVersion = authority.requestVersion,
        evidenceRequestId = authority.evidence?.evidenceRequestId,
        pendingInteractionKind = authority.evidence?.kind,
        targetScopeId = scopeId,
        targetActivationFingerprint = activationFingerprint,
        constrainedTutorContentFingerprint = presentationFingerprint,
        presentationToken = CanonicalSha256("current-tutor-presentation-token-v1")
            .field("activationFingerprint", activationFingerprint)
            .field("payloadFingerprint", payload)
            .finish(),
        payloadFingerprint = payload,
        expectedPolicyStateFingerprint = if (!durablePolicyRequired) {
            null
        } else {
            policy.durableStateFingerprint(learnerId)
        },
        expectedStateVersion = existing?.stateVersion,
        expectedStateFingerprint = existing?.stateFingerprint,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )
}

internal fun CurrentTutorSessionHostWorkRecord.matchesActivation(
    activation: CurrentTutorSessionActivation,
    directiveFingerprint: String,
    policy: TutorCurrentSessionPolicySnapshot,
): Boolean =
    learnerId == activation.learnerId &&
        sessionId == activation.conversationId &&
        questionDocumentId == activation.questionDocument.id &&
        questionRevisionNumber == activation.questionRevisionNumber &&
        subject == activation.subject &&
        authorityConversationId == activation.authorityConversationId &&
        authorityConversationGeneration == activation.authorityConversationGeneration &&
        authorityConversationStateVersion == activation.authorityConversationStateVersion &&
        authorityTurnReceiptId == activation.authorityTurnReceiptId &&
        authorityTurnOrdinal == activation.authorityTurnOrdinal &&
        authorityRequestVersion == activation.authorityRequestVersion &&
        authorityDirectiveFingerprint == directiveFingerprint &&
        problemAnchorId == activation.problemAnchorId &&
        explanationMode == activation.explanationMode &&
        modeVersion == activation.modeVersion &&
        learningWritesAllowed == activation.learningWritesAllowed &&
        learningWritePermissionVersion == activation.learningWritePermissionVersion &&
        explanationMode == policy.explanationMode &&
        modeVersion == policy.modeVersion &&
        learningWritesAllowed == policy.learningWritesAllowed &&
        learningWritePermissionVersion == policy.learningWritePermissionVersion &&
        visualIntent == policy.visualIntent &&
        visualIntentVersion == policy.visualIntentVersion &&
        cycleOrdinal == activation.cycleOrdinal &&
        turnOrdinal == activation.turnOrdinal &&
        attemptOrdinal == activation.attemptOrdinal.coerceAtLeast(FIRST_STUDENT_ATTEMPT_ORDINAL) &&
        requestVersion == activation.requestVersion &&
        targetScopeId == activation.scopeId &&
        targetActivationFingerprint == activation.activationFingerprint &&
        constrainedTutorContentFingerprint == activation.presentationFingerprint

internal fun TutorCurrentSessionPolicySnapshot.durableStateFingerprint(learnerId: String): String =
    CanonicalSha256("current-tutor-policy-state-v1")
        .field("learnerId", learnerId)
        .field("sessionId", sessionId)
        .field("explanationMode", explanationMode.name)
        .field("modeVersion", modeVersion)
        .field("learningWritesAllowed", learningWritesAllowed)
        .field("learningWritePermissionVersion", learningWritePermissionVersion)
        .field("visualIntent", visualIntent.name)
        .field("visualIntentVersion", visualIntentVersion)
        .finish()

internal fun CurrentTutorSessionHostWorkRecord.toHostSnapshot(): TutorCurrentSessionHostSnapshot =
    TutorCurrentSessionHostSnapshot(
        sessionId = sessionId,
        phase = when (status) {
            CurrentTutorSessionHostWorkStatus.STAGED -> TutorCurrentSessionHostPhase.PREPARING
            CurrentTutorSessionHostWorkStatus.ACTIVE -> TutorCurrentSessionHostPhase.ACTIVE
            CurrentTutorSessionHostWorkStatus.REVOKED -> TutorCurrentSessionHostPhase.REVOKED
        },
        questionRevisionNumber = questionRevisionNumber,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        learningWritesAllowed = learningWritesAllowed,
        learningWritePermissionVersion = learningWritePermissionVersion,
        visualIntent = visualIntent,
        visualIntentVersion = visualIntentVersion,
        presentationToken = presentationToken.takeIf {
            status == CurrentTutorSessionHostWorkStatus.ACTIVE
        },
        pendingInteractionKind = pendingInteractionKind.takeIf {
            status == CurrentTutorSessionHostWorkStatus.ACTIVE
        },
    )

internal fun TutorCurrentSessionRevocationReason.toDatabaseReason():
    CurrentTutorSessionHostWorkRevocationReason =
    CurrentTutorSessionHostWorkRevocationReason.valueOf(name)

internal const val TRUSTED_ANSWER_ACCEPTED_FEEDBACK = "已记录。"
internal const val FIRST_STUDENT_ATTEMPT_ORDINAL = 1

internal fun unavailable(
    blocker: TutorCurrentSessionHostBlocker,
): TutorCurrentSessionHostResult = TutorCurrentSessionHostResult.Unavailable(blocker)

internal fun rejectedAction(
    blocker: TutorCurrentSessionActionBlocker,
): TutorCurrentSessionChoiceActionResult = TutorCurrentSessionChoiceActionResult.Rejected(blocker)

internal fun buildCurrentTutorFreeResponseSubmission(
    context: CurrentFreeResponseDispatchContext,
    lease: CurrentTutorFreeResponseDispatchLease,
    activeDispatch: ActiveFreeResponseDispatch,
    isCurrent: () -> Boolean,
): CurrentTutorFreeResponseSubmission {
    val record = context.record
    val contextRequest = CoreDataTutorOpenResponseContextRequest(
        conversationId = record.sessionId,
        conversationGeneration = 1L,
        conversationStateVersion = record.authorityConversationStateVersion,
        questionDocumentId = record.questionDocumentId,
        questionRevisionNumber = record.questionRevisionNumber,
        subject = record.subject,
        questionDocument = context.current.input.questionDocument,
        explanationMode = record.explanationMode,
        modeVersion = record.modeVersion,
        turnReferenceId = context.authority.evidenceRequestId,
        turnOrdinal = record.turnOrdinal,
        turnGeneration = record.authorityConversationStateVersion,
        evidenceRequestId = context.authority.evidenceRequestId,
        attemptOrdinal = context.evidenceState.attemptOrdinal,
        hintCount = context.evidenceState.hintCount,
        answerWasRevealed = context.evidenceState.answerWasRevealed,
        requestVersion = record.requestVersion,
    )
    return CurrentTutorFreeResponseSubmission(
        contextRequest = contextRequest,
        expectedPresentationFingerprint = record.constrainedTutorContentFingerprint,
        expectedProblemFingerprint = context.material.problemFingerprint,
        expectedProblemFamilyFingerprint = context.material.problemFamilyFingerprint,
        actionToken = lease.actionToken,
        answerBinding = lease.answerBinding,
        answer = lease.answer,
        elapsedDurationMillis = null,
        occurredAtEpochMillis = lease.canonicalOccurredAtEpochMillis,
        isCurrent = isCurrent,
    )
}

internal fun prepareCurrentTutorPresentationOnlyAuthority(
    learnerId: String,
    session: ConfirmedTutorSession,
    task: ModelTaskSnapshot,
    input: TutorPlanInput,
    output: TutorPlanOutput,
    subject: SubjectKind,
    material: CurrentTutorVerifiedMaterial,
    nowEpochMillis: Long,
): PreparedTutorLearningAuthority {
    val directiveFingerprint = directiveFingerprint(input, output)
    val requestVersion = tutorRequestVersion(output)
    val conversationId = "current-tutor-no-write:" + CanonicalSha256(
        "current-tutor-no-write-conversation-v1",
    )
        .field("learnerId", learnerId)
        .field("sessionId", session.sessionId)
        .field("permissionVersion", input.learningWritePermissionVersion)
        .finish()
    val turnReceiptId = "current-tutor-no-write-turn:" + CanonicalSha256(
        "current-tutor-no-write-turn-v1",
    )
        .field("taskRequestFingerprint", task.requestFingerprint)
        .field("directiveFingerprint", directiveFingerprint)
        .finish()
    val conversation = TutorConversation(
        conversationId = conversationId,
        learnerScopeId = learnerId,
        generation = 1L,
        status = TutorConversationStatus.ACTIVE,
        createdAtEpochMillis = nowEpochMillis,
        archivedAtEpochMillis = null,
        stateVersion = 1L,
    )
    val turn = TutorTurnReceipt(
        turnReceiptId = turnReceiptId,
        conversationId = conversationId,
        conversationGeneration = 1L,
        conversationStateVersion = 1L,
        turnOrdinal = 1,
        subject = subject,
        problemAnchorId = material.problemAnchorId,
        requestVersion = requestVersion,
        modeVersion = input.modeVersion,
        explanationMode = input.explanationMode,
        directiveFingerprint = directiveFingerprint,
        studentMessageFingerprint =
            OpenResponseEvaluationTaskFingerprints.question(input.questionDocument),
        studentMessageSummary = session.title.take(TutorTurnReceipt.MAX_SUMMARY_CHARS),
        occurredAtEpochMillis = nowEpochMillis,
    )
    return PreparedTutorLearningAuthority(
        conversation = conversation,
        turn = turn,
        evidence = null,
        directiveFingerprint = directiveFingerprint,
        requestVersion = requestVersion,
    )
}

internal fun buildCurrentTutorSessionActivation(
    learnerId: String,
    session: ConfirmedTutorSession,
    task: ModelTaskSnapshot,
    input: TutorPlanInput,
    output: TutorPlanOutput,
    subject: SubjectKind,
    writeAuthority: CurrentTutorLearningWriteAuthoritySnapshot,
    material: CurrentTutorVerifiedMaterial,
    authority: PreparedTutorLearningAuthority,
    nowEpochMillis: Long,
): CurrentTutorSessionActivation = CurrentTutorSessionActivation(
    learnerId = learnerId,
    conversationId = session.sessionId,
    conversationGeneration = 1L,
    conversationStateVersion = authority.turn.conversationStateVersion,
    authorityConversationId = authority.conversation.conversationId,
    authorityConversationGeneration = authority.conversation.generation,
    authorityConversationStateVersion = authority.turn.conversationStateVersion,
    authorityTurnReceiptId = authority.turn.turnReceiptId,
    authorityTurnOrdinal = authority.turn.turnOrdinal,
    authorityRequestVersion = authority.requestVersion,
    questionRevisionNumber = session.draftRevisionNumber,
    questionDocument = input.questionDocument,
    subject = subject,
    problemAnchorId = material.problemAnchorId,
    explanationMode = input.explanationMode,
    modeVersion = input.modeVersion,
    turnReferenceId = authority.evidence?.evidenceRequestId ?: task.request.requestId,
    turnOrdinal = output.turnOrdinal,
    turnGeneration = authority.turn.conversationStateVersion,
    cycleOrdinal = output.cycleOrdinal,
    attemptOrdinal = 0,
    hintCount = 0,
    answerWasRevealed = output.plan.solutionRevealed,
    requestVersion = authority.requestVersion,
    learningWritesAllowed = writeAuthority.allowed,
    learningWritePermissionVersion = writeAuthority.permissionVersion,
    presentationFingerprint = constrainedPresentationFingerprint(task, input, output),
    problemFingerprint = material.problemFingerprint,
    problemFamilyFingerprint = material.problemFamilyFingerprint,
    attributionPolicyVersion = material.attributionPolicyVersion,
    responsePolicyVersion = material.responsePolicyVersion,
    rubricCanonicalFingerprint = material.rubricCanonicalFingerprint,
    verifiedKnowledgeProofs = material.verifiedKnowledgeProofs,
    teachingReferences = material.teachingReferences,
    evaluator = material.evaluator,
    evaluatorPolicyFingerprint = material.evaluatorPolicyFingerprint,
    prohibitedEvaluatorExecutionFingerprint =
        material.prohibitedEvaluatorExecutionFingerprint,
    occurredAtEpochMillis = nowEpochMillis,
)

internal suspend fun resolveCurrentTutorLearningMaterial(
    learnerId: String,
    verifiedMaterialAuthority: CurrentTutorVerifiedMaterialAuthority,
    current: ResolvedCurrentTutorPresentation,
    kind: TutorEvidenceRequestKind,
): CurrentTutorVerifiedMaterial? {
    val query = CurrentTutorVerifiedMaterialQuery(
        learnerId = learnerId,
        session = current.session,
        subject = current.subject,
        task = current.task,
        input = current.input,
        output = current.output,
        currentStepKnowledgeAuthorityRequired = true,
    )
    return verifiedMaterialAuthority.resolve(query)?.takeIf { material ->
        material.matchesSubject(current.subject) &&
            material.isUniqueCurrentStepAuthority(query, kind)
    }
}

internal suspend fun resolveCurrentTutorVisual(
    modelTasks: CurrentTutorModelTaskSource,
    current: ResolvedCurrentTutorPresentation,
    proof: TutorVisualHitProof,
): VerifiedCurrentTutorVisual? {
    if (proof.presentation.ownerModelTaskRequestId != current.task.request.requestId) return null
    val anchor = TutorVisualTurnAnchor(
        surface = TutorVisualTurnSurface.PLAN,
        cycleOrdinal = current.output.cycleOrdinal,
        turnOrdinal = current.output.turnOrdinal,
    )
    val scene = when (proof.presentation.sourceKind) {
        TutorVisualSceneSourceKind.INLINE -> {
            if (proof.presentation.sceneTaskRequestId != current.task.request.requestId) {
                return null
            }
            current.output.plan.visualScene as? TutorVisualDocumentScene ?: return null
        }
        TutorVisualSceneSourceKind.GENERATED -> {
            val task = modelTasks.read(proof.presentation.sceneTaskRequestId)
                ?.takeIf { value ->
                    value.status == ModelTaskStatus.SUCCEEDED &&
                        value.request.requestId == proof.presentation.sceneTaskRequestId
                }
                ?: return null
            when (val visualInput = task.request.input) {
                is TutorVisualGenerateInput -> {
                    val visualOutput = task.output as? TutorVisualGenerateOutput ?: return null
                    if (
                        !visualInput.matchesCurrentVisual(current, anchor) ||
                        visualOutput.sessionId != current.session.sessionId ||
                        visualOutput.draftRevisionNumber != current.session.draftRevisionNumber ||
                        visualOutput.questionDocumentId != current.input.questionDocument.id ||
                        visualOutput.anchor != anchor ||
                        visualOutput.decision != TutorVisualGenerationDecision.GENERATED ||
                        visualOutput.confidence < MIN_VISUAL_GENERATION_CONFIDENCE
                    ) return null
                    visualOutput.scene ?: return null
                }
                is TutorVisualReviewInput -> {
                    val visualOutput = task.output as? TutorVisualReviewOutput ?: return null
                    if (
                        !visualInput.matchesCurrentVisual(current, anchor) ||
                        visualOutput.sessionId != current.session.sessionId ||
                        visualOutput.draftRevisionNumber != current.session.draftRevisionNumber ||
                        visualOutput.questionDocumentId != current.input.questionDocument.id ||
                        visualOutput.anchor != anchor ||
                        visualOutput.confidence < MIN_VISUAL_REVIEW_CONFIDENCE
                    ) return null
                    when (visualOutput.decision) {
                        TutorVisualReviewDecision.APPROVED -> visualInput.candidateScene
                        TutorVisualReviewDecision.REPAIRED -> visualOutput.scene ?: return null
                        TutorVisualReviewDecision.REJECTED -> return null
                    }
                }
                else -> return null
            }
        }
    }
    val identity = TutorVisualPresentationIdentity(
        ownerModelTaskRequestId = current.task.request.requestId,
        sourceKind = proof.presentation.sourceKind,
        sceneTaskRequestId = proof.presentation.sceneTaskRequestId,
        sceneId = scene.sceneId,
        sceneFingerprint = TutorVisualSceneFingerprint.of(scene),
    )
    if (proof.presentation != identity) return null
    if (scene.panels.none { panel -> panel.panelId == proof.panelId }) return null
    return VerifiedCurrentTutorVisual(scene, identity, anchor)
}

internal fun requireHostId(value: String) {
    require(
        value.isNotBlank() && value == value.trim() && value.length <= 256 &&
            value.none(Char::isISOControl),
    )
}

internal fun requireHostFingerprint(value: String) {
    require(value.length == 64 && value.all { character -> character in '0'..'9' || character in 'a'..'f' })
}

internal val SUPPORTED_INTERACTION_EVIDENCE_KINDS = setOf(
    TutorEvidenceRequestKind.CHOICE,
    TutorEvidenceRequestKind.VISUAL_TARGET,
    TutorEvidenceRequestKind.FREE_RESPONSE,
)
internal const val MIN_VISUAL_GENERATION_CONFIDENCE = 0.90
internal const val MIN_VISUAL_REVIEW_CONFIDENCE = 0.75
internal const val VISUAL_TARGET_TOKEN_TTL_MILLIS = 2 * 60 * 1_000L
internal const val FREE_RESPONSE_TOKEN_TTL_MILLIS = 2 * 60 * 1_000L
// Longer than the 120s provider timeout plus preparation/finalization overhead.
internal const val FREE_RESPONSE_DISPATCH_LEASE_MILLIS = 5 * 60 * 1_000L
internal val CURRENT_TUTOR_PROCESS_DISPATCH_GENERATION_ID =
    "current-tutor-process:${UUID.randomUUID()}"
