package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorEvidenceCancellationCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorAnswerExposureCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorMoveCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorRevealCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorSessionAnchorCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.TutorVisualTargetEvidenceRecord
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorEvidenceRejectedException
import com.tingyun.smartmistakebook.core.domain.TutorSessionProblemAnchor
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.domain.TutorVisualTargetEvidence
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProofRegistry
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap

internal class RoomTutorInteractionRepository(
    private val database: StudyDatabasePort,
    private val learnerId: String = "learner:local",
) : TutorInteractionRepository {
    private val evidenceWriteGate = TutorEvidenceWriteGate()

    override fun observe(sessionId: String): Flow<List<TutorTurnResponse>> {
        require(sessionId.isNotBlank())
        return database.observeTutorTurnResponses(sessionId).map { records ->
            records.map(TutorTurnResponseRecord::toDomain)
        }
    }

    override fun observeVisualTargetEvidence(
        sessionId: String,
    ): Flow<List<TutorVisualTargetEvidence>> {
        require(sessionId.isNotBlank())
        return database.observeTutorVisualTargetEvidence(sessionId).map { records ->
            records.map(TutorVisualTargetEvidenceRecord::toDomain)
        }
    }

    override suspend fun recordChoice(command: RecordTutorChoiceCommand): TutorTurnResponse {
        val persisted = command.toPersistedChoice()
        val requestId = command.evidenceRequestId
        return if (requestId == null) {
            withContext(Dispatchers.IO) { database.recordTutorChoice(persisted).toDomain() }
        } else {
            evidenceWriteGate.persist(
                requestId = requestId,
                write = {
                    withContext(Dispatchers.IO) {
                        database.recordTutorChoiceUnlessCancelled(
                            persisted,
                            command.toPersistedCancellation(learnerId),
                        )?.toDomain() ?: throw TutorEvidenceRejectedException(requestId)
                    }
                },
            )
        }
    }

    override suspend fun recordVisualTargetEvidence(
        command: RecordTutorVisualTargetEvidenceCommand,
    ): TutorVisualTargetEvidence {
        currentCoroutineContext().ensureActive()
        val persisted = command.toPersistedVisualTargetEvidence()
        val stored = evidenceWriteGate.persist(
            requestId = command.modelTaskRequestId,
            authorizationIdentity = persisted,
            beforeAuthorization = {
                if (!TutorVisualHitProofRegistry.claim(command.hitProof)) {
                    throw TutorEvidenceRejectedException(command.modelTaskRequestId)
                }
            },
            onAuthorizationCommitted = {
                TutorVisualHitProofRegistry.finalize(command.hitProof)
            },
            onAuthorizationReleased = {
                TutorVisualHitProofRegistry.release(command.hitProof)
            },
            // Room rolls back an @Transaction before surfacing its exception to this adapter.
            isDefinitelyNotCommitted = { true },
            write = {
                withContext(Dispatchers.IO) {
                    database.recordTutorVisualTargetEvidenceUnlessCancelled(
                        persisted,
                        command.toPersistedCancellation(learnerId),
                    )
                }
            },
        )
        return (stored ?: throw TutorEvidenceRejectedException(command.modelTaskRequestId)).toDomain()
    }

    override fun cancelEvidence(requestId: String) {
        evidenceWriteGate.cancel(requestId)
    }

    override suspend fun cancelEvidence(command: CancelTutorEvidenceCommand) {
        withContext(Dispatchers.IO) {
            database.recordTutorEvidenceCancellation(command.toPersistedCancellation(learnerId))
        }
        evidenceWriteGate.cancel(command.evidenceRequestId)
    }

    override suspend fun isEvidenceCancelled(command: CancelTutorEvidenceCommand): Boolean =
        withContext(Dispatchers.IO) {
            database.isTutorEvidenceCancelled(command.toPersistedCancellation(learnerId))
        }

    override suspend fun recordMove(command: RecordTutorMoveCommand): TutorTurnResponse =
        withContext(Dispatchers.IO) {
            require(command.requestedMove != TutorMoveType.REVEAL_SOLUTION)
            database.recordTutorMove(
                PersistTutorMoveCommand(
                    sessionId = command.sessionId,
                    questionDocumentId = command.questionDocumentId,
                    revisionNumber = command.revisionNumber,
                    cycleOrdinal = command.cycleOrdinal,
                    turnOrdinal = command.turnOrdinal,
                    requestedMove = command.requestedMove.name,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toDomain()
        }

    override suspend fun revealSolution(command: RevealTutorSolutionCommand): TutorTurnResponse =
        withContext(Dispatchers.IO) {
            database.revealTutorSolution(
                PersistTutorRevealCommand(
                    learnerId = learnerId,
                    sessionId = command.sessionId,
                    questionDocumentId = command.questionDocumentId,
                    revisionNumber = command.revisionNumber,
                    cycleOrdinal = command.cycleOrdinal,
                    turnOrdinal = command.turnOrdinal,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            ).toDomain()
        }

    override suspend fun recordSolutionExposure(command: RecordTutorSolutionExposureCommand) {
        withContext(Dispatchers.IO) {
            database.recordTutorSolutionExposure(
                PersistTutorAnswerExposureCommand(
                    learnerId = learnerId,
                    sessionId = command.sessionId,
                    questionDocumentId = command.questionDocumentId,
                    revisionNumber = command.revisionNumber,
                    cycleOrdinal = command.cycleOrdinal,
                    turnOrdinal = command.turnOrdinal,
                    surfaceKind = command.surfaceKind.name,
                    modelTaskRequestId = command.modelTaskRequestId,
                    responseOrdinal = command.responseOrdinal,
                    occurredAtEpochMillis = command.occurredAtEpochMillis,
                ),
            )
        }
    }

    override suspend fun hasAnswerExposure(key: TutorAnswerExposureKey): Boolean =
        withContext(Dispatchers.IO) {
            database.readTutorAnswerExposure(
                modelTaskRequestId = key.modelTaskRequestId,
            )?.matchesAnswerExposure(
                expectedLearnerId = learnerId,
                expectedKey = key,
            ) == true
        }

    override suspend fun findRecordedAnswerExposures(
        keys: Set<TutorAnswerExposureKey>,
    ): Set<TutorAnswerExposureKey> = withContext(Dispatchers.IO) {
        if (keys.isEmpty()) return@withContext emptySet()
        val records = database.readTutorAnswerExposures(
            keys.mapTo(linkedSetOf(), TutorAnswerExposureKey::modelTaskRequestId),
        )
        records.matchingAnswerExposureKeys(
            expectedLearnerId = learnerId,
            candidates = keys,
        )
    }

    override suspend fun anchorSession(anchor: TutorSessionProblemAnchor) {
        withContext(Dispatchers.IO) {
            database.bindTutorSessionProblemAnchor(
                PersistTutorSessionAnchorCommand(
                    learnerId = learnerId,
                    sessionId = anchor.sessionId,
                    problemRevisionId = anchor.problemRevisionId,
                    practiceUnitId = anchor.practiceUnitId,
                    source = "SAVED_MISTAKE",
                    anchoredAtEpochMillis = anchor.anchoredAtEpochMillis,
                ),
            )
        }
    }
}

private fun CancelTutorEvidenceCommand.toPersistedCancellation(
    learnerId: String,
) = PersistTutorEvidenceCancellationCommand(
    learnerId = learnerId,
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    evidenceRequestId = evidenceRequestId,
    cancelledAtEpochMillis = occurredAtEpochMillis,
)

private fun RecordTutorChoiceCommand.toPersistedCancellation(
    learnerId: String,
) = PersistTutorEvidenceCancellationCommand(
    learnerId = learnerId,
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    evidenceRequestId = requireNotNull(evidenceRequestId),
    cancelledAtEpochMillis = occurredAtEpochMillis,
)

private fun RecordTutorVisualTargetEvidenceCommand.toPersistedCancellation(
    learnerId: String,
) = PersistTutorEvidenceCancellationCommand(
    learnerId = learnerId,
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    evidenceRequestId = modelTaskRequestId,
    cancelledAtEpochMillis = occurredAtEpochMillis,
)

internal class TutorEvidenceWriteGate {
    private val requests = ConcurrentHashMap<String, EvidenceWriteAuthorization>()

    fun cancel(requestId: String): Boolean {
        require(requestId.isNotBlank())
        return requests.computeIfAbsent(requestId) { EvidenceWriteAuthorization() }.cancel()
    }

    suspend fun <T> persist(
        requestId: String,
        authorizationIdentity: Any = requestId,
        beforeAuthorization: () -> Unit = {},
        onAuthorizationCommitted: () -> Unit = {},
        onAuthorizationReleased: () -> Unit = {},
        isDefinitelyNotCommitted: (Throwable) -> Boolean = { false },
        write: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        val authorization = requests.computeIfAbsent(requestId) { EvidenceWriteAuthorization() }
        return when (authorization.begin(authorizationIdentity)) {
            EvidenceWriteBegin.REJECTED -> throw TutorEvidenceRejectedException(requestId)
            EvidenceWriteBegin.REPLAY -> persistAuthorized(
                authorization = authorization,
                authorizationIdentity = authorizationIdentity,
                onAuthorizationCommitted = onAuthorizationCommitted,
                onAuthorizationReleased = onAuthorizationReleased,
                isDefinitelyNotCommitted = isDefinitelyNotCommitted,
                write = write,
            )
            EvidenceWriteBegin.STARTED ->
                persistStarted(
                    requestId = requestId,
                    authorization = authorization,
                    authorizationIdentity = authorizationIdentity,
                    beforeAuthorization = beforeAuthorization,
                    onAuthorizationCommitted = onAuthorizationCommitted,
                    onAuthorizationReleased = onAuthorizationReleased,
                    isDefinitelyNotCommitted = isDefinitelyNotCommitted,
                    write = write,
                )
        }
    }

    private suspend fun <T> persistStarted(
        requestId: String,
        authorization: EvidenceWriteAuthorization,
        authorizationIdentity: Any,
        beforeAuthorization: () -> Unit,
        onAuthorizationCommitted: () -> Unit,
        onAuthorizationReleased: () -> Unit,
        isDefinitelyNotCommitted: (Throwable) -> Boolean,
        write: suspend () -> T,
    ): T = withContext(NonCancellable) {
        try {
            beforeAuthorization()
        } catch (failure: Throwable) {
            authorization.abandonBeforeAuthorization(authorizationIdentity)
            throw failure
        }
        if (!authorization.authorizeWrite(authorizationIdentity)) {
            onAuthorizationReleased()
            throw TutorEvidenceRejectedException(requestId)
        }
        persistAuthorized(
            authorization = authorization,
            authorizationIdentity = authorizationIdentity,
            onAuthorizationCommitted = onAuthorizationCommitted,
            onAuthorizationReleased = onAuthorizationReleased,
            isDefinitelyNotCommitted = isDefinitelyNotCommitted,
            write = write,
        )
    }

    private suspend fun <T> persistAuthorized(
        authorization: EvidenceWriteAuthorization,
        authorizationIdentity: Any,
        onAuthorizationCommitted: () -> Unit,
        onAuthorizationReleased: () -> Unit,
        isDefinitelyNotCommitted: (Throwable) -> Boolean,
        write: suspend () -> T,
    ): T = withContext(NonCancellable) {
        try {
            val value = write()
            authorization.finishWrite(authorizationIdentity)
            onAuthorizationCommitted()
            value
        } catch (failure: Throwable) {
            if (
                authorization.failWrite(
                    authorizationIdentity = authorizationIdentity,
                    definitelyNotCommitted = isDefinitelyNotCommitted(failure),
                )
            ) {
                onAuthorizationReleased()
            }
            throw failure
        }
    }
}

private enum class EvidenceWriteState {
    OPEN,
    WRITING,
    AUTHORIZED_WRITING,
    AUTHORIZED_RETRY,
    COMMITTED,
    CANCELLED,
}

private enum class EvidenceWriteBegin {
    STARTED,
    REPLAY,
    REJECTED,
}

private class EvidenceWriteAuthorization {
    private var state = EvidenceWriteState.OPEN
    private var authorizationIdentity: Any? = null

    @Synchronized
    fun begin(candidateIdentity: Any): EvidenceWriteBegin = when (state) {
        EvidenceWriteState.OPEN -> {
            state = EvidenceWriteState.WRITING
            authorizationIdentity = candidateIdentity
            EvidenceWriteBegin.STARTED
        }
        EvidenceWriteState.AUTHORIZED_RETRY -> {
            if (authorizationIdentity == candidateIdentity) {
                state = EvidenceWriteState.AUTHORIZED_WRITING
                EvidenceWriteBegin.REPLAY
            } else {
                EvidenceWriteBegin.REJECTED
            }
        }
        EvidenceWriteState.COMMITTED ->
            if (authorizationIdentity == candidateIdentity) {
                EvidenceWriteBegin.REPLAY
            } else {
                EvidenceWriteBegin.REJECTED
            }
        EvidenceWriteState.WRITING,
        EvidenceWriteState.AUTHORIZED_WRITING,
        EvidenceWriteState.CANCELLED,
        -> EvidenceWriteBegin.REJECTED
    }

    @Synchronized
    fun authorizeWrite(candidateIdentity: Any): Boolean {
        if (
            state != EvidenceWriteState.WRITING ||
            authorizationIdentity != candidateIdentity
        ) {
            return false
        }
        state = EvidenceWriteState.AUTHORIZED_WRITING
        return true
    }

    @Synchronized
    fun abandonBeforeAuthorization(candidateIdentity: Any) {
        when {
            state == EvidenceWriteState.CANCELLED -> Unit
            state == EvidenceWriteState.WRITING &&
                authorizationIdentity == candidateIdentity -> {
                state = EvidenceWriteState.OPEN
                authorizationIdentity = null
            }
            else -> error("Only an uncommitted evidence write may abandon authorization")
        }
    }

    @Synchronized
    fun finishWrite(candidateIdentity: Any) {
        when {
            state == EvidenceWriteState.COMMITTED &&
                authorizationIdentity == candidateIdentity -> Unit
            state == EvidenceWriteState.AUTHORIZED_WRITING &&
                authorizationIdentity == candidateIdentity -> state = EvidenceWriteState.COMMITTED
            else -> error("Only the exact authorized evidence write may finish")
        }
    }

    @Synchronized
    fun failWrite(
        authorizationIdentity: Any,
        definitelyNotCommitted: Boolean,
    ): Boolean {
        if (
            state == EvidenceWriteState.COMMITTED &&
            this.authorizationIdentity == authorizationIdentity
        ) {
            return false
        }
        check(
            state == EvidenceWriteState.AUTHORIZED_WRITING &&
                this.authorizationIdentity == authorizationIdentity,
        ) {
            "Only the exact authorized evidence write may fail"
        }
        state = if (definitelyNotCommitted) {
            this.authorizationIdentity = null
            EvidenceWriteState.OPEN
        } else {
            EvidenceWriteState.AUTHORIZED_RETRY
        }
        return definitelyNotCommitted
    }

    @Synchronized
    fun cancel(): Boolean = when (state) {
        EvidenceWriteState.OPEN,
        EvidenceWriteState.WRITING,
        -> {
            state = EvidenceWriteState.CANCELLED
            true
        }
        EvidenceWriteState.AUTHORIZED_WRITING,
        EvidenceWriteState.AUTHORIZED_RETRY,
        EvidenceWriteState.COMMITTED,
        EvidenceWriteState.CANCELLED,
        -> false
    }
}

private fun RecordTutorChoiceCommand.toPersistedChoice() = PersistTutorChoiceCommand(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
    diagnosticStemMarkdown = diagnosticStemMarkdown,
    selectedChoiceId = selectedChoiceId,
    selectedChoiceMarkdown = selectedChoiceMarkdown,
    selectionWasCorrect = selectionWasCorrect,
    feedbackMarkdown = feedbackMarkdown,
    choiceSubmittedAtEpochMillis = occurredAtEpochMillis,
)

private fun RecordTutorVisualTargetEvidenceCommand.toPersistedVisualTargetEvidence() =
    PersistTutorVisualTargetEvidenceCommand(
        sessionId = sessionId,
        questionDocumentId = questionDocumentId,
        revisionNumber = revisionNumber,
        cycleOrdinal = anchor.cycleOrdinal,
        turnOrdinal = anchor.turnOrdinal,
        surfaceKind = anchor.surface.name,
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = anchor.responseOrdinal,
        sceneSourceKind = hitProof.presentation.sourceKind.name,
        sceneTaskRequestId = hitProof.presentation.sceneTaskRequestId,
        sceneId = hitProof.presentation.sceneId,
        sceneFingerprint = hitProof.presentation.sceneFingerprint,
        hitProofId = hitProof.proofId,
        panelId = hitProof.panelId,
        frameFingerprint = hitProof.frameFingerprint,
        stepIndex = hitProof.stepIndex,
        selectedTargetId = selectedTargetId,
        submittedAtEpochMillis = occurredAtEpochMillis,
    )

internal fun TutorAnswerExposureRecord.matchesAnswerExposure(
    expectedLearnerId: String,
    expectedKey: TutorAnswerExposureKey,
): Boolean = learnerId == expectedLearnerId &&
    sessionId == expectedKey.sessionId &&
    questionDocumentId == expectedKey.questionDocumentId &&
    questionRevisionNumber == expectedKey.revisionNumber &&
    cycleOrdinal == expectedKey.cycleOrdinal &&
    turnOrdinal == expectedKey.turnOrdinal &&
    surfaceKind == expectedKey.surfaceKind.name &&
    modelTaskRequestId == expectedKey.modelTaskRequestId &&
    responseOrdinal == expectedKey.responseOrdinal

internal fun List<TutorAnswerExposureRecord>.matchingAnswerExposureKeys(
    expectedLearnerId: String,
    candidates: Set<TutorAnswerExposureKey>,
): Set<TutorAnswerExposureKey> {
    if (isEmpty() || candidates.isEmpty()) return emptySet()
    val candidatesByRequestId = candidates.groupBy(TutorAnswerExposureKey::modelTaskRequestId)
    return buildSet {
        this@matchingAnswerExposureKeys.forEach { record ->
            candidatesByRequestId[record.modelTaskRequestId].orEmpty()
                .filterTo(this) { key ->
                    record.matchesAnswerExposure(expectedLearnerId, key)
                }
        }
    }
}

private fun TutorTurnResponseRecord.toDomain() = TutorTurnResponse(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    cycleOrdinal = cycleOrdinal,
    turnOrdinal = turnOrdinal,
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
)

private fun TutorVisualTargetEvidenceRecord.toDomain() = TutorVisualTargetEvidence(
    sessionId = sessionId,
    questionDocumentId = questionDocumentId,
    revisionNumber = revisionNumber,
    anchor = TutorVisualTurnAnchor(
        surface = TutorVisualTurnSurface.valueOf(surfaceKind),
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        responseOrdinal = responseOrdinal,
    ),
    modelTaskRequestId = modelTaskRequestId,
    sceneSourceKind = TutorVisualSceneSourceKind.valueOf(sceneSourceKind),
    sceneTaskRequestId = sceneTaskRequestId,
    sceneId = sceneId,
    sceneFingerprint = sceneFingerprint,
    hitProofId = hitProofId,
    panelId = panelId,
    frameFingerprint = frameFingerprint,
    stepIndex = stepIndex,
    selectedTargetId = selectedTargetId,
    selectionWasCorrect = selectionWasCorrect,
    submittedAtEpochMillis = submittedAtEpochMillis,
)

object TutorInteractionRepositoryFactory {
    fun create(database: StudyDatabasePort): TutorInteractionRepository =
        RoomTutorInteractionRepository(database)
}
