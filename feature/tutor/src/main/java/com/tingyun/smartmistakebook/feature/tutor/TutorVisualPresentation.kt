package com.tingyun.smartmistakebook.feature.tutor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualPresentationIdentity
import com.tingyun.smartmistakebook.core.model.TutorVisualPresentationStateKey
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer

internal enum class TutorVisualPresentationMode {
    CURRENT_EXPANDED,
    HISTORY_COLLAPSED,
}

internal fun tutorVisualPresentationMode(isCurrent: Boolean): TutorVisualPresentationMode =
    if (isCurrent) {
        TutorVisualPresentationMode.CURRENT_EXPANDED
    } else {
        TutorVisualPresentationMode.HISTORY_COLLAPSED
    }

internal fun tutorPlanVisualPresentationMode(
    isCurrentTurn: Boolean,
): TutorVisualPresentationMode = tutorVisualPresentationMode(isCurrent = isCurrentTurn)

internal enum class TutorVisualFallbackAction {
    RETRY,
    ORIGINAL,
}

internal fun TutorVisualResolution.Fallback.primaryAction(
    originalAvailable: Boolean,
): TutorVisualFallbackAction? = when {
    canRetry -> TutorVisualFallbackAction.RETRY
    originalAvailable -> TutorVisualFallbackAction.ORIGINAL
    else -> null
}

internal data class VisualTargetAttempt(
    val mode: TutorExplanationMode,
    val pendingEvidenceRequestId: String?,
    val requestId: String,
    val visualReady: Boolean,
    val expectedTargetId: String,
    val hitTargetId: String,
    val sceneReported: Boolean,
)

internal fun canSubmitTutorVisualTarget(attempt: VisualTargetAttempt): Boolean =
    attempt.mode == TutorExplanationMode.GUIDED &&
        attempt.pendingEvidenceRequestId == attempt.requestId &&
        attempt.visualReady &&
        !attempt.sceneReported &&
        attempt.expectedTargetId.isNotBlank() &&
        attempt.hitTargetId.isNotBlank()

internal fun isTutorVisualTargetReady(
    state: TutorVisualResolution,
    inlineScene: TutorVisualScene?,
): Boolean =
    (inlineScene ?: (state as? TutorVisualResolution.Ready)?.scene) is TutorVisualDocumentScene

internal fun inlineTutorVisualResolution(
    scene: TutorVisualScene,
    ownerModelTaskRequestId: String,
): TutorVisualResolution.Ready {
    val sceneFingerprint = TutorVisualSceneFingerprint.of(scene)
    return TutorVisualResolution.Ready(
        scene = scene,
        cacheKey = "inline:$ownerModelTaskRequestId:$sceneFingerprint",
        sourceKind = TutorVisualSceneSourceKind.INLINE,
        sceneTaskRequestId = ownerModelTaskRequestId,
        sceneFingerprint = sceneFingerprint,
    )
}

internal fun TutorVisualResolution.Ready.hitPresentation(
    ownerModelTaskRequestId: String?,
): TutorVisualPresentationIdentity? {
    if (scene !is TutorVisualDocumentScene) return null
    return presentationIdentity(ownerModelTaskRequestId)
}

internal fun TutorVisualResolution.Ready.presentationStateKey(
    ownerModelTaskRequestId: String?,
): String = TutorVisualPresentationStateKey.of(
    scene = scene,
    presentation = presentationIdentity(ownerModelTaskRequestId),
)

private fun TutorVisualResolution.Ready.presentationIdentity(
    ownerModelTaskRequestId: String?,
): TutorVisualPresentationIdentity? {
    val ownerRequestId = ownerModelTaskRequestId?.takeIf(String::isNotBlank) ?: return null
    val kind = sourceKind ?: return null
    val sourceRequestId = sceneTaskRequestId ?: return null
    if (sceneFingerprint != TutorVisualSceneFingerprint.of(scene)) return null
    return TutorVisualPresentationIdentity(
        ownerModelTaskRequestId = ownerRequestId,
        sourceKind = kind,
        sceneTaskRequestId = sourceRequestId,
        sceneId = scene.sceneId,
        sceneFingerprint = sceneFingerprint,
    )
}

internal fun canSubmitTutorVisualTarget(
    mode: TutorExplanationMode,
    pendingEvidenceRequestId: String?,
    requestId: String,
    visualReady: Boolean,
    expectedTargetId: String,
    hitTargetId: String,
    sceneReported: Boolean,
): Boolean = canSubmitTutorVisualTarget(
    VisualTargetAttempt(
        mode = mode,
        pendingEvidenceRequestId = pendingEvidenceRequestId,
        requestId = requestId,
        visualReady = visualReady,
        expectedTargetId = expectedTargetId,
        hitTargetId = hitTargetId,
        sceneReported = sceneReported,
    ),
)

@Composable
internal fun TutorVisualPresentation(
    state: TutorVisualResolution,
    mode: TutorVisualPresentationMode,
    originalAvailable: Boolean,
    onRetry: () -> Unit,
    onOpenOriginal: () -> Unit,
    onReportIncorrect: (String) -> Unit,
    ownerModelTaskRequestId: String? = null,
    onTargetHit: ((TutorVisualHitProof) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (state) {
        TutorVisualResolution.Hidden -> Unit
        TutorVisualResolution.Preparing -> TutorVisualPending(
            label = "正在准备图解",
            tag = "tutor_visual_preparing",
            modifier = modifier,
        )
        is TutorVisualResolution.Reviewing -> TutorVisualPending(
            label = "图解快好了",
            tag = "tutor_visual_reviewing",
            modifier = modifier,
        )
        is TutorVisualResolution.Fallback -> {
            val action = state.primaryAction(originalAvailable)
            TutorVisualFallback(
                action = action,
                onAction = when (action) {
                    TutorVisualFallbackAction.RETRY -> onRetry
                    TutorVisualFallbackAction.ORIGINAL -> onOpenOriginal
                    null -> null
                },
                modifier = modifier,
            )
        }
        is TutorVisualResolution.Ready -> {
            val hitPresentation = state.hitPresentation(ownerModelTaskRequestId)
            val presentationStateKey = state.presentationStateKey(ownerModelTaskRequestId)
            var historyExpanded by rememberSaveable(presentationStateKey) {
                mutableStateOf(false)
            }
            if (
                mode == TutorVisualPresentationMode.CURRENT_EXPANDED ||
                historyExpanded
            ) {
                TutorVisualSceneRenderer(
                    scene = state.scene,
                    onOpenOriginal = onOpenOriginal.takeIf { originalAvailable },
                    onReportIncorrect = { onReportIncorrect(state.scene.sceneId) },
                    hitPresentation = hitPresentation,
                    onTargetHit = onTargetHit,
                    presentationStateKey = presentationStateKey,
                    modifier = modifier.testTag("tutor_visual_ready"),
                )
            } else {
                OutlineActionChip(
                    text = "查看图解",
                    onClick = { historyExpanded = true },
                    modifier = modifier
                        .fillMaxWidth()
                        .testTag("tutor_visual_history_collapsed"),
                )
            }
        }
    }
}

@Composable
private fun TutorVisualPending(
    label: String,
    tag: String,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(tag),
        color = JadeSoft.copy(alpha = 0.24f),
        border = BorderStroke(1.dp, Outline),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, color = InkSecondary, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Ink,
                trackColor = JadeSoft,
            )
        }
    }
}

@Composable
private fun TutorVisualFallback(
    action: TutorVisualFallbackAction?,
    onAction: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tutor_visual_fallback"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "这次先看文字或原图",
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (action != null && onAction != null) {
            OutlineActionChip(
                text = when (action) {
                    TutorVisualFallbackAction.RETRY -> "重试图解"
                    TutorVisualFallbackAction.ORIGINAL -> "查看原图"
                },
                onClick = onAction,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tutor_visual_fallback_action"),
            )
        }
    }
}
