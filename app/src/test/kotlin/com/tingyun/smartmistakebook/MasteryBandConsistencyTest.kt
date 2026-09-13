package com.tingyun.smartmistakebook

import com.tingyun.smartmistakebook.core.domain.StudyKnowledgeSummary
import com.tingyun.smartmistakebook.core.model.MasteryStatus
import com.tingyun.smartmistakebook.core.ui.MASTERY_FAIR_THRESHOLD
import com.tingyun.smartmistakebook.core.ui.MASTERY_STRONG_THRESHOLD
import com.tingyun.smartmistakebook.core.ui.masteryBandLabel
import com.tingyun.smartmistakebook.core.ui.retentionBandLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 同一张卡的「掌握程度」与「遗忘风险」必须落在**同一对切点**上（审计 R-03）。
 *
 * 消灭的失败：这两档措辞不同（较稳／一般／薄弱 vs 低／中／高）、分处两个模块，此前**各自**
 * 写死 `0.7` 与 `0.4`。把其中一处调成 0.75，同一张卡就会同时显示「较稳」和「遗忘风险 高」
 * ——界面上看得见的自相矛盾，而两处各自的用例都只断言自己那一条，**不会有任何测试变红**。
 *
 * 判别性的那一格是**恰好等于切点**：`>= 0.7` 与 `> 0.7` 只在边界上不同，而"该说哪个词"
 * 的分歧也正是在边界上第一次显形。所以三条用例各钉一个边界而不是各钉一个区间中点。
 */
class MasteryBandConsistencyTest {

    @Test
    fun theStrongBoundaryAgreesBetweenMasteryAndForgettingRisk() {
        // 恰好落在强档切点上：掌握说「较稳」，风险就必须说「低」。
        assertEquals("较稳", masteryBandLabel(MASTERY_STRONG_THRESHOLD))
        assertEquals("低", forgettingRiskLabel(summaryAt(MASTERY_STRONG_THRESHOLD)))

        // 切点下方一格：两者必须**同时**换档，不能只有一个换。
        val justBelow = MASTERY_STRONG_THRESHOLD - 0.0001
        assertEquals("一般", masteryBandLabel(justBelow))
        assertEquals("中", forgettingRiskLabel(summaryAt(justBelow)))
    }

    @Test
    fun theFairBoundaryAgreesBetweenMasteryAndForgettingRisk() {
        assertEquals("一般", masteryBandLabel(MASTERY_FAIR_THRESHOLD))
        assertEquals("中", forgettingRiskLabel(summaryAt(MASTERY_FAIR_THRESHOLD)))

        val justBelow = MASTERY_FAIR_THRESHOLD - 0.0001
        assertEquals("薄弱", masteryBandLabel(justBelow))
        assertEquals("高", forgettingRiskLabel(summaryAt(justBelow)))
    }

    @Test
    fun theRetentionBandSharesTheSameCutPoints() {
        // 保持率读的是另一个量（快照时的可提取率），但用同一对切点是有意的：
        // 两个量都在 [0,1] 上，语义都是"学生对它有多牢"。
        assertEquals("记忆较稳", retentionBandLabel(MASTERY_STRONG_THRESHOLD))
        assertEquals("记忆减弱", retentionBandLabel(MASTERY_STRONG_THRESHOLD - 0.0001))
        assertEquals("记忆减弱", retentionBandLabel(MASTERY_FAIR_THRESHOLD))
        assertEquals("记忆模糊", retentionBandLabel(MASTERY_FAIR_THRESHOLD - 0.0001))
    }

    @Test
    fun theCutPointsAreTheDocumentedOnes() {
        // 常量本身也钉住：改数值应当是**有意的**，不是顺手。
        assertEquals(0.7, MASTERY_STRONG_THRESHOLD, 0.0)
        assertEquals(0.4, MASTERY_FAIR_THRESHOLD, 0.0)
    }

    private fun summaryAt(mastery: Double) = StudyKnowledgeSummary(
        knowledgeNodeId = "knowledge:band-test",
        displayName = "切点用例",
        status = MasteryStatus.LEARNING,
        conservativeMasteryScore = mastery,
    )
}
