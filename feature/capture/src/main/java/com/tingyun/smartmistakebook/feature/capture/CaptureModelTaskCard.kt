package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentIssueCode
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.requiresModelSettings
import com.tingyun.smartmistakebook.core.ui.ErrorWarm
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip

@Composable
internal fun CaptureModelTaskCard(
    snapshot: ModelTaskSnapshot?,
    onRetry: () -> Unit,
    onOpenModelSettings: () -> Unit,
    parseSnapshot: ModelTaskSnapshot? = null,
    onRetryParse: () -> Unit = {},
    onRetake: () -> Unit = {},
    onAddPage: () -> Unit = {},
    splitInProgress: Boolean = false,
    splitError: String? = null,
    onRetrySplit: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val activeSnapshot = parseSnapshot ?: snapshot
    val failed = activeSnapshot?.status == ModelTaskStatus.RETRYABLE_FAILURE ||
        activeSnapshot?.status == ModelTaskStatus.PERMANENT_FAILURE
    val cancelled = activeSnapshot?.status == ModelTaskStatus.CANCELLED
    val needsAttention = failed || cancelled || splitError != null
    val succeeded = activeSnapshot?.status == ModelTaskStatus.SUCCEEDED
    val accent = when {
        needsAttention -> ErrorWarm
        succeeded -> JadeActive
        else -> InkSecondary
    }
    val title = captureModelTaskTitle(snapshot, parseSnapshot)
    val icon = when {
        needsAttention -> Icons.Outlined.ErrorOutline
        succeeded -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.Schedule
    }
    val assessmentDecision = (snapshot?.output as? CaptureAssessmentOutput)?.assessment?.decision
    val multipleQuestionsDetected = (snapshot?.output as? CaptureAssessmentOutput)
        ?.assessment
        ?.issues
        ?.any { it.code == CaptureAssessmentIssueCode.MULTIPLE_QUESTIONS } == true
    val recoveryAction = captureModelRecoveryAction(snapshot, parseSnapshot)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag("capture_model_task_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(23.dp),
                tint = accent,
            )
            Text(
                text = title,
                modifier = Modifier.padding(start = 8.dp),
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            text = when {
                splitError != null -> splitError
                splitInProgress -> "正在把这一页里的题目分别整理，原图已经保留。"
                else -> modelPipelineDetail(snapshot, parseSnapshot)
            },
            modifier = Modifier.padding(top = 8.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (assessmentDecision == CaptureAssessmentDecision.NEED_MORE_IMAGE) {
            Text(
                text = "题目内容还没拍全。继续补拍下一页，已保存的页面不会丢失。",
                modifier = Modifier.padding(top = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (assessmentDecision == CaptureAssessmentDecision.NEED_MORE_IMAGE) {
            OutlineActionChip(
                text = "继续补拍本题",
                onClick = onAddPage,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("capture_model_add_page_button"),
            )
        } else if (assessmentDecision == CaptureAssessmentDecision.RECAPTURE) {
            if (multipleQuestionsDetected) {
                Text(
                    text = "画面里有多道题，请先只拍其中一道。",
                    modifier = Modifier.padding(top = 8.dp),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            OutlineActionChip(
                text = if (multipleQuestionsDetected) "只拍一道题" else "重新拍完整题目",
                onClick = onRetake,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("capture_model_retake_button"),
            )
        }
        if (assessmentDecision == CaptureAssessmentDecision.SPLIT && splitError != null) {
            OutlineActionChip(
                text = "重新整理",
                onClick = onRetrySplit,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("capture_split_retry_button"),
            )
        }
        when (recoveryAction) {
            CaptureModelRecoveryAction.RETRY -> OutlineActionChip(
                text = "重试",
                onClick = if (parseSnapshot != null) onRetryParse else onRetry,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("capture_model_retry_button"),
            )
            CaptureModelRecoveryAction.OPEN_SETTINGS -> OutlineActionChip(
                text = "检查模型设置",
                onClick = onOpenModelSettings,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .testTag("capture_model_settings_button"),
            )
            CaptureModelRecoveryAction.NONE -> Unit
        }
    }
}

internal enum class CaptureModelRecoveryAction {
    NONE,
    RETRY,
    OPEN_SETTINGS,
}

internal fun captureModelRecoveryAction(
    snapshot: ModelTaskSnapshot?,
    parseSnapshot: ModelTaskSnapshot?,
): CaptureModelRecoveryAction {
    val activeSnapshot = parseSnapshot ?: snapshot ?: return CaptureModelRecoveryAction.NONE
    val assessmentDecision = (snapshot?.output as? CaptureAssessmentOutput)?.assessment?.decision
    if (assessmentDecision in setOf(
            CaptureAssessmentDecision.RECAPTURE,
            CaptureAssessmentDecision.NEED_MORE_IMAGE,
            CaptureAssessmentDecision.SPLIT,
        )
    ) {
        return CaptureModelRecoveryAction.NONE
    }
    if (activeSnapshot.provider?.isDemo == true && activeSnapshot.status == ModelTaskStatus.SUCCEEDED) {
        return CaptureModelRecoveryAction.OPEN_SETTINGS
    }
    if (activeSnapshot.failure?.code?.requiresModelSettings() == true) {
        return CaptureModelRecoveryAction.OPEN_SETTINGS
    }
    return if (activeSnapshot.status in setOf(
            ModelTaskStatus.RETRYABLE_FAILURE,
            ModelTaskStatus.PERMANENT_FAILURE,
            ModelTaskStatus.CANCELLED,
        )
    ) {
        CaptureModelRecoveryAction.RETRY
    } else {
        CaptureModelRecoveryAction.NONE
    }
}

internal fun captureModelTaskTitle(
    snapshot: ModelTaskSnapshot?,
    parseSnapshot: ModelTaskSnapshot?,
): String {
    val parseStatus = parseSnapshot?.status
    val assessmentStatus = snapshot?.status
    val assessmentDecision = (snapshot?.output as? CaptureAssessmentOutput)
        ?.assessment
        ?.decision
    return when {
        parseStatus == ModelTaskStatus.CANCELLED -> "这道题暂时没准备好"
        parseStatus == ModelTaskStatus.SUCCEEDED && parseSnapshot.provider?.isDemo == true ->
            "需要连接模型"
        parseStatus == ModelTaskStatus.SUCCEEDED -> "题面已准备好"
        parseStatus == ModelTaskStatus.RETRYABLE_FAILURE ||
            parseStatus == ModelTaskStatus.PERMANENT_FAILURE -> "这道题暂时没准备好"
        parseSnapshot != null -> "正在准备这道题"
        assessmentStatus == ModelTaskStatus.CANCELLED -> "这道题暂时没准备好"
        snapshot == null -> "正在准备这道题"
        assessmentStatus == ModelTaskStatus.SUCCEEDED && snapshot.provider?.isDemo == true ->
            "需要连接模型"
        assessmentStatus == ModelTaskStatus.SUCCEEDED &&
            assessmentDecision == CaptureAssessmentDecision.SPLIT -> "正在整理这一页"
        assessmentStatus == ModelTaskStatus.SUCCEEDED -> "正在准备这道题"
        assessmentStatus == ModelTaskStatus.RETRYABLE_FAILURE ||
            assessmentStatus == ModelTaskStatus.PERMANENT_FAILURE -> "这道题暂时没准备好"
        else -> "正在准备这道题"
    }
}

internal fun capturePrivacyLine(
    snapshot: ModelTaskSnapshot?,
    parseSnapshot: ModelTaskSnapshot? = null,
    sourcePersisted: Boolean = true,
): String = when {
    !sourcePersisted -> "拍摄后会保存原图"
    snapshot?.provider?.isDemo == true || parseSnapshot?.provider?.isDemo == true ->
        "原图保存在本机"
    (parseSnapshot ?: snapshot)?.failure?.code == ModelFailureCode.MODEL_NOT_CONFIGURED ->
        "原图保存在本机"
    (parseSnapshot ?: snapshot)?.provider != null ->
        "原图已保存 · 发送范围由你决定"
    else -> "原图已保存在本机"
}

internal fun modelPipelineDetail(
    snapshot: ModelTaskSnapshot?,
    parseSnapshot: ModelTaskSnapshot?,
): String {
    if (parseSnapshot != null) {
        if (parseSnapshot.status == ModelTaskStatus.CANCELLED) {
            return "处理已停止，原图已经保存，可以重新处理。"
        }
        if (
            parseSnapshot.provider?.isDemo == true &&
            parseSnapshot.status == ModelTaskStatus.SUCCEEDED
        ) {
            return "原图已经保存，连接模型后可以继续。"
        }
        if (parseSnapshot.status == ModelTaskStatus.SUCCEEDED) return "题面已经准备好。"
        if (parseSnapshot.status == ModelTaskStatus.RETRYABLE_FAILURE) {
            return if (captureModelRecoveryAction(snapshot, parseSnapshot) ==
                CaptureModelRecoveryAction.OPEN_SETTINGS
            ) {
                "原图已经保存，检查模型设置后可以继续。"
            } else {
                "这次没有完成，原图已经保存，可以重试。"
            }
        }
        if (parseSnapshot.status == ModelTaskStatus.PERMANENT_FAILURE) {
            return if (captureModelRecoveryAction(snapshot, parseSnapshot) ==
                CaptureModelRecoveryAction.OPEN_SETTINGS
            ) {
                "原图已经保存，检查模型设置后可以继续。"
            } else {
                "这次没有完成，原图已经保存，可以重新处理。"
            }
        }
        return "原图已经保存，完成后会显示题面。"
    }
    if (snapshot == null) return "原图已经保存，可以稍后回来继续。"
    if (snapshot.status == ModelTaskStatus.CANCELLED) {
        return "处理已停止，原图已经保存，可以重新处理。"
    }
    if (snapshot.provider?.isDemo == true && snapshot.status == ModelTaskStatus.SUCCEEDED) {
        return "原图已经保存，连接模型后可以继续。"
    }
    if (snapshot.status == ModelTaskStatus.RETRYABLE_FAILURE) {
        return if (captureModelRecoveryAction(snapshot, parseSnapshot) ==
            CaptureModelRecoveryAction.OPEN_SETTINGS
        ) {
            "原图已经保存，检查模型设置后可以继续。"
        } else {
            "这次没有完成，原图已经保存，可以重试。"
        }
    }
    if (snapshot.status == ModelTaskStatus.PERMANENT_FAILURE) {
        return if (captureModelRecoveryAction(snapshot, parseSnapshot) ==
            CaptureModelRecoveryAction.OPEN_SETTINGS
        ) {
            "原图已经保存，检查模型设置后可以继续。"
        } else {
            "这次没有完成，原图已经保存，可以重新处理。"
        }
    }
    val assessment = (snapshot.output as? CaptureAssessmentOutput)?.assessment
    return when {
        assessment == null -> "原图已经保存，完成后会显示题面。"
        assessment.decision == CaptureAssessmentDecision.PASS ->
            "题目范围清楚，正在准备题面。"
        assessment.decision == CaptureAssessmentDecision.SPLIT ->
            "正在把这一页里的题目分别整理，原图已经保留。"
        assessment.issues.isNotEmpty() -> assessment.issues.first().message
        else -> "原图已经保存，完成后会显示题面。"
    }
}
