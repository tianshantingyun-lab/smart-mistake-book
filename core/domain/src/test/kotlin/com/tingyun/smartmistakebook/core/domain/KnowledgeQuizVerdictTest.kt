package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorEvidenceDirection
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 知识点复习作答回写判决（spec dual-review-entry §3.4）：客观对错 → 掌握度证据语义的
 * 确定映射。答对 → POSITIVE+CONFIDENT+行为佐证；答错 → NEGATIVE+STRUGGLING。
 */
class KnowledgeQuizVerdictTest {

    @Test
    fun correctAnswerMapsToPositiveConfidentWithBehavioralSupport() {
        val verdict = knowledgeQuizMasteryVerdict(isCorrect = true)
        assertEquals(TutorEvidenceDirection.POSITIVE, verdict.direction)
        assertEquals(TutorUnderstandingTier.CONFIDENT, verdict.understanding)
        assertEquals(true, verdict.hasBehavioralSupport)
    }

    @Test
    fun incorrectAnswerMapsToNegativeStruggling() {
        val verdict = knowledgeQuizMasteryVerdict(isCorrect = false)
        assertEquals(TutorEvidenceDirection.NEGATIVE, verdict.direction)
        assertEquals(TutorUnderstandingTier.STRUGGLING, verdict.understanding)
        assertEquals(false, verdict.hasBehavioralSupport)
    }
}
