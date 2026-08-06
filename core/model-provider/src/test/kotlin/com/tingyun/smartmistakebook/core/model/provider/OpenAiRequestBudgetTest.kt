package com.tingyun.smartmistakebook.core.model.provider

import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelRequestBudgetExceededException
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorTeachingReference
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiRequestBudgetTest {
    @Test
    fun measuresChineseEmojiAndJsonEscapesFromTheExactSerializedString() {
        val serialized = "{\"text\":\"中文🙂\\n\\\"quoted\\\"\"}"

        val measurement = OpenAiTextRequestBudget.measure(serialized)

        assertEquals(serialized.length, measurement.serializedChars)
        assertEquals(
            serialized.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            measurement.exactUtf8Bytes,
        )
        assertEquals(measurement.exactUtf8Bytes, measurement.conservativeTokenUpperBound)
    }

    @Test
    fun zeroImageTutorPlanUsesTheExactFinalSerializedJsonForItsTextCheck() {
        val input = TutorPlanInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = SubjectKind.MATH.name,
            questionDocument = QuestionDocument(
                id = "question-1",
                blocks = listOf(
                    ContentBlock.Paragraph("stem", "中文🙂：求函数 \\\"f(x)\\\" 的零点。"),
                ),
            ),
        )
        val requestBody = serializeZeroImageRequestForBudget(
            modelId = "model-v1",
            input = input,
            authorizedDisclosures = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
        )
        val streamedRequestBody = serializeZeroImageRequestForBudget(
            modelId = "model-v1",
            input = input,
            authorizedDisclosures = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
            stream = true,
        )

        assertTrue(streamedRequestBody.contains("\"stream\":true"))
        assertEquals(
            requestBody.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            OpenAiTextRequestBudget.requireFits(requestBody, input).exactUtf8Bytes,
        )
    }

    @Test
    fun zeroImageTutorResponseMeasuresItsStreamingJsonBeforeSend() {
        val input = TutorRespondInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = SubjectKind.MATH.name,
            questionDocument = QuestionDocument(
                id = "question-1",
                blocks = listOf(ContentBlock.Paragraph("stem", "求函数的零点。")),
            ),
            responseOrdinal = 1,
            studentMessage = "请解释中文🙂和引号 \\\"这里\\\"。",
        )
        val serialized = serializeZeroImageRequestForBudget(
            modelId = "model-v1",
            input = input,
            authorizedDisclosures = ModelEgressManifest.TUTOR_RESPOND_DISCLOSURE,
            stream = true,
        )

        assertTrue(serialized.contains("\"stream\":true"))
        assertEquals(
            serialized.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            OpenAiTextRequestBudget.requireFits(serialized, input).exactUtf8Bytes,
        )
    }

    @Test
    fun oversizedTextFailsClosedBeforeTransport() {
        val input = TutorPlanInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = SubjectKind.MATH.name,
            questionDocument = QuestionDocument(
                id = "question-1",
                blocks = listOf(ContentBlock.Paragraph("stem", "求函数的零点。")),
            ),
        )
        val failure = runCatching {
            OpenAiTextRequestBudget.requireFits(
                serializedJson =
                    "a".repeat(OpenAiTextRequestBudget.MAX_TUTOR_SERIALIZED_CHARS + 1),
                input = input,
            )
        }.exceptionOrNull()

        assertTrue(failure is ModelRequestBudgetExceededException)
        assertTrue(
            OpenAiTextRequestBudget.MAX_TUTOR_EXACT_UTF8_BYTES <
                OpenAiTextRequestBudget.MAX_EXACT_UTF8_BYTES,
        )
    }

    @Test
    fun jsonEscapeInflationDropsWholeOptionalContextAndKeepsCurrentQuestion() {
        val currentQuestion = "CURRENT_QUESTION_MARKER:" + "\\\"".repeat(8_000)
        val optionalContent = "OPTIONAL_REFERENCE_MARKER:" + "\\\"".repeat(7_000)
        val input = TutorPlanInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = SubjectKind.MATH.name,
            questionDocument = QuestionDocument(
                id = "question-1",
                blocks = listOf(ContentBlock.Paragraph("stem", currentQuestion)),
            ),
            reviewedTeachingReferences = listOf(
                TutorTeachingReference(
                    materialId = "material-1",
                    subject = SubjectKind.MATH.name,
                    materialType = KnowledgeTeachingMaterialType.CONCEPT_EXPLANATION,
                    title = "函数方法",
                    summaryMarkdown = "函数分析摘要。",
                    applicabilityMarkdown = "适用于当前函数题。",
                    contentMarkdown = optionalContent,
                    boundaryMarkdown = "只用于当前题。",
                    knowledgeNodeIds = listOf("knowledge:math:function"),
                ),
            ),
        )

        val serialized = serializeZeroImageRequestForBudget(
            modelId = "model-v1",
            input = input,
            authorizedDisclosures = ModelEgressManifest.TUTOR_PLAN_DISCLOSURE,
        )

        OpenAiTextRequestBudget.requireFits(serialized, input)
        assertTrue(serialized.contains("CURRENT_QUESTION_MARKER"))
        assertTrue(!serialized.contains("OPTIONAL_REFERENCE_MARKER"))
    }
}
