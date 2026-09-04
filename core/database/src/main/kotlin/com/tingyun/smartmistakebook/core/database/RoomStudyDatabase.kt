package com.tingyun.smartmistakebook.core.database

import androidx.paging.PagingSource
import androidx.room3.RoomRawQuery
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.CanonicalSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
import com.tingyun.smartmistakebook.core.database.dao.LibraryFacetCountRow
import com.tingyun.smartmistakebook.core.database.dao.LibraryFtsSearchDao
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureHeadRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureIndexRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureSourceAssetRow
import com.tingyun.smartmistakebook.core.database.dao.PendingCaptureWorkspaceColumns
import com.tingyun.smartmistakebook.core.database.dao.ReviewPlanAggregate
import com.tingyun.smartmistakebook.core.database.dao.ReviewedKnowledgeCoverageRow
import com.tingyun.smartmistakebook.core.database.dao.activeSessionHead
import com.tingyun.smartmistakebook.core.database.dao.latestSessionHead
import com.tingyun.smartmistakebook.core.database.dao.toRecord
import com.tingyun.smartmistakebook.core.database.dao.toSnapshot
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.PredictionOutcomeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.StudentModelPredictionEntity
import com.tingyun.smartmistakebook.core.database.entity.VisualInteractionAttemptEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.LibraryCatalogView
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewLogEntity
import com.tingyun.smartmistakebook.core.database.entity.LlmTeachingAdvisoryEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.database.LearningLedgerRead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import java.io.File
import com.tingyun.smartmistakebook.core.database.port.StudentModelPredictionRecord
import com.tingyun.smartmistakebook.core.database.port.ResolvedStudentModelPredictionRecord
import com.tingyun.smartmistakebook.core.database.port.VisualInteractionAttemptRecord
import com.tingyun.smartmistakebook.core.database.port.PracticeUnitKnowledgeBindingRecord
import com.tingyun.smartmistakebook.core.database.port.KnowledgeQuestionLatticeRecord
import com.tingyun.smartmistakebook.core.model.TeachingAdvisoryRecord

internal class RoomStudyDatabase(
    internal val database: StudyDatabase,
) : StudyDatabasePort {
    private val knowledgeResearchReviewStore = RoomKnowledgeResearchReviewStore(database)

    private val problemOrganization = RoomProblemOrganizationStore(database)
    private val batchImports = RoomBatchImportStore(database)
    private val splitImports = RoomSplitImportStore(database)
    private val librarySearch = RoomLibrarySearchStore(database)
    private val knowledgeBase = RoomKnowledgeBaseStore(database, knowledgeResearchReviewStore)
    private val backupSupport = RoomBackupSupportStore(database)
    private val studentModel = RoomStudentModelStore(database)
    private val pendingCaptures = RoomPendingCaptureStore(database)
    override fun observeMistakes(): Flow<List<MistakeRecord>> =
        database.problemDao().observeActiveMistakes().map { rows -> rows.map(MistakeRow::toRecord) }

    override fun libraryPagingSource(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
    ): PagingSource<Int, LibraryCatalogRow> =
        librarySearch.pagingSource(
            searchText = searchText,
            subjectId = subjectId,
            sectionId = sectionId,
            knowledgePointId = knowledgePointId,
            masteryId = masteryId,
            sort = sort,
        )

    override fun librarySearchPagingSource(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
    ): PagingSource<Int, LibraryCatalogRow> =
        librarySearch.searchPagingSource(
            matchQuery = matchQuery,
            subjectId = subjectId,
            sectionId = sectionId,
            knowledgePointId = knowledgePointId,
            masteryId = masteryId,
            sort = sort,
            tokens = tokens,
        )

    override suspend fun librarySearchCount(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int = librarySearch.searchCount(
        matchQuery = matchQuery,
        subjectId = subjectId,
        sectionId = sectionId,
        knowledgePointId = knowledgePointId,
        masteryId = masteryId,
    )

    override suspend fun librarySearchPage(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        tokens: List<String>,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> = librarySearch.searchPage(
        matchQuery = matchQuery,
        subjectId = subjectId,
        sectionId = sectionId,
        knowledgePointId = knowledgePointId,
        masteryId = masteryId,
        sort = sort,
        tokens = tokens,
        offset = offset,
        limit = limit,
    )

    override suspend fun librarySearchFacets(
        matchQuery: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> = librarySearch.searchFacets(
        matchQuery = matchQuery,
        subjectId = subjectId,
        sectionId = sectionId,
        knowledgePointId = knowledgePointId,
        masteryId = masteryId,
        facet = facet,
    )

    override suspend fun refreshLibrarySearchProjection() = librarySearch.refreshProjection()

    override suspend fun libraryCatalogPage(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        sort: String,
        offset: Int,
        limit: Int,
    ): List<LibraryCatalogRow> = librarySearch.catalogPage(
        searchText = searchText,
        subjectId = subjectId,
        sectionId = sectionId,
        knowledgePointId = knowledgePointId,
        masteryId = masteryId,
        sort = sort,
        offset = offset,
        limit = limit,
    )

    override suspend fun libraryCatalogCount(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
    ): Int = librarySearch.catalogCount(
        searchText = searchText,
        subjectId = subjectId,
        sectionId = sectionId,
        knowledgePointId = knowledgePointId,
        masteryId = masteryId,
    )

    override suspend fun libraryCatalogFacets(
        searchText: String,
        subjectId: String?,
        sectionId: String?,
        knowledgePointId: String?,
        masteryId: String?,
        facet: String,
    ): List<LibraryFacetCountRecord> = librarySearch.catalogFacets(
        searchText = searchText,
        subjectId = subjectId,
        sectionId = sectionId,
        knowledgePointId = knowledgePointId,
        masteryId = masteryId,
        facet = facet,
    )

    override fun observeLearningLedgerHead(learnerId: String): Flow<Long> {
        require(learnerId.isNotBlank())
        return database.learningDao().observeLedgerHead(learnerId)
    }

    override fun observePendingProblemDraftCount(): Flow<Int> =
        database.problemDraftTransactionDao().observePendingDraftCount()

    override fun observeTutorTurnResponses(sessionId: String): Flow<List<TutorTurnResponseRecord>> {
        require(sessionId.isNotBlank())
        return database.tutorInteractionDao().observe(sessionId)
    }

    override fun observeRecentTutorConversations(
        limit: Int,
    ): Flow<List<TutorConversationRecord>> {
        require(limit > 0) { "Tutor conversation limit must be positive" }
        return database.tutorConversationDao().observeRecent(limit)
    }

    override fun observeTutorMessages(
        conversationId: String,
    ): Flow<List<TutorMessageRecord>> {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return database.tutorConversationDao().observeMessages(conversationId)
    }

    override fun observeTutorConversation(
        conversationId: String,
    ): Flow<TutorConversationRecord?> {
        require(conversationId.isNotBlank()) { "Tutor conversation id must not be blank" }
        return database.tutorConversationDao().observeConversation(conversationId)
    }

    override fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>> =
        pendingCaptures.observeDrafts()

    override fun observeActiveSplitImports(): Flow<List<SplitImportJobRecord>> =
        splitImports.observe()

    override suspend fun readSplitImportJob(jobId: String): SplitImportJobRecord? {
        require(jobId.isNotBlank())
        return splitImports.read(jobId)
    }

    override suspend fun createSplitImportJob(
        command: CreateSplitImportJobCommand,
        questions: List<SplitImportQuestionSeed>,
    ): SplitImportJobRecord = splitImports.create(command, questions)

    override suspend fun markSplitImportReady(
        jobId: String,
        questionCount: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = splitImports.markReady(jobId, questionCount, occurredAtEpochMillis)

    override suspend fun updateSplitImportSelection(
        jobId: String,
        questionOrdinal: Int,
        selected: Boolean,
        occurredAtEpochMillis: Long,
    ): Boolean = splitImports.updateSelected(jobId, questionOrdinal, selected, occurredAtEpochMillis)

    override suspend fun markSplitImportQuestionConfirmed(
        jobId: String,
        questionOrdinal: Int,
        confirmState: String,
        splitDraftId: String?,
        occurredAtEpochMillis: Long,
    ): Boolean = splitImports.markQuestionConfirmed(
        jobId,
        questionOrdinal,
        confirmState,
        splitDraftId,
        occurredAtEpochMillis,
    )

    override suspend fun completeSplitImportJob(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = splitImports.complete(jobId, occurredAtEpochMillis)

    override suspend fun abandonSplitImportJob(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = splitImports.abandon(jobId, occurredAtEpochMillis)

    override fun observeBatchImportJobs(): Flow<List<BatchImportJobRecord>> = batchImports.observe()

    override fun observeReviewPlan(reviewPlanId: String): Flow<ReviewPlanBundle?> =
        database.reviewDao().observePlan(reviewPlanId).map { it?.toRecord() }

    override fun observeReviewPlanForSession(sessionId: String): Flow<ReviewPlanBundle?> =
        database.reviewDao().observePlanForSession(sessionId).map { it?.toRecord() }

    override fun observeActiveReviewPlan(learnerId: String): Flow<ReviewPlanBundle?> =
        database.reviewDao().observeActivePlans(learnerId).map { activePlans ->
            when (activePlans.size) {
                0 -> null
                1 -> activePlans.single().toRecord()
                else -> throw ImmutablePayloadConflictException(
                    "active_review_session",
                    learnerId,
                )
            }
        }

    override fun observeCurrentReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
        timeZoneId: String,
    ): Flow<ReviewPlanBundle?> = database.reviewDao()
        .observeCurrentPlan(learnerId, localDayEpochDay, timeZoneId)
        .map { it?.toRecord() }

    override fun observeCompletedReviewLocalDays(
        learnerId: String,
        limit: Int,
    ): Flow<List<Long>> {
        require(learnerId.isNotBlank())
        require(limit in 1..MAX_REVIEW_COMPLETION_HISTORY_DAYS)
        return database.reviewDao().observeCompletedLocalDays(learnerId, limit)
    }

    override suspend fun countMistakes(): Int = database.problemDao().countActiveMistakes()

    override suspend fun checkpointForBackup() = backupSupport.checkpointForBackup()

    override suspend fun snapshotForBackup(
        sourceDatabaseFile: File,
        snapshotTarget: File,
    ) = backupSupport.snapshotForBackup(sourceDatabaseFile, snapshotTarget)

    override suspend fun clearAllData() = backupSupport.clearAllData()

    override suspend fun recordStudentModelPredictions(
        predictions: List<StudentModelPredictionRecord>,
    ) = studentModel.recordPredictions(predictions)

    override suspend fun resolveStudentModelPredictions(
        practiceUnitId: String,
        wasIndependentCorrect: Boolean,
        observedAtEpochMillis: Long,
        responseLatencyMs: Long?,
        hintCount: Int,
    ): Int = studentModel.resolvePredictions(
        practiceUnitId = practiceUnitId,
        wasIndependentCorrect = wasIndependentCorrect,
        observedAtEpochMillis = observedAtEpochMillis,
        responseLatencyMs = responseLatencyMs,
        hintCount = hintCount,
    )

    override suspend fun readResolvedStudentModelPredictions(
        modelId: String,
        modelVersion: String,
    ): List<ResolvedStudentModelPredictionRecord> =
        studentModel.readResolvedPredictions(modelId, modelVersion)

    override suspend fun findLastPredictionLatencyMs(practiceUnitId: String): Long? =
        studentModel.findLastLatencyMs(practiceUnitId)

    override suspend fun recordVisualInteractionAttempt(
        attempt: VisualInteractionAttemptRecord,
    ) = studentModel.recordVisualInteractionAttempt(attempt)

    override suspend fun readVisualInteractionAttempts(
        problemRevisionId: String,
    ): List<VisualInteractionAttemptRecord> =
        studentModel.readVisualInteractionAttempts(problemRevisionId)

    override suspend fun readPracticeUnitKnowledgeBindings(
        practiceUnitId: String,
    ): List<PracticeUnitKnowledgeBindingRecord> =
        database.problemOrganizationDao()
            .readKnowledgeBindingsForPracticeUnit(practiceUnitId)
            .map { row ->
                PracticeUnitKnowledgeBindingRecord(
                    bindingId = row.bindingId,
                    practiceUnitId = row.practiceUnitId,
                    knowledgeNodeId = row.knowledgeNodeId,
                    basisRevisionId = row.basisRevisionId,
                    taxonomyVersion = row.taxonomyVersion,
                    acceptedAtEpochMillis = row.acceptedAtEpochMillis,
                )
            }

    override suspend fun recordTeachingAdvisories(entries: List<TeachingAdvisoryRecord>) {
        database.learningDao().recordTeachingAdvisories(
            entries.map { entry ->
                LlmTeachingAdvisoryEntity(
                    advisoryId = entry.advisoryId,
                    learnerId = entry.learnerId,
                    practiceUnitId = entry.practiceUnitId,
                    knowledgeNodeId = entry.knowledgeNodeId,
                    advisoryKind = entry.advisoryKind,
                    payloadMarkdown = entry.payloadMarkdown,
                    confidence = entry.confidence,
                    sourceId = entry.sourceId,
                    createdAtEpochMillis = entry.createdAtEpochMillis,
                )
            },
        )
    }

    override fun observeTeachingAdvisories(
        learnerId: String,
        practiceUnitId: String?,
    ): Flow<List<TeachingAdvisoryRecord>> =
        database.learningDao().observeTeachingAdvisories(learnerId, practiceUnitId).map { rows ->
            rows.map { row ->
                TeachingAdvisoryRecord(
                    advisoryId = row.advisoryId,
                    learnerId = row.learnerId,
                    practiceUnitId = row.practiceUnitId,
                    knowledgeNodeId = row.knowledgeNodeId,
                    advisoryKind = row.advisoryKind,
                    payloadMarkdown = row.payloadMarkdown,
                    confidence = row.confidence,
                    sourceId = row.sourceId,
                    createdAtEpochMillis = row.createdAtEpochMillis,
                )
            }
        }

    override fun observeKnowledgeQuestionLattice(
        learnerId: String,
    ): Flow<List<KnowledgeQuestionLatticeRecord>> =
        database.problemDao().observeKnowledgeQuestionLattice(learnerId).map { rows ->
            rows.map { row ->
                KnowledgeQuestionLatticeRecord(
                    practiceUnitId = row.practiceUnitId,
                    knowledgeNodeId = row.knowledgeNodeId,
                    bindingStrength = row.bindingStrength,
                    basisRevisionId = row.basisRevisionId,
                    bindingTaxonomyVersion = row.bindingTaxonomyVersion,
                    entryId = row.entryId,
                    entryStatus = row.entryStatus,
                    kcLearnerId = row.kcLearnerId,
                    memoryLearnerId = row.memoryLearnerId,
                    kcConservativeMastery = row.kcConservativeMastery,
                    kcStatus = row.kcStatus,
                    kcLastEvidenceDirection = row.kcLastEvidenceDirection,
                    kcLastEvidenceAt = row.kcLastEvidenceAt,
                    questionStabilityDays = row.questionStabilityDays,
                    questionDifficulty = row.questionDifficulty,
                    questionNextReviewAt = row.questionNextReviewAt,
                    questionLapseCount = row.questionLapseCount,
                    questionCrossDayAgain = row.questionCrossDayAgain,
                )
            }
        }

    override suspend fun readDatabaseVersion(): Int {
        var version = 0
        database.useConnection(isReadOnly = true) { connection ->
            connection.usePrepared("PRAGMA user_version") { statement ->
                if (statement.step()) {
                    version = statement.getLong(0).toInt()
                }
            }
        }
        return version
    }

    override suspend fun ensurePseudoKnowledgeBinding(
        practiceUnitId: String,
        problemRevisionId: String,
        taxonomyVersion: String,
        subject: String,
        acceptedAtEpochMillis: Long,
    ): PracticeUnitKnowledgeBindingRecord? = knowledgeBase.ensurePseudoKnowledgeBinding(
        practiceUnitId = practiceUnitId,
        problemRevisionId = problemRevisionId,
        taxonomyVersion = taxonomyVersion,
        subject = subject,
        acceptedAtEpochMillis = acceptedAtEpochMillis,
    )

    override suspend fun readSubjectKnowledgeNodes(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> = knowledgeBase.readSubjectKnowledgeNodes(subject, limit)

    override suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> = knowledgeBase.readSubjectKnowledgeRecallCandidates(
        subject = subject,
        searchFeatures = searchFeatures,
        limit = limit,
    )

    override suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord> =
        knowledgeBase.readKnowledgeNodesByIds(ids)

    override suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> =
        knowledgeBase.readKnowledgeSourcesByIds(ids)

    override suspend fun readSubjectKnowledgeNodeRelations(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeRelationRecord> = knowledgeBase.readSubjectKnowledgeNodeRelations(
        subject = subject,
        limit = limit,
    )

    override suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord> = knowledgeBase.readKnowledgeNodeRelationsForDependents(
        subject = subject,
        dependentKnowledgeNodeIds = dependentKnowledgeNodeIds,
    )

    override suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord> = knowledgeBase.readKnowledgeTeachingMaterialsForNodes(
        subject = subject,
        knowledgeNodeIds = knowledgeNodeIds,
        limit = limit,
    )

    override suspend fun readKnowledgeTeachingMaterialsByIds(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialRecord> =
        knowledgeBase.readKnowledgeTeachingMaterialsByIds(materialIds)

    override suspend fun readKnowledgeTeachingMaterialNodeBindings(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingRecord> =
        knowledgeBase.readKnowledgeTeachingMaterialNodeBindings(materialIds)

    override suspend fun importKnowledgeNodeRelations(relations: List<KnowledgeNodeRelationRecord>) =
        knowledgeBase.importKnowledgeNodeRelations(relations)

    override suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    ) = knowledgeBase.importKnowledgeTeachingMaterials(
        materials = materials,
        bindings = bindings,
        sources = sources,
    )

    override suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord> =
        knowledgeBase.readKnowledgeNodeSourceBindings(knowledgeNodeIds)

    override suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ) = knowledgeBase.importKnowledgeBase(
        sources = sources,
        nodes = nodes,
        bindings = bindings,
    )

    override suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> =
        knowledgeBase.applyReviewedKnowledgePack(command)

    override suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> =
        knowledgeBase.applyApprovedKnowledgeResearchPack(command)

    override fun observePendingKnowledgeGroundingRequests(
        limit: Int,
    ): Flow<List<KnowledgeGroundingRequestRecord>> =
        knowledgeBase.observePendingKnowledgeGroundingRequests(limit)

    override fun observePendingKnowledgeGroundingSummaries(
        limit: Int,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> =
        knowledgeBase.observePendingKnowledgeGroundingSummaries(limit)

    override fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        knowledgeBase.observeReviewedKnowledgeCoverage()

    override suspend fun enqueueKnowledgeResearchReviewBundle(
        bundle: KnowledgeResearchReviewBundleRecord,
    ) = knowledgeResearchReviewStore.enqueue(bundle)

    override suspend fun readPendingKnowledgeResearchReviewBundles(
        limit: Int,
    ): List<KnowledgeResearchReviewBundleRecord> =
        knowledgeResearchReviewStore.readPending(limit)

    override suspend fun readKnowledgeResearchReviewBundle(
        bundleId: String,
    ): KnowledgeResearchReviewBundleRecord? =
        knowledgeResearchReviewStore.read(bundleId)

    override suspend fun decideKnowledgeResearchReviewBundle(
        command: DecideKnowledgeResearchReviewBundleCommand,
    ): KnowledgeResearchReviewBundleRecord =
        knowledgeResearchReviewStore.decide(command)

    override suspend fun recordKnowledgeGroundingRequests(
        requests: List<KnowledgeGroundingRequestRecord>,
    ) = knowledgeBase.recordKnowledgeGroundingRequests(requests)

    override suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord = knowledgeBase.resolveKnowledgeGrounding(command)

    override suspend fun readKnowledgeGroundingResolution(
        groundingKey: String,
    ): KnowledgeGroundingResolutionRecord? =
        knowledgeBase.readKnowledgeGroundingResolution(groundingKey)

    override suspend fun findMistakeBySourceKey(sourceKey: String): MistakeRecord? {
        require(sourceKey.isNotBlank()) { "sourceKey must not be blank" }
        return database.problemDao().findMistakeBySourceKey(sourceKey)?.toRecord()
    }

    override suspend fun readMistakeDetail(errorBookEntryId: String): MistakeDetailRecord? {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return database.mistakeDetailDao().read(errorBookEntryId)
    }

    override suspend fun readExactMistakeDetail(
        entryId: String,
        problemId: String,
        problemRevisionId: String,
    ): MistakeDetailRecord? {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
        require(problemId.isNotBlank()) { "problemId must not be blank" }
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        return database.mistakeDetailDao().readExact(entryId, problemId, problemRevisionId)
    }

    override suspend fun readCurrentMistakeDetails(
        entryIds: List<String>,
    ): List<MistakeDetailRecord> {
        require(entryIds.size <= 100) { "Mistake-detail batch is too large" }
        require(entryIds.all(String::isNotBlank)) { "entryIds must not contain blank values" }
        return database.mistakeDetailDao().readCurrentBatch(entryIds)
    }

    override suspend fun readMistakeRevisionHistory(
        errorBookEntryId: String,
    ): List<MistakeRevisionSummaryRecord> {
        require(errorBookEntryId.isNotBlank()) { "errorBookEntryId must not be blank" }
        return database.mistakeDetailDao().readRevisionHistory(errorBookEntryId)
    }

    override suspend fun createProblemDraft(
        command: CreateProblemDraftCommand,
    ): ProblemDraftWriteResult = database.problemDraftTransactionDao().create(command)

    override suspend fun appendProblemDraftSourceAsset(
        command: AppendProblemDraftSourceAssetCommand,
    ): AppendProblemDraftSourceAssetResult =
        database.problemDraftTransactionDao().appendSourceAsset(command)

    override suspend fun reviseProblemDraft(
        command: ReviseProblemDraftCommand,
    ): ProblemDraftWriteResult = database.problemDraftTransactionDao().revise(command)

    override suspend fun replaceProblemDraft(
        command: ReplaceProblemDraftCommand,
    ): ProblemDraftReplacementResult = database.withWriteTransaction {
        val draft = database.problemDraftTransactionDao().read(command.replacedDraftId)
        val workspace = draft
            ?.takeIf { it.status == StudyDbValue.ProblemDraftStatus.EDITING }
            ?.let { database.problemDraftEditWorkspaceDao().read(it.draftId) }
        val result = database.problemDraftTransactionDao().replace(command)
        workspace?.let { database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(it.toConsumeCommand()) }
        result
    }

    override suspend fun splitProblemDraft(
        command: SplitProblemDraftCommand,
    ): ProblemDraftSplitResult = database.withWriteTransaction {
        val draft = database.problemDraftTransactionDao().read(command.replacedDraftId)
        val workspace = draft
            ?.takeIf { it.status == StudyDbValue.ProblemDraftStatus.EDITING }
            ?.let { database.problemDraftEditWorkspaceDao().read(it.draftId) }
        val result = database.problemDraftTransactionDao().split(command)
        workspace?.let {
            database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(
                it.toConsumeCommand(),
            )
        }
        result
    }

    override suspend fun readProblemDraft(draftId: String): ProblemDraftRecord? {
        require(draftId.isNotBlank()) { "draftId must not be blank" }
        return database.problemDraftTransactionDao().read(draftId)
    }

    override suspend fun readCanonicalSourceAsset(
        sourceAssetId: String,
    ): CanonicalSourceAssetRecord? {
        require(sourceAssetId.isNotBlank()) { "sourceAssetId must not be blank" }
        return database.problemDraftTransactionDao().readCanonicalSourceAsset(sourceAssetId)
    }

    override suspend fun readUnreferencedCanonicalAssets(): List<CanonicalSourceAssetRecord> =
        database.pendingCaptureDao()
            .findUnreferencedCanonicalAssets()
            .map(CanonicalSourceAssetRow::toRecord)

    override suspend fun deleteUnreferencedCanonicalAssets(): Int =
        database.pendingCaptureDao().deleteUnreferencedCanonicalAssets()

    override suspend fun insertOrphanCanonicalAssetForTest(asset: CanonicalSourceAssetRecord) {
        database.useConnection(isReadOnly = false) { connection ->
            connection.usePrepared(
                "INSERT OR IGNORE INTO canonical_source_asset (" +
                    "source_asset_id, content_sha256, relative_path, mime_type, byte_size, " +
                    "width, height, source_type, created_at_epoch_millis" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ) { statement ->
                statement.bindText(1, asset.sourceAssetId)
                statement.bindText(2, asset.contentSha256)
                statement.bindText(3, asset.relativePath)
                statement.bindText(4, asset.mimeType)
                statement.bindLong(5, asset.byteSize)
                statement.bindLong(6, asset.width.toLong())
                statement.bindLong(7, asset.height.toLong())
                statement.bindText(8, asset.sourceType)
                statement.bindLong(9, asset.createdAtEpochMillis)
                statement.step()
            }
        }
    }

    override suspend fun readPendingCaptureDraft(draftId: String): PendingCaptureDraftRecord? =
        pendingCaptures.readDraft(draftId)

    override suspend fun createBatchImportJob(
        command: CreateBatchImportJobCommand,
    ): BatchImportJobRecord = batchImports.create(command)

    override suspend fun readBatchImportJob(jobId: String): BatchImportJobRecord? =
        batchImports.read(jobId)

    override suspend fun updateBatchImportJobStatus(
        jobId: String,
        expectedStatus: String,
        nextStatus: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.updateStatus(
        jobId,
        expectedStatus,
        nextStatus,
        occurredAtEpochMillis,
    )

    override suspend fun requeueInterruptedBatchImportPages(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = batchImports.requeueInterrupted(jobId, occurredAtEpochMillis)

    override suspend fun claimNextBatchImportPage(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): BatchImportPageRecord? = batchImports.claimNext(jobId, occurredAtEpochMillis)

    override suspend fun completeBatchImportPage(
        jobId: String,
        pageIndex: Int,
        draftId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.completePage(jobId, pageIndex, draftId, occurredAtEpochMillis)

    override suspend fun claimBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.claimBoundary(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun requeueInterruptedBatchImportBoundaries(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Int = batchImports.requeueInterruptedBoundaries(jobId, occurredAtEpochMillis)

    override suspend fun resolveBatchImportBoundary(
        command: ResolveBatchImportBoundaryCommand,
    ): BatchImportJobRecord {
        require(command.jobId.isNotBlank())
        require(command.pageIndex >= 0)
        require(command.primaryDraftId.isNotBlank())
        require(command.followingDraftId.isNotBlank())
        require(command.occurredAtEpochMillis >= 0)
        require(
            command.resolution == StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION ||
                command.resolution == StudyDbValue.BatchImportBoundaryStatus.NEXT_QUESTION ||
                command.resolution == StudyDbValue.BatchImportBoundaryStatus.KEPT_SEPARATE,
        )
        database.withWriteTransaction {
            val batchDao = database.batchImportDao()
            val pages = batchDao.readPages(command.jobId)
            val primaryPage = pages.getOrNull(command.pageIndex)
                ?: throw ImmutablePayloadConflictException(
                    "batch_import_boundary",
                    "${command.jobId}:${command.pageIndex}",
                )
            val followingPage = pages.getOrNull(command.pageIndex + 1)
                ?: throw ImmutablePayloadConflictException(
                    "batch_import_boundary",
                    "${command.jobId}:${command.pageIndex}",
                )
            if (
                primaryPage.pageIndex != command.pageIndex ||
                followingPage.pageIndex != command.pageIndex + 1 ||
                primaryPage.status != StudyDbValue.BatchImportPageStatus.READY ||
                followingPage.status != StudyDbValue.BatchImportPageStatus.READY ||
                primaryPage.boundaryAfterStatus !=
                StudyDbValue.BatchImportBoundaryStatus.CHECKING ||
                primaryPage.resultDraftId != command.primaryDraftId ||
                followingPage.resultDraftId != command.followingDraftId
            ) {
                throw ImmutablePayloadConflictException(
                    "batch_import_boundary",
                    "${command.jobId}:${command.pageIndex}",
                )
            }

            if (
                command.resolution == StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION &&
                command.primaryDraftId != command.followingDraftId
            ) {
                val draftDao = database.problemDraftTransactionDao()
                val primary = draftDao.read(command.primaryDraftId)
                    ?: throw ImmutablePayloadConflictException(
                        "problem_draft",
                        command.primaryDraftId,
                    )
                val following = draftDao.read(command.followingDraftId)
                    ?: throw ImmutablePayloadConflictException(
                        "problem_draft",
                        command.followingDraftId,
                    )
                if (
                    database.problemDraftEditWorkspaceDao().read(command.primaryDraftId) != null ||
                    database.problemDraftEditWorkspaceDao().read(command.followingDraftId) != null
                ) {
                    throw ImmutablePayloadConflictException(
                        "problem_draft_bundle_workspace",
                        command.primaryDraftId,
                    )
                }
                draftDao.mergeSourceBundle(
                    primaryDraftId = command.primaryDraftId,
                    expectedPrimaryRevisionNumber = primary.currentRevision.revisionNumber,
                    followingDraftId = command.followingDraftId,
                    expectedFollowingRevisionNumber = following.currentRevision.revisionNumber,
                    mergedAtEpochMillis = command.occurredAtEpochMillis,
                )
                check(
                    batchDao.remapDraft(
                        jobId = command.jobId,
                        followingDraftId = command.followingDraftId,
                        primaryDraftId = command.primaryDraftId,
                        updatedAtEpochMillis = command.occurredAtEpochMillis,
                    ) > 0,
                ) { "Merged batch draft was not referenced by its batch" }
            }
            check(
                batchDao.resolveBoundary(
                    jobId = command.jobId,
                    pageIndex = command.pageIndex,
                    resolution = command.resolution,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                ) == 1,
            ) { "Claimed batch boundary could not be resolved" }
            batchDao.touchJob(command.jobId, command.occurredAtEpochMillis)
        }
        return checkNotNull(batchImports.read(command.jobId))
    }

    override suspend fun failBatchImportBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.failBoundary(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun failBatchImportPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.failPage(jobId, pageIndex, failureCode, occurredAtEpochMillis)

    override suspend fun retryBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.retryPage(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun skipBatchImportPage(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.skipPage(jobId, pageIndex, occurredAtEpochMillis)

    override suspend fun finishBatchImportIfSettled(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): Boolean = batchImports.finishIfSettled(jobId, occurredAtEpochMillis)

    override suspend fun hasRetainedBatchImportSourceUri(sourceUri: String): Boolean {
        return batchImports.hasRetainedSourceUri(sourceUri)
    }

    override suspend fun readProblemDraftEditWorkspace(
        draftId: String,
    ): ProblemDraftEditWorkspaceRecord? =
        database.problemDraftEditWorkspaceDao().read(draftId)

    override suspend fun saveProblemDraftEditWorkspace(
        command: SaveProblemDraftEditWorkspaceCommand,
    ): ProblemDraftEditWorkspaceWriteResult =
        database.problemDraftEditWorkspaceDao().save(command)

    override suspend fun consumeProblemDraftEditWorkspace(
        command: ConsumeProblemDraftEditWorkspaceCommand,
    ): Boolean = database.problemDraftEditWorkspaceDao().consume(command)

    override suspend fun commitProblemDraft(
        command: CommitProblemDraftCommand,
    ): CommitProblemDraftResult = database.problemDraftTransactionDao().commit(command)

    override suspend fun confirmAndCommitProblemDraftFromWorkspace(
        command: ConfirmAndCommitProblemDraftFromWorkspaceCommand,
    ): CommitProblemDraftResult = database.withWriteTransaction {
        DatabaseContractValidator.validateConfirmAndCommitProblemDraftFromWorkspace(command)
        database.problemDraftTransactionDao().replayCommit(
            command = command.commit,
            allowTutorDraft = false,
        )?.let { return@withWriteTransaction it }
        val workspace = database.problemDraftEditWorkspaceDao()
            .requireExactForConfirmation(command.workspace)
        val confirmedRevision = workspace.toConfirmedRevision(command.workspace)
        database.problemDraftTransactionDao().revise(
            ReviseProblemDraftCommand(
                draftId = command.workspace.draftId,
                expectedRevisionNumber = command.workspace.basisRevisionNumber,
                revision = confirmedRevision,
            ),
        )
        val result = database.problemDraftTransactionDao().commit(command.commit)
        database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(
            command.workspace.toConsumeCommand(),
        )
        result
    }

    override suspend fun attachCleanRedrawAsset(
        revisionId: String,
        asset: CanonicalSourceAssetRecord,
    ): Boolean = database.withWriteTransaction {
        database.problemDraftTransactionDao().attachCleanRedrawAsset(
            revisionId = revisionId,
            asset = asset,
        )
    }

    override suspend fun confirmTutorSession(
        command: ConfirmTutorSessionCommand,
    ): TutorSessionWriteResult = database.problemDraftTransactionDao().confirmTutorSession(command)

    override suspend fun confirmTutorSessionFromWorkspace(
        command: ConfirmTutorSessionFromWorkspaceCommand,
    ): TutorSessionWriteResult = database.withWriteTransaction {
        DatabaseContractValidator.validateConfirmTutorSessionFromWorkspace(command)
        database.problemDraftTransactionDao().readTutorSession(command.sessionId)?.let { existing ->
            if (
                existing.draftId != command.workspace.draftId ||
                existing.draftRevisionNumber != command.workspace.basisRevisionNumber + 1 ||
                existing.createdAtEpochMillis != command.workspace.finalOccurredAtEpochMillis
            ) {
                throw ImmutablePayloadConflictException("tutor_session", command.sessionId)
            }
            return@withWriteTransaction TutorSessionWriteResult(
                created = false,
                session = existing,
            )
        }
        val workspace = database.problemDraftEditWorkspaceDao()
            .requireExactForConfirmation(command.workspace)
        val result = database.problemDraftTransactionDao().confirmTutorSession(
            ConfirmTutorSessionCommand(
                sessionId = command.sessionId,
                draftId = command.workspace.draftId,
                expectedRevisionNumber = command.workspace.basisRevisionNumber,
                confirmedRevision = workspace.toConfirmedRevision(command.workspace),
                createdAtEpochMillis = command.workspace.finalOccurredAtEpochMillis,
            ),
        )
        database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(
            command.workspace.toConsumeCommand(),
        )
        result
    }

    override suspend fun readTutorSession(sessionId: String): TutorSessionRecord? {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        return database.problemDraftTransactionDao().readTutorSession(sessionId)
    }

    override suspend fun createTutorConversation(
        command: CreateTutorConversationDatabaseCommand,
    ): TutorConversationRecord = database.tutorConversationDao().createConversation(command)

    override suspend fun appendTutorStudentMessage(
        command: AppendTutorStudentMessageDatabaseCommand,
    ): TutorMessageRecord = database.tutorConversationDao().appendStudentMessage(command)

    override suspend fun appendTutorAssistantMessage(
        command: AppendTutorAssistantMessageDatabaseCommand,
    ): TutorMessageRecord = database.tutorConversationDao().appendAssistantMessage(command)

    override suspend fun updateTutorMessageStatus(
        command: UpdateTutorMessageStatusDatabaseCommand,
    ): TutorMessageRecord = database.tutorConversationDao().updateMessageStatus(command)

    override suspend fun pauseTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord = database.tutorConversationDao().pauseConversation(
        conversationId = conversationId,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    override suspend fun archiveTutorConversation(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ): TutorConversationRecord = database.tutorConversationDao().archiveConversation(
        conversationId = conversationId,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    override suspend fun deleteTutorConversation(conversationId: String) {
        database.tutorConversationDao().deleteConversation(conversationId)
    }

    override suspend fun saveTutorConversationDraft(
        conversationId: String,
        draft: String,
        updatedAtEpochMillis: Long,
    ) {
        database.tutorConversationDao().saveStudentDraft(
            SaveTutorConversationDraftDatabaseCommand(
                conversationId = conversationId,
                draft = draft,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun clearTutorConversationDraft(
        conversationId: String,
        updatedAtEpochMillis: Long,
    ) {
        database.tutorConversationDao().clearStudentDraft(
            ClearTutorConversationDraftDatabaseCommand(
                conversationId = conversationId,
                updatedAtEpochMillis = updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun commitTutorSession(
        command: CommitTutorSessionCommand,
    ): CommitProblemDraftResult = database.withWriteTransaction {
        val result = database.problemDraftTransactionDao().commitTutorSession(command)
        database.tutorExposureDao().bindAnchor(
            PersistTutorSessionAnchorCommand(
                sessionId = command.sessionId,
                problemRevisionId = result.receipt.problemRevisionId,
                practiceUnitId = result.receipt.practiceUnitId,
                source = "DRAFT_COMMIT",
                anchoredAtEpochMillis = result.receipt.committedAtEpochMillis,
            ),
        )
        result
    }

    override suspend fun endTutorSession(
        command: EndTutorSessionCommand,
    ): EndTutorSessionResult = database.withWriteTransaction {
        val session = database.problemDraftTransactionDao().readTutorSession(command.sessionId)
        val workspace = session?.let { database.problemDraftEditWorkspaceDao().read(it.draftId) }
        val result = database.problemDraftTransactionDao().endTutorSession(command)
        workspace?.let { database.problemDraftEditWorkspaceDao().deleteExactAfterFinalization(it.toConsumeCommand()) }
        result
    }

    override suspend fun recordTutorChoice(
        command: PersistTutorChoiceCommand,
    ): TutorTurnResponseRecord = database.tutorInteractionDao().recordChoice(command)

    override suspend fun recordTutorMove(
        command: PersistTutorMoveCommand,
    ): TutorTurnResponseRecord = database.tutorInteractionDao().recordMove(command)

    override suspend fun revealTutorSolution(
        command: PersistTutorRevealCommand,
    ): TutorTurnResponseRecord = database.tutorInteractionDao().revealSolution(command)

    override suspend fun recordTutorSolutionExposure(
        command: PersistTutorAnswerExposureCommand,
    ): TutorAnswerExposureRecord = database.tutorExposureDao().recordVisibleExposure(command)

    override suspend fun bindTutorSessionProblemAnchor(
        command: PersistTutorSessionAnchorCommand,
    ): TutorSessionProblemAnchorRecord = database.tutorExposureDao().bindAnchor(command)

    override suspend fun reconcileTutorAnswerExposures(learnerId: String, limit: Int): Int =
        database.tutorExposureDao().reconcilePending(learnerId, limit)

    override suspend fun readTutorAnswerExposure(
        modelTaskRequestId: String,
    ): TutorAnswerExposureRecord? =
        database.tutorExposureDao().readExposure(modelTaskRequestId)

    override suspend fun readTutorAnswerExposures(
        modelTaskRequestIds: Set<String>,
    ): List<TutorAnswerExposureRecord> =
        database.tutorExposureDao().readExposures(modelTaskRequestIds)

    override fun observeModelTask(
        requestId: String,
    ) = database.modelTaskTransactionDao().observe(requestId)

    override fun observeModelTasks(
        subjectId: String,
        kind: com.tingyun.smartmistakebook.core.model.ModelTaskKind,
    ) = database.modelTaskTransactionDao().observeBySubject(subjectId, kind)

    override fun observeRecentModelTasks(
        subjectId: String,
        kind: com.tingyun.smartmistakebook.core.model.ModelTaskKind,
        limit: Int,
    ) = database.modelTaskTransactionDao().observeRecentBySubject(subjectId, kind, limit)

    override suspend fun readModelTask(requestId: String) =
        database.modelTaskTransactionDao().read(requestId)

    override suspend fun createModelTask(command: CreateModelTaskCommand) =
        database.modelTaskTransactionDao().create(command)

    override suspend fun reserveModelTaskRemoteDispatch(
        command: ReserveModelTaskRemoteDispatchCommand,
    ) = database.modelTaskTransactionDao().reserveRemoteDispatch(command)

    override suspend fun transitionModelTask(command: TransitionModelTaskCommand) =
        database.modelTaskTransactionDao().transition(command)

    override suspend fun seedFixture(bundle: StudySeedBundle): SeedResult {
        DatabaseContractValidator.validateSeedBundle(bundle)
        val result = database.fixtureSeedDao().seed(
            problems = bundle.problems.map(ProblemSeedRecord::toEntity),
            revisions = bundle.revisions.map(ProblemRevisionSeedRecord::toEntity),
            practiceUnits = bundle.practiceUnits.map(PracticeUnitSeedRecord::toEntity),
            errorBookEntries = bundle.errorBookEntries.map(ErrorBookEntrySeedRecord::toEntity),
            knowledgeNodes = bundle.knowledgeNodes.map(KnowledgeNodeSeedRecord::toEntity),
            knowledgeBindings = bundle.knowledgeBindings.map(KnowledgeBindingSeedRecord::toEntity),
            relations = bundle.relations.map(ProblemRelationSeedRecord::toEntity),
            assessmentItems = bundle.assessmentItems.map(AssessmentItemSnapshotSeedRecord::toEntity),
            assessmentEvents = bundle.assessmentEvents.map(AssessmentEventSeedRecord::toEntity),
            memoryStates = bundle.problemMemoryStates.map(ProblemMemoryStateRecord::toEntity),
            masteryStates = bundle.knowledgeMasteryStates.map(KnowledgeMasteryStateRecord::toEntity),
            reviewPlans = bundle.reviewPlans.map(ReviewPlanRecord::toEntity),
            reviewQueueItems = bundle.reviewQueueItems.map(ReviewQueueItemRecord::toEntity),
            reviewQueueKnowledgeNodes = bundle.reviewQueueItems.flatMap(
                ReviewQueueItemRecord::toKnowledgeNodeEntities,
            ),
            reviewQueueReasons = bundle.reviewQueueItems.flatMap(
                ReviewQueueItemRecord::toReasonEntities,
            ),
            reviewSessions = bundle.reviewSessions.map(ReviewSessionRecord::toEntity),
            reviewSessionRevisions = bundle.reviewSessions.map(ReviewSessionRecord::toRevisionEntity),
        )
        return SeedResult(
            insertedProblemCount = result.insertedProblemCount,
            insertedErrorBookEntryCount = result.insertedErrorBookEntryCount,
        )
    }

    override suspend fun saveAssessmentItemSnapshot(item: AssessmentItemSnapshotSeedRecord) {
        DatabaseContractValidator.validateAssessmentItem(item)
        database.immutableLearningFactDao().saveAssessmentItem(item.toEntity())
    }

    override suspend fun saveAssessmentEvidenceSnapshot(
        snapshot: com.tingyun.smartmistakebook.core.model.AssessmentEvidenceSnapshot,
    ) {
        database.attemptTransactionDao().saveAssessmentEvidenceSnapshot(snapshot)
    }

    override suspend fun appendAssessmentEvent(event: AssessmentEventSeedRecord) {
        DatabaseContractValidator.validateAssessmentEvent(event)
        database.immutableLearningFactDao().saveAssessmentEvent(event.toEntity())
    }

    override suspend fun recordAttempt(command: AttemptWriteCommand): AttemptWriteResult {
        val result = database.attemptTransactionDao().recordAttempt(command)
        return AttemptWriteResult(
            submissionId = result.submissionId,
            created = result.created,
            attempt = result.attempt,
            canonicalFingerprint = result.canonicalFingerprint,
            outboxId = result.outbox.outboxId,
        )
    }

    override suspend fun recordReviewAttempt(
        command: ReviewAttemptWriteCommand,
    ): ReviewAttemptWriteResult = database.withWriteTransaction {
        val attempt = this@RoomStudyDatabase.recordAttempt(command.attempt)
        val persistedPracticeUnitId = attempt.attempt.assessmentSnapshot.practiceUnitId
        if (persistedPracticeUnitId != command.practiceUnitId) {
            throw ImmutablePayloadConflictException("review_attempt", attempt.attempt.attemptId)
        }
        val advanceCommand = ReviewSessionAdvanceCommand(
            sessionId = command.sessionId,
            expectedStateVersion = command.expectedStateVersion,
            reviewQueueItemId = command.reviewQueueItemId,
            practiceUnitId = persistedPracticeUnitId,
            attemptId = attempt.attempt.attemptId,
            submissionId = attempt.submissionId,
            presentationId = attempt.attempt.presentationId,
            occurredAtEpochMillis = attempt.attempt.occurredAtEpochMillis,
        )
        DatabaseContractValidator.validateReviewSessionAdvance(advanceCommand)
        val transition = database.reviewPlanTransactionDao().advanceSession(
            command = advanceCommand,
            attemptCreatedInCurrentTransaction = attempt.created,
        )
        val advance = ReviewSessionAdvanceResult(
            created = transition.created,
            session = transition.session.toRecord(),
            receipt = transition.receipt.toRecord(),
        )
        ReviewAttemptWriteResult(attempt = attempt, advance = advance)
    }

    override suspend fun recordAnswerReveal(
        command: AnswerRevealWriteCommand,
    ): AnswerRevealWriteResult {
        val result = database.attemptTransactionDao().recordAnswerReveal(command)
        return AnswerRevealWriteResult(
            created = result.created,
            outcome = result.outcome,
            canonicalFingerprint = result.canonicalFingerprint,
            outboxId = result.outbox.outboxId,
        )
    }

    override suspend fun reconcileAnswerRevealOutcomes(
        learnerId: String,
        limit: Int,
    ): List<AnswerRevealWriteResult> = database.attemptTransactionDao()
        .reconcileAnswerRevealOutcomes(learnerId, limit)
        .map { result ->
            AnswerRevealWriteResult(
                created = result.created,
                outcome = result.outcome,
                canonicalFingerprint = result.canonicalFingerprint,
                outboxId = result.outbox.outboxId,
            )
        }

    override suspend fun appendAttemptCorrection(
        correction: AttemptCorrectionRecord,
    ): AttemptCorrectionResult {
        val result = database.attemptTransactionDao().appendCorrection(correction)
        return AttemptCorrectionResult(
            created = result.created,
            correction = result.correction,
            canonicalFingerprint = result.canonicalFingerprint,
            outboxId = result.outbox.outboxId,
        )
    }

    override suspend fun findAttemptPersistence(
        submissionId: String,
    ): AttemptPersistenceRecord? = database.learningDao().findAttemptPersistence(submissionId)

    override suspend fun recordReviewLogEntries(entries: List<ReviewLogEntry>) {
        database.learningDao().recordReviewLog(
            entries.map { entry ->
                ReviewLogEntity(
                    learnerId = entry.learnerId,
                    cardId = entry.practiceUnitId,
                    rating = entry.rating,
                    deltaTDays = entry.deltaTDays,
                    durationMs = entry.durationMs,
                    reviewedAtUtc = entry.reviewedAtEpochMillis,
                    sourceKind = entry.sourceKind,
                    sourceId = entry.sourceId,
                    evidenceWeight = entry.evidenceWeight,
                    schedulingEligible = entry.schedulingEligible,
                    timeBucket = entry.timeBucket,
                    scrollUpCount = entry.scrollUpCount,
                    editCount = entry.editCount,
                    interruptionCount = entry.interruptionCount,
                    awayMillis = entry.awayMillis,
                    plannedReason = entry.plannedReason,
                    recordedAt = entry.recordedAtEpochMillis,
                )
            },
        )
    }

    override suspend fun readReviewLogSamples(learnerId: String, limit: Int): List<ReviewLogSampleRecord> =
        database.learningDao().readReviewLogSamples(learnerId, limit).map { row ->
            ReviewLogSampleRecord(
                practiceUnitId = row.practiceUnitId,
                reviewedAtEpochMillis = row.reviewedAtUtc,
                rating = row.rating,
                durationMs = row.durationMs,
                timeBucket = row.timeBucket,
                sourceKind = row.sourceKind,
                evidenceWeight = row.evidenceWeight,
                scrollUpCount = row.scrollUpCount,
                editCount = row.editCount,
                interruptionCount = row.interruptionCount,
                awayMillis = row.awayMillis,
                plannedReason = row.plannedReason,
                deltaTDays = row.deltaTDays,
            )
        }

    override suspend fun readLastReviewLogAt(
        learnerId: String,
        practiceUnitId: String,
        sourceKind: String,
    ): Long? = database.learningDao().readLastReviewLogAt(learnerId, practiceUnitId, sourceKind)

    override suspend fun findAttemptAdvanceProof(
        attemptId: String,
    ): AttemptAdvanceProofRecord? = database.learningDao().findAttemptAdvanceProof(attemptId)

    override suspend fun markRelationsStaleForRevision(
        problemRevisionId: String,
        updatedAtEpochMillis: Long,
    ): Int {
        require(problemRevisionId.isNotBlank()) { "problemRevisionId must not be blank" }
        require(updatedAtEpochMillis >= 0) { "updatedAtEpochMillis cannot be negative" }
        return database.problemDao().markRelationsStaleForRevision(
            problemRevisionId = problemRevisionId,
            staleStatus = StudyDbValue.RelationStatus.STALE,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    override suspend fun loadProjectionBatch(
        projectionName: String,
        learnerId: String,
        limit: Int,
    ): ProjectionBatch = database.projectionTransactionDao()
        .loadProjectionBatch(projectionName, learnerId, limit)

    override suspend fun recordChatEvidence(entries: List<com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity>) {
        database.chatEvidenceDao().insertAsLedgerEvents(entries)
    }

    override suspend fun readChatEvidenceByLearner(learnerId: String): List<com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity> =
        database.chatEvidenceDao().readByLearner(learnerId)

    override suspend fun readChatEvidenceByConversation(conversationId: String): List<com.tingyun.smartmistakebook.core.database.entity.LearnerChatEvidenceEntity> =
        database.chatEvidenceDao().readByConversation(conversationId)

    override suspend fun loadLearningLedger(learnerId: String): LearningLedgerRead =
        database.projectionTransactionDao().loadLearningLedger(learnerId)

    override suspend fun readCurrentLearnerSnapshot(
        projectionName: String,
        learnerId: String,
    ): PersistedLearnerSnapshot? = database.projectionTransactionDao()
        .readCurrentSnapshot(projectionName, learnerId)

    override suspend fun commitProjection(
        commit: ProjectionCommit,
    ): PersistedLearnerSnapshot = database.projectionTransactionDao().commitProjection(commit)

    override suspend fun saveReviewPlan(bundle: ReviewPlanBundle) {
        DatabaseContractValidator.validateReviewBundle(bundle)
        database.reviewPlanTransactionDao().savePlan(
            plan = bundle.plan.toEntity(),
            queue = bundle.queue.map(ReviewQueueItemRecord::toEntity),
            knowledgeNodes = bundle.queue.flatMap(ReviewQueueItemRecord::toKnowledgeNodeEntities),
            reasons = bundle.queue.flatMap(ReviewQueueItemRecord::toReasonEntities),
            activeSession = bundle.activeSession?.toEntity(),
            isCurrent = bundle.isCurrent,
        )
    }

    override suspend fun saveReviewSession(session: ReviewSessionRecord) {
        DatabaseContractValidator.validateReviewSessionCreation(session)
        database.reviewPlanTransactionDao().saveSession(session.toEntity())
    }

    @Deprecated("New review transitions must use recordReviewAttempt")
    override suspend fun advanceReviewSession(
        command: ReviewSessionAdvanceCommand,
    ): ReviewSessionAdvanceResult {
        DatabaseContractValidator.validateReviewSessionAdvance(command)
        val result = database.reviewPlanTransactionDao().advanceSession(
            command = command,
            attemptCreatedInCurrentTransaction = false,
        )
        return ReviewSessionAdvanceResult(
            created = result.created,
            session = result.session.toRecord(),
            receipt = result.receipt.toRecord(),
        )
    }

    override suspend fun readAssessmentSnapshotP0(
        assessmentItemSnapshotId: String,
    ): AssessmentItemSnapshotSeedRecord? = database.immutableLearningFactDao()
        .findAssessmentItem(assessmentItemSnapshotId)
        ?.toRecord()

    override suspend fun readAttemptP0(attemptId: String): PersistedAttemptP0? =
        database.learningDao().readAttempt(attemptId)

    override suspend fun readCorrectionP0(correctionId: String): PersistedCorrectionP0? =
        database.learningDao().readCorrection(correctionId)

    override suspend fun readAnswerRevealP0(outcomeId: String): PersistedAnswerRevealP0? =
        database.learningDao().readAnswerReveal(outcomeId)

    override fun observeConfirmedProblemOrganization(
        problemId: String,
        problemRevisionId: String,
    ): Flow<ConfirmedProblemOrganizationRecord> =
        problemOrganization.observe(problemId, problemRevisionId)

    override suspend fun confirmProblemOrganization(
        command: ConfirmProblemOrganizationCommand,
    ): ConfirmProblemOrganizationResult = problemOrganization.confirm(command)

    override fun close() = database.close()
}

private fun ProblemSeedRecord.toEntity() = ProblemEntity(
    problemId = problemId,
    canonicalFingerprint = canonicalFingerprint,
    subject = subject,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ProblemRevisionSeedRecord.toEntity() = ProblemRevisionEntity(
    revisionId = revisionId,
    problemId = problemId,
    revisionNumber = revisionNumber,
    title = title,
    problemMarkdown = problemMarkdown,
    questionDocumentSnapshot = questionDocumentSnapshot,
    answerSpecId = answerSpecId,
    answerSpecSnapshot = answerSpecSnapshot,
    answerVerificationStatus = answerVerificationStatus,
    sourceType = sourceType,
    sourceReference = sourceReference,
    contentFingerprint = contentFingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun PracticeUnitSeedRecord.toEntity() = PracticeUnitEntity(
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    unitKey = unitKey,
    unitKind = unitKind,
    title = title,
    promptMarkdown = promptMarkdown,
    estimatedSeconds = estimatedSeconds,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ErrorBookEntrySeedRecord.toEntity() = ErrorBookEntryEntity(
    entryId = entryId,
    practiceUnitId = practiceUnitId,
    problemId = problemId,
    currentRevisionId = currentRevisionId,
    sourceKey = sourceKey,
    status = status,
    acceptedAtEpochMillis = acceptedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun ProblemRelationSeedRecord.toEntity() = ProblemRelationEntity(
    relationId = relationId,
    sourceProblemId = sourceProblemId,
    targetProblemId = targetProblemId,
    relationType = relationType,
    status = status,
    sourceBasisRevisionId = sourceBasisRevisionId,
    targetBasisRevisionId = targetBasisRevisionId,
    confidence = confidence,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun AssessmentItemSnapshotSeedRecord.toEntity() = AssessmentItemSnapshotEntity(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun AssessmentItemSnapshotEntity.toRecord() = AssessmentItemSnapshotSeedRecord(
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    itemRevision = itemRevision,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    tutorContentSnapshotId = tutorContentSnapshotId,
    promptMarkdown = promptMarkdown,
    optionsSnapshot = optionsSnapshot,
    answerSpecSnapshot = answerSpecSnapshot,
    verificationStatus = verificationStatus,
    assessmentEligibility = assessmentEligibility,
    scoringMode = scoringMode,
    learnerSnapshotVersion = learnerSnapshotVersion,
    projectionCheckpoint = projectionCheckpoint,
    hintLevelAtPresentation = hintLevelAtPresentation,
    answerRevealState = answerRevealState,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun AssessmentEventSeedRecord.toEntity() = AssessmentEventEntity(
    assessmentEventId = assessmentEventId,
    assessmentItemSnapshotId = assessmentItemSnapshotId,
    eventSequence = eventSequence,
    eventType = eventType,
    hintLevel = hintLevel,
    submittedResponse = submittedResponse,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun ProblemMemoryStateRecord.toEntity() = ProblemMemoryStateEntity(
    practiceUnitId = practiceUnitId,
    stabilityDays = stabilityDays,
    difficulty = difficulty,
    lastReviewedAtEpochMillis = lastReviewedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    reviewCount = reviewCount,
    lapseCount = lapseCount,
    retrievability = retrievability,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun KnowledgeMasteryStateRecord.toEntity() = KnowledgeMasteryStateEntity(
    knowledgeNodeId = knowledgeNodeId,
    masteryProbability = masteryProbability,
    independentCorrectCount = independentCorrectCount,
    assistedCorrectCount = assistedCorrectCount,
    incorrectCount = incorrectCount,
    evidenceWeightTotal = evidenceWeightTotal,
    lastEvidenceAtEpochMillis = lastEvidenceAtEpochMillis,
    projectionCheckpoint = projectionCheckpoint,
    projectorVersion = projectorVersion,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun ReviewPlanRecord.toEntity() = ReviewPlanEntity(
    reviewPlanId = reviewPlanId,
    learnerId = learnerId,
    localDate = localDate,
    localDayEpochDay = localDayEpochDay,
    timeZoneId = timeZoneId,
    timeBudgetSeconds = timeBudgetSeconds,
    planningAtEpochMillis = planningAtEpochMillis,
    status = status,
    plannerVersion = plannerVersion,
    projectionCheckpoint = projectionCheckpoint,
    inputFingerprint = inputFingerprint,
    planFingerprint = planFingerprint,
    planRevision = planRevision,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun ReviewQueueItemRecord.toEntity() = ReviewQueueItemEntity(
    reviewQueueItemId = reviewQueueItemId,
    reviewPlanId = reviewPlanId,
    practiceUnitId = practiceUnitId,
    itemFamilyId = itemFamilyId,
    sourceBundleId = sourceBundleId,
    ordinal = ordinal,
    priorityScore = priorityScore,
    difficultyBand = difficultyBand,
    dueAtEpochMillis = dueAtEpochMillis,
    estimatedSeconds = estimatedSeconds,
    reasonSnapshot = reasonSnapshot,
    status = status,
)

private fun ReviewQueueItemRecord.toKnowledgeNodeEntities() = knowledgeNodeIds
    .sorted()
    .map { ReviewQueueKnowledgeNodeEntity(reviewQueueItemId, it) }

private fun ReviewQueueItemRecord.toReasonEntities() = reasons
    .sorted()
    .map { ReviewQueueReasonEntity(reviewQueueItemId, it) }

private fun ReviewSessionRecord.toEntity() = ReviewSessionEntity(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    activeSessionKey = reviewPlanId.takeIf { status == StudyDbValue.ReviewStatus.IN_PROGRESS },
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

private fun ReviewSessionRecord.toRevisionEntity() = ReviewSessionRevisionEntity(
    reviewSessionId = reviewSessionId,
    stateVersion = stateVersion,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
)

private fun ReviewPlanAggregate.toRecord(): ReviewPlanBundle {
    val sortedQueue = queue.sortedBy { it.item.ordinal }
    return ReviewPlanBundle(
        plan = ReviewPlanRecord(
            reviewPlanId = plan.reviewPlanId,
            learnerId = plan.learnerId,
            localDate = plan.localDate,
            localDayEpochDay = plan.localDayEpochDay,
            timeZoneId = plan.timeZoneId,
            timeBudgetSeconds = plan.timeBudgetSeconds,
            planningAtEpochMillis = plan.planningAtEpochMillis,
            status = plan.status,
            plannerVersion = plan.plannerVersion,
            projectionCheckpoint = plan.projectionCheckpoint,
            inputFingerprint = plan.inputFingerprint,
            planFingerprint = plan.planFingerprint,
            planRevision = plan.planRevision,
            createdAtEpochMillis = plan.createdAtEpochMillis,
        ),
        queue = sortedQueue.map { aggregate ->
            val item = aggregate.item
            ReviewQueueItemRecord(
                reviewQueueItemId = item.reviewQueueItemId,
                reviewPlanId = item.reviewPlanId,
                practiceUnitId = item.practiceUnitId,
                knowledgeNodeIds = aggregate.knowledgeNodes.mapTo(linkedSetOf()) { it.knowledgeNodeId },
                itemFamilyId = item.itemFamilyId,
                sourceBundleId = item.sourceBundleId,
                reasons = aggregate.reasons.mapTo(linkedSetOf()) { it.reason },
                ordinal = item.ordinal,
                priorityScore = item.priorityScore,
                difficultyBand = item.difficultyBand,
                dueAtEpochMillis = item.dueAtEpochMillis,
                estimatedSeconds = item.estimatedSeconds,
                reasonSnapshot = item.reasonSnapshot,
                status = item.status,
            )
        },
        activeSession = activeSessionHead()?.toRecord(),
        isCurrent = currentSlots.isNotEmpty(),
        latestSession = latestSessionHead()?.toRecord(),
    )
}

private fun ReviewSessionEntity.toRecord() = ReviewSessionRecord(
    reviewSessionId = reviewSessionId,
    reviewPlanId = reviewPlanId,
    status = status,
    startedAtEpochMillis = startedAtEpochMillis,
    lastActiveAtEpochMillis = lastActiveAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    currentOrdinal = currentOrdinal,
    timeBudgetSeconds = timeBudgetSeconds,
    projectionCheckpoint = projectionCheckpoint,
    stateVersion = stateVersion,
)

private fun ProblemDraftEditWorkspaceRecord.toConfirmedRevision(
    expected: ExpectedProblemDraftEditWorkspace,
): ProblemDraftRevisionRecord {
    val workspace = try {
        DatabaseContractValidator.decodeProblemDraftEditWorkspace(
            snapshotSchemaVersion = snapshotSchemaVersion,
            workspaceSnapshot = workspaceSnapshot,
            workspaceFingerprint = workspaceFingerprint,
        )
    } catch (failure: Exception) {
        throw ProblemDraftEditWorkspaceIntegrityException(
            "Problem-draft workspace $draftId is corrupted",
            failure,
        )
    }
    val finalRequest = workspace.finalConfirmationRequest
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no final confirmation identity",
        )
    if (
        draftId != expected.draftId ||
        basisRevisionNumber != expected.basisRevisionNumber ||
        workspaceVersion != expected.workspaceVersion ||
        workspaceFingerprint != expected.workspaceFingerprint ||
        finalRequest.requestId != expected.finalRequestId ||
        finalRequest.occurredAtEpochMillis != expected.finalOccurredAtEpochMillis ||
        expected.finalOccurredAtEpochMillis < updatedAtEpochMillis
    ) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId does not match the final request",
        )
    }
    val subject = workspace.subject
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed subject",
        )
    val title = workspace.workingDocument.document.title
        ?.takeIf(String::isNotBlank)
        ?: throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId has no confirmed title",
        )
    if (CapturedQuestionDocumentValidator.validateForCommit(workspace.workingDocument).isNotEmpty()) {
        throw ProblemDraftEditWorkspaceConflictException(
            "Problem-draft workspace $draftId is not ready to confirm",
        )
    }
    return ProblemDraftRevisionRecord(
        draftId = draftId,
        revisionNumber = basisRevisionNumber + 1,
        basisRevisionNumber = basisRevisionNumber,
        subject = subject,
        title = title,
        questionDocument = workspace.workingDocument,
        documentFingerprint = CapturedQuestionDocumentFingerprint.of(workspace.workingDocument),
        author = StudyDbValue.ProblemDraftAuthor.USER,
        createdAtEpochMillis = expected.finalOccurredAtEpochMillis,
    )
}

private fun ExpectedProblemDraftEditWorkspace.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

private fun ProblemDraftEditWorkspaceRecord.toConsumeCommand() =
    ConsumeProblemDraftEditWorkspaceCommand(
        draftId = draftId,
        basisRevisionNumber = basisRevisionNumber,
        expectedWorkspaceVersion = workspaceVersion,
        expectedWorkspaceFingerprint = workspaceFingerprint,
    )

private fun ReviewSessionAdvanceReceiptEntity.toRecord() = ReviewSessionAdvanceReceipt(
    sessionId = reviewSessionId,
    fromVersion = fromVersion,
    toVersion = toVersion,
    reviewQueueItemId = reviewQueueItemId,
    practiceUnitId = practiceUnitId,
    attemptId = attemptId,
    submissionId = submissionId,
    presentationId = presentationId,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun MistakeRow.toRecord() = MistakeRecord(
    entryId = entryId,
    problemId = problemId,
    problemRevisionId = problemRevisionId,
    practiceUnitId = practiceUnitId,
    sourceKey = sourceKey,
    subject = subject,
    title = title,
    problemMarkdown = problemMarkdown,
    status = status,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
    retrievability = retrievability,
    estimatedSeconds = estimatedSeconds,
    knowledgeNodeIds = knowledgeNodeIds.toCatalogLabels().toCollection(linkedSetOf()),
    chapterLabels = chapterLabels.toCatalogLabels(),
    knowledgeLabels = knowledgeLabels.toCatalogLabels(),
    captureOccurrenceCount = maxOf(1, captureOccurrenceCount),
)

private fun CanonicalSourceAssetRow.toRecord() = CanonicalSourceAssetRecord(
    sourceAssetId = sourceAssetId,
    contentSha256 = contentSha256,
    relativePath = relativePath,
    mimeType = mimeType,
    byteSize = byteSize,
    width = width,
    height = height,
    sourceType = sourceType,
    createdAtEpochMillis = createdAtEpochMillis,
)

