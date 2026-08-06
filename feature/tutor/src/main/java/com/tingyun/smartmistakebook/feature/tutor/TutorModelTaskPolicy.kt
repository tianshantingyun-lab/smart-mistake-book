package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorGuidancePolicy
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContext
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRequest
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryStatus
import com.tingyun.smartmistakebook.core.domain.TutorVisualSourceAssetScope
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskInput
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskLogicalOperationFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeGuidance
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingConstraint
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualReviewInput
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceFact
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceFactExtractor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.isModelEgressApprovalFresh
import com.tingyun.smartmistakebook.core.model.requiresEgressAuthorizationRenewal
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val TUTOR_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_PLAN
internal const val TUTOR_RESPOND_PROMPT_POLICY_VERSION = ModelPromptPolicyVersions.TUTOR_RESPOND
internal const val TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION =
    ModelPromptPolicyVersions.TUTOR_VISUAL_GENERATE
internal const val TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION =
    ModelPromptPolicyVersions.TUTOR_VISUAL_REVIEW
private const val LOCAL_RECOVERY_MARKER = ":local-recovery:"

internal fun ModelTaskRequest.isLocalTutorRecoveryRequest(): Boolean =
    LOCAL_RECOVERY_MARKER in requestId

internal fun ModelTaskStatus.isTutorExecutionPending(): Boolean = when (this) {
    ModelTaskStatus.WAITING_FOR_MODEL,
    ModelTaskStatus.QUEUED,
    ModelTaskStatus.RUNNING,
    ModelTaskStatus.STREAMING,
    -> true
    else -> false
}

internal fun ModelTaskSnapshot.isPendingTutorRespondFor(
    explanationMode: TutorExplanationMode,
): Boolean {
    val input = request.input as? TutorRespondInput ?: return false
    return status.isTutorExecutionPending() &&
        input.explanationMode == tutorResponseModeFor(
            currentMode = explanationMode,
            studentMessage = input.studentMessage,
        )
}

internal fun latestPendingTutorLobbyTask(
    tasks: List<ModelTaskSnapshot>,
): ModelTaskSnapshot? = tasks.lastOrNull { task ->
    task.status.isTutorExecutionPending() && task.request.input is TutorLobbyInput
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
    val questionDocumentFingerprint: String = "",
    val providerId: String,
    val modelId: String,
    val providerConfigurationVersion: String,
    val planPromptPolicyVersion: String,
    val respondPromptPolicyVersion: String,
    val visualGeneratePromptPolicyVersion: String,
    val visualReviewPromptPolicyVersion: String,
    val approvedAtEpochMillis: Long,
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
        return approvedAtEpochMillis.takeIf {
            provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER &&
                provider.supports(taskKind) &&
                sessionId == question.sessionId &&
                revisionNumber == question.revisionNumber &&
                questionDocumentId == question.questionDocument.document.id &&
                questionDocumentFingerprint == CapturedQuestionDocumentFingerprint.of(
                    question.questionDocument,
                ) &&
                providerId == provider.providerId &&
                modelId == provider.modelId &&
                providerConfigurationVersion == provider.providerConfigurationVersion &&
                policyMatches &&
                isModelEgressApprovalFresh(approvedAtEpochMillis, nowEpochMillis)
        }
    }

    companion object {
        fun grant(
            question: TutorQuestionContext,
            provider: ProviderCapabilitySnapshot,
            approvedAtEpochMillis: Long,
        ): TutorCompositionEgressLease {
            require(provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER)
            return TutorCompositionEgressLease(
                sessionId = question.sessionId,
                revisionNumber = question.revisionNumber,
                questionDocumentId = question.questionDocument.document.id,
                questionDocumentFingerprint = CapturedQuestionDocumentFingerprint.of(
                    question.questionDocument,
                ),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                planPromptPolicyVersion = TUTOR_PROMPT_POLICY_VERSION,
                respondPromptPolicyVersion = TUTOR_RESPOND_PROMPT_POLICY_VERSION,
                visualGeneratePromptPolicyVersion =
                    TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION,
                visualReviewPromptPolicyVersion =
                    TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION,
                approvedAtEpochMillis = approvedAtEpochMillis,
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
    if (!isRebuildableTutorRequest()) return false
    val failureCode = failure?.code ?: return false
    return !coversCurrentTutorDisclosure(provider, request.input.kind) ||
        failureCode.requiresEgressAuthorizationRenewal() ||
        (failureCode.requiresModelSettings() && !matchesTutorProvider(provider))
}

internal fun ModelTaskSnapshot.isRebuildableTutorRequest(): Boolean =
    request.schemaVersion >= ModelTaskRequest.EGRESS_SCHEMA_VERSION &&
        when (request.input.kind) {
            ModelTaskKind.TUTOR_PLAN,
            ModelTaskKind.TUTOR_RESPOND,
            ModelTaskKind.TUTOR_VISUAL_GENERATE,
            ModelTaskKind.TUTOR_VISUAL_REVIEW,
            -> true
            else -> false
        }

internal fun tutorRecoveryRequestId(
    failedRequest: ModelTaskRequest,
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
): String = tutorRecoveryRequestId(
    recoveryInput = failedRequest.input.withTrustedTutorRecoveryContext(),
    provider = provider,
    approvedAtEpochMillis = approvedAtEpochMillis,
)

private fun tutorRecoveryRequestId(
    recoveryInput: ModelTaskInput,
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
): String {
    val taskName = when (recoveryInput.kind) {
        ModelTaskKind.TUTOR_PLAN -> "plan"
        ModelTaskKind.TUTOR_RESPOND -> "respond"
        ModelTaskKind.TUTOR_VISUAL_GENERATE -> "visual-generate"
        ModelTaskKind.TUTOR_VISUAL_REVIEW -> "visual-review"
        else -> error("Only tutor tasks can be recovered here")
    }
    val promptPolicy = when (recoveryInput.kind) {
        ModelTaskKind.TUTOR_PLAN -> TUTOR_PROMPT_POLICY_VERSION
        ModelTaskKind.TUTOR_RESPOND -> TUTOR_RESPOND_PROMPT_POLICY_VERSION
        ModelTaskKind.TUTOR_VISUAL_GENERATE -> TUTOR_VISUAL_GENERATE_PROMPT_POLICY_VERSION
        ModelTaskKind.TUTOR_VISUAL_REVIEW -> TUTOR_VISUAL_REVIEW_PROMPT_POLICY_VERSION
        else -> error("Only tutor tasks can be recovered here")
    }
    val fingerprint = sha256Hex(
        buildString {
            append(ModelTaskLogicalOperationFingerprint.of(recoveryInput))
            appendLengthPrefixed(provider.providerId)
            appendLengthPrefixed(provider.modelId)
            appendLengthPrefixed(provider.providerConfigurationVersion)
            appendLengthPrefixed(promptPolicy)
            append('\n').append(approvedAtEpochMillis)
        },
    ).take(32)
    return "tutor-$taskName:approved-recovery:$fingerprint"
}

/**
 * Creates a new authorization from current owner-issued context. Persisted plan/response guidance
 * is never trusted; callers without current context receive the safe empty projection.
 */
internal fun rebuildTutorRequestAfterApproval(
    failedTask: ModelTaskSnapshot,
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
    question: TutorQuestionContext? = null,
    masteryContext: TutorMasteryContext = TutorMasteryContext.EMPTY,
): ModelTaskRequest {
    require(failedTask.request.schemaVersion >= ModelTaskRequest.EGRESS_SCHEMA_VERSION) {
        "Tutor recovery cannot authorize a pre-egress request schema"
    }
    val taskKind = failedTask.request.input.kind
    require(
        taskKind == ModelTaskKind.TUTOR_PLAN ||
            taskKind == ModelTaskKind.TUTOR_RESPOND ||
            taskKind == ModelTaskKind.TUTOR_VISUAL_GENERATE ||
            taskKind == ModelTaskKind.TUTOR_VISUAL_REVIEW,
    )
    require(provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER)
    require(provider.supports(taskKind))
    val recoveryInput = failedTask.request.input.withTrustedTutorRecoveryContext(
        question = question,
        masteryContext = masteryContext,
    )
    val requestId = tutorRecoveryRequestId(
        recoveryInput = recoveryInput,
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
        authorizationId =
            ModelEgressAuthorizationId.forInput(requestId, recoveryInput),
        subjectId = recoveryInput.subjectId,
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
        schemaVersion = failedTask.request.schemaVersion,
        requestId = requestId,
        input = recoveryInput,
        occurredAtEpochMillis = failedTask.request.occurredAtEpochMillis,
        egressManifest = manifest,
    )
}

internal fun rebuildTutorRequestAfterApprovalOrNull(
    failedTask: ModelTaskSnapshot,
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
    question: TutorQuestionContext? = null,
    masteryContext: TutorMasteryContext = TutorMasteryContext.EMPTY,
): ModelTaskRequest? {
    val taskKind = failedTask.request.input.kind
    if (
        !failedTask.isRebuildableTutorRequest() ||
        provider.executionLocation != ModelExecutionLocation.EXTERNAL_PROVIDER ||
        !provider.supports(taskKind)
    ) {
        return null
    }
    return runCatching {
        rebuildTutorRequestAfterApproval(
            failedTask = failedTask,
            provider = provider,
            approvedAtEpochMillis = approvedAtEpochMillis,
            question = question,
            masteryContext = masteryContext,
        )
    }.getOrNull()
}

/**
 * Re-reads the owner store immediately before an external recovery. A stale/closed read returns
 * null and must not be dispatched; an unavailable store safely rebuilds with no constraints.
 */
internal suspend fun rebuildTutorRequestAfterFreshMasteryApprovalOrNull(
    failedTask: ModelTaskSnapshot,
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
    question: TutorQuestionContext,
    masteryContextRepository: TutorMasteryContextRepository?,
    recoveryReader: TutorMasteryRecoveryReader,
    recoveryIsAuthorized: () -> Boolean = { true },
): ModelTaskRequest? {
    if (!recoveryIsAuthorized()) return null
    val freshMasteryContext = recoveryReader.read(
        repository = masteryContextRepository,
        question = question,
    ) ?: return null
    currentCoroutineContext().ensureActive()
    if (!recoveryIsAuthorized()) return null
    return rebuildTutorRequestAfterApprovalOrNull(
        failedTask = failedTask,
        provider = provider,
        approvedAtEpochMillis = approvedAtEpochMillis,
        question = question,
        masteryContext = freshMasteryContext,
    )
}

/**
 * Rebuilds a durable local Tutor task from the current question authority. Persisted teaching
 * material is never replayed directly: current provenanced references replace it, or the request
 * continues without teaching material when the current projection is unavailable. The rebuilt
 * request identity includes the process-local recovery authority so a terminal Room row from an
 * earlier owner/provider/conversation generation cannot be replayed as fresh work.
 */
internal fun rebuildLocalTutorRequestForRecoveryOrNull(
    failedTask: ModelTaskSnapshot,
    provider: ProviderCapabilitySnapshot,
    question: TutorQuestionContext,
    masteryContext: TutorMasteryContext = TutorMasteryContext.EMPTY,
    explanationMode: TutorExplanationMode,
    modeVersion: Long,
    learningWritePermissionVersion: Long,
    allowLongTermLearningWrites: Boolean,
    cycleOrdinal: Int,
    turnOrdinal: Int,
    recoveryAuthority: TutorLocalRecoveryRequestAuthority,
    onTeachingReferenceDrop: (TutorTeachingReferenceRecoveryDropReason) -> Unit = {},
): ModelTaskRequest? {
    val taskKind = failedTask.request.input.kind
    if (
        !failedTask.isRebuildableTutorRequest() ||
        provider.executionLocation != ModelExecutionLocation.LOCAL_NO_EGRESS ||
        !provider.supports(taskKind) ||
        taskKind != ModelTaskKind.TUTOR_PLAN && taskKind != ModelTaskKind.TUTOR_RESPOND
    ) {
        return null
    }
    if (
        recoveryAuthority.questionDocumentFingerprint !=
            CapturedQuestionDocumentFingerprint.of(question.questionDocument) ||
        recoveryAuthority.sourceRequestFingerprint != ModelTaskFingerprint.of(failedTask.request) ||
        recoveryAuthority.sourceTaskStateVersion != failedTask.stateVersion
    ) {
        return null
    }
    return runCatching {
        val persistedReferences = when (val input = failedTask.request.input) {
            is TutorPlanInput -> input.reviewedTeachingReferences
            is TutorRespondInput -> input.reviewedTeachingReferences
            else -> emptyList()
        }
        if (persistedReferences.any { !it.hasCompleteCatalogProvenance }) {
            onTeachingReferenceDrop(
                TutorTeachingReferenceRecoveryDropReason.PERSISTED_PROVENANCE_INCOMPLETE,
            )
        }
        if (
            persistedReferences.isNotEmpty() &&
            question.trustedReviewedTeachingReferences.isEmpty()
        ) {
            onTeachingReferenceDrop(
                TutorTeachingReferenceRecoveryDropReason.CURRENT_BINDING_UNAVAILABLE,
            )
        }
        val recoveryInput = failedTask.request.input.withTrustedTutorRecoveryContext(
            question = question,
            masteryContext = masteryContext,
            explanationMode = explanationMode,
            modeVersion = modeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion,
            allowLongTermLearningWrites = allowLongTermLearningWrites,
            cycleOrdinal = cycleOrdinal,
            turnOrdinal = turnOrdinal,
        )
        val taskName = if (taskKind == ModelTaskKind.TUTOR_PLAN) "plan" else "respond"
        val semanticRequestId = failedTask.request.requestId.substringBefore(LOCAL_RECOVERY_MARKER)
        val recoveryFingerprint = sha256Hex(
            buildString {
                appendLengthPrefixed(semanticRequestId)
                appendLengthPrefixed(ModelTaskLogicalOperationFingerprint.of(recoveryInput))
                appendLengthPrefixed(provider.providerId)
                appendLengthPrefixed(provider.modelId)
                appendLengthPrefixed(provider.providerConfigurationVersion)
                appendLengthPrefixed(recoveryAuthority.authoritySessionId)
                appendLengthPrefixed(recoveryAuthority.authorityGeneration.toString())
                appendLengthPrefixed(recoveryAuthority.questionDocumentFingerprint)
                appendLengthPrefixed(recoveryAuthority.providerAuthorityGeneration.toString())
                appendLengthPrefixed(recoveryAuthority.conversationGeneration.toString())
                appendLengthPrefixed(recoveryAuthority.activeOwnerEpoch.toString())
                appendLengthPrefixed(recoveryAuthority.sourceRequestFingerprint)
                appendLengthPrefixed(recoveryAuthority.sourceTaskStateVersion.toString())
            },
        ).take(32)
        ModelTaskRequest(
            schemaVersion = failedTask.request.schemaVersion,
            requestId = "tutor-$taskName$LOCAL_RECOVERY_MARKER$recoveryFingerprint",
            input = recoveryInput,
            occurredAtEpochMillis = failedTask.request.occurredAtEpochMillis,
            egressManifest = null,
        )
    }.getOrNull()
}

internal enum class TutorTeachingReferenceRecoveryDropReason {
    PERSISTED_PROVENANCE_INCOMPLETE,
    CURRENT_BINDING_UNAVAILABLE,
}

private fun ModelTaskInput.withTrustedTutorRecoveryContext(
    question: TutorQuestionContext? = null,
    masteryContext: TutorMasteryContext = TutorMasteryContext.EMPTY,
    explanationMode: TutorExplanationMode? = null,
    modeVersion: Long? = null,
    learningWritePermissionVersion: Long? = null,
    allowLongTermLearningWrites: Boolean? = null,
    cycleOrdinal: Int? = null,
    turnOrdinal: Int? = null,
): ModelTaskInput = when (this) {
    is TutorPlanInput -> if (question == null) {
        copy(
            teachingConstraints = emptyList(),
            reviewedTeachingReferences = emptyList(),
        )
    } else {
        require(matchesTutorRecoveryQuestion(question))
        require(cycleOrdinal == null || this.cycleOrdinal == cycleOrdinal)
        require(turnOrdinal == null || this.turnOrdinal == turnOrdinal)
        copy(
            sessionId = question.sessionId,
            draftRevisionNumber = question.revisionNumber,
            subject = question.subject,
            questionDocument = question.questionDocument.document,
            teachingConstraints = TutorGuidancePolicy.projectTeachingConstraints(
                masteryContext,
                question,
            ),
            reviewedTeachingReferences = question.trustedReviewedTeachingReferences,
            explanationMode = explanationMode ?: this.explanationMode,
            modeVersion = modeVersion ?: this.modeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion
                ?: this.learningWritePermissionVersion,
            allowLongTermLearningWrites = allowLongTermLearningWrites
                ?: this.allowLongTermLearningWrites,
        )
    }
    is TutorRespondInput -> if (question == null) {
        copy(
            teachingConstraints = emptyList(),
            reviewedTeachingReferences = emptyList(),
        )
    } else {
        require(matchesTutorRecoveryQuestion(question))
        require(cycleOrdinal == null || this.cycleOrdinal == cycleOrdinal)
        require(turnOrdinal == null || this.turnOrdinal == turnOrdinal)
        copy(
            sessionId = question.sessionId,
            draftRevisionNumber = question.revisionNumber,
            subject = question.subject,
            questionDocument = question.questionDocument.document,
            teachingConstraints = TutorGuidancePolicy.projectTeachingConstraints(
                masteryContext,
                question,
            ),
            reviewedTeachingReferences = question.trustedReviewedTeachingReferences,
            explanationMode = explanationMode ?: this.explanationMode,
            modeVersion = modeVersion ?: this.modeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion
                ?: this.learningWritePermissionVersion,
            allowLongTermLearningWrites = allowLongTermLearningWrites
                ?: this.allowLongTermLearningWrites,
        )
    }
    else -> this
}

private fun TutorPlanInput.matchesTutorRecoveryQuestion(question: TutorQuestionContext): Boolean =
    sessionId == question.sessionId &&
        draftRevisionNumber == question.revisionNumber &&
        questionDocument.id == question.questionDocument.document.id

private fun TutorRespondInput.matchesTutorRecoveryQuestion(question: TutorQuestionContext): Boolean =
    sessionId == question.sessionId &&
        draftRevisionNumber == question.revisionNumber &&
        questionDocument.id == question.questionDocument.document.id

internal data class TutorQuestionContext(
    val sessionId: String,
    val revisionNumber: Int,
    val subject: String,
    val title: String,
    val questionDocument: com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument,
    val learningMemory: StudyQuestionMemory? = null,
    val directKnowledgeNodeIds: Set<String> = emptySet(),
    val relatedKnowledgeNodeIds: Set<String> = emptySet(),
    val reviewedTeachingReferences: List<TutorTeachingReference> = emptyList(),
    val questionKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    val fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    val relatedKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    val trustedKnowledgeLabelResolver: TutorTrustedKnowledgeLabelResolver? = null,
) {
    init {
        require(sessionId.isNotBlank())
        require(revisionNumber > 0)
        require(subject.isNotBlank())
        require(title.isNotBlank())
        require(directKnowledgeNodeIds.all(String::isNotBlank))
        require(relatedKnowledgeNodeIds.all(String::isNotBlank))
        require(reviewedTeachingReferences.all { reference ->
            reference.subject == subject &&
                reference.knowledgeNodeIds.isNotEmpty() &&
                reference.knowledgeNodeIds.all(directKnowledgeNodeIds::contains)
        })
        val masteryNodes = questionKnowledgeNodes + fallbackKnowledgeNodes + relatedKnowledgeNodes
        if (masteryNodes.isNotEmpty()) {
            val subjectKind = SubjectKind.entries.singleOrNull { candidate ->
                candidate != SubjectKind.GENERAL && candidate.name == subject
            }
            requireNotNull(subjectKind) {
                "Tutor mastery nodes require one specific question subject"
            }
            TutorMasteryContextRequest(
                subject = subjectKind,
                questionKnowledgeNodes = questionKnowledgeNodes,
                fallbackKnowledgeNodes = fallbackKnowledgeNodes,
                relatedKnowledgeNodes = relatedKnowledgeNodes,
            )
        }
    }
}

private val TutorQuestionContext.trustedReviewedTeachingReferences: List<TutorTeachingReference>
    get() = reviewedTeachingReferences.filter(TutorTeachingReference::hasCompleteCatalogProvenance)

/**
 * Narrow, read-only projection owned by the activated local knowledge snapshot.
 *
 * The tutor never receives a catalog repository, DAO, database handle, or query capability.
 * A missing or stale result is deliberately indistinguishable from an unknown reference.
 */
internal fun interface TutorTrustedKnowledgeLabelResolver {
    fun resolveFromActivatedSnapshot(ref: KnowledgeNodeRef): TutorTrustedKnowledgeLabel?
}

/** Minimal snapshot-bound result; provenance is checked locally and is never sent to the model. */
internal data class TutorTrustedKnowledgeLabel(
    val ref: KnowledgeNodeRef,
    val displayName: String,
    val activatedTaxonomyVersion: String,
    val activatedKnowledgePackVersion: String,
    val manifestFingerprint: String,
    val activationGeneration: Long,
)

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
    masteryContext: TutorMasteryContext = TutorMasteryContext.EMPTY,
    provider: ProviderCapabilitySnapshot,
    attempt: Int,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    modeVersion: Long = 0,
    learningWritePermissionVersion: Long = 0,
): String = tutorPlanRequestId(
    question = session.toTutorQuestionContext(),
    masteryContext = masteryContext,
    provider = provider,
    attempt = attempt,
    cycleOrdinal = cycleOrdinal,
    priorConversationMemory = priorConversationMemory,
    priorCycleStudentMessages = priorCycleStudentMessages,
    priorTurns = priorTurns,
    explanationMode = explanationMode,
    modeVersion = modeVersion,
    learningWritePermissionVersion = learningWritePermissionVersion,
)

internal fun tutorPlanRequestId(
    question: TutorQuestionContext,
    masteryContext: TutorMasteryContext = TutorMasteryContext.EMPTY,
    provider: ProviderCapabilitySnapshot,
    attempt: Int,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    modeVersion: Long = 0,
    learningWritePermissionVersion: Long = 0,
): String {
    require(attempt >= 0)
    require(modeVersion >= 0)
    require(learningWritePermissionVersion >= 0)
    val providerVersion = sha256Hex(provider.providerConfigurationVersion).take(16)
    val sessionFingerprint = sha256Hex(
        buildString {
            appendLengthPrefixed(question.sessionId)
            append('\n').append(cycleOrdinal)
            appendLengthPrefixed(explanationMode.name)
            append('\n').append(modeVersion)
            append('\n').append(learningWritePermissionVersion)
            TutorGuidancePolicy.projectTeachingConstraints(masteryContext, question)
                .forEach { guidance ->
                    appendLengthPrefixed(guidance.ref)
                    appendLengthPrefixed(guidance.label)
                    appendLengthPrefixed(guidance.constraint.name)
            }
            question.directKnowledgeNodeIds.sorted().forEach { knowledgeNodeId ->
                appendLengthPrefixed(knowledgeNodeId)
            }
            question.relatedKnowledgeNodeIds.sorted().forEach { knowledgeNodeId ->
                appendLengthPrefixed(knowledgeNodeId)
            }
            question.questionKnowledgeNodes.forEach { node ->
                appendLengthPrefixed(node.canonicalFingerprint)
            }
            question.fallbackKnowledgeNodes.forEach { node ->
                appendLengthPrefixed(node.canonicalFingerprint)
            }
            question.relatedKnowledgeNodes.forEach { node ->
                appendLengthPrefixed(node.canonicalFingerprint)
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

internal fun buildTutorPlanRequest(
    session: ConfirmedTutorSession,
    masteryContext: TutorMasteryContext,
    provider: ProviderCapabilitySnapshot,
    requestId: String,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    modeVersion: Long = 0,
    learningWritePermissionVersion: Long = 0,
): ModelTaskRequest = buildTutorPlanRequest(
    question = session.toTutorQuestionContext().copy(
        questionKnowledgeNodes = masteryContext.summaries
            .take(TutorMasteryContextRequest.MAX_QUESTION_KNOWLEDGE_NODES)
            .map { summary -> summary.knowledgeNode },
        fallbackKnowledgeNodes = masteryContext.summaries
            .drop(TutorMasteryContextRequest.MAX_QUESTION_KNOWLEDGE_NODES)
            .map { summary -> summary.knowledgeNode },
        relatedKnowledgeNodes =
            masteryContext.relatedSummaries
                .map { summary -> summary.knowledgeNode },
        relatedKnowledgeNodeIds =
            masteryContext.relatedSummaries
                .map { summary -> summary.knowledgeNode.knowledgeNodeId }
                .toSet(),
    ),
    masteryContext = masteryContext,
    provider = provider,
    requestId = requestId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    approvedAtEpochMillis = approvedAtEpochMillis,
    cycleOrdinal = cycleOrdinal,
    priorConversationMemory = priorConversationMemory,
    priorCycleStudentMessages = priorCycleStudentMessages,
    priorTurns = priorTurns,
    explanationMode = explanationMode,
    modeVersion = modeVersion,
    learningWritePermissionVersion = learningWritePermissionVersion,
)

@Suppress("UNUSED_PARAMETER")
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
    masteryContext = TutorMasteryContext.EMPTY,
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
    masteryContext: TutorMasteryContext,
    provider: ProviderCapabilitySnapshot,
    requestId: String,
    occurredAtEpochMillis: Long,
    approvedAtEpochMillis: Long,
    cycleOrdinal: Int = 1,
    priorConversationMemory: TutorConversationMemory? = null,
    priorCycleStudentMessages: List<String> = emptyList(),
    priorTurns: List<TutorTurnHistoryEntry> = emptyList(),
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    modeVersion: Long = 0,
    learningWritePermissionVersion: Long = 0,
    allowLongTermLearningWrites: Boolean = true,
): ModelTaskRequest {
    val teachingConstraints = TutorGuidancePolicy.projectTeachingConstraints(
        masteryContext,
        question,
    )
    val input = TutorPlanInput(
        sessionId = question.sessionId,
        draftRevisionNumber = question.revisionNumber,
        subject = question.subject,
        questionDocument = question.questionDocument.document,
        teachingConstraints = teachingConstraints,
        reviewedTeachingReferences = question.trustedReviewedTeachingReferences,
        cycleOrdinal = cycleOrdinal,
        priorConversationMemory = priorConversationMemory,
        priorCycleStudentMessages = priorCycleStudentMessages,
        turnOrdinal = priorTurns.size + 1,
        priorTurns = priorTurns,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        learningWritePermissionVersion = learningWritePermissionVersion,
        allowLongTermLearningWrites = allowLongTermLearningWrites,
    )
    val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
        ModelEgressManifest(
            authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
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

@Suppress("UNUSED_PARAMETER")
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
    return buildTutorPlanRequest(
        question = question,
        masteryContext = TutorMasteryContext.EMPTY,
        provider = provider,
        requestId = requestId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        approvedAtEpochMillis = approvedAtEpochMillis,
        cycleOrdinal = cycleOrdinal,
        priorConversationMemory = priorConversationMemory,
        priorCycleStudentMessages = priorCycleStudentMessages,
        priorTurns = priorTurns,
    )
}

internal fun tutorRespondRequestId(
    question: TutorQuestionContext,
    masteryContext: TutorMasteryContext = TutorMasteryContext.EMPTY,
    provider: ProviderCapabilitySnapshot,
    responseOrdinal: Int,
    cycleOrdinal: Int,
    turnOrdinal: Int,
    studentMessage: String,
    visibleTutorContextMarkdown: String?,
    priorMessages: List<TutorChatHistoryEntry>,
    requestedMove: TutorMoveType? = null,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    modeVersion: Long = 0,
    learningWritePermissionVersion: Long = 0,
    selectedChoiceId: String? = null,
    attempt: Int,
): String {
    require(responseOrdinal > 0)
    require(cycleOrdinal > 0)
    require(turnOrdinal in 1..TutorPlanInput.MAX_TURNS)
    require(attempt >= 0)
    require(modeVersion >= 0)
    require(learningWritePermissionVersion >= 0)
    val conversationFingerprint = sha256Hex(
        buildString {
            appendLengthPrefixed(question.sessionId)
            appendLengthPrefixed(question.questionDocument.document.id)
            appendLengthPrefixed(
                CapturedQuestionDocumentFingerprint.of(question.questionDocument),
            )
            appendLengthPrefixed(question.revisionNumber.toString())
            appendLengthPrefixed(responseOrdinal.toString())
            appendLengthPrefixed(cycleOrdinal.toString())
            appendLengthPrefixed(turnOrdinal.toString())
            appendLengthPrefixed(studentMessage)
            appendLengthPrefixed(selectedChoiceId)
            appendLengthPrefixed(visibleTutorContextMarkdown)
            appendLengthPrefixed(requestedMove?.name)
            appendLengthPrefixed(explanationMode.name)
            appendLengthPrefixed(modeVersion.toString())
            appendLengthPrefixed(learningWritePermissionVersion.toString())
            TutorGuidancePolicy.projectTeachingConstraints(masteryContext, question)
                .forEach { guidance ->
                    appendLengthPrefixed(guidance.ref)
                    appendLengthPrefixed(guidance.label)
                    appendLengthPrefixed(guidance.constraint.name)
                }
            priorMessages.forEach { message ->
                appendLengthPrefixed(message.studentMessage)
                appendLengthPrefixed(message.assistantMarkdown)
            }
            question.reviewedTeachingReferences.forEach { reference ->
                appendLengthPrefixed(reference.materialId)
            }
            question.directKnowledgeNodeIds.sorted().forEach { knowledgeNodeId ->
                appendLengthPrefixed(knowledgeNodeId)
            }
            question.questionKnowledgeNodes.forEach { node ->
                appendLengthPrefixed(node.canonicalFingerprint)
            }
            question.fallbackKnowledgeNodes.forEach { node ->
                appendLengthPrefixed(node.canonicalFingerprint)
            }
            question.relatedKnowledgeNodes.forEach { node ->
                appendLengthPrefixed(node.canonicalFingerprint)
            }
        },
    ).take(24)
    val providerFingerprint = sha256Hex(provider.providerConfigurationVersion).take(12)
    return "tutor-respond:$conversationFingerprint:${question.revisionNumber}:" +
        "$responseOrdinal:$providerFingerprint:$TUTOR_RESPOND_PROMPT_POLICY_VERSION:$attempt"
}

internal fun ModelTaskRequest.matchesTutorRuntimeAuthority(
    explanationMode: TutorExplanationMode? = null,
    modeVersion: Long,
    learningWritePermissionVersion: Long,
): Boolean = when (val tutorInput = input) {
    is TutorPlanInput ->
        (explanationMode == null || tutorInput.explanationMode == explanationMode) &&
            tutorInput.modeVersion == modeVersion &&
            tutorInput.learningWritePermissionVersion == learningWritePermissionVersion

    is TutorRespondInput ->
        (explanationMode == null || tutorInput.explanationMode == explanationMode) &&
            tutorInput.modeVersion == modeVersion &&
            tutorInput.learningWritePermissionVersion == learningWritePermissionVersion

    else -> false
}

internal fun buildTutorRespondRequest(
    question: TutorQuestionContext,
    masteryContext: TutorMasteryContext,
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
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    modeVersion: Long = 0,
    learningWritePermissionVersion: Long = 0,
    allowLongTermLearningWrites: Boolean = true,
    selectedChoice: TutorVisibleChoice? = null,
): ModelTaskRequest {
    if (selectedChoice != null) {
        require(selectedChoice.labelMarkdown == studentMessage) {
            "Tutor response choice label does not match the selected choice id"
        }
    }
    val input = TutorRespondInput(
        sessionId = question.sessionId,
        draftRevisionNumber = question.revisionNumber,
        subject = question.subject,
        questionDocument = question.questionDocument.document,
        teachingConstraints = TutorGuidancePolicy.projectTeachingConstraints(
            masteryContext,
            question,
        ),
        reviewedTeachingReferences = question.trustedReviewedTeachingReferences,
        responseOrdinal = responseOrdinal,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        studentMessage = studentMessage,
        selectedChoiceId = selectedChoice?.id,
        visibleTutorContextMarkdown = visibleTutorContextMarkdown,
        priorMessages = priorMessages,
        requestedMove = requestedMove,
        explanationMode = explanationMode,
        modeVersion = modeVersion,
        learningWritePermissionVersion = learningWritePermissionVersion,
        allowLongTermLearningWrites = allowLongTermLearningWrites,
    )
    val manifest = if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
        ModelEgressManifest(
            authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
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

@Suppress("UNUSED_PARAMETER")
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
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    selectedChoice: TutorVisibleChoice? = null,
): ModelTaskRequest {
    return buildTutorRespondRequest(
        question = question,
        masteryContext = TutorMasteryContext.EMPTY,
        provider = provider,
        requestId = requestId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        approvedAtEpochMillis = approvedAtEpochMillis,
        responseOrdinal = responseOrdinal,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        studentMessage = studentMessage,
        visibleTutorContextMarkdown = visibleTutorContextMarkdown,
        priorMessages = priorMessages,
        requestedMove = requestedMove,
        explanationMode = explanationMode,
        selectedChoice = selectedChoice,
    )
}

internal fun tutorVisualGenerateRequestId(
    question: TutorQuestionContext,
    provider: ProviderCapabilitySnapshot,
    sourceAssets: List<TutorVisualSourceAssetScope>,
    anchor: TutorVisualTurnAnchor,
    focusMarkdown: String,
    explanationMarkdown: String,
    semanticFence: String = "",
    sourceFacts: List<TutorVisualSourceFact> = TutorVisualSourceFactExtractor.extract(
        capturedDocument = question.questionDocument,
        sourceAssets = sourceAssets
            .sortedBy(TutorVisualSourceAssetScope::pageIndex)
            .map(TutorVisualSourceAssetScope::toSourceRef),
    ),
): String {
    val contentFingerprint = sha256Hex(
        buildString {
            appendLengthPrefixed(question.sessionId)
            appendLengthPrefixed(question.questionDocument.document.id)
            appendLengthPrefixed(
                CapturedQuestionDocumentFingerprint.of(question.questionDocument),
            )
            appendLengthPrefixed(question.revisionNumber.toString())
            appendLengthPrefixed(question.subject)
            appendLengthPrefixed(anchor.surface.name)
            appendLengthPrefixed(anchor.cycleOrdinal.toString())
            appendLengthPrefixed(anchor.turnOrdinal.toString())
            appendLengthPrefixed(anchor.responseOrdinal?.toString())
            appendLengthPrefixed(focusMarkdown)
            appendLengthPrefixed(explanationMarkdown)
            appendLengthPrefixed(semanticFence)
            sourceAssets.sortedBy(TutorVisualSourceAssetScope::pageIndex).forEach { asset ->
                appendLengthPrefixed(asset.pageIndex.toString())
                appendLengthPrefixed(asset.assetId)
                appendLengthPrefixed(asset.sha256)
                appendLengthPrefixed(asset.selectedRegion?.let { region ->
                    "${region.left},${region.top},${region.right},${region.bottom}"
                })
            }
            sourceFacts.sortedBy(TutorVisualSourceFact::factId).forEach { fact ->
                appendLengthPrefixed(fact.factId)
                appendLengthPrefixed(fact.anchorSha256)
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
    semanticFence: String = "",
): ModelTaskRequest {
    require(provider.supports(ModelTaskKind.TUTOR_VISUAL_GENERATE))
    val orderedAssets = sourceAssets.sortedBy(TutorVisualSourceAssetScope::pageIndex)
    require(orderedAssets.map(TutorVisualSourceAssetScope::pageIndex) == orderedAssets.indices.toList())
    val sourceRefs = orderedAssets.map(TutorVisualSourceAssetScope::toSourceRef)
    val sourceFacts = TutorVisualSourceFactExtractor.extract(
        capturedDocument = question.questionDocument,
        sourceAssets = sourceRefs,
    )
    val requestId = tutorVisualGenerateRequestId(
        question = question,
        provider = provider,
        sourceAssets = orderedAssets,
        anchor = anchor,
        focusMarkdown = focusMarkdown,
        explanationMarkdown = explanationMarkdown,
        semanticFence = semanticFence,
        sourceFacts = sourceFacts,
    )
    val input = TutorVisualGenerateInput(
        sessionId = question.sessionId,
        draftRevisionNumber = question.revisionNumber,
        subject = question.subject,
        questionDocument = question.questionDocument.document,
        sourceAssets = sourceRefs,
        sourceFacts = sourceFacts,
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
            input = input,
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
            appendLengthPrefixed(generated.scene?.let(TutorVisualSceneFingerprint::of))
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
    require(generationInput.subject == question.subject)
    require(generationInput.questionDocument == question.questionDocument.document)
    require(generated.sessionId == question.sessionId)
    require(generated.draftRevisionNumber == question.revisionNumber)
    require(generated.questionDocumentId == question.questionDocument.document.id)
    require(generated.anchor == generationInput.anchor)
    val orderedAssets = sourceAssets.sortedBy(TutorVisualSourceAssetScope::pageIndex)
    require(
        orderedAssets.map(TutorVisualSourceAssetScope::toSourceRef) == generationInput.sourceAssets,
    ) { "Tutor visual review must reuse the exact generation image scope" }
    require(
        TutorVisualSourceFactExtractor.extract(
            capturedDocument = question.questionDocument,
            sourceAssets = generationInput.sourceAssets,
        ) == generationInput.sourceFacts,
    ) { "Tutor visual review must reuse the exact locally minted source facts" }
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
        sourceFacts = generationInput.sourceFacts,
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
            input = input,
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
    input: com.tingyun.smartmistakebook.core.model.ModelTaskInput,
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
        authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
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

internal fun TutorGuidancePolicy.projectTeachingConstraints(
    masteryContext: TutorMasteryContext,
    question: TutorQuestionContext,
): List<TutorKnowledgeGuidance> {
    val request = question.masteryContextRequestOrNull() ?: return emptyList()
    if (!masteryContext.projectionIsCurrent) return genericTeachingConstraint()
    val boundedContext = masteryContext.boundedTo(request)
    val summaryByFingerprint =
        (boundedContext.summaries + boundedContext.relatedSummaries).associateBy { summary ->
            summary.knowledgeNode.canonicalFingerprint
        }
    val resolver = question.trustedKnowledgeLabelResolver ?: return genericTeachingConstraint()
    val projected = (request.allowedKnowledgeNodes + boundedContext.relatedKnowledgeNodes)
        .mapIndexed { index, node ->
            val summary = summaryByFingerprint[node.canonicalFingerprint]
                ?: return genericTeachingConstraint()
            val trustedLabel = runCatching {
                resolver.resolveFromActivatedSnapshot(node)
            }.getOrNull()
                ?.takeIf { resolution -> resolution.isCurrentSafeResolutionOf(node) }
                ?: return genericTeachingConstraint()
            TutorKnowledgeGuidance(
                ref = "current-question-point-${index + 1}",
                label = trustedLabel.displayName,
                constraint = summary.status.toTutorTeachingConstraint(),
            )
        }
    return projected
        .groupBy(TutorKnowledgeGuidance::label)
        .values
        .map { sameLabel ->
            sameLabel.first().copy(
                constraint = when {
                    sameLabel.any {
                        it.constraint == TutorTeachingConstraint.EXPLAIN_DIRECTLY
                    } -> TutorTeachingConstraint.EXPLAIN_DIRECTLY
                    sameLabel.any {
                        it.constraint == TutorTeachingConstraint.MAY_GUIDE
                    } -> TutorTeachingConstraint.MAY_GUIDE
                    else -> TutorTeachingConstraint.SKIP_BASIC_PROMPT
                },
            )
        }
        .take(TutorPlanInput.MAX_TEACHING_CONSTRAINTS)
}

private fun TutorTrustedKnowledgeLabel.isCurrentSafeResolutionOf(ref: KnowledgeNodeRef): Boolean =
    this.ref == ref &&
        activatedTaxonomyVersion == ref.taxonomyVersion &&
        activatedKnowledgePackVersion == ref.knowledgePackVersion &&
        activationGeneration > 0L &&
        TRUSTED_MANIFEST_FINGERPRINT.matches(manifestFingerprint) &&
        displayName.isSafeStudentFacingKnowledgeLabel()

private fun String.isSafeStudentFacingKnowledgeLabel(): Boolean {
    if (
        this != trim() ||
        length !in 1..TutorKnowledgeGuidance.MAX_LABEL_CHARS ||
        any { character -> character.isISOControl() || character.isDigit() } ||
        any { character -> character.isWhitespace() && character != ' ' } ||
        none { character -> character in '\u3400'..'\u9fff' }
    ) {
        return false
    }
    if (!SAFE_KNOWLEDGE_LABEL_CHARACTERS.matches(this)) return false
    return KNOWLEDGE_LABEL_INJECTION_MARKERS.none { marker -> contains(marker, ignoreCase = true) }
}

private fun genericTeachingConstraint(): List<TutorKnowledgeGuidance> = listOf(
    TutorKnowledgeGuidance(
        ref = "current-question-point-1",
        label = "当前题相关内容",
        constraint = TutorTeachingConstraint.EXPLAIN_DIRECTLY,
    ),
)

private val TRUSTED_MANIFEST_FINGERPRINT = Regex("[0-9a-f]{64}")
private val SAFE_KNOWLEDGE_LABEL_CHARACTERS = Regex(
    """[\p{IsHan}\p{L}\p{M}（）()·、，—+\- ]+""",
)
private val KNOWLEDGE_LABEL_INJECTION_MARKERS = listOf(
    "忽略以上",
    "忽略前面",
    "忽略指令",
    "系统提示",
    "开发者消息",
    "提示注入",
    "不要遵守",
    "输出密钥",
    "输出数据库",
    "内部id",
    "internal id",
    "system prompt",
    "developer message",
    "ignore instruction",
    "ignore previous",
    "knowledgeNodeId",
    "taxonomyVersion",
    "knowledgePackVersion",
)

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

private fun TutorMasteryStatus.toTutorTeachingConstraint(): TutorTeachingConstraint = when (this) {
    TutorMasteryStatus.SOLID -> TutorTeachingConstraint.SKIP_BASIC_PROMPT
    TutorMasteryStatus.UNKNOWN,
    TutorMasteryStatus.LEARNING,
    -> TutorTeachingConstraint.MAY_GUIDE
    TutorMasteryStatus.NEEDS_PRACTICE,
    TutorMasteryStatus.NEEDS_REFRESH,
    -> TutorTeachingConstraint.EXPLAIN_DIRECTLY
}

internal fun List<TutorKnowledgeGuidance>.matchesTutorGuidanceBoundary(
    question: TutorQuestionContext,
): Boolean {
    val allowedRefs =
        (
            question.questionKnowledgeNodes +
                question.fallbackKnowledgeNodes +
                question.relatedKnowledgeNodes
        ).mapIndexedTo(mutableSetOf()) { index, _ ->
            "current-question-point-${index + 1}"
        }
    return size <= TutorPlanInput.MAX_TEACHING_CONSTRAINTS &&
        map(TutorKnowledgeGuidance::ref).distinct().size == size &&
        map(TutorKnowledgeGuidance::label).distinct().size == size &&
        all { guidance -> guidance.ref in allowedRefs }
}
