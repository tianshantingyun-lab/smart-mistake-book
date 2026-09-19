package com.tingyun.smartmistakebook.core.domain

import com.tingyun.smartmistakebook.core.model.TutorChatHistoryEntry
import com.tingyun.smartmistakebook.core.model.TutorRespondInput

/**
 * Whole-exchange windowing for tutor prior history, shared by the session
 * (Respond) and lobby routes.
 *
 * Both routes hand their history to an input type whose `init` enforces the
 * same two budgets — `MAX_PRIOR_MESSAGES` and `MAX_PRIOR_MESSAGE_CHARS` — and
 * that enforcement is a `require`, not a trim. A caller that hands over an
 * over-budget history therefore throws inside the input's constructor rather
 * than sending a shorter conversation.
 *
 * That asymmetry used to be fatal on the lobby route only: the session route
 * trimmed here, but the lobby route took the newest eight exchanges and
 * checked no character budget at all, so a long enough conversation made every
 * subsequent send throw. The lobby's generic `catch (e: Exception)` classified
 * that as `NETWORK_UNAVAILABLE` and offered a retry that reproduced the same
 * throw — the conversation could never advance again. Both routes now trim
 * through this one function, which is also why the budgets are read from
 * [TutorRespondInput] rather than passed in: the lobby input aliases those
 * exact constants, and a shared source cannot drift.
 *
 * Newest exchanges win: the walk runs newest-to-oldest and stops at the first
 * exchange that would breach either budget, dropping everything older. The
 * character budget is therefore crossed by accumulation, never by one oversized
 * exchange — a single [TutorChatHistoryEntry] is itself capped at 1200 student
 * and 12000 assistant characters, well under the 24000 aggregate. A partially
 * kept exchange is never produced, because a truncated reply would
 * misrepresent what the model actually said.
 */
object TutorHistoryBudget {

    /**
     * The newest whole exchanges that fit both budgets, in chronological
     * order (oldest first), ready to hand to a tutor input's `priorMessages`.
     */
    fun bounded(entries: List<TutorChatHistoryEntry>): List<TutorChatHistoryEntry> {
        var totalChars = 0
        return buildList {
            for (entry in entries.asReversed()) {
                val entryChars = entry.studentMessage.length + entry.assistantMarkdown.length
                if (
                    size >= TutorRespondInput.MAX_PRIOR_MESSAGES ||
                    totalChars + entryChars > TutorRespondInput.MAX_PRIOR_MESSAGE_CHARS
                ) {
                    break
                }
                add(entry)
                totalChars += entryChars
            }
        }.asReversed()
    }
}
