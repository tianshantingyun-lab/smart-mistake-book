package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

/**
 * Narrow legacy-session capability for crash-safe capture saves into student-mistakes.db.
 *
 * It deliberately cannot write legacy problem, error-book, mastery, or knowledge records.
 */
interface CaptureStudentSaveHandoffJournalPort {
    suspend fun prepare(
        command: PrepareCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult

    suspend fun finalize(
        command: FinalizeCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult

    suspend fun readPending(
        query: ReadPendingCaptureStudentSaveHandoffsQuery,
    ): List<CaptureStudentSaveHandoffRecord>
}

/**
 * Capability adapter that retains only the three handoff-journal operations.
 */
class StudyDatabaseCaptureStudentSaveHandoffJournalAdapter(
    private val prepareHandoff:
        suspend (
            PrepareCaptureStudentSaveHandoffCommand,
        ) -> CaptureStudentSaveHandoffWriteResult,
    private val finalizeHandoff:
        suspend (
            FinalizeCaptureStudentSaveHandoffCommand,
        ) -> CaptureStudentSaveHandoffWriteResult,
    private val readPendingHandoffs:
        suspend (
            ReadPendingCaptureStudentSaveHandoffsQuery,
        ) -> List<CaptureStudentSaveHandoffRecord>,
) : CaptureStudentSaveHandoffJournalPort {
    override suspend fun prepare(
        command: PrepareCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult = prepareHandoff(command)

    override suspend fun finalize(
        command: FinalizeCaptureStudentSaveHandoffCommand,
    ): CaptureStudentSaveHandoffWriteResult = finalizeHandoff(command)

    override suspend fun readPending(
        query: ReadPendingCaptureStudentSaveHandoffsQuery,
    ): List<CaptureStudentSaveHandoffRecord> = readPendingHandoffs(query)
}

sealed interface StudentOwnedCaptureSessionAckSource {
    val intentId: String
    val sourceCanonicalFingerprint: String
    val draftId: String
    val draftRevisionNumber: Int
    val sessionId: String?
    val occurredAtEpochMillis: Long
}

data class StudentOwnedLibraryCaptureSessionAckSource(
    override val intentId: String,
    override val sourceCanonicalFingerprint: String,
    val workspace: ExpectedProblemDraftEditWorkspace,
) : StudentOwnedCaptureSessionAckSource {
    override val draftId: String = workspace.draftId
    override val draftRevisionNumber: Int = workspace.basisRevisionNumber + 1
    override val sessionId: String? = null
    override val occurredAtEpochMillis: Long = workspace.finalOccurredAtEpochMillis

    init {
        requireSessionAckText(intentId, "intentId")
        requireSessionAckSha256(sourceCanonicalFingerprint, "sourceCanonicalFingerprint")
    }
}

data class StudentOwnedTutorCaptureSessionAckSource(
    override val intentId: String,
    override val sourceCanonicalFingerprint: String,
    val saveRequestId: String,
    override val sessionId: String,
    override val draftId: String,
    override val draftRevisionNumber: Int,
    override val occurredAtEpochMillis: Long,
) : StudentOwnedCaptureSessionAckSource {
    init {
        requireSessionAckText(intentId, "intentId")
        requireSessionAckSha256(sourceCanonicalFingerprint, "sourceCanonicalFingerprint")
        requireSessionAckText(saveRequestId, "saveRequestId")
        requireSessionAckText(sessionId, "sessionId")
        requireSessionAckText(draftId, "draftId")
        require(draftRevisionNumber > 0) { "draftRevisionNumber must be positive" }
        require(occurredAtEpochMillis >= 0) {
            "occurredAtEpochMillis must not be negative"
        }
    }
}

/**
 * Exact, session-only acknowledgement of a business save already committed by student-mistakes.
 *
 * Implementations may advance the temporary draft/session lifecycle and write the existing handoff
 * receipt. They must not create legacy problem, error-book, knowledge, or mastery rows.
 */
data class AcknowledgeStudentOwnedCaptureSessionCommand(
    val learnerId: String,
    val source: StudentOwnedCaptureSessionAckSource,
    val targetProblem: StudentProblemRef,
    val targetRevision: StudentProblemRevisionRef,
    val targetSaveReceiptFingerprint: String,
    val acknowledgedAtEpochMillis: Long,
) {
    init {
        requireSessionAckText(learnerId, "learnerId")
        require(targetProblem.learnerId == learnerId) {
            "Student-owned capture acknowledgement belongs to another learner"
        }
        require(targetRevision.problem == targetProblem) {
            "Student-owned capture acknowledgement revision belongs to another problem"
        }
        requireSessionAckSha256(
            targetSaveReceiptFingerprint,
            "targetSaveReceiptFingerprint",
        )
        require(acknowledgedAtEpochMillis >= source.occurredAtEpochMillis) {
            "Student-owned capture acknowledgement precedes the business save"
        }
    }
}

data class StudentOwnedCaptureSessionAckResult(
    val created: Boolean,
    val handoff: CaptureStudentSaveHandoffRecord,
) {
    init {
        require(handoff.state == CaptureStudentSaveHandoffState.FINALIZED) {
            "Student-owned capture session acknowledgement must be finalized"
        }
    }
}

interface StudentOwnedCaptureSessionAckPort {
    suspend fun acknowledgeStudentOwnedCaptureSession(
        command: AcknowledgeStudentOwnedCaptureSessionCommand,
    ): StudentOwnedCaptureSessionAckResult
}

private fun requireSessionAckText(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_SESSION_ACK_TEXT_CHARS &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed opaque value of at most $MAX_SESSION_ACK_TEXT_CHARS characters"
    }
}

private fun requireSessionAckSha256(value: String, label: String) {
    require(SESSION_ACK_SHA_256.matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private const val MAX_SESSION_ACK_TEXT_CHARS = 256
private val SESSION_ACK_SHA_256 = Regex("[a-f0-9]{64}")
