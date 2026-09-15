package com.tingyun.smartmistakebook.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.domain.ConfirmedMistakeOrganization
import com.tingyun.smartmistakebook.core.domain.ConfirmedProblemRelation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationRelationKey
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.UserProblemClassification
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
import com.tingyun.smartmistakebook.core.model.PROBLEM_ORGANIZATION_RELATION_KINDS
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.ui.Ink
import com.tingyun.smartmistakebook.core.ui.InkSecondary
import com.tingyun.smartmistakebook.core.ui.JadeActive
import com.tingyun.smartmistakebook.core.ui.JadeSoft
import com.tingyun.smartmistakebook.core.ui.Outline
import com.tingyun.smartmistakebook.core.ui.SafeMarkdownText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun OrganizationCorrectionEditor(
    requestId: String,
    input: ProblemOrganizationInput,
    output: ProblemOrganizationOutput,
    confirmed: ConfirmedMistakeOrganization,
    organizationRepository: MistakeOrganizationRepository,
    onConfirmed: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var classifications by remember(requestId, confirmed.classifications) {
        mutableStateOf(confirmedClassificationIndexes(output.plan, confirmed))
    }
    var relations by remember(requestId, confirmed.relations) { mutableStateOf(emptySet<Int>()) }
    var relationsEdited by remember(requestId) { mutableStateOf(false) }
    var relationRemovals by remember(requestId, confirmed.relations) {
        mutableStateOf(emptySet<ProblemOrganizationRelationKey>())
    }
    var userClassifications by remember(requestId, confirmed.classifications, output.plan) {
        mutableStateOf(unmappedConfirmedClassifications(output.plan, confirmed))
    }
    var customEditorVisible by rememberSaveable(requestId) { mutableStateOf(false) }
    var dimensionMenuExpanded by remember { mutableStateOf(false) }
    var customDimension by rememberSaveable(requestId) {
        mutableStateOf(ClassificationDimension.KNOWLEDGE)
    }
    var customLabel by rememberSaveable(requestId) { mutableStateOf("") }
    var customError by rememberSaveable(requestId) { mutableStateOf<String?>(null) }
    var isSaving by remember(requestId) { mutableStateOf(false) }
    val visibleClassificationIndexes = output.plan.classifications.indices.filter { index ->
        output.plan.classifications[index].dimension in PROBLEM_ORGANIZATION_CONTENT_DIMENSIONS
    }
    val visibleRelationIndexes = unconfirmedRelationIndexes(output.plan, confirmed)
    val hasKnowledge = classifications.any { index ->
        output.plan.classifications[index].dimension == ClassificationDimension.KNOWLEDGE
    } || userClassifications.any { it.dimension == ClassificationDimension.KNOWLEDGE }
    val hasChapter = classifications.any { index ->
        output.plan.classifications[index].dimension == ClassificationDimension.CHAPTER
    } || userClassifications.any { it.dimension == ClassificationDimension.CHAPTER }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("mistake_organization_suggestions"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("修改分类", color = Ink, fontWeight = FontWeight.SemiBold)
        visibleClassificationIndexes.forEach { index ->
            val suggestion = output.plan.classifications[index]
            SuggestionCheckRow(
                checked = index in classifications,
                title = "${suggestion.dimension.contentLabel()} · ${suggestion.displayName}",
                rationale = suggestion.rationaleMarkdown,
                onToggle = {
                    classifications = if (index in classifications) {
                        classifications - index
                    } else {
                        classifications + index
                    }
                },
                testTag = "mistake_classification_$index",
            )
        }
        OutlinedButton(
            onClick = { customEditorVisible = !customEditorVisible },
            enabled = !isSaving,
            modifier = Modifier.testTag("mistake_add_classification_toggle"),
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
                    Text(
                        "没有合适选项时，补充正确的板块或知识点。",
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Box {
                        OutlinedButton(
                            onClick = { dimensionMenuExpanded = true },
                            enabled = !isSaving,
                            modifier = Modifier.testTag("mistake_custom_dimension"),
                        ) { Text(customDimension.contentLabel()) }
                        DropdownMenu(
                            expanded = dimensionMenuExpanded,
                            onDismissRequest = { dimensionMenuExpanded = false },
                        ) {
                            CONTENT_EDITABLE_DIMENSIONS.forEach { dimension ->
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
                            .testTag("mistake_custom_label"),
                    )
                    Button(
                        onClick = {
                            val label = customLabel.trim()
                            val duplicate = output.plan.classifications.any {
                                it.dimension == customDimension &&
                                    it.displayName.equals(label, ignoreCase = true)
                            } || userClassifications.any {
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
                                userClassifications = userClassifications +
                                    UserProblemClassification(
                                        dimension = customDimension,
                                        displayName = label,
                                    )
                                customLabel = ""
                            }
                        },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(containerColor = JadeActive),
                        modifier = Modifier.testTag("mistake_custom_add"),
                    ) { Text("加入本次整理") }
                }
            }
        }
        userClassifications.forEachIndexed { index, classification ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("mistake_custom_classification_$index"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "你补充的 · ${classification.dimension.contentLabel()} · " +
                        classification.displayName,
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        userClassifications = userClassifications.filterIndexed { itemIndex, _ ->
                            itemIndex != index
                        }
                    },
                    enabled = !isSaving,
                ) { Text("移除") }
            }
        }
        if (confirmed.relations.isNotEmpty()) {
            Text("现有题目关系", color = Ink, fontWeight = FontWeight.SemiBold)
            confirmed.relations.forEachIndexed { index, relation ->
                val key = relation.toRelationKey()
                val targetTitle = input.relationCandidates.firstOrNull { candidate ->
                    candidate.problemId == relation.targetProblemId &&
                        candidate.problemRevisionId == relation.targetProblemRevisionId
                }?.title ?: "关联题"
                val pendingRemoval = key in relationRemovals
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("mistake_existing_relation_$index"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(checkNotNull(relation.kind.confirmedRelationLabel()), color = Ink)
                        Text(
                            targetTitle,
                            color = InkSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            relationRemovals = if (pendingRemoval) {
                                relationRemovals - key
                            } else {
                                relationRemovals + key
                            }
                        },
                        enabled = !isSaving,
                        modifier = Modifier.testTag("mistake_existing_relation_remove_$index"),
                    ) { Text(if (pendingRemoval) "恢复" else "移除") }
                }
            }
        }
        if (visibleRelationIndexes.isNotEmpty()) {
            Text("相关题目建议", color = Ink, fontWeight = FontWeight.SemiBold)
            visibleRelationIndexes.forEach { index ->
                val suggestion = output.plan.relations[index]
                val targetTitle = input.relationCandidates.firstOrNull {
                    it.problemId == suggestion.targetProblemId &&
                        it.problemRevisionId == suggestion.targetProblemRevisionId
                }?.title ?: "候选题"
                SuggestionCheckRow(
                    checked = index in relations,
                    title = "${suggestion.kind.relationLabel()} · $targetTitle",
                    rationale = suggestion.rationaleMarkdown,
                    onToggle = {
                        relationsEdited = true
                        relations = if (index in relations) relations - index else relations + index
                    },
                    testTag = "mistake_relation_$index",
                )
            }
        }
        if (!hasKnowledge) {
            Text(
                text = "至少保留一个知识点，后续复习计划才能正确使用这道题。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!hasChapter) {
            Text(
                text = "至少保留一个板块，错题本才能形成清晰层级。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        isSaving = true
                        try {
                            val confirmation = organizationRepository.confirm(
                                requestId = requestId,
                                selection = ProblemOrganizationSelection(
                                    classificationIndexes = classifications,
                                    relationIndexes = if (relationsEdited) relations else emptySet(),
                                    userClassifications = userClassifications,
                                    relationRemovals = relationRemovals,
                                ),
                                acceptedAtEpochMillis = System.currentTimeMillis(),
                            )
                            if (!confirmation.applied) {
                                // 与离线校正一致：被策略拒绝时必须报失败，不能假成功。
                                onFailure("这次修改没有写进去，请重新进入后再试")
                            } else {
                                onConfirmed()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onFailure("暂时无法保存修改")
                        } finally {
                            isSaving = false
                        }
                    }
                },
                enabled = canSaveOrganizationCorrection(
                    hasKnowledge = hasKnowledge,
                    hasChapter = hasChapter,
                    selectedModelClassificationCount = classifications.size,
                    userClassificationCount = userClassifications.size,
                    isSaving = isSaving,
                ),
                colors = ButtonDefaults.buttonColors(containerColor = JadeActive),
                modifier = Modifier.testTag("mistake_organization_confirm"),
            ) { Text(if (isSaving) "正在保存" else "保存修改") }
        }
    }
}

private fun ConfirmedProblemRelation.toRelationKey() = ProblemOrganizationRelationKey(
    targetProblemId = targetProblemId,
    targetProblemRevisionId = targetProblemRevisionId,
    kind = kind,
)

internal fun confirmedClassificationIndexes(
    plan: ProblemOrganizationPlan,
    confirmed: ConfirmedMistakeOrganization,
): Set<Int> = plan.classifications.indices.filterTo(linkedSetOf()) { index ->
    val suggestion = plan.classifications[index]
    confirmed.classifications.any { accepted ->
        accepted.dimension == suggestion.dimension &&
            accepted.displayName.equals(suggestion.displayName.trim(), ignoreCase = true)
    }
}

internal fun unmappedConfirmedClassifications(
    plan: ProblemOrganizationPlan,
    confirmed: ConfirmedMistakeOrganization,
): List<UserProblemClassification> {
    val mapped = confirmedClassificationIndexes(plan, confirmed)
    return confirmed.classifications.mapNotNull { accepted ->
        val appearsInPlan = mapped.any { index ->
            val suggestion = plan.classifications[index]
            suggestion.dimension == accepted.dimension &&
                suggestion.displayName.trim().equals(accepted.displayName, ignoreCase = true)
        }
        if (appearsInPlan || accepted.dimension !in CONTENT_EDITABLE_DIMENSIONS) {
            null
        } else {
            runCatching {
                UserProblemClassification(accepted.dimension, accepted.displayName.trim())
            }.getOrNull()
        }
    }.distinctBy { it.dimension to it.displayName.lowercase() }
}

internal fun confirmedRelationIndexes(
    plan: ProblemOrganizationPlan,
    confirmed: ConfirmedMistakeOrganization,
): Set<Int> = plan.relations.indices.filterTo(linkedSetOf()) { index ->
    val suggestion = plan.relations[index]
    confirmed.relations.any { accepted ->
        accepted.targetProblemId == suggestion.targetProblemId &&
            accepted.targetProblemRevisionId == suggestion.targetProblemRevisionId &&
            accepted.kind == suggestion.kind
    }
}

internal fun unconfirmedRelationIndexes(
    plan: ProblemOrganizationPlan,
    confirmed: ConfirmedMistakeOrganization,
): List<Int> {
    val alreadyAccepted = confirmedRelationIndexes(plan, confirmed)
    return plan.relations.indices.filter { index ->
        index !in alreadyAccepted &&
            plan.relations[index].kind in PROBLEM_ORGANIZATION_RELATION_KINDS
    }
}

internal fun canSaveOrganizationCorrection(
    hasKnowledge: Boolean,
    hasChapter: Boolean,
    selectedModelClassificationCount: Int,
    userClassificationCount: Int,
    isSaving: Boolean,
): Boolean = hasKnowledge &&
    hasChapter &&
    selectedModelClassificationCount + userClassificationCount > 0 &&
    selectedModelClassificationCount + userClassificationCount <=
        ProblemOrganizationSelection.MAX_TOTAL_CLASSIFICATIONS &&
    !isSaving

@Composable
private fun SuggestionCheckRow(
    checked: Boolean,
    title: String,
    rationale: String,
    onToggle: () -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.padding(start = 6.dp, top = 10.dp)) {
            Text(title, color = Ink, style = MaterialTheme.typography.bodyMedium)
            SafeMarkdownText(
                markdown = rationale,
                color = InkSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val CONTENT_EDITABLE_DIMENSIONS = listOf(
    ClassificationDimension.CHAPTER,
    ClassificationDimension.KNOWLEDGE,
)

internal fun ClassificationDimension.contentLabel(): String = when (this) {
    ClassificationDimension.CHAPTER -> "板块"
    ClassificationDimension.KNOWLEDGE -> "知识点"
    else -> error("Unsupported content classification: $this")
}

private fun ProblemRelationKind.relationLabel(): String = when (this) {
    ProblemRelationKind.SAME_KNOWLEDGE -> "同一知识点"
    ProblemRelationKind.VARIANT_OF -> "变式题"
    ProblemRelationKind.PREREQUISITE_OF -> "前置题"
    ProblemRelationKind.SAME_FIGURE_PATTERN -> "相同图形结构"
    ProblemRelationKind.POSSIBLE_DUPLICATE -> "可能重复"
    else -> error("Unsupported problem relation: $this")
}

internal fun ProblemRelationKind.confirmedRelationLabel(): String? = when (this) {
    ProblemRelationKind.SAME_KNOWLEDGE -> "同一知识点"
    ProblemRelationKind.VARIANT_OF -> "这题是它的变式"
    ProblemRelationKind.PREREQUISITE_OF -> "这题是它的前置题"
    ProblemRelationKind.SAME_FIGURE_PATTERN -> "相同图形结构"
    ProblemRelationKind.POSSIBLE_DUPLICATE -> "可能与它重复"
    else -> null
}
