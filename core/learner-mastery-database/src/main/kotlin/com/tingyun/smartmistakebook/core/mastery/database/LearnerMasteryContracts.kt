package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

const val LEARNER_MASTERY_DATABASE_NAME = "learner-mastery.db"
const val LEARNER_MASTERY_SOURCE_POLICY_VERSION = "learner-mastery-source-v2"
const val LEARNER_MASTERY_PROJECTION_POLICY_VERSION = "learner-mastery-projection-v3"
internal const val LEARNER_MASTERY_ADMISSION_POLICY_VERSION =
    "learner-mastery-admission-v6"
internal const val LEARNER_MASTERY_CALIBRATION_VERSION =
    "learner-mastery-calibration-v4"
internal const val LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION =
    "learner-mastery-calibration-v3"
internal const val LEARNER_MASTERY_OLDER_CALIBRATION_VERSION =
    "learner-mastery-calibration-v2"
internal const val LEARNER_MASTERY_LEGACY_PROJECTION_POLICY_VERSION =
    "learner-mastery-projection-v1"
internal const val LEARNER_MASTERY_MASS_RATIO_PROJECTION_POLICY_VERSION =
    "learner-mastery-projection-v2"

/**
 * Frozen wire value for pre-snapshot heuristics. It is not a calibration registry entry and must
 * never be resolved as one.
 */
internal const val LEARNER_MASTERY_LEGACY_CALIBRATION_VERSION =
    "learner-mastery-calibration-v1"
internal const val LEARNER_MASTERY_AUTHORITY_IDENTITY_VERSION =
    "learner-mastery-evidence-identity-v1"

internal enum class MasteryCalibrationBindingStatus {
    BOUND,
    LEGACY_UNCALIBRATED,
}

/**
 * Internal owner capability. It must never be handed to a model-facing adapter because it also
 * owns trusted source-fact ingestion and analytics reads.
 */
internal interface LearnerMasteryStore : AutoCloseable {
    suspend fun ingestSourceFact(
        command: IngestLearningSourceFactCommand,
    ): LearningSourceFactIngestResult

    suspend fun ingestObservationCandidate(
        command: IngestLearningObservationCandidateCommand,
    ): LearningObservationIngestResult

    suspend fun ingestModelObservationCandidate(
        command: IngestLearningObservationCandidateCommand,
        attempt: ModelSubmissionAttempt,
    ): ModelSubmissionAttemptOutcome

    suspend fun recordRejectedModelSubmissionAttempt(
        attempt: ModelSubmissionAttempt,
        terminalReason: ModelSubmissionTerminalReason,
    ): ModelSubmissionAttemptReceipt

    suspend fun enqueuePendingOpenResponse(
        sourceFact: IngestLearningSourceFactCommand,
        candidate: IngestLearningObservationCandidateCommand,
    ): PendingOpenResponsePersistenceResult

    suspend fun readPendingEvidenceReviews(
        learnerId: String,
        subject: SubjectKind,
        limit: Int,
    ): List<PendingLearningEvidenceReview>

    suspend fun resolveEvidenceReview(
        learnerId: String,
        command: ResolveLearningEvidenceReviewCommand,
    ): LearningEvidenceReviewWriteResult

    suspend fun correctLearningEvidence(
        learnerId: String,
        command: CorrectLearningEvidenceCommand,
        replacementSourceFact: IngestLearningSourceFactCommand,
        replacementCandidate: IngestLearningObservationCandidateCommand,
    ): LearningEvidenceCorrectionResult

    suspend fun acceptProblemKnowledgeBindings(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition

    suspend fun acceptStudentProblemReference(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition

    suspend fun acceptProblemLifecycleChange(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition

    suspend fun acceptProblemRevisionSupersession(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition

    suspend fun acceptStudentReviewObservation(
        message: VerifiedStudentMistakeDelivery,
        receivedAtEpochMillis: Long,
    ): MasteryInboundDisposition

    suspend fun isProblemRevisionAuthorized(
        problemRevision: StudentProblemRevisionRef,
    ): Boolean

    suspend fun areProblemKnowledgeBindingsAuthorized(
        problemRevisionCanonicalFingerprint: String,
        bindingCanonicalFingerprints: Set<String>,
    ): Boolean

    /**
     * Returns the current V2 binding-authority snapshot for one problem revision. The review
     * relay deliberately carries no semantic bindings, so the mastery authority re-reads them
     * from its own immutable binding authority before creating the trusted candidate.
     */
    suspend fun readAuthorizedProblemKnowledgeBindings(
        problemRevision: StudentProblemRevisionRef,
    ): List<ProblemKnowledgeBindingRef>

    suspend fun readPendingMessages(
        learnerId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<LearnerMasteryRelayMessage>

    suspend fun markMessageDelivered(
        message: LearnerMasteryOutboxDelivery,
        deliveredAtEpochMillis: Long,
    )

    suspend fun querySubjectDigest(
        query: SubjectMasteryDigestQuery,
    ): SubjectMasteryDigest

    suspend fun queryLocalMasteryContext(
        query: BoundLocalMasteryContextQuery,
    ): LocalMasteryContext

    suspend fun querySubjectTimeline(
        query: SubjectMasteryTimelineQuery,
    ): List<SubjectMasteryTimelineEntry>

    fun observeDisplayRevision(
        query: BoundLearnerMasteryDisplayQuery,
    ): kotlinx.coroutines.flow.Flow<LearnerMasteryDisplayRevision>

    suspend fun readDisplayOverview(
        query: BoundLearnerMasteryDisplayQuery,
        expectedRevision: LearnerMasteryDisplayRevision,
    ): LearnerMasteryDisplayOverviewResult

    suspend fun readDisplayKnowledgePage(
        query: BoundLearnerMasteryDisplayPageQuery,
    ): LearnerMasteryDisplayPageResult

    suspend fun readDisplaySubjectTimeline(
        query: BoundLearnerMasteryDisplayTimelineQuery,
    ): LearnerMasteryDisplayTimelineResult

    suspend fun migrateLegacyFactBatch(
        observations: List<LegacyMasteryObservationWrite>,
        checkpoint: MasteryLegacyFactMigrationCheckpointEntity,
    ): LegacyMasteryFactBatchWriteDisposition

    suspend fun eraseAllLearnerData(): LearnerMasteryEraseResult
}

data class LearnerMasteryEraseResult(
    val erasedAtEpochMillis: Long,
) {
    init {
        require(erasedAtEpochMillis >= 0L) {
            "Learner-mastery erase time must not be negative"
        }
    }
}

internal data class LegacyMasteryObservationWrite(
    val sourceFact: IngestLearningSourceFactCommand,
    val candidate: IngestLearningObservationCandidateCommand,
)

internal enum class LegacyMasteryFactBatchWriteDisposition {
    IMPORTED,
    DUPLICATE,
    OUT_OF_ORDER,
    CONFLICT,
}

/**
 * The only model-facing write capability. Learner, subject, model metadata, candidate identity,
 * timestamps and policy version are bound locally and therefore cannot be supplied by a model.
 * The result deliberately coarsens every internal rejection so it cannot become an existence
 * oracle for another learner, subject or source fact.
 */
interface LearnerMasteryCandidateSink {
    suspend fun submitCandidate(
        command: SubmitLearningObservationCandidateCommand,
    ): ModelLearningObservationResult
}

enum class ModelLearningObservationDisposition {
    RECEIVED,
    RETRYABLE,
}

class ModelLearningObservationResult(
    val disposition: ModelLearningObservationDisposition,
    val receiptFingerprint: String?,
) {
    init {
        require(
            (disposition == ModelLearningObservationDisposition.RECEIVED) ==
                (receiptFingerprint != null),
        ) {
            "Only a terminal model learning observation may expose a receipt fingerprint"
        }
        receiptFingerprint?.let { fingerprint ->
            requireMasteryFingerprint(
                fingerprint,
                "Model learning observation receipt fingerprint",
            )
        }
    }
}

internal data class SubjectMasteryDigestQuery(
    val learnerId: String,
    val subject: SubjectKind,
    val focusLimit: Int = DEFAULT_FOCUS_LIMIT,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
        requireSpecificSubject(subject)
        require(focusLimit in 1..MAX_FOCUS_LIMIT) {
            "Subject digest focus limit is out of range"
        }
    }

    companion object {
        const val DEFAULT_FOCUS_LIMIT = LearnerMasteryReader.DEFAULT_DIGEST_FOCUS_LIMIT
        const val MAX_FOCUS_LIMIT = LearnerMasteryReader.MAX_DIGEST_FOCUS_LIMIT
    }
}

enum class KnowledgeMasteryState {
    NEEDS_REINFORCEMENT,
    FAMILIARIZING,
    STEADY,
}

enum class KnowledgeMasteryTrend {
    IMPROVING,
    STABLE,
    WAVERING,
}

data class KnowledgeMasteryDigestItem(
    val knowledgeNode: KnowledgeNodeRef,
    val historicalState: KnowledgeMasteryState,
    val currentRecallState: KnowledgeMasteryState,
    val trend: KnowledgeMasteryTrend,
    val evidenceQuality: MasteryEvidenceQuality,
    val independentProblemFamilyCount: Long,
    val distinctPresentationCount: Long,
    val memoryStabilityMillis: Long,
    val recallDueAtEpochMillis: Long,
    val lastPositiveAtEpochMillis: Long?,
    val lastNegativeAtEpochMillis: Long?,
    val lastEvidenceAtEpochMillis: Long,
)

data class SubjectMasteryStateCounts(
    val needsReinforcement: Int,
    val familiarizing: Int,
    val steady: Int,
) {
    init {
        require(needsReinforcement >= 0 && familiarizing >= 0 && steady >= 0) {
            "Subject mastery counts must not be negative"
        }
    }
}

enum class LearnerMasteryHistoryAvailability {
    AVAILABLE,
    INSUFFICIENT_HISTORY,
}

data class SubjectMasteryDigest(
    val learnerId: String,
    val subject: SubjectKind,
    val stateCounts: SubjectMasteryStateCounts,
    val focus: List<KnowledgeMasteryDigestItem>,
    val lastUpdatedAtEpochMillis: Long?,
    val historyAvailability: LearnerMasteryHistoryAvailability =
        LearnerMasteryHistoryAvailability.AVAILABLE,
)

class LocalMasteryContextRequest(
    val subject: SubjectKind,
    exactStableNodeFingerprints: List<String>,
    val fallbackLimit: Int = DEFAULT_FALLBACK_LIMIT,
) {
    val exactStableNodeFingerprints: List<String> =
        Collections.unmodifiableList(exactStableNodeFingerprints.toList())

    init {
        requireSpecificSubject(subject)
        require(this.exactStableNodeFingerprints.size <= MAX_EXACT_NODE_COUNT) {
            "Local mastery context exceeds the exact-node budget"
        }
        this.exactStableNodeFingerprints.forEach { fingerprint ->
            requireMasteryFingerprint(fingerprint, "Stable knowledge-node fingerprint")
        }
        require(
            this.exactStableNodeFingerprints.distinct().size ==
                this.exactStableNodeFingerprints.size,
        ) {
            "Local mastery context exact nodes must be unique"
        }
        require(fallbackLimit in 0..MAX_FALLBACK_COUNT) {
            "Local mastery context fallback limit is out of range"
        }
    }

    companion object {
        const val MAX_EXACT_NODE_COUNT = 16
        const val DEFAULT_FALLBACK_LIMIT = 4
        const val MAX_FALLBACK_COUNT = 4
        const val MAX_RESULT_COUNT = MAX_EXACT_NODE_COUNT + MAX_FALLBACK_COUNT

        fun fromKnowledgeNodes(
            subject: SubjectKind,
            exactKnowledgeNodes: List<KnowledgeNodeRef>,
            fallbackLimit: Int = DEFAULT_FALLBACK_LIMIT,
        ): LocalMasteryContextRequest {
            require(exactKnowledgeNodes.all { it.subject == subject }) {
                "Local mastery context knowledge nodes must match the requested subject"
            }
            return LocalMasteryContextRequest(
                subject = subject,
                exactStableNodeFingerprints =
                    exactKnowledgeNodes.map { node ->
                        MasteryProjectionIdentity.fingerprint(
                            subject = node.subject.name,
                            knowledgeNodeId = node.knowledgeNodeId,
                            taxonomyVersion = node.taxonomyVersion,
                        )
                    },
                fallbackLimit = fallbackLimit,
            )
        }
    }
}

internal data class BoundLocalMasteryContextQuery(
    val learnerId: String,
    val request: LocalMasteryContextRequest,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
    }
}

enum class LocalMasteryContextSelection {
    EXACT,
    SUBJECT_FOCUS,
}

data class LocalMasteryContextItem(
    val selection: LocalMasteryContextSelection,
    val stableNodeIdentityFingerprint: String,
    val knowledgeNode: KnowledgeNodeRef,
    val historicalState: KnowledgeMasteryState,
    val currentRecallState: KnowledgeMasteryState,
    val trend: KnowledgeMasteryTrend,
    val evidenceQuality: MasteryEvidenceQuality,
) {
    init {
        requireMasteryFingerprint(
            stableNodeIdentityFingerprint,
            "Stable knowledge-node fingerprint",
        )
    }
}

class LocalMasteryContext(
    val subject: SubjectKind,
    items: List<LocalMasteryContextItem>,
    val historyAvailability: LearnerMasteryHistoryAvailability =
        LearnerMasteryHistoryAvailability.AVAILABLE,
) {
    val items: List<LocalMasteryContextItem> =
        Collections.unmodifiableList(items.toList())

    init {
        requireSpecificSubject(subject)
        require(this.items.size <= LocalMasteryContextRequest.MAX_RESULT_COUNT) {
            "Local mastery context result exceeds the item budget"
        }
        require(
            this.items.count { it.selection == LocalMasteryContextSelection.EXACT } <=
                LocalMasteryContextRequest.MAX_EXACT_NODE_COUNT,
        ) {
            "Local mastery context result exceeds the exact-node budget"
        }
        require(
            this.items.count { it.selection == LocalMasteryContextSelection.SUBJECT_FOCUS } <=
                LocalMasteryContextRequest.MAX_FALLBACK_COUNT,
        ) {
            "Local mastery context result exceeds the subject-focus budget"
        }
        require(this.items.all { it.knowledgeNode.subject == subject }) {
            "Local mastery context result crossed its subject scope"
        }
        require(
            this.items.map(LocalMasteryContextItem::stableNodeIdentityFingerprint)
                .distinct()
                .size == this.items.size,
        ) {
            "Local mastery context result contains duplicate knowledge nodes"
        }
        val firstFallback =
            this.items.indexOfFirst {
                it.selection == LocalMasteryContextSelection.SUBJECT_FOCUS
            }
        require(
            firstFallback == -1 ||
                this.items.drop(firstFallback).all {
                    it.selection == LocalMasteryContextSelection.SUBJECT_FOCUS
                },
        ) {
            "Exact local mastery context items must precede subject focus"
        }
    }
}

internal data class SubjectMasteryTimelineQuery(
    val learnerId: String,
    val subject: SubjectKind,
    val sinceEpochMillis: Long = 0,
    val dayLimit: Int = DEFAULT_DAY_LIMIT,
) {
    init {
        requireMasteryIdentity(learnerId, "Learner id")
        requireSpecificSubject(subject)
        require(sinceEpochMillis >= 0) {
            "Timeline start must not be negative"
        }
        require(dayLimit in 1..MAX_DAY_LIMIT) {
            "Timeline day limit is out of range"
        }
    }

    companion object {
        const val DEFAULT_DAY_LIMIT = LearnerMasteryReader.DEFAULT_TIMELINE_DAY_LIMIT
        const val MAX_DAY_LIMIT = LearnerMasteryReader.MAX_TIMELINE_DAY_LIMIT
    }
}

enum class SubjectMasteryTimelineSignal {
    PROGRESS,
    MIXED,
    NEEDS_ATTENTION,
}

enum class SubjectMasteryTimelineActivity {
    LIGHT,
    REGULAR,
    INTENSIVE,
}

data class SubjectMasteryTimelineEntry(
    val utcEpochDay: Long,
    val signal: SubjectMasteryTimelineSignal,
    val activity: SubjectMasteryTimelineActivity,
    val observationCount: Int,
    val affectedKnowledgeCount: Int,
)

internal fun requireSpecificSubject(subject: SubjectKind) {
    require(subject != SubjectKind.GENERAL) {
        "Learner mastery must be scoped to one high-school subject"
    }
}

internal fun requireMasteryIdentity(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= 256 &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value of at most 256 characters"
    }
}

internal fun requireMasteryOpaqueReference(value: String, label: String) {
    requireMasteryIdentity(value, label)
    require(Regex("[A-Za-z0-9][A-Za-z0-9._:+-]{0,255}").matches(value)) {
        "$label must be an opaque reference, not retained content"
    }
}

internal fun requireMasteryVersion(value: String, label: String) {
    requireMasteryIdentity(value, label)
    require(
        value.length <= 128 &&
            Regex("[A-Za-z0-9][A-Za-z0-9._:+-]*").matches(value),
    ) {
        "$label contains unsupported characters"
    }
}

internal fun requireMasteryFingerprint(value: String, label: String) {
    require(Regex("[0-9a-f]{64}").matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}
