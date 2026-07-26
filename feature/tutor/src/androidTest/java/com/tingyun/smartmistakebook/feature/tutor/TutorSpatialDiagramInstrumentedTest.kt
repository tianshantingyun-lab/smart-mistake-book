package com.tingyun.smartmistakebook.feature.tutor

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.TutorDiagramAnchor
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdge
import com.tingyun.smartmistakebook.core.model.TutorDiagramEdgeStyle
import com.tingyun.smartmistakebook.core.model.TutorDiagramNode
import com.tingyun.smartmistakebook.core.model.TutorDiagramNodeShape
import com.tingyun.smartmistakebook.core.model.TutorSpatialDiagramScene
import com.tingyun.smartmistakebook.core.ui.RootPageColumn
import com.tingyun.smartmistakebook.core.ui.TutorVisualSceneRenderer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorSpatialDiagramInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun physicsGeometryChemistryAndCircuitDiagramsUseOneBoundedLocalRenderer() {
        val scene = mutableStateOf(physicsScene())
        composeRule.setContent {
            MaterialTheme {
                RootPageColumn {
                    TutorVisualSceneRenderer(scene.value)
                }
            }
        }

        composeRule.onNodeWithText("木块受到哪些力").assertExists()
        composeRule.onNodeWithTag("tutor-spatial-node-physics-object").assertExists()
        composeRule.onNodeWithTag("tutor-spatial-edge-label-physics-friction").assertExists()
        composeRule.onNodeWithContentDescription("图中中央：木块").assertExists()
        capture("tutor-spatial-physics-current.png")

        composeRule.runOnIdle { scene.value = geometryScene() }
        composeRule.onNodeWithText("三角形中的边和顶点").assertExists()
        composeRule.onNodeWithTag("tutor-spatial-node-geometry-a").assertExists()
        composeRule.onNodeWithTag("tutor-spatial-edge-label-geometry-bc").assertExists()
        capture("tutor-spatial-geometry-current.png")

        composeRule.runOnIdle { scene.value = chemistryScene() }
        composeRule.onNodeWithText("甲烷的连接关系").assertExists()
        composeRule.onNodeWithTag("tutor-spatial-node-chemistry-carbon").assertExists()
        composeRule.onNodeWithContentDescription("图中中央：C").assertExists()
        capture("tutor-spatial-chemistry-current.png")

        composeRule.runOnIdle { scene.value = circuitScene() }
        composeRule.onNodeWithText("串联电路中的元件").assertExists()
        composeRule.onNodeWithText("电路图").assertDoesNotExist()
        composeRule.onNodeWithTag("tutor-spatial-node-circuit-resistor").assertExists()
        composeRule.onNodeWithContentDescription("图中上方：电阻").assertExists()
        capture("tutor-spatial-circuit-current.png")
    }

    @Test
    fun legacyVisualUsesFocusedViewAndClosesItBeforeOpeningOriginal() {
        var originalOpened = false
        composeRule.setContent {
            MaterialTheme {
                TutorVisualSceneRenderer(
                    scene = physicsScene(),
                    onOpenOriginal = { originalOpened = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("专注查看").performClick()
        composeRule.onNodeWithContentDescription("返回").assertExists()
        composeRule.onNodeWithContentDescription("查看原图").performClick()
        composeRule.onNodeWithContentDescription("返回").assertDoesNotExist()
        composeRule.runOnIdle { assertTrue(originalOpened) }
    }

    private fun physicsScene() = TutorSpatialDiagramScene(
        sceneId = "physics",
        title = "木块受到哪些力",
        nodes = listOf(
            node("physics-object", "木块", TutorDiagramAnchor.CENTER, TutorDiagramNodeShape.BLOCK),
            node("physics-normal", "N", TutorDiagramAnchor.TOP, TutorDiagramNodeShape.POINT),
            node("physics-gravity", "G", TutorDiagramAnchor.BOTTOM, TutorDiagramNodeShape.POINT),
            node("physics-pull", "F", TutorDiagramAnchor.RIGHT, TutorDiagramNodeShape.POINT),
            node("physics-friction-node", "f", TutorDiagramAnchor.LEFT, TutorDiagramNodeShape.POINT),
        ),
        edges = listOf(
            arrow("physics-normal-edge", "physics-object", "physics-normal", "支持力"),
            arrow("physics-gravity-edge", "physics-object", "physics-gravity", "重力"),
            arrow("physics-pull-edge", "physics-object", "physics-pull", "拉力"),
            arrow("physics-friction", "physics-object", "physics-friction-node", "摩擦力"),
        ),
        captionMarkdown = "所有箭头都从当前研究的木块出发，方向对应力的方向。",
    )

    private fun geometryScene() = TutorSpatialDiagramScene(
        sceneId = "geometry",
        title = "三角形中的边和顶点",
        nodes = listOf(
            node("geometry-a", "A", TutorDiagramAnchor.TOP, TutorDiagramNodeShape.POINT),
            node("geometry-b", "B", TutorDiagramAnchor.BOTTOM_LEFT, TutorDiagramNodeShape.POINT),
            node("geometry-c", "C", TutorDiagramAnchor.BOTTOM_RIGHT, TutorDiagramNodeShape.POINT),
        ),
        edges = listOf(
            line("geometry-ab", "geometry-a", "geometry-b", "c"),
            line("geometry-ac", "geometry-a", "geometry-c", "b"),
            line("geometry-bc", "geometry-b", "geometry-c", "a"),
        ),
        captionMarkdown = "边 a 与顶点 A 相对，另外两条边同理。",
    )

    private fun chemistryScene() = TutorSpatialDiagramScene(
        sceneId = "chemistry",
        title = "甲烷的连接关系",
        nodes = listOf(
            node("chemistry-carbon", "C", TutorDiagramAnchor.CENTER, TutorDiagramNodeShape.CIRCLE),
            node("chemistry-h-top", "H", TutorDiagramAnchor.TOP, TutorDiagramNodeShape.CIRCLE),
            node("chemistry-h-left", "H", TutorDiagramAnchor.LEFT, TutorDiagramNodeShape.CIRCLE),
            node("chemistry-h-right", "H", TutorDiagramAnchor.RIGHT, TutorDiagramNodeShape.CIRCLE),
            node("chemistry-h-bottom", "H", TutorDiagramAnchor.BOTTOM, TutorDiagramNodeShape.CIRCLE),
        ),
        edges = listOf(
            line("chemistry-bond-top", "chemistry-carbon", "chemistry-h-top"),
            line("chemistry-bond-left", "chemistry-carbon", "chemistry-h-left"),
            line("chemistry-bond-right", "chemistry-carbon", "chemistry-h-right"),
            line("chemistry-bond-bottom", "chemistry-carbon", "chemistry-h-bottom"),
        ),
        captionMarkdown = "这里只表达一个碳原子分别与四个氢原子相连。",
    )

    private fun circuitScene() = TutorSpatialDiagramScene(
        sceneId = "circuit",
        title = "串联电路中的元件",
        nodes = listOf(
            node(
                "circuit-top-left",
                "接点",
                TutorDiagramAnchor.TOP_LEFT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            node(
                "circuit-resistor",
                "电阻",
                TutorDiagramAnchor.TOP,
                TutorDiagramNodeShape.RESISTOR,
            ),
            node(
                "circuit-top-right",
                "接点",
                TutorDiagramAnchor.TOP_RIGHT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            node(
                "circuit-lamp",
                "灯泡",
                TutorDiagramAnchor.RIGHT,
                TutorDiagramNodeShape.LAMP,
            ),
            node(
                "circuit-bottom-right",
                "接点",
                TutorDiagramAnchor.BOTTOM_RIGHT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            node(
                "circuit-battery",
                "电源",
                TutorDiagramAnchor.BOTTOM,
                TutorDiagramNodeShape.BATTERY,
            ),
            node(
                "circuit-bottom-left",
                "接点",
                TutorDiagramAnchor.BOTTOM_LEFT,
                TutorDiagramNodeShape.JUNCTION,
            ),
            node(
                "circuit-switch",
                "开关",
                TutorDiagramAnchor.LEFT,
                TutorDiagramNodeShape.SWITCH_OPEN,
            ),
        ),
        edges = listOf(
            line("circuit-wire-1", "circuit-top-left", "circuit-resistor"),
            line("circuit-wire-2", "circuit-resistor", "circuit-top-right"),
            line("circuit-wire-3", "circuit-top-right", "circuit-lamp"),
            line("circuit-wire-4", "circuit-lamp", "circuit-bottom-right"),
            line("circuit-wire-5", "circuit-bottom-right", "circuit-battery"),
            line("circuit-wire-6", "circuit-battery", "circuit-bottom-left"),
            line("circuit-wire-7", "circuit-bottom-left", "circuit-switch"),
            line("circuit-wire-8", "circuit-switch", "circuit-top-left"),
        ),
        captionMarkdown = "开关目前断开，闭合后电流流过电阻和灯泡。",
    )

    private fun node(
        id: String,
        label: String,
        anchor: TutorDiagramAnchor,
        shape: TutorDiagramNodeShape,
    ) = TutorDiagramNode(id, label, anchor, shape)

    private fun arrow(
        id: String,
        from: String,
        to: String,
        label: String,
    ) = TutorDiagramEdge(id, from, to, label, TutorDiagramEdgeStyle.ARROW)

    private fun line(
        id: String,
        from: String,
        to: String,
        label: String? = null,
    ) = TutorDiagramEdge(id, from, to, label, TutorDiagramEdgeStyle.LINE)

    private fun capture(displayName: String) {
        val resolver = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SmartMistakeBookQA/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        checkNotNull(resolver.openOutputStream(uri)).use { stream ->
            check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream))
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }
}
