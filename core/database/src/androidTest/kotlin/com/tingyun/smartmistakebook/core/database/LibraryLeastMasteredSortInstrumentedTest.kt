package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.CalibrationSupport
import com.tingyun.smartmistakebook.core.model.KnowledgeMasteryState
import com.tingyun.smartmistakebook.core.model.LearnerSnapshot
import com.tingyun.smartmistakebook.core.model.LearnerSnapshotFreshness
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.model.ProblemMemoryState
import com.tingyun.smartmistakebook.core.model.ProjectionCheckpoint
import com.tingyun.smartmistakebook.core.model.ProjectionStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `LEAST_MASTERED` catalog sort must order by the learner's *numeric*
 * mastery, weakest first.
 *
 * It used to order by `library_catalog.retrievability`, which the view defines
 * as `NULL AS retrievability` — so every row compared equal and the sort
 * silently fell through to `updated_at DESC`. This is the same class of defect
 * as KD-7: a query reading a column that carries no data, invisible because
 * nothing failed.
 *
 * The fixture makes the assertion decisive rather than incidental: the expected
 * weakness order (`weak`, `middle`, `strong`) is the exact **reverse** of the
 * `updated_at DESC` fallback order, so an implementation that does nothing
 * still produces a total order — just the wrong one.
 */
@RunWith(AndroidJUnit4::class)
class LibraryLeastMasteredSortInstrumentedTest {
    private lateinit var store: RoomStudyDatabase

    @Before
    fun setUp() {
        store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun leastMasteredOrdersByNumericMasteryWeakestFirst() = runBlocking {
        store.seedFixture(catalogSeed())
        store.commitProjection(projectionWithMastery())

        val ordered = store.database.libraryQueryDao().page(
            searchText = "",
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "LEAST_MASTERED",
            offset = 0,
            limit = 10,
        )

        assertEquals(
            listOf("entry-weak", "entry-middle", "entry-strong"),
            ordered.map { it.entryId },
        )
    }

    @Test
    fun leastMasteredOrdersByNumericMasteryOnTheFtsPathToo() = runBlocking {
        // The FTS search path builds its own SQL at runtime and carries its own
        // copy of the expression, so it needs its own behavioral assertion —
        // otherwise a drifted copy would silently sort by nothing again.
        store.seedFixture(catalogSeed())
        store.commitProjection(projectionWithMastery())

        val ordered = store.librarySearchPage(
            matchQuery = CjkTextTokenizer.matchExpression(SEARCH_TERM),
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "LEAST_MASTERED",
            tokens = CjkTextTokenizer.tokens(SEARCH_TERM),
            offset = 0,
            limit = 10,
        )

        assertEquals(
            listOf("entry-weak", "entry-middle", "entry-strong"),
            ordered.map { it.entryId },
        )
    }

    @Test
    fun leastMasteredStillAgreesWithTheMasteryFacetItSorts() = runBlocking {
        // The facet the UI groups by and the sort it orders by must describe
        // the same KC set; a sort that reads a different join would let the two
        // disagree. All three entries here are LEARNING, so the facet cannot
        // distinguish them — the sort must.
        store.seedFixture(catalogSeed())
        store.commitProjection(projectionWithMastery())

        val facets = store.database.libraryQueryDao().masteryFacets(
            searchText = "",
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
        )
        val sorted = store.database.libraryQueryDao().page(
            searchText = "",
            subjectId = null,
            sectionId = null,
            knowledgePointId = null,
            masteryId = null,
            sort = "LEAST_MASTERED",
            offset = 0,
            limit = 10,
        )

        assertEquals(listOf("learning"), facets.map { it.id })
        assertEquals(3, facets.sumOf { it.count })
        assertEquals(3, sorted.size)
    }

    /**
     * Weakest mastery carries the **oldest** `updated_at`, strongest the newest,
     * so the `updated_at DESC` fallback yields `strong, middle, weak`.
     */
    private fun catalogSeed(): StudySeedBundle {
        val nodes = listOf(
            Triple("kc-weak", "entry-weak", 1_000L),
            Triple("kc-middle", "entry-middle", 2_000L),
            Triple("kc-strong", "entry-strong", 3_000L),
        )
        return StudySeedBundle(
            problems = nodes.map { (node, entry, stamp) ->
                ProblemSeedRecord("problem-$node", "fp-$node", "MATH", stamp)
            },
            revisions = nodes.map { (node, entry, stamp) ->
                ProblemRevisionSeedRecord(
                    revisionId = "revision-$node",
                    problemId = "problem-$node",
                    revisionNumber = 1,
                    title = "题 $entry",
                    problemMarkdown = "题面 $entry",
                    answerSpecId = null,
                    answerSpecSnapshot = null,
                    answerVerificationStatus = StudyDbValue.VerificationStatus.UNKNOWN,
                    sourceType = "CAPTURE_CONFIRMED",
                    sourceReference = null,
                    contentFingerprint = "f".repeat(64),
                    createdAtEpochMillis = stamp,
                )
            },
            practiceUnits = nodes.map { (node, entry, stamp) ->
                PracticeUnitSeedRecord(
                    practiceUnitId = "unit-$node",
                    problemId = "problem-$node",
                    problemRevisionId = "revision-$node",
                    unitKey = "whole-problem",
                    unitKind = "WHOLE_PROBLEM",
                    title = "题 $entry",
                    promptMarkdown = "题面 $entry",
                    estimatedSeconds = 180,
                    createdAtEpochMillis = stamp,
                )
            },
            errorBookEntries = nodes.map { (node, entry, stamp) ->
                ErrorBookEntrySeedRecord(
                    entryId = entry,
                    practiceUnitId = "unit-$node",
                    problemId = "problem-$node",
                    currentRevisionId = "revision-$node",
                    sourceKey = null,
                    acceptedAtEpochMillis = stamp,
                    updatedAtEpochMillis = stamp,
                )
            },
            knowledgeNodes = nodes.map { (node, _, stamp) ->
                KnowledgeNodeSeedRecord(
                    knowledgeNodeId = node,
                    stableCode = "math.test.$node",
                    subject = "MATH",
                    displayName = node,
                    parentKnowledgeNodeId = null,
                    taxonomyVersion = TAXONOMY_VERSION,
                    createdAtEpochMillis = stamp,
                )
            },
            knowledgeBindings = nodes.map { (node, _, stamp) ->
                KnowledgeBindingSeedRecord(
                    bindingId = "binding-$node",
                    practiceUnitId = "unit-$node",
                    knowledgeNodeId = node,
                    basisRevisionId = "revision-$node",
                    strength = 1.0,
                    sourceType = "VERIFIED",
                    taxonomyVersion = TAXONOMY_VERSION,
                    acceptedAtEpochMillis = stamp,
                )
            },
        )
    }

    /**
     * Mastery rows come from a real projection commit, not `seedFixture`: the
     * fixture writes the legacy `knowledge_mastery_state` table, while the
     * catalog reads `learner_knowledge_mastery_state`. Seeding the wrong table
     * is exactly how KD-7 hid. A memory row is required too — the view resolves
     * the learner from `learner_problem_memory_state`.
     */
    private fun projectionWithMastery() = ProjectionCommit(
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
            problemMemoryStates = MASTERY.keys.associate { node ->
                "unit-$node" to ProblemMemoryState(
                    practiceUnitId = "unit-$node",
                    stabilityDays = 3.0,
                    difficulty = 5.0,
                    lastReviewedAtEpochMillis = 1_000,
                    nextReviewAtEpochMillis = 2_000,
                    lastAttemptId = "attempt-$node",
                    projectorVersion = PROJECTOR_VERSION,
                    checkpointSequence = 0,
                )
            },
            knowledgeMasteryStates = MASTERY.mapValues { (node, lowerBound) ->
                KnowledgeMasteryState(
                    knowledgeNodeId = node,
                    masteryScore = (lowerBound + 0.1).coerceAtMost(1.0),
                    conservativeMasteryScore = lowerBound,
                    evidenceMass = 1.0,
                    status = MasteryStatus.LEARNING,
                    calibrationSupport = CalibrationSupport.SUPPORTED,
                    projectorVersion = PROJECTOR_VERSION,
                    checkpointSequence = 0,
                )
            },
            checkpoint = ProjectionCheckpoint(
                lastSequence = 0,
                projectorVersion = PROJECTOR_VERSION,
                projectedAtEpochMillis = 1_000,
            ),
            generatedAtEpochMillis = 1_000,
            freshness = LearnerSnapshotFreshness.CURRENT,
            projectionStatus = ProjectionStatus.CURRENT,
        ),
    )

    private companion object {
        const val LEARNER = "learner-sort-test"
        const val PROJECTION = "study-experience-v1"
        const val PROJECTOR_VERSION = "learning-core-v7"
        const val TAXONOMY_VERSION = "taxonomy-sort-v1"

        /**
         * Present in every seeded problem's markdown, so the FTS path returns
         * all three rows and the sort is what decides their order.
         */
        const val SEARCH_TERM = "题面"

        /** Conservative mastery per knowledge node; deliberately distinct. */
        val MASTERY = linkedMapOf(
            "kc-weak" to 0.20,
            "kc-middle" to 0.50,
            "kc-strong" to 0.80,
        )
    }
}
