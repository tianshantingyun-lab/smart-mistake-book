package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CaptureSourceAssetRef
import com.tingyun.smartmistakebook.core.model.ContentBlock
import com.tingyun.smartmistakebook.core.model.ModelTaskFingerprint
import com.tingyun.smartmistakebook.core.model.ModelTaskOutput
import com.tingyun.smartmistakebook.core.model.ModelTaskRequest
import com.tingyun.smartmistakebook.core.model.ModelTaskSnapshot
import com.tingyun.smartmistakebook.core.model.ModelTaskStage
import com.tingyun.smartmistakebook.core.model.ModelTaskStatus
import com.tingyun.smartmistakebook.core.model.QuestionDocument
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestKind
import com.tingyun.smartmistakebook.core.model.TutorEvidenceRequestStatus
import com.tingyun.smartmistakebook.core.model.TutorExplanationMode
import com.tingyun.smartmistakebook.core.model.TutorCurrentSessionVisualIntent
import com.tingyun.smartmistakebook.core.model.TutorInteractionDirective
import com.tingyun.smartmistakebook.core.model.TutorPlanInput
import com.tingyun.smartmistakebook.core.model.TutorPlanOutput
import com.tingyun.smartmistakebook.core.model.TutorTurnPlan
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeElement
import com.tingyun.smartmistakebook.core.model.TutorVisual2DNodeKind
import com.tingyun.smartmistakebook.core.model.TutorVisualDocumentScene
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateInput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerateOutput
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationDecision
import com.tingyun.smartmistakebook.core.model.TutorVisualGenerationRequest
import com.tingyun.smartmistakebook.core.model.TutorVisualPanel
import com.tingyun.smartmistakebook.core.model.TutorVisualPanelKind
import com.tingyun.smartmistakebook.core.model.TutorVisualSceneFingerprint
import com.tingyun.smartmistakebook.core.model.TutorVisualStep
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnAnchor
import com.tingyun.smartmistakebook.core.model.TutorVisualTurnSurface
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrentTutorInteractionSessionInstrumentedTest {
    @Test
    fun encryptedFreeResponseOutboxNeverPersistsRawAnswerAcrossRecoveryAndTerminalPaths(): Unit =
        runBlocking {
            val context = context()
            val successDatabase = "free-response-outbox-success-${System.nanoTime()}.db"
            val revokedDatabase = "free-response-outbox-revoked-${System.nanoTime()}.db"
            val answer = "FR-9f2a7c1e-\"向量\"-\\-Q8vL3mN6xP4k"
            val escapedAnswer = answer.replace("\\", "\\\\").replace("\"", "\\\"")
            try {
                lateinit var prepared: PreparedFreeResponseOutbox
                StudyDatabaseFactory.openPreCutoverForTest(context, successDatabase) { NOW }
                    .use { store ->
                        prepared = prepareFreeResponseOutbox(store, answer, "success")
                        assertEquals(
                            CurrentTutorFreeResponseActionClaimDisposition.CLAIMED,
                            prepared.claim.disposition,
                        )
                        assertSensitiveBytesAbsent(context, successDatabase, answer, escapedAnswer)

                        val abandoned = store.acquireCurrentTutorFreeResponseDispatch(
                            prepared.acquire("process-one", NOW),
                        )
                        assertTrue(abandoned is CurrentTutorFreeResponseDispatchAcquireResult.Acquired)
                        assertSensitiveBytesAbsent(context, successDatabase, answer, escapedAnswer)
                        // Deliberately close without release/complete: this is the process-death point.
                    }

                StudyDatabaseFactory.openPreCutoverForTest(context, successDatabase) { NOW + 1L }
                    .use { store ->
                        val restoredInteraction = checkNotNull(
                            store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
                        )
                        assertEquals(1, restoredInteraction.scope.attemptOrdinal)
                        assertEquals(0, restoredInteraction.scope.hintCount)
                        val reclaimed = store.acquireCurrentTutorFreeResponseDispatch(
                            prepared.acquire("process-two", NOW + 1L),
                        ) as CurrentTutorFreeResponseDispatchAcquireResult.Acquired
                        assertEquals(
                            CurrentTutorFreeResponseDispatchMutationResult.APPLIED,
                            store.releaseCurrentTutorFreeResponseDispatch(
                                ReleaseCurrentTutorFreeResponseDispatchCommand(
                                    learnerId = LEARNER_A,
                                    sessionId = VISIBLE_CONVERSATION,
                                    actionToken = prepared.actionToken,
                                    leaseToken = reclaimed.lease.leaseToken,
                                    occurredAtEpochMillis = NOW + 2L,
                                ),
                            ),
                        )
                        assertSensitiveBytesAbsent(context, successDatabase, answer, escapedAnswer)

                        val retry = store.acquireCurrentTutorFreeResponseDispatch(
                            prepared.acquire(
                                "process-two",
                                NOW + 2L + TUTOR_FREE_RESPONSE_SECOND_RETRY_DELAY_MILLIS,
                            ),
                        ) as CurrentTutorFreeResponseDispatchAcquireResult.Acquired
                        assertEquals(
                            CurrentTutorFreeResponseDispatchMutationResult.APPLIED,
                            store.completeCurrentTutorFreeResponseDispatch(
                                CompleteCurrentTutorFreeResponseDispatchCommand(
                                    learnerId = LEARNER_A,
                                    sessionId = VISIBLE_CONVERSATION,
                                    actionToken = prepared.actionToken,
                                    leaseToken = retry.lease.leaseToken,
                                    candidateIdempotencyKey = sha256("candidate-success"),
                                    candidateReceiptFingerprint = sha256("receipt-success"),
                                    occurredAtEpochMillis =
                                        NOW + 3L + TUTOR_FREE_RESPONSE_SECOND_RETRY_DELAY_MILLIS,
                                ),
                            ),
                        )
                        assertSensitiveBytesAbsent(context, successDatabase, answer, escapedAnswer)
                    }
                assertTerminalOutboxWiped(context, successDatabase, prepared.actionToken, "COMPLETED")
                assertSensitiveBytesAbsent(context, successDatabase, answer, escapedAnswer)

                lateinit var revoked: PreparedFreeResponseOutbox
                StudyDatabaseFactory.openPreCutoverForTest(context, revokedDatabase) { NOW }
                    .use { store ->
                        revoked = prepareFreeResponseOutbox(store, answer, "revoked")
                        val current = checkNotNull(
                            store.readCurrentTutorSessionHostWork(LEARNER_A, VISIBLE_CONVERSATION),
                        )
                        assertEquals(
                            CurrentTutorSessionHostWorkWriteDisposition.APPLIED,
                            store.revokeCurrentTutorSessionHostWork(
                                RevokeCurrentTutorSessionHostWorkCommand(
                                    learnerId = LEARNER_A,
                                    sessionId = VISIBLE_CONVERSATION,
                                    expectedWorkId = current.workId,
                                    expectedStateVersion = current.stateVersion,
                                    expectedStateFingerprint = current.stateFingerprint,
                                    reason = CurrentTutorSessionHostWorkRevocationReason.SESSION_ENDED,
                                    occurredAtEpochMillis = NOW + 1L,
                                ),
                            ).disposition,
                        )
                        assertSensitiveBytesAbsent(context, revokedDatabase, answer, escapedAnswer)
                    }
                assertTerminalOutboxWiped(context, revokedDatabase, revoked.actionToken, "FAILED_CLOSED")
                assertSensitiveBytesAbsent(context, revokedDatabase, answer, escapedAnswer)
            } finally {
                context.deleteDatabase(successDatabase)
                context.deleteDatabase(revokedDatabase)
            }
        }

    @Test
    fun tamperedEncryptedFreeResponseFailsClosedAndWipesCiphertextAfterRecovery(): Unit =
        runBlocking {
            val context = context()
            val databaseName = "free-response-outbox-tamper-${System.nanoTime()}.db"
            val answer = "FR-tamper-1d42b59e-密文不可继续派发"
            lateinit var prepared: PreparedFreeResponseOutbox
            try {
                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }
                    .use { store ->
                        prepared = prepareFreeResponseOutbox(store, answer, "tamper")
                    }
                tamperEncryptedOutboxAnswer(
                    context = context,
                    databaseName = databaseName,
                    actionToken = prepared.actionToken,
                )

                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW + 1L }
                    .use { store ->
                        assertEquals(
                            CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed,
                            store.acquireCurrentTutorFreeResponseDispatch(
                                prepared.acquire("tamper-recovery", NOW + 1L),
                            ),
                        )
                    }

                assertTerminalOutboxWiped(
                    context = context,
                    databaseName = databaseName,
                    actionToken = prepared.actionToken,
                    expectedStatus = "FAILED_CLOSED",
                )
                assertSensitiveBytesAbsent(
                    context = context,
                    databaseName = databaseName,
                    rawAnswer = answer,
                    jsonEscapedAnswer = answer,
                )
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun freeResponseRetryBackoffAndAttemptCapSurviveExactClockBoundaries(): Unit = runBlocking {
        val context = context()
        val databaseName = "free-response-outbox-backoff-${System.nanoTime()}.db"
        val answer = "FR-backoff-17f3c9d1-不应无限重试"
        lateinit var prepared: PreparedFreeResponseOutbox
        try {
            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }.use { store ->
                prepared = prepareFreeResponseOutbox(store, answer, "backoff")
                val first = store.acquireCurrentTutorFreeResponseDispatch(
                    prepared.acquire("backoff-process", NOW),
                ) as CurrentTutorFreeResponseDispatchAcquireResult.Acquired
                assertEquals(
                    CurrentTutorFreeResponseDispatchMutationResult.APPLIED,
                    store.releaseCurrentTutorFreeResponseDispatch(
                        first.lease.releaseAt(NOW + 1L),
                    ),
                )
                val firstRetryAt = NOW + 1L + TUTOR_FREE_RESPONSE_FIRST_RETRY_DELAY_MILLIS
                assertEquals(
                    CurrentTutorFreeResponseDispatchAcquireResult.Busy,
                    store.acquireCurrentTutorFreeResponseDispatch(
                        prepared.acquire("backoff-process", firstRetryAt - 1L),
                    ),
                )
                val second = store.acquireCurrentTutorFreeResponseDispatch(
                    prepared.acquire("backoff-process", firstRetryAt),
                ) as CurrentTutorFreeResponseDispatchAcquireResult.Acquired
                assertEquals(
                    CurrentTutorFreeResponseDispatchMutationResult.APPLIED,
                    store.releaseCurrentTutorFreeResponseDispatch(
                        second.lease.releaseAt(firstRetryAt + 1L),
                    ),
                )
                val secondRetryAt =
                    firstRetryAt + 1L + TUTOR_FREE_RESPONSE_SECOND_RETRY_DELAY_MILLIS
                assertEquals(
                    CurrentTutorFreeResponseDispatchAcquireResult.Busy,
                    store.acquireCurrentTutorFreeResponseDispatch(
                        prepared.acquire("backoff-process", secondRetryAt - 1L),
                    ),
                )
                val third = store.acquireCurrentTutorFreeResponseDispatch(
                    prepared.acquire("backoff-process", secondRetryAt),
                ) as CurrentTutorFreeResponseDispatchAcquireResult.Acquired
                assertEquals(
                    CurrentTutorFreeResponseDispatchMutationResult.APPLIED,
                    store.releaseCurrentTutorFreeResponseDispatch(
                        third.lease.releaseAt(secondRetryAt + 1L),
                    ),
                )
                assertEquals(
                    CurrentTutorFreeResponseDispatchAcquireResult.FailedClosed,
                    store.acquireCurrentTutorFreeResponseDispatch(
                        prepared.acquire("backoff-process", secondRetryAt + 2L),
                    ),
                )
            }
            assertTerminalOutboxWiped(context, databaseName, prepared.actionToken, "FAILED_CLOSED")
            assertOutboxAttemptCount(context, databaseName, prepared.actionToken, 3)
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun freeResponseAbsoluteRetentionBoundaryWipesOnDatabaseOpenWithoutSessionRecovery(): Unit =
        runBlocking {
            val context = context()
            val databaseName = "free-response-outbox-retention-${System.nanoTime()}.db"
            val answer = "FR-retention-a81f03e7-到期即擦除"
            val escapedAnswer = answer.replace("\\", "\\\\").replace("\"", "\\\"")
            lateinit var prepared: PreparedFreeResponseOutbox
            val discardAt = NOW + TUTOR_FREE_RESPONSE_OUTBOX_RETENTION_MILLIS
            try {
                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }
                    .use { store ->
                        prepared = prepareFreeResponseOutbox(store, answer, "retention")
                    }
                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { discardAt - 1L }
                    .use { store ->
                        assertTrue(
                            store.acquireCurrentTutorFreeResponseDispatch(
                                prepared.acquire("process-before-boundary", discardAt - 1L),
                            ) is CurrentTutorFreeResponseDispatchAcquireResult.Acquired,
                        )
                        // Close without releasing to emulate process death at the retention boundary.
                    }
                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { discardAt }
                    .close()
                assertTerminalOutboxWiped(
                    context,
                    databaseName,
                    prepared.actionToken,
                    "FAILED_CLOSED",
                )
                assertSensitiveBytesAbsent(context, databaseName, answer, escapedAnswer)
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun stagingRejectsPolicyObservedByAnotherCoordinatorAfterDurableEpochChanges(): Unit =
        runBlocking {
            val context = context()
            val databaseName = "current-tutor-policy-race-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            try {
                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }
                    .use { firstCoordinator ->
                        createAuthority(
                            firstCoordinator,
                            LEARNER_A,
                            AUTHORITY_A,
                            TURN_A,
                            TutorExplanationMode.DIRECT,
                        )
                        val activation = activation(
                            learnerId = LEARNER_A,
                            authorityConversationId = AUTHORITY_A,
                            turnReceiptId = TURN_A,
                            seed = "policy-race",
                            explanationMode = TutorExplanationMode.DIRECT,
                        )
                        val firstPolicy = checkNotNull(
                            firstCoordinator.persistCurrentTutorSessionPolicy(
                                PersistCurrentTutorSessionPolicyCommand(
                                    learnerId = LEARNER_A,
                                    sessionId = activation.conversationId,
                                    explanationMode = TutorExplanationMode.DIRECT,
                                    modeVersion = 1L,
                                    learningWritesAllowed = true,
                                    learningWritePermissionVersion = 1L,
                                    visualIntent = TutorCurrentSessionVisualIntent.NONE,
                                    visualIntentVersion = 0L,
                                    occurredAtEpochMillis = NOW,
                                ),
                            ).record,
                        )
                        val staleStage = hostWork(activation).copy(
                            expectedPolicyStateFingerprint = firstPolicy.stateFingerprint,
                        )

                        StudyDatabaseFactory.openPreCutoverForTest(
                            context,
                            databaseName,
                        ) { NOW + 1L }.use { secondCoordinator ->
                            assertEquals(
                                CurrentTutorSessionHostWorkWriteDisposition.APPLIED,
                                secondCoordinator.persistCurrentTutorSessionPolicy(
                                    PersistCurrentTutorSessionPolicyCommand(
                                        learnerId = LEARNER_A,
                                        sessionId = activation.conversationId,
                                        explanationMode = TutorExplanationMode.DIRECT,
                                        modeVersion = 1L,
                                        learningWritesAllowed = true,
                                        learningWritePermissionVersion = 1L,
                                        visualIntent = TutorCurrentSessionVisualIntent.USER_EXPLICIT,
                                        visualIntentVersion = 1L,
                                        occurredAtEpochMillis = NOW + 1L,
                                    ),
                                ).disposition,
                            )
                        }

                        assertEquals(
                            CurrentTutorSessionHostWorkWriteDisposition.REJECTED,
                            firstCoordinator.stageCurrentTutorSessionHostWork(staleStage).disposition,
                        )
                        assertNull(
                            firstCoordinator.readCurrentTutorSessionHostWork(
                                LEARNER_A,
                                activation.conversationId,
                            ),
                        )
                    }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun policyEpochSurvivesRestartBeforeAnyHostWorkExists(): Unit = runBlocking {
        val context = context()
        val databaseName = "current-tutor-policy-restart-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }.use { store ->
                val written = store.persistCurrentTutorSessionPolicy(
                    PersistCurrentTutorSessionPolicyCommand(
                        learnerId = LEARNER_A,
                        sessionId = VISIBLE_CONVERSATION,
                        explanationMode = TutorExplanationMode.GUIDED,
                        modeVersion = 3L,
                        learningWritesAllowed = true,
                        learningWritePermissionVersion = 2L,
                        visualIntent = TutorCurrentSessionVisualIntent.USER_EXPLICIT,
                        visualIntentVersion = 5L,
                        occurredAtEpochMillis = NOW,
                    ),
                )
                assertEquals(CurrentTutorSessionHostWorkWriteDisposition.APPLIED, written.disposition)
                assertNull(
                    store.readCurrentTutorSessionHostWork(LEARNER_A, VISIBLE_CONVERSATION),
                )
            }

            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW + 1 }.use { store ->
                val restored = store.readCurrentTutorSessionPolicy(
                    LEARNER_A,
                    VISIBLE_CONVERSATION,
                )
                assertEquals(TutorExplanationMode.GUIDED, restored?.explanationMode)
                assertEquals(3L, restored?.modeVersion)
                assertEquals(true, restored?.learningWritesAllowed)
                assertEquals(2L, restored?.learningWritePermissionVersion)
                assertEquals(TutorCurrentSessionVisualIntent.USER_EXPLICIT, restored?.visualIntent)
                assertEquals(5L, restored?.visualIntentVersion)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun currentScopeRejectsOlderActivationAndSurvivesDatabaseRestart(): Unit = runBlocking {
        val context = context()
        val databaseName = "current-tutor-restart-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }.use { store ->
                createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
                assertEquals(
                    CurrentTutorInteractionActivationDisposition.ACTIVATED,
                    store.activateCurrentTutorInteraction(
                        activation(
                            learnerId = LEARNER_A,
                            authorityConversationId = AUTHORITY_A,
                            turnReceiptId = TURN_A,
                            revision = 2,
                            seed = "newer",
                        ),
                    ).disposition,
                )
                assertEquals(
                    CurrentTutorInteractionActivationDisposition.STALE,
                    store.activateCurrentTutorInteraction(
                        activation(
                            learnerId = LEARNER_A,
                            authorityConversationId = AUTHORITY_A,
                            turnReceiptId = TURN_A,
                            revision = 1,
                            seed = "older",
                        ),
                    ).disposition,
                )
            }

            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW + 1 }.use { store ->
                val restored = store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION)
                assertNotNull(restored)
                assertEquals(2, restored?.scope?.questionRevisionNumber)
                assertEquals(0L, restored?.head?.stateVersion)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun stagedHostWorkFencesInteractionUntilActivationAndRevocationIsTerminal(): Unit = runBlocking {
        StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
            createAuthority(
                store,
                LEARNER_A,
                AUTHORITY_A,
                TURN_A,
                TutorExplanationMode.DIRECT,
            )
            val activation = activation(
                learnerId = LEARNER_A,
                authorityConversationId = AUTHORITY_A,
                turnReceiptId = TURN_A,
                seed = "host-work",
                explanationMode = TutorExplanationMode.DIRECT,
            )
            val staged = store.stageCurrentTutorSessionHostWork(hostWork(activation))
            assertEquals(CurrentTutorSessionHostWorkWriteDisposition.APPLIED, staged.disposition)
            assertEquals(
                CurrentTutorInteractionActivationDisposition.ACTIVATED,
                store.activateCurrentTutorInteraction(activation).disposition,
            )
            assertNull(
                store.readCurrentTutorInteraction(LEARNER_A, activation.conversationId),
            )

            val stagedRecord = checkNotNull(staged.record)
            val marked = store.markCurrentTutorSessionHostWorkActive(
                MarkCurrentTutorSessionHostWorkActiveCommand(
                    learnerId = LEARNER_A,
                    sessionId = activation.conversationId,
                    expectedWorkId = stagedRecord.workId,
                    expectedStateVersion = stagedRecord.stateVersion,
                    expectedStateFingerprint = stagedRecord.stateFingerprint,
                    expectedTargetScopeId = activation.scopeId,
                    expectedTargetActivationFingerprint = activation.activationFingerprint,
                    occurredAtEpochMillis = NOW,
                ),
            )
            assertEquals(CurrentTutorSessionHostWorkWriteDisposition.APPLIED, marked.disposition)
            assertNotNull(
                store.readCurrentTutorInteraction(LEARNER_A, activation.conversationId),
            )

            val activeRecord = checkNotNull(marked.record)
            val revoked = store.revokeCurrentTutorSessionHostWork(
                RevokeCurrentTutorSessionHostWorkCommand(
                    learnerId = LEARNER_A,
                    sessionId = activation.conversationId,
                    expectedWorkId = activeRecord.workId,
                    expectedStateVersion = activeRecord.stateVersion,
                    expectedStateFingerprint = activeRecord.stateFingerprint,
                    reason = CurrentTutorSessionHostWorkRevocationReason.SESSION_ENDED,
                    occurredAtEpochMillis = NOW,
                ),
            )
            assertEquals(CurrentTutorSessionHostWorkWriteDisposition.APPLIED, revoked.disposition)
            assertNull(
                store.readCurrentTutorInteraction(LEARNER_A, activation.conversationId),
            )
            assertEquals(
                CurrentTutorInteractionAppendDisposition.REJECTED,
                store.appendCurrentTutorInteraction(exposure(activation)).disposition,
            )
            val revokedRecord = checkNotNull(revoked.record)
            assertEquals(
                CurrentTutorSessionHostWorkWriteDisposition.REJECTED,
                store.revokeCurrentTutorSessionHostWork(
                    RevokeCurrentTutorSessionHostWorkCommand(
                        learnerId = LEARNER_A,
                        sessionId = activation.conversationId,
                        expectedWorkId = revokedRecord.workId,
                        expectedStateVersion = revokedRecord.stateVersion,
                        expectedStateFingerprint = revokedRecord.stateFingerprint,
                        reason = CurrentTutorSessionHostWorkRevocationReason.NEW_QUESTION,
                        occurredAtEpochMillis = NOW,
                    ),
                ).disposition,
            )
        }
    }

    @Test
    fun stagedHostWorkSurvivesRestartButRemainsHiddenUntilExactContentIsActivated(): Unit =
        runBlocking {
            val context = context()
            val databaseName = "current-tutor-host-recovery-${System.nanoTime()}.db"
            context.deleteDatabase(databaseName)
            lateinit var activation: ActivateCurrentTutorInteractionCommand
            lateinit var staged: CurrentTutorSessionHostWorkRecord
            try {
                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }.use { store ->
                    createAuthority(
                        store,
                        LEARNER_A,
                        AUTHORITY_A,
                        TURN_A,
                        TutorExplanationMode.DIRECT,
                    )
                    activation = activation(
                        learnerId = LEARNER_A,
                        authorityConversationId = AUTHORITY_A,
                        turnReceiptId = TURN_A,
                        seed = "host-recovery",
                        explanationMode = TutorExplanationMode.DIRECT,
                    )
                    staged = checkNotNull(
                        store.stageCurrentTutorSessionHostWork(hostWork(activation)).record,
                    )
                    store.activateCurrentTutorInteraction(activation)
                    assertNull(store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION))
                }

                StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW + 1 }.use { store ->
                    assertEquals(
                        CurrentTutorSessionHostWorkStatus.STAGED,
                        store.readCurrentTutorSessionHostWork(LEARNER_A, VISIBLE_CONVERSATION)?.status,
                    )
                    assertNull(store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION))
                    assertEquals(
                        CurrentTutorSessionHostWorkWriteDisposition.APPLIED,
                        store.markCurrentTutorSessionHostWorkActive(
                            MarkCurrentTutorSessionHostWorkActiveCommand(
                                learnerId = LEARNER_A,
                                sessionId = VISIBLE_CONVERSATION,
                                expectedWorkId = staged.workId,
                                expectedStateVersion = staged.stateVersion,
                                expectedStateFingerprint = staged.stateFingerprint,
                                expectedTargetScopeId = activation.scopeId,
                                expectedTargetActivationFingerprint = activation.activationFingerprint,
                                occurredAtEpochMillis = NOW + 1,
                            ),
                        ).disposition,
                    )
                    assertNotNull(
                        store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun directiveAndConstrainedPresentationCannotBeReplacedAtSameRequestVersion(): Unit =
        runBlocking {
            StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
                createAuthority(
                    store,
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    TutorExplanationMode.DIRECT,
                )
                val activation = activation(
                    learnerId = LEARNER_A,
                    authorityConversationId = AUTHORITY_A,
                    turnReceiptId = TURN_A,
                    seed = "content-binding",
                    explanationMode = TutorExplanationMode.DIRECT,
                )
                assertEquals(
                    CurrentTutorSessionHostWorkWriteDisposition.REJECTED,
                    store.stageCurrentTutorSessionHostWork(
                        hostWork(activation).copy(
                            authorityDirectiveFingerprint = sha256("substituted-directive"),
                        ),
                    ).disposition,
                )

                val staged = checkNotNull(
                    store.stageCurrentTutorSessionHostWork(
                        hostWork(
                            activation,
                            constrainedContentFingerprint = sha256("substituted-content"),
                        ),
                    ).record,
                )
                store.activateCurrentTutorInteraction(activation)
                assertEquals(
                    CurrentTutorSessionHostWorkWriteDisposition.STALE,
                    store.markCurrentTutorSessionHostWorkActive(
                        MarkCurrentTutorSessionHostWorkActiveCommand(
                            learnerId = LEARNER_A,
                            sessionId = activation.conversationId,
                            expectedWorkId = staged.workId,
                            expectedStateVersion = staged.stateVersion,
                            expectedStateFingerprint = staged.stateFingerprint,
                            expectedTargetScopeId = activation.scopeId,
                            expectedTargetActivationFingerprint = activation.activationFingerprint,
                            occurredAtEpochMillis = NOW,
                        ),
                    ).disposition,
                )
                assertNull(store.readCurrentTutorInteraction(LEARNER_A, activation.conversationId))
            }
        }

    @Test
    fun equalEventAndIdempotencyIdsRemainIsolatedAcrossLearners(): Unit = runBlocking {
        StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
            createAuthority(
                store,
                LEARNER_A,
                AUTHORITY_A,
                TURN_A,
                TutorExplanationMode.DIRECT,
            )
            createAuthority(
                store,
                LEARNER_B,
                AUTHORITY_B,
                TURN_B,
                TutorExplanationMode.DIRECT,
            )
            val activationA = activation(
                LEARNER_A,
                AUTHORITY_A,
                TURN_A,
                seed = "learner-a",
                explanationMode = TutorExplanationMode.DIRECT,
            )
            val activationB = activation(
                LEARNER_B,
                AUTHORITY_B,
                TURN_B,
                seed = "learner-b",
                explanationMode = TutorExplanationMode.DIRECT,
            )
            store.activateCurrentTutorInteraction(activationA)
            store.activateCurrentTutorInteraction(activationB)

            val first = store.appendCurrentTutorInteraction(exposure(activationA))
            val second = store.appendCurrentTutorInteraction(exposure(activationB))

            assertEquals(CurrentTutorInteractionAppendDisposition.APPLIED, first.disposition)
            assertEquals(CurrentTutorInteractionAppendDisposition.APPLIED, second.disposition)
            assertEquals(
                listOf(LEARNER_A),
                store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION)
                    ?.events?.map { it.learnerId },
            )
            assertEquals(
                listOf(LEARNER_B),
                store.readCurrentTutorInteraction(LEARNER_B, VISIBLE_CONVERSATION)
                    ?.events?.map { it.learnerId },
            )
        }
    }

    @Test
    fun cancellationAtomicallyClosesAuthorityRequestAndReplaysOnce(): Unit = runBlocking {
        StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
            createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
            val evidence = evidenceRequest(LEARNER_A, AUTHORITY_A, TURN_A)
            store.prepareTutorEvidenceRequest(evidence)
            val activation = activation(LEARNER_A, AUTHORITY_A, TURN_A, seed = "cancel")
            val active = store.activateCurrentTutorInteraction(activation).bundle
            val command = cancellation(activation, checkNotNull(active))

            val first = store.appendCurrentTutorInteraction(command)
            val replay = store.appendCurrentTutorInteraction(command)

            assertEquals(CurrentTutorInteractionAppendDisposition.APPLIED, first.disposition)
            assertEquals(CurrentTutorInteractionAppendDisposition.DUPLICATE, replay.disposition)
            assertEquals(
                TutorEvidenceRequestStatus.CANCELLED,
                store.openTutorEvidenceRequest(LEARNER_A, EVIDENCE_REQUEST)?.status,
            )
            assertEquals(
                1,
                store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION)
                    ?.events?.count { it.eventKind == CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION },
            )
        }
    }

    @Test
    fun directExposureRequiresExactNonNullTurnAndAllPersistedVersions(): Unit = runBlocking {
        StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
            createAuthority(
                store,
                LEARNER_A,
                AUTHORITY_A,
                TURN_A,
                TutorExplanationMode.DIRECT,
            )
            val activation = activation(
                LEARNER_A,
                AUTHORITY_A,
                TURN_A,
                seed = "direct-binding",
                explanationMode = TutorExplanationMode.DIRECT,
            )
            store.activateCurrentTutorInteraction(activation)
            val exact = exposure(activation)
            val rejected = listOf(
                exact.copy(authorizationRequestId = null),
                exact.copy(requestVersion = exact.requestVersion + 1),
                exact.copy(modeVersion = exact.modeVersion + 1),
                exact.copy(
                    learningWritePermissionVersion = exact.learningWritePermissionVersion + 1,
                ),
                exact.copy(turnReferenceId = "another-turn"),
                exact.copy(modelTaskRequestId = "another-turn"),
            )

            rejected.forEach { command ->
                assertEquals(
                    CurrentTutorInteractionAppendDisposition.REJECTED,
                    store.appendCurrentTutorInteraction(command).disposition,
                )
            }
            assertEquals(
                CurrentTutorInteractionAppendDisposition.APPLIED,
                store.appendCurrentTutorInteraction(exact).disposition,
            )
        }
    }

    @Test
    fun guidedExposureRequiresItsExactPendingDirectiveNotTheDirectTurnReference(): Unit =
        runBlocking {
            StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
                createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
                store.prepareTutorEvidenceRequest(evidenceRequest(LEARNER_A, AUTHORITY_A, TURN_A))
                val activation = activation(
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    seed = "guided-binding",
                )
                store.activateCurrentTutorInteraction(activation)
                val exact = exposure(activation).copy(
                    authorizationRequestId = EVIDENCE_REQUEST,
                )

                assertEquals(
                    CurrentTutorInteractionAppendDisposition.REJECTED,
                    store.appendCurrentTutorInteraction(
                        exact.copy(authorizationRequestId = activation.turnReferenceId),
                    ).disposition,
                )
                assertEquals(
                    CurrentTutorInteractionAppendDisposition.APPLIED,
                    store.appendCurrentTutorInteraction(exact).disposition,
                )
            }
        }

    @Test
    fun choiceAndVisualSelectionRequireTheirExactPendingRequestKind(): Unit = runBlocking {
        StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
            createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
            store.prepareTutorEvidenceRequest(
                evidenceRequest(
                    learnerId = LEARNER_A,
                    conversationId = AUTHORITY_A,
                    turnReceiptId = TURN_A,
                    kind = TutorEvidenceRequestKind.CHOICE,
                ),
            )
            val activation = activation(
                LEARNER_A,
                AUTHORITY_A,
                TURN_A,
                seed = "choice-kind",
            ).copy(attemptOrdinal = 0)
            val active = checkNotNull(store.activateCurrentTutorInteraction(activation).bundle)
            assertEquals(0, active.scope.attemptOrdinal)

            assertEquals(
                CurrentTutorInteractionAppendDisposition.REJECTED,
                store.appendCurrentTutorInteraction(
                    visualSelection(activation, active, "wrong-visual-kind"),
                ).disposition,
            )
            val exactChoice = choice(activation, active, "exact-choice")
            assertEquals(
                CurrentTutorInteractionAppendDisposition.APPLIED,
                store.appendCurrentTutorInteraction(exactChoice).disposition,
            )
            assertEquals(
                CurrentTutorInteractionAppendDisposition.DUPLICATE,
                store.appendCurrentTutorInteraction(exactChoice).disposition,
            )
            val committed = checkNotNull(
                store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
            )
            assertEquals(1, committed.scope.attemptOrdinal)
            assertEquals(1, committed.events.single().attemptOrdinal)
        }

        StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
            createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
            store.prepareTutorEvidenceRequest(
                evidenceRequest(
                    learnerId = LEARNER_A,
                    conversationId = AUTHORITY_A,
                    turnReceiptId = TURN_A,
                    kind = TutorEvidenceRequestKind.VISUAL_TARGET,
                ),
            )
            val activation = activation(
                LEARNER_A,
                AUTHORITY_A,
                TURN_A,
                seed = "visual-kind",
            ).copy(attemptOrdinal = 0)
            val active = checkNotNull(store.activateCurrentTutorInteraction(activation).bundle)

            assertEquals(
                CurrentTutorInteractionAppendDisposition.REJECTED,
                store.appendCurrentTutorInteraction(choice(activation, active, "wrong-choice-kind"))
                    .disposition,
            )
            val exactVisual = visualSelection(activation, active, "exact-visual")
            store.persistSucceededVisualSelectionTasks(exactVisual)
            assertEquals(
                CurrentTutorInteractionAppendDisposition.APPLIED,
                store.appendCurrentTutorInteraction(exactVisual).disposition,
            )
            assertEquals(
                CurrentTutorInteractionAppendDisposition.DUPLICATE,
                store.appendCurrentTutorInteraction(exactVisual).disposition,
            )
            val committed = checkNotNull(
                store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
            )
            assertEquals(1, committed.scope.attemptOrdinal)
            assertEquals(1, committed.events.single().attemptOrdinal)
        }
    }

    @Test
    fun attemptAndHintCountersRemainExactAcrossDuplicateAndDatabaseReopen(): Unit = runBlocking {
        val context = context()
        val databaseName = "current-tutor-attempt-hint-${System.nanoTime()}.db"
        lateinit var choiceCommand: AppendCurrentTutorInteractionCommand
        lateinit var hintCommand: AppendCurrentTutorInteractionCommand
        try {
            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }.use { store ->
                createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
                store.prepareTutorEvidenceRequest(
                    evidenceRequest(
                        learnerId = LEARNER_A,
                        conversationId = AUTHORITY_A,
                        turnReceiptId = TURN_A,
                        kind = TutorEvidenceRequestKind.CHOICE,
                    ),
                )
                val activation = activation(
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    seed = "durable-attempt-hint",
                ).copy(attemptOrdinal = 0)
                val initial = checkNotNull(store.activateCurrentTutorInteraction(activation).bundle)
                choiceCommand = choice(activation, initial, "durable-attempt")
                assertEquals(
                    CurrentTutorInteractionAppendDisposition.APPLIED,
                    store.appendCurrentTutorInteraction(choiceCommand).disposition,
                )
                assertEquals(
                    CurrentTutorInteractionAppendDisposition.DUPLICATE,
                    store.appendCurrentTutorInteraction(choiceCommand).disposition,
                )

                val afterChoice = checkNotNull(
                    store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
                )
                hintCommand = hintShown(activation, afterChoice, "durable-hint")
                assertEquals(
                    CurrentTutorInteractionAppendDisposition.APPLIED,
                    store.appendCurrentTutorInteraction(hintCommand).disposition,
                )
                assertEquals(
                    CurrentTutorInteractionAppendDisposition.DUPLICATE,
                    store.appendCurrentTutorInteraction(hintCommand).disposition,
                )
            }

            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW + 1L }
                .use { store ->
                    val restored = checkNotNull(
                        store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
                    )
                    assertEquals(1, restored.scope.attemptOrdinal)
                    assertEquals(1, restored.scope.hintCount)
                    assertEquals(2L, restored.head.stateVersion)
                    assertEquals(
                        listOf(1 to 0, 1 to 1),
                        restored.events.map { event -> event.attemptOrdinal to event.hintCount },
                    )
                    assertEquals(
                        CurrentTutorInteractionAppendDisposition.DUPLICATE,
                        store.appendCurrentTutorInteraction(choiceCommand).disposition,
                    )
                    assertEquals(
                        CurrentTutorInteractionAppendDisposition.DUPLICATE,
                        store.appendCurrentTutorInteraction(hintCommand).disposition,
                    )
                    val unchanged = checkNotNull(
                        store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
                    )
                    assertEquals(1, unchanged.scope.attemptOrdinal)
                    assertEquals(1, unchanged.scope.hintCount)
                    assertEquals(2L, unchanged.head.stateVersion)
                }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun finalizationIntentAndAcknowledgementInvalidateUnconsumedOpenResponseGrant(): Unit =
        runBlocking {
            StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
                createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
                store.prepareTutorEvidenceRequest(evidenceRequest(LEARNER_A, AUTHORITY_A, TURN_A))
                val activation = activation(
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    seed = "terminal-overlay",
                )
                val active = checkNotNull(store.activateCurrentTutorInteraction(activation).bundle)
                val capability = StudyDatabaseFactory.authorizeTutorSession(store)

                capability.beginTutorLearningEvidenceSessionIntent(
                    beginEvidenceSession(TutorEvidenceRequestKind.FREE_RESPONSE),
                )
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED,
                    store.consumeCurrentTutorOpenResponseAuthorization(
                        candidateClaim(activation, active, "after-finalization-intent"),
                    ).disposition,
                )
                assertEquals(
                    CurrentTutorInteractionAppendDisposition.REJECTED,
                    store.appendCurrentTutorInteraction(
                        exposure(activation).copy(
                            authorizationRequestId = EVIDENCE_REQUEST,
                            eventId = "exposure-after-finalization-intent",
                            idempotencyKey = "exposure-after-finalization-intent",
                            payloadFingerprint = sha256("exposure-after-finalization-intent"),
                        ),
                    ).disposition,
                )

                capability.acknowledgeTutorLearningEvidenceSession(acknowledgeEvidenceSession())
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED,
                    store.consumeCurrentTutorOpenResponseAuthorization(
                        candidateClaim(activation, active, "after-mastery-ack"),
                    ).disposition,
                )
            }
        }

    @Test
    fun openResponseCandidateConsumeIsDurableOneShotAndRestartIdempotent(): Unit = runBlocking {
        val context = context()
        val databaseName = "current-tutor-claim-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        lateinit var command: ConsumeCurrentTutorOpenResponseAuthorizationCommand
        try {
            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW }.use { store ->
                createAuthority(
                    store,
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    TutorExplanationMode.DIRECT,
                )
                val activation = activation(
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    seed = "claim-restart",
                    explanationMode = TutorExplanationMode.DIRECT,
                )
                val active = checkNotNull(store.activateCurrentTutorInteraction(activation).bundle)
                command = candidateClaim(activation, active, "candidate-one")
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.CONSUMED,
                    store.consumeCurrentTutorOpenResponseAuthorization(command).disposition,
                )
            }

            StudyDatabaseFactory.openPreCutoverForTest(context, databaseName) { NOW + 1 }.use { store ->
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.DUPLICATE,
                    store.consumeCurrentTutorOpenResponseAuthorization(command).disposition,
                )
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED,
                    store.consumeCurrentTutorOpenResponseAuthorization(
                        command.copy(candidateIdempotencyKey = sha256("candidate-two")),
                    ).disposition,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun persistedCloseReplacementAndGuidedCancellationInvalidateOldCandidateWithoutCollector(): Unit =
        runBlocking {
            StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
                createAuthority(
                    store,
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    TutorExplanationMode.DIRECT,
                )
                val old = activation(
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    seed = "old-scope",
                    explanationMode = TutorExplanationMode.DIRECT,
                )
                val oldBundle = checkNotNull(store.activateCurrentTutorInteraction(old).bundle)
                val oldClaim = candidateClaim(old, oldBundle, "old-scope-candidate")
                store.activateCurrentTutorInteraction(
                    activation(
                        LEARNER_A,
                        AUTHORITY_A,
                        TURN_A,
                        revision = 2,
                        seed = "replacement",
                        explanationMode = TutorExplanationMode.DIRECT,
                    ),
                )
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT,
                    store.consumeCurrentTutorOpenResponseAuthorization(oldClaim).disposition,
                )
            }

            StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
                createAuthority(
                    store,
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    TutorExplanationMode.DIRECT,
                )
                val activation = activation(
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    seed = "archive",
                    explanationMode = TutorExplanationMode.DIRECT,
                )
                val active = checkNotNull(store.activateCurrentTutorInteraction(activation).bundle)
                val oldClaim = candidateClaim(activation, active, "archive-candidate")
                store.archiveTutorConversation(
                    ArchiveTutorConversationCommand(
                        learnerId = LEARNER_A,
                        conversationId = AUTHORITY_A,
                        conversationGeneration = 1,
                        expectedStateVersion = 1,
                        idempotencyKey = "archive-authority",
                        payloadFingerprint = sha256("archive-authority"),
                    ),
                )
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED,
                    store.consumeCurrentTutorOpenResponseAuthorization(oldClaim).disposition,
                )
            }

            StudyDatabaseFactory.openInMemory(context()) { NOW }.use { store ->
                createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
                store.prepareTutorEvidenceRequest(evidenceRequest(LEARNER_A, AUTHORITY_A, TURN_A))
                val activation = activation(
                    LEARNER_A,
                    AUTHORITY_A,
                    TURN_A,
                    seed = "guided-cancel",
                )
                val active = checkNotNull(store.activateCurrentTutorInteraction(activation).bundle)
                val oldClaim = candidateClaim(activation, active, "guided-candidate")
                store.appendCurrentTutorInteraction(cancellation(activation, active))
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.NOT_CURRENT,
                    store.consumeCurrentTutorOpenResponseAuthorization(oldClaim).disposition,
                )
                val cancelled = checkNotNull(
                    store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
                )
                assertEquals(
                    CurrentTutorOpenResponseAuthorizationConsumeDisposition.REJECTED,
                    store.consumeCurrentTutorOpenResponseAuthorization(
                        candidateClaim(activation, cancelled, "guided-after-cancel"),
                    ).disposition,
                )
            }
        }

    private suspend fun prepareFreeResponseOutbox(
        store: RoomStudyDatabase,
        answer: String,
        seed: String,
    ): PreparedFreeResponseOutbox {
        createAuthority(store, LEARNER_A, AUTHORITY_A, TURN_A)
        store.prepareTutorEvidenceRequest(evidenceRequest(LEARNER_A, AUTHORITY_A, TURN_A))
        val policy = checkNotNull(
            store.persistCurrentTutorSessionPolicy(
                PersistCurrentTutorSessionPolicyCommand(
                    learnerId = LEARNER_A,
                    sessionId = VISIBLE_CONVERSATION,
                    explanationMode = TutorExplanationMode.GUIDED,
                    modeVersion = 1L,
                    learningWritesAllowed = true,
                    learningWritePermissionVersion = 1L,
                    visualIntent = TutorCurrentSessionVisualIntent.NONE,
                    visualIntentVersion = 0L,
                    occurredAtEpochMillis = NOW,
                ),
            ).record,
        )
        val activation = activation(
            learnerId = LEARNER_A,
            authorityConversationId = AUTHORITY_A,
            turnReceiptId = TURN_A,
            seed = "outbox-$seed",
        ).copy(
            turnReferenceId = EVIDENCE_REQUEST,
            attemptOrdinal = 0,
        )
        val staged = checkNotNull(
            store.stageCurrentTutorSessionHostWork(
                hostWork(activation).copy(
                    evidenceRequestId = EVIDENCE_REQUEST,
                    pendingInteractionKind = TutorEvidenceRequestKind.FREE_RESPONSE,
                    expectedPolicyStateFingerprint = policy.stateFingerprint,
                ),
            ).record,
        )
        assertEquals(
            CurrentTutorInteractionActivationDisposition.ACTIVATED,
            store.activateCurrentTutorInteraction(activation).disposition,
        )
        val active = checkNotNull(
            store.markCurrentTutorSessionHostWorkActive(
                MarkCurrentTutorSessionHostWorkActiveCommand(
                    learnerId = LEARNER_A,
                    sessionId = VISIBLE_CONVERSATION,
                    expectedWorkId = staged.workId,
                    expectedStateVersion = staged.stateVersion,
                    expectedStateFingerprint = staged.stateFingerprint,
                    expectedTargetScopeId = activation.scopeId,
                    expectedTargetActivationFingerprint = activation.activationFingerprint,
                    expectedPolicyStateFingerprint = policy.stateFingerprint,
                    occurredAtEpochMillis = NOW,
                ),
            ).record,
        )
        val actionToken = sha256("outbox-action-$seed")
        val claimCommand = ClaimCurrentTutorFreeResponseActionCommand(
            learnerId = LEARNER_A,
            sessionId = VISIBLE_CONVERSATION,
            expectedWorkId = active.workId,
            expectedWorkStateVersion = active.stateVersion,
            expectedWorkStateFingerprint = active.stateFingerprint,
            presentationToken = active.presentationToken,
            evidenceRequestId = EVIDENCE_REQUEST,
            actionToken = actionToken,
            answer = answer,
            actionExpiresAtEpochMillis = NOW + 120_000L,
            occurredAtEpochMillis = NOW,
        )
        val claim = store.claimCurrentTutorFreeResponseAction(claimCommand)
        assertEquals(CurrentTutorFreeResponseActionClaimDisposition.CLAIMED, claim.disposition)
        assertEquals(
            CurrentTutorFreeResponseActionClaimDisposition.DUPLICATE,
            store.claimCurrentTutorFreeResponseAction(claimCommand).disposition,
        )
        val committed = checkNotNull(
            store.readCurrentTutorInteraction(LEARNER_A, VISIBLE_CONVERSATION),
        )
        assertEquals(1, committed.scope.attemptOrdinal)
        assertEquals(0, committed.scope.hintCount)
        assertEquals(
            1,
            committed.events.single { event ->
                event.eventKind == CurrentTutorInteractionEventKind.FREE_RESPONSE_SUBMISSION_CLAIM
            }.attemptOrdinal,
        )
        return PreparedFreeResponseOutbox(actionToken, claim)
    }

    private fun assertSensitiveBytesAbsent(
        context: Context,
        databaseName: String,
        rawAnswer: String,
        jsonEscapedAnswer: String,
    ) {
        val database = context.getDatabasePath(databaseName)
        listOf(database, File(database.path + "-wal"), File(database.path + "-shm"))
            .filter(File::isFile)
            .forEach { file ->
                val bytes = file.readBytes()
                listOf(rawAnswer, jsonEscapedAnswer).forEach { forbidden ->
                    assertFalse(
                        "Sensitive free response found in ${file.name}",
                        bytes.containsSubsequence(forbidden.toByteArray(StandardCharsets.UTF_8)),
                    )
                }
            }
    }

    private fun tamperEncryptedOutboxAnswer(
        context: Context,
        databaseName: String,
        actionToken: String,
    ) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            val ciphertext = sqlite.rawQuery(
                "SELECT encrypted_answer FROM tutor_free_response_outbox " +
                    "WHERE learner_id = ? AND action_token = ?",
                arrayOf(LEARNER_A, actionToken),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                checkNotNull(cursor.getBlob(0))
            }
            assertTrue(ciphertext.isNotEmpty())
            ciphertext[0] = (ciphertext[0].toInt() xor 0x01).toByte()
            sqlite.execSQL(
                "UPDATE tutor_free_response_outbox SET encrypted_answer = ? " +
                    "WHERE learner_id = ? AND action_token = ?",
                arrayOf(ciphertext, LEARNER_A, actionToken),
            )
        }
    }

    private fun assertTerminalOutboxWiped(
        context: Context,
        databaseName: String,
        actionToken: String,
        expectedStatus: String,
    ) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            sqlite.rawQuery(
                "SELECT status, encrypted_answer, nonce FROM tutor_free_response_outbox " +
                    "WHERE learner_id = ? AND action_token = ?",
                arrayOf(LEARNER_A, actionToken),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(expectedStatus, cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    private fun assertOutboxAttemptCount(
        context: Context,
        databaseName: String,
        actionToken: String,
        expectedCount: Int,
    ) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            sqlite.rawQuery(
                "SELECT dispatch_attempt_count FROM tutor_free_response_outbox " +
                    "WHERE learner_id = ? AND action_token = ?",
                arrayOf(LEARNER_A, actionToken),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(expectedCount, cursor.getInt(0))
            }
        }
    }

    private suspend fun createAuthority(
        store: RoomStudyDatabase,
        learnerId: String,
        conversationId: String,
        turnReceiptId: String,
        explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    ) {
        store.createTutorConversation(
            CreateTutorConversationCommand(
                conversationId = conversationId,
                learnerId = learnerId,
                generation = 1,
                idempotencyKey = "create-$learnerId",
                payloadFingerprint = sha256("create-$learnerId"),
            ),
        )
        store.allocateTutorTurn(
            AllocateTutorTurnCommand(
                turnReceiptId = turnReceiptId,
                learnerId = learnerId,
                conversationId = conversationId,
                conversationGeneration = 1,
                expectedConversationStateVersion = 0,
                expectedTurnOrdinal = 1,
                clientTurnId = "client-$learnerId",
                payloadFingerprint = sha256("turn-$learnerId"),
                subject = SubjectKind.PHYSICS,
                problemAnchorId = ANCHOR,
                requestVersion = 1,
                explanationMode = explanationMode,
                modeVersion = 1,
                directiveFingerprint = sha256("directive-$learnerId"),
                studentMessageFingerprint = sha256("message-$learnerId"),
                studentMessageSummary = "当前题",
                occurredAtEpochMillis = NOW,
            ),
        )
    }

    private fun activation(
        learnerId: String,
        authorityConversationId: String,
        turnReceiptId: String,
        revision: Int = 1,
        seed: String,
        explanationMode: TutorExplanationMode = TutorExplanationMode.GUIDED,
    ) = ActivateCurrentTutorInteractionCommand(
        scopeId = "scope-$learnerId-$seed",
        learnerId = learnerId,
        conversationId = VISIBLE_CONVERSATION,
        conversationGeneration = 1,
        conversationStateVersion = revision.toLong(),
        authorityConversationId = authorityConversationId,
        authorityConversationGeneration = 1,
        authorityConversationStateVersion = 1,
        authorityTurnReceiptId = turnReceiptId,
        authorityTurnOrdinal = 1,
        authorityRequestVersion = 1,
        questionDocumentId = "question-$learnerId",
        questionRevisionNumber = revision,
        questionDocumentSnapshot = "{\"id\":\"question-$learnerId\"}",
        questionFingerprint = sha256("question-$learnerId-$revision"),
        subject = SubjectKind.PHYSICS,
        problemAnchorId = ANCHOR,
        explanationMode = explanationMode,
        modeVersion = 1,
        turnReferenceId = "model-task-$learnerId",
        turnOrdinal = 1,
        turnGeneration = 1,
        cycleOrdinal = 1,
        attemptOrdinal = 1,
        hintCount = 0,
        answerWasRevealed = false,
        requestVersion = revision.toLong(),
        learningWritePermissionVersion = revision.toLong(),
        presentationFingerprint = sha256("presentation-$learnerId-$revision"),
        problemFingerprint = sha256("problem-$learnerId"),
        problemFamilyFingerprint = sha256("family-$learnerId"),
        attributionPolicyVersion = "attribution-v1",
        responsePolicyVersion = "response-v1",
        rubricCanonicalFingerprint = sha256("rubric-$learnerId"),
        knowledgeAuthorityFingerprint = sha256("knowledge-$learnerId"),
        evaluator = "RUBRIC",
        evaluatorPolicyFingerprint = sha256("evaluator-$learnerId"),
        activationFingerprint = sha256("activation-$learnerId-$seed-$revision"),
        occurredAtEpochMillis = NOW,
    )

    private fun hostWork(
        activation: ActivateCurrentTutorInteractionCommand,
        constrainedContentFingerprint: String = activation.presentationFingerprint,
    ): StageCurrentTutorSessionHostWorkCommand {
        val modelTaskFingerprint = sha256("model-${activation.learnerId}")
        val directiveFingerprint = sha256("directive-${activation.learnerId}")
        val payloadFingerprint = com.tingyun.smartmistakebook.core.model.CanonicalSha256(
            "current-tutor-host-work-payload-v1",
        )
            .field("activationFingerprint", activation.activationFingerprint)
            .field("taskRequestFingerprint", modelTaskFingerprint)
            .field("authorityTurnReceiptId", activation.authorityTurnReceiptId)
            .field("authorityDirectiveFingerprint", directiveFingerprint)
            .field("constrainedTutorContentFingerprint", constrainedContentFingerprint)
            .field("learningWritePermissionVersion", activation.learningWritePermissionVersion)
            .field("visualIntent", TutorCurrentSessionVisualIntent.NONE.name)
            .field("visualIntentVersion", 0L)
            .finish()
        val presentationToken = com.tingyun.smartmistakebook.core.model.CanonicalSha256(
            "current-tutor-presentation-token-v1",
        )
            .field("activationFingerprint", activation.activationFingerprint)
            .field("payloadFingerprint", payloadFingerprint)
            .finish()
        return StageCurrentTutorSessionHostWorkCommand(
        workId = "work-${activation.learnerId}",
        learnerId = activation.learnerId,
        sessionId = activation.conversationId,
        questionDocumentId = activation.questionDocumentId,
        questionRevisionNumber = activation.questionRevisionNumber,
        subject = activation.subject,
        authorityConversationId = activation.authorityConversationId,
        authorityConversationGeneration = activation.authorityConversationGeneration,
        authorityConversationStateVersion = activation.authorityConversationStateVersion,
        authorityTurnReceiptId = activation.authorityTurnReceiptId,
        authorityTurnOrdinal = activation.authorityTurnOrdinal,
        authorityRequestVersion = activation.authorityRequestVersion,
        authorityDirectiveFingerprint = directiveFingerprint,
        modelTaskRequestId = activation.turnReferenceId,
        modelTaskRequestFingerprint = modelTaskFingerprint,
        problemAnchorId = activation.problemAnchorId,
        explanationMode = activation.explanationMode,
        modeVersion = activation.modeVersion,
        learningWritesAllowed = true,
        learningWritePermissionVersion = activation.learningWritePermissionVersion,
        visualIntent = TutorCurrentSessionVisualIntent.NONE,
        visualIntentVersion = 0L,
        cycleOrdinal = activation.cycleOrdinal,
        turnOrdinal = activation.turnOrdinal,
        attemptOrdinal = activation.attemptOrdinal.coerceAtLeast(1),
        requestVersion = activation.requestVersion,
        evidenceRequestId = null,
        pendingInteractionKind = null,
        targetScopeId = activation.scopeId,
        targetActivationFingerprint = activation.activationFingerprint,
        constrainedTutorContentFingerprint = constrainedContentFingerprint,
        presentationToken = presentationToken,
        payloadFingerprint = payloadFingerprint,
        expectedStateVersion = null,
        expectedStateFingerprint = null,
        occurredAtEpochMillis = NOW,
    )
    }

    private fun exposure(
        activation: ActivateCurrentTutorInteractionCommand,
    ) = AppendCurrentTutorInteractionCommand(
        scopeId = activation.scopeId,
        learnerId = activation.learnerId,
        conversationId = activation.conversationId,
        conversationGeneration = activation.conversationGeneration,
        conversationStateVersion = activation.conversationStateVersion,
        questionDocumentId = activation.questionDocumentId,
        questionRevisionNumber = activation.questionRevisionNumber,
        questionFingerprint = activation.questionFingerprint,
        subject = activation.subject,
        problemAnchorId = activation.problemAnchorId,
        explanationMode = activation.explanationMode,
        modeVersion = activation.modeVersion,
        learningWritePermissionVersion = activation.learningWritePermissionVersion,
        turnReferenceId = activation.turnReferenceId,
        turnOrdinal = activation.turnOrdinal,
        turnGeneration = activation.turnGeneration,
        cycleOrdinal = activation.cycleOrdinal,
        attemptOrdinal = activation.attemptOrdinal,
        hintCount = activation.hintCount,
        answerWasRevealed = activation.answerWasRevealed,
        expectedStateVersion = 0,
        expectedStateFingerprint = activation.activationFingerprint,
        eventId = "shared-event",
        eventKind = CurrentTutorInteractionEventKind.ANSWER_EXPOSURE,
        authorizationPurpose = "RECORD_EXPOSURE",
        authorizationRequestId = activation.turnReferenceId,
        idempotencyKey = "shared-idempotency",
        requestVersion = activation.requestVersion,
        payloadFingerprint = sha256("exposure-${activation.learnerId}"),
        occurredAtEpochMillis = NOW,
        surfaceKind = "TUTOR_REPLY",
        modelTaskRequestId = activation.turnReferenceId,
        responseOrdinal = 1,
    )

    private fun evidenceRequest(
        learnerId: String,
        conversationId: String,
        turnReceiptId: String,
        kind: TutorEvidenceRequestKind = TutorEvidenceRequestKind.FREE_RESPONSE,
    ) = PrepareTutorEvidenceRequestCommand(
        evidenceRequestId = EVIDENCE_REQUEST,
        learnerId = learnerId,
        conversationId = conversationId,
        conversationGeneration = 1,
        conversationStateVersion = 1,
        turnReceiptId = turnReceiptId,
        turnOrdinal = 1,
        subject = SubjectKind.PHYSICS,
        problemAnchorId = ANCHOR,
        kind = kind,
        requestVersion = 1,
        explanationMode = TutorExplanationMode.GUIDED,
        modeVersion = 1,
        directiveFingerprint = sha256("directive-$learnerId"),
        idempotencyKey = "prepare-free-response",
        payloadFingerprint = sha256("prepare-free-response"),
    )

    private fun choice(
        activation: ActivateCurrentTutorInteractionCommand,
        active: CurrentTutorInteractionBundle,
        seed: String,
    ) = interactionCommand(
        activation = activation,
        active = active,
        eventId = "choice-$seed",
        eventKind = CurrentTutorInteractionEventKind.CHOICE,
        authorizationPurpose = "RECORD_CHOICE",
        idempotencyKey = "choice-$seed",
        payloadFingerprint = sha256("choice-$seed"),
        diagnosticStemMarkdown = "判断当前关系。",
        selectedChoiceId = "choice-a",
        selectedChoiceMarkdown = "A",
        selectionWasCorrect = true,
        feedbackMarkdown = "正确。",
        evidenceRequestId = EVIDENCE_REQUEST,
    )

    private fun visualSelection(
        activation: ActivateCurrentTutorInteractionCommand,
        active: CurrentTutorInteractionBundle,
        seed: String,
    ): AppendCurrentTutorInteractionCommand {
        val scene = visualSelectionScene(seed)
        return interactionCommand(
            activation = activation,
            active = active,
            eventId = "visual-$seed",
            eventKind = CurrentTutorInteractionEventKind.VISUAL_SELECTION,
            authorizationPurpose = "RECORD_VISUAL_SELECTION",
            idempotencyKey = "visual-$seed",
            payloadFingerprint = sha256("visual-$seed"),
            evidenceRequestId = EVIDENCE_REQUEST,
            surfaceKind = TutorVisualTurnSurface.PLAN.name,
            modelTaskRequestId = EVIDENCE_REQUEST,
            responseOrdinal = null,
            sceneSourceKind = "GENERATED",
            sceneTaskRequestId = "scene-task-$seed",
            sceneId = scene.sceneId,
            sceneFingerprint = TutorVisualSceneFingerprint.of(scene),
            hitProofId = "hit-$seed",
            panelId = scene.panels.single().panelId,
            frameFingerprint = sha256("frame-$seed"),
            stepIndex = 0,
            selectedTargetId = scene.elements.single().elementId,
        )
    }

    private suspend fun StudyDatabasePort.persistSucceededVisualSelectionTasks(
        command: AppendCurrentTutorInteractionCommand,
    ) {
        val questionDocument = visualQuestionDocument(command.questionDocumentId)
        val targetId = checkNotNull(command.selectedTargetId)
        val anchor = TutorVisualTurnAnchor(
            surface = TutorVisualTurnSurface.valueOf(checkNotNull(command.surfaceKind)),
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            responseOrdinal = command.responseOrdinal,
        )
        val focusMarkdown = "请点出图中的目标。"
        val solutionMarkdown = "先比较图中各位置，再确定目标。"
        val planInput = TutorPlanInput(
            sessionId = command.conversationId,
            draftRevisionNumber = command.questionRevisionNumber,
            subject = command.subject.name,
            questionDocument = questionDocument,
            cycleOrdinal = command.cycleOrdinal,
            turnOrdinal = command.turnOrdinal,
            explanationMode = command.explanationMode,
            modeVersion = command.modeVersion,
            learningWritePermissionVersion = command.learningWritePermissionVersion,
        )
        persistSucceededModelTask(
            request = ModelTaskRequest(
                requestId = checkNotNull(command.modelTaskRequestId),
                input = planInput,
                occurredAtEpochMillis = MODEL_TASK_CREATED_AT_EPOCH_MILLIS,
            ),
            output = TutorPlanOutput(
                sessionId = planInput.sessionId,
                draftRevisionNumber = planInput.draftRevisionNumber,
                questionDocumentId = planInput.questionDocument.id,
                plan = TutorTurnPlan(
                    openingMarkdown = "先观察图中的候选位置。",
                    interactionDirective = TutorInteractionDirective.VisualTarget(
                        promptMarkdown = focusMarkdown,
                        targetId = targetId,
                    ),
                    visualRequest = TutorVisualGenerationRequest(focusMarkdown),
                    solutionMarkdown = solutionMarkdown,
                    alternateMethodMarkdown = "也可以逐一排除不符合条件的位置。",
                    difficultyReasonMarkdown = "关键在于把题意和图中位置对应起来。",
                    targetedEvidenceLabels = emptyList(),
                    inferredKnowledgeLabels = listOf("电磁感应"),
                ),
                modelVersion = "current-tutor-test-model",
                cycleOrdinal = command.cycleOrdinal,
                turnOrdinal = command.turnOrdinal,
            ),
        )

        val scene = visualSelectionScene(command.idempotencyKey.removePrefix("visual-"))
        val visualInput = TutorVisualGenerateInput(
            sessionId = command.conversationId,
            draftRevisionNumber = command.questionRevisionNumber,
            subject = command.subject.name,
            questionDocument = questionDocument,
            sourceAssets = listOf(
                CaptureSourceAssetRef(
                    assetId = "source-${command.idempotencyKey}",
                    sha256 = sha256("source-${command.idempotencyKey}"),
                    width = 100,
                    height = 100,
                    pageIndex = 0,
                ),
            ),
            anchor = anchor,
            focusMarkdown = focusMarkdown,
            explanationMarkdown = solutionMarkdown,
        )
        persistSucceededModelTask(
            request = ModelTaskRequest(
                requestId = checkNotNull(command.sceneTaskRequestId),
                input = visualInput,
                occurredAtEpochMillis = MODEL_TASK_CREATED_AT_EPOCH_MILLIS,
            ),
            output = TutorVisualGenerateOutput(
                sessionId = visualInput.sessionId,
                draftRevisionNumber = visualInput.draftRevisionNumber,
                questionDocumentId = visualInput.questionDocument.id,
                anchor = visualInput.anchor,
                decision = TutorVisualGenerationDecision.GENERATED,
                confidence = 0.9,
                scene = scene,
                modelVersion = "current-tutor-visual-test-model",
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
            modelTaskTransition(
                snapshot = snapshot,
                nextStatus = ModelTaskStatus.QUEUED,
                stage = ModelTaskStage.PREPARING,
                occurredAtEpochMillis = request.occurredAtEpochMillis + 1,
            ),
        ).snapshot
        snapshot = transitionModelTask(
            modelTaskTransition(
                snapshot = snapshot,
                nextStatus = ModelTaskStatus.RUNNING,
                stage = ModelTaskStage.VALIDATING_OUTPUT,
                occurredAtEpochMillis = request.occurredAtEpochMillis + 2,
            ),
        ).snapshot
        snapshot = transitionModelTask(
            modelTaskTransition(
                snapshot = snapshot,
                nextStatus = ModelTaskStatus.SUCCEEDED,
                stage = ModelTaskStage.COMPLETE,
                occurredAtEpochMillis = request.occurredAtEpochMillis + 3,
                output = output,
            ),
        ).snapshot
        check(snapshot.status == ModelTaskStatus.SUCCEEDED)
    }

    private fun modelTaskTransition(
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

    private fun visualQuestionDocument(documentId: String) = QuestionDocument(
        id = documentId,
        blocks = listOf(ContentBlock.Paragraph("stem", "线框转动时感应电动势如何变化？")),
    )

    private fun visualSelectionScene(seed: String) = TutorVisualDocumentScene(
        sceneId = "scene-$seed",
        title = "目标选择",
        panels = listOf(TutorVisualPanel("panel-$seed", TutorVisualPanelKind.DIAGRAM_2D)),
        elements = listOf(
            TutorVisual2DNodeElement(
                elementId = "target-$seed",
                panelId = "panel-$seed",
                kind = TutorVisual2DNodeKind.RECTANGLE,
                label = "目标",
            ),
        ),
        steps = listOf(
            TutorVisualStep(
                stepId = "pick-$seed",
                label = "选择目标",
                focusElementIds = listOf("target-$seed"),
                primaryRelationElementId = "target-$seed",
            ),
        ),
        fallbackMarkdown = "请选择图中的目标。",
        accessibilitySummary = "一个可选择目标。",
    )

    private fun hintShown(
        activation: ActivateCurrentTutorInteractionCommand,
        active: CurrentTutorInteractionBundle,
        seed: String,
    ) = interactionCommand(
        activation = activation,
        active = active,
        eventId = "hint-$seed",
        eventKind = CurrentTutorInteractionEventKind.HINT_SHOWN,
        authorizationPurpose = "RECORD_HINT_SHOWN",
        authorizationRequestId = null,
        idempotencyKey = "hint-$seed",
        payloadFingerprint = sha256("hint-$seed"),
        requestedMove = "HINT_SHOWN",
    )

    private fun interactionCommand(
        activation: ActivateCurrentTutorInteractionCommand,
        active: CurrentTutorInteractionBundle,
        eventId: String,
        eventKind: CurrentTutorInteractionEventKind,
        authorizationPurpose: String,
        authorizationRequestId: String? = EVIDENCE_REQUEST,
        idempotencyKey: String,
        payloadFingerprint: String,
        diagnosticStemMarkdown: String? = null,
        selectedChoiceId: String? = null,
        selectedChoiceMarkdown: String? = null,
        selectionWasCorrect: Boolean? = null,
        feedbackMarkdown: String? = null,
        evidenceRequestId: String? = null,
        requestedMove: String? = null,
        surfaceKind: String? = null,
        modelTaskRequestId: String? = null,
        responseOrdinal: Int? = null,
        sceneSourceKind: String? = null,
        sceneTaskRequestId: String? = null,
        sceneId: String? = null,
        sceneFingerprint: String? = null,
        hitProofId: String? = null,
        panelId: String? = null,
        frameFingerprint: String? = null,
        stepIndex: Int? = null,
        selectedTargetId: String? = null,
    ) = AppendCurrentTutorInteractionCommand(
        scopeId = activation.scopeId,
        learnerId = activation.learnerId,
        conversationId = activation.conversationId,
        conversationGeneration = activation.conversationGeneration,
        conversationStateVersion = activation.conversationStateVersion,
        questionDocumentId = activation.questionDocumentId,
        questionRevisionNumber = activation.questionRevisionNumber,
        questionFingerprint = activation.questionFingerprint,
        subject = activation.subject,
        problemAnchorId = activation.problemAnchorId,
        explanationMode = activation.explanationMode,
        modeVersion = activation.modeVersion,
        learningWritePermissionVersion = activation.learningWritePermissionVersion,
        turnReferenceId = activation.turnReferenceId,
        turnOrdinal = activation.turnOrdinal,
        turnGeneration = activation.turnGeneration,
        cycleOrdinal = activation.cycleOrdinal,
        attemptOrdinal = active.scope.attemptOrdinal.coerceAtLeast(1),
        hintCount = active.scope.hintCount,
        answerWasRevealed = active.scope.answerWasRevealed,
        expectedStateVersion = active.head.stateVersion,
        expectedStateFingerprint = active.head.stateFingerprint,
        eventId = eventId,
        eventKind = eventKind,
        authorizationPurpose = authorizationPurpose,
        authorizationRequestId = authorizationRequestId,
        idempotencyKey = idempotencyKey,
        requestVersion = activation.requestVersion,
        payloadFingerprint = payloadFingerprint,
        occurredAtEpochMillis = NOW,
        diagnosticStemMarkdown = diagnosticStemMarkdown,
        selectedChoiceId = selectedChoiceId,
        selectedChoiceMarkdown = selectedChoiceMarkdown,
        selectionWasCorrect = selectionWasCorrect,
        feedbackMarkdown = feedbackMarkdown,
        evidenceRequestId = evidenceRequestId,
        requestedMove = requestedMove,
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
    )

    private fun beginEvidenceSession(kind: TutorEvidenceRequestKind) =
        BeginTutorLearningEvidenceSessionIntentCommand(
            learnerId = LEARNER_A,
            evidenceRequestId = EVIDENCE_REQUEST,
            conversationId = AUTHORITY_A,
            conversationGeneration = 1,
            conversationStateVersion = 1,
            turnReceiptId = TURN_A,
            turnOrdinal = 1,
            subject = SubjectKind.PHYSICS,
            sessionAnchorId = ANCHOR,
            evidenceKind = kind,
            requestVersion = 1,
            modeVersion = 1,
            idempotencyKey = "finalize-current-tutor-evidence",
            candidateFingerprint = sha256("current-tutor-evidence-candidate"),
        )

    private fun acknowledgeEvidenceSession() =
        AcknowledgeTutorLearningEvidenceSessionCommand(
            learnerId = LEARNER_A,
            evidenceRequestId = EVIDENCE_REQUEST,
            conversationId = AUTHORITY_A,
            conversationGeneration = 1,
            turnReceiptId = TURN_A,
            candidateFingerprint = sha256("current-tutor-evidence-candidate"),
            expectedStateVersion = 0,
            masteryReceiptId = "mastery-current-tutor",
            masteryReceiptFingerprint = sha256("mastery-current-tutor"),
        )

    private fun cancellation(
        activation: ActivateCurrentTutorInteractionCommand,
        active: CurrentTutorInteractionBundle,
    ) = AppendCurrentTutorInteractionCommand(
        scopeId = activation.scopeId,
        learnerId = activation.learnerId,
        conversationId = activation.conversationId,
        conversationGeneration = activation.conversationGeneration,
        conversationStateVersion = activation.conversationStateVersion,
        questionDocumentId = activation.questionDocumentId,
        questionRevisionNumber = activation.questionRevisionNumber,
        questionFingerprint = activation.questionFingerprint,
        subject = activation.subject,
        problemAnchorId = activation.problemAnchorId,
        explanationMode = activation.explanationMode,
        modeVersion = activation.modeVersion,
        learningWritePermissionVersion = activation.learningWritePermissionVersion,
        turnReferenceId = activation.turnReferenceId,
        turnOrdinal = activation.turnOrdinal,
        turnGeneration = activation.turnGeneration,
        cycleOrdinal = activation.cycleOrdinal,
        attemptOrdinal = activation.attemptOrdinal,
        hintCount = activation.hintCount,
        answerWasRevealed = activation.answerWasRevealed,
        expectedStateVersion = active.head.stateVersion,
        expectedStateFingerprint = active.head.stateFingerprint,
        eventId = "cancel-event",
        eventKind = CurrentTutorInteractionEventKind.EVIDENCE_CANCELLATION,
        authorizationPurpose = "CANCEL_EVIDENCE",
        authorizationRequestId = EVIDENCE_REQUEST,
        idempotencyKey = "cancel-idempotency",
        requestVersion = activation.requestVersion,
        payloadFingerprint = sha256("cancel-payload"),
        occurredAtEpochMillis = NOW,
        evidenceRequestId = EVIDENCE_REQUEST,
    )

    private fun candidateClaim(
        activation: ActivateCurrentTutorInteractionCommand,
        active: CurrentTutorInteractionBundle,
        seed: String,
    ) = ConsumeCurrentTutorOpenResponseAuthorizationCommand(
        scopeId = activation.scopeId,
        learnerId = activation.learnerId,
        conversationId = activation.conversationId,
        conversationGeneration = activation.conversationGeneration,
        conversationStateVersion = activation.conversationStateVersion,
        questionDocumentId = activation.questionDocumentId,
        questionRevisionNumber = activation.questionRevisionNumber,
        questionFingerprint = activation.questionFingerprint,
        subject = activation.subject,
        problemAnchorId = activation.problemAnchorId,
        explanationMode = activation.explanationMode,
        modeVersion = activation.modeVersion,
        learningWritePermissionVersion = activation.learningWritePermissionVersion,
        turnReferenceId = activation.turnReferenceId,
        turnOrdinal = activation.turnOrdinal,
        turnGeneration = activation.turnGeneration,
        cycleOrdinal = activation.cycleOrdinal,
        attemptOrdinal = activation.attemptOrdinal,
        hintCount = activation.hintCount,
        answerWasRevealed = activation.answerWasRevealed,
        requestVersion = activation.requestVersion,
        expectedStateVersion = active.head.stateVersion,
        expectedStateFingerprint = active.head.stateFingerprint,
        evidenceRequestId = if (activation.explanationMode == TutorExplanationMode.DIRECT) {
            activation.turnReferenceId
        } else {
            EVIDENCE_REQUEST
        },
        candidateScopeFingerprint = sha256("candidate-scope-$seed"),
        candidateIdempotencyKey = sha256("candidate-idempotency-$seed"),
        occurredAtEpochMillis = NOW,
    )

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private data class PreparedFreeResponseOutbox(
        val actionToken: String,
        val claim: CurrentTutorFreeResponseActionClaimResult,
    ) {
        fun acquire(
            processGeneration: String,
            occurredAtEpochMillis: Long,
        ) = AcquireCurrentTutorFreeResponseDispatchCommand(
            learnerId = LEARNER_A,
            sessionId = VISIBLE_CONVERSATION,
            actionToken = actionToken,
            leaseOwnerId = "instrumented-owner-$processGeneration",
            leaseGenerationId = processGeneration,
            leaseDurationMillis = 300_000L,
            occurredAtEpochMillis = occurredAtEpochMillis,
        )
    }

    private fun CurrentTutorFreeResponseDispatchLease.releaseAt(
        occurredAtEpochMillis: Long,
    ) = ReleaseCurrentTutorFreeResponseDispatchCommand(
        learnerId = learnerId,
        sessionId = sessionId,
        actionToken = actionToken,
        leaseToken = leaseToken,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private companion object {
        const val LEARNER_A = "learner-a"
        const val LEARNER_B = "learner-b"
        const val AUTHORITY_A = "authority-a"
        const val AUTHORITY_B = "authority-b"
        const val TURN_A = "turn-a"
        const val TURN_B = "turn-b"
        const val VISIBLE_CONVERSATION = "visible-conversation"
        const val ANCHOR = "problem-anchor"
        const val EVIDENCE_REQUEST = "evidence-request"
        const val MODEL_TASK_CREATED_AT_EPOCH_MILLIS = 9_000L
        const val NOW = 10_000L

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
