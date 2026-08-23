package com.tingyun.smartmistakebook.core.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tingyun.smartmistakebook.core.model.KnowledgeGroundingFingerprint
import com.tingyun.smartmistakebook.core.model.SubjectKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KnowledgeGroundingQueueInstrumentedTest {
    @Test
    fun pendingSummariesGroupRepeatedQuestionsByStableGap() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            store.recordKnowledgeGroundingRequests(
                listOf(
                    request(),
                    request(
                        organizationRequestId = "organization-request:2",
                        problemOrdinal = 2,
                        createdAtEpochMillis = 2_000,
                    ),
                ),
            )

            val summary = store.observePendingKnowledgeGroundingSummaries(limit = 256).first().single()
            assertEquals(request().groundingKey, summary.groundingKey)
            assertEquals(SubjectKind.MATH.name, summary.subject)
            assertEquals(2, summary.relatedQuestionCount)
            assertEquals(1_000, summary.firstObservedAtEpochMillis)
            assertEquals(2_000, summary.lastObservedAtEpochMillis)
        } finally {
            store.close()
        }
    }

    @Test
    fun replayIsIdempotentAndConflictingPayloadIsRejected() = runBlocking {
        val store = StudyDatabaseFactory.openInMemory(ApplicationProvider.getApplicationContext())
        try {
            val request = request()
            store.recordKnowledgeGroundingRequests(listOf(request))
            store.recordKnowledgeGroundingRequests(listOf(request))

            assertEquals(listOf(request), store.observePendingKnowledgeGroundingRequests(limit = 512).first())

            val conflict = request.copy(
                reasonMarkdown = "同一模型请求不允许改写已经排队的理由。",
            )
            val thrown = runCatching {
                store.recordKnowledgeGroundingRequests(listOf(conflict))
            }.exceptionOrNull()
            assertTrue(thrown is ImmutablePayloadConflictException)
            assertEquals(listOf(request), store.observePendingKnowledgeGroundingRequests(limit = 512).first())
        } finally {
            store.close()
        }
    }

    private fun request(
        organizationRequestId: String = "organization-request:1",
        problemOrdinal: Int = 1,
        createdAtEpochMillis: Long = 1_000,
    ): KnowledgeGroundingRequestRecord {
        val parent = "函数性质"
        val query = "高中数学 导数符号 单调性"
        val groundingKey = KnowledgeGroundingFingerprint.of(SubjectKind.MATH, parent, query)
        return KnowledgeGroundingRequestRecord(
            groundingRequestId = KnowledgeGroundingFingerprint.occurrenceId(
                organizationRequestId,
                0,
                groundingKey,
            ),
            groundingKey = groundingKey,
            organizationRequestId = organizationRequestId,
            organizationRequestFingerprint = problemOrdinal.toString().repeat(64).take(64),
            requestOrdinal = 0,
            problemId = "problem-$problemOrdinal",
            problemRevisionId = "revision-$problemOrdinal",
            practiceUnitId = "unit-$problemOrdinal",
            subject = SubjectKind.MATH.name,
            query = query,
            expectedParentKnowledgeDisplayName = parent,
            reasonMarkdown = "现有本体无法可靠归因到原子知识。",
            createdAtEpochMillis = createdAtEpochMillis,
            updatedAtEpochMillis = createdAtEpochMillis,
        )
    }
}
