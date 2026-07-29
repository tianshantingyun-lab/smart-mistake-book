package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemOrganizationFingerprintCompatibilityTest {
    @Test
    fun legacyV1AndV2RequestBytesAndOperationFingerprintStayStable() {
        val input = legacyInput()
        val v1 = request(schemaVersion = 1, input = input)
        val v2 = request(schemaVersion = 2, input = input)
        val expectedInput =
            """{"type":"problem_organization","problemId":"problem-legacy","problemRevisionId":"revision-legacy","practiceUnitId":"unit-legacy","subject":"MATH","questionDocument":{"id":"question-legacy","title":null,"blocks":[{"type":"paragraph","id":"block-legacy","markdown":"求函数的单调区间。"}]},"relevantLearningEvidence":[],"relationCandidates":[],"knowledgeBaseNodes":[]}"""

        assertEquals(
            """{"schemaVersion":1,"requestId":"request-legacy","input":$expectedInput,"occurredAtEpochMillis":123,"egressManifest":null}""",
            ModelTaskCodec.encodeRequest(v1),
        )
        assertEquals(
            """{"schemaVersion":2,"requestId":"request-legacy","input":$expectedInput,"occurredAtEpochMillis":123,"egressManifest":null}""",
            ModelTaskCodec.encodeRequest(v2),
        )
        assertEquals(
            "1336be9b352ba60853470b5f6c90563bd69466bf309a58566d7bd6621c5562d7",
            ModelTaskFingerprint.of(v1),
        )
        assertEquals(
            "fe0923d14ac024349d027dd5af6e29ef07899aa25ba8ed8fa31290ce359a3a3e",
            ModelTaskFingerprint.of(v2),
        )
        assertEquals(
            "388e631bfde829ebc11741a5be3e1a2d4e51755debd415e85fcb964625994af5",
            ModelTaskLogicalOperationFingerprint.of(input),
        )
    }

    @Test
    fun persistedSchemaOneSuccessRemainsReadableWhileCurrentRequestsRejectItsPlan() {
        val legacyRequest = request(schemaVersion = 1, input = legacyInput())
        val legacyOutput = ProblemOrganizationOutput(
            problemId = "problem-legacy",
            problemRevisionId = "revision-legacy",
            practiceUnitId = "unit-legacy",
            plan = ProblemOrganizationPlan(
                summaryMarkdown = "这道题考查函数单调性。",
                reviewPriorityMarkdown = "按原有安排复习。",
                targetedEvidenceLabels = emptyList(),
                classifications = listOf(
                    ProblemClassificationSuggestion(
                        dimension = ClassificationDimension.KNOWLEDGE,
                        displayName = "函数单调性",
                        rationaleMarkdown = "需要判断函数的增减区间。",
                        confidence = 0.9,
                    ),
                ),
                relations = emptyList(),
                schemaVersion = 1,
            ),
            modelVersion = "legacy-model-v1",
        )
        val persistedRequest =
            ModelTaskCodec.decodeRequest(ModelTaskCodec.encodeRequest(legacyRequest))
        val persistedOutput =
            ModelTaskCodec.decodeOutput(ModelTaskCodec.encodeOutput(legacyOutput))

        val snapshot = successfulSnapshot(persistedRequest, persistedOutput)

        assertEquals(ModelTaskStatus.SUCCEEDED, snapshot.status)
        assertEquals(1, snapshot.request.schemaVersion)
        assertEquals(1, (snapshot.output as ProblemOrganizationOutput).plan.schemaVersion)

        val currentRequest = request(
            schemaVersion = ModelTaskRequest.CURRENT_SCHEMA_VERSION,
            input = legacyInput(),
        )
        assertTrue(
            ModelTaskCompletionIssueCode.ORGANIZATION_ATOMIC_DECOMPOSITION_REQUIRED in
                ModelTaskCompletionValidator.validate(currentRequest, legacyOutput)
                    .mapTo(hashSetOf(), ModelTaskCompletionIssue::code),
        )
        assertTrue(
            runCatching { successfulSnapshot(currentRequest, legacyOutput) }.isFailure,
        )
    }

    private fun legacyInput() = ProblemOrganizationInput(
        problemId = "problem-legacy",
        problemRevisionId = "revision-legacy",
        practiceUnitId = "unit-legacy",
        subject = SubjectKind.MATH,
        questionDocument = QuestionDocument(
            id = "question-legacy",
            blocks = listOf(
                ContentBlock.Paragraph("block-legacy", "求函数的单调区间。"),
            ),
        ),
        relevantLearningEvidence = emptyList(),
        relationCandidates = emptyList(),
    )

    private fun successfulSnapshot(
        request: ModelTaskRequest,
        output: ModelTaskOutput,
    ) = ModelTaskSnapshot(
        taskId = "legacy-success",
        request = request,
        requestFingerprint = ModelTaskFingerprint.of(request),
        status = ModelTaskStatus.SUCCEEDED,
        stateVersion = 3,
        stage = ModelTaskStage.COMPLETE,
        userMessage = "整理完成",
        attemptCount = 1,
        output = output,
        createdAtEpochMillis = 100,
        updatedAtEpochMillis = 200,
    )

    private fun request(
        schemaVersion: Int,
        input: ProblemOrganizationInput,
    ) = ModelTaskRequest(
        schemaVersion = schemaVersion,
        requestId = "request-legacy",
        input = input,
        occurredAtEpochMillis = 123,
    )
}
