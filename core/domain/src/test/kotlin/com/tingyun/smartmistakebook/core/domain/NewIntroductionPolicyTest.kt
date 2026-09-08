package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.domain.NewIntroductionPolicy.IntakeCandidate
import com.tingyun.smartmistakebook.core.model.TutorUnderstandingTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewIntroductionPolicyTest {

    private fun candidate(
        id: String,
        seconds: Int,
        examPriority: Double = 0.0,
        confidenceAtError: ConfidenceLevel? = null,
        createdAt: Long = 0,
    ) = IntakeCandidate(
        practiceUnitId = id,
        estimatedDurationSeconds = seconds,
        examPriority = examPriority,
        confidenceAtError = confidenceAtError,
        createdAtEpochMillis = createdAt,
    )

    @Test
    fun `introductions are capped by the time share not by count`() {
        // Budget 600s → slice = 200s; four 90s candidates → only two fit.
        val decision = NewIntroductionPolicy.decide(
            candidates = listOf(candidate("a", 90), candidate("b", 90), candidate("c", 90), candidate("d", 90)),
            timeBudgetSeconds = 600,
        )
        assertEquals(listOf("a", "b"), decision.introduced.map { it.practiceUnitId })
        assertEquals(listOf("c", "d"), decision.deferred.map { it.practiceUnitId })
        assertEquals(180, decision.grantedSliceSeconds)
    }

    @Test
    fun `exam catch up grows the slice to fit the remaining backlog per day`() {
        // 600s budget; 12 candidates × 100s; exam in 4 days → quota 3/day
        // → slice grows to 300s (3 × 100) so 3 fit instead of 2.
        val candidates = (1..12).map { candidate("u-$it", 100) }
        val decision = NewIntroductionPolicy.decide(
            candidates = candidates,
            timeBudgetSeconds = 600,
            daysLeftToExam = 4,
        )
        assertEquals(3, decision.introduced.size)
        assertTrue(decision.grantedSliceSeconds >= 300)
    }

    @Test
    fun `exam catch up never exceeds the whole budget`() {
        val candidates = (1..30).map { candidate("u-$it", 100) }
        val decision = NewIntroductionPolicy.decide(
            candidates = candidates,
            timeBudgetSeconds = 600,
            daysLeftToExam = 1,
        )
        // Quota wants 30 items (3000s) but the whole budget is 600s and each
        // item is capped at half the effective slice.
        assertTrue(decision.introduced.size * 100 <= 600)
        assertTrue(decision.grantedSliceSeconds <= 600)
    }

    @Test
    fun `priority order is exam then hypercorrection then fifo`() {
        // Budget 540s → slice 180s, exactly three 60s candidates fit.
        val decision = NewIntroductionPolicy.decide(
            candidates = listOf(
                candidate("plain", 60, createdAt = 100),
                candidate("exam", 60, examPriority = 1.0, createdAt = 50),
                candidate("hyper", 60, confidenceAtError = ConfidenceLevel.HIGH, createdAt = 200),
            ),
            timeBudgetSeconds = 540,
        )
        assertEquals(listOf("exam", "hyper", "plain"), decision.introduced.map { it.practiceUnitId })
    }

    @Test
    fun `an oversized estimate is deferred not silently capped`() {
        // Slice = 200s; cap = 100s. The 500s candidate's TRUE solving time
        // would overshoot the promised slice, so it must NOT be introduced
        // today (no silent over-commit of the student's time); the 90s
        // companion fits.
        val decision = NewIntroductionPolicy.decide(
            candidates = listOf(candidate("huge", 500), candidate("small", 90)),
            timeBudgetSeconds = 600,
        )
        assertEquals(listOf("small"), decision.introduced.map { it.practiceUnitId })
        assertEquals(listOf("huge"), decision.deferred.map { it.practiceUnitId })
        assertEquals(90, decision.grantedSliceSeconds)
    }

    @Test
    fun `deferred items carry no state and can be introduced tomorrow`() {
        val decision = NewIntroductionPolicy.decide(
            candidates = listOf(candidate("a", 90), candidate("b", 90), candidate("c", 90)),
            timeBudgetSeconds = 600,
        )
        assertTrue(decision.deferred.isNotEmpty())
        // Pure function: same input reproduces the same decision.
        val again = NewIntroductionPolicy.decide(
            candidates = listOf(candidate("a", 90), candidate("b", 90), candidate("c", 90)),
            timeBudgetSeconds = 600,
        )
        assertEquals(decision.introduced.map { it.practiceUnitId }, again.introduced.map { it.practiceUnitId })
    }

    @Test
    fun `preview computes days to cover from the time share`() {
        // Slice = 1/3 × 1800 = 600s; 90s items → 6/day (wait: 600/90 = 6.67 → 6).
        val preview = NewIntroductionPolicy.preview(
            backlogCount = 60,
            typicalItemSeconds = 90,
            timeBudgetSeconds = 1800,
        )
        assertEquals(6, preview.perDayItems)
        assertEquals(10, preview.daysToCover)
    }

    @Test
    fun `preview exam catch up accelerates coverage within budget`() {
        // No exam: 60 items at 100s in a 1200s budget → slice 400 → 4/day → 15 days.
        val plain = NewIntroductionPolicy.preview(60, 100, 1200)
        assertEquals(4, plain.perDayItems)
        assertEquals(15, plain.daysToCover)
        // Exam in 6 days: quota 10/day; whole budget fits 12 → 10/day → 6 days.
        val exam = NewIntroductionPolicy.preview(60, 100, 1200, daysLeftToExam = 6)
        assertEquals(10, exam.perDayItems)
        assertEquals(6, exam.daysToCover)
    }

    @Test
    fun `preview of an empty backlog is zeroed`() {
        val preview = NewIntroductionPolicy.preview(0, 90, 1800)
        assertEquals(0, preview.perDayItems)
        assertEquals(0, preview.daysToCover)
        assertEquals(0, preview.backlogCount)
    }
}

class AttemptConfidenceFluentErrorTest {

    @Test
    fun `a fluent wrong attempt reads as a confident error`() {
        // Zero switches, zero away, zero back-tracking: the student saw no
        // problem — hypercorrection ordering input (Butterfield & Metcalfe).
        val assessment = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(
                attentionFactor = 1.0,
                scrollUpCount = 0,
                answerWasWrong = true,
            ),
        )
        assertEquals(ConfidenceLevel.HIGH, assessment.level)
    }

    @Test
    fun `a distracted or hesitant wrong attempt stays low`() {
        val distracted = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(
                attentionFactor = 0.6,
                scrollUpCount = 0,
                answerWasWrong = true,
            ),
        )
        assertEquals(ConfidenceLevel.LOW, distracted.level)

        val hesitant = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(
                attentionFactor = 1.0,
                scrollUpCount = 4,
                answerWasWrong = true,
            ),
        )
        assertEquals(ConfidenceLevel.LOW, hesitant.level)
    }
}

class AttemptConfidenceAssessmentTest {

    @Test
    fun `self report decides and objective distraction downgrades overconfident high`() {
        val plain = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(selfReport = ConfidenceLevel.HIGH),
        )
        assertEquals(ConfidenceLevel.HIGH, plain.level)
        assertEquals("self_report", plain.decidedBy)

        // A claimed-HIGH under objective distraction is untrustworthy
        // (divided attention impairs encoding) — downgrade ALL the way to LOW.
        val distracted = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(
                selfReport = ConfidenceLevel.HIGH,
                attentionFactor = 0.5,
            ),
        )
        assertEquals(ConfidenceLevel.LOW, distracted.level)
    }

    @Test
    fun `model semantics decides when no self report`() {
        val confident = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(modelTier = TutorUnderstandingTier.CONFIDENT),
        )
        assertEquals(ConfidenceLevel.HIGH, confident.level)
        assertEquals("model_semantics", confident.decidedBy)

        val struggling = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(modelTier = TutorUnderstandingTier.STRUGGLING),
        )
        assertEquals(ConfidenceLevel.LOW, struggling.level)
    }

    @Test
    fun `hesitant scrolling downgrades model high confidence to low`() {
        val assessment = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(
                modelTier = TutorUnderstandingTier.MASTERED,
                scrollUpCount = 5,
            ),
        )
        assertEquals(ConfidenceLevel.LOW, assessment.level)
    }

    @Test
    fun `objective only path infers low when distracted and medium when ordinary`() {
        val distracted = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(attentionFactor = 0.6, answerWasWrong = true),
        )
        assertEquals(ConfidenceLevel.LOW, distracted.level)
        assertEquals("objective_signals", distracted.decidedBy)

        // Hesitant (scroll 4) + wrong → LOW, not fluent.
        val hesitantWrong = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(
                attentionFactor = 0.9,
                scrollUpCount = 4,
                answerWasWrong = true,
            ),
        )
        assertEquals(ConfidenceLevel.LOW, hesitantWrong.level)
    }

    @Test
    fun `a brief single switch is still fluent enough for the confident error proxy`() {
        // One switch with no away time gives attentionFactor = 1 - 0.12 =
        // 0.88 (AttentionSignal.FREE_SWITCH_ALLOWANCE) — a notification
        // glance is normal, not hesitation.
        val fluent = AttemptConfidenceAssessment.assess(
            AttemptConfidenceAssessment.Signals(
                attentionFactor = AttemptConfidenceAssessment.FLUENT_ATTENTION_FLOOR,
                scrollUpCount = 0,
                answerWasWrong = true,
            ),
        )
        assertEquals(ConfidenceLevel.HIGH, fluent.level)
    }
}
