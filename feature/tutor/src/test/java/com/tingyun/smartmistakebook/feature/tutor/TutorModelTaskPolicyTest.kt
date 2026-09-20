package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.domain.ConfirmedTutorSession
import com.tingyun.smartmistakebook.core.domain.MistakeDetail
import com.tingyun.smartmistakebook.core.domain.MistakeDetailIdentity
import com.tingyun.smartmistakebook.core.domain.MistakeDetailState
import com.tingyun.smartmistakebook.core.domain.MistakeSourceSet
import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.domain.StudyQuestionMemory
import com.tingyun.smartmistakebook.core.domain.TutorConversationReference
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorConversationMemory
import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRecency
import com.tingyun.smartmistakebook.core.model.TutorEvidenceLevel
import com.tingyun.smartmistakebook.core.model.TutorKnowledgeEvidence
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorQuestionReviewStatus
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorTurnHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorToolName
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.studentAuthorizedSolutionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import com.tingyun.smartmistakebook.core.domain.TUTOR_TOOL_DECLARATIONS
import com.tingyun.smartmistakebook.core.domain.tutorRoundToolAvailable
import org.junit.Assert.assertTrue
import org.junit.Test

class TutorModelTaskPolicyTest {
    @Test
    fun everyPersistedInFlightStatusRequiresAnExplicitTutorResumePath() {
        assertTrue(ModelTaskStatus.WAITING_FOR_MODEL.isTutorExecutionPending())
        assertTrue(ModelTaskStatus.QUEUED.isTutorExecutionPending())
        assertTrue(ModelTaskStatus.RUNNING.isTutorExecutionPending())
        assertTrue(ModelTaskStatus.STREAMING.isTutorExecutionPending())
        assertFalse(ModelTaskStatus.SUCCEEDED.isTutorExecutionPending())
        assertFalse(ModelTaskStatus.RETRYABLE_FAILURE.isTutorExecutionPending())
    }

    @Test
    fun externalAgentRequestsCarryConsentAndNoPerItemManifest() {
        val question = session().toTutorQuestionContext()
        val plan = buildTutorPlanRequest(
            question = question,
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = "consented-plan",
            occurredAtEpochMillis = 100,
        )
        val respond = buildTutorRespondRequest(
            question = question,
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = "consented-respond",
            occurredAtEpochMillis = 100,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "这一步为什么？",
            visibleTutorContextMarkdown = "先看符号。",
            priorMessages = emptyList(),
        )

        assertTrue(plan.agentConsentGranted)
        assertTrue(respond.agentConsentGranted)
        assertTrue(plan.egressManifest == null)
        assertTrue(respond.egressManifest == null)
    }

    @Test
    fun requestIdIncludesPromptPolicyVersionSoChangedPromptsCannotReuseAnOldTask() {
        val requestId = tutorPlanRequestId(
            session = session(),
            provider = provider(),
            attempt = 0,
        )

        assertTrue(requestId.contains(":$TUTOR_PROMPT_POLICY_VERSION:"))
    }

    @Test
    fun planRequestIdLengthPrefixesAdjacentTurnMarkdown() {
        val first = tutorPlanRequestId(
            question = session().toTutorQuestionContext(),
            provider = provider(),
            attempt = 0,
            priorTurns = listOf(turn(stem = "a\nb", choice = "c")),
        )
        val second = tutorPlanRequestId(
            question = session().toTutorQuestionContext(),
            provider = provider(),
            attempt = 0,
            priorTurns = listOf(turn(stem = "a", choice = "b\nc")),
        )

        assertNotEquals(first, second)
    }

    @Test
    fun planRequestIdLengthPrefixesExactEarlierStudentMessages() {
        val first = tutorPlanRequestId(
            question = session().toTutorQuestionContext(),
            provider = provider(),
            attempt = 0,
            cycleOrdinal = 2,
            priorCycleStudentMessages = listOf("a\nb", "c"),
        )
        val second = tutorPlanRequestId(
            question = session().toTutorQuestionContext(),
            provider = provider(),
            attempt = 0,
            cycleOrdinal = 2,
            priorCycleStudentMessages = listOf("a", "b\nc"),
        )

        assertNotEquals(first, second)
    }

    @Test
    fun planRequestIdUsesStrongProviderConfigurationFingerprint() {
        check("Aa".hashCode() == "BB".hashCode())

        val first = tutorPlanRequestId(
            session = session(),
            provider = provider(configurationVersion = "Aa"),
            attempt = 0,
        )
        val second = tutorPlanRequestId(
            session = session(),
            provider = provider(configurationVersion = "BB"),
            attempt = 0,
        )

        assertNotEquals(first, second)
    }

    @Test
    fun savedMistakeUsesStableMemoryForExactRevisionAndSeparatesChangedRevision() {
        val first = savedMistakeTutorQuestion(savedMistakeState("revision-3", revisionNumber = 3))
        val reopened = savedMistakeTutorQuestion(savedMistakeState("revision-3", revisionNumber = 3))
        val corrected = savedMistakeTutorQuestion(savedMistakeState("revision-4", revisionNumber = 4))

        assertEquals(first.sessionId, reopened.sessionId)
        assertEquals(first.questionDocument, reopened.questionDocument)
        assertFalse(first.sessionId == corrected.sessionId)
        assertEquals(4, corrected.revisionNumber)
    }

    @Test
    fun savedCapturedMistakeReusesTheConversationThatCreatedIt() {
        val question = savedMistakeTutorQuestion(
            savedMistakeState(
                problemRevisionId = "revision-3",
                revisionNumber = 3,
                tutorConversation = TutorConversationReference(
                    sessionId = "tutor-session-before-save",
                    questionRevisionNumber = 2,
                ),
            ),
        )

        assertEquals("tutor-session-before-save", question.sessionId)
        assertEquals(2, question.revisionNumber)
    }

    @Test
    fun savedMistakeTutorRequestBindsExactQuestionDocumentWithoutSourceAssets() {
        val question = savedMistakeTutorQuestion(
            state = savedMistakeState("revision-3", revisionNumber = 3),
            relatedKnowledgeNodeIds = setOf("knowledge-current"),
        )
        val request = buildTutorPlanRequest(
            question = question,
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = "saved-mistake-request",
            occurredAtEpochMillis = 20,
        )

        val input = request.input as TutorPlanInput
        assertEquals(question.sessionId, input.sessionId)
        assertEquals(question.revisionNumber, input.draftRevisionNumber)
        assertEquals(question.questionDocument.document, input.questionDocument)
        assertEquals(setOf("knowledge-current"), question.relatedKnowledgeNodeIds)
        assertTrue(request.agentConsentGranted)
        assertTrue(request.egressManifest == null)
    }

    @Test
    fun requestDisclosesOnlyQuestionRelatedKnowledgeUnderAgentConsent() {
        val request = buildTutorPlanRequest(
            question = session().toTutorQuestionContext().copy(
                relatedKnowledgeNodeIds = setOf("node-1", "node-3"),
            ),
            profile = StudyProfileOverview(
                hasLearningEvidence = true,
                recordedAttemptCount = 28,
                weaknesses = listOf(
                    StudyKnowledgeSummary("node-2", "二次函数", MasteryStatus.LEARNING, 0.42),
                    StudyKnowledgeSummary("node-1", "导数符号", MasteryStatus.CONFLICTED, 0.18),
                    StudyKnowledgeSummary("other-subject", "遗传规律", MasteryStatus.LEARNING, 0.11),
                ),
                strengths = listOf(
                    StudyKnowledgeSummary("node-3", "一次函数", MasteryStatus.MASTERED, 0.92),
                ),
            ),
            provider = provider(),
            requestId = "tutor-request",
            occurredAtEpochMillis = 10,
        )

        val input = request.input as TutorPlanInput
        assertEquals(
            listOf("node-1", "node-3"),
            input.relevantLearningEvidence.map { it.knowledgeNodeId },
        )
        assertTrue(request.agentConsentGranted)
        assertTrue(request.egressManifest == null)
        assertFalse(input.relevantLearningEvidence.any { it.knowledgeNodeId == "node-2" })
        assertFalse(input.relevantLearningEvidence.any { it.knowledgeNodeId == "other-subject" })
    }

    @Test
    fun learningTimelineIsDisclosedAsBoundedRecencyInsteadOfRawTimestamps() {
        val day = 86_400_000L
        val now = 100L * day
        val request = buildTutorPlanRequest(
            question = session().toTutorQuestionContext().copy(
                relatedKnowledgeNodeIds = setOf("node-strong"),
            ),
            profile = StudyProfileOverview(
                hasLearningEvidence = true,
                strengths = listOf(
                    StudyKnowledgeSummary(
                        knowledgeNodeId = "node-strong",
                        displayName = "判断导数符号",
                        status = MasteryStatus.MASTERED,
                        conservativeMasteryScore = 0.93,
                        evidenceMass = 140.0,
                        independentCorrectObservationCount = 140,
                        lastEvidenceAtEpochMillis = now - 2L * day,
                        lastIndependentErrorAtEpochMillis = now - 40L * day,
                    ),
                ),
            ),
            provider = provider(),
            requestId = "bounded-timeline-request",
            occurredAtEpochMillis = now,
        )

        val evidence = (request.input as TutorPlanInput).relevantLearningEvidence.single()
        assertEquals(100.0, evidence.evidenceMass, 0.0)
        assertEquals(100, evidence.independentCorrectObservationCount)
        assertEquals(TutorEvidenceRecency.WITHIN_7_DAYS, evidence.latestEvidenceRecency)
        assertEquals(
            TutorEvidenceRecency.WITHIN_90_DAYS,
            evidence.latestIndependentErrorRecency,
        )
    }

    @Test
    fun capturedQuestionReceivesOnlyBoundedSameSubjectGlobalMemoryBeforeClassification() {
        val request = buildTutorPlanRequest(
            question = session().toTutorQuestionContext(),
            profile = StudyProfileOverview(
                hasLearningEvidence = true,
                weaknesses = listOf(
                    StudyKnowledgeSummary(
                        "math-weak",
                        "函数单调性",
                        MasteryStatus.LEARNING,
                        0.28,
                        lastEvidenceAtEpochMillis = 9,
                        subject = SubjectKind.MATH,
                    ),
                    StudyKnowledgeSummary(
                        "biology-node",
                        "遗传规律",
                        MasteryStatus.LEARNING,
                        0.18,
                        subject = SubjectKind.BIOLOGY,
                    ),
                ),
                strengths = listOf(
                    StudyKnowledgeSummary(
                        "math-strong",
                        "一次函数",
                        MasteryStatus.MASTERED,
                        0.92,
                        lastEvidenceAtEpochMillis = 10,
                        subject = SubjectKind.MATH,
                    ),
                ),
            ),
            provider = provider(),
            requestId = "tutor-unrelated-profile-request",
            occurredAtEpochMillis = 11,
        )

        val input = request.input as TutorPlanInput
        assertEquals(
            listOf("math-weak", "math-strong"),
            input.relevantLearningEvidence.map(TutorKnowledgeEvidence::knowledgeNodeId),
        )
        assertFalse(
            input.relevantLearningEvidence.any { it.knowledgeNodeId == "biology-node" },
        )
    }

    @Test
    fun classifiedQuestionStillReceivesBoundedSameSubjectGlobalMemory() {
        val request = buildTutorPlanRequest(
            question = session().toTutorQuestionContext().copy(
                relatedKnowledgeNodeIds = setOf("math-related"),
            ),
            profile = StudyProfileOverview(
                hasLearningEvidence = true,
                weaknesses = listOf(
                    StudyKnowledgeSummary(
                        "math-global",
                        "基础函数性质",
                        MasteryStatus.LEARNING,
                        0.34,
                        subject = SubjectKind.MATH,
                    ),
                    StudyKnowledgeSummary(
                        "math-related",
                        "当前题相关知识",
                        MasteryStatus.LEARNING,
                        0.45,
                        subject = SubjectKind.MATH,
                    ),
                    StudyKnowledgeSummary(
                        "physics-global",
                        "牛顿第二定律",
                        MasteryStatus.CONFLICTED,
                        0.12,
                        subject = SubjectKind.PHYSICS,
                    ),
                ),
                strengths = listOf(
                    StudyKnowledgeSummary(
                        "math-foundation",
                        "一次函数",
                        MasteryStatus.MASTERED,
                        0.94,
                        subject = SubjectKind.MATH,
                    ),
                ),
            ),
            provider = provider(),
            requestId = "classified-question-global-memory-request",
            occurredAtEpochMillis = 11,
        )

        val evidence = (request.input as TutorPlanInput).relevantLearningEvidence
        assertEquals(
            listOf("math-related", "math-global", "math-foundation"),
            evidence.map(TutorKnowledgeEvidence::knowledgeNodeId),
        )
        assertFalse(evidence.any { it.knowledgeNodeId == "physics-global" })
    }

    @Test
    fun sameSubjectGlobalMemoryIsBoundedAndPrioritizesCurrentConflicts() {
        val now = 20L * 86_400_000L
        val weaknesses = buildList {
            repeat(9) { index ->
                add(
                    StudyKnowledgeSummary(
                        knowledgeNodeId = "math-learning-$index",
                        displayName = "待巩固知识 $index",
                        status = MasteryStatus.LEARNING,
                        conservativeMasteryScore = 0.1 + index * 0.01,
                        lastEvidenceAtEpochMillis = now - index,
                        subject = SubjectKind.MATH,
                    ),
                )
            }
            add(
                StudyKnowledgeSummary(
                    knowledgeNodeId = "math-conflicted",
                    displayName = "近期出现矛盾的知识",
                    status = MasteryStatus.CONFLICTED,
                    conservativeMasteryScore = 0.8,
                    lastEvidenceAtEpochMillis = now,
                    lastIndependentErrorAtEpochMillis = now,
                    subject = SubjectKind.MATH,
                ),
            )
        }
        val strengths = List(6) { index ->
            StudyKnowledgeSummary(
                knowledgeNodeId = "math-mastered-$index",
                displayName = "已掌握知识 $index",
                status = MasteryStatus.MASTERED,
                conservativeMasteryScore = 0.9 + index * 0.01,
                lastEvidenceAtEpochMillis = now - index,
                subject = SubjectKind.MATH,
            )
        }
        val request = buildTutorPlanRequest(
            question = session().toTutorQuestionContext(),
            profile = StudyProfileOverview(
                hasLearningEvidence = true,
                weaknesses = weaknesses,
                strengths = strengths,
            ),
            provider = provider(),
            requestId = "bounded-subject-memory-request",
            occurredAtEpochMillis = now,
        )

        val evidence = (request.input as TutorPlanInput).relevantLearningEvidence
        assertEquals(12, evidence.size)
        assertEquals("math-conflicted", evidence.first().knowledgeNodeId)
        assertEquals(8, evidence.count { it.level != TutorEvidenceLevel.MASTERED })
        assertEquals(4, evidence.count { it.level == TutorEvidenceLevel.MASTERED })
    }

    @Test
    fun staleProjectionDoesNotPresentOldStrengthsAsMastered() {
        val request = buildTutorPlanRequest(
            question = session().toTutorQuestionContext().copy(
                relatedKnowledgeNodeIds = setOf("node-old"),
            ),
            profile = StudyProfileOverview(
                hasLearningEvidence = true,
                projectionIsCurrent = false,
                strengths = listOf(
                    StudyKnowledgeSummary("node-old", "旧强项", MasteryStatus.MASTERED, 0.95),
                ),
            ),
            provider = provider(),
            requestId = "tutor-stale-request",
            occurredAtEpochMillis = 12,
        )

        val input = request.input as TutorPlanInput
        assertFalse(input.projectionIsCurrent)
        assertTrue(input.relevantLearningEvidence.isEmpty())
    }

    @Test
    fun exactQuestionMemoryIsBoundedAndExplainsWhyTheQuestionIsDue() {
        val memory = StudyQuestionMemory(
            independentRecallCount = 2,
            assistedRecallCount = 1,
            retrievalFailureCount = 3,
            answerRevealCount = 1,
            lastReviewedAtEpochMillis = 20,
            nextReviewAtEpochMillis = 80,
            retrievabilityAtSnapshot = 0.41,
            projectionIsCurrent = true,
        )
        val question = savedMistakeTutorQuestion(
            savedMistakeState("revision-3", revisionNumber = 3),
            learningMemory = memory,
        )

        val request = buildTutorPlanRequest(
            question = question,
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = "question-memory-request",
            occurredAtEpochMillis = 100,
        )

        val evidence = requireNotNull((request.input as TutorPlanInput).questionLearningEvidence)
        assertEquals(2, evidence.independentRecallCount)
        assertEquals(3, evidence.retrievalFailureCount)
        assertEquals(0.41, evidence.retentionEstimate)
        assertEquals(TutorQuestionReviewStatus.DUE, evidence.reviewStatus)
        assertTrue(request.agentConsentGranted)
        assertTrue(request.egressManifest == null)
    }

    @Test
    fun laterCycleSendsDeterministicSummaryAndExactStudentMessages() {
        val memory = TutorConversationMemory(
            completedCycleCount = 1,
            answeredTurnCount = 4,
            correctChoiceCount = 2,
            lastFeedbackMarkdown = "你已经能识别定义域，但符号变化仍不稳定。",
            lastRequestedMove = TutorMoveType.CHANGE_REPRESENTATION,
        )
        val exactMessages = listOf(
            "  我卡在配方法第二步\n",
            "为什么这里要同时加上 4？  ",
        )

        val request = buildTutorPlanRequest(
            session = session(),
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = "second-cycle-request",
            occurredAtEpochMillis = 200,
            cycleOrdinal = 2,
            priorConversationMemory = memory,
            priorCycleStudentMessages = exactMessages,
        )

        val input = request.input as TutorPlanInput
        assertEquals(2, input.cycleOrdinal)
        assertEquals(memory, input.priorConversationMemory)
        assertEquals(exactMessages, input.priorCycleStudentMessages)
        assertTrue(input.priorTurns.isEmpty())
        assertEquals(1, input.turnOrdinal)
        assertTrue(request.agentConsentGranted)
        assertTrue(request.egressManifest == null)
    }

    @Test
    fun textResponsePersistsExactCurrentQuestionContextWithItsOwnDisclosure() {
        val question = session().toTutorQuestionContext().copy(
            relatedKnowledgeNodeIds = setOf("node-related"),
        )
        val history = listOf(TutorChatHistoryEntry("这里为什么要变号？", "因为跨过零点后符号改变。"))
        val requestId = tutorRespondRequestId(
            question = question,
            provider = provider(),
            responseOrdinal = 2,
            cycleOrdinal = 2,
            turnOrdinal = 3,
            studentMessage = "我还是不懂第二步",
            visibleTutorContextMarkdown = "刚才只讲到了求导。",
            priorMessages = history,
            attempt = 0,
        )
        val request = buildTutorRespondRequest(
            question = question,
            profile = StudyProfileOverview(
                weaknesses = listOf(
                    StudyKnowledgeSummary("node-related", "导数符号", MasteryStatus.LEARNING, 0.35),
                    StudyKnowledgeSummary("node-unrelated", "遗传规律", MasteryStatus.CONFLICTED, 0.12),
                ),
            ),
            provider = provider(),
            requestId = requestId,
            occurredAtEpochMillis = 300,
            responseOrdinal = 2,
            cycleOrdinal = 2,
            turnOrdinal = 3,
            studentMessage = "我还是不懂第二步",
            visibleTutorContextMarkdown = "刚才只讲到了求导。",
            priorMessages = history,
        )

        val input = request.input as TutorRespondInput
        assertEquals(question.sessionId, input.sessionId)
        assertEquals(question.revisionNumber, input.draftRevisionNumber)
        assertEquals(question.questionDocument.document, input.questionDocument)
        assertEquals(2, input.cycleOrdinal)
        assertEquals(3, input.turnOrdinal)
        assertEquals("我还是不懂第二步", input.studentMessage)
        assertEquals(history, input.priorMessages)
        assertEquals(listOf("node-related"), input.relevantLearningEvidence.map { it.knowledgeNodeId })
        assertTrue(requestId.contains(":$TUTOR_RESPOND_PROMPT_POLICY_VERSION:"))
        assertTrue(request.agentConsentGranted)
        assertTrue(request.egressManifest == null)
    }

    /**
     * 换图必须换请求标识：同一条文本重发时若命中上一个请求，学生会看到"上一次那张图"的回复，
     * 而这次附的图根本没被看过。
     */
    @Test
    fun responseRequestIdentityChangesWhenTheAttachedImageChanges() {
        val question = session().toTutorQuestionContext()
        val withoutImage = tutorRespondRequestId(
            question = question,
            provider = provider(),
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "看看我写的这一步。",
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
            attempt = 0,
        )
        val withImage = tutorRespondRequestId(
            question = question,
            provider = provider(),
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "看看我写的这一步。",
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
            studentImageAssetIds = listOf("asset-aaa"),
            attempt = 0,
        )
        val withOtherImage = tutorRespondRequestId(
            question = question,
            provider = provider(),
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "看看我写的这一步。",
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
            studentImageAssetIds = listOf("asset-bbb"),
            attempt = 0,
        )

        assertFalse(withoutImage == withImage)
        assertFalse(withImage == withOtherImage)
    }

    @Test
    fun responseRequestCarriesTheAttachedImageIdsInSelectionOrder() {
        val request = buildTutorRespondRequest(
            question = session().toTutorQuestionContext(),
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = "tutor-respond:images",
            occurredAtEpochMillis = 10,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "看看我写的这一步。",
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
            studentImageAssetIds = listOf("asset-aaa", "asset-bbb"),
        )

        val input = request.input as TutorRespondInput
        assertEquals(listOf("asset-aaa", "asset-bbb"), input.studentImageAssetRefs)
        // 带图即申报需要图片能力；纯文本仍不申报，文本模型照常可用。
        assertTrue(input.requestsImageBytes)
        assertFalse((request.input as TutorRespondInput).copy(studentImageAssetRefs = emptyList()).requestsImageBytes)
    }

    @Test
    fun responseRequestIdentityChangesWhenTheStudentMessageChanges() {
        val question = session().toTutorQuestionContext()
        val first = tutorRespondRequestId(
            question = question,
            provider = provider(),
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "解释第二步",
            visibleTutorContextMarkdown = "先求导。",
            priorMessages = emptyList(),
            attempt = 0,
        )
        val changed = tutorRespondRequestId(
            question = question,
            provider = provider(),
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "换一种方法解释第二步",
            visibleTutorContextMarkdown = "先求导。",
            priorMessages = emptyList(),
            attempt = 0,
        )

        assertFalse(first == changed)
    }

    @Test
    fun visibleContextNeverIncludesHiddenSolutionOrAlternateMethod() {
        val output = TutorPlanOutput(
            sessionId = "session-1",
            draftRevisionNumber = 2,
            questionDocumentId = "document-1",
            plan = TutorTurnPlan(
                openingMarkdown = "先看导数的符号。",
                solutionMarkdown = "这是隐藏的完整讲解。",
                alternateMethodMarkdown = "这是隐藏的另一种方法。",
                difficultyReasonMarkdown = "关键在符号变化。",
                targetedEvidenceLabels = emptyList(),
                inferredKnowledgeLabels = listOf("导数"),
            ),
            modelVersion = "model-v1",
        )

        val hidden = visibleTutorContextMarkdown(
            output = output,
            response = null,
            answerWasExposed = false,
        )
        assertTrue("先看导数的符号。" in hidden)
        assertFalse("隐藏的完整讲解" in hidden)
        assertFalse("隐藏的另一种方法" in hidden)

        val unlockedResponse = TutorTurnResponse(
            sessionId = "session-1",
            questionDocumentId = "document-1",
            revisionNumber = 2,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            diagnosticStemMarkdown = null,
            selectedChoiceId = null,
            selectedChoiceMarkdown = null,
            selectionWasCorrect = null,
            feedbackMarkdown = null,
            requestedMove = TutorMoveType.CHANGE_REPRESENTATION,
            solutionRevealed = true,
            submittedAtEpochMillis = 10,
            updatedAtEpochMillis = 11,
        )
        val unlockedOnly = visibleTutorContextMarkdown(
            output = output,
            response = unlockedResponse,
            answerWasExposed = false,
        )
        assertFalse("隐藏的完整讲解" in unlockedOnly)
        assertTrue("隐藏的另一种方法" in unlockedOnly)

        val exposureWithoutUnlock = visibleTutorContextMarkdown(
            output = output,
            response = unlockedResponse.copy(solutionRevealed = false),
            answerWasExposed = true,
        )
        assertFalse("隐藏的完整讲解" in exposureWithoutUnlock)

        val exposed = visibleTutorContextMarkdown(
            output = output,
            response = unlockedResponse,
            answerWasExposed = true,
        )
        assertTrue("隐藏的完整讲解" in exposed)
        assertTrue("隐藏的另一种方法" in exposed)
    }

    @Test
    fun visibleContextTellsTheModelTheLocallyJudgedCheckOutcome() {
        // 对错由本地按 correctChoiceId 算出；模型必须看到它，否则会把自己事先写的
        // 反馈（可能写反）当事实，而写侧门控已按本地判定否决了它的 POSITIVE 声明。
        val output = TutorPlanOutput(
            sessionId = "session-1",
            draftRevisionNumber = 2,
            questionDocumentId = "document-1",
            plan = TutorTurnPlan(
                openingMarkdown = "先看导数的符号。",
                solutionMarkdown = "这是隐藏的完整讲解。",
                alternateMethodMarkdown = "这是隐藏的另一种方法。",
                difficultyReasonMarkdown = "关键在符号变化。",
                targetedEvidenceLabels = emptyList(),
                inferredKnowledgeLabels = listOf("导数"),
            ),
            modelVersion = "model-v1",
        )
        val answeredWrong = TutorTurnResponse(
            sessionId = "session-1",
            questionDocumentId = "document-1",
            revisionNumber = 2,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            diagnosticStemMarkdown = "这一步的依据是什么？",
            selectedChoiceId = "choice-b",
            selectedChoiceMarkdown = "因为符号变了",
            selectionWasCorrect = false,
            feedbackMarkdown = "再想想。",
            requestedMove = null,
            solutionRevealed = false,
            submittedAtEpochMillis = 10,
            updatedAtEpochMillis = 11,
        )

        val wrongContext = visibleTutorContextMarkdown(
            output = output,
            response = answeredWrong,
            answerWasExposed = false,
        )
        assertTrue("系统核对：这道检查题学生答错了" in wrongContext)
        assertFalse("隐藏的完整讲解" in wrongContext)

        val rightContext = visibleTutorContextMarkdown(
            output = output,
            response = answeredWrong.copy(selectionWasCorrect = true),
            answerWasExposed = false,
        )
        assertTrue("系统核对：这道检查题学生答对了" in rightContext)
    }

    @Test
    fun modelCannotAuthorizeAnswerExposureWithoutTheStudentsExactRequest() {
        assertFalse(respondInput("这一步为什么先求导？").studentAuthorizedSolutionRequest())
        assertFalse(respondInput("先不看答案，只给提示").studentAuthorizedSolutionRequest())
        assertFalse(respondInput("我觉得这个答案不对").studentAuthorizedSolutionRequest())
        assertFalse(respondInput("答案不用说，换一种方法").studentAuthorizedSolutionRequest())
        assertFalse(respondInput("这两个答案有什么区别？").studentAuthorizedSolutionRequest())
        assertFalse(respondInput("不要直接给答案，先讲思路").studentAuthorizedSolutionRequest())
        assertFalse(respondInput("不用完整过程，只说下一步").studentAuthorizedSolutionRequest())
        assertTrue(respondInput("请告诉我答案").studentAuthorizedSolutionRequest())
        assertTrue(respondInput("答案是什么？").studentAuthorizedSolutionRequest())
        assertTrue(respondInput("请给我完整解法").studentAuthorizedSolutionRequest())
        assertTrue(respondInput("把解题过程完整写出来").studentAuthorizedSolutionRequest())
        assertTrue(respondInput("结果是多少？").studentAuthorizedSolutionRequest())
        assertTrue(
            respondInput(
                message = "继续",
                requestedMove = TutorMoveType.REVEAL_SOLUTION,
            ).studentAuthorizedSolutionRequest(),
        )
    }

    @Test
    fun respondRequestCarriesReadToolDeclarations() {
        val request = buildTutorRespondRequest(
            question = session().toTutorQuestionContext(),
            profile = StudyProfileOverview(),
            provider = provider(),
            requestId = "tutor-respond-declare-tools",
            occurredAtEpochMillis = 300,
            responseOrdinal = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = "这一步怎么来的？",
            visibleTutorContextMarkdown = null,
            priorMessages = emptyList(),
        )
        val input = request.input as TutorRespondInput
        assertTrue(
            "Respond 应声明读工具 T2/T3/T5 + 写工具 T6(MASTERY_UPDATE)",
            input.toolDeclarations.containsAll(
                listOf(
                    TutorToolName.KNOWLEDGE_READ,
                    TutorToolName.NOTEBOOK_READ,
                    TutorToolName.MASTERY_READ,
                    TutorToolName.MASTERY_UPDATE,
                ),
            ),
        )
        assertTrue(
            "Respond 应声明 T4(NOTEBOOK_WRITE)——模型可申请，但授权层要求 explicitActionRequest 确认门",
            TutorToolName.NOTEBOOK_WRITE in input.toolDeclarations,
        )
    }

    @Test
    fun lobbyRequestCarriesTheSamePageToolDeclarations() {
        val request = buildTutorLobbyRequest(
            provider = provider(),
            conversationId = "tutor-lobby-declare",
            messageOrdinal = 1,
            studentMessage = "帮我看看错题本里有没有二次函数",
            priorMessages = emptyList(),
            occurredAtEpochMillis = 300,
        )
        val input = request.input as TutorLobbyInput
        // 同页全量五个（docs/tutor-surface-unification.md §5.6）：大厅与讲题会话是同一个页面，
        // 声明集不再随轮次类型跳变——"模型能申请什么"与"本地放行什么"分成两层表达。
        assertEquals(TUTOR_TOOL_DECLARATIONS.toList(), input.toolDeclarations)
        assertEquals(
            listOf(
                TutorToolName.KNOWLEDGE_READ,
                TutorToolName.NOTEBOOK_READ,
                TutorToolName.MASTERY_READ,
                TutorToolName.MASTERY_UPDATE,
                TutorToolName.NOTEBOOK_WRITE,
            ),
            input.toolDeclarations,
        )
        // 声明全量**不等于**放行全量：无题轮（大厅）里写工具与"产出装不进本轮披露面"的读工具
        // 都由轮次级可用性拒掉，least-disclosure 与"无题不得写"两条不变量都不动。
        assertFalse(tutorRoundToolAvailable(TutorToolName.MASTERY_UPDATE, roundHasBoundQuestion = false))
        assertFalse(tutorRoundToolAvailable(TutorToolName.NOTEBOOK_WRITE, roundHasBoundQuestion = false))
        assertFalse(tutorRoundToolAvailable(TutorToolName.MASTERY_READ, roundHasBoundQuestion = false))
        assertFalse(tutorRoundToolAvailable(TutorToolName.KNOWLEDGE_READ, roundHasBoundQuestion = false))
        assertTrue(tutorRoundToolAvailable(TutorToolName.NOTEBOOK_READ, roundHasBoundQuestion = false))
    }

    private fun turn(stem: String, choice: String) = TutorTurnHistoryEntry(
        turnOrdinal = 1,
        diagnosticStemMarkdown = stem,
        selectedChoiceMarkdown = choice,
        selectionWasCorrect = false,
        feedbackMarkdown = "继续分析当前题。",
        requestedMove = TutorMoveType.DEEPEN_REASONING,
    )

    private fun respondInput(
        message: String,
        requestedMove: TutorMoveType? = null,
    ) = TutorRespondInput(
        sessionId = "session-1",
        draftRevisionNumber = 1,
        subject = "MATH",
        questionDocument = QuestionDocument(
            id = "question-1",
            blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
        ),
        relevantLearningEvidence = emptyList(),
        projectionIsCurrent = true,
        responseOrdinal = 1,
        studentMessage = message,
        requestedMove = requestedMove,
    )

    private fun provider(configurationVersion: String = "configuration-v1") = ProviderCapabilitySnapshot(
        providerId = "provider",
        providerDisplayName = "兼容模型",
        modelId = "model",
        supportedTasks = setOf(
            ModelTaskKind.TUTOR_PLAN,
            ModelTaskKind.TUTOR_RESPOND,
            ModelTaskKind.TUTOR_LOBBY,
        ),
        supportsImageInput = true,
        supportsStructuredOutput = true,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = configurationVersion,
    )

    private fun session(): ConfirmedTutorSession = ConfirmedTutorSession(
        sessionId = "session-1",
        draftId = "draft-1",
        draftRevisionNumber = 2,
        subject = "MATH",
        title = "函数单调性",
        questionDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-1",
                blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间")),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset-1",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.USER_CORRECTION,
                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                ),
            ),
        ),
        sourceImageUri = "file:///private/source.jpg",
        createdAtEpochMillis = 1,
        isSaved = false,
        errorBookEntryId = null,
    )

    private fun savedMistakeState(
        problemRevisionId: String,
        revisionNumber: Int,
        tutorConversation: TutorConversationReference? = null,
    ): MistakeDetailState.Ready = MistakeDetailState.Ready(
        detail = MistakeDetail(
            identity = MistakeDetailIdentity(
                errorBookEntryId = "entry-1",
                problemId = "problem-1",
                problemRevisionId = problemRevisionId,
                revisionNumber = revisionNumber,
                title = "函数单调性",
                subject = "MATH",
            ),
            fallbackMarkdown = "备用题面",
            source = MistakeSourceSet.Missing,
            tutorConversation = tutorConversation,
        ),
        questionDocument = session().questionDocument,
    )
}
