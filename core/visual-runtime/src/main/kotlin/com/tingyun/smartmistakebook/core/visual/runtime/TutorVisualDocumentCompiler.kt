package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualBinding
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingTarget
import com.tingyun.smartmistakebook.core.model.TutorVisualCamera
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesElement
import com.tingyun.smartmistakebook.core.model.TutorVisualConnectorKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDimension
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentElement
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentExpressionOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualParticleGroupElement
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualValueSource
import com.tingyun.smartmistakebook.core.model.TutorVisualVariable
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class TutorVisualIssueSeverity {
    WARNING,
    ERROR,
}

enum class TutorVisualIssueCode {
    UNIT_MISMATCH,
    INVALID_EXPRESSION_RESULT,
    UNSUPPORTED_BINDING_PROPERTY,
    PARTICLE_RENDER_BUDGET_EXCEEDED,
    INVALID_DIRECTION,
    CHART_AXIS_MISSING,
    EMPTY_FOCUS_STEP,
    UNREVIEWED_HIGH_RISK_SCENE,
}

data class TutorVisualIntegrityIssue(
    val code: TutorVisualIssueCode,
    val severity: TutorVisualIssueSeverity,
    val targetId: String,
    val detail: String,
)

data class TutorVisualIntegrityReport(
    val issues: List<TutorVisualIntegrityIssue>,
) {
    val canRender: Boolean = issues.none { it.severity == TutorVisualIssueSeverity.ERROR }
}

data class CompiledTutorVisualPanel(
    val source: TutorVisualPanel,
    val staticElementIds: Set<String>,
    val dynamicElementIds: Set<String>,
)

data class CompiledTutorVisualDocument(
    val scene: TutorVisualDocumentScene,
    val panels: Map<String, CompiledTutorVisualPanel>,
    val variables: Map<String, TutorVisualVariable>,
    val elements: Map<String, TutorVisualDocumentElement>,
    val bindingsByTarget: Map<String, List<TutorVisualBinding>>,
    val integrity: TutorVisualIntegrityReport,
) {
    fun evaluate(
        requestedTimeSeconds: Double,
        requestedStepIndex: Int,
    ): TutorVisualFrame {
        val time = requestedTimeSeconds.coerceIn(0.0, scene.durationSeconds)
        val stepIndex = requestedStepIndex.coerceIn(0, scene.steps.lastIndex)
        val step = scene.steps[stepIndex]
        val variableValues = variables.mapValues { it.value.value }
        val timeProgress = if (scene.durationSeconds > 0.0) time / scene.durationSeconds else 0.0
        val evaluationIssues = mutableListOf<TutorVisualIntegrityIssue>()
        val elementStates = elements.mapValues { (elementId, element) ->
            val properties = buildMap {
                bindingsByTarget[elementId].orEmpty().forEach { binding ->
                    val value = TutorVisualExpressionEvaluator.evaluate(
                        expression = binding.expression,
                        variables = variableValues,
                        timeSeconds = time,
                        timeProgress = timeProgress,
                    )
                    if (value == null) {
                        evaluationIssues += binding.invalidExpressionIssue()
                    } else {
                        put(binding.property, value)
                    }
                }
            }
            val explicitlyVisible = elementId in step.visibleElementIds
            val hidden = elementId in step.hiddenElementIds
            val visible = !hidden && (element.initiallyVisible || explicitlyVisible)
            val focused = elementId in step.focusElementIds ||
                elementId == step.primaryRelationElementId
            val dimmed = elementId in step.dimmedElementIds ||
                step.focusElementIds.isNotEmpty() && !focused
            TutorVisualElementFrame(
                visible = visible,
                focused = focused,
                dimmed = dimmed,
                properties = properties,
            )
        }
        val cameras = scene.panels
            .filter { it.kind == TutorVisualPanelKind.SCENE_3D }
            .associate { panel ->
                panel.panelId to (
                    step.camera
                        ?.takeIf { it.panelId == panel.panelId }
                        ?.camera
                        ?: requireNotNull(panel.camera)
                    )
            }
            .toMutableMap()
        bindingsByTarget.forEach { (targetId, bindings) ->
            val original = cameras[targetId] ?: return@forEach
            cameras[targetId] = original.applyBindings(
                bindings = bindings,
                variables = variableValues,
                timeSeconds = time,
                timeProgress = timeProgress,
                evaluationIssues = evaluationIssues,
            )
        }
        return TutorVisualFrame(
            timeSeconds = time,
            timeProgress = timeProgress,
            stepIndex = stepIndex,
            step = step,
            elements = elementStates,
            panelCameras = cameras,
            evaluationIssues = evaluationIssues,
        )
    }
}

data class TutorVisualElementFrame(
    val visible: Boolean,
    val focused: Boolean,
    val dimmed: Boolean,
    val properties: Map<TutorVisualBindingProperty, Double>,
)

data class TutorVisualFrame(
    val timeSeconds: Double,
    val timeProgress: Double,
    val stepIndex: Int,
    val step: TutorVisualStep,
    val elements: Map<String, TutorVisualElementFrame>,
    val panelCameras: Map<String, TutorVisualCamera>,
    val evaluationIssues: List<TutorVisualIntegrityIssue> = emptyList(),
) {
    val canRender: Boolean
        get() = evaluationIssues.isEmpty()
}

object TutorVisualDocumentCompiler {
    fun compile(scene: TutorVisualDocumentScene): CompiledTutorVisualDocument {
        val variables = scene.variables.associateBy(TutorVisualVariable::variableId)
        val bindingsByTarget = scene.bindings.groupBy(TutorVisualBinding::targetId)
        val panels = scene.panels.associateBy(TutorVisualPanel::panelId)
        val elements = scene.elements.associateBy(TutorVisualDocumentElement::elementId)
        val variableValues = variables.mapValues { (_, variable) -> variable.value }
        val evaluationTimes = buildSet {
            add(0.0)
            add(scene.durationSeconds)
            scene.steps.forEach { step ->
                add(step.animationStartSeconds)
                add(step.animationEndSeconds)
                add((step.animationStartSeconds + step.animationEndSeconds) / 2.0)
            }
        }
        val issues = buildList {
            scene.bindings.forEach { binding ->
                val dimension = inferDimension(binding.expression, variables, binding.bindingId, this)
                requireBindingDimension(binding, dimension, this)
                requireRendererConsumer(binding, panels, elements, this)
                requireProvablySafeExpression(
                    binding = binding,
                    durationSeconds = scene.durationSeconds,
                    variables = variableValues,
                    issues = this,
                )
                if (
                    evaluationTimes.any { timeSeconds ->
                        TutorVisualExpressionEvaluator.evaluate(
                            expression = binding.expression,
                            variables = variableValues,
                            timeSeconds = timeSeconds,
                            timeProgress = if (scene.durationSeconds > 0.0) {
                                timeSeconds / scene.durationSeconds
                            } else {
                                0.0
                            },
                        ) == null
                    }
                ) {
                    add(binding.invalidExpressionIssue())
                }
            }
            scene.elements
                .filterIsInstance<TutorVisualParticleGroupElement>()
                .groupBy(TutorVisualParticleGroupElement::panelId)
                .forEach { (panelId, groups) ->
                    val visibleInstanceBudget = groups.sumOf(
                        TutorVisualParticleGroupElement::instanceCount,
                    )
                    if (visibleInstanceBudget > MAX_CANVAS_PARTICLE_INSTANCES) {
                        add(
                            TutorVisualIntegrityIssue(
                                code = TutorVisualIssueCode.PARTICLE_RENDER_BUDGET_EXCEEDED,
                                severity = TutorVisualIssueSeverity.ERROR,
                                targetId = panelId,
                                detail = "The visible 2D particle budget exceeds " +
                                    "$MAX_CANVAS_PARTICLE_INSTANCES instances.",
                            ),
                        )
                    }
                }
            scene.elements.filterIsInstance<TutorVisual2DConnectorElement>().forEach { connector ->
                if (connector.kind in DIRECTED_CONNECTORS && !connector.directed) {
                    add(
                        TutorVisualIntegrityIssue(
                            TutorVisualIssueCode.INVALID_DIRECTION,
                            TutorVisualIssueSeverity.ERROR,
                            connector.elementId,
                            "A directional relationship is missing its direction.",
                        ),
                    )
                }
            }
            scene.elements.filterIsInstance<TutorVisualChartSeriesElement>().forEach { series ->
                if (
                    series.axis.name == "RIGHT" &&
                    panels.getValue(series.panelId).chart?.rightAxisLabel == null
                ) {
                    add(
                        TutorVisualIntegrityIssue(
                            TutorVisualIssueCode.CHART_AXIS_MISSING,
                            TutorVisualIssueSeverity.ERROR,
                            series.elementId,
                            "A right-axis series requires a right-axis label.",
                        ),
                    )
                }
            }
            scene.steps.forEach { step ->
                if (
                    scene.elements.size > 24 &&
                    step.focusElementIds.isEmpty() &&
                    step.primaryRelationElementId == null
                ) {
                    add(
                        TutorVisualIntegrityIssue(
                            TutorVisualIssueCode.EMPTY_FOCUS_STEP,
                            TutorVisualIssueSeverity.WARNING,
                            step.stepId,
                            "A dense scene step has no single focus.",
                        ),
                    )
                }
            }
        }
        val dynamicElementIds = scene.bindings
            .filter { it.target == TutorVisualBindingTarget.ELEMENT }
            .mapTo(hashSetOf(), TutorVisualBinding::targetId)
        val compiledPanels = scene.panels.associate { panel ->
            val panelElementIds = scene.elements
                .filter { it.panelId == panel.panelId }
                .mapTo(hashSetOf(), TutorVisualDocumentElement::elementId)
            panel.panelId to CompiledTutorVisualPanel(
                source = panel,
                staticElementIds = panelElementIds - dynamicElementIds,
                dynamicElementIds = panelElementIds.intersect(dynamicElementIds),
            )
        }
        val report = TutorVisualIntegrityReport(issues)
        require(report.canRender) {
            report.issues.filter { it.severity == TutorVisualIssueSeverity.ERROR }
                .joinToString(prefix = "Tutor visual compilation failed: ") { it.detail }
        }
        return CompiledTutorVisualDocument(
            scene = scene,
            panels = compiledPanels,
            variables = variables,
            elements = elements,
            bindingsByTarget = bindingsByTarget,
            integrity = report,
        )
    }

    private fun inferDimension(
        expression: TutorVisualDocumentExpression,
        variables: Map<String, TutorVisualVariable>,
        targetId: String,
        issues: MutableList<TutorVisualIntegrityIssue>,
    ): TutorVisualDimension {
        val argumentDimensions = expression.arguments.map {
            inferDimension(it, variables, targetId, issues)
        }
        fun mismatch(detail: String): TutorVisualDimension {
            issues += TutorVisualIntegrityIssue(
                TutorVisualIssueCode.UNIT_MISMATCH,
                TutorVisualIssueSeverity.ERROR,
                targetId,
                detail,
            )
            return TutorVisualDimension.OTHER
        }
        return when (expression.operation) {
            TutorVisualDocumentExpressionOperation.CONSTANT,
            TutorVisualDocumentExpressionOperation.TIME_PROGRESS,
            -> TutorVisualDimension.DIMENSIONLESS
            TutorVisualDocumentExpressionOperation.TIME_SECONDS -> TutorVisualDimension.TIME
            TutorVisualDocumentExpressionOperation.VARIABLE ->
                variables.getValue(requireNotNull(expression.variableId)).dimension
            TutorVisualDocumentExpressionOperation.ADD,
            TutorVisualDocumentExpressionOperation.SUBTRACT,
            TutorVisualDocumentExpressionOperation.MIN,
            TutorVisualDocumentExpressionOperation.MAX,
            -> if (argumentDimensions.distinct().size == 1) {
                argumentDimensions.first()
            } else {
                mismatch("Addition, subtraction, minimum, and maximum require matching units.")
            }
            TutorVisualDocumentExpressionOperation.MULTIPLY -> when {
                argumentDimensions[0] == TutorVisualDimension.DIMENSIONLESS -> argumentDimensions[1]
                argumentDimensions[1] == TutorVisualDimension.DIMENSIONLESS -> argumentDimensions[0]
                else -> TutorVisualDimension.OTHER
            }
            TutorVisualDocumentExpressionOperation.DIVIDE -> when {
                argumentDimensions[0] == argumentDimensions[1] -> TutorVisualDimension.DIMENSIONLESS
                argumentDimensions[1] == TutorVisualDimension.DIMENSIONLESS -> argumentDimensions[0]
                else -> TutorVisualDimension.OTHER
            }
            TutorVisualDocumentExpressionOperation.NEGATE,
            TutorVisualDocumentExpressionOperation.ABS,
            -> argumentDimensions.single()
            TutorVisualDocumentExpressionOperation.SIN,
            TutorVisualDocumentExpressionOperation.COS,
            -> if (
                argumentDimensions.single() in
                setOf(TutorVisualDimension.DIMENSIONLESS, TutorVisualDimension.ANGLE)
            ) {
                TutorVisualDimension.DIMENSIONLESS
            } else {
                mismatch("Trigonometric expressions require an angle or a dimensionless value.")
            }
            TutorVisualDocumentExpressionOperation.SQRT ->
                if (argumentDimensions.single() == TutorVisualDimension.DIMENSIONLESS) {
                    TutorVisualDimension.DIMENSIONLESS
                } else {
                    TutorVisualDimension.OTHER
                }
            TutorVisualDocumentExpressionOperation.CLAMP ->
                if (argumentDimensions.distinct().size == 1) {
                    argumentDimensions.first()
                } else {
                    mismatch("Clamp bounds must use the same unit as the clamped value.")
                }
            TutorVisualDocumentExpressionOperation.LERP ->
                if (
                    argumentDimensions[0] == argumentDimensions[1] &&
                    argumentDimensions[2] == TutorVisualDimension.DIMENSIONLESS
                ) {
                    argumentDimensions[0]
                } else {
                    mismatch("Interpolation endpoints must match and progress must be dimensionless.")
                }
        }
    }

    private fun requireBindingDimension(
        binding: TutorVisualBinding,
        dimension: TutorVisualDimension,
        issues: MutableList<TutorVisualIntegrityIssue>,
    ) {
        val accepted = when (binding.property) {
            TutorVisualBindingProperty.OPACITY,
            TutorVisualBindingProperty.PATH_PROGRESS,
            TutorVisualBindingProperty.PARTICLE_PROGRESS,
            TutorVisualBindingProperty.CURVE_HIGHLIGHT,
            TutorVisualBindingProperty.SCALE,
            -> setOf(TutorVisualDimension.DIMENSIONLESS)
            TutorVisualBindingProperty.ROTATION_X_DEGREES,
            TutorVisualBindingProperty.ROTATION_Y_DEGREES,
            TutorVisualBindingProperty.ROTATION_Z_DEGREES,
            TutorVisualBindingProperty.CAMERA_AZIMUTH_DEGREES,
            TutorVisualBindingProperty.CAMERA_ELEVATION_DEGREES,
            -> setOf(TutorVisualDimension.DIMENSIONLESS, TutorVisualDimension.ANGLE)
            TutorVisualBindingProperty.X,
            TutorVisualBindingProperty.Y,
            TutorVisualBindingProperty.Z,
            TutorVisualBindingProperty.LIQUID_LEVEL,
            TutorVisualBindingProperty.VECTOR_X,
            TutorVisualBindingProperty.VECTOR_Y,
            TutorVisualBindingProperty.VECTOR_Z,
            TutorVisualBindingProperty.CAMERA_DISTANCE,
            -> setOf(
                TutorVisualDimension.DIMENSIONLESS,
                TutorVisualDimension.LENGTH,
                TutorVisualDimension.OTHER,
            )
        }
        if (dimension !in accepted) {
            issues += TutorVisualIntegrityIssue(
                TutorVisualIssueCode.UNIT_MISMATCH,
                TutorVisualIssueSeverity.ERROR,
                binding.bindingId,
                "The bound value has an incompatible physical dimension.",
            )
        }
    }

    private fun requireRendererConsumer(
        binding: TutorVisualBinding,
        panels: Map<String, TutorVisualPanel>,
        elements: Map<String, TutorVisualDocumentElement>,
        issues: MutableList<TutorVisualIntegrityIssue>,
    ) {
        val supported = when (binding.target) {
            TutorVisualBindingTarget.PANEL -> {
                panels[binding.targetId]?.kind == TutorVisualPanelKind.SCENE_3D &&
                    binding.property in CAMERA_PROPERTIES
            }
            TutorVisualBindingTarget.ELEMENT -> {
                val element = elements[binding.targetId]
                val panelKind = element?.panelId?.let(panels::get)?.kind
                when {
                    element is TutorVisual2DConnectorElement ->
                        binding.property == TutorVisualBindingProperty.PATH_PROGRESS
                    element is TutorVisual2DNodeElement ->
                        binding.property == TutorVisualBindingProperty.OPACITY ||
                            (
                                binding.property == TutorVisualBindingProperty.LIQUID_LEVEL &&
                                    element.kind == TutorVisual2DNodeKind.LIQUID_LEVEL
                                )
                    element is TutorVisualParticleGroupElement ->
                        binding.property == TutorVisualBindingProperty.PARTICLE_PROGRESS
                    panelKind == TutorVisualPanelKind.SCENE_3D &&
                        (element is TutorVisualGeometry3DElement ||
                            element is TutorVisualLatticeElement) ->
                        binding.property in THREE_DIMENSIONAL_PROPERTIES
                    else -> false
                }
            }
        }
        if (!supported) {
            issues += TutorVisualIntegrityIssue(
                code = TutorVisualIssueCode.UNSUPPORTED_BINDING_PROPERTY,
                severity = TutorVisualIssueSeverity.ERROR,
                targetId = binding.bindingId,
                detail = "The declared binding has no renderer consumer.",
            )
        }
    }

    private fun requireProvablySafeExpression(
        binding: TutorVisualBinding,
        durationSeconds: Double,
        variables: Map<String, Double>,
        issues: MutableList<TutorVisualIntegrityIssue>,
    ) {
        if (
            inferSafeInterval(
                expression = binding.expression,
                durationSeconds = durationSeconds,
                variables = variables,
            ) == null
        ) {
            issues += binding.invalidExpressionIssue()
        }
    }

    private fun inferSafeInterval(
        expression: TutorVisualDocumentExpression,
        durationSeconds: Double,
        variables: Map<String, Double>,
    ): RuntimeInterval? {
        val arguments = expression.arguments.map { argument ->
            inferSafeInterval(argument, durationSeconds, variables) ?: return null
        }
        val interval = when (expression.operation) {
            TutorVisualDocumentExpressionOperation.CONSTANT ->
                expression.value?.let(RuntimeInterval::exact)
            TutorVisualDocumentExpressionOperation.TIME_SECONDS ->
                RuntimeInterval(0.0, durationSeconds)
            TutorVisualDocumentExpressionOperation.TIME_PROGRESS ->
                RuntimeInterval(0.0, if (durationSeconds > 0.0) 1.0 else 0.0)
            TutorVisualDocumentExpressionOperation.VARIABLE ->
                variables[expression.variableId]?.let(RuntimeInterval::exact)
            TutorVisualDocumentExpressionOperation.ADD ->
                RuntimeInterval(
                    arguments[0].minimum + arguments[1].minimum,
                    arguments[0].maximum + arguments[1].maximum,
                )
            TutorVisualDocumentExpressionOperation.SUBTRACT ->
                RuntimeInterval(
                    arguments[0].minimum - arguments[1].maximum,
                    arguments[0].maximum - arguments[1].minimum,
                )
            TutorVisualDocumentExpressionOperation.MULTIPLY ->
                RuntimeInterval.hullOfProducts(arguments[0], arguments[1])
            TutorVisualDocumentExpressionOperation.DIVIDE -> {
                val denominator = arguments[1]
                if (
                    denominator.minimum <= MIN_PROVABLE_DIVISOR &&
                    denominator.maximum >= -MIN_PROVABLE_DIVISOR
                ) {
                    return null
                }
                RuntimeInterval.hullOfQuotients(arguments[0], denominator)
            }
            TutorVisualDocumentExpressionOperation.NEGATE ->
                RuntimeInterval(-arguments[0].maximum, -arguments[0].minimum)
            TutorVisualDocumentExpressionOperation.SIN,
            TutorVisualDocumentExpressionOperation.COS,
            -> RuntimeInterval(-1.0, 1.0)
            TutorVisualDocumentExpressionOperation.SQRT -> {
                if (arguments[0].minimum < 0.0) return null
                RuntimeInterval(
                    sqrt(arguments[0].minimum),
                    sqrt(arguments[0].maximum),
                )
            }
            TutorVisualDocumentExpressionOperation.ABS -> arguments[0].absolute()
            TutorVisualDocumentExpressionOperation.MIN ->
                RuntimeInterval(
                    min(arguments[0].minimum, arguments[1].minimum),
                    min(arguments[0].maximum, arguments[1].maximum),
                )
            TutorVisualDocumentExpressionOperation.MAX ->
                RuntimeInterval(
                    max(arguments[0].minimum, arguments[1].minimum),
                    max(arguments[0].maximum, arguments[1].maximum),
                )
            TutorVisualDocumentExpressionOperation.CLAMP ->
                RuntimeInterval.hull(arguments)
            TutorVisualDocumentExpressionOperation.LERP ->
                RuntimeInterval.hull(arguments.take(2))
        } ?: return null
        return interval.takeIf(RuntimeInterval::isRenderable)
    }

    private val DIRECTED_CONNECTORS = setOf(
        TutorVisualConnectorKind.FLOW,
        TutorVisualConnectorKind.VECTOR,
        TutorVisualConnectorKind.RAY,
        TutorVisualConnectorKind.FORCE,
    )
    private val CAMERA_PROPERTIES = setOf(
        TutorVisualBindingProperty.CAMERA_AZIMUTH_DEGREES,
        TutorVisualBindingProperty.CAMERA_ELEVATION_DEGREES,
        TutorVisualBindingProperty.CAMERA_DISTANCE,
    )
    private val THREE_DIMENSIONAL_PROPERTIES = setOf(
        TutorVisualBindingProperty.X,
        TutorVisualBindingProperty.Y,
        TutorVisualBindingProperty.Z,
        TutorVisualBindingProperty.ROTATION_X_DEGREES,
        TutorVisualBindingProperty.ROTATION_Y_DEGREES,
        TutorVisualBindingProperty.ROTATION_Z_DEGREES,
        TutorVisualBindingProperty.SCALE,
    )
    private const val MIN_PROVABLE_DIVISOR = 1e-12
    private const val MAX_CANVAS_PARTICLE_INSTANCES = 300
}

private data class RuntimeInterval(
    val minimum: Double,
    val maximum: Double,
) {
    fun isRenderable(): Boolean =
        minimum.isFinite() &&
            maximum.isFinite() &&
            minimum <= maximum &&
            abs(minimum) <= MAX_ABS_PROVABLE_VALUE &&
            abs(maximum) <= MAX_ABS_PROVABLE_VALUE

    fun absolute(): RuntimeInterval {
        val maximumMagnitude = max(abs(minimum), abs(maximum))
        return if (minimum <= 0.0 && maximum >= 0.0) {
            RuntimeInterval(0.0, maximumMagnitude)
        } else {
            RuntimeInterval(min(abs(minimum), abs(maximum)), maximumMagnitude)
        }
    }

    companion object {
        fun exact(value: Double): RuntimeInterval = RuntimeInterval(value, value)

        fun hull(intervals: List<RuntimeInterval>): RuntimeInterval = RuntimeInterval(
            minimum = intervals.minOf(RuntimeInterval::minimum),
            maximum = intervals.maxOf(RuntimeInterval::maximum),
        )

        fun hullOfProducts(
            left: RuntimeInterval,
            right: RuntimeInterval,
        ): RuntimeInterval = hullOfValues(
            left.minimum * right.minimum,
            left.minimum * right.maximum,
            left.maximum * right.minimum,
            left.maximum * right.maximum,
        )

        fun hullOfQuotients(
            numerator: RuntimeInterval,
            denominator: RuntimeInterval,
        ): RuntimeInterval = hullOfValues(
            numerator.minimum / denominator.minimum,
            numerator.minimum / denominator.maximum,
            numerator.maximum / denominator.minimum,
            numerator.maximum / denominator.maximum,
        )

        private fun hullOfValues(vararg values: Double): RuntimeInterval = RuntimeInterval(
            minimum = values.min(),
            maximum = values.max(),
        )

        private const val MAX_ABS_PROVABLE_VALUE = 1e15
    }
}

object TutorVisualExpressionEvaluator {
    fun evaluate(
        expression: TutorVisualDocumentExpression,
        variables: Map<String, Double>,
        timeSeconds: Double,
        timeProgress: Double,
    ): Double? {
        val args = expression.arguments.map {
            evaluate(it, variables, timeSeconds, timeProgress) ?: return null
        }
        val result = when (expression.operation) {
            TutorVisualDocumentExpressionOperation.CONSTANT -> expression.value
            TutorVisualDocumentExpressionOperation.TIME_SECONDS -> timeSeconds
            TutorVisualDocumentExpressionOperation.TIME_PROGRESS -> timeProgress
            TutorVisualDocumentExpressionOperation.VARIABLE -> variables[expression.variableId]
            TutorVisualDocumentExpressionOperation.ADD -> args[0] + args[1]
            TutorVisualDocumentExpressionOperation.SUBTRACT -> args[0] - args[1]
            TutorVisualDocumentExpressionOperation.MULTIPLY -> args[0] * args[1]
            TutorVisualDocumentExpressionOperation.DIVIDE ->
                if (abs(args[1]) < MIN_DIVISOR) null else args[0] / args[1]
            TutorVisualDocumentExpressionOperation.NEGATE -> -args[0]
            TutorVisualDocumentExpressionOperation.SIN -> sin(args[0])
            TutorVisualDocumentExpressionOperation.COS -> cos(args[0])
            TutorVisualDocumentExpressionOperation.SQRT ->
                if (args[0] < 0.0) null else sqrt(args[0])
            TutorVisualDocumentExpressionOperation.ABS -> abs(args[0])
            TutorVisualDocumentExpressionOperation.MIN -> min(args[0], args[1])
            TutorVisualDocumentExpressionOperation.MAX -> max(args[0], args[1])
            TutorVisualDocumentExpressionOperation.CLAMP ->
                args[0].coerceIn(min(args[1], args[2]), max(args[1], args[2]))
            TutorVisualDocumentExpressionOperation.LERP ->
                args[0] + (args[1] - args[0]) * args[2].coerceIn(0.0, 1.0)
        }
        return result?.takeIf { it.isFinite() && abs(it) <= MAX_ABS_RUNTIME_VALUE }
    }

    private const val MIN_DIVISOR = 1e-12
    private const val MAX_ABS_RUNTIME_VALUE = 1e15
}

private fun TutorVisualCamera.applyBindings(
    bindings: List<TutorVisualBinding>,
    variables: Map<String, Double>,
    timeSeconds: Double,
    timeProgress: Double,
    evaluationIssues: MutableList<TutorVisualIntegrityIssue>,
): TutorVisualCamera {
    var azimuth = azimuthDegrees
    var elevation = elevationDegrees
    var boundDistance = distance
    bindings.forEach { binding ->
        val value = TutorVisualExpressionEvaluator.evaluate(
            binding.expression,
            variables,
            timeSeconds,
            timeProgress,
        )
        if (value == null) {
            evaluationIssues += binding.invalidExpressionIssue()
            return@forEach
        }
        when (binding.property) {
            TutorVisualBindingProperty.CAMERA_AZIMUTH_DEGREES -> azimuth = value
            TutorVisualBindingProperty.CAMERA_ELEVATION_DEGREES -> elevation = value
            TutorVisualBindingProperty.CAMERA_DISTANCE -> boundDistance = value
            else -> Unit
        }
    }
    return copy(
        azimuthDegrees = azimuth.coerceIn(-720.0, 720.0),
        elevationDegrees = elevation.coerceIn(-85.0, 85.0),
        distance = boundDistance.coerceIn(minimumDistance, maximumDistance),
    )
}

private fun TutorVisualBinding.invalidExpressionIssue() = TutorVisualIntegrityIssue(
    code = TutorVisualIssueCode.INVALID_EXPRESSION_RESULT,
    severity = TutorVisualIssueSeverity.ERROR,
    targetId = bindingId,
    detail = "The bound expression must produce a finite value within the renderable range.",
)

enum class TutorVisualRiskLevel {
    LOW,
    REVIEW_REQUIRED,
}

data class TutorVisualRiskAssessment(
    val level: TutorVisualRiskLevel,
    val reasons: Set<String>,
)

object TutorVisualRiskAssessor {
    fun assess(scene: TutorVisualDocumentScene): TutorVisualRiskAssessment {
        val reasons = buildSet {
            if (scene.panels.size > 1) add("multiple_synchronized_views")
            if (scene.elements.size > 80) add("dense_scene")
            if (scene.elements.count { it is TutorVisual2DConnectorElement } > 20) {
                add("connection_dense_device")
            }
            if (scene.elements.any { it is TutorVisualLatticeElement }) add("lattice")
            if (
                scene.elements.count {
                    it is TutorVisualGeometry3DElement || it is TutorVisualLatticeElement
                } > 12
            ) {
                add("complex_3d")
            }
        }
        return TutorVisualRiskAssessment(
            level = if (reasons.isEmpty()) TutorVisualRiskLevel.LOW else TutorVisualRiskLevel.REVIEW_REQUIRED,
            reasons = reasons,
        )
    }
}

object TutorVisualCacheKey {
    fun create(
        questionDocumentFingerprint: String,
        sourceImageSha256: String,
        modelVersion: String,
        schemaVersion: Int = 2,
        providerId: String = "",
        providerConfigurationVersion: String = "",
        requestIdentity: String = "",
    ): String {
        val canonical = listOf(
            questionDocumentFingerprint,
            sourceImageSha256.lowercase(),
            providerId,
            providerConfigurationVersion,
            requestIdentity,
            modelVersion,
            schemaVersion.toString(),
        ).joinToString("\n")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

fun TutorVisualDocumentScene.containsVisibleIllustrativeValue(): Boolean {
    val illustrativeIds = variables
        .filter { it.source == TutorVisualValueSource.ILLUSTRATIVE }
        .mapTo(hashSetOf(), TutorVisualVariable::variableId)
    if (steps.any { step -> step.displayVariableIds.any(illustrativeIds::contains) }) return true
    return elements.any { element ->
        when (element) {
            is com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement ->
                element.valueVariableId in illustrativeIds
            is TutorVisual2DConnectorElement -> element.valueVariableId in illustrativeIds
            is com.tingyun.smartmistakebook.core.model.TutorVisualChartAnnotationElement ->
                listOfNotNull(
                    element.xVariableId,
                    element.yVariableId,
                    element.endXVariableId,
                ).any(illustrativeIds::contains)
            else -> false
        }
    }
}
