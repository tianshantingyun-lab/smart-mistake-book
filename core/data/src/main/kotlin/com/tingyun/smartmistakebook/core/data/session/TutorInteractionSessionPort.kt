package com.tingyun.smartmistakebook.core.data.session

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import kotlinx.coroutines.flow.Flow

/**
 * Exact current-session boundary for one tutor turn.
 *
 * Every production append carries this complete binding back to the session owner. The owner must
 * compare it atomically with its current conversation state before committing the append.
 */
internal data class TutorCurrentInteractionContext(
    val scope: SessionScope,
    val conversationId: String,
    val conversationGeneration: Long,
    val conversationStateVersion: Long,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val questionFingerprint: String,
    val subject: SubjectKind,
    val problemAnchorId: String,
    val explanationMode: TutorExplanationMode,
    val modeVersion: Long,
    val learningWritePermissionVersion: Long,
    val turnReferenceId: String,
    val turnGeneration: Long,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
    val attemptOrdinal: Int,
    val hintCount: Int,
    val answerWasRevealed: Boolean,
    val requestVersion: Long,
    val version: SessionVersion,
) {
    init {
        conversationId.requireSessionIdentifier("Tutor conversation id")
        questionDocumentId.requireSessionIdentifier("Tutor question document id")
        questionFingerprint.requireSessionFingerprint("Tutor question fingerprint")
        problemAnchorId.requireSessionIdentifier("Tutor problem anchor id")
        turnReferenceId.requireSessionIdentifier("Tutor turn reference id")
        require(conversationGeneration > 0) {
            "Tutor conversation generation must be positive"
        }
        require(conversationStateVersion >= 0 && subject != SubjectKind.GENERAL)
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0) {
            "Tutor interaction revision and ordinals must be positive"
        }
        require(
            modeVersion >= 0 && learningWritePermissionVersion >= 0 &&
                requestVersion >= 0 && turnGeneration > 0,
        ) {
            "Tutor mode version and turn generation are invalid"
        }
        require(attemptOrdinal in 1..17 && hintCount in 0..32)
    }

    fun owns(other: TutorCurrentInteractionContext): Boolean =
        scope == other.scope &&
            conversationId == other.conversationId &&
            conversationGeneration == other.conversationGeneration &&
            questionDocumentId == other.questionDocumentId &&
            revisionNumber == other.revisionNumber
}

internal data class TutorCurrentInteractionRecord<T>(
    val context: TutorCurrentInteractionContext,
    val value: T,
)

/**
 * One coherent view of the active question in one conversation generation.
 *
 * Earlier committed turns may have older mode/turn generations, but never cross the learner,
 * conversation generation, or question revision boundary.
 */
internal data class TutorCurrentInteractionSessionSnapshot(
    val current: TutorCurrentInteractionContext,
    val responses: List<TutorCurrentInteractionRecord<TutorTurnResponseSessionSnapshot>>,
    val visualSelections:
        List<TutorCurrentInteractionRecord<TutorVisualSelectionSessionSnapshot>>,
    val exposures: List<TutorCurrentInteractionRecord<TutorAnswerExposureSessionSnapshot>>,
) {
    init {
        (responses.map { it.context } +
            visualSelections.map { it.context } +
            exposures.map { it.context }).forEach { rowContext ->
            require(current.owns(rowContext)) {
                "Current tutor snapshot contains another conversation or question revision"
            }
        }
        responses.forEach { record ->
            require(record.value.scope == record.context.scope)
            require(record.value.key.matches(record.context))
        }
        visualSelections.forEach { record ->
            require(record.value.scope == record.context.scope)
            require(record.value.key.matches(record.context))
        }
        exposures.forEach { record ->
            require(record.value.scope == record.context.scope)
            require(record.value.key.matches(record.context))
        }
    }
}

internal enum class TutorCurrentInteractionAuthorizationPurpose {
    RECORD_CHOICE,
    RECORD_VISUAL_SELECTION,
    CANCEL_EVIDENCE,
    RECORD_MOVE,
    REVEAL_SOLUTION,
    RECORD_EXPOSURE,
    ANCHOR_SESSION,
}

/**
 * Requests an opaque append grant from the session owner.
 *
 * Evidence and exposure requests are resolved against their original request identity. A current
 * grant must not be returned for a cancelled request or an uncommitted request from a superseded
 * conversation, question revision, mode version, or turn generation. Cancellation may resolve an
 * already-cancelled current request so recording the tombstone stays idempotent.
 */
internal data class TutorCurrentInteractionAuthorizationQuery(
    val scope: SessionScope,
    val conversationId: String,
    val questionDocumentId: String?,
    val revisionNumber: Int?,
    val cycleOrdinal: Int?,
    val turnOrdinal: Int?,
    val requestId: String?,
    val purpose: TutorCurrentInteractionAuthorizationPurpose,
) {
    init {
        conversationId.requireSessionIdentifier("Tutor conversation id")
        questionDocumentId?.requireSessionIdentifier("Tutor question document id")
        requestId?.requireSessionIdentifier("Tutor interaction request id")
        require(revisionNumber == null || revisionNumber > 0)
        require(cycleOrdinal == null || cycleOrdinal > 0)
        require(turnOrdinal == null || turnOrdinal > 0)
        when (purpose) {
            TutorCurrentInteractionAuthorizationPurpose.ANCHOR_SESSION -> {
                require(
                    questionDocumentId == null &&
                        revisionNumber == null &&
                        cycleOrdinal == null &&
                        turnOrdinal == null &&
                        requestId == null,
                )
            }
            TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE -> {
                require(
                    questionDocumentId != null &&
                        revisionNumber != null &&
                        cycleOrdinal == null &&
                        turnOrdinal == null &&
                        requestId != null,
                )
            }
            TutorCurrentInteractionAuthorizationPurpose.RECORD_MOVE,
            TutorCurrentInteractionAuthorizationPurpose.REVEAL_SOLUTION,
            -> {
                require(
                    questionDocumentId != null &&
                        revisionNumber != null &&
                        cycleOrdinal != null &&
                        turnOrdinal != null &&
                        requestId == null,
                )
            }
            TutorCurrentInteractionAuthorizationPurpose.RECORD_CHOICE,
            TutorCurrentInteractionAuthorizationPurpose.RECORD_VISUAL_SELECTION,
            TutorCurrentInteractionAuthorizationPurpose.RECORD_EXPOSURE,
            -> {
                require(
                    questionDocumentId != null &&
                        revisionNumber != null &&
                        cycleOrdinal != null &&
                        turnOrdinal != null &&
                        requestId != null,
                )
            }
        }
    }
}

internal data class TutorCurrentInteractionAuthorization(
    val context: TutorCurrentInteractionContext,
    val purpose: TutorCurrentInteractionAuthorizationPurpose,
    val requestId: String?,
    val token: String,
) {
    init {
        requestId?.requireSessionIdentifier("Tutor authorized request id")
        token.requireSessionIdentifier("Tutor interaction authorization token")
        when (purpose) {
            TutorCurrentInteractionAuthorizationPurpose.RECORD_CHOICE,
            TutorCurrentInteractionAuthorizationPurpose.RECORD_VISUAL_SELECTION,
            TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE,
            TutorCurrentInteractionAuthorizationPurpose.RECORD_EXPOSURE,
            -> require(requestId != null)
            TutorCurrentInteractionAuthorizationPurpose.RECORD_MOVE,
            TutorCurrentInteractionAuthorizationPurpose.REVEAL_SOLUTION,
            TutorCurrentInteractionAuthorizationPurpose.ANCHOR_SESSION,
            -> require(requestId == null)
        }
    }
}

internal data class TutorTurnSessionKey(
    val sessionId: String,
    val questionDocumentId: String,
    val revisionNumber: Int,
    val cycleOrdinal: Int,
    val turnOrdinal: Int,
) {
    init {
        sessionId.requireSessionIdentifier("Tutor interaction session id")
        questionDocumentId.requireSessionIdentifier("Tutor question document id")
        require(revisionNumber > 0 && cycleOrdinal > 0 && turnOrdinal > 0) {
            "Tutor interaction revision and ordinals must be positive"
        }
    }
}

private fun TutorTurnSessionKey.matches(
    context: TutorCurrentInteractionContext,
): Boolean =
    sessionId == context.conversationId &&
        questionDocumentId == context.questionDocumentId &&
        revisionNumber == context.revisionNumber &&
        cycleOrdinal == context.cycleOrdinal &&
        turnOrdinal == context.turnOrdinal

internal data class TutorTurnResponseSessionSnapshot(
    val scope: SessionScope,
    val key: TutorTurnSessionKey,
    val version: SessionVersion,
    val diagnosticStemMarkdown: String?,
    val selectedChoiceId: String?,
    val selectedChoiceMarkdown: String?,
    val selectionWasCorrect: Boolean?,
    val feedbackMarkdown: String?,
    val requestedMove: String?,
    val solutionRevealed: Boolean,
    val choiceSubmittedAtEpochMillis: Long?,
    val submittedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val evidenceRequestId: String?,
) {
    init {
        evidenceRequestId?.requireSessionIdentifier("Tutor evidence request id")
        require(choiceSubmittedAtEpochMillis == null || choiceSubmittedAtEpochMillis >= 0) {
            "Tutor choice time must not be negative"
        }
        require(submittedAtEpochMillis >= 0 && updatedAtEpochMillis >= submittedAtEpochMillis) {
            "Tutor response times are invalid"
        }
        listOfNotNull(
            diagnosticStemMarkdown,
            selectedChoiceMarkdown,
            feedbackMarkdown,
        ).forEach { text ->
            require(text.length <= MAX_TUTOR_INTERACTION_TEXT_CHARS) {
                "Tutor interaction text exceeds its session budget"
            }
        }
    }
}

internal data class TutorVisualSelectionSessionSnapshot(
    val scope: SessionScope,
    val key: TutorTurnSessionKey,
    val version: SessionVersion,
    val surfaceKind: String,
    val modelTaskRequestId: String,
    val responseOrdinal: Int?,
    val sceneSourceKind: String,
    val sceneTaskRequestId: String,
    val sceneId: String,
    val sceneFingerprint: String,
    val hitProofId: String,
    val panelId: String,
    val frameFingerprint: String,
    val stepIndex: Int,
    val selectedTargetId: String,
    val selectionWasCorrect: Boolean,
    val submittedAtEpochMillis: Long,
) {
    init {
        surfaceKind.requireSessionIdentifier("Tutor visual surface kind")
        modelTaskRequestId.requireSessionIdentifier("Tutor model request id")
        require(responseOrdinal == null || responseOrdinal > 0) {
            "Tutor visual response ordinal must be positive"
        }
        sceneSourceKind.requireSessionIdentifier("Tutor visual scene source kind")
        sceneTaskRequestId.requireSessionIdentifier("Tutor visual scene request id")
        sceneId.requireSessionIdentifier("Tutor visual scene id")
        sceneFingerprint.requireSessionFingerprint("Tutor visual scene fingerprint")
        hitProofId.requireSessionIdentifier("Tutor visual hit-proof id")
        panelId.requireSessionIdentifier("Tutor visual panel id")
        frameFingerprint.requireSessionFingerprint("Tutor visual frame fingerprint")
        require(stepIndex >= 0) { "Tutor visual step index must not be negative" }
        selectedTargetId.requireSessionIdentifier("Tutor visual selected target id")
        require(submittedAtEpochMillis >= 0) {
            "Tutor visual submission time must not be negative"
        }
    }
}

internal data class TutorAnswerExposureSessionSnapshot(
    val scope: SessionScope,
    val exposureId: String,
    val key: TutorTurnSessionKey,
    val version: SessionVersion,
    val surfaceKind: String,
    val modelTaskRequestId: String?,
    val responseOrdinal: Int?,
    val exposedAtEpochMillis: Long,
    val outcomeReceiptId: String?,
) {
    init {
        exposureId.requireSessionIdentifier("Tutor exposure id")
        surfaceKind.requireSessionIdentifier("Tutor exposure surface kind")
        modelTaskRequestId?.requireSessionIdentifier("Tutor exposure model request id")
        require(responseOrdinal == null || responseOrdinal > 0) {
            "Tutor exposure response ordinal must be positive"
        }
        require(exposedAtEpochMillis >= 0) { "Tutor exposure time must not be negative" }
        outcomeReceiptId?.requireSessionIdentifier("Tutor exposure outcome receipt id")
    }
}

internal data class TutorEvidenceCancellationSessionQuery(
    val scope: SessionScope,
    val key: TutorTurnSessionKey,
    val evidenceRequestId: String,
) {
    init {
        evidenceRequestId.requireSessionIdentifier("Tutor evidence request id")
    }
}

internal data class TutorAnswerExposureSessionQuery(
    val scope: SessionScope,
    val modelTaskRequestIds: Set<String>,
) {
    init {
        require(modelTaskRequestIds.size <= MAX_TUTOR_EXPOSURE_QUERY_SIZE) {
            "Tutor exposure query exceeds its session budget"
        }
        modelTaskRequestIds.forEach {
            it.requireSessionIdentifier("Tutor exposure model request id")
        }
    }
}

internal sealed interface TutorInteractionSessionMutation {
    val scope: SessionScope
    val operation: SessionOperationIdentity
    val expectedVersion: SessionVersion?
    val occurredAtEpochMillis: Long

    data class RecordChoice(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val expectedVersion: SessionVersion?,
        override val occurredAtEpochMillis: Long,
        val key: TutorTurnSessionKey,
        val diagnosticStemMarkdown: String,
        val selectedChoiceId: String,
        val selectedChoiceMarkdown: String,
        val selectionWasCorrect: Boolean,
        val feedbackMarkdown: String,
        val evidenceRequestId: String?,
    ) : TutorInteractionSessionMutation

    data class RecordVisualSelection(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val expectedVersion: SessionVersion?,
        override val occurredAtEpochMillis: Long,
        val key: TutorTurnSessionKey,
        val evidenceRequestId: String,
        val surfaceKind: String,
        val modelTaskRequestId: String,
        val responseOrdinal: Int?,
        val sceneSourceKind: String,
        val sceneTaskRequestId: String,
        val sceneId: String,
        val sceneFingerprint: String,
        val hitProofId: String,
        val panelId: String,
        val frameFingerprint: String,
        val stepIndex: Int,
        val selectedTargetId: String,
        val selectionWasCorrect: Boolean = false,
    ) : TutorInteractionSessionMutation

    data class CancelEvidence(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val expectedVersion: SessionVersion? = null,
        override val occurredAtEpochMillis: Long,
        val key: TutorTurnSessionKey,
        val evidenceRequestId: String,
    ) : TutorInteractionSessionMutation

    data class RecordMove(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val key: TutorTurnSessionKey,
        val requestedMove: String,
    ) : TutorInteractionSessionMutation

    data class RevealSolution(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val expectedVersion: SessionVersion,
        override val occurredAtEpochMillis: Long,
        val key: TutorTurnSessionKey,
    ) : TutorInteractionSessionMutation

    data class RecordExposure(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val expectedVersion: SessionVersion? = null,
        override val occurredAtEpochMillis: Long,
        val key: TutorTurnSessionKey,
        val surfaceKind: String,
        val modelTaskRequestId: String,
        val responseOrdinal: Int?,
    ) : TutorInteractionSessionMutation

    data class AnchorSession(
        override val scope: SessionScope,
        override val operation: SessionOperationIdentity,
        override val expectedVersion: SessionVersion? = null,
        override val occurredAtEpochMillis: Long,
        val sessionId: String,
        val targetRevisionRef: String,
        val targetPracticeRef: String,
        val sourceKind: String,
    ) : TutorInteractionSessionMutation
}

internal data class TutorSessionAnchorReceipt(
    val scope: SessionScope,
    val sessionId: String,
    val targetRevisionRef: String,
    val targetPracticeRef: String,
    val sourceKind: String,
    val anchoredAtEpochMillis: Long,
) {
    init {
        sessionId.requireSessionIdentifier("Tutor anchor session id")
        targetRevisionRef.requireSessionIdentifier("Tutor anchor revision reference")
        targetPracticeRef.requireSessionIdentifier("Tutor anchor practice reference")
        sourceKind.requireSessionIdentifier("Tutor anchor source kind")
        require(anchoredAtEpochMillis >= 0) { "Tutor anchor time must not be negative" }
    }
}

internal data class TutorInteractionSessionMutationResult(
    val receipt: SessionMutationReceipt,
    val response: TutorTurnResponseSessionSnapshot? = null,
    val visualSelection: TutorVisualSelectionSessionSnapshot? = null,
    val exposure: TutorAnswerExposureSessionSnapshot? = null,
    val anchor: TutorSessionAnchorReceipt? = null,
)

/**
 * Production append envelope. The port implementation must validate [authorization] and the
 * complete current context in the same transaction that applies [mutation].
 */
internal data class TutorAuthorizedInteractionSessionMutation(
    val authorization: TutorCurrentInteractionAuthorization,
    val mutation: TutorInteractionSessionMutation,
) {
    init {
        val context = authorization.context
        require(mutation.scope == context.scope)
        require(mutation.expectedVersion == context.version)
        when (val value = mutation) {
            is TutorInteractionSessionMutation.RecordChoice -> {
                requirePurpose(TutorCurrentInteractionAuthorizationPurpose.RECORD_CHOICE)
                require(value.key.matches(context))
                require(value.evidenceRequestId == authorization.requestId)
            }
            is TutorInteractionSessionMutation.RecordVisualSelection -> {
                requirePurpose(TutorCurrentInteractionAuthorizationPurpose.RECORD_VISUAL_SELECTION)
                require(value.key.matches(context))
                require(value.evidenceRequestId == authorization.requestId)
            }
            is TutorInteractionSessionMutation.CancelEvidence -> {
                requirePurpose(TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE)
                require(value.key.matches(context))
                require(value.evidenceRequestId == authorization.requestId)
            }
            is TutorInteractionSessionMutation.RecordMove -> {
                requirePurpose(TutorCurrentInteractionAuthorizationPurpose.RECORD_MOVE)
                require(value.key.matches(context))
            }
            is TutorInteractionSessionMutation.RevealSolution -> {
                requirePurpose(TutorCurrentInteractionAuthorizationPurpose.REVEAL_SOLUTION)
                require(value.key.matches(context))
            }
            is TutorInteractionSessionMutation.RecordExposure -> {
                requirePurpose(TutorCurrentInteractionAuthorizationPurpose.RECORD_EXPOSURE)
                require(value.key.matches(context))
                require(value.modelTaskRequestId == context.turnReferenceId)
                if (context.explanationMode == TutorExplanationMode.DIRECT) {
                    require(authorization.requestId == context.turnReferenceId)
                }
            }
            is TutorInteractionSessionMutation.AnchorSession -> {
                requirePurpose(TutorCurrentInteractionAuthorizationPurpose.ANCHOR_SESSION)
                require(value.sessionId == context.conversationId)
            }
        }
    }

    private fun requirePurpose(expected: TutorCurrentInteractionAuthorizationPurpose) {
        require(authorization.purpose == expected) {
            "Tutor interaction authorization does not match its mutation"
        }
    }
}

internal interface TutorInteractionSessionPort {
    /** Learner scope fixed when the owner issues this capability. */
    val scope: SessionScope

    fun observeResponses(
        scope: SessionScope,
        sessionId: String,
    ): Flow<List<TutorTurnResponseSessionSnapshot>>

    fun observeVisualSelections(
        scope: SessionScope,
        sessionId: String,
    ): Flow<List<TutorVisualSelectionSessionSnapshot>>

    suspend fun isEvidenceCancelled(
        query: TutorEvidenceCancellationSessionQuery,
    ): Boolean

    suspend fun readExposures(
        query: TutorAnswerExposureSessionQuery,
    ): List<TutorAnswerExposureSessionSnapshot>

    suspend fun mutate(
        command: TutorInteractionSessionMutation,
    ): TutorInteractionSessionMutationResult

    /**
     * Observes only the active question revision in the requested conversation generation.
     *
     * Implementations must emit an initial snapshot when that current session exists.
     */
    fun observeCurrent(
        conversationId: String,
    ): Flow<TutorCurrentInteractionSessionSnapshot>

    suspend fun authorizeCurrent(
        query: TutorCurrentInteractionAuthorizationQuery,
    ): TutorCurrentInteractionAuthorization?

    suspend fun mutateCurrent(
        command: TutorAuthorizedInteractionSessionMutation,
    ): TutorInteractionSessionMutationResult
}

private const val MAX_TUTOR_INTERACTION_TEXT_CHARS = 8_000
private const val MAX_TUTOR_EXPOSURE_QUERY_SIZE = 100
