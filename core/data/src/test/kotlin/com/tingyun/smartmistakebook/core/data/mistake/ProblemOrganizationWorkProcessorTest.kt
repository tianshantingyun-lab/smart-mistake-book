package com.tingyun.smartmistakebook.core.data.mistake

import com.tingyun.smartmistakebook.core.data.session.AuthorizeProblemOrganizationWorkSessionCommand
import com.tingyun.smartmistakebook.core.data.session.ClaimProblemOrganizationWorkSessionCommand
import com.tingyun.smartmistakebook.core.data.session.OrganizationSourceCommitSessionReceipt
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkRecoveryQuery
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionMutationResult
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionPort
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionReadQuery
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionStatus
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionTransition
import com.tingyun.smartmistakebook.core.data.session.SessionMutationDisposition
import com.tingyun.smartmistakebook.core.data.session.SessionMutationReceipt
import com.tingyun.smartmistakebook.core.data.session.SessionOpaquePayload
import com.tingyun.smartmistakebook.core.data.session.SessionOperationIdentity
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.SessionVersion
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRevokedException
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.MistakeRevisionKey
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationConfirmation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationImmutableConflictException
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationSelection
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionAuthority
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionOutcome
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemOrganizationWorkProcessorTest {
    @Test
    fun revokedGenerationCannotCrossASuspendedReadIntoAClaimMutation() = runBlocking {
        val fixture = SessionFixture(work = runningWork(requestSnapshot = null))
        val readStarted = CompletableDeferred<Unit>()
        val resumeRead = CompletableDeferred<Unit>()
        var executionIsCurrent = true
        val suspendingSessions =
            object : ProblemOrganizationWorkSessionPort by fixture {
                override suspend fun read(
                    query: ProblemOrganizationWorkSessionReadQuery,
                ): ProblemOrganizationWorkSessionSnapshot? {
                    readStarted.complete(Unit)
                    resumeRead.await()
                    return fixture.read(query)
                }
            }
        val processor =
            ProblemOrganizationWorkProcessor(
                scope = TEST_SCOPE,
                sessions = suspendingSessions,
                modelTasks = NoOpModelTaskRepository,
                organizations = NoOpMistakeOrganizationRepository,
                executionIsCurrent = { executionIsCurrent },
            )

        val outcome =
            async {
                runCatching {
                    processor.process(
                        workId = WORK_ID,
                        leaseOwner = LEASE_OWNER,
                        nowEpochMillis = NOW,
                    )
                }
            }
        readStarted.await()
        executionIsCurrent = false
        resumeRead.complete(Unit)

        assertTrue(
            outcome.await().exceptionOrNull() is
                ProductionProblemOrganizationExecutionRevokedException,
        )
        assertEquals(0, fixture.claims)
    }

    @Test
    fun missingRequestSnapshotMovesClaimedWorkToWaitingAuthorization() = runBlocking {
        val fixture = SessionFixture(work = runningWork(requestSnapshot = null))
        val result = processor(fixture).process(
            workId = WORK_ID,
            leaseOwner = LEASE_OWNER,
            nowEpochMillis = NOW,
        )

        assertEquals(ProblemOrganizationWorkProcessResult.WaitingAuthorization, result)
        assertEquals(1, fixture.claims)
        assertEquals(listOf(300_000L), fixture.claimLeaseDurations)
        assertEquals(1, fixture.waitingTransitions.size)
        val transition = fixture.waitingTransitions.single()
        assertEquals(7, transition.expectedVersion.sequence)
        assertEquals(NOW, transition.occurredAtEpochMillis)
        assertEquals("EGRESS_AUTHORIZATION_REQUIRED", transition.failureCode)
        assertEquals("需要重新确认本次题目整理的发送范围", transition.failureMessage)
    }

    @Test
    fun lostClaimNeverWritesACompetingTransition() = runBlocking {
        val fixture = SessionFixture(work = null)

        val result = processor(fixture).process(
            workId = WORK_ID,
            leaseOwner = LEASE_OWNER,
            nowEpochMillis = NOW,
        )

        assertEquals(ProblemOrganizationWorkProcessResult.LostLease, result)
        assertEquals(0, fixture.claims)
        assertTrue(fixture.waitingTransitions.isEmpty())
    }

    @Test
    fun undecodableRequestSnapshotIsAProtocolFailure() = runBlocking {
        val fixture = SessionFixture(work = runningWork(requestSnapshot = "not-json"))

        val result = processor(fixture).process(
            workId = WORK_ID,
            leaseOwner = LEASE_OWNER,
            nowEpochMillis = NOW,
        )

        assertEquals(ProblemOrganizationWorkProcessResult.PermanentFailure, result)
        val transition = fixture.permanentFailureTransitions.single()
        assertEquals(7, transition.expectedVersion.sequence)
        assertEquals(NOW, transition.occurredAtEpochMillis)
        assertEquals("INVALID_REQUEST_SNAPSHOT", transition.failureCode)
        assertEquals("组织任务请求快照无法解码", transition.failureMessage)
    }

    @Test
    fun expiredLeaseRejectsAWaitingTransitionAtTheActualCommitTime() = runBlocking {
        val fixture = SessionFixture(work = runningWork(requestSnapshot = null))
        val expiredAt = NOW + 300_001
        val result = ProblemOrganizationWorkProcessor(
            scope = TEST_SCOPE,
            sessions = fixture,
            modelTasks = NoOpModelTaskRepository,
            organizations = NoOpMistakeOrganizationRepository,
            clock = { expiredAt },
        ).process(WORK_ID, LEASE_OWNER, NOW)

        assertEquals(ProblemOrganizationWorkProcessResult.LostLease, result)
        assertEquals(expiredAt, fixture.waitingTransitions.single().occurredAtEpochMillis)
    }

    @Test
    fun authorizeDoesNotPrepareWorkOutsideWaitingAuthorization() = runBlocking {
        val fixture =
            SessionFixture(
                work =
                    runningWork(requestSnapshot = null).copy(
                        status = ProblemOrganizationWorkSessionStatus.PENDING,
                    ),
            )
        val organizations = PreparingOrganizationRepository(authorizationRequest())

        val result = ProblemOrganizationWorkProcessor(
            TEST_SCOPE,
            fixture,
            NoOpModelTaskRepository,
            organizations,
        ).authorizeStoredGrant(WORK_ID, NOW)

        assertEquals(ProblemOrganizationWorkAuthorizationResult.NotWaiting, result)
        assertEquals(0, organizations.prepareCalls)
        assertTrue(fixture.authorizationCommands.isEmpty())
    }

    @Test
    fun authorizeBindsExactEncodedRequestOnlyWhenCasWins() = runBlocking {
        val authorization = authorizationGrant()
        val request = authorizationRequest(authorization)
        val fixture = SessionFixture(
            work = runningWork(requestSnapshot = null).copy(
                status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
                authorizationPayload =
                    SessionOpaquePayload(
                        "organization-authorization-v1",
                        ProblemOrganizationAuthorizationGrantCodec.encode(authorization),
                    ),
            ),
        )
        val organizations = PreparingOrganizationRepository(request)

        val result = ProblemOrganizationWorkProcessor(
            TEST_SCOPE,
            fixture,
            NoOpModelTaskRepository,
            organizations,
        ).authorizeStoredGrant(WORK_ID, NOW)

        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.Authorized(request.requestId, NOW),
            result,
        )
        assertEquals(1, organizations.prepareCalls)
        assertEquals(authorization, organizations.authorization)
        assertEquals(
            request,
            ModelTaskCodec.decodeRequest(fixture.authorizationCommands.single().requestPayload.content),
        )
        assertEquals(7, fixture.authorizationCommands.single().expectedVersion.sequence)
        assertEquals(NOW, fixture.authorizationCommands.single().notBeforeEpochMillis)
    }

    @Test
    fun authorizeCasRaceFailsClosed() = runBlocking {
        val authorization = authorizationGrant()
        val fixture = SessionFixture(
            work = runningWork(requestSnapshot = null).copy(
                status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
                authorizationPayload =
                    SessionOpaquePayload(
                        "organization-authorization-v1",
                        ProblemOrganizationAuthorizationGrantCodec.encode(authorization),
                    ),
            ),
            authorizeApplied = false,
        )

        val result = ProblemOrganizationWorkProcessor(
            TEST_SCOPE,
            fixture,
            NoOpModelTaskRepository,
            PreparingOrganizationRepository(authorizationRequest(authorization)),
        ).authorizeStoredGrant(WORK_ID, NOW)

        assertEquals(ProblemOrganizationWorkAuthorizationResult.LostLease, result)
    }

    @Test
    fun missingOrMalformedPersistedGrantStaysWaitingWithoutPreparing() = runBlocking {
        val organizations = PreparingOrganizationRepository(authorizationRequest())
        val missing = SessionFixture(
            work = runningWork(null).copy(
                status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
                authorizationPayload = null,
            ),
        )
        val malformed = SessionFixture(
            work = runningWork(null).copy(
                status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
                authorizationPayload =
                    SessionOpaquePayload("organization-authorization-v1", "{"),
            ),
        )

        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                TEST_SCOPE,
                missing,
                NoOpModelTaskRepository,
                organizations,
            ).authorizeStoredGrant(WORK_ID, NOW),
        )
        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                TEST_SCOPE,
                malformed,
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
        val expired = SessionFixture(
            work = runningWork(null).copy(
                status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
                authorizationPayload =
                    SessionOpaquePayload(
                        "organization-authorization-v1",
                        ProblemOrganizationAuthorizationGrantCodec.encode(expiredGrant),
                    ),
            ),
        )
        val providerChanged = SessionFixture(
            work = runningWork(null).copy(
                status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
                authorizationPayload =
                    SessionOpaquePayload(
                        "organization-authorization-v1",
                        ProblemOrganizationAuthorizationGrantCodec.encode(validGrant),
                    ),
            ),
        )

        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                TEST_SCOPE,
                expired,
                NoOpModelTaskRepository,
                organizations,
            ).authorizeStoredGrant(WORK_ID, NOW),
        )
        assertEquals(
            ProblemOrganizationWorkAuthorizationResult.WaitingAuthorization,
            ProblemOrganizationWorkProcessor(
                TEST_SCOPE,
                providerChanged,
                CapabilityModelTaskRepository(provider().copy(modelId = "changed-model")),
                organizations,
            ).authorizeStoredGrant(WORK_ID, NOW),
        )
        assertEquals(0, organizations.prepareCalls)
    }

    @Test
    fun immutableApplyConflictIsPermanentAndNeverRetries() = runBlocking {
        val request = authorizationRequest().copy(requestId = "request-id")
        val fixture = SessionFixture(
            work = runningWork(ModelTaskCodec.encodeRequest(request)),
            receipt = sourceReceipt(),
        )
        val result = ProblemOrganizationWorkProcessor(
            scope = TEST_SCOPE,
            sessions = fixture,
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

    @Test
    fun successfulResultUsesOneAtomicCompletionAuthority() = runBlocking {
        val request = authorizationRequest().copy(requestId = "request-id")
        val fixture = SessionFixture(
            work = runningWork(ModelTaskCodec.encodeRequest(request)),
            receipt = sourceReceipt(),
        )
        val organizations = AtomicCompletionOrganizationRepository()
        val ruleHook = CapturingTrustedAnswerRuleCompletionPort()

        val result = ProblemOrganizationWorkProcessor(
            scope = TEST_SCOPE,
            sessions = fixture,
            modelTasks = SuccessfulModelTaskRepository(request),
            organizations = organizations,
            trustedAnswerRules = ruleHook,
            clock = { NOW },
        ).process(WORK_ID, LEASE_OWNER, NOW)

        assertEquals(ProblemOrganizationWorkProcessResult.Succeeded, result)
        assertEquals(
            ProblemOrganizationWorkCompletionAuthority(
                workId = WORK_ID,
                expectedStateVersion = 7,
                leaseOwner = LEASE_OWNER,
                requestId = request.requestId,
            ),
            organizations.authority,
        )
        assertTrue(fixture.retryTransitions.isEmpty())
        assertTrue(fixture.permanentFailureTransitions.isEmpty())
        assertEquals(
            listOf(TEST_SCOPE.learnerId to (request.input as ProblemOrganizationV3Input).problemRevisionId),
            ruleHook.revisions,
        )
        assertTrue(
            ProblemOrganizationTrustedAnswerRuleCompletionPort::class.java.methods
                .flatMap { it.parameterTypes.asList() }
                .none { it == ModelTaskSnapshot::class.java || it == ProblemOrganizationOutput::class.java },
        )
    }

    @Test
    fun unavailableTrustedRuleAdmissionKeepsOrganizationSavedAndEvidenceClosed() = runBlocking {
        val request = authorizationRequest().copy(requestId = "request-id")
        val fixture =
            SessionFixture(
                work = runningWork(ModelTaskCodec.encodeRequest(request)),
                receipt = sourceReceipt(),
            )

        val result =
            ProblemOrganizationWorkProcessor(
                scope = TEST_SCOPE,
                sessions = fixture,
                modelTasks = SuccessfulModelTaskRepository(request),
                organizations = AtomicCompletionOrganizationRepository(),
                trustedAnswerRules =
                    ProblemOrganizationTrustedAnswerRuleCompletionPort { _, _ ->
                        error("trusted credential unavailable")
                    },
                clock = { NOW },
            ).process(WORK_ID, LEASE_OWNER, NOW)

        assertEquals(ProblemOrganizationWorkProcessResult.Succeeded, result)
        assertTrue(fixture.retryTransitions.isEmpty())
        assertTrue(fixture.permanentFailureTransitions.isEmpty())
    }

    @Test
    fun lostAtomicCompletionAuthorityNeverFallsBackToASecondTransition() = runBlocking {
        val request = authorizationRequest().copy(requestId = "request-id")
        val fixture = SessionFixture(
            work = runningWork(ModelTaskCodec.encodeRequest(request)),
            receipt = sourceReceipt(),
        )
        val organizations = AtomicCompletionOrganizationRepository(
            outcome = ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY,
        )

        val result = ProblemOrganizationWorkProcessor(
            scope = TEST_SCOPE,
            sessions = fixture,
            modelTasks = SuccessfulModelTaskRepository(request),
            organizations = organizations,
            clock = { NOW },
        ).process(WORK_ID, LEASE_OWNER, NOW)

        assertEquals(ProblemOrganizationWorkProcessResult.LostLease, result)
        assertTrue(fixture.waitingTransitions.isEmpty())
        assertTrue(fixture.retryTransitions.isEmpty())
        assertTrue(fixture.permanentFailureTransitions.isEmpty())
    }

    private fun processor(fixture: SessionFixture) = ProblemOrganizationWorkProcessor(
        scope = TEST_SCOPE,
        sessions = fixture,
        modelTasks = NoOpModelTaskRepository,
        organizations = NoOpMistakeOrganizationRepository,
        clock = { NOW },
    )

    private class SessionFixture(
        private val work: ProblemOrganizationWorkSessionSnapshot?,
        private val authorizeApplied: Boolean = true,
        private val receipt: OrganizationSourceCommitSessionReceipt? = null,
    ) : ProblemOrganizationWorkSessionPort {
        var claims = 0
        val claimLeaseDurations = mutableListOf<Long>()
        val waitingTransitions =
            mutableListOf<ProblemOrganizationWorkSessionTransition.WaitForAuthorization>()
        val retryTransitions = mutableListOf<ProblemOrganizationWorkSessionTransition.Retry>()
        val permanentFailureTransitions =
            mutableListOf<ProblemOrganizationWorkSessionTransition.FailPermanently>()
        val authorizationCommands =
            mutableListOf<AuthorizeProblemOrganizationWorkSessionCommand>()
        private var current = work?.beforeClaim()

        override fun observeSchedulable(
            scope: SessionScope,
        ): Flow<List<ProblemOrganizationWorkSessionSnapshot>> = flowOf(
            listOfNotNull(current),
        )

        override suspend fun read(
            query: ProblemOrganizationWorkSessionReadQuery,
        ): ProblemOrganizationWorkSessionSnapshot? =
            current?.takeIf { snapshot ->
                when (query) {
                    is ProblemOrganizationWorkSessionReadQuery.ByWorkId ->
                        snapshot.workId == query.workId
                    is ProblemOrganizationWorkSessionReadQuery.ByRequestId ->
                        snapshot.requestId == query.requestId
                    is ProblemOrganizationWorkSessionReadQuery.BySourceReceiptId ->
                        snapshot.sourceCommitReceiptId == query.sourceCommitReceiptId
                }
            }

        override suspend fun readSourceReceipt(
            scope: SessionScope,
            sourceCommitReceiptId: String,
        ): OrganizationSourceCommitSessionReceipt? =
            receipt?.takeIf { it.scope == scope && it.receiptId == sourceCommitReceiptId }

        override suspend fun authorize(
            command: AuthorizeProblemOrganizationWorkSessionCommand,
        ): ProblemOrganizationWorkSessionMutationResult {
            authorizationCommands += command
            if (!authorizeApplied) {
                return result(command.operation, SessionMutationDisposition.RELOAD_REQUIRED, current)
            }
            val authorized =
                checkNotNull(current).copy(
                    status = ProblemOrganizationWorkSessionStatus.PENDING,
                    version = nextVersion(checkNotNull(current).version),
                    requestId = command.operation.requestId,
                    requestPayload = command.requestPayload,
                    authorizationPayload = null,
                    notBeforeEpochMillis = command.notBeforeEpochMillis,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                )
            current = authorized
            return result(command.operation, SessionMutationDisposition.APPLIED, authorized)
        }

        override suspend fun claim(
            command: ClaimProblemOrganizationWorkSessionCommand,
        ): ProblemOrganizationWorkSessionMutationResult {
            claims += 1
            claimLeaseDurations += command.leaseDurationMillis
            val claimed = work
                ?: return result(command.operation, SessionMutationDisposition.NOT_FOUND, null)
            current = claimed
            return result(command.operation, SessionMutationDisposition.APPLIED, claimed)
        }

        override suspend fun transition(
            command: ProblemOrganizationWorkSessionTransition,
        ): ProblemOrganizationWorkSessionMutationResult {
            when (command) {
                is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
                    waitingTransitions += command
                is ProblemOrganizationWorkSessionTransition.Retry -> retryTransitions += command
                is ProblemOrganizationWorkSessionTransition.FailPermanently ->
                    permanentFailureTransitions += command
                is ProblemOrganizationWorkSessionTransition.Complete -> Unit
            }
            val before = current
            if (
                before == null ||
                before.leaseExpiresAtEpochMillis?.let {
                    command.occurredAtEpochMillis >= it
                } != false
            ) {
                return result(
                    command.operation,
                    SessionMutationDisposition.RELOAD_REQUIRED,
                    before,
                )
            }
            val transitioned =
                when (command) {
                    is ProblemOrganizationWorkSessionTransition.WaitForAuthorization ->
                        before.copy(
                            status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
                            requestId = null,
                            requestPayload = null,
                            failureCode = command.failureCode,
                            failureMessage = command.failureMessage,
                        )
                    is ProblemOrganizationWorkSessionTransition.Retry ->
                        before.copy(
                            status = ProblemOrganizationWorkSessionStatus.RETRY,
                            notBeforeEpochMillis = command.notBeforeEpochMillis,
                            failureCode = command.failureCode,
                            failureMessage = command.failureMessage,
                        )
                    is ProblemOrganizationWorkSessionTransition.FailPermanently ->
                        before.copy(
                            status = ProblemOrganizationWorkSessionStatus.PERMANENT_FAILURE,
                            failureCode = command.failureCode,
                            failureMessage = command.failureMessage,
                        )
                    is ProblemOrganizationWorkSessionTransition.Complete ->
                        before.copy(status = ProblemOrganizationWorkSessionStatus.SUCCEEDED)
                }.copy(
                    version = nextVersion(before.version),
                    leaseOwner = null,
                    leaseExpiresAtEpochMillis = null,
                    updatedAtEpochMillis = command.occurredAtEpochMillis,
                )
            current = transitioned
            return result(command.operation, SessionMutationDisposition.APPLIED, transitioned)
        }

        override suspend fun readRunningRecoveryPage(
            query: ProblemOrganizationWorkRecoveryQuery,
        ): List<ProblemOrganizationWorkSessionSnapshot> =
            listOfNotNull(
                current?.takeIf { it.status == ProblemOrganizationWorkSessionStatus.RUNNING },
            )

        private fun result(
            operation: SessionOperationIdentity,
            disposition: SessionMutationDisposition,
            snapshot: ProblemOrganizationWorkSessionSnapshot?,
        ) = ProblemOrganizationWorkSessionMutationResult(
            receipt =
                SessionMutationReceipt(
                    operation = operation,
                    disposition = disposition,
                    currentVersion = snapshot?.version,
                    recordedAtEpochMillis = NOW,
                ),
            snapshot = snapshot,
        )

        private fun ProblemOrganizationWorkSessionSnapshot.beforeClaim() =
            if (status == ProblemOrganizationWorkSessionStatus.RUNNING) {
                copy(
                    status = ProblemOrganizationWorkSessionStatus.PENDING,
                    version = SessionVersion(
                        sequence = (version.sequence - 1).coerceAtLeast(0),
                        fingerprint = "6".repeat(64),
                    ),
                    attemptCount = (attemptCount - 1).coerceAtLeast(0),
                    leaseOwner = null,
                    leaseExpiresAtEpochMillis = null,
                )
            } else {
                this
            }

        private fun nextVersion(version: SessionVersion) =
            SessionVersion(
                sequence = version.sequence + 1,
                fingerprint = "8".repeat(64),
            )
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
        override suspend fun completeSuccessfulOrganizationWork(
            authority: ProblemOrganizationWorkCompletionAuthority,
        ): ProblemOrganizationWorkCompletionOutcome {
            throw ProblemOrganizationImmutableConflictException(
                "problem organization conflicts with immutable state: ${authority.requestId}",
            )
        }
    }

    private class AtomicCompletionOrganizationRepository(
        private val outcome: ProblemOrganizationWorkCompletionOutcome =
            ProblemOrganizationWorkCompletionOutcome.COMPLETED,
    ) :
        MistakeOrganizationRepository by NoOpMistakeOrganizationRepository {
        var authority: ProblemOrganizationWorkCompletionAuthority? = null

        override suspend fun completeSuccessfulOrganizationWork(
            authority: ProblemOrganizationWorkCompletionAuthority,
        ): ProblemOrganizationWorkCompletionOutcome {
            this.authority = authority
            return outcome
        }
    }

    private class CapturingTrustedAnswerRuleCompletionPort :
        ProblemOrganizationTrustedAnswerRuleCompletionPort {
        val revisions = mutableListOf<Pair<String, String>>()

        override suspend fun afterSuccessfulOrganization(
            learnerId: String,
            problemRevisionId: String,
        ) {
            revisions += learnerId to problemRevisionId
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
    ): ModelTaskRequest {
        val requestId = "authorization-request"
        val input = ProblemOrganizationV3Input(
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
        )
        return ModelTaskRequest(
            requestId = requestId,
            input = input,
            occurredAtEpochMillis = NOW,
            egressManifest = authorization.toEgressManifest(requestId, input),
        )
    }

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

    private fun sourceReceipt() = OrganizationSourceCommitSessionReceipt(
        scope = TEST_SCOPE,
        receiptId = "commit-receipt",
        payloadFingerprint = "b".repeat(64),
        recordedAtEpochMillis = NOW - 20,
    )

    private fun runningWork(requestSnapshot: String?) = ProblemOrganizationWorkSessionSnapshot(
        scope = TEST_SCOPE,
        workId = WORK_ID,
        sourceCommitReceiptId = "commit-receipt",
        status = ProblemOrganizationWorkSessionStatus.RUNNING,
        version = SessionVersion(7, "7".repeat(64)),
        attemptCount = 1,
        notBeforeEpochMillis = NOW,
        requestId = "request-id",
        requestPayload =
            requestSnapshot?.let {
                SessionOpaquePayload("organization-request-v1", it)
            },
        authorizationPayload = null,
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

        val TEST_SCOPE = SessionScope("learner")

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
