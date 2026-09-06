package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceLevel
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeEvidence
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRecency
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorDebriefInput
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorQuestionLearningEvidence
import com.tingyun.smartmistakebook.core.model.TutorQuestionReviewStatus
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.isModelEgressApprovalFresh
import com.tingyun.smartmistakebook.core.model.requiresEgressAuthorizationRenewal
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val TUTOR_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_PLAN
internal const val TUTOR_RESPOND_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_RESPOND
internal const val TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION =
    ModelPromptPolicyVersions.TUTOR_VISUAL_GENERATE
internal const val TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION =
    ModelPromptPolicyVersions.TUTOR_VISUAL_REVIEW

internal fun ModelTaskStatus.isTutorExecutionPending(): Boolean = when (this) {
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
    -> true
    else -> false
}

internal fun ModelTaskSnapshot.toPlanAnswerExposureKey(): TutorAnswerExposureKey? {
    val input = request.input as? TutorPlanInput ?: return null
    return TutorAnswerExposureKey(
        sessionId = input.sessionId,
        questionDocumentId = input.questionDocument.id,
        revisionNumber = input.draftRevisionNumber,
        cycleOrdinal = input.cycleOrdinal,
        turnOrdinal = input.turnOrdinal,
        surfaceKind = TutorAnswerExposureSurfaceKind.PLAN_SOLUTION,
        modelTaskRequestId = request.requestId,
    )
}

internal fun ModelTaskSnapshot.toRespondAnswerExposureKey(): TutorAnswerExposureKey? {
    val input = request.input as? TutorRespondInput ?: return null
    return TutorAnswerExposureKey(
        sessionId = input.sessionId,
        questionDocumentId = input.questionDocument.id,
        revisionNumber = input.draftRevisionNumber,
        cycleOrdinal = input.cycleOrdinal,
        turnOrdinal = input.turnOrdinal,
        surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
        modelTaskRequestId = request.requestId,
        responseOrdinal = input.responseOrdinal,
    )
}

/**
 * A short-lived approval owned by the current tutor composition only.
 *
 * Persisted manifests prove exactly what an earlier request contained, but deliberately do not
 * recreate this lease after the screen or process is rebuilt.
 */
internal data class TutorCompositionEgressLease(
    val sessionId: String,
    val revisionNumber: Int,
    val questionDocumentId: String,
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val planPromptPolicyVersion: String,
    val respondPromptPolicyVersion: String,
    val visualGeneratePromptPolicyVersion: String,
    val visualReviewPromptPolicyVersion: String,
    val planApprovedAtEpochMillis: Long? = null,
    val respondApprovedAtEpochMillis: Long? = null,
    val visualGenerateApprovedAtEpochMillis: Long? = null,
    val visualReviewApprovedAtEpochMillis: Long? = null,
) {
    fun approvedAtFor(
        question: TutorQuestionContext,
        provider: ProviderCapabilitySnapshot,
        taskKind: ModelTaskKind,
        nowEpochMillis: Long,
    ): Long? {
        val policyMatches = when (taskKind) {
            ModelTaskKind.TUTOR_PLAN -> planPromptPolicyVersion == TUTOR_PROMPT_POLICY_VERSION
            ModelTaskKind.TUTOR_RESPOND ->
                respondPromptPolicyVersion == TUTOR_RESPOND_PROMPT_POLICY_VERSION
            ModelTaskKind.TUTOR_VISUAL_GENERATE ->
                visualGeneratePromptPolicyVersion == TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION
            ModelTaskKind.TUTOR_VISUAL_REVIEW ->
                visualReviewPromptPolicyVersion == TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION
            else -> false
        }
        val taskApprovedAt = when (taskKind) {
            ModelTaskKind.TUTOR_PLAN -> planApprovedAtEpochMillis
            ModelTaskKind.TUTOR_RESPOND -> respondApprovedAtEpochMillis
            ModelTaskKind.TUTOR_VISUAL_GENERATE -> visualGenerateApprovedAtEpochMillis
            ModelTaskKind.TUTOR_VISUAL_REVIEW -> visualReviewApprovedAtEpochMillis
            else -> null
        }
        return taskApprovedAt?.takeIf {
            provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                provider.supports(taskKind) &&
                sessionId == question.sessionId &&
                revisionNumber == question.revisionNumber &&
                questionDocumentId == question.questionDocument.document.id &&
                providerId == provider.providerId &&
                modelId == provider.modelId &&
                providerConfigurationVersion == provider.providerConfigurationVersion &&
                policyMatches &&
                isModelEgressApprovalFresh(it, nowEpochMillis)
        }
    }

    companion object {
        fun grant(
            question: TutorQuestionContext,
            provider: ProviderCapabilitySnapshot,
            approvedAtEpochMillis: Long,
            taskKinds: Set<ModelTaskKind> = setOf(
                ModelTaskKind.TUTOR_PLAN,
                ModelTaskKind.TUTOR_RESPOND,
                ModelTaskKind.TUTOR_VISUAL_GENERATE,
                ModelTaskKind.TUTOR_VISUAL_REVIEW,
            ),
        ): TutorCompositionEgressLease {
            require(provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER)
            return TutorCompositionEgressLease(
                sessionId = question.sessionId,
                revisionNumber = question.revisionNumber,
                questionDocumentId = question.questionDocument.document.id,
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                planPromptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                respondPromptPolicyVersion = TUTOR_RESPOND_PROMPT_POLICY_VERSION,
                visualGeneratePromptPolicyVersion =
                    TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION,
                visualReviewPromptPolicyVersion =
                    TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION,
                planApprovedAtEpochMillis = approvedAtEpochMillis.takeIf {
                    ModelTaskKind.TUTOR_PLAN in taskKinds
                },
                respondApprovedAtEpochMillis = approvedAtEpochMillis.takeIf {
                    ModelTaskKind.TUTOR_RESPOND in taskKinds
                },
                visualGenerateApprovedAtEpochMillis = approvedAtEpochMillis.takeIf {
                    ModelTaskKind.TUTOR_VISUAL_GENERATE in taskKinds
                },
                visualReviewApprovedAtEpochMillis = approvedAtEpochMillis.takeIf {
                    ModelTaskKind.TUTOR_VISUAL_REVIEW in taskKinds
                },
            )
        }
    }
}

internal fun ModelTaskSnapshot.matchesTutorProvider(
    provider: ProviderCapabilitySnapshot,
): Boolean {
    val approvedProvider = request.egressManifest
    return if (approvedProvider != null) {
        approvedProvider.providerId == provider.providerId &&
            approvedProvider.modelId == provider.modelId &&
            approvedProvider.providerConfigurationVersion == provider.providerConfigurationVersion
    } else {
        this.provider?.let { executedBy ->
            executedBy.providerId == provider.providerId &&
                executedBy.modelId == provider.modelId &&
                executedBy.providerConfigurationVersion == provider.providerConfigurationVersion
        } == true
    }
}

internal fun ModelTaskSnapshot.coversCurrentTutorDisclosure(
    provider: ProviderCapabilitySnapshot,
    taskKind: ModelTaskKind,
): Boolean {
    if (!matchesTutorProvider(provider)) return false
    val manifest = request.egressManifest ?: return false
    val promptPolicyVersion: String
    val disclosedData: Set<com.tingyun.smartmistakebook.core.model.ModelEgressDataClass>
    val prohibitedData: Set<com.tingyun.smartmistakebook.core.model.ModelEgressDataClass>
    when (taskKind) {
        ModelTaskKind.TUTOR_PLAN -> {
            promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE
            prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA
        }
        ModelTaskKind.TUTOR_RESPOND -> {
            promptPolicyVersion = TUTOR_RESPOND_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE
            prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA
        }
        ModelTaskKind.TUTOR_VISUAL_GENERATE -> {
            promptPolicyVersion = TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.tutorVisualGenerateDisclosure(
                includesSelectedRegion = manifest.assets.any { asset ->
                    asset.selectedRegion != null
                },
            )
            prohibitedData =
                com.tingyun.smartmistakebook.core.model.ModelEgressDataClass.entries.toSet() -
                    disclosedData
        }
        ModelTaskKind.TUTOR_VISUAL_REVIEW -> {
            promptPolicyVersion = TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.tutorVisualReviewDisclosure(
                includesSelectedRegion = manifest.assets.any { asset ->
                    asset.selectedRegion != null
                },
            )
            prohibitedData =
                com.tingyun.smartmistakebook.core.model.ModelEgressDataClass.entries.toSet() -
                    disclosedData
        }
        else -> return false
    }
    return manifest.authorizedTaskKinds == setOf(taskKind) &&
        manifest.promptPolicyVersion == promptPolicyVersion &&
        manifest.disclosedData == disclosedData &&
        manifest.prohibitedData == prohibitedData
}

internal fun ModelTaskSnapshot.requiresFreshTutorApproval(
    provider: ProviderCapabilitySnapshot,
): Boolean {
    val failureCode = failure?.code ?: return false
    return !coversCurrentTutorDisclosure(provider, request.input.kind) ||
        failureCode.requiresEgressAuthorizationRenewal() ||
        (failureCode.requiresModelSettings() && !matchesTutorProvider(provider))
}

internal fun tutorRecoveryRequestId(
    failedRequest: ModelTaskRequest,
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
): String {
    val taskName = when (failedRequest.input.kind) {
        ModelTaskKind.TUTOR_PLAN -> "plan"
        ModelTaskKind.TUTOR_RESPOND -> "respond"
        ModelTaskKind.TUTOR_VISUAL_GENERATE -> "visual-generate"
        ModelTaskKind.TUTOR_VISUAL_REVIEW -> "visual-review"
        else -> error("Only tutor tasks can be recovered here")
    }
    val promptPolicy = when (failedRequest.input.kind) {
        ModelTaskKind.TUTOR_PLAN -> TUTOR_PROMPT_POLICY_VERSION
        ModelTaskKind.TUTOR_RESPOND -> TUTOR_RESPOND_PROMPT_POLICY_VERSION
        ModelTaskKind.TUTOR_VISUAL_GENERATE -> TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION
        ModelTaskKind.TUTOR_VISUAL_REVIEW -> TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION
        else -> error("Only tutor tasks can be recovered here")
    }
    val fingerprint = sha256Hex(
        buildString {
            append(ModelTaskFingerprint.of(failedRequest))
            appendLengthPrefixed(provider.providerId)
            appendLengthPrefixed(provider.modelId)
            appendLengthPrefixed(provider.providerConfigurationVersion)
            appendLengthPrefixed(promptPolicy)
            append('\n').append(approvedAtEpochMillis)
        },
    ).take(32)
    return "tutor-$taskName:approved-recovery:$fingerprint"
}

/** Creates a new authorized envelope without changing any persisted tutoring input. */
internal fun rebuildTutorRequestAfterApproval(
    failedTask: ModelTaskSnapshot,
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
): ModelTaskRequest {
    val taskKind = failedTask.request.input.kind
    require(
        taskKind == ModelTaskKind.TUTOR_PLAN ||
            taskKind == ModelTaskKind.TUTOR_RESPOND ||
            taskKind == ModelTaskKind.TUTOR_VISUAL_GENERATE ||
            taskKind == ModelTaskKind.TUTOR_VISUAL_REVIEW,
    )
    require(provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER)
    require(provider.supports(taskKind))
    val requestId = tutorRecoveryRequestId(
        failedRequest = failedTask.request,
        provider = provider,
        approvedAtEpochMillis = approvedAtEpochMillis,
    )
    val assets = failedTask.request.egressManifest?.assets.orEmpty()
    val promptPolicyVersion: String
    val disclosedData: Set<com.tingyun.smartmistakebook.core.model.ModelEgressDataClass>
    val prohibitedData: Set<com.tingyun.smartmistakebook.core.model.ModelEgressDataClass>
    when (taskKind) {
        ModelTaskKind.TUTOR_PLAN -> {
            promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE
            prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA
        }
        ModelTaskKind.TUTOR_RESPOND -> {
            promptPolicyVersion = TUTOR_RESPOND_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE
            prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA
        }
        ModelTaskKind.TUTOR_VISUAL_GENERATE -> {
            promptPolicyVersion = TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.tutorVisualGenerateDisclosure(
                includesSelectedRegion = assets.any { asset -> asset.selectedRegion != null },
            )
            prohibitedData =
                com.tingyun.smartmistakebook.core.model.ModelEgressDataClass.entries.toSet() -
                    disclosedData
        }
        ModelTaskKind.TUTOR_VISUAL_REVIEW -> {
            promptPolicyVersion = TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION
            disclosedData = ModelEgressManifest.tutorVisualReviewDisclosure(
                includesSelectedRegion = assets.any { asset -> asset.selectedRegion != null },
            )
            prohibitedData =
                com.tingyun.smartmistakebook.core.model.ModelEgressDataClass.entries.toSet() -
                    disclosedData
        }
    }
    val manifest = ModelEgressManifest(
        authorizationId = "authorization:$requestId",
        subjectId = failedTask.request.input.subjectId,
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(taskKind),
        providerId = provider.providerId,
        modelId = provider.modelId,
        providerConfigurationVersion = provider.providerConfigurationVersion,
        promptPolicyVersion = promptPolicyVersion,
        approvedAtEpochMillis = approvedAtEpochMillis,
        assets = assets,
        disclosedData = disclosedData,
        prohibitedData = prohibitedData,
    )
    require(manifest.authorizationId != failedTask.request.egressManifest?.authorizationId) {
        "Tutor recovery must not reuse the failed authorization"
    }
    return ModelTaskRequest(
        requestId = requestId,
        input = failedTask.request.input,
        occurredAtEpochMillis = failedTask.request.occurredAtEpochMillis,
        egressManifest = manifest,
    )
}

internal data class TutorQuestionContext(
    val sessionId: String,
    val revisionNumber: Int,
    val subject: String,
    val title: String,
    val questionDocument: com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument,
    val learningMemory: StudyQuestionMemory? = null,
    val relatedKnowledgeNodeIds: Set<String> = emptySet(),
    val reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    /** Stored model advisories for this question (three-store loop read side). */
    val priorTeachingAdvisories: List<String> = emptyList(),
) {
    init {
        require(sessionId.isNotBlank())
        require(revisionNumber > 0)
        require(subject.isNotBlank())
        require(title.isNotBlank())
        require(relatedKnowledgeNodeIds.all(String::isNotBlank))
        require(reviewedTeachingReferences.all { reference ->
            reference.subject == subject &&
                reference.knowledgeNodeIds.any(relatedKnowledgeNodeIds::contains)
        })
    }
}

internal fun ConfirmedTutorSession.toTutorQuestionContext() = TutorQuestionContext(
    sessionId = sessionId,
    revisionNumber = draftRevisionNumber,
    subject = subject,
    title = title,
    questionDocument = questionDocument,
    learningMemory = null,
)

internal fun tutorPlanRequestId(
    session: ConfirmedTutorSession,
    provider: ProviderCapabilitySnapshot,
    attempt: Int,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
): String = tutorPlanRequestId(
    question = session.toTutorQuestionContext(),
    provider = provider,
    attempt = attempt,
    cycleOrdinal = cycleOrdinal,
    priorConversationMemory = priorConversationMemory,
    priorCycleStudentMessages = priorCycleStudentMessages,
    priorTurns = priorTurns,
)

internal fun tutorPlanRequestId(
    question: TutorQuestionContext,
    provider: ProviderCapabilitySnapshot,
    attempt: Int,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
): String {
    require(attempt >= 0)
    val providerVersion = sha256Hex(provider.providerConfigurationVersion).take(16)
    val sessionFingerprint = sha256Hex(
        buildString {
            appendLengthPrefixed(question.sessionId)
            append('\n').append(cycleOrdinal)
            question.learningMemory?.let { memory ->
                append('\n').append(memory.independentRecallCount)
                append('\n').append(memory.assistedRecallCount)
                append('\n').append(memory.retrievalFailureCount)
                append('\n').append(memory.answerRevealCount)
                append('\n').append(memory.nextReviewAtEpochMillis)
                append('\n').append(memory.projectionIsCurrent)
            }
            question.relatedKnowledgeNodeIds.sorted().forEach { knowledgeNodeId ->
                appendLengthPrefixed(knowledgeNodeId)
            }
            question.reviewedTeachingReferences.forEach { reference ->
                appendLengthPrefixed(reference.materialId)
            }
            priorConversationMemory?.let { memory ->
                append('\n').append(memory.completedCycleCount)
                append('\n').append(memory.answeredTurnCount)
                append('\n').append(memory.correctChoiceCount)
                append('\n').append(memory.lastFeedbackMarkdown != null)
                appendLengthPrefixed(memory.lastFeedbackMarkdown)
                appendLengthPrefixed(memory.lastRequestedMove?.name)
                append('\n').append(memory.solutionWasRevealed)
            }
            priorCycleStudentMessages.forEach { message -> appendLengthPrefixed(message) }
            priorTurns.forEach { turn ->
                append('\n').append(turn.turnOrdinal)
                appendLengthPrefixed(turn.diagnosticStemMarkdown)
                appendLengthPrefixed(turn.selectedChoiceMarkdown)
                append('\n').append(turn.selectionWasCorrect)
                appendLengthPrefixed(turn.feedbackMarkdown)
                appendLengthPrefixed(turn.requestedMove.name)
            }
        },
    ).take(24)
    return "tutor-plan:$sessionFingerprint:${question.revisionNumber}:$cycleOrdinal:${priorTurns.size + 1}:$providerVersion:$TUTOR_PROMPT_POLICY_VERSION:$attempt"
}

/**
 * Silent post-session debrief request (three-store loop). Privacy-first:
 * only built for providers that keep the transcript on-device
 * (LOCAL_NO_EGRESS); external-provider configurations skip the debrief
 * silently rather than ship the transcript without a per-session approval.
 */
/** Public app-facing wrapper (feature-internal builder stays hidden). */
fun buildTutorDebriefRequestForApp(
    capabilities: ProviderCapabilitySnapshot,
    sessionId: String,
    practiceUnitId: String,
    subject: String,
    questionStemMarkdown: String,
    transcriptMarkdown: String,
    knowledgeLabels: List<String>,
    requestId: String,
    occurredAtEpochMillis: Long,
): com.tingyun.smartmistakebook.core.model.ModelTaskRequest? = buildTutorDebriefRequest(
    capabilities = capabilities,
    sessionId = sessionId,
    practiceUnitId = practiceUnitId,
    subject = subject,
    questionStemMarkdown = questionStemMarkdown,
    transcriptMarkdown = transcriptMarkdown,
    knowledgeLabels = knowledgeLabels,
    requestId = requestId,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

internal fun buildTutorDebriefRequest(
    capabilities: ProviderCapabilitySnapshot,
    sessionId: String,
    practiceUnitId: String,
    subject: String,
    questionStemMarkdown: String,
    transcriptMarkdown: String,
    knowledgeLabels: List<String>,
    requestId: String,
    occurredAtEpochMillis: Long,
): ModelTaskRequest? {
    if (capabilities.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS) return null
    val input = TutorDebriefInput(
        sessionId = sessionId,
        practiceUnitId = practiceUnitId,
        subject = subject,
        questionStemMarkdown = questionStemMarkdown,
        transcriptMarkdown = transcriptMarkdown,
        knowledgeLabels = knowledgeLabels,
    )
    return ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = null,
    )
}

internal fun buildTutorPlanRequest(
    session: ConfirmedTutorSession,
    profile: StudyProfileOverview,
    provider: ProviderCapabilitySnapshot,
    requestId: String,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
): ModelTaskRequest = buildTutorPlanRequest(
    question = session.toTutorQuestionContext(),
    profile = profile,
    provider = provider,
    requestId = requestId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    approvedAtEpochMillis = approvedAtEpochMillis,
    cycleOrdinal = cycleOrdinal,
    priorConversationMemory = priorConversationMemory,
    priorCycleStudentMessages = priorCycleStudentMessages,
    priorTurns = priorTurns,
)

internal fun buildTutorPlanRequest(
    question: TutorQuestionContext,
    profile: StudyProfileOverview,
    provider: ProviderCapabilitySnapshot,
    requestId: String,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
): ModelTaskRequest {
    val evidence = profile.toTutorKnowledgeEvidence(
        relatedKnowledgeNodeIds = question.relatedKnowledgeNodeIds,
        subject = question.subject,
        atEpochMillis = occurredAtEpochMillis,
    )
    val input = TutorPlanInput(
        sessionId = question.sessionId,
        draftRevisionNumber = question.revisionNumber,
        subject = question.subject,
        questionDocument = question.questionDocument.document,
        relevantLearningEvidence = evidence,
        projectionIsCurrent = profile.projectionIsCurrent,
        reviewedTeachingReferences = question.reviewedTeachingReferences,
        questionLearningEvidence = question.learningMemory?.toTutorEvidence(occurredAtEpochMillis),
        priorTeachingAdvisories = question.priorTeachingAdvisories,
        cycleOrdinal = cycleOrdinal,
        priorConversationMemory = priorConversationMemory,
        priorCycleStudentMessages = priorCycleStudentMessages,
        turnOrdinal = priorTurns.size + 1,
        priorTurns = priorTurns,
    )
    val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
        ModelEgressManifest(
            authorizationId = "authorization:$requestId",
            subjectId = question.sessionId,
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
            approvedAtEpochMillis = approvedAtEpochMillis,
            assets = emptyList(),
            disclosedData = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_PLAN_PROHIBITED_DATA,
        )
    } else {
        null
    }
    return ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = manifest,
    )
}

internal fun tutorRespondRequestId(
    question: TutorQuestionContext,
    provider: ProviderCapabilitySnapshot,
    responseOrdinal: Int,
    cycleOrdinal: Int,
    turnOrdinal: Int,
    studentMessage: String,
    visibleTutorContextMarkdown: String?,
    priorMessages: List<TutorChatHistoryEntry>,
    requestedMove: TutorMoveType? = null,
    attempt: Int,
): String {
    require(responseOrdinal > 0)
    require(cycleOrdinal > 0)
    require(turnOrdinal in 1..TutorPlanInput.MAX_TURNS)
    require(attempt >= 0)
    val conversationFingerprint = sha256Hex(
        buildString {
            appendLengthPrefixed(question.sessionId)
            appendLengthPrefixed(question.questionDocument.document.id)
            appendLengthPrefixed(question.revisionNumber.toString())
            appendLengthPrefixed(responseOrdinal.toString())
            appendLengthPrefixed(cycleOrdinal.toString())
            appendLengthPrefixed(turnOrdinal.toString())
            appendLengthPrefixed(studentMessage)
            appendLengthPrefixed(visibleTutorContextMarkdown)
            appendLengthPrefixed(requestedMove?.name)
            priorMessages.forEach { message ->
                appendLengthPrefixed(message.studentMessage)
                appendLengthPrefixed(message.assistantMarkdown)
            }
            question.reviewedTeachingReferences.forEach { reference ->
                appendLengthPrefixed(reference.materialId)
            }
        },
    ).take(24)
    val providerFingerprint = sha256Hex(provider.providerConfigurationVersion).take(12)
    return "tutor-respond:$conversationFingerprint:${question.revisionNumber}:" +
        "$responseOrdinal:$providerFingerprint:$TUTOR_RESPOND_PROMPT_POLICY_VERSION:$attempt"
}

internal fun buildTutorRespondRequest(
    question: TutorQuestionContext,
    profile: StudyProfileOverview,
    provider: ProviderCapabilitySnapshot,
    requestId: String,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long,
    responseOrdinal: Int,
    cycleOrdinal: Int,
    turnOrdinal: Int,
    studentMessage: String,
    visibleTutorContextMarkdown: String?,
    priorMessages: List<TutorChatHistoryEntry>,
    requestedMove: TutorMoveType? = null,
): ModelTaskRequest {
    val input = TutorRespondInput(
        sessionId = question.sessionId,
        draftRevisionNumber = question.revisionNumber,
        subject = question.subject,
        questionDocument = question.questionDocument.document,
        relevantLearningEvidence = profile.toTutorKnowledgeEvidence(
            relatedKnowledgeNodeIds = question.relatedKnowledgeNodeIds,
            subject = question.subject,
            atEpochMillis = occurredAtEpochMillis,
        ),
        projectionIsCurrent = profile.projectionIsCurrent,
        reviewedTeachingReferences = question.reviewedTeachingReferences,
        questionLearningEvidence = question.learningMemory?.toTutorEvidence(occurredAtEpochMillis),
        responseOrdinal = responseOrdinal,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        studentMessage = studentMessage,
        visibleTutorContextMarkdown = visibleTutorContextMarkdown,
        priorMessages = priorMessages,
        requestedMove = requestedMove,
        toolDeclarations = listOf(
            TutorToolName.KNOWLEDGE_READ,
            TutorToolName.NOTEBOOK_READ,
            TutorToolName.MASTERY_READ,
            TutorToolName.MASTERY_UPDATE,
            TutorToolName.NOTEBOOK_WRITE,
        ),
    )
    val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
        ModelEgressManifest(
            authorizationId = "authorization:$requestId",
            subjectId = question.sessionId,
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_RESPOND),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = TUTOR_RESPOND_PROMPT_POLICY_VERSION,
            approvedAtEpochMillis = approvedAtEpochMillis,
            assets = emptyList(),
            disclosedData = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
            prohibitedData = ModelEgressManifest.TUTOR_RESPOND_PROHIBITED_DATA,
        )
    } else {
        null
    }
    return ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = manifest,
    )
}

internal fun tutorVisualGenerateRequestId(
    question: TutorQuestionContext,
    provider: ProviderCapabilitySnapshot,
    sourceAssets: List<TutorVisualSourceAssetScope>,
    anchor: TutorVisualTurnAnchor,
    focusMarkdown: String,
    explanationMarkdown: String,
): String {
    val contentFingerprint = sha256Hex(
        buildString {
            appendLengthPrefixed(question.sessionId)
            appendLengthPrefixed(question.questionDocument.document.id)
            appendLengthPrefixed(question.revisionNumber.toString())
            appendLengthPrefixed(question.subject)
            appendLengthPrefixed(anchor.surface.name)
            appendLengthPrefixed(anchor.cycleOrdinal.toString())
            appendLengthPrefixed(anchor.turnOrdinal.toString())
            appendLengthPrefixed(anchor.responseOrdinal?.toString())
            appendLengthPrefixed(focusMarkdown)
            appendLengthPrefixed(explanationMarkdown)
            sourceAssets.sortedBy(TutorVisualSourceAssetScope::pageIndex).forEach { asset ->
                appendLengthPrefixed(asset.pageIndex.toString())
                appendLengthPrefixed(asset.assetId)
                appendLengthPrefixed(asset.sha256)
                appendLengthPrefixed(asset.selectedRegion?.let { region ->
                    "${region.left},${region.top},${region.right},${region.bottom}"
                })
            }
            appendLengthPrefixed(provider.providerId)
            appendLengthPrefixed(provider.modelId)
            appendLengthPrefixed(provider.providerConfigurationVersion)
            appendLengthPrefixed(TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION)
            appendLengthPrefixed(TutorVisualScene.DOCUMENT_SCHEMA_VERSION.toString())
        },
    ).take(32)
    return "tutor-visual-generate:$contentFingerprint"
}

internal fun buildTutorVisualGenerateRequest(
    question: TutorQuestionContext,
    provider: ProviderCapabilitySnapshot,
    sourceAssets: List<TutorVisualSourceAssetScope>,
    anchor: TutorVisualTurnAnchor,
    focusMarkdown: String,
    explanationMarkdown: String,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long,
): ModelTaskRequest {
    require(provider.supports(ModelTaskKind.TUTOR_VISUAL_GENERATE))
    val orderedAssets = sourceAssets.sortedBy(TutorVisualSourceAssetScope::pageIndex)
    require(orderedAssets.map(TutorVisualSourceAssetScope::pageIndex) == orderedAssets.indices.toList())
    val requestId = tutorVisualGenerateRequestId(
        question = question,
        provider = provider,
        sourceAssets = orderedAssets,
        anchor = anchor,
        focusMarkdown = focusMarkdown,
        explanationMarkdown = explanationMarkdown,
    )
    val input = TutorVisualGenerateInput(
        sessionId = question.sessionId,
        draftRevisionNumber = question.revisionNumber,
        subject = question.subject,
        questionDocument = question.questionDocument.document,
        sourceAssets = orderedAssets.map(TutorVisualSourceAssetScope::toSourceRef),
        anchor = anchor,
        focusMarkdown = focusMarkdown,
        explanationMarkdown = explanationMarkdown,
    )
    return ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = buildTutorVisualManifest(
            question = question,
            provider = provider,
            sourceAssets = orderedAssets,
            taskKind = ModelTaskKind.TUTOR_VISUAL_GENERATE,
            requestId = requestId,
            approvedAtEpochMillis = approvedAtEpochMillis,
        ),
    )
}

internal fun tutorVisualReviewRequestId(
    generationRequestId: String,
    provider: ProviderCapabilitySnapshot,
    generated: TutorVisualGenerateOutput,
    reviewReasonCodes: Set<String>,
): String {
    val fingerprint = sha256Hex(
        buildString {
            appendLengthPrefixed(generationRequestId)
            appendLengthPrefixed(generated.modelVersion)
            appendLengthPrefixed(generated.scene?.sceneId)
            reviewReasonCodes.sorted().forEach(::appendLengthPrefixed)
            appendLengthPrefixed(provider.providerId)
            appendLengthPrefixed(provider.modelId)
            appendLengthPrefixed(provider.providerConfigurationVersion)
            appendLengthPrefixed(TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION)
        },
    ).take(32)
    return "tutor-visual-review:$fingerprint"
}

internal fun buildTutorVisualReviewRequest(
    question: TutorQuestionContext,
    provider: ProviderCapabilitySnapshot,
    sourceAssets: List<TutorVisualSourceAssetScope>,
    generationRequest: ModelTaskRequest,
    generated: TutorVisualGenerateOutput,
    reviewReasonCodes: Set<String>,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long,
): ModelTaskRequest {
    require(provider.supports(ModelTaskKind.TUTOR_VISUAL_REVIEW))
    val generationInput = generationRequest.input as? TutorVisualGenerateInput
        ?: error("Tutor visual review requires the originating generation input")
    val candidate = requireNotNull(generated.scene) {
        "Tutor visual review requires a generated candidate"
    }
    require(generationInput.sessionId == question.sessionId)
    require(generationInput.draftRevisionNumber == question.revisionNumber)
    require(generated.sessionId == question.sessionId)
    require(generated.draftRevisionNumber == question.revisionNumber)
    require(generated.questionDocumentId == question.questionDocument.document.id)
    require(generated.anchor == generationInput.anchor)
    val orderedAssets = sourceAssets.sortedBy(TutorVisualSourceAssetScope::pageIndex)
    require(
        orderedAssets.map(TutorVisualSourceAssetScope::toSourceRef) == generationInput.sourceAssets,
    ) { "Tutor visual review must reuse the exact generation image scope" }
    val requestId = tutorVisualReviewRequestId(
        generationRequestId = generationRequest.requestId,
        provider = provider,
        generated = generated,
        reviewReasonCodes = reviewReasonCodes,
    )
    val input = TutorVisualReviewInput(
        sessionId = generationInput.sessionId,
        draftRevisionNumber = generationInput.draftRevisionNumber,
        subject = generationInput.subject,
        questionDocument = generationInput.questionDocument,
        sourceAssets = generationInput.sourceAssets,
        anchor = generationInput.anchor,
        focusMarkdown = generationInput.focusMarkdown,
        explanationMarkdown = generationInput.explanationMarkdown,
        candidateScene = candidate,
        reviewReasonCodes = reviewReasonCodes,
    )
    return ModelTaskRequest(
        requestId = requestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = buildTutorVisualManifest(
            question = question,
            provider = provider,
            sourceAssets = orderedAssets,
            taskKind = ModelTaskKind.TUTOR_VISUAL_REVIEW,
            requestId = requestId,
            approvedAtEpochMillis = approvedAtEpochMillis,
        ),
    )
}

private fun buildTutorVisualManifest(
    question: TutorQuestionContext,
    provider: ProviderCapabilitySnapshot,
    sourceAssets: List<TutorVisualSourceAssetScope>,
    taskKind: ModelTaskKind,
    requestId: String,
    approvedAtEpochMillis: Long,
): ModelEgressManifest? {
    if (provider.executionLocation != ModelExecutionLocation.EXTERNAL_PROVIDER) return null
    require(
        taskKind == ModelTaskKind.TUTOR_VISUAL_GENERATE ||
            taskKind == ModelTaskKind.TUTOR_VISUAL_REVIEW,
    )
    val grants = sourceAssets.map(TutorVisualSourceAssetScope::toEgressGrant)
    val includesSelectedRegion = grants.any { grant -> grant.selectedRegion != null }
    val disclosedData = when (taskKind) {
        ModelTaskKind.TUTOR_VISUAL_GENERATE ->
            ModelEgressManifest.tutorVisualGenerateDisclosure(includesSelectedRegion)
        ModelTaskKind.TUTOR_VISUAL_REVIEW ->
            ModelEgressManifest.tutorVisualReviewDisclosure(includesSelectedRegion)
    }
    return ModelEgressManifest(
        authorizationId = "authorization:$requestId",
        subjectId = question.sessionId,
        purpose = ModelEgressPurpose.TUTORING,
        authorizedTaskKinds = setOf(taskKind),
        providerId = provider.providerId,
        modelId = provider.modelId,
        providerConfigurationVersion = provider.providerConfigurationVersion,
        promptPolicyVersion = when (taskKind) {
            ModelTaskKind.TUTOR_VISUAL_GENERATE -> TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION
            ModelTaskKind.TUTOR_VISUAL_REVIEW -> TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION
        },
        approvedAtEpochMillis = approvedAtEpochMillis,
        assets = grants,
        disclosedData = disclosedData,
        prohibitedData =
            com.tingyun.smartmistakebook.core.model.ModelEgressDataClass.entries.toSet() -
                disclosedData,
    )
}

/** Builds only what was already rendered; hidden solutions and alternate methods never leak here. */
internal fun visibleTutorContextMarkdown(
    output: TutorPlanOutput,
    response: TutorTurnResponse?,
    answerWasExposed: Boolean,
): String = buildString {
    append(output.plan.openingMarkdown)
    output.plan.diagnosticItem?.let { item ->
        append("\n\n").append(item.stemMarkdown)
        item.promptMarkdown?.let { append("\n\n").append(it) }
    }
    response?.takeIf(TutorTurnResponse::hasChoicePayload)?.let { choice ->
        append("\n\n学生选择：").append(choice.selectedChoiceMarkdown)
        append("\n\n已显示反馈：").append(choice.feedbackMarkdown)
    }
    val sceneWasVisible = output.plan.diagnosticItem == null || response?.hasChoicePayload == true
    if (sceneWasVisible) {
        output.plan.visualScene?.let { scene ->
            append("\n\n已显示图解：").append(scene.title)
        }
    }
    if (response?.requestedMove == TutorMoveType.CHANGE_REPRESENTATION) {
        append("\n\n已显示另一种方法：").append(output.plan.alternateMethodMarkdown)
    }
    if (answerWasExposed && response?.solutionRevealed == true) {
        append("\n\n已显示完整讲解：").append(output.plan.solutionMarkdown)
    }
}.take(TutorRespondInput.MAX_VISIBLE_CONTEXT_CHARS)

private fun StudyProfileOverview.toTutorKnowledgeEvidence(
    relatedKnowledgeNodeIds: Set<String>,
    subject: String,
    atEpochMillis: Long,
): List<TutorKnowledgeEvidence> {
    require(atEpochMillis >= 0)
    val subjectKind = SubjectKind.entries.firstOrNull { it.name == subject }
        ?: return emptyList()
    if (subjectKind == SubjectKind.GENERAL) return emptyList()
    val allowsSummary: (com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary) -> Boolean =
        { summary ->
            summary.subject == subjectKind ||
                (
                    relatedKnowledgeNodeIds.isNotEmpty() &&
                        summary.subject == SubjectKind.GENERAL &&
                        summary.knowledgeNodeId in relatedKnowledgeNodeIds
                    )
        }
    val questionPriority: (com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary) -> Int =
        { summary -> if (summary.knowledgeNodeId in relatedKnowledgeNodeIds) 0 else 1 }
    val weaknessEvidence = weaknesses
        .filter(allowsSummary)
        .sortedWith(
            compareBy<com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary>(
                questionPriority,
                ::weaknessPriority,
            )
                .thenByDescending { it.lastIndependentErrorAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.conservativeMasteryScore }
                .thenByDescending { it.lastEvidenceAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.knowledgeNodeId },
        )
        .take(MAX_WEAKNESS_EVIDENCE)
    val strengthEvidence = strengths
        .takeIf { projectionIsCurrent }
        .orEmpty()
        .filter(allowsSummary)
        .sortedWith(
            compareBy<com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary>(
                questionPriority,
            )
                .thenByDescending { it.lastEvidenceAtEpochMillis ?: Long.MIN_VALUE }
                .thenByDescending { it.conservativeMasteryScore }
                .thenBy { it.knowledgeNodeId },
        )
        .take(MAX_STRENGTH_EVIDENCE)
    return (weaknessEvidence + strengthEvidence).map { summary ->
        TutorKnowledgeEvidence(
            knowledgeNodeId = summary.knowledgeNodeId,
            displayName = summary.displayName,
            level = summary.status.toTutorEvidenceLevel(),
            independentCorrectLowerBound = summary.conservativeMasteryScore,
            evidenceMass = summary.evidenceMass
                .coerceAtMost(TutorKnowledgeEvidence.MAX_DISCLOSED_EVIDENCE_MASS),
            independentCorrectObservationCount = summary.independentCorrectObservationCount
                .coerceAtMost(TutorKnowledgeEvidence.MAX_DISCLOSED_OBSERVATIONS),
            latestEvidenceRecency = summary.lastEvidenceAtEpochMillis
                .toTutorEvidenceRecency(atEpochMillis),
            latestIndependentErrorRecency = summary.lastIndependentErrorAtEpochMillis
                .toTutorEvidenceRecency(atEpochMillis),
        )
    }
}

private fun weaknessPriority(
    summary: com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary,
): Int = when (summary.status) {
    MasteryStatus.CONFLICTED -> 0
    MasteryStatus.LEARNING -> 1
    MasteryStatus.STALE -> 2
    MasteryStatus.UNKNOWN -> 3
    MasteryStatus.MASTERED -> 4
}

private fun Long?.toTutorEvidenceRecency(atEpochMillis: Long): TutorEvidenceRecency {
    val eventAt = this ?: return TutorEvidenceRecency.UNKNOWN
    if (eventAt > atEpochMillis) return TutorEvidenceRecency.UNKNOWN
    val ageMillis = atEpochMillis - eventAt
    return when {
        ageMillis <= 7L * MILLIS_PER_DAY -> TutorEvidenceRecency.WITHIN_7_DAYS
        ageMillis <= 30L * MILLIS_PER_DAY -> TutorEvidenceRecency.WITHIN_30_DAYS
        ageMillis <= 90L * MILLIS_PER_DAY -> TutorEvidenceRecency.WITHIN_90_DAYS
        else -> TutorEvidenceRecency.OLDER
    }
}

private fun StringBuilder.appendLengthPrefixed(value: String?) {
    append('\n')
    if (value == null) {
        append("-1:")
    } else {
        append(value.length).append(':').append(value)
    }
}

private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private const val MAX_WEAKNESS_EVIDENCE = 8
private const val MAX_STRENGTH_EVIDENCE = 4
private const val MILLIS_PER_DAY = 86_400_000L

private fun StudyQuestionMemory.toTutorEvidence(atEpochMillis: Long): TutorQuestionLearningEvidence =
    TutorQuestionLearningEvidence(
        independentRecallCount = independentRecallCount,
        assistedRecallCount = assistedRecallCount,
        retrievalFailureCount = retrievalFailureCount,
        answerRevealCount = answerRevealCount,
        retentionEstimate = retrievabilityAtSnapshot.takeIf { projectionIsCurrent },
        reviewStatus = when {
            !projectionIsCurrent -> TutorQuestionReviewStatus.STALE
            nextReviewAtEpochMillis <= atEpochMillis -> TutorQuestionReviewStatus.DUE
            else -> TutorQuestionReviewStatus.SCHEDULED
        },
    )

private fun MasteryStatus.toTutorEvidenceLevel(): TutorEvidenceLevel = when (this) {
    MasteryStatus.UNKNOWN -> TutorEvidenceLevel.UNKNOWN
    MasteryStatus.LEARNING -> TutorEvidenceLevel.LEARNING
    MasteryStatus.MASTERED -> TutorEvidenceLevel.MASTERED
    MasteryStatus.CONFLICTED -> TutorEvidenceLevel.CONFLICTED
    MasteryStatus.STALE -> TutorEvidenceLevel.STALE
}
