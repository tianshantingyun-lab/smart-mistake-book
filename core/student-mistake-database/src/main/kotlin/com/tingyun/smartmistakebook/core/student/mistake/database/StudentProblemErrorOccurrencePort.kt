package com.tingyun.smartmistakebook.core.student.mistake.database

import com.tingyun.smartmistakebook.core.model.CanonicalSha256
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionCandidate
import com.tingyun.smartmistakebook.core.model.ProblemErrorAttributionResolutionStatus
import com.tingyun.smartmistakebook.core.model.ProblemErrorEvidenceRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

const val STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION = 1
const val DEFAULT_STUDENT_PROBLEM_ERROR_OCCURRENCE_HISTORY_LIMIT = 50
const val MAX_STUDENT_PROBLEM_ERROR_OCCURRENCE_HISTORY_LIMIT = 100

/**
 * Stable reference to one immutable error occurrence.
 *
 * The occurrence fingerprint binds the reference to the exact learner/problem/revision, source,
 * times, attribution state, and captured-document evidence without exposing a database identity.
 */
data class StudentProblemErrorOccurrenceRef(
    val occurrenceId: String,
    val problemRevision: StudentProblemRevisionRef,
    val occurrenceCanonicalFingerprint: String,
    val schemaVersion: Int = STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION,
) {
    init {
        require(schemaVersion == STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION) {
            "Unsupported student-problem error-occurrence reference version"
        }
        occurrenceId.requireStoreText("Error occurrence id", MAX_ID_CHARS)
        problemRevision.problem.subject.requireHighSchoolSubject()
        requireSha256(
            occurrenceCanonicalFingerprint,
            "Error occurrence canonical fingerprint",
        )
    }

    val canonicalFingerprint: String
        get() =
            CanonicalSha256(ERROR_OCCURRENCE_REF_FINGERPRINT_DOMAIN)
                .field("schemaVersion", schemaVersion)
                .field("occurrenceId", occurrenceId)
                .field("problemRevision", problemRevision.canonicalFingerprint)
                .field(
                    "occurrenceCanonicalFingerprint",
                    occurrenceCanonicalFingerprint,
                )
                .finish()
}

/**
 * Append-only command for one observed mistake. A batch may contain a revision only once; another
 * batch may append another occurrence for the same exact revision.
 */
data class AppendStudentProblemErrorOccurrenceCommand(
    val occurrenceId: String,
    val idempotencyKey: String,
    val problemRevision: StudentProblemRevisionRef,
    val batchCanonicalFingerprint: String,
    val importSourceCanonicalFingerprint: String,
    val occurredAtEpochMillis: Long,
    val importedAtEpochMillis: Long,
    val attributionStatus: ProblemErrorAttributionResolutionStatus? = null,
    val evidenceRefs: List<ProblemErrorEvidenceRef> = emptyList(),
) {
    init {
        occurrenceId.requireStoreText("Error occurrence id", MAX_ID_CHARS)
        idempotencyKey.requireStoreText("Error occurrence idempotency key", MAX_ID_CHARS)
        requireSha256(
            batchCanonicalFingerprint,
            "Error occurrence batch fingerprint",
        )
        requireSha256(
            importSourceCanonicalFingerprint,
            "Error occurrence import-source fingerprint",
        )
        require(occurredAtEpochMillis >= 0) {
            "Error occurrence time must not be negative"
        }
        require(importedAtEpochMillis >= occurredAtEpochMillis) {
            "Error occurrence import time precedes the occurrence"
        }
        require(evidenceRefs.size <= ProblemErrorAttributionCandidate.MAX_EVIDENCE_REFS) {
            "Error occurrence has too many evidence references"
        }
        require(evidenceRefs.distinct().size == evidenceRefs.size) {
            "Error occurrence evidence references must be unique"
        }
        when (attributionStatus) {
            ProblemErrorAttributionResolutionStatus.RESOLVED -> {
                require(evidenceRefs.isNotEmpty()) {
                    "A resolved error occurrence requires exact captured evidence"
                }
            }

            ProblemErrorAttributionResolutionStatus.UNRESOLVED -> {
                require(evidenceRefs.isEmpty()) {
                    "An unresolved error occurrence cannot claim evidence attribution"
                }
            }

            null -> Unit
        }
    }

    val occurrenceCanonicalFingerprint: String
        get() =
            canonicalErrorOccurrenceFingerprint(
                occurrenceId = occurrenceId,
                idempotencyKey = idempotencyKey,
                problemRevision = problemRevision,
                batchCanonicalFingerprint = batchCanonicalFingerprint,
                importSourceCanonicalFingerprint = importSourceCanonicalFingerprint,
                occurredAtEpochMillis = occurredAtEpochMillis,
                importedAtEpochMillis = importedAtEpochMillis,
                attributionStatus = attributionStatus,
                evidenceRefs = evidenceRefs,
            )

    val ref: StudentProblemErrorOccurrenceRef
        get() =
            StudentProblemErrorOccurrenceRef(
                occurrenceId = occurrenceId,
                problemRevision = problemRevision,
                occurrenceCanonicalFingerprint = occurrenceCanonicalFingerprint,
            )
}

data class StudentProblemErrorOccurrence(
    val ref: StudentProblemErrorOccurrenceRef,
    val idempotencyKey: String,
    val batchCanonicalFingerprint: String,
    val importSourceCanonicalFingerprint: String,
    val occurredAtEpochMillis: Long,
    val importedAtEpochMillis: Long,
    val attributionStatus: ProblemErrorAttributionResolutionStatus?,
    val evidenceRefs: List<ProblemErrorEvidenceRef>,
) {
    init {
        idempotencyKey.requireStoreText("Error occurrence idempotency key", MAX_ID_CHARS)
        requireSha256(
            batchCanonicalFingerprint,
            "Error occurrence batch fingerprint",
        )
        requireSha256(
            importSourceCanonicalFingerprint,
            "Error occurrence import-source fingerprint",
        )
        require(occurredAtEpochMillis >= 0) {
            "Error occurrence time must not be negative"
        }
        require(importedAtEpochMillis >= occurredAtEpochMillis) {
            "Error occurrence import time precedes the occurrence"
        }
        require(
            evidenceRefs == evidenceRefs.normalizedErrorOccurrenceEvidence(),
        ) {
            "Persisted error occurrence evidence must use stable order without duplicates"
        }
        require(evidenceRefs.size <= ProblemErrorAttributionCandidate.MAX_EVIDENCE_REFS) {
            "Persisted error occurrence has too many evidence references"
        }
        when (attributionStatus) {
            ProblemErrorAttributionResolutionStatus.RESOLVED -> {
                require(evidenceRefs.isNotEmpty()) {
                    "A resolved error occurrence requires exact captured evidence"
                }
            }

            ProblemErrorAttributionResolutionStatus.UNRESOLVED -> {
                require(evidenceRefs.isEmpty()) {
                    "An unresolved error occurrence cannot claim evidence attribution"
                }
            }

            null -> Unit
        }
        require(
            ref.occurrenceCanonicalFingerprint ==
                canonicalErrorOccurrenceFingerprint(
                    occurrenceId = ref.occurrenceId,
                    idempotencyKey = idempotencyKey,
                    problemRevision = ref.problemRevision,
                    batchCanonicalFingerprint = batchCanonicalFingerprint,
                    importSourceCanonicalFingerprint = importSourceCanonicalFingerprint,
                    occurredAtEpochMillis = occurredAtEpochMillis,
                    importedAtEpochMillis = importedAtEpochMillis,
                    attributionStatus = attributionStatus,
                    evidenceRefs = evidenceRefs,
                ),
        ) {
            "Error occurrence content does not match its stable reference"
        }
    }
}

data class AppendStudentProblemErrorOccurrenceResult(
    val created: Boolean,
    val occurrence: StudentProblemErrorOccurrence,
)

class StudentProblemErrorOccurrenceConflictException(
    message: String,
) : IllegalStateException(message)

/**
 * Learner-bound append/read capability. It exposes no Room, DAO, SQL, or other authority writer.
 *
 * History is newest-first with a deterministic order:
 * occurred-at descending, imported-at descending, occurrence id ascending.
 */
interface StudentProblemErrorOccurrencePort {
    val learnerId: String

    suspend fun append(
        command: AppendStudentProblemErrorOccurrenceCommand,
    ): AppendStudentProblemErrorOccurrenceResult

    suspend fun readHistory(
        problemRevision: StudentProblemRevisionRef,
        limit: Int = DEFAULT_STUDENT_PROBLEM_ERROR_OCCURRENCE_HISTORY_LIMIT,
    ): List<StudentProblemErrorOccurrence>
}

internal fun List<ProblemErrorEvidenceRef>.normalizedErrorOccurrenceEvidence():
    List<ProblemErrorEvidenceRef> =
    distinct().sortedWith(
        compareBy(
            ProblemErrorEvidenceRef::blockId,
            ProblemErrorEvidenceRef::sourceAssetId,
            { it.evidenceKind.name },
        ),
    )

internal fun canonicalErrorOccurrenceFingerprint(
    occurrenceId: String,
    idempotencyKey: String,
    problemRevision: StudentProblemRevisionRef,
    batchCanonicalFingerprint: String,
    importSourceCanonicalFingerprint: String,
    occurredAtEpochMillis: Long,
    importedAtEpochMillis: Long,
    attributionStatus: ProblemErrorAttributionResolutionStatus?,
    evidenceRefs: List<ProblemErrorEvidenceRef>,
): String {
    val canonical =
        CanonicalSha256(ERROR_OCCURRENCE_PAYLOAD_FINGERPRINT_DOMAIN)
            .field("schemaVersion", STUDENT_PROBLEM_ERROR_OCCURRENCE_SCHEMA_VERSION)
            .field("occurrenceId", occurrenceId)
            .field("idempotencyKey", idempotencyKey)
            .field("problemRevision", problemRevision.canonicalFingerprint)
            .field("batchCanonicalFingerprint", batchCanonicalFingerprint)
            .field(
                "importSourceCanonicalFingerprint",
                importSourceCanonicalFingerprint,
            )
            .field("occurredAtEpochMillis", occurredAtEpochMillis)
            .field("importedAtEpochMillis", importedAtEpochMillis)
            .nullableField("attributionStatus", attributionStatus?.name)
    val normalizedEvidence = evidenceRefs.normalizedErrorOccurrenceEvidence()
    canonical.field("evidenceCount", normalizedEvidence.size)
    normalizedEvidence.forEachIndexed { index, evidence ->
        canonical
            .field("evidence[$index].blockId", evidence.blockId)
            .field("evidence[$index].sourceAssetId", evidence.sourceAssetId)
            .field("evidence[$index].kind", evidence.evidenceKind.name)
    }
    return canonical.finish()
}

private const val ERROR_OCCURRENCE_REF_FINGERPRINT_DOMAIN =
    "student-problem-error-occurrence-ref-v1"
private const val ERROR_OCCURRENCE_PAYLOAD_FINGERPRINT_DOMAIN =
    "student-problem-error-occurrence-v1"
