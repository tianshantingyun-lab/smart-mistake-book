package com.tingyun.smartmistakebook.core.mastery.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Upsert

/**
 * Cross-store relay, metadata, projection generation lease and shadow cleanup primitives.
 */
@Dao
internal abstract class LearnerMasteryCrossStoreDao : LearnerMasteryShadowBudgetDao() {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutbox(entity: MasteryCrossStoreOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertInbox(entity: MasteryCrossStoreInboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertStudentRelaySourceBinding(
        entity: MasteryStudentRelaySourceBindingEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAuthenticatedStudentInboxReceipt(
        entity: MasteryAuthenticatedStudentInboxReceiptEntity,
    )

    @Query(
        "SELECT * FROM mastery_student_relay_source_binding " +
            "WHERE learner_id = :learnerId LIMIT 1",
    )
    protected abstract suspend fun findStudentRelaySourceBinding(
        learnerId: String,
    ): MasteryStudentRelaySourceBindingEntity?

    @Query(
        "SELECT * FROM mastery_authenticated_student_inbox_receipt " +
            "WHERE event_id = :eventId LIMIT 1",
    )
    protected abstract suspend fun findAuthenticatedStudentInboxReceipt(
        eventId: String,
    ): MasteryAuthenticatedStudentInboxReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertProblemBindingAuthorities(
        entities: List<MasteryProblemBindingAuthorityEntity>,
    ): List<Long>

    @Query(
        """
        DELETE FROM mastery_problem_binding_authority
        WHERE problem_revision_ref_fingerprint = :problemRevisionRefFingerprint
        """,
    )
    protected abstract suspend fun deleteProblemBindingAuthorities(
        problemRevisionRefFingerprint: String,
    ): Int

    @Upsert
    protected abstract suspend fun upsertProblemBindingAuthorityState(
        entity: MasteryProblemBindingAuthorityStateEntity,
    )

    @Query(
        """
        SELECT * FROM mastery_problem_binding_authority_state
        WHERE problem_revision_ref_fingerprint = :problemRevisionRefFingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun findProblemBindingAuthorityState(
        problemRevisionRefFingerprint: String,
    ): MasteryProblemBindingAuthorityStateEntity?

    @Query("SELECT * FROM mastery_cross_store_inbox WHERE event_id = :eventId LIMIT 1")
    protected abstract suspend fun findInboxByEventId(
        eventId: String,
    ): MasteryCrossStoreInboxEntity?

    @Query(
        "SELECT * FROM mastery_cross_store_inbox WHERE idempotency_key = :idempotencyKey LIMIT 1",
    )
    protected abstract suspend fun findInboxByIdempotency(
        idempotencyKey: String,
    ): MasteryCrossStoreInboxEntity?

    @Query(
        """
        SELECT * FROM mastery_cross_store_inbox
        WHERE source_store = :sourceStore
          AND source_store_generation = :sourceStoreGeneration
          AND aggregate_id = :aggregateId
          AND aggregate_version = :aggregateVersion
          AND payload_type = :payloadType
          AND payload_version = :payloadVersion
        LIMIT 1
        """,
    )
    protected abstract suspend fun findInboxByAggregateVersion(
        sourceStore: String,
        sourceStoreGeneration: String,
        aggregateId: String,
        aggregateVersion: Long,
        payloadType: String,
        payloadVersion: Int,
    ): MasteryCrossStoreInboxEntity?

    @Query(
        """
        SELECT inbox.* FROM mastery_cross_store_inbox AS inbox
        INNER JOIN mastery_problem_binding_authority_state AS state
          ON state.inbox_event_id = inbox.event_id
        WHERE state.problem_revision_ref_fingerprint = :problemRevisionRefFingerprint
        LIMIT 1
        """,
    )
    internal abstract suspend fun findCurrentBindingSnapshotInbox(
        problemRevisionRefFingerprint: String,
    ): MasteryCrossStoreInboxEntity?

    @Query(
        """
        SELECT * FROM mastery_cross_store_outbox
        WHERE published_at_epoch_millis IS NULL
          AND learner_id = :learnerId
          AND created_at_epoch_millis <= :nowEpochMillis
        ORDER BY created_at_epoch_millis ASC, event_id ASC
        LIMIT :limit
        """,
    )
    internal abstract suspend fun readPendingOutbox(
        learnerId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<MasteryCrossStoreOutboxEntity>

    @Query(
        """
        SELECT * FROM mastery_outbox_authenticity_key_state
        WHERE singleton_id = 1
        LIMIT 1
        """,
    )
    internal abstract suspend fun readActiveMasteryOutboxAuthenticityKeyState():
        MasteryOutboxAuthenticityKeyStateEntity?

    @Query(
        """
        UPDATE mastery_cross_store_outbox
        SET published_at_epoch_millis = :deliveredAtEpochMillis
        WHERE event_id = :eventId
          AND envelope_canonical_fingerprint = :envelopeCanonicalFingerprint
          AND published_at_epoch_millis IS NULL
        """,
    )
    internal abstract suspend fun markOutboxDelivered(
        eventId: String,
        envelopeCanonicalFingerprint: String,
        deliveredAtEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMetadata(entity: MasteryStoreMetadataEntity): Long

    @Query(
        "SELECT metadata_value FROM mastery_store_metadata WHERE metadata_key = :metadataKey LIMIT 1",
    )
    protected abstract suspend fun readMetadata(metadataKey: String): String?

    @Query(
        """
        SELECT * FROM mastery_store_metadata
        WHERE metadata_key GLOB 'projection_rebuild_progress_v3:*'
           OR metadata_key GLOB 'projection_rebuild_progress_v2:*'
        ORDER BY metadata_key DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLatestProjectionRebuildProgress():
        MasteryStoreMetadataEntity?

    @Query("SELECT COUNT(*) FROM mastery_learning_event")
    protected abstract suspend fun countLearningEvents(): Long

    @Query(
        "SELECT (SELECT COUNT(*) FROM mastery_learning_evidence_supersession) + " +
            "(SELECT COUNT(*) FROM mastery_open_response_legacy_quarantine)",
    )
    protected abstract suspend fun countLearningEvidenceSupersessions(): Long

    @Query("SELECT COUNT(*) FROM mastery_open_response_legacy_quarantine")
    protected abstract suspend fun countOpenResponseLegacyQuarantines(): Long

    @Query(
        """
        SELECT * FROM mastery_projection_generation
        WHERE state = 'ACTIVE'
          AND snapshot_fingerprint IS NOT NULL
        ORDER BY generation_id DESC
        LIMIT 1
        """,
    )
    internal abstract suspend fun readActiveProjectionGeneration():
        MasteryProjectionGenerationEntity?

    @Query(
        """
        SELECT * FROM mastery_projection_generation
        WHERE state = 'BUILDING'
        ORDER BY generation_id DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readBuildingProjectionGeneration():
        MasteryProjectionGenerationEntity?

    @Query("SELECT COALESCE(MAX(generation_id), 0) FROM mastery_projection_generation")
    protected abstract suspend fun readMaximumProjectionGenerationId(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertProjectionGeneration(
        entity: MasteryProjectionGenerationEntity,
    ): Long

    @Upsert
    protected abstract suspend fun upsertProjectionGeneration(
        entity: MasteryProjectionGenerationEntity,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertProjectionBudgetRebuildReceipt(
        entity: ProjectionBudgetRebuildReceiptEntity,
    ): Long

    @Query(
        "SELECT * FROM mastery_projection_budget_rebuild_receipt " +
            "WHERE generation_id = :generationId LIMIT 1",
    )
    protected abstract suspend fun findProjectionBudgetRebuildReceipt(
        generationId: Long,
    ): ProjectionBudgetRebuildReceiptEntity?

    @Query(
        """
        UPDATE mastery_projection_generation
        SET lease_owner_id = :ownerId,
            lease_expires_at_epoch_millis = :leaseExpiresAtEpochMillis
        WHERE generation_id = :generationId
          AND state = 'BUILDING'
          AND (
            lease_owner_id IS NULL OR
            lease_owner_id = :ownerId OR
            lease_expires_at_epoch_millis IS NULL OR
            lease_expires_at_epoch_millis <= :nowEpochMillis
          )
        """,
    )
    protected abstract suspend fun claimProjectionGenerationLease(
        generationId: Long,
        ownerId: String,
        nowEpochMillis: Long,
        leaseExpiresAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE mastery_projection_generation
        SET lease_owner_id = NULL,
            lease_expires_at_epoch_millis = NULL
        WHERE generation_id = :generationId
          AND state = 'BUILDING'
          AND lease_owner_id = :ownerId
        """,
    )
    protected abstract suspend fun releaseProjectionGenerationLease(
        generationId: Long,
        ownerId: String,
    ): Int

    @Query("DELETE FROM mastery_projection_shadow WHERE generation_id = :generationId")
    protected abstract suspend fun deleteShadowProjections(generationId: Long): Int

    @Query("DELETE FROM mastery_subject_digest_shadow WHERE generation_id = :generationId")
    protected abstract suspend fun deleteShadowSubjectDigests(generationId: Long): Int

    @Query(
        "DELETE FROM mastery_presentation_node_budget_shadow WHERE generation_id = :generationId",
    )
    protected abstract suspend fun deleteShadowPresentationBudgets(generationId: Long): Int

    @Query(
        "DELETE FROM mastery_problem_family_node_budget_shadow WHERE generation_id = :generationId",
    )
    protected abstract suspend fun deleteShadowProblemFamilyBudgets(generationId: Long): Int

    @Query(
        """
        DELETE FROM mastery_projection_shadow
        WHERE generation_id IN (
            SELECT generation_id FROM mastery_projection_generation WHERE state = 'RETIRED'
        )
        """,
    )
    protected abstract suspend fun deleteRetiredShadowProjections(): Int

    @Query(
        """
        DELETE FROM mastery_subject_digest_shadow
        WHERE generation_id IN (
            SELECT generation_id FROM mastery_projection_generation WHERE state = 'RETIRED'
        )
        """,
    )
    protected abstract suspend fun deleteRetiredShadowSubjectDigests(): Int

    @Query(
        """
        DELETE FROM mastery_presentation_node_budget_shadow
        WHERE generation_id IN (
            SELECT generation_id FROM mastery_projection_generation WHERE state = 'RETIRED'
        )
        """,
    )
    protected abstract suspend fun deleteRetiredShadowPresentationBudgets(): Int

    @Query(
        """
        DELETE FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id IN (
            SELECT generation_id FROM mastery_projection_generation WHERE state = 'RETIRED'
        )
        """,
    )
    protected abstract suspend fun deleteRetiredShadowProblemFamilyBudgets(): Int

    @Query(
        "SELECT COUNT(*) FROM mastery_projection_shadow WHERE generation_id = :generationId",
    )
    protected abstract suspend fun countShadowProjectionRows(generationId: Long): Long

    @Query(
        "SELECT COUNT(*) FROM mastery_subject_digest_shadow WHERE generation_id = :generationId",
    )
    protected abstract suspend fun countShadowSubjectDigestRows(generationId: Long): Long

    @Query(
        """
        SELECT COUNT(*) FROM mastery_presentation_node_budget_shadow
        WHERE generation_id = :generationId
        """,
    )
    protected abstract suspend fun countShadowPresentationBudgetRows(generationId: Long): Long

    @Query(
        """
        SELECT COUNT(*) FROM mastery_problem_family_node_budget_shadow
        WHERE generation_id = :generationId
        """,
    )
    protected abstract suspend fun countShadowProblemFamilyBudgetRows(generationId: Long): Long

}
