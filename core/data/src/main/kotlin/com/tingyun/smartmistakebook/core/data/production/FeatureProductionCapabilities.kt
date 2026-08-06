package com.tingyun.smartmistakebook.core.data.production

import com.tingyun.smartmistakebook.core.data.authority.LocalLearningAuthorityRuntime
import com.tingyun.smartmistakebook.core.data.authority.ProductionAdapterManifest
import com.tingyun.smartmistakebook.core.data.authority.ThreeAuthorityProductionStartupState
import com.tingyun.smartmistakebook.core.data.mistake.StudentMistakeLibraryCatalogRepository
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingActionPort
import com.tingyun.smartmistakebook.core.data.review.DailyReviewPacingCommandFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewProductionCapability
import com.tingyun.smartmistakebook.core.data.review.DailyReviewAnswerSubmissionPortFactory
import com.tingyun.smartmistakebook.core.data.review.DailyReviewSessionActionPort
import com.tingyun.smartmistakebook.core.domain.BatchImportRepository
import com.tingyun.smartmistakebook.core.domain.CaptureWorkflowRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.MistakeDetailRepository
import com.tingyun.smartmistakebook.core.domain.MistakeOrganizationRepository
import com.tingyun.smartmistakebook.core.domain.ScopedModelTaskPort
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorLearningMemoryRepository
import com.tingyun.smartmistakebook.core.domain.TutorMasteryContextRepository
import com.tingyun.smartmistakebook.core.domain.TutorTeachingReferenceRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.jvm.JvmSynthetic

/**
 * Feature-facing production areas.
 *
 * These names are presentation boundaries only. Learner identity, authority generation, knowledge
 * activation, and adapter-manifest identity remain publication-owner details.
 */
enum class ProductionFeature {
    REVIEW,
    CAPTURE,
    LIBRARY,
    TUTOR,
    PROFILE,
}

/**
 * A fail-closed reason that is safe to expose outside core:data.
 *
 * Deliberately absent are learner ids, proof fingerprints, generation numbers, knowledge pack
 * identities, activation receipts, and manifest contents.
 */
enum class FeatureProductionCapabilityUnavailableReason {
    TERMINAL_GATE_NOT_READY,
    AUTHORITY_RUNTIME_UNAVAILABLE,
    PRODUCTION_MANIFEST_INCOMPLETE,
    CURRENT_GENERATION_BINDING_UNAVAILABLE,
    REQUIRED_PORT_UNAVAILABLE,
}

/**
 * Publication result for one feature.
 *
 * [Available] cannot be constructed outside this module. Feature modules can consume a published
 * token or render an unavailable state, but cannot bless their own repositories as production.
 */
sealed interface FeatureProductionCapabilityAvailability<out Capability : AutoCloseable> {
    sealed interface Available<out Capability : AutoCloseable> :
        FeatureProductionCapabilityAvailability<Capability> {
        val capability: Capability
    }

    data class Unavailable(
        val reason: FeatureProductionCapabilityUnavailableReason,
    ) : FeatureProductionCapabilityAvailability<Nothing>
}

/**
 * REVIEW receives only the already-bounded review repository and action ports.
 *
 * It cannot access a student-mistake owner, mastery writer, database, or terminal proof.
 */
sealed interface ReviewFeatureProductionCapability : AutoCloseable {
    val planning: DailyReviewProductionCapability
    val sessionActions: DailyReviewSessionActionPort
    val pacingActions: DailyReviewPacingActionPort
    val answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory
    val pacingCommandFactory: DailyReviewPacingCommandFactory
}

/** CAPTURE receives no asset stream or storage handle, only its three route-facing repositories. */
sealed interface CaptureFeatureProductionCapability : AutoCloseable {
    val captureWorkflow: CaptureWorkflowRepository
    val batchImports: BatchImportRepository
    val modelTasks: ScopedModelTaskPort
}

/**
 * LIBRARY receives only the verified catalog projection and narrow question-facing repositories.
 */
sealed interface LibraryFeatureProductionCapability : AutoCloseable {
    val catalog: StudentMistakeLibraryCatalogRepository
    val mistakeDetails: MistakeDetailRepository
    val organization: MistakeOrganizationRepository
    val batchImports: BatchImportRepository
    val modelTasks: ScopedModelTaskPort
}

/**
 * TUTOR receives the current captured-question session plus bounded interaction, memory, mastery,
 * teaching-reference, and model-task ports.
 */
sealed interface TutorFeatureProductionCapability : AutoCloseable {
    val tutorSessions: CaptureWorkflowRepository
    val interactions: TutorInteractionRepository
    val learningMemory: TutorLearningMemoryRepository
    val masteryContext: TutorMasteryContextRepository
    val teachingReferences: TutorTeachingReferenceRepository
    val modelTasks: ScopedModelTaskPort
}

/** PROFILE receives only the qualitative, learner-facing mastery display repository. */
sealed interface ProfileFeatureProductionCapability : AutoCloseable {
    val learningMasteryDisplay: LearningMasteryDisplayRepository
}

/**
 * One publication snapshot for the five route families.
 *
 * Closing the snapshot closes every available token exactly once. Individual tokens are also
 * independently closeable, so a feature can release its lease without closing another feature.
 */
sealed interface FeatureProductionCapabilities : AutoCloseable {
    val review: FeatureProductionCapabilityAvailability<ReviewFeatureProductionCapability>
    val capture: FeatureProductionCapabilityAvailability<CaptureFeatureProductionCapability>
    val library: FeatureProductionCapabilityAvailability<LibraryFeatureProductionCapability>
    val tutor: FeatureProductionCapabilityAvailability<TutorFeatureProductionCapability>
    val profile: FeatureProductionCapabilityAvailability<ProfileFeatureProductionCapability>
}

/**
 * The module owner for issued tokens.
 *
 * These methods are Kotlin-internal and JVM-synthetic. A feature module can receive the public
 * interfaces above, but it cannot call this owner or construct the file-private implementations.
 */
internal object FeatureProductionCapabilityOwner {
    @JvmSynthetic
    internal fun issueReview(
        planning: DailyReviewProductionCapability,
        sessionActions: DailyReviewSessionActionPort,
        pacingActions: DailyReviewPacingActionPort,
        answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
        pacingCommandFactory: DailyReviewPacingCommandFactory,
        onClose: () -> Unit,
    ): ReviewFeatureProductionCapability =
        IssuedReviewFeatureProductionCapability(
            planning = planning,
            sessionActions = sessionActions,
            pacingActions = pacingActions,
            answerSubmissionPorts = answerSubmissionPorts,
            pacingCommandFactory = pacingCommandFactory,
            lease = FeatureProductionCapabilityLease(onClose),
        )

    @JvmSynthetic
    internal fun issueCapture(
        captureWorkflow: CaptureWorkflowRepository,
        batchImports: BatchImportRepository,
        modelTasks: ScopedModelTaskPort,
        onClose: () -> Unit,
    ): CaptureFeatureProductionCapability =
        IssuedCaptureFeatureProductionCapability(
            captureWorkflow = captureWorkflow,
            batchImports = batchImports,
            modelTasks = modelTasks,
            lease = FeatureProductionCapabilityLease(onClose),
        )

    @JvmSynthetic
    internal fun issueLibrary(
        catalog: StudentMistakeLibraryCatalogRepository,
        mistakeDetails: MistakeDetailRepository,
        organization: MistakeOrganizationRepository,
        batchImports: BatchImportRepository,
        modelTasks: ScopedModelTaskPort,
        onClose: () -> Unit,
    ): LibraryFeatureProductionCapability =
        IssuedLibraryFeatureProductionCapability(
            catalog = catalog,
            mistakeDetails = mistakeDetails,
            organization = organization,
            batchImports = batchImports,
            modelTasks = modelTasks,
            lease = FeatureProductionCapabilityLease(onClose),
        )

    @JvmSynthetic
    internal fun issueTutor(
        tutorSessions: CaptureWorkflowRepository,
        interactions: TutorInteractionRepository,
        learningMemory: TutorLearningMemoryRepository,
        masteryContext: TutorMasteryContextRepository,
        teachingReferences: TutorTeachingReferenceRepository,
        modelTasks: ScopedModelTaskPort,
        onClose: () -> Unit,
    ): TutorFeatureProductionCapability =
        IssuedTutorFeatureProductionCapability(
            tutorSessions = tutorSessions,
            interactions = interactions,
            learningMemory = learningMemory,
            masteryContext = masteryContext,
            teachingReferences = teachingReferences,
            modelTasks = modelTasks,
            lease = FeatureProductionCapabilityLease(onClose),
        )

    @JvmSynthetic
    internal fun issueProfile(
        learningMasteryDisplay: LearningMasteryDisplayRepository,
        onClose: () -> Unit,
    ): ProfileFeatureProductionCapability =
        IssuedProfileFeatureProductionCapability(
            learningMasteryDisplay = learningMasteryDisplay,
            lease = FeatureProductionCapabilityLease(onClose),
        )

    @JvmSynthetic
    internal fun <Capability : AutoCloseable> available(
        capability: Capability,
    ): FeatureProductionCapabilityAvailability.Available<Capability> =
        IssuedFeatureProductionCapabilityAvailability(capability)

    @JvmSynthetic
    internal fun unavailable(
        reason: FeatureProductionCapabilityUnavailableReason,
    ): FeatureProductionCapabilities {
        val unavailable = FeatureProductionCapabilityAvailability.Unavailable(reason)
        return issue(
            review = unavailable,
            capture = unavailable,
            library = unavailable,
            tutor = unavailable,
            profile = unavailable,
        )
    }

    @JvmSynthetic
    internal fun issue(
        review: FeatureProductionCapabilityAvailability<ReviewFeatureProductionCapability>,
        capture: FeatureProductionCapabilityAvailability<CaptureFeatureProductionCapability>,
        library: FeatureProductionCapabilityAvailability<LibraryFeatureProductionCapability>,
        tutor: FeatureProductionCapabilityAvailability<TutorFeatureProductionCapability>,
        profile: FeatureProductionCapabilityAvailability<ProfileFeatureProductionCapability>,
    ): FeatureProductionCapabilities =
        IssuedFeatureProductionCapabilities(
            review = review,
            capture = capture,
            library = library,
            tutor = tutor,
            profile = profile,
        )
}

private class IssuedFeatureProductionCapabilityAvailability<
    out Capability : AutoCloseable,
>(
    override val capability: Capability,
) : FeatureProductionCapabilityAvailability.Available<Capability>

private class IssuedReviewFeatureProductionCapability(
    override val planning: DailyReviewProductionCapability,
    override val sessionActions: DailyReviewSessionActionPort,
    override val pacingActions: DailyReviewPacingActionPort,
    override val answerSubmissionPorts: DailyReviewAnswerSubmissionPortFactory,
    override val pacingCommandFactory: DailyReviewPacingCommandFactory,
    private val lease: FeatureProductionCapabilityLease,
) : ReviewFeatureProductionCapability {
    override fun close() = lease.close()
}

private class IssuedCaptureFeatureProductionCapability(
    override val captureWorkflow: CaptureWorkflowRepository,
    override val batchImports: BatchImportRepository,
    override val modelTasks: ScopedModelTaskPort,
    private val lease: FeatureProductionCapabilityLease,
) : CaptureFeatureProductionCapability {
    override fun close() = lease.close()
}

private class IssuedLibraryFeatureProductionCapability(
    override val catalog: StudentMistakeLibraryCatalogRepository,
    override val mistakeDetails: MistakeDetailRepository,
    override val organization: MistakeOrganizationRepository,
    override val batchImports: BatchImportRepository,
    override val modelTasks: ScopedModelTaskPort,
    private val lease: FeatureProductionCapabilityLease,
) : LibraryFeatureProductionCapability {
    override fun close() = lease.close()
}

private class IssuedTutorFeatureProductionCapability(
    override val tutorSessions: CaptureWorkflowRepository,
    override val interactions: TutorInteractionRepository,
    override val learningMemory: TutorLearningMemoryRepository,
    override val masteryContext: TutorMasteryContextRepository,
    override val teachingReferences: TutorTeachingReferenceRepository,
    override val modelTasks: ScopedModelTaskPort,
    private val lease: FeatureProductionCapabilityLease,
) : TutorFeatureProductionCapability {
    override fun close() = lease.close()
}

private class IssuedProfileFeatureProductionCapability(
    override val learningMasteryDisplay: LearningMasteryDisplayRepository,
    private val lease: FeatureProductionCapabilityLease,
) : ProfileFeatureProductionCapability {
    override fun close() = lease.close()
}

private class IssuedFeatureProductionCapabilities(
    override val review:
        FeatureProductionCapabilityAvailability<ReviewFeatureProductionCapability>,
    override val capture:
        FeatureProductionCapabilityAvailability<CaptureFeatureProductionCapability>,
    override val library:
        FeatureProductionCapabilityAvailability<LibraryFeatureProductionCapability>,
    override val tutor:
        FeatureProductionCapabilityAvailability<TutorFeatureProductionCapability>,
    override val profile:
        FeatureProductionCapabilityAvailability<ProfileFeatureProductionCapability>,
) : FeatureProductionCapabilities {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var firstFailure: Throwable? = null
        listOf(profile, tutor, library, capture, review)
            .forEach { availability ->
                val capability =
                    (
                        availability as?
                            FeatureProductionCapabilityAvailability.Available<*>
                    )?.capability ?: return@forEach
                try {
                    capability.close()
                } catch (failure: Throwable) {
                    val first = firstFailure
                    if (first == null) {
                        firstFailure = failure
                    } else {
                        first.addSuppressed(failure)
                    }
                }
            }
        firstFailure?.let { throw it }
    }
}

/**
 * The current safe assembly boundary.
 *
 * The existing terminal Ready token proves that a terminal gate succeeded but intentionally
 * carries no cutover generation or proof fingerprint. [LocalLearningAuthorityRuntime] likewise
 * keeps learner identity and knowledge activation private. Consequently this file cannot prove
 * that an arbitrary Ready token and runtime belong to the same current generation.
 *
 * Until the authority runtime issues one opaque binding that joins those facts with owner-issued
 * legacy-session ports, publication must remain unavailable. This method accepts neither a legacy
 * repository fallback, a database capability, nor a raw database owner.
 */
internal object FeatureProductionCapabilitiesFactory {
    @JvmSynthetic
    internal fun fromTerminalRuntime(
        terminalState: ThreeAuthorityProductionStartupState,
        runtime: LocalLearningAuthorityRuntime?,
        manifest: ProductionAdapterManifest,
    ): FeatureProductionCapabilities {
        val reason =
            when {
                terminalState !is ThreeAuthorityProductionStartupState.Ready ->
                    FeatureProductionCapabilityUnavailableReason.TERMINAL_GATE_NOT_READY
                runtime == null ->
                    FeatureProductionCapabilityUnavailableReason.AUTHORITY_RUNTIME_UNAVAILABLE
                !manifest.isComplete ->
                    FeatureProductionCapabilityUnavailableReason.PRODUCTION_MANIFEST_INCOMPLETE
                else ->
                    FeatureProductionCapabilityUnavailableReason
                        .CURRENT_GENERATION_BINDING_UNAVAILABLE
            }
        return FeatureProductionCapabilityOwner.unavailable(reason)
    }
}

private class FeatureProductionCapabilityLease(
    private val release: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            release()
        }
    }
}
