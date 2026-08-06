package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeItem
import com.tingyun.smartmistakebook.core.domain.LearningMasteryKnowledgeKey
import com.tingyun.smartmistakebook.core.domain.LearningMasteryStatus
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubject
import com.tingyun.smartmistakebook.core.domain.LearningMasterySubjectOverview
import com.tingyun.smartmistakebook.core.domain.LearningMasteryTrend
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningMasteryPresentationTest {
    @Test
    fun `weak knowledge is not repeated in the regular list`() {
        val weak = knowledge("weak", LearningMasteryStatus.NEEDS_REINFORCEMENT)
        val regular = knowledge("regular", LearningMasteryStatus.GETTING_FAMILIAR)

        val presentation = learningMasteryKnowledgePresentation(
            listOf(weak, regular, weak),
        )

        assertEquals(listOf("weak"), presentation.weakItems.map { it.key.opaqueValue })
        assertEquals(listOf("regular"), presentation.regularItems.map { it.key.opaqueValue })
        val presentedKeys =
            (presentation.weakItems + presentation.regularItems).map { it.key }
        assertEquals(presentedKeys.distinct(), presentedKeys)
    }

    @Test
    fun `weak summary limit keeps remaining knowledge visible once`() {
        val weakItems = (1..5).map { index ->
            knowledge("weak-$index", LearningMasteryStatus.NEEDS_REINFORCEMENT)
        }

        val presentation = learningMasteryKnowledgePresentation(weakItems)

        assertEquals(4, presentation.weakItems.size)
        assertEquals(listOf("weak-5"), presentation.regularItems.map { it.key.opaqueValue })
    }

    @Test
    fun `status distribution counts each student facing band once`() {
        val subjects =
            listOf(
                subject(
                    LearningMasterySubject.MATHEMATICS,
                    LearningMasteryStatus.NEEDS_REINFORCEMENT,
                ),
                subject(
                    LearningMasterySubject.PHYSICS,
                    LearningMasteryStatus.FAIRLY_STEADY,
                ),
                subject(
                    LearningMasterySubject.CHEMISTRY,
                    LearningMasteryStatus.GETTING_FAMILIAR,
                ),
                subject(
                    LearningMasterySubject.BIOLOGY,
                    LearningMasteryStatus.NOT_YET_LEARNED,
                ),
                subject(
                    LearningMasterySubject.ENGLISH,
                    LearningMasteryStatus.NOT_YET_LEARNED,
                ),
            )

        val distribution = learningMasteryStatusDistribution(subjects)

        assertEquals(1, distribution.needsReinforcement)
        assertEquals(1, distribution.gettingFamiliar)
        assertEquals(1, distribution.fairlySteady)
        assertEquals(2, distribution.notYetLearned)
        assertEquals(5, distribution.total)
    }

    @Test
    fun `section progress groups loaded knowledge by board`() {
        val items =
            listOf(
                knowledge(
                    "a",
                    LearningMasteryStatus.NEEDS_REINFORCEMENT,
                    path = listOf("数学", "函数"),
                ),
                knowledge(
                    "b",
                    LearningMasteryStatus.GETTING_FAMILIAR,
                    path = listOf("数学", "函数"),
                ),
                knowledge(
                    "c",
                    LearningMasteryStatus.FAIRLY_STEADY,
                    path = listOf("数学", "导数"),
                ),
            )

        val progress =
            learningMasterySectionProgress(
                items = items,
                subject = LearningMasterySubject.MATHEMATICS,
            )

        assertEquals(listOf("函数", "导数"), progress.map { it.section })
        val functions = progress.first { it.section == "函数" }
        assertEquals(2, functions.total)
        assertEquals(1, functions.needsReinforcement)
        assertEquals(1, functions.familiarizing)
        assertEquals(0, functions.steady)
    }

    @Test
    fun `section filter keeps only the selected board and names are distinct`() {
        val items =
            listOf(
                knowledge(
                    "a",
                    LearningMasteryStatus.NEEDS_REINFORCEMENT,
                    path = listOf("数学", "函数"),
                ),
                knowledge(
                    "b",
                    LearningMasteryStatus.GETTING_FAMILIAR,
                    path = listOf("数学", "导数"),
                ),
                knowledge(
                    "c",
                    LearningMasteryStatus.FAIRLY_STEADY,
                    path = listOf("数学", "函数"),
                ),
                knowledge(
                    "d",
                    LearningMasteryStatus.FAIRLY_STEADY,
                    path = listOf("数学"),
                ),
            )

        val names =
            learningMasterySectionNames(
                items = items,
                subject = LearningMasterySubject.MATHEMATICS,
            )
        val filtered =
            learningMasterySectionItems(
                items = items,
                subject = LearningMasterySubject.MATHEMATICS,
                selectedSection = "函数",
            )
        val other =
            learningMasterySectionItems(
                items = items,
                subject = LearningMasterySubject.MATHEMATICS,
                selectedSection = "其他",
            )

        assertEquals(listOf("其他", "函数", "导数"), names)
        assertEquals(listOf("a", "c"), filtered.map { it.key.opaqueValue })
        assertEquals(listOf("d"), other.map { it.key.opaqueValue })
        assertEquals(
            items.map { it.key.opaqueValue },
            learningMasterySectionItems(
                items = items,
                subject = LearningMasterySubject.MATHEMATICS,
                selectedSection = null,
            ).map { it.key.opaqueValue },
        )
    }

    private fun knowledge(
        key: String,
        status: LearningMasteryStatus,
        path: List<String> = listOf("数学", "函数"),
    ) = LearningMasteryKnowledgeItem(
        key = LearningMasteryKnowledgeKey.fromOpaque(key),
        displayName = key,
        displayPath = path,
        status = status,
        trend = LearningMasteryTrend.STEADY,
    )

    private fun subject(
        subject: LearningMasterySubject,
        status: LearningMasteryStatus,
    ) = LearningMasterySubjectOverview(
        subject = subject,
        status = status,
        trend = LearningMasteryTrend.NO_CLEAR_CHANGE,
    )
}
