package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.Attempt
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewCase
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
interface StudyDatabasePort :
    AutoCloseable,
    LegacyModelTaskAndAssetDocumentDatabasePort,
    TutorLearningMemoryDatabasePort,
    LegacyAuthorityMigrationSourcePort,
    ExactLegacyCaptureStudentDocumentSourcePort,
    LegacyCaptureSessionDatabasePort,
    LegacyPreCutoverCaptureBusinessWritePort,
    LegacyPreCutoverBatchImportDatabasePort,
    LegacyPreCutoverTutorInteractionSessionDatabasePort,
    LegacyPreCutoverMistakeDetailReadPort,
    LegacyPreCutoverMistakeOrganizationBusinessPort,
    LegacyPreCutoverOrganizationReauthorizationPort,
    LegacyOrganizationWorkCoordinationPort {
    override fun observeMistakes(): Flow<List<MistakeRecord>>

    fun observeLearningLedgerHead(learnerId: String): Flow<Long> = flowOf(0L)

    override fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord> = throw UnsupportedOperationException(
        "Problem organization reads are not implemented",
    )

    fun observePendingProblemDraftCount(): Flow<Int>

    override fun observeTutorTurnResponses(sessionId: String): Flow<List<TutorTurnResponseRecord>> =
        flowOf(emptyList())

    override fun observeTutorVisualTargetEvidence(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidenceRecord>> = flowOf(emptyList())

    override fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>> =
        throw UnsupportedOperationException("Pending capture reads are not implemented")

    override fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>> = flowOf(emptyList())

    fun observeReviewPlan(reviewPlanId: String): Flow<ReviewPlanBundle?>

    fun observeReviewPlanForSession(sessionId: String): Flow<ReviewPlanBundle?>

    fun observeActiveReviewPlan(learnerId: String): Flow<ReviewPlanBundle?>

    fun observeCurrentReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanBundle?>

    fun observeCompletedReviewLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>> = flowOf(emptyList())

    suspend fun countMistakes(): Int

    suspend fun readSubjectKnowledgeNodes(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> = emptyList()

    /**
     * Reads a lightweight, bounded subject slice for local relevance ranking.
     *
     * This is deliberately separate from the small model-context read: callers rank the larger
     * slice locally and only disclose the final bounded result to an external model.
     */
    suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> = readSubjectKnowledgeNodes(subject, limit.coerceAtMost(256))

    suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord> = emptyList()

    suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> = emptyList()

    suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord> = emptyList()

    suspend fun readSubjectKnowledgeNodeRelations(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeRelationRecord> = emptyList()

    suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord> = emptyList()

    suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord> = emptyList()

    suspend fun readKnowledgeTeachingMaterialsByIds(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialRecord> = emptyList()

    suspend fun readKnowledgeTeachingMaterialNodeBindings(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingRecord> = emptyList()

    suspend fun importKnowledgeNodeRelations(
        relations: List<KnowledgeNodeRelationRecord>,
    ): Unit = throw UnsupportedOperationException("Knowledge-node relation imports are not implemented")

    suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord> = emptyList(),
    ): Unit = throw UnsupportedOperationException(
        "Knowledge teaching-material imports are not implemented",
    )

    suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ): Unit = throw UnsupportedOperationException("Knowledge-base imports are not implemented")

    suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = throw UnsupportedOperationException(
        "Reviewed knowledge-pack application is not implemented",
    )

    fun observePendingKnowledgeGroundingRequests(
        limit: Int = 256,
    ): Flow<List<KnowledgeGroundingRequestRecord>> = flowOf(emptyList())

    fun observePendingKnowledgeGroundingSummaries(
        limit: Int = 128,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> = flowOf(emptyList())

    fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        flowOf(emptyList())

    suspend fun enqueueKnowledgeResearchReviewBundle(
        bundle: KnowledgeResearchReviewBundleRecord,
    ): Unit = throw UnsupportedOperationException(
        "Knowledge research review persistence is not implemented",
    )

    suspend fun readPendingKnowledgeResearchReviewBundles(
        limit: Int = 64,
    ): List<KnowledgeResearchReviewBundleRecord> = emptyList()

    suspend fun readKnowledgeResearchReviewBundle(
        bundleId: String,
    ): KnowledgeResearchReviewBundleRecord? = null

    suspend fun decideKnowledgeResearchReviewBundle(
        command: DecideKnowledgeResearchReviewBundleCommand,
    ): KnowledgeResearchReviewBundleRecord = throw UnsupportedOperationException(
        "Knowledge research review decisions are not implemented",
    )

    suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = throw UnsupportedOperationException(
        "Approved knowledge research pack application is not implemented",
    )

    override suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    ): Unit = throw UnsupportedOperationException("Knowledge-grounding requests are not implemented")

    suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord =
        throw UnsupportedOperationException("Knowledge-grounding resolution is not implemented")

    suspend fun readKnowledgeGroundingResolution(
        groundingKey: String,
    ): KnowledgeGroundingResolutionRecord? = null

    suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRecord?

    override suspend fun readMistakeDetail(errorBookEntryId: String): MistakeDetailRecord? =
        throw UnsupportedOperationException("Mistake-detail reads are not implemented")

    override suspend fun readExactMistakeDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord? =
        throw UnsupportedOperationException("Exact mistake-detail reads are not implemented")

    override suspend fun readCurrentMistakeDetails(
        entryIds: List<String>,
    ): List<MistakeDetailRecord> = entryIds.mapNotNull { entryId ->
        readMistakeDetail(entryId)
    }

    override suspend fun readMistakeRevisionHistory(
        errorBookEntryId: String,
    ): List<MistakeRevisionSummaryRecord> = emptyList()

    override suspend fun createProblemDraft(
        command: CreateProblemDraftCommand,
    ): ProblemDraftWriteResult

    override suspend fun appendProblemDraftSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult = throw UnsupportedOperationException(
        "Problem-draft source append is not implemented",
    )

    override suspend fun mergeProblemDraftSourceBundle(
        command: MergeProblemDraftSourceBundleCommand,
    ): CaptureDraftMergeSessionReceiptRecord = throw UnsupportedOperationException(
        "Problem-draft source-bundle merge is not implemented",
    )

    override suspend fun readProblemDraftMergeSessionReceipt(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftMergeSessionReceiptRecord? = null

    override suspend fun reviseProblemDraft(
        command: ReviseProblemDraftCommand,
    ): ProblemDraftWriteResult

    override suspend fun replaceProblemDraft(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult = throw UnsupportedOperationException(
        "Atomic capture replacement is not implemented",
    )

    override suspend fun splitProblemDraft(
        command: SplitProblemDraftCommand,
    ): ProblemDraftSplitResult = throw UnsupportedOperationException(
        "Atomic capture splitting is not implemented",
    )

    override suspend fun readProblemDraft(draftId: String): ProblemDraftRecord?

    override suspend fun readCanonicalSourceAsset(
        sourceAssetId: String,
    ): CanonicalSourceAssetRecord? = null

    override suspend fun readPendingCaptureDraft(
        draftId: String,
    ): PendingCaptureDraftRecord? =
        throw UnsupportedOperationException("Pending capture reads are not implemented")

    override suspend fun createBatchImportJob(
        command: CreateBatchImportJobCommand,
    ): BatchImportJobRecord = throw UnsupportedOperationException(
        "Batch import writes are not implemented",
    )

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

    override suspend fun recordBatchImportBoundarySessionResolution(
        command: RecordBatchImportBoundarySessionResolutionCommand,
    ): BatchImportJobRecord {
        val room =
            this as? RoomStudyDatabase
                ?: throw UnsupportedOperationException(
                    "Session-only batch boundary resolution requires the audited Room bridge",
                )
        return RoomBatchImportStore(room.database).recordBoundaryResolution(command)
    }

    override suspend fun resolveBatchImportBoundary(
        command: ResolveBatchImportBoundaryCommand,
    ): BatchImportJobRecord = throw UnsupportedOperationException(
        "Batch import page-boundary writes are not implemented",
    )

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

    override suspend fun hasRetainedBatchImportSourceUri(sourceUri: String): Boolean = true

    override suspend fun readProblemDraftEditWorkspace(
        draftId: String,
    ): ProblemDraftEditWorkspaceRecord? = throw UnsupportedOperationException(
        "Problem-draft edit-workspace reads are not implemented",
    )

    override suspend fun saveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult = throw UnsupportedOperationException(
        "Problem-draft edit-workspace writes are not implemented",
    )

    override suspend fun consumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ): Boolean = throw UnsupportedOperationException(
        "Problem-draft edit-workspace consumption is not implemented",
    )

    suspend fun commitProblemDraft(command: CommitProblemDraftCommand): CommitProblemDraftResult

    override suspend fun readProblemOrganizationWork(
        workId: String,
    ): ProblemOrganizationWorkRecord? = null

    override suspend fun readProblemOrganizationWorkByCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemOrganizationWorkRecord? = null

    override suspend fun readProblemOrganizationWorkByRequestId(
        requestId: String,
    ): ProblemOrganizationWorkRecord? = null

    override suspend fun readProblemOrganizationWorkCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemDraftCommitReceipt? = null

    suspend fun readWaitingProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? = null

    override suspend fun readLatestProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? = null

    suspend fun claimNextProblemOrganizationWork(
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? = null

    override suspend fun claimProblemOrganizationWork(
        workId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? = null

    suspend fun readSchedulableProblemOrganizationWorks(
        nowEpochMillis: Long,
        limit: Int,
    ): List<ProblemOrganizationWorkRecord> = emptyList()

    override suspend fun readRunningProblemOrganizationWorks(
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long?,
        afterUpdatedAtEpochMillis: Long?,
        afterWorkId: String?,
    ): List<ProblemOrganizationWorkRecord> = emptyList()

    override fun observeSchedulableProblemOrganizationWorks():
        Flow<List<ProblemOrganizationWorkRecord>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun authorizeProblemOrganizationWork(
        command: AuthorizeProblemOrganizationWorkCommand,
    ): Boolean = false

    override suspend fun reauthorizeProblemOrganizationWork(
        command: ReauthorizeProblemOrganizationWorkCommand,
    ): ReauthorizeProblemOrganizationWorkResult =
        ReauthorizeProblemOrganizationWorkResult(
            outcome = ReauthorizeProblemOrganizationWorkOutcome.NOT_APPLIED,
            work = null,
        )

    override suspend fun markProblemOrganizationWorkWaitingAuthorization(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    override suspend fun retryProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    override suspend fun failProblemOrganizationWorkPermanently(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    override suspend fun completeProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean = false

    override suspend fun confirmAndCompleteProblemOrganizationWork(
        command: CompleteProblemOrganizationWorkAtomicallyCommand,
    ): ConfirmAndCompleteProblemOrganizationWorkResult =
        ConfirmAndCompleteProblemOrganizationWorkResult(
            completed = false,
            organizationResult = null,
        )

    override suspend fun confirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ): CommitProblemDraftResult = throw UnsupportedOperationException(
        "Atomic workspace-backed problem confirmation is not implemented",
    )

    suspend fun confirmTutorSession(
        command: ConfirmTutorSessionCommand,
    ): TutorSessionWriteResult = throw UnsupportedOperationException(
        "Tutor-session confirmation is not implemented",
    )

    override suspend fun confirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ): TutorSessionWriteResult = throw UnsupportedOperationException(
        "Atomic workspace-backed tutor confirmation is not implemented",
    )

    override suspend fun readTutorSession(sessionId: String): TutorSessionRecord? =
        throw UnsupportedOperationException("Tutor-session reads are not implemented")

    override suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult = throw UnsupportedOperationException(
        "Tutor-session commit is not implemented",
    )

    override suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult = throw UnsupportedOperationException(
        "Tutor-session ending is not implemented",
    )

    override suspend fun recordTutorChoice(
        command: PersistTutorChoiceCommand,
    ): TutorTurnResponseRecord =
        throw UnsupportedOperationException("Tutor response writes are not implemented")

    override suspend fun recordTutorChoiceUnlessCancelled(
        command: PersistTutorChoiceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorTurnResponseRecord? = recordTutorChoice(command)

    suspend fun discardTutorChoice(command: PersistTutorChoiceCommand): Boolean = false

    suspend fun recordTutorVisualTargetEvidence(
        command: PersistTutorVisualTargetEvidenceCommand,
    ): TutorVisualTargetEvidenceRecord = throw UnsupportedOperationException(
        "Tutor visual-target evidence writes are not implemented",
    )

    override suspend fun recordTutorVisualTargetEvidenceUnlessCancelled(
        command: PersistTutorVisualTargetEvidenceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorVisualTargetEvidenceRecord? = recordTutorVisualTargetEvidence(command)

    override suspend fun recordTutorEvidenceCancellation(
        command: PersistTutorEvidenceCancellationCommand,
    ) = Unit

    override suspend fun isTutorEvidenceCancelled(
        command: PersistTutorEvidenceCancellationCommand,
    ): Boolean = false

    override suspend fun recordTutorMove(
        command: PersistTutorMoveCommand,
    ): TutorTurnResponseRecord =
        throw UnsupportedOperationException("Tutor move writes are not implemented")

    override suspend fun revealTutorSolution(
        command: PersistTutorRevealCommand,
    ): TutorTurnResponseRecord =
        throw UnsupportedOperationException("Tutor reveal writes are not implemented")

    override suspend fun recordTutorSolutionExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord = throw UnsupportedOperationException(
        "Tutor solution-exposure writes are not implemented",
    )

    override suspend fun bindTutorSessionProblemAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord = TutorSessionProblemAnchorRecord(
        learnerId = command.learnerId,
        sessionId = command.sessionId,
        problemRevisionId = command.problemRevisionId,
        practiceUnitId = command.practiceUnitId,
        source = command.source,
        anchoredAtEpochMillis = command.anchoredAtEpochMillis,
    )

    suspend fun reconcileTutorAnswerExposures(learnerId: String, limit: Int = 100): Int = 0

    override suspend fun readTutorAnswerExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureRecord? = null

    override suspend fun readTutorAnswerExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureRecord> = modelTaskRequestIds.mapNotNull { requestId ->
        readTutorAnswerExposure(requestId)
    }

    suspend fun seedFixture(bundle: StudySeedBundle): SeedResult

    suspend fun saveAssessmentItemSnapshot(item: AssessmentItemSnapshotSeedRecord)

    suspend fun saveAssessmentEvidenceSnapshot(snapshot: AssessmentEvidenceSnapshot)

    suspend fun appendAssessmentEvent(event: AssessmentEventSeedRecord)

    suspend fun recordAttempt(command: AttemptWriteCommand): AttemptWriteResult

    suspend fun submitLearningObservationCandidate(
        candidate: LearningObservationCandidate,
    ): LearningObservationCandidateWriteResult

    /**
     * Registers an immutable local source fact. Tutor/capture ingestion should call this inside
     * the same transaction that persists the referenced source response.
     */
    suspend fun registerLearningObservationSourceAuthority(
        authority: LearningObservationSourceAuthorityRecord,
    ): LearningObservationSourceAuthorityWriteResult = throw UnsupportedOperationException(
        "Learning-observation source authority is not implemented",
    )

    suspend fun readLearningObservationSourceAuthority(
        learnerId: String,
        source: LearningObservationSource,
        sourceReferenceId: String,
    ): LearningObservationSourceAuthorityRecord? = null

    suspend fun compareAndSetLearningObservationCandidateStatus(
        command: LearningObservationCandidateStatusChangeCommand,
    ): LearningObservationCandidateStatusCasResult

    suspend fun materializeLearningObservation(
        command: MaterializeLearningObservationCommand,
    ): LearningObservationMaterializationResult

    suspend fun readLearningObservationCandidate(
        candidateId: String,
    ): LearningObservationCandidate?

    suspend fun readAttributedLearningObservation(
        eventId: String,
    ): AttributedLearningObservationEvent?

    suspend fun readLearningEvidenceReviewCase(
        reviewCaseId: String,
    ): LearningEvidenceReviewCase? = null

    suspend fun recordReviewAttempt(
        command: ReviewAttemptWriteCommand,
    ): ReviewAttemptWriteResult = throw UnsupportedOperationException(
        "Atomic review-attempt writes are not implemented",
    )

    suspend fun recordAnswerReveal(command: AnswerRevealWriteCommand): AnswerRevealWriteResult

    suspend fun reconcileAnswerRevealOutcomes(
        learnerId: String,
        limit: Int = 100,
    ): List<AnswerRevealWriteResult>

    suspend fun appendAttemptCorrection(correction: AttemptCorrectionRecord): AttemptCorrectionResult

    suspend fun findAttemptPersistence(submissionId: String): AttemptPersistenceRecord?

    suspend fun findAttemptAdvanceProof(attemptId: String): AttemptAdvanceProofRecord? = null

    suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int

    override suspend fun confirmProblemOrganization(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult = throw UnsupportedOperationException(
        "Atomic problem organization confirmation is not implemented",
    )

    suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        limit: Int,
    ): ProjectionBatch

    suspend fun loadLearningLedger(learnerId: String): LearningLedgerRead

    suspend fun readCurrentLearnerSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot?

    suspend fun commitProjection(commit: ProjectionCommit): PersistedLearnerSnapshot

    suspend fun saveReviewPlan(bundle: ReviewPlanBundle)

    suspend fun saveReviewSession(session: ReviewSessionRecord)

    /**
     * Replays an already committed review transition. It must never create a new transition.
     * New answers must enter through [recordReviewAttempt], which creates the attempt and advances
     * its queue item in one write transaction.
     */
    @Deprecated("New review transitions must use recordReviewAttempt")
    suspend fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
    ): ReviewSessionAdvanceResult = throw UnsupportedOperationException(
        "Review-session replay is not implemented",
    )

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readAssessmentSnapshotP0(
        assessmentItemSnapshotId: String,
    ): AssessmentItemSnapshotSeedRecord?

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readAttemptP0(attemptId: String): PersistedAttemptP0?

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readCorrectionP0(correctionId: String): PersistedCorrectionP0?

    @Deprecated("P0 inspection only; keep data-layer wiring behind the repository boundary")
    suspend fun readAnswerRevealP0(outcomeId: String): PersistedAnswerRevealP0?

    suspend fun appendLegacyAuthorityCutoverStageReceipt(
        command: AppendLegacyAuthorityCutoverStageCommand,
    ): LegacyAuthorityCutoverJournalWriteResult = throw UnsupportedOperationException(
        "Legacy authority cutover journaling is not implemented",
    )

    suspend fun readLegacyAuthorityCutoverStageReceipts():
        List<LegacyAuthorityCutoverStageReceipt> = emptyList()

    suspend fun prepareCaptureStudentSaveHandoff(
        command: PrepareCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult = throw UnsupportedOperationException(
        "Capture-to-student save handoff preparation is not implemented",
    )

    suspend fun finalizeCaptureStudentSaveHandoff(
        command: FinalizeCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult = throw UnsupportedOperationException(
        "Capture-to-student save handoff finalization is not implemented",
    )

    suspend fun readPendingCaptureStudentSaveHandoffs(
        query: ReadPendingCaptureStudentSaveHandoffsQuery,
    ): List<CaptureStudentSaveHandoffRecord> = emptyList()

    override suspend fun acknowledgeStudentOwnedCaptureSession(
        command: AcknowledgeStudentOwnedCaptureSessionCommand,
    ): StudentOwnedCaptureSessionAckResult = throw UnsupportedOperationException(
        "Student-owned capture session acknowledgement is not implemented",
    )
}
