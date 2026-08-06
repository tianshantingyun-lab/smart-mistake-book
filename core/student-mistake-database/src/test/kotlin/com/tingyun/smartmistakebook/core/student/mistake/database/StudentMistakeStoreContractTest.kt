package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.CrossStoreEventPayload
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeCatalogActivatedV1
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.LearningAttemptRecordedV1
import com.tingyun.smartmistakebook.core.model.storage.LearningEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsAcceptedV1
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingsSnapshotV2
import com.tingyun.smartmistakebook.core.model.storage.ProblemRevisionCommittedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewObservationCapturedV1
import com.tingyun.smartmistakebook.core.model.storage.ReviewResponseForm
import com.tingyun.smartmistakebook.core.model.storage.ReviewVerificationOutcome
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMistakeStoreContractTest {
    @Test
    fun productionDatabaseNameIsFixed() {
        assertEquals("student-mistakes.db", STUDENT_MISTAKE_DATABASE_NAME)
    }

    @Test
    fun productionDatabaseCanOnlyBeOpenedThroughThePackagePrivateOwnerBoundary() {
        assertFalse(
            java.lang.reflect.Modifier.isPublic(
                StudentMistakeRoomDatabase::class.java.modifiers,
            ),
        )
        assertFalse(
            java.lang.reflect.Modifier.isPublic(
                StudentMistakeOwnedDatabase::class.java.modifiers,
            ),
        )
        assertFalse(
            java.lang.reflect.Modifier.isPublic(
                StudentMistakeDatabaseConfiguration::class.java.modifiers,
            ),
        )
        assertTrue(
            runCatching {
                Class.forName(
                    "com.tingyun.smartmistakebook.core.student.mistake.database." +
                        "StudentMistakeStoreFactory",
                )
            }.isFailure,
        )
        assertTrue(
            StudentMistakeRoomDatabase::class.java.declaredMethods.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertTrue(
            RoomStudentMistakeStore::class.java.declaredConstructors.all {
                it.parameterTypes.firstOrNull() == StudentMistakeRoomDatabase::class.java
            },
        )

        StudentMistakeOwnedDatabase::class.java.declaredMethods
            .filter { it.name == "openDatabase" || it.name == "openStore" }
            .also { methods ->
                assertEquals(
                    setOf("openDatabase", "openStore"),
                    methods.mapTo(mutableSetOf()) { it.name },
                )
            }
            .forEach { method ->
                assertFalse(java.lang.reflect.Modifier.isPublic(method.modifiers))
                assertEquals(StudentMistakeOwnerKey::class.java, method.parameterTypes.last())
            }

        val ownerAssemblyFacade =
            Class.forName(
                "com.tingyun.smartmistakebook.core.student.mistake.database." +
                    "StudentProblemIdentityEvidenceOwnerKt",
            )
        assertTrue(
            ownerAssemblyFacade.declaredMethods.none {
                it.name == "createStudentProblemIdentityEvidenceOwner"
            },
        )
        val ownerAssembly =
            ownerAssemblyFacade.declaredMethods
                .filter { it.name.startsWith("assembleStudentProblemIdentityEvidenceOwner") }
                .also { assertEquals(1, it.size) }
                .single()
        assertTrue(java.lang.reflect.Modifier.isPrivate(ownerAssembly.modifiers))
        assertEquals(StudentMistakeOwnerKey::class.java, ownerAssembly.parameterTypes.last())
        assertFalse(
            java.lang.reflect.Modifier.isPublic(
                StudentProblemIdentityEvidenceOwner::class.java.modifiers,
            ),
        )
        assertTrue(
            StudentProblemIdentityEvidenceOwner::class.java.declaredMethods.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertTrue(
            ownerAssemblyFacade.declaredMethods.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers) &&
                    it.parameterTypes.firstOrNull() == StudentProblemIdentityEvidenceOwner::class.java
            },
        )
    }

    @Test
    fun schemaOwnsOnlyMistakeBusinessAndMessageJournalTables() {
        assertEquals(
            setOf(
                "student_store_metadata",
                "student_problem_document",
                "student_problem_revision",
                "student_problem_canonical_identity",
                "student_problem_canonical_source_binding",
                "student_problem_error_occurrence",
                "student_problem_error_occurrence_evidence",
                "student_capture_occurrence_transaction",
                "student_practice_unit",
                "student_problem_import_semantic_snapshot",
                "student_problem_image_reference",
                "student_problem_search_document",
                "student_problem_search_fts",
                "student_problem_search_index_state",
                "student_problem_collection",
                "student_problem_classification_result",
                "student_problem_solution_analysis",
                "student_problem_solution_step",
                "student_problem_error_attribution",
                "student_problem_error_evidence",
                "student_problem_organization_receipt",
                "student_problem_organization_occurrence_binding",
                "student_problem_step_knowledge_binding",
                "student_problem_organization_facet",
                "student_review_candidate",
                "student_review_plan",
                "student_review_queue_item",
                "student_review_self_report_receipt",
                "student_review_session",
                "student_review_transition_receipt",
                "student_review_reveal_receipt",
                "student_trusted_review_answer_rule",
                "student_trusted_review_lease_receipt",
                "student_trusted_review_presentation_fence",
                "student_trusted_review_attempt_receipt",
                "student_trusted_review_assistance_receipt",
                "student_tutor_interaction_answer_certificate",
                "student_tutor_interaction_answer_certificate_status_event",
                "student_tutor_interaction_answer_certificate_lease_receipt",
                "student_tutor_interaction_answer_evaluation_receipt",
                "student_learner_change",
                "student_mistake_save_receipt",
                "student_mistake_migration_checkpoint",
                "student_mistake_migration_receipt",
                "student_mistake_migration_destination_record",
                "student_cutover_fence",
                "student_cutover_completion_receipt",
                "student_mistake_destination_attestation_invalidation",
                "student_mistake_destination_reattestation_receipt",
                "student_capture_save_handoff",
                "student_problem_identity_receipt",
                "student_outbox_authenticity_key_state",
                "student_mastery_relay_source_binding",
                "student_mastery_relay_reauthorization_case",
                "student_mastery_relay_reauthorization_resolution",
                "student_pre_auth_inbox_quarantine",
                "student_authenticated_mastery_inbox_receipt",
                "student_store_outbox",
                "student_store_inbox",
            ),
            STUDENT_MISTAKE_DOMAIN_TABLES,
        )
        val permittedMasteryRelayJournalTables =
            setOf(
                "student_mastery_relay_source_binding",
                "student_mastery_relay_reauthorization_case",
                "student_mastery_relay_reauthorization_resolution",
                "student_authenticated_mastery_inbox_receipt",
            )
        assertEquals(
            permittedMasteryRelayJournalTables,
            STUDENT_MISTAKE_DOMAIN_TABLES.filterTo(mutableSetOf()) { "mastery" in it },
        )
        val forbiddenTableFragments =
            setOf(
                "learning_observation",
                "learning_event",
                "projection",
                "knowledge_node",
                "knowledge_source",
                "knowledge_material",
                "knowledge_pack",
                "teaching_material",
            )
        STUDENT_MISTAKE_DOMAIN_TABLES.forEach { table ->
            forbiddenTableFragments.forEach { fragment ->
                assertTrue(
                    "Student mistake schema must not own '$fragment' through table '$table'",
                    fragment !in table,
                )
            }
        }
    }

    @Test
    fun publicPortExposesOnlyMistakeBusinessOperations() {
        assertEquals(
            setOf(
                "commitProblem",
                "findProblem",
                "observeChangeVersion",
                "readClassifications",
                "readCurrentClassifications",
                "readMistakeDetail",
                "readReviewCandidates",
                "readReviewCandidatesWithKnowledge",
                "readReviewPlanSnapshot",
                "readReviewQueue",
                "readRevisionHistory",
                "recordClassifications",
                "saveConfirmedMistake",
                "searchMistakes",
                "setCollectionState",
                "setProblemLifecycle",
                "storeReviewQueue",
                "upsertReviewCandidate",
            ),
            StudentMistakeStore::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val forbiddenApiFragments =
            setOf(
                "room",
                "dao",
                "sqlite",
                "sql",
                "raw",
                "attach",
                "mastery",
                "observation",
                "catalog",
            )
        listOf(
            StudentMistakeStore::class.java,
        ).forEach { port ->
            port.methods.forEach { method ->
                val signature =
                    buildString {
                        append(method.name)
                        append(method.returnType.name)
                        method.parameterTypes.forEach { append(it.name) }
                    }
                forbiddenApiFragments.forEach { fragment ->
                    assertTrue(
                        "Student mistake port leaks '$fragment' through ${method.name}",
                        !signature.contains(fragment, ignoreCase = true),
                    )
                }
            }
        }
    }

    @Test
    fun relayMutationRequiresValidatedMessagesAndOwnerIssuedCapability() {
        assertEquals(
            "com.tingyun.smartmistakebook.core.model.storage",
            StudentMistakeRelayMessage::class.java.packageName,
        )
        assertEquals(
            "com.tingyun.smartmistakebook.core.model.storage",
            LearnerMasteryRelayMessage::class.java.packageName,
        )
        assertTrue(
            StudentMistakeRelayMessage::class.java.constructors.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertTrue(
            LearnerMasteryRelayMessage::class.java.constructors.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertTrue(
            StudentMistakeRuntimeCapabilities::class.java.constructors.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        val factoryOpen =
            StudentMistakeRuntimeFactory::class.java.declaredMethods
                .single { it.name == "open" }
        assertEquals(StudentMistakeRuntimeCapabilities::class.java, factoryOpen.returnType)
        assertEquals(StudentMistakeOwnerKey::class.java, factoryOpen.parameterTypes.last())
        assertFalse(
            java.lang.reflect.Modifier.isPublic(
                StudentMistakeOwnerKey::class.java.modifiers,
            ),
        )
        assertTrue(
            StudentMistakeOwnerKey::class.java.declaredConstructors.all {
                java.lang.reflect.Modifier.isPrivate(it.modifiers)
            },
        )

        val relayMethods = StudentMistakeRelayCapability::class.java.declaredMethods
        val acknowledgement = relayMethods.single { it.name == "markDelivered" }
        val inbound = relayMethods.single { it.name == "acceptInbound" }
        assertEquals(
            VerifiedLearnerMasteryDelivery::class.java,
            inbound.parameterTypes.first(),
        )
        assertTrue(
            inbound.parameterTypes.none {
                it.name.endsWith(".CrossStoreEventEnvelope")
            },
        )
        assertEquals(StudentOutboxDelivery::class.java, acknowledgement.parameterTypes.first())
        assertTrue(
            relayMethods.flatMap { it.parameterTypes.asList() }.none {
                it == LearnerMasteryRelayMessage::class.java ||
                    it == StudentMistakeRelayMessage::class.java
            },
        )
        assertTrue(
            acknowledgement.parameterTypes.none {
                it.name.endsWith(".CrossStoreEventEnvelope")
            },
        )
        assertTrue(
            StudentMistakeStore::class.java.declaredMethods.none {
                it.name.contains("relay", ignoreCase = true) ||
                    it.name.contains("messagePump", ignoreCase = true)
            },
        )
    }

    @Test
    fun classificationSurfaceContainsOnlyCurriculumSectionsAndKnowledgeReferences() {
        assertEquals(
            setOf("CURRICULUM_SECTION", "KNOWLEDGE"),
            StudentProblemClassificationDimension.entries.mapTo(sortedSetOf()) { it.name },
        )
        val knowledge = knowledgeClassification()
        assertEquals(null, knowledge.displayName)
        assertEquals("math.function.quadratic", knowledge.knowledgeNode?.knowledgeNodeId)
        assertTrue(
            runCatching {
                RecordStudentProblemClassificationsCommand(
                    problemRevision = revision(),
                    results = listOf(knowledge),
                )
            }.isFailure,
        )
        val proofGetter =
            RecordStudentProblemClassificationsCommand::class.java.declaredMethods
                .single { it.name == "getVerifiedKnowledgeReferences" }
        assertTrue(
            proofGetter.genericReturnType.typeName.contains(
                VerifiedKnowledgeReferenceProof::class.java.name,
            ),
        )
        assertFalse(
            proofGetter.genericReturnType.typeName.contains("VerifiedKnowledgeNodeHandle") ||
                proofGetter.genericReturnType.typeName.contains("KnowledgeCatalogNode"),
        )
    }

    @Test
    fun classificationPersistenceDropsCatalogDisplayTextAndReadsOnlyAGenericSummary() {
        val submitted =
            StudentProblemClassificationResult(
                classificationId = "classification-section",
                problemRevision = revision(),
                dimension = StudentProblemClassificationDimension.CURRICULUM_SECTION,
                labelId = "math.function",
                displayName = "二次函数与图像",
                knowledgeNode = null,
                modelProviderId = "provider-test",
                modelId = "model-test",
                classifierVersion = "classifier-v1",
                resultCanonicalFingerprint = "d".repeat(64),
                status = StudentProblemClassificationStatus.ACCEPTED,
                recordedAtEpochMillis = 200,
            )

        val persisted = submitted.toEntity(verifiedKnowledgeReference = null)
        assertFalse(
            StudentProblemClassificationResultEntity::class.java.declaredFields.any {
                it.name.contains("display", ignoreCase = true)
            },
        )
        assertFalse(persisted.toString().contains(submitted.displayName.orEmpty()))

        val restored = persisted.toDomain(submitted.problemRevision)
        assertEquals(STUDENT_CLASSIFICATION_DISPLAY_PLACEHOLDER, restored.displayName)
        assertFalse(restored.displayName == submitted.displayName)
        assertEquals(submitted.labelId, restored.labelId)
    }

    @Test
    fun listPortsEnforceBoundedCursorPages() {
        assertTrue(
            runCatching {
                StudentMistakeSearchQuery(
                    learnerId = LEARNER_ID,
                    limit = MAX_PAGE_SIZE + 1,
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                StudentReviewCandidateQuery(
                    learnerId = LEARNER_ID,
                    nowEpochMillis = 0,
                    limit = MAX_PAGE_SIZE + 1,
                )
            }.isFailure,
        )
    }

    @Test
    fun fiveHundredImportedMistakesStillRequireAPlanWithinTheDailyTimeBudget() {
        val items =
            List(500) { index ->
                val problem =
                    StudentProblemRef(
                        learnerId = LEARNER_ID,
                        subject = SubjectKind.MATH,
                        problemId = "problem-$index",
                        practiceUnitId = "practice-unit-$index",
                    )
                StudentReviewQueueItem(
                    queueItemId = "queue-item-$index",
                    problemRevision =
                        StudentProblemRevisionRef(
                            problem = problem,
                            revisionId = "revision-$index",
                            revisionNumber = 1,
                            documentCanonicalFingerprint =
                                index.toString().padStart(64, '0'),
                        ),
                    scheduledOrder = index,
                    estimatedDurationSeconds = 2,
                    reasonCodes = setOf("selected-by-review-policy"),
                )
            }
        assertTrue(
            runCatching {
                StoreStudentReviewQueueCommand(
                    planId = "plan-over-budget",
                    planCanonicalFingerprint = "e".repeat(64),
                    learnerId = LEARNER_ID,
                    localDayEpochDay = 20_000,
                    timeZoneId = "Asia/Shanghai",
                    timeBudgetSeconds = 900,
                    generatedAtEpochMillis = 100,
                    plannerVersion = "planner-v1",
                    items = items,
                )
            }.isFailure,
        )
        val withinBudget =
            StoreStudentReviewQueueCommand(
                planId = "plan-within-budget",
                planCanonicalFingerprint = "f".repeat(64),
                learnerId = LEARNER_ID,
                localDayEpochDay = 20_000,
                timeZoneId = "Asia/Shanghai",
                timeBudgetSeconds = 900,
                generatedAtEpochMillis = 100,
                plannerVersion = "planner-v1",
                items = items.take(450),
            )
        assertEquals(450, withinBudget.items.size)
        assertEquals(900, withinBudget.items.sumOf { it.estimatedDurationSeconds })
        assertTrue(
            runCatching {
                withinBudget.copy(timeBudgetSeconds = 899)
            }.isFailure,
        )
    }

    @Test
    fun queueCompletionCannotBypassTheLearningAttemptInbox() {
        assertTrue(
            runCatching {
                TransitionStudentReviewQueueItemCommand(
                    learnerId = LEARNER_ID,
                    queueItemId = "queue-item-1",
                    expectedState = StudentReviewQueueState.PRESENTED,
                    nextState = StudentReviewQueueState.COMPLETED,
                    changedAtEpochMillis = 1,
                )
            }.isFailure,
        )
    }

    @Test
    fun selfReportCompletionCarriesAnIdempotentWholeProblemScheduleWithoutEvidence() {
        val command =
            RecordReviewSelfReportAndCompleteCommand(
                selfReportId = "self-report-1",
                learnerId = LEARNER_ID,
                queueItemId = "queue-item-1",
                report = StudentReviewSelfReportKind.STUCK,
                reportedAtEpochMillis = 100,
                nextAvailableAtEpochMillis = 200,
                nextDueAtEpochMillis = 300,
                schedulingPolicyVersion = "review-policy-v1",
            )
        assertEquals(command.canonicalFingerprint, command.canonicalFingerprint)
        assertTrue(
            RecordReviewSelfReportAndCompleteCommand::class.java.declaredFields.none {
                it.name.contains("evidence", ignoreCase = true) ||
                    it.type.name.contains("LearningEvidence", ignoreCase = true)
            },
        )
    }

    @Test
    fun databaseV18KeepsEveryExplicitMigration() {
        assertEquals(20, STUDENT_MISTAKE_DATABASE_VERSION)
        assertEquals(2, STUDENT_MISTAKE_MIGRATION_2_3.startVersion)
        assertEquals(3, STUDENT_MISTAKE_MIGRATION_2_3.endVersion)
        assertEquals(3, STUDENT_MISTAKE_MIGRATION_3_4.startVersion)
        assertEquals(4, STUDENT_MISTAKE_MIGRATION_3_4.endVersion)
        assertEquals(4, STUDENT_MISTAKE_MIGRATION_4_5.startVersion)
        assertEquals(5, STUDENT_MISTAKE_MIGRATION_4_5.endVersion)
        assertEquals(5, STUDENT_MISTAKE_MIGRATION_5_6.startVersion)
        assertEquals(6, STUDENT_MISTAKE_MIGRATION_5_6.endVersion)
        assertEquals(6, STUDENT_MISTAKE_MIGRATION_6_7.startVersion)
        assertEquals(7, STUDENT_MISTAKE_MIGRATION_6_7.endVersion)
        assertEquals(7, STUDENT_MISTAKE_MIGRATION_7_8.startVersion)
        assertEquals(8, STUDENT_MISTAKE_MIGRATION_7_8.endVersion)
        assertEquals(8, STUDENT_MISTAKE_MIGRATION_8_9.startVersion)
        assertEquals(9, STUDENT_MISTAKE_MIGRATION_8_9.endVersion)
        assertEquals(9, STUDENT_MISTAKE_MIGRATION_9_10.startVersion)
        assertEquals(10, STUDENT_MISTAKE_MIGRATION_9_10.endVersion)
        assertEquals(10, STUDENT_MISTAKE_MIGRATION_10_11.startVersion)
        assertEquals(11, STUDENT_MISTAKE_MIGRATION_10_11.endVersion)
        assertEquals(11, STUDENT_MISTAKE_MIGRATION_11_12.startVersion)
        assertEquals(12, STUDENT_MISTAKE_MIGRATION_11_12.endVersion)
        assertEquals(12, STUDENT_MISTAKE_MIGRATION_12_13.startVersion)
        assertEquals(13, STUDENT_MISTAKE_MIGRATION_12_13.endVersion)
        assertEquals(13, STUDENT_MISTAKE_MIGRATION_13_14.startVersion)
        assertEquals(14, STUDENT_MISTAKE_MIGRATION_13_14.endVersion)
        assertEquals(14, STUDENT_MISTAKE_MIGRATION_14_15.startVersion)
        assertEquals(15, STUDENT_MISTAKE_MIGRATION_14_15.endVersion)
        assertEquals(15, STUDENT_MISTAKE_MIGRATION_15_16.startVersion)
        assertEquals(16, STUDENT_MISTAKE_MIGRATION_15_16.endVersion)
        assertEquals(16, STUDENT_MISTAKE_MIGRATION_16_17.startVersion)
        assertEquals(17, STUDENT_MISTAKE_MIGRATION_16_17.endVersion)
        assertEquals(17, STUDENT_MISTAKE_MIGRATION_17_18.startVersion)
        assertEquals(18, STUDENT_MISTAKE_MIGRATION_17_18.endVersion)
        assertEquals(18, STUDENT_MISTAKE_MIGRATION_18_19.startVersion)
        assertEquals(19, STUDENT_MISTAKE_MIGRATION_18_19.endVersion)
        assertEquals(19, STUDENT_MISTAKE_MIGRATION_19_20.startVersion)
        assertEquals(20, STUDENT_MISTAKE_MIGRATION_19_20.endVersion)
        assertEquals(7, LIBRARY_READ_INDEXES.size)
    }

    @Test
    fun crossStoreWireRoundTripsStableReferencesWithoutCatalogContent() {
        payloadFixtures().forEach { payload ->
            val decoded =
                StudentMistakeCrossStoreCodec.decode(
                    payloadType = payload.payloadType,
                    payloadVersion = payload.payloadVersion,
                    wire = StudentMistakeCrossStoreCodec.encode(payload),
                )
            assertEquals(payload, decoded)
            assertEquals(payload.payloadCanonicalFingerprint, decoded.payloadCanonicalFingerprint)
        }
    }

    private fun payloadFixtures(): List<CrossStoreEventPayload> {
        val revision = revision()
        val evidence =
            LearningEvidenceRef(
                learnerId = LEARNER_ID,
                eventKind = "review_answer",
                eventId = "evidence-1",
                eventSequence = 7,
                eventCanonicalFingerprint = "e".repeat(64),
            )
        val node =
            KnowledgeNodeRef(
                subject = SubjectKind.MATH,
                knowledgeNodeId = "math.function.quadratic",
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )
        return listOf(
            ProblemRevisionCommittedV1(
                revision = revision,
                commitReceiptId = "receipt-1",
                commitReceiptCanonicalFingerprint = "b".repeat(64),
                committedAtEpochMillis = 100,
            ),
            ProblemKnowledgeBindingsAcceptedV1(
                problemRevision = revision,
                bindings =
                    listOf(
                        ProblemKnowledgeBindingRef(
                            bindingId = "binding-1",
                            problemRevision = revision,
                            knowledgeNode = node,
                            bindingCanonicalFingerprint = "c".repeat(64),
                        ),
                    ),
                acceptedAtEpochMillis = 200,
            ),
            ProblemKnowledgeBindingsSnapshotV2(
                problemRevision = revision,
                bindings = emptyList(),
                bindingSetVersion = 2,
                changedAtEpochMillis = 250,
            ),
            ReviewObservationCapturedV1(
                problemRevision = revision,
                reviewSessionId = "review-session-1",
                reviewQueueItemId = "queue-item-1",
                observationId = "observation-1",
                submissionId = "submission-1",
                presentationId = "presentation-1",
                responseForm = ReviewResponseForm.VISUAL_TARGET,
                responseCanonicalFingerprint = "9".repeat(64),
                verificationOutcome = ReviewVerificationOutcome.INCORRECT,
                attemptOrdinal = 1,
                hintCount = 0,
                answerWasRevealed = false,
                verificationPolicyVersion = "verification-v1",
                elapsedDurationMillis = 1_500,
                capturedAtEpochMillis = 275,
            ),
            LearningAttemptRecordedV1(
                evidence = evidence,
                problemRevision = revision,
                reviewSessionId = "review-session-1",
                reviewQueueItemId = "queue-item-1",
                submissionId = "submission-1",
                presentationId = "presentation-1",
                recordedAtEpochMillis = 300,
            ),
            KnowledgeCatalogActivatedV1(
                knowledgePackVersion = "pack-v1",
                taxonomyVersion = "taxonomy-v1",
                manifestCanonicalFingerprint = "d".repeat(64),
                activatedAtEpochMillis = 400,
            ),
        )
    }

    private fun revision(): StudentProblemRevisionRef =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = LEARNER_ID,
                    subject = SubjectKind.MATH,
                    problemId = "problem-1",
                    practiceUnitId = "practice-unit-1",
                ),
            revisionId = "revision-1",
            revisionNumber = 1,
            documentCanonicalFingerprint = "a".repeat(64),
        )

    private fun knowledgeClassification(): StudentProblemClassificationResult =
        StudentProblemClassificationResult(
            classificationId = "classification-1",
            problemRevision = revision(),
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

    private companion object {
        const val LEARNER_ID = "learner-1"
    }
}
