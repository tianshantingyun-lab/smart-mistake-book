package com.tingyun.smartmistakebook.core.data.session.migration

import android.content.Context
import com.tingyun.smartmistakebook.core.data.capture.AndroidBatchImportSourceStaging
import com.tingyun.smartmistakebook.core.data.capture.BatchImportSourceStaging
import com.tingyun.smartmistakebook.core.data.capture.StagedBatchImportSources
import com.tingyun.smartmistakebook.core.database.BatchImportJobRecord
import com.tingyun.smartmistakebook.core.database.BatchImportPageRecord
import com.tingyun.smartmistakebook.core.database.CanonicalSourceAssetRecord
import com.tingyun.smartmistakebook.core.database.CreateBatchImportJobCommand
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LegacyPreCutoverBatchImportDatabasePort
import com.tingyun.smartmistakebook.core.database.ResolveBatchImportBoundaryCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.domain.BatchImportBoundaryStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportJob
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationApproval
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationOffer
import com.tingyun.smartmistakebook.core.domain.BatchImportPage
import com.tingyun.smartmistakebook.core.domain.BatchImportPageStatus
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.BatchImportStatus
import com.tingyun.smartmistakebook.core.domain.CaptureDraftImportRequest
import com.tingyun.smartmistakebook.core.domain.CaptureEntryOrigin
import com.tingyun.smartmistakebook.core.domain.CaptureInputSource
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.CreateBatchImportRequest
import com.tingyun.smartmistakebook.core.domain.CreatePdfImportRequest
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CapturePageRelation
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Migration-only compatibility for legacy batch rows that still share the old Room database.
 *
 * Production batch import is assembled from learner-scoped session and capture capabilities and
 * must never call this implementation as a fallback.
 */
private class LegacyRoomBatchImportRepository(
    private val database: LegacyPreCutoverBatchImportDatabasePort,
    private val capture: CaptureWorkflowRepository,
    private val processingScope: CoroutineScope,
    private val sourceStaging: BatchImportSourceStaging,
    private val modelTasks: ModelTaskRepository = BatchOrganizationUnavailableModelTasks,
) : BatchImportRepository {
    private val processingMutex = Mutex()
    private val organizationMutex = Mutex()

    init {
        processingScope.launch {
            val jobs = database.observeBatchImportJobs().first()
            jobs.asSequence()
                .flatMap { it.pages }
                .filter { page ->
                    page.status == StudyDbValue.BatchImportPageStatus.READY ||
                        page.status == StudyDbValue.BatchImportPageStatus.SKIPPED
                }
                .forEach { page ->
                    if (!database.hasRetainedBatchImportSourceUri(page.sourceUri)) {
                        sourceStaging.delete(page.sourceUri)
                    }
                }
            sourceStaging.reconcileOrphans(
                referencedSourceUris = jobs.flatMap { job ->
                    job.pages.map(BatchImportPageRecord::sourceUri)
                }.toSet(),
                nowEpochMillis = System.currentTimeMillis(),
            )
            jobs.filter { it.status == StudyDbValue.BatchImportStatus.PROCESSING }
                .forEach { process(it.jobId) }
        }
    }

    override fun observeBatchImports(): Flow<List<BatchImportJob>> =
        database.observeBatchImportJobs().map { jobs -> jobs.map(BatchImportJobRecord::toDomain) }
            .flowOn(Dispatchers.IO)

    override suspend fun createBatchImport(request: CreateBatchImportRequest): BatchImportJob =
        createImport(
            requestId = request.requestId,
            requestFingerprint = fingerprint(request.localUris),
            occurredAtEpochMillis = request.occurredAtEpochMillis,
        ) { sourceStaging.stage(request.localUris) }

    override suspend fun createPdfImport(request: CreatePdfImportRequest): BatchImportJob =
        createImport(
            requestId = request.requestId,
            requestFingerprint = digest("PDF\u001F${request.localUri}"),
            occurredAtEpochMillis = request.occurredAtEpochMillis,
        ) { sourceStaging.stagePdf(request.localUri) }

    private suspend fun createImport(
        requestId: String,
        requestFingerprint: String,
        occurredAtEpochMillis: Long,
        stage: () -> StagedBatchImportSources,
    ): BatchImportJob =
        withContext(NonCancellable + Dispatchers.IO) {
            val jobId = stableId("batch", requestId)
            database.readBatchImportJob(jobId)?.let { existing ->
                if (existing.requestFingerprint != requestFingerprint) {
                    throw ImmutablePayloadConflictException("batch_import_request", requestId)
                }
                schedule(existing.jobId)
                return@withContext existing.toDomain()
            }
            val staged = stage()
            val job = try {
                database.createBatchImportJob(
                    CreateBatchImportJobCommand(
                        jobId = jobId,
                        requestId = requestId,
                        requestFingerprint = requestFingerprint,
                        sourceUris = staged.sourceUris,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                )
            } catch (failure: Exception) {
                sourceStaging.delete(staged)
                throw failure
            }
            if (job.pages.map(BatchImportPageRecord::sourceUri) != staged.sourceUris) {
                sourceStaging.delete(staged)
            }
            schedule(job.jobId)
            job.toDomain()
        }

    override suspend fun pauseBatchImport(jobId: String) = withContext(Dispatchers.IO) {
        require(jobId.isNotBlank())
        database.updateBatchImportJobStatus(
            jobId,
            StudyDbValue.BatchImportStatus.PROCESSING,
            StudyDbValue.BatchImportStatus.PAUSED,
            System.currentTimeMillis(),
        )
        Unit
    }

    override suspend fun resumeBatchImport(jobId: String) = withContext(Dispatchers.IO) {
        require(jobId.isNotBlank())
        val job = database.readBatchImportJob(jobId) ?: error("Batch import no longer exists")
        when (job.status) {
            StudyDbValue.BatchImportStatus.PAUSED,
            StudyDbValue.BatchImportStatus.COMPLETED,
            -> database.updateBatchImportJobStatus(
                jobId,
                job.status,
                StudyDbValue.BatchImportStatus.PROCESSING,
                System.currentTimeMillis(),
            )
            StudyDbValue.BatchImportStatus.PROCESSING -> Unit
            else -> error("Unsupported batch import status")
        }
        schedule(jobId)
    }

    override suspend fun retryBatchImportPage(jobId: String, pageIndex: Int) =
        withContext(Dispatchers.IO) {
            require(jobId.isNotBlank())
            require(pageIndex >= 0)
            val now = System.currentTimeMillis()
            if (!database.retryBatchImportPage(jobId, pageIndex, now)) return@withContext
            schedule(jobId)
        }

    override suspend fun skipBatchImportPage(jobId: String, pageIndex: Int) =
        withContext(Dispatchers.IO) {
            require(jobId.isNotBlank())
            require(pageIndex >= 0)
            val now = System.currentTimeMillis()
            if (!database.skipBatchImportPage(jobId, pageIndex, now)) return@withContext
            database.readBatchImportJob(jobId)?.pages
                ?.firstOrNull { it.pageIndex == pageIndex }
                ?.sourceUri
                ?.takeUnless { database.hasRetainedBatchImportSourceUri(it) }
                ?.let(sourceStaging::delete)
            database.finishBatchImportIfSettled(jobId, now)
        }

    override suspend fun prepareOrganization(jobId: String): BatchImportOrganizationOffer =
        withContext(Dispatchers.IO) {
            require(jobId.isNotBlank())
            val job = database.readBatchImportJob(jobId)
                ?: error("Batch import no longer exists")
            require(job.status == StudyDbValue.BatchImportStatus.COMPLETED) {
                "Batch import must finish before its pages can be organized"
            }
            val provider = modelTasks.capabilities()
            require(provider.canOrganizeBatchPages()) {
                "The configured model cannot compare adjacent question pages"
            }
            val disclosedPageIndexes = job.pages.zipWithNext()
                .filter { (page, following) -> page.hasUnresolvedBoundaryWith(following) }
                .flatMap { (page, following) -> listOf(page.pageIndex, following.pageIndex) }
                .toSet()
            require(disclosedPageIndexes.size >= 2) {
                "Batch import has no adjacent saved pages left to organize"
            }
            BatchImportOrganizationOffer(
                jobId = jobId,
                pageCount = disclosedPageIndexes.size,
                provider = provider,
            )
        }

    override suspend fun organizeBatch(approval: BatchImportOrganizationApproval) =
        organizationMutex.withLock {
            withContext(Dispatchers.IO) {
                val provider = modelTasks.capabilities()
                require(provider.matches(approval) && provider.canOrganizeBatchPages()) {
                    "The configured model changed after page organization was approved"
                }
                val initial = database.readBatchImportJob(approval.jobId)
                    ?: error("Batch import no longer exists")
                require(initial.status == StudyDbValue.BatchImportStatus.COMPLETED)
                database.requeueInterruptedBatchImportBoundaries(
                    approval.jobId,
                    System.currentTimeMillis(),
                )

                val refreshed = database.readBatchImportJob(approval.jobId)
                    ?: error("Batch import no longer exists")
                val pageSources = loadBatchPageSources(refreshed)
                unresolvedBoundaryWindows(refreshed).forEach { boundaryIndexes ->
                    val claimed = boundaryIndexes.filter { pageIndex ->
                        database.claimBatchImportBoundary(
                            approval.jobId,
                            pageIndex,
                            System.currentTimeMillis(),
                        )
                    }
                    if (claimed.size != boundaryIndexes.size) {
                        claimed.forEach { pageIndex ->
                            database.failBatchImportBoundary(
                                approval.jobId,
                                pageIndex,
                                System.currentTimeMillis(),
                            )
                        }
                        return@forEach
                    }
                    try {
                        val pageIndexes = boundaryIndexes.first()..(boundaryIndexes.last() + 1)
                        val sources = pageIndexes.map { pageIndex ->
                            checkNotNull(pageSources[pageIndex])
                        }
                        val request = pageRelationRequest(
                            jobId = approval.jobId,
                            boundaryIndexes = boundaryIndexes,
                            sources = sources,
                            provider = provider,
                            approval = approval,
                        )
                        val snapshot = modelTasks.execute(request).last()
                        val assessment = (snapshot.output as? CaptureAssessmentOutput)?.assessment
                        if (
                            snapshot.status != ModelTaskStatus.SUCCEEDED ||
                            assessment == null
                        ) {
                            boundaryIndexes.forEach { pageIndex ->
                                database.failBatchImportBoundary(
                                    approval.jobId,
                                    pageIndex,
                                    System.currentTimeMillis(),
                                )
                            }
                            return@forEach
                        }
                        boundaryIndexes.zip(assessment.followingPageRelations)
                            .forEach { (pageIndex, relation) ->
                                resolveBoundary(
                                    jobId = approval.jobId,
                                    pageIndex = pageIndex,
                                    relation = relation,
                                    assessmentDecision = assessment.decision,
                                )
                            }
                    } catch (cancelled: CancellationException) {
                        boundaryIndexes.forEach { pageIndex ->
                            database.failBatchImportBoundary(
                                approval.jobId,
                                pageIndex,
                                System.currentTimeMillis(),
                            )
                        }
                        throw cancelled
                    } catch (_: Exception) {
                        boundaryIndexes.forEach { pageIndex ->
                            database.failBatchImportBoundary(
                                approval.jobId,
                                pageIndex,
                                System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }
        }

    private fun schedule(jobId: String) {
        processingScope.launch { process(jobId) }
    }

    private suspend fun process(jobId: String) = processingMutex.withLock {
        withContext(Dispatchers.IO) {
            database.requeueInterruptedBatchImportPages(jobId, System.currentTimeMillis())
            while (true) {
                val page = database.claimNextBatchImportPage(jobId, System.currentTimeMillis())
                    ?: break
                val draft = try {
                    capture.importDraft(
                        CaptureDraftImportRequest(
                            requestId = "batch:$jobId:${page.pageIndex}",
                            localUri = page.sourceUri,
                            source = CaptureInputSource.PHOTO_PICKER,
                            origin = CaptureEntryOrigin.LIBRARY,
                            occurredAtEpochMillis = page.createdAtEpochMillis,
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    database.failBatchImportPage(
                        jobId,
                        page.pageIndex,
                        failure.toBatchFailureCode(),
                        System.currentTimeMillis(),
                    )
                    continue
                }
                check(
                    database.completeBatchImportPage(
                        jobId,
                        page.pageIndex,
                        draft.draftId,
                        System.currentTimeMillis(),
                    ),
                ) { "Claimed batch page could not be completed" }
                if (!database.hasRetainedBatchImportSourceUri(page.sourceUri)) {
                    sourceStaging.delete(page.sourceUri)
                }
            }
            database.finishBatchImportIfSettled(jobId, System.currentTimeMillis())
        }
    }

    private fun stableId(prefix: String, requestId: String) =
        "$prefix-${digest(requestId).take(32)}"

    private fun fingerprint(uris: List<String>) = digest(uris.joinToString("\u001F"))

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private suspend fun loadBatchPageSources(
        job: BatchImportJobRecord,
    ): Map<Int, CanonicalSourceAssetRecord> = buildMap {
        var index = 0
        while (index < job.pages.size) {
            val first = job.pages[index]
            val draftId = first.resultDraftId
            if (
                first.status != StudyDbValue.BatchImportPageStatus.READY ||
                draftId == null
            ) {
                index += 1
                continue
            }
            val groupedPages = job.pages.drop(index).takeWhile { page ->
                page.status == StudyDbValue.BatchImportPageStatus.READY &&
                    page.resultDraftId == draftId
            }
            val draft = checkNotNull(database.readProblemDraft(draftId))
            require(draft.sourceAssets.size == groupedPages.size) {
                "Batch page bundle changed after import"
            }
            groupedPages.zip(draft.sourceAssets).forEach { (page, source) ->
                put(page.pageIndex, source.sourceAsset)
            }
            index += groupedPages.size
        }
    }

    private fun unresolvedBoundaryWindows(job: BatchImportJobRecord): List<List<Int>> {
        val unresolved = job.pages.zipWithNext().mapIndexedNotNull {
                pageIndex,
                (page, following),
            ->
            pageIndex.takeIf {
                page.hasUnresolvedBoundaryWith(following)
            }
        }
        if (unresolved.isEmpty()) return emptyList()
        val segments = mutableListOf<MutableList<Int>>()
        unresolved.forEach { pageIndex ->
            val current = segments.lastOrNull()
            if (current == null || pageIndex != current.last() + 1) {
                segments += mutableListOf(pageIndex)
            } else {
                current += pageIndex
            }
        }
        return segments.flatMap { segment ->
            segment.chunked(MAX_BOUNDARIES_PER_MODEL_REQUEST)
        }
    }

    private suspend fun resolveBoundary(
        jobId: String,
        pageIndex: Int,
        relation: CapturePageRelation,
        assessmentDecision: CaptureAssessmentDecision,
    ) {
        val job = database.readBatchImportJob(jobId) ?: error("Batch import no longer exists")
        val primaryDraftId = checkNotNull(job.pages[pageIndex].resultDraftId)
        val followingDraftId = checkNotNull(job.pages[pageIndex + 1].resultDraftId)
        val resolution = when (relation) {
            CapturePageRelation.SAME_QUESTION ->
                if (assessmentDecision == CaptureAssessmentDecision.RECAPTURE) {
                    StudyDbValue.BatchImportBoundaryStatus.KEPT_SEPARATE
                } else {
                    StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION
                }
            CapturePageRelation.NEXT_QUESTION ->
                StudyDbValue.BatchImportBoundaryStatus.NEXT_QUESTION
            CapturePageRelation.UNSURE ->
                StudyDbValue.BatchImportBoundaryStatus.KEPT_SEPARATE
        }
        database.resolveBatchImportBoundary(
            ResolveBatchImportBoundaryCommand(
                jobId = jobId,
                pageIndex = pageIndex,
                primaryDraftId = primaryDraftId,
                followingDraftId = followingDraftId,
                resolution = resolution,
                occurredAtEpochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun pageRelationRequest(
        jobId: String,
        boundaryIndexes: List<Int>,
        sources: List<CanonicalSourceAssetRecord>,
        provider: ProviderCapabilitySnapshot,
        approval: BatchImportOrganizationApproval,
    ): ModelTaskRequest {
        require(boundaryIndexes.isNotEmpty())
        require(sources.size == boundaryIndexes.size + 1)
        val windowKey = "${boundaryIndexes.first()}:${boundaryIndexes.last()}"
        val boundarySubjectId = stableId("batch-boundary", "$jobId:$windowKey")
        val primarySource = sources.first()
        val followingRefs = sources.drop(1).mapIndexed { index, source ->
            CaptureSourceAssetRef(
                assetId = source.sourceAssetId,
                sha256 = source.contentSha256,
                width = source.width,
                height = source.height,
                pageIndex = index + 1,
            )
        }
        val requestId = stableId(
            "batch-page",
            "$jobId:$windowKey:${approval.approvedAtEpochMillis}",
        )
        val input = CaptureAssessmentInput(
            draftId = boundarySubjectId,
            sourceAssetId = primarySource.sourceAssetId,
            origin = CaptureAssessmentOrigin.LIBRARY,
            imageWidth = primarySource.width,
            imageHeight = primarySource.height,
            followingSourceAssets = followingRefs,
        )
        val manifest = if (
            provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER
        ) {
            ModelEgressManifest(
                authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                subjectId = boundarySubjectId,
                purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
                authorizedTaskKinds = setOf(
                    ModelTaskKind.CAPTURE_ASSESS,
                    ModelTaskKind.CAPTURE_PARSE,
                ),
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerConfigurationVersion = provider.providerConfigurationVersion,
                promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
                approvedAtEpochMillis = approval.approvedAtEpochMillis,
                assets = sources.map { source ->
                    ModelEgressAssetGrant(
                        assetId = source.sourceAssetId,
                        sha256 = source.contentSha256,
                        byteSize = source.byteSize,
                        width = source.width,
                        height = source.height,
                    )
                },
                disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
                prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
            )
        } else {
            null
        }
        return ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = approval.approvedAtEpochMillis,
            egressManifest = manifest,
        )
    }

    private companion object {
        const val MAX_BOUNDARIES_PER_MODEL_REQUEST = 7
    }
}

private object LegacyBatchImportRepositoryFactory {
    fun create(
        context: Context,
        database: LegacyPreCutoverBatchImportDatabasePort,
        capture: CaptureWorkflowRepository,
        processingScope: CoroutineScope,
        modelTasks: ModelTaskRepository = BatchOrganizationUnavailableModelTasks,
    ): BatchImportRepository = LegacyRoomBatchImportRepository(
        database = database,
        capture = capture,
        processingScope = processingScope,
        sourceStaging = AndroidBatchImportSourceStaging(context),
        modelTasks = modelTasks,
    )
}

private fun BatchImportJobRecord.toDomain() = BatchImportJob(
    jobId = jobId,
    status = when (status) {
        StudyDbValue.BatchImportStatus.PROCESSING -> BatchImportStatus.PROCESSING
        StudyDbValue.BatchImportStatus.PAUSED -> BatchImportStatus.PAUSED
        StudyDbValue.BatchImportStatus.COMPLETED -> BatchImportStatus.COMPLETED
        else -> error("Unsupported batch import status")
    },
    pages = pages.map(BatchImportPageRecord::toDomain),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun BatchImportPageRecord.toDomain() = BatchImportPage(
    pageIndex = pageIndex,
    status = when (status) {
        StudyDbValue.BatchImportPageStatus.QUEUED -> BatchImportPageStatus.QUEUED
        StudyDbValue.BatchImportPageStatus.IMPORTING -> BatchImportPageStatus.IMPORTING
        StudyDbValue.BatchImportPageStatus.READY -> BatchImportPageStatus.READY
        StudyDbValue.BatchImportPageStatus.FAILED -> BatchImportPageStatus.FAILED
        StudyDbValue.BatchImportPageStatus.SKIPPED -> BatchImportPageStatus.SKIPPED
        else -> error("Unsupported batch import page status")
    },
    draftId = resultDraftId,
    failureCode = failureCode,
    attemptCount = attemptCount,
    updatedAtEpochMillis = updatedAtEpochMillis,
    boundaryAfterStatus = when (boundaryAfterStatus) {
        StudyDbValue.BatchImportBoundaryStatus.PENDING -> BatchImportBoundaryStatus.PENDING
        StudyDbValue.BatchImportBoundaryStatus.CHECKING -> BatchImportBoundaryStatus.CHECKING
        StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION ->
            BatchImportBoundaryStatus.SAME_QUESTION
        StudyDbValue.BatchImportBoundaryStatus.NEXT_QUESTION ->
            BatchImportBoundaryStatus.NEXT_QUESTION
        StudyDbValue.BatchImportBoundaryStatus.KEPT_SEPARATE ->
            BatchImportBoundaryStatus.KEPT_SEPARATE
        StudyDbValue.BatchImportBoundaryStatus.FAILED -> BatchImportBoundaryStatus.FAILED
        else -> error("Unsupported batch import boundary status")
    },
)

private fun BatchImportPageRecord.hasUnresolvedBoundaryWith(
    following: BatchImportPageRecord,
): Boolean =
    status == StudyDbValue.BatchImportPageStatus.READY &&
        following.status == StudyDbValue.BatchImportPageStatus.READY &&
        boundaryAfterStatus != StudyDbValue.BatchImportBoundaryStatus.SAME_QUESTION &&
        boundaryAfterStatus != StudyDbValue.BatchImportBoundaryStatus.NEXT_QUESTION &&
        boundaryAfterStatus != StudyDbValue.BatchImportBoundaryStatus.KEPT_SEPARATE

private fun Exception.toBatchFailureCode(): String = when (this) {
    is SecurityException -> "SOURCE_PERMISSION_LOST"
    is IllegalArgumentException -> "SOURCE_NOT_READABLE"
    else -> "IMPORT_FAILED"
}

private fun ProviderCapabilitySnapshot.canOrganizeBatchPages(): Boolean =
    supports(ModelTaskKind.CAPTURE_ASSESS) &&
        supportsImageInput &&
        supportsStructuredOutput &&
        executionLocation != ModelExecutionLocation.UNAVAILABLE

private fun ProviderCapabilitySnapshot.matches(
    approval: BatchImportOrganizationApproval,
): Boolean =
    providerId == approval.providerId &&
        modelId == approval.modelId &&
        providerConfigurationVersion == approval.providerConfigurationVersion

private object BatchOrganizationUnavailableModelTasks : ModelTaskRepository {
    private val provider = ProviderCapabilitySnapshot(
        providerId = "unavailable",
        providerDisplayName = "尚未配置模型",
        modelId = "unavailable",
        supportedTasks = emptySet(),
        supportsImageInput = false,
        supportsStructuredOutput = false,
        supportsStreaming = false,
        executionLocation = ModelExecutionLocation.UNAVAILABLE,
    )

    override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

    override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

    override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
        error("Batch page organization requires a configured model")
    }
}
