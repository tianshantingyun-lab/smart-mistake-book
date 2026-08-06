package com.tingyun.smartmistakebook.quality.visualbenchmark

import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualDocumentCompiler
import com.tingyun.smartmistakebook.core.visual.runtime.TutorVisualProvenanceContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualBenchmarkTest {
    @Test
    fun releaseDatasetRequires120DistinctImagesNineSubjectsAndTwelveFamilies() {
        val report = VisualBenchmarkDatasetValidator.validate(releaseManifest())

        assertTrue(report.issues.toString(), report.isValid)
    }

    @Test
    fun releaseDatasetDoesNotSilentlyAcceptASmokeSet() {
        val report = VisualBenchmarkDatasetValidator.validate(
            releaseManifest().copy(cases = releaseManifest().cases.take(12)),
        )

        assertFalse(report.isValid)
        assertTrue(report.issues.any { it.code == "CASE_COVERAGE" })
        assertTrue(report.issues.any { it.code == "IMAGE_COVERAGE" })
    }

    @Test
    fun privateImagePathCannotEscapeDatasetRoot() {
        val benchmarkCase = releaseManifest().cases.first()
        val manifest = releaseManifest().copy(
            cases = listOf(
                benchmarkCase.copy(
                    source = benchmarkCase.source.copy(relativePath = "../student-photo.jpg"),
                ),
            ),
        )

        val report = VisualBenchmarkDatasetValidator.validate(
            manifest,
            VisualBenchmarkRequirements.STRUCTURAL_SMOKE,
        )

        assertTrue(report.issues.any { it.code == "IMAGE_PATH" })
    }

    @Test
    fun imagePathCannotBeBlankEvenWithoutThePrivateDatasetMounted() {
        val benchmarkCase = releaseManifest().cases.first()
        val manifest = releaseManifest().copy(
            cases = listOf(
                benchmarkCase.copy(
                    source = benchmarkCase.source.copy(relativePath = ""),
                ),
            ),
        )

        val report = VisualBenchmarkDatasetValidator.validate(
            manifest,
            VisualBenchmarkRequirements.STRUCTURAL_SMOKE,
        )

        assertTrue(report.issues.any { it.code == "IMAGE_PATH" })
    }

    @Test
    fun candidateFactoryRunsTheRealVisualCompiler() {
        val benchmarkCase = releaseManifest().cases.first()
        val compiled = TutorVisualDocumentCompiler.compileForPresentation(
            scene = simpleScene(),
            provenanceContext = TutorVisualProvenanceContext(
                questionDocument = QuestionDocument(
                    id = "benchmark_question",
                    blocks = listOf(ContentBlock.Paragraph("stem", "判断关键对象关系")),
                ),
                sourceAssets = emptyList(),
                sourceFacts = emptyList(),
            ),
        )
        val attempt = VisualBenchmarkAttemptFactory.generated(
            stage = VisualBenchmarkAttemptStage.INITIAL,
            compiled = compiled,
            observation = observationFor(benchmarkCase),
        )

        assertTrue(attempt.runtimeCompiled)
        assertTrue(attempt.provenanceVerified)
        assertFalse(attempt.visibleIllustrativeValue)
    }

    @Test
    fun twoProvidersPassOnlyAfterEveryCaseIsScored() {
        val manifest = releaseManifest()
        val runs = listOf(
            passingRun("provider-a", manifest),
            passingRun("provider-b", manifest),
        )

        val report = VisualProviderBenchmarkScorer.evaluate(manifest, runs)

        assertTrue(report.issues.toString(), report.passes)
        assertEquals(2, report.providerReports.size)
        report.providerReports.forEach {
            assertEquals(1.0, it.passAfterAtMostOneRepairRate, 0.0)
        }
    }

    @Test
    fun oneRepairCanRecoverAnIncorrectInitialScene() {
        val manifest = releaseManifest()
        val firstCase = manifest.cases.first()
        val repairedCaseRun = VisualBenchmarkCaseRun(
            caseId = firstCase.caseId,
            attempts = listOf(
                generatedAttempt(
                    firstCase,
                    observationFor(firstCase).copy(objectSemanticIds = emptyList()),
                ),
                generatedAttempt(
                    firstCase,
                    observationFor(firstCase),
                    VisualBenchmarkAttemptStage.REPAIR,
                ),
            ),
        )
        val providerA = passingRun("provider-a", manifest).copy(
            cases = passingRun("provider-a", manifest).cases.map {
                if (it.caseId == firstCase.caseId) repairedCaseRun else it
            },
        )

        val report = VisualProviderBenchmarkScorer.evaluate(
            manifest,
            listOf(providerA, passingRun("provider-b", manifest)),
        )

        assertTrue(report.issues.toString(), report.passes)
        val repairedScore = report.providerReports.first().caseScores.first()
        assertEquals(VisualBenchmarkCaseOutcome.PASS, repairedScore.outcome)
        assertTrue(repairedScore.repaired)
    }

    @Test
    fun safeFallbackProtectsStudentsButDoesNotInflateGenerationCompliance() {
        val manifest = releaseManifest()
        val firstCase = manifest.cases.first()
        val providerA = passingRun("provider-a", manifest).copy(
            cases = passingRun("provider-a", manifest).cases.map {
                if (it.caseId == firstCase.caseId) {
                    VisualBenchmarkCaseRun(
                        firstCase.caseId,
                        listOf(VisualBenchmarkAttemptFactory.declined(VisualBenchmarkAttemptStage.INITIAL)),
                    )
                } else {
                    it
                }
            },
        )

        val report = VisualProviderBenchmarkScorer.evaluate(
            manifest,
            listOf(providerA, passingRun("provider-b", manifest)),
        )

        val providerReport = report.providerReports.first()
        assertEquals(119.0 / 120.0, providerReport.passAfterAtMostOneRepairRate, 0.0)
        assertEquals(1.0, providerReport.safeHandlingRate, 0.0)
    }

    @Test
    fun moreThanOneRepairIsRejected() {
        val manifest = releaseManifest()
        val firstCase = manifest.cases.first()
        val invalidRun = passingRun("provider-a", manifest).copy(
            cases = passingRun("provider-a", manifest).cases.map {
                if (it.caseId == firstCase.caseId) {
                    VisualBenchmarkCaseRun(
                        firstCase.caseId,
                        listOf(
                            generatedAttempt(firstCase, observationFor(firstCase)),
                            generatedAttempt(
                                firstCase,
                                observationFor(firstCase),
                                VisualBenchmarkAttemptStage.REPAIR,
                            ),
                            generatedAttempt(
                                firstCase,
                                observationFor(firstCase),
                                VisualBenchmarkAttemptStage.REPAIR,
                            ),
                        ),
                    )
                } else {
                    it
                }
            },
        )

        val report = VisualProviderBenchmarkScorer.evaluate(
            manifest,
            listOf(invalidRun, passingRun("provider-b", manifest)),
        )

        assertFalse(report.passes)
        assertEquals(
            VisualBenchmarkCaseOutcome.PROVIDER_FAILURE,
            report.providerReports.first().caseScores.first().outcome,
        )
    }

    @Test
    fun structurallyCompiledButUnprovenSceneIsRejected() {
        val manifest = releaseManifest()
        val firstCase = manifest.cases.first()
        val providerA = passingRun("provider-a", manifest).copy(
            cases = passingRun("provider-a", manifest).cases.map { caseRun ->
                if (caseRun.caseId == firstCase.caseId) {
                    caseRun.copy(
                        attempts = listOf(
                            caseRun.attempts.single().copy(provenanceVerified = false),
                        ),
                    )
                } else {
                    caseRun
                }
            },
        )

        val report = VisualProviderBenchmarkScorer.evaluate(
            manifest,
            listOf(providerA, passingRun("provider-b", manifest)),
        )

        assertEquals(
            VisualBenchmarkCaseOutcome.RUNTIME_REJECTED,
            report.providerReports.first().caseScores.first().outcome,
        )
    }

    private fun passingRun(
        providerId: String,
        manifest: VisualBenchmarkManifest,
    ) = VisualBenchmarkProviderRun(
        providerId = providerId,
        modelVersion = "test-model",
        cases = manifest.cases.map { benchmarkCase ->
            VisualBenchmarkCaseRun(
                benchmarkCase.caseId,
                listOf(generatedAttempt(benchmarkCase, observationFor(benchmarkCase))),
            )
        },
    )

    private fun generatedAttempt(
        benchmarkCase: VisualBenchmarkCase,
        observation: VisualBenchmarkObservation,
        stage: VisualBenchmarkAttemptStage = VisualBenchmarkAttemptStage.INITIAL,
    ) = VisualBenchmarkAttempt(
        stage = stage,
        status = VisualBenchmarkAttemptStatus.GENERATED,
        runtimeCompiled = true,
        provenanceVerified = true,
        visibleIllustrativeValue = false,
        observation = observation,
        failureReason = null,
    )

    private fun observationFor(
        benchmarkCase: VisualBenchmarkCase,
    ) = VisualBenchmarkObservation(
        objectSemanticIds = benchmarkCase.annotations.objects.map { it.semanticId },
        connections = benchmarkCase.annotations.connections,
        directions = benchmarkCase.annotations.directions,
        numericValues = benchmarkCase.annotations.numericValues,
        spatialRelations = benchmarkCase.annotations.spatialRelations,
        temporalOrder = benchmarkCase.annotations.temporalOrder,
    )

    private fun releaseManifest(): VisualBenchmarkManifest {
        val subjects = VisualBenchmarkSubject.entries
        val families = VisualFigureFamily.entries
        return VisualBenchmarkManifest(
            datasetId = "release-set",
            datasetVersion = "1.0.0",
            cases = List(120) { index ->
                val suffix = (index + 1).toString().padStart(3, '0')
                val sourceId = "source-$suffix"
                val targetId = "target-$suffix"
                VisualBenchmarkCase(
                    caseId = "case-$suffix",
                    subject = subjects[index % subjects.size],
                    family = families[index % families.size],
                    source = VisualBenchmarkImageSource(
                        relativePath = "images/case-$suffix.jpg",
                        sha256 = (index + 1).toString(16).padStart(64, '0'),
                        widthPixels = 1600,
                        heightPixels = 1200,
                    ),
                    currentQuestionPart = "当前小问 $suffix",
                    annotations = VisualBenchmarkAnnotations(
                        objects = listOf(
                            VisualBenchmarkObject(sourceId, "起点"),
                            VisualBenchmarkObject(targetId, "终点"),
                        ),
                        connections = listOf(
                            VisualBenchmarkConnection(sourceId, targetId, "相连", directed = true),
                        ),
                        directions = listOf(
                            VisualBenchmarkDirection(
                                "direction-$suffix",
                                sourceId,
                                targetId,
                                "由起点指向终点",
                            ),
                        ),
                        numericValues = listOf(
                            VisualBenchmarkNumericValue(
                                "value-$suffix",
                                canonicalValue = (index + 1).toString(),
                                unit = "u",
                                source = VisualBenchmarkValueSource.GIVEN,
                                displayRequired = true,
                            ),
                        ),
                        spatialRelations = listOf(
                            VisualBenchmarkSpatialRelation(
                                "spatial-$suffix",
                                sourceId,
                                "位于左侧",
                                targetId,
                            ),
                        ),
                        temporalOrder = listOf(
                            VisualBenchmarkTemporalRelation(
                                "temporal-$suffix",
                                "初始",
                                "开始",
                                "变化后",
                            ),
                        ),
                    ),
                )
            },
        )
    }

    private fun simpleScene() = TutorVisualDocumentScene(
        sceneId = "benchmark_scene",
        title = "基准场景",
        panels = listOf(
            TutorVisualPanel(
                panelId = "benchmark_panel",
                kind = TutorVisualPanelKind.DIAGRAM_2D,
            ),
        ),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "benchmark_node",
                panelId = "benchmark_panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "关键对象",
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "benchmark_step",
                label = "查看关键对象",
                focusElementIds = listOf("benchmark_node"),
                primaryRelationElementId = "benchmark_node",
            ),
        ),
        fallbackMarkdown = "查看关键对象。",
        accessibilitySummary = "一个关键对象。",
        provenanceSchemaVersion = TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
    )
}
