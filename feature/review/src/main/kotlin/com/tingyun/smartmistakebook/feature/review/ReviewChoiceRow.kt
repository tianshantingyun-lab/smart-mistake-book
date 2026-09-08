package com.tingyun.smartmistakebook.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tingyun.smartmistakebook.core.model.TutorChoice
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import com.tingyun.smartmistakebook.core.ui.SmartColors

/**
 * 复习选择题的选项行——错题复习与知识点复习共用（spec dual-review-entry §3.3）。
 *
 * 三态视觉（选中 / 已提交且正确 / 已提交需修正）+ 无障碍语义（radio 角色、选中态、
 * 状态描述、锁定态 disabled）。[testTag] 由调用方给定，保持各复习面的测试锚点稳定。
 * 消灭的失败：两个复习面各写一份选项行，视觉与语义随改动漂移。
 */
@Composable
internal fun ReviewChoiceRow(
    choice: TutorChoice,
    isCorrect: Boolean,
    selectedChoice: String?,
    submittedChoice: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    testTag: String = "review_choice_${choice.id}",
) {
    val isSelected = selectedChoice == choice.id
    val isSubmitted = submittedChoice != null
    val isSubmittedSelection = submittedChoice == choice.id
    val outlineColor = when {
        isSubmittedSelection && isCorrect -> SmartColors.Jade
        isSubmittedSelection -> SmartColors.ErrorWarm
        isSelected -> SmartColors.Jade
        else -> SmartColors.Outline
    }
    val backgroundColor = if (isSelected) SmartColors.JadeSoft else SmartColors.Paper
    val shape = RoundedCornerShape(8.dp)
    val stateLabel = when {
        isSubmittedSelection && isCorrect -> "已提交，回答正确"
        isSubmittedSelection -> "已提交，需要修正"
        isSubmitted -> "未选择"
        !enabled && isSelected -> "已选中，当前选择已锁定"
        !enabled -> "当前不可选择"
        isSelected -> "已选中，尚未提交"
        else -> "可选择"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .border(1.dp, outlineColor, shape)
            .clickable(
                enabled = enabled && !isSubmitted,
                role = Role.RadioButton,
            ) { onSelect(choice.id) }
            .semantics {
                role = Role.RadioButton
                selected = isSelected
                stateDescription = stateLabel
                if (!enabled || isSubmitted) disabled()
            }
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .border(1.dp, outlineColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = choice.id,
                color = SmartColors.Ink,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(12.dp))
        SafeMarkdownText(
            markdown = choice.markdown,
            modifier = Modifier.weight(1f),
            color = SmartColors.Ink,
            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
        )
    }
}
