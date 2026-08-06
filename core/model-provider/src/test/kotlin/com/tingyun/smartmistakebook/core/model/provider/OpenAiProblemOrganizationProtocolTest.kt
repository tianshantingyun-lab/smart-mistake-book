package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseCatalogProvenance
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.WritingLayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProblemOrganizationProtocolTest {
    @Test
    fun promptOnlyRequestsContentHierarchyClassifications() {
        val prompt = OpenAiProblemOrganizationProtocol.prompt(input())

        assertTrue(prompt.contains("CHAPTER或KNOWLEDGE"))
        assertFalse(prompt.contains("QUESTION_TYPE"))
        assertFalse(prompt.contains("ERROR_CAUSE"))
        assertFalse(prompt.contains("SAME_ERROR_PATTERN"))
        assertFalse(prompt.contains("evidence："))
    }

    @Test
    fun bothOrganizationPromptsExposeOnlyBoundedTemporaryKnowledgeAliases() {
        val nodes =
            (1..32).map { index ->
                input().knowledgeBaseNodes.single().copy(
                    knowledgeNodeId = "private-node-$index",
                    canonicalName = "根据导数符号判断单调性$index",
                    taxonomyVersion = "private-taxonomy",
                    catalogProvenance =
                        knowledgeCatalogProvenance().copy(
                            taxonomyVersion = "private-taxonomy",
                        ),
                    boundaryMarkdown = "学生可理解的边界说明。".repeat(40),
                )
            }
        val prompts =
            listOf(
                OpenAiProblemOrganizationProtocol.prompt(
                    input().copy(knowledgeBaseNodes = nodes),
                ),
                OpenAiProblemOrganizationProtocol.prompt(
                    v3Input().copy(knowledgeBaseNodes = nodes),
                ),
            )

        prompts.forEach { prompt ->
            nodes.forEach { node ->
                assertFalse(prompt.contains(node.knowledgeNodeId))
                assertFalse(prompt.contains(node.taxonomyVersion))
            }
            assertFalse(prompt.contains("\"taxonomyVersion\""))
            assertFalse(prompt.contains("\"verificationStatus\""))
            val knowledgeJson =
                prompt.substringAfter("subjectKnowledgeBase：")
                    .lineSequence()
                    .first()
            assertTrue(
                knowledgeJson.length <=
                    OpenAiProblemOrganizationProtocol.MAX_KNOWLEDGE_EGRESS_CHARS,
            )
            val entries = Json.parseToJsonElement(knowledgeJson) as JsonArray
            assertTrue(entries.isNotEmpty())
            val allowedKeys =
                setOf(
                    "alias",
                    "canonicalName",
                    "aliases",
                    "parentKnowledgeDisplayName",
                    "prerequisiteAliases",
                    "boundaryMarkdown",
                )
            assertTrue(entries.all { entry -> entry.jsonObject.keys.all(allowedKeys::contains) })
        }
    }

    @Test
    fun malformedRelationIsDiscardedWithoutDiscardingClassifications() {
        val payload = Json.parseToJsonElement(validGroundedPayload()).jsonObject

        val output = OpenAiProblemOrganizationProtocol.parse(payload, input(), "test-model")

        assertTrue(output.plan.classifications.size == 2)
        assertTrue(output.plan.relations.isEmpty())
    }

    @Test
    fun groundedAliasCannotBeReinterpretedAsAnotherAbility() {
        val validPayload = validGroundedPayload()
        val mismatches = listOf(
            validPayload.replace("根据导数符号判断单调性", "套用求导公式"),
            validPayload.replace("\"kind\":\"REASONING\"", "\"kind\":\"PROCEDURE\""),
            validPayload.replace("\"parentKnowledgeDisplayName\":\"导数\"", "\"parentKnowledgeDisplayName\":\"数列\""),
        )

        assertTrue(mismatches.all { payload ->
            runCatching {
                OpenAiProblemOrganizationProtocol.parse(
                    Json.parseToJsonElement(payload).jsonObject,
                    input(),
                    "test-model",
                )
            }.isFailure
        })
    }

    @Test
    fun v3PromptAliasesLocalIdsAndNeverDisclosesCallerCoordinates() {
        val input = v3Input()

        val prompt = OpenAiProblemOrganizationProtocol.prompt(input)

        assertTrue(prompt.contains("confirmed-question-block-1"))
        assertTrue(prompt.contains("source-1"))
        assertTrue(prompt.contains("knowledge-1"))
        assertTrue(prompt.contains("errorAttributionCandidates"))
        assertTrue(prompt.contains("problemFamily"))
        assertFalse(prompt.contains(input.problemId))
        assertFalse(prompt.contains(input.problemRevisionId))
        assertFalse(prompt.contains(input.capturedDocument.document.id))
        assertFalse(prompt.contains(input.capturedDocument.document.blocks.single().id))
        assertFalse(prompt.contains(input.sourceAssets.single().assetId))
        assertFalse(prompt.contains(input.knowledgeBaseNodes.single().knowledgeNodeId))
        assertFalse(prompt.contains("\"sourceRegion\""))
        assertFalse(prompt.contains("\"left\""))
        assertFalse(prompt.contains("\"top\""))
        assertFalse(prompt.contains("relatedCandidates"))
        assertFalse(prompt.contains("候选题"))
    }

    @Test
    fun v3ParserReconstructsExactBlockAndSourceEvidenceFromAliases() {
        val input = v3Input()

        val output = OpenAiProblemOrganizationProtocol.parse(
            Json.parseToJsonElement(validV3Payload()).jsonObject,
            input,
            "test-model",
        )
        val candidate = output.plan.errorAttributionCandidates.single()
        val evidence = candidate.evidenceRefs.single()

        assertEquals(ProblemErrorAttributionResolutionStatus.RESOLVED, candidate.resolutionStatus)
        assertEquals(input.capturedDocument.document.blocks.single().id, evidence.blockId)
        assertEquals(input.sourceAssets.single().assetId, evidence.sourceAssetId)
        assertEquals(3, output.plan.schemaVersion)
        assertEquals("quadratic_vertex_family", checkNotNull(output.plan.problemFamily).familyKey)
    }

    @Test
    fun v3ParserRejectsPayloadWithoutAReviewedProblemFamily() {
        val valid = validV3Payload()
        val withoutFamily =
            valid
                .replace(
                    """"problemFamily":{"familyKey":"quadratic_vertex_family","rationaleMarkdown":"同题不同录入与近似变式归入同一族。","confidence":0.9},""",
                    "",
                )

        assertTrue(
            runCatching {
                OpenAiProblemOrganizationProtocol.parse(
                    Json.parseToJsonElement(withoutFamily).jsonObject,
                    v3Input(),
                    "test-model",
                )
            }.isFailure,
        )
    }

    @Test
    fun v3ParserFailsClosedOnUnknownOrSqlLikeBlockKnowledgeSourceAndAtomicIds() {
        val valid = validV3Payload()
        val maliciousPayloads = listOf(
            valid.replace(
                "\"blockAlias\":\"confirmed-question-block-1\"",
                "\"blockAlias\":\"stem-private OR 1=1\"",
            ),
            valid.replace(
                "\"sourceAlias\":\"source-1\"",
                "\"sourceAlias\":\"source-1; DROP TABLE assets\"",
            ),
            valid.replace(
                "\"existingAlias\":\"knowledge-1\"",
                "\"existingAlias\":\"knowledge-1 UNION SELECT id\"",
            ),
            valid.replace(
                "\"referenceId\":\"atom-1\"",
                "\"referenceId\":\"atom-1; DELETE FROM knowledge\"",
            ),
        )

        assertTrue(
            maliciousPayloads.all { payload ->
                runCatching {
                    OpenAiProblemOrganizationProtocol.parse(
                        Json.parseToJsonElement(payload).jsonObject,
                        v3Input(),
                        "test-model",
                    )
                }.isFailure
            },
        )
    }

    @Test
    fun v3ParserRejectsUnresolvedCandidatesThatInventReferences() {
        val payload = validV3Payload()
            .replace("\"resolutionStatus\":\"RESOLVED\"", "\"resolutionStatus\":\"UNRESOLVED\"")

        assertTrue(
            runCatching {
                OpenAiProblemOrganizationProtocol.parse(
                    Json.parseToJsonElement(payload).jsonObject,
                    v3Input(),
                    "test-model",
                )
            }.isFailure,
        )
    }

    private fun validGroundedPayload() =
        """
            {
              "summaryMarkdown":"整理完成。",
              "reviewPriorityMarkdown":"适合近期复习。",
              "schemaVersion":2,
              "targetedEvidenceLabels":[],
              "classifications":[
                {"dimension":"CHAPTER","displayName":"函数","rationaleMarkdown":"所属板块。","confidence":0.9},
                {"dimension":"KNOWLEDGE","displayName":"导数","rationaleMarkdown":"核心知识点。","confidence":0.9}
              ],
              "relations":[
                {"targetAlias":"unknown","kind":"VARIANT_OF","rationaleMarkdown":"无效候选。","confidence":0.99}
              ],
              "atomicKnowledge":[
                {"referenceId":"atom-1","canonicalName":"根据导数符号判断单调性","aliases":[],"kind":"REASONING","parentKnowledgeDisplayName":"导数","existingAlias":"knowledge-1","prerequisiteReferenceIds":[],"observableOutcomeMarkdown":"能由导数符号判断增减。","boundaryMarkdown":"不包含求导计算本身。","confidence":0.9}
              ],
              "stepAttributions":[
                {"stepOrdinal":1,"stepSummaryMarkdown":"根据导数符号判断单调区间。","atomicReferenceIds":["atom-1"]}
              ],
              "groundingRequests":[]
            }
        """.trimIndent()

    private fun input() = ProblemOrganizationInput(
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "practice-1",
        subject = SubjectKind.MATH,
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        relationCandidates = emptyList(),
        knowledgeBaseNodes = listOf(
            KnowledgeBaseNodeContext(
                knowledgeNodeId = "math-derivative-sign-monotonicity",
                catalogProvenance = knowledgeCatalogProvenance(),
                subject = SubjectKind.MATH,
                canonicalName = "根据导数符号判断单调性",
                aliases = emptyList(),
                kind = KnowledgeNodeKind.REASONING,
                granularity = KnowledgeNodeGranularity.ATOMIC,
                parentCanonicalName = "导数",
                taxonomyVersion = "math-v1",
                verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                boundaryMarkdown = "不包含求导计算本身。",
            ),
        ),
    )

    private fun v3Input() = ProblemOrganizationV3Input(
        problemId = "problem-private",
        problemRevisionId = "revision-private",
        practiceUnitId = "practice-private",
        subject = SubjectKind.MATH,
        capturedDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "question-private",
                blocks = listOf(
                    ContentBlock.Paragraph("stem-private", "手写答案：函数在该区间递增。"),
                ),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem-private",
                    sourceAssetId = "asset-private",
                    sourceRegion = NormalizedSourceRegion(0.1, 0.2, 0.8, 0.9),
                    writingLayer = WritingLayer.HANDWRITTEN,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    confidence = 0.95,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        ),
        sourceAssets = listOf(
            CaptureSourceAssetRef(
                assetId = "asset-private",
                sha256 = "a".repeat(64),
                width = 1_000,
                height = 1_400,
                pageIndex = 0,
            ),
        ),
        relationCandidates = emptyList(),
        knowledgeBaseNodes = listOf(
            KnowledgeBaseNodeContext(
                knowledgeNodeId = "knowledge-private",
                catalogProvenance = knowledgeCatalogProvenance(),
                subject = SubjectKind.MATH,
                canonicalName = "根据导数符号判断单调性",
                aliases = emptyList(),
                kind = KnowledgeNodeKind.REASONING,
                granularity = KnowledgeNodeGranularity.ATOMIC,
                parentCanonicalName = "导数",
                taxonomyVersion = "math-v1",
                verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                boundaryMarkdown = "不包含求导计算本身。",
            ),
        ),
    )

    private fun knowledgeCatalogProvenance() =
        KnowledgeBaseCatalogProvenance(
            packId = "pack",
            knowledgePackVersion = "pack-v1",
            taxonomyVersion = "math-v1",
            manifestFingerprint = "a".repeat(64),
            activationGeneration = 1L,
        )

    private fun validV3Payload() =
        """
            {
              "summaryMarkdown":"整理完成。",
              "reviewPriorityMarkdown":"适合近期复习。",
              "schemaVersion":3,
              "problemFamily":{"familyKey":"quadratic_vertex_family","rationaleMarkdown":"同题不同录入与近似变式归入同一族。","confidence":0.9},
              "targetedEvidenceLabels":[],
              "classifications":[
                {"dimension":"KNOWLEDGE","displayName":"导数","rationaleMarkdown":"核心知识点。","confidence":0.9}
              ],
              "relations":[],
              "atomicKnowledge":[
                {"referenceId":"atom-1","canonicalName":"根据导数符号判断单调性","aliases":[],"kind":"REASONING","parentKnowledgeDisplayName":"导数","existingAlias":"knowledge-1","prerequisiteReferenceIds":[],"observableOutcomeMarkdown":"能由导数符号判断增减。","boundaryMarkdown":"不包含求导计算本身。","confidence":0.9}
              ],
              "stepAttributions":[
                {"stepOrdinal":1,"stepSummaryMarkdown":"根据导数符号判断单调区间。","atomicReferenceIds":["atom-1"]}
              ],
              "groundingRequests":[],
              "errorAttributionCandidates":[
                {"resolutionStatus":"RESOLVED","rationaleMarkdown":"手写作答把负号区间判断成递增区间。","confidence":0.92,"stepOrdinal":1,"atomicReferenceId":"atom-1","evidenceRefs":[{"blockAlias":"confirmed-question-block-1","sourceAlias":"source-1","evidenceKind":"STUDENT_WORK"}]}
              ]
            }
        """.trimIndent()
}
