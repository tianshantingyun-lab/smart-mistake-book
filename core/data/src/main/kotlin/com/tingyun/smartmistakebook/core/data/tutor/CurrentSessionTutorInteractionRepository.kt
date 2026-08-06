package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.data.session.SessionMutationDisposition
import com.tingyun.smartmistakebook.core.data.session.SessionOperationIdentity
import com.tingyun.smartmistakebook.core.data.session.SessionScope
import com.tingyun.smartmistakebook.core.data.session.TutorAnswerExposureSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.TutorAuthorizedInteractionSessionMutation
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionAuthorization
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionAuthorizationPurpose
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionAuthorizationQuery
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionContext
import com.tingyun.smartmistakebook.core.data.session.TutorCurrentInteractionSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.TutorEvidenceCancellationSessionQuery
import com.tingyun.smartmistakebook.core.data.session.TutorInteractionSessionMutation
import com.tingyun.smartmistakebook.core.data.session.TutorInteractionSessionMutationResult
import com.tingyun.smartmistakebook.core.data.session.TutorInteractionSessionPort
import com.tingyun.smartmistakebook.core.data.session.TutorTurnResponseSessionSnapshot
import com.tingyun.smartmistakebook.core.data.session.TutorTurnSessionKey
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorEvidenceRejectedException
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorSessionProblemAnchor
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualTargetEvidence
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Current-session interaction writer.
 *
 * The repository can only request opaque, learner-bound session authorizations. It cannot open or
 * interpret any business store, and it never emits learning evidence.
 */
internal class CurrentSessionTutorInteractionRepository(
    private val sessions: TutorInteractionSessionPort,
) : TutorInteractionRepository {
    private val processCancelledRequestIds = ConcurrentHashMap.newKeySet<String>()

    override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> {
        require(sessionId.isNotBlank())
        return sessions.observeCurrent(sessionId).map { snapshot ->
            snapshot.requireCurrentSession(sessions.scope, sessionId)
            snapshot.responses.mapNotNull { it.value.toDomainWithoutArchivedAnswerOrNull() }
        }
    }

    override fun observeVisualTargetEvidence(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidence>> {
        require(sessionId.isNotBlank())
        return sessions.observeCurrent(sessionId).map { snapshot ->
            snapshot.requireCurrentSession(sessions.scope, sessionId)
            emptyList()
        }
    }

    override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse =
        throw TutorEvidenceRejectedException(STUDENT_OWNER_ANSWER_REQUIRED)

    override suspend fun recordVisualTargetEvidence(
        command: RecordTutorVisualTargetEvidenceCommand,
    ): TutorVisualTargetEvidence =
        throw TutorEvidenceRejectedException(STUDENT_OWNER_ANSWER_REQUIRED)

    override fun cancelEvidence(requestId: String) {
        require(requestId.isNotBlank())
        processCancelledRequestIds += requestId
    }

    override suspend fun cancelEvidence(command: CancelTutorEvidenceCommand) {
        cancelEvidence(command.evidenceRequestId)
        val authorization = authorizeOrNull(
            purpose = TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE,
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            requestId = command.evidenceRequestId,
        ) ?: return
        val context = authorization.context
        val mutation = TutorInteractionSessionMutation.CancelEvidence(
            scope = sessions.scope,
            operation = operation(
                action = "cancel-evidence",
                authorization = authorization,
                payload = listOf("requestId" to command.evidenceRequestId),
            ),
            expectedVersion = context.version,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            key = context.toSessionKey(),
            evidenceRequestId = command.evidenceRequestId,
        )
        sessions.mutateCurrent(
            TutorAuthorizedInteractionSessionMutation(authorization, mutation),
        ).requireAccepted(command.evidenceRequestId)
    }

    override suspend fun isEvidenceCancelled(command: CancelTutorEvidenceCommand): Boolean {
        if (command.evidenceRequestId in processCancelledRequestIds) return true
        val authorization = authorizeOrNull(
            purpose = TutorCurrentInteractionAuthorizationPurpose.CANCEL_EVIDENCE,
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            requestId = command.evidenceRequestId,
        ) ?: return true
        return sessions.isEvidenceCancelled(
            TutorEvidenceCancellationSessionQuery(
                scope = sessions.scope,
                key = authorization.context.toSessionKey(),
                evidenceRequestId = command.evidenceRequestId,
            ),
        )
    }

    override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse {
        val authorization = authorize(
            purpose = TutorCurrentInteractionAuthorizationPurpose.RECORD_MOVE,
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
        )
        val mutation = TutorInteractionSessionMutation.RecordMove(
            scope = sessions.scope,
            operation = operation(
                action = "record-move",
                authorization = authorization,
                payload = listOf("move" to command.requestedMove.name),
            ),
            expectedVersion = authorization.context.version,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            key = command.toSessionKey(),
            requestedMove = command.requestedMove.name,
        )
        return sessions.mutateCurrent(
            TutorAuthorizedInteractionSessionMutation(authorization, mutation),
        ).requireResponse(mutation.operation.requestId, authorization).toDomain()
    }

    override suspend fun revealSolution(
        command: RevealTutorSolutionCommand,
    ): TutorTurnResponse {
        val authorization = authorize(
            purpose = TutorCurrentInteractionAuthorizationPurpose.REVEAL_SOLUTION,
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
        )
        val mutation = TutorInteractionSessionMutation.RevealSolution(
            scope = sessions.scope,
            operation = operation(
                action = "reveal-solution",
                authorization = authorization,
                payload = emptyList(),
            ),
            expectedVersion = authorization.context.version,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            key = command.toSessionKey(),
        )
        return sessions.mutateCurrent(
            TutorAuthorizedInteractionSessionMutation(authorization, mutation),
        ).requireResponse(mutation.operation.requestId, authorization).toDomain()
    }

    override suspend fun recordSolutionExposure(
        command: RecordTutorSolutionExposureCommand,
    ) {
        command.authorizationRequestId.requireNotCancelled()
        val authorization = authorize(
            purpose = TutorCurrentInteractionAuthorizationPurpose.RECORD_EXPOSURE,
            sessionId = command.sessionId,
            questionDocumentId = command.questionDocumentId,
            revisionNumber = command.revisionNumber,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            requestId = command.authorizationRequestId,
        )
        command.authorizationRequestId.requireNotCancelled()
        val mutation = TutorInteractionSessionMutation.RecordExposure(
            scope = sessions.scope,
            operation = operation(
                action = "record-exposure",
                authorization = authorization,
                payload = listOf(
                    "surface" to command.surfaceKind.name,
                    "responseOrdinal" to command.responseOrdinal?.toString(),
                ),
            ),
            expectedVersion = authorization.context.version,
            occurredAtEpochMillis = command.occurredAtEpochMillis,
            key = command.toSessionKey(),
            surfaceKind = command.surfaceKind.name,
            modelTaskRequestId = command.modelTaskRequestId,
            responseOrdinal = command.responseOrdinal,
        )
        sessions.mutateCurrent(
            TutorAuthorizedInteractionSessionMutation(authorization, mutation),
        ).requireAccepted(command.authorizationRequestId)
    }

    override suspend fun hasAnswerExposure(key: TutorAnswerExposureKey): Boolean =
        key in findRecordedAnswerExposures(setOf(key))

    override suspend fun findRecordedAnswerExposures(
        keys: Set<TutorAnswerExposureKey>,
    ): Set<TutorAnswerExposureKey> {
        if (keys.isEmpty()) return emptySet()
        return buildSet {
            keys.groupBy(TutorAnswerExposureKey::sessionId).forEach { (sessionId, candidates) ->
                val snapshot = sessions.observeCurrent(sessionId).first()
                snapshot.requireCurrentSession(sessions.scope, sessionId)
                snapshot.exposures.forEach { record ->
                    candidates.filterTo(this) { candidate ->
                        record.value.matches(candidate)
                    }
                }
            }
        }
    }

    override suspend fun anchorSession(anchor: TutorSessionProblemAnchor) {
        val authorization = authorize(
            purpose = TutorCurrentInteractionAuthorizationPurpose.ANCHOR_SESSION,
            sessionId = anchor.sessionId,
        )
        val mutation = TutorInteractionSessionMutation.AnchorSession(
            scope = sessions.scope,
            operation = operation(
                action = "anchor-session",
                authorization = authorization,
                payload = listOf(
                    "revisionRef" to anchor.problemRevisionId,
                    "practiceRef" to anchor.practiceUnitId,
                ),
            ),
            expectedVersion = authorization.context.version,
            occurredAtEpochMillis = anchor.anchoredAtEpochMillis,
            sessionId = anchor.sessionId,
            targetRevisionRef = anchor.problemRevisionId,
            targetPracticeRef = anchor.practiceUnitId,
            sourceKind = "SAVED_MISTAKE",
        )
        sessions.mutateCurrent(
            TutorAuthorizedInteractionSessionMutation(authorization, mutation),
        ).requireAccepted(mutation.operation.requestId)
    }

    private suspend fun authorize(
        purpose: TutorCurrentInteractionAuthorizationPurpose,
        sessionId: String,
        questionDocumentId: String? = null,
        revisionNumber: Int? = null,
        cycleOrdinal: Int? = null,
        turnOrdinal: Int? = null,
        requestId: String? = null,
    ): TutorCurrentInteractionAuthorization =
        authorizeOrNull(
            purpose = purpose,
            sessionId = sessionId,
            questionDocumentId = questionDocumentId,
            revisionNumber = revisionNumber,
            cycleOrdinal = cycleOrdinal,
            turnOrdinal = turnOrdinal,
            requestId = requestId,
        ) ?: throw TutorEvidenceRejectedException(requestId ?: "$sessionId:${purpose.name}")

    private suspend fun authorizeOrNull(
        purpose: TutorCurrentInteractionAuthorizationPurpose,
        sessionId: String,
        questionDocumentId: String? = null,
        revisionNumber: Int? = null,
        cycleOrdinal: Int? = null,
        turnOrdinal: Int? = null,
        requestId: String? = null,
    ): TutorCurrentInteractionAuthorization? {
        val query = TutorCurrentInteractionAuthorizationQuery(
            scope = sessions.scope,
            conversationId = sessionId,
            questionDocumentId = questionDocumentId,
            revisionNumber = revisionNumber,
            cycleOrdinal = cycleOrdinal,
            turnOrdinal = turnOrdinal,
            requestId = requestId,
            purpose = purpose,
        )
        return sessions.authorizeCurrent(query)?.also { authorization ->
            require(authorization.matches(query)) {
                "Tutor session owner returned an authorization for another request"
            }
        }
    }

    private fun String.requireNotCancelled() {
        if (this in processCancelledRequestIds) {
            throw TutorEvidenceRejectedException(this)
        }
    }
}

internal object CurrentSessionTutorInteractionRepositoryFactory {
    fun createProduction(
        sessions: TutorInteractionSessionPort,
    ): TutorInteractionRepository = CurrentSessionTutorInteractionRepository(sessions)
}

private fun TutorCurrentInteractionAuthorization.matches(
    query: TutorCurrentInteractionAuthorizationQuery,
): Boolean {
    if (
        context.scope != query.scope ||
        context.conversationId != query.conversationId ||
        purpose != query.purpose ||
        requestId != query.requestId
    ) {
        return false
    }
    if (query.purpose == TutorCurrentInteractionAuthorizationPurpose.ANCHOR_SESSION) return true
    if (
        context.questionDocumentId != query.questionDocumentId ||
        context.revisionNumber != query.revisionNumber
    ) {
        return false
    }
    return query.cycleOrdinal == null ||
        context.cycleOrdinal == query.cycleOrdinal &&
        context.turnOrdinal == query.turnOrdinal
}

private fun operation(
    action: String,
    authorization: TutorCurrentInteractionAuthorization,
    payload: List<Pair<String, String?>>,
): SessionOperationIdentity {
    val context = authorization.context
    val digest = CanonicalSha256("current-tutor-interaction-v1")
        .field("action", action)
        .field("learnerId", context.scope.learnerId)
        .field("conversationId", context.conversationId)
        .field("conversationGeneration", context.conversationGeneration)
        .field("conversationStateVersion", context.conversationStateVersion)
        .field("questionDocumentId", context.questionDocumentId)
        .field("revisionNumber", context.revisionNumber)
        .field("questionFingerprint", context.questionFingerprint)
        .field("subject", context.subject.name)
        .field("problemAnchorId", context.problemAnchorId)
        .field("explanationMode", context.explanationMode.name)
        .field("modeVersion", context.modeVersion)
        .field("learningWritePermissionVersion", context.learningWritePermissionVersion)
        .field("turnReferenceId", context.turnReferenceId)
        .field("turnGeneration", context.turnGeneration)
        .field("cycleOrdinal", context.cycleOrdinal)
        .field("turnOrdinal", context.turnOrdinal)
        .field("attemptOrdinal", context.attemptOrdinal)
        .field("hintCount", context.hintCount)
        .field("answerWasRevealed", context.answerWasRevealed)
        .field("requestVersion", context.requestVersion)
        .nullableField("authorizedRequestId", authorization.requestId)
    payload.forEachIndexed { index, (name, value) ->
        digest.field("payload${index}Name", name)
        digest.nullableField("payload${index}Value", value)
    }
    val fingerprint = digest.finish()
    return SessionOperationIdentity(
        requestId = "tutor-current-$fingerprint",
        idempotencyKey = fingerprint,
        requestVersion = context.requestVersion,
        payloadFingerprint = fingerprint,
    )
}

private fun TutorInteractionSessionMutationResult.requireAccepted(
    requestId: String,
): TutorInteractionSessionMutationResult {
    if (
        receipt.disposition != SessionMutationDisposition.APPLIED &&
        receipt.disposition != SessionMutationDisposition.DUPLICATE
    ) {
        throw TutorEvidenceRejectedException(requestId)
    }
    return this
}

private fun TutorInteractionSessionMutationResult.requireResponse(
    requestId: String,
    authorization: TutorCurrentInteractionAuthorization,
): TutorTurnResponseSessionSnapshot {
    requireAccepted(requestId)
    return response?.also { stored ->
        require(stored.scope == authorization.context.scope)
        require(stored.key == authorization.context.toSessionKey())
    } ?: error("Accepted tutor response append returned no response")
}

private fun TutorCurrentInteractionSessionSnapshot.requireCurrentSession(
    expectedScope: SessionScope,
    sessionId: String,
) {
    require(current.scope == expectedScope)
    require(current.conversationId == sessionId)
}

private fun RecordTutorMoveCommand.toSessionKey() = TutorTurnSessionKey(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

private fun RevealTutorSolutionCommand.toSessionKey() = TutorTurnSessionKey(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

private fun RecordTutorSolutionExposureCommand.toSessionKey() = TutorTurnSessionKey(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

private fun TutorCurrentInteractionContext.toSessionKey() = TutorTurnSessionKey(
    sessionId = conversationId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
)

private fun TutorTurnResponseSessionSnapshot.toDomain() = TutorTurnResponse(
    sessionId = key.sessionId,
    questionDocumentId = key.questionDocumentId,
    revisionNumber = key.revisionNumber,
    cycleOrdinal = key.cycleOrdinal,
    turnOrdinal = key.turnOrdinal,
    diagnosticStemMarkdown = diagnosticStemMarkdown,
    selectedChoiceId = selectedChoiceId,
    selectedChoiceMarkdown = selectedChoiceMarkdown,
    selectionWasCorrect = selectionWasCorrect,
    feedbackMarkdown = feedbackMarkdown,
    requestedMove = requestedMove?.let(TutorMoveType::valueOf),
    solutionRevealed = solutionRevealed,
    choiceSubmittedAtEpochMillis = choiceSubmittedAtEpochMillis,
    submittedAtEpochMillis = submittedAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    evidenceRequestId = evidenceRequestId,
)

private fun TutorTurnResponseSessionSnapshot.toDomainWithoutArchivedAnswerOrNull(): TutorTurnResponse? {
    val hasArchivedAnswer = diagnosticStemMarkdown != null ||
        selectedChoiceId != null ||
        selectedChoiceMarkdown != null ||
        selectionWasCorrect != null ||
        feedbackMarkdown != null ||
        choiceSubmittedAtEpochMillis != null ||
        evidenceRequestId != null
    if (!hasArchivedAnswer) return toDomain()
    if (requestedMove == null && !solutionRevealed) return null
    return copy(
        diagnosticStemMarkdown = null,
        selectedChoiceId = null,
        selectedChoiceMarkdown = null,
        selectionWasCorrect = null,
        feedbackMarkdown = null,
        choiceSubmittedAtEpochMillis = null,
        evidenceRequestId = null,
    ).toDomain()
}

private fun TutorAnswerExposureSessionSnapshot.matches(
    key: TutorAnswerExposureKey,
): Boolean =
    this.key.sessionId == key.sessionId &&
        this.key.questionDocumentId == key.questionDocumentId &&
        this.key.revisionNumber == key.revisionNumber &&
        this.key.cycleOrdinal == key.cycleOrdinal &&
        this.key.turnOrdinal == key.turnOrdinal &&
        surfaceKind == key.surfaceKind.name &&
        modelTaskRequestId == key.modelTaskRequestId &&
        responseOrdinal == key.responseOrdinal

private const val STUDENT_OWNER_ANSWER_REQUIRED = "student-owner-answer-required"
