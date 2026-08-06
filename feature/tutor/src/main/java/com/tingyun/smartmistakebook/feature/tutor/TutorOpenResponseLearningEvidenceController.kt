package com.tingyun.smartmistakebook.feature.tutor

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import java.util.Collections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Current user-visible Tutor state for one real question.
 *
 * This contains no knowledge attribution, correctness, evidence weight, or storage capability.
 * The question snapshot is copied so a mutable caller list cannot change an in-flight submission.
 */
class TutorOpenResponseLearningContext(
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    questionDocument: QuestionDocument,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val evidenceRequestId: String,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
) {
    val questionDocument: QuestionDocument =
        questionDocument.copy(
            blocks = Collections.unmodifiableList(questionDocument.blocks.toList()),
        )

    val identity: TutorOpenResponseLearningContextIdentity =
        TutorOpenResponseLearningContextIdentity(
            learnerId = learnerId,
            conversationId = conversationId,
            conversationGeneration = conversationGeneration,
            conversationStateVersion = conversationStateVersion,
            questionDocumentId = questionDocumentId,
            questionRevisionNumber = questionRevisionNumber,
            subject = subject,
            questionFingerprint =
                OpenResponseEvaluationTaskFingerprints.question(this.questionDocument),
            presentationFingerprint = presentationFingerprint,
            problemFingerprint = problemFingerprint,
            problemFamilyFingerprint = problemFamilyFingerprint,
            explanationMode = explanationMode,
            modeVersion = modeVersion,
            turnReferenceId = turnReferenceId,
            turnOrdinal = turnOrdinal,
            turnGeneration = turnGeneration,
            evidenceRequestId = evidenceRequestId,
            attemptOrdinal = attemptOrdinal,
            hintCount = hintCount,
            answerWasRevealed = answerWasRevealed,
            requestVersion = requestVersion,
        )

    init {
        require(questionDocumentId == this.questionDocument.id) {
            "Open-response question id does not match its document"
        }
    }
}

data class TutorOpenResponseLearningContextIdentity(
    val learnerId: String,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val questionRevisionNumber: Int,
    val subject: SubjectKind,
    val questionFingerprint: String,
    val presentationFingerprint: String,
    val problemFingerprint: String,
    val problemFamilyFingerprint: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val turnReferenceId: String,
    val turnOrdinal: Int,
    val turnGeneration: Long,
    val evidenceRequestId: String,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
) {
    val canonicalFingerprint: String =
        CanonicalSha256("feature-tutor-open-response-learning-context-v1")
            .field("learnerId", learnerId)
            .field("conversationId", conversationId)
            .field("conversationGeneration", conversationGeneration)
            .field("conversationStateVersion", conversationStateVersion)
            .field("questionDocumentId", questionDocumentId)
            .field("questionRevisionNumber", questionRevisionNumber)
            .field("subject", subject.name)
            .field("questionFingerprint", questionFingerprint)
            .field("presentationFingerprint", presentationFingerprint)
            .field("problemFingerprint", problemFingerprint)
            .field("problemFamilyFingerprint", problemFamilyFingerprint)
            .field("explanationMode", explanationMode.name)
            .field("modeVersion", modeVersion)
            .field("turnReferenceId", turnReferenceId)
            .field("turnOrdinal", turnOrdinal)
            .field("turnGeneration", turnGeneration)
            .field("evidenceRequestId", evidenceRequestId)
            .field("attemptOrdinal", attemptOrdinal)
            .field("hintCount", hintCount)
            .field("answerWasRevealed", answerWasRevealed)
            .field("requestVersion", requestVersion)
            .finish()

    init {
        requireTutorOpenResponseIdentity(learnerId, "Learner id")
        requireTutorOpenResponseIdentity(conversationId, "Conversation id")
        requireTutorOpenResponseIdentity(questionDocumentId, "Question document id")
        requireTutorOpenResponseIdentity(turnReferenceId, "Turn reference id")
        requireTutorOpenResponseIdentity(evidenceRequestId, "Evidence request id")
        require(conversationGeneration >= 0L)
        require(conversationStateVersion >= 0L)
        require(questionRevisionNumber > 0)
        require(subject != SubjectKind.GENERAL)
        requireTutorOpenResponseFingerprint(questionFingerprint, "Question fingerprint")
        requireTutorOpenResponseFingerprint(presentationFingerprint, "Presentation fingerprint")
        requireTutorOpenResponseFingerprint(problemFingerprint, "Problem fingerprint")
        requireTutorOpenResponseFingerprint(problemFamilyFingerprint, "Problem-family fingerprint")
        require(modeVersion >= 0L)
        require(turnOrdinal >= 0)
        require(turnGeneration >= 0L)
        require(attemptOrdinal in 1..MAX_ATTEMPT_ORDINAL)
        require(hintCount in 0..MAX_HINT_COUNT)
        require(requestVersion >= 0L)
    }

    private companion object {
        const val MAX_ATTEMPT_ORDINAL = 17
        const val MAX_HINT_COUNT = 32
    }
}

enum class TutorOpenResponseMessageSource {
    GUIDED_ASK_FREE_RESPONSE,
    DIRECT_CONVERSATION,
}

/**
 * The user message has already been added to the conversation before this command is dispatched.
 */
class TutorOpenResponseLearningMessage(
    val context: TutorOpenResponseLearningContext,
    val submissionId: String,
    val sourceMessageId: String,
    val source: TutorOpenResponseMessageSource,
    val rawAnswer: String,
    val elapsedDurationMillis: Long?,
    val occurredAtEpochMillis: Long,
) {
    val answerFingerprint: String = OpenResponseEvaluationTaskFingerprints.answer(rawAnswer)

    init {
        requireTutorOpenResponseIdentity(submissionId, "Submission id")
        requireTutorOpenResponseIdentity(sourceMessageId, "Source message id")
        require(rawAnswer.isNotBlank()) { "Open response must not be blank" }
        require(
            elapsedDurationMillis == null ||
                elapsedDurationMillis in 0L..MAX_ELAPSED_DURATION_MILLIS,
        )
        require(occurredAtEpochMillis >= 0L)
        require(
            source != TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE ||
                context.explanationMode == TutorExplanationMode.GUIDED,
        ) {
            "Guided free response requires the guided mode"
        }
        require(
            source != TutorOpenResponseMessageSource.DIRECT_CONVERSATION ||
                context.explanationMode == TutorExplanationMode.DIRECT,
        ) {
            "Direct conversation response requires the direct mode"
        }
    }

    private companion object {
        const val MAX_ELAPSED_DURATION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}

sealed interface TutorOpenResponseEvidenceAdmission {
    data class GuidedFreeResponse(
        val evidenceRequestId: String,
    ) : TutorOpenResponseEvidenceAdmission

    data class DirectSpecificCurrentQuestionGap(
        val intentFingerprint: String,
    ) : TutorOpenResponseEvidenceAdmission {
        init {
            requireTutorOpenResponseFingerprint(intentFingerprint, "Direct intent fingerprint")
        }
    }
}

data class AdmittedTutorOpenResponseLearningEvidence(
    val message: TutorOpenResponseLearningMessage,
    val admission: TutorOpenResponseEvidenceAdmission,
)

data class UnresolvedTutorOpenResponseLearningIntent(
    val message: TutorOpenResponseLearningMessage,
    val classificationRequestId: String,
) {
    init {
        requireTutorOpenResponseIdentity(classificationRequestId, "Classification request id")
    }
}

/**
 * A live host lease. App/core:data assembly must re-enter [requireCurrent] whenever it converts
 * this feature command into a trusted source fact, model task, or weak-candidate handoff.
 */
class TutorOpenResponseLearningSubmissionLease internal constructor(
    val contextFingerprint: String,
    val answerFingerprint: String,
    private val isCurrentBlock: () -> Boolean,
) {
    fun isCurrent(): Boolean = isCurrentBlock()

    fun requireCurrent() {
        check(isCurrent()) {
            "Open-response learning submission is no longer current"
        }
    }
}

enum class TutorOpenResponseLearningSubmissionReceipt {
    PENDING,
    DUPLICATE,
    RETAINED_FOR_RETRY,
    REJECTED,
}

/**
 * Host-issued narrow boundary. Its app implementation owns the trusted core:data coordinator,
 * current-session authorization, knowledge proofs, and operational source-body storage.
 */
interface OpenResponseLearningEvidenceSubmissionPort {
    val learnerId: String

    suspend fun submitAdmitted(
        evidence: AdmittedTutorOpenResponseLearningEvidence,
        lease: TutorOpenResponseLearningSubmissionLease,
    ): TutorOpenResponseLearningSubmissionReceipt

    /**
     * An unavailable intent classifier must not turn a potentially useful message into mastery
     * evidence. It is retained operationally for a later intent decision instead.
     */
    suspend fun retainUnresolvedIntent(
        intent: UnresolvedTutorOpenResponseLearningIntent,
        lease: TutorOpenResponseLearningSubmissionLease,
    ): TutorOpenResponseLearningSubmissionReceipt
}

sealed interface DirectOpenResponseLearningIntent {
    data class SpecificCurrentQuestionGap(
        val intentFingerprint: String,
    ) : DirectOpenResponseLearningIntent {
        init {
            requireTutorOpenResponseFingerprint(intentFingerprint, "Direct intent fingerprint")
        }
    }

    /** Includes unrelated messages, emotional conversation, and vague learning remarks. */
    data object NotSpecificLearningEvidence : DirectOpenResponseLearningIntent

    /** The intent task timed out or could not safely decide; the source remains retryable. */
    data class Unknown(
        val classificationRequestId: String,
    ) : DirectOpenResponseLearningIntent {
        init {
            requireTutorOpenResponseIdentity(classificationRequestId, "Classification request id")
        }
    }
}

/**
 * Model-backed intent boundary. Feature code receives only the decision category and cannot
 * infer knowledge points, correctness, mastery, or evidence mass.
 */
fun interface DirectOpenResponseLearningIntentClassifier {
    suspend fun classify(
        message: TutorOpenResponseLearningMessage,
        lease: TutorOpenResponseLearningSubmissionLease,
    ): DirectOpenResponseLearningIntent
}

sealed interface TutorOpenResponseLearningDispatch {
    data class Scheduled(val operationId: String) : TutorOpenResponseLearningDispatch

    data object NotCurrent : TutorOpenResponseLearningDispatch
}

/**
 * Non-blocking controller for evidence side effects after a message is already visible.
 *
 * It has no UI result callback or observable evaluation state. Switching question/mode/turn
 * rotates the live lease and cancels pending feature work; the trusted lower layer still owns any
 * source fact it already committed.
 */
class TutorOpenResponseLearningEvidenceController(
    parentScope: CoroutineScope,
    private val submissions: OpenResponseLearningEvidenceSubmissionPort,
    private val directIntentClassifier: DirectOpenResponseLearningIntentClassifier,
) : AutoCloseable {
    private val lock = Any()
    private val parentContext = parentScope.coroutineContext
    private var ownerVersion = 0L
    private var current: TutorOpenResponseLearningContextIdentity? = null
    private var generationJob: Job = SupervisorJob(parentContext[Job])
    private var closed = false

    fun bindCurrent(context: TutorOpenResponseLearningContext) {
        require(context.learnerId == submissions.learnerId) {
            "Tutor open-response port crossed its learner scope"
        }
        synchronized(lock) {
            check(!closed) { "Tutor open-response controller is closed" }
            if (current == context.identity) return
            rotateLocked(context.identity)
        }
    }

    fun clearCurrent() {
        synchronized(lock) {
            rotateLocked(null)
        }
    }

    fun submitVisibleMessage(
        message: TutorOpenResponseLearningMessage,
    ): TutorOpenResponseLearningDispatch {
        val ticket: SubmissionTicket
        val worker: CoroutineScope
        synchronized(lock) {
            if (
                closed ||
                current != message.context.identity ||
                submissions.learnerId != message.context.learnerId
            ) {
                return TutorOpenResponseLearningDispatch.NotCurrent
            }
            rotateLocked(message.context.identity)
            ticket =
                SubmissionTicket(
                    context = message.context.identity,
                    answerFingerprint = message.answerFingerprint,
                    ownerVersion = ownerVersion,
                )
            worker = CoroutineScope(parentContext.minusKey(Job) + generationJob)
        }
        val operationId =
            CanonicalSha256("feature-tutor-open-response-dispatch-v1")
                .field("context", ticket.context.canonicalFingerprint)
                .field("answer", ticket.answerFingerprint)
                .field("submissionId", message.submissionId)
                .field("sourceMessageId", message.sourceMessageId)
                .finish()
        val lease =
            TutorOpenResponseLearningSubmissionLease(
                contextFingerprint = ticket.context.canonicalFingerprint,
                answerFingerprint = ticket.answerFingerprint,
                isCurrentBlock = { isCurrent(ticket) },
            )
        worker.launch {
            yield()
            try {
                process(message, lease)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Evidence is a background side effect. The host port owns durable retry state.
            }
        }
        return TutorOpenResponseLearningDispatch.Scheduled(operationId)
    }

    private suspend fun process(
        message: TutorOpenResponseLearningMessage,
        lease: TutorOpenResponseLearningSubmissionLease,
    ) {
        if (!lease.isCurrent()) return
        when (message.source) {
            TutorOpenResponseMessageSource.GUIDED_ASK_FREE_RESPONSE -> {
                submissions.submitAdmitted(
                    evidence =
                        AdmittedTutorOpenResponseLearningEvidence(
                            message = message,
                            admission =
                                TutorOpenResponseEvidenceAdmission.GuidedFreeResponse(
                                    message.context.evidenceRequestId,
                                ),
                        ),
                    lease = lease,
                )
            }
            TutorOpenResponseMessageSource.DIRECT_CONVERSATION -> {
                val intent =
                    try {
                        directIntentClassifier.classify(message, lease)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        DirectOpenResponseLearningIntent.Unknown(
                            directIntentClassificationRequestId(message),
                        )
                    }
                when (intent) {
                    is DirectOpenResponseLearningIntent.SpecificCurrentQuestionGap -> {
                        if (!lease.isCurrent()) return
                        submissions.submitAdmitted(
                            evidence =
                                AdmittedTutorOpenResponseLearningEvidence(
                                    message = message,
                                    admission =
                                        TutorOpenResponseEvidenceAdmission
                                            .DirectSpecificCurrentQuestionGap(
                                                intent.intentFingerprint,
                                            ),
                                ),
                            lease = lease,
                        )
                    }
                    DirectOpenResponseLearningIntent.NotSpecificLearningEvidence -> Unit
                    is DirectOpenResponseLearningIntent.Unknown -> {
                        if (!lease.isCurrent()) return
                        submissions.retainUnresolvedIntent(
                            intent =
                                UnresolvedTutorOpenResponseLearningIntent(
                                    message = message,
                                    classificationRequestId = intent.classificationRequestId,
                                ),
                            lease = lease,
                        )
                    }
                }
            }
        }
    }

    private fun isCurrent(ticket: SubmissionTicket): Boolean =
        synchronized(lock) {
            generationJob.isActive &&
                ownerVersion == ticket.ownerVersion &&
                current == ticket.context
        }

    private fun rotateLocked(
        updated: TutorOpenResponseLearningContextIdentity?,
    ) {
        generationJob.cancel()
        ownerVersion += 1
        current = updated
        generationJob = SupervisorJob(parentContext[Job])
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            current = null
            generationJob.cancel()
            ownerVersion += 1
        }
    }

    private data class SubmissionTicket(
        val context: TutorOpenResponseLearningContextIdentity,
        val answerFingerprint: String,
        val ownerVersion: Long,
    )
}

private fun directIntentClassificationRequestId(
    message: TutorOpenResponseLearningMessage,
): String =
    "direct-intent:${
        CanonicalSha256("feature-tutor-direct-open-response-intent-v1")
            .field("context", message.context.identity.canonicalFingerprint)
            .field("submissionId", message.submissionId)
            .field("sourceMessageId", message.sourceMessageId)
            .field("answer", message.answerFingerprint)
            .finish()
    }"

private fun requireTutorOpenResponseIdentity(
    value: String,
    label: String,
) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_IDENTITY_CHARS &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed non-blank value"
    }
}

private fun requireTutorOpenResponseFingerprint(
    value: String,
    label: String,
) {
    require(FINGERPRINT_PATTERN.matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private const val MAX_IDENTITY_CHARS = 256
private val FINGERPRINT_PATTERN = Regex("[0-9a-f]{64}")
