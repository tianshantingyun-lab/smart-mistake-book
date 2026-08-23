package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.AppFailure

/**
 * Shared page-level state contract (acceptance audit §14.3).
 *
 * Every major route must cover loading / empty / error / offline states.
 * [PageStateFrame] renders these four states with a consistent layout,
 * stable test tags and accessibility announcements, so routes no longer
 * need ad-hoc empty/error panels.
 *
 * Content rendering is the caller's responsibility: pass `state = null`
 * (the default branch inside [PageStateFrame]) to show real content.
 */
sealed interface PageState {
    /** Data is still loading; no user action is offered. */
    data object Loading : PageState

    /** No data yet, with an optional call-to-action that guides the next step. */
    data class Empty(
        val title: String,
        val supportingText: String? = null,
        val actionLabel: String? = null,
        val onAction: (() -> Unit)? = null,
    ) : PageState

    /** The page failed; retry is offered only when retrying is safe. */
    data class Error(
        val title: String,
        val supportingText: String? = null,
        val retryLabel: String = "重试",
        val onRetry: (() -> Unit)? = null,
    ) : PageState

    /** The device is offline; local data stays available. */
    data class Offline(
        val title: String = "当前连接不上网络",
        val supportingText: String? = "这个功能需要联网。本机已有数据不受影响。",
        val retryLabel: String = "重试",
        val onRetry: (() -> Unit)? = null,
    ) : PageState
}

/**
 * Maps the unified failure model onto an [PageState.Error], keeping retry
 * available only when the failure is retryable (audit §15.1).
 */
fun pageStateForFailure(
    failure: AppFailure,
    onRetry: (() -> Unit)? = null,
): PageState.Error {
    val retryAction = failure.primaryAction?.takeIf { failure.retryable }
    return PageState.Error(
        title = failure.title,
        supportingText = failure.safeMessage,
        retryLabel = retryAction?.label ?: "重试",
        onRetry = if (failure.retryable) onRetry else null,
    )
}

/**
 * Renders [content] when [state] is null, otherwise one of the four shared
 * page states.
 *
 * The state panel is announced by TalkBack whenever the state changes
 * (`liveRegion = Polite`), and every state exposes a stable `page_state_*`
 * test tag for instrumented coverage.
 */
@Composable
fun PageStateFrame(
    state: PageState?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (state == null) {
        content()
        return
    }
    val (tag, title, supportingText, actionLabel, onAction, icon) = when (state) {
        PageState.Loading -> PageStateVisual(
            tag = "page_state_loading",
            title = "正在加载…",
            supportingText = null,
            actionLabel = null,
            onAction = null,
            icon = null,
        )
        is PageState.Empty -> PageStateVisual(
            tag = "page_state_empty",
            title = state.title,
            supportingText = state.supportingText,
            actionLabel = state.actionLabel,
            onAction = state.onAction,
            icon = Icons.Outlined.Inbox,
        )
        is PageState.Error -> PageStateVisual(
            tag = "page_state_error",
            title = state.title,
            supportingText = state.supportingText,
            actionLabel = state.onRetry?.let { state.retryLabel },
            onAction = state.onRetry,
            icon = Icons.Outlined.ErrorOutline,
        )
        is PageState.Offline -> PageStateVisual(
            tag = "page_state_offline",
            title = state.title,
            supportingText = state.supportingText,
            actionLabel = state.onRetry?.let { state.retryLabel },
            onAction = state.onRetry,
            icon = Icons.Outlined.CloudOff,
        )
    }
    val announcement = buildString {
        append(title)
        if (supportingText != null) {
            append("。")
            append(supportingText)
        }
        if (actionLabel != null) {
            append("。可用操作：")
            append(actionLabel)
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 160.dp)
            .padding(vertical = 24.dp)
            .semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = announcement
            }
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon == null) {
            CircularProgressIndicator(
                modifier = Modifier.size(42.dp),
                color = JadeActive,
                trackColor = Track,
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(42.dp),
                tint = if (state is PageState.Error) ErrorWarm else InkMuted,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            color = Ink,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (supportingText != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = supportingText,
                color = InkSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = onAction,
                modifier = Modifier
                    .defaultMinSize(minHeight = SmartDimens.MinimumTouchTarget)
                    .testTag("page_state_action"),
            ) {
                Text(actionLabel)
            }
        }
    }
}

/** Internal render model shared by the four states. */
private data class PageStateVisual(
    val tag: String,
    val title: String,
    val supportingText: String?,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val icon: ImageVector?,
)
