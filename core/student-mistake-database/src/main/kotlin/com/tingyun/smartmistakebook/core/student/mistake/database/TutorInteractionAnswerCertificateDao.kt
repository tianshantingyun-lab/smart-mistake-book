package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.ColumnInfo
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

@Dao
internal abstract class TutorInteractionAnswerCertificateDao :
    TutorInteractionAnswerCertificateAdmissionPort,
    TutorInteractionAnswerCertificatePersistencePort {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertCertificate(
        entity: TutorInteractionAnswerCertificateEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertStatus(
        entity: TutorInteractionAnswerCertificateStatusEventEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertLease(
        entity: TutorInteractionAnswerCertificateLeaseReceiptEntity,
    )

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertEvaluation(
        entity: TutorInteractionAnswerEvaluationReceiptEntity,
    )

    @Query(
        """
        SELECT *
        FROM student_tutor_interaction_answer_certificate
        WHERE admission_idempotency_key = :idempotencyKey
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCertificateByIdempotencyKey(
        idempotencyKey: String,
    ): TutorInteractionAnswerCertificateEntity?

    @Query(
        """
        SELECT *
        FROM student_tutor_interaction_answer_certificate
        WHERE certificate_id = :certificateId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCertificateById(
        certificateId: String,
    ): TutorInteractionAnswerCertificateEntity?

    @Query(
        """
        SELECT *
        FROM student_tutor_interaction_answer_certificate
        WHERE provenance_receipt_canonical_fingerprint = :fingerprint
        LIMIT 1
        """,
    )
    protected abstract suspend fun readCertificateByProvenanceReceipt(
        fingerprint: String,
    ): TutorInteractionAnswerCertificateEntity?

    @Query(
        """
        SELECT
            document.problem_id AS problem_id,
            document.learner_id AS learner_id,
            document.subject AS subject,
            document.primary_practice_unit_id AS practice_unit_id,
            document.current_revision_id AS current_revision_id,
            document.lifecycle_state AS lifecycle_state,
            revision.revision_number AS revision_number,
            revision.document_canonical_fingerprint AS document_canonical_fingerprint
        FROM student_problem_revision AS revision
        INNER JOIN student_problem_document AS document
          ON document.problem_id = revision.problem_id
        WHERE revision.revision_id = :problemRevisionId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readAdmissionTarget(
        problemRevisionId: String,
    ): TutorInteractionAnswerCertificateTargetRow?

    @Query(
        """
        SELECT
            certificate.certificate_id AS certificate_id,
            status.status_generation AS status_generation,
            status.status AS status,
            status.occurred_at_epoch_millis AS occurred_at_epoch_millis,
            certificate.not_after_epoch_millis AS not_after_epoch_millis
        FROM student_tutor_interaction_answer_certificate AS certificate
        INNER JOIN student_tutor_interaction_answer_certificate_status_event AS status
          ON status.certificate_id = certificate.certificate_id
         AND status.status_generation = (
            SELECT MAX(newer.status_generation)
            FROM student_tutor_interaction_answer_certificate_status_event AS newer
            WHERE newer.certificate_id = certificate.certificate_id
         )
        WHERE certificate.certificate_family_canonical_fingerprint = :familyFingerprint
        ORDER BY certificate.issued_at_epoch_millis, certificate.certificate_id
        """,
    )
    protected abstract suspend fun readFamilyStatusHeads(
        familyFingerprint: String,
    ): List<TutorInteractionAnswerCertificateStatusHeadRow>

    @Query(
        """
        SELECT *
        FROM student_tutor_interaction_answer_certificate_status_event
        WHERE certificate_id = :certificateId
          AND status_generation = :statusGeneration
        LIMIT 1
        """,
    )
    protected abstract suspend fun readStatusAtGeneration(
        certificateId: String,
        statusGeneration: Long,
    ): TutorInteractionAnswerCertificateStatusEventEntity?

    @Query(
        """
        SELECT
            status.certificate_id AS certificate_id,
            status.status_generation AS status_generation,
            status.status AS status,
            status.occurred_at_epoch_millis AS occurred_at_epoch_millis,
            certificate.not_after_epoch_millis AS not_after_epoch_millis
        FROM student_tutor_interaction_answer_certificate_status_event AS status
        INNER JOIN student_tutor_interaction_answer_certificate AS certificate
          ON certificate.certificate_id = status.certificate_id
        WHERE status.certificate_id = :certificateId
        ORDER BY status.status_generation DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun readStatusHead(
        certificateId: String,
    ): TutorInteractionAnswerCertificateStatusHeadRow?

    @Transaction
    override suspend fun admit(
        command: AdmitTutorInteractionAnswerCertificateCommand,
    ): AdmitTutorInteractionAnswerCertificateResult {
        val entity = command.toEntity()
        readCertificateByIdempotencyKey(command.idempotencyKey)?.let { existing ->
            return if (existing.ownerCanonicalFingerprint == command.ownerCanonicalFingerprint) {
                AdmitTutorInteractionAnswerCertificateResult.Duplicate(existing.toAdmissionReceipt())
            } else {
                AdmitTutorInteractionAnswerCertificateResult.Conflict
            }
        }
        readCertificateById(command.certificateId)?.let {
            return AdmitTutorInteractionAnswerCertificateResult.Conflict
        }
        readCertificateByProvenanceReceipt(
            command.provenance.receiptCanonicalFingerprint,
        )?.let {
            return AdmitTutorInteractionAnswerCertificateResult.Conflict
        }
        val target = readAdmissionTarget(command.presentation.problemRevision.revisionId)
            ?: return AdmitTutorInteractionAnswerCertificateResult.Unavailable
        if (!target.matches(command.presentation)) {
            return AdmitTutorInteractionAnswerCertificateResult.Unavailable
        }
        readFamilyStatusHeads(command.presentation.familyCanonicalFingerprint)
            .filter { it.status == TutorInteractionAnswerCertificateStatus.ADMITTED.name }
            .forEach { previous ->
                val terminal =
                    if (previous.notAfterEpochMillis < command.issuedAtEpochMillis) {
                        TutorInteractionAnswerCertificateStatus.EXPIRED
                    } else {
                        TutorInteractionAnswerCertificateStatus.SUPERSEDED
                    }
                insertStatus(
                    previous.toAutomaticRetirement(
                        terminal,
                        command.issuedAtEpochMillis,
                        command.certificateId,
                    ),
                )
            }
        insertCertificate(entity)
        insertStatus(command.toInitialStatusEntity())
        return AdmitTutorInteractionAnswerCertificateResult.Admitted(entity.toAdmissionReceipt())
    }

    @Transaction
    override suspend fun appendStatus(
        command: AppendTutorInteractionAnswerCertificateStatusCommand,
    ): AppendTutorInteractionAnswerCertificateStatusResult {
        readStatusAtGeneration(command.certificateId, command.statusGeneration)?.let { existing ->
            return if (existing == command.toEntity()) {
                AppendTutorInteractionAnswerCertificateStatusResult.Duplicate
            } else {
                AppendTutorInteractionAnswerCertificateStatusResult.Conflict
            }
        }
        val certificate = readCertificateById(command.certificateId)
            ?: return AppendTutorInteractionAnswerCertificateStatusResult.Unavailable
        val head = readStatusHead(command.certificateId)
            ?: return AppendTutorInteractionAnswerCertificateStatusResult.Unavailable
        if (
            head.statusGeneration != command.expectedStatusGeneration ||
            head.status != TutorInteractionAnswerCertificateStatus.ADMITTED.name ||
            command.occurredAtEpochMillis < certificate.issuedAtEpochMillis ||
            command.occurredAtEpochMillis < head.occurredAtEpochMillis
        ) {
            return AppendTutorInteractionAnswerCertificateStatusResult.Conflict
        }
        insertStatus(command.toEntity())
        return AppendTutorInteractionAnswerCertificateStatusResult.Appended
    }

    @Query(
        """
        SELECT
            certificate.certificate_id AS certificate_id,
            certificate.learner_id AS learner_id,
            certificate.subject AS subject,
            certificate.problem_id AS problem_id,
            certificate.practice_unit_id AS practice_unit_id,
            certificate.problem_revision_id AS problem_revision_id,
            certificate.problem_revision_number AS problem_revision_number,
            certificate.question_document_fingerprint AS question_document_fingerprint,
            certificate.session_id AS session_id,
            certificate.model_task_request_id AS model_task_request_id,
            certificate.cycle_ordinal AS cycle_ordinal,
            certificate.turn_ordinal AS turn_ordinal,
            certificate.mode_version AS mode_version,
            certificate.learning_write_permission_version AS learning_write_permission_version,
            certificate.interaction_kind AS interaction_kind,
            certificate.presentation_canonical_fingerprint AS presentation_canonical_fingerprint,
            certificate.allowed_answer_ids_wire AS allowed_answer_ids_wire,
            certificate.correct_answer_ids_wire AS correct_answer_ids_wire,
            certificate.certificate_schema_version AS certificate_schema_version,
            certificate.admission_policy_version AS admission_policy_version,
            certificate.admission_receipt_canonical_fingerprint AS admission_receipt_canonical_fingerprint,
            certificate.issued_at_epoch_millis AS issued_at_epoch_millis,
            certificate.not_after_epoch_millis AS not_after_epoch_millis,
            status.status_generation AS status_generation
        FROM student_tutor_interaction_answer_certificate AS certificate
        INNER JOIN student_tutor_interaction_answer_certificate_status_event AS status
          ON status.certificate_id = certificate.certificate_id
         AND status.status_generation = (
            SELECT MAX(newer.status_generation)
            FROM student_tutor_interaction_answer_certificate_status_event AS newer
            WHERE newer.certificate_id = certificate.certificate_id
         )
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = certificate.problem_revision_id
         AND revision.problem_id = certificate.problem_id
         AND revision.revision_number = certificate.problem_revision_number
         AND revision.document_canonical_fingerprint = certificate.question_document_fingerprint
        INNER JOIN student_problem_document AS document
          ON document.problem_id = certificate.problem_id
         AND document.learner_id = certificate.learner_id
         AND document.subject = certificate.subject
         AND document.primary_practice_unit_id = certificate.practice_unit_id
         AND document.current_revision_id = certificate.problem_revision_id
        WHERE certificate.learner_id = :learnerId
          AND certificate.problem_id = :problemId
          AND certificate.problem_revision_id = :problemRevisionId
          AND certificate.question_document_fingerprint = :questionDocumentFingerprint
          AND certificate.session_id = :sessionId
          AND certificate.model_task_request_id = :modelTaskRequestId
          AND certificate.cycle_ordinal = :cycleOrdinal
          AND certificate.turn_ordinal = :turnOrdinal
          AND certificate.mode_version = :modeVersion
          AND certificate.learning_write_permission_version = :learningWritePermissionVersion
          AND certificate.interaction_kind = :interactionKind
          AND certificate.presentation_canonical_fingerprint = :presentationFingerprint
          AND certificate.allowed_answer_ids_wire = :allowedAnswerIdsWire
          AND certificate.certificate_schema_version = :certificateSchemaVersion
          AND certificate.admission_policy_version = :admissionPolicyVersion
          AND certificate.issued_at_epoch_millis <= :nowEpochMillis
          AND certificate.not_after_epoch_millis >= :nowEpochMillis
          AND status.status = 'ADMITTED'
          AND document.lifecycle_state = 'ACTIVE'
        ORDER BY certificate.issued_at_epoch_millis DESC, certificate.certificate_id DESC
        LIMIT 2
        """,
    )
    protected abstract suspend fun readActiveExactRows(
        learnerId: String,
        problemId: String,
        problemRevisionId: String,
        questionDocumentFingerprint: String,
        sessionId: String,
        modelTaskRequestId: String,
        cycleOrdinal: Int,
        turnOrdinal: Int,
        modeVersion: Long,
        learningWritePermissionVersion: Long,
        interactionKind: String,
        presentationFingerprint: String,
        allowedAnswerIdsWire: String,
        certificateSchemaVersion: String,
        admissionPolicyVersion: String,
        nowEpochMillis: Long,
    ): List<TutorInteractionAnswerCertificatePrivateRow>

    @Transaction
    override suspend fun readActiveAdmission(
        learnerId: String,
        presentation: TutorInteractionAnswerPresentation,
        nowEpochMillis: Long,
    ): TutorInteractionAnswerAdmissionReceipt? =
        readActiveExact(learnerId, presentation, nowEpochMillis)?.toAdmissionReceipt()

    @Transaction
    override suspend fun issueLease(
        learnerId: String,
        presentation: TutorInteractionAnswerPresentation,
        receiptId: String,
        issuedAtEpochMillis: Long,
        requestedNotAfterEpochMillis: Long,
    ): PersistedTutorInteractionAnswerLease? {
        if (
            issuedAtEpochMillis < 0L ||
            requestedNotAfterEpochMillis <= issuedAtEpochMillis ||
            requestedNotAfterEpochMillis - issuedAtEpochMillis >
            TUTOR_CERTIFICATE_MAX_VALIDITY_MILLIS
        ) {
            return null
        }
        val certificate = readActiveExact(learnerId, presentation, issuedAtEpochMillis)
            ?: return null
        val notAfter = minOf(requestedNotAfterEpochMillis, certificate.notAfterEpochMillis)
        if (notAfter <= issuedAtEpochMillis) return null
        val fingerprint = CanonicalSha256("student-tutor-answer-certificate-lease-v1")
            .field("receiptId", receiptId)
            .field("certificateId", certificate.certificateId)
            .field("presentation", presentation.answerFreeCanonicalFingerprint)
            .field("certificateStatusGeneration", certificate.statusGeneration)
            .field("issuedAtEpochMillis", issuedAtEpochMillis)
            .field("notAfterEpochMillis", notAfter)
            .finish()
        insertLease(
            TutorInteractionAnswerCertificateLeaseReceiptEntity(
                leaseReceiptId = receiptId,
                certificateId = certificate.certificateId,
                learnerId = learnerId,
                certificateStatusGeneration = certificate.statusGeneration,
                presentationAnswerFreeCanonicalFingerprint =
                    presentation.answerFreeCanonicalFingerprint,
                issuedAtEpochMillis = issuedAtEpochMillis,
                notAfterEpochMillis = notAfter,
                leaseCanonicalFingerprint = fingerprint,
            ),
        )
        return PersistedTutorInteractionAnswerLease(
            receiptId = receiptId,
            certificateId = certificate.certificateId,
            presentation = presentation,
            certificateStatusGeneration = certificate.statusGeneration,
            issuedAtEpochMillis = issuedAtEpochMillis,
            notAfterEpochMillis = notAfter,
            canonicalFingerprint = fingerprint,
        )
    }

    @Query(
        """
        SELECT
            certificate.certificate_id AS certificate_id,
            certificate.learner_id AS learner_id,
            certificate.subject AS subject,
            certificate.problem_id AS problem_id,
            certificate.practice_unit_id AS practice_unit_id,
            certificate.problem_revision_id AS problem_revision_id,
            certificate.problem_revision_number AS problem_revision_number,
            certificate.question_document_fingerprint AS question_document_fingerprint,
            certificate.session_id AS session_id,
            certificate.model_task_request_id AS model_task_request_id,
            certificate.cycle_ordinal AS cycle_ordinal,
            certificate.turn_ordinal AS turn_ordinal,
            certificate.mode_version AS mode_version,
            certificate.learning_write_permission_version AS learning_write_permission_version,
            certificate.interaction_kind AS interaction_kind,
            certificate.presentation_canonical_fingerprint AS presentation_canonical_fingerprint,
            certificate.allowed_answer_ids_wire AS allowed_answer_ids_wire,
            certificate.correct_answer_ids_wire AS correct_answer_ids_wire,
            certificate.certificate_schema_version AS certificate_schema_version,
            certificate.admission_policy_version AS admission_policy_version,
            certificate.admission_receipt_canonical_fingerprint AS admission_receipt_canonical_fingerprint,
            certificate.issued_at_epoch_millis AS issued_at_epoch_millis,
            certificate.not_after_epoch_millis AS not_after_epoch_millis,
            status.status_generation AS status_generation
        FROM student_tutor_interaction_answer_certificate_lease_receipt AS lease
        INNER JOIN student_tutor_interaction_answer_certificate AS certificate
          ON certificate.certificate_id = lease.certificate_id
         AND certificate.learner_id = lease.learner_id
        INNER JOIN student_tutor_interaction_answer_certificate_status_event AS status
          ON status.certificate_id = certificate.certificate_id
         AND status.status_generation = (
            SELECT MAX(newer.status_generation)
            FROM student_tutor_interaction_answer_certificate_status_event AS newer
            WHERE newer.certificate_id = certificate.certificate_id
         )
        INNER JOIN student_problem_revision AS revision
          ON revision.revision_id = certificate.problem_revision_id
         AND revision.problem_id = certificate.problem_id
         AND revision.revision_number = certificate.problem_revision_number
         AND revision.document_canonical_fingerprint = certificate.question_document_fingerprint
        INNER JOIN student_problem_document AS document
          ON document.problem_id = certificate.problem_id
         AND document.learner_id = certificate.learner_id
         AND document.current_revision_id = certificate.problem_revision_id
        WHERE lease.lease_receipt_id = :leaseReceiptId
          AND lease.lease_canonical_fingerprint = :leaseCanonicalFingerprint
          AND lease.learner_id = :learnerId
          AND lease.certificate_status_generation = status.status_generation
          AND lease.issued_at_epoch_millis <= :evaluatedAtEpochMillis
          AND lease.not_after_epoch_millis >= :evaluatedAtEpochMillis
          AND lease.presentation_answer_free_canonical_fingerprint =
              certificate.presentation_answer_free_canonical_fingerprint
          AND certificate.issued_at_epoch_millis <= :evaluatedAtEpochMillis
          AND certificate.not_after_epoch_millis >= :evaluatedAtEpochMillis
          AND status.status = 'ADMITTED'
          AND document.lifecycle_state = 'ACTIVE'
        LIMIT 1
        """,
    )
    protected abstract suspend fun readActiveLeaseCertificate(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        evaluatedAtEpochMillis: Long,
    ): TutorInteractionAnswerCertificatePrivateRow?

    @Query(
        """
        SELECT *
        FROM student_tutor_interaction_answer_evaluation_receipt
        WHERE lease_receipt_id = :leaseReceiptId
        LIMIT 1
        """,
    )
    protected abstract suspend fun readEvaluationByLease(
        leaseReceiptId: String,
    ): TutorInteractionAnswerEvaluationReceiptEntity?

    @Transaction
    override suspend fun evaluate(
        learnerId: String,
        leaseReceiptId: String,
        leaseCanonicalFingerprint: String,
        response: TutorInteractionAnswerResponse,
        evaluatedAtEpochMillis: Long,
    ): TutorInteractionAnswerEvaluationResult {
        if (evaluatedAtEpochMillis < 0L) {
            return TutorInteractionAnswerEvaluationResult.ReloadRequired
        }
        val certificate = readActiveLeaseCertificate(
            learnerId,
            leaseReceiptId,
            leaseCanonicalFingerprint,
            evaluatedAtEpochMillis,
        ) ?: return TutorInteractionAnswerEvaluationResult.ReloadRequired
        val presentation = certificate.toPresentationOrNull()
            ?: return TutorInteractionAnswerEvaluationResult.ReloadRequired
        val responseFingerprint = response.canonicalFingerprint(presentation.interactionKind)
            ?: return TutorInteractionAnswerEvaluationResult.Conflict
        if (response.answerId !in presentation.allowedAnswerIds) {
            return TutorInteractionAnswerEvaluationResult.Conflict
        }
        readEvaluationByLease(leaseReceiptId)?.let { existing ->
            return if (existing.responseCanonicalFingerprint == responseFingerprint) {
                TutorInteractionAnswerEvaluationResult.Recorded(existing.toReceipt(duplicate = true))
            } else {
                TutorInteractionAnswerEvaluationResult.Conflict
            }
        }
        val correctIds = certificate.correctAnswerIdsWire.decodeCanonicalCertificateSetOrNull()
            ?: return TutorInteractionAnswerEvaluationResult.ReloadRequired
        if (correctIds.isEmpty() || !presentation.allowedAnswerIds.containsAll(correctIds)) {
            return TutorInteractionAnswerEvaluationResult.ReloadRequired
        }
        val wasCorrect = response.answerId in correctIds
        val evaluationId = "$leaseReceiptId:evaluation"
        val fingerprint = CanonicalSha256("student-tutor-answer-certificate-evaluation-v1")
            .field("evaluationReceiptId", evaluationId)
            .field("leaseReceiptId", leaseReceiptId)
            .field("certificateId", certificate.certificateId)
            .field("learnerId", learnerId)
            .field("responseCanonicalFingerprint", responseFingerprint)
            .field("responseWasCorrect", wasCorrect)
            .field("evaluatedAtEpochMillis", evaluatedAtEpochMillis)
            .finish()
        val entity = TutorInteractionAnswerEvaluationReceiptEntity(
            evaluationReceiptId = evaluationId,
            leaseReceiptId = leaseReceiptId,
            certificateId = certificate.certificateId,
            learnerId = learnerId,
            responseCanonicalFingerprint = responseFingerprint,
            responseWasCorrect = wasCorrect,
            evaluatedAtEpochMillis = evaluatedAtEpochMillis,
            evaluationCanonicalFingerprint = fingerprint,
        )
        insertEvaluation(entity)
        return TutorInteractionAnswerEvaluationResult.Recorded(entity.toReceipt(duplicate = false))
    }

    private suspend fun readActiveExact(
        learnerId: String,
        presentation: TutorInteractionAnswerPresentation,
        nowEpochMillis: Long,
    ): TutorInteractionAnswerCertificatePrivateRow? {
        if (nowEpochMillis < 0L || presentation.problemRevision.problem.learnerId != learnerId) {
            return null
        }
        val rows = readActiveExactRows(
            learnerId = learnerId,
            problemId = presentation.problemRevision.problem.problemId,
            problemRevisionId = presentation.problemRevision.revisionId,
            questionDocumentFingerprint = presentation.questionDocumentFingerprint,
            sessionId = presentation.sessionId,
            modelTaskRequestId = presentation.modelTaskRequestId,
            cycleOrdinal = presentation.cycleOrdinal,
            turnOrdinal = presentation.turnOrdinal,
            modeVersion = presentation.modeVersion,
            learningWritePermissionVersion = presentation.learningWritePermissionVersion,
            interactionKind = presentation.interactionKind.name,
            presentationFingerprint = presentation.presentationCanonicalFingerprint,
            allowedAnswerIdsWire = presentation.allowedAnswerIdsWire,
            certificateSchemaVersion = presentation.certificateSchemaVersion,
            admissionPolicyVersion = presentation.admissionPolicyVersion,
            nowEpochMillis = nowEpochMillis,
        )
        return rows.singleOrNull()?.takeIf { it.toPresentationOrNull() == presentation }
    }
}

internal data class TutorInteractionAnswerCertificateTargetRow(
    @ColumnInfo(name = "problem_id")
    val problemId: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "subject")
    val subject: String,
    @ColumnInfo(name = "practice_unit_id")
    val practiceUnitId: String,
    @ColumnInfo(name = "current_revision_id")
    val currentRevisionId: String,
    @ColumnInfo(name = "lifecycle_state")
    val lifecycleState: String,
    @ColumnInfo(name = "revision_number")
    val revisionNumber: Int,
    @ColumnInfo(name = "document_canonical_fingerprint")
    val documentCanonicalFingerprint: String,
)

private fun TutorInteractionAnswerCertificateTargetRow.matches(
    presentation: TutorInteractionAnswerPresentation,
): Boolean =
    problemId == presentation.problemRevision.problem.problemId &&
        learnerId == presentation.problemRevision.problem.learnerId &&
        subject == presentation.problemRevision.problem.subject.name &&
        practiceUnitId == presentation.problemRevision.problem.practiceUnitId &&
        currentRevisionId == presentation.problemRevision.revisionId &&
        lifecycleState == StudentProblemLifecycleState.ACTIVE.name &&
        revisionNumber == presentation.problemRevision.revisionNumber &&
        documentCanonicalFingerprint == presentation.questionDocumentFingerprint

private fun AdmitTutorInteractionAnswerCertificateCommand.toEntity() =
    TutorInteractionAnswerCertificateEntity(
        certificateId = certificateId,
        admissionIdempotencyKey = idempotencyKey,
        learnerId = presentation.problemRevision.problem.learnerId,
        subject = presentation.problemRevision.problem.subject.name,
        problemId = presentation.problemRevision.problem.problemId,
        practiceUnitId = presentation.problemRevision.problem.practiceUnitId,
        problemRevisionId = presentation.problemRevision.revisionId,
        problemRevisionNumber = presentation.problemRevision.revisionNumber,
        questionDocumentFingerprint = presentation.questionDocumentFingerprint,
        sessionId = presentation.sessionId,
        modelTaskRequestId = presentation.modelTaskRequestId,
        cycleOrdinal = presentation.cycleOrdinal,
        turnOrdinal = presentation.turnOrdinal,
        modeVersion = presentation.modeVersion,
        learningWritePermissionVersion = presentation.learningWritePermissionVersion,
        interactionKind = presentation.interactionKind.name,
        presentationCanonicalFingerprint = presentation.presentationCanonicalFingerprint,
        allowedAnswerIdsWire = presentation.allowedAnswerIdsWire,
        correctAnswerIdsWire = encodeCanonicalSet(correctRule.correctAnswerIds),
        provenanceKind = provenance.kind.name,
        provenanceReceiptId = provenance.receiptId,
        provenanceReceiptCanonicalFingerprint = provenance.receiptCanonicalFingerprint,
        provenanceReceiptSchemaVersion = provenance.receiptSchemaVersion,
        certificateSchemaVersion = presentation.certificateSchemaVersion,
        admissionPolicyVersion = presentation.admissionPolicyVersion,
        certificateFamilyCanonicalFingerprint = presentation.familyCanonicalFingerprint,
        presentationAnswerFreeCanonicalFingerprint =
            presentation.answerFreeCanonicalFingerprint,
        ownerCanonicalFingerprint = ownerCanonicalFingerprint,
        admissionReceiptCanonicalFingerprint = admissionReceiptCanonicalFingerprint,
        issuedAtEpochMillis = issuedAtEpochMillis,
        notAfterEpochMillis = notAfterEpochMillis,
    )

private fun AdmitTutorInteractionAnswerCertificateCommand.toInitialStatusEntity():
    TutorInteractionAnswerCertificateStatusEventEntity {
    val eventId = "$certificateId:status:1"
    val fingerprint = CanonicalSha256("student-tutor-answer-certificate-status-v1")
        .field("statusEventId", eventId)
        .field("certificateId", certificateId)
        .field("statusGeneration", 1L)
        .field("status", TutorInteractionAnswerCertificateStatus.ADMITTED.name)
        .field("reasonCanonicalFingerprint", ownerCanonicalFingerprint)
        .field("statusPolicyVersion", presentation.admissionPolicyVersion)
        .field("occurredAtEpochMillis", issuedAtEpochMillis)
        .finish()
    return TutorInteractionAnswerCertificateStatusEventEntity(
        statusEventId = eventId,
        certificateId = certificateId,
        statusGeneration = 1L,
        status = TutorInteractionAnswerCertificateStatus.ADMITTED.name,
        reasonCanonicalFingerprint = ownerCanonicalFingerprint,
        statusPolicyVersion = presentation.admissionPolicyVersion,
        occurredAtEpochMillis = issuedAtEpochMillis,
        statusCanonicalFingerprint = fingerprint,
    )
}

private fun TutorInteractionAnswerCertificateStatusHeadRow.toAutomaticRetirement(
    terminal: TutorInteractionAnswerCertificateStatus,
    occurredAtEpochMillis: Long,
    replacementCertificateId: String,
): TutorInteractionAnswerCertificateStatusEventEntity {
    val generation = Math.addExact(statusGeneration, 1L)
    val eventId = "$certificateId:status:$generation"
    val reason = CanonicalSha256("student-tutor-answer-certificate-auto-retirement-reason-v1")
        .field("terminal", terminal.name)
        .field("replacementCertificateId", replacementCertificateId)
        .finish()
    val policy = "student-tutor-answer-certificate-auto-retirement-v1"
    val fingerprint = CanonicalSha256("student-tutor-answer-certificate-status-v1")
        .field("statusEventId", eventId)
        .field("certificateId", certificateId)
        .field("statusGeneration", generation)
        .field("status", terminal.name)
        .field("reasonCanonicalFingerprint", reason)
        .field("statusPolicyVersion", policy)
        .field("occurredAtEpochMillis", occurredAtEpochMillis)
        .finish()
    return TutorInteractionAnswerCertificateStatusEventEntity(
        statusEventId = eventId,
        certificateId = certificateId,
        statusGeneration = generation,
        status = terminal.name,
        reasonCanonicalFingerprint = reason,
        statusPolicyVersion = policy,
        occurredAtEpochMillis = occurredAtEpochMillis,
        statusCanonicalFingerprint = fingerprint,
    )
}

private fun AppendTutorInteractionAnswerCertificateStatusCommand.toEntity() =
    TutorInteractionAnswerCertificateStatusEventEntity(
        statusEventId = statusEventId,
        certificateId = certificateId,
        statusGeneration = statusGeneration,
        status = status.name,
        reasonCanonicalFingerprint = reasonCanonicalFingerprint,
        statusPolicyVersion = statusPolicyVersion,
        occurredAtEpochMillis = occurredAtEpochMillis,
        statusCanonicalFingerprint = canonicalFingerprint,
    )

private fun TutorInteractionAnswerCertificateEntity.toAdmissionReceipt() =
    TutorInteractionAnswerAdmissionReceipt(
        certificateId = certificateId,
        presentation = checkNotNull(toPresentationOrNull()),
        statusGeneration = 1L,
        issuedAtEpochMillis = issuedAtEpochMillis,
        notAfterEpochMillis = notAfterEpochMillis,
        admissionCanonicalFingerprint = admissionReceiptCanonicalFingerprint,
    )

private fun TutorInteractionAnswerCertificatePrivateRow.toAdmissionReceipt() =
    TutorInteractionAnswerAdmissionReceipt(
        certificateId = certificateId,
        presentation = checkNotNull(toPresentationOrNull()),
        statusGeneration = statusGeneration,
        issuedAtEpochMillis = issuedAtEpochMillis,
        notAfterEpochMillis = notAfterEpochMillis,
        admissionCanonicalFingerprint = admissionReceiptCanonicalFingerprint,
    )

private fun TutorInteractionAnswerCertificateEntity.toPresentationOrNull():
    TutorInteractionAnswerPresentation? =
    persistedPresentationOrNull(
        learnerId,
        subject,
        problemId,
        practiceUnitId,
        problemRevisionId,
        problemRevisionNumber,
        questionDocumentFingerprint,
        sessionId,
        modelTaskRequestId,
        cycleOrdinal,
        turnOrdinal,
        modeVersion,
        learningWritePermissionVersion,
        interactionKind,
        presentationCanonicalFingerprint,
        allowedAnswerIdsWire,
        certificateSchemaVersion,
        admissionPolicyVersion,
    )

private fun TutorInteractionAnswerCertificatePrivateRow.toPresentationOrNull():
    TutorInteractionAnswerPresentation? =
    persistedPresentationOrNull(
        learnerId,
        subject,
        problemId,
        practiceUnitId,
        problemRevisionId,
        problemRevisionNumber,
        questionDocumentFingerprint,
        sessionId,
        modelTaskRequestId,
        cycleOrdinal,
        turnOrdinal,
        modeVersion,
        learningWritePermissionVersion,
        interactionKind,
        presentationCanonicalFingerprint,
        allowedAnswerIdsWire,
        certificateSchemaVersion,
        admissionPolicyVersion,
    )

@Suppress("LongParameterList")
private fun persistedPresentationOrNull(
    learnerId: String,
    subject: String,
    problemId: String,
    practiceUnitId: String,
    revisionId: String,
    revisionNumber: Int,
    questionDocumentFingerprint: String,
    sessionId: String,
    modelTaskRequestId: String,
    cycleOrdinal: Int,
    turnOrdinal: Int,
    modeVersion: Long,
    learningWritePermissionVersion: Long,
    interactionKind: String,
    presentationCanonicalFingerprint: String,
    allowedAnswerIdsWire: String,
    certificateSchemaVersion: String,
    admissionPolicyVersion: String,
): TutorInteractionAnswerPresentation? =
    runCatching {
        TutorInteractionAnswerPresentation(
            problemRevision = StudentProblemRevisionRef(
                problem = StudentProblemRef(
                    learnerId = learnerId,
                    subject = SubjectKind.valueOf(subject),
                    problemId = problemId,
                    practiceUnitId = practiceUnitId,
                ),
                revisionId = revisionId,
                revisionNumber = revisionNumber,
                documentCanonicalFingerprint = questionDocumentFingerprint,
            ),
            questionDocumentFingerprint = questionDocumentFingerprint,
            sessionId = sessionId,
            modelTaskRequestId = modelTaskRequestId,
            cycleOrdinal = cycleOrdinal,
            turnOrdinal = turnOrdinal,
            modeVersion = modeVersion,
            learningWritePermissionVersion = learningWritePermissionVersion,
            interactionKind = TutorInteractionAnswerKind.valueOf(interactionKind),
            presentationCanonicalFingerprint = presentationCanonicalFingerprint,
            allowedAnswerIds = checkNotNull(allowedAnswerIdsWire.decodeCanonicalCertificateSetOrNull()),
            certificateSchemaVersion = certificateSchemaVersion,
            admissionPolicyVersion = admissionPolicyVersion,
        )
    }.getOrNull()

private fun TutorInteractionAnswerEvaluationReceiptEntity.toReceipt(
    duplicate: Boolean,
) = TutorInteractionAnswerEvaluationReceipt(
    responseWasCorrect = responseWasCorrect,
    duplicate = duplicate,
    evaluatedAtEpochMillis = evaluatedAtEpochMillis,
    canonicalFingerprint = evaluationCanonicalFingerprint,
)

private fun String.decodeCanonicalCertificateSetOrNull(): Set<String>? =
    runCatching {
        val values = decodeOrderedStrings(this)
        values.toSet().takeIf { it.size == values.size && encodeCanonicalSet(it) == this }
    }.getOrNull()
