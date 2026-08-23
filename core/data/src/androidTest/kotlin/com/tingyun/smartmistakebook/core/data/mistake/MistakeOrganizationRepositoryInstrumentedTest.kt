package com.tingyun.smartmistakebook.core.data.mistake

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.ErrorBookEntrySeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.PracticeUnitSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemRevisionSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.database.TransitionModelTaskCommand
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationRelationKey
import com.tingyun.smartmistakebook.core.model.BindingAcceptanceSource
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationInput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemRelationKind
import com.tingyun.smartmistakebook.core.model.ProblemRelationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.RelatedProblemCandidate
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MistakeOrganizationRepositoryInstrumentedTest {
    private lateinit var database: StudyDatabasePort
    private lateinit var repository: RoomMistakeOrganizationRepository
    private lateinit var context: Context
    private lateinit var databaseName: String

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "organization-repository-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        database = StudyDatabaseFactory.open(context, databaseName)
        database.seedFixture(seed())
        repository = RoomMistakeOrganizationRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun persistedSuccessIsAppliedWithLocalAuthorityCompletionTimeAndIdempotency() = runBlocking {
        persistSuccess("request-auto", completeOutput(), completedAt = 400)

        val first = repository.applySuccessfulOrganization("request-auto")
        val replay = repository.applySuccessfulOrganization("request-auto")
        val stored = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertTrue(first.applied)
        assertTrue(first.created)
        assertFalse(replay.created)
        assertEquals(setOf("CHAPTER", "KNOWLEDGE"), stored.classifications.map { it.dimension }.toSet())
        assertTrue(stored.classifications.all { it.acceptanceSource == "LOCAL_POLICY_ACCEPTED" })
        assertTrue(stored.classifications.all { it.acceptedAtEpochMillis == 400L })
        assertEquals(setOf(ATOMIC_KNOWLEDGE_NODE), stored.knowledgeNodeIds)
        assertEquals(listOf(RELATED_PROBLEM), stored.relations.map { it.targetProblemId })
    }

    @Test
    fun unresolvedAtomizationQueuesOneSilentResearchGapWithoutApplyingModelCandidates() = runBlocking {
        val unresolved = completeOutput().copy(
            plan = completeOutput().plan.copy(
                atomicKnowledge = emptyList(),
                stepAttributions = emptyList(),
                groundingRequests = listOf(
                    KnowledgeGroundingRequest(
                        query = "高中数学 导数符号 单调性 原子知识",
                        expectedParentKnowledgeDisplayName = "函数最值",
                        reasonMarkdown = "现有知识本体没有能够可靠匹配的原子节点。",
                    ),
                ),
            ),
        )
        persistSuccess("request-grounding", unresolved, completedAt = 450)

        val first = repository.applySuccessfulOrganization("request-grounding")
        val replay = repository.applySuccessfulOrganization("request-grounding")
        val pending = database.observePendingKnowledgeGroundingRequests(limit = 512).first()
        val stored = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertFalse(first.applied)
        assertFalse(replay.applied)
        assertEquals(1, pending.size)
        assertEquals("MATH", pending.single().subject)
        assertEquals("函数最值", pending.single().expectedParentKnowledgeDisplayName)
        assertTrue(stored.classifications.isEmpty())
        assertTrue(stored.knowledgeNodeIds.isEmpty())
    }

    @Test
    fun lowConfidenceHierarchyDoesNotOverwriteExistingOrganization() = runBlocking {
        persistExistingOrganization()
        persistSuccess(
            requestId = "request-incomplete",
            output = completeOutput().copy(
                plan = completeOutput().plan.copy(
                    classifications = listOf(
                        classification(ClassificationDimension.CHAPTER, "新板块", 0.77),
                        classification(ClassificationDimension.KNOWLEDGE, "函数最值", 0.99),
                    ),
                ),
            ),
            completedAt = 500,
        )

        val result = repository.applySuccessfulOrganization("request-incomplete")
        val stored = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertFalse(result.applied)
        assertTrue(result.preservedUserCorrection)
        assertEquals(listOf("函数", "函数最值"), stored.classifications.map { it.displayName }.sorted())
        assertEquals(listOf(RELATED_PROBLEM), stored.relations.map { it.targetProblemId })
    }

    @Test
    fun invalidNewRelationDoesNotBlockClassificationMergeOrDeleteExistingRelation() = runBlocking {
        persistExistingOrganization(BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED)
        val invalidRelation = relation().copy(
            targetProblemId = "not-in-candidates",
            targetProblemRevisionId = "unknown-revision",
            confidence = 0.99,
        )
        persistSuccess(
            requestId = "request-reclassify",
            output = completeOutput().copy(
                plan = completeOutput().plan.copy(
                    classifications = listOf(
                        classification(ClassificationDimension.CHAPTER, "新函数板块", 0.90),
                        classification(ClassificationDimension.KNOWLEDGE, "函数最值", 0.91),
                    ),
                    relations = listOf(invalidRelation),
                ),
            ),
            completedAt = 600,
        )

        val result = repository.applySuccessfulOrganization("request-reclassify")
        val stored = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertTrue(result.applied)
        assertEquals(
            listOf("函数", "函数最值", "新函数板块"),
            stored.classifications.map { it.displayName }.sorted(),
        )
        assertEquals(listOf(RELATED_PROBLEM), stored.relations.map { it.targetProblemId })
    }

    @Test
    fun highConfidenceAutomaticRelationMergesWithoutDeletingExistingRelation() = runBlocking {
        persistExistingOrganization(BindingAcceptanceSource.LOCAL_POLICY_ACCEPTED)
        persistSuccess(
            requestId = "request-merge-relation",
            output = completeOutput().copy(
                plan = completeOutput().plan.copy(
                    relations = listOf(relation(SECOND_RELATED_PROBLEM, SECOND_RELATED_REVISION)),
                ),
            ),
            completedAt = 650,
        )

        repository.applySuccessfulOrganization("request-merge-relation")
        val stored = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertEquals(
            setOf(RELATED_PROBLEM, SECOND_RELATED_PROBLEM),
            stored.relations.mapTo(linkedSetOf()) { it.targetProblemId },
        )

        repository.confirm(
            requestId = "request-merge-relation",
            selection = ProblemOrganizationSelection(
                classificationIndexes = setOf(0, 1),
                relationIndexes = emptySet(),
                relationRemovals = setOf(
                    ProblemOrganizationRelationKey(
                        targetProblemId = RELATED_PROBLEM,
                        targetProblemRevisionId = RELATED_REVISION,
                        kind = ProblemRelationKind.VARIANT_OF,
                    ),
                ),
            ),
            acceptedAtEpochMillis = 700,
        )
        val afterRemoval = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertEquals(
            listOf(SECOND_RELATED_PROBLEM),
            afterRemoval.relations.map { it.targetProblemId },
        )
    }

    @Test
    fun newerAutomaticOrganizationCannotOverwriteUserCorrection() = runBlocking {
        persistExistingOrganization(BindingAcceptanceSource.USER_CORRECTED)
        persistSuccess(
            requestId = "request-after-user-correction",
            output = completeOutput().copy(
                plan = completeOutput().plan.copy(
                    classifications = listOf(
                        classification(ClassificationDimension.CHAPTER, "自动新板块", 0.99),
                        classification(ClassificationDimension.KNOWLEDGE, "函数最值", 0.99),
                    ),
                ),
            ),
            completedAt = 900,
        )

        val result = repository.applySuccessfulOrganization("request-after-user-correction")
        val stored = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertFalse(result.applied)
        assertTrue(result.preservedUserCorrection)
        assertEquals(listOf("函数", "函数最值"), stored.classifications.map { it.displayName }.sorted())
        assertTrue(stored.classifications.all { it.acceptanceSource == "USER_CORRECTED" })
    }

    @Test
    fun explicitUserCorrectionCanClearAllRelationsWithoutChangingTheClassificationBoundary() = runBlocking {
        persistSuccess("request-clear-relations", completeOutput(), completedAt = 400)
        repository.applySuccessfulOrganization("request-clear-relations")

        repository.confirm(
            requestId = "request-clear-relations",
            selection = ProblemOrganizationSelection(
                classificationIndexes = setOf(0, 1),
                relationIndexes = emptySet(),
                replaceRelations = true,
            ),
            acceptedAtEpochMillis = 700,
        )
        val stored = database.observeConfirmedProblemOrganization(PROBLEM, REVISION).first()

        assertTrue(stored.relations.isEmpty())
        assertTrue(stored.classifications.all { it.acceptanceSource == "USER_CORRECTED" })
    }

    private suspend fun persistExistingOrganization(
        acceptanceSource: BindingAcceptanceSource = BindingAcceptanceSource.USER_CORRECTED,
    ) {
        database.confirmProblemOrganization(
            buildConfirmationCommand(
                requestId = "user-correction-request",
                input = input(),
                classifications = listOf(
                    classification(ClassificationDimension.CHAPTER, "函数", 1.0),
                    classification(ClassificationDimension.KNOWLEDGE, "函数最值", 1.0),
                ),
                relations = listOf(relation()),
                acceptedAtEpochMillis = 250,
                acceptanceSource = acceptanceSource,
                replaceRelations = true,
            ),
        )
    }

    private suspend fun persistSuccess(
        requestId: String,
        output: ProblemOrganizationOutput,
        completedAt: Long,
    ) {
        val request = ModelTaskRequest(
            requestId = requestId,
            input = input(),
            occurredAtEpochMillis = 100,
        )
        val waiting = database.createModelTask(
            CreateModelTaskCommand(
                taskId = "task-$requestId",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                occurredAtEpochMillis = 100,
            ),
        ).snapshot
        val queued = transition(waiting, ModelTaskStatus.QUEUED, 200)
        val running = transition(queued, ModelTaskStatus.RUNNING, 300, provider = PROVIDER)
        transition(
            running,
            ModelTaskStatus.SUCCEEDED,
            completedAt,
            provider = PROVIDER,
            output = output,
        )
    }

    private suspend fun transition(
        snapshot: ModelTaskSnapshot,
        nextStatus: ModelTaskStatus,
        occurredAt: Long,
        provider: ProviderCapabilitySnapshot? = snapshot.provider,
        output: ProblemOrganizationOutput? = null,
    ): ModelTaskSnapshot = database.transitionModelTask(
        TransitionModelTaskCommand(
            taskId = snapshot.taskId,
            expectedStateVersion = snapshot.stateVersion,
            expectedStatus = snapshot.status,
            nextStatus = nextStatus,
            stage = if (nextStatus == ModelTaskStatus.SUCCEEDED) {
                ModelTaskStage.COMPLETE
            } else {
                ModelTaskStage.PREPARING
            },
            userMessage = nextStatus.name,
            attemptCount = if (nextStatus == ModelTaskStatus.QUEUED) 0 else 1,
            provider = provider,
            output = output,
            occurredAtEpochMillis = occurredAt,
        ),
    ).snapshot

    private fun input() = ProblemOrganizationInput(
        problemId = PROBLEM,
        problemRevisionId = REVISION,
        practiceUnitId = PRACTICE,
        subject = SubjectKind.MATH,
        questionDocument = question("question-main", "求函数的最大值。"),
        relevantLearningEvidence = emptyList(),
        relationCandidates = listOf(
            RelatedProblemCandidate(
                problemId = RELATED_PROBLEM,
                problemRevisionId = RELATED_REVISION,
                subject = SubjectKind.MATH,
                title = "函数变式",
                questionDocument = question("question-related", "讨论参数范围。"),
            ),
            RelatedProblemCandidate(
                problemId = SECOND_RELATED_PROBLEM,
                problemRevisionId = SECOND_RELATED_REVISION,
                subject = SubjectKind.MATH,
                title = "函数同类题",
                questionDocument = question("question-related-second", "比较两个函数的最值。"),
            ),
        ),
        knowledgeBaseNodes = listOf(knowledgeContext()),
    )

    private fun completeOutput() = ProblemOrganizationOutput(
        problemId = PROBLEM,
        problemRevisionId = REVISION,
        practiceUnitId = PRACTICE,
        plan = ProblemOrganizationPlan(
            summaryMarkdown = "整理完成。",
            reviewPriorityMarkdown = "适合近期复习。",
            targetedEvidenceLabels = emptyList(),
            classifications = listOf(
                classification(ClassificationDimension.CHAPTER, "函数", 0.90),
                classification(ClassificationDimension.KNOWLEDGE, "函数最值", 0.91),
            ),
            relations = listOf(relation()),
            schemaVersion = 2,
            atomicKnowledge = listOf(
                AtomicKnowledgeSuggestion(
                    referenceId = "atom-1",
                    canonicalName = "确定函数最值的候选位置",
                    aliases = emptyList(),
                    kind = KnowledgeNodeKind.PROCEDURE,
                    parentKnowledgeDisplayName = "函数最值",
                    matchedKnowledgeNodeId = ATOMIC_KNOWLEDGE_NODE,
                    prerequisiteReferenceIds = emptyList(),
                    observableOutcomeMarkdown = "能确定端点与驻点并比较函数值。",
                    boundaryMarkdown = "不包含导数公式的机械计算。",
                    confidence = 0.91,
                ),
            ),
            stepAttributions = listOf(
                ProblemStepKnowledgeAttribution(
                    stepOrdinal = 1,
                    stepSummaryMarkdown = "确定候选位置并比较函数值。",
                    atomicReferenceIds = listOf("atom-1"),
                ),
            ),
        ),
        modelVersion = "test-model",
    )

    private fun classification(
        dimension: ClassificationDimension,
        name: String,
        confidence: Double,
    ) = ProblemClassificationSuggestion(
        dimension = dimension,
        displayName = name,
        rationaleMarkdown = "内容层级匹配。",
        confidence = confidence,
    )

    private fun relation(
        targetProblemId: String = RELATED_PROBLEM,
        targetProblemRevisionId: String = RELATED_REVISION,
    ) = ProblemRelationSuggestion(
        targetProblemId = targetProblemId,
        targetProblemRevisionId = targetProblemRevisionId,
        kind = ProblemRelationKind.VARIANT_OF,
        rationaleMarkdown = "知识结构相近。",
        confidence = 0.95,
    )

    private fun question(id: String, text: String) = QuestionDocument(
        id = id,
        blocks = listOf(ContentBlock.Paragraph("$id-block", text)),
    )

    private fun seed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(PROBLEM, "a".repeat(64), "MATH", 1),
            ProblemSeedRecord(RELATED_PROBLEM, "b".repeat(64), "MATH", 1),
            ProblemSeedRecord(SECOND_RELATED_PROBLEM, "e".repeat(64), "MATH", 1),
        ),
        revisions = listOf(
            revision(REVISION, PROBLEM, "c".repeat(64)),
            revision(RELATED_REVISION, RELATED_PROBLEM, "d".repeat(64)),
            revision(SECOND_RELATED_REVISION, SECOND_RELATED_PROBLEM, "f".repeat(64)),
        ),
        practiceUnits = listOf(
            practice(PRACTICE, PROBLEM, REVISION),
            practice(RELATED_PRACTICE, RELATED_PROBLEM, RELATED_REVISION),
            practice(SECOND_RELATED_PRACTICE, SECOND_RELATED_PROBLEM, SECOND_RELATED_REVISION),
        ),
        errorBookEntries = listOf(
            entry("entry-main", PRACTICE, PROBLEM, REVISION),
            entry("entry-related", RELATED_PRACTICE, RELATED_PROBLEM, RELATED_REVISION),
            entry(
                "entry-related-second",
                SECOND_RELATED_PRACTICE,
                SECOND_RELATED_PROBLEM,
                SECOND_RELATED_REVISION,
            ),
        ),
        knowledgeNodes = listOf(
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = KNOWLEDGE_TOPIC_NODE,
                stableCode = "math:topic:function-extrema",
                subject = SubjectKind.MATH.name,
                displayName = "函数最值",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "math-v1",
                createdAtEpochMillis = 1,
                canonicalName = "函数最值",
                nodeKind = KnowledgeNodeKind.TOPIC.name,
                granularity = KnowledgeNodeGranularity.TOPIC.name,
                verificationStatus = KnowledgeNodeVerificationStatus.CURATED.name,
            ),
            KnowledgeNodeSeedRecord(
                knowledgeNodeId = ATOMIC_KNOWLEDGE_NODE,
                stableCode = "math:atomic:procedure:function-extrema-candidates",
                subject = SubjectKind.MATH.name,
                displayName = "确定函数最值的候选位置",
                parentKnowledgeNodeId = KNOWLEDGE_TOPIC_NODE,
                taxonomyVersion = "math-v1",
                createdAtEpochMillis = 1,
                canonicalName = "确定函数最值的候选位置",
                nodeKind = KnowledgeNodeKind.PROCEDURE.name,
                granularity = KnowledgeNodeGranularity.ATOMIC.name,
                boundaryMarkdown = "不包含导数公式的机械计算。",
                verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED.name,
            ),
        ),
    )

    private fun knowledgeContext() = KnowledgeBaseNodeContext(
        knowledgeNodeId = ATOMIC_KNOWLEDGE_NODE,
        subject = SubjectKind.MATH,
        canonicalName = "确定函数最值的候选位置",
        aliases = emptyList(),
        kind = KnowledgeNodeKind.PROCEDURE,
        granularity = KnowledgeNodeGranularity.ATOMIC,
        parentCanonicalName = "函数最值",
        taxonomyVersion = "math-v1",
        verificationStatus = KnowledgeNodeVerificationStatus.SOURCE_GROUNDED,
        boundaryMarkdown = "不包含导数公式的机械计算。",
    )

    private fun revision(id: String, problemId: String, fingerprint: String) =
        ProblemRevisionSeedRecord(
            revisionId = id,
            problemId = problemId,
            revisionNumber = 1,
            title = "测试题",
            problemMarkdown = "求函数最值。",
            questionDocumentSnapshot = null,
            answerSpecId = null,
            answerSpecSnapshot = null,
            answerVerificationStatus = "UNKNOWN",
            sourceType = "TEST",
            sourceReference = null,
            contentFingerprint = fingerprint,
            createdAtEpochMillis = 1,
        )

    private fun practice(id: String, problemId: String, revisionId: String) =
        PracticeUnitSeedRecord(
            practiceUnitId = id,
            problemId = problemId,
            problemRevisionId = revisionId,
            unitKey = "unit:$id",
            unitKind = "PROBLEM",
            title = "测试题",
            promptMarkdown = "求函数最值。",
            estimatedSeconds = 180,
            createdAtEpochMillis = 1,
        )

    private fun entry(id: String, practiceId: String, problemId: String, revisionId: String) =
        ErrorBookEntrySeedRecord(
            entryId = id,
            practiceUnitId = practiceId,
            problemId = problemId,
            currentRevisionId = revisionId,
            sourceKey = null,
            status = StudyDbValue.ErrorBookStatus.ACTIVE,
            acceptedAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )

    private companion object {
        const val PROBLEM = "problem-main"
        const val REVISION = "revision-main"
        const val PRACTICE = "practice-main"
        const val RELATED_PROBLEM = "problem-related"
        const val RELATED_REVISION = "revision-related"
        const val RELATED_PRACTICE = "practice-related"
        const val SECOND_RELATED_PROBLEM = "problem-related-second"
        const val SECOND_RELATED_REVISION = "revision-related-second"
        const val SECOND_RELATED_PRACTICE = "practice-related-second"
        const val KNOWLEDGE_TOPIC_NODE = "math-topic-function-extrema"
        const val ATOMIC_KNOWLEDGE_NODE = "math-atomic-function-extrema-candidates"
        val PROVIDER = ProviderCapabilitySnapshot(
            providerId = "provider",
            providerDisplayName = "测试模型",
            modelId = "model-v1",
            supportedTasks = setOf(com.tingyun.smartmistakebook.core.model.ModelTaskKind.PROBLEM_CLASSIFY),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            providerConfigurationVersion = "config-v1",
        )
    }
}
