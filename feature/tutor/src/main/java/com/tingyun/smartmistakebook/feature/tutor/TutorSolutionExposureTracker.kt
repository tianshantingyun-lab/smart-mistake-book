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

internal class TutorSolutionExposureTracker internal constructor(
    private val viewportBounds: MutableState<Rect?>,
    private val solutionBottomBounds: SnapshotStateMap<String, Rect>,
    private val answerExposureKeysState: MutableState<Set<TutorAnswerExposureKey>>,
    private val targets: List<TutorSolutionExposureTarget>,
) {
    val answerExposureKeys: Set<TutorAnswerExposureKey>
        get() = answerExposureKeysState.value

    val blockAutoFollowToken: RecordTutorSolutionExposureCommand?
        get() = targets.lastOrNull()?.exposureCommand

    fun updateViewportBounds(bounds: Rect) {
        viewportBounds.value = bounds
    }

    fun updateSolutionBottomBounds(stableId: String, bounds: Rect) {
        solutionBottomBounds[stableId] = bounds
    }

    fun markTransientAnswerExposure(key: TutorAnswerExposureKey): Boolean {
        val current = answerExposureKeysState.value
        if (key in current) return false
        answerExposureKeysState.value = current + key
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
    val answerExposureKeysState = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
        interactions,
    ) { mutableStateOf(emptySet<TutorAnswerExposureKey>()) }
    val viewportBounds = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
    ) { mutableStateOf<Rect?>(null) }
    val solutionBottomBounds = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
    ) { mutableStateMapOf<String, Rect>() }
    val recordedCommands = remember(
        question.sessionId,
        documentId,
        question.revisionNumber,
    ) { mutableSetOf<RecordTutorSolutionExposureCommand>() }
    val candidateKeys = remember(timeline, responses, longTermWritesBlocked) {
        tutorSolutionExposureCandidateKeys(
            timeline = timeline,
            responses = responses,
            longTermWritesBlocked = longTermWritesBlocked,
        )
    }
    val targets = remember(timeline, responses, previewKeys, longTermWritesBlocked) {
        buildTutorSolutionExposureTargets(
            timeline = timeline,
            responses = responses,
            previewKeys = previewKeys,
            longTermWritesBlocked = longTermWritesBlocked,
        )
    }
    val currentClock by rememberUpdatedState(clock)

    LaunchedEffect(
        question.sessionId,
        documentId,
        question.revisionNumber,
        candidateKeys,
        interactions,
    ) {
        val persistedKeys = try {
            interactions.findRecordedAnswerExposures(candidateKeys)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptySet()
        }
        answerExposureKeysState.value = answerExposureKeysState.value + persistedKeys
    }

    LaunchedEffect(
        question.sessionId,
        documentId,
        question.revisionNumber,
        longTermWritesBlocked,
        targets,
        interactions,
    ) {
        if (longTermWritesBlocked) return@LaunchedEffect
        snapshotFlow {
            val viewport = viewportBounds.value
            targets.filter { target ->
                val bottom = solutionBottomBounds[target.stableId]
                viewport != null && bottom != null &&
                    bottom.bottom > bottom.top &&
                    bottom.top >= viewport.top &&
                    bottom.bottom <= viewport.bottom
            }
        }.collect { visibleTargets ->
            visibleTargets.forEach { target ->
                val command = target.exposureCommand
                if (!recordedCommands.add(command)) return@forEach
                launch {
                    var recorded = false
                    var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
                    try {
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
                                answerExposureKeysState.value =
                                    answerExposureKeysState.value +
                                    command.toTutorAnswerExposureKey()
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
                        if (!recorded) recordedCommands.remove(command)
                    }
                }
            }
        }
    }

    return TutorSolutionExposureTracker(
        viewportBounds = viewportBounds,
        solutionBottomBounds = solutionBottomBounds,
        answerExposureKeysState = answerExposureKeysState,
        targets = targets,
    )
}

private const val INITIAL_RETRY_DELAY_MILLIS = 250L
private const val MAX_RETRY_DELAY_MILLIS = 5_000L
