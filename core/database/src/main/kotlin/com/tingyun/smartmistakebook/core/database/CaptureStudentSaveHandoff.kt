package com.tingyun.smartmistakebook.core.database

import com.tingyun.smartmistakebook.core.model.LOCAL_LEARNER_ID
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRef
import com.tingyun.smartmistakebook.core.model.storage.StudentProblemRevisionRef

/**
 * Explicit authority to bind one legacy capture commit to the only learner represented by the
 * legacy database.
 *
 * This is deliberately separate from the model-facing save intent. Only the local capture
 * coordinator may bind it to a commit; old rows without this evidence remain unclaimed.
 */
data class LegacyCaptureStudentSaveClaim(
    val learnerId: String,
    val tutorSessionId: String? = null,
) {
    init {
        require(learnerId == LOCAL_LEARNER_ID) {
            "The legacy database can only prove ownership for the local learner"
        }
        tutorSessionId?.let { requireHandoffText(it, "tutorSessionId") }
    }
}

/**
 * Durable state of one user-confirmed capture save while authority moves from the legacy session
 * database to the student mistake store.
 */
enum class CaptureStudentSaveHandoffState {
    PREPARED,
    FINALIZED,
}

data class PrepareCaptureStudentSaveHandoffCommand(
    val intentId: String,
    val intentCanonicalFingerprint: String,
    val learnerId: String,
    val draftId: String,
    val draftRevisionNumber: Int,
    val sessionId: String?,
    val targetProblemRef: StudentProblemRef,
    val targetProblemRevisionRef: StudentProblemRevisionRef,
    val preparedAtEpochMillis: Long,
    val schemaVersion: Int = CaptureStudentSaveHandoffRecord.SCHEMA_VERSION,
) {
    init {
        validateCaptureStudentSaveHandoffIdentity(
            intentId = intentId,
            intentCanonicalFingerprint = intentCanonicalFingerprint,
            learnerId = learnerId,
            draftId = draftId,
            draftRevisionNumber = draftRevisionNumber,
            sessionId = sessionId,
            targetProblemRef = targetProblemRef,
            targetProblemRevisionRef = targetProblemRevisionRef,
            preparedAtEpochMillis = preparedAtEpochMillis,
            schemaVersion = schemaVersion,
        )
    }
}

data class FinalizeCaptureStudentSaveHandoffCommand(
    val intentId: String,
    val intentCanonicalFingerprint: String,
    val learnerId: String,
    val targetSaveReceiptFingerprint: String,
    val finalizedAtEpochMillis: Long,
    val expectedStateVersion: Long = CaptureStudentSaveHandoffRecord.PREPARED_STATE_VERSION,
) {
    init {
        requireHandoffText(intentId, "intentId")
        requireHandoffSha256(intentCanonicalFingerprint, "intentCanonicalFingerprint")
        requireHandoffText(learnerId, "learnerId")
        requireHandoffSha256(targetSaveReceiptFingerprint, "targetSaveReceiptFingerprint")
        require(finalizedAtEpochMillis >= 0) {
            "finalizedAtEpochMillis must not be negative"
        }
        require(expectedStateVersion == CaptureStudentSaveHandoffRecord.PREPARED_STATE_VERSION) {
            "A handoff can only be finalized from its prepared state version"
        }
    }
}

data class ReadPendingCaptureStudentSaveHandoffsQuery(
    val learnerId: String,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        requireHandoffText(learnerId, "learnerId")
        require(limit in 1..MAX_LIMIT) { "Pending handoff limit must be between 1 and $MAX_LIMIT" }
    }

    companion object {
        const val DEFAULT_LIMIT = 64
        const val MAX_LIMIT = 128
    }
}

data class CaptureStudentSaveHandoffRecord(
    val intentId: String,
    val intentCanonicalFingerprint: String,
    val learnerId: String,
    val draftId: String,
    val draftRevisionNumber: Int,
    val sessionId: String?,
    val targetProblemRef: StudentProblemRef,
    val targetProblemRevisionRef: StudentProblemRevisionRef,
    val state: CaptureStudentSaveHandoffState,
    val preparedAtEpochMillis: Long,
    val finalizedAtEpochMillis: Long?,
    val targetSaveReceiptFingerprint: String?,
    val stateVersion: Long,
    val schemaVersion: Int,
) {
    init {
        validateCaptureStudentSaveHandoffIdentity(
            intentId = intentId,
            intentCanonicalFingerprint = intentCanonicalFingerprint,
            learnerId = learnerId,
            draftId = draftId,
            draftRevisionNumber = draftRevisionNumber,
            sessionId = sessionId,
            targetProblemRef = targetProblemRef,
            targetProblemRevisionRef = targetProblemRevisionRef,
            preparedAtEpochMillis = preparedAtEpochMillis,
            schemaVersion = schemaVersion,
        )
        when (state) {
            CaptureStudentSaveHandoffState.PREPARED -> {
                require(finalizedAtEpochMillis == null) {
                    "A prepared handoff cannot have a finalization timestamp"
                }
                require(targetSaveReceiptFingerprint == null) {
                    "A prepared handoff cannot have a target save receipt"
                }
                require(stateVersion == PREPARED_STATE_VERSION) {
                    "A prepared handoff must use state version $PREPARED_STATE_VERSION"
                }
            }

            CaptureStudentSaveHandoffState.FINALIZED -> {
                requireNotNull(finalizedAtEpochMillis) {
                    "A finalized handoff requires a finalization timestamp"
                }
                require(finalizedAtEpochMillis >= preparedAtEpochMillis) {
                    "A handoff cannot be finalized before it was prepared"
                }
                requireHandoffSha256(
                    requireNotNull(targetSaveReceiptFingerprint) {
                        "A finalized handoff requires a target save receipt"
                    },
                    "targetSaveReceiptFingerprint",
                )
                require(stateVersion == FINALIZED_STATE_VERSION) {
                    "A finalized handoff must use state version $FINALIZED_STATE_VERSION"
                }
            }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val PREPARED_STATE_VERSION = 1L
        const val FINALIZED_STATE_VERSION = 2L
    }
}

enum class CaptureStudentSaveHandoffWriteOutcome {
    INSERTED,
    TRANSITIONED,
    REPLAYED,
}

data class CaptureStudentSaveHandoffWriteResult(
    val outcome: CaptureStudentSaveHandoffWriteOutcome,
    val record: CaptureStudentSaveHandoffRecord,
)

class CaptureStudentSaveHandoffConflictException(intentId: String) :
    IllegalStateException("Capture save handoff $intentId conflicts with its durable intent")

class CaptureStudentSaveHandoffNotFoundException(intentId: String) :
    IllegalStateException("Capture save handoff $intentId was not prepared")

class CaptureStudentSaveHandoffIntegrityException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun validateCaptureStudentSaveHandoffIdentity(
    intentId: String,
    intentCanonicalFingerprint: String,
    learnerId: String,
    draftId: String,
    draftRevisionNumber: Int,
    sessionId: String?,
    targetProblemRef: StudentProblemRef,
    targetProblemRevisionRef: StudentProblemRevisionRef,
    preparedAtEpochMillis: Long,
    schemaVersion: Int,
) {
    requireHandoffText(intentId, "intentId")
    requireHandoffSha256(intentCanonicalFingerprint, "intentCanonicalFingerprint")
    requireHandoffText(learnerId, "learnerId")
    requireHandoffText(draftId, "draftId")
    require(draftRevisionNumber > 0) { "draftRevisionNumber must be positive" }
    sessionId?.let { requireHandoffText(it, "sessionId") }
    require(targetProblemRef.learnerId == learnerId) {
        "The target problem reference must belong to the handoff learner"
    }
    require(targetProblemRevisionRef.problem == targetProblemRef) {
        "The target revision reference must belong to the exact target problem"
    }
    require(preparedAtEpochMillis >= 0) { "preparedAtEpochMillis must not be negative" }
    require(schemaVersion == CaptureStudentSaveHandoffRecord.SCHEMA_VERSION) {
        "Unsupported capture save handoff schema version"
    }
}

private fun requireHandoffText(value: String, label: String) {
    require(
        value.isNotBlank() &&
            value == value.trim() &&
            value.length <= MAX_HANDOFF_TEXT_CHARS &&
            value.none(Char::isISOControl),
    ) {
        "$label must be a trimmed opaque value of at most $MAX_HANDOFF_TEXT_CHARS characters"
    }
}

private fun requireHandoffSha256(value: String, label: String) {
    require(HANDOFF_SHA256.matches(value)) {
        "$label must be a lowercase SHA-256 fingerprint"
    }
}

private const val MAX_HANDOFF_TEXT_CHARS = 256
private val HANDOFF_SHA256 = Regex("[0-9a-f]{64}")
