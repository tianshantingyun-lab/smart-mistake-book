package com.tingyun.smartmistakebook.core.data.study

import com.tingyun.smartmistakebook.core.database.MistakeRecord
import com.tingyun.smartmistakebook.core.domain.StudyReviewRating
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmission
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused coverage for the four-button rating channel (spec mastery-scheduling
 * §2.21) and the subjective-report cooldown (spec §2.7).
 */
class RoomBackedReviewRatingTest {

    @Test
    fun easyRatingMapsToRatingEvidenceAndReviewLog() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(ratingMistake())
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("rating-start", 2_000),
            )

            val result = repository.submitReviewRating(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = StudyReviewRatingSubmission(
                    requestId = "rating-request-1",
                    presentationId = "presentation:rating:1",
                    practiceUnitId = "rating-practice-unit",
                    rating = StudyReviewRating.EASY,
                    durationSeconds = 30,
                    occurredAtEpochMillis = 3_000,
                ),
            )

            assertTrue(result.created)
            assertEquals(LearningEvidenceReason.SELF_REPORTED_RECALL, result.evidenceReason)
            // The rating evidence maps to FSRS grade 4 (Easy) in review_log.
            val sample = database.reviewLogEntries.single()
            assertEquals(4, sample.rating)
            assertTrue(sample.schedulingEligible)
            assertEquals("SELF_REPORT", sample.sourceKind)
            assertEquals(ReviewLogRatingAssertionHolder.RATING_WEIGHT_EASY, sample.evidenceWeight, 1e-9)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun againRatingRecordsRetrievalFailure() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(ratingMistake())
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("rating-start-again", 2_000),
            )

            val result = repository.submitReviewRating(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = StudyReviewRatingSubmission(
                    requestId = "rating-request-2",
                    presentationId = "presentation:rating:2",
                    practiceUnitId = "rating-practice-unit",
                    rating = StudyReviewRating.AGAIN,
                    durationSeconds = 15,
                    occurredAtEpochMillis = 3_000,
                ),
            )

            assertTrue(result.created)
            assertEquals(LearningEvidenceReason.SELF_REPORTED_STUCK, result.evidenceReason)
            assertEquals(1, database.reviewLogEntries.single().rating)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    @Test
    fun secondRatingInsideCooldownIsObservationOnly() = runBlocking {
        val database = FakeStudyDatabasePort().apply {
            addMistake(ratingMistake())
        }
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = repository(database, applicationScope)

        try {
            repository.initialize()
            val started = requireNotNull(
                repository.startOrResumeReviewSession("rating-start-cd", 2_000),
            )
            val first = repository.submitReviewRating(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = StudyReviewRatingSubmission(
                    requestId = "rating-request-3",
                    presentationId = "presentation:rating:3",
                    practiceUnitId = "rating-practice-unit",
                    rating = StudyReviewRating.GOOD,
                    durationSeconds = 10,
                    occurredAtEpochMillis = 3_000,
                ),
            )
            val completed = database.latestSessions.values
                .single { it.reviewSessionId == started.sessionId }
            database.latestSessions[completed.reviewPlanId] = completed.copy(
                currentOrdinal = 0,
                stateVersion = 0,
                status = com.tingyun.smartmistakebook.core.database.StudyDbValue.ReviewStatus.IN_PROGRESS,
            )

            val second = repository.submitReviewRating(
                sessionId = started.sessionId,
                expectedStateVersion = 0,
                submission = StudyReviewRatingSubmission(
                    requestId = "rating-request-4",
                    presentationId = "presentation:rating:3",
                    practiceUnitId = "rating-practice-unit",
                    rating = StudyReviewRating.GOOD,
                    durationSeconds = 10,
                    occurredAtEpochMillis = 3_000 + 60_000,
                ),
            )

            assertFalse(first.evidenceSuppressedByCooldown)
            assertTrue(second.evidenceSuppressedByCooldown)
            assertEquals(2, database.reviewLogEntries.size)
            assertFalse(database.reviewLogEntries.last().schedulingEligible)
            // The suppressed report never produced a ledger attempt.
            assertEquals(1, database.recordedAttemptCount)
        } finally {
            repository.close()
            applicationScope.cancel()
        }
    }

    private fun repository(
        database: FakeStudyDatabasePort,
        applicationScope: CoroutineScope,
    ): RoomBackedStudyExperienceRepository = RoomBackedStudyExperienceRepository(
        database = database,
        applicationScope = applicationScope,
        initialFixture = null,
    )

    private fun ratingMistake() = MistakeRecord(
        entryId = "rating-entry",
        problemId = "rating-problem",
        problemRevisionId = "rating-revision",
        practiceUnitId = "rating-practice-unit",
        sourceKey = "capture:rating-photo",
        subject = "MATH",
        title = "函数原题",
        problemMarkdown = "求函数的单调区间。",
        status = "ACTIVE",
        createdAtEpochMillis = 1_000,
        nextReviewAtEpochMillis = null,
        retrievability = null,
        knowledgeNodeIds = setOf("knowledge:function-monotonicity"),
    )

    /** Keeps magic numbers out of assertions while staying literal in one place. */
    private object ReviewLogRatingAssertionHolder {
        const val RATING_WEIGHT_EASY = 0.9
    }
}
