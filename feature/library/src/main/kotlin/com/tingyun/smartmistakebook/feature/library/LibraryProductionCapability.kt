package com.tingyun.smartmistakebook.feature.library
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogState
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartColors

/**
 * Route-facing mistake-library capabilities published after the terminal production cutover.
 *
 * This container intentionally exposes only existing learner-facing repositories. It carries no
 * database, DAO, SQL, mastery writer, teaching-knowledge store, or authority runtime.
 */
class LibraryProductionCapability(
    internal val catalog: StudentMistakeLibraryCatalogRepository,
    internal val mistakeDetails: MistakeDetailRepository,
    internal val organization: MistakeOrganizationRepository,
    internal val batchImports: BatchImportRepository,
    internal val modelTasks: ScopedModelTaskPort,
)

/**
 * Publication-owned source. Null means that the complete production graph has not been published.
 */
fun interface LibraryProductionCapabilitySource {
    fun currentCapability(): LibraryProductionCapability?
}

/**
 * Fail-closed boundary shared by every production mistake-library entry point.
 *
 * The capability source is never touched until every route dependency is present and the global
 * manifest is complete. Missing, exceptional, incomplete, or unpublished state cannot fall back
 * to the legacy library graph.
 */
class LibraryProductionCapabilityProvider(
    private val adapterAvailability: ProductionAdapterAvailabilityPort,
    private val capabilitySource: LibraryProductionCapabilitySource,
) {
    fun resolve(): LibraryProductionCapabilityDecision {
        val manifest =
            try {
                adapterAvailability.readManifest()
            } catch (_: Exception) {
                return LibraryProductionCapabilityDecision.Blocked(
                    LibraryProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
                )
            }
        val missingRequiredAdapters =
            REQUIRED_LIBRARY_ADAPTERS - manifest.availableAdapters
        if (missingRequiredAdapters.isNotEmpty()) {
            return LibraryProductionCapabilityDecision.Blocked(
                reason =
                    LibraryProductionCapabilityBlockReason
                        .REQUIRED_ADAPTERS_UNAVAILABLE,
                missingRequiredAdapters = missingRequiredAdapters,
            )
        }
        if (!manifest.isComplete) {
            return LibraryProductionCapabilityDecision.Blocked(
                LibraryProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            )
        }
        val capability =
            try {
                capabilitySource.currentCapability()
            } catch (_: Exception) {
                null
            }
                ?: return LibraryProductionCapabilityDecision.Blocked(
                    LibraryProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
                )
        return LibraryProductionCapabilityDecision.Available(capability)
    }

    companion object {
        val REQUIRED_LIBRARY_ADAPTERS: Set<ProductionAdapter> =
            setOf(
                ProductionAdapter.MISTAKE_DETAIL,
                ProductionAdapter.MISTAKE_ORGANIZATION,
                ProductionAdapter.STUDENT_MISTAKE_CATALOG,
                ProductionAdapter.BATCH_IMPORT,
                ProductionAdapter.MODEL_TASK_QUEUE,
                ProductionAdapter.MODEL_ASSET_DOCUMENTS,
            )
    }
}

enum class LibraryProductionCapabilityBlockReason {
    MANIFEST_UNAVAILABLE,
    REQUIRED_ADAPTERS_UNAVAILABLE,
    PRODUCTION_MANIFEST_INCOMPLETE,
    CAPABILITY_UNPUBLISHED,
}

sealed interface LibraryProductionCapabilityDecision {
    data class Available(
        val capability: LibraryProductionCapability,
    ) : LibraryProductionCapabilityDecision

    data class Blocked(
        val reason: LibraryProductionCapabilityBlockReason,
        val missingRequiredAdapters: Set<ProductionAdapter> = emptySet(),
    ) : LibraryProductionCapabilityDecision
}

/**
 * Production catalog entry. Only a verified student-mistake projection is ever composed.
 */
@Composable
fun ProductionLibraryRoute(
    capabilityProvider: LibraryProductionCapabilityProvider,
    pendingCaptureCount: Int,
    onCapture: () -> Unit,
    onBatchImport: () -> Unit,
    onOpenPendingCaptures: () -> Unit,
    onExportVisible: (List<String>) -> Unit,
    onOpenItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }
    val capability =
        (decision as? LibraryProductionCapabilityDecision.Available)?.capability
    val catalogFlow =
        remember(capability) {
            capability?.let { published ->
                try {
                    published.catalog.state
                } catch (_: Exception) {
                    null
                }
            }
        }
    if (catalogFlow == null) {
        LibraryProductionUnavailable(
            onRetry = { retryRevision += 1 },
            modifier = modifier,
        )
        return
    }
    val catalogState by catalogFlow.collectAsStateWithLifecycle()
    val verified = catalogState as? StudentMistakeLibraryCatalogState.Verified
    if (verified == null) {
        LibraryProductionUnavailable(
            onRetry = { retryRevision += 1 },
            modifier = modifier,
        )
        return
    }
    LibraryRoute(
        entries = verified.entries,
        pendingCaptureCount = pendingCaptureCount,
        onCapture = onCapture,
        onBatchImport = onBatchImport,
        onOpenPendingCaptures = onOpenPendingCaptures,
        onExportVisible = onExportVisible,
        onOpenItem = onOpenItem,
        modifier = modifier,
        viewModelKey = PRODUCTION_LIBRARY_VIEW_MODEL_KEY,
    )
}

/**
 * Production current-detail entry. Historical revisions remain blocked until core:data publishes
 * a student-authoritative exact-revision read capability without a legacy fallback.
 */
@Composable
fun ProductionMistakeDetailRoute(
    capabilityProvider: LibraryProductionCapabilityProvider,
    errorBookEntryId: String,
    profile: StudyProfileOverview? = null,
    onBack: () -> Unit,
    onExport: (MistakeRevisionKey) -> Unit,
    onTutor: (MistakeRevisionKey) -> Unit,
    onOpenRelatedMistake: (String) -> Unit,
    onOpenModelSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }
    val capability =
        (decision as? LibraryProductionCapabilityDecision.Available)?.capability
    val catalogFlow =
        remember(capability) {
            capability?.let { published ->
                try {
                    published.catalog.state
                } catch (_: Exception) {
                    null
                }
            }
        }
    if (capability == null || catalogFlow == null) {
        LibraryProductionUnavailable(
            onRetry = { retryRevision += 1 },
            onBack = onBack,
            modifier = modifier,
        )
        return
    }
    val catalogState by catalogFlow.collectAsStateWithLifecycle()
    val verified = catalogState as? StudentMistakeLibraryCatalogState.Verified
    if (
        verified == null ||
        verified.entries.none { entry -> entry.entryId == errorBookEntryId }
    ) {
        LibraryProductionUnavailable(
            onRetry = { retryRevision += 1 },
            onBack = onBack,
            modifier = modifier,
        )
        return
    }
    MistakeDetailRoute(
        errorBookEntryId = errorBookEntryId,
        repository = capability.mistakeDetails,
        organizationRepository = capability.organization,
        modelTasks = capability.modelTasks,
        profile = profile,
        catalogEntries = verified.entries,
        onBack = onBack,
        onExport = onExport,
        onTutor = onTutor,
        onOpenRelatedMistake = onOpenRelatedMistake,
        onOpenModelSettings = onOpenModelSettings,
        modifier = modifier,
        historicalRevisionsEnabled = false,
    )
}

/**
 * Production batch-import entry. A blocked provider does not compose [BatchImportRoute], so no
 * picker, repository observation, import, retry, or organization action can start.
 */
@Composable
fun ProductionBatchImportRoute(
    capabilityProvider: LibraryProductionCapabilityProvider,
    onOpenDraft: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }
    when (decision) {
        is LibraryProductionCapabilityDecision.Available ->
            BatchImportRoute(
                repository = decision.capability.batchImports,
                onOpenDraft = onOpenDraft,
                onBack = onBack,
                modifier = modifier,
            )
        is LibraryProductionCapabilityDecision.Blocked ->
            LibraryProductionUnavailable(
                onRetry = { retryRevision += 1 },
                onBack = onBack,
                modifier = modifier,
            )
    }
}

@Composable
private fun LibraryProductionUnavailable(
    onRetry: () -> Unit,
    modifier: Modifier,
    onBack: (() -> Unit)? = null,
) {
    BackHandler(enabled = onBack != null) {
        onBack?.invoke()
    }
    RootPageColumn(
        modifier = modifier.testTag("library_production_unavailable"),
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
                    .testTag("library_production_retry"),
        )
    }
}

private const val PRODUCTION_LIBRARY_VIEW_MODEL_KEY = "production-library"
