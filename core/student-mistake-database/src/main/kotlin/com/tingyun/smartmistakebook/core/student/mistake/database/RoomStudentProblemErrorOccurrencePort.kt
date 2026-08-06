package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceKind
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.SubjectKind
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

internal class RoomStudentProblemErrorOccurrencePort(
    private val dao: StudentProblemErrorOccurrenceDao,
    override val learnerId: String,
) : StudentProblemErrorOccurrencePort {
    init {
        learnerId.requireStoreText("Error-occurrence owner learner id", MAX_ID_CHARS)
    }

    override suspend fun append(
        command: AppendStudentProblemErrorOccurrenceCommand,
    ): AppendStudentProblemErrorOccurrenceResult {
        require(command.problemRevision.problem.learnerId == learnerId) {
            "Error occurrence command crosses the learner-bound capability"
        }
        command.problemRevision.problem.subject.requireHighSchoolSubject()
        val result = dao.append(command.toErrorOccurrenceBundle())
        return AppendStudentProblemErrorOccurrenceResult(
            created = result.created,
            occurrence = result.bundle.toDomainErrorOccurrence(),
        )
    }

    override suspend fun readHistory(
        problemRevision: StudentProblemRevisionRef,
        limit: Int,
    ): List<StudentProblemErrorOccurrence> {
        require(problemRevision.problem.learnerId == learnerId) {
            "Error occurrence history read crosses the learner-bound capability"
        }
        problemRevision.problem.subject.requireHighSchoolSubject()
        require(limit in 1..MAX_STUDENT_PROBLEM_ERROR_OCCURRENCE_HISTORY_LIMIT) {
            "Error occurrence history limit is outside the supported range"
        }
        val target =
            dao.readExactRevision(problemRevision.revisionId)
                ?: throw StudentProblemErrorOccurrenceConflictException(
                    "Error occurrence history target revision does not exist",
                )
        target.requireExactHistoryTarget(problemRevision)
        return dao.readHistory(problemRevision.revisionId, limit).map { bundle ->
            val occurrence = bundle.toDomainErrorOccurrence()
            check(
                occurrence.ref.problemRevision == problemRevision &&
                    occurrence.ref.problemRevision.problem.learnerId == learnerId,
            ) {
                "Error occurrence history crossed the exact requested revision"
            }
            occurrence
        }
    }
}

internal fun AppendStudentProblemErrorOccurrenceCommand.toErrorOccurrenceBundle():
    StudentProblemErrorOccurrenceBundle {
    val normalizedEvidence = evidenceRefs.normalizedErrorOccurrenceEvidence()
    val occurrence =
        StudentProblemErrorOccurrenceEntity(
            occurrenceId = occurrenceId,
            idempotencyKey = idempotencyKey,
            schemaVersion = STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION,
            learnerId = problemRevision.problem.learnerId,
            subject = problemRevision.problem.subject.name,
            problemId = problemRevision.problem.problemId,
            practiceUnitId = problemRevision.problem.practiceUnitId,
            basisRevisionId = problemRevision.revisionId,
            basisRevisionNumber = problemRevision.revisionNumber,
            basisDocumentCanonicalFingerprint =
                problemRevision.documentCanonicalFingerprint,
            batchCanonicalFingerprint = batchCanonicalFingerprint,
            importSourceCanonicalFingerprint = importSourceCanonicalFingerprint,
            occurredAtEpochMillis = occurredAtEpochMillis,
            importedAtEpochMillis = importedAtEpochMillis,
            attributionStatus = attributionStatus?.name,
            evidenceCount = normalizedEvidence.size,
            occurrenceCanonicalFingerprint = occurrenceCanonicalFingerprint,
        )
    return StudentProblemErrorOccurrenceBundle(
        occurrence = occurrence,
        evidence =
            normalizedEvidence.mapIndexed { ordinal, evidence ->
                StudentProblemErrorOccurrenceEvidenceEntity(
                    occurrenceId = occurrenceId,
                    ordinal = ordinal,
                    blockId = evidence.blockId,
                    sourceAssetId = evidence.sourceAssetId,
                    evidenceKind = evidence.evidenceKind.name,
                )
            },
    )
}

internal fun StudentProblemErrorOccurrenceBundle.toDomainErrorOccurrence():
    StudentProblemErrorOccurrence {
    val entity = occurrence
    check(entity.evidenceCount == evidence.size) {
        "Persisted error occurrence evidence count is corrupt"
    }
    val subject =
        enumValueOrCorrupt<SubjectKind>(
            entity.subject,
            "error occurrence subject",
        )
    val problemRevision =
        StudentProblemRevisionRef(
            problem =
                StudentProblemRef(
                    learnerId = entity.learnerId,
                    subject = subject,
                    problemId = entity.problemId,
                    practiceUnitId = entity.practiceUnitId,
                ),
            revisionId = entity.basisRevisionId,
            revisionNumber = entity.basisRevisionNumber,
            documentCanonicalFingerprint =
                entity.basisDocumentCanonicalFingerprint,
        )
    val domainEvidence =
        evidence.mapIndexed { expectedOrdinal, stored ->
            check(
                stored.occurrenceId == entity.occurrenceId &&
                    stored.ordinal == expectedOrdinal,
            ) {
                "Persisted error occurrence evidence order is corrupt"
            }
            ProblemErrorEvidenceRef(
                blockId = stored.blockId,
                sourceAssetId = stored.sourceAssetId,
                evidenceKind =
                    enumValueOrCorrupt<ProblemErrorEvidenceKind>(
                        stored.evidenceKind,
                        "error occurrence evidence kind",
                    ),
            )
        }
    return StudentProblemErrorOccurrence(
        ref =
            StudentProblemErrorOccurrenceRef(
                occurrenceId = entity.occurrenceId,
                problemRevision = problemRevision,
                occurrenceCanonicalFingerprint =
                    entity.occurrenceCanonicalFingerprint,
                schemaVersion = entity.schemaVersion,
            ),
        idempotencyKey = entity.idempotencyKey,
        batchCanonicalFingerprint = entity.batchCanonicalFingerprint,
        importSourceCanonicalFingerprint =
            entity.importSourceCanonicalFingerprint,
        occurredAtEpochMillis = entity.occurredAtEpochMillis,
        importedAtEpochMillis = entity.importedAtEpochMillis,
        attributionStatus =
            entity.attributionStatus?.let {
                enumValueOrCorrupt<ProblemErrorAttributionResolutionStatus>(
                    it,
                    "error occurrence attribution status",
                )
            },
        evidenceRefs = domainEvidence,
    )
}

private fun PersistedStudentProblemRevisionRefRow.requireExactHistoryTarget(
    expected: StudentProblemRevisionRef,
) {
    check(
        learnerId == expected.problem.learnerId &&
            subject == expected.problem.subject.name &&
            problemId == expected.problem.problemId &&
            practiceUnitId == expected.problem.practiceUnitId &&
            revisionId == expected.revisionId &&
            revisionNumber == expected.revisionNumber &&
            documentCanonicalFingerprint == expected.documentCanonicalFingerprint,
    ) {
        "Error occurrence history target does not match the exact persisted revision"
    }
}
