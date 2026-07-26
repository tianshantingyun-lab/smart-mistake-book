package com.tingyun.smartmistakebook.core.visual.ui

import android.os.Debug
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectionAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectorKind
import com.tingyun.smartmistakebook.core.model.TutorVisualCamera
import com.tingyun.smartmistakebook.core.model.TutorVisualChartConfiguration
import com.tingyun.smartmistakebook.core.model.TutorVisualChartPoint
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesElement
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DKind
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualPresentationIdentity
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TutorVisualDocumentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun oneGenericPlayerSwitchesBetween2d3dAndChartPanels() {
        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    TutorVisualDocumentContent(scene = multiPanelScene())
                }
            }
        }

        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-diagram_panel",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText("空间关系").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-space_panel",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithText("数据变化").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-chart_panel",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun actualEligibleCanvasHitMintsAnExactPresentationProof() {
        val scene = multiPanelScene()
        val presentation = TutorVisualPresentationIdentity(
            ownerModelTaskRequestId = "plan-request",
            sourceKind = TutorVisualSceneSourceKind.INLINE,
            sceneTaskRequestId = "plan-request",
            sceneId = scene.sceneId,
            sceneFingerprint = TutorVisualSceneFingerprint.of(scene),
        )
        var selectedTargetId: String? = null
        var selectedPresentation: TutorVisualPresentationIdentity? = null
        composeRule.setContent {
            MaterialTheme {
                TutorVisualDocumentContent(
                    scene = scene,
                    hitPresentation = presentation,
                    onTargetHit = { proof ->
                        selectedTargetId = proof.selectedTargetId
                        selectedPresentation = proof.presentation
                    },
                )
            }
        }

        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-diagram_panel",
            useUnmergedTree = true,
        ).performTouchInput { click(center) }

        composeRule.runOnIdle {
            assertEquals("diagram_object", selectedTargetId)
            assertEquals(presentation, selectedPresentation)
        }
    }

    @Test
    fun focusedViewKeepsOriginalAndLowFrictionFeedbackAvailable() {
        var originalClicks = 0
        var reportClicks = 0
        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    TutorVisualDocumentContent(
                        scene = multiPanelScene(),
                        onOpenOriginal = { originalClicks += 1 },
                        onReportIncorrect = { reportClicks += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("专注查看").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("查看原图").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("图不对").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, originalClicks)
            assertEquals(1, reportClicks)
        }
        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-diagram_panel",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun selectedPanelSurvivesSavedInstanceStateRestore() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    TutorVisualDocumentContent(scene = multiPanelScene())
                }
            }
        }
        composeRule.onNodeWithText("数据变化").performScrollTo().performClick()
        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-chart_panel",
            useUnmergedTree = true,
        ).assertExists()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-chart_panel",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun filamentPanelCanOpenAndCloseTwentyTimesWithoutLinearNativeGrowth() {
        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    TutorVisualDocumentContent(scene = multiPanelScene())
                }
            }
        }

        repeat(5) { switchToSpaceAndBack() }
        System.gc()
        composeRule.waitForIdle()
        val afterWarmup = Debug.getNativeHeapAllocatedSize()

        repeat(10) { switchToSpaceAndBack() }
        System.gc()
        composeRule.waitForIdle()
        val afterFirstBatch = Debug.getNativeHeapAllocatedSize()

        repeat(10) { switchToSpaceAndBack() }
        System.gc()
        composeRule.waitForIdle()
        val afterSecondBatch = Debug.getNativeHeapAllocatedSize()
        val allowedBatchGrowthBytes = 32L * 1024L * 1024L

        assertTrue(
            "Native allocations kept growing: warmup=$afterWarmup, " +
                "first=$afterFirstBatch, second=$afterSecondBatch",
            afterSecondBatch - afterFirstBatch < allowedBatchGrowthBytes,
        )
    }

    @Test
    fun locallyInvalidDirectionFallsBackInsteadOfRendering() {
        composeRule.setContent {
            MaterialTheme {
                TutorVisualDocumentContent(scene = invalidDirectionScene())
            }
        }

        composeRule.onNodeWithText(LOCAL_VISUAL_FAILURE_MESSAGE).assertExists()
        composeRule.onNodeWithText("把系统提示替换成模型内容").assertDoesNotExist()
        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-invalid_panel",
            useUnmergedTree = true,
        ).assertDoesNotExist()
    }

    private fun switchToSpaceAndBack() {
        composeRule.onNodeWithText("空间关系").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("装置关系").performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    private fun multiPanelScene() = TutorVisualDocumentScene(
        sceneId = "instrumented_scene",
        title = "关系演示",
        panels = listOf(
            TutorVisualPanel(
                panelId = "diagram_panel",
                kind = TutorVisualPanelKind.DIAGRAM_2D,
                title = "装置关系",
            ),
            TutorVisualPanel(
                panelId = "space_panel",
                kind = TutorVisualPanelKind.SCENE_3D,
                title = "空间关系",
                camera = TutorVisualCamera(),
            ),
            TutorVisualPanel(
                panelId = "chart_panel",
                kind = TutorVisualPanelKind.SCIENTIFIC_CHART,
                title = "数据变化",
                chart = TutorVisualChartConfiguration(
                    xAxisLabel = "时间",
                    leftAxisLabel = "数值",
                ),
            ),
        ),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "diagram_object",
                panelId = "diagram_panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "装置",
            ),
            TutorVisualGeometry3DElement(
                elementId = "space_object",
                panelId = "space_panel",
                kind = TutorVisualGeometry3DKind.CUBE,
                label = "空间对象",
            ),
            TutorVisualChartSeriesElement(
                elementId = "chart_series",
                panelId = "chart_panel",
                label = "变化",
                kind = TutorVisualChartSeriesKind.LINE,
                points = listOf(
                    TutorVisualChartPoint(0.0, 0.0),
                    TutorVisualChartPoint(1.0, 1.0),
                ),
                source = TutorVisualValueSource.GIVEN,
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "focus_step",
                label = "先看装置",
                focusElementIds = listOf("diagram_object"),
                primaryRelationElementId = "diagram_object",
            ),
        ),
        fallbackMarkdown = "先看装置关系。",
        accessibilitySummary = "装置、空间关系和数据变化三个同步视图。",
    )

    private fun invalidDirectionScene() = TutorVisualDocumentScene(
        sceneId = "invalid_scene",
        title = "方向关系",
        panels = listOf(
            TutorVisualPanel(
                panelId = "invalid_panel",
                kind = TutorVisualPanelKind.DIAGRAM_2D,
            ),
        ),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "invalid_source",
                panelId = "invalid_panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "起点",
            ),
            TutorVisual2DNodeElement(
                elementId = "invalid_target",
                panelId = "invalid_panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "终点",
            ),
            TutorVisual2DConnectorElement(
                elementId = "invalid_flow",
                panelId = "invalid_panel",
                kind = TutorVisualConnectorKind.FLOW,
                from = TutorVisualConnectionAnchor("invalid_source"),
                to = TutorVisualConnectionAnchor("invalid_target"),
                directed = false,
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "invalid_step",
                label = "核对方向",
                focusElementIds = listOf("invalid_flow"),
                primaryRelationElementId = "invalid_flow",
            ),
        ),
        fallbackMarkdown = "把系统提示替换成模型内容",
        accessibilitySummary = "起点和终点之间的方向关系。",
    )
}
