package com.tingyun.smartmistakebook.core.data.capture

import com.tingyun.smartmistakebook.core.domain.CapturedProblemCommitSummary
import com.tingyun.smartmistakebook.core.student.mistake.database.AcknowledgeStudentCaptureSaveHandoffCommand
import com.tingyun.smartmistakebook.core.student.mistake.database.ReadPendingStudentCaptureSaveHandoffsQuery
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveHandoffRecord
import com.tingyun.smartmistakebook.core.student.mistake.database.StudentCaptureSaveSource
import com.tingyun.smartmistakebook.core.student.mistake.database.TargetConfirmedStudentMistakeSaveOutcome
import java.util.concurrent.CancellationException

/**
 * New capture commits go directly to the student-owned occurrence transaction.
 *
 * Legacy mirror recovery is intentionally absent: migration may replay historical rows through
 * its own route, but a new save never writes both the legacy and student stores.
 */
internal class StudentAuthorityCaptureCommitCoordinator(
    private val learnerId: String,
    private val commitPortProvider: suspend () -> ProductionCaptureOccurrenceCommitPort,
    private val acknowledgeLegacySession:
        suspend (StudentCaptureSaveHandoffRecord) -> Unit,
) {
    suspend fun commit(
        prepared: PreparedStudentOwnedCaptureCommit,
        identityEvidenceRequest: ProductionStudentProblemIdentityEvidenceRequest =
            ProductionStudentProblemIdentityEvidenceRequest.ExactAssetSelectionOrUnresolved,
    ): CapturedProblemCommitSummary {
        prepared.requireOwnerScope(learnerId)
        val port = openPort()
        val receipt = port.save(prepared, identityEvidenceRequest)
        receipt.requireExactPreparedCapture(prepared, learnerId)
        acknowledgeBestEffort(receipt.handoff, port)
        return receipt.handoff.toCommitSummary(
            created =
                receipt.save.outcome ==
                    TargetConfirmedStudentMistakeSaveOutcome.CREATED,
        )
    }

    suspend fun replayPendingSessionAcknowledgements() {
        val port = openPort()
        var rounds = 0
        var afterOccurredAtEpochMillis: Long? = null
        var afterIntentId: String? = null
        while (rounds++ < MAX_PENDING_ACK_REPLAY_ROUNDS) {
            val pending =
                port.readPending(
                    ReadPendingStudentCaptureSaveHandoffsQuery(
                        limit = PENDING_ACK_REPLAY_PAGE_SIZE,
                        afterOccurredAtEpochMillis = afterOccurredAtEpochMillis,
                        afterIntentId = afterIntentId,
                    ),
                )
            check(pending.size <= PENDING_ACK_REPLAY_PAGE_SIZE) {
                "Student capture authority returned too many pending acknowledgements"
            }
            check(pending.map { it.source.intentId }.distinct().size == pending.size) {
                "Student capture authority returned duplicate pending acknowledgements"
            }
            for (handoff in pending) {
                handoff.requireOwnerScope(learnerId)
                acknowledgeBestEffort(handoff, port)
            }
            if (pending.size < PENDING_ACK_REPLAY_PAGE_SIZE) {
                return
            }
            val cursor = pending.last()
            afterOccurredAtEpochMillis = cursor.source.occurredAtEpochMillis
            afterIntentId = cursor.source.intentId
        }
        error("Student capture acknowledgement replay exceeded its bounded rounds")
    }

    suspend fun replayCommitted(
        source: StudentCaptureSaveSource,
    ): CapturedProblemCommitSummary? {
        val port = openPort()
        val durableForDraft = port.readByDraftIds(setOf(source.draftId))
        if (durableForDraft.isEmpty()) return null
        check(durableForDraft.size == 1) {
            "A capture draft cannot resolve to multiple student-owned save occurrences"
        }
        val durable = durableForDraft.single()
        durable.requireOwnerScope(learnerId)
        check(durable.source == source) {
            "Capture save source was replayed with different immutable content"
        }
        acknowledgeBestEffort(durable, port)
        return durable.toCommitSummary(created = false)
    }

    suspend fun committedDraftIds(
        draftIds: Set<String>,
    ): Set<String> {
        if (draftIds.isEmpty()) return emptySet()
        val port = openPort()
        return draftIds
            .chunked(STUDENT_CAPTURE_LOOKUP_PAGE_SIZE)
            .flatMap { page -> port.readByDraftIds(page.toSet()) }
            .onEach { it.requireOwnerScope(learnerId) }
            .mapTo(linkedSetOf()) { it.source.draftId }
    }

    suspend fun committedTutorSession(
        sessionId: String,
    ): StudentCaptureSaveHandoffRecord? {
        val handoff = openPort().readBySessionId(sessionId) ?: return null
        handoff.requireOwnerScope(learnerId)
        return handoff
    }

    private suspend fun acknowledgeBestEffort(
        handoff: StudentCaptureSaveHandoffRecord,
        port: ProductionCaptureOccurrenceCommitPort,
    ): Boolean {
        handoff.requireOwnerScope(learnerId)
        if (handoff.acknowledgedAtEpochMillis != null) return true
        val acknowledged =
            try {
                acknowledgeLegacySession(handoff)
                port.acknowledge(
                    AcknowledgeStudentCaptureSaveHandoffCommand(
                        intentId = handoff.source.intentId,
                        sourceCanonicalFingerprint =
                            handoff.source.sourceCanonicalFingerprint,
                        targetCanonicalFingerprint =
                            handoff.targetCanonicalFingerprint,
                        acknowledgedAtEpochMillis =
                            handoff.source.occurredAtEpochMillis,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The student receipt is durable; the pending handoff remains the retry signal.
                return false
            }
        acknowledged.requireExactAcknowledgementOf(handoff)
        return true
    }

    private suspend fun openPort(): ProductionCaptureOccurrenceCommitPort =
        commitPortProvider().also { port ->
            check(port.learnerId == learnerId) {
                "Capture commit port belongs to another learner"
            }
        }

    private companion object {
        const val PENDING_ACK_REPLAY_PAGE_SIZE = 128
        const val MAX_PENDING_ACK_REPLAY_ROUNDS = 1_024
        const val STUDENT_CAPTURE_LOOKUP_PAGE_SIZE = 128
    }
}

private fun PreparedStudentOwnedCaptureCommit.requireOwnerScope(
    expectedLearnerId: String,
) {
    val source = command.source
    val revision = command.target.problem.revision
    check(revision.problem.learnerId == expectedLearnerId) {
        "Prepared capture commit belongs to another learner"
    }
    check(summary.draftId == source.draftId) {
        "Prepared capture summary belongs to another draft"
    }
    check(summary.problemRevisionId == revision.revisionId) {
        "Prepared capture summary belongs to another target revision"
    }
    check(summary.problemId == revision.problem.problemId) {
        "Prepared capture summary belongs to another target problem"
    }
    check(summary.practiceUnitId == revision.problem.practiceUnitId) {
        "Prepared capture summary belongs to another practice unit"
    }
    check(summary.errorBookEntryId == command.target.problem.errorBookEntryId) {
        "Prepared capture summary belongs to another error-book entry"
    }
}

private fun StudentCaptureSaveHandoffRecord.requireOwnerScope(
    expectedLearnerId: String,
) {
    check(learnerId == expectedLearnerId && targetProblem.learnerId == expectedLearnerId) {
        "Student capture handoff belongs to another learner"
    }
    check(targetRevision.problem == targetProblem) {
        "Student capture handoff target revision changed its problem"
    }
    check(source.draftId.isNotBlank() && source.intentId.isNotBlank()) {
        "Student capture handoff has no durable source identity"
    }
}

private fun ProductionCaptureOccurrenceCommitReceipt.requireExactPreparedCapture(
    prepared: PreparedStudentOwnedCaptureCommit,
    expectedLearnerId: String,
) {
    val durableHandoff = handoff
    val durableOccurrence = occurrence
    durableHandoff.requireOwnerScope(expectedLearnerId)
    val revision = prepared.command.target.problem.revision
    check(durableHandoff.source == prepared.command.source)
    check(durableHandoff.targetProblem.subject == revision.problem.subject)
    check(
        durableHandoff.targetRevision.documentCanonicalFingerprint ==
            revision.documentCanonicalFingerprint,
    )
    check(durableOccurrence.ref.problemRevision == durableHandoff.targetRevision)
    check(durableOccurrence.idempotencyKey == durableHandoff.source.intentId)
    check(
        durableOccurrence.occurredAtEpochMillis ==
            durableHandoff.source.occurredAtEpochMillis,
    )
}

private fun StudentCaptureSaveHandoffRecord.requireExactAcknowledgementOf(
    pending: StudentCaptureSaveHandoffRecord,
) {
    check(source == pending.source)
    check(learnerId == pending.learnerId)
    check(targetProblem == pending.targetProblem)
    check(targetRevision == pending.targetRevision)
    check(errorBookEntryId == pending.errorBookEntryId)
    check(targetCanonicalFingerprint == pending.targetCanonicalFingerprint)
    check(acknowledgedAtEpochMillis == pending.source.occurredAtEpochMillis) {
        "Student capture acknowledgement returned another durable receipt"
    }
}

private fun StudentCaptureSaveHandoffRecord.toCommitSummary(
    created: Boolean,
): CapturedProblemCommitSummary =
    CapturedProblemCommitSummary(
        draftId = source.draftId,
        problemId = targetProblem.problemId,
        problemRevisionId = targetRevision.revisionId,
        practiceUnitId = targetProblem.practiceUnitId,
        errorBookEntryId = errorBookEntryId,
        created = created,
    )
