package com.tingyun.smartmistakebook.core.data.model

import com.tingyun.smartmistakebook.core.model.provider.FakeModelGateway
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.LegacyStudyDatabaseTestFactory
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.data.authority.CurrentOpenResponseEvaluationBinding
import com.tingyun.smartmistakebook.core.data.authority.CurrentOpenResponseEvaluationScopeAuthorization
import com.tingyun.smartmistakebook.core.data.authority.ProductionOpenResponseEvaluationTaskProducerFactory
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentInput
import com.tingyun.smartmistakebook.core.model.CaptureAssessmentOrigin
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.CaptureParseOutput
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelEgressAuthorizationId
import com.tingyun.smartmistakebook.core.model.ModelEgressManifest
import com.tingyun.smartmistakebook.core.model.ModelEgressPurpose
import com.tingyun.smartmistakebook.core.model.ModelFailureCode
import com.tingyun.smartmistakebook.core.model.ModelGatewayEvent
import com.tingyun.smartmistakebook.core.model.ModelGatewayExecution
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.ModelPromptPolicyVersions
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionBlockEvidence
import com.tingyun.smartmistakebook.core.model.QuestionBlockProvenance
import com.tingyun.smartmistakebook.core.model.QuestionBlockReviewStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorLobbyInput
import com.tingyun.smartmistakebook.core.model.TutorLobbyOutput
import com.tingyun.smartmistakebook.core.model.TutorMarkdownSnapshot
import com.tingyun.smartmistakebook.core.model.TutorStreamEvent
import com.tingyun.smartmistakebook.core.model.TutorStreamIdentity
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.domain.ModelGateway
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.LongSupplier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelTaskRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: StudyDatabasePort
    private lateinit var databaseName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "model-task-repository-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        database =
            LegacyStudyDatabaseTestFactory.openPreCutoverForTest(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun openResponseEvaluationIsRejectedByDurablePathAndEphemeralExecutionCreatesNoRows() =
        runBlocking {
            val provider = ProviderCapabilitySnapshot(
                providerId = "local-open-response-test",
                providerDisplayName = "Local evaluator",
                modelId = "local-evaluator-v1",
                supportedTasks = setOf(ModelTaskKind.TUTOR_EVALUATE),
                supportsImageInput = false,
                supportsStructuredOutput = true,
                supportsStreaming = false,
                executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
            )
            val gateway = object : ModelGateway {
                override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

                override fun execute(execution: ModelGatewayExecution) = flow {
                    emit(ModelGatewayEvent.Started(provider))
                    emit(
                        ModelGatewayEvent.Failed(
                            com.tingyun.smartmistakebook.core.model.ModelTaskFailure(
                                code = ModelFailureCode.PROVIDER_REJECTED_INPUT,
                                message = "测试评价未执行",
                                retryable = false,
                            ),
                        ),
                    )
                }
            }
            val repository =
                RoomModelTaskRepository(
                    database = database,
                    gateway = gateway,
                    clock = { 1_000L },
                )
            val request = sensitiveOpenResponseRequest()

            assertThrows(IllegalArgumentException::class.java) {
                repository.execute(request)
            }
            assertNull(repository.observe(request.requestId).first())

            repository.executeSensitiveEphemeral(request).toList()

            assertNull(repository.observe(request.requestId).first())
            assertTrue(
                repository.observeBySubject(request.input.subjectId, ModelTaskKind.TUTOR_EVALUATE)
                    .first()
                    .isEmpty(),
            )
            assertNoDurableModelTaskRows()
        }

    @Test
    fun fakeProviderPersistsEveryUserVisibleStageBeforeEmission() = runBlocking {
        var now = 1_000L
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = FakeModelGateway(stepDelayMillis = 0),
            clock = { ++now },
        )

        val states = repository.execute(request()).toList()

        assertEquals(ModelTaskStatus.WAITING_FOR_MODEL, states.first().status)
        assertEquals(ModelTaskStatus.SUCCEEDED, states.last().status)
        assertTrue(states.any { it.status == ModelTaskStatus.RUNNING })
        assertTrue(states.any { it.status == ModelTaskStatus.STREAMING })
        assertEquals(states.last(), repository.observe(request().requestId).first())
        assertTrue(states.last().provider?.isDemo == true)
    }

    @Test
    fun localInterruptedExecutionResumesWithoutConsumingRemoteBudget() = runBlocking {
        var now = 2_000L
        val interruptedRepository = RoomModelTaskRepository(
            database = database,
            gateway = FakeModelGateway(stepDelayMillis = 60_000),
            clock = { ++now },
        )
        val interrupted = interruptedRepository.execute(request()).first {
            it.status == ModelTaskStatus.RUNNING
        }
        assertEquals(0, interrupted.attemptCount)

        val resumedRepository = RoomModelTaskRepository(
            database = database,
            gateway = FakeModelGateway(stepDelayMillis = 0),
            clock = { ++now },
        )
        val completed = resumedRepository.execute(request()).toList().last()

        assertEquals(interrupted.taskId, completed.taskId)
        assertEquals(ModelTaskStatus.SUCCEEDED, completed.status)
        assertEquals(0, completed.attemptCount)
    }

    @Test
    fun localFailuresNeverConsumeTheLaterExternalDispatchBudget() = runBlocking {
        var now = 2_250L
        val localGatewayExecutions = AtomicInteger()
        val localRepository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = TEST_CAPABILITIES

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    localGatewayExecutions.incrementAndGet()
                    error("Local fixture failure")
                }
            },
            clock = { ++now },
        )

        repeat(4) {
            val failed = localRepository.execute(request()).toList().last()
            assertEquals(ModelTaskStatus.RETRYABLE_FAILURE, failed.status)
            assertEquals(0, failed.attemptCount)
        }

        val externalCapabilities = externalCapabilities()
        val externalRepository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = externalCapabilities

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    error("External fixture failure")
                }
            },
            clock = { ++now },
        )
        val firstExternalFailure = externalRepository.execute(
            externalRequest(
                requestId = "capture-assess:after-local-failures",
                occurredAtEpochMillis = 2_200,
                provider = externalCapabilities,
            ),
        ).toList().last()

        assertEquals(4, localGatewayExecutions.get())
        assertEquals(ModelTaskStatus.RETRYABLE_FAILURE, firstExternalFailure.status)
        assertEquals(1, firstExternalFailure.attemptCount)
    }

    @Test
    fun failureBeforeStartedConsumesTheDurableDispatchBudget() = runBlocking {
        var now = 2_500L
        val capabilityCalls = AtomicInteger()
        val gatewayExecutions = AtomicInteger()
        val externalCapabilities = externalCapabilities()
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities(): ProviderCapabilitySnapshot {
                    capabilityCalls.incrementAndGet()
                    return externalCapabilities
                }

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    gatewayExecutions.incrementAndGet()
                    error("Connection failed before the provider emitted Started")
                }
            },
            clock = { ++now },
        )
        val originalRequest = externalRequest(provider = externalCapabilities)

        val first = repository.execute(originalRequest).toList().last()
        val second = repository.execute(originalRequest).toList().last()
        val exhausted = repository.execute(originalRequest).toList().last()

        assertEquals(ModelTaskStatus.RETRYABLE_FAILURE, first.status)
        assertEquals(1, first.attemptCount)
        assertEquals(ModelTaskStatus.RETRYABLE_FAILURE, second.status)
        assertEquals(2, second.attemptCount)
        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, exhausted.status)
        assertEquals(3, exhausted.attemptCount)
        assertEquals(ModelFailureCode.UNKNOWN, exhausted.failure?.code)
        assertEquals(false, exhausted.failure?.retryable)
        assertEquals(originalRequest, exhausted.request)
        assertEquals(ModelTaskFingerprint.of(originalRequest), exhausted.requestFingerprint)
        assertEquals(3, capabilityCalls.get())
        assertEquals(3, gatewayExecutions.get())

        val replayed = repository.execute(originalRequest).toList().last()

        assertEquals(exhausted, replayed)
        assertEquals(3, capabilityCalls.get())
        assertEquals(3, gatewayExecutions.get())
    }

    @Test
    fun newRequestIdsAndProviderEnvelopesDoNotResetTheLogicalOperationBudget() = runBlocking {
        var now = 2_700L
        val capabilityCalls = AtomicInteger()
        val gatewayExecutions = AtomicInteger()
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities(): ProviderCapabilitySnapshot {
                    val call = capabilityCalls.incrementAndGet()
                    return externalCapabilities(
                        providerId = "test-provider-$call",
                        providerConfigurationVersion = "instrumented-fixture-$call",
                    )
                }

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    gatewayExecutions.incrementAndGet()
                    error("Connection failed before the provider emitted Started")
                }
            },
            clock = { ++now },
        )
        val envelopes = (1..4).map { ordinal ->
            externalRequest(
                requestId = "capture-assess:logical-envelope-$ordinal",
                occurredAtEpochMillis = 1_000L + ordinal,
                provider = externalCapabilities(
                    providerId = "test-provider-$ordinal",
                    providerConfigurationVersion = "instrumented-fixture-$ordinal",
                ),
            )
        }

        val completed = envelopes.map { envelope -> repository.execute(envelope).toList().last() }

        assertEquals(listOf(1, 2, 3, 3), completed.map { it.attemptCount })
        assertEquals(ModelTaskStatus.RETRYABLE_FAILURE, completed[0].status)
        assertEquals(ModelTaskStatus.RETRYABLE_FAILURE, completed[1].status)
        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, completed[2].status)
        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, completed[3].status)
        assertEquals(4, capabilityCalls.get())
        assertEquals(3, gatewayExecutions.get())
    }

    @Test
    fun duplicateProviderStartFailsClosedWithoutIncreasingTheReservedAttempt() = runBlocking {
        val gatewayExecutions = AtomicInteger()
        val externalCapabilities = externalCapabilities()
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = externalCapabilities

                override fun execute(execution: ModelGatewayExecution) = flow {
                    gatewayExecutions.incrementAndGet()
                    emit(ModelGatewayEvent.Started(externalCapabilities))
                    emit(ModelGatewayEvent.Started(externalCapabilities))
                }
            },
            clock = { 2_750L },
        )

        val failed = repository.execute(
            externalRequest(provider = externalCapabilities),
        ).toList().last()

        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, failed.status)
        assertEquals(ModelFailureCode.INVALID_RESPONSE, failed.failure?.code)
        assertEquals(1, failed.attemptCount)
        assertEquals(1, gatewayExecutions.get())
    }

    @Test
    fun exhaustedPersistedRunningTaskRecoversLocallyWithoutAnotherGatewayCall() = runBlocking {
        val externalCapabilities = externalCapabilities()
        val originalRequest = externalRequest(provider = externalCapabilities)
        val authorizationTime = requireNotNull(originalRequest.egressManifest).approvedAtEpochMillis
        val interruptedRepository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = externalCapabilities

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    awaitCancellation()
                }
            },
            clock = { authorizationTime },
        )
        (1..3).forEach { expectedAttempt ->
            val interrupted = interruptedRepository.execute(originalRequest).first { snapshot ->
                snapshot.status == ModelTaskStatus.RUNNING &&
                    snapshot.attemptCount == expectedAttempt
            }
            assertEquals(expectedAttempt, interrupted.attemptCount)
        }
        val capabilityCalls = AtomicInteger()
        val gatewayExecutions = AtomicInteger()
        val guardedRepository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities(): ProviderCapabilitySnapshot {
                    capabilityCalls.incrementAndGet()
                    return externalCapabilities
                }

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    gatewayExecutions.incrementAndGet()
                    error("An exhausted task must never reach the gateway")
                }
            },
            clock = { authorizationTime },
        )

        val exhausted = guardedRepository.execute(originalRequest).toList().last()

        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, exhausted.status)
        assertEquals(3, exhausted.attemptCount)
        assertEquals(originalRequest, exhausted.request)
        assertEquals(ModelTaskFingerprint.of(originalRequest), exhausted.requestFingerprint)
        assertEquals(1, capabilityCalls.get())
        assertEquals(0, gatewayExecutions.get())
    }

    @Test
    fun exhaustedRemoteDispatchBudgetCanStillFinishLocally() = runBlocking {
        val externalCapabilities = externalCapabilities()
        val originalRequest = externalRequest(
            requestId = "capture-assess:remote-exhausted-local-recovery",
            provider = externalCapabilities,
        )
        val authorizationTime = requireNotNull(originalRequest.egressManifest).approvedAtEpochMillis
        val remoteExecutions = AtomicInteger()
        val interruptedRepository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = externalCapabilities

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    remoteExecutions.incrementAndGet()
                    awaitCancellation()
                }
            },
            clock = { authorizationTime },
        )
        (1..3).forEach { expectedAttempt ->
            interruptedRepository.execute(originalRequest).first { snapshot ->
                snapshot.status == ModelTaskStatus.RUNNING &&
                    snapshot.attemptCount == expectedAttempt
            }
        }

        val completed = RoomModelTaskRepository(
            database = database,
            gateway = FakeModelGateway(stepDelayMillis = 0),
            clock = { authorizationTime },
        ).execute(originalRequest).toList().last()

        assertEquals(ModelTaskStatus.SUCCEEDED, completed.status)
        assertEquals(3, completed.attemptCount)
        assertEquals(ModelExecutionLocation.LOCAL_NO_EGRESS, completed.provider?.executionLocation)
        assertEquals(3, remoteExecutions.get())
    }

    @Test
    fun wrongCompletionTypeFailsClosedAsInvalidResponse() = runBlocking {
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = TEST_CAPABILITIES

                override fun execute(execution: ModelGatewayExecution) = flow {
                    emit(ModelGatewayEvent.Started(TEST_CAPABILITIES))
                    emit(ModelGatewayEvent.Completed(parseOutput()))
                }
            },
            clock = { 3_000L },
        )

        val completed = repository.execute(request()).toList().last()

        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, completed.status)
        assertEquals(ModelFailureCode.INVALID_RESPONSE, completed.failure?.code)
    }

    @Test
    fun excessiveProviderEventsAreBoundedAndFailClosed() = runBlocking {
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = TEST_CAPABILITIES

                override fun execute(execution: ModelGatewayExecution) = flow {
                    emit(ModelGatewayEvent.Started(TEST_CAPABILITIES))
                    repeat(65) { index ->
                        emit(
                            ModelGatewayEvent.Progress(
                                ModelTaskStage.READING_IMAGE,
                                "安全校验进度 $index",
                            ),
                        )
                    }
                }
            },
            clock = { 4_000L },
        )

        val completed = repository.execute(request()).toList().last()

        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, completed.status)
        assertEquals(ModelFailureCode.INVALID_RESPONSE, completed.failure?.code)
    }

    @Test
    fun tutorPreviewsStayEphemeralUntilTheValidatedFinalIsPersisted() = runBlocking {
        val request = tutorLobbyRequest()
        val capabilities = tutorCapabilities()
        val output = TutorLobbyOutput(
            conversationId = "tutor-stream-conversation",
            messageOrdinal = 1,
            messageMarkdown = "先看题目中的已知条件。",
            modelVersion = capabilities.modelId,
        )
        val gatewayExecutions = AtomicInteger()
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = capabilities

                override fun execute(execution: ModelGatewayExecution) = flow {
                    gatewayExecutions.incrementAndGet()
                    emit(ModelGatewayEvent.Started(capabilities))
                    repeat(128) {
                        emit(
                            ModelGatewayEvent.TutorPreview(
                                TutorMarkdownSnapshot(
                                    stableMarkdown = "",
                                    provisionalMarkdown = "先看题目中的已知条件。",
                                ),
                            ),
                        )
                    }
                    emit(ModelGatewayEvent.Completed(output))
                }
            },
            clock = { 5_000L },
        )
        val identity = TutorStreamIdentity(
            requestId = request.requestId,
            ownerVersion = 1,
            turnVersion = 2,
            modeVersion = 3,
        )

        val events = repository.executeTutorStream(request, identity).toList()

        assertEquals(1, gatewayExecutions.get())
        assertTrue(events.first() is TutorStreamEvent.Started)
        assertTrue(events.any { it is TutorStreamEvent.Preview })
        val completed = events.last() as TutorStreamEvent.Completed
        assertEquals(output.messageMarkdown, completed.snapshot.stableMarkdown)
        val durable = requireNotNull(repository.observe(request.requestId).first())
        assertEquals(ModelTaskStatus.SUCCEEDED, durable.status)
        assertEquals(output, durable.output)
        assertEquals(3L, durable.stateVersion)
    }

    @Test
    fun nonTutorTasksRejectTutorPreviewsInsteadOfSilentlyIgnoringThem() = runBlocking {
        val delegate = FakeModelGateway(stepDelayMillis = 0)
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = delegate.capabilities()

                override fun execute(execution: ModelGatewayExecution) = flow {
                    delegate.execute(execution).collect { event ->
                        emit(event)
                        if (event is ModelGatewayEvent.Started) {
                            emit(
                                ModelGatewayEvent.TutorPreview(
                                    TutorMarkdownSnapshot(
                                        stableMarkdown = "不应出现",
                                        provisionalMarkdown = "",
                                    ),
                                ),
                            )
                        }
                    }
                }
            },
            clock = { 5_500L },
        )

        val final = repository.execute(request()).toList().last()

        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, final.status)
        assertEquals(ModelFailureCode.INVALID_RESPONSE, final.failure?.code)
    }

    @Test
    fun cancellingAnActiveTutorStreamMakesSupersessionDurable() = runBlocking {
        val request = tutorLobbyRequest()
        val capabilities = tutorCapabilities()
        val gatewayStarted = CompletableDeferred<Unit>()
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = capabilities

                override fun execute(execution: ModelGatewayExecution) = flow {
                    emit(ModelGatewayEvent.Started(capabilities))
                    gatewayStarted.complete(Unit)
                    awaitCancellation()
                }
            },
            clock = { 5_750L },
        )
        val identity = TutorStreamIdentity(
            requestId = request.requestId,
            ownerVersion = 1,
            turnVersion = 2,
            modeVersion = 3,
        )
        val execution = async {
            repository.executeTutorStream(request, identity).toList()
        }
        withTimeout(10_000) { gatewayStarted.await() }

        repository.cancel(request.requestId)

        val cancelled = withTimeout(10_000) {
            repository.observe(request.requestId).first {
                it?.status == ModelTaskStatus.CANCELLED
            }
        }
        assertEquals(ModelTaskStatus.CANCELLED, cancelled?.status)
        execution.cancelAndJoin()
    }

    @Test
    fun externalProviderNeverReceivesAnImageTaskWithoutStudentApproval() = runBlocking {
        var gatewayCalled = false
        val externalCapabilities = TEST_CAPABILITIES.copy(
            executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
            providerConfigurationVersion = "external-config-v1",
        )
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = externalCapabilities

                override fun execute(execution: ModelGatewayExecution) = flow<ModelGatewayEvent> {
                    gatewayCalled = true
                    error("External gateway must not be called without approval")
                }
            },
            clock = { 4_500L },
        )

        val completed = repository.execute(request()).toList().last()

        assertEquals(ModelTaskStatus.PERMANENT_FAILURE, completed.status)
        assertEquals(ModelFailureCode.EGRESS_AUTHORIZATION_REQUIRED, completed.failure?.code)
        assertTrue(!gatewayCalled)
    }

    @Test
    fun concurrentRepositoriesNeverRewriteTheWinnerAsRetryableFailure() = runBlocking {
        val first = RoomModelTaskRepository(database, FakeModelGateway(stepDelayMillis = 5))
        val second = RoomModelTaskRepository(database, FakeModelGateway(stepDelayMillis = 5))

        coroutineScope {
            val executions = listOf(
                async { first.execute(request()).toList() },
                async { second.execute(request()).toList() },
            )
            executions.forEach { it.await() }
        }

        val persisted = first.observe(request().requestId).first()
        assertEquals(ModelTaskStatus.SUCCEEDED, persisted?.status)
        assertTrue(persisted?.failure == null)
    }

    @Test
    fun waitingCollectorTakesOverWhenActiveCollectorIsCancelled() = runBlocking {
        val firstAttemptStarted = CompletableDeferred<Unit>()
        val gatewayExecutions = AtomicInteger()
        val delegate = FakeModelGateway(stepDelayMillis = 0)
        val repository = RoomModelTaskRepository(
            database = database,
            gateway = object : ModelGateway {
                override suspend fun capabilities() = delegate.capabilities()

                override fun execute(execution: ModelGatewayExecution) =
                    if (gatewayExecutions.incrementAndGet() == 1) {
                        flow {
                            emit(ModelGatewayEvent.Started(delegate.capabilities()))
                            firstAttemptStarted.complete(Unit)
                            awaitCancellation()
                        }
                    } else {
                        delegate.execute(execution)
                    }
            },
        )

        val winner = async { repository.execute(request()).toList() }
        withTimeout(10_000) { firstAttemptStarted.await() }
        val loserObservedRunning = CompletableDeferred<Unit>()
        val loser = async {
            repository.execute(request())
                .onEach { snapshot ->
                    if (snapshot.status == ModelTaskStatus.RUNNING) {
                        loserObservedRunning.complete(Unit)
                    }
                }
                .toList()
        }
        withTimeout(10_000) { loserObservedRunning.await() }
        val loserCompletedBeforeCancellation = withTimeoutOrNull(1_000) {
            loser.join()
            true
        } ?: false
        assertTrue(
            "The losing collector must wait for active ownership to finish",
            !loserCompletedBeforeCancellation,
        )

        winner.cancelAndJoin()
        val completed = withTimeout(10_000) { loser.await().last() }

        assertEquals(ModelTaskStatus.SUCCEEDED, completed.status)
        assertEquals(0, completed.attemptCount)
        assertEquals(2, gatewayExecutions.get())
        assertEquals(completed, repository.observe(request().requestId).first())
    }

    private fun request() = ModelTaskRequest(
        requestId = "capture-assess:repository-test",
        input = CaptureAssessmentInput(
            draftId = "draft-repository-test",
            sourceAssetId = "asset-repository-test",
            origin = CaptureAssessmentOrigin.TUTOR,
            imageWidth = 1080,
            imageHeight = 1440,
        ),
        occurredAtEpochMillis = 1_000,
    )

    private fun tutorLobbyRequest() = ModelTaskRequest(
        requestId = "tutor-lobby:repository-stream",
        input = TutorLobbyInput(
            conversationId = "tutor-stream-conversation",
            messageOrdinal = 1,
            studentMessage = "这一步从哪里开始？",
        ),
        occurredAtEpochMillis = 1_000,
    )

    private fun sensitiveOpenResponseRequest(): ModelTaskRequest {
        val question = QuestionDocument(
            id = "open-response-question-content",
            blocks = listOf(
                ContentBlock.Paragraph(
                    id = "open-response-question-paragraph",
                    markdown = "判断函数在该区间的单调性。",
                ),
            ),
        )
        val binding = CurrentOpenResponseEvaluationBinding(
            learnerId = "local-learner",
            conversationId = "local-conversation",
            questionDocumentId = "local-question",
            questionRevisionNumber = 1,
            subject = SubjectKind.MATH,
            questionDocument = question,
            rubricCanonicalFingerprint = fingerprint("rubric"),
            operationBinding = fingerprint("operation-binding"),
            currentAnswer = "高熵回答-7f9c2a41-单调递减",
            teachingReferences = emptyList(),
            evaluator = OpenResponseEvaluatorKind.RUBRIC,
            evaluatorPolicyFingerprint = fingerprint("evaluator-policy"),
        )
        val knowledgeAuthority = KnowledgeReferenceProofAuthority.create()
        val input = ProductionOpenResponseEvaluationTaskProducerFactory.create(
            binding = binding,
            knowledgeReferenceVerifier = knowledgeAuthority.verifier,
            expiresAtEpochMillis = 10_000L,
            nowEpochMillis = LongSupplier { 1_000L },
            currentScopeAuthorization =
                CurrentOpenResponseEvaluationScopeAuthorization { expected ->
                    expected == binding.scopeIdentity
                },
        ).createTask(requestVersion = 1L)
        return ModelTaskRequest(
            requestId = "tutor-evaluate:ephemeral-only",
            input = input,
            occurredAtEpochMillis = 1_000L,
        )
    }

    private fun assertNoDurableModelTaskRows() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            listOf("model_task", "model_task_operation", "model_task_event").forEach { table ->
                sqlite.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Unexpected durable row in $table", 0, cursor.getInt(0))
                }
            }
        }
    }

    private fun fingerprint(seed: String): String =
        CanonicalSha256("open-response-ephemeral-instrumented-test")
            .field("seed", seed)
            .finish()

    private fun externalRequest(
        requestId: String = "capture-assess:repository-test-external",
        occurredAtEpochMillis: Long = 1_000,
        provider: ProviderCapabilitySnapshot = externalCapabilities(),
    ) = request().copy(
        requestId = requestId,
        occurredAtEpochMillis = occurredAtEpochMillis,
        egressManifest = ModelEgressManifest(
            authorizationId =
                ModelEgressAuthorizationId.forInput(requestId, request().input),
            subjectId = "draft-repository-test",
            purpose = ModelEgressPurpose.CAPTURE_TO_DOCUMENT,
            authorizedTaskKinds = setOf(
                ModelTaskKind.CAPTURE_ASSESS,
                ModelTaskKind.CAPTURE_PARSE,
            ),
            providerId = provider.providerId,
            modelId = provider.modelId,
            providerConfigurationVersion = provider.providerConfigurationVersion,
            promptPolicyVersion = ModelPromptPolicyVersions.CAPTURE_DOCUMENT,
            approvedAtEpochMillis = occurredAtEpochMillis + 1,
            assets = listOf(
                ModelEgressAssetGrant(
                    assetId = "asset-repository-test",
                    sha256 = "a".repeat(64),
                    byteSize = 1_024,
                    width = 1_080,
                    height = 1_440,
                ),
            ),
            disclosedData = ModelEgressManifest.CAPTURE_IMAGE_DISCLOSURE,
            prohibitedData = ModelEgressManifest.CAPTURE_PROHIBITED_DATA,
        ),
    )

    private fun externalCapabilities(
        providerId: String = "test-provider-external",
        providerConfigurationVersion: String = "instrumented-external-v1",
    ) = TEST_CAPABILITIES.copy(
        providerId = providerId,
        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
        providerConfigurationVersion = providerConfigurationVersion,
        isDemo = false,
    )

    private fun tutorCapabilities() = ProviderCapabilitySnapshot(
        providerId = "tutor-stream-provider",
        providerDisplayName = "测试模型",
        modelId = "tutor-stream-v1",
        supportedTasks = setOf(ModelTaskKind.TUTOR_LOBBY),
        supportsImageInput = false,
        supportsStructuredOutput = true,
        supportsStreaming = true,
        executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
        providerConfigurationVersion = "tutor-stream-fixture-v1",
    )

    private fun parseOutput() = CaptureParseOutput(
        capturedDocument = CapturedQuestionDocument(
            document = QuestionDocument(
                id = "document-draft-repository-test",
                blocks = listOf(ContentBlock.Paragraph("stem", "测试题干")),
            ),
            blockEvidence = listOf(
                QuestionBlockEvidence(
                    blockId = "stem",
                    sourceAssetId = "asset-repository-test",
                    sourceRegion = NormalizedSourceRegion(0.0, 0.0, 1.0, 1.0),
                    writingLayer = WritingLayer.PRINTED,
                    provenance = QuestionBlockProvenance.MODEL_DOCUMENT_PARSE,
                    confidence = 0.9,
                    reviewStatus = QuestionBlockReviewStatus.NEEDS_REVIEW,
                    producerVersion = "test/parse-v1",
                ),
            ),
        ),
        modelVersion = "test/parse-v1",
    )

    private companion object {
        val TEST_CAPABILITIES = ProviderCapabilitySnapshot(
            providerId = "test-provider",
            providerDisplayName = "测试模型",
            modelId = "test-vision-v1",
            supportedTasks = setOf(ModelTaskKind.CAPTURE_ASSESS),
            supportsImageInput = true,
            supportsStructuredOutput = true,
            supportsStreaming = true,
            executionLocation = ModelExecutionLocation.LOCAL_NO_EGRESS,
            providerConfigurationVersion = "instrumented-fixture-v1",
        )
    }
}
