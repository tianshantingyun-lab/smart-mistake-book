package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Transaction
import androidx.room3.Update
import com.tingyun.smartmistakebook.core.model.CanonicalSha256

/**
 * Student-mistake outbox, inbox and mastery relay primitive DAO.
 */
@Dao
internal abstract class StudentMistakeCrossStoreDao : StudentMistakeMigrationDao() {
    @Query(
        """
        SELECT *
        FROM student_store_outbox
        WHERE learner_id = :learnerId
          AND delivery_state = 'PENDING'
          AND available_at_epoch_millis <= :nowEpochMillis
          AND authenticity_proof_protocol_version IS NOT NULL
          AND authenticity_algorithm_version IS NOT NULL
          AND authenticity_issuer_key_id IS NOT NULL
          AND authenticity_learner_id IS NOT NULL
          AND authenticity_envelope_fingerprint IS NOT NULL
          AND authenticity_tag_hex IS NOT NULL
          AND authenticity_relay_epoch IS NOT NULL
          AND NOT (
              payload_type = 'review_observation_captured' AND payload_version = 1
          )
        ORDER BY occurred_at_epoch_millis ASC, event_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readPendingOutbox(
        learnerId: String,
        nowEpochMillis: Long,
        limit: Int,
    ): List<StudentStoreOutboxEntity>

    @Query(
        """
        UPDATE student_store_outbox
        SET delivery_state = 'RETIRED_UNSAFE_LEGACY',
            delivered_at_epoch_millis = :retiredAtEpochMillis
        WHERE learner_id = :learnerId
          AND delivery_state = 'PENDING'
          AND source_store = 'STUDENT_MISTAKES'
          AND destination_store = 'LEARNER_MASTERY'
          AND payload_type = 'review_observation_captured'
          AND payload_version = 1
          AND delivered_at_epoch_millis IS NULL
          AND :retiredAtEpochMillis >= occurred_at_epoch_millis
        """,
    )
    abstract suspend fun retirePendingLegacyReviewObservationV1(
        learnerId: String,
        retiredAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE student_store_outbox
        SET delivery_state = 'RETIRED_UNSAFE_LEGACY',
            delivered_at_epoch_millis = :retiredAtEpochMillis
        WHERE learner_id = :learnerId
          AND delivery_state = 'PENDING'
          AND source_store = 'STUDENT_MISTAKES'
          AND destination_store = 'LEARNER_MASTERY'
          AND delivered_at_epoch_millis IS NULL
          AND :retiredAtEpochMillis >= occurred_at_epoch_millis
          AND (
              authenticity_proof_protocol_version IS NULL
              OR authenticity_algorithm_version IS NULL
              OR authenticity_issuer_key_id IS NULL
              OR authenticity_learner_id IS NULL
              OR authenticity_envelope_fingerprint IS NULL
              OR authenticity_tag_hex IS NULL
              OR authenticity_relay_epoch IS NULL
          )
        """,
    )
    abstract suspend fun retirePendingUnsignedStudentOutbox(
        learnerId: String,
        retiredAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE student_store_outbox
        SET delivery_state = 'AUTHENTICITY_REJECTED',
            delivered_at_epoch_millis = :rejectedAtEpochMillis
        WHERE learner_id = :learnerId
          AND event_id = :eventId
          AND envelope_canonical_fingerprint = :expectedEnvelopeCanonicalFingerprint
          AND authenticity_tag_hex = :expectedAuthenticityTagHex
          AND delivery_state = 'PENDING'
          AND source_store = 'STUDENT_MISTAKES'
          AND destination_store = 'LEARNER_MASTERY'
          AND delivered_at_epoch_millis IS NULL
          AND :rejectedAtEpochMillis >= occurred_at_epoch_millis
        """,
    )
    abstract suspend fun rejectPendingOutboxAuthenticity(
        learnerId: String,
        eventId: String,
        expectedEnvelopeCanonicalFingerprint: String,
        expectedAuthenticityTagHex: String,
        rejectedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT COALESCE(MAX(aggregate_version), 0)
        FROM student_store_outbox
        WHERE aggregate_id = :revisionId
          AND payload_type = 'problem_knowledge_bindings_snapshot'
          AND payload_version = 2
        """,
    )
    abstract suspend fun readLatestBindingSetVersion(
        revisionId: String,
    ): Long

    @Query(
        """
        UPDATE student_store_outbox
        SET delivery_state = 'DELIVERED',
            delivered_at_epoch_millis = :deliveredAtEpochMillis
        WHERE event_id = :eventId
          AND learner_id = :learnerId
          AND envelope_canonical_fingerprint = :envelopeCanonicalFingerprint
          AND delivery_state = 'PENDING'
        """,
    )
    abstract suspend fun markOutboxDelivered(
        learnerId: String,
        eventId: String,
        envelopeCanonicalFingerprint: String,
        deliveredAtEpochMillis: Long,
    ): Int

    @Query(
        """
        SELECT event_id, source_store, destination_store, aggregate_id,
               aggregate_version, payload_type, payload_version,
               payload_canonical_fingerprint, payload_wire,
               envelope_canonical_fingerprint, occurred_at_epoch_millis,
               idempotency_key, source_store_generation, apply_state,
               received_at_epoch_millis, applied_at_epoch_millis
        FROM student_store_inbox
        WHERE source_store = :sourceStore
          AND source_store_generation = :sourceStoreGeneration
          AND idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun readInboxByIdempotency(
        sourceStore: String,
        sourceStoreGeneration: String,
        idempotencyKey: String,
    ): StudentStoreInboxEntity?

    @Query(
        """
        SELECT * FROM student_mastery_relay_source_binding
        WHERE learner_id = :learnerId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readMasteryRelaySourceBinding(
        learnerId: String,
    ): StudentMasteryRelaySourceBindingEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMasteryRelaySourceBinding(
        binding: StudentMasteryRelaySourceBindingEntity,
    ): Long

    @Query(
        """
        SELECT * FROM student_authenticated_mastery_inbox_receipt
        WHERE event_id = :eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readAuthenticatedMasteryInboxReceipt(
        eventId: String,
    ): StudentAuthenticatedMasteryInboxReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAuthenticatedMasteryInboxReceipt(
        receipt: StudentAuthenticatedMasteryInboxReceiptEntity,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertMasteryRelayReauthorizationCase(
        case: StudentMasteryRelayReauthorizationCaseEntity,
    ): Long

    @Query(
        """
        SELECT reauth_case.*
        FROM student_mastery_relay_reauthorization_case AS reauth_case
        LEFT JOIN student_mastery_relay_reauthorization_resolution AS resolution
          ON resolution.case_id = reauth_case.case_id
        WHERE reauth_case.learner_id = :learnerId
          AND resolution.case_id IS NULL
        ORDER BY reauth_case.detected_at_epoch_millis DESC, reauth_case.case_id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun readPendingMasteryRelayReauthorizationCase(
        learnerId: String,
    ): StudentMasteryRelayReauthorizationCaseEntity?

    @Query(
        """
        SELECT * FROM student_mastery_relay_reauthorization_case
        WHERE case_id = :caseId AND learner_id = :learnerId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readMasteryRelayReauthorizationCase(
        learnerId: String,
        caseId: String,
    ): StudentMasteryRelayReauthorizationCaseEntity?

    @Query(
        """
        SELECT COUNT(*) FROM student_mastery_relay_reauthorization_resolution
        WHERE case_id = :caseId
        """,
    )
    protected abstract suspend fun countMasteryRelayReauthorizationResolutions(
        caseId: String,
    ): Int

    @Query(
        """
        UPDATE student_mastery_relay_source_binding
        SET source_store_generation = :candidateSourceStoreGeneration,
            relay_epoch = :candidateRelayEpoch,
            issuer_key_id = :candidateIssuerKeyId,
            algorithm_version = :candidateAlgorithmVersion
        WHERE learner_id = :learnerId
          AND source_store_generation = :expectedSourceStoreGeneration
          AND relay_epoch = :expectedRelayEpoch
          AND issuer_key_id = :expectedIssuerKeyId
        """,
    )
    protected abstract suspend fun updateMasteryRelaySourceBinding(
        learnerId: String,
        expectedSourceStoreGeneration: String,
        expectedRelayEpoch: String,
        expectedIssuerKeyId: String,
        candidateSourceStoreGeneration: String,
        candidateRelayEpoch: String,
        candidateIssuerKeyId: String,
        candidateAlgorithmVersion: String,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMasteryRelayReauthorizationResolution(
        resolution: StudentMasteryRelayReauthorizationResolutionEntity,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertInbox(message: StudentStoreInboxEntity): Long

    @Update
    protected abstract suspend fun updateInbox(message: StudentStoreInboxEntity): Int

    @Transaction
    open suspend fun reauthorizeMasteryRelaySource(
        learnerId: String,
        command: StudentMasteryRelayReauthorizationCommand,
    ): StudentMasteryRelayReauthenticationStatus {
        val case = checkNotNull(
            readMasteryRelayReauthorizationCase(learnerId, command.caseId),
        ) { "Mastery relay reauthorization case does not exist" }
        check(countMasteryRelayReauthorizationResolutions(case.caseId) == 0) {
            "Mastery relay reauthorization case was already resolved"
        }
        check(
            case.boundSourceStoreGeneration == command.expectedBoundSourceStoreGeneration &&
                case.boundRelayEpoch == command.expectedBoundRelayEpoch &&
                case.boundIssuerKeyId == command.expectedBoundIssuerKeyId &&
                case.candidateSourceStoreGeneration ==
                command.candidateSourceStoreGeneration &&
                case.candidateRelayEpoch == command.candidateRelayEpoch &&
                case.candidateIssuerKeyId == command.candidateIssuerKeyId &&
                case.candidateAlgorithmVersion == command.candidateAlgorithmVersion,
        ) { "Mastery relay reauthorization command does not match the detected rotation" }
        check(
            updateMasteryRelaySourceBinding(
                learnerId = learnerId,
                expectedSourceStoreGeneration = command.expectedBoundSourceStoreGeneration,
                expectedRelayEpoch = command.expectedBoundRelayEpoch,
                expectedIssuerKeyId = command.expectedBoundIssuerKeyId,
                candidateSourceStoreGeneration = command.candidateSourceStoreGeneration,
                candidateRelayEpoch = command.candidateRelayEpoch,
                candidateIssuerKeyId = command.candidateIssuerKeyId,
                candidateAlgorithmVersion = command.candidateAlgorithmVersion,
            ) == 1,
        ) { "Mastery relay source binding changed before explicit reauthorization" }
        insertMasteryRelayReauthorizationResolution(
            StudentMasteryRelayReauthorizationResolutionEntity(
                resolutionId = "mastery-relay-resolution:${command.canonicalFingerprint}",
                caseId = case.caseId,
                authorizationCanonicalFingerprint = command.authorizationCanonicalFingerprint,
                authorizedAtEpochMillis = command.authorizedAtEpochMillis,
            ),
        )
        return StudentMasteryRelayReauthenticationStatus(
            StudentMasteryRelayReauthenticationState.TRUSTED,
            caseId = null,
        )
    }

    protected fun StudentMasteryRelaySourceBindingEntity.matches(
        receipt: StudentAuthenticatedMasteryInboxReceiptEntity,
    ): Boolean =
        sourceStore == receipt.sourceStore &&
            sourceStoreGeneration == receipt.sourceStoreGeneration &&
            relayEpoch == receipt.relayEpoch &&
            issuerKeyId == receipt.issuerKeyId &&
            algorithmVersion == receipt.algorithmVersion

    protected suspend fun appendMasteryRelayReauthorizationCase(
        binding: StudentMasteryRelaySourceBindingEntity,
        receipt: StudentAuthenticatedMasteryInboxReceiptEntity,
    ) {
        val caseFingerprint =
            CanonicalSha256("student-mastery-relay-reauthorization-case-v1")
                .field("learnerId", receipt.learnerId)
                .field("boundSourceStoreGeneration", binding.sourceStoreGeneration)
                .field("boundRelayEpoch", binding.relayEpoch)
                .field("boundIssuerKeyId", binding.issuerKeyId)
                .field("candidateSourceStoreGeneration", receipt.sourceStoreGeneration)
                .field("candidateRelayEpoch", receipt.relayEpoch)
                .field("candidateIssuerKeyId", receipt.issuerKeyId)
                .field(
                    "candidateVerificationReceiptCanonicalFingerprint",
                    receipt.verificationReceiptCanonicalFingerprint,
                )
                .finish()
        insertMasteryRelayReauthorizationCase(
            StudentMasteryRelayReauthorizationCaseEntity(
                caseId = "mastery-relay-reauth:$caseFingerprint",
                learnerId = receipt.learnerId,
                boundSourceStoreGeneration = binding.sourceStoreGeneration,
                boundRelayEpoch = binding.relayEpoch,
                boundIssuerKeyId = binding.issuerKeyId,
                candidateSourceStoreGeneration = receipt.sourceStoreGeneration,
                candidateRelayEpoch = receipt.relayEpoch,
                candidateIssuerKeyId = receipt.issuerKeyId,
                candidateAlgorithmVersion = receipt.algorithmVersion,
                candidateVerificationReceiptCanonicalFingerprint =
                    receipt.verificationReceiptCanonicalFingerprint,
                detectedAtEpochMillis = receipt.receivedAtEpochMillis,
                reason = "SOURCE_GENERATION_EPOCH_OR_KEY_CHANGED",
            ),
        )
    }
}
