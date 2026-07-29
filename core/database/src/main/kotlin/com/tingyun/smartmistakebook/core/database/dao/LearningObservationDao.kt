package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateStatusCasResult
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateStatusChangeCommand
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateWriteResult
import com.tingyun.smartmistakebook.core.database.LearningObservationMaterializationResult
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceAuthorityException
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceAuthorityRecord
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceAuthorityWriteResult
import com.tingyun.smartmistakebook.core.database.MaterializeLearningObservationCommand
import com.tingyun.smartmistakebook.core.database.StudyDbValue
import com.tingyun.smartmistakebook.core.database.entity.AttributedLearningObservationEventEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningEvidenceReviewCaseEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningEventIdentityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationCandidateAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationCandidateEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAttributionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceAuthorityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionCertainty
import com.tingyun.smartmistakebook.core.model.EvidenceAttributionRole
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewCase
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewReason
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReviewStatus
import com.tingyun.smartmistakebook.core.model.LearningLedgerFingerprint
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidate
import com.tingyun.smartmistakebook.core.model.LearningObservationCandidateStatus
import com.tingyun.smartmistakebook.core.model.LearningObservationDirection
import com.tingyun.smartmistakebook.core.model.LearningObservationEvidenceLevel
import com.tingyun.smartmistakebook.core.model.LearningObservationIndependence
import com.tingyun.smartmistakebook.core.model.LearningObservationKnowledgeAttribution
import com.tingyun.smartmistakebook.core.model.LearningObservationSource
import com.tingyun.smartmistakebook.core.model.SourceFactEvidencePolicy
import com.tingyun.smartmistakebook.core.model.allowedExternalTransitions
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val EVENT_KIND_LEARNING_OBSERVATION = "ATTRIBUTED_LEARNING_OBSERVATION"

internal data class LearningObservationAnchorAuthorityRow(
    val subject: String,
)

internal data class LearningObservationAttributionAuthorityRow(
    val bindingId: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val basisRevisionId: String,
    val taxonomyVersion: String,
    val problemSubject: String,
    val knowledgeSubject: String,
)

@Dao
internal abstract class LearningObservationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSourceAuthority(
        entity: LearningObservationSourceAuthorityEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidate(entity: LearningObservationCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidateAttributions(
        entities: List<LearningObservationCandidateAttributionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvent(entity: AttributedLearningObservationEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEventAttributions(
        entities: List<LearningObservationEventAttributionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOutbox(entity: ProjectionOutboxEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertEventIdentity(
        identity: LearningEventIdentityEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertReviewCase(entity: LearningEvidenceReviewCaseEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun initializeSequence(sequence: LearningSequenceEntity): Long

    @Query(
        """
        UPDATE learning_sequence
        SET last_allocated_sequence = :next
        WHERE learner_id = :learnerId AND last_allocated_sequence = :expected
        """,
    )
    protected abstract suspend fun compareAndSetSequence(
        learnerId: String,
        expected: Long,
        next: Long,
    ): Int

    @Query("SELECT last_allocated_sequence FROM learning_sequence WHERE learner_id = :learnerId")
    protected abstract suspend fun lastAllocatedSequence(learnerId: String): Long?

    @Query(
        "SELECT * FROM learning_observation_candidate WHERE candidate_id = :candidateId LIMIT 1",
    )
    protected abstract suspend fun findCandidateEntity(
        candidateId: String,
    ): LearningObservationCandidateEntity?

    @Query(
        """
        SELECT * FROM learning_observation_candidate
        WHERE learner_id = :learnerId
          AND source = :source
          AND source_reference_id = :sourceReferenceId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findCandidateByProvenance(
        learnerId: String,
        source: String,
        sourceReferenceId: String,
    ): LearningObservationCandidateEntity?

    @Query(
        """
        SELECT * FROM learning_observation_candidate
        WHERE source_fact_id = :sourceFactId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findCandidateBySourceFact(
        sourceFactId: String,
    ): LearningObservationCandidateEntity?

    @Query(
        "SELECT * FROM learning_observation_source_fact WHERE source_fact_id = :sourceFactId LIMIT 1",
    )
    protected abstract suspend fun findCanonicalSourceFact(
        sourceFactId: String,
    ): LearningObservationSourceFactEntity?

    @Query(
        """
        SELECT * FROM learning_observation_source_authority
        WHERE learner_id = :learnerId
          AND source = :source
          AND source_reference_id = :sourceReferenceId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceAuthorityEntity(
        learnerId: String,
        source: String,
        sourceReferenceId: String,
    ): LearningObservationSourceAuthorityEntity?

    @Query(
        """
        SELECT * FROM learning_observation_source_authority
        WHERE source_fact_id = :sourceFactId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findSourceAuthorityBySourceFact(
        sourceFactId: String,
    ): LearningObservationSourceAuthorityEntity?

    @Query(
        """
        SELECT * FROM learning_observation_candidate_attribution
        WHERE candidate_id = :candidateId
        ORDER BY ordinal ASC
        """,
    )
    protected abstract suspend fun findCandidateAttributions(
        candidateId: String,
    ): List<LearningObservationCandidateAttributionEntity>

    @Query(
        "SELECT * FROM attributed_learning_observation_event WHERE event_id = :eventId LIMIT 1",
    )
    internal abstract suspend fun findEventEntity(
        eventId: String,
    ): AttributedLearningObservationEventEntity?

    @Query(
        """
        SELECT * FROM attributed_learning_observation_event
        WHERE candidate_id = :candidateId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findEventForCandidate(
        candidateId: String,
    ): AttributedLearningObservationEventEntity?

    @Query(
        """
        SELECT * FROM learning_observation_event_attribution
        WHERE event_id = :eventId
        ORDER BY ordinal ASC
        """,
    )
    internal abstract suspend fun findEventAttributions(
        eventId: String,
    ): List<LearningObservationEventAttributionEntity>

    @Query(
        """
        SELECT * FROM projection_outbox
        WHERE event_kind = :eventKind AND event_id = :eventId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findOutbox(
        eventKind: String,
        eventId: String,
    ): ProjectionOutboxEntity?

    @Query(
        "SELECT * FROM learning_evidence_review_case WHERE review_case_id = :reviewCaseId LIMIT 1",
    )
    protected abstract suspend fun findReviewCase(
        reviewCaseId: String,
    ): LearningEvidenceReviewCaseEntity?

    @Query(
        """
        UPDATE learning_evidence_review_case
        SET status = :resolvedStatus,
            resolved_at_epoch_millis = CASE
                WHEN created_at_epoch_millis > :resolvedAtEpochMillis
                    THEN created_at_epoch_millis
                ELSE :resolvedAtEpochMillis
            END
        WHERE candidate_id = :candidateId
          AND proposed_event_id = :proposedEventId
          AND status = :openStatus
        """,
    )
    protected abstract suspend fun resolveOpenReviewCases(
        candidateId: String,
        proposedEventId: String,
        resolvedAtEpochMillis: Long,
        openStatus: String,
        resolvedStatus: String,
    ): Int

    @Query(
        """
        SELECT problem.subject AS subject
        FROM practice_unit AS unit
        JOIN problem ON problem.problem_id = unit.problem_id
        WHERE unit.practice_unit_id = :practiceUnitId
          AND unit.problem_revision_id = :problemRevisionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAnchorAuthority(
        practiceUnitId: String,
        problemRevisionId: String,
    ): LearningObservationAnchorAuthorityRow?

    @Query(
        """
        SELECT binding.binding_id AS bindingId,
               binding.practice_unit_id AS practiceUnitId,
               binding.knowledge_node_id AS knowledgeNodeId,
               binding.basis_revision_id AS basisRevisionId,
               binding.taxonomy_version AS taxonomyVersion,
               problem.subject AS problemSubject,
               node.subject AS knowledgeSubject
        FROM practice_unit_knowledge_binding AS binding
        JOIN practice_unit AS unit
          ON unit.practice_unit_id = binding.practice_unit_id
         AND unit.problem_revision_id = binding.basis_revision_id
        JOIN problem ON problem.problem_id = unit.problem_id
        JOIN knowledge_node AS node
          ON node.knowledge_node_id = binding.knowledge_node_id
        WHERE binding.binding_id = :bindingId
        LIMIT 1
        """,
    )
    protected abstract suspend fun findAttributionAuthority(
        bindingId: String,
    ): LearningObservationAttributionAuthorityRow?

    @Query(
        """
        UPDATE learning_observation_candidate
        SET status = :newStatus,
            retry_count = retry_count + CASE WHEN :incrementRetry THEN 1 ELSE 0 END,
            updated_at_epoch_millis = :updatedAtEpochMillis
        WHERE candidate_id = :candidateId
          AND status = :expectedStatus
          AND retry_count = :expectedRetryCount
          AND updated_at_epoch_millis <= :updatedAtEpochMillis
        """,
    )
    protected abstract suspend fun compareAndSetStatus(
        candidateId: String,
        expectedStatus: String,
        newStatus: String,
        expectedRetryCount: Int,
        incrementRetry: Boolean,
        updatedAtEpochMillis: Long,
    ): Int

    @Transaction
    open suspend fun registerSourceAuthority(
        authority: LearningObservationSourceAuthorityRecord,
    ): LearningObservationSourceAuthorityWriteResult {
        validateSourceAuthority(authority)
        val sourceFactId = requireNotNull(authority.sourceFactId)
        findSourceAuthorityBySourceFact(sourceFactId)?.let { existing ->
            val persisted = existing.toModel()
            if (persisted != authority) {
                throw ImmutablePayloadConflictException(
                    "learning_observation_source_authority_source_fact",
                    sourceFactId,
                )
            }
            return LearningObservationSourceAuthorityWriteResult(
                created = false,
                authority = persisted,
            )
        }
        findSourceAuthorityEntity(
            authority.learnerId,
            authority.source.name,
            authority.sourceReferenceId,
        )?.let { existing ->
            val persisted = existing.toModel()
            if (persisted != authority) {
                throw ImmutablePayloadConflictException(
                    "learning_observation_source_authority",
                    authority.provenanceKey(),
                )
            }
            return LearningObservationSourceAuthorityWriteResult(
                created = false,
                authority = persisted,
            )
        }
        insertSourceAuthority(authority.toEntity())
        return LearningObservationSourceAuthorityWriteResult(created = true, authority = authority)
    }

    @Transaction
    open suspend fun submitCandidate(
        candidate: LearningObservationCandidate,
    ): LearningObservationCandidateWriteResult {
        val sourceFactId = requireNotNull(candidate.sourceFactId) {
            "New learning-observation candidates require a canonical source-fact id"
        }
        val sourceFact = requireNotNull(findCanonicalSourceFact(sourceFactId)) {
            "Learning-observation source fact is not canonical"
        }.toModel()
        SourceFactEvidencePolicy.requireCandidate(sourceFact, candidate)
        sourceAuthorityFailure(candidate)?.let {
            throw LearningObservationSourceAuthorityException(candidate.candidateId)
        }
        val fingerprint = LearningLedgerFingerprint.learningObservationCandidate(candidate)
        findCandidateBySourceFact(sourceFactId)?.let { existing ->
            if (existing.candidateId != candidate.candidateId) {
                throw ImmutablePayloadConflictException(
                    "learning_observation_candidate_source_fact",
                    sourceFactId,
                )
            }
        }
        findCandidateByProvenance(
            candidate.learnerId,
            candidate.source.name,
            candidate.sourceReferenceId,
        )?.let { existing ->
            if (existing.candidateId != candidate.candidateId) {
                throw ImmutablePayloadConflictException(
                    "learning_observation_candidate_provenance",
                    candidate.provenanceKey(),
                )
            }
        }
        findCandidateEntity(candidate.candidateId)?.let { existing ->
            val current = readCandidate(existing)
            if (existing.payloadFingerprint != fingerprint ||
                LearningLedgerFingerprint.learningObservationCandidate(current) != fingerprint
            ) {
                throw ImmutablePayloadConflictException(
                    "learning_observation_candidate",
                    candidate.candidateId,
                )
            }
            return LearningObservationCandidateWriteResult(created = false, candidate = current)
        }
        insertCandidate(candidate.toEntity(fingerprint))
        candidate.toAttributionEntities().insertWhenNotEmpty(::insertCandidateAttributions)
        return LearningObservationCandidateWriteResult(created = true, candidate = candidate)
    }

    @Transaction
    open suspend fun compareAndSetCandidateStatus(
        command: LearningObservationCandidateStatusChangeCommand,
    ): LearningObservationCandidateStatusCasResult {
        require(command.candidateId.isNotBlank()) { "candidateId must not be blank" }
        require(command.expectedRetryCount >= 0) { "expectedRetryCount cannot be negative" }
        require(command.updatedAtEpochMillis >= 0) { "updatedAtEpochMillis cannot be negative" }
        require(command.newStatus in command.expectedStatus.allowedExternalTransitions()) {
            "Illegal learning-observation candidate status transition"
        }
        val before = findCandidateEntity(command.candidateId)
            ?: throw ImmutablePayloadConflictException(
                "learning_observation_candidate",
                command.candidateId,
            )
        if (command.newStatus == LearningObservationCandidateStatus.READY &&
            before.status == command.expectedStatus.name &&
            before.retryCount == command.expectedRetryCount &&
            sourceAuthorityFailure(readCandidate(before)) != null
        ) {
            throw LearningObservationSourceAuthorityException(command.candidateId)
        }
        val updated = compareAndSetStatus(
            candidateId = command.candidateId,
            expectedStatus = command.expectedStatus.name,
            newStatus = command.newStatus.name,
            expectedRetryCount = command.expectedRetryCount,
            incrementRetry = command.incrementRetry,
            updatedAtEpochMillis = command.updatedAtEpochMillis,
        ) == 1
        val current = findCandidateEntity(command.candidateId)
            ?: throw ImmutablePayloadConflictException(
                "learning_observation_candidate",
                command.candidateId,
            )
        return LearningObservationCandidateStatusCasResult(
            updated = updated,
            candidate = readCandidate(current),
        )
    }

    @Transaction
    open suspend fun materialize(
        command: MaterializeLearningObservationCommand,
    ): LearningObservationMaterializationResult {
        require(command.candidateId.isNotBlank()) { "candidateId must not be blank" }
        require(command.eventId.isNotBlank()) { "eventId must not be blank" }
        require(command.confirmedAtEpochMillis >= 0) {
            "confirmedAtEpochMillis cannot be negative"
        }
        val candidateEntity = findCandidateEntity(command.candidateId)
            ?: throw ImmutablePayloadConflictException(
                "learning_observation_candidate",
                command.candidateId,
            )
        val candidate = readCandidate(candidateEntity)
        if (candidateEntity.payloadFingerprint !=
            LearningLedgerFingerprint.learningObservationCandidate(candidate)
        ) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
                "The persisted candidate payload no longer matches its immutable fingerprint.",
            )
        }
        val sourceFactId = candidate.sourceFactId
            ?: return review(
                candidate,
                command,
                LearningEvidenceReviewReason.SOURCE_FACT_MISSING,
                "Legacy candidate has no canonical source-fact association.",
            )
        val sourceFactEntity = findCanonicalSourceFact(sourceFactId)
            ?: return review(
                candidate,
                command,
                LearningEvidenceReviewReason.SOURCE_FACT_MISSING,
                "The candidate source fact is absent from canonical storage.",
            )
        val sourceFact = try {
            sourceFactEntity.toModel()
        } catch (_: IllegalArgumentException) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
                "The canonical source fact is invalid.",
            )
        }
        try {
            SourceFactEvidencePolicy.requirePersistedCandidate(sourceFact, candidate)
        } catch (_: IllegalArgumentException) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
                "The candidate exceeds or mismatches its canonical source fact.",
            )
        }
        sourceAuthorityFailure(candidate)?.let { (reason, detail) ->
            return review(candidate, command, reason, detail)
        }

        findEventEntity(command.eventId)?.let { existing ->
            if (existing.candidateId == command.candidateId &&
                existing.confirmedAtEpochMillis == command.confirmedAtEpochMillis
            ) {
                val event = readEvent(existing)
                try {
                    SourceFactEvidencePolicy.requireAttribution(sourceFact, candidate, event)
                } catch (_: IllegalArgumentException) {
                    return review(
                        candidate,
                        command,
                        LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
                        "The persisted event mismatches its canonical source fact.",
                    )
                }
                return replay(existing)
            }
            return review(
                candidate = candidate,
                command = command,
                reason = LearningEvidenceReviewReason.EVENT_ID_CONFLICT,
                detail = "The event id already identifies another immutable observation payload.",
            )
        }
        findEventForCandidate(command.candidateId)?.let { existing ->
            return if (existing.eventId == command.eventId &&
                existing.confirmedAtEpochMillis == command.confirmedAtEpochMillis
            ) {
                val event = readEvent(existing)
                try {
                    SourceFactEvidencePolicy.requireAttribution(sourceFact, candidate, event)
                } catch (_: IllegalArgumentException) {
                    return review(
                        candidate,
                        command,
                        LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
                        "The persisted event mismatches its canonical source fact.",
                    )
                }
                replay(existing)
            } else {
                review(
                    candidate = candidate,
                    command = command,
                    reason = LearningEvidenceReviewReason.CANDIDATE_ALREADY_MATERIALIZED,
                    detail = "The candidate already materialized as another immutable event.",
                )
            }
        }
        if (candidate.status != LearningObservationCandidateStatus.READY) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.CANDIDATE_NOT_READY,
                "Only a READY candidate may be admitted to the learning ledger.",
            )
        }
        if (candidate.evidenceLevel == LearningObservationEvidenceLevel.LOW_CONFIDENCE) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.LOW_CONFIDENCE,
                "Low-confidence evidence cannot become a projectable learning event.",
            )
        }
        val practiceUnitId = candidate.practiceUnitId
        val problemRevisionId = candidate.problemRevisionId
        if (practiceUnitId == null || problemRevisionId == null) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.MISSING_AUTHORITY,
                "The candidate lacks an authoritative practice-unit anchor.",
            )
        }
        if (candidate.proposedAttributions.none {
                it.certainty == EvidenceAttributionCertainty.DIRECT
            }
        ) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.NO_DIRECT_ATTRIBUTION,
                "At least one explicit DIRECT attribution is required before materialization.",
            )
        }
        val anchor = findAnchorAuthority(practiceUnitId, problemRevisionId)
            ?: return review(
                candidate,
                command,
                LearningEvidenceReviewReason.MISSING_AUTHORITY,
                "The practice-unit and problem-revision anchor is not authoritative.",
            )
        for (attribution in candidate.proposedAttributions) {
            val authority = findAttributionAuthority(attribution.bindingId)
                ?: return review(
                    candidate,
                    command,
                    LearningEvidenceReviewReason.ATTRIBUTION_CONFLICT,
                    "An attribution does not match an accepted knowledge binding.",
                )
            val exactBinding = authority.practiceUnitId == practiceUnitId &&
                authority.knowledgeNodeId == attribution.knowledgeNodeId &&
                authority.basisRevisionId == problemRevisionId &&
                authority.basisRevisionId == attribution.basisRevisionId &&
                authority.taxonomyVersion == attribution.taxonomyVersion
            if (!exactBinding) {
                return review(
                    candidate,
                    command,
                    LearningEvidenceReviewReason.ATTRIBUTION_CONFLICT,
                    "An attribution differs from its accepted practice-unit knowledge binding.",
                )
            }
            if (authority.problemSubject != anchor.subject ||
                authority.knowledgeSubject != anchor.subject
            ) {
                return review(
                    candidate,
                    command,
                    LearningEvidenceReviewReason.SUBJECT_MISMATCH,
                    "Problem and knowledge-node subjects differ at the storage authority boundary.",
                )
            }
        }
        val proposedEvent = AttributedLearningObservationEvent(
            eventId = command.eventId,
            candidateId = candidate.candidateId,
            sourceFactId = sourceFactId,
            learnerId = candidate.learnerId,
            practiceUnitId = practiceUnitId,
            problemRevisionId = problemRevisionId,
            direction = candidate.direction,
            evidenceLevel = candidate.evidenceLevel,
            evidenceWeight = candidate.evidenceWeight,
            independence = candidate.independence,
            attributions = candidate.proposedAttributions,
            occurredAtEpochMillis = candidate.occurredAtEpochMillis,
            confirmedAtEpochMillis = command.confirmedAtEpochMillis,
            modelVersion = candidate.modelVersion,
            evidenceLocator = candidate.evidenceLocator,
            eventSequence = 1,
        )
        try {
            SourceFactEvidencePolicy.requireAttribution(sourceFact, candidate, proposedEvent)
        } catch (_: IllegalArgumentException) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED,
                "The attributed event would exceed its canonical source-fact evidence ceiling.",
            )
        }
        if (compareAndSetStatus(
                candidateId = candidate.candidateId,
                expectedStatus = LearningObservationCandidateStatus.READY.name,
                newStatus = LearningObservationCandidateStatus.MATERIALIZED.name,
                expectedRetryCount = candidate.retryCount,
                incrementRetry = false,
                updatedAtEpochMillis = command.confirmedAtEpochMillis,
            ) != 1
        ) {
            return review(
                candidate,
                command,
                LearningEvidenceReviewReason.CANDIDATE_ALREADY_MATERIALIZED,
                "Candidate state changed before ledger admission.",
            )
        }
        claimLearningEventIdentity(
            eventId = command.eventId,
            eventKind = EVENT_KIND_LEARNING_OBSERVATION,
            insert = ::insertEventIdentity,
        )
        val sequence = allocateSequence(candidate.learnerId)
        val event = AttributedLearningObservationEvent(
            eventId = command.eventId,
            candidateId = candidate.candidateId,
            sourceFactId = sourceFactId,
            learnerId = candidate.learnerId,
            practiceUnitId = practiceUnitId,
            problemRevisionId = problemRevisionId,
            direction = candidate.direction,
            evidenceLevel = candidate.evidenceLevel,
            evidenceWeight = candidate.evidenceWeight,
            independence = candidate.independence,
            attributions = candidate.proposedAttributions,
            occurredAtEpochMillis = candidate.occurredAtEpochMillis,
            confirmedAtEpochMillis = command.confirmedAtEpochMillis,
            modelVersion = candidate.modelVersion,
            evidenceLocator = candidate.evidenceLocator,
            eventSequence = sequence,
        )
        val fingerprint = LearningLedgerFingerprint.learningObservation(event)
        val entity = event.toEntity(anchor.subject, fingerprint)
        val outbox = entity.toOutbox()
        insertEvent(entity)
        event.toAttributionEntities().insertWhenNotEmpty(::insertEventAttributions)
        insertOutbox(outbox)
        resolveOpenReviewCases(
            candidateId = candidate.candidateId,
            proposedEventId = command.eventId,
            resolvedAtEpochMillis = command.confirmedAtEpochMillis,
            openStatus = LearningEvidenceReviewStatus.OPEN.name,
            resolvedStatus = LearningEvidenceReviewStatus.RESOLVED.name,
        )
        return LearningObservationMaterializationResult(
            created = true,
            event = event,
            canonicalFingerprint = fingerprint,
            outboxId = outbox.outboxId,
            reviewCase = null,
        )
    }

    @Transaction
    open suspend fun readCandidate(candidateId: String): LearningObservationCandidate? =
        findCandidateEntity(candidateId)?.let { readCandidate(it) }

    @Transaction
    open suspend fun readEvent(eventId: String): AttributedLearningObservationEvent? =
        findEventEntity(eventId)?.let { readEvent(it) }

    @Transaction
    open suspend fun readSourceAuthority(
        learnerId: String,
        source: LearningObservationSource,
        sourceReferenceId: String,
    ): LearningObservationSourceAuthorityRecord? =
        findSourceAuthorityEntity(learnerId, source.name, sourceReferenceId)?.toModel()

    @Transaction
    open suspend fun readReviewCase(reviewCaseId: String): LearningEvidenceReviewCase? =
        findReviewCase(reviewCaseId)?.toModel()

    internal suspend fun readEvent(
        entity: AttributedLearningObservationEventEntity,
    ): AttributedLearningObservationEvent = entity.toModel(findEventAttributions(entity.eventId))

    private suspend fun readCandidate(
        entity: LearningObservationCandidateEntity,
    ): LearningObservationCandidate = entity.toModel(findCandidateAttributions(entity.candidateId))

    private suspend fun replay(
        entity: AttributedLearningObservationEventEntity,
    ): LearningObservationMaterializationResult {
        val event = readEvent(entity)
        val fingerprint = LearningLedgerFingerprint.learningObservation(event)
        val outbox = findOutbox(EVENT_KIND_LEARNING_OBSERVATION, event.eventId)
        if (entity.canonicalFingerprint != fingerprint ||
            outbox == null ||
            outbox.learnerId != entity.learnerId ||
            outbox.outboxSequence != entity.eventSequence ||
            outbox.canonicalFingerprint != fingerprint
        ) {
            throw ImmutablePayloadConflictException(
                "attributed_learning_observation_event",
                event.eventId,
            )
        }
        return LearningObservationMaterializationResult(
            created = false,
            event = event,
            canonicalFingerprint = fingerprint,
            outboxId = outbox.outboxId,
            reviewCase = null,
        )
    }

    private suspend fun review(
        candidate: LearningObservationCandidate,
        command: MaterializeLearningObservationCommand,
        reason: LearningEvidenceReviewReason,
        detail: String,
    ): LearningObservationMaterializationResult {
        val reviewCase = LearningEvidenceReviewCase(
            reviewCaseId = stableReviewCaseId(candidate.candidateId, command.eventId, reason),
            candidateId = candidate.candidateId,
            learnerId = candidate.learnerId,
            proposedEventId = command.eventId,
            reason = reason,
            detail = detail,
            status = LearningEvidenceReviewStatus.OPEN,
            createdAtEpochMillis = command.confirmedAtEpochMillis,
        )
        val entity = reviewCase.toEntity()
        val persisted = if (insertReviewCase(entity) == -1L) {
            checkNotNull(findReviewCase(reviewCase.reviewCaseId)).toModel()
        } else {
            reviewCase
        }
        return LearningObservationMaterializationResult(
            created = false,
            event = null,
            canonicalFingerprint = null,
            outboxId = null,
            reviewCase = persisted,
        )
    }

    private suspend fun sourceAuthorityFailure(
        candidate: LearningObservationCandidate,
    ): Pair<LearningEvidenceReviewReason, String>? {
        val sourceFactId = candidate.sourceFactId
            ?: return LearningEvidenceReviewReason.SOURCE_FACT_MISSING to
                "The candidate has no canonical source-fact association."
        val sourceFactEntity = findCanonicalSourceFact(sourceFactId)
            ?: return LearningEvidenceReviewReason.SOURCE_FACT_MISSING to
                "The candidate source fact is absent from canonical storage."
        val sourceFact = try {
            sourceFactEntity.toModel()
        } catch (_: IllegalArgumentException) {
            return LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED to
                "The canonical source fact is invalid."
        }
        try {
            SourceFactEvidencePolicy.requirePersistedCandidate(sourceFact, candidate)
        } catch (_: IllegalArgumentException) {
            return LearningEvidenceReviewReason.SOURCE_FACT_POLICY_REJECTED to
                "The candidate does not match its canonical source fact."
        }
        val authority = findSourceAuthorityEntity(
            candidate.learnerId,
            candidate.source.name,
            candidate.sourceReferenceId,
        )?.toModel() ?: return LearningEvidenceReviewReason.SOURCE_AUTHORITY_MISSING to
            "No immutable local source fact authorizes this learner and source reference."
        if (authority.sourceFactId == null) {
            return LearningEvidenceReviewReason.SOURCE_AUTHORITY_MISSING to
                "Legacy source authority without a canonical source fact cannot authorize evidence."
        }
        if (authority.sourceFactId != sourceFactId ||
            candidate.practiceUnitId != authority.practiceUnitId ||
            candidate.problemRevisionId != authority.problemRevisionId
        ) {
            return LearningEvidenceReviewReason.SOURCE_AUTHORITY_MISMATCH to
                "The candidate fact or anchor differs from its immutable local source authority."
        }
        val anchor = findAnchorAuthority(
            authority.practiceUnitId,
            authority.problemRevisionId,
        ) ?: return LearningEvidenceReviewReason.MISSING_AUTHORITY to
            "The source authority no longer names an authoritative practice-unit anchor."
        if (anchor.subject != sourceFact.subject.name) {
            return LearningEvidenceReviewReason.SUBJECT_MISMATCH to
                "The canonical source fact subject differs from the authoritative problem subject."
        }
        return null
    }

    private suspend fun validateSourceAuthority(
        authority: LearningObservationSourceAuthorityRecord,
    ) {
        val sourceFactId = requireNotNull(authority.sourceFactId) {
            "New learning-observation source authorities require a canonical source-fact id"
        }
        val sourceFact = requireNotNull(findCanonicalSourceFact(sourceFactId)) {
            "Learning-observation source fact is not canonical"
        }.toModel()
        require(authority.learnerId == sourceFact.learnerScopeId) {
            "Source authority learner must match the canonical source fact"
        }
        require(authority.source == sourceFact.source) {
            "Source authority source must match the canonical source fact"
        }
        require(
            authority.sourceReferenceId ==
                SourceFactEvidencePolicy.canonicalSourceReferenceId(sourceFact),
        ) {
            "Source authority reference must match the canonical source fact"
        }
        val anchor = requireNotNull(
            findAnchorAuthority(authority.practiceUnitId, authority.problemRevisionId),
        ) {
            "Source authority must name an authoritative practice-unit anchor"
        }
        require(anchor.subject == sourceFact.subject.name) {
            "Source authority problem subject must match the canonical source fact"
        }
    }

    private suspend fun allocateSequence(learnerId: String): Long {
        initializeSequence(LearningSequenceEntity(learnerId, 0))
        val current = checkNotNull(lastAllocatedSequence(learnerId))
        check(current < Long.MAX_VALUE) { "Learning sequence exhausted for $learnerId" }
        val next = current + 1
        check(compareAndSetSequence(learnerId, current, next) == 1) {
            "Learning sequence CAS failed inside a serialized Room transaction"
        }
        return next
    }
}

private fun LearningObservationSourceAuthorityRecord.toEntity() =
    LearningObservationSourceAuthorityEntity(
        learnerId = learnerId,
        source = source.name,
        sourceReferenceId = sourceReferenceId,
        practiceUnitId = practiceUnitId,
        problemRevisionId = problemRevisionId,
        sourcePayloadFingerprint = sourcePayloadFingerprint,
        verifiedAtEpochMillis = verifiedAtEpochMillis,
        sourceFactId = sourceFactId,
    )

private fun LearningObservationSourceAuthorityEntity.toModel() =
    LearningObservationSourceAuthorityRecord(
        learnerId = learnerId,
        source = LearningObservationSource.valueOf(source),
        sourceReferenceId = sourceReferenceId,
        practiceUnitId = practiceUnitId,
        problemRevisionId = problemRevisionId,
        sourcePayloadFingerprint = sourcePayloadFingerprint,
        verifiedAtEpochMillis = verifiedAtEpochMillis,
        sourceFactId = sourceFactId,
    )

private fun LearningObservationSourceAuthorityRecord.provenanceKey(): String =
    "$learnerId:${source.name}:$sourceReferenceId"

private fun LearningObservationCandidate.provenanceKey(): String =
    "$learnerId:${source.name}:$sourceReferenceId"

private fun LearningObservationCandidate.toEntity(
    fingerprint: String,
) = LearningObservationCandidateEntity(
    candidateId = candidateId,
    learnerId = learnerId,
    source = source.name,
    sourceReferenceId = sourceReferenceId,
    sourceFactId = sourceFactId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    direction = direction.name,
    evidenceLevel = evidenceLevel.name,
    evidenceWeight = evidenceWeight,
    independence = independence.name,
    occurredAtEpochMillis = occurredAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    status = status.name,
    retryCount = retryCount,
    payloadFingerprint = fingerprint,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun LearningObservationCandidate.toAttributionEntities() =
    proposedAttributions.sortedBy(LearningObservationKnowledgeAttribution::bindingId)
        .mapIndexed { index, attribution ->
            LearningObservationCandidateAttributionEntity(
                candidateId = candidateId,
                ordinal = index,
                bindingId = attribution.bindingId,
                knowledgeNodeId = attribution.knowledgeNodeId,
                weight = attribution.weight,
                basisRevisionId = attribution.basisRevisionId,
                taxonomyVersion = attribution.taxonomyVersion,
                role = attribution.role.name,
                certainty = attribution.certainty.name,
            )
        }

private fun LearningObservationCandidateEntity.toModel(
    attributions: List<LearningObservationCandidateAttributionEntity>,
) = LearningObservationCandidate(
    candidateId = candidateId,
    learnerId = learnerId,
    source = LearningObservationSource.valueOf(source),
    sourceReferenceId = sourceReferenceId,
    sourceFactId = sourceFactId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    direction = LearningObservationDirection.valueOf(direction),
    evidenceLevel = LearningObservationEvidenceLevel.valueOf(evidenceLevel),
    evidenceWeight = evidenceWeight,
    independence = LearningObservationIndependence.valueOf(independence),
    proposedAttributions = attributions.map {
        LearningObservationKnowledgeAttribution(
            bindingId = it.bindingId,
            knowledgeNodeId = it.knowledgeNodeId,
            weight = it.weight,
            basisRevisionId = it.basisRevisionId,
            taxonomyVersion = it.taxonomyVersion,
            role = EvidenceAttributionRole.valueOf(it.role),
            certainty = EvidenceAttributionCertainty.valueOf(it.certainty),
        )
    },
    occurredAtEpochMillis = occurredAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    status = LearningObservationCandidateStatus.valueOf(status),
    retryCount = retryCount,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun AttributedLearningObservationEvent.toEntity(
    subject: String,
    fingerprint: String,
) = AttributedLearningObservationEventEntity(
    eventId = eventId,
    candidateId = candidateId,
    sourceFactId = sourceFactId,
    learnerId = learnerId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    subject = subject,
    direction = direction.name,
    evidenceLevel = evidenceLevel.name,
    evidenceWeight = evidenceWeight,
    independence = independence.name,
    occurredAtEpochMillis = occurredAtEpochMillis,
    confirmedAtEpochMillis = confirmedAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    eventSequence = eventSequence,
    canonicalFingerprint = fingerprint,
)

private fun AttributedLearningObservationEvent.toAttributionEntities() =
    attributions.sortedBy(LearningObservationKnowledgeAttribution::bindingId)
        .mapIndexed { index, attribution ->
            LearningObservationEventAttributionEntity(
                eventId = eventId,
                practiceUnitId = practiceUnitId,
                ordinal = index,
                bindingId = attribution.bindingId,
                knowledgeNodeId = attribution.knowledgeNodeId,
                weight = attribution.weight,
                basisRevisionId = attribution.basisRevisionId,
                taxonomyVersion = attribution.taxonomyVersion,
                role = attribution.role.name,
                certainty = attribution.certainty.name,
            )
        }

internal fun AttributedLearningObservationEventEntity.toModel(
    attributions: List<LearningObservationEventAttributionEntity>,
) = AttributedLearningObservationEvent(
    eventId = eventId,
    candidateId = candidateId,
    sourceFactId = sourceFactId,
    learnerId = learnerId,
    practiceUnitId = practiceUnitId,
    problemRevisionId = problemRevisionId,
    direction = LearningObservationDirection.valueOf(direction),
    evidenceLevel = LearningObservationEvidenceLevel.valueOf(evidenceLevel),
    evidenceWeight = evidenceWeight,
    independence = LearningObservationIndependence.valueOf(independence),
    attributions = attributions.map {
        LearningObservationKnowledgeAttribution(
            bindingId = it.bindingId,
            knowledgeNodeId = it.knowledgeNodeId,
            weight = it.weight,
            basisRevisionId = it.basisRevisionId,
            taxonomyVersion = it.taxonomyVersion,
            role = EvidenceAttributionRole.valueOf(it.role),
            certainty = EvidenceAttributionCertainty.valueOf(it.certainty),
        )
    },
    occurredAtEpochMillis = occurredAtEpochMillis,
    confirmedAtEpochMillis = confirmedAtEpochMillis,
    modelVersion = modelVersion,
    evidenceLocator = evidenceLocator,
    eventSequence = eventSequence,
)

internal fun AttributedLearningObservationEventEntity.toOutbox() = ProjectionOutboxEntity(
    outboxId = "learning-outbox:$learnerId:$EVENT_KIND_LEARNING_OBSERVATION:$eventId",
    learnerId = learnerId,
    outboxSequence = eventSequence,
    eventKind = EVENT_KIND_LEARNING_OBSERVATION,
    eventId = eventId,
    canonicalFingerprint = canonicalFingerprint,
    status = StudyDbValue.OutboxStatus.PENDING,
    createdAtEpochMillis = confirmedAtEpochMillis,
)

private fun LearningEvidenceReviewCase.toEntity() = LearningEvidenceReviewCaseEntity(
    reviewCaseId = reviewCaseId,
    candidateId = candidateId,
    learnerId = learnerId,
    proposedEventId = proposedEventId,
    reason = reason.name,
    detail = detail,
    status = status.name,
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
)

private fun LearningEvidenceReviewCaseEntity.toModel() = LearningEvidenceReviewCase(
    reviewCaseId = reviewCaseId,
    candidateId = candidateId,
    learnerId = learnerId,
    proposedEventId = proposedEventId,
    reason = LearningEvidenceReviewReason.valueOf(reason),
    detail = detail,
    status = LearningEvidenceReviewStatus.valueOf(status),
    createdAtEpochMillis = createdAtEpochMillis,
    resolvedAtEpochMillis = resolvedAtEpochMillis,
)

private fun stableReviewCaseId(
    candidateId: String,
    eventId: String,
    reason: LearningEvidenceReviewReason,
): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "$candidateId\u0000$eventId\u0000${reason.name}".toByteArray(StandardCharsets.UTF_8),
    ).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "learning-review:$digest"
}

private suspend fun <T> List<T>.insertWhenNotEmpty(insert: suspend (List<T>) -> Unit) {
    if (isNotEmpty()) insert(this)
}
