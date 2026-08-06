package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProof
import com.tingyun.smartmistakebook.core.model.TutorVisualScene
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationInputPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * UI-safe view of the trusted current-question owner.
 *
 * The port deliberately exposes neither persistence identifiers used by the learning ledger nor
 * knowledge proofs, activation fields, model output, or database capabilities. A presentation
 * token is only an opaque correlation value; every later action must be re-authorized by the host.
 */
interface TutorCurrentSessionHostPort {
    fun observe(sessionId: String): Flow<TutorCurrentSessionHostSnapshot?>

    /**
     * Only locally constrained content is returned here. Implementations must re-read the current
     * question, model task, policy epoch, and host-work fingerprint before emitting a value.
     */
    fun observePresentation(sessionId: String): Flow<TutorCurrentSessionPresentation?> =
        flowOf(null)

    /**
     * Resolves one immutable saved-mistake revision inside the trusted Host. The caller supplies
     * only the stable local locator and expected revision; question content never crosses this
     * command boundary and the Host must re-read the exact persisted revision before accepting it.
     */
    suspend fun prepareSavedMistake(
        reference: TutorCurrentSessionSavedMistakeReference,
    ): TutorCurrentSessionQuestionPreparationResult =
        TutorCurrentSessionQuestionPreparationResult.Unavailable(
            TutorCurrentSessionQuestionPreparationBlocker.UNSUPPORTED,
        )

    /** Accepts only an identifier; the host re-reads and verifies the completed task itself. */
    suspend fun activate(
        sessionId: String,
        modelTaskRequestId: String,
    ): TutorCurrentSessionHostResult

    /** Rebuilds the current owner exclusively from durable local records. */
    suspend fun resume(sessionId: String): TutorCurrentSessionHostResult

    /** Revokes the exact current host generation. Navigation alone must not call this operation. */
    suspend fun revoke(
        sessionId: String,
        reason: TutorCurrentSessionRevocationReason,
    ): TutorCurrentSessionHostResult

    /**
     * Establishes the local policy epoch that a subsequently-created model task must repeat.
     * A model request can remove authority, but it can never create or advance this authority.
     */
    suspend fun updatePolicy(
        command: TutorCurrentSessionPolicyUpdate,
    ): TutorCurrentSessionPolicyResult = TutorCurrentSessionPolicyResult.Unavailable

    /** Restores the exact durable UI-owned policy without advancing or overwriting any epoch. */
    suspend fun currentPolicy(
        sessionId: String,
    ): TutorCurrentSessionPolicyResult = TutorCurrentSessionPolicyResult.Unavailable

    /**
     * Commits one Host-issued hint slot only after its content is stably visible. Model generation,
     * preloading, and retries must never call this method. UI can replay the opaque slot token but
     * cannot mint an idempotency key or otherwise describe a different hint.
     */
    suspend fun recordHintShown(
        action: TutorCurrentSessionHintShownAction,
    ): TutorCurrentSessionHintShownResult = TutorCurrentSessionHintShownResult.Rejected(
        TutorCurrentSessionActionBlocker.NOT_CURRENT,
    )

    /**
     * Handles one visible choice using only opaque presentation identity and the choice id. The
     * Host decides whether independently verified evidence exists; model-authored answer keys alone
     * may produce presentation feedback but must not produce a learning write.
     */
    suspend fun submitChoice(
        action: TutorCurrentSessionChoiceAction,
    ): TutorCurrentSessionChoiceActionResult = TutorCurrentSessionChoiceActionResult.Rejected(
        TutorCurrentSessionActionBlocker.NOT_CURRENT,
    )

    /**
     * Submits one guided free response through an opaque, one-shot Host capability. The Host
     * re-authorizes the exact current question and may only create revisable weak evidence.
     */
    suspend fun submitFreeResponse(
        action: TutorCurrentSessionFreeResponseAction,
    ): TutorCurrentSessionFreeResponseActionResult =
        TutorCurrentSessionFreeResponseActionResult.Rejected(
            TutorCurrentSessionActionBlocker.NOT_CURRENT,
        )

    /**
     * Retries an encrypted pending answer without asking UI to retain or resubmit the answer.
     * The Host re-authorizes the opaque token before it decrypts the durable outbox row.
     */
    suspend fun retryFreeResponse(
        action: TutorCurrentSessionFreeResponseRetryAction,
    ): TutorCurrentSessionFreeResponseActionResult =
        TutorCurrentSessionFreeResponseActionResult.Rejected(
            TutorCurrentSessionActionBlocker.NOT_CURRENT,
        )

    /**
     * Asks the Host to re-read the exact validated scene and bind one real renderer hit to the
     * current question, step, policy epoch, and independently verified target rule.
     */
    suspend fun prepareVisualTarget(
        action: TutorCurrentSessionVisualTargetPreparation,
    ): TutorCurrentSessionVisualTargetPreparationResult =
        TutorCurrentSessionVisualTargetPreparationResult.Rejected(
            TutorCurrentSessionActionBlocker.INTERACTION_UNAVAILABLE,
        )

    /** UI submits only the opaque Host token and the target selected by the renderer. */
    suspend fun submitVisualTarget(
        action: TutorCurrentSessionVisualTargetAction,
    ): TutorCurrentSessionVisualTargetActionResult =
        TutorCurrentSessionVisualTargetActionResult.Rejected(
            TutorCurrentSessionActionBlocker.NOT_CURRENT,
        )
}

data class TutorCurrentSessionSavedMistakeReference(
    val errorBookEntryId: String,
    val problemId: String,
    val problemRevisionId: String,
    val expectedRevisionNumber: Int,
) {
    init {
        require(errorBookEntryId.isValidTutorHostId())
        require(problemId.isValidTutorHostId())
        require(problemRevisionId.isValidTutorHostId())
        require(expectedRevisionNumber > 0)
    }
}

data class TutorCurrentSessionPreparedQuestion(
    val sessionId: String,
    val questionRevisionNumber: Int,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(questionRevisionNumber > 0)
    }
}

enum class TutorCurrentSessionQuestionPreparationBlocker {
    UNSUPPORTED,
    NOT_FOUND,
    REVISION_MISMATCH,
}

sealed interface TutorCurrentSessionQuestionPreparationResult {
    data class Ready(
        val question: TutorCurrentSessionPreparedQuestion,
    ) : TutorCurrentSessionQuestionPreparationResult

    data class Unavailable(
        val blocker: TutorCurrentSessionQuestionPreparationBlocker,
    ) : TutorCurrentSessionQuestionPreparationResult
}

data class TutorCurrentSessionPolicyUpdate(
    val sessionId: String,
    val explanationMode: TutorExplanationMode,
    val learningWritesAllowed: Boolean,
    val visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
) {
    init {
        require(sessionId.isValidTutorHostId())
    }
}

data class TutorCurrentSessionPolicySnapshot(
    val sessionId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritesAllowed: Boolean,
    val learningWritePermissionVersion: Long,
    val visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    val visualIntentVersion: Long = 0L,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(
            modeVersion >= 0L && learningWritePermissionVersion >= 0L && visualIntentVersion >= 0L,
        )
    }
}

sealed interface TutorCurrentSessionPolicyResult {
    data class Current(
        val snapshot: TutorCurrentSessionPolicySnapshot,
    ) : TutorCurrentSessionPolicyResult

    data object Unavailable : TutorCurrentSessionPolicyResult
}

data class TutorCurrentSessionHintShownAction(
    val sessionId: String,
    val presentationToken: String,
    /** Opaque, stable identity issued by the Host for this one current-question hint. */
    val slotToken: String,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(presentationToken.isLowerSha256())
        require(slotToken.isLowerSha256())
    }
}

sealed interface TutorCurrentSessionHintShownResult {
    data object Recorded : TutorCurrentSessionHintShownResult
    data object Duplicate : TutorCurrentSessionHintShownResult
    data class Rejected(
        val blocker: TutorCurrentSessionActionBlocker,
    ) : TutorCurrentSessionHintShownResult
}

enum class TutorCurrentSessionTextKind {
    OPENING,
    SOLUTION,
    ALTERNATE_METHOD,
    DIFFICULTY,
}

data class TutorCurrentSessionText(
    val kind: TutorCurrentSessionTextKind,
    val markdown: String,
) {
    init {
        require(markdown.isNotBlank())
    }
}

data class TutorCurrentSessionChoice(
    val choiceId: String,
    val labelMarkdown: String,
) {
    init {
        require(choiceId.isValidTutorHostId())
        require(labelMarkdown.isNotBlank())
    }
}

sealed interface TutorCurrentSessionInteraction {
    val promptMarkdown: String

    data class Choices(
        override val promptMarkdown: String,
        val choices: List<TutorCurrentSessionChoice>,
    ) : TutorCurrentSessionInteraction {
        init {
            require(promptMarkdown.isNotBlank())
            require(choices.size in 2..4)
            require(choices.map(TutorCurrentSessionChoice::choiceId).distinct().size == choices.size)
        }
    }

    data class FreeResponse(
        override val promptMarkdown: String,
        val actionToken: String,
        /** User-facing lifecycle only; persistence and lease details stay inside the trusted Host. */
        val submissionStatus: TutorCurrentSessionFreeResponseStatus =
            TutorCurrentSessionFreeResponseStatus.READY,
    ) : TutorCurrentSessionInteraction {
        init {
            require(promptMarkdown.isNotBlank())
            require(actionToken.isLowerSha256())
        }
    }

    data class VisualTarget(
        override val promptMarkdown: String,
    ) : TutorCurrentSessionInteraction {
        init {
            require(promptMarkdown.isNotBlank())
        }
    }
}

enum class TutorCurrentSessionFreeResponseStatus {
    READY,
    SENDING,
    COMPLETED,
    RETRY_AVAILABLE,
    UNAVAILABLE,
}

enum class TutorCurrentSessionHintStatus {
    AVAILABLE,
    SHOWN,
}

/** A non-scoring Host-issued hint, independent of the current answer interaction. */
data class TutorCurrentSessionHint(
    val markdown: String,
    val slotToken: String,
    val status: TutorCurrentSessionHintStatus,
) {
    init {
        require(markdown == markdown.trim() && markdown.isNotBlank())
        require(slotToken.isLowerSha256())
    }
}

/** UI DTO with no answer key, feedback key, evidence id, knowledge id, or persistence id. */
data class TutorCurrentSessionPresentation(
    val sessionId: String,
    val presentationToken: String,
    val explanationMode: TutorExplanationMode,
    val learningWritesAllowed: Boolean,
    val visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    val visualIntentVersion: Long = 0L,
    val text: List<TutorCurrentSessionText>,
    val interaction: TutorCurrentSessionInteraction?,
    val visualScene: TutorVisualScene?,
    val hint: TutorCurrentSessionHint? = null,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(presentationToken.isLowerSha256())
        require(visualIntentVersion >= 0L)
        require(text.isNotEmpty())
        require(
            interaction == null || explanationMode == TutorExplanationMode.GUIDED,
        )
        require(hint == null || explanationMode == TutorExplanationMode.GUIDED)
        require(hint == null || text.none { it.kind == TutorCurrentSessionTextKind.SOLUTION }) {
            "A hint must not accompany an already visible complete solution"
        }
        require(learningWritesAllowed || interaction == null) {
            "A presentation without learning-write authority must not solicit evidence"
        }
    }
}

data class TutorCurrentSessionChoiceAction(
    val sessionId: String,
    val presentationToken: String,
    val choiceId: String,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(presentationToken.isLowerSha256())
        require(choiceId.isValidTutorHostId())
    }
}

data class TutorCurrentSessionFreeResponseAction(
    val sessionId: String,
    val actionToken: String,
    val answer: String,
    val elapsedDurationMillis: Long? = null,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(actionToken.isLowerSha256())
        require(answer == answer.trim())
        OpenResponseEvaluationInputPolicy.requireValidCurrentAnswer(answer)
        require(elapsedDurationMillis == null || elapsedDurationMillis in 0L..MAX_ELAPSED_MILLIS)
    }

    companion object {
        const val MAX_ANSWER_CHARS = OpenResponseEvaluationInputPolicy.MAX_CURRENT_ANSWER_CHARS
        private const val MAX_ELAPSED_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }

    override fun toString(): String =
        "TutorCurrentSessionFreeResponseAction(sessionId=<redacted>, answer=<redacted>)"
}

/** Opaque retry capability. It intentionally contains no student-authored answer. */
data class TutorCurrentSessionFreeResponseRetryAction(
    val sessionId: String,
    val actionToken: String,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(actionToken.isLowerSha256())
    }
}

sealed interface TutorCurrentSessionFreeResponseActionResult {
    /** The immutable source fact was accepted for independent weak-evidence evaluation. */
    data object Accepted : TutorCurrentSessionFreeResponseActionResult

    /** The same action token and answer were already durably claimed. */
    data object Duplicate : TutorCurrentSessionFreeResponseActionResult

    data class Rejected(
        val blocker: TutorCurrentSessionActionBlocker,
    ) : TutorCurrentSessionFreeResponseActionResult
}

data class TutorCurrentSessionVisualTargetPreparation(
    val sessionId: String,
    val presentationToken: String,
    val hitProof: TutorVisualHitProof,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(presentationToken.isLowerSha256())
    }
}

data class TutorCurrentSessionPreparedVisualTarget(
    val actionToken: String,
) {
    init {
        require(actionToken.isLowerSha256())
    }
}

sealed interface TutorCurrentSessionVisualTargetPreparationResult {
    data class Ready(
        val prepared: TutorCurrentSessionPreparedVisualTarget,
    ) : TutorCurrentSessionVisualTargetPreparationResult

    data class Rejected(
        val blocker: TutorCurrentSessionActionBlocker,
    ) : TutorCurrentSessionVisualTargetPreparationResult
}

data class TutorCurrentSessionVisualTargetAction(
    val sessionId: String,
    val actionToken: String,
    val targetId: String,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(actionToken.isLowerSha256())
        require(targetId.isValidTutorHostId())
    }
}

data class TutorCurrentSessionVisualTargetFeedback(
    val feedbackMarkdown: String,
) {
    init {
        require(feedbackMarkdown.isNotBlank())
    }
}

sealed interface TutorCurrentSessionVisualTargetActionResult {
    data class Answered(
        val feedback: TutorCurrentSessionVisualTargetFeedback,
    ) : TutorCurrentSessionVisualTargetActionResult

    data class Rejected(
        val blocker: TutorCurrentSessionActionBlocker,
    ) : TutorCurrentSessionVisualTargetActionResult
}

/** Presentation-only feedback. It deliberately carries no model-authored correctness claim. */
data class TutorCurrentSessionChoiceFeedback(
    val feedbackMarkdown: String,
) {
    init {
        require(feedbackMarkdown.isNotBlank())
    }
}

enum class TutorCurrentSessionActionBlocker {
    NOT_CURRENT,
    INTERACTION_UNAVAILABLE,
    LEARNING_WRITES_DISABLED,
    OWNER_CLOSED,
}

sealed interface TutorCurrentSessionChoiceActionResult {
    /** The answer was handled for this presentation; this does not assert a mastery write. */
    data class Answered(
        val feedback: TutorCurrentSessionChoiceFeedback,
    ) : TutorCurrentSessionChoiceActionResult

    data class Rejected(
        val blocker: TutorCurrentSessionActionBlocker,
    ) : TutorCurrentSessionChoiceActionResult
}

enum class TutorCurrentSessionHostPhase {
    PREPARING,
    ACTIVE,
    REVOKED,
}

enum class TutorCurrentSessionRevocationReason {
    NEW_QUESTION,
    MODE_CHANGED,
    LEARNING_WRITES_DISABLED,
    SESSION_ENDED,
    POLICY_REJECTED,
}

/** Coarse reason suitable for local UI routing; it is not student-facing copy. */
enum class TutorCurrentSessionHostBlocker {
    NOT_PREPARED,
    QUESTION_NOT_CURRENT,
    MODEL_RESULT_NOT_CURRENT,
    LEARNING_AUTHORITY_NOT_CURRENT,
    VERIFIED_MATERIAL_UNAVAILABLE,
    OWNER_CLOSED,
    SUPERSEDED,
}

data class TutorCurrentSessionHostSnapshot(
    val sessionId: String,
    val phase: TutorCurrentSessionHostPhase,
    val questionRevisionNumber: Int,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritesAllowed: Boolean,
    val learningWritePermissionVersion: Long,
    val visualIntent: TutorCurrentSessionVisualIntent = TutorCurrentSessionVisualIntent.NONE,
    val visualIntentVersion: Long = 0L,
    val presentationToken: String?,
    val pendingInteractionKind: TutorEvidenceRequestKind?,
) {
    init {
        require(sessionId.isValidTutorHostId())
        require(questionRevisionNumber > 0)
        require(
            modeVersion >= 0L && learningWritePermissionVersion >= 0L &&
                visualIntentVersion >= 0L,
        )
        require((phase == TutorCurrentSessionHostPhase.ACTIVE) == (presentationToken != null))
        presentationToken?.let { require(it.isLowerSha256()) }
        require(
            pendingInteractionKind == null || explanationMode == TutorExplanationMode.GUIDED,
        ) { "Only a guided current session may expose an interaction" }
        require(phase == TutorCurrentSessionHostPhase.ACTIVE || pendingInteractionKind == null)
    }
}

sealed interface TutorCurrentSessionHostResult {
    data class Ready(
        val snapshot: TutorCurrentSessionHostSnapshot,
        val restored: Boolean,
    ) : TutorCurrentSessionHostResult {
        init {
            require(snapshot.phase == TutorCurrentSessionHostPhase.ACTIVE)
        }
    }

    data class Revoked(
        val snapshot: TutorCurrentSessionHostSnapshot,
    ) : TutorCurrentSessionHostResult {
        init {
            require(snapshot.phase == TutorCurrentSessionHostPhase.REVOKED)
        }
    }

    data class Unavailable(
        val blocker: TutorCurrentSessionHostBlocker,
    ) : TutorCurrentSessionHostResult
}

private fun String.isValidTutorHostId(): Boolean =
    isNotBlank() && this == trim() && length <= 256 && none(Char::isISOControl)

private fun String.isLowerSha256(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
