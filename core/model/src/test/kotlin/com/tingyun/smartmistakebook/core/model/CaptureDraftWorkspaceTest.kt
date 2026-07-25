package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDraftWorkspaceTest {
    @Test
    fun codecPreservesStructuredChoicesFormulaFigureAndEvidence() {
        val workspace = workspace()

        val encoded = CaptureDraftWorkspaceCodec.encode(workspace)
        val restored = CaptureDraftWorkspaceCodec.decode(encoded)

        assertEquals(workspace, restored)
        assertEquals(
            CaptureDraftWorkspaceFingerprint.of(workspace),
            CaptureDraftWorkspaceFingerprint.of(restored),
        )
        assertTrue(restored.workingDocument.document.blocks[0] is ContentBlock.ChoiceGroup)
        assertTrue(restored.workingDocument.document.blocks[1] is ContentBlock.Formula)
        assertTrue(restored.workingDocument.document.blocks[2] is ContentBlock.Figure)
        assertEquals(3, restored.workingDocument.blockEvidence.size)
    }

    @Test
    fun validatorRejectsEditedBlockOutsideDocumentAndInvalidCandidateFingerprint() {
        val invalid = workspace().copy(
            userEditedBlockIds = setOf("missing"),
            baseCandidateFingerprint = "not-a-sha256",
        )

        val issues = CaptureDraftWorkspaceValidator.validate(invalid).map { it.code }.toSet()

        assertTrue(CaptureDraftWorkspaceIssueCode.INVALID_EDITED_BLOCK_ID in issues)
        assertTrue(CaptureDraftWorkspaceIssueCode.INVALID_BASE_CANDIDATE_FINGERPRINT in issues)
        assertTrue(runCatching { CaptureDraftWorkspaceCodec.encode(invalid) }.isFailure)
    }

    private fun workspace(): CaptureDraftWorkspace {
        val blocks = listOf(
            ContentBlock.ChoiceGroup(
                id = "choice",
                promptMarkdown = "函数在何处递增？",
                choices = listOf(
                    StructuredChoice(id = "a", markdown = "\$x<0\$"),
                    StructuredChoice(id = "b", markdown = "\$x>0\$"),
                ),
                selectedChoiceId = "b",
            ),
            ContentBlock.Formula(
                id = "formula",
                latex = "f'(x)=2x",
                alternativeText = "f 撇 x 等于二 x",
            ),
            ContentBlock.Figure(
                id = "figure",
                title = "函数图像",
                alternativeText = "开口向上的抛物线",
                schema = FigureSchema.Cartesian(
                    xAxis = FigureAxis(-2.0, 2.0, "x"),
                    yAxis = FigureAxis(0.0, 4.0, "y"),
                    polylines = listOf(
                        FigurePolyline(
                            id = "curve",
                            points = listOf(
                                FigureCoordinate(-1.0, 1.0),
                                FigureCoordinate(0.0, 0.0),
                                FigureCoordinate(1.0, 1.0),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val document = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-draft",
                title = "函数单调性",
                blocks = blocks,
            ),
            blockEvidence = blocks.mapIndexed { index, block ->
                QuestionBlockEvidence(
                    blockId = block.id,
                    sourceAssetId = "asset-1",
                    sourceRegion = NormalizedSourceRegion(
                        left = 0.05,
                        top = index * 0.25,
                        right = 0.95,
                        bottom = index * 0.25 + 0.2,
                    ),
                    writingLayer = if (block.id == "figure") {
                        WritingLayer.DIAGRAM
                    } else {
                        WritingLayer.PRINTED
                    },
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                )
            },
        )
        return CaptureDraftWorkspace(
            subject = SubjectKind.MATH.name,
            workingDocument = document,
            editorMode = CaptureDraftEditorMode.STRUCTURED_DOCUMENT,
            userEditedFields = setOf(
                CaptureDraftEditedField.STRUCTURE,
                CaptureDraftEditedField.SUBJECT,
            ),
            userEditedBlockIds = blocks.map(ContentBlock::id).toSet(),
            baseCandidateFingerprint = "a".repeat(64),
            finalConfirmationRequest = CaptureFinalConfirmationRequestIdentity(
                requestId = "confirm-1",
                occurredAtEpochMillis = 1_234,
            ),
        )
    }
}
