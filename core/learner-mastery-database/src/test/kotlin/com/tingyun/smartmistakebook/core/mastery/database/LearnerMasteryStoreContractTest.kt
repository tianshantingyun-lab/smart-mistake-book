package com.tingyun.smartmistakebook.core.mastery.database

import com.tingyun.smartmistakebook.core.model.storage.VerifiedKnowledgeReferenceProof
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef
import com.tingyun.smartmistakebook.core.model.storage.LearnerMasteryRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.ProblemKnowledgeBindingRef
import com.tingyun.smartmistakebook.core.model.storage.StudentMistakeRelayMessage
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LearnerMasteryStoreContractTest {
    @Test
    fun directionalBudgetGenerationEpochUsesASeparateCurrentNamespace() {
        assertEquals(
            "presentation_fingerprint_budget_generation_epoch_v2:7",
            directionalBudgetGenerationEpochMetadataKey(7L),
        )
        assertEquals(
            "presentation_fingerprint_budget_generation_epoch_v1:7",
            presentationFingerprintBudgetGenerationEpochMetadataKey(7L),
        )
        assertNotEquals(
            directionalBudgetGenerationEpochMetadataKey(7L),
            presentationFingerprintBudgetGenerationEpochMetadataKey(7L),
        )
    }

    @Test
    fun productionDatabaseNameAndTableSetAreFixed() {
        assertEquals("learner-mastery.db", LEARNER_MASTERY_DATABASE_NAME)
        assertEquals(
            setOf(
                "mastery_source_fact",
                "mastery_source_proof",
                "mastery_problem_binding_authority",
                "mastery_problem_binding_authority_state",
                "mastery_observation_candidate",
                "mastery_candidate_attribution",
                "mastery_admission_receipt",
                "mastery_model_submission_attempt_receipt",
                LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
                LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
                LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
                LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
                LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE,
                "mastery_evidence_review_case",
                "mastery_evidence_review_resolution",
                LEARNER_MASTERY_LEGACY_REVIEW_RESOLUTION_AUDIT_TABLE,
                "mastery_calibration_snapshot",
                "mastery_learning_event",
                "mastery_learning_evidence_supersession",
                "mastery_learning_event_attribution",
                "mastery_applied_event",
                "mastery_knowledge_projection",
                "mastery_subject_digest",
                "mastery_presentation_node_budget",
                "mastery_problem_family_node_budget",
                "mastery_projection_generation",
                "mastery_projection_shadow",
                "mastery_subject_digest_shadow",
                "mastery_presentation_node_budget_shadow",
                "mastery_problem_family_node_budget_shadow",
                LEARNER_MASTERY_PROJECTION_BUDGET_REBUILD_RECEIPT_TABLE,
                LEARNER_MASTERY_DIRECTIONAL_BUDGET_MIGRATION_RECEIPT_TABLE,
                LEARNER_MASTERY_PROJECTION_INPUT_FACT_TABLE,
                LEARNER_MASTERY_PROJECTION_INPUT_COVERAGE_GAP_TABLE,
                LEARNER_MASTERY_CALIBRATION_RELEASE_TABLE,
                LEARNER_MASTERY_CALIBRATION_PROFILE_HEADER_TABLE,
                LEARNER_MASTERY_CALIBRATION_VALIDATION_METRIC_TABLE,
                "mastery_cross_store_inbox",
                "mastery_cross_store_outbox",
                "mastery_taxonomy_lineage_decision",
                "mastery_store_metadata",
                "mastery_ledger_sequence",
                "mastery_legacy_fact_migration_checkpoint",
                "mastery_cutover_fence",
                "mastery_cutover_completion_receipt",
                "mastery_legacy_fact_migration_destination_record",
                "mastery_legacy_observation_snapshot_page",
                "mastery_legacy_observation_snapshot",
            ),
            LEARNER_MASTERY_TABLE_NAMES,
        )
        assertTrue(
            LEARNER_MASTERY_TABLE_NAMES.none { tableName ->
                listOf("image", "favorite", "review_queue", "knowledge_content", "mistake")
                    .any { forbidden -> forbidden in tableName }
            },
        )
    }

    @Test
    fun modelRuntimeAbiExposesOnlyScopedCandidateAndDigestCapabilities() {
        assertEquals(
            setOf("submitCandidate"),
            LearnerMasteryCandidateSink::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertEquals(
            setOf("queryDigest"),
            SubjectMasteryDigestReader::class.java.declaredMethods
                .filterNot { it.isSynthetic || it.name.endsWith("\$default") }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertEquals(
            setOf("getKnowledgeNode", "getState", "getTrend"),
            ModelKnowledgeMasteryDigestItem::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertEquals(
            setOf("getItems", "getSubject"),
            ModelSubjectMasteryDigest::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertEquals(
            setOf("getDisposition", "getReceiptFingerprint"),
            ModelLearningObservationResult::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertTrue(
            LearnerMasteryModelAccess::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertTrue(
            LearnerMasteryModelAccessLease::class.java.declaredConstructors
                .filterNot { it.isSynthetic }
                .none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertEquals(
            setOf("invalidateCurrentLease", "openLease"),
            LearnerMasteryModelAccessProvider::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val openLease =
            LearnerMasteryModelAccessProvider::class.java.declaredMethods
                .single { it.name == "openLease" }
        assertEquals(
            listOf(LearnerMasteryModelRequestScope::class.java),
            openLease.parameterTypes.toList(),
        )
        assertEquals(LearnerMasteryModelAccessLease::class.java, openLease.returnType)
        assertEquals(
            setOf("close", "getAccess"),
            LearnerMasteryModelAccessLease::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        val forbidden =
            listOf(
                "ingestSourceFact",
                "timeline",
                "commit",
                "dao",
                "sql",
                "database",
                "snapshot",
                "raw",
                "weight",
                "confidence",
                "quality",
                "count",
                "time",
                "stability",
                "recall",
                "correct",
                "supersed",
            )
        (
            LearnerMasteryCandidateSink::class.java.methods +
                SubjectMasteryDigestReader::class.java.methods +
                LearnerMasteryModelAccess::class.java.methods +
                ModelKnowledgeMasteryDigestItem::class.java.methods +
                ModelSubjectMasteryDigest::class.java.methods +
                ModelLearningObservationResult::class.java.methods
            ).forEach { method ->
            forbidden.forEach { fragment ->
                assertFalse(
                    "Model mastery ABI unexpectedly exposes ${method.name}",
                    method.name.contains(fragment, ignoreCase = true),
                )
            }
        }
        val modelAccessMethods =
            LearnerMasteryModelAccess::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName)
        assertEquals(setOf("getCandidateSink", "getDigestReader"), modelAccessMethods)
        listOf("close", "revoke", "renew", "request", "mode", "current").forEach { forbidden ->
            assertTrue(
                LearnerMasteryModelAccess::class.java.methods.none {
                    it.name.contains(forbidden, ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun productionRuntimeExposesOnlyIndependentLearnerBoundCapabilities() {
        assertEquals(
            setOf(
                "getLearnerId",
                "getDisplayReader",
                "getEraseCapability",
                "getEvidenceCorrectionCapability",
                "getEvidenceReviewCapability",
                "getKnowledgeEvidenceAuthorizer",
                "getLocalContextReader",
                "getObservationSink",
                "getPendingOpenResponseSink",
                "getReader",
            ),
            LearnerMasteryRuntimeCapabilities::class.java.declaredMethods
                .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
                .filterNot { it.isSynthetic || it.name.endsWith("\$default") }
                .filterNot { it.name == "close" }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertTrue(
            LearnerMasteryRuntimeCapabilities::class.java.constructors.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        val hostHandoff =
            LearnerMasteryRuntimeCapabilities::class.java.declaredMethods
                .single { it.name == "modelHostHandoff" }
        assertFalse(java.lang.reflect.Modifier.isPublic(hostHandoff.modifiers))
        assertEquals(LearnerMasteryModelAccessProvider::class.java, hostHandoff.returnType)
        val relayHandoff =
            LearnerMasteryRuntimeCapabilities::class.java.declaredMethods
                .single { it.name == "relayHandoff" }
        assertFalse(java.lang.reflect.Modifier.isPublic(relayHandoff.modifiers))
        assertEquals(LearnerMasteryRelayCapability::class.java, relayHandoff.returnType)
        assertTrue(
            LearnerMasteryRuntimeCapabilities::class.java.methods.none { method ->
                method.returnType == LearnerMasteryModelAccessProvider::class.java
            },
        )
        val factoryOpen =
            LearnerMasteryRuntimeFactory::class.java.declaredMethods
                .single { it.name == "open" }
        assertEquals(
            LearnerMasteryRuntimeCapabilities::class.java,
            factoryOpen.returnType,
        )
        assertEquals(LearnerMasteryOwnerKey::class.java, factoryOpen.parameterTypes[3])
        assertEquals(Any::class.java, factoryOpen.parameterTypes[4])
        assertFalse(
            java.lang.reflect.Modifier.isPublic(
                LearnerMasteryOwnerKey::class.java.modifiers,
            ),
        )
        assertTrue(
            LearnerMasteryOwnerKey::class.java.declaredConstructors.all {
                java.lang.reflect.Modifier.isPrivate(it.modifiers)
            },
        )
        val exposedMethods =
            LearnerMasteryObservationSink::class.java.methods +
                LearnerMasteryReader::class.java.methods +
                LearnerMasteryDisplayReader::class.java.methods +
                LearnerMasteryEvidenceCorrectionCapability::class.java.methods +
                LearnerMasteryEraseCapability::class.java.methods +
                LearnerMasteryKnowledgeEvidenceAuthorizer::class.java.methods +
                LearnerMasteryModelAccessProvider::class.java.methods +
                LearnerMasteryPendingOpenResponseSink::class.java.methods +
                LearnerMasteryRuntimeCapabilities::class.java.methods
        exposedMethods.forEach { method ->
            val exposedTypes = method.parameterTypes.toList() + method.returnType
            assertTrue(
                "Production mastery ABI exposes an arbitrary envelope",
                exposedTypes.none {
                    it.name.endsWith(".CrossStoreEventEnvelope")
                },
            )
            listOf("dao", "sql", "raw", "acceptEnvelope", "ingestEnvelope").forEach {
                forbidden ->
                assertFalse(
                    "Production mastery ABI unexpectedly exposes ${method.name}",
                    method.name.contains(forbidden, ignoreCase = true),
                )
            }
        }
        val knowledgeAuthorization =
            LearnerMasteryKnowledgeEvidenceAuthorizer::class.java.declaredMethods
                .single { it.name == "authorize" }
        assertEquals(
            listOf(VerifiedKnowledgeReferenceProof::class.java),
            knowledgeAuthorization.parameterTypes.toList(),
        )
        assertTrue(
            knowledgeAuthorization.parameterTypes.none {
                it.name.contains("VerifiedKnowledgeNodeHandle") ||
                    it.name.contains("KnowledgeCatalogNode")
            },
        )
    }

    @Test
    fun trustedPolicyAndRelayCapabilityKeepUnforgeableAuthorityTokens() {
        assertEquals(
            LearningObservationFacts::class.java,
            RecordTrustedLearningObservationCommand::class.java,
        )
        assertTrue(
            AuthorityIssuedLearningObservation::class.java.constructors.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertTrue(
            LearnerMasteryRelayMessage::class.java.constructors.none {
                java.lang.reflect.Modifier.isPublic(it.modifiers)
            },
        )
        assertEquals(
            "com.tingyun.smartmistakebook.core.model.storage",
            StudentMistakeRelayMessage::class.java.packageName,
        )
        assertEquals(
            "com.tingyun.smartmistakebook.core.model.storage",
            LearnerMasteryRelayMessage::class.java.packageName,
        )
        val accept =
            LearnerMasteryRelayCapability::class.java.declaredMethods
                .single { it.name == "accept" }
        assertTrue(
            accept.parameterTypes.first().name.endsWith(".VerifiedStudentMistakeDelivery"),
        )
        val acknowledgement =
            LearnerMasteryRelayCapability::class.java.declaredMethods
                .single { it.name == "markDelivered" }
        assertEquals(LearnerMasteryOutboxDelivery::class.java, acknowledgement.parameterTypes.first())
        assertTrue(
            (accept.parameterTypes + acknowledgement.parameterTypes).none {
                it.name.endsWith(".CrossStoreEventEnvelope")
            },
        )
    }

    @Test
    fun modelCandidateCannotSubmitNumericWeightOrMasteryValue() {
        val exposedFieldNames =
            (
                SubmitLearningObservationCandidateCommand::class.java.declaredFields +
                    ProposedKnowledgeAttribution::class.java.declaredFields
                ).map { it.name.lowercase() }
        listOf(
            "weight",
            "direction",
            "mass",
            "score",
            "posterior",
            "stability",
            "masteryvalue",
        ).forEach { forbidden ->
            assertTrue(
                "Model candidate unexpectedly controls '$forbidden'",
                exposedFieldNames.none { forbidden in it },
            )
        }
        listOf(
            "sourcefactid",
            "learnerid",
            "subject",
            "candidateid",
            "proposedatepochmillis",
            "modelversion",
            "idempotencykey",
            "requestedpolicyversion",
        ).forEach { locallyOwned ->
            assertTrue(
                "Model candidate unexpectedly controls '$locallyOwned'",
                exposedFieldNames.none { locallyOwned in it },
            )
        }
    }

    @Test
    fun modelSubmissionAttemptReceiptStoresOnlyFingerprintsAndHostBoundScope() {
        val fieldNames =
            MasteryModelSubmissionAttemptReceiptEntity::class.java.declaredFields
                .map { it.name.lowercase() }

        listOf(
            "prompt",
            "answer",
            "response",
            "message",
            "markdown",
            "content",
            "raw",
        ).forEach { forbidden ->
            assertTrue(
                "Model attempt receipt persists forbidden '$forbidden' content",
                fieldNames.none { forbidden in it },
            )
        }
        listOf(
            "requestgenerationfingerprint",
            "proposalfingerprint",
            "sourcefactid",
            "requestversion",
            "modeversion",
            "terminalreason",
            "receiptfingerprint",
        ).forEach { required ->
            assertTrue(
                "Model attempt receipt is missing '$required'",
                fieldNames.any { required in it },
            )
        }
    }

    @Test
    fun trustedObservationExposesFactsButNotPolicyOwnedValues() {
        val fields =
            RecordTrustedLearningObservationCommand::class.java.declaredFields
                .map { it.name.lowercase() }
        listOf(
            "authority",
            "outcome",
            "weight",
            "mass",
            "masteryscore",
            "projection",
            "policyversion",
        ).forEach { forbidden ->
            assertTrue(
                "Trusted fact command exposes policy-owned '$forbidden'",
                fields.none { forbidden in it },
            )
        }
        listOf(
            "answerwascorrect",
            "hintcount",
            "answerrevealed",
            "retrycount",
            "elapseddurationmillis",
            "independentlyanswered",
            "presentationfingerprint",
            "responseform",
            "verification",
        ).forEach { requiredFact ->
            assertTrue(
                "Trusted fact command is missing '$requiredFact'",
                fields.any { requiredFact in it },
            )
        }
    }

    @Test
    fun ephemeralEvidenceIsOpaqueAndCannotCarryProblemOrChatContent() {
        assertTrue(
            VerifiedEphemeralKnowledgeEvidence::class.java.constructors.none { constructor ->
                java.lang.reflect.Modifier.isPublic(constructor.modifiers) &&
                    !constructor.isSynthetic
            },
        )
        val contextFields =
            EphemeralTutorProblemLearningContext::class.java.declaredFields
                .map { it.name.lowercase() }
        listOf("markdown", "chat", "message", "questiontext", "body", "content").forEach {
            forbidden ->
            assertTrue(
                "Ephemeral tutor context persists forbidden '$forbidden' content",
                contextFields.none { forbidden in it },
            )
        }
        assertTrue(contextFields.any { "problemfingerprint" in it })
        assertTrue(contextFields.any { "submissionevidencefingerprint" in it })
        assertTrue(contextFields.any { "attributionmodelversion" in it })
    }

    @Test
    fun terminalObservationReceiptCannotBeCallerConstructed() {
        assertTrue(
            TrustedLearningObservationTerminalReceipt::class.java.declaredConstructors
                .none { constructor ->
                    java.lang.reflect.Modifier.isPublic(constructor.modifiers) &&
                        !constructor.isSynthetic
                },
        )
    }

    @Test
    fun modelCandidateRequiresBindingAndDefensivelyCopiesAttributions() {
        val attribution = boundAttribution()
        val mutableAttributions = mutableListOf(attribution)
        val command =
            SubmitLearningObservationCandidateCommand(
                proposedAttributions = mutableAttributions,
                confidence = MasteryCandidateConfidence.HIGH,
            )
        mutableAttributions.clear()

        assertEquals(listOf(attribution), command.proposedAttributions)
        assertTrue(
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (command.proposedAttributions as MutableList<ProposedKnowledgeAttribution>)
                    .clear()
            }.exceptionOrNull() is UnsupportedOperationException,
        )

        val rejected =
            runCatching {
                SubmitLearningObservationCandidateCommand(
                    proposedAttributions = listOf(attribution.copy(problemBinding = null)),
                    confidence = MasteryCandidateConfidence.HIGH,
                )
            }
        assertTrue(rejected.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun localScopeOwnsCandidateIdentityTimeAndModelMetadata() {
        val command =
            SubmitLearningObservationCandidateCommand(
                proposedAttributions = listOf(boundAttribution()),
                confidence = MasteryCandidateConfidence.MEDIUM,
            )
        val first =
            prepareBoundModelCandidate(
                learnerId = "learner-local",
                subject = SubjectKind.MATH,
                sourceFactId = "source-local-metadata",
                modelVersion = "model-gateway-v1",
                command = command,
                proposedAtEpochMillis = 10L,
            )
        val retried =
            prepareBoundModelCandidate(
                learnerId = "learner-local",
                subject = SubjectKind.MATH,
                sourceFactId = "source-local-metadata",
                modelVersion = "model-gateway-v1",
                command = command,
                proposedAtEpochMillis = 20L,
            )
        val anotherFact =
            prepareBoundModelCandidate(
                learnerId = "learner-local",
                subject = SubjectKind.MATH,
                sourceFactId = "source-local-metadata-2",
                modelVersion = "model-gateway-v1",
                command = command,
                proposedAtEpochMillis = 20L,
            )

        assertEquals("learner-local", first.learnerId)
        assertEquals(SubjectKind.MATH, first.subject)
        assertEquals("source-local-metadata", first.sourceFactId)
        assertEquals("model-gateway-v1", first.modelVersion)
        assertEquals(10L, first.proposedAtEpochMillis)
        assertEquals(20L, retried.proposedAtEpochMillis)
        assertEquals(first.candidateId, retried.candidateId)
        assertEquals(first.idempotencyKey, retried.idempotencyKey)
        assertEquals(first.canonicalFingerprint, retried.canonicalFingerprint)
        assertNotEquals(first.candidateId, anotherFact.candidateId)
        assertNotEquals(first.idempotencyKey, anotherFact.idempotencyKey)
        assertEquals("source-local-metadata-2", anotherFact.sourceFactId)
    }

    @Test
    fun projectionIdentityIgnoresPackVersionButNeverTaxonomyVersion() {
        val packOne =
            nodeRef(
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v1",
            )
        val packTwo =
            nodeRef(
                taxonomyVersion = "taxonomy-v1",
                knowledgePackVersion = "pack-v2",
            )
        val newTaxonomy =
            nodeRef(
                taxonomyVersion = "taxonomy-v2",
                knowledgePackVersion = "pack-v2",
            )

        val packOneIdentity = packOne.projectionIdentity()
        val packTwoIdentity = packTwo.projectionIdentity()
        val newTaxonomyIdentity = newTaxonomy.projectionIdentity()

        assertEquals(packOneIdentity, packTwoIdentity)
        assertNotEquals(packOne.canonicalFingerprint, packTwo.canonicalFingerprint)
        assertNotEquals(packOneIdentity, newTaxonomyIdentity)

        assertEquals(
            setOf("learner_id", "subject", "knowledge_node_id", "taxonomy_version"),
            LEARNER_MASTERY_PROJECTION_IDENTITY_COLUMNS,
        )
        assertFalse(
            "knowledge_pack_version" in LEARNER_MASTERY_PROJECTION_IDENTITY_COLUMNS,
        )
        assertTrue("mastery_taxonomy_lineage_decision" in LEARNER_MASTERY_TABLE_NAMES)
    }

    @Test
    fun behaviorProofAdmissionAndApplicationTimesRemainSeparate() {
        assertTrue(
            "occurredAtEpochMillis" in
                MasterySourceFactEntity::class.java.declaredFields.map { it.name },
        )
        assertTrue(
            "attestedAtEpochMillis" in
                MasterySourceFactEntity::class.java.declaredFields.map { it.name },
        )
        assertTrue(
            "receivedAtEpochMillis" in
                MasterySourceFactEntity::class.java.declaredFields.map { it.name },
        )
        assertTrue(
            "proposedAtEpochMillis" in
                MasteryObservationCandidateEntity::class.java.declaredFields.map { it.name },
        )
        assertTrue(
            "admittedAtEpochMillis" in
                MasteryLearningEventEntity::class.java.declaredFields.map { it.name },
        )
        assertTrue(
            "appliedAtEpochMillis" in
                MasteryAppliedEventEntity::class.java.declaredFields.map { it.name },
        )
    }

    @Test
    fun correctionCapabilityCannotAcceptModelOwnedMasteryNumbers() {
        val correctionFields =
            CorrectLearningEvidenceCommand::class.java.declaredFields
                .map { it.name.lowercase() }
        listOf("direction", "mass", "mastery", "score", "weight").forEach { forbidden ->
            assertTrue(correctionFields.none { forbidden in it })
        }
        assertEquals(
            setOf("correct"),
            LearnerMasteryEvidenceCorrectionCapability::class.java.declaredMethods
                .filterNot { it.isSynthetic }
                .mapTo(sortedSetOf(), java.lang.reflect.Method::getName),
        )
        assertTrue(
            LearnerMasteryModelAccess::class.java.methods.none {
                it.name.contains("correct", ignoreCase = true) ||
                    it.name.contains("supersed", ignoreCase = true)
            },
        )
    }

    @Test
    fun bindingSnapshotOrderingIsScopedToOneStudentStoreGeneration() {
        val current =
            bindingState(
                generation = "student-generation-old",
                version = 100L,
                payloadFingerprint = "1".repeat(64),
            )

        assertEquals(
            MasteryInboundDisposition.CONFLICT,
            bindingAuthorityStateDisposition(
                current = current,
                incoming =
                    bindingState(
                        generation = current.sourceStoreGeneration,
                        version = current.bindingSetVersion,
                        payloadFingerprint = "2".repeat(64),
                    ),
            ),
        )
        assertEquals(
            MasteryInboundDisposition.DUPLICATE,
            bindingAuthorityStateDisposition(
                current = current,
                incoming =
                    bindingState(
                        generation = current.sourceStoreGeneration,
                        version = 99L,
                        payloadFingerprint = "3".repeat(64),
                    ),
            ),
        )
        assertNull(
            bindingAuthorityStateDisposition(
                current = current,
                incoming =
                    bindingState(
                        generation = "student-generation-new",
                        version = 1L,
                        payloadFingerprint = "4".repeat(64),
                    ),
            ),
        )
    }

    private fun KnowledgeNodeRef.projectionIdentity(): String =
        MasteryProjectionIdentity.fingerprint(
            subject = subject.name,
            knowledgeNodeId = knowledgeNodeId,
            taxonomyVersion = taxonomyVersion,
        )

    private fun nodeRef(
        taxonomyVersion: String,
        knowledgePackVersion: String,
    ): KnowledgeNodeRef =
        KnowledgeNodeRef(
            subject = SubjectKind.MATH,
            knowledgeNodeId = "math.function.quadratic",
            taxonomyVersion = taxonomyVersion,
            knowledgePackVersion = knowledgePackVersion,
        )

    private fun boundAttribution(): ProposedKnowledgeAttribution {
        val node = nodeRef("taxonomy-v1", "pack-v1")
        val problem =
            StudentProblemRef(
                learnerId = "learner-local",
                subject = SubjectKind.MATH,
                problemId = "problem-local",
                practiceUnitId = "practice-local",
            )
        val revision =
            StudentProblemRevisionRef(
                problem = problem,
                revisionId = "revision-local",
                revisionNumber = 1,
                documentCanonicalFingerprint = "a".repeat(64),
            )
        val binding =
            ProblemKnowledgeBindingRef(
                bindingId = "binding-local",
                problemRevision = revision,
                knowledgeNode = node,
                bindingCanonicalFingerprint = "b".repeat(64),
            )
        return ProposedKnowledgeAttribution(
            knowledgeNode = node,
            problemBinding = binding,
            role = MasteryAttributionRole.PRIMARY,
            certainty = MasteryAttributionCertainty.DIRECT,
        )
    }

    private fun bindingState(
        generation: String,
        version: Long,
        payloadFingerprint: String,
    ): MasteryProblemBindingAuthorityStateEntity =
        MasteryProblemBindingAuthorityStateEntity(
            problemRevisionRefFingerprint = "5".repeat(64),
            inboxEventId = "binding-state-$generation-$version",
            sourceStoreGeneration = generation,
            envelopeCanonicalFingerprint = "6".repeat(64),
            payloadCanonicalFingerprint = payloadFingerprint,
            bindingProtocolVersion = CURRENT_BINDING_PROTOCOL_VERSION,
            bindingSetVersion = version,
            learnerId = "learner-local",
            subject = SubjectKind.MATH.name,
            changedAtEpochMillis = version,
        )
}
