package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeContextRepository
import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeReferenceBatchRequest
import com.tingyun.smartmistakebook.core.data.openresponse.openResponseEvaluatorExecutionFingerprint
import com.tingyun.smartmistakebook.core.domain.TutorExplanationModeSnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicySnapshot
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicyUpdate
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentTrustedSavedAnswerRulePort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerLease
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerPresentationRequest
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedSavedAnswerSubmissionResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentTrustedReviewResponse
import com.tingyun.smartmistakebook.core.student.mistake.database.TRUSTED_SAVED_ANSWER_PRESENTATION_V2_PREFIX
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.QuestionDocumentMarkdownProjection
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeGuidance
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.model.VerifiedOpenResponseEvaluationTeachingReference
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process owner for the current UI-selected policy. It starts fail-closed for long-term writes;
 * model input can only repeat an owner-issued epoch and can never advance one.
 */
internal class ProductionCurrentTutorPolicyOwner(
    private val questions: CurrentTutorQuestionSource,
    private val persistedPolicies: CurrentTutorPersistedPolicySource =
        CurrentTutorPersistedPolicySource { _ -> null },
    private val persistPolicy: CurrentTutorPersistedPolicySink =
        CurrentTutorPersistedPolicySink { true },
) : CurrentTutorModeSource,
    CurrentTutorLearningWriteAuthority,
    CurrentTutorHostPolicyController {
    private val monitor = Any()
    private var mode: TutorExplanationModeSnapshot? = null
    private val learningWrites = ConcurrentHashMap<String, CurrentTutorLearningWriteAuthoritySnapshot>()
    private val visualIntents = ConcurrentHashMap<String, VisualIntentEpoch>()

    override suspend fun currentMode(sessionId: String): TutorExplanationModeSnapshot {
        requireHostPolicyId(sessionId)
        val persisted = persistedPolicies.current(sessionId)
        val next = synchronized(monitor) {
            seedPersistedPolicy(persisted)
            mode ?: DEFAULT_MODE.also { mode = it }
        }
        return next
    }

    override suspend fun current(sessionId: String): CurrentTutorLearningWriteAuthoritySnapshot? {
        requireHostPolicyId(sessionId)
        val question = questions.read(sessionId)
            ?.takeIf { current ->
                current.sessionId == sessionId && !current.isEndedWithoutSave
            }
            ?: return null
        val persisted = persistedPolicies.current(sessionId)
        return synchronized(monitor) {
            seedPersistedPolicy(persisted)
            learningWrites[question.sessionId] ?: DEFAULT_WRITE.copy(sessionId = question.sessionId)
        }
    }

    override suspend fun update(
        command: TutorCurrentSessionPolicyUpdate,
    ): TutorCurrentSessionPolicySnapshot? {
        val question = questions.read(command.sessionId)
            ?.takeIf { current ->
                current.sessionId == command.sessionId && !current.isEndedWithoutSave
            }
            ?: return null
        val persisted = persistedPolicies.current(command.sessionId)
        val next = synchronized(monitor) {
            seedPersistedPolicy(persisted)
            val currentMode = mode ?: DEFAULT_MODE
            val nextMode = if (currentMode.mode != command.explanationMode) {
                check(currentMode.modeVersion < Long.MAX_VALUE) {
                    "Tutor explanation policy version overflow"
                }
                TutorExplanationModeSnapshot(
                    mode = command.explanationMode,
                    modeVersion = currentMode.modeVersion + 1L,
                )
            } else {
                currentMode
            }
            val currentWrite = learningWrites[question.sessionId]
                ?: DEFAULT_WRITE.copy(sessionId = question.sessionId)
            val nextWrite = if (currentWrite.allowed == command.learningWritesAllowed) {
                currentWrite
            } else {
                check(currentWrite.permissionVersion < Long.MAX_VALUE) {
                    "Tutor learning-write policy version overflow"
                }
                currentWrite.copy(
                    allowed = command.learningWritesAllowed,
                    permissionVersion = currentWrite.permissionVersion + 1L,
                )
            }
            val currentVisual = visualIntents[question.sessionId] ?: VisualIntentEpoch(
                TutorCurrentSessionVisualIntent.NONE,
                0L,
            )
            val nextVisual = if (currentVisual.intent == command.visualIntent) {
                currentVisual
            } else {
                check(currentVisual.version < Long.MAX_VALUE) {
                    "Tutor visual-intent policy version overflow"
                }
                VisualIntentEpoch(command.visualIntent, currentVisual.version + 1L)
            }
            TutorCurrentSessionPolicySnapshot(
                sessionId = question.sessionId,
                explanationMode = nextMode.mode,
                modeVersion = nextMode.modeVersion,
                learningWritesAllowed = nextWrite.allowed,
                learningWritePermissionVersion = nextWrite.permissionVersion,
                visualIntent = nextVisual.intent,
                visualIntentVersion = nextVisual.version,
            )
        }
        if (!persistPolicy.persist(next)) return null
        synchronized(monitor) {
            seedPersistedPolicy(next)
        }
        return next
    }

    override suspend fun currentPolicy(sessionId: String): TutorCurrentSessionPolicySnapshot? {
        val question = questions.read(sessionId)
            ?.takeIf { current -> current.sessionId == sessionId && !current.isEndedWithoutSave }
            ?: return null
        val persisted = persistedPolicies.current(sessionId)
        return synchronized(monitor) {
            seedPersistedPolicy(persisted)
            val currentMode = mode ?: DEFAULT_MODE.also { mode = it }
            val currentWrite = learningWrites[question.sessionId]
                ?: DEFAULT_WRITE.copy(sessionId = question.sessionId).also {
                    learningWrites[question.sessionId] = it
                }
            val currentVisual = visualIntents[question.sessionId]
                ?: VisualIntentEpoch(TutorCurrentSessionVisualIntent.NONE, 0L).also {
                    visualIntents[question.sessionId] = it
                }
            TutorCurrentSessionPolicySnapshot(
                sessionId = question.sessionId,
                explanationMode = currentMode.mode,
                modeVersion = currentMode.modeVersion,
                learningWritesAllowed = currentWrite.allowed,
                learningWritePermissionVersion = currentWrite.permissionVersion,
                visualIntent = currentVisual.intent,
                visualIntentVersion = currentVisual.version,
            )
        }
    }

    private fun seedPersistedPolicy(persisted: TutorCurrentSessionPolicySnapshot?) {
        if (persisted == null) return
        val currentMode = mode
        if (currentMode == null || currentMode.modeVersion < persisted.modeVersion) {
            mode = TutorExplanationModeSnapshot(
                mode = persisted.explanationMode,
                modeVersion = persisted.modeVersion,
            )
        }
        val currentWrite = learningWrites[persisted.sessionId]
        if (
            currentWrite == null ||
            currentWrite.permissionVersion < persisted.learningWritePermissionVersion
        ) {
            learningWrites[persisted.sessionId] = CurrentTutorLearningWriteAuthoritySnapshot(
                sessionId = persisted.sessionId,
                allowed = persisted.learningWritesAllowed,
                permissionVersion = persisted.learningWritePermissionVersion,
            )
        }
        val currentVisual = visualIntents[persisted.sessionId]
        if (currentVisual == null || currentVisual.version < persisted.visualIntentVersion) {
            visualIntents[persisted.sessionId] = VisualIntentEpoch(
                intent = persisted.visualIntent,
                version = persisted.visualIntentVersion,
            )
        }
    }

    private data class VisualIntentEpoch(
        val intent: TutorCurrentSessionVisualIntent,
        val version: Long,
    )

    private companion object {
        val DEFAULT_MODE = TutorExplanationModeSnapshot(TutorExplanationMode.DIRECT, 0L)
        val DEFAULT_WRITE = CurrentTutorLearningWriteAuthoritySnapshot(
            sessionId = "unbound-current-tutor",
            allowed = false,
            permissionVersion = 0L,
        )
    }
}

internal fun interface CurrentTutorPersistedPolicySource {
    suspend fun current(sessionId: String): TutorCurrentSessionPolicySnapshot?
}

internal fun interface CurrentTutorPersistedPolicySink {
    suspend fun persist(snapshot: TutorCurrentSessionPolicySnapshot): Boolean
}

/** Independent saved-answer bridge. It cannot read model output and fails closed on any mismatch. */
internal class ProductionCurrentTutorVerifiedAnswerAuthority(
    private val trustedAnswers: LearnerBoundStudentTrustedSavedAnswerRulePort,
    private val committer: CurrentTutorTrustedAnswerCommitter,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : CurrentTutorVerifiedAnswerAuthority {
    private val preparedLock = Mutex()
    private val prepared = LinkedHashMap<String, CurrentTutorPreparedAnswerInteraction>()

    override suspend fun resolve(
        query: CurrentTutorVerifiedMaterialQuery,
    ): CurrentTutorPreparedAnswerInteraction? {
        val session = query.session
        val revisionId = session.savedProblemRevisionId ?: return null
        val errorBookEntryId = session.errorBookEntryId ?: return null
        if (!session.isSaved || query.learnerId != trustedAnswers.learnerId) return null
        val responseForm = query.visibleAnswerResponseForm() ?: return null
        val exactContentBinding = CurrentTutorTrustedAnswerContentBinding.forQuery(
            query = query,
            responseForm = responseForm,
        ) ?: return null
        val cacheKey = CanonicalSha256("current-tutor-answer-capability-cache-v2")
            .field("learnerId", query.learnerId)
            .field("sessionId", session.sessionId)
            .field("problemRevisionId", revisionId)
            .field("errorBookEntryId", errorBookEntryId)
            .field("contentBinding", exactContentBinding)
            .finish()
        preparedLock.withLock { prepared[cacheKey] }?.let { return it }
        val presentationRequest = StudentTrustedSavedAnswerPresentationRequest(
            problemRevisionId = revisionId,
            errorBookEntryId = errorBookEntryId,
            questionGeneration = session.draftRevisionNumber.toLong(),
            questionVersion = exactContentBinding,
            responseForm = responseForm,
        )
        val leaseHolder = RenewableTrustedAnswerLease(
            request = presentationRequest,
            initialLease = trustedAnswers.issueExactLease(presentationRequest) ?: return null,
        )
        val candidate = CurrentTutorPreparedAnswerInteraction(
            responseForm = responseForm,
            canonicalFingerprint = CanonicalSha256("current-tutor-answer-capability-v2")
                .field("cacheKey", cacheKey)
                .finish(),
            exactContentBinding = exactContentBinding,
            submitter = { response, fence ->
                if (fence.sessionId != session.sessionId) {
                    CurrentTutorTrustedAnswerSubmissionResult.Rejected
                } else {
                    val event = query.toTrustedAnswerEvent(response, fence.hitProof)
                    val evaluation = event?.let { leaseHolder.evaluate(response) }
                    if (event == null || evaluation == null) {
                        CurrentTutorTrustedAnswerSubmissionResult.Rejected
                    } else {
                        committer.commit(
                            CurrentTutorTrustedAnswerCommitCommand(
                                learnerId = query.learnerId,
                                sessionId = session.sessionId,
                                presentationToken = fence.presentationToken,
                                exactContentBinding = exactContentBinding,
                                evidenceRequestId = fence.evidenceRequestId,
                                expectedEvidenceState = fence.expectedEvidenceState,
                                evaluation = evaluation.evaluation,
                                event = event,
                                occurredAtEpochMillis = fence.occurredAtEpochMillis,
                            ),
                        )
                    }
                }
            },
        )
        return preparedLock.withLock {
            prepared[cacheKey] ?: candidate.also { value ->
                if (prepared.size >= MAX_PREPARED_ANSWER_CAPABILITIES) {
                    prepared.entries.firstOrNull()?.key?.let(prepared::remove)
                }
                prepared[cacheKey] = value
            }
        }
    }

    private inner class RenewableTrustedAnswerLease(
        private val request: StudentTrustedSavedAnswerPresentationRequest,
        initialLease: StudentTrustedSavedAnswerLease,
    ) {
        private val lock = Mutex()
        private var lease: StudentTrustedSavedAnswerLease = initialLease

        suspend fun evaluate(
            response: StudentTrustedReviewResponse,
        ): StudentTrustedSavedAnswerSubmissionResult.Accepted? = lock.withLock {
            val now = nowEpochMillis()
            if (now > lease.validThroughEpochMillis) {
                lease = trustedAnswers.issueExactLease(request) ?: return@withLock null
            }
            trustedAnswers.submitResponse(lease, response)
                as? StudentTrustedSavedAnswerSubmissionResult.Accepted
        }
    }
}

private fun CurrentTutorVerifiedMaterialQuery.toTrustedAnswerEvent(
    response: StudentTrustedReviewResponse,
    hitProof: TutorVisualHitProof?,
): CurrentTutorTrustedAnswerEvent? = when (response) {
    is StudentTrustedReviewResponse.Choice -> {
        if (hitProof != null) return null
        val item = output.plan.diagnosticItem
        val directive = proposedAnswerContent()?.directive as? TutorInteractionDirective.Choices
        if ((item == null) == (directive == null)) return null
        val prompt = item?.promptMarkdown ?: item?.stemMarkdown ?: directive?.promptMarkdown
            ?: return null
        val selected = item?.choices
            ?.singleOrNull { choice -> choice.id == response.choiceId }
            ?.let { choice -> choice.id to choice.markdown }
            ?: directive?.choices
                ?.singleOrNull { choice -> choice.id == response.choiceId }
                ?.let { choice -> choice.id to choice.labelMarkdown }
            ?: return null
        CurrentTutorTrustedAnswerEvent.Choice(
            diagnosticStemMarkdown = prompt,
            selectedChoiceId = selected.first,
            selectedChoiceMarkdown = selected.second,
            feedbackMarkdown = TRUSTED_SAVED_ANSWER_RECORDED_FEEDBACK,
        )
    }
    is StudentTrustedReviewResponse.VisualTarget -> {
        val proof = hitProof?.takeIf { it.selectedTargetId == response.targetId } ?: return null
        val proposal = proposedAnswerContent() ?: return null
        if (proposal.directive !is TutorInteractionDirective.VisualTarget) return null
        val scene = proposal.visualScene ?: return null
        val step = scene.steps.getOrNull(proof.stepIndex) ?: return null
        val eligibleTargets = buildSet {
            addAll(step.focusElementIds)
            step.primaryRelationElementId?.let(::add)
        }
        val elementIds = scene.elements.mapTo(hashSetOf()) { element -> element.elementId }
        if (
            proof.presentation.ownerModelTaskRequestId != task.request.requestId ||
            proof.presentation.sourceKind != TutorVisualSceneSourceKind.INLINE ||
            proof.presentation.sceneTaskRequestId != task.request.requestId ||
            proof.presentation.sceneId != scene.sceneId ||
            proof.presentation.sceneFingerprint != TutorVisualSceneFingerprint.of(scene) ||
            proof.selectedTargetId !in eligibleTargets ||
            proof.selectedTargetId !in elementIds
        ) return null
        CurrentTutorTrustedAnswerEvent.VisualTarget(proof)
    }
    is StudentTrustedReviewResponse.Numeric -> null
}

/**
 * Canonical binding shared by trusted credential issuers and the Host verifier. A repeated choice
 * id or target id is insufficient: the exact task, checkpoint text/labels, and scene step must
 * match. Unsupported or ambiguous visual scenes deliberately have no binding.
 */
internal object CurrentTutorTrustedAnswerContentBinding {
    fun forQuery(
        query: CurrentTutorVerifiedMaterialQuery,
        responseForm: ReviewResponseForm,
    ): String? = when (responseForm) {
        ReviewResponseForm.CHOICE -> choice(query)
        ReviewResponseForm.VISUAL_TARGET -> visual(query)
        ReviewResponseForm.NUMERIC -> null
    }

    private fun choice(query: CurrentTutorVerifiedMaterialQuery): String? {
        val item = query.output.plan.diagnosticItem
        val proposal = query.proposedAnswerContent()
        val directive = proposal?.directive as? TutorInteractionDirective.Choices
        if ((item == null) == (directive == null)) return null
        val promptMarkdown = item?.promptMarkdown ?: item?.stemMarkdown ?: directive?.promptMarkdown
            ?: return null
        val choices = item?.choices?.map { choice -> choice.id to choice.markdown }
            ?: directive?.choices?.map { choice -> choice.id to choice.labelMarkdown }
            ?: return null
        if (choices.size !in 2..4 || choices.map { it.first }.distinct().size != choices.size) {
            return null
        }
        val fingerprint = CanonicalSha256("current-tutor-visible-choice-content-v2")
            .base(query)
            .field("source", proposal?.source ?: "legacy-diagnostic")
            .nullableField("diagnosticId", item?.id)
            .field("promptMarkdown", promptMarkdown)
            .field(
                "choices",
                choices.joinToString("\u001e") { (id, label) ->
                    "$id\u001f$label"
                },
            )
            .finish()
        return TRUSTED_SAVED_ANSWER_PRESENTATION_V2_PREFIX + fingerprint
    }

    private fun visual(query: CurrentTutorVerifiedMaterialQuery): String? {
        val proposal = query.proposedAnswerContent() ?: return null
        val directive = proposal.directive as? TutorInteractionDirective.VisualTarget
            ?: return null
        val scene = proposal.visualScene ?: return null
        val matchingSteps = scene.steps.withIndex().filter { (_, step) ->
            val targets = buildSet {
                addAll(step.focusElementIds)
                step.primaryRelationElementId?.let(::add)
            }
            directive.targetId in targets
        }
        val exactStep = matchingSteps.singleOrNull() ?: return null
        val elementIds = scene.elements.mapTo(hashSetOf()) { element -> element.elementId }
        if (directive.targetId !in elementIds) return null
        val fingerprint = CanonicalSha256("current-tutor-visible-visual-content-v2")
            .base(query)
            .field("promptMarkdown", directive.promptMarkdown)
            .field("directiveTargetId", directive.targetId)
            .field("sceneFingerprint", TutorVisualSceneFingerprint.of(scene))
            .field("stepIndex", exactStep.index)
            .finish()
        return TRUSTED_SAVED_ANSWER_PRESENTATION_V2_PREFIX + fingerprint
    }

    private fun CanonicalSha256.base(
        query: CurrentTutorVerifiedMaterialQuery,
    ): CanonicalSha256 =
        field("learnerId", query.learnerId)
            .field("sessionId", query.session.sessionId)
            .field("questionRevision", query.session.draftRevisionNumber)
            .field("questionDocument", CapturedQuestionDocumentFingerprint.of(query.session.questionDocument))
            .field("taskRequestFingerprint", query.task.requestFingerprint)
            .field("taskRequestId", query.task.request.requestId)
            .field("cycleOrdinal", query.output.cycleOrdinal)
            .field("turnOrdinal", query.output.turnOrdinal)
            .field("explanationMode", query.input.explanationMode.name)
            .field("modeVersion", query.input.modeVersion)
            .field("learningWritesAllowed", query.input.allowLongTermLearningWrites)
            .field(
                "learningWritePermissionVersion",
                query.input.learningWritePermissionVersion,
            )
            .field(
                "targetedEvidenceLabels",
                query.output.plan.targetedEvidenceLabels.sorted().joinToString("\u001f"),
            )
}

private data class CurrentTutorProposedAnswerContent(
    val directive: TutorInteractionDirective,
    val visualScene: TutorVisualDocumentScene?,
    val source: String,
)

private fun CurrentTutorVerifiedMaterialQuery.proposedAnswerContent():
    CurrentTutorProposedAnswerContent? {
    val retained = output.plan.guidedInteractionProposal
    if (retained != null) {
        if (output.plan.diagnosticItem != null) return null
        val visible = output.plan.interactionDirective
        if (visible !is TutorInteractionDirective.FreeResponse && visible != retained.directive) {
            return null
        }
        return CurrentTutorProposedAnswerContent(
            directive = retained.directive,
            visualScene = retained.visualScene,
            source = "guided-proposal",
        )
    }
    val visible = output.plan.interactionDirective ?: return null
    return when (visible) {
        is TutorInteractionDirective.Choices -> CurrentTutorProposedAnswerContent(
            directive = visible,
            visualScene = null,
            source = "legacy-directive",
        )
        is TutorInteractionDirective.VisualTarget -> CurrentTutorProposedAnswerContent(
            directive = visible,
            visualScene = output.plan.visualScene as? TutorVisualDocumentScene,
            source = "legacy-directive",
        )
        TutorInteractionDirective.Continue,
        is TutorInteractionDirective.FreeResponse,
        -> null
    }
}

private fun CurrentTutorVerifiedMaterialQuery.visibleAnswerResponseForm(): ReviewResponseForm? =
    when {
        output.plan.diagnosticItem != null ||
            proposedAnswerContent()?.directive is TutorInteractionDirective.Choices ->
            ReviewResponseForm.CHOICE
        proposedAnswerContent()?.directive is TutorInteractionDirective.VisualTarget ->
            ReviewResponseForm.VISUAL_TARGET
        else -> null
    }

/**
 * Reconstructs knowledge authority from the active reviewed catalog. Model labels and model output
 * are never proof: only exact nodes already present in the host input or an exact reviewed-name
 * match may be verified.
 */
internal class ProductionCurrentTutorVerifiedMaterialAuthority(
    private val knowledgeContext: ReviewedProblemKnowledgeContextRepository,
    private val answerAuthority: CurrentTutorVerifiedAnswerAuthority? = null,
) : CurrentTutorVerifiedMaterialAuthority {
    override suspend fun resolve(
        query: CurrentTutorVerifiedMaterialQuery,
    ): CurrentTutorVerifiedMaterial? {
        val questionFingerprint =
            OpenResponseEvaluationTaskFingerprints.question(query.input.questionDocument)
        val verified = if (query.currentStepKnowledgeAuthorityRequired) {
            verifyExactHostReferences(
                query = query,
                contexts = knowledgeContext.read(
                    subject = query.subject,
                    questionText = QuestionDocumentMarkdownProjection.project(
                        query.input.questionDocument,
                    ),
                ),
            )
        } else {
            null
        }
        if (query.currentStepKnowledgeAuthorityRequired && verified == null) return null
        val proofs = verified?.proofs.orEmpty()
        val references = verified?.teachingReferences.orEmpty()
        val answerInteraction = if (verified == null) null else answerAuthority?.resolve(query)
        val isOpenResponse =
            query.output.plan.interactionDirective is TutorInteractionDirective.FreeResponse
        val prohibitedEvaluatorExecutionFingerprint = if (isOpenResponse) {
            query.task.provider?.let(::openResponseEvaluatorExecutionFingerprint)
        } else {
            null
        }
        return CurrentTutorVerifiedMaterial(
            problemAnchorId = "current-question:" + CanonicalSha256(
                "current-tutor-problem-anchor-v1",
            )
                .field("learnerId", query.learnerId)
                .field("sessionId", query.session.sessionId)
                .field("questionRevision", query.session.draftRevisionNumber)
                .field("questionFingerprint", questionFingerprint)
                .finish(),
            problemFingerprint = questionFingerprint,
            problemFamilyFingerprint = CanonicalSha256("current-tutor-problem-family-v1")
                .field("subject", query.subject.name)
                .field("questionFingerprint", questionFingerprint)
                .finish(),
            attributionPolicyVersion = ATTRIBUTION_POLICY_VERSION,
            responsePolicyVersion = RESPONSE_POLICY_VERSION,
            rubricCanonicalFingerprint = CanonicalSha256(
                "current-tutor-evaluation-authority-v2",
            )
                .field("questionFingerprint", questionFingerprint)
                .field(
                    "verifiedKnowledge",
                    proofs.map { proof -> proof.ref.canonicalFingerprint }
                        .sorted()
                        .joinToString("|"),
                )
                .nullableField(
                    "trustedAnswerCapability",
                    answerInteraction?.canonicalFingerprint,
                )
                .finish(),
            verifiedKnowledgeProofs = proofs,
            teachingReferences = references,
            evaluator = OpenResponseEvaluatorKind.RUBRIC,
            evaluatorPolicyFingerprint = CanonicalSha256("current-tutor-evaluator-policy-v2")
                .field("policyVersion", EVALUATOR_POLICY_VERSION)
                .field(
                    "independentExecutionRequired",
                    prohibitedEvaluatorExecutionFingerprint != null,
                )
                .finish(),
            prohibitedEvaluatorExecutionFingerprint =
                prohibitedEvaluatorExecutionFingerprint,
            trustedAnswerInteraction = answerInteraction,
            learningEvidenceEligible = verified != null && (
                answerInteraction != null ||
                    isOpenResponse && prohibitedEvaluatorExecutionFingerprint != null
                ),
        )
    }

    private suspend fun verifyExactHostReferences(
        query: CurrentTutorVerifiedMaterialQuery,
        contexts: List<KnowledgeBaseNodeContext>,
    ): VerifiedHostKnowledge? {
        if (contexts.isEmpty()) return null
        val contextsById = contexts.associateBy(KnowledgeBaseNodeContext::knowledgeNodeId)
        val targetLabel = query.output.plan.targetedEvidenceLabels.singleOrNull() ?: return null
        val guidance = query.input.teachingConstraints.singleOrNull { value ->
            value.label == targetLabel && value.constraint == TutorTeachingConstraint.MAY_GUIDE
        } ?: return null
        if (query.input.teachingConstraints.count {
                it.constraint == TutorTeachingConstraint.MAY_GUIDE
            } != 1
        ) return null
        val exactId = contexts.singleOrNull { node -> node.matchesGuidanceLabel(guidance.label) }
            ?.knowledgeNodeId
            ?: return null
        if (exactId !in contextsById) return null
        val exactIds = listOf(exactId)
        val proofs = knowledgeContext.verifyExactReferences(
            ReviewedProblemKnowledgeReferenceBatchRequest(
                subject = query.subject,
                knowledgeBaseNodes = contexts,
                exactKnowledgeNodeIds = exactIds,
            ),
        )
        if (
            proofs.size != exactIds.size ||
            proofs.any { proof ->
                proof.ref.subject != query.subject ||
                    proof.ref.knowledgeNodeId !in exactIds
            }
        ) return null
        val references = proofs.map { proof ->
            VerifiedOpenResponseEvaluationTeachingReference(
                proof = proof,
                label = guidance.label,
                constraint = TutorTeachingConstraint.MAY_GUIDE,
            )
        }
        return VerifiedHostKnowledge(proofs, references)
    }

    private data class VerifiedHostKnowledge(
        val proofs: List<com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof>,
        val teachingReferences: List<VerifiedOpenResponseEvaluationTeachingReference>,
    )

    private companion object {
        const val MAX_VERIFIED_REFERENCES = 24
        const val ATTRIBUTION_POLICY_VERSION = "current-tutor-attribution-v1"
        const val RESPONSE_POLICY_VERSION = "current-tutor-response-v1"
        const val EVALUATOR_POLICY_VERSION = "current-tutor-independent-evaluator-v2"
    }
}

private fun KnowledgeBaseNodeContext.matchesGuidanceLabel(label: String): Boolean =
    canonicalName == label || label in aliases

private const val MAX_PREPARED_ANSWER_CAPABILITIES = 64
private const val TRUSTED_SAVED_ANSWER_RECORDED_FEEDBACK = "已记录"

private fun requireHostPolicyId(value: String) {
    require(
        value.isNotBlank() && value == value.trim() && value.length <= 256 &&
            value.none(Char::isISOControl),
    )
}
