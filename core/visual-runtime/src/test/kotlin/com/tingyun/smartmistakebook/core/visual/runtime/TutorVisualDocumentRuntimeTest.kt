package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisualBinding
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingTarget
import com.tingyun.smartmistakebook.core.model.TutorVisualDimension
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualLatticeElement
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
    }
}
