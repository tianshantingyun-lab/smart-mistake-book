package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorTeachingReferenceTest {
    @Test
    fun `worked examples are valid bounded teaching context for a learner supplied question`() {
        val reference = workedExample()

        val input = TutorPlanInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = "MATH",
            questionDocument = question(),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            reviewedTeachingReferences = listOf(reference),
        )

        assertEquals(KnowledgeTeachingMaterialType.WORKED_EXAMPLE, reference.materialType)
        assertTrue(reference.contentMarkdown.contains("完整解答"))
        assertEquals(listOf(reference), input.reviewedTeachingReferences)
    }

    @Test
    fun `complete solutions and derivations are teaching forms without assessment fields`() {
        val references = listOf(
            workedExample().copy(
                materialId = "teaching:math:function-monotonicity:complete-solution:v1",
                materialType = KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
            ),
            workedExample().copy(
                materialId = "teaching:math:function-monotonicity:derivation:v1",
                materialType = KnowledgeTeachingMaterialType.DERIVATION,
            ),
        )

        val input = TutorPlanInput(
            sessionId = "session-1",
            draftRevisionNumber = 1,
            subject = "MATH",
            questionDocument = question(),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            reviewedTeachingReferences = references,
        )

        assertEquals(
            listOf(
                KnowledgeTeachingMaterialType.COMPLETE_SOLUTION,
                KnowledgeTeachingMaterialType.DERIVATION,
            ),
            input.reviewedTeachingReferences.map(TutorTeachingReference::materialType),
        )
    }

    @Test
    fun `teaching context cannot cross the current subject`() {
        val failure = runCatching {
            TutorPlanInput(
                sessionId = "session-1",
                draftRevisionNumber = 1,
                subject = "PHYSICS",
                questionDocument = question(),
                relevantLearningEvidence = emptyList(),
                projectionIsCurrent = true,
                reviewedTeachingReferences = listOf(workedExample()),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `schema three tutoring manifests remain valid without reviewed knowledge disclosure`() {
        val legacyDisclosure = setOf(
            ModelEgressDataClass.CONFIRMED_QUESTION_DOCUMENT,
            ModelEgressDataClass.RELEVANT_LEARNING_EVIDENCE,
            ModelEgressDataClass.QUESTION_LEARNING_EVIDENCE,
            ModelEgressDataClass.STUDENT_TUTOR_MESSAGE,
            ModelEgressDataClass.TUTOR_CONVERSATION_CONTEXT,
        )

        val manifest = ModelEgressManifest(
            schemaVersion = 3,
            authorizationId = "authorization-1",
            subjectId = "session-1",
            purpose = ModelEgressPurpose.TUTORING,
            authorizedTaskKinds = setOf(ModelTaskKind.TUTOR_PLAN),
            providerId = "provider",
            modelId = "model",
            providerConfigurationVersion = "configuration-v1",
            promptPolicyVersion = "legacy-tutor-policy",
            approvedAtEpochMillis = 1,
            assets = emptyList(),
            disclosedData = legacyDisclosure,
            prohibitedData = ModelEgressDataClass.entries.toSet() - legacyDisclosure,
        )

        assertEquals(3, manifest.schemaVersion)
        assertTrue(ModelEgressDataClass.SUBJECT_KNOWLEDGE_BASE !in manifest.disclosedData)
    }

    private fun workedExample() = TutorTeachingReference(
        materialId = "teaching:math:function-monotonicity:worked-example:v1",
        subject = "MATH",
        materialType = KnowledgeTeachingMaterialType.WORKED_EXAMPLE,
        title = "由图象写出单调区间",
        summaryMarkdown = "先找转折位置，再按横坐标从左到右判断增减。",
        applicabilityMarkdown = "适用于题目给出函数图象并要求写单调区间。",
        contentMarkdown = "完整解答：先读出分界点，再分别写出递增区间和递减区间。",
        boundaryMarkdown = "区间端点是否包含，必须服从当前题的定义域和题目约定。",
        knowledgeNodeIds = listOf("knowledge:math:function-monotonicity"),
    )

    private fun question() = QuestionDocument(
        id = "question-1",
        blocks = listOf(ContentBlock.Paragraph("stem", "根据图象写出函数的单调区间。")),
    )
}
