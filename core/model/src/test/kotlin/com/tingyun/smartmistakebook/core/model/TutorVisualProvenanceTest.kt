package com.tingyun.smartmistakebook.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorVisualProvenanceTest {
    @Test
    fun sourceFactRemainsBoundToExactDocumentAssetAndSpan() {
        val document = document("长度为30 cm")
        val asset = asset()
        val fact = fact(document, asset, "30", 30.0)

        TutorVisualSourceFactCatalog.requireValid(document, listOf(asset), listOf(fact))

        assertTrue(
            runCatching {
                TutorVisualSourceFactCatalog.requireValid(
                    document("长度为31 cm"),
                    listOf(asset),
                    listOf(fact),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                TutorVisualSourceFactCatalog.requireValid(
                    document,
                    listOf(asset.copy(sha256 = "b".repeat(64))),
                    listOf(fact),
                )
            }.isFailure,
        )
    }

    @Test
    fun sourceFactAnchorRejectsPostMintingMutation() {
        val document = document("长度为30 cm")
        val asset = asset()
        val fact = fact(document, asset, "30", 30.0)

        assertTrue(runCatching { fact.copy(value = 31.0) }.isFailure)
        assertTrue(runCatching { fact.copy(literal = "31") }.isFailure)
    }

    @Test
    fun deterministicExtractorUsesCommittedBlockEvidenceAndKnownUnitsOnly() {
        val document = document("长度30 cm，化学式2H2，时间0.2 s")
        val asset = asset()
        val captured = CapturedQuestionDocument(
            document = document,
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = asset.assetId,
                    sourceRegion = requireNotNull(asset.selectedRegion),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        )

        val first = TutorVisualSourceFactExtractor.extract(captured, listOf(asset))
        val second = TutorVisualSourceFactExtractor.extract(captured, listOf(asset))

        assertEquals(first, second)
        assertEquals(listOf("30 cm", "0.2 s"), first.map(TutorVisualSourceFact::literal))
        assertEquals(
            listOf(TutorVisualDimension.LENGTH, TutorVisualDimension.TIME),
            first.map(TutorVisualSourceFact::dimension),
        )
        assertEquals(listOf("cm", "s"), first.map(TutorVisualSourceFact::unit))
    }

    @Test
    fun extractorBindsCommittedCartesianAxesAndPointsToStableLocators() {
        val document = QuestionDocument(
            id = "question",
            blocks = listOf(
                ContentBlock.Figure(
                    id = "chart",
                    alternativeText = "函数图像",
                    schema = FigureSchema.Cartesian(
                        xAxis = FigureAxis(0.0, 10.0),
                        yAxis = FigureAxis(-5.0, 5.0),
                        polylines = listOf(
                            FigurePolyline(
                                id = "curve",
                                points = listOf(
                                    FigureCoordinate(1.0, 2.0),
                                    FigureCoordinate(3.5, 4.0),
                                ),
                            ),
                        ),
                        points = listOf(FigurePoint(FigureCoordinate(6.0, -2.5))),
                    ),
                ),
            ),
        )
        val asset = asset()
        val captured = CapturedQuestionDocument(
            document = document,
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "chart",
                    sourceAssetId = asset.assetId,
                    sourceRegion = requireNotNull(asset.selectedRegion),
                    writingLayer = WritingLayer.DIAGRAM,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        )

        val facts = TutorVisualSourceFactExtractor.extract(captured, listOf(asset))

        TutorVisualSourceFactCatalog.requireValid(document, listOf(asset), facts)
        assertEquals(
            listOf(0.0, 10.0, -5.0, 5.0, 1.0, 2.0, 3.5, 4.0, 6.0, -2.5),
            facts.map(TutorVisualSourceFact::value),
        )
        assertEquals(
            TutorVisualSourceTextLocator(
                TutorVisualSourceTextField.CARTESIAN_POLYLINE_POINT_Y,
                listOf(0, 1),
            ),
            facts[7].locator,
        )
        assertEquals(
            TutorVisualSourceTextLocator(
                TutorVisualSourceTextField.CARTESIAN_POINT_X,
                listOf(0),
            ),
            facts[8].locator,
        )
        assertTrue(facts.all { it.dimension == TutorVisualDimension.DIMENSIONLESS && it.unit == null })
    }

    @Test
    fun extractorRejectsUncommittedEvidence() {
        val document = document("长度30 cm")
        val asset = asset()
        val captured = CapturedQuestionDocument(
            document = document,
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = asset.assetId,
                    sourceRegion = requireNotNull(asset.selectedRegion),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.LOCAL_OCR,
                    reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                    producerVersion = "ocr-v1",
                ),
            ),
        )

        assertTrue(
            runCatching {
                TutorVisualSourceFactExtractor.extract(captured, listOf(asset))
            }.isFailure,
        )
    }

    @Test
    fun persistedPreProvenanceV2StillDecodesWithoutGrantingProof() {
        val scene = TutorVisualDocumentScene(
            sceneId = "legacy_scene",
            title = "长度关系",
            panels = listOf(TutorVisualPanel("diagram", TutorVisualPanelKind.DIAGRAM_2D)),
            variables = listOf(
                TutorVisualVariable(
                    variableId = "length",
                    label = "长度",
                    value = 30.0,
                    unit = "cm",
                    dimension = TutorVisualDimension.LENGTH,
                    source = TutorVisualValueSource.GIVEN,
                ),
            ),
            elements = listOf(
                TutorVisual2DNodeElement(
                    elementId = "object",
                    panelId = "diagram",
                    kind = TutorVisual2DNodeKind.CONTAINER,
                    label = "对象",
                    valueVariableId = "length",
                ),
            ),
            steps = listOf(
                TutorVisualStep(
                    stepId = "focus",
                    label = "观察关系",
                    focusElementIds = listOf("object"),
                    displayVariableIds = listOf("length"),
                ),
            ),
            fallbackMarkdown = "按题面关系讲解",
            accessibilitySummary = "展示题面关系",
        )

        val encoded = Json.encodeToString(TutorVisualDocumentScene.serializer(), scene)
        val decoded = Json.decodeFromString(TutorVisualDocumentScene.serializer(), encoded)

        assertEquals(TutorVisualScene.DOCUMENT_SCHEMA_VERSION, decoded.schemaVersion)
        assertNull(decoded.variables.single().proof)
    }

    private fun document(text: String) = QuestionDocument(
        id = "question",
        blocks = listOf(ContentBlock.Paragraph("stem", text)),
    )

    private fun asset() = CaptureSourceAssetRef(
        assetId = "asset",
        sha256 = "a".repeat(64),
        width = 100,
        height = 100,
        pageIndex = 0,
        selectedRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
    )

    private fun fact(
        document: QuestionDocument,
        asset: CaptureSourceAssetRef,
        literal: String,
        value: Double,
    ): TutorVisualSourceFact {
        val text = (document.blocks.single() as ContentBlock.Paragraph).markdown
        val start = text.indexOf(literal)
        return TutorVisualSourceFact.create(
            factId = "length_fact",
            documentId = document.id,
            blockId = document.blocks.single().id,
            locator = TutorVisualSourceTextLocator(TutorVisualSourceTextField.PARAGRAPH_MARKDOWN),
            startUtf16 = start,
            endUtf16Exclusive = start + literal.length,
            literal = literal,
            value = value,
            unit = "cm",
            dimension = TutorVisualDimension.LENGTH,
            sourceAssetId = asset.assetId,
            sourceAssetSha256 = asset.sha256,
            sourceRegion = requireNotNull(asset.selectedRegion),
        )
    }
}
