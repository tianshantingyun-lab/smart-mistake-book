package com.tingyun.smartmistakebook.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline

@Composable
internal fun CaptureStructuredDocumentEditor(
    state: CaptureWorkspaceUiState,
    enabled: Boolean,
    onBlockChange: (ContentBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .border(1.dp, Outline, RoundedCornerShape(8.dp))
            .background(JadeSoft.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
            .padding(14.dp)
            .testTag("capture_structured_editor"),
    ) {
        Text("修改题面", color = Ink, style = MaterialTheme.typography.titleSmall)
        Text(
            "只改有误的地方，原图会继续保留。",
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        state.workingDocument.document.blocks.forEachIndexed { index, block ->
            when (block) {
                is ContentBlock.Paragraph -> StructuredField(
                    value = block.markdown,
                    label = "题干或说明 ${index + 1}",
                    enabled = enabled,
                    tag = "capture_block_${block.id}",
                    onValueChange = { onBlockChange(block.copy(markdown = safeParagraphEdit(it))) },
                )

                is ContentBlock.Formula -> StructuredField(
                    value = block.latex,
                    label = "公式 ${index + 1}",
                    enabled = enabled,
                    tag = "capture_block_${block.id}",
                    onValueChange = { onBlockChange(block.copy(latex = safeFormulaEdit(it))) },
                )

                is ContentBlock.ChoiceGroup -> {
                    StructuredField(
                        value = block.promptMarkdown,
                        label = "选择题题干 ${index + 1}",
                        enabled = enabled,
                        tag = "capture_block_${block.id}_prompt",
                        onValueChange = {
                            onBlockChange(block.copy(promptMarkdown = safeParagraphEdit(it)))
                        },
                    )
                    block.choices.forEachIndexed { choiceIndex, choice ->
                        StructuredField(
                            value = choice.markdown,
                            label = "选项 ${('A'.code + choiceIndex).toChar()}",
                            enabled = enabled,
                            tag = "capture_block_${block.id}_choice_${choice.id}",
                            onValueChange = { value ->
                                if (value.isBlank()) return@StructuredField
                                val choices = block.choices.toMutableList().also {
                                    it[choiceIndex] = choice.copy(markdown = safeParagraphEdit(value))
                                }
                                onBlockChange(block.copy(choices = choices))
                            },
                        )
                    }
                }

                is ContentBlock.Figure -> ReadOnlyBlock(
                    "图形 ${index + 1} 已按原图保留，暂不在这里改动",
                    "capture_block_${block.id}_readonly",
                )

                is ContentBlock.Unknown -> ReadOnlyBlock(
                    "这部分已保留，暂不支持安全修改",
                    "capture_block_${block.id}_readonly",
                )
            }
        }
    }
}

@Composable
private fun StructuredField(
    value: String,
    label: String,
    enabled: Boolean,
    tag: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        enabled = enabled,
        minLines = 1,
        maxLines = 6,
        modifier = Modifier
            .padding(top = 10.dp)
            .testTag(tag),
    )
}

@Composable
private fun ReadOnlyBlock(text: String, tag: String) {
    Text(
        text = text,
        color = InkSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier
            .padding(top = 12.dp)
            .testTag(tag),
    )
}
