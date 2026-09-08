package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One bounded, read-only visual explanation for the confirmed question. The model selects only the
 * semantic shape and content; ids, schema version, layout, styling, and interaction stay local.
 */
@Serializable
sealed interface TutorVisualScene {
    val sceneId: String
    val title: String
    val schemaVersion: Int

    companion object {
        const val SCHEMA_VERSION = 1
        const val DOCUMENT_SCHEMA_VERSION = 2
        const val MAX_TITLE_CHARS = 96
        const val MAX_ITEM_MARKDOWN_CHARS = 600
        const val MAX_FORMULA_CHARS = 500
        const val MAX_TOTAL_TEXT_CHARS = 6_000
        const val MIN_STEP_COUNT = 2
        const val MAX_STEP_COUNT = 6
        const val MIN_COMPARISON_ROW_COUNT = 1
        const val MAX_COMPARISON_ROW_COUNT = 6
        const val MIN_EVIDENCE_COUNT = 1
        const val MAX_EVIDENCE_COUNT = 5
        const val MIN_PROCESS_STAGE_COUNT = 2
        const val MAX_PROCESS_STAGE_COUNT = 6
        const val MIN_CONCEPT_RELATION_COUNT = 2
        const val MAX_CONCEPT_RELATION_COUNT = 6
        const val MIN_FORMULA_DERIVATION_STEP_COUNT = 1
        const val MAX_FORMULA_DERIVATION_STEP_COUNT = 6
        const val MIN_SPATIAL_NODE_COUNT = 2
        const val MAX_SPATIAL_NODE_COUNT = 7
        const val MAX_CIRCUIT_NODE_COUNT = 9
        const val MIN_SPATIAL_EDGE_COUNT = 1
        const val MAX_SPATIAL_EDGE_COUNT = 10
        const val MAX_SPATIAL_NODE_LABEL_CHARS = 20
        const val MAX_SPATIAL_COMPACT_LABEL_CHARS = 6
        const val MAX_SPATIAL_EDGE_LABEL_CHARS = 20
        const val MAX_PROCESS_TRANSITION_CHARS = 180
        const val MAX_RELATION_LABEL_CHARS = 32
    }
}

@Serializable
enum class TutorSceneEmphasis {
    NORMAL,
    KEY,
    CHECK,
}

@Serializable
enum class TutorEvidencePointKind {
    GIVEN,
    INFERENCE,
    CHECK,
}

@Serializable
data class TutorSceneStep(
    val stepId: String,
    val label: String,
    val bodyMarkdown: String,
    val formula: String? = null,
    val emphasis: TutorSceneEmphasis = TutorSceneEmphasis.NORMAL,
) {
    init {
        stepId.requireTutorSceneId("Tutor scene step id")
        label.requireTutorSceneText("Tutor scene step label", TutorVisualScene.MAX_TITLE_CHARS, false)
        bodyMarkdown.requireTutorSceneText(
            "Tutor scene step markdown",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        formula?.requireTutorSceneFormula("Tutor scene step formula")
    }
}

@Serializable
@SerialName("step_flow")
data class TutorStepFlowScene(
    override val sceneId: String,
    override val title: String,
    val steps: List<TutorSceneStep>,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        require(steps.size in TutorVisualScene.MIN_STEP_COUNT..TutorVisualScene.MAX_STEP_COUNT) {
            "Tutor step flow must contain two to six steps"
        }
        requireUniqueTutorSceneIds(sceneId, steps.map(TutorSceneStep::stepId))
        requireTutorSceneTextBudget(
            listOf(title) + steps.flatMap { step ->
                listOfNotNull(step.label, step.bodyMarkdown, step.formula)
            },
        )
    }
}

@Serializable
data class TutorComparisonRow(
    val rowId: String,
    val criterion: String,
    val leftMarkdown: String,
    val rightMarkdown: String,
    val takeawayMarkdown: String? = null,
) {
    init {
        rowId.requireTutorSceneId("Tutor comparison row id")
        criterion.requireTutorSceneText(
            "Tutor comparison criterion",
            TutorVisualScene.MAX_TITLE_CHARS,
            false,
        )
        leftMarkdown.requireTutorSceneText(
            "Tutor comparison left markdown",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        rightMarkdown.requireTutorSceneText(
            "Tutor comparison right markdown",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        takeawayMarkdown?.requireTutorSceneText(
            "Tutor comparison takeaway",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
    }
}

@Serializable
@SerialName("comparison")
data class TutorComparisonScene(
    override val sceneId: String,
    override val title: String,
    val leftTitle: String,
    val rightTitle: String,
    val rows: List<TutorComparisonRow>,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        leftTitle.requireTutorSceneText("Tutor comparison left title", TutorVisualScene.MAX_TITLE_CHARS, false)
        rightTitle.requireTutorSceneText("Tutor comparison right title", TutorVisualScene.MAX_TITLE_CHARS, false)
        require(
            rows.size in
                TutorVisualScene.MIN_COMPARISON_ROW_COUNT..TutorVisualScene.MAX_COMPARISON_ROW_COUNT,
        ) { "Tutor comparison must contain one to six rows" }
        requireUniqueTutorSceneIds(sceneId, rows.map(TutorComparisonRow::rowId))
        requireTutorSceneTextBudget(
            listOf(title, leftTitle, rightTitle) + rows.flatMap { row ->
                listOfNotNull(
                    row.criterion,
                    row.leftMarkdown,
                    row.rightMarkdown,
                    row.takeawayMarkdown,
                )
            },
        )
    }
}

@Serializable
data class TutorEvidencePoint(
    val pointId: String,
    val kind: TutorEvidencePointKind,
    val markdown: String,
) {
    init {
        pointId.requireTutorSceneId("Tutor evidence point id")
        markdown.requireTutorSceneText(
            "Tutor evidence point markdown",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
    }
}

@Serializable
@SerialName("evidence_chain")
data class TutorEvidenceChainScene(
    override val sceneId: String,
    override val title: String,
    val claimMarkdown: String,
    val evidence: List<TutorEvidencePoint>,
    val conclusionMarkdown: String,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        claimMarkdown.requireTutorSceneText(
            "Tutor evidence claim",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        conclusionMarkdown.requireTutorSceneText(
            "Tutor evidence conclusion",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        require(evidence.size in TutorVisualScene.MIN_EVIDENCE_COUNT..TutorVisualScene.MAX_EVIDENCE_COUNT) {
            "Tutor evidence chain must contain one to five evidence points"
        }
        requireUniqueTutorSceneIds(sceneId, evidence.map(TutorEvidencePoint::pointId))
        requireTutorSceneTextBudget(
            listOf(title, claimMarkdown, conclusionMarkdown) + evidence.map(TutorEvidencePoint::markdown),
        )
    }
}

@Serializable
data class TutorProcessStage(
    val stageId: String,
    val label: String,
    val bodyMarkdown: String,
    val transitionMarkdown: String? = null,
) {
    init {
        stageId.requireTutorSceneId("Tutor process stage id")
        label.requireTutorSceneText(
            "Tutor process stage label",
            TutorVisualScene.MAX_TITLE_CHARS,
            false,
        )
        bodyMarkdown.requireTutorSceneText(
            "Tutor process stage markdown",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        transitionMarkdown?.requireTutorSceneText(
            "Tutor process transition markdown",
            TutorVisualScene.MAX_PROCESS_TRANSITION_CHARS,
            true,
        )
    }
}

/** A temporal or causal change in the current question, not a sequence of user actions. */
@Serializable
@SerialName("process_timeline")
data class TutorProcessTimelineScene(
    override val sceneId: String,
    override val title: String,
    val stages: List<TutorProcessStage>,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        require(
            stages.size in
                TutorVisualScene.MIN_PROCESS_STAGE_COUNT..TutorVisualScene.MAX_PROCESS_STAGE_COUNT,
        ) { "Tutor process timeline must contain two to six stages" }
        require(stages.last().transitionMarkdown == null) {
            "The final tutor process stage cannot transition to another stage"
        }
        requireUniqueTutorSceneIds(sceneId, stages.map(TutorProcessStage::stageId))
        requireTutorSceneTextBudget(
            listOf(title) + stages.flatMap { stage ->
                listOfNotNull(stage.label, stage.bodyMarkdown, stage.transitionMarkdown)
            },
        )
    }
}

@Serializable
data class TutorConceptRelation(
    val relationId: String,
    val relationLabel: String,
    val targetMarkdown: String,
    val detailMarkdown: String? = null,
) {
    init {
        relationId.requireTutorSceneId("Tutor concept relation id")
        relationLabel.requireTutorSceneText(
            "Tutor concept relation label",
            TutorVisualScene.MAX_RELATION_LABEL_CHARS,
            false,
        )
        targetMarkdown.requireTutorSceneText(
            "Tutor concept relation target",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        detailMarkdown?.requireTutorSceneText(
            "Tutor concept relation detail",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
    }
}

/** A bounded local-rendered concept map around one anchor from the confirmed question. */
@Serializable
@SerialName("concept_map")
data class TutorConceptMapScene(
    override val sceneId: String,
    override val title: String,
    val centerMarkdown: String,
    val relations: List<TutorConceptRelation>,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        centerMarkdown.requireTutorSceneText(
            "Tutor concept map center",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        require(
            relations.size in
                TutorVisualScene.MIN_CONCEPT_RELATION_COUNT..TutorVisualScene.MAX_CONCEPT_RELATION_COUNT,
        ) { "Tutor concept map must contain two to six relations" }
        requireUniqueTutorSceneIds(sceneId, relations.map(TutorConceptRelation::relationId))
        requireTutorSceneTextBudget(
            listOf(title, centerMarkdown) + relations.flatMap { relation ->
                listOfNotNull(
                    relation.relationLabel,
                    relation.targetMarkdown,
                    relation.detailMarkdown,
                )
            },
        )
    }
}

@Serializable
data class TutorFormulaDerivationStep(
    val stepId: String,
    val reasonMarkdown: String,
    val resultFormula: String,
) {
    init {
        stepId.requireTutorSceneId("Tutor formula derivation step id")
        reasonMarkdown.requireTutorSceneText(
            "Tutor formula derivation reason",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
        resultFormula.requireTutorSceneFormula("Tutor formula derivation result")
    }
}

/** A compact, local-rendered account of how one expression in the confirmed question changes. */
@Serializable
@SerialName("formula_derivation")
data class TutorFormulaDerivationScene(
    override val sceneId: String,
    override val title: String,
    val startFormula: String,
    val steps: List<TutorFormulaDerivationStep>,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        startFormula.requireTutorSceneFormula("Tutor formula derivation start")
        require(
            steps.size in
                TutorVisualScene.MIN_FORMULA_DERIVATION_STEP_COUNT..
                    TutorVisualScene.MAX_FORMULA_DERIVATION_STEP_COUNT,
        ) { "Tutor formula derivation must contain one to six steps" }
        requireUniqueTutorSceneIds(sceneId, steps.map(TutorFormulaDerivationStep::stepId))
        requireTutorSceneTextBudget(
            listOf(title, startFormula) + steps.flatMap { step ->
                listOf(step.reasonMarkdown, step.resultFormula)
            },
        )
    }
}
