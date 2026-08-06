package com.tingyun.smartmistakebook.core.visual.ui

import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualDocumentCompiler
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualProvenanceReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualEvidenceEligibilityTest {
    @Test
    fun missingProvenanceNeverAuthorizesVisualAnswerEvidence() {
        assertTrue(
            visualEvidenceEligibleTargetIds(
                browsableTargetIds = setOf("object", "flow"),
                provenanceEligibleTargetIds = null,
            ).isEmpty(),
        )
    }

    @Test
    fun evidenceTargetsMustBeBothVisibleAndProvenanceEligible() {
        assertEquals(
            setOf("object"),
            visualEvidenceEligibleTargetIds(
                browsableTargetIds = setOf("object", "flow"),
                provenanceEligibleTargetIds = setOf("object", "hidden"),
            ),
        )
    }

    @Test
    fun contentEntryRequiresAProvenanceCheckedCompilation() {
        val structuralCompilation = TutorVisualDocumentCompiler.compile(scene())

        assertFalse(structuralCompilation.isVerifiedForPresentation())
        assertTrue(
            structuralCompilation.copy(
                provenance = TutorVisualProvenanceReport(
                    issues = emptyList(),
                    verifiedVariableIds = emptySet(),
                    evidenceEligibleElementIds = emptySet(),
                ),
            ).isVerifiedForPresentation(),
        )
    }

    private fun scene() = TutorVisualDocumentScene(
        sceneId = "verified_entry_scene",
        title = "关系",
        panels = listOf(TutorVisualPanel("diagram", TutorVisualPanelKind.DIAGRAM_2D)),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "object",
                panelId = "diagram",
                kind = TutorVisual2DNodeKind.CONTAINER,
                label = "对象",
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "观察关系",
                focusElementIds = listOf("object"),
            ),
        ),
        fallbackMarkdown = "按原题讲解",
        accessibilitySummary = "展示题目关系",
        provenanceSchemaVersion = TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
    )
}
