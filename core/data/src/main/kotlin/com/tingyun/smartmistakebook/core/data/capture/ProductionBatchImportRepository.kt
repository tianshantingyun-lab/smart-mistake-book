package com.tingyun.smartmistakebook.core.data.capture

import android.content.Context
import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationBatchImportClaim
import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationBatchImportConstructionClaim
import com.tingyun.smartmistakebook.core.data.authority.CurrentGenerationBatchImportConstructionResources
import com.tingyun.smartmistakebook.core.data.authority.ProductionBatchImportOwner
import com.tingyun.smartmistakebook.core.data.authority.ProductionBatchImportOwnerFactory
import com.tingyun.smartmistakebook.core.data.model.SharedWorkloadAdmissionGate
import com.tingyun.smartmistakebook.core.data.model.WorkloadCategory
import com.tingyun.smartmistakebook.core.data.session.BatchImportBoundarySessionStatus
import com.tingyun.smartmistakebook.core.data.session.BatchImportPageSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.BatchImportPageSessionStatus
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionMutation
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionMutationResult
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionPort
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionReadQuery
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.BatchImportSessionStatus
import com.tingyun.smartmistakebook.core.data.session.CreateBatchImportSessionCommand
import com.tingyun.smartmistakebook.core.data.session.SessionMutationDisposition
import com.tingyun.smartmistakebook.core.data.session.SessionOperationIdentity
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.SessionVersion
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
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Production batch coordinator.
 *
 * Batch progress belongs to [BatchImportSessionPort]. Temporary question drafts and canonical
 * images belong to [CaptureDraftSessionPort]. The coordinator only combines their opaque
 * identifiers in memory and never opens either backing database.
 *
 * The public batch contract has pause/resume but no cancellation command. Pausing therefore stays
 * resumable and must not be presented or persisted as cancellation.
 */
private class ProductionBatchImportRepository private constructor(
    constructionResources: CurrentGenerationBatchImportConstructionResources,
    canonicalContext: Context,
    private val scope: SessionScope,
    private val sessions: BatchImportSessionPort,
    private val captureDrafts: CaptureDraftSessionPort,
    private val processingScope: CoroutineScope,
    private val modelTasks: ModelTaskRepository,
) : BatchImportRepository {
    init {
        constructionResources.claimForRawConstruction()
        check(constructionResources.canonicalContext === canonicalContext)
        check(constructionResources.sessionScope === scope)
        check(constructionResources.batchSessions === sessions)
        check(constructionResources.captureDrafts === captureDrafts)
        check(constructionResources.modelTaskQueue === modelTasks)
    }

    private val sourceStaging: BatchImportSourceStaging =
        AndroidBatchImportSourceStaging(canonicalContext)
    private val nowEpochMillis: () -> Long = System::currentTimeMillis
    private val processingMutex = Mutex()
    private val organizationMutex = Mutex()
    private val workloadGate = SharedWorkloadAdmissionGate.gate

    init {
        processingScope.launch {
            val jobs = sessions.observe(scope).first()
            jobs.asSequence()
                .flatMap { job -> job.pages.asSequence() }
                .filter { page ->
                    page.status == BatchImportPageSessionStatus.READY ||
                        page.status == BatchImportPageSessionStatus.SKIPPED
                }
                .forEach { page -> deleteReleasedSource(page.sourceUri) }
            sourceStaging.reconcileOrphans(
                referencedSourceUris =
                    jobs.flatMap { job -> job.pages.map(BatchImportPageSessionSnapshot::sourceUri) }
                        .toSet(),
                nowEpochMillis = nowEpochMillis(),
            )
            jobs.filter { job -> job.status == BatchImportSessionStatus.PROCESSING }
                .forEach { job -> process(job.jobId) }
        }
    }

    override fun observeBatchImports(): Flow<List<BatchImportJob>> =
        sessions.observe(scope)
            .map { jobs -> jobs.map(BatchImportSessionSnapshot::toDomain) }
            .flowOn(Dispatchers.IO)

    override suspend fun recoverInterruptedBatchImportWork() {
        withContext(Dispatchers.IO) {
            val jobs = sessions.observe(scope).first()
            jobs.forEach { snapshot ->
                recoverDurableCaptureMerges(snapshot)
                val current = read(snapshot.jobId) ?: return@forEach
                if (current.status == BatchImportSessionStatus.PROCESSING) {
                    requeueInterruptedPages(current, nowEpochMillis())
                    schedule(current.jobId)
                }
                requeueInterruptedBoundaries(current, nowEpochMillis())
            }
        }
    }

    override suspend fun createBatchImport(request: CreateBatchImportRequest): BatchImportJob =
        createImport(
            requestId = request.requestId,
            requestFingerprint = fingerprint(request.localUris),
            occurredAtEpochMillis = request.occurredAtEpochMillis,
        ) {
            workloadGate.withPermit(WorkloadCategory.BATCH_IMPORT) {
                sourceStaging.stage(request.localUris)
            }
        }

    override suspend fun createPdfImport(request: CreatePdfImportRequest): BatchImportJob =
        createImport(
            requestId = request.requestId,
            requestFingerprint = digest("PDF\u001F${request.localUri}"),
            occurredAtEpochMillis = request.occurredAtEpochMillis,
        ) {
            workloadGate.withPermit(WorkloadCategory.PDF) {
                sourceStaging.stagePdf(request.localUri)
            }
        }

    private suspend fun createImport(
        requestId: String,
        requestFingerprint: String,
        occurredAtEpochMillis: Long,
        stage: suspend () -> StagedBatchImportSources,
    ): BatchImportJob =
        withContext(NonCancellable + Dispatchers.IO) {
            val jobId = stableId("batch", requestId)
            read(jobId)?.let { existing ->
                existing.requireRequest(requestId, requestFingerprint)
                if (existing.status == BatchImportSessionStatus.PROCESSING) schedule(jobId)
                return@withContext existing.toDomain()
            }

            val staged = stage()
            val result =
                try {
                    sessions.create(
                        CreateBatchImportSessionCommand(
                            scope = scope,
                            operation =
                                SessionOperationIdentity(
                                    requestId = requestId,
                                    idempotencyKey = stableId("batch-create", requestId),
                                    requestVersion = 0,
                                    payloadFingerprint = requestFingerprint,
                                ),
                            jobId = jobId,
                            sourceUris = staged.sourceUris,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                        ),
                    )
                } catch (failure: Exception) {
                    sourceStaging.delete(staged)
                    throw failure
                }

            val durable =
                result.snapshot
                    ?: run {
                        sourceStaging.delete(staged)
                        error("Batch import session was not returned")
                    }
            try {
                durable.requireRequest(requestId, requestFingerprint)
            } catch (failure: Exception) {
                if (
                    durable.pages.map(BatchImportPageSessionSnapshot::sourceUri) !=
                    staged.sourceUris
                ) {
                    sourceStaging.delete(staged)
                }
                throw failure
            }
            when (result.receipt.disposition) {
                SessionMutationDisposition.APPLIED -> Unit
                SessionMutationDisposition.DUPLICATE,
                SessionMutationDisposition.REJECTED,
                -> {
                    if (durable.pages.map(BatchImportPageSessionSnapshot::sourceUri) !=
                        staged.sourceUris
                    ) {
                        sourceStaging.delete(staged)
                    }
                }
                SessionMutationDisposition.RELOAD_REQUIRED,
                SessionMutationDisposition.NOT_FOUND,
                -> {
                    sourceStaging.delete(staged)
                    error("Batch import session could not be created")
                }
            }
            schedule(durable.jobId)
            durable.toDomain()
        }

    override suspend fun pauseBatchImport(jobId: String) {
        require(jobId.isNotBlank())
        val occurredAt = nowEpochMillis()
        mutateUntilSettled(
            jobId = jobId,
            action = "pause",
            occurredAtEpochMillis = occurredAt,
            isSettled = { snapshot ->
                snapshot.status == BatchImportSessionStatus.PAUSED ||
                    snapshot.status == BatchImportSessionStatus.COMPLETED
            },
        ) { snapshot, operation ->
            BatchImportSessionMutation.SetStatus(
                scope = scope,
                operation = operation,
                jobId = jobId,
                expectedVersion = snapshot.version,
                occurredAtEpochMillis = occurredAt,
                nextStatus = BatchImportSessionStatus.PAUSED,
            )
        }
    }

    override suspend fun resumeBatchImport(jobId: String) {
        require(jobId.isNotBlank())
        val occurredAt = nowEpochMillis()
        mutateUntilSettled(
            jobId = jobId,
            action = "resume",
            occurredAtEpochMillis = occurredAt,
            isSettled = { snapshot -> snapshot.status == BatchImportSessionStatus.PROCESSING },
        ) { snapshot, operation ->
            BatchImportSessionMutation.SetStatus(
                scope = scope,
                operation = operation,
                jobId = jobId,
                expectedVersion = snapshot.version,
                occurredAtEpochMillis = occurredAt,
                nextStatus = BatchImportSessionStatus.PROCESSING,
            )
        }
        schedule(jobId)
    }

    override suspend fun retryBatchImportPage(jobId: String, pageIndex: Int) {
        require(jobId.isNotBlank())
        require(pageIndex >= 0)
        val occurredAt = nowEpochMillis()
        mutateUntilSettled(
            jobId = jobId,
            action = "retry-page:$pageIndex",
            occurredAtEpochMillis = occurredAt,
            isSettled = { snapshot ->
                snapshot.pages.getOrNull(pageIndex)?.status ==
                    BatchImportPageSessionStatus.QUEUED
            },
        ) { snapshot, operation ->
            BatchImportSessionMutation.RetryPage(
                scope = scope,
                operation = operation,
                jobId = jobId,
                expectedVersion = snapshot.version,
                occurredAtEpochMillis = occurredAt,
                pageIndex = pageIndex,
            )
        }
        schedule(jobId)
    }

    override suspend fun skipBatchImportPage(jobId: String, pageIndex: Int) {
        require(jobId.isNotBlank())
        require(pageIndex >= 0)
        val occurredAt = nowEpochMillis()
        val skipped =
            mutateUntilSettled(
                jobId = jobId,
                action = "skip-page:$pageIndex",
                occurredAtEpochMillis = occurredAt,
                isSettled = { snapshot ->
                    snapshot.pages.getOrNull(pageIndex)?.status ==
                        BatchImportPageSessionStatus.SKIPPED
                },
            ) { snapshot, operation ->
                BatchImportSessionMutation.SkipPage(
                    scope = scope,
                    operation = operation,
                    jobId = jobId,
                    expectedVersion = snapshot.version,
                    occurredAtEpochMillis = occurredAt,
                    pageIndex = pageIndex,
                )
            }
        skipped.pages.getOrNull(pageIndex)?.let { page ->
            deleteReleasedSource(page.sourceUri)
        }
        finishIfSettled(jobId, occurredAt)
    }

    override suspend fun prepareOrganization(jobId: String): BatchImportOrganizationOffer =
        withContext(Dispatchers.IO) {
            require(jobId.isNotBlank())
            val job = read(jobId) ?: error("Batch import no longer exists")
            require(job.status == BatchImportSessionStatus.COMPLETED) {
                "Batch import must finish before its pages can be organized"
            }
            val provider = modelTasks.capabilities()
            require(provider.canOrganizeBatchPages()) {
                "The configured model cannot compare adjacent question pages"
            }
            val disclosedPageIndexes =
                job.pages.zipWithNext()
                    .filter { (page, following) -> page.hasUnresolvedBoundaryWith(following) }
                    .flatMap { (page, following) ->
                        listOf(page.pageIndex, following.pageIndex)
                    }.toSet()
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
                val initial = read(approval.jobId) ?: error("Batch import no longer exists")
                require(initial.status == BatchImportSessionStatus.COMPLETED)
                recoverDurableCaptureMerges(initial)
                val afterMergeRecovery =
                    read(approval.jobId) ?: error("Batch import no longer exists")
                requeueInterruptedBoundaries(
                    afterMergeRecovery,
                    maxOf(nowEpochMillis(), approval.approvedAtEpochMillis),
                )

                val refreshed = read(approval.jobId) ?: error("Batch import no longer exists")
                val pageSources = loadBatchPageSources(refreshed)
                unresolvedBoundaryWindows(refreshed).forEach { boundaryIndexes ->
                    val claimed =
                        boundaryIndexes.filter { pageIndex ->
                            claimBoundary(
                                jobId = approval.jobId,
                                pageIndex = pageIndex,
                                occurredAtEpochMillis =
                                    maxOf(nowEpochMillis(), approval.approvedAtEpochMillis),
                            )
                        }
                    if (claimed.size != boundaryIndexes.size) {
                        claimed.forEach { pageIndex ->
                            failBoundary(
                                jobId = approval.jobId,
                                pageIndex = pageIndex,
                                occurredAtEpochMillis = approval.approvedAtEpochMillis,
                            )
                        }
                        return@forEach
                    }
                    try {
                        val pageIndexes =
                            boundaryIndexes.first()..(boundaryIndexes.last() + 1)
                        val sources =
                            pageIndexes.map { pageIndex -> checkNotNull(pageSources[pageIndex]) }
                        val request =
                            pageRelationRequest(
                                jobId = approval.jobId,
                                boundaryIndexes = boundaryIndexes,
                                sources = sources,
                                provider = provider,
                                approval = approval,
                            )
                        val snapshot = modelTasks.execute(request).last()
                        val assessment =
                            (snapshot.output as? CaptureAssessmentOutput)?.assessment
                        if (snapshot.status != ModelTaskStatus.SUCCEEDED || assessment == null) {
                            boundaryIndexes.forEach { pageIndex ->
                                failBoundary(
                                    jobId = approval.jobId,
                                    pageIndex = pageIndex,
                                    occurredAtEpochMillis = approval.approvedAtEpochMillis,
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
                            failBoundary(
                                jobId = approval.jobId,
                                pageIndex = pageIndex,
                                occurredAtEpochMillis = approval.approvedAtEpochMillis,
                            )
                        }
                        throw cancelled
                    } catch (_: Exception) {
                        boundaryIndexes.forEach { pageIndex ->
                            failBoundary(
                                jobId = approval.jobId,
                                pageIndex = pageIndex,
                                occurredAtEpochMillis = approval.approvedAtEpochMillis,
                            )
                        }
                    }
                }
            }
        }

    private fun schedule(jobId: String) {
        processingScope.launch { process(jobId) }
    }

    private suspend fun process(jobId: String) =
        workloadGate.withPermit(WorkloadCategory.BATCH_IMPORT) {
            processingMutex.withLock {
            withContext(Dispatchers.IO) {
                val initial = read(jobId) ?: return@withContext
                requeueInterruptedPages(initial, nowEpochMillis())
                while (true) {
                    val claimed = claimNextPage(jobId, nowEpochMillis()) ?: break
                    val draftSessionId =
                        try {
                            captureDrafts.importDraft(
                                ImportCaptureDraftSessionCommand(
                                    CaptureDraftImportRequest(
                                        requestId = "batch:$jobId:${claimed.pageIndex}",
                                        localUri = claimed.sourceUri,
                                        source = CaptureInputSource.PHOTO_PICKER,
                                        origin = CaptureEntryOrigin.LIBRARY,
                                        occurredAtEpochMillis = claimed.createdAtEpochMillis,
                                    ),
                                ),
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            failPage(
                                jobId = jobId,
                                pageIndex = claimed.pageIndex,
                                failureCode = failure.toBatchFailureCode(),
                                occurredAtEpochMillis = nowEpochMillis(),
                            )
                            continue
                        }
                    completePage(
                        jobId = jobId,
                        pageIndex = claimed.pageIndex,
                        draftSessionId = draftSessionId,
                        occurredAtEpochMillis = nowEpochMillis(),
                    )
                    deleteReleasedSource(claimed.sourceUri)
                }
                finishIfSettled(jobId, nowEpochMillis())
            }
        }
        }

    private suspend fun requeueInterruptedPages(
        snapshot: BatchImportSessionSnapshot,
        occurredAtEpochMillis: Long,
    ) {
        mutateOnce(
            snapshot = snapshot,
            action = "requeue-pages",
            occurredAtEpochMillis = occurredAtEpochMillis,
        ) { current, operation ->
            BatchImportSessionMutation.RequeueInterruptedPages(
                scope = scope,
                operation = operation,
                jobId = current.jobId,
                expectedVersion = current.version,
                occurredAtEpochMillis = occurredAtEpochMillis,
            )
        }
    }

    private suspend fun claimNextPage(
        jobId: String,
        occurredAtEpochMillis: Long,
    ): BatchImportPageSessionSnapshot? {
        var current = read(jobId) ?: return null
        repeat(MAX_SESSION_RELOAD_ATTEMPTS) {
            if (current.status != BatchImportSessionStatus.PROCESSING) return null
            val result =
                sessions.mutate(
                    BatchImportSessionMutation.ClaimNextPage(
                        scope = scope,
                        operation =
                            mutationOperation(
                                action = "claim-page",
                                jobId = jobId,
                                expectedVersion = current.version,
                                occurredAtEpochMillis = occurredAtEpochMillis,
                            ),
                        jobId = jobId,
                        expectedVersion = current.version,
                        occurredAtEpochMillis = occurredAtEpochMillis,
                    ),
                )
            when (result.receipt.disposition) {
                SessionMutationDisposition.APPLIED -> return result.claimedPage
                SessionMutationDisposition.DUPLICATE -> return null
                SessionMutationDisposition.RELOAD_REQUIRED -> {
                    current = result.snapshot ?: read(jobId) ?: return null
                }
                SessionMutationDisposition.NOT_FOUND -> return null
                SessionMutationDisposition.REJECTED ->
                    error("Batch page claim was rejected")
            }
        }
        error("Batch page claim did not converge")
    }

    private suspend fun completePage(
        jobId: String,
        pageIndex: Int,
        draftSessionId: CaptureDraftSessionId,
        occurredAtEpochMillis: Long,
    ) {
        mutateUntilSettled(
            jobId = jobId,
            action = "complete-page:$pageIndex:${draftSessionId.value}",
            occurredAtEpochMillis = occurredAtEpochMillis,
            isSettled = { snapshot ->
                snapshot.pages.getOrNull(pageIndex)?.let { page ->
                    page.status == BatchImportPageSessionStatus.READY &&
                        page.resultDraftSessionId == draftSessionId.value
                } == true
            },
        ) { snapshot, operation ->
            BatchImportSessionMutation.CompletePage(
                scope = scope,
                operation = operation,
                jobId = jobId,
                expectedVersion = snapshot.version,
                occurredAtEpochMillis = occurredAtEpochMillis,
                pageIndex = pageIndex,
                draftSessionId = draftSessionId.value,
            )
        }
    }

    private suspend fun failPage(
        jobId: String,
        pageIndex: Int,
        failureCode: String,
        occurredAtEpochMillis: Long,
    ) {
        mutateUntilSettled(
            jobId = jobId,
            action = "fail-page:$pageIndex:$failureCode",
            occurredAtEpochMillis = occurredAtEpochMillis,
            isSettled = { snapshot ->
                snapshot.pages.getOrNull(pageIndex)?.status ==
                    BatchImportPageSessionStatus.FAILED
            },
        ) { snapshot, operation ->
            BatchImportSessionMutation.FailPage(
                scope = scope,
                operation = operation,
                jobId = jobId,
                expectedVersion = snapshot.version,
                occurredAtEpochMillis = occurredAtEpochMillis,
                pageIndex = pageIndex,
                failureCode = failureCode,
            )
        }
    }

    private suspend fun finishIfSettled(
        jobId: String,
        occurredAtEpochMillis: Long,
    ) {
        val snapshot = read(jobId) ?: return
        mutateOnce(
            snapshot = snapshot,
            action = "finish-if-settled",
            occurredAtEpochMillis = occurredAtEpochMillis,
        ) { current, operation ->
            BatchImportSessionMutation.FinishIfSettled(
                scope = scope,
                operation = operation,
                jobId = jobId,
                expectedVersion = current.version,
                occurredAtEpochMillis = occurredAtEpochMillis,
            )
        }
    }

    private suspend fun requeueInterruptedBoundaries(
        snapshot: BatchImportSessionSnapshot,
        occurredAtEpochMillis: Long,
    ) {
        mutateOnce(
            snapshot = snapshot,
            action = "requeue-boundaries",
            occurredAtEpochMillis = occurredAtEpochMillis,
        ) { current, operation ->
            BatchImportSessionMutation.RequeueInterruptedBoundaries(
                scope = scope,
                operation = operation,
                jobId = current.jobId,
                expectedVersion = current.version,
                occurredAtEpochMillis = occurredAtEpochMillis,
            )
        }
    }

    private suspend fun claimBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ): Boolean {
        val result =
            mutateUntilSettled(
                jobId = jobId,
                action = "claim-boundary:$pageIndex",
                occurredAtEpochMillis = occurredAtEpochMillis,
                isSettled = { snapshot ->
                    snapshot.pages.getOrNull(pageIndex)?.boundaryAfter ==
                        BatchImportBoundarySessionStatus.CHECKING
                },
            ) { snapshot, operation ->
                BatchImportSessionMutation.ClaimBoundary(
                    scope = scope,
                    operation = operation,
                    jobId = jobId,
                    expectedVersion = snapshot.version,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    pageIndex = pageIndex,
                )
            }
        return result.pages.getOrNull(pageIndex)?.boundaryAfter ==
            BatchImportBoundarySessionStatus.CHECKING
    }

    private suspend fun failBoundary(
        jobId: String,
        pageIndex: Int,
        occurredAtEpochMillis: Long,
    ) {
        val current = read(jobId) ?: return
        val page = current.pages.getOrNull(pageIndex) ?: return
        if (page.boundaryAfter != BatchImportBoundarySessionStatus.CHECKING) return
        val boundaryClaimedAtEpochMillis =
            checkNotNull(page.boundaryClaimedAtEpochMillis) {
                "Claimed batch boundary has no durable claim time"
            }
        val failedAtEpochMillis =
            maxOf(
                occurredAtEpochMillis,
                boundaryClaimedAtEpochMillis,
                page.updatedAtEpochMillis,
                nowEpochMillis(),
            )
        mutateUntilSettled(
            jobId = jobId,
            action = "fail-boundary:$pageIndex",
            occurredAtEpochMillis = failedAtEpochMillis,
            isSettled = { snapshot ->
                snapshot.pages.getOrNull(pageIndex)?.boundaryAfter ==
                    BatchImportBoundarySessionStatus.FAILED
            },
        ) { snapshot, operation ->
            BatchImportSessionMutation.FailBoundary(
                scope = scope,
                operation = operation,
                jobId = jobId,
                expectedVersion = snapshot.version,
                occurredAtEpochMillis = failedAtEpochMillis,
                pageIndex = pageIndex,
            )
        }
    }

    private suspend fun resolveBoundary(
        jobId: String,
        pageIndex: Int,
        relation: CapturePageRelation,
        assessmentDecision: CaptureAssessmentDecision,
    ) {
        val initial = read(jobId) ?: error("Batch import no longer exists")
        val primaryPage =
            checkNotNull(initial.pages.getOrNull(pageIndex)) {
                "Claimed batch boundary no longer exists"
            }
        check(primaryPage.boundaryAfter == BatchImportBoundarySessionStatus.CHECKING) {
            "Batch boundary is not actively claimed"
        }
        val boundaryClaimedAtEpochMillis =
            checkNotNull(primaryPage.boundaryClaimedAtEpochMillis) {
                "Claimed batch boundary has no durable claim time"
            }
        val primaryDraftId =
            checkNotNull(primaryPage.resultDraftSessionId)
        val followingDraftId =
            checkNotNull(initial.pages.getOrNull(pageIndex + 1)?.resultDraftSessionId)
        val resolution =
            when (relation) {
                CapturePageRelation.SAME_QUESTION ->
                    if (assessmentDecision == CaptureAssessmentDecision.RECAPTURE) {
                        BatchImportBoundarySessionStatus.KEPT_SEPARATE
                    } else {
                        BatchImportBoundarySessionStatus.SAME_QUESTION
                    }
                CapturePageRelation.NEXT_QUESTION ->
                    BatchImportBoundarySessionStatus.NEXT_QUESTION
                CapturePageRelation.UNSURE ->
                    BatchImportBoundarySessionStatus.KEPT_SEPARATE
            }
        val captureMergeReceipt =
            if (
                resolution == BatchImportBoundarySessionStatus.SAME_QUESTION &&
                primaryDraftId != followingDraftId
            ) {
                mergeAdjacentDrafts(
                    job = initial,
                    pageIndex = pageIndex,
                    primaryDraftId = CaptureDraftSessionId(primaryDraftId),
                    followingDraftId = CaptureDraftSessionId(followingDraftId),
                    boundaryClaimedAtEpochMillis = boundaryClaimedAtEpochMillis,
                )
            } else {
                null
            }
        val mergeReceiptReference = captureMergeReceipt?.receiptReference
        val resolvedAtEpochMillis =
            captureMergeReceipt?.mergedAtEpochMillis ?: boundaryClaimedAtEpochMillis
        check(resolvedAtEpochMillis >= boundaryClaimedAtEpochMillis) {
            "Capture merge cannot precede its batch boundary claim"
        }
        recordBoundaryResolution(
            jobId = jobId,
            pageIndex = pageIndex,
            primaryDraftId = primaryDraftId,
            followingDraftId = followingDraftId,
            resolution = resolution,
            boundaryClaimedAtEpochMillis = boundaryClaimedAtEpochMillis,
            resolvedAtEpochMillis = resolvedAtEpochMillis,
            mergeReceiptReference = mergeReceiptReference,
        )
    }

    private suspend fun recordBoundaryResolution(
        jobId: String,
        pageIndex: Int,
        primaryDraftId: String,
        followingDraftId: String,
        resolution: BatchImportBoundarySessionStatus,
        boundaryClaimedAtEpochMillis: Long,
        resolvedAtEpochMillis: Long,
        mergeReceiptReference: String?,
    ) {
        val action =
            "resolve-boundary:$pageIndex:${resolution.name}:" +
                mergeReceiptReference.orEmpty()
        var current = read(jobId) ?: error("Batch import no longer exists")
        repeat(MAX_SESSION_RELOAD_ATTEMPTS) {
            val result =
                sessions.mutate(
                    BatchImportSessionMutation.ResolveBoundary(
                        scope = scope,
                        operation =
                            mutationOperation(
                                action = action,
                                jobId = jobId,
                                expectedVersion = current.version,
                                occurredAtEpochMillis = resolvedAtEpochMillis,
                            ),
                        jobId = jobId,
                        expectedVersion = current.version,
                        occurredAtEpochMillis = resolvedAtEpochMillis,
                        pageIndex = pageIndex,
                        primaryDraftSessionId = primaryDraftId,
                        followingDraftSessionId = followingDraftId,
                        resolution = resolution,
                        boundaryClaimedAtEpochMillis = boundaryClaimedAtEpochMillis,
                        captureMergeReceiptRef = mergeReceiptReference,
                    ),
                )
            when (result.receipt.disposition) {
                SessionMutationDisposition.APPLIED,
                SessionMutationDisposition.DUPLICATE,
                -> {
                    val verified =
                        result.snapshot
                            ?: error("Batch boundary resolution returned no snapshot")
                    check(
                        verified.pages.getOrNull(pageIndex)?.boundaryAfter == resolution,
                    ) {
                        "Batch boundary resolution receipt disagrees with session state"
                    }
                    return
                }
                SessionMutationDisposition.RELOAD_REQUIRED -> {
                    current =
                        result.snapshot ?: read(jobId)
                        ?: error("Batch import no longer exists")
                }
                SessionMutationDisposition.NOT_FOUND ->
                    error("Batch import no longer exists")
                SessionMutationDisposition.REJECTED ->
                    error("Batch boundary resolution was rejected")
            }
        }
        error("Batch boundary resolution did not converge")
    }

    /**
     * Completes the second phase of a capture merge that committed before the process stopped.
     *
     * Capture receipts are append-only, so recovery must consume them before resetting unfinished
     * CHECKING boundaries. Otherwise a new claim time would make the already committed merge look
     * as if it happened before its boundary was claimed.
     */
    private suspend fun recoverDurableCaptureMerges(
        initial: BatchImportSessionSnapshot,
    ) {
        var current = initial
        repeat(current.pages.size) {
            val recoverable =
                current.pages.zipWithNext()
                    .mapIndexedNotNull { pageIndex, (primaryPage, followingPage) ->
                        if (
                            primaryPage.boundaryAfter !=
                                BatchImportBoundarySessionStatus.CHECKING
                        ) {
                            return@mapIndexedNotNull null
                        }
                        val boundaryClaimedAtEpochMillis =
                            checkNotNull(primaryPage.boundaryClaimedAtEpochMillis) {
                                "Claimed batch boundary has no durable claim time"
                            }
                        val primaryDraftId =
                            primaryPage.resultDraftSessionId
                                ?: return@mapIndexedNotNull null
                        val followingDraftId =
                            followingPage.resultDraftSessionId
                                ?: return@mapIndexedNotNull null
                        if (primaryDraftId == followingDraftId) {
                            return@mapIndexedNotNull null
                        }
                        val receipt =
                            captureDrafts.readMergeReceipt(
                                ReadCaptureDraftMergeReceiptQuery(
                                    batchJobId = current.jobId,
                                    batchPageIndex = pageIndex,
                                    primaryDraftSessionId =
                                        CaptureDraftSessionId(primaryDraftId),
                                    followingDraftSessionId =
                                        CaptureDraftSessionId(followingDraftId),
                                ),
                            ) ?: return@mapIndexedNotNull null
                        check(
                            receipt.batchJobId == current.jobId &&
                                receipt.batchPageIndex == pageIndex &&
                                receipt.primaryDraftSessionId.value == primaryDraftId &&
                                receipt.followingDraftSessionId.value == followingDraftId,
                        ) {
                            "Recovered capture merge receipt conflicts with the batch boundary"
                        }
                        check(receipt.mergedAtEpochMillis >= boundaryClaimedAtEpochMillis) {
                            "Recovered capture merge predates its batch boundary claim"
                        }
                        RecoveredCaptureMerge(
                            pageIndex = pageIndex,
                            primaryDraftId = primaryDraftId,
                            followingDraftId = followingDraftId,
                            boundaryClaimedAtEpochMillis = boundaryClaimedAtEpochMillis,
                            receipt = receipt,
                        )
                    }.firstOrNull()
                    ?: return
            recordBoundaryResolution(
                jobId = current.jobId,
                pageIndex = recoverable.pageIndex,
                primaryDraftId = recoverable.primaryDraftId,
                followingDraftId = recoverable.followingDraftId,
                resolution = BatchImportBoundarySessionStatus.SAME_QUESTION,
                boundaryClaimedAtEpochMillis =
                    recoverable.boundaryClaimedAtEpochMillis,
                resolvedAtEpochMillis = recoverable.receipt.mergedAtEpochMillis,
                mergeReceiptReference = recoverable.receipt.receiptReference,
            )
            current = read(current.jobId) ?: error("Batch import no longer exists")
        }
    }

    private suspend fun mergeAdjacentDrafts(
        job: BatchImportSessionSnapshot,
        pageIndex: Int,
        primaryDraftId: CaptureDraftSessionId,
        followingDraftId: CaptureDraftSessionId,
        boundaryClaimedAtEpochMillis: Long,
    ): CaptureDraftMergeReceipt {
        captureDrafts.readMergeReceipt(
            ReadCaptureDraftMergeReceiptQuery(
                batchJobId = job.jobId,
                batchPageIndex = pageIndex,
                primaryDraftSessionId = primaryDraftId,
                followingDraftSessionId = followingDraftId,
            ),
        )?.let { receipt ->
            check(
                receipt.batchJobId == job.jobId &&
                    receipt.batchPageIndex == pageIndex &&
                    receipt.primaryDraftSessionId == primaryDraftId &&
                    receipt.followingDraftSessionId == followingDraftId,
            ) {
                "Capture merge receipt conflicts with the batch boundary"
            }
            check(receipt.mergedAtEpochMillis >= boundaryClaimedAtEpochMillis) {
                "Capture merge receipt predates the active batch boundary claim"
            }
            return receipt
        }
        val primary =
            checkNotNull(
                captureDrafts.readCanonicalSourceAssets(
                    ReadCaptureDraftCanonicalAssetsQuery(primaryDraftId),
                ),
            ) {
                "Primary capture draft no longer exists"
            }
        val following =
            checkNotNull(
                captureDrafts.readCanonicalSourceAssets(
                    ReadCaptureDraftCanonicalAssetsQuery(followingDraftId),
                ),
            ) {
                "Following capture draft no longer exists"
            }
        return captureDrafts.mergeAdjacentDrafts(
            MergeAdjacentCaptureDraftsCommand(
                batchJobId = job.jobId,
                batchPageIndex = pageIndex,
                primaryDraftSessionId = primaryDraftId,
                followingDraftSessionId = followingDraftId,
                expectedPrimarySessionVersion = primary.sessionVersion,
                expectedFollowingSessionVersion = following.sessionVersion,
                expectedPrimaryAssetOrderFingerprint = primary.assetOrderFingerprint,
                expectedFollowingAssetOrderFingerprint =
                    following.assetOrderFingerprint,
                occurredAtEpochMillis = boundaryClaimedAtEpochMillis,
            ),
        )
    }

    private suspend fun loadBatchPageSources(
        job: BatchImportSessionSnapshot,
    ): Map<Int, CaptureAssetDescriptor> =
        buildMap {
            var index = 0
            while (index < job.pages.size) {
                val first = job.pages[index]
                val draftId = first.resultDraftSessionId
                if (first.status != BatchImportPageSessionStatus.READY || draftId == null) {
                    index += 1
                    continue
                }
                val groupedPages =
                    job.pages.drop(index).takeWhile { page ->
                        page.status == BatchImportPageSessionStatus.READY &&
                            page.resultDraftSessionId == draftId
                    }
                val bundle =
                    checkNotNull(
                        captureDrafts.readCanonicalSourceAssets(
                            ReadCaptureDraftCanonicalAssetsQuery(
                                CaptureDraftSessionId(draftId),
                            ),
                        ),
                    ) {
                        "Batch capture draft no longer exists"
                    }
                require(bundle.pages.size == groupedPages.size) {
                    "Batch page bundle changed after import"
                }
                groupedPages.zip(bundle.pages).forEach { (page, source) ->
                    put(page.pageIndex, source.asset)
                }
                index += groupedPages.size
            }
        }

    private fun unresolvedBoundaryWindows(
        job: BatchImportSessionSnapshot,
    ): List<List<Int>> {
        val unresolved =
            job.pages.zipWithNext().mapIndexedNotNull { pageIndex, (page, following) ->
                pageIndex.takeIf { page.hasUnresolvedBoundaryWith(following) }
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
        return segments.flatMap { segment -> segment.chunked(MAX_BOUNDARIES_PER_MODEL_REQUEST) }
    }

    private fun pageRelationRequest(
        jobId: String,
        boundaryIndexes: List<Int>,
        sources: List<CaptureAssetDescriptor>,
        provider: ProviderCapabilitySnapshot,
        approval: BatchImportOrganizationApproval,
    ): ModelTaskRequest {
        require(boundaryIndexes.isNotEmpty())
        require(sources.size == boundaryIndexes.size + 1)
        val windowKey = "${boundaryIndexes.first()}:${boundaryIndexes.last()}"
        val boundarySubjectId = stableId("batch-boundary", "$jobId:$windowKey")
        val primarySource = sources.first()
        val followingRefs =
            sources.drop(1).mapIndexed { index, source ->
                CaptureSourceAssetRef(
                    assetId = source.assetId,
                    sha256 = source.contentSha256,
                    width = source.width,
                    height = source.height,
                    pageIndex = index + 1,
                )
            }
        val requestId =
            stableId(
                "batch-page",
                "$jobId:$windowKey:${approval.approvedAtEpochMillis}",
            )
        val input =
            CaptureAssessmentInput(
                draftId = boundarySubjectId,
                sourceAssetId = primarySource.assetId,
                origin = CaptureAssessmentOrigin.LIBRARY,
                imageWidth = primarySource.width,
                imageHeight = primarySource.height,
                followingSourceAssets = followingRefs,
            )
        val manifest =
            if (provider.executionLocation == ModelExecutionLocation.EXTERNAL_PROVIDER) {
                ModelEgressManifest(
                    authorizationId = ModelEgressAuthorizationId.forInput(requestId, input),
                    subjectId = boundarySubjectId,
                    purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
                    authorizedTaskKinds =
                        setOf(
                            ModelTaskKind.CAPTURE_ASSESS,
                            ModelTaskKind.CAPTURE_PARSE,
                        ),
                    providerId = provider.providerId,
                    modelId = provider.modelId,
                    providerConfigurationVersion = provider.providerConfigurationVersion,
                    promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
                    approvedAtEpochMillis = approval.approvedAtEpochMillis,
                    assets =
                        sources.map { source ->
                            ModelEgressAssetGrant(
                                assetId = source.assetId,
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

    private suspend fun mutateUntilSettled(
        jobId: String,
        action: String,
        occurredAtEpochMillis: Long,
        isSettled: (BatchImportSessionSnapshot) -> Boolean,
        mutation:
            (
                BatchImportSessionSnapshot,
                SessionOperationIdentity,
            ) -> BatchImportSessionMutation,
    ): BatchImportSessionSnapshot {
        var current = read(jobId) ?: error("Batch import no longer exists")
        repeat(MAX_SESSION_RELOAD_ATTEMPTS) {
            if (isSettled(current)) return current
            val result =
                sessions.mutate(
                    mutation(
                        current,
                        mutationOperation(
                            action = action,
                            jobId = jobId,
                            expectedVersion = current.version,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                        ),
                    ),
                )
            when (result.receipt.disposition) {
                SessionMutationDisposition.APPLIED,
                SessionMutationDisposition.DUPLICATE,
                -> {
                    current =
                        result.snapshot ?: error("Batch session mutation returned no snapshot")
                    if (isSettled(current)) return current
                }
                SessionMutationDisposition.RELOAD_REQUIRED -> {
                    current =
                        result.snapshot ?: read(jobId)
                        ?: error("Batch import no longer exists")
                }
                SessionMutationDisposition.NOT_FOUND ->
                    error("Batch import no longer exists")
                SessionMutationDisposition.REJECTED ->
                    error("Batch session mutation was rejected")
            }
        }
        error("Batch session mutation did not converge")
    }

    private suspend fun mutateOnce(
        snapshot: BatchImportSessionSnapshot,
        action: String,
        occurredAtEpochMillis: Long,
        mutation:
            (
                BatchImportSessionSnapshot,
                SessionOperationIdentity,
            ) -> BatchImportSessionMutation,
    ): BatchImportSessionMutationResult {
        var current = snapshot
        repeat(MAX_SESSION_RELOAD_ATTEMPTS) {
            val result =
                sessions.mutate(
                    mutation(
                        current,
                        mutationOperation(
                            action = action,
                            jobId = current.jobId,
                            expectedVersion = current.version,
                            occurredAtEpochMillis = occurredAtEpochMillis,
                        ),
                    ),
                )
            if (result.receipt.disposition != SessionMutationDisposition.RELOAD_REQUIRED) {
                return result
            }
            current =
                result.snapshot ?: read(current.jobId)
                ?: error("Batch import no longer exists")
        }
        error("Batch session mutation did not converge")
    }

    private fun mutationOperation(
        action: String,
        jobId: String,
        expectedVersion: SessionVersion,
        occurredAtEpochMillis: Long,
    ): SessionOperationIdentity {
        val payload =
            digest(
                listOf(
                    action,
                    jobId,
                    expectedVersion.sequence.toString(),
                    expectedVersion.fingerprint,
                    occurredAtEpochMillis.toString(),
                ).joinToString("\u001F"),
            )
        return SessionOperationIdentity(
            requestId = "batch-operation-${payload.take(32)}",
            idempotencyKey = "batch-operation-$payload",
            requestVersion = expectedVersion.sequence,
            payloadFingerprint = payload,
        )
    }

    private suspend fun read(jobId: String): BatchImportSessionSnapshot? =
        sessions.read(BatchImportSessionReadQuery(scope = scope, jobId = jobId))

    private suspend fun deleteReleasedSource(sourceUri: String) {
        if (!sessions.hasRetainedSourceUri(scope, sourceUri)) {
            sourceStaging.delete(sourceUri)
        }
    }

    private fun stableId(prefix: String, requestId: String): String =
        "$prefix-${digest(requestId).take(32)}"

    private fun fingerprint(uris: List<String>): String =
        digest(uris.joinToString("\u001F"))

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class RecoveredCaptureMerge(
        val pageIndex: Int,
        val primaryDraftId: String,
        val followingDraftId: String,
        val boundaryClaimedAtEpochMillis: Long,
        val receipt: CaptureDraftMergeReceipt,
    )

    private companion object {
        const val MAX_BOUNDARIES_PER_MODEL_REQUEST = 7
        const val MAX_SESSION_RELOAD_ATTEMPTS = 8
    }
}

/**
 * File-private reflective bridge. The constructor itself consumes the registry-backed one-shot
 * resource before accepting any raw dependency, so reflected raw inputs alone have no authority.
 */
private fun buildProductionBatchImportClaim(
    resources: CurrentGenerationBatchImportConstructionResources,
): CurrentGenerationBatchImportClaim {
    var processingJob: Job? = null
    try {
        val canonicalContext = resources.canonicalContext
        val sessionScope = resources.sessionScope
        val batchSessions = resources.batchSessions
        val captureDrafts = resources.captureDrafts
        val modelTaskQueue = resources.modelTaskQueue
        val generationIdentity = resources.generationIdentity
        val contextIdentity = resources.contextIdentity
        val learnerIdentity = resources.learnerIdentity
        val sessionOwnerIdentity = resources.sessionOwnerIdentity
        val modelOwnerIdentity = resources.modelOwnerIdentity

        val ownedJob = SupervisorJob()
        processingJob = ownedJob
        val constructor =
            ProductionBatchImportRepository::class.java.getDeclaredConstructor(
                CurrentGenerationBatchImportConstructionResources::class.java,
                Context::class.java,
                SessionScope::class.java,
                BatchImportSessionPort::class.java,
                CaptureDraftSessionPort::class.java,
                CoroutineScope::class.java,
                ModelTaskRepository::class.java,
            ).apply { isAccessible = true }
        val repository =
            try {
                constructor.newInstance(
                    resources,
                    canonicalContext,
                    sessionScope,
                    batchSessions,
                    captureDrafts,
                    CoroutineScope(ownedJob + Dispatchers.IO),
                    modelTaskQueue,
                )
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        return resources.registerBuilt(
            repository,
            ownedJob,
            generationIdentity,
            contextIdentity,
            learnerIdentity,
            sessionOwnerIdentity,
            modelOwnerIdentity,
        )
    } catch (failure: Throwable) {
        processingJob?.cancel(
            CancellationException("Batch-import repository construction failed")
                .apply { initCause(failure) },
        )
        throw failure
    }
}

/**
 * The JVM-visible factory accepts only an opaque current-generation claim. Raw learner, session,
 * model, context, and scope inputs remain behind private bytecode and cannot be caller-forged.
 */
internal object ProductionBatchImportRepositoryFactory {
    const val CANCELLATION_AVAILABLE: Boolean = false

    fun issue(
        constructionClaim: CurrentGenerationBatchImportConstructionClaim,
    ): CurrentGenerationBatchImportClaim {
        val resources = constructionClaim.claimForConstruction()
        try {
            return buildProductionBatchImportClaim(resources)
        } finally {
            resources.close()
            constructionClaim.close()
        }
    }

    fun open(claim: CurrentGenerationBatchImportClaim): ProductionBatchImportOwner =
        ProductionBatchImportOwnerFactory.open(claim)
}

private fun BatchImportSessionSnapshot.requireRequest(
    expectedRequestId: String,
    expectedFingerprint: String,
) {
    require(requestId == expectedRequestId && requestFingerprint == expectedFingerprint) {
        "Batch import request id was reused with different input"
    }
}

private fun BatchImportSessionSnapshot.toDomain() =
    BatchImportJob(
        jobId = jobId,
        status =
            when (status) {
                BatchImportSessionStatus.PROCESSING -> BatchImportStatus.PROCESSING
                BatchImportSessionStatus.PAUSED -> BatchImportStatus.PAUSED
                BatchImportSessionStatus.COMPLETED -> BatchImportStatus.COMPLETED
            },
        pages = pages.map(BatchImportPageSessionSnapshot::toDomain),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

private fun BatchImportPageSessionSnapshot.toDomain() =
    BatchImportPage(
        pageIndex = pageIndex,
        status =
            when (status) {
                BatchImportPageSessionStatus.QUEUED -> BatchImportPageStatus.QUEUED
                BatchImportPageSessionStatus.IMPORTING -> BatchImportPageStatus.IMPORTING
                BatchImportPageSessionStatus.READY -> BatchImportPageStatus.READY
                BatchImportPageSessionStatus.FAILED -> BatchImportPageStatus.FAILED
                BatchImportPageSessionStatus.SKIPPED -> BatchImportPageStatus.SKIPPED
            },
        draftId = resultDraftSessionId,
        failureCode = failureCode,
        attemptCount = attemptCount,
        updatedAtEpochMillis = updatedAtEpochMillis,
        boundaryAfterStatus =
            when (boundaryAfter) {
                BatchImportBoundarySessionStatus.PENDING -> BatchImportBoundaryStatus.PENDING
                BatchImportBoundarySessionStatus.CHECKING -> BatchImportBoundaryStatus.CHECKING
                BatchImportBoundarySessionStatus.SAME_QUESTION ->
                    BatchImportBoundaryStatus.SAME_QUESTION
                BatchImportBoundarySessionStatus.NEXT_QUESTION ->
                    BatchImportBoundaryStatus.NEXT_QUESTION
                BatchImportBoundarySessionStatus.KEPT_SEPARATE ->
                    BatchImportBoundaryStatus.KEPT_SEPARATE
                BatchImportBoundarySessionStatus.FAILED -> BatchImportBoundaryStatus.FAILED
            },
    )

private fun BatchImportPageSessionSnapshot.hasUnresolvedBoundaryWith(
    following: BatchImportPageSessionSnapshot,
): Boolean =
    status == BatchImportPageSessionStatus.READY &&
        following.status == BatchImportPageSessionStatus.READY &&
        boundaryAfter != BatchImportBoundarySessionStatus.SAME_QUESTION &&
        boundaryAfter != BatchImportBoundarySessionStatus.NEXT_QUESTION &&
        boundaryAfter != BatchImportBoundarySessionStatus.KEPT_SEPARATE

private fun Exception.toBatchFailureCode(): String =
    when (this) {
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
