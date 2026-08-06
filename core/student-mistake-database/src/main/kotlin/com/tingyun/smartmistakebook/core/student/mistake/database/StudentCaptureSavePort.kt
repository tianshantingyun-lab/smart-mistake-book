package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

enum class StudentCaptureSaveSourceKind {
    LIBRARY_CONFIRMATION,
    TUTOR_SESSION,
}

sealed interface StudentCaptureSaveSource {
    val kind: StudentCaptureSaveSourceKind
    val intentId: String
    val sourceCanonicalFingerprint: String
    val draftId: String
    val draftRevisionNumber: Int
    val sessionId: String?
    val occurredAtEpochMillis: Long
}

data class LibraryStudentCaptureSaveSource(
    override val intentId: String,
    override val sourceCanonicalFingerprint: String,
    override val draftId: String,
    val basisRevisionNumber: Int,
    val workspaceVersion: Long,
    val workspaceCanonicalFingerprint: String,
    val confirmationRequestId: String,
    override val occurredAtEpochMillis: Long,
) : StudentCaptureSaveSource {
    override val kind: StudentCaptureSaveSourceKind =
        StudentCaptureSaveSourceKind.LIBRARY_CONFIRMATION
    override val draftRevisionNumber: Int = basisRevisionNumber + 1
    override val sessionId: String? = null

    init {
        validateCommonCaptureSource()
        require(basisRevisionNumber > 0) {
            "Library capture basis revision must be positive"
        }
        require(workspaceVersion > 0) {
            "Library capture workspace version must be positive"
        }
        requireCaptureSha256(
            workspaceCanonicalFingerprint,
            "Library capture workspace fingerprint",
        )
        confirmationRequestId.requireCaptureText("Library capture confirmation request id")
    }
}

data class TutorStudentCaptureSaveSource(
    override val intentId: String,
    override val sourceCanonicalFingerprint: String,
    val saveRequestId: String,
    override val sessionId: String,
    override val draftId: String,
    override val draftRevisionNumber: Int,
    override val occurredAtEpochMillis: Long,
) : StudentCaptureSaveSource {
    override val kind: StudentCaptureSaveSourceKind = StudentCaptureSaveSourceKind.TUTOR_SESSION

    init {
        validateCommonCaptureSource()
        saveRequestId.requireCaptureText("Tutor capture save request id")
    }
}

data class SaveStudentOwnedCaptureCommand(
    val source: StudentCaptureSaveSource,
    val target: SaveTargetConfirmedStudentMistakeCommand,
) {
    init {
        require(target.problem.committedAtEpochMillis == source.occurredAtEpochMillis) {
            "Capture source and student commit times must agree"
        }
        require(target.confirmedAtEpochMillis == source.occurredAtEpochMillis) {
            "Capture confirmation and student commit times must agree"
        }
    }
}

data class StudentCaptureSaveHandoffRecord(
    val source: StudentCaptureSaveSource,
    val learnerId: String,
    val targetProblem: StudentProblemRef,
    val targetRevision: StudentProblemRevisionRef,
    val errorBookEntryId: String,
    val targetCanonicalFingerprint: String,
    val acknowledgedAtEpochMillis: Long?,
) {
    init {
        learnerId.requireCaptureText("Capture save learner id")
        require(targetProblem.learnerId == learnerId) {
            "Capture save target problem belongs to another learner"
        }
        require(targetRevision.problem == targetProblem) {
            "Capture save target revision belongs to another problem"
        }
        errorBookEntryId.requireCaptureText("Capture save error-book entry id")
        requireCaptureSha256(targetCanonicalFingerprint, "Capture save target fingerprint")
        acknowledgedAtEpochMillis?.let { acknowledgedAt ->
            require(acknowledgedAt >= source.occurredAtEpochMillis) {
                "Capture session acknowledgement precedes the student commit"
            }
        }
    }
}

data class StudentOwnedCaptureSaveReceipt(
    val outcome: TargetConfirmedStudentMistakeSaveOutcome,
    val handoff: StudentCaptureSaveHandoffRecord,
)

data class ReadPendingStudentCaptureSaveHandoffsQuery(
    val limit: Int = DEFAULT_LIMIT,
    val afterOccurredAtEpochMillis: Long? = null,
    val afterIntentId: String? = null,
) {
    init {
        require(limit in 1..MAX_LIMIT) {
            "Pending student capture handoff limit must be between 1 and $MAX_LIMIT"
        }
        require(
            (afterOccurredAtEpochMillis == null) == (afterIntentId == null),
        ) {
            "Pending student capture cursor must include both time and intent id"
        }
        afterOccurredAtEpochMillis?.let {
            require(it >= 0) { "Pending student capture cursor time must not be negative" }
        }
        afterIntentId?.requireCaptureText("Pending student capture cursor intent id")
    }

    companion object {
        const val DEFAULT_LIMIT = 64
        const val MAX_LIMIT = 128
    }
}

data class AcknowledgeStudentCaptureSaveHandoffCommand(
    val intentId: String,
    val sourceCanonicalFingerprint: String,
    val targetCanonicalFingerprint: String,
    val acknowledgedAtEpochMillis: Long,
) {
    init {
        intentId.requireCaptureText("Capture acknowledgement intent id")
        requireCaptureSha256(
            sourceCanonicalFingerprint,
            "Capture acknowledgement source fingerprint",
        )
        requireCaptureSha256(
            targetCanonicalFingerprint,
            "Capture acknowledgement target fingerprint",
        )
        require(acknowledgedAtEpochMillis >= 0) {
            "Capture acknowledgement time must not be negative"
        }
    }
}

/**
 * Learner-bound authority for user-initiated capture saves.
 *
 * The first operation atomically commits the student-owned mistake and its pending source-session
 * acknowledgement. The remaining operations expose only that acknowledgement outbox.
 */
interface LearnerBoundStudentCaptureHandoffPort {
    val learnerId: String

    suspend fun readPending(
        query: ReadPendingStudentCaptureSaveHandoffsQuery =
            ReadPendingStudentCaptureSaveHandoffsQuery(),
    ): List<StudentCaptureSaveHandoffRecord>

    suspend fun readByDraftIds(
        draftIds: Set<String>,
    ): List<StudentCaptureSaveHandoffRecord>

    suspend fun readBySessionId(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord?

    suspend fun acknowledge(
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffRecord
}

internal interface LearnerBoundStudentCaptureSavePort :
    LearnerBoundStudentCaptureHandoffPort {
    suspend fun save(
        command: SaveStudentOwnedCaptureCommand,
    ): StudentOwnedCaptureSaveReceipt
}

@Entity(
    tableName = "student_capture_save_handoff",
    foreignKeys = [
        ForeignKey(
            entity = StudentProblemRevisionEntity::class,
            parentColumns = ["revision_id"],
            childColumns = ["target_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["draft_id"], unique = true),
        Index(value = ["session_id"], unique = true),
        Index(value = ["target_revision_id"]),
        Index(value = ["target_canonical_fingerprint"]),
        Index(
            value = [
                "learner_id",
                "acknowledged_at_epoch_millis",
                "occurred_at_epoch_millis",
                "intent_id",
            ],
        ),
    ],
    primaryKeys = ["intent_id"],
)
internal data class StudentCaptureSaveHandoffEntity(
    @ColumnInfo(name = "intent_id")
    val intentId: String,
    @ColumnInfo(name = "source_kind")
    val sourceKind: String,
    @ColumnInfo(name = "source_canonical_fingerprint")
    val sourceCanonicalFingerprint: String,
    @ColumnInfo(name = "learner_id")
    val learnerId: String,
    @ColumnInfo(name = "draft_id")
    val draftId: String,
    @ColumnInfo(name = "draft_revision_number")
    val draftRevisionNumber: Int,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    @ColumnInfo(name = "basis_revision_number")
    val basisRevisionNumber: Int?,
    @ColumnInfo(name = "workspace_version")
    val workspaceVersion: Long?,
    @ColumnInfo(name = "workspace_canonical_fingerprint")
    val workspaceCanonicalFingerprint: String?,
    @ColumnInfo(name = "confirmation_request_id")
    val confirmationRequestId: String?,
    @ColumnInfo(name = "save_request_id")
    val saveRequestId: String?,
    @ColumnInfo(name = "target_subject")
    val targetSubject: String,
    @ColumnInfo(name = "target_problem_id")
    val targetProblemId: String,
    @ColumnInfo(name = "target_practice_unit_id")
    val targetPracticeUnitId: String,
    @ColumnInfo(name = "target_revision_id")
    val targetRevisionId: String,
    @ColumnInfo(name = "target_revision_number")
    val targetRevisionNumber: Int,
    @ColumnInfo(name = "target_document_canonical_fingerprint")
    val targetDocumentCanonicalFingerprint: String,
    @ColumnInfo(name = "error_book_entry_id")
    val errorBookEntryId: String,
    @ColumnInfo(name = "target_canonical_fingerprint")
    val targetCanonicalFingerprint: String,
    @ColumnInfo(name = "occurred_at_epoch_millis")
    val occurredAtEpochMillis: Long,
    @ColumnInfo(name = "acknowledged_at_epoch_millis")
    val acknowledgedAtEpochMillis: Long?,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int,
)

internal data class SaveStudentOwnedCaptureBundle(
    val confirmedMistake: SaveConfirmedStudentMistakeBundle,
    val handoff: StudentCaptureSaveHandoffEntity,
)

internal data class SaveStudentOwnedCaptureDbResult(
    val outcome: TargetConfirmedStudentMistakeSaveOutcome,
    val handoff: StudentCaptureSaveHandoffEntity,
)

private fun StudentCaptureSaveSource.validateCommonCaptureSource() {
    intentId.requireCaptureText("Capture save intent id")
    requireCaptureSha256(sourceCanonicalFingerprint, "Capture save source fingerprint")
    draftId.requireCaptureText("Capture save draft id")
    require(draftRevisionNumber > 0) { "Capture save draft revision must be positive" }
    sessionId?.requireCaptureText("Capture save tutor session id")
    require(occurredAtEpochMillis >= 0) { "Capture save time must not be negative" }
}

private fun String.requireCaptureText(label: String) {
    require(
        isNotBlank() &&
            this == trim() &&
            length <= MAX_CAPTURE_ID_CHARS &&
            none(Char::isISOControl),
    ) {
        "$label must be a trimmed opaque value of at most $MAX_CAPTURE_ID_CHARS characters"
    }
}

private fun requireCaptureSha256(value: String, label: String) {
    require(CAPTURE_SHA_256.matches(value)) { "$label must be a lowercase SHA-256 fingerprint" }
}

internal const val STUDENT_CAPTURE_SAVE_HANDOFF_SCHEMA_VERSION = 1
private const val MAX_CAPTURE_ID_CHARS = 256
private val CAPTURE_SHA_256 = Regex("[a-f0-9]{64}")
