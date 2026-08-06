package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.domain.AllocateTutorTurnCommand
import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.CreateTutorConversationCommand
import com.tingyun.smartmistakebook.core.domain.PrepareTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicySnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyUpdate
import com.tingyun.smartmistakebook.core.domain.TutorExplanationModeSnapshot
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorConversation
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorTurnReceipt
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewResponse

/** Current value from the local owner of long-term learning-write permission. */
internal data class CurrentTutorLearningWriteAuthoritySnapshot(
    val sessionId: String,
    val allowed: Boolean,
    val permissionVersion: Long,
) {
    init {
        requireHostId(sessionId)
        require(permissionVersion >= 0L)
    }
}

internal fun interface CurrentTutorLearningWriteAuthority {
    suspend fun current(sessionId: String): CurrentTutorLearningWriteAuthoritySnapshot?
}

/** Query contains only already-verified local records and a schema-validated model result. */
internal data class CurrentTutorVerifiedMaterialQuery(
    val learnerId: String,
    val session: ConfirmedTutorSession,
    val subject: SubjectKind,
    val task: ModelTaskSnapshot,
    val input: TutorPlanInput,
    val output: TutorPlanOutput,
    /** True only after the Host has persisted an exact current-step MAY_GUIDE proof subset. */
    val currentStepKnowledgeAuthorityRequired: Boolean,
)

/**
 * Independent local authority material. The coordinator has no fallback values for this object:
 * absence, ambiguity, or a changed proof snapshot makes activation unavailable.
 */
internal data class CurrentTutorVerifiedMaterial(
    val problemAnchorId: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val attributionPolicyVersion: String,
    val responsePolicyVersion: String,
    val rubricCanonicalFingerprint: String,
    val verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof>,
    val teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    val evaluator: OpenResponseEvaluatorKind,
    val evaluatorPolicyFingerprint: String,
    val prohibitedEvaluatorExecutionFingerprint: String? = null,
    val trustedAnswerInteraction: CurrentTutorPreparedAnswerInteraction? = null,
    val learningEvidenceEligible: Boolean = true,
) {
    init {
        listOf(problemAnchorId, attributionPolicyVersion, responsePolicyVersion)
            .forEach(::requireHostId)
        listOf(
            problemFingerprint,
            problemFamilyFingerprint,
            rubricCanonicalFingerprint,
            evaluatorPolicyFingerprint,
        ).forEach(::requireHostFingerprint)
        prohibitedEvaluatorExecutionFingerprint?.let(::requireHostFingerprint)
        require(verifiedKnowledgeProofs.size <= 24)
        require(teachingReferences.size <= 24)
        require(
            !learningEvidenceEligible ||
                verifiedKnowledgeProofs.isNotEmpty() && teachingReferences.isNotEmpty(),
        ) { "Learning-eligible material requires independently verified knowledge" }
        require(verifiedKnowledgeProofs.isEmpty() == teachingReferences.isEmpty())
        require(
            verifiedKnowledgeProofs.distinctBy { proof -> proof.ref.canonicalFingerprint }.size ==
                verifiedKnowledgeProofs.size,
        )
        require(
            teachingReferences.distinctBy { reference ->
                reference.proof.ref.canonicalFingerprint
            }.size == teachingReferences.size,
        )
        val proofFingerprints = verifiedKnowledgeProofs
            .mapTo(hashSetOf()) { proof -> proof.ref.canonicalFingerprint }
        require(
            teachingReferences.all { reference ->
                reference.proof.ref.canonicalFingerprint in proofFingerprints &&
                    reference.proof in verifiedKnowledgeProofs
            },
        ) { "Every teaching reference must be backed by the supplied verified proof" }
    }
}

internal data class CurrentTutorTrustedAnswerSubmissionFence(
    val sessionId: String,
    val presentationToken: String,
    val evidenceRequestId: String,
    val expectedEvidenceState: CurrentTutorSessionEvidenceState,
    val occurredAtEpochMillis: Long,
    val hitProof: TutorVisualHitProof? = null,
) {
    init {
        requireHostId(sessionId)
        requireHostFingerprint(presentationToken)
        requireHostId(evidenceRequestId)
        require(occurredAtEpochMillis >= 0L)
    }
}

internal data class CurrentTutorTrustedAnswerCommitReceipt(
    val sessionId: String,
    val presentationToken: String,
    val evidenceRequestId: String,
    val exactContentBinding: String,
    val responseForm: ReviewResponseForm,
    val responseBinding: String,
    val eventId: String,
    val eventPayloadFingerprint: String,
    val duplicate: Boolean,
    val resultingEvidenceState: CurrentTutorSessionEvidenceState,
    val learningReceiptFingerprint: String,
    val canonicalFingerprint: String,
) {
    init {
        requireHostId(sessionId)
        requireHostFingerprint(presentationToken)
        requireHostId(evidenceRequestId)
        require(exactContentBinding.isNotBlank() && exactContentBinding.length <= 256)
        requireHostFingerprint(responseBinding)
        requireHostId(eventId)
        requireHostFingerprint(eventPayloadFingerprint)
        requireHostFingerprint(learningReceiptFingerprint)
        requireHostFingerprint(canonicalFingerprint)
        require(canonicalFingerprint == currentTutorTrustedAnswerCommitFingerprint(this))
    }

    fun matches(
        fence: CurrentTutorTrustedAnswerSubmissionFence,
        expectedContentBinding: String,
        expectedResponseForm: ReviewResponseForm,
        currentState: CurrentTutorSessionEvidenceState?,
    ): Boolean =
        sessionId == fence.sessionId &&
            presentationToken == fence.presentationToken &&
            evidenceRequestId == fence.evidenceRequestId &&
            exactContentBinding == expectedContentBinding &&
            responseForm == expectedResponseForm &&
            resultingEvidenceState == currentState &&
            resultingEvidenceState.scopeId == fence.expectedEvidenceState.scopeId &&
            resultingEvidenceState.activationFingerprint ==
                fence.expectedEvidenceState.activationFingerprint &&
            resultingEvidenceState.presentationFingerprint ==
                fence.expectedEvidenceState.presentationFingerprint &&
            if (duplicate) {
                resultingEvidenceState == fence.expectedEvidenceState
            } else {
                resultingEvidenceState.isStudentSubmissionSuccessorOf(
                    fence.expectedEvidenceState,
                )
            }
}

internal fun currentTutorTrustedAnswerCommitFingerprint(
    receipt: CurrentTutorTrustedAnswerCommitReceipt,
): String = CanonicalSha256("current-tutor-trusted-answer-commit-receipt-v1")
    .field("sessionId", receipt.sessionId)
    .field("presentationToken", receipt.presentationToken)
    .field("evidenceRequestId", receipt.evidenceRequestId)
    .field("exactContentBinding", receipt.exactContentBinding)
    .field("responseForm", receipt.responseForm.name)
    .field("responseBinding", receipt.responseBinding)
    .field("eventId", receipt.eventId)
    .field("eventPayloadFingerprint", receipt.eventPayloadFingerprint)
    .field("duplicate", receipt.duplicate)
    .field("scopeId", receipt.resultingEvidenceState.scopeId)
    .field("activationFingerprint", receipt.resultingEvidenceState.activationFingerprint)
    .field("presentationFingerprint", receipt.resultingEvidenceState.presentationFingerprint)
    .field("stateVersion", receipt.resultingEvidenceState.stateVersion)
    .field("stateFingerprint", receipt.resultingEvidenceState.stateFingerprint)
    .field("attemptOrdinal", receipt.resultingEvidenceState.attemptOrdinal)
    .field("hintCount", receipt.resultingEvidenceState.hintCount)
    .field("answerWasRevealed", receipt.resultingEvidenceState.answerWasRevealed)
    .field("learningReceiptFingerprint", receipt.learningReceiptFingerprint)
    .finish()

internal sealed interface CurrentTutorTrustedAnswerSubmissionResult {
    data class Committed(
        val receipt: CurrentTutorTrustedAnswerCommitReceipt,
    ) : CurrentTutorTrustedAnswerSubmissionResult

    data object Rejected : CurrentTutorTrustedAnswerSubmissionResult
}

/** One exact process-local submission capability. It carries no enumerable answer material. */
internal class CurrentTutorPreparedAnswerInteraction(
    val responseForm: ReviewResponseForm,
    val canonicalFingerprint: String,
    val exactContentBinding: String? = null,
    private val sourceCanonicalFingerprint: String = canonicalFingerprint,
    private val boundPresentationToken: String? = null,
    private val submitter: suspend (
        StudentTrustedReviewResponse,
        CurrentTutorTrustedAnswerSubmissionFence,
    ) -> CurrentTutorTrustedAnswerSubmissionResult,
) {
    init {
        requireHostFingerprint(canonicalFingerprint)
        requireHostFingerprint(sourceCanonicalFingerprint)
        exactContentBinding?.let { binding ->
            require(binding.isNotBlank() && binding.length <= 256)
        }
        boundPresentationToken?.let(::requireHostFingerprint)
    }

    suspend fun submit(
        response: StudentTrustedReviewResponse,
        fence: CurrentTutorTrustedAnswerSubmissionFence,
    ): CurrentTutorTrustedAnswerSubmissionResult =
        if (boundPresentationToken == fence.presentationToken) submitter(response, fence)
        else CurrentTutorTrustedAnswerSubmissionResult.Rejected

    fun bindToPresentation(presentationToken: String): CurrentTutorPreparedAnswerInteraction {
        requireHostFingerprint(presentationToken)
        check(boundPresentationToken == null) { "An answer interaction is already presentation-bound" }
        return CurrentTutorPreparedAnswerInteraction(
            responseForm = responseForm,
            canonicalFingerprint = CanonicalSha256("current-tutor-presentation-answer-capability-v1")
                .field("sourceCapability", sourceCanonicalFingerprint)
                .nullableField("exactContentBinding", exactContentBinding)
                .field("presentationToken", presentationToken)
                .finish(),
            exactContentBinding = exactContentBinding,
            sourceCanonicalFingerprint = sourceCanonicalFingerprint,
            boundPresentationToken = presentationToken,
            submitter = submitter,
        )
    }

    fun matchesSource(
        source: CurrentTutorPreparedAnswerInteraction?,
        presentationToken: String,
    ): Boolean = source != null &&
        boundPresentationToken == presentationToken &&
        responseForm == source.responseForm &&
        sourceCanonicalFingerprint == source.canonicalFingerprint &&
        exactContentBinding == source.exactContentBinding
}

internal fun interface CurrentTutorVerifiedMaterialAuthority {
    suspend fun resolve(
        query: CurrentTutorVerifiedMaterialQuery,
    ): CurrentTutorVerifiedMaterial?
}

/** Optional independent answer owner; implementations must not read the tutor model output key. */
internal fun interface CurrentTutorVerifiedAnswerAuthority {
    suspend fun resolve(query: CurrentTutorVerifiedMaterialQuery): CurrentTutorPreparedAnswerInteraction?
}

/** Fresh owner-derived evidence facts. Model output and feature actions cannot construct this. */
internal data class CurrentTutorSessionEvidenceState(
    val scopeId: String,
    val activationFingerprint: String,
    val presentationFingerprint: String,
    val stateVersion: Long,
    val stateFingerprint: String,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
) {
    init {
        requireHostId(scopeId)
        listOf(
            activationFingerprint,
            presentationFingerprint,
            stateFingerprint,
        ).forEach(::requireHostFingerprint)
        require(stateVersion >= 0L)
        require(attemptOrdinal in 0..17)
        require(hintCount in 0..32)
    }
}

internal data class CurrentTutorHintShownCommit(
    val sessionId: String,
    val expectedScopeId: String,
    val expectedQuestionDocumentId: String,
    val expectedQuestionRevisionNumber: Int,
    val expectedCycleOrdinal: Int,
    val expectedTurnOrdinal: Int,
    val expectedPresentationFingerprint: String,
    val modeVersion: Long,
    val slotToken: String,
    val occurredAtEpochMillis: Long,
) {
    init {
        requireHostId(sessionId)
        requireHostId(expectedScopeId)
        requireHostId(expectedQuestionDocumentId)
        require(expectedQuestionRevisionNumber > 0)
        require(expectedCycleOrdinal > 0)
        require(expectedTurnOrdinal > 0)
        requireHostFingerprint(expectedPresentationFingerprint)
        require(modeVersion >= 0L)
        requireHostFingerprint(slotToken)
        require(occurredAtEpochMillis >= 0L)
    }
}

internal enum class CurrentTutorHintShownCommitResult {
    RECORDED,
    DUPLICATE,
    REJECTED,
}

internal fun interface CurrentTutorSessionActivationOwner {
    suspend fun activate(activation: CurrentTutorSessionActivation): CurrentTutorSessionActivationResult

    suspend fun revokeLearningWrites(scopeId: String) = Unit

    suspend fun currentEvidenceState(sessionId: String): CurrentTutorSessionEvidenceState? = null

    suspend fun recordHintShown(
        commit: CurrentTutorHintShownCommit,
    ): CurrentTutorHintShownCommitResult = CurrentTutorHintShownCommitResult.REJECTED
}

internal fun interface CurrentTutorQuestionSource {
    suspend fun read(sessionId: String): ConfirmedTutorSession?
}

internal fun interface CurrentTutorModelTaskSource {
    suspend fun read(requestId: String): ModelTaskSnapshot?
}

internal fun interface CurrentTutorModeSource {
    suspend fun currentMode(sessionId: String): TutorExplanationModeSnapshot
}

internal fun interface CurrentTutorHostPolicyController {
    suspend fun update(command: TutorCurrentSessionPolicyUpdate): TutorCurrentSessionPolicySnapshot?

    suspend fun currentPolicy(sessionId: String): TutorCurrentSessionPolicySnapshot? = null
}

/** Narrow owner-only view; no model or UI object receives this authority. */
internal interface CurrentTutorLearningAuthorityStore {
    suspend fun openConversation(
        learnerId: String,
        conversationId: String,
        generation: Long,
    ): TutorConversation?

    suspend fun createConversation(command: CreateTutorConversationCommand): TutorConversation

    suspend fun openTurn(learnerId: String, turnReceiptId: String): TutorTurnReceipt?

    suspend fun allocateTurn(command: AllocateTutorTurnCommand): TutorTurnReceipt

    suspend fun prepareEvidenceRequest(command: PrepareTutorEvidenceCommand): TutorEvidenceRequest
}

