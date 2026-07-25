package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualProgramTest {
    @Test
    fun oneGenericProgramEvaluatesMotionWithoutAQuestionSpecificSceneType() {
        val time = TutorVisualExpression.time()
        val velocity = TutorVisualExpression.parameter("velocity")
        val acceleration = TutorVisualExpression.parameter("acceleration")
        val position = TutorVisualExpression.binary(
            TutorVisualExpressionOperation.ADD,
            TutorVisualExpression.binary(TutorVisualExpressionOperation.MULTIPLY, velocity, time),
            TutorVisualExpression.binary(
                TutorVisualExpressionOperation.MULTIPLY,
                TutorVisualExpression.constant(0.5),
                TutorVisualExpression.binary(
                    TutorVisualExpressionOperation.MULTIPLY,
                    acceleration,
                    TutorVisualExpression.binary(
                        TutorVisualExpressionOperation.MULTIPLY,
                        time,
                        time,
                    ),
                ),
            ),
        )
        val scene = program(
            parameters = listOf(
                TutorVisualParameter("velocity", "初速度", 3.0, "m/s"),
                TutorVisualParameter("acceleration", "加速度", 2.0, "m/s²"),
            ),
            commands = listOf(
                TutorVisualEntityCommand(
                    commandId = "object",
                    label = "小球",
                    shape = TutorVisualEntityShape.CIRCLE,
                    x = position,
                    y = TutorVisualExpression.constant(0.0),
                ),
                TutorVisualPathCommand("trajectory", "object"),
                TutorVisualMetricCommand("position", "位置", position, "m"),
            ),
            durationSeconds = 4.0,
        )

        val frame = TutorVisualProgramEvaluator.evaluate(scene, 2.0)

        assertEquals(10.0, frame.entities.single().x, 0.000_001)
        assertEquals(10.0, frame.metrics.single().value, 0.000_001)
    }

    @Test
    fun compiledRuntimeReusesOneProgramAcrossPlaybackFrames() {
        val position = TutorVisualExpression.binary(
            TutorVisualExpressionOperation.MULTIPLY,
            TutorVisualExpression.parameter("velocity"),
            TutorVisualExpression.time(),
        )
        val scene = program(
            parameters = listOf(TutorVisualParameter("velocity", "速度", 2.5, "m/s")),
            commands = listOf(
                TutorVisualEntityCommand(
                    commandId = "object",
                    label = "小球",
                    shape = TutorVisualEntityShape.CIRCLE,
                    x = position,
                    y = TutorVisualExpression.constant(0.0),
                ),
                TutorVisualMetricCommand("position", "位置", position, "m"),
            ),
            durationSeconds = 4.0,
        )
        val runtime = TutorVisualProgramRuntime.compile(scene)

        assertEquals(2.5, runtime.evaluate(1.0).entities.single().x, 0.000_001)
        assertEquals(7.5, runtime.evaluate(3.0).metrics.single().value, 0.000_001)
        assertEquals(
            TutorVisualProgramEvaluator.evaluate(scene, 2.0),
            runtime.evaluate(2.0),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun timeExpressionRequiresABoundedDuration() {
        program(
            commands = listOf(
                TutorVisualMetricCommand(
                    commandId = "clock",
                    label = "时间",
                    expression = TutorVisualExpression.time(),
                    unit = "s",
                ),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun programRejectsExpressionsDeeperThanTheRuntimeBudget() {
        var expression = TutorVisualExpression.constant(1.0)
        repeat(TutorVisualProgramScene.MAX_EXPRESSION_DEPTH) {
            expression = TutorVisualExpression.unary(
                TutorVisualExpressionOperation.ABS,
                expression,
            )
        }
        program(
            commands = listOf(
                TutorVisualMetricCommand("too-deep", "数值", expression),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun programRejectsLinksToEntitiesThatDoNotExist() {
        program(
            commands = listOf(
                TutorVisualEntityCommand(
                    commandId = "known",
                    label = "已知点",
                    shape = TutorVisualEntityShape.POINT,
                    x = TutorVisualExpression.constant(0.0),
                    y = TutorVisualExpression.constant(0.0),
                ),
                TutorVisualLinkCommand(
                    commandId = "bad-link",
                    fromEntityId = "known",
                    toEntityId = "missing",
                ),
            ),
        )
    }

    @Test
    fun runtimeDropsNonFiniteResultsInsteadOfCrashingOrDrawingThem() {
        val divisionByZero = TutorVisualExpression.binary(
            TutorVisualExpressionOperation.DIVIDE,
            TutorVisualExpression.constant(1.0),
            TutorVisualExpression.constant(0.0),
        )
        val scene = program(
            commands = listOf(
                TutorVisualMetricCommand("unsafe", "比值", divisionByZero),
            ),
        )

        assertTrue(TutorVisualProgramEvaluator.evaluate(scene, 0.0).metrics.isEmpty())
    }

    @Test
    fun currentVisualProgramSchemaRoundTripsAsDurableData() {
        val scene = program(
            commands = listOf(
                TutorVisualNoteCommand("note", "观察横轴上的位置变化。"),
                TutorVisualTableCommand(
                    commandId = "values",
                    columns = listOf("时间", "位置"),
                    rows = listOf(listOf("0 s", "0 m"), listOf("1 s", "2 m")),
                ),
            ),
        )

        val encoded = Json.encodeToString(TutorVisualProgramScene.serializer(), scene)
        val decoded = Json.decodeFromString(TutorVisualProgramScene.serializer(), encoded)

        assertEquals(scene, decoded)
    }

    private fun program(
        parameters: List<TutorVisualParameter> = emptyList(),
        commands: List<TutorVisualCommand>,
        durationSeconds: Double? = null,
    ) = TutorVisualProgramScene(
        sceneId = "program-scene",
        title = "位置怎样变化",
        accessibilitySummary = "小球沿横轴移动，位置随时间改变。",
        parameters = parameters,
        commands = commands,
        durationSeconds = durationSeconds,
        showAxes = commands.any { it is TutorVisualEntityCommand },
        xUnit = if (commands.any { it is TutorVisualEntityCommand }) "m" else null,
    )
}
