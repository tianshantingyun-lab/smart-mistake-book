package com.tingyun.smartmistakebook.core.visual.runtime

import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualBinding
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingProperty
import com.tingyun.smartmistakebook.core.model.TutorVisualBindingTarget
import com.tingyun.smartmistakebook.core.model.TutorVisualCamera
import com.tingyun.smartmistakebook.core.model.TutorVisualChartConfiguration
import com.tingyun.smartmistakebook.core.model.TutorVisualChartPoint
import com.tingyun.smartmistakebook.core.model.TutorVisualChartPointVariableProof
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesElement
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesKind
import com.tingyun.smartmistakebook.core.model.TutorVisualChartSeriesProof
import com.tingyun.smartmistakebook.core.model.TutorVisualDerivation
import com.tingyun.smartmistakebook.core.model.TutorVisualDerivationNode
import com.tingyun.smartmistakebook.core.model.TutorVisualDerivationOperation
import com.tingyun.smartmistakebook.core.model.TutorVisualDimension
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentExpression
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DElement
import com.tingyun.smartmistakebook.core.model.TutorVisualGeometry3DKind
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceFact
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceTextField
import com.tingyun.smartmistakebook.core.model.TutorVisualSourceTextLocator
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualValueProof
import com.tingyun.smartmistakebook.core.model.TutorVisualValueSource
import com.tingyun.smartmistakebook.core.model.TutorVisualVariable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualProvenanceValidatorTest {
    @Test
    fun locallyBoundGivenAndRecomputedDerivedValueCanBePresented() {
        val fixture = sourceFixture("题面给出长度为30 cm")
        val lengthFact = fixture.fact("length-fact", "30", 30.0, "cm", TutorVisualDimension.LENGTH)
        val length = givenVariable("length", "长度", 30.0, lengthFact)
        val doubled = TutorVisualVariable(
            variableId = "double_length",
            label = "加倍长度",
            value = 60.0,
            unit = "cm",
            dimension = TutorVisualDimension.LENGTH,
            source = TutorVisualValueSource.DERIVED,
            derivationMarkdown = "由已知长度加倍得到",
            proof = TutorVisualValueProof.Derived(
                TutorVisualDerivation(
                    rootNodeId = "product",
                    nodes = listOf(
                        variableNode("source", length.variableId),
                        TutorVisualDerivationNode("two", TutorVisualDerivationOperation.TWO),
                        TutorVisualDerivationNode(
                            "product",
                            TutorVisualDerivationOperation.MULTIPLY,
                            listOf("source", "two"),
                        ),
                    ),
                ),
            ),
        )
        val scene = diagramScene(listOf(length, doubled), visibleText = "长度关系")

        val compiled = TutorVisualDocumentCompiler.compileForPresentation(
            scene,
            fixture.context(listOf(lengthFact)),
        )

        assertTrue(compiled.integrity.canRender)
        assertTrue(compiled.provenance?.verifiedVariableIds?.containsAll(setOf("length", "double_length")) == true)
    }

    @Test
    fun derivedValuesAreRecomputedWithLocalUnitConversion() {
        val fixture = sourceFixture("两段长度分别为30 cm和0.2 m")
        val firstFact = fixture.fact("first-fact", "30", 30.0, "cm", TutorVisualDimension.LENGTH)
        val secondFact = fixture.fact("second-fact", "0.2", 0.2, "m", TutorVisualDimension.LENGTH)
        val first = givenVariable("first", "第一段", 30.0, firstFact)
        val second = givenVariable("second", "第二段", 0.2, secondFact)
        val total = TutorVisualVariable(
            variableId = "total",
            label = "总长度",
            value = 50.0,
            unit = "cm",
            dimension = TutorVisualDimension.LENGTH,
            source = TutorVisualValueSource.DERIVED,
            derivationMarkdown = "把两段长度换算后相加",
            proof = TutorVisualValueProof.Derived(
                TutorVisualDerivation(
                    rootNodeId = "sum",
                    nodes = listOf(
                        variableNode("first_node", first.variableId),
                        variableNode("second_node", second.variableId),
                        TutorVisualDerivationNode(
                            "sum",
                            TutorVisualDerivationOperation.ADD,
                            listOf("first_node", "second_node"),
                        ),
                    ),
                ),
            ),
        )
        val context = fixture.context(listOf(firstFact, secondFact))

        assertTrue(
            TutorVisualProvenanceValidator.validate(
                diagramScene(listOf(first, second, total), "长度关系"),
                context,
            ).canPresent,
        )
        assertTrue(
            TutorVisualProvenanceValidator.validate(
                diagramScene(listOf(first, second, total.copy(value = 30.2)), "长度关系"),
                context,
            ).issues.any { it.code == TutorVisualProvenanceIssueCode.DERIVATION_VALUE_MISMATCH },
        )
    }

    @Test
    fun forgedGivenAndLegacyUnprovenancedV2FailClosed() {
        val fixture = sourceFixture("题面给出长度为30 cm")
        val fact = fixture.fact("length-fact", "30", 30.0, "cm", TutorVisualDimension.LENGTH)
        val forged = givenVariable("length", "长度", 31.0, fact)
        val legacy = forged.copy(value = 30.0, proof = null)
        val legacyScene = diagramScene(listOf(legacy), "长度关系").copy(
            provenanceSchemaVersion = null,
        )

        val forgedReport = TutorVisualProvenanceValidator.validate(
            diagramScene(listOf(forged), "长度关系"),
            fixture.context(listOf(fact)),
        )
        assertTrue(forgedReport.issues.any { it.code == TutorVisualProvenanceIssueCode.SOURCE_FACT_MISMATCH })
        assertTrue(
            runCatching {
                TutorVisualDocumentCompiler.compileForPresentation(
                    legacyScene,
                    fixture.context(listOf(fact)),
                )
            }.isFailure,
        )
        assertTrue(TutorVisualDocumentCompiler.compile(legacyScene).integrity.canRender)
    }

    @Test
    fun derivationCyclesAndAllModelAuthoredNumericTextAreRejected() {
        val fixture = sourceFixture("题面给出长度为30 cm")
        val fact = fixture.fact("length-fact", "30", 30.0, "cm", TutorVisualDimension.LENGTH)
        val first = derivedReference("first", "second")
        val second = derivedReference("second", "first")
        val cycleReport = TutorVisualProvenanceValidator.validate(
            diagramScene(listOf(first, second), "长度关系"),
            fixture.context(listOf(fact)),
        )
        assertTrue(cycleReport.issues.any { it.code == TutorVisualProvenanceIssueCode.DERIVATION_CYCLE })

        val given = givenVariable("length", "长度", 30.0, fact)
        val textReport = TutorVisualProvenanceValidator.validate(
            diagramScene(listOf(given), "结论仍是30"),
            fixture.context(listOf(fact)),
        )
        assertTrue(textReport.issues.any { it.code == TutorVisualProvenanceIssueCode.UNSAFE_VISIBLE_NUMBER })
        assertTrue(textReport.evidenceEligibleElementIds.isEmpty())
    }

    @Test
    fun illustrativeAnimationCanRenderButCannotAuthorizeLearningEvidence() {
        val fixture = sourceFixture("题面没有需要显示的数值")
        val animation = TutorVisualVariable(
            variableId = "animation_progress",
            label = "运动进度",
            value = 1.0,
            dimension = TutorVisualDimension.DIMENSIONLESS,
            source = TutorVisualValueSource.ILLUSTRATIVE,
            display = false,
            proof = TutorVisualValueProof.Illustrative(),
        )
        val scene = diagramScene(
            variables = listOf(animation),
            visibleText = "运动关系",
            bindings = listOf(
                TutorVisualBinding(
                    bindingId = "fade",
                    target = TutorVisualBindingTarget.ELEMENT,
                    targetId = "node",
                    property = TutorVisualBindingProperty.OPACITY,
                    expression = TutorVisualDocumentExpression.variable(animation.variableId),
                ),
            ),
        )

        val report = TutorVisualProvenanceValidator.validate(scene, fixture.context(emptyList()))

        assertTrue(report.canPresent)
        assertFalse("node" in report.evidenceEligibleElementIds)

        val legacyWithoutProof = animation.copy(proof = null)
        assertFalse(
            TutorVisualProvenanceValidator.validate(
                diagramScene(listOf(legacyWithoutProof), "运动关系"),
                fixture.context(emptyList()),
            ).canPresent,
        )
    }

    @Test
    fun illustrativePanelMotionCannotAuthorizeChildElementsAsEvidence() {
        val fixture = sourceFixture("题面没有需要显示的数值")
        val animation = TutorVisualVariable(
            variableId = "camera_progress",
            label = "镜头进度",
            value = 1.0,
            dimension = TutorVisualDimension.DIMENSIONLESS,
            source = TutorVisualValueSource.ILLUSTRATIVE,
            display = false,
            proof = TutorVisualValueProof.Illustrative(),
        )
        val scene = TutorVisualDocumentScene(
            sceneId = "camera_scene",
            title = "运动关系",
            panels = listOf(
                TutorVisualPanel(
                    panelId = "space",
                    kind = TutorVisualPanelKind.SCENE_3D,
                    camera = TutorVisualCamera(),
                ),
            ),
            variables = listOf(animation),
            elements = listOf(
                TutorVisualGeometry3DElement(
                    elementId = "object",
                    panelId = "space",
                    kind = TutorVisualGeometry3DKind.SPHERE,
                    label = "对象",
                ),
            ),
            bindings = listOf(
                TutorVisualBinding(
                    bindingId = "panel_motion",
                    target = TutorVisualBindingTarget.PANEL,
                    targetId = "space",
                    property = TutorVisualBindingProperty.CAMERA_DISTANCE,
                    expression = TutorVisualDocumentExpression.variable(animation.variableId),
                ),
            ),
            steps = listOf(TutorVisualStep("focus", "观察运动", focusElementIds = listOf("object"))),
            fallbackMarkdown = "按题面关系继续讲解",
            accessibilitySummary = "展示题面对象之间的关系",
            provenanceSchemaVersion = TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
        )

        val report = TutorVisualProvenanceValidator.validate(scene, fixture.context(emptyList()))

        assertTrue(report.canPresent)
        assertFalse("object" in report.evidenceEligibleElementIds)
    }

    @Test
    fun chartProofsDistinguishExactCurveAndTrendData() {
        val fixture = sourceFixture("横坐标从0到2")
        val zeroFact = fixture.fact("zero-fact", "0", 0.0, null, TutorVisualDimension.DIMENSIONLESS)
        val twoFact = fixture.fact("two-fact", "2", 2.0, null, TutorVisualDimension.DIMENSIONLESS)
        val zero = givenVariable("zero", "起点", 0.0, zeroFact, unit = null, dimension = TutorVisualDimension.DIMENSIONLESS)
        val two = givenVariable("two_value", "终点", 2.0, twoFact, unit = null, dimension = TutorVisualDimension.DIMENSIONLESS)
        val exact = TutorVisualChartSeriesElement(
            elementId = "exact",
            panelId = "chart",
            label = "已知点",
            kind = TutorVisualChartSeriesKind.SCATTER,
            points = listOf(TutorVisualChartPoint(0.0, 2.0)),
            source = TutorVisualValueSource.GIVEN,
            proof = TutorVisualChartSeriesProof.ProvenPoints(
                listOf(TutorVisualChartPointVariableProof("zero", "two_value")),
            ),
        )
        val curve = TutorVisualChartSeriesElement(
            elementId = "curve",
            panelId = "chart",
            label = "推导曲线",
            kind = TutorVisualChartSeriesKind.LINE,
            points = listOf(
                TutorVisualChartPoint(0.0, 0.0),
                TutorVisualChartPoint(1.0, 2.0),
                TutorVisualChartPoint(2.0, 4.0),
            ),
            source = TutorVisualValueSource.DERIVED,
            proof = TutorVisualChartSeriesProof.DerivedCurve(
                xStartVariableId = "zero",
                xEndVariableId = "two_value",
                yDimension = TutorVisualDimension.DIMENSIONLESS,
                sampleCount = 3,
                yDerivation = TutorVisualDerivation(
                    rootNodeId = "product",
                    nodes = listOf(
                        TutorVisualDerivationNode("x", TutorVisualDerivationOperation.PARAMETER),
                        TutorVisualDerivationNode("factor", TutorVisualDerivationOperation.TWO),
                        TutorVisualDerivationNode(
                            "product",
                            TutorVisualDerivationOperation.MULTIPLY,
                            listOf("x", "factor"),
                        ),
                    ),
                ),
            ),
        )
        val trend = TutorVisualChartSeriesElement(
            elementId = "trend",
            panelId = "chart",
            label = "变化趋势",
            kind = TutorVisualChartSeriesKind.LINE,
            points = listOf(TutorVisualChartPoint(0.0, 0.2), TutorVisualChartPoint(1.0, 0.8)),
            source = TutorVisualValueSource.ILLUSTRATIVE,
            proof = TutorVisualChartSeriesProof.IllustrativeTrend(),
        )
        val context = fixture.context(listOf(zeroFact, twoFact))

        assertTrue(TutorVisualProvenanceValidator.validate(chartScene(listOf(zero, two), listOf(exact, curve)), context).canPresent)
        assertFalse(TutorVisualProvenanceValidator.validate(chartScene(emptyList(), listOf(trend)), context).canPresent)
        assertTrue(
            TutorVisualProvenanceValidator.validate(
                chartScene(emptyList(), listOf(trend)),
                context,
                TutorVisualProvenancePolicy(supportsSafeIllustrativeTrends = true),
            ).canPresent,
        )
    }

    private fun diagramScene(
        variables: List<TutorVisualVariable>,
        visibleText: String,
        bindings: List<TutorVisualBinding> = emptyList(),
    ) = TutorVisualDocumentScene(
        sceneId = "diagram_scene",
        title = visibleText,
        panels = listOf(TutorVisualPanel("diagram", TutorVisualPanelKind.DIAGRAM_2D)),
        variables = variables,
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "node",
                panelId = "diagram",
                kind = TutorVisual2DNodeKind.CONTAINER,
                label = "对象",
                valueVariableId = variables.firstOrNull { it.display }?.variableId,
            ),
        ),
        bindings = bindings,
        steps = listOf(
            TutorVisualStep(
                stepId = "focus",
                label = "观察关系",
                focusElementIds = listOf("node"),
                displayVariableIds = variables.filter(TutorVisualVariable::display).map(TutorVisualVariable::variableId).take(4),
            ),
        ),
        fallbackMarkdown = "按题面关系继续讲解",
        accessibilitySummary = "展示题面对象之间的关系",
        provenanceSchemaVersion = TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
    )

    private fun chartScene(
        variables: List<TutorVisualVariable>,
        series: List<TutorVisualChartSeriesElement>,
    ) = TutorVisualDocumentScene(
        sceneId = "chart_scene",
        title = "函数关系",
        panels = listOf(
            TutorVisualPanel(
                panelId = "chart",
                kind = TutorVisualPanelKind.SCIENTIFIC_CHART,
                chart = TutorVisualChartConfiguration(
                    xAxisLabel = "横轴",
                    leftAxisLabel = "纵轴",
                    allowTouchReadout = false,
                ),
            ),
        ),
        variables = variables,
        elements = series,
        steps = listOf(TutorVisualStep("focus", "观察变化", highlightedSeriesIds = series.map { it.elementId })),
        fallbackMarkdown = "根据题面条件分析曲线",
        accessibilitySummary = "展示量之间的变化关系",
        provenanceSchemaVersion = TutorVisualDocumentScene.CURRENT_PROVENANCE_SCHEMA_VERSION,
    )

    private fun derivedReference(id: String, dependencyId: String) = TutorVisualVariable(
        variableId = id,
        label = id,
        value = 1.0,
        dimension = TutorVisualDimension.DIMENSIONLESS,
        source = TutorVisualValueSource.DERIVED,
        derivationMarkdown = "由相关量推出",
        proof = TutorVisualValueProof.Derived(
            TutorVisualDerivation(
                rootNodeId = "${id}_source",
                nodes = listOf(variableNode("${id}_source", dependencyId)),
            ),
        ),
    )

    private fun variableNode(id: String, variableId: String) = TutorVisualDerivationNode(
        nodeId = id,
        operation = TutorVisualDerivationOperation.VARIABLE,
        variableId = variableId,
    )

    private fun givenVariable(
        id: String,
        label: String,
        value: Double,
        fact: TutorVisualSourceFact,
        unit: String? = fact.unit,
        dimension: TutorVisualDimension = fact.dimension,
    ) = TutorVisualVariable(
        variableId = id,
        label = label,
        value = value,
        unit = unit,
        dimension = dimension,
        source = TutorVisualValueSource.GIVEN,
        proof = TutorVisualValueProof.Given(fact.factId),
    )

    private data class SourceFixture(
        val document: QuestionDocument,
        val asset: CaptureSourceAssetRef,
        val region: NormalizedSourceRegion,
    ) {
        fun fact(
            id: String,
            literal: String,
            value: Double,
            unit: String?,
            dimension: TutorVisualDimension,
        ): TutorVisualSourceFact {
            val text = (document.blocks.single() as ContentBlock.Paragraph).markdown
            val start = text.indexOf(literal)
            require(start >= 0)
            return TutorVisualSourceFact.create(
                factId = id,
                documentId = document.id,
                blockId = document.blocks.single().id,
                locator = TutorVisualSourceTextLocator(TutorVisualSourceTextField.PARAGRAPH_MARKDOWN),
                startUtf16 = start,
                endUtf16Exclusive = start + literal.length,
                literal = literal,
                value = value,
                unit = unit,
                dimension = dimension,
                sourceAssetId = asset.assetId,
                sourceAssetSha256 = asset.sha256,
                sourceRegion = region,
            )
        }

        fun context(facts: List<TutorVisualSourceFact>) = TutorVisualProvenanceContext(
            questionDocument = document,
            sourceAssets = listOf(asset),
            sourceFacts = facts.distinctBy(TutorVisualSourceFact::factId),
        )
    }

    private fun sourceFixture(text: String): SourceFixture {
        val region = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0)
        return SourceFixture(
            document = QuestionDocument(
                id = "question",
                blocks = listOf(ContentBlock.Paragraph("stem", text)),
            ),
            asset = CaptureSourceAssetRef(
                assetId = "asset",
                sha256 = "a".repeat(64),
                width = 1000,
                height = 1000,
                pageIndex = 0,
                selectedRegion = region,
            ),
            region = region,
        )
    }
}
