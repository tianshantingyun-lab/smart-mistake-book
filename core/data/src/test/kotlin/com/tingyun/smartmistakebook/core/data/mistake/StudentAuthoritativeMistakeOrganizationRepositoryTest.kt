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
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.SessionVersion
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRevokedException
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationPreparation
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionAuthority
import com.tingyun.smartmistakebook.core.domain.ProblemOrganizationWorkCompletionOutcome
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelTaskCodec
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationV3Input
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentProblemOrganizationCommitFence
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentAuthoritativeMistakeOrganizationRepositoryTest {
    @Test
    fun preparationUsesOnlyTheExactSessionReceiptAndStudentOwner() = runBlocking {
        val request = request()
        val sessions = FakeWorkSessions(waitingWork())
        val owner =
            FakeStudentOrganizationOwner(
                prepared =
                    PreparedStudentProblemOrganization(
                        sourceReceiptId = SOURCE_RECEIPT_ID,
                        sourceReceiptPayloadFingerprint = SOURCE_FINGERPRINT,
                        preparation = MistakeOrganizationPreparation(request, emptyList()),
                    ),
                reviewOutcome = applied(request),
            )
        val repository = repository(owner, sessions)

        val result =
            repository.prepareCommittedWork(
                workId = WORK_ID,
                provider = provider(),
                authorization = authorization(),
                requestVersion = WAITING_VERSION,
                occurredAtEpochMillis = NOW,
            )

        assertEquals(request, result.request)
        val command = checkNotNull(owner.prepareCommand)
        assertEquals(TEST_SCOPE, command.scope)
        assertEquals(WORK_ID, command.workId)
        assertEquals(SOURCE_RECEIPT_ID, command.sourceReceipt.receiptId)
        assertEquals(SOURCE_FINGERPRINT, command.sourceReceipt.payloadFingerprint)
    }

    @Test
    fun reviewedOwnerWriteCompletesOnceAndReplayDoesNotWriteAgain() = runBlocking {
        val request = request()
        val sessions = FakeWorkSessions(runningWork(request))
        val owner =
            FakeStudentOrganizationOwner(
                prepared = unusedPreparation(request),
                reviewOutcome = applied(request),
            )
        val repository = repository(owner, sessions)
        val authority = authority(request)

        assertEquals(
            ProblemOrganizationWorkCompletionOutcome.COMPLETED,
            repository.completeSuccessfulOrganizationWork(authority),
        )
        assertEquals(1, owner.reviewCalls)
        assertEquals(1, sessions.transitions.size)
        assertTrue(sessions.work?.status == ProblemOrganizationWorkSessionStatus.SUCCEEDED)
        val review = checkNotNull(owner.reviewCommand)
        assertEquals(ModelTaskFingerprint.of(request), review.requestCanonicalFingerprint)
        assertEquals(SOURCE_FINGERPRINT, review.sourceReceipt.payloadFingerprint)

        assertEquals(
            ProblemOrganizationWorkCompletionOutcome.COMPLETED,
            repository.completeSuccessfulOrganizationWork(authority),
        )
        assertEquals(1, owner.reviewCalls)
        assertEquals(1, sessions.transitions.size)
    }

    @Test
    fun lateOwnerResultCannotCompleteWorkNowHeldByAnotherLease() = runBlocking {
        val request = request()
        val sessions = FakeWorkSessions(runningWork(request))
        val owner =
            FakeStudentOrganizationOwner(
                prepared = unusedPreparation(request),
                reviewOutcome = applied(request),
                afterReview = {
                    sessions.work =
                        checkNotNull(sessions.work).copy(
                            version = SessionVersion(RUNNING_VERSION + 1, "9".repeat(64)),
                            leaseOwner = "other-worker",
                        )
                },
            )
        val repository = repository(owner, sessions)

        assertEquals(
            ProblemOrganizationWorkCompletionOutcome.LOST_AUTHORITY,
            repository.completeSuccessfulOrganizationWork(authority(request)),
        )
        assertEquals(1, owner.reviewCalls)
        assertTrue(sessions.transitions.isEmpty())
        assertEquals("other-worker", sessions.work?.leaseOwner)
    }

    @Test
    fun revocationAfterStudentWritePreventsTheFollowingSessionWrite() = runBlocking {
        val request = request()
        val sessions = FakeWorkSessions(runningWork(request))
        val owner =
            FakeStudentOrganizationOwner(
                prepared = unusedPreparation(request),
                reviewOutcome = applied(request),
            )
        val repository = repository(owner, sessions)
        var guardChecks = 0
        val requireCurrentExecution: () -> Unit = {
            guardChecks += 1
            if (guardChecks == 6) {
                throw ProductionProblemOrganizationExecutionRevokedException()
            }
        }

        val failure =
            runCatching {
                (repository as GuardedProblemOrganizationWorkCompletionPort)
                    .completeSuccessfulOrganizationWork(
                        authority = authority(request),
                        requireCurrentExecution = requireCurrentExecution,
                        commitFence =
                            currentExecutionCheckCommitFence(requireCurrentExecution),
                    )
            }.exceptionOrNull()

        assertTrue(failure is ProductionProblemOrganizationExecutionRevokedException)
        assertEquals(1, owner.reviewCalls)
        assertTrue(sessions.transitions.isEmpty())
        assertTrue(sessions.work?.status == ProblemOrganizationWorkSessionStatus.RUNNING)
    }

    @Test
    fun ownerNonWritesKeepRetryableAndTerminalRejectionsDistinct() = runBlocking {
        val request = request()
        val retrySessions = FakeWorkSessions(runningWork(request))
        val retryOwner =
            FakeStudentOrganizationOwner(
                prepared = unusedPreparation(request),
                reviewOutcome =
                    StudentProblemOrganizationReviewWriteOutcome.NotApplied(
                        disposition = StudentProblemOrganizationNonApplication.RETRYABLE,
                        reasonCode = "OWNER_TEMPORARILY_UNAVAILABLE",
                    ),
            )
        val retryFailure =
            runCatching {
                repository(retryOwner, retrySessions)
                    .completeSuccessfulOrganizationWork(authority(request))
            }.exceptionOrNull()
        assertTrue(retryFailure is StudentProblemOrganizationReviewUnavailableException)
        assertTrue(retrySessions.transitions.isEmpty())

        val terminalSessions = FakeWorkSessions(runningWork(request))
        val terminalOwner =
            FakeStudentOrganizationOwner(
                prepared = unusedPreparation(request),
                reviewOutcome =
                    StudentProblemOrganizationReviewWriteOutcome.NotApplied(
                        disposition = StudentProblemOrganizationNonApplication.TERMINAL,
                        reasonCode = "LOW_CONFIDENCE",
                    ),
            )
        val terminalFailure =
            runCatching {
                repository(terminalOwner, terminalSessions)
                    .completeSuccessfulOrganizationWork(authority(request))
            }.exceptionOrNull()
        assertTrue(terminalFailure is StudentProblemOrganizationReviewRejectedException)
        assertTrue(terminalSessions.transitions.isEmpty())
        assertEquals(1, retryOwner.reviewCalls)
        assertEquals(1, terminalOwner.reviewCalls)
    }

    @Test
    fun productionFactoryExposesOnlyTheNarrowOwnerAndWorkSessionPorts() {
        val method =
            StudentAuthoritativeMistakeOrganizationRepositoryFactory::class.java
                .declaredMethods
                .single { it.name == "createProduction" }
        assertEquals(
            listOf(
                StudentProblemOrganizationOwnerPort::class.java,
                ProblemOrganizationWorkSessionPort::class.java,
            ),
            method.parameterTypes.toList(),
        )

        val source = productionSource().readText()
        listOf(
            "StudyDatabasePort",
            "StudentMistakeRuntimeCapabilities",
            "StudentMistakeStore",
            "LegacyPreCutoverMistakeOrganizationBusinessPort",
            "core.database",
        ).forEach { forbidden ->
            assertFalse(
                "Production organization repository contains broad dependency: $forbidden",
                forbidden in source,
            )
        }
        assertTrue("Production factory is missing", "fun createProduction(" in source)
        assertFalse(
            "Production source must not call the legacy factory",
            "createLegacyDuringAuthorityMigration(" in source,
        )
    }

    private fun repository(
        owner: StudentProblemOrganizationOwnerPort,
        sessions: ProblemOrganizationWorkSessionPort,
    ) = StudentAuthoritativeMistakeOrganizationRepository(owner, sessions)

    private fun unusedPreparation(request: ModelTaskRequest) =
        PreparedStudentProblemOrganization(
            sourceReceiptId = SOURCE_RECEIPT_ID,
            sourceReceiptPayloadFingerprint = SOURCE_FINGERPRINT,
            preparation = MistakeOrganizationPreparation(request, emptyList()),
        )

    private fun applied(
        request: ModelTaskRequest,
    ) = StudentProblemOrganizationReviewWriteOutcome.Applied(
        organizationReceiptId = "student-organization-receipt",
        requestId = request.requestId,
        requestCanonicalFingerprint = ModelTaskFingerprint.of(request),
        sourceReceiptId = SOURCE_RECEIPT_ID,
        sourceReceiptPayloadFingerprint = SOURCE_FINGERPRINT,
        problemRevisionCanonicalFingerprint = "d".repeat(64),
        modelProviderId = provider().providerId,
        modelId = provider().modelId,
        modelVersion = "model-version",
        providerConfigurationVersion = provider().providerConfigurationVersion,
        reviewedModelTaskSchemaVersion = request.schemaVersion,
        reviewedOrganizationPlanSchemaVersion = 3,
        verifiedKnowledgeReferenceCount = 2,
        knowledgeManifestFingerprint = "e".repeat(64),
        knowledgeActivationGeneration = 1,
        organizationPayloadCanonicalFingerprint = "f".repeat(64),
        ownerReviewProofFingerprint = "c".repeat(64),
        recordedAtEpochMillis = NOW + 1,
    )

    private fun authority(request: ModelTaskRequest) =
        ProblemOrganizationWorkCompletionAuthority(
            workId = WORK_ID,
            expectedStateVersion = RUNNING_VERSION,
            leaseOwner = LEASE_OWNER,
            requestId = request.requestId,
        )

    private class FakeStudentOrganizationOwner(
        private val prepared: PreparedStudentProblemOrganization,
        private val reviewOutcome: StudentProblemOrganizationReviewWriteOutcome,
        private val afterReview: () -> Unit = {},
    ) : StudentProblemOrganizationOwnerPort {
        override val scope: SessionScope = TEST_SCOPE
        var prepareCommand: PrepareStudentProblemOrganizationCommand? = null
        var reviewCommand: ReviewAndWriteStudentProblemOrganizationCommand? = null
        var reviewCalls: Int = 0

        override suspend fun prepareCommitted(
            command: PrepareStudentProblemOrganizationCommand,
        ): PreparedStudentProblemOrganization {
            prepareCommand = command
            return prepared
        }

        override suspend fun reviewAndWrite(
            command: ReviewAndWriteStudentProblemOrganizationCommand,
            requireCurrentExecution: () -> Unit,
            commitFence: StudentProblemOrganizationCommitFence,
        ): StudentProblemOrganizationReviewWriteOutcome {
            reviewCalls += 1
            reviewCommand = command
            afterReview()
            return reviewOutcome
        }
    }

    private class FakeWorkSessions(
        var work: ProblemOrganizationWorkSessionSnapshot?,
    ) : ProblemOrganizationWorkSessionPort {
        val transitions = mutableListOf<ProblemOrganizationWorkSessionTransition>()

        override fun observeSchedulable(
            scope: SessionScope,
        ): Flow<List<ProblemOrganizationWorkSessionSnapshot>> = emptyFlow()

        override suspend fun read(
            query: ProblemOrganizationWorkSessionReadQuery,
        ): ProblemOrganizationWorkSessionSnapshot? =
            work?.takeIf { snapshot ->
                query.scope == TEST_SCOPE &&
                    when (query) {
                        is ProblemOrganizationWorkSessionReadQuery.ByWorkId ->
                            query.workId == snapshot.workId
                        is ProblemOrganizationWorkSessionReadQuery.ByRequestId ->
                            query.requestId == snapshot.requestId
                        is ProblemOrganizationWorkSessionReadQuery.BySourceReceiptId ->
                            query.sourceCommitReceiptId == snapshot.sourceCommitReceiptId
                    }
            }

        override suspend fun readSourceReceipt(
            scope: SessionScope,
            sourceCommitReceiptId: String,
        ): OrganizationSourceCommitSessionReceipt? =
            sourceReceipt().takeIf {
                scope == TEST_SCOPE && sourceCommitReceiptId == SOURCE_RECEIPT_ID
            }

        override suspend fun authorize(
            command: AuthorizeProblemOrganizationWorkSessionCommand,
        ): ProblemOrganizationWorkSessionMutationResult = error("unused")

        override suspend fun claim(
            command: ClaimProblemOrganizationWorkSessionCommand,
        ): ProblemOrganizationWorkSessionMutationResult = error("unused")

        override suspend fun transition(
            command: ProblemOrganizationWorkSessionTransition,
        ): ProblemOrganizationWorkSessionMutationResult {
            transitions += command
            val current = checkNotNull(work)
            val complete = command as ProblemOrganizationWorkSessionTransition.Complete
            val next =
                current.copy(
                    status = ProblemOrganizationWorkSessionStatus.SUCCEEDED,
                    version = SessionVersion(current.version.sequence + 1, "8".repeat(64)),
                    requestId = complete.completedRequestId,
                    leaseOwner = null,
                    leaseExpiresAtEpochMillis = null,
                    updatedAtEpochMillis = complete.occurredAtEpochMillis,
                )
            work = next
            return ProblemOrganizationWorkSessionMutationResult(
                receipt =
                    SessionMutationReceipt(
                        operation = command.operation,
                        disposition = SessionMutationDisposition.APPLIED,
                        currentVersion = next.version,
                        recordedAtEpochMillis = command.occurredAtEpochMillis,
                    ),
                snapshot = next,
            )
        }

        override suspend fun readRunningRecoveryPage(
            query: ProblemOrganizationWorkRecoveryQuery,
        ): List<ProblemOrganizationWorkSessionSnapshot> = emptyList()
    }

    private fun waitingWork() =
        ProblemOrganizationWorkSessionSnapshot(
            scope = TEST_SCOPE,
            workId = WORK_ID,
            sourceCommitReceiptId = SOURCE_RECEIPT_ID,
            status = ProblemOrganizationWorkSessionStatus.WAITING_AUTHORIZATION,
            version = SessionVersion(WAITING_VERSION, "4".repeat(64)),
            attemptCount = 0,
            notBeforeEpochMillis = NOW,
            requestId = null,
            requestPayload = null,
            authorizationPayload = null,
            leaseOwner = null,
            leaseExpiresAtEpochMillis = null,
            failureCode = "EGRESS_AUTHORIZATION_REQUIRED",
            failureMessage = "需要授权",
            createdAtEpochMillis = NOW - 20,
            updatedAtEpochMillis = NOW - 10,
        )

    private fun runningWork(request: ModelTaskRequest) =
        ProblemOrganizationWorkSessionSnapshot(
            scope = TEST_SCOPE,
            workId = WORK_ID,
            sourceCommitReceiptId = SOURCE_RECEIPT_ID,
            status = ProblemOrganizationWorkSessionStatus.RUNNING,
            version = SessionVersion(RUNNING_VERSION, "7".repeat(64)),
            attemptCount = 1,
            notBeforeEpochMillis = NOW,
            requestId = request.requestId,
            requestPayload =
                SessionOpaquePayload(
                    schema = "organization-request-v1",
                    content = ModelTaskCodec.encodeRequest(request),
                ),
            authorizationPayload = null,
            leaseOwner = LEASE_OWNER,
            leaseExpiresAtEpochMillis = NOW + 300_000,
            failureCode = null,
            failureMessage = null,
            createdAtEpochMillis = NOW - 20,
            updatedAtEpochMillis = NOW,
        )

    private fun request(): ModelTaskRequest {
        val input =
            ProblemOrganizationV3Input(
                problemId = "problem",
                problemRevisionId = "revision",
                practiceUnitId = "unit",
                subject = SubjectKind.MATH,
                capturedDocument =
                    CapturedQuestionDocument(
                        document =
                            QuestionDocument(
                                id = "question",
                                blocks = listOf(ContentBlock.Paragraph("block", "题面")),
                            ),
                        blockEvidence =
                            listOf(
                                QuestionBlockEvidence(
                                    blockId = "block",
                                    sourceAssetId = "asset",
                                    provenance = QuestionBlockProvenance.USER_TRANSCRIPTION,
                                    reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                                ),
                            ),
                    ),
                sourceAssets =
                    listOf(
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
            schemaVersion = ModelTaskRequest.PROBLEM_ORGANIZATION_V3_SCHEMA_VERSION,
            requestId = REQUEST_ID,
            input = input,
            occurredAtEpochMillis = NOW,
            egressManifest = authorization().toEgressManifest(REQUEST_ID, input),
        )
    }

    private fun authorization() =
        ProblemOrganizationAuthorizationGrant(
            authorizationId = "organization-authorization",
            sourceDraftId = "draft",
            providerId = provider().providerId,
            modelId = provider().modelId,
            providerConfigurationVersion = provider().providerConfigurationVersion,
            approvedAtEpochMillis = NOW - 1,
            expiresAtEpochMillis = NOW + 1_000,
            assets =
                listOf(
                    ModelEgressAssetGrant(
                        assetId = "asset",
                        sha256 = "a".repeat(64),
                        byteSize = 1_024,
                        width = 1_200,
                        height = 1_600,
                    ),
                ),
        )

    private fun provider() =
        ProviderCapabilitySnapshot(
            providerId = "provider",
            providerDisplayName = "Provider",
            modelId = "model",
            supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = false,
        )

    private fun productionSource(): File =
        File(
            projectRoot(),
            "core/data/src/main/kotlin/com/tingyun/smartmistakebook/core/data/mistake/" +
                "StudentAuthoritativeMistakeOrganizationRepository.kt",
        )

    private fun projectRoot(): File =
        generateSequence(
            File(requireNotNull(System.getProperty("user.dir"))).canonicalFile,
        ) { directory -> directory.parentFile }
            .firstOrNull { directory -> File(directory, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root")

    private companion object {
        const val WORK_ID = "organization-work"
        const val REQUEST_ID = "request-id"
        const val SOURCE_RECEIPT_ID = "source-receipt"
        const val SOURCE_FINGERPRINT =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val LEASE_OWNER = "worker"
        const val WAITING_VERSION = 4L
        const val RUNNING_VERSION = 7L
        const val NOW = 1_000L
        val TEST_SCOPE = SessionScope("learner")

        fun sourceReceipt() =
            OrganizationSourceCommitSessionReceipt(
                scope = TEST_SCOPE,
                receiptId = SOURCE_RECEIPT_ID,
                payloadFingerprint = SOURCE_FINGERPRINT,
                recordedAtEpochMillis = NOW - 20,
            )
    }
}
