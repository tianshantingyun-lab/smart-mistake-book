package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeItem
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeKey
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgePage
import com.tingyun.smartmistakebook.core.domain.LearningMasteryDisplayRepository
import com.tingyun.smartmistakebook.core.domain.LearningMasteryLoadState
import com.tingyun.smartmistakebook.core.domain.LearningMasteryOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryPageRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryProjectionRevision
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubjectOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTimelineRequest
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningMemoryExportTest {
    @Test
    fun exportUsesStudentLanguageAndKeepsCanonicalSubjectOrder() {
        val overview = overview(
            LearningMasterySubjectOverview(
                subject = LearningMasterySubject.MATHEMATICS,
                status = LearningMasteryStatus.FAIRLY_STEADY,
                trend = LearningMasteryTrend.STEADY,
            ),
            LearningMasterySubjectOverview(
                subject = LearningMasterySubject.PHYSICS,
                status = LearningMasteryStatus.NEEDS_REINFORCEMENT,
                trend = LearningMasteryTrend.RECENTLY_FLUCTUATING,
            ),
        )
        val document = buildLearningMemoryExport(
            overview = overview,
            knowledgeBySubject = mapOf(
                LearningMasterySubject.MATHEMATICS to listOf(
                    knowledge("函数单调性", "数学", LearningMasteryStatus.FAIRLY_STEADY),
                ),
                LearningMasterySubject.PHYSICS to listOf(
                    knowledge("受力分析", "物理", LearningMasteryStatus.NEEDS_REINFORCEMENT),
                ),
            ),
            exportedAtEpochMillis = 0L,
        )

        assertTrue(document.text.contains("智能错题本学习记录"))
        assertTrue(document.text.contains("科目：数学"))
        assertTrue(document.text.contains("掌握状态：比较稳"))
        assertTrue(document.text.contains("函数单调性（比较稳）"))
        assertTrue(document.text.contains("科目：物理"))
        assertTrue(document.text.contains("受力分析（需要再巩固）"))
        assertEquals(2, document.subjectCount)
        assertEquals(2, document.knowledgePointCount)
        listOf("FAIRLY_STEADY", "NEEDS_REINFORCEMENT", "学习投影", "置信度", "evidence").forEach { forbidden ->
            assertFalse(forbidden, document.text.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun exportBoundsKnowledgePointsAndReportsOmittedTail() {
        val overview = overview(
            LearningMasterySubjectOverview(
                subject = LearningMasterySubject.MATHEMATICS,
                status = LearningMasteryStatus.GETTING_FAMILIAR,
                trend = LearningMasteryTrend.IMPROVING,
            ),
        )
        val allItems = List(MAX_EXPORT_KNOWLEDGE_POINTS + 1) { index ->
            knowledge(
                "知识点$index",
                "数学",
                LearningMasteryStatus.GETTING_FAMILIAR,
            )
        }

        val document = buildLearningMemoryExport(
            overview = overview,
            knowledgeBySubject = mapOf(LearningMasterySubject.MATHEMATICS to allItems),
            exportedAtEpochMillis = 0L,
        )

        assertEquals(MAX_EXPORT_KNOWLEDGE_POINTS, document.knowledgePointCount)
        assertTrue(document.text.contains("知识点超过导出上限"))
        assertFalse(document.text.contains("知识点${MAX_EXPORT_KNOWLEDGE_POINTS}（"))
    }

    @Test
    fun loaderPagesEveryOverviewSubjectOnce() = runBlocking {
        val overview = overview(
            LearningMasterySubjectOverview(
                subject = LearningMasterySubject.MATHEMATICS,
                status = LearningMasteryStatus.FAIRLY_STEADY,
                trend = LearningMasteryTrend.STEADY,
            ),
            LearningMasterySubjectOverview(
                subject = LearningMasterySubject.CHEMISTRY,
                status = LearningMasteryStatus.NOT_YET_LEARNED,
                trend = LearningMasteryTrend.NO_CLEAR_CHANGE,
            ),
        )
        val repository = FakeLearningMasteryDisplay(
            overview = overview,
            knowledgeBySubject = mapOf(
                LearningMasterySubject.MATHEMATICS to listOf(
                    knowledge("函数单调性", "数学", LearningMasteryStatus.FAIRLY_STEADY),
                ),
                LearningMasterySubject.CHEMISTRY to emptyList(),
            ),
        )

        val result = repository.loadLearningMemoryExport(nowEpochMillis = 0L)
        assertTrue("Expected a ready learning memory export", result is LearningMemoryExportLoadResult.Ready)
        val document = (result as LearningMemoryExportLoadResult.Ready).document

        assertTrue(document.text.contains("科目：数学"))
        assertTrue(document.text.contains("科目：化学"))
        assertTrue(document.text.contains("知识点：暂无"))
        assertEquals(2, document.subjectCount)
        assertEquals(1, document.knowledgePointCount)
    }

    @Test
    fun loaderReturnsUnavailableWhenOverviewIsEmpty() = runBlocking {
        val repository = EmptyLearningMasteryDisplay(
            LearningMasteryProjectionRevision.fromOpaque("empty-revision-v1"),
        )

        val result = repository.loadLearningMemoryExport(nowEpochMillis = 0L)

        assertTrue(result is LearningMemoryExportLoadResult.Unavailable)
    }

    private fun overview(vararg subjects: LearningMasterySubjectOverview) =
        LearningMasteryOverview(
            revision = LearningMasteryProjectionRevision.fromOpaque("export-revision-v1"),
            subjects = subjects.toList(),
        )

    private fun knowledge(
        name: String,
        subject: String,
        status: LearningMasteryStatus,
    ) = LearningMasteryKnowledgeItem(
        key = LearningMasteryKnowledgeKey.fromOpaque("key-${name.hashCode()}"),
        displayName = name,
        displayPath = listOf(subject),
        status = status,
        trend = LearningMasteryTrend.NO_CLEAR_CHANGE,
    )
}

private class EmptyLearningMasteryDisplay(
    private val revision: LearningMasteryProjectionRevision,
) : LearningMasteryDisplayRepository {
    override fun observeSubjectOverview(): Flow<LearningMasteryLoadState<LearningMasteryOverview>> =
        flowOf(LearningMasteryLoadState.Empty(revision))

    override fun observeSubjectTimeline(
        request: LearningMasteryTimelineRequest,
    ): Flow<LearningMasteryLoadState<com.tingyun.smartmistakebook.core.domain.LearningMasteryTimeline>> =
        flowOf(LearningMasteryLoadState.Empty(request.revision))

    override fun observeKnowledgePage(
        request: LearningMasteryPageRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryKnowledgePage>> =
        flowOf(LearningMasteryLoadState.Empty(request.revision))
}

private class FakeLearningMasteryDisplay(
    private val overview: LearningMasteryOverview,
    private val knowledgeBySubject: Map<LearningMasterySubject, List<LearningMasteryKnowledgeItem>>,
) : LearningMasteryDisplayRepository {
    override fun observeSubjectOverview(): Flow<LearningMasteryLoadState<LearningMasteryOverview>> =
        flowOf(LearningMasteryLoadState.Content(overview))

    override fun observeSubjectTimeline(
        request: LearningMasteryTimelineRequest,
    ): Flow<LearningMasteryLoadState<com.tingyun.smartmistakebook.core.domain.LearningMasteryTimeline>> =
        flowOf(LearningMasteryLoadState.Empty(request.revision))

    override fun observeKnowledgePage(
        request: LearningMasteryPageRequest,
    ): Flow<LearningMasteryLoadState<LearningMasteryKnowledgePage>> {
        val items = knowledgeBySubject[request.subject].orEmpty()
        return flowOf(
            if (items.isEmpty()) {
                LearningMasteryLoadState.Empty(request.revision)
            } else {
                LearningMasteryLoadState.Content(
                    LearningMasteryKnowledgePage(
                        subject = request.subject,
                        revision = request.revision,
                        items = items,
                    ),
                )
            },
        )
    }
}
