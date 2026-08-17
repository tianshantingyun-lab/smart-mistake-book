package com.tingyun.smartmistakebook.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_RELATION_KINDS
import com.tingyun.smartmistakebook.core.model.AppErrorCode
import com.tingyun.smartmistakebook.core.model.RecoveryAction
import com.tingyun.smartmistakebook.core.model.UserRecoverableError
import com.tingyun.smartmistakebook.core.model.userRecoverableError
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.PaperDivider
import com.tingyun.smartmistakebook.core.ui.SectionHeader
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun MistakeOrganizationSection(
    key: MistakeRevisionKey,
    organizationRepository: MistakeOrganizationRepository,
    modelTasks: ModelTaskRepository,
    profile: StudyProfileOverview,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    onOpenRelatedMistake: (String) -> Unit = {},
    onOpenModelSettings: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf<ProviderCapabilitySnapshot?>(null) }
    var capabilityLoadFailed by rememberSaveable(key) { mutableStateOf(false) }
    var capabilityLoadAttempt by rememberSaveable(key) { mutableStateOf(0) }
    var preparation by remember(key) { mutableStateOf<MistakeOrganizationPreparation?>(null) }
    var task by remember(key) { mutableStateOf<ModelTaskSnapshot?>(null) }
    var recoveryComplete by remember(key, modelTasks) { mutableStateOf(false) }
    var trackedRequestId by remember(key, modelTasks) { mutableStateOf<String?>(null) }
    var requestToResume by remember(key, modelTasks) {
        mutableStateOf<ModelTaskRequest?>(null)
    }
    var locallyResumedRequestId by remember(key, modelTasks) { mutableStateOf<String?>(null) }
    var recoveredPendingRequest by remember(key, modelTasks) {
        mutableStateOf<ModelTaskRequest?>(null)
    }
    var isContinuingPausedOrganization by remember(key, modelTasks) { mutableStateOf(false) }
    var message by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var error by remember(key) { mutableStateOf<UserRecoverableError?>(null) }
    var isPreparing by rememberSaveable(key) { mutableStateOf(false) }
    var attempt by rememberSaveable(key) { mutableStateOf(0) }
    var preparationDismissed by rememberSaveable(key) { mutableStateOf(false) }
    var applyState by remember(key) {
        mutableStateOf<AutomaticOrganizationState>(AutomaticOrganizationState.Idle)
    }
    var applyRetry by rememberSaveable(key) { mutableStateOf(0) }
    var correctionVisible by rememberSaveable(key) { mutableStateOf(false) }
    val confirmedFlow = remember(key, organizationRepository) {
        organizationRepository.observeConfirmed(key)
    }
    val confirmed by confirmedFlow.collectAsStateWithLifecycle(
        initialValue = ConfirmedMistakeOrganization(),
    )
    val restartOrganization: () -> Unit = {
        attempt += 1
        preparation = null
        task = null
        trackedRequestId = null
        requestToResume = null
        recoveredPendingRequest = null
        isContinuingPausedOrganization = false
        applyState = AutomaticOrganizationState.Idle
        correctionVisible = false
        preparationDismissed = false
        message = null
        error = null
    }
    val retryAutomaticApply: () -> Unit = {
        applyState = AutomaticOrganizationState.Idle
        applyRetry += 1
    }
    val startPreparedOrganization: () -> Unit = {
        scope.launch {
            message = null
            error = null
            try {
                val now = System.currentTimeMillis()
                val prepared = checkNotNull(preparation)
                val approvedRequest = prepared.request.copy(
                    egressManifest = prepared.request.egressManifest?.copy(
                        approvedAtEpochMillis = now,
                    ),
                )
                preparation = prepared.copy(request = approvedRequest)
                trackedRequestId = approvedRequest.requestId
                modelTasks.execute(approvedRequest).collect { snapshot ->
                    task = snapshot
                    recoveredPendingRequest = snapshot.request.takeIf {
                        snapshot.status == ModelTaskStatus.RETRYABLE_FAILURE &&
                            it.egressManifest != null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = userRecoverableError(
                    code = AppErrorCode.NETWORK_UNAVAILABLE,
                    title = "整理没有完成",
                    message = "整理失败，请稍后重试",
                    dataSafe = true,
                    primaryAction = RecoveryAction.RETRY,
                )
            }
        }
    }
    val continueRecoveredOrganization: () -> Unit = continueRecoveredOrganization@{
        val persistedRequest = recoveredPendingRequest ?: return@continueRecoveredOrganization
        val availableProvider = provider ?: return@continueRecoveredOrganization
        if (
            !availableProvider.supports(ModelTaskKind.PROBLEM_CLASSIFY) ||
            availableProvider.executionLocation == ModelExecutionLocation.UNAVAILABLE
        ) {
            error = userRecoverableError(
                code = AppErrorCode.PROVIDER_CAPABILITY_MISMATCH,
                title = "暂时无法继续整理",
                message = "暂时无法继续整理",
                dataSafe = true,
                primaryAction = RecoveryAction.OPEN_SETTINGS,
            )
            return@continueRecoveredOrganization
        }
        val resumedRequest = persistedRequest.renewOrganizationRequest(
            provider = availableProvider,
            approvedAtEpochMillis = System.currentTimeMillis(),
        )
        preparation = resumedRequest.toOrganizationPreparation()
        trackedRequestId = resumedRequest.requestId
        isContinuingPausedOrganization = true
        message = null
        error = null
        scope.launch {
            try {
                modelTasks.execute(resumedRequest).collect { snapshot ->
                    preparation = snapshot.toOrganizationPreparation()
                    trackedRequestId = snapshot.request.requestId
                    task = snapshot
                    recoveredPendingRequest = snapshot.request.takeIf {
                        snapshot.status == ModelTaskStatus.RETRYABLE_FAILURE &&
                            it.egressManifest != null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = userRecoverableError(
                    code = AppErrorCode.NETWORK_UNAVAILABLE,
                    title = "整理没有完成",
                    message = "整理失败，请稍后重试",
                    dataSafe = true,
                    primaryAction = RecoveryAction.RETRY,
                )
            } finally {
                isContinuingPausedOrganization = false
            }
        }
    }

    LaunchedEffect(modelTasks, capabilityLoadAttempt) {
        capabilityLoadFailed = false
        provider = null
        provider = try {
            modelTasks.capabilities()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            capabilityLoadFailed = true
            null
        }
    }

    // Preparing before durable history arrives can reuse a request id with a different timestamp.
    LaunchedEffect(key, modelTasks) {
        modelTasks.observeBySubject(key.problemRevisionId, ModelTaskKind.PROBLEM_CLASSIFY)
            .collect { snapshots ->
                if (!recoveryComplete) {
                    val matchingTasks = snapshots.filter { it.matchesOrganization(key) }
                    val recovered = matchingTasks.maxWithOrNull(
                        compareBy<ModelTaskSnapshot> { it.updatedAtEpochMillis }
                            .thenBy { it.stateVersion }
                            .thenBy { it.createdAtEpochMillis }
                            .thenBy { it.request.requestId },
                    )
                    recoveryComplete = true
                    if (recovered != null) {
                        trackedRequestId = recovered.request.requestId
                        preparation = recovered.toOrganizationPreparation()
                        task = recovered
                        attempt = maxOf(attempt, matchingTasks.distinctBy { it.request.requestId }.lastIndex)
                        if (
                            !recovered.status.isTerminal ||
                            recovered.status == ModelTaskStatus.RETRYABLE_FAILURE
                        ) {
                            recoveredPendingRequest = recovered.request
                        }
                    }
                } else {
                    snapshots.firstOrNull { it.request.requestId == trackedRequestId }
                        ?.takeIf { it.matchesOrganization(key) }
                        ?.let { persisted ->
                            preparation = persisted.toOrganizationPreparation()
                            task = persisted
                            if (
                                persisted.status.isTerminal &&
                                persisted.status != ModelTaskStatus.RETRYABLE_FAILURE &&
                                recoveredPendingRequest?.requestId == persisted.request.requestId
                            ) {
                                recoveredPendingRequest = null
                            }
                        }
                }
            }
    }

    LaunchedEffect(provider, recoveredPendingRequest?.requestId) {
        val persistedRequest = recoveredPendingRequest ?: return@LaunchedEffect
        val availableProvider = provider ?: return@LaunchedEffect
        if (
            availableProvider.executionLocation == ModelExecutionLocation.LOCAL_NO_EGRESS &&
            persistedRequest.egressManifest == null
        ) {
            recoveredPendingRequest = null
            requestToResume = persistedRequest
        }
    }

    LaunchedEffect(modelTasks, requestToResume?.requestId) {
        val persistedRequest = requestToResume ?: return@LaunchedEffect
        if (locallyResumedRequestId == persistedRequest.requestId) return@LaunchedEffect
        locallyResumedRequestId = persistedRequest.requestId
        message = null
        error = null
        try {
            modelTasks.execute(persistedRequest).collect { snapshot ->
                task = snapshot
                if (
                    snapshot.status == ModelTaskStatus.RETRYABLE_FAILURE &&
                    snapshot.request.egressManifest != null
                ) {
                    recoveredPendingRequest = snapshot.request
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = userRecoverableError(
                code = AppErrorCode.NETWORK_UNAVAILABLE,
                title = "整理没有完成",
                message = "整理失败，请稍后重试",
                dataSafe = true,
                primaryAction = RecoveryAction.RETRY,
            )
        }
    }

    LaunchedEffect(key, provider, attempt, preparationDismissed, recoveryComplete) {
        if (!recoveryComplete) return@LaunchedEffect
        val availableProvider = provider ?: return@LaunchedEffect
        if (
            preparationDismissed ||
            preparation != null ||
            task != null ||
            !availableProvider.supports(ModelTaskKind.PROBLEM_CLASSIFY) ||
            availableProvider.executionLocation == ModelExecutionLocation.UNAVAILABLE
        ) {
            return@LaunchedEffect
        }
        isPreparing = true
        message = null
        error = null
        try {
            val now = System.currentTimeMillis()
            preparation = organizationRepository.prepare(
                key = key,
                profile = profile,
                provider = availableProvider,
                attempt = attempt,
                occurredAtEpochMillis = now,
                approvedAtEpochMillis = now,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = userRecoverableError(
                code = AppErrorCode.PROVIDER_NOT_CONFIGURED,
                title = "暂时无法准备智能整理",
                message = "暂时无法准备智能整理",
                dataSafe = true,
                primaryAction = RecoveryAction.OPEN_SETTINGS,
            )
            preparationDismissed = true
        } finally {
            isPreparing = false
        }
    }

    LaunchedEffect(task?.request?.requestId, task?.status, applyRetry) {
        val successfulTask = task?.takeIf { it.status == ModelTaskStatus.SUCCEEDED }
            ?: return@LaunchedEffect
        val requestId = successfulTask.request.requestId
        if (applyState.requestId == requestId) return@LaunchedEffect
        applyState = AutomaticOrganizationState.Applying(requestId)
        applyState = try {
            val confirmation = organizationRepository.applySuccessfulOrganization(requestId)
            if (confirmation.applied) {
                AutomaticOrganizationState.Applied(requestId)
            } else if (confirmation.preservedUserCorrection) {
                AutomaticOrganizationState.PreservedUserCorrection(requestId)
            } else {
                AutomaticOrganizationState.Incomplete(requestId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AutomaticOrganizationState.Failed(
                requestId = requestId,
                message = "这次整理没有完成，请再试一次",
            )
        }
    }

    PaperDivider(Modifier.padding(vertical = 18.dp))
    SectionHeader("智能整理")
    Spacer(Modifier.height(10.dp))
    ConfirmedOrganizationSummary(
        confirmed = confirmed,
        catalogEntries = catalogEntries,
        onOpenRelatedMistake = onOpenRelatedMistake,
    )
    Spacer(Modifier.height(12.dp))

    val currentProvider = provider
    val output = task?.output as? ProblemOrganizationOutput
    val surfaceState = resolveMistakeOrganizationSurface(
        MistakeOrganizationSurfaceFacts(
            providerAvailability = when {
                currentProvider == null && capabilityLoadFailed ->
                    OrganizationProviderAvailability.LOAD_FAILED
                currentProvider == null -> OrganizationProviderAvailability.LOADING
                !currentProvider.supports(ModelTaskKind.PROBLEM_CLASSIFY) ||
                    currentProvider.executionLocation == ModelExecutionLocation.UNAVAILABLE ->
                    OrganizationProviderAvailability.UNAVAILABLE
                else -> OrganizationProviderAvailability.READY
            },
            hasPreparation = preparation != null,
            isPreparing = isPreparing,
            preparationDismissed = preparationDismissed,
            hasRecoveredRequest = recoveredPendingRequest != null,
            isContinuingRecoveredRequest = isContinuingPausedOrganization,
            taskStatus = task?.status,
            taskMessage = task?.userMessage,
            taskRequestId = task?.request?.requestId,
            hasUsableOutput = output != null,
            applyState = applyState,
        ),
    )
    when (val state = surfaceState) {
        MistakeOrganizationSurfaceState.CapabilityLoadFailed -> ModelCapabilityFailure(
            onRetry = { capabilityLoadAttempt += 1 },
            onOpenModelSettings = onOpenModelSettings,
        )

        MistakeOrganizationSurfaceState.CapabilityLoading,
        MistakeOrganizationSurfaceState.PreparationLoading,
        -> OrganizationLoading()

        MistakeOrganizationSurfaceState.ProviderUnavailable ->
            ModelUnavailable(onOpenModelSettings)

        is MistakeOrganizationSurfaceState.Consent -> {
            OrganizationConsentCard(
                preparation = checkNotNull(preparation),
                provider = checkNotNull(currentProvider),
                isRunning = state.running,
                isPaused = state.paused,
                onCancel = {
                    preparation = null
                    task = null
                    trackedRequestId = null
                    requestToResume = null
                    recoveredPendingRequest = null
                    message = null
                    preparationDismissed = true
                },
                onApprove = if (state.paused) {
                    continueRecoveredOrganization
                } else {
                    startPreparedOrganization
                },
            )
        }

        MistakeOrganizationSurfaceState.PreparationRetry -> OutlinedButton(
            onClick = restartOrganization,
            modifier = Modifier.testTag("mistake_organization_prepare_retry"),
        ) { Text("再次整理") }

        is MistakeOrganizationSurfaceState.ExecutionFailed -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = JadeSoft.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, Outline),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.message, color = InkSecondary)
                    OutlinedButton(
                        onClick = restartOrganization,
                    ) { Text("重新生成") }
                }
            }
        }

        is MistakeOrganizationSurfaceState.Running -> OrganizationRunning(state.message)

        MistakeOrganizationSurfaceState.UnusableResult -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "这次整理结果无法使用",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("mistake_organization_unusable_result"),
                )
                OutlinedButton(
                    onClick = restartOrganization,
                    modifier = Modifier.testTag("mistake_organization_unusable_retry"),
                ) { Text("重新整理") }
            }
        }

        MistakeOrganizationSurfaceState.Applying -> OrganizationApplying()

        MistakeOrganizationSurfaceState.Incomplete -> IncompleteOrganizationCard(
            onRetry = restartOrganization,
        )

        is MistakeOrganizationSurfaceState.ApplyFailed -> OrganizationApplyFailure(
            message = state.message,
            onRetry = retryAutomaticApply,
        )

        MistakeOrganizationSurfaceState.PreservedUserCorrection -> Text(
            text = "已保留你修改过的分类",
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("mistake_organization_user_correction_preserved"),
        )

        MistakeOrganizationSurfaceState.Applied -> {
            Text(
                text = "已自动整理到错题本",
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("mistake_organization_applied"),
            )
            TextButton(
                onClick = { correctionVisible = !correctionVisible },
                modifier = Modifier.testTag("mistake_organization_correct_toggle"),
            ) {
                Text(if (correctionVisible) "收起修改" else "分类有误，修改")
            }
            if (correctionVisible) {
                OrganizationCorrectionEditor(
                    requestId = checkNotNull(task).request.requestId,
                    input = checkNotNull(task).request.input as ProblemOrganizationInput,
                    output = checkNotNull(output),
                    confirmed = confirmed,
                    organizationRepository = organizationRepository,
                    onConfirmed = {
                        correctionVisible = false
                        message = "已保存分类修改"
                    },
                    onFailure = { failure -> message = failure },
                )
            }
        }
    }
    message?.let {
        Spacer(Modifier.height(10.dp))
        Text(
            text = it,
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("mistake_organization_message"),
        )
    }
    error?.let { organizationError ->
        Spacer(Modifier.height(10.dp))
        Text(
            text = organizationError.message,
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("mistake_organization_error"),
        )
    }
    Spacer(Modifier.height(12.dp))
}

private fun ModelTaskSnapshot.matchesOrganization(key: MistakeRevisionKey): Boolean {
    val input = request.input as? ProblemOrganizationInput ?: return false
    return input.problemId == key.problemId && input.problemRevisionId == key.problemRevisionId
}

private fun ModelTaskSnapshot.toOrganizationPreparation(): MistakeOrganizationPreparation {
    return request.toOrganizationPreparation()
}

private fun ModelTaskRequest.toOrganizationPreparation(): MistakeOrganizationPreparation {
    val input = input as ProblemOrganizationInput
    return MistakeOrganizationPreparation(
        request = this,
        relatedCandidateTitles = input.relationCandidates.map { it.title },
        knowledgeContextCount = input.knowledgeBaseNodes.size,
    )
}

private fun ModelTaskRequest.renewOrganizationRequest(
    provider: ProviderCapabilitySnapshot,
    approvedAtEpochMillis: Long,
): ModelTaskRequest {
    val newRequestId = "problem-organization-resume:${UUID.randomUUID()}"
    val newManifest = when (provider.executionLocation) {
        ModelExecutionLocation.EXTERNAL_PROVIDER -> ModelEgressManifest(
            authorizationId = "authorization:${UUID.randomUUID()}",
            subjectId = input.subjectId,
            purpose = ModelEgressPurpose.CLASSIFICATION,
            authorizedTaskKinds = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = ModelPromptPolicyVersions.PROBLEM_ORGANIZATION,
            approvedAtEpochMillis = maxOf(approvedAtEpochMillis, occurredAtEpochMillis),
            assets = emptyList(),
            disclosedData = ModelEgressManifest.PROBLEM_ORGANIZATION_DISCLOSURE,
            prohibitedData = ModelEgressManifest.PROBLEM_ORGANIZATION_PROHIBITED_DATA,
        )
        ModelExecutionLocation.LOCAL_NO_EGRESS -> null
        ModelExecutionLocation.UNAVAILABLE -> error("Organization provider is unavailable")
    }
    return ModelTaskRequest(
        requestId = newRequestId,
        input = input,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = newManifest,
    )
}

@Composable
private fun OrganizationApplying() {
    Row(
        modifier = Modifier.testTag("mistake_organization_applying"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        Text("正在整理到错题本…", modifier = Modifier.padding(start = 10.dp), color = InkSecondary)
    }
}

@Composable
private fun IncompleteOrganizationCard(onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("mistake_organization_incomplete"),
        shape = RoundedCornerShape(10.dp),
        color = JadeSoft.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("暂未整理完整", color = Ink, fontWeight = FontWeight.SemiBold)
            Text("现有分类不会被覆盖。", color = InkSecondary, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.testTag("mistake_organization_retry"),
            ) { Text("再整理一次") }
        }
    }
}

@Composable
private fun OrganizationApplyFailure(message: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("mistake_organization_apply_failure"),
        shape = RoundedCornerShape(10.dp),
        color = JadeSoft.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = InkSecondary)
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.testTag("mistake_organization_apply_retry"),
            ) { Text("再整理一次") }
        }
    }
}

@Composable
private fun ConfirmedOrganizationSummary(
    confirmed: ConfirmedMistakeOrganization,
    catalogEntries: List<StudyCatalogEntry>,
    onOpenRelatedMistake: (String) -> Unit,
) {
    val contentClassifications = confirmed.classifications.filter {
        it.dimension in PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
    }
    val contentRelations = confirmed.relations.filter {
        it.kind in PROBLEM_ORGANIZATION_RELATION_KINDS
    }
    if (contentClassifications.isEmpty() && contentRelations.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("mistake_organization_confirmed"),
        shape = RoundedCornerShape(10.dp),
        color = JadeSoft.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("已整理", color = Ink, fontWeight = FontWeight.SemiBold)
            contentClassifications.groupBy { it.dimension }.forEach { (dimension, items) ->
                Text(
                    text = "${dimension.contentLabel()}：${items.joinToString("、") { it.displayName }}",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (contentRelations.isNotEmpty()) {
                Text(
                    text = "相关题目",
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "相关题目会随整理结果更新。",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                contentRelations.forEachIndexed { index, relation ->
                    val exactTarget = catalogEntries.firstOrNull { entry ->
                        entry.problemId == relation.targetProblemId &&
                            entry.problemRevisionId == relation.targetProblemRevisionId
                    }
                    val newerTarget = catalogEntries.firstOrNull { entry ->
                        entry.problemId == relation.targetProblemId
                    }
                    val targetTitle = exactTarget?.title ?: newerTarget?.title ?: "关联题"
                    val availability = when {
                        exactTarget != null -> "点按打开"
                        newerTarget != null -> "目标题面已更新，请重新整理关系"
                        else -> "已不在当前错题本"
                    }
                    val rowModifier = Modifier
                        .fillMaxWidth()
                        .testTag("mistake_confirmed_relation_$index")
                        .then(
                            if (exactTarget != null) {
                                Modifier.clickable {
                                    onOpenRelatedMistake(exactTarget.entryId)
                                }
                            } else {
                                Modifier
                            },
                        )
                    Surface(
                        modifier = rowModifier,
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, Outline),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = checkNotNull(relation.kind.confirmedRelationLabel()),
                                color = JadeActive,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = targetTitle,
                                color = Ink,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = availability,
                                color = InkSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrganizationLoading() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        Text("正在准备智能整理…", modifier = Modifier.padding(start = 10.dp), color = InkSecondary)
    }
}

@Composable
private fun OrganizationRunning(message: String?) {
    Row(
        modifier = Modifier.testTag("mistake_organization_running"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
        Text(
            text = message?.takeIf { it.isNotBlank() } ?: "正在整理…",
            modifier = Modifier.padding(start = 10.dp),
            color = InkSecondary,
        )
    }
}

@Composable
private fun ModelUnavailable(onOpenModelSettings: () -> Unit) {
    Surface(
        color = JadeSoft.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("配置大模型后，才能自动整理板块、知识点和相关题目。", color = InkSecondary)
            OutlinedButton(onClick = onOpenModelSettings) { Text("去配置模型") }
        }
    }
}

@Composable
private fun ModelCapabilityFailure(
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("mistake_organization_capability_failure"),
        color = JadeSoft.copy(alpha = 0.35f),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("暂时无法读取模型配置", color = InkSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onRetry) { Text("重试") }
                TextButton(onClick = onOpenModelSettings) { Text("检查模型设置") }
            }
        }
    }
}

@Composable
private fun OrganizationConsentCard(
    preparation: MistakeOrganizationPreparation,
    provider: ProviderCapabilitySnapshot,
    isRunning: Boolean,
    isPaused: Boolean = false,
    onCancel: () -> Unit,
    onApprove: () -> Unit,
) {
    val knowledgeScope = if (preparation.knowledgeContextCount > 0) {
        "和相关学科知识目录"
    } else {
        ""
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(
            if (isPaused) "mistake_organization_paused" else "mistake_organization_consent",
        ),
        shape = RoundedCornerShape(10.dp),
        color = JadeSoft.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(
                text = if (isPaused) "整理已暂停" else "确认本次发送范围",
                color = Ink,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "会把当前题面、${preparation.relatedCandidateTitles.size} 道同科目题面" +
                    "${knowledgeScope}发给${provider.providerDisplayName}，只用于整理板块、" +
                    "细化知识点和题目关系；不发送原图或学习记录。",
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onCancel, enabled = !isRunning) { Text("暂不整理") }
                Button(
                    onClick = onApprove,
                    enabled = !isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = JadeActive),
                    modifier = Modifier.testTag(
                        if (isPaused) "mistake_organization_continue" else "mistake_organization_approve",
                    ),
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).height(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(
                        when {
                            isPaused && isRunning -> "正在继续"
                            isPaused -> "继续整理"
                            isRunning -> "正在整理"
                            else -> "同意并整理"
                        },
                    )
                }
            }
        }
    }
}
