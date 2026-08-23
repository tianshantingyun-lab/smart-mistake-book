package com.tingyun.smartmistakebook.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchReviewFingerprint
import com.tingyun.smartmistakebook.core.model.KnowledgeResearchSourceFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class KnowledgeResearchReviewInstrumentedTest {
    @Test
    fun pendingBundleSurvivesExactReplayAndRejectsPayloadConflict() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = StudyDatabaseFactory.openInMemory(context)
        val bundle = bundle()

        database.enqueueKnowledgeResearchReviewBundle(bundle)
        database.enqueueKnowledgeResearchReviewBundle(bundle)

        assertEquals(listOf(bundle), database.readPendingKnowledgeResearchReviewBundles(limit = 256))
        assertTrue(
            runCatching {
                database.enqueueKnowledgeResearchReviewBundle(
                    bundle.copy(relatedQuestionCount = bundle.relatedQuestionCount + 1),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
        database.close()
    }

    @Test
    fun reviewDecisionIsAtomicIdempotentAndCannotBeRewritten() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = StudyDatabaseFactory.openInMemory(context)
        val bundle = bundle()
        val approval = DecideKnowledgeResearchReviewBundleCommand(
            bundleId = bundle.bundleId,
            decisionStatus = StudyDbValue.KnowledgeResearchReviewStatus.APPROVED,
            reviewerReference = "reviewer:curriculum-team",
            decisionNote = "来源、边界和学习顺序已复核。",
            decidedAtEpochMillis = 30L,
        )
        database.enqueueKnowledgeResearchReviewBundle(bundle)

        val decided = database.decideKnowledgeResearchReviewBundle(approval)

        assertEquals(StudyDbValue.KnowledgeResearchReviewStatus.APPROVED, decided.status)
        assertEquals(approval.reviewerReference, decided.reviewerReference)
        assertEquals(approval.decisionNote, decided.decisionNote)
        assertEquals(approval.decidedAtEpochMillis, decided.reviewedAtEpochMillis)
        assertTrue(database.readPendingKnowledgeResearchReviewBundles(limit = 256).isEmpty())
        assertEquals(decided, database.decideKnowledgeResearchReviewBundle(approval))
        assertTrue(
            runCatching {
                database.enqueueKnowledgeResearchReviewBundle(
                    bundle.copy(
                        relatedQuestionCount = bundle.relatedQuestionCount + 1,
                        createdAtEpochMillis = 40L,
                        updatedAtEpochMillis = 40L,
                    ),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
        assertTrue(database.readPendingKnowledgeResearchReviewBundles(limit = 256).isEmpty())
        assertTrue(
            runCatching {
                database.decideKnowledgeResearchReviewBundle(
                    approval.copy(
                        decisionStatus = StudyDbValue.KnowledgeResearchReviewStatus.REJECTED,
                        decisionNote = "试图覆盖已有决定",
                    ),
                )
            }.exceptionOrNull() is ImmutablePayloadConflictException,
        )
        database.close()
    }

    @Test
    fun versionTwentyFourAddsAnEmptyDurableResearchReviewQueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "knowledge-research-review-v24-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        try {
            createDatabaseFromExportedSchema(context, databaseName, version = 24)

            val migrated = StudyDatabaseFactory.open(context, databaseName)
            assertTrue(migrated.readPendingKnowledgeResearchReviewBundles(limit = 256).isEmpty())
            migrated.close()

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(databaseName).path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                assertEquals(STUDY_DATABASE_VERSION, database.version)
                database.rawQuery(
                    "SELECT COUNT(*) FROM knowledge_research_review_bundle",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun maximumPendingPageUsesTwoBoundedQueriesWithinTheReadBudget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = StudyDatabaseFactory.openInMemory(context)
        repeat(MAX_PENDING_PAGE) { index ->
            database.enqueueKnowledgeResearchReviewBundle(
                bundle(index = index, sourceCount = MAX_SOURCES_PER_BUNDLE),
            )
        }
        database.readPendingKnowledgeResearchReviewBundles(MAX_PENDING_PAGE)

        val durations = List(10) {
            var read = emptyList<KnowledgeResearchReviewBundleRecord>()
            val duration = measureNanoTime {
                read = database.readPendingKnowledgeResearchReviewBundles(MAX_PENDING_PAGE)
            }
            assertEquals(MAX_PENDING_PAGE, read.size)
            assertTrue(read.all { it.sources.size == MAX_SOURCES_PER_BUNDLE })
            duration / 1_000_000
        }.sorted()
        val p95 = durations[((durations.size - 1) * 95) / 100]

        println("knowledgeResearchReviewReadMillis=$durations p95=$p95")
        assertTrue("p95=$p95 ms exceeded $READ_P95_BUDGET_MS ms", p95 < READ_P95_BUDGET_MS)
        database.close()
    }

    private fun bundle(
        index: Int = 0,
        sourceCount: Int = 1,
    ): KnowledgeResearchReviewBundleRecord {
        val query = "导数与单调性的局部判定 $index"
        val expectedParent = "导数"
        val groundingKey = KnowledgeGroundingFingerprint.of(
            subject = SubjectKind.MATH,
            expectedParentKnowledgeDisplayName = expectedParent,
            query = query,
        )
        val sources = List(sourceCount) { sourceIndex ->
            KnowledgeResearchReviewSourceRecord(
                sourceOrdinal = sourceIndex,
                canonicalSourceUri = "https://source$index.example/document-$sourceIndex.pdf",
                title = "课程资料 $index-$sourceIndex",
                publisher = "资料发布方",
                sourceType = "OFFICIAL_CURRICULUM_STANDARD",
                licenseStatus = "PUBLIC_OFFICIAL",
                searchRank = sourceIndex,
                contentType = "application/pdf",
                contentLengthBytes = 1_024L,
                contentFingerprint = sha256("content-$index-$sourceIndex"),
                verifiedAtEpochMillis = 10L,
            )
        }
        val bundleId = KnowledgeResearchReviewFingerprint.of(
            groundingKey = groundingKey,
            subject = "MATH",
            sources = sources.map { source ->
                KnowledgeResearchSourceFingerprint(
                    canonicalSourceUri = source.canonicalSourceUri,
                    contentFingerprint = source.contentFingerprint,
                )
            },
        )
        return KnowledgeResearchReviewBundleRecord(
            bundleId = bundleId,
            groundingKey = groundingKey,
            subject = "MATH",
            query = query,
            expectedParentKnowledgeDisplayName = expectedParent,
            relatedQuestionCount = 3,
            workflowVersion = "knowledge-research-v1",
            createdAtEpochMillis = 20L + index,
            updatedAtEpochMillis = 20L + index,
            sources = sources,
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_PENDING_PAGE = 256
        const val MAX_SOURCES_PER_BUNDLE = 4
        const val READ_P95_BUDGET_MS = 250L
    }
}
