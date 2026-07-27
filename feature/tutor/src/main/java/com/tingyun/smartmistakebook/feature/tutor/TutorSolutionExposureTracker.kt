package com.tingyun.smartmistakebook.feature.tutor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.toTutorAnswerExposureKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class TutorSolutionBottomAnchor(
    val token: Any,
    val bounds: Rect,
)

internal class TutorSolutionExposureTracker internal constructor(
    private val viewportBounds: MutableState<Rect?>,
    private val solutionBottomAnchors: SnapshotStateMap<String, TutorSolutionBottomAnchor>,
    private val recordedAnswerExposureKeysState: MutableState<Set<TutorAnswerExposureKey>>,
    private val transientAnswerExposureKeysState: MutableState<Set<TutorAnswerExposureKey>>,
    private val targets: List<TutorSolutionExposureTarget>,
) {
    /** Durable, bottom-visible exposure authority for history, memory, and learning semantics. */
    val answerExposureKeys: Set<TutorAnswerExposureKey>
        get() = recordedAnswerExposureKeysState.value

    /** Process-only visibility used solely by the currently rendered preview. */
    val presentationAnswerExposureKeys: Set<TutorAnswerExposureKey>
        get() = recordedAnswerExposureKeysState.value + transientAnswerExposureKeysState.value

    val blockAutoFollowToken: RecordTutorSolutionExposureCommand?
        get() = targets.lastOrNull()?.exposureCommand

    fun updateViewportBounds(bounds: Rect) {
        viewportBounds.value = bounds
    }

    fun updateSolutionBottomBounds(stableId: String, token: Any, bounds: Rect) {
        solutionBottomAnchors[stableId] = TutorSolutionBottomAnchor(token, bounds)
    }

    fun removeSolutionBottomBounds(stableId: String, token: Any) {
        if (solutionBottomAnchors[stableId]?.token === token) {
            solutionBottomAnchors.remove(stableId)
        }
    }

    fun markTransientAnswerExposure(key: TutorAnswerExposureKey): Boolean {
        if (key in presentationAnswerExposureKeys) return false
        transientAnswerExposureKeysState.value = transientAnswerExposureKeysState.value + key
        return true
    }
}

@Composable
internal fun rememberTutorSolutionExposureTracker(
    question: TutorQuestionContext,
    timeline: List<TutorConversationTimelineItem>,
    responses: List<TutorTurnResponse>,
    previewKeys: Set<PlanSolutionPreviewKey>,
    longTermWritesBlocked: Boolean,
    interactions: TutorInteractionRepository,
    clock: () -> Long,
): TutorSolutionExposureTracker {
    val documentId = question.questionDocument.document.id
    val transientAnswerExposureKeysState = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
    ) { mutableStateOf(emptySet<TutorAnswerExposureKey>()) }
    val viewportBounds = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
    ) { mutableStateOf<Rect?>(null) }
    val solutionBottomAnchors = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
    ) { mutableStateMapOf<String, TutorSolutionBottomAnchor>() }
    val inFlightExposureKeys = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
        interactions,
    ) { mutableSetOf<TutorAnswerExposureKey>() }
    val candidateKeys = remember(timeline, responses, longTermWritesBlocked) {
        tutorSolutionExposureCandidateKeys(
            timeline = timeline,
            responses = responses,
            longTermWritesBlocked = longTermWritesBlocked,
        )
    }
    val recordedAnswerExposureKeysState = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
        interactions,
    ) { mutableStateOf(emptySet<TutorAnswerExposureKey>()) }
    val hydrationScope = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
        candidateKeys,
        interactions,
    ) { Any() }
    val hydratedScopeState = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
        interactions,
    ) { mutableStateOf<Any?>(null) }
    val targets = remember(timeline, responses, previewKeys, longTermWritesBlocked) {
        buildTutorSolutionExposureTargets(
            timeline = timeline,
            responses = responses,
            previewKeys = previewKeys,
            longTermWritesBlocked = longTermWritesBlocked,
        )
    }
    val currentClock by rememberUpdatedState(clock)
    val currentHydrationScope by rememberUpdatedState(hydrationScope)

    LaunchedEffect(
        question.sessionId,
        documentId,
        question.revisionNumber,
        candidateKeys,
        interactions,
        hydrationScope,
    ) {
        var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
        while (currentHydrationScope === hydrationScope) {
            try {
                val persistedKeys = interactions.findRecordedAnswerExposures(candidateKeys)
                if (currentHydrationScope !== hydrationScope) return@LaunchedEffect
                recordedAnswerExposureKeysState.value =
                    recordedAnswerExposureKeysState.value + persistedKeys
                hydratedScopeState.value = hydrationScope
                return@LaunchedEffect
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2)
                    .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
        }
    }

    LaunchedEffect(
        question.sessionId,
        documentId,
        question.revisionNumber,
        longTermWritesBlocked,
        targets,
        interactions,
        hydrationScope,
    ) {
        if (longTermWritesBlocked) return@LaunchedEffect
        snapshotFlow {
            if (hydratedScopeState.value !== hydrationScope) return@snapshotFlow emptyList()
            val viewport = viewportBounds.value
            targets.filter { target ->
                val bottom = solutionBottomAnchors[target.stableId]?.bounds
                isTutorSolutionBottomVisible(viewport, bottom)
            }
        }.collect { visibleTargets ->
            visibleTargets.forEach { target ->
                val command = target.exposureCommand
                val exposureKey = command.toTutorAnswerExposureKey()
                val completedKeys = recordedAnswerExposureKeysState.value
                val started = tryStartTutorSolutionExposure(
                    exposureKey = exposureKey,
                    completedKeys = completedKeys,
                    inFlightKeys = inFlightExposureKeys,
                )
                if (!started) {
                    return@forEach
                }
                launch {
                    var recorded = false
                    var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
                    try {
                        val stability = TutorSolutionExposureStability()
                        while (true) {
                            val frameNanos = withFrameNanos { it }
                            val currentViewport = viewportBounds.value
                            val currentAnchor = solutionBottomAnchors[target.stableId]
                            val stabilityStatus = stability.observe(
                                frameNanos = frameNanos,
                                viewport = currentViewport,
                                bottom = currentAnchor?.bounds,
                                anchorToken = currentAnchor?.token,
                            )
                            when (stabilityStatus) {
                                TutorSolutionExposureStabilityStatus.NOT_VISIBLE ->
                                    return@launch
                                TutorSolutionExposureStabilityStatus.STABLE -> break
                                TutorSolutionExposureStabilityStatus.WAITING -> Unit
                            }
                        }
                        if (exposureKey in recordedAnswerExposureKeysState.value) return@launch
                        while (!recorded) {
                            try {
                                val visibleAt = maxOf(
                                    currentClock(),
                                    target.notBeforeEpochMillis,
                                )
                                target.pendingRevealCommand?.let { revealCommand ->
                                    interactions.revealSolution(
                                        revealCommand.copy(occurredAtEpochMillis = visibleAt),
                                    )
                                }
                                interactions.recordSolutionExposure(
                                    command.copy(occurredAtEpochMillis = visibleAt),
                                )
                                recordedAnswerExposureKeysState.value =
                                    recordedAnswerExposureKeysState.value +
                                    exposureKey
                                recorded = true
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                delay(retryDelayMillis)
                                retryDelayMillis = (retryDelayMillis * 2)
                                    .coerceAtMost(MAX_RETRY_DELAY_MILLIS)
                            }
                        }
                    } finally {
                        inFlightExposureKeys.remove(exposureKey)
                    }
                }
            }
        }
    }

    return TutorSolutionExposureTracker(
        viewportBounds = viewportBounds,
        solutionBottomAnchors = solutionBottomAnchors,
        recordedAnswerExposureKeysState = recordedAnswerExposureKeysState,
        transientAnswerExposureKeysState = transientAnswerExposureKeysState,
        targets = targets,
    )
}

internal class TutorSolutionExposureStability(
    private val stabilityWindowNanos: Long = EXPOSURE_STABILITY_WINDOW_NANOS,
) {
    private var previousAnchorToken: Any? = null
    private var previousFrameNanos: Long? = null
    private var stableSinceFrameNanos: Long? = null

    init {
        require(stabilityWindowNanos > 0)
    }

    fun observe(
        frameNanos: Long,
        viewport: Rect?,
        bottom: Rect?,
        anchorToken: Any? = null,
    ): TutorSolutionExposureStabilityStatus {
        if (!isTutorSolutionBottomVisible(viewport, bottom)) {
            previousAnchorToken = null
            previousFrameNanos = null
            stableSinceFrameNanos = null
            return TutorSolutionExposureStabilityStatus.NOT_VISIBLE
        }
        val previousFrame = previousFrameNanos
        val frameGapBrokeContinuity =
            previousFrame == null || frameNanos - previousFrame >= stabilityWindowNanos
        val anchorChanged = anchorToken !== previousAnchorToken
        if (frameGapBrokeContinuity || anchorChanged) {
            stableSinceFrameNanos = frameNanos
        }
        previousAnchorToken = anchorToken
        previousFrameNanos = frameNanos
        val stableSince = requireNotNull(stableSinceFrameNanos)
        return if (frameNanos - stableSince >= stabilityWindowNanos) {
            TutorSolutionExposureStabilityStatus.STABLE
        } else {
            TutorSolutionExposureStabilityStatus.WAITING
        }
    }
}

internal enum class TutorSolutionExposureStabilityStatus {
    NOT_VISIBLE,
    WAITING,
    STABLE,
}

internal fun tryStartTutorSolutionExposure(
    exposureKey: TutorAnswerExposureKey,
    completedKeys: Set<TutorAnswerExposureKey>,
    inFlightKeys: MutableSet<TutorAnswerExposureKey>,
): Boolean = exposureKey !in completedKeys && inFlightKeys.add(exposureKey)

private fun isTutorSolutionBottomVisible(viewport: Rect?, bottom: Rect?): Boolean =
    viewport != null && bottom != null &&
        bottom.bottom > bottom.top &&
        bottom.top >= viewport.top &&
        bottom.bottom <= viewport.bottom

private const val EXPOSURE_STABILITY_WINDOW_NANOS = 100_000_000L
private const val INITIAL_RETRY_DELAY_MILLIS = 250L
private const val MAX_RETRY_DELAY_MILLIS = 5_000L
