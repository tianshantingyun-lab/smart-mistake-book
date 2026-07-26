package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorAnswerExposureCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorMoveCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorRevealCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorSessionAnchorCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorMoveCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorSolutionExposureCommand
import com.tingyun.smartmistakebook.core.domain.RevealTutorSolutionCommand
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorInteractionRepository
import com.tingyun.smartmistakebook.core.domain.TutorEvidenceRejectedException
import com.tingyun.smartmistakebook.core.domain.TutorSessionProblemAnchor
import com.tingyun.smartmistakebook.core.domain.TutorTurnResponse
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

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
                        database.recordTutorChoice(persisted).toDomain()
                    }
                },
                discard = {
                    withContext(Dispatchers.IO) {
                        check(database.discardTutorChoice(persisted)) {
                            "A revoked tutor evidence write could not be discarded"
                        }
                    }
                },
            )
        }
    }

    override fun cancelEvidence(requestId: String) {
        evidenceWriteGate.cancel(requestId)
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

internal class TutorEvidenceWriteGate {
    private val requests = ConcurrentHashMap<String, EvidenceWriteAuthorization>()

    fun cancel(requestId: String): Boolean {
        require(requestId.isNotBlank())
        return requests.computeIfAbsent(requestId) { EvidenceWriteAuthorization() }.cancel()
    }

    suspend fun <T> persist(
        requestId: String,
        write: suspend () -> T,
        discard: suspend () -> Unit,
    ): T {
        currentCoroutineContext().ensureActive()
        val authorization = requests.computeIfAbsent(requestId) { EvidenceWriteAuthorization() }
        return when (authorization.begin()) {
            EvidenceWriteBegin.REJECTED -> throw TutorEvidenceRejectedException(requestId)
            EvidenceWriteBegin.REPLAY -> withContext(NonCancellable) { write() }
            EvidenceWriteBegin.STARTED -> persistStarted(requestId, authorization, write, discard)
        }
    }

    private suspend fun <T> persistStarted(
        requestId: String,
        authorization: EvidenceWriteAuthorization,
        write: suspend () -> T,
        discard: suspend () -> Unit,
    ): T = withContext(NonCancellable) {
        val value = try {
            write()
        } catch (failure: Throwable) {
            when (authorization.failWrite()) {
                EvidenceWriteFailure.RETRYABLE -> throw failure
                EvidenceWriteFailure.REVOKED -> {
                    discard()
                    throw TutorEvidenceRejectedException(requestId)
                }
            }
        }
        if (!authorization.finalizeWrite()) {
            discard()
            throw TutorEvidenceRejectedException(requestId)
        }
        value
    }
}

private enum class EvidenceWriteState {
    OPEN,
    WRITING,
    COMMITTED,
    CANCELLED,
}

private enum class EvidenceWriteBegin {
    STARTED,
    REPLAY,
    REJECTED,
}

private enum class EvidenceWriteFailure {
    RETRYABLE,
    REVOKED,
}

private class EvidenceWriteAuthorization {
    private val state = AtomicReference(EvidenceWriteState.OPEN)

    fun begin(): EvidenceWriteBegin = when {
        state.compareAndSet(EvidenceWriteState.OPEN, EvidenceWriteState.WRITING) ->
            EvidenceWriteBegin.STARTED
        state.get() == EvidenceWriteState.COMMITTED -> EvidenceWriteBegin.REPLAY
        else -> EvidenceWriteBegin.REJECTED
    }

    fun finalizeWrite(): Boolean =
        state.compareAndSet(EvidenceWriteState.WRITING, EvidenceWriteState.COMMITTED)

    fun failWrite(): EvidenceWriteFailure =
        if (state.compareAndSet(EvidenceWriteState.WRITING, EvidenceWriteState.OPEN)) {
            EvidenceWriteFailure.RETRYABLE
        } else {
            check(state.get() == EvidenceWriteState.CANCELLED) {
                "Only cancellation may race with an in-flight evidence write"
            }
            EvidenceWriteFailure.REVOKED
        }

    fun cancel(): Boolean {
        while (true) {
            when (val current = state.get()) {
                EvidenceWriteState.COMMITTED,
                EvidenceWriteState.CANCELLED,
                -> return false
                EvidenceWriteState.OPEN,
                EvidenceWriteState.WRITING,
                -> if (state.compareAndSet(current, EvidenceWriteState.CANCELLED)) return true
            }
        }
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

object TutorInteractionRepositoryFactory {
    fun create(database: StudyDatabasePort): TutorInteractionRepository =
        RoomTutorInteractionRepository(database)
}
