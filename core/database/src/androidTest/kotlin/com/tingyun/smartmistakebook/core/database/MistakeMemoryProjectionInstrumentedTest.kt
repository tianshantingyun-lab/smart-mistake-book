package com.tingyun.smartmistakebook.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The mistake-catalog reads must take their per-question memory facts from the
 * learner projection, the only table the projector writes.
 *
 * These reads used to join `problem_memory_state`, the pre-projection
 * denormalized table that only `FixtureSeedDao` writes. Fixture seeding is the
 * only reason instrumented tests ever saw memory values there, so the join
 * looked healthy on device while every production row returned NULL — the same
 * split the library_catalog view already closed in migration 35 -> 36. The
 * first two tests fail on that regression; the third pins the LEFT JOIN so a
 * future change cannot silently drop unprojected entries from the catalog.
 */
@RunWith(AndroidJUnit4::class)
class MistakeMemoryProjectionInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() {
        runBlocking {
            store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
            store.seedFixture(baseSeed())
        }
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun observeMistakesExposesProjectionMemoryState() = runBlocking {
        store.commitProjection(projectionCommit(nextReviewAtEpochMillis = NEXT_REVIEW_AT))

        val observed = store.observeMistakes().first().single { it.entryId == ENTRY_ID }

        assertEquals(NEXT_REVIEW_AT, observed.nextReviewAtEpochMillis)
    }

    @Test
    fun findMistakeBySourceKeyExposesProjectionMemoryState() = runBlocking {
        store.commitProjection(projectionCommit(nextReviewAtEpochMillis = NEXT_REVIEW_AT))

        val found = store.findMistakeBySourceKey(SOURCE_KEY)

        assertNotNull(found)
        assertEquals(NEXT_REVIEW_AT, found?.nextReviewAtEpochMillis)
    }

    @Test
    fun activeEntryWithoutProjectionMemoryKeepsItsRow() = runBlocking {
        val observed = store.observeMistakes().first().single { it.entryId == ENTRY_ID }

        assertNull(observed.nextReviewAtEpochMillis)
    }

    private fun projectionCommit(nextReviewAtEpochMillis: Long) = ProjectionCommit(
        projectionName = PROJECTION,
        learnerId = LEARNER,
        expectedPreviousCheckpoint = 0,
        expectedPreviousStateVersion = 0,
        mode = ProjectionCommitMode.FULL_REPLAY,
        knownLedgerHeadSequence = 0,
        consumedLedgerEvents = emptyList(),
        presentationProjectionStates = emptyMap(),
        snapshot = LearnerSnapshot(
            learnerId = LEARNER,
            problemMemoryStates = mapOf(
                UNIT_ID to ProblemMemoryState(
                    practiceUnitId = UNIT_ID,
                    stabilityDays = 3.0,
                    difficulty = 5.0,
                    lastReviewedAtEpochMillis = LAST_REVIEWED_AT,
                    nextReviewAtEpochMillis = nextReviewAtEpochMillis,
                    lastAttemptId = "attempt-1",
                    projectorVersion = PROJECTOR_VERSION,
                    checkpointSequence = 0,
                ),
            ),
            checkpoint = ProjectionCheckpoint(
                lastSequence = 0,
                projectorVersion = PROJECTOR_VERSION,
                projectedAtEpochMillis = PROJECTED_AT,
            ),
            generatedAtEpochMillis = PROJECTED_AT,
            freshness = LearnerSnapshotFreshness.CURRENT,
            projectionStatus = ProjectionStatus.CURRENT,
        ),
    )

    private fun baseSeed() = StudySeedBundle(
        problems = listOf(
            ProblemSeedRecord(PROBLEM_ID, "problem-fingerprint", "MATH", 1_000),
        ),
        revisions = listOf(
            ProblemRevisionSeedRecord(
                revisionId = REVISION_ID,
                problemId = PROBLEM_ID,
                revisionNumber = 1,
                title = "函数单调性",
                problemMarkdown = "求函数的单调区间。",
                answerSpecId = "answer-spec-1",
                answerSpecSnapshot = "增区间与减区间",
                answerVerificationStatus = StudyDbValue.VerificationStatus.VERIFIED,
                sourceType = "IMPORT",
                sourceReference = null,
                contentFingerprint = "revision-fingerprint",
                createdAtEpochMillis = 2_000,
            ),
        ),
        practiceUnits = listOf(
            PracticeUnitSeedRecord(
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                problemRevisionId = REVISION_ID,
                unitKey = "whole",
                unitKind = "WHOLE",
                title = "函数单调性",
                promptMarkdown = "求单调区间。",
                estimatedSeconds = 120,
                createdAtEpochMillis = 3_000,
            ),
        ),
        errorBookEntries = listOf(
            ErrorBookEntrySeedRecord(
                entryId = ENTRY_ID,
                practiceUnitId = UNIT_ID,
                problemId = PROBLEM_ID,
                currentRevisionId = REVISION_ID,
                sourceKey = SOURCE_KEY,
                acceptedAtEpochMillis = 4_000,
                updatedAtEpochMillis = 4_000,
            ),
        ),
    )

    private companion object {
        /**
         * Not the production `learner:local` identity on purpose: the catalog
         * reads must resolve the learner from the projection row itself, the
         * way the library_catalog view does, instead of hardcoding one.
         */
        const val LEARNER = "learner-test"
        const val PROJECTION = "study-experience-v1"
        const val PROJECTOR_VERSION = "learning-core-v6"
        const val PROBLEM_ID = "problem-1"
        const val REVISION_ID = "revision-1"
        const val UNIT_ID = "unit-1"
        const val ENTRY_ID = "entry-1"
        const val SOURCE_KEY = "source-1"
        const val LAST_REVIEWED_AT = 1_700_000_000_000L
        const val NEXT_REVIEW_AT = 1_700_086_400_000L
        const val PROJECTED_AT = 1_700_086_400_001L
    }
}
