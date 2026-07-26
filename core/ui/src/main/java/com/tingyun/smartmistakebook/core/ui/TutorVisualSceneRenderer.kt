package com.tingyun.smartmistakebook.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tingyun.smartmistakebook.core.model.ReadableMathText
import com.tingyun.smartmistakebook.core.model.TutorComparisonRow
import com.tingyun.smartmistakebook.core.model.TutorComparisonScene
import com.tingyun.smartmistakebook.core.model.TutorConceptMapScene
import com.tingyun.smartmistakebook.core.model.TutorConceptRelation
import com.tingyun.smartmistakebook.core.model.TutorEvidenceChainScene
import com.tingyun.smartmistakebook.core.model.TutorEvidencePoint
import com.tingyun.smartmistakebook.core.model.TutorEvidencePointKind
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationScene
import com.tingyun.smartmistakebook.core.model.TutorFormulaDerivationStep
import com.tingyun.smartmistakebook.core.model.TutorMotionScene
import com.tingyun.smartmistakebook.core.model.TutorProcessStage
import com.tingyun.smartmistakebook.core.model.TutorProcessTimelineScene
import com.tingyun.smartmistakebook.core.model.TutorSceneEmphasis
import com.tingyun.smartmistakebook.core.model.TutorSceneStep
import com.tingyun.smartmistakebook.core.model.TutorSpatialDiagramScene
import com.tingyun.smartmistakebook.core.model.TutorStepFlowScene
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorVisualProgramScene
import com.tingyun.smartmistakebook.core.visual.ui.TutorVisualDocumentContent

/**
 * Deterministically renders the bounded tutor scene contract. This renderer has no network,
 * WebView, runtime-code, image, or model-controlled styling path.
 */
@Composable
fun TutorVisualSceneRenderer(
    scene: TutorVisualScene,
    modifier: Modifier = Modifier,
    onOpenOriginal: (() -> Unit)? = null,
    onReportIncorrect: (() -> Unit)? = null,
    onTargetHit: ((String) -> Unit)? = null,
) {
    SceneFrame(
        scene = scene,
        modifier = modifier,
    ) {
        when (scene) {
            is TutorStepFlowScene -> StepFlowScene(scene)
            is TutorComparisonScene -> ComparisonScene(scene)
            is TutorEvidenceChainScene -> EvidenceChainScene(scene)
            is TutorProcessTimelineScene -> ProcessTimelineScene(scene)
            is TutorConceptMapScene -> ConceptMapScene(scene)
            is TutorFormulaDerivationScene -> FormulaDerivationScene(scene)
            is TutorSpatialDiagramScene -> SpatialDiagramScene(scene)
            is TutorMotionScene -> TutorMotionSceneContent(scene)
            is TutorVisualProgramScene -> TutorVisualProgramContent(scene)
            is TutorVisualDocumentScene -> TutorVisualDocumentContent(
                scene = scene,
                onOpenOriginal = onOpenOriginal,
                onReportIncorrect = onReportIncorrect,
                onTargetHit = onTargetHit,
            )
        }
    }
}

@Composable
private fun SceneFrame(
    scene: TutorVisualScene,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("tutor-visual-scene-${scene.sceneId}"),
        color = Paper,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = scene.title,
                modifier = Modifier.semantics { heading() },
                color = Ink,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

@Composable
private fun StepFlowScene(scene: TutorStepFlowScene) {
    Column(modifier = Modifier.fillMaxWidth()) {
        scene.steps.forEachIndexed { index, step ->
            key(step.stepId) {
                StepRow(
                    step = step,
                    index = index,
                    total = scene.steps.size,
                )
                if (index < scene.steps.lastIndex) {
                    Row(modifier = Modifier.height(14.dp)) {
                        Box(
                            modifier = Modifier.width(30.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(2.dp)
                                    .height(14.dp)
                                    .background(Outline),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    step: TutorSceneStep,
    index: Int,
    total: Int,
) {
    val containerColor = if (step.emphasis == TutorSceneEmphasis.KEY) JadeSoft else Paper
    val borderColor = when (step.emphasis) {
        TutorSceneEmphasis.NORMAL -> Outline
        TutorSceneEmphasis.KEY -> JadeActive
        TutorSceneEmphasis.CHECK -> JadeMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-step-${step.stepId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier
                .size(30.dp)
                .semantics {
                    contentDescription = "第 ${index + 1} 步，共 $total 步"
                },
            color = if (step.emphasis == TutorSceneEmphasis.KEY) JadeActive else JadeSoft,
            contentColor = if (step.emphasis == TutorSceneEmphasis.KEY) OnJade else JadeActive,
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = containerColor,
            contentColor = Ink,
            shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
            border = BorderStroke(1.dp, borderColor),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = step.label,
                        modifier = Modifier.weight(1f),
                        color = Ink,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    StepEmphasisLabel(step.emphasis)
                }
                SafeMarkdownText(
                    markdown = step.bodyMarkdown,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                step.formula?.let { formula ->
                    TutorFormulaText(
                        formula = formula,
                        testTag = "tutor-step-formula-${step.stepId}",
                    )
                }
            }
        }
    }
}

@Composable
private fun StepEmphasisLabel(emphasis: TutorSceneEmphasis) {
    val label = when (emphasis) {
        TutorSceneEmphasis.NORMAL -> return
        TutorSceneEmphasis.KEY -> "关键"
        TutorSceneEmphasis.CHECK -> "核对"
    }
    Surface(
        color = JadeSoft,
        contentColor = JadeActive,
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TutorFormulaText(
    formula: String,
    testTag: String,
) {
    val readableFormula = ReadableMathText.formula(formula)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics(mergeDescendants = true) {
                contentDescription = "公式：$readableFormula"
            },
        color = JadeSoft,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
    ) {
        Text(
            text = readableFormula,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            color = Ink,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ComparisonScene(scene: TutorComparisonScene) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        scene.rows.forEach { row ->
            key(row.rowId) {
                ComparisonRow(
                    row = row,
                    leftTitle = scene.leftTitle,
                    rightTitle = scene.rightTitle,
                )
            }
        }
    }
}

@Composable
private fun ComparisonRow(
    row: TutorComparisonRow,
    leftTitle: String,
    rightTitle: String,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-comparison-${row.rowId}"),
        color = Paper,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
        border = BorderStroke(1.dp, Outline),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = row.criterion,
                color = Ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                if (maxWidth < 420.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ComparisonCell(leftTitle, row.leftMarkdown, accented = true)
                        ComparisonCell(rightTitle, row.rightMarkdown, accented = false)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ComparisonCell(
                            title = leftTitle,
                            markdown = row.leftMarkdown,
                            accented = true,
                            modifier = Modifier.weight(1f),
                        )
                        ComparisonCell(
                            title = rightTitle,
                            markdown = row.rightMarkdown,
                            accented = false,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            row.takeawayMarkdown?.let { takeaway ->
                SafeMarkdownText(
                    markdown = takeaway,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(JadeSoft, RoundedCornerShape(SmartDimens.ChipRadius))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    color = Ink,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ComparisonCell(
    title: String,
    markdown: String,
    accented: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (accented) JadeSoft else Paper,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.ChipRadius),
        border = BorderStroke(1.dp, if (accented) JadeMuted else Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                color = if (accented) JadeActive else InkSecondary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SafeMarkdownText(
                markdown = markdown,
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EvidenceChainScene(scene: TutorEvidenceChainScene) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LabeledMarkdownCard(
            label = "要说明",
            markdown = scene.claimMarkdown,
            accented = false,
            modifier = Modifier.testTag("tutor-evidence-claim-${scene.sceneId}"),
        )
        scene.evidence.forEach { point ->
            key(point.pointId) { EvidencePointRow(point) }
        }
        LabeledMarkdownCard(
            label = "结论",
            markdown = scene.conclusionMarkdown,
            accented = true,
            modifier = Modifier.testTag("tutor-evidence-conclusion-${scene.sceneId}"),
        )
    }
}

@Composable
private fun EvidencePointRow(point: TutorEvidencePoint) {
    val label = when (point.kind) {
        TutorEvidencePointKind.GIVEN -> "已知"
        TutorEvidencePointKind.INFERENCE -> "推理"
        TutorEvidencePointKind.CHECK -> "核对"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-evidence-${point.pointId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            color = if (point.kind == TutorEvidencePointKind.INFERENCE) Paper else JadeSoft,
            contentColor = JadeActive,
            shape = RoundedCornerShape(SmartDimens.ChipRadius),
            border = BorderStroke(1.dp, JadeMuted),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SafeMarkdownText(
            markdown = point.markdown,
            modifier = Modifier
                .weight(1f)
                .padding(top = 3.dp),
            color = InkSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ProcessTimelineScene(scene: TutorProcessTimelineScene) {
    Column(modifier = Modifier.fillMaxWidth()) {
        scene.stages.forEachIndexed { index, stage ->
            key(stage.stageId) {
                ProcessStageRow(
                    stage = stage,
                    index = index,
                    total = scene.stages.size,
                )
                if (index < scene.stages.lastIndex) {
                    ProcessTransition(stage.transitionMarkdown)
                }
            }
        }
    }
}

@Composable
private fun ProcessStageRow(
    stage: TutorProcessStage,
    index: Int,
    total: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-process-stage-${stage.stageId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            modifier = Modifier
                .size(30.dp)
                .semantics {
                    contentDescription = "第 ${index + 1} 个阶段，共 $total 个阶段"
                },
            color = if (index == total - 1) JadeActive else JadeSoft,
            contentColor = if (index == total - 1) OnJade else JadeActive,
            shape = CircleShape,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = if (index == total - 1) JadeSoft else Paper,
            contentColor = Ink,
            shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
            border = BorderStroke(1.dp, if (index == total - 1) JadeActive else Outline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = stage.label,
                    color = Ink,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                SafeMarkdownText(
                    markdown = stage.bodyMarkdown,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ProcessTransition(markdown: String?) {
    if (markdown == null) {
        Row(modifier = Modifier.height(18.dp)) {
            Box(
                modifier = Modifier.width(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(18.dp)
                        .background(JadeMuted),
                )
            }
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(42.dp)
                    .background(JadeMuted),
            )
        }
        SafeMarkdownText(
            markdown = markdown,
            modifier = Modifier
                .weight(1f)
                .background(JadeSoft, RoundedCornerShape(SmartDimens.ChipRadius))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            color = JadeActive,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun ConceptMapScene(scene: TutorConceptMapScene) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LabeledMarkdownCard(
            label = "当前核心",
            markdown = scene.centerMarkdown,
            accented = true,
            modifier = Modifier.testTag("tutor-concept-center-${scene.sceneId}"),
        )
        scene.relations.forEach { relation ->
            key(relation.relationId) {
                ConceptRelationRow(relation)
            }
        }
    }
}

@Composable
private fun ConceptRelationRow(relation: TutorConceptRelation) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-concept-relation-${relation.relationId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.width(78.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(10.dp)
                    .background(JadeMuted),
            )
            Surface(
                color = JadeSoft,
                contentColor = JadeActive,
                shape = RoundedCornerShape(SmartDimens.ChipRadius),
                border = BorderStroke(1.dp, JadeMuted),
            ) {
                Text(
                    text = relation.relationLabel,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = Paper,
            contentColor = Ink,
            shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
            border = BorderStroke(1.dp, Outline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SafeMarkdownText(
                    markdown = relation.targetMarkdown,
                    color = Ink,
                    style = MaterialTheme.typography.bodyLarge,
                )
                relation.detailMarkdown?.let { detail ->
                    SafeMarkdownText(
                        markdown = detail,
                        color = InkSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun FormulaDerivationScene(scene: TutorFormulaDerivationScene) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "起点",
            color = InkSecondary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        TutorFormulaText(
            formula = scene.startFormula,
            testTag = "tutor-formula-derivation-start-${scene.sceneId}",
        )
        scene.steps.forEachIndexed { index, step ->
            key(step.stepId) {
                FormulaDerivationStepRow(
                    step = step,
                    index = index,
                    total = scene.steps.size,
                )
            }
        }
    }
}

@Composable
private fun FormulaDerivationStepRow(
    step: TutorFormulaDerivationStep,
    index: Int,
    total: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tutor-formula-derivation-${step.stepId}"),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            modifier = Modifier.width(30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(10.dp)
                    .background(JadeMuted),
            )
            Surface(
                modifier = Modifier
                    .size(30.dp)
                    .semantics {
                        contentDescription = "第 ${index + 1} 次变形，共 $total 次"
                    },
                color = if (index == total - 1) JadeActive else JadeSoft,
                contentColor = if (index == total - 1) OnJade else JadeActive,
                shape = CircleShape,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = Paper,
            contentColor = Ink,
            shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
            border = BorderStroke(1.dp, if (index == total - 1) JadeActive else Outline),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                SafeMarkdownText(
                    markdown = step.reasonMarkdown,
                    color = InkSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TutorFormulaText(
                    formula = step.resultFormula,
                    testTag = "tutor-formula-derivation-result-${step.stepId}",
                )
            }
        }
    }
}

@Composable
private fun LabeledMarkdownCard(
    label: String,
    markdown: String,
    accented: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (accented) JadeSoft else Paper,
        contentColor = Ink,
        shape = RoundedCornerShape(SmartDimens.SurfaceRadius),
        border = BorderStroke(1.dp, if (accented) JadeActive else Outline),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                color = JadeActive,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            SafeMarkdownText(
                markdown = markdown,
                color = Ink,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
