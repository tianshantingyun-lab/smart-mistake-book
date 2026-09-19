package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorRespondInput
import com.tingyun.smartmistakebook.core.model.TutorRespondOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The budget is enforced twice: here by trimming, and inside the tutor input
 * types by a `require`. The lobby route used to skip the trim and rely on the
 * `require`, which turned a long conversation into a permanent send failure —
 * so these tests pin the trimming behaviour, including the boundaries where
 * the two budgets meet.
 *
 * Fixtures respect the per-entry caps ([TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS]
 * and [TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS]); a single exchange cannot
 * exceed the aggregate budget, so the character cases accumulate.
 */
class TutorHistoryBudgetTest {

    /** One whole exchange; the first character of each field is the marker. */
    private fun entry(studentChars: Int, assistantChars: Int, marker: String) =
        TutorChatHistoryEntry(
            studentMessage = marker.padEnd(studentChars, 'x'),
            assistantMarkdown = marker.padEnd(assistantChars, 'y'),
        )

    private fun markers(entries: List<TutorChatHistoryEntry>): List<String> =
        entries.map { it.studentMessage.take(1) }

    private val charBudget = TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS
    private val messageBudget = TutorRespondInput.MAX_PRIOR_MESSAGES
    private val maxStudent = TutorRespondInput.MAX_STUDENT_MESSAGE_CHARS
    private val maxAssistant = TutorRespondOutput.MAX_MESSAGE_MARKDOWN_CHARS

    /** A full-size exchange: the largest single entry this contract permits. */
    private fun fullEntry(marker: String): TutorChatHistoryEntry {
        val perEntry = charBudget / 2
        return entry(maxStudent, perEntry - maxStudent, marker)
    }

    /** A reply-heavy exchange: the assistant body alone is the whole budget. */
    private fun replyHeavyEntry(marker: String): TutorChatHistoryEntry =
        entry(1, maxAssistant, marker)

    @Test
    fun `an empty history stays empty`() {
        assertTrue(TutorHistoryBudget.bounded(emptyList()).isEmpty())
    }

    @Test
    fun `a history inside both budgets is returned whole in chronological order`() {
        val entries = listOf(entry(10, 10, "a"), entry(10, 10, "b"), entry(10, 10, "c"))

        val bounded = TutorHistoryBudget.bounded(entries)

        assertEquals(listOf("a", "b", "c"), markers(bounded))
    }

    @Test
    fun `the oldest exchanges beyond the message count are dropped`() {
        val entries = ('a'..'j').map { letter -> entry(1, 0, letter.toString()) }

        val bounded = TutorHistoryBudget.bounded(entries)

        assertEquals(messageBudget, bounded.size)
        // The newest eight survive; the two oldest are gone.
        assertEquals(listOf("c", "d", "e", "f", "g", "h", "i", "j"), markers(bounded))
    }

    @Test
    fun `a history totalling exactly the character budget is kept`() {
        val entries = listOf(fullEntry("a"), fullEntry("b"))

        val bounded = TutorHistoryBudget.bounded(entries)

        assertEquals(listOf("a", "b"), markers(bounded))
    }

    @Test
    fun `the exchange that crosses the character budget and everything older is dropped`() {
        val entries = listOf(fullEntry("a"), fullEntry("b"), fullEntry("c"))

        val bounded = TutorHistoryBudget.bounded(entries)

        assertEquals(listOf("b", "c"), markers(bounded))
    }

    @Test
    fun `a long reply alone can push its exchange out of the budget`() {
        val entries = listOf(
            replyHeavyEntry("a"),
            fullEntry("b"),
            entry(1, 0, "c"),
        )

        val bounded = TutorHistoryBudget.bounded(entries)

        assertEquals(listOf("b", "c"), markers(bounded))
    }
}
