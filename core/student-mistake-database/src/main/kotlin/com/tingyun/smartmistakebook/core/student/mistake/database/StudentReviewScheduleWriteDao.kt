package com.tingyun.smartmistakebook.core.student.mistake.database

import androidx.room3.Dao
import androidx.room3.Transaction

/**
 * Review candidate and plan/queue write transactions shared by the main DAO and review workflows.
 */
@Dao
internal abstract class StudentReviewScheduleWriteDao : StudentReviewDao() {
    @Transaction
    open suspend fun upsertReviewCandidate(candidate: StudentReviewCandidateEntity) {
        applyReviewCandidate(candidate, recordChange = true)
    }

    protected suspend fun applyReviewCandidate(
        candidate: StudentReviewCandidateEntity,
        recordChange: Boolean,
    ) {
        val problem = checkNotNull(readProblemByPracticeUnit(candidate.practiceUnitId)) {
            "Cannot schedule a missing practice unit"
        }
        val collection = checkNotNull(readCollection(candidate.practiceUnitId)) {
            "Only an active mistake entry can become a review candidate"
        }
        check(
            problem.learnerId == candidate.learnerId &&
                problem.lifecycleState == StudentProblemLifecycleState.ACTIVE.name &&
                collection.mistakeState == StudentMistakeEntryState.ACTIVE.name,
        ) {
            "Review candidates require an active learner-owned mistake entry"
        }
        val revision = checkNotNull(readRevision(candidate.basisRevisionId)) {
            "Review candidate basis revision does not exist"
        }
        check(revision.problemId == problem.problemId) {
            "Review candidate basis revision belongs to another problem"
        }
        val existing = readReviewCandidate(candidate.candidateId)
        if (existing == null) {
            insertReviewCandidate(candidate)
            if (recordChange) bumpChangeVersion(candidate.learnerId)
            return
        }
        val normalized =
            candidate.copy(createdAtEpochMillis = existing.createdAtEpochMillis)
        when {
            candidate.candidateVersion < existing.candidateVersion ->
                error("Review candidate version moved backwards")

            candidate.candidateVersion == existing.candidateVersion ->
                check(existing == normalized) {
                    "Review candidate version was replayed with different content"
                }

            else -> {
                check(
                    updateReviewCandidate(normalized) == 1,
                ) {
                    "Review candidate update did not affect exactly one row"
                }
                if (recordChange) bumpChangeVersion(candidate.learnerId)
            }
        }
    }

    @Transaction
    open suspend fun storeReviewQueue(bundle: StoreStudentReviewQueueBundle) {
        val existing = readReviewPlan(bundle.plan.planId)
        if (existing != null) {
            check(
                existing == bundle.plan &&
                    readReviewQueueItems(
                        bundle.plan.planId,
                        MAX_STORED_REVIEW_ITEMS,
                    ) == bundle.items,
            ) {
                "Review plan id was replayed with different immutable content"
            }
            return
        }
        readReviewPlan(
            learnerId = bundle.plan.learnerId,
            localDayEpochDay = bundle.plan.localDayEpochDay,
        )?.let {
            error("A different review plan already exists for this learner and day")
        }
        val referenceRows =
            if (bundle.items.isEmpty()) {
                emptyMap()
            } else {
                val revisionIds = bundle.items.map(StudentReviewQueueItemEntity::basisRevisionId).distinct()
                readRevisionReferenceRows(revisionIds, revisionIds.size)
                    .associateBy(PersistedStudentProblemRevisionRefRow::revisionId)
            }
        bundle.items.forEach { item ->
            val reference = checkNotNull(referenceRows[item.basisRevisionId]) {
                "Review queue basis revision does not exist"
            }
            check(
                reference.learnerId == bundle.plan.learnerId &&
                    reference.practiceUnitId == item.practiceUnitId &&
                    reference.lifecycleState == StudentProblemLifecycleState.ACTIVE.name &&
                    reference.mistakeState == StudentMistakeEntryState.ACTIVE.name,
            ) {
                "Review queue items require active learner-owned mistake entries"
            }
        }
        insertReviewPlan(bundle.plan)
        bundle.items.insertWhenNotEmpty(::insertReviewQueueItems)
        bumpChangeVersion(bundle.plan.learnerId)
    }
}
