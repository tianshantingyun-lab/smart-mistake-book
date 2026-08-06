package com.tingyun.smartmistakebook.core.database.dao

import androidx.room3.Dao
import androidx.room3.ColumnInfo
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.database.ImmutablePayloadConflictException
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateStatusCasResult
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateStatusChangeCommand
import com.tingyun.smartmistakebook.core.database.LearningObservationCandidateWriteResult
import com.tingyun.smartmistakebook.core.database.LEARNING_OBSERVATION_ADMISSION_POLICY_VERSION
import com.tingyun.smartmistakebook.core.database.LearningObservationSourceFactProofFingerprint
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
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationEventAdmissionEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceAuthorityEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactProofEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningObservationSourceFactEntity
import com.tingyun.smartmistakebook.core.database.entity.LearningSequenceEntity
import com.tingyun.smartmistakebook.core.database.entity.ProjectionOutboxEntity
import com.tingyun.smartmistakebook.core.model.AttributedLearningObservationEvent
import com.tingyun.smartmistakebook.core.model.CapturedTutorProblemIdentity
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
import com.tingyun.smartmistakebook.core.model.LearningObservationSourceFact
import com.tingyun.smartmistakebook.core.model.LearningObservationProjectionAdmission
import com.tingyun.smartmistakebook.core.model.SourceFactEvidencePolicy
import com.tingyun.smartmistakebook.core.model.allowedExternalTransitions
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal const val EVENT_KIND_LEARNING_OBSERVATION = "ATTRIBUTED_LEARNING_OBSERVATION"

internal data class LearningObservationAttributionAuthorityRow(
    val bindingId: String,
    val practiceUnitId: String,
    val knowledgeNodeId: String,
    val basisRevisionId: String,
    val taxonomyVersion: String,
    val problemSubject: String,
    val knowledgeSubject: String,
)

internal data class CapturedTutorSourceFactProofCandidateRow(
    @ColumnInfo(name = "source_fact_id")
    val sourceFactId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    val subject: String,
    @ColumnInfo(name = "anchor_id")
    val anchorId: String,
    @ColumnInfo(name = "source_reference_id")
    val sourceReferenceId: String,
    @ColumnInfo(name = "source_fingerprint")
    val sourceFingerprint: String,
    @ColumnInfo(name = "conversation_id")
    val conversationId: String?,
    @ColumnInfo(name = "conversation_generation")
    val conversationGeneration: Long?,
    @ColumnInfo(name = "turn_receipt_id")
    val turnReceiptId: String?,
    @ColumnInfo(name = "evidence_request_id")
    val evidenceRequestId: String?,
    val source: String,
    @ColumnInfo(name = "request_kind")
    val requestKind: String,
    @ColumnInfo(name = "anchor_question_fingerprint")
    val anchorQuestionFingerprint: String,
    @ColumnInfo(name = "anchor_revision_fingerprint")
    val anchorRevisionFingerprint: String,
    @ColumnInfo(name = "anchor_fingerprint_version")
    val anchorFingerprintVersion: String,
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "draft_revision_fingerprint")
    val draftRevisionFingerprint: String,
    @ColumnInfo(name = "source_locator_kind")
    val sourceLocatorKind: String,
    @ColumnInfo(name = "source_locator_id")
    val sourceLocatorId: String,
    @ColumnInfo(name = "interaction_submitted_at_epoch_millis")
    val interactionSubmittedAtEpochMillis: Long,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @ColumnInfo(name = "target_version")
    val targetVersion: String,
    @ColumnInfo(name = "target_fingerprint")
    val targetFingerprint: String,
    @ColumnInfo(name = "target_created_at_epoch_millis")
    val targetCreatedAtEpochMillis: Long,
)

@Dao
internal abstract class LearningObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSourceAuthority(
        entity: LearningObservationSourceAuthorityEntity,
    ): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidate(entity: LearningObservationCandidateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCandidateAttributions(
        entities: List<LearningObservationCandidateAttributionEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvent(entity: AttributedLearningObservationEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEventAdmission(
        entity: LearningObservationEventAdmissionEntity,
    )

    @Query(
        "SELECT * FROM learning_observation_event_admission WHERE event_id = :eventId LIMIT 1",
    )
    internal abstract suspend fun findEventAdmission(
        eventId: String,
    ): LearningObservationEventAdmissionEntity?

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSourceFactProof(
        entity: LearningObservationSourceFactProofEntity,
    )

    @Query(
        """
        SELECT * FROM learning_observation_source_fact_proof
        WHERE source_fact_id = :sourceFactId
        LIMIT 1
        """,
    )
    internal abstract suspend fun findSourceFactProof(
        sourceFactId: String,
    ): LearningObservationSourceFactProofEntity?

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
        SELECT source_fact.source_fact_id AS source_fact_id,
               source_fact.learner_id AS learner_id,
               source_fact.subject AS subject,
               source_fact.anchor_id AS anchor_id,
               request.evidence_request_id AS source_reference_id,
               source_fact.payload_fingerprint AS source_fingerprint,
               source_fact.conversation_id AS conversation_id,
               source_fact.conversation_generation AS conversation_generation,
               source_fact.turn_receipt_id AS turn_receipt_id,
               source_fact.evidence_request_id AS evidence_request_id,
               source_fact.source AS source,
               request.kind AS request_kind,
               anchor.question_fingerprint AS anchor_question_fingerprint,
               anchor.revision_fingerprint AS anchor_revision_fingerprint,
               anchor.fingerprint_version AS anchor_fingerprint_version,
               session.draft_id AS draft_id,
               draft_revision.document_fingerprint AS draft_revision_fingerprint,
               'TUTOR_RESPONSE' AS source_locator_kind,
               response.session_id || ':' || response.cycle_ordinal || ':' ||
                   response.turn_ordinal AS source_locator_id,
               response.choice_submitted_at_epoch_millis
                   AS interaction_submitted_at_epoch_millis,
               receipt.practice_unit_id AS target_id,
               receipt.problem_revision_id AS target_version,
               receipt.payload_fingerprint AS target_fingerprint,
               receipt.committed_at_epoch_millis AS target_created_at_epoch_millis
        FROM learning_observation_source_fact AS source_fact
        JOIN learning_problem_anchor AS anchor
          ON anchor.anchor_id = source_fact.anchor_id
         AND anchor.learner_id = source_fact.learner_id
         AND anchor.subject = source_fact.subject
        JOIN tutor_evidence_request AS request
          ON request.evidence_request_id = source_fact.evidence_request_id
         AND request.learner_id = source_fact.learner_id
         AND request.conversation_id = source_fact.conversation_id
         AND request.conversation_generation = source_fact.conversation_generation
         AND request.turn_receipt_id = source_fact.turn_receipt_id
         AND request.problem_anchor_id = source_fact.anchor_id
         AND request.subject = source_fact.subject
         AND request.status = 'SUBMITTED'
         AND request.terminal_source_fact_id = source_fact.source_fact_id
        JOIN tutor_turn_receipt AS turn
          ON turn.turn_receipt_id = source_fact.turn_receipt_id
         AND turn.learner_id = source_fact.learner_id
         AND turn.conversation_id = source_fact.conversation_id
         AND turn.conversation_generation = source_fact.conversation_generation
         AND turn.turn_ordinal = request.turn_ordinal
         AND turn.problem_anchor_id = source_fact.anchor_id
         AND turn.subject = source_fact.subject
        JOIN tutor_turn_response AS response
          ON response.evidence_request_id = source_fact.evidence_request_id
         AND response.turn_ordinal = request.turn_ordinal
         AND response.diagnostic_stem_markdown IS NOT NULL
         AND response.selected_choice_id IS NOT NULL
         AND response.selected_choice_markdown IS NOT NULL
         AND response.selection_was_correct IS NOT NULL
         AND response.choice_submitted_at_epoch_millis IS NOT NULL
        JOIN tutor_session AS session
          ON session.session_id = response.session_id
         AND session.draft_revision_number = response.revision_number
         AND response.question_document_id = 'document-' || session.draft_id
        JOIN problem_draft_revision AS draft_revision
          ON draft_revision.draft_id = session.draft_id
         AND draft_revision.revision_number = session.draft_revision_number
         AND draft_revision.subject = source_fact.subject
        JOIN problem_draft AS draft
          ON draft.draft_id = session.draft_id
         AND draft.current_revision_number = session.draft_revision_number
         AND draft.status = 'COMMITTED'
        JOIN problem_draft_commit_receipt AS receipt
          ON receipt.draft_id = session.draft_id
         AND receipt.draft_revision_number = session.draft_revision_number
        JOIN practice_unit AS unit
          ON unit.practice_unit_id = receipt.practice_unit_id
         AND unit.problem_revision_id = receipt.problem_revision_id
         AND unit.problem_id = receipt.problem_id
        JOIN problem AS problem
          ON problem.problem_id = unit.problem_id
         AND problem.subject = source_fact.subject
        WHERE source_fact.source_fact_id = :sourceFactId
          AND source_fact.source = 'TUTOR_CHOICE'
          AND request.kind = 'CHOICE'
        """,
    )
    protected abstract suspend fun findCapturedTutorResponseProofCandidates(
        sourceFactId: String,
    ): List<CapturedTutorSourceFactProofCandidateRow>

    @Query(
        """
        SELECT source_fact.source_fact_id AS source_fact_id,
               source_fact.learner_id AS learner_id,
               source_fact.subject AS subject,
               source_fact.anchor_id AS anchor_id,
               request.evidence_request_id AS source_reference_id,
               source_fact.payload_fingerprint AS source_fingerprint,
               source_fact.conversation_id AS conversation_id,
               source_fact.conversation_generation AS conversation_generation,
               source_fact.turn_receipt_id AS turn_receipt_id,
               source_fact.evidence_request_id AS evidence_request_id,
               source_fact.source AS source,
               request.kind AS request_kind,
               anchor.question_fingerprint AS anchor_question_fingerprint,
               anchor.revision_fingerprint AS anchor_revision_fingerprint,
               anchor.fingerprint_version AS anchor_fingerprint_version,
               session.draft_id AS draft_id,
               draft_revision.document_fingerprint AS draft_revision_fingerprint,
               'TUTOR_VISUAL_EVIDENCE' AS source_locator_kind,
               visual.hit_proof_id AS source_locator_id,
               visual.submitted_at_epoch_millis
                   AS interaction_submitted_at_epoch_millis,
               receipt.practice_unit_id AS target_id,
               receipt.problem_revision_id AS target_version,
               receipt.payload_fingerprint AS target_fingerprint,
               receipt.committed_at_epoch_millis AS target_created_at_epoch_millis
        FROM learning_observation_source_fact AS source_fact
        JOIN learning_problem_anchor AS anchor
          ON anchor.anchor_id = source_fact.anchor_id
         AND anchor.learner_id = source_fact.learner_id
         AND anchor.subject = source_fact.subject
        JOIN tutor_evidence_request AS request
          ON request.evidence_request_id = source_fact.evidence_request_id
         AND request.learner_id = source_fact.learner_id
         AND request.conversation_id = source_fact.conversation_id
         AND request.conversation_generation = source_fact.conversation_generation
         AND request.turn_receipt_id = source_fact.turn_receipt_id
         AND request.problem_anchor_id = source_fact.anchor_id
         AND request.subject = source_fact.subject
         AND request.status = 'SUBMITTED'
         AND request.terminal_source_fact_id = source_fact.source_fact_id
        JOIN tutor_turn_receipt AS turn
          ON turn.turn_receipt_id = source_fact.turn_receipt_id
         AND turn.learner_id = source_fact.learner_id
         AND turn.conversation_id = source_fact.conversation_id
         AND turn.conversation_generation = source_fact.conversation_generation
         AND turn.turn_ordinal = request.turn_ordinal
         AND turn.problem_anchor_id = source_fact.anchor_id
         AND turn.subject = source_fact.subject
        JOIN tutor_visual_target_evidence AS visual
          ON visual.model_task_request_id = source_fact.evidence_request_id
         AND visual.turn_ordinal = request.turn_ordinal
        JOIN tutor_session AS session
          ON session.session_id = visual.session_id
         AND session.draft_revision_number = visual.revision_number
         AND visual.question_document_id = 'document-' || session.draft_id
        JOIN problem_draft_revision AS draft_revision
          ON draft_revision.draft_id = session.draft_id
         AND draft_revision.revision_number = session.draft_revision_number
         AND draft_revision.subject = source_fact.subject
        JOIN problem_draft AS draft
          ON draft.draft_id = session.draft_id
         AND draft.current_revision_number = session.draft_revision_number
         AND draft.status = 'COMMITTED'
        JOIN problem_draft_commit_receipt AS receipt
          ON receipt.draft_id = session.draft_id
         AND receipt.draft_revision_number = session.draft_revision_number
        JOIN practice_unit AS unit
          ON unit.practice_unit_id = receipt.practice_unit_id
         AND unit.problem_revision_id = receipt.problem_revision_id
         AND unit.problem_id = receipt.problem_id
        JOIN problem AS problem
          ON problem.problem_id = unit.problem_id
         AND problem.subject = source_fact.subject
        WHERE source_fact.source_fact_id = :sourceFactId
          AND source_fact.source = 'TUTOR_VISUAL_TARGET'
          AND request.kind = 'VISUAL_TARGET'
        """,
    )
    protected abstract suspend fun findCapturedTutorVisualProofCandidates(
        sourceFactId: String,
    ): List<CapturedTutorSourceFactProofCandidateRow>

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
        val proof = validateSourceAuthority(authority)
        if (insertSourceAuthority(authority.toEntity()) != -1L) {
            require(proof.matches(authority)) {
                "Source authority must match the immutable source-fact target proof"
            }
            return LearningObservationSourceAuthorityWriteResult(created = true, authority = authority)
        }
        val existing = listOfNotNull(
            authority.sourceFactId?.let { findSourceAuthorityBySourceFact(it) },
            findSourceAuthorityEntity(
                authority.learnerId,
                authority.source.name,
                authority.sourceReferenceId,
            ),
        ).distinct()
        if (existing.size == 1 && existing.single().toModel() == authority) {
            require(proof.matches(authority)) {
                "Source authority must match the immutable source-fact target proof"
            }
            return LearningObservationSourceAuthorityWriteResult(
                created = false,
                authority = authority,
            )
        }
        throw ImmutablePayloadConflictException(
            "learning_observation_source_authority",
            authority.provenanceKey(),
        )
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
        val sourceProof = findSourceFactProof(sourceFactId)
            ?.takeIf(LearningObservationSourceFactProofFingerprint::isValid)
            ?.takeIf { proof ->
                proof.matches(sourceFact, sourceFactEntity.payloadFingerprint) &&
                    command.confirmedAtEpochMillis >= proof.attestedAtEpochMillis
            }
            ?: return review(
                candidate,
                command,
                LearningEvidenceReviewReason.MISSING_AUTHORITY,
                "The source fact has no valid local projection proof.",
            )

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
            if (authority.problemSubject != sourceFact.subject.name ||
                authority.knowledgeSubject != sourceFact.subject.name
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
        val entity = event.toEntity(sourceFact.subject.name, fingerprint)
        val admission = LearningObservationProjectionAdmission.create(
            observation = event,
            sourceFactProofFingerprint = sourceProof.proofFingerprint,
            policyVersion = LEARNING_OBSERVATION_ADMISSION_POLICY_VERSION,
        )
        val outbox = entity.toOutbox()
        insertEvent(entity)
        insertEventAdmission(
            LearningObservationEventAdmissionEntity(
                eventId = event.eventId,
                sourceFactId = sourceFactId,
                rawEventCanonicalFingerprint = admission.rawEventCanonicalFingerprint,
                sourceFactProofFingerprint = admission.sourceFactProofFingerprint,
                policyVersion = admission.policyVersion,
                admissionFingerprint = admission.admissionFingerprint,
                admittedAtEpochMillis = command.confirmedAtEpochMillis,
            ),
        )
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
        val proof = ensureSourceFactProof(
            sourceFact = sourceFact,
            sourceFingerprint = sourceFactEntity.payloadFingerprint,
            attestedAtEpochMillis = authority.verifiedAtEpochMillis,
        )
            ?: return LearningEvidenceReviewReason.MISSING_AUTHORITY to
                "The source fact has no trusted local target proof."
        if (!proof.matches(sourceFact, sourceFactEntity.payloadFingerprint, authority)) {
            return LearningEvidenceReviewReason.SOURCE_AUTHORITY_MISMATCH to
                "The source authority differs from its immutable target proof."
        }
        return null
    }

    private suspend fun validateSourceAuthority(
        authority: LearningObservationSourceAuthorityRecord,
    ): LearningObservationSourceFactProofEntity {
        val sourceFactId = requireNotNull(authority.sourceFactId) {
            "New learning-observation source authorities require a canonical source-fact id"
        }
        val sourceFactEntity = requireNotNull(findCanonicalSourceFact(sourceFactId)) {
            "Learning-observation source fact is not canonical"
        }
        val sourceFact = sourceFactEntity.toModel()
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
        val proof = requireNotNull(
            ensureSourceFactProof(
                sourceFact = sourceFact,
                sourceFingerprint = sourceFactEntity.payloadFingerprint,
                attestedAtEpochMillis = authority.verifiedAtEpochMillis,
            ),
        ) {
            "Source authority requires one exact trusted local source-fact proof"
        }
        require(proof.matches(sourceFact, sourceFactEntity.payloadFingerprint)) {
            "Source authority requires a proof for the exact canonical source fact"
        }
        return proof
    }

    private suspend fun ensureSourceFactProof(
        sourceFact: LearningObservationSourceFact,
        sourceFingerprint: String,
        attestedAtEpochMillis: Long,
    ): LearningObservationSourceFactProofEntity? {
        findSourceFactProof(sourceFact.sourceFactId)?.let { existing ->
            return existing.takeIf { proof ->
                proof.attestedAtEpochMillis == attestedAtEpochMillis &&
                    proof.matches(sourceFact, sourceFingerprint)
            }
        }
        val candidates = (
            findCapturedTutorResponseProofCandidates(sourceFact.sourceFactId) +
                findCapturedTutorVisualProofCandidates(sourceFact.sourceFactId)
            ).distinct().mapNotNull { candidate ->
                candidate.toProof(
                    sourceFact = sourceFact,
                    sourceFingerprint = sourceFingerprint,
                    attestedAtEpochMillis = attestedAtEpochMillis,
                )
            }
        if (candidates.size != 1) return null
        val candidate = candidates.single()
        insertSourceFactProof(candidate)
        return findSourceFactProof(sourceFact.sourceFactId)?.takeIf { proof ->
            proof == candidate && LearningObservationSourceFactProofFingerprint.isValid(proof)
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

