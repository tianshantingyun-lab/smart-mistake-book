package com.tingyun.smartmistakebook.feature.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapter
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterAvailabilityPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAssistanceActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewProductionCapability
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewRawAnswerSubmission
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeProblemPreview
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeSession
import com.tingyun.smartmistakebook.core.data.review.ReviewHomeState

/**
 * Opaque feature capability assembled only from the published production review adapter and
 * narrow review actions.
 *
 * Requiring [DailyReviewProductionCapability] prevents this feature from replacing the
 * three-authority saved-question repository with a legacy or feature-owned implementation.
 */
class ReviewProductionCapability(
    planning: DailyReviewProductionCapability,
    sessionActions: DailyReviewSessionActionPort,
    pacingActions: DailyReviewPacingActionPort,
    answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
    assistanceActions: DailyReviewAssistanceActionPort,
    pacingCommandFactory: DailyReviewPacingCommandFactory,
    requestProvider: ReviewHomeRequestProvider,
) {
    internal val repository = planning.repository
    internal val sessionActions = sessionActions
    internal val pacingActions = pacingActions
    internal val answerSubmissionPorts = answerSubmissionPorts
    internal val assistanceActions = assistanceActions
    internal val pacingCommandFactory = pacingCommandFactory
    internal val requestProvider = requestProvider

    init {
        require(
            planning.availableProductionAdapters ==
                setOf(ProductionAdapter.REVIEW_PLANNING),
        ) {
            "Review planning capability has an invalid production-adapter claim"
        }
    }
}

/**
 * Publication-owned source. It returns null until the terminal production bootstrap has
 * successfully published every dependency.
 */
fun interface ReviewProductionCapabilitySource {
    fun currentCapability(): ReviewProductionCapability?
}

/**
 * Fail-closed feature boundary for production review.
 *
 * The source is not inspected until the global adapter manifest is complete. A missing manifest,
 * missing capability, or publication failure cannot fall back to the legacy review route.
 */
class ReviewProductionCapabilityProvider(
    private val adapterAvailability: ProductionAdapterAvailabilityPort,
    private val capabilitySource: ReviewProductionCapabilitySource,
) {
    internal fun resolve(): ReviewProductionCapabilityDecision {
        val manifest =
            try {
                adapterAvailability.readManifest()
            } catch (_: Exception) {
                return ReviewProductionCapabilityDecision.Blocked(
                    ReviewProductionCapabilityBlockReason.MANIFEST_UNAVAILABLE,
                )
            }
        if (ProductionAdapter.REVIEW_PLANNING !in manifest.availableAdapters) {
            return ReviewProductionCapabilityDecision.Blocked(
                ReviewProductionCapabilityBlockReason.REVIEW_ADAPTER_UNAVAILABLE,
            )
        }
        if (!manifest.isComplete) {
            return ReviewProductionCapabilityDecision.Blocked(
                ReviewProductionCapabilityBlockReason.PRODUCTION_MANIFEST_INCOMPLETE,
            )
        }
        val capability =
            try {
                capabilitySource.currentCapability()
            } catch (_: Exception) {
                null
            }
                ?: return ReviewProductionCapabilityDecision.Blocked(
                    ReviewProductionCapabilityBlockReason.CAPABILITY_UNPUBLISHED,
                )
        return ReviewProductionCapabilityDecision.Available(capability)
    }
}

internal enum class ReviewProductionCapabilityBlockReason {
    MANIFEST_UNAVAILABLE,
    REVIEW_ADAPTER_UNAVAILABLE,
    PRODUCTION_MANIFEST_INCOMPLETE,
    CAPABILITY_UNPUBLISHED,
}

internal sealed interface ReviewProductionCapabilityDecision {
    data class Available(
        val capability: ReviewProductionCapability,
    ) : ReviewProductionCapabilityDecision

    data class Blocked(
        val reason: ReviewProductionCapabilityBlockReason,
    ) : ReviewProductionCapabilityDecision
}

/**
 * The production landing entry point. It never accepts the broad legacy study repository.
 */
@Composable
fun ProductionDailyReviewRoute(
    capabilityProvider: ReviewProductionCapabilityProvider,
    onOpenSession: (ReviewHomeState.Ready) -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryRevision by remember(capabilityProvider) { mutableIntStateOf(0) }
    val decision =
        remember(capabilityProvider, retryRevision) {
            capabilityProvider.resolve()
        }
    when (decision) {
        is ReviewProductionCapabilityDecision.Available ->
            DailyReviewRoute(
                repository = decision.capability.repository,
                sessionActions = decision.capability.sessionActions,
                requestProvider = decision.capability.requestProvider,
                onOpenSession = onOpenSession,
                modifier = modifier,
            )
        is ReviewProductionCapabilityDecision.Blocked ->
            ReviewHomeScreen(
                state = ReviewLandingState.Unavailable,
                onPrimaryAction = {},
                onRetry = { retryRevision += 1 },
                modifier = modifier,
            )
    }
}

/**
 * The production session entry point. DONE/STUCK reach only the pacing port; verified learning
 * evidence can enter only through the separately typed verified-answer callback.
 */
@Composable
fun ProductionDailyReviewSessionRoute(
    capabilityProvider: ReviewProductionCapabilityProvider,
    home: ReviewHomeState.Ready,
    onBack: () -> Unit,
    onOpenExplanation: (ReviewHomeProblemPreview) -> Unit,
    onPacingRecorded: (ReviewHomeSession) -> Unit,
    onVerifiedAnswerRecorded: () -> Unit,
    modifier: Modifier = Modifier,
    answerContent:
        (@Composable ((DailyReviewRawAnswerSubmission) -> Unit) -> Unit)? = null,
) {
    val decision =
        remember(capabilityProvider) {
            capabilityProvider.resolve()
        }
    when (decision) {
        is ReviewProductionCapabilityDecision.Available ->
            DailyReviewSessionRoute(
                home = home,
                pacingActions = decision.capability.pacingActions,
                answerSubmissionPorts = decision.capability.answerSubmissionPorts,
                assistanceActions = decision.capability.assistanceActions,
                pacingCommandFactory = decision.capability.pacingCommandFactory,
                onBack = onBack,
                onOpenExplanation = onOpenExplanation,
                onPacingRecorded = onPacingRecorded,
                onVerifiedAnswerRecorded = onVerifiedAnswerRecorded,
                modifier = modifier,
                answerContent = answerContent,
            )
        is ReviewProductionCapabilityDecision.Blocked ->
            DailyReviewSessionScreen(
                home = home.copy(session = null),
                operation = DailyReviewSessionOperation.IDLE,
                onBack = onBack,
                onOpenExplanation = {},
                onDone = {},
                onStuck = {},
                answerContent = null,
                modifier = modifier,
            )
    }
}
