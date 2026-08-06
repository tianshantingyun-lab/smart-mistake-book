package com.tingyun.smartmistakebook.core.data.openresponse

import com.tingyun.smartmistakebook.core.data.authority.LearnerBoundLearningEvidencePort
import com.tingyun.smartmistakebook.core.data.authority.PendingAttributionDisposition
import com.tingyun.smartmistakebook.core.data.authority.SavedReviewEvidenceCommand
import com.tingyun.smartmistakebook.core.data.authority.SavedReviewEvidenceWriteResult
import com.tingyun.smartmistakebook.core.data.authority.UnsavedStudyEvidenceCommand
import com.tingyun.smartmistakebook.core.data.authority.UnsavedStudyEvidenceWriteResult
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelEgressPolicy
import com.tingyun.smartmistakebook.core.model.ModelExecutionLocation
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationModelTaskProtocol
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluatorKind
import com.tingyun.smartmistakebook.core.model.ProviderCapabilitySnapshot
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorOpenResponseEvaluationInput
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.function.LongSupplier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDataTutorOpenResponseLearningHostAdapterTest {
    @Test
    fun missingCurrentContextOwnerIsExplicitlyUnavailable() {
        val result =
            CoreDataTutorOpenResponseLearningHostAdapter.assemble(
                evidence = FakeEvidencePort(),
                evaluator = UnavailableEvaluator(),
                candidateOwner = FakeCandidateOwner(),
                contextOwner = null,
                nowEpochMillis = LongSupplier { NOW },
            )

        assertEquals(
            CoreDataTutorOpenResponseLearningAssemblyUnavailableReason
                .MISSING_CURRENT_CONTEXT_OWNER,
            (result as CoreDataTutorOpenResponseLearningAssemblyResult.Unavailable).reason,
        )
    }

    @Test
    fun admittedAnswerUsesOnlyStableSourceFactAndWeakCandidateCoordinatorPath() = runBlocking {
        val evidence = FakeEvidencePort()
        val candidateOwner = FakeCandidateOwner()
        val host = readyHost(evidence = evidence, candidateOwner = candidateOwner)
        val context = requireNotNull(host.issueContext(request()))
        val submission = submission(context)
        val lease = lease(submission)

        val first = host.submitAdmitted(submission, lease)
        val second = host.submitAdmitted(submission, lease)

        assertEquals(CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY, first)
        assertEquals(CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY, second)
        assertEquals(2, evidence.commands.size)
        assertEquals(
            evidence.commands.first().submissionId,
            evidence.commands.last().submissionId,
        )
        assertNotEquals(submission.sourceSubmissionId, evidence.commands.first().submissionId)
        assertEquals(2, evidence.commands.first().attemptOrdinal)
        assertEquals(1, evidence.commands.first().hintCount)
        assertTrue(evidence.commands.first().answerWasRevealed)
        assertTrue(candidateOwner.proposals.isEmpty())
    }

    @Test
    fun newModeOrQuestionIssuanceRevokesEarlierContextBeforeSourceFact() = runBlocking {
        val evidence = FakeEvidencePort()
        val host = readyHost(evidence = evidence)
        val guided = requireNotNull(host.issueContext(request()))
        val direct =
            requireNotNull(
                host.issueContext(
                    request(
                        conversationStateVersion = 8,
                        mode = TutorExplanationMode.DIRECT,
                        modeVersion = 6,
                        evidenceRequestId = "direct-evidence-request",
                    ),
                ),
            )

        val staleSubmission = submission(guided)
        val staleReceipt = host.submitAdmitted(staleSubmission, lease(staleSubmission))

        assertEquals(CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT, staleReceipt)
        assertTrue(evidence.commands.isEmpty())
        assertNotEquals(guided.featureContextFingerprint, direct.featureContextFingerprint)
    }

    @Test
    fun ownerChangeAfterSourceFactLeavesPendingFactAndNeverEvaluates() = runBlocking {
        val owner = FakeContextOwner()
        val evidence = FakeEvidencePort(afterRecord = { owner.current.set(false) })
        val evaluator = CountingUnavailableEvaluator()
        val host = readyHost(evidence = evidence, evaluator = evaluator, contextOwner = owner)
        val context = requireNotNull(host.issueContext(request()))
        val admitted = submission(context)

        val receipt = host.submitAdmitted(admitted, lease(admitted))

        assertEquals(CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT, receipt)
        assertEquals(1, evidence.commands.size)
        assertEquals(0, evaluator.calls)
    }

    @Test
    fun lateContextAuthorizationCannotReplaceNewerIssue() = runBlocking {
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val owner =
            FakeContextOwner(
                beforeAuthorize = { request ->
                    if (request.conversationStateVersion == 7L) {
                        firstEntered.complete(Unit)
                        releaseFirst.await()
                    }
                },
            )
        val host = readyHost(contextOwner = owner)

        val late = async { host.issueContext(request(conversationStateVersion = 7)) }
        firstEntered.await()
        val current = host.issueContext(request(conversationStateVersion = 8))
        releaseFirst.complete(Unit)

        assertNotNull(current)
        assertNull(late.await())
        val currentSubmission = submission(requireNotNull(current))
        assertEquals(
            CoreDataTutorOpenResponseLearningReceipt.RETAINED_FOR_RETRY,
            host.submitAdmitted(currentSubmission, lease(currentSubmission)),
        )
    }

    @Test
    fun timeoutExpiryAndRevokedFeatureLeaseFailClosed() = runBlocking {
        val now = AtomicLong(NOW)
        val slowOwner =
            FakeContextOwner(
                beforeAuthorize = { delay(50) },
            )
        val timedOut =
            readyHost(
                contextOwner = slowOwner,
                now = now,
                contextIssueTimeoutMillis = 10,
            )
        assertNull(timedOut.issueContext(request()))
        val failingOwner =
            FakeContextOwner(
                beforeAuthorize = { error("session owner unavailable") },
            )
        assertNull(
            readyHost(contextOwner = failingOwner, now = now)
                .issueContext(request()),
        )

        val owner = FakeContextOwner()
        val evidence = FakeEvidencePort()
        val host = readyHost(evidence = evidence, contextOwner = owner, now = now)
        val context = requireNotNull(host.issueContext(request()))
        val admitted = submission(context)
        now.set(context.expiresAtEpochMillis)
        assertEquals(
            CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
            host.submitAdmitted(admitted, lease(admitted)),
        )

        now.set(NOW)
        val fresh = requireNotNull(host.issueContext(request(conversationStateVersion = 9)))
        val freshSubmission = submission(fresh)
        assertEquals(
            CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
            host.submitAdmitted(freshSubmission, lease(freshSubmission, current = false)),
        )
        assertTrue(evidence.commands.isEmpty())
    }

    @Test
    fun durablyClaimedAnswerQueuesWeakCandidateAfterContextDeadline() = runBlocking {
        val now = AtomicLong(NOW)
        val evidence = FakeEvidencePort()
        val candidateOwner = FakeCandidateOwner()
        val contextOwner = FakeContextOwner()
        val modelTasks = EvaluatingModelTaskRepository(
            beforeEmit = { now.set(NOW + CONTEXT_LIFETIME_MILLIS + 1L) },
        )
        val host = readyHost(
            evidence = evidence,
            evaluator = realEvaluator(modelTasks, now),
            candidateOwner = candidateOwner,
            contextOwner = contextOwner,
            now = now,
        )
        val context = requireNotNull(host.issueContext(request()))
        val admitted = submission(context)

        assertEquals(
            CoreDataTutorOpenResponseLearningReceipt.PENDING,
            host.submitAdmitted(admitted, claimedLease(admitted)),
        )
        assertTrue(now.get() >= context.expiresAtEpochMillis)
        assertEquals(1, contextOwner.consumeCalls)
        assertEquals(1, candidateOwner.proposals.size)
    }

    @Test
    fun modeChangeStillRejectsClaimedEvaluationAfterContextDeadline() = runBlocking {
        val now = AtomicLong(NOW)
        val leaseCurrent = AtomicBoolean(true)
        val candidateOwner = FakeCandidateOwner()
        val modelTasks = EvaluatingModelTaskRepository(
            beforeEmit = {
                now.set(NOW + CONTEXT_LIFETIME_MILLIS + 1L)
                leaseCurrent.set(false)
            },
        )
        val host = readyHost(
            evaluator = realEvaluator(modelTasks, now),
            candidateOwner = candidateOwner,
            now = now,
        )
        val admitted = submission(requireNotNull(host.issueContext(request())))

        assertEquals(
            CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
            host.submitAdmitted(admitted, claimedLease(admitted, leaseCurrent::get)),
        )
        assertTrue(candidateOwner.proposals.isEmpty())
    }

    @Test
    fun hostRevocationStillRejectsClaimedEvaluationAfterContextDeadline() = runBlocking {
        val now = AtomicLong(NOW)
        val candidateOwner = FakeCandidateOwner()
        lateinit var host: CoreDataTutorOpenResponseLearningHostAdapter
        val modelTasks = EvaluatingModelTaskRepository(
            beforeEmit = {
                now.set(NOW + CONTEXT_LIFETIME_MILLIS + 1L)
                host.revoke()
            },
        )
        host = readyHost(
            evaluator = realEvaluator(modelTasks, now),
            candidateOwner = candidateOwner,
            now = now,
        )
        val admitted = submission(requireNotNull(host.issueContext(request())))

        assertEquals(
            CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
            host.submitAdmitted(admitted, claimedLease(admitted)),
        )
        assertTrue(candidateOwner.proposals.isEmpty())
    }

    @Test
    fun closeRevokesIssuedContextAndClosesCandidateOwnerOnce() = runBlocking {
        val candidateOwner = FakeCandidateOwner()
        val evidence = FakeEvidencePort()
        val host = readyHost(evidence = evidence, candidateOwner = candidateOwner)
        val context = requireNotNull(host.issueContext(request()))
        val admitted = submission(context)

        host.close()
        host.close()

        assertEquals(1, candidateOwner.closeCount)
        assertNull(host.issueContext(request(conversationStateVersion = 10)))
        assertEquals(
            CoreDataTutorOpenResponseLearningReceipt.NOT_CURRENT,
            host.submitAdmitted(admitted, lease(admitted)),
        )
        assertTrue(evidence.commands.isEmpty())
    }

    private fun readyHost(
        evidence: FakeEvidencePort = FakeEvidencePort(),
        evaluator: IndependentOpenResponseEvaluator = UnavailableEvaluator(),
        candidateOwner: FakeCandidateOwner = FakeCandidateOwner(),
        contextOwner: FakeContextOwner = FakeContextOwner(),
        now: AtomicLong = AtomicLong(NOW),
        contextIssueTimeoutMillis: Long = 1_000,
    ): CoreDataTutorOpenResponseLearningHostAdapter {
        val result =
            CoreDataTutorOpenResponseLearningHostAdapter.assemble(
                evidence = evidence,
                evaluator = evaluator,
                candidateOwner = candidateOwner,
                contextOwner = contextOwner,
                nowEpochMillis = LongSupplier(now::get),
                contextLifetimeMillis = 2_000,
                contextIssueTimeoutMillis = contextIssueTimeoutMillis,
                submissionTimeoutMillis = 2_000,
            )
        return (result as CoreDataTutorOpenResponseLearningAssemblyResult.Available).host
    }

    private fun realEvaluator(
        modelTasks: EvaluatingModelTaskRepository,
        now: AtomicLong,
    ): IndependentOpenResponseEvaluator =
        ModelTaskRepositoryOpenResponseEvaluator(
            modelTasks = modelTasks,
            knowledgeReferenceVerifier = KnowledgeReferenceProofAuthority.create().verifier,
            nowEpochMillis = LongSupplier(now::get),
            physicalTrustRegistry =
                HostPinnedOpenResponsePhysicalTrustRegistry(
                    registryGenerationFingerprint = fingerprint("adapter-test-registry"),
                    tutorPhysicalBackendsByExecution =
                        mapOf(tutorExecutionIdentity() to fingerprint("tutor-backend")),
                    evaluatorExecutions =
                        listOf(
                            HostPinnedOpenResponseEvaluatorExecution(
                                repository = modelTasks,
                                executionFingerprint = checkNotNull(
                                    openResponseEvaluatorExecutionFingerprint(
                                        modelTasks.providerSnapshot(),
                                    ),
                                ),
                                physicalBackendFingerprint = fingerprint("evaluator-backend"),
                            ),
                        ),
                ),
        )

    private class FakeContextOwner(
        val current: AtomicBoolean = AtomicBoolean(true),
        var consumeDisposition: CurrentTutorOpenResponseAuthorizationConsumeDisposition =
            CurrentTutorOpenResponseAuthorizationConsumeDisposition.CONSUMED,
        private val beforeAuthorize:
            suspend (CoreDataTutorOpenResponseContextRequest) -> Unit = {},
    ) : CurrentTutorOpenResponseContextOwner {
        override val learnerId: String = LEARNER_ID
        var consumeCalls: Int = 0

        override suspend fun authorizeCurrent(
            request: CoreDataTutorOpenResponseContextRequest,
            notAfterEpochMillis: Long,
        ): CurrentTutorOpenResponseContextAuthorization {
            beforeAuthorize(request)
            return authorization(request, notAfterEpochMillis)
        }

        override fun isCurrent(
            authorization: CurrentTutorOpenResponseContextAuthorization,
        ): Boolean = current.get()

        override suspend fun consumeCurrentAuthorization(
            authorization: CurrentTutorOpenResponseContextAuthorization,
            scope: CurrentOpenResponseLearningScope,
            candidateIdempotencyKey: String,
        ): CurrentTutorOpenResponseAuthorizationConsumeDisposition {
            consumeCalls += 1
            return consumeDisposition
        }
    }

    private class FakeEvidencePort(
        private val afterRecord: () -> Unit = {},
    ) : LearnerBoundLearningEvidencePort {
        override val learnerId: String = LEARNER_ID
        val commands = mutableListOf<UnsavedStudyEvidenceCommand>()

        override suspend fun submitSavedReview(
            command: SavedReviewEvidenceCommand,
        ): SavedReviewEvidenceWriteResult = SavedReviewEvidenceWriteResult.Rejected

        override suspend fun recordUnsavedStudy(
            command: UnsavedStudyEvidenceCommand,
        ): UnsavedStudyEvidenceWriteResult {
            commands += command
            afterRecord()
            return UnsavedStudyEvidenceWriteResult.PendingAttributionQueued(
                sourceFactId = SOURCE_FACT_ID,
                reviewCaseId = REVIEW_CASE_ID,
                disposition =
                    if (commands.size == 1) {
                        PendingAttributionDisposition.QUEUED
                    } else {
                        PendingAttributionDisposition.DUPLICATE
                    },
            )
        }
    }

    private open class UnavailableEvaluator : IndependentOpenResponseEvaluator {
        override suspend fun evaluate(
            work: IndependentOpenResponseEvaluationWork,
        ): IndependentOpenResponseEvaluationResult =
            IndependentOpenResponseEvaluationResult.ProviderUnavailable
    }

    private class CountingUnavailableEvaluator : UnavailableEvaluator() {
        var calls: Int = 0

        override suspend fun evaluate(
            work: IndependentOpenResponseEvaluationWork,
        ): IndependentOpenResponseEvaluationResult {
            calls += 1
            return super.evaluate(work)
        }
    }

    private class EvaluatingModelTaskRepository(
        private val beforeEmit: () -> Unit,
    ) : ModelTaskRepository {
        private val provider = ProviderCapabilitySnapshot(
            providerId = "external-evaluator",
            providerDisplayName = "外部评价器",
            modelId = "gpt-evaluator-v1",
            supportedTasks = setOf(ModelTaskKind.TUTOR_EVALUATE),
            supportsImageInput = false,
            supportsStructuredOutput = true,
            supportsStreaming = false,
            executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
            providerConfigurationVersion = "external-config-v1",
        )

        override suspend fun capabilities(): ProviderCapabilitySnapshot = provider

        fun providerSnapshot(): ProviderCapabilitySnapshot = provider

        override fun observe(requestId: String): Flow<ModelTaskSnapshot?> = flowOf(null)

        override fun execute(request: ModelTaskRequest): Flow<ModelTaskSnapshot> = flow {
            ModelEgressPolicy.authorize(request, provider, nowEpochMillis = NOW)
            val input = request.input as TutorOpenResponseEvaluationInput
            val issuedScope = Json.parseToJsonElement(input.authorizedScopeForProvider()).jsonObject
            val output = OpenResponseEvaluationModelTaskProtocol.decodeProviderDecision(
                input = input,
                providerJson = buildJsonObject {
                    put("schemaVersion", issuedScope.getValue("schemaVersion"))
                    put("binding", issuedScope.getValue("binding"))
                    put("subject", issuedScope.getValue("subject"))
                    put("outcome", OpenResponseEvaluationOutcome.INCORRECT.name)
                    put("knowledgeEffects", buildJsonArray {})
                    put("evaluator", issuedScope.getValue("evaluator"))
                    put("evidenceFingerprint", issuedScope.getValue("evidenceFingerprint"))
                }.toString(),
                modelVersion = provider.modelId,
            )
            beforeEmit()
            emit(
                ModelTaskSnapshot(
                    taskId = "task-${request.requestId}",
                    request = request,
                    requestFingerprint = ModelTaskFingerprint.of(request),
                    status = ModelTaskStatus.SUCCEEDED,
                    stateVersion = 1L,
                    stage = ModelTaskStage.COMPLETE,
                    userMessage = "",
                    attemptCount = 1,
                    provider = provider,
                    output = output,
                    createdAtEpochMillis = NOW,
                    updatedAtEpochMillis = NOW,
                ),
            )
        }

        override fun executeSensitiveEphemeral(
            request: ModelTaskRequest,
        ): Flow<ModelTaskSnapshot> = execute(request)
    }

    private class FakeCandidateOwner :
        LearnerBoundOpenResponseWeakCandidateOwner,
        AutoCloseable {
        override val learnerId: String = LEARNER_ID
        val proposals = mutableListOf<OpenResponseWeakCandidateProposal>()
        var closeCount: Int = 0
        private val committed =
            mutableMapOf<
                OpenResponseWeakCandidateReceiptQuery,
                OpenResponseWeakCandidateCommitReceipt,
            >()

        override suspend fun findCommitted(
            query: OpenResponseWeakCandidateReceiptQuery,
        ): OpenResponseWeakCandidateCommitReceipt? = committed[query]

        override suspend fun submit(
            proposal: OpenResponseWeakCandidateProposal,
            authorization: OpenResponseWeakCandidateSubmissionAuthorization,
        ): OpenResponseWeakCandidateCommitResult {
            val epoch = authorization.requireCurrentEpoch()
            assertEquals(epoch, authorization.requireCurrentEpoch())
            proposals += proposal
            val query =
                OpenResponseWeakCandidateReceiptQuery(
                    learnerId = proposal.learnerId,
                    sourceFactId = proposal.sourceFactId,
                    reviewCaseId = proposal.reviewCaseId,
                    scopeFingerprint = proposal.scope.canonicalFingerprint,
                    candidateIdempotencyKey = proposal.candidateIdempotencyKey,
                )
            val receipt = committed.getOrPut(query) {
                OpenResponseWeakCandidateCommitReceipt(
                    query = query,
                    receiptFingerprint = fingerprint("candidate-receipt"),
                )
            }
            return OpenResponseWeakCandidateCommitResult(
                OpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
                receipt,
            )
        }

        override fun close() {
            closeCount += 1
        }
    }

    private companion object {
        const val LEARNER_ID = "learner-local"
        const val NOW = 10_000L
        const val CONTEXT_LIFETIME_MILLIS = 2_000L
        const val ANSWER = "因为导数为正，所以函数在该区间单调递增。"
        const val SOURCE_FACT_ID = "ordinary-study:source-fact"
        const val REVIEW_CASE_ID = "open-response-review-case"

        val QUESTION =
            QuestionDocument(
                id = "question-document",
                blocks =
                    listOf(
                        ContentBlock.Paragraph(
                            id = "question-block",
                            markdown = "已知函数在区间内导数为正，判断函数的单调性。",
                        ),
                    ),
            )

        fun request(
            conversationStateVersion: Long = 7,
            mode: TutorExplanationMode = TutorExplanationMode.GUIDED,
            modeVersion: Long = 5,
            evidenceRequestId: String = "guided-evidence-request",
        ): CoreDataTutorOpenResponseContextRequest =
            CoreDataTutorOpenResponseContextRequest(
                conversationId = "conversation-current",
                conversationGeneration = 3,
                conversationStateVersion = conversationStateVersion,
                questionDocumentId = QUESTION.id,
                questionRevisionNumber = 2,
                subject = SubjectKind.MATH,
                questionDocument = QUESTION,
                explanationMode = mode,
                modeVersion = modeVersion,
                turnReferenceId = "turn-current",
                turnOrdinal = 4,
                turnGeneration = 6,
                evidenceRequestId = evidenceRequestId,
                attemptOrdinal = 2,
                hintCount = 1,
                answerWasRevealed = true,
                requestVersion = 9,
            )

        fun authorization(
            request: CoreDataTutorOpenResponseContextRequest,
            expiresAtEpochMillis: Long,
        ): CurrentTutorOpenResponseContextAuthorization =
            CurrentTutorOpenResponseContextAuthorization(
                learnerId = LEARNER_ID,
                requestFingerprint = request.canonicalFingerprint,
                presentationFingerprint = fingerprint("presentation"),
                problemFingerprint = fingerprint("problem"),
                problemFamilyFingerprint = fingerprint("problem-family"),
                attributionPolicyVersion = "attribution-policy-v3",
                responsePolicyVersion = "open-response-policy-v1",
                rubricCanonicalFingerprint = fingerprint("rubric"),
                verifiedKnowledgeProofs = emptyList(),
                teachingReferences = emptyList(),
                evaluator = OpenResponseEvaluatorKind.RUBRIC,
                evaluatorPolicyFingerprint = fingerprint("evaluator-policy"),
                prohibitedEvaluatorExecutionFingerprint = tutorExecutionIdentity(),
                expiresAtEpochMillis = expiresAtEpochMillis,
            )

        fun submission(
            context: CoreDataTutorOpenResponseLearningContext,
        ): CoreDataTutorOpenResponseAdmittedSubmission =
            CoreDataTutorOpenResponseAdmittedSubmission(
                context = context,
                sourceSubmissionId = "feature-submission-current",
                sourceMessageId = "feature-message-current",
                admission =
                    when (context.request.explanationMode) {
                        TutorExplanationMode.GUIDED ->
                            CoreDataTutorOpenResponseAdmission.GuidedFreeResponse(
                                context.request.evidenceRequestId,
                            )
                        TutorExplanationMode.DIRECT ->
                            CoreDataTutorOpenResponseAdmission
                                .DirectSpecificCurrentQuestionGap(
                                    fingerprint("specific-current-question-gap"),
                                )
                },
                currentAnswer = ANSWER,
                responseBinding = fingerprint("current-answer-binding"),
                elapsedDurationMillis = 12_000,
                occurredAtEpochMillis = NOW,
            )

        fun lease(
            submission: CoreDataTutorOpenResponseAdmittedSubmission,
            current: Boolean = true,
        ): CoreDataTutorOpenResponseSubmissionLease =
            CoreDataTutorOpenResponseSubmissionLease(
                featureContextFingerprint = submission.context.featureContextFingerprint,
                responseBinding = submission.responseBinding,
                isCurrentBlock = { current },
            )

        fun claimedLease(
            submission: CoreDataTutorOpenResponseAdmittedSubmission,
            isCurrent: () -> Boolean = { true },
        ): CoreDataTutorOpenResponseSubmissionLease =
            CoreDataTutorOpenResponseSubmissionLease.afterDurableClaim(
                featureContextFingerprint = submission.context.featureContextFingerprint,
                responseBinding = submission.responseBinding,
                isCurrentBlock = isCurrent,
            )

        fun fingerprint(seed: String): String =
            CanonicalSha256("core-data-tutor-open-response-host-test")
                .field("seed", seed)
                .finish()

        fun tutorExecutionIdentity(): String =
            checkNotNull(
                openResponseEvaluatorExecutionFingerprint(
                    ProviderCapabilitySnapshot(
                        providerId = "tutor-provider",
                        providerDisplayName = "讲题模型",
                        modelId = "tutor-model-v1",
                        supportedTasks = setOf(ModelTaskKind.TUTOR_PLAN),
                        supportsImageInput = true,
                        supportsStructuredOutput = true,
                        supportsStreaming = true,
                        executionLocation = ModelExecutionLocation.EXTERNAL_PROVIDER,
                    ),
                ),
            )
    }
}
