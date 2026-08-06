package com.tingyun.smartmistakebook.core.mastery.database

import androidx.sqlite.SQLiteConnection
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationOutcome
import com.tingyun.smartmistakebook.core.model.OpenResponseEvaluationTaskFingerprints
import com.tingyun.smartmistakebook.core.model.OpenResponseKnowledgeRole
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.KnowledgeNodeRef

/** Revalidates the immutable, content-free proof chain used by open-response weak evidence. */
internal fun auditLearnerMasteryOpenResponseDedicatedProofChains(
    connection: SQLiteConnection,
) {
    auditOpenResponseProofChainPrivacyBoundary(connection)
    check(
        !connection.hasOpenResponseAuditRow(
            """
            SELECT 1
            FROM $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE AS receipt
            WHERE receipt.proof_chain_version NOT IN (0, $CURRENT_OPEN_RESPONSE_PROOF_CHAIN_VERSION)
               OR (
                 receipt.proof_chain_version = 0
                 AND (
                   EXISTS (
                     SELECT 1
                     FROM $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE AS attestation
                     WHERE attestation.receipt_fingerprint = receipt.receipt_fingerprint
                   )
                   OR EXISTS (
                     SELECT 1
                     FROM $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE AS decision
                     WHERE decision.receipt_fingerprint = receipt.receipt_fingerprint
                   )
                 )
               )
               OR (
                 receipt.proof_chain_version = $CURRENT_OPEN_RESPONSE_PROOF_CHAIN_VERSION
                 AND (
                   (SELECT COUNT(*)
                    FROM $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE AS attestation
                    WHERE attestation.receipt_fingerprint = receipt.receipt_fingerprint) != 1
                   OR
                   (SELECT COUNT(*)
                    FROM $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE AS decision
                    WHERE decision.receipt_fingerprint = receipt.receipt_fingerprint) != 1
                 )
               )
            LIMIT 1
            """.trimIndent(),
        ),
    ) { "Learner-mastery open-response receipt has an incomplete proof chain" }

    check(
        !connection.hasOpenResponseAuditRow(INVALID_OPEN_RESPONSE_ATTESTATION_BINDING_SQL),
    ) { "Learner-mastery open-response attestation crossed its persisted authority scope" }

    val attestations = readAndAuditOpenResponseAttestations(connection)
    val scopes = readAndAuditOpenResponseKnowledgeScopes(connection, attestations)
    auditOpenResponseAuthorizedScopeAggregates(connection, attestations, scopes)
    auditOpenResponseWeakCandidateReceipts(connection, attestations, scopes)
    readAndAuditOpenResponseDedicatedDecisions(connection, attestations, scopes)
    auditOpenResponseLegacyQuarantines(connection)
    check(!connection.hasOpenResponseAuditRow("PRAGMA foreign_key_check")) {
        "Learner-mastery open-response proof chain contains a foreign-key violation"
    }
}

/**
 * Included in the existing calibration watermark so any proof-row content change invalidates a
 * previously trusted startup audit. Rows are read in primary-key order and fed directly into the
 * digest, keeping memory constant while covering rewrites that preserve COUNT and MAX(rowid).
 */
internal fun learnerMasteryOpenResponseProofStateFingerprint(
    connection: SQLiteConnection,
): String {
    val digest = CanonicalSha256("learner-mastery-open-response-proof-state-v2")
    listOf(
        LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE,
    ).forEachIndexed { index, table ->
        digest.field("table[$index]", table)
        connection.appendCanonicalOpenResponseTableContent(digest, index, table)
    }
    return digest.finish()
}

private fun SQLiteConnection.appendCanonicalOpenResponseTableContent(
    digest: CanonicalSha256,
    tableIndex: Int,
    tableName: String,
) {
    val columns = mutableListOf<OpenResponseAuditColumn>()
    prepare("PRAGMA table_info(`${tableName.escapeOpenResponseIdentifier()}`)").use { statement ->
        while (statement.step()) {
            columns +=
                OpenResponseAuditColumn(
                    name = statement.getText(1),
                    primaryKeyOrdinal = statement.getLong(5).toInt(),
                )
        }
    }
    check(columns.isNotEmpty()) { "Open-response proof table $tableName is unavailable" }
    val primaryKey = columns.filter { it.primaryKeyOrdinal > 0 }.sortedBy { it.primaryKeyOrdinal }
    check(primaryKey.isNotEmpty()) { "Open-response proof table $tableName has no stable key" }
    digest.field("columnCount[$tableIndex]", columns.size)
    columns.forEachIndexed { columnIndex, column ->
        digest.field("column[$tableIndex][$columnIndex]", column.name)
        digest.field("pk[$tableIndex][$columnIndex]", column.primaryKeyOrdinal)
    }
    val quotedColumns =
        columns.joinToString(", ") { column ->
            "quote(`${column.name.escapeOpenResponseIdentifier()}`)"
        }
    val ordering =
        primaryKey.joinToString(", ") { column ->
            "`${column.name.escapeOpenResponseIdentifier()}` ASC"
        }
    var rowIndex = 0L
    prepare(
        "SELECT $quotedColumns FROM `${tableName.escapeOpenResponseIdentifier()}` " +
            "ORDER BY $ordering",
    ).use { statement ->
        while (statement.step()) {
            columns.indices.forEach { columnIndex ->
                digest.field(
                    "value[$tableIndex][$rowIndex][$columnIndex]",
                    statement.getText(columnIndex),
                )
            }
            rowIndex += 1L
        }
    }
    digest.field("rowCount[$tableIndex]", rowIndex)
}

private data class OpenResponseAuditColumn(
    val name: String,
    val primaryKeyOrdinal: Int,
)

private fun String.escapeOpenResponseIdentifier(): String = replace("`", "``")

private fun readAndAuditOpenResponseAttestations(
    connection: SQLiteConnection,
): Map<String, MasteryOpenResponseModelEvaluationAttestationEntity> {
    val attestations = linkedMapOf<String, MasteryOpenResponseModelEvaluationAttestationEntity>()
    connection.prepare(
        """
        SELECT attestation_fingerprint, receipt_fingerprint, candidate_id,
               candidate_canonical_fingerprint, source_fact_id, review_case_id, learner_id,
               subject, scope_fingerprint, conversation_id, conversation_generation,
               conversation_state_version, question_document_id, question_revision_number,
               question_fingerprint, evidence_request_id, turn_reference_id, turn_ordinal,
               turn_generation, mode_version, request_version, model_task_request_id,
               model_response_schema_version, evaluator_request_version,
               candidate_idempotency_key, evidence_fingerprint, model_output_fingerprint,
               model_version, outcome, occurred_at_epoch_millis, received_at_epoch_millis
        FROM $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE
        ORDER BY attestation_fingerprint ASC
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val entity =
                MasteryOpenResponseModelEvaluationAttestationEntity(
                    attestationFingerprint = statement.getText(0),
                    receiptFingerprint = statement.getText(1),
                    candidateId = statement.getText(2),
                    candidateCanonicalFingerprint = statement.getText(3),
                    sourceFactId = statement.getText(4),
                    reviewCaseId = statement.getText(5),
                    learnerId = statement.getText(6),
                    subject = statement.getText(7),
                    scopeFingerprint = statement.getText(8),
                    conversationId = statement.getText(9),
                    conversationGeneration = statement.getLong(10),
                    conversationStateVersion = statement.getLong(11),
                    questionDocumentId = statement.getText(12),
                    questionRevisionNumber = statement.getLong(13).toInt(),
                    questionFingerprint = statement.getText(14),
                    evidenceRequestId = statement.getText(15),
                    turnReferenceId = statement.getText(16),
                    turnOrdinal = statement.getLong(17).toInt(),
                    turnGeneration = statement.getLong(18),
                    modeVersion = statement.getLong(19),
                    requestVersion = statement.getLong(20),
                    modelTaskRequestId = statement.getText(21),
                    modelResponseSchemaVersion = statement.getLong(22).toInt(),
                    evaluatorRequestVersion = statement.getLong(23),
                    candidateIdempotencyKey = statement.getText(24),
                    evidenceFingerprint = statement.getText(25),
                    modelOutputFingerprint = statement.getText(26),
                    modelVersion = statement.getText(27),
                    outcome = statement.getText(28),
                    occurredAtEpochMillis = statement.getLong(29),
                    receivedAtEpochMillis = statement.getLong(30),
                )
            check(entity.attestationFingerprint == entity.expectedCanonicalFingerprint()) {
                "Learner-mastery open-response attestation fingerprint is invalid"
            }
            check(OpenResponseEvaluationOutcome.valueOf(entity.outcome) !=
                OpenResponseEvaluationOutcome.UNSCORABLE) {
                "Unscorable open-response output escaped the pending-review boundary"
            }
            check(attestations.put(entity.attestationFingerprint, entity) == null) {
                "Learner-mastery open-response attestation fingerprint is duplicated"
            }
        }
    }
    return attestations
}

private fun readAndAuditOpenResponseKnowledgeScopes(
    connection: SQLiteConnection,
    attestations: Map<String, MasteryOpenResponseModelEvaluationAttestationEntity>,
): Map<String, List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>> {
    val scopes = linkedMapOf<String, MutableList<MasteryOpenResponseEvaluationKnowledgeScopeEntity>>()
    connection.prepare(
        """
        SELECT attestation_fingerprint, ordinal, ref_fingerprint, subject, knowledge_node_id,
               taxonomy_version, knowledge_pack_version, knowledge_node_ref_fingerprint,
               manifest_fingerprint, activation_generation, evaluation_role,
               scope_entry_fingerprint
        FROM $LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE
        ORDER BY attestation_fingerprint ASC, ordinal ASC
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val entity =
                MasteryOpenResponseEvaluationKnowledgeScopeEntity(
                    attestationFingerprint = statement.getText(0),
                    ordinal = statement.getLong(1).toInt(),
                    refFingerprint = statement.getText(2),
                    subject = statement.getText(3),
                    knowledgeNodeId = statement.getText(4),
                    taxonomyVersion = statement.getText(5),
                    knowledgePackVersion = statement.getText(6),
                    knowledgeNodeRefFingerprint = statement.getText(7),
                    manifestFingerprint = statement.getText(8),
                    activationGeneration = statement.getLong(9),
                    evaluationRole = statement.nullableText(10),
                    scopeEntryFingerprint = statement.getText(11),
                )
            val attestation = checkNotNull(attestations[entity.attestationFingerprint]) {
                "Learner-mastery open-response scope has no attestation"
            }
            val group = scopes.getOrPut(entity.attestationFingerprint, ::mutableListOf)
            check(entity.ordinal == group.size) {
                "Learner-mastery open-response knowledge-scope ordinals are not contiguous"
            }
            val knowledgeNode =
                KnowledgeNodeRef(
                    subject = SubjectKind.valueOf(entity.subject),
                    knowledgeNodeId = entity.knowledgeNodeId,
                    taxonomyVersion = entity.taxonomyVersion,
                    knowledgePackVersion = entity.knowledgePackVersion,
                )
            check(knowledgeNode.canonicalFingerprint == entity.knowledgeNodeRefFingerprint) {
                "Learner-mastery open-response knowledge reference is not canonical"
            }
            check(entity.subject == attestation.subject) {
                "Learner-mastery open-response knowledge crossed its subject boundary"
            }
            check(
                entity.refFingerprint ==
                    OpenResponseEvaluationTaskFingerprints.knowledgeScopeReference(
                        questionFingerprint = attestation.questionFingerprint,
                        knowledgeNodeReferenceFingerprint = entity.knowledgeNodeRefFingerprint,
                        knowledgeManifestFingerprint = entity.manifestFingerprint,
                        knowledgeActivationGeneration = entity.activationGeneration,
                    ),
            ) { "Learner-mastery open-response model reference is not host-bound" }
            val role = entity.evaluationRole?.let { OpenResponseKnowledgeRole.valueOf(it) }
            val entry =
                LearnerMasteryOpenResponseKnowledgeScopeEntry(
                    refFingerprint = entity.refFingerprint,
                    knowledgeNode = knowledgeNode,
                    manifestFingerprint = entity.manifestFingerprint,
                    activationGeneration = entity.activationGeneration,
                    evaluationRole = role,
                )
            val expectedScopeFingerprint =
                CanonicalSha256("learner-mastery-open-response-persisted-knowledge-scope-v1")
                    .field("attestationFingerprint", entity.attestationFingerprint)
                    .field("ordinal", entity.ordinal)
                    .field("entry", entry.canonicalFingerprint)
                    .finish()
            check(entity.scopeEntryFingerprint == expectedScopeFingerprint) {
                "Learner-mastery open-response persisted scope fingerprint is invalid"
            }
            group += entity
        }
    }
    return scopes
}

private fun auditOpenResponseAuthorizedScopeAggregates(
    connection: SQLiteConnection,
    attestations: Map<String, MasteryOpenResponseModelEvaluationAttestationEntity>,
    scopes: Map<String, List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>>,
) {
    connection.prepare(
        """
        SELECT attestation.attestation_fingerprint, fact.authorized_knowledge_refs_fingerprint,
               fact.knowledge_manifest_fingerprint, fact.knowledge_activation_generation
        FROM $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE AS attestation
        JOIN mastery_source_fact AS fact ON fact.source_fact_id = attestation.source_fact_id
        ORDER BY attestation.attestation_fingerprint ASC
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val attestationFingerprint = statement.getText(0)
            check(attestationFingerprint in attestations)
            val scope = scopes[attestationFingerprint].orEmpty()
            check(
                fingerprintAuthorizedKnowledgeRefs(
                    scope.map(
                        MasteryOpenResponseEvaluationKnowledgeScopeEntity::knowledgeNodeRefFingerprint,
                    ),
                ) == statement.getText(1),
            ) { "Learner-mastery open-response authorized scope aggregate is invalid" }
            val sourceManifest = statement.nullableText(2)
            val sourceGeneration = statement.nullableLong(3)
            if (scope.isEmpty()) {
                check(sourceManifest == null && sourceGeneration == null) {
                    "Empty open-response scope retained a catalog activation"
                }
            } else {
                check(
                    scope.all {
                        it.manifestFingerprint == sourceManifest &&
                            it.activationGeneration == sourceGeneration
                    },
                ) { "Learner-mastery open-response scope mixed catalog activations" }
            }
        }
    }
}

private fun auditOpenResponseWeakCandidateReceipts(
    connection: SQLiteConnection,
    attestations: Map<String, MasteryOpenResponseModelEvaluationAttestationEntity>,
    scopes: Map<String, List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>>,
) {
    val lineage = mutableMapOf<String, MutableMap<Long, String>>()
    connection.prepare(
        """
        SELECT receipt.receipt_fingerprint, receipt.canonical_fingerprint,
               receipt.logical_attempt_fingerprint, receipt.lineage_parent_fingerprint,
               receipt.revision_ordinal, receipt.candidate_id,
               receipt.candidate_canonical_fingerprint, receipt.source_fact_id,
               receipt.review_case_id, receipt.review_case_fingerprint, receipt.learner_id,
               receipt.subject, receipt.scope_fingerprint, receipt.conversation_id,
               receipt.conversation_generation, receipt.conversation_state_version,
               receipt.question_document_id, receipt.question_revision_number,
               receipt.question_fingerprint, receipt.answer_fingerprint,
               receipt.evidence_request_id, receipt.turn_reference_id, receipt.turn_ordinal,
               receipt.turn_generation, receipt.mode_version, receipt.request_version,
               receipt.attempt_ordinal, receipt.hint_count, receipt.answer_was_revealed,
               receipt.model_task_request_id, receipt.model_response_schema_version,
               receipt.evaluator_request_version, receipt.candidate_idempotency_key,
               receipt.revision_of_candidate_idempotency_key, receipt.evidence_fingerprint,
               receipt.model_version, receipt.outcome, receipt.occurred_at_epoch_millis,
               receipt.received_at_epoch_millis, attestation.attestation_fingerprint,
               attestation.model_output_fingerprint
        FROM $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE AS receipt
        JOIN $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE AS attestation
          ON attestation.receipt_fingerprint = receipt.receipt_fingerprint
        WHERE receipt.proof_chain_version = $CURRENT_OPEN_RESPONSE_PROOF_CHAIN_VERSION
        ORDER BY receipt.logical_attempt_fingerprint ASC, receipt.revision_ordinal ASC
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val attestationFingerprint = statement.getText(39)
            check(attestationFingerprint in attestations)
            val authorizedScope =
                scopes[attestationFingerprint].orEmpty().map { entity ->
                    LearnerMasteryOpenResponseKnowledgeScopeEntry(
                        refFingerprint = entity.refFingerprint,
                        knowledgeNode =
                            KnowledgeNodeRef(
                                subject = SubjectKind.valueOf(entity.subject),
                                knowledgeNodeId = entity.knowledgeNodeId,
                                taxonomyVersion = entity.taxonomyVersion,
                                knowledgePackVersion = entity.knowledgePackVersion,
                            ),
                        manifestFingerprint = entity.manifestFingerprint,
                        activationGeneration = entity.activationGeneration,
                        evaluationRole =
                            entity.evaluationRole?.let {
                                OpenResponseKnowledgeRole.valueOf(it)
                            },
                    )
                }
            val command =
                LearnerMasteryOpenResponseWeakCandidateCommand(
                    learnerId = statement.getText(10),
                    subject = SubjectKind.valueOf(statement.getText(11)),
                    sourceFactId = statement.getText(7),
                    reviewCaseId = statement.getText(8),
                    scopeFingerprint = statement.getText(12),
                    conversationId = statement.getText(13),
                    conversationGeneration = statement.getLong(14),
                    conversationStateVersion = statement.getLong(15),
                    questionDocumentId = statement.getText(16),
                    questionRevisionNumber = statement.getLong(17).toInt(),
                    questionFingerprint = statement.getText(18),
                    responseBinding = statement.getText(19),
                    evidenceRequestId = statement.getText(20),
                    turnReferenceId = statement.getText(21),
                    turnOrdinal = statement.getLong(22).toInt(),
                    turnGeneration = statement.getLong(23),
                    modeVersion = statement.getLong(24),
                    requestVersion = statement.getLong(25),
                    attemptOrdinal = statement.getLong(26).toInt(),
                    hintCount = statement.getLong(27).toInt(),
                    answerWasRevealed = statement.getLong(28) != 0L,
                    modelTaskRequestId = statement.getText(29),
                    modelResponseSchemaVersion = statement.getLong(30).toInt(),
                    evaluatorRequestVersion = statement.getLong(31),
                    candidateIdempotencyKey = statement.getText(32),
                    revisionOfCandidateIdempotencyKey = statement.nullableText(33),
                    evidenceFingerprint = statement.getText(34),
                    modelOutputFingerprint = statement.getText(40),
                    modelVersion = statement.getText(35),
                    outcome = OpenResponseEvaluationOutcome.valueOf(statement.getText(36)),
                    authorizedKnowledgeScope = authorizedScope,
                    occurredAtEpochMillis = statement.getLong(37),
                )
            check(statement.getText(1) == command.canonicalFingerprint) {
                "Learner-mastery open-response weak-candidate fingerprint is invalid"
            }
            check(statement.getText(2) == command.logicalAttemptFingerprint) {
                "Learner-mastery open-response logical-attempt fingerprint is invalid"
            }
            val expectedReceipt =
                CanonicalSha256("learner-mastery-open-response-weak-candidate-receipt-v1")
                    .field("candidate", statement.getText(6))
                    .field("reviewCase", statement.getText(9))
                    .field("command", command.canonicalFingerprint)
                    .finish()
            check(statement.getText(0) == expectedReceipt) {
                "Learner-mastery open-response receipt fingerprint is invalid"
            }
            check(statement.getLong(38) >= command.occurredAtEpochMillis) {
                "Learner-mastery open-response receipt predates its observation"
            }
            val revisionOrdinal = checkNotNull(statement.nullableLong(4)) {
                "New open-response proof chain omitted its revision ordinal"
            }
            val expectedLineageParent =
                command.revisionOfCandidateIdempotencyKey ?: command.logicalAttemptFingerprint
            check(statement.getText(3) == expectedLineageParent) {
                "Learner-mastery open-response lineage parent is invalid"
            }
            check((revisionOrdinal == 0L) ==
                (command.revisionOfCandidateIdempotencyKey == null)) {
                "Learner-mastery open-response revision root is invalid"
            }
            val attemptLineage =
                lineage.getOrPut(command.logicalAttemptFingerprint) { mutableMapOf() }
            if (revisionOrdinal > 0L) {
                check(
                    attemptLineage[revisionOrdinal - 1L] ==
                        command.revisionOfCandidateIdempotencyKey,
                ) { "Learner-mastery open-response revision lineage is not contiguous" }
            }
            check(attemptLineage.put(revisionOrdinal, command.candidateIdempotencyKey) == null) {
                "Learner-mastery open-response revision ordinal is duplicated"
            }
        }
    }
}

private fun readAndAuditOpenResponseDedicatedDecisions(
    connection: SQLiteConnection,
    attestations: Map<String, MasteryOpenResponseModelEvaluationAttestationEntity>,
    scopes: Map<String, List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>>,
) {
    connection.prepare(
        """
        SELECT decision_fingerprint, attestation_fingerprint, receipt_fingerprint,
               review_case_id, candidate_id, source_fact_id, learner_id, subject, disposition,
               direction, local_reason, selected_scope_fingerprint, selected_knowledge_count,
               local_policy_version, calibration_snapshot_fingerprint, accepted_event_id,
               independently_completed, decided_at_epoch_millis
        FROM $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE
        ORDER BY decision_fingerprint ASC
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val entity =
                MasteryOpenResponseDedicatedDecisionEntity(
                    decisionFingerprint = statement.getText(0),
                    attestationFingerprint = statement.getText(1),
                    receiptFingerprint = statement.getText(2),
                    reviewCaseId = statement.getText(3),
                    candidateId = statement.getText(4),
                    sourceFactId = statement.getText(5),
                    learnerId = statement.getText(6),
                    subject = statement.getText(7),
                    disposition = statement.getText(8),
                    direction = statement.nullableText(9),
                    localReason = statement.nullableText(10),
                    selectedScopeFingerprint = statement.nullableText(11),
                    selectedKnowledgeCount = statement.getLong(12).toInt(),
                    localPolicyVersion = statement.getText(13),
                    calibrationSnapshotFingerprint = statement.getText(14),
                    acceptedEventId = statement.nullableText(15),
                    independentlyCompleted = statement.getLong(16) != 0L,
                    decidedAtEpochMillis = statement.getLong(17),
                )
            val attestation = checkNotNull(attestations[entity.attestationFingerprint]) {
                "Learner-mastery open-response decision has no attestation"
            }
            val selected =
                scopes[entity.attestationFingerprint].orEmpty().filter { scope ->
                    when (entity.direction) {
                        MasteryEventDirection.POSITIVE.name ->
                            scope.evaluationRole == OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS.name
                        MasteryEventDirection.NEGATIVE.name ->
                            scope.evaluationRole == OpenResponseKnowledgeRole.LOCATED_GAP.name
                        else -> false
                    }
                }
            check(entity.decisionFingerprint == entity.expectedCanonicalFingerprint()) {
                "Learner-mastery open-response dedicated decision fingerprint is invalid"
            }
            check(entity.localPolicyVersion ==
                LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_POLICY_VERSION) {
                "Learner-mastery open-response decision used another local policy"
            }
            check(
                entity.receiptFingerprint == attestation.receiptFingerprint &&
                    entity.candidateId == attestation.candidateId &&
                    entity.sourceFactId == attestation.sourceFactId &&
                    entity.learnerId == attestation.learnerId &&
                    entity.subject == attestation.subject,
            ) { "Learner-mastery open-response decision crossed its attested identity" }

            when (OpenResponseDedicatedDecisionDisposition.valueOf(entity.disposition)) {
                OpenResponseDedicatedDecisionDisposition.ACCEPTED -> {
                    check(entity.direction != null && entity.localReason == null)
                    check(entity.acceptedEventId ==
                        openResponseDedicatedEventId(entity.attestationFingerprint))
                    check(selected.isNotEmpty() && entity.selectedKnowledgeCount == selected.size)
                    check(entity.selectedScopeFingerprint ==
                        fingerprintOpenResponseSelectedScope(selected))
                    checkAcceptedOpenResponseOutcome(
                        decision = entity,
                        attestation = attestation,
                        fullScope = scopes[entity.attestationFingerprint].orEmpty(),
                        selected = selected,
                    )
                }
                OpenResponseDedicatedDecisionDisposition.RETAINED_FOR_REVIEW -> {
                    check(entity.direction == null && entity.localReason != null)
                    OpenResponseDedicatedDecisionReason.valueOf(entity.localReason)
                    check(entity.selectedScopeFingerprint == null)
                    check(entity.selectedKnowledgeCount == 0)
                    check(entity.acceptedEventId == null)
                    check(!entity.independentlyCompleted)
                }
            }
        }
    }
    check(!connection.hasOpenResponseAuditRow(INVALID_OPEN_RESPONSE_DECISION_BINDING_SQL)) {
        "Learner-mastery open-response decision is not bound to its event and calibration"
    }
}

private fun checkAcceptedOpenResponseOutcome(
    decision: MasteryOpenResponseDedicatedDecisionEntity,
    attestation: MasteryOpenResponseModelEvaluationAttestationEntity,
    fullScope: List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>,
    selected: List<MasteryOpenResponseEvaluationKnowledgeScopeEntity>,
) {
    val outcome = OpenResponseEvaluationOutcome.valueOf(attestation.outcome)
    when (decision.direction) {
        MasteryEventDirection.POSITIVE.name -> {
            check(outcome == OpenResponseEvaluationOutcome.CORRECT ||
                outcome == OpenResponseEvaluationOutcome.ASSISTED_CORRECT)
            check(selected.all {
                it.evaluationRole == OpenResponseKnowledgeRole.SUPPORTED_CORRECTNESS.name
            })
            check(fullScope.none {
                it.evaluationRole == OpenResponseKnowledgeRole.LOCATED_GAP.name
            })
        }
        MasteryEventDirection.NEGATIVE.name -> {
            check(outcome == OpenResponseEvaluationOutcome.INCORRECT)
            check(selected.size == 1)
            check(selected.single().evaluationRole == OpenResponseKnowledgeRole.LOCATED_GAP.name)
        }
        else -> error("Accepted open-response decision has an invalid direction")
    }
}

private fun auditOpenResponseLegacyQuarantines(connection: SQLiteConnection) {
    check(
        !connection.hasOpenResponseAuditRow(
            """
            SELECT 1
            FROM $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE AS decision
            LEFT JOIN $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE AS quarantine
              ON quarantine.decision_fingerprint = decision.decision_fingerprint
            WHERE (decision.disposition = 'ACCEPTED' AND quarantine.accepted_event_id IS NULL)
               OR (decision.disposition != 'ACCEPTED' AND quarantine.accepted_event_id IS NOT NULL)
            LIMIT 1
            """.trimIndent(),
        ),
    ) { "Model-semantic open-response acceptance escaped the local quarantine" }
    connection.prepare(
        """
        SELECT quarantine.accepted_event_id, quarantine.event_canonical_fingerprint,
               quarantine.decision_fingerprint, quarantine.quarantine_reason,
               quarantine.policy_version, quarantine.quarantined_at_epoch_millis,
               quarantine.quarantine_fingerprint, decision.accepted_event_id,
               event.canonical_fingerprint, decision.decided_at_epoch_millis,
               event.admitted_at_epoch_millis
        FROM $LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE AS quarantine
        JOIN $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE AS decision
          ON decision.decision_fingerprint = quarantine.decision_fingerprint
        JOIN mastery_learning_event AS event
          ON event.event_id = quarantine.accepted_event_id
        ORDER BY quarantine.accepted_event_id
        """.trimIndent(),
    ).use { statement ->
        while (statement.step()) {
            val eventId = statement.getText(0)
            val eventFingerprint = statement.getText(1)
            val decisionFingerprint = statement.getText(2)
            val quarantinedAt = statement.getLong(5)
            check(
                statement.getText(3) ==
                    LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_REASON &&
                    statement.getText(4) ==
                    LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_POLICY_VERSION &&
                    eventId == statement.getText(7) &&
                    eventFingerprint == statement.getText(8) &&
                    quarantinedAt >= statement.getLong(9) &&
                    quarantinedAt >= statement.getLong(10) &&
                    statement.getText(6) ==
                    openResponseLegacyQuarantineFingerprint(
                        acceptedEventId = eventId,
                        eventCanonicalFingerprint = eventFingerprint,
                        decisionFingerprint = decisionFingerprint,
                        quarantinedAtEpochMillis = quarantinedAt,
                    ),
            ) { "Open-response legacy quarantine is not canonically bound" }
        }
    }
}

private fun MasteryOpenResponseModelEvaluationAttestationEntity.expectedCanonicalFingerprint():
    String =
    CanonicalSha256("learner-mastery-open-response-model-attestation-v1")
        .field("receiptFingerprint", receiptFingerprint)
        .field("candidateId", candidateId)
        .field("candidateFingerprint", candidateCanonicalFingerprint)
        .field("sourceFactId", sourceFactId)
        .field("reviewCaseId", reviewCaseId)
        .field("learnerId", learnerId)
        .field("subject", subject)
        .field("scopeFingerprint", scopeFingerprint)
        .field("conversationId", conversationId)
        .field("conversationGeneration", conversationGeneration)
        .field("conversationStateVersion", conversationStateVersion)
        .field("questionDocumentId", questionDocumentId)
        .field("questionRevisionNumber", questionRevisionNumber)
        .field("questionFingerprint", questionFingerprint)
        .field("evidenceRequestId", evidenceRequestId)
        .field("turnReferenceId", turnReferenceId)
        .field("turnOrdinal", turnOrdinal)
        .field("turnGeneration", turnGeneration)
        .field("modeVersion", modeVersion)
        .field("requestVersion", requestVersion)
        .field("modelTaskRequestId", modelTaskRequestId)
        .field("modelResponseSchemaVersion", modelResponseSchemaVersion)
        .field("evaluatorRequestVersion", evaluatorRequestVersion)
        .field("candidateIdempotencyKey", candidateIdempotencyKey)
        .field("evidenceFingerprint", evidenceFingerprint)
        .field("modelOutputFingerprint", modelOutputFingerprint)
        .field("modelVersion", modelVersion)
        .field("outcome", outcome)
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .field("receivedAtEpochMillis", receivedAtEpochMillis)
        .finish()

private fun MasteryOpenResponseDedicatedDecisionEntity.expectedCanonicalFingerprint(): String =
    CanonicalSha256("learner-mastery-open-response-dedicated-decision-v1")
        .field("attestationFingerprint", attestationFingerprint)
        .field("receiptFingerprint", receiptFingerprint)
        .field("reviewCaseId", reviewCaseId)
        .field("candidateId", candidateId)
        .field("sourceFactId", sourceFactId)
        .field("learnerId", learnerId)
        .field("subject", subject)
        .field("disposition", disposition)
        .nullableField("direction", direction)
        .nullableField("localReason", localReason)
        .nullableField("selectedScopeFingerprint", selectedScopeFingerprint)
        .field("selectedKnowledgeCount", selectedKnowledgeCount)
        .field("localPolicyVersion", localPolicyVersion)
        .field("calibrationSnapshotFingerprint", calibrationSnapshotFingerprint)
        .nullableField("acceptedEventId", acceptedEventId)
        .field("independentlyCompleted", independentlyCompleted)
        .field("decidedAtEpochMillis", decidedAtEpochMillis)
        .finish()

private fun auditOpenResponseProofChainPrivacyBoundary(connection: SQLiteConnection) {
    val forbidden = listOf("answer", "prompt", "body", "display", "label", "weight", "confidence", "sql")
    listOf(
        LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE,
        LEARNER_MASTERY_OPEN_RESPONSE_LEGACY_QUARANTINE_TABLE,
    ).forEach { table ->
        connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
            while (statement.step()) {
                val column = statement.getText(1).lowercase()
                check(forbidden.none(column::contains)) {
                    "Open-response proof table $table exposes forbidden column $column"
                }
            }
        }
    }
}

private fun SQLiteConnection.hasOpenResponseAuditRow(sql: String): Boolean =
    prepare(sql).use { statement -> statement.step() }

private fun androidx.sqlite.SQLiteStatement.nullableText(index: Int): String? =
    if (isNull(index)) null else getText(index)

private fun androidx.sqlite.SQLiteStatement.nullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

private val INVALID_OPEN_RESPONSE_ATTESTATION_BINDING_SQL =
    """
    SELECT 1
    FROM $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE AS attestation
    JOIN $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE AS receipt
      ON receipt.receipt_fingerprint = attestation.receipt_fingerprint
    LEFT JOIN mastery_observation_candidate AS candidate
      ON candidate.candidate_id = attestation.candidate_id
    LEFT JOIN mastery_source_fact AS fact
      ON fact.source_fact_id = attestation.source_fact_id
    LEFT JOIN mastery_evidence_review_case AS review_case
      ON review_case.review_case_id = attestation.review_case_id
    WHERE receipt.proof_chain_version != $CURRENT_OPEN_RESPONSE_PROOF_CHAIN_VERSION
       OR attestation.candidate_id IS NOT receipt.candidate_id
       OR attestation.candidate_canonical_fingerprint IS NOT receipt.candidate_canonical_fingerprint
       OR attestation.source_fact_id IS NOT receipt.source_fact_id
       OR attestation.review_case_id IS NOT receipt.review_case_id
       OR attestation.learner_id IS NOT receipt.learner_id
       OR attestation.subject IS NOT receipt.subject
       OR attestation.scope_fingerprint IS NOT receipt.scope_fingerprint
       OR attestation.conversation_id IS NOT receipt.conversation_id
       OR attestation.conversation_generation IS NOT receipt.conversation_generation
       OR attestation.conversation_state_version IS NOT receipt.conversation_state_version
       OR attestation.question_document_id IS NOT receipt.question_document_id
       OR attestation.question_revision_number IS NOT receipt.question_revision_number
       OR attestation.question_fingerprint IS NOT receipt.question_fingerprint
       OR attestation.evidence_request_id IS NOT receipt.evidence_request_id
       OR attestation.turn_reference_id IS NOT receipt.turn_reference_id
       OR attestation.turn_ordinal IS NOT receipt.turn_ordinal
       OR attestation.turn_generation IS NOT receipt.turn_generation
       OR attestation.mode_version IS NOT receipt.mode_version
       OR attestation.request_version IS NOT receipt.request_version
       OR attestation.model_task_request_id IS NOT receipt.model_task_request_id
       OR attestation.model_response_schema_version IS NOT receipt.model_response_schema_version
       OR attestation.evaluator_request_version IS NOT receipt.evaluator_request_version
       OR attestation.candidate_idempotency_key IS NOT receipt.candidate_idempotency_key
       OR attestation.evidence_fingerprint IS NOT receipt.evidence_fingerprint
       OR attestation.model_version IS NOT receipt.model_version
       OR attestation.outcome IS NOT receipt.outcome
       OR attestation.occurred_at_epoch_millis IS NOT receipt.occurred_at_epoch_millis
       OR attestation.received_at_epoch_millis IS NOT receipt.received_at_epoch_millis
       OR candidate.candidate_id IS NULL
       OR candidate.canonical_fingerprint IS NOT attestation.candidate_canonical_fingerprint
       OR candidate.source_fact_id IS NOT attestation.source_fact_id
       OR candidate.learner_id IS NOT attestation.learner_id
       OR candidate.subject IS NOT attestation.subject
       OR fact.source_fact_id IS NULL
       OR fact.learner_id IS NOT attestation.learner_id
       OR fact.subject IS NOT attestation.subject
       OR fact.outcome != 'PENDING_REVIEW'
       OR review_case.review_case_id IS NULL
       OR review_case.candidate_id IS NOT attestation.candidate_id
       OR review_case.source_fact_id IS NOT attestation.source_fact_id
       OR review_case.learner_id IS NOT attestation.learner_id
       OR review_case.subject IS NOT attestation.subject
       OR review_case.reason != 'MODEL_ONLY_OPEN_RESPONSE'
    LIMIT 1
    """.trimIndent()

private val INVALID_OPEN_RESPONSE_DECISION_BINDING_SQL =
    """
    SELECT 1
    FROM $LEARNER_MASTERY_OPEN_RESPONSE_DEDICATED_DECISION_TABLE AS decision
    JOIN $LEARNER_MASTERY_OPEN_RESPONSE_MODEL_ATTESTATION_TABLE AS attestation
      ON attestation.attestation_fingerprint = decision.attestation_fingerprint
    JOIN $LEARNER_MASTERY_OPEN_RESPONSE_WEAK_CANDIDATE_RECEIPT_TABLE AS receipt
      ON receipt.receipt_fingerprint = decision.receipt_fingerprint
    JOIN mastery_evidence_review_case AS review_case
      ON review_case.review_case_id = decision.review_case_id
    LEFT JOIN mastery_calibration_snapshot AS calibration
      ON calibration.subject = decision.subject
     AND calibration.snapshot_fingerprint = decision.calibration_snapshot_fingerprint
    LEFT JOIN mastery_learning_event AS event
      ON event.event_id = decision.accepted_event_id
    LEFT JOIN mastery_applied_event AS applied
      ON applied.event_id = event.event_id
    WHERE calibration.snapshot_fingerprint IS NULL
       OR decision.review_case_id IS NOT attestation.review_case_id
       OR receipt.receipt_fingerprint IS NOT attestation.receipt_fingerprint
       OR review_case.candidate_id IS NOT decision.candidate_id
       OR review_case.learner_id IS NOT decision.learner_id
       OR review_case.subject IS NOT decision.subject
       OR review_case.calibration_binding_status != 'BOUND'
       OR review_case.calibration_snapshot_fingerprint IS NOT
          decision.calibration_snapshot_fingerprint
       OR decision.disposition NOT IN ('ACCEPTED', 'RETAINED_FOR_REVIEW')
       OR (
         decision.disposition = 'ACCEPTED'
         AND (
           receipt.answer_was_revealed != 0
           OR receipt.hint_count > $LEARNER_MASTERY_OPEN_RESPONSE_MAX_ADMITTED_HINTS
           OR receipt.attempt_ordinal > $LEARNER_MASTERY_OPEN_RESPONSE_MAX_ADMITTED_ATTEMPTS
           OR (attestation.outcome = 'ASSISTED_CORRECT'
               AND receipt.hint_count = 0 AND receipt.attempt_ordinal = 1)
           OR decision.independently_completed IS NOT (
                receipt.hint_count = 0
                AND receipt.attempt_ordinal = 1
              )
           OR
           event.event_id IS NULL
           OR applied.event_id IS NULL
           OR event.candidate_id IS NOT decision.candidate_id
           OR event.source_fact_id IS NOT decision.source_fact_id
           OR event.learner_id IS NOT decision.learner_id
           OR event.subject IS NOT decision.subject
           OR event.direction IS NOT decision.direction
           OR event.calibration_snapshot_fingerprint IS NOT
              decision.calibration_snapshot_fingerprint
           OR event.independently_answered IS NOT decision.independently_completed
           OR applied.event_canonical_fingerprint IS NOT event.canonical_fingerprint
           OR EXISTS (
             SELECT 1
             FROM mastery_evidence_review_resolution AS resolution
             WHERE resolution.review_case_id = decision.review_case_id
           )
           OR (SELECT COUNT(*) FROM mastery_learning_event_attribution AS event_scope
               WHERE event_scope.event_id = event.event_id) != decision.selected_knowledge_count
           OR EXISTS (
             SELECT 1
             FROM mastery_learning_event_attribution AS event_scope
             WHERE event_scope.event_id = event.event_id
               AND NOT EXISTS (
                 SELECT 1
                 FROM $LEARNER_MASTERY_OPEN_RESPONSE_KNOWLEDGE_SCOPE_TABLE AS selected_scope
                 WHERE selected_scope.attestation_fingerprint = decision.attestation_fingerprint
                   AND selected_scope.subject = event_scope.subject
                   AND selected_scope.knowledge_node_ref_fingerprint =
                       event_scope.knowledge_node_ref_fingerprint
                   AND (
                     (decision.direction = 'POSITIVE' AND
                      selected_scope.evaluation_role = 'SUPPORTED_CORRECTNESS')
                     OR
                     (decision.direction = 'NEGATIVE' AND
                      selected_scope.evaluation_role = 'LOCATED_GAP')
                   )
               )
           )
         )
       )
       OR (
         decision.disposition = 'RETAINED_FOR_REVIEW'
         AND event.event_id IS NOT NULL
       )
    LIMIT 1
    """.trimIndent()
