package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The v2 visual contract for complex figures.
 *
 * This is a bounded semantic document, not a drawing or scripting format. The model can select
 * local primitives and provide relationships; layout, styling, hit testing, playback, rendering,
 * and all persistence decisions remain local.
 */
@Serializable
@SerialName("visual_document")
data class TutorVisualDocumentScene(
    override val sceneId: String,
    override val title: String,
    val panels: List<TutorVisualPanel>,
    val variables: List<TutorVisualVariable> = emptyList(),
    val elements: List<TutorVisualDocumentElement>,
    val bindings: List<TutorVisualBinding> = emptyList(),
    val steps: List<TutorVisualStep>,
    val durationSeconds: Double = 0.0,
    val fallbackMarkdown: String,
    val accessibilitySummary: String,
    override val schemaVersion: Int = TutorVisualScene.DOCUMENT_SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        TutorVisualDocumentValidator.requireValid(this)
    }

    companion object {
        const val MAX_PANELS = 3
        const val MAX_LOGICAL_ELEMENTS = 240
        const val MAX_VARIABLES = 64
        const val MAX_STEPS = 16
        const val MAX_DURATION_SECONDS = 120.0
        const val MAX_CHART_SERIES = 8
        const val MAX_CHART_POINTS_PER_SERIES = 512
        const val MAX_EXPRESSION_DEPTH = 8
        const val MAX_EXPRESSION_NODES = 512
        const val MAX_RUNTIME_INSTANCES = 1_500
        const val MAX_LABEL_CHARS = 64
        const val MAX_ACCESSIBILITY_CHARS = 1_200
        const val MAX_FALLBACK_CHARS = 12_000
        const val MAX_TOTAL_TEXT_CHARS = 18_000
        const val MAX_POLYLINE_POINTS = 128
        const val MAX_LATTICE_BASIS_SITES = 64
    }
}
@Serializable
enum class TutorVisual2DNodeKind {
    POINT,
    CIRCLE,
    RECTANGLE,
    ROUNDED_RECTANGLE,
    POLYGON,
    BEZIER,
    FILLED_REGION,
    CROSS_SECTION,
    CONTAINER,
    REGION,
    MEMBRANE,
    PORT,
    PUMP,
    RESERVOIR,
    ELECTRODE,
    PISTON,
    LIQUID_LEVEL,
    AXES,
    BATTERY,
    SWITCH,
    RESISTOR,
    LENS,
    MIRROR,
    WAVE,
    BIOLOGICAL_STRUCTURE,
    GEOGRAPHIC_LAYER,
    MATERIAL_NODE,
}
@Serializable
sealed interface TutorVisualDocumentElement {
    val elementId: String
    val panelId: String
    val layer: TutorVisualLayer
    val initiallyVisible: Boolean
    val accessibilityLabel: String?
}
@Serializable
@SerialName("node_2d")
data class TutorVisual2DNodeElement(
    override val elementId: String,
    override val panelId: String,
    val kind: TutorVisual2DNodeKind,
    val label: String? = null,
    val layout: TutorVisualLayoutHint = TutorVisualLayoutHint(),
    val sizeClass: TutorVisualSizeClass = TutorVisualSizeClass.MEDIUM,
    val localPoints: List<TutorVisualVector2> = emptyList(),
    val valueVariableId: String? = null,
    override val layer: TutorVisualLayer = TutorVisualLayer.CONTENT,
    override val initiallyVisible: Boolean = true,
    override val accessibilityLabel: String? = label,
) : TutorVisualDocumentElement {
    init {
        requireDocumentElementHeader(elementId, panelId, accessibilityLabel)
        label?.requireTutorDocumentText("Tutor visual node label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        require(localPoints.size <= TutorVisualDocumentScene.MAX_POLYLINE_POINTS)
        valueVariableId?.requireTutorSceneId("Tutor visual node value variable id")
    }
}
@Serializable
data class TutorVisualConnectionAnchor(
    val elementId: String,
    val portName: String? = null,
    val side: TutorVisualAnchor = TutorVisualAnchor.AUTO,
) {
    init {
        elementId.requireTutorSceneId("Tutor visual connection element id")
        portName?.requireTutorDocumentText("Tutor visual port name", 24)
    }
}
@Serializable
enum class TutorVisualConnectorKind {
    LINE,
    WIRE,
    PIPE,
    FLOW,
    FIELD_LINE,
    VECTOR,
    DIMENSION,
    ANGLE,
    LEADER,
    RAY,
    FORCE,
}
@Serializable
enum class TutorVisualRouteKind {
    AUTO_ORTHOGONAL,
    DIRECT,
    POLYLINE,
    BEZIER,
}
@Serializable
@SerialName("connector_2d")
data class TutorVisual2DConnectorElement(
    override val elementId: String,
    override val panelId: String,
    val kind: TutorVisualConnectorKind,
    val from: TutorVisualConnectionAnchor,
    val to: TutorVisualConnectionAnchor,
    val route: TutorVisualRouteKind = TutorVisualRouteKind.AUTO_ORTHOGONAL,
    val controlPoints: List<TutorVisualVector2> = emptyList(),
    val label: String? = null,
    val valueVariableId: String? = null,
    val directed: Boolean = kind in DIRECTED_CONNECTORS,
    override val layer: TutorVisualLayer = TutorVisualLayer.CONTENT,
    override val initiallyVisible: Boolean = true,
    override val accessibilityLabel: String? = label,
) : TutorVisualDocumentElement {
    init {
        requireDocumentElementHeader(elementId, panelId, accessibilityLabel)
        require(from.elementId != to.elementId || kind == TutorVisualConnectorKind.ANGLE) {
            "A tutor visual connector must connect distinct elements"
        }
        require(controlPoints.size <= TutorVisualDocumentScene.MAX_POLYLINE_POINTS)
        label?.requireTutorDocumentText("Tutor visual connector label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        valueVariableId?.requireTutorSceneId("Tutor visual connector value variable id")
        require(route in setOf(TutorVisualRouteKind.POLYLINE, TutorVisualRouteKind.BEZIER) || controlPoints.isEmpty()) {
            "Only polyline and Bezier routes accept control points"
        }
    }

    companion object {
        private val DIRECTED_CONNECTORS = setOf(
            TutorVisualConnectorKind.FLOW,
            TutorVisualConnectorKind.VECTOR,
            TutorVisualConnectorKind.RAY,
            TutorVisualConnectorKind.FORCE,
        )
    }
}
@Serializable
enum class TutorVisualParticleMotion {
    STATIC,
    RANDOM_DRIFT,
    FOLLOW_PATH,
}
@Serializable
@SerialName("particle_group_2d")
data class TutorVisualParticleGroupElement(
    override val elementId: String,
    override val panelId: String,
    val regionElementId: String,
    val label: String? = null,
    val instanceCount: Int,
    val motion: TutorVisualParticleMotion = TutorVisualParticleMotion.STATIC,
    val pathElementId: String? = null,
    val deterministicSeed: Int = 0,
    override val layer: TutorVisualLayer = TutorVisualLayer.CONTENT,
    override val initiallyVisible: Boolean = true,
    override val accessibilityLabel: String? = label,
) : TutorVisualDocumentElement {
    init {
        requireDocumentElementHeader(elementId, panelId, accessibilityLabel)
        regionElementId.requireTutorSceneId("Tutor visual particle region id")
        pathElementId?.requireTutorSceneId("Tutor visual particle path id")
        label?.requireTutorDocumentText("Tutor visual particle label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        require(instanceCount in 1..TutorVisualDocumentScene.MAX_RUNTIME_INSTANCES)
        require(motion == TutorVisualParticleMotion.FOLLOW_PATH || pathElementId == null)
        require(motion != TutorVisualParticleMotion.FOLLOW_PATH || pathElementId != null)
    }
}
@Serializable
enum class TutorVisualGeometry3DKind {
    SPHERE,
    CYLINDER,
    CUBE,
    PLANE,
    LINE_SEGMENT,
    POLYLINE,
    GRID,
    GROUP,
    AXES,
}
@Serializable
data class TutorVisualTransform3D(
    val translation: TutorVisualVector3 = TutorVisualVector3.ZERO,
    val rotationDegrees: TutorVisualVector3 = TutorVisualVector3.ZERO,
    val scale: TutorVisualVector3 = TutorVisualVector3.ONE,
) {
    init {
        require(scale.x > 0.0 && scale.y > 0.0 && scale.z > 0.0) {
            "Tutor visual 3D scale must be positive"
        }
    }
}
@Serializable
@SerialName("geometry_3d")
data class TutorVisualGeometry3DElement(
    override val elementId: String,
    override val panelId: String,
    val kind: TutorVisualGeometry3DKind,
    val label: String? = null,
    val transform: TutorVisualTransform3D = TutorVisualTransform3D(),
    val points: List<TutorVisualVector3> = emptyList(),
    val parentElementId: String? = null,
    val instanceTransforms: List<TutorVisualTransform3D> = emptyList(),
    override val layer: TutorVisualLayer = TutorVisualLayer.CONTENT,
    override val initiallyVisible: Boolean = true,
    override val accessibilityLabel: String? = label,
) : TutorVisualDocumentElement {
    init {
        requireDocumentElementHeader(elementId, panelId, accessibilityLabel)
        label?.requireTutorDocumentText("Tutor visual 3D label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        parentElementId?.requireTutorSceneId("Tutor visual 3D parent id")
        require(points.size <= TutorVisualDocumentScene.MAX_POLYLINE_POINTS)
        require(instanceTransforms.size <= TutorVisualDocumentScene.MAX_RUNTIME_INSTANCES)
        require(kind in setOf(TutorVisualGeometry3DKind.POLYLINE, TutorVisualGeometry3DKind.LINE_SEGMENT) || points.isEmpty()) {
            "Only 3D lines accept explicit points"
        }
    }
}
@Serializable
data class TutorVisualLatticeBasisSite(
    val fractionalCoordinate: TutorVisualVector3,
    val label: String,
    val radiusScale: Double = 1.0,
) {
    init {
        label.requireTutorDocumentText("Tutor lattice site label", 24)
        radiusScale.requireDocumentNumber("Tutor lattice site radius", 0.05..5.0)
        require(
            fractionalCoordinate.x in -1.0..2.0 &&
                fractionalCoordinate.y in -1.0..2.0 &&
                fractionalCoordinate.z in -1.0..2.0,
        ) { "Tutor lattice fractional coordinates are outside the supported boundary range" }
    }
}
@Serializable
data class TutorVisualRepeat3D(
    val x: Int = 1,
    val y: Int = 1,
    val z: Int = 1,
) {
    init {
        require(x in 1..32 && y in 1..32 && z in 1..32)
    }

    val count: Int get() = x * y * z
}
@Serializable
@SerialName("lattice_3d")
data class TutorVisualLatticeElement(
    override val elementId: String,
    override val panelId: String,
    val latticeVectors: List<TutorVisualVector3>,
    val basis: List<TutorVisualLatticeBasisSite>,
    val repeat: TutorVisualRepeat3D = TutorVisualRepeat3D(),
    val connectionCutoff: Double? = null,
    val cropAtBoundary: Boolean = true,
    val label: String? = null,
    override val layer: TutorVisualLayer = TutorVisualLayer.CONTENT,
    override val initiallyVisible: Boolean = true,
    override val accessibilityLabel: String? = label,
) : TutorVisualDocumentElement {
    init {
        requireDocumentElementHeader(elementId, panelId, accessibilityLabel)
        require(latticeVectors.size == 3) { "A tutor lattice requires three lattice vectors" }
        require(basis.size in 1..TutorVisualDocumentScene.MAX_LATTICE_BASIS_SITES)
        require(basis.size * repeat.count <= TutorVisualDocumentScene.MAX_RUNTIME_INSTANCES) {
            "Tutor lattice exceeds the runtime instance budget"
        }
        connectionCutoff?.requireDocumentNumber("Tutor lattice connection cutoff", 0.001..100_000.0)
        label?.requireTutorDocumentText("Tutor lattice label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
    }
}
@Serializable
enum class TutorVisualBindingTarget {
    ELEMENT,
    PANEL,
}
@Serializable
enum class TutorVisualBindingProperty {
    X,
    Y,
    Z,
    ROTATION_X_DEGREES,
    ROTATION_Y_DEGREES,
    ROTATION_Z_DEGREES,
    SCALE,
    OPACITY,
    PATH_PROGRESS,
    LIQUID_LEVEL,
    PARTICLE_PROGRESS,
    VECTOR_X,
    VECTOR_Y,
    VECTOR_Z,
    CURVE_HIGHLIGHT,
    CAMERA_AZIMUTH_DEGREES,
    CAMERA_ELEVATION_DEGREES,
    CAMERA_DISTANCE,
}
@Serializable
data class TutorVisualBinding(
    val bindingId: String,
    val target: TutorVisualBindingTarget,
    val targetId: String,
    val property: TutorVisualBindingProperty,
    val expression: TutorVisualDocumentExpression,
) {
    init {
        bindingId.requireTutorSceneId("Tutor visual binding id")
        targetId.requireTutorSceneId("Tutor visual binding target id")
    }
}
@Serializable
enum class TutorVisualDocumentExpressionOperation {
    CONSTANT,
    TIME_SECONDS,
    TIME_PROGRESS,
    VARIABLE,
    ADD,
    SUBTRACT,
    MULTIPLY,
    DIVIDE,
    NEGATE,
    SIN,
    COS,
    SQRT,
    ABS,
    MIN,
    MAX,
    CLAMP,
    LERP,
}
@Serializable
data class TutorVisualDocumentExpression(
    val operation: TutorVisualDocumentExpressionOperation,
    val value: Double? = null,
    val variableId: String? = null,
    val arguments: List<TutorVisualDocumentExpression> = emptyList(),
) {
    init {
        val expectedArgumentCount = when (operation) {
            TutorVisualDocumentExpressionOperation.CONSTANT,
            TutorVisualDocumentExpressionOperation.TIME_SECONDS,
            TutorVisualDocumentExpressionOperation.TIME_PROGRESS,
            TutorVisualDocumentExpressionOperation.VARIABLE,
            -> 0
            TutorVisualDocumentExpressionOperation.NEGATE,
            TutorVisualDocumentExpressionOperation.SIN,
            TutorVisualDocumentExpressionOperation.COS,
            TutorVisualDocumentExpressionOperation.SQRT,
            TutorVisualDocumentExpressionOperation.ABS,
            -> 1
            TutorVisualDocumentExpressionOperation.ADD,
            TutorVisualDocumentExpressionOperation.SUBTRACT,
            TutorVisualDocumentExpressionOperation.MULTIPLY,
            TutorVisualDocumentExpressionOperation.DIVIDE,
            TutorVisualDocumentExpressionOperation.MIN,
            TutorVisualDocumentExpressionOperation.MAX,
            -> 2
            TutorVisualDocumentExpressionOperation.CLAMP,
            TutorVisualDocumentExpressionOperation.LERP,
            -> 3
        }
        require(arguments.size == expectedArgumentCount) {
            "Tutor visual expression has an invalid argument count"
        }
        when (operation) {
            TutorVisualDocumentExpressionOperation.CONSTANT -> {
                requireNotNull(value).requireDocumentNumber(
                    "Tutor visual expression constant",
                    -TutorVisualVariable.MAX_ABS_VALUE..TutorVisualVariable.MAX_ABS_VALUE,
                )
                require(variableId == null)
            }
            TutorVisualDocumentExpressionOperation.VARIABLE -> {
                require(value == null)
                requireNotNull(variableId).requireTutorSceneId("Tutor visual expression variable id")
            }
            else -> require(value == null && variableId == null)
        }
    }

    internal fun nodeCount(): Int = 1 + arguments.sumOf(TutorVisualDocumentExpression::nodeCount)

    internal fun depth(): Int = 1 + (arguments.maxOfOrNull(TutorVisualDocumentExpression::depth) ?: 0)

    internal fun variableIds(): Set<String> =
        listOfNotNull(variableId).toSet() + arguments.flatMap(TutorVisualDocumentExpression::variableIds)

    internal fun usesTime(): Boolean =
        operation in setOf(
            TutorVisualDocumentExpressionOperation.TIME_SECONDS,
            TutorVisualDocumentExpressionOperation.TIME_PROGRESS,
        ) || arguments.any(TutorVisualDocumentExpression::usesTime)

    companion object {
        fun constant(value: Double) = TutorVisualDocumentExpression(
            operation = TutorVisualDocumentExpressionOperation.CONSTANT,
            value = value,
        )

        fun variable(variableId: String) = TutorVisualDocumentExpression(
            operation = TutorVisualDocumentExpressionOperation.VARIABLE,
            variableId = variableId,
        )

        fun timeProgress() = TutorVisualDocumentExpression(
            operation = TutorVisualDocumentExpressionOperation.TIME_PROGRESS,
        )
    }
}
@Serializable
data class TutorVisualStep(
    val stepId: String,
    val label: String,
    val focusElementIds: List<String> = emptyList(),
    val visibleElementIds: List<String> = emptyList(),
    val dimmedElementIds: List<String> = emptyList(),
    val hiddenElementIds: List<String> = emptyList(),
    val displayVariableIds: List<String> = emptyList(),
    val primaryRelationElementId: String? = null,
    val animationStartSeconds: Double = 0.0,
    val animationEndSeconds: Double = 0.0,
    val camera: TutorVisualCameraStep? = null,
    val highlightedSeriesIds: List<String> = emptyList(),
) {
    init {
        stepId.requireTutorSceneId("Tutor visual step id")
        label.requireTutorDocumentText("Tutor visual step label", TutorVisualDocumentScene.MAX_LABEL_CHARS)
        primaryRelationElementId?.requireTutorSceneId("Tutor visual primary relation id")
        animationStartSeconds.requireDocumentNumber(
            "Tutor visual step animation start",
            0.0..TutorVisualDocumentScene.MAX_DURATION_SECONDS,
        )
        animationEndSeconds.requireDocumentNumber(
            "Tutor visual step animation end",
            0.0..TutorVisualDocumentScene.MAX_DURATION_SECONDS,
        )
        require(animationStartSeconds <= animationEndSeconds)
        require(displayVariableIds.size <= MAX_DISPLAYED_VALUES) {
            "A tutor visual step can display at most four key values"
        }
        val visibilityIds = visibleElementIds + dimmedElementIds + hiddenElementIds
        require(visibilityIds.distinct().size == visibilityIds.size) {
            "Tutor visual step visibility groups must not overlap"
        }
        require(focusElementIds.none(hiddenElementIds::contains)) {
            "A hidden tutor visual element cannot be focused"
        }
    }

    companion object {
        const val MAX_DISPLAYED_VALUES = 4
    }
}

private object TutorVisualDocumentValidator {
    fun requireValid(scene: TutorVisualDocumentScene) {
        requireTutorSceneHeader(
            scene.sceneId,
            scene.title,
            scene.schemaVersion,
            TutorVisualScene.DOCUMENT_SCHEMA_VERSION,
        )
        scene.fallbackMarkdown.requireTutorDocumentText(
            "Tutor visual fallback",
            TutorVisualDocumentScene.MAX_FALLBACK_CHARS,
            allowLineBreaks = true,
        )
        scene.accessibilitySummary.requireTutorDocumentText(
            "Tutor visual accessibility summary",
            TutorVisualDocumentScene.MAX_ACCESSIBILITY_CHARS,
            allowLineBreaks = true,
        )
        require(scene.panels.size in 1..TutorVisualDocumentScene.MAX_PANELS)
        require(scene.variables.size <= TutorVisualDocumentScene.MAX_VARIABLES)
        require(scene.elements.size in 1..TutorVisualDocumentScene.MAX_LOGICAL_ELEMENTS)
        require(scene.steps.size in 1..TutorVisualDocumentScene.MAX_STEPS)
        scene.durationSeconds.requireDocumentNumber(
            "Tutor visual document duration",
            0.0..TutorVisualDocumentScene.MAX_DURATION_SECONDS,
        )

        val panelIds = scene.panels.map(TutorVisualPanel::panelId)
        val variableIds = scene.variables.map(TutorVisualVariable::variableId)
        val elementIds = scene.elements.map(TutorVisualDocumentElement::elementId)
        val bindingIds = scene.bindings.map(TutorVisualBinding::bindingId)
        val stepIds = scene.steps.map(TutorVisualStep::stepId)
        requireUniqueTutorSceneIds(
            scene.sceneId,
            panelIds + variableIds + elementIds + bindingIds + stepIds,
        )
        val panelsById = scene.panels.associateBy(TutorVisualPanel::panelId)
        val variablesById = scene.variables.associateBy(TutorVisualVariable::variableId)
        val elementsById = scene.elements.associateBy(TutorVisualDocumentElement::elementId)

        scene.elements.forEach { element ->
            val panel = panelsById[element.panelId]
                ?: throw IllegalArgumentException("Tutor visual element references an unknown panel")
            require(element.supportedPanelKind() == panel.kind) {
                "Tutor visual element is incompatible with its panel"
            }
            element.referencedElementIds().forEach { referencedId ->
                val referenced = elementsById[referencedId]
                    ?: throw IllegalArgumentException("Tutor visual element reference is unknown")
                require(referenced.panelId == element.panelId) {
                    "Tutor visual element references another panel"
                }
            }
            element.referencedVariableIds().forEach { variableId ->
                require(variableId in variablesById) {
                    "Tutor visual element references an unknown variable"
                }
                require(variablesById.getValue(variableId).source != TutorVisualValueSource.ILLUSTRATIVE) {
                    "Illustrative variables cannot be rendered as visible values"
                }
            }
        }

        val series = scene.elements.filterIsInstance<TutorVisualChartSeriesElement>()
        require(series.size <= TutorVisualDocumentScene.MAX_CHART_SERIES)
        val runtimeInstances = scene.elements.sumOf(TutorVisualDocumentElement::runtimeInstanceCount)
        require(runtimeInstances <= TutorVisualDocumentScene.MAX_RUNTIME_INSTANCES) {
            "Tutor visual document exceeds the runtime instance budget"
        }

        val expressionNodes = scene.bindings.sumOf { it.expression.nodeCount() }
        require(expressionNodes <= TutorVisualDocumentScene.MAX_EXPRESSION_NODES)
        scene.bindings.forEach { binding ->
            require(binding.expression.depth() <= TutorVisualDocumentScene.MAX_EXPRESSION_DEPTH)
            require(binding.expression.variableIds().all(variablesById::containsKey)) {
                "Tutor visual binding references an unknown variable"
            }
            when (binding.target) {
                TutorVisualBindingTarget.ELEMENT -> require(binding.targetId in elementsById)
                TutorVisualBindingTarget.PANEL -> require(binding.targetId in panelsById)
            }
            require(binding.target.accepts(binding.property)) {
                "Tutor visual binding property is incompatible with its target"
            }
        }
        if (scene.bindings.any { it.expression.usesTime() }) {
            require(scene.durationSeconds > 0.0) {
                "Time-based tutor visual bindings require a positive duration"
            }
        }

        val seriesIds = series.mapTo(hashSetOf(), TutorVisualChartSeriesElement::elementId)
        scene.steps.forEach { step ->
            val allStepElementIds = step.focusElementIds + step.visibleElementIds +
                step.dimmedElementIds + step.hiddenElementIds +
                listOfNotNull(step.primaryRelationElementId)
            require(allStepElementIds.all(elementsById::containsKey)) {
                "Tutor visual step references an unknown element"
            }
            require(step.displayVariableIds.all(variablesById::containsKey))
            require(step.displayVariableIds.all { variablesById.getValue(it).display })
            require(step.highlightedSeriesIds.all(seriesIds::contains))
            step.camera?.let {
                require(panelsById[it.panelId]?.kind == TutorVisualPanelKind.SCENE_3D)
            }
            require(step.animationEndSeconds <= scene.durationSeconds || scene.durationSeconds == 0.0)
        }

        val textParts = buildList {
            add(scene.title)
            add(scene.fallbackMarkdown)
            add(scene.accessibilitySummary)
            scene.panels.forEach { panel ->
                panel.title?.let(::add)
                panel.chart?.let { chart ->
                    add(chart.xAxisLabel)
                    add(chart.leftAxisLabel)
                    chart.rightAxisLabel?.let(::add)
                }
            }
            scene.variables.forEach { variable ->
                add(variable.label)
                variable.unit?.let(::add)
                variable.derivationMarkdown?.let(::add)
            }
            scene.elements.forEach { addAll(it.textParts()) }
            scene.steps.forEach { add(it.label) }
        }
        require(textParts.sumOf(String::length) <= TutorVisualDocumentScene.MAX_TOTAL_TEXT_CHARS) {
            "Tutor visual document exceeds the text budget"
        }
    }
}

internal fun TutorVisualDocumentElement.supportedPanelKind(): TutorVisualPanelKind = when (this) {
    is TutorVisual2DNodeElement,
    is TutorVisual2DConnectorElement,
    is TutorVisualParticleGroupElement,
    -> TutorVisualPanelKind.DIAGRAM_2D
    is TutorVisualGeometry3DElement,
    is TutorVisualLatticeElement,
    -> TutorVisualPanelKind.SCENE_3D
    is TutorVisualChartSeriesElement,
    is TutorVisualChartAnnotationElement,
    -> TutorVisualPanelKind.SCIENTIFIC_CHART
}

internal fun TutorVisualDocumentElement.referencedElementIds(): List<String> = when (this) {
    is TutorVisual2DNodeElement,
    is TutorVisualLatticeElement,
    is TutorVisualChartSeriesElement,
    is TutorVisualChartAnnotationElement,
    -> emptyList()
    is TutorVisual2DConnectorElement -> listOf(from.elementId, to.elementId)
    is TutorVisualParticleGroupElement -> listOfNotNull(regionElementId, pathElementId)
    is TutorVisualGeometry3DElement -> listOfNotNull(parentElementId)
}

internal fun TutorVisualDocumentElement.referencedVariableIds(): List<String> = when (this) {
    is TutorVisual2DNodeElement -> listOfNotNull(valueVariableId)
    is TutorVisual2DConnectorElement -> listOfNotNull(valueVariableId)
    is TutorVisualChartAnnotationElement -> listOfNotNull(xVariableId, yVariableId, endXVariableId)
    is TutorVisualParticleGroupElement,
    is TutorVisualGeometry3DElement,
    is TutorVisualLatticeElement,
    is TutorVisualChartSeriesElement,
    -> emptyList()
}

internal fun TutorVisualDocumentElement.runtimeInstanceCount(): Int = when (this) {
    is TutorVisualParticleGroupElement -> instanceCount
    is TutorVisualGeometry3DElement -> maxOf(1, instanceTransforms.size)
    is TutorVisualLatticeElement -> basis.size * repeat.count
    else -> 1
}

internal fun TutorVisualDocumentElement.textParts(): List<String> = when (this) {
    is TutorVisual2DNodeElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisual2DConnectorElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualParticleGroupElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualGeometry3DElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualLatticeElement -> listOfNotNull(label, accessibilityLabel) + basis.map { it.label }
        is TutorVisualChartSeriesElement -> listOfNotNull(label, accessibilityLabel)
    is TutorVisualChartAnnotationElement -> listOfNotNull(label, accessibilityLabel)
}

internal fun TutorVisualBindingTarget.accepts(property: TutorVisualBindingProperty): Boolean = when (this) {
    TutorVisualBindingTarget.ELEMENT -> property !in setOf(
        TutorVisualBindingProperty.CAMERA_AZIMUTH_DEGREES,
        TutorVisualBindingProperty.CAMERA_ELEVATION_DEGREES,
        TutorVisualBindingProperty.CAMERA_DISTANCE,
    )
    TutorVisualBindingTarget.PANEL -> property in setOf(
        TutorVisualBindingProperty.CAMERA_AZIMUTH_DEGREES,
        TutorVisualBindingProperty.CAMERA_ELEVATION_DEGREES,
        TutorVisualBindingProperty.CAMERA_DISTANCE,
    )
}

internal fun requireDocumentElementHeader(
    elementId: String,
    panelId: String,
    accessibilityLabel: String?,
) {
    elementId.requireTutorSceneId("Tutor visual element id")
    panelId.requireTutorSceneId("Tutor visual element panel id")
    accessibilityLabel?.requireTutorDocumentText(
        "Tutor visual element accessibility label",
        TutorVisualDocumentScene.MAX_LABEL_CHARS,
    )
}

internal fun String.requireTutorDocumentText(
    label: String,
    maxChars: Int,
    allowLineBreaks: Boolean = false,
) {
    requireTutorSceneText(label, maxChars, allowLineBreaks)
}

internal fun Double.requireDocumentNumber(
    label: String,
    range: ClosedFloatingPointRange<Double>,
) {
    require(isFinite()) { "$label must be finite" }
    require(this in range) { "$label is outside the supported range" }
}
