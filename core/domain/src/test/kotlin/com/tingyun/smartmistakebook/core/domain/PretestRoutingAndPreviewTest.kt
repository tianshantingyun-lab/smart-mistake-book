package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.domain.NewIntroductionPolicy.IntakeCandidate
import com.tingyun.smartmistakebook.core.domain.PretestRouting.ItemCapabilities
import com.tingyun.smartmistakebook.core.domain.PretestRouting.PretestSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PretestRoutingTest {

    @Test
    fun `item with options routes to the machine scored choice flow`() {
        val route = PretestRouting.routeForNewItem(
            ItemCapabilities(hasOptions = true, hasAnswerSpec = true),
        )
        assertEquals(PretestSurface.CHOICE_FLOW, route)
        assertTrue(PretestRouting.producesRealAttempt(route))
    }

    @Test
    fun `free response with answer spec and tutor routes to the tutor judged flow`() {
        val route = PretestRouting.routeForNewItem(
            ItemCapabilities(hasOptions = false, hasAnswerSpec = true, tutorAvailable = true),
        )
        assertEquals(PretestSurface.TUTOR_JUDGED_FLOW, route)
        assertTrue(PretestRouting.producesRealAttempt(route))
    }

    @Test
    fun `free response without answer spec still routes to the tutor judge`() {
        // 2026-09-14 修正：讲题判定不要求 answer spec——开放式作答由模型语义判断，
        // 本地只核对引文。生产线上的真实错题都没有 answer spec，若这里退回
        // UNAVAILABLE，每道错题都会变成复习不动的死项。
        val route = PretestRouting.routeForNewItem(
            ItemCapabilities(hasOptions = false, hasAnswerSpec = false, tutorAvailable = true),
        )
        assertEquals(PretestSurface.TUTOR_JUDGED_FLOW, route)
        assertTrue(PretestRouting.producesRealAttempt(route))
    }

    @Test
    fun `without a tutor the item is unavailable regardless of an answer spec`() {
        val route = PretestRouting.routeForNewItem(
            ItemCapabilities(hasOptions = false, hasAnswerSpec = true, tutorAvailable = false),
        )
        // 自评兜底已拆（2026-09-13）：既无机判也无判定就如实停下，不写任何"学生自报"的对错证据。
        assertEquals(PretestSurface.UNAVAILABLE, route)
        assertFalse(PretestRouting.producesRealAttempt(route))
    }

    @Test
    fun `no tutor available routes free response to unavailable`() {
        val route = PretestRouting.routeForNewItem(
            ItemCapabilities(hasOptions = false, hasAnswerSpec = true, tutorAvailable = false),
        )
        assertEquals(PretestSurface.UNAVAILABLE, route)
    }

    @Test
    fun `options outrank answer spec even without a tutor`() {
        val route = PretestRouting.routeForNewItem(
            ItemCapabilities(hasOptions = true, hasAnswerSpec = false, tutorAvailable = false),
        )
        assertEquals(PretestSurface.CHOICE_FLOW, route)
    }
}

class PretestScoringModeAssemblyTest {

    @Test
    fun `auto verified mode assembles a machine scorable item`() {
        val caps = PretestRouting.ItemCapabilities.fromScoringMode(
            scoringMode = "AUTO_VERIFIED",
            hasOptions = true,
            hasAnswerSpec = true,
        )
        assertEquals(
            PretestSurface.CHOICE_FLOW,
            PretestRouting.routeForNewItem(caps),
        )
    }

    @Test
    fun `rubric assisted mode needs the tutor judge`() {
        val caps = PretestRouting.ItemCapabilities.fromScoringMode(
            scoringMode = "RUBRIC_ASSISTED",
            hasOptions = false,
            hasAnswerSpec = true,
        )
        assertEquals(
            PretestSurface.TUTOR_JUDGED_FLOW,
            PretestRouting.routeForNewItem(caps),
        )
    }

    @Test
    fun `user self report mode stays unavailable regardless of structural signals`() {
        // A self-report item is metacognitive only — even if the row carries
        // an answer spec, it must never route to a machine-scored surface,
        // and since 2026-09-13 there is no self-report fallback to fall into.
        val caps = PretestRouting.ItemCapabilities.fromScoringMode(
            scoringMode = "USER_SELF_REPORT",
            hasOptions = true,
            hasAnswerSpec = true,
        )
        assertEquals(PretestSurface.UNAVAILABLE, PretestRouting.routeForNewItem(caps))
        assertFalse(PretestRouting.producesRealAttempt(PretestSurface.UNAVAILABLE))
    }

    @Test
    fun `unknown scoring mode falls back to structural signals`() {
        val caps = PretestRouting.ItemCapabilities.fromScoringMode(
            scoringMode = null,
            hasOptions = true,
            hasAnswerSpec = false,
        )
        assertEquals(PretestSurface.CHOICE_FLOW, PretestRouting.routeForNewItem(caps))
    }
}

class IntakePreviewAssemblyTest {

    private fun candidate(id: String, seconds: Int) = IntakeCandidate(
        practiceUnitId = id,
        estimatedDurationSeconds = seconds,
        createdAtEpochMillis = 0,
    )

    @Test
    fun `empty backlog previews zeroed`() {
        val preview = NewIntroductionPolicy.previewBacklog(emptyList(), 1800)
        assertEquals(0, preview.perDayItems)
        assertEquals(0, preview.daysToCover)
        assertEquals(0, preview.backlogCount)
    }

    @Test
    fun `backlog preview simulates the real per day policy`() {
        // Durations: 60, 60, 1200 in an 1800s budget. Slice = 600s, cap =
        // 300s. Day 1: the two 60s items fit (120s), the 1200s item is
        // oversized (> cap) and defers. Day 2: only the 1200s item remains,
        // all-oversized guard expands the day to the full budget and admits
        // it (1200 ≤ 1800). Total 2 days.
        val preview = NewIntroductionPolicy.previewBacklog(
            backlog = listOf(candidate("a", 60), candidate("b", 60), candidate("c", 1200)),
            timeBudgetSeconds = 1800,
        )
        assertEquals(2, preview.daysToCover)
        assertEquals(0, preview.unschedulableCount)
    }

    @Test
    fun `item larger than a whole daily budget is unschedulable not looped`() {
        // 60s items + one 4000s item in a 1200s budget: the 4000s item can
        // never fit ANY day — the preview must count it unschedulable instead
        // of simulating forever.
        val preview = NewIntroductionPolicy.previewBacklog(
            backlog = listOf(candidate("a", 60), candidate("b", 60), candidate("c", 4000)),
            timeBudgetSeconds = 1200,
        )
        assertEquals(1, preview.unschedulableCount)
        // The 60s items still schedule: slice 400s / 60 = 6/day → 1 day.
        assertEquals(1, preview.daysToCover)
    }

    @Test
    fun `exam catch up shortens the coverage horizon`() {
        val backlog = (1..30).map { candidate("u-$it", 100) }
        val plain = NewIntroductionPolicy.previewBacklog(backlog, timeBudgetSeconds = 1200)
        // Slice 400s / 100s = 4/day → 30 items in 8 days (ceil).
        assertEquals(4, plain.perDayItems)
        assertEquals(8, plain.daysToCover)
        assertEquals(0, plain.unschedulableCount)

        // Exam in 5 days: quota 6/day → but per-day cap by budget = 12 → 6/day → 5 days.
        val exam = NewIntroductionPolicy.previewBacklog(backlog, timeBudgetSeconds = 1200, daysLeftToExam = 5)
        assertEquals(6, exam.perDayItems)
        assertEquals(5, exam.daysToCover)
    }
}
