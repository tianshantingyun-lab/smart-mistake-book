package com.tingyun.smartmistakebook.core.mastery.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleState
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentOutboxAuthenticityProof
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnerMasteryStoreInstrumentedTest {
    @Test
    fun displayProjectionUsesAtomicLedgerRevisionAndBoundedExactLookups() = runBlocking {
        withStore("display-projection.mastery-test.db") { store ->
            val displaySources =
                listOf(
                    sourceFact(
                        id = "display-a",
                        occurredAt = DAY_1,
                        knowledgeNodeId = "math.algebra.a",
                    ),
                    sourceFact(
                        id = "display-b",
                        occurredAt = DAY_1 + 1L,
                        knowledgeNodeId = "math.algebra.b",
                    ),
                    sourceFact(
                        id = "display-c",
                        occurredAt = DAY_1 + 2L,
                        knowledgeNodeId = "math.algebra.c",
                    ),
                )
            displaySources.forEach { source ->
                store.acceptFixtureBindings(source)
                store.ingestSourceFact(source.command)
                store.ingestObservationCandidate(candidate("candidate:${source.command.sourceFactId}", source))
            }

            val observedRevision =
                store.observeDisplayRevision(
                    BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                ).first()
            val overviewResult =
                store.readDisplayOverview(
                    BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                    expectedRevision = observedRevision,
                )
            val overview =
                (overviewResult as LearnerMasteryDisplayOverviewResult.Current).snapshot

            assertEquals(observedRevision, overview.revision)
            assertEquals(9, overview.subjects.size)
            assertEquals(
                LEARNER_MASTERY_DISPLAY_SUBJECTS,
                overview.subjects.map { it.subject },
            )
            assertTrue(
                overview.subjects.single { it.subject == SubjectKind.MATH }
                    .currentState != null,
            )
            assertTrue(
                overview.subjects.filterNot { it.subject == SubjectKind.MATH }
                    .all { it.currentState == null && it.trend == null },
            )
            assertEquals(setOf("taxonomy-v1"), overview.taxonomyVersions)

            val firstPage =
                store.readDisplayKnowledgePage(
                    BoundLearnerMasteryDisplayPageQuery(
                        learnerId = LEARNER_ID,
                        request =
                            LearnerMasteryDisplayPageRequest(
                                subject = SubjectKind.MATH,
                                expectedRevision = overview.revision,
                                orderedKnowledgeNodes =
                                    listOf(displaySources[1].node, displaySources[0].node),
                            ),
                    ),
                ) as LearnerMasteryDisplayPageResult.Current
            assertEquals(2, firstPage.items.size)
            assertEquals(
                listOf(displaySources[1].node, displaySources[0].node),
                firstPage.items.map { it.knowledgeNode },
            )

            val secondPage =
                store.readDisplayKnowledgePage(
                    BoundLearnerMasteryDisplayPageQuery(
                        learnerId = LEARNER_ID,
                        request =
                            LearnerMasteryDisplayPageRequest(
                                subject = SubjectKind.MATH,
                                expectedRevision = overview.revision,
                                orderedKnowledgeNodes = listOf(displaySources[2].node),
                            ),
                    ),
                ) as LearnerMasteryDisplayPageResult.Current
            assertEquals(1, secondPage.items.size)
            assertTrue(
                firstPage.items.map { it.knowledgeNode }.toSet()
                    .intersect(secondPage.items.map { it.knowledgeNode }.toSet())
                    .isEmpty(),
            )

            val later =
                sourceFact(
                    id = "display-d",
                    occurredAt = DAY_1 + 3L,
                    knowledgeNodeId = "math.algebra.d",
                )
            val nextObservedRevision =
                async(start = CoroutineStart.UNDISPATCHED) {
                    store.observeDisplayRevision(
                        BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                    ).first { revision ->
                        revision.ledgerSequence > overview.revision.ledgerSequence
                    }
                }
            store.acceptFixtureBindings(later)
            store.ingestSourceFact(later.command)
            store.ingestObservationCandidate(candidate("candidate:display-d", later))
            assertTrue(
                nextObservedRevision.await().ledgerSequence >
                    overview.revision.ledgerSequence,
            )
            val changed =
                store.readDisplayKnowledgePage(
                    BoundLearnerMasteryDisplayPageQuery(
                        learnerId = LEARNER_ID,
                        request =
                            LearnerMasteryDisplayPageRequest(
                                subject = SubjectKind.MATH,
                                expectedRevision = overview.revision,
                                orderedKnowledgeNodes = listOf(displaySources[0].node),
                            ),
                    ),
                )
            assertTrue(changed is LearnerMasteryDisplayPageResult.RevisionChanged)
        }
    }

    @Test
    fun explicitEraseClearsAllLearnerRowsAndReinstallsGuards() = runBlocking {
        withStore("erase-all.mastery-test.db") { store ->
            val source =
                sourceFact(
                    id = "erase-a",
                    occurredAt = DAY_1,
                    knowledgeNodeId = "math.algebra.erase",
                )
            store.acceptFixtureBindings(source)
            store.ingestSourceFact(source.command)
            store.ingestObservationCandidate(candidate("candidate:erase-a", source))

            val beforeRevision =
                store.observeDisplayRevision(
                    BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                ).first()
            val beforeOverview =
                store.readDisplayOverview(
                    BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                    expectedRevision = beforeRevision,
                ) as LearnerMasteryDisplayOverviewResult.Current
            assertTrue(
                beforeOverview.snapshot.subjects
                    .single { it.subject == SubjectKind.MATH }
                    .currentState != null,
            )

            val result = store.eraseAllLearnerData()
            assertTrue(result.erasedAtEpochMillis >= 0L)

            val afterRevision =
                store.observeDisplayRevision(
                    BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                ).first()
            val afterOverview =
                store.readDisplayOverview(
                    BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                    expectedRevision = afterRevision,
                ) as LearnerMasteryDisplayOverviewResult.Current
            assertTrue(
                afterOverview.snapshot.subjects.all { it.currentState == null },
            )

            store.acceptFixtureBindings(source)
            val storedAgain = store.ingestSourceFact(source.command)
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                storedAgain.status,
            )
        }
    }

    @Test
    fun reviewObservationProjectsIntoSameSubjectMasteryContextAndImprovesAfterCorrectRetry() =
        runBlocking {
            withStore("review-context-loop.mastery-test.db") { store ->
                val source =
                    sourceFact(
                        id = "review-loop-a",
                        occurredAt = DAY_1,
                        outcome = ObservedLearningOutcome.INCORRECT,
                    )
                store.acceptFixtureBindings(source)
                val authority =
                    RoomLearnerMasteryAuthority(
                        store = store,
                        learnerId = LEARNER_ID,
                        nowEpochMillis = { NOW },
                    )
                authority.acceptFixtureReviewObservation(
                    source = source,
                    outcome = ReviewVerificationOutcome.INCORRECT,
                    eventSuffix = "error",
                    occurredAtEpochMillis = DAY_2,
                )

                val request =
                    LocalMasteryContextRequest.fromKnowledgeNodes(
                        subject = SubjectKind.MATH,
                        exactKnowledgeNodes = listOf(source.node),
                        fallbackLimit = 0,
                    )
                val afterError =
                    store.queryLocalMasteryContext(
                        BoundLocalMasteryContextQuery(LEARNER_ID, request),
                    )
                val errorItem =
                    afterError.items.single {
                        it.selection == LocalMasteryContextSelection.EXACT
                    }
                assertEquals(
                    KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                    errorItem.currentRecallState,
                )

                (0 until 5).forEach { index ->
                    authority.acceptFixtureReviewObservation(
                        source = source,
                        outcome = ReviewVerificationOutcome.CORRECT,
                        eventSuffix = "recover-$index",
                        occurredAtEpochMillis = DAY_3 + index,
                    )
                }
                val afterCorrect =
                    store.queryLocalMasteryContext(
                        BoundLocalMasteryContextQuery(LEARNER_ID, request),
                    )
                val recoveredItem =
                    afterCorrect.items.single {
                        it.selection == LocalMasteryContextSelection.EXACT
                    }
                assertEquals(
                    KnowledgeMasteryState.NEEDS_REINFORCEMENT,
                    recoveredItem.currentRecallState,
                )
                val digest =
                    store.querySubjectDigest(
                        SubjectMasteryDigestQuery(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH,
                            focusLimit = 16,
                        ),
                    )
                val digestItem =
                    digest.focus.single {
                        it.knowledgeNode.canonicalFingerprint ==
                            source.node.canonicalFingerprint
                    }
                assertTrue(
                    "Correct review observations must write positive evidence",
                    digestItem.lastPositiveAtEpochMillis != null,
                )
                assertTrue(
                    "Correct review observations must count five distinct presentations",
                    digestItem.distinctPresentationCount >= 5L,
                )
            }
        }

    @Test
    fun steadyProjectionAgesAfterRecallWindowAndFreshEvidenceRecovers() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "recall-age-recovery.mastery-test.db"
            context.deleteDatabase(databaseName)
            var clock = NOW
            try {
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { clock },
                ).use { store ->
                    val source =
                        sourceFact(
                            id = "recall-age-a",
                            occurredAt = clock - 1_000L,
                        )
                    store.acceptFixtureBindings(source)
                    val authority =
                        RoomLearnerMasteryAuthority(
                            store = store,
                            learnerId = LEARNER_ID,
                            nowEpochMillis = { clock },
                        )
                    val request =
                        LocalMasteryContextRequest.fromKnowledgeNodes(
                            subject = SubjectKind.MATH,
                            exactKnowledgeNodes = listOf(source.node),
                            fallbackLimit = 0,
                        )

                    var reachedSteady = false
                    for (index in 0 until 40) {
                        authority.acceptFixtureReviewObservation(
                            source = source,
                            outcome = ReviewVerificationOutcome.CORRECT,
                            eventSuffix = "steady-$index",
                            occurredAtEpochMillis = clock - 900L + index * 10L,
                        )
                        val current =
                            store.queryLocalMasteryContext(
                                BoundLocalMasteryContextQuery(LEARNER_ID, request),
                            )
                        val item =
                            current.items.single {
                                it.selection == LocalMasteryContextSelection.EXACT
                            }
                        if (item.currentRecallState == KnowledgeMasteryState.STEADY) {
                            reachedSteady = true
                            break
                        }
                    }
                    assertTrue(
                        "Projection did not reach STEADY while within the recall window",
                        reachedSteady,
                    )
                    val stable =
                        store.queryLocalMasteryContext(
                            BoundLocalMasteryContextQuery(LEARNER_ID, request),
                        )
                    val stableItem =
                        stable.items.single {
                            it.selection == LocalMasteryContextSelection.EXACT
                        }
                    assertEquals(
                        KnowledgeMasteryState.STEADY,
                        stableItem.historicalState,
                    )
                    assertEquals(
                        KnowledgeMasteryState.STEADY,
                        stableItem.currentRecallState,
                    )

                    clock = NOW + 200L * DAY_MILLIS
                    val aged =
                        store.queryLocalMasteryContext(
                            BoundLocalMasteryContextQuery(LEARNER_ID, request),
                        )
                    val agedItem =
                        aged.items.single {
                            it.selection == LocalMasteryContextSelection.EXACT
                        }
                    assertEquals(
                        KnowledgeMasteryState.STEADY,
                        agedItem.historicalState,
                    )
                    assertTrue(
                        "Steady projection must age out of the recall window",
                        agedItem.currentRecallState != KnowledgeMasteryState.STEADY,
                    )

                    authority.acceptFixtureReviewObservation(
                        source = source,
                        outcome = ReviewVerificationOutcome.CORRECT,
                        eventSuffix = "fresh-recovery",
                        occurredAtEpochMillis = clock - 1_000L,
                        receivedAtEpochMillis = clock,
                    )
                    val recovered =
                        store.queryLocalMasteryContext(
                            BoundLocalMasteryContextQuery(LEARNER_ID, request),
                        )
                    val recoveredItem =
                        recovered.items.single {
                            it.selection == LocalMasteryContextSelection.EXACT
                        }
                    assertEquals(
                        KnowledgeMasteryState.STEADY,
                        recoveredItem.currentRecallState,
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun displaySubjectTimelineReturnsRecentDaysFromRealLedger() = runBlocking {
        withStore("display-timeline.mastery-test.db") { store ->
            val sources =
                listOf(
                    sourceFact(
                        id = "timeline-a",
                        occurredAt = DAY_1,
                        knowledgeNodeId = "math.algebra.a",
                    ),
                    sourceFact(
                        id = "timeline-b",
                        occurredAt = DAY_2,
                        knowledgeNodeId = "math.algebra.b",
                    ),
                )
            sources.forEach { source ->
                store.acceptFixtureBindings(source)
                store.ingestSourceFact(source.command)
                store.ingestObservationCandidate(
                    candidate("candidate:${source.command.sourceFactId}", source),
                )
            }

            val observedRevision =
                store.observeDisplayRevision(
                    BoundLearnerMasteryDisplayQuery(LEARNER_ID),
                ).first()
            val result =
                store.readDisplaySubjectTimeline(
                    BoundLearnerMasteryDisplayTimelineQuery(
                        learnerId = LEARNER_ID,
                        request =
                            LearnerMasteryDisplayTimelineRequest(
                                subject = SubjectKind.MATH,
                                expectedRevision = observedRevision,
                                sinceEpochMillis = DAY_1,
                                dayLimit = 7,
                            ),
                    ),
                )

            val current = result as LearnerMasteryDisplayTimelineResult.Current
            assertEquals(observedRevision, current.revision)
            assertEquals(2, current.entries.size)
            assertTrue(current.entries.all { it.observationCount == 1 })
            assertEquals(
                listOf(DAY_1 / DAY_MILLIS, DAY_2 / DAY_MILLIS),
                current.entries.map { it.utcEpochDay },
            )
        }
    }

    @Test
    fun migrationFromV2AddsCurrentIndexesWithoutManufacturingAuthorityRecords() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val databaseName = "projection-index-migration.mastery-test.db"
        val databasePath = context.getDatabasePath(databaseName)
        context.deleteDatabase(databaseName)
        val helper =
            MigrationTestHelper(
                instrumentation = instrumentation,
                file = databasePath,
                driver = AndroidSQLiteDriver(),
                databaseClass = LearnerMasteryRoomDatabase::class,
            )
        try {
            helper.createDatabase(2).close()

            val migrated =
                Room.databaseBuilder(
                    context.applicationContext,
                    LearnerMasteryRoomDatabase::class.java,
                    databaseName,
                ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            try {
                migrated.displayDao().readExactKnowledgeProjections(
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.MATH.name,
                    stableNodeFingerprints = listOf("a".repeat(64)),
                )
            } finally {
                migrated.close()
            }

            SQLiteDatabase.openDatabase(
                databasePath.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                sqlite.rawQuery(
                    """
                    SELECT name
                    FROM sqlite_master
                    WHERE type = 'index'
                      AND name = ?
                    """.trimIndent(),
                    arrayOf(
                        "index_mastery_knowledge_projection_learner_id_subject_" +
                            "stable_node_identity_fingerprint",
                    ),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                }
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM mastery_calibration_snapshot",
                    emptyArray(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(27L, cursor.getLong(0))
                }
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM mastery_evidence_review_resolution",
                    emptyArray(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
                sqlite.rawQuery(
                    "SELECT COUNT(*) FROM mastery_learning_evidence_supersession",
                    emptyArray(),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0L, cursor.getLong(0))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun duplicateAndUnsupportedCandidatesNeverApplyTwice() = runBlocking {
        withStore("idempotency.mastery-test.db") { store ->
            val source = sourceFact(id = "fact-1", occurredAt = DAY_2)
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureBindings(source),
            )
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(source.command).status,
            )
            assertEquals(
                LearningSourceFactIngestStatus.DUPLICATE,
                store.ingestSourceFact(source.command).status,
            )

            val candidate = candidate(id = "candidate-1", source = source)
            val admitted = store.ingestObservationCandidate(candidate)
            assertEquals(
                LearningObservationDisposition.ADMITTED,
                admitted.disposition,
            )
            val admittedReceipt = checkNotNull(admitted.terminalReceipt)
            assertEquals(LearningObservationDisposition.ADMITTED, admittedReceipt.disposition)
            val duplicate = store.ingestObservationCandidate(candidate)
            assertEquals(
                LearningObservationDisposition.DUPLICATE,
                duplicate.disposition,
            )
            val duplicateReceipt = checkNotNull(duplicate.terminalReceipt)
            assertEquals(admittedReceipt.receiptFingerprint, duplicateReceipt.receiptFingerprint)
            assertEquals(admittedReceipt.disposition, duplicateReceipt.disposition)
            assertEquals(admittedReceipt.inertReason, duplicateReceipt.inertReason)
            val originConflict =
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate-1",
                        source = source,
                        candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                    ),
                )
            assertEquals(LearningObservationDisposition.CONFLICT, originConflict.disposition)
            assertEquals(
                LearningObservationInertReason.IDEMPOTENCY_CONFLICT,
                originConflict.inertReason,
            )
            val attempt =
                store.readPendingMessages(LEARNER_ID, NOW, 10)
                    .single()
                    .envelope.payload as LearningAttemptRecordedV1
            assertEquals(source.revision, attempt.problemRevision)
            assertEquals("review-session:fact-1", attempt.reviewSessionId)
            assertEquals("review-queue:fact-1", attempt.reviewQueueItemId)
            assertEquals("submission:fact-1", attempt.submissionId)
            assertEquals("presentation:fact-1", attempt.presentationId)
            val afterDuplicate = store.mathDigest()
            assertEquals(1, afterDuplicate.focus.size)
            assertEquals(
                1,
                afterDuplicate.stateCounts.needsReinforcement +
                    afterDuplicate.stateCounts.familiarizing +
                    afterDuplicate.stateCounts.steady,
            )
            assertEquals(
                1,
                store.querySubjectTimeline(
                    SubjectMasteryTimelineQuery(LEARNER_ID, SubjectKind.MATH),
                ).single().observationCount,
            )
            assertTrue(
                store.querySubjectDigest(
                    SubjectMasteryDigestQuery(LEARNER_ID, SubjectKind.PHYSICS),
                ).focus.isEmpty(),
            )

            val unknownFact = sourceFact(id = "fact-unknown", occurredAt = DAY_3)
            store.ingestSourceFact(unknownFact.command)
            val unknownPolicyResult =
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate-unknown",
                        source = unknownFact,
                        requestedPolicyVersion = "future-policy-v99",
                    ),
                )
            assertEquals(LearningObservationDisposition.INERT, unknownPolicyResult.disposition)
            assertEquals(
                LearningObservationInertReason.UNSUPPORTED_PROJECTION_POLICY,
                unknownPolicyResult.inertReason,
            )
            val unknownReceipt = checkNotNull(unknownPolicyResult.terminalReceipt)
            val unknownDuplicate =
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate-unknown",
                        source = unknownFact,
                        requestedPolicyVersion = "future-policy-v99",
                    ),
                )
            assertEquals(LearningObservationDisposition.DUPLICATE, unknownDuplicate.disposition)
            val unknownDuplicateReceipt = checkNotNull(unknownDuplicate.terminalReceipt)
            assertEquals(LearningObservationDisposition.INERT, unknownDuplicateReceipt.disposition)
            assertEquals(
                LearningObservationInertReason.UNSUPPORTED_PROJECTION_POLICY,
                unknownDuplicateReceipt.inertReason,
            )
            assertEquals(
                unknownReceipt.receiptFingerprint,
                unknownDuplicateReceipt.receiptFingerprint,
            )

            val missingProofResult =
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate-missing-proof",
                        source = sourceFact("fact-never-stored", DAY_3),
                    ),
                )
            assertEquals(LearningObservationDisposition.INERT, missingProofResult.disposition)
            assertEquals(
                LearningObservationInertReason.MISSING_SOURCE_PROOF,
                missingProofResult.inertReason,
            )

            val unsupportedSource =
                sourceFact(
                    id = "fact-unsupported-source",
                    occurredAt = DAY_3,
                    sourcePolicyVersion = "future-source-v99",
                )
            store.acceptFixtureBindings(unsupportedSource)
            assertEquals(
                LearningSourceFactIngestStatus.STORED_INERT,
                store.ingestSourceFact(unsupportedSource.command).status,
            )
            val unsupportedSourceResult =
                store.ingestObservationCandidate(
                    candidate("candidate-unsupported-source", unsupportedSource),
                )
            assertEquals(
                LearningObservationInertReason.UNSUPPORTED_SOURCE_POLICY,
                unsupportedSourceResult.inertReason,
            )

            val finalDigest = store.mathDigest()
            assertEquals(afterDuplicate, finalDigest)
            assertEquals(
                1,
                store.querySubjectTimeline(
                    SubjectMasteryTimelineQuery(LEARNER_ID, SubjectKind.MATH),
                ).single().observationCount,
            )
        }
    }

    @Test
    fun pendingOpenResponsePersistsOneDirectionUnknownReviewCaseWithoutProjection() =
        runBlocking {
            withStore("pending-open-response.mastery-test.db") { store ->
                val sourceFact = pendingOpenResponseSourceFact("a".repeat(64))
                val candidate = pendingOpenResponseCandidate()

                val queued = store.enqueuePendingOpenResponse(sourceFact, candidate)
                val duplicate = store.enqueuePendingOpenResponse(sourceFact, candidate)
                val conflict =
                    store.enqueuePendingOpenResponse(
                        pendingOpenResponseSourceFact("b".repeat(64)),
                        candidate,
                    )
                val pending =
                    store.readPendingEvidenceReviews(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        limit = 8,
                    )

                assertEquals(PendingOpenResponsePersistenceStatus.QUEUED, queued.status)
                assertEquals(PendingOpenResponsePersistenceStatus.DUPLICATE, duplicate.status)
                assertEquals(PendingOpenResponsePersistenceStatus.CONFLICT, conflict.status)
                assertEquals(queued.reviewCaseId, duplicate.reviewCaseId)
                assertEquals(
                    listOf(sourceFact.sourceFactId),
                    pending.map(PendingLearningEvidenceReview::sourceFactId),
                )
                assertTrue(
                    store.querySubjectTimeline(
                        SubjectMasteryTimelineQuery(LEARNER_ID, SubjectKind.MATH),
                    ).isEmpty(),
                )
            }
        }

    @Test
    fun onOpenReplacesSpoofedReviewResolutionGuardAndRejectsCrossScopeWrites() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "review-resolution-guard-upgrade.mastery-test.db"
            context.deleteDatabase(databaseName)
            val sourceFact = pendingOpenResponseSourceFact("c".repeat(64))
            val candidate = pendingOpenResponseCandidate()
            var reviewCaseId = ""
            try {
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW },
                ).use { store ->
                    reviewCaseId =
                        checkNotNull(
                            store.enqueuePendingOpenResponse(sourceFact, candidate).reviewCaseId,
                        )
                    assertTrue(reviewCaseId.isNotBlank())
                }

                var calibrationSnapshotFingerprint = ""
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    sqlite.rawQuery(
                        """
                        SELECT calibration_snapshot_fingerprint
                        FROM mastery_evidence_review_case
                        WHERE review_case_id = ?
                          AND learner_id = ?
                          AND subject = 'MATH'
                          AND calibration_binding_status =
                              '${MasteryCalibrationBindingStatus.BOUND.name}'
                        """.trimIndent(),
                        arrayOf(reviewCaseId, LEARNER_ID),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        calibrationSnapshotFingerprint = cursor.getString(0)
                    }
                    sqlite.execSQL(
                        "DROP TRIGGER IF EXISTS " +
                            "validate_mastery_evidence_review_resolution_calibration_insert",
                    )
                    sqlite.execSQL(
                        """
                        CREATE TRIGGER
                            validate_mastery_evidence_review_resolution_calibration_insert
                        BEFORE INSERT ON mastery_evidence_review_resolution
                        BEGIN
                            SELECT 1;
                        END
                        """.trimIndent(),
                    )
                }

                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 1L },
                ).use { store ->
                    assertEquals(
                        reviewCaseId,
                        store.readPendingEvidenceReviews(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH,
                            limit = 1,
                        ).single().reviewCaseId,
                    )
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    sqlite.execSQL("PRAGMA foreign_keys = OFF")
                    assertEquals(0L, sqlite.longForQuery("PRAGMA foreign_keys"))
                    val installedSql =
                        sqlite.rawQuery(
                            """
                            SELECT sql
                            FROM sqlite_master
                            WHERE type = 'trigger'
                              AND name =
                                  'validate_mastery_evidence_review_resolution_calibration_insert'
                            """.trimIndent(),
                            emptyArray<String>(),
                        ).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            cursor.getString(0)
                        }
                    assertTrue(
                        installedSql.contains(
                            "bound_review_case.learner_id = NEW.learner_id",
                        ),
                    )
                    assertTrue(
                        installedSql.contains(
                            "bound_review_case.subject = NEW.subject",
                        ),
                    )

                    fun rejectedInsert(
                        id: String,
                        learnerId: String,
                        subject: String,
                        snapshotFingerprint: String,
                    ): Boolean =
                        runCatching {
                            sqlite.execSQL(
                                """
                                INSERT INTO mastery_evidence_review_resolution (
                                    resolution_id, review_case_id, learner_id, subject,
                                    decision, authority, reviewer_version,
                                    review_evidence_fingerprint,
                                    calibration_snapshot_fingerprint, idempotency_key,
                                    resolution_fingerprint, decided_at_epoch_millis
                                ) VALUES (
                                    '$id', '$reviewCaseId', '$learnerId', '$subject',
                                    'REJECT', 'INDEPENDENT_MODEL_REVIEW', 'reviewer-v1',
                                    '${CanonicalSha256("guard-review-evidence-v1").field("id", id).finish()}',
                                    '$snapshotFingerprint', '$id-idempotency',
                                    '${CanonicalSha256("guard-resolution-v1").field("id", id).finish()}',
                                    $NOW
                                )
                                """.trimIndent(),
                            )
                        }.isFailure

                    assertTrue(
                        rejectedInsert(
                            id = "wrong-learner-resolution",
                            learnerId = "another-learner",
                            subject = SubjectKind.MATH.name,
                            snapshotFingerprint = calibrationSnapshotFingerprint,
                        ),
                    )
                    assertTrue(
                        rejectedInsert(
                            id = "wrong-subject-resolution",
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.PHYSICS.name,
                            snapshotFingerprint = calibrationSnapshotFingerprint,
                        ),
                    )
                    assertTrue(
                        rejectedInsert(
                            id = "wrong-fingerprint-resolution",
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH.name,
                            snapshotFingerprint = "f".repeat(64),
                        ),
                    )
                    assertEquals(
                        0L,
                        sqlite.longForQuery(
                            "SELECT COUNT(*) FROM mastery_evidence_review_resolution",
                        ),
                    )
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun authorityIdentityPreventsSameSubmissionFromMintingAnotherEvent() = runBlocking {
        withStore("authority-identity.mastery-test.db") { store ->
            val first =
                sourceFact(
                    id = "authority-first",
                    occurredAt = DAY_1,
                    authorityIdentitySeed = "same-real-submission",
                )
            val renamedReplay =
                sourceFact(
                    id = "authority-renamed-replay",
                    occurredAt = DAY_1 + 1L,
                    authorityIdentitySeed = "same-real-submission",
                )
            store.acceptFixtureBindings(first)
            store.acceptFixtureBindings(renamedReplay)

            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(first.command).status,
            )
            assertEquals(
                LearningObservationDisposition.ADMITTED,
                store.ingestObservationCandidate(
                    candidate("authority-first-candidate", first),
                ).disposition,
            )
            assertEquals(
                LearningSourceFactIngestStatus.CONFLICT,
                store.ingestSourceFact(renamedReplay.command).status,
            )
            assertEquals(
                LearningObservationDisposition.INERT,
                store.ingestObservationCandidate(
                    candidate("authority-renamed-candidate", renamedReplay),
                ).disposition,
            )
            assertEquals(
                1,
                store.querySubjectTimeline(
                    SubjectMasteryTimelineQuery(LEARNER_ID, SubjectKind.MATH),
                ).single().observationCount,
            )
        }
    }

    @Test
    fun modelLeaseUsesOnlyItsHostBoundFactAndCoarsensEveryStoreOutcome() = runBlocking {
        withStore("model-source-fact-lease.mastery-test.db") { store ->
            val admittedSource = sourceFact(id = "lease-fact-a", occurredAt = DAY_2)
            val differentSource = sourceFact(id = "lease-fact-b", occurredAt = DAY_3)
            listOf(admittedSource, differentSource).forEach { source ->
                assertEquals(
                    MasteryInboundDisposition.APPLIED,
                    store.acceptFixtureBindings(source),
                )
                assertEquals(
                    LearningSourceFactIngestStatus.STORED,
                    store.ingestSourceFact(source.command).status,
                )
            }
            val provider =
                assembleLearnerMasteryRuntimeCapabilities(
                    RoomLearnerMasteryAuthority(
                        store = store,
                        learnerId = LEARNER_ID,
                        nowEpochMillis = { NOW },
                    ),
                ).modelHostHandoff()
            val modelCommand =
                SubmitLearningObservationCandidateCommand(
                    proposedAttributions =
                        listOf(
                            ProposedKnowledgeAttribution(
                                knowledgeNode = admittedSource.node,
                                problemBinding = admittedSource.binding,
                                role = MasteryAttributionRole.PRIMARY,
                                certainty = MasteryAttributionCertainty.DIRECT,
                            ),
                        ),
                    confidence = MasteryCandidateConfidence.HIGH,
                )
            val admittedLease =
                provider.openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = admittedSource.command.sourceFactId,
                    modelVersion = "instrumented-model-v1",
                    requestVersion = "request-admitted",
                    modeVersion = "guided-1",
                )

            val admitted =
                admittedLease.access.candidateSink.submitCandidate(modelCommand)
            val second = admittedLease.access.candidateSink.submitCandidate(modelCommand)
            val conflictingRetry =
                admittedLease.access.candidateSink.submitCandidate(
                    SubmitLearningObservationCandidateCommand(
                        proposedAttributions = modelCommand.proposedAttributions,
                        confidence = MasteryCandidateConfidence.MEDIUM,
                    ),
                )
            val repeatedConflict =
                admittedLease.access.candidateSink.submitCandidate(
                    SubmitLearningObservationCandidateCommand(
                        proposedAttributions = modelCommand.proposedAttributions,
                        confidence = MasteryCandidateConfidence.MEDIUM,
                    ),
                )
            val afterAdmission = store.mathDigest()

            val crossFact =
                provider.openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = differentSource.command.sourceFactId,
                    modelVersion = "instrumented-model-v1",
                    requestVersion = "request-cross-fact",
                    modeVersion = "guided-1",
                ).access.candidateSink.submitCandidate(modelCommand)
            val crossSubject =
                provider.openLease(
                    subject = SubjectKind.PHYSICS,
                    sourceFactId = admittedSource.command.sourceFactId,
                    modelVersion = "instrumented-model-v1",
                    requestVersion = "request-cross-subject",
                    modeVersion = "guided-1",
                ).access.candidateSink.submitCandidate(modelCommand)

            assertEquals(ModelLearningObservationDisposition.RECEIVED, admitted.disposition)
            assertEquals(admitted.receiptFingerprint, second.receiptFingerprint)
            assertNotEquals(
                admitted.receiptFingerprint,
                conflictingRetry.receiptFingerprint,
            )
            assertEquals(
                conflictingRetry.receiptFingerprint,
                repeatedConflict.receiptFingerprint,
            )
            assertEquals(ModelLearningObservationDisposition.RECEIVED, crossFact.disposition)
            assertEquals(ModelLearningObservationDisposition.RECEIVED, crossSubject.disposition)
            assertEquals(afterAdmission, store.mathDigest())
            assertTrue(
                store.querySubjectDigest(
                    SubjectMasteryDigestQuery(LEARNER_ID, SubjectKind.PHYSICS),
                ).focus.isEmpty(),
            )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            SQLiteDatabase.openDatabase(
                context.getDatabasePath("model-source-fact-lease.mastery-test.db").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    4L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                            " WHERE terminal_reason = " +
                            "'${ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT.name}'",
                    ),
                )
            }
        }
    }

    @Test
    fun logicalModelAttemptBudgetSurvivesGateRecreationAndModeRotation() = runBlocking {
        val databaseName = "model-logical-attempt-restart.mastery-test.db"
        withStore(databaseName) { store ->
            val source =
                sourceFact(
                    id = "logical-attempt-restart",
                    occurredAt = DAY_2,
                    responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                )
            assertEquals(MasteryInboundDisposition.APPLIED, store.acceptFixtureBindings(source))
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(source.command).status,
            )
            fun provider(): LearnerMasteryModelAccessProvider =
                assembleLearnerMasteryRuntimeCapabilities(
                    RoomLearnerMasteryAuthority(
                        store = store,
                        learnerId = LEARNER_ID,
                        nowEpochMillis = { NOW },
                    ),
                ).modelHostHandoff()

            val first =
                provider().openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = source.command.sourceFactId,
                    modelVersion = "restart-model-v1",
                    requestVersion = "restart-request-v1",
                    modeVersion = "guided-1",
                ).access.candidateSink.submitCandidate(modelCandidate(source))
            val changedProposal =
                provider().openLease(
                    subject = SubjectKind.MATH,
                    sourceFactId = source.command.sourceFactId,
                    modelVersion = "restart-model-v1",
                    requestVersion = "restart-request-v1",
                    modeVersion = "direct-2",
                ).access.candidateSink.submitCandidate(
                    modelCandidate(
                        source = source,
                        confidence = MasteryCandidateConfidence.MEDIUM,
                    ),
                )

            assertNotEquals(first.receiptFingerprint, changedProposal.receiptFingerprint)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    1L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_observation_candidate"),
                )
                assertEquals(
                    1L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                            " WHERE terminal_reason = " +
                            "'${ModelSubmissionTerminalReason.LOGICAL_ATTEMPT_CONFLICT.name}'",
                    ),
                )
            }
        }
    }

    @Test
    fun semanticModelRejectionsPersistDistinctReceiptsWithoutLearningEvents() = runBlocking {
        val databaseName = "model-rejection-receipts.mastery-test.db"
        withStore(databaseName) { store ->
            val low = sourceFact(id = "audit-low", occurredAt = DAY_1)
            val empty = sourceFact(id = "audit-empty", occurredAt = DAY_1 + 1L)
            val crossSubject = sourceFact(id = "audit-cross", occurredAt = DAY_1 + 2L)
            val unauthorized =
                sourceFact(id = "audit-unauthorized", occurredAt = DAY_1 + 3L)
            val ambiguous =
                sourceFact(
                    id = "audit-ambiguous",
                    occurredAt = DAY_1 + 4L,
                    outcome = ObservedLearningOutcome.INCORRECT,
                    additionalKnowledgeNodeIds =
                        listOf("math.function.quadratic.discriminant"),
                )
            listOf(low, empty, crossSubject).forEach { source ->
                assertEquals(
                    MasteryInboundDisposition.APPLIED,
                    store.acceptFixtureBindings(source),
                )
                assertEquals(
                    LearningSourceFactIngestStatus.STORED,
                    store.ingestSourceFact(source.command).status,
                )
            }
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(unauthorized.command).status,
            )
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureBindings(
                    source = ambiguous,
                    bindings = ambiguous.bindings,
                ),
            )
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(ambiguous.command).status,
            )
            val provider =
                assembleLearnerMasteryRuntimeCapabilities(
                    RoomLearnerMasteryAuthority(
                        store = store,
                        learnerId = LEARNER_ID,
                        nowEpochMillis = { NOW },
                    ),
                ).modelHostHandoff()

            suspend fun submit(
                requestVersion: String,
                subject: SubjectKind,
                sourceFactId: String,
                command: SubmitLearningObservationCandidateCommand,
            ): ModelLearningObservationResult =
                provider.openLease(
                    subject = subject,
                    sourceFactId = sourceFactId,
                    modelVersion = "receipt-audit-model-v1",
                    requestVersion = requestVersion,
                    modeVersion = "direct-1",
                ).access.candidateSink.submitCandidate(command)

            val results =
                listOf(
                    submit(
                        requestVersion = "audit-request-low",
                        subject = SubjectKind.MATH,
                        sourceFactId = low.command.sourceFactId,
                        command =
                            modelCandidate(
                                low,
                                confidence = MasteryCandidateConfidence.LOW,
                            ),
                    ),
                    submit(
                        requestVersion = "audit-request-missing",
                        subject = SubjectKind.MATH,
                        sourceFactId = "audit-missing-proof",
                        command = modelCandidate(low),
                    ),
                    submit(
                        requestVersion = "audit-request-empty",
                        subject = SubjectKind.MATH,
                        sourceFactId = empty.command.sourceFactId,
                        command =
                            SubmitLearningObservationCandidateCommand(
                                proposedAttributions = emptyList(),
                                confidence = MasteryCandidateConfidence.HIGH,
                            ),
                    ),
                    submit(
                        requestVersion = "audit-request-cross",
                        subject = SubjectKind.PHYSICS,
                        sourceFactId = crossSubject.command.sourceFactId,
                        command = modelCandidate(crossSubject),
                    ),
                    submit(
                        requestVersion = "audit-request-unauthorized",
                        subject = SubjectKind.MATH,
                        sourceFactId = unauthorized.command.sourceFactId,
                        command = modelCandidate(unauthorized),
                    ),
                    submit(
                        requestVersion = "audit-request-ambiguous",
                        subject = SubjectKind.MATH,
                        sourceFactId = ambiguous.command.sourceFactId,
                        command = modelCandidate(ambiguous),
                    ),
                )

            assertTrue(
                results.all {
                    it.disposition == ModelLearningObservationDisposition.RECEIVED
                },
            )
            assertEquals(6, results.map { it.receiptFingerprint }.toSet().size)

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    6L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE,
                    ),
                )
                assertEquals(
                    5L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_observation_candidate",
                    ),
                )
                assertEquals(
                    5L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_admission_receipt",
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                listOf(
                    ModelSubmissionTerminalReason.LOW_CONFIDENCE,
                    ModelSubmissionTerminalReason.MISSING_SOURCE_PROOF,
                    ModelSubmissionTerminalReason.NO_ATTRIBUTION,
                    ModelSubmissionTerminalReason.PERMISSION_SCOPE_MISMATCH,
                    ModelSubmissionTerminalReason.PROBLEM_BINDING_NOT_AUTHORIZED,
                    ModelSubmissionTerminalReason.UNLOCALIZED_MULTI_KNOWLEDGE_NEGATIVE,
                ).forEach { reason ->
                    assertEquals(
                        1L,
                        sqlite.longForQuery(
                            "SELECT COUNT(*) FROM " +
                                LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                                " WHERE terminal_reason = '${reason.name}'",
                        ),
                    )
                }
                assertEquals(
                    5L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                            " WHERE candidate_id IS NOT NULL " +
                            "AND admission_receipt_fingerprint IS NOT NULL",
                    ),
                )
                assertEquals(
                    0,
                    sqlite.rowCountForQuery(
                        "PRAGMA foreign_key_list(" +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                            ")",
                    ),
                )
            }
        }
    }

    @Test
    fun revokedPermissionAtTheFinalFenceRollsBackCandidateAndLearningEvent() = runBlocking {
        val databaseName = "model-permission-fence-rollback.mastery-test.db"
        withStore(databaseName) { store ->
            val source =
                sourceFact(
                    id = "fence-rollback",
                    occurredAt = DAY_1,
                    responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                )
            assertEquals(MasteryInboundDisposition.APPLIED, store.acceptFixtureBindings(source))
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(source.command).status,
            )
            val candidate =
                candidate(
                    id = "candidate:fence-rollback",
                    source = source,
                    candidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED,
                )
            val scope =
                ModelSubmissionAttemptScope(
                    learnerId = LEARNER_ID,
                    permission =
                        modelRequestScope(
                            subject = SubjectKind.MATH,
                            sourceFactId = source.command.sourceFactId,
                            modelVersion = candidate.modelVersion,
                            requestVersion = "request-fence-rollback",
                            modeVersion = "direct-1",
                        ),
                )
            var authorizationChecks = 0
            val attempt =
                ModelSubmissionAttempt(
                    scope = scope,
                    modeVersion = scope.modeVersion,
                    proposalFingerprint =
                        scope.proposalFingerprint(candidate.canonicalFingerprint),
                    claim = ModelSubmissionCandidateClaim.FIRST,
                    commitAuthorization =
                        ModelSubmissionCommitAuthorization {
                            authorizationChecks += 1
                            if (authorizationChecks == 2) {
                                throw ModelSubmissionPermissionEpochRevokedException()
                            }
                        },
                    onPermissionEpochRollback = {},
                    onFinished = {},
                )

            val rejected =
                runCatching {
                    store.ingestModelObservationCandidate(candidate, attempt)
                }
            assertTrue(
                rejected.exceptionOrNull() is
                    ModelSubmissionPermissionEpochRevokedException,
            )
            store.recordRejectedModelSubmissionAttempt(
                attempt = attempt,
                terminalReason = ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED,
            )

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    0L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_observation_candidate"),
                )
                assertEquals(
                    0L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_MODEL_SUBMISSION_ATTEMPT_RECEIPT_TABLE +
                            " WHERE terminal_reason = " +
                            "'${ModelSubmissionTerminalReason.PERMISSION_EPOCH_REVOKED.name}'",
                    ),
                )
            }
        }
    }

    @Test
    fun v4PersistsLocalCalibrationAndHoldsWeakStableMasteryConflicts() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "admission-v4.mastery-test.db"
        context.deleteDatabase(databaseName)
        try {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                repeat(6) { index ->
                    val source =
                        sourceFact(
                            id = "stable-$index",
                            occurredAt = DAY_1 + index,
                            responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                        )
                    assertEquals(
                        MasteryInboundDisposition.APPLIED,
                        store.acceptFixtureBindings(source),
                    )
                    assertEquals(
                        LearningSourceFactIngestStatus.STORED,
                        store.ingestSourceFact(source.command).status,
                    )
                    assertEquals(
                        LearningObservationDisposition.ADMITTED,
                        store.ingestObservationCandidate(
                            candidate(
                                id = "stable-candidate-$index",
                                source = source,
                                candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                            ),
                        ).disposition,
                    )
                }
                val weakConflict =
                    sourceFact(
                        id = "weak-conflict",
                        occurredAt = DAY_2,
                        outcome = ObservedLearningOutcome.INCORRECT,
                        authority = MasteryEvidenceAuthority.MODEL_REVIEWED,
                        verificationKind = TrustedLearningVerification.MODEL_REVIEWED,
                        responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                        independentlyAnswered = false,
                    )
                store.acceptFixtureBindings(weakConflict)
                store.ingestSourceFact(weakConflict.command)

                val held =
                    store.ingestObservationCandidate(
                        candidate("weak-conflict-candidate", weakConflict),
                    )

                assertEquals(LearningObservationDisposition.INERT, held.disposition)
                assertEquals(
                    LearningObservationInertReason.WEAK_CONFLICT_REQUIRES_REVIEW,
                    held.inertReason,
                )
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                sqlite.rawQuery(
                    """
                    SELECT admission_policy_version, calibration_version
                    FROM mastery_learning_event
                    ORDER BY event_sequence
                    LIMIT 1
                    """.trimIndent(),
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(LEARNER_MASTERY_ADMISSION_POLICY_VERSION, cursor.getString(0))
                    assertEquals(LEARNER_MASTERY_CALIBRATION_VERSION, cursor.getString(1))
                }
                sqlite.rawQuery(
                    """
                    SELECT admission_policy_version, calibration_version
                    FROM mastery_admission_receipt
                    WHERE candidate_id = ?
                    """.trimIndent(),
                    arrayOf("stable-candidate-0"),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(LEARNER_MASTERY_ADMISSION_POLICY_VERSION, cursor.getString(0))
                    assertEquals(LEARNER_MASTERY_CALIBRATION_VERSION, cursor.getString(1))
                }
                sqlite.rawQuery(
                    """
                    SELECT reason,
                           admission_policy_version,
                           calibration_binding_status,
                           calibration_version,
                           calibration_profile_id,
                           calibration_snapshot_fingerprint
                    FROM mastery_evidence_review_case
                    WHERE candidate_id = ?
                    """.trimIndent(),
                    arrayOf("weak-conflict-candidate"),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(
                        LearningObservationInertReason.WEAK_CONFLICT_REQUIRES_REVIEW.name,
                        cursor.getString(0),
                    )
                    assertEquals(LEARNER_MASTERY_ADMISSION_POLICY_VERSION, cursor.getString(1))
                    assertEquals(MasteryCalibrationBindingStatus.BOUND.name, cursor.getString(2))
                    assertEquals(LEARNER_MASTERY_CALIBRATION_VERSION, cursor.getString(3))
                    assertTrue(cursor.getString(4).startsWith("calibration:math:"))
                    assertEquals(64, cursor.getString(5).length)
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun calibratedProjectionAndRecallRemainIdenticalAfterDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "calibration-reopen.mastery-test.db"
        context.deleteDatabase(databaseName)
        try {
            lateinit var beforeReopen: SubjectMasteryDigest
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                repeat(3) { index ->
                    val source =
                        sourceFact(
                            id = "calibration-reopen-$index",
                            occurredAt = DAY_1 + index,
                        )
                    store.acceptFixtureBindings(source)
                    store.ingestSourceFact(source.command)
                    assertEquals(
                        LearningObservationDisposition.ADMITTED,
                        store.ingestObservationCandidate(
                            candidate("calibration-reopen-candidate-$index", source),
                        ).disposition,
                    )
                }
                beforeReopen = store.mathDigest()
            }

            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { reopened ->
                assertEquals(beforeReopen, reopened.mathDigest())
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    27L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_calibration_snapshot",
                    ),
                )
                sqlite.rawQuery(
                    """
                    SELECT calibration_version, calibration_snapshot_fingerprint
                    FROM mastery_knowledge_projection
                    WHERE learner_id = ? AND subject = 'MATH'
                    """.trimIndent(),
                    arrayOf(LEARNER_ID),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(LEARNER_MASTERY_CALIBRATION_VERSION, cursor.getString(0))
                    assertEquals(64, cursor.getString(1).length)
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun reviewResolutionIsAppendOnlyAndProjectsOnlyAcceptedCases() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "review-resolution.mastery-test.db"
        context.deleteDatabase(databaseName)
        fun reviewCommand(
            reviewCaseId: String,
            decision: LearningEvidenceReviewDecision,
            key: String,
        ) = ResolveLearningEvidenceReviewCommand(
            reviewCaseId = reviewCaseId,
            decision = decision,
            authority = LearningEvidenceReviewAuthority.INDEPENDENT_MODEL_REVIEW,
            reviewerVersion = "independent-review-v1",
            reviewEvidenceFingerprint =
                CanonicalSha256("test-review-evidence-v1")
                    .field("case", reviewCaseId)
                    .field("decision", decision.name)
                    .finish(),
            idempotencyKey = key,
            decidedAtEpochMillis = NOW,
        )
        try {
            var acceptedCaseId = ""
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                // The default free-response fixture is deliberately capped at half mass. Twelve
                // independent successes reach the calibrated seven-day stable-conflict floor.
                repeat(12) { index ->
                    val source = sourceFact("resolution-stable-$index", DAY_1 + index)
                    store.acceptFixtureBindings(source)
                    store.ingestSourceFact(source.command)
                    assertEquals(
                        LearningObservationDisposition.ADMITTED,
                        store.ingestObservationCandidate(
                            candidate(
                                "resolution-stable-candidate-$index",
                                source,
                                candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                            ),
                        ).disposition,
                    )
                }
                val baseline = store.mathDigest()

                suspend fun createWeakConflict(id: String): String {
                    val source =
                        sourceFact(
                            id = id,
                            occurredAt = DAY_2,
                            outcome = ObservedLearningOutcome.INCORRECT,
                            authority = MasteryEvidenceAuthority.MODEL_REVIEWED,
                            verificationKind = TrustedLearningVerification.MODEL_REVIEWED,
                            responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                            independentlyAnswered = false,
                        )
                    store.acceptFixtureBindings(source)
                    store.ingestSourceFact(source.command)
                    assertEquals(
                        LearningObservationDisposition.INERT,
                        store.ingestObservationCandidate(
                            candidate("$id-candidate", source),
                        ).disposition,
                    )
                    return store.readPendingEvidenceReviews(
                        LEARNER_ID,
                        SubjectKind.MATH,
                        16,
                    ).single { it.sourceFactId == id }.reviewCaseId
                }

                val rejectedCase = createWeakConflict("resolution-reject")
                val reject =
                    reviewCommand(
                        rejectedCase,
                        LearningEvidenceReviewDecision.REJECT,
                        "resolution:reject",
                    )
                assertEquals(
                    LearningEvidenceReviewWriteDisposition.APPLIED,
                    store.resolveEvidenceReview(LEARNER_ID, reject).disposition,
                )
                assertEquals(
                    LearningEvidenceReviewWriteDisposition.DUPLICATE,
                    store.resolveEvidenceReview(LEARNER_ID, reject).disposition,
                )
                assertEquals(
                    LearningEvidenceReviewWriteDisposition.CONFLICT,
                    store.resolveEvidenceReview(
                        LEARNER_ID,
                        reviewCommand(
                            rejectedCase,
                            LearningEvidenceReviewDecision.ACCEPT,
                            "resolution:reject-conflict",
                        ),
                    ).disposition,
                )
                assertEquals(baseline, store.mathDigest())

                acceptedCaseId = createWeakConflict("resolution-accept")
                val accept =
                    reviewCommand(
                        acceptedCaseId,
                        LearningEvidenceReviewDecision.ACCEPT,
                        "resolution:accept",
                    )
                assertEquals(
                    LearningEvidenceReviewWriteDisposition.APPLIED,
                    store.resolveEvidenceReview(LEARNER_ID, accept).disposition,
                )
                assertNotEquals(baseline, store.mathDigest())
                assertTrue(
                    store.readPendingEvidenceReviews(
                        LEARNER_ID,
                        SubjectKind.MATH,
                        16,
                    ).none { it.reviewCaseId == rejectedCase || it.reviewCaseId == acceptedCaseId },
                )
            }

            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { reopened ->
                assertEquals(
                    LearningEvidenceReviewWriteDisposition.DUPLICATE,
                    reopened.resolveEvidenceReview(
                        LEARNER_ID,
                        reviewCommand(
                            acceptedCaseId,
                            LearningEvidenceReviewDecision.ACCEPT,
                            "resolution:accept",
                        ),
                    ).disposition,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun delayedEvidenceReviewUsesItsBoundHistoricalCalibrationAfterCurrentVersionAdvances() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "historical-review-resolution.mastery-test.db"
            context.deleteDatabase(databaseName)
            val historical =
                LocalMasteryCalibrationRegistry.allSnapshots().single {
                    it.subject == SubjectKind.MATH.name &&
                        it.calibrationVersion ==
                        LEARNER_MASTERY_PREVIOUS_CALIBRATION_VERSION
                }
            val current = LocalMasteryCalibrationRegistry.current(SubjectKind.MATH.name)
            val source =
                sourceFact(
                    id = "historical-review",
                    occurredAt = DAY_2,
                    outcome = ObservedLearningOutcome.INCORRECT,
                    authority = MasteryEvidenceAuthority.MODEL_REVIEWED,
                    verificationKind = TrustedLearningVerification.MODEL_REVIEWED,
                    responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                    independentlyAnswered = false,
                )
            val candidate = candidate("historical-review-candidate", source)
            var currentCaseId = ""
            lateinit var baseline: SubjectMasteryDigest
            lateinit var baselineProjection: MasteryKnowledgeProjectionEntity
            lateinit var committedProjection: MasteryKnowledgeProjectionEntity
            suspend fun readReviewProjection(): MasteryKnowledgeProjectionEntity {
                val database =
                    LearnerMasteryStoreFactory.openDatabaseForTest(
                        context = context,
                        databaseName = databaseName,
                    )
                return try {
                    database.displayDao().readExactKnowledgeProjections(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH.name,
                            stableNodeFingerprints =
                                listOf(
                                    MasteryProjectionIdentity.fingerprint(
                                        subject = SubjectKind.MATH.name,
                                        knowledgeNodeId = source.node.knowledgeNodeId,
                                        taxonomyVersion = source.node.taxonomyVersion,
                                    ),
                                ),
                        ).single()
                } finally {
                    database.close()
                }
            }
            try {
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW },
                ).use { store ->
                    repeat(12) { index ->
                        val stable = sourceFact("historical-review-stable-$index", DAY_1 + index)
                        store.acceptFixtureBindings(stable)
                        store.ingestSourceFact(stable.command)
                        assertEquals(
                            LearningObservationDisposition.ADMITTED,
                            store.ingestObservationCandidate(
                                candidate(
                                    "historical-review-stable-candidate-$index",
                                    stable,
                                    candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                                ),
                            ).disposition,
                        )
                    }
                    baseline = store.mathDigest()
                    store.acceptFixtureBindings(source)
                    store.ingestSourceFact(source.command)
                    assertEquals(
                        LearningObservationDisposition.INERT,
                        store.ingestObservationCandidate(candidate).disposition,
                    )
                    currentCaseId =
                        store.readPendingEvidenceReviews(
                            LEARNER_ID,
                            SubjectKind.MATH,
                            8,
                        ).single { it.sourceFactId == source.command.sourceFactId }.reviewCaseId
                }
                baselineProjection = readReviewProjection()

                var sourceProofFingerprint = ""
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    sqlite.rawQuery(
                        """
                        SELECT source_proof_fingerprint
                        FROM mastery_evidence_review_case
                        WHERE review_case_id = ?
                        """.trimIndent(),
                        arrayOf(currentCaseId),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        sourceProofFingerprint = cursor.getString(0)
                    }
                    val historicalReviewFingerprint =
                        CanonicalSha256("learner-mastery-evidence-review-case-v2")
                            .field("candidateFingerprint", candidate.canonicalFingerprint)
                            .field("candidateOrigin", candidate.candidateOrigin.name)
                            .field("sourceProofFingerprint", sourceProofFingerprint)
                            .field(
                                "reason",
                                LearningObservationInertReason
                                    .WEAK_CONFLICT_REQUIRES_REVIEW
                                    .name,
                            )
                            .field(
                                "admissionPolicyVersion",
                                LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
                            )
                            .field(
                                "calibrationBindingStatus",
                                MasteryCalibrationBindingStatus.BOUND.name,
                            )
                            .field("calibrationVersion", historical.calibrationVersion)
                            .field("calibrationProfileId", historical.profileId)
                            .field(
                                "calibrationSnapshotFingerprint",
                                historical.snapshotFingerprint,
                            )
                            .field("createdAtEpochMillis", NOW)
                            .finish()
                    val historicalCaseId = "mrc:${historicalReviewFingerprint.take(48)}"
                    val persistedCandidate =
                        MasteryObservationCandidateEntity(
                            candidateId = candidate.candidateId,
                            learnerId = candidate.learnerId,
                            subject = candidate.subject.name,
                            sourceFactId = candidate.sourceFactId,
                            confidence = candidate.confidence.name,
                            modelVersion = candidate.modelVersion,
                            requestedPolicyVersion = candidate.requestedPolicyVersion,
                            proposedAtEpochMillis = candidate.proposedAtEpochMillis,
                            receivedAtEpochMillis = NOW,
                            idempotencyKey = candidate.idempotencyKey,
                            canonicalFingerprint = candidate.canonicalFingerprint,
                            candidateOrigin = candidate.candidateOrigin.name,
                        )
                    val historicalReceiptFingerprint =
                        LearnerMasteryFingerprint.receipt(
                            candidate = persistedCandidate,
                            disposition = LearningObservationDisposition.INERT.name,
                            inertReason =
                                LearningObservationInertReason
                                    .WEAK_CONFLICT_REQUIRES_REVIEW
                                    .name,
                            sourceProofFingerprint = sourceProofFingerprint,
                            eventId = null,
                            decidedAtEpochMillis = NOW,
                            calibrationVersion = historical.calibrationVersion,
                        )
                    sqlite.execSQL(
                        "DROP TRIGGER IF EXISTS " +
                            "immutable_mastery_evidence_review_case_update",
                    )
                    sqlite.execSQL(
                        "DROP TRIGGER IF EXISTS " +
                            "immutable_mastery_admission_receipt_update",
                    )
                    sqlite.execSQL(
                        """
                        UPDATE mastery_evidence_review_case
                        SET review_case_id = ?,
                            calibration_version = ?,
                            calibration_profile_id = ?,
                            calibration_snapshot_fingerprint = ?,
                            review_case_fingerprint = ?
                        WHERE review_case_id = ?
                        """.trimIndent(),
                        arrayOf(
                            historicalCaseId,
                            historical.calibrationVersion,
                            historical.profileId,
                            historical.snapshotFingerprint,
                            historicalReviewFingerprint,
                            currentCaseId,
                        ),
                    )
                    sqlite.execSQL(
                        """
                        UPDATE mastery_admission_receipt
                        SET calibration_version = ?,
                            receipt_fingerprint = ?
                        WHERE candidate_id = ?
                        """.trimIndent(),
                        arrayOf(
                            historical.calibrationVersion,
                            historicalReceiptFingerprint,
                            candidate.candidateId,
                        ),
                    )
                    currentCaseId = historicalCaseId
                }

                val command =
                    ResolveLearningEvidenceReviewCommand(
                        reviewCaseId = currentCaseId,
                        decision = LearningEvidenceReviewDecision.ACCEPT,
                        authority =
                            LearningEvidenceReviewAuthority.INDEPENDENT_MODEL_REVIEW,
                        reviewerVersion = "historical-review-v1",
                        reviewEvidenceFingerprint =
                            CanonicalSha256("historical-review-evidence-v1")
                                .field("case", currentCaseId)
                                .finish(),
                        idempotencyKey = "historical-review:accept",
                        decidedAtEpochMillis = NOW + 1L,
                    )
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 1L },
                ).use { store ->
                    assertTrue(
                        store.readPendingEvidenceReviews(
                            LEARNER_ID,
                            SubjectKind.MATH,
                            8,
                        ).any { it.reviewCaseId == currentCaseId },
                    )
                    assertEquals(
                        LearningEvidenceReviewWriteDisposition.APPLIED,
                        store.resolveEvidenceReview(LEARNER_ID, command).disposition,
                    )
                    assertNotEquals(baseline, store.mathDigest())
                }

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    sqlite.rawQuery(
                        """
                        SELECT calibration_snapshot_fingerprint
                        FROM mastery_evidence_review_resolution
                        WHERE review_case_id = ?
                        """.trimIndent(),
                        arrayOf(currentCaseId),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(historical.snapshotFingerprint, cursor.getString(0))
                    }
                    sqlite.rawQuery(
                        """
                        SELECT calibration_version,
                               calibration_profile_id,
                               calibration_snapshot_fingerprint
                        FROM mastery_learning_event
                        WHERE source_fact_id = ?
                        """.trimIndent(),
                        arrayOf(source.command.sourceFactId),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(historical.calibrationVersion, cursor.getString(0))
                        assertEquals(historical.profileId, cursor.getString(1))
                        assertEquals(historical.snapshotFingerprint, cursor.getString(2))
                    }
                    sqlite.rawQuery(
                        """
                        SELECT attribution.evidence_mass_micros
                        FROM mastery_learning_event AS event
                        INNER JOIN mastery_learning_event_attribution AS attribution
                          ON attribution.event_id = event.event_id
                        WHERE event.source_fact_id = ?
                        """.trimIndent(),
                        arrayOf(source.command.sourceFactId),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(historical.modelReviewedMassMicros, cursor.getLong(0))
                        assertNotEquals(current.modelReviewedMassMicros, cursor.getLong(0))
                    }
                    listOf(
                        "mastery_presentation_node_budget",
                        "mastery_problem_family_node_budget",
                    ).forEach { budgetTable ->
                        sqlite.rawQuery(
                            """
                            SELECT budget.consumed_mass_micros
                            FROM $budgetTable AS budget
                            INNER JOIN mastery_learning_event AS event
                              ON event.event_id = budget.last_event_id
                            WHERE event.source_fact_id = ?
                            """.trimIndent(),
                            arrayOf(source.command.sourceFactId),
                        ).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertEquals(
                                historical.modelReviewedMassMicros,
                                cursor.getLong(0),
                            )
                            assertNotEquals(current.modelReviewedMassMicros, cursor.getLong(0))
                        }
                    }
                    sqlite.rawQuery(
                        """
                        SELECT calibration_version,
                               calibration_profile_id,
                               calibration_snapshot_fingerprint
                        FROM mastery_knowledge_projection
                        WHERE learner_id = ?
                          AND subject = 'MATH'
                          AND knowledge_node_id = 'math.function.quadratic'
                        """.trimIndent(),
                        arrayOf(LEARNER_ID),
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(historical.calibrationVersion, cursor.getString(0))
                        assertEquals(historical.profileId, cursor.getString(1))
                        assertEquals(historical.snapshotFingerprint, cursor.getString(2))
                    }
                }
                committedProjection = readReviewProjection()
                val counterfactualEvent =
                    MasteryLearningEventEntity(
                        eventId = "historical-review-counterfactual",
                        candidateId = candidate.candidateId,
                        sourceFactId = source.command.sourceFactId,
                        sourceProofFingerprint = "a".repeat(64),
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH.name,
                        direction = MasteryEventDirection.NEGATIVE.name,
                        eventSequence = baselineProjection.lastEventSequence + 1L,
                        occurredAtEpochMillis = source.command.occurredAtEpochMillis,
                        admittedAtEpochMillis = NOW + 1L,
                        projectionPolicyVersion =
                            LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
                        admissionPolicyVersion =
                            LEARNER_MASTERY_ADMISSION_POLICY_VERSION,
                        calibrationVersion = historical.calibrationVersion,
                        canonicalFingerprint = "b".repeat(64),
                        calibrationSnapshotFingerprint =
                            historical.snapshotFingerprint,
                        calibrationProfileId = historical.profileId,
                    )
                val historicalAttribution =
                    MasteryLearningEventAttributionEntity(
                        eventId = counterfactualEvent.eventId,
                        ordinal = 0,
                        subject = SubjectKind.MATH.name,
                        knowledgeNodeId = source.node.knowledgeNodeId,
                        taxonomyVersion = source.node.taxonomyVersion,
                        knowledgePackVersion = source.node.knowledgePackVersion,
                        knowledgeNodeRefFingerprint = source.node.canonicalFingerprint,
                        evidenceMassMicros = historical.modelReviewedMassMicros,
                    )
                val expectedHistorical =
                    LocalMasteryPolicy.nextProjection(
                        learnerId = LEARNER_ID,
                        event = counterfactualEvent,
                        attribution = historicalAttribution,
                        current = baselineProjection,
                    )
                val expectedCurrent =
                    LocalMasteryPolicy.nextProjection(
                        learnerId = LEARNER_ID,
                        event =
                            counterfactualEvent.copy(
                                calibrationVersion = current.calibrationVersion,
                                calibrationProfileId = current.profileId,
                                calibrationSnapshotFingerprint =
                                    current.snapshotFingerprint,
                            ),
                        attribution =
                            historicalAttribution.copy(
                                evidenceMassMicros = current.modelReviewedMassMicros,
                            ),
                        current = baselineProjection,
                    )
                assertEquals(
                    expectedHistorical.masteryScoreMicros,
                    committedProjection.masteryScoreMicros,
                )
                assertEquals(
                    expectedHistorical.historicalLogOddsMicros,
                    committedProjection.historicalLogOddsMicros,
                )
                assertEquals(
                    expectedHistorical.memoryStabilityMillis,
                    committedProjection.memoryStabilityMillis,
                )
                assertEquals(
                    expectedHistorical.recallDueAtEpochMillis,
                    committedProjection.recallDueAtEpochMillis,
                )
                assertNotEquals(
                    expectedCurrent.masteryScoreMicros,
                    committedProjection.masteryScoreMicros,
                )
                assertNotEquals(
                    expectedCurrent.memoryStabilityMillis,
                    committedProjection.memoryStabilityMillis,
                )
                assertNotEquals(
                    expectedCurrent.recallDueAtEpochMillis,
                    committedProjection.recallDueAtEpochMillis,
                )

                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    sqlite.execSQL("PRAGMA foreign_keys = OFF")
                    sqlite.execSQL(
                        "DROP TRIGGER IF EXISTS " +
                            "immutable_mastery_calibration_snapshot_delete",
                    )
                    sqlite.execSQL(
                        """
                        DELETE FROM mastery_calibration_snapshot
                        WHERE calibration_version = ?
                        """.trimIndent(),
                        arrayOf(LEARNER_MASTERY_CALIBRATION_VERSION),
                    )
                    assertEquals(
                        18L,
                        sqlite.longForQuery(
                            "SELECT COUNT(*) FROM mastery_calibration_snapshot",
                        ),
                    )
                }
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW + 1L },
                ).use { reopened ->
                    assertEquals(
                        LearningEvidenceReviewWriteDisposition.DUPLICATE,
                        reopened.resolveEvidenceReview(LEARNER_ID, command).disposition,
                    )
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    assertEquals(
                        27L,
                        sqlite.longForQuery(
                            "SELECT COUNT(*) FROM mastery_calibration_snapshot",
                        ),
                    )
                    assertEquals(
                        1L,
                        sqlite.longForQuery(
                            """
                            SELECT COUNT(*)
                            FROM mastery_learning_event
                            WHERE source_fact_id = '${source.command.sourceFactId}'
                              AND calibration_version =
                                  '${historical.calibrationVersion}'
                              AND calibration_profile_id =
                                  '${historical.profileId}'
                              AND calibration_snapshot_fingerprint =
                                  '${historical.snapshotFingerprint}'
                            """.trimIndent(),
                        ),
                    )
                }
                assertEquals(committedProjection, readReviewProjection())
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun invalidReviewCalibrationBindingsReturnConflictWithoutDerivedWrites() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val current = LocalMasteryCalibrationRegistry.current(SubjectKind.MATH.name)
        val invalidBindings =
            listOf(
                Triple(
                    "learner-mastery-calibration-v999",
                    current.profileId,
                    current.snapshotFingerprint,
                ),
                Triple(
                    current.calibrationVersion,
                    "calibration:math:unknown-profile",
                    current.snapshotFingerprint,
                ),
                Triple(
                    current.calibrationVersion,
                    current.profileId,
                    "f".repeat(64),
                ),
            )
        invalidBindings.forEachIndexed { scenario, binding ->
            val databaseName = "invalid-review-calibration-$scenario.mastery-test.db"
            context.deleteDatabase(databaseName)
            val source =
                sourceFact(
                    id = "invalid-review-$scenario",
                    occurredAt = DAY_2,
                    outcome = ObservedLearningOutcome.INCORRECT,
                    authority = MasteryEvidenceAuthority.MODEL_REVIEWED,
                    verificationKind = TrustedLearningVerification.MODEL_REVIEWED,
                    responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                    independentlyAnswered = false,
                )
            try {
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW },
                ).use { store ->
                    repeat(12) { index ->
                        val stable =
                            sourceFact(
                                id = "invalid-review-stable-$scenario-$index",
                                occurredAt = DAY_1 + index,
                            )
                        store.acceptFixtureBindings(stable)
                        store.ingestSourceFact(stable.command)
                        assertEquals(
                            LearningObservationDisposition.ADMITTED,
                            store.ingestObservationCandidate(
                                candidate(
                                    id = "invalid-review-stable-candidate-$scenario-$index",
                                    source = stable,
                                    candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                                ),
                            ).disposition,
                        )
                    }
                    store.acceptFixtureBindings(source)
                    store.ingestSourceFact(source.command)
                    assertEquals(
                        LearningObservationDisposition.INERT,
                        store.ingestObservationCandidate(
                            candidate("invalid-review-candidate-$scenario", source),
                        ).disposition,
                    )
                    val reviewCase =
                        store.readPendingEvidenceReviews(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH,
                            limit = 8,
                        ).single { it.sourceFactId == source.command.sourceFactId }
                    fun derivedCounts(): List<Long> =
                        SQLiteDatabase.openDatabase(
                            context.getDatabasePath(databaseName).absolutePath,
                            null,
                            SQLiteDatabase.OPEN_READONLY,
                        ).use { sqlite ->
                            listOf(
                                "mastery_evidence_review_resolution",
                                "mastery_learning_event",
                                "mastery_knowledge_projection",
                                "mastery_presentation_node_budget",
                                "mastery_problem_family_node_budget",
                            ).map { table -> sqlite.longForQuery("SELECT COUNT(*) FROM $table") }
                        }
                    val before = derivedCounts()
                    SQLiteDatabase.openDatabase(
                        context.getDatabasePath(databaseName).absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    ).use { sqlite ->
                        sqlite.execSQL("PRAGMA foreign_keys = OFF")
                        sqlite.execSQL(
                            "DROP TRIGGER IF EXISTS " +
                                "immutable_mastery_evidence_review_case_update",
                        )
                        sqlite.execSQL(
                            "DROP TRIGGER IF EXISTS " +
                                "validate_mastery_evidence_review_case_calibration_update",
                        )
                        sqlite.execSQL(
                            """
                            UPDATE mastery_evidence_review_case
                            SET calibration_version = ?,
                                calibration_profile_id = ?,
                                calibration_snapshot_fingerprint = ?
                            WHERE review_case_id = ?
                            """.trimIndent(),
                            arrayOf(
                                binding.first,
                                binding.second,
                                binding.third,
                                reviewCase.reviewCaseId,
                            ),
                        )
                    }
                    val result =
                        store.resolveEvidenceReview(
                            learnerId = LEARNER_ID,
                            command =
                                ResolveLearningEvidenceReviewCommand(
                                    reviewCaseId = reviewCase.reviewCaseId,
                                    decision = LearningEvidenceReviewDecision.ACCEPT,
                                    authority =
                                        LearningEvidenceReviewAuthority
                                            .INDEPENDENT_MODEL_REVIEW,
                                    reviewerVersion = "invalid-review-v1",
                                    reviewEvidenceFingerprint =
                                        (scenario + 1).toString().repeat(64),
                                    idempotencyKey = "invalid-review-resolution-$scenario",
                                    decidedAtEpochMillis = NOW + 1L,
                                ),
                        )
                    assertEquals(
                        LearningEvidenceReviewWriteDisposition.CONFLICT,
                        result.disposition,
                    )
                    assertEquals(before, derivedCounts())
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun evidenceSupersessionKeepsAuditHistoryButProjectsOnlyActiveCorrections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "evidence-supersession.mastery-test.db"
        context.deleteDatabase(databaseName)
        val positiveOriginal =
            sourceFact(
                id = "correction-positive-original",
                occurredAt = DAY_1,
                outcome = ObservedLearningOutcome.CORRECT,
                knowledgeNodeId = "math.correction.shared",
                responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
            )
        val negativeOriginal =
            sourceFact(
                id = "correction-negative-original",
                occurredAt = DAY_1 + 1L,
                outcome = ObservedLearningOutcome.INCORRECT,
                knowledgeNodeId = "math.correction.shared",
                responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
            )
        val positiveToNegative =
            correctionFixture(
                original = positiveOriginal,
                replacementId = "correction-negative-replacement",
                replacementOutcome = ObservedLearningOutcome.INCORRECT,
                idempotencyKey = "correction:positive-to-negative",
            )
        val negativeToPositive =
            correctionFixture(
                original = negativeOriginal,
                replacementId = "correction-positive-replacement",
                replacementOutcome = ObservedLearningOutcome.CORRECT,
                idempotencyKey = "correction:negative-to-positive",
            )
        try {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                listOf(positiveOriginal, negativeOriginal).forEach { original ->
                    store.acceptFixtureBindings(original)
                    assertEquals(
                        LearningSourceFactIngestStatus.STORED,
                        store.ingestSourceFact(original.command).status,
                    )
                    assertEquals(
                        LearningObservationDisposition.ADMITTED,
                        store.ingestObservationCandidate(
                            candidate(
                                id = "candidate:${original.command.sourceFactId}",
                                source = original,
                                candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                            ),
                        ).disposition,
                    )
                }

                val appliedPositiveCorrection = store.applyCorrection(positiveToNegative)
                assertEquals(
                    LearningEvidenceCorrectionDisposition.APPLIED,
                    appliedPositiveCorrection.disposition,
                )
                val duplicatePositiveCorrection = store.applyCorrection(positiveToNegative)
                assertEquals(
                    LearningEvidenceCorrectionDisposition.DUPLICATE,
                    duplicatePositiveCorrection.disposition,
                )
                assertEquals(
                    appliedPositiveCorrection.replacementSourceFactCanonicalFingerprint,
                    duplicatePositiveCorrection.replacementSourceFactCanonicalFingerprint,
                )
                assertEquals(
                    LearningEvidenceCorrectionDisposition.APPLIED,
                    store.applyCorrection(negativeToPositive).disposition,
                )
                assertEquals(
                    LearningEvidenceCorrectionDisposition.REJECTED,
                    store.applyCorrection(
                        correctionFixture(
                            original = positiveOriginal,
                            replacementId = "correction-second-replacement",
                            replacementOutcome = ObservedLearningOutcome.CORRECT,
                            idempotencyKey = "correction:already-superseded",
                        ),
                    ).disposition,
                )
                assertTrue(
                    runCatching {
                        store.correctLearningEvidence(
                            learnerId = "another-learner",
                            command = positiveToNegative.command,
                            replacementSourceFact = positiveToNegative.sourceFact,
                            replacementCandidate = positiveToNegative.candidate,
                        )
                    }.isFailure,
                )
                assertEquals(
                    LearningEvidenceCorrectionDisposition.REJECTED,
                    store.applyCorrection(
                        positiveToNegative.copy(
                            command =
                                correctionCommand(
                                    originalFingerprint = "f".repeat(64),
                                    replacement = positiveToNegative.observation,
                                    idempotencyKey = "correction:unknown-original",
                                ),
                        ),
                    ).disposition,
                )

                val timeline =
                    store.querySubjectTimeline(
                        SubjectMasteryTimelineQuery(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH,
                            sinceEpochMillis = 0L,
                            dayLimit = 30,
                        ),
                    ).single()
                assertEquals(2, timeline.observationCount)
                assertEquals(SubjectMasteryTimelineSignal.MIXED, timeline.signal)
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    4L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                assertEquals(
                    2L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_learning_evidence_supersession",
                    ),
                )
                listOf(
                    "mastery_presentation_node_budget",
                    "mastery_problem_family_node_budget",
                ).forEach { table ->
                    assertEquals(
                        2L,
                        sqlite.longForQuery("SELECT COUNT(*) FROM $table"),
                    )
                    assertEquals(
                        2L,
                        sqlite.longForQuery(
                            "SELECT COUNT(DISTINCT direction) FROM $table",
                        ),
                    )
                }
                assertEquals(
                    2L,
                    sqlite.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM mastery_learning_event e
                        WHERE NOT EXISTS (
                            SELECT 1
                            FROM mastery_learning_evidence_supersession s
                            WHERE s.original_event_id = e.event_id
                        )
                        """.trimIndent(),
                    ),
                )
                sqlite.rawQuery(
                    """
                    SELECT source_fact_id, direction
                    FROM mastery_learning_event
                    WHERE source_fact_id IN (?, ?)
                    ORDER BY source_fact_id
                    """.trimIndent(),
                    arrayOf(
                        positiveToNegative.sourceFact.sourceFactId,
                        negativeToPositive.sourceFact.sourceFactId,
                    ),
                ).use { cursor ->
                    val directions = linkedMapOf<String, String>()
                    while (cursor.moveToNext()) {
                        directions[cursor.getString(0)] = cursor.getString(1)
                    }
                    assertEquals(
                        MasteryEventDirection.NEGATIVE.name,
                        directions[positiveToNegative.sourceFact.sourceFactId],
                    )
                    assertEquals(
                        MasteryEventDirection.POSITIVE.name,
                        directions[negativeToPositive.sourceFact.sourceFactId],
                    )
                }
            }

            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { reopened ->
                assertEquals(
                    LearningEvidenceCorrectionDisposition.DUPLICATE,
                    reopened.applyCorrection(positiveToNegative).disposition,
                )
                assertEquals(
                    2,
                    reopened.querySubjectTimeline(
                        SubjectMasteryTimelineQuery(
                            learnerId = LEARNER_ID,
                            subject = SubjectKind.MATH,
                            sinceEpochMillis = 0L,
                            dayLimit = 30,
                        ),
                    ).single().observationCount,
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun replacementHeadCanBeCorrectedAgainUsingReturnedFingerprint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "evidence-correction-chain.mastery-test.db"
        withStore(databaseName) { store ->
            val original =
                sourceFact(
                    id = "correction-chain-original",
                    occurredAt = DAY_1,
                    outcome = ObservedLearningOutcome.CORRECT,
                    knowledgeNodeId = "math.correction.chain",
                    responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                )
            store.acceptFixtureBindings(original)
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(original.command).status,
            )
            assertEquals(
                LearningObservationDisposition.ADMITTED,
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate:${original.command.sourceFactId}",
                        source = original,
                        candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                    ),
                ).disposition,
            )

            val firstReplacement =
                correctionFixture(
                    original = original,
                    replacementId = "correction-chain-negative",
                    replacementOutcome = ObservedLearningOutcome.INCORRECT,
                    idempotencyKey = "correction:chain:first",
                )
            val firstResult = store.applyCorrection(firstReplacement)
            assertEquals(LearningEvidenceCorrectionDisposition.APPLIED, firstResult.disposition)

            val replacementHead =
                FixtureSource(
                    command = firstReplacement.sourceFact,
                    node = original.node,
                    binding = original.binding,
                    bindings = original.bindings,
                    revision = original.revision,
                )
            val secondReplacement =
                correctionFixture(
                    original = replacementHead,
                    originalFingerprint =
                        requireNotNull(
                            firstResult.replacementSourceFactCanonicalFingerprint,
                        ),
                    replacementId = "correction-chain-positive",
                    replacementOutcome = ObservedLearningOutcome.CORRECT,
                    idempotencyKey = "correction:chain:second",
                )
            assertEquals(
                LearningEvidenceCorrectionDisposition.APPLIED,
                store.applyCorrection(secondReplacement).disposition,
            )

            val timeline =
                store.querySubjectTimeline(
                    SubjectMasteryTimelineQuery(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        sinceEpochMillis = 0L,
                        dayLimit = 30,
                    ),
                ).single()
            assertEquals(1, timeline.observationCount)
            assertEquals(SubjectMasteryTimelineSignal.PROGRESS, timeline.signal)

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    3L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_learning_event"),
                )
                assertEquals(
                    2L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_learning_evidence_supersession",
                    ),
                )
            }
        }
    }

    @Test
    fun lateEvidenceReplaysByBehaviorTimeAndStableEventId() = runBlocking {
        val chronological =
            captureOrderResult(
                databaseName = "chronological.mastery-test.db",
                order = listOf(EventFixture.OLDER_NEGATIVE, EventFixture.NEWER_POSITIVE),
            )
        val lateArrival =
            captureOrderResult(
                databaseName = "late-arrival.mastery-test.db",
                order = listOf(EventFixture.NEWER_POSITIVE, EventFixture.OLDER_NEGATIVE),
            )

        assertEquals(chronological.digest, lateArrival.digest)
        assertEquals(chronological.timeline, lateArrival.timeline)
        assertEquals(DAY_2, lateArrival.digest.focus.single().lastEvidenceAtEpochMillis)
    }

    @Test
    fun packUpdatesPreserveProjectionButTaxonomyChangesStaySeparate() = runBlocking {
        withStore("version-identity.mastery-test.db") { store ->
            val first =
                sourceFact(
                    id = "fact-pack-1",
                    occurredAt = DAY_1,
                    knowledgePackVersion = "pack-v1",
                )
            val second =
                sourceFact(
                    id = "fact-pack-2",
                    occurredAt = DAY_2,
                    knowledgePackVersion = "pack-v2",
                )
            store.acceptFixtureBindings(first)
            store.ingestSourceFact(first.command)
            store.ingestObservationCandidate(candidate("candidate-pack-1", first))
            store.acceptFixtureBindings(second)
            store.ingestSourceFact(second.command)
            store.ingestObservationCandidate(candidate("candidate-pack-2", second))

            val afterPackUpdate = store.mathDigest()
            assertEquals(1, afterPackUpdate.focus.size)
            assertEquals(
                "pack-v2",
                afterPackUpdate.focus.single().knowledgeNode.knowledgePackVersion,
            )

            val newTaxonomy =
                sourceFact(
                    id = "fact-taxonomy-2",
                    occurredAt = DAY_3,
                    taxonomyVersion = "taxonomy-v2",
                    knowledgePackVersion = "pack-v3",
                )
            store.acceptFixtureBindings(newTaxonomy)
            store.ingestSourceFact(newTaxonomy.command)
            store.ingestObservationCandidate(candidate("candidate-taxonomy-2", newTaxonomy))

            val afterTaxonomyChange = store.mathDigest()
            assertEquals(2, afterTaxonomyChange.focus.size)
            assertEquals(
                setOf("taxonomy-v1", "taxonomy-v2"),
                afterTaxonomyChange.focus.map { it.knowledgeNode.taxonomyVersion }.toSet(),
            )
        }
    }

    @Test
    fun projectionRebuildUpgradesLegacyCompletionAndScansSharedSubjectHistoryOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "chunked-rebuild.mastery-test.db"
        context.deleteDatabase(databaseName)
        lateinit var baseline: SubjectMasteryDigest
        try {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                repeat(70) { index ->
                    val source =
                        sourceFact(
                            id = "rebuild-$index",
                            occurredAt = DAY_1 + (70 - index),
                            knowledgeNodeId = "math.shared-rebuild-node.$index",
                        )
                    assertEquals(MasteryInboundDisposition.APPLIED, store.acceptFixtureBindings(source))
                    assertEquals(
                        LearningSourceFactIngestStatus.STORED,
                        store.ingestSourceFact(source.command).status,
                    )
                    assertEquals(
                        LearningObservationDisposition.ADMITTED,
                        store.ingestObservationCandidate(
                            candidate("rebuild-candidate-$index", source),
                        ).disposition,
                    )
                }
                baseline = store.mathDigest()
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    """
                    INSERT OR REPLACE INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    INSERT OR REPLACE INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_completed_v2', ?)
                    """.trimIndent(),
                    arrayOf(LEARNER_MASTERY_PROJECTION_POLICY_VERSION),
                )
                sqlite.execSQL(
                    """
                    INSERT OR REPLACE INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_progress_v2:00000000000000000001', ?)
                    """.trimIndent(),
                    arrayOf(legacyProjectionRebuildCompleteProgress()),
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_projection_generation
                    SET target_projection_policy_version = 'previous-active-projection',
                        target_calibration_version = 'previous-active-calibration'
                    WHERE state = 'ACTIVE'
                    """.trimIndent(),
                )
            }

            val firstDatabase =
                Room.databaseBuilder(
                    context.applicationContext,
                    LearnerMasteryRoomDatabase::class.java,
                    databaseName,
                ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            try {
                val dao = firstDatabase.masteryDao()
                val reset = dao.rebuildDerivedStateChunk()
                val firstPage = dao.rebuildDerivedStateChunk()

                assertEquals(MasteryProjectionRebuildStage.RESET, reset.stage)
                assertEquals(MasteryProjectionRebuildStage.PROJECTIONS, firstPage.stage)
                assertEquals(70, firstPage.processedRowCount)
                assertTrue(!firstPage.completed)
            } finally {
                firstDatabase.close()
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                assertEquals(
                    70L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_knowledge_projection"),
                )
                assertEquals(
                    0L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_projection_shadow"),
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_projection_generation
                    SET lease_owner_id = 'crashed-owner',
                        lease_expires_at_epoch_millis = ?
                    WHERE state = 'BUILDING'
                    """.trimIndent(),
                    arrayOf(NOW + 1_000L),
                )
            }

            val resumedDatabase =
                Room.databaseBuilder(
                    context.applicationContext,
                    LearnerMasteryRoomDatabase::class.java,
                    databaseName,
                ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            RoomLearnerMasteryStore(resumedDatabase, nowEpochMillis = { NOW }).use { store ->
                val dao = resumedDatabase.masteryDao()
                val busy =
                    dao.rebuildDerivedStateChunk(
                        ownerId = "takeover-owner",
                        nowEpochMillis = NOW,
                    )
                assertTrue(busy.leaseBusy)
                var result =
                    dao.rebuildDerivedStateChunk(
                        ownerId = "takeover-owner",
                        nowEpochMillis = NOW + 1_001L,
                    )
                assertTrue(result.stage != MasteryProjectionRebuildStage.RESET)
                var transactionCount = 3
                var immutableHistoryQueryCount = result.immutableHistoryQueryCount
                while (!result.completed) {
                    result = dao.rebuildDerivedStateChunk()
                    transactionCount += 1
                    assertTrue(
                        result.processedRowCount <=
                            LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
                    )
                    immutableHistoryQueryCount += result.immutableHistoryQueryCount
                }
                assertTrue(transactionCount > 2)
                assertEquals(
                    "All 70 knowledge points share one learner-and-subject history scan",
                    1,
                    immutableHistoryQueryCount,
                )
                val digest = store.mathDigest()
                assertEquals(baseline, digest)
                assertEquals(
                    70,
                    digest.stateCounts.needsReinforcement +
                        digest.stateCounts.familiarizing +
                        digest.stateCounts.steady,
                )
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM mastery_store_metadata
                        WHERE metadata_key =
                            'projection_rebuild_completed_v3:' ||
                            'event-sequence-subject-history-stream-v2'
                          AND metadata_value = '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM mastery_projection_generation
                        WHERE state = 'ACTIVE'
                          AND snapshot_fingerprint IS NOT NULL
                          AND target_projection_policy_version =
                              '$LEARNER_MASTERY_PROJECTION_POLICY_VERSION'
                        """.trimIndent(),
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM mastery_projection_generation
                        WHERE state = 'BUILDING'
                        """.trimIndent(),
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun projectionRebuildResumesAcrossMixedDirectionsAtAnExactKeysetBoundary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "projection-boundary-recovery.mastery-test.db"
        val eventCount =
            (LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE * 2 + 1) / 3
        val boundaryEventIndex = eventCount - 1
        val projectedNodeCount = LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE
        context.deleteDatabase(databaseName)
        lateinit var baseline: SubjectMasteryDigest
        lateinit var baselineDerivedState: Map<String, List<String>>
        try {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                repeat(eventCount) { index ->
                    val source =
                        sourceFact(
                            id = "page-boundary-$index",
                            occurredAt = DAY_1,
                            outcome =
                                if (
                                    index % 2 == 0 ||
                                    index == boundaryEventIndex
                                ) {
                                    ObservedLearningOutcome.CORRECT
                                } else {
                                    ObservedLearningOutcome.INCORRECT
                            },
                            knowledgeNodeId = "math.page-boundary-node.$index",
                            additionalKnowledgeNodeIds =
                                listOf("math.page-boundary-node.$index.extra"),
                        )
                    assertEquals(
                        MasteryInboundDisposition.APPLIED,
                        store.acceptFixtureBindings(source, bindings = source.bindings),
                    )
                    assertEquals(
                        LearningSourceFactIngestStatus.STORED,
                        store.ingestSourceFact(source.command).status,
                    )
                    val ingestion =
                        store.ingestObservationCandidate(
                            candidate(
                                id = "page-boundary-candidate-$index",
                                source = source,
                                proposedBindings = source.bindings,
                                additionalAttributionRole =
                                    if (
                                        index % 2 == 0 ||
                                        index == boundaryEventIndex
                                    ) {
                                        MasteryAttributionRole.PRIMARY
                                    } else {
                                        MasteryAttributionRole.SUPPORTING
                                    },
                            ),
                        )
                    assertEquals(
                        "index=$index inertReason=${ingestion.inertReason}",
                        LearningObservationDisposition.ADMITTED,
                        ingestion.disposition,
                    )
                }
                baseline = store.mathDigest()
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                baselineDerivedState = sqlite.snapshotMasteryDerivedState()
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    """
                    INSERT OR REPLACE INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_projection_generation
                    SET target_projection_policy_version = 'previous-active-projection',
                        target_calibration_version = 'previous-active-calibration'
                    WHERE state = 'ACTIVE'
                    """.trimIndent(),
                )
            }

            val firstDatabase =
                Room.databaseBuilder(
                    context.applicationContext,
                    LearnerMasteryRoomDatabase::class.java,
                    databaseName,
                ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            try {
                val dao = firstDatabase.masteryDao()
                assertEquals(
                    MasteryProjectionRebuildStage.RESET,
                    dao.rebuildDerivedStateChunk().stage,
                )
                val exactPage = dao.rebuildDerivedStateChunk()
                assertEquals(MasteryProjectionRebuildStage.PROJECTIONS, exactPage.stage)
                assertEquals(
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
                    exactPage.processedRowCount,
                )
                assertTrue(!exactPage.completed)

                val foregroundSnapshot =
                    dao.readSubjectDigestSnapshot(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH.name,
                        limit = projectedNodeCount,
                    )
                assertEquals(projectedNodeCount, foregroundSnapshot.focus.size)
                assertEquals(eventCount.toLong(), foregroundSnapshot.digest?.lastEventSequence)
            } finally {
                firstDatabase.close()
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    0L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_projection_shadow"),
                )
                assertEquals(
                    projectedNodeCount.toLong(),
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_knowledge_projection"),
                )
            }

            val resumedDatabase =
                Room.databaseBuilder(
                    context.applicationContext,
                    LearnerMasteryRoomDatabase::class.java,
                    databaseName,
                ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            try {
                val dao = resumedDatabase.masteryDao()
                val secondExactPage = dao.rebuildDerivedStateChunk()
                assertEquals(MasteryProjectionRebuildStage.PROJECTIONS, secondExactPage.stage)
                assertEquals(0, secondExactPage.processedRowCount)
                assertTrue(!secondExactPage.completed)
                val foregroundSnapshot =
                    dao.readSubjectDigestSnapshot(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH.name,
                        limit = projectedNodeCount,
                    )
                assertEquals(projectedNodeCount, foregroundSnapshot.focus.size)
            } finally {
                resumedDatabase.close()
            }

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    0L,
                    sqlite.longForQuery("SELECT COUNT(*) FROM mastery_projection_shadow"),
                )
            }

            val finalDatabase =
                Room.databaseBuilder(
                    context.applicationContext,
                    LearnerMasteryRoomDatabase::class.java,
                    databaseName,
                ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            RoomLearnerMasteryStore(finalDatabase, nowEpochMillis = { NOW }).use { store ->
                val dao = finalDatabase.masteryDao()
                var result = dao.rebuildDerivedStateChunk()
                assertEquals(MasteryProjectionRebuildStage.DIMENSIONS, result.stage)
                assertEquals(
                    LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE,
                    result.processedRowCount,
                )
                assertEquals(1, result.immutableHistoryQueryCount)
                assertTrue(
                    result.peakMaterializedRowCount <=
                        LEARNER_MASTERY_PROJECTION_REBUILD_PAGE_SIZE * 3,
                )
                while (!result.completed) {
                    result = dao.rebuildDerivedStateChunk()
                }
                assertEquals(baseline, store.mathDigest())
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertMasteryDerivedStateEquals(
                    expected = baselineDerivedState,
                    actual = sqlite.snapshotMasteryDerivedState(),
                )
                assertEquals(
                    1L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE +
                            " receipt INNER JOIN mastery_projection_generation generation " +
                            "ON generation.generation_id = receipt.generation_id " +
                            "WHERE generation.state = 'ACTIVE'",
                    ),
                )
                assertEquals(
                    projectedNodeCount.toLong(),
                    sqlite.longForQuery(
                        "SELECT receipt.input_row_count FROM " +
                            LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE +
                            " receipt INNER JOIN mastery_projection_generation generation " +
                            "ON generation.generation_id = receipt.generation_id " +
                            "WHERE generation.state = 'ACTIVE'",
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM " +
                            LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE +
                            " receipt INNER JOIN mastery_projection_generation generation " +
                            "ON generation.generation_id = receipt.generation_id " +
                            "WHERE generation.state = 'ACTIVE' AND (" +
                            "last_direction NOT IN ('POSITIVE', 'NEGATIVE') " +
                            "OR length(input_snapshot_fingerprint) != 64 " +
                            "OR length(output_fingerprint) != 64)",
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun projectionRebuildBlocksBeforeResetWhenHistoricalCalibrationIsUnknown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "blocked-calibration-rebuild.mastery-test.db"
        context.deleteDatabase(databaseName)
        lateinit var baseline: SubjectMasteryDigest
        try {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                val source = sourceFact(id = "blocked-rebuild", occurredAt = DAY_1)
                store.acceptFixtureBindings(source)
                store.ingestSourceFact(source.command)
                assertEquals(
                    LearningObservationDisposition.ADMITTED,
                    store.ingestObservationCandidate(
                        candidate(
                            id = "blocked-rebuild-candidate",
                            source = source,
                            candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                        ),
                    ).disposition,
                )
                baseline = store.mathDigest()
            }
            lateinit var derivedCounts: List<Long>
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                derivedCounts =
                    listOf(
                        "mastery_knowledge_projection",
                        "mastery_subject_digest",
                        "mastery_presentation_node_budget",
                        "mastery_problem_family_node_budget",
                    ).map { table -> sqlite.longForQuery("SELECT COUNT(*) FROM $table") }
                sqlite.execSQL("PRAGMA foreign_keys = OFF")
                sqlite.execSQL(
                    "DROP TRIGGER IF EXISTS immutable_mastery_learning_event_update",
                )
                sqlite.execSQL(
                    "DROP TRIGGER IF EXISTS validate_mastery_learning_event_calibration_update",
                )
                sqlite.execSQL(
                    "DROP TRIGGER IF EXISTS immutable_mastery_store_metadata_delete",
                )
                sqlite.execSQL(
                    """
                    UPDATE mastery_learning_event
                    SET calibration_snapshot_fingerprint = ?
                    """.trimIndent(),
                    arrayOf("e".repeat(64)),
                )
                sqlite.execSQL(
                    """
                    INSERT OR REPLACE INTO mastery_store_metadata(metadata_key, metadata_value)
                    VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    DELETE FROM mastery_store_metadata
                    WHERE metadata_key = 'projection_rebuild_completed_v2'
                       OR metadata_key GLOB 'projection_rebuild_progress_v2:*'
                       OR metadata_key =
                            'projection_rebuild_completed_v3:' ||
                            'event-sequence-subject-history-stream-v2'
                       OR metadata_key GLOB 'projection_rebuild_progress_v3:*'
                    """.trimIndent(),
                )
            }

            val database =
                Room.databaseBuilder(
                    context.applicationContext,
                    LearnerMasteryRoomDatabase::class.java,
                    databaseName,
                ).addMigrations(*LEARNER_MASTERY_MIGRATIONS.toTypedArray())
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            RoomLearnerMasteryStore(database, nowEpochMillis = { NOW }).use { store ->
                val blocked = database.masteryDao().rebuildDerivedStateChunk()
                assertEquals(MasteryProjectionRebuildStage.RESET, blocked.stage)
                assertEquals(
                    MasteryCalibrationBindingBlockReason.UNKNOWN_CALIBRATION_SNAPSHOT,
                    blocked.blockedReason,
                )
                assertTrue(!blocked.completed)
                assertEquals(baseline, store.mathDigest())

                val followUp =
                    sourceFact(
                        id = "blocked-rebuild-follow-up",
                        occurredAt = DAY_2,
                    )
                val followUpCandidate =
                    candidate(
                        id = "blocked-rebuild-follow-up-candidate",
                        source = followUp,
                        candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                    )
                store.acceptFixtureBindings(followUp)
                assertEquals(
                    LearningSourceFactIngestStatus.STORED,
                    store.ingestSourceFact(followUp.command).status,
                )
                val ingestResult = store.ingestObservationCandidate(followUpCandidate)
                assertEquals(LearningObservationDisposition.CONFLICT, ingestResult.disposition)
                assertEquals(
                    LearningObservationInertReason.CALIBRATION_BINDING_CONFLICT,
                    ingestResult.inertReason,
                )
            }
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                assertEquals(
                    derivedCounts,
                    listOf(
                        "mastery_knowledge_projection",
                        "mastery_subject_digest",
                        "mastery_presentation_node_budget",
                        "mastery_problem_family_node_budget",
                    ).map { table -> sqlite.longForQuery("SELECT COUNT(*) FROM $table") },
                )
                assertEquals(
                    0L,
                    sqlite.longForQuery(
                        "SELECT COUNT(*) FROM mastery_store_metadata " +
                            "WHERE metadata_key GLOB 'projection_rebuild_progress_v3:*'",
                    ),
                )
                assertEquals(
                    0L,
                    sqlite.longForQuery(
                        """
                        SELECT COUNT(*)
                        FROM mastery_observation_candidate
                        WHERE candidate_id = 'blocked-rebuild-follow-up-candidate'
                        """.trimIndent(),
                    ),
                )
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun incompleteLegacyGenerationWithoutSafeActiveSnapshotReturnsInsufficientHistory() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "unsafe-active-generation.mastery-test.db"
            context.deleteDatabase(databaseName)
            try {
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW },
                ).use { store ->
                    val source = sourceFact(id = "unsafe-active", occurredAt = DAY_1)
                    store.acceptFixtureBindings(source)
                    store.ingestSourceFact(source.command)
                    assertEquals(
                        LearningObservationDisposition.ADMITTED,
                        store.ingestObservationCandidate(
                            candidate("unsafe-active-candidate", source),
                        ).disposition,
                    )
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                ).use { sqlite ->
                    sqlite.execSQL("DELETE FROM mastery_projection_generation")
                    sqlite.execSQL(
                        """
                        INSERT INTO mastery_store_metadata(metadata_key, metadata_value)
                        VALUES('projection_rebuild_policy', 'REQUIRED_V2')
                        """.trimIndent(),
                    )
                    sqlite.execSQL(
                        """
                        INSERT INTO mastery_store_metadata(metadata_key, metadata_value)
                        VALUES('projection_rebuild_progress_v3:00000000000000000001', ?)
                        """.trimIndent(),
                        arrayOf(incompleteProjectionRebuildProgress()),
                    )
                }

                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW },
                ).use { store ->
                    val digest = store.mathDigest()
                    assertEquals(
                        LearnerMasteryHistoryAvailability.INSUFFICIENT_HISTORY,
                        digest.historyAvailability,
                    )
                    assertTrue(digest.focus.isEmpty())
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun legacyMigrationConflictRollsBackFactsEventsAndCheckpointTogether() = runBlocking {
        withStore("atomic-legacy-migration.mastery-test.db") { store ->
            val existing = sourceFact(id = "atomic-existing", occurredAt = DAY_1)
            store.acceptFixtureBindings(existing)
            store.ingestSourceFact(existing.command)
            store.ingestObservationCandidate(candidate("atomic-existing-candidate", existing))

            val fresh = sourceFact(id = "atomic-fresh", occurredAt = DAY_2)
            val conflicting = sourceFact(id = "atomic-existing", occurredAt = DAY_3)
            store.acceptFixtureBindings(fresh)
            val conflictResult =
                store.migrateLegacyFactBatch(
                    observations =
                        listOf(
                            LegacyMasteryObservationWrite(
                                sourceFact = fresh.command,
                                candidate = candidate("atomic-fresh-candidate", fresh),
                            ),
                            LegacyMasteryObservationWrite(
                                sourceFact = conflicting.command,
                                candidate = candidate("atomic-conflict-candidate", conflicting),
                            ),
                        ),
                    checkpoint =
                        migrationCheckpoint(
                            fingerprint = "1".repeat(64),
                            observationCount = 2,
                        ),
                )

            assertEquals(LegacyMasteryFactBatchWriteDisposition.CONFLICT, conflictResult)
            assertEquals(
                1,
                store.querySubjectTimeline(
                    SubjectMasteryTimelineQuery(LEARNER_ID, SubjectKind.MATH),
                ).sumOf(SubjectMasteryTimelineEntry::observationCount),
            )

            val retry =
                store.migrateLegacyFactBatch(
                    observations =
                        listOf(
                            LegacyMasteryObservationWrite(
                                sourceFact = fresh.command,
                                candidate = candidate("atomic-fresh-candidate", fresh),
                            ),
                        ),
                    checkpoint =
                        migrationCheckpoint(
                            fingerprint = "2".repeat(64),
                            observationCount = 1,
                        ),
                )
            assertEquals(LegacyMasteryFactBatchWriteDisposition.IMPORTED, retry)
            assertEquals(
                2,
                store.querySubjectTimeline(
                    SubjectMasteryTimelineQuery(LEARNER_ID, SubjectKind.MATH),
                ).sumOf(SubjectMasteryTimelineEntry::observationCount),
            )
        }
    }

    @Test
    fun positiveAndNegativeEvidenceUseIndependentPresentationAndFamilyBudgets() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val databaseName = "directional-evidence-budgets.mastery-test.db"
            context.deleteDatabase(databaseName)
            try {
                LearnerMasteryStoreFactory.openForTest(
                    context = context,
                    databaseName = databaseName,
                    nowEpochMillis = { NOW },
                ).use { store ->
                    val positive =
                        sourceFact(
                            id = "direction-positive",
                            occurredAt = DAY_1,
                            outcome = ObservedLearningOutcome.CORRECT,
                            budgetIdentitySeed = "shared-directional-budget",
                        )
                    val negative =
                        sourceFact(
                            id = "direction-negative",
                            occurredAt = DAY_2,
                            outcome = ObservedLearningOutcome.INCORRECT,
                            budgetIdentitySeed = "shared-directional-budget",
                        )
                    listOf(positive, negative).forEachIndexed { index, source ->
                        store.acceptFixtureBindings(source)
                        assertEquals(
                            LearningSourceFactIngestStatus.STORED,
                            store.ingestSourceFact(source.command).status,
                        )
                        assertEquals(
                            LearningObservationDisposition.ADMITTED,
                            store.ingestObservationCandidate(
                                candidate(
                                    id = "direction-candidate-$index",
                                    source = source,
                                    candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                                ),
                            ).disposition,
                        )
                    }
                }
                SQLiteDatabase.openDatabase(
                    context.getDatabasePath(databaseName).absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { sqlite ->
                    listOf(
                        "mastery_presentation_node_budget",
                        "mastery_problem_family_node_budget",
                    ).forEach { table ->
                        assertEquals(
                            2L,
                            sqlite.longForQuery("SELECT COUNT(*) FROM $table"),
                        )
                        assertEquals(
                            2L,
                            sqlite.longForQuery(
                                "SELECT COUNT(DISTINCT direction) FROM $table " +
                                    "WHERE direction IN ('POSITIVE', 'NEGATIVE')",
                            ),
                        )
                        sqlite.rawQuery(
                            "SELECT MIN(consumed_mass_micros), " +
                                "MAX(consumed_mass_micros) FROM $table",
                            emptyArray<String>(),
                        ).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            assertTrue(cursor.getLong(0) > 0L)
                            assertEquals(cursor.getLong(0), cursor.getLong(1))
                        }
                    }
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }

    @Test
    fun revokedOrStaleProblemBindingsCannotAuthorizeModelEvidence() = runBlocking {
        withStore("binding-revocation.mastery-test.db") { store ->
            val source = sourceFact(id = "fact-revoked", occurredAt = DAY_2)
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureBindings(source, bindingSetVersion = 1L),
            )
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureBindings(
                    source = source,
                    bindingSetVersion = 3L,
                    bindings = emptyList(),
                ),
            )
            assertEquals(
                MasteryInboundDisposition.DUPLICATE,
                store.acceptFixtureBindings(source, bindingSetVersion = 2L),
            )
            store.ingestSourceFact(source.command)

            val result =
                store.ingestObservationCandidate(
                    candidate("candidate-revoked", source),
                )

            assertEquals(LearningObservationDisposition.INERT, result.disposition)
            assertEquals(
                LearningObservationInertReason.PROBLEM_BINDING_NOT_AUTHORIZED,
                result.inertReason,
            )
            assertTrue(store.mathDigest().focus.isEmpty())
        }
    }

    @Test
    fun lifecycleRetirementSupersedesExistingProjectionEvidence() = runBlocking {
        withStore("lifecycle-retirement.mastery-test.db") { store ->
            val source = sourceFact(id = "fact-retire", occurredAt = DAY_2)
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureBindings(source),
            )
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(source.command).status,
            )
            val ingestion =
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate-retire",
                        source = source,
                        proposedBindings = source.bindings,
                    ),
                )
            assertEquals(LearningObservationDisposition.ADMITTED, ingestion.disposition)
            assertTrue(store.mathDigest().focus.isNotEmpty())

            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureLifecycleChange(
                    source = source,
                    nextState = ProblemLifecycleState.TOMBSTONED,
                    changedAtEpochMillis = DAY_3,
                ),
            )

            assertTrue(store.mathDigest().focus.isEmpty())
        }
    }

    @Test
    fun archiveDoesNotRetireExistingProjectionAndRestoreKeepsIt() = runBlocking {
        withStore("archive-preserves-projection.mastery-test.db") { store ->
            val source = sourceFact(id = "fact-archived", occurredAt = DAY_2)
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureBindings(source),
            )
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(source.command).status,
            )
            val ingestion =
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate-archived",
                        source = source,
                        proposedBindings = source.bindings,
                    ),
                )
            assertEquals(LearningObservationDisposition.ADMITTED, ingestion.disposition)
            assertTrue(store.mathDigest().focus.isNotEmpty())

            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureLifecycleChange(
                    source = source,
                    nextState = ProblemLifecycleState.ARCHIVED,
                    changedAtEpochMillis = DAY_3,
                ),
            )
            assertTrue(store.mathDigest().focus.isNotEmpty())

            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureLifecycleChange(
                    source = source,
                    nextState = ProblemLifecycleState.ACTIVE,
                    changedAtEpochMillis = DAY_3 + 1L,
                    previousState = ProblemLifecycleState.ARCHIVED,
                ),
            )
            assertTrue(store.mathDigest().focus.isNotEmpty())
        }
    }

    @Test
    fun revisionSupersessionRetiresPreviousProjectionEvidence() = runBlocking {
        withStore("revision-supersession.mastery-test.db") { store ->
            val source = sourceFact(id = "fact-superseded", occurredAt = DAY_2)
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureBindings(source),
            )
            assertEquals(
                LearningSourceFactIngestStatus.STORED,
                store.ingestSourceFact(source.command).status,
            )
            val ingestion =
                store.ingestObservationCandidate(
                    candidate(
                        id = "candidate-superseded",
                        source = source,
                        proposedBindings = source.bindings,
                    ),
                )
            assertEquals(LearningObservationDisposition.ADMITTED, ingestion.disposition)
            assertTrue(store.mathDigest().focus.isNotEmpty())

            val nextRevision =
                source.revision.copy(
                    revisionId = "revision:${source.revision.revisionId}-2",
                    revisionNumber = 2,
                    documentCanonicalFingerprint = "b".repeat(64),
                )
            assertEquals(
                MasteryInboundDisposition.APPLIED,
                store.acceptFixtureRevisionSupersession(
                    source = source,
                    nextRevision = nextRevision,
                    changedAtEpochMillis = DAY_3,
                ),
            )

            assertTrue(store.mathDigest().focus.isEmpty())
        }
    }

    @Test
    fun lifecycleRetirementReplayAfterDatabaseReopenStaysIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "lifecycle-replay.mastery-test.db"
        context.deleteDatabase(databaseName)
        try {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                val source = sourceFact(id = "fact-lifecycle-replay", occurredAt = DAY_2)
                assertEquals(
                    MasteryInboundDisposition.APPLIED,
                    store.acceptFixtureBindings(source),
                )
                assertEquals(
                    LearningSourceFactIngestStatus.STORED,
                    store.ingestSourceFact(source.command).status,
                )
                assertEquals(
                    LearningObservationDisposition.ADMITTED,
                    store.ingestObservationCandidate(
                        candidate(
                            id = "candidate-lifecycle-replay",
                            source = source,
                            proposedBindings = source.bindings,
                        ),
                    ).disposition,
                )
                assertTrue(store.mathDigest().focus.isNotEmpty())
                assertEquals(
                    MasteryInboundDisposition.APPLIED,
                    store.acceptFixtureLifecycleChange(
                        source = source,
                        nextState = ProblemLifecycleState.TOMBSTONED,
                        changedAtEpochMillis = DAY_3,
                    ),
                )
                assertTrue(store.mathDigest().focus.isEmpty())
            }

            val supersessionRowsBeforeReplay =
                supersessionRowCount(context, databaseName)
            assertTrue(supersessionRowsBeforeReplay > 0L)

            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { reopened ->
                val source = sourceFact(id = "fact-lifecycle-replay", occurredAt = DAY_2)
                assertEquals(
                    MasteryInboundDisposition.DUPLICATE,
                    reopened.acceptFixtureLifecycleChange(
                        source = source,
                        nextState = ProblemLifecycleState.TOMBSTONED,
                        changedAtEpochMillis = DAY_3,
                    ),
                )
                assertTrue(reopened.mathDigest().focus.isEmpty())
            }

            assertEquals(
                supersessionRowsBeforeReplay,
                supersessionRowCount(context, databaseName),
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun revisionSupersessionReplayAfterDatabaseReopenStaysIdempotent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "revision-replay.mastery-test.db"
        context.deleteDatabase(databaseName)
        try {
            val source = sourceFact(id = "fact-revision-replay", occurredAt = DAY_2)
            val nextRevision =
                source.revision.copy(
                    revisionId = "revision:${source.revision.revisionId}-replay",
                    revisionNumber = 2,
                    documentCanonicalFingerprint = "b".repeat(64),
                )

            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                assertEquals(
                    MasteryInboundDisposition.APPLIED,
                    store.acceptFixtureBindings(source),
                )
                assertEquals(
                    LearningSourceFactIngestStatus.STORED,
                    store.ingestSourceFact(source.command).status,
                )
                assertEquals(
                    LearningObservationDisposition.ADMITTED,
                    store.ingestObservationCandidate(
                        candidate(
                            id = "candidate-revision-replay",
                            source = source,
                            proposedBindings = source.bindings,
                        ),
                    ).disposition,
                )
                assertTrue(store.mathDigest().focus.isNotEmpty())
                assertEquals(
                    MasteryInboundDisposition.APPLIED,
                    store.acceptFixtureRevisionSupersession(
                        source = source,
                        nextRevision = nextRevision,
                        changedAtEpochMillis = DAY_3,
                    ),
                )
                assertTrue(store.mathDigest().focus.isEmpty())
            }

            val supersessionRowsBeforeReplay =
                supersessionRowCount(context, databaseName)
            assertTrue(supersessionRowsBeforeReplay > 0L)

            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { reopened ->
                assertEquals(
                    MasteryInboundDisposition.DUPLICATE,
                    reopened.acceptFixtureRevisionSupersession(
                        source = source,
                        nextRevision = nextRevision,
                        changedAtEpochMillis = DAY_3,
                    ),
                )
                assertTrue(reopened.mathDigest().focus.isEmpty())
            }

            assertEquals(
                supersessionRowsBeforeReplay,
                supersessionRowCount(context, databaseName),
            )
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private suspend fun captureOrderResult(
        databaseName: String,
        order: List<EventFixture>,
    ): OrderResult {
        lateinit var result: OrderResult
        withStore(databaseName) { store ->
            order.forEach { fixture ->
                val source =
                    sourceFact(
                        id = fixture.factId,
                        occurredAt = fixture.occurredAt,
                        outcome = fixture.outcome,
                    )
                store.acceptFixtureBindings(source)
                store.ingestSourceFact(source.command)
                store.ingestObservationCandidate(candidate(fixture.candidateId, source))
            }
            result =
                OrderResult(
                    digest = store.mathDigest(),
                    timeline =
                        store.querySubjectTimeline(
                            SubjectMasteryTimelineQuery(LEARNER_ID, SubjectKind.MATH),
                        ),
                )
        }
        return result
    }

    private suspend fun LearnerMasteryStore.mathDigest(): SubjectMasteryDigest =
        querySubjectDigest(
            SubjectMasteryDigestQuery(
                learnerId = LEARNER_ID,
                subject = SubjectKind.MATH,
            ),
        )

    private fun LearnerMasteryModelAccessProvider.openLease(
        subject: SubjectKind,
        sourceFactId: String,
        modelVersion: String,
        requestVersion: String,
        modeVersion: String,
    ): LearnerMasteryModelAccessLease =
        openLease(
            modelRequestScope(
                subject = subject,
                sourceFactId = sourceFactId,
                modelVersion = modelVersion,
                requestVersion = requestVersion,
                modeVersion = modeVersion,
            ),
        )

    private fun modelRequestScope(
        subject: SubjectKind,
        sourceFactId: String,
        modelVersion: String,
        requestVersion: String,
        modeVersion: String,
    ): LearnerMasteryModelRequestScope {
        val sourceRevision =
            StudentProblemRevisionRef(
                problem =
                    StudentProblemRef(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        problemId = "problem:$sourceFactId",
                        practiceUnitId = "practice:$sourceFactId",
                    ),
                revisionId = "revision:$sourceFactId",
                revisionNumber = 1,
                documentCanonicalFingerprint = "a".repeat(64),
            )
        return LearnerMasteryModelRequestScope(
            subject = subject,
            sourceFactId = sourceFactId,
            conversationId = "conversation:$requestVersion",
            conversationGeneration = 1L,
            conversationState = "ACTIVE",
            conversationStateVersion = 1L,
            turnReferenceId = "turn:$requestVersion",
            turnOrdinal = 1,
            problemRevisionFingerprint = sourceRevision.canonicalFingerprint,
            problemDocumentFingerprint = sourceRevision.documentCanonicalFingerprint,
            problemFingerprint = null,
            problemFingerprintVersion = null,
            bindingSetVersion = 1L,
            knowledgeManifestFingerprint = null,
            knowledgeActivationGeneration = null,
            sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
            projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            modelVersion = modelVersion,
            requestVersion = requestVersion,
            modeVersion = modeVersion,
            learningWritePermissionVersion = "learning-write-v1",
        )
    }

    private suspend fun LearnerMasteryStore.acceptFixtureBindings(
        source: FixtureSource,
        bindingSetVersion: Long = 1L,
        bindings: List<ProblemKnowledgeBindingRef> = listOf(source.binding),
    ): MasteryInboundDisposition {
        val payload =
            ProblemKnowledgeBindingsSnapshotV2(
                problemRevision = source.revision,
                bindings = bindings.sortedBy(ProblemKnowledgeBindingRef::bindingId),
                bindingSetVersion = bindingSetVersion,
                changedAtEpochMillis = DAY_1 + bindingSetVersion,
            )
        val envelope =
            CrossStoreEventEnvelope(
                    eventId =
                        "binding-event:${source.revision.revisionId}:$bindingSetVersion",
                    sourceStore = StudyStoreKind.STUDENT_MISTAKES,
                    destinationStore = StudyStoreKind.LEARNER_MASTERY,
                    aggregateId = payload.aggregateId,
                    aggregateVersion = bindingSetVersion,
                    occurredAtEpochMillis = payload.occurredAtEpochMillis,
                    idempotencyKey =
                        "binding-idempotency:${source.revision.revisionId}:$bindingSetVersion",
                    sourceStoreGeneration = "student-store-test-v1",
                    payload = payload,
                )
        return acceptProblemKnowledgeBindings(
            message = envelope.verifiedStudentDelivery(),
            receivedAtEpochMillis = NOW,
        )
    }

    private suspend fun LearnerMasteryStore.acceptFixtureLifecycleChange(
        source: FixtureSource,
        nextState: ProblemLifecycleState,
        changedAtEpochMillis: Long,
        previousState: ProblemLifecycleState = ProblemLifecycleState.ACTIVE,
    ): MasteryInboundDisposition {
        val payload =
            ProblemLifecycleChangedV1(
                problemRevision = source.revision,
                previousState = previousState,
                nextState = nextState,
                changedAtEpochMillis = changedAtEpochMillis,
            )
        val envelope =
            CrossStoreEventEnvelope(
                eventId =
                    "lifecycle-event:${source.revision.revisionId}:${nextState.name}",
                sourceStore = StudyStoreKind.STUDENT_MISTAKES,
                destinationStore = StudyStoreKind.LEARNER_MASTERY,
                aggregateId = payload.aggregateId,
                aggregateVersion = changedAtEpochMillis,
                occurredAtEpochMillis = payload.occurredAtEpochMillis,
                idempotencyKey =
                    "lifecycle-idempotency:${source.revision.revisionId}:${nextState.name}",
                sourceStoreGeneration = "student-store-test-v1",
                payload = payload,
            )
        return acceptProblemLifecycleChange(
            message = envelope.verifiedStudentDelivery(),
            receivedAtEpochMillis = NOW,
        )
    }

    private suspend fun RoomLearnerMasteryAuthority.acceptFixtureReviewObservation(
        source: FixtureSource,
        outcome: ReviewVerificationOutcome,
        eventSuffix: String,
        occurredAtEpochMillis: Long,
        receivedAtEpochMillis: Long = NOW,
    ): LearnerMasteryInboundDisposition {
        val payload =
            ReviewObservationCapturedV2(
                problemRevision = source.revision,
                reviewSessionId = "review-session:$eventSuffix",
                reviewQueueItemId = "review-queue:$eventSuffix",
                observationId = "review-observation:$eventSuffix",
                submissionId = "review-submission:$eventSuffix",
                presentationId = "review-presentation:$eventSuffix",
                responseForm = ReviewResponseForm.NUMERIC,
                responseOpaqueBinding = "5".repeat(64),
                responseBindingAlgorithmVersion = "test-hmac-sha256-v1",
                verificationOutcome = outcome,
                attemptOrdinal = 1,
                hintCount = 0,
                answerWasRevealed = false,
                verificationPolicyVersion = "review-verification-v1",
                elapsedDurationMillis = 20_000L,
                capturedAtEpochMillis = occurredAtEpochMillis,
            )
        val envelope =
            CrossStoreEventEnvelope(
                eventId = "review-observation-event:$eventSuffix",
                sourceStore = StudyStoreKind.STUDENT_MISTAKES,
                destinationStore = StudyStoreKind.LEARNER_MASTERY,
                aggregateId = payload.aggregateId,
                aggregateVersion = ReviewObservationCapturedV2.PAYLOAD_VERSION.toLong(),
                occurredAtEpochMillis = payload.occurredAtEpochMillis,
                idempotencyKey = "review-observation-idempotency:$eventSuffix",
                sourceStoreGeneration = "student-store-test-v1",
                payload = payload,
            )
        return relay.accept(
            message = envelope.verifiedStudentDelivery(),
            receivedAtEpochMillis = receivedAtEpochMillis,
        )
    }

    private suspend fun LearnerMasteryStore.acceptFixtureRevisionSupersession(
        source: FixtureSource,
        nextRevision: StudentProblemRevisionRef,
        changedAtEpochMillis: Long,
    ): MasteryInboundDisposition {
        val payload =
            ProblemRevisionSupersededV1(
                previousRevision = source.revision,
                nextRevision = nextRevision,
                changedAtEpochMillis = changedAtEpochMillis,
            )
        val envelope =
            CrossStoreEventEnvelope(
                eventId =
                    "revision-supersession:${source.revision.revisionId}:${nextRevision.revisionId}",
                sourceStore = StudyStoreKind.STUDENT_MISTAKES,
                destinationStore = StudyStoreKind.LEARNER_MASTERY,
                aggregateId = payload.aggregateId,
                aggregateVersion = changedAtEpochMillis,
                occurredAtEpochMillis = payload.occurredAtEpochMillis,
                idempotencyKey =
                    "revision-supersession-idempotency:${source.revision.revisionId}:${nextRevision.revisionId}",
                sourceStoreGeneration = "student-store-test-v1",
                payload = payload,
            )
        return acceptProblemRevisionSupersession(
            message = envelope.verifiedStudentDelivery(),
            receivedAtEpochMillis = NOW,
        )
    }

    private fun CrossStoreEventEnvelope.verifiedStudentDelivery():
        VerifiedStudentMistakeDelivery {
        val raw =
            StudentMistakeRelayMessage.fromUnverifiedEnvelopeAndProof(
                this,
                StudentOutboxAuthenticityProof(
                    protocolVersion = StudentOutboxAuthenticityProof.PROTOCOL_VERSION,
                    algorithmVersion = StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
                    issuerKeyId = "student-test-key",
                    learnerId = LEARNER_ID,
                    envelopeCanonicalFingerprint = canonicalFingerprint,
                    tagHex = "0".repeat(64),
                ),
            )
        return VerifiedStudentMistakeDelivery(
            this,
            LEARNER_ID,
            sourceStoreGeneration,
            "student-test-relay-epoch",
            "student-test-key",
            StudentOutboxAuthenticityProof.ALGORITHM_VERSION,
            raw.authenticityProof.canonicalFingerprint,
            canonicalFingerprint,
            CanonicalSha256("student-outbox-verification-receipt-v1")
                .field("learnerId", LEARNER_ID)
                .field("sourceStoreGeneration", sourceStoreGeneration)
                .field("relayEpoch", "student-test-relay-epoch")
                .field("issuerKeyId", "student-test-key")
                .field("algorithmVersion", StudentOutboxAuthenticityProof.ALGORITHM_VERSION)
                .field("envelopeCanonicalFingerprint", canonicalFingerprint)
                .field("proofCanonicalFingerprint", raw.authenticityProof.canonicalFingerprint)
                .finish(),
        )
    }

    private suspend fun withStore(
        databaseName: String,
        block: suspend (LearnerMasteryStore) -> Unit,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        try {
            LearnerMasteryStoreFactory.openForTest(
                context = context,
                databaseName = databaseName,
                nowEpochMillis = { NOW },
            ).use { store ->
                block(store)
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun supersessionRowCount(
        context: Context,
        databaseName: String,
    ): Long =
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            sqlite.longForQuery(
                "SELECT COUNT(*) FROM mastery_learning_evidence_supersession",
            )
        }

    private fun correctionFixture(
        original: FixtureSource,
        originalFingerprint: String = original.command.canonicalFingerprint,
        replacementId: String,
        replacementOutcome: ObservedLearningOutcome,
        idempotencyKey: String,
    ): CorrectionFixture {
        val review =
            requireNotNull(original.command.reviewAttemptContext) {
                "Correction fixture requires a saved review observation"
            }
        val sourceFact =
            IngestLearningSourceFactCommand(
                sourceFactId = replacementId,
                learnerId = original.command.learnerId,
                subject = original.command.subject,
                sourceKind = original.command.sourceKind,
                sourceReferenceId = "source:$replacementId",
                presentationId = original.command.presentationId,
                presentationFingerprint = original.command.presentationFingerprint,
                problemFamilyFingerprint = original.command.problemFamilyFingerprint,
                evidenceContextKind = original.command.evidenceContextKind,
                ephemeralProblemFingerprint = original.command.ephemeralProblemFingerprint,
                tutorTurnReferenceId = original.command.tutorTurnReferenceId,
                submissionEvidenceFingerprint =
                    original.command.submissionEvidenceFingerprint,
                attributionModelVersion = original.command.attributionModelVersion,
                authorizedProblemBindingsFingerprint =
                    original.command.authorizedProblemBindingsFingerprint,
                authorizedKnowledgeRefsFingerprint =
                    original.command.authorizedKnowledgeRefsFingerprint,
                knowledgeManifestFingerprint = original.command.knowledgeManifestFingerprint,
                knowledgeActivationGeneration =
                    original.command.knowledgeActivationGeneration,
                authorityAttemptFingerprint = original.command.authorityAttemptFingerprint,
                authoritySubmissionFingerprint =
                    original.command.authoritySubmissionFingerprint,
                authorityPresentationFingerprint =
                    original.command.authorityPresentationFingerprint,
                authorityProblemFamilyFingerprint =
                    original.command.authorityProblemFamilyFingerprint,
                authorityIdentityVersion = original.command.authorityIdentityVersion,
                problemRevision = original.revision,
                reviewAttemptContext = review,
                responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                independentlyAnswered = true,
                hintCount = 0,
                answerRevealed = false,
                elapsedDurationMillis = original.command.elapsedDurationMillis,
                verificationKind = TrustedLearningVerification.DETERMINISTIC_RUBRIC,
                outcome = replacementOutcome,
                assistance = ObservedAssistance.INDEPENDENT,
                retryState = ObservedRetryState.FIRST_ATTEMPT,
                authority = MasteryEvidenceAuthority.DETERMINISTIC_RUBRIC,
                sourcePayloadCanonicalFingerprint =
                    CanonicalSha256("test-corrected-source-payload-v1")
                        .field("replacementId", replacementId)
                        .field("outcome", replacementOutcome.name)
                        .finish(),
                occurredAtEpochMillis = original.command.occurredAtEpochMillis,
                attestedAtEpochMillis = NOW,
                idempotencyKey = "source-idempotency:$replacementId",
            )
        val replacement =
            FixtureSource(
                command = sourceFact,
                node = original.node,
                binding = original.binding,
                bindings = original.bindings,
                revision = original.revision,
            )
        val observation =
            LearningObservationFacts(
                observationId = replacementId,
                subject = original.command.subject,
                source = TrustedLearningObservationSource.SAVED_PROBLEM_REVIEW,
                sourceReferenceId = "source:$replacementId",
                presentationFingerprint = original.command.presentationFingerprint,
                context =
                    SavedMistakeLearningContext(
                        problemRevision = original.revision,
                        problemFamilyFingerprint = original.command.problemFamilyFingerprint,
                        reviewAttempt =
                            TrustedReviewAttemptEvidence(
                                reviewSessionId = review.reviewSessionId,
                                reviewQueueItemId = review.reviewQueueItemId,
                                submissionId = review.submissionId,
                            ),
                        knowledgeEvidenceBindings = listOf(original.binding),
                    ),
                responseForm = TrustedLearningResponseForm.MULTIPLE_CHOICE,
                answerWasCorrect = replacementOutcome == ObservedLearningOutcome.CORRECT,
                learnerReportedStuck = false,
                answerWasViewed = false,
                independentlyAnswered = true,
                hintCount = 0,
                answerRevealed = false,
                retryCount = 0,
                elapsedDurationMillis = 30_000L,
                verification = TrustedLearningVerification.DETERMINISTIC_RUBRIC,
                evidenceCanonicalFingerprint =
                    CanonicalSha256("test-corrected-observation-v1")
                        .field("replacementId", replacementId)
                        .field("outcome", replacementOutcome.name)
                        .finish(),
                occurredAtEpochMillis = original.command.occurredAtEpochMillis,
                attestedAtEpochMillis = NOW,
            )
        return CorrectionFixture(
            command =
                correctionCommand(
                    originalFingerprint = originalFingerprint,
                    replacement = observation,
                    idempotencyKey = idempotencyKey,
                ),
            observation = observation,
            sourceFact = sourceFact,
            candidate =
                candidate(
                    id = "candidate:$replacementId",
                    source = replacement,
                    candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
                ),
        )
    }

    private fun correctionCommand(
        originalFingerprint: String,
        replacement: LearningObservationFacts,
        idempotencyKey: String,
    ): CorrectLearningEvidenceCommand =
        CorrectLearningEvidenceCommand(
            subject = replacement.subject,
            originalSourceFactCanonicalFingerprint = originalFingerprint,
            replacementObservation = replacement,
            authority = LearningEvidenceCorrectionAuthority.INDEPENDENT_MODEL_REVIEW,
            authorityVersion = "test-correction-authority-v1",
            correctionEvidenceFingerprint =
                CanonicalSha256("test-correction-evidence-v1")
                    .field("idempotencyKey", idempotencyKey)
                    .finish(),
            idempotencyKey = idempotencyKey,
            correctedAtEpochMillis = NOW,
        )

    private suspend fun LearnerMasteryStore.applyCorrection(
        fixture: CorrectionFixture,
    ): LearningEvidenceCorrectionResult =
        correctLearningEvidence(
            learnerId = LEARNER_ID,
            command = fixture.command,
            replacementSourceFact = fixture.sourceFact,
            replacementCandidate = fixture.candidate,
        )

    private fun SQLiteDatabase.longForQuery(sql: String): Long =
        rawQuery(sql, emptyArray<String>()).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun SQLiteDatabase.snapshotMasteryDerivedState(): Map<String, List<String>> =
        listOf(
            "mastery_knowledge_projection",
            "mastery_subject_digest",
            "mastery_presentation_node_budget",
            "mastery_problem_family_node_budget",
        ).associateWith { tableName ->
            rawQuery("SELECT * FROM $tableName", emptyArray<String>()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            (0 until cursor.columnCount).joinToString(separator = "|") { index ->
                                if (cursor.isNull(index)) {
                                    "-1:"
                                } else {
                                    cursor.getString(index).let { value -> "${value.length}:$value" }
                                }
                            },
                        )
                    }
                }.sorted()
            }
        }

    private fun assertMasteryDerivedStateEquals(
        expected: Map<String, List<String>>,
        actual: Map<String, List<String>>,
    ) {
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (tableName, expectedRows) ->
            val actualRows = checkNotNull(actual[tableName])
            assertEquals("$tableName row count", expectedRows.size, actualRows.size)
            expectedRows.indices.forEach { rowIndex ->
                assertEquals(
                    "$tableName canonical row $rowIndex",
                    expectedRows[rowIndex],
                    actualRows[rowIndex],
                )
            }
        }
    }

    private fun SQLiteDatabase.rowCountForQuery(sql: String): Int =
        rawQuery(sql, emptyArray<String>()).use { cursor ->
            cursor.count
        }

    private fun legacyProjectionRebuildCompleteProgress(): String =
        listOf(
            "1",
            MasteryProjectionRebuildStage.COMPLETE.name,
            "",
            "",
            "",
            "",
            "event-sequence-stream-v1",
            "-1",
            "",
            "-1",
        ).joinToString(separator = "") { part -> "${part.length}:$part" }

    private fun incompleteProjectionRebuildProgress(): String =
        listOf(
            "1",
            MasteryProjectionRebuildStage.PROJECTIONS.name,
            "",
            "",
            "",
            "",
            "event-sequence-subject-history-stream-v2",
            "-1",
            "",
            "-1",
        ).joinToString(separator = "") { part -> "${part.length}:$part" }

    private fun sourceFact(
        id: String,
        occurredAt: Long,
        outcome: ObservedLearningOutcome = ObservedLearningOutcome.CORRECT,
        knowledgeNodeId: String = "math.function.quadratic",
        taxonomyVersion: String = "taxonomy-v1",
        knowledgePackVersion: String = "pack-v1",
        sourcePolicyVersion: String = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
        authority: MasteryEvidenceAuthority = MasteryEvidenceAuthority.LOCAL_VERIFIED,
        verificationKind: TrustedLearningVerification =
            TrustedLearningVerification.DEVICE_OBSERVED,
        responseForm: TrustedLearningResponseForm = TrustedLearningResponseForm.FREE_RESPONSE,
        independentlyAnswered: Boolean = true,
        authorityIdentitySeed: String? = null,
        budgetIdentitySeed: String? = authorityIdentitySeed,
        additionalKnowledgeNodeIds: List<String> = emptyList(),
    ): FixtureSource {
        val problem =
            StudentProblemRef(
                learnerId = LEARNER_ID,
                subject = SubjectKind.MATH,
                problemId = "problem:$id",
                practiceUnitId = "practice:$id",
            )
        val revision =
            StudentProblemRevisionRef(
                problem = problem,
                revisionId = "revision:$id",
                revisionNumber = 1,
                documentCanonicalFingerprint = "a".repeat(64),
            )
        val node =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = knowledgeNodeId,
                taxonomyVersion = taxonomyVersion,
                knowledgePackVersion = knowledgePackVersion,
            )
        val binding =
            ProblemKnowledgeBindingRef(
                bindingId = "binding:${node.canonicalFingerprint.take(24)}",
                problemRevision = revision,
                knowledgeNode = node,
                bindingCanonicalFingerprint =
                    CanonicalSha256("test-problem-binding-v1")
                        .field("revision", revision.canonicalFingerprint)
                        .field("node", node.canonicalFingerprint)
                    .finish(),
            )
        val additionalBindings =
            additionalKnowledgeNodeIds.map { additionalNodeId ->
                val additionalNode =
                    KnowledgeNodeRef(
                        subject = SubjectKind.MATH,
                        knowledgeNodeId = additionalNodeId,
                        taxonomyVersion = taxonomyVersion,
                        knowledgePackVersion = knowledgePackVersion,
                    )
                ProblemKnowledgeBindingRef(
                    bindingId =
                        "binding:${additionalNode.canonicalFingerprint.take(24)}",
                    problemRevision = revision,
                    knowledgeNode = additionalNode,
                    bindingCanonicalFingerprint =
                        CanonicalSha256("test-problem-binding-v1")
                            .field("revision", revision.canonicalFingerprint)
                            .field("node", additionalNode.canonicalFingerprint)
                            .finish(),
                )
            }
        val bindings = listOf(binding) + additionalBindings
        val submissionIdentitySeed =
            authorityIdentitySeed ?: budgetIdentitySeed?.let { "$it:$id" }
        return FixtureSource(
            command =
                IngestLearningSourceFactCommand(
                    sourceFactId = id,
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.MATH,
                    sourceKind = MasteryEvidenceSourceKind.SAVED_PROBLEM_REVIEW,
                    sourceReferenceId = "source:$id",
                    presentationId = "presentation:$id",
                    presentationFingerprint =
                        CanonicalSha256("test-presentation-v1")
                            .field("sourceFactId", id)
                            .finish(),
                    problemFamilyFingerprint =
                        CanonicalSha256("test-problem-family-v1")
                            .field("sourceFactId", id)
                            .finish(),
                    evidenceContextKind = MasteryEvidenceContextKind.SAVED_MISTAKE,
                    ephemeralProblemFingerprint = null,
                    tutorTurnReferenceId = null,
                    submissionEvidenceFingerprint = null,
                    attributionModelVersion = null,
                    authorizedProblemBindingsFingerprint =
                        fingerprintAuthorizedProblemBindings(
                            bindings.map(ProblemKnowledgeBindingRef::canonicalFingerprint),
                        ),
                    authorizedKnowledgeRefsFingerprint = null,
                    knowledgeManifestFingerprint = null,
                    knowledgeActivationGeneration = null,
                    authorityAttemptFingerprint =
                        submissionIdentitySeed?.let { seed ->
                            CanonicalSha256("test-authority-attempt-v1")
                                .field("seed", seed)
                                .finish()
                        },
                    authoritySubmissionFingerprint =
                        submissionIdentitySeed?.let { seed ->
                            CanonicalSha256("test-authority-submission-v1")
                                .field("seed", seed)
                                .finish()
                        },
                    authorityPresentationFingerprint =
                        budgetIdentitySeed?.let { seed ->
                            CanonicalSha256("test-authority-presentation-v1")
                                .field("seed", seed)
                                .finish()
                        },
                    authorityProblemFamilyFingerprint =
                        budgetIdentitySeed?.let { seed ->
                            CanonicalSha256("test-authority-family-v1")
                                .field("seed", seed)
                                .finish()
                        },
                    authorityIdentityVersion =
                        (authorityIdentitySeed ?: budgetIdentitySeed)?.let {
                            LEARNER_MASTERY_AUTHORITY_IDENTITY_VERSION
                        },
                    problemRevision = revision,
                    reviewAttemptContext =
                        TrustedReviewAttemptContext(
                            reviewSessionId = "review-session:$id",
                            reviewQueueItemId = "review-queue:$id",
                            submissionId = "submission:$id",
                        ),
                    responseForm = responseForm,
                    independentlyAnswered = independentlyAnswered,
                    hintCount = 0,
                    answerRevealed = false,
                    elapsedDurationMillis = 30_000L,
                    verificationKind = verificationKind,
                    outcome = outcome,
                    assistance = ObservedAssistance.INDEPENDENT,
                    retryState = ObservedRetryState.FIRST_ATTEMPT,
                    authority = authority,
                    sourcePayloadCanonicalFingerprint =
                        CanonicalSha256("test-source-payload-v1")
                            .field("sourceFactId", id)
                            .finish(),
                    occurredAtEpochMillis = occurredAt,
                    attestedAtEpochMillis = NOW,
                    idempotencyKey = "source-idempotency:$id",
                    sourcePolicyVersion = sourcePolicyVersion,
                ),
            node = node,
            binding = binding,
            bindings = bindings,
            revision = revision,
        )
    }

    private fun candidate(
        id: String,
        source: FixtureSource,
        requestedPolicyVersion: String = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
        candidateOrigin: MasteryCandidateOrigin = MasteryCandidateOrigin.MODEL_SCOPED,
        proposedBindings: List<ProblemKnowledgeBindingRef> = listOf(source.binding),
        additionalAttributionRole: MasteryAttributionRole = MasteryAttributionRole.PRIMARY,
    ): IngestLearningObservationCandidateCommand =
        IngestLearningObservationCandidateCommand(
            candidateId = id,
            learnerId = LEARNER_ID,
            subject = SubjectKind.MATH,
            sourceFactId = source.command.sourceFactId,
            proposedAttributions =
                proposedBindings.map { binding ->
                    ProposedKnowledgeAttribution(
                        knowledgeNode = binding.knowledgeNode,
                        problemBinding = binding,
                        role =
                            if (proposedBindings.firstOrNull() == binding) {
                                MasteryAttributionRole.PRIMARY
                            } else {
                                additionalAttributionRole
                            },
                        certainty = MasteryAttributionCertainty.DIRECT,
                    )
                },
            confidence = MasteryCandidateConfidence.HIGH,
            modelVersion = "test-attribution-v1",
            idempotencyKey = "candidate-idempotency:$id",
            proposedAtEpochMillis = NOW,
            requestedPolicyVersion = requestedPolicyVersion,
            candidateOrigin = candidateOrigin,
        )

    private fun modelCandidate(
        source: FixtureSource,
        confidence: MasteryCandidateConfidence = MasteryCandidateConfidence.HIGH,
        bindings: List<ProblemKnowledgeBindingRef> = source.bindings,
    ): SubmitLearningObservationCandidateCommand =
        SubmitLearningObservationCandidateCommand(
            proposedAttributions =
                bindings.map { binding ->
                    ProposedKnowledgeAttribution(
                        knowledgeNode = binding.knowledgeNode,
                        problemBinding = binding,
                        role = MasteryAttributionRole.PRIMARY,
                        certainty = MasteryAttributionCertainty.DIRECT,
                    )
                },
            confidence = confidence,
        )

    private fun pendingOpenResponseSourceFact(
        payloadFingerprint: String,
    ): IngestLearningSourceFactCommand =
        IngestLearningSourceFactCommand(
            sourceFactId = "pending-open-response-source",
            learnerId = LEARNER_ID,
            subject = SubjectKind.MATH,
            sourceKind = MasteryEvidenceSourceKind.TUTOR_FREE_RESPONSE,
            sourceReferenceId = "pending-open-response-submission",
            presentationId = "pending-open-response-presentation",
            presentationFingerprint = "1".repeat(64),
            problemFamilyFingerprint = "2".repeat(64),
            evidenceContextKind = MasteryEvidenceContextKind.EPHEMERAL_TUTOR_PROBLEM,
            ephemeralProblemFingerprint = "3".repeat(64),
            tutorTurnReferenceId = "pending-open-response-turn",
            submissionEvidenceFingerprint = "4".repeat(64),
            attributionModelVersion = "pending-open-response-v1",
            authorizedProblemBindingsFingerprint = null,
            authorizedKnowledgeRefsFingerprint = fingerprintAuthorizedKnowledgeRefs(emptyList()),
            knowledgeManifestFingerprint = null,
            knowledgeActivationGeneration = null,
            problemRevision = null,
            responseForm = TrustedLearningResponseForm.FREE_RESPONSE,
            independentlyAnswered = true,
            hintCount = 0,
            answerRevealed = false,
            elapsedDurationMillis = 1_000L,
            verificationKind = TrustedLearningVerification.SELF_REPORTED,
            outcome = ObservedLearningOutcome.PENDING_REVIEW,
            assistance = ObservedAssistance.INDEPENDENT,
            retryState = ObservedRetryState.FIRST_ATTEMPT,
            authority = MasteryEvidenceAuthority.SELF_REPORTED,
            sourcePayloadCanonicalFingerprint = payloadFingerprint,
            occurredAtEpochMillis = DAY_1,
            attestedAtEpochMillis = DAY_1,
            idempotencyKey = "pending-open-response:pending-open-response-source",
        )

    private fun pendingOpenResponseCandidate(): IngestLearningObservationCandidateCommand =
        IngestLearningObservationCandidateCommand(
            candidateId = "pending-open-response-candidate",
            learnerId = LEARNER_ID,
            subject = SubjectKind.MATH,
            sourceFactId = "pending-open-response-source",
            proposedAttributions = emptyList(),
            confidence = MasteryCandidateConfidence.LOW,
            modelVersion = "pending-open-response-v1",
            idempotencyKey = "pending-open-response-candidate:pending-open-response-source",
            proposedAtEpochMillis = DAY_1,
            candidateOrigin = MasteryCandidateOrigin.TRUSTED_LOCAL,
        )

    private fun migrationCheckpoint(
        fingerprint: String,
        observationCount: Int,
    ): MasteryLegacyFactMigrationCheckpointEntity =
        MasteryLegacyFactMigrationCheckpointEntity(
            learnerId = LEARNER_ID,
            sourceGeneration = "legacy-atomic-v1",
            batchSequence = 1L,
            batchFingerprint = fingerprint,
            observationCount = observationCount,
            finalBatch = false,
            sourcePolicyVersion = LEARNER_MASTERY_SOURCE_POLICY_VERSION,
            projectionPolicyVersion = LEARNER_MASTERY_PROJECTION_POLICY_VERSION,
            completedAtEpochMillis = NOW,
        )

    private data class FixtureSource(
        val command: IngestLearningSourceFactCommand,
        val node: KnowledgeNodeRef,
        val binding: ProblemKnowledgeBindingRef,
        val bindings: List<ProblemKnowledgeBindingRef>,
        val revision: StudentProblemRevisionRef,
    )

    private data class CorrectionFixture(
        val command: CorrectLearningEvidenceCommand,
        val observation: LearningObservationFacts,
        val sourceFact: IngestLearningSourceFactCommand,
        val candidate: IngestLearningObservationCandidateCommand,
    )

    private data class OrderResult(
        val digest: SubjectMasteryDigest,
        val timeline: List<SubjectMasteryTimelineEntry>,
    )

    private enum class EventFixture(
        val factId: String,
        val candidateId: String,
        val occurredAt: Long,
        val outcome: ObservedLearningOutcome,
    ) {
        OLDER_NEGATIVE(
            factId = "fact-old",
            candidateId = "candidate-old",
            occurredAt = DAY_1,
            outcome = ObservedLearningOutcome.INCORRECT,
        ),
        NEWER_POSITIVE(
            factId = "fact-new",
            candidateId = "candidate-new",
            occurredAt = DAY_2,
            outcome = ObservedLearningOutcome.CORRECT,
        ),
    }

    private companion object {
        const val LEARNER_ID = "learner-test"
        const val DAY_MILLIS = 86_400_000L
        const val DAY_1 = 10L * DAY_MILLIS
        const val DAY_2 = 11L * DAY_MILLIS
        const val DAY_3 = 12L * DAY_MILLIS
        const val NOW = 100L * DAY_MILLIS
    }
}
