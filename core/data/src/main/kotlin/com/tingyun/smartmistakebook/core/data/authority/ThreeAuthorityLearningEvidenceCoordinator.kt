package com.tingyun.smartmistakebook.core.data.authority

import com.tingyun.smartmistakebook.core.mastery.database.EphemeralTutorProblemLearningContext
import com.tingyun.smartmistakebook.core.mastery.database.LearnerMasteryRuntimeCapabilities
import com.tingyun.smartmistakebook.core.mastery.database.LearningObservationFacts
import com.tingyun.smartmistakebook.core.mastery.database.PendingOpenResponseDisposition
import com.tingyun.smartmistakebook.core.mastery.database.PendingOpenResponseFacts
import com.tingyun.smartmistakebook.core.mastery.database.PendingOpenResponseResult
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationDisposition
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationResult
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningObservationSource
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningResponseForm
import com.tingyun.smartmistakebook.core.mastery.database.TrustedLearningVerification
import com.tingyun.smartmistakebook.core.mastery.database.VerifiedEphemeralKnowledgeEvidence
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentMistakeRuntimeCapabilities
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewResponseForm
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewScheduleUpdate
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewTransitionResult
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.student.mistake.database.SubmitStudentReviewResponseCommand
import java.util.Collections
import kotlinx.coroutines.CancellationException

enum class StudyEvidenceResponseForm {
    CHOICE,
    NUMERIC,
    VISUAL_TARGET,
}

enum class StudyEvidenceVerificationOutcome {
    CORRECT,
    INCORRECT,
}

data class SavedReviewSchedule(
    val nextAvailableAtEpochMillis: Long,
    val nextDueAtEpochMillis: Long?,
    val schedulingPolicyVersion: String,
)

data class SavedReviewEvidenceCommand(
    val transitionId: String,
    val sessionId: String,
    val queueItemId: String,
    val expectedSessionVersion: Long,
    val presentationId: String,
    val observationId: String,
    val submissionId: String,
    val responseForm: StudyEvidenceResponseForm,
    val responseCanonicalFingerprint: String,
    val verificationOutcome: StudyEvidenceVerificationOutcome,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val verificationPolicyVersion: String,
    val elapsedDurationMillis: Long?,
    val schedule: SavedReviewSchedule,
    val capturedAtEpochMillis: Long,
)

enum class SavedReviewTransitionDisposition {
    APPLIED,
    DUPLICATE,
}

enum class AuthorityRelayDelivery {
    DELIVERED,
    PENDING_RETRY,
}

sealed interface SavedReviewEvidenceWriteResult {
    data class Saved(
        val transition: SavedReviewTransitionDisposition,
        val sessionVersion: Long,
        val relayDelivery: AuthorityRelayDelivery,
    ) : SavedReviewEvidenceWriteResult

    data object ReloadRequired : SavedReviewEvidenceWriteResult

    data object StorageUnavailable : SavedReviewEvidenceWriteResult

    data object Rejected : SavedReviewEvidenceWriteResult
}

sealed interface OrdinaryStudyResponse {
    val responseCanonicalFingerprint: String
    val verificationPolicyVersion: String

    data class Choice(
        override val responseCanonicalFingerprint: String,
        override val verificationPolicyVersion: String,
        val isCorrect: Boolean,
    ) : OrdinaryStudyResponse

    data class Numeric(
        override val responseCanonicalFingerprint: String,
        override val verificationPolicyVersion: String,
        val isCorrect: Boolean,
    ) : OrdinaryStudyResponse

    data class VisualTarget(
        override val responseCanonicalFingerprint: String,
        override val verificationPolicyVersion: String,
        val isCorrect: Boolean,
    ) : OrdinaryStudyResponse

    data class LocallyVerifiedFreeResponse(
        override val responseCanonicalFingerprint: String,
        override val verificationPolicyVersion: String,
        val rubricCanonicalFingerprint: String,
        val isCorrect: Boolean,
    ) : OrdinaryStudyResponse

    data class UnverifiedFreeResponse(
        override val responseCanonicalFingerprint: String,
        override val verificationPolicyVersion: String,
    ) : OrdinaryStudyResponse
}

class UnsavedStudyEvidenceCommand(
    val submissionId: String,
    val subject: SubjectKind,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val interactionReferenceId: String,
    val attributionPolicyVersion: String,
    verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof>,
    val response: OrdinaryStudyResponse,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val elapsedDurationMillis: Long?,
    val occurredAtEpochMillis: Long,
) {
    val verifiedKnowledgeProofs: List<VerifiedKnowledgeReferenceProof> =
        Collections.unmodifiableList(verifiedKnowledgeProofs.toList())

    init {
        requireEvidenceIdentity(submissionId, "Study submission id")
        require(subject != SubjectKind.GENERAL) {
            "Study evidence must belong to one high-school subject"
        }
        requireEvidenceFingerprint(presentationFingerprint, "Study presentation fingerprint")
        requireEvidenceFingerprint(problemFingerprint, "Study problem fingerprint")
        requireEvidenceFingerprint(problemFamilyFingerprint, "Study problem-family fingerprint")
        requireEvidenceIdentity(interactionReferenceId, "Study interaction reference id")
        requireEvidenceVersion(attributionPolicyVersion, "Study attribution-policy version")
        response.requireValid()
        require(attemptOrdinal in 1..MAX_ATTEMPT_ORDINAL) {
            "Study attempt ordinal is outside the supported range"
        }
        require(hintCount in 0..MAX_HINT_COUNT) {
            "Study hint count is outside the supported range"
        }
        require(
            elapsedDurationMillis == null ||
                elapsedDurationMillis in 0L..MAX_ELAPSED_DURATION_MILLIS,
        ) {
            "Study elapsed duration is outside the supported range"
        }
        require(occurredAtEpochMillis >= 0L) {
            "Study response time must not be negative"
        }
        require(this.verifiedKnowledgeProofs.size <= MAX_KNOWLEDGE_PROOFS) {
            "Study knowledge evidence exceeds the supported proof budget"
        }
        require(
            this.verifiedKnowledgeProofs
                .map { it.ref.canonicalFingerprint }
                .distinct()
                .size == this.verifiedKnowledgeProofs.size,
        ) {
            "Study knowledge evidence must contain unique node references"
        }
        require(this.verifiedKnowledgeProofs.all { it.ref.subject == subject }) {
            "Study knowledge evidence must stay inside the response subject"
        }
        require(
            this.verifiedKnowledgeProofs
                .map { it.manifestFingerprint to it.activationGeneration }
                .distinct()
                .size <= 1,
        ) {
            "Study knowledge evidence must come from one activated catalog generation"
        }
    }

    private companion object {
        const val MAX_ATTEMPT_ORDINAL = 17
        const val MAX_HINT_COUNT = 32
        const val MAX_KNOWLEDGE_PROOFS = 32
        const val MAX_ELAPSED_DURATION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

enum class RecordedStudyEvidenceDisposition {
    ADMITTED,
    FACT_STORED,
    DUPLICATE,
    INERT,
}

enum class PendingAttributionDisposition {
    QUEUED,
    DUPLICATE,
}

sealed interface UnsavedStudyEvidenceWriteResult {
    data class Recorded(
        val sourceFactId: String,
        val disposition: RecordedStudyEvidenceDisposition,
    ) : UnsavedStudyEvidenceWriteResult

    data class PendingAttributionQueued(
        val sourceFactId: String,
        val reviewCaseId: String,
        val disposition: PendingAttributionDisposition,
    ) : UnsavedStudyEvidenceWriteResult

    data class Conflict(
        val sourceFactId: String,
    ) : UnsavedStudyEvidenceWriteResult

    data class StorageUnavailable(
        val sourceFactId: String,
    ) : UnsavedStudyEvidenceWriteResult

    data class Rejected(
        val sourceFactId: String,
    ) : UnsavedStudyEvidenceWriteResult
}

interface LearnerBoundLearningEvidencePort {
    val learnerId: String

    suspend fun submitSavedReview(
        command: SavedReviewEvidenceCommand,
    ): SavedReviewEvidenceWriteResult

    suspend fun recordUnsavedStudy(
        command: UnsavedStudyEvidenceCommand,
    ): UnsavedStudyEvidenceWriteResult
}

internal interface LearnerBoundLearningEvidenceAuthority {
    val learnerId: String

    suspend fun submitStudentReview(
        command: SubmitStudentReviewResponseCommand,
    ): StudentReviewTransitionResult

    fun authorizeKnowledge(
        proofs: List<VerifiedKnowledgeReferenceProof>,
    ): List<VerifiedEphemeralKnowledgeEvidence>

    suspend fun recordObservation(
        facts: LearningObservationFacts,
    ): TrustedLearningObservationResult

    suspend fun enqueuePendingOpenResponse(
        facts: PendingOpenResponseFacts,
    ): PendingOpenResponseResult

    suspend fun drainAuthorityRelay()
}

internal class ThreeAuthorityLearningEvidenceCoordinator(
    private val authority: LearnerBoundLearningEvidenceAuthority,
) : LearnerBoundLearningEvidencePort {
    override val learnerId: String = authority.learnerId

    override suspend fun submitSavedReview(
        command: SavedReviewEvidenceCommand,
    ): SavedReviewEvidenceWriteResult {
        val transition =
            try {
                authority.submitStudentReview(command.toStudentCommand())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IllegalArgumentException) {
                return SavedReviewEvidenceWriteResult.Rejected
            } catch (_: Exception) {
                return SavedReviewEvidenceWriteResult.StorageUnavailable
            }
        val saved =
            when (transition) {
                is StudentReviewTransitionResult.Applied ->
                    SavedReviewTransitionDisposition.APPLIED to
                        transition.session.sessionVersion
                is StudentReviewTransitionResult.Duplicate ->
                    SavedReviewTransitionDisposition.DUPLICATE to
                        transition.session.sessionVersion
                StudentReviewTransitionResult.ReloadRequired ->
                    return SavedReviewEvidenceWriteResult.ReloadRequired
            }
        val relayDelivery =
            try {
                authority.drainAuthorityRelay()
                AuthorityRelayDelivery.DELIVERED
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                AuthorityRelayDelivery.PENDING_RETRY
            }
        return SavedReviewEvidenceWriteResult.Saved(
            transition = saved.first,
            sessionVersion = saved.second,
            relayDelivery = relayDelivery,
        )
    }

    override suspend fun recordUnsavedStudy(
        command: UnsavedStudyEvidenceCommand,
    ): UnsavedStudyEvidenceWriteResult {
        val sourceFactId = command.stableSourceFactId()
        val context =
            try {
                command.toEphemeralContext(
                    authority.authorizeKnowledge(command.verifiedKnowledgeProofs),
                )
            } catch (_: Exception) {
                return UnsavedStudyEvidenceWriteResult.Rejected(sourceFactId)
            }
        val verified = command.response.toVerifiedResponse()
            ?: return enqueuePendingOpenResponse(command, context, sourceFactId)
        val observation = command.toObservation(context, verified, sourceFactId)
        val result =
            try {
                authority.recordObservation(observation)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return UnsavedStudyEvidenceWriteResult.StorageUnavailable(sourceFactId)
            }
        check(result.observationId == sourceFactId) {
            "Learner-mastery returned a mismatched ordinary observation identity"
        }
        if (result.disposition == TrustedLearningObservationDisposition.CONFLICT) {
            return UnsavedStudyEvidenceWriteResult.Conflict(sourceFactId)
        }
        return UnsavedStudyEvidenceWriteResult.Recorded(
            sourceFactId = sourceFactId,
            disposition = result.disposition.toPublicDisposition(),
        )
    }

    private suspend fun enqueuePendingOpenResponse(
        command: UnsavedStudyEvidenceCommand,
        context: EphemeralTutorProblemLearningContext,
        sourceFactId: String,
    ): UnsavedStudyEvidenceWriteResult {
        val facts =
            PendingOpenResponseFacts(
                sourceFactId = sourceFactId,
                submissionId = command.submissionId,
                subject = command.subject,
                presentationFingerprint = command.presentationFingerprint,
                context = context,
                responseCanonicalFingerprint =
                    command.response.responseCanonicalFingerprint,
                responsePolicyVersion = command.response.verificationPolicyVersion,
                attemptOrdinal = command.attemptOrdinal,
                hintCount = command.hintCount,
                answerWasRevealed = command.answerWasRevealed,
                elapsedDurationMillis = command.elapsedDurationMillis,
                occurredAtEpochMillis = command.occurredAtEpochMillis,
                attestedAtEpochMillis = command.occurredAtEpochMillis,
            )
        val result =
            try {
                authority.enqueuePendingOpenResponse(facts)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return UnsavedStudyEvidenceWriteResult.StorageUnavailable(sourceFactId)
            }
        check(result.sourceFactId == sourceFactId) {
            "Learner-mastery returned a mismatched pending source-fact identity"
        }
        return when (result.disposition) {
            PendingOpenResponseDisposition.QUEUED,
            PendingOpenResponseDisposition.DUPLICATE,
            ->
                UnsavedStudyEvidenceWriteResult.PendingAttributionQueued(
                    sourceFactId = sourceFactId,
                    reviewCaseId = checkNotNull(result.reviewCaseId),
                    disposition =
                        if (result.disposition == PendingOpenResponseDisposition.QUEUED) {
                            PendingAttributionDisposition.QUEUED
                        } else {
                            PendingAttributionDisposition.DUPLICATE
                        },
                )
            PendingOpenResponseDisposition.CONFLICT ->
                UnsavedStudyEvidenceWriteResult.Conflict(sourceFactId)
            PendingOpenResponseDisposition.REJECTED ->
                UnsavedStudyEvidenceWriteResult.Rejected(sourceFactId)
        }
    }

    internal companion object {
        fun assemble(
            studentMistakes: StudentMistakeRuntimeCapabilities,
            learnerMastery: LearnerMasteryRuntimeCapabilities,
            drainAuthorityRelay: suspend () -> Unit,
        ): ThreeAuthorityLearningEvidenceCoordinator {
            require(studentMistakes.learnerId == learnerMastery.learnerId) {
                "Learning-evidence authorities must share one learner scope"
            }
            return ThreeAuthorityLearningEvidenceCoordinator(
                RuntimeBoundLearningEvidenceAuthority(
                    studentMistakes = studentMistakes,
                    learnerMastery = learnerMastery,
                    drainAuthorityRelay = drainAuthorityRelay,
                ),
            )
        }
    }
}

private class RuntimeBoundLearningEvidenceAuthority(
    private val studentMistakes: StudentMistakeRuntimeCapabilities,
    private val learnerMastery: LearnerMasteryRuntimeCapabilities,
    private val drainAuthorityRelay: suspend () -> Unit,
) : LearnerBoundLearningEvidenceAuthority {
    override val learnerId: String = studentMistakes.learnerId

    init {
        require(learnerId == learnerMastery.learnerId) {
            "Learning-evidence authorities must share one learner scope"
        }
    }

    override suspend fun submitStudentReview(
        command: SubmitStudentReviewResponseCommand,
    ): StudentReviewTransitionResult =
        studentMistakes.reviewSessions.submitResponse(command)

    override fun authorizeKnowledge(
        proofs: List<VerifiedKnowledgeReferenceProof>,
    ): List<VerifiedEphemeralKnowledgeEvidence> =
        proofs.map(learnerMastery.knowledgeEvidenceAuthorizer::authorize)

    override suspend fun recordObservation(
        facts: LearningObservationFacts,
    ): TrustedLearningObservationResult =
        learnerMastery.observationSink.record(facts)

    override suspend fun enqueuePendingOpenResponse(
        facts: PendingOpenResponseFacts,
    ): PendingOpenResponseResult =
        learnerMastery.pendingOpenResponseSink.enqueue(facts)

    override suspend fun drainAuthorityRelay() {
        drainAuthorityRelay.invoke()
    }
}

private data class VerifiedOrdinaryStudyResponse(
    val source: TrustedLearningObservationSource,
    val responseForm: TrustedLearningResponseForm,
    val responseCanonicalFingerprint: String,
    val verificationPolicyVersion: String,
    val rubricCanonicalFingerprint: String?,
    val isCorrect: Boolean,
)

private fun OrdinaryStudyResponse.toVerifiedResponse(): VerifiedOrdinaryStudyResponse? =
    when (this) {
        is OrdinaryStudyResponse.Choice ->
            verified(
                source = TrustedLearningObservationSource.TUTOR_CHOICE,
                form = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                isCorrect = isCorrect,
            )
        is OrdinaryStudyResponse.Numeric ->
            verified(
                source = TrustedLearningObservationSource.TUTOR_FREE_RESPONSE,
                form = TrustedLearningResponseForm.FREE_RESPONSE,
                isCorrect = isCorrect,
            )
        is OrdinaryStudyResponse.VisualTarget ->
            verified(
                source = TrustedLearningObservationSource.TUTOR_VISUAL_TARGET,
                form = TrustedLearningResponseForm.VISUAL_TARGET,
                isCorrect = isCorrect,
            )
        is OrdinaryStudyResponse.LocallyVerifiedFreeResponse ->
            VerifiedOrdinaryStudyResponse(
                source = TrustedLearningObservationSource.TUTOR_FREE_RESPONSE,
                responseForm = TrustedLearningResponseForm.FREE_RESPONSE,
                responseCanonicalFingerprint = responseCanonicalFingerprint,
                verificationPolicyVersion = verificationPolicyVersion,
                rubricCanonicalFingerprint = rubricCanonicalFingerprint,
                isCorrect = isCorrect,
            )
        is OrdinaryStudyResponse.UnverifiedFreeResponse -> null
    }

private fun OrdinaryStudyResponse.verified(
    source: TrustedLearningObservationSource,
    form: TrustedLearningResponseForm,
    isCorrect: Boolean,
): VerifiedOrdinaryStudyResponse =
    VerifiedOrdinaryStudyResponse(
        source = source,
        responseForm = form,
        responseCanonicalFingerprint = responseCanonicalFingerprint,
        verificationPolicyVersion = verificationPolicyVersion,
        rubricCanonicalFingerprint = null,
        isCorrect = isCorrect,
    )

private fun UnsavedStudyEvidenceCommand.toEphemeralContext(
    verifiedKnowledge: List<VerifiedEphemeralKnowledgeEvidence>,
): EphemeralTutorProblemLearningContext =
    EphemeralTutorProblemLearningContext(
        problemFingerprint = problemFingerprint,
        problemFamilyFingerprint = problemFamilyFingerprint,
        tutorTurnReferenceId = interactionReferenceId,
        submissionEvidenceFingerprint = responseEvidenceFingerprint(),
        attributionModelVersion = attributionPolicyVersion,
        verifiedKnowledgeEvidence = verifiedKnowledge,
    )

private fun UnsavedStudyEvidenceCommand.toObservation(
    context: EphemeralTutorProblemLearningContext,
    verified: VerifiedOrdinaryStudyResponse,
    sourceFactId: String,
): LearningObservationFacts =
    LearningObservationFacts(
        observationId = sourceFactId,
        subject = subject,
        source = verified.source,
        sourceReferenceId = submissionId,
        presentationFingerprint = presentationFingerprint,
        context = context,
        responseForm = verified.responseForm,
        answerWasCorrect = verified.isCorrect,
        learnerReportedStuck = false,
        answerWasViewed = false,
        independentlyAnswered =
            attemptOrdinal == 1 &&
                hintCount == 0 &&
                !answerWasRevealed,
        hintCount = hintCount,
        answerRevealed = answerWasRevealed,
        retryCount = attemptOrdinal - 1,
        elapsedDurationMillis = elapsedDurationMillis,
        verification = TrustedLearningVerification.DETERMINISTIC_RUBRIC,
        evidenceCanonicalFingerprint =
            payloadFingerprint(
                context = context,
                source = verified.source,
                responseForm = verified.responseForm,
                correctness = verified.isCorrect,
                rubricCanonicalFingerprint = verified.rubricCanonicalFingerprint,
            ),
        occurredAtEpochMillis = occurredAtEpochMillis,
        attestedAtEpochMillis = occurredAtEpochMillis,
    )

private fun UnsavedStudyEvidenceCommand.payloadFingerprint(
    context: EphemeralTutorProblemLearningContext,
    source: TrustedLearningObservationSource,
    responseForm: TrustedLearningResponseForm,
    correctness: Boolean?,
    rubricCanonicalFingerprint: String?,
): String =
    CanonicalSha256("core-data-ordinary-study-evidence-v2")
        .field("sourceFactId", stableSourceFactId())
        .field("submissionId", submissionId)
        .field("subject", subject.name)
        .field("source", source.name)
        .field("responseForm", responseForm.name)
        .field("presentationFingerprint", presentationFingerprint)
        .field("context", context.canonicalFingerprint)
        .field("responseCanonicalFingerprint", response.responseCanonicalFingerprint)
        .field("responsePolicyVersion", response.verificationPolicyVersion)
        .nullableField("rubricCanonicalFingerprint", rubricCanonicalFingerprint)
        .nullableField("correctness", correctness?.toString())
        .field("attemptOrdinal", attemptOrdinal)
        .field("hintCount", hintCount)
        .field("answerWasRevealed", answerWasRevealed)
        .nullableField("elapsedDurationMillis", elapsedDurationMillis?.toString())
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .finish()

private fun UnsavedStudyEvidenceCommand.responseEvidenceFingerprint(): String =
    CanonicalSha256("core-data-ordinary-study-response-evidence-v1")
        .field("responseCanonicalFingerprint", response.responseCanonicalFingerprint)
        .field("responsePolicyVersion", response.verificationPolicyVersion)
        .finish()

private fun UnsavedStudyEvidenceCommand.stableSourceFactId(): String =
    "ordinary-study:${
        CanonicalSha256("core-data-ordinary-study-source-fact-id-v1")
            .field("subject", subject.name)
            .field("interactionReferenceId", interactionReferenceId)
            .field("submissionId", submissionId)
            .finish()
    }"

private fun SavedReviewEvidenceCommand.toStudentCommand(): SubmitStudentReviewResponseCommand =
    SubmitStudentReviewResponseCommand(
        transitionId = transitionId,
        sessionId = sessionId,
        queueItemId = queueItemId,
        expectedSessionVersion = expectedSessionVersion,
        presentationId = presentationId,
        observationId = observationId,
        submissionId = submissionId,
        responseForm =
            when (responseForm) {
                StudyEvidenceResponseForm.CHOICE -> StudentReviewResponseForm.CHOICE
                StudyEvidenceResponseForm.NUMERIC -> StudentReviewResponseForm.NUMERIC
                StudyEvidenceResponseForm.VISUAL_TARGET ->
                    StudentReviewResponseForm.VISUAL_TARGET
            },
        responseCanonicalFingerprint = responseCanonicalFingerprint,
        verificationOutcome =
            when (verificationOutcome) {
                StudyEvidenceVerificationOutcome.CORRECT ->
                    StudentReviewVerificationOutcome.CORRECT
                StudyEvidenceVerificationOutcome.INCORRECT ->
                    StudentReviewVerificationOutcome.INCORRECT
            },
        attemptOrdinal = attemptOrdinal,
        hintCount = hintCount,
        answerWasRevealed = answerWasRevealed,
        verificationPolicyVersion = verificationPolicyVersion,
        elapsedDurationMillis = elapsedDurationMillis,
        schedule =
            StudentReviewScheduleUpdate(
                nextAvailableAtEpochMillis = schedule.nextAvailableAtEpochMillis,
                nextDueAtEpochMillis = schedule.nextDueAtEpochMillis,
                schedulingPolicyVersion = schedule.schedulingPolicyVersion,
            ),
        capturedAtEpochMillis = capturedAtEpochMillis,
    )

private fun TrustedLearningObservationDisposition.toPublicDisposition():
    RecordedStudyEvidenceDisposition =
    when (this) {
        TrustedLearningObservationDisposition.ADMITTED ->
            RecordedStudyEvidenceDisposition.ADMITTED
        TrustedLearningObservationDisposition.FACT_STORED ->
            RecordedStudyEvidenceDisposition.FACT_STORED
        TrustedLearningObservationDisposition.DUPLICATE ->
            RecordedStudyEvidenceDisposition.DUPLICATE
        TrustedLearningObservationDisposition.INERT ->
            RecordedStudyEvidenceDisposition.INERT
        TrustedLearningObservationDisposition.CONFLICT ->
            error("A conflicting observation has no recorded disposition")
    }

private fun OrdinaryStudyResponse.requireValid() {
    requireEvidenceFingerprint(responseCanonicalFingerprint, "Study response fingerprint")
    requireEvidenceVersion(verificationPolicyVersion, "Study verification-policy version")
    if (this is OrdinaryStudyResponse.LocallyVerifiedFreeResponse) {
        requireEvidenceFingerprint(rubricCanonicalFingerprint, "Study rubric fingerprint")
    }
}

private fun requireEvidenceIdentity(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_IDENTITY_CHARS &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value of at most $MAX_IDENTITY_CHARS characters"
    }
}

private fun requireEvidenceVersion(
    value: String,
    label: String,
) {
    requireEvidenceIdentity(value, label)
    require(value.length <= MAX_VERSION_CHARS && VERSION_PATTERN.matches(value)) {
        "$label contains unsupported characters or exceeds $MAX_VERSION_CHARS characters"
    }
}

private fun requireEvidenceFingerprint(
    value: String,
    label: String,
) {
    require(FINGERPRINT_PATTERN.matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private const val MAX_IDENTITY_CHARS = 256
private const val MAX_VERSION_CHARS = 128
private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:+-]*")
private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
