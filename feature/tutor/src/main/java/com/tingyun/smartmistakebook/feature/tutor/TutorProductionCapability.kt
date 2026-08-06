package com.tingyun.smartmistakebook.feature.tutor
import com.tingyun.smartmistakebook.core.ui.Ink

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.domain.AdaptiveDecision
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealRequest
import com.tingyun.smartmistakebook.core.domain.StudyAnswerRevealResult
import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmissionResult
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.model.AppCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.TutorAutoStartAuthorization
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.VerifiedTeachingArtifact
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.SmartColors

/**
 * Route-facing tutor capabilities published after the terminal production cutover.
 *
 * [tutorSessions] resolves only the current captured-question session. The remaining ports are
 * learner-facing, bounded repositories. This container carries no database, SQL, raw learner
 * identity, mastery store, teaching-knowledge store, or cross-conversation history handle.
 */
class TutorProductionCapability(
    internal val tutorSessions: CaptureWorkflowRepository,
    internal val currentSessionHost: TutorCurrentSessionHostPort,
    internal val conversationLobby: TutorConversationLobbyPort,
    internal val masteryContext: TutorMasteryContextRepository,
    internal val teachingReferences: TutorTeachingReferenceRepository,
    internal val modelTasks: ScopedModelTaskPort,
)

/**
 * Publication-owned source. Null means the terminal production graph is not published.
 */
fun interface TutorProductionCapabilitySource {
    fun currentCapability(): TutorProductionCapability?
}

/**
 * Fail-closed tutor publication boundary.
 *
 * A missing requirement, exceptional manifest, incomplete global inventory, or unpublished
 * capability is terminal for this route attempt. The provider never constructs a legacy fallback.
 */
class TutorProductionCapabilityProvider(
    private val adapterAvailability: ProductionAdapterAvailabilityPort,
    private val capabilitySource: TutorProductionCapabilitySource,
) {
    fun resolve(): TutorProductionCapabilityDecision {
        val manifest =
            try {
                adapterAvailability.readManifest()
            } catch (_: Exception) {
                return TutorProductionCapabilityDecision.Blocked(
                    TutorProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
                )
            }
        val missingRequiredAdapters =
            REQUIRED_TUTOR_ADAPTERS - manifest.availableAdapters
        if (missingRequiredAdapters.isNotEmpty()) {
            return TutorProductionCapabilityDecision.Blocked(
                reason =
                    TutorProductionCapabilityBlockReason
                        .REQUIRED_ADAPTERS_UNAVAILABLE,
                missingRequiredAdapters = missingRequiredAdapters,
            )
        }
        if (!manifest.isComplete) {
            return TutorProductionCapabilityDecision.Blocked(
                TutorProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            )
        }
        val capability =
            try {
                capabilitySource.currentCapability()
            } catch (_: Exception) {
                null
            }
                ?: return TutorProductionCapabilityDecision.Blocked(
                    TutorProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
                )
        return TutorProductionCapabilityDecision.Available(capability)
    }

    companion object {
        val REQUIRED_TUTOR_ADAPTERS: Set<ProductionAdapter> =
            setOf(
                ProductionAdapter.TUTOR_SESSION,
                ProductionAdapter.TUTOR_LEARNING_MEMORY,
                ProductionAdapter.TUTOR_MASTERY_CONTEXT,
                ProductionAdapter.TUTOR_TEACHING_REFERENCE,
                ProductionAdapter.MODEL_TASK_QUEUE,
                ProductionAdapter.MODEL_ASSET_DOCUMENTS,
            )
    }
}

enum class TutorProductionCapabilityBlockReason {
    MANIFEST_UNAVAILABLE,
    REQUIRED_ADAPTERS_UNAVAILABLE,
    PRODUCTION_MANIFEST_INCOMPLETE,
    CAPABILITY_UNPUBLISHED,
}

sealed interface TutorProductionCapabilityDecision {
    data class Available(
        val capability: TutorProductionCapability,
    ) : TutorProductionCapabilityDecision

    data class Blocked(
        val reason: TutorProductionCapabilityBlockReason,
        val missingRequiredAdapters: Set<ProductionAdapter> = emptySet(),
    ) : TutorProductionCapabilityDecision
}

/**
 * Production captured-session entry.
 *
 * A blocked decision does not compose [CapturedTutorSessionRoute], so it cannot read chat state,
 * send a model request, or persist learning evidence. The keyed subtree cancels the old
 * composition whenever the published capability or session changes; late results therefore have
 * no presentation owner and cannot appear in the new session.
 */
@Composable
fun ProductionCapturedTutorSessionRoute(
    capabilityProvider: TutorProductionCapabilityProvider,
    sessionId: String,
    autoStartAuthorization: TutorAutoStartAuthorization? = null,
    onAutoStartAuthorizationConsumed: (String) -> Unit = {},
    profile: StudyProfileOverview? = null,
    catalogEntries: List<StudyCatalogEntry> = emptyList(),
    onOpenModelSettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit = {},
    onOpenProfile: () -> Unit = {},
    onCameraAttachment: () -> Unit = {},
    onGalleryAttachment: () -> Unit = {},
    onLibraryAttachment: () -> Unit = onOpenMistakeNotebook,
    onBack: () -> Unit,
    onEndedWithoutSave: () -> Unit = onBack,
    explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    explanationModeVersion: Long = 0L,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    learningWritesAllowed: Boolean = true,
    questionKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    fallbackKnowledgeNodes: List<KnowledgeNodeRef> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }
    when (decision) {
        is TutorProductionCapabilityDecision.Available ->
            key(decision.capability, sessionId) {
                CapturedTutorSessionRoute(
                    sessionId = sessionId,
                    autoStartAuthorization = autoStartAuthorization,
                    onAutoStartAuthorizationConsumed = onAutoStartAuthorizationConsumed,
                    repository = decision.capability.tutorSessions,
                    modelTasks = decision.capability.modelTasks,
                    sessionHost = decision.capability.currentSessionHost,
                    profile = profile,
                    catalogEntries = catalogEntries,
                    onOpenModelSettings = onOpenModelSettings,
                    onOpenMistakeNotebook = onOpenMistakeNotebook,
                    onOpenProfile = onOpenProfile,
                    onCameraAttachment = onCameraAttachment,
                    onGalleryAttachment = onGalleryAttachment,
                    onLibraryAttachment = onLibraryAttachment,
                    onBack = onBack,
                    onEndedWithoutSave = onEndedWithoutSave,
                    explanationMode = explanationMode,
                    explanationModeVersion = explanationModeVersion,
                    onExplanationModeChange = onExplanationModeChange,
                    learningWritesAllowed = learningWritesAllowed,
                    masteryContextRepository = decision.capability.masteryContext,
                    questionKnowledgeNodes = questionKnowledgeNodes,
                    fallbackKnowledgeNodes = fallbackKnowledgeNodes,
                    modifier = modifier,
                )
            }
        is TutorProductionCapabilityDecision.Blocked ->
            TutorProductionUnavailable(
                onRetry = { retryRevision += 1 },
                onBack = onBack,
                modifier = modifier,
            )
    }
}

/**
 * Production lobby/verified-artifact entry. Repository construction remains outside the feature,
 * while this wrapper ensures no chat controller or model task is created before publication.
 */
@Composable
fun ProductionTutorRoute(
    capabilityProvider: TutorProductionCapabilityProvider,
    isSaved: Boolean,
    onSave: suspend () -> Unit,
    showSaveAction: Boolean = true,
    practiceUnitId: String,
    teachingArtifact: VerifiedTeachingArtifact?,
    adaptiveDecision: AdaptiveDecision?,
    profile: StudyProfileOverview,
    onSubmitChoice: suspend (StudyChoiceSubmission) -> StudyChoiceSubmissionResult,
    onCancelChoiceSubmission: (String) -> Unit = {},
    onRevealAnswer: suspend (StudyAnswerRevealRequest) -> StudyAnswerRevealResult,
    onCapture: () -> Unit,
    onGallery: () -> Unit = onCapture,
    onChooseExisting: () -> Unit,
    onOpenCapabilitySettings: () -> Unit,
    onOpenMistakeNotebook: () -> Unit,
    onOpenProfile: () -> Unit,
    catalogEntries: List<StudyCatalogEntry>,
    capabilities: AppCapabilitySnapshot,
    explanationMode: TutorExplanationMode = TutorExplanationMode.DIRECT,
    onExplanationModeChange: (TutorExplanationMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }
    when (decision) {
        is TutorProductionCapabilityDecision.Available ->
            key(decision.capability, practiceUnitId) {
                TutorRoute(
                    isSaved = isSaved,
                    onSave = onSave,
                    showSaveAction = showSaveAction,
                    practiceUnitId = practiceUnitId,
                    teachingArtifact = teachingArtifact,
                    adaptiveDecision = adaptiveDecision,
                    profile = profile,
                    onSubmitChoice = onSubmitChoice,
                    onCancelChoiceSubmission = onCancelChoiceSubmission,
                    onRevealAnswer = onRevealAnswer,
                    onCapture = onCapture,
                    onGallery = onGallery,
                    onChooseExisting = onChooseExisting,
                    onOpenCapabilitySettings = onOpenCapabilitySettings,
                    onOpenMistakeNotebook = onOpenMistakeNotebook,
                    onOpenProfile = onOpenProfile,
                    modelTasks = decision.capability.modelTasks,
                    conversationLobby = decision.capability.conversationLobby,
                    catalogEntries = catalogEntries,
                    capabilities = capabilities,
                    explanationMode = explanationMode,
                    onExplanationModeChange = onExplanationModeChange,
                    modifier = modifier,
                )
            }
        is TutorProductionCapabilityDecision.Blocked ->
            TutorProductionUnavailable(
                onRetry = { retryRevision += 1 },
                modifier = modifier,
            )
    }
}

@Composable
private fun TutorProductionUnavailable(
    onRetry: () -> Unit,
    modifier: Modifier,
    onBack: (() -> Unit)? = null,
) {
    RootPageColumn(
        modifier = modifier.testTag("tutor_production_unavailable"),
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
                    .testTag("tutor_production_retry"),
        )
        if (onBack != null) {
            TextButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("tutor_production_back"),
            ) {
                Text("返回")
            }
        }
    }
}
