package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.domain.StudyCatalogEntry
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRoundQuestionDeclaration
import com.tingyun.smartmistakebook.core.data.model.toTutorRespond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本轮绑定声明在**线上契约**这一层的两条纪律：
 *
 * 1. 新 wire key 必须进白名单——`requireOnlyKeys` 是逐字段的，漏登记会让合法的声明被判成
 *    无效响应，多登记则会让模型多写的字段静默通过；
 * 2. 白名单在 `boundQuestion` 内部同样收口——越界字段整条响应无效，而不是被忽略。
 */
class TutorRoundQuestionWireTest {

    @Test
    fun `a declaration is parsed into the reply`() {
        val output = respond(
            """
            {
              "messageMarkdown": "先看这一步。",
              "solutionRevealed": false,
              "boundQuestion": {
                "problemId": "problem-1",
                "problemRevisionId": "revision-1",
                "anchorTerms": ["光的折射"]
              }
            }
            """.trimIndent(),
        )

        assertEquals("problem-1", output.boundQuestion?.problemId)
        assertEquals("revision-1", output.boundQuestion?.problemRevisionId)
        assertEquals(listOf("光的折射"), output.boundQuestion?.anchorTerms)
    }

    @Test
    fun `a reply without a declaration carries none`() {
        val output = respond(
            """
            {
              "messageMarkdown": "先看这一步。",
              "solutionRevealed": false
            }
            """.trimIndent(),
        )

        assertNull(output.boundQuestion)
    }

    @Test
    fun `a declaration without anchors keeps them empty and never invents one`() {
        val output = respond(
            """
            {
              "messageMarkdown": "先看这一步。",
              "solutionRevealed": false,
              "boundQuestion": {"problemId": "problem-1", "problemRevisionId": "revision-1"}
            }
            """.trimIndent(),
        )

        assertEquals("problem-1", output.boundQuestion?.problemId)
        assertTrue(output.boundQuestion?.anchorTerms.orEmpty().isEmpty())
    }

    @Test
    fun `an unknown field inside the declaration invalidates the whole reply`() {
        val rejection = runCatching {
            respond(
                """
                {
                  "messageMarkdown": "先看这一步。",
                  "solutionRevealed": false,
                  "boundQuestion": {
                    "problemId": "problem-1",
                    "problemRevisionId": "revision-1",
                    "anchorTerms": ["光的折射"],
                    "confidence": 0.9
                  }
                }
                """.trimIndent(),
            )
        }

        assertTrue(rejection.isFailure)
    }

    @Test
    fun `an unknown sibling field still invalidates the whole reply`() {
        val rejection = runCatching {
            respond(
                """
                {
                  "messageMarkdown": "先看这一步。",
                  "solutionRevealed": false,
                  "boundQuestion": {"problemId": "p", "problemRevisionId": "r"},
                  "questionOrdinal": 2
                }
                """.trimIndent(),
            )
        }

        assertTrue(rejection.isFailure)
    }

    private fun respond(reply: String) = Json.parseToJsonElement(reply)
        .let { element -> element as JsonObject }
        .toTutorRespond(input(), "model-v1")

    private fun input() = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "物理",
        questionDocument = QuestionDocument(
            id = "question-current",
            blocks = listOf(ContentBlock.Paragraph("stem", "题干")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        studentMessage = "光的折射实验这一步为什么这样",
        boundQuestionCandidates = listOf(
            com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate(
                problemId = "problem-1",
                problemRevisionId = "revision-1",
                subject = com.tingyun.smartmistakebook.core.model.SubjectKind.PHYSICS,
                title = "光的折射实验",
                questionDocument = QuestionDocument(
                    id = "question-1",
                    title = "光的折射实验",
                    blocks = listOf(ContentBlock.Paragraph("stem-1", "入射角与折射角的关系。")),
                ),
            ),
        ),
    )
}

/**
 * 本地检索打分：只排序、不判定。分档必须让"标题命中"压过"科目命中"，并让"知识层"整体压过
 * 文本层（与 `RoomMistakeOrganizationRepository.relationCandidateScore` 的相对关系一致）。
 */
class TutorRoundCandidateScoreTest {

    @Test
    fun `no terms never ranks anything`() {
        assertEquals(0, tutorRoundCandidateScore(emptyList(), entry(title = "光的折射实验")))
    }

    @Test
    fun `a title hit outranks a subject hit`() {
        val titleHit = tutorRoundCandidateScore(listOf("光的折射"), entry(title = "光的折射实验"))
        val subjectHit = tutorRoundCandidateScore(listOf("物理"), entry(title = "凸透镜成像", subject = "物理"))

        assertTrue(titleHit > subjectHit)
    }

    @Test
    fun `a knowledge hit outranks a chapter hit`() {
        val knowledgeHit = tutorRoundCandidateScore(
            listOf("电磁感应"),
            entry(title = "第 12 题", knowledgeLabels = listOf("电磁感应")),
        )
        val chapterHit = tutorRoundCandidateScore(
            listOf("电磁感应"),
            entry(title = "第 12 题", chapterLabels = listOf("电磁感应")),
        )

        assertTrue(knowledgeHit > chapterHit)
    }

    @Test
    fun `an unmatched term scores nothing`() {
        assertEquals(0, tutorRoundCandidateScore(listOf("光合作用"), entry(title = "光的折射实验")))
    }

    @Test
    fun `cjk text is segmented the same way the local full-text index segments it`() {
        val terms = tutorRoundSearchTerms("光的折射实验")

        assertTrue(terms.isNotEmpty())
        assertTrue(terms.all { term -> term.length == 1 })
    }

    @Test
    fun `latin words and digits stay whole`() {
        // 与库内索引同一套变换：CJK 逐**字**切（不是逐词），西文/数字整段保留
        // （`°` 不是 CJK，不切）。断言的是分词器**实际**的语义，不是更顺手的猜测。
        val terms = tutorRoundSearchTerms("sin30° 是多少")

        assertTrue(terms.contains("sin30°"))
        assertTrue(terms.contains("是"))
        assertTrue(terms.contains("多"))
        assertTrue(terms.contains("少"))
    }

    private fun entry(
        title: String,
        subject: String = "物理",
        chapterLabels: List<String> = emptyList(),
        knowledgeLabels: List<String> = emptyList(),
    ) = StudyCatalogEntry(
        entryId = "entry-1",
        problemId = "problem-1",
        problemRevisionId = "revision-1",
        practiceUnitId = "unit-1",
        subject = subject,
        title = title,
        problemMarkdown = "题干",
        sourceKey = null,
        isCuratedExample = false,
        chapterLabels = chapterLabels,
        knowledgeLabels = knowledgeLabels,
        nextReviewAtEpochMillis = null,
        retrievability = null,
    )
}
