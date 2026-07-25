package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A bounded, declarative visual program.
 *
 * The model describes semantic values and relationships. The local runtime owns validation,
 * calculation, playback, layout, colors, typography, touch behavior, and accessibility.
 */
@Serializable
@SerialName("visual_program")
data class TutorVisualProgramScene(
    override val sceneId: String,
    override val title: String,
    val accessibilitySummary: String,
    val parameters: List<TutorVisualParameter> = emptyList(),
    val commands: List<TutorVisualCommand>,
    val durationSeconds: Double? = null,
    val showAxes: Boolean = false,
    val xUnit: String? = null,
    val yUnit: String? = null,
    override val schemaVersion: Int = TutorVisualScene.SCHEMA_VERSION,
) : TutorVisualScene {
    init {
        requireTutorSceneHeader(sceneId, title, schemaVersion)
        accessibilitySummary.requireTutorSceneText(
            "Tutor visual program accessibility summary",
            MAX_ACCESSIBILITY_SUMMARY_CHARS,
            true,
        )
        require(parameters.size <= MAX_PARAMETERS) {
            "Tutor visual program exceeds the parameter budget"
        }
        require(commands.size in 1..MAX_COMMANDS) {
            "Tutor visual program must contain one to $MAX_COMMANDS commands"
        }
        durationSeconds?.requireFiniteProgramValue("Duration", MIN_DURATION_SECONDS..MAX_DURATION_SECONDS)
        xUnit?.requireTutorVisualUnit("X axis unit")
        yUnit?.requireTutorVisualUnit("Y axis unit")

        val parameterIds = parameters.map(TutorVisualParameter::parameterId)
        require(parameterIds.distinct().size == parameterIds.size) {
            "Tutor visual program parameter ids must be unique"
        }
        val commandIds = commands.map(TutorVisualCommand::commandId)
        requireUniqueTutorSceneIds(sceneId, parameterIds + commandIds)

        val entities = commands.filterIsInstance<TutorVisualEntityCommand>()
        require(entities.size <= MAX_ENTITIES) {
            "Tutor visual program exceeds the entity budget"
        }
        require(commands.filterIsInstance<TutorVisualMetricCommand>().size <= MAX_METRICS) {
            "Tutor visual program exceeds the metric budget"
        }
        require(commands.filterIsInstance<TutorVisualTableCommand>().size <= MAX_TABLES) {
            "Tutor visual program exceeds the table budget"
        }
        if (showAxes) {
            require(entities.isNotEmpty()) { "Axes require at least one visual entity" }
        }

        val entityIds = entities.map(TutorVisualEntityCommand::commandId).toSet()
        commands.forEach { command ->
            command.requiredEntityIds().forEach { entityId ->
                require(entityId in entityIds) {
                    "Tutor visual program command references an unknown entity"
                }
            }
        }
        val parameterIdSet = parameterIds.toSet()
        val expressions = commands.flatMap(TutorVisualCommand::expressions)
        require(expressions.sumOf(TutorVisualExpression::nodeCount) <= MAX_EXPRESSION_NODES) {
            "Tutor visual program exceeds the expression budget"
        }
        expressions.forEach { expression ->
            require(expression.depth() <= MAX_EXPRESSION_DEPTH) {
                "Tutor visual expression exceeds the nesting budget"
            }
            require(expression.parameterIds().all(parameterIdSet::contains)) {
                "Tutor visual expression references an unknown parameter"
            }
        }
        if (expressions.any(TutorVisualExpression::usesTime)) {
            require(durationSeconds != null) {
                "Time expressions require a bounded playback duration"
            }
        }
        if (commands.any { it is TutorVisualPathCommand }) {
            require(durationSeconds != null) {
                "A visual path requires a bounded playback duration"
            }
        }

        requireTutorSceneTextBudget(
            listOfNotNull(title, accessibilitySummary, xUnit, yUnit) +
                parameters.flatMap { listOfNotNull(it.label, it.unit) } +
                commands.flatMap(TutorVisualCommand::textParts),
        )
    }

    companion object {
        const val MAX_ACCESSIBILITY_SUMMARY_CHARS = 360
        const val MAX_PARAMETERS = 16
        const val MAX_COMMANDS = 32
        const val MAX_ENTITIES = 12
        const val MAX_METRICS = 8
        const val MAX_TABLES = 2
        const val MAX_EXPRESSION_NODES = 160
        const val MAX_EXPRESSION_DEPTH = 6
        const val MAX_TABLE_COLUMNS = 6
        const val MAX_TABLE_ROWS = 12
        const val MAX_UNIT_CHARS = 16
        const val MAX_LABEL_CHARS = 48
        const val MIN_DURATION_SECONDS = 0.5
        const val MAX_DURATION_SECONDS = 30.0
        const val MAX_ABS_INPUT_VALUE = 1_000_000_000.0
        const val MAX_ABS_RUNTIME_VALUE = 1_000_000_000_000.0
    }
}

@Serializable
data class TutorVisualParameter(
    val parameterId: String,
    val label: String,
    val value: Double,
    val unit: String? = null,
) {
    init {
        parameterId.requireTutorSceneId("Tutor visual parameter id")
        label.requireTutorSceneText(
            "Tutor visual parameter label",
            TutorVisualProgramScene.MAX_LABEL_CHARS,
            false,
        )
        value.requireFiniteProgramValue(
            "Tutor visual parameter value",
            -TutorVisualProgramScene.MAX_ABS_INPUT_VALUE..
                TutorVisualProgramScene.MAX_ABS_INPUT_VALUE,
        )
        unit?.requireTutorVisualUnit("Tutor visual parameter unit")
    }
}

@Serializable
enum class TutorVisualExpressionOperation {
    CONSTANT,
    TIME,
    PARAMETER,
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
}

/**
 * A non-executable numeric expression tree. It cannot call functions, access files, use the
 * network, allocate loops, or reference anything except the declared parameters and local time.
 */
@Serializable
data class TutorVisualExpression(
    val operation: TutorVisualExpressionOperation,
    val value: Double? = null,
    val parameterId: String? = null,
    val arguments: List<TutorVisualExpression> = emptyList(),
) {
    init {
        when (operation) {
            TutorVisualExpressionOperation.CONSTANT -> {
                requireNotNull(value).requireFiniteProgramValue(
                    "Tutor visual constant",
                    -TutorVisualProgramScene.MAX_ABS_INPUT_VALUE..
                        TutorVisualProgramScene.MAX_ABS_INPUT_VALUE,
                )
                require(parameterId == null && arguments.isEmpty())
            }
            TutorVisualExpressionOperation.TIME -> {
                require(value == null && parameterId == null && arguments.isEmpty())
            }
            TutorVisualExpressionOperation.PARAMETER -> {
                require(value == null && arguments.isEmpty())
                requireNotNull(parameterId).requireTutorSceneId("Tutor visual expression parameter id")
            }
            TutorVisualExpressionOperation.NEGATE,
            TutorVisualExpressionOperation.SIN,
            TutorVisualExpressionOperation.COS,
            TutorVisualExpressionOperation.SQRT,
            TutorVisualExpressionOperation.ABS,
            -> {
                require(value == null && parameterId == null && arguments.size == 1)
            }
            TutorVisualExpressionOperation.ADD,
            TutorVisualExpressionOperation.SUBTRACT,
            TutorVisualExpressionOperation.MULTIPLY,
            TutorVisualExpressionOperation.DIVIDE,
            TutorVisualExpressionOperation.MIN,
            TutorVisualExpressionOperation.MAX,
            -> {
                require(value == null && parameterId == null && arguments.size == 2)
            }
        }
    }

    internal fun nodeCount(): Int = 1 + arguments.sumOf(TutorVisualExpression::nodeCount)

    internal fun depth(): Int = 1 + (arguments.maxOfOrNull(TutorVisualExpression::depth) ?: 0)

    internal fun parameterIds(): Set<String> =
        listOfNotNull(parameterId).toSet() + arguments.flatMap(TutorVisualExpression::parameterIds)

    internal fun usesTime(): Boolean =
        operation == TutorVisualExpressionOperation.TIME || arguments.any(TutorVisualExpression::usesTime)

    companion object {
        fun constant(value: Double) = TutorVisualExpression(
            operation = TutorVisualExpressionOperation.CONSTANT,
            value = value,
        )

        fun time() = TutorVisualExpression(operation = TutorVisualExpressionOperation.TIME)

        fun parameter(parameterId: String) = TutorVisualExpression(
            operation = TutorVisualExpressionOperation.PARAMETER,
            parameterId = parameterId,
        )

        fun unary(
            operation: TutorVisualExpressionOperation,
            argument: TutorVisualExpression,
        ) = TutorVisualExpression(operation = operation, arguments = listOf(argument))

        fun binary(
            operation: TutorVisualExpressionOperation,
            left: TutorVisualExpression,
            right: TutorVisualExpression,
        ) = TutorVisualExpression(operation = operation, arguments = listOf(left, right))
    }
}

@Serializable
sealed interface TutorVisualCommand {
    val commandId: String
}

@Serializable
enum class TutorVisualEntityShape {
    POINT,
    CIRCLE,
    BLOCK,
}

@Serializable
@SerialName("entity")
data class TutorVisualEntityCommand(
    override val commandId: String,
    val label: String,
    val shape: TutorVisualEntityShape,
    val x: TutorVisualExpression,
    val y: TutorVisualExpression,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual entity id")
        label.requireTutorSceneText(
            "Tutor visual entity label",
            TutorVisualProgramScene.MAX_LABEL_CHARS,
            false,
        )
    }
}

@Serializable
enum class TutorVisualLineStyle {
    LINE,
    DASHED,
    ARROW,
}

@Serializable
@SerialName("link")
data class TutorVisualLinkCommand(
    override val commandId: String,
    val fromEntityId: String,
    val toEntityId: String,
    val label: String? = null,
    val style: TutorVisualLineStyle = TutorVisualLineStyle.LINE,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual link id")
        fromEntityId.requireTutorSceneId("Tutor visual link start id")
        toEntityId.requireTutorSceneId("Tutor visual link end id")
        require(fromEntityId != toEntityId) { "Tutor visual link endpoints must differ" }
        label?.requireTutorSceneText(
            "Tutor visual link label",
            TutorVisualProgramScene.MAX_LABEL_CHARS,
            false,
        )
    }
}

@Serializable
@SerialName("path")
data class TutorVisualPathCommand(
    override val commandId: String,
    val entityId: String,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual path id")
        entityId.requireTutorSceneId("Tutor visual path entity id")
    }
}

@Serializable
@SerialName("vector")
data class TutorVisualVectorCommand(
    override val commandId: String,
    val label: String,
    val originEntityId: String,
    val x: TutorVisualExpression,
    val y: TutorVisualExpression,
    val unit: String? = null,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual vector id")
        label.requireTutorSceneText(
            "Tutor visual vector label",
            TutorVisualProgramScene.MAX_LABEL_CHARS,
            false,
        )
        originEntityId.requireTutorSceneId("Tutor visual vector origin id")
        unit?.requireTutorVisualUnit("Tutor visual vector unit")
    }
}

@Serializable
@SerialName("metric")
data class TutorVisualMetricCommand(
    override val commandId: String,
    val label: String,
    val expression: TutorVisualExpression,
    val unit: String? = null,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual metric id")
        label.requireTutorSceneText(
            "Tutor visual metric label",
            TutorVisualProgramScene.MAX_LABEL_CHARS,
            false,
        )
        unit?.requireTutorVisualUnit("Tutor visual metric unit")
    }
}

@Serializable
@SerialName("note")
data class TutorVisualNoteCommand(
    override val commandId: String,
    val markdown: String,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual note id")
        markdown.requireTutorSceneText(
            "Tutor visual note",
            TutorVisualScene.MAX_ITEM_MARKDOWN_CHARS,
            true,
        )
    }
}

@Serializable
@SerialName("formula")
data class TutorVisualFormulaCommand(
    override val commandId: String,
    val formula: String,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual formula id")
        formula.requireTutorSceneFormula("Tutor visual formula")
    }
}

@Serializable
@SerialName("table")
data class TutorVisualTableCommand(
    override val commandId: String,
    val columns: List<String>,
    val rows: List<List<String>>,
) : TutorVisualCommand {
    init {
        commandId.requireTutorSceneId("Tutor visual table id")
        require(columns.size in 2..TutorVisualProgramScene.MAX_TABLE_COLUMNS) {
            "Tutor visual table must contain two to six columns"
        }
        require(rows.size in 1..TutorVisualProgramScene.MAX_TABLE_ROWS) {
            "Tutor visual table must contain one to twelve rows"
        }
        columns.forEach {
            it.requireTutorSceneText(
                "Tutor visual table heading",
                TutorVisualProgramScene.MAX_LABEL_CHARS,
                false,
            )
        }
        rows.forEach { row ->
            require(row.size == columns.size) {
                "Tutor visual table rows must match the heading count"
            }
            row.forEach {
                it.requireTutorSceneText(
                    "Tutor visual table cell",
                    TutorVisualProgramScene.MAX_LABEL_CHARS,
                    false,
                )
            }
        }
    }
}

data class TutorVisualEntityState(
    val entityId: String,
    val label: String,
    val shape: TutorVisualEntityShape,
    val x: Double,
    val y: Double,
)

data class TutorVisualVectorState(
    val commandId: String,
    val label: String,
    val originEntityId: String,
    val x: Double,
    val y: Double,
    val unit: String?,
)

data class TutorVisualMetricState(
    val commandId: String,
    val label: String,
    val value: Double,
    val unit: String?,
)

data class TutorVisualProgramFrame(
    val timeSeconds: Double,
    val entities: List<TutorVisualEntityState>,
    val vectors: List<TutorVisualVectorState>,
    val metrics: List<TutorVisualMetricState>,
)

object TutorVisualProgramEvaluator {
    fun evaluate(
        scene: TutorVisualProgramScene,
        requestedTimeSeconds: Double,
    ): TutorVisualProgramFrame =
        TutorVisualProgramRuntime.compile(scene).evaluate(requestedTimeSeconds)
}

/**
 * Compiles the immutable parts of a visual program once, so playback frames do not repeatedly
 * partition commands or rebuild the parameter lookup.
 */
class TutorVisualProgramRuntime private constructor(
    private val durationSeconds: Double,
    private val parameters: Map<String, Double>,
    private val entityCommands: List<TutorVisualEntityCommand>,
    private val vectorCommands: List<TutorVisualVectorCommand>,
    private val metricCommands: List<TutorVisualMetricCommand>,
) {
    fun evaluate(requestedTimeSeconds: Double): TutorVisualProgramFrame {
        val time = requestedTimeSeconds
            .takeIf(Double::isFinite)
            ?.coerceIn(0.0, durationSeconds)
            ?: 0.0
        val entities = entityCommands.mapNotNull { command ->
            val x = command.x.evaluate(time, parameters) ?: return@mapNotNull null
            val y = command.y.evaluate(time, parameters) ?: return@mapNotNull null
            TutorVisualEntityState(command.commandId, command.label, command.shape, x, y)
        }
        val vectors = vectorCommands.mapNotNull { command ->
            if (entities.none { it.entityId == command.originEntityId }) return@mapNotNull null
            val x = command.x.evaluate(time, parameters) ?: return@mapNotNull null
            val y = command.y.evaluate(time, parameters) ?: return@mapNotNull null
            TutorVisualVectorState(
                command.commandId,
                command.label,
                command.originEntityId,
                x,
                y,
                command.unit,
            )
        }
        val metrics = metricCommands.mapNotNull { command ->
            val value = command.expression.evaluate(time, parameters) ?: return@mapNotNull null
            TutorVisualMetricState(command.commandId, command.label, value, command.unit)
        }
        return TutorVisualProgramFrame(time, entities, vectors, metrics)
    }

    companion object {
        fun compile(scene: TutorVisualProgramScene) = TutorVisualProgramRuntime(
            durationSeconds = scene.durationSeconds ?: 0.0,
            parameters = scene.parameters.associate { it.parameterId to it.value },
            entityCommands = scene.commands.filterIsInstance<TutorVisualEntityCommand>(),
            vectorCommands = scene.commands.filterIsInstance<TutorVisualVectorCommand>(),
            metricCommands = scene.commands.filterIsInstance<TutorVisualMetricCommand>(),
        )

        private const val MIN_DIVISOR = 1e-12
    }

    private fun TutorVisualExpression.evaluate(
        timeSeconds: Double,
        parameters: Map<String, Double>,
    ): Double? {
        val evaluated = when (operation) {
            TutorVisualExpressionOperation.CONSTANT -> value
            TutorVisualExpressionOperation.TIME -> timeSeconds
            TutorVisualExpressionOperation.PARAMETER -> parameters[parameterId]
            TutorVisualExpressionOperation.ADD -> {
                val left = arguments[0].evaluate(timeSeconds, parameters) ?: return null
                val right = arguments[1].evaluate(timeSeconds, parameters) ?: return null
                left + right
            }
            TutorVisualExpressionOperation.SUBTRACT -> {
                val left = arguments[0].evaluate(timeSeconds, parameters) ?: return null
                val right = arguments[1].evaluate(timeSeconds, parameters) ?: return null
                left - right
            }
            TutorVisualExpressionOperation.MULTIPLY -> {
                val left = arguments[0].evaluate(timeSeconds, parameters) ?: return null
                val right = arguments[1].evaluate(timeSeconds, parameters) ?: return null
                left * right
            }
            TutorVisualExpressionOperation.DIVIDE -> {
                val left = arguments[0].evaluate(timeSeconds, parameters) ?: return null
                val right = arguments[1].evaluate(timeSeconds, parameters) ?: return null
                if (abs(right) < MIN_DIVISOR) null else left / right
            }
            TutorVisualExpressionOperation.NEGATE ->
                arguments.single().evaluate(timeSeconds, parameters)?.let { -it }
            TutorVisualExpressionOperation.SIN ->
                arguments.single().evaluate(timeSeconds, parameters)?.let(::sin)
            TutorVisualExpressionOperation.COS ->
                arguments.single().evaluate(timeSeconds, parameters)?.let(::cos)
            TutorVisualExpressionOperation.SQRT ->
                arguments.single().evaluate(timeSeconds, parameters)?.takeIf { it >= 0.0 }?.let(::sqrt)
            TutorVisualExpressionOperation.ABS ->
                arguments.single().evaluate(timeSeconds, parameters)?.let(::abs)
            TutorVisualExpressionOperation.MIN -> {
                val left = arguments[0].evaluate(timeSeconds, parameters) ?: return null
                val right = arguments[1].evaluate(timeSeconds, parameters) ?: return null
                min(left, right)
            }
            TutorVisualExpressionOperation.MAX -> {
                val left = arguments[0].evaluate(timeSeconds, parameters) ?: return null
                val right = arguments[1].evaluate(timeSeconds, parameters) ?: return null
                max(left, right)
            }
        }
        return evaluated?.takeIf {
            it.isFinite() && abs(it) <= TutorVisualProgramScene.MAX_ABS_RUNTIME_VALUE
        }
    }
}

private fun TutorVisualCommand.requiredEntityIds(): List<String> = when (this) {
    is TutorVisualEntityCommand,
    is TutorVisualMetricCommand,
    is TutorVisualNoteCommand,
    is TutorVisualFormulaCommand,
    is TutorVisualTableCommand,
    -> emptyList()
    is TutorVisualLinkCommand -> listOf(fromEntityId, toEntityId)
    is TutorVisualPathCommand -> listOf(entityId)
    is TutorVisualVectorCommand -> listOf(originEntityId)
}

private fun TutorVisualCommand.expressions(): List<TutorVisualExpression> = when (this) {
    is TutorVisualEntityCommand -> listOf(x, y)
    is TutorVisualVectorCommand -> listOf(x, y)
    is TutorVisualMetricCommand -> listOf(expression)
    is TutorVisualLinkCommand,
    is TutorVisualPathCommand,
    is TutorVisualNoteCommand,
    is TutorVisualFormulaCommand,
    is TutorVisualTableCommand,
    -> emptyList()
}

private fun TutorVisualCommand.textParts(): List<String> = when (this) {
    is TutorVisualEntityCommand -> listOf(label)
    is TutorVisualLinkCommand -> listOfNotNull(label)
    is TutorVisualPathCommand -> emptyList()
    is TutorVisualVectorCommand -> listOfNotNull(label, unit)
    is TutorVisualMetricCommand -> listOfNotNull(label, unit)
    is TutorVisualNoteCommand -> listOf(markdown)
    is TutorVisualFormulaCommand -> listOf(formula)
    is TutorVisualTableCommand -> columns + rows.flatten()
}

private fun String.requireTutorVisualUnit(label: String) {
    requireTutorSceneText(label, TutorVisualProgramScene.MAX_UNIT_CHARS, false)
}

private fun Double.requireFiniteProgramValue(
    label: String,
    range: ClosedFloatingPointRange<Double>,
) {
    require(isFinite()) { "$label must be finite" }
    require(this in range) { "$label is outside the supported range" }
}
