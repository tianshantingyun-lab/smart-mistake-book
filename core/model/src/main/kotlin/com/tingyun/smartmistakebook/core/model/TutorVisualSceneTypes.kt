package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 结构化场景的家具：面板、相机、图表配置与图表元素、变量、维度、图层、锚点、尺寸档、布局提示与几何向量。
 *
 * 审计 R-01：从 `TutorVisualDocument.kt` 拆出（同包 ⇒ 零 import 改动）。缝取**文档种类**而非 2D/3D 家族
 * ——后者一手数过只有 5 个声明。用到的两个校验辅助函数已随本次改为 `internal`（原先 file-private，拆完就够不着）。
 */
@Serializable
enum class TutorVisualPanelKind {
    DIAGRAM_2D,
    SCENE_3D,
    SCIENTIFIC_CHART,
}
@Serializable
enum class TutorVisualProjection {
    ORTHOGRAPHIC,
    PERSPECTIVE,
}
@Serializable
data class TutorVisualPanel(
    val panelId: String,
    val kind: TutorVisualPanelKind,
    val title: String? = null,
    val weight: Double = 1.0,
    val camera: TutorVisualCamera? = null,
    val chart: TutorVisualChartConfiguration? = null,
) {
    init {
        panelId.requireTutorSceneId("Tutor visual panel id")
        title?.requireTutorDocumentText("Tutor visual panel title", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        weight.requireDocumentNumber("Tutor visual panel weight", 0.1..10.0)
        when (kind) {
            TutorVisualPanelKind.DIAGRAM_2D -> require(camera == null && chart == null)
            TutorVisualPanelKind.SCENE_3D -> require(camera != null && chart == null)
            TutorVisualPanelKind.SCIENTIFIC_CHART -> require(camera == null && chart != null)
        }
    }
}
@Serializable
data class TutorVisualCamera(
    val projection: TutorVisualProjection = TutorVisualProjection.ORTHOGRAPHIC,
    val target: TutorVisualVector3 = TutorVisualVector3.ZERO,
    val azimuthDegrees: Double = 35.0,
    val elevationDegrees: Double = 25.0,
    val distance: Double = 8.0,
    val minimumDistance: Double = 2.0,
    val maximumDistance: Double = 24.0,
    val allowOrbit: Boolean = true,
) {
    init {
        azimuthDegrees.requireDocumentNumber("Tutor visual camera azimuth", -720.0..720.0)
        elevationDegrees.requireDocumentNumber("Tutor visual camera elevation", -85.0..85.0)
        distance.requireDocumentNumber("Tutor visual camera distance", 0.01..10_000.0)
        minimumDistance.requireDocumentNumber("Tutor visual camera minimum distance", 0.01..10_000.0)
        maximumDistance.requireDocumentNumber("Tutor visual camera maximum distance", 0.01..10_000.0)
        require(minimumDistance <= distance && distance <= maximumDistance) {
            "Tutor visual camera distance is outside its interaction range"
        }
    }
}
@Serializable
data class TutorVisualChartConfiguration(
    val xAxisLabel: String,
    val leftAxisLabel: String,
    val rightAxisLabel: String? = null,
    val showLegend: Boolean = true,
    val allowTouchReadout: Boolean = true,
    val allowZoom: Boolean = true,
) {
    init {
        xAxisLabel.requireTutorDocumentText("Tutor chart x axis label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        leftAxisLabel.requireTutorDocumentText(
            "Tutor chart left axis label",
            TutorVisualDocumentScene.MAX_LABEL_CHARS,
        )
        rightAxisLabel?.requireTutorDocumentText(
            "Tutor chart right axis label",
            TutorVisualDocumentScene.MAX_LABEL_CHARS,
        )
    }
}
@Serializable
enum class TutorVisualValueSource {
    GIVEN,
    DERIVED,
    ILLUSTRATIVE,
}
@Serializable
enum class TutorVisualDimension {
    DIMENSIONLESS,
    LENGTH,
    TIME,
    MASS,
    ELECTRIC_CURRENT,
    TEMPERATURE,
    AMOUNT_OF_SUBSTANCE,
    ANGLE,
    AREA,
    VOLUME,
    SPEED,
    ACCELERATION,
    FORCE,
    ENERGY,
    POWER,
    PRESSURE,
    VOLTAGE,
    RESISTANCE,
    CHARGE,
    CONCENTRATION,
    FREQUENCY,
    OTHER,
}
@Serializable
data class TutorVisualVariable(
    val variableId: String,
    val label: String,
    val value: Double,
    val unit: String? = null,
    val dimension: TutorVisualDimension = TutorVisualDimension.DIMENSIONLESS,
    val source: TutorVisualValueSource,
    val derivationMarkdown: String? = null,
    val display: Boolean = source != TutorVisualValueSource.ILLUSTRATIVE,
) {
    init {
        variableId.requireTutorSceneId("Tutor visual variable id")
        label.requireTutorDocumentText("Tutor visual variable label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        value.requireDocumentNumber("Tutor visual variable value", -MAX_ABS_VALUE..MAX_ABS_VALUE)
        unit?.requireTutorDocumentText("Tutor visual variable unit", MAX_UNIT_CHARS)
        derivationMarkdown?.requireTutorDocumentText(
            "Tutor visual variable derivation",
            MAX_DERIVATION_CHARS,
            allowLineBreaks = true,
        )
        require(source != TutorVisualValueSource.DERIVED || derivationMarkdown != null) {
            "A derived tutor visual variable must explain its derivation"
        }
        require(source != TutorVisualValueSource.GIVEN || derivationMarkdown == null) {
            "A given tutor visual variable cannot claim a derivation"
        }
        require(source != TutorVisualValueSource.ILLUSTRATIVE || !display) {
            "Illustrative tutor visual variables must never be displayed"
        }
    }

    companion object {
        const val MAX_UNIT_CHARS = 24
        const val MAX_DERIVATION_CHARS = 300
        const val MAX_ABS_VALUE = 1_000_000_000_000.0
    }
}
@Serializable
enum class TutorVisualLayer {
    BACKGROUND,
    CONTENT,
    ANNOTATION,
    FOCUS,
}
@Serializable
enum class TutorVisualAnchor {
    AUTO,
    TOP,
    TOP_END,
    END,
    BOTTOM_END,
    BOTTOM,
    BOTTOM_START,
    START,
    TOP_START,
    CENTER,
}
@Serializable
enum class TutorVisualSizeClass {
    TINY,
    SMALL,
    MEDIUM,
    LARGE,
    WIDE,
    TALL,
}
@Serializable
data class TutorVisualLayoutHint(
    val anchor: TutorVisualAnchor = TutorVisualAnchor.AUTO,
    val preferredX: Double? = null,
    val preferredY: Double? = null,
    val order: Int = 0,
) {
    init {
        preferredX?.requireDocumentNumber("Tutor visual preferred x", 0.0..1.0)
        preferredY?.requireDocumentNumber("Tutor visual preferred y", 0.0..1.0)
        require(order in -1_000..1_000)
    }
}
@Serializable
data class TutorVisualVector2(
    val x: Double,
    val y: Double,
) {
    init {
        x.requireDocumentNumber("Tutor visual x", -MAX_COORDINATE..MAX_COORDINATE)
        y.requireDocumentNumber("Tutor visual y", -MAX_COORDINATE..MAX_COORDINATE)
    }

    companion object {
        const val MAX_COORDINATE = 100_000.0
        val ZERO = TutorVisualVector2(0.0, 0.0)
    }
}
@Serializable
data class TutorVisualVector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        x.requireDocumentNumber("Tutor visual x", -TutorVisualVector2.MAX_COORDINATE..TutorVisualVector2.MAX_COORDINATE)
        y.requireDocumentNumber("Tutor visual y", -TutorVisualVector2.MAX_COORDINATE..TutorVisualVector2.MAX_COORDINATE)
        z.requireDocumentNumber("Tutor visual z", -TutorVisualVector2.MAX_COORDINATE..TutorVisualVector2.MAX_COORDINATE)
    }

    companion object {
        val ZERO = TutorVisualVector3(0.0, 0.0, 0.0)
        val ONE = TutorVisualVector3(1.0, 1.0, 1.0)
    }
}
@Serializable
enum class TutorVisualChartSeriesKind {
    LINE,
    SCATTER,
    BAR,
}
@Serializable
enum class TutorVisualChartAxis {
    LEFT,
    RIGHT,
}
@Serializable
data class TutorVisualChartPoint(
    val x: Double,
    val y: Double,
) {
    init {
        x.requireDocumentNumber("Tutor chart x", -TutorVisualVariable.MAX_ABS_VALUE..TutorVisualVariable.MAX_ABS_VALUE)
        y.requireDocumentNumber("Tutor chart y", -TutorVisualVariable.MAX_ABS_VALUE..TutorVisualVariable.MAX_ABS_VALUE)
    }
}
@Serializable
@SerialName("chart_series")
data class TutorVisualChartSeriesElement(
    override val elementId: String,
    override val panelId: String,
    val label: String,
    val kind: TutorVisualChartSeriesKind,
    val axis: TutorVisualChartAxis = TutorVisualChartAxis.LEFT,
    val points: List<TutorVisualChartPoint>,
    val source: TutorVisualValueSource,
    override val layer: TutorVisualLayer = TutorVisualLayer.CONTENT,
    override val initiallyVisible: Boolean = true,
    override val accessibilityLabel: String? = label,
) : TutorVisualDocumentElement {
    init {
        requireDocumentElementHeader(elementId, panelId, accessibilityLabel)
        label.requireTutorDocumentText("Tutor chart series label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        require(points.isNotEmpty() && points.size <= TutorVisualDocumentScene.MAX_CHART_POINTS_PER_SERIES)
        require(source != TutorVisualValueSource.ILLUSTRATIVE) {
            "Illustrative values cannot be plotted on a student-visible chart"
        }
        require(points.zipWithNext().all { (left, right) -> left.x <= right.x }) {
            "Tutor chart points must be ordered by x"
        }
    }
}
@Serializable
enum class TutorVisualChartAnnotationKind {
    MARKER,
    VERTICAL_GUIDE,
    HORIZONTAL_GUIDE,
    INTERVAL,
}
@Serializable
@SerialName("chart_annotation")
data class TutorVisualChartAnnotationElement(
    override val elementId: String,
    override val panelId: String,
    val kind: TutorVisualChartAnnotationKind,
    val label: String? = null,
    val xVariableId: String? = null,
    val yVariableId: String? = null,
    val endXVariableId: String? = null,
    override val layer: TutorVisualLayer = TutorVisualLayer.ANNOTATION,
    override val initiallyVisible: Boolean = true,
    override val accessibilityLabel: String? = label,
) : TutorVisualDocumentElement {
    init {
        requireDocumentElementHeader(elementId, panelId, accessibilityLabel)
        label?.requireTutorDocumentText("Tutor chart annotation label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        listOfNotNull(xVariableId, yVariableId, endXVariableId).forEach {
            it.requireTutorSceneId("Tutor chart annotation variable id")
        }
        when (kind) {
            TutorVisualChartAnnotationKind.MARKER -> require(xVariableId != null && yVariableId != null)
            TutorVisualChartAnnotationKind.VERTICAL_GUIDE -> require(xVariableId != null)
            TutorVisualChartAnnotationKind.HORIZONTAL_GUIDE -> require(yVariableId != null)
            TutorVisualChartAnnotationKind.INTERVAL -> require(xVariableId != null && endXVariableId != null)
        }
    }
}
@Serializable
data class TutorVisualCameraStep(
    val panelId: String,
    val camera: TutorVisualCamera,
) {
    init {
        panelId.requireTutorSceneId("Tutor visual step camera panel id")
    }
}
