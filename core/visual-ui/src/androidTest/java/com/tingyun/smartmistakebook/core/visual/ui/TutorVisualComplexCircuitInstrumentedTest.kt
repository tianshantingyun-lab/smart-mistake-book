package com.tingyun.smartmistakebook.core.visual.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualChartAxis
import com.tingyun.smartmistakebook.core.model.TutorVisualChartConfiguration
import com.tingyun.smartmistakebook.core.model.TutorVisualChartPoint
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesElement
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesKind
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectionAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectorKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDimension
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualLayoutHint
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualSizeClass
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualValueSource
import com.tingyun.smartmistakebook.core.model.TutorVisualVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TutorVisualComplexCircuitInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun complexCircuitSemanticRedrawPassesDeviceAcceptanceAndSavesStepScreenshots() {
        val scene = complexCircuitScene()
        assertSemanticFixture(scene)

        composeRule.setContent {
            MaterialTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    TutorVisualDocumentContent(scene = scene)
                }
            }
        }

        composeRule.onNodeWithContentDescription(CIRCUIT_ACCESSIBILITY_SUMMARY).assertExists()
        composeRule.onNodeWithText("电路连接").assertExists()
        composeRule.onNodeWithText("1  核对连接").performScrollTo().assertExists()
        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-circuit_panel",
            useUnmergedTree = true,
        ).assertExists()
        captureQaScreenshot("complex-circuit-01-connection.png")

        composeRule.onNodeWithText("2  闭合开关").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("闭合开关").assertExists()
        composeRule.onNodeWithText("电流  0.3 A").assertExists()
        captureQaScreenshot("complex-circuit-02-switch-closed.png")

        composeRule.onNodeWithText("3  滑片减小").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("滑片减小").assertExists()
        composeRule.onNodeWithText("滑动变阻器阻值  10 Ω").assertExists()
        composeRule.onNodeWithText("灯泡电压  3 V").assertExists()
        captureQaScreenshot("complex-circuit-03-slider-decrease.png")

        composeRule.onNodeWithText("图像验证").performScrollTo().performClick()
        composeRule.onNodeWithText("4  图表验证").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(
            "tutor-visual-v2-panel-response_chart",
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNodeWithContentDescription("滑动变阻器阻值 Rs；电流 I；灯泡电压 UL")
            .assertExists()
        captureQaScreenshot("complex-circuit-04-response-chart.png")
    }

    private fun assertSemanticFixture(scene: TutorVisualDocumentScene) {
        val wires = scene.elements.filterIsInstance<TutorVisual2DConnectorElement>()
        val labels = scene.elements
            .filterIsInstance<TutorVisual2DNodeElement>()
            .mapNotNull(TutorVisual2DNodeElement::label)
        val series = scene.elements.filterIsInstance<TutorVisualChartSeriesElement>()

        assertEquals(9, wires.size)
        assertTrue(wires.all { it.kind == TutorVisualConnectorKind.WIRE })
        assertTrue(wires.all { it.from.portName != null && it.to.portName != null })
        assertTrue(
            labels.containsAll(
                listOf("电源 6 V", "开关 S", "电流表 A", "小灯泡 L", "滑动变阻器 Rs", "电压表 V"),
            ),
        )
        assertEquals(4, scene.steps.size)
        assertEquals(listOf("核对连接", "闭合开关", "滑片减小", "图表验证"), scene.steps.map { it.label })
        assertEquals(2, series.size)
        assertTrue(series.all { it.points.size == 7 })
    }

    private fun captureQaScreenshot(displayName: String) {
        require(displayName.matches(Regex("[a-z0-9-]+\\.png")))
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        resolver.delete(
            collection,
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(displayName, QA_SCREENSHOT_PATH),
        )
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, QA_SCREENSHOT_PATH)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            val saved = checkNotNull(resolver.openOutputStream(uri)).use { stream ->
                screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            check(saved) { "Expected $displayName to be saved" }
            check(
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1,
            ) { "Expected $displayName to be published" }
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        } finally {
            screenshot.recycle()
        }
    }

    private fun complexCircuitScene() = TutorVisualDocumentScene(
        sceneId = "complex_circuit_semantic_redraw",
        title = "滑动变阻器控制灯泡",
        panels = listOf(
            TutorVisualPanel(
                panelId = "circuit_panel",
                kind = TutorVisualPanelKind.DIAGRAM_2D,
                title = "电路连接",
                weight = 1.4,
            ),
            TutorVisualPanel(
                panelId = "response_chart",
                kind = TutorVisualPanelKind.SCIENTIFIC_CHART,
                title = "图像验证",
                chart = TutorVisualChartConfiguration(
                    xAxisLabel = "滑动变阻器阻值 Rs",
                    leftAxisLabel = "电流 I",
                    rightAxisLabel = "灯泡电压 UL",
                    showLegend = true,
                    allowTouchReadout = true,
                    allowZoom = true,
                ),
            ),
        ),
        variables = listOf(
            TutorVisualVariable(
                variableId = "supply_voltage",
                label = "电源电压",
                value = 6.0,
                unit = "V",
                dimension = TutorVisualDimension.VOLTAGE,
                source = TutorVisualValueSource.GIVEN,
            ),
            TutorVisualVariable(
                variableId = "lamp_resistance",
                label = "灯泡电阻",
                value = 10.0,
                unit = "Ω",
                dimension = TutorVisualDimension.RESISTANCE,
                source = TutorVisualValueSource.GIVEN,
            ),
            TutorVisualVariable(
                variableId = "slider_resistance",
                label = "滑动变阻器阻值",
                value = 10.0,
                unit = "Ω",
                dimension = TutorVisualDimension.RESISTANCE,
                source = TutorVisualValueSource.GIVEN,
            ),
            TutorVisualVariable(
                variableId = "circuit_current",
                label = "电流",
                value = 0.3,
                unit = "A",
                dimension = TutorVisualDimension.ELECTRIC_CURRENT,
                source = TutorVisualValueSource.DERIVED,
                derivationMarkdown = "I = U / (RL + Rs) = 6 V / (10 Ω + 10 Ω) = 0.3 A",
            ),
            TutorVisualVariable(
                variableId = "lamp_voltage",
                label = "灯泡电压",
                value = 3.0,
                unit = "V",
                dimension = TutorVisualDimension.VOLTAGE,
                source = TutorVisualValueSource.DERIVED,
                derivationMarkdown = "UL = I × RL = 0.3 A × 10 Ω = 3 V",
            ),
        ),
        elements = circuitNodes() + circuitWires() + responseSeries(),
        steps = listOf(
            TutorVisualStep(
                stepId = "verify_connections",
                label = "核对连接",
                focusElementIds = listOf(
                    "battery",
                    "switch",
                    "ammeter",
                    "lamp",
                    "variable_resistor",
                    "voltmeter",
                ),
                displayVariableIds = listOf("supply_voltage", "lamp_resistance"),
                primaryRelationElementId = "wire_battery_switch",
            ),
            TutorVisualStep(
                stepId = "close_switch",
                label = "闭合开关",
                focusElementIds = listOf(
                    "switch",
                    "wire_battery_switch",
                    "wire_switch_ammeter",
                    "wire_ammeter_high_junction",
                ),
                displayVariableIds = listOf("supply_voltage", "circuit_current"),
                primaryRelationElementId = "wire_switch_ammeter",
            ),
            TutorVisualStep(
                stepId = "decrease_slider_resistance",
                label = "滑片减小",
                focusElementIds = listOf(
                    "variable_resistor",
                    "wire_low_junction_resistor",
                    "wire_resistor_battery",
                ),
                displayVariableIds = listOf("slider_resistance", "circuit_current", "lamp_voltage"),
                primaryRelationElementId = "variable_resistor",
            ),
            TutorVisualStep(
                stepId = "verify_chart",
                label = "图表验证",
                focusElementIds = listOf("current_series", "lamp_voltage_series"),
                displayVariableIds = listOf("slider_resistance", "circuit_current", "lamp_voltage"),
                primaryRelationElementId = "current_series",
                highlightedSeriesIds = listOf("current_series", "lamp_voltage_series"),
            ),
        ),
        fallbackMarkdown = "按端口核对主回路，再确认电压表并联在小灯泡两端。",
        accessibilitySummary = CIRCUIT_ACCESSIBILITY_SUMMARY,
    )

    private fun circuitNodes() = listOf(
        circuitNode(
            id = "battery",
            kind = TutorVisual2DNodeKind.BATTERY,
            label = "电源 6 V",
            x = 0.12,
            y = 0.72,
            size = TutorVisualSizeClass.MEDIUM,
        ),
        circuitNode(
            id = "switch",
            kind = TutorVisual2DNodeKind.SWITCH,
            label = "开关 S",
            x = 0.25,
            y = 0.2,
            size = TutorVisualSizeClass.MEDIUM,
        ),
        circuitNode(
            id = "ammeter",
            kind = TutorVisual2DNodeKind.AMMETER,
            label = "电流表 A",
            x = 0.47,
            y = 0.2,
            size = TutorVisualSizeClass.MEDIUM,
        ),
        circuitNode(
            id = "high_junction",
            kind = TutorVisual2DNodeKind.JUNCTION,
            label = null,
            x = 0.68,
            y = 0.2,
            size = TutorVisualSizeClass.TINY,
        ),
        circuitNode(
            id = "lamp",
            kind = TutorVisual2DNodeKind.LAMP,
            label = "小灯泡 L",
            x = 0.68,
            y = 0.46,
            size = TutorVisualSizeClass.MEDIUM,
        ),
        circuitNode(
            id = "low_junction",
            kind = TutorVisual2DNodeKind.JUNCTION,
            label = null,
            x = 0.68,
            y = 0.72,
            size = TutorVisualSizeClass.TINY,
        ),
        circuitNode(
            id = "variable_resistor",
            kind = TutorVisual2DNodeKind.VARIABLE_RESISTOR,
            label = "滑动变阻器 Rs",
            x = 0.4,
            y = 0.72,
            size = TutorVisualSizeClass.WIDE,
            valueVariableId = "slider_resistance",
        ),
        circuitNode(
            id = "voltmeter",
            kind = TutorVisual2DNodeKind.VOLTMETER,
            label = "电压表 V",
            x = 0.88,
            y = 0.46,
            size = TutorVisualSizeClass.MEDIUM,
            valueVariableId = "lamp_voltage",
        ),
    )

    private fun circuitNode(
        id: String,
        kind: TutorVisual2DNodeKind,
        label: String?,
        x: Double,
        y: Double,
        size: TutorVisualSizeClass,
        valueVariableId: String? = null,
    ) = TutorVisual2DNodeElement(
        elementId = id,
        panelId = "circuit_panel",
        kind = kind,
        label = label,
        layout = TutorVisualLayoutHint(preferredX = x, preferredY = y),
        sizeClass = size,
        valueVariableId = valueVariableId,
    )

    private fun circuitWires() = listOf(
        wire(
            id = "wire_battery_switch",
            fromId = "battery",
            fromPort = "positive",
            fromSide = TutorVisualAnchor.TOP,
            toId = "switch",
            toPort = "input",
            toSide = TutorVisualAnchor.START,
        ),
        wire(
            id = "wire_switch_ammeter",
            fromId = "switch",
            fromPort = "output",
            fromSide = TutorVisualAnchor.END,
            toId = "ammeter",
            toPort = "input",
            toSide = TutorVisualAnchor.START,
        ),
        wire(
            id = "wire_ammeter_high_junction",
            fromId = "ammeter",
            fromPort = "output",
            fromSide = TutorVisualAnchor.END,
            toId = "high_junction",
            toPort = "main_in",
            toSide = TutorVisualAnchor.START,
        ),
        wire(
            id = "wire_high_junction_lamp",
            fromId = "high_junction",
            fromPort = "lamp_out",
            fromSide = TutorVisualAnchor.BOTTOM,
            toId = "lamp",
            toPort = "top",
            toSide = TutorVisualAnchor.TOP,
        ),
        wire(
            id = "wire_lamp_low_junction",
            fromId = "lamp",
            fromPort = "bottom",
            fromSide = TutorVisualAnchor.BOTTOM,
            toId = "low_junction",
            toPort = "lamp_in",
            toSide = TutorVisualAnchor.TOP,
        ),
        wire(
            id = "wire_low_junction_resistor",
            fromId = "low_junction",
            fromPort = "main_out",
            fromSide = TutorVisualAnchor.START,
            toId = "variable_resistor",
            toPort = "fixed_right",
            toSide = TutorVisualAnchor.END,
        ),
        wire(
            id = "wire_resistor_battery",
            fromId = "variable_resistor",
            fromPort = "slider_and_fixed_left",
            fromSide = TutorVisualAnchor.START,
            toId = "battery",
            toPort = "negative",
            toSide = TutorVisualAnchor.END,
        ),
        wire(
            id = "wire_high_junction_voltmeter",
            fromId = "high_junction",
            fromPort = "voltmeter_out",
            fromSide = TutorVisualAnchor.END,
            toId = "voltmeter",
            toPort = "positive",
            toSide = TutorVisualAnchor.TOP,
        ),
        wire(
            id = "wire_voltmeter_low_junction",
            fromId = "voltmeter",
            fromPort = "negative",
            fromSide = TutorVisualAnchor.BOTTOM,
            toId = "low_junction",
            toPort = "voltmeter_in",
            toSide = TutorVisualAnchor.END,
        ),
    )

    private fun wire(
        id: String,
        fromId: String,
        fromPort: String,
        fromSide: TutorVisualAnchor,
        toId: String,
        toPort: String,
        toSide: TutorVisualAnchor,
    ) = TutorVisual2DConnectorElement(
        elementId = id,
        panelId = "circuit_panel",
        kind = TutorVisualConnectorKind.WIRE,
        from = TutorVisualConnectionAnchor(
            elementId = fromId,
            portName = fromPort,
            side = fromSide,
        ),
        to = TutorVisualConnectionAnchor(
            elementId = toId,
            portName = toPort,
            side = toSide,
        ),
    )

    private fun responseSeries() = listOf(
        TutorVisualChartSeriesElement(
            elementId = "current_series",
            panelId = "response_chart",
            label = "电流 I",
            kind = TutorVisualChartSeriesKind.LINE,
            axis = TutorVisualChartAxis.LEFT,
            points = listOf(
                TutorVisualChartPoint(0.0, 0.6),
                TutorVisualChartPoint(2.0, 0.5),
                TutorVisualChartPoint(4.0, 0.4286),
                TutorVisualChartPoint(6.0, 0.375),
                TutorVisualChartPoint(8.0, 0.3333),
                TutorVisualChartPoint(10.0, 0.3),
                TutorVisualChartPoint(15.0, 0.24),
            ),
            source = TutorVisualValueSource.DERIVED,
        ),
        TutorVisualChartSeriesElement(
            elementId = "lamp_voltage_series",
            panelId = "response_chart",
            label = "灯泡电压 UL",
            kind = TutorVisualChartSeriesKind.LINE,
            axis = TutorVisualChartAxis.RIGHT,
            points = listOf(
                TutorVisualChartPoint(0.0, 6.0),
                TutorVisualChartPoint(2.0, 5.0),
                TutorVisualChartPoint(4.0, 4.286),
                TutorVisualChartPoint(6.0, 3.75),
                TutorVisualChartPoint(8.0, 3.333),
                TutorVisualChartPoint(10.0, 3.0),
                TutorVisualChartPoint(15.0, 2.4),
            ),
            source = TutorVisualValueSource.DERIVED,
        ),
    )

    private companion object {
        const val QA_SCREENSHOT_PATH = "Download/smart-mistake-book-visual-qa/"
        const val CIRCUIT_ACCESSIBILITY_SUMMARY =
            "主回路依次连接电源、开关、电流表、小灯泡和滑动变阻器；电压表并联在小灯泡两端。"
    }
}
