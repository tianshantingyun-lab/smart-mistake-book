package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Transaction

/**
 * Confirmed mistake save and capture handoff write transactions shared by the main DAO and stores.
 */
@Dao
internal abstract class StudentSaveWriteDao : StudentInboxWriteDao() {
    @Transaction
    open suspend fun saveConfirmedMistake(
        bundle: SaveConfirmedStudentMistakeBundle,
    ) {
        readSaveReceipt(bundle.receipt.intentConfirmationId)?.let { existing ->
            check(existing == bundle.receipt) {
                "Save intent-confirmation id was replayed with different immutable content"
            }
            val problem = checkNotNull(readProblem(bundle.problem.problem.problemId)) {
                "Intent-confirmed save receipt exists without its problem"
            }
            check(
                problem.errorBookEntryId == bundle.receipt.errorBookEntryId &&
                    readRevision(bundle.receipt.basisRevisionId) == bundle.problem.revision &&
                    checkNotNull(readCollection(bundle.collection.practiceUnitId)).let {
                        it.mistakeState != StudentMistakeEntryState.NONE.name || it.favorite
                    } &&
                    readLatestReviewCandidate(
                        learnerId = bundle.receipt.learnerId,
                        practiceUnitId = bundle.collection.practiceUnitId,
                    ) != null,
            ) {
                "Intent-confirmed save receipt does not match persisted authority"
            }
            return
        }
        commitProblem(bundle.problem, recordChange = false)
        setCollectionState(bundle.collection, recordChange = false)
        applyReviewCandidate(bundle.initialReviewCandidate, recordChange = false)
        insertSaveReceipt(bundle.receipt)
        bumpChangeVersion(bundle.receipt.learnerId)
    }

    @Transaction
    open suspend fun saveTargetConfirmedMistake(
        bundle: SaveConfirmedStudentMistakeBundle,
    ): TargetConfirmedStudentMistakeSaveOutcome {
        val targetRevisionId = bundle.problem.revision.revisionId
        val targetEntryId = bundle.receipt.errorBookEntryId
        val collisions =
            readSaveReceiptTargetCollisions(
                basisRevisionId = targetRevisionId,
                errorBookEntryId = targetEntryId,
                targetReceiptId = bundle.receipt.intentConfirmationId,
                targetCanonicalFingerprint = bundle.receipt.intentCanonicalFingerprint,
            )
        if (collisions.isNotEmpty()) {
            check(
                collisions.all { receipt ->
                    receipt.basisRevisionId == targetRevisionId &&
                        receipt.errorBookEntryId == targetEntryId
                },
            ) {
                "Target save identity collides with another confirmed mistake"
            }
            requireExactConfirmedTarget(bundle)
            return TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE
        }

        if (
            readRevision(targetRevisionId) != null ||
            readProblemByErrorBookEntryId(targetEntryId) != null
        ) {
            requireExactConfirmedTarget(bundle)
            insertSaveReceipt(bundle.receipt)
            return TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE
        }

        commitProblem(bundle.problem, recordChange = false)
        setCollectionState(bundle.collection, recordChange = false)
        applyReviewCandidate(bundle.initialReviewCandidate, recordChange = false)
        insertSaveReceipt(bundle.receipt)
        bumpChangeVersion(bundle.receipt.learnerId)
        return TargetConfirmedStudentMistakeSaveOutcome.CREATED
    }

    @Transaction
    open suspend fun saveStudentOwnedCapture(
        bundle: SaveStudentOwnedCaptureBundle,
    ): SaveStudentOwnedCaptureDbResult {
        val candidate = bundle.handoff
        val collisions =
            readCaptureSaveHandoffCollisions(
                intentId = candidate.intentId,
                draftId = candidate.draftId,
                sessionId = candidate.sessionId,
            )
        if (collisions.isNotEmpty()) {
            check(collisions.all { it.sameImmutableCaptureHandoff(candidate) }) {
                "Student capture save identity collides with another immutable handoff"
            }
            val expectedReceipt = bundle.confirmedMistake.receipt
            check(readSaveReceipt(expectedReceipt.intentConfirmationId) == expectedReceipt) {
                "Student capture handoff exists without its immutable save receipt"
            }
            check(
                readRevision(candidate.targetRevisionId) ==
                    bundle.confirmedMistake.problem.revision,
            ) {
                "Student capture handoff exists without its immutable target revision"
            }
            return SaveStudentOwnedCaptureDbResult(
                outcome = TargetConfirmedStudentMistakeSaveOutcome.DUPLICATE,
                handoff = collisions.single(),
            )
        }

        val outcome = saveTargetConfirmedMistake(bundle.confirmedMistake)
        insertCaptureSaveHandoff(candidate)
        return SaveStudentOwnedCaptureDbResult(
            outcome = outcome,
            handoff = candidate,
        )
    }

    @Transaction
    open suspend fun acknowledgeStudentCaptureSaveHandoff(
        learnerId: String,
        command: AcknowledgeStudentCaptureSaveHandoffCommand,
    ): StudentCaptureSaveHandoffEntity {
        val existing =
            checkNotNull(readCaptureSaveHandoffByIntent(command.intentId)) {
                "Student capture save handoff ${command.intentId} was not found"
            }
        check(
            existing.learnerId == learnerId &&
                existing.sourceCanonicalFingerprint == command.sourceCanonicalFingerprint &&
                existing.targetCanonicalFingerprint == command.targetCanonicalFingerprint,
        ) {
            "Student capture acknowledgement changed its immutable handoff"
        }
        check(command.acknowledgedAtEpochMillis >= existing.occurredAtEpochMillis) {
            "Student capture acknowledgement precedes its durable save"
        }
        existing.acknowledgedAtEpochMillis?.let { acknowledgedAt ->
            check(acknowledgedAt == command.acknowledgedAtEpochMillis) {
                "Student capture handoff was acknowledged with a different timestamp"
            }
            return existing
        }
        check(
            markCaptureSaveHandoffAcknowledged(
                intentId = command.intentId,
                learnerId = learnerId,
                sourceCanonicalFingerprint = command.sourceCanonicalFingerprint,
                targetCanonicalFingerprint = command.targetCanonicalFingerprint,
                acknowledgedAtEpochMillis = command.acknowledgedAtEpochMillis,
            ) == 1,
        ) {
            "Student capture handoff acknowledgement lost an update race"
        }
        return checkNotNull(readCaptureSaveHandoffByIntent(command.intentId)) {
            "Acknowledged student capture handoff is not readable"
        }
    }

    private suspend fun requireExactConfirmedTarget(
        bundle: SaveConfirmedStudentMistakeBundle,
    ) {
        check(readProblem(bundle.problem.problem.problemId) == bundle.problem.problem) {
            "Confirmed target was replayed with different problem-document fields"
        }
        check(readRevision(bundle.problem.revision.revisionId) == bundle.problem.revision) {
            "Confirmed target was replayed with different revision fields"
        }
        check(
            readPracticeUnit(bundle.problem.practiceUnit.practiceUnitId) ==
                bundle.problem.practiceUnit,
        ) {
            "Confirmed target was replayed with different practice-unit fields"
        }
        check(
            readImages(bundle.problem.revision.revisionId, MAX_STORED_IMAGES + 1) ==
                bundle.problem.images,
        ) {
            "Confirmed target was replayed with different original-image fields"
        }
        checkRevisionAuthority(bundle.problem)
        check(readCollection(bundle.collection.practiceUnitId) == bundle.collection) {
            "Confirmed target was replayed with different collection fields"
        }
        check(
            readReviewCandidate(bundle.initialReviewCandidate.candidateId) ==
                bundle.initialReviewCandidate,
        ) {
            "Confirmed target was replayed with different initial-candidate fields"
        }
    }
}
