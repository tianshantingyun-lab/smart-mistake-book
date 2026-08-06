package com.tingyun.smartmistakebook.feature.capture
import com.tingyun.smartmistakebook.core.ui.Ink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartColors

/**
 * Published, route-facing capture capabilities.
 *
 * This container exposes no database, learner-mastery writer, asset stream, or authority runtime.
 * Its source must remain null until the terminal production bootstrap publishes the real
 * repositories.
 */
class CaptureProductionCapability(
    val captureWorkflow: CaptureWorkflowRepository,
    val batchImports: BatchImportRepository,
    val modelTasks: ScopedModelTaskPort,
)

fun interface CaptureProductionCapabilitySource {
    fun currentCapability(): CaptureProductionCapability?
}

/**
 * Fail-closed capture publication boundary.
 *
 * Model tasks and model asset documents are separate requirements: the former owns durable work,
 * while the latter resolves only the exact locally approved image documents used by that work.
 */
class CaptureProductionCapabilityProvider(
    private val adapterAvailability: ProductionAdapterAvailabilityPort,
    private val capabilitySource: CaptureProductionCapabilitySource,
) {
    fun resolve(): CaptureProductionCapabilityDecision {
        val manifest =
            try {
                adapterAvailability.readManifest()
            } catch (_: Exception) {
                return CaptureProductionCapabilityDecision.Blocked(
                    reason = CaptureProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
                )
            }
        val missingRequiredAdapters =
            REQUIRED_CAPTURE_ADAPTERS - manifest.availableAdapters
        if (missingRequiredAdapters.isNotEmpty()) {
            return CaptureProductionCapabilityDecision.Blocked(
                reason =
                    CaptureProductionCapabilityBlockReason
                        .REQUIRED_ADAPTERS_UNAVAILABLE,
                missingRequiredAdapters = missingRequiredAdapters,
            )
        }
        if (!manifest.isComplete) {
            return CaptureProductionCapabilityDecision.Blocked(
                reason =
                    CaptureProductionCapabilityBlockReason
                        .PRODUCTION_MANIFEST_INCOMPLETE,
            )
        }
        val capability =
            try {
                capabilitySource.currentCapability()
            } catch (_: Exception) {
                null
            }
                ?: return CaptureProductionCapabilityDecision.Blocked(
                    reason =
                        CaptureProductionCapabilityBlockReason
                            .CAPABILITY_UNPUBLISHED,
                )
        return CaptureProductionCapabilityDecision.Available(capability)
    }

    companion object {
        val REQUIRED_CAPTURE_ADAPTERS: Set<ProductionAdapter> =
            setOf(
                ProductionAdapter.CAPTURE_WORKFLOW,
                ProductionAdapter.BATCH_IMPORT,
                ProductionAdapter.MODEL_TASK_QUEUE,
                ProductionAdapter.MODEL_ASSET_DOCUMENTS,
            )
    }
}

enum class CaptureProductionCapabilityBlockReason {
    MANIFEST_UNAVAILABLE,
    REQUIRED_ADAPTERS_UNAVAILABLE,
    PRODUCTION_MANIFEST_INCOMPLETE,
    CAPABILITY_UNPUBLISHED,
}

sealed interface CaptureProductionCapabilityDecision {
    data class Available(
        val capability: CaptureProductionCapability,
    ) : CaptureProductionCapabilityDecision

    data class Blocked(
        val reason: CaptureProductionCapabilityBlockReason,
        val missingRequiredAdapters: Set<ProductionAdapter> = emptySet(),
    ) : CaptureProductionCapabilityDecision
}

/**
 * Production capture entry point. A blocked decision does not compose [CaptureScreen], so camera,
 * photo picker, draft, model, and commit side effects cannot start.
 */
@Composable
fun ProductionCaptureRoute(
    capabilityProvider: CaptureProductionCapabilityProvider,
    entryOrigin: CaptureEntryOrigin,
    onOpenModelSettings: () -> Unit,
    onTutorSessionReady: (
        sessionId: String,
        autoStartAuthorization: TutorAutoStartAuthorization?,
    ) -> Unit,
    onLibraryEntryReady: (String) -> Unit,
    onSplitReady: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    resumeDraftId: String? = null,
    initialAction: CaptureInitialAction = CaptureInitialAction.NONE,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }
    when (decision) {
        is CaptureProductionCapabilityDecision.Available ->
            CaptureScreen(
                entryOrigin = entryOrigin,
                repository = decision.capability.captureWorkflow,
                modelTasks = decision.capability.modelTasks,
                onOpenModelSettings = onOpenModelSettings,
                onTutorSessionReady = onTutorSessionReady,
                onLibraryEntryReady = onLibraryEntryReady,
                onSplitReady = onSplitReady,
                onBack = onBack,
                modifier = modifier,
                resumeDraftId = resumeDraftId,
                initialAction = initialAction,
            )
        is CaptureProductionCapabilityDecision.Blocked ->
            CaptureProductionUnavailable(
                onRetry = { retryRevision += 1 },
                onBack = onBack,
                modifier = modifier,
            )
    }
}

@Composable
private fun CaptureProductionUnavailable(
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    BackHandler(onBack = onBack)
    RootPageColumn(
        modifier = modifier.testTag("capture_production_unavailable"),
    ) {
        Text(
            text = "暂时无法使用",
            color = Ink,
        )
        Spacer(Modifier.height(20.dp))
        PrimaryActionButton(
            text = "重试",
            onClick = onRetry,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("capture_production_retry"),
        )
    }
}
