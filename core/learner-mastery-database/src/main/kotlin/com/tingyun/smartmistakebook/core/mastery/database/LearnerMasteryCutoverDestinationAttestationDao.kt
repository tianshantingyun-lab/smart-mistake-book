package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query

@Dao
internal abstract class LearnerMasteryCutoverDestinationAttestationDao {
    @Query(
        """
        SELECT *
        FROM mastery_source_fact
        WHERE learner_id = :learnerId
          AND (:afterExclusive IS NULL OR source_fact_id > :afterExclusive)
        ORDER BY source_fact_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSourceFactPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<MasterySourceFactEntity>

    @Query(
        """
        SELECT candidate.candidate_id AS stableKey,
               candidate.canonical_fingerprint AS primaryFingerprint,
               fact.canonical_fingerprint AS sourceFingerprint,
               proof.proof_fingerprint AS proofFingerprint,
               receipt.receipt_fingerprint AS decisionFingerprint,
               event.canonical_fingerprint AS eventFingerprint,
               applied.application_fingerprint AS applicationFingerprint,
               CASE
                 WHEN fact.source_fact_id IS NULL
                   OR receipt.candidate_id IS NULL THEN 0
                 WHEN candidate.learner_id != :learnerId
                   OR fact.learner_id != candidate.learner_id
                   OR fact.subject != candidate.subject
                   OR fact.source_policy_version != :sourcePolicyVersion
                   OR candidate.requested_policy_version !=
                      :projectionPolicyVersion
                   OR receipt.candidate_canonical_fingerprint !=
                      candidate.canonical_fingerprint
                   OR receipt.learner_id != candidate.learner_id
                   OR receipt.admission_policy_version !=
                      :admissionPolicyVersion
                   OR receipt.calibration_version != :calibrationVersion
                   OR receipt.source_proof_fingerprint IS NOT
                      proof.proof_fingerprint
                   OR receipt.disposition NOT IN ('ADMITTED', 'DUPLICATE', 'INERT')
                   OR receipt.disposition = 'CONFLICT' THEN 0
                 WHEN proof.source_fact_id IS NOT NULL
                   AND (
                     proof.source_fact_canonical_fingerprint !=
                        fact.canonical_fingerprint
                     OR proof.policy_supported = 0
                     OR proof.source_policy_version != :sourcePolicyVersion
                   ) THEN 0
                 WHEN receipt.disposition = 'ADMITTED'
                   AND (
                     proof.source_fact_id IS NULL
                     OR event.event_id IS NULL
                     OR receipt.event_id != event.event_id
                     OR event.candidate_id != candidate.candidate_id
                     OR event.source_fact_id != fact.source_fact_id
                     OR event.source_proof_fingerprint != proof.proof_fingerprint
                     OR event.learner_id != candidate.learner_id
                     OR event.subject != candidate.subject
                     OR event.admission_policy_version !=
                        :admissionPolicyVersion
                     OR event.calibration_version != :calibrationVersion
                     OR event.projection_policy_version !=
                        :projectionPolicyVersion
                     OR applied.event_id IS NULL
                     OR applied.event_canonical_fingerprint !=
                        event.canonical_fingerprint
                     OR applied.learner_id != event.learner_id
                     OR applied.event_sequence != event.event_sequence
                     OR applied.projection_policy_version !=
                        :projectionPolicyVersion
                     OR (
                       SELECT COUNT(*)
                       FROM mastery_learning_event_attribution AS attribution
                       WHERE attribution.event_id = event.event_id
                     ) = 0
                   ) THEN 0
                 WHEN receipt.disposition = 'INERT'
                   AND (receipt.event_id IS NOT NULL OR event.event_id IS NOT NULL) THEN 0
                 WHEN receipt.disposition = 'DUPLICATE'
                   AND receipt.event_id IS NOT NULL
                   AND event.event_id IS NULL THEN 0
                 WHEN fact.outcome = 'PENDING_REVIEW'
                   AND NOT (
                     EXISTS (
                       SELECT 1
                       FROM mastery_evidence_review_case AS review_case
                       INNER JOIN mastery_evidence_review_resolution AS resolution
                         ON resolution.review_case_id = review_case.review_case_id
                       WHERE review_case.candidate_id = candidate.candidate_id
                         AND review_case.learner_id = candidate.learner_id
                         AND review_case.subject = candidate.subject
                         AND resolution.learner_id = review_case.learner_id
                         AND resolution.subject = review_case.subject
                     )
                     OR EXISTS (
                       SELECT 1
                       FROM mastery_open_response_dedicated_decision AS dedicated
                       JOIN mastery_open_response_model_evaluation_attestation AS attestation
                         ON attestation.attestation_fingerprint =
                            dedicated.attestation_fingerprint
                       WHERE dedicated.disposition = 'ACCEPTED'
                         AND dedicated.candidate_id = candidate.candidate_id
                         AND dedicated.source_fact_id = fact.source_fact_id
                         AND dedicated.learner_id = candidate.learner_id
                         AND dedicated.subject = candidate.subject
                         AND attestation.receipt_fingerprint = dedicated.receipt_fingerprint
                     )
                   ) THEN 0
                 ELSE 1
               END AS consistent
        FROM mastery_observation_candidate AS candidate
        LEFT JOIN mastery_source_fact AS fact
          ON fact.source_fact_id = candidate.source_fact_id
        LEFT JOIN mastery_source_proof AS proof
          ON proof.source_fact_id = fact.source_fact_id
        LEFT JOIN mastery_admission_receipt AS receipt
          ON receipt.candidate_id = candidate.candidate_id
        LEFT JOIN mastery_learning_event AS event
          ON event.event_id = receipt.event_id
        LEFT JOIN mastery_applied_event AS applied
          ON applied.event_id = event.event_id
        WHERE candidate.learner_id = :learnerId
          AND (:afterExclusive IS NULL OR candidate.candidate_id > :afterExclusive)
        ORDER BY candidate.candidate_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readCandidatePage(
        learnerId: String,
        sourcePolicyVersion: String,
        admissionPolicyVersion: String,
        calibrationVersion: String,
        projectionPolicyVersion: String,
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverCandidateRow>

    @Query(
        """
        SELECT stableKey, primaryFingerprint, nodeFingerprint,
               auxiliaryFingerprint, scalarOne, scalarTwo, consistent
        FROM (
          SELECT '0:' || hex(candidate.candidate_id) || ':' ||
                   printf('%010d', attribution.ordinal) AS stableKey,
                 candidate.canonical_fingerprint AS primaryFingerprint,
                 attribution.knowledge_node_ref_fingerprint AS nodeFingerprint,
                 attribution.proposal_fingerprint AS auxiliaryFingerprint,
                 attribution.role AS scalarOne,
                 attribution.certainty AS scalarTwo,
                 CASE
                   WHEN candidate.learner_id != :learnerId
                     OR attribution.subject != candidate.subject
                     OR attribution.ordinal < 0
                     OR (
                       attribution.problem_binding_ref_fingerprint IS NULL
                     ) != (
                       attribution.binding_problem_revision_ref_fingerprint IS NULL
                     ) THEN 0
                   WHEN attribution.problem_binding_ref_fingerprint IS NOT NULL
                     AND NOT EXISTS (
                       SELECT 1
                       FROM mastery_problem_binding_authority AS binding
                       WHERE binding.binding_ref_fingerprint =
                          attribution.problem_binding_ref_fingerprint
                         AND binding.problem_revision_ref_fingerprint =
                          attribution.binding_problem_revision_ref_fingerprint
                         AND binding.learner_id = candidate.learner_id
                         AND binding.subject = attribution.subject
                         AND binding.knowledge_node_id =
                          attribution.knowledge_node_id
                         AND binding.taxonomy_version =
                          attribution.taxonomy_version
                         AND binding.knowledge_pack_version =
                          attribution.knowledge_pack_version
                         AND binding.knowledge_node_ref_fingerprint =
                          attribution.knowledge_node_ref_fingerprint
                     ) THEN 0
                   ELSE 1
                 END AS consistent
          FROM mastery_candidate_attribution AS attribution
          INNER JOIN mastery_observation_candidate AS candidate
            ON candidate.candidate_id = attribution.candidate_id
          WHERE candidate.learner_id = :learnerId

          UNION ALL

          SELECT '1:' || hex(event.event_id) || ':' ||
                   printf('%010d', attribution.ordinal) AS stableKey,
                 event.canonical_fingerprint AS primaryFingerprint,
                 attribution.knowledge_node_ref_fingerprint AS nodeFingerprint,
                 NULL AS auxiliaryFingerprint,
                 CAST(attribution.evidence_mass_micros AS TEXT) AS scalarOne,
                 event.direction AS scalarTwo,
                 CASE
                   WHEN event.learner_id != :learnerId
                     OR attribution.subject != event.subject
                     OR attribution.ordinal < 0
                     OR attribution.evidence_mass_micros <= 0
                     OR (
                       NOT EXISTS (
                         SELECT 1
                         FROM mastery_candidate_attribution AS proposed
                         WHERE proposed.candidate_id = event.candidate_id
                           AND proposed.subject = attribution.subject
                           AND proposed.knowledge_node_id =
                            attribution.knowledge_node_id
                           AND proposed.taxonomy_version =
                            attribution.taxonomy_version
                           AND proposed.knowledge_pack_version =
                            attribution.knowledge_pack_version
                           AND proposed.knowledge_node_ref_fingerprint =
                            attribution.knowledge_node_ref_fingerprint
                       )
                       AND NOT EXISTS (
                         SELECT 1
                         FROM mastery_open_response_dedicated_decision AS dedicated
                         JOIN mastery_open_response_model_evaluation_attestation AS attestation
                           ON attestation.attestation_fingerprint =
                              dedicated.attestation_fingerprint
                          AND attestation.receipt_fingerprint =
                              dedicated.receipt_fingerprint
                         JOIN mastery_open_response_evaluation_knowledge_scope AS open_scope
                           ON open_scope.attestation_fingerprint =
                              attestation.attestation_fingerprint
                          AND open_scope.subject = attribution.subject
                          AND open_scope.knowledge_node_id = attribution.knowledge_node_id
                          AND open_scope.taxonomy_version = attribution.taxonomy_version
                          AND open_scope.knowledge_pack_version =
                              attribution.knowledge_pack_version
                          AND open_scope.knowledge_node_ref_fingerprint =
                              attribution.knowledge_node_ref_fingerprint
                         WHERE dedicated.disposition = 'ACCEPTED'
                           AND dedicated.accepted_event_id = event.event_id
                           AND dedicated.candidate_id = event.candidate_id
                           AND dedicated.source_fact_id = event.source_fact_id
                           AND dedicated.learner_id = event.learner_id
                           AND dedicated.subject = event.subject
                           AND dedicated.direction = event.direction
                           AND (
                             (event.direction = 'POSITIVE' AND
                              open_scope.evaluation_role = 'SUPPORTED_CORRECTNESS')
                             OR
                             (event.direction = 'NEGATIVE' AND
                              open_scope.evaluation_role = 'LOCATED_GAP')
                           )
                       )
                     ) THEN 0
                   ELSE 1
                 END AS consistent
          FROM mastery_learning_event_attribution AS attribution
          INNER JOIN mastery_learning_event AS event
            ON event.event_id = attribution.event_id
          WHERE event.learner_id = :learnerId
        ) AS attribution_rows
        WHERE (:afterExclusive IS NULL OR stableKey > :afterExclusive)
        ORDER BY stableKey ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readAttributionPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverAttributionRow>

    @Query(
        """
        SELECT stableKey, primaryFingerprint, secondaryFingerprint,
               scalarOne, scalarTwo, consistent
        FROM (
          SELECT '0:' || hex(state.problem_revision_ref_fingerprint) AS stableKey,
                 state.envelope_canonical_fingerprint AS primaryFingerprint,
                 state.payload_canonical_fingerprint AS secondaryFingerprint,
                 CAST(state.binding_set_version AS TEXT) AS scalarOne,
                 CAST(state.binding_protocol_version AS TEXT) AS scalarTwo,
                 CASE
                   WHEN state.learner_id != :learnerId
                     OR inbox.event_id IS NULL
                     OR inbox.processed_at_epoch_millis IS NULL
                     OR inbox.source_store_generation !=
                        state.source_store_generation
                     OR inbox.envelope_canonical_fingerprint !=
                        state.envelope_canonical_fingerprint
                     OR inbox.payload_canonical_fingerprint !=
                        state.payload_canonical_fingerprint
                     OR state.binding_set_version <= 0
                     OR state.binding_protocol_version <= 0 THEN 0
                   ELSE 1
                 END AS consistent
          FROM mastery_problem_binding_authority_state AS state
          LEFT JOIN mastery_cross_store_inbox AS inbox
            ON inbox.event_id = state.inbox_event_id
          WHERE state.learner_id = :learnerId

          UNION ALL

          SELECT '1:' || hex(binding.binding_ref_fingerprint) AS stableKey,
                 binding.binding_ref_fingerprint AS primaryFingerprint,
                 binding.knowledge_node_ref_fingerprint AS secondaryFingerprint,
                 CAST(binding.binding_set_version AS TEXT) AS scalarOne,
                 CAST(binding.binding_protocol_version AS TEXT) AS scalarTwo,
                 CASE
                   WHEN binding.learner_id != :learnerId
                     OR state.problem_revision_ref_fingerprint IS NULL
                     OR binding.inbox_event_id != state.inbox_event_id
                     OR binding.source_store_generation !=
                        state.source_store_generation
                     OR binding.envelope_canonical_fingerprint !=
                        state.envelope_canonical_fingerprint
                     OR binding.payload_canonical_fingerprint !=
                        state.payload_canonical_fingerprint
                     OR binding.binding_protocol_version !=
                        state.binding_protocol_version
                     OR binding.binding_set_version != state.binding_set_version
                     OR binding.learner_id != state.learner_id
                     OR binding.subject != state.subject
                     OR binding.accepted_at_epoch_millis <
                        state.changed_at_epoch_millis THEN 0
                   ELSE 1
                 END AS consistent
          FROM mastery_problem_binding_authority AS binding
          LEFT JOIN mastery_problem_binding_authority_state AS state
            ON state.problem_revision_ref_fingerprint =
               binding.problem_revision_ref_fingerprint
          WHERE binding.learner_id = :learnerId
        ) AS binding_rows
        WHERE (:afterExclusive IS NULL OR stableKey > :afterExclusive)
        ORDER BY stableKey ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readProblemBindingPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverBindingRow>

    @Query(
        """
        SELECT supersession.supersession_id AS stableKey,
               supersession.canonical_fingerprint AS primaryFingerprint,
               supersession.original_event_canonical_fingerprint AS originalFingerprint,
               supersession.replacement_source_fact_canonical_fingerprint AS replacementFingerprint,
               CASE
                 WHEN supersession.learner_id != :learnerId
                   OR original_fact.source_fact_id IS NULL
                   OR original_event.event_id IS NULL
                   OR replacement_fact.source_fact_id IS NULL
                   OR replacement_event.event_id IS NULL
                   OR original_fact.learner_id != supersession.learner_id
                   OR original_event.learner_id != supersession.learner_id
                   OR replacement_fact.learner_id != supersession.learner_id
                   OR replacement_event.learner_id != supersession.learner_id
                   OR original_fact.canonical_fingerprint !=
                      supersession.original_source_fact_canonical_fingerprint
                   OR original_event.canonical_fingerprint !=
                      supersession.original_event_canonical_fingerprint
                   OR replacement_fact.canonical_fingerprint !=
                      supersession.replacement_source_fact_canonical_fingerprint
                   OR replacement_event.candidate_id !=
                      supersession.replacement_candidate_id THEN 0
                 ELSE 1
               END AS consistent
        FROM mastery_learning_evidence_supersession AS supersession
        LEFT JOIN mastery_source_fact AS original_fact
          ON original_fact.source_fact_id = supersession.original_source_fact_id
        LEFT JOIN mastery_learning_event AS original_event
          ON original_event.event_id = supersession.original_event_id
        LEFT JOIN mastery_source_fact AS replacement_fact
          ON replacement_fact.source_fact_id =
             supersession.replacement_source_fact_id
        LEFT JOIN mastery_learning_event AS replacement_event
          ON replacement_event.event_id = supersession.replacement_event_id
        WHERE supersession.learner_id = :learnerId
          AND (:afterExclusive IS NULL
            OR supersession.supersession_id > :afterExclusive)
        ORDER BY supersession.supersession_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSupersessionPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverSupersessionRow>

    @Query(
        """
        SELECT printf('%020d:%010d', destination.batch_sequence,
                      destination.observation_ordinal) AS stableKey,
               destination.destination_record_canonical_fingerprint AS primaryFingerprint,
               destination.source_fact_canonical_fingerprint AS sourceFingerprint,
               destination.candidate_canonical_fingerprint AS candidateFingerprint,
               CASE
                 WHEN destination.learner_id != :learnerId
                   OR destination.source_generation != :sourceGeneration
                   OR checkpoint.learner_id IS NULL
                   OR fact.source_fact_id IS NULL
                   OR candidate.candidate_id IS NULL
                   OR destination.source_fact_canonical_fingerprint !=
                      fact.canonical_fingerprint
                   OR destination.candidate_canonical_fingerprint !=
                      candidate.canonical_fingerprint
                   OR candidate.source_fact_id != destination.source_fact_id
                   OR checkpoint.final_batch NOT IN (0, 1) THEN 0
                 ELSE 1
               END AS consistent
        FROM mastery_legacy_fact_migration_destination_record AS destination
        LEFT JOIN mastery_legacy_fact_migration_checkpoint AS checkpoint
          ON checkpoint.learner_id = destination.learner_id
         AND checkpoint.source_generation = destination.source_generation
         AND checkpoint.batch_sequence = destination.batch_sequence
        LEFT JOIN mastery_source_fact AS fact
          ON fact.source_fact_id = destination.source_fact_id
        LEFT JOIN mastery_observation_candidate AS candidate
          ON candidate.candidate_id = destination.candidate_id
        WHERE destination.learner_id = :learnerId
          AND destination.source_generation = :sourceGeneration
          AND (:afterExclusive IS NULL OR
               printf('%020d:%010d', destination.batch_sequence,
                      destination.observation_ordinal) > :afterExclusive)
        ORDER BY stableKey ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readMigrationDestinationPage(
        learnerId: String,
        sourceGeneration: String,
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverMigrationRow>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM mastery_source_fact
             WHERE learner_id = :learnerId) AS sourceFactCount,
          (SELECT COUNT(*) FROM mastery_source_proof AS proof
             INNER JOIN mastery_source_fact AS fact
               ON fact.source_fact_id = proof.source_fact_id
             WHERE fact.learner_id = :learnerId) AS sourceProofCount,
          (SELECT COUNT(*) FROM mastery_observation_candidate
             WHERE learner_id = :learnerId) AS candidateCount,
          (SELECT COUNT(*) FROM mastery_learning_event
             WHERE learner_id = :learnerId) AS eventCount,
          (SELECT COUNT(*) FROM mastery_applied_event
             WHERE learner_id = :learnerId) AS appliedEventCount,
          (SELECT COUNT(*) FROM mastery_learning_event_attribution AS attribution
             INNER JOIN mastery_learning_event AS event
               ON event.event_id = attribution.event_id
             WHERE event.learner_id = :learnerId) AS eventAttributionCount,
          (SELECT COUNT(*) FROM mastery_problem_binding_authority
             WHERE learner_id = :learnerId) AS bindingCount,
          (SELECT COUNT(*) FROM mastery_problem_binding_authority_state
             WHERE learner_id = :learnerId) AS bindingStateCount,
          (SELECT COUNT(*) FROM mastery_learning_evidence_supersession
             WHERE learner_id = :learnerId) AS supersessionCount,
          (SELECT COUNT(*)
             FROM mastery_legacy_fact_migration_destination_record
             WHERE learner_id = :learnerId
               AND source_generation = :sourceGeneration) AS migrationRecordCount,
          (SELECT COALESCE(MAX(event_sequence), 0)
             FROM mastery_learning_event
             WHERE learner_id = :learnerId) AS maxEventSequence,
          (SELECT COALESCE(last_allocated_sequence, 0)
             FROM mastery_ledger_sequence
             WHERE learner_id = :learnerId
             LIMIT 1) AS allocatedEventSequence,
          (SELECT COUNT(*) FROM mastery_admission_receipt
             WHERE learner_id = :learnerId
               AND disposition = 'CONFLICT') AS conflictReceiptCount,
          (SELECT COUNT(*)
             FROM mastery_evidence_review_case AS review_case
             LEFT JOIN mastery_evidence_review_resolution AS resolution
               ON resolution.review_case_id = review_case.review_case_id
             WHERE review_case.learner_id = :learnerId
               AND resolution.review_case_id IS NULL
               AND NOT EXISTS (
                 SELECT 1
                 FROM mastery_open_response_dedicated_decision AS dedicated
                 WHERE dedicated.review_case_id = review_case.review_case_id
                   AND dedicated.candidate_id = review_case.candidate_id
                   AND dedicated.learner_id = review_case.learner_id
                   AND dedicated.subject = review_case.subject
                   AND dedicated.disposition = 'ACCEPTED'
               )) AS unresolvedReviewCount,
          (SELECT COUNT(*) FROM mastery_cross_store_inbox
             WHERE processed_at_epoch_millis IS NULL) AS pendingInboxCount,
          (SELECT COUNT(*) FROM mastery_cross_store_outbox
             WHERE learner_id = :learnerId
               AND published_at_epoch_millis IS NULL) AS pendingOutboxCount,
          (SELECT COUNT(*) FROM mastery_observation_candidate AS candidate
             LEFT JOIN mastery_source_fact AS fact
               ON fact.source_fact_id = candidate.source_fact_id
             LEFT JOIN mastery_source_proof AS proof
               ON proof.source_fact_id = fact.source_fact_id
             LEFT JOIN mastery_admission_receipt AS receipt
               ON receipt.candidate_id = candidate.candidate_id
             LEFT JOIN mastery_learning_event AS event
               ON event.event_id = receipt.event_id
             LEFT JOIN mastery_applied_event AS applied
               ON applied.event_id = event.event_id
             WHERE candidate.learner_id = :learnerId
               AND (
                 fact.source_fact_id IS NULL
                 OR receipt.candidate_id IS NULL
                 OR fact.learner_id != candidate.learner_id
                 OR fact.subject != candidate.subject
                 OR fact.source_policy_version != :sourcePolicyVersion
                 OR receipt.source_proof_fingerprint IS NOT
                    proof.proof_fingerprint
                 OR (
                   proof.source_fact_id IS NOT NULL
                   AND (
                     proof.source_fact_canonical_fingerprint !=
                        fact.canonical_fingerprint
                     OR proof.policy_supported = 0
                     OR proof.source_policy_version != :sourcePolicyVersion
                   )
                 )
                 OR candidate.requested_policy_version !=
                    :projectionPolicyVersion
                 OR receipt.disposition = 'CONFLICT'
                 OR receipt.admission_policy_version != :admissionPolicyVersion
                 OR receipt.calibration_version != :calibrationVersion
                 OR (
                   receipt.disposition = 'ADMITTED'
                   AND (
                     proof.source_fact_id IS NULL
                     OR event.event_id IS NULL
                     OR event.candidate_id != candidate.candidate_id
                     OR event.projection_policy_version !=
                        :projectionPolicyVersion
                     OR event.admission_policy_version !=
                        :admissionPolicyVersion
                     OR event.calibration_version != :calibrationVersion
                     OR applied.event_id IS NULL
                     OR applied.projection_policy_version !=
                        :projectionPolicyVersion
                   )
                 )
                 OR (
                   receipt.disposition = 'INERT'
                   AND receipt.event_id IS NOT NULL
                 )
               )) AS invalidCandidateCount,
          (SELECT COUNT(*)
             FROM mastery_learning_event_attribution AS attribution
             INNER JOIN mastery_learning_event AS event
               ON event.event_id = attribution.event_id
             WHERE event.learner_id = :learnerId
               AND (
                 attribution.subject != event.subject
                 OR attribution.evidence_mass_micros <= 0
                 OR (
                   NOT EXISTS (
                     SELECT 1
                     FROM mastery_candidate_attribution AS proposed
                     WHERE proposed.candidate_id = event.candidate_id
                       AND proposed.knowledge_node_ref_fingerprint =
                          attribution.knowledge_node_ref_fingerprint
                   )
                   AND NOT EXISTS (
                     SELECT 1
                     FROM mastery_open_response_dedicated_decision AS dedicated
                     JOIN mastery_open_response_model_evaluation_attestation AS attestation
                       ON attestation.attestation_fingerprint =
                          dedicated.attestation_fingerprint
                      AND attestation.receipt_fingerprint = dedicated.receipt_fingerprint
                     JOIN mastery_open_response_evaluation_knowledge_scope AS open_scope
                       ON open_scope.attestation_fingerprint =
                          attestation.attestation_fingerprint
                      AND open_scope.subject = attribution.subject
                      AND open_scope.knowledge_node_ref_fingerprint =
                          attribution.knowledge_node_ref_fingerprint
                     WHERE dedicated.disposition = 'ACCEPTED'
                       AND dedicated.accepted_event_id = event.event_id
                       AND dedicated.candidate_id = event.candidate_id
                       AND dedicated.source_fact_id = event.source_fact_id
                       AND dedicated.learner_id = event.learner_id
                       AND dedicated.subject = event.subject
                       AND dedicated.direction = event.direction
                       AND (
                         (event.direction = 'POSITIVE' AND
                          open_scope.evaluation_role = 'SUPPORTED_CORRECTNESS')
                         OR
                         (event.direction = 'NEGATIVE' AND
                          open_scope.evaluation_role = 'LOCATED_GAP')
                       )
                   )
                 )
               )) AS invalidAttributionCount,
          (SELECT COUNT(*)
             FROM mastery_problem_binding_authority AS binding
             LEFT JOIN mastery_problem_binding_authority_state AS state
               ON state.problem_revision_ref_fingerprint =
                  binding.problem_revision_ref_fingerprint
             WHERE binding.learner_id = :learnerId
               AND (
                 state.problem_revision_ref_fingerprint IS NULL
                 OR state.inbox_event_id != binding.inbox_event_id
                 OR state.binding_set_version != binding.binding_set_version
                 OR state.learner_id != binding.learner_id
                 OR state.subject != binding.subject
               )) AS invalidBindingCount,
          (SELECT COUNT(*)
             FROM mastery_legacy_fact_migration_destination_record AS destination
             LEFT JOIN mastery_source_fact AS fact
               ON fact.source_fact_id = destination.source_fact_id
             LEFT JOIN mastery_observation_candidate AS candidate
               ON candidate.candidate_id = destination.candidate_id
             WHERE destination.learner_id = :learnerId
               AND destination.source_generation = :sourceGeneration
               AND (
                 fact.source_fact_id IS NULL
                 OR candidate.candidate_id IS NULL
                 OR fact.canonical_fingerprint !=
                    destination.source_fact_canonical_fingerprint
                 OR candidate.canonical_fingerprint !=
                    destination.candidate_canonical_fingerprint
               )) AS invalidMigrationCount,
          (SELECT COUNT(*) FROM mastery_cutover_fence) AS cutoverFenceCount,
          (SELECT COUNT(*) FROM mastery_cutover_completion_receipt)
             AS completionReceiptCount
        """,
    )
    abstract suspend fun readEventBindingHealth(
        learnerId: String,
        sourceGeneration: String,
        sourcePolicyVersion: String,
        admissionPolicyVersion: String,
        calibrationVersion: String,
        projectionPolicyVersion: String,
    ): LearnerMasteryCutoverEventBindingHealthRow

    @Query(
        """
        SELECT * FROM mastery_projection_generation
        WHERE state = 'ACTIVE'
        ORDER BY generation_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun readActiveProjectionGeneration():
        MasteryProjectionGenerationEntity?

    @Query(
        "SELECT * FROM mastery_projection_budget_rebuild_receipt " +
            "WHERE generation_id = :generationId LIMIT 1",
    )
    abstract suspend fun readProjectionBudgetRebuildReceipt(
        generationId: Long,
    ): ProjectionBudgetRebuildReceiptEntity?

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM mastery_presentation_node_budget
             WHERE direction NOT IN ('POSITIVE', 'NEGATIVE')) +
          (SELECT COUNT(*) FROM mastery_problem_family_node_budget
             WHERE direction NOT IN ('POSITIVE', 'NEGATIVE')) +
          (SELECT COUNT(*) FROM mastery_presentation_node_budget_shadow
             WHERE direction NOT IN ('POSITIVE', 'NEGATIVE')) +
          (SELECT COUNT(*) FROM mastery_problem_family_node_budget_shadow
             WHERE direction NOT IN ('POSITIVE', 'NEGATIVE'))
        """,
    )
    abstract suspend fun countInvalidDirectionalBudgetRows(): Long

    @Query(
        """
        SELECT *
        FROM mastery_knowledge_projection
        WHERE learner_id = :learnerId
          AND (:afterExclusive IS NULL OR
               hex(learner_id) || ':' || hex(subject) || ':' ||
               hex(knowledge_node_id) || ':' || hex(taxonomy_version) >
               :afterExclusive)
        ORDER BY hex(learner_id) || ':' || hex(subject) || ':' ||
                 hex(knowledge_node_id) || ':' || hex(taxonomy_version) ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readProjectionPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<MasteryKnowledgeProjectionEntity>

    @Query(
        """
        SELECT *
        FROM mastery_subject_digest
        WHERE learner_id = :learnerId
          AND (:afterExclusive IS NULL OR
               hex(learner_id) || ':' || hex(subject) > :afterExclusive)
        ORDER BY hex(learner_id) || ':' || hex(subject) ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSubjectDigestPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<MasterySubjectDigestEntity>

    @Query(
        """
        SELECT *
        FROM mastery_presentation_node_budget
        WHERE learner_id = :learnerId
          AND (:afterExclusive IS NULL OR
               hex(learner_id) || ':' || hex(presentation_id) || ':' ||
               hex(subject) || ':' || hex(knowledge_node_id) || ':' ||
               hex(taxonomy_version) || ':' || hex(direction) > :afterExclusive)
        ORDER BY hex(learner_id) || ':' || hex(presentation_id) || ':' ||
                 hex(subject) || ':' || hex(knowledge_node_id) || ':' ||
                 hex(taxonomy_version) || ':' || hex(direction) ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readPresentationBudgetPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<MasteryPresentationNodeBudgetEntity>

    @Query(
        """
        SELECT *
        FROM mastery_problem_family_node_budget
        WHERE learner_id = :learnerId
          AND (:afterExclusive IS NULL OR
               hex(learner_id) || ':' || hex(problem_family_fingerprint) || ':' ||
               hex(subject) || ':' || hex(knowledge_node_id) || ':' ||
               hex(taxonomy_version) || ':' || hex(direction) > :afterExclusive)
        ORDER BY hex(learner_id) || ':' || hex(problem_family_fingerprint) || ':' ||
                 hex(subject) || ':' || hex(knowledge_node_id) || ':' ||
                 hex(taxonomy_version) || ':' || hex(direction) ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readProblemFamilyBudgetPage(
        learnerId: String,
        afterExclusive: String?,
        limit: Int,
    ): List<MasteryProblemFamilyNodeBudgetEntity>

    @Query(
        """
        SELECT type || ':' || name AS stableKey, type, name, sql
        FROM sqlite_schema
        WHERE type IN ('index', 'trigger')
          AND name NOT LIKE 'sqlite_%'
          AND (name LIKE 'index_mastery_%'
            OR name LIKE 'immutable_mastery_%'
            OR name LIKE 'sealed_mastery_%'
            OR name LIKE 'validate_mastery_%')
          AND (:afterExclusive IS NULL OR type || ':' || name > :afterExclusive)
        ORDER BY stableKey ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readSchemaObjectPage(
        afterExclusive: String?,
        limit: Int,
    ): List<LearnerMasteryCutoverSchemaObjectRow>

    @Query(
        """
        SELECT type || ':' || name AS stableKey, type, name, sql
        FROM sqlite_schema
        WHERE type IN ('index', 'trigger')
          AND name NOT LIKE 'sqlite_%'
          AND (name LIKE 'index_mastery_%'
            OR name LIKE 'immutable_mastery_%'
            OR name LIKE 'sealed_mastery_%'
            OR name LIKE 'validate_mastery_%')
        ORDER BY stableKey ASC
        """,
    )
    abstract suspend fun readAllSchemaObjects():
        List<LearnerMasteryCutoverSchemaObjectRow>

    @Query(
        """
        SELECT
          (SELECT COUNT(*) FROM mastery_projection_generation
             WHERE state = 'ACTIVE') AS activeGenerationCount,
          (SELECT COUNT(*) FROM mastery_projection_generation
             WHERE state = 'BUILDING') AS buildingGenerationCount,
          (SELECT COUNT(*) FROM mastery_learning_event
             WHERE learner_id = :learnerId) AS sourceEventCount,
          ((SELECT COUNT(*) FROM mastery_learning_evidence_supersession
              WHERE learner_id = :learnerId) +
           (SELECT COUNT(*)
              FROM mastery_open_response_legacy_quarantine AS quarantine
              JOIN mastery_learning_event AS event
                ON event.event_id = quarantine.accepted_event_id
             WHERE event.learner_id = :learnerId)) AS sourceSupersessionCount,
          (SELECT COUNT(*) FROM mastery_knowledge_projection
             WHERE learner_id = :learnerId) AS projectionCount,
          (SELECT COUNT(*) FROM mastery_subject_digest
             WHERE learner_id = :learnerId) AS subjectDigestCount,
          (SELECT COUNT(*) FROM mastery_presentation_node_budget
             WHERE learner_id = :learnerId) AS presentationBudgetCount,
          (SELECT COUNT(*) FROM mastery_problem_family_node_budget
             WHERE learner_id = :learnerId) AS problemFamilyBudgetCount,
          (SELECT COUNT(*) FROM mastery_projection_shadow
             WHERE generation_id = :generationId
               AND learner_id = :learnerId) AS activeShadowProjectionCount,
          (SELECT COUNT(*) FROM mastery_subject_digest_shadow
             WHERE generation_id = :generationId
               AND learner_id = :learnerId) AS activeShadowDigestCount,
          (SELECT COUNT(*) FROM mastery_presentation_node_budget_shadow
             WHERE generation_id = :generationId
               AND learner_id = :learnerId) AS activeShadowPresentationBudgetCount,
          (SELECT COUNT(*) FROM mastery_problem_family_node_budget_shadow
             WHERE generation_id = :generationId
               AND learner_id = :learnerId) AS activeShadowProblemFamilyBudgetCount,
          ((SELECT COUNT(*) FROM mastery_projection_shadow AS shadow
              LEFT JOIN mastery_projection_generation AS generation
                ON generation.generation_id = shadow.generation_id
              WHERE generation.generation_id IS NULL
                 OR generation.state IN ('BUILDING', 'BLOCKED')) +
           (SELECT COUNT(*) FROM mastery_subject_digest_shadow AS shadow
              LEFT JOIN mastery_projection_generation AS generation
                ON generation.generation_id = shadow.generation_id
              WHERE generation.generation_id IS NULL
                 OR generation.state IN ('BUILDING', 'BLOCKED')) +
           (SELECT COUNT(*) FROM mastery_presentation_node_budget_shadow AS shadow
              LEFT JOIN mastery_projection_generation AS generation
                ON generation.generation_id = shadow.generation_id
              WHERE generation.generation_id IS NULL
                 OR generation.state IN ('BUILDING', 'BLOCKED')) +
           (SELECT COUNT(*) FROM mastery_problem_family_node_budget_shadow AS shadow
              LEFT JOIN mastery_projection_generation AS generation
                ON generation.generation_id = shadow.generation_id
              WHERE generation.generation_id IS NULL
                 OR generation.state IN ('BUILDING', 'BLOCKED')))
             AS nonActiveShadowCount,
          (SELECT COUNT(*)
             FROM mastery_knowledge_projection AS projection
             LEFT JOIN mastery_projection_shadow AS shadow
               ON shadow.generation_id = :generationId
              AND shadow.learner_id = projection.learner_id
              AND shadow.subject = projection.subject
              AND shadow.knowledge_node_id = projection.knowledge_node_id
              AND shadow.taxonomy_version = projection.taxonomy_version
             WHERE projection.learner_id = :learnerId
               AND (
                 shadow.learner_id IS NULL
                 OR shadow.latest_evidence_knowledge_pack_version !=
                    projection.latest_evidence_knowledge_pack_version
                 OR shadow.stable_node_identity_fingerprint !=
                    projection.stable_node_identity_fingerprint
                 OR shadow.positive_evidence_micros !=
                    projection.positive_evidence_micros
                 OR shadow.negative_evidence_micros !=
                    projection.negative_evidence_micros
                 OR shadow.mastery_score_micros != projection.mastery_score_micros
                 OR shadow.mastery_state != projection.mastery_state
                 OR shadow.trend != projection.trend
                 OR shadow.observation_count != projection.observation_count
                 OR shadow.memory_stability_millis !=
                    projection.memory_stability_millis
                 OR shadow.recall_due_at_epoch_millis !=
                    projection.recall_due_at_epoch_millis
                 OR shadow.last_positive_at_epoch_millis IS NOT
                    projection.last_positive_at_epoch_millis
                 OR shadow.last_negative_at_epoch_millis IS NOT
                    projection.last_negative_at_epoch_millis
                 OR shadow.last_evidence_at_epoch_millis !=
                    projection.last_evidence_at_epoch_millis
                 OR shadow.last_event_sequence != projection.last_event_sequence
                 OR shadow.last_ordered_event_id != projection.last_ordered_event_id
                 OR shadow.projection_policy_version !=
                    projection.projection_policy_version
                 OR shadow.evidence_quality_micros !=
                    projection.evidence_quality_micros
                 OR shadow.independent_problem_family_count !=
                    projection.independent_problem_family_count
                 OR shadow.distinct_presentation_count !=
                    projection.distinct_presentation_count
                 OR shadow.historical_log_odds_micros IS NOT
                    projection.historical_log_odds_micros
                 OR shadow.calibration_snapshot_fingerprint IS NOT
                    projection.calibration_snapshot_fingerprint
                 OR shadow.calibration_profile_id IS NOT
                    projection.calibration_profile_id
                 OR shadow.calibration_version IS NOT projection.calibration_version
                 OR shadow.recall_familiarizing_at_epoch_millis IS NOT
                    projection.recall_familiarizing_at_epoch_millis
                 OR shadow.recall_reinforcement_at_epoch_millis IS NOT
                    projection.recall_reinforcement_at_epoch_millis
               )) AS projectionShadowMismatchCount,
          (SELECT COUNT(*)
             FROM mastery_subject_digest AS digest
             LEFT JOIN mastery_subject_digest_shadow AS shadow
               ON shadow.generation_id = :generationId
              AND shadow.learner_id = digest.learner_id
              AND shadow.subject = digest.subject
             WHERE digest.learner_id = :learnerId
               AND (
                 shadow.learner_id IS NULL
                 OR shadow.needs_reinforcement_count !=
                    digest.needs_reinforcement_count
                 OR shadow.familiarizing_count != digest.familiarizing_count
                 OR shadow.steady_count != digest.steady_count
                 OR shadow.last_event_sequence != digest.last_event_sequence
                 OR shadow.updated_at_epoch_millis != digest.updated_at_epoch_millis
                 OR shadow.projection_policy_version !=
                    digest.projection_policy_version
               )) AS digestShadowMismatchCount,
          (SELECT COUNT(*)
             FROM mastery_presentation_node_budget AS budget
             LEFT JOIN mastery_presentation_node_budget_shadow AS shadow
               ON shadow.generation_id = :generationId
              AND shadow.learner_id = budget.learner_id
              AND shadow.presentation_id = budget.presentation_id
              AND shadow.subject = budget.subject
              AND shadow.knowledge_node_id = budget.knowledge_node_id
              AND shadow.taxonomy_version = budget.taxonomy_version
              AND shadow.direction = budget.direction
             WHERE budget.learner_id = :learnerId
               AND (
                 shadow.learner_id IS NULL
                 OR shadow.stable_node_identity_fingerprint !=
                    budget.stable_node_identity_fingerprint
                 OR shadow.consumed_mass_micros != budget.consumed_mass_micros
                 OR shadow.last_event_id != budget.last_event_id
                 OR shadow.updated_at_epoch_millis != budget.updated_at_epoch_millis
               )) AS presentationShadowMismatchCount,
          (SELECT COUNT(*)
             FROM mastery_problem_family_node_budget AS budget
             LEFT JOIN mastery_problem_family_node_budget_shadow AS shadow
               ON shadow.generation_id = :generationId
              AND shadow.learner_id = budget.learner_id
              AND shadow.problem_family_fingerprint =
                  budget.problem_family_fingerprint
              AND shadow.subject = budget.subject
              AND shadow.knowledge_node_id = budget.knowledge_node_id
              AND shadow.taxonomy_version = budget.taxonomy_version
              AND shadow.direction = budget.direction
             WHERE budget.learner_id = :learnerId
               AND (
                 shadow.learner_id IS NULL
                 OR shadow.stable_node_identity_fingerprint !=
                    budget.stable_node_identity_fingerprint
                 OR shadow.observation_count != budget.observation_count
                 OR shadow.consumed_mass_micros != budget.consumed_mass_micros
                 OR shadow.last_event_id != budget.last_event_id
                 OR shadow.updated_at_epoch_millis != budget.updated_at_epoch_millis
               )) AS problemFamilyShadowMismatchCount,
          (SELECT COUNT(*) FROM mastery_knowledge_projection
             WHERE learner_id = :learnerId
               AND (
                 projection_policy_version != :projectionPolicyVersion
                 OR calibration_version IS NULL
                 OR calibration_version != :calibrationVersion
                 OR calibration_profile_id IS NULL
                 OR calibration_snapshot_fingerprint IS NULL
               )) AS staleProjectionCount,
          (SELECT COUNT(*) FROM mastery_subject_digest
             WHERE learner_id = :learnerId
               AND projection_policy_version != :projectionPolicyVersion)
             AS staleDigestCount,
          (SELECT COUNT(*)
             FROM mastery_subject_digest AS digest
             LEFT JOIN (
               SELECT learner_id, subject,
                      SUM(CASE WHEN mastery_state = 'NEEDS_REINFORCEMENT'
                               THEN 1 ELSE 0 END) AS needsCount,
                      SUM(CASE WHEN mastery_state = 'FAMILIARIZING'
                               THEN 1 ELSE 0 END) AS familiarizingCount,
                      SUM(CASE WHEN mastery_state = 'STEADY'
                               THEN 1 ELSE 0 END) AS steadyCount,
                      MAX(last_event_sequence) AS lastSequence
               FROM mastery_knowledge_projection
               WHERE learner_id = :learnerId
               GROUP BY learner_id, subject
             ) AS aggregate
               ON aggregate.learner_id = digest.learner_id
              AND aggregate.subject = digest.subject
             WHERE digest.learner_id = :learnerId
               AND (
                 aggregate.learner_id IS NULL
                 OR digest.needs_reinforcement_count != aggregate.needsCount
                 OR digest.familiarizing_count != aggregate.familiarizingCount
                 OR digest.steady_count != aggregate.steadyCount
                 OR digest.last_event_sequence != aggregate.lastSequence
               )) AS inconsistentDigestCount,
          (SELECT COUNT(*) FROM mastery_knowledge_projection AS projection
             WHERE projection.learner_id = :learnerId
               AND NOT EXISTS (
                 SELECT 1 FROM mastery_subject_digest AS digest
                 WHERE digest.learner_id = projection.learner_id
                   AND digest.subject = projection.subject
               )) AS missingDigestProjectionCount,
          (SELECT COUNT(*) FROM mastery_cutover_fence) AS cutoverFenceCount,
          (SELECT COUNT(*) FROM mastery_cutover_completion_receipt)
             AS completionReceiptCount,
          (SELECT metadata_value FROM mastery_store_metadata
             WHERE metadata_key =
               'projection_rebuild_completed_v3:event-sequence-subject-history-stream-v2'
             LIMIT 1) AS projectionCompletionMarker,
          (SELECT metadata_value FROM mastery_store_metadata
             WHERE metadata_key =
               'presentation_fingerprint_budget_rebuild_completed_v2'
             LIMIT 1) AS directionalBudgetCompletionMarker
        """,
    )
    abstract suspend fun readProjectionHealth(
        learnerId: String,
        generationId: Long,
        projectionPolicyVersion: String,
        calibrationVersion: String,
    ): LearnerMasteryCutoverProjectionHealthRow
}

internal data class LearnerMasteryCutoverCandidateRow(
    val stableKey: String,
    val primaryFingerprint: String,
    val sourceFingerprint: String?,
    val proofFingerprint: String?,
    val decisionFingerprint: String?,
    val eventFingerprint: String?,
    val applicationFingerprint: String?,
    val consistent: Boolean,
)

internal data class LearnerMasteryCutoverAttributionRow(
    val stableKey: String,
    val primaryFingerprint: String,
    val nodeFingerprint: String,
    val auxiliaryFingerprint: String?,
    val scalarOne: String,
    val scalarTwo: String,
    val consistent: Boolean,
)

internal data class LearnerMasteryCutoverBindingRow(
    val stableKey: String,
    val primaryFingerprint: String,
    val secondaryFingerprint: String,
    val scalarOne: String,
    val scalarTwo: String,
    val consistent: Boolean,
)

internal data class LearnerMasteryCutoverSupersessionRow(
    val stableKey: String,
    val primaryFingerprint: String,
    val originalFingerprint: String,
    val replacementFingerprint: String,
    val consistent: Boolean,
)

internal data class LearnerMasteryCutoverMigrationRow(
    val stableKey: String,
    val primaryFingerprint: String,
    val sourceFingerprint: String,
    val candidateFingerprint: String,
    val consistent: Boolean,
)

internal data class LearnerMasteryCutoverEventBindingHealthRow(
    val sourceFactCount: Long,
    val sourceProofCount: Long,
    val candidateCount: Long,
    val eventCount: Long,
    val appliedEventCount: Long,
    val eventAttributionCount: Long,
    val bindingCount: Long,
    val bindingStateCount: Long,
    val supersessionCount: Long,
    val migrationRecordCount: Long,
    val maxEventSequence: Long,
    val allocatedEventSequence: Long,
    val conflictReceiptCount: Long,
    val unresolvedReviewCount: Long,
    val pendingInboxCount: Long,
    val pendingOutboxCount: Long,
    val invalidCandidateCount: Long,
    val invalidAttributionCount: Long,
    val invalidBindingCount: Long,
    val invalidMigrationCount: Long,
    val cutoverFenceCount: Long,
    val completionReceiptCount: Long,
)

internal data class LearnerMasteryCutoverSchemaObjectRow(
    val stableKey: String,
    val type: String,
    val name: String,
    val sql: String?,
)

internal data class LearnerMasteryCutoverProjectionHealthRow(
    val activeGenerationCount: Long,
    val buildingGenerationCount: Long,
    val sourceEventCount: Long,
    val sourceSupersessionCount: Long,
    val projectionCount: Long,
    val subjectDigestCount: Long,
    val presentationBudgetCount: Long,
    val problemFamilyBudgetCount: Long,
    val activeShadowProjectionCount: Long,
    val activeShadowDigestCount: Long,
    val activeShadowPresentationBudgetCount: Long,
    val activeShadowProblemFamilyBudgetCount: Long,
    val nonActiveShadowCount: Long,
    val projectionShadowMismatchCount: Long,
    val digestShadowMismatchCount: Long,
    val presentationShadowMismatchCount: Long,
    val problemFamilyShadowMismatchCount: Long,
    val staleProjectionCount: Long,
    val staleDigestCount: Long,
    val inconsistentDigestCount: Long,
    val missingDigestProjectionCount: Long,
    val cutoverFenceCount: Long,
    val completionReceiptCount: Long,
    val projectionCompletionMarker: String?,
    val directionalBudgetCompletionMarker: String?,
)
