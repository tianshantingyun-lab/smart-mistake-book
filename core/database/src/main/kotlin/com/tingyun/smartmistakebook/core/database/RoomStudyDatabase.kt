package com.tingyun.smartmistakebook.core.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.tingyun.smartmistakebook.core.database.dao.MistakeRow
import com.tingyun.smartmistakebook.core.database.dao.KnowledgeGroundingSummaryRow
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
import com.tingyun.smartmistakebook.core.database.dao.toModel
import com.tingyun.smartmistakebook.core.database.entity.AssessmentEventEntity
import com.tingyun.smartmistakebook.core.database.entity.AssessmentItemSnapshotEntity
import com.tingyun.smartmistakebook.core.database.entity.ErrorBookEntryEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeMasteryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingRequestEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeGroundingResolutionEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeNodeSourceBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSearchFeatureEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeSourceEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialEntity
import com.tingyun.smartmistakebook.core.database.entity.KnowledgeTeachingMaterialNodeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitEntity
import com.tingyun.smartmistakebook.core.database.entity.PracticeUnitKnowledgeBindingEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemDraftCommitReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemMemoryStateEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemOrganizationWorkEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRelationEntity
import com.tingyun.smartmistakebook.core.database.entity.ProblemRevisionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewPlanEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueItemEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueKnowledgeNodeEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewQueueReasonEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionAdvanceReceiptEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionEntity
import com.tingyun.smartmistakebook.core.database.entity.ReviewSessionRevisionEntity
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentCodec
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentValidator
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

internal class RoomStudyDatabase(
    internal val database: StudyDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    freeResponseOutboxCipher: TutorFreeResponseOutboxCipher,
    freeResponseOutboxExpirySchedulerFactory: TutorFreeResponseOutboxExpirySchedulerFactory =
        CoroutineTutorFreeResponseOutboxExpirySchedulerFactory,
) : StudyDatabasePort,
    BatchCaptureDraftImportPort,
    TutorConversationSessionDatabasePort,
    TutorLearningEvidenceSessionDatabasePort,
    CurrentTutorInteractionSessionDatabasePort,
    CurrentTutorSessionHostWorkDatabasePort {
    private val knowledgeResearchReviewStore = RoomKnowledgeResearchReviewStore(database)

    private val problemOrganization = RoomProblemOrganizationStore(database)
    private val batchImports = RoomBatchImportStore(database)
    private val batchCaptureImports = RoomBatchCaptureDraftImportStore(database)
    private val pendingCaptureSupport =
        RoomStudyDatabasePendingCaptureSupport(database)
    private val knowledgeSupport =
        RoomStudyDatabaseKnowledgeSupport(database)
    private val legacyAuthoritySupport =
        RoomStudyDatabaseLegacyAuthoritySupport(database)
    private val problemOrganizationWork =
        RoomStudyDatabaseProblemOrganizationWorkSupport(database, problemOrganization, clock)
    private val currentTutorInteractions =
        RoomCurrentTutorInteractionSessionStore(
            database,
            ::trustedClockEpochMillis,
            freeResponseOutboxCipher,
            freeResponseOutboxExpirySchedulerFactory,
        )
    override suspend fun createTutorConversation(
        command: CreateTutorConversationCommand,
    ): TutorConversationWriteResult =
        database.tutorLearningMemoryDao().createConversation(
            command,
            trustedClockEpochMillis(),
        )

    override suspend fun openTutorConversation(
        learnerId: String,
        conversationId: String,
        conversationGeneration: Long,
    ) = database.tutorLearningMemoryDao()
        .openConversation(learnerId, conversationId, conversationGeneration)
        ?.let { entity ->
            com.tingyun.smartmistakebook.core.model.TutorConversation(
                conversationId = entity.conversationId,
                learnerScopeId = entity.learnerId,
                generation = entity.generation,
                status = com.tingyun.smartmistakebook.core.model.TutorConversationStatus
                    .valueOf(entity.status),
                createdAtEpochMillis = entity.createdAtEpochMillis,
                archivedAtEpochMillis = entity.archivedAtEpochMillis,
                stateVersion = entity.stateVersion,
            )
        }

    override suspend fun latestActiveTutorConversation(
        learnerId: String,
    ) = database.tutorLearningMemoryDao()
        .latestActiveConversation(learnerId)
        ?.let { entity ->
            com.tingyun.smartmistakebook.core.model.TutorConversation(
                conversationId = entity.conversationId,
                learnerScopeId = entity.learnerId,
                generation = entity.generation,
                status = com.tingyun.smartmistakebook.core.model.TutorConversationStatus
                    .valueOf(entity.status),
                createdAtEpochMillis = entity.createdAtEpochMillis,
                archivedAtEpochMillis = entity.archivedAtEpochMillis,
                stateVersion = entity.stateVersion,
            )
        }

    override suspend fun latestActiveTutorConversationInNamespace(
        learnerId: String,
        conversationIdPrefix: String,
    ): com.tingyun.smartmistakebook.core.model.TutorConversation? {
        requireOpaque(learnerId, "learnerId")
        requireOpaque(conversationIdPrefix, "conversationIdPrefix")
        return database.tutorLearningMemoryDao()
            .latestActiveConversationInNamespace(learnerId, conversationIdPrefix)
            ?.let { entity ->
                com.tingyun.smartmistakebook.core.model.TutorConversation(
                    conversationId = entity.conversationId,
                    learnerScopeId = entity.learnerId,
                    generation = entity.generation,
                    status = com.tingyun.smartmistakebook.core.model.TutorConversationStatus
                        .valueOf(entity.status),
                    createdAtEpochMillis = entity.createdAtEpochMillis,
                    archivedAtEpochMillis = entity.archivedAtEpochMillis,
                    stateVersion = entity.stateVersion,
                )
            }
    }

    override suspend fun archiveTutorConversation(
        command: ArchiveTutorConversationCommand,
    ) = database.tutorLearningMemoryDao().archiveConversation(
        command,
        trustedClockEpochMillis(),
    )

    override suspend fun allocateTutorTurn(
        command: AllocateTutorTurnCommand,
    ) = database.tutorLearningMemoryDao().allocateTurn(
        command,
        trustedClockEpochMillis(),
    )

    override suspend fun openTutorTurn(
        learnerId: String,
        turnReceiptId: String,
    ): TutorTurnReadResult = database.tutorLearningMemoryDao()
        .openTurn(learnerId, turnReceiptId)
        ?.let { entity ->
            TutorTurnReadResult.Found(
                com.tingyun.smartmistakebook.core.model.TutorTurnReceipt(
                    turnReceiptId = entity.turnReceiptId,
                    conversationId = entity.conversationId,
                    conversationGeneration = entity.conversationGeneration,
                    conversationStateVersion = entity.conversationStateVersion,
                    turnOrdinal = entity.turnOrdinal,
                    subject = com.tingyun.smartmistakebook.core.model.SubjectKind
                        .valueOf(entity.subject),
                    problemAnchorId = entity.problemAnchorId,
                    requestVersion = entity.requestVersion,
                    modeVersion = entity.modeVersion,
                    explanationMode = com.tingyun.smartmistakebook.core.model.TutorExplanationMode
                        .valueOf(entity.explanationMode),
                    directiveFingerprint = entity.directiveFingerprint,
                    studentMessageFingerprint = entity.studentMessageFingerprint,
                    studentMessageSummary = entity.studentMessageSummary,
                    occurredAtEpochMillis = entity.occurredAtEpochMillis,
                ),
            )
        }
        ?: TutorTurnReadResult.NotFound

    override suspend fun openTutorEvidenceRequest(
        learnerId: String,
        evidenceRequestId: String,
    ): com.tingyun.smartmistakebook.core.model.TutorEvidenceRequest? {
        requireOpaque(learnerId, "learnerId")
        requireOpaque(evidenceRequestId, "evidenceRequestId")
        val request = database.tutorLearningMemoryDao()
            .openEvidenceRequest(learnerId, evidenceRequestId)
            ?.toModel()
            ?: return null
        val session = database.tutorLearningEvidenceSessionDao()
            .openForLearner(learnerId, evidenceRequestId)
        return session?.let { request.withLearningEvidenceSession(it) } ?: request
    }

    override suspend fun prepareTutorEvidenceRequest(
        command: PrepareTutorEvidenceRequestCommand,
    ) = database.tutorLearningMemoryDao().prepareEvidence(
        command,
        trustedClockEpochMillis(),
    )

    override suspend fun finalizeTutorEvidenceRequest(
        command: FinalizeTutorEvidenceRequestCommand,
    ) = database.tutorLearningMemoryDao().finalizeEvidence(
        command,
        trustedClockEpochMillis(),
    )

    override fun observeMistakes(): Flow<List<MistakeRecord>> =
        database.problemDao().observeActiveMistakes().map { rows -> rows.map(MistakeRow::toRecord) }

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

    override fun observeTutorVisualTargetEvidence(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidenceRecord>> {
        require(sessionId.isNotBlank())
        return database.tutorInteractionDao().observeVisualEvidence(sessionId)
    }

    override fun observePendingCaptureDrafts(): Flow<List<PendingCaptureDraftRecord>> =
        database.invalidationTracker.createFlow(*PENDING_CAPTURE_TABLES).mapLatest {
            loadPendingCaptureBatch()
        }

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

    override suspend fun readSubjectKnowledgeNodes(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..256) { "knowledge-node limit is outside the supported range" }
        return database.legacyKnowledgeCatalogDao()
            .readSubjectKnowledgeNodes(subject, limit)
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    override suspend fun readSubjectKnowledgeRecallCandidates(
        subject: String,
        searchFeatures: Set<String>,
        limit: Int,
    ): List<KnowledgeNodeSeedRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..MAX_KNOWLEDGE_RECALL_CANDIDATES) {
            "knowledge recall candidate limit is outside the supported range"
        }
        require(searchFeatures.size <= KnowledgeSearchFeatureExtractor.MAX_QUERY_FEATURES) {
            "knowledge recall query has too many search features"
        }
        if (searchFeatures.isEmpty()) {
            return readSubjectKnowledgeNodes(subject, limit.coerceAtMost(256))
        }
        ensureKnowledgeSearchIndex(subject)
        val dao = database.legacyKnowledgeCatalogDao()
        val matched = dao.searchSubjectKnowledgeRecallCandidates(subject, searchFeatures, limit)
        val matchedIds = matched.mapTo(hashSetOf(), KnowledgeNodeEntity::knowledgeNodeId)
        val parents = dao.readKnowledgeNodesByIds(
            matched.mapNotNullTo(hashSetOf(), KnowledgeNodeEntity::parentKnowledgeNodeId) - matchedIds,
        )
        return (parents + matched)
            .distinctBy(KnowledgeNodeEntity::knowledgeNodeId)
            .map(KnowledgeNodeEntity::toSeedRecord)
    }

    private suspend fun ensureKnowledgeSearchIndex(subject: String) =
        knowledgeSupport.ensureKnowledgeSearchIndex(subject)

    override suspend fun readKnowledgeNodesByIds(ids: Set<String>): List<KnowledgeNodeSeedRecord> =
        knowledgeSupport.readKnowledgeNodesByIds(ids)

    override suspend fun readKnowledgeSourcesByIds(ids: Set<String>): List<KnowledgeSourceSeedRecord> =
        knowledgeSupport.readKnowledgeSourcesByIds(ids)

    private suspend fun readAppliedKnowledgeResearchResolutions(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> =
        knowledgeSupport.readAppliedKnowledgeResearchResolutions(command)

    private suspend fun applyReviewedKnowledgePackInTransaction(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> =
        knowledgeSupport.applyReviewedKnowledgePackInTransaction(command)

    private suspend fun readKnowledgeBaseDependencies(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ): KnowledgeBaseDependencies =
        knowledgeSupport.readKnowledgeBaseDependencies(sources, nodes, bindings)

    override suspend fun readSubjectKnowledgeNodeRelations(
        subject: String,
        limit: Int,
    ): List<KnowledgeNodeRelationRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(limit in 1..16_384) { "knowledge relation limit is outside the supported range" }
        return database.knowledgeNodeRelationDao().readBySubject(subject, limit)
            .map(KnowledgeNodeRelationEntity::toRecord)
    }

    override suspend fun readKnowledgeNodeRelationsForDependents(
        subject: String,
        dependentKnowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeRelationRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(dependentKnowledgeNodeIds.size <= 256) {
            "too many dependent knowledge nodes were requested"
        }
        if (dependentKnowledgeNodeIds.isEmpty()) return emptyList()
        return database.knowledgeNodeRelationDao()
            .readForDependents(subject, dependentKnowledgeNodeIds)
            .map(KnowledgeNodeRelationEntity::toRecord)
    }

    override suspend fun readKnowledgeTeachingMaterialsForNodes(
        subject: String,
        knowledgeNodeIds: Set<String>,
        limit: Int,
    ): List<KnowledgeTeachingMaterialRecord> {
        require(subject.isNotBlank()) { "subject must not be blank" }
        require(knowledgeNodeIds.size <= 256) {
            "too many knowledge nodes were requested for teaching context"
        }
        require(limit in 1..64) { "teaching-material limit is outside the supported range" }
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        return database.knowledgeTeachingMaterialDao()
            .readForKnowledgeNodes(subject, knowledgeNodeIds, limit)
            .map(KnowledgeTeachingMaterialEntity::toRecord)
    }

    override suspend fun readKnowledgeTeachingMaterialsByIds(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialRecord> {
        if (materialIds.isEmpty()) return emptyList()
        return materialIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                database.knowledgeTeachingMaterialDao().readByIds(ids.toSet())
            }
            .map(KnowledgeTeachingMaterialEntity::toRecord)
    }

    override suspend fun readKnowledgeTeachingMaterialNodeBindings(
        materialIds: Set<String>,
    ): List<KnowledgeTeachingMaterialNodeBindingRecord> {
        if (materialIds.isEmpty()) return emptyList()
        return materialIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { ids ->
                database.knowledgeTeachingMaterialDao().readBindingsForMaterials(ids.toSet())
            }
            .map(KnowledgeTeachingMaterialNodeBindingEntity::toRecord)
    }

    override suspend fun importKnowledgeNodeRelations(relations: List<KnowledgeNodeRelationRecord>) {
        if (relations.isEmpty()) return
        database.withWriteTransaction {
            val nodeIds = relations.flatMapTo(mutableSetOf()) {
                listOf(it.prerequisiteKnowledgeNodeId, it.dependentKnowledgeNodeId)
            }
            val sourceIds = relations.mapTo(mutableSetOf(), KnowledgeNodeRelationRecord::sourceId)
            val subjects = relations.mapTo(mutableSetOf(), KnowledgeNodeRelationRecord::subject)
            val existingRelations = subjects.flatMap { subject ->
                require(database.knowledgeNodeRelationDao().countBySubject(subject) <= 16_384) {
                    "knowledge relation graph exceeds the supported validation budget"
                }
                database.knowledgeNodeRelationDao().readBySubject(subject, 16_384)
            }.map(KnowledgeNodeRelationEntity::toRecord)
            KnowledgeNodeRelationContract.validate(
                incoming = relations,
                nodes = readKnowledgeNodesByIds(nodeIds),
                sources = readKnowledgeSourcesByIds(sourceIds),
                existing = existingRelations,
            )
            database.knowledgeNodeRelationDao().importAll(
                relations.map(KnowledgeNodeRelationRecord::toEntity),
            )
        }
    }

    override suspend fun importKnowledgeTeachingMaterials(
        materials: List<KnowledgeTeachingMaterialRecord>,
        bindings: List<KnowledgeTeachingMaterialNodeBindingRecord>,
        sources: List<KnowledgeSourceSeedRecord>,
    ) {
        if (materials.isEmpty() && bindings.isEmpty() && sources.isEmpty()) return
        database.withWriteTransaction {
            KnowledgeBaseImportContract.validateSourcesOnly(sources)
            val nodes = readKnowledgeNodesByIds(
                bindings.mapTo(hashSetOf(), KnowledgeTeachingMaterialNodeBindingRecord::knowledgeNodeId),
            )
            val requiredSourceIds = materials.mapTo(hashSetOf(), KnowledgeTeachingMaterialRecord::sourceId)
                .apply {
                    addAll(sources.map(KnowledgeSourceSeedRecord::sourceId))
                }
            val existingSources = readKnowledgeSourcesByIds(requiredSourceIds)
            val existingSourcesById = existingSources.associateBy(KnowledgeSourceSeedRecord::sourceId)
            sources.forEach { source ->
                existingSourcesById[source.sourceId]?.let { existing ->
                    if (existing != source) {
                        throw ImmutablePayloadConflictException(
                            entityType = "knowledgeSource",
                            entityId = source.sourceId,
                        )
                    }
                }
            }
            val sourcesToInsert = sources.filterNot { source ->
                existingSourcesById.containsKey(source.sourceId)
            }
            val validatedSources = (
                existingSources + sourcesToInsert
                ).distinctBy(KnowledgeSourceSeedRecord::sourceId)
            KnowledgeTeachingMaterialContract.validate(
                materials = materials,
                bindings = bindings,
                nodes = nodes,
                sources = validatedSources,
            )
            if (sourcesToInsert.isNotEmpty()) {
                database.legacyKnowledgeCatalogDao().insertKnowledgeSources(
                    sourcesToInsert.map(KnowledgeSourceSeedRecord::toEntity),
                )
            }
            database.knowledgeTeachingMaterialDao().importAll(
                materials = materials.map(KnowledgeTeachingMaterialRecord::toEntity),
                bindings = bindings.map(KnowledgeTeachingMaterialNodeBindingRecord::toEntity),
            )
        }
    }

    override suspend fun readKnowledgeNodeSourceBindings(
        knowledgeNodeIds: Set<String>,
    ): List<KnowledgeNodeSourceBindingSeedRecord> {
        if (knowledgeNodeIds.isEmpty()) return emptyList()
        return knowledgeNodeIds.chunked(KNOWLEDGE_NODE_QUERY_CHUNK_SIZE)
            .flatMap { chunk ->
                database.legacyKnowledgeCatalogDao().readKnowledgeNodeSourceBindings(chunk.toSet())
            }
            .map(KnowledgeNodeSourceBindingEntity::toSeedRecord)
    }

    override suspend fun importKnowledgeBase(
        sources: List<KnowledgeSourceSeedRecord>,
        nodes: List<KnowledgeNodeSeedRecord>,
        bindings: List<KnowledgeNodeSourceBindingSeedRecord>,
    ) {
        val dependencies = readKnowledgeBaseDependencies(sources, nodes, bindings)
        KnowledgeBaseImportContract.validate(
            sources = sources,
            nodes = nodes,
            bindings = bindings,
            existingSources = dependencies.sources,
            existingParentNodes = dependencies.parentNodes,
        )
        database.legacyKnowledgeCatalogDao().importKnowledgeBase(
            sources = sources.map(KnowledgeSourceSeedRecord::toEntity),
            nodes = nodes.map(KnowledgeNodeSeedRecord::toEntity),
            bindings = bindings.map(KnowledgeNodeSourceBindingSeedRecord::toEntity),
            searchFeatures = nodes.flatMap(KnowledgeNodeSeedRecord::toSearchFeatures),
        )
    }

    override suspend fun applyReviewedKnowledgePack(
        command: ApplyReviewedKnowledgePackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = database.withWriteTransaction {
        applyReviewedKnowledgePackInTransaction(command)
    }

    override suspend fun applyApprovedKnowledgeResearchPack(
        command: ApplyApprovedKnowledgeResearchPackCommand,
    ): List<KnowledgeGroundingResolutionRecord> = database.withWriteTransaction {
        val review = knowledgeResearchReviewStore.read(command.reviewBundleId)
            ?: throw DatabaseContractViolationException(
                "Knowledge research review bundle does not exist",
            )
        val packFingerprint = ApprovedKnowledgeResearchPackContract.validate(review, command)
        if (review.status == StudyDbValue.KnowledgeResearchReviewStatus.APPLIED) {
            if (
                review.appliedPackFingerprint != packFingerprint ||
                review.appliedAtEpochMillis != command.appliedAtEpochMillis
            ) {
                throw ImmutablePayloadConflictException(
                    entityType = "approved knowledge research pack",
                    entityId = command.reviewBundleId,
                )
            }
            return@withWriteTransaction readAppliedKnowledgeResearchResolutions(command)
        }
        val resolutions = applyReviewedKnowledgePackInTransaction(command.pack)
        knowledgeResearchReviewStore.markApplied(
            bundleId = command.reviewBundleId,
            packFingerprint = packFingerprint,
            appliedAtEpochMillis = command.appliedAtEpochMillis,
        )
        resolutions
    }

    override fun observePendingKnowledgeGroundingRequests(
        limit: Int,
    ): Flow<List<KnowledgeGroundingRequestRecord>> {
        require(limit in 1..512) { "Knowledge-grounding queue limit must be in 1..512" }
        return database.knowledgeGroundingDao().observePending(limit).map { requests ->
            requests.map(KnowledgeGroundingRequestEntity::toRecord)
        }
    }

    override fun observePendingKnowledgeGroundingSummaries(
        limit: Int,
    ): Flow<List<KnowledgeGroundingSummaryRecord>> {
        require(limit in 1..256) { "Knowledge-grounding summary limit must be in 1..256" }
        return database.knowledgeGroundingDao().observePendingSummaries(limit).map { summaries ->
            summaries.map(KnowledgeGroundingSummaryRow::toRecord)
        }
    }

    override fun observeReviewedKnowledgeCoverage(): Flow<List<ReviewedKnowledgeCoverageRecord>> =
        database.legacyKnowledgeCatalogDao().observeReviewedKnowledgeCoverage().map { rows ->
            rows.map(ReviewedKnowledgeCoverageRow::toRecord)
        }

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
    ) {
        KnowledgeGroundingRequestContract.validate(requests)
        database.knowledgeGroundingDao().recordAll(
            requests.map(KnowledgeGroundingRequestRecord::toEntity),
        )
    }

    override suspend fun resolveKnowledgeGrounding(
        command: ResolveKnowledgeGroundingCommand,
    ): KnowledgeGroundingResolutionRecord {
        val resolutionId = KnowledgeGroundingResolutionContract.validate(command)
        return database.knowledgeGroundingDao()
            .resolve(command, resolutionId)
            .toRecord()
    }

    override suspend fun readKnowledgeGroundingResolution(
        groundingKey: String,
    ): KnowledgeGroundingResolutionRecord? =
        database.knowledgeGroundingDao().readResolution(groundingKey)?.toRecord()

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

    override suspend fun readPendingCaptureDraft(draftId: String): PendingCaptureDraftRecord? {
        require(draftId.isNotBlank()) { "draftId must not be blank" }
        val index = database.pendingCaptureDao().readPending(draftId) ?: return null
        return loadPendingCapture(index)
    }

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
                    boundaryClaimedAtEpochMillis =
                        checkNotNull(primaryPage.boundaryClaimedAtEpochMillis),
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
    ): CommitProblemDraftResult = database.withWriteTransaction {
        val result = database.problemDraftTransactionDao().commit(command)
        pendingCaptureSupport.prepareLegacyStudentSaveHandoff(command, result.receipt)
        result
    }

    override suspend fun mergeProblemDraftSourceBundle(
        command: MergeProblemDraftSourceBundleCommand,
    ): CaptureDraftMergeSessionReceiptRecord = database.withWriteTransaction {
        val receiptDao = database.captureDraftMergeSessionReceiptDao()
        receiptDao.read(command.batchJobId, command.batchPageIndex)?.let { existing ->
            if (existing.receiptReference != command.receiptReference) {
                throw ImmutablePayloadConflictException(
                    entityType = "capture_draft_merge_session_receipt",
                    entityId = "${command.batchJobId}:${command.batchPageIndex}",
                )
            }
            return@withWriteTransaction existing.toRecord()
        }
        val draftDao = database.problemDraftTransactionDao()
        val primary = draftDao.read(command.primaryDraftId)
            ?: throw ImmutablePayloadConflictException(
                entityType = "problem_draft",
                entityId = command.primaryDraftId,
            )
        val following = draftDao.read(command.followingDraftId)
            ?: throw ImmutablePayloadConflictException(
                entityType = "problem_draft",
                entityId = command.followingDraftId,
            )
        require(primary.currentRevision.revisionNumber == command.expectedPrimaryRevisionNumber) {
            "Primary draft revision does not match the merge command"
        }
        require(following.currentRevision.revisionNumber == command.expectedFollowingRevisionNumber) {
            "Following draft revision does not match the merge command"
        }
        require(
            primary.captureAssetOrderFingerprint() == command.expectedPrimaryAssetOrderFingerprint,
        ) { "Primary draft asset order does not match the merge command" }
        require(
            following.captureAssetOrderFingerprint() == command.expectedFollowingAssetOrderFingerprint,
        ) { "Following draft asset order does not match the merge command" }
        require(
            captureDraftSessionVersion(
                revisionNumber = primary.currentRevision.revisionNumber,
                sourceAssetCount = primary.sourceAssets.size,
            ) == command.expectedPrimarySessionVersion,
        ) { "Primary draft session version does not match the merge command" }
        require(
            captureDraftSessionVersion(
                revisionNumber = following.currentRevision.revisionNumber,
                sourceAssetCount = following.sourceAssets.size,
            ) == command.expectedFollowingSessionVersion,
        ) { "Following draft session version does not match the merge command" }
        require(
            primary.sourceAssets.map { it.sourceAsset.sourceAssetId } ==
                command.expectedPrimarySourceAssetIds,
        ) { "Primary draft source assets do not match the merge command" }
        require(
            following.sourceAssets.map { it.sourceAsset.sourceAssetId } ==
                command.expectedFollowingSourceAssetIds,
        ) { "Following draft source assets do not match the merge command" }
        draftDao.mergeSourceBundle(
            primaryDraftId = command.primaryDraftId,
            expectedPrimaryRevisionNumber = command.expectedPrimaryRevisionNumber,
            followingDraftId = command.followingDraftId,
            expectedFollowingRevisionNumber = command.expectedFollowingRevisionNumber,
            mergedAtEpochMillis = command.mergedAtEpochMillis,
        )
        val mergedSourceAssetCount = primary.sourceAssets.size + following.sourceAssets.size
        require(
            captureDraftSessionVersion(
                revisionNumber = command.expectedPrimaryRevisionNumber,
                sourceAssetCount = mergedSourceAssetCount,
            ) == command.mergedSessionVersion,
        ) { "Merged session version does not match the merge command" }
        val receipt = com.tingyun.smartmistakebook.core.database.entity
            .CaptureDraftMergeSessionReceiptEntity(
                receiptReference = command.receiptReference,
                batchJobId = command.batchJobId,
                batchPageIndex = command.batchPageIndex,
                primaryDraftId = command.primaryDraftId,
                followingDraftId = command.followingDraftId,
                mergedDraftId = command.primaryDraftId,
                assetOrderFingerprint = command.mergedAssetOrderFingerprint,
                sessionVersion = command.mergedSessionVersion,
                sourceAssetCount = mergedSourceAssetCount,
                requestCanonicalFingerprint = command.requestCanonicalFingerprint,
                mergedAtEpochMillis = command.mergedAtEpochMillis,
            )
        check(receiptDao.insert(receipt) != -1L) {
            "Merge receipt was not persisted"
        }
        checkNotNull(
            receiptDao.read(command.batchJobId, command.batchPageIndex),
        ).toRecord()
    }

    override suspend fun readProblemDraftMergeSessionReceipt(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftMergeSessionReceiptRecord? =
        database.captureDraftMergeSessionReceiptDao()
            .read(batchJobId, batchPageIndex)
            ?.toRecord()

    override suspend fun readProblemOrganizationWork(
        workId: String,
    ): ProblemOrganizationWorkRecord? =
        problemOrganizationWork.readProblemOrganizationWork(workId)

    override suspend fun readProblemOrganizationWorkByCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemOrganizationWorkRecord? =
        problemOrganizationWork.readProblemOrganizationWorkByCommitReceipt(commitReceiptCommandId)

    override suspend fun readProblemOrganizationWorkByRequestId(
        requestId: String,
    ): ProblemOrganizationWorkRecord? =
        problemOrganizationWork.readProblemOrganizationWorkByRequestId(requestId)

    override suspend fun readProblemOrganizationWorkCommitReceipt(
        commitReceiptCommandId: String,
    ): ProblemDraftCommitReceipt? =
        problemOrganizationWork.readProblemOrganizationWorkCommitReceipt(commitReceiptCommandId)

    override suspend fun readWaitingProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? =
        problemOrganizationWork.readWaitingProblemOrganizationWork(
            problemId = problemId,
            problemRevisionId = problemRevisionId,
            errorBookEntryId = errorBookEntryId,
        )

    override suspend fun readLatestProblemOrganizationWork(
        problemId: String,
        problemRevisionId: String,
        errorBookEntryId: String,
    ): ProblemOrganizationWorkPreparationRecord? =
        problemOrganizationWork.readLatestProblemOrganizationWork(
            problemId = problemId,
            problemRevisionId = problemRevisionId,
            errorBookEntryId = errorBookEntryId,
        )

    override suspend fun claimNextProblemOrganizationWork(
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? =
        problemOrganizationWork.claimNextProblemOrganizationWork(
            leaseOwner = leaseOwner,
            nowEpochMillis = nowEpochMillis,
            leaseDurationMillis = leaseDurationMillis,
        )

    override suspend fun claimProblemOrganizationWork(
        workId: String,
        leaseOwner: String,
        nowEpochMillis: Long,
        leaseDurationMillis: Long,
    ): ProblemOrganizationWorkRecord? =
        problemOrganizationWork.claimProblemOrganizationWork(
            workId = workId,
            leaseOwner = leaseOwner,
            nowEpochMillis = nowEpochMillis,
            leaseDurationMillis = leaseDurationMillis,
        )

    override suspend fun readSchedulableProblemOrganizationWorks(
        nowEpochMillis: Long,
        limit: Int,
    ): List<ProblemOrganizationWorkRecord> =
        problemOrganizationWork.readSchedulableProblemOrganizationWorks(
            nowEpochMillis = nowEpochMillis,
            limit = limit,
        )

    override fun observeSchedulableProblemOrganizationWorks():
        Flow<List<ProblemOrganizationWorkRecord>> =
        problemOrganizationWork.observeSchedulableProblemOrganizationWorks()

    override suspend fun readRunningProblemOrganizationWorks(
        limit: Int,
        afterLeaseExpiresAtEpochMillis: Long?,
        afterUpdatedAtEpochMillis: Long?,
        afterWorkId: String?,
    ): List<ProblemOrganizationWorkRecord> =
        problemOrganizationWork.readRunningProblemOrganizationWorks(
            limit = limit,
            afterLeaseExpiresAtEpochMillis = afterLeaseExpiresAtEpochMillis,
            afterUpdatedAtEpochMillis = afterUpdatedAtEpochMillis,
            afterWorkId = afterWorkId,
        )

    override suspend fun authorizeProblemOrganizationWork(
        command: AuthorizeProblemOrganizationWorkCommand,
    ): Boolean =
        problemOrganizationWork.authorizeProblemOrganizationWork(command)

    override suspend fun reauthorizeProblemOrganizationWork(
        command: ReauthorizeProblemOrganizationWorkCommand,
    ): ReauthorizeProblemOrganizationWorkResult =
        problemOrganizationWork.reauthorizeProblemOrganizationWork(command)

    override suspend fun markProblemOrganizationWorkWaitingAuthorization(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean =
        problemOrganizationWork.markProblemOrganizationWorkWaitingAuthorization(command)

    override suspend fun retryProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean =
        problemOrganizationWork.retryProblemOrganizationWork(command)

    override suspend fun failProblemOrganizationWorkPermanently(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean =
        problemOrganizationWork.failProblemOrganizationWorkPermanently(command)

    override suspend fun completeProblemOrganizationWork(
        command: ProblemOrganizationWorkTransitionCommand,
    ): Boolean =
        problemOrganizationWork.completeProblemOrganizationWork(command)

    override suspend fun confirmAndCompleteProblemOrganizationWork(
        command: CompleteProblemOrganizationWorkAtomicallyCommand,
    ): ConfirmAndCompleteProblemOrganizationWorkResult =
        problemOrganizationWork.confirmAndCompleteProblemOrganizationWork(command)

    private fun trustedClockEpochMillis(): Long =
        clock().also { require(it >= 0) { "clock must not be negative" } }

    internal fun trustedBarrierClockEpochMillis(): Long = trustedClockEpochMillis()

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

    override suspend fun recordTutorChoiceUnlessCancelled(
        command: PersistTutorChoiceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorTurnResponseRecord? =
        database.tutorInteractionDao().recordChoiceUnlessCancelled(command, cancellation)

    override suspend fun discardTutorChoice(
        command: PersistTutorChoiceCommand,
    ): Boolean = database.tutorInteractionDao().discardChoice(command)

    override suspend fun recordTutorVisualTargetEvidence(
        command: PersistTutorVisualTargetEvidenceCommand,
    ): TutorVisualTargetEvidenceRecord =
        database.tutorInteractionDao().recordVisualTargetEvidence(command)

    override suspend fun recordTutorVisualTargetEvidenceUnlessCancelled(
        command: PersistTutorVisualTargetEvidenceCommand,
        cancellation: PersistTutorEvidenceCancellationCommand,
    ): TutorVisualTargetEvidenceRecord? =
        database.tutorInteractionDao()
            .recordVisualTargetEvidenceUnlessCancelled(command, cancellation)

    override suspend fun recordTutorEvidenceCancellation(
        command: PersistTutorEvidenceCancellationCommand,
    ) {
        database.tutorInteractionDao().recordEvidenceCancellation(command)
    }

    override suspend fun isTutorEvidenceCancelled(
        command: PersistTutorEvidenceCancellationCommand,
    ): Boolean = database.tutorInteractionDao().isEvidenceCancelled(command)

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

    private suspend fun loadPendingCaptureBatch(): List<PendingCaptureDraftRecord> =
        pendingCaptureSupport.loadPendingCaptureBatch()

    private suspend fun loadPendingCapture(
        index: PendingCaptureIndexRow,
    ): PendingCaptureDraftRecord? =
        pendingCaptureSupport.loadPendingCapture(index)

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

    override suspend fun submitLearningObservationCandidate(
        candidate: com.tingyun.smartmistakebook.core.model.LearningObservationCandidate,
    ): LearningObservationCandidateWriteResult =
        database.learningObservationDao().submitCandidate(candidate)

    override suspend fun registerLearningObservationSourceAuthority(
        authority: LearningObservationSourceAuthorityRecord,
    ): LearningObservationSourceAuthorityWriteResult =
        database.learningObservationDao().registerSourceAuthority(authority)

    override suspend fun readLearningObservationSourceAuthority(
        learnerId: String,
        source: com.tingyun.smartmistakebook.core.model.LearningObservationSource,
        sourceReferenceId: String,
    ): LearningObservationSourceAuthorityRecord? =
        database.learningObservationDao().readSourceAuthority(
            learnerId,
            source,
            sourceReferenceId,
        )

    override suspend fun compareAndSetLearningObservationCandidateStatus(
        command: LearningObservationCandidateStatusChangeCommand,
    ): LearningObservationCandidateStatusCasResult =
        database.learningObservationDao().compareAndSetCandidateStatus(command)

    override suspend fun materializeLearningObservation(
        command: MaterializeLearningObservationCommand,
    ): LearningObservationMaterializationResult =
        database.learningObservationDao().materialize(command)

    override suspend fun readLearningObservationCandidate(
        candidateId: String,
    ): com.tingyun.smartmistakebook.core.model.LearningObservationCandidate? =
        database.learningObservationDao().readCandidate(candidateId)

    override suspend fun readAttributedLearningObservation(
        eventId: String,
    ): com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent? =
        database.learningObservationDao().readEvent(eventId)

    override suspend fun readLearningEvidenceReviewCase(
        reviewCaseId: String,
    ): com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewCase? =
        database.learningObservationDao().readReviewCase(reviewCaseId)

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

    override suspend fun cancelTutorEvidenceRequest(
        command: CancelTutorEvidenceRequestCommand,
    ): TutorEvidenceCancellationResult {
        val result = database.tutorLearningMemoryDao().finalizeEvidence(
            command = FinalizeTutorEvidenceRequestCommand(
                learnerId = command.learnerId,
                conversationId = command.conversationId,
                conversationGeneration = command.conversationGeneration,
                conversationStateVersion = command.conversationStateVersion,
                turnReceiptId = command.turnReceiptId,
                turnOrdinal = command.turnOrdinal,
                subject = command.subject,
                problemAnchorId = command.problemAnchorId,
                evidenceRequestId = command.evidenceRequestId,
                expectedEvidenceStateVersion = command.expectedEvidenceStateVersion,
                kind = command.kind,
                requestVersion = command.requestVersion,
                explanationMode = command.explanationMode,
                modeVersion = command.modeVersion,
                directiveFingerprint = command.directiveFingerprint,
                terminalStatus = com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus.CANCELLED,
                idempotencyKey = command.idempotencyKey,
                payloadFingerprint = command.payloadFingerprint,
                submission = null,
            ),
            nowEpochMillis = trustedClockEpochMillis(),
        )
        return TutorEvidenceCancellationResult(
            replayed = result.replayed,
            request = result.request,
        )
    }

    override suspend fun beginTutorLearningEvidenceSessionIntent(
        command: BeginTutorLearningEvidenceSessionIntentCommand,
    ): TutorLearningEvidenceSessionRecord =
        database.tutorLearningEvidenceSessionDao().begin(command, trustedClockEpochMillis())

    override suspend fun acknowledgeTutorLearningEvidenceSession(
        command: AcknowledgeTutorLearningEvidenceSessionCommand,
    ): TutorLearningEvidenceSessionAcknowledgeDatabaseResult =
        database.tutorLearningEvidenceSessionDao().acknowledge(command, trustedClockEpochMillis())

    override suspend fun importOrReuseBatchDraft(
        command: ImportOrReuseBatchCaptureDraftCommand,
    ): CaptureDraftBatchImportReceipt = batchCaptureImports.importOrReuse(command)

    override suspend fun readBatchCaptureDraftReceipt(
        batchJobId: String,
        batchPageIndex: Int,
    ): CaptureDraftBatchImportReceipt? = batchCaptureImports.readExact(batchJobId, batchPageIndex)

    override suspend fun readBatchCaptureDraftReceipts(
        batchJobId: String,
        afterReceiptSequenceExclusive: Long,
        limit: Int,
    ): CaptureDraftBatchImportReceiptPage = batchCaptureImports.readPage(
        batchJobId = batchJobId,
        afterReceiptSequenceExclusive = afterReceiptSequenceExclusive,
        limit = limit,
    )

    override suspend fun appendLegacyAuthorityCutoverStageReceipt(
        command: AppendLegacyAuthorityCutoverStageCommand,
    ): LegacyAuthorityCutoverJournalWriteResult =
        legacyAuthoritySupport.appendLegacyAuthorityCutoverStageReceipt(command)

    override suspend fun readLegacyAuthorityCutoverStageReceipts():
        List<LegacyAuthorityCutoverStageReceipt> =
        legacyAuthoritySupport.readLegacyAuthorityCutoverStageReceipts()

    override suspend fun prepareCaptureStudentSaveHandoff(
        command: PrepareCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult =
        legacyAuthoritySupport.prepareCaptureStudentSaveHandoff(command)

    override suspend fun finalizeCaptureStudentSaveHandoff(
        command: FinalizeCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult =
        legacyAuthoritySupport.finalizeCaptureStudentSaveHandoff(command)

    override suspend fun readPendingCaptureStudentSaveHandoffs(
        query: ReadPendingCaptureStudentSaveHandoffsQuery,
    ): List<CaptureStudentSaveHandoffRecord> =
        legacyAuthoritySupport.readPendingCaptureStudentSaveHandoffs(query)

    override suspend fun acknowledgeStudentOwnedCaptureSession(
        command: AcknowledgeStudentOwnedCaptureSessionCommand,
    ): StudentOwnedCaptureSessionAckResult =
        legacyAuthoritySupport.acknowledgeStudentOwnedCaptureSession(command)

    override suspend fun readExactLegacyCaptureStudentDocument(
        query: ExactLegacyCaptureStudentDocumentQuery,
    ): LegacyStudentDocumentMigrationRecord? =
        legacyAuthoritySupport.readExactLegacyCaptureStudentDocument(query)

    override suspend fun readLegacyAuthorityMigrationSnapshot(
        learnerId: String,
    ): LegacyAuthorityMigrationSnapshot =
        legacyAuthoritySupport.readLegacyAuthorityMigrationSnapshot(learnerId)

    override suspend fun readLegacyStudentDocumentMigrationPage(
        afterExclusive: LegacyStudentDocumentMigrationCursor?,
        limit: Int,
    ): LegacyStudentDocumentMigrationPage =
        legacyAuthoritySupport.readLegacyStudentDocumentMigrationPage(afterExclusive, limit)

    override suspend fun readLegacyMasteryFactMigrationPage(
        learnerId: String,
        afterExclusive: LegacyMasteryFactMigrationCursor?,
        limit: Int,
    ): LegacyMasteryFactMigrationPage =
        legacyAuthoritySupport.readLegacyMasteryFactMigrationPage(learnerId, afterExclusive, limit)

    override suspend fun activateCurrentTutorInteraction(
        command: ActivateCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionActivationResult =
        currentTutorInteractions.activateCurrentTutorInteraction(command)

    override suspend fun readCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): CurrentTutorInteractionBundle? =
        currentTutorInteractions.readCurrentTutorInteraction(learnerId, conversationId)

    override fun observeCurrentTutorInteraction(
        learnerId: String,
        conversationId: String,
    ): Flow<CurrentTutorInteractionBundle?> =
        currentTutorInteractions.observeCurrentTutorInteraction(learnerId, conversationId)

    override fun observeTutorInteractionHistory(
        learnerId: String,
        conversationId: String,
    ): Flow<List<CurrentTutorInteractionEventRecord>> =
        currentTutorInteractions.observeTutorInteractionHistory(learnerId, conversationId)

    override suspend fun readTutorAnswerExposureEvents(
        learnerId: String,
        modelTaskRequestIds: Set<String>,
    ): List<CurrentTutorInteractionEventRecord> =
        currentTutorInteractions.readTutorAnswerExposureEvents(learnerId, modelTaskRequestIds)

    override suspend fun appendCurrentTutorInteraction(
        command: AppendCurrentTutorInteractionCommand,
    ): CurrentTutorInteractionAppendResult =
        currentTutorInteractions.appendCurrentTutorInteraction(command)

    override suspend fun consumeCurrentTutorOpenResponseAuthorization(
        command: ConsumeCurrentTutorOpenResponseAuthorizationCommand,
    ): CurrentTutorOpenResponseAuthorizationConsumeResult =
        currentTutorInteractions.consumeCurrentTutorOpenResponseAuthorization(command)

    override suspend fun persistCurrentTutorSessionPolicy(
        command: PersistCurrentTutorSessionPolicyCommand,
    ): CurrentTutorSessionPolicyWriteResult =
        currentTutorInteractions.persistCurrentTutorSessionPolicy(command)

    override suspend fun readCurrentTutorSessionPolicy(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionPolicyRecord? =
        currentTutorInteractions.readCurrentTutorSessionPolicy(learnerId, sessionId)

    override suspend fun stageCurrentTutorSessionHostWork(
        command: StageCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult =
        currentTutorInteractions.stageCurrentTutorSessionHostWork(command)

    override suspend fun readCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): CurrentTutorSessionHostWorkRecord? =
        currentTutorInteractions.readCurrentTutorSessionHostWork(learnerId, sessionId)

    override fun observeCurrentTutorSessionHostWork(
        learnerId: String,
        sessionId: String,
    ): Flow<CurrentTutorSessionHostWorkRecord?> =
        currentTutorInteractions.observeCurrentTutorSessionHostWork(learnerId, sessionId)

    override suspend fun markCurrentTutorSessionHostWorkActive(
        command: MarkCurrentTutorSessionHostWorkActiveCommand,
    ): CurrentTutorSessionHostWorkWriteResult =
        currentTutorInteractions.markCurrentTutorSessionHostWorkActive(command)

    override suspend fun revokeCurrentTutorSessionHostWork(
        command: RevokeCurrentTutorSessionHostWorkCommand,
    ): CurrentTutorSessionHostWorkWriteResult =
        currentTutorInteractions.revokeCurrentTutorSessionHostWork(command)

    override suspend fun claimCurrentTutorFreeResponseAction(
        command: ClaimCurrentTutorFreeResponseActionCommand,
    ): CurrentTutorFreeResponseActionClaimResult =
        currentTutorInteractions.claimCurrentTutorFreeResponseAction(command)

    override suspend fun readCurrentTutorFreeResponseDispatchState(
        query: CurrentTutorFreeResponseActionClaimQuery,
    ): CurrentTutorFreeResponseDispatchState =
        currentTutorInteractions.readCurrentTutorFreeResponseDispatchState(query)

    override suspend fun acquireCurrentTutorFreeResponseDispatch(
        command: AcquireCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchAcquireResult =
        currentTutorInteractions.acquireCurrentTutorFreeResponseDispatch(command)

    override suspend fun completeCurrentTutorFreeResponseDispatch(
        command: CompleteCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult =
        currentTutorInteractions.completeCurrentTutorFreeResponseDispatch(command)

    override suspend fun releaseCurrentTutorFreeResponseDispatch(
        command: ReleaseCurrentTutorFreeResponseDispatchCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult =
        currentTutorInteractions.releaseCurrentTutorFreeResponseDispatch(command)

    override suspend fun failCurrentTutorFreeResponseDispatchClosed(
        command: FailCurrentTutorFreeResponseDispatchClosedCommand,
    ): CurrentTutorFreeResponseDispatchMutationResult =
        currentTutorInteractions.failCurrentTutorFreeResponseDispatchClosed(command)
    override fun close() {
        currentTutorInteractions.close()
        database.close()
    }
}
