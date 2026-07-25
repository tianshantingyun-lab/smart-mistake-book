package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.CaptureRecognitionState
import com.tingyun.smartmistakebook.core.domain.CaptureWritingLayer
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.StructuredContentLimits
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.OutlineActionChip
import com.tingyun.smartmistakebook.core.ui.PrimaryActionButton

internal data class CaptureSubjectOption(val value: String, val label: String)

internal enum class CaptureCandidateKind {
    NONE,
    LOCAL_TRANSITIONAL,
    MODEL_STRUCTURED,
}

internal enum class CaptureCompletionIntent {
    SAVE_TO_LIBRARY,
    START_TUTORING,
}

internal val CAPTURE_SUBJECT_OPTIONS = listOf(
    CaptureSubjectOption("CHINESE", "语文"),
    CaptureSubjectOption("MATH", "数学"),
    CaptureSubjectOption("ENGLISH", "英语"),
    CaptureSubjectOption("PHYSICS", "物理"),
    CaptureSubjectOption("CHEMISTRY", "化学"),
    CaptureSubjectOption("BIOLOGY", "生物"),
    CaptureSubjectOption("POLITICS", "思想政治"),
    CaptureSubjectOption("HISTORY", "历史"),
    CaptureSubjectOption("GEOGRAPHY", "地理"),
    CaptureSubjectOption("GENERAL", "其他"),
)

@Composable
internal fun CaptureCorrectionForm(
    subject: String,
    title: String,
    transcription: String,
    writingLayer: CaptureWritingLayer,
    writingLayerResolved: Boolean = writingLayer != CaptureWritingLayer.UNKNOWN,
    recognitionState: CaptureRecognitionState,
    recognitionConfidence: Double?,
    recognitionBlockCount: Int,
    candidateKind: CaptureCandidateKind = if (
        recognitionState == CaptureRecognitionState.CANDIDATE_AVAILABLE
    ) {
        CaptureCandidateKind.LOCAL_TRANSITIONAL
    } else {
        CaptureCandidateKind.NONE
    },
    candidateUsable: Boolean = transcription.isNotBlank(),
    entryGateOpen: Boolean = true,
    entryGateMessage: String = "",
    initialEditorExpanded: Boolean = false,
    structuredProjectionEdited: Boolean = false,
    transcriptionEditable: Boolean = true,
    completionIntent: CaptureCompletionIntent = CaptureCompletionIntent.SAVE_TO_LIBRARY,
    isSaving: Boolean,
    isRetryLocked: Boolean,
    onSubjectChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onTranscriptionChange: (String) -> Unit,
    onWritingLayerChange: (CaptureWritingLayer) -> Unit,
    onCommit: () -> Unit,
    modifier: Modifier = Modifier,
    structuredEditorState: CaptureWorkspaceUiState? = null,
    onStructuredBlockChange: (ContentBlock) -> Unit = {},
) {
    if (!candidateUsable) return

    var editorExpanded by rememberSaveable { mutableStateOf(initialEditorExpanded) }
    val editingEnabled = !isSaving && !isRetryLocked
    val commitEnabled = !isSaving && entryGateOpen

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, Outline, RoundedCornerShape(8.dp))
            .padding(16.dp)
            .testTag("capture_correction_form"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = JadeActive,
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    text = "题面可以直接使用",
                    color = Ink,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "题面已整理好，可以直接继续",
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!editorExpanded) {
            OutlineActionChip(
                text = "题面有误，修改",
                onClick = { editorExpanded = true },
                enabled = editingEnabled,
                modifier = Modifier
                    .padding(top = 14.dp)
                    .testTag("capture_edit_disclosure"),
            )
        } else {
            Text(
                text = "只改有误的地方，原图会继续保留。",
                modifier = Modifier.padding(top = 14.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )

            structuredEditorState?.let { state ->
                CaptureStructuredDocumentEditor(
                    state = state,
                    enabled = editingEnabled && state.finalConfirmationRequest == null,
                    onBlockChange = onStructuredBlockChange,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Text(
                text = "学科（可选）",
                modifier = Modifier.padding(top = 16.dp),
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CAPTURE_SUBJECT_OPTIONS.take(3).forEach { option ->
                    OutlineActionChip(
                        text = option.label,
                        onClick = { onSubjectChange(option.value) },
                        selected = subject == option.value,
                        enabled = editingEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CAPTURE_SUBJECT_OPTIONS.drop(3).forEach { option ->
                    OutlineActionChip(
                        text = option.label,
                        onClick = { onSubjectChange(option.value) },
                        selected = subject == option.value,
                        enabled = editingEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { onTitleChange(it.take(MAX_TITLE_CHARS)) },
                label = { Text("题目标题（可选）") },
                singleLine = true,
                enabled = editingEnabled,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .testTag("capture_title_input"),
            )
            if (transcriptionEditable) {
                OutlinedTextField(
                    value = transcription,
                    onValueChange = {
                        onTranscriptionChange(it.take(StructuredContentLimits.MAX_TEXT_CHARS))
                    },
                    label = { Text("题面") },
                    placeholder = { Text("只修改识别有误的部分") },
                    minLines = 4,
                    maxLines = 10,
                    enabled = editingEnabled,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .testTag("capture_transcription_input"),
                )
            }

            Text(
                text = "题面类型（可选）",
                modifier = Modifier.padding(top = 16.dp),
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    CaptureWritingLayer.PRINTED to "印刷题面",
                    CaptureWritingLayer.HANDWRITTEN to "手写题面",
                    CaptureWritingLayer.MIXED to "混合",
                ).forEach { (value, label) ->
                    OutlineActionChip(
                        text = label,
                        onClick = { onWritingLayerChange(value) },
                        selected = writingLayer == value,
                        enabled = editingEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (writingLayer == CaptureWritingLayer.UNKNOWN && writingLayerResolved) {
                Text(
                    text = "图形区域会按原图保留。",
                    modifier = Modifier.padding(top = 8.dp),
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        PrimaryActionButton(
            text = captureCommitButtonText(
                completionIntent = completionIntent,
                isSaving = isSaving,
                isRetryLocked = isRetryLocked,
                structuredProjectionEdited = structuredProjectionEdited,
            ),
            onClick = onCommit,
            enabled = commitEnabled,
            icon = Icons.Outlined.Save,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .testTag("capture_commit_button"),
        )
        if (!entryGateOpen && entryGateMessage.isNotBlank()) {
            Text(
                text = entryGateMessage,
                modifier = Modifier.padding(top = 8.dp),
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = when (completionIntent) {
                CaptureCompletionIntent.START_TUTORING ->
                    "不会自动加入错题本。"
                CaptureCompletionIntent.SAVE_TO_LIBRARY ->
                    "只存入错题本，不会自动开始讲解。"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .background(JadeSoft.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                .padding(10.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

internal fun captureCommitButtonText(
    completionIntent: CaptureCompletionIntent,
    isSaving: Boolean,
    isRetryLocked: Boolean,
    structuredProjectionEdited: Boolean,
): String = when (completionIntent) {
    CaptureCompletionIntent.START_TUTORING -> when {
        isSaving -> "正在保存待讲题目…"
        isRetryLocked -> "重试保存待讲题目"
        structuredProjectionEdited -> "保存修改并开始讲题"
        else -> "开始讲题"
    }
    CaptureCompletionIntent.SAVE_TO_LIBRARY -> when {
        isSaving -> "正在保存…"
        isRetryLocked -> "重试保存"
        structuredProjectionEdited -> "保存修改并存入错题本"
        else -> "存入错题本"
    }
}

private const val MAX_TITLE_CHARS = 120
