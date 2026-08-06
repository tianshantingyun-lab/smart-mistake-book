package com.tingyun.smartmistakebook.core.student.mistake.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventEnvelope
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeReferenceProofAuthority
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.MasteryOutboxAuthenticityProof
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleChangedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemLifecycleState
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionSupersededV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV2
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.StudyStoreKind
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StudentMistakeStoreInstrumentedTest {
    private val knowledgeProofAuthority = KnowledgeReferenceProofAuthority.create()

    @Test
    fun reviewCandidatesCarryReviewedProblemFamilyFacet() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val database =
            StudentMistakeStoreFactory.openDatabaseForTest(context, TEST_DATABASE_NAME)
        val store =
            RoomStudentMistakeStore(
                database = database,
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        try {
            val command = problemCommand(errorBookEntryId = "error-book-entry-family")
            store.commitProblem(command)
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = command.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = 110,
                ),
            )
            store.upsertReviewCandidate(reviewCandidate(command.revision))
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(TEST_DATABASE_NAME).absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { sqlite ->
                sqlite.execSQL(
                    """
                    INSERT INTO student_problem_organization_receipt (
                        receipt_id, request_id, request_canonical_fingerprint, request_version,
                        reviewed_request_version, organization_revision, supersedes_receipt_id,
                        previous_payload_canonical_fingerprint, learner_id, subject, problem_id,
                        practice_unit_id, basis_revision_id, basis_revision_number,
                        basis_document_canonical_fingerprint, model_provider_id, model_id,
                        requested_model_version, result_model_version, provider_configuration_version,
                        model_task_schema_version, organization_plan_schema_version, review_source,
                        review_version, review_issuer_key_id, review_issuer_version,
                        review_issued_at_epoch_millis, review_expires_at_epoch_millis,
                        payload_canonical_fingerprint, error_occurrence_count, classification_count,
                        step_knowledge_binding_count, error_attribution_count, facet_count, status,
                        completed_at_epoch_millis
                    ) VALUES (
                        'receipt-family-test', 'request-family-test', '${"b".repeat(64)}', 1,
                        1, 1, NULL, NULL, '$LEARNER_ID', 'MATH', 'problem-1',
                        'practice-unit-1', 'revision-1', 1,
                        '${"a".repeat(64)}', 'provider-family', 'model-family',
                        'model-version-1', 'model-version-1', 'provider-config-family',
                        3, 3, 'LOCAL_POLICY_ACCEPTED',
                        'review-family', 'review-owner-family', 'review-issuer-family',
                        120, 220, '${"c".repeat(64)}', 1, 1,
                        1, 1, 1, 'COMPLETED', 130
                    )
                    """.trimIndent(),
                )
                sqlite.execSQL(
                    """
                    INSERT INTO student_problem_organization_facet (
                        organization_receipt_id, dimension, family_id, family_version,
                        binding_canonical_fingerprint
                    ) VALUES (
                        'receipt-family-test', 'PROBLEM_FAMILY', 'monotonic_interval_family',
                        'model-version-1', '${"d".repeat(64)}'
                    )
                    """.trimIndent(),
                )
            }

            val item =
                store.readReviewCandidatesWithKnowledge(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = 1_000,
                    ),
                ).items.single()
            assertEquals("monotonic_interval_family", item.reviewedProblemFamilyId)
        } finally {
            store.close()
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    @Test
    fun independentStorePersistsBusinessDataAndDeduplicatesMessages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val database =
            StudentMistakeStoreFactory.openDatabaseForTest(context, TEST_DATABASE_NAME)
        val store =
            RoomStudentMistakeStore(
                database = database,
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            val command = problemCommand(errorBookEntryId = "error-book-entry-1")
            assertEquals(command.revision, store.commitProblem(command).revision)
            assertEquals(command.revision, store.commitProblem(command).revision)
            assertEquals(
                1,
                messages.readPending(nowEpochMillis = 1_000).size,
            )

            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = command.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = true,
                    changedAtEpochMillis = 110,
                ),
            )
            val collected = checkNotNull(store.findProblem(command.revision.problem))
            assertEquals(StudentMistakeEntryState.ACTIVE, collected.mistakeState)
            assertTrue(collected.favorite)
            assertEquals(
                listOf(command.revision),
                store.searchMistakes(
                    StudentMistakeSearchQuery(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        text = "顶点",
                    ),
                ).items.map(StudentMistakeSearchItem::problemRevision),
            )
            assertTrue(
                store.searchMistakes(
                    StudentMistakeSearchQuery(
                        learnerId = LEARNER_ID,
                        text = "%",
                    ),
                ).items.isEmpty(),
            )

            val classification = knowledgeClassification(command.revision)
            val classificationCommand =
                classificationCommand(
                    problemRevision = command.revision,
                    results = listOf(classification),
                )
            store.recordClassifications(classificationCommand)
            store.recordClassifications(classificationCommand)
            assertEquals(listOf(classification), store.readClassifications(command.revision))

            val reclassified =
                classification.copy(
                    classificationId = "classification-2",
                    knowledgeNode =
                        checkNotNull(classification.knowledgeNode).copy(
                            taxonomyVersion = "taxonomy-v2",
                            knowledgePackVersion = "pack-v2",
                        ),
                    classifierVersion = "classifier-v2",
                    resultCanonicalFingerprint = "f".repeat(64),
                    supersedesClassificationId = classification.classificationId,
                    recordedAtEpochMillis = 201,
                )
            val reclassificationCommand =
                classificationCommand(
                    problemRevision = command.revision,
                    results = listOf(reclassified),
                )
            store.recordClassifications(reclassificationCommand)
            store.recordClassifications(reclassificationCommand)
            val persistedClassifications = store.readClassifications(command.revision)
            assertEquals(
                listOf(
                    classification.copy(status = StudentProblemClassificationStatus.SUPERSEDED),
                    reclassified,
                ),
                persistedClassifications,
            )
            assertEquals(
                setOf("pack-v1", "pack-v2"),
                persistedClassifications.mapNotNull { it.knowledgeNode?.knowledgePackVersion }.toSet(),
            )
            assertEquals(
                listOf(reclassified),
                store.readCurrentClassifications(command.revision),
            )
            val candidate = reviewCandidate(command.revision)
            store.upsertReviewCandidate(candidate)
            val candidateWithKnowledge =
                store.readReviewCandidatesWithKnowledge(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = 1_000,
                    ),
                ).items.single()
            assertEquals(candidate, candidateWithKnowledge.candidate)
            assertEquals(
                setOf(checkNotNull(reclassified.knowledgeNode)),
                candidateWithKnowledge.acceptedKnowledgeNodes,
            )
            assertEquals(command.revision, checkNotNull(store.findProblem(command.revision.problem)).revision)
            val pending =
                messages.readPending(nowEpochMillis = 1_000)
            assertEquals(3, pending.size)
            val latestBindings =
                pending
                    .mapNotNull { it.envelope.payload as? ProblemKnowledgeBindingsSnapshotV2 }
                    .last()
            assertEquals(2L, latestBindings.bindingSetVersion)
            assertEquals(
                listOf(reclassified.classificationId),
                latestBindings.bindings.map { it.bindingId },
            )
            assertTrue(
                latestBindings.bindings.single().bindingCanonicalFingerprint !=
                    reclassified.resultCanonicalFingerprint,
            )
            val implicitDuplicate =
                reclassified.copy(
                    classificationId = "classification-implicit-duplicate",
                    knowledgeNode =
                        checkNotNull(reclassified.knowledgeNode).copy(
                            knowledgePackVersion = "pack-v3",
                        ),
                    classifierVersion = "classifier-v3",
                    resultCanonicalFingerprint = "1".repeat(64),
                    supersedesClassificationId = null,
                    recordedAtEpochMillis = 202,
                )
            assertTrue(
                runCatching {
                    store.recordClassifications(
                        classificationCommand(
                            problemRevision = command.revision,
                            results = listOf(implicitDuplicate),
                        ),
                    )
                }.isFailure,
            )
            val cycleA =
                reclassified.copy(
                    classificationId = "classification-cycle-a",
                    resultCanonicalFingerprint = "2".repeat(64),
                    supersedesClassificationId = "classification-cycle-b",
                    recordedAtEpochMillis = 203,
                )
            val cycleB =
                reclassified.copy(
                    classificationId = "classification-cycle-b",
                    resultCanonicalFingerprint = "3".repeat(64),
                    supersedesClassificationId = "classification-cycle-a",
                    recordedAtEpochMillis = 203,
                )
            assertTrue(
                runCatching {
                    store.recordClassifications(
                        classificationCommand(
                            problemRevision = command.revision,
                            results = listOf(cycleA, cycleB),
                        ),
                    )
                }.isFailure,
            )
            val additionalKnowledge =
                reclassified.copy(
                    classificationId = "classification-additional",
                    labelId = "math.function.vertex",
                    knowledgeNode =
                        checkNotNull(reclassified.knowledgeNode).copy(
                            knowledgeNodeId = "math.function.vertex",
                        ),
                    resultCanonicalFingerprint = "6".repeat(64),
                    supersedesClassificationId = null,
                    recordedAtEpochMillis = 203,
                )
            store.recordClassifications(
                classificationCommand(
                    problemRevision = command.revision,
                    results = listOf(additionalKnowledge),
                ),
            )
            val revocation =
                reclassified.copy(
                    classificationId = "classification-revocation",
                    status = StudentProblemClassificationStatus.REVOKED,
                    resultCanonicalFingerprint = "4".repeat(64),
                    supersedesClassificationId = reclassified.classificationId,
                    recordedAtEpochMillis = 204,
                )
            val revocationCommand =
                classificationCommand(
                    problemRevision = command.revision,
                    results = listOf(revocation),
                )
            store.recordClassifications(revocationCommand)
            store.recordClassifications(revocationCommand)
            assertEquals(
                listOf(additionalKnowledge),
                store.readCurrentClassifications(command.revision),
            )
            val classificationHistory =
                store.readClassifications(command.revision)
                    .associateBy(StudentProblemClassificationResult::classificationId)
            assertEquals(
                StudentProblemClassificationStatus.SUPERSEDED,
                classificationHistory.getValue(reclassified.classificationId).status,
            )
            assertEquals(
                StudentProblemClassificationStatus.REVOKED,
                classificationHistory.getValue(revocation.classificationId).status,
            )
            val emptySnapshotRevocation =
                additionalKnowledge.copy(
                    classificationId = "classification-empty-snapshot",
                    status = StudentProblemClassificationStatus.REVOKED,
                    resultCanonicalFingerprint = "7".repeat(64),
                    supersedesClassificationId = additionalKnowledge.classificationId,
                    recordedAtEpochMillis = 205,
            )
            val emptySnapshotCommand =
                classificationCommand(
                    problemRevision = command.revision,
                    results = listOf(emptySnapshotRevocation),
                )
            store.recordClassifications(emptySnapshotCommand)
            store.recordClassifications(emptySnapshotCommand)
            assertTrue(store.readCurrentClassifications(command.revision).isEmpty())
            val bindingSnapshots =
                messages.readPending(nowEpochMillis = 1_000)
                    .mapNotNull { it.envelope.payload as? ProblemKnowledgeBindingsSnapshotV2 }
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L),
                bindingSnapshots.map { it.bindingSetVersion },
            )
            assertTrue(bindingSnapshots.last().bindings.isEmpty())
            assertEquals(
                6,
                messages.readPending(nowEpochMillis = 1_000).size,
            )

            store.upsertReviewCandidate(candidate)
            store.upsertReviewCandidate(candidate)
            assertEquals(
                listOf(candidate),
                store.readReviewCandidates(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = 1_000,
                    ),
                ).items,
            )
            val queue = reviewQueue(command.revision)
            store.storeReviewQueue(queue)
            store.storeReviewQueue(queue)
            assertEquals(
                AdmitStudentTrustedReviewAnswerRuleResult.Admitted,
                store.trustedReviewAnswerRuleAdmissions().admit(
                    trustedChoiceRuleCommand(
                        revision = command.revision,
                        errorBookEntryId =
                            checkNotNull(store.findProblem(command.revision.problem)?.errorBookEntryId),
                    ),
                ),
            )
            assertEquals(queue.items, store.readReviewQueue(LEARNER_ID, LOCAL_DAY))
            assertEquals(
                StudentReviewPlanSnapshot(
                    planId = queue.planId,
                    planCanonicalFingerprint = queue.planCanonicalFingerprint,
                    learnerId = queue.learnerId,
                    localDayEpochDay = queue.localDayEpochDay,
                    timeZoneId = queue.timeZoneId,
                    timeBudgetSeconds = queue.timeBudgetSeconds,
                    generatedAtEpochMillis = queue.generatedAtEpochMillis,
                    plannerVersion = queue.plannerVersion,
                    items = queue.items,
                ),
                store.readReviewPlanSnapshot(LEARNER_ID, LOCAL_DAY),
            )
            val reviewSessions = store.reviewSessionsForLearner(LEARNER_ID)
            val activeSession =
                (
                    reviewSessions.startOrResume(
                        StartStudentReviewSessionCommand(
                            sessionId = "review-session-1",
                            planId = queue.planId,
                            expectedPlanCanonicalFingerprint =
                                queue.planCanonicalFingerprint,
                            startedAtEpochMillis = 230,
                        ),
                    ) as StartStudentReviewSessionResult.Ready
                ).session
            assertEquals(
                StudentReviewQueueState.PRESENTED,
                store.readReviewQueue(LEARNER_ID, LOCAL_DAY).single().state,
            )

            assertEquals(
                candidate,
                store.readReviewCandidates(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = 1_000,
                    ),
                ).items.single(),
            )
            val answerOwner = store.trustedReviewAnswersForLearner(LEARNER_ID) { 500L }
            val answerLease = requireNotNull(answerOwner.issueCurrentLease())
            assertEquals(activeSession.currentPresentationId, answerLease.presentationId)
            val recorded =
                answerOwner.submitResponse(
                    answerLease,
                    StudentTrustedReviewResponse.Choice("A"),
                ) as StudentTrustedReviewSubmissionResult.Recorded
            assertTrue(!recorded.duplicate)
            assertEquals(activeSession.sessionVersion + 1L, recorded.resultingSessionVersion)
            assertEquals(null, reviewSessions.readActiveSession())
            assertEquals(
                StudentTrustedReviewSubmissionResult.ReloadRequired,
                answerOwner.submitResponse(
                    answerLease,
                    StudentTrustedReviewResponse.Choice("A"),
                ),
            )
            val rescheduledCandidate =
                store.readReviewCandidates(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = Long.MAX_VALUE,
                    ),
                ).items.single()
            assertEquals(2L, rescheduledCandidate.candidateVersion)
            assertTrue(rescheduledCandidate.availableAtEpochMillis > 410L)
            assertEquals(
                rescheduledCandidate.availableAtEpochMillis,
                rescheduledCandidate.dueAtEpochMillis,
            )
            assertEquals(null, rescheduledCandidate.sourceEvidence)
            assertTrue("verified-review-response" in rescheduledCandidate.reasonCodes)
            val reviewObservation =
                messages.readPending(nowEpochMillis = Long.MAX_VALUE)
                    .mapNotNull { it.envelope.payload as? ReviewObservationCapturedV2 }
                    .single()
            assertEquals(QUEUE_ITEM_ID, reviewObservation.reviewQueueItemId)
            assertTrue(reviewObservation.responseOpaqueBinding.matches(Regex("[0-9a-f]{64}")))
            assertEquals(
                StudentOutboxBoundReviewResponseBindingIssuer.ALGORITHM_VERSION,
                reviewObservation.responseBindingAlgorithmVersion,
            )
            val attemptEnvelope = learningAttemptEnvelope(command.revision)
            assertEquals(
                listOf(StudentMistakeInboundDisposition.APPLIED),
                messages.acceptInboundBatch(
                    messages = listOf(sourceIssuedMasteryMessage(attemptEnvelope)),
                    receivedAtEpochMillis = 400,
                ),
            )
            assertEquals(
                StudentMistakeInboundDisposition.DUPLICATE,
                messages.acceptInbound(
                    sourceIssuedMasteryMessage(attemptEnvelope),
                    receivedAtEpochMillis = 401,
                ),
            )
            val conflictingPayload =
                (attemptEnvelope.payload as LearningAttemptRecordedV1).copy(
                    submissionId = "submission-conflict",
                )
            val conflictingEnvelope =
                CrossStoreEventEnvelope(
                    eventId = "mastery-event-conflict",
                    sourceStore = StudyStoreKind.LEARNER_MASTERY,
                    destinationStore = StudyStoreKind.STUDENT_MISTAKES,
                    aggregateId = conflictingPayload.aggregateId,
                    aggregateVersion = 2,
                    occurredAtEpochMillis = conflictingPayload.occurredAtEpochMillis,
                    idempotencyKey = attemptEnvelope.idempotencyKey,
                    sourceStoreGeneration = attemptEnvelope.sourceStoreGeneration,
                    payload = conflictingPayload,
                )
            assertTrue(
                runCatching {
                    messages.acceptInbound(
                        message = sourceIssuedMasteryMessage(conflictingEnvelope),
                        receivedAtEpochMillis = 402,
                    )
                }.isFailure,
            )
            assertEquals(
                StudentReviewQueueState.COMPLETED,
                store.readReviewQueue(LEARNER_ID, LOCAL_DAY).single().state,
            )
            val replayPersistence =
                RoomStudentTrustedReviewAnswerPersistence(
                    answers = database.trustedReviewAnswerDao(),
                    reviews = database.mistakeDao(),
                    storeGenerationProvider = store::ensureStoreGeneration,
                    outboxIssuerProvider = { learnerId, sourceStoreGeneration ->
                        val state =
                            checkNotNull(
                                database.mistakeDao()
                                    .readActiveOutboxAuthenticityKeyState(),
                            )
                        check(state.sourceStoreGeneration == sourceStoreGeneration)
                        StudentOutboxAuthenticator(
                            learnerId,
                            state.toActiveKey(),
                            AndroidKeystoreStudentOutboxHmacKeyStore.INSTANCE,
                        )
                    },
                )
            assertEquals(
                StudentTrustedReviewSubmissionResult.Recorded(
                    duplicate = true,
                    resultingSessionVersion = activeSession.sessionVersion + 1L,
                ),
                replayPersistence.submitResponse(
                    learnerId = LEARNER_ID,
                    leaseReceiptId = answerLease.receiptId,
                    leaseCanonicalFingerprint = answerLease.canonicalFingerprint,
                    response = StudentTrustedReviewResponse.Choice("A"),
                    submittedAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            assertEquals(
                1,
                messages.readPending(nowEpochMillis = Long.MAX_VALUE)
                    .count { it.envelope.payload is ReviewObservationCapturedV2 },
            )
            val recoveredPlan =
                checkNotNull(store.readReviewPlanSnapshot(LEARNER_ID, LOCAL_DAY))
            assertEquals(queue.planId, recoveredPlan.planId)
            assertEquals(queue.planCanonicalFingerprint, recoveredPlan.planCanonicalFingerprint)
            assertEquals(queue.timeZoneId, recoveredPlan.timeZoneId)
            assertEquals(queue.timeBudgetSeconds, recoveredPlan.timeBudgetSeconds)
            assertEquals(
                StudentReviewQueueState.COMPLETED,
                recoveredPlan.items.single().state,
            )

            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = command.revision.problem,
                    mistakeState = StudentMistakeEntryState.ARCHIVED,
                    favorite = true,
                    changedAtEpochMillis = 450,
                ),
            )
            assertTrue(
                store.readReviewCandidates(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = 1_000,
                    ),
                ).items.isEmpty(),
            )
            assertTrue(store.readReviewQueue(LEARNER_ID, LOCAL_DAY).isEmpty())
            assertTrue(
                store.searchMistakes(
                    StudentMistakeSearchQuery(learnerId = LEARNER_ID),
                ).items.isEmpty(),
            )
            assertTrue(
                runCatching {
                    store.upsertReviewCandidate(
                        candidate.copy(
                            candidateVersion = 2,
                            updatedAtEpochMillis = 451,
                        ),
                    )
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    store.storeReviewQueue(
                        queue.copy(
                            planId = "plan-inactive",
                            planCanonicalFingerprint = "5".repeat(64),
                            localDayEpochDay = LOCAL_DAY + 1,
                            items =
                                queue.items.map {
                                    it.copy(queueItemId = "queue-item-inactive")
                                },
                        ),
                    )
                }.isFailure,
            )
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = command.revision.problem,
                    mistakeState = StudentMistakeEntryState.TRASHED,
                    favorite = false,
                    changedAtEpochMillis = 460,
                ),
            )
            assertTrue(store.readReviewQueue(LEARNER_ID, LOCAL_DAY).isEmpty())

            store.setProblemLifecycle(
                SetStudentProblemLifecycleCommand(
                    problem = command.revision.problem,
                    state = StudentProblemLifecycleState.ARCHIVED,
                    changedAtEpochMillis = 500,
                ),
            )
            assertEquals(
                StudentProblemLifecycleState.ARCHIVED,
                checkNotNull(store.findProblem(command.revision.problem)).lifecycleState,
            )
            assertTrue(store.readReviewQueue(LEARNER_ID, LOCAL_DAY).isEmpty())
            store.setProblemLifecycle(
                SetStudentProblemLifecycleCommand(
                    problem = command.revision.problem,
                    state = StudentProblemLifecycleState.TOMBSTONED,
                    changedAtEpochMillis = 510,
                ),
            )
            assertTrue(
                runCatching {
                    store.setProblemLifecycle(
                        SetStudentProblemLifecycleCommand(
                            problem = command.revision.problem,
                            state = StudentProblemLifecycleState.ACTIVE,
                            changedAtEpochMillis = 511,
                        ),
                    )
                }.isFailure,
            )
            assertTrue(store.readReviewQueue(LEARNER_ID, LOCAL_DAY).isEmpty())
            assertEquals(
                11,
                messages.readPending(nowEpochMillis = 1_000).size,
            )
        } finally {
            database.close()
        }

        verifyPhysicalSchema(context.getDatabasePath(TEST_DATABASE_NAME).absolutePath)
        context.deleteDatabase(TEST_DATABASE_NAME)
        Unit
    }

    private fun verifyPhysicalSchema(path: String) {
        val sqlite = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables =
                sqlite.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                    emptyArray(),
                ).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0)
                            if (name != "android_metadata" &&
                                name != "room_master_table" &&
                                !name.startsWith("sqlite_") &&
                                !name.startsWith("student_problem_search_fts_")
                            ) {
                                add(name)
                            }
                        }
                    }
                }
            assertEquals(STUDENT_MISTAKE_DOMAIN_TABLES, tables)

            val attachedNames =
                sqlite.rawQuery("PRAGMA database_list", emptyArray()).use { cursor ->
                    buildSet {
                        while (cursor.moveToNext()) add(cursor.getString(1))
                    }
                }
            assertEquals(setOf("main"), attachedNames)

            val requiredTimeColumns =
                mapOf(
                    "student_store_metadata" to
                        setOf("created_at_epoch_millis", "updated_at_epoch_millis"),
                    "student_problem_document" to
                        setOf(
                            "lifecycle_state",
                            "archived_at_epoch_millis",
                            "tombstoned_at_epoch_millis",
                            "created_at_epoch_millis",
                            "updated_at_epoch_millis",
                        ),
                    "student_problem_revision" to setOf("created_at_epoch_millis"),
                    "student_practice_unit" to
                        setOf("created_at_epoch_millis", "updated_at_epoch_millis"),
                    "student_problem_image_reference" to setOf("created_at_epoch_millis"),
                    "student_problem_search_document" to setOf("indexed_at_epoch_millis"),
                    "student_problem_collection" to
                        setOf(
                            "added_at_epoch_millis",
                            "archived_at_epoch_millis",
                            "trashed_at_epoch_millis",
                            "changed_at_epoch_millis",
                        ),
                    "student_problem_classification_result" to
                        setOf("recorded_at_epoch_millis"),
                    "student_review_candidate" to
                        setOf("created_at_epoch_millis", "updated_at_epoch_millis"),
                    "student_review_plan" to setOf("generated_at_epoch_millis"),
                    "student_review_queue_item" to
                        setOf("created_at_epoch_millis", "state_changed_at_epoch_millis"),
                    "student_review_self_report_receipt" to
                        setOf(
                            "reported_at_epoch_millis",
                            "next_available_at_epoch_millis",
                            "next_due_at_epoch_millis",
                        ),
                    "student_review_session" to
                        setOf(
                            "started_at_epoch_millis",
                            "updated_at_epoch_millis",
                            "completed_at_epoch_millis",
                        ),
                    "student_review_transition_receipt" to
                        setOf(
                            "occurred_at_epoch_millis",
                            "next_available_at_epoch_millis",
                            "next_due_at_epoch_millis",
                        ),
                    "student_review_reveal_receipt" to
                        setOf("revealed_at_epoch_millis"),
                    "student_store_outbox" to
                        setOf(
                            "occurred_at_epoch_millis",
                            "available_at_epoch_millis",
                            "delivered_at_epoch_millis",
                        ),
                    "student_store_inbox" to
                        setOf(
                            "occurred_at_epoch_millis",
                            "received_at_epoch_millis",
                            "applied_at_epoch_millis",
                        ),
                )
            requiredTimeColumns.forEach { (table, requiredColumns) ->
                val actualColumns =
                    sqlite.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
                        buildSet {
                            while (cursor.moveToNext()) add(cursor.getString(1))
                        }
                    }
                assertTrue(
                    "$table is missing lifecycle columns ${requiredColumns - actualColumns}",
                    actualColumns.containsAll(requiredColumns),
                )
            }

            val forbiddenFragments =
                setOf(
                    "learning_observation",
                    "mastery",
                    "projection",
                    "knowledge_node",
                    "knowledge_source",
                    "knowledge_material",
                    "teaching_material",
                )
            val masteryRelayCoordinationTables =
                setOf(
                    "student_authenticated_mastery_inbox_receipt",
                    "student_mastery_relay_source_binding",
                    "student_mastery_relay_reauthorization_case",
                    "student_mastery_relay_reauthorization_resolution",
                )
            tables.forEach { table ->
                forbiddenFragments.forEach { fragment ->
                    if (fragment != "mastery" || table !in masteryRelayCoordinationTables) {
                        assertFalse(
                            "Forbidden table fragment '$fragment' found in '$table'",
                            table.contains(fragment),
                        )
                    }
                }
            }
            masteryRelayCoordinationTables.forEach { table ->
                val columns =
                    sqlite.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
                        buildSet {
                            while (cursor.moveToNext()) add(cursor.getString(1))
                        }
                    }
                val forbiddenProjectionColumns =
                    setOf(
                        "mastery_state",
                        "mastery_score",
                        "knowledge_node_id",
                        "posterior_probability",
                        "evidence_weight",
                    )
                assertTrue(
                    "$table contains learner-mastery projection columns",
                    columns.intersect(forbiddenProjectionColumns).isEmpty(),
                )
            }
        } finally {
            sqlite.close()
        }
    }

    @Test
    fun classificationRejectsProofFromAnotherKnowledgeAuthority() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val database =
            StudentMistakeStoreFactory.openDatabaseForTest(context, TEST_DATABASE_NAME)
        val owner = KnowledgeReferenceProofAuthority.create()
        val other = KnowledgeReferenceProofAuthority.create()
        val store =
            RoomStudentMistakeStore(
                database = database,
                knowledgeReferenceVerifier = owner.verifier,
            )
        try {
            val problem = problemCommand()
            store.commitProblem(problem)
            val classification = knowledgeClassification(problem.revision)
            val foreignProof =
                other.issuer.issue(
                    checkNotNull(classification.knowledgeNode),
                    "8".repeat(64),
                    1L,
                )

            assertTrue(
                runCatching {
                    store.recordClassifications(
                        RecordStudentProblemClassificationsCommand(
                            problemRevision = problem.revision,
                            results = listOf(classification),
                            verifiedKnowledgeReferences =
                                mapOf(classification.classificationId to foreignProof),
                        ),
                    )
                }.isFailure,
            )
            assertTrue(store.readClassifications(problem.revision).isEmpty())
        } finally {
            store.close()
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    @Test
    fun stuckTransitionCompletesQueueIdempotentlyWithoutCreatingLearningEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val database =
            StudentMistakeStoreFactory.openDatabaseForTest(context, TEST_DATABASE_NAME)
        val store = RoomStudentMistakeStore(database)
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            val problem = problemCommand()
            store.commitProblem(problem)
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = problem.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = 110,
                ),
            )
            store.upsertReviewCandidate(reviewCandidate(problem.revision))
            val queue = reviewQueue(problem.revision)
            store.storeReviewQueue(queue)
            val reviewSessions = store.reviewSessionsForLearner(LEARNER_ID)
            val activeSession =
                (
                    reviewSessions.startOrResume(
                        StartStudentReviewSessionCommand(
                            sessionId = "review-session-stuck",
                            planId = queue.planId,
                            expectedPlanCanonicalFingerprint =
                                queue.planCanonicalFingerprint,
                            startedAtEpochMillis = 230,
                        ),
                    ) as StartStudentReviewSessionResult.Ready
                ).session
            val pendingBefore = messages.readPending(nowEpochMillis = 1_000).size
            val stuck =
                ReportStudentReviewStuckCommand(
                    transitionId = "review-stuck-1",
                    sessionId = activeSession.sessionId,
                    queueItemId = QUEUE_ITEM_ID,
                    expectedSessionVersion = activeSession.sessionVersion,
                    presentationId = checkNotNull(activeSession.currentPresentationId),
                    elapsedDurationMillis = 1_000L,
                    schedule =
                        StudentReviewScheduleUpdate(
                            nextAvailableAtEpochMillis = 500,
                            nextDueAtEpochMillis = 1_000,
                            schedulingPolicyVersion = "review-policy-v1",
                        ),
                    reportedAtEpochMillis = 300,
                )
            assertTrue(
                reviewSessions.reportStuck(stuck) is StudentReviewTransitionResult.Applied,
            )
            assertTrue(
                reviewSessions.reportStuck(stuck) is StudentReviewTransitionResult.Duplicate,
            )

            val snapshot = checkNotNull(store.readReviewPlanSnapshot(LEARNER_ID, LOCAL_DAY))
            assertEquals(StudentReviewQueueState.COMPLETED, snapshot.items.single().state)
            val candidate =
                store.readReviewCandidates(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = 1_000,
                    ),
                ).items.single()
            assertEquals(2L, candidate.candidateVersion)
            assertEquals(500L, candidate.availableAtEpochMillis)
            assertEquals(1_000L, candidate.dueAtEpochMillis)
            assertEquals(null, candidate.sourceEvidence)
            assertTrue("review-stuck" in candidate.reasonCodes)
            assertEquals(pendingBefore, messages.readPending(nowEpochMillis = 1_000).size)
            assertTrue(
                runCatching {
                    reviewSessions.reportStuck(
                        stuck.copy(
                            schedule =
                                stuck.schedule.copy(
                                    nextAvailableAtEpochMillis = 600,
                                ),
                        ),
                    )
                }.isFailure,
            )
            assertTrue(
                reviewSessions.reportStuck(
                    stuck.copy(transitionId = "review-stuck-after-completion"),
                ) is StudentReviewTransitionResult.ReloadRequired,
            )
        } finally {
            store.close()
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    @Test
    fun revealTransitionSchedulesWithoutEvidenceAndItsReceiptsStayAppendOnly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-review-reveal.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val store =
            RoomStudentMistakeStore(
                StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
            )
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            val problem = problemCommand()
            store.commitProblem(problem)
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = problem.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = 110,
                ),
            )
            store.upsertReviewCandidate(reviewCandidate(problem.revision))
            val queue = reviewQueue(problem.revision)
            store.storeReviewQueue(queue)
            val sessions = store.reviewSessionsForLearner(LEARNER_ID)
            val activeSession =
                (
                    sessions.startOrResume(
                        StartStudentReviewSessionCommand(
                            sessionId = "review-session-reveal",
                            planId = queue.planId,
                            expectedPlanCanonicalFingerprint =
                                queue.planCanonicalFingerprint,
                            startedAtEpochMillis = 230,
                        ),
                    ) as StartStudentReviewSessionResult.Ready
                ).session
            val pendingBefore = messages.readPending(nowEpochMillis = 1_000).size
            val reveal =
                RevealStudentReviewAnswerCommand(
                    transitionId = "review-reveal-transition-1",
                    revealId = "review-reveal-1",
                    sessionId = activeSession.sessionId,
                    queueItemId = QUEUE_ITEM_ID,
                    expectedSessionVersion = activeSession.sessionVersion,
                    presentationId = checkNotNull(activeSession.currentPresentationId),
                    schedule =
                        StudentReviewScheduleUpdate(
                            nextAvailableAtEpochMillis = 700,
                            nextDueAtEpochMillis = 1_500,
                            schedulingPolicyVersion = "review-policy-v1",
                        ),
                    revealedAtEpochMillis = 300,
                )
            assertTrue(
                sessions.revealAnswer(reveal) is StudentReviewTransitionResult.Applied,
            )
            assertTrue(
                sessions.revealAnswer(reveal) is StudentReviewTransitionResult.Duplicate,
            )
            val candidate =
                store.readReviewCandidates(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = 1_000,
                    ),
                ).items.single()
            assertEquals(700L, candidate.availableAtEpochMillis)
            assertEquals(1_500L, candidate.dueAtEpochMillis)
            assertEquals(null, candidate.sourceEvidence)
            assertTrue("review-answer-revealed" in candidate.reasonCodes)
            assertEquals(pendingBefore, messages.readPending(nowEpochMillis = 1_000).size)
        } finally {
            store.close()
        }

        val sqlite =
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).absolutePath,
                null,
                0,
            )
        try {
            sqlite.execSQL("PRAGMA foreign_keys = ON")
            assertTrue(
                runCatching {
                    sqlite.execSQL(
                        """
                        UPDATE student_review_transition_receipt
                        SET scheduling_policy_version = 'tampered'
                        WHERE transition_id = 'review-reveal-transition-1'
                        """.trimIndent(),
                    )
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    sqlite.execSQL(
                        """
                        DELETE FROM student_review_reveal_receipt
                        WHERE reveal_id = 'review-reveal-1'
                        """.trimIndent(),
                    )
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    sqlite.execSQL(
                        """
                        DELETE FROM student_review_queue_item
                        WHERE queue_item_id = '$QUEUE_ITEM_ID'
                        """.trimIndent(),
                    )
                }.isFailure,
            )
        } finally {
            sqlite.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun trustedRevealedAnswerResponseCarriesFastReviewReasonCode() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-review-revealed-response.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val store =
            RoomStudentMistakeStore(
                database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        try {
            val problem = problemCommand(errorBookEntryId = "error-book-entry-revealed")
            store.commitProblem(problem)
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = problem.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = 110,
                ),
            )
            store.recordClassifications(
                classificationCommand(
                    problemRevision = problem.revision,
                    results = listOf(knowledgeClassification(problem.revision)),
                ),
            )
            store.upsertReviewCandidate(reviewCandidate(problem.revision))
            val queue = reviewQueue(problem.revision)
            store.storeReviewQueue(queue)
            assertEquals(
                AdmitStudentTrustedReviewAnswerRuleResult.Admitted,
                store.trustedReviewAnswerRuleAdmissions().admit(
                    trustedChoiceRuleCommand(
                        revision = problem.revision,
                        errorBookEntryId =
                            checkNotNull(store.findProblem(problem.revision.problem)?.errorBookEntryId),
                    ),
                ),
            )
            val sessions = store.reviewSessionsForLearner(LEARNER_ID)
            val activeSession =
                (
                    sessions.startOrResume(
                        StartStudentReviewSessionCommand(
                            sessionId = "review-session-revealed-response",
                            planId = queue.planId,
                            expectedPlanCanonicalFingerprint =
                                queue.planCanonicalFingerprint,
                            startedAtEpochMillis = 230,
                        ),
                    ) as StartStudentReviewSessionResult.Ready
                ).session
            val answerOwner = store.trustedReviewAnswersForLearner(LEARNER_ID) { 500L }
            val lease = requireNotNull(answerOwner.issueCurrentLease())
            assertTrue(
                answerOwner.recordAssistance(
                    lease = lease,
                    assistanceEventId = "assistance-revealed-response",
                    kind = StudentTrustedReviewAssistanceKind.ANSWER_REVEAL,
                ) is StudentTrustedReviewAssistanceResult.Recorded,
            )
            val recorded =
                answerOwner.submitResponse(
                    lease = lease,
                    response = StudentTrustedReviewResponse.Choice("C"),
                ) as StudentTrustedReviewSubmissionResult.Recorded
            assertTrue(!recorded.duplicate)
            assertEquals(activeSession.sessionVersion + 1L, recorded.resultingSessionVersion)
            val candidate =
                store.readReviewCandidates(
                    StudentReviewCandidateQuery(
                        learnerId = LEARNER_ID,
                        nowEpochMillis = Long.MAX_VALUE,
                    ),
                ).items.single()
            assertTrue("review-answer-revealed" in candidate.reasonCodes)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun lifecycleChangePublishesBindingSnapshotRevocationAndRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-lifecycle-binding-recovery.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val store =
            RoomStudentMistakeStore(
                database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            val problem = problemCommand(errorBookEntryId = "error-book-entry-lifecycle")
            store.commitProblem(problem)
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = problem.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = 110,
                ),
            )
            store.recordClassifications(
                classificationCommand(
                    problemRevision = problem.revision,
                    results = listOf(knowledgeClassification(problem.revision)),
                ),
            )

            store.setProblemLifecycle(
                SetStudentProblemLifecycleCommand(
                    problem = problem.revision.problem,
                    state = StudentProblemLifecycleState.ARCHIVED,
                    changedAtEpochMillis = 500,
                ),
            )
            val afterArchive =
                messages.readPending(nowEpochMillis = Long.MAX_VALUE)
                    .mapNotNull { it.envelope.payload as? ProblemKnowledgeBindingsSnapshotV2 }
            val revocation = afterArchive.last()
            assertTrue(revocation.bindings.isEmpty())
            val lifecycleAfterArchive =
                messages.readPending(nowEpochMillis = Long.MAX_VALUE)
                    .mapNotNull { it.envelope.payload as? ProblemLifecycleChangedV1 }
            assertEquals(
                ProblemLifecycleState.ARCHIVED,
                lifecycleAfterArchive.last().nextState,
            )

            store.setProblemLifecycle(
                SetStudentProblemLifecycleCommand(
                    problem = problem.revision.problem,
                    state = StudentProblemLifecycleState.ACTIVE,
                    changedAtEpochMillis = 510,
                ),
            )
            val afterRestore =
                messages.readPending(nowEpochMillis = Long.MAX_VALUE)
                    .mapNotNull { it.envelope.payload as? ProblemKnowledgeBindingsSnapshotV2 }
            val restoration = afterRestore.last()
            assertEquals(revocation.bindingSetVersion + 1L, restoration.bindingSetVersion)
            assertEquals(1, restoration.bindings.size)
            assertEquals(
                problem.revision.canonicalFingerprint,
                restoration.problemRevision.canonicalFingerprint,
            )
            val lifecycleAfterRestore =
                messages.readPending(nowEpochMillis = Long.MAX_VALUE)
                    .mapNotNull { it.envelope.payload as? ProblemLifecycleChangedV1 }
            assertEquals(
                ProblemLifecycleState.ACTIVE,
                lifecycleAfterRestore.last().nextState,
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun revisionCommitPublishesSupersessionEvent() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-revision-supersession.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val store =
            RoomStudentMistakeStore(
                database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            val first = problemCommand()
            store.commitProblem(first)
            val second =
                problemCommand(
                    revisionId = "revision-2",
                    revisionNumber = 2,
                    documentFingerprint = "e".repeat(64),
                    imageReferenceId = "image-ref-2",
                    committedAtEpochMillis = 200,
                )
            store.commitProblem(second)

            val superseded =
                messages.readPending(nowEpochMillis = Long.MAX_VALUE)
                    .mapNotNull { it.envelope.payload as? ProblemRevisionSupersededV1 }
                    .single()
            assertEquals(first.revision, superseded.previousRevision)
            assertEquals(second.revision, superseded.nextRevision)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun unreadyQueueMaintenanceIsExactAuditedAndCreatesNoLearningEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-review-unready-maintenance.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val store =
            RoomStudentMistakeStore(
                database =
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            val problem = problemCommand()
            store.commitProblem(problem)
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = problem.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = 110,
                ),
            )
            val acceptedKnowledge = knowledgeClassification(problem.revision)
            store.recordClassifications(
                classificationCommand(
                    problemRevision = problem.revision,
                    results = listOf(acceptedKnowledge),
                ),
            )
            store.upsertReviewCandidate(reviewCandidate(problem.revision))
            val queue = reviewQueue(problem.revision)
            store.storeReviewQueue(queue)
            val command =
                RemoveUnreadyStudentReviewQueueItemCommand(
                    maintenanceId = "review-unready-maintenance-1",
                    planId = queue.planId,
                    expectedPlanCanonicalFingerprint = queue.planCanonicalFingerprint,
                    queueItemId = QUEUE_ITEM_ID,
                    expectedProblemRevision = problem.revision,
                    reason =
                        StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION,
                    changedAtEpochMillis = 300,
                )
            val sessions = store.reviewSessionsForLearner(LEARNER_ID)

            assertEquals(
                StudentReviewQueueMaintenanceResult.ReloadRequired,
                sessions.removeUnreadyQueueItem(
                    command.copy(maintenanceId = "review-unready-still-attributed"),
                ),
            )
            store.recordClassifications(
                classificationCommand(
                    problemRevision = problem.revision,
                    results =
                        listOf(
                            acceptedKnowledge.copy(
                                classificationId = "classification-maintenance-revoked",
                                resultCanonicalFingerprint = "9".repeat(64),
                                status = StudentProblemClassificationStatus.REVOKED,
                                supersedesClassificationId =
                                    acceptedKnowledge.classificationId,
                                recordedAtEpochMillis = 250,
                            ),
                        ),
                ),
            )
            val pendingMessagesBefore = messages.readPending(nowEpochMillis = 1_000).size
            assertEquals(
                StudentReviewQueueMaintenanceResult.ReloadRequired,
                sessions.removeUnreadyQueueItem(
                    command.copy(
                        maintenanceId = "review-unready-wrong-reason",
                        reason = StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE,
                    ),
                ),
            )
            assertEquals(
                StudentReviewQueueMaintenanceResult.Applied,
                sessions.removeUnreadyQueueItem(command),
            )
            assertEquals(
                StudentReviewQueueMaintenanceResult.Duplicate,
                sessions.removeUnreadyQueueItem(command),
            )
            assertEquals(
                StudentReviewQueueMaintenanceResult.ReloadRequired,
                sessions.removeUnreadyQueueItem(command.copy(changedAtEpochMillis = 301)),
            )
            assertEquals(
                StudentReviewQueueMaintenanceResult.ReloadRequired,
                store.reviewSessionsForLearner("other-learner")
                    .removeUnreadyQueueItem(command),
            )

            val removed = store.readReviewQueue(LEARNER_ID, LOCAL_DAY).single()
            assertEquals(StudentReviewQueueState.REMOVED, removed.state)
            assertEquals(setOf(command.auditReasonCode(LEARNER_ID)), removed.reasonCodes)
            assertEquals(
                problem.revision,
                checkNotNull(store.findProblem(problem.revision.problem)).revision,
            )
            assertEquals(
                pendingMessagesBefore,
                messages.readPending(nowEpochMillis = 1_000).size,
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun unreadyQueueMaintenanceRemovesOnlyTheSupersededQueuedRevision() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-review-superseded-maintenance.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val store =
            RoomStudentMistakeStore(
                StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
            )
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            val first = problemCommand(errorBookEntryId = "error-book-entry-1")
            store.commitProblem(first)
            store.setCollectionState(
                SetStudentProblemCollectionCommand(
                    problem = first.revision.problem,
                    mistakeState = StudentMistakeEntryState.ACTIVE,
                    favorite = false,
                    changedAtEpochMillis = 110,
                ),
            )
            store.upsertReviewCandidate(reviewCandidate(first.revision))
            val queue = reviewQueue(first.revision)
            store.storeReviewQueue(queue)
            val secondRevision =
                StudentProblemRevisionRef(
                    problem = first.revision.problem,
                    revisionId = "revision-current-2",
                    revisionNumber = 2,
                    documentCanonicalFingerprint = "7".repeat(64),
                )
            store.commitProblem(
                first.copy(
                    revision = secondRevision,
                    originalImages =
                        first.originalImages.map { image ->
                            image.copy(
                                imageReferenceId = "${image.imageReferenceId}-revision-2",
                                localContentUri =
                                    "${image.localContentUri}-revision-2",
                                contentCanonicalFingerprint = "6".repeat(64),
                            )
                        },
                    committedAtEpochMillis = 250,
                ),
            )
            val pendingMessagesBefore = messages.readPending(nowEpochMillis = 1_000).size
            val command =
                RemoveUnreadyStudentReviewQueueItemCommand(
                    maintenanceId = "review-superseded-maintenance-1",
                    planId = queue.planId,
                    expectedPlanCanonicalFingerprint = queue.planCanonicalFingerprint,
                    queueItemId = QUEUE_ITEM_ID,
                    expectedProblemRevision = first.revision,
                    reason = StudentReviewQueueRemovalReason.SAVED_REVISION_INELIGIBLE,
                    changedAtEpochMillis = 300,
                )

            assertEquals(
                StudentReviewQueueMaintenanceResult.Applied,
                store.reviewSessionsForLearner(LEARNER_ID)
                    .removeUnreadyQueueItem(command),
            )
            assertEquals(
                StudentReviewQueueState.REMOVED,
                store.readReviewQueue(LEARNER_ID, LOCAL_DAY).single().state,
            )
            assertEquals(
                secondRevision,
                checkNotNull(store.findProblem(first.revision.problem)).revision,
            )
            assertEquals(
                pendingMessagesBefore,
                messages.readPending(nowEpochMillis = 1_000).size,
            )
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun verifiedResponseAtomicallyAdvancesAndActiveSessionSurvivesReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-review-advance.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        var store =
            RoomStudentMistakeStore(
                StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        try {
            val first = problemCommand(errorBookEntryId = "error-book-entry-1")
            val second =
                problemCommand(
                    problemId = "problem-2",
                    practiceUnitId = "practice-unit-2",
                    revisionId = "revision-2",
                    documentFingerprint = "2".repeat(64),
                    committedAtEpochMillis = 101,
                    errorBookEntryId = "error-book-entry-2",
                )
            listOf(first, second).forEachIndexed { index, problem ->
                store.commitProblem(problem)
                store.setCollectionState(
                    SetStudentProblemCollectionCommand(
                        problem = problem.revision.problem,
                        mistakeState = StudentMistakeEntryState.ACTIVE,
                        favorite = false,
                        changedAtEpochMillis = 110L + index,
                    ),
                )
                store.upsertReviewCandidate(
                    reviewCandidate(problem.revision)
                        .copy(
                            candidateId = "candidate-${index + 1}",
                            updatedAtEpochMillis = 210L + index,
                        ),
                )
                store.recordClassifications(
                    classificationCommand(
                        problemRevision = problem.revision,
                        results =
                            listOf(
                                knowledgeClassification(problem.revision).copy(
                                    classificationId =
                                        "classification-advance-${index + 1}",
                                    resultCanonicalFingerprint =
                                        (index + 3).toString().repeat(64),
                                    recordedAtEpochMillis = 200L + index,
                                ),
                            ),
                    ),
                )
            }
            assertEquals(
                AdmitStudentTrustedReviewAnswerRuleResult.Admitted,
                store.trustedReviewAnswerRuleAdmissions().admit(
                    trustedChoiceRuleCommand(
                        revision = first.revision,
                        errorBookEntryId =
                            checkNotNull(store.findProblem(first.revision.problem)?.errorBookEntryId),
                    ),
                ),
            )
            val queue =
                StoreStudentReviewQueueCommand(
                    planId = "plan-advance",
                    planCanonicalFingerprint = "7".repeat(64),
                    learnerId = LEARNER_ID,
                    localDayEpochDay = LOCAL_DAY,
                    timeZoneId = "Asia/Shanghai",
                    timeBudgetSeconds = 900,
                    generatedAtEpochMillis = 220,
                    plannerVersion = "planner-v1",
                    items =
                        listOf(first, second).mapIndexed { index, problem ->
                            StudentReviewQueueItem(
                                queueItemId = "queue-advance-${index + 1}",
                                problemRevision = problem.revision,
                                scheduledOrder = index,
                                estimatedDurationSeconds = 180,
                                reasonCodes = setOf("recent-mistake"),
                            )
                        },
                )
            store.storeReviewQueue(queue)
            val sessions = store.reviewSessionsForLearner(LEARNER_ID)
            val startCommand =
                StartStudentReviewSessionCommand(
                    sessionId = "session-advance",
                    planId = queue.planId,
                    expectedPlanCanonicalFingerprint =
                        queue.planCanonicalFingerprint,
                    startedAtEpochMillis = 230,
                )
            val startResult = sessions.startOrResume(startCommand)
            val started = (startResult as StartStudentReviewSessionResult.Ready).session
            assertEquals(
                started,
                (
                    sessions.startOrResume(startCommand)
                        as StartStudentReviewSessionResult.Ready
                ).session,
            )
            assertEquals(
                started,
                (
                    sessions.startOrResume(
                        startCommand.copy(sessionId = "session-resume-alias"),
                    ) as StartStudentReviewSessionResult.Ready
                ).session,
            )
            val queueBeforeRejectedMaintenance =
                store.readReviewQueue(LEARNER_ID, LOCAL_DAY)
            assertEquals(
                StudentReviewQueueMaintenanceResult.ReloadRequired,
                sessions.removeUnreadyQueueItem(
                    RemoveUnreadyStudentReviewQueueItemCommand(
                        maintenanceId = "maintenance-active-session",
                        planId = queue.planId,
                        expectedPlanCanonicalFingerprint = queue.planCanonicalFingerprint,
                        queueItemId = "queue-advance-2",
                        expectedProblemRevision = second.revision,
                        reason =
                            StudentReviewQueueRemovalReason.PENDING_KNOWLEDGE_ATTRIBUTION,
                        changedAtEpochMillis = 240,
                    ),
                ),
            )
            assertEquals(
                queueBeforeRejectedMaintenance,
                store.readReviewQueue(LEARNER_ID, LOCAL_DAY),
            )
            val answerOwner = store.trustedReviewAnswersForLearner(LEARNER_ID) { 500L }
            val lease = requireNotNull(answerOwner.issueCurrentLease())
            val recorded =
                answerOwner.submitResponse(
                    lease = lease,
                    response = StudentTrustedReviewResponse.Choice("C"),
                ) as StudentTrustedReviewSubmissionResult.Recorded
            assertTrue(!recorded.duplicate)
            val advanced = checkNotNull(sessions.readActiveSession())
            assertEquals(StudentReviewSessionStatus.ACTIVE, advanced.status)
            assertEquals(2L, advanced.sessionVersion)
            assertEquals("queue-advance-2", advanced.currentItem?.queueItemId)
            assertEquals(
                StudentTrustedReviewSubmissionResult.ReloadRequired,
                answerOwner.submitResponse(
                    lease = lease,
                    response = StudentTrustedReviewResponse.Choice("C"),
                ),
            )
            assertEquals(
                listOf(
                    StudentReviewQueueState.COMPLETED,
                    StudentReviewQueueState.PRESENTED,
                ),
                store.readReviewQueue(LEARNER_ID, LOCAL_DAY).map { it.state },
            )
            assertEquals(
                1,
                store.relayForLearner(LEARNER_ID)
                    .readPending(nowEpochMillis = Long.MAX_VALUE)
                    .count { it.envelope.payload is ReviewObservationCapturedV2 },
            )

            store.close()
            store =
                RoomStudentMistakeStore(
                    StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                    knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
                )
            val restored =
                checkNotNull(
                    store.reviewSessionsForLearner(LEARNER_ID).readActiveSession(),
                )
            assertEquals(advanced, restored)
        } finally {
            runCatching { store.close() }
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun reviewTransitionSkipsPendingKnowledgeAndAdvancesAtomically() = runBlocking {
        assertReviewTransitionSkipsUnreadyFuture(
            databaseName = "student-review-skip-pending.student-mistake-test.db",
            unreadyMode = UnreadyFutureMode.PENDING_KNOWLEDGE,
        )
    }

    @Test
    fun reviewTransitionSkipsSupersededRevisionAndAdvancesAtomically() = runBlocking {
        assertReviewTransitionSkipsUnreadyFuture(
            databaseName = "student-review-skip-superseded.student-mistake-test.db",
            unreadyMode = UnreadyFutureMode.SUPERSEDED_REVISION,
        )
    }

    @Test
    fun outboxDeliveryIsScopedBeforeLimitAndAcknowledgement() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DATABASE_NAME)
        val database =
            StudentMistakeStoreFactory.openDatabaseForTest(context, TEST_DATABASE_NAME)
        val store = RoomStudentMistakeStore(database)
        val messages = store.relayForLearner(LEARNER_ID)
        val otherLearnerMessages = store.relayForLearner("learner-2")
        try {
            val otherLearner =
                problemCommand(
                    learnerId = "learner-2",
                    problemId = "problem-2",
                    practiceUnitId = "practice-unit-2",
                    revisionId = "revision-2",
                    documentFingerprint = "2".repeat(64),
                    committedAtEpochMillis = 90,
                )
            val currentLearner = problemCommand(committedAtEpochMillis = 100)
            store.commitProblem(otherLearner)
            store.commitProblem(currentLearner)

            val currentPending =
                messages.readPending(
                    nowEpochMillis = 1_000,
                    limit = 1,
                )
            assertEquals(1, currentPending.size)
            assertEquals(
                LEARNER_ID,
                currentPending.single().envelope.aggregateLearnerId(),
            )
            assertTrue(
                runCatching {
                    otherLearnerMessages.markDelivered(
                        message = currentPending.single(),
                        deliveredAtEpochMillis = 1_000,
                    )
                }.isFailure,
            )
            messages.markDelivered(
                message = currentPending.single(),
                deliveredAtEpochMillis = 1_000,
            )
            assertTrue(
                messages.readPending(
                    nowEpochMillis = 1_000,
                ).isEmpty(),
            )
            assertEquals(
                1,
                otherLearnerMessages.readPending(
                    nowEpochMillis = 1_000,
                ).size,
            )
        } finally {
            store.close()
            context.deleteDatabase(TEST_DATABASE_NAME)
        }
    }

    @Test
    fun invalidOutboxProofIsRejectedWithoutStarvingFollowingAuthenticRows() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-outbox-poison-row.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val database = StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
        val store = RoomStudentMistakeStore(database)
        val messages = store.relayForLearner(LEARNER_ID)
        try {
            listOf(
                problemCommand(
                    problemId = "poison-problem-1",
                    practiceUnitId = "poison-practice-1",
                    revisionId = "poison-revision-1",
                    documentFingerprint = "1".repeat(64),
                    committedAtEpochMillis = 100,
                ),
                problemCommand(
                    problemId = "poison-problem-2",
                    practiceUnitId = "poison-practice-2",
                    revisionId = "poison-revision-2",
                    documentFingerprint = "2".repeat(64),
                    committedAtEpochMillis = 200,
                ),
                problemCommand(
                    problemId = "poison-problem-3",
                    practiceUnitId = "poison-practice-3",
                    revisionId = "poison-revision-3",
                    documentFingerprint = "3".repeat(64),
                    committedAtEpochMillis = 300,
                ),
            ).forEach { store.commitProblem(it) }

            val initiallyAuthentic = messages.readPending(Long.MAX_VALUE, limit = 3)
            assertEquals(3, initiallyAuthentic.size)
            val poisoned = initiallyAuthentic[1]
            val originalTag = poisoned.authenticityProof.tagHex
            val tamperedTag =
                if (originalTag == "0".repeat(64)) "1".repeat(64) else "0".repeat(64)
            context.openOrCreateDatabase(databaseName, 0, null).use { sql ->
                sql.execSQL("DROP TRIGGER immutable_student_outbox_proof_update")
                sql.execSQL(
                    "UPDATE student_store_outbox SET authenticity_tag_hex = ? WHERE event_id = ?",
                    arrayOf<Any?>(tamperedTag, poisoned.eventId),
                )
                fun proofSnapshot(): List<String?> =
                    sql.rawQuery(
                        """
                        SELECT authenticity_proof_protocol_version,
                               authenticity_algorithm_version,
                               authenticity_issuer_key_id,
                               authenticity_learner_id,
                               authenticity_envelope_fingerprint,
                               authenticity_tag_hex,
                               authenticity_relay_epoch
                        FROM student_store_outbox
                        WHERE event_id = ?
                        """.trimIndent(),
                        arrayOf(poisoned.eventId),
                    ).use { cursor ->
                        check(cursor.moveToFirst())
                        List(cursor.columnCount) { index ->
                            if (cursor.isNull(index)) null else cursor.getString(index)
                        }
                    }
                val poisonedProof = proofSnapshot()

                val readable = messages.readPending(Long.MAX_VALUE, limit = 3)

                assertEquals(
                    listOf(initiallyAuthentic[0].eventId, initiallyAuthentic[2].eventId),
                    readable.map { it.eventId },
                )
                assertEquals(poisonedProof, proofSnapshot())
                sql.rawQuery(
                    "SELECT delivery_state, delivered_at_epoch_millis " +
                        "FROM student_store_outbox WHERE event_id = ?",
                    arrayOf(poisoned.eventId),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("AUTHENTICITY_REJECTED", cursor.getString(0))
                    assertEquals(Long.MAX_VALUE, cursor.getLong(1))
                }
                assertTrue(
                    runCatching {
                        sql.execSQL(
                            "UPDATE student_store_outbox SET authenticity_tag_hex = ? WHERE event_id = ?",
                            arrayOf<Any?>(originalTag, poisoned.eventId),
                        )
                    }.isFailure,
                )
                assertTrue(
                    runCatching {
                        sql.execSQL(
                            "DELETE FROM student_store_outbox WHERE event_id = ?",
                            arrayOf<Any?>(poisoned.eventId),
                        )
                    }.isFailure,
                )
            }
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun transientKeyFailureLeavesAuthenticOutboxPendingForRetry() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-outbox-transient-key.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        val database = StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName)
        val store = RoomStudentMistakeStore(database)
        try {
            store.commitProblem(
                problemCommand(
                    problemId = "transient-key-problem",
                    practiceUnitId = "transient-key-practice",
                    revisionId = "transient-key-revision",
                    documentFingerprint = "4".repeat(64),
                    committedAtEpochMillis = 400,
                ),
            )
            val failingKeyStore =
                object : StudentOutboxHmacKeyStore() {
                    override fun contains(keyAlias: String): Boolean = true

                    override fun loadOrCreateBootstrap(keyAlias: String) = Unit

                    override fun issueHmac(
                        keyAlias: String,
                        authenticatedContextFingerprint: String,
                    ): String = throw SecurityException("test keystore temporarily unavailable")

                    override fun issueReviewResponseBinding(
                        keyAlias: String,
                        authenticatedBindingContextFingerprint: String,
                    ): String = throw SecurityException("test keystore temporarily unavailable")
                }
            val messages =
                store.relayForLearner(
                    LEARNER_ID,
                    StudentOutboxAuthenticatorSession(LEARNER_ID, failingKeyStore),
                )

            val failure =
                runCatching { messages.readPending(Long.MAX_VALUE, limit = 1) }.exceptionOrNull()

            assertTrue(failure is SecurityException)
            assertTrue(failure !is StudentOutboxInvalidProofException)
            context.openOrCreateDatabase(databaseName, 0, null).use { sql ->
                sql.rawQuery(
                    """
                    SELECT delivery_state, delivered_at_epoch_millis, authenticity_tag_hex
                    FROM student_store_outbox
                    WHERE learner_id = ?
                    """.trimIndent(),
                    arrayOf(LEARNER_ID),
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("PENDING", cursor.getString(0))
                    assertTrue(cursor.isNull(1))
                    assertTrue(cursor.getString(2).matches(Regex("[0-9a-f]{64}")))
                }
            }
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun pendingLegacyReviewV1IsRetiredAsArchiveAndNeverForwarded() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "student-review-v1-retirement.student-mistake-test.db"
        context.deleteDatabase(databaseName)
        StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName).let { database ->
            database.mistakeDao().readProblem("force-schema-open")
            database.close()
        }
        context.openOrCreateDatabase(databaseName, 0, null).use { legacyDatabase ->
            legacyDatabase.execSQL("DROP TRIGGER validate_student_authenticated_outbox_insert")
            legacyDatabase.execSQL(
                """
                INSERT INTO student_store_outbox(
                    event_id, source_store, destination_store, learner_id, aggregate_id,
                    aggregate_version, payload_type, payload_version,
                    payload_canonical_fingerprint, payload_wire,
                    envelope_canonical_fingerprint, occurred_at_epoch_millis, idempotency_key,
                    source_store_generation, delivery_state, delivery_attempt_count,
                    available_at_epoch_millis, delivered_at_epoch_millis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "archived-review-v1-event",
                    StudyStoreKind.STUDENT_MISTAKES.name,
                    StudyStoreKind.LEARNER_MASTERY.name,
                    LEARNER_ID,
                    "archived-review-v1-observation",
                    1L,
                    "review_observation_captured",
                    1,
                    "1".repeat(64),
                    "archive-only-wire-that-must-never-decode",
                    "2".repeat(64),
                    100L,
                    "archived-review-v1-idempotency",
                    "legacy-student-store-generation",
                    "PENDING",
                    0,
                    100L,
                    null,
                ),
            )
        }
        val store =
            RoomStudentMistakeStore(
                StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        try {
            assertTrue(store.relayForLearner(LEARNER_ID).readPending(Long.MAX_VALUE).isEmpty())
        } finally {
            store.close()
        }
        context.openOrCreateDatabase(databaseName, 0, null).use { archivedDatabase ->
            archivedDatabase.rawQuery(
                "SELECT delivery_state, delivered_at_epoch_millis FROM student_store_outbox " +
                    "WHERE event_id = ?",
                arrayOf("archived-review-v1-event"),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RETIRED_UNSAFE_LEGACY", cursor.getString(0))
                assertEquals(Long.MAX_VALUE, cursor.getLong(1))
            }
        }
        context.deleteDatabase(databaseName)
        Unit
    }

    private suspend fun assertReviewTransitionSkipsUnreadyFuture(
        databaseName: String,
        unreadyMode: UnreadyFutureMode,
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(databaseName)
        val store =
            RoomStudentMistakeStore(
                StudentMistakeStoreFactory.openDatabaseForTest(context, databaseName),
                knowledgeReferenceVerifier = knowledgeProofAuthority.verifier,
            )
        try {
            val problems =
                (1..3).map { ordinal ->
                    problemCommand(
                        problemId = "problem-skip-$ordinal",
                        practiceUnitId = "practice-skip-$ordinal",
                        revisionId = "revision-skip-$ordinal",
                        documentFingerprint = ordinal.toString().repeat(64),
                        committedAtEpochMillis = 100L + ordinal,
                        errorBookEntryId = "error-book-skip-$ordinal",
                    )
                }
            problems.forEachIndexed { index, problem ->
                store.commitProblem(problem)
                store.setCollectionState(
                    SetStudentProblemCollectionCommand(
                        problem = problem.revision.problem,
                        mistakeState = StudentMistakeEntryState.ACTIVE,
                        favorite = false,
                        changedAtEpochMillis = 120L + index,
                    ),
                )
                store.upsertReviewCandidate(
                    reviewCandidate(problem.revision).copy(
                        candidateId = "candidate-skip-${index + 1}",
                        updatedAtEpochMillis = 180L + index,
                    ),
                )
                if (index != 1 || unreadyMode == UnreadyFutureMode.SUPERSEDED_REVISION) {
                    store.recordClassifications(
                        classificationCommand(
                            problemRevision = problem.revision,
                            results =
                                listOf(
                                    knowledgeClassification(problem.revision).copy(
                                        classificationId =
                                            "classification-skip-${index + 1}",
                                        resultCanonicalFingerprint =
                                            (index + 4).toString().repeat(64),
                                        recordedAtEpochMillis = 160L + index,
                                    ),
                                ),
                        ),
                    )
                }
            }
            val queue =
                StoreStudentReviewQueueCommand(
                    planId = "plan-skip-${unreadyMode.name.lowercase()}",
                    planCanonicalFingerprint = "7".repeat(64),
                    learnerId = LEARNER_ID,
                    localDayEpochDay = LOCAL_DAY,
                    timeZoneId = "Asia/Shanghai",
                    timeBudgetSeconds = 900,
                    generatedAtEpochMillis = 200,
                    plannerVersion = "planner-v1",
                    items =
                        problems.mapIndexed { index, problem ->
                            StudentReviewQueueItem(
                                queueItemId = "queue-skip-${index + 1}",
                                problemRevision = problem.revision,
                                scheduledOrder = index,
                                estimatedDurationSeconds = 180,
                                reasonCodes = setOf("recent-mistake"),
                            )
                        },
                )
            store.storeReviewQueue(queue)
            assertEquals(
                AdmitStudentTrustedReviewAnswerRuleResult.Admitted,
                store.trustedReviewAnswerRuleAdmissions().admit(
                    trustedChoiceRuleCommand(
                        revision = problems.first().revision,
                        errorBookEntryId =
                            checkNotNull(
                                store.findProblem(problems.first().revision.problem)
                                    ?.errorBookEntryId,
                            ),
                    ),
                ),
            )
            val sessions = store.reviewSessionsForLearner(LEARNER_ID)
            val started =
                (
                    sessions.startOrResume(
                        StartStudentReviewSessionCommand(
                            sessionId = "session-skip-${unreadyMode.name.lowercase()}",
                            planId = queue.planId,
                            expectedPlanCanonicalFingerprint =
                                queue.planCanonicalFingerprint,
                            startedAtEpochMillis = 220,
                        ),
                    ) as StartStudentReviewSessionResult.Ready
                ).session
            if (unreadyMode == UnreadyFutureMode.SUPERSEDED_REVISION) {
                val queued = problems[1]
                store.commitProblem(
                    queued.copy(
                        revision =
                            queued.revision.copy(
                                revisionId = "revision-skip-2-current",
                                revisionNumber = 2,
                                documentCanonicalFingerprint = "9".repeat(64),
                            ),
                        originalImages =
                            queued.originalImages.map { image ->
                                image.copy(
                                    imageReferenceId = "${image.imageReferenceId}-current",
                                    localContentUri = "${image.localContentUri}-current",
                                    contentCanonicalFingerprint = "8".repeat(64),
                                )
                            },
                        committedAtEpochMillis = 240,
                    ),
                )
            }
            val answerOwner = store.trustedReviewAnswersForLearner(LEARNER_ID) { 300L }
            val lease = requireNotNull(answerOwner.issueCurrentLease())
            val recorded =
                answerOwner.submitResponse(
                    lease = lease,
                    response = StudentTrustedReviewResponse.Choice("C"),
                ) as StudentTrustedReviewSubmissionResult.Recorded
            assertTrue(!recorded.duplicate)
            val advanced = checkNotNull(sessions.readActiveSession())
            assertEquals("queue-skip-3", advanced.currentItem?.queueItemId)
            assertEquals(
                listOf(
                    StudentReviewQueueState.COMPLETED,
                    StudentReviewQueueState.REMOVED,
                    StudentReviewQueueState.PRESENTED,
                ),
                store.readReviewQueue(LEARNER_ID, LOCAL_DAY).map { it.state },
            )
            val removed = store.readReviewQueue(LEARNER_ID, LOCAL_DAY)[1]
            assertEquals(1, removed.reasonCodes.size)
            assertTrue(removed.reasonCodes.single().startsWith("transition-unready:"))
            val pendingResponsesBeforeReplay =
                store.relayForLearner(LEARNER_ID)
                    .readPending(nowEpochMillis = Long.MAX_VALUE)
                    .count { it.envelope.payload is ReviewObservationCapturedV2 }
            assertEquals(
                StudentTrustedReviewSubmissionResult.ReloadRequired,
                answerOwner.submitResponse(
                    lease = lease,
                    response = StudentTrustedReviewResponse.Choice("C"),
                ),
            )
            assertEquals(
                pendingResponsesBeforeReplay,
                store.relayForLearner(LEARNER_ID)
                    .readPending(nowEpochMillis = Long.MAX_VALUE)
                    .count { it.envelope.payload is ReviewObservationCapturedV2 },
            )
            assertEquals(1, pendingResponsesBeforeReplay)
        } finally {
            store.close()
            context.deleteDatabase(databaseName)
        }
    }

    private enum class UnreadyFutureMode {
        PENDING_KNOWLEDGE,
        SUPERSEDED_REVISION,
    }

    private fun problemCommand(
        learnerId: String = LEARNER_ID,
        problemId: String = "problem-1",
        practiceUnitId: String = "practice-unit-1",
        revisionId: String = "revision-1",
        revisionNumber: Int = 1,
        documentFingerprint: String = "a".repeat(64),
        imageReferenceId: String = "image-ref-$problemId",
        committedAtEpochMillis: Long = 100,
        errorBookEntryId: String? = null,
    ): CommitStudentProblemCommand =
        CommitStudentProblemCommand(
            revision =
                revision(
                    learnerId = learnerId,
                    problemId = problemId,
                    practiceUnitId = practiceUnitId,
                    revisionId = revisionId,
                    revisionNumber = revisionNumber,
                    documentFingerprint = documentFingerprint,
                ),
            title = "二次函数",
            stemMarkdown = "已知二次函数，求其顶点。",
            practiceUnitKind = StudentPracticeUnitKind.WHOLE_PROBLEM,
            practiceUnitTitle = "二次函数",
            itemFamilyId = "family-quadratic",
            estimatedDurationSeconds = 180,
            sourceBundleId = null,
            partIds = emptyList(),
            originalImages =
                listOf(
                    StudentProblemImageReference(
                        imageReferenceId = imageReferenceId,
                        localContentUri = "content://student-mistakes/$problemId/original-1",
                        contentCanonicalFingerprint = "b".repeat(64),
                        mediaType = "image/jpeg",
                        ordinal = 0,
                    ),
                ),
            committedAtEpochMillis = committedAtEpochMillis,
            errorBookEntryId = errorBookEntryId,
        )

    private fun knowledgeClassification(
        revision: StudentProblemRevisionRef,
    ): StudentProblemClassificationResult =
        StudentProblemClassificationResult(
            classificationId = "classification-1",
            problemRevision = revision,
            dimension = StudentProblemClassificationDimension.KNOWLEDGE,
            labelId = "math.function.quadratic",
            displayName = null,
            knowledgeNode =
                KnowledgeNodeRef(
                    subject = SubjectKind.MATH,
                    knowledgeNodeId = "math.function.quadratic",
                    taxonomyVersion = "taxonomy-v1",
                    knowledgePackVersion = "pack-v1",
                ),
            modelProviderId = "provider-test",
            modelId = "model-test",
            classifierVersion = "classifier-v1",
            resultCanonicalFingerprint = "c".repeat(64),
            status = StudentProblemClassificationStatus.ACCEPTED,
            recordedAtEpochMillis = 200,
        )

    private fun classificationCommand(
        problemRevision: StudentProblemRevisionRef,
        results: List<StudentProblemClassificationResult>,
    ): RecordStudentProblemClassificationsCommand =
        RecordStudentProblemClassificationsCommand(
            problemRevision = problemRevision,
            results = results,
            verifiedKnowledgeReferences =
                results
                    .filter {
                        it.dimension == StudentProblemClassificationDimension.KNOWLEDGE &&
                            (
                                it.status == StudentProblemClassificationStatus.ACCEPTED ||
                                    it.status == StudentProblemClassificationStatus.REVOKED
                            )
                    }
                    .associate { result ->
                        result.classificationId to
                            verifiedKnowledgeReference(checkNotNull(result.knowledgeNode))
                    },
        )

    private fun verifiedKnowledgeReference(
        ref: KnowledgeNodeRef,
    ): VerifiedKnowledgeReferenceProof =
        knowledgeProofAuthority.issuer.issue(
            ref,
            "8".repeat(64),
            1L,
        )

    private fun reviewCandidate(
        revision: StudentProblemRevisionRef,
    ): StudentReviewCandidate =
        StudentReviewCandidate(
            candidateId = "candidate-1",
            problemRevision = revision,
            reasonCodes = setOf("recent-mistake"),
            itemFamilyId = "family-quadratic",
            estimatedDurationSeconds = 180,
            availableAtEpochMillis = 210,
            dueAtEpochMillis = 1_000,
            sourceEvidence = null,
            candidateVersion = 1,
            updatedAtEpochMillis = 210,
        )

    private fun reviewQueue(
        revision: StudentProblemRevisionRef,
    ): StoreStudentReviewQueueCommand =
        StoreStudentReviewQueueCommand(
            planId = "plan-1",
            planCanonicalFingerprint = "d".repeat(64),
            learnerId = LEARNER_ID,
            localDayEpochDay = LOCAL_DAY,
            timeZoneId = "Asia/Shanghai",
            timeBudgetSeconds = 900,
            generatedAtEpochMillis = 220,
            plannerVersion = "planner-v1",
            items =
                listOf(
                    StudentReviewQueueItem(
                        queueItemId = QUEUE_ITEM_ID,
                        problemRevision = revision,
                        scheduledOrder = 0,
                        estimatedDurationSeconds = 180,
                        reasonCodes = setOf("recent-mistake"),
                    ),
                ),
        )

    private fun trustedChoiceRuleCommand(
        revision: StudentProblemRevisionRef,
        errorBookEntryId: String,
    ): AdmitStudentTrustedReviewAnswerRuleCommand =
        AdmitStudentTrustedReviewAnswerRuleCommand(
            answerRuleId = "trusted-choice:${revision.canonicalFingerprint}",
            problemRevision = revision,
            errorBookEntryId = errorBookEntryId,
            questionGeneration = 1,
            questionVersion = "choice-v1",
            answerRule =
                StudentTrustedReviewAnswerRule.Choice(
                    acceptedChoiceIds = setOf("A", "B", "C", "D"),
                    correctChoiceId = "C",
                    answerSpecVersion = "choice-v1",
                ),
            provenanceKind = StudentTrustedReviewAnswerProvenanceKind.DETERMINISTIC_VALIDATION,
            provenanceReferenceId = "fixture:${revision.revisionId}",
            provenanceCanonicalFingerprint = revision.canonicalFingerprint,
            admittedAtEpochMillis = 225,
        )

    private fun learningAttemptEnvelope(
        revision: StudentProblemRevisionRef,
    ): CrossStoreEventEnvelope {
        val evidence =
            LearningEvidenceRef(
                learnerId = LEARNER_ID,
                eventKind = "review_answer",
                eventId = "evidence-1",
                eventSequence = 1,
                eventCanonicalFingerprint = "e".repeat(64),
            )
        val payload =
            LearningAttemptRecordedV1(
                evidence = evidence,
                problemRevision = revision,
                reviewSessionId = "review-session-1",
                reviewQueueItemId = QUEUE_ITEM_ID,
                submissionId = "submission-1",
                presentationId = "presentation-1",
                recordedAtEpochMillis = 300,
            )
        return CrossStoreEventEnvelope(
            eventId = "mastery-event-1",
            sourceStore = StudyStoreKind.LEARNER_MASTERY,
            destinationStore = StudyStoreKind.STUDENT_MISTAKES,
            aggregateId = payload.aggregateId,
            aggregateVersion = 1,
            occurredAtEpochMillis = payload.occurredAtEpochMillis,
            idempotencyKey = "attempt-receipt-1",
            sourceStoreGeneration = "mastery-test-generation-v1",
            payload = payload,
        )
    }

    private fun revision(
        learnerId: String = LEARNER_ID,
        problemId: String = "problem-1",
        practiceUnitId: String = "practice-unit-1",
        revisionId: String = "revision-1",
        revisionNumber: Int = 1,
        documentFingerprint: String = "a".repeat(64),
    ): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = learnerId,
                    subject = SubjectKind.MATH,
                    problemId = problemId,
                    practiceUnitId = practiceUnitId,
                ),
            revisionId = revisionId,
            revisionNumber = revisionNumber,
            documentCanonicalFingerprint = documentFingerprint,
        )

    private fun CrossStoreEventEnvelope.aggregateLearnerId(): String =
        when (val event = payload) {
            is ProblemRevisionCommittedV1 -> event.revision.problem.learnerId
            is ProblemLifecycleChangedV1 -> event.problemRevision.problem.learnerId
            is ProblemRevisionSupersededV1 -> event.previousRevision.problem.learnerId
            is ProblemKnowledgeBindingsSnapshotV2 ->
                event.problemRevision.problem.learnerId

            is ReviewObservationCapturedV2 -> event.problemRevision.problem.learnerId

            else -> error("Unexpected student mistake outbox payload")
        }

    private val StudentOutboxDelivery.envelope: CrossStoreEventEnvelope
        get() =
            CrossStoreEventEnvelope(
                eventId = eventId,
                sourceStore = sourceStore,
                destinationStore = destinationStore,
                aggregateId = aggregateId,
                aggregateVersion = aggregateVersion,
                occurredAtEpochMillis = occurredAtEpochMillis,
                idempotencyKey = idempotencyKey,
                sourceStoreGeneration = sourceStoreGeneration,
                payload = payload,
                payloadType = payloadType,
                payloadVersion = payloadVersion,
                payloadCanonicalFingerprint = payloadCanonicalFingerprint,
            )

    private fun sourceIssuedMasteryMessage(
        envelope: CrossStoreEventEnvelope,
    ): VerifiedLearnerMasteryDelivery {
        val proof =
            MasteryOutboxAuthenticityProof(
                protocolVersion = MasteryOutboxAuthenticityProof.PROTOCOL_VERSION,
                algorithmVersion = MasteryOutboxAuthenticityProof.ALGORITHM_VERSION,
                issuerKeyId = "test-mastery-outbox-key",
                learnerId = LEARNER_ID,
                envelopeCanonicalFingerprint = envelope.canonicalFingerprint,
                tagHex = "a".repeat(64),
            )
        return VerifiedLearnerMasteryDelivery(
            envelope,
            LEARNER_ID,
            envelope.sourceStoreGeneration,
            "test-mastery-relay-epoch",
            proof.issuerKeyId,
            proof.algorithmVersion,
            proof.canonicalFingerprint,
            envelope.canonicalFingerprint,
            CanonicalSha256("learner-mastery-outbox-verification-receipt-v1")
                .field("learnerId", LEARNER_ID)
                .field("sourceStoreGeneration", envelope.sourceStoreGeneration)
                .field("relayEpoch", "test-mastery-relay-epoch")
                .field("issuerKeyId", proof.issuerKeyId)
                .field("algorithmVersion", proof.algorithmVersion)
                .field("envelopeCanonicalFingerprint", envelope.canonicalFingerprint)
                .field("proofCanonicalFingerprint", proof.canonicalFingerprint)
                .finish(),
        )
    }

    private companion object {
        const val TEST_DATABASE_NAME = "student-mistake-slice.student-mistake-test.db"
        const val LEARNER_ID = "learner-1"
        const val QUEUE_ITEM_ID = "queue-item-1"
        const val LOCAL_DAY = 20_000L
    }
}
