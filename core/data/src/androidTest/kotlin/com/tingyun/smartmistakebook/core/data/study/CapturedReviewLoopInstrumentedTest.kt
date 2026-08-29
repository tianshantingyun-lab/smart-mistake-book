package com.tingyun.smartmistakebook.core.data.study

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.database.ErrorBookEntrySeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemRevisionSeedRecord
import com.tingyun.smartmistakebook.core.database.ProblemSeedRecord
import com.tingyun.smartmistakebook.core.database.PracticeUnitSeedRecord
import com.tingyun.smartmistakebook.core.database.StudyDatabaseFactory
import com.tingyun.smartmistakebook.core.database.StudySeedBundle
import com.tingyun.smartmistakebook.core.domain.FsrsScheduleMath
import com.tingyun.smartmistakebook.core.domain.StudyReviewRating
import com.tingyun.smartmistakebook.core.domain.StudyReviewRatingSubmission
import com.tingyun.smartmistakebook.core.model.LearningEvidenceReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-algorithm loop on a real Room database (spec mastery-scheduling):
 * a captured-and-saved question (no curated fixtures, pseudo-KC binding path)
 * goes through the real review session, the real FSRS-6 projection, the real
 * review-log collection, and the real lattice read - the exact code path a
 * student's device runs.
 */
@RunWith(AndroidJUnit4::class)
class CapturedReviewLoopInstrumentedTest {

    private val learnerId = "learner:local"

    @Test
    fun capturedQuestionRunsTheFullFsrsLoopOnDevice() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "prod-loop-\${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val roomStudyDatabase: com.tingyun.smartmistakebook.core.database.StudyDatabasePort =
            StudyDatabaseFactory.open(context, databaseName)
        val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = RoomBackedStudyExperienceRepository(
            database = roomStudyDatabase,
            applicationScope = applicationScope,
            initialFixture = null,
        )

        try {
            // A captured question saved to the library (capture/commit path
            // outside algorithm scope): active entry, revision chain, unit,
            // and NO knowledge bindings - the pseudo-KC fallback applies.
            roomStudyDatabase.seedFixture(capturedShapedSeed())
            repository.initialize()

            assertTrue(
                repository.snapshot.value.review.scheduledPracticeUnitIds
                    .contains("captured-unit"),
            )

            val started = requireNotNull(
                repository.startOrResumeReviewSession("prod-loop-start", 2_000),
            )
            val result = repository.submitReviewRating(
                sessionId = started.sessionId,
                expectedStateVersion = started.stateVersion,
                submission = StudyReviewRatingSubmission(
                    requestId = "prod-rating-1",
                    presentationId = "presentation:prod:1",
                    practiceUnitId = "captured-unit",
                    rating = StudyReviewRating.GOOD,
                    durationSeconds = 42,
                    occurredAtEpochMillis = 3_000,
                ),
            )

            // 1. Rating accepted; grading per the four-key mapping.
            assertTrue(result.created)
            assertEquals(LearningEvidenceReason.SELF_REPORTED_RECALL, result.evidenceReason)

            // 2. FSRS-6 projection on device: first GOOD review seeds w[2]
            //    stability (2.3065) visible through the lattice view.
            val ledger = roomStudyDatabase.loadLearningLedger(learnerId)
            val ledgerHead = ledger.validPrefix.size
            val batch = roomStudyDatabase.loadProjectionBatch(
                "study-experience-v1",
                learnerId,
                100,
            )
            val ledgerKinds = ledger.validPrefix.map { it.event.ledgerEventId to it.event.eventSequence }
            val persisted = roomStudyDatabase.readCurrentLearnerSnapshot(
                "study-experience-v1",
                learnerId,
            )
            // The submission publishes its own drain; an explicit refresh
            // makes the synchronous expectation explicit for the assertion.
            repository.refresh()
            val snapshotNow = repository.snapshot.value
            val memory = requireNotNull(
                snapshotNow.catalog
                    .single { it.practiceUnitId == "captured-unit" }
                    .questionMemory,
            ) {
                "memory missing: status=${snapshotNow.status} " +
                    "failure=${snapshotNow.failureMessage} " +
                    "ledgerHead=$ledgerHead kinds=$ledgerKinds " +
                    "batchStop=${batch.stopReason} batchDetail=${batch.detail} " +
                    "batchEvents=${batch.events.map { it.event.ledgerEventId }} " +
                    "batchPrev=${batch.previousCheckpoint} " +
                    "persistedCheckpoint=${persisted?.snapshot?.checkpoint} " +
                    "persistedMemories=${persisted?.snapshot?.problemMemoryStates?.keys} " +
                    "projectionCurrent=${snapshotNow.profile.projectionIsCurrent} " +
                    "memories=${snapshotNow.catalog.map { it.practiceUnitId to it.questionMemory }}"
            }
            assertEquals(1, memory.assistedRecallCount)
            assertTrue(memory.nextReviewAtEpochMillis > 3_000)

            val latticeRows = roomStudyDatabase
                .observeKnowledgeQuestionLattice(learnerId)
                .first()
            assertTrue(latticeRows.isNotEmpty())
            val lattice = latticeRows.first {
                it.practiceUnitId == "captured-unit"
            }
            assertEquals("pseudo:MATH", lattice.knowledgeNodeId)
            assertEquals(
                FsrsScheduleMath.initialStability(com.tingyun.smartmistakebook.core.domain.FsrsRating.GOOD),
                requireNotNull(lattice.questionStabilityDays),
                1e-6,
            )

            // 3. Review-log collection: one scheduling-eligible row with the
            //    reported grade and a time bucket.
            val samples = roomStudyDatabase.readReviewLogSamples(learnerId, limit = 10)
            assertEquals(1, samples.size)
            assertEquals(3, samples.single().rating)
            assertEquals("SELF_REPORT", samples.single().sourceKind)
            assertEquals(42_000L, samples.single().durationMs)
        } finally {
            repository.close()
            applicationScope.cancel()
            context.deleteDatabase(databaseName)
        }
    }

    private fun capturedShapedSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(
                problemId = "captured-problem",
                canonicalFingerprint = "fp-captured",
                subject = "MATH",
                createdAtEpochMillis = 0L,
            ),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = "captured-revision",
                problemId = "captured-problem",
                revisionNumber = 1,
                title = "拍摄保存的题",
                problemMarkdown = "解方程。",
                answerSpecId = null,
                answerSpecSnapshot = null,
                answerVerificationStatus = "USER_ASSERTED",
                sourceType = "CAPTURE",
                sourceReference = null,
                contentFingerprint = "fp-captured-r1",
                createdAtEpochMillis = 0L,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = "captured-unit",
                problemId = "captured-problem",
                problemRevisionId = "captured-revision",
                unitKey = "captured-unit",
                unitKind = "SINGLE",
                title = "拍摄保存的题",
                promptMarkdown = "解方程。",
                estimatedSeconds = 60,
                createdAtEpochMillis = 0L,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = "captured-entry",
                practiceUnitId = "captured-unit",
                problemId = "captured-problem",
                currentRevisionId = "captured-revision",
                sourceKey = "capture:prod-photo",
                status = "ACTIVE",
                acceptedAtEpochMillis = 0L,
                updatedAtEpochMillis = 0L,
            ),
        ),
    )
}
