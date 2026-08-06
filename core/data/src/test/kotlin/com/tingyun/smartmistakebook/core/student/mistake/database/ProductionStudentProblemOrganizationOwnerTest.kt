package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeContextRepository
import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeReferenceBatchRequest
import com.tingyun.smartmistakebook.core.data.mistake.CommittedStudentProblemOrganizationSource
import com.tingyun.smartmistakebook.core.data.mistake.CommittedStudentProblemOrganizationSourcePort
import com.tingyun.smartmistakebook.core.data.mistake.CompletedStudentProblemOrganizationTaskReadPort
import com.tingyun.smartmistakebook.core.data.mistake.PrepareStudentProblemOrganizationCommand
import com.tingyun.smartmistakebook.core.data.mistake.ProductionStudentProblemOrganizationOwnerFactory
import com.tingyun.smartmistakebook.core.data.mistake.ReviewAndWriteStudentProblemOrganizationCommand
import com.tingyun.smartmistakebook.core.data.mistake.ReviewedStudentProblemOrganizationMapper
import com.tingyun.smartmistakebook.core.data.mistake.ReviewedStudentProblemOrganizationReviewPort
import com.tingyun.smartmistakebook.core.data.mistake.StudentProblemOrganizationLeaseGuard
import com.tingyun.smartmistakebook.core.data.mistake.StudentProblemOrganizationNonApplication
import com.tingyun.smartmistakebook.core.data.mistake.StudentProblemOrganizationReviewWriteOutcome
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationCommitLinearizer
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionRevokedException
import com.tingyun.smartmistakebook.core.data.session.OrganizationSourceCommitSessionReceipt
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.model.AtomicKnowledgeSuggestion
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocument
import com.tingyun.smartmistakebook.core.model.CapturedQuestionDocumentFingerprint
import com.tingyun.smartmistakebook.core.model.ClassificationDimension
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseCatalogProvenance
import com.tingyun.smartmistakebook.core.model.KnowledgeBaseNodeContext
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeGranularity
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeKind
import com.tingyun.smartmistakebook.core.model.KnowledgeNodeVerificationStatus
import com.tingyun.smartmistakebook.core.model.ModelEgressAssetGrant
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskKind
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.NormalizedSourceRegion
import com.tingyun.smartmistakebook.core.model.ProblemClassificationSuggestion
import com.tingyun.smartmistakebook.core.model.ProblemOrganizationAuthorizationGrant
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
import com.tingyun.smartmistakebook.core.model.WritingLayer
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionStudentProblemOrganizationOwnerTest {
    @Test
    fun sourceReceiptMismatchIsTerminalAndWritesNothing() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        harness.source =
            harness.source.copy(sourceReceiptPayloadFingerprint = fingerprint("other-source"))

        harness.assertTerminalNonWrite(request)
    }

    @Test
    fun lateModelCompletionIsTerminalAndWritesNothing() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask =
            harness.successfulTask(request).copy(
                updatedAtEpochMillis = LEASE_EXPIRES_AT,
            )

        harness.assertTerminalNonWrite(request)
    }

    @Test
    fun exactReplayReturnsTheStoredReceiptWithoutASecondWrite() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)

        assertTrue(harness.review(request) is StudentProblemOrganizationReviewWriteOutcome.Applied)
        assertTrue(harness.review(request) is StudentProblemOrganizationReviewWriteOutcome.Applied)
        assertEquals(1, harness.organizations.organizeCalls)
    }

    @Test
    fun leaseOwnerChangeBetweenReviewAndCommitWritesNothing() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        harness.leaseDecisions.addAll(listOf(true, false))

        harness.assertTerminalNonWrite(request)
    }

    @Test
    fun executionRevokedWhileMapAndReviewIsSuspendedWritesNothing() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        val reviewStarted = CompletableDeferred<Unit>()
        val resumeReview = CompletableDeferred<Unit>()
        val executionCurrent = AtomicBoolean(true)
        harness.beforeMapAndReview = {
            reviewStarted.complete(Unit)
            resumeReview.await()
        }

        val failure =
            async {
                runCatching {
                    harness.review(
                        request = request,
                        requireCurrentExecution = {
                            if (!executionCurrent.get()) {
                                throw ProductionProblemOrganizationExecutionRevokedException()
                            }
                        },
                    )
                }.exceptionOrNull()
            }
        reviewStarted.await()
        executionCurrent.set(false)
        resumeReview.complete(Unit)

        assertTrue(failure.await() is ProductionProblemOrganizationExecutionRevokedException)
        assertEquals(0, harness.organizations.organizeCalls)
    }

    @Test
    fun executionRevokedAtFinalLeaseCheckImmediatelyBeforeWriteWritesNothing() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        val checksUntilRevocation = AtomicInteger(Int.MAX_VALUE)
        harness.afterLeaseCheck = { leaseCheck ->
            if (leaseCheck == 2) checksUntilRevocation.set(2)
        }

        val failure =
            runCatching {
                harness.review(
                    request = request,
                    requireCurrentExecution = {
                        val remaining = checksUntilRevocation.get()
                        if (
                            remaining != Int.MAX_VALUE &&
                            checksUntilRevocation.decrementAndGet() == 0
                        ) {
                            throw ProductionProblemOrganizationExecutionRevokedException()
                        }
                    },
                )
            }.exceptionOrNull()

        assertTrue(failure is ProductionProblemOrganizationExecutionRevokedException)
        assertEquals(0, harness.organizations.organizeCalls)
    }

    @Test
    fun commitLinearizedBeforeRevocationFinishesThenRevocationReturns() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        val transactionStarted = CompletableDeferred<Unit>()
        val resumeTransaction = CompletableDeferred<Unit>()
        val commitFence = ProductionProblemOrganizationCommitLinearizer { true }
        harness.organizations.beforeCommit = {
            transactionStarted.complete(Unit)
            resumeTransaction.await()
        }

        val execution =
            async {
                runCatching {
                    harness.review(
                        request = request,
                        requireCurrentExecution = commitFence::requireCurrentOwner,
                        commitFence = commitFence,
                    )
                }.exceptionOrNull()
            }
        transactionStarted.await()
        val revocation = async(Dispatchers.Default) { commitFence.close() }
        while (commitFence.isCurrent()) yield()
        assertFalse(revocation.isCompleted)
        resumeTransaction.complete(Unit)

        assertTrue(execution.await() is ProductionProblemOrganizationExecutionRevokedException)
        revocation.await()
        assertEquals(1, harness.organizations.organizeCalls)
    }

    @Test
    fun revocationLinearizedBeforeCommitRejectsWithoutWriting() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        val commitFence = ProductionProblemOrganizationCommitLinearizer { true }
        commitFence.close()

        val failure =
            runCatching {
                harness.review(
                    request = request,
                    requireCurrentExecution = commitFence::requireCurrentOwner,
                    commitFence = commitFence,
                )
            }.exceptionOrNull()

        assertTrue(failure is ProductionProblemOrganizationExecutionRevokedException)
        assertEquals(0, harness.organizations.organizeCalls)
    }

    @Test
    fun crossLearnerAndCrossSubjectSourcesBothWriteNothing() = runBlocking {
        listOf(
            { source: CommittedStudentProblemOrganizationSource ->
                source.withProblemIdentity(learnerId = "another-learner")
            },
            { source: CommittedStudentProblemOrganizationSource ->
                source.withProblemIdentity(subject = SubjectKind.PHYSICS)
            },
        ).forEach { mutate ->
            val harness = Harness()
            val request = harness.prepare()
            harness.completedTask = harness.successfulTask(request)
            harness.source = mutate(harness.source)

            harness.assertTerminalNonWrite(request)
        }
    }

    @Test
    fun lowConfidenceModelClassificationWritesNothing() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask =
            harness.successfulTask(
                request,
                chapterConfidence = 0.40,
            )

        harness.assertTerminalNonWrite(request)
    }

    @Test
    fun knowledgeGenerationDriftAfterPreparationWritesNothing() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        harness.knowledge.generation = KNOWLEDGE_GENERATION + 1

        harness.assertTerminalNonWrite(request)
    }

    @Test
    fun crashReplayUsesStoredKnowledgeBindingWithoutReissuingProofs() = runBlocking {
        val harness = Harness()
        val request = harness.prepare()
        harness.completedTask = harness.successfulTask(request)
        assertTrue(harness.review(request) is StudentProblemOrganizationReviewWriteOutcome.Applied)
        harness.knowledge.failVerification = true

        val recovered = harness.review(request)

        assertTrue(recovered is StudentProblemOrganizationReviewWriteOutcome.Applied)
        assertEquals(1, harness.organizations.organizeCalls)
    }

    private class Harness {
        var now: Long = NOW
        var source: CommittedStudentProblemOrganizationSource = source()
        var completedTask: ModelTaskSnapshot? = null
        val leaseDecisions = ArrayDeque<Boolean>()
        val knowledge = FakeKnowledgeRepository(contexts())
        val organizations = FakeStudentProblemOrganizationPort(LEARNER_ID)
        var beforeMapAndReview: suspend () -> Unit = {}
        var afterLeaseCheck: (Int) -> Unit = {}
        private var leaseCheckCount: Int = 0
        private val knowledgeAuthority = knowledge.authority
        private val sourceReceipt =
            OrganizationSourceCommitSessionReceipt(
                scope = SCOPE,
                receiptId = SOURCE_RECEIPT_ID,
                payloadFingerprint = SOURCE_RECEIPT_FINGERPRINT,
                recordedAtEpochMillis = SOURCE_COMMITTED_AT,
            )

        fun newOwner() =
            ProductionStudentProblemOrganizationOwnerFactory.createFromNarrowPorts(
                scope = SCOPE,
                sources =
                    CommittedStudentProblemOrganizationSourcePort { requested ->
                        source.takeIf {
                            requested.scope == SCOPE &&
                                requested.receiptId == SOURCE_RECEIPT_ID
                        }
                    },
                completedTasks =
                    CompletedStudentProblemOrganizationTaskReadPort { requestId ->
                        completedTask?.takeIf { it.request.requestId == requestId }
                    },
                leaseGuard =
                    StudentProblemOrganizationLeaseGuard {
                        val isHeld = leaseDecisions.removeFirstOrNull() ?: true
                        leaseCheckCount += 1
                        afterLeaseCheck(leaseCheckCount)
                        isHeld
                    },
                knowledgeContext = knowledge,
                reviewPort = signedReviewPort(),
                studentOrganizations = organizations,
                clock = { now },
            )

        suspend fun prepare(): ModelTaskRequest =
            newOwner().prepareCommitted(
                PrepareStudentProblemOrganizationCommand(
                    scope = SCOPE,
                    workId = WORK_ID,
                    sourceReceipt = sourceReceipt,
                    provider = PROVIDER,
                    authorization = authorization(source),
                    requestVersion = 3,
                    occurredAtEpochMillis = REQUESTED_AT,
                ),
            ).preparation.request

        suspend fun review(
            request: ModelTaskRequest,
            requireCurrentExecution: () -> Unit = {},
            commitFence: StudentProblemOrganizationCommitFence =
                object : StudentProblemOrganizationCommitFence {
                    override fun requireCurrentOwner() {
                        requireCurrentExecution()
                    }
                },
        ): StudentProblemOrganizationReviewWriteOutcome =
            newOwner().reviewAndWrite(
                reviewCommand(request),
                requireCurrentExecution,
                commitFence,
            )

        fun reviewCommand(
            request: ModelTaskRequest,
        ) = ReviewAndWriteStudentProblemOrganizationCommand(
            scope = SCOPE,
            workId = WORK_ID,
            sourceReceipt = sourceReceipt,
            requestId = request.requestId,
            requestCanonicalFingerprint = ModelTaskFingerprint.of(request),
            expectedWorkVersionSequence = 7,
            expectedWorkVersionFingerprint = fingerprint("running-work"),
            leaseOwner = "organization-worker",
            leaseExpiresAtEpochMillis = LEASE_EXPIRES_AT,
        )

        suspend fun assertTerminalNonWrite(request: ModelTaskRequest) {
            val outcome = review(request)
            assertTrue(outcome is StudentProblemOrganizationReviewWriteOutcome.NotApplied)
            assertEquals(
                StudentProblemOrganizationNonApplication.TERMINAL,
                (outcome as StudentProblemOrganizationReviewWriteOutcome.NotApplied).disposition,
            )
            assertEquals(0, organizations.organizeCalls)
        }

        fun successfulTask(
            request: ModelTaskRequest,
            chapterConfidence: Double = 0.98,
        ): ModelTaskSnapshot {
            val input = request.input as ProblemOrganizationV3Input
            val chapter = input.knowledgeBaseNodes.single { it.granularity == KnowledgeNodeGranularity.TOPIC }
            val atom = input.knowledgeBaseNodes.single { it.granularity == KnowledgeNodeGranularity.ATOMIC }
            val output =
                ProblemOrganizationOutput(
                    problemId = input.problemId,
                    problemRevisionId = input.problemRevisionId,
                    practiceUnitId = input.practiceUnitId,
                    plan =
                        ProblemOrganizationPlan(
                            summaryMarkdown = "先判断导数符号，再确定单调区间。",
                            reviewPriorityMarkdown = "需要再巩固导数符号与单调性的对应关系。",
                            targetedEvidenceLabels = emptyList(),
                            classifications =
                                listOf(
                                    ProblemClassificationSuggestion(
                                        dimension = ClassificationDimension.CHAPTER,
                                        displayName = chapter.canonicalName,
                                        rationaleMarkdown = "题目属于函数与导数部分。",
                                        confidence = chapterConfidence,
                                    ),
                                    ProblemClassificationSuggestion(
                                        dimension = ClassificationDimension.KNOWLEDGE,
                                        displayName = checkNotNull(atom.parentCanonicalName),
                                        rationaleMarkdown = "解题需要判断导数符号。",
                                        confidence = 0.96,
                                    ),
                                ),
                            relations = emptyList(),
                            schemaVersion = ProblemOrganizationPlan.SCHEMA_VERSION,
                            atomicKnowledge =
                                listOf(
                                    AtomicKnowledgeSuggestion(
                                        referenceId = "atom-derivative-sign",
                                        canonicalName = atom.canonicalName,
                                        aliases = emptyList(),
                                        kind = atom.kind,
                                        parentKnowledgeDisplayName =
                                            checkNotNull(atom.parentCanonicalName),
                                        matchedKnowledgeNodeId = atom.knowledgeNodeId,
                                        prerequisiteReferenceIds = emptyList(),
                                        observableOutcomeMarkdown = "能根据导数符号判断单调性。",
                                        boundaryMarkdown = "只判断当前函数区间。",
                                        confidence = 0.96,
                                    ),
                                ),
                            stepAttributions =
                                listOf(
                                    ProblemStepKnowledgeAttribution(
                                        stepOrdinal = 1,
                                        stepSummaryMarkdown = "判断各区间导数的正负。",
                                        atomicReferenceIds = listOf("atom-derivative-sign"),
                                    ),
                                ),
                            errorAttributionCandidates = emptyList(),
                        ),
                    modelVersion = "model-version-1",
                )
            return ModelTaskSnapshot(
                taskId = "model-task-organization",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                status = ModelTaskStatus.SUCCEEDED,
                stateVersion = 2,
                stage = ModelTaskStage.COMPLETE,
                userMessage = "整理完成",
                attemptCount = 1,
                provider = PROVIDER,
                output = output,
                createdAtEpochMillis = request.occurredAtEpochMillis,
                updatedAtEpochMillis = TASK_COMPLETED_AT,
            )
        }

        private fun signedReviewPort() =
            ReviewedStudentProblemOrganizationReviewPort { local, task, proofs ->
                beforeMapAndReview()
                val unsigned =
                    ReviewedStudentProblemOrganizationMapper.mapUnsigned(local, task, proofs)
                val binding =
                    ReviewedStudentProblemOrganizationMapping.requireExact(
                        unsigned,
                        task,
                        knowledgeAuthority.verifier::verifies,
                    )
                val provenance = unsigned.provenance
                val revision = unsigned.problemRevision
                val reviewAuthority =
                    StudentProblemOrganizationReviewAuthority.create(
                        provenance.reviewIssuerKeyId,
                        provenance.reviewIssuerVersion,
                    )
                val proof =
                    reviewAuthority.issuer.issue(
                        revision.problem.learnerId,
                        revision.problem.problemId,
                        revision.revisionId,
                        revision.revisionNumber,
                        revision.documentCanonicalFingerprint,
                        unsigned.requestId,
                        unsigned.requestCanonicalFingerprint,
                        provenance.requestVersion,
                        provenance.modelProviderId,
                        provenance.modelId,
                        provenance.resultModelVersion,
                        provenance.providerConfigurationVersion,
                        provenance.modelTaskSchemaVersion,
                        provenance.organizationPlanSchemaVersion,
                        binding.reviewedOutputCanonicalFingerprint,
                        binding.mappingPolicyVersion,
                        binding.knowledgeManifestFingerprint,
                        binding.knowledgeActivationGeneration,
                        binding.finalCommandCanonicalFingerprint,
                        provenance.reviewSource.name,
                        provenance.reviewVersion,
                        provenance.reviewIssuerKeyId,
                        provenance.reviewIssuerVersion,
                        provenance.reviewIssuedAtEpochMillis,
                        provenance.reviewExpiresAtEpochMillis,
                    )
                unsigned.withVerifiedReview(proof)
            }
    }

    private class FakeKnowledgeRepository(
        private val nodes: List<KnowledgeBaseNodeContext>,
    ) : ReviewedProblemKnowledgeContextRepository {
        val authority: KnowledgeReferenceProofAuthority = KnowledgeReferenceProofAuthority.create()
        var generation: Long = KNOWLEDGE_GENERATION
        var failVerification: Boolean = false

        override suspend fun read(
            subject: SubjectKind,
            questionText: String,
        ): List<KnowledgeBaseNodeContext> =
            nodes.takeIf { subject == SubjectKind.MATH }.orEmpty()

        override suspend fun verifyExactReferences(
            request: ReviewedProblemKnowledgeReferenceBatchRequest,
        ): List<VerifiedKnowledgeReferenceProof> {
            check(!failVerification) { "knowledge verifier is intentionally unavailable" }
            val byId = request.knowledgeBaseNodes.associateBy { it.knowledgeNodeId }
            return request.exactKnowledgeNodeIds.map { knowledgeNodeId ->
                val node = checkNotNull(byId[knowledgeNodeId])
                authority.issuer.issue(
                    KnowledgeNodeRef(
                        subject = node.subject,
                        knowledgeNodeId = node.knowledgeNodeId,
                        taxonomyVersion = node.taxonomyVersion,
                        knowledgePackVersion = KNOWLEDGE_PACK_VERSION,
                    ),
                    KNOWLEDGE_MANIFEST,
                    generation,
                )
            }
        }
    }

    private class FakeStudentProblemOrganizationPort(
        override val learnerId: String,
    ) : StudentProblemOrganizationPort {
        var organizeCalls: Int = 0
        var beforeCommit: suspend () -> Unit = {}
        private var receipt: StudentProblemOrganizationReceipt? = null
        private var knowledge: StudentProblemOrganizationKnowledgeSnapshot? = null

        override suspend fun organize(
            command: OrganizeStudentProblemCommand,
            commitFence: StudentProblemOrganizationCommitFence,
        ): OrganizeStudentProblemResult = commitFence.linearizeCommit {
            val provenance = command.provenance
            val stored =
                StudentProblemOrganizationReceipt(
                    receiptId = command.receiptId,
                    requestId = command.requestId,
                    requestCanonicalFingerprint = command.requestCanonicalFingerprint,
                    requestVersion = provenance.requestVersion,
                    organizationRevision = command.organizationRevision,
                    supersedesReceiptId = command.supersedesReceiptId,
                    previousPayloadCanonicalFingerprint =
                        command.previousPayloadCanonicalFingerprint,
                    problemRevision = command.problemRevision,
                    modelProviderId = provenance.modelProviderId,
                    modelId = provenance.modelId,
                    modelVersion = provenance.resultModelVersion,
                    providerConfigurationVersion = provenance.providerConfigurationVersion,
                    modelTaskSchemaVersion = provenance.modelTaskSchemaVersion,
                    organizationPlanSchemaVersion = provenance.organizationPlanSchemaVersion,
                    reviewSource = provenance.reviewSource,
                    reviewVersion = provenance.reviewVersion,
                    reviewIssuerKeyId = provenance.reviewIssuerKeyId,
                    reviewIssuerVersion = provenance.reviewIssuerVersion,
                    reviewIssuedAtEpochMillis = provenance.reviewIssuedAtEpochMillis,
                    reviewExpiresAtEpochMillis = provenance.reviewExpiresAtEpochMillis,
                    payloadCanonicalFingerprint = command.payloadCanonicalFingerprint,
                    errorOccurrenceCount = command.errorOccurrences.size,
                    classificationCount = command.classifications.size,
                    stepKnowledgeBindingCount = command.stepKnowledgeBindings.size,
                    errorAttributionCount = command.errorAttributions.size,
                    facetCount = command.facets.size,
                    completedAtEpochMillis = command.completedAtEpochMillis,
                )
            beforeCommit()
            organizeCalls += 1
            receipt = stored
            val proof = command.classifications.first().verifiedKnowledgeReference
            knowledge =
                StudentProblemOrganizationKnowledgeSnapshot(
                    proof.manifestFingerprint,
                    proof.activationGeneration,
                )
            OrganizeStudentProblemResult(created = true, receipt = stored)
        }

        override suspend fun readReceipt(
            requestId: String,
            requestCanonicalFingerprint: String,
        ): StudentProblemOrganizationReceipt? =
            receipt?.takeIf {
                it.requestId == requestId &&
                    it.requestCanonicalFingerprint == requestCanonicalFingerprint
            }

        override suspend fun readCurrent(
            problemRevision: StudentProblemRevisionRef,
        ): StudentProblemOrganizationReceipt? =
            receipt?.takeIf { it.problemRevision == problemRevision }

        override suspend fun readKnowledgeSnapshot(
            receiptId: String,
        ): StudentProblemOrganizationKnowledgeSnapshot? =
            knowledge.takeIf { receipt?.receiptId == receiptId }

        override suspend fun readCurrentKnowledgeSnapshot(
            problemRevision: StudentProblemRevisionRef,
        ): StudentProblemOrganizationKnowledgeSnapshot? =
            knowledge.takeIf { receipt?.problemRevision == problemRevision }

        override suspend fun readCurrentKnowledgeAttribution(
            problemRevision: StudentProblemRevisionRef,
            requiredKnowledgeSnapshot: StudentProblemOrganizationKnowledgeSnapshot,
        ): StudentProblemKnowledgeAttributionSnapshot? = null
    }

    private companion object {
        const val LEARNER_ID = "learner-1"
        const val SOURCE_RECEIPT_ID = "source-receipt-1"
        const val WORK_ID = "organization-work-1"
        const val SOURCE_COMMITTED_AT = 80L
        const val REQUESTED_AT = 100L
        const val TASK_COMPLETED_AT = 150L
        const val NOW = 200L
        const val LEASE_EXPIRES_AT = 1_000L
        const val KNOWLEDGE_GENERATION = 3L
        const val KNOWLEDGE_PACK_VERSION = "knowledge-pack-v1"
        val SOURCE_RECEIPT_FINGERPRINT = fingerprint("source-receipt")
        val KNOWLEDGE_MANIFEST = fingerprint("knowledge-manifest")
        val SCOPE = SessionScope(LEARNER_ID)
        val PROVIDER =
            ProviderCapabilitySnapshot(
                providerId = "provider-1",
                providerDisplayName = "Provider",
                modelId = "model-1",
                supportedTasks = setOf(ModelTaskKind.PROBLEM_CLASSIFY),
                supportsImageInput = true,
                supportsStructuredOutput = true,
                supportsStreaming = false,
                providerConfigurationVersion = "provider-config-v1",
            )

        fun source(): CommittedStudentProblemOrganizationSource {
            val captured = capturedDocument()
            val revision = revision(captured)
            val asset =
                CaptureSourceAssetRef(
                    assetId = "source-page-1",
                    sha256 = fingerprint("source-asset"),
                    width = 1_200,
                    height = 1_600,
                    pageIndex = 0,
                )
            return CommittedStudentProblemOrganizationSource(
                sourceReceiptId = SOURCE_RECEIPT_ID,
                sourceReceiptPayloadFingerprint = SOURCE_RECEIPT_FINGERPRINT,
                draftId = "draft-1",
                problemRevision = revision,
                capturedDocument = captured,
                sourceAssets = listOf(asset),
                egressAssets =
                    listOf(
                        ModelEgressAssetGrant(
                            assetId = asset.assetId,
                            sha256 = asset.sha256,
                            byteSize = 2_048,
                            width = asset.width,
                            height = asset.height,
                        ),
                    ),
                errorOccurrences =
                    listOf(
                        StudentProblemErrorOccurrenceRef(
                            occurrenceId = "error-occurrence-1",
                            problemRevision = revision,
                            occurrenceCanonicalFingerprint = fingerprint("error-occurrence"),
                        ),
                    ),
                committedAtEpochMillis = SOURCE_COMMITTED_AT,
            )
        }

        fun authorization(source: CommittedStudentProblemOrganizationSource) =
            ProblemOrganizationAuthorizationGrant(
                authorizationId = "organization-authorization-1",
                sourceDraftId = source.draftId,
                providerId = PROVIDER.providerId,
                modelId = PROVIDER.modelId,
                providerConfigurationVersion = PROVIDER.providerConfigurationVersion,
                approvedAtEpochMillis = REQUESTED_AT - 1,
                expiresAtEpochMillis = LEASE_EXPIRES_AT,
                assets = source.egressAssets,
            )

        fun contexts(): List<KnowledgeBaseNodeContext> =
            listOf(
                KnowledgeBaseNodeContext(
                    knowledgeNodeId = "math-topic-functions",
                    subject = SubjectKind.MATH,
                    canonicalName = "函数与导数",
                    aliases = listOf("导数章节"),
                    kind = KnowledgeNodeKind.TOPIC,
                    granularity = KnowledgeNodeGranularity.TOPIC,
                    parentCanonicalName = null,
                    taxonomyVersion = "taxonomy-v1",
                    catalogProvenance = knowledgeCatalogProvenance(),
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                ),
                KnowledgeBaseNodeContext(
                    knowledgeNodeId = "math-atom-derivative-sign",
                    subject = SubjectKind.MATH,
                    canonicalName = "根据导数符号判断单调性",
                    aliases = emptyList(),
                    kind = KnowledgeNodeKind.REASONING,
                    granularity = KnowledgeNodeGranularity.ATOMIC,
                    parentCanonicalName = "导数应用",
                    taxonomyVersion = "taxonomy-v1",
                    catalogProvenance = knowledgeCatalogProvenance(),
                    verificationStatus = KnowledgeNodeVerificationStatus.CURATED,
                ),
            )

        private fun knowledgeCatalogProvenance() =
            KnowledgeBaseCatalogProvenance(
                packId = "pack",
                knowledgePackVersion = "pack-v1",
                taxonomyVersion = "taxonomy-v1",
                manifestFingerprint = "a".repeat(64),
                activationGeneration = 7L,
            )

        fun capturedDocument(): CapturedQuestionDocument =
            CapturedQuestionDocument(
                document =
                    QuestionDocument(
                        id = "question-document-1",
                        blocks =
                            listOf(
                                ContentBlock.Paragraph(
                                    id = "question-block-1",
                                    markdown = "根据导数符号判断函数的单调区间。",
                                ),
                            ),
                    ),
                blockEvidence =
                    listOf(
                        QuestionBlockEvidence(
                            blockId = "question-block-1",
                            sourceAssetId = "source-page-1",
                            sourceRegion = NormalizedSourceRegion(0.1, 0.1, 0.9, 0.9),
                            writingLayer = WritingLayer.PRINTED,
                            provenance = QuestionBlockProvenance.USER_CORRECTION,
                            confidence = 0.98,
                            reviewStatus = QuestionBlockReviewStatus.USER_CONFIRMED,
                        ),
                    ),
            )

        fun revision(captured: CapturedQuestionDocument): StudentProblemRevisionRef =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        problemId = "problem-1",
                        practiceUnitId = "practice-unit-1",
                    ),
                revisionId = "revision-1",
                revisionNumber = 1,
                documentCanonicalFingerprint = CapturedQuestionDocumentFingerprint.of(captured),
            )

        fun CommittedStudentProblemOrganizationSource.withProblemIdentity(
            learnerId: String = problemRevision.problem.learnerId,
            subject: SubjectKind = problemRevision.problem.subject,
        ): CommittedStudentProblemOrganizationSource {
            val changedRevision =
                problemRevision.copy(
                    problem = problemRevision.problem.copy(learnerId = learnerId, subject = subject),
                )
            return copy(
                problemRevision = changedRevision,
                errorOccurrences =
                    errorOccurrences.map { occurrence ->
                        occurrence.copy(problemRevision = changedRevision)
                    },
            )
        }

        fun fingerprint(value: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
