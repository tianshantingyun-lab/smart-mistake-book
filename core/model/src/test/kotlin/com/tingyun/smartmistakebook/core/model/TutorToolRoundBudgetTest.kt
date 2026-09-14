package com.tingyun.smartmistakebook.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轮内结果预算（spec model-intent-routing §3.1）。单工具上限管不到"一轮加起来"，
 * 所以这里守的是**总额**：三个 2k 结果不能把 6k 塞进下一轮 prompt。
 *
 * 被压缩的结果必须**说出来**——静默变短的清单会被读成完整清单，模型据此
 * 以为没有更多可查。这条性质是这些用例的核心，不是顺带断言的。
 */
class TutorToolRoundBudgetTest {

    @Test
    fun outcomesThatFitArePassedThroughUntouched() {
        val outcomes = listOf(outcome("第一个结果"), outcome("第二个结果"))

        val budgeted = budgetTutorToolOutcomes(outcomes, budgetChars = 4_000)

        assertEquals(outcomes, budgeted)
    }

    @Test
    fun theRoundTotalIsCappedEvenWhenEachOutcomeIsWithinItsOwnBudget() {
        // 三条各 2k 的结果（各自都在单工具上限内）合计 6k，必须被压到轮预算里。
        val outcomes = (1..3).map { outcome("x".repeat(2_000) + it) }

        val budgeted = budgetTutorToolOutcomes(
            outcomes,
            budgetChars = TutorToolRoundResult.MAX_TOOL_ROUND_RESULT_CHARS,
        )

        val total = budgeted.sumOf { it.summaryMarkdown.length }
        assertTrue(
            "round total $total exceeds the round budget",
            total <= TutorToolRoundResult.MAX_TOOL_ROUND_RESULT_CHARS,
        )
    }

    @Test
    fun whatDoesNotFitSaysSoInsteadOfSilentlyDisappearing() {
        // 前两条把预算吃光，第三条必须留下"未随请求发送"的说明，而不是变成一个空摘要
        // 或干脆消失。第三条本身很长，确保它是真的放不下。
        val outcomes = listOf(
            outcome("a".repeat(2_000)),
            outcome("b".repeat(2_000)),
            outcome("第三个结果".repeat(500)),
        )

        val budgeted = budgetTutorToolOutcomes(outcomes, budgetChars = 4_000)

        assertEquals(3, budgeted.size)
        assertEquals(DROPPED_OUTCOME_SUMMARY, budgeted.last().summaryMarkdown)
    }

    @Test
    fun admittingADroppedOutcomeStillFitsInsideTheBudget() {
        // 这条是上一条的另一半：写出"未发送"本身也要占字符，所以每一步都得为后面几条
        // 预留那一行。没有预留时总额会反超——预算门就成了摆设。
        val outcomes = listOf(
            outcome("a".repeat(2_000)),
            outcome("b".repeat(2_000)),
            outcome("c".repeat(2_000)),
            outcome("d".repeat(2_000)),
        )

        val budgeted = budgetTutorToolOutcomes(outcomes, budgetChars = 4_000)

        val total = budgeted.sumOf { it.summaryMarkdown.length } + budgeted.size - 1
        assertTrue("round total $total exceeds 4000", total <= 4_000)
        assertEquals(DROPPED_OUTCOME_SUMMARY, budgeted.last().summaryMarkdown)
    }

    @Test
    fun aSqueezedOutcomeKeepsItsExecutionVerdict() {
        // 压缩的是文本，不是执行结论：工具确实跑过了，ok/errorKind 不能被改写。
        val failing = TutorToolOutcome(
            tool = TutorToolName.KNOWLEDGE_READ,
            ok = false,
            summaryMarkdown = "y".repeat(2_000),
            errorKind = "failed",
        )

        val budgeted = budgetTutorToolOutcomes(
            listOf(outcome("x".repeat(2_000)), failing),
            budgetChars = 4_000,
        )

        assertEquals(false, budgeted.last().ok)
        assertEquals("failed", budgeted.last().errorKind)
    }

    @Test
    fun truncationKeepsAUsablePrefixAndNamesTheTruncation() {
        val outcomes = listOf(
            outcome("a".repeat(1_000)),
            outcome("开头内容" + "b".repeat(3_000)),
        )

        val budgeted = budgetTutorToolOutcomes(outcomes, budgetChars = 2_000)

        val squeezed = budgeted.last().summaryMarkdown
        assertTrue("prefix lost: $squeezed", squeezed.startsWith("开头内容"))
        assertTrue("truncation not named: $squeezed", squeezed.contains(TRUNCATED_OUTCOME_NOTE))
    }

    @Test
    fun aBudgetTooSmallForAnyOutcomeIsRefusedRatherThanSilentlyEmptyingThem() {
        assertThrows(IllegalArgumentException::class.java) {
            budgetTutorToolOutcomes(listOf(outcome("结果")), budgetChars = 3)
        }
    }

    // ---- 轮结果的构成（把预算算术放进 model 层，使它整体可测） ----

    @Test
    fun theRoundResultIsBuiltWithTheBudgetApplied() {
        val outcomes = (1..3).map { outcome("x".repeat(2_000) + it) }

        val round = tutorToolRoundResult(
            roundOrdinal = 1,
            outcomes = outcomes,
            extendedResultUsed = false,
        )

        val total = round.outcomes.sumOf { it.summaryMarkdown.length } + round.outcomes.size - 1
        assertTrue("round total $total exceeds the base budget", total <= 4_000)
    }

    @Test
    fun anExtendedRoundGetsTheLargerTotalSoTheExtendedResultIsReachable() {
        // 这条锁的是你批的两项之间的相互作用：若轮总额不随扩展调用提高，单个 6k 结果
        // 会被 4k 的轮门砍掉——扩展预算就成了永远生效不了的空头承诺。
        val extended = outcome("a".repeat(5_000))
        val plain = outcome("b".repeat(1_500))

        val baseRound = tutorToolRoundResult(1, listOf(extended, plain), extendedResultUsed = false)
        val extendedRound = tutorToolRoundResult(1, listOf(extended, plain), extendedResultUsed = true)

        assertTrue(
            "the 5k result should not survive the base round budget",
            baseRound.outcomes.first().summaryMarkdown.length < 5_000,
        )
        assertEquals(5_000, extendedRound.outcomes.first().summaryMarkdown.length)
    }

    private fun outcome(summary: String) = TutorToolOutcome(
        tool = TutorToolName.MASTERY_READ,
        ok = true,
        summaryMarkdown = summary,
    )
}
