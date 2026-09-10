package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.serialization.json.Json
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
    fun modelJudgedDifficultyTierIsReadOffTheWire() {
        // spec batch-intake-spec §2 L2：模型的难度判断是排程冷启动估时的输入，
        // 必须在协议层被真的读出来——此前 TutorDifficultyTier 全库无消费方。
        val payload = Json.parseToJsonElement(
            validGroundedPayload().replace(
                "\"summaryMarkdown\":",
                "\"difficultyTier\":\"HARD\",\"summaryMarkdown\":",
            ),
        ).jsonObject

        val output = OpenAiProblemOrganizationProtocol.parse(payload, input(), "test-model")

        assertEquals(
            com.tingyun.smartmistakebook.core.model.TutorDifficultyTier.HARD,
            output.plan.difficultyTier,
        )
    }

    @Test
    fun aDifficultyTierThatWasNotJudgedStaysNull() {
        // 未输出（旧行/本次没判）必须是 null，不得默认成中档——默认中档正是
        // 本次要消灭的失败（每道新题都被估成 180s）。
        val payload = Json.parseToJsonElement(validGroundedPayload()).jsonObject

        val output = OpenAiProblemOrganizationProtocol.parse(payload, input(), "test-model")

        assertEquals(null, output.plan.difficultyTier)
    }

    @Test
    fun anUnrecognizedDifficultyTierDoesNotSinkTheOrganization() {
        // 难度是 advisory：档位名无法识别时退回"未判"，不让一个边缘字段
        // 把整份整理结果（分类/关系/原子能力）一起掀掉。
        val payload = Json.parseToJsonElement(
            validGroundedPayload().replace(
                "\"summaryMarkdown\":",
                "\"difficultyTier\":\"VERY_HARD\",\"summaryMarkdown\":",
            ),
        ).jsonObject

        val output = OpenAiProblemOrganizationProtocol.parse(payload, input(), "test-model")

        assertEquals(null, output.plan.difficultyTier)
        assertEquals(2, output.plan.classifications.size)
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
}
