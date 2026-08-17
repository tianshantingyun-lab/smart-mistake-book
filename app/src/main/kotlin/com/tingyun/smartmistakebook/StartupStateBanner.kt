package com.tingyun.smartmistakebook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.JadeSoft

@Composable
internal fun StartupStateBanner(
    state: StartupState,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (state) {
        StartupState.Initializing,
        StartupState.Ready,
        -> Unit

        is StartupState.RecoverableFailure -> StartupFailureCard(
            title = state.title,
            message = state.message,
            diagnosticId = state.diagnosticId,
            recoverable = true,
            onRetry = onRetry,
            modifier = modifier,
        )

        is StartupState.FatalFailure -> StartupFailureCard(
            title = state.title,
            message = state.message,
            diagnosticId = state.diagnosticId,
            recoverable = false,
            onRetry = null,
            modifier = modifier,
        )
    }
}

@Composable
private fun StartupFailureCard(
    title: String,
    message: String,
    diagnosticId: String,
    recoverable: Boolean,
    onRetry: (() -> Unit)?,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (recoverable) ErrorWarm.copy(alpha = 0.1f) else ErrorWarm)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("startup_state_banner"),
    ) {
        Text(
            text = title,
            color = Ink,
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = message,
            color = Ink,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "诊断编号 $diagnosticId",
                color = Ink.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
            if (recoverable && onRetry != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("startup_retry_button"),
                ) {
                    Text("重试")
                }
            }
        }
    }
}
