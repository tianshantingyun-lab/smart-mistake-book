package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TutorVisualIntentTest {
    @Test
    fun explicitDiagramRequestCreatesDiagramIntent() {
        val intent = VisualIntent.detect("请画一个受力图来解释这道题")

        assertEquals(VisualIntentKind.DIAGRAM, intent?.kind)
        assertEquals("请画一个受力图来解释这道题", intent?.focusMarkdown)
    }

    @Test
    fun explicitAnimationAndThreeDimensionalRequestsAreRecognized() {
        assertEquals(
            VisualIntentKind.ANIMATION,
            VisualIntent.detect("用动画展示小球的运动过程")?.kind,
        )
        assertEquals(
            VisualIntentKind.THREE_DIMENSIONAL,
            VisualIntent.detect("给我看这个晶胞的 3D 模型")?.kind,
        )
        assertEquals(
            VisualIntentKind.VISUALIZATION,
            VisualIntent.detect("请把这组数据做成可视化")?.kind,
        )
    }

    @Test
    fun visualWordsWithoutARequestDoNotStartIndependentWork() {
        assertNull(VisualIntent.detect("题目里已经有一张受力图"))
        assertNull(VisualIntent.detect("这不是一个 3D 问题"))
    }

    @Test
    fun explicitIntentStartsVisualWorkWhenTutorOutputOmitsVisualRequest() {
        val input = TutorRespondInput(
            sessionId = "session",
            draftRevisionNumber = 1,
            subject = "PHYSICS",
            questionDocument = QuestionDocument(
                id = "question",
                blocks = listOf(ContentBlock.Paragraph("stem", "判断受力")),
            ),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = 1,
            studentMessage = "请画一个受力图解释",
            explanationMode = TutorExplanationMode.DIRECT,
        )
        val output = TutorRespondOutput(
            sessionId = input.sessionId,
            draftRevisionNumber = input.draftRevisionNumber,
            questionDocumentId = input.questionDocument.id,
            responseOrdinal = input.responseOrdinal,
            messageMarkdown = "先确定研究对象，再逐个标出力。",
            modelVersion = "model-v1",
        )
        val request = ModelTaskRequest(
            requestId = "respond-1",
            input = input,
            occurredAtEpochMillis = 1,
        )
        val task = ModelTaskSnapshot(
            taskId = request.requestId,
            request = request,
            requestFingerprint = ModelTaskFingerprint.of(request),
            status = ModelTaskStatus.SUCCEEDED,
            stateVersion = 1,
            stage = ModelTaskStage.COMPLETE,
            userMessage = "完成",
            attemptCount = 1,
            output = output,
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
        )

        val seed = tutorVisualWorkSeeds(emptyList(), listOf(task)).single()

        assertEquals("请画一个受力图解释", seed.request.focusMarkdown)
        assertEquals(output.messageMarkdown, seed.explanationMarkdown)
    }
}
