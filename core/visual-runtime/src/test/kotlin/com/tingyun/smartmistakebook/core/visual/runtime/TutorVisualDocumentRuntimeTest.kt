package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualBinding
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingTarget
import com.tingyun.smartmistakebook.core.model.TutorVisualDimension
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentExpressionOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualParticleGroupElement
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualValueSource
import com.tingyun.smartmistakebook.core.model.TutorVisualVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualDocumentRuntimeTest {
    @Test
    fun fiveSeedFamiliesCompileThroughOneGenericRuntime() {
        assertEquals(5, TutorVisualSeedFixtures.all.size)

        TutorVisualSeedFixtures.all.forEach { scene ->
            val compiled = TutorVisualDocumentCompiler.compile(scene)

            assertTrue(scene.sceneId, compiled.integrity.canRender)
            assertFalse(scene.sceneId, scene.containsVisibleIllustrativeValue())
            assertEquals(scene.panels.size, compiled.panels.size)
            assertEquals(scene.elements.size, compiled.elements.size)
        }
    }

    @Test
    fun seedsExerciseTwoDimensionalThreeDimensionalAndChartPanels() {
        val panelKinds = TutorVisualSeedFixtures.all
            .flatMap { it.panels }
            .mapTo(mutableSetOf()) { it.kind }

        assertEquals(
            setOf(
                TutorVisualPanelKind.DIAGRAM_2D,
                TutorVisualPanelKind.SCENE_3D,
                TutorVisualPanelKind.SCIENTIFIC_CHART,
            ),
            panelKinds,
        )
    }

    @Test
    fun twelveAdditionalFigureFamiliesCompileWithoutRendererSpecificCode() {
        assertEquals(
            TutorVisualBenchmarkFamily.entries.toSet(),
            TutorVisualFamilyFixtures.all.mapTo(mutableSetOf()) { fixture -> fixture.family },
        )

        TutorVisualFamilyFixtures.all.forEach { fixture ->
            val compiled = TutorVisualDocumentCompiler.compile(fixture.scene)

            assertTrue(fixture.family.name, compiled.integrity.canRender)
            assertFalse(fixture.family.name, fixture.scene.containsVisibleIllustrativeValue())
        }
    }

    @Test
    fun benchmarkScaffoldCoversAtLeastNineHighSchoolSubjects() {
        val subjects = TutorVisualFamilyFixtures.all
            .flatMapTo(mutableSetOf()) { fixture -> fixture.subjects }

        assertTrue(subjects.size >= 9)
        assertTrue(
            subjects.containsAll(
                setOf(
                    "CHINESE",
                    "MATH",
                    "ENGLISH",
                    "PHYSICS",
                    "CHEMISTRY",
                    "BIOLOGY",
                    "HISTORY",
                    "GEOGRAPHY",
                    "POLITICS",
                ),
            ),
        )
    }

    @Test
    fun layoutIsDeterministicAndConnectorsReachTheirNodes() {
        val scene = TutorVisualSeedFixtures.membraneFlowBattery()
        val panel = scene.panels.single()
        val nodes = scene.elements.filterIsInstance<TutorVisual2DNodeElement>()
        val connectors = scene.elements.filterIsInstance<com.tingyun.smartmistakebook.core.model.TutorVisual2DConnectorElement>()

        val first = TutorVisualLayoutEngine.layout(panel, nodes, connectors, 1080.0, 720.0)
        val second = TutorVisualLayoutEngine.layout(panel, nodes, connectors, 1080.0, 720.0)

        assertEquals(first, second)
        assertTrue(first.connectors.values.all { it.points.size >= 2 })
        val regionCenter = first.nodes.getValue("battery_region_two").bounds.center
        assertEquals("battery_region_two", first.hitTest(regionCenter))
        assertEquals(
            null,
            first.hitTest(
                point = regionCenter,
                eligibleElementIds = emptySet(),
            ),
        )
        val connector = first.connectors.values.first()
        val connectorId = connector.element.elementId
        assertEquals(
            connectorId,
            first.hitTest(
                point = connector.points.first(),
                eligibleElementIds = setOf(connectorId),
                connectorProgressById = mapOf(connectorId to 0.25),
            ),
        )
        assertEquals(
            null,
            first.hitTest(
                point = connector.points.last(),
                eligibleElementIds = setOf(connectorId),
                connectorProgressById = mapOf(connectorId to 0.25),
            ),
        )
    }

    @Test
    fun latticeExpansionUsesInstancesRatherThanExtraModelCommands() {
        val scene = TutorVisualSeedFixtures.crystalLattice()
        val lattice = scene.elements.filterIsInstance<TutorVisualLatticeElement>().single()

        val instances = TutorVisualLatticeCompiler.expand(lattice)

        assertEquals(lattice.basis.size * lattice.repeat.count, instances.size)
        assertEquals(2, scene.elements.size)
        assertEquals(instances.map { it.elementId }.distinct().size, instances.size)
    }

    @Test
    fun riskAssessmentRequiresReviewForLatticeAndSynchronizedViews() {
        val latticeRisk = TutorVisualRiskAssessor.assess(TutorVisualSeedFixtures.crystalLattice())
        val synchronizedRisk = TutorVisualRiskAssessor.assess(TutorVisualSeedFixtures.rotatingCoil())

        assertEquals(TutorVisualRiskLevel.REVIEW_REQUIRED, latticeRisk.level)
        assertTrue("lattice" in latticeRisk.reasons)
        assertEquals(TutorVisualRiskLevel.REVIEW_REQUIRED, synchronizedRisk.level)
        assertTrue("multiple_synchronized_views" in synchronizedRisk.reasons)
    }

    @Test
    fun incompatiblePhysicalDimensionFailsCompilation() {
        val original = TutorVisualSeedFixtures.uTubeGasColumns()
        val unsafeVariable = TutorVisualVariable(
            variableId = "utube_bad_opacity",
            label = "错误的透明度输入",
            value = 1.0,
            unit = "cm",
            dimension = TutorVisualDimension.LENGTH,
            source = TutorVisualValueSource.GIVEN,
        )
        val unsafeBinding = TutorVisualBinding(
            bindingId = "utube_bad_opacity_binding",
            target = TutorVisualBindingTarget.ELEMENT,
            targetId = "utube_gas_a",
            property = TutorVisualBindingProperty.OPACITY,
            expression = TutorVisualDocumentExpression.variable(unsafeVariable.variableId),
        )

        val result = runCatching {
            TutorVisualDocumentCompiler.compile(
                original.copy(
                    variables = original.variables + unsafeVariable,
                    bindings = original.bindings + unsafeBinding,
                ),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("physical dimension"))
    }

    @Test
    fun nonFiniteRuntimeExpressionFailsCompilationInsteadOfDroppingTheBinding() {
        val original = TutorVisualSeedFixtures.uTubeGasColumns()
        val time = TutorVisualDocumentExpression.timeProgress()
        val zero = TutorVisualDocumentExpression(
            operation = TutorVisualDocumentExpressionOperation.SUBTRACT,
            arguments = listOf(time, time),
        )
        val invalidBinding = TutorVisualBinding(
            bindingId = "utube_non_finite_binding",
            target = TutorVisualBindingTarget.ELEMENT,
            targetId = "utube_gas_a",
            property = TutorVisualBindingProperty.LIQUID_LEVEL,
            expression = TutorVisualDocumentExpression(
                operation = TutorVisualDocumentExpressionOperation.DIVIDE,
                arguments = listOf(TutorVisualDocumentExpression.constant(1.0), zero),
            ),
        )

        val result = runCatching {
            TutorVisualDocumentCompiler.compile(
                original.copy(bindings = original.bindings + invalidBinding),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("finite"))
    }

    @Test
    fun denominatorThatCanCrossZeroBetweenSamplesFailsStaticValidation() {
        val original = TutorVisualSeedFixtures.uTubeGasColumns()
        val denominator = TutorVisualDocumentExpression(
            operation = TutorVisualDocumentExpressionOperation.SUBTRACT,
            arguments = listOf(
                TutorVisualDocumentExpression.timeProgress(),
                TutorVisualDocumentExpression.constant(0.25),
            ),
        )
        val invalidBinding = TutorVisualBinding(
            bindingId = "utube_between_samples_binding",
            target = TutorVisualBindingTarget.ELEMENT,
            targetId = "utube_gas_a",
            property = TutorVisualBindingProperty.LIQUID_LEVEL,
            expression = TutorVisualDocumentExpression(
                operation = TutorVisualDocumentExpressionOperation.DIVIDE,
                arguments = listOf(
                    TutorVisualDocumentExpression.constant(1.0),
                    denominator,
                ),
            ),
        )

        val result = runCatching {
            TutorVisualDocumentCompiler.compile(
                original.copy(bindings = original.bindings + invalidBinding),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("finite"))
    }

    @Test
    fun rendererBindingIntervalsFailClosedOutsideTheirConsumerContracts() {
        val original = TutorVisualSeedFixtures.rotatingCoil()

        fun compiles(property: TutorVisualBindingProperty, value: Double): Boolean {
            val binding = TutorVisualBinding(
                bindingId = "bounded_${property.name.lowercase()}",
                target = TutorVisualBindingTarget.ELEMENT,
                targetId = "coil_frame",
                property = property,
                expression = TutorVisualDocumentExpression.constant(value),
            )
            return runCatching {
                TutorVisualDocumentCompiler.compile(original.copy(bindings = listOf(binding)))
            }.isSuccess
        }

        assertFalse(compiles(TutorVisualBindingProperty.SCALE, -1.0))
        assertTrue(compiles(TutorVisualBindingProperty.SCALE, 0.0001))
        assertTrue(compiles(TutorVisualBindingProperty.X, 100_000.0))
        assertFalse(compiles(TutorVisualBindingProperty.X, 100_001.0))
    }

    @Test
    fun clampIntervalsUseTheClampedOutputAndRejectUnprovableBounds() {
        val doubledProgress = TutorVisualDocumentExpression(
            operation = TutorVisualDocumentExpressionOperation.MULTIPLY,
            arguments = listOf(
                TutorVisualDocumentExpression.timeProgress(),
                TutorVisualDocumentExpression.constant(2.0),
            ),
        )

        fun clamp(
            input: TutorVisualDocumentExpression,
            lower: TutorVisualDocumentExpression,
            upper: TutorVisualDocumentExpression,
        ) = TutorVisualDocumentExpression(
            operation = TutorVisualDocumentExpressionOperation.CLAMP,
            arguments = listOf(input, lower, upper),
        )

        fun compiles(
            scene: TutorVisualDocumentScene,
            targetId: String,
            property: TutorVisualBindingProperty,
            expression: TutorVisualDocumentExpression,
        ): Boolean {
            val binding = TutorVisualBinding(
                bindingId = "clamped_${property.name.lowercase()}",
                target = TutorVisualBindingTarget.ELEMENT,
                targetId = targetId,
                property = property,
                expression = expression,
            )
            return runCatching {
                TutorVisualDocumentCompiler.compile(scene.copy(bindings = listOf(binding)))
            }.isSuccess
        }

        val zero = TutorVisualDocumentExpression.constant(0.0)
        val one = TutorVisualDocumentExpression.constant(1.0)
        val uTube = TutorVisualSeedFixtures.uTubeGasColumns()
        assertTrue(
            compiles(
                uTube,
                "utube_gas_a",
                TutorVisualBindingProperty.OPACITY,
                clamp(doubledProgress, zero, one),
            ),
        )
        assertTrue(
            compiles(
                TutorVisualSeedFixtures.rotatingCoil(),
                "coil_frame",
                TutorVisualBindingProperty.SCALE,
                clamp(
                    doubledProgress,
                    TutorVisualDocumentExpression.constant(0.25),
                    TutorVisualDocumentExpression.constant(1.5),
                ),
            ),
        )
        assertTrue(
            compiles(
                uTube,
                "utube_left_level",
                TutorVisualBindingProperty.LIQUID_LEVEL,
                clamp(doubledProgress, zero, one),
            ),
        )
        assertFalse(
            compiles(
                uTube,
                "utube_gas_a",
                TutorVisualBindingProperty.OPACITY,
                clamp(
                    doubledProgress,
                    TutorVisualDocumentExpression.timeProgress(),
                    TutorVisualDocumentExpression.constant(0.5),
                ),
            ),
        )
        assertFalse(
            compiles(
                uTube,
                "utube_gas_a",
                TutorVisualBindingProperty.OPACITY,
                clamp(doubledProgress, one, zero),
            ),
        )
        assertFalse(
            compiles(
                uTube,
                "utube_gas_a",
                TutorVisualBindingProperty.OPACITY,
                clamp(
                    doubledProgress,
                    zero,
                    TutorVisualDocumentExpression.constant(2.0),
                ),
            ),
        )
    }

    @Test
    fun bindingWithoutRendererConsumerFailsCompilation() {
        val original = TutorVisualSeedFixtures.uTubeGasColumns()
        val unsupportedBinding = TutorVisualBinding(
            bindingId = "utube_unrendered_vector_binding",
            target = TutorVisualBindingTarget.ELEMENT,
            targetId = "utube_gas_a",
            property = TutorVisualBindingProperty.VECTOR_X,
            expression = TutorVisualDocumentExpression.constant(1.0),
        )

        val result = runCatching {
            TutorVisualDocumentCompiler.compile(
                original.copy(bindings = original.bindings + unsupportedBinding),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("renderer"))
    }

    @Test
    fun liquidLevelBindingRequiresALiquidLevelNode() {
        val original = TutorVisualSeedFixtures.uTubeGasColumns()
        val nonLiquidNode = original.elements
            .filterIsInstance<TutorVisual2DNodeElement>()
            .first { node ->
                node.kind != com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind.LIQUID_LEVEL
            }
        val unsupportedBinding = TutorVisualBinding(
            bindingId = "utube_non_liquid_level_binding",
            target = TutorVisualBindingTarget.ELEMENT,
            targetId = nonLiquidNode.elementId,
            property = TutorVisualBindingProperty.LIQUID_LEVEL,
            expression = TutorVisualDocumentExpression.constant(0.5),
        )

        val result = runCatching {
            TutorVisualDocumentCompiler.compile(
                original.copy(bindings = original.bindings + unsupportedBinding),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("renderer"))
    }

    @Test
    fun canvasParticleBudgetFailsClosedInsteadOfSilentlyCappingDraws() {
        val original = TutorVisualSeedFixtures.membraneFlowBattery()
        val particles = original.elements
            .filterIsInstance<TutorVisualParticleGroupElement>()
            .first()
        val overBudget = particles.copy(instanceCount = 301)

        val result = runCatching {
            TutorVisualDocumentCompiler.compile(
                original.copy(
                    elements = original.elements.map { element ->
                        if (element.elementId == particles.elementId) overBudget else element
                    },
                ),
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("particle budget"))
    }

    @Test
    fun cacheKeyChangesForEverySemanticInput() {
        val baseline = TutorVisualCacheKey.create("question-a", "ABCD", "model-1")

        assertEquals(
            baseline,
            TutorVisualCacheKey.create("question-a", "abcd", "model-1"),
        )
        assertNotEquals(
            baseline,
            TutorVisualCacheKey.create("question-b", "abcd", "model-1"),
        )
        assertNotEquals(
            baseline,
            TutorVisualCacheKey.create("question-a", "abcd", "model-2"),
        )
        assertNotEquals(
            baseline,
            TutorVisualCacheKey.create("question-a", "abcd", "model-1", schemaVersion = 3),
        )
        assertNotEquals(
            TutorVisualCacheKey.create(
                "question-a",
                "abcd",
                "model-1",
                providerId = "provider-a",
            ),
            TutorVisualCacheKey.create(
                "question-a",
                "abcd",
                "model-1",
                providerId = "provider-b",
            ),
        )
        assertNotEquals(
            TutorVisualCacheKey.create(
                "question-a",
                "abcd",
                "model-1",
                providerId = "provider-a",
                providerConfigurationVersion = "config-v1",
            ),
            TutorVisualCacheKey.create(
                "question-a",
                "abcd",
                "model-1",
                providerId = "provider-a",
                providerConfigurationVersion = "config-v2",
            ),
        )
        assertNotEquals(
            TutorVisualCacheKey.create(
                "question-a",
                "abcd",
                "model-1",
                requestIdentity = "plan:1:1:focus-a",
            ),
            TutorVisualCacheKey.create(
                "question-a",
                "abcd",
                "model-1",
                requestIdentity = "plan:1:1:focus-b",
            ),
        )
    }
}
