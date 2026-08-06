package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.data.authority.TestOnlyBatchImportRepositoryConstruction
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
import com.tingyun.smartmistakebook.core.data.session.SessionMutationReceipt
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.SessionVersion
import com.tingyun.smartmistakebook.core.domain.BatchImportOrganizationApproval
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureDraftSummary
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CaptureAssessment
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentDecision
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOutput
import com.tingyun.smartmistakebook.core.model.CapturePageRelation
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionBatchImportRepositoryTest {
    @Test
    fun delayedOrganizationUsesTheDurableBoundaryClaimTimeForBothCommits() = runBlocking {
        var now = CLAIMED_AT
        val sessions = FakeBatchImportSessionPort(snapshot(boundaryClaimedAt = null))
        val captures = FakeCaptureDraftSessionPort()
        val models =
            SuccessfulPageRelationModelTasks {
                now = AFTER_MODEL_DELAY
            }
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository =
                testProductionBatchImportRepository(
                    scope = SCOPE,
                    sessions = sessions,
                    captureDrafts = captures,
                    processingScope = processingScope,
                    sourceStaging = NoOpBatchImportSourceStaging,
                    modelTasks = models,
                    nowEpochMillis = { now },
                )

            repository.organizeBatch(approval())

            assertEquals(1, captures.mergeCommands.size)
            assertEquals(CLAIMED_AT, captures.mergeCommands.single().occurredAtEpochMillis)
            val resolution = sessions.resolveCommands.single()
            assertEquals(CLAIMED_AT, resolution.boundaryClaimedAtEpochMillis)
            assertEquals(CLAIMED_AT, resolution.occurredAtEpochMillis)
            assertTrue(resolution.occurredAtEpochMillis <= AFTER_MODEL_DELAY)
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun processRecoveryConsumesTheDurableMergeReceiptWithoutMergingAgain() = runBlocking {
        val sessions =
            FakeBatchImportSessionPort(snapshot(boundaryClaimedAt = CLAIMED_AT))
        val captures = FakeCaptureDraftSessionPort()
        captures.preloadMergeReceipt(
            batchJobId = JOB_ID,
            batchPageIndex = 0,
            primaryDraftSessionId = CaptureDraftSessionId(PRIMARY_DRAFT_ID),
            followingDraftSessionId = CaptureDraftSessionId(FOLLOWING_DRAFT_ID),
            occurredAtEpochMillis = CLAIMED_AT,
        )
        captures.mergeCommands.clear()
        captures.canonicalReadDraftIds.clear()
        val models = SuccessfulPageRelationModelTasks()
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository =
                testProductionBatchImportRepository(
                    scope = SCOPE,
                    sessions = sessions,
                    captureDrafts = captures,
                    processingScope = processingScope,
                    sourceStaging = NoOpBatchImportSourceStaging,
                    modelTasks = models,
                    nowEpochMillis = { AFTER_MODEL_DELAY },
                )

            repository.organizeBatch(approval())

            assertTrue("Recovery must not merge the drafts twice", captures.mergeCommands.isEmpty())
            assertTrue(
                "Receipt replay must not reopen the following mutable draft",
                CaptureDraftSessionId(FOLLOWING_DRAFT_ID) !in
                    captures.canonicalReadDraftIds,
            )
            assertEquals(0, models.executionCount)
            val resolution = sessions.resolveCommands.single()
            assertEquals(CLAIMED_AT, resolution.boundaryClaimedAtEpochMillis)
            assertEquals(CLAIMED_AT, resolution.occurredAtEpochMillis)
            assertTrue(
                "Receipt recovery must precede interrupted-boundary requeue",
                sessions.mutationEvents.indexOf("resolve:0") <
                    sessions.mutationEvents.indexOf("requeue-boundaries"),
            )
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun startupRecoveryRequeuesInterruptedBoundariesForCompletedJobs() = runBlocking {
        val sessions =
            FakeBatchImportSessionPort(snapshot(boundaryClaimedAt = CLAIMED_AT))
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository =
                testProductionBatchImportRepository(
                    scope = SCOPE,
                    sessions = sessions,
                    captureDrafts = FakeCaptureDraftSessionPort(),
                    processingScope = processingScope,
                    sourceStaging = NoOpBatchImportSourceStaging,
                    modelTasks = SuccessfulPageRelationModelTasks(),
                    nowEpochMillis = { AFTER_MODEL_DELAY },
                )

            repository.recoverInterruptedBatchImportWork()

            assertTrue("requeue-boundaries" in sessions.mutationEvents)
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun delayedModelFailureCannotMoveTheBoundaryTimelineBeforeItsClaim() = runBlocking {
        var now = CLAIMED_AT
        val sessions = FakeBatchImportSessionPort(snapshot(boundaryClaimedAt = null))
        val captures = FakeCaptureDraftSessionPort()
        val models =
            FailingPageRelationModelTasks {
                now = AFTER_MODEL_DELAY
            }
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository =
                testProductionBatchImportRepository(
                    scope = SCOPE,
                    sessions = sessions,
                    captureDrafts = captures,
                    processingScope = processingScope,
                    sourceStaging = NoOpBatchImportSourceStaging,
                    modelTasks = models,
                    nowEpochMillis = { now },
                )

            repository.organizeBatch(approval())

            val failure = sessions.failCommands.single()
            assertEquals(AFTER_MODEL_DELAY, failure.occurredAtEpochMillis)
            assertTrue(failure.occurredAtEpochMillis >= CLAIMED_AT)
        } finally {
            processingScope.cancel()
        }
    }

    @Test
    fun concurrentTerminalResolutionStillRequiresAnImmutableReceiptReplay() = runBlocking {
        val sessions =
            FakeBatchImportSessionPort(
                initial = snapshot(boundaryClaimedAt = null),
                reloadFirstResolutionAsConcurrentCommit = true,
            )
        val captures = FakeCaptureDraftSessionPort()
        val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository =
                testProductionBatchImportRepository(
                    scope = SCOPE,
                    sessions = sessions,
                    captureDrafts = captures,
                    processingScope = processingScope,
                    sourceStaging = NoOpBatchImportSourceStaging,
                    modelTasks = SuccessfulPageRelationModelTasks(),
                    nowEpochMillis = { CLAIMED_AT },
                )

            repository.organizeBatch(approval())

            assertEquals(2, sessions.resolveMutationAttempts)
            assertEquals(1, sessions.resolveCommands.size)
            assertTrue(
                "Terminal state must be verified by receipt replay",
                "verify-resolve:0" in sessions.mutationEvents,
            )
        } finally {
            processingScope.cancel()
        }
    }

    private fun approval() =
        BatchImportOrganizationApproval(
            jobId = JOB_ID,
            providerId = PROVIDER.providerId,
            modelId = PROVIDER.modelId,
            providerConfigurationVersion = PROVIDER.providerConfigurationVersion,
            approvedAtEpochMillis = APPROVED_AT,
        )

    private fun snapshot(boundaryClaimedAt: Long?): BatchImportSessionSnapshot {
        val boundary =
            if (boundaryClaimedAt == null) {
                BatchImportBoundarySessionStatus.PENDING
            } else {
                BatchImportBoundarySessionStatus.CHECKING
            }
        return BatchImportSessionSnapshot(
            scope = SCOPE,
            jobId = JOB_ID,
            requestId = "batch-request",
            requestFingerprint = "1".repeat(64),
            version = version(1),
            status = BatchImportSessionStatus.COMPLETED,
            pages =
                listOf(
                    page(
                        pageIndex = 0,
                        draftId = PRIMARY_DRAFT_ID,
                        boundary = boundary,
                        boundaryClaimedAt = boundaryClaimedAt,
                    ),
                    page(
                        pageIndex = 1,
                        draftId = FOLLOWING_DRAFT_ID,
                        boundary = BatchImportBoundarySessionStatus.PENDING,
                        boundaryClaimedAt = null,
                    ),
                ),
            createdAtEpochMillis = CREATED_AT,
            updatedAtEpochMillis = boundaryClaimedAt ?: IMPORTED_AT,
        )
    }

    private fun page(
        pageIndex: Int,
        draftId: String,
        boundary: BatchImportBoundarySessionStatus,
        boundaryClaimedAt: Long?,
    ) = BatchImportPageSessionSnapshot(
        pageIndex = pageIndex,
        sourceUri = "content://batch/$pageIndex",
        status = BatchImportPageSessionStatus.READY,
        resultDraftSessionId = draftId,
        failureCode = null,
        attemptCount = 1,
        boundaryAfter = boundary,
        boundaryClaimedAtEpochMillis = boundaryClaimedAt,
        createdAtEpochMillis = CREATED_AT,
        updatedAtEpochMillis = boundaryClaimedAt ?: IMPORTED_AT,
    )

    private class FakeBatchImportSessionPort(
        initial: BatchImportSessionSnapshot,
        private var reloadFirstResolutionAsConcurrentCommit: Boolean = false,
    ) : BatchImportSessionPort {
        private val state = MutableStateFlow(initial)
        val resolveCommands = mutableListOf<BatchImportSessionMutation.ResolveBoundary>()
        val failCommands = mutableListOf<BatchImportSessionMutation.FailBoundary>()
        val mutationEvents = mutableListOf<String>()
        var resolveMutationAttempts: Int = 0
            private set
        private var committedResolution: BatchImportSessionMutation.ResolveBoundary? = null

        override fun observe(scope: SessionScope): Flow<List<BatchImportSessionSnapshot>> {
            require(scope == SCOPE)
            return kotlinx.coroutines.flow.flowOf(listOf(state.value))
        }

        override suspend fun read(
            query: BatchImportSessionReadQuery,
        ): BatchImportSessionSnapshot? {
            require(query.scope == SCOPE)
            return state.value.takeIf { snapshot -> snapshot.jobId == query.jobId }
        }

        override suspend fun create(
            command: CreateBatchImportSessionCommand,
        ): BatchImportSessionMutationResult = error("Not used by organization tests")

        override suspend fun mutate(
            command: BatchImportSessionMutation,
        ): BatchImportSessionMutationResult {
            val before = state.value
            if (command.expectedVersion != before.version) {
                return result(
                    command = command,
                    disposition = SessionMutationDisposition.RELOAD_REQUIRED,
                    snapshot = before,
                )
            }
            return when (command) {
                is BatchImportSessionMutation.RequeueInterruptedBoundaries -> {
                    mutationEvents += "requeue-boundaries"
                    val changed =
                        before.pages.any { page ->
                            page.boundaryAfter == BatchImportBoundarySessionStatus.CHECKING
                        }
                    if (!changed) {
                        result(command, SessionMutationDisposition.DUPLICATE, before)
                    } else {
                        apply(
                            command,
                            before.copy(
                                pages =
                                    before.pages.map { page ->
                                        if (
                                            page.boundaryAfter ==
                                            BatchImportBoundarySessionStatus.CHECKING
                                        ) {
                                            page.copy(
                                                boundaryAfter =
                                                    BatchImportBoundarySessionStatus.FAILED,
                                                boundaryClaimedAtEpochMillis = null,
                                                updatedAtEpochMillis =
                                                    command.occurredAtEpochMillis,
                                            )
                                        } else {
                                            page
                                        }
                                    },
                            ),
                        )
                    }
                }

                is BatchImportSessionMutation.ClaimBoundary -> {
                    mutationEvents += "claim:${command.pageIndex}"
                    val page = before.pages[command.pageIndex]
                    check(
                        page.boundaryAfter == BatchImportBoundarySessionStatus.PENDING ||
                            page.boundaryAfter == BatchImportBoundarySessionStatus.FAILED,
                    )
                    apply(
                        command,
                        before.copy(
                            pages =
                                before.pages.updated(command.pageIndex) { current ->
                                    current.copy(
                                        boundaryAfter =
                                            BatchImportBoundarySessionStatus.CHECKING,
                                        boundaryClaimedAtEpochMillis =
                                            command.occurredAtEpochMillis,
                                        updatedAtEpochMillis = command.occurredAtEpochMillis,
                                    )
                                },
                        ),
                    )
                }

                is BatchImportSessionMutation.ResolveBoundary -> {
                    resolveMutationAttempts += 1
                    mutationEvents += "resolve:${command.pageIndex}"
                    val page = before.pages[command.pageIndex]
                    if (page.boundaryAfter != BatchImportBoundarySessionStatus.CHECKING) {
                        check(command.hasSameImmutableResolution(checkNotNull(committedResolution)))
                        mutationEvents += "verify-resolve:${command.pageIndex}"
                        return result(
                            command,
                            SessionMutationDisposition.DUPLICATE,
                            before,
                        )
                    }
                    check(page.boundaryAfter == BatchImportBoundarySessionStatus.CHECKING)
                    check(
                        page.boundaryClaimedAtEpochMillis ==
                            command.boundaryClaimedAtEpochMillis,
                    )
                    check(command.occurredAtEpochMillis >= command.boundaryClaimedAtEpochMillis)
                    resolveCommands += command
                    committedResolution = command
                    val resolved = before.withResolution(command)
                    if (reloadFirstResolutionAsConcurrentCommit) {
                        reloadFirstResolutionAsConcurrentCommit = false
                        val concurrent =
                            resolved.copy(
                                version = version(before.version.sequence + 1),
                                updatedAtEpochMillis = command.occurredAtEpochMillis,
                            )
                        state.value = concurrent
                        result(
                            command,
                            SessionMutationDisposition.RELOAD_REQUIRED,
                            concurrent,
                        )
                    } else {
                        apply(command, resolved)
                    }
                }

                is BatchImportSessionMutation.FailBoundary -> {
                    mutationEvents += "fail:${command.pageIndex}"
                    val page = before.pages[command.pageIndex]
                    check(page.boundaryAfter == BatchImportBoundarySessionStatus.CHECKING)
                    check(
                        command.occurredAtEpochMillis >=
                            checkNotNull(page.boundaryClaimedAtEpochMillis),
                    )
                    failCommands += command
                    apply(
                        command,
                        before.copy(
                            pages =
                                before.pages.updated(command.pageIndex) { current ->
                                    current.copy(
                                        boundaryAfter =
                                            BatchImportBoundarySessionStatus.FAILED,
                                        boundaryClaimedAtEpochMillis = null,
                                        updatedAtEpochMillis = command.occurredAtEpochMillis,
                                    )
                                },
                        ),
                    )
                }

                else -> error("Unexpected mutation ${command::class.java.simpleName}")
            }
        }

        override suspend fun hasRetainedSourceUri(
            scope: SessionScope,
            sourceUri: String,
        ): Boolean = false

        private fun apply(
            command: BatchImportSessionMutation,
            changed: BatchImportSessionSnapshot,
        ): BatchImportSessionMutationResult {
            val next =
                changed.copy(
                    version = version(state.value.version.sequence + 1),
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                )
            state.value = next
            return result(command, SessionMutationDisposition.APPLIED, next)
        }

        private fun result(
            command: BatchImportSessionMutation,
            disposition: SessionMutationDisposition,
            snapshot: BatchImportSessionSnapshot,
        ) = BatchImportSessionMutationResult(
            receipt =
                SessionMutationReceipt(
                    operation = command.operation,
                    disposition = disposition,
                    currentVersion = snapshot.version,
                    recordedAtEpochMillis = command.occurredAtEpochMillis,
                ),
            snapshot = snapshot,
        )

        private fun BatchImportSessionSnapshot.withResolution(
            command: BatchImportSessionMutation.ResolveBoundary,
        ): BatchImportSessionSnapshot {
            val remappedPages =
                pages.map { candidate ->
                    if (
                        command.resolution ==
                        BatchImportBoundarySessionStatus.SAME_QUESTION &&
                        candidate.resultDraftSessionId ==
                        command.followingDraftSessionId
                    ) {
                        candidate.copy(
                            resultDraftSessionId = command.primaryDraftSessionId,
                            updatedAtEpochMillis = command.occurredAtEpochMillis,
                        )
                    } else {
                        candidate
                    }
                }.updated(command.pageIndex) { current ->
                    current.copy(
                        boundaryAfter = command.resolution,
                        boundaryClaimedAtEpochMillis = null,
                        updatedAtEpochMillis = command.occurredAtEpochMillis,
                    )
                }
            return copy(pages = remappedPages)
        }

        private fun BatchImportSessionMutation.ResolveBoundary.hasSameImmutableResolution(
            other: BatchImportSessionMutation.ResolveBoundary,
        ): Boolean =
            jobId == other.jobId &&
                pageIndex == other.pageIndex &&
                primaryDraftSessionId == other.primaryDraftSessionId &&
                followingDraftSessionId == other.followingDraftSessionId &&
                resolution == other.resolution &&
                boundaryClaimedAtEpochMillis == other.boundaryClaimedAtEpochMillis &&
                captureMergeReceiptRef == other.captureMergeReceiptRef &&
                occurredAtEpochMillis == other.occurredAtEpochMillis
    }

    private class FakeCaptureDraftSessionPort : CaptureDraftSessionPort {
        private val bundles =
            mutableMapOf(
                CaptureDraftSessionId(PRIMARY_DRAFT_ID) to
                    bundle(PRIMARY_DRAFT_ID, asset("asset-primary", "a")),
                CaptureDraftSessionId(FOLLOWING_DRAFT_ID) to
                    bundle(FOLLOWING_DRAFT_ID, asset("asset-following", "b")),
            )
        private val receipts =
            mutableMapOf<MergeKey, CaptureDraftMergeReceipt>()
        private val requestFingerprints = mutableMapOf<MergeKey, String>()
        val mergeCommands = mutableListOf<MergeAdjacentCaptureDraftsCommand>()
        val canonicalReadDraftIds = mutableListOf<CaptureDraftSessionId>()

        override suspend fun importDraft(
            command: ImportCaptureDraftSessionCommand,
        ): CaptureDraftSessionId = error("Not used by organization tests")

        override suspend fun readDraftSummary(
            draftSessionId: CaptureDraftSessionId,
        ): CaptureDraftSummary? = null

        override suspend fun readCanonicalSourceAssets(
            query: ReadCaptureDraftCanonicalAssetsQuery,
        ): CaptureDraftCanonicalAssetBundle? {
            canonicalReadDraftIds += query.draftSessionId
            return bundles[query.draftSessionId]
        }

        override suspend fun mergeAdjacentDrafts(
            command: MergeAdjacentCaptureDraftsCommand,
        ): CaptureDraftMergeReceipt {
            val key = command.key()
            receipts[key]?.let { receipt ->
                check(requestFingerprints[key] == command.canonicalFingerprint)
                return receipt
            }
            mergeCommands += command
            val primary = checkNotNull(bundles[command.primaryDraftSessionId])
            val following = checkNotNull(bundles[command.followingDraftSessionId])
            check(primary.sessionVersion == command.expectedPrimarySessionVersion)
            check(following.sessionVersion == command.expectedFollowingSessionVersion)
            val mergedPages =
                (primary.pages + following.pages).mapIndexed { pageIndex, page ->
                    page.copy(pageIndex = pageIndex)
                }
            val merged =
                CaptureDraftCanonicalAssetBundle(
                    draftSessionId = command.primaryDraftSessionId,
                    revisionNumber = primary.revisionNumber + 1,
                    sessionVersion =
                        captureDraftSessionVersion(
                            primary.revisionNumber + 1,
                            mergedPages.size,
                        ),
                    assetOrderFingerprint =
                        captureAssetOrderFingerprint(
                            command.primaryDraftSessionId,
                            mergedPages,
                        ),
                    pages = mergedPages,
                )
            bundles[command.primaryDraftSessionId] = merged
            val receiptReference = captureMergeSessionReceiptReference(command)
            val receipt =
                CaptureDraftMergeReceipt(
                    receiptReference = receiptReference,
                    batchJobId = command.batchJobId,
                    batchPageIndex = command.batchPageIndex,
                    primaryDraftSessionId = command.primaryDraftSessionId,
                    followingDraftSessionId = command.followingDraftSessionId,
                    mergedDraftSessionId = command.primaryDraftSessionId,
                    assetOrderFingerprint = merged.assetOrderFingerprint,
                    sessionVersion = merged.sessionVersion,
                    sourceAssetCount = merged.pages.size,
                    mergedAtEpochMillis = command.occurredAtEpochMillis,
                    receiptFingerprint =
                        captureDraftMergeReceiptFingerprint(
                            receiptReference = receiptReference,
                            batchJobId = command.batchJobId,
                            batchPageIndex = command.batchPageIndex,
                            primaryDraftSessionId = command.primaryDraftSessionId,
                            followingDraftSessionId = command.followingDraftSessionId,
                            assetOrderFingerprint = merged.assetOrderFingerprint,
                            sessionVersion = merged.sessionVersion,
                            sourceAssetCount = merged.pages.size,
                            mergedAtEpochMillis = command.occurredAtEpochMillis,
                        ),
                )
            receipts[key] = receipt
            requestFingerprints[key] = command.canonicalFingerprint
            return receipt
        }

        override suspend fun readMergeReceipt(
            query: ReadCaptureDraftMergeReceiptQuery,
        ): CaptureDraftMergeReceipt? = receipts[query.key()]

        suspend fun preloadMergeReceipt(
            batchJobId: String,
            batchPageIndex: Int,
            primaryDraftSessionId: CaptureDraftSessionId,
            followingDraftSessionId: CaptureDraftSessionId,
            occurredAtEpochMillis: Long,
        ) {
            val primary = checkNotNull(bundles[primaryDraftSessionId])
            val following = checkNotNull(bundles[followingDraftSessionId])
            mergeAdjacentDrafts(
                MergeAdjacentCaptureDraftsCommand(
                    batchJobId = batchJobId,
                    batchPageIndex = batchPageIndex,
                    primaryDraftSessionId = primaryDraftSessionId,
                    followingDraftSessionId = followingDraftSessionId,
                    expectedPrimarySessionVersion = primary.sessionVersion,
                    expectedFollowingSessionVersion = following.sessionVersion,
                    expectedPrimaryAssetOrderFingerprint = primary.assetOrderFingerprint,
                    expectedFollowingAssetOrderFingerprint =
                        following.assetOrderFingerprint,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                ),
            )
        }

        private fun bundle(
            draftId: String,
            asset: CaptureAssetDescriptor,
        ): CaptureDraftCanonicalAssetBundle {
            val sessionId = CaptureDraftSessionId(draftId)
            val pages = listOf(CaptureDraftCanonicalAssetPage(0, asset))
            return CaptureDraftCanonicalAssetBundle(
                draftSessionId = sessionId,
                revisionNumber = 1,
                sessionVersion = captureDraftSessionVersion(1, 1),
                assetOrderFingerprint = captureAssetOrderFingerprint(sessionId, pages),
                pages = pages,
            )
        }

        private fun asset(
            assetId: String,
            hashDigit: String,
        ) = CaptureAssetDescriptor(
            assetId = assetId,
            contentSha256 = hashDigit.repeat(64),
            relativePath = "captures/$assetId.jpg",
            mimeType = "image/jpeg",
            byteSize = 128,
            width = 100,
            height = 160,
            sourceType = "PHOTO_PICKER",
            createdAtEpochMillis = IMPORTED_AT,
        )

        private data class MergeKey(
            val jobId: String,
            val pageIndex: Int,
            val primaryDraftId: CaptureDraftSessionId,
            val followingDraftId: CaptureDraftSessionId,
        )

        private fun MergeAdjacentCaptureDraftsCommand.key() =
            MergeKey(
                jobId = batchJobId,
                pageIndex = batchPageIndex,
                primaryDraftId = primaryDraftSessionId,
                followingDraftId = followingDraftSessionId,
            )

        private fun ReadCaptureDraftMergeReceiptQuery.key() =
            MergeKey(
                jobId = batchJobId,
                pageIndex = batchPageIndex,
                primaryDraftId = primaryDraftSessionId,
                followingDraftId = followingDraftSessionId,
            )
    }

    private class SuccessfulPageRelationModelTasks(
        private val onExecute: () -> Unit = {},
    ) : ModelTaskRepository {
        var executionCount: Int = 0
            private set

        override suspend fun capabilities(): ProviderCapabilitySnapshot = PROVIDER

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = emptyFlow()

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            executionCount += 1
            onExecute()
            emit(
                ModelTaskSnapshot(
                    taskId = "batch-page-relation-task",
                    request = request,
                    requestFingerprint = ModelTaskFingerprint.of(request),
                    status = ModelTaskStatus.SUCCEEDED,
                    stateVersion = 1,
                    stage = ModelTaskStage.COMPLETE,
                    userMessage = "完成",
                    attemptCount = 1,
                    provider = PROVIDER,
                    output =
                        CaptureAssessmentOutput(
                            CaptureAssessment(
                                decision = CaptureAssessmentDecision.PASS,
                                issues = emptyList(),
                                suggestedActions = emptyList(),
                                modelVersion = "fixture-v1",
                                followingPageRelations =
                                    listOf(CapturePageRelation.SAME_QUESTION),
                            ),
                        ),
                    createdAtEpochMillis = APPROVED_AT,
                    updatedAtEpochMillis = APPROVED_AT,
                ),
            )
        }
    }

    private class FailingPageRelationModelTasks(
        private val onExecute: () -> Unit,
    ) : ModelTaskRepository {
        override suspend fun capabilities(): ProviderCapabilitySnapshot = PROVIDER

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = emptyFlow()

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            onExecute()
            error("fixture model failure")
        }
    }

    private object NoOpBatchImportSourceStaging : BatchImportSourceStaging {
        override fun stage(sourceUris: List<String>): StagedBatchImportSources =
            StagedBatchImportSources(sourceUris)

        override fun stagePdf(sourceUri: String): StagedBatchImportSources =
            error("Not used by organization tests")

        override fun delete(sourceUri: String): Boolean = true

        override fun reconcileOrphans(
            referencedSourceUris: Set<String>,
            nowEpochMillis: Long,
        ) = Unit
    }

    private companion object {
        val SCOPE = SessionScope("learner-batch-test")
        const val JOB_ID = "batch-job"
        const val PRIMARY_DRAFT_ID = "draft-primary"
        const val FOLLOWING_DRAFT_ID = "draft-following"
        const val CREATED_AT = 100L
        const val IMPORTED_AT = 200L
        const val APPROVED_AT = 1_000L
        const val CLAIMED_AT = 10_000L
        const val AFTER_MODEL_DELAY = 50_000L
        val PROVIDER =
            ProviderCapabilitySnapshot(
                providerId = "fixture-provider",
                providerDisplayName = "测试模型",
                modelId = "fixture-v1",
                supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
                supportsImageInput = true,
                supportsStructuredOutput = true,
                supportsStreaming = false,
                executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
                providerConfigurationVersion = "fixture-config-v1",
            )

        fun version(sequence: Long) =
            SessionVersion(
                sequence = sequence,
                fingerprint = sequence.toString(16).padStart(64, '0'),
            )
    }
}

/**
 * Test-classpath-only behavior seam. Construction still consumes the real one-shot registry
 * resource and the returned repository is claimed through the real production owner.
 */
private fun testProductionBatchImportRepository(
    scope: SessionScope,
    sessions: BatchImportSessionPort,
    captureDrafts: CaptureDraftSessionPort,
    processingScope: CoroutineScope,
    sourceStaging: BatchImportSourceStaging,
    modelTasks: ModelTaskRepository,
    nowEpochMillis: () -> Long,
): BatchImportRepository {
    val fixture =
        TestOnlyBatchImportRepositoryConstruction.issue(
            scope,
            sessions,
            captureDrafts,
            modelTasks,
        )
    val resources = fixture.claim().claimForConstruction()
    try {
        val repositoryType =
            Class.forName(
                "com.tingyun.smartmistakebook.core.data.capture." +
                    "ProductionBatchImportRepository",
            )
        val constructor =
            repositoryType.declaredConstructors.single { candidate ->
                candidate.parameterTypes.firstOrNull()?.name ==
                    "com.tingyun.smartmistakebook.core.data.authority." +
                    "CurrentGenerationBatchImportConstructionResources"
            }.apply { isAccessible = true }
        val rawRepository =
            constructor.newInstance(
                resources,
                fixture.context(),
                scope,
                sessions,
                fixture.captureDrafts(),
                processingScope,
                modelTasks,
            ) as BatchImportRepository

        repositoryType.replaceTestCollaborator(
            rawRepository,
            fieldName = "sourceStaging",
            value = sourceStaging,
        )
        repositoryType.replaceTestCollaborator(
            rawRepository,
            fieldName = "nowEpochMillis",
            value = nowEpochMillis,
        )

        val processingJob = checkNotNull(processingScope.coroutineContext[Job])
        val finalClaim =
            resources.registerBuilt(
                rawRepository,
                processingJob,
                fixture.generationIdentity(),
                fixture.context(),
                fixture.learnerIdentity(),
                fixture.sessionOwnerIdentity(),
                fixture.modelOwnerIdentity(),
            )
        val owner = ProductionBatchImportRepositoryFactory.open(finalClaim)
        return try {
            owner.claimRepository().also {
                processingJob.invokeOnCompletion { owner.close() }
            }
        } catch (failure: Throwable) {
            owner.close()
            throw failure
        }
    } finally {
        resources.close()
        fixture.close()
    }
}

private fun Class<*>.replaceTestCollaborator(
    repository: BatchImportRepository,
    fieldName: String,
    value: Any,
) {
    getDeclaredField(fieldName).apply { isAccessible = true }.set(repository, value)
}

private fun <T> List<T>.updated(
    index: Int,
    transform: (T) -> T,
): List<T> = mapIndexed { candidateIndex, value ->
    if (candidateIndex == index) transform(value) else value
}
