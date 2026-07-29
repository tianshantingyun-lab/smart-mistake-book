package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.TutorAnswerExposureOutcome
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorIntentDecision
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorMoveType
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorInteractionDatabaseInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun choiceMoveAndRevealAreDurableIdempotentAndImmutable() = runBlocking {
        val choice = choiceCommand()

        val recorded = store.recordTutorChoice(choice)
        assertEquals(1_000L, recorded.choiceSubmittedAtEpochMillis)
        assertEquals("guided-request-1", recorded.evidenceRequestId)
        assertEquals(
            recorded,
            store.recordTutorChoice(choice.copy(choiceSubmittedAtEpochMillis = 1_050)),
        )
        assertTrue(
            runCatching {
                store.recordTutorChoice(
                    choice.copy(
                        selectedChoiceId = "choice-2",
                        selectedChoiceMarkdown = "第二个判断",
                    ),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                store.recordTutorChoice(choice.copy(evidenceRequestId = "guided-request-2"))
            }.isFailure,
        )

        val move = PersistTutorMoveCommand(
            sessionId = choice.sessionId,
            questionDocumentId = choice.questionDocumentId,
            revisionNumber = choice.revisionNumber,
            cycleOrdinal = choice.cycleOrdinal,
            turnOrdinal = choice.turnOrdinal,
            requestedMove = "CHANGE_REPRESENTATION",
            occurredAtEpochMillis = 1_100,
        )
        assertNull(
            store.recordTutorMove(move.copy(sessionId = "action-only-session"))
                .evidenceRequestId,
        )
        val moved = store.recordTutorMove(move)
        assertEquals(moved, store.recordTutorMove(move.copy(occurredAtEpochMillis = 1_150)))
        assertTrue(
            runCatching {
                store.recordTutorMove(move.copy(requestedMove = "TARGET_PREREQUISITE"))
            }.isFailure,
        )

        val reveal = PersistTutorRevealCommand(
            sessionId = choice.sessionId,
            questionDocumentId = choice.questionDocumentId,
            revisionNumber = choice.revisionNumber,
            cycleOrdinal = choice.cycleOrdinal,
            turnOrdinal = choice.turnOrdinal,
            occurredAtEpochMillis = 1_200,
        )
        val revealed = store.revealTutorSolution(reveal)
        assertEquals(revealed, store.revealTutorSolution(reveal.copy(occurredAtEpochMillis = 1_250)))
        assertTrue(revealed.solutionRevealed)
        assertEquals("CHANGE_REPRESENTATION", revealed.requestedMove)
        assertEquals(listOf(revealed), store.observeTutorTurnResponses(choice.sessionId).first())
    }

    @Test
    fun durableEvidenceCancellationBlocksOnlyTheExactIdentity() = runBlocking {
        val choice = choiceCommand()
        val cancellation = PersistTutorEvidenceCancellationCommand(
            learnerId = LEARNER_ID,
            sessionId = choice.sessionId,
            questionDocumentId = choice.questionDocumentId,
            revisionNumber = choice.revisionNumber,
            evidenceRequestId = "guided-request-1",
            cancelledAtEpochMillis = 900,
        )

        store.recordTutorEvidenceCancellation(cancellation)

        assertTrue(store.isTutorEvidenceCancelled(cancellation))
        assertNull(store.recordTutorChoiceUnlessCancelled(choice, cancellation))
        assertFalse(store.isTutorEvidenceCancelled(cancellation.copy(revisionNumber = 3)))
    }

    @Test
    fun visualTargetEvidenceIsExactIdempotentAndIndependentFromChoiceAndExposureLedgers() =
        runBlocking {
            val sessionId = "visual-target-session"
            val requestId = planRequestId(sessionId)
            store.persistSucceededPlanTask(
                sessionId = sessionId,
                modelTaskRequestId = requestId,
                visualTargetId = "target-node",
            )
            val command = PersistTutorVisualTargetEvidenceCommand(
                sessionId = sessionId,
                questionDocumentId = EXPOSURE_QUESTION_DOCUMENT_ID,
                revisionNumber = 1,
                cycleOrdinal = 1,
                turnOrdinal = 1,
                surfaceKind = "PLAN",
                modelTaskRequestId = requestId,
                responseOrdinal = null,
                sceneSourceKind = "INLINE",
                sceneTaskRequestId = requestId,
                sceneId = "visual-target",
                sceneFingerprint = TutorVisualSceneFingerprint.of(visualTargetScene()),
                hitProofId = "proof-correct",
                panelId = "panel",
                frameFingerprint = "f".repeat(64),
                stepIndex = 0,
                selectedTargetId = "target-node",
                submittedAtEpochMillis = 1_300,
            )

            val recorded = store.recordTutorVisualTargetEvidence(command)
            val replayed = store.recordTutorVisualTargetEvidence(
                command.copy(submittedAtEpochMillis = 1_500),
            )

            assertTrue(recorded.selectionWasCorrect)
            assertEquals(recorded, replayed)
            assertEquals(
                listOf(recorded),
                store.observeTutorVisualTargetEvidence(sessionId).first(),
            )
            assertTrue(store.observeTutorTurnResponses(sessionId).first().isEmpty())
            assertNull(store.readTutorAnswerExposure(requestId))
            assertTrue(store.loadLearningLedger(LEARNER_ID).validPrefix.isEmpty())
            assertTrue(
                runCatching {
                    store.recordTutorVisualTargetEvidence(
                        command.copy(
                            selectedTargetId = "different-target",
                            submittedAtEpochMillis = 1_600,
                        ),
                    )
                }.exceptionOrNull() is ImmutablePayloadConflictException,
            )
        }

    @Test
    fun wrongVisibleVisualTargetIsPersistedAsNegativeEvidence() = runBlocking {
        val sessionId = "visual-target-wrong-session"
        val requestId = planRequestId(sessionId)
        store.persistSucceededPlanTask(
            sessionId = sessionId,
            modelTaskRequestId = requestId,
            visualTargetId = "expected-target",
        )

        val recorded = store.recordTutorVisualTargetEvidence(
            PersistTutorVisualTargetEvidenceCommand(
                sessionId = sessionId,
                questionDocumentId = EXPOSURE_QUESTION_DOCUMENT_ID,
                revisionNumber = 1,
                cycleOrdinal = 1,
                turnOrdinal = 1,
                surfaceKind = "PLAN",
                modelTaskRequestId = requestId,
                responseOrdinal = null,
                sceneSourceKind = "INLINE",
                sceneTaskRequestId = requestId,
                sceneId = "visual-target",
                sceneFingerprint = TutorVisualSceneFingerprint.of(visualTargetScene()),
                hitProofId = "proof-wrong",
                panelId = "panel",
                frameFingerprint = "e".repeat(64),
                stepIndex = 0,
                selectedTargetId = "wrong-visible-target",
                submittedAtEpochMillis = 1_300,
            ),
        )

        assertFalse(recorded.selectionWasCorrect)
        assertEquals(
            listOf(recorded),
            store.observeTutorVisualTargetEvidence(sessionId).first(),
        )
        assertTrue(store.observeTutorTurnResponses(sessionId).first().isEmpty())
        assertNull(store.readTutorAnswerExposure(requestId))
        assertTrue(store.loadLearningLedger(LEARNER_ID).validPrefix.isEmpty())
    }

    @Test
    fun visualTargetEvidenceWithoutAnExactReadySceneIsRejected() = runBlocking {
        val sessionId = "visual-target-missing-scene"
        val requestId = planRequestId(sessionId)
        store.persistSucceededPlanTask(
            sessionId = sessionId,
            modelTaskRequestId = requestId,
            visualTargetId = "target-node",
            includeVisualScene = false,
        )

        val failure = runCatching {
            store.recordTutorVisualTargetEvidence(
                PersistTutorVisualTargetEvidenceCommand(
                    sessionId = sessionId,
                    questionDocumentId = EXPOSURE_QUESTION_DOCUMENT_ID,
                    revisionNumber = 1,
                    cycleOrdinal = 1,
                    turnOrdinal = 1,
                    surfaceKind = "PLAN",
                    modelTaskRequestId = requestId,
                    responseOrdinal = null,
                    sceneSourceKind = "INLINE",
                    sceneTaskRequestId = requestId,
                    sceneId = "visual-target",
                    sceneFingerprint = TutorVisualSceneFingerprint.of(visualTargetScene()),
                    hitProofId = "proof-missing",
                    panelId = "panel",
                    frameFingerprint = "d".repeat(64),
                    stepIndex = 0,
                    selectedTargetId = "target-node",
                    submittedAtEpochMillis = 1_300,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is ImmutablePayloadConflictException)
        assertTrue(store.observeTutorVisualTargetEvidence(sessionId).first().isEmpty())
    }

    @Test
    fun revealRequestDoesNotMaterializeUntilVisibleAndVisibleExposureIsIdempotent() = runBlocking {
        store.seedFixture(exposureSeed())
        val sessionId = "saved-mistake-session"
        val modelTaskRequestId = planRequestId(sessionId)
        store.persistSucceededPlanTask(sessionId, modelTaskRequestId)
        store.bindTutorSessionProblemAnchor(anchorCommand(sessionId))

        assertEquals(0, store.loadLearningLedger(LEARNER_ID).validPrefix.size)
        assertNull(store.readTutorAnswerExposure(modelTaskRequestId))

        val reveal = revealCommand(sessionId)
        assertTrue(
            runCatching {
                store.recordTutorSolutionExposure(
                    planExposureCommand(reveal, modelTaskRequestId),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
        val requested = store.revealTutorSolution(reveal)

        assertTrue(requested.solutionRevealed)
        assertNull(store.readTutorAnswerExposure(modelTaskRequestId))
        assertTrue(store.loadLearningLedger(LEARNER_ID).validPrefix.isEmpty())

        val visible = store.recordTutorSolutionExposure(
            planExposureCommand(reveal, modelTaskRequestId, occurredAtEpochMillis = 1_300),
        )
        val replayed = store.recordTutorSolutionExposure(
            planExposureCommand(reveal, modelTaskRequestId, occurredAtEpochMillis = 1_500),
        )

        val exposure = requireNotNull(store.readTutorAnswerExposure(modelTaskRequestId))
        assertEquals(visible, replayed)
        assertEquals(visible, exposure)
        assertEquals(modelTaskRequestId, exposure.modelTaskRequestId)
        assertEquals(1_300L, exposure.exposedAtEpochMillis)
        assertNotNull(exposure.outcomeId)
        val outcome = store.loadLearningLedger(LEARNER_ID).validPrefix.single().event
            as TutorAnswerExposureOutcome
        assertEquals(exposure.exposureId, outcome.exposureId)
        assertEquals(REVISION_ID, outcome.problemRevisionId)
        assertEquals(UNIT_ID, outcome.practiceUnitId)
    }

    @Test
    fun unsavedVisibleAnswerReconcilesExactlyOnceAndRejectsConflictingReanchor() = runBlocking {
        store.seedFixture(exposureSeed())
        val sessionId = "pending-capture-session"
        val modelTaskRequestId = planRequestId(sessionId)
        store.persistSucceededPlanTask(sessionId, modelTaskRequestId)
        val firstReveal = revealCommand(sessionId)
        store.revealTutorSolution(firstReveal)

        store.recordTutorSolutionExposure(
            planExposureCommand(firstReveal, modelTaskRequestId),
        )
        val pending = requireNotNull(store.readTutorAnswerExposure(modelTaskRequestId))
        assertNull(pending.outcomeId)
        assertTrue(store.loadLearningLedger(LEARNER_ID).validPrefix.isEmpty())

        store.bindTutorSessionProblemAnchor(anchorCommand(sessionId, source = "DRAFT_COMMIT"))
        val materialized = requireNotNull(store.readTutorAnswerExposure(modelTaskRequestId))
        assertNotNull(materialized.outcomeId)
        store.recordTutorSolutionExposure(
            planExposureCommand(firstReveal, modelTaskRequestId, occurredAtEpochMillis = 1_500),
        )

        assertEquals(materialized, store.readTutorAnswerExposure(modelTaskRequestId))
        assertEquals(1, store.loadLearningLedger(LEARNER_ID).validPrefix.size)
        assertTrue(
            runCatching {
                store.bindTutorSessionProblemAnchor(
                    anchorCommand(sessionId).copy(practiceUnitId = ALTERNATE_UNIT_ID),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
    }

    @Test
    fun concurrentAnchorAndVisibilityRaceMaterializesOneLedgerOutcome() = runBlocking {
        store.seedFixture(exposureSeed())
        val sessionId = "anchor-visibility-race"
        val modelTaskRequestId = planRequestId(sessionId)
        store.persistSucceededPlanTask(sessionId, modelTaskRequestId)
        val reveal = revealCommand(sessionId)
        store.revealTutorSolution(reveal)

        coroutineScope {
            val anchor = async { store.bindTutorSessionProblemAnchor(anchorCommand(sessionId)) }
            val exposure = async {
                store.recordTutorSolutionExposure(
                    planExposureCommand(reveal, modelTaskRequestId),
                )
            }
            anchor.await()
            exposure.await()
        }

        assertNotNull(store.readTutorAnswerExposure(modelTaskRequestId)?.outcomeId)
        assertEquals(1, store.loadLearningLedger(LEARNER_ID).validPrefix.size)
        assertEquals(0, store.reconcileTutorAnswerExposures(LEARNER_ID))
    }

    @Test
    fun twoRespondRepliesInSameTurnUseExactRequestAndDoNotFabricateTurnResponse() = runBlocking {
        store.seedFixture(exposureSeed())
        val sessionId = "respond-exposure-session"
        val revealingRequestId = "tutor-respond:$sessionId:1"
        val hiddenRequestId = "tutor-respond:$sessionId:2"
        store.bindTutorSessionProblemAnchor(anchorCommand(sessionId))
        store.persistSucceededRespondTask(
            sessionId = sessionId,
            modelTaskRequestId = revealingRequestId,
            responseOrdinal = 1,
            solutionRevealed = true,
        )
        store.persistSucceededRespondTask(
            sessionId = sessionId,
            modelTaskRequestId = hiddenRequestId,
            responseOrdinal = 2,
            solutionRevealed = false,
        )

        assertTrue(store.observeTutorTurnResponses(sessionId).first().isEmpty())

        val exposure = store.recordTutorSolutionExposure(
            respondExposureCommand(
                sessionId = sessionId,
                modelTaskRequestId = revealingRequestId,
                responseOrdinal = 1,
            ),
        )

        assertEquals(exposure, store.readTutorAnswerExposure(revealingRequestId))
        assertEquals(revealingRequestId, exposure.modelTaskRequestId)
        assertEquals("RESPOND_REPLY", exposure.surfaceKind)
        assertEquals(1, exposure.responseOrdinal)
        assertNull(store.readTutorAnswerExposure(hiddenRequestId))
        assertTrue(
            runCatching {
                store.recordTutorSolutionExposure(
                    respondExposureCommand(
                        sessionId = sessionId,
                        modelTaskRequestId = hiddenRequestId,
                        responseOrdinal = 2,
                    ),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
        assertNull(store.readTutorAnswerExposure(hiddenRequestId))
        assertTrue(store.observeTutorTurnResponses(sessionId).first().isEmpty())
        assertEquals(1, store.loadLearningLedger(LEARNER_ID).validPrefix.size)
    }

    @Test
    fun respondExposureBeforeTheSucceededTaskTimestampIsRejected() = runBlocking {
        store.seedFixture(exposureSeed())
        val sessionId = "respond-exposure-before-task"
        val modelTaskRequestId = "tutor-respond:$sessionId:1"
        store.persistSucceededRespondTask(
            sessionId = sessionId,
            modelTaskRequestId = modelTaskRequestId,
            responseOrdinal = 1,
            solutionRevealed = true,
        )

        assertTrue(
            runCatching {
                store.recordTutorSolutionExposure(
                    respondExposureCommand(
                        sessionId = sessionId,
                        modelTaskRequestId = modelTaskRequestId,
                        responseOrdinal = 1,
                        occurredAtEpochMillis = MODEL_TASK_CREATED_AT_EPOCH_MILLIS + 2,
                    ),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
        assertNull(store.readTutorAnswerExposure(modelTaskRequestId))
    }

    @Test
    fun revealFirstThenChoiceEnrichesTheSameTurnWithoutLosingRevealOrTimestamps() = runBlocking {
        val choice = choiceCommand().copy(choiceSubmittedAtEpochMillis = 1_300)
        val revealed = store.revealTutorSolution(
            PersistTutorRevealCommand(
                sessionId = choice.sessionId,
                questionDocumentId = choice.questionDocumentId,
                revisionNumber = choice.revisionNumber,
                cycleOrdinal = choice.cycleOrdinal,
                turnOrdinal = choice.turnOrdinal,
                occurredAtEpochMillis = 1_200,
            ),
        )

        val recorded = store.recordTutorChoice(choice)

        assertEquals(
            revealed.copy(
                diagnosticStemMarkdown = choice.diagnosticStemMarkdown,
                selectedChoiceId = choice.selectedChoiceId,
                selectedChoiceMarkdown = choice.selectedChoiceMarkdown,
                selectionWasCorrect = choice.selectionWasCorrect,
                feedbackMarkdown = choice.feedbackMarkdown,
                choiceSubmittedAtEpochMillis = choice.choiceSubmittedAtEpochMillis,
            ),
            recorded,
        )
        assertEquals(1_200, recorded.submittedAtEpochMillis)
        assertEquals(1_200, recorded.updatedAtEpochMillis)
        assertEquals(listOf(recorded), store.observeTutorTurnResponses(choice.sessionId).first())
    }

    @Test
    fun moveFirstThenChoiceEnrichesTheSameTurnAndStillRejectsChoiceConflicts() = runBlocking {
        val choice = choiceCommand().copy(choiceSubmittedAtEpochMillis = 1_200)
        val moved = store.recordTutorMove(
            PersistTutorMoveCommand(
                sessionId = choice.sessionId,
                questionDocumentId = choice.questionDocumentId,
                revisionNumber = choice.revisionNumber,
                cycleOrdinal = choice.cycleOrdinal,
                turnOrdinal = choice.turnOrdinal,
                requestedMove = "CHANGE_REPRESENTATION",
                occurredAtEpochMillis = 1_100,
            ),
        )
        assertTrue(
            runCatching {
                store.recordTutorChoice(choice.copy(questionDocumentId = "different-question"))
            }.isFailure,
        )
        assertTrue(
            runCatching {
                store.recordTutorChoice(choice.copy(revisionNumber = choice.revisionNumber + 1))
            }.isFailure,
        )

        val recorded = store.recordTutorChoice(choice)

        assertEquals(
            moved.copy(
                diagnosticStemMarkdown = choice.diagnosticStemMarkdown,
                selectedChoiceId = choice.selectedChoiceId,
                selectedChoiceMarkdown = choice.selectedChoiceMarkdown,
                selectionWasCorrect = choice.selectionWasCorrect,
                feedbackMarkdown = choice.feedbackMarkdown,
                choiceSubmittedAtEpochMillis = choice.choiceSubmittedAtEpochMillis,
            ),
            recorded,
        )
        assertEquals(1_100, recorded.submittedAtEpochMillis)
        assertEquals(1_100, recorded.updatedAtEpochMillis)
        assertEquals(
            recorded,
            store.recordTutorChoice(choice.copy(choiceSubmittedAtEpochMillis = 1_250)),
        )
        assertTrue(
            runCatching {
                store.recordTutorChoice(
                    choice.copy(
                        selectedChoiceId = "choice-2",
                        selectedChoiceMarkdown = "第二个判断",
                    ),
                )
            }.isFailure,
        )
        assertEquals(listOf(recorded), store.observeTutorTurnResponses(choice.sessionId).first())
    }

    @Test
    fun actionOnlyMovesAndRevealsAreDurableIdempotentAndIdentityBound() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-action-reopen-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            var persistentStore = StudyDatabaseFactory.open(context, databaseName)
            val move = PersistTutorMoveCommand(
                sessionId = "explanation-session",
                questionDocumentId = "question-document-action",
                revisionNumber = 3,
                cycleOrdinal = 1,
                turnOrdinal = 1,
                requestedMove = "CHANGE_REPRESENTATION",
                occurredAtEpochMillis = 1_000,
            )
            val moved = persistentStore.recordTutorMove(move)
            assertEquals(moved, persistentStore.recordTutorMove(move.copy(occurredAtEpochMillis = 1_050)))
            assertNull(moved.diagnosticStemMarkdown)
            assertNull(moved.selectedChoiceId)
            assertNull(moved.selectionWasCorrect)
            assertNull(moved.choiceSubmittedAtEpochMillis)

            val reveal = PersistTutorRevealCommand(
                sessionId = move.sessionId,
                questionDocumentId = move.questionDocumentId,
                revisionNumber = move.revisionNumber,
                cycleOrdinal = move.cycleOrdinal,
                turnOrdinal = move.turnOrdinal,
                occurredAtEpochMillis = 1_100,
            )
            val modelTaskRequestId = planRequestId(move.sessionId)
            persistentStore.persistSucceededPlanTask(
                sessionId = move.sessionId,
                modelTaskRequestId = modelTaskRequestId,
                questionDocumentId = move.questionDocumentId,
                revisionNumber = move.revisionNumber,
            )
            val revealed = persistentStore.revealTutorSolution(reveal)
            assertEquals(
                revealed,
                persistentStore.revealTutorSolution(
                    PersistTutorRevealCommand(
                        sessionId = move.sessionId,
                        questionDocumentId = move.questionDocumentId,
                        revisionNumber = move.revisionNumber,
                        cycleOrdinal = move.cycleOrdinal,
                        turnOrdinal = move.turnOrdinal,
                        occurredAtEpochMillis = 1_150,
                    ),
                ),
            )
            assertTrue(revealed.solutionRevealed)
            assertEquals("CHANGE_REPRESENTATION", revealed.requestedMove)
            assertNull(persistentStore.readTutorAnswerExposure(modelTaskRequestId))
            val visibleExposure = persistentStore.recordTutorSolutionExposure(
                planExposureCommand(
                    reveal,
                    modelTaskRequestId,
                    occurredAtEpochMillis = 1_200,
                ),
            )
            assertEquals(
                visibleExposure,
                persistentStore.recordTutorSolutionExposure(
                    planExposureCommand(
                        reveal,
                        modelTaskRequestId,
                        occurredAtEpochMillis = 1_250,
                    ),
                ),
            )
            assertTrue(
                runCatching {
                    persistentStore.recordTutorMove(
                        move.copy(questionDocumentId = "different-question"),
                    )
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    persistentStore.recordTutorMove(move.copy(requestedMove = "DEEPEN_REASONING"))
                }.isFailure,
            )
            assertTrue(runCatching { move.copy(requestedMove = "UNKNOWN_MOVE") }.isFailure)
            assertTrue(runCatching { move.copy(requestedMove = "REVEAL_SOLUTION") }.isFailure)
            assertTrue(
                runCatching {
                    persistentStore.revealTutorSolution(
                        PersistTutorRevealCommand(
                            sessionId = move.sessionId,
                            questionDocumentId = move.questionDocumentId,
                            revisionNumber = move.revisionNumber + 1,
                            cycleOrdinal = move.cycleOrdinal,
                            turnOrdinal = move.turnOrdinal,
                            occurredAtEpochMillis = 1_200,
                        ),
                    )
                }.isFailure,
            )
            val revealFirst = PersistTutorRevealCommand(
                sessionId = move.sessionId,
                questionDocumentId = move.questionDocumentId,
                revisionNumber = move.revisionNumber,
                cycleOrdinal = 1,
                turnOrdinal = 2,
                occurredAtEpochMillis = 1_300,
            )
            persistentStore.revealTutorSolution(revealFirst)
            val revealThenMove = persistentStore.recordTutorMove(
                move.copy(turnOrdinal = 2, occurredAtEpochMillis = 1_250),
            )
            assertEquals(1_250, revealThenMove.submittedAtEpochMillis)
            assertEquals(1_300, revealThenMove.updatedAtEpochMillis)
            assertTrue(revealThenMove.solutionRevealed)

            persistentStore.close()
            persistentStore = StudyDatabaseFactory.open(context, databaseName)
            assertEquals(
                listOf(revealed, revealThenMove),
                persistentStore.observeTutorTurnResponses(move.sessionId).first(),
            )
            val pendingAfterRestart = requireNotNull(
                persistentStore.readTutorAnswerExposure(modelTaskRequestId),
            )
            assertEquals(visibleExposure, pendingAfterRestart)
            assertNull(pendingAfterRestart.outcomeId)
            persistentStore.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    @Test
    fun versionNineDatabaseMigratesAndAcceptsTutorInteractions() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-interaction-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 9)
            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val recorded = migrated.recordTutorChoice(choiceCommand())
            assertEquals(
                listOf(recorded),
                migrated.observeTutorTurnResponses(recorded.sessionId).first(),
            )
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    @Test
    fun versionElevenChoiceRowMigratesWithoutLoss() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-v11-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 11)
            seedVersionElevenChoice(context, databaseName)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val restored = migrated.observeTutorTurnResponses("legacy-session").first().single()
            assertEquals("legacy-question", restored.questionDocumentId)
            assertEquals(4, restored.revisionNumber)
            assertEquals("旧版诊断题干", restored.diagnosticStemMarkdown)
            assertEquals("legacy-choice", restored.selectedChoiceId)
            assertEquals("旧版选择", restored.selectedChoiceMarkdown)
            assertEquals(true, restored.selectionWasCorrect)
            assertEquals("旧版反馈", restored.feedbackMarkdown)
            assertEquals("CONNECT_KNOWLEDGE", restored.requestedMove)
            assertTrue(restored.solutionRevealed)
            assertEquals(2_000, restored.submittedAtEpochMillis)
            assertEquals(2_100, restored.updatedAtEpochMillis)
            assertEquals(2_000L, restored.choiceSubmittedAtEpochMillis)

            val action = migrated.recordTutorMove(
                PersistTutorMoveCommand(
                    sessionId = "post-migration-action",
                    questionDocumentId = "post-migration-question",
                    revisionNumber = 1,
                    cycleOrdinal = 1,
                    turnOrdinal = 1,
                    requestedMove = "CHANGE_REPRESENTATION",
                    occurredAtEpochMillis = 3_000,
                ),
            )
            assertNull(action.diagnosticStemMarkdown)
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    @Test
    fun versionThirteenMigrationBackfillsOnlyExistingChoicesWithApproximateTime() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-v13-timestamp-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 13)
            seedVersionThirteenChoiceAndAction(context, databaseName)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            val restored = migrated.observeTutorTurnResponses("legacy-v13-session").first()
            assertEquals(2, restored.size)
            assertEquals(4_000, restored[0].submittedAtEpochMillis)
            assertEquals(4_000L, restored[0].choiceSubmittedAtEpochMillis)
            assertNull(restored[1].diagnosticStemMarkdown)
            assertNull(restored[1].choiceSubmittedAtEpochMillis)
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    private fun seedVersionElevenChoice(context: Context, databaseName: String) {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            database.execSQL(
                """
                INSERT INTO tutor_turn_response (
                    session_id,
                    question_document_id,
                    revision_number,
                    cycle_ordinal,
                    turn_ordinal,
                    diagnostic_stem_markdown,
                    selected_choice_id,
                    selected_choice_markdown,
                    selection_was_correct,
                    feedback_markdown,
                    requested_move,
                    solution_revealed,
                    submitted_at_epoch_millis,
                    updated_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    "legacy-session",
                    "legacy-question",
                    4,
                    2,
                    3,
                    "旧版诊断题干",
                    "legacy-choice",
                    "旧版选择",
                    1,
                    "旧版反馈",
                    "CONNECT_KNOWLEDGE",
                    1,
                    2_000,
                    2_100,
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun seedVersionThirteenChoiceAndAction(context: Context, databaseName: String) {
        val database = SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        )
        try {
            val insertSql =
                """
                INSERT INTO tutor_turn_response (
                    session_id,
                    question_document_id,
                    revision_number,
                    cycle_ordinal,
                    turn_ordinal,
                    diagnostic_stem_markdown,
                    selected_choice_id,
                    selected_choice_markdown,
                    selection_was_correct,
                    feedback_markdown,
                    requested_move,
                    solution_revealed,
                    submitted_at_epoch_millis,
                    updated_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            database.execSQL(
                insertSql,
                arrayOf<Any?>(
                    "legacy-v13-session",
                    "legacy-v13-question",
                    1,
                    1,
                    1,
                    "旧选择题干",
                    "legacy-choice",
                    "旧选择",
                    1,
                    "旧反馈",
                    null,
                    0,
                    4_000,
                    4_100,
                ),
            )
            database.execSQL(
                insertSql,
                arrayOf<Any?>(
                    "legacy-v13-session",
                    "legacy-v13-question",
                    1,
                    1,
                    2,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "CHANGE_REPRESENTATION",
                    0,
                    4_200,
                    4_200,
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun choiceCommand() = PersistTutorChoiceCommand(
        sessionId = "mistake-tutor-session-1",
        questionDocumentId = "question-document-1",
        revisionNumber = 2,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        diagnosticStemMarkdown = "哪一步最能区分两个解法？",
        selectedChoiceId = "choice-1",
        selectedChoiceMarkdown = "先检查定义域",
        selectionWasCorrect = true,
        feedbackMarkdown = "定义域会约束后续每一步，因此这是有效起点。",
        evidenceRequestId = "guided-request-1",
        choiceSubmittedAtEpochMillis = 1_000,
    )

    private fun revealCommand(sessionId: String) = PersistTutorRevealCommand(
        learnerId = LEARNER_ID,
        sessionId = sessionId,
        questionDocumentId = EXPOSURE_QUESTION_DOCUMENT_ID,
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        occurredAtEpochMillis = 1_000,
    )

    private fun planExposureCommand(
        reveal: PersistTutorRevealCommand,
        modelTaskRequestId: String,
        occurredAtEpochMillis: Long = reveal.occurredAtEpochMillis,
    ) = PersistTutorAnswerExposureCommand(
        learnerId = reveal.learnerId,
        sessionId = reveal.sessionId,
        questionDocumentId = reveal.questionDocumentId,
        revisionNumber = reveal.revisionNumber,
        cycleOrdinal = reveal.cycleOrdinal,
        turnOrdinal = reveal.turnOrdinal,
        surfaceKind = "PLAN_SOLUTION",
        modelTaskRequestId = modelTaskRequestId,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun respondExposureCommand(
        sessionId: String,
        modelTaskRequestId: String,
        responseOrdinal: Int,
        occurredAtEpochMillis: Long = 1_300,
    ) = PersistTutorAnswerExposureCommand(
        learnerId = LEARNER_ID,
        sessionId = sessionId,
        questionDocumentId = EXPOSURE_QUESTION_DOCUMENT_ID,
        revisionNumber = 1,
        cycleOrdinal = 1,
        turnOrdinal = 1,
        surfaceKind = "RESPOND_REPLY",
        modelTaskRequestId = modelTaskRequestId,
        responseOrdinal = responseOrdinal,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private suspend fun StudyDatabasePort.persistSucceededPlanTask(
        sessionId: String,
        modelTaskRequestId: String,
        questionDocumentId: String = EXPOSURE_QUESTION_DOCUMENT_ID,
        revisionNumber: Int = 1,
        visualTargetId: String? = null,
        includeVisualScene: Boolean = true,
    ) {
        val input = TutorPlanInput(
            sessionId = sessionId,
            draftRevisionNumber = revisionNumber,
            subject = "MATH",
            questionDocument = questionDocument(questionDocumentId),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
        )
        persistSucceededModelTask(
            request = ModelTaskRequest(
                requestId = modelTaskRequestId,
                input = input,
                occurredAtEpochMillis = MODEL_TASK_CREATED_AT_EPOCH_MILLIS,
            ),
            output = TutorPlanOutput(
                sessionId = sessionId,
                draftRevisionNumber = revisionNumber,
                questionDocumentId = questionDocumentId,
                plan = TutorTurnPlan(
                    openingMarkdown = "先梳理题目条件。",
                    solutionMarkdown = "完整解法与最终答案。",
                    alternateMethodMarkdown = "也可以换一种方法验证。",
                    difficultyReasonMarkdown = "关键在于识别约束。",
                    targetedEvidenceLabels = emptyList(),
                    inferredKnowledgeLabels = listOf("函数"),
                    interactionDirective = visualTargetId?.let { targetId ->
                        TutorInteractionDirective.VisualTarget(
                            promptMarkdown = "请点出图中的目标。",
                            targetId = targetId,
                        )
                    },
                    visualScene = visualTargetId
                        ?.takeIf { includeVisualScene }
                        ?.let { visualTargetScene() },
                ),
                modelVersion = "instrumented-test-model",
            ),
        )
    }

    @Test
    fun versionThirtyMigratesAndPersistsEvidenceCancellationAcrossReopen() = runBlocking {
        store.close()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "tutor-cancellation-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val cancellation = PersistTutorEvidenceCancellationCommand(
            learnerId = LEARNER_ID,
            sessionId = "migration-session",
            questionDocumentId = "migration-question",
            revisionNumber = 1,
            evidenceRequestId = "migration-request",
            cancelledAtEpochMillis = 500,
        )
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 30)
            var migrated = StudyDatabaseFactory.open(context, databaseName)
            migrated.recordTutorEvidenceCancellation(cancellation)
            migrated.close()

            migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(migrated.isTutorEvidenceCancelled(cancellation))
            migrated.close()
        } finally {
            context.deleteDatabase(databaseName)
            store = StudyDatabaseFactory.openInMemory(context)
        }
    }

    private fun visualTargetScene() = TutorVisualDocumentScene(
        sceneId = "visual-target",
        title = "目标选择",
        panels = listOf(TutorVisualPanel("panel", TutorVisualPanelKind.DIAGRAM_2D)),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "target-node",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "目标",
            ),
            TutorVisual2DNodeElement(
                elementId = "expected-target",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "预期目标",
            ),
            TutorVisual2DNodeElement(
                elementId = "wrong-visible-target",
                panelId = "panel",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "其他可见目标",
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "pick",
                label = "选择目标",
                focusElementIds = listOf(
                    "target-node",
                    "expected-target",
                    "wrong-visible-target",
                ),
                primaryRelationElementId = "target-node",
            ),
        ),
        fallbackMarkdown = "请选择图中的目标。",
        accessibilitySummary = "三个可选择目标。",
    )

    private suspend fun StudyDatabasePort.persistSucceededRespondTask(
        sessionId: String,
        modelTaskRequestId: String,
        responseOrdinal: Int,
        solutionRevealed: Boolean,
    ) {
        val input = TutorRespondInput(
            sessionId = sessionId,
            draftRevisionNumber = 1,
            subject = "MATH",
            questionDocument = questionDocument(EXPOSURE_QUESTION_DOCUMENT_ID),
            relevantLearningEvidence = emptyList(),
            projectionIsCurrent = true,
            responseOrdinal = responseOrdinal,
            cycleOrdinal = 1,
            turnOrdinal = 1,
            studentMessage = if (solutionRevealed) "请告诉我答案。" else "请继续解释。",
            requestedMove = if (solutionRevealed) TutorMoveType.REVEAL_SOLUTION else null,
            explanationMode = TutorExplanationMode.DIRECT,
        )
        persistSucceededModelTask(
            request = ModelTaskRequest(
                requestId = modelTaskRequestId,
                input = input,
                occurredAtEpochMillis = MODEL_TASK_CREATED_AT_EPOCH_MILLIS,
            ),
            output = TutorRespondOutput(
                sessionId = sessionId,
                draftRevisionNumber = 1,
                questionDocumentId = EXPOSURE_QUESTION_DOCUMENT_ID,
                responseOrdinal = responseOrdinal,
                cycleOrdinal = 1,
                turnOrdinal = 1,
                messageMarkdown = if (solutionRevealed) {
                    "完整解法与最终答案。"
                } else {
                    "先检查题目条件。"
                },
                solutionRevealed = solutionRevealed,
                intentDecision = TutorIntentDecision.currentQuestionDefault(),
                modelVersion = "instrumented-test-model",
            ),
        )
    }

    private suspend fun StudyDatabasePort.persistSucceededModelTask(
        request: ModelTaskRequest,
        output: ModelTaskOutput,
    ) {
        var snapshot = createModelTask(
            CreateModelTaskCommand(
                taskId = "task:${request.requestId}",
                request = request,
                requestFingerprint = ModelTaskFingerprint.of(request),
                occurredAtEpochMillis = request.occurredAtEpochMillis,
            ),
        ).snapshot
        snapshot = transitionModelTask(
            transitionCommand(
                snapshot = snapshot,
                nextStatus = ModelTaskStatus.QUEUED,
                stage = ModelTaskStage.PREPARING,
                occurredAtEpochMillis = request.occurredAtEpochMillis + 1,
            ),
        ).snapshot
        snapshot = transitionModelTask(
            transitionCommand(
                snapshot = snapshot,
                nextStatus = ModelTaskStatus.RUNNING,
                stage = ModelTaskStage.VALIDATING_OUTPUT,
                occurredAtEpochMillis = request.occurredAtEpochMillis + 2,
            ),
        ).snapshot
        snapshot = transitionModelTask(
            transitionCommand(
                snapshot = snapshot,
                nextStatus = ModelTaskStatus.SUCCEEDED,
                stage = ModelTaskStage.COMPLETE,
                occurredAtEpochMillis = request.occurredAtEpochMillis + 3,
                output = output,
            ),
        ).snapshot
        check(snapshot.status == ModelTaskStatus.SUCCEEDED)
    }

    private fun transitionCommand(
        snapshot: ModelTaskSnapshot,
        nextStatus: ModelTaskStatus,
        stage: ModelTaskStage,
        occurredAtEpochMillis: Long,
        output: ModelTaskOutput? = null,
    ) = TransitionModelTaskCommand(
        taskId = snapshot.taskId,
        expectedStateVersion = snapshot.stateVersion,
        expectedStatus = snapshot.status,
        nextStatus = nextStatus,
        stage = stage,
        userMessage = nextStatus.name,
        attemptCount = snapshot.attemptCount,
        output = output,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun questionDocument(questionDocumentId: String) = QuestionDocument(
        id = questionDocumentId,
        blocks = listOf(ContentBlock.Paragraph("stem", "求函数的单调区间。")),
    )

    private fun planRequestId(sessionId: String): String = "tutor-plan:$sessionId"

    private fun anchorCommand(
        sessionId: String,
        source: String = "SAVED_MISTAKE",
    ) = PersistTutorSessionAnchorCommand(
        learnerId = LEARNER_ID,
        sessionId = sessionId,
        problemRevisionId = REVISION_ID,
        practiceUnitId = UNIT_ID,
        source = source,
        anchoredAtEpochMillis = 1_100,
    )

    private fun exposureSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(PROBLEM_ID, "problem-fingerprint", "MATH", 100),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = REVISION_ID,
                problemId = PROBLEM_ID,
                revisionNumber = 1,
                title = "函数题",
                problemMarkdown = "求函数的单调区间。",
                answerSpecId = "answer-spec-exposure",
                answerSpecSnapshot = "增区间与减区间",
                answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                sourceType = "IMPORT",
                sourceReference = null,
                contentFingerprint = "revision-fingerprint",
                createdAtEpochMillis = 200,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                unitKey = "whole",
                unitKind = "WHOLE",
                title = "函数题",
                promptMarkdown = "求单调区间。",
                estimatedSeconds = 120,
                createdAtEpochMillis = 300,
            ),
            PracticeUnitSeedRecord(
                practiceUnitId = ALTERNATE_UNIT_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                unitKey = "alternate",
                unitKind = "SUBPROBLEM",
                title = "函数题另一单元",
                promptMarkdown = "说明理由。",
                estimatedSeconds = 60,
                createdAtEpochMillis = 301,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "entry-exposure",
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                currentRevisionId = REVISION_ID,
                sourceKey = null,
                acceptedAtEpochMillis = 400,
                updatedAtEpochMillis = 400,
            ),
        ),
    )

    private companion object {
        const val LEARNER_ID = "learner:local"
        const val PROBLEM_ID = "problem-exposure"
        const val REVISION_ID = "revision-exposure"
        const val UNIT_ID = "practice-exposure"
        const val ALTERNATE_UNIT_ID = "practice-exposure-alternate"
        const val EXPOSURE_QUESTION_DOCUMENT_ID = "question-document-exposure"
        const val MODEL_TASK_CREATED_AT_EPOCH_MILLIS = 100L
    }
}
