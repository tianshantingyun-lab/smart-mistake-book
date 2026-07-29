package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.database.AuthorizeProblemOrganizationWorkCommand
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.ProblemDraftCommitReceipt
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkRecord
import com.tingyun.smartmistakebook.core.database.ProblemOrganizationWorkTransitionCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationConfirmation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.StudyProfileOverview
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrantCodec
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationOutput
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationPlan
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProblemStepKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemOrganizationWorkProcessorTest {
    @Test
    fun missingRequestSnapshotMovesClaimedWorkToWaitingAuthorization() = runBlocking {
        val fixture = DatabaseFixture(work = runningWork(requestSnapshot = null))
        val result = processor(fixture).process(
            workId = WORK_ID,
            leaseOwner = LEASE_OWNER,
            nowEpochMillis = NOW,
        )

        assertEquals(ProblemOrganizationWorkProcessResult.WaitingAuthorization, result)
        assertEquals(1, fixture.claims)
        assertEquals(listOf(300_000L), fixture.claimLeaseDurations)
        assertEquals(1, fixture.waitingTransitions.size)
        assertEquals(
            ProblemOrganizationWorkTransitionCommand(
                workId = WORK_ID,
                expectedStateVersion = 7,
                leaseOwner = LEASE_OWNER,
                occurredAtEpochMillis = NOW,
                failureCode = "EGRESS_AUTHORIZATION_REQUIRED",
                failureMessage = "需要重新确认本次题目整理的发送范围",
            ),
            fixture.waitingTransitions.single(),
        )
    }

    @Test
    fun lostClaimNeverWritesACompetingTransition() = runBlocking {
        val fixture = DatabaseFixture(work = null)

        val result = processor(fixture).process(
            workId = WORK_ID,
            leaseOwner = LEASE_OWNER,
            nowEpochMillis = NOW,
        )

        assertEquals(ProblemOrganizationWorkProcessResult.LostLease, result)
        assertEquals(1, fixture.claims)
        assertTrue(fixture.waitingTransitions.isEmpty())
    }

    @Test
    fun undecodableRequestSnapshotIsAProtocolFailure() = runBlocking {
        val fixture = DatabaseFixture(work = runningWork(requestSnapshot = "not-json"))

        val result = processor(fixture).process(
            workId = WORK_ID,
            leaseOwner = LEASE_OWNER,
            nowEpochMillis = NOW,
        )

        assertEquals(ProblemOrganizationWorkProcessResult.PermanentFailure, result)
        assertEquals(
            ProblemOrganizationWorkTransitionCommand(
                workId = WORK_ID,
                expectedStateVersion = 7,
                leaseOwner = LEASE_OWNER,
                occurredAtEpochMillis = NOW,
                failureCode = "INVALID_REQUEST_SNAPSHOT",
                failureMessage = "组织任务请求快照无法解码",
            ),
            fixture.permanentFailureTransitions.single(),
        )
    }

    @Test
    fun expiredLeaseRejectsAWaitingTransitionAtTheActualCommitTime() = runBlocking {
        val fixture = DatabaseFixture(work = runningWork(requestSnapshot = null))
        val expiredAt = NOW + 300_001
        val result = ProblemOrganizationWorkProcessor(
            database = fixture.port,
            modelTasks = NoOpModelTaskRepository,
            organizations = NoOpMistakeOrganizationRepository,
            clock = { expiredAt },
        ).process(WORK_ID, LEASE_OWNER, NOW)

        assertEquals(ProblemOrganizationWorkProcessResult.LostLease, result)
        assertEquals(expiredAt, fixture.waitingTransitions.single().occurredAtEpochMillis)
    }

    @Test
    fun authorizeDoesNotPrepareWorkOutsideWaitingAuthorization() = runBlocking {
        val fixture = DatabaseFixture(work = runningWork(requestSnapshot = null).copy(status = "PENDING"))
        val organizations = PreparingOrganizationRepository(authorizationRequest())

        val result = ProblemOrganizationWorkProcessor(fixture.port, NoOpModelTaskRepository, organizations)
            .authorizeStoredGrant(WORK_ID, NOW)

        assertEquals(ProblemOrganizationWorkAuthorizationResult.NotWaiting, result)
        assertEquals(0, organizations.prepareCalls)
        assertTrue(fixture.authorizationCommands.isEmpty())
    }

    @Test
    fun authorizeBindsExactEncodedRequestOnlyWhenCasWins() = runBlocking {
        val authorization = authorizationGrant()
        val request = authorizationRequest(authorization)
        val fixture = DatabaseFixture(
            work = runningWork(requestSnapshot = null).copy(
                status = "WAITING_AUTHORIZATION",
                authorizationGrantSnapshot =
                    ProblemOrganizationAuthorizationGrantCodec.encode(authorization),
            ),
        )
        val organizations = PreparingOrganizationRepository(request)

        val result = ProblemOrganizationWorkProcessor(fixture.port, NoOpModelTaskRepository, organizations)
            .authorizeStoredGrant(WORK_ID, NOW)

        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.Authorized(request.requestId, NOW),
            result,
        )
        assertEquals(1, organizations.prepareCalls)
        assertEquals(authorization, organizations.authorization)
        assertEquals(request, ModelTaskCodec.decodeRequest(fixture.authorizationCommands.single().requestSnapshot))
        assertEquals(7, fixture.authorizationCommands.single().expectedStateVersion)
        assertEquals(NOW, fixture.authorizationCommands.single().notBeforeEpochMillis)
    }

    @Test
    fun authorizeCasRaceFailsClosed() = runBlocking {
        val authorization = authorizationGrant()
        val fixture = DatabaseFixture(
            work = runningWork(requestSnapshot = null).copy(
                status = "WAITING_AUTHORIZATION",
                authorizationGrantSnapshot =
                    ProblemOrganizationAuthorizationGrantCodec.encode(authorization),
            ),
            authorizeApplied = false,
        )

        val result = ProblemOrganizationWorkProcessor(
            fixture.port,
            NoOpModelTaskRepository,
            PreparingOrganizationRepository(authorizationRequest(authorization)),
        ).authorizeStoredGrant(WORK_ID, NOW)

        assertEquals(ProblemOrganizationWorkAuthorizationResult.LostLease, result)
    }

    @Test
    fun missingOrMalformedPersistedGrantStaysWaitingWithoutPreparing() = runBlocking {
        val organizations = PreparingOrganizationRepository(authorizationRequest())
        val missing = DatabaseFixture(
            work = runningWork(null).copy(
                status = "WAITING_AUTHORIZATION",
                authorizationGrantSnapshot = null,
            ),
        )
        val malformed = DatabaseFixture(
            work = runningWork(null).copy(
                status = "WAITING_AUTHORIZATION",
                authorizationGrantSnapshot = "{",
            ),
        )

        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                missing.port,
                NoOpModelTaskRepository,
                organizations,
            ).authorizeStoredGrant(WORK_ID, NOW),
        )
        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                malformed.port,
                NoOpModelTaskRepository,
                organizations,
            ).authorizeStoredGrant(WORK_ID, NOW),
        )
        assertEquals(0, organizations.prepareCalls)
    }

    @Test
    fun expiredOrProviderChangedGrantStaysWaiting() = runBlocking {
        val expiredGrant = authorizationGrant().copy(
            approvedAtEpochMillis = 1,
            expiresAtEpochMillis = 2,
        )
        val validGrant = authorizationGrant()
        val organizations = PreparingOrganizationRepository(authorizationRequest(validGrant))
        val expired = DatabaseFixture(
            work = runningWork(null).copy(
                status = "WAITING_AUTHORIZATION",
                authorizationGrantSnapshot =
                    ProblemOrganizationAuthorizationGrantCodec.encode(expiredGrant),
            ),
        )
        val providerChanged = DatabaseFixture(
            work = runningWork(null).copy(
                status = "WAITING_AUTHORIZATION",
                authorizationGrantSnapshot =
                    ProblemOrganizationAuthorizationGrantCodec.encode(validGrant),
            ),
        )

        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                expired.port,
                NoOpModelTaskRepository,
                organizations,
            ).authorizeStoredGrant(WORK_ID, NOW),
        )
        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                providerChanged.port,
                CapabilityModelTaskRepository(provider().copy(modelId = "changed-model")),
                organizations,
            ).authorizeStoredGrant(WORK_ID, NOW),
        )
        assertEquals(0, organizations.prepareCalls)
    }

    @Test
    fun immutableApplyConflictIsPermanentAndNeverRetries() = runBlocking {
        val request = authorizationRequest().copy(requestId = "request-id")
        val fixture = DatabaseFixture(
            work = runningWork(ModelTaskCodec.encodeRequest(request)),
            receipt = commitReceipt(),
        )
        val result = ProblemOrganizationWorkProcessor(
            database = fixture.port,
            modelTasks = SuccessfulModelTaskRepository(request),
            organizations = ImmutableConflictOrganizationRepository,
            clock = { NOW },
        ).process(WORK_ID, LEASE_OWNER, NOW)

        assertEquals(ProblemOrganizationWorkProcessResult.PermanentFailure, result)
        assertEquals(
            "ORGANIZATION_IMMUTABLE_CONFLICT",
            fixture.permanentFailureTransitions.single().failureCode,
        )
        assertTrue(fixture.retryTransitions.isEmpty())
    }

    private fun processor(fixture: DatabaseFixture) = ProblemOrganizationWorkProcessor(
        database = fixture.port,
        modelTasks = NoOpModelTaskRepository,
        organizations = NoOpMistakeOrganizationRepository,
        clock = { NOW },
    )

    private class DatabaseFixture(
        val work: ProblemOrganizationWorkRecord?,
        private val authorizeApplied: Boolean = true,
        private val receipt: ProblemDraftCommitReceipt? = null,
    ) {
        var claims = 0
        val claimLeaseDurations = mutableListOf<Long>()
        val waitingTransitions = mutableListOf<ProblemOrganizationWorkTransitionCommand>()
        val retryTransitions = mutableListOf<ProblemOrganizationWorkTransitionCommand>()
        val permanentFailureTransitions = mutableListOf<ProblemOrganizationWorkTransitionCommand>()
        val authorizationCommands = mutableListOf<AuthorizeProblemOrganizationWorkCommand>()

        val port: StudyDatabasePort = Proxy.newProxyInstance(
            StudyDatabasePort::class.java.classLoader,
            arrayOf(StudyDatabasePort::class.java),
        ) { _, method, args ->
            when (method.name) {
                "claimProblemOrganizationWork" -> {
                    claims += 1
                    claimLeaseDurations += args!![3] as Long
                    work
                }
                "readProblemOrganizationWork" -> work
                "readProblemOrganizationWorkCommitReceipt" -> receipt
                "authorizeProblemOrganizationWork" -> {
                    authorizationCommands += args!![0] as AuthorizeProblemOrganizationWorkCommand
                    authorizeApplied
                }
                "markProblemOrganizationWorkWaitingAuthorization" -> {
                    val command = args!![0] as ProblemOrganizationWorkTransitionCommand
                    waitingTransitions += command
                    command.occursBeforeLeaseExpiry()
                }
                "failProblemOrganizationWorkPermanently" -> {
                    val command = args!![0] as ProblemOrganizationWorkTransitionCommand
                    permanentFailureTransitions += command
                    command.occursBeforeLeaseExpiry()
                }
                "retryProblemOrganizationWork" -> {
                    val command = args!![0] as ProblemOrganizationWorkTransitionCommand
                    retryTransitions += command
                    command.occursBeforeLeaseExpiry()
                }
                "close" -> Unit
                else -> error("Unexpected database call: ${method.name}")
            }
        } as StudyDatabasePort

        private fun ProblemOrganizationWorkTransitionCommand.occursBeforeLeaseExpiry(): Boolean =
            work?.leaseExpiresAtEpochMillis?.let { occurredAtEpochMillis < it } ?: false
    }

    private object NoOpModelTaskRepository : ModelTaskRepository {
        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider()

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = emptyFlow()

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = emptyFlow()
    }

    private class CapabilityModelTaskRepository(
        private val provider: ProviderCapabilitySnapshot,
    ) : ModelTaskRepository {
        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = emptyFlow()

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = emptyFlow()
    }

    private inner class SuccessfulModelTaskRepository(
        private val expectedRequest: ModelTaskRequest,
    ) : ModelTaskRepository {
        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider()

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = emptyFlow()

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> {
            assertEquals(expectedRequest, request)
            return flowOf(
                ModelTaskSnapshot(
                    taskId = "successful-organization-task",
                    request = request,
                    requestFingerprint = ModelTaskFingerprint.of(request),
                    status = ModelTaskStatus.SUCCEEDED,
                    stateVersion = 3,
                    stage = ModelTaskStage.COMPLETE,
                    userMessage = "整理完成",
                    attemptCount = 1,
                    output = successfulOutput(),
                    createdAtEpochMillis = NOW - 10,
                    updatedAtEpochMillis = NOW,
                ),
            )
        }
    }

    private object NoOpMistakeOrganizationRepository : MistakeOrganizationRepository {
        override suspend fun prepare(
            key: MistakeRevisionKey,
            profile: StudyProfileOverview,
            provider: ProviderCapabilitySnapshot,
            attempt: Int,
            occurredAtEpochMillis: Long,
            approvedAtEpochMillis: Long,
        ): MistakeOrganizationPreparation = error("unused")

        override fun observeConfirmed(key: MistakeRevisionKey) = flowOf(error("unused"))

        override suspend fun applySuccessfulOrganization(requestId: String): ProblemOrganizationConfirmation =
            error("unused")

        override suspend fun confirm(
            requestId: String,
            selection: ProblemOrganizationSelection,
            acceptedAtEpochMillis: Long,
        ): ProblemOrganizationConfirmation = error("unused")
    }

    private object ImmutableConflictOrganizationRepository :
        MistakeOrganizationRepository by NoOpMistakeOrganizationRepository {
        override suspend fun applySuccessfulOrganization(
            requestId: String,
        ): ProblemOrganizationConfirmation {
            throw ImmutablePayloadConflictException(
                "problem_organization_stale_confirmation",
                requestId,
            )
        }
    }

    private class PreparingOrganizationRepository(
        private val request: ModelTaskRequest,
    ) : MistakeOrganizationRepository by NoOpMistakeOrganizationRepository {
        var prepareCalls = 0
        var authorization: ProblemOrganizationAuthorizationGrant? = null

        override suspend fun prepareCommittedWork(
            workId: String,
            provider: ProviderCapabilitySnapshot,
            authorization: ProblemOrganizationAuthorizationGrant,
            requestVersion: Long,
            occurredAtEpochMillis: Long,
        ): MistakeOrganizationPreparation {
            prepareCalls += 1
            this.authorization = authorization
            return MistakeOrganizationPreparation(request, emptyList())
        }
    }

    private fun authorizationRequest(
        authorization: ProblemOrganizationAuthorizationGrant = authorizationGrant(),
    ) = ModelTaskRequest(
        requestId = "authorization-request",
        input = ProblemOrganizationV3Input(
            problemId = "problem",
            problemRevisionId = "revision",
            practiceUnitId = "unit",
            subject = SubjectKind.MATH,
            capturedDocument = CapturedQuestionDocument(
                document = QuestionDocument(
                    id = "question",
                    blocks = listOf(ContentBlock.Paragraph("block", "题面")),
                ),
                blockEvidence = listOf(
                    QuestionBlockEvidence(
                        blockId = "block",
                        sourceAssetId = "asset",
                        provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                        reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                    ),
                ),
            ),
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = "asset",
                    sha256 = "a".repeat(64),
                    width = 1_200,
                    height = 1_600,
                    pageIndex = 0,
                ),
            ),
            relationCandidates = emptyList(),
        ),
        occurredAtEpochMillis = NOW,
        egressManifest = authorization.toEgressManifest("revision"),
    )

    private fun authorizationGrant() = ProblemOrganizationAuthorizationGrant(
        authorizationId = "organization-authorization",
        sourceDraftId = "draft",
        providerId = provider().providerId,
        modelId = provider().modelId,
        providerConfigurationVersion = provider().providerConfigurationVersion,
        approvedAtEpochMillis = NOW - 1,
        expiresAtEpochMillis = NOW + 1_000,
        assets = listOf(
            ModelEgressAssetGrant(
                assetId = "asset",
                sha256 = "a".repeat(64),
                byteSize = 1_024,
                width = 1_200,
                height = 1_600,
            ),
        ),
    )

    private fun successfulOutput() = ProblemOrganizationOutput(
        problemId = "problem",
        problemRevisionId = "revision",
        practiceUnitId = "unit",
        plan = ProblemOrganizationPlan(
            summaryMarkdown = "这道题考查函数关系。",
            reviewPriorityMarkdown = "按当前安排复习。",
            targetedEvidenceLabels = emptyList(),
            classifications = listOf(
                ProblemClassificationSuggestion(
                    dimension = ClassificationDimension.CHAPTER,
                    displayName = "函数",
                    rationaleMarkdown = "属于函数板块。",
                    confidence = 0.95,
                ),
                ProblemClassificationSuggestion(
                    dimension = ClassificationDimension.KNOWLEDGE,
                    displayName = "函数关系",
                    rationaleMarkdown = "需要判断函数关系。",
                    confidence = 0.95,
                ),
            ),
            relations = emptyList(),
            schemaVersion = 3,
            atomicKnowledge = listOf(
                AtomicKnowledgeSuggestion(
                    referenceId = "atom-1",
                    canonicalName = "判断函数关系",
                    aliases = emptyList(),
                    kind = KnowledgeNodeKind.REASONING,
                    parentKnowledgeDisplayName = "函数关系",
                    matchedKnowledgeNodeId = null,
                    prerequisiteReferenceIds = emptyList(),
                    observableOutcomeMarkdown = "能根据题意判断函数关系。",
                    boundaryMarkdown = "只处理本题给出的函数关系。",
                    confidence = 0.95,
                ),
            ),
            stepAttributions = listOf(
                ProblemStepKnowledgeAttribution(
                    stepOrdinal = 1,
                    stepSummaryMarkdown = "判断函数关系。",
                    atomicReferenceIds = listOf("atom-1"),
                ),
            ),
        ),
        modelVersion = "model-v3",
    )

    private fun commitReceipt() = ProblemDraftCommitReceipt(
        commandId = "commit-receipt",
        payloadFingerprint = "b".repeat(64),
        draftId = "draft",
        draftRevisionNumber = 1,
        problemId = "problem",
        problemRevisionId = "revision",
        practiceUnitId = "unit",
        errorBookEntryId = "entry",
        committedAtEpochMillis = NOW - 20,
    )

    private fun runningWork(requestSnapshot: String?) = ProblemOrganizationWorkRecord(
        workId = WORK_ID,
        commitReceiptCommandId = "commit-receipt",
        status = "RUNNING",
        stateVersion = 7,
        attemptCount = 1,
        notBeforeEpochMillis = NOW,
        requestId = "request-id",
        requestSnapshot = requestSnapshot,
        leaseOwner = LEASE_OWNER,
        leaseExpiresAtEpochMillis = NOW + 300_000,
        failureCode = null,
        failureMessage = null,
        createdAtEpochMillis = NOW - 1,
        updatedAtEpochMillis = NOW,
    )

    private companion object {
        const val WORK_ID = "organization-work"
        const val LEASE_OWNER = "processor"
        const val NOW = 1_000L

        fun provider() = ProviderCapabilitySnapshot(
            providerId = "provider",
            providerDisplayName = "Provider",
            modelId = "model",
            supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
        )
    }
}
