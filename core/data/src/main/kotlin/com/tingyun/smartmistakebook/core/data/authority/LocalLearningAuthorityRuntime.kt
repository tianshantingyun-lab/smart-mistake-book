package com.tingyun.smartmistakebook.core.data.authority

import android.content.Context
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureOccurrenceCommitPort
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureOccurrenceCommitPortFactory
import com.tingyun.smartmistakebook.core.data.capture.ProductionBatchImportRepositoryFactory
import com.tingyun.smartmistakebook.core.data.capture.ProductionCaptureWorkflowRepositoryFactory
import com.tingyun.smartmistakebook.core.data.knowledge.ProductionHighSchoolKnowledgeCatalogBootstrap
import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeContextRepository
import com.tingyun.smartmistakebook.core.data.knowledge.ReviewedProblemKnowledgeContextRepositoryFactory
import com.tingyun.smartmistakebook.core.data.knowledge.TutorTeachingReferenceRepositoryFactory
import com.tingyun.smartmistakebook.core.data.mastery.LearningMasteryKnowledgeActivation
import com.tingyun.smartmistakebook.core.data.mastery.LocalLearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.data.model.ConfiguredModelExecutionGateway
import com.tingyun.smartmistakebook.core.data.model.ModelTaskRepositoryFactory
import com.tingyun.smartmistakebook.core.data.model.RestrictedModelAssetSourceFactory
import com.tingyun.smartmistakebook.core.data.model.SharedProjectionWorkloadGate
import com.tingyun.smartmistakebook.core.data.mistake.LearnerBoundStudentMistakeMasteryRead
import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationTrustedAnswerRuleCompletionPort
import com.tingyun.smartmistakebook.core.data.mistake.ProblemOrganizationWorkProcessor
import com.tingyun.smartmistakebook.core.data.mistake.ProductionStudentProblemOrganizationOwnerFactory
import com.tingyun.smartmistakebook.core.data.mistake.StudentAuthoritativeMistakeOrganizationRepositoryFactory
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeKnowledgeCatalogRevision
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepositoryFactory
import com.tingyun.smartmistakebook.core.data.openresponse.LearnerBoundOpenResponseWeakCandidateOwner
import com.tingyun.smartmistakebook.core.data.openresponse.LearnerMasteryOpenResponseWeakCandidateOwnerAdapter
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseLearningAssemblyResult
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseLearningHostAssembly
import com.tingyun.smartmistakebook.core.data.openresponse.CoreDataTutorOpenResponseLearningHostPort
import com.tingyun.smartmistakebook.core.data.openresponse.CurrentTutorFreeResponseSubmissionAdapter
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationCommitLinearizer
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationScheduling
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionLeaseAuthority
import com.tingyun.smartmistakebook.core.data.production.ProductionProblemOrganizationExecutionResolver
import com.tingyun.smartmistakebook.core.data.production.issueProductionProblemOrganizationExecutionResolver
import com.tingyun.smartmistakebook.core.data.production.ProductionWorkManagerCoordination
import com.tingyun.smartmistakebook.core.data.production.productionWorkManagerCoordination
import com.tingyun.smartmistakebook.core.data.review.BoundLearnerDailyReviewPlanPort
import com.tingyun.smartmistakebook.core.data.review.LearnerBoundDailyReviewPlanPort
import com.tingyun.smartmistakebook.core.data.review.ProductionDailyReviewPorts
import com.tingyun.smartmistakebook.core.data.review.RUNTIME_DAILY_REVIEW_CANDIDATE_LIMIT
import com.tingyun.smartmistakebook.core.data.session.LegacyRoomCaptureSessionPortFactory
import com.tingyun.smartmistakebook.core.data.session.LegacyBatchImportSessionAdapter
import com.tingyun.smartmistakebook.core.data.session.LegacyProblemOrganizationWorkSessionAdapter
import com.tingyun.smartmistakebook.core.data.session.ProblemOrganizationWorkSessionPort
import com.tingyun.smartmistakebook.core.data.session.CurrentTutorSessionProductionOwner
import com.tingyun.smartmistakebook.core.data.session.CurrentTutorSessionProductionOwnerFactory
import com.tingyun.smartmistakebook.core.data.session.CurrentTutorSessionHostCoordinatorFactory
import com.tingyun.smartmistakebook.core.data.session.CurrentTutorQuestionSource
import com.tingyun.smartmistakebook.core.data.session.CurrentTutorSavedMistakeQuestionRegistry
import com.tingyun.smartmistakebook.core.data.session.CurrentTutorPersistedPolicySource
import com.tingyun.smartmistakebook.core.data.session.CurrentTutorPersistedPolicySink
import com.tingyun.smartmistakebook.core.data.session.ProductionCurrentTutorPolicyOwner
import com.tingyun.smartmistakebook.core.data.session.ProductionCurrentTutorVerifiedMaterialAuthority
import com.tingyun.smartmistakebook.core.data.session.ProductionCurrentTutorVerifiedAnswerAuthority
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.TutorConversationLobbyCoordinator
import com.tingyun.smartmistakebook.core.data.tutor.LocalTutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.data.tutor.TutorKnowledgeEvidenceAuthorizer
import com.tingyun.smartmistakebook.core.data.tutor.TutorMasteryObservationSink
import com.tingyun.smartmistakebook.core.data.tutor.TrustedTutorChoiceInteractionRepositoryFactory
import com.tingyun.smartmistakebook.core.data.tutor.assembleTutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityCutoverJournalPort
import com.tingyun.smartmistakebook.core.database.LegacyAuthorityMigrationSourcePort
import com.tingyun.smartmistakebook.core.database.LegacySessionDatabaseOwner
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkStatus
import com.tingyun.smartmistakebook.core.database.CurrentTutorSessionHostWorkWriteDisposition
import com.tingyun.smartmistakebook.core.database.PersistCurrentTutorSessionPolicyCommand
import com.tingyun.smartmistakebook.core.database.TrustedCaptureSessionDatabaseCapability
import com.tingyun.smartmistakebook.core.database.TrustedTutorSessionDatabaseCapability
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.ConfiguredModelExecutionLease
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ModelTaskRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryEraseOutcome
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPrivacyRepository
import com.tingyun.smartmistakebook.core.domain.TutorConversationLobbyPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionHostPort
import com.tingyun.smartmistakebook.core.domain.TutorCurrentSessionPolicySnapshot
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import com.tingyun.smartmistakebook.core.knowledge.database.HighSchoolKnowledgeCatalog
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogSnapshotMetadata
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgeCatalogActivationReceipt
import com.tingyun.smartmistakebook.core.knowledge.database.KnowledgePackManifest
import com.tingyun.smartmistakebook.core.knowledge.database.ProductionKnowledgeActivationWitness
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRelayCapability
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryDisplayRevision
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRuntimeCapabilities
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryEraseResult
import com.tingyun.smartmistakebook.core.mastery.database.LocalMasteryContextReader
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.claimLearnerMasteryOpenResponseOwnerGrant
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryOwnerCapabilities
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryRelayCapability
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openMasteryOutboxAuthenticityVerifier
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryOwnerAccess.openLearnerMasteryOpenResponseWeakCandidateOwner
import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofIssuer
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofVerifier
import com.tingyun.smartmistakebook.core.student.mistake.database.LearnerBoundStudentMistakeLibraryPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeRuntimeCapabilities
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openMistakeDetailRepository
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openProductionDailyReviewPorts
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openProductionStudentCaptureOccurrenceOwner
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openReviewedStudentProblemOrganizationReviewPort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentMistakeMigrationOwner
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentMistakeOwnerCapabilities
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentOutboxAuthenticityVerifier
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openStudentProblemOrganizationSourcePort
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeOwnerAccess.openThreeAuthorityReviewPlanCoordinator
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongSupplier
import kotlin.jvm.JvmSynthetic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A successfully activated read-only catalog and its unforgeable activation proof.
 *
 * Bootstrapping remains separate because only reviewed content shipped with the application may
 * write the public knowledge database.
 */
internal data class ActivatedHighSchoolKnowledgeCatalog(
    val catalog: HighSchoolKnowledgeCatalog,
    val manifest: KnowledgePackManifest,
    val activation: KnowledgeCatalogActivationReceipt,
    val productionCutoverEligible: Boolean,
    val productionActivationWitness: ProductionKnowledgeActivationWitness?,
)

internal fun interface HighSchoolKnowledgeCatalogBootstrap {
    suspend fun openActivatedCatalog(
        context: Context,
        proofIssuer: KnowledgeReferenceProofIssuer,
    ): ActivatedHighSchoolKnowledgeCatalog
}

/**
 * Owns the three independent learning authorities for one local learner.
 *
 * The runtime deliberately exposes typed capabilities rather than a database, DAO, SQL
 * connection, or cross-database transaction. Each authority keeps its own file and lifecycle:
 *
 * - student mistakes: saved problem documents and review collection;
 * - learner mastery: immutable evidence and subject-scoped projections;
 * - high-school knowledge: reviewed, versioned, read-only public knowledge.
 */
class LocalLearningAuthorityRuntime private constructor(
    private val canonicalContext: Context,
    private val studentMistakes: StudentMistakeRuntimeCapabilities,
    private val learnerMastery: LearnerMasteryRuntimeCapabilities,
    private val highSchoolKnowledge: HighSchoolKnowledgeCatalog,
    private val knowledgeManifest: KnowledgePackManifest,
    private val knowledgeActivation: KnowledgeCatalogActivationReceipt,
    private val knowledgeActivationWitness: ProductionKnowledgeActivationWitness?,
    private val knowledgeCutoverEligible: Boolean,
    private val knowledgeReferenceProofVerifier: KnowledgeReferenceProofVerifier,
    private val authorityRelay: LocalLearningAuthorityRelay,
    private val tutorSessionCapability: TrustedTutorSessionDatabaseCapability,
) : Closeable {
    private val relayDrainMutex = Mutex()
    private val productionGenerationClaimed = AtomicBoolean(false)
    private val productionGenerationIdentity =
        AtomicReference<GenerationBoundLearningAuthorityRuntime?>(null)
    private val openResponseWeakCandidateOwnerClaimed = AtomicBoolean(false)
    private val productionBatchImport = RuntimeOwnedProductionBatchImport()
    private val closed = AtomicBoolean(false)

    private val currentTutorSessionOwner: CurrentTutorSessionProductionOwner =
        CurrentTutorSessionProductionOwnerFactory.open(
            context = canonicalContext,
            learnerId = learnerMastery.learnerId,
            database = tutorSessionCapability,
        )

    private val learningEvidence =
        ThreeAuthorityLearningEvidenceCoordinator.assemble(
            studentMistakes = studentMistakes,
            learnerMastery = learnerMastery,
            drainAuthorityRelay = ::triggerAuthorityRelay,
        )

    private val tutorLearningMemory =
        assembleTutorLearningMemoryRepository(
            boundLearnerId = learnerMastery.learnerId,
            conversationSession = tutorSessionCapability,
            evidenceSession = tutorSessionCapability,
            observationSink =
                TutorMasteryObservationSink { command ->
                    val result = learnerMastery.observationSink.record(command)
                    triggerAuthorityRelay()
                    result
                },
            currentSessionProofSource = currentTutorSessionOwner.learningEvidenceProofSource,
            knowledgeEvidenceAuthorizer =
                TutorKnowledgeEvidenceAuthorizer(
                    learnerMastery.knowledgeEvidenceAuthorizer::authorize,
                ),
            productionOwnerIsCurrent = currentTutorSessionOwner::isOpen,
        )

    private val tutorInteractions: TutorInteractionRepository =
        TrustedTutorChoiceInteractionRepositoryFactory.create(
            delegate = currentTutorSessionOwner.interactions,
            learningFinalizer =
                currentTutorSessionOwner.trustedChoiceLearningFinalizer(tutorLearningMemory),
        )

    private val tutorMasteryContext =
        LocalTutorMasteryContextRepository(
            mastery = learnerMastery.localContextReader,
            knowledge = highSchoolKnowledge,
        )

    private val learningMasteryDisplay =
        LocalLearningMasteryDisplayRepository(
            mastery = learnerMastery.displayReader,
            knowledge = highSchoolKnowledge,
            activation =
                LearningMasteryKnowledgeActivation(
                    knowledgePackVersion = knowledgeActivation.knowledgePackVersion,
                    taxonomyVersion = knowledgeActivation.taxonomyVersion,
                    manifestFingerprint = knowledgeActivation.manifestFingerprint,
                    generation = knowledgeActivation.generation,
                ),
        )

    private val problemKnowledgeContext =
        ReviewedProblemKnowledgeContextRepositoryFactory.create(highSchoolKnowledge)

    private val dailyReviewPlans =
        BoundLearnerDailyReviewPlanPort(
            learnerId = studentMistakes.learnerId,
            coordinator =
                openThreeAuthorityReviewPlanCoordinator(
                    studentMistakes,
                    learnerMastery.localContextReader,
                    RUNTIME_DAILY_REVIEW_CANDIDATE_LIMIT,
                ),
        )

    fun mistakeDetailRepository(): MistakeDetailRepository =
        openMistakeDetailRepository(studentMistakes)

    @JvmSynthetic
    internal fun studentMistakeLibraryPort(): LearnerBoundStudentMistakeLibraryPort =
        studentMistakes.library

    /**
     * Resolves only learner-facing names for references already stored by the student-mistake
     * authority. Missing or stale references remain unlabeled instead of leaking internal ids.
     */
    @JvmSynthetic
    internal suspend fun resolveStudentMistakeKnowledgeDisplayNames(
        refs: List<KnowledgeNodeRef>,
    ): Map<KnowledgeNodeRef, String> {
        if (refs.isEmpty()) return emptyMap()
        require(refs.size <= MAX_STUDENT_MISTAKE_CATALOG_KNOWLEDGE_REFS) {
            "Student mistake catalog knowledge lookup exceeds its bounded budget"
        }
        val result = linkedMapOf<KnowledgeNodeRef, String>()
        refs.distinct()
            .filter { ref ->
                ref.knowledgePackVersion == knowledgeManifest.knowledgePackVersion &&
                    ref.taxonomyVersion == knowledgeManifest.taxonomyVersion
            }
            .chunked(HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS)
            .forEach { batch ->
                val resolved =
                    try {
                        highSchoolKnowledge.findNodes(batch)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        return@forEach
                    }
                resolved.lookups.forEach { lookup ->
                    lookup.metadata?.let { metadata ->
                        result[lookup.requestedRef] = metadata.displayName
                    }
                }
            }
        return result
    }

    /**
     * Projection-only resolver fenced to one exact activated knowledge revision. Store failures
     * propagate so the discardable catalog retries instead of permanently caching missing labels.
     */
    private suspend fun resolveStudentMistakeKnowledgeDisplayNamesAtRevision(
        expectedRevision: StudentMistakeKnowledgeCatalogRevision,
        refs: List<KnowledgeNodeRef>,
    ): Map<KnowledgeNodeRef, String> {
        if (refs.isEmpty()) return emptyMap()
        require(refs.size <= MAX_STUDENT_MISTAKE_CATALOG_KNOWLEDGE_REFS) {
            "Student mistake catalog knowledge lookup exceeds its bounded budget"
        }
        check(expectedRevision == currentStudentMistakeKnowledgeRevision()) {
            "Student mistake catalog knowledge revision is no longer current"
        }
        val result = linkedMapOf<KnowledgeNodeRef, String>()
        refs.distinct()
            .filter { ref ->
                ref.knowledgePackVersion == expectedRevision.knowledgePackVersion &&
                    ref.taxonomyVersion == expectedRevision.taxonomyVersion
            }
            .chunked(HighSchoolKnowledgeCatalog.MAX_BATCH_NODE_REQUESTS)
            .forEach { batch ->
                val resolved = highSchoolKnowledge.findNodes(batch)
                check(
                    resolved.snapshot.activationGeneration == expectedRevision.activationGeneration &&
                        resolved.snapshot.manifestFingerprint == expectedRevision.manifestFingerprint &&
                        resolved.snapshot.taxonomyVersion == expectedRevision.taxonomyVersion &&
                        resolved.snapshot.knowledgePackVersion == expectedRevision.knowledgePackVersion &&
                        resolved.lookups.map { it.requestedRef } == batch,
                ) {
                    "Knowledge display lookup crossed its exact activated revision"
                }
                resolved.lookups.forEach { lookup ->
                    lookup.metadata?.let { metadata ->
                        result[lookup.requestedRef] = metadata.displayName
                    }
                }
            }
        return result
    }

    private fun currentStudentMistakeKnowledgeRevision():
        StudentMistakeKnowledgeCatalogRevision =
        highSchoolKnowledge.observeSnapshotRevision().value.toStudentMistakeKnowledgeRevision()

    @JvmSynthetic
    internal fun productionCaptureOccurrenceCommitPort():
        ProductionCaptureOccurrenceCommitPort =
        ProductionCaptureOccurrenceCommitPortFactory.create(
            openProductionStudentCaptureOccurrenceOwner(studentMistakes),
        )

    /**
     * Joins temporary capture-session state to the student-owned commit path without exposing the
     * learner id, either owner, or the legacy session capability to the publication assembler.
     */
    @JvmSynthetic
    internal fun productionCaptureWorkflowRepository(
        captureSessions: TrustedCaptureSessionDatabaseCapability,
    ): CaptureWorkflowRepository {
        val sessionPorts =
            LegacyRoomCaptureSessionPortFactory.create(
                context = canonicalContext,
                database = captureSessions,
                learnerId = studentMistakes.learnerId,
            )
        return ProductionCaptureWorkflowRepositoryFactory.create(
            session = sessionPorts.session,
            commitPreparation = sessionPorts.commitPreparation,
            commitPortProvider = ::productionCaptureOccurrenceCommitPort,
        )
    }

    @JvmSynthetic
    internal fun productionBatchImportRepository(
        generationIdentity: GenerationBoundLearningAuthorityRuntime,
        sessionOwner: LegacySessionDatabaseOwner,
        modelTaskOwner: ProductionModelTaskQueueOwner,
    ): BatchImportRepository {
        check(!closed.get()) { "Learning authority runtime is closed" }
        check(studentMistakes.learnerId == learnerMastery.learnerId) {
            "Batch-import authority learner bindings do not match"
        }
        val scope = SessionScope(studentMistakes.learnerId)
        val capturePorts =
            LegacyRoomCaptureSessionPortFactory.create(
                context = canonicalContext,
                database = sessionOwner.captureSessions(),
                learnerId = studentMistakes.learnerId,
            )
        val constructionClaim =
            ProductionBatchImportConstructionRegistry.issue(
                canonicalContext,
                scope,
                LegacyBatchImportSessionAdapter(scope, sessionOwner.batchSessions()),
                capturePorts.session,
                modelTaskOwner.modelTaskQueue,
                ProductionBatchImportAuthorityBindings.of(
                    generationIdentity,
                    canonicalContext,
                    studentMistakes,
                    sessionOwner,
                    modelTaskOwner,
                ),
            )
        var finalClaim: CurrentGenerationBatchImportClaim? = null
        try {
            val issuedClaim = ProductionBatchImportRepositoryFactory.issue(constructionClaim)
            finalClaim = issuedClaim
            val repository = productionBatchImport.attach(issuedClaim)
            finalClaim = null
            return repository
        } finally {
            finalClaim?.close()
            constructionClaim.close()
        }
    }

    @JvmSynthetic
    internal fun productionMistakeCapabilities(
        generationIdentity: GenerationBoundLearningAuthorityRuntime,
        sessionOwner: LegacySessionDatabaseOwner,
        modelTaskOwner: ProductionModelTaskQueueOwner,
    ): CurrentGenerationMistakeCapabilityOwner {
        check(isCurrentProductionGeneration(generationIdentity)) {
            "Mistake capabilities require the exact current production generation"
        }
        check(studentMistakes.learnerId == learnerMastery.learnerId) {
            "Mistake capability authorities do not share one learner"
        }
        val scope = SessionScope(studentMistakes.learnerId)
        val organizationWork = sessionOwner.organizationWork()
        val workSessions =
            LegacyProblemOrganizationWorkSessionAdapter(scope, organizationWork)
        val organizationOwner =
            ProductionStudentProblemOrganizationOwnerFactory.create(
                learnerId = scope.learnerId,
                studentSources = openStudentProblemOrganizationSourcePort(studentMistakes),
                studentOrganizations = studentMistakes.problemOrganization,
                reviewPort = openReviewedStudentProblemOrganizationReviewPort(studentMistakes),
                organizationWork = organizationWork,
                modelTasksAndAssetDocuments = sessionOwner.modelTasksAndAssetDocuments(),
                knowledgeContext = problemKnowledgeContext,
            )
        val organization =
            StudentAuthoritativeMistakeOrganizationRepositoryFactory.createProduction(
                studentOrganizationOwner = organizationOwner,
                organizationWorkSessions = workSessions,
            )
        val catalogJob = SupervisorJob()
        val catalogScope = CoroutineScope(catalogJob + Dispatchers.IO)
        val learnerBoundMasteryDisplay = learnerMastery.displayReader
        val masteryDisplayRevisions =
            learnerBoundMasteryDisplay.observeRevision().stateIn(
                scope = catalogScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    LearnerMasteryDisplayRevision(
                        ledgerSequence = 0L,
                        asOfEpochMillis = 0L,
                    ),
            )
        val authorityKnowledgeRevisions = highSchoolKnowledge.observeSnapshotRevision()
        val knowledgeRevisions =
            authorityKnowledgeRevisions.map { revision ->
                revision.toStudentMistakeKnowledgeRevision()
            }.stateIn(
                scope = catalogScope,
                started = SharingStarted.Eagerly,
                initialValue =
                    authorityKnowledgeRevisions.value.toStudentMistakeKnowledgeRevision(),
            )
        val catalog =
            try {
                StudentMistakeLibraryCatalogRepositoryFactory.createProjected(
                    context = canonicalContext,
                    expectedLearnerId = scope.learnerId,
                    applicationScope = catalogScope,
                    libraryProvider = {
                        check(isCurrentProductionGeneration(generationIdentity)) {
                            "Student mistake catalog generation is no longer current"
                        }
                        studentMistakes.library
                    },
                    masteryDisplayRevisions = masteryDisplayRevisions,
                    masteryRead =
                        LearnerBoundStudentMistakeMasteryRead { request ->
                            check(isCurrentProductionGeneration(generationIdentity)) {
                                "Student mistake catalog generation is no longer current"
                            }
                            learnerBoundMasteryDisplay.readKnowledgePage(request)
                        },
                    knowledgeRevisions = knowledgeRevisions,
                    knowledgeDisplayResolver = { expectedRevision, refs ->
                        check(isCurrentProductionGeneration(generationIdentity)) {
                            "Student mistake catalog generation is no longer current"
                        }
                        resolveStudentMistakeKnowledgeDisplayNamesAtRevision(
                            expectedRevision = expectedRevision,
                            refs = refs,
                        )
                    },
                )
            } catch (failure: Throwable) {
                catalogJob.cancel()
                throw failure
            }
        return try {
            CurrentGenerationMistakeCapabilityOwner(
                mistakeOrganization = organization,
                studentMistakeCatalog = catalog,
                workScope = scope,
                workSessions = workSessions,
                modelTasks = modelTaskOwner.modelTaskQueue,
                trustedAnswerRules =
                    ProblemOrganizationTrustedAnswerRuleCompletionPort { learnerId, _ ->
                        check(learnerId == scope.learnerId) {
                            "Organization completion crossed learner scope"
                        }
                        triggerAuthorityRelay()
                    },
                catalogJob = catalogJob,
                productionGenerationIsCurrent = {
                    isCurrentProductionGeneration(generationIdentity)
                },
            )
        } catch (failure: Throwable) {
            catalog.closeWithSuppressed(failure)
            catalogJob.cancel()
            throw failure
        }
    }

    @JvmSynthetic
    internal fun closeProductionBatchImportBeforeSharedOwners() {
        productionBatchImport.close()
    }

    @JvmSynthetic
    internal fun closeCurrentTutorSessionBeforeSharedOwners() {
        productionGenerationIdentity.set(null)
        currentTutorSessionOwner.close()
    }

    fun dailyReviewPlanPort(): LearnerBoundDailyReviewPlanPort =
        dailyReviewPlans

    fun learningEvidencePort(): LearnerBoundLearningEvidencePort =
        learningEvidence

    @JvmSynthetic
    internal fun productionDailyReviewPorts(
        nowEpochMillis: () -> Long = System::currentTimeMillis,
    ): ProductionDailyReviewPorts =
        openProductionDailyReviewPorts(
            studentMistakes,
            dailyReviewPlans,
            learnerMastery.localContextReader,
            highSchoolKnowledge,
            nowEpochMillis,
            suspend { triggerAuthorityRelay() },
        )

    /**
     * Claims the learner-mastery runtime's one open-response writer and immediately narrows it to
     * core:data's proposal contract. Neither the generation grant nor the database owner escapes
     * this authority runtime.
     */
    @JvmSynthetic
    internal fun claimOpenResponseWeakCandidateOwner():
        LearnerBoundOpenResponseWeakCandidateOwner {
        check(openResponseWeakCandidateOwnerClaimed.compareAndSet(false, true)) {
            "Open-response weak-candidate owner was already claimed for this runtime"
        }
        val ownerGrant =
            claimLearnerMasteryOpenResponseOwnerGrant(learnerMastery)
        val owner =
            openLearnerMasteryOpenResponseWeakCandidateOwner(
                canonicalContext,
                learnerMastery.learnerId,
                ownerGrant,
            )
        return try {
            LearnerMasteryOpenResponseWeakCandidateOwnerAdapter(owner)
        } catch (failure: Throwable) {
            owner.closeWithSuppressed(failure)
            throw failure
        }
    }

    @JvmSynthetic
    internal fun claimTutorOpenResponseLearningHost(
        modelTasks: ModelTaskRepository,
    ): CoreDataTutorOpenResponseLearningAssemblyResult =
        CoreDataTutorOpenResponseLearningHostAssembly.assemble(
            evidence = learningEvidence,
            modelTasks = modelTasks,
            knowledgeReferenceVerifier = knowledgeReferenceProofVerifier,
            candidateOwner = claimOpenResponseWeakCandidateOwner(),
            contextOwner = currentTutorSessionOwner.openResponseContextOwner,
            nowEpochMillis = LongSupplier(System::currentTimeMillis),
        )

    /**
     * Exposes only the bounded, qualitative tutor read contract. The implementation resolves exact
     * same-subject refs across the two authorities in memory and never exposes either store.
     */
    fun tutorMasteryContextRepository(): TutorMasteryContextRepository =
        tutorMasteryContext

    fun learningMasteryDisplayRepository(): LearningMasteryDisplayRepository =
        learningMasteryDisplay

    fun learningMasteryPrivacyRepository(): LearningMasteryPrivacyRepository =
        LearningMasteryPrivacyRepository {
            learnerMastery.eraseCapability.eraseAll().toDomain()
        }

    fun tutorTeachingReferenceRepository(): TutorTeachingReferenceRepository =
        TutorTeachingReferenceRepositoryFactory.create(highSchoolKnowledge)

    fun problemKnowledgeContextRepository(): ReviewedProblemKnowledgeContextRepository =
        problemKnowledgeContext

    /**
     * Mirrors only the authority data that can be reproduced exactly and reports every remaining
     * cutover blocker. It never marks student/mastery import stages complete.
     */
    suspend fun prepareLegacyAuthorityMigration(
        context: Context,
        legacySource: LegacyAuthorityMigrationSourcePort,
        journal: LegacyAuthorityCutoverJournalPort,
    ): LegacyAuthorityPreparationResult =
        try {
            openStudentMistakeMigrationOwner(context.applicationContext).use { migration ->
                val layout = ThreeAuthorityDatabaseLayout()
                val prefix =
                    ProductionAuthorityCutoverPrefixFactory.create(
                        layout = layout,
                        legacySource = legacySource,
                        knowledgeCatalog = highSchoolKnowledge,
                        knowledgeActivation = knowledgeActivation,
                        knowledgeCutoverEligible = knowledgeCutoverEligible,
                        journal = LegacyAuthorityCutoverJournalAdapter(journal),
                    )
                LegacyAuthorityPreparationCoordinator(
                    learnerId = learnerMastery.learnerId,
                    verifiedPrefix = prefix,
                    legacySource = legacySource,
                    studentMigration = migration,
                    studentRelayDrainer =
                        LegacyStudentRelayDrainer { nowEpochMillis, batchSize ->
                            authorityRelay.drain(
                                nowEpochMillis = nowEpochMillis,
                                batchSize = batchSize,
                            )
                        },
                    assetUriResolver =
                        productionLegacyStudentAssetUriResolver(context.applicationContext),
                ).prepare()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LegacyAuthorityPreparationResult.failed()
        }

    /**
     * Returns the legacy conversation repository decorated with the private mastery authority.
     * Callers, including model-provider adapters, receive no authority, store, or relay capability.
     */
    fun tutorLearningMemoryRepository(): TutorLearningMemoryRepository =
        tutorLearningMemory

    @JvmSynthetic
    internal fun tutorInteractionRepository(): TutorInteractionRepository =
        tutorInteractions

    @JvmSynthetic
    internal fun tutorCurrentSessionHost(
        captureWorkflow: CaptureWorkflowRepository,
        mistakeDetails: MistakeDetailRepository,
        modelTasks: ModelTaskRepository,
        openResponseLearningHost: CoreDataTutorOpenResponseLearningHostPort,
    ): TutorCurrentSessionHostPort {
        val savedMistakes = CurrentTutorSavedMistakeQuestionRegistry(mistakeDetails)
        val questions = CurrentTutorQuestionSource { sessionId ->
            captureWorkflow.readTutorSession(sessionId) ?: savedMistakes.read(sessionId)
        }
        val policyOwner = ProductionCurrentTutorPolicyOwner(
            questions = questions,
            persistedPolicies = CurrentTutorPersistedPolicySource { sessionId ->
                tutorSessionCapability.readCurrentTutorSessionPolicy(
                    learnerId = learnerMastery.learnerId,
                    sessionId = sessionId,
                )?.let { policy ->
                    TutorCurrentSessionPolicySnapshot(
                        sessionId = policy.sessionId,
                        explanationMode = policy.explanationMode,
                        modeVersion = policy.modeVersion,
                        learningWritesAllowed = policy.learningWritesAllowed,
                        learningWritePermissionVersion = policy.learningWritePermissionVersion,
                        visualIntent = policy.visualIntent,
                        visualIntentVersion = policy.visualIntentVersion,
                    )
                } ?: tutorSessionCapability.readCurrentTutorSessionHostWork(
                    learnerId = learnerMastery.learnerId,
                    sessionId = sessionId,
                )
                    ?.takeIf { work ->
                        work.status != CurrentTutorSessionHostWorkStatus.REVOKED
                    }
                    ?.let { work ->
                        TutorCurrentSessionPolicySnapshot(
                            sessionId = work.sessionId,
                            explanationMode = work.explanationMode,
                            modeVersion = work.modeVersion,
                            learningWritesAllowed = work.learningWritesAllowed,
                            learningWritePermissionVersion = work.learningWritePermissionVersion,
                            visualIntent = work.visualIntent,
                            visualIntentVersion = work.visualIntentVersion,
                        )
                    }
            },
            persistPolicy = CurrentTutorPersistedPolicySink { snapshot ->
                val result = tutorSessionCapability.persistCurrentTutorSessionPolicy(
                    PersistCurrentTutorSessionPolicyCommand(
                        learnerId = learnerMastery.learnerId,
                        sessionId = snapshot.sessionId,
                        explanationMode = snapshot.explanationMode,
                        modeVersion = snapshot.modeVersion,
                        learningWritesAllowed = snapshot.learningWritesAllowed,
                        learningWritePermissionVersion =
                            snapshot.learningWritePermissionVersion,
                        visualIntent = snapshot.visualIntent,
                        visualIntentVersion = snapshot.visualIntentVersion,
                        occurredAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
                result.disposition == CurrentTutorSessionHostWorkWriteDisposition.APPLIED ||
                    result.disposition == CurrentTutorSessionHostWorkWriteDisposition.DUPLICATE
            },
        )
        return CurrentTutorSessionHostCoordinatorFactory.createProduction(
            learnerId = learnerMastery.learnerId,
            questions = questions,
            savedMistakePreparer = savedMistakes,
            modelTasks = modelTasks,
            learningMemory = tutorLearningMemory,
            hostWork = tutorSessionCapability,
            policyOwner = policyOwner,
            verifiedMaterialAuthority =
                ProductionCurrentTutorVerifiedMaterialAuthority(
                    problemKnowledgeContext,
                    ProductionCurrentTutorVerifiedAnswerAuthority(
                        studentMistakes.trustedSavedAnswerRules,
                        currentTutorSessionOwner.trustedSavedAnswerCommitter(
                            tutorLearningMemory,
                        ),
                    ),
                ),
            owner = currentTutorSessionOwner,
            interactionWriter = tutorInteractions,
            freeResponseSubmission =
                CurrentTutorFreeResponseSubmissionAdapter(openResponseLearningHost),
        )
    }

    @JvmSynthetic
    internal fun tutorConversationLobby(): TutorConversationLobbyPort =
        TutorConversationLobbyCoordinator(
            learnerId = learnerMastery.learnerId,
            learningMemory = tutorLearningMemory,
        )

    @JvmSynthetic
    internal fun bindCurrentTutorSessionToProductionGeneration(
        context: Context,
        generationIdentity: GenerationBoundLearningAuthorityRuntime,
    ) {
        check(productionGenerationIdentity.compareAndSet(null, generationIdentity)) {
            "Learning authority runtime is already bound to a production generation"
        }
        val bound =
            currentTutorSessionOwner.bindToProductionGeneration(
                context = context,
                generationIdentity = generationIdentity,
            )
        if (!bound) {
            productionGenerationIdentity.compareAndSet(generationIdentity, null)
        }
        check(bound) {
            "Current tutor session owner belongs to another context or generation"
        }
    }

    private fun isCurrentProductionGeneration(
        generationIdentity: GenerationBoundLearningAuthorityRuntime,
    ): Boolean =
        !closed.get() &&
            productionGenerationIdentity.get() === generationIdentity &&
            currentTutorSessionOwner.isOpen()

    internal fun localMasteryContextReader(): LocalMasteryContextReader =
        learnerMastery.localContextReader

    @JvmSynthetic
    internal fun belongsToCanonicalContext(context: Context): Boolean =
        canonicalContext === context.canonicalApplicationContext()

    @JvmSynthetic
    internal fun belongsToLearner(learnerId: String): Boolean =
        studentMistakes.learnerId == learnerId && learnerMastery.learnerId == learnerId

    @JvmSynthetic
    internal fun hasCurrentKnowledgeWitness(witness: KnowledgeActivationWitness): Boolean {
        val active = knowledgeActivationWitness ?: return false
        return knowledgeCutoverEligible &&
            witness.hasValidFingerprint() &&
            active.activationGeneration == witness.activationGeneration &&
            active.activatedAtEpochMillis == witness.activatedAtEpochMillis &&
            active.packId == witness.packId &&
            active.knowledgePackVersion == witness.knowledgePackVersion &&
            active.taxonomyVersion == witness.taxonomyVersion &&
            active.manifestFingerprint == witness.manifestFingerprint
    }

    @JvmSynthetic
    internal fun claimForProductionGeneration(): Boolean =
        productionGenerationClaimed.compareAndSet(false, true)

    /**
     * Explicit relay trigger for an authority-owned write facade.
     *
     * The current tutor observation sink invokes it after every durable mastery write. A future
     * student-authoritative write facade must do the same after committing its outbox event.
     */
    internal suspend fun triggerAuthorityRelay() =
        relayDrainMutex.withLock {
            var rounds = 0
            var morePending: Boolean
            do {
                check(rounds++ < MAX_RELAY_DRAIN_ROUNDS) {
                    "Authority relay did not become idle within the bounded recovery budget"
                }
                val result =
                    authorityRelay.drain(
                        nowEpochMillis = System.currentTimeMillis(),
                        batchSize = LearnerMasteryRelayCapability.MAX_RELAY_BATCH_SIZE,
                    )
                morePending =
                    result.inboundApplied + result.inboundDuplicates ==
                        LearnerMasteryRelayCapability.MAX_RELAY_BATCH_SIZE ||
                        result.outboundApplied + result.outboundDuplicates ==
                        LearnerMasteryRelayCapability.MAX_RELAY_BATCH_SIZE
            } while (morePending)
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        productionGenerationIdentity.set(null)
        var failure: Throwable? = null
        listOf<AutoCloseable>(
            productionBatchImport,
            currentTutorSessionOwner,
            learnerMastery,
            studentMistakes,
            highSchoolKnowledge,
        ).forEach { authority ->
            try {
                authority.close()
            } catch (closeFailure: Throwable) {
                val firstFailure = failure
                if (firstFailure == null) {
                    failure = closeFailure
                } else {
                    firstFailure.addSuppressed(closeFailure)
                }
            }
        }
        failure?.let { throw it }
    }

    companion object {
        private const val MAX_STUDENT_MISTAKE_CATALOG_KNOWLEDGE_REFS = 20_000

        /**
         * Production opener for the single canonical local learner. The caller cannot provide a
         * learner id or splice in an independently opened runtime.
         */
        @JvmSynthetic
        internal suspend fun openCurrentLocalLearnerForProduction(
            context: Context,
            tutorSessionCapability: TrustedTutorSessionDatabaseCapability,
        ): CurrentLocalLearnerRuntimeClaim {
            val canonicalContext = context.canonicalApplicationContext()
            val runtime =
                open(
                    context = canonicalContext,
                    learnerId = LOCAL_LEARNER_ID,
                    tutorSessionCapability = tutorSessionCapability,
                )
            return try {
                check(runtime.belongsToCanonicalContext(canonicalContext)) {
                    "Authority runtime belongs to another application context"
                }
                check(runtime.belongsToLearner(LOCAL_LEARNER_ID)) {
                    "Authority runtime does not belong to the canonical local learner"
                }
                CurrentLocalLearnerRuntimeClaim.Owner.issue(
                    runtime,
                    canonicalContext,
                )
            } catch (failure: Throwable) {
                runtime.closeWithSuppressed(failure)
                throw failure
            }
        }

        suspend fun open(
            context: Context,
            learnerId: String,
            tutorSessionCapability: TrustedTutorSessionDatabaseCapability,
        ): LocalLearningAuthorityRuntime =
            open(
                context = context,
                learnerId = learnerId,
                tutorSessionCapability = tutorSessionCapability,
                knowledgeBootstrap = ProductionHighSchoolKnowledgeCatalogBootstrap,
            )

        @JvmSynthetic
        internal suspend fun open(
            context: Context,
            learnerId: String,
            tutorSessionCapability: TrustedTutorSessionDatabaseCapability,
            knowledgeBootstrap: HighSchoolKnowledgeCatalogBootstrap,
        ): LocalLearningAuthorityRuntime {
            require(learnerId.isNotBlank()) { "Learner id must not be blank" }
            val applicationContext = context.canonicalApplicationContext()
            val knowledgeProofAuthority = KnowledgeReferenceProofAuthority.create()
            val knowledge =
                knowledgeBootstrap.openActivatedCatalog(
                    context = applicationContext,
                    proofIssuer = knowledgeProofAuthority.issuer,
                )
            try {
                val manifest = knowledge.catalog.readManifest()
                require(
                    manifest == knowledge.manifest &&
                        manifest.packId == knowledge.activation.packId &&
                        manifest.knowledgePackVersion ==
                        knowledge.activation.knowledgePackVersion &&
                        manifest.taxonomyVersion == knowledge.activation.taxonomyVersion &&
                        manifest.contentFingerprint ==
                        knowledge.activation.manifestFingerprint,
                ) {
                    "Knowledge catalog and activation proof do not identify the same pack"
                }
                val witness = knowledge.productionActivationWitness
                if (knowledge.productionCutoverEligible) {
                    requireNotNull(witness) {
                        "Production knowledge catalog requires a fresh activation witness"
                    }
                    require(
                        witness.activationGeneration == knowledge.activation.generation &&
                            witness.activatedAtEpochMillis ==
                            knowledge.activation.activatedAtEpochMillis &&
                            witness.packId == knowledge.activation.packId &&
                            witness.knowledgePackVersion ==
                            knowledge.activation.knowledgePackVersion &&
                            witness.taxonomyVersion == knowledge.activation.taxonomyVersion &&
                            witness.manifestFingerprint ==
                            knowledge.activation.manifestFingerprint,
                    ) {
                        "Knowledge catalog activation witness is stale"
                    }
                } else {
                    require(witness == null) {
                        "Non-production knowledge catalog cannot carry a production witness"
                    }
                }
            } catch (failure: Throwable) {
                knowledge.catalog.closeWithSuppressed(failure)
                throw failure
            }
            val mistakes =
                try {
                    openStudentMistakeOwnerCapabilities(
                        applicationContext,
                        learnerId,
                        knowledgeProofAuthority.verifier,
                    )
                } catch (failure: Throwable) {
                    knowledge.catalog.closeWithSuppressed(failure)
                    throw failure
                }
            val mastery =
                try {
                    openLearnerMasteryOwnerCapabilities(
                        applicationContext,
                        learnerId,
                        knowledgeProofAuthority.verifier,
                        SharedProjectionWorkloadGate,
                    )
                } catch (failure: Throwable) {
                    mistakes.closeWithSuppressed(failure)
                    knowledge.catalog.closeWithSuppressed(failure)
                    throw failure
                }
            val runtime =
                LocalLearningAuthorityRuntime(
                    canonicalContext = applicationContext,
                    studentMistakes = mistakes,
                    learnerMastery = mastery,
                    highSchoolKnowledge = knowledge.catalog,
                    knowledgeManifest = knowledge.manifest,
                    knowledgeActivation = knowledge.activation,
                    knowledgeActivationWitness = knowledge.productionActivationWitness,
                    knowledgeCutoverEligible = knowledge.productionCutoverEligible,
                    knowledgeReferenceProofVerifier = knowledgeProofAuthority.verifier,
                    authorityRelay =
                        LocalLearningAuthorityRelay(
                            learnerId = learnerId,
                            studentMistakes = mistakes.relay,
                            learnerMastery = openLearnerMasteryRelayCapability(mastery),
                            studentOutboxAuthenticityVerifier =
                                openStudentOutboxAuthenticityVerifier(mistakes),
                            masteryOutboxAuthenticityVerifier =
                                openMasteryOutboxAuthenticityVerifier(mastery),
                        ),
                    tutorSessionCapability = tutorSessionCapability,
                )
            try {
                runtime.triggerAuthorityRelay()
            } catch (failure: Throwable) {
                runtime.closeWithSuppressed(failure)
                throw failure
            }
            return runtime
        }

        private const val MAX_RELAY_DRAIN_ROUNDS = 1_024
    }
}

private fun Context.canonicalApplicationContext(): Context =
    applicationContext ?: this

private fun KnowledgeCatalogSnapshotMetadata.toStudentMistakeKnowledgeRevision():
    StudentMistakeKnowledgeCatalogRevision =
    StudentMistakeKnowledgeCatalogRevision(
        activationGeneration = activationGeneration,
        manifestFingerprint = manifestFingerprint,
        taxonomyVersion = taxonomyVersion,
        knowledgePackVersion = knowledgePackVersion,
    )

private fun AutoCloseable.closeWithSuppressed(owner: Throwable) {
    try {
        close()
    } catch (closeFailure: Throwable) {
        owner.addSuppressed(closeFailure)
    }
}

internal class CurrentGenerationMistakeCapabilityOwner(
    val mistakeOrganization: MistakeOrganizationRepository,
    val studentMistakeCatalog: StudentMistakeLibraryCatalogRepository,
    workScope: SessionScope,
    workSessions: ProblemOrganizationWorkSessionPort,
    modelTasks: ModelTaskRepository,
    trustedAnswerRules: ProblemOrganizationTrustedAnswerRuleCompletionPort =
        ProblemOrganizationTrustedAnswerRuleCompletionPort { _, _ -> },
    private val catalogJob: Job,
    private val productionGenerationIsCurrent: () -> Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val organizationCommitLinearizer =
        ProductionProblemOrganizationCommitLinearizer {
            productionGenerationIsCurrent()
        }
    private val currentExecution: () -> Boolean = organizationCommitLinearizer::isCurrent
    private val processor =
        ProblemOrganizationWorkProcessor(
            scope = workScope,
            sessions = workSessions,
            modelTasks = modelTasks,
            organizations = mistakeOrganization,
            trustedAnswerRules = trustedAnswerRules,
            executionIsCurrent = currentExecution,
            organizationCommitFence = organizationCommitLinearizer,
        )
    private val executionLeaseAuthority =
        ProductionProblemOrganizationExecutionLeaseAuthority()
    private val executionResolver =
        issueProductionProblemOrganizationExecutionResolver(
            processor = processor,
            leaseAuthority = executionLeaseAuthority,
            productionGenerationIsCurrent = currentExecution,
        )
    private val scheduling =
        ProductionProblemOrganizationScheduling.issue(
            scope = workScope,
            sessions = workSessions,
            leaseAuthority = executionLeaseAuthority,
            productionGenerationIsCurrent = currentExecution,
        )

    val workManagerCoordination: ProductionWorkManagerCoordination =
        productionWorkManagerCoordination(
            executionResolverProvider = {
                check(currentExecution()) {
                    "Problem organization execution resolver generation is no longer current"
                }
                executionResolver
            },
            schedulingProvider = {
                check(currentExecution()) {
                    "Problem organization scheduling generation is no longer current"
                }
                scheduling
            },
        )

    init {
        check(productionGenerationIsCurrent()) {
            "Mistake capability owner requires a current production generation"
        }
    }

    override fun close() {
        // Every concurrent revoker must cross the same gate. A later close may return before the
        // first close finishes unrelated resource cleanup, but never before an earlier commit.
        organizationCommitLinearizer.close()
        if (!closed.compareAndSet(false, true)) return
        try {
            executionLeaseAuthority.close()
        } finally {
            try {
                studentMistakeCatalog.close()
            } finally {
                catalogJob.cancel()
            }
        }
    }
}

internal fun assembleCurrentGenerationProductionOwnerPorts(
    context: Context,
    generationBinding: GenerationBoundLearningAuthorityRuntime,
    sessionOwnerResource: Any,
    modelExecutionLease: ConfiguredModelExecutionLease,
): CurrentGenerationProductionOwnerPorts {
    val sessionOwner =
        sessionOwnerResource as? LegacySessionDatabaseOwner
            ?: run {
                modelExecutionLease.close()
                throw IllegalArgumentException("Unsupported legacy session owner resource")
            }
    val canonicalContext = context.applicationContext ?: context
    val runtime =
        try {
            generationBinding.claimForCapabilityAssembly()
        } catch (failure: Throwable) {
            modelExecutionLease.closeWithSuppressed(failure)
            sessionOwner.closeWithSuppressed(failure)
            throw failure
        }
    try {
        runtime.bindCurrentTutorSessionToProductionGeneration(
            context = canonicalContext,
            generationIdentity = generationBinding,
        )
    } catch (failure: Throwable) {
        modelExecutionLease.closeWithSuppressed(failure)
        sessionOwner.closeWithSuppressed(failure)
        runtime.closeWithSuppressed(failure)
        throw failure
    }
    val modelTaskOwner =
        try {
            openProductionModelTaskQueueOwner(
                context = canonicalContext,
                sessionOwner = sessionOwner,
                executionLease = modelExecutionLease,
            )
        } catch (failure: Throwable) {
            sessionOwner.closeWithSuppressed(failure)
            runtime.closeWithSuppressed(failure)
            throw failure
        }
    val batchImport =
        try {
            runtime.productionBatchImportRepository(
                generationIdentity = generationBinding,
                sessionOwner = sessionOwner,
                modelTaskOwner = modelTaskOwner,
            )
        } catch (failure: Throwable) {
            runtime.closeProductionBatchImportBeforeSharedOwners()
            runtime.closeCurrentTutorSessionBeforeSharedOwners()
            modelTaskOwner.closeWithSuppressed(failure)
            sessionOwner.closeWithSuppressed(failure)
            runtime.closeWithSuppressed(failure)
            throw failure
        }
    val mistakeCapabilities =
        try {
            runtime.productionMistakeCapabilities(
                generationIdentity = generationBinding,
                sessionOwner = sessionOwner,
                modelTaskOwner = modelTaskOwner,
            )
        } catch (failure: Throwable) {
            runtime.closeProductionBatchImportBeforeSharedOwners()
            runtime.closeCurrentTutorSessionBeforeSharedOwners()
            modelTaskOwner.closeWithSuppressed(failure)
            sessionOwner.closeWithSuppressed(failure)
            runtime.closeWithSuppressed(failure)
            throw failure
        }
    val captureWorkflow = runtime.productionCaptureWorkflowRepository(sessionOwner.captureSessions())
    val mistakeDetails = runtime.mistakeDetailRepository()
    val tutorOpenResponseLearningHost =
        try {
            val assembled = runtime.claimTutorOpenResponseLearningHost(modelTaskOwner.modelTaskQueue)
            check(assembled is CoreDataTutorOpenResponseLearningAssemblyResult.Available) {
                "Current Tutor open-response learning host is unavailable"
            }
            assembled.host
        } catch (failure: Throwable) {
            mistakeCapabilities.closeWithSuppressed(failure)
            runtime.closeProductionBatchImportBeforeSharedOwners()
            runtime.closeCurrentTutorSessionBeforeSharedOwners()
            modelTaskOwner.closeWithSuppressed(failure)
            sessionOwner.closeWithSuppressed(failure)
            runtime.closeWithSuppressed(failure)
            throw failure
        }
    val tutorCurrentSessionHost = runtime.tutorCurrentSessionHost(
        captureWorkflow = captureWorkflow,
        mistakeDetails = mistakeDetails,
        modelTasks = modelTaskOwner.modelTaskQueue,
        openResponseLearningHost = tutorOpenResponseLearningHost,
    )
    val tutorConversationLobby = runtime.tutorConversationLobby()
    return try {
        CurrentGenerationProductionOwnerPorts.issue(
            CurrentGenerationAuthorityResources(
                runtime = runtime,
                sessionOwner = sessionOwner,
                modelTaskOwner = modelTaskOwner,
                mistakeCapabilities = mistakeCapabilities,
                tutorOpenResponseLearningHost = tutorOpenResponseLearningHost,
            ),
            generationBinding,
            batchImport,
            captureWorkflow,
            mistakeDetails,
            mistakeCapabilities.mistakeOrganization,
            mistakeCapabilities.studentMistakeCatalog,
            tutorCurrentSessionHost,
            tutorConversationLobby,
            runtime.tutorMasteryContextRepository(),
            runtime.learningMasteryDisplayRepository(),
            runtime.learningMasteryPrivacyRepository(),
            runtime.tutorTeachingReferenceRepository(),
            modelTaskOwner.modelTaskQueue,
            modelTaskOwner.modelAssetDocuments,
            runtime.productionDailyReviewPorts(),
            mistakeCapabilities.workManagerCoordination,
        )
    } catch (failure: Throwable) {
        tutorOpenResponseLearningHost.closeWithSuppressed(failure)
        mistakeCapabilities.closeWithSuppressed(failure)
        runtime.closeProductionBatchImportBeforeSharedOwners()
        runtime.closeCurrentTutorSessionBeforeSharedOwners()
        modelTaskOwner.closeWithSuppressed(failure)
        sessionOwner.closeWithSuppressed(failure)
        runtime.closeWithSuppressed(failure)
        throw failure
    }
}

private fun LearnerMasteryEraseResult.toDomain(): LearningMasteryEraseOutcome =
    LearningMasteryEraseOutcome(erasedAtEpochMillis = erasedAtEpochMillis)

private class CurrentGenerationAuthorityResources(
    private val runtime: LocalLearningAuthorityRuntime,
    private val sessionOwner: LegacySessionDatabaseOwner,
    private val modelTaskOwner: ProductionModelTaskQueueOwner,
    private val mistakeCapabilities: CurrentGenerationMistakeCapabilityOwner,
    private val tutorOpenResponseLearningHost:
        com.tingyun.smartmistakebook.core.data.openresponse
            .CoreDataTutorOpenResponseLearningHostAdapter,
) : AutoCloseable {
    override fun close() {
        var firstFailure: Throwable? = null
        listOf<AutoCloseable>(
            tutorOpenResponseLearningHost,
            mistakeCapabilities,
            AutoCloseable { runtime.closeProductionBatchImportBeforeSharedOwners() },
            AutoCloseable { runtime.closeCurrentTutorSessionBeforeSharedOwners() },
            modelTaskOwner,
            sessionOwner,
            runtime,
        ).forEach { resource ->
            try {
                resource.close()
            } catch (failure: Throwable) {
                val first = firstFailure
                if (first == null) {
                    firstFailure = failure
                } else {
                    first.addSuppressed(failure)
                }
            }
        }
        firstFailure?.let { failure -> throw failure }
    }
}

private fun openProductionModelTaskQueueOwner(
    context: Context,
    sessionOwner: LegacySessionDatabaseOwner,
    executionLease: ConfiguredModelExecutionLease,
): ProductionModelTaskQueueOwner =
    try {
        val modelTaskStorage = sessionOwner.modelTasksAndAssetDocuments()
        val modelAssets =
            RestrictedModelAssetSourceFactory.create(
                context = context,
                modelTasksAndAssetDocuments = modelTaskStorage,
            )
        val execution = executionLease.claimForModelTaskOwner()
        val modelTasks =
            ModelTaskRepositoryFactory.create(
                modelTasksAndAssetDocuments = modelTaskStorage,
                gateway = ConfiguredModelExecutionGateway(execution, modelAssets),
            )
        ProductionModelTaskQueueOwner.issue(
            modelTasks,
            modelAssets,
            executionLease,
        )
    } catch (failure: Throwable) {
        executionLease.closeWithSuppressed(failure)
        throw failure
    }
