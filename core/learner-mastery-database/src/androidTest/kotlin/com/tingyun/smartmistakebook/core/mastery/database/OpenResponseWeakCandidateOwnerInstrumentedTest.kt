package com.tingyun.smartmistakebook.core.mastery.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseKnowledgeRole
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenResponseWeakCandidateOwnerInstrumentedTest {
    private lateinit var currentTestOwner: RoomOpenResponseWeakCandidateOwner

    @Test
    fun modelSemanticOutcomesRemainReviewOnlyWithoutCreatingLearningEvents() =
        runBlocking {
            val scenarios =
                listOf(
                    DedicatedScenario(
                        label = "correct",
                        outcome = OpenResponseEvaluationOutcome.CORRECT,
                        roles = listOf(OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS),
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                    DedicatedScenario(
                        label = "assisted-correct",
                        outcome = OpenResponseEvaluationOutcome.ASSISTED_CORRECT,
                        roles = listOf(OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS),
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                    DedicatedScenario(
                        label = "one-gap",
                        outcome = OpenResponseEvaluationOutcome.INCORRECT,
                        roles = listOf(OpenResponseKnowledgeRole.LOCATED_GAP),
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                    DedicatedScenario(
                        label = "two-gaps",
                        outcome = OpenResponseEvaluationOutcome.INCORRECT,
                        roles =
                            listOf(
                                OpenResponseKnowledgeRole.LOCATED_GAP,
                                OpenResponseKnowledgeRole.LOCATED_GAP,
                            ),
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                    DedicatedScenario(
                        label = "assistance-only",
                        outcome = OpenResponseEvaluationOutcome.ASSISTED_CORRECT,
                        roles = listOf(OpenResponseKnowledgeRole.REQUIRED_ASSISTANCE),
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                    DedicatedScenario(
                        label = "revealed",
                        outcome = OpenResponseEvaluationOutcome.CORRECT,
                        roles = listOf(OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS),
                        answerWasRevealed = true,
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                    DedicatedScenario(
                        label = "assistance-mismatch",
                        outcome = OpenResponseEvaluationOutcome.ASSISTED_CORRECT,
                        roles = listOf(OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS),
                        attemptOrdinal = 1,
                        hintCount = 0,
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                    DedicatedScenario(
                        label = "assistance-limit",
                        outcome = OpenResponseEvaluationOutcome.CORRECT,
                        roles = listOf(OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS),
                        hintCount = 2,
                        expectedDisposition = "RETAINED_FOR_REVIEW",
                        expectedReason = REVIEW_ONLY_REASON,
                    ),
                )

            scenarios.forEach { scenario -> verifyDedicatedScenario(scenario) }
        }

    @Test
    fun reopenAuditsCanonicalDedicatedProofChainAfterGuardTampering() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "open-response-proof-audit.mastery-test.db"
            context.deleteDatabase(databaseName)
            val facts = pendingFacts()
            val reviewCaseId =
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 10L },
                ).use { store ->
                    checkNotNull(
                        store.enqueuePendingOpenResponse(
                            sourceFact = preparePendingOpenResponseSourceFact(LEARNER_ID, facts),
                            candidate = preparePendingOpenResponseCandidate(LEARNER_ID, facts),
                        ).reviewCaseId,
                    )
                }
            val firstKey = fingerprint("candidate-idempotency")
            val revisionKey = fingerprint("proof-audit-revision")
            val command =
                command(
                    reviewCaseId = reviewCaseId,
                    candidateIdempotencyKey = firstKey,
                )
            val receiptQuery =
                LearnerMasteryOpenResponseWeakCandidateReceiptQuery(
                    learnerId = command.learnerId,
                    sourceFactId = command.sourceFactId,
                    reviewCaseId = command.reviewCaseId,
                    scopeFingerprint = command.scopeFingerprint,
                    candidateIdempotencyKey = command.candidateIdempotencyKey,
                )
            openOwner(context, databaseName).use { owner ->
                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
                    owner.submit(
                        command = command,
                        authorization =
                            authorization(
                                scopeFingerprint = command.scopeFingerprint,
                                current = {},
                            ),
                    ).disposition,
                )
                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
                    owner.submit(
                        command =
                            command(
                                reviewCaseId = reviewCaseId,
                                candidateIdempotencyKey = revisionKey,
                                revisionOfCandidateIdempotencyKey = firstKey,
                            ),
                        authorization = authorization {},
                    ).disposition,
                )
            }
            openOwner(context, databaseName).use { owner ->
                assertNotNull(owner.findCommitted(receiptQuery))
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    "DROP TRIGGER immutable_" +
                        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE +
                        "_update",
                )
                sqlite.execSQL(
                    "UPDATE " + LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE +
                        " SET local_reason = 'ANSWER_REVEALED' WHERE rowid = (SELECT MIN(rowid) " +
                        "FROM " + LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE + ")",
                )
                sqlite.execSQL(
                    "CREATE TRIGGER immutable_" +
                        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE +
                        "_update BEFORE UPDATE ON " +
                        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE +
                        " BEGIN SELECT RAISE(ABORT, 'immutable learner-mastery record'); END",
                )
            }

            val requiredFailure =
                checkNotNull(
                    runCatching {
                        openOwner(context, databaseName).use { owner ->
                            owner.findCommitted(receiptQuery)
                        }
                    }.exceptionOrNull(),
                )
            assertTrue(
                generateSequence(requiredFailure) { it.cause }
                    .any { failure ->
                        failure.message?.contains(
                            "dedicated decision fingerprint is invalid",
                        ) == true
                    },
            )
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun evaluationAttemptMustMatchTheImmutableSourceFact() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "open-response-attempt-mismatch.mastery-test.db"
            context.deleteDatabase(databaseName)
            val facts = pendingFacts(attemptOrdinal = 3, hintCount = 1)
            val reviewCaseId =
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 10L },
                ).use { store ->
                    checkNotNull(
                        store.enqueuePendingOpenResponse(
                            sourceFact = preparePendingOpenResponseSourceFact(LEARNER_ID, facts),
                            candidate = preparePendingOpenResponseCandidate(LEARNER_ID, facts),
                        ).reviewCaseId,
                    )
                }
            val command =
                command(
                    reviewCaseId = reviewCaseId,
                    attemptOrdinal = 2,
                    hintCount = 1,
                )

            openOwner(context, databaseName).use { owner ->
                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.REJECTED,
                    owner.submit(
                        command = command,
                        authorization =
                            authorization(
                                scopeFingerprint = command.scopeFingerprint,
                                current = {},
                            ),
                    ).disposition,
                )
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun retryAndReopenAreIdempotentWithoutLearningEventOrProjection() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "open-response-owner-retry.mastery-test.db"
            context.deleteDatabase(databaseName)
            val pending = pendingFacts()
            var reviewCaseId = ""
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW + 10L },
            ).use { store ->
                val queued =
                    store.enqueuePendingOpenResponse(
                        sourceFact = preparePendingOpenResponseSourceFact(LEARNER_ID, pending),
                        candidate = preparePendingOpenResponseCandidate(LEARNER_ID, pending),
                    )
                reviewCaseId = checkNotNull(queued.reviewCaseId)
                assertEquals(PendingOpenResponsePersistenceStatus.QUEUED, queued.status)
            }

            var currentChecks = 0
            val first =
                openOwner(context, databaseName).use { owner ->
                    owner.submit(
                        command = command(reviewCaseId = reviewCaseId),
                        authorization =
                            authorization {
                                currentChecks += 1
                            },
                    )
                }
            val retry =
                openOwner(context, databaseName).use { owner ->
                    owner.submit(
                        command = command(reviewCaseId = reviewCaseId),
                        authorization = authorization {},
                    )
                }

            assertEquals(
                LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
                first.disposition,
            )
            assertEquals(
                LearnerMasteryOpenResponseWeakCandidateDisposition.DUPLICATE,
                retry.disposition,
            )
            assertEquals(first.receiptFingerprint, retry.receiptFingerprint)
            assertNotNull(first.receiptFingerprint)
            assertEquals(3, currentChecks)

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    1L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_candidate_attribution"),
                )
                assertEquals(
                    1L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
                    ),
                )
                assertEquals(
                    1L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_knowledge_projection"),
                )
                assertEquals(
                    "conversation",
                    sqlite.queryText(
                        "SELECT conversation_id FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    setOf("mastery_observation_candidate", "mastery_evidence_review_case"),
                    sqlite.foreignKeyParents(
                        LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun staleOrCrossLearnerAuthorizationFailsClosedAndLeavesPendingFact() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "open-response-owner-stale.mastery-test.db"
            context.deleteDatabase(databaseName)
            val pending = pendingFacts()
            val reviewCaseId =
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 10L },
                ).use { store ->
                    checkNotNull(
                        store.enqueuePendingOpenResponse(
                            sourceFact =
                                preparePendingOpenResponseSourceFact(LEARNER_ID, pending),
                            candidate =
                                preparePendingOpenResponseCandidate(LEARNER_ID, pending),
                        ).reviewCaseId,
                    )
                }

            openOwner(context, databaseName).use { owner ->
                var staleChecks = 0
                assertThrows(IllegalStateException::class.java) {
                    runBlocking {
                        owner.submit(
                            command = command(reviewCaseId = reviewCaseId),
                            authorization =
                                authorization {
                                    staleChecks += 1
                                    check(staleChecks == 1) { "stale scope" }
                                },
                        )
                    }
                }
            }
            openOwner(context, databaseName).use { owner ->
                assertThrows(SecurityException::class.java) {
                    authorization(
                        learnerId = "another-learner",
                        scopeFingerprint =
                            scopeFingerprint(learnerId = "another-learner"),
                    ) {}
                }
                assertTrue(owner.learnerId == LEARNER_ID)
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(1L, sqlite.queryLong("SELECT COUNT(*) FROM mastery_source_fact"))
                assertEquals(
                    1L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_observation_candidate"),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun scopeEpochChangedAfterTransactionStartRollsBackBeforeFinalInsert() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "open-response-owner-toctou.mastery-test.db"
            context.deleteDatabase(databaseName)
            val pending = pendingFacts()
            val reviewCaseId =
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 10L },
                ).use { store ->
                    checkNotNull(
                        store.enqueuePendingOpenResponse(
                            sourceFact =
                                preparePendingOpenResponseSourceFact(LEARNER_ID, pending),
                            candidate =
                                preparePendingOpenResponseCandidate(LEARNER_ID, pending),
                        ).reviewCaseId,
                    )
                }

            openOwner(context, databaseName).use { owner ->
                val epoch = AtomicLong(31L)
                val checks = AtomicInteger(0)
                val finalCheckEntered = CountDownLatch(1)
                val allowFinalCheck = CountDownLatch(1)
                val authorization =
                    authorization(epoch = epoch::get) {
                        if (checks.incrementAndGet() == 3) {
                            finalCheckEntered.countDown()
                            check(allowFinalCheck.await(10L, TimeUnit.SECONDS)) {
                                "Timed out waiting to invalidate the tutor scope"
                            }
                        }
                    }
                val submitted =
                    async(Dispatchers.IO) {
                        runCatching {
                            owner.submit(
                                command = command(reviewCaseId = reviewCaseId),
                                authorization = authorization,
                            )
                        }
                    }
                assertTrue(finalCheckEntered.await(10L, TimeUnit.SECONDS))
                epoch.incrementAndGet()
                allowFinalCheck.countDown()
                assertTrue(submitted.await().exceptionOrNull() is IllegalStateException)
                assertEquals(3, checks.get())
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    0L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_knowledge_projection"),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    @Test
    fun conflictingRetryAndBranchedRevisionFailClosed() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "open-response-owner-conflict.mastery-test.db"
            context.deleteDatabase(databaseName)
            val pending = pendingFacts()
            val reviewCaseId =
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 10L },
                ).use { store ->
                    checkNotNull(
                        store.enqueuePendingOpenResponse(
                            sourceFact =
                                preparePendingOpenResponseSourceFact(LEARNER_ID, pending),
                            candidate =
                                preparePendingOpenResponseCandidate(LEARNER_ID, pending),
                        ).reviewCaseId,
                    )
                }
            val firstKey = fingerprint("candidate-idempotency")
            val revisionKey = fingerprint("candidate-revision")
            val branchKey = fingerprint("candidate-branch")
            openOwner(context, databaseName).use { owner ->
                val first =
                    owner.submit(
                        command =
                            command(
                                reviewCaseId = reviewCaseId,
                                candidateIdempotencyKey = firstKey,
                            ),
                        authorization = authorization {},
                    )
                val changedRetry =
                    owner.submit(
                        command =
                            command(
                                reviewCaseId = reviewCaseId,
                                candidateIdempotencyKey = firstKey,
                                outcome = OpenResponseEvaluationOutcome.CORRECT,
                            ),
                        authorization = authorization {},
                    )
                val unlinkedSecond =
                    owner.submit(
                        command =
                            command(
                                reviewCaseId = reviewCaseId,
                                candidateIdempotencyKey = revisionKey,
                            ),
                        authorization = authorization {},
                    )
                val revision =
                    owner.submit(
                        command =
                            command(
                                reviewCaseId = reviewCaseId,
                                candidateIdempotencyKey = revisionKey,
                                revisionOfCandidateIdempotencyKey = firstKey,
                            ),
                        authorization = authorization {},
                    )
                val branchedRevision =
                    owner.submit(
                        command =
                            command(
                                reviewCaseId = reviewCaseId,
                                candidateIdempotencyKey = branchKey,
                                revisionOfCandidateIdempotencyKey = firstKey,
                            ),
                        authorization = authorization {},
                    )

                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
                    first.disposition,
                )
                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    changedRetry.disposition,
                )
                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    unlinkedSecond.disposition,
                )
                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
                    revision.disposition,
                )
                assertEquals(
                    LearnerMasteryOpenResponseWeakCandidateDisposition.CONFLICT,
                    branchedRevision.disposition,
                )
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    2L,
                    sqlite.queryLong(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"),
                )
            }
            context.deleteDatabase(databaseName)
            Unit
        }

    private fun openOwner(
        context: android.content.Context,
        databaseName: String,
    ): OpenResponseWeakCandidateAndroidTestSession =
        OpenResponseWeakCandidateAndroidTestFixture.open(
            context = context,
            databaseName = databaseName,
            learnerId = LEARNER_ID,
        ).also { session -> currentTestOwner = session.databaseOwner }

    private suspend fun verifyDedicatedScenario(scenario: DedicatedScenario) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "open-response-${scenario.label}.mastery-test.db"
        context.deleteDatabase(databaseName)
        val evidence =
            scenario.roles.mapIndexed { index, _ ->
                VerifiedEphemeralKnowledgeEvidence.create(
                    knowledgeNode =
                        KnowledgeNodeRef(
                            subject = SubjectKind.MATH,
                            knowledgeNodeId = "math.open-response.${scenario.label}.$index",
                            taxonomyVersion = "taxonomy-v1",
                            knowledgePackVersion = "knowledge-pack-v1",
                        ),
                    manifestFingerprint = fingerprint("manifest-${scenario.label}"),
                    activationGeneration = 3L,
                    runtimeBindingId = "runtime-binding-${scenario.label}",
                )
            }
        val facts =
            pendingFacts(
                verifiedKnowledgeEvidence = evidence,
                attemptOrdinal = scenario.attemptOrdinal,
                hintCount = scenario.hintCount,
                answerRevealed = scenario.answerWasRevealed,
            )
        val reviewCaseId =
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW + 10L },
            ).use { store ->
                checkNotNull(
                    store.enqueuePendingOpenResponse(
                        sourceFact = preparePendingOpenResponseSourceFact(LEARNER_ID, facts),
                        candidate = preparePendingOpenResponseCandidate(LEARNER_ID, facts),
                    ).reviewCaseId,
                )
            }
        completeDerivedState(context, databaseName)
        val scope =
            evidence.zip(scenario.roles).map { (verified, role) ->
                LearnerMasteryOpenResponseKnowledgeScopeEntry(
                    refFingerprint =
                        OpenResponseEvaluationTaskFingerprints.knowledgeScopeReference(
                            questionFingerprint = fingerprint("question"),
                            knowledgeNodeReferenceFingerprint =
                                verified.knowledgeNode.canonicalFingerprint,
                            knowledgeManifestFingerprint = verified.manifestFingerprint,
                            knowledgeActivationGeneration = verified.activationGeneration,
                        ),
                    knowledgeNode = verified.knowledgeNode,
                    manifestFingerprint = verified.manifestFingerprint,
                    activationGeneration = verified.activationGeneration,
                    evaluationRole = role,
                )
            }
        val command =
            command(
                reviewCaseId = reviewCaseId,
                candidateIdempotencyKey = fingerprint("candidate-${scenario.label}"),
                outcome = scenario.outcome,
                authorizedKnowledgeScope = scope,
                attemptOrdinal = scenario.attemptOrdinal,
                hintCount = scenario.hintCount,
                answerWasRevealed = scenario.answerWasRevealed,
            )
        val first =
            openOwner(context, databaseName).use { owner ->
                owner.submit(
                    command = command,
                    authorization =
                        authorization(
                            scopeFingerprint = command.scopeFingerprint,
                            current = {},
                        ),
                )
            }
        val retry =
            openOwner(context, databaseName).use { owner ->
                owner.submit(
                    command = command,
                    authorization =
                        authorization(
                            scopeFingerprint = command.scopeFingerprint,
                            current = {},
                        ),
                )
            }
        assertEquals(
            LearnerMasteryOpenResponseWeakCandidateDisposition.PENDING_CONFIRMATION,
            first.disposition,
        )
        assertEquals(
            LearnerMasteryOpenResponseWeakCandidateDisposition.DUPLICATE,
            retry.disposition,
        )
        if (scenario.label == "assistance-only") {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW + 30L },
            ).use { store ->
                val genericAccept =
                    ResolveLearningEvidenceReviewCommand(
                        reviewCaseId = reviewCaseId,
                        decision = LearningEvidenceReviewDecision.ACCEPT,
                        authority = LearningEvidenceReviewAuthority.INDEPENDENT_MODEL_REVIEW,
                        reviewerVersion = "independent-review-v1",
                        reviewEvidenceFingerprint = fingerprint("generic-accept"),
                        idempotencyKey = "generic-accept-${scenario.label}",
                        decidedAtEpochMillis = NOW + 30L,
                    )
                assertEquals(
                    LearningEvidenceReviewWriteDisposition.CONFLICT,
                    store.resolveEvidenceReview(LEARNER_ID, genericAccept).disposition,
                )
            }
        }

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            assertEquals(
                scenario.expectedDisposition,
                sqlite.queryText(
                    "SELECT disposition FROM " +
                        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                ),
            )
            assertEquals(
                scenario.expectedReason,
                sqlite.queryNullableText(
                    "SELECT local_reason FROM " +
                        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                ),
            )
            assertEquals(
                scenario.expectedDirection,
                sqlite.queryNullableText(
                    "SELECT direction FROM " +
                        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                ),
            )
            val expectedEvents = if (scenario.expectedDisposition == "ACCEPTED") 1L else 0L
            assertEquals(expectedEvents, sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event"))
            assertEquals(
                expectedEvents * scenario.roles.count { role ->
                    (scenario.expectedDirection == "POSITIVE" &&
                        role == OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS) ||
                        (scenario.expectedDirection == "NEGATIVE" &&
                            role == OpenResponseKnowledgeRole.LOCATED_GAP)
                }.toLong(),
                sqlite.queryLong("SELECT COUNT(*) FROM mastery_learning_event_attribution"),
            )
            assertEquals(0L, sqlite.queryLong("SELECT COUNT(*) FROM mastery_candidate_attribution"))
        }
        context.deleteDatabase(databaseName)
    }

    private suspend fun completeDerivedState(
        context: android.content.Context,
        databaseName: String,
    ) {
        val database = LearnerMasteryStoreFactory.openDatabaseForTest(context, databaseName)
        try {
            var result = database.masteryDao().rebuildDerivedStateChunk(
                ownerId = "open-response-test-rebuild",
                nowEpochMillis = NOW + 20L,
            )
            var chunk = 0
            while (!result.completed && result.blockedReason == null && chunk < 64) {
                result = database.masteryDao().rebuildDerivedStateChunk(
                    ownerId = "open-response-test-rebuild",
                    nowEpochMillis = NOW + 20L + chunk,
                )
                chunk += 1
            }
            check(result.completed) { "Open-response test could not prepare directional budgets" }
        } finally {
            database.close()
        }
    }

    private fun pendingFacts(
        verifiedKnowledgeEvidence: List<VerifiedEphemeralKnowledgeEvidence> = emptyList(),
        attemptOrdinal: Int = 2,
        hintCount: Int = 1,
        answerRevealed: Boolean = false,
    ): PendingOpenResponseFacts =
        PendingOpenResponseFacts(
            sourceFactId = SOURCE_FACT_ID,
            submissionId = "submission",
            subject = SubjectKind.MATH,
            presentationFingerprint = fingerprint("presentation"),
            context =
                EphemeralTutorProblemLearningContext(
                    problemFingerprint = fingerprint("problem"),
                    problemFamilyFingerprint = fingerprint("problem-family"),
                    tutorTurnReferenceId = "turn-reference",
                    submissionEvidenceFingerprint = fingerprint("submission-evidence"),
                    attributionModelVersion = "attribution-policy-v1",
                    verifiedKnowledgeEvidence = verifiedKnowledgeEvidence,
                ),
            responseCanonicalFingerprint = fingerprint("answer"),
            responsePolicyVersion = "response-policy-v1",
            attemptOrdinal = attemptOrdinal,
            hintCount = hintCount,
            answerWasRevealed = answerRevealed,
            elapsedDurationMillis = 2_000L,
            occurredAtEpochMillis = NOW,
            attestedAtEpochMillis = NOW,
        )

    private fun command(
        learnerId: String = LEARNER_ID,
        reviewCaseId: String,
        candidateIdempotencyKey: String = fingerprint("candidate-idempotency"),
        revisionOfCandidateIdempotencyKey: String? = null,
        outcome: OpenResponseEvaluationOutcome = OpenResponseEvaluationOutcome.INCORRECT,
        authorizedKnowledgeScope: List<LearnerMasteryOpenResponseKnowledgeScopeEntry> = emptyList(),
        attemptOrdinal: Int = 2,
        hintCount: Int = 1,
        answerWasRevealed: Boolean = false,
    ): LearnerMasteryOpenResponseWeakCandidateCommand =
        LearnerMasteryOpenResponseWeakCandidateCommand(
            learnerId = learnerId,
            subject = SubjectKind.MATH,
            sourceFactId = SOURCE_FACT_ID,
            reviewCaseId = reviewCaseId,
            scopeFingerprint =
                scopeFingerprint(
                    learnerId = learnerId,
                    attemptOrdinal = attemptOrdinal,
                    hintCount = hintCount,
                    answerWasRevealed = answerWasRevealed,
                ),
            conversationId = "conversation",
            conversationGeneration = 4L,
            conversationStateVersion = 7L,
            questionDocumentId = "question-document",
            questionRevisionNumber = 3,
            questionFingerprint = fingerprint("question"),
            responseBinding = fingerprint("answer-binding"),
            evidenceRequestId = "evidence-request",
            turnReferenceId = "turn-reference",
            turnOrdinal = 5,
            turnGeneration = 6L,
            modeVersion = 8L,
            requestVersion = 9L,
            attemptOrdinal = attemptOrdinal,
            hintCount = hintCount,
            answerWasRevealed = answerWasRevealed,
            modelTaskRequestId = "model-task-request",
            modelResponseSchemaVersion = 1,
            evaluatorRequestVersion = 9L,
            candidateIdempotencyKey = candidateIdempotencyKey,
            revisionOfCandidateIdempotencyKey = revisionOfCandidateIdempotencyKey,
            evidenceFingerprint = fingerprint("model-evidence"),
            modelOutputFingerprint =
                fingerprint(
                    "model-output:$candidateIdempotencyKey:$outcome:" +
                        authorizedKnowledgeScope.joinToString { it.refFingerprint },
                ),
            modelVersion = "gpt-5.6-luna",
            outcome = outcome,
            authorizedKnowledgeScope = authorizedKnowledgeScope,
            occurredAtEpochMillis = NOW,
        )

    private fun authorization(
        learnerId: String = LEARNER_ID,
        scopeFingerprint: String = scopeFingerprint(learnerId),
        epoch: () -> Long = { 1L },
        current: () -> Unit,
    ): LearnerMasteryOpenResponseWeakCandidateAuthorization {
        val lease =
            LearnerMasteryOpenResponseScopeLease {
                current()
                epoch()
            }
        return CoreDataLearnerMasteryOwnerBridge.bindOpenResponseWeakCandidateAuthorization(
            currentTestOwner,
            learnerId,
            scopeFingerprint,
            lease,
        )
    }

    private fun scopeFingerprint(
        learnerId: String = LEARNER_ID,
        attemptOrdinal: Int = 2,
        hintCount: Int = 1,
        answerWasRevealed: Boolean = false,
    ): String =
        CanonicalSha256("current-open-response-learning-scope-v1")
            .field("learnerId", learnerId)
            .field("conversationId", "conversation")
            .field("conversationGeneration", 4L)
            .field("conversationStateVersion", 7L)
            .field("questionDocumentId", "question-document")
            .field("questionRevisionNumber", 3)
            .field("subject", SubjectKind.MATH.name)
            .field("questionFingerprint", fingerprint("question"))
            .field("responseBinding", fingerprint("answer-binding"))
            .field("evidenceRequestId", "evidence-request")
            .field("modeVersion", 8L)
            .field("turnReferenceId", "turn-reference")
            .field("turnOrdinal", 5)
            .field("turnGeneration", 6L)
            .field("attemptOrdinal", attemptOrdinal)
            .field("hintCount", hintCount)
            .field("answerWasRevealed", answerWasRevealed)
            .field("requestVersion", 9L)
            .finish()

    private fun fingerprint(seed: String): String =
        CanonicalSha256("open-response-owner-instrumented-test")
            .field("seed", seed)
            .finish()

    private fun SQLiteDatabase.queryLong(sql: String): Long =
        rawQuery(sql, emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SQLiteDatabase.queryText(sql: String): String =
        rawQuery(sql, emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SQLiteDatabase.queryNullableText(sql: String): String? =
        rawQuery(sql, emptyArray()).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun SQLiteDatabase.foreignKeyParents(tableName: String): Set<String> =
        rawQuery("PRAGMA foreign_key_list(`$tableName`)", emptyArray()).use { cursor ->
            val tableIndex = cursor.getColumnIndexOrThrow("table")
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(tableIndex))
                }
            }
        }

    private companion object {
        const val LEARNER_ID = "learner-open-response-owner"
        const val SOURCE_FACT_ID = "source-fact-open-response-owner"
        const val NOW = 100_000L
        const val REVIEW_ONLY_REASON =
            "MODEL_SEMANTIC_REVIEW_REQUIRES_NON_MODEL_CONFIRMATION"
    }

    private data class DedicatedScenario(
        val label: String,
        val outcome: OpenResponseEvaluationOutcome,
        val roles: List<OpenResponseKnowledgeRole>,
        val attemptOrdinal: Int = 2,
        val hintCount: Int = 1,
        val answerWasRevealed: Boolean = false,
        val expectedDisposition: String,
        val expectedReason: String? = null,
        val expectedDirection: String? = null,
    )
}
