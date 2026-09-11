package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.data.M1CuratedStudySeed
import com.tingyun.smartmistakebook.core.database.ReviewLogEntry
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord
import com.tingyun.smartmistakebook.core.database.ReviewLogSampleRecord
import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteCommand
import com.tingyun.smartmistakebook.core.database.AnswerRevealWriteResult
import com.tingyun.smartmistakebook.core.database.AssessmentEventSeedRecord
import com.tingyun.smartmistakebook.core.database.AssessmentItemSnapshotSeedRecord
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CommitProblemDraftResult
import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetCommand
import com.tingyun.smartmistakebook.core.database.AppendProblemDraftSourceAssetResult
import com.tingyun.smartmistakebook.core.database.ReplaceProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ProblemDraftReplacementResult
import com.tingyun.smartmistakebook.core.database.SplitProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.ProblemDraftSplitResult
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceRecord
import com.tingyun.smartmistakebook.core.database.SaveProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.ModelTaskDispatchReservationResult
import com.tingyun.smartmistakebook.core.database.ReserveModelTaskRemoteDispatchCommand
import com.tingyun.smartmistakebook.core.database.ProblemDraftEditWorkspaceWriteResult
import com.tingyun.smartmistakebook.core.database.ConsumeProblemDraftEditWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.ConfirmAndCommitProblemDraftFromWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.ConfirmTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.ConfirmTutorSessionFromWorkspaceCommand
import com.tingyun.smartmistakebook.core.database.TutorSessionWriteResult
import com.tingyun.smartmistakebook.core.database.TutorSessionRecord
import com.tingyun.smartmistakebook.core.database.CommitTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.EndTutorSessionCommand
import com.tingyun.smartmistakebook.core.database.EndTutorSessionResult
import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorMoveCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorRevealCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorAnswerExposureCommand
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.database.PersistTutorSessionAnchorCommand
import com.tingyun.smartmistakebook.core.database.TutorSessionProblemAnchorRecord
import com.tingyun.smartmistakebook.core.database.ConfirmProblemOrganizationCommand
import com.tingyun.smartmistakebook.core.database.ConfirmProblemOrganizationResult
import com.tingyun.smartmistakebook.core.database.LibraryCatalogRow
import com.tingyun.smartmistakebook.core.database.LibraryFacetCountRecord
import com.tingyun.smartmistakebook.core.database.TutorConversationRecord
import com.tingyun.smartmistakebook.core.database.TutorMessageRecord
import com.tingyun.smartmistakebook.core.database.CreateTutorConversationDatabaseCommand
import com.tingyun.smartmistakebook.core.database.AppendTutorStudentMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.AppendTutorAssistantMessageDatabaseCommand
import com.tingyun.smartmistakebook.core.database.UpdateTutorMessageStatusDatabaseCommand
import com.tingyun.smartmistakebook.core.database.PendingCaptureDraftRecord
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeSourceSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeSourceBindingSeedRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeNodeRelationRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeTeachingMaterialNodeBindingRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingRequestRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeResearchReviewBundleRecord
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingResolutionRecord
import com.tingyun.smartmistakebook.core.database.ApplyReviewedKnowledgePackCommand
import com.tingyun.smartmistakebook.core.database.DecideKnowledgeResearchReviewBundleCommand
import com.tingyun.smartmistakebook.core.database.ApplyApprovedKnowledgeResearchPackCommand
import com.tingyun.smartmistakebook.core.database.ResolveKnowledgeGroundingCommand
import com.tingyun.smartmistakebook.core.database.MistakeDetailRecord
import com.tingyun.smartmistakebook.core.database.MistakeRevisionSummaryRecord
import com.tingyun.smartmistakebook.core.database.BatchImportJobRecord
import com.tingyun.smartmistakebook.core.database.BatchImportPageRecord
import com.tingyun.smartmistakebook.core.database.CreateBatchImportJobCommand
import com.tingyun.smartmistakebook.core.database.CreateSplitImportJobCommand
import com.tingyun.smartmistakebook.core.database.SplitImportJobRecord
import com.tingyun.smartmistakebook.core.database.SplitImportQuestionSeed
import com.tingyun.smartmistakebook.core.database.ResolveBatchImportBoundaryCommand
import com.tingyun.smartmistakebook.core.database.ConfirmedProblemOrganizationRecord
import androidx.paging.PagingSource
import com.tingyun.smartmistakebook.core.database.CreateProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.CreateModelTaskCommand
import com.tingyun.smartmistakebook.core.database.AttemptCorrectionRecord
import com.tingyun.smartmistakebook.core.database.AttemptCorrectionResult
import com.tingyun.smartmistakebook.core.database.AttemptAdvanceProofRecord
import com.tingyun.smartmistakebook.core.database.AttemptPersistenceRecord
import com.tingyun.smartmistakebook.core.database.AttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.AttemptWriteResult
import com.tingyun.smartmistakebook.core.database.LearningLedgerRead
import com.tingyun.smartmistakebook.core.database.LearningLedgerReadStatus
import com.tingyun.smartmistakebook.core.database.KnowledgeGroundingSummaryRecord
import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.database.ModelTaskWriteResult
import com.tingyun.smartmistakebook.core.database.PersistedAnswerRevealP0
import com.tingyun.smartmistakebook.core.database.PersistedAttemptP0
import com.tingyun.smartmistakebook.core.database.PersistedCorrectionP0
import com.tingyun.smartmistakebook.core.database.PersistedLearnerSnapshot
import com.tingyun.smartmistakebook.core.database.ProjectionBatch
import com.tingyun.smartmistakebook.core.database.ProjectionBatchStopReason
import com.tingyun.smartmistakebook.core.database.ProjectionCommit
import com.tingyun.smartmistakebook.core.database.ProblemDraftRecord
import com.tingyun.smartmistakebook.core.database.ProblemDraftWriteResult
import com.tingyun.smartmistakebook.core.database.ReviewPlanBundle
import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteCommand
import com.tingyun.smartmistakebook.core.database.ReviewAttemptWriteResult
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceCommand
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceReceipt
import com.tingyun.smartmistakebook.core.database.ReviewSessionAdvanceResult
import com.tingyun.smartmistakebook.core.database.ReviewSessionRecord
import com.tingyun.smartmistakebook.core.database.ReviewedKnowledgeCoverageRecord
import com.tingyun.smartmistakebook.core.database.ReviseProblemDraftCommand
import com.tingyun.smartmistakebook.core.database.SeedResult
import com.tingyun.smartmistakebook.core.database.port.PracticeUnitKnowledgeBindingRecord
import com.tingyun.smartmistakebook.core.database.port.ResolvedStudentModelPredictionRecord
import com.tingyun.smartmistakebook.core.database.port.StudentModelPredictionRecord
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort

import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.database.TransitionModelTaskCommand
import com.tingyun.smartmistakebook.core.domain.StudyDataStatus
import com.tingyun.smartmistakebook.core.domain.StudyChoiceSubmission
import com.tingyun.smartmistakebook.core.domain.StudyReviewSessionStatus
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReport
import com.tingyun.smartmistakebook.core.domain.StudyReviewSelfReportSubmission
import com.tingyun.smartmistakebook.core.domain.LearningProjector
import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.AttemptSubmittedResponse
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.LearningEvidenceDirection
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.KnowledgeTeachingMaterialType
import com.tingyun.smartmistakebook.core.model.ProblemMemoryOutcome
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBackedStudyExperienceRepositoryTest {
    @Test
    fun initializePublishesGroupedKnowledgeCoverageWithoutLearningEvidence() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            reviewedKnowledgeCoverage.value = listOf(
                ReviewedKnowledgeCoverageRecord(
                    subject = SubjectKind.MATH.name,
                    topicCount = 1,
                    atomicKnowledgeCount = 4,
                    reviewedSourceCount = 2,
                    latestReviewedAtEpochMillis = 4_000,
                ),
            )
            knowledgeGroundingSummaries.value = listOf(
                KnowledgeGroundingSummaryRecord(
                    groundingKey = "gap:math:monotonicity",
                    subject = SubjectKind.MATH.name,
                    expectedParentKnowledgeDisplayName = "函数性质",
                    query = "导数符号与单调区间",
                    relatedQuestionCount = 3,
                    firstObservedAtEpochMillis = 1_000,
                    lastObservedAtEpochMillis = 3_000,
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(
            database = database,
            applicationScope = applicationScope,
            initialFixture = null,
        )

        try {
            repository.initialize()

            val coverage = repository.snapshot.value.knowledgeCoverage
            assertEquals(1, coverage.pendingGapCount)
            assertEquals(3, coverage.pendingQuestionOccurrenceCount)
            assertEquals(SubjectKind.MATH, coverage.pendingGaps.single().subject)
            assertEquals(1, coverage.reviewedSubjectCount)
            assertEquals(4, coverage.reviewedAtomicKnowledgeCount)
            assertEquals(2, coverage.reviewedSourceCount)
            assertFalse(repository.snapshot.value.profile.hasLearningEvidence)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun productionDefaultInitializeKeepsAFreshDatabaseEmpty() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(
            database = database,
            applicationScope = applicationScope,
            initialFixture = null,
        )

        try {
            repository.initialize()
            repository.initialize()

            val snapshot = repository.snapshot.value
            assertEquals(0, database.seedCallCount)
            assertEquals(0, database.problemCount)
            assertEquals(0, snapshot.mistakeCount)
            assertTrue(snapshot.review.scheduledPracticeUnitIds.isEmpty())
            assertFalse(snapshot.profile.hasLearningEvidence)
            assertEquals(StudyDataStatus.READY, snapshot.status)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun explicitFixtureSeedsFiveProblemsAndFourEntriesWithoutInventingHistory() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            repository.initialize()

            val snapshot = repository.snapshot.value
            assertEquals(5, database.problemCount)
            assertEquals(4, snapshot.mistakeCount)
            assertEquals(StudyDataStatus.READY, snapshot.status)
            assertFalse(snapshot.profile.hasLearningEvidence)
            assertEquals(0, snapshot.profile.recordedAttemptCount)
            assertEquals(0, snapshot.profile.newlyMasteredCount)
            assertTrue(snapshot.profile.weaknesses.isEmpty())
            assertTrue(snapshot.profile.projectionIsCurrent)
            assertEquals(null, snapshot.tutorPracticeUnitId)
            assertEquals(null, snapshot.tutorDecision)
            assertEquals(
                listOf("learner:local", "learner:local"),
                database.tutorExposureReconcileLearners,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun capturedMistakeWithoutAnswerKeyIsScheduledFromItsSavedTranscription() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "captured-entry",
                    problemId = "captured-problem",
                    problemRevisionId = "captured-revision",
                    practiceUnitId = "captured-practice-unit",
                    sourceKey = "capture:photo-1",
                    subject = "数学",
                    title = "刚拍下的错题",
                    problemMarkdown = "待模型完成可信转写与分类",
                    status = "ACTIVE",
                    createdAtEpochMillis = 9_999_999_999_999L,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            val snapshot = repository.snapshot.value
            val captured = snapshot.catalog.single { it.entryId == "captured-entry" }
            assertEquals(StudyDataStatus.READY, snapshot.status)
            assertEquals("captured-entry", snapshot.catalog.first().entryId)
            assertTrue(captured.knowledgeLabels.isEmpty())
            assertEquals(MasteryStatus.UNKNOWN, captured.masteryStatus)
            assertEquals(
                listOf("captured-practice-unit"),
                snapshot.review.scheduledPracticeUnitIds,
            )
            assertEquals(null, repository.teachingArtifact("captured-practice-unit"))
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun repeatedCaptureMovesTheExistingMistakeForwardInReview() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "entry-a",
                    problemId = "problem-a",
                    problemRevisionId = "revision-a",
                    practiceUnitId = "unit-a",
                    sourceKey = "capture:a",
                    subject = "MATH",
                    title = "一次拍到的题",
                    problemMarkdown = "题目 A",
                    status = "ACTIVE",
                    createdAtEpochMillis = 1_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                ),
            )
            addMistake(
                MistakeRecord(
                    entryId = "entry-z",
                    problemId = "problem-z",
                    problemRevisionId = "revision-z",
                    practiceUnitId = "unit-z",
                    sourceKey = "capture:z",
                    subject = "MATH",
                    title = "再次遇到的题",
                    problemMarkdown = "题目 Z",
                    status = "ACTIVE",
                    createdAtEpochMillis = 2_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                    captureOccurrenceCount = 3,
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            assertEquals(
                listOf("unit-z", "unit-a"),
                repository.snapshot.value.review.scheduledPracticeUnitIds,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun capturedReviewSelfReportAttributesToPseudoKnowledgeNode() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "captured-entry",
                    problemId = "captured-problem",
                    problemRevisionId = "captured-revision",
                    practiceUnitId = "captured-practice-unit",
                    sourceKey = "capture:photo-1",
                    subject = "MATH",
                    title = "函数原题",
                    problemMarkdown = "求函数的单调区间。",
                    status = "ACTIVE",
                    createdAtEpochMillis = 1_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                    knowledgeNodeIds = setOf("knowledge:function-monotonicity"),
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()
            assertEquals(
                setOf("knowledge:function-monotonicity"),
                database.savedPlans.single().queue.single().knowledgeNodeIds,
            )
            val started = requireNotNull(
                repository.startOrResumeReviewSession("captured-start", 2_000),
            )
            val submission = StudyReviewSelfReportSubmission(
                requestId = "captured-self-report",
                presentationId = "captured-presentation",
                practiceUnitId = "captured-practice-unit",
                report = StudyReviewSelfReport.RECALL_COMPLETED,
                durationSeconds = 15,
                occurredAtEpochMillis = 3_000,
            )

            val first = repository.submitReviewSelfReport(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )
            val replay = repository.submitReviewSelfReport(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )

            assertEquals(LearningEvidenceReason.SELF_REPORTED_RECALL, first.evidenceReason)
            assertEquals(StudyReviewSessionStatus.COMPLETED, first.progress.status)
            assertTrue(first.created)
            assertFalse(replay.created)
            assertEquals(first.progress, replay.progress)
            assertEquals(0.35, database.lastAttemptCommand?.evidence?.weight ?: -1.0, 0.0)
            // Spec 3.4: the unbound question attributes its evidence to the
            // subject-scoped pseudo KC through the pseudo binding.
            val pseudoAttribution = database.lastEvidenceSnapshot?.attributions?.singleOrNull()
            assertEquals("pseudo:MATH", pseudoAttribution?.knowledgeNodeId)
            assertEquals(1.0, pseudoAttribution?.weight ?: -1.0, 0.0)
            assertTrue(database.pseudoBindingCalls.all { it == "pseudo:MATH" })
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun currentKnowledgeReviewPlanKeepsOnlyQuizAbleScopeFromTodaysQueue() = runBlocking {
        // 知识点复习范围 = 今天错题复习队列的题绑定知识点（spec dual-review-entry §3.2），
        // 不是全知识库。KNOWLEDGE_QUIZ 以讲解材料为防臆造锚（§3.3）——无材料的伪节点
        // （pseudo:MATH，未绑定题的占位）必须被排除，否则会话会排一个永远出不了题的死点。
        val database = FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "captured-entry",
                    problemId = "captured-problem",
                    problemRevisionId = "captured-revision",
                    practiceUnitId = "captured-practice-unit",
                    sourceKey = "capture:photo-1",
                    subject = "MATH",
                    title = "函数原题",
                    problemMarkdown = "求函数的单调区间。",
                    status = "ACTIVE",
                    createdAtEpochMillis = 1_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                    // 绑定两个点：一个有讲解材料（可出题），一个没有（pseudo 占位，应排除）。
                    knowledgeNodeIds = setOf(
                        "knowledge:function-monotonicity",
                        "pseudo:MATH",
                    ),
                ),
            )
            knowledgeNodes += KnowledgeNodeSeedRecord(
                knowledgeNodeId = "knowledge:function-monotonicity",
                stableCode = "math.function.monotonicity",
                subject = "MATH",
                displayName = "函数单调性",
                parentKnowledgeNodeId = null,
                taxonomyVersion = "cn-highschool-m1-v1",
                createdAtEpochMillis = 1_000,
                canonicalName = "函数单调性",
            )
            teachingMaterials += KnowledgeTeachingMaterialRecord(
                materialId = "material:function-monotonicity",
                stableCode = "math.function.monotonicity",
                subject = "MATH",
                materialType = "CONCEPT_EXPLANATION",
                title = "函数单调性讲解",
                summaryMarkdown = "函数单调性的判定。",
                applicabilityMarkdown = "用于导数判断单调区间。",
                contentMarkdown = "函数单调性定义与判定方法。",
                boundaryMarkdown = "只覆盖单调性判定，不涉及极值。",
                derivationKind = "REVIEWED",
                sourceId = "source:m1",
                sourceLocator = "m1",
                contentFingerprint = "fp-function-monotonicity",
                reviewedAtEpochMillis = 1_000,
            )
            materialNodeBindings += KnowledgeTeachingMaterialNodeBindingRecord(
                materialId = "material:function-monotonicity",
                knowledgeNodeId = "knowledge:function-monotonicity",
                role = "PRIMARY",
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()
            val plan = requireNotNull(
                repository.currentKnowledgeReviewPlan("knowledge-review-start", 2_000),
            )
            // 计划范围精确来自今天队列绑定点，且只保留可出题（有材料）的点。
            assertTrue(plan.queue.isNotEmpty())
            assertEquals(
                "knowledge:function-monotonicity",
                plan.queue.single().knowledgeNodeId,
            )
            // 无材料伪节点被排除。
            assertTrue(plan.queue.none { it.knowledgeNodeId == "pseudo:MATH" })
            assertEquals("MATH", plan.queue.single().subject)
            assertEquals("函数单调性", plan.queue.single().displayName)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun feasibleVisualAttemptIsIngestedOnceWithDirectKnowledgeAttribution() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(visualIngestMistake())
            addPracticeUnitKnowledgeBinding(visualIngestBinding())
            addVisualInteractionAttempt(
                visualAttemptRecord(attemptId = "visual-a", feasible = true),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            val created = repository.ingestVisualInteractionAttempts()

            // initialize() already consumed the pending visual attempt into the
            // ledger (startup ingestion, audit §12); the manual sweep therefore
            // finds nothing new, and the single attempt was recorded exactly once.
            assertEquals(0, created)
            assertEquals(1, database.recordedAttemptCount)
            val command = requireNotNull(database.lastAttemptCommand)
            assertEquals(0.25, command.evidence?.weight ?: -1.0, 0.0)
            assertEquals(LearningEvidenceDirection.POSITIVE, command.evidence?.direction)
            assertEquals(ProblemMemoryOutcome.ASSISTED_RECALL, command.problemMemoryOutcome)
            assertEquals(
                "visual:SATISFIED",
                (requireNotNull(command.submittedResponse) as AttemptSubmittedResponse.Choice)
                    .choiceId,
            )
            val attribution = requireNotNull(
                database.lastEvidenceSnapshot?.attributions?.singleOrNull(),
            )
            assertEquals("binding-v", attribution.bindingId)
            assertEquals("knowledge:visual", attribution.knowledgeNodeId)
            assertEquals(0.6, attribution.weight, 0.0)
            assertEquals(EvidenceAttributionRole.PRIMARY, attribution.role)
            assertEquals(EvidenceAttributionCertainty.DIRECT, attribution.certainty)
            assertEquals("revision-v", attribution.basisRevisionId)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun violatedVisualAttemptCreatesNegativeEvidence() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(visualIngestMistake())
            addPracticeUnitKnowledgeBinding(visualIngestBinding())
            addVisualInteractionAttempt(
                visualAttemptRecord(
                    attemptId = "visual-b",
                    feasible = false,
                    feedback = "直线未通过目标点",
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            val created = repository.ingestVisualInteractionAttempts()

            assertEquals(0, created)
            val command = requireNotNull(database.lastAttemptCommand)
            assertEquals(0.5, command.evidence?.weight ?: -1.0, 0.0)
            assertEquals(
                LearningEvidenceReason.VISUAL_INTERACTION_VIOLATED,
                command.evidence?.reason,
            )
            assertEquals(LearningEvidenceDirection.NEGATIVE, command.evidence?.direction)
            assertEquals(ProblemMemoryOutcome.RETRIEVAL_FAILURE, command.problemMemoryOutcome)
            assertEquals(
                "visual:VIOLATED",
                (requireNotNull(command.submittedResponse) as AttemptSubmittedResponse.Choice)
                    .choiceId,
            )
            assertEquals(
                "直线未通过目标点",
                (requireNotNull(command.submittedResponse) as AttemptSubmittedResponse.Choice)
                    .choiceMarkdown,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun repeatedVisualIngestionSweepsDoNotDoubleCount() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(visualIngestMistake())
            addPracticeUnitKnowledgeBinding(visualIngestBinding())
            addVisualInteractionAttempt(
                visualAttemptRecord(attemptId = "visual-c", feasible = true),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            // Startup ingestion already recorded the pending attempt; both
            // repeat sweeps stay idempotent and report zero new creations.
            assertEquals(0, repository.ingestVisualInteractionAttempts())
            assertEquals(0, repository.ingestVisualInteractionAttempts())
            assertEquals(1, database.recordedAttemptCount)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun conservativeVisualIngestionSkipsUndecidableAndUnboundAttempts() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(visualIngestMistake())
            addMistake(
                visualIngestMistake(
                    entryId = "entry-w",
                    practiceUnitId = "unit-w",
                    problemRevisionId = "revision-w",
                ),
            )
            addPracticeUnitKnowledgeBinding(visualIngestBinding())
            addVisualInteractionAttempt(
                visualAttemptRecord(
                    attemptId = "visual-measure",
                    actionKind = "Measure",
                    feasible = true,
                ),
            )
            addVisualInteractionAttempt(
                visualAttemptRecord(
                    attemptId = "visual-unbound",
                    problemRevisionId = "revision-w",
                    feasible = false,
                ),
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            assertEquals(0, repository.ingestVisualInteractionAttempts())
            assertEquals(0, database.recordedAttemptCount)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun calibrationReportWiresResolvedShadowPredictions() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            resolvedStudentModelPredictions += ResolvedStudentModelPredictionRecord(
                predictionId = "prediction-1",
                modelId = "hlr-shadow-v1",
                modelVersion = "0.1.0-experimental",
                algorithmHash = "hlr-recall-v1",
                predictedScore = 0.8,
                conservativeScore = 0.5,
                wasIndependentCorrect = true,
                observedAtEpochMillis = 1,
            )
            resolvedStudentModelPredictions += ResolvedStudentModelPredictionRecord(
                predictionId = "prediction-2",
                modelId = "hlr-shadow-v1",
                modelVersion = "0.1.0-experimental",
                algorithmHash = "hlr-recall-v1",
                predictedScore = 0.3,
                conservativeScore = 0.2,
                wasIndependentCorrect = false,
                observedAtEpochMillis = 2,
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            val report = repository.calibrationReport()

            assertEquals("hlr-shadow-v1", report.modelVersion.modelId)
            assertEquals(2, report.resolvedPredictions)
            assertEquals(2, report.totalPredictions)
            assertEquals(0.065, report.overallBrierScore, 1e-9)
            assertEquals(10, report.buckets.size)
            assertEquals(0.2899092476264711, requireNotNull(report.overallLogLoss), 1e-9)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun projectionFailureClearsInteractiveDecisionsAndReviewSessionProjection() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            assertEquals(null, repository.snapshot.value.tutorDecision)
            database.failProjectionReads = true

            // Any snapshot-republishing operation must surface the projection failure.
            assertTrue(runCatching { repository.refresh() }.isFailure)

            val failed = repository.snapshot.value
            assertEquals(StudyDataStatus.ERROR, failed.status)
            assertEquals(null, failed.tutorDecision)
            assertEquals(null, failed.tutorPracticeUnitId)
            assertEquals(null, failed.review.activeSessionId)
            assertFalse(failed.profile.projectionIsCurrent)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun learningLedgerHeadChangeRefreshesCurrentQuestionMemoryWithoutAnotherMutation() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            val entryBeforeExposure = repository.snapshot.value.catalog.first()
            assertEquals(null, entryBeforeExposure.questionMemory)

            database.publishTutorExposure(entryBeforeExposure.practiceUnitId)
            yield()

            val refreshedEntry = repository.snapshot.value.catalog.single {
                it.practiceUnitId == entryBeforeExposure.practiceUnitId
            }
            assertEquals(1, refreshedEntry.questionMemory?.answerRevealCount)
            assertEquals(StudyDataStatus.READY, repository.snapshot.value.status)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun `hint count reaches the prediction audit outcome`() = runBlocking {
        val database = FakeStudyDatabasePort()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, scope)
        val startedAt = Instant.parse("2026-01-02T08:05:00Z").toEpochMilli()

        try {
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("hint-start", startedAt),
            )
            val practiceUnitId = repository.snapshot.value.review.scheduledPracticeUnitIds.first()
            val artifact = requireNotNull(repository.teachingArtifact(practiceUnitId))

            val submitted = repository.submitReviewChoice(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = StudyChoiceSubmission(
                    requestId = "hint-choice",
                    presentationId = "review-presentation:hint",
                    practiceUnitId = practiceUnitId,
                    selectedChoiceId = artifact.assessmentItems.single().choices.first().id,
                    responseOrdinal = 1,
                    durationSeconds = 12,
                    occurredAtEpochMillis = startedAt + 1,
                    // A hint was shown before the graded answer; the prediction
                    // audit must see it (spec §2.14; no UI produces this today,
                    // so the channel itself is what this test pins).
                    hintCount = 1,
                ),
            )

            assertTrue(submitted.attempt.created)
            assertEquals(1, database.resolvedPredictionOutcomes.last().hintCount)
        } finally {
            repository.close()
            scope.cancel()
        }
    }

    @Test
    fun reviewSessionProgressAndCompletionComeBackFromPersistence() = runBlocking {
        val database = FakeStudyDatabasePort()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val firstRepository = repository(database, firstScope)
        val startedAt = Instant.parse("2026-01-02T08:05:00Z").toEpochMilli()

        val completed = try {
            firstRepository.initialize()
            val started = requireNotNull(
                firstRepository.startOrResumeReviewSession("start-review", startedAt),
            )
            assertEquals(
                started,
                firstRepository.startOrResumeReviewSession("duplicate-start", startedAt + 1),
            )
            val scheduledPracticeUnitIds =
                firstRepository.snapshot.value.review.scheduledPracticeUnitIds
            assertEquals(started.queueSize, scheduledPracticeUnitIds.size)
            var progress = started
            repeat(started.queueSize) { index ->
                val expectedVersion = progress.stateVersion
                val progressedAt = startedAt + index + 1
                val practiceUnitId = scheduledPracticeUnitIds[index]
                val artifact = requireNotNull(firstRepository.teachingArtifact(practiceUnitId))
                val submission = StudyChoiceSubmission(
                    requestId = "review-choice-$index",
                    presentationId = "review-presentation:${started.sessionId}:$index",
                    practiceUnitId = practiceUnitId,
                    selectedChoiceId = artifact.assessmentItems.single().choices.first().id,
                    responseOrdinal = 1,
                    durationSeconds = index + 1,
                    occurredAtEpochMillis = progressedAt,
                )
                val submitted = firstRepository.submitReviewChoice(
                    sessionId = progress.sessionId,
                    expectedStateVersion = expectedVersion,
                    submission = submission,
                )
                progress = submitted.progress
                assertTrue(submitted.attempt.created)
                assertEquals(index + 1, progress.currentOrdinal)
                assertEquals((index + 1).toLong(), progress.stateVersion)
                val replay = firstRepository.submitReviewChoice(
                    sessionId = progress.sessionId,
                    expectedStateVersion = expectedVersion,
                    submission = submission,
                )
                assertEquals(progress, replay.progress)
                assertFalse(replay.attempt.created)
            }
            assertEquals(StudyReviewSessionStatus.COMPLETED, progress.status)
            assertTrue(firstRepository.snapshot.value.review.completedToday)
            assertEquals(1, firstRepository.snapshot.value.review.completionStreakDays)
            assertEquals(null, firstRepository.snapshot.value.review.activeSessionId)
            progress
        } finally {
            firstRepository.close()
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val secondRepository = repository(database, secondScope)
        try {
            secondRepository.initialize()
            val resumed = requireNotNull(
                secondRepository.startOrResumeReviewSession("another-start", startedAt + 100),
            )
            assertEquals(completed, resumed)
            assertTrue(secondRepository.snapshot.value.review.completedToday)
            assertEquals(1, secondRepository.snapshot.value.review.completionStreakDays)
        } finally {
            secondRepository.close()
            secondScope.cancel()
        }
    }

    @Test
    fun reviewAnswerAtomicallyAdvancesAndReplaysAcrossLocalMidnight() = runBlocking {
        val database = FakeStudyDatabasePort()
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val zone = ZoneId.of("Asia/Shanghai")
        val clock = MutableClock(Instant.parse("2026-01-02T15:59:50Z"), zone)
        val repository = repository(database, applicationScope, clock)

        try {
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("cross-midnight", clock.millis()),
            )
            val practiceUnitId = repository.snapshot.value.review.scheduledPracticeUnitIds.first()
            val submittedChoice = requireNotNull(repository.teachingArtifact(practiceUnitId))
                .assessmentItems.single().choices.single { choice -> choice.id == "A" }
            clock.moveTo(Instant.parse("2026-01-02T16:00:10Z"))
            val submission = StudyChoiceSubmission(
                requestId = "review-cross-midnight-choice",
                presentationId = "presentation:${started.sessionId}:${started.stateVersion}",
                practiceUnitId = practiceUnitId,
                selectedChoiceId = "A",
                responseOrdinal = 1,
                durationSeconds = 20,
                occurredAtEpochMillis = clock.millis(),
            )

            val first = repository.submitReviewChoice(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )
            val replay = repository.submitReviewChoice(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = submission,
            )

            assertEquals(1, first.progress.currentOrdinal)
            assertEquals(started.sessionId, repository.snapshot.value.review.activeSessionId)
            assertEquals(1, repository.snapshot.value.review.currentOrdinal)
            assertEquals(first.progress, replay.progress)
            assertEquals(first.attempt.attemptId, replay.attempt.attemptId)
            assertFalse(replay.attempt.created)
            assertEquals(
                AttemptSubmittedResponse.Choice(
                    choiceId = submittedChoice.id,
                    choiceMarkdown = submittedChoice.markdown,
                    submittedAtEpochMillis = submission.occurredAtEpochMillis,
                ),
                database.lastAttemptCommand?.submittedResponse,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun leechedProblemOffersItsMisconceptionMaterialBeforeTheNextAttempt() = runBlocking {
        // spec §2.16 的"先重教再练"这一半：leech 卡进入复习会话前必须先看到针对错误认知的
        // 材料。此前只有"降权 + 难度冻结"，学员第 7 次打开的还是那道已经连续失败 6 次的题，
        // 没有任何重教发生（审计 §3.9 的实体缺口）。
        val unitId = M1_LEECH_PRACTICE_UNIT_ID
        val database = FakeStudyDatabasePort().apply {
            publishLeechedMemory(unitId)
            addBoundTeachingMaterial(
                materialId = "material:m1:explanation",
                knowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                type = "CONCEPT_EXPLANATION",
                content = "单调性的一般讲解。",
            )
            addBoundTeachingMaterial(
                materialId = "material:m1:misconception",
                knowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                type = "MISCONCEPTION_GUIDE",
                content = "只比较驻点而漏掉端点，是闭区间最值最常见的错误。",
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            val opening = repository.reTeachOpening(unitId)

            // 生效的不只是"有材料"：选中必须是重教优先级最高的 misconception-guide，
            // 而不是先入库的泛泛讲解——顺序由生产选择器给出，不是行序。
            assertEquals("material:m1:misconception", opening?.materialId)
            assertEquals(
                KnowledgeTeachingMaterialType.MISCONCEPTION_GUIDE,
                opening?.materialType,
            )
            assertTrue(
                requireNotNull(opening).markdown
                    .contains("只比较驻点而漏掉端点"),
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun aHealthyProblemIsNotForcedIntoReTeaching() = runBlocking {
        // 反例：没有 leech 的题不该被强制重教——否则重教会退化成每次复习都开场的常规动作，
        // 把"重教材料"这个信号的稀缺性和可信度一并耗光。
        val unitId = M1_LEECH_PRACTICE_UNIT_ID
        val database = FakeStudyDatabasePort().apply {
            addBoundTeachingMaterial(
                materialId = "material:m1:misconception",
                knowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                type = "MISCONCEPTION_GUIDE",
                content = "只比较驻点而漏掉端点。",
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            assertNull(repository.reTeachOpening(unitId))
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun theDailyPlanDemotesAndLabelsAQuestionWhosePrerequisiteIsMissing() = runBlocking {
        // 端到端接线证明（spec §2.9）。这条断言此前**不可能通过**：生产调用点从不给
        // `ReviewPlanningRequest.knowledgePrerequisites` 赋值，前置门恒等于"无前置"。
        // 上面那些测试验的是规则本身，这一条验的是"规则真的被喂到了数据"。
        val database = prerequisitePlannedDatabase(prerequisiteMastery = 0.2)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            val item = database.savedPlans.single().queue.single()
            assertEquals(PREREQ_DEPENDENT_UNIT_ID, item.practiceUnitId)
            assertTrue(
                "缺前置的题必须带着可解释的理由进计划，实际理由：${item.reasons}",
                "PREREQ_GAP" in item.reasons,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun theDailyPlanLeavesAQuestionAloneWhenItsPrerequisitesAreReady() = runBlocking {
        // 反例：前置达标时不该出现前置缺口理由——否则"前置缺失"会退化成所有题的常态标签。
        val database = prerequisitePlannedDatabase(prerequisiteMastery = 0.75)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope, initialFixture = null)

        try {
            repository.initialize()

            val item = database.savedPlans.single().queue.single()
            assertTrue(
                "前置已具备的题不该带前置缺口理由，实际理由：${item.reasons}",
                "PREREQ_GAP" !in item.reasons,
            )
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    private fun prerequisitePlannedDatabase(prerequisiteMastery: Double) =
        FakeStudyDatabasePort().apply {
            addMistake(
                MistakeRecord(
                    entryId = "entry-prereq",
                    problemId = "problem-prereq",
                    problemRevisionId = "revision-prereq",
                    practiceUnitId = PREREQ_DEPENDENT_UNIT_ID,
                    sourceKey = "capture:prereq",
                    subject = "MATH",
                    title = "需要前置的题",
                    problemMarkdown = "判断并证明该函数在闭区间上的单调性。",
                    status = "ACTIVE",
                    createdAtEpochMillis = 1_000,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                    knowledgeNodeIds = setOf(PREREQ_DEPENDENT_KNOWLEDGE_NODE_ID),
                ),
            )
            addPrerequisiteRelation(
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                dependentKnowledgeNodeId = PREREQ_DEPENDENT_KNOWLEDGE_NODE_ID,
            )
            addKnowledgeNode(PREREQ_NODE_ID, displayName = "从图像读取单调性")
            publishMastery(
                dependentKnowledgeNodeId = PREREQ_DEPENDENT_KNOWLEDGE_NODE_ID,
                dependentMastery = 0.9,
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                prerequisiteMastery = prerequisiteMastery,
            )
        }

    @Test
    fun aMissingPrerequisiteOffersThatPrerequisitesMaterialBesideTheQuestion() = runBlocking {
        // spec §2.9：目标题绑定的 KC 有一个前置未达可学门槛时，注入**那个前置**的材料。
        // 此前这条通道从未接线——`ReviewPlanningRequest.knowledgePrerequisites` 在生产调用点
        // 没被填过，闸门恒等于"无前置"。
        val unitId = M1_LEECH_PRACTICE_UNIT_ID
        val database = FakeStudyDatabasePort().apply {
            addPrerequisiteRelation(
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
            )
            addKnowledgeNode(PREREQ_NODE_ID, displayName = "从图像读取单调性")
            addBoundTeachingMaterial(
                materialId = "material:prereq:explanation",
                knowledgeNodeId = PREREQ_NODE_ID,
                type = "CONCEPT_EXPLANATION",
                content = "单调性的一般讲解。",
            )
            addBoundTeachingMaterial(
                materialId = "material:prereq:misconception",
                knowledgeNodeId = PREREQ_NODE_ID,
                type = "MISCONCEPTION_GUIDE",
                content = "只按局部形状下结论，是读图判断单调性最常见的错误。",
            )
            publishMastery(
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                dependentMastery = 0.9,
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                prerequisiteMastery = 0.2,
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            val remediation = repository.prerequisiteRemediation(unitId)

            // 生效的不只是"有材料"：必须是重教优先级最高的 misconception-guide，而不是先
            // 入库的泛泛讲解——顺序由生产选择器给出，不是行序。
            assertEquals("材料 material:prereq:misconception", remediation?.title)
            assertTrue(requireNotNull(remediation).markdown.contains("只按局部形状下结论"))
            // 适用边界必须一并呈现，否则学员会把它外推到不成立的题目上。
            assertTrue(requireNotNull(remediation).markdown.contains("只在题意满足时使用"))
            // 卡片要说出补的是哪一个前置，否则学员看到一段无来由的材料。
            assertEquals("从图像读取单调性", requireNotNull(remediation).prerequisiteName)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun aProblemWhosePrerequisitesAreReadyOffersNoRemediation() = runBlocking {
        // 反例：前置已具备时不该弹补救卡。否则每一个有前置关系的题都会变成关卡，
        // "前置缺失"这个信号会退化成常态噪声。
        val unitId = M1_LEECH_PRACTICE_UNIT_ID
        val database = FakeStudyDatabasePort().apply {
            addPrerequisiteRelation(
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
            )
            addKnowledgeNode(PREREQ_NODE_ID, displayName = "从图像读取单调性")
            addBoundTeachingMaterial(
                materialId = "material:prereq:misconception",
                knowledgeNodeId = PREREQ_NODE_ID,
                type = "MISCONCEPTION_GUIDE",
                content = "只按局部形状下结论。",
            )
            publishMastery(
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                dependentMastery = 0.9,
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                prerequisiteMastery = 0.75,
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            assertNull(repository.prerequisiteRemediation(unitId))
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun aWeakPrerequisiteWithNoReviewedMaterialOffersNoRemediation() = runBlocking {
        // 前置确实缺失、但材料库里没有它的内容：不能编造补救内容，也不能假装补救发生过。
        val unitId = M1_LEECH_PRACTICE_UNIT_ID
        val database = FakeStudyDatabasePort().apply {
            addPrerequisiteRelation(
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
            )
            addKnowledgeNode(PREREQ_NODE_ID, displayName = "从图像读取单调性")
            publishMastery(
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                dependentMastery = 0.9,
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                prerequisiteMastery = 0.1,
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            assertNull(repository.prerequisiteRemediation(unitId))
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun aQuestionWithNoRecordedPrerequisiteOffersNoRemediation() = runBlocking {
        // 没有前置关系 ≠ 前置缺失。若把两者混为一谈，任何一道新绑定的题都会被判成缺前置。
        val unitId = M1_LEECH_PRACTICE_UNIT_ID
        val database = FakeStudyDatabasePort().apply {
            addBoundTeachingMaterial(
                materialId = "material:prereq:misconception",
                knowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                type = "MISCONCEPTION_GUIDE",
                content = "只按局部形状下结论。",
            )
            publishMastery(
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                dependentMastery = 0.9,
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                prerequisiteMastery = 0.1,
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            assertNull(repository.prerequisiteRemediation(unitId))
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    /**
     * 前置材料的检索必须用**前置 KC 自己的科目**：`TutorTeachingReferenceSelector` 会按
     * subject 过滤，用题目的科目去查在两者不一致时会静默拿到空集——前置关系看起来"不存在"，
     * 而不是查询出错了。这里让前置节点的科目与题目科目不同，锁定的是"以节点为准"。
     */
    @Test
    fun thePrerequisiteMaterialIsLookedUpUnderThePrerequisiteNodeSubject() = runBlocking {
        val unitId = M1_LEECH_PRACTICE_UNIT_ID
        val database = FakeStudyDatabasePort().apply {
            addPrerequisiteRelation(
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
            )
            addKnowledgeNode(PREREQ_NODE_ID, displayName = "从图像读取单调性", subject = "PHYSICS")
            addBoundTeachingMaterial(
                materialId = "material:prereq:misconception",
                knowledgeNodeId = PREREQ_NODE_ID,
                type = "MISCONCEPTION_GUIDE",
                content = "只按局部形状下结论。",
                subject = "PHYSICS",
            )
            publishMastery(
                dependentKnowledgeNodeId = M1_LEECH_KNOWLEDGE_NODE_ID,
                dependentMastery = 0.9,
                prerequisiteKnowledgeNodeId = PREREQ_NODE_ID,
                prerequisiteMastery = 0.2,
            )
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            assertNotNull(repository.prerequisiteRemediation(unitId))
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    private fun FakeStudyDatabasePort.addBoundTeachingMaterial(
        materialId: String,
        knowledgeNodeId: String,
        type: String,
        content: String,
        subject: String = "MATH",
    ) {
        teachingMaterials += KnowledgeTeachingMaterialRecord(
            materialId = materialId,
            stableCode = materialId,
            subject = subject,
            materialType = type,
            title = "材料 $materialId",
            summaryMarkdown = "摘要",
            applicabilityMarkdown = "适用于该知识点。",
            contentMarkdown = content,
            boundaryMarkdown = "只在题意满足时使用。",
            derivationKind = "REVIEWED",
            sourceId = "source:$materialId",
            sourceLocator = "m1",
            contentFingerprint = "fp-$materialId",
            reviewedAtEpochMillis = 1_000,
        )
        materialNodeBindings += KnowledgeTeachingMaterialNodeBindingRecord(
            materialId = materialId,
            knowledgeNodeId = knowledgeNodeId,
            role = "PRIMARY",
        )
    }

    private fun FakeStudyDatabasePort.addKnowledgeNode(
        knowledgeNodeId: String,
        displayName: String,
        subject: String = "MATH",
    ) {
        if (knowledgeNodes.any { it.knowledgeNodeId == knowledgeNodeId }) return
        knowledgeNodes += KnowledgeNodeSeedRecord(
            knowledgeNodeId = knowledgeNodeId,
            stableCode = knowledgeNodeId,
            subject = subject,
            displayName = displayName,
            parentKnowledgeNodeId = null,
            taxonomyVersion = "taxonomy-m1",
            createdAtEpochMillis = 1_000,
        )
    }

    private fun FakeStudyDatabasePort.addPrerequisiteRelation(
        prerequisiteKnowledgeNodeId: String,
        dependentKnowledgeNodeId: String,
        subject: String = "MATH",
    ) {
        // 被查询的 KC 必须在知识库里存在：它的科目决定关系表的分区查询用哪个 subject。
        addKnowledgeNode(dependentKnowledgeNodeId, displayName = "闭区间上的函数最值")
        knowledgeNodeRelations += KnowledgeNodeRelationRecord(
            relationId = "relation:$prerequisiteKnowledgeNodeId->$dependentKnowledgeNodeId",
            subject = subject,
            prerequisiteKnowledgeNodeId = prerequisiteKnowledgeNodeId,
            dependentKnowledgeNodeId = dependentKnowledgeNodeId,
            relationType = StudyDbValue.KnowledgeRelationType.PREREQUISITE_OF,
            sourceId = "source:m1",
            sourceLocator = "m1",
            reviewedAtEpochMillis = 1_000,
        )
    }

    /**
     * 把两个 KC 的掌握度写进当前投影（实现在 [FakeStudyDatabasePort.publishMastery]，
     * 那里才能碰到私有的投影字段）。
     */
    private fun repository(
        database: StudyDatabasePort,
        applicationScope: CoroutineScope,
        clock: Clock = Clock.fixed(
            Instant.parse("2026-01-02T08:00:00Z"),
            ZoneId.of("Asia/Shanghai"),
        ),
        initialFixture: StudySeedBundle? = M1CuratedStudySeed.bundle(includeTutorMistake = false),
    ) = RoomBackedStudyExperienceRepository(
        database = database,
        applicationScope = applicationScope,
        clock = clock,
        studyZoneId = ZoneId.of("Asia/Shanghai"),
        initialFixture = initialFixture,
        fixtureSource = M1CuratedFixtureSource,
    )

    private fun visualIngestMistake(
        entryId: String = "entry-v",
        practiceUnitId: String = "unit-v",
        problemRevisionId: String = "revision-v",
    ) = MistakeRecord(
        entryId = entryId,
        problemId = "problem-v",
        problemRevisionId = problemRevisionId,
        practiceUnitId = practiceUnitId,
        sourceKey = "capture:visual",
        subject = "MATH",
        title = "几何作图题",
        problemMarkdown = "作出满足条件的图形。",
        status = "ACTIVE",
        createdAtEpochMillis = 1_000,
        nextReviewAtEpochMillis = null,
        retrievability = null,
        knowledgeNodeIds = setOf("knowledge:visual"),
    )

    private fun visualIngestBinding(
        practiceUnitId: String = "unit-v",
        basisRevisionId: String = "revision-v",
    ) = PracticeUnitKnowledgeBindingRecord(
        bindingId = "binding-v",
        practiceUnitId = practiceUnitId,
        knowledgeNodeId = "knowledge:visual",
        basisRevisionId = basisRevisionId,
        taxonomyVersion = "taxonomy-v1",
        acceptedAtEpochMillis = 900,
    )

    private fun visualAttemptRecord(
        attemptId: String,
        feasible: Boolean,
        actionKind: String = "DragPoint",
        problemRevisionId: String = "revision-v",
        feedback: String = "操作判定记录",
    ) = VisualInteractionAttemptRecord(
        attemptId = attemptId,
        problemRevisionId = problemRevisionId,
        actionKind = actionKind,
        actionPayload = "{}",
        feasible = feasible,
        feedback = feedback,
        attemptedAtEpochMillis = 1_500,
    )

    private companion object {
        /** Curated M1 unit whose artifact carries a knowledge-node scope (spec §2.16 fixtures). */
        const val M1_LEECH_PRACTICE_UNIT_ID = "practice:m1:closed-interval-extrema:whole"

        /** The node the M1 artifact declares, and the node teaching material binds to. */
        const val M1_LEECH_KNOWLEDGE_NODE_ID = "knowledge:m1:math.derivative.closed_interval_extrema"

        /** A prerequisite of the M1 node (spec §2.9 fixtures). */
        const val PREREQ_NODE_ID = "knowledge:m1:math.read-monotonicity-from-graph"

        /** A captured question bound to a node that has a prerequisite. */
        const val PREREQ_DEPENDENT_UNIT_ID = "unit-prereq"
        const val PREREQ_DEPENDENT_KNOWLEDGE_NODE_ID = "knowledge:math.monotonicity-symbolic"
    }
}

private class MutableClock(
    private var currentInstant: Instant,
    private val currentZone: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = currentZone

    override fun withZone(zone: ZoneId): Clock = MutableClock(currentInstant, zone)

    override fun instant(): Instant = currentInstant

    fun moveTo(instant: Instant) {
        currentInstant = instant
    }
}

@Suppress("OVERRIDE_DEPRECATION")
internal data class ResolvedPredictionOutcomeCall(
    val practiceUnitId: String,
    val wasIndependentCorrect: Boolean,
    val observedAtEpochMillis: Long,
    val hintCount: Int = 0,
)

/** 对齐 `RoomKnowledgeBaseStore.readKnowledgeNodeRelationsForDependents` 的 256 上限。 */
private const val MAX_DEPENDENT_NODES_PER_QUERY = 256

internal class FakeStudyDatabasePort : StudyDatabasePort {
    private val mistakes = MutableStateFlow<List<MistakeRecord>>(emptyList())
    private val learningLedgerHead = MutableStateFlow(0L)
    val knowledgeGroundingSummaries =
        MutableStateFlow<List<KnowledgeGroundingSummaryRecord>>(emptyList())
    val reviewedKnowledgeCoverage =
        MutableStateFlow<List<ReviewedKnowledgeCoverageRecord>>(emptyList())
    private val entries = linkedMapOf<String, MistakeRecord>()
    private val problemIds = linkedSetOf<String>()
    internal val latestSessions = mutableMapOf<String, ReviewSessionRecord>()
    private val sessionRevisions = mutableMapOf<Pair<String, Long>, ReviewSessionRecord>()
    private val advanceProofs = mutableMapOf<String, AttemptAdvanceProofRecord>()
    private val advanceReceipts = mutableMapOf<String, ReviewSessionAdvanceReceipt>()
    private val evidenceSnapshots = mutableMapOf<String, AssessmentEvidenceSnapshot>()
    private val attemptsBySubmission = mutableMapOf<String, AttemptWriteResult>()
    private var nextEventSequence = 1L
    private var persistedLearnerSnapshot: PersistedLearnerSnapshot? = null
    var lastAttemptCommand: AttemptWriteCommand? = null
        private set
    var lastEvidenceSnapshot: AssessmentEvidenceSnapshot? = null
        private set
    var seedCallCount: Int = 0
        private set
    var failProjectionReads: Boolean = false
    val savedPlans = mutableListOf<ReviewPlanBundle>()
    val tutorExposureReconcileLearners = mutableListOf<String>()
    val recordedPredictions = mutableListOf<StudentModelPredictionRecord>()
    val resolvedPredictionOutcomes = mutableListOf<ResolvedPredictionOutcomeCall>()
    val visualAttempts = mutableListOf<VisualInteractionAttemptRecord>()
    val practiceUnitBindings = mutableListOf<PracticeUnitKnowledgeBindingRecord>()
    val knowledgeNodes = mutableListOf<KnowledgeNodeSeedRecord>()
    val knowledgeNodeRelations = mutableListOf<KnowledgeNodeRelationRecord>()
    val teachingMaterials = mutableListOf<KnowledgeTeachingMaterialRecord>()
    val materialNodeBindings = mutableListOf<KnowledgeTeachingMaterialNodeBindingRecord>()
    val recordedChatEvidence =
        mutableListOf<com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity>()
    val reviewLogEntries = mutableListOf<ReviewLogEntry>()
    val teachingAdvisories = mutableListOf<TeachingAdvisoryRecord>()
    val resolvedStudentModelPredictions =
        mutableListOf<ResolvedStudentModelPredictionRecord>()
    val pseudoBindingCalls = mutableListOf<String>()
    var pseudoKnowledgeBindingEnabled = true

    /**
     * 讲题会话的检查题作答行。MASTERY_UPDATE 的客观交叉核对（研究
     * tutor-evidence-gate §3.2）按 sessionId 回读它，故此处必须按会话过滤，
     * 不能一律返回空——否则"学生答错了还判正向"这条路径在测试里不可达。
     */
    val tutorTurnResponses = mutableListOf<TutorTurnResponseRecord>()

    /**
     * Tutor conversation messages of the fake, used to give the MASTERY_UPDATE
     * evidence-anchor verification a real session corpus. Empty by default:
     * a blank corpus verifies zero anchors, which is the fail-closed posture.
     */
    val tutorMessages = mutableListOf<TutorMessageRecord>()

    override suspend fun recordStudentModelPredictions(
        predictions: List<StudentModelPredictionRecord>,
    ) {
        recordedPredictions += predictions
    }

    override suspend fun resolveStudentModelPredictions(
        practiceUnitId: String,
        wasIndependentCorrect: Boolean,
        observedAtEpochMillis: Long,
        responseLatencyMs: Long?,
        hintCount: Int,
    ): Int {
        resolvedPredictionOutcomes += ResolvedPredictionOutcomeCall(
            practiceUnitId = practiceUnitId,
            wasIndependentCorrect = wasIndependentCorrect,
            observedAtEpochMillis = observedAtEpochMillis,
            hintCount = hintCount,
        )
        return recordedPredictions.count { it.practiceUnitId == practiceUnitId }
    }

    override suspend fun readResolvedStudentModelPredictions(
        modelId: String,
        modelVersion: String,
    ): List<ResolvedStudentModelPredictionRecord> =
        resolvedStudentModelPredictions.filter { prediction ->
            prediction.modelId == modelId && prediction.modelVersion == modelVersion
        }

    override suspend fun readVisualInteractionAttempts(
        problemRevisionId: String,
    ): List<VisualInteractionAttemptRecord> =
        visualAttempts.filter { it.problemRevisionId == problemRevisionId }

    override suspend fun readPracticeUnitKnowledgeBindings(
        practiceUnitId: String,
    ): List<PracticeUnitKnowledgeBindingRecord> =
        practiceUnitBindings.filter { it.practiceUnitId == practiceUnitId }

    override suspend fun reserveModelTaskRemoteDispatch(
        command: ReserveModelTaskRemoteDispatchCommand,
    ): ModelTaskDispatchReservationResult =
        error("Model task dispatch reservations are outside this study-repository fake")

    override suspend fun snapshotForBackup(sourceDatabaseFile: File, snapshotTarget: File) = Unit

    val recordedAttemptCount: Int
        get() = attemptsBySubmission.size

    fun addVisualInteractionAttempt(attempt: VisualInteractionAttemptRecord) {
        visualAttempts += attempt
    }

    fun addPracticeUnitKnowledgeBinding(binding: PracticeUnitKnowledgeBindingRecord) {
        practiceUnitBindings += binding
    }

    val problemCount: Int
        get() = problemIds.size

    fun addMistake(mistake: MistakeRecord) {
        entries[mistake.entryId] = mistake
        problemIds += mistake.problemId
        mistakes.value = entries.values.toList()
    }

    fun publishTutorExposure(practiceUnitId: String) {
        val outcome = TutorAnswerExposureOutcome(
            outcomeId = "tutor-exposure-outcome-1",
            exposureId = "tutor-exposure-1",
            sessionId = "current-tutor-session",
            questionDocumentId = "current-question-document",
            questionRevisionNumber = 1,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            problemRevisionId = "current-problem-revision",
            practiceUnitId = practiceUnitId,
            occurredAtEpochMillis = 1_000,
            eventSequence = 1,
        )
        val snapshot = LearningProjector().replay(
            learnerId = "learner:local",
            ledger = listOf(outcome),
        ).snapshot
        persistedLearnerSnapshot = PersistedLearnerSnapshot(
            projectionName = "study-experience-v1",
            stateVersion = 1,
            knownLedgerHeadSequence = 1,
            snapshot = snapshot,
        )
        learningLedgerHead.value = 1
    }

    /**
     * Seeds a leeched problem-memory state (spec §2.16: six lapses plus two
     * cross-day Again) as the fake's current projection, so the re-teach path can
     * be exercised without driving six real lapses through the ledger.
     */
    fun publishLeechedMemory(practiceUnitId: String) {
        val leeched = ProblemMemoryState(
            practiceUnitId = practiceUnitId,
            stabilityDays = 2.0,
            difficulty = 9.0,
            lastReviewedAtEpochMillis = 10L * 86_400_000L,
            nextReviewAtEpochMillis = 12L * 86_400_000L,
            lapseCount = ProblemMemoryState.LEECH_LAPSE_THRESHOLD,
            consecutiveCrossDayAgain = ProblemMemoryState.LEECH_AGAIN_STREAK,
            lastAttemptId = "attempt:leeched",
            projectorVersion = LearningProjector.VERSION,
            checkpointSequence = 1,
        )
        persistedLearnerSnapshot = PersistedLearnerSnapshot(
            projectionName = "study-experience-v1",
            stateVersion = 1,
            knownLedgerHeadSequence = 1,
            snapshot = LearnerSnapshot(
                learnerId = "learner:local",
                problemMemoryStates = mapOf(practiceUnitId to leeched),
                checkpoint = ProjectionCheckpoint(
                    lastSequence = 1,
                    projectorVersion = LearningProjector.VERSION,
                    projectedAtEpochMillis = 10L * 86_400_000L,
                ),
                generatedAtEpochMillis = 10L * 86_400_000L,
            ),
        )
        learningLedgerHead.value = 1
    }

    /**
     * Seeds mastery for two knowledge nodes (spec §2.9) as the fake's current
     * projection, so the prerequisite gate can be exercised without driving real
     * evidence through the ledger.
     *
     * The decision value is `conservativeMasteryScore` — §2.9 reads the
     * conservative lower bound, not the point estimate.
     */
    fun publishMastery(
        dependentKnowledgeNodeId: String,
        dependentMastery: Double,
        prerequisiteKnowledgeNodeId: String,
        prerequisiteMastery: Double,
    ) {
        persistedLearnerSnapshot = PersistedLearnerSnapshot(
            projectionName = "study-experience-v1",
            stateVersion = 1,
            knownLedgerHeadSequence = 1,
            snapshot = LearnerSnapshot(
                learnerId = "learner:local",
                problemMemoryStates = emptyMap(),
                knowledgeMasteryStates = mapOf(
                    dependentKnowledgeNodeId to masteryStateFor(
                        dependentKnowledgeNodeId,
                        dependentMastery,
                    ),
                    prerequisiteKnowledgeNodeId to masteryStateFor(
                        prerequisiteKnowledgeNodeId,
                        prerequisiteMastery,
                    ),
                ),
                checkpoint = ProjectionCheckpoint(
                    lastSequence = 1,
                    projectorVersion = LearningProjector.VERSION,
                    projectedAtEpochMillis = 1_000,
                ),
                generatedAtEpochMillis = 1_000,
            ),
        )
        learningLedgerHead.value = 1
    }

    private fun masteryStateFor(
        knowledgeNodeId: String,
        conservativeMastery: Double,
    ) = KnowledgeMasteryState(
        knowledgeNodeId = knowledgeNodeId,
        masteryScore = conservativeMastery,
        conservativeMasteryScore = conservativeMastery,
        evidenceMass = 2.0,
        status = MasteryStatus.LEARNING,
        calibrationSupport = CalibrationSupport.SUPPORTED,
        projectorVersion = LearningProjector.VERSION,
        checkpointSequence = 1,
    )

    override fun observeMistakes(): Flow<List<MistakeRecord>> = mistakes

    override suspend fun updateErrorBookEntryNote(
        entryId: String,
        note: String?,
        updatedAtEpochMillis: Long,
    ): Boolean = error("entry notes are outside this study-repository fake")

    override suspend fun archiveErrorBookEntry(entryId: String, at: Long): Boolean =
        error("archive is outside this study-repository fake")

    override suspend fun restoreErrorBookEntry(entryId: String, at: Long): Boolean =
        error("restore is outside this study-repository fake")

    override fun observeArchivedErrorBookEntries(): Flow<List<String>> = flowOf(emptyList())

    override fun observeModelTask(requestId: String): Flow<ModelTaskSnapshot?> =
        MutableStateFlow(null)

    override suspend fun readModelTask(requestId: String): ModelTaskSnapshot? = null

    override suspend fun createModelTask(
        command: CreateModelTaskCommand,
    ): ModelTaskWriteResult = error("Model tasks are outside this study-repository fake")

    override suspend fun transitionModelTask(
        command: TransitionModelTaskCommand,
    ): ModelTaskWriteResult = error("Model tasks are outside this study-repository fake")

    override suspend fun recordChatEvidence(entries: List<com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity>) {
        recordedChatEvidence += entries
    }

    override suspend fun readChatEvidenceByLearner(learnerId: String): List<com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity> = emptyList()

    override suspend fun readChatEvidenceByConversation(conversationId: String): List<com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity> = emptyList()

    override suspend fun lastAcceptedChatEvidenceAtForKc(learnerId: String, knowledgeNodeId: String): Long? = null

    override suspend fun countAcceptedChatEvidenceSince(learnerId: String, sinceEpochMillis: Long): Int = 0

    /** Per-conversation accepted-write counts; tests seed this to model quota use. */
    val acceptedChatEvidenceByConversation: MutableMap<String, Int> = mutableMapOf()

    override suspend fun countAcceptedChatEvidenceInConversation(conversationId: String): Int =
        acceptedChatEvidenceByConversation[conversationId] ?: 0

    override suspend fun countRejectedChatEvidenceByReason(learnerId: String): List<com.tingyun.smartmistakebook.core.database.dao.RejectedReasonCountRow> = emptyList()

    override suspend fun countAcceptedChatEvidencePerHour(learnerId: String, sinceEpochMillis: Long): List<com.tingyun.smartmistakebook.core.database.dao.HourlyAcceptedCountRow> = emptyList()

    override fun observePendingProblemDraftCount(): Flow<Int> = MutableStateFlow(0)

    override fun observeLearningLedgerHead(learnerId: String): Flow<Long> = learningLedgerHead

    override fun observePendingKnowledgeGroundingSummaries(
        limit: Int,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> = knowledgeGroundingSummaries

    override fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        reviewedKnowledgeCoverage

    override fun observeReviewPlan(reviewPlanId: String): Flow<ReviewPlanBundle?> =
        MutableStateFlow(savedPlans.lastOrNull { it.plan.reviewPlanId == reviewPlanId })

    override fun observeReviewPlanForSession(sessionId: String): Flow<ReviewPlanBundle?> =
        MutableStateFlow(
            savedPlans.lastOrNull { plan ->
                plan.activeSession?.reviewSessionId == sessionId ||
                    plan.latestSession?.reviewSessionId == sessionId
            },
        )

    override fun observeActiveReviewPlan(learnerId: String): Flow<ReviewPlanBundle?> =
        MutableStateFlow(
            savedPlans.lastOrNull { plan ->
                plan.plan.learnerId == learnerId && plan.activeSession != null
            },
        )

    override fun observeCurrentReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanBundle?> = MutableStateFlow(
        savedPlans.lastOrNull { plan ->
            plan.isCurrent &&
                plan.plan.learnerId == learnerId &&
                plan.plan.localDayEpochDay == localDayEpochDay &&
                plan.plan.timeZoneId == timeZoneId
        },
    )

    override fun observeCompletedReviewLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>> = MutableStateFlow(
        savedPlans.asReversed()
            .asSequence()
            .filter { plan ->
                plan.plan.learnerId == learnerId &&
                    plan.latestSession?.status == StudyDbValue.ReviewStatus.COMPLETED
            }
            .map { it.plan.localDayEpochDay }
            .distinct()
            .take(limit)
            .toList(),
    )

    override suspend fun countMistakes(): Int = entries.size

    override suspend fun reconcileTutorAnswerExposures(learnerId: String, limit: Int): Int {
        tutorExposureReconcileLearners += learnerId
        return 0
    }

    override suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRecord? =
        entries.values.firstOrNull { it.sourceKey == sourceKey }

    override suspend fun createProblemDraft(
        command: CreateProblemDraftCommand,
    ): ProblemDraftWriteResult = error("Capture is outside this study-repository fake")

    override suspend fun reviseProblemDraft(
        command: ReviseProblemDraftCommand,
    ): ProblemDraftWriteResult = error("Capture is outside this study-repository fake")

    override suspend fun readProblemDraft(draftId: String): ProblemDraftRecord? = null

    override suspend fun commitProblemDraft(
        command: CommitProblemDraftCommand,
    ): CommitProblemDraftResult = error("Capture is outside this study-repository fake")

    override suspend fun appendProblemDraftSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult = error("Capture is outside this study-repository fake")

    override suspend fun replaceProblemDraft(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult = error("Capture is outside this study-repository fake")

    override suspend fun splitProblemDraft(
        command: SplitProblemDraftCommand,
    ): ProblemDraftSplitResult = error("Capture is outside this study-repository fake")

    override suspend fun readProblemDraftEditWorkspace(
        draftId: String,
    ): ProblemDraftEditWorkspaceRecord? = null

    override suspend fun saveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult =
        error("Capture is outside this study-repository fake")

    override suspend fun consumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ): Boolean = error("Capture is outside this study-repository fake")

    override suspend fun confirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ): CommitProblemDraftResult = error("Capture is outside this study-repository fake")

    override suspend fun confirmTutorSession(
        command: ConfirmTutorSessionCommand,
    ): TutorSessionWriteResult = error("Capture is outside this study-repository fake")

    override suspend fun confirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ): TutorSessionWriteResult = error("Capture is outside this study-repository fake")

    override suspend fun attachCleanRedrawAsset(
        revisionId: String,
        asset: com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord,
    ): Boolean = error("Capture is outside this study-repository fake")

    override suspend fun readTutorSession(sessionId: String): TutorSessionRecord? = null

    override suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult = error("Capture is outside this study-repository fake")

    override suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult = error("Capture is outside this study-repository fake")

    override suspend fun recordTutorChoice(
        command: PersistTutorChoiceCommand,
    ): TutorTurnResponseRecord = error("Capture is outside this study-repository fake")

    override suspend fun recordTutorMove(
        command: PersistTutorMoveCommand,
    ): TutorTurnResponseRecord = error("Capture is outside this study-repository fake")

    override suspend fun revealTutorSolution(
        command: PersistTutorRevealCommand,
    ): TutorTurnResponseRecord = error("Capture is outside this study-repository fake")

    override suspend fun recordTutorSolutionExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord = error("Capture is outside this study-repository fake")

    override suspend fun bindTutorSessionProblemAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord =
        error("Capture is outside this study-repository fake")

    override suspend fun confirmProblemOrganization(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult =
        error("Organization is outside this study-repository fake")

    override fun libraryPagingSource(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
    ): PagingSource<Int, LibraryCatalogRow> =
        error("Library is outside this study-repository fake")

    override suspend fun libraryCatalogPage(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> =
        error("Library is outside this study-repository fake")

    override suspend fun libraryCatalogCount(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int = error("Library is outside this study-repository fake")

    override suspend fun libraryCatalogFacets(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> =
        error("Library is outside this study-repository fake")

    override fun observeTutorTurnResponses(
        sessionId: String,
    ): Flow<List<TutorTurnResponseRecord>> = MutableStateFlow(
        tutorTurnResponses.filter { it.sessionId == sessionId },
    )

    override fun observeRecentTutorConversations(
        limit: Int,
    ): Flow<List<TutorConversationRecord>> = MutableStateFlow(emptyList())

    override fun observeTutorMessages(
        conversationId: String,
    ): Flow<List<TutorMessageRecord>> = MutableStateFlow(
        tutorMessages.filter { it.conversationId == conversationId },
    )

    override fun observeTutorConversation(
        conversationId: String,
    ): Flow<TutorConversationRecord?> = MutableStateFlow(null)

    override suspend fun createTutorConversation(
        command: CreateTutorConversationDatabaseCommand,
    ): TutorConversationRecord = error("Capture is outside this study-repository fake")

    override suspend fun appendTutorStudentMessage(
        command: AppendTutorStudentMessageDatabaseCommand,
    ): TutorMessageRecord = error("Capture is outside this study-repository fake")

    override suspend fun appendTutorAssistantMessage(
        command: AppendTutorAssistantMessageDatabaseCommand,
    ): TutorMessageRecord = error("Capture is outside this study-repository fake")

    override suspend fun updateTutorMessageStatus(
        command: UpdateTutorMessageStatusDatabaseCommand,
    ): TutorMessageRecord = error("Capture is outside this study-repository fake")

    override suspend fun pauseTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord = error("Capture is outside this study-repository fake")

    override suspend fun archiveTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord = error("Capture is outside this study-repository fake")

    override suspend fun deleteTutorConversation(conversationId: String) = Unit

    override suspend fun saveTutorConversationDraft(
        conversationId: String,
        draft: String,
        updatedAtEpochMillis: Long,
    ) = Unit

    override suspend fun clearTutorConversationDraft(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ) = Unit

    override fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>> =
        MutableStateFlow(emptyList())

    override suspend fun readPendingCaptureDraft(draftId: String): PendingCaptureDraftRecord? =
        null

    override suspend fun readCanonicalSourceAsset(sourceAssetId: String):
        CanonicalSourceAssetRecord? = null

    override suspend fun readUnreferencedCanonicalAssets():
        List<CanonicalSourceAssetRecord> = emptyList()

    override suspend fun deleteUnreferencedCanonicalAssets(): Int = 0

    override suspend fun insertOrphanCanonicalAssetForTest(asset: CanonicalSourceAssetRecord) =
        Unit

    override suspend fun readSubjectKnowledgeNodes(subject: String, limit: Int):
        List<KnowledgeNodeSeedRecord> = emptyList()

    override suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> = emptyList()

    override suspend fun readKnowledgeNodesByIds(ids: Set<String>):
        List<KnowledgeNodeSeedRecord> = knowledgeNodes.filter { it.knowledgeNodeId in ids }

    override suspend fun readKnowledgeSourcesByIds(ids: Set<String>):
        List<KnowledgeSourceSeedRecord> = emptyList()

    override suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord> = emptyList()

    override suspend fun readSubjectKnowledgeNodeRelations(subject: String, limit: Int):
        List<KnowledgeNodeRelationRecord> = emptyList()

    override suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord> {
        // 与 RoomKnowledgeBaseStore 同一条硬约束：分块不是优化，是正确性前提。把它复制到
        // 假实现里，才能让"调用方是否分块"在测试中真的可判定。
        require(dependentKnowledgeNodeIds.size <= MAX_DEPENDENT_NODES_PER_QUERY)
        return knowledgeNodeRelations.filter { relation ->
            relation.subject == subject && relation.dependentKnowledgeNodeId in dependentKnowledgeNodeIds
        }
    }

    override suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord> = teachingMaterials
        .asSequence()
        .filter { material ->
            material.subject == subject &&
                materialNodeBindings.any { binding ->
                    binding.materialId == material.materialId &&
                        binding.knowledgeNodeId in knowledgeNodeIds
                }
        }
        .take(limit)
        .toList()

    override suspend fun readKnowledgeTeachingMaterialsByIds(materialIds: Set<String>):
        List<KnowledgeTeachingMaterialRecord> =
        teachingMaterials.filter { it.materialId in materialIds }

    override suspend fun readKnowledgeTeachingMaterialNodeBindings(materialIds: Set<String>):
        List<KnowledgeTeachingMaterialNodeBindingRecord> =
        materialNodeBindings.filter { it.materialId in materialIds }

    override fun observePendingKnowledgeGroundingRequests(
        limit: Int,
    ): Flow<List<KnowledgeGroundingRequestRecord>> = MutableStateFlow(emptyList())

    override suspend fun readPendingKnowledgeResearchReviewBundles(limit: Int):
        List<KnowledgeResearchReviewBundleRecord> = emptyList()

    override suspend fun readKnowledgeResearchReviewBundle(bundleId: String):
        KnowledgeResearchReviewBundleRecord? = null

    override suspend fun readKnowledgeGroundingResolution(groundingKey: String):
        KnowledgeGroundingResolutionRecord? = null

    override suspend fun importKnowledgeNodeRelations(
        relations: List<KnowledgeNodeRelationRecord>,
    ) = Unit

    override suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    ) = Unit

    override suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ) = Unit

    override suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = emptyList()

    override suspend fun enqueueKnowledgeResearchReviewBundle(
        bundle: KnowledgeResearchReviewBundleRecord,
    ) = Unit

    override suspend fun decideKnowledgeResearchReviewBundle(
        command: DecideKnowledgeResearchReviewBundleCommand,
    ): KnowledgeResearchReviewBundleRecord =
        error("Knowledge review is outside this study-repository fake")

    override suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = emptyList()

    override suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    ) = Unit

    override suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord =
        error("Knowledge grounding is outside this study-repository fake")

    override suspend fun readMistakeDetail(errorBookEntryId: String): MistakeDetailRecord? =
        null

    override suspend fun readExactMistakeDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord? = null

    override suspend fun readCurrentMistakeDetails(entryIds: List<String>):
        List<MistakeDetailRecord> = emptyList()

    override suspend fun readMistakeRevisionHistory(problemId: String):
        List<MistakeRevisionSummaryRecord> = emptyList()

    override suspend fun checkpointForBackup() = Unit

    override suspend fun clearAllData() {
        entries.clear()
        mistakes.value = emptyList()
    }

    override fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>> =
        MutableStateFlow(emptyList())

    override fun observeActiveSplitImports(): Flow<List<SplitImportJobRecord>> =
        MutableStateFlow(emptyList())

    override suspend fun readSplitImportJob(jobId: String): SplitImportJobRecord? = null

    override suspend fun createSplitImportJob(
        command: CreateSplitImportJobCommand,
        questions: List<SplitImportQuestionSeed>,
    ): SplitImportJobRecord = error("Split import is outside this study-repository fake")

    override suspend fun markSplitImportReady(
        jobId: String,
        questionCount: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun updateSplitImportSelection(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun markSplitImportQuestionConfirmed(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun completeSplitImportJob(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun abandonSplitImportJob(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun createBatchImportJob(
        command: CreateBatchImportJobCommand,
    ): BatchImportJobRecord = error("Batch import is outside this study-repository fake")

    override suspend fun readBatchImportJob(jobId: String): BatchImportJobRecord? = null

    override suspend fun updateBatchImportJobStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun requeueInterruptedBatchImportPages(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = 0

    override suspend fun claimNextBatchImportPage(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): BatchImportPageRecord? = null

    override suspend fun completeBatchImportPage(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun claimBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun requeueInterruptedBatchImportBoundaries(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = 0

    override suspend fun resolveBatchImportBoundary(
        command: ResolveBatchImportBoundaryCommand,
    ): BatchImportJobRecord = error("Batch import is outside this study-repository fake")

    override suspend fun failBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun failBatchImportPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun finishBatchImportIfSettled(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = false

    override suspend fun hasRetainedBatchImportSourceUri(sourceUri: String): Boolean = false

    override fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord> = MutableStateFlow(
        ConfirmedProblemOrganizationRecord(
            classifications = emptyList(),
            relations = emptyList(),
            knowledgeNodeIds = emptySet(),
        ),
    )

    override suspend fun seedFixture(bundle: StudySeedBundle): SeedResult {
        seedCallCount++
        val insertedProblems = bundle.problems.count { problemIds.add(it.problemId) }
        val problemsById = bundle.problems.associateBy { it.problemId }
        val revisionsById = bundle.revisions.associateBy { it.revisionId }
        val unitsById = bundle.practiceUnits.associateBy { it.practiceUnitId }
        var insertedEntries = 0
        bundle.errorBookEntries.forEach { entry ->
            if (entry.entryId !in entries) {
                val problem = requireNotNull(problemsById[entry.problemId])
                val revision = requireNotNull(revisionsById[entry.currentRevisionId])
                val unit = requireNotNull(unitsById[entry.practiceUnitId])
                entries[entry.entryId] = MistakeRecord(
                    entryId = entry.entryId,
                    problemId = entry.problemId,
                    problemRevisionId = entry.currentRevisionId,
                    practiceUnitId = entry.practiceUnitId,
                    sourceKey = entry.sourceKey,
                    subject = problem.subject,
                    title = revision.title,
                    problemMarkdown = unit.promptMarkdown,
                    status = entry.status,
                    createdAtEpochMillis = entry.acceptedAtEpochMillis,
                    nextReviewAtEpochMillis = null,
                    retrievability = null,
                )
                insertedEntries++
            }
        }
        mistakes.value = entries.values.toList()
        return SeedResult(insertedProblems, insertedEntries)
    }

    override suspend fun saveAssessmentItemSnapshot(item: AssessmentItemSnapshotSeedRecord) = Unit

    override suspend fun saveAssessmentEvidenceSnapshot(snapshot: AssessmentEvidenceSnapshot) {
        evidenceSnapshots[snapshot.snapshotId] = snapshot
        lastEvidenceSnapshot = snapshot
    }

    override suspend fun appendAssessmentEvent(event: AssessmentEventSeedRecord) = Unit

    override suspend fun recordAttempt(command: AttemptWriteCommand): AttemptWriteResult {
        lastAttemptCommand = command
        attemptsBySubmission[command.submissionId]?.let { return it.copy(created = false) }
        val attempt = Attempt(
            attemptId = command.attemptId,
            presentationId = command.presentationId,
            responseOrdinal = 1,
            assessmentSnapshot = requireNotNull(evidenceSnapshots[command.assessmentSnapshotId]),
            evidence = command.evidence,
            problemMemoryOutcome = command.problemMemoryOutcome,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            durationSeconds = command.durationSeconds,
            studyDay = command.studyDay,
            eventSequence = nextEventSequence++,
            submittedResponse = command.submittedResponse,
        )
        val result = AttemptWriteResult(
            submissionId = command.submissionId,
            created = true,
            attempt = attempt,
            canonicalFingerprint = "fake:${command.attemptId}",
            outboxId = "outbox:${command.attemptId}",
        )
        attemptsBySubmission[command.submissionId] = result
        addAdvanceProof(
            AttemptAdvanceProofRecord(
                learnerId = command.learnerId,
                attemptId = attempt.attemptId,
                submissionId = command.submissionId,
                presentationId = attempt.presentationId,
                practiceUnitId = attempt.practiceUnitId,
                occurredAtEpochMillis = attempt.occurredAtEpochMillis,
            ),
        )
        return result
    }

    override suspend fun recordReviewAttempt(
        command: ReviewAttemptWriteCommand,
    ): ReviewAttemptWriteResult {
        val attempt = recordAttempt(command.attempt)
        return try {
            val advance = advanceReviewSession(
                command = ReviewSessionAdvanceCommand(
                    sessionId = command.sessionId,
                    expectedStateVersion = command.expectedStateVersion,
                    reviewQueueItemId = command.reviewQueueItemId,
                    practiceUnitId = command.practiceUnitId,
                    attemptId = attempt.attempt.attemptId,
                    submissionId = attempt.submissionId,
                    presentationId = attempt.attempt.presentationId,
                    occurredAtEpochMillis = attempt.attempt.occurredAtEpochMillis,
                ),
                attemptCreatedInCurrentTransaction = attempt.created,
            )
            ReviewAttemptWriteResult(attempt = attempt, advance = advance)
        } catch (failure: Throwable) {
            if (attempt.created) {
                attemptsBySubmission.remove(attempt.submissionId)
                advanceProofs.remove(attempt.attempt.attemptId)
                nextEventSequence--
            }
            throw failure
        }
    }

    override suspend fun recordAnswerReveal(command: AnswerRevealWriteCommand): AnswerRevealWriteResult =
        error("recordAnswerReveal is not used by these focused tests")

    override suspend fun reconcileAnswerRevealOutcomes(
        learnerId: String,
        limit: Int,
    ): List<AnswerRevealWriteResult> = emptyList()

    override suspend fun appendAttemptCorrection(
        correction: AttemptCorrectionRecord,
    ): AttemptCorrectionResult = error("appendAttemptCorrection is not used by these focused tests")

    override suspend fun findAttemptPersistence(submissionId: String): AttemptPersistenceRecord? = null

    override suspend fun recordTeachingAdvisories(entries: List<TeachingAdvisoryRecord>) {
        entries.forEach { entry ->
            // Mirror the Room UNIQUE(learner, source id, kind) dedup.
            teachingAdvisories.removeAll {
                it.learnerId == entry.learnerId &&
                    it.sourceId == entry.sourceId &&
                    it.advisoryKind == entry.advisoryKind
            }
            teachingAdvisories += entry
        }
    }

    override fun observeTeachingAdvisories(
        learnerId: String,
        practiceUnitId: String?,
    ): Flow<List<TeachingAdvisoryRecord>> = kotlinx.coroutines.flow.flowOf(
        teachingAdvisories.filter {
            it.learnerId == learnerId &&
                (practiceUnitId == null || it.practiceUnitId == practiceUnitId)
        },
    )

    override suspend fun recordReviewLogEntries(entries: List<ReviewLogEntry>) {
        reviewLogEntries += entries
    }

    override suspend fun readReviewLogSamples(learnerId: String, limit: Int): List<ReviewLogSampleRecord> =
        reviewLogEntries
            .filter { it.learnerId == learnerId }
            .sortedBy { it.reviewedAtEpochMillis }
            .take(limit)
            .map { entry ->
                ReviewLogSampleRecord(
                    practiceUnitId = entry.practiceUnitId,
                    reviewedAtEpochMillis = entry.reviewedAtEpochMillis,
                    rating = entry.rating,
                    durationMs = entry.durationMs,
                    timeBucket = entry.timeBucket,
                    sourceKind = entry.sourceKind,
                    evidenceWeight = entry.evidenceWeight,
                )
            }

    override suspend fun readLastReviewLogAt(
        learnerId: String,
        practiceUnitId: String,
        sourceKind: String,
    ): Long? = reviewLogEntries
        .filter {
            it.learnerId == learnerId &&
                it.practiceUnitId == practiceUnitId &&
                it.sourceKind == sourceKind &&
                it.schedulingEligible
        }
        .maxOfOrNull { it.reviewedAtEpochMillis }

    override suspend fun ensurePseudoKnowledgeBinding(
        practiceUnitId: String,
        problemRevisionId: String,
        taxonomyVersion: String,
        subject: String,
        acceptedAtEpochMillis: Long,
    ): PracticeUnitKnowledgeBindingRecord? {
        if (!pseudoKnowledgeBindingEnabled) return null
        val knowledgeNodeId = "pseudo:${subject.uppercase()}"
        val bindingId = "pseudo-binding:$practiceUnitId:$problemRevisionId:$taxonomyVersion:$knowledgeNodeId"
        pseudoBindingCalls += knowledgeNodeId
        return PracticeUnitKnowledgeBindingRecord(
            bindingId = bindingId,
            practiceUnitId = practiceUnitId,
            knowledgeNodeId = knowledgeNodeId,
            basisRevisionId = problemRevisionId,
            taxonomyVersion = taxonomyVersion,
            acceptedAtEpochMillis = acceptedAtEpochMillis,
        )
    }

    fun addAdvanceProof(proof: AttemptAdvanceProofRecord) {
        advanceProofs[proof.attemptId] = proof
    }

    override suspend fun findAttemptAdvanceProof(attemptId: String): AttemptAdvanceProofRecord? =
        advanceProofs[attemptId]

    override suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int = 0

    override suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        limit: Int,
    ): ProjectionBatch {
        if (failProjectionReads) error("forced projection read failure")
        val checkpoint = persistedLearnerSnapshot?.snapshot?.checkpoint?.lastSequence ?: 0L
        return ProjectionBatch(
            projectionName = projectionName,
            learnerId = learnerId,
            previousCheckpoint = checkpoint,
            ledgerHeadSequence = learningLedgerHead.value,
            events = emptyList(),
            authoritativePresentationStates = emptyMap(),
            stopReason = ProjectionBatchStopReason.END_OF_LEDGER,
        )
    }

    override suspend fun loadLearningLedger(learnerId: String): LearningLedgerRead =
        LearningLedgerRead(
            learnerId = learnerId,
            validPrefix = emptyList(),
            status = LearningLedgerReadStatus.COMPLETE,
        )

    override suspend fun readCurrentLearnerSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot? = persistedLearnerSnapshot

    override suspend fun commitProjection(commit: ProjectionCommit): PersistedLearnerSnapshot =
        error("commitProjection is not used by these focused tests")

    override suspend fun saveReviewPlan(bundle: ReviewPlanBundle) {
        val session = latestSessions[bundle.plan.reviewPlanId]
        val stored = bundle.copy(
            activeSession = session?.takeIf { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS },
            latestSession = session,
        )
        savedPlans.removeAll { it.plan.reviewPlanId == bundle.plan.reviewPlanId }
        savedPlans += stored
    }

    override suspend fun saveReviewSession(session: ReviewSessionRecord) {
        val existing = latestSessions[session.reviewPlanId]
        if (existing == session) return
        if (existing != null) {
            require(session.reviewSessionId == existing.reviewSessionId)
            require(session.stateVersion == existing.stateVersion + 1)
        } else {
            require(session.stateVersion == 0L)
        }
        latestSessions[session.reviewPlanId] = session
        sessionRevisions[session.reviewSessionId to session.stateVersion] = session
        val index = savedPlans.indexOfLast { it.plan.reviewPlanId == session.reviewPlanId }
        require(index >= 0)
        savedPlans[index] = savedPlans[index].copy(
            activeSession = session.takeIf { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS },
            latestSession = session,
        )
    }

    override suspend fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
    ): ReviewSessionAdvanceResult = advanceReviewSession(
        command = command,
        attemptCreatedInCurrentTransaction = false,
    )

    private fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
        attemptCreatedInCurrentTransaction: Boolean,
    ): ReviewSessionAdvanceResult {
        advanceReceipts[command.attemptId]?.let { receipt ->
            require(receipt.matches(command))
            val replayed = requireNotNull(sessionRevisions[receipt.sessionId to receipt.toVersion])
            return ReviewSessionAdvanceResult(
                created = false,
                session = replayed,
                receipt = receipt,
            )
        }
        require(attemptCreatedInCurrentTransaction)

        val current = latestSessions.values.single { it.reviewSessionId == command.sessionId }
        require(current.status == StudyDbValue.ReviewStatus.IN_PROGRESS)
        require(current.stateVersion == command.expectedStateVersion)
        val plan = savedPlans.single { it.plan.reviewPlanId == current.reviewPlanId }
        val queue = plan.queue.sortedBy { it.ordinal }
        val queueItem = queue.single { it.reviewQueueItemId == command.reviewQueueItemId }
        require(queueItem.ordinal == current.currentOrdinal)
        require(queueItem.practiceUnitId == command.practiceUnitId)
        val proof = requireNotNull(advanceProofs[command.attemptId])
        require(proof.submissionId == command.submissionId)
        require(proof.presentationId == command.presentationId)
        require(proof.practiceUnitId == command.practiceUnitId)
        require(proof.occurredAtEpochMillis == command.occurredAtEpochMillis)

        val nextOrdinal = current.currentOrdinal + 1
        val completed = nextOrdinal == queue.size
        val next = current.copy(
            status = if (completed) {
                StudyDbValue.ReviewStatus.COMPLETED
            } else {
                StudyDbValue.ReviewStatus.IN_PROGRESS
            },
            currentOrdinal = nextOrdinal,
            stateVersion = current.stateVersion + 1,
            lastActiveAtEpochMillis = command.occurredAtEpochMillis,
            completedAtEpochMillis = command.occurredAtEpochMillis.takeIf { completed },
        )
        val receipt = ReviewSessionAdvanceReceipt(
            sessionId = command.sessionId,
            fromVersion = command.expectedStateVersion,
            toVersion = next.stateVersion,
            reviewQueueItemId = command.reviewQueueItemId,
            practiceUnitId = command.practiceUnitId,
            attemptId = command.attemptId,
            submissionId = command.submissionId,
            presentationId = command.presentationId,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
        )
        latestSessions[current.reviewPlanId] = next
        sessionRevisions[next.reviewSessionId to next.stateVersion] = next
        advanceReceipts[command.attemptId] = receipt
        val planIndex = savedPlans.indexOf(plan)
        savedPlans[planIndex] = plan.copy(
            activeSession = next.takeIf { it.status == StudyDbValue.ReviewStatus.IN_PROGRESS },
            latestSession = next,
        )
        return ReviewSessionAdvanceResult(created = true, session = next, receipt = receipt)
    }

    override suspend fun readAssessmentSnapshotP0(
        assessmentItemSnapshotId: String,
    ): AssessmentItemSnapshotSeedRecord? = null

    override suspend fun readAttemptP0(attemptId: String): PersistedAttemptP0? = null

    override suspend fun readCorrectionP0(correctionId: String): PersistedCorrectionP0? = null

    override suspend fun readAnswerRevealP0(outcomeId: String): PersistedAnswerRevealP0? = null

    override fun close() = Unit

    private fun ReviewSessionAdvanceReceipt.matches(command: ReviewSessionAdvanceCommand): Boolean =
        sessionId == command.sessionId &&
            fromVersion == command.expectedStateVersion &&
            reviewQueueItemId == command.reviewQueueItemId &&
            practiceUnitId == command.practiceUnitId &&
            attemptId == command.attemptId &&
            submissionId == command.submissionId &&
            presentationId == command.presentationId &&
            occurredAtEpochMillis == command.occurredAtEpochMillis
}
