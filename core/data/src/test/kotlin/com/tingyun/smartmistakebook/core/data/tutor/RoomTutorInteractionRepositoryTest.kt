package com.tingyun.smartmistakebook.core.data.tutor

import com.tingyun.smartmistakebook.core.database.PersistTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorEvidenceCancellationCommand
import com.tingyun.smartmistakebook.core.database.PersistTutorChoiceCommand
import com.tingyun.smartmistakebook.core.database.StudyDatabasePort
import com.tingyun.smartmistakebook.core.database.TutorAnswerExposureRecord
import com.tingyun.smartmistakebook.core.database.TutorTurnResponseRecord
import com.tingyun.smartmistakebook.core.database.TutorVisualTargetEvidenceRecord
import com.tingyun.smartmistakebook.core.domain.RecordTutorVisualTargetEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.CancelTutorEvidenceCommand
import com.tingyun.smartmistakebook.core.domain.RecordTutorChoiceCommand
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureKey
import com.tingyun.smartmistakebook.core.domain.TutorAnswerExposureSurfaceKind
import com.tingyun.smartmistakebook.core.domain.TutorEvidenceRejectedException
import com.tingyun.smartmistakebook.core.model.TutorVisualHitProofRegistry
import com.tingyun.smartmistakebook.core.model.TutorVisualPresentationIdentity
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneSourceKind
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.runBlocking

class RoomTutorInteractionRepositoryTest {
    @Test
    fun `guided choice preserves its exact evidence request identity through the database adapter`() =
        runBlocking {
            var persistedChoice: PersistTutorChoiceCommand? = null
            val database = Proxy.newProxyInstance(
                StudyDatabasePort::class.java.classLoader,
                arrayOf(StudyDatabasePort::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "recordTutorChoiceUnlessCancelled" -> {
                        val choice = arguments.orEmpty().first() as PersistTutorChoiceCommand
                        persistedChoice = choice
                        TutorTurnResponseRecord(
                            sessionId = choice.sessionId,
                            questionDocumentId = choice.questionDocumentId,
                            revisionNumber = choice.revisionNumber,
                            cycleOrdinal = choice.cycleOrdinal,
                            turnOrdinal = choice.turnOrdinal,
                            diagnosticStemMarkdown = choice.diagnosticStemMarkdown,
                            selectedChoiceId = choice.selectedChoiceId,
                            selectedChoiceMarkdown = choice.selectedChoiceMarkdown,
                            selectionWasCorrect = choice.selectionWasCorrect,
                            feedbackMarkdown = choice.feedbackMarkdown,
                            requestedMove = null,
                            solutionRevealed = false,
                            choiceSubmittedAtEpochMillis = choice.choiceSubmittedAtEpochMillis,
                            submittedAtEpochMillis = choice.choiceSubmittedAtEpochMillis,
                            updatedAtEpochMillis = choice.choiceSubmittedAtEpochMillis,
                            evidenceRequestId = choice.evidenceRequestId,
                        )
                    }
                    "close" -> Unit
                    else -> error("Unexpected database call: ${method.name}")
                }
            } as StudyDatabasePort
            val command = RecordTutorChoiceCommand(
                sessionId = "session-choice",
                questionDocumentId = "question-choice",
                revisionNumber = 1,
                cycleOrdinal = 1,
                turnOrdinal = 1,
                diagnosticStemMarkdown = "Which premise matters?",
                selectedChoiceId = "choice-a",
                selectedChoiceMarkdown = "The exact premise",
                selectionWasCorrect = true,
                feedbackMarkdown = "That premise controls the next step.",
                occurredAtEpochMillis = 1_000,
                evidenceRequestId = "guided-request-exact",
            )

            val recorded = RoomTutorInteractionRepository(database).recordChoice(command)

            assertEquals("guided-request-exact", persistedChoice?.evidenceRequestId)
            assertEquals("guided-request-exact", recorded.evidenceRequestId)
        }

    @Test
    fun `durable cancellation survives repository reconstruction with exact identity`() =
        runBlocking {
            val cancelled = mutableSetOf<PersistTutorEvidenceCancellationCommand>()
            val database = Proxy.newProxyInstance(
                StudyDatabasePort::class.java.classLoader,
                arrayOf(StudyDatabasePort::class.java),
            ) { _, method, arguments ->
                val command = arguments.orEmpty().firstOrNull()
                    as? PersistTutorEvidenceCancellationCommand
                when (method.name) {
                    "recordTutorEvidenceCancellation" -> {
                        cancelled += requireNotNull(command)
                        Unit
                    }
                    "isTutorEvidenceCancelled" -> requireNotNull(command) in cancelled
                    "close" -> Unit
                    else -> error("Unexpected database call: ${method.name}")
                }
            } as StudyDatabasePort
            val command = CancelTutorEvidenceCommand(
                sessionId = "session-1",
                questionDocumentId = "question-1",
                revisionNumber = 2,
                evidenceRequestId = "request-1",
                occurredAtEpochMillis = 100,
            )

            RoomTutorInteractionRepository(database).cancelEvidence(command)
            val restored = RoomTutorInteractionRepository(database)

            assertTrue(restored.isEvidenceCancelled(command))
            assertFalse(restored.isEvidenceCancelled(command.copy(revisionNumber = 3)))
        }

    @Test
    fun `answer exposure requires the exact learner question revision and turn`() {
        val key = TutorAnswerExposureKey(
            sessionId = "session-1",
            questionDocumentId = "question-1",
            revisionNumber = 2,
            cycleOrdinal = 3,
            turnOrdinal = 4,
            surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
            modelTaskRequestId = "respond-request-4",
            responseOrdinal = 4,
        )
        val record = TutorAnswerExposureRecord(
            exposureId = "exposure-1",
            learnerId = "learner:local",
            sessionId = key.sessionId,
            questionDocumentId = key.questionDocumentId,
            questionRevisionNumber = key.revisionNumber,
            cycleOrdinal = key.cycleOrdinal,
            turnOrdinal = key.turnOrdinal,
            surfaceKind = key.surfaceKind.name,
            modelTaskRequestId = key.modelTaskRequestId,
            responseOrdinal = key.responseOrdinal,
            exposedAtEpochMillis = 1_000,
            outcomeId = null,
        )

        assertTrue(record.matchesAnswerExposure("learner:local", key))
        assertFalse(record.matchesAnswerExposure("learner:other", key))
        assertFalse(
            record.copy(sessionId = "session-2")
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(questionDocumentId = "question-2")
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(questionRevisionNumber = 3)
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(cycleOrdinal = 4)
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(turnOrdinal = 5)
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(modelTaskRequestId = "respond-request-other")
                .matchesAnswerExposure("learner:local", key),
        )
        assertFalse(
            record.copy(responseOrdinal = 5)
                .matchesAnswerExposure("learner:local", key),
        )
    }

    @Test
    fun `batch exposure matching returns only exact requested identities`() {
        val exact = TutorAnswerExposureKey(
            sessionId = "session-1",
            questionDocumentId = "question-1",
            revisionNumber = 2,
            cycleOrdinal = 3,
            turnOrdinal = 4,
            surfaceKind = TutorAnswerExposureSurfaceKind.RESPOND_REPLY,
            modelTaskRequestId = "respond-request-4",
            responseOrdinal = 4,
        )
        val record = TutorAnswerExposureRecord(
            exposureId = "exposure-1",
            learnerId = "learner:local",
            sessionId = exact.sessionId,
            questionDocumentId = exact.questionDocumentId,
            questionRevisionNumber = exact.revisionNumber,
            cycleOrdinal = exact.cycleOrdinal,
            turnOrdinal = exact.turnOrdinal,
            surfaceKind = exact.surfaceKind.name,
            modelTaskRequestId = exact.modelTaskRequestId,
            responseOrdinal = exact.responseOrdinal,
            exposedAtEpochMillis = 1_000,
            outcomeId = null,
        )
        val sameRequestWrongTurn = exact.copy(turnOrdinal = 9)
        val absent = exact.copy(
            modelTaskRequestId = "respond-request-5",
            responseOrdinal = 5,
        )

        val matches = listOf(record).matchingAnswerExposureKeys(
            expectedLearnerId = "learner:local",
            candidates = setOf(exact, sameRequestWrongTurn, absent),
        )

        assertEquals(setOf(exact), matches)
    }

    @Test
    fun `rolled back visual evidence write accepts a fresh tap and rejects the old payload`() =
        runBlocking {
            var attempts = 0
            val database = visualEvidenceDatabase { command ->
                attempts += 1
                if (attempts == 1) error("Room transaction rolled back")
                command.toVisualEvidenceRecord()
            }
            val repository = RoomTutorInteractionRepository(database)
            val oldCommand = visualEvidenceCommand(frameFingerprint = "b".repeat(64))

            val firstFailure = runCatching {
                repository.recordVisualTargetEvidence(oldCommand)
            }.exceptionOrNull()
            val freshCommand = visualEvidenceCommand(frameFingerprint = "c".repeat(64))
            val stored = repository.recordVisualTargetEvidence(freshCommand)
            val oldReplayFailure = runCatching {
                repository.recordVisualTargetEvidence(oldCommand)
            }.exceptionOrNull()

            assertTrue(firstFailure is IllegalStateException)
            assertEquals(freshCommand.hitProof.proofId, stored.hitProofId)
            assertTrue(oldReplayFailure is TutorEvidenceRejectedException)
            assertEquals(2, attempts)
        }

    @Test
    fun `cancellation cannot revoke evidence after storage authorization`() = runBlocking {
        val gate = TutorEvidenceWriteGate()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var stored = false

        supervisorScope {
            val lateWrite = async {
                gate.persist(
                    requestId = "evidence-3",
                    write = {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        stored = true
                        "stored"
                    },
                )
            }

            writeStarted.await()
            assertFalse(gate.cancel("evidence-3"))
            releaseWrite.complete(Unit)

            assertEquals("stored", lateWrite.await())
            assertTrue(stored)
        }
    }

    @Test
    fun `cancel reports whether it won the atomic evidence finalization race`() = runBlocking {
        val gate = TutorEvidenceWriteGate()

        assertEquals(
            "stored",
            gate.persist(
                requestId = "evidence-finalized",
                write = { "stored" },
            ),
        )

        assertFalse(gate.cancel("evidence-finalized"))
        assertTrue(gate.cancel("evidence-never-started"))
        val failure = runCatching {
            gate.persist(
                requestId = "evidence-never-started",
                write = { error("Revoked evidence must not start writing") },
            )
        }.exceptionOrNull()
        assertTrue(failure is TutorEvidenceRejectedException)
    }

    @Test
    fun `transient write failure allows the same evidence request to retry`() = runBlocking {
        val gate = TutorEvidenceWriteGate()
        var attempts = 0

        val firstFailure = runCatching {
            gate.persist(
                requestId = "evidence-retry",
                write = {
                    attempts += 1
                    error("temporary database failure")
                },
            )
        }.exceptionOrNull()
        val retried = gate.persist(
            requestId = "evidence-retry",
            write = {
                attempts += 1
                "stored"
            },
        )

        assertTrue(firstFailure is IllegalStateException)
        assertEquals("stored", retried)
        assertEquals(2, attempts)
    }

    @Test
    fun `authorization evidence is consumed once across an authorized storage retry`() =
        runBlocking {
            val gate = TutorEvidenceWriteGate()
            var authorizationChecks = 0

            val firstFailure = runCatching {
                gate.persist(
                    requestId = "evidence-proof-retry",
                    beforeAuthorization = { authorizationChecks += 1 },
                    write = { error("temporary database failure") },
                )
            }.exceptionOrNull()
            val retried = gate.persist(
                requestId = "evidence-proof-retry",
                beforeAuthorization = { authorizationChecks += 1 },
                write = { "stored" },
            )

            assertTrue(firstFailure is IllegalStateException)
            assertEquals("stored", retried)
            assertEquals(1, authorizationChecks)
        }

    @Test
    fun `authorized retry rejects a different evidence payload`() = runBlocking {
        val gate = TutorEvidenceWriteGate()
        val firstFailure = runCatching {
            gate.persist(
                requestId = "evidence-payload",
                authorizationIdentity = "payload-a",
                write = { error("unknown commit result") },
            )
        }.exceptionOrNull()

        val conflictingFailure = runCatching {
            gate.persist(
                requestId = "evidence-payload",
                authorizationIdentity = "payload-b",
                write = { error("A different payload must never write") },
            )
        }.exceptionOrNull()
        val exactRetry = gate.persist(
            requestId = "evidence-payload",
            authorizationIdentity = "payload-a",
            write = { "stored" },
        )

        assertTrue(firstFailure is IllegalStateException)
        assertTrue(conflictingFailure is TutorEvidenceRejectedException)
        assertEquals("stored", exactRetry)
    }

    @Test
    fun `definite no-commit failure releases authorization for a fresh claim`() = runBlocking {
        val gate = TutorEvidenceWriteGate()
        var claims = 0
        var releases = 0

        val firstFailure = runCatching {
            gate.persist(
                requestId = "evidence-no-commit",
                authorizationIdentity = "first-payload",
                beforeAuthorization = { claims += 1 },
                onAuthorizationReleased = { releases += 1 },
                isDefinitelyNotCommitted = { true },
                write = { error("transaction rolled back") },
            )
        }.exceptionOrNull()
        val retried = gate.persist(
            requestId = "evidence-no-commit",
            authorizationIdentity = "fresh-payload",
            beforeAuthorization = { claims += 1 },
            write = { "stored" },
        )

        assertTrue(firstFailure is IllegalStateException)
        assertEquals("stored", retried)
        assertEquals(2, claims)
        assertEquals(1, releases)
    }

    @Test
    fun `failed authorized write stays irrevocable and can be replayed`() = runBlocking {
        val gate = TutorEvidenceWriteGate()
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var stored = false

        supervisorScope {
            val failedWrite = async {
                gate.persist(
                    requestId = "evidence-cancelled-failure",
                    write = {
                        writeStarted.complete(Unit)
                        releaseWrite.await()
                        stored = true
                        error("database reported failure after a partial write")
                    },
                )
            }

            writeStarted.await()
            assertFalse(gate.cancel("evidence-cancelled-failure"))
            releaseWrite.complete(Unit)

            val failure = runCatching { failedWrite.await() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(stored)
            assertEquals(
                "stored",
                gate.persist("evidence-cancelled-failure") { "stored" },
            )
        }
    }
}

private fun visualEvidenceCommand(
    frameFingerprint: String,
): RecordTutorVisualTargetEvidenceCommand {
    val requestId = "visual-evidence-room-rollback"
    val presentation = TutorVisualPresentationIdentity(
        ownerModelTaskRequestId = requestId,
        sourceKind = TutorVisualSceneSourceKind.INLINE,
        sceneTaskRequestId = requestId,
        sceneId = "scene-room-rollback",
        sceneFingerprint = "a".repeat(64),
    )
    val proof = TutorVisualHitProofRegistry.issue(
        presentation = presentation,
        panelId = "panel-room-rollback",
        frameFingerprint = frameFingerprint,
        stepIndex = 0,
        selectedTargetId = "target-room-rollback",
        eligibleTargetIds = setOf("target-room-rollback"),
    )
    return RecordTutorVisualTargetEvidenceCommand(
        sessionId = "session-room-rollback",
        questionDocumentId = "question-room-rollback",
        revisionNumber = 1,
        anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.PLAN,
            cycleOrdinal = 1,
            turnOrdinal = 1,
        ),
        modelTaskRequestId = requestId,
        hitProof = proof,
        occurredAtEpochMillis = 1_000,
    )
}

private fun visualEvidenceDatabase(
    write: (PersistTutorVisualTargetEvidenceCommand) -> TutorVisualTargetEvidenceRecord,
): StudyDatabasePort =
    Proxy.newProxyInstance(
        StudyDatabasePort::class.java.classLoader,
        arrayOf(StudyDatabasePort::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "recordTutorVisualTargetEvidence",
            "recordTutorVisualTargetEvidenceUnlessCancelled",
            ->
                write(arguments.orEmpty().first() as PersistTutorVisualTargetEvidenceCommand)
            "close" -> Unit
            else -> error("Unexpected database call: ${method.name}")
        }
    } as StudyDatabasePort

private fun PersistTutorVisualTargetEvidenceCommand.toVisualEvidenceRecord() =
    TutorVisualTargetEvidenceRecord(
        sessionId = sessionId,
        questionDocumentId = questionDocumentId,
        revisionNumber = revisionNumber,
        cycleOrdinal = cycleOrdinal,
        turnOrdinal = turnOrdinal,
        surfaceKind = surfaceKind,
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = responseOrdinal,
        sceneSourceKind = sceneSourceKind,
        sceneTaskRequestId = sceneTaskRequestId,
        sceneId = sceneId,
        sceneFingerprint = sceneFingerprint,
        hitProofId = hitProofId,
        panelId = panelId,
        frameFingerprint = frameFingerprint,
        stepIndex = stepIndex,
        selectedTargetId = selectedTargetId,
        selectionWasCorrect = true,
        submittedAtEpochMillis = submittedAtEpochMillis,
    )
