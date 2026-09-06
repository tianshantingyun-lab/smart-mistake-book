package com.tingyun.smartmistakebook.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemClassification
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.UserProblemClassification
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import kotlinx.coroutines.launch

/**
 * Offline correction editor for an already-imported mistake. Unlike the
 * model-assisted editor it does not require a successful online task: the
 * user's final classification set is saved directly through
 * [MistakeOrganizationRepository.correctConfirmedOrganization] with
 * USER_CORRECTED authority (which the local policy then protects from being
 * overwritten by later automatic organization).
 */
@Composable
internal fun MistakeOfflineCorrectionEditor(
    key: MistakeRevisionKey,
    confirmed: ConfirmedMistakeOrganization,
    organizationRepository: MistakeOrganizationRepository,
    onConfirmed: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val initialUserClassifications = remember(confirmed.classifications) {
        confirmed.classifications
            .filter { it.dimension in EDITABLE_OFFLINE_DIMENSIONS }
            .map { it.toUserProblemClassification() }
            .toMutableList()
    }
    var userClassifications by remember(key, confirmed.classifications) {
        mutableStateOf(initialUserClassifications.toMutableList())
    }
    var customEditorVisible by rememberSaveable(key) { mutableStateOf(false) }
    var dimensionMenuExpanded by remember { mutableStateOf(false) }
    var customDimension by rememberSaveable(key) {
        mutableStateOf(ClassificationDimension.KNOWLEDGE)
    }
    var customLabel by rememberSaveable(key) { mutableStateOf("") }
    var customError by rememberSaveable(key) { mutableStateOf<String?>(null) }
    var isSaving by remember(key) { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("mistake_offline_correction"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "修改章节 / 知识点",
            color = Ink,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        Text(
            "修改立即对当前版本生效；之后的自动整理不会覆盖你的选择。",
            color = InkSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
        userClassifications.forEachIndexed { index, classification ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mistake_offline_classification_$index"),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${classification.dimension.contentLabel()} · ${classification.displayName}",
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        userClassifications = userClassifications.filterIndexed { itemIndex, _ ->
                            itemIndex != index
                        }.toMutableList()
                    },
                    enabled = !isSaving,
                ) { Text("移除") }
            }
        }
        OutlinedButton(
            onClick = { customEditorVisible = !customEditorVisible },
            enabled = !isSaving,
            modifier = Modifier.testTag("mistake_offline_add_toggle"),
        ) {
            Text(if (customEditorVisible) "收起补充" else "补充或纠正分类")
        }
        if (customEditorVisible) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = JadeSoft.copy(alpha = 0.28f),
                border = BorderStroke(1.dp, Outline),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        OutlinedButton(
                            onClick = { dimensionMenuExpanded = true },
                            enabled = !isSaving,
                            modifier = Modifier.testTag("mistake_offline_dimension"),
                        ) { Text(customDimension.contentLabel()) }
                        DropdownMenu(
                            expanded = dimensionMenuExpanded,
                            onDismissRequest = { dimensionMenuExpanded = false },
                        ) {
                            EDITABLE_OFFLINE_DIMENSIONS.forEach { dimension ->
                                DropdownMenuItem(
                                    text = { Text(dimension.contentLabel()) },
                                    onClick = {
                                        customDimension = dimension
                                        dimensionMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = customLabel,
                        onValueChange = {
                            customLabel = it.take(96)
                            customError = null
                        },
                        label = { Text("正确的分类名称") },
                        singleLine = true,
                        enabled = !isSaving,
                        isError = customError != null,
                        supportingText = customError?.let { error -> { Text(error) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("mistake_offline_label"),
                    )
                    Button(
                        onClick = {
                            val label = customLabel.trim()
                            val duplicate = userClassifications.any {
                                it.dimension == customDimension &&
                                    it.displayName.equals(label, ignoreCase = true)
                            }
                            customError = when {
                                label.isEmpty() -> "先填写分类名称"
                                duplicate -> "这个分类已经在列表里"
                                userClassifications.size >=
                                    ProblemOrganizationSelection.MAX_USER_CLASSIFICATIONS ->
                                    "一次最多补充 " +
                                        "${ProblemOrganizationSelection.MAX_USER_CLASSIFICATIONS} 个分类"
                                else -> null
                            }
                            if (customError == null) {
                                userClassifications = (userClassifications +
                                    UserProblemClassification(
                                        dimension = customDimension,
                                        displayName = label,
                                    )).toMutableList()
                                customLabel = ""
                            }
                        },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = JadeActive),
                        modifier = Modifier.testTag("mistake_offline_add"),
                    ) { Text("加入本次修改") }
                }
            }
        }
        Button(
            onClick = {
                val hasKnowledge = userClassifications.any {
                    it.dimension == ClassificationDimension.KNOWLEDGE
                }
                val hasChapter = userClassifications.any {
                    it.dimension == ClassificationDimension.CHAPTER
                }
                when {
                    userClassifications.isEmpty() ->
                        onFailure("请至少保留一个章节和一个知识点")
                    !hasKnowledge || !hasChapter ->
                        onFailure("请同时保留至少一个章节和一个知识点")
                    else -> {
                        isSaving = true
                        scope.launch {
                            try {
                                organizationRepository.correctConfirmedOrganization(
                                    key = key,
                                    selection = ProblemOrganizationSelection(
                                        classificationIndexes = emptySet(),
                                        relationIndexes = emptySet(),
                                        userClassifications = userClassifications,
                                    ),
                                    correctedAtEpochMillis = System.currentTimeMillis(),
                                )
                                onConfirmed()
                            } catch (failure: Exception) {
                                isSaving = false
                                onFailure(failure.message ?: "保存失败，请重试")
                            }
                        }
                    }
                }
            },
            enabled = !isSaving,
            colors = ButtonDefaults.buttonColors(containerColor = JadeActive),
            modifier = Modifier.testTag("mistake_offline_save"),
        ) { Text(if (isSaving) "保存中…" else "保存修改") }
    }
}

private val EDITABLE_OFFLINE_DIMENSIONS = setOf(
    ClassificationDimension.CHAPTER,
    ClassificationDimension.KNOWLEDGE,
)

private fun ConfirmedProblemClassification.toUserProblemClassification(): UserProblemClassification =
    UserProblemClassification(dimension = dimension, displayName = displayName)
