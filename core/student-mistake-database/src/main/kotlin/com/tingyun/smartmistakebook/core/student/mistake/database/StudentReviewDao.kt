package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * Read-only review surface shared by the write DAO and learner-bound review ports.
 *
 * Keeping review plan/queue/session/receipt reads here gives the review session feature a stable
 * read boundary while [StudentMistakeDao] retains the transaction-heavy write path.
 */
@Dao
internal abstract class StudentReviewDao : StudentCaptureIdentityDao() {
    @Query(
        """
        SELECT COALESCE(MAX(change_version), 0)
        FROM student_learner_change
        WHERE learner_id = :learnerId
        """,
    )
    protected abstract suspend fun readChangeVersion(learnerId: String): Long

    @Query(
        """
        SELECT
          candidate.candidate_id AS candidateId,
          candidate.learner_id AS learnerId,
          problem.subject AS subject,
          problem.problem_id AS problemId,
          candidate.practice_unit_id AS practiceUnitId,
          candidate.basis_revision_id AS basisRevisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
          candidate.reason_codes_wire AS reasonCodesWire,
          candidate.item_family_id AS itemFamilyId,
          candidate.estimated_duration_seconds AS estimatedDurationSeconds,
          candidate.available_at_epoch_millis AS availableAtEpochMillis,
          candidate.due_at_epoch_millis AS dueAtEpochMillis,
          candidate.source_evidence_event_kind AS sourceEvidenceEventKind,
          candidate.source_evidence_event_id AS sourceEvidenceEventId,
          candidate.source_evidence_sequence AS sourceEvidenceSequence,
          candidate.source_evidence_canonical_fingerprint AS sourceEvidenceCanonicalFingerprint,
          candidate.candidate_version AS candidateVersion,
          candidate.updated_at_epoch_millis AS updatedAtEpochMillis
        FROM student_review_candidate AS candidate
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = candidate.basis_revision_id
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = revision.problem_id
        INNER JOIN student_problem_collection AS collection
          ON collection.practice_unit_id = candidate.practice_unit_id
        WHERE candidate.learner_id = :learnerId
          AND candidate.available_at_epoch_millis <= :nowEpochMillis
          AND problem.lifecycle_state = 'ACTIVE'
          AND collection.mistake_state = 'ACTIVE'
          AND (
            :cursorAvailableAtEpochMillis IS NULL OR
            candidate.available_at_epoch_millis > :cursorAvailableAtEpochMillis OR
            (
              candidate.available_at_epoch_millis = :cursorAvailableAtEpochMillis AND
              candidate.candidate_id > :cursorCandidateId
            )
          )
        ORDER BY candidate.available_at_epoch_millis ASC, candidate.candidate_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readReviewCandidateRows(
        learnerId: String,
        nowEpochMillis: Long,
        cursorAvailableAtEpochMillis: Long?,
        cursorCandidateId: String?,
        limit: Int,
    ): List<StudentReviewCandidateReadRow>

    @Query(
        """
        SELECT basis_revision_id AS basisRevisionId,
               knowledge_subject AS knowledgeSubject,
               knowledge_node_id AS knowledgeNodeId,
               knowledge_taxonomy_version AS knowledgeTaxonomyVersion,
               knowledge_pack_version AS knowledgePackVersion,
               knowledge_manifest_fingerprint AS knowledgeManifestFingerprint,
               knowledge_activation_generation AS knowledgeActivationGeneration
        FROM student_problem_classification_result AS classification
        WHERE classification.basis_revision_id IN (:basisRevisionIds)
          AND classification.dimension = 'KNOWLEDGE'
          AND classification.status = 'ACCEPTED'
          AND (
            classification.organization_receipt_id = (
              SELECT receipt.receipt_id
              FROM student_problem_organization_receipt AS receipt
              WHERE receipt.basis_revision_id = classification.basis_revision_id
                AND receipt.status = 'COMPLETED'
              ORDER BY receipt.organization_revision DESC
              LIMIT 1
            )
            OR (
              classification.organization_receipt_id IS NULL
              AND NOT EXISTS (
                SELECT 1
                FROM student_problem_organization_receipt AS receipt
                WHERE receipt.basis_revision_id = classification.basis_revision_id
                  AND receipt.status = 'COMPLETED'
              )
            )
          )
        ORDER BY classification.basis_revision_id ASC,
                 classification.knowledge_node_id ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readAcceptedReviewKnowledgeRows(
        basisRevisionIds: List<String>,
        limit: Int,
    ): List<StudentReviewAcceptedKnowledgeRow>

    @Query(
        """
        SELECT receipt.basis_revision_id AS basisRevisionId,
               facet.family_id AS familyId,
               receipt.basis_document_canonical_fingerprint
                   AS basisDocumentCanonicalFingerprint
        FROM student_problem_organization_facet AS facet
        INNER JOIN student_problem_organization_receipt AS receipt
          ON receipt.receipt_id = facet.organization_receipt_id
        WHERE receipt.basis_revision_id IN (:basisRevisionIds)
          AND receipt.status = 'COMPLETED'
          AND facet.dimension = 'PROBLEM_FAMILY'
          AND receipt.organization_revision = (
            SELECT MAX(latest.organization_revision)
            FROM student_problem_organization_receipt AS latest
            WHERE latest.basis_revision_id = receipt.basis_revision_id
              AND latest.status = 'COMPLETED'
          )
        ORDER BY receipt.basis_revision_id ASC, facet.family_id ASC
        """,
    )
    abstract suspend fun readReviewedProblemFamilies(
        basisRevisionIds: List<String>,
    ): List<StudentReviewOrganizationFamilyRow>

    @Query(
        """
        SELECT plan_id, plan_canonical_fingerprint, learner_id,
               local_day_epoch_day, time_zone_id, time_budget_seconds,
               generated_at_epoch_millis, planner_version
        FROM student_review_plan
        WHERE plan_id = :planId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewPlan(planId: String): StudentReviewPlanEntity?

    @Query(
        """
        SELECT plan_id, plan_canonical_fingerprint, learner_id,
               local_day_epoch_day, time_zone_id, time_budget_seconds,
               generated_at_epoch_millis, planner_version
        FROM student_review_plan
        WHERE learner_id = :learnerId
          AND local_day_epoch_day = :localDayEpochDay
        LIMIT 1
        """,
    )
    abstract suspend fun readReviewPlan(
        learnerId: String,
        localDayEpochDay: Long,
    ): StudentReviewPlanEntity?

    @Query(
        """
        SELECT queue_item_id, plan_id, learner_id, practice_unit_id,
               basis_revision_id, scheduled_order, estimated_duration_seconds,
               reason_codes_wire, source_evidence_event_kind,
               source_evidence_event_id, source_evidence_sequence,
               source_evidence_canonical_fingerprint, state,
               created_at_epoch_millis, state_changed_at_epoch_millis
        FROM student_review_queue_item
        WHERE plan_id = :planId
        ORDER BY scheduled_order ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readReviewQueueItems(
        planId: String,
        limit: Int,
    ): List<StudentReviewQueueItemEntity>

    @Query(
        """
        SELECT
          item.queue_item_id AS queueItemId,
          item.plan_id AS planId,
          item.learner_id AS learnerId,
          problem.subject AS subject,
          problem.problem_id AS problemId,
          item.practice_unit_id AS practiceUnitId,
          item.basis_revision_id AS basisRevisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
          item.scheduled_order AS scheduledOrder,
          item.estimated_duration_seconds AS estimatedDurationSeconds,
          item.reason_codes_wire AS reasonCodesWire,
          item.source_evidence_event_kind AS sourceEvidenceEventKind,
          item.source_evidence_event_id AS sourceEvidenceEventId,
          item.source_evidence_sequence AS sourceEvidenceSequence,
          item.source_evidence_canonical_fingerprint AS sourceEvidenceCanonicalFingerprint,
          item.state AS state,
          item.created_at_epoch_millis AS createdAtEpochMillis,
          item.state_changed_at_epoch_millis AS stateChangedAtEpochMillis
        FROM student_review_queue_item AS item
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = item.basis_revision_id
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = revision.problem_id
        INNER JOIN student_problem_collection AS collection
          ON collection.practice_unit_id = item.practice_unit_id
        WHERE item.plan_id = :planId
          AND problem.lifecycle_state = 'ACTIVE'
          AND collection.mistake_state = 'ACTIVE'
        ORDER BY item.scheduled_order ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readReviewQueueRows(
        planId: String,
        limit: Int,
    ): List<StudentReviewQueueReadRow>

    @Query(
        """
        SELECT
          item.queue_item_id AS queueItemId,
          item.plan_id AS planId,
          item.learner_id AS learnerId,
          problem.subject AS subject,
          problem.problem_id AS problemId,
          item.practice_unit_id AS practiceUnitId,
          item.basis_revision_id AS basisRevisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
          item.scheduled_order AS scheduledOrder,
          item.estimated_duration_seconds AS estimatedDurationSeconds,
          item.reason_codes_wire AS reasonCodesWire,
          item.source_evidence_event_kind AS sourceEvidenceEventKind,
          item.source_evidence_event_id AS sourceEvidenceEventId,
          item.source_evidence_sequence AS sourceEvidenceSequence,
          item.source_evidence_canonical_fingerprint AS sourceEvidenceCanonicalFingerprint,
          item.state AS state,
          item.created_at_epoch_millis AS createdAtEpochMillis,
          item.state_changed_at_epoch_millis AS stateChangedAtEpochMillis
        FROM student_review_queue_item AS item
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = item.basis_revision_id
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = revision.problem_id
        WHERE item.plan_id = :planId
        ORDER BY item.scheduled_order ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun readReviewPlanSnapshotRows(
        planId: String,
        limit: Int,
    ): List<StudentReviewQueueReadRow>

    @Query(
        """
        SELECT queue_item_id, plan_id, learner_id, practice_unit_id,
               basis_revision_id, scheduled_order, estimated_duration_seconds,
               reason_codes_wire, source_evidence_event_kind,
               source_evidence_event_id, source_evidence_sequence,
               source_evidence_canonical_fingerprint, state,
               created_at_epoch_millis, state_changed_at_epoch_millis
        FROM student_review_queue_item
        WHERE queue_item_id = :queueItemId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewQueueItem(
        queueItemId: String,
    ): StudentReviewQueueItemEntity?

    @Query(
        """
        SELECT
          item.queue_item_id AS queueItemId,
          item.plan_id AS planId,
          item.learner_id AS learnerId,
          problem.subject AS subject,
          problem.problem_id AS problemId,
          item.practice_unit_id AS practiceUnitId,
          item.basis_revision_id AS basisRevisionId,
          revision.revision_number AS revisionNumber,
          revision.document_canonical_fingerprint AS documentCanonicalFingerprint,
          item.scheduled_order AS scheduledOrder,
          item.estimated_duration_seconds AS estimatedDurationSeconds,
          item.reason_codes_wire AS reasonCodesWire,
          item.source_evidence_event_kind AS sourceEvidenceEventKind,
          item.source_evidence_event_id AS sourceEvidenceEventId,
          item.source_evidence_sequence AS sourceEvidenceSequence,
          item.source_evidence_canonical_fingerprint AS sourceEvidenceCanonicalFingerprint,
          item.state AS state,
          item.created_at_epoch_millis AS createdAtEpochMillis,
          item.state_changed_at_epoch_millis AS stateChangedAtEpochMillis
        FROM student_review_queue_item AS item
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = item.basis_revision_id
        INNER JOIN student_problem_document AS problem
          ON problem.problem_id = revision.problem_id
        WHERE item.queue_item_id = :queueItemId
          AND item.learner_id = :learnerId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewQueueRow(
        learnerId: String,
        queueItemId: String,
    ): StudentReviewQueueReadRow?

    @Query(
        """
        SELECT
          item.queue_item_id AS queueItemId,
          item.plan_id AS planId,
          item.learner_id AS learnerId,
          item.basis_revision_id AS basisRevisionId,
          CASE
            WHEN revision.revision_id IS NOT NULL
             AND problem.problem_id IS NOT NULL
             AND collection.practice_unit_id IS NOT NULL
             AND problem.learner_id = item.learner_id
             AND problem.problem_id = revision.problem_id
             AND problem.primary_practice_unit_id = item.practice_unit_id
             AND problem.current_revision_id = revision.revision_id
             AND problem.lifecycle_state = 'ACTIVE'
             AND collection.problem_id = problem.problem_id
             AND collection.mistake_state = 'ACTIVE'
            THEN 1
            ELSE 0
          END AS savedRevisionEligible,
          (
            SELECT COUNT(*)
            FROM student_problem_classification_result AS classification
            WHERE classification.basis_revision_id = item.basis_revision_id
              AND classification.dimension = 'KNOWLEDGE'
              AND classification.status = 'ACCEPTED'
              AND classification.knowledge_subject = problem.subject
              AND (
                classification.organization_receipt_id = (
                  SELECT receipt.receipt_id
                  FROM student_problem_organization_receipt AS receipt
                  WHERE receipt.basis_revision_id = classification.basis_revision_id
                    AND receipt.status = 'COMPLETED'
                  ORDER BY receipt.organization_revision DESC
                  LIMIT 1
                )
                OR (
                  classification.organization_receipt_id IS NULL
                  AND NOT EXISTS (
                    SELECT 1
                    FROM student_problem_organization_receipt AS receipt
                    WHERE receipt.basis_revision_id = classification.basis_revision_id
                      AND receipt.status = 'COMPLETED'
                  )
                )
              )
          ) AS acceptedKnowledgeCount
        FROM student_review_queue_item AS item
        LEFT JOIN student_problem_revision AS revision
          ON revision.revision_id = item.basis_revision_id
        LEFT JOIN student_problem_document AS problem
          ON problem.problem_id = revision.problem_id
        LEFT JOIN student_problem_collection AS collection
          ON collection.practice_unit_id = item.practice_unit_id
        WHERE item.queue_item_id = :queueItemId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewQueueLocalReadiness(
        queueItemId: String,
    ): StudentReviewQueueLocalReadinessRow?

    @Query(
        """
        SELECT session_id, session_canonical_fingerprint, learner_id, plan_id,
               active_learner_id, state, session_version, current_queue_item_id,
               current_presentation_id, started_at_epoch_millis,
               updated_at_epoch_millis, completed_at_epoch_millis
        FROM student_review_session
        WHERE session_id = :sessionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewSession(
        sessionId: String,
    ): StudentReviewSessionEntity?

    @Query(
        """
        SELECT session_id, session_canonical_fingerprint, learner_id, plan_id,
               active_learner_id, state, session_version, current_queue_item_id,
               current_presentation_id, started_at_epoch_millis,
               updated_at_epoch_millis, completed_at_epoch_millis
        FROM student_review_session
        WHERE active_learner_id = :learnerId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readActiveReviewSession(
        learnerId: String,
    ): StudentReviewSessionEntity?

    @Query(
        """
        SELECT EXISTS(
          SELECT 1
          FROM student_review_queue_item AS item
          WHERE item.learner_id = :learnerId
            AND item.state = 'PRESENTED'
            AND NOT EXISTS (
              SELECT 1
              FROM student_review_session AS session
              WHERE session.current_queue_item_id = item.queue_item_id
                AND session.state = 'ACTIVE'
                AND session.plan_id = item.plan_id
                AND session.learner_id = item.learner_id
                AND session.active_learner_id = item.learner_id
            )
        )
        """,
    )
    protected abstract suspend fun hasUnownedPresentedReviewItem(
        learnerId: String,
    ): Boolean

    @Query(
        """
        SELECT item.queue_item_id, item.plan_id, item.learner_id,
               item.practice_unit_id, item.basis_revision_id,
               item.scheduled_order, item.estimated_duration_seconds,
               item.reason_codes_wire, item.source_evidence_event_kind,
               item.source_evidence_event_id, item.source_evidence_sequence,
               item.source_evidence_canonical_fingerprint, item.state,
               item.created_at_epoch_millis, item.state_changed_at_epoch_millis
        FROM student_review_queue_item AS item
        WHERE item.plan_id = :planId
          AND item.learner_id = :learnerId
          AND item.state = 'READY'
          AND item.scheduled_order > :afterScheduledOrder
        ORDER BY item.scheduled_order ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readNextReadyReviewQueueItem(
        learnerId: String,
        planId: String,
        afterScheduledOrder: Int,
    ): StudentReviewQueueItemEntity?

    @Query(
        """
        SELECT item.queue_item_id, item.plan_id, item.learner_id,
               item.practice_unit_id, item.basis_revision_id,
               item.scheduled_order, item.estimated_duration_seconds,
               item.reason_codes_wire, item.source_evidence_event_kind,
               item.source_evidence_event_id, item.source_evidence_sequence,
               item.source_evidence_canonical_fingerprint, item.state,
               item.created_at_epoch_millis, item.state_changed_at_epoch_millis
        FROM student_review_queue_item AS item
        WHERE item.plan_id = :planId
          AND item.learner_id = :learnerId
          AND item.state = 'READY'
          AND item.scheduled_order > :afterScheduledOrder
        ORDER BY item.scheduled_order ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun readFutureReadyReviewQueueItems(
        learnerId: String,
        planId: String,
        afterScheduledOrder: Int,
        limit: Int,
    ): List<StudentReviewQueueItemEntity>

    @Query(
        """
        SELECT session_id, session_canonical_fingerprint, learner_id, plan_id,
               active_learner_id, state, session_version, current_queue_item_id,
               current_presentation_id, started_at_epoch_millis,
               updated_at_epoch_millis, completed_at_epoch_millis
        FROM student_review_session
        WHERE learner_id = :learnerId
          AND plan_id = :planId
        ORDER BY updated_at_epoch_millis DESC, session_version DESC, session_id ASC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readLatestReviewSessionForPlan(
        learnerId: String,
        planId: String,
    ): StudentReviewSessionEntity?

    @Query(
        """
        SELECT transition_id, transition_canonical_fingerprint, learner_id, plan_id,
               session_id, queue_item_id, action_kind, expected_session_version,
               resulting_session_version, presentation_id, observation_id,
               submission_id, response_form, response_canonical_fingerprint,
               verification_outcome, attempt_ordinal, hint_count,
               answer_was_revealed, verification_policy_version,
               elapsed_duration_millis, next_available_at_epoch_millis,
               next_due_at_epoch_millis, scheduling_policy_version,
               outbox_event_id, occurred_at_epoch_millis
        FROM student_review_transition_receipt
        WHERE transition_id = :transitionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewTransitionReceipt(
        transitionId: String,
    ): StudentReviewTransitionReceiptEntity?

    @Query(
        """
        SELECT transition_id, transition_canonical_fingerprint, learner_id, plan_id,
               session_id, queue_item_id, action_kind, expected_session_version,
               resulting_session_version, presentation_id, observation_id,
               submission_id, response_form, response_canonical_fingerprint,
               verification_outcome, attempt_ordinal, hint_count,
               answer_was_revealed, verification_policy_version,
               elapsed_duration_millis, next_available_at_epoch_millis,
               next_due_at_epoch_millis, scheduling_policy_version,
               outbox_event_id, occurred_at_epoch_millis
        FROM student_review_transition_receipt
        WHERE queue_item_id = :queueItemId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewTransitionReceiptForQueueItem(
        queueItemId: String,
    ): StudentReviewTransitionReceiptEntity?

    @Query(
        """
        SELECT reveal_id, reveal_canonical_fingerprint, learner_id, plan_id,
               session_id, queue_item_id, presentation_id,
               revealed_at_epoch_millis
        FROM student_review_reveal_receipt
        WHERE reveal_id = :revealId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewRevealReceipt(
        revealId: String,
    ): StudentReviewRevealReceiptEntity?

    @Query(
        """
        SELECT reveal_id, reveal_canonical_fingerprint, learner_id, plan_id,
               session_id, queue_item_id, presentation_id,
               revealed_at_epoch_millis
        FROM student_review_reveal_receipt
        WHERE session_id = :sessionId
          AND queue_item_id = :queueItemId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewRevealReceiptForQueueItem(
        sessionId: String,
        queueItemId: String,
    ): StudentReviewRevealReceiptEntity?

    @Query(
        """
        SELECT self_report_id, report_canonical_fingerprint, learner_id,
               queue_item_id, report_kind, reported_at_epoch_millis,
               next_available_at_epoch_millis, next_due_at_epoch_millis,
               scheduling_policy_version
        FROM student_review_self_report_receipt
        WHERE self_report_id = :selfReportId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewSelfReportReceipt(
        selfReportId: String,
    ): StudentReviewSelfReportReceiptEntity?

    @Query(
        """
        SELECT self_report_id, report_canonical_fingerprint, learner_id,
               queue_item_id, report_kind, reported_at_epoch_millis,
               next_available_at_epoch_millis, next_due_at_epoch_millis,
               scheduling_policy_version
        FROM student_review_self_report_receipt
        WHERE queue_item_id = :queueItemId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readReviewSelfReportReceiptForQueueItem(
        queueItemId: String,
    ): StudentReviewSelfReportReceiptEntity?

    @Transaction
    open suspend fun readReviewSessionSnapshot(
        learnerId: String,
        sessionId: String,
    ): StudentReviewSessionSnapshotBundle? {
        val session = readReviewSession(sessionId) ?: return null
        if (session.learnerId != learnerId) return null
        val currentItem =
            session.currentQueueItemId?.let { queueItemId ->
                readReviewQueueRow(
                    learnerId = learnerId,
                    queueItemId = queueItemId,
                )
            }
        return StudentReviewSessionSnapshotBundle(
            session = session,
            currentQueueItem = currentItem,
        )
    }

    @Transaction
    open suspend fun readActiveReviewSessionSnapshot(
        learnerId: String,
    ): StudentReviewSessionSnapshotBundle? {
        val session = readActiveReviewSession(learnerId) ?: return null
        val currentItemId = session.currentQueueItemId ?: return null
        return StudentReviewSessionSnapshotBundle(
            session = session,
            currentQueueItem =
                readReviewQueueRow(
                    learnerId = learnerId,
                    queueItemId = currentItemId,
                ),
        )
    }

    @Transaction
    open suspend fun readReviewHomeSnapshot(
        learnerId: String,
        localDayEpochDay: Long,
    ): StudentReviewHomeSnapshotBundle {
        val changeVersion = readChangeVersion(learnerId)
        val plan = readReviewPlan(learnerId, localDayEpochDay)
        val planItems =
            plan?.let { persistedPlan ->
                readReviewPlanSnapshotRows(
                    planId = persistedPlan.planId,
                    limit = MAX_STORED_REVIEW_ITEMS + 1,
                )
            }.orEmpty()
        check(planItems.size <= MAX_STORED_REVIEW_ITEMS) {
            "Corrupt student mistake store: review-home plan item budget exceeded"
        }
        val active =
            readActiveReviewSession(learnerId)?.let { session ->
                val currentItemId =
                    checkNotNull(session.currentQueueItemId) {
                        "Active review session is missing its current queue item"
                    }
                StudentReviewSessionSnapshotBundle(
                    session = session,
                    currentQueueItem =
                        readReviewQueueRow(
                            learnerId = learnerId,
                            queueItemId = currentItemId,
                        ),
                )
            }
        return StudentReviewHomeSnapshotBundle(
            changeVersion = changeVersion,
            plan = plan,
            planItems = planItems,
            activeSession = active,
        )
    }

    @Transaction
    open suspend fun readExistingReviewTransitionContext(
        learnerId: String,
        transitionId: String,
    ): ExistingStudentReviewTransitionContext? {
        val receipt = readReviewTransitionReceipt(transitionId) ?: return null
        if (receipt.learnerId != learnerId) return null
        val session = checkNotNull(readReviewSession(receipt.sessionId)) {
            "Persisted review transition is missing its session"
        }
        val queueItem =
            readReviewQueueRow(
                learnerId = learnerId,
                queueItemId = receipt.queueItemId,
            ) ?: return null
        check(
            session.learnerId == learnerId &&
                session.planId == receipt.planId &&
            queueItem.planId == receipt.planId &&
                queueItem.learnerId == learnerId,
        ) {
            "Persisted review transition ownership is inconsistent"
        }
        return ExistingStudentReviewTransitionContext(
            planId = receipt.planId,
            queueItem = queueItem,
        )
    }
}
